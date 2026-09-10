package nova

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	pt "gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/goptlib"
	"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/transports/base"
	"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/transports/meeklite"
	"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/transports/obfs4"
	"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/transports/webtunnel"
)

// Pluggable transports Tor внутри ядра, а не отдельным процессом.
//
// Зачем. Tor умеет ходить к мостам через «внешний» pluggable transport:
// `ClientTransportPlugin <имя> socks5 127.0.0.1:порт`. Тогда весь исходящий
// трафик tor'а — это соединения на localhost, а настоящие сокеты к мостам
// открываем мы. Отсюда два следствия, ради которых всё и сделано так:
//
//   - **Сокеты наши.** `VpnService.protect()` применим только к своим
//     дескрипторам; сокеты чужого процесса (готовый бинарь lyrebird) пометить
//     нечем (G154). Здесь пометка ставится в `protectedDial`.
//   - **Размер.** Готовый Lyrebird — 33,8 МБ на две архитектуры. Здесь те же
//     транспорты берутся библиотекой в уже существующий `libgojni.so`.
//
// Что это НЕ делает: не заменяет tor. SOCKS этого прокси ведёт на ORPort моста,
// говорить с ним умеет только сам tor.
//
// Транспорт выбирается по имени (см. `ptTransports`), поэтому obfs4, webtunnel
// и meek_lite обслуживаются одним и тем же кодом: различия целиком лежат в
// аргументах моста, а их присылает сам tor.
//
// Протокол разговора с tor описан в pt-spec (SOCKS5, RFC 1928): аргументы моста
// (`cert=…;iat-mode=0`, `url=…;front=…` и подобные) приезжают в полях логина и
// пароля, разрезанные по 255 байт, адрес моста — в самом запросе CONNECT.

const (
	ptDialTimeout     = 60 * time.Second
	ptAuthTimeout     = 15 * time.Second
	ptMaxSessions     = 64
	socks5Version     = 0x05
	socks5AuthNone    = 0x00
	socks5AuthUserPwd = 0x02
	socks5AuthNoneOk  = 0xFF
	socks5CmdConnect  = 0x01
	socks5AtypIPv4    = 0x01
	socks5AtypDomain  = 0x03
	socks5AtypIPv6    = 0x04
)

type ptProxy struct {
	name     string
	listener net.Listener
	factory  base.ClientFactory

	sessions atomic.Int64
	accepted atomic.Int64
	failed   atomic.Int64

	closeOnce sync.Once
}

var (
	ptMu      sync.Mutex
	ptRunning = map[string]*ptProxy{}
)

// Реестр собран руками, а не взят у lyrebird целиком.
//
// `transports.Init()` регистрирует заодно snowflake, а тот тянет за собой
// pion/webrtc, AWS SDK и `wlynxg/anet`. Последний лезет в `net.zoneCache`
// через `go:linkname`, и сборка падает на компоновке: `link:
// github.com/wlynxg/anet: invalid reference to net.zoneCache`. Лечится это
// только `-checklinkname=0` у всего ядра, то есть снятием проверки компоновщика
// ради одного транспорта — и вместе с ней приезжают мегабайты WebRTC. Поэтому
// здесь ровно те транспорты, которые нужны и ничего лишнего не тянут.
var ptTransports = map[string]base.Transport{
	"obfs4":     new(obfs4.Transport),
	"webtunnel": webtunnel.Transport,
	"meek_lite": new(meeklite.Transport),
}

// StartTorPtProxy поднимает локальный SOCKS5 с названным транспортом.
//
// Слушать разрешено только петлю: за этим адресом нет никакой проверки прав, и
// открытый наружу порт превратился бы в чужой прокси до моста. Пустой
// `listenAddr` означает «127.0.0.1 и любой свободный порт» — возвращается
// фактический адрес, его и надо записать в torrc.
//
// `stateDir` нужен транспортам, которые хранят состояние между запусками
// (snowflake, meek_lite); obfs4 и webtunnel им не пользуются.
func StartTorPtProxy(name string, listenAddr string, stateDir string) (string, error) {
	ptMu.Lock()
	defer ptMu.Unlock()

	transportName := strings.TrimSpace(strings.ToLower(name))
	if transportName == "" {
		return "", errors.New("pt: имя транспорта не задано")
	}
	if existing, ok := ptRunning[transportName]; ok {
		return existing.listener.Addr().String(), nil
	}

	address := strings.TrimSpace(listenAddr)
	if address == "" {
		address = "127.0.0.1:0"
	}
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return "", fmt.Errorf("pt: адрес %q неразбираем: %w", address, err)
	}
	if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
		return "", fmt.Errorf("pt: слушать можно только петлю, а не %q", host)
	}

	transport := ptTransports[transportName]
	if transport == nil {
		return "", fmt.Errorf("pt: транспорт %q не собран в это ядро", transportName)
	}
	factory, err := transport.ClientFactory(stateDir)
	if err != nil {
		return "", fmt.Errorf("pt: клиент %q не создан: %w", transportName, err)
	}

	listener, err := net.Listen("tcp", address)
	if err != nil {
		return "", fmt.Errorf("pt: порт не занят: %w", err)
	}

	proxy := &ptProxy{name: transportName, listener: listener, factory: factory}
	ptRunning[transportName] = proxy
	go proxy.serve()

	actual := listener.Addr().String()
	log.Printf("pt: локальный SOCKS5 %s для tor слушает %s", transportName, actual)
	return actual, nil
}

// StopTorPtProxy закрывает всех слушателей. Уже установленные соединения
// закрывает сам tor, когда закрывает свои: они висят на его же сокетах.
func StopTorPtProxy() {
	ptMu.Lock()
	proxies := make([]*ptProxy, 0, len(ptRunning))
	for _, proxy := range ptRunning {
		proxies = append(proxies, proxy)
	}
	ptRunning = map[string]*ptProxy{}
	ptMu.Unlock()

	for _, proxy := range proxies {
		proxy.closeOnce.Do(func() {
			_ = proxy.listener.Close()
			log.Printf(
				"pt: локальный SOCKS5 %s остановлен (принято %d, неудачных %d)",
				proxy.name, proxy.accepted.Load(), proxy.failed.Load(),
			)
		})
	}
}

// TorPtProxyStats — строка для журнала приложения: «принято/неудачно/сейчас».
// Числами, а не «работает»: tor молчит о том, дошёл ли он до моста, и без
// счётчиков отличить «мост не отвечает» от «tor даже не пробовал» нечем.
func TorPtProxyStats() string {
	ptMu.Lock()
	defer ptMu.Unlock()

	if len(ptRunning) == 0 {
		return "pt: не запущен"
	}
	parts := make([]string, 0, len(ptRunning))
	for name, proxy := range ptRunning {
		parts = append(parts, fmt.Sprintf(
			"%s: принято %d, неудачных %d, живых %d",
			name, proxy.accepted.Load(), proxy.failed.Load(), proxy.sessions.Load(),
		))
	}
	return strings.Join(parts, "; ")
}

// TorPtTransports перечисляет транспорты, собранные в это ядро.
func TorPtTransports() string {
	names := make([]string, 0, len(ptTransports))
	for name := range ptTransports {
		names = append(names, name)
	}
	sort.Strings(names)
	return strings.Join(names, ",")
}

func (p *ptProxy) serve() {
	for {
		conn, err := p.listener.Accept()
		if err != nil {
			// Закрытый слушатель — штатный конец, а не отказ.
			if errors.Is(err, net.ErrClosed) {
				return
			}
			log.Printf("pt %s: accept не удался: %v", p.name, err)
			return
		}
		if p.sessions.Load() >= ptMaxSessions {
			// Tor держит немного соединений к мосту; всё сверх этого — признак
			// того, что что-то пошло вразнос, и молча копить такие соединения
			// хуже, чем отказать.
			log.Printf("pt %s: соединений уже %d, отказываем", p.name, p.sessions.Load())
			_ = conn.Close()
			continue
		}
		p.accepted.Add(1)
		p.sessions.Add(1)
		go func() {
			defer p.sessions.Add(-1)
			if err := p.handle(conn); err != nil {
				p.failed.Add(1)
				log.Printf("pt %s: соединение к мосту не состоялось: %v", p.name, err)
			}
		}()
	}
}

func (p *ptProxy) handle(client net.Conn) error {
	defer client.Close()

	// Рукопожатие SOCKS5 идёт под общим сроком: зависший на нём tor иначе
	// оставит нам горутину и сокет навсегда.
	if err := client.SetDeadline(time.Now().Add(ptAuthTimeout)); err != nil {
		return err
	}

	params, err := socks5ReadHandshake(client)
	if err != nil {
		return err
	}

	target, err := socks5ReadConnect(client)
	if err != nil {
		return err
	}

	args, err := parseBridgeArgs(params)
	if err != nil {
		_ = socks5Reply(client, 0x01)
		return fmt.Errorf("аргументы моста неразбираемы: %w", err)
	}

	parsed, err := p.factory.ParseArgs(&args)
	if err != nil {
		_ = socks5Reply(client, 0x01)
		return fmt.Errorf("аргументы %s не приняты: %w", p.name, err)
	}

	// Дальше — сеть, и срок нужен свой: рукопожатие транспорта идёт поверх
	// дозвона, а у snowflake и meek_lite оно включает ещё и обращение к
	// брокеру или к фронту.
	if err := client.SetDeadline(time.Now().Add(ptDialTimeout + ptAuthTimeout)); err != nil {
		return err
	}

	bridge, err := p.factory.Dial("tcp", target, protectedDial, parsed)
	if err != nil {
		// 0x04 — host unreachable: tor по коду отличает «мост не отвечает» от
		// «прокси сам не понял запрос» и не снимает мост со счетов зря.
		_ = socks5Reply(client, 0x04)
		return fmt.Errorf("мост %s: %w", target, err)
	}
	defer bridge.Close()

	if err := socks5Reply(client, 0x00); err != nil {
		return err
	}

	// Срок снимается: дальше это обычный поток, и он живёт столько, сколько
	// нужно tor'у.
	if err := client.SetDeadline(time.Time{}); err != nil {
		return err
	}

	pipe(client, bridge)
	return nil
}

// protectedDial — тот же дозвон, что и у прочего служебного трафика ядра:
// сокет помечается «мимо VPN» до соединения. Без метки мост оказался бы за
// нашим же туннелем, а туннель в этот момент как раз и поднимается через мост.
func protectedDial(network, address string) (net.Conn, error) {
	dialer := &net.Dialer{
		Timeout: ptDialTimeout,
		Control: func(_ string, _ string, rawConn syscall.RawConn) error {
			return protectRawConn(rawConn)
		},
	}
	return dialer.Dial(network, address)
}

func pipe(a, b net.Conn) {
	var wg sync.WaitGroup
	wg.Add(2)
	copyOne := func(dst, src net.Conn) {
		defer wg.Done()
		_, _ = io.Copy(dst, src)
		// Полузакрытие вместо полного: обратное направление может ещё нести
		// данные, и закрытие целиком обрывало бы их.
		if closer, ok := dst.(interface{ CloseWrite() error }); ok {
			_ = closer.CloseWrite()
			return
		}
		_ = dst.SetReadDeadline(time.Now())
	}
	go copyOne(a, b)
	go copyOne(b, a)
	wg.Wait()
}

// socks5ReadHandshake отвечает на приветствие и возвращает строку аргументов
// моста, склеенную из логина и пароля.
//
// Разрез именно такой: pt-spec велит класть в логин первые 255 байт, остаток —
// в пароль, а когда аргументы короче, пароль занимает один нулевой байт (пустое
// поле запрещено RFC 1929). Поэтому нули с конца снимаются, а не считаются
// частью значения.
func socks5ReadHandshake(client net.Conn) (string, error) {
	header := make([]byte, 2)
	if _, err := io.ReadFull(client, header); err != nil {
		return "", fmt.Errorf("приветствие не прочитано: %w", err)
	}
	if header[0] != socks5Version {
		return "", fmt.Errorf("версия SOCKS %d не поддерживается", header[0])
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(client, methods); err != nil {
		return "", fmt.Errorf("список методов не прочитан: %w", err)
	}

	offersUserPwd := false
	offersNone := false
	for _, method := range methods {
		switch method {
		case socks5AuthUserPwd:
			offersUserPwd = true
		case socks5AuthNone:
			offersNone = true
		}
	}

	switch {
	case offersUserPwd:
		if _, err := client.Write([]byte{socks5Version, socks5AuthUserPwd}); err != nil {
			return "", err
		}
		return socks5ReadUserPassword(client)
	case offersNone:
		// Мост без аргументов бывает: у snowflake и meek_lite их присылает не
		// строка моста, а сам tor из встроенной настройки, и она может быть
		// пустой. Отказывать тут нельзя.
		if _, err := client.Write([]byte{socks5Version, socks5AuthNone}); err != nil {
			return "", err
		}
		return "", nil
	default:
		_, _ = client.Write([]byte{socks5Version, socks5AuthNoneOk})
		return "", errors.New("клиент не предложил ни одного знакомого метода")
	}
}

func socks5ReadUserPassword(client net.Conn) (string, error) {
	header := make([]byte, 2)
	if _, err := io.ReadFull(client, header); err != nil {
		return "", fmt.Errorf("логин не прочитан: %w", err)
	}
	if header[0] != 0x01 {
		return "", fmt.Errorf("версия проверки пароля %d не поддерживается", header[0])
	}
	username := make([]byte, int(header[1]))
	if _, err := io.ReadFull(client, username); err != nil {
		return "", fmt.Errorf("логин не прочитан: %w", err)
	}
	length := make([]byte, 1)
	if _, err := io.ReadFull(client, length); err != nil {
		return "", fmt.Errorf("длина пароля не прочитана: %w", err)
	}
	password := make([]byte, int(length[0]))
	if _, err := io.ReadFull(client, password); err != nil {
		return "", fmt.Errorf("пароль не прочитан: %w", err)
	}
	if _, err := client.Write([]byte{0x01, 0x00}); err != nil {
		return "", err
	}
	joined := string(username) + string(password)
	return strings.TrimRight(joined, "\x00"), nil
}

func socks5ReadConnect(client net.Conn) (string, error) {
	header := make([]byte, 4)
	if _, err := io.ReadFull(client, header); err != nil {
		return "", fmt.Errorf("запрос не прочитан: %w", err)
	}
	if header[0] != socks5Version {
		return "", fmt.Errorf("версия SOCKS %d не поддерживается", header[0])
	}
	if header[1] != socks5CmdConnect {
		_ = socks5Reply(client, 0x07)
		return "", fmt.Errorf("команда %d не поддерживается", header[1])
	}

	var host string
	switch header[3] {
	case socks5AtypIPv4:
		buf := make([]byte, 4)
		if _, err := io.ReadFull(client, buf); err != nil {
			return "", err
		}
		host = net.IP(buf).String()
	case socks5AtypIPv6:
		buf := make([]byte, 16)
		if _, err := io.ReadFull(client, buf); err != nil {
			return "", err
		}
		host = net.IP(buf).String()
	case socks5AtypDomain:
		length := make([]byte, 1)
		if _, err := io.ReadFull(client, length); err != nil {
			return "", err
		}
		buf := make([]byte, int(length[0]))
		if _, err := io.ReadFull(client, buf); err != nil {
			return "", err
		}
		host = string(buf)
	default:
		_ = socks5Reply(client, 0x08)
		return "", fmt.Errorf("тип адреса %d не поддерживается", header[3])
	}

	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(client, portBuf); err != nil {
		return "", err
	}
	port := binary.BigEndian.Uint16(portBuf)

	return net.JoinHostPort(host, strconv.Itoa(int(port))), nil
}

// parseBridgeArgs разбирает `ключ=значение;ключ=значение` из полей SOCKS.
//
// Свой разбор, а не библиотечный, потому что в goptlib эта функция не
// экспортирована (`parseClientParameters`, v1.6.0). Правила взяты оттуда же и из
// pt-spec: обратная косая экранирует следующий байт, поэтому `;` и `=` внутри
// значения существуют — а `cert` у obfs4 это base64, где `=` в конце обычное
// дело. Наивный `strings.Split` разрезал бы такой `cert` пополам.
func parseBridgeArgs(s string) (pt.Args, error) {
	args := make(pt.Args)
	if len(s) == 0 {
		return args, nil
	}

	i := 0
	for {
		begin := i

		offset, key, err := indexUnescapedByte(s[i:], "=;")
		if err != nil {
			return nil, err
		}
		i += offset
		if i >= len(s) || s[i] != '=' {
			return nil, fmt.Errorf("нет знака равенства в %q", s[begin:i])
		}
		i++

		offset, value, err := indexUnescapedByte(s[i:], ";")
		if err != nil {
			return nil, err
		}
		i += offset
		if len(key) == 0 {
			return nil, fmt.Errorf("пустой ключ в %q", s[begin:i])
		}
		args.Add(key, value)

		if i >= len(s) {
			break
		}
		i++
	}
	return args, nil
}

// indexUnescapedByte ищет первый неэкранированный байт из [terminators] и
// заодно возвращает уже расэкранированную часть до него.
func indexUnescapedByte(s string, terminators string) (int, string, error) {
	unescaped := make([]byte, 0, len(s))
	i := 0
	for ; i < len(s); i++ {
		b := s[i]
		if strings.IndexByte(terminators, b) != -1 {
			break
		}
		if b == '\\' {
			i++
			if i >= len(s) {
				return 0, "", fmt.Errorf("обратная косая в конце %q", s)
			}
			b = s[i]
		}
		unescaped = append(unescaped, b)
	}
	return i, string(unescaped), nil
}

// socks5Reply отвечает одним кодом. Привязанный адрес всегда нулевой: tor его
// не использует, а настоящий адрес нашего сокета — это лишняя подсказка о том,
// куда именно мы ходили.
func socks5Reply(client net.Conn, code byte) error {
	_, err := client.Write([]byte{socks5Version, code, 0x00, socks5AtypIPv4, 0, 0, 0, 0, 0, 0})
	return err
}

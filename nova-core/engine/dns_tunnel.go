package nova

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"syscall"
	"time"

	vay "github.com/net2share/vaydns/client"
)

// Туннель поверх DNS: данные едут в QNAME наверх и в записях ответа вниз, а
// несущей служит обычный резолвер — тот, что и так обслуживает абонента.
//
// Зачем он здесь. Под «белыми списками» оператор режет зарубежные адреса на
// L3, до всякого DPI, и тогда не проходит ни один обычный транспорт: ни подмена
// SNI, ни маскировка под QUIC. Резолвер при этом остаётся доступен по
// построению — без него не работает ничего, — и он сам ходит на произвольный
// зарубежный авторитативный сервер. Замер с российской SIM 2026-09-20
// (`kb/dns-tunnel-and-tor.md`): рекурсоры МегаФона и НСДИ `195.208.5.1` оба
// резолвят случайную метку под чужой зоной.
//
// Чем он не является. Это **не быстрый режим**, и подписывать его так нельзя:
// измеренный потолок — 120 Б полезной нагрузки на запрос вверх, 1187 Б вниз,
// 43,5 запроса в секунду, то есть примерно 380/42 кбит/с при асимметрии 1:9.
// Три предела независимы и ни один не обходится: 253-байтный QNAME, лимит
// частоты у рекурсора и 27-кратная плата за уход от классификатора FOCI 2021.
//
// Сервера у нас нет и не будет (D34): адрес зоны и открытый ключ приносит
// пользователь, ровно как в импорте AWG/VLESS. Ни один DNS-туннель не работает
// без авторитативного NS, через который идёт весь его трафик, поэтому релей
// такой зоной быть не может, оставаясь control plane.
//
// Наружу отдаётся локальный TCP-порт. Дальний конец туннеля у пользователя
// смотрит в SOCKS5-демон, поэтому порт ведёт себя как SOCKS5 из конца в конец —
// это и есть то, что умеет потреблять `tun2proxy`.

// dnsTunnelResolver — одна несущая: чем спрашиваем и кого.
type dnsTunnelResolver struct {
	// Type: "udp" либо "doh". "dot" отвергается намеренно, см. newDNSResolver.
	Type string `json:"type"`
	Addr string `json:"addr"`
}

// dnsTunnelConfig — то, что приносит пользователь, плюс ручки прикрытия.
type dnsTunnelConfig struct {
	Zone      string              `json:"zone"`
	PubKey    string              `json:"pubkey"`
	Resolvers []dnsTunnelResolver `json:"resolvers"`

	// DnsttCompat — формат исходного dnstt (8-байтный ClientID, префиксы
	// добивки). Нужен, потому что чужие точки выхода почти все именно dnstt.
	DnsttCompat bool `json:"dnsttCompat"`

	// Прикрытие. Нули означают «как у библиотеки».
	MaxQnameLen  int     `json:"maxQnameLen"`
	MaxNumLabels int     `json:"maxNumLabels"`
	RPS          float64 `json:"rps"`
	RecordType   string  `json:"recordType"`
	ClientIDSize int     `json:"clientIdSize"`

	// ProbeTimeoutMs ограничивает пробу одной несущей. Ноль — 12 с.
	ProbeTimeoutMs int `json:"probeTimeoutMs"`
}

type dnsTunnelState struct {
	mu     sync.Mutex
	cancel context.CancelFunc
	// listener — наш собственный слушатель, и именно он делает туннель
	// останавливаемым. Подробности — в комментарии к serveDNSTunnel.
	listener *net.TCPListener
	listen   string
	resolver string
	running  bool
	lastErr  string
}

var dnsTunnel dnsTunnelState

// StartDNSTunnel поднимает туннель и возвращает адрес локального слушателя.
//
// Несущие перебираются по порядку, и победителем считается только та, на
// которой сошлось рукопожатие Noise: это единственное доказательство, что
// дальний конец жив и ключ верен. Резолвер, который «ответил», не доказывает
// ничего — он мог ответить и NXDOMAIN, и подменой.
//
// Перебор ограничен по времени намеренно. `ListenAndServe` у библиотеки
// пересобирает стек вечно с нарастающей паузой, и отдать эту вечность наружу
// значило бы, что фаза транспорта никогда не скажет «не смогла» (I4).
func StartDNSTunnel(configJSON string, listenAddr string) (string, error) {
	var cfg dnsTunnelConfig
	if err := json.Unmarshal([]byte(configJSON), &cfg); err != nil {
		return "", fmt.Errorf("dns-tunnel: разбор конфигурации: %w", err)
	}
	if strings.TrimSpace(cfg.Zone) == "" {
		return "", errors.New("dns-tunnel: не задана зона туннеля")
	}
	if strings.TrimSpace(cfg.PubKey) == "" {
		return "", errors.New("dns-tunnel: не задан открытый ключ сервера")
	}
	if len(cfg.Resolvers) == 0 {
		return "", errors.New("dns-tunnel: не задано ни одной несущей (резолвера)")
	}

	dnsTunnel.mu.Lock()
	if dnsTunnel.running {
		addr := dnsTunnel.listen
		dnsTunnel.mu.Unlock()
		return addr, nil
	}
	dnsTunnel.mu.Unlock()

	server, err := newDNSTunnelServer(cfg)
	if err != nil {
		return "", err
	}

	probeTimeout := time.Duration(cfg.ProbeTimeoutMs) * time.Millisecond
	if probeTimeout <= 0 {
		probeTimeout = 12 * time.Second
	}

	// Слушатель открывается здесь и живёт у нас, а не внутри библиотеки.
	//
	// Так было не всегда, и прежняя форма была дефектом. `vay.ListenAndServe`
	// заводит слушатель сам, наружу его не отдаёт и не возвращается **никогда**:
	// её внешний цикл бесконечно пересобирает стек, а выход из него есть ровно
	// один — ошибка `Accept`, то есть закрытый слушатель, до которого снаружи не
	// дотянуться. `StopDNSTunnel` отменял контекст, но контекст в этом цикле не
	// участвует: после «Отключить» туннель продолжал слать DNS-запросы и держать
	// локальный порт, а каждое следующее подключение заводило ещё один такой же.
	//
	// Поэтому слушатель наш: закрыть его — и есть остановка.
	localAddr, err := net.ResolveTCPAddr("tcp", listenAddr)
	if err != nil {
		return "", fmt.Errorf("dns-tunnel: неразборчивый локальный адрес %q: %w", listenAddr, err)
	}
	listener, err := net.ListenTCP("tcp", localAddr)
	if err != nil {
		return "", fmt.Errorf("dns-tunnel: не удалось занять локальный порт: %w", err)
	}
	listenAddr = listener.Addr().String()
	listenerTaken := false
	defer func() {
		// Ни одна несущая не победила — порт отдаём обратно.
		if !listenerTaken {
			_ = listener.Close()
		}
	}()

	var failures []string
	for _, r := range cfg.Resolvers {
		resolver, err := newDNSResolver(r)
		if err != nil {
			failures = append(failures, fmt.Sprintf("%s %s: %v", r.Type, r.Addr, err))
			continue
		}
		if err := probeDNSTunnel(resolver, server, probeTimeout); err != nil {
			failures = append(failures, fmt.Sprintf("%s %s: %v", r.Type, r.Addr, err))
			continue
		}

		ctx, cancel := context.WithCancel(context.Background())
		tunnel, err := newDNSTunnel(resolver, server, cfg)
		if err != nil {
			cancel()
			failures = append(failures, fmt.Sprintf("%s %s: %v", r.Type, r.Addr, err))
			continue
		}

		dnsTunnel.mu.Lock()
		dnsTunnel.cancel = cancel
		dnsTunnel.listener = listener
		dnsTunnel.listen = listenAddr
		dnsTunnel.resolver = r.Type + " " + r.Addr
		dnsTunnel.running = true
		dnsTunnel.lastErr = ""
		dnsTunnel.mu.Unlock()

		listenerTaken = true
		go serveDNSTunnel(ctx, listener, tunnel, server, r.Type+" "+r.Addr)

		// Дозвоном на порт здесь ничего не проверяют: дозвон приняли бы как
		// клиентское соединение и открыли бы ради него настоящий поток сквозь
		// туннель, то есть заплатили бы круговым обходом по каналу в 380 кбит/с
		// за проверку, которая ничего нового не скажет. Да и проверять уже
		// нечего: bind произошёл выше синхронно, а рукопожатие на этой несущей
		// только что сошлось в пробе.
		return listenAddr, nil
	}

	return "", fmt.Errorf("dns-tunnel: ни одна несущая не подняла туннель: %s", strings.Join(failures, "; "))
}

// StopDNSTunnel закрывает туннель и его слушатель.
//
// Закрывается именно слушатель, и это не дубль к отмене контекста, а сама
// остановка: цикл обслуживания сидит в `Accept`, и разбудить его можно только
// так. Контекст отменяется тоже — он вынимает цикл из пауз между попытками
// пересобрать сессию, когда `Accept` ещё не вызван.
func StopDNSTunnel() {
	dnsTunnel.mu.Lock()
	cancel := dnsTunnel.cancel
	listener := dnsTunnel.listener
	dnsTunnel.cancel = nil
	dnsTunnel.listener = nil
	dnsTunnel.running = false
	dnsTunnel.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	if listener != nil {
		_ = listener.Close()
	}
}

// Пределы пересборки сессии. Пауза растёт, чтобы мёртвая несущая не молотила
// запросами, но потолок невелик: канал и так узкий, а ждать полминуты после
// возврата сети — это полминуты без связи.
const (
	dnsTunnelRebuildMinDelay = 500 * time.Millisecond
	dnsTunnelRebuildMaxDelay = 20 * time.Second
	// Окно `Accept`: через него цикл просыпается и смотрит, не пора ли
	// пересобрать сессию или уйти.
	dnsTunnelAcceptWindow = 2 * time.Second
)

// serveDNSTunnel — свой цикл обслуживания вместо `vay.ListenAndServe`.
//
// ## Зачем свой
//
// Библиотечный не останавливается (см. комментарий в StartDNSTunnel). Своего
// API остановки у неё нет, зато собрать сессию можно её же экспортированными
// частями — ровно теми, которыми это уже делает проба ([probeDNSTunnel]).
// Поэтому цикл здесь, а слушатель — наш.
//
// ## Чем он отличается от библиотечного, честно
//
// Библиотека замечает смерть сессии **заранее**, по своим внутренним каналам
// (`sess.CloseChan()`, `TransportErrors()`), — наружу они не выведены. Здесь
// смерть замечается по первому не открывшемуся потоку: одно соединение платит
// за это отказом, после чего сессия пересобирается, и следующие идут уже по
// живой. Хуже на одно соединение — и останавливается, а прежний вариант был
// лучше на одно соединение и не останавливался никогда.
//
// ## Почему поток открывается под замком
//
// `OpenStream` читает у туннеля указатель на сессию, а пересборка его меняет.
// Библиотека обходит это тем, что отдаёт обработчику сессию значением; здесь
// обработчик берёт её через сам туннель, поэтому пересборка ждёт открытия, а
// не наоборот. Копирование байтов идёт уже без замка — закрытие потока во время
// `io.Copy` библиотека переносит сама.
func serveDNSTunnel(ctx context.Context, ln *net.TCPListener, t *vay.Tunnel, server vay.TunnelServer, who string) {
	defer func() {
		_ = ln.Close()
		_ = t.Close()
	}()

	var sessionMu sync.RWMutex
	// Ёмкость 1: важен сам факт «сессия подвела», а не сколько раз.
	broken := make(chan struct{}, 1)

	needSession := true
	delay := dnsTunnelRebuildMinDelay

	for {
		if ctx.Err() != nil {
			return
		}

		if needSession {
			sessionMu.Lock()
			err := buildDNSTunnelSession(t, server)
			sessionMu.Unlock()
			if err != nil {
				// Молчаливое завершение запрещено (I4): снаружи оно
				// неотличимо от живого туннеля, который просто ничего не несёт.
				noteDNSTunnelTrouble(fmt.Sprintf("несущая %s: сессия не собралась: %v", who, err))
				select {
				case <-ctx.Done():
					return
				case <-time.After(delay):
				}
				delay *= 2
				if delay > dnsTunnelRebuildMaxDelay {
					delay = dnsTunnelRebuildMaxDelay
				}
				continue
			}
			needSession = false
			delay = dnsTunnelRebuildMinDelay
			select {
			case <-broken:
			default:
			}
			dnsTunnel.mu.Lock()
			if dnsTunnel.running {
				dnsTunnel.lastErr = ""
			}
			dnsTunnel.mu.Unlock()
		}

		_ = ln.SetDeadline(time.Now().Add(dnsTunnelAcceptWindow))
		conn, err := ln.AcceptTCP()
		if err != nil {
			if ctx.Err() != nil {
				return
			}
			if ne, ok := err.(net.Error); ok && ne.Timeout() {
				select {
				case <-broken:
					needSession = true
				default:
				}
				continue
			}
			// Не таймаут — значит слушатель закрыт или сломан. Пересобирать
			// нечего: порта больше нет.
			noteDNSTunnelStopped(fmt.Sprintf("несущая %s: слушатель закрыт: %v", who, err))
			return
		}

		go func(c *net.TCPConn) {
			defer c.Close()
			if err := pipeDNSTunnelConn(t, &sessionMu, c); err != nil {
				select {
				case broken <- struct{}{}:
				default:
				}
			}
		}(conn)
	}
}

// buildDNSTunnelSession собирает стек заново — той же последовательностью, что
// и проба, плюс мультиплексор поверх шифрованного канала.
func buildDNSTunnelSession(t *vay.Tunnel, server vay.TunnelServer) error {
	// Старое сносится первым: `Close` у библиотеки безопасно звать повторно, а
	// пересборка поверх живых сокетов оставила бы их висеть.
	_ = t.Close()
	if err := t.InitiateResolverConnection(); err != nil {
		return fmt.Errorf("несущая не открылась: %w", err)
	}
	if err := t.InitiateDNSPacketConn(server.Addr); err != nil {
		return fmt.Errorf("кодирование DNS не собралось: %w", err)
	}
	if err := t.InitiateKCPConn(server.MTU); err != nil {
		return fmt.Errorf("KCP не поднялся: %w", err)
	}
	if err := t.InitiateNoiseChannel(); err != nil {
		return fmt.Errorf("рукопожатие Noise не сошлось: %w", err)
	}
	if err := t.InitiateSmuxSession(); err != nil {
		return fmt.Errorf("мультиплексор не поднялся: %w", err)
	}
	return nil
}

// pipeDNSTunnelConn проводит одно соединение сквозь туннель.
func pipeDNSTunnelConn(t *vay.Tunnel, sessionMu *sync.RWMutex, local *net.TCPConn) error {
	sessionMu.RLock()
	stream, err := t.OpenStream()
	sessionMu.RUnlock()
	if err != nil {
		return err
	}
	defer stream.Close()

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(stream, local)
		_ = local.CloseRead()
		_ = stream.Close()
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(local, stream)
		_ = local.CloseWrite()
	}()
	wg.Wait()
	return nil
}

// noteDNSTunnelTrouble записывает беду, не объявляя туннель мёртвым: цикл его
// пересобирает, и снаружи он всё ещё тот же туннель.
func noteDNSTunnelTrouble(reason string) {
	dnsTunnel.mu.Lock()
	if dnsTunnel.running {
		dnsTunnel.lastErr = reason
	}
	dnsTunnel.mu.Unlock()
}

// noteDNSTunnelStopped объявляет туннель мёртвым — цикл ушёл насовсем.
func noteDNSTunnelStopped(reason string) {
	dnsTunnel.mu.Lock()
	if dnsTunnel.running {
		dnsTunnel.lastErr = reason
		dnsTunnel.running = false
	}
	dnsTunnel.mu.Unlock()
}

// DNSTunnelStatus отдаёт одну строку для журнала: жив ли туннель, на какой
// несущей и чем закончился, если умер.
func DNSTunnelStatus() string {
	dnsTunnel.mu.Lock()
	defer dnsTunnel.mu.Unlock()
	if dnsTunnel.running {
		return fmt.Sprintf("живой, несущая %s, слушает %s", dnsTunnel.resolver, dnsTunnel.listen)
	}
	if dnsTunnel.lastErr != "" {
		return "остановлен: " + dnsTunnel.lastErr
	}
	return "не запущен"
}

func newDNSTunnelServer(cfg dnsTunnelConfig) (vay.TunnelServer, error) {
	server, err := vay.NewTunnelServer(cfg.Zone, cfg.PubKey)
	if err != nil {
		return vay.TunnelServer{}, fmt.Errorf("dns-tunnel: зона или ключ не приняты: %w", err)
	}
	server.DnsttCompat = cfg.DnsttCompat
	server.MaxQnameLen = cfg.MaxQnameLen
	server.MaxNumLabels = cfg.MaxNumLabels
	server.RPS = cfg.RPS
	server.RecordType = cfg.RecordType
	if !cfg.DnsttCompat {
		server.ClientIDSize = cfg.ClientIDSize
	}
	return server, nil
}

func newDNSTunnel(resolver vay.Resolver, server vay.TunnelServer, cfg dnsTunnelConfig) (*vay.Tunnel, error) {
	t, err := vay.NewTunnel(resolver, server)
	if err != nil {
		return nil, fmt.Errorf("dns-tunnel: %w", err)
	}
	return t, nil
}

// newDNSResolver строит несущую и помечает её сокеты как «мимо VPN».
//
// Про DoT отдельно и вслух. `vay.Resolver` пропускает `DialerControl` **только**
// в ветку UDP: DoH ходит через `http.DefaultTransport`, а DoT — через
// `tls.DialWithDialer` с пустым `net.Dialer`. Внутри VpnService это значит, что
// несущая туннеля уедет в тот самый туннель, который она поднимает, — ровно
// G154 и G200, за которые здесь уже дважды заплачено. DoH лечится своим
// `RoundTripper`, а DoT через публичный API — ничем, поэтому он не
// поддерживается и говорит об этом прямо, а не подменяется другим типом (I4).
func newDNSResolver(r dnsTunnelResolver) (vay.Resolver, error) {
	addr := strings.TrimSpace(r.Addr)
	if addr == "" {
		return vay.Resolver{}, errors.New("пустой адрес несущей")
	}

	switch strings.ToLower(strings.TrimSpace(r.Type)) {
	case "udp":
		resolver, err := vay.NewResolver(vay.ResolverTypeUDP, addr)
		if err != nil {
			return vay.Resolver{}, err
		}
		resolver.DialerControl = protectDialerControl
		return resolver, nil

	case "doh":
		resolver, err := vay.NewResolver(vay.ResolverTypeDOH, addr)
		if err != nil {
			return vay.Resolver{}, err
		}
		resolver.RoundTripper = protectedDoHRoundTripper()
		return resolver, nil

	case "dot":
		return vay.Resolver{}, errors.New(
			"DoT как несущая не поддерживается: библиотека открывает его сокет сама, " +
				"пометить его VpnService.protect() нечем, и он ушёл бы в поднимаемый туннель")

	default:
		return vay.Resolver{}, fmt.Errorf("неизвестный тип несущей %q", r.Type)
	}
}

// protectDialerControl — форма, которой ждёт vaydns, поверх общего протектора ядра.
func protectDialerControl(network, address string, c syscall.RawConn) error {
	return protectRawConn(c)
}

// protectedDoHRoundTripper повторяет умолчания `http.Transport`, но с
// помеченным сокетом. Брать `http.DefaultTransport` и «подложить» ему диалер
// нельзя: он общий на весь процесс.
func protectedDoHRoundTripper() http.RoundTripper {
	dialer := &net.Dialer{
		Timeout:   10 * time.Second,
		KeepAlive: 30 * time.Second,
		Control:   protectDialerControl,
	}
	return &http.Transport{
		DialContext:           dialer.DialContext,
		ForceAttemptHTTP2:     true,
		MaxIdleConns:          8,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}
}

// probeDNSTunnel поднимает стек до рукопожатия Noise и разбирает его обратно.
//
// Проверяется именно рукопожатие, а не «резолвер ответил»: ответ приходит и от
// подменяющего посредника, и NXDOMAIN'ом, а сошедшееся рукопожатие доказывает,
// что за зоной стоит наш сервер и ключ тот самый.
func probeDNSTunnel(resolver vay.Resolver, server vay.TunnelServer, timeout time.Duration) error {
	t, err := vay.NewTunnel(resolver, server)
	if err != nil {
		return err
	}
	t.HandshakeTimeout = timeout

	done := make(chan error, 1)
	go func() {
		done <- func() error {
			if err := t.InitiateResolverConnection(); err != nil {
				return fmt.Errorf("несущая не открылась: %w", err)
			}
			if err := t.InitiateDNSPacketConn(server.Addr); err != nil {
				return fmt.Errorf("кодирование DNS не собралось: %w", err)
			}
			if err := t.InitiateKCPConn(server.MTU); err != nil {
				return fmt.Errorf("KCP не поднялся: %w", err)
			}
			if err := t.InitiateNoiseChannel(); err != nil {
				return fmt.Errorf("рукопожатие Noise не сошлось: %w", err)
			}
			return nil
		}()
	}()

	select {
	case err := <-done:
		_ = t.Close()
		return err
	case <-time.After(timeout):
		_ = t.Close()
		return fmt.Errorf("проба не уложилась в %s", timeout)
	}
}

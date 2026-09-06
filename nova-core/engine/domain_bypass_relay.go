package nova

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	wgtun "github.com/amnezia-vpn/amneziawg-go/v3/tun"
	wgnetstack "github.com/amnezia-vpn/amneziawg-go/v3/tun/netstack"
)

// Обход по доменам и зонам: часть вторая — прозрачный TCP-релей.
//
// Устройство скопировано с telegramTransparentProxy, но из него выброшено всё
// MTProto: ни чтения 64-байтного init (для TLS оно бы просто повисло — клиент
// молчит, пока не увидит ServerHello), ни WSS, ни доменных гонок. Здесь только
// splice: приняли соединение на подставном локальном адресе netstack, набрали
// тот же адрес защищённым сокетом и перелили байты.
//
// Работает это только на WARP и MASQUE. В Opera и VLESS дескриптор tun отдаётся
// tun2proxy целиком, и ни один пакет не проходит через код на Go — перехватывать
// нечего.
//
// UDP в первой версии не переносится: QUIC к выученному адресу по-прежнему
// уходит в туннель — и доходит. Отката на TCP при этом НЕ происходит: откат
// случается, когда QUIC не отвечает, а здесь он отвечает, просто изнутри
// туннеля. То есть для HTTP/3 обход по доменам не действует вовсе, и счётчик
// quicSkipped считает именно пакеты, прошедшие мимо обхода, а не неудачи.

const (
	domainBypassProtoTCP = 6
	domainBypassProtoUDP = 17
)

// Сколько подряд неудачных обращений к Android TUN насос терпит, прежде чем сдаться.
//
// Просто выйти из насоса нельзя, и это не вопрос аккуратности, а взаимная
// блокировка всего туннеля. netstack отдаёт ответные пакеты в НЕбуферизованный
// канал incomingPacket (tools/amneziawg-go/tun/netstack/tun.go: make(chan
// *buffer.View) без размера), и запись в него идёт синхронно внутри
// p.dev.Write — то есть внутри maybeHandle, то есть под AndroidTUN.mu. Если
// читателя канала не стало, а перехват продолжается, первый же ответ gVisor
// повисает навсегда вместе с мьютексом: встают и чтение из TUN, и Close(),
// который берёт тот же мьютекс. Пользователь получает мёртвый туннель, который
// нельзя даже выключить.
//
// Поэтому ошибка считается временной, а окончательный отказ обязан снять
// перехват (pumpAlive) прежде, чем насос уйдёт.
const domainBypassPumpRetries = 40

const domainBypassPumpRetryDelay = 25 * time.Millisecond

type domainBypassProxy struct {
	file           *osFileAdapter
	mtu            int
	dev            wgtun.Device
	net            *wgnetstack.Net
	closeOnce      sync.Once
	mu             sync.Mutex
	listeners      []net.Listener
	flowListeners  map[string]net.Listener
	installedAddrs map[netip.Addr]struct{}
	capturedFlows  map[domainBypassFlowKey]time.Time
	ctx            context.Context
	cancel         context.CancelFunc
	wg             sync.WaitGroup

	// Жив ли насос netstack → Android TUN. Пока он не жив, перехватывать нельзя
	// ни одного пакета: см. domainBypassPumpRetries.
	pumpAlive atomic.Bool

	// Открытые прямо сейчас перелитые соединения. Без этого списка снятое
	// правило переживало бы себя: слушателя закрыть можно, а уже принятый
	// поток продолжал бы идти мимо туннеля, пока его не закроет собеседник.
	sessions map[*domainBypassSession]struct{}
}

// domainBypassSession — одна пара «клиент из netstack ↔ прямой сокет наружу».
type domainBypassSession struct {
	dst      netip.Addr
	client   net.Conn
	upstream net.Conn
}

// Живой релей, если он поднят.
//
// Политика (domain_bypass.go) обязана уметь дотянуться до него: вытеснение из
// LRU и снятие правил закрывают слушателя, его горутину и открытые соединения.
// Иначе слушатели копятся всю сессию, а трафик по снятому правилу продолжает
// идти наружу.
var domainBypassActiveProxy atomic.Pointer[domainBypassProxy]

// domainBypassReleaseAddrs возвращает туннелю перечисленные адреса.
func domainBypassReleaseAddrs(addrs []netip.Addr) {
	if len(addrs) == 0 {
		return
	}
	if proxy := domainBypassActiveProxy.Load(); proxy != nil {
		proxy.releaseAddrs(addrs)
	}
}

// domainBypassReleaseAll возвращает туннелю всё, что релей успел забрать.
func domainBypassReleaseAll() {
	if proxy := domainBypassActiveProxy.Load(); proxy != nil {
		proxy.releaseAll()
	}
}

// releaseAddrs закрывает слушателей, перехваченные потоки и живые соединения
// указанных адресов.
//
// Замок отпускается до Close(): у gonet-соединения закрытие ходит в netstack, и
// держать при этом p.mu значит стоять на пути у acceptLoop.
func (p *domainBypassProxy) releaseAddrs(addrs []netip.Addr) {
	victims := make(map[netip.Addr]struct{}, len(addrs))
	for _, addr := range addrs {
		victims[addr.Unmap()] = struct{}{}
	}

	p.mu.Lock()
	closing := make([]net.Listener, 0, len(addrs))
	for key, listener := range p.flowListeners {
		addrPort, err := netip.ParseAddrPort(key)
		if err != nil {
			continue
		}
		if _, hit := victims[addrPort.Addr().Unmap()]; !hit {
			continue
		}
		closing = append(closing, listener)
		delete(p.flowListeners, key)
	}
	for flow := range p.capturedFlows {
		if _, hit := victims[flow.dst.Unmap()]; hit {
			delete(p.capturedFlows, flow)
		}
	}
	dropping := make([]*domainBypassSession, 0, len(p.sessions))
	for session := range p.sessions {
		if _, hit := victims[session.dst]; hit {
			dropping = append(dropping, session)
		}
	}
	domainBypassListenerCount.Store(int64(len(p.flowListeners)))
	p.mu.Unlock()

	closeRelayed(closing, dropping)
}

// releaseAll — то же самое для всего сразу: правила сняли целиком.
func (p *domainBypassProxy) releaseAll() {
	p.mu.Lock()
	closing := make([]net.Listener, 0, len(p.flowListeners))
	for key, listener := range p.flowListeners {
		closing = append(closing, listener)
		delete(p.flowListeners, key)
	}
	p.capturedFlows = make(map[domainBypassFlowKey]time.Time)
	dropping := make([]*domainBypassSession, 0, len(p.sessions))
	for session := range p.sessions {
		dropping = append(dropping, session)
	}
	domainBypassListenerCount.Store(0)
	p.mu.Unlock()

	closeRelayed(closing, dropping)
}

func closeRelayed(listeners []net.Listener, sessions []*domainBypassSession) {
	for _, listener := range listeners {
		_ = listener.Close()
	}
	for _, session := range sessions {
		safeCloseConn(session.client)
		safeCloseConn(session.upstream)
	}
	if len(listeners) > 0 || len(sessions) > 0 {
		log.Printf(
			"Domain bypass relay released: listeners=%d sessions=%d",
			len(listeners),
			len(sessions),
		)
	}
}

type domainBypassFlow struct {
	src     netip.Addr
	srcPort uint16
	dst     netip.Addr
	port    uint16
	proto   uint8
}

// key — четвёрка, по которой поток опознаётся однозначно.
//
// Одного «адрес:порт назначения» мало: выученный адрес почти всегда общий (CDN,
// anycast), и по нему уже идут чужие соединения — через туннель. Заворачивать их
// в netstack на середине нельзя: там нет их endpoint'а, будет RST.
func (f domainBypassFlow) key() domainBypassFlowKey {
	return domainBypassFlowKey{src: f.src, srcPort: f.srcPort, dst: f.dst, dstPort: f.port}
}

type domainBypassFlowKey struct {
	src     netip.Addr
	srcPort uint16
	dst     netip.Addr
	dstPort uint16
}

// Подсеть намеренно другая, чем у релея Telegram (10.202.0.0 / fd00:202::):
// оба стека живут одновременно в одном процессе, и пересечение адресов
// означало бы, что пакет уедет не в тот netstack.
func newDomainBypassProxy(file *os.File, mtu int) (*domainBypassProxy, error) {
	baseAddr := netip.MustParseAddr("10.203.0.1")
	baseAddrV6 := netip.MustParseAddr("fd00:203::1")
	dev, netStack, err := wgnetstack.CreateNetTUN([]netip.Addr{baseAddr, baseAddrV6}, nil, intMax(1280, mtu))
	if err != nil {
		return nil, err
	}

	ctx, cancel := context.WithCancel(context.Background())
	proxy := &domainBypassProxy{
		file:           &osFileAdapter{file: file},
		mtu:            intMax(1280, mtu),
		dev:            dev,
		net:            netStack,
		flowListeners:  make(map[string]net.Listener),
		installedAddrs: make(map[netip.Addr]struct{}),
		capturedFlows:  make(map[domainBypassFlowKey]time.Time),
		sessions:       make(map[*domainBypassSession]struct{}),
		ctx:            ctx,
		cancel:         cancel,
	}

	proxy.pumpAlive.Store(true)
	domainBypassRelayState.Store(domainBypassRelayAlive)
	domainBypassActiveProxy.Store(proxy)
	proxy.wg.Add(1)
	go proxy.pumpPacketsToAndroidTun()

	log.Printf("Domain bypass relay started: mtu=%d", proxy.mtu)
	return proxy, nil
}

// maybeHandle решает, забрать ли пакет себе.
//
// Возвращает true, только если пакет уже отдан в netstack: вызывающий обязан в
// этом случае не отправлять его в туннель.
func (p *domainBypassProxy) maybeHandle(packet []byte) (bool, error) {
	// Насос умер — перехватывать больше нечем: ответный пакет некому вернуть в
	// Android TUN, и netstack повис бы под AndroidTUN.mu. Отказ не молчаливый:
	// о нём сказано один раз, громко, в самом насосе (I4).
	if !p.pumpAlive.Load() {
		return false, nil
	}
	flow, ok := parseDomainBypassFlow(packet)
	if !ok {
		return false, nil
	}
	if flow.port != 443 && flow.port != 80 {
		return false, nil
	}
	if !domainBypassKnows(flow.dst) {
		return false, nil
	}
	if flow.proto == domainBypassProtoUDP {
		// QUIC к выученному адресу. Первая версия его не переносит: UDP через
		// netstack требует своей пары сокетов и своего времени жизни потока, и
		// это отдельная работа. Пакет уходит в туннель как обычно — и дойдёт,
		// так что браузер на TCP не откатится и обход для HTTP/3 не сработает.
		// Счётчик здесь пакетный: заводить учёт потоков значило бы брать замок
		// на каждом UDP-пакете горячего пути (I9).
		domainBypassQuicSkipped.Add(1)
		return false, nil
	}
	// Забираем только те соединения, которые сами и начали.
	//
	// Выученный адрес почти всегда общий: CDN и anycast отдают один IP десяткам
	// имён. По нему уже идут чужие соединения — через туннель, — и заворачивать
	// их середину в netstack нельзя: endpoint'а для такой четвёрки там нет, и
	// gVisor ответит RST. Снаружи это выглядело бы как «рабочая вкладка вдруг
	// оборвалась» при первом же обращении к обойдённому имени на том же адресе.
	key := flow.key()
	isSyn := isTelegramTransparentSyn(packet)
	if !isSyn && !p.isCapturedFlow(key) {
		return false, nil
	}
	if err := p.installLocalAddress(flow.dst); err != nil {
		return false, err
	}
	if err := p.ensureFlowListener(flow); err != nil {
		return false, err
	}
	// isTelegramTransparentSyn — обычная проверка флага SYN в TCP, к Telegram
	// она отношения не имеет; здесь она нужна только чтобы писать в лог один
	// раз на поток, а не на каждый пакет.
	if isSyn {
		p.captureFlow(key)
		if domainBypassLogBudget.Add(-1) >= 0 {
			log.Printf("Domain bypass relay intercepted SYN: dst=%s:%d", flow.dst, flow.port)
		}
	}
	packetCopy := append([]byte(nil), packet...)
	if _, err := p.dev.Write([][]byte{packetCopy}, 0); err != nil {
		return false, err
	}
	return true, nil
}

// Захваченные потоки живут ровно столько, сколько идёт соединение, плюс запас.
//
// Срок нужен потому, что конец соединения по одному лишь FIN/RST не опознать
// надёжно: закрывающие пакеты теряются, а половина закрытий приходит уже из
// netstack, а не с TUN. Час простоя — заведомо больше любого keepalive.
const (
	domainBypassFlowTTL = time.Hour

	// Потолок карты потоков. Не про память — про то, что запись в неё
	// делается на каждый SYN, и без предела шумящее приложение раздуло бы её
	// молча.
	domainBypassFlowCap = 4096
)

func (p *domainBypassProxy) isCapturedFlow(key domainBypassFlowKey) bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	seen, ok := p.capturedFlows[key]
	if !ok {
		return false
	}
	if time.Since(seen) > domainBypassFlowTTL {
		delete(p.capturedFlows, key)
		return false
	}
	p.capturedFlows[key] = time.Now()
	return true
}

func (p *domainBypassProxy) captureFlow(key domainBypassFlowKey) {
	now := time.Now()
	p.mu.Lock()
	defer p.mu.Unlock()
	// Подметаем здесь же: отдельная горутина ради карты, которая меняется только
	// на SYN, — лишняя сущность и лишний повод для гонки при остановке.
	if len(p.capturedFlows) >= domainBypassFlowCap {
		for k, seen := range p.capturedFlows {
			if now.Sub(seen) > domainBypassFlowTTL {
				delete(p.capturedFlows, k)
			}
		}
	}
	if len(p.capturedFlows) >= domainBypassFlowCap {
		return
	}
	p.capturedFlows[key] = now
}

// installLocalAddress поднимает адрес локальным в netstack.
//
// Свой учёт нужен ради счётчика installed: netstack умеет только добавлять
// адреса и не сообщает, сколько их у него. Именно этот счётчик показывает цену
// потолка в domainBypassLearnedCap — освободить установленный адрес нельзя.
// Слушатель и соединения на нём освобождаются (releaseAddrs), сам адрес — нет.
func (p *domainBypassProxy) installLocalAddress(addr netip.Addr) error {
	p.mu.Lock()
	_, exists := p.installedAddrs[addr]
	p.mu.Unlock()
	if exists {
		return nil
	}
	if err := p.net.EnsureLocalAddress(addr); err != nil {
		return err
	}
	p.mu.Lock()
	p.installedAddrs[addr] = struct{}{}
	domainBypassInstalled.Store(int64(len(p.installedAddrs)))
	p.mu.Unlock()
	return nil
}

func (p *domainBypassProxy) ensureFlowListener(flow domainBypassFlow) error {
	key := netip.AddrPortFrom(flow.dst, flow.port).String()
	p.mu.Lock()
	defer p.mu.Unlock()
	if _, exists := p.flowListeners[key]; exists {
		return nil
	}
	listener, err := p.net.ListenTCPAddrPort(netip.AddrPortFrom(flow.dst, flow.port))
	if err != nil {
		return err
	}
	p.flowListeners[key] = listener
	p.listeners = append(p.listeners, listener)
	domainBypassListenerCount.Store(int64(len(p.flowListeners)))
	p.wg.Add(1)
	go p.acceptLoop(listener)
	if domainBypassLogBudget.Add(-1) >= 0 {
		log.Printf("Domain bypass relay opened listener: %s", key)
	}
	return nil
}

func (p *domainBypassProxy) close() {
	p.closeOnce.Do(func() {
		// Снимаем себя с учёта первым делом: политика не должна дотягиваться до
		// релея, который уже разбирается.
		p.pumpAlive.Store(false)
		// «Сдался» переживает close(): именно этот отказ экран и должен
		// показать. Обычная остановка сессии возвращает состояние в «не
		// поднимался».
		domainBypassRelayState.CompareAndSwap(domainBypassRelayAlive, domainBypassRelayOff)
		domainBypassActiveProxy.CompareAndSwap(p, nil)
		p.cancel()
		p.mu.Lock()
		listeners := append([]net.Listener(nil), p.listeners...)
		p.mu.Unlock()
		for _, listener := range listeners {
			_ = listener.Close()
		}
		if p.dev != nil {
			_ = p.dev.Close()
		}
		_ = p.file.Close()
		done := make(chan struct{})
		go func() {
			defer close(done)
			p.wg.Wait()
		}()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
		}
		domainBypassListenerCount.Store(0)
		log.Printf("Domain bypass relay stopped")
	})
}

// pumpPacketsToAndroidTun возвращает ответные пакеты netstack в Android TUN.
func (p *domainBypassProxy) pumpPacketsToAndroidTun() {
	defer p.wg.Done()
	// Снимается до выхода при любом сценарии, включая panic: перехват без
	// насоса — это взаимная блокировка туннеля, а не деградация.
	defer p.pumpAlive.Store(false)
	buff := make([][]byte, 1)
	buff[0] = make([]byte, p.mtu+256)
	sizes := make([]int, 1)
	failures := 0
	for {
		select {
		case <-p.ctx.Done():
			return
		default:
		}
		n, err := p.dev.Read(buff, sizes, 0)
		if err != nil {
			if errors.Is(err, os.ErrClosed) || errors.Is(err, net.ErrClosed) {
				return
			}
			if p.pumpGaveUp(&failures, "read from netstack", err) {
				return
			}
			continue
		}
		if n <= 0 || sizes[0] <= 0 {
			continue
		}
		packet := append([]byte(nil), buff[0][:sizes[0]]...)
		if err := p.file.Write(packet); err != nil {
			if errors.Is(err, os.ErrClosed) || errors.Is(err, net.ErrClosed) {
				return
			}
			// Раньше здесь стоял `return`, и это был самый дорогой отказ в
			// ядре: насос уходил, перехват оставался, туннель вставал целиком
			// (см. domainBypassPumpRetries).
			if p.pumpGaveUp(&failures, "write to Android TUN", err) {
				return
			}
			continue
		}
		failures = 0
	}
}

// pumpGaveUp считает подряд идущие отказы насоса и решает, пора ли сдаваться.
//
// Возвращает true, когда терпение кончилось: к этому моменту перехват уже снят,
// а разбор релея запущен в отдельной горутине — своей ждать нельзя, close()
// дожидается той самой WaitGroup, в которой числится насос.
func (p *domainBypassProxy) pumpGaveUp(failures *int, what string, cause error) bool {
	select {
	case <-p.ctx.Done():
		return true
	default:
	}
	*failures++
	if *failures < domainBypassPumpRetries {
		log.Printf("Domain bypass relay %s failed (%d/%d): %v", what, *failures, domainBypassPumpRetries, cause)
		time.Sleep(domainBypassPumpRetryDelay)
		return false
	}
	log.Printf(
		"Domain bypass relay %s failed %d times in a row (%v) — relay stops capturing, traffic returns to the tunnel",
		what, *failures, cause,
	)
	p.pumpAlive.Store(false)
	domainBypassRelayState.Store(domainBypassRelayGaveUp)
	go p.close()
	return true
}

func (p *domainBypassProxy) acceptLoop(listener net.Listener) {
	defer p.wg.Done()
	for {
		conn, err := listener.Accept()
		if err != nil {
			select {
			case <-p.ctx.Done():
				return
			default:
				if ne, ok := err.(net.Error); ok && ne.Temporary() {
					time.Sleep(50 * time.Millisecond)
					continue
				}
				return
			}
		}
		p.wg.Add(1)
		go func() {
			defer p.wg.Done()
			p.handleConn(conn)
		}()
	}
}

// handleConn — весь смысл релея.
//
// conn.LocalAddr() у netstack — это исходный адрес назначения пакета, тот
// самый, который мы подняли локальным. Его же и набираем, но защищённым
// сокетом: protect() выводит его из-под маршрута туннеля, и поток уходит
// напрямую через сеть устройства.
func (p *domainBypassProxy) handleConn(conn net.Conn) {
	defer safeCloseConn(conn)

	localAddr, ok := conn.LocalAddr().(*net.TCPAddr)
	if !ok || localAddr == nil || localAddr.IP == nil || localAddr.Port <= 0 {
		return
	}
	targetAddr, ok := netip.AddrFromSlice(localAddr.IP)
	if !ok {
		return
	}
	targetAddr = targetAddr.Unmap()
	if !domainBypassKnows(targetAddr) {
		// Правила успели измениться, пока поток стоял в очереди на приём.
		return
	}
	domainBypassTouch(targetAddr)
	target := net.JoinHostPort(targetAddr.String(), strconv.Itoa(localAddr.Port))

	dialCtx, cancelDial := context.WithTimeout(p.ctx, 10*time.Second)
	upstream, err := protectedDialer(10*time.Second).DialContext(dialCtx, "tcp", target)
	cancelDial()
	if err != nil {
		if domainBypassLogBudget.Add(-1) >= 0 {
			log.Printf("Domain bypass relay dial failed: target=%s err=%v", target, err)
		}
		// Забываем адрес — иначе следующий SYN снова перехватывается, локальное
		// рукопожатие снова завершается, прямой набор снова падает, и до
		// туннеля соединение не доходит никогда. Разбор релея — не тот случай:
		// там адрес ни при чём.
		select {
		case <-p.ctx.Done():
		default:
			if domainBypassForget(targetAddr) {
				log.Printf("Domain bypass relay returned %s to the tunnel after a failed direct dial", targetAddr)
			}
		}
		return
	}
	defer safeCloseConn(upstream)
	setTcpNoDelay(upstream)
	domainBypassRelayed.Add(1)

	// Учёт живых соединений: снятие правила обязано их оборвать, иначе оно
	// действует только на новые, а старые идут наружу часами.
	session := &domainBypassSession{dst: targetAddr, client: conn, upstream: upstream}
	p.mu.Lock()
	p.sessions[session] = struct{}{}
	p.mu.Unlock()
	defer func() {
		p.mu.Lock()
		delete(p.sessions, session)
		p.mu.Unlock()
	}()
	if domainBypassLogBudget.Add(-1) >= 0 {
		log.Printf("Domain bypass relay connected: target=%s", target)
	}

	// Сессию рвём вместе с релеем: gonet-соединение само по себе о закрытии
	// устройства не узнает и могло бы удержать p.wg.Wait().
	watchDone := make(chan struct{})
	defer close(watchDone)
	go func() {
		select {
		case <-p.ctx.Done():
			safeCloseConn(conn)
			safeCloseConn(upstream)
		case <-watchDone:
		}
	}()

	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		_, _ = io.Copy(upstream, conn)
		domainBypassHalfClose(upstream)
	}()
	go func() {
		defer wg.Done()
		_, _ = io.Copy(conn, upstream)
		domainBypassHalfClose(conn)
	}()
	wg.Wait()
}

// domainBypassHalfClose закрывает только запись.
//
// Половинное закрытие обязательно: на 80 порту клиент, отправив запрос, ждёт
// ответа на том же соединении, и полное закрытие оборвало бы ответ на середине.
// И gonet.TCPConn, и *net.TCPConn умеют CloseWrite; если вдруг не умеет — рвём
// целиком, это всё равно лучше повисшей горутины.
func domainBypassHalfClose(conn net.Conn) {
	type halfCloser interface{ CloseWrite() error }
	if hc, ok := conn.(halfCloser); ok {
		_ = hc.CloseWrite()
		return
	}
	safeCloseConn(conn)
}

func parseDomainBypassFlow(packet []byte) (domainBypassFlow, bool) {
	if len(packet) < 24 {
		return domainBypassFlow{}, false
	}
	switch packet[0] >> 4 {
	case 4:
		return parseDomainBypassFlowIPv4(packet)
	case 6:
		return parseDomainBypassFlowIPv6(packet)
	default:
		return domainBypassFlow{}, false
	}
}

func parseDomainBypassFlowIPv4(packet []byte) (domainBypassFlow, bool) {
	ihl := int(packet[0]&0x0f) * 4
	if ihl < 20 || len(packet) < ihl+4 {
		return domainBypassFlow{}, false
	}
	proto := packet[9]
	if proto != domainBypassProtoTCP && proto != domainBypassProtoUDP {
		return domainBypassFlow{}, false
	}
	// Фрагмент без заголовка транспорта разбирать нечем.
	if binary.BigEndian.Uint16(packet[6:8])&0x1fff != 0 {
		return domainBypassFlow{}, false
	}
	if proto == domainBypassProtoTCP && len(packet) < ihl+20 {
		return domainBypassFlow{}, false
	}
	src := netip.AddrFrom4([4]byte{packet[12], packet[13], packet[14], packet[15]})
	dst := netip.AddrFrom4([4]byte{packet[16], packet[17], packet[18], packet[19]})
	srcPort := binary.BigEndian.Uint16(packet[ihl : ihl+2])
	dstPort := binary.BigEndian.Uint16(packet[ihl+2 : ihl+4])
	return domainBypassFlow{src: src, srcPort: srcPort, dst: dst, port: dstPort, proto: proto}, true
}

func parseDomainBypassFlowIPv6(packet []byte) (domainBypassFlow, bool) {
	if len(packet) < 44 {
		return domainBypassFlow{}, false
	}
	// Заголовки расширения не разбираются — так же, как в релее Telegram: с
	// туннеля приходит обычный TCP/UDP сразу за фиксированным заголовком.
	proto := packet[6]
	if proto != domainBypassProtoTCP && proto != domainBypassProtoUDP {
		return domainBypassFlow{}, false
	}
	if proto == domainBypassProtoTCP && len(packet) < 60 {
		return domainBypassFlow{}, false
	}
	src, okSrc := netip.AddrFromSlice(packet[8:24])
	dst, ok := netip.AddrFromSlice(packet[24:40])
	if !ok || !okSrc {
		return domainBypassFlow{}, false
	}
	srcPort := binary.BigEndian.Uint16(packet[40:42])
	dstPort := binary.BigEndian.Uint16(packet[42:44])
	return domainBypassFlow{
		src:     src.Unmap(),
		srcPort: srcPort,
		dst:     dst.Unmap(),
		port:    dstPort,
		proto:   proto,
	}, true
}

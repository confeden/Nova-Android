package nova

import (
	"context"
	"crypto/tls"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Разбор одного апстрима DNS: чем он говорит и каким путём до него идти.
//
// Весь список апстримов приходит из Java одной строкой через запятую, поэтому
// поля внутри одного апстрима разделяет `|` — запятая развалила бы список.
// Грамматика:
//
//	<цель>[|<токен>]*
//	цель  := https://... | tls://имя[:порт] | <IP>
//	токен := <IP>            — bootstrap-адрес для имени цели
//	       | via=tunnel      — идти через туннель
//	       | via=direct      — идти мимо туннеля (по умолчанию)
//	       | via=auto        — измерить оба и выбрать быстрый
//	       | via=obfs        — сначала мимо туннеля с фрагментированным ClientHello,
//	                           при отказе — через туннель (правило владельца для DNS-AI)
//	       | via=obfsonly    — то же, но без запасного туннеля: отказ отдаётся следующему
//	                           апстриму списка (цепочка входов DNS-AI)
//	       | obf=<имя>       — стратегия обфускации прямого пути вместо лестницы по хосту:
//	                           tcpmid, tcp1, recmid, plain, legacy (dns_obfs_strategy.go, kb D46)
//	       | m=get           — DoH по GET (`?dns=…`), а не POST: личные ссылки DNS-AI за CDN
//	                           на POST отвечают 413
//
// Нераспознанный токен пропускается молча — это намеренно: старое ядро уже
// пропускало всё, что не IP, и новый флаг на нём просто не сработает вместо того
// чтобы уронить резолвинг.
type dnsUpstreamKind int

const (
	dnsUpstreamPlain dnsUpstreamKind = iota
	dnsUpstreamDoH
	dnsUpstreamDoT
)

// Каким путём идёт запрос к апстриму.
//
// Разница не косметическая. `direct` — сокет помечен `protect()`, пакет уходит
// мимо TUN по нижележащей сети: так резолвинг работает, пока туннель ещё
// поднимается, и так же он работает, когда туннель мёртв. `tunnel` — сокет не
// помечен, запрос идёт внутрь туннеля: провайдер не видит даже факта обращения к
// резолверу, но пока туннеля нет, нет и резолвинга.
type dnsUpstreamRoute int

const (
	dnsRouteDirect dnsUpstreamRoute = iota
	dnsRouteTunnel
	dnsRouteAuto
	// dnsRouteObfsFirst — режим апстрима: прямой путь с обфускацией, туннель запасной.
	dnsRouteObfsFirst
	// dnsRouteDirectObfs — путь одной попытки: protect() плюс фрагментированный
	// ClientHello. В строке апстрима не пишется, его выбирает dnsRouteObfsFirst.
	dnsRouteDirectObfs
	// dnsRouteObfsOnly — режим апстрима: только прямой путь с обфускацией, без своего запасного
	// туннеля. Нужен цепочке входов DNS-AI: все прямые входы идут один за другим, и только после
	// них те же входы через туннель отдельными строками (`via=tunnel`). Пока прямой путь снят
	// после неудачи, отказывает сразу, не тратя таймаут.
	dnsRouteObfsOnly
)

const (
	dnsUpstreamTokenSeparator = "|"
	dnsRouteTokenPrefix       = "via="
	dnsMethodGetToken         = "m=get"
	dnsObfTokenPrefix         = "obf="
	dotDefaultPort            = "853"
)

type dnsUpstreamSpec struct {
	raw       string
	kind      dnsUpstreamKind
	target    string // DoH: URL; DoT: host:port; plain: IP
	host      string
	port      string
	bootstrap []string
	route     dnsUpstreamRoute
	// get — DoH-запрос уходит методом GET.
	get bool
	// obf — имя стратегии обфускации, заданное токеном `obf=`; пусто — лестница по хосту.
	obf string
	// display — цель для журнала и меток: у личной ссылки путь (токен-секрет) заменён на «…».
	display string
}

// encrypted отвечает на единственный вопрос, ради которого различие вообще
// заведено: можно ли доверять быстрому ответу.
//
// У DoH и DoT ответ подписан TLS-сертификатом нашего сервера, поэтому «кто
// ответил первым» — честное сравнение путей. У открытого UDP быстрый ответ может
// быть подделкой провайдера, и гонка путей выбрала бы именно её.
func (s *dnsUpstreamSpec) encrypted() bool {
	return s.kind == dnsUpstreamDoH || s.kind == dnsUpstreamDoT
}

func parseDNSUpstream(raw string) (*dnsUpstreamSpec, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return nil, errors.New("empty dns upstream")
	}
	spec := &dnsUpstreamSpec{raw: trimmed, route: dnsRouteDirect}
	target := trimmed
	if idx := strings.Index(trimmed, dnsUpstreamTokenSeparator); idx >= 0 {
		target = strings.TrimSpace(trimmed[:idx])
		for _, item := range strings.Split(trimmed[idx+1:], dnsUpstreamTokenSeparator) {
			token := strings.TrimSpace(item)
			if token == "" {
				continue
			}
			if strings.HasPrefix(strings.ToLower(token), dnsRouteTokenPrefix) {
				switch strings.ToLower(strings.TrimPrefix(strings.ToLower(token), dnsRouteTokenPrefix)) {
				case "tunnel":
					spec.route = dnsRouteTunnel
				case "auto":
					spec.route = dnsRouteAuto
				case "obfs":
					spec.route = dnsRouteObfsFirst
				case "obfsonly":
					spec.route = dnsRouteObfsOnly
				default:
					spec.route = dnsRouteDirect
				}
				continue
			}
			if strings.EqualFold(token, dnsMethodGetToken) {
				spec.get = true
				continue
			}
			if strings.HasPrefix(strings.ToLower(token), dnsObfTokenPrefix) {
				if name := strings.TrimSpace(token[len(dnsObfTokenPrefix):]); obfsStrategyByName(name) != nil {
					spec.obf = strings.ToLower(name)
				}
				continue
			}
			if net.ParseIP(token) == nil {
				continue
			}
			spec.bootstrap = append(spec.bootstrap, token)
		}
	}
	if target == "" {
		return nil, fmt.Errorf("dns upstream without a target: %s", trimmed)
	}

	lowered := strings.ToLower(target)
	switch {
	case strings.HasPrefix(lowered, "https://"):
		doh, err := parseDohUpstream(target)
		if err != nil {
			return nil, err
		}
		spec.kind = dnsUpstreamDoH
		spec.target = doh.url
		spec.display = redactDohURL(doh.url)
		spec.host = doh.host
		spec.port = doh.port
	case strings.HasPrefix(lowered, "tls://"):
		hostPort := strings.TrimSpace(target[len("tls://"):])
		host, port := hostPort, dotDefaultPort
		if h, p, err := net.SplitHostPort(hostPort); err == nil {
			host, port = h, p
		}
		host = strings.Trim(strings.TrimSpace(host), "[]")
		if host == "" {
			return nil, fmt.Errorf("DoT upstream without host: %s", target)
		}
		spec.kind = dnsUpstreamDoT
		spec.host = host
		spec.port = port
		spec.target = net.JoinHostPort(host, port)
	default:
		if net.ParseIP(target) == nil {
			return nil, fmt.Errorf("not an IP address: %s", target)
		}
		spec.kind = dnsUpstreamPlain
		spec.host = target
		spec.port = "53"
		spec.target = target
	}

	// Открытый UDP не участвует в гонке путей: подделанный ответ приходит
	// быстрее настоящего, и «автоматически» выбрало бы подделку.
	if (spec.route == dnsRouteAuto || spec.route == dnsRouteObfsFirst || spec.route == dnsRouteObfsOnly) && !spec.encrypted() {
		spec.route = dnsRouteDirect
	}
	if spec.display == "" {
		spec.display = spec.target
	}
	// GET имеет смысл только у DoH.
	if spec.kind != dnsUpstreamDoH {
		spec.get = false
	}
	// Личная ссылка DNS-AI всегда по GET, даже если токен `m=get` не пришёл: CDN за ней на POST
	// отвечает 413, так что POST для неё не вариант ни при каких настройках.
	if spec.kind == dnsUpstreamDoH && isPersonalDNSAILink(spec.target) {
		spec.get = true
	}
	return spec, nil
}

// isPersonalDNSAILink — адрес вида `https://<имя>.dns-ai.ru/<токен>`: поддомен dns-ai.ru с одним
// сегментом пути, который не `dns-query`. Шаблон `{?dns}` до ядра не доходит, приложение его снимает.
func isPersonalDNSAILink(raw string) bool {
	parsed, err := url.Parse(raw)
	if err != nil {
		return false
	}
	host := strings.ToLower(strings.TrimSuffix(parsed.Hostname(), "."))
	if !strings.HasSuffix(host, ".dns-ai.ru") {
		return false
	}
	path := strings.Trim(parsed.Path, "/")
	return path != "" && path != "dns-query" && !strings.Contains(path, "/")
}

// redactUpstreamList готовит список апстримов для журнала: у каждого строки цель пропущена через
// [redactDohURL], остальные токены (bootstrap, via=, m=) остаются.
func redactUpstreamList(upstreams []string) string {
	out := make([]string, 0, len(upstreams))
	for _, entry := range upstreams {
		target, rest, found := strings.Cut(entry, dnsUpstreamTokenSeparator)
		if strings.HasPrefix(strings.ToLower(strings.TrimSpace(target)), "https://") {
			target = redactDohURL(strings.TrimSpace(target))
		}
		if found {
			target += dnsUpstreamTokenSeparator + rest
		}
		out = append(out, target)
	}
	return strings.Join(out, ",")
}

// redactDohURL прячет путь DoH-ссылки: у личных ссылок DNS-AI (`https://ru.dns-ai.ru/<токен>`)
// путь — это секрет устройства, и в журнал он не попадает. Обычный `/dns-query` остаётся.
func redactDohURL(raw string) string {
	parsed, err := url.Parse(raw)
	if err != nil || parsed.Host == "" {
		return raw
	}
	path := strings.TrimRight(parsed.Path, "/")
	if path == "" || path == "/dns-query" {
		return raw
	}
	return parsed.Scheme + "://" + parsed.Host + "/…"
}

// -- выбор пути -----------------------------------------------------------

// Победивший путь запоминается, чтобы не гонять оба на каждом запросе: гонка
// стоит второго сокета и второго рукопожатия, а сеть между двумя запросами
// браузера не меняется. Срок короткий намеренно — переезд из Wi-Fi в LTE меняет
// ответ, и держать вчерашний выбор час значило бы отвечать по мёртвому пути.
const dnsRouteChoiceTTL = 5 * time.Minute

type dnsRouteChoice struct {
	route dnsUpstreamRoute
	at    time.Time
}

var (
	dnsRouteChoiceMu sync.Mutex
	dnsRouteChoices  = make(map[string]dnsRouteChoice)
)

func rememberDNSRoute(raw string, route dnsUpstreamRoute) {
	dnsRouteChoiceMu.Lock()
	dnsRouteChoices[raw] = dnsRouteChoice{route: route, at: time.Now()}
	dnsRouteChoiceMu.Unlock()
}

func recallDNSRoute(raw string) (dnsUpstreamRoute, bool) {
	dnsRouteChoiceMu.Lock()
	defer dnsRouteChoiceMu.Unlock()
	choice, ok := dnsRouteChoices[raw]
	if !ok || time.Since(choice.at) > dnsRouteChoiceTTL {
		return dnsRouteDirect, false
	}
	return choice.route, true
}

func resetDNSRouteChoices() {
	dnsRouteChoiceMu.Lock()
	dnsRouteChoices = make(map[string]dnsRouteChoice)
	dnsRouteChoiceMu.Unlock()
	resetObfsDirectState()
}

// DNSRouteChoiceLabel отдаёт выбранный путь для журнала приложения.
// Пусто — выбор ещё не сделан или устарел.
func DNSRouteChoiceLabel(raw string) string {
	if label := obfsDirectLabel(strings.TrimSpace(raw)); label != "" {
		return label
	}
	route, ok := recallDNSRoute(strings.TrimSpace(raw))
	if !ok {
		return ""
	}
	if route == dnsRouteTunnel {
		return "tunnel"
	}
	return "direct"
}

// -- защита от самоперехвата ---------------------------------------------

// Сокет без `protect()` уходит в TUN, и его же пакет возвращается в
// `AndroidTUN.Read`, где перехват снова видит UDP на порт 53 и открывает
// следующий такой сокет. Получается воронка, ограниченная только очередью на 32
// места. Поэтому исходящий порт каждого незащищённого сокета запоминается, и
// перехват такие пакеты пропускает.
//
// Запоминаются **только незащищённые**: защищённый сокет в TUN не попадает
// вовсе, а лишняя запись означала бы, что чужой запрос с тем же случайным
// исходящим портом перестанет перехватываться.
var (
	coreDNSPortsMu sync.RWMutex
	coreDNSPorts   = make(map[uint16]int)
)

func rememberCoreDNSPort(port uint16) {
	if port == 0 {
		return
	}
	coreDNSPortsMu.Lock()
	coreDNSPorts[port]++
	coreDNSPortsMu.Unlock()
}

func forgetCoreDNSPort(port uint16) {
	if port == 0 {
		return
	}
	coreDNSPortsMu.Lock()
	if coreDNSPorts[port] <= 1 {
		delete(coreDNSPorts, port)
	} else {
		coreDNSPorts[port]--
	}
	coreDNSPortsMu.Unlock()
}

func isCoreDNSPort(port uint16) bool {
	coreDNSPortsMu.RLock()
	_, ok := coreDNSPorts[port]
	coreDNSPortsMu.RUnlock()
	return ok
}

// -- DoT ------------------------------------------------------------------

// RFC 7858: TCP/853, TLS, и каждое сообщение с двухбайтовой длиной впереди.
//
// Соединение переиспользуется по той же причине, что и у DoH: рукопожатие на
// каждый запрос стоит дороже самого запроса, и открытый UDP выигрывал бы у
// шифрованного апстрима настолько, что первый же таймаут увёл бы резолвинг на
// него.
type dotConn struct {
	mu   sync.Mutex
	conn *tls.Conn
}

var (
	dotConnsMu sync.Mutex
	dotConns   = make(map[string]*dotConn)
)

func dotConnFor(key string) *dotConn {
	dotConnsMu.Lock()
	defer dotConnsMu.Unlock()
	if existing, ok := dotConns[key]; ok {
		return existing
	}
	created := &dotConn{}
	dotConns[key] = created
	return created
}

func resetDotConns() {
	dotConnsMu.Lock()
	pooled := make([]*dotConn, 0, len(dotConns))
	for _, entry := range dotConns {
		pooled = append(pooled, entry)
	}
	dotConns = make(map[string]*dotConn)
	dotConnsMu.Unlock()
	for _, entry := range pooled {
		entry.mu.Lock()
		if entry.conn != nil {
			_ = entry.conn.Close()
			entry.conn = nil
		}
		entry.mu.Unlock()
	}
}

func dialDoT(spec *dnsUpstreamSpec, route dnsUpstreamRoute, attempt *dnsAttempt, timeout time.Duration) (*tls.Conn, error) {
	dialer := &net.Dialer{
		Timeout:   timeout,
		KeepAlive: 30 * time.Second,
		Control: func(_ string, _ string, rawConn syscall.RawConn) error {
			if route == dnsRouteTunnel {
				return nil
			}
			return protectRawConn(rawConn)
		},
	}
	// Имя резолвера решать некому: системный резолвер внутри туннеля ведёт в
	// этот же перехват. Поэтому по имени ходим только если приложение не дало
	// bootstrap-адресов — и тогда это уже его выбор, а не наш недосмотр.
	addresses := make([]string, 0, len(spec.bootstrap)+1)
	if net.ParseIP(spec.host) != nil {
		addresses = append(addresses, net.JoinHostPort(spec.host, spec.port))
	} else {
		if attempt != nil && attempt.pin != "" {
			addresses = append(addresses, net.JoinHostPort(attempt.pin, spec.port))
		} else {
			for _, ip := range spec.bootstrap {
				addresses = append(addresses, net.JoinHostPort(ip, spec.port))
			}
			addresses = append(addresses, net.JoinHostPort(spec.host, spec.port))
		}
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	// Имя в сертификате — всегда имя из настройки, даже когда дозвонились по
	// bootstrap-адресу: иначе проверка сертификата отключается ровно там, где
	// она и нужна.
	serverName := spec.host
	var lastErr error
	for _, address := range addresses {
		raw, err := dialer.DialContext(ctx, "tcp", address)
		if err != nil {
			lastErr = err
			continue
		}
		var strat *obfsStrategy
		if attempt != nil {
			strat = attempt.strat
		}
		raw = obfuscateDNSConn(raw, route, strat)
		tlsConn := tls.Client(raw, &tls.Config{
			ServerName: serverName,
			MinVersion: tls.VersionTLS12,
			NextProtos: []string{"dot"},
		})
		if err := tlsConn.HandshakeContext(ctx); err != nil {
			_ = tlsConn.Close()
			lastErr = err
			continue
		}
		return tlsConn, nil
	}
	if lastErr == nil {
		lastErr = fmt.Errorf("no address answered for %s", spec.host)
	}
	return nil, lastErr
}

func dotExchange(conn *tls.Conn, query []byte, timeout time.Duration) ([]byte, error) {
	_ = conn.SetDeadline(time.Now().Add(timeout))
	framed := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(framed[:2], uint16(len(query)))
	copy(framed[2:], query)
	if _, err := conn.Write(framed); err != nil {
		return nil, err
	}
	var lengthPrefix [2]byte
	if _, err := io.ReadFull(conn, lengthPrefix[:]); err != nil {
		return nil, err
	}
	length := int(binary.BigEndian.Uint16(lengthPrefix[:]))
	if length < 12 || length > 65535 {
		return nil, fmt.Errorf("dot answer of impossible size %d", length)
	}
	answer := make([]byte, length)
	if _, err := io.ReadFull(conn, answer); err != nil {
		return nil, err
	}
	return answer, nil
}

// resolveDNSViaDoT делает один обмен, переиспользуя соединение и повторяя один
// раз на разорванном.
//
// Повтор ровно один и только при живом ранее соединении: сервер закрывает
// простоявший канал молча, и первая же ошибка на нём — это «переподключись», а
// не «сеть не работает». Второй отказ уже настоящий, и повторять его значило бы
// платить таймаут дважды.
func resolveDNSViaDoT(query []byte, spec *dnsUpstreamSpec, route dnsUpstreamRoute, attempt *dnsAttempt, timeout time.Duration) ([]byte, error) {
	if len(query) < 12 {
		return nil, errors.New("dns payload too short")
	}
	entry := dotConnFor(routeKey(spec.raw, route) + "#" + attempt.key())
	entry.mu.Lock()
	defer entry.mu.Unlock()

	if entry.conn != nil {
		answer, err := dotExchange(entry.conn, query, timeout)
		if err == nil {
			return answer, nil
		}
		_ = entry.conn.Close()
		entry.conn = nil
	}

	conn, err := dialDoT(spec, route, attempt, timeout)
	if err != nil {
		return nil, err
	}
	answer, err := dotExchange(conn, query, timeout)
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	entry.conn = conn
	return answer, nil
}

// routeKey отличает два соединения к одному апстриму по пути.
//
// Без него `via=auto` меряло бы один и тот же прогретый канал дважды и всегда
// объявляло бы победителем тот путь, которым сходили первым.
func routeKey(raw string, route dnsUpstreamRoute) string {
	switch route {
	case dnsRouteTunnel:
		return raw + "#tunnel"
	case dnsRouteDirectObfs, dnsRouteObfsOnly:
		return raw + "#obfs"
	default:
		return raw + "#direct"
	}
}

func forgetDNSRoute(raw string) {
	dnsRouteChoiceMu.Lock()
	delete(dnsRouteChoices, raw)
	dnsRouteChoiceMu.Unlock()
}

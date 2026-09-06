package nova

import (
	"bytes"
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"syscall"
	"time"
)

// Апстрим DNS-over-HTTPS для перехвата DNS в туннеле.
//
// Зачем он здесь. Перехват в ядре резолвит имена наружу, по защищённому сокету:
// пакет уходит по сети оператора мимо туннеля. Для обычного UDP-резолвера это
// значит, что провайдер видит весь список посещённых доменов, даже когда включён
// Proton или WARP, — то есть ровно то, от чего пользователь и прячется. DoH тот же
// путь оставляет, но имена в нём зашифрованы: наружу видно только TLS-соединение с
// самим резолвером.
//
// Формат строки апстрима: `https://host/path` либо `https://host/path|ip1|ip2`.
// Второй вид обязателен на практике: имя резолвера надо во что-то развернуть, а
// системный резолвер внутри туннеля ведёт обратно в этот же перехват. Поэтому
// адреса приходят из приложения — оно резолвит их по нижележащей сети до подъёма
// TUN.
//
// Разделитель именно `|`, а не запятая: весь список апстримов приходит из Java
// одной строкой через запятую, и адрес с запятой внутри развалил бы её на два
// куска — URL без bootstrap и «резолвер» из голого IP.
type dohUpstream struct {
	raw       string
	url       string
	host      string
	port      string
	bootstrap []string
}

const dohBootstrapSeparator = "|"

// Клиенты кэшируются по строке апстрима: keep-alive соединение экономит TLS-
// рукопожатие на каждом запросе, а без него DoH проигрывал бы UDP по задержке
// настолько, что первый же таймаут увёл бы резолвинг на запасной открытый DNS.
var (
	dohClientsMu sync.Mutex
	dohClients   = make(map[string]*http.Client)
)

func isDohUpstream(raw string) bool {
	return strings.HasPrefix(strings.ToLower(strings.TrimSpace(raw)), "https://")
}

func parseDohUpstream(raw string) (*dohUpstream, error) {
	trimmed := strings.TrimSpace(raw)
	if !isDohUpstream(trimmed) {
		return nil, fmt.Errorf("not a DoH upstream: %s", trimmed)
	}
	spec := trimmed
	bootstrap := make([]string, 0, 4)
	if idx := strings.Index(trimmed, dohBootstrapSeparator); idx >= 0 {
		spec = strings.TrimSpace(trimmed[:idx])
		for _, item := range strings.Split(trimmed[idx+1:], dohBootstrapSeparator) {
			address := strings.TrimSpace(item)
			if address == "" {
				continue
			}
			if net.ParseIP(address) == nil {
				continue
			}
			bootstrap = append(bootstrap, address)
		}
	}
	parsed, err := url.Parse(spec)
	if err != nil {
		return nil, fmt.Errorf("parse DoH url %s: %w", spec, err)
	}
	host := parsed.Hostname()
	if host == "" {
		return nil, fmt.Errorf("DoH url without host: %s", spec)
	}
	port := parsed.Port()
	if port == "" {
		port = "443"
	}
	return &dohUpstream{
		raw:       trimmed,
		url:       spec,
		host:      host,
		port:      port,
		bootstrap: bootstrap,
	}, nil
}

// protectRawConn помечает сокет как «мимо VPN» тем же протектором, что и весь
// остальной служебный трафик ядра. Без этого DoH-соединение ушло бы в туннель,
// который в этот момент как раз и резолвит через нас имя своего же узла.
func protectRawConn(rawConn syscall.RawConn) error {
	if GlobalProtector == nil {
		return nil
	}
	var protectErr error
	controlErr := rawConn.Control(func(fd uintptr) {
		if !GlobalProtector(int(fd)) {
			protectErr = errors.New("protect returned false")
		}
	})
	if controlErr != nil {
		return controlErr
	}
	return protectErr
}

// Клиент кэшируется по паре «апстрим + путь», а не по одному апстриму: при
// `via=auto` один и тот же URL опрашивается и мимо туннеля, и через него, и
// общий keep-alive означал бы, что второй путь измеряет соединение первого.
func dohClientFor(upstream *dohUpstream, route dnsUpstreamRoute, timeout time.Duration) *http.Client {
	key := routeKey(upstream.raw, route)
	dohClientsMu.Lock()
	defer dohClientsMu.Unlock()
	if client, ok := dohClients[key]; ok {
		return client
	}

	dialer := &net.Dialer{
		Timeout:   timeout,
		KeepAlive: 30 * time.Second,
		Control: func(_ string, _ string, rawConn syscall.RawConn) error {
			// Через туннель — намеренно без `protect()`: именно отсутствие метки
			// и отправляет пакет в TUN. Зацикливания тут быть не может, перехват
			// смотрит только на UDP/53.
			if route == dnsRouteTunnel {
				return nil
			}
			return protectRawConn(rawConn)
		},
	}
	bootstrap := append([]string(nil), upstream.bootstrap...)
	transport := &http.Transport{
		Proxy: nil,
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(address)
			if err != nil {
				return dialer.DialContext(ctx, network, address)
			}
			if !strings.EqualFold(host, upstream.host) || len(bootstrap) == 0 {
				return dialer.DialContext(ctx, network, address)
			}
			var lastErr error
			for _, ip := range bootstrap {
				conn, dialErr := dialer.DialContext(ctx, network, net.JoinHostPort(ip, port))
				if dialErr == nil {
					return conn, nil
				}
				lastErr = dialErr
			}
			if lastErr == nil {
				lastErr = fmt.Errorf("no bootstrap address answered for %s", upstream.host)
			}
			return nil, lastErr
		},
		// HTTP/2 обязателен: RFC 8484 §5.2 разрешает серверу отказать HTTP/1.1, и
		// dns.dns-ai.ru именно так и делает — на HTTP/1.1 он отвечает 505.
		ForceAttemptHTTP2: true,
		TLSClientConfig: &tls.Config{
			ServerName: upstream.host,
			MinVersion: tls.VersionTLS12,
			NextProtos: []string{"h2", "http/1.1"},
		},
		TLSHandshakeTimeout:   timeout,
		ResponseHeaderTimeout: timeout,
		MaxIdleConns:          4,
		MaxIdleConnsPerHost:   2,
		IdleConnTimeout:       90 * time.Second,
	}
	client := &http.Client{Transport: transport}
	dohClients[key] = client
	return client
}

// resetDohClients закрывает простаивающие соединения при смене конфигурации.
// Кэш живёт до конца процесса, а сеть под ним меняется вместе с туннелем: старое
// keep-alive соединение после переподключения ведёт в никуда и стоит одного
// таймаута на каждый запрос.
func resetDohClients() {
	dohClientsMu.Lock()
	clients := make([]*http.Client, 0, len(dohClients))
	for _, client := range dohClients {
		clients = append(clients, client)
	}
	dohClients = make(map[string]*http.Client)
	dohClientsMu.Unlock()
	for _, client := range clients {
		if transport, ok := client.Transport.(*http.Transport); ok {
			transport.CloseIdleConnections()
		}
	}
}

// resolveDNSViaDoh отправляет перехваченный запрос по RFC 8484.
//
// Идентификатор транзакции обнуляется на время запроса (так требует §4.1: он
// мешает кэшированию) и восстанавливается в ответе, иначе вызывающая сторона
// сочтёт ответ чужим.
func resolveDNSViaDoh(query []byte, upstream *dohUpstream, route dnsUpstreamRoute, timeout time.Duration) ([]byte, error) {
	if len(query) < 12 {
		return nil, errors.New("dns payload too short")
	}
	body := append([]byte(nil), query...)
	transactionHigh, transactionLow := body[0], body[1]
	body[0], body[1] = 0, 0

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	request, err := http.NewRequestWithContext(ctx, http.MethodPost, upstream.url, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	request.Header.Set("Content-Type", "application/dns-message")
	request.Header.Set("Accept", "application/dns-message")
	request.ContentLength = int64(len(body))

	response, err := dohClientFor(upstream, route, timeout).Do(request)
	if err != nil {
		return nil, err
	}
	defer func() {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		_ = response.Body.Close()
	}()
	if response.StatusCode < 200 || response.StatusCode > 299 {
		return nil, fmt.Errorf("doh status %d", response.StatusCode)
	}
	payload, err := io.ReadAll(io.LimitReader(response.Body, 65535))
	if err != nil {
		return nil, err
	}
	if len(payload) < 12 {
		return nil, fmt.Errorf("short doh response (%d bytes)", len(payload))
	}
	payload[0], payload[1] = transactionHigh, transactionLow
	return payload, nil
}

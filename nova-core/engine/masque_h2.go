package nova

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"net/netip"
	"syscall"

	connectip "github.com/Diniboy1123/connect-ip-go"
	"github.com/yosida95/uritemplate/v3"
	"golang.org/x/net/http2"
)

type masquePacketConn interface {
	Close() error
	ReadPacket([]byte, bool) (int, error)
	WritePacket([]byte) ([]byte, error)
	LocalPrefixes(context.Context) ([]netip.Prefix, error)
	Routes(context.Context) ([]connectip.IPRoute, error)
}

type masqueCloseError struct {
	Remote bool
}

func (e *masqueCloseError) Error() string        { return net.ErrClosed.Error() }
func (e *masqueCloseError) Is(target error) bool { return target == net.ErrClosed }

// connectMasqueTunnelTCP открывает CONNECT-IP поверх HTTP/2 и TCP.
//
// Единственный путь MASQUE, не использующий UDP. Нужен там, где QUIC режется как
// класс: на МегаФон LTE замер показал, что рукопожатие QUIC не проходит вовсе,
// тогда как обычный TLS поверх TCP на 443 выглядит как всякий другой сайт.
//
// Предыдущая версия была написана вручную и не работала по трём причинам сразу,
// причём отказ выглядел как отказ Cloudflare, а был наш:
//
//  1. Мы ставили заголовок `:protocol`, и `x/net/http2` объявлял запрос extended
//     CONNECT, а затем отказывался его отправлять с «extended connect not supported
//     by peer» — ДО отправки. Мы записали в отрицательное знание, что Cloudflare
//     не поддерживает h2, ни разу его не спросив. Эталон шлёт обычный CONNECT и
//     помечает туннель своими заголовками `cf-connect-proto`.
//  2. В капсулу уходил лишний идентификатор контекста: на h2 полезная нагрузка —
//     сам IP-пакет, без varint.
//  3. На чтении мы снимали тот же несуществующий идентификатор, то есть портили
//     первый байт каждого входящего пакета и молча его отбрасывали.
//
// Поэтому обрамление больше не пишется здесь: `connectip.DialH2` — тот же код,
// что у эталонных клиентов.
func connectMasqueTunnelTCP(
	ctx context.Context,
	tlsConfig *tls.Config,
	endpointHost string,
	endpointPort int,
) (masquePacketConn, *http.Response, error) {
	if endpointPort <= 0 {
		endpointPort = 443
	}
	dialAddr := net.JoinHostPort(trimBrackets(endpointHost), fmt.Sprintf("%d", endpointPort))

	clone := tlsConfig.Clone()
	// Ровно h2: если оставить http/1.1, сервер вправе выбрать его, и клиент
	// HTTP/2 поедет по соединению, которое его не понимает.
	clone.NextProtos = []string{"h2"}

	tlsDialer := &tls.Dialer{
		NetDialer: &net.Dialer{Control: dialerControlProtectTCP()},
		Config:    clone,
	}
	netConn, err := tlsDialer.DialContext(ctx, "tcp", dialAddr)
	if err != nil {
		return nil, nil, fmt.Errorf("failed to dial MASQUE TLS/TCP: %w", err)
	}

	transport := &http2.Transport{}
	clientConn, err := transport.NewClientConn(netConn)
	if err != nil {
		_ = netConn.Close()
		return nil, nil, fmt.Errorf("failed to create HTTP/2 client conn: %w", err)
	}

	template := uritemplate.MustNew(masqueConnectURI)
	headers := http.Header{
		// Проприетарная разметка Cloudflare вместо `:protocol`.
		"cf-connect-proto": []string{"cf-connect-ip"},
		"pq-enabled":       []string{"false"},
		"User-Agent":       []string{""},
	}

	ipConn, rsp, err := connectip.DialH2(
		ctx,
		&http.Client{Transport: h2SingleConnTransport{conn: clientConn}},
		template,
		headers,
	)
	if err != nil {
		_ = clientConn.Close()
		_ = netConn.Close()
		return nil, nil, fmt.Errorf("failed to open MASQUE HTTP/2 CONNECT-IP: %w", err)
	}
	return ipConn, rsp, nil
}

// h2SingleConnTransport гоняет запросы по уже установленному соединению.
//
// `http2.Transport.NewClientConn` даёт готовое соединение, а `connectip.DialH2`
// принимает `*http.Client`; иначе клиент открыл бы своё соединение и потерял бы
// и наш TLS-конфиг с прикреплённым ключом, и защиту сокета от собственного VPN.
type h2SingleConnTransport struct {
	conn *http2.ClientConn
}

func (t h2SingleConnTransport) RoundTrip(req *http.Request) (*http.Response, error) {
	return t.conn.RoundTrip(req)
}

func trimBrackets(host string) string {
	if len(host) >= 2 && host[0] == '[' && host[len(host)-1] == ']' {
		return host[1 : len(host)-1]
	}
	return host
}

func dialerControlProtectTCP() func(string, string, syscall.RawConn) error {
	return func(_, _ string, c syscall.RawConn) error {
		if GlobalProtector == nil {
			return nil
		}
		var protectErr error
		if err := c.Control(func(fd uintptr) {
			if !GlobalProtector(int(fd)) {
				protectErr = fmt.Errorf("protect(%d) returned false", fd)
			}
		}); err != nil {
			return err
		}
		return protectErr
	}
}

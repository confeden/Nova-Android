package connectip

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"sort"
	"strings"

	"github.com/quic-go/quic-go/http3"
	"github.com/yosida95/uritemplate/v3"
)

// Dial dials a proxied connection to a target server.
func Dial(ctx context.Context, conn *http3.ClientConn, template *uritemplate.Template, requestProtocol string, additionalHeaders http.Header, ignoreExtendedConnect bool) (*Conn, *http.Response, error) {
	if len(template.Varnames()) > 0 {
		return nil, nil, errors.New("connect-ip: IP flow forwarding not supported")
	}

	u, err := url.Parse(template.Raw())
	if err != nil {
		return nil, nil, fmt.Errorf("connect-ip: failed to parse URI: %w", err)
	}

	select {
	case <-ctx.Done():
		return nil, nil, context.Cause(ctx)
	case <-conn.Context().Done():
		return nil, nil, context.Cause(conn.Context())
	case <-conn.ReceivedSettings():
	}
	settings := conn.Settings()
	if !ignoreExtendedConnect && !settings.EnableExtendedConnect {
		return nil, nil, errors.New("connect-ip: server didn't enable Extended CONNECT")
	}
	if !settings.EnableDatagrams {
		return nil, nil, errors.New("connect-ip: server didn't enable datagrams")
	}

	headers := http.Header{http3.CapsuleProtocolHeader: []string{capsuleProtocolHeaderValue}}
	for k, v := range additionalHeaders {
		headers[k] = v
	}

	// Печатаем запрос до шифрования.
	//
	// Nova: на устройстве нет root, снять tcpdump нечем, а внутри QUIC всё зашифровано —
	// это единственное место, где видно, что мы на самом деле посылаем. Одна и та же
	// функция вызывается и эталонной пробой, и службой, поэтому строки сравнимы дословно.
	logConnectIPRequest(u, requestProtocol, headers, settings)

	rstr, err := conn.OpenRequestStream(ctx)
	if err != nil {
		return nil, nil, fmt.Errorf("connect-ip: failed to open request stream: %w", err)
	}
	if err := rstr.SendRequestHeader(&http.Request{
		Method: http.MethodConnect,
		Proto:  requestProtocol,
		Host:   u.Host,
		Header: headers,
		URL:    u,
	}); err != nil {
		return nil, nil, fmt.Errorf("connect-ip: failed to send request: %w", err)
	}
	// TODO: optimistically return the connection
	rsp, err := rstr.ReadResponse()
	if err != nil {
		return nil, nil, fmt.Errorf("connect-ip: failed to read response: %w", err)
	}
	if rsp.StatusCode < 200 || rsp.StatusCode > 299 {
		return nil, rsp, fmt.Errorf("connect-ip: server responded with %d", rsp.StatusCode)
	}
	return newProxiedConn(rstr), rsp, nil
}

// logConnectIPRequest печатает запрос CONNECT-IP так, как он уйдёт в поток.
func logConnectIPRequest(u *url.URL, requestProtocol string, headers http.Header, settings *http3.Settings) {
	names := make([]string, 0, len(headers))
	for name := range headers {
		names = append(names, name)
	}
	sort.Strings(names)
	rendered := make([]string, 0, len(names))
	for _, name := range names {
		rendered = append(rendered, name+": "+strings.Join(headers[name], ","))
	}
	extendedConnect := false
	datagrams := false
	if settings != nil {
		extendedConnect = settings.EnableExtendedConnect
		datagrams = settings.EnableDatagrams
	}
	log.Printf(
		"CONNECT-IP запрос: :method=CONNECT :protocol=%q :authority=%q :path=%q scheme=%q; "+
			"заголовки [%s]; SETTINGS сервера extended_connect=%v датаграммы=%v",
		requestProtocol, u.Host, u.Path, u.Scheme,
		strings.Join(rendered, "; "), extendedConnect, datagrams,
	)
}

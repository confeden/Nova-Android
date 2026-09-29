package main

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// QueryResult holds the outcome of a single DNS query.
type QueryResult struct {
	RawResponse []byte
	Msg         *DNSMessage
	RTT         time.Duration
	Err         error
	ErrStage    string // precise failure stage for diagnostics
	RespSize    int    // raw response size in bytes
}

// Transport is the interface all DNS transports implement.
type Transport interface {
	Query(ctx context.Context, msg *DNSMessage, server string) QueryResult
	Name() string
}

// TransportOpts holds common transport configuration.
type TransportOpts struct {
	Timeout   time.Duration
	Insecure  bool   // skip TLS cert verification
	SNI       string // explicit TLS SNI
	DoHMethod string // "POST" or "GET" for DoH
	DoHURL    string // full URL template for DoH, e.g. "https://dns.google/dns-query"

	// RootCAs is the trust store used for every TLS connection. nil means "use the
	// Go default", which on a GOOS=linux binary running under Android finds nothing:
	// Android keeps its CAs in /system/etc/security/cacerts, not /etc/ssl/certs.
	RootCAs *x509.CertPool
	// TrustStoreEmpty records that no CA could be loaded at all, so a verification
	// failure below is ours and must not be reported as a network-level TLS failure.
	TrustStoreEmpty bool
}

// NewTransport creates a transport by name.
func NewTransport(name string, opts TransportOpts) (Transport, error) {
	switch strings.ToLower(name) {
	case "udp":
		return &UDPTransport{opts: opts}, nil
	case "tcp":
		return &TCPTransport{opts: opts}, nil
	case "dot":
		return &DoTTransport{opts: opts}, nil
	case "doh":
		return &DoHTransport{opts: opts}, nil
	case "doh-json":
		return &DoHJSONTransport{opts: opts}, nil
	default:
		return nil, fmt.Errorf("unknown transport: %s", name)
	}
}

// ---------- UDP ----------

type UDPTransport struct{ opts TransportOpts }

func (t *UDPTransport) Name() string { return "udp" }

func (t *UDPTransport) Query(ctx context.Context, msg *DNSMessage, server string) QueryResult {
	wire, err := msg.Encode()
	if err != nil {
		return QueryResult{Err: err, ErrStage: "encode"}
	}
	addr := net.JoinHostPort(server, "53")
	d := net.Dialer{Timeout: t.opts.Timeout}
	conn, err := d.DialContext(ctx, "udp", addr)
	if err != nil {
		return QueryResult{Err: err, ErrStage: "udp_connect"}
	}
	defer conn.Close()

	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(t.opts.Timeout)
	}
	conn.SetDeadline(deadline)

	start := time.Now()
	if _, err := conn.Write(wire); err != nil {
		return QueryResult{Err: err, ErrStage: "udp_write"}
	}
	buf := make([]byte, 4096)
	n, err := conn.Read(buf)
	rtt := time.Since(start)
	if err != nil {
		return QueryResult{Err: err, ErrStage: "udp_read", RTT: rtt}
	}

	resp, err := ParseMessage(buf[:n])
	if err != nil {
		return QueryResult{Err: err, ErrStage: "parse", RTT: rtt, RawResponse: buf[:n], RespSize: n}
	}
	return QueryResult{RawResponse: buf[:n], Msg: resp, RTT: rtt, RespSize: n}
}

// ---------- TCP ----------

type TCPTransport struct{ opts TransportOpts }

func (t *TCPTransport) Name() string { return "tcp" }

func (t *TCPTransport) Query(ctx context.Context, msg *DNSMessage, server string) QueryResult {
	wire, err := msg.Encode()
	if err != nil {
		return QueryResult{Err: err, ErrStage: "encode"}
	}
	addr := net.JoinHostPort(server, "53")
	d := net.Dialer{Timeout: t.opts.Timeout}
	conn, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return QueryResult{Err: err, ErrStage: "tcp_connect"}
	}
	defer conn.Close()

	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(t.opts.Timeout)
	}
	conn.SetDeadline(deadline)

	return tcpExchange(conn, wire)
}

func tcpExchange(conn net.Conn, wire []byte) QueryResult {
	// DNS over TCP: 2-byte length prefix.
	lenBuf := make([]byte, 2)
	binary.BigEndian.PutUint16(lenBuf, uint16(len(wire)))

	start := time.Now()
	if _, err := conn.Write(append(lenBuf, wire...)); err != nil {
		return QueryResult{Err: err, ErrStage: "tcp_write"}
	}

	if _, err := io.ReadFull(conn, lenBuf); err != nil {
		rtt := time.Since(start)
		return QueryResult{Err: err, ErrStage: "tcp_read_length", RTT: rtt}
	}
	respLen := binary.BigEndian.Uint16(lenBuf)
	if respLen == 0 || respLen > 65535 {
		return QueryResult{Err: fmt.Errorf("invalid TCP response length: %d", respLen), ErrStage: "tcp_read_length"}
	}
	respBuf := make([]byte, respLen)
	if _, err := io.ReadFull(conn, respBuf); err != nil {
		rtt := time.Since(start)
		return QueryResult{Err: err, ErrStage: "tcp_read_body", RTT: rtt}
	}
	rtt := time.Since(start)

	resp, err := ParseMessage(respBuf)
	if err != nil {
		return QueryResult{Err: err, ErrStage: "parse", RTT: rtt, RawResponse: respBuf, RespSize: int(respLen)}
	}
	return QueryResult{RawResponse: respBuf, Msg: resp, RTT: rtt, RespSize: int(respLen)}
}

// ---------- DoT (DNS over TLS) ----------

type DoTTransport struct{ opts TransportOpts }

func (t *DoTTransport) Name() string { return "dot" }

func (t *DoTTransport) Query(ctx context.Context, msg *DNSMessage, server string) QueryResult {
	wire, err := msg.Encode()
	if err != nil {
		return QueryResult{Err: err, ErrStage: "encode"}
	}
	addr := net.JoinHostPort(server, "853")
	d := net.Dialer{Timeout: t.opts.Timeout}
	tcpConn, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return QueryResult{Err: err, ErrStage: "tcp_connect"}
	}
	defer tcpConn.Close()

	sni := t.opts.SNI
	if sni == "" {
		sni = server
	}
	tlsConf := &tls.Config{
		ServerName:         sni,
		InsecureSkipVerify: t.opts.Insecure,
		RootCAs:            t.opts.RootCAs,
	}
	tlsConn := tls.Client(tcpConn, tlsConf)

	deadline, ok := ctx.Deadline()
	if !ok {
		deadline = time.Now().Add(t.opts.Timeout)
	}
	tlsConn.SetDeadline(deadline)

	if err := tlsConn.HandshakeContext(ctx); err != nil {
		return QueryResult{Err: err, ErrStage: "tls_handshake"}
	}
	defer tlsConn.Close()

	return tcpExchange(tlsConn, wire)
}

// ---------- DoH (DNS over HTTPS, RFC 8484 wire format) ----------

type DoHTransport struct{ opts TransportOpts }

func (t *DoHTransport) Name() string { return "doh" }

func (t *DoHTransport) Query(ctx context.Context, msg *DNSMessage, server string) QueryResult {
	wire, err := msg.Encode()
	if err != nil {
		return QueryResult{Err: err, ErrStage: "encode"}
	}

	dohURL := t.opts.DoHURL
	if dohURL == "" {
		dohURL = fmt.Sprintf("https://%s/dns-query", server)
	}

	client := &http.Client{
		Timeout: t.opts.Timeout,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{
				InsecureSkipVerify: t.opts.Insecure,
				ServerName:         t.opts.SNI,
				RootCAs:            t.opts.RootCAs,
			},
		},
	}

	var req *http.Request
	method := strings.ToUpper(t.opts.DoHMethod)
	if method == "" {
		method = "POST"
	}

	start := time.Now()
	if method == "GET" {
		encoded := base64URLEncode(wire)
		u := dohURL + "?dns=" + encoded
		req, err = http.NewRequestWithContext(ctx, "GET", u, nil)
	} else {
		req, err = http.NewRequestWithContext(ctx, "POST", dohURL, strings.NewReader(string(wire)))
		if err == nil {
			req.Header.Set("Content-Type", "application/dns-message")
		}
	}
	if err != nil {
		return QueryResult{Err: err, ErrStage: "http_request_build"}
	}
	req.Header.Set("Accept", "application/dns-message")

	resp, err := client.Do(req)
	if err != nil {
		rtt := time.Since(start)
		stage := classifyHTTPError(err)
		return QueryResult{Err: err, ErrStage: stage, RTT: rtt}
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	rtt := time.Since(start)
	if err != nil {
		return QueryResult{Err: err, ErrStage: "http_read_body", RTT: rtt}
	}
	if resp.StatusCode != 200 {
		return QueryResult{
			Err:      fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(body)),
			ErrStage: "http_status",
			RTT:      rtt,
		}
	}

	parsed, err := ParseMessage(body)
	if err != nil {
		return QueryResult{Err: err, ErrStage: "parse", RTT: rtt, RawResponse: body, RespSize: len(body)}
	}
	return QueryResult{RawResponse: body, Msg: parsed, RTT: rtt, RespSize: len(body)}
}

// base64URLEncode encodes data as base64url without padding (RFC 4648 §5).
func base64URLEncode(data []byte) string {
	const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
	var sb strings.Builder
	sb.Grow((len(data)*4 + 2) / 3)

	var buffer uint32
	var bitsLeft int

	for _, b := range data {
		buffer = (buffer << 8) | uint32(b)
		bitsLeft += 8
		for bitsLeft >= 6 {
			bitsLeft -= 6
			idx := (buffer >> uint(bitsLeft)) & 0x3F
			sb.WriteByte(alphabet[idx])
		}
	}
	if bitsLeft > 0 {
		idx := (buffer << uint(6-bitsLeft)) & 0x3F
		sb.WriteByte(alphabet[idx])
	}
	return sb.String()
}

// ---------- DoH-JSON (Google/Cloudflare JSON API) ----------

type DoHJSONTransport struct{ opts TransportOpts }

func (t *DoHJSONTransport) Name() string { return "doh-json" }

// dohJSONResponse represents the Google/Cloudflare JSON DNS API response.
type dohJSONResponse struct {
	Status   int  `json:"Status"`
	TC       bool `json:"TC"`
	RD       bool `json:"RD"`
	RA       bool `json:"RA"`
	AD       bool `json:"AD"`
	CD       bool `json:"CD"`
	Question []struct {
		Name string `json:"name"`
		Type int    `json:"type"`
	} `json:"Question"`
	Answer []struct {
		Name string `json:"name"`
		Type int    `json:"type"`
		TTL  int    `json:"TTL"`
		Data string `json:"data"`
	} `json:"Answer"`
	Authority []struct {
		Name string `json:"name"`
		Type int    `json:"type"`
		TTL  int    `json:"TTL"`
		Data string `json:"data"`
	} `json:"Authority"`
}

func (t *DoHJSONTransport) Query(ctx context.Context, msg *DNSMessage, server string) QueryResult {
	if len(msg.Questions) == 0 {
		return QueryResult{Err: errors.New("no question in message"), ErrStage: "encode"}
	}
	q := msg.Questions[0]

	dohURL := t.opts.DoHURL
	if dohURL == "" {
		dohURL = fmt.Sprintf("https://%s/resolve", server)
	}

	u, err := url.Parse(dohURL)
	if err != nil {
		return QueryResult{Err: err, ErrStage: "url_parse"}
	}
	params := u.Query()
	params.Set("name", q.Name)
	params.Set("type", typeName(q.Type))
	u.RawQuery = params.Encode()

	client := &http.Client{
		Timeout: t.opts.Timeout,
		Transport: &http.Transport{
			TLSClientConfig: &tls.Config{
				InsecureSkipVerify: t.opts.Insecure,
				ServerName:         t.opts.SNI,
				RootCAs:            t.opts.RootCAs,
			},
		},
	}

	req, err := http.NewRequestWithContext(ctx, "GET", u.String(), nil)
	if err != nil {
		return QueryResult{Err: err, ErrStage: "http_request_build"}
	}
	req.Header.Set("Accept", "application/dns-json")

	start := time.Now()
	resp, err := client.Do(req)
	if err != nil {
		rtt := time.Since(start)
		stage := classifyHTTPError(err)
		return QueryResult{Err: err, ErrStage: stage, RTT: rtt}
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	rtt := time.Since(start)
	if err != nil {
		return QueryResult{Err: err, ErrStage: "http_read_body", RTT: rtt}
	}
	if resp.StatusCode != 200 {
		return QueryResult{
			Err:      fmt.Errorf("HTTP %d: %s", resp.StatusCode, string(body)),
			ErrStage: "http_status",
			RTT:      rtt,
		}
	}

	var jr dohJSONResponse
	if err := json.Unmarshal(body, &jr); err != nil {
		return QueryResult{Err: fmt.Errorf("json parse: %w", err), ErrStage: "json_parse", RTT: rtt}
	}

	// Synthesize a DNSMessage from the JSON response.
	dnsMsg := &DNSMessage{
		Header: DNSHeader{
			ID:    msg.Header.ID,
			Flags: FlagQR | FlagRD,
		},
	}
	if jr.RA {
		dnsMsg.Header.Flags |= FlagRA
	}
	if jr.TC {
		dnsMsg.Header.Flags |= FlagTC
	}
	dnsMsg.Header.Flags |= uint16(jr.Status) & 0x000F

	for _, jq := range jr.Question {
		dnsMsg.Questions = append(dnsMsg.Questions, DNSQuestion{
			Name:  jq.Name,
			Type:  uint16(jq.Type),
			Class: ClassIN,
		})
	}
	dnsMsg.Header.QDCount = uint16(len(dnsMsg.Questions))

	for _, ja := range jr.Answer {
		rr := DNSRR{
			Name:  ja.Name,
			Type:  uint16(ja.Type),
			Class: ClassIN,
			TTL:   uint32(ja.TTL),
		}
		switch rr.Type {
		case TypeA:
			rr.AAddr = ja.Data
		case TypeAAAA:
			rr.AAAAAddr = ja.Data
		case TypeTXT:
			rr.TXTData = []string{strings.Trim(ja.Data, "\"")}
		case TypeCNAME:
			rr.CName = ja.Data
		}
		dnsMsg.Answers = append(dnsMsg.Answers, rr)
	}
	dnsMsg.Header.ANCount = uint16(len(dnsMsg.Answers))

	return QueryResult{Msg: dnsMsg, RTT: rtt, RespSize: len(body)}
}

// classifyHTTPError attempts to classify an HTTP error into a specific stage.
func classifyHTTPError(err error) string {
	if err == nil {
		return ""
	}
	s := err.Error()
	if strings.Contains(s, "connection refused") {
		return "tcp_connect_refused"
	}
	if strings.Contains(s, "i/o timeout") || strings.Contains(s, "deadline exceeded") {
		if strings.Contains(s, "tls") || strings.Contains(s, "TLS") {
			return "tls_timeout"
		}
		return "tcp_connect_timeout"
	}
	if strings.Contains(s, "tls") || strings.Contains(s, "TLS") || strings.Contains(s, "certificate") || strings.Contains(s, "x509") {
		if strings.Contains(s, "reset") {
			return "tls_handshake_reset"
		}
		return "tls_handshake"
	}
	if strings.Contains(s, "reset") {
		return "tcp_reset"
	}
	return "http_error"
}

// DoHReachTransport performs a single DoH/DoT connection attempt and reports precise stage.
type DoHReachTransport struct {
	Timeout         time.Duration
	Insecure        bool
	Resolver        *net.Resolver // bootstrap resolver for hostname lookups; nil = system default
	RootCAs         *x509.CertPool
	TrustStoreEmpty bool
}

// DoHReachResult has detailed connection stage info.
type DoHReachResult struct {
	URL          string  `json:"url"`
	Protocol     string  `json:"protocol"` // "doh" or "dot"
	ResolvedAddr string  `json:"resolved_addr,omitempty"`
	Success      bool    `json:"success"`
	FailStage    string  `json:"fail_stage,omitempty"`
	FailReason   string  `json:"fail_reason,omitempty"`
	CertSubject  string  `json:"cert_subject,omitempty"`
	HTTPStatus   int     `json:"http_status,omitempty"`
	RTTMS        float64 `json:"rtt_ms"`
}

// resolveHost resolves a hostname to an IP address using the bootstrap resolver.
// If the host is already an IP, it is returned as-is.
func (t *DoHReachTransport) resolveHost(ctx context.Context, host string) (ip string, err error) {
	// If it's already an IP, skip resolution.
	if net.ParseIP(host) != nil {
		return host, nil
	}
	resolver := t.Resolver
	if resolver == nil {
		resolver = net.DefaultResolver
	}
	addrs, err := resolver.LookupHost(ctx, host)
	if err != nil {
		return "", err
	}
	if len(addrs) == 0 {
		return "", fmt.Errorf("no addresses for %s", host)
	}
	return addrs[0], nil
}

func (t *DoHReachTransport) ProbeDoH(ctx context.Context, dohURL string) DoHReachResult {
	result := DoHReachResult{URL: dohURL, Protocol: "doh"}

	u, err := url.Parse(dohURL)
	if err != nil {
		result.FailStage = "url_parse"
		result.FailReason = err.Error()
		return result
	}

	host := u.Hostname()
	port := u.Port()
	if port == "" {
		port = "443"
	}

	// Resolve the hostname via bootstrap resolver.
	resolvedIP, err := t.resolveHost(ctx, host)
	if err != nil {
		result.FailStage = "bootstrap_dns_failed"
		result.FailReason = err.Error()
		return result
	}
	result.ResolvedAddr = resolvedIP

	start := time.Now()

	// Stage 1: TCP connect — dial the resolved IP, not the hostname.
	d := net.Dialer{Timeout: t.Timeout}
	tcpConn, err := d.DialContext(ctx, "tcp", net.JoinHostPort(resolvedIP, port))
	if err != nil {
		result.RTTMS = float64(time.Since(start).Microseconds()) / 1000.0
		if strings.Contains(err.Error(), "refused") {
			result.FailStage = "tcp_connect_refused"
		} else if strings.Contains(err.Error(), "timeout") || strings.Contains(err.Error(), "deadline") {
			result.FailStage = "tcp_connect_timeout"
		} else {
			result.FailStage = "tcp_connect"
		}
		result.FailReason = err.Error()
		return result
	}
	defer tcpConn.Close()

	// Stage 2: TLS handshake — SNI must be the original hostname for cert verification.
	tlsConf := &tls.Config{
		ServerName:         host,
		InsecureSkipVerify: t.Insecure,
		RootCAs:            t.RootCAs,
	}
	tlsConn := tls.Client(tcpConn, tlsConf)
	tlsConn.SetDeadline(time.Now().Add(t.Timeout))

	if err := tlsConn.HandshakeContext(ctx); err != nil {
		result.RTTMS = float64(time.Since(start).Microseconds()) / 1000.0
		if strings.Contains(err.Error(), "reset") {
			result.FailStage = "tls_handshake_reset"
		} else if strings.Contains(err.Error(), "timeout") || strings.Contains(err.Error(), "deadline") {
			result.FailStage = "tls_timeout"
		} else if isCertVerifyError(err) {
			// A certificate that will not verify is only a network finding when we
			// had something to verify it against. With an empty trust store every
			// reachable endpoint fails this way, which reads exactly like blocking.
			if t.TrustStoreEmpty {
				result.FailStage = "tls_no_trust_store"
			} else {
				result.FailStage = "tls_cert_invalid"
			}
		} else {
			result.FailStage = "tls_handshake"
		}
		result.FailReason = err.Error()
		return result
	}
	defer tlsConn.Close()

	// Record cert subject.
	state := tlsConn.ConnectionState()
	if len(state.PeerCertificates) > 0 {
		result.CertSubject = state.PeerCertificates[0].Subject.String()
	}

	// Stage 3: HTTP request.
	// Build a minimal DNS query for health check.
	q := NewQuery("example.com", TypeA)
	q.AddEDNS(4096)
	wire, err := q.Encode()
	if err != nil {
		result.FailStage = "encode"
		result.FailReason = err.Error()
		return result
	}

	httpClient := &http.Client{
		Timeout: t.Timeout,
		Transport: &http.Transport{
			DialTLSContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
				return tlsConn, nil
			},
		},
	}
	req, err := http.NewRequestWithContext(ctx, "POST", dohURL, strings.NewReader(string(wire)))
	if err != nil {
		result.FailStage = "http_request_build"
		result.FailReason = err.Error()
		return result
	}
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")

	resp, err := httpClient.Do(req)
	result.RTTMS = float64(time.Since(start).Microseconds()) / 1000.0
	if err != nil {
		result.FailStage = "http_error"
		result.FailReason = err.Error()
		return result
	}
	defer resp.Body.Close()

	result.HTTPStatus = resp.StatusCode
	if resp.StatusCode != 200 {
		result.FailStage = "http_status"
		result.FailReason = fmt.Sprintf("HTTP %d", resp.StatusCode)
		return result
	}

	result.Success = true
	return result
}

func (t *DoHReachTransport) ProbeDoT(ctx context.Context, host, port string) DoHReachResult {
	result := DoHReachResult{URL: host, Protocol: "dot"}

	// Resolve the hostname via bootstrap resolver.
	resolvedIP, err := t.resolveHost(ctx, host)
	if err != nil {
		result.FailStage = "bootstrap_dns_failed"
		result.FailReason = err.Error()
		return result
	}
	result.ResolvedAddr = resolvedIP

	dialAddr := net.JoinHostPort(resolvedIP, port)

	start := time.Now()

	// Stage 1: TCP connect.
	d := net.Dialer{Timeout: t.Timeout}
	tcpConn, err := d.DialContext(ctx, "tcp", dialAddr)
	if err != nil {
		result.RTTMS = float64(time.Since(start).Microseconds()) / 1000.0
		if strings.Contains(err.Error(), "refused") {
			result.FailStage = "tcp_connect_refused"
		} else if strings.Contains(err.Error(), "timeout") || strings.Contains(err.Error(), "deadline") {
			result.FailStage = "tcp_connect_timeout"
		} else {
			result.FailStage = "tcp_connect"
		}
		result.FailReason = err.Error()
		return result
	}
	defer tcpConn.Close()

	// Stage 2: TLS handshake — SNI must be the original hostname for cert verification.
	tlsConf := &tls.Config{
		ServerName:         host,
		InsecureSkipVerify: t.Insecure,
		RootCAs:            t.RootCAs,
	}
	tlsConn := tls.Client(tcpConn, tlsConf)
	tlsConn.SetDeadline(time.Now().Add(t.Timeout))

	if err := tlsConn.HandshakeContext(ctx); err != nil {
		result.RTTMS = float64(time.Since(start).Microseconds()) / 1000.0
		if strings.Contains(err.Error(), "reset") {
			result.FailStage = "tls_handshake_reset"
		} else if strings.Contains(err.Error(), "timeout") || strings.Contains(err.Error(), "deadline") {
			result.FailStage = "tls_timeout"
		} else if isCertVerifyError(err) {
			// A certificate that will not verify is only a network finding when we
			// had something to verify it against. With an empty trust store every
			// reachable endpoint fails this way, which reads exactly like blocking.
			if t.TrustStoreEmpty {
				result.FailStage = "tls_no_trust_store"
			} else {
				result.FailStage = "tls_cert_invalid"
			}
		} else {
			result.FailStage = "tls_handshake"
		}
		result.FailReason = err.Error()
		return result
	}
	defer tlsConn.Close()

	state := tlsConn.ConnectionState()
	if len(state.PeerCertificates) > 0 {
		result.CertSubject = state.PeerCertificates[0].Subject.String()
	}

	// Stage 3: DNS query over the TLS connection.
	q := NewQuery("example.com", TypeA)
	q.AddEDNS(4096)
	wire, err := q.Encode()
	if err != nil {
		result.FailStage = "encode"
		result.FailReason = err.Error()
		return result
	}

	lenBuf := make([]byte, 2)
	binary.BigEndian.PutUint16(lenBuf, uint16(len(wire)))
	if _, err := tlsConn.Write(append(lenBuf, wire...)); err != nil {
		result.RTTMS = float64(time.Since(start).Microseconds()) / 1000.0
		result.FailStage = "dot_write"
		result.FailReason = err.Error()
		return result
	}

	if _, err := io.ReadFull(tlsConn, lenBuf); err != nil {
		result.RTTMS = float64(time.Since(start).Microseconds()) / 1000.0
		result.FailStage = "dot_read"
		result.FailReason = err.Error()
		return result
	}
	respLen := binary.BigEndian.Uint16(lenBuf)
	respBuf := make([]byte, respLen)
	if _, err := io.ReadFull(tlsConn, respBuf); err != nil {
		result.RTTMS = float64(time.Since(start).Microseconds()) / 1000.0
		result.FailStage = "dot_read"
		result.FailReason = err.Error()
		return result
	}
	result.RTTMS = float64(time.Since(start).Microseconds()) / 1000.0
	result.Success = true
	return result
}

// isCertVerifyError reports whether err is a certificate verification failure
// rather than a transport-level handshake failure.
func isCertVerifyError(err error) bool {
	var ua x509.UnknownAuthorityError
	var ci x509.CertificateInvalidError
	var hn x509.HostnameError
	if errors.As(err, &ua) || errors.As(err, &ci) || errors.As(err, &hn) {
		return true
	}
	var ve *tls.CertificateVerificationError
	return errors.As(err, &ve)
}

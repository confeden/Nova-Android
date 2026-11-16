package main

import (
	"bufio"
	"encoding/base64"
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"net/http"
	"time"

	"golang.org/x/net/http2"
	"golang.org/x/net/http2/hpack"
)

type diagOpts struct {
	sni      string // ServerName в ClientHello ("" = cf.dns-ai.ru)
	host     string // :authority / Host ("" = cf.dns-ai.ru)
	tls12    bool   // только TLS 1.2
	appSplit bool   // каждый байт прикладных данных отдельной записью TCP
	wdelay   time.Duration
	ech      []byte
	http1    bool
	coalesce bool // HEADERS+DATA одной записью
	getReq   bool // запрос GET ?dns= вместо POST
}

type splitConn struct {
	net.Conn
	on bool
}

func (c splitConn) Write(b []byte) (int, error) {
	if !c.on {
		return c.Conn.Write(b)
	}
	for i := range b {
		if _, err := c.Conn.Write(b[i : i+1]); err != nil {
			return i, err
		}
		time.Sleep(2 * time.Millisecond)
	}
	return len(b), nil
}

// diag — по ступеням: рукопожатие, первый кадр от сервера, SETTINGS клиента -> ACK, запрос -> ответ.
// Ступень, на которой оборвалось, и есть место, где путь режется.
func diag(ip string, port int, o diagOpts, budget time.Duration) result {
	start := time.Now()
	sni, host := o.sni, o.host
	if sni == "" {
		sni = sniInner
	}
	if host == "" {
		host = sniInner
	}
	ctx, cancel := context.WithTimeout(context.Background(), budget)
	defer cancel()
	raw, err := (&net.Dialer{}).DialContext(ctx, "tcp", net.JoinHostPort(ip, fmt.Sprint(port)))
	if err != nil {
		return result{err: "s0 tcp:" + classify(err)}
	}
	defer raw.Close()
	cfg := tlsConfig(sni, o.ech, !o.http1)
	if o.http1 {
		cfg.NextProtos = []string{"http/1.1"}
	}
	if o.tls12 {
		cfg.MaxVersion = tls.VersionTLS12
	}
	if sni != sniInner {
		cfg.InsecureSkipVerify = true
	}
	tc := tls.Client(raw, cfg)
	_ = raw.SetDeadline(time.Now().Add(budget))
	if err := tc.HandshakeContext(ctx); err != nil {
		return result{err: "s1 tls:" + classify(err)}
	}
	if o.wdelay > 0 {
		time.Sleep(o.wdelay)
	}
	out := splitConn{Conn: tc, on: o.appSplit}
	if o.http1 {
		body := dnsQuery()
		req := fmt.Sprintf("POST /dns-query HTTP/1.1\r\nHost: %s\r\nContent-Type: application/dns-message\r\nAccept: application/dns-message\r\nContent-Length: %d\r\nConnection: close\r\n\r\n", host, len(body))
		if _, err := out.Write(append([]byte(req), body...)); err != nil {
			return result{err: "s3 write:" + classify(err)}
		}
		resp, err := http.ReadResponse(bufio.NewReader(tc), nil)
		if err != nil {
			return result{err: "s3 no-response:" + classify(err)}
		}
		defer resp.Body.Close()
		rb, _ := io.ReadAll(io.LimitReader(resp.Body, 65535))
		if resp.StatusCode != 200 {
			return result{err: fmt.Sprintf("answered %d", resp.StatusCode)}
		}
		if err := checkAnswer(rb); err != nil {
			return result{err: err.Error()}
		}
		return result{ok: true, ms: time.Since(start).Milliseconds()}
	}
	fr := http2.NewFramer(out, tc)
	// ступень 1: сервер говорит первым (SETTINGS)
	_ = raw.SetReadDeadline(time.Now().Add(budget))
	if _, err := fr.ReadFrame(); err != nil {
		return result{err: "s1 no-server-frame:" + classify(err)}
	}
	// ступень 2: наш префикс и SETTINGS, ждём ACK
	if _, err := out.Write([]byte(http2.ClientPreface)); err != nil {
		return result{err: "s2 write:" + classify(err)}
	}
	if err := fr.WriteSettings(); err != nil {
		return result{err: "s2 write:" + classify(err)}
	}
	acked := false
	for i := 0; i < 6 && !acked; i++ {
		_ = raw.SetReadDeadline(time.Now().Add(budget))
		f, err := fr.ReadFrame()
		if err != nil {
			return result{err: "s2 no-settings-ack:" + classify(err)}
		}
		if sf, ok := f.(*http2.SettingsFrame); ok && sf.IsAck() {
			acked = true
		}
	}
	if !acked {
		return result{err: "s2 no-ack-in-6-frames"}
	}
	// ступень 3: запрос
	var hb bytes.Buffer
	enc := hpack.NewEncoder(&hb)
	q := dnsQuery()
	hdrs := [][2]string{{":method", "POST"}, {":scheme", "https"}, {":authority", host}, {":path", "/dns-query"},
		{"content-type", "application/dns-message"}, {"accept", "application/dns-message"}, {"content-length", fmt.Sprint(len(q))}}
	if o.getReq {
		qq := append([]byte(nil), q...)
		qq[0], qq[1] = 0, 0
		hdrs = [][2]string{{":method", "GET"}, {":scheme", "https"}, {":authority", host},
			{":path", "/dns-query?dns=" + base64.RawURLEncoding.EncodeToString(qq)}, {"accept", "application/dns-message"}}
	}
	for _, kv := range hdrs {
		_ = enc.WriteField(hpack.HeaderField{Name: kv[0], Value: kv[1]})
	}
	wfr := fr
	var wb bytes.Buffer
	if o.coalesce {
		wfr = http2.NewFramer(&wb, tc)
	}
	if err := wfr.WriteHeaders(http2.HeadersFrameParam{StreamID: 1, BlockFragment: hb.Bytes(), EndHeaders: !false, EndStream: o.getReq}); err != nil {
		return result{err: "s3 write:" + classify(err)}
	}
	if !o.getReq {
		if err := wfr.WriteData(1, true, q); err != nil {
			return result{err: "s3 write:" + classify(err)}
		}
	}
	if o.coalesce {
		if _, err := out.Write(wb.Bytes()); err != nil {
			return result{err: "s3 write:" + classify(err)}
		}
	}
	var status string
	var body []byte
	dec := hpack.NewDecoder(4096, func(hf hpack.HeaderField) {
		if hf.Name == ":status" {
			status = hf.Value
		}
	})
	for i := 0; i < 12; i++ {
		_ = raw.SetReadDeadline(time.Now().Add(budget))
		f, err := fr.ReadFrame()
		if err != nil {
			return result{err: "s3 no-response:" + classify(err)}
		}
		switch v := f.(type) {
		case *http2.HeadersFrame:
			_, _ = dec.Write(v.HeaderBlockFragment())
			if v.StreamEnded() {
				return result{err: "answered " + status}
			}
		case *http2.DataFrame:
			body = append(body, v.Data()...)
			if v.StreamEnded() {
				if status != "200" {
					return result{err: "answered " + status}
				}
				if err := checkAnswer(body); err != nil {
					return result{err: err.Error()}
				}
				return result{ok: true, ms: time.Since(start).Milliseconds()}
			}
		case *http2.GoAwayFrame:
			return result{err: "goaway"}
		}
	}
	return result{err: "s3 no-end"}
}

// rewritePublicName меняет public_name в ECHConfigList (один конфиг), пересчитывая длины.
func rewritePublicName(list []byte, name string) []byte {
	if len(list) < 6 {
		return list
	}
	body := list[2:]
	ver := body[:2]
	contents := body[4:]
	// contents: config_id(1) kem(2) pk_len(2) pk | suites_len(2) suites | max_name(1) | name_len(1) name | ext_len(2) ext
	p := 1 + 2
	pkLen := int(binary.BigEndian.Uint16(contents[p:]))
	p += 2 + pkLen
	sLen := int(binary.BigEndian.Uint16(contents[p:]))
	p += 2 + sLen
	p++ // max name length
	oldLen := int(contents[p])
	head := append([]byte(nil), contents[:p]...)
	tail := contents[p+1+oldLen:]
	nc := append(head, byte(len(name)))
	nc = append(nc, name...)
	nc = append(nc, tail...)
	out := append([]byte(nil), ver...)
	out = append(out, byte(len(nc)>>8), byte(len(nc)))
	out = append(out, nc...)
	res := []byte{byte(len(out) >> 8), byte(len(out))}
	return append(res, out...)
}

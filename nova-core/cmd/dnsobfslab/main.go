// dnsobfslab — лабораторный прогон стратегий обфускации прямого DoH к DNS-AI с телефона.
//
// Не часть приложения. Собирается статически под linux/arm64 и запускается через adb shell:
//
//	GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -o dnsobfslab ./cmd/dnsobfslab
//	adb push dnsobfslab /data/local/tmp/ && adb shell /data/local/tmp/dnsobfslab -host cf -n 20 -grid s1
//
// Каждая попытка — новое TCP-соединение, полное рукопожатие TLS (ALPN h2, как в ядре) и один реальный
// DoH-запрос; успех = ответ с тем же id, QR=1, RCODE=0. Стратегии, требующие root (подделка пакетов,
// TTL, сырые сокеты), здесь не реализованы и не измеряются.
//
// Строка стратегии: токены через `;`:
//
//	tcp=<смещения>   разбиение потока на TCP-записи по смещениям, список через `/`
//	rec=<смещения>   разбиение ClientHello на несколько TLS-записей (смещения в потоке исходной записи)
//	d=<мс>           пауза между TCP-записями
//	mss=<байт>       TCP_MAXSEG до connect
//	hello=x25519     только X25519 (без постквантового ключа, ClientHello ~250 байт вместо ~1,4 КБ)
//	case=1           SNI с чередующимся регистром (сертификат Go сверяет без учёта регистра)
//
// Смещение: число, `sni` (начало имени), `mid` (середина имени), `end` (конец имени), с поправкой `sni-1`, `end+2`.
package main

import (
	"bytes"
	"context"
	"crypto/tls"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	utls "github.com/refraction-networking/utls"
	"golang.org/x/net/http2"
)

type hostSpec struct {
	name  string
	sni   string
	path  string
	addrs []string
	// reach — для личной ссылки с выдуманным токеном: 404 от CDN считается «дошли».
	reach bool
}

var hosts = map[string]hostSpec{
	"cf": {name: "cf", sni: "cf.dns-ai.ru", path: "/dns-query",
		addrs: []string{"104.21.82.125", "172.67.157.232", "2606:4700:3030::ac43:9de8", "2606:4700:3035::6815:527d"}},
	"dns": {name: "dns", sni: "dns.dns-ai.ru", path: "/dns-query",
		addrs: []string{"194.87.196.13", "45.151.102.219", "2a00:b700:5::3:62", "2a12:5940:d4ef::219"}},
	"ru": {name: "ru", sni: "ru.dns-ai.ru", path: "/labprobe0000000000000000", reach: true,
		addrs: []string{"188.72.112.1"}},
}

type offset struct {
	kind string // abs, sni, mid, end
	n    int
}

type strategy struct {
	spec  string
	tcp   []offset
	rec   []offset
	delay time.Duration
	mss   int
	x25   bool
	kase  bool
	utls  string
	oob   []offset
	oobB  byte
}

func parseOffsets(s string) ([]offset, error) {
	var out []offset
	for _, item := range strings.Split(s, "/") {
		item = strings.TrimSpace(item)
		if item == "" {
			continue
		}
		o := offset{kind: "abs"}
		for _, k := range []string{"sni", "mid", "end"} {
			if strings.HasPrefix(item, k) {
				o.kind = k
				item = strings.TrimPrefix(item, k)
				break
			}
		}
		if item != "" {
			v, err := strconv.Atoi(item)
			if err != nil {
				return nil, fmt.Errorf("offset %q: %w", item, err)
			}
			o.n = v
		}
		out = append(out, o)
	}
	return out, nil
}

func parseStrategy(spec string) (strategy, error) {
	st := strategy{spec: spec}
	if spec == "none" || spec == "" {
		st.spec = "none"
		return st, nil
	}
	for _, tok := range strings.Split(spec, ";") {
		key, val, _ := strings.Cut(strings.TrimSpace(tok), "=")
		var err error
		switch key {
		case "tcp":
			st.tcp, err = parseOffsets(val)
		case "rec":
			st.rec, err = parseOffsets(val)
		case "d":
			var ms float64
			ms, err = strconv.ParseFloat(val, 64)
			st.delay = time.Duration(ms * float64(time.Millisecond))
		case "mss":
			st.mss, err = strconv.Atoi(val)
		case "hello":
			st.x25 = val == "x25519"
			switch val {
			case "chrome", "firefox", "okhttp", "ios", "safari":
				st.utls = val
			}
		case "case":
			st.kase = val == "1"
		case "oob":
			st.oob, err = parseOffsets(val)
			st.oobB = 'a'
			st.tcp = append(st.tcp, st.oob...)
		case "none", "":
		default:
			err = fmt.Errorf("unknown token %q", key)
		}
		if err != nil {
			return st, err
		}
	}
	return st, nil
}

// sniRange возвращает начало и конец имени SNI в записи ClientHello p (с 5-байтным заголовком записи).
func sniRange(p []byte) (int, int, bool) {
	if len(p) < 48 || p[0] != 0x16 || p[5] != 0x01 {
		return 0, 0, false
	}
	pos := 5 + 4 + 2 + 32
	if pos >= len(p) {
		return 0, 0, false
	}
	pos += 1 + int(p[pos])
	if pos+2 > len(p) {
		return 0, 0, false
	}
	pos += 2 + int(binary.BigEndian.Uint16(p[pos:]))
	if pos >= len(p) {
		return 0, 0, false
	}
	pos += 1 + int(p[pos])
	if pos+2 > len(p) {
		return 0, 0, false
	}
	extEnd := pos + 2 + int(binary.BigEndian.Uint16(p[pos:]))
	pos += 2
	for pos+4 <= extEnd && pos+4 <= len(p) {
		typ := binary.BigEndian.Uint16(p[pos:])
		l := int(binary.BigEndian.Uint16(p[pos+2:]))
		if typ == 0 && pos+9 <= len(p) {
			nameLen := int(binary.BigEndian.Uint16(p[pos+7:]))
			start := pos + 9
			if start+nameLen <= len(p) {
				return start, start + nameLen, true
			}
		}
		pos += 4 + l
	}
	return 0, 0, false
}

func resolve(o offset, p []byte) int {
	s, e, ok := sniRange(p)
	if !ok && o.kind != "abs" {
		return -1
	}
	switch o.kind {
	case "sni":
		return s + o.n
	case "mid":
		return (s+e)/2 + o.n
	case "end":
		return e + o.n
	}
	return o.n
}

func resolveAll(offs []offset, p []byte) []int {
	var out []int
	for _, o := range offs {
		v := resolve(o, p)
		if v > 0 && v < len(p) {
			out = append(out, v)
		}
	}
	sort.Ints(out)
	// убрать повторы
	uniq := out[:0]
	for i, v := range out {
		if i == 0 || v != out[i-1] {
			uniq = append(uniq, v)
		}
	}
	return uniq
}

// buildSegments превращает исходную запись ClientHello в список TCP-записей по стратегии.
func (st strategy) buildSegments(p []byte) [][]byte {
	wire := p
	recSplits := resolveAll(st.rec, p)
	if len(recSplits) > 0 && p[0] == 0x16 && len(p) > 5 {
		body := p[5:]
		var out bytes.Buffer
		prev := 0
		cuts := append([]int(nil), recSplits...)
		cuts = append(cuts, len(p))
		for _, c := range cuts {
			bo := c - 5
			if bo <= prev {
				continue
			}
			part := body[prev:bo]
			out.Write([]byte{0x16, p[1], p[2], byte(len(part) >> 8), byte(len(part))})
			out.Write(part)
			prev = bo
		}
		wire = out.Bytes()
	}
	// смещения TCP считаются по исходной записи, переносим в новый поток
	var cuts []int
	for _, v := range resolveAll(st.tcp, p) {
		shift := 0
		for _, r := range recSplits {
			if r < v {
				shift += 5
			}
		}
		cuts = append(cuts, v+shift)
	}
	var segs [][]byte
	prev := 0
	for _, c := range cuts {
		if c <= prev || c >= len(wire) {
			continue
		}
		segs = append(segs, wire[prev:c])
		prev = c
	}
	segs = append(segs, wire[prev:])
	return segs
}

type stratConn struct {
	net.Conn
	st   strategy
	done bool
}

func (c *stratConn) Write(p []byte) (int, error) {
	if c.done || len(p) < 6 || p[0] != 0x16 {
		return c.Conn.Write(p)
	}
	c.done = true
	if tcp, ok := c.Conn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}
	segs := c.st.buildSegments(p)
	for i, seg := range segs {
		if _, err := c.Conn.Write(seg); err != nil {
			return 0, err
		}
		if i < len(segs)-1 && c.st.delay > 0 {
			time.Sleep(c.st.delay)
		}
	}
	return len(p), nil
}

func utlsID(name string) utls.ClientHelloID {
	switch name {
	case "firefox":
		return utls.HelloFirefox_Auto
	case "okhttp":
		return utls.HelloAndroid_11_OkHttp
	case "ios":
		return utls.HelloIOS_Auto
	case "safari":
		return utls.HelloSafari_Auto
	}
	return utls.HelloChrome_Auto
}

type attemptInfo struct {
	mu          sync.Mutex
	connectedAt time.Time
}

type attemptKey struct{}

func altCase(s string) string {
	b := []byte(s)
	for i := range b {
		if i%2 == 1 && b[i] >= 'a' && b[i] <= 'z' {
			b[i] -= 32
		}
	}
	return string(b)
}

func buildQuery(name string) []byte {
	q := []byte{0, 0, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0}
	for _, label := range strings.Split(name, ".") {
		q = append(q, byte(len(label)))
		q = append(q, label...)
	}
	q = append(q, 0, 0, 1, 0, 1)
	return q
}

var names = []string{"example.com", "wikipedia.org", "ya.ru", "yandex.ru", "mail.ru", "github.com", "cloudflare.com", "debian.org"}

type result struct {
	ok  bool
	dur time.Duration
	err string
}

func classify(err error, connected bool) string {
	msg := err.Error()
	kind := "other"
	switch {
	case strings.Contains(msg, "deadline exceeded"), strings.Contains(msg, "i/o timeout"), strings.Contains(msg, "timeout"):
		kind = "timeout"
	case strings.Contains(msg, "reset"):
		kind = "reset"
	case strings.Contains(msg, "EOF"):
		kind = "eof"
	case strings.Contains(msg, "refused"):
		kind = "refused"
	case strings.Contains(msg, "unreachable"):
		kind = "unreach"
	case strings.Contains(msg, "certificate"):
		kind = "cert"
	case strings.Contains(msg, "status"):
		kind = msg[strings.LastIndex(msg, "status"):]
	}
	if connected {
		return "tls/http:" + kind
	}
	return "dial:" + kind
}

func attemptDoT(h hostSpec, addr string, st strategy, timeout time.Duration, idx int) result {
	sni := h.sni
	if st.kase {
		sni = altCase(sni)
	}
	start := time.Now()
	dialer := &net.Dialer{Timeout: timeout, Control: func(_, _ string, rc syscall.RawConn) error {
		if st.mss <= 0 {
			return nil
		}
		var serr error
		if err := rc.Control(func(fd uintptr) { serr = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_TCP, syscall.TCP_MAXSEG, st.mss) }); err != nil {
			return err
		}
		return serr
	}}
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	raw, err := dialer.DialContext(ctx, "tcp", net.JoinHostPort(addr, "853"))
	if err != nil {
		return result{err: classify(err, false), dur: time.Since(start)}
	}
	defer raw.Close()
	var conn net.Conn = raw
	if st.spec != "none" {
		conn = &stratConn{Conn: raw, st: st}
	}
	cfg := &tls.Config{ServerName: sni, MinVersion: tls.VersionTLS12, NextProtos: []string{"dot"}}
	if st.x25 {
		cfg.CurvePreferences = []tls.CurveID{tls.X25519}
	}
	tc := tls.Client(conn, cfg)
	if err := tc.HandshakeContext(ctx); err != nil {
		return result{err: classify(err, true), dur: time.Since(start)}
	}
	_ = tc.SetDeadline(time.Now().Add(timeout))
	q := buildQuery(names[idx%len(names)])
	framed := append([]byte{byte(len(q) >> 8), byte(len(q))}, q...)
	if _, err := tc.Write(framed); err != nil {
		return result{err: classify(err, true), dur: time.Since(start)}
	}
	var lp [2]byte
	if _, err := io.ReadFull(tc, lp[:]); err != nil {
		return result{err: classify(err, true), dur: time.Since(start)}
	}
	ans := make([]byte, int(lp[0])<<8|int(lp[1]))
	if _, err := io.ReadFull(tc, ans); err != nil {
		return result{err: classify(err, true), dur: time.Since(start)}
	}
	if len(ans) < 12 || ans[2]&0x80 == 0 || ans[3]&0x0f != 0 {
		return result{err: "tls/http:bad-dns", dur: time.Since(start)}
	}
	return result{ok: true, dur: time.Since(start)}
}

func attempt(h hostSpec, addr string, st strategy, method string, timeout time.Duration, idx int) result {
	if method == "dot" {
		return attemptDoT(h, addr, st, timeout, idx)
	}
	info := &attemptInfo{}
	sni := h.sni
	if st.kase {
		sni = altCase(sni)
	}
	dialer := &net.Dialer{
		Timeout: timeout,
		Control: func(_, _ string, rc syscall.RawConn) error {
			if st.mss <= 0 {
				return nil
			}
			var serr error
			if err := rc.Control(func(fd uintptr) {
				serr = syscall.SetsockoptInt(int(fd), syscall.IPPROTO_TCP, syscall.TCP_MAXSEG, st.mss)
			}); err != nil {
				return err
			}
			return serr
		},
	}
	port := "443"
	cfg := &tls.Config{ServerName: sni, MinVersion: tls.VersionTLS12, NextProtos: []string{"h2", "http/1.1"}}
	if diagSNI != "" {
		cfg.ServerName = diagSNI
		cfg.InsecureSkipVerify = true
	}
	if diagTLS12 {
		cfg.MaxVersion = tls.VersionTLS12
	}
	if st.x25 {
		cfg.CurvePreferences = []tls.CurveID{tls.X25519}
	}
	var rt http.RoundTripper
	if st.utls != "" {
		rt = &http2.Transport{DialTLSContext: func(ctx context.Context, network, _ string, _ *tls.Config) (net.Conn, error) {
			conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(addr, port))
			if err != nil {
				return nil, err
			}
			info.mu.Lock()
			info.connectedAt = time.Now()
			info.mu.Unlock()
			var c net.Conn = conn
			if len(st.tcp) > 0 || len(st.rec) > 0 {
				c = &stratConn{Conn: conn, st: st}
			}
			name := sni
			if diagSNI != "" {
				name = diagSNI
			}
			uc := utls.UClient(c, &utls.Config{ServerName: name, InsecureSkipVerify: diagSNI != "", NextProtos: []string{"h2", "http/1.1"}}, utlsID(st.utls))
			if err := uc.HandshakeContext(ctx); err != nil {
				conn.Close()
				return nil, err
			}
			return uc, nil
		}}
	}
	tr := &http.Transport{
		Proxy:             nil,
		DisableKeepAlives: true,
		ForceAttemptHTTP2: true,
		DialContext: func(ctx context.Context, network, _ string) (net.Conn, error) {
			conn, err := dialer.DialContext(ctx, network, net.JoinHostPort(addr, port))
			if err != nil {
				return nil, err
			}
			info.mu.Lock()
			info.connectedAt = time.Now()
			info.mu.Unlock()
			if st.spec == "none" {
				return conn, nil
			}
			return &stratConn{Conn: conn, st: st}, nil
		},
		TLSClientConfig:       cfg,
		TLSHandshakeTimeout:   timeout,
		ResponseHeaderTimeout: timeout,
	}
	defer tr.CloseIdleConnections()
	if rt == nil {
		rt = tr
	}
	client := &http.Client{Transport: rt}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	query := buildQuery(names[idx%len(names)])
	url := "https://" + h.sni + h.path
	var req *http.Request
	var err error
	if method == "get" {
		url += "?dns=" + b64(query)
		req, err = http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
		if err == nil {
			req.Header.Set("Accept", "application/dns-message")
		}
	} else {
		req, err = http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(query))
		if err == nil {
			req.Header.Set("Content-Type", "application/dns-message")
			req.Header.Set("Accept", "application/dns-message")
		}
	}
	if err != nil {
		return result{err: err.Error()}
	}
	start := time.Now()
	resp, err := client.Do(req)
	connected := !info.connectedAt.IsZero()
	if err != nil {
		return result{err: classify(err, connected), dur: time.Since(start)}
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 65535))
	dur := time.Since(start)
	if diagReach {
		return result{ok: true, dur: dur}
	}
	if h.reach {
		if resp.StatusCode == 404 || (resp.StatusCode >= 200 && resp.StatusCode < 300) {
			return result{ok: true, dur: dur}
		}
		return result{err: fmt.Sprintf("tls/http:status %d", resp.StatusCode), dur: dur}
	}
	if err != nil {
		return result{err: classify(err, true), dur: dur}
	}
	if resp.StatusCode != 200 {
		return result{err: fmt.Sprintf("tls/http:status %d", resp.StatusCode), dur: dur}
	}
	if len(body) < 12 || body[0] != 0 || body[1] != 0 || body[2]&0x80 == 0 || body[3]&0x0f != 0 {
		return result{err: "tls/http:bad-dns", dur: dur}
	}
	return result{ok: true, dur: dur}
}

func b64(b []byte) string {
	const enc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
	var sb strings.Builder
	for i := 0; i < len(b); i += 3 {
		var v uint32
		n := 0
		for j := 0; j < 3 && i+j < len(b); j++ {
			v |= uint32(b[i+j]) << (16 - 8*uint(j))
			n++
		}
		for j := 0; j < n+1; j++ {
			sb.WriteByte(enc[(v>>(18-6*uint(j)))&63])
		}
	}
	return sb.String()
}

func median(d []time.Duration) time.Duration {
	if len(d) == 0 {
		return 0
	}
	s := append([]time.Duration(nil), d...)
	sort.Slice(s, func(i, j int) bool { return s[i] < s[j] })
	return s[len(s)/2]
}

// grid возвращает набор стратегий по имени.
func grid(name string) []string {
	switch name {
	case "base":
		return []string{"none"}
	case "s1":
		return []string{
			"none",
			"tcp=1", "tcp=2", "tcp=5", "tcp=40", "tcp=sni-1", "tcp=sni", "tcp=mid", "tcp=end", "tcp=end+1",
			"tcp=1/mid", "tcp=1/sni/end", "tcp=2/sni/mid/end", "tcp=1/256/512", // последний — действующий план ядра
			"rec=1", "rec=5", "rec=sni", "rec=mid", "rec=sni/end",
			"rec=1;tcp=1", "rec=mid;tcp=mid",
			"mss=88", "mss=536",
			"case=1", "case=1;tcp=mid",
			"hello=x25519", "hello=x25519;tcp=1", "hello=x25519;tcp=mid", "hello=x25519;rec=mid",
		}
	case "delay":
		var out []string
		for _, d := range []string{"0", "1", "4", "10", "30"} {
			out = append(out, "tcp=1;d="+d, "tcp=mid;d="+d, "tcp=1/mid/end;d="+d)
		}
		return out
	case "every":
		var out []string
		for _, n := range []int{8, 16, 32, 64, 128} {
			var offs []string
			for i := n; i < 1500; i += n {
				offs = append(offs, strconv.Itoa(i))
			}
			out = append(out, "tcp="+strings.Join(offs, "/")+";d=1")
		}
		return out
	}
	return strings.Split(name, "|")
}

// Диагностика: подменить SNI (без проверки сертификата) и считать «дошли» любой ответ HTTP.
var (
	diagSNI   string
	diagReach bool
	diagTLS12 bool
)

type job struct {
	h    hostSpec
	addr string
	st   strategy
}

func main() {
	hostFlag := flag.String("host", "cf", "cf|dns|ru, через запятую")
	addrFlag := flag.String("addr", "", "адреса вместо встроенных, через запятую")
	famFlag := flag.String("fam", "all", "v4|v6|all")
	n := flag.Int("n", 20, "попыток на строку")
	gridFlag := flag.String("grid", "s1", "base|s1|delay|every или строки стратегий через `|`")
	method := flag.String("method", "post", "post|get|dot")
	timeout := flag.Duration("timeout", 3*time.Second, "бюджет одной попытки")
	gap := flag.Duration("gap", 100*time.Millisecond, "пауза между попытками")
	par := flag.Int("par", 4, "параллельных строк")
	flag.StringVar(&diagSNI, "diag-sni", "", "диагностика: другой SNI, сертификат не проверяется")
	flag.BoolVar(&diagReach, "diag-reach", false, "диагностика: успех = любой HTTP-ответ")
	flag.BoolVar(&diagTLS12, "diag-tls12", false, "диагностика: только TLS 1.2")
	netLabel := flag.String("net", "?", "метка сети в выводе")
	flag.Parse()

	var jobs []job
	for _, hn := range strings.Split(*hostFlag, ",") {
		h, ok := hosts[strings.TrimSpace(hn)]
		if !ok {
			fmt.Fprintln(os.Stderr, "unknown host", hn)
			os.Exit(2)
		}
		addrs := h.addrs
		if *addrFlag != "" {
			addrs = strings.Split(*addrFlag, ",")
		}
		for _, a := range addrs {
			v6 := strings.Contains(a, ":")
			if (*famFlag == "v4" && v6) || (*famFlag == "v6" && !v6) {
				continue
			}
			for _, spec := range grid(*gridFlag) {
				st, err := parseStrategy(spec)
				if err != nil {
					fmt.Fprintln(os.Stderr, "bad strategy", spec, err)
					os.Exit(2)
				}
				jobs = append(jobs, job{h, a, st})
			}
		}
	}

	var mu sync.Mutex
	var wg sync.WaitGroup
	sem := make(chan struct{}, *par)
	for _, j := range jobs {
		wg.Add(1)
		sem <- struct{}{}
		go func(j job) {
			defer wg.Done()
			defer func() { <-sem }()
			rng := rand.New(rand.NewSource(time.Now().UnixNano()))
			ok := 0
			var durs []time.Duration
			errs := map[string]int{}
			for i := 0; i < *n; i++ {
				r := attempt(j.h, j.addr, j.st, *method, *timeout, rng.Intn(1000)+i)
				if r.ok {
					ok++
					durs = append(durs, r.dur)
				} else {
					errs[r.err]++
				}
				time.Sleep(*gap)
			}
			var es []string
			for k, v := range errs {
				es = append(es, fmt.Sprintf("%s×%d", k, v))
			}
			sort.Strings(es)
			mu.Lock()
			fmt.Printf("%s\t%s\t%s\t%s\t%s\t%d/%d\t%dms\t%s\n", *netLabel, j.h.name, *method, j.addr, j.st.spec, ok, *n, median(durs).Milliseconds(), strings.Join(es, " "))
			mu.Unlock()
		}(j)
	}
	wg.Wait()
}

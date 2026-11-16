// cfprobe — лабораторный замер: какими способами с телефона без root доходит DoH до cf.dns-ai.ru.
//
// Не часть приложения. Собирается статически под linux/arm64 и запускается через adb shell:
//
//	GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go build -o cfprobe ./cmd/cfprobe
//	adb push cfprobe /data/local/tmp/ && adb shell SSL_CERT_DIR=/system/etc/security/cacerts /data/local/tmp/cfprobe -mode plain
//
// Режимы:
//
//	plain   обычное TLS+HTTP/2 DoH на адреса из -ips, порт 443 (опорная линия)
//	ech     то же с Encrypted ClientHello (внешний SNI cloudflare-ech.com, настоящий внутри)
//	port    TLS на порты Cloudflare 2053 2083 2087 2096 8443 (и 443) без ECH
//	h3      DoH по HTTP/3 (QUIC, UDP) на -ips, порт 443
//	scan    по одному адресу на каждую /24 из опубликованных диапазонов Cloudflare, режим -scan-with plain|ech
//
// Успех = ответ DoH с QR=1, RCODE=0 и тем же id. Всё, что нужно root (подделка пакетов, TTL), не реализовано.
package main

import (
	"context"
	"crypto/tls"
	"encoding/base64"
	"encoding/binary"
	"flag"
	"fmt"
	"io"
	"math/rand"
	"net"
	"net/http"
	"os"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"golang.org/x/net/http2"
)

const (
	sniInner = "cf.dns-ai.ru"
	// ech из записи HTTPS cf.dns-ai.ru (public_name cloudflare-ech.com); приходит вместе с записью, меняется редко.
	echDefault = "AEX+DQBBZwAgACBxoBGc3/1oKLHSAwCqwR4DOpLnk96pdk1CvIZFE6fJTQAEAAEAAQASY2xvdWRmbGFyZS1lY2guY29tAAA="
)

var cfRanges = []string{
	"173.245.48.0/20", "103.21.244.0/22", "103.22.200.0/22", "103.31.4.0/22", "141.101.64.0/18",
	"108.162.192.0/18", "190.93.240.0/20", "188.114.96.0/20", "197.234.240.0/22", "198.41.128.0/17",
	"162.158.0.0/15", "104.16.0.0/13", "104.24.0.0/14", "172.64.0.0/13", "131.0.72.0/22",
}

func dnsQuery() []byte {
	q := []byte{0x4e, 0x56, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0}
	for _, l := range strings.Split("example.com", ".") {
		q = append(q, byte(len(l)))
		q = append(q, l...)
	}
	q = append(q, 0, 0, 1, 0, 1)
	return q
}

func checkAnswer(b []byte) error {
	if len(b) < 12 {
		return fmt.Errorf("short answer %d", len(b))
	}
	if b[0] != 0x4e || b[1] != 0x56 {
		// GET без обнуления id не используем; POST возвращает id как есть
		return fmt.Errorf("id mismatch")
	}
	if b[2]&0x80 == 0 || b[3]&0x0f != 0 {
		return fmt.Errorf("rcode/qr %02x%02x", b[2], b[3])
	}
	return nil
}

type result struct {
	ok  bool
	ms  int64
	err string
}

func classify(err error) string {
	if err == nil {
		return ""
	}
	s := err.Error()
	switch {
	case strings.Contains(s, "i/o timeout"), strings.Contains(s, "deadline exceeded"), strings.Contains(s, "timeout"):
		return "timeout"
	case strings.Contains(s, "reset"):
		return "reset"
	case strings.Contains(s, "refused"):
		return "refused"
	case strings.Contains(s, "unreachable"):
		return "unreachable"
	}
	if len(s) > 80 {
		s = s[:80]
	}
	return s
}

func tlsConfig(sni string, ech []byte, h2 bool) *tls.Config {
	cfg := &tls.Config{ServerName: sni, MinVersion: tls.VersionTLS12}
	if h2 {
		cfg.NextProtos = []string{"h2", "http/1.1"}
	}
	if ech != nil {
		cfg.MinVersion = tls.VersionTLS13
		cfg.EncryptedClientHelloConfigList = ech
	}
	return cfg
}

// dohTCP — полный круг: TCP, TLS (по желанию с ECH), HTTP/2, один POST.
func dohTCP(ip string, port int, ech []byte, budget time.Duration) result {
	start := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), budget)
	defer cancel()
	d := net.Dialer{}
	raw, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ip, fmt.Sprint(port)))
	if err != nil {
		return result{err: "tcp:" + classify(err)}
	}
	defer raw.Close()
	_ = raw.SetDeadline(start.Add(budget))
	tc := tls.Client(raw, tlsConfig(sniInner, ech, true))
	if err := tc.HandshakeContext(ctx); err != nil {
		return result{err: "tls:" + classify(err)}
	}
	cc, err := (&http2.Transport{}).NewClientConn(tc)
	if err != nil {
		return result{err: "h2:" + classify(err)}
	}
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, "https://"+sniInner+"/dns-query", strings.NewReader(string(dnsQuery())))
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := cc.RoundTrip(req)
	if err != nil {
		return result{err: "http:" + classify(err)}
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return result{err: fmt.Sprintf("status %d", resp.StatusCode)}
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 65535))
	if err != nil {
		return result{err: "body:" + classify(err)}
	}
	if err := checkAnswer(body); err != nil {
		return result{err: err.Error()}
	}
	return result{ok: true, ms: time.Since(start).Milliseconds()}
}

func dohH3(ip string, port int, budget time.Duration) result {
	start := time.Now()
	ctx, cancel := context.WithTimeout(context.Background(), budget)
	defer cancel()
	tr := &http3.Transport{
		TLSClientConfig: &tls.Config{ServerName: sniInner, NextProtos: []string{"h3"}, MinVersion: tls.VersionTLS13},
		Dial: func(ctx context.Context, _ string, tlsCfg *tls.Config, cfg *quic.Config) (*quic.Conn, error) {
			addr, err := net.ResolveUDPAddr("udp", net.JoinHostPort(ip, fmt.Sprint(port)))
			if err != nil {
				return nil, err
			}
			udp, err := net.ListenUDP("udp", nil)
			if err != nil {
				return nil, err
			}
			return quic.Dial(ctx, udp, addr, tlsCfg, cfg)
		},
	}
	defer tr.Close()
	req, _ := http.NewRequestWithContext(ctx, http.MethodPost, "https://"+sniInner+"/dns-query", strings.NewReader(string(dnsQuery())))
	req.Header.Set("Content-Type", "application/dns-message")
	req.Header.Set("Accept", "application/dns-message")
	resp, err := tr.RoundTrip(req)
	if err != nil {
		return result{err: "h3:" + classify(err)}
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return result{err: fmt.Sprintf("status %d", resp.StatusCode)}
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 65535))
	if err != nil {
		return result{err: "body:" + classify(err)}
	}
	if err := checkAnswer(body); err != nil {
		return result{err: err.Error()}
	}
	return result{ok: true, ms: time.Since(start).Milliseconds()}
}

func median(v []int64) int64 {
	if len(v) == 0 {
		return 0
	}
	sort.Slice(v, func(i, j int) bool { return v[i] < v[j] })
	return v[len(v)/2]
}

func runLine(label string, n int, f func() result) {
	okN := 0
	var ms []int64
	errs := map[string]int{}
	for i := 0; i < n; i++ {
		r := f()
		if r.ok {
			okN++
			ms = append(ms, r.ms)
		} else {
			errs[r.err]++
		}
		time.Sleep(60 * time.Millisecond)
	}
	var es []string
	for k, v := range errs {
		es = append(es, fmt.Sprintf("%s×%d", k, v))
	}
	sort.Strings(es)
	fmt.Printf("%-44s %2d/%-2d med=%4dms  %s\n", label, okN, n, median(ms), strings.Join(es, " "))
}

func randIn24(base net.IP, rnd *rand.Rand) string {
	ip := base.To4()
	return fmt.Sprintf("%d.%d.%d.%d", ip[0], ip[1], ip[2], 1+rnd.Intn(254))
}

func allSlash24() []net.IP {
	var out []net.IP
	for _, c := range cfRanges {
		_, nw, _ := net.ParseCIDR(c)
		base := binary.BigEndian.Uint32(nw.IP.To4())
		ones, bits := nw.Mask.Size()
		count := 1 << uint(24-ones)
		if bits != 32 || ones > 24 {
			count = 1
		}
		for i := 0; i < count; i++ {
			var b [4]byte
			binary.BigEndian.PutUint32(b[:], base+uint32(i)<<8)
			out = append(out, net.IP(b[:]))
		}
	}
	return out
}

func main() {
	mode := flag.String("mode", "plain", "plain|ech|port|h3|scan")
	ips := flag.String("ips", "172.64.80.1,104.21.82.125,172.67.157.232,104.16.248.249,1.1.1.1,162.159.61.4", "адреса через запятую")
	n := flag.Int("n", 6, "попыток на строку")
	budget := flag.Duration("budget", 4*time.Second, "бюджет одной попытки")
	echB64 := flag.String("ech", echDefault, "ECHConfigList в base64")
	scanWith := flag.String("scan-with", "plain", "plain|ech|h3 для scan")
	workers := flag.Int("workers", 150, "параллельность scan")
	limit := flag.Int("limit", 0, "сколько /24 просканировать (0 = все)")
	seed := flag.Int64("seed", 1, "seed выбора адреса в /24")
	sniFlag := flag.String("sni", "", "diag: ServerName в ClientHello")
	hostFlag := flag.String("host", "", "diag: :authority / Host")
	tls12 := flag.Bool("tls12", false, "diag: только TLS 1.2")
	appSplit := flag.Bool("appsplit", false, "diag: прикладные данные по одному байту")
	wdelay := flag.Duration("wdelay", 0, "diag: пауза после рукопожатия")
	http1 := flag.Bool("http1", false, "diag: HTTP/1.1 вместо h2")
	coalesce := flag.Bool("coalesce", false, "diag: HEADERS+DATA одной записью")
	getReq := flag.Bool("get", false, "diag: GET ?dns= (h2)")
	useECH := flag.Bool("useech", false, "diag: ECH")
	echOuter := flag.String("echouter", "", "diag: подменить public_name в ECHConfig")
	flag.Parse()

	ech, err := base64.StdEncoding.DecodeString(*echB64)
	if err != nil {
		fmt.Println("bad ech:", err)
		os.Exit(2)
	}
	list := strings.Split(*ips, ",")

	switch *mode {
	case "plain", "ech":
		var e []byte
		if *mode == "ech" {
			e = ech
		}
		for _, ip := range list {
			ip := ip
			runLine(*mode+" "+ip+":443", *n, func() result { return dohTCP(ip, 443, e, *budget) })
		}
	case "diag":
		var e []byte
		if *useECH {
			e = ech
			if *echOuter != "" {
				e = rewritePublicName(ech, *echOuter)
			}
		}
		o := diagOpts{sni: *sniFlag, host: *hostFlag, tls12: *tls12, appSplit: *appSplit, wdelay: *wdelay, ech: e, http1: *http1, coalesce: *coalesce, getReq: *getReq}
		for _, ip := range list {
			ip := ip
			runLine("diag "+ip, *n, func() result { return diag(ip, 443, o, *budget) })
		}
	case "port":
		for _, ip := range list {
			for _, p := range []int{443, 2053, 2083, 2087, 2096, 8443} {
				ip, p := ip, p
				runLine(fmt.Sprintf("port %s:%d", ip, p), *n, func() result { return dohTCP(ip, p, nil, *budget) })
			}
		}
	case "h3":
		for _, ip := range list {
			ip := ip
			runLine("h3 "+ip+":443", *n, func() result { return dohH3(ip, 443, *budget) })
		}
	case "scan":
		rnd := rand.New(rand.NewSource(*seed))
		blocks := allSlash24()
		rnd.Shuffle(len(blocks), func(i, j int) { blocks[i], blocks[j] = blocks[j], blocks[i] })
		if *limit > 0 && *limit < len(blocks) {
			blocks = blocks[:*limit]
		}
		fmt.Printf("scan: %d blocks (/24), with=%s, workers=%d\n", len(blocks), *scanWith, *workers)
		var done, okN int64
		errs := sync.Map{}
		var mu sync.Mutex
		var good []string
		ch := make(chan string)
		var wg sync.WaitGroup
		var e []byte
		if *scanWith == "ech" {
			e = ech
		}
		for w := 0; w < *workers; w++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				for ip := range ch {
					var r result
					if *scanWith == "h3" {
						r = dohH3(ip, 443, *budget)
					} else if *scanWith == "http1" {
						r = diag(ip, 443, diagOpts{http1: true}, *budget)
					} else {
						r = dohTCP(ip, 443, e, *budget)
					}
					atomic.AddInt64(&done, 1)
					if r.ok {
						atomic.AddInt64(&okN, 1)
						mu.Lock()
						good = append(good, fmt.Sprintf("%s(%dms)", ip, r.ms))
						mu.Unlock()
					} else {
						c, _ := errs.LoadOrStore(r.err, new(int64))
						atomic.AddInt64(c.(*int64), 1)
					}
				}
			}()
		}
		startAt := time.Now()
		go func() {
			for {
				time.Sleep(10 * time.Second)
				fmt.Printf("  ... %d/%d done, %d ok, %ds\n", atomic.LoadInt64(&done), len(blocks), atomic.LoadInt64(&okN), int(time.Since(startAt).Seconds()))
			}
		}()
		for _, b := range blocks {
			ch <- randIn24(b, rnd)
		}
		close(ch)
		wg.Wait()
		fmt.Printf("scan finished: %d ok of %d in %ds\n", okN, len(blocks), int(time.Since(startAt).Seconds()))
		sort.Strings(good)
		fmt.Println("good:", strings.Join(good, " "))
		errs.Range(func(k, v any) bool {
			fmt.Printf("  %s ×%d\n", k, *v.(*int64))
			return true
		})
	default:
		fmt.Println("unknown mode")
		os.Exit(2)
	}
}

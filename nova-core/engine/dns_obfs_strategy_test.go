package nova

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"errors"
	"math/big"
	"net"
	"strings"
	"sync"
	"testing"
	"time"
)

// realClientHello снимает настоящий ClientHello Go для имени host.
func realClientHello(t *testing.T, host string) []byte {
	t.Helper()
	client, server := net.Pipe()
	defer server.Close()
	captured := make(chan []byte, 1)
	go func() {
		buf := make([]byte, 8192)
		n, _ := server.Read(buf)
		captured <- append([]byte(nil), buf[:n]...)
		server.Close()
	}()
	conn := tls.Client(client, &tls.Config{ServerName: host, NextProtos: []string{"h2"}})
	ctx, cancel := context.WithTimeout(context.Background(), time.Second)
	defer cancel()
	_ = conn.HandshakeContext(ctx)
	_ = client.Close()
	return <-captured
}

func TestRecMidKeepsTheSNIOutOfEverySegment(t *testing.T) {
	hello := realClientHello(t, "cf.dns-ai.ru")
	if len(hello) < 100 || hello[0] != 0x16 {
		t.Fatalf("not a ClientHello: %d bytes", len(hello))
	}
	segs := obfsRegistry[obfsRecMid].segments(hello)
	if len(segs) != 2 {
		t.Fatalf("recmid gave %d segments, want 2", len(segs))
	}
	for i, seg := range segs {
		if bytes.Contains(seg, []byte("cf.dns-ai.ru")) {
			t.Fatalf("segment %d holds the whole SNI", i)
		}
	}
	joined := bytes.Join(segs, nil)
	// Две записи вместо одной: заголовок второй записи добавляет пять байт.
	if len(joined) != len(hello)+5 {
		t.Fatalf("stream is %d bytes, want %d", len(joined), len(hello)+5)
	}
	body := hello[5:]
	first := int(joined[3])<<8 | int(joined[4])
	rest := joined[5+first:]
	if rest[0] != 0x16 {
		t.Fatalf("second record type = %#x", rest[0])
	}
	second := int(rest[3])<<8 | int(rest[4])
	rebuilt := append(append([]byte(nil), joined[5:5+first]...), rest[5:5+second]...)
	if !bytes.Equal(rebuilt, body) {
		t.Fatal("record bodies do not rebuild the ClientHello")
	}
}

func TestTCPMidSplitsInsideTheNameAndKeepsTheBytes(t *testing.T) {
	hello := realClientHello(t, "dns.dns-ai.ru")
	segs := obfsRegistry[obfsTCPMid].segments(hello)
	if len(segs) != 2 {
		t.Fatalf("tcpmid gave %d segments, want 2", len(segs))
	}
	if !bytes.Equal(bytes.Join(segs, nil), hello) {
		t.Fatal("tcpmid changed the bytes")
	}
	for i, seg := range segs {
		if bytes.Contains(seg, []byte("dns.dns-ai.ru")) {
			t.Fatalf("segment %d holds the whole SNI", i)
		}
	}
	if !bytes.Contains(segs[0], []byte("dns.dn")) && !bytes.Contains(segs[0], []byte("dns.d")) {
		t.Fatalf("the cut is not inside the name: first segment ends %q", segs[0][len(segs[0])-8:])
	}
}

func TestTCP1SplitsOffOneByte(t *testing.T) {
	hello := realClientHello(t, "dns.dns-ai.ru")
	segs := obfsRegistry[obfsTCP1].segments(hello)
	if len(segs) != 2 || len(segs[0]) != 1 {
		t.Fatalf("segments = %d, first %d bytes", len(segs), len(segs[0]))
	}
	if !bytes.Equal(bytes.Join(segs, nil), hello) {
		t.Fatal("tcp1 changed the bytes")
	}
}

func TestPlainStrategyIsOneSegment(t *testing.T) {
	hello := realClientHello(t, "dns.dns-ai.ru")
	segs := obfsRegistry[obfsPlain].segments(hello)
	if len(segs) != 1 || !bytes.Equal(segs[0], hello) {
		t.Fatal("plain must pass the record untouched")
	}
}

// Сервер Go должен принимать ClientHello, разрезанный каждой стратегией: иначе лестница ломала бы
// рукопожатие там, где обычный ClientHello проходил.
func TestEveryStrategyCompletesATLSHandshake(t *testing.T) {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "cf.dns-ai.ru"},
		DNSNames:     []string{"cf.dns-ai.ru"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	cert, _ := x509.ParseCertificate(der)
	pool := x509.NewCertPool()
	pool.AddCert(cert)
	serverCfg := &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}}

	for name, st := range obfsRegistry {
		listener, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			t.Fatal(err)
		}
		go func() {
			conn, err := listener.Accept()
			if err != nil {
				return
			}
			s := tls.Server(conn, serverCfg)
			if s.Handshake() == nil {
				_, _ = s.Write([]byte("ok"))
			}
			_ = s.Close()
		}()
		raw, err := net.Dial("tcp", listener.Addr().String())
		if err != nil {
			t.Fatal(err)
		}
		conn := tls.Client(obfuscateDNSConn(raw, dnsRouteDirectObfs, st), &tls.Config{ServerName: "cf.dns-ai.ru", RootCAs: pool})
		_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
		if err := conn.Handshake(); err != nil {
			t.Errorf("%s: handshake failed: %v", name, err)
		}
		_ = conn.Close()
		_ = listener.Close()
	}
}

func TestParseObfsToken(t *testing.T) {
	spec, err := parseDNSUpstream("https://cf.dns-ai.ru/dns-query|1.2.3.4|via=obfsonly|obf=tcp1")
	if err != nil {
		t.Fatal(err)
	}
	if spec.obf != obfsTCP1 {
		t.Fatalf("obf = %q", spec.obf)
	}
	if len(spec.bootstrap) != 1 {
		t.Fatalf("bootstrap = %v", spec.bootstrap)
	}
	bad, _ := parseDNSUpstream("https://cf.dns-ai.ru/dns-query|obf=nonsense")
	if bad.obf != "" {
		t.Fatalf("unknown strategy name must be ignored, got %q", bad.obf)
	}
	for _, c := range obfsCandidates(spec) {
		if c.strat.name != obfsTCP1 {
			t.Fatalf("forced strategy leaked another one: %s", c.strat.name)
		}
	}
}

func TestCandidatesOrderAndExtraAddress(t *testing.T) {
	resetObfsDirectState()
	spec, _ := parseDNSUpstream("https://cf.dns-ai.ru/dns-query|104.21.82.125|172.67.157.232|via=obfsonly")
	var got []string
	for _, c := range obfsCandidates(spec) {
		got = append(got, c.key())
	}
	want := []string{
		"tcpmid~h1@104.21.82.125", "tcpmid~h1@172.67.157.232", "tcpmid~h1@172.64.80.1", "tcpmid@172.64.80.1",
			"tcp1~h1@104.21.82.125", "recmid~h1@104.21.82.125", "plain~h1@104.21.82.125",
	}
	if strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("candidates = %v, want %v", got, want)
	}
	// dns.: без дополнительного адреса.
	dns, _ := parseDNSUpstream("https://dns.dns-ai.ru/dns-query|2a00:b700:5::3:62|45.151.102.219|via=obfsonly")
	for _, c := range obfsCandidates(dns) {
		if c.pin == "172.64.80.1" {
			t.Fatal("the Cloudflare extra address belongs to cf. only")
		}
	}
	// Победитель встаёт первым.
	rememberObfsWinner(spec, dnsAttempt{strat: obfsRegistry[obfsPlain], pin: "104.21.82.125", h1: true})
	if first := obfsCandidates(spec)[0].key(); first != "plain~h1@104.21.82.125" {
		t.Fatalf("winner is not first: %s", first)
	}
	resetObfsDirectState()
	if first := obfsCandidates(spec)[0].key(); first != "tcpmid~h1@104.21.82.125" {
		t.Fatalf("winner survived the reset: %s", first)
	}
}

func TestWinnerAddressGetsTheOtherStrategiesToo(t *testing.T) {
	resetObfsDirectState()
	t.Cleanup(resetObfsDirectState)
	spec, _ := parseDNSUpstream("https://cf.dns-ai.ru/dns-query|104.21.82.125|172.67.157.232|via=obfsonly")
	rememberObfsWinner(spec, dnsAttempt{strat: obfsRegistry[obfsTCPMid], pin: "172.64.80.1"})
	cands := obfsCandidates(spec)
	if cands[0].key() != "tcpmid@172.64.80.1" {
		t.Fatalf("winner is not first: %s", cands[0].key())
	}
	var retry bool
	for _, c := range cands[1:] {
		if c.pin == "172.64.80.1" && c.strat.name != obfsTCPMid {
			retry = true
		}
		if c.key() == cands[0].key() {
			t.Fatalf("the winner is listed twice")
		}
	}
	if !retry {
		t.Fatalf("a stalled winner must be retried on the same address with another strategy: %v", cands)
	}
}

func withAttemptFn(t *testing.T, fn func(query []byte, spec *dnsUpstreamSpec, route dnsUpstreamRoute, attempt *dnsAttempt, timeout time.Duration) ([]byte, string, error)) {
	t.Helper()
	old := obfsAttemptFn
	obfsAttemptFn = fn
	t.Cleanup(func() { obfsAttemptFn = old; resetObfsDirectState() })
}

func TestRaceTakesTheSecondCandidateWhenTheFirstHangs(t *testing.T) {
	resetObfsDirectState()
	spec, _ := parseDNSUpstream("https://cf.dns-ai.ru/dns-query|104.21.82.125|172.67.157.232|via=obfsonly")
	withAttemptFn(t, func(_ []byte, _ *dnsUpstreamSpec, _ dnsUpstreamRoute, a *dnsAttempt, timeout time.Duration) ([]byte, string, error) {
		if a.pin == "104.21.82.125" {
			time.Sleep(timeout) // режется на TLS: соединение есть, ответа нет
			return nil, "", errors.New("timeout")
		}
		return []byte{1}, "ok", nil
	})
	started := time.Now()
	payload, _, err := raceDirectObfs(make([]byte, 12), spec, 1500*time.Millisecond)
	if err != nil || len(payload) != 1 {
		t.Fatalf("race failed: %v", err)
	}
	if took := time.Since(started); took > obfsRaceStagger+200*time.Millisecond {
		t.Fatalf("a hung first candidate cost %s", took)
	}
	if first := obfsCandidates(spec)[0].key(); first != "tcpmid~h1@172.67.157.232" {
		t.Fatalf("winner not remembered: %s", first)
	}
}

func TestRaceStartsTheNextCandidateAtOnceOnAFastRefusal(t *testing.T) {
	resetObfsDirectState()
	spec, _ := parseDNSUpstream("https://dns.dns-ai.ru/dns-query|45.151.102.219|via=obfsonly")
	calls := 0
	var mu sync.Mutex
	withAttemptFn(t, func(_ []byte, _ *dnsUpstreamSpec, _ dnsUpstreamRoute, _ *dnsAttempt, _ time.Duration) ([]byte, string, error) {
		mu.Lock()
		calls++
		n := calls
		mu.Unlock()
		if n == 1 {
			return nil, "", errors.New("connection reset")
		}
		return []byte{1}, "ok", nil
	})
	started := time.Now()
	if _, _, err := raceDirectObfs(make([]byte, 12), spec, time.Second); err != nil {
		t.Fatal(err)
	}
	if took := time.Since(started); took > obfsRaceStagger/2 {
		t.Fatalf("a refusal waited for the stagger: %s", took)
	}
}

func TestRaceFailsWithinTheBudgetWhenEverythingHangs(t *testing.T) {
	resetObfsDirectState()
	spec, _ := parseDNSUpstream("https://cf.dns-ai.ru/dns-query|104.21.82.125|172.67.157.232|via=obfsonly")
	withAttemptFn(t, func(_ []byte, _ *dnsUpstreamSpec, _ dnsUpstreamRoute, _ *dnsAttempt, timeout time.Duration) ([]byte, string, error) {
		time.Sleep(timeout)
		return nil, "", errors.New("timeout")
	})
	started := time.Now()
	if _, _, err := raceDirectObfs(make([]byte, 12), spec, 800*time.Millisecond); err == nil {
		t.Fatal("a dead entrance must fail")
	}
	if took := time.Since(started); took > 1200*time.Millisecond {
		t.Fatalf("the race overran its budget: %s", took)
	}
}

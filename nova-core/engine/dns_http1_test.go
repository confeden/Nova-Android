package nova

import (
	"crypto/x509"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

// cf. идёт по HTTP/1.1, у остальных хостов h2 не трогается (D47).
func TestCfCandidatesUseHTTP1WithOneH2Fallback(t *testing.T) {
	resetObfsDirectState()
	t.Cleanup(resetObfsDirectState)
	cf, _ := parseDNSUpstream("https://cf.dns-ai.ru/dns-query|104.21.82.125|172.67.157.232|via=obfsonly")
	var h1, h2 int
	var h2Pins []string
	for _, c := range obfsCandidates(cf) {
		if c.h1 {
			h1++
		} else {
			h2++
			h2Pins = append(h2Pins, c.pin)
		}
	}
	if h1 == 0 || h2 != 1 {
		t.Fatalf("cf. wants HTTP/1.1 candidates and exactly one h2 fallback, got h1=%d h2=%d", h1, h2)
	}
	// Запасной h2 идёт по запасному адресу, на котором проходят оба протокола.
	if h2Pins[0] != "172.64.80.1" {
		t.Fatalf("the h2 fallback must use the address that passes both: %v", h2Pins)
	}
	if cands := obfsCandidates(cf); cands[0].h1 != true {
		t.Fatalf("the first candidate of cf. must be HTTP/1.1: %s", cands[0].key())
	}
	for _, raw := range []string{
		"https://dns.dns-ai.ru/dns-query|45.151.102.219|via=obfsonly",
		"https://ru.dns-ai.ru/abcdefg234567|188.72.112.1|m=get|via=obfsonly",
	} {
		spec, _ := parseDNSUpstream(raw)
		for _, c := range obfsCandidates(spec) {
			if c.h1 {
				t.Fatalf("%s must stay on h2: %s", spec.display, c.key())
			}
		}
	}
	if obfsPrefersHTTP1("dns.dns-ai.ru") || !obfsPrefersHTTP1("CF.dns-ai.ru.") {
		t.Fatal("obfsPrefersHTTP1 matches cf. only (any case, trailing dot)")
	}
}

func TestHTTP1AttemptKeyDiffersFromH2(t *testing.T) {
	a := dnsAttempt{strat: obfsRegistry[obfsTCPMid], pin: "1.2.3.4"}
	b := a
	b.h1 = true
	if a.key() == b.key() {
		t.Fatal("an h2 and an HTTP/1.1 attempt must not share a pooled client")
	}
	if !strings.Contains(b.describe(), "http1") || strings.Contains(a.describe(), "http1") {
		t.Fatalf("describe: %q / %q", a.describe(), b.describe())
	}
}

// Попытка с h1 действительно приходит на сервер как HTTP/1.1, а без неё — как h2.
func TestHTTP1AttemptReallyNegotiatesHTTP11(t *testing.T) {
	var mu sync.Mutex
	protos := map[int]int{}
	server := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		protos[r.ProtoMajor]++
		mu.Unlock()
		w.Header().Set("Content-Type", "application/dns-message")
		_, _ = w.Write([]byte{0, 0, 0x81, 0x80, 0, 1, 0, 0, 0, 0, 0, 0})
	}))
	server.EnableHTTP2 = true
	server.StartTLS()
	defer server.Close()

	pool := x509.NewCertPool()
	pool.AddCert(server.Certificate())
	oldRoots := dohRootCAs
	dohRootCAs = pool
	t.Cleanup(func() { dohRootCAs = oldRoots; resetDohClients() })

	host, port, _ := net.SplitHostPort(strings.TrimPrefix(server.URL, "https://"))
	query := make([]byte, 12)
	query[5] = 1
	for _, tc := range []struct {
		name  string
		h1    bool
		major int
	}{{"h1", true, 1}, {"h2", false, 2}} {
		upstream := &dohUpstream{
			raw:       "https://example.com:" + port + "/dns-query#" + tc.name,
			url:       "https://example.com:" + port + "/dns-query",
			host:      "example.com",
			port:      port,
			bootstrap: []string{host},
			attempt:   &dnsAttempt{strat: obfsRegistry[obfsPlain], pin: host, h1: tc.h1},
		}
		for i := 0; i < 2; i++ {
			payload, err := resolveDNSViaDoh(query, upstream, dnsRouteDirectObfs, 2*time.Second)
			if err != nil || len(payload) < 12 {
				t.Fatalf("%s: %v", tc.name, err)
			}
		}
		mu.Lock()
		got := protos[tc.major]
		mu.Unlock()
		if got != 2 {
			t.Fatalf("%s: server saw %v, want two requests over HTTP/%d", tc.name, protos, tc.major)
		}
	}
}

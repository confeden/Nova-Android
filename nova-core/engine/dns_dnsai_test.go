package nova

import (
	"context"
	"encoding/base64"
	"net/http"
	"strings"
	"testing"
	"time"
)

// Личная ссылка DNS-AI: путь — секрет устройства, в метки и журнал он не попадает, а запрос
// уходит методом GET: CDN за `ru.dns-ai.ru` на POST отвечает 413.
func TestParsePersonalLinkUpstream(t *testing.T) {
	spec, err := parseDNSUpstream("https://ru.dns-ai.ru/abcdefg234567|188.72.112.1|via=obfsonly|m=get")
	if err != nil {
		t.Fatal(err)
	}
	if !spec.get {
		t.Fatal("m=get must switch the DoH query to GET")
	}
	if spec.route != dnsRouteObfsOnly {
		t.Fatalf("route = %v, want obfs-only", spec.route)
	}
	if len(spec.bootstrap) != 1 || spec.bootstrap[0] != "188.72.112.1" {
		t.Fatalf("bootstrap = %v", spec.bootstrap)
	}
	if strings.Contains(spec.display, "abcdefg234567") {
		t.Fatalf("display leaks the token: %q", spec.display)
	}
	if spec.display != "https://ru.dns-ai.ru/…" {
		t.Fatalf("display = %q", spec.display)
	}
	if !strings.Contains(spec.target, "abcdefg234567") {
		t.Fatalf("target must keep the real URL for the request: %q", spec.target)
	}
}

func TestPlainDohKeepsPostAndItsAddress(t *testing.T) {
	spec, err := parseDNSUpstream("https://cf.dns-ai.ru/dns-query|via=obfsonly")
	if err != nil {
		t.Fatal(err)
	}
	if spec.get {
		t.Fatal("only m=get switches to GET")
	}
	if spec.display != "https://cf.dns-ai.ru/dns-query" {
		t.Fatalf("display = %q", spec.display)
	}
}

func TestGetTokenIsIgnoredOutsideDoH(t *testing.T) {
	spec, err := parseDNSUpstream("tls://dns.dns-ai.ru|m=get")
	if err != nil {
		t.Fatal(err)
	}
	if spec.get {
		t.Fatal("GET has no meaning for DoT")
	}
}

func TestObfsOnlyNeverFallsBackToTunnelItself(t *testing.T) {
	resetObfsDirectState()
	raw := "https://example.invalid/dns-query|via=obfsonly"
	spec, err := parseDNSUpstream(raw)
	if err != nil {
		t.Fatal(err)
	}
	suspendObfsDirect(raw)
	started := time.Now()
	_, _, err = resolveDNSObfsOnly(make([]byte, 12), spec, 2*time.Second)
	if err == nil {
		t.Fatal("a suspended direct path must refuse, not answer")
	}
	if time.Since(started) > 200*time.Millisecond {
		t.Fatalf("a suspended entrance must fail at once, took %s", time.Since(started))
	}
	// Туннельная копия — отдельная строка со своим состоянием.
	tunnel, err := parseDNSUpstream("https://example.invalid/dns-query|via=tunnel")
	if err != nil {
		t.Fatal(err)
	}
	if tunnel.route != dnsRouteTunnel {
		t.Fatalf("route = %v", tunnel.route)
	}
	resetObfsDirectState()
}

func TestObfsOnlyRouteKeyDiffersFromDirect(t *testing.T) {
	if routeKey("x", dnsRouteObfsOnly) == routeKey("x", dnsRouteDirect) {
		t.Fatal("obfs-only shares a pooled connection with plain direct")
	}
}

func TestPlainUdpIgnoresObfsOnly(t *testing.T) {
	spec, err := parseDNSUpstream("1.1.1.1|via=obfsonly")
	if err != nil {
		t.Fatal(err)
	}
	if spec.route != dnsRouteDirect {
		t.Fatalf("plain UDP must degrade to direct, got %v", spec.route)
	}
}

func TestDohGetURLEncodesTheQuery(t *testing.T) {
	query := []byte{0x00, 0x00, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	got := dohGetURL("https://ru.dns-ai.ru/token", query)
	want := "https://ru.dns-ai.ru/token?dns=" + base64.RawURLEncoding.EncodeToString(query)
	if got != want {
		t.Fatalf("url = %q, want %q", got, want)
	}
	if strings.Contains(got, "=") && strings.HasSuffix(got, "=") {
		t.Fatal("base64url must be unpadded")
	}
	if dohGetURL("https://h/p?x=1", query) != "https://h/p?x=1&dns="+base64.RawURLEncoding.EncodeToString(query) {
		t.Fatal("an existing query string must be extended with &")
	}
}

func TestRedactDohURL(t *testing.T) {
	cases := map[string]string{
		"https://dns.dns-ai.ru/dns-query": "https://dns.dns-ai.ru/dns-query",
		"https://host.example":            "https://host.example",
		"https://ru.dns-ai.ru/secret1234": "https://ru.dns-ai.ru/…",
	}
	for in, want := range cases {
		if got := redactDohURL(in); got != want {
			t.Errorf("redactDohURL(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestRedactUpstreamListHidesTheToken(t *testing.T) {
	got := redactUpstreamList([]string{
		"https://ru.dns-ai.ru/abcdefg234567|188.72.112.1|m=get|via=obfsonly",
		"https://cf.dns-ai.ru/dns-query|via=obfsonly",
		"tls://dns.dns-ai.ru|via=tunnel",
	})
	if strings.Contains(got, "abcdefg234567") {
		t.Fatalf("token leaks into the log line: %q", got)
	}
	want := "https://ru.dns-ai.ru/…|188.72.112.1|m=get|via=obfsonly,https://cf.dns-ai.ru/dns-query|via=obfsonly,tls://dns.dns-ai.ru|via=tunnel"
	if got != want {
		t.Fatalf("got %q, want %q", got, want)
	}
}

// Личная ссылка уходит только методом GET: токен-запрос в `?dns=`, тела нет, Content-Type нет.
func TestPersonalLinkRequestIsAlwaysGet(t *testing.T) {
	query := []byte{0x00, 0x00, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	// Токен `m=get` может и не прийти: ядро узнаёт личную ссылку само.
	for _, raw := range []string{
		"https://ru.dns-ai.ru/abcdefg234567|188.72.112.1|m=get|via=obfsonly",
		"https://ru.dns-ai.ru/abcdefg234567|188.72.112.1",
		"https://ru.dns-ai.ru/abcdefg234567|via=tunnel",
		"https://spb.dns-ai.ru/abcdefg234567",
	} {
		spec, err := parseDNSUpstream(raw)
		if err != nil {
			t.Fatalf("%s: %v", raw, err)
		}
		upstream := &dohUpstream{raw: spec.raw, url: spec.target, host: spec.host, port: spec.port, bootstrap: spec.bootstrap, get: spec.get}
		request, err := newDohRequest(context.Background(), upstream, query)
		if err != nil {
			t.Fatal(err)
		}
		if request.Method != http.MethodGet {
			t.Fatalf("%s: method = %s, want GET", spec.display, request.Method)
		}
		if request.Body != nil && request.Body != http.NoBody {
			t.Fatalf("%s: a GET must carry no body", spec.display)
		}
		if request.Header.Get("Content-Type") != "" {
			t.Fatalf("%s: a GET must carry no Content-Type", spec.display)
		}
		if got := request.URL.Query().Get("dns"); got != base64.RawURLEncoding.EncodeToString(query) {
			t.Fatalf("%s: dns= %q", spec.display, got)
		}
		if request.URL.Path != "/abcdefg234567" {
			t.Fatalf("%s: path = %q", spec.display, request.URL.Path)
		}
	}
}

func TestOrdinaryDohStaysPost(t *testing.T) {
	query := []byte{0x00, 0x00, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	for _, raw := range []string{
		"https://dns.dns-ai.ru/dns-query|via=obfsonly",
		"https://cf.dns-ai.ru/dns-query",
		"https://dns.comss.one/dns-query",
		"https://example.org/abcdefg234567",
	} {
		spec, err := parseDNSUpstream(raw)
		if err != nil {
			t.Fatal(err)
		}
		if spec.get && !strings.HasSuffix(strings.ToLower(spec.host), ".dns-ai.ru") {
			t.Fatalf("%s: GET is only for personal dns-ai.ru links", raw)
		}
		upstream := &dohUpstream{raw: spec.raw, url: spec.target, host: spec.host, port: spec.port, get: spec.get}
		request, err := newDohRequest(context.Background(), upstream, query)
		if err != nil {
			t.Fatal(err)
		}
		if request.Method != http.MethodPost {
			t.Fatalf("%s: method = %s, want POST", raw, request.Method)
		}
	}
}

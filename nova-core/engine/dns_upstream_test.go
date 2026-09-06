package nova

import "testing"

func TestParseDNSUpstreamKinds(t *testing.T) {
	cases := []struct {
		raw       string
		kind      dnsUpstreamKind
		target    string
		host      string
		port      string
		route     dnsUpstreamRoute
		bootstrap []string
	}{
		{raw: "1.1.1.1", kind: dnsUpstreamPlain, target: "1.1.1.1", host: "1.1.1.1", port: "53", route: dnsRouteDirect},
		{raw: "2606:4700:4700::1111", kind: dnsUpstreamPlain, target: "2606:4700:4700::1111", port: "53", host: "2606:4700:4700::1111", route: dnsRouteDirect},
		{
			raw:  "https://dns.dns-ai.ru/dns-query|192.144.59.14|186.246.49.127",
			kind: dnsUpstreamDoH, target: "https://dns.dns-ai.ru/dns-query",
			host: "dns.dns-ai.ru", port: "443", route: dnsRouteDirect,
			bootstrap: []string{"192.144.59.14", "186.246.49.127"},
		},
		{
			raw: "tls://dns.dns-ai.ru|192.144.59.14", kind: dnsUpstreamDoT,
			target: "dns.dns-ai.ru:853", host: "dns.dns-ai.ru", port: "853",
			route: dnsRouteDirect, bootstrap: []string{"192.144.59.14"},
		},
		{
			raw: "tls://1.1.1.1:8853", kind: dnsUpstreamDoT, target: "1.1.1.1:8853",
			host: "1.1.1.1", port: "8853", route: dnsRouteDirect,
		},
		{
			raw: "https://dns.google/dns-query|via=tunnel", kind: dnsUpstreamDoH,
			target: "https://dns.google/dns-query", host: "dns.google", port: "443",
			route: dnsRouteTunnel,
		},
		{
			raw: "https://dns.google/dns-query|8.8.8.8|via=auto", kind: dnsUpstreamDoH,
			target: "https://dns.google/dns-query", host: "dns.google", port: "443",
			route: dnsRouteAuto, bootstrap: []string{"8.8.8.8"},
		},
	}
	for _, c := range cases {
		spec, err := parseDNSUpstream(c.raw)
		if err != nil {
			t.Fatalf("%s: unexpected error %v", c.raw, err)
		}
		if spec.kind != c.kind {
			t.Errorf("%s: kind = %d, want %d", c.raw, spec.kind, c.kind)
		}
		if spec.target != c.target {
			t.Errorf("%s: target = %q, want %q", c.raw, spec.target, c.target)
		}
		if spec.host != c.host {
			t.Errorf("%s: host = %q, want %q", c.raw, spec.host, c.host)
		}
		if spec.port != c.port {
			t.Errorf("%s: port = %q, want %q", c.raw, spec.port, c.port)
		}
		if spec.route != c.route {
			t.Errorf("%s: route = %d, want %d", c.raw, spec.route, c.route)
		}
		if len(spec.bootstrap) != len(c.bootstrap) {
			t.Fatalf("%s: bootstrap = %v, want %v", c.raw, spec.bootstrap, c.bootstrap)
		}
		for i := range c.bootstrap {
			if spec.bootstrap[i] != c.bootstrap[i] {
				t.Errorf("%s: bootstrap[%d] = %q, want %q", c.raw, i, spec.bootstrap[i], c.bootstrap[i])
			}
		}
	}
}

// A plaintext upstream must never enter the path race: a forged answer from the
// ISP arrives sooner than the real one, so "pick the faster path" would pick the
// forgery. Only TLS-authenticated upstreams may race.
func TestPlainUpstreamNeverRaces(t *testing.T) {
	spec, err := parseDNSUpstream("8.8.8.8|via=auto")
	if err != nil {
		t.Fatalf("unexpected error %v", err)
	}
	if spec.route != dnsRouteDirect {
		t.Fatalf("route = %d, want direct", spec.route)
	}
	if spec.encrypted() {
		t.Fatal("a bare IP must not count as encrypted")
	}
}

func TestParseDNSUpstreamRejects(t *testing.T) {
	for _, raw := range []string{"", "   ", "not-an-ip", "example.com", "ftp://1.1.1.1", "tls://"} {
		if spec, err := parseDNSUpstream(raw); err == nil {
			t.Errorf("%q parsed as %+v, want an error", raw, spec)
		}
	}
}

// An unknown token must be skipped, not fatal: an older core already ignored
// everything that was not an IP, and a new flag has to degrade the same way.
func TestUnknownTokenIsSkipped(t *testing.T) {
	spec, err := parseDNSUpstream("https://dns.google/dns-query|whatever=1|8.8.4.4")
	if err != nil {
		t.Fatalf("unexpected error %v", err)
	}
	if len(spec.bootstrap) != 1 || spec.bootstrap[0] != "8.8.4.4" {
		t.Fatalf("bootstrap = %v, want [8.8.4.4]", spec.bootstrap)
	}
	if spec.route != dnsRouteDirect {
		t.Fatalf("route = %d, want direct", spec.route)
	}
}

func TestRouteKeySeparatesPaths(t *testing.T) {
	raw := "https://dns.google/dns-query"
	if routeKey(raw, dnsRouteDirect) == routeKey(raw, dnsRouteTunnel) {
		t.Fatal("direct and tunnel must not share a cached connection")
	}
}

func TestRouteChoiceExpiryAndForget(t *testing.T) {
	resetDNSRouteChoices()
	raw := "https://dns.dns-ai.ru/dns-query"
	if _, ok := recallDNSRoute(raw); ok {
		t.Fatal("nothing should be remembered yet")
	}
	rememberDNSRoute(raw, dnsRouteTunnel)
	route, ok := recallDNSRoute(raw)
	if !ok || route != dnsRouteTunnel {
		t.Fatalf("recall = (%d, %v), want (tunnel, true)", route, ok)
	}
	if DNSRouteChoiceLabel(raw) != "tunnel" {
		t.Fatalf("label = %q, want tunnel", DNSRouteChoiceLabel(raw))
	}
	forgetDNSRoute(raw)
	if _, ok := recallDNSRoute(raw); ok {
		t.Fatal("a forgotten choice must not come back")
	}
	if DNSRouteChoiceLabel(raw) != "" {
		t.Fatal("an unknown upstream must report no choice")
	}
}

// The guard that stops the intercept from eating its own via-tunnel query. It is
// reference-counted because two upstreams can be in flight at once and the first
// to finish must not clear the other's port.
func TestCoreDNSPortGuardIsRefCounted(t *testing.T) {
	const port uint16 = 41234
	if isCoreDNSPort(port) {
		t.Fatal("port must start unregistered")
	}
	rememberCoreDNSPort(port)
	rememberCoreDNSPort(port)
	if !isCoreDNSPort(port) {
		t.Fatal("port must be registered")
	}
	forgetCoreDNSPort(port)
	if !isCoreDNSPort(port) {
		t.Fatal("one release must not clear a doubly-held port")
	}
	forgetCoreDNSPort(port)
	if isCoreDNSPort(port) {
		t.Fatal("the last release must clear the port")
	}
	// Port 0 is "no port"; registering it would make the guard swallow packets
	// it has no business swallowing.
	rememberCoreDNSPort(0)
	if isCoreDNSPort(0) {
		t.Fatal("port 0 must never register")
	}
}

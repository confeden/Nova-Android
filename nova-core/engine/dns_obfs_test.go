package nova

import (
	"net"
	"testing"
	"time"
)

func TestParseObfsRoute(t *testing.T) {
	spec, err := parseDNSUpstream("https://dns.dns-ai.ru/dns-query|194.87.196.13|via=obfs")
	if err != nil {
		t.Fatal(err)
	}
	if spec.route != dnsRouteObfsFirst {
		t.Fatalf("route = %v, want obfs-first", spec.route)
	}
	if len(spec.bootstrap) != 1 || spec.bootstrap[0] != "194.87.196.13" {
		t.Fatalf("bootstrap = %v", spec.bootstrap)
	}
	plain, err := parseDNSUpstream("1.1.1.1|via=obfs")
	if err != nil {
		t.Fatal(err)
	}
	if plain.route != dnsRouteDirect {
		t.Fatalf("plain UDP must fall back to direct, got %v", plain.route)
	}
}

func TestObfsRouteKeyDiffersFromDirect(t *testing.T) {
	if routeKey("x", dnsRouteDirectObfs) == routeKey("x", dnsRouteDirect) {
		t.Fatal("obfs and plain direct share a pooled connection")
	}
}

func TestObfsDirectSuspendAndReset(t *testing.T) {
	resetObfsDirectState()
	raw := "https://example.invalid/dns-query|via=obfs"
	spec, _ := parseDNSUpstream(raw)
	if !obfsDirectAllowed(raw, make([]byte, 12), spec, time.Second) {
		t.Fatal("fresh upstream must try direct first")
	}
	suspendObfsDirect(raw)
	if obfsDirectAllowed(raw, make([]byte, 12), spec, time.Second) {
		t.Fatal("suspended upstream must go to the tunnel without waiting")
	}
	if got := obfsDirectLabel(raw); got != "tunnel" {
		t.Fatalf("label = %q, want tunnel", got)
	}
	noteObfsDirectOK(raw)
	if got := obfsDirectLabel(raw); got != "direct-obfs" {
		t.Fatalf("label = %q, want direct-obfs", got)
	}
	resetObfsDirectState()
	if got := obfsDirectLabel(raw); got != "" {
		t.Fatalf("label after reset = %q", got)
	}
}

func TestObfuscateDNSConnSplitsClientHello(t *testing.T) {
	client, server := net.Pipe()
	defer client.Close()
	defer server.Close()
	wrapped := obfuscateDNSConn(client, dnsRouteDirectObfs)
	if _, ok := wrapped.(*fragmentedConn); !ok {
		t.Fatal("direct-obfs conn is not fragmented")
	}
	if obfuscateDNSConn(client, dnsRouteDirect) != client {
		t.Fatal("plain direct conn must stay untouched")
	}
	hello := make([]byte, 600)
	sizes := make(chan []int, 1)
	go func() {
		var got []int
		buf := make([]byte, 1024)
		total := 0
		for total < len(hello) {
			n, err := server.Read(buf)
			if err != nil {
				break
			}
			got = append(got, n)
			total += n
		}
		sizes <- got
	}()
	if _, err := wrapped.Write(hello); err != nil {
		t.Fatal(err)
	}
	got := <-sizes
	if len(got) < 3 || got[0] != 1 || got[1] != 255 {
		t.Fatalf("segments = %v, want 1,255,256,...", got)
	}
}

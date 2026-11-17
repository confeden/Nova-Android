package nova

import (
	"context"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestCloudflareTunnelRouteValidation(t *testing.T) {
	for _, raw := range []string{"", "socks5://127.0.0.1:1080", "socks5h://[::1]:1080", "http://127.0.0.1:8080"} {
		if _, err := cloudflareTunnelContext(raw); err != nil {
			t.Errorf("valid route %q: %v", raw, err)
		}
	}
	for _, raw := range []string{"https://127.0.0.1:8080", "http://example.com:8080", "http://127.0.0.1", "socks5://user:pass@127.0.0.1:1080", "http://127.0.0.1:8080/path"} {
		if _, err := cloudflareTunnelContext(raw); err == nil {
			t.Errorf("accepted unsafe route %q", raw)
		}
	}
}

func TestCloudflareTunnelHTTPProxyIsUsed(t *testing.T) {
	var requests atomic.Int32
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.URL.Host != "unresolved.invalid" {
			t.Errorf("proxy destination = %q", r.URL.Host)
		}
		w.WriteHeader(http.StatusNoContent)
	}))
	defer proxy.Close()
	proxyURL, _ := url.Parse(proxy.URL)
	transport := cloudflareTunnelTransport(cloudflareTunnelRoute{proxy: proxyURL})
	defer transport.CloseIdleConnections()
	client := &http.Client{Transport: transport, Timeout: time.Second}
	resp, err := client.Get("http://unresolved.invalid/")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNoContent || requests.Load() != 1 {
		t.Fatal("request did not use the specified proxy")
	}
}

func TestCloudflareTunnelProxyFailureDoesNotFallback(t *testing.T) {
	var requests atomic.Int32
	proxy := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		requests.Add(1)
		if r.Method != http.MethodConnect || r.Host != warpRegistrationHost+":443" {
			t.Errorf("unexpected tunnel request %s %s", r.Method, r.Host)
		}
		w.WriteHeader(http.StatusBadGateway)
	}))
	defer proxy.Close()
	ctx, err := cloudflareTunnelContext(proxy.URL)
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(ctx, time.Second)
	defer cancel()
	started := time.Now()
	_, err = doCloudflareAPIRequestWithContext(ctx, cloudflareAPIRequest{label: "test", method: http.MethodPatch, path: warpRegistrationPath + "/test"})
	if err == nil || !strings.Contains(err.Error(), "active tunnel") {
		t.Fatalf("want route error, got %v", err)
	}
	if requests.Load() != 1 || time.Since(started) >= time.Second {
		t.Fatal("proxy failure attempted DNS/direct fallback")
	}
}

func TestCloudflareTunnelContextDoesNotChangeOtherCalls(t *testing.T) {
	first, _ := cloudflareTunnelContext("socks5://127.0.0.1:1080")
	second, _ := cloudflareTunnelContext("http://127.0.0.1:8080")
	a := first.Value(cloudflareTunnelRouteKey{}).(cloudflareTunnelRoute)
	b := second.Value(cloudflareTunnelRouteKey{}).(cloudflareTunnelRoute)
	if a.proxy.Port() != "1080" || b.proxy.Port() != "8080" || context.Background().Value(cloudflareTunnelRouteKey{}) != nil {
		t.Fatal("enrollment route escaped its context")
	}
}

func TestCloudflareTunnelSOCKSResolvesAtProxy(t *testing.T) {
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer listener.Close()
	destination := make(chan string, 1)
	go func() {
		conn, err := listener.Accept()
		if err != nil {
			destination <- "accept failed"
			return
		}
		defer conn.Close()
		conn.SetDeadline(time.Now().Add(2 * time.Second))
		greeting := make([]byte, 3)
		if _, err := io.ReadFull(conn, greeting); err != nil {
			destination <- "greeting failed"
			return
		}
		conn.Write([]byte{5, 0})
		header := make([]byte, 5)
		if _, err := io.ReadFull(conn, header); err != nil || header[3] != 3 {
			destination <- "expected unresolved hostname"
			return
		}
		host := make([]byte, int(header[4])+2)
		if _, err := io.ReadFull(conn, host); err != nil {
			destination <- "hostname failed"
			return
		}
		destination <- string(host[:len(host)-2])
		conn.Write([]byte{5, 5, 0, 1, 0, 0, 0, 0, 0, 0})
	}()
	ctx, err := cloudflareTunnelContext("socks5://" + listener.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(ctx, 2*time.Second)
	defer cancel()
	_, err = doCloudflareAPIRequestWithContext(ctx, cloudflareAPIRequest{label: "test", method: http.MethodPatch, path: warpRegistrationPath + "/test"})
	if err == nil {
		t.Fatal("SOCKS rejection must propagate")
	}
	select {
	case host := <-destination:
		if host != warpRegistrationHost {
			t.Fatalf("SOCKS destination = %q", host)
		}
	case <-ctx.Done():
		t.Fatal("request did not reach SOCKS proxy")
	}
}

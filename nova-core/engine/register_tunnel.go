package nova

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// The context route is per enrollment, including activation and device rereads.
// A missing proxy means the caller has kept the app inside the system VPN.
type cloudflareTunnelRoute struct{ proxy *url.URL }
type cloudflareTunnelRouteKey struct{}

func cloudflareTunnelContext(raw string) (context.Context, error) {
	route := cloudflareTunnelRoute{}
	if raw = strings.TrimSpace(raw); raw != "" {
		proxy, err := url.Parse(raw)
		if err != nil {
			return nil, errors.New("invalid Cloudflare tunnel proxy URL")
		}
		ip := net.ParseIP(proxy.Hostname())
		if (proxy.Scheme != "http" && proxy.Scheme != "socks5" && proxy.Scheme != "socks5h") ||
			ip == nil || !ip.IsLoopback() || proxy.Port() == "" || proxy.User != nil ||
			(proxy.Path != "" && proxy.Path != "/") || proxy.RawQuery != "" || proxy.Fragment != "" {
			return nil, errors.New("Cloudflare tunnel proxy must be a loopback HTTP/SOCKS5 URL with a port")
		}
		if proxy.Scheme == "socks5h" {
			proxy.Scheme = "socks5"
		}
		route.proxy = proxy
	}
	return context.WithValue(context.Background(), cloudflareTunnelRouteKey{}, route), nil
}

func cloudflareTunnelTransport(route cloudflareTunnelRoute) *http.Transport {
	transport := &http.Transport{
		ForceAttemptHTTP2:     false,
		DisableKeepAlives:     true,
		DisableCompression:    true,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 12 * time.Second,
		ExpectContinueTimeout: time.Second,
	}
	if route.proxy != nil {
		transport.Proxy = http.ProxyURL(route.proxy)
	} else {
		// Go's Android resolver can have no DNS configuration. Keep TLS/SNI on
		// the API hostname while dialing the existing pinned bootstrap addresses.
		// These sockets are intentionally not protected: they must enter the VPN.
		transport.DialContext = func(ctx context.Context, network, address string) (net.Conn, error) {
			host, port, err := net.SplitHostPort(address)
			if err != nil || host != warpRegistrationHost || port != "443" {
				return nil, errors.New("unexpected Cloudflare tunnel destination")
			}
			var lastErr error
			for _, ip := range warpRegistrationHardcodedIPs {
				conn, err := (&net.Dialer{Timeout: 5 * time.Second}).DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
				if err == nil {
					return conn, nil
				}
				lastErr = err
				if ctx.Err() != nil {
					break
				}
			}
			return nil, fmt.Errorf("Cloudflare tunnel dial failed: %w", lastErr)
		}
	}
	return transport
}

func doCloudflareAPIRequestThroughTunnel(baseCtx context.Context, apiReq cloudflareAPIRequest, route cloudflareTunnelRoute) (*cloudflareAPIResponse, error) {
	transport := cloudflareTunnelTransport(route)
	defer transport.CloseIdleConnections()
	client := &http.Client{
		Transport:     transport,
		Timeout:       20 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	ctx, cancel := context.WithTimeout(baseCtx, 20*time.Second)
	defer cancel()
	req, err := newCloudflareAPIHTTPRequest(ctx, apiReq)
	if err != nil {
		return nil, fmt.Errorf("build tunnel request: %w", err)
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("Cloudflare API through active tunnel: %w", err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, 512*1024))
	if err != nil {
		return nil, fmt.Errorf("read tunnel response: %w", err)
	}
	return &cloudflareAPIResponse{StatusCode: resp.StatusCode, Status: resp.Status, Body: body, Via: "active-tunnel"}, nil
}

package nova

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"slices"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	utls "github.com/refraction-networking/utls"
)

const (
	warpRegistrationHost    = "api.cloudflareclient.com"
	warpRegistrationPath    = "/v0a4471/reg"
	warpRegistrationVersion = "a-6.35-4471"

	// Историческое значение по умолчанию. Приложение поднимает Opera-прокси на
	// случайном порту, поэтому реальный адрес приходит через
	// SetLocalCloudflareProxyAddress; константа остаётся запасным вариантом для
	// сборок, где адрес не задан.
	warpLocalProxyDefaultAddress = "127.0.0.1:1085"
)

// warpLocalProxyAddress хранит адрес локального прокси, через который ядро ходит
// в API Cloudflare. Раньше адрес был захардкожен, а приложение выбирало порт
// динамически — из-за расхождения проверка доступности прокси не срабатывала
// никогда, и регистрация MASQUE уходила напрямую, в ту самую фильтрацию по имени
// узла, из-за которой она и не проходит.
var warpLocalProxyAddress atomic.Value

// SetLocalCloudflareProxyAddress задаёт адрес локального HTTP-прокси в формате
// `host:port`. Пустая строка возвращает значение по умолчанию.
func SetLocalCloudflareProxyAddress(address string) {
	warpLocalProxyAddress.Store(strings.TrimSpace(address))
}

func localCloudflareProxyAddress() string {
	value, _ := warpLocalProxyAddress.Load().(string)
	if value == "" {
		return warpLocalProxyDefaultAddress
	}
	return value
}

func localCloudflareProxyURL() string {
	return "http://" + localCloudflareProxyAddress()
}

var (
	warpRegistrationDNS = []string{
		"111.88.96.50:53",
		"111.88.96.51:53",
		"1.1.1.1:53",
		"1.0.0.1:53",
		"8.8.8.8:53",
		"8.8.4.4:53",
	}
	warpRegistrationHardcodedIPs = []netip.Addr{
		netip.MustParseAddr("104.16.24.84"),
		netip.MustParseAddr("104.16.192.82"),
	}
	activeWarpRegisterCancelMu sync.Mutex
	activeWarpRegisterCancel   context.CancelFunc
)

type warpRegistrationRequest struct {
	Key       string `json:"key"`
	InstallID string `json:"install_id"`
	FcmToken  string `json:"fcm_token"`
	Tos       string `json:"tos"`
	Model     string `json:"model"`
	Serial    string `json:"serial_number"`
	OsVersion string `json:"os_version"`
	KeyType   string `json:"key_type"`
	TunType   string `json:"tunnel_type"`
	Locale    string `json:"locale"`
}

type registrationProfile struct {
	label            string
	helloID          utls.ClientHelloID
	splitPlan        []int
	fragmentSize     int
	fragmentBytes    int
	fragmentDelay    time.Duration
	handshakeTimeout time.Duration
}

type cloudflareAPIRequest struct {
	label     string
	method    string
	path      string
	body      []byte
	authToken string
}

type cloudflareAPIResponse struct {
	StatusCode int
	Status     string
	Body       []byte
	Via        string
}

// RegisterWarp performs WARP device registration over an obfuscated TLS path.
func RegisterWarp(publicKey string, locale string, model string) (string, error) {
	publicKey = strings.TrimSpace(publicKey)
	if publicKey == "" {
		return "", errors.New("public key is required")
	}
	if strings.TrimSpace(locale) == "" {
		locale = "en-US"
	}
	if strings.TrimSpace(model) == "" {
		model = "PC"
	}

	serial, err := randomAndroidSerial()
	if err != nil {
		return "", fmt.Errorf("failed to generate Android serial: %w", err)
	}

	reqBody, err := json.Marshal(warpRegistrationRequest{
		Key:       publicKey,
		InstallID: "",
		FcmToken:  "",
		Tos:       cloudflareTime(time.Now()),
		Model:     model,
		Serial:    serial,
		OsVersion: "",
		KeyType:   "curve25519",
		TunType:   "wireguard",
		Locale:    locale,
	})
	if err != nil {
		return "", fmt.Errorf("failed to encode registration request: %w", err)
	}

	baseCtx, cancel := context.WithCancel(context.Background())
	activeWarpRegisterCancelMu.Lock()
	activeWarpRegisterCancel = cancel
	activeWarpRegisterCancelMu.Unlock()
	defer func() {
		cancel()
		activeWarpRegisterCancelMu.Lock()
		activeWarpRegisterCancel = nil
		activeWarpRegisterCancelMu.Unlock()
	}()

	resp, err := doCloudflareAPIRequestWithContext(baseCtx, cloudflareAPIRequest{
		label:  "warp-register",
		method: http.MethodPost,
		path:   warpRegistrationPath,
		body:   reqBody,
	})
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("registration failed: %s body=%s", resp.Status, truncateForLog(string(resp.Body), 200))
	}
	if len(resp.Body) == 0 {
		return "", errors.New("empty response body")
	}

	log.Printf("WARP registration: success via %s", resp.Via)
	return string(resp.Body), nil
}

func CancelRegisterWarp() {
	activeWarpRegisterCancelMu.Lock()
	cancel := activeWarpRegisterCancel
	activeWarpRegisterCancel = nil
	activeWarpRegisterCancelMu.Unlock()

	if cancel != nil {
		cancel()
	}
}

func doCloudflareAPIRequest(req cloudflareAPIRequest) (*cloudflareAPIResponse, error) {
	return doCloudflareAPIRequestWithContext(context.Background(), req)
}

func doCloudflareAPIRequestWithContext(baseCtx context.Context, req cloudflareAPIRequest) (*cloudflareAPIResponse, error) {
	targetIPs := resolveWarpRegistrationIPs()
	profiles := defaultCloudflareProfiles()
	var (
		failures      []string
		firstResponse *cloudflareAPIResponse
	)
	proxyAvailable := hasLocalCloudflareProxy()
	preferLocalProxy := proxyAvailable && shouldPreferLocalProxyForCloudflareRequest(req)

	tryLocalProxy := func() (*cloudflareAPIResponse, bool) {
		if !proxyAvailable {
			return nil, false
		}
		log.Printf("Cloudflare API %s: trying local proxy %s", req.label, localCloudflareProxyURL())
		resp, err := doCloudflareAPIRequestViaProxy(baseCtx, req)
		if err != nil {
			failures = append(failures, "local-proxy: "+err.Error())
			log.Printf("Cloudflare API %s: local proxy failed: %v", req.label, err)
			return nil, false
		}
		log.Printf("Cloudflare API %s: HTTP %s via local proxy", req.label, resp.Status)
		if resp.StatusCode == http.StatusOK {
			return resp, true
		}
		if firstResponse == nil {
			firstResponse = resp
		}
		return nil, false
	}

	if preferLocalProxy {
		if resp, ok := tryLocalProxy(); ok {
			return resp, nil
		}
	}

	for _, ip := range targetIPs {
		for _, profile := range profiles {
			if err := baseCtx.Err(); err != nil {
				return nil, err
			}
			log.Printf("Cloudflare API %s: trying %s via %s", req.label, ip, profile.label)
			resp, err := doCloudflareAPIRequestViaIP(baseCtx, ip, req, profile)
			if err != nil {
				failures = append(failures, fmt.Sprintf("%s/%s: %v", ip, profile.label, err))
				log.Printf("Cloudflare API %s: transport failed via %s (%s): %v", req.label, ip, profile.label, err)
				continue
			}

			log.Printf("Cloudflare API %s: HTTP %s via %s (%s)", req.label, resp.Status, ip, profile.label)
			if resp.StatusCode == http.StatusOK {
				return resp, nil
			}
			if firstResponse == nil {
				firstResponse = resp
			}
		}
	}

	if proxyAvailable && !preferLocalProxy {
		if err := baseCtx.Err(); err != nil {
			return nil, err
		}
		if resp, ok := tryLocalProxy(); ok {
			return resp, nil
		}
	}

	if firstResponse != nil {
		return firstResponse, nil
	}

	if len(failures) > 8 {
		failures = failures[:8]
	}
	return nil, fmt.Errorf("all Cloudflare API attempts failed: %s", strings.Join(failures, " | "))
}

func shouldPreferLocalProxyForCloudflareRequest(req cloudflareAPIRequest) bool {
	label := strings.ToLower(strings.TrimSpace(req.label))
	return strings.Contains(label, "masque")
}

func defaultCloudflareProfiles() []registrationProfile {
	return []registrationProfile{
		{
			label:            "android-okhttp-multisplit-512",
			helloID:          utls.HelloAndroid_11_OkHttp,
			splitPlan:        []int{1, 255, 256},
			fragmentBytes:    512,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 9 * time.Second,
		},
		{
			label:            "android-okhttp-multisplit-664",
			helloID:          utls.HelloAndroid_11_OkHttp,
			splitPlan:        []int{1, 663},
			fragmentBytes:    664,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 9 * time.Second,
		},
		{
			label:            "chrome-multisplit-681",
			helloID:          utls.HelloChrome_Auto,
			splitPlan:        []int{1, 680},
			fragmentBytes:    681,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 9 * time.Second,
		},
		{
			label:            "chrome-multisplit-540",
			helloID:          utls.HelloChrome_Auto,
			splitPlan:        []int{1, 269, 270},
			fragmentBytes:    540,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 9 * time.Second,
		},
		{
			label:            "firefox-multisplit-681",
			helloID:          utls.HelloFirefox_Auto,
			splitPlan:        []int{1, 680},
			fragmentBytes:    681,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 9 * time.Second,
		},
		{
			label:            "firefox-multisplit-540",
			helloID:          utls.HelloFirefox_Auto,
			splitPlan:        []int{1, 269, 270},
			fragmentBytes:    540,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 9 * time.Second,
		},
		{
			label:            "randomized-noalpn-multisplit-664",
			helloID:          utls.HelloRandomizedNoALPN,
			splitPlan:        []int{1, 663},
			fragmentBytes:    664,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 9 * time.Second,
		},
		{
			label:            "randomized-noalpn-multisplit-540",
			helloID:          utls.HelloRandomizedNoALPN,
			splitPlan:        []int{1, 269, 270},
			fragmentBytes:    540,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 9 * time.Second,
		},
		{
			label:            "android-okhttp-split-16",
			helloID:          utls.HelloAndroid_11_OkHttp,
			fragmentSize:     16,
			fragmentBytes:    640,
			fragmentDelay:    5 * time.Millisecond,
			handshakeTimeout: 8 * time.Second,
		},
		{
			label:            "android-okhttp-split-32",
			helloID:          utls.HelloAndroid_11_OkHttp,
			fragmentSize:     32,
			fragmentBytes:    768,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 8 * time.Second,
		},
		{
			label:            "chrome-split-32",
			helloID:          utls.HelloChrome_Auto,
			fragmentSize:     32,
			fragmentBytes:    896,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 8 * time.Second,
		},
		{
			label:            "firefox-split-24",
			helloID:          utls.HelloFirefox_Auto,
			fragmentSize:     24,
			fragmentBytes:    768,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 8 * time.Second,
		},
		{
			label:            "randomized-noalpn-split-24",
			helloID:          utls.HelloRandomizedNoALPN,
			fragmentSize:     24,
			fragmentBytes:    768,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 8 * time.Second,
		},
		{
			label:            "chrome-split-16",
			helloID:          utls.HelloChrome_Auto,
			fragmentSize:     16,
			fragmentBytes:    640,
			fragmentDelay:    4 * time.Millisecond,
			handshakeTimeout: 8 * time.Second,
		},
		{
			label:            "android-okhttp-split-24",
			helloID:          utls.HelloAndroid_11_OkHttp,
			fragmentSize:     24,
			fragmentBytes:    736,
			fragmentDelay:    5 * time.Millisecond,
			handshakeTimeout: 6 * time.Second,
		},
	}
}

func doCloudflareAPIRequestViaIP(baseCtx context.Context, ip netip.Addr, apiReq cloudflareAPIRequest, profile registrationProfile) (*cloudflareAPIResponse, error) {
	ctx, cancel := context.WithTimeout(baseCtx, profile.handshakeTimeout+6*time.Second)
	defer cancel()

	dialer := &net.Dialer{Timeout: 5 * time.Second}
	rawConn, err := dialer.DialContext(ctx, "tcp4", net.JoinHostPort(ip.String(), "443"))
	if err != nil {
		return nil, fmt.Errorf("tcp dial failed: %w", err)
	}
	defer rawConn.Close()
	go func(conn net.Conn, done context.Context) {
		<-done.Done()
		_ = conn.Close()
	}(rawConn, ctx)

	if tcpConn, ok := rawConn.(*net.TCPConn); ok {
		_ = tcpConn.SetNoDelay(true)
	}

	conn := rawConn
	if len(profile.splitPlan) > 0 || (profile.fragmentSize > 0 && profile.fragmentBytes > 0) {
		conn = &fragmentedConn{
			Conn:          rawConn,
			splitPlan:     slices.Clone(profile.splitPlan),
			fragmentSize:  profile.fragmentSize,
			fragmentBytes: profile.fragmentBytes,
			delay:         profile.fragmentDelay,
		}
	}

	tlsConn := utls.UClient(conn, &utls.Config{
		ServerName: warpRegistrationHost,
		NextProtos: []string{"http/1.1"},
		MinVersion: utls.VersionTLS12,
		MaxVersion: utls.VersionTLS13,
	}, profile.helloID)
	if err := tlsConn.SetDeadline(time.Now().Add(profile.handshakeTimeout)); err != nil {
		return nil, fmt.Errorf("set deadline failed: %w", err)
	}
	if err := tlsConn.Handshake(); err != nil {
		return nil, fmt.Errorf("tls handshake failed: %w", err)
	}
	_ = tlsConn.SetDeadline(time.Time{})

	req, err := newCloudflareAPIHTTPRequest(ctx, apiReq)
	if err != nil {
		return nil, fmt.Errorf("build request failed: %w", err)
	}
	req.Host = warpRegistrationHost

	if err := tlsConn.SetDeadline(time.Now().Add(10 * time.Second)); err != nil {
		return nil, fmt.Errorf("set request deadline failed: %w", err)
	}
	if err := req.Write(tlsConn); err != nil {
		return nil, fmt.Errorf("write request failed: %w", err)
	}

	resp, err := http.ReadResponse(bufio.NewReader(tlsConn), req)
	if err != nil {
		return nil, fmt.Errorf("read response failed: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(io.LimitReader(resp.Body, 512*1024))
	if err != nil {
		return nil, fmt.Errorf("read body failed: %w", err)
	}

	return &cloudflareAPIResponse{
		StatusCode: resp.StatusCode,
		Status:     resp.Status,
		Body:       respBody,
		Via:        ip.String() + "/" + profile.label,
	}, nil
}

func doCloudflareAPIRequestViaProxy(baseCtx context.Context, apiReq cloudflareAPIRequest) (*cloudflareAPIResponse, error) {
	proxyURL, err := url.Parse(localCloudflareProxyURL())
	if err != nil {
		return nil, fmt.Errorf("parse proxy url failed: %w", err)
	}

	transport := &http.Transport{
		Proxy:                 http.ProxyURL(proxyURL),
		ForceAttemptHTTP2:     false,
		DisableKeepAlives:     true,
		DisableCompression:    true,
		TLSHandshakeTimeout:   10 * time.Second,
		ResponseHeaderTimeout: 12 * time.Second,
		ExpectContinueTimeout: 1 * time.Second,
	}
	defer transport.CloseIdleConnections()

	client := &http.Client{
		Transport: transport,
		Timeout:   16 * time.Second,
	}

	ctx, cancel := context.WithTimeout(baseCtx, 16*time.Second)
	defer cancel()

	req, err := newCloudflareAPIHTTPRequest(ctx, apiReq)
	if err != nil {
		return nil, fmt.Errorf("build request failed: %w", err)
	}

	resp, err := client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("proxy request failed: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(io.LimitReader(resp.Body, 512*1024))
	if err != nil {
		return nil, fmt.Errorf("proxy body read failed: %w", err)
	}

	return &cloudflareAPIResponse{
		StatusCode: resp.StatusCode,
		Status:     resp.Status,
		Body:       respBody,
		Via:        "local-http-proxy",
	}, nil
}

func newCloudflareAPIHTTPRequest(ctx context.Context, apiReq cloudflareAPIRequest) (*http.Request, error) {
	method := strings.ToUpper(strings.TrimSpace(apiReq.method))
	if method == "" {
		method = http.MethodPost
	}
	path := strings.TrimSpace(apiReq.path)
	if path == "" {
		path = warpRegistrationPath
	}
	if !strings.HasPrefix(path, "/") {
		path = "/" + path
	}

	req, err := http.NewRequestWithContext(
		ctx,
		method,
		"https://"+warpRegistrationHost+path,
		bytes.NewReader(apiReq.body),
	)
	if err != nil {
		return nil, err
	}

	req.Header.Set("User-Agent", "WARP for Android")
	req.Header.Set("CF-Client-Version", warpRegistrationVersion)
	req.Header.Set("Content-Type", "application/json; charset=UTF-8")
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Accept-Encoding", "identity")
	req.Header.Set("Connection", "close")
	if strings.TrimSpace(apiReq.authToken) != "" {
		req.Header.Set("Authorization", "Bearer "+strings.TrimSpace(apiReq.authToken))
	}
	return req, nil
}

func hasLocalCloudflareProxy() bool {
	conn, err := net.DialTimeout("tcp", localCloudflareProxyAddress(), 400*time.Millisecond)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}

func resolveWarpRegistrationIPs() []netip.Addr {
	ordered := make([]netip.Addr, 0, len(warpRegistrationHardcodedIPs)+4)
	seen := make(map[netip.Addr]struct{})

	add := func(ip netip.Addr) {
		if !ip.IsValid() || !ip.Is4() {
			return
		}
		if !isKnownWarpRegistrationIP(ip) {
			log.Printf("WARP registration: ignoring unexpected bootstrap IP %s", ip)
			return
		}
		if _, ok := seen[ip]; ok {
			return
		}
		seen[ip] = struct{}{}
		ordered = append(ordered, ip)
	}

	for _, server := range warpRegistrationDNS {
		ctx, cancel := context.WithTimeout(context.Background(), 2500*time.Millisecond)
		resolver := &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network string, address string) (net.Conn, error) {
				return (&net.Dialer{}).DialContext(ctx, "udp", server)
			},
		}
		ips, err := resolver.LookupIP(ctx, "ip4", warpRegistrationHost)
		cancel()
		if err != nil {
			continue
		}
		for _, ip := range ips {
			if addr, ok := netip.AddrFromSlice(ip); ok {
				add(addr.Unmap())
			}
		}
	}

	for _, ip := range warpRegistrationHardcodedIPs {
		add(ip)
	}

	slices.SortFunc(ordered, func(a, b netip.Addr) int {
		return strings.Compare(a.String(), b.String())
	})
	return ordered
}

func isKnownWarpRegistrationIP(ip netip.Addr) bool {
	normalized := ip.String()
	return normalized == "104.16.24.84" || normalized == "104.16.192.82"
}

func truncateForLog(value string, limit int) string {
	value = strings.TrimSpace(value)
	if len(value) <= limit {
		return value
	}
	return value[:limit] + "..."
}

func randomAndroidSerial() (string, error) {
	buf := make([]byte, 8)
	if _, err := io.ReadFull(rand.Reader, buf); err != nil {
		return "", err
	}
	const hex = "0123456789abcdef"
	out := make([]byte, 16)
	for i, b := range buf {
		out[i*2] = hex[b>>4]
		out[i*2+1] = hex[b&0x0f]
	}
	return string(out), nil
}

func cloudflareTime(now time.Time) string {
	return now.UTC().Format("2006-01-02T15:04:05.000-07:00")
}

// fragmentedConn splits the first part of the TLS ClientHello into small writes.
type fragmentedConn struct {
	net.Conn
	splitPlan     []int
	fragmentSize  int
	fragmentBytes int
	delay         time.Duration
}

func (c *fragmentedConn) Write(p []byte) (int, error) {
	if c.fragmentBytes <= 0 || len(p) == 0 {
		return c.Conn.Write(p)
	}

	total := 0
	limit := len(p)
	if limit > c.fragmentBytes {
		limit = c.fragmentBytes
	}

	if len(c.splitPlan) > 0 {
		for len(c.splitPlan) > 0 && total < limit {
			nextChunk := c.splitPlan[0]
			c.splitPlan = c.splitPlan[1:]
			if nextChunk <= 0 {
				continue
			}
			end := total + nextChunk
			if end > limit {
				end = limit
			}
			n, err := c.Conn.Write(p[total:end])
			total += n
			if err != nil {
				c.fragmentBytes -= total
				if c.fragmentBytes < 0 {
					c.fragmentBytes = 0
				}
				return total, err
			}
			if total < limit && c.delay > 0 {
				time.Sleep(c.delay)
			}
		}
	}

	if c.fragmentSize <= 0 {
		c.fragmentSize = limit
	}

	for total < limit {
		end := total + c.fragmentSize
		if end > limit {
			end = limit
		}
		n, err := c.Conn.Write(p[total:end])
		total += n
		if err != nil {
			c.fragmentBytes -= total
			if c.fragmentBytes < 0 {
				c.fragmentBytes = 0
			}
			return total, err
		}
		if total < limit && c.delay > 0 {
			time.Sleep(c.delay)
		}
	}

	c.fragmentBytes -= total
	if c.fragmentBytes < 0 {
		c.fragmentBytes = 0
	}

	if total == len(p) {
		return total, nil
	}

	n, err := c.Conn.Write(p[total:])
	return total + n, err
}

// SetWarpLicense привязывает лицензию WARP+ к устройству.
//
// Бесплатная анонимная регистрация выходит с `account_type: "free"`, и служба MASQUE её
// не обслуживает: соединение принимается, а на запрос туннеля не отвечает — позже и вовсе
// закрывается алертом `tls: access denied`. Лицензия меняет тип аккаунта, поэтому её надо
// уметь задать, не переустанавливая приложение.
//
// Возвращает тип аккаунта, каким его видит сервер после запроса, — по нему видно, приняли
// ключ или нет. «HTTP 200 OK» тут недостаточно: на неизвестное поле Cloudflare отвечает
// тем же кодом и молча ничего не меняет.
func SetWarpLicense(accessToken string, deviceID string, license string) (string, error) {
	accessToken = strings.TrimSpace(accessToken)
	deviceID = strings.TrimSpace(deviceID)
	license = strings.TrimSpace(license)
	if accessToken == "" || deviceID == "" {
		return "", errors.New("license update requires a WARP access token and device id")
	}
	if license == "" {
		return "", errors.New("license key is empty")
	}

	body, err := json.Marshal(map[string]string{"license": license})
	if err != nil {
		return "", err
	}

	resp, err := doCloudflareAPIRequest(cloudflareAPIRequest{
		label:     "warp-license",
		method:    http.MethodPut,
		path:      warpRegistrationPath + "/" + deviceID + "/account",
		body:      body,
		authToken: accessToken,
	})
	if err != nil {
		return "", err
	}
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("%s: %s", resp.Status, truncateForLog(string(resp.Body), 200))
	}

	var account struct {
		AccountType string `json:"account_type"`
		License     string `json:"license"`
		Premium     int    `json:"premium_data"`
		Quota       int64  `json:"quota"`
	}
	if err := json.Unmarshal(resp.Body, &account); err != nil {
		return "", fmt.Errorf("failed to decode license response: %w", err)
	}

	accountType := strings.TrimSpace(account.AccountType)
	log.Printf(
		"WARP лицензия принята: account_type=%q quota=%d premium=%d",
		accountType, account.Quota, account.Premium,
	)
	if accountType == "" {
		accountType = "unknown"
	}
	return accountType, nil
}

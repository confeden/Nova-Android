package nova

import (
	"bufio"
	"context"
	"crypto/aes"
	"crypto/cipher"
	cryptorand "crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/netip"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	wgtun "github.com/amnezia-vpn/amneziawg-go/tun"
	wgnetstack "github.com/amnezia-vpn/amneziawg-go/tun/netstack"
	utls "github.com/refraction-networking/utls"

	"nova-core/cfws"
	"nova-core/tlsshape"
)

// SetTelegramWsSignatureSecret задаёт секрет подписи WSS для собственных
// поддоменов nova-app.eu. Пустая строка оставляет чистый `binary`.
func SetTelegramWsSignatureSecret(secret string) {
	cfws.SetSecret(secret)
}

type telegramTransparentProfile string

const (
	telegramTransparentOff    telegramTransparentProfile = "off"
	telegramTransparentWifi   telegramTransparentProfile = "wifi"
	telegramTransparentMobile telegramTransparentProfile = "mobile"
)

type telegramTransparentConfig struct {
	enabled bool
	profile telegramTransparentProfile
}

type telegramTransparentRouteKind string

const (
	telegramTransparentRouteDirect telegramTransparentRouteKind = "direct"
	telegramTransparentRouteCF     telegramTransparentRouteKind = "cf"
)

type pooledTransparentWS struct {
	ws        *transparentRawWebSocket
	route     string
	createdAt time.Time
}

type telegramTransparentWsPool struct {
	mu        sync.Mutex
	idle      map[string][]pooledTransparentWS
	refilling map[string]bool
}

var telegramTransparentConfigState atomic.Value
var telegramTransparentInterceptLogBudget atomic.Int64
var telegramTransparentInitPreviewBudget atomic.Int64
var telegramTransparentRouteErrorBudget atomic.Int64
var telegramTransparentPacketDebugBudget atomic.Int64
var telegramTransparentMediaDebugBudget atomic.Int64
var telegramTransparentAcceptDebugBudget atomic.Int64
var telegramTransparentBypassLogBudget atomic.Int64

// telegramTransparentOwnZoneLogBudget — сколько раз объяснять, что маршрут через
// свою зону не поднялся.
//
// Отход на чужой пул был молчаливым: `cfpool:` называет выбранный домен, но не
// говорит, что своих в списке не осталось. Из-за этого пустой журнал читался как
// «подстановка SNI прижилась», хотя своя зона не участвовала ни разу. Бюджет
// маленький: строка нужна как факт, а не как поток.
var telegramTransparentOwnZoneLogBudget atomic.Int64
var transparentDirectRouteMu sync.RWMutex
var telegramTransparentRouteCooldownMu sync.RWMutex
var telegramTransparentFlowBypassMu sync.RWMutex
var transparentDirectDomainByTarget = make(map[string]string)
var transparentDirectDCByTarget = make(map[string]int)
var telegramTransparentRouteCooldowns = make(map[string]time.Time)
var telegramTransparentFlowBypassUntil = make(map[string]time.Time)
var telegramTransparentPool = telegramTransparentWsPool{
	idle:      make(map[string][]pooledTransparentWS),
	refilling: make(map[string]bool),
}

func init() {
	telegramTransparentConfigState.Store(telegramTransparentConfig{
		enabled: false,
		profile: telegramTransparentOff,
	})
	telegramTransparentInterceptLogBudget.Store(24)
	telegramTransparentInitPreviewBudget.Store(8)
	telegramTransparentRouteErrorBudget.Store(64)
	telegramTransparentPacketDebugBudget.Store(32)
	telegramTransparentMediaDebugBudget.Store(96)
	telegramTransparentAcceptDebugBudget.Store(48)
	telegramTransparentBypassLogBudget.Store(32)
	telegramTransparentOwnZoneLogBudget.Store(8)
}

func SetTelegramTransparentProxyConfig(enabled bool, profile string) {
	normalizedProfile := normalizeTelegramTransparentProfile(profile)
	if !enabled || normalizedProfile == telegramTransparentOff {
		enabled = false
		normalizedProfile = telegramTransparentOff
	}
	telegramTransparentConfigState.Store(telegramTransparentConfig{
		enabled: enabled,
		profile: normalizedProfile,
	})
	telegramTransparentInterceptLogBudget.Store(24)
	telegramTransparentInitPreviewBudget.Store(8)
	telegramTransparentRouteErrorBudget.Store(64)
	telegramTransparentPacketDebugBudget.Store(48)
	telegramTransparentMediaDebugBudget.Store(128)
	telegramTransparentAcceptDebugBudget.Store(64)
	telegramTransparentBypassLogBudget.Store(48)
	telegramTransparentOwnZoneLogBudget.Store(8)
	log.Printf(
		"Telegram transparent relay config updated: enabled=%v profile=%s",
		enabled,
		normalizedProfile,
	)
}

func getTelegramTransparentProxyConfig() telegramTransparentConfig {
	stored, _ := telegramTransparentConfigState.Load().(telegramTransparentConfig)
	return stored
}

func normalizeTelegramTransparentProfile(profile string) telegramTransparentProfile {
	switch strings.TrimSpace(strings.ToLower(profile)) {
	case "mobile", "cell", "cellular":
		return telegramTransparentMobile
	case "wifi", "wlan":
		return telegramTransparentWifi
	default:
		return telegramTransparentOff
	}
}

func telegramTransparentPoolKey(kind telegramTransparentRouteKind, dc int, isMedia bool) string {
	if isMedia {
		return fmt.Sprintf("%s:%dm", kind, dc)
	}
	return fmt.Sprintf("%s:%d", kind, dc)
}

func telegramTransparentDesiredPoolSize(profile telegramTransparentProfile) int {
	switch profile {
	case telegramTransparentMobile:
		return 8
	case telegramTransparentWifi:
		return 4
	default:
		return 0
	}
}

func telegramTransparentPoolMaxAge(profile telegramTransparentProfile) time.Duration {
	switch profile {
	case telegramTransparentMobile:
		return 90 * time.Second
	case telegramTransparentWifi:
		return 60 * time.Second
	default:
		return 45 * time.Second
	}
}

func telegramTransparentWarmupPool(profile telegramTransparentProfile) {
	if profile == telegramTransparentOff {
		return
	}
	warmupDCs := []int{1, 5, 2, 4}
	switch profile {
	case telegramTransparentMobile:
		for _, dc := range warmupDCs {
			telegramTransparentPoolScheduleRefill(telegramTransparentRouteCF, dc, false, profile, 2)
			telegramTransparentPoolScheduleRefill(telegramTransparentRouteCF, dc, true, profile, 1)
			telegramTransparentPoolScheduleRefill(telegramTransparentRouteDirect, dc, false, profile, 1)
			telegramTransparentPoolScheduleRefill(telegramTransparentRouteDirect, dc, true, profile, 1)
		}
	default:
		for _, dc := range warmupDCs {
			telegramTransparentPoolScheduleRefill(telegramTransparentRouteDirect, dc, false, profile, 2)
			telegramTransparentPoolScheduleRefill(telegramTransparentRouteDirect, dc, true, profile, 1)
			telegramTransparentPoolScheduleRefill(telegramTransparentRouteCF, dc, false, profile, 1)
			telegramTransparentPoolScheduleRefill(telegramTransparentRouteCF, dc, true, profile, 1)
		}
	}
	if telegramTransparentInterceptLogBudget.Add(-1) >= 0 {
		log.Printf(
			"Telegram transparent relay WS pool warmup scheduled: profile=%s size=%d dcs=%v",
			profile,
			telegramTransparentDesiredPoolSize(profile),
			warmupDCs,
		)
	}
}

func telegramTransparentPoolGet(
	kind telegramTransparentRouteKind,
	dc int,
	isMedia bool,
	profile telegramTransparentProfile,
) (*transparentRawWebSocket, string) {
	key := telegramTransparentPoolKey(kind, dc, isMedia)
	maxAge := telegramTransparentPoolMaxAge(profile)
	desiredSize := telegramTransparentDesiredPoolSize(profile)
	now := time.Now()

	telegramTransparentPool.mu.Lock()
	bucket := telegramTransparentPool.idle[key]
	for len(bucket) > 0 {
		last := bucket[len(bucket)-1]
		bucket = bucket[:len(bucket)-1]
		if last.ws == nil || last.ws.closed.Load() || now.Sub(last.createdAt) > maxAge {
			if last.ws != nil {
				last.ws.Close()
			}
			continue
		}
		telegramTransparentPool.idle[key] = bucket
		remaining := len(bucket)
		telegramTransparentPool.mu.Unlock()
		if desiredSize > 0 && remaining < desiredSize {
			telegramTransparentPoolScheduleRefill(kind, dc, isMedia, profile, 0)
		}
		return last.ws, last.route
	}
	telegramTransparentPool.idle[key] = bucket
	telegramTransparentPool.mu.Unlock()
	if desiredSize > 0 {
		telegramTransparentPoolScheduleRefill(kind, dc, isMedia, profile, 0)
	}
	return nil, ""
}

func telegramTransparentRouteCooldownKey(scope string, value string) string {
	return scope + ":" + strings.TrimSpace(strings.ToLower(value))
}

func telegramTransparentRouteCooling(key string, now time.Time) bool {
	if key == "" {
		return false
	}
	telegramTransparentRouteCooldownMu.RLock()
	until, ok := telegramTransparentRouteCooldowns[key]
	telegramTransparentRouteCooldownMu.RUnlock()
	if !ok {
		return false
	}
	if now.Before(until) {
		return true
	}
	telegramTransparentRouteCooldownMu.Lock()
	if currentUntil, exists := telegramTransparentRouteCooldowns[key]; exists && !now.Before(currentUntil) {
		delete(telegramTransparentRouteCooldowns, key)
	}
	telegramTransparentRouteCooldownMu.Unlock()
	return false
}

func telegramTransparentRouteRememberFailure(key string, duration time.Duration) {
	if key == "" {
		return
	}
	telegramTransparentRouteCooldownMu.Lock()
	telegramTransparentRouteCooldowns[key] = time.Now().Add(duration)
	telegramTransparentRouteCooldownMu.Unlock()
}

func telegramTransparentRouteRememberSuccess(key string) {
	if key == "" {
		return
	}
	telegramTransparentRouteCooldownMu.Lock()
	delete(telegramTransparentRouteCooldowns, key)
	telegramTransparentRouteCooldownMu.Unlock()
}

func telegramTransparentPoolScheduleRefill(
	kind telegramTransparentRouteKind,
	dc int,
	isMedia bool,
	profile telegramTransparentProfile,
	minFill int,
) {
	desiredSize := telegramTransparentDesiredPoolSize(profile)
	if desiredSize <= 0 || dc <= 0 {
		return
	}
	targetFill := desiredSize
	if minFill > targetFill {
		targetFill = minFill
	}
	key := telegramTransparentPoolKey(kind, dc, isMedia)

	telegramTransparentPool.mu.Lock()
	current := len(telegramTransparentPool.idle[key])
	if current >= targetFill || telegramTransparentPool.refilling[key] {
		telegramTransparentPool.mu.Unlock()
		return
	}
	telegramTransparentPool.refilling[key] = true
	telegramTransparentPool.mu.Unlock()

	go func() {
		defer func() {
			telegramTransparentPool.mu.Lock()
			delete(telegramTransparentPool.refilling, key)
			telegramTransparentPool.mu.Unlock()
		}()

		for {
			telegramTransparentPool.mu.Lock()
			current := len(telegramTransparentPool.idle[key])
			telegramTransparentPool.mu.Unlock()
			if current >= targetFill {
				return
			}

			ws, route := connectTelegramTransparentRoute(kind, dc, isMedia)
			if ws == nil || route == "" {
				return
			}

			telegramTransparentPool.mu.Lock()
			telegramTransparentPool.idle[key] = append(telegramTransparentPool.idle[key], pooledTransparentWS{
				ws:        ws,
				route:     route,
				createdAt: time.Now(),
			})
			telegramTransparentPool.mu.Unlock()
		}
	}()
}

func connectTelegramTransparentRoute(
	kind telegramTransparentRouteKind,
	dc int,
	isMedia bool,
) (*transparentRawWebSocket, string) {
	switch kind {
	case telegramTransparentRouteCF:
		return connectTransparentCF(dc, isMedia)
	case telegramTransparentRouteDirect:
		return connectTransparentCanonicalDC(dc, isMedia)
	default:
		return nil, ""
	}
}

func telegramTransparentPoolReset() {
	telegramTransparentPool.mu.Lock()
	defer telegramTransparentPool.mu.Unlock()
	for key, bucket := range telegramTransparentPool.idle {
		for _, item := range bucket {
			if item.ws != nil {
				item.ws.Close()
			}
		}
		delete(telegramTransparentPool.idle, key)
	}
	telegramTransparentPool.refilling = make(map[string]bool)
}

type telegramTransparentInitInfo struct {
	proto   uint32
	dc      int
	isMedia bool
}

type telegramTransparentProxy struct {
	file          *osFileAdapter
	mtu           int
	dev           wgtun.Device
	net           *wgnetstack.Net
	profile       atomic.Value
	closeOnce     sync.Once
	startErr      error
	mu            sync.Mutex
	listeners     []net.Listener
	flowListeners map[string]net.Listener
	ctx           context.Context
	cancel        context.CancelFunc
	wg            sync.WaitGroup
}

type osFileAdapter struct {
	fileMu sync.Mutex
	file   *os.File
}

func (a *osFileAdapter) Write(packet []byte) error {
	a.fileMu.Lock()
	defer a.fileMu.Unlock()
	if a.file == nil {
		return os.ErrClosed
	}
	_, err := a.file.Write(packet)
	return err
}

func (a *osFileAdapter) Close() error {
	a.fileMu.Lock()
	defer a.fileMu.Unlock()
	a.file = nil
	return nil
}

func newTelegramTransparentProxy(file *os.File, mtu int) (*telegramTransparentProxy, error) {
	baseAddr := netip.MustParseAddr("10.202.0.1")
	baseAddrV6 := netip.MustParseAddr("fd00:202::1")
	dev, netStack, err := wgnetstack.CreateNetTUN([]netip.Addr{baseAddr, baseAddrV6}, nil, intMax(1280, mtu))
	if err != nil {
		return nil, err
	}

	ctx, cancel := context.WithCancel(context.Background())
	proxy := &telegramTransparentProxy{
		file:          &osFileAdapter{file: file},
		mtu:           intMax(1280, mtu),
		dev:           dev,
		net:           netStack,
		flowListeners: make(map[string]net.Listener),
		ctx:           ctx,
		cancel:        cancel,
	}
	proxy.profile.Store(getTelegramTransparentProxyConfig().profile)

	proxy.wg.Add(1)
	go proxy.pumpPacketsToAndroidTun()

	log.Printf("Telegram transparent relay started: listeners=%d mtu=%d", len(proxy.listeners), proxy.mtu)
	telegramTransparentWarmupPool(getTelegramTransparentProxyConfig().profile)
	return proxy, nil
}

func (p *telegramTransparentProxy) setProfile(profile telegramTransparentProfile) {
	p.profile.Store(profile)
}

func (p *telegramTransparentProxy) maybeHandle(packet []byte) (bool, error) {
	cfg := getTelegramTransparentProxyConfig()
	p.setProfile(cfg.profile)
	if !cfg.enabled || cfg.profile == telegramTransparentOff {
		return false, nil
	}
	flow, ok := parseTelegramTransparentFlow(packet)
	if !ok {
		return false, nil
	}
	if shouldBypassTelegramTransparentFlow(flow) {
		if isTelegramTransparentSyn(packet) && telegramTransparentBypassLogBudget.Add(-1) >= 0 {
			log.Printf(
				"Telegram transparent relay bypassed raw flow: dst=%s:%d dc=%d media=%v",
				flow.dst,
				flow.port,
				transparentTargetDCHint(flow.dst.String()),
				transparentLikelyMediaTarget(flow.dst.String(), int(flow.port), transparentTargetDCHint(flow.dst.String())),
			)
		}
		return false, nil
	}
	if err := p.net.EnsureLocalAddress(flow.dst); err != nil {
		return false, err
	}
	if err := p.ensureFlowListener(flow); err != nil {
		return false, err
	}
	if isTelegramTransparentSyn(packet) && telegramTransparentInterceptLogBudget.Add(-1) >= 0 {
		log.Printf(
			"Telegram transparent relay intercepted SYN: dst=%s:%d ipver=%d",
			flow.dst,
			flow.port,
			packet[0]>>4,
		)
	}
	packetCopy := append([]byte(nil), packet...)
	if _, err := p.dev.Write([][]byte{packetCopy}, 0); err != nil {
		return false, err
	}
	return true, nil
}

func debugTelegramTransparentCandidate(packet []byte, stage string) {
	if !isTelegramTransparentSyn(packet) {
		return
	}
	flow, ok := parseTelegramTransparentFlow(packet)
	if !ok {
		return
	}
	isPriorityTarget := transparentDebugPriorityTarget(flow.dst.String(), flow.port)
	if isPriorityTarget {
		if telegramTransparentMediaDebugBudget.Add(-1) < 0 {
			return
		}
	} else if telegramTransparentPacketDebugBudget.Add(-1) < 0 {
		return
	}
	cfg := getTelegramTransparentProxyConfig()
	log.Printf(
		"Telegram transparent relay candidate %s: enabled=%v profile=%s dst=%s:%d dc=%d media=%v ipver=%d",
		stage,
		cfg.enabled,
		cfg.profile,
		flow.dst,
		flow.port,
		transparentTargetDCHint(flow.dst.String()),
		transparentLikelyMediaTarget(flow.dst.String(), int(flow.port), transparentTargetDCHint(flow.dst.String())),
		packet[0]>>4,
	)
}

func transparentDebugPriorityTarget(targetIP string, targetPort uint16) bool {
	targetIP = strings.TrimSpace(strings.ToLower(targetIP))
	if targetPort != 443 && targetPort != 80 && targetPort != 5222 {
		return false
	}
	switch targetIP {
	case "5.28.195.2", "149.154.167.91", "149.154.167.92", "149.154.167.255":
		return true
	default:
		return false
	}
}

func shouldBypassTelegramTransparentFlow(flow telegramTransparentFlow) bool {
	targetIP := flow.dst.String()
	targetPort := int(flow.port)
	if targetPort == 443 {
		if targetIP == "149.154.167.99" || targetIP == "149.154.167.98" || targetIP == "2001:67c:4e8:f004::9" {
			return true // Bypass telegram.org so it uses standard WARP tunnel
		}
	}
	if telegramTransparentFlowBypassCooling(targetIP, targetPort, time.Now()) {
		return true
	}
	dcHint := transparentTargetDCHint(targetIP)
	if transparentLikelyMediaTarget(targetIP, targetPort, dcHint) {
		if shouldProxyTelegramMediaTLS(targetIP, targetPort) {
			return false
		}
		// Telegram media/CDN flows on these endpoints are commonly plain TLS,
		// not obfuscated MTProto. Intercepting them just drops the first TLS
		// connection before the bypass cache is learned, which looks like
		// periodic "Connecting..." in Telegram. Keep them on the normal VPN path.
		return true
	}
	return dcHint <= 0 && (targetPort == 80 || targetPort == 5222)
}

func telegramTransparentFlowBypassKey(targetIP string, targetPort int) string {
	targetIP = strings.ToLower(strings.TrimSpace(targetIP))
	if targetIP == "" || targetPort <= 0 {
		return ""
	}
	return targetIP + ":" + strconv.Itoa(targetPort)
}

func rememberTelegramTransparentFlowBypass(targetIP string, targetPort int, duration time.Duration, reason string) {
	key := telegramTransparentFlowBypassKey(targetIP, targetPort)
	if key == "" || duration <= 0 {
		return
	}
	telegramTransparentFlowBypassMu.Lock()
	telegramTransparentFlowBypassUntil[key] = time.Now().Add(duration)
	telegramTransparentFlowBypassMu.Unlock()
	if telegramTransparentBypassLogBudget.Add(-1) >= 0 {
		log.Printf(
			"Telegram transparent relay bypass cached: target=%s duration=%s reason=%s",
			key,
			duration.Round(time.Second),
			reason,
		)
	}
}

func telegramTransparentFlowBypassCooling(targetIP string, targetPort int, now time.Time) bool {
	key := telegramTransparentFlowBypassKey(targetIP, targetPort)
	if key == "" {
		return false
	}
	telegramTransparentFlowBypassMu.RLock()
	until, exists := telegramTransparentFlowBypassUntil[key]
	telegramTransparentFlowBypassMu.RUnlock()
	if !exists {
		return false
	}
	if now.Before(until) {
		return true
	}
	telegramTransparentFlowBypassMu.Lock()
	if currentUntil, stillExists := telegramTransparentFlowBypassUntil[key]; stillExists && !now.Before(currentUntil) {
		delete(telegramTransparentFlowBypassUntil, key)
	}
	telegramTransparentFlowBypassMu.Unlock()
	return false
}

func (p *telegramTransparentProxy) ensureFlowListener(flow telegramTransparentFlow) error {
	key := netip.AddrPortFrom(flow.dst, flow.port).String()
	p.mu.Lock()
	defer p.mu.Unlock()
	if _, exists := p.flowListeners[key]; exists {
		return nil
	}
	listener, err := p.net.ListenTCPAddrPort(netip.AddrPortFrom(flow.dst, flow.port))
	if err != nil {
		return err
	}
	p.flowListeners[key] = listener
	p.listeners = append(p.listeners, listener)
	p.wg.Add(1)
	go p.acceptLoop(listener)
	if telegramTransparentInterceptLogBudget.Add(-1) >= 0 {
		log.Printf("Telegram transparent relay opened listener: %s", key)
	}
	return nil
}

func (p *telegramTransparentProxy) close() {
	p.closeOnce.Do(func() {
		p.cancel()
		telegramTransparentPoolReset()
		for _, listener := range p.listeners {
			_ = listener.Close()
		}
		if p.dev != nil {
			_ = p.dev.Close()
		}
		_ = p.file.Close()
		done := make(chan struct{})
		go func() {
			defer close(done)
			p.wg.Wait()
		}()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
		}
		log.Printf("Telegram transparent relay stopped")
	})
}

func (p *telegramTransparentProxy) pumpPacketsToAndroidTun() {
	defer p.wg.Done()
	buff := make([][]byte, 1)
	buff[0] = make([]byte, p.mtu+256)
	sizes := make([]int, 1)
	for {
		select {
		case <-p.ctx.Done():
			return
		default:
		}
		n, err := p.dev.Read(buff, sizes, 0)
		if err != nil {
			if errors.Is(err, os.ErrClosed) || errors.Is(err, net.ErrClosed) {
				return
			}
			select {
			case <-p.ctx.Done():
				return
			default:
				log.Printf("Telegram transparent relay read failed: %v", err)
				time.Sleep(25 * time.Millisecond)
				continue
			}
		}
		if n <= 0 || sizes[0] <= 0 {
			continue
		}
		packet := append([]byte(nil), buff[0][:sizes[0]]...)
		if err := p.file.Write(packet); err != nil {
			if errors.Is(err, os.ErrClosed) {
				return
			}
			log.Printf("Telegram transparent relay write-to-android failed: %v", err)
			return
		}
	}
}

func (p *telegramTransparentProxy) acceptLoop(listener net.Listener) {
	defer p.wg.Done()
	for {
		conn, err := listener.Accept()
		if err != nil {
			select {
			case <-p.ctx.Done():
				return
			default:
				if ne, ok := err.(net.Error); ok && ne.Temporary() {
					time.Sleep(50 * time.Millisecond)
					continue
				}
				return
			}
		}
		p.wg.Add(1)
		go func() {
			defer p.wg.Done()
			p.handleConn(conn)
		}()
	}
}

func (p *telegramTransparentProxy) handleConn(conn net.Conn) {
	defer safeCloseConn(conn)

	localAddr, ok := conn.LocalAddr().(*net.TCPAddr)
	if !ok || localAddr == nil || localAddr.IP == nil {
		return
	}
	targetIP := strings.TrimSpace(localAddr.IP.String())
	targetPort := localAddr.Port
	if targetIP == "" || targetPort <= 0 {
		return
	}
	if telegramTransparentAcceptDebugBudget.Add(-1) >= 0 {
		log.Printf(
			"Telegram transparent relay accepted local=%s remote=%s",
			conn.LocalAddr(),
			conn.RemoteAddr(),
		)
	}

	initPacket := make([]byte, 64)
	_ = conn.SetReadDeadline(time.Now().Add(12 * time.Second))
	if _, err := io.ReadFull(conn, initPacket); err != nil {
		log.Printf("Telegram transparent relay init read failed for %s:%d: %v", targetIP, targetPort, err)
		return
	}
	_ = conn.SetReadDeadline(time.Time{})

	initInfo, ok := transparentInitInfoFromPacket(initPacket)
	if !ok {
		if shouldProxyTelegramMediaTLS(targetIP, targetPort) {
			if telegramTransparentMediaDebugBudget.Add(-1) >= 0 {
				log.Printf(
					"Telegram transparent relay media TLS fallback for %s:%d via %s:%d",
					targetIP,
					targetPort,
					transparentPreferredTCPUpstream(targetIP),
					targetPort,
				)
			}
			transparentTCPFallback(p.ctx, conn, targetIP, targetPort, initPacket)
			return
		}

		if telegramTransparentInitPreviewBudget.Add(-1) >= 0 {
			log.Printf(
				"Telegram transparent relay bypass learned for %s:%d: init is not obfuscated MTProto, head=%x",
				targetIP,
				targetPort,
				initPacket[:16],
			)
		} else {
			log.Printf("Telegram transparent relay bypass learned for %s:%d: init is not obfuscated MTProto", targetIP, targetPort)
		}
		rememberTelegramTransparentFlowBypass(targetIP, targetPort, 90*time.Second, "non-mtproto-init")
		return
	}

	profile, _ := p.profile.Load().(telegramTransparentProfile)
	if profile == telegramTransparentOff {
		profile = telegramTransparentWifi
	}

	dcHint := initInfo.dc
	if dcHint <= 0 {
		dcHint = transparentTargetDCHint(targetIP)
	}
	isMedia := initInfo.isMedia
	if !isMedia {
		isMedia = transparentLikelyMediaTarget(targetIP, targetPort, dcHint)
	}

	ws, routeLabel := p.connectViaProfile(targetIP, targetPort, dcHint, isMedia, profile)
	if ws == nil {
		log.Printf("Telegram transparent relay WSS failed via %s, bypassing next retries for %s:%d", routeLabel, targetIP, targetPort)
		rememberTelegramTransparentFlowBypass(
			targetIP,
			targetPort,
			telegramTransparentRouteFailureBypassDuration(dcHint, isMedia, 60*time.Second),
			"wss-route-failed",
		)
		return
	}
	defer ws.Close()

	splitter, splitterErr := newTransparentMsgSplitter(initPacket, initInfo.proto)
	if splitterErr != nil && telegramTransparentRouteErrorBudget.Add(-1) >= 0 {
		log.Printf(
			"Telegram transparent relay splitter disabled for %s:%d via %s: %v",
			targetIP,
			targetPort,
			routeLabel,
			splitterErr,
		)
		splitter = nil
	}

	sendErr := ws.Send(initPacket)
	if sendErr != nil {
		ws.Close()
		if retryWs, retryRouteLabel := p.connectViaProfileFresh(targetIP, targetPort, dcHint, isMedia, profile); retryWs != nil {
			ws = retryWs
			routeLabel = retryRouteLabel
			sendErr = ws.Send(initPacket)
		}
	}
	if sendErr != nil {
		log.Printf("Telegram transparent relay send init failed via %s: %v", routeLabel, sendErr)
		rememberTelegramTransparentFlowBypass(
			targetIP,
			targetPort,
			telegramTransparentRouteFailureBypassDuration(dcHint, isMedia, 45*time.Second),
			"wss-send-failed",
		)
		return
	}

	log.Printf(
		"Telegram transparent relay connected: proto=%s profile=%s dc=%d media=%v route=%s target=%s:%d",
		transparentProtoLabel(initInfo.proto),
		profile,
		dcHint,
		isMedia,
		routeLabel,
		targetIP,
		targetPort,
	)
	bridgeTransparentWS(p.ctx, conn, ws, splitter)
}

func (p *telegramTransparentProxy) connectViaProfile(
	targetIP string,
	targetPort int,
	dcHint int,
	isMedia bool,
	profile telegramTransparentProfile,
) (*transparentRawWebSocket, string) {
	return p.connectViaProfileInternal(targetIP, targetPort, dcHint, isMedia, profile, true)
}

func (p *telegramTransparentProxy) connectViaProfileFresh(
	targetIP string,
	targetPort int,
	dcHint int,
	isMedia bool,
	profile telegramTransparentProfile,
) (*transparentRawWebSocket, string) {
	return p.connectViaProfileInternal(targetIP, targetPort, dcHint, isMedia, profile, false)
}

func (p *telegramTransparentProxy) connectViaProfileInternal(
	targetIP string,
	targetPort int,
	dcHint int,
	isMedia bool,
	profile telegramTransparentProfile,
	allowPool bool,
) (*transparentRawWebSocket, string) {
	if dcHint <= 0 {
		dcHint = transparentTargetDCHint(targetIP)
	}
	preferCFFirst := shouldPreferTelegramTransparentCFFirst(dcHint, isMedia, profile)
	switch profile {
	case telegramTransparentMobile:
		if dcHint > 0 {
			if allowPool {
				if ws, route := telegramTransparentPoolGet(telegramTransparentRouteCF, dcHint, isMedia, profile); ws != nil {
					return ws, "cfpool:" + route
				}
			}
			if ws, domain := connectTransparentCF(dcHint, isMedia); ws != nil {
				telegramTransparentPoolScheduleRefill(telegramTransparentRouteCF, dcHint, isMedia, profile, 0)
				return ws, "cf:" + domain
			}
		}
		if dcHint > 0 && allowPool {
			if ws, route := telegramTransparentPoolGet(telegramTransparentRouteDirect, dcHint, isMedia, profile); ws != nil {
				return ws, "directpool:" + route
			}
		}
		if ws, domain := connectTransparentDirectQuick(targetIP, dcHint, isMedia); ws != nil {
			if dcHint > 0 {
				telegramTransparentPoolScheduleRefill(telegramTransparentRouteDirect, dcHint, isMedia, profile, 0)
			}
			return ws, "direct:" + domain
		}
	default:
		if preferCFFirst && dcHint > 0 {
			if allowPool {
				if ws, route := telegramTransparentPoolGet(telegramTransparentRouteCF, dcHint, isMedia, profile); ws != nil {
					return ws, "cfpool:" + route
				}
			}
			if ws, domain := connectTransparentCF(dcHint, isMedia); ws != nil {
				telegramTransparentPoolScheduleRefill(telegramTransparentRouteCF, dcHint, isMedia, profile, 0)
				return ws, "cf:" + domain
			}
		}
		if dcHint > 0 && allowPool {
			if ws, route := telegramTransparentPoolGet(telegramTransparentRouteDirect, dcHint, isMedia, profile); ws != nil {
				return ws, "directpool:" + route
			}
		}
		if ws, domain := connectTransparentDirectQuick(targetIP, dcHint, isMedia); ws != nil {
			if dcHint > 0 {
				telegramTransparentPoolScheduleRefill(telegramTransparentRouteDirect, dcHint, isMedia, profile, 0)
			}
			return ws, "direct:" + domain
		}
		if dcHint > 0 {
			if preferCFFirst {
				return nil, "none"
			}
			if allowPool {
				if ws, route := telegramTransparentPoolGet(telegramTransparentRouteCF, dcHint, isMedia, profile); ws != nil {
					return ws, "cfpool:" + route
				}
			}
			if ws, domain := connectTransparentCF(dcHint, isMedia); ws != nil {
				telegramTransparentPoolScheduleRefill(telegramTransparentRouteCF, dcHint, isMedia, profile, 0)
				return ws, "cf:" + domain
			}
		}
	}
	return nil, "none"
}

func shouldPreferTelegramTransparentCFFirst(
	dcHint int,
	isMedia bool,
	profile telegramTransparentProfile,
) bool {
	if profile == telegramTransparentMobile {
		return true
	}
	if dcHint <= 0 {
		return false
	}
	if isMedia {
		return true
	}
	switch dcHint {
	case 4, 5:
		return true
	default:
		return false
	}
}

type telegramTransparentFlow struct {
	dst  netip.Addr
	port uint16
}

func parseTelegramTransparentFlow(packet []byte) (telegramTransparentFlow, bool) {
	if len(packet) < 40 {
		return telegramTransparentFlow{}, false
	}
	switch packet[0] >> 4 {
	case 4:
		return parseTelegramTransparentFlowIPv4(packet)
	case 6:
		return parseTelegramTransparentFlowIPv6(packet)
	default:
		return telegramTransparentFlow{}, false
	}
}

func parseTelegramTransparentFlowIPv4(packet []byte) (telegramTransparentFlow, bool) {
	ihl := int(packet[0]&0x0f) * 4
	if ihl < 20 || len(packet) < ihl+20 {
		return telegramTransparentFlow{}, false
	}
	if packet[9] != 6 {
		return telegramTransparentFlow{}, false
	}
	if binary.BigEndian.Uint16(packet[6:8])&0x1fff != 0 {
		return telegramTransparentFlow{}, false
	}
	dst := netip.AddrFrom4([4]byte{packet[16], packet[17], packet[18], packet[19]})
	dstPort := binary.BigEndian.Uint16(packet[ihl+2 : ihl+4])
	if isTelegramPortAlwaysCaptured(dstPort) {
		return telegramTransparentFlow{dst: dst, port: dstPort}, true
	}
	if isTelegramIPv4(dst) && isTelegramTransparentPort(dstPort) {
		return telegramTransparentFlow{dst: dst, port: dstPort}, true
	}
	return telegramTransparentFlow{}, false
}

func parseTelegramTransparentFlowIPv6(packet []byte) (telegramTransparentFlow, bool) {
	if len(packet) < 60 {
		return telegramTransparentFlow{}, false
	}
	if packet[6] != 6 {
		return telegramTransparentFlow{}, false
	}
	dst, ok := netip.AddrFromSlice(packet[24:40])
	if !ok {
		return telegramTransparentFlow{}, false
	}
	dstPort := binary.BigEndian.Uint16(packet[42:44])
	if isTelegramPortAlwaysCaptured(dstPort) {
		return telegramTransparentFlow{dst: dst, port: dstPort}, true
	}
	if isTelegramIPv6(dst) && isTelegramTransparentPort(dstPort) {
		return telegramTransparentFlow{dst: dst, port: dstPort}, true
	}
	return telegramTransparentFlow{}, false
}

func isTelegramTransparentSyn(packet []byte) bool {
	if len(packet) < 40 {
		return false
	}
	switch packet[0] >> 4 {
	case 4:
		ihl := int(packet[0]&0x0f) * 4
		if ihl < 20 || len(packet) < ihl+14 {
			return false
		}
		flags := packet[ihl+13]
		return flags&0x02 != 0 && flags&0x10 == 0
	case 6:
		if len(packet) < 54 || packet[6] != 6 {
			return false
		}
		flags := packet[53]
		return flags&0x02 != 0 && flags&0x10 == 0
	default:
		return false
	}
}

type telegramIPv4Range struct {
	lo uint32
	hi uint32
}

type telegramTransparentTargetInfo struct {
	dc      int
	isMedia bool
}

var telegramIPv4Ranges = []telegramIPv4Range{
	{lo: mustIPv4ToUint32("5.28.195.0"), hi: mustIPv4ToUint32("5.28.195.255")},
	{lo: mustIPv4ToUint32("185.76.151.0"), hi: mustIPv4ToUint32("185.76.151.255")},
	{lo: mustIPv4ToUint32("149.154.160.0"), hi: mustIPv4ToUint32("149.154.175.255")},
	{lo: mustIPv4ToUint32("91.105.192.0"), hi: mustIPv4ToUint32("91.105.193.255")},
	{lo: mustIPv4ToUint32("91.108.0.0"), hi: mustIPv4ToUint32("91.108.255.255")},
	// Telegram CDN for APK updates
	{lo: mustIPv4ToUint32("194.221.250.0"), hi: mustIPv4ToUint32("194.221.250.255")},
}

var telegramTransparentExactTargets = map[string]telegramTransparentTargetInfo{
	// DC1
	"149.154.175.50": {dc: 1, isMedia: false},
	"149.154.175.51": {dc: 1, isMedia: false},
	"149.154.175.53": {dc: 1, isMedia: false},
	"149.154.175.54": {dc: 1, isMedia: false},
	"149.154.175.52": {dc: 1, isMedia: true},
	// DC2
	"149.154.167.35":  {dc: 2, isMedia: false},
	"149.154.167.36":  {dc: 2, isMedia: false},
	"149.154.167.41":  {dc: 2, isMedia: false},
	"149.154.167.50":  {dc: 2, isMedia: false},
	"149.154.167.51":  {dc: 2, isMedia: false},
	"149.154.167.220": {dc: 2, isMedia: false},
	"95.161.76.100":   {dc: 2, isMedia: false},
	"149.154.162.123": {dc: 2, isMedia: true},
	"149.154.167.151": {dc: 2, isMedia: true},
	"149.154.167.222": {dc: 2, isMedia: true},
	"149.154.167.223": {dc: 2, isMedia: true},
	// DC3
	"149.154.175.100": {dc: 3, isMedia: false},
	"149.154.175.101": {dc: 3, isMedia: false},
	"149.154.175.102": {dc: 3, isMedia: true},
	// DC4
	"149.154.164.250": {dc: 4, isMedia: true},
	"149.154.165.111": {dc: 4, isMedia: true},
	"149.154.166.120": {dc: 4, isMedia: true},
	"149.154.166.121": {dc: 4, isMedia: true},
	"149.154.167.91":  {dc: 4, isMedia: false},
	"149.154.167.92":  {dc: 4, isMedia: false},
	"149.154.167.118": {dc: 4, isMedia: true},
	// DC5
	"91.108.56.100": {dc: 5, isMedia: false},
	"91.108.56.101": {dc: 5, isMedia: false},
	"91.108.56.102": {dc: 5, isMedia: true},
	"91.108.56.116": {dc: 5, isMedia: false},
	"91.108.56.126": {dc: 5, isMedia: false},
	"91.108.56.128": {dc: 5, isMedia: true},
	"91.108.56.151": {dc: 5, isMedia: true},
	"149.154.171.5": {dc: 5, isMedia: false},
	// DC203
	"91.105.192.100": {dc: 203, isMedia: false},
	// Telegram CDN (APK updates)
	"194.221.250.50": {dc: 4, isMedia: true},
}

var telegramIPv6Prefixes = []netip.Prefix{
	mustParseTelegramPrefix("2001:067c:04e8:f000::/52"),
	mustParseTelegramPrefix("2001:0b28:f23d:f000::/52"),
	mustParseTelegramPrefix("2001:0b28:f23f:f000::/52"),
}

func mustParseTelegramPrefix(value string) netip.Prefix {
	return netip.MustParsePrefix(value)
}

func mustIPv4ToUint32(value string) uint32 {
	addr := netip.MustParseAddr(value)
	return binary.BigEndian.Uint32(addr.AsSlice())
}

func isTelegramIPv4(addr netip.Addr) bool {
	if !addr.Is4() {
		return false
	}
	value := binary.BigEndian.Uint32(addr.AsSlice())
	for _, r := range telegramIPv4Ranges {
		if value >= r.lo && value <= r.hi {
			return true
		}
	}
	return false
}

func isTelegramIPv6(addr netip.Addr) bool {
	if !addr.Is6() {
		return false
	}
	for _, prefix := range telegramIPv6Prefixes {
		if prefix.Contains(addr) {
			return true
		}
	}
	return false
}

func lookupTelegramTransparentTargetInfo(targetIP string) (telegramTransparentTargetInfo, bool) {
	targetIP = strings.TrimSpace(strings.ToLower(targetIP))
	info, ok := telegramTransparentExactTargets[targetIP]
	return info, ok
}

func telegramTransparentPorts() []uint16 {
	ports := []uint16{80, 443, 5222}
	for port := uint16(7300); port <= 7310; port++ {
		ports = append(ports, port)
	}
	return ports
}

func isTelegramTransparentPort(port uint16) bool {
	if port == 80 || port == 443 || port == 5222 {
		return true
	}
	return port >= 7300 && port <= 7310
}

func isTelegramPortAlwaysCaptured(port uint16) bool {
	return port >= 7300 && port <= 7310
}

func protectedDialer(timeout time.Duration) *net.Dialer {
	dialer := &net.Dialer{Timeout: timeout}
	if GlobalProtector == nil {
		return dialer
	}
	dialer.Control = func(network, address string, rawConn syscall.RawConn) error {
		var protectErr error
		if err := rawConn.Control(func(fd uintptr) {
			if !GlobalProtector(int(fd)) {
				protectErr = errors.New("protect returned false")
			}
		}); err != nil {
			return err
		}
		return protectErr
	}
	return dialer
}

func setTcpNoDelay(conn net.Conn) {
	tcpConn, ok := conn.(*net.TCPConn)
	if !ok {
		return
	}
	_ = tcpConn.SetNoDelay(true)
	_ = tcpConn.SetKeepAlive(true)
	_ = tcpConn.SetKeepAlivePeriod(30 * time.Second)
}

func connectTransparentDirectQuick(targetIP string, dcHint int, isMedia bool) (*transparentRawWebSocket, string) {
	targetIP = strings.TrimSpace(targetIP)
	if targetIP == "" {
		return nil, ""
	}
	if dcHint <= 0 {
		dcHint = transparentTargetDCHint(targetIP)
	}
	dialTarget := transparentPreferredWSTarget(targetIP, dcHint, isMedia)
	if cached := loadTransparentDirectDomain(targetIP, isMedia); cached != "" {
		if ws, domain := raceTransparentDomainsWithTimeout(dialTarget, []string{cached}, 650*time.Millisecond); ws != nil {
			return ws, transparentWSRouteLabel(domain, dialTarget, targetIP)
		}
	}
	if dcHint > 0 {
		if telegramTransparentInterceptLogBudget.Add(-1) >= 0 {
			log.Printf(
				"Telegram transparent relay trying quick WS route: target=%s dial=%s dc=%d media=%v",
				targetIP,
				dialTarget,
				dcHint,
				isMedia,
			)
		}
		candidates := make([]string, 0, 6)
		candidates = append(candidates, transparentOfficialDomains(dcHint)...)
		candidates = append(candidates, transparentWSDomains(dcHint, isMedia)...)
		if ws, domain := raceTransparentDomainsWithTimeout(dialTarget, candidates, 750*time.Millisecond); ws != nil {
			route := transparentWSRouteLabel(domain, dialTarget, targetIP)
			storeTransparentDirectRoute(targetIP, isMedia, route)
			return ws, route
		}
	}
	return nil, ""
}

type transparentRawWebSocket struct {
	conn      net.Conn
	bufReader *bufio.Reader
	writeMu   sync.Mutex
	closed    atomic.Bool
}

func wsConnectTransparent(ip string, domain string, path string, timeout time.Duration) (*transparentRawWebSocket, error) {
	path = normalizeTransparentWSPath(path)
	timeout = normalizeTransparentWSTimeout(timeout)

	tlsConn, neutralSNIUsed, err := dialTransparentTLSEndpoint(ip, domain, timeout)
	if err != nil {
		return nil, err
	}
	reader, err := upgradeTransparentWebSocket(tlsConn, domain, path, timeout)
	if err != nil {
		_ = tlsConn.Close()
		if neutralSNIUsed && upgradeRefusedTheName(err) {
			noteNeutralSNIRejected(domain, err.Error())
		}
		return nil, err
	}
	return &transparentRawWebSocket{conn: tlsConn, bufReader: reader}, nil
}

func normalizeTransparentWSPath(path string) string {
	trimmed := strings.TrimSpace(path)
	if trimmed == "" {
		return "/apiws"
	}
	if !strings.HasPrefix(trimmed, "/") {
		return "/" + trimmed
	}
	return trimmed
}

func normalizeTransparentWSTimeout(timeout time.Duration) time.Duration {
	if timeout <= 0 {
		return 5 * time.Second
	}
	return timeout
}

// dialTransparentTLSEndpoint поднимает TLS к endpoint'у и сообщает, ушло ли в
// SNI подставленное имя: от этого зависит, кого винить в последующем отказе.
//
// Маршрут едет в заголовке Host, который пишет upgradeTransparentWebSocket, —
// имя домена здесь нужно только как ключ подстановки. См. cfws.NeutralSNI.
func dialTransparentTLSEndpoint(ip string, domain string, timeout time.Duration) (*utls.UConn, bool, error) {
	rawConn, err := protectedDialer(timeout).Dial("tcp", net.JoinHostPort(ip, "443"))
	if err != nil {
		return nil, false, err
	}
	setTcpNoDelay(rawConn)

	sni, neutralSNIUsed := cfws.NeutralSNI(domain)
	profile := tlsshape.Current()
	// Кэш сессий не заводим намеренно: возобновление добавляет к hello
	// расширение PSK, то есть меняет ту самую форму, которую мы здесь задаём, а
	// выигрыш почти нулевой — пул держит WebSocket открытыми, и новые
	// рукопожатия случаются редко.
	config := &utls.Config{
		InsecureSkipVerify: true,
		ServerName:         sni,
	}
	tlsConn, err := newTransparentUTLSClient(rawConn, config, profile)
	if err != nil {
		_ = rawConn.Close()
		return nil, neutralSNIUsed, err
	}
	_ = tlsConn.SetDeadline(time.Now().Add(timeout))
	if err := tlsConn.Handshake(); err != nil {
		_ = tlsConn.Close()
		if handshakeWentUnanswered(err) {
			noteTelegramHandshakeIgnored(domain, profile, neutralSNIUsed, err)
		}
		return nil, neutralSNIUsed, err
	}
	_ = tlsConn.SetDeadline(time.Time{})
	logTransparentTLSShapeOnce(profile, sni, neutralSNIUsed, tlsConn.ConnectionState())
	return tlsConn, neutralSNIUsed, nil
}

// newTransparentUTLSClient собирает клиента с формой hello профиля.
//
// Форма задаётся спецификацией, а не идентификатором пресета: только так можно
// свести ALPN к http/1.1, не переписывая остальные расширения (см.
// telegramTLSSpec). Если спецификацию собрать не удалось, берём пресет, у
// которого ALPN нет вовсе, — потерять маршрут Telegram из-за таблицы форм было
// бы несоразмерно.
func newTransparentUTLSClient(
	rawConn net.Conn,
	config *utls.Config,
	profile tlsshape.Profile,
) (*utls.UConn, error) {
	spec, err := tlsshape.Spec(profile.HelloID)
	if err != nil {
		log.Printf(
			"Telegram transparent relay: cannot build the %s ClientHello spec (%v). "+
				"Falling back to the %s preset.",
			profile.Label,
			err,
			tlsshape.FallbackLabel,
		)
		return utls.UClient(rawConn, config, tlsshape.FallbackHelloID), nil
	}
	tlsConn := utls.UClient(rawConn, config, utls.HelloCustom)
	if err := tlsConn.ApplyPreset(&spec); err != nil {
		// rawConn закрывает вызывающий: у него он и остаётся, пока клиент не
		// собран. Закрыть здесь значит закрыть его дважды.
		return nil, fmt.Errorf("apply %s ClientHello spec: %w", profile.Label, err)
	}
	return tlsConn, nil
}

// transparentTLSShapeLogged — первое удачное рукопожатие называет форму вслух.
//
// Без этой строки правка формы неотличима от её отсутствия: пока ничего не
// сломалось, журнал молчит, и проверить на устройстве, какой ClientHello ушёл
// в сеть, нечем.
var transparentTLSShapeLogged atomic.Bool

func logTransparentTLSShapeOnce(profile tlsshape.Profile, sni string, neutralSNIUsed bool, state utls.ConnectionState) {
	if !transparentTLSShapeLogged.CompareAndSwap(false, true) {
		return
	}
	log.Printf(
		"Telegram transparent relay: TLS shape=%s sni=%s (substituted=%v) "+
			"version=%#04x alpn=%q",
		profile.Label,
		sni,
		neutralSNIUsed,
		state.Version,
		state.NegotiatedProtocol,
	)
}

// noteTelegramHandshakeIgnored — реакция на единственную фазу, где под
// подозрением сразу оба наших рычага: подставленное имя и форма hello.
//
// Различить их по одной попытке нельзя, поэтому убираем оба: ходы дешёвые и
// обратимые, а следующая попытка уйдёт литеральным именем в другой форме. Со
// временем рычаги расходятся сами — имя возвращается через пятнадцать минут,
// форма меняется на каждом новом отказе.
func noteTelegramHandshakeIgnored(
	domain string,
	profile tlsshape.Profile,
	neutralSNIUsed bool,
	cause error,
) {
	if neutralSNIUsed {
		noteNeutralSNIRejected(domain, cause.Error())
	}
	if next := tlsshape.Rotate(); next != "" {
		log.Printf(
			"Telegram transparent relay: handshake ignored with the %s ClientHello "+
				"(%s: %v). Switching the TLS shape to %s.",
			profile.Label,
			domain,
			cause,
			next,
		)
	}
}

// handshakeWentUnanswered — единственное окно, в котором подставленное имя ещё
// под подозрением: TCP уже поднялся, ClientHello ушёл, ответа нет.
//
// Отказы до отправки hello видит Dial, а после завершённого рукопожатия имя
// уже принято — само рукопожатие и есть доказательство. Откатываться на них
// значило бы отдавать реальный выигрыш за постороннюю помеху.
func handshakeWentUnanswered(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) {
		return true
	}
	var netErr net.Error
	return errors.As(err, &netErr) && netErr.Timeout()
}

// upgradeRefusedTheName: 403 и 421 — то, что край CDN отвечает, когда не
// принимает имя, которое ему дали, для запрошенного хоста. Остальные статусы
// приходят уже из-за самого маршрута и к SNI отношения не имеют.
func upgradeRefusedTheName(err error) bool {
	var upgradeErr *transparentUpgradeError
	if !errors.As(err, &upgradeErr) {
		return false
	}
	return upgradeErr.statusCode == 403 || upgradeErr.statusCode == 421
}

// noteOwnZoneRouteFailure говорит вслух, что маршрут через нашу зону не поднялся.
//
// Своя зона — это подпись WSS и нейтральный SNI; без неё релей работает, но на
// чужих воркерах общего пула, где ни того ни другого нет. Отход штатный (план
// воркера бесплатный, 200 000 запросов в сутки, и квота кончается), но он должен
// быть виден: иначе «нет отказов» читается как «всё работает», хотя проверяемый
// путь просто не проходили.
func noteOwnZoneRouteFailure(domain string, cause error) {
	if !cfws.IsOwnedHost(domain) {
		return
	}
	if telegramTransparentOwnZoneLogBudget.Add(-1) < 0 {
		return
	}
	log.Printf(
		"Telegram transparent relay: own-zone route %s is unusable (%v). "+
			"Falling back to the shared pool — WSS signature and neutral SNI are not in play there.",
		domain,
		cause,
	)
}

func noteNeutralSNIRejected(domain string, reason string) {
	if cfws.NoteNeutralSNIRejected(domain) {
		log.Printf(
			"Telegram transparent relay: neutral SNI refused for %s (%s). "+
				"Falling back to the literal route name for 15m.",
			domain,
			reason,
		)
	}
}

func upgradeTransparentWebSocket(conn net.Conn, domain string, path string, timeout time.Duration) (*bufio.Reader, error) {
	wsKey, err := generateTransparentWSKey()
	if err != nil {
		return nil, err
	}
	if err := writeTransparentUpgradeRequest(conn, domain, path, wsKey, timeout); err != nil {
		return nil, err
	}
	reader := bufio.NewReaderSize(conn, 4096)
	if err := verifyTransparentUpgradeResponse(conn, reader, timeout); err != nil {
		return nil, err
	}
	return reader, nil
}

func generateTransparentWSKey() (string, error) {
	keyBytes := make([]byte, 16)
	if _, err := cryptorand.Read(keyBytes); err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(keyBytes), nil
}

func writeTransparentUpgradeRequest(conn net.Conn, domain string, path string, wsKey string, timeout time.Duration) error {
	var request strings.Builder
	request.Grow(256)
	request.WriteString("GET ")
	request.WriteString(path)
	request.WriteString(" HTTP/1.1\r\n")
	request.WriteString("Host: ")
	request.WriteString(domain)
	request.WriteString("\r\n")
	request.WriteString("Upgrade: websocket\r\n")
	request.WriteString("Connection: Upgrade\r\n")
	request.WriteString("Sec-WebSocket-Key: ")
	request.WriteString(wsKey)
	request.WriteString("\r\n")
	request.WriteString("Sec-WebSocket-Version: 13\r\n")
	request.WriteString("Sec-WebSocket-Protocol: ")
	request.WriteString(cfws.SubprotocolHeader(domain))
	request.WriteString("\r\n")
	request.WriteString("Origin: https://web.telegram.org\r\n")
	request.WriteString("User-Agent: Mozilla/5.0 (Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36\r\n")
	request.WriteString("\r\n")

	_ = conn.SetWriteDeadline(time.Now().Add(timeout))
	_, err := io.WriteString(conn, request.String())
	_ = conn.SetWriteDeadline(time.Time{})
	return err
}

func verifyTransparentUpgradeResponse(conn net.Conn, reader *bufio.Reader, timeout time.Duration) error {
	_ = conn.SetReadDeadline(time.Now().Add(timeout))
	statusLine, err := consumeTransparentUpgradeResponse(reader)
	_ = conn.SetReadDeadline(time.Time{})
	if err != nil {
		return err
	}
	if code := transparentUpgradeStatusCode(statusLine); code != 101 {
		return &transparentUpgradeError{statusCode: code, statusLine: statusLine}
	}
	return nil
}

// transparentUpgradeError несёт статус отказа наверх: по строке его пришлось бы
// разбирать заново, а решение об откате нейтрального SNI смотрит именно на
// код.
type transparentUpgradeError struct {
	statusCode int
	statusLine string
}

func (e *transparentUpgradeError) Error() string {
	return fmt.Sprintf("websocket upgrade failed: %s", e.statusLine)
}

func consumeTransparentUpgradeResponse(reader *bufio.Reader) (string, error) {
	statusLine, err := readTransparentHeaderLine(reader)
	if err != nil {
		return "", err
	}
	if statusLine == "" {
		return "", errors.New("empty websocket response")
	}
	for headerCount := 0; headerCount < 100; headerCount++ {
		line, err := readTransparentHeaderLine(reader)
		if err != nil {
			return "", err
		}
		if line == "" {
			return statusLine, nil
		}
	}
	return "", errors.New("too many websocket headers")
}

func readTransparentHeaderLine(reader *bufio.Reader) (string, error) {
	line, err := reader.ReadString('\n')
	if err != nil {
		return "", err
	}
	return strings.TrimRight(line, "\r\n"), nil
}

func transparentUpgradeStatusCode(statusLine string) int {
	parts := strings.Fields(statusLine)
	if len(parts) < 2 {
		return 0
	}
	code, _ := strconv.Atoi(parts[1])
	return code
}

func (ws *transparentRawWebSocket) Send(payload []byte) error {
	return ws.writeSingleFrame(0x2, payload)
}

func (ws *transparentRawWebSocket) SendBatch(parts [][]byte) error {
	if ws.closed.Load() {
		return net.ErrClosed
	}
	ws.writeMu.Lock()
	defer ws.writeMu.Unlock()
	for _, part := range parts {
		if err := ws.writeFrameLocked(0x2, part); err != nil {
			return err
		}
	}
	return nil
}

func (ws *transparentRawWebSocket) writeSingleFrame(opcode byte, payload []byte) error {
	if ws.closed.Load() {
		return net.ErrClosed
	}
	ws.writeMu.Lock()
	defer ws.writeMu.Unlock()
	return ws.writeFrameLocked(opcode, payload)
}

func (ws *transparentRawWebSocket) writeFrameLocked(opcode byte, payload []byte) error {
	_, err := ws.conn.Write(transparentMarshalWSFrame(opcode, payload, true))
	return err
}

func (ws *transparentRawWebSocket) Recv() ([]byte, error) {
	for !ws.closed.Load() {
		opcode, payload, err := transparentReadWSFrame(ws.bufReader)
		if err != nil {
			ws.closed.Store(true)
			return nil, err
		}
		switch opcode {
		case 0x8:
			ws.closed.Store(true)
			return nil, io.EOF
		case 0x9:
			ws.writeMu.Lock()
			_ = ws.writeFrameLocked(0xA, payload)
			ws.writeMu.Unlock()
			continue
		case 0xA:
			continue
		case 0x1, 0x2:
			return payload, nil
		default:
			continue
		}
	}
	return nil, io.EOF
}

func (ws *transparentRawWebSocket) Close() {
	if ws.closed.Swap(true) {
		return
	}
	ws.writeMu.Lock()
	_ = ws.writeFrameLocked(0x8, nil)
	ws.writeMu.Unlock()
	_ = ws.conn.Close()
}

func transparentMarshalWSFrame(opcode byte, payload []byte, masked bool) []byte {
	payloadLen := len(payload)
	headerLen := 2
	if payloadLen >= 126 && payloadLen < 65536 {
		headerLen += 2
	} else if payloadLen >= 65536 {
		headerLen += 8
	}
	if masked {
		headerLen += 4
	}

	frame := make([]byte, headerLen+payloadLen)
	offset := 0
	frame[offset] = 0x80 | opcode
	offset++

	maskBit := byte(0)
	if masked {
		maskBit = 0x80
	}
	switch {
	case payloadLen < 126:
		frame[offset] = maskBit | byte(payloadLen)
		offset++
	case payloadLen < 65536:
		frame[offset] = maskBit | 126
		offset++
		binary.BigEndian.PutUint16(frame[offset:], uint16(payloadLen))
		offset += 2
	default:
		frame[offset] = maskBit | 127
		offset++
		binary.BigEndian.PutUint64(frame[offset:], uint64(payloadLen))
		offset += 8
	}

	if masked {
		var maskKey [4]byte
		_, _ = cryptorand.Read(maskKey[:])
		copy(frame[offset:], maskKey[:])
		offset += len(maskKey)
		copy(frame[offset:], payload)
		xorMaskInPlace(frame[offset:offset+payloadLen], maskKey[:])
		return frame
	}

	copy(frame[offset:], payload)
	return frame
}

func transparentReadWSFrame(reader *bufio.Reader) (int, []byte, error) {
	var header [2]byte
	if _, err := io.ReadFull(reader, header[:]); err != nil {
		return 0, nil, err
	}
	payloadLen, err := transparentReadWSLength(reader, header[1]&0x7F)
	if err != nil {
		return 0, nil, err
	}
	if payloadLen > uint64(^uint(0)>>1) {
		return 0, nil, errors.New("websocket frame too large")
	}

	maskKey, masked, err := transparentReadWSMaskKey(reader, header[1]&0x80 != 0)
	if err != nil {
		return 0, nil, err
	}
	payload := make([]byte, int(payloadLen))
	if _, err := io.ReadFull(reader, payload); err != nil && payloadLen > 0 {
		return 0, nil, err
	}
	if masked {
		xorMaskInPlace(payload, maskKey[:])
	}
	return int(header[0] & 0x0F), payload, nil
}

func transparentReadWSLength(reader *bufio.Reader, lengthCode byte) (uint64, error) {
	switch lengthCode {
	case 126:
		var shortBuf [2]byte
		if _, err := io.ReadFull(reader, shortBuf[:]); err != nil {
			return 0, err
		}
		return uint64(binary.BigEndian.Uint16(shortBuf[:])), nil
	case 127:
		var longBuf [8]byte
		if _, err := io.ReadFull(reader, longBuf[:]); err != nil {
			return 0, err
		}
		return binary.BigEndian.Uint64(longBuf[:]), nil
	default:
		return uint64(lengthCode), nil
	}
}

func transparentReadWSMaskKey(reader *bufio.Reader, masked bool) ([4]byte, bool, error) {
	var maskKey [4]byte
	if !masked {
		return maskKey, false, nil
	}
	if _, err := io.ReadFull(reader, maskKey[:]); err != nil {
		return maskKey, false, err
	}
	return maskKey, true, nil
}

func xorMaskInPlace(payload []byte, mask []byte) {
	for i := range payload {
		payload[i] ^= mask[i&3]
	}
}

var transparentZero64 = make([]byte, 64)

type transparentTrackedStream struct {
	key       []byte
	iv        []byte
	processed uint64
	stream    cipher.Stream
}

func newTransparentTrackedCTR(key []byte, iv []byte) (*transparentTrackedStream, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	return &transparentTrackedStream{
		key:    append([]byte(nil), key...),
		iv:     append([]byte(nil), iv...),
		stream: cipher.NewCTR(block, iv),
	}, nil
}

func (t *transparentTrackedStream) XORKeyStream(dst []byte, src []byte) {
	t.stream.XORKeyStream(dst, src)
	t.processed += uint64(len(src))
}

func newTransparentAESCTR(key []byte, iv []byte) (cipher.Stream, error) {
	return newTransparentTrackedCTR(key, iv)
}

func transparentInitInfoFromPacket(data []byte) (telegramTransparentInitInfo, bool) {
	if len(data) < 64 {
		return telegramTransparentInitInfo{}, false
	}
	stream, err := newTransparentAESCTR(data[8:40], data[40:56])
	if err != nil {
		return telegramTransparentInitInfo{}, false
	}
	keystream := make([]byte, 64)
	stream.XORKeyStream(keystream, transparentZero64)
	plain := make([]byte, 8)
	for i := 0; i < 8; i++ {
		plain[i] = data[56+i] ^ keystream[56+i]
	}
	proto := binary.LittleEndian.Uint32(plain[0:4])
	if !transparentValidProto(proto) {
		return telegramTransparentInitInfo{}, false
	}
	dcIdx := int(int16(binary.LittleEndian.Uint16(plain[4:6])))
	info := telegramTransparentInitInfo{proto: proto}
	dcAbs := dcIdx
	if dcAbs < 0 {
		dcAbs = -dcAbs
	}
	if dcAbs >= 1 && dcAbs <= 203 {
		info.dc = dcAbs
		info.isMedia = dcIdx < 0
	}
	return info, true
}

func transparentProtoLabel(proto uint32) string {
	switch proto {
	case 0xEFEFEFEF:
		return "abridged"
	case 0xEEEEEEEE:
		return "intermediate"
	case 0xDDDDDDDD:
		return "padded-intermediate"
	default:
		return fmt.Sprintf("0x%08x", proto)
	}
}

func transparentValidProto(proto uint32) bool {
	return proto == 0xEFEFEFEF || proto == 0xDDDDDDDD || proto == 0xEEEEEEEE
}

type transparentMsgSplitter struct {
	stream    cipher.Stream
	protoType int
	cipherBuf []byte
	plainBuf  []byte
	disabled  bool
}

func newTransparentMsgSplitter(initData []byte, proto uint32) (*transparentMsgSplitter, error) {
	if len(initData) < 56 {
		return nil, errors.New("init packet too short")
	}
	stream, err := newTransparentAESCTR(initData[8:40], initData[40:56])
	if err != nil {
		return nil, err
	}
	skip := make([]byte, 64)
	stream.XORKeyStream(skip, transparentZero64)
	return &transparentMsgSplitter{
		stream:    stream,
		protoType: transparentProtoTagToType(proto),
	}, nil
}

const (
	transparentProtoAbridged           = 0
	transparentProtoIntermediate       = 1
	transparentProtoPaddedIntermediate = 2
)

func transparentProtoTagToType(proto uint32) int {
	switch proto {
	case 0xEEEEEEEE:
		return transparentProtoIntermediate
	case 0xDDDDDDDD:
		return transparentProtoPaddedIntermediate
	default:
		return transparentProtoAbridged
	}
}

func (s *transparentMsgSplitter) Split(chunk []byte) [][]byte {
	if len(chunk) == 0 {
		return nil
	}
	if s.disabled {
		return [][]byte{append([]byte(nil), chunk...)}
	}
	s.bufferChunk(chunk)
	return s.collectReadyPackets()
}

func (s *transparentMsgSplitter) Flush() [][]byte {
	tail := s.releaseBufferedCiphertext()
	if len(tail) == 0 {
		return nil
	}
	return [][]byte{tail}
}

func (s *transparentMsgSplitter) bufferChunk(chunk []byte) {
	s.cipherBuf = append(s.cipherBuf, chunk...)
	plainChunk := make([]byte, len(chunk))
	s.stream.XORKeyStream(plainChunk, chunk)
	s.plainBuf = append(s.plainBuf, plainChunk...)
}

func (s *transparentMsgSplitter) collectReadyPackets() [][]byte {
	var parts [][]byte
	for {
		packetLen := s.peekPacketSize()
		switch packetLen {
		case -1:
			return parts
		case 0:
			remainder := s.releaseBufferedCiphertext()
			if len(remainder) > 0 {
				parts = append(parts, remainder)
			}
			s.disabled = true
			return parts
		default:
			packet := s.consumePacket(packetLen)
			if len(packet) == 0 {
				return parts
			}
			parts = append(parts, packet)
		}
	}
}

func (s *transparentMsgSplitter) releaseBufferedCiphertext() []byte {
	if len(s.cipherBuf) == 0 {
		return nil
	}
	tail := append([]byte(nil), s.cipherBuf...)
	s.resetBuffers()
	return tail
}

func (s *transparentMsgSplitter) resetBuffers() {
	s.cipherBuf = s.cipherBuf[:0]
	s.plainBuf = s.plainBuf[:0]
}

func (s *transparentMsgSplitter) consumePacket(packetLen int) []byte {
	if packetLen <= 0 || len(s.cipherBuf) < packetLen || len(s.plainBuf) < packetLen {
		return nil
	}
	packet := append([]byte(nil), s.cipherBuf[:packetLen]...)
	s.cipherBuf = s.cipherBuf[packetLen:]
	s.plainBuf = s.plainBuf[packetLen:]
	return packet
}

func (s *transparentMsgSplitter) peekPacketSize() int {
	if len(s.plainBuf) == 0 {
		return -1
	}
	switch s.protoType {
	case transparentProtoAbridged:
		return s.peekAbridgedPacketSize()
	case transparentProtoIntermediate, transparentProtoPaddedIntermediate:
		return s.peekIntermediatePacketSize()
	default:
		return 0
	}
}

func (s *transparentMsgSplitter) peekAbridgedPacketSize() int {
	lengthTag := s.plainBuf[0] & 0x7F
	frameHeaderSize := 1
	payloadSize := 0
	if lengthTag == 0x7F {
		if len(s.plainBuf) < 4 {
			return -1
		}
		frameHeaderSize = 4
		payloadSize = int(uint32(s.plainBuf[1])|uint32(s.plainBuf[2])<<8|uint32(s.plainBuf[3])<<16) * 4
	} else {
		payloadSize = int(lengthTag) * 4
	}
	if payloadSize <= 0 {
		return 0
	}
	frameSize := frameHeaderSize + payloadSize
	if len(s.plainBuf) < frameSize {
		return -1
	}
	return frameSize
}

func (s *transparentMsgSplitter) peekIntermediatePacketSize() int {
	if len(s.plainBuf) < 4 {
		return -1
	}
	payloadSize := int(binary.LittleEndian.Uint32(s.plainBuf[:4]) & 0x7FFFFFFF)
	if payloadSize <= 0 {
		return 0
	}
	frameSize := 4 + payloadSize
	if len(s.plainBuf) < frameSize {
		return -1
	}
	return frameSize
}

func bridgeTransparentWS(ctx context.Context, conn net.Conn, ws *transparentRawWebSocket, splitter *transparentMsgSplitter) {
	relayCtx, cancel := context.WithCancel(ctx)
	defer cancel()

	go func() {
		<-relayCtx.Done()
		safeCloseConn(conn)
		ws.Close()
	}()

	errCh := make(chan error, 2)
	go func() {
		errCh <- pumpTransparentClientToWS(relayCtx, conn, ws, splitter)
	}()
	go func() {
		errCh <- pumpTransparentWSToClient(relayCtx, conn, ws)
	}()

	<-errCh
	cancel()
	<-errCh
}

func pumpTransparentClientToWS(
	ctx context.Context,
	conn net.Conn,
	ws *transparentRawWebSocket,
	splitter *transparentMsgSplitter,
) error {
	buffer := make([]byte, 64*1024)
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		_ = conn.SetReadDeadline(time.Now().Add(5 * time.Minute))
		n, err := conn.Read(buffer)
		if n > 0 {
			if sendErr := forwardTransparentClientChunk(ws, splitter, buffer[:n]); sendErr != nil {
				return sendErr
			}
		}
		if err != nil {
			if splitter != nil {
				if tail := splitter.Flush(); len(tail) > 0 {
					if flushErr := ws.SendBatch(tail); flushErr != nil {
						return flushErr
					}
				}
			}
			return err
		}
	}
}

func forwardTransparentClientChunk(
	ws *transparentRawWebSocket,
	splitter *transparentMsgSplitter,
	chunk []byte,
) error {
	payload := append([]byte(nil), chunk...)
	if splitter == nil {
		return ws.Send(payload)
	}
	parts := splitter.Split(payload)
	switch len(parts) {
	case 0:
		return nil
	case 1:
		return ws.Send(parts[0])
	default:
		return ws.SendBatch(parts)
	}
}

func pumpTransparentWSToClient(
	ctx context.Context,
	conn net.Conn,
	ws *transparentRawWebSocket,
) error {
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		default:
		}
		payload, err := ws.Recv()
		if err != nil {
			return err
		}
		if len(payload) == 0 {
			continue
		}
		if _, err := conn.Write(payload); err != nil {
			return err
		}
	}
}

func transparentTCPFallback(ctx context.Context, client net.Conn, targetIP string, targetPort int, initPacket []byte) {
	upstreamIP := transparentPreferredTCPUpstream(targetIP)
	remote, err := protectedDialer(10*time.Second).DialContext(ctx, "tcp", net.JoinHostPort(upstreamIP, strconv.Itoa(targetPort)))
	if err != nil {
		log.Printf("Telegram transparent TCP fallback failed for %s via %s:%d: %v", targetIP, upstreamIP, targetPort, err)
		return
	}
	defer safeCloseConn(remote)
	setTcpNoDelay(remote)
	if _, err := remote.Write(initPacket); err != nil {
		log.Printf("Telegram transparent TCP fallback init write failed for %s via %s:%d: %v", targetIP, upstreamIP, targetPort, err)
		return
	}
	bridgeTransparentTCP(ctx, client, remote)
}

func bridgeTransparentTCP(ctx context.Context, client net.Conn, remote net.Conn) {
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()
	go func() {
		<-ctx.Done()
		safeCloseConn(client)
		safeCloseConn(remote)
	}()

	var wg sync.WaitGroup
	wg.Add(2)

	copyPipe := func(dst net.Conn, src net.Conn) {
		defer wg.Done()
		defer cancel()
		buffer := make([]byte, 64*1024)
		_, _ = io.CopyBuffer(dst, src, buffer)
	}

	go copyPipe(remote, client)
	go copyPipe(client, remote)
	wg.Wait()
}

func safeCloseConn(conn net.Conn) {
	if conn == nil {
		return
	}
	defer func() {
		if recovered := recover(); recovered != nil && telegramTransparentRouteErrorBudget.Add(-1) >= 0 {
			log.Printf("Telegram transparent relay suppressed close panic: %v", recovered)
		}
	}()
	if tcpConn, ok := conn.(*net.TCPConn); ok {
		_ = tcpConn.SetLinger(0)
	}
	_ = conn.Close()
}

func connectTransparentDirect(targetIP string, dcHint int, isMedia bool) (*transparentRawWebSocket, string) {
	targetIP = strings.TrimSpace(targetIP)
	if targetIP == "" {
		return nil, ""
	}
	if dcHint <= 0 {
		dcHint = transparentTargetDCHint(targetIP)
	}
	if dcHint > 0 {
		if telegramTransparentInterceptLogBudget.Add(-1) >= 0 {
			log.Printf(
				"Telegram transparent relay trying canonical WS route: target=%s dc=%d media=%v ips=%s",
				targetIP,
				dcHint,
				isMedia,
				strings.Join(transparentCanonicalDCIPs(dcHint), ","),
			)
		}
		if ws, route := connectTransparentCanonicalDC(dcHint, isMedia); ws != nil {
			storeTransparentDirectRoute(targetIP, isMedia, route)
			return ws, route
		}
	}
	candidates := transparentDirectDomainCandidates(targetIP, dcHint, isMedia)
	for start := 0; start < len(candidates); start += 4 {
		end := start + 4
		if end > len(candidates) {
			end = len(candidates)
		}
		if ws, domain := raceTransparentDomains(targetIP, candidates[start:end]); ws != nil {
			storeTransparentDirectRoute(targetIP, isMedia, domain)
			return ws, domain
		}
	}
	return nil, ""
}

var transparentCfProxyMu sync.RWMutex
var transparentCfProxyActive = ""
var transparentCfProxyDomains = decodeTransparentCfDomains()

// transparentCfHostname собирает имя узла воркера для датацентра.
//
// Медийные датацентры Telegram живут на отдельных именах `kws<dc>-1`, и раньше
// CF-маршрут этого не учитывал: медийный трафик уходил на немедийный узел. Суффикс
// добавляется только для собственного домена — у чужих из общего пула медийных
// поддоменов может не быть вовсе, и попытка увела бы их в NXDOMAIN.
func transparentCfHostname(domain string, dc int, isMedia bool) string {
	suffix := ""
	if isMedia && cfws.IsOwnedHost(domain) {
		suffix = "-1"
	}
	return fmt.Sprintf("kws%d%s.%s", dc, suffix, domain)
}

func connectTransparentCF(dc int, isMedia bool) (*transparentRawWebSocket, string) {
	transparentCfProxyMu.RLock()
	active := transparentCfProxyActive
	domains := append([]string(nil), transparentCfProxyDomains...)
	transparentCfProxyMu.RUnlock()
	if len(domains) == 0 {
		return nil, ""
	}

	ordered := make([]string, 0, len(domains))
	if active != "" {
		ordered = append(ordered, active)
	}
	for _, domain := range domains {
		if domain != "" && domain != active {
			ordered = append(ordered, domain)
		}
	}
	now := time.Now()
	// Ключ остывания привязан к самому имени узла: медийный и немедийный узлы одного
	// домена отказывают независимо, и общий ключ прятал бы рабочий за сломанным.
	cooldownKey := func(domain string) string {
		return telegramTransparentRouteCooldownKey("cf-domain", transparentCfHostname(domain, dc, isMedia))
	}
	filtered := make([]string, 0, len(ordered))
	for _, domain := range ordered {
		if telegramTransparentRouteCooling(cooldownKey(domain), now) {
			continue
		}
		filtered = append(filtered, domain)
	}
	if len(filtered) > 0 {
		ordered = filtered
	}
	type result struct {
		ws     *transparentRawWebSocket
		domain string
	}
	results := make(chan result, len(ordered))
	for _, baseDomain := range ordered {
		go func(current string) {
			wsDomain := transparentCfHostname(current, dc, isMedia)
			ws, err := wsConnectTransparent(wsDomain, wsDomain, "/apiws", 5*time.Second)
			if err != nil {
				telegramTransparentRouteRememberFailure(cooldownKey(current), 35*time.Second)
				noteOwnZoneRouteFailure(wsDomain, err)
				results <- result{}
				return
			}
			telegramTransparentRouteRememberSuccess(cooldownKey(current))
			results <- result{ws: ws, domain: current}
		}(baseDomain)
	}
	var winner *transparentRawWebSocket
	var winnerDomain string
	for i := 0; i < len(ordered); i++ {
		res := <-results
		if res.ws != nil && winner == nil {
			winner = res.ws
			winnerDomain = res.domain
			continue
		}
		if res.ws != nil {
			res.ws.Close()
		}
	}
	if winner != nil && winnerDomain != "" {
		transparentCfProxyMu.Lock()
		transparentCfProxyActive = winnerDomain
		transparentCfProxyMu.Unlock()
	}
	return winner, winnerDomain
}

// Последний домен — собственный (`.eu`, маркер `.net`). Он единственный, чей воркер
// проверяет подпись рукопожатия, см. cfws. Остальные четыре — чужие из общего пула:
// про подпись они не знают и получают чистый `binary`.
func decodeTransparentCfDomains() []string {
	encoded := []string{
		"virkgj.com",
		"vmmzovy.com",
		"mkuosckvso.com",
		"zaewayzmplad.com",
		"twdmbzcm.com",
		"uvch-hww.net",
	}
	decoded := make([]string, 0, len(encoded))
	for _, value := range encoded {
		decoded = append(decoded, decodeTransparentCfDomain(value))
	}
	return decoded
}

func decodeTransparentCfDomain(value string) string {
	suffix := ".co.uk"
	trimLen := 4
	switch {
	case strings.HasSuffix(value, ".com"):
	case strings.HasSuffix(value, ".net"):
		suffix = ".eu"
	default:
		return value
	}
	base := value[:len(value)-trimLen]
	letters := 0
	for _, char := range base {
		if (char >= 'a' && char <= 'z') || (char >= 'A' && char <= 'Z') {
			letters++
		}
	}
	output := make([]byte, 0, len(base)+len(suffix))
	for _, char := range []byte(base) {
		switch {
		case char >= 'a' && char <= 'z':
			output = append(output, byte((int(char-'a')-letters%26+26)%26+'a'))
		case char >= 'A' && char <= 'Z':
			output = append(output, byte((int(char-'A')-letters%26+26)%26+'A'))
		default:
			output = append(output, char)
		}
	}
	return string(output) + suffix
}

func transparentWSDomains(dc int, isMedia bool) []string {
	if isMedia {
		return []string{
			fmt.Sprintf("kws%d-1.web.telegram.org", dc),
			fmt.Sprintf("kws%d.web.telegram.org", dc),
		}
	}
	return []string{
		fmt.Sprintf("kws%d.web.telegram.org", dc),
		fmt.Sprintf("kws%d-1.web.telegram.org", dc),
	}
}

func transparentDirectDomainCandidates(targetIP string, dcHint int, isMedia bool) []string {
	candidates := make([]string, 0, 24)
	seen := make(map[string]struct{}, 24)
	add := func(domain string) {
		domain = strings.TrimSpace(domain)
		if domain == "" {
			return
		}
		if _, exists := seen[domain]; exists {
			return
		}
		seen[domain] = struct{}{}
		candidates = append(candidates, domain)
	}
	if cached := loadTransparentDirectDomain(targetIP, isMedia); cached != "" {
		add(cached)
	}
	if dcHint <= 0 {
		dcHint = transparentTargetDCHint(targetIP)
	}
	if dcHint > 0 {
		for _, domain := range transparentOfficialDomains(dcHint) {
			add(domain)
		}
		for _, domain := range transparentWSDomains(dcHint, isMedia) {
			add(domain)
		}
	}
	for dc := 1; dc <= 5; dc++ {
		for _, domain := range transparentOfficialDomains(dc) {
			add(domain)
		}
	}
	for dc := 1; dc <= 5; dc++ {
		for _, domain := range transparentWSDomains(dc, isMedia) {
			add(domain)
		}
	}
	add("kws203.web.telegram.org")
	add("kws203-1.web.telegram.org")
	return candidates
}

func transparentOfficialDomains(dc int) []string {
	name := ""
	switch dc {
	case 1:
		name = "pluto"
	case 2:
		name = "venus"
	case 3:
		name = "aurora"
	case 4:
		name = "vesta"
	case 5:
		name = "flora"
	default:
		return nil
	}
	return []string{
		name + ".web.telegram.org",
		name + "-1.web.telegram.org",
	}
}

func raceTransparentDomains(targetIP string, domains []string) (*transparentRawWebSocket, string) {
	return raceTransparentDomainsWithTimeout(targetIP, domains, 1200*time.Millisecond)
}

func raceTransparentDomainsWithTimeout(targetIP string, domains []string, timeout time.Duration) (*transparentRawWebSocket, string) {
	if timeout <= 0 {
		timeout = 1200 * time.Millisecond
	}
	now := time.Now()
	candidates := make([]string, 0, len(domains))
	for _, domain := range domains {
		key := telegramTransparentRouteCooldownKey("direct-domain", targetIP+"|"+domain)
		if telegramTransparentRouteCooling(key, now) {
			continue
		}
		candidates = append(candidates, domain)
	}
	if len(candidates) == 0 {
		candidates = append(candidates, domains...)
	}
	type result struct {
		ws     *transparentRawWebSocket
		domain string
		err    error
	}
	results := make(chan result, len(candidates))
	for _, domain := range candidates {
		go func(current string) {
			ws, err := wsConnectTransparent(targetIP, current, "/apiws", timeout)
			if err != nil {
				telegramTransparentRouteRememberFailure(
					telegramTransparentRouteCooldownKey("direct-domain", targetIP+"|"+current),
					45*time.Second,
				)
				results <- result{domain: current, err: err}
				return
			}
			telegramTransparentRouteRememberSuccess(
				telegramTransparentRouteCooldownKey("direct-domain", targetIP+"|"+current),
			)
			results <- result{ws: ws, domain: current}
		}(domain)
	}
	var winner *transparentRawWebSocket
	var winnerDomain string
	for i := 0; i < len(candidates); i++ {
		res := <-results
		if res.ws != nil && winner == nil {
			winner = res.ws
			winnerDomain = res.domain
			continue
		}
		if res.ws != nil {
			res.ws.Close()
			continue
		}
		if res.err != nil && telegramTransparentRouteErrorBudget.Add(-1) >= 0 {
			log.Printf(
				"Telegram transparent relay WSS candidate failed: target=%s domain=%s err=%v",
				targetIP,
				res.domain,
				res.err,
			)
		}
	}
	return winner, winnerDomain
}

func connectTransparentCanonicalDC(dc int, isMedia bool) (*transparentRawWebSocket, string) {
	domains := transparentCanonicalDomainCandidates(dc, isMedia)
	for _, ip := range transparentCanonicalDCIPs(dc) {
		if ip == "" {
			continue
		}
		if ws, domain := raceTransparentDomains(ip, domains); ws != nil {
			return ws, domain + "@" + ip
		}
	}
	return nil, ""
}

func transparentTelegramFlowsealRedirectIP(dc int) string {
	switch dc {
	case 2, 4:
		// TgWsProxy uses this Telegram edge for the DC2/DC4 WebSocket path.
		// It is especially useful for DC4 media flows that stall on the raw
		// 5.28.195.2 path through some providers.
		return "149.154.167.220"
	default:
		return ""
	}
}

func transparentPreferredWSTarget(targetIP string, dcHint int, isMedia bool) string {
	targetIP = strings.TrimSpace(targetIP)
	if dcHint <= 0 {
		dcHint = transparentTargetDCHint(targetIP)
	}
	if redirect := transparentTelegramFlowsealRedirectIP(dcHint); redirect != "" {
		switch dcHint {
		case 4:
			if isMedia || targetIP == "5.28.195.2" || targetIP == "149.154.167.91" || targetIP == "149.154.167.255" {
				return redirect
			}
		case 2:
			return redirect
		}
	}
	return targetIP
}

func transparentWSRouteLabel(domain string, dialTarget string, originalTarget string) string {
	domain = strings.TrimSpace(domain)
	dialTarget = strings.TrimSpace(dialTarget)
	originalTarget = strings.TrimSpace(originalTarget)
	if domain == "" {
		return ""
	}
	if dialTarget != "" && originalTarget != "" && dialTarget != originalTarget {
		return domain + "@" + dialTarget
	}
	return domain
}

func storeTransparentDirectRoute(targetIP string, isMedia bool, route string) {
	domain := route
	if separator := strings.IndexByte(route, '@'); separator > 0 {
		domain = route[:separator]
	}
	dc := transparentDomainDC(domain)
	if dc <= 0 {
		return
	}
	targetKey := transparentDirectTargetKey(targetIP, isMedia)
	transparentDirectRouteMu.Lock()
	transparentDirectDomainByTarget[targetKey] = domain
	transparentDirectDCByTarget[targetKey] = dc
	transparentDirectRouteMu.Unlock()
}

func transparentDirectTargetKey(targetIP string, isMedia bool) string {
	targetIP = strings.TrimSpace(strings.ToLower(targetIP))
	if isMedia {
		return targetIP + "|m"
	}
	return targetIP + "|n"
}

func loadTransparentDirectDomain(targetIP string, isMedia bool) string {
	transparentDirectRouteMu.RLock()
	defer transparentDirectRouteMu.RUnlock()
	return transparentDirectDomainByTarget[transparentDirectTargetKey(targetIP, isMedia)]
}

func loadTransparentDirectDC(targetIP string, isMedia bool) int {
	transparentDirectRouteMu.RLock()
	defer transparentDirectRouteMu.RUnlock()
	return transparentDirectDCByTarget[transparentDirectTargetKey(targetIP, isMedia)]
}

func transparentDomainDC(domain string) int {
	domain = strings.ToLower(strings.TrimSpace(domain))
	switch {
	case strings.HasPrefix(domain, "pluto"):
		return 1
	case strings.HasPrefix(domain, "venus"), strings.HasPrefix(domain, "kws2"):
		return 2
	case strings.HasPrefix(domain, "aurora"), strings.HasPrefix(domain, "kws3"):
		return 3
	case strings.HasPrefix(domain, "vesta"), strings.HasPrefix(domain, "kws4"):
		return 4
	case strings.HasPrefix(domain, "flora"), strings.HasPrefix(domain, "kws5"):
		return 5
	case strings.HasPrefix(domain, "kws1"):
		return 1
	case strings.HasPrefix(domain, "kws203"):
		return 203
	default:
		return 0
	}
}

func transparentTargetDCHint(targetIP string) int {
	return transparentTargetDCHintForMode(targetIP, false)
}

func transparentTargetDCHintForMode(targetIP string, isMedia bool) int {
	targetIP = strings.TrimSpace(strings.ToLower(targetIP))
	if targetIP == "" {
		return 0
	}
	if cached := loadTransparentDirectDC(targetIP, isMedia); cached > 0 {
		return cached
	}
	if info, ok := lookupTelegramTransparentTargetInfo(targetIP); ok {
		return info.dc
	}
	switch targetIP {
	case "173.239.243.185":
		return 5
	case "5.28.195.2":
		return 4
	case "149.154.167.220":
		if isMedia {
			return 4
		}
		return 2
	case "149.154.167.92":
		return 4
	case "149.154.167.50", "149.154.167.41":
		return 2
	case "149.154.167.255":
		return 4
	}
	if addr, err := netip.ParseAddr(targetIP); err == nil {
		if addr.Is4() {
			switch {
			case mustParseTelegramPrefix("91.108.56.0/22").Contains(addr):
				return 5
			case mustParseTelegramPrefix("149.154.171.0/24").Contains(addr):
				return 5
			case mustParseTelegramPrefix("149.154.167.50/32").Contains(addr):
				return 2
			case mustParseTelegramPrefix("149.154.167.41/32").Contains(addr):
				return 2
			case mustParseTelegramPrefix("149.154.167.91/32").Contains(addr):
				return 4
			case mustParseTelegramPrefix("149.154.167.255/32").Contains(addr):
				return 4
			case mustParseTelegramPrefix("149.154.167.51/32").Contains(addr):
				return 2
			case mustParseTelegramPrefix("149.154.167.0/24").Contains(addr):
				return 2
			case mustParseTelegramPrefix("149.154.175.50/32").Contains(addr):
				return 1
			case mustParseTelegramPrefix("149.154.175.100/32").Contains(addr):
				return 3
			case mustParseTelegramPrefix("91.105.192.100/32").Contains(addr):
				return 203
			case mustParseTelegramPrefix("149.154.175.0/24").Contains(addr):
				return 1
			}
		} else {
			switch {
			case mustParseTelegramPrefix("2001:067c:04e8:f002::/64").Contains(addr):
				return 2
			case mustParseTelegramPrefix("2001:067c:04e8:f004::/64").Contains(addr):
				return 4
			case mustParseTelegramPrefix("2001:0b28:f23f:f005::/64").Contains(addr):
				return 5
			case mustParseTelegramPrefix("2001:0b28:f23d:f001::/64").Contains(addr):
				return 1
			}
		}
	}
	return 0
}

func transparentLikelyMediaTarget(targetIP string, targetPort int, dcHint int) bool {
	targetIP = strings.TrimSpace(strings.ToLower(targetIP))
	if info, ok := lookupTelegramTransparentTargetInfo(targetIP); ok && info.isMedia {
		return true
	}
	switch targetIP {
	case "5.28.195.2", "149.154.167.91", "149.154.167.92", "149.154.167.255":
		return true
	}
	if targetPort >= 7300 && targetPort <= 7310 {
		return false
	}
	if dcHint == 4 && (targetPort == 443 || targetPort == 80) {
		return true
	}
	return false
}

func shouldProxyTelegramMediaTLS(targetIP string, targetPort int) bool {
	return true
}

func transparentPreferredTCPUpstream(targetIP string) string {
	targetIP = strings.TrimSpace(targetIP)
	if targetIP == "" {
		return targetIP
	}
	if dcHint := transparentTargetDCHint(targetIP); dcHint > 0 {
		if canonical := transparentCanonicalIPv4(dcHint); canonical != "" {
			return canonical
		}
	}
	return targetIP
}

func transparentCanonicalDomainCandidates(dc int, isMedia bool) []string {
	candidates := make([]string, 0, 4)
	candidates = append(candidates, transparentOfficialDomains(dc)...)
	candidates = append(candidates, transparentWSDomains(dc, isMedia)...)
	return candidates
}

func transparentCanonicalDCIPs(dc int) []string {
	switch dc {
	case 1:
		return []string{"149.154.175.50"}
	case 2:
		return []string{"149.154.167.220", "149.154.167.50", "149.154.167.41", "149.154.167.51"}
	case 3:
		return []string{"149.154.175.100"}
	case 4:
		return []string{"149.154.167.220", "149.154.167.91", "5.28.195.2"}
	case 5:
		return []string{"149.154.171.5", "91.108.56.100", "91.108.56.101", "91.108.56.116", "91.108.56.126", "91.108.56.102", "91.108.56.128", "91.108.56.151", "173.239.243.185", "91.108.56.123"}
	case 203:
		return []string{"91.105.192.100"}
	default:
		return nil
	}
}

func transparentCanonicalIPv4(dc int) string {
	for _, ip := range transparentCanonicalDCIPs(dc) {
		addr, err := netip.ParseAddr(ip)
		if err == nil && addr.Is4() {
			return ip
		}
	}
	return ""
}

func telegramTransparentRouteFailureBypassDuration(dcHint int, isMedia bool, fallback time.Duration) time.Duration {
	if dcHint > 0 && isMedia {
		if fallback > 12*time.Second {
			return 12 * time.Second
		}
	}
	return fallback
}

func intMax(a int, b int) int {
	if a > b {
		return a
	}
	return b
}

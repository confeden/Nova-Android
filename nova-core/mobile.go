package nova

import (
	"context"
	cryptorand "crypto/rand"
	"crypto/tls"
	"encoding/base64"
	"io"
	"log/slog"
	mathrand "math/rand"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	novaengine "nova-core/engine"

	"github.com/bepass-org/warp-plus/ipscanner"
	"github.com/bepass-org/warp-plus/warp"
	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"golang.org/x/crypto/curve25519"

	_ "golang.org/x/mobile/bind"
)

// SocketProtector interface for Java callback
type SocketProtector interface {
	Protect(fd int) bool
}

var protector SocketProtector

// masqueProtector — отдельный протектор для сокета MASQUE; nil означает «общий».
var masqueProtector SocketProtector

// SetSocketProtector registers the Java callback
func SetSocketProtector(p SocketProtector) {
	protector = p
	// Link to engine (for wireguard)
	novaengine.GlobalProtector = func(fd int) bool {
		if protector != nil {
			return protector.Protect(fd)
		}
		return false
	}
}

// SetDnsInterceptConfig configures optional TUN-level DNS interception.
// upstreamsCSV should contain a comma-separated list of DNS server IPs.
func SetDnsInterceptConfig(enabled bool, upstreamsCSV string) {
	upstreams := make([]string, 0)
	for _, raw := range strings.Split(upstreamsCSV, ",") {
		trimmed := strings.TrimSpace(raw)
		if trimmed == "" {
			continue
		}
		upstreams = append(upstreams, trimmed)
	}
	novaengine.SetDNSInterceptConfig(enabled, upstreams)
}

// SetDnsInterceptPolicy configures targeted DNS interception rules.
// mediaUpstreamsCSV is used first for matched domains, then defaultUpstreamsCSV
// is used as fallback. mediaDomainsCSV contains comma-separated domain suffixes.
func SetDnsInterceptPolicy(enabled bool, mediaUpstreamsCSV string, defaultUpstreamsCSV string, mediaDomainsCSV string) {
	parseCSV := func(value string) []string {
		result := make([]string, 0)
		for _, raw := range strings.Split(value, ",") {
			trimmed := strings.TrimSpace(raw)
			if trimmed == "" {
				continue
			}
			result = append(result, trimmed)
		}
		return result
	}
	novaengine.SetDNSInterceptPolicy(
		enabled,
		parseCSV(mediaUpstreamsCSV),
		parseCSV(defaultUpstreamsCSV),
		parseCSV(mediaDomainsCSV),
	)
}

// SetTrafficCamouflageHost configures a neutral fake-template host that can be
// reused by transport-specific obfuscation paths such as MASQUE fake QUIC bursts.
func SetTrafficCamouflageHost(host string) {
	novaengine.SetFakeTemplateHintHost(host)
}

// SetTelegramTransparentProxyConfig configures the transparent Telegram relay
// inside nova-core. Supported profiles: "wifi", "mobile", "off".
func SetTelegramTransparentProxyConfig(enabled bool, profile string) {
	novaengine.SetTelegramTransparentProxyConfig(enabled, profile)
}

// SetLocalCloudflareProxyAddress tells the engine where the app's local HTTP
// proxy listens, in "host:port" form. The app picks an ephemeral port, so the
// address cannot be hardcoded in the core: a mismatch silently disables the
// proxy path for Cloudflare API calls.
func SetLocalCloudflareProxyAddress(address string) {
	novaengine.SetLocalCloudflareProxyAddress(address)
}

// SetTelegramWsSignatureSecret passes the WSS handshake secret to the engine.
// The token itself is computed inside nova-core because its validity window is
// tied to the moment each connection is opened, not to app startup.
func SetTelegramWsSignatureSecret(secret string) {
	novaengine.SetTelegramWsSignatureSecret(secret)
}

// SetMasqueFakeBurstEnabled toggles the extra fake QUIC pre-burst for MASQUE.
func SetMasqueFakeBurstEnabled(enabled bool) {
	novaengine.SetMasqueFakeBurstEnabled(enabled)
}

// SetMasqueAwaitSettingsEnabled toggles waiting for the server's HTTP/3 SETTINGS as a
// separate step before the CONNECT-IP request. Diagnostic only: it is the last structural
// difference between our dial and the reference probe, which sends the request at once.
//
// Doc comments on exported bindings must stay ASCII: gomobile copies them into
// generated Java, and javac reads that file as windows-1252.
func SetMasqueAwaitSettingsEnabled(enabled bool) {
	novaengine.SetMasqueAwaitSettingsEnabled(enabled)
}

// SetMasqueProtectSocketEnabled toggles marking the MASQUE UDP socket so it bypasses our
// own VPN. Diagnostic only: the reference probe never installs a socket protector, and
// this is the last thing it does not replicate. Leave it on in production, otherwise the
// dial would be routed into the tunnel it is trying to build.
//
// Doc comments on exported bindings must stay ASCII: gomobile copies them into
// generated Java, and javac reads that file as windows-1252.
func SetMasqueProtectSocketEnabled(enabled bool) {
	novaengine.SetMasqueProtectSocketEnabled(enabled)
}

// SetMasqueConnectIPOpenTimeoutMs overrides the budget for HTTP/3 SETTINGS plus the
// CONNECT-IP request. Pass 0 for the built-in value. Diagnostic: every failure lands
// exactly on the built-in budget, and it was only ever excluded from another process.
//
// Doc comments on exported bindings must stay ASCII: gomobile copies them into
// generated Java, and javac reads that file as windows-1252.
func SetMasqueConnectIPOpenTimeoutMs(ms int) {
	novaengine.SetMasqueConnectIPOpenTimeoutMs(ms)
}

// SetMasqueSocketPreprobeEnabled toggles the pre-handshake reachability packet (a QUIC
// datagram with a deliberately unknown version). Off by default: on the wire the node
// answers it with Version Negotiation and then goes completely silent right after the
// CONNECT-IP request, while the reference probe, which never sends it, is served.
//
// Doc comments on exported bindings must stay ASCII: gomobile copies them into
// generated Java, and javac reads that file as windows-1252.
func SetMasqueSocketPreprobeEnabled(enabled bool) {
	novaengine.SetMasqueSocketPreprobeEnabled(enabled)
}

// SetMasqueSocketProtector installs a MASQUE-specific way to keep the dial off our own
// VPN. VpnService.protect() breaks the CONNECT-IP response on this path (measured), while
// it works for WireGuard, so MASQUE binds the socket to the underlying network instead.
// Leave it unset to fall back to the shared protector.
//
// Doc comments on exported bindings must stay ASCII: gomobile copies them into
// generated Java, and javac reads that file as windows-1252.
func SetMasqueSocketProtector(p SocketProtector) {
	masqueProtector = p
	if p == nil {
		novaengine.MasqueProtector = nil
		return
	}
	novaengine.MasqueProtector = func(fd int) bool {
		if masqueProtector != nil {
			return masqueProtector.Protect(fd)
		}
		return false
	}
}

// StartVPN starts the WireGuard engine for Android.
func StartVPN(fd int, config string) error {
	return novaengine.StartWireGuard(fd, config)
}

// StartMasqueVPN starts the MASQUE engine for Android.
func StartMasqueVPN(fd int, identityJSON string, endpointHost string, endpointPort int) error {
	return novaengine.StartMasque(fd, identityJSON, endpointHost, endpointPort, "")
}

// StartMasqueVPNWithSNI starts the MASQUE engine for Android with an explicit SNI.
func StartMasqueVPNWithSNI(fd int, identityJSON string, endpointHost string, endpointPort int, sni string) error {
	return novaengine.StartMasque(fd, identityJSON, endpointHost, endpointPort, sni)
}

// StopVPN stops the active WireGuard engine.
func StopVPN() {
	novaengine.StopWireGuard()
	novaengine.StopMasque()
}

// GetVPNStats returns current WireGuard peer stats in UAPI format.
func GetVPNStats() string {
	if stats := novaengine.GetMasqueRuntimeStats(); strings.TrimSpace(stats) != "" {
		return stats
	}
	return novaengine.GetWireGuardRuntimeStats()
}

// EnsureMasqueConfig returns a cached-or-enrolled MASQUE identity JSON.
//
// Only one enrollment per device id runs at a time. A second concurrent call joins the
// first instead of issuing a second key: the server keeps only the last key, so two
// enrollments in one connect cycle leave the saved identity dead on arrival.
//
// Doc comments on exported bindings must stay ASCII: gomobile copies them into
// generated Java, and javac reads that file as windows-1252.
func EnsureMasqueConfig(existingConfigJSON string, accessToken string, deviceID string, deviceName string) (string, error) {
	return novaengine.EnsureMasqueConfig(existingConfigJSON, accessToken, deviceID, deviceName)
}

// LastMasqueEnrollResult returns the identity JSON of the most recent enrollment without
// making any request, or an empty string when there is none.
//
// It exists so that a caller whose own wait timed out can still pick up the key that was
// issued meanwhile. Dropping it would leave the public key on the server with its private
// half gone, which reads exactly like "the endpoint accepts TLS and never answers
// CONNECT-IP".
//
// Doc comments on exported bindings must stay ASCII: gomobile copies them into
// generated Java, and javac reads that file as windows-1252.
func LastMasqueEnrollResult(deviceID string) string {
	return novaengine.LastMasqueEnrollResult(deviceID)
}

// ForgetMasqueEnrollResult drops the remembered enrollment so that the next call issues a
// genuinely fresh key. Pass an empty device id to drop every remembered key.
//
// Doc comments on exported bindings must stay ASCII: gomobile copies them into
// generated Java, and javac reads that file as windows-1252.
func ForgetMasqueEnrollResult(deviceID string) {
	novaengine.ForgetMasqueEnrollResult(deviceID)
}

// ProbeMasqueHandshake dials MASQUE up to the tunnel-request response and closes the
// connection. Diagnostic only: it separates a refusal by the Cloudflare service from
// interference by our own VPN process. No data plane is brought up.
//
// Doc comments on exported bindings must stay ASCII: gomobile copies them into
// generated Java, and javac reads that file as windows-1252.
func ProbeMasqueHandshake(identityJSON string, endpointHost string, endpointPort int, sni string) string {
	return novaengine.ProbeMasqueHandshake(identityJSON, endpointHost, endpointPort, sni)
}

// SetPlainCloudflareApiPreferred makes Cloudflare API calls go through a plain HTTPS
// request first, before the obfuscated transport. Turn it on only while a tunnel is
// already up: inside the tunnel the SNI block does not apply, and a MASQUE key issued
// over the obfuscated transport is never served by the tunnel endpoint.
//
// Doc comments on exported bindings must stay ASCII: gomobile copies them into
// generated Java, and javac reads that file as windows-1252.
func SetPlainCloudflareApiPreferred(enabled bool) {
	novaengine.SetPlainCloudflareAPIPreferredInternal(enabled)
}

// RegisterWarp performs obfuscated WARP registration and returns raw API JSON.
func RegisterWarp(publicKey string, locale string, deviceModel string) (string, error) {
	return novaengine.RegisterWarp(publicKey, locale, deviceModel)
}

// SetWarpLicense binds a WARP+ license key to the device and returns the resulting
// account type as the server reports it.
func SetWarpLicense(accessToken string, deviceID string, license string) (string, error) {
	return novaengine.SetWarpLicense(accessToken, deviceID, license)
}

// CancelRegisterWarp cancels an in-flight direct WARP registration attempt.
func CancelRegisterWarp() {
	novaengine.CancelRegisterWarp()
}

// ScanWarpEndpoints returns newline-separated WARP endpoint candidates in the
// form addr:port|rtt_ms. It uses warp-plus ipscanner so Android can rotate
// through multiple likely-good endpoints instead of pinning to one API IP.
func ScanWarpEndpoints(privateKey string, peerPublicKey string, useIPv4 bool, useIPv6 bool, timeoutMs int, limit int) string {
	if strings.TrimSpace(privateKey) == "" || strings.TrimSpace(peerPublicKey) == "" {
		return ""
	}
	if !useIPv4 && !useIPv6 {
		useIPv4 = true
		useIPv6 = true
	}
	if timeoutMs <= 0 {
		timeoutMs = 2500
	}
	if limit <= 0 {
		limit = 4
	}

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	scanner := ipscanner.NewScanner(
		ipscanner.WithLogger(logger),
		ipscanner.WithWarpPrivateKey(privateKey),
		ipscanner.WithWarpPeerPublicKey(peerPublicKey),
		ipscanner.WithUseIPv4(useIPv4),
		ipscanner.WithUseIPv6(useIPv6),
		ipscanner.WithMaxDesirableRTT(1500*time.Millisecond),
		ipscanner.WithIPQueueSize(128),
		ipscanner.WithCidrList(warp.WarpPrefixes()),
	)

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	scanner.Run(ctx)
	<-ctx.Done()

	infos := scanner.GetAvailableIPs()
	var sb strings.Builder

	added := 0
	for _, info := range infos {
		if added >= limit {
			break
		}
		sb.WriteString(info.AddrPort.String())
		sb.WriteString("|")
		sb.WriteString(strconv.FormatInt(info.RTT.Milliseconds(), 10))
		sb.WriteString("\n")
		added++
	}

	for added < limit {
		addrPort, err := warp.RandomWarpEndpoint(useIPv4, useIPv6)
		if err != nil {
			break
		}
		sb.WriteString(addrPort.String())
		sb.WriteString("|-1\n")
		added++
	}

	return sb.String()
}

type masqueScanCandidate struct {
	host string
	port int
}

type masqueScanResult struct {
	host  string
	port  int
	rttMs int64
}

var masqueScanRange4 = []string{
	"162.159.192.0/24",
	"162.159.197.0/24",
	"162.159.198.0/24",
}

var masqueScanSeeds4 = []string{
	"162.159.192.1",
	"162.159.192.2",
	"162.159.197.1",
	"162.159.197.2",
	"162.159.198.1",
	"162.159.198.2",
}

var masqueScanSeeds6 = []string{
	"2606:4700:102::1",
	"2606:4700:102::2",
	"2606:4700:103::1",
	"2606:4700:103::2",
}

// ScanMasqueEndpoints performs a quick QUIC/H3 pre-scan across Cloudflare MASQUE
// ranges and returns newline-separated endpoint candidates in the form
// addr:port|rtt_ms. It uses the provided SNI because Cloudflare MASQUE path
// selection depends on it in practice.
func ScanMasqueEndpoints(useIPv4 bool, useIPv6 bool, timeoutMs int, limit int, portsCSV string, sni string) string {
	return scanMasqueEndpointsInternal(useIPv4, useIPv6, timeoutMs, limit, portsCSV, sni, "quic")
}

// ScanMasqueEndpointsWithTransport performs the same pre-scan, but allows selecting
// transport explicitly. Supported values: "quic", "tcp-tls".
func ScanMasqueEndpointsWithTransport(useIPv4 bool, useIPv6 bool, timeoutMs int, limit int, portsCSV string, sni string, transport string) string {
	return scanMasqueEndpointsInternal(useIPv4, useIPv6, timeoutMs, limit, portsCSV, sni, transport)
}

func scanMasqueEndpointsInternal(useIPv4 bool, useIPv6 bool, timeoutMs int, limit int, portsCSV string, sni string, transport string) string {
	if !useIPv4 && !useIPv6 {
		useIPv4 = true
		useIPv6 = true
	}
	if timeoutMs <= 0 {
		timeoutMs = 3200
	}
	if limit <= 0 {
		limit = 4
	}

	ports := parseMasquePorts(portsCSV)
	candidates := buildMasqueScanCandidates(useIPv4, useIPv6, ports)
	if len(candidates) == 0 {
		return ""
	}

	rng := mathrand.New(mathrand.NewSource(time.Now().UnixNano()))
	seedCount := 0
	for seedCount < len(candidates) {
		if isMasqueSeedHost(candidates[seedCount].host) {
			seedCount++
			continue
		}
		break
	}
	if tail := len(candidates) - seedCount; tail > 1 {
		rng.Shuffle(tail, func(i, j int) {
			candidates[seedCount+i], candidates[seedCount+j] = candidates[seedCount+j], candidates[seedCount+i]
		})
	}

	maxToTry := limit * 10
	if maxToTry < 20 {
		maxToTry = 20
	}
	if maxToTry > len(candidates) {
		maxToTry = len(candidates)
	}
	candidates = candidates[:maxToTry]

	totalTimeout := time.Duration(timeoutMs) * time.Millisecond
	perProbeTimeout := totalTimeout / 3
	if perProbeTimeout < 900*time.Millisecond {
		perProbeTimeout = 900 * time.Millisecond
	}
	if perProbeTimeout > 1500*time.Millisecond {
		perProbeTimeout = 1500 * time.Millisecond
	}

	ctx, cancel := context.WithTimeout(context.Background(), totalTimeout)
	defer cancel()

	jobs := make(chan masqueScanCandidate)
	results := make(chan masqueScanResult, limit*3)
	var wg sync.WaitGroup

	workerCount := 6
	if maxToTry < workerCount {
		workerCount = maxToTry
	}
	if workerCount <= 0 {
		workerCount = 1
	}

	connectSNI := strings.TrimSpace(sni)
	if connectSNI == "" {
		connectSNI = "zt-masque.cloudflareclient.com"
	}
	transport = strings.TrimSpace(strings.ToLower(transport))
	if transport == "" {
		transport = "quic"
	}

	for i := 0; i < workerCount; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case candidate, ok := <-jobs:
					if !ok {
						return
					}

					startedAt := time.Now()
					if err := tryMasqueEndpoint(ctx, candidate, connectSNI, perProbeTimeout, transport); err != nil {
						continue
					}

					select {
					case results <- masqueScanResult{
						host:  candidate.host,
						port:  candidate.port,
						rttMs: time.Since(startedAt).Milliseconds(),
					}:
					case <-ctx.Done():
						return
					}
				}
			}
		}()
	}

	go func() {
		defer close(jobs)
		for _, candidate := range candidates {
			select {
			case <-ctx.Done():
				return
			case jobs <- candidate:
			}
		}
	}()

	go func() {
		wg.Wait()
		close(results)
	}()

	byEndpoint := make(map[string]masqueScanResult)
	for result := range results {
		key := formatMasqueEndpoint(result.host, result.port)
		prev, ok := byEndpoint[key]
		if !ok || result.rttMs < prev.rttMs {
			byEndpoint[key] = result
		}
	}

	ordered := make([]masqueScanResult, 0, len(byEndpoint))
	for _, result := range byEndpoint {
		ordered = append(ordered, result)
	}
	sort.Slice(ordered, func(i, j int) bool {
		if ordered[i].rttMs == ordered[j].rttMs {
			if ordered[i].host == ordered[j].host {
				return ordered[i].port < ordered[j].port
			}
			return ordered[i].host < ordered[j].host
		}
		return ordered[i].rttMs < ordered[j].rttMs
	})
	if len(ordered) > limit {
		ordered = ordered[:limit]
	}

	var sb strings.Builder
	for _, result := range ordered {
		sb.WriteString(formatMasqueEndpoint(result.host, result.port))
		sb.WriteString("|")
		sb.WriteString(strconv.FormatInt(result.rttMs, 10))
		sb.WriteString("\n")
	}

	return sb.String()
}

func parseMasquePorts(raw string) []int {
	seen := make(map[int]struct{})
	ports := make([]int, 0, 8)
	for _, item := range strings.Split(raw, ",") {
		port, err := strconv.Atoi(strings.TrimSpace(item))
		if err != nil || port < 1 || port > 65535 {
			continue
		}
		if _, ok := seen[port]; ok {
			continue
		}
		seen[port] = struct{}{}
		ports = append(ports, port)
	}
	if len(ports) == 0 {
		ports = []int{443, 500, 1701, 4500, 4443, 8443, 8095}
	}
	return ports
}

func buildMasqueScanCandidates(useIPv4 bool, useIPv6 bool, ports []int) []masqueScanCandidate {
	seen := make(map[string]struct{})
	candidates := make([]masqueScanCandidate, 0, 64)

	add := func(host string, port int) {
		host = strings.TrimSpace(strings.Trim(host, "[]"))
		if host == "" || port < 1 || port > 65535 {
			return
		}
		key := host + ":" + strconv.Itoa(port)
		if _, ok := seen[key]; ok {
			return
		}
		seen[key] = struct{}{}
		candidates = append(candidates, masqueScanCandidate{host: host, port: port})
	}

	if useIPv4 {
		for _, host := range masqueScanSeeds4 {
			for _, port := range ports {
				add(host, port)
			}
		}
		for _, cidr := range masqueScanRange4 {
			for _, host := range expandIPv4CIDRHosts(cidr) {
				for _, port := range ports {
					add(host, port)
				}
			}
		}
	}

	if useIPv6 {
		for _, host := range masqueScanSeeds6 {
			for _, port := range ports {
				add(host, port)
			}
		}
	}

	return candidates
}

func expandIPv4CIDRHosts(cidr string) []string {
	_, ipNet, err := net.ParseCIDR(strings.TrimSpace(cidr))
	if err != nil || ipNet == nil {
		return nil
	}
	base := ipNet.IP.To4()
	mask := net.IP(ipNet.Mask).To4()
	if base == nil || mask == nil {
		return nil
	}

	network := uint32(base[0])<<24 | uint32(base[1])<<16 | uint32(base[2])<<8 | uint32(base[3])
	maskValue := uint32(mask[0])<<24 | uint32(mask[1])<<16 | uint32(mask[2])<<8 | uint32(mask[3])
	broadcast := network | ^maskValue
	if broadcast <= network+1 {
		return nil
	}

	hosts := make([]string, 0, int(broadcast-network-1))
	for value := network + 1; value < broadcast; value++ {
		hosts = append(hosts, net.IPv4(byte(value>>24), byte(value>>16), byte(value>>8), byte(value)).String())
	}
	return hosts
}

func tryMasqueEndpoint(parent context.Context, candidate masqueScanCandidate, sni string, timeout time.Duration, transport string) error {
	ctx, cancel := context.WithTimeout(parent, timeout)
	defer cancel()

	if transport == "tcp-tls" {
		tlsConfig := &tls.Config{
			InsecureSkipVerify: true,
			ServerName:         sni,
			NextProtos:         []string{"h2", "http/1.1"},
		}
		dialer := &net.Dialer{Timeout: timeout}
		conn, err := tls.DialWithDialer(dialer, "tcp", formatMasqueEndpoint(candidate.host, candidate.port), tlsConfig)
		if err != nil {
			return err
		}
		return conn.Close()
	}

	tlsConfig := &tls.Config{
		InsecureSkipVerify: true,
		NextProtos:         []string{http3.NextProtoH3, "h3-29", "h3-32", "h3-34"},
		ServerName:         sni,
	}
	quicConfig := &quic.Config{
		EnableDatagrams:      true,
		HandshakeIdleTimeout: timeout,
		MaxIdleTimeout:       timeout,
		InitialPacketSize:    1242,
	}

	conn, err := quic.DialAddr(ctx, formatMasqueEndpoint(candidate.host, candidate.port), tlsConfig, quicConfig)
	if err != nil {
		return err
	}
	return conn.CloseWithError(0, "")
}

func formatMasqueEndpoint(host string, port int) string {
	if strings.Contains(host, ":") && !strings.HasPrefix(host, "[") {
		return "[" + host + "]:" + strconv.Itoa(port)
	}
	return host + ":" + strconv.Itoa(port)
}

func isMasqueSeedHost(host string) bool {
	host = strings.TrimSpace(strings.Trim(host, "[]"))
	for _, seed := range masqueScanSeeds4 {
		if host == seed {
			return true
		}
	}
	for _, seed := range masqueScanSeeds6 {
		if host == seed {
			return true
		}
	}
	return false
}

// Other functions
func GeneratePrivateKey() (string, error) {
	var privateKey [32]byte
	_, _ = cryptorand.Read(privateKey[:])
	privateKey[0] &= 248
	privateKey[31] &= 127
	privateKey[31] |= 64
	return base64.StdEncoding.EncodeToString(privateKey[:]), nil
}

func GeneratePublicKey(privateKeyB64 string) (string, error) {
	privateKeySlice, _ := base64.StdEncoding.DecodeString(privateKeyB64)
	var privateKey [32]byte
	copy(privateKey[:], privateKeySlice)
	var publicKey [32]byte
	curve25519.ScalarBaseMult(&publicKey, &privateKey)
	return base64.StdEncoding.EncodeToString(publicKey[:]), nil
}

func UpdateStrategy(name string) {}

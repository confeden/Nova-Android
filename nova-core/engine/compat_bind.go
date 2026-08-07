package nova

import (
	"context"
	"encoding/base64"
	"io"
	"log"
	"log/slog"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/bepass-org/warp-plus/ipscanner"
	"github.com/bepass-org/warp-plus/warp"
)

// SocketProtector is implemented by Android VpnService so Go sockets can be
// excluded from the VPN routing table before they connect to the real network.
type SocketProtector interface {
	Protect(fd int) bool
}

func SetSocketProtector(protector SocketProtector) {
	if protector == nil {
		GlobalProtector = nil
		return
	}
	GlobalProtector = func(fd int) bool {
		return protector.Protect(fd)
	}
}

func GeneratePrivateKey() (string, error) {
	key, err := warp.GeneratePrivateKey()
	if err != nil {
		return "", err
	}
	return key.String(), nil
}

func GeneratePublicKey(privateKeyB64 string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(privateKeyB64))
	if err != nil {
		return "", err
	}
	key, err := warp.NewKey(raw)
	if err != nil {
		return "", err
	}
	return key.PublicKey().String(), nil
}

func StartVPN(fd int, config string) error {
	return StartWireGuard(fd, config)
}

func StopVPN() {
	StopWireGuard()
	StopMasque()
}

func GetVPNStats() string {
	if stats := GetWireGuardRuntimeStats(); strings.TrimSpace(stats) != "" {
		return stats
	}
	return GetMasqueRuntimeStats()
}

func StartMasqueVPN(fd int, identityJSON string, endpointHost string, endpointPort int) error {
	return StartMasque(fd, identityJSON, endpointHost, endpointPort, "")
}

func StartMasqueVPNWithSNI(fd int, identityJSON string, endpointHost string, endpointPort int, sni string) error {
	return StartMasque(fd, identityJSON, endpointHost, endpointPort, sni)
}

func SetDnsInterceptConfig(enabled bool, upstreamsCSV string) {
	upstreams := splitCSVList(upstreamsCSV)
	SetDNSInterceptPolicy(enabled, upstreams, nil, nil)
}

func SetDnsInterceptPolicy(enabled bool, mediaUpstreamsCSV string, defaultUpstreamsCSV string, mediaDomainsCSV string) {
	SetDNSInterceptPolicy(
		enabled,
		splitCSVList(mediaUpstreamsCSV),
		splitCSVList(defaultUpstreamsCSV),
		splitCSVList(mediaDomainsCSV),
	)
}

func SetTrafficCamouflageHost(host string) {
	SetFakeTemplateHintHost(host)
}

func UpdateStrategy(name string) {
	SetFakeStrategyProfile(name)
}

func ScanWarpEndpoints(privateKey string, peerPublicKey string, useIPv4 bool, useIPv6 bool, timeoutMs int64, limit int64) string {
	if timeoutMs <= 0 {
		timeoutMs = 3500
	}
	if limit <= 0 {
		limit = 3
	}
	if !useIPv4 && !useIPv6 {
		return ""
	}

	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutMs)*time.Millisecond)
	defer cancel()

	scanner := ipscanner.NewScanner(
		ipscanner.WithLogger(slog.New(slog.NewTextHandler(io.Discard, nil))),
		ipscanner.WithWarpPrivateKey(strings.TrimSpace(privateKey)),
		ipscanner.WithWarpPeerPublicKey(strings.TrimSpace(peerPublicKey)),
		ipscanner.WithUseIPv4(useIPv4),
		ipscanner.WithUseIPv6(useIPv6),
		ipscanner.WithCidrList(warp.WarpPrefixes()),
		ipscanner.WithIPQueueSize(4096),
		ipscanner.WithMaxDesirableRTT(time.Duration(timeoutMs)*time.Millisecond),
	)
	scanner.Run(ctx)

	ticker := time.NewTicker(120 * time.Millisecond)
	defer ticker.Stop()

	var last []ipscanner.IPInfo
	for {
		current := scanner.GetAvailableIPs()
		if len(current) > 0 {
			last = current
			if int64(len(current)) >= limit {
				break
			}
		}
		select {
		case <-ctx.Done():
			goto done
		case <-ticker.C:
		}
	}

done:
	if len(last) == 0 {
		last = scanner.GetAvailableIPs()
	}
	sort.SliceStable(last, func(i, j int) bool {
		return last[i].RTT < last[j].RTT
	})
	if int64(len(last)) > limit {
		last = last[:limit]
	}

	var b strings.Builder
	for _, item := range last {
		if !item.AddrPort.IsValid() {
			continue
		}
		rttMs := item.RTT.Milliseconds()
		if rttMs < 0 {
			rttMs = 0
		}
		b.WriteString(item.AddrPort.String())
		b.WriteByte('|')
		b.WriteString(strconv.FormatInt(rttMs, 10))
		b.WriteByte('\n')
	}
	result := b.String()
	if strings.TrimSpace(result) != "" {
		log.Printf("ScanWarpEndpoints found %d candidate(s)", len(strings.Split(strings.TrimSpace(result), "\n")))
	}
	return result
}

func ScanMasqueEndpoints(useIPv4 bool, useIPv6 bool, timeoutMs int64, limit int64, portsCSV string, sni string) string {
	return ScanMasqueEndpointsWithTransport(useIPv4, useIPv6, timeoutMs, limit, portsCSV, sni, "quic")
}

func ScanMasqueEndpointsWithTransport(useIPv4 bool, useIPv6 bool, timeoutMs int64, limit int64, portsCSV string, sni string, transport string) string {
	// Kotlin has a deterministic MASQUE fallback candidate set. Returning empty
	// here keeps that path active without blocking connection startup on a scan.
	return ""
}

func splitCSVList(csv string) []string {
	if strings.TrimSpace(csv) == "" {
		return nil
	}
	parts := strings.Split(csv, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		trimmed := strings.TrimSpace(part)
		if trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}

package nova

import (
	"os"
	"sort"
	"testing"
	"time"
)

// Живой прогон прямого пути DNS-AI с настоящей сетью: ядро (гонка адрес × стратегия), а не лаборатория.
// По умолчанию пропускается. На телефоне:
//
//	GOOS=linux GOARCH=arm64 CGO_ENABLED=0 go test -c -o engine.test ./engine
//	adb push engine.test /data/local/tmp/ && adb shell 'cd /data/local/tmp && SSL_CERT_DIR=/system/etc/security/cacerts NOVA_LIVE_DNSAI=1 ./engine.test -test.run LiveDNSAI -test.v'
func TestLiveDNSAIDirectObfs(t *testing.T) {
	if os.Getenv("NOVA_LIVE_DNSAI") == "" {
		t.Skip("set NOVA_LIVE_DNSAI=1 to query the real DNS-AI entrances")
	}
	if os.Getenv("NOVA_LIVE_NOEXTRA") != "" {
		obfsExtraOff = true
		defer func() { obfsExtraOff = false }()
	}
	inner := obfsAttemptFn
	obfsAttemptFn = func(q []byte, sp *dnsUpstreamSpec, r dnsUpstreamRoute, a *dnsAttempt, to time.Duration) ([]byte, string, error) {
		started := time.Now()
		payload, label, err := inner(q, sp, r, a, to)
		t.Logf("    %s %-26s %6dms err=%v", sp.display, a.key(), time.Since(started).Milliseconds(), err)
		return payload, label, err
	}
	defer func() { obfsAttemptFn = inner }()
	query := []byte{0, 0, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 7, 'e', 'x', 'a', 'm', 'p', 'l', 'e', 3, 'c', 'o', 'm', 0, 0, 1, 0, 1}
	const dnsBootstrap = "|2a12:5940:d4ef::219|2a00:b700:5::3:62|194.87.196.13|45.151.102.219|via=obfsonly"
	const cfBootstrap = "|2606:4700:3030::ac43:9de8|2606:4700:3035::6815:527d|172.67.157.232|104.21.82.125|via=obfsonly"
	for _, raw := range []string{
		"https://dns.dns-ai.ru/dns-query" + dnsBootstrap,
		"tls://dns.dns-ai.ru" + dnsBootstrap,
		"https://cf.dns-ai.ru/dns-query" + cfBootstrap,
	} {
		resetDNSRouteChoices()
		spec, err := parseDNSUpstream(raw)
		if err != nil {
			t.Fatal(err)
		}
		var ok int
		var durs []time.Duration
		for i := 0; i < 30; i++ {
			started := time.Now()
			// Бюджет запроса как у ядра: 2,5 с -> прямая попытка 1,25 с.
			payload, _, err := resolveDNSObfsOnly(query, spec, 2500*time.Millisecond)
			if err == nil && len(payload) >= 12 && payload[3]&0x0f == 0 {
				ok++
				durs = append(durs, time.Since(started))
			} else {
				t.Logf("  %s attempt %d: %v", spec.display, i, err)
			}
			time.Sleep(150 * time.Millisecond)
		}
		sort.Slice(durs, func(i, j int) bool { return durs[i] < durs[j] })
		med := time.Duration(0)
		if len(durs) > 0 {
			med = durs[len(durs)/2]
		}
		win, _ := recallObfsWinner(spec.raw)
		t.Logf("%s: %d/30, median %s, winner %q", spec.display, ok, med, win)
	}
}

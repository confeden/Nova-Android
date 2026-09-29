package main

import (
	"context"
	"crypto/x509"
	"fmt"
	"math"
	"net"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// ---------------------------------------------------------------------------
// reach — basic reachability per resolver
// ---------------------------------------------------------------------------

type ReachEntry struct {
	Iteration int      `json:"iteration"`
	Answered  bool     `json:"answered"`
	RCode     string   `json:"rcode,omitempty"`
	RA        bool     `json:"ra"`
	Answers   []string `json:"answers,omitempty"`
	RTTMS     float64  `json:"rtt_ms"`
	Reason    string   `json:"reason,omitempty"`
}

type ReachResult struct {
	Timestamp string       `json:"timestamp"`
	Resolver  string       `json:"resolver"`
	Transport string       `json:"transport"`
	Repeat    int          `json:"repeat"`
	Entries   []ReachEntry `json:"entries"`
	Answered  int          `json:"answered"`
	Timeouts  int          `json:"timeouts"`
	Refused   int          `json:"refused"`
	ServFail  int          `json:"servfail"`
	LossPct   float64      `json:"loss_pct"`
	MinMS     float64      `json:"min_ms"`
	MedianMS  float64      `json:"median_ms"`
	P95MS     float64      `json:"p95_ms"`
}

func ProbeReach(ctx context.Context, tr Transport, resolver string, repeat int, timeout time.Duration) ReachResult {
	res := ReachResult{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Resolver:  resolver,
		Transport: tr.Name(),
		Repeat:    repeat,
	}
	var rtts []float64
	for i := 0; i < repeat; i++ {
		qctx, cancel := context.WithTimeout(ctx, timeout)
		q := NewQuery("example.com", TypeA)
		q.AddEDNS(4096)
		qr := tr.Query(qctx, q, resolver)
		cancel()

		entry := ReachEntry{Iteration: i + 1}
		entry.RTTMS = float64(qr.RTT.Microseconds()) / 1000.0
		if qr.Err != nil {
			entry.Reason = fmt.Sprintf("%s: %v", qr.ErrStage, qr.Err)
			if isTimeout(qr.Err) {
				res.Timeouts++
			}
		} else if qr.Msg != nil {
			rc := qr.Msg.FullRCode()
			entry.RCode = rcodeName(rc)
			entry.RA = qr.Msg.Header.RecursionAvail()
			entry.Answered = true
			rtts = append(rtts, entry.RTTMS)
			for _, a := range qr.Msg.Answers {
				if a.AAddr != "" {
					entry.Answers = append(entry.Answers, a.AAddr)
				} else if a.AAAAAddr != "" {
					entry.Answers = append(entry.Answers, a.AAAAAddr)
				}
			}
			if rc == 5 {
				res.Refused++
			} else if rc == 2 {
				res.ServFail++
			}
		} else {
			entry.Reason = "no response parsed and no error"
		}
		res.Entries = append(res.Entries, entry)
	}
	res.Answered = len(rtts)
	if repeat > 0 {
		res.LossPct = 100.0 * float64(repeat-res.Answered) / float64(repeat)
	}
	if len(rtts) > 0 {
		sort.Float64s(rtts)
		res.MinMS = rtts[0]
		res.MedianMS = percentile(rtts, 50)
		res.P95MS = percentile(rtts, 95)
	}
	return res
}

// ---------------------------------------------------------------------------
// recurse — proves the resolver actually recurses
// ---------------------------------------------------------------------------

type RecurseEchoResult struct {
	Name    string   `json:"name"`
	QType   string   `json:"qtype"`
	RCode   string   `json:"rcode,omitempty"`
	Answers []string `json:"answers,omitempty"`
	Egress  string   `json:"egress,omitempty"`
	RTTMS   float64  `json:"rtt_ms"`
	Reason  string   `json:"reason,omitempty"`
}

type RecurseResult struct {
	Timestamp   string              `json:"timestamp"`
	Resolver    string              `json:"resolver"`
	Transport   string              `json:"transport"`
	Zone        string              `json:"zone"`
	RandomLabel string              `json:"random_label"`
	RandomQuery RecurseEchoResult   `json:"random_query"`
	EchoResults []RecurseEchoResult `json:"echo_results"`
}

func ProbeRecurse(ctx context.Context, tr Transport, resolver, zone string, timeout time.Duration) RecurseResult {
	res := RecurseResult{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Resolver:  resolver,
		Transport: tr.Name(),
		Zone:      zone,
	}

	// (a) Random label under zone — proves recursion to zone's auth
	label := RandomLabel(12)
	res.RandomLabel = label
	name := label + "." + strings.TrimSuffix(zone, ".")
	res.RandomQuery = doEchoQuery(ctx, tr, resolver, name, TypeA, timeout)

	// (b) Built-in echo names
	echoNames := []struct {
		name  string
		qtype uint16
	}{
		{"whoami.akamai.net", TypeA},
		{"o-o.myaddr.l.google.com", TypeTXT},
		{"whoami.ds.akahelp.net", TypeTXT},
		{"resolver.dnscrypt.info", TypeTXT},
	}
	for _, e := range echoNames {
		er := doEchoQuery(ctx, tr, resolver, e.name, e.qtype, timeout)
		// Extract egress address from known echo responses
		if len(er.Answers) > 0 {
			er.Egress = er.Answers[0]
		}
		res.EchoResults = append(res.EchoResults, er)
	}
	return res
}

func doEchoQuery(ctx context.Context, tr Transport, resolver, name string, qtype uint16, timeout time.Duration) RecurseEchoResult {
	qctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	q := NewQuery(name, qtype)
	q.AddEDNS(4096)
	qr := tr.Query(qctx, q, resolver)
	er := RecurseEchoResult{
		Name:  name,
		QType: typeName(qtype),
		RTTMS: float64(qr.RTT.Microseconds()) / 1000.0,
	}
	if qr.Err != nil {
		er.Reason = fmt.Sprintf("%s: %v", qr.ErrStage, qr.Err)
		return er
	}
	if qr.Msg != nil {
		er.RCode = rcodeName(qr.Msg.FullRCode())
		for _, a := range qr.Msg.Answers {
			if a.AAddr != "" {
				er.Answers = append(er.Answers, a.AAddr)
			} else if a.AAAAAddr != "" {
				er.Answers = append(er.Answers, a.AAAAAddr)
			}
			for _, t := range a.TXTData {
				er.Answers = append(er.Answers, t)
			}
		}
	}
	return er
}

// ---------------------------------------------------------------------------
// qname — QNAME length ladder
// ---------------------------------------------------------------------------

type QNameStep struct {
	PayloadBytes int     `json:"payload_bytes"`
	QNameLength  int     `json:"qname_length"`
	Responded    bool    `json:"responded"`
	RCode        string  `json:"rcode,omitempty"`
	RTTMS        float64 `json:"rtt_ms"`
	Reason       string  `json:"reason,omitempty"`
}

type QNameResult struct {
	Timestamp  string      `json:"timestamp"`
	Resolver   string      `json:"resolver"`
	Transport  string      `json:"transport"`
	Zone       string      `json:"zone"`
	Steps      []QNameStep `json:"steps"`
	MaxPayload int         `json:"max_payload"`
}

func ProbeQName(ctx context.Context, tr Transport, resolver, zone string, timeout time.Duration) QNameResult {
	res := QNameResult{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Resolver:  resolver,
		Transport: tr.Name(),
		Zone:      zone,
	}
	payloads := []int{30, 60, 90, 120, 150, 180, 210, 240}
	for _, pl := range payloads {
		step := QNameStep{PayloadBytes: pl}
		payload := make([]byte, pl)
		for i := range payload {
			payload[i] = byte(i % 256)
		}
		qname, err := Base32LabelSplit(payload, zone)
		if err != nil {
			step.Reason = fmt.Sprintf("qname_build: %v", err)
			res.Steps = append(res.Steps, step)
			continue
		}
		// Calculate encoded QNAME length
		encoded, encErr := encodeName(qname)
		if encErr != nil {
			step.Reason = fmt.Sprintf("qname_encode: %v", encErr)
			res.Steps = append(res.Steps, step)
			continue
		}
		step.QNameLength = len(encoded)

		qctx, cancel := context.WithTimeout(ctx, timeout)
		q := NewQuery(qname, TypeA)
		q.AddEDNS(4096)
		qr := tr.Query(qctx, q, resolver)
		cancel()

		step.RTTMS = float64(qr.RTT.Microseconds()) / 1000.0
		if qr.Err != nil {
			step.Reason = fmt.Sprintf("%s: %v", qr.ErrStage, qr.Err)
		} else if qr.Msg != nil {
			step.Responded = true
			step.RCode = rcodeName(qr.Msg.FullRCode())
			res.MaxPayload = pl
		} else {
			step.Reason = "no response parsed and no error"
		}
		res.Steps = append(res.Steps, step)
	}
	return res
}

// ---------------------------------------------------------------------------
// ednssize — downstream EDNS UDP payload ladder
// ---------------------------------------------------------------------------

type EDNSSizeStep struct {
	AdvertisedSize int     `json:"advertised_size"`
	ResponseSize   int     `json:"response_size"`
	TCSet          bool    `json:"tc_set"`
	TXTCount       int     `json:"txt_count"`
	RTTMS          float64 `json:"rtt_ms"`
	Reason         string  `json:"reason,omitempty"`
}

type EDNSSizeResult struct {
	Timestamp     string         `json:"timestamp"`
	Resolver      string         `json:"resolver"`
	Transport     string         `json:"transport"`
	BigName       string         `json:"bigname"`
	Steps         []EDNSSizeStep `json:"steps"`
	MaxUsefulSize int            `json:"max_useful_size"`
}

func ProbeEDNSSize(ctx context.Context, tr Transport, resolver, bigname string, timeout time.Duration) EDNSSizeResult {
	res := EDNSSizeResult{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Resolver:  resolver,
		Transport: tr.Name(),
		BigName:   bigname,
	}
	sizes := []int{512, 1232, 1400, 2048, 4096}
	for _, sz := range sizes {
		step := EDNSSizeStep{AdvertisedSize: sz}
		qctx, cancel := context.WithTimeout(ctx, timeout)
		q := NewQuery(bigname, TypeTXT)
		q.AddEDNS(uint16(sz))
		qr := tr.Query(qctx, q, resolver)
		cancel()

		step.RTTMS = float64(qr.RTT.Microseconds()) / 1000.0
		if qr.Err != nil {
			step.Reason = fmt.Sprintf("%s: %v", qr.ErrStage, qr.Err)
		} else if qr.Msg != nil {
			step.ResponseSize = qr.RespSize
			step.TCSet = qr.Msg.Header.IsTruncated()
			for _, a := range qr.Msg.Answers {
				if a.Type == TypeTXT {
					step.TXTCount += len(a.TXTData)
				}
			}
			if step.ResponseSize > res.MaxUsefulSize {
				res.MaxUsefulSize = step.ResponseSize
			}
		} else {
			step.Reason = "no response parsed and no error"
		}
		res.Steps = append(res.Steps, step)
	}
	return res
}

// ---------------------------------------------------------------------------
// rate — sustained query rate
// ---------------------------------------------------------------------------

type RateBucket struct {
	Second   int `json:"second"`
	Success  int `json:"success"`
	Errors   int `json:"errors"`
	Timeouts int `json:"timeouts"`
}

type RateRampStep struct {
	Concurrency int          `json:"concurrency"`
	QPS         float64      `json:"qps"`
	Buckets     []RateBucket `json:"buckets"`
	MinMS       float64      `json:"min_ms"`
	MedianMS    float64      `json:"median_ms"`
	P95MS       float64      `json:"p95_ms"`
}

type RateResult struct {
	Timestamp   string         `json:"timestamp"`
	Resolver    string         `json:"resolver"`
	Transport   string         `json:"transport"`
	Zone        string         `json:"zone"`
	Concurrency int            `json:"concurrency"`
	DurationSec int            `json:"duration_sec"`
	Ramp        bool           `json:"ramp"`
	QPS         float64        `json:"qps,omitempty"`
	Buckets     []RateBucket   `json:"buckets,omitempty"`
	MinMS       float64        `json:"min_ms,omitempty"`
	MedianMS    float64        `json:"median_ms,omitempty"`
	P95MS       float64        `json:"p95_ms,omitempty"`
	RampSteps   []RateRampStep `json:"ramp_steps,omitempty"`
	Reason      string         `json:"reason,omitempty"`
}

func ProbeRate(ctx context.Context, tr Transport, resolver, zone string, concurrency, durationSec int, ramp bool, timeout time.Duration) RateResult {
	res := RateResult{
		Timestamp:   time.Now().UTC().Format(time.RFC3339),
		Resolver:    resolver,
		Transport:   tr.Name(),
		Zone:        zone,
		Concurrency: concurrency,
		DurationSec: durationSec,
		Ramp:        ramp,
	}
	if ramp {
		rampLevels := []int{1, 2, 4, 8, 16, 32}
		for _, c := range rampLevels {
			step := runRateBurst(ctx, tr, resolver, zone, c, durationSec, timeout)
			res.RampSteps = append(res.RampSteps, step)
		}
	} else {
		step := runRateBurst(ctx, tr, resolver, zone, concurrency, durationSec, timeout)
		res.QPS = step.QPS
		res.Buckets = step.Buckets
		res.MinMS = step.MinMS
		res.MedianMS = step.MedianMS
		res.P95MS = step.P95MS
	}
	return res
}

func runRateBurst(ctx context.Context, tr Transport, resolver, zone string, concurrency, durationSec int, timeout time.Duration) RateRampStep {
	dur := time.Duration(durationSec) * time.Second
	deadline := time.Now().Add(dur)
	startTime := time.Now()

	var mu sync.Mutex
	bucketMap := make(map[int]*RateBucket)
	var allRTTs []float64
	var totalQueries int64

	var wg sync.WaitGroup
	for w := 0; w < concurrency; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for time.Now().Before(deadline) {
				if ctx.Err() != nil {
					return
				}
				label := RandomLabel(12)
				name := label + "." + strings.TrimSuffix(zone, ".")
				qctx, cancel := context.WithTimeout(ctx, timeout)
				q := NewQuery(name, TypeA)
				q.AddEDNS(4096)
				qr := tr.Query(qctx, q, resolver)
				cancel()

				sec := int(time.Since(startTime).Seconds())
				atomic.AddInt64(&totalQueries, 1)

				mu.Lock()
				b, ok := bucketMap[sec]
				if !ok {
					b = &RateBucket{Second: sec}
					bucketMap[sec] = b
				}
				rttMS := float64(qr.RTT.Microseconds()) / 1000.0
				if qr.Err != nil {
					if isTimeout(qr.Err) {
						b.Timeouts++
					} else {
						b.Errors++
					}
				} else {
					b.Success++
					allRTTs = append(allRTTs, rttMS)
				}
				mu.Unlock()
			}
		}()
	}
	wg.Wait()

	var buckets []RateBucket
	for i := 0; i < durationSec+1; i++ {
		if b, ok := bucketMap[i]; ok {
			buckets = append(buckets, *b)
		}
	}

	step := RateRampStep{
		Concurrency: concurrency,
		Buckets:     buckets,
	}
	elapsed := time.Since(startTime).Seconds()
	if elapsed > 0 {
		step.QPS = float64(totalQueries) / elapsed
	}
	if len(allRTTs) > 0 {
		sort.Float64s(allRTTs)
		step.MinMS = allRTTs[0]
		step.MedianMS = percentile(allRTTs, 50)
		step.P95MS = percentile(allRTTs, 95)
	}
	return step
}

// ---------------------------------------------------------------------------
// goodput — theoretical tunnel goodput calculation
// ---------------------------------------------------------------------------

type GoodputResult struct {
	Timestamp        string  `json:"timestamp"`
	Resolver         string  `json:"resolver"`
	Transport        string  `json:"transport"`
	UpstreamBudget   int     `json:"upstream_budget_bytes"`
	DownstreamBudget int     `json:"downstream_budget_bytes"`
	QPS              float64 `json:"qps"`
	UpstreamKBps     float64 `json:"upstream_kbps"`
	DownstreamKBps   float64 `json:"downstream_kbps"`
	FormulaUp        string  `json:"formula_upstream"`
	FormulaDown      string  `json:"formula_downstream"`
	Reason           string  `json:"reason,omitempty"`
}

func ProbeGoodput(ctx context.Context, tr Transport, resolver, zone, bigname string,
	concurrency, durationSec int, timeout time.Duration,
	qnameRes *QNameResult, ednsRes *EDNSSizeResult, rateRes *RateResult) GoodputResult {

	res := GoodputResult{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Resolver:  resolver,
		Transport: tr.Name(),
	}

	// Run prerequisite probes if not supplied
	if qnameRes == nil {
		r := ProbeQName(ctx, tr, resolver, zone, timeout)
		qnameRes = &r
	}
	if ednsRes == nil {
		r := ProbeEDNSSize(ctx, tr, resolver, bigname, timeout)
		ednsRes = &r
	}
	if rateRes == nil {
		r := ProbeRate(ctx, tr, resolver, zone, concurrency, durationSec, false, timeout)
		rateRes = &r
	}

	res.UpstreamBudget = qnameRes.MaxPayload
	res.DownstreamBudget = ednsRes.MaxUsefulSize
	res.QPS = rateRes.QPS

	if res.UpstreamBudget == 0 {
		res.Reason = "qname probe returned 0 max payload"
		return res
	}
	if res.DownstreamBudget == 0 {
		res.Reason = "ednssize probe returned 0 max useful size"
		return res
	}
	if res.QPS == 0 {
		res.Reason = "rate probe returned 0 qps"
		return res
	}

	// Upstream: each query carries UpstreamBudget bytes of payload in the QNAME.
	// Downstream: each response carries DownstreamBudget bytes.
	// Over a TXT tunnel, roughly 75% of downstream is usable data (base64/base32 overhead).
	// Upstream encoding overhead: base32 expands 5 bytes to 8 chars, so usable = budget * 5/8.
	upUsable := float64(res.UpstreamBudget) * 5.0 / 8.0
	downUsable := float64(res.DownstreamBudget) * 0.75

	res.UpstreamKBps = upUsable * res.QPS / 1024.0
	res.DownstreamKBps = downUsable * res.QPS / 1024.0

	res.FormulaUp = fmt.Sprintf("upstream_kbps = (upstream_budget_bytes=%d * 5/8) * qps=%.1f / 1024 = %.2f",
		res.UpstreamBudget, res.QPS, res.UpstreamKBps)
	res.FormulaDown = fmt.Sprintf("downstream_kbps = (downstream_budget_bytes=%d * 0.75) * qps=%.1f / 1024 = %.2f",
		res.DownstreamBudget, res.QPS, res.DownstreamKBps)

	return res
}

// ---------------------------------------------------------------------------
// tamper — compare answers between target and reference resolver
// ---------------------------------------------------------------------------

type TamperEntry struct {
	Name             string   `json:"name"`
	QType            string   `json:"qtype"`
	TargetRCode      string   `json:"target_rcode,omitempty"`
	ReferenceRCode   string   `json:"reference_rcode,omitempty"`
	TargetAnswers    []string `json:"target_answers,omitempty"`
	ReferenceAnswers []string `json:"reference_answers,omitempty"`
	TargetTTL        uint32   `json:"target_ttl,omitempty"`
	ReferenceTTL     uint32   `json:"reference_ttl,omitempty"`
	Tampered         bool     `json:"tampered"`
	TamperReason     string   `json:"tamper_reason,omitempty"`
	RTTMS            float64  `json:"rtt_ms"`
	Reason           string   `json:"reason,omitempty"`
}

type TamperResult struct {
	Timestamp string        `json:"timestamp"`
	Resolver  string        `json:"resolver"`
	Reference string        `json:"reference"`
	Transport string        `json:"transport"`
	Entries   []TamperEntry `json:"entries"`
}

func ProbeTamper(ctx context.Context, tr Transport, resolver, reference string, timeout time.Duration) TamperResult {
	res := TamperResult{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Resolver:  resolver,
		Reference: reference,
		Transport: tr.Name(),
	}
	names := []struct {
		name  string
		qtype uint16
	}{
		{"example.com", TypeA},
		{"google.com", TypeA},
		{"facebook.com", TypeA},
		{"twitter.com", TypeA},
	}
	for _, n := range names {
		entry := TamperEntry{Name: n.name, QType: typeName(n.qtype)}

		// Query target
		qctx1, cancel1 := context.WithTimeout(ctx, timeout)
		q1 := NewQuery(n.name, n.qtype)
		q1.AddEDNS(4096)
		qr1 := tr.Query(qctx1, q1, resolver)
		cancel1()

		entry.RTTMS = float64(qr1.RTT.Microseconds()) / 1000.0

		// Query reference
		qctx2, cancel2 := context.WithTimeout(ctx, timeout)
		q2 := NewQuery(n.name, n.qtype)
		q2.AddEDNS(4096)
		qr2 := tr.Query(qctx2, q2, reference)
		cancel2()

		if qr1.Err != nil {
			entry.Reason = fmt.Sprintf("target: %s: %v", qr1.ErrStage, qr1.Err)
			res.Entries = append(res.Entries, entry)
			continue
		}
		if qr2.Err != nil {
			entry.Reason = fmt.Sprintf("reference: %s: %v", qr2.ErrStage, qr2.Err)
			res.Entries = append(res.Entries, entry)
			continue
		}
		if qr1.Msg == nil || qr2.Msg == nil {
			entry.Reason = "no response parsed"
			res.Entries = append(res.Entries, entry)
			continue
		}

		entry.TargetRCode = rcodeName(qr1.Msg.FullRCode())
		entry.ReferenceRCode = rcodeName(qr2.Msg.FullRCode())
		entry.TargetAnswers = extractAnswers(qr1.Msg)
		entry.ReferenceAnswers = extractAnswers(qr2.Msg)

		if len(qr1.Msg.Answers) > 0 {
			entry.TargetTTL = qr1.Msg.Answers[0].TTL
		}
		if len(qr2.Msg.Answers) > 0 {
			entry.ReferenceTTL = qr2.Msg.Answers[0].TTL
		}

		// Compare rcodes
		if entry.TargetRCode != entry.ReferenceRCode {
			entry.Tampered = true
			entry.TamperReason = fmt.Sprintf("rcode mismatch: target=%s reference=%s", entry.TargetRCode, entry.ReferenceRCode)
		}

		// Compare answer sets
		if !entry.Tampered && !sameAnswers(entry.TargetAnswers, entry.ReferenceAnswers) {
			entry.Tampered = true
			entry.TamperReason = "answer set differs"
		}

		res.Entries = append(res.Entries, entry)
	}
	return res
}

func extractAnswers(msg *DNSMessage) []string {
	var out []string
	for _, a := range msg.Answers {
		if a.AAddr != "" {
			out = append(out, a.AAddr)
		} else if a.AAAAAddr != "" {
			out = append(out, a.AAAAAddr)
		} else if a.CName != "" {
			out = append(out, "CNAME:"+a.CName)
		}
		for _, t := range a.TXTData {
			out = append(out, "TXT:"+t)
		}
	}
	return out
}

func sameAnswers(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	sa := make([]string, len(a))
	sb := make([]string, len(b))
	copy(sa, a)
	copy(sb, b)
	sort.Strings(sa)
	sort.Strings(sb)
	for i := range sa {
		if sa[i] != sb[i] {
			return false
		}
	}
	return true
}

// ---------------------------------------------------------------------------
// doh-reach — stage-precise DoH/DoT reachability
// ---------------------------------------------------------------------------

type DoHReachProbeResult struct {
	Timestamp string           `json:"timestamp"`
	Transport string           `json:"transport"`
	Results   []DoHReachResult `json:"results"`
}

func ProbeDoHReach(ctx context.Context, endpoints []string, timeout time.Duration, insecure bool, resolver *net.Resolver, rootCAs *x509.CertPool, trustStoreEmpty bool) DoHReachProbeResult {
	res := DoHReachProbeResult{
		Timestamp: time.Now().UTC().Format(time.RFC3339),
		Transport: "doh/dot",
	}
	prober := &DoHReachTransport{Timeout: timeout, Insecure: insecure, Resolver: resolver, RootCAs: rootCAs, TrustStoreEmpty: trustStoreEmpty}

	for _, ep := range endpoints {
		ep = strings.TrimSpace(ep)
		if ep == "" {
			continue
		}
		if strings.HasPrefix(ep, "https://") {
			r := prober.ProbeDoH(ctx, ep)
			res.Results = append(res.Results, r)
		} else if strings.HasPrefix(ep, "dot://") {
			// dot://host or dot://host:port -> DoT
			hostPort := strings.TrimPrefix(ep, "dot://")
			host, port := splitHostPort(hostPort, "853")
			r := prober.ProbeDoT(ctx, host, port)
			r.URL = ep // preserve original endpoint in output
			res.Results = append(res.Results, r)
		} else if strings.Contains(ep, "://") {
			// Unknown scheme -> bad_endpoint
			res.Results = append(res.Results, DoHReachResult{
				URL:        ep,
				Protocol:   "unknown",
				FailStage:  "bad_endpoint",
				FailReason: fmt.Sprintf("unsupported scheme in %q", ep),
			})
		} else {
			// Bare host or host:port -> DoT on 853
			host, port := splitHostPort(ep, "853")
			r := prober.ProbeDoT(ctx, host, port)
			r.URL = ep
			res.Results = append(res.Results, r)
		}
	}
	return res
}

// splitHostPort splits a host or host:port string. If no port is present, defaultPort is used.
func splitHostPort(hostPort, defaultPort string) (host, port string) {
	// net.SplitHostPort requires a port; try it first.
	h, p, err := net.SplitHostPort(hostPort)
	if err == nil {
		return h, p
	}
	// No port — return as-is with default.
	return hostPort, defaultPort
}

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------

func percentile(sorted []float64, pct float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	rank := pct / 100.0 * float64(len(sorted)-1)
	lower := int(math.Floor(rank))
	upper := int(math.Ceil(rank))
	if lower == upper || upper >= len(sorted) {
		return sorted[lower]
	}
	frac := rank - float64(lower)
	return sorted[lower]*(1-frac) + sorted[upper]*frac
}

func isTimeout(err error) bool {
	if err == nil {
		return false
	}
	s := err.Error()
	return strings.Contains(s, "i/o timeout") ||
		strings.Contains(s, "deadline exceeded") ||
		strings.Contains(s, "context deadline exceeded")
}

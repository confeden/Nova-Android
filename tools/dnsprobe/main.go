package main

import (
	"bufio"
	"context"
	"crypto/x509"
	"encoding/json"
	"flag"
	"fmt"
	"net"
	"net/netip"
	"os"
	"os/exec"
	"regexp"
	"runtime"
	"strings"
	"time"
)

func main() {
	probe := flag.String("probe", "", "probe name: reach, recurse, qname, ednssize, rate, goodput, tamper, doh-reach")
	resolvers := flag.String("resolvers", "", "comma-separated resolver IPs, @file, or 'auto'")
	zone := flag.String("zone", "example.com", "zone for random-label probes")
	// google.com TXT is ~927 B of data -> a 1187 B UDP response at an advertised 1232, which is the
	// realistic downstream ceiling. Do NOT use microsoft.com (3779 B of TXT): 8.8.8.8 caps its own
	// UDP answers around 1232 and returns TC at every advertised size, which reads as a broken path.
	bigname := flag.String("bigname", "google.com", "domain with large TXT for ednssize")
	reference := flag.String("reference", "1.1.1.1", "reference resolver for tamper probe")
	endpoints := flag.String("endpoints", "", "comma-separated DoH URLs / DoT hosts, or @file")
	transport := flag.String("transport", "udp", "transport: udp, tcp, dot, doh, doh-json")
	timeout := flag.Duration("timeout", 5*time.Second, "per-query timeout")
	repeat := flag.Int("repeat", 5, "repetitions for reach probe")
	concurrency := flag.Int("concurrency", 1, "concurrent workers for rate probe")
	duration := flag.Duration("duration", 10*time.Second, "duration for rate probe")
	ramp := flag.Bool("ramp", false, "ramp concurrency for rate probe")
	insecure := flag.Bool("insecure", false, "skip TLS cert verification")
	sni := flag.String("sni", "", "explicit TLS SNI")
	jsonOut := flag.Bool("json", false, "emit JSON output")
	outFile := flag.String("out", "", "write JSON to file (still prints table)")
	verbose := flag.Bool("v", false, "verbose output")
	bootstrap := flag.String("bootstrap", "", "IP address for resolving endpoint hostnames (default: first -resolvers IP, or 8.8.8.8)")
	cadir := flag.String("cadir", "", "comma-separated CA cert directories (default: auto-detect system + Android)")
	flag.Parse()

	if *probe == "" {
		fmt.Fprintf(os.Stderr, "error: -probe is required\n")
		flag.Usage()
		os.Exit(1)
	}

	// For doh-reach, resolvers are optional
	if *probe != "doh-reach" && *resolvers == "" {
		fmt.Fprintf(os.Stderr, "error: -resolvers is required\n")
		flag.Usage()
		os.Exit(1)
	}

	// Parse resolvers
	var resolverList []string
	if *resolvers != "" {
		resolverList = parseList(*resolvers)
	}

	// Build TLS trust store.
	var caDirs []string
	if *cadir != "" {
		for _, d := range strings.Split(*cadir, ",") {
			d = strings.TrimSpace(d)
			if d != "" {
				caDirs = append(caDirs, d)
			}
		}
	}
	ts := LoadTrustStore(caDirs)
	PrintTrustStoreStatus(ts)

	var rootCAs *x509.CertPool
	if !ts.Empty {
		rootCAs = ts.Pool
	}

	opts := TransportOpts{
		Timeout:         *timeout,
		Insecure:        *insecure,
		SNI:             *sni,
		RootCAs:         rootCAs,
		TrustStoreEmpty: ts.Empty,
	}
	tr, err := NewTransport(*transport, opts)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}

	ctx := context.Background()

	var result interface{}

	switch *probe {
	case "reach":
		var results []ReachResult
		for _, r := range resolverList {
			rr := ProbeReach(ctx, tr, r, *repeat, *timeout)
			results = append(results, rr)
		}
		result = results
		if !*jsonOut {
			printReachTable(results)
		}

	case "recurse":
		var results []RecurseResult
		for _, r := range resolverList {
			rr := ProbeRecurse(ctx, tr, r, *zone, *timeout)
			results = append(results, rr)
		}
		result = results
		if !*jsonOut {
			printRecurseTable(results)
		}

	case "qname":
		var results []QNameResult
		for _, r := range resolverList {
			rr := ProbeQName(ctx, tr, r, *zone, *timeout)
			results = append(results, rr)
		}
		result = results
		if !*jsonOut {
			printQNameTable(results)
		}

	case "ednssize":
		var results []EDNSSizeResult
		for _, r := range resolverList {
			rr := ProbeEDNSSize(ctx, tr, r, *bigname, *timeout)
			results = append(results, rr)
		}
		result = results
		if !*jsonOut {
			printEDNSSizeTable(results)
		}

	case "rate":
		if len(resolverList) == 0 {
			fmt.Fprintf(os.Stderr, "error: rate probe requires at least one resolver\n")
			os.Exit(1)
		}
		rr := ProbeRate(ctx, tr, resolverList[0], *zone, *concurrency, int(duration.Seconds()), *ramp, *timeout)
		result = rr
		if !*jsonOut {
			printRateTable(rr)
		}

	case "goodput":
		if len(resolverList) == 0 {
			fmt.Fprintf(os.Stderr, "error: goodput probe requires at least one resolver\n")
			os.Exit(1)
		}
		rr := ProbeGoodput(ctx, tr, resolverList[0], *zone, *bigname, *concurrency, int(duration.Seconds()), *timeout, nil, nil, nil)
		result = rr
		if !*jsonOut {
			printGoodputTable(rr)
		}

	case "tamper":
		var results []TamperResult
		for _, r := range resolverList {
			rr := ProbeTamper(ctx, tr, r, *reference, *timeout)
			results = append(results, rr)
		}
		result = results
		if !*jsonOut {
			printTamperTable(results)
		}

	case "doh-reach":
		if *endpoints == "" {
			fmt.Fprintf(os.Stderr, "error: -endpoints is required for doh-reach probe\n")
			os.Exit(1)
		}
		epList := parseList(*endpoints)

		// Determine bootstrap DNS server.
		bootstrapIP := *bootstrap
		if bootstrapIP == "" {
			// Default: first plain-IP resolver, or 8.8.8.8.
			for _, r := range resolverList {
				if net.ParseIP(r) != nil {
					bootstrapIP = r
					break
				}
			}
			if bootstrapIP == "" {
				bootstrapIP = "8.8.8.8"
			}
		}
		bootstrapResolver := &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
				d := net.Dialer{Timeout: *timeout}
				return d.DialContext(ctx, "udp", net.JoinHostPort(bootstrapIP, "53"))
			},
		}

		rr := ProbeDoHReach(ctx, epList, *timeout, *insecure, bootstrapResolver, rootCAs, ts.Empty)
		result = rr
		if !*jsonOut {
			printDoHReachTable(rr)
		}

	default:
		fmt.Fprintf(os.Stderr, "error: unknown probe %q\n", *probe)
		os.Exit(1)
	}

	_ = verbose

	jsonBytes, err := json.MarshalIndent(result, "", "  ")
	if err != nil {
		fmt.Fprintf(os.Stderr, "error marshaling JSON: %v\n", err)
		os.Exit(1)
	}

	if *jsonOut {
		fmt.Println(string(jsonBytes))
	}
	if *outFile != "" {
		if err := os.WriteFile(*outFile, jsonBytes, 0644); err != nil {
			fmt.Fprintf(os.Stderr, "error writing %s: %v\n", *outFile, err)
			os.Exit(1)
		}
	}
}

// parseList handles "a,b,c", "@file" (one item per line), or "auto".
func parseList(s string) []string {
	if s == "auto" {
		list := autoDetectResolvers()
		if len(list) == 0 {
			fmt.Fprintf(os.Stderr, "error: auto-detection found no resolvers. "+
				"Neither getprop net.dns1..4 nor dumpsys connectivity returned DNS server addresses.\n")
			os.Exit(1)
		}
		return list
	}
	if strings.HasPrefix(s, "@") {
		return readFileLines(s[1:])
	}
	var out []string
	for _, item := range strings.Split(s, ",") {
		item = strings.TrimSpace(item)
		if item != "" {
			out = append(out, item)
		}
	}
	return out
}

func readFileLines(path string) []string {
	f, err := os.Open(path)
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: cannot read %s: %v\n", path, err)
		os.Exit(1)
	}
	defer f.Close()
	var lines []string
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line != "" && !strings.HasPrefix(line, "#") {
			lines = append(lines, line)
		}
	}
	return lines
}

func autoDetectResolvers() []string {
	if runtime.GOOS != "android" && runtime.GOOS != "linux" {
		return nil
	}
	seen := make(map[string]bool)
	var resolvers []string
	add := func(s string) {
		s = strings.Trim(s, "[],/ 	")
		if s == "" || seen[s] || !isUsableResolver(s) {
			return
		}
		seen[s] = true
		resolvers = append(resolvers, s)
	}

	// net.dns1..4 was removed in Android 8.0 but still answers on some builds.
	for i := 1; i <= 4; i++ {
		out, err := exec.Command("getprop", fmt.Sprintf("net.dns%d", i)).Output()
		if err == nil {
			add(strings.TrimSpace(string(out)))
		}
	}

	// dumpsys connectivity. Only the DnsAddresses list is a resolver list; a
	// LinkProperties line on the same dump also carries the interface MAC and the
	// device's own address, and a loose token scan picks those up as "resolvers".
	out, err := exec.Command("dumpsys", "connectivity").Output()
	if err == nil {
		re := regexp.MustCompile(`DnsAddresses:\s*\[([^\]]*)\]`)
		for _, m := range re.FindAllStringSubmatch(string(out), -1) {
			for _, part := range strings.Split(m[1], ",") {
				add(part)
			}
		}
	}

	return resolvers
}

// isUsableResolver accepts only an address we could actually send a query to.
// Hand-rolled "looks like an IP" checks accept a MAC address as IPv6 (hex and
// colons), which is how a resolver list ends up holding f4:de:af:25:59:68.
func isUsableResolver(s string) bool {
	if i := strings.LastIndex(s, "%"); i > 0 {
		s = s[:i] // strip a zone such as fe80::1%wlan0
	}
	addr, err := netip.ParseAddr(s)
	if err != nil {
		return false
	}
	if addr.IsUnspecified() || addr.IsLoopback() || addr.IsLinkLocalUnicast() || addr.IsMulticast() {
		return false
	}
	return true
}

// ---------------------------------------------------------------------------
// Table printers
// ---------------------------------------------------------------------------

func printReachTable(results []ReachResult) {
	for _, r := range results {
		fmt.Printf("\n=== Reach: %s (%s) ===\n", r.Resolver, r.Transport)
		fmt.Printf("%-5s %-8s %-10s %-4s %-10s %s\n", "Iter", "RCode", "RTT(ms)", "RA", "Answers", "Reason")
		for _, e := range r.Entries {
			answers := strings.Join(e.Answers, ",")
			reason := e.Reason
			rcode := e.RCode
			if !e.Answered {
				rcode = "-"
			}
			fmt.Printf("%-5d %-8s %-10.2f %-4v %-10s %s\n", e.Iteration, rcode, e.RTTMS, e.RA, answers, reason)
		}
		fmt.Printf("Summary: %d/%d answered, loss=%.1f%%, min=%.2fms median=%.2fms p95=%.2fms\n",
			r.Answered, r.Repeat, r.LossPct, r.MinMS, r.MedianMS, r.P95MS)
		fmt.Printf("  timeouts=%d refused=%d servfail=%d\n", r.Timeouts, r.Refused, r.ServFail)
	}
}

func printRecurseTable(results []RecurseResult) {
	for _, r := range results {
		fmt.Printf("\n=== Recurse: %s (%s) ===\n", r.Resolver, r.Transport)
		fmt.Printf("Zone: %s, Random label: %s\n", r.Zone, r.RandomLabel)
		rq := r.RandomQuery
		fmt.Printf("  Random query: %s %s rcode=%s rtt=%.2fms answers=%v\n",
			rq.Name, rq.QType, rq.RCode, rq.RTTMS, rq.Answers)
		if rq.Reason != "" {
			fmt.Printf("    reason: %s\n", rq.Reason)
		}
		for _, e := range r.EchoResults {
			fmt.Printf("  Echo: %s %s rcode=%s rtt=%.2fms egress=%s answers=%v\n",
				e.Name, e.QType, e.RCode, e.RTTMS, e.Egress, e.Answers)
			if e.Reason != "" {
				fmt.Printf("    reason: %s\n", e.Reason)
			}
		}
	}
}

func printQNameTable(results []QNameResult) {
	for _, r := range results {
		fmt.Printf("\n=== QName Ladder: %s (%s) ===\n", r.Resolver, r.Transport)
		fmt.Printf("%-10s %-12s %-10s %-10s %-10s %s\n", "Payload", "QNAME Len", "Responded", "RCode", "RTT(ms)", "Reason")
		for _, s := range r.Steps {
			fmt.Printf("%-10d %-12d %-10v %-10s %-10.2f %s\n",
				s.PayloadBytes, s.QNameLength, s.Responded, s.RCode, s.RTTMS, s.Reason)
		}
		fmt.Printf("Max surviving payload: %d bytes\n", r.MaxPayload)
	}
}

func printEDNSSizeTable(results []EDNSSizeResult) {
	for _, r := range results {
		fmt.Printf("\n=== EDNS Size Ladder: %s (%s) ===\n", r.Resolver, r.Transport)
		fmt.Printf("%-12s %-12s %-6s %-10s %-10s %s\n", "Advertised", "Response", "TC", "TXT Count", "RTT(ms)", "Reason")
		for _, s := range r.Steps {
			fmt.Printf("%-12d %-12d %-6v %-10d %-10.2f %s\n",
				s.AdvertisedSize, s.ResponseSize, s.TCSet, s.TXTCount, s.RTTMS, s.Reason)
		}
		fmt.Printf("Max useful downstream: %d bytes\n", r.MaxUsefulSize)
	}
}

func printRateTable(r RateResult) {
	fmt.Printf("\n=== Rate: %s (%s) ===\n", r.Resolver, r.Transport)
	if r.Ramp {
		fmt.Printf("%-12s %-10s %-10s %-10s %-10s\n", "Concurrency", "QPS", "Min(ms)", "Median(ms)", "P95(ms)")
		for _, s := range r.RampSteps {
			fmt.Printf("%-12d %-10.1f %-10.2f %-10.2f %-10.2f\n",
				s.Concurrency, s.QPS, s.MinMS, s.MedianMS, s.P95MS)
		}
	} else {
		fmt.Printf("Concurrency: %d, Duration: %ds, QPS: %.1f\n", r.Concurrency, r.DurationSec, r.QPS)
		fmt.Printf("RTT: min=%.2fms median=%.2fms p95=%.2fms\n", r.MinMS, r.MedianMS, r.P95MS)
		fmt.Printf("%-8s %-8s %-8s %-8s\n", "Second", "Success", "Errors", "Timeouts")
		for _, b := range r.Buckets {
			fmt.Printf("%-8d %-8d %-8d %-8d\n", b.Second, b.Success, b.Errors, b.Timeouts)
		}
	}
}

func printGoodputTable(r GoodputResult) {
	fmt.Printf("\n=== Goodput: %s (%s) ===\n", r.Resolver, r.Transport)
	if r.Reason != "" {
		fmt.Printf("FAILED: %s\n", r.Reason)
		return
	}
	fmt.Printf("Upstream budget:   %d bytes/query\n", r.UpstreamBudget)
	fmt.Printf("Downstream budget: %d bytes/response\n", r.DownstreamBudget)
	fmt.Printf("Sustained QPS:     %.1f\n", r.QPS)
	fmt.Printf("Upstream:          %.2f KB/s\n", r.UpstreamKBps)
	fmt.Printf("Downstream:        %.2f KB/s\n", r.DownstreamKBps)
	fmt.Printf("Formula (up):      %s\n", r.FormulaUp)
	fmt.Printf("Formula (down):    %s\n", r.FormulaDown)
}

func printTamperTable(results []TamperResult) {
	for _, r := range results {
		fmt.Printf("\n=== Tamper: %s vs %s (%s) ===\n", r.Resolver, r.Reference, r.Transport)
		fmt.Printf("%-20s %-6s %-10s %-10s %-10s %s\n", "Name", "QType", "TgtRCode", "RefRCode", "Tampered", "Reason")
		for _, e := range r.Entries {
			fmt.Printf("%-20s %-6s %-10s %-10s %-10v %s\n",
				e.Name, e.QType, e.TargetRCode, e.ReferenceRCode, e.Tampered, e.TamperReason)
			if e.Reason != "" {
				fmt.Printf("  error: %s\n", e.Reason)
			}
		}
	}
}

func printDoHReachTable(r DoHReachProbeResult) {
	fmt.Printf("\n=== DoH/DoT Reach ===\n")
	fmt.Printf("%-40s %-8s %-8s %-18s %-20s %-10s %s\n", "Endpoint", "Proto", "OK", "ResolvedAddr", "FailStage", "RTT(ms)", "Reason")
	for _, e := range r.Results {
		fmt.Printf("%-40s %-8s %-8v %-18s %-20s %-10.2f %s\n",
			e.URL, e.Protocol, e.Success, e.ResolvedAddr, e.FailStage, e.RTTMS, e.FailReason)
	}
}

# dnsprobe

A standalone DNS diagnostic tool for Android. Probes resolver reachability,
recursion behaviour, QNAME length limits, EDNS downstream capacity, sustained
query rate, theoretical DNS-tunnel goodput, answer tampering, and DoH/DoT
endpoint availability.

**Standard library only — zero external dependencies.**

## Build

### Windows (native, for testing)

```
go build -o dnsprobe.exe .
```

### Android arm64 (cross-compile)

Both targets were verified to build successfully with Go 1.26:

```powershell
# Preferred: GOOS=android — produces a binary linked for Bionic.
$env:CGO_ENABLED='0'; $env:GOOS='android'; $env:GOARCH='arm64'
go build -o dnsprobe-arm64 .

# Alternative: GOOS=linux also works because Android is Linux.
$env:CGO_ENABLED='0'; $env:GOOS='linux'; $env:GOARCH='arm64'
go build -o dnsprobe-arm64 .
```

Both commands exit 0 with no errors. `GOOS=android` is preferred because
the Go runtime can detect Bionic-specific behaviour at runtime.

## Deploy to Android

```
adb push dnsprobe-arm64 /data/local/tmp/dnsprobe
adb shell chmod 755 /data/local/tmp/dnsprobe
```

## Usage

### Probes

Each probe is selected with `-probe <name>`. Resolvers are given with
`-resolvers` (comma-separated, `@file`, or `auto`).

#### reach — basic reachability

```
./dnsprobe -probe reach -resolvers 8.8.8.8,1.1.1.1 -repeat 5 -json
```

Reports per-iteration answered/timeout/REFUSED/SERVFAIL, rcode, RA bit,
answers, and min/median/p95 RTT plus loss percentage.

#### recurse — proves recursion

```
./dnsprobe -probe recurse -resolvers 8.8.8.8 -zone example.com -json
```

Sends a random 12-char label under `-zone` (no cache can answer it) and
queries built-in echo names (`whoami.akamai.net`, etc.) to reveal the
resolver's egress address.

#### qname — QNAME length ladder

```
./dnsprobe -probe qname -resolvers 8.8.8.8 -zone example.com -json
```

Sends queries carrying 30, 60, 90, …, 240 bytes of random payload encoded
as base32 labels under `-zone`. Reports the largest payload size that
survived the path (got any response, including NXDOMAIN/SERVFAIL).

#### ednssize — downstream EDNS buffer ladder

```
./dnsprobe -probe ednssize -resolvers 8.8.8.8 -bigname microsoft.com -json
```

Queries `-bigname` (TXT) with advertised EDNS UDP payload sizes 512, 1232,
1400, 2048, 4096. Reports response size, TC bit, and TXT record count per
step.

#### rate — sustained query rate

```
./dnsprobe -probe rate -resolvers 8.8.8.8 -zone example.com -concurrency 4 -duration 10s -json
./dnsprobe -probe rate -resolvers 8.8.8.8 -zone example.com -ramp -duration 5s -json
```

Measures queries/second with per-second buckets of success/error/timeout
counts. With `-ramp`, steps concurrency 1→2→4→8→16→32 and reports where
QPS stops growing.

#### goodput — theoretical tunnel goodput

```
./dnsprobe -probe goodput -resolvers 8.8.8.8 -zone example.com -bigname microsoft.com -json
```

Runs `qname`, `ednssize`, and `rate` probes internally, then computes
theoretical upstream and downstream tunnel throughput in KB/s.

#### tamper — answer comparison

```
./dnsprobe -probe tamper -resolvers 8.8.8.8 -reference 1.1.1.1 -json
```

Queries the same names on the target and a reference resolver; flags
rewritten/blocked answers.

#### doh-reach — DoH/DoT endpoint reachability

```
./dnsprobe -probe doh-reach -endpoints "https://dns.google/dns-query,1.1.1.1" -json
```

URLs starting with `https://` are probed as DoH; bare hostnames as DoT.
Reports the precise failure stage (tcp_connect, tls_handshake, http_status,
etc.).

## Flags reference

| Flag | Default | Description |
|------|---------|-------------|
| `-probe` | (required) | Probe name |
| `-resolvers` | (required) | Resolvers: CSV, `@file`, or `auto` |
| `-zone` | `example.com` | Zone for random-label probes |
| `-bigname` | `microsoft.com` | Domain with large TXT for ednssize |
| `-reference` | `1.1.1.1` | Reference resolver for tamper |
| `-endpoints` | | DoH URLs / DoT hosts for doh-reach |
| `-transport` | `udp` | Transport: udp, tcp, dot, doh, doh-json |
| `-timeout` | `5s` | Per-query timeout |
| `-repeat` | `5` | Repetitions for reach probe |
| `-concurrency` | `1` | Workers for rate probe |
| `-duration` | `10s` | Duration for rate probe |
| `-ramp` | `false` | Ramp concurrency for rate probe |
| `-insecure` | `false` | Skip TLS cert verification |
| `-sni` | | Explicit TLS SNI |
| `-json` | `false` | Emit JSON output |
| `-out` | | Write JSON to file (table still printed) |
| `-v` | `false` | Verbose output |

## How to read the output

### qname

The QNAME ladder shows whether long DNS queries survive the path between the
device and the resolver. Each row shows a payload size in bytes and the total
QNAME length (wire format). "Responded=true" means *any* response came back
(even NXDOMAIN or SERVFAIL) — the query survived. A timeout means the query
was dropped. The "Max surviving payload" line tells you the largest data chunk
you can encode per query in a DNS tunnel.

### ednssize

The EDNS size ladder shows how large a UDP DNS response the path allows.
TC=true means the response was truncated (the resolver couldn't fit its data
in the advertised buffer). The "Response size" column shows the actual bytes
received. The "Max useful downstream" is the largest response observed — this
is your per-query downstream budget for a DNS tunnel.

### goodput

Goodput combines the three measurements:
- **Upstream budget** (from qname): max payload bytes per query
- **Downstream budget** (from ednssize): max response bytes
- **QPS** (from rate): sustained queries per second

The formulas account for encoding overhead:
- Upstream: payload is base32-encoded (5 bytes → 8 chars), so usable data = budget × 5/8
- Downstream: ~75% of response bytes are usable data (rest is DNS framing)

The result is theoretical maximum — real tunnels are lower due to protocol
overhead, retransmissions, and variable latency.

## `-resolvers auto`

On Android, auto-detection shells out to `getprop net.dns1..4` and parses
`dumpsys connectivity` for DNS server addresses. If both come back empty, the
tool **exits with an error** — it never silently falls back to a public
resolver, because a hidden default would make a blocked network look like a
working one.

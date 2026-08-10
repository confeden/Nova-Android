# ROADMAP.md — Claude's persistent project state file

> **Protocol (global rule):** Claude reads this file at the START of every session and updates it at the END of every response that changed anything. This is Claude's own working context, read by Claude only — NOT user documentation. Keep it in English, dense, conclusions not narratives. Accuracy rule: write "works" ONLY for what a device log confirms; everything else is "built, not verified on device". Quoted log/UI string literals stay in Russian — they are exact search keys for real program output.
>
> Updated: 2026-08-10.

## Current state

Version **1.26** (`versionCode 136`). Test device — Pixel 4 XL (`9B071FFBA001CE`), spare — Pixel 4a.
Release build, signed with the working key, installs over the previous one without data loss.
134 Kotlin unit tests, green, plus `go test ./cfws ./tlsshape` green.

Nothing unfinished in the tree. The whole series is committed 2026-08-10 to `main` (75 files, one
commit). Screenshots of the test device (`.nova_shots/`) are deliberately not committed — they are
evidence for analysis, not build input.

Build and install: `./gradlew :app:assembleRelease`, then
`adb install -r app/build/outputs/apk/release/Nova_<version>.apk`. Release deliberately does not build
without the WSS signing secret. Edits in `nova-core/` do **not** reach the APK by themselves — run
`tools/build_nova_core_aar.sh` (needs Go, gomobile, NDK 27.2.12479018); it updates both the `.aar`
and `jniLibs`, and `jniLibs` wins — updating only the `.aar` leaves the old library loading on device.

### Stopping point

- **Closed and log-confirmed:** `:vpn` crash after «Стоп» → «Пуск»; self-restart of a doomed process
  (36 ms instead of the 62 s Android scheduled); trailing `STOPPED` broadcast that silently cancelled
  a just-started launch.
- **Built, awaiting device check:** neutral SNI + ClientHello shape for Telegram (problem 1) — the
  shape is confirmed under WARP, the name substitution cannot be checked while the worker quota is
  exhausted (problem 2); D-pad focus highlight; the rows in "Built, not verified on device".
- **Problem 3 (WARP instability) — cause found:** churn is a property of the path to a specific node,
  not of the client. See section 3.
- **Adaptation:** two full 50/50 runs done, the second one clean (after SNI substitution was removed,
  problem 3b). Both named the same four worst nodes, all with ping 38–49 ms — by the old metrics they
  looked healthy. Bundled profile order rebuilt from the clean run. Adaptation deliberately leaves the
  tunnel down when it finishes — it is a measuring run; reconnecting afterwards is a manual step.

### Confirmed by device log

| What | Log evidence |
|---|---|
| EU comes up through our own API relay | `Discovered endpoints` через релей даёт рабочий набор, `fallback активен` |
| Ping for EU/US is displayed | замер публикует служба, экран берёт его по метке транспорта |
| Attempt over a cached address — 4–5.4 s | было 17.5 с |
| Promoting a successful method to the top after 20 s | `Opera EU удержалась 20 секунд. Поднимаем этот способ наверх очереди` |
| Iteration state survives «Стоп» | после перезапуска перебор начинается с поднятого адреса |
| «Стоп» → immediate «Пуск» is no longer lost | `Ждём завершения cleanup` → подключение за 430 мс |
| An explicit region is not substituted with WARP | `регион выбран явно: временный WARP-bootstrap не запускаем` |
| VLESS connects, the counter is honest | `перебор 305 профилей`, `VLESS активен — профиль 1/305` |
| A doomed process does not raise a new session | `процесс :vpn обречён` в 19:34:38.983, падение в 19:34:40.6, туннель не пострадал |
| An explicit launch removes the restart delay | после падения в 19:34:13.7 «Пуск» в 19:34:15.19 поднял процесс за 30 мс |
| Our own restart beats Android | 22:28:35.939 `Scheduling restart … in 62646ms`, 22:28:35.975 `Повторяем запуск сами` — 36 мс |
| Hello shape is browser-like, ALPN reduced | `TLS shape=chrome sni=vesta-1.web.telegram.org (substituted=false) version=0x0303 alpn="http/1.1"` |
| Neutral SNI rollback fires | `neutral SNI refused for kws5-1.nova-app.eu (403 Forbidden)` → зона вернулась к литеральному имени |
| «Стоп» → «Пуск» in WARP reaches the service | 23:26:18.9 стоп → 23:26:21.5 пуск → 23:26:21.7 служба приняла → 23:26:35.4 `STATE_CONNECTED` |
| Hold is measured inside the connection window | `Удержание warp-awg-exact@…69.8:987: тишина 1059 мс за окно 16774 мс, проб 16` |
| The tail of the window does not enter the measurement | 16 проб в окне удержания против 20 в окне качества — последние 2,5 с отрезаны |
| The measurement survives quality publication | в выгрузке `hold_windows=1 … hold_grade=3` при том, что следом прошёл `WARP quality [ok]` |
| Windows add up instead of overwriting each other | `hold_windows=2 hold_stall_ms=2117 hold_span_ms=33200` = сумма окон 1055+1062 и 16625+16575 |
| A non-indicative window states its reason | `Удержание …69.8:987 не замерено: окно 0 мс короче 12000 мс. Проб 0` |
| Switches are large and without labels | снимки экрана «Настройки»: белый бегунок на зелёной дорожке, серый на тёмной |
| SNI substitution fully removed | за весь прогон адаптации ни одного `sni=` в попытках WARP; в выгрузке `preferred_sni` пуст у всех 50 |
| Connection after a run does not stall | было около пяти минут перебора, стало `Успешное подключение на порту 7559` со второго профиля |
| Adaptation walked all profiles | экран: `Адаптация завершена. Сохранено: 50  50/50`, 47 показательных окон |
| The measurement separates nodes | здоровые 1,05–1,4 с тишины, четыре узла 7,8–15,4 с; промежуточных нет |
| A bad node drops to the bottom of the queue | `8.39.125.3:987` (ping 49 мс) с 9-го места на 50-е |
| The cheap measurement agreed with the expensive one | `8.39.214.9:500` назван и двухминутными окнами churn, и двадцатисекундным замером тишины |
| Churn stopped being erased | на карточке появилось `Рукопожатий: 1,6/окно` — раньше счётчик не доживал до двух окон |

### Built, not verified on device

Screens are not exported — `am start` from adb gets `SecurityException: not exported from uid 10446`.
They can still be driven as a user: `input tap/swipe` + `exec-out screencap`; that is how the card
layout was checked. Caveat: `uiautomator dump` returns the tree of the **previous** screen if taken
right after launch — always cross-check against a screenshot, otherwise you tap the wrong thing. The
`>` button next to «ОТКЛЮЧИТЬ» is not region choice, it is reconnect-to-next-config. Region lives in
«Настройки → Выбор региона»: AUTO / WARP / EU / US / MASQUE. Search in the app list does not tolerate
a trailing space — with one it finds nothing.

Subscription address for recovery:
`https://raw.githubusercontent.com/luxxuria/harvester/refs/heads/main/speed_tested.txt`
(307 profiles, 12 h interval).

| What | How to check | Expected result |
|---|---|---|
| DNS `Virtual` instead of `OverTcp` for tun2proxy | EU + Chrome (the owner added it to the VPN app list on 2026-08-09) | names resolve, pages open |
| Deleting VLESS profiles | configs screen → delete one; «Удалить все импортированные» | the entry disappears from both lists, the active profile does not vanish |
| Subscription management | change the interval, delete the subscription, delete it together with the profiles | the interval is preserved, profiles go away only on an explicit choice |
| Nodes that disappeared from the subscription | switch the subscription address to a different one | old nodes disappear instead of piling up |
| ~~Card buttons on the second row (`FlowLayout`)~~ | **checked 2026-08-09** | six buttons laid out in two rows: `⇈ ↑ ↓ ⇊ Удалить` / `Копировать`, nothing clipped |
| Focus highlight from the remote | open Nova on a TV, walk around the menu | the focused row has a three times thicker border and a lighter background; it differs by more than color |

Awaiting a background Opera check:

- **Merging the endpoint list instead of replacing it.** New log line
  `Кэш Opera endpoints для X после слияния: было N, discover дал M, стало K: <список>` was added
  because the only existing line (`Есть кэш Opera endpoints…`) prints the first two non-cooling
  addresses, not the list — merge and replace were indistinguishable by it, and that already misled
  one analysis. `mergeKeepingOrder` itself is covered by tests; the pipeline is what needs checking.
- **Neutral SNI** (problem 1) — our own zone does not take part in routing while the worker quota is
  exhausted (problem 2).

## Open issues

### 1. Telegram traffic shape: ported and verified, except Opera mode

The Telegram route leaked in cleartext SNI: `dialTransparentTLSEndpoint` put the literal
`kwsN.nova-app.eu` into `ServerName`. One rule `^kws\d+\.` covers the whole six-domain pool — the
prefix is shared, so the pool costs the same as one domain.

Ported from Nova PC (`docs/adr/0004-neutral-sni.md`): SNI carries a neutral name of the same zone, the
route stays in the `Host` header. CF edge tolerates SNI/Host divergence inside a zone and rejects it
outside (a foreign zone gives 403). The worker needs no changes. Foreign domains — Telegram's own
`kwsN.web.telegram.org` and the shared-pool workers — are left alone: nobody measured them. Rollback:
the zone reverts to the literal name for 15 minutes if the handshake with the substituted name goes
unanswered or the upgrade returns 403/421; failures before hello is sent and after an accepted
handshake do not trigger it — the name took no part in them.

**ClientHello shape moved to uTLS.** Go's `crypto/tls` hello matches no browser, so the connection was
separable long before the SNI name mattered. uTLS was already a core dependency (WARP registration), so
no new one was needed. Shape lives in `nova-core/tlsshape`, Chrome first — the same browser the upgrade
header claims to be. Exactly one failure phase rotates the shape (unanswered handshake) with a 20 s
hold-off, because the candidate race hits several domains at once and without hold-off would burn the
whole list in milliseconds without measuring a single shape. Neutral SNI is dropped in that same phase:
one attempt cannot tell a name from a fingerprint, and both moves are cheap and reversible.

**Verified under WARP 2026-08-08:** relay came up (`профиль=wifi`), `TLS shape=chrome … alpn="http/1.1"`,
WebSocket upgrade passes. No `Switching the TLS shape` in the log — Chrome shape accepted by all routes.

**Apex in SNI is wrong for some routes** (measured with a valid token, three identical runs): media route
`kws5-1` rejects the apex `nova-app.eu` with **403**, while `www.nova-app.eu` and the literal name give
429 (exhausted worker quota, i.e. the request reached our route); `kws2` accepts the apex (429 too —
routed, not refused). Nova PC tested
only `kws2` and generalized. Apex removed from candidates, only `www.` remains — accepted everywhere.
Per-user name spread is lost, but a candidate that drags the zone into a 15-minute rollback on a third of
the routes costs more. The zone has no wildcard (a random name does not resolve) and only apex, `www.`
and `relay.` records exist — there is no third candidate. The rollback behaved as designed: caught the
403, reverted the zone, left the hello shape alone (403 arrives after a completed handshake).

**The apex → `www.` fix is NOT verified on device and cannot be verified right now.** The 23:26 run has
no `neutral SNI refused` line, but the substitution never fired either: routes went only through the
foreign pool (`cakeisalie.co.uk`, `offshor.co.uk`, `pclead.co.uk`, `lovetrue.co.uk`,
`noskomnadzor.co.uk`) and Telegram's own nodes; `*.nova-app.eu` did not participate at all. Cause is
problem 2. An empty log here means "not attempted", not "works".

**There is no relay in Opera-only mode.** The transparent relay lives in `AndroidTUN` inside the Go core
(`tunnel.go`, `tryHandleTelegramTransparent`), but on EU/US the tun-fd is handed to tun2proxy via
`detachFd` and the core sees no packets. Also `setTelegramTransparentProxyConfigCompat` and
`installTelegramWsSignatureSecret` are called from exactly one place — the warp-connect branch — and
`configureAndStartOperaOnly` does not call them. So on EU/US the whole Telegram relay is structurally
off, and with it neutral SNI and hello shape. They work only where the Go core owns the datapath: WARP
and auto. **Owner decision (2026-08-08): leave it that way** — Telegram through Opera's Dutch exit works
without slowdowns, the route is already outside Russia, there is nothing to bypass. A local proxy in
front of `opera-proxy` that would return the relay to the Opera datapath was rejected as work without a
task. If Telegram on EU ever starts lagging, this note says what to do.

The log silence that cost a run is closed: a disabled relay now states its reason — no messengers on the
device, or they are not in split tunneling.

**Not ported, and why:**

- **Phase attribution of failures** (`docs/adr/0003-tunnel-phases.md`). `raceTransparentDomains` applies
  a flat 45 s cooldown to any error, including ones the domain is not to blame for. The route model
  differs: PC penalizes egress, here it is the (IP, domain) pair. The mapping must be designed, not
  guessed.
- **Header persona.** On PC the headers come from the same browser whose ClientHello is in the profile.
  Here the upgrade header is hand-built and only `User-Agent` pretends to be Chrome. All inside TLS and
  invisible from outside — but switching the shape to Firefox or OkHttp breaks the persona.
- **winws strategies** (`strat/*.json`). Those are zapret arguments over WinDivert, a Windows kernel
  driver. There is no Android analogue and cannot be: bypass here is built with a tunnel, not by
  rewriting packets in flight.

### 2. Own zone hits the worker quota — falling back to the foreign pool is accepted, and now visible

Measured from the dev machine: **any** request to `kws2.nova-app.eu` and `kws5-1.nova-app.eu` answers
`429 Too Many Requests` — with and without a valid token, with literal and substituted name. 429 from a
Cloudflare Worker means the daily quota is spent. So our own leg of the Telegram relay does not work at
all, and has not for some time (the same 429s were present in the measurement that uncovered the apex
story). In practice the relay lives on the foreign shared-pool domains, where the WSS signature and the
neutral SNI simply do not exist.

**Owner decision (2026-08-08): keep working this way.** The worker plan is free, 200 000 requests/day;
when our own zone hits the quota we rely on the pool's foreign domains. This is a normal fallback, not a
defect.

**Fixed:** the fallback no longer stays silent. An unusable own-zone route now prints
`own-zone route … is unusable`, budgeted at 8 lines per session. (`cfpool:` named the chosen domain but
never said that none of ours were left, and the empty log read as "SNI substitution took hold".)

**Still open:** who is spending the quota — our own pool iteration or third-party clients that learned
the names from the public repository. While the quota is spent, neutral SNI cannot be verified on device.

### 3. WARP rebuilds the handshake every 16–31 seconds — cause: path quality to a specific node

Baseline measurement 2026-08-09 (7 min, `warp-awg-exact`): 18 handshake initiations, 18 responses, 17
`Retrying handshake because we stopped hearing back after 15 seconds`, 37 keepalives sent, **0
keepalives received**, zero decryption/MAC/drop errors. Each handshake completes in 40–60 ms first try;
15 s later the node goes quiet and WireGuard rebuilds the session. Ping dips land exactly in those gaps.
**The handshake path is fine; the reverse transport stream disappears.**

**Reference thresholds.** A healthy WireGuard session rekeys once per two minutes = 0,5/min. Measured
1,5–3,0/min = three to six times the norm. Churn is sampled by `sampleTunnelRekeyChurn` over 2-minute
windows off `last_handshake_time_sec`; windows with < 32 KB tx (`TUNNEL_REKEY_MIN_TX_KB = 32`) are marked
non-indicative.

**Cause confirmed 2026-08-10** — same build, same network, natural traffic, two nodes back to back:

| Node | Traffic per window | Rekeys |
|---|---|---|
| `.214.9:500` | 219–302 КБ tx | 5, 4, 4, 5 — **2,0–2,5/мин** |
| `.69.8:987` | 395–442 КБ tx | 2, 1, 2 — **0,5–1,0/мин** |

The second node is 2–4× calmer while carrying **more** traffic. AWG parameters are identical on all 96
bundled profiles, so churn is a property of the path, not of the client config.

**Disproven hypotheses — do not retry:**

- **junk is not the cause of churn — it is a precondition for the handshake.** Debug key `SET_AWG_JUNK`
  (adb only, no UI screen on purpose) strips `Jc`, `Jmin`, `Jmax`, `I1..I5`, keeping `S1..S4`/`H1..H4`.
  The first experiment "0 rekeys without junk" was an artifact of a silent tunnel (the rekey timer is
  armed only by data being sent). Tool fixed first: the window now prints `трафик N/M КБ tx/rx` next to
  the count. Re-run honestly with that denominator: with junk at comparable load 1,0–1,5/min; without
  junk the session does not come up at all (config iteration without connection, twice in a row — 42/50
  profiles walked with no connection on the first). "Strip junk after the handshake" has no basis —
  nothing shows it harms an already-established session.
- **`reserved` is irrelevant — checked from data, no run needed.** `warp_verified_export.json` (available via adb) has 96
  records, **none with `Reserved`**, and no WARP client id, so "enable reserved" would write `0,0,0` —
  exactly what is there now. The measurement would measure noise.
- **amneziawg-go upstream keepalive fix does not help.** Bundled copy was 4 months behind (2026-03-31);
  upstream commit `08d68cd` **`fix: keepalives are ignored`** matched the symptom by description. Ported
  (two files; the `padding` field from a neighbouring commit was not pulled). Measurement after it:
  **6, 7, 6 rekeys per window (3,0–3,4/min)** — no change. The fix is kept (correct on its merits,
  harmless), but the cause is not there: zero keepalives were received all session, so the receiving half
  is inapplicable and the sending half does not matter because real traffic arms the timer honestly. The
  reverse stream really disappears; it is not a bookkeeping loss.
- **All bundled profiles carry identical AWG parameters.** From the same export: `Jc = 4` ×96,
  `Jmin = 40` ×96, `Jmax = 70` ×96, `S1..S4 = 0` ×96, `H1..H4 = 1,2,3,4` ×96; only endpoint and keys
  differ. Consequences: "switch to another profile" changes no AWG shape at all, only the node (a message
  advising it would mislead); and to get parameter diversity you must change the seed data, not the code.
- **AWG parameters are applied verbatim** — verified in code, not guessed. `tunnel.go` writes every `jc`,
  `jmin`, `jmax`, `s1..s4`, `h1..h4`, `i1..i5` into UAPI as-is; bundled `amneziawg-go/device/uapi.go`
  parses each and **returns an error** on an unknown key (`invalid UAPI device key`), so the tunnel would
  not come up at all. `awg_compat.go` is stubs: validation only.

**Churn in ranking (2026-08-10).** Chain: indicative window (tx ≥ 32 KB) → `recordWarpConfigChurn(host,
port)` → `churnWindows`/`churnRekeys` on the record → penalty in the numeric score → iteration order.
Penalty from a healthy 1 per 2-minute window: `(churn − 1) × 12`, cap 36 (so one bad evening does not
bury a node forever); at 12 windows the counters are halved (aging, otherwise a node that went bad a
month ago never recovers). Silent windows are excluded — a zero there comes for free and would inflate a
node that carried nothing; in particular the window right after switching nodes is marked
`Туннель молчал: трафик 0/0 КБ` and never enters a comparison. The configs screen shows «Удержание: N/окно» next to «Качество» so that a
changed sort order has a visible explanation. Device-confirmed: `8.39.214.9:500 windows=2 rekeys=13`
(6,5 per window vs a healthy 1 = full penalty cap).

**Not done:** actively moving off a bad node on a live session. Churn only affects the order of the next
iteration — a working tunnel is not torn down for statistics.

**The churn penalty never reached the queue (found and fixed 2026-08-10).** `buildBuiltInWarpAttemptSet`
sorted bundled profiles by `compareBy { seedOrder }` and `sortedVerifiedWarpConfigs` by `promotedAt` then
`seedOrder`. The asset has 50 records with `seed_order` 0..49, all distinct (verified by script), so the
first key never ties and `qualityTier`, `qualityPingSuccesses`, `qualityAvgPingMs` and
`getWarpVerifiedPriorityScore` behind it were unreachable — any measurement affected only card order on
screen. Fixed by coarsening: `SessionHoldMetric.bundledSeedQueueBucket` buckets profiles by ten — the
firmware order keeps coarse authority (first ten before second ten), measurements decide inside a bucket.
Removing `seedOrder` outright was rejected: it carries the manual rank from the Pixel 4a export and there
is nothing to replace it with on an unmeasured network. Exact `seedOrder` is appended as the last
tie-breaker so that without measurements the order stays as before and stable across launches.

### 3a. Session hold: cheap measurement inside the adaptation window (2026-08-10)

Adaptation measured ping and the fact of connecting. A node losing the reverse stream is normal on both —
that is how it stayed at the top. Measuring churn with 2-minute windows over 50 profiles would take ~100
minutes, so the metric was built from what already runs.

**What is counted.** `startWarpQualitySampling` already probes connectivity once a second for a 20 s
window; a probe is data sent, a successful probe is a packet received from the tunnel. So **the longest
silence between successful probes** is the same physical signal with twenty samples instead of one bit,
and it triggers earlier: a five-second gap causes no rekey but does show as silence. It adds to coverage
rather than duplicating it — twelve scattered failures and twelve consecutive ones both read "12/20" but
describe different nodes. Logic is isolated in the pure `SessionHoldMetric`, covered by 23 tests.

**Counting rekeys in that window was rejected on analysis, not for cost.** The timer is armed by data and
fires after 15 s of silence; the window opens after data-plane confirmation. Per the 2026-08-09 log the
reverse stream lived 1, 11, 12, 12, 15, 16, 16 s after a handshake — a rekey lands in a 20 s window about
one time in seven, so a bad node would usually show an honest zero just like a good one. Two side issues
die with it: MASQUE stamps the handshake mark once per tunnel (zero rekeys would mean "not measured", not
health), and on a tunnel being torn down `last_handshake_time_sec` goes to zero and would read as an
extra rekey. A probe-based signal is free of both.

**Constraints that keep the metric honest:**

- **Silence in milliseconds, not in failed-probe count.** A failed probe costs ~1,1 s on top of the sleep,
  a successful one tens of ms, and on a slow device the loop step is 1,5×: the same "5 in a row" would
  mean different durations on different nodes. The denominator — probe count and actual window length —
  is always logged alongside.
- **`uptimeMillis`, not `elapsedRealtime`** (see the gotcha). Unobserved sleep time is not counted, and a
  slept-through window ends up too short and is discarded.
- **Tail of the window cut by 2,5 s.** The attempt's hold window and the measurement window are equal and
  start from the same mark, so the last iterations hit an already-tearing-down tunnel. Probes there fail
  and would add silence to **every** profile, including healthy ones — biasing exactly those that
  survived the window.
- **Its own indicativeness threshold.** `TUNNEL_REKEY_MIN_TX_KB = 32` does not apply here: probes move
  single kilobytes and every window would be dropped silently. Indicative = duration ≥ 12 s and ≥ 5
  probes; a non-indicative window states its reason in the log.
- **An iteration with no VPN network sent nothing** — that silence is not charged to the node, but the
  window after it is not counted as full either. Silence accumulated **before** the skip does count: it
  was measured by real probes through a live tunnel.
- **"No data" is −1, not zero.** Zero is indistinguishable from perfect, and an unmeasured node must not
  look flawless.

**How it influences ranking.** The grade is coarse — four levels (держит / проседает / неизвестно /
теряет поток) — and it is inserted as an early key in four sorts right after `qualityTier`, above ping,
because ping on such a node is normal. The numeric penalty shares a common cap with churn: both describe
the same path defect and on a normal connection are taken from the same seconds, so two independent
deductions would skew the score. The hold penalty decays with measurement age over six hours — churn has
no such decay, and a month-old sample there penalizes like yesterday's.

**What the metric cannot see.** A profile that does not survive to the end of the window gets no
measurement (`spanMs` < 12 s → window discarded). Such nodes are punished by other mechanisms
(`qualityFailureCount`, runtime outcomes, `qualityTier`). The purpose of the signal is to separate the
profiles that **did** survive the window and previously looked equally good.

#### Full 50-profile runs (2026-08-10, confirmed by export)

Run split the nodes: **43 hold, 4 lose the stream, 3 unmeasured**. No intermediate grade ever appeared —
nodes are silent either ~1 s or for a long time.

| Node | Silence | Ping | Quality | Rank before → after |
|---|---|---|---|---|
| `8.39.125.3:987` | 7,8 с | 49 мс | 4/19 | **9 → 50** |
| `8.47.69.8:946` | 15,4 с | 42 мс | 6/9 | 32 → 45 |
| `8.6.112.8:1070` | 11,5 с | 42 мс | 5/8 | 34 → 47 |
| `8.39.214.9:500` | 15,3 с | 38 мс | 5/8 | 45 → 46 |

Healthy nodes land at 1,05–1,4 s — that is the resolution of the metric (the poll step). The gap between
classes is 7–15×, spread inside the healthy class is almost nil: the 3 s calm threshold passes through
empty space, not along the edge of a point cloud.

Three independent instruments converged on `8.39.214.9:500`: 2-minute churn windows, the 20 s
probe-silence metric (which knows nothing about handshakes), and the second clean run. `8.39.125.3:987`
stood **ninth** and fell to last with ping 49 ms — by the old metrics it had no complaints.

**Second run was clean** (after SNI substitution was removed, see 3b): 50/50, 46 indicative windows,
`preferred_sni` empty on all profiles, same four worst nodes.

**Bundled order in `warp_verified_seeds.json` rebuilt from the clean run.** The key change:
`8.39.214.9:500` carried `seed_order = 0`, i.e. **every user tried it first** — the very node listed
since 9 August as losing the reverse stream. It is now 45th; 48 of 50 profiles changed position. Order is
generated by `tools/generate_warp_verified_seeds_from_export.py` from `release_seed_items`, which is
written in `sortWarpVerifiedConfigs` order (hold as an early key). Previously the basis was the manual
Pixel 4a rank (`source_file: pixel4a_export_rank_*`) built on ping.

UI note: `tv_config_meta` was `maxLines="1"` and the new indicator truncated itself («Удержание:
держи…»). Two lines allowed, wording shortened to «Удержание: держит 1,4 с»; the full form with the
denominator stays in the log.

### 3b. Masking SNI substitution removed (closed 2026-08-10)

Symptom: after an adaptation run, manual «Подключить» took ~5 minutes instead of seconds; every attempt
failed with `handshake_timeout` on the same stored masking domain:

```
Ставим cooldown на warp-awg-exact@3138 через ads.max.ru после handshake_timeout.
Сбрасываем сохранённый preferred SNI 'ads.max.ru' для warp-awg-exact@3138: он привёл к handshake_timeout.
```

Three levels, the main one not in code: (1) the release asset carried the mask —
`preferred_sni = ads.max.ru` on **45 of 50** profiles in `warp_verified_seeds.json`, so every fresh
install started with a substitution nobody enabled and the masking toggle had nothing to do with it;
(2) every successful connection wrote the mask into the profile, and an adaptation run stamped it on all
fifty at once; (3) the stored name was applied bypassing the toggle — the `preferredSni` branch in
`publishWarpTrafficMaskHint` sat ABOVE the "masking enabled" check.

**Owner decision (2026-08-10): no substitution at all, neither on connect nor during adaptation.** Done:
`resolveWarpTrafficMaskHosts` returns an empty list; the stored-name branch removed; writing the name
into the profile removed; asset cleaned; the seed generator no longer carries `preferred_sni`; already
installed apps purge stored names once at startup (`purgeStoredWarpPreferredSniOnce`). The domain catalog
and the toggle are left in place — only the substitution was removed, so it can be restored from one spot.

Device-verified: `preferred_sni` empty on all 50 in the export, not a single `sni=` in WARP attempts
across a whole adaptation run, connection after a run comes up on the second profile in seconds. The
Telegram relay's neutral SNI is a separate mechanism and untouched — visible in the log as
`TLS shape=chrome sni=kws2.web.telegram.org (substituted=false)`.

Side observation worth one run: the mask seems to have damaged tunnels itself — `8.39.125.3:987` gave
7,5 s of silence in the masked run and 1,06 s in the clean one.

### 3c. Connect stuck on «1/50»: Private DNS resolve without a timeout (closed 2026-08-10)

Symptom: the counter stays on the first profile forever, no connection. In the log there are
**79 seconds of complete silence** between raising the TUN and the next line, then the background
heartbeat decides «условия сети изменились» and restarts the cycle from the top — every 80 seconds.

The silence fell exactly between `applyUnderlyingNetworkHint` and `builder.establish()`, and there is
a single call in between: `applyPrivateDnsBypass`. The device has strict Private DNS
(`private_dns_mode=hostname`, `xbox-dns.ru`), so that branch ran and went into
`resolveHostOutsideVpn` — a bare `InetAddress.getAllByName` **with no timeout and on the global
resolver**. Once the process is bound to the VPN, the global resolver goes into a tunnel that does
not exist yet: the resolve blocked until the system limit and the attempt never started.

Fixed in two parts, both needed:

1. **Resolve over the underlying network** (`underlyingNetwork.getAllByName`) instead of the global
   resolver — «outside VPN» in the function name finally became true. This removed the cause.
2. **Hard 1.5 s limit** with a log line. If the resolver ever blocks again, the attempt no longer
   stalls: waiting silently here eats the whole attempt.

Verified on device: `Private DNS xbox-dns.ru выводим мимо VPN: …96.55` — 107 ms instead of 79
seconds; three stop→start cycles connected on ports 987, 7103, 7559; no cycle-restart lines.

**Checked alongside, at the owner's explicit request:** built-in profile parameters are not
substituted. AWG parameters (`Jc/Jmin/Jmax`, `S1..S4`, `H1..H4`, `I1`) are present on all 50 records
of the regenerated asset; `8.35.211.1:1701` from the «Сжали набор WARP endpoint-ов» line is a real
asset entry, not an invented pair; each profile's `preferred_ports` holds exactly its own port.

### 4. Split tunneling in Opera mode

The Nova package is **always** outside the VPN — `applyOperaSplitTunnelPolicy` excludes it in all three
branches. So the UI fundamentally cannot measure anything itself: measurements for Opera must be
published by the service. Not a defect, but easy to forget and write a UI-side measurement again.

### 5. Graceful Opera stop waits for nothing

The `preferGracefulOperaStopOnce` branch expects a natural exit of the tun2proxy loop, which never
happens: the tun-fd is handed to the library via `detachFd`, launch uses `closeFdOnDrop = true`, and
closing the interface does not affect it. The branch sits out `join(4200)` and leaves the old tun2proxy
running over the new WARP session. The new force-stop-skip logging will show it in the first log. Not
related to the crash; fix separately.

### 6. `opera_state.json` growth

Stores addresses, cooldowns, plan statistics and the chosen API profile. Cleanup exists only for expired
cooldowns. If it starts growing under address rotation — add age-based trimming.

## Third-party component updates (2026-08-09)

Tested as a separate hypothesis: does a plain update cure any of the problems? It cured nothing known and
did no harm to function — 112 tests green at the time, release built and working, ping 25 ms after.

**Raised:** `compileSdk` 34 → 35 (`targetSdk` left at 34 — app behaviour unchanged); `core-ktx`
1.12.0 → 1.13.1; `appcompat` 1.6.1 → 1.7.0; `material` 1.11.0 → 1.12.0; `constraintlayout`
2.1.4 → 2.2.1; `work-runtime-ktx` 2.9.1 → 2.10.5 (required compileSdk 35); `commons-compress`
1.26.0 → 1.27.1; `xz` 1.9 → 1.10; `bcprov-jdk18on` 1.78.1 → 1.80. In Go: `utls` 1.7.3 → 1.8.2,
`x/crypto` 0.49 → 0.54, `x/net` 0.52 → 0.57 and companions.

**`quic-go` 0.55 → 0.61 via our own fork.** 0.61 replaced one-shot `http3.ParseCapsule` with the
streaming `CapsuleParser`. The old call existed in `connect-ip-go/conn.go` and our `engine/masque_h2.go`;
both converted (one line each). `connect-ip-go` is forked into `tools/connect-ip-go` and wired via
`replace`, as already done for `usque`, `warp-plus`, `amneziawg-go`; the fork also covers `usque`, which
pulls the same dependency.

**Future target:** `github.com/quic-go/masque-go` — the CONNECT-IP implementation by the quic-go authors,
which tracks its API. `connect-ip-go` is third-party and we have now patched it by hand twice. The move
touches the whole MASQUE branch, so it is separate work.

**Harm not noticed at the time:** the «Настройки» switches came out small with «ВКЛ» written inside the
thumb — `appcompat`/`material` defaults for `Switch` changed. Fixed 2026-08-10: sizes, shape and state
colors are set by our own resources and no longer depend on library version.

**Not touched:** local `replace` forks (`usque`, `warp-plus`, `gvisor`) and the amneziawg-go 3.0 move —
major version, separate work with separate verification.

**Aside:** `cmd/masque-bootstrap` does not build (`"nova-core/engine" imported as nova and not used`).
Broken before the update (verified by reverting go.mod). Not in the `.aar` — gomobile binds the root
package — so the app is unaffected.

## Accepted rules

- **A user's explicit choice is never overridden.** Protocol or region may be switched only in «Авто».
  Enforced by `RegionTransportPolicy`, covered by tests.
- **Secrets never enter the repository.** Values come from env vars or `local.properties` into
  `BuildConfig` (WSS signing secret, API relay password). One secret — one source of truth; duplication
  lets a stale value live unnoticed.
- **State written by the service lives in files, not `SharedPreferences`.**
- **A failure must be visible.** A silent `return` in connection paths is forbidden: if the loop did not
  start, the log must state why. This includes disabled subsystems — a silent "off" is indistinguishable
  from "the change did not take effect", and one run has already been lost to that.
- **Changes in sensitive places are reviewed adversarially.** Analysis plus a skeptic trying to refute it.
  That practice has already caught a deadlock and two wrong root causes.

## GOTCHAS (already burned by)

- **Cross-process settings rollback.** The service lives in `:vpn`, the UI in the main process.
  `MODE_PRIVATE` `SharedPreferences` are cached whole by each process; any `commit()` writes that
  process's entire copy and rolls back the other's writes. That is how the address that had just held the
  tunnel disappeared. Cure: move to `AtomicFile`.
- **A probe that breaks itself.** `socket.getOutputStream().bufferedWriter().use { }` closes the stream
  and with it the socket: the proxy saw the client disconnect before it could go outside.
- **A library that calls `exit()`.** Established by disassembling `libtun2proxy.so`: `tun2proxy_stop()`
  unconditionally starts a detached thread "sleep 2 seconds → `exit(-1)`", checking nothing in between —
  neither whether the worker loop returned nor whether a new instance started. `exit` runs
  `__cxa_finalize`, which destroys the global `libandroidio` mutex, and any thread parked in a native read
  kills the process. The fuse cannot be cancelled: **any call to `haltTun2proxy()` is a death sentence for
  the `:vpn` process**, so scheduling launch order inside the same process is pointless. Three variants
  already tried and found harmful: quarantine before a new launch (the fuse burns regardless of launches),
  extending the cleanup-guard wait (breaks «Стоп» → «Пуск»), and a shared lock around `stopOperaFallback`
  (ANR on the main thread). Working solution — do not raise a session in a doomed process, restart into a
  fresh one.
- **A promise that does not exist.** The "graceful" Opera stop waits for a natural exit of the tun2proxy
  loop, which does not exist: the tun-fd was handed over via `detachFd` and closing the interface does not
  affect it. The wait just sits out its timeout.
- **Overloaded flag.** `manual` on a VLESS profile meant "currently selected", while the screen hides
  "manual" entries from both lists — the active profile vanished from the UI entirely.
- **A probe outside the budget.** `probeLocalProxyHttpConnectivity` walked six addresses at 2.6 s each and
  ran after `waitUntilReady`, so the plan budget did not bound it.
- **Cooldown as a ban.** When all API profiles were cooling, every plan was filtered out and the loop
  finished in 2 ms without trying anything.
- **`NextProtos` does not affect uTLS presets.** ALPN lives in the hello spec itself: Chrome and Firefox
  advertise "h2, http/1.1" no matter what `utls.Config` says. The CF edge picks h2, and a hand-written
  HTTP/1.1 WebSocket upgrade goes into a connection that does not understand it. Cure: edit the extension
  in `utls.ClientHelloSpec` (`tlsshape.Spec`), not the config. Measured on the live zone: `alpn="h2"` via
  config vs `alpn="http/1.1"` via spec.
- **256 KiB logcat ring buffer.** On Pixel 4 XL that is seconds of history: `adb logcat -d` after a run
  returns nothing. Before collecting — `adb logcat -G 16M`; then the buffer survives even a power cut on
  the dev machine and covers about two hours.
- **A log line shows something other than what it looks like.** `Есть кэш Opera endpoints для X: a,b` is
  not the cache contents but the first two non-cooling addresses (`filterNot { cooling }.take(2)`). You
  cannot check the list size or tell merge from replace by it; a missing address means cooldown, not loss.
  Before concluding from a log line, check what that line actually prints.
- **An empty log is not confirmation.** The absence of `neutral SNI refused` looked like "the substitution
  took hold" but meant "our own zone never participated in routing". Before counting an absent failure,
  make sure the path under test was actually exercised.
- **The tail of a stop outlives the stop.** A `STOPPED` broadcast from the previous session arrives on top
  of the new launch. The service guards against it, the UI did not — and the cycle died between
  «Подготовка к подключению...» and sending the intent. The "this STOPPED is not ours" test must be exact,
  not time-based: until the intent has gone to the service, there is nothing to report about.
- **A measurement on one route is not a measurement of the zone.** Nova PC tested SNI substitution on
  `kws2` and generalized to the whole zone; media route `kws5-1` rejects that apex with 403. Before
  generalizing a CF edge response, walk routes of different kinds — plain and media at minimum.
- **A counter without a denominator lies.** Rekeys were counted without traffic, but the rekey timer is
  armed only by data being sent: a silent tunnel yields an honest zero and looks healthy. A false
  conclusion about junk was built on this and nearly shipped. Any "how many times it happened" metric must
  print how many attempts there were next to it.
- **A debug key routed through `SharedPreferences` never reaches the service.** The "AWG without junk" key
  was first written to settings: the UI saved it, the service in `:vpn` kept reading its own cache, and
  the toggle silently did nothing — exactly the cross-process rollback trap. Moved to `opera_state.json`
  (AtomicFile). Anything the UI writes and the service reads goes through a file only.
- **A default value hides a lost field.** Counters in `WarpVerifiedConfig` have defaults, so a missing
  constructor argument is valid code the compiler says nothing about. Churn was zeroed in two places and
  hold in three more — with a comment "переносим руками" sitting right there describing exactly what was
  not done. From outside it looks like "the measurement was not recorded": log says written, export shows
  zero. Cure: one transfer point (`carryMeasurementsFrom`) and `previous.copy(...)`, so the next field
  survives a rebuild by itself.
- **A strict total order as the first key kills every key after it.** `seedOrder` differs on all fifty
  bundled profiles, so `thenBy` after it never ran and accumulated measurements never affected the queue.
  Before explaining why a signal "did not work", check whether the key you put it behind is reachable at
  all.
- **`elapsedRealtime` runs while the device sleeps.** Any interval measured with it between two iterations
  of a background loop includes time when the loop was not running: the log has `4/4, avg=56836ms` windows
  where probes succeeded but the phone slept in between. Metrics of the form "how long the silence lasted"
  need `uptimeMillis`, otherwise an Android doze becomes a property of the node.
- **A library update changes the look of stock widgets.** `appcompat` 1.6.1 → 1.7.0 and `material`
  1.11.0 → 1.12.0 made the «Настройки» switches small and printed «ВКЛ» inside the thumb. The update
  section recorded "did no harm" — true for tests and connectivity, false for appearance. The switch is
  now defined by our own sizes, states and drawables so the next library version cannot override it.
- **One color for thumb and track hides the switch position.** `setupSwitchColor` painted both parts the
  same, so an enabled switch looked like a solid fill and state was readable only from the label inside.
  Color must distinguish the parts, not match across them.
- **An interrupted adaptation run leaves a live engine.** Stopping adaptation midway and immediately
  restarting produced two iterations at once: `Движок не завершился после stop. Принудительно пропускаем
  зависший…` in the log, and in GoLog an old peer sending keepalives for ten minutes while the new engine
  could not come up. Cure: restart the process (`am force-stop`), not press the button again. Adaptation
  needs a quiet state beforehand: starting ten seconds after app launch collided with the normal
  connection cycle and the run aborted on the 19th profile.
- **The attempt counter in the adaptation header can show "4 из 4" instead of "4 из 50".** The total is
  `maxOf(currentAttemptTotal, currentAttemptOrdinal)`, and the normal connection cycle zeroes both
  variables when it ends. Cosmetic: the log honestly says `подготовлено 50/50` at that moment. Trust the
  log.
- **A default value in an asset outlives the code.** The masking-name substitution would have been
  disabled by a single `if`, but 45 of 50 profiles carried `ads.max.ru` directly in
  `warp_verified_seeds.json`, so every new install started with it. Before considering a behaviour
  disabled, check whether it is written into data shipped in the release.
- **The datapath differs per mode.** In WARP/auto, packets are read by `AndroidTUN` from the Go core; in
  Opera-only the tun-fd is handed to tun2proxy via `detachFd` and the core drops out of the datapath
  entirely. Everything living in `engine` and touching packets — the transparent Telegram relay included —
  is simply not called in EU/US modes. Testing such changes on EU is pointless: the log will be silent for
  the wrong reason.

## How the key mechanisms work

- **SurfEasy API relay.** The endpoint set depends on where `discover` came from, and a Russian address
  gets an unreachable set. The relay moves **only API calls** to Sweden; the tunnel is dialed directly and
  the exit country does not change. The relay hostname is resolved by Android through
  `OperaApiRelayBridge`: the Go resolver inside `opera-proxy` on Android has no settings and falls back to
  `[::1]:53`.
- **Opera iteration order.** Tiers: cached address → relay → direct discover. Inside a tier: the "held for
  20 seconds" mark first, then accumulated statistics.
- **Latency measurement.** The liveness probe through the proxy *is* the measurement; the service
  publishes it to `transport_latency.json`, the UI filters by transport tag.
- **Telegram route through our own Worker.** The name `kwsN.nova-app.eu` travels in the `Host` header —
  Cloudflare routes Workers by it, not by SNI. SNI carries a neutral name of the same zone
  (`cfws.NeutralSNI`). The handshake signature (`cfws.Build`) is bound to the literal name, not the
  substituted one, and is computed in Go because the window lives two minutes and the pool keeps sockets
  open. The handshake itself is written by uTLS with a shape from `tlsshape`: ALPN reduced to `http/1.1`,
  otherwise the CF edge would pick h2 and the HTTP/1.1 WebSocket upgrade would fail.
- **Measurement-write concurrency.** The verified-config list is read-modify-written by three threads at
  once (iteration, daemon sampler, live-session ticker). It used to do that without a lock, so a writer
  losing the race rolled back not one field but the results of every config changed since it read. Nineteen
  writer functions are now under a shared `warpVerifiedConfigsLock`. No new work was added under the lock —
  only the writes that were already happening. Do not widen it: a shared lock around the Opera stop already
  caused an ANR.

## Where things live

Own components introduced by this work: `RegionTransportPolicy` (explicit-choice policy),
`OperaApiRelayBridge` (relay hostname resolution), `SessionHoldMetric` (pure hold metric + seed
bucketing), `FlowLayout`, `cfws/neutral_sni.go`, `tlsshape/shape.go`, `DiagnosticLogSanitizer`, switch
drawables/state lists (`switch_thumb_liquid`, `switch_track_liquid`, `color/switch_*_tint_liquid`).
Main touch points: `NovaVpnService`, `OperaProxyManager`, `ClientData`, `MainActivity`,
`SettingsActivity`, `WarpConfigsActivity`, `ConfigsAdapter`, `ProfileRotation`,
`VlessSubscriptionManager`, `engine/telegram_transparent.go`.
Core artifacts: `app/libs/nova-core-api24*.aar`, `app/src/main/jniLibs/*/libgojni.so`.

## Release 1.26 (2026-08-10)

**Sources published** to `confeden/Nova-Android` with commit `Исходный код Nova 1.26`
(`2b0e0367..a6799aa7`, 76 files). The method was chosen by the owner: a commit of our tree **on top of**
the public branch, not a force-push. Reason — the public repository and the working one have **no common
ancestor**: the histories are independent, the contents almost coincide. A force-push would have erased
51 public commits.

Two files were deliberately left in the public repository's revision, because there they are newer:
`.github/workflows/publish_apk_update.yml` (APK auto-publishing — deleting it would break the automation)
and `README.md` (screenshots were added to it).

**The release draft** `Nova 1.26` with tag `v1.26` on `main` is created and saved as a Draft — not
published. The tag appears at the moment of publication, not earlier.

**What is left to do by hand:**

- **Attach the APK to the draft.** `app/build/outputs/apk/release/Nova_1.26.apk`, 76,8 MB,
  sha256 `e8d7f1352485a0e5419b79ce8c8613343cfd53219dd6180d6ee3b99699680f03`. There is nothing to upload
  it with from the agent: the file upload limit is 10 MB.
- **Update `apk_version.json`.** Auto-update reads the version not from releases but from a third
  repository `confeden/nova_updates` (`AppUpdateManager`:
  `raw.githubusercontent.com/confeden/nova_updates/main/apk_version.json`). Until it is updated,
  publishing the release by itself will not deliver an update to users.
- **`origin` (`Nova-source`) was not touched:** it has a different layout — sources in the
  `NovaAndroid/` subfolder — and 3 commits that we do not have.

## Reference

- Connection-logic reference is Nova PC (`D:/Documents/Coding/Nova PC`, `nova.pyw`). If a region works
  there and not here, the solution is usually already written there.
- Nova PC decisions on the Telegram relay are in its `docs/adr/`: 0003 — phase attribution of failures,
  0004 — neutral SNI.

package nova

import (
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"time"
)

// Правило владельца для DNS-AI (`via=obfs`): сначала напрямую, мимо туннеля, с
// фрагментированным ClientHello; если так не выходит — через активный VPN.
//
// Это не гонка, как `via=auto`: ответ туннеля берётся только после отказа прямого
// пути. Чтобы отказ не стоил таймаута на каждом запросе, прямой путь после него
// снимается на [obfsDirectSuspend], запросы идут сразу в туннель, а прямой путь
// перепроверяется в фоне одним запросом. Ответил — снова первый.
//
// Замер 2026-11-09 (Mi A1, домашний Ростелеком): до обоих адресов dns.dns-ai.ru не
// проходит даже TCP SYN — блокировка по адресу, фрагментация её не лечит, и
// работает только туннель. С ПК через чужой выход прямой DoH с разбиением
// ClientHello 1/255/256 отвечал так же, как без разбиения.

const (
	obfsDirectSuspend   = 60 * time.Second
	obfsDirectMaxBudget = 1500 * time.Millisecond
	obfsDirectMinBudget = 800 * time.Millisecond
)

// Раскладка ClientHello теперь по стратегиям (dns_obfs_strategy.go), а не один план на всех: прежнее
// «1/255/256 с фрагментом 512 байт» никто не подбирал. Прямой путь — это гонка кандидатов
// (адрес × стратегия) внутри обычного бюджета: первый стартует сразу, следующий через
// obfsRaceStagger или тут же при быстром отказе первого. Победитель запоминается и идёт первым.
const obfsRaceStagger = 250 * time.Millisecond

// obfsMaxCandidates ограничивает гонку: дальше бюджета всё равно не хватит.
const obfsMaxCandidates = 8

type obfsDirectState struct {
	suspendedUntil time.Time
	probing        bool
	lastOK         time.Time
}

var (
	obfsDirectMu     sync.Mutex
	obfsDirectStates = make(map[string]*obfsDirectState)
)

// obfuscateDNSConn оборачивает прямое соединение к резолверу так, что первая запись (ClientHello)
// уходит по стратегии. Остальные пути и «плоскую» стратегию не трогает.
func obfuscateDNSConn(conn net.Conn, route dnsUpstreamRoute, strat *obfsStrategy) net.Conn {
	if route != dnsRouteDirectObfs || conn == nil || strat.isPlain() {
		return conn
	}
	if tcp, ok := conn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}
	return &strategyConn{Conn: conn, st: strat}
}

// dnsAttempt — одна попытка прямого пути: стратегия и (необязательно) закреплённый адрес.
type dnsAttempt struct {
	strat *obfsStrategy
	pin   string
	// h1 — DoH по HTTP/1.1 (в ClientHello только ALPN http/1.1). На МегаФон LTE адреса Cloudflare
	// пропускают рукопожатие, а запрос HTTP/2 не доходит; тот же запрос по HTTP/1.1 проходит (D47).
	h1 bool
}

func (a *dnsAttempt) key() string {
	if a == nil {
		return ""
	}
	name := obfsPlain
	if a.strat != nil {
		name = a.strat.name
	}
	if a.h1 {
		name += "~h1"
	}
	return name + "@" + a.pin
}

func (a *dnsAttempt) describe() string {
	name := a.strat.name
	if a.h1 {
		name += "/http1"
	}
	if a.pin == "" {
		return name
	}
	// Адрес в журнале маскируется так же, как у остальных строк приложения («194.87.196.***»).
	masked := a.pin
	if i := strings.LastIndexAny(masked, ".:"); i >= 0 {
		masked = masked[:i+1] + "***"
	}
	return name + " @" + masked
}

// obfsCandidates — порядок попыток прямого пути: основная стратегия по первым двум адресам и по
// запасному адресу хоста (obfsExtraAddresses: у cf. на МегаФон проходит только он, ждать его пятым
// значило не успеть в бюджет), затем вторая стратегия по первому адресу, остальные адреса, остальные
// стратегии. Запомненный победитель встаёт первым.
func obfsCandidates(spec *dnsUpstreamSpec) []dnsAttempt {
	ladder := obfsLadder(spec.host)
	if spec.obf != "" {
		if forced := obfsStrategyByName(spec.obf); forced != nil {
			ladder = []*obfsStrategy{forced}
		}
	}
	var addrs, extras []string
	if net.ParseIP(spec.host) == nil {
		seen := map[string]bool{}
		for _, a := range spec.bootstrap {
			if !seen[a] {
				seen[a] = true
				addrs = append(addrs, a)
			}
		}
		for _, a := range obfsExtraAddresses(spec.host) {
			if !seen[a] {
				seen[a] = true
				extras = append(extras, a)
			}
		}
	}
	if len(addrs) == 0 && len(extras) == 0 {
		addrs = []string{""}
	}
	if len(addrs) == 0 {
		addrs, extras = extras, nil
	}
	// Адрес победителя идёт первым: единичный сбой на нём (один из тринадцати на LTE) должен
	// повторяться тем же адресом с другой стратегией, а не уходить в отказ на целую минуту.
	win, haveWin := recallObfsWinner(spec.raw)
	if haveWin {
		if _, pin, found := strings.Cut(win, "@"); found && pin != "" {
			addrs = promoteAddress(addrs, extras, pin)
			extras = removeAddress(extras, pin)
		}
	}
	var out []dnsAttempt
	h1 := obfsPrefersHTTP1(spec.host)
	add := func(st *obfsStrategy, pin string) { out = append(out, dnsAttempt{strat: st, pin: pin, h1: h1}) }
	add(ladder[0], addrs[0])
	if len(addrs) > 1 {
		add(ladder[0], addrs[1])
	}
	for _, a := range extras {
		add(ladder[0], a)
	}
	if len(ladder) > 1 {
		add(ladder[1], addrs[0])
	}
	if len(addrs) > 2 {
		for _, a := range addrs[2:] {
			add(ladder[0], a)
		}
	}
	if len(ladder) > 2 {
		for _, st := range ladder[2:] {
			add(st, addrs[0])
		}
	}
	if h1 && len(out) > 0 {
		// Страховка на сети, где наоборот режется HTTP/1.1: один кандидат с h2 по запасному адресу
		// (у cf. это 172.64.80.1, на котором проходят оба протокола) четвёртым по очереди.
		alt := out[0]
		alt.h1 = false
		if len(extras) > 0 {
			alt.pin = extras[0]
		}
		at := 3
		if at > len(out) {
			at = len(out)
		}
		withAlt := append([]dnsAttempt(nil), out[:at]...)
		withAlt = append(withAlt, alt)
		out = append(withAlt, out[at:]...)
	}
	if len(out) > obfsMaxCandidates {
		out = out[:obfsMaxCandidates]
	}
	if haveWin {
		for i := range out {
			if out[i].key() == win {
				out[0], out[i] = out[i], out[0]
				break
			}
		}
	}
	return out
}

func removeAddress(list []string, addr string) []string {
	var out []string
	for _, a := range list {
		if a != addr {
			out = append(out, a)
		}
	}
	return out
}

// promoteAddress ставит addr (из addrs или extras) первым в addrs.
func promoteAddress(addrs, extras []string, addr string) []string {
	for _, a := range addrs {
		if a == addr {
			return append([]string{addr}, removeAddress(addrs, addr)...)
		}
	}
	for _, a := range extras {
		if a == addr {
			return append([]string{addr}, addrs...)
		}
	}
	return addrs
}

var (
	obfsWinnerMu sync.Mutex
	obfsWinners  = make(map[string]string)
)

func recallObfsWinner(raw string) (string, bool) {
	obfsWinnerMu.Lock()
	defer obfsWinnerMu.Unlock()
	win, ok := obfsWinners[raw]
	return win, ok
}

func rememberObfsWinner(spec *dnsUpstreamSpec, a dnsAttempt) {
	obfsWinnerMu.Lock()
	prev := obfsWinners[spec.raw]
	obfsWinners[spec.raw] = a.key()
	obfsWinnerMu.Unlock()
	if prev != a.key() {
		log.Printf("DNS %s: direct path answers with %s", spec.display, a.describe())
	}
}

// obfsAttemptFn — одна попытка гонки; переменная нужна тестам.
var obfsAttemptFn = resolveDNSAttempt

type obfsRaceResult struct {
	idx     int
	payload []byte
	label   string
	err     error
}

// raceDirectObfs — прямой путь одного апстрима: кандидаты стартуют по очереди, первый ответ
// выигрывает и запоминается. Весь запрос укладывается в budget, как и одна попытка раньше.
func raceDirectObfs(query []byte, spec *dnsUpstreamSpec, budget time.Duration) ([]byte, string, error) {
	cands := obfsCandidates(spec)
	deadline := time.Now().Add(budget)
	results := make(chan obfsRaceResult, len(cands))
	started, pending := 0, 0
	launch := func() {
		i := started
		started++
		pending++
		remaining := time.Until(deadline)
		if remaining < 200*time.Millisecond {
			remaining = 200 * time.Millisecond
		}
		attempt := cands[i]
		go func() {
			payload, label, err := obfsAttemptFn(query, spec, dnsRouteDirectObfs, &attempt, remaining)
			results <- obfsRaceResult{i, payload, label, err}
		}()
	}
	launch()
	stagger := time.NewTimer(obfsRaceStagger)
	defer stagger.Stop()
	hard := time.NewTimer(budget)
	defer hard.Stop()
	var lastErr error
	for {
		select {
		case r := <-results:
			pending--
			if r.err == nil {
				rememberObfsWinner(spec, cands[r.idx])
				return r.payload, r.label, nil
			}
			lastErr = r.err
			if started < len(cands) && time.Now().Before(deadline) {
				launch()
				stagger.Reset(obfsRaceStagger)
			} else if pending == 0 {
				return nil, "", lastErr
			}
		case <-stagger.C:
			if started < len(cands) && time.Now().Before(deadline) {
				launch()
				stagger.Reset(obfsRaceStagger)
			}
		case <-hard.C:
			if lastErr == nil {
				lastErr = fmt.Errorf("direct+obfs to %s: no answer in %s", spec.display, budget)
			}
			return nil, "", lastErr
		}
	}
}

func obfsDirectBudget(timeout time.Duration) time.Duration {
	budget := timeout / 2
	if budget > obfsDirectMaxBudget {
		budget = obfsDirectMaxBudget
	}
	if budget < obfsDirectMinBudget {
		budget = obfsDirectMinBudget
	}
	return budget
}

func resolveDNSObfsFirst(query []byte, spec *dnsUpstreamSpec, timeout time.Duration) ([]byte, string, error) {
	if obfsDirectAllowed(spec.raw, query, spec, timeout) {
		payload, label, err := raceDirectObfs(query, spec, obfsDirectBudget(timeout))
		if err == nil {
			noteObfsDirectOK(spec.raw)
			return payload, label, nil
		}
		suspendObfsDirect(spec.raw)
		log.Printf("DNS %s: direct+obfs failed (%v), using tunnel for %s", spec.display, err, obfsDirectSuspend)
		payload, label, tunnelErr := resolveDNSOnRoute(query, spec, dnsRouteTunnel, timeout)
		if tunnelErr == nil {
			return payload, label, nil
		}
		return nil, "", fmt.Errorf("obfs direct: %v; tunnel: %w", err, tunnelErr)
	}
	return resolveDNSOnRoute(query, spec, dnsRouteTunnel, timeout)
}

// resolveDNSObfsOnly — прямой путь с обфускацией без своего запасного туннеля: вход цепочки
// DNS-AI (`via=obfsonly`). Отказ уходит следующему апстриму списка; туннельные копии тех же
// входов стоят в списке за всеми прямыми. Пока прямой путь снят, отказывает сразу.
func resolveDNSObfsOnly(query []byte, spec *dnsUpstreamSpec, timeout time.Duration) ([]byte, string, error) {
	if !obfsDirectAllowed(spec.raw, query, spec, timeout) {
		return nil, "", fmt.Errorf("direct+obfs to %s suspended after a failure", spec.display)
	}
	payload, label, err := raceDirectObfs(query, spec, obfsDirectBudget(timeout))
	if err != nil {
		suspendObfsDirect(spec.raw)
		log.Printf("DNS %s: direct+obfs failed (%v), next entrance or tunnel; direct suspended %s", spec.display, err, obfsDirectSuspend)
		return nil, "", err
	}
	noteObfsDirectOK(spec.raw)
	return payload, label, nil
}

// obfsDirectAllowed отвечает, пробовать ли прямой путь в этом запросе. Пока путь
// снят, отвечает «нет»; по истечении срока запускает одну фоновую перепроверку и
// тоже отвечает «нет» — пользовательский запрос не ждёт пробы.
func obfsDirectAllowed(raw string, query []byte, spec *dnsUpstreamSpec, timeout time.Duration) bool {
	obfsDirectMu.Lock()
	state := obfsDirectStates[raw]
	if state == nil || state.suspendedUntil.IsZero() {
		obfsDirectMu.Unlock()
		return true
	}
	if time.Now().Before(state.suspendedUntil) || state.probing {
		obfsDirectMu.Unlock()
		return false
	}
	state.probing = true
	obfsDirectMu.Unlock()

	probe := append([]byte(nil), query...)
	go func() {
		_, _, err := raceDirectObfs(probe, spec, obfsDirectBudget(timeout))
		obfsDirectMu.Lock()
		defer obfsDirectMu.Unlock()
		current := obfsDirectStates[raw]
		if current != state {
			return // состояние сброшено сменой конфигурации
		}
		state.probing = false
		if err == nil {
			state.suspendedUntil = time.Time{}
			state.lastOK = time.Now()
			log.Printf("DNS %s: direct+obfs answers again, tunnel is fallback only", spec.display)
			return
		}
		state.suspendedUntil = time.Now().Add(obfsDirectSuspend)
	}()
	return false
}

func suspendObfsDirect(raw string) {
	obfsDirectMu.Lock()
	defer obfsDirectMu.Unlock()
	state := obfsDirectStates[raw]
	if state == nil {
		state = &obfsDirectState{}
		obfsDirectStates[raw] = state
	}
	state.suspendedUntil = time.Now().Add(obfsDirectSuspend)
}

func noteObfsDirectOK(raw string) {
	obfsDirectMu.Lock()
	defer obfsDirectMu.Unlock()
	state := obfsDirectStates[raw]
	if state == nil {
		state = &obfsDirectState{}
		obfsDirectStates[raw] = state
	}
	state.suspendedUntil = time.Time{}
	state.lastOK = time.Now()
}

// obfsDirectLabel — путь DNS-AI для журнала приложения: "direct-obfs", "tunnel"
// или пусто, если апстрим не в режиме obfs либо ещё не опрашивался.
func obfsDirectLabel(raw string) string {
	obfsDirectMu.Lock()
	defer obfsDirectMu.Unlock()
	state := obfsDirectStates[raw]
	if state == nil {
		return ""
	}
	if !state.suspendedUntil.IsZero() {
		return "tunnel"
	}
	return "direct-obfs"
}

func resetObfsDirectState() {
	obfsDirectMu.Lock()
	obfsDirectStates = make(map[string]*obfsDirectState)
	obfsDirectMu.Unlock()
	obfsWinnerMu.Lock()
	obfsWinners = make(map[string]string)
	obfsWinnerMu.Unlock()
}

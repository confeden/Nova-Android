package nova

import (
	"fmt"
	"log"
	"net"
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

// Разбиение ClientHello то же, что у регистрации WARP по умолчанию
// (`android-okhttp-multisplit-512`): 1 байт, затем 255 и 256 с паузой 4 мс.
var obfsDNSSplitPlan = []int{1, 255, 256}

const (
	obfsDNSFragmentBytes = 512
	obfsDNSFragmentDelay = 4 * time.Millisecond
)

type obfsDirectState struct {
	suspendedUntil time.Time
	probing        bool
	lastOK         time.Time
}

var (
	obfsDirectMu     sync.Mutex
	obfsDirectStates = make(map[string]*obfsDirectState)
)

// obfuscateDNSConn оборачивает прямое соединение к резолверу так, что первая
// запись (ClientHello) уходит несколькими TCP-сегментами. Остальные пути не трогает.
func obfuscateDNSConn(conn net.Conn, route dnsUpstreamRoute) net.Conn {
	if route != dnsRouteDirectObfs || conn == nil {
		return conn
	}
	if tcp, ok := conn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}
	return &fragmentedConn{
		Conn:          conn,
		splitPlan:     append([]int(nil), obfsDNSSplitPlan...),
		fragmentBytes: obfsDNSFragmentBytes,
		delay:         obfsDNSFragmentDelay,
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
		payload, label, err := resolveDNSOnRoute(query, spec, dnsRouteDirectObfs, obfsDirectBudget(timeout))
		if err == nil {
			noteObfsDirectOK(spec.raw)
			return payload, label, nil
		}
		suspendObfsDirect(spec.raw)
		log.Printf("DNS %s: direct+obfs failed (%v), using tunnel for %s", spec.target, err, obfsDirectSuspend)
		payload, label, tunnelErr := resolveDNSOnRoute(query, spec, dnsRouteTunnel, timeout)
		if tunnelErr == nil {
			return payload, label, nil
		}
		return nil, "", fmt.Errorf("obfs direct: %v; tunnel: %w", err, tunnelErr)
	}
	return resolveDNSOnRoute(query, spec, dnsRouteTunnel, timeout)
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
		_, _, err := resolveDNSOnRoute(probe, spec, dnsRouteDirectObfs, obfsDirectBudget(timeout))
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
			log.Printf("DNS %s: direct+obfs answers again, tunnel is fallback only", spec.target)
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
}

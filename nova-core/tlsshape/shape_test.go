package tlsshape

import (
	"testing"
	"time"

	utls "github.com/refraction-networking/utls"
)

func reset() {
	state.mu.Lock()
	defer state.mu.Unlock()
	state.index = 0
	state.rotatedAt = time.Time{}
	state.everRotated = false
}

func TestFirstProfileMatchesTheUpgradePersona(t *testing.T) {
	reset()
	if got := Current().Label; got != "chrome" {
		t.Fatalf("заголовок апгрейда представляется Chrome, а форма — %q", got)
	}
}

// Ради этого правила пакет и написан: край CF выбирает h2, если ему предложить
// h2, а апгрейд WebSocket в engine пишется по HTTP/1.1.
func TestEveryProfileOffersOnlyHTTP11(t *testing.T) {
	for _, profile := range Profiles() {
		spec, err := Spec(profile.HelloID)
		if err != nil {
			t.Fatalf("%s: спецификацию собрать не удалось: %v", profile.Label, err)
		}
		for _, extension := range spec.Extensions {
			alpn, ok := extension.(*utls.ALPNExtension)
			if !ok {
				continue
			}
			if len(alpn.AlpnProtocols) != 1 || alpn.AlpnProtocols[0] != "http/1.1" {
				t.Fatalf("%s: ALPN остался %v", profile.Label, alpn.AlpnProtocols)
			}
		}
	}
}

// Пресет запасной формы обязан не объявлять ALPN вовсе: к ней приходят тогда,
// когда собрать спецификацию не вышло, то есть подправить ALPN нечем.
func TestFallbackPresetDeclaresNoALPN(t *testing.T) {
	spec, err := utls.UTLSIdToSpec(FallbackHelloID)
	if err != nil {
		t.Fatalf("спецификацию запасной формы собрать не удалось: %v", err)
	}
	for _, extension := range spec.Extensions {
		if _, ok := extension.(*utls.ALPNExtension); ok {
			t.Fatal("запасная форма объявляет ALPN — край CF может выбрать h2")
		}
	}
}

func TestRotationWalksEveryProfileAndReturns(t *testing.T) {
	reset()
	profiles := Profiles()
	now := time.Unix(1_800_000_000, 0)
	seen := []string{Current().Label}
	for i := 1; i <= len(profiles); i++ {
		now = now.Add(Hold + time.Second)
		label := rotateAt(now)
		if label == "" {
			t.Fatalf("шаг %d: выдержка вышла, а форма не сменилась", i)
		}
		seen = append(seen, label)
	}
	for i, profile := range profiles {
		if seen[i] != profile.Label {
			t.Fatalf("порядок перебора нарушен: %v", seen)
		}
	}
	if seen[len(profiles)] != profiles[0].Label {
		t.Fatalf("перебор не вернулся к первой форме: %v", seen)
	}
}

func TestRotationHoldsWhileCandidatesFailTogether(t *testing.T) {
	reset()
	now := time.Unix(1_800_000_000, 0)
	first := rotateAt(now)
	if first == "" {
		t.Fatal("первый отказ должен сменить форму")
	}
	// Гонка кандидатов: несколько доменов отказали в одну миллисекунду.
	for i := 0; i < 5; i++ {
		if label := rotateAt(now.Add(time.Duration(i) * time.Millisecond)); label != "" {
			t.Fatalf("форма сменилась внутри выдержки: %q", label)
		}
	}
	if Current().Label != first {
		t.Fatalf("форма уехала внутри выдержки: %q вместо %q", Current().Label, first)
	}
	if label := rotateAt(now.Add(Hold + time.Second)); label == "" {
		t.Fatal("после выдержки форма должна смениться")
	}
}

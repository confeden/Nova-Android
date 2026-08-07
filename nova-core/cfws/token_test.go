package cfws

import (
	"strings"
	"testing"
)

// Ошибка в подписи стоит дорого: воркер ответит 404, и домен потеряется для
// всех пользователей сборки. Проверяются те же свойства, что и в CfWsTokenTest
// на стороне приложения, плюс общий эталон — если Go и Kotlin разойдутся,
// разойдётся и подпись.
const (
	testSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	testNow    = int64(1_800_000_000)
)

func TestOwnedHostsOnly(t *testing.T) {
	owned := []string{
		"nova-app.eu",
		"kws5.nova-app.eu",
		"kws5-1.nova-app.eu",
		"KWS5.Nova-App.EU.",
	}
	for _, host := range owned {
		if !IsOwnedHost(host) {
			t.Errorf("IsOwnedHost(%q) = false, ожидался true", host)
		}
	}

	foreign := []string{
		"pclead.co.uk",
		"kws5.web.telegram.org",
		// Подстрока в чужом домене не должна считаться своей.
		"nova-app.eu.evil.com",
		"xnova-app.eu",
	}
	for _, host := range foreign {
		if IsOwnedHost(host) {
			t.Errorf("IsOwnedHost(%q) = true, ожидался false", host)
		}
	}
}

func TestForeignHostsGetPlainBinary(t *testing.T) {
	for _, host := range []string{"pclead.co.uk", "kws5.web.telegram.org"} {
		if got := subprotocolHeaderAt(host, testSecret, testNow); got != PlainSubprotocol {
			t.Errorf("subprotocolHeaderAt(%q) = %q, ожидался %q", host, got, PlainSubprotocol)
		}
	}
}

func TestNoSecretMeansNoSignature(t *testing.T) {
	if got := subprotocolHeaderAt("kws5.nova-app.eu", "", testNow); got != PlainSubprotocol {
		t.Errorf("без секрета получили %q, ожидался %q", got, PlainSubprotocol)
	}
}

// Эталон совпадает с CfWsTokenTest: обе реализации обязаны давать один токен,
// иначе воркер увидит bad-signature ровно на той сборке, где подпись включили.
func TestGoldenVectorMatchesAppImplementation(t *testing.T) {
	cases := map[string]string{
		"kws5.nova-app.eu":   "nova1.15000000.76385ab1fbc4aacbc00bdaf0f13a52ec",
		"kws5-1.nova-app.eu": "nova1.15000000.f6b498ebcca66e422d2d145870ff8973",
		"kws2.nova-app.eu":   "nova1.15000000.b845bde9cdc531d958661185d335ae3d",
	}
	for host, expected := range cases {
		if got := Build(host, testSecret, testNow); got != expected {
			t.Errorf("Build(%q) = %q, ожидался %q", host, got, expected)
		}
	}
}

func TestHeaderFormat(t *testing.T) {
	header := subprotocolHeaderAt("kws5.nova-app.eu", testSecret, testNow)
	if !strings.HasPrefix(header, "binary, nova1.") {
		t.Fatalf("заголовок %q не начинается с \"binary, nova1.\"", header)
	}
	parts := strings.Split(strings.TrimPrefix(header, "binary, "), ".")
	if len(parts) != 3 {
		t.Fatalf("токен разбился на %d частей, ожидалось 3", len(parts))
	}
	if parts[0] != "nova1" {
		t.Errorf("версия = %q, ожидалась nova1", parts[0])
	}
	if parts[1] != "15000000" {
		t.Errorf("окно = %q, ожидалось 15000000", parts[1])
	}
	if len(parts[2]) != 32 {
		t.Errorf("длина подписи = %d, ожидалось 32", len(parts[2]))
	}
	if strings.ToLower(parts[2]) != parts[2] {
		t.Errorf("подпись %q не в нижнем регистре", parts[2])
	}
}

func TestWindowRollsEveryTwoMinutes(t *testing.T) {
	base := Build("kws5.nova-app.eu", testSecret, testNow)
	if same := Build("kws5.nova-app.eu", testSecret, testNow+119); same != base {
		t.Errorf("внутри окна подпись изменилась: %q против %q", same, base)
	}
	if next := Build("kws5.nova-app.eu", testSecret, testNow+120); next == base {
		t.Error("на границе окна подпись не изменилась")
	}
}

func TestSignatureIsBoundToHost(t *testing.T) {
	kws2 := Build("kws2.nova-app.eu", testSecret, testNow)
	kws5 := Build("kws5.nova-app.eu", testSecret, testNow)
	media := Build("kws5-1.nova-app.eu", testSecret, testNow)
	if kws2 == kws5 {
		t.Error("kws2 и kws5 получили одну подпись")
	}
	// Медийный узел — отдельное имя, значит и подпись отдельная.
	if kws5 == media {
		t.Error("kws5 и kws5-1 получили одну подпись")
	}
}

func TestHostNormalization(t *testing.T) {
	plain := Build("kws5.nova-app.eu", testSecret, testNow)
	for _, host := range []string{
		"KWS5.NOVA-APP.EU",
		"kws5.nova-app.eu.",
		"  kws5.nova-app.eu  ",
		// Host-заголовок может прийти с портом, воркер видит имя без него.
		"kws5.nova-app.eu:443",
	} {
		if got := Build(host, testSecret, testNow); got != plain {
			t.Errorf("Build(%q) = %q, ожидался %q", host, got, plain)
		}
	}
}

func TestDifferentSecretDiffersSignature(t *testing.T) {
	a := Build("kws5.nova-app.eu", testSecret, testNow)
	b := Build("kws5.nova-app.eu", "f"+testSecret[1:], testNow)
	if a == b {
		t.Error("разные секреты дали одну подпись")
	}
}

func TestSecretIsHotSwappable(t *testing.T) {
	t.Cleanup(func() { SetSecret("") })

	SetSecret("")
	if got := SubprotocolHeader("kws5.nova-app.eu"); got != PlainSubprotocol {
		t.Errorf("после сброса секрета получили %q, ожидался %q", got, PlainSubprotocol)
	}

	SetSecret(testSecret)
	if got := SubprotocolHeader("kws5.nova-app.eu"); !strings.HasPrefix(got, "binary, nova1.") {
		t.Errorf("после установки секрета получили %q без подписи", got)
	}
	if got := SubprotocolHeader("pclead.co.uk"); got != PlainSubprotocol {
		t.Errorf("чужому домену досталось %q, ожидался %q", got, PlainSubprotocol)
	}
}

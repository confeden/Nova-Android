package nova

import (
	"strings"
	"testing"
)

// Профиль AmneziaWG 3.1 из ключа `vpn://`, каким его отдаёт сама Amnezia.
//
// Ключи и адрес заменены на заведомо ненастоящие: тест проверяет перевод
// `.conf` в UAPI, а не чужой сервер. Набор полей и их форма — те самые, на
// которых ядро 2.0 молча работало как 2.0, потому что девять строк 3.x
// отбрасывались ещё в Kotlin (G127).
const awg31Sample = `[Interface]
Address = 10.8.1.7/32
DNS = 1.1.1.1, 1.0.0.1
PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
Jc = 6
Jmin = 10
Jmax = 50
S1 = 43
S2 = 143
S3 = 21
S4 = 12
H1 = 1
H2 = 2
H3 = 3
H4 = 4
HeaderProtectionKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=
ContentPaddingAddition = 10-100
RekeyAfterTime = 100-120
RekeyTimeout = 3-7
RejectAfterTime = 150-180
KeepaliveTimeout = 5-15
MaxHandshakeAttempts = 15-20
RandomTrailers = on
DisableCookies = on

[Peer]
PublicKey = CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=
PresharedKey = DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = 203.0.113.10:37955
PersistentKeepalive = 25-35
`

// Проверяет, что каждое поле 3.x доезжает до UAPI в том виде, какого ждёт ядро:
// ключ защиты заголовка — шестнадцатеричным, диапазоны — как есть, `on` —
// булевым `true`.
func TestAwg31SampleTranslates(t *testing.T) {
	uapi, err := configToUAPI(awg31Sample)
	if err != nil {
		t.Fatalf("configToUAPI failed: %v", err)
	}
	want := []string{
		"header_protection_key=",
		"content_padding_addition=10-100",
		"rekey_after_time=100-120",
		"rekey_timeout=3-7",
		"reject_after_time=150-180",
		"keepalive_timeout=5-15",
		"max_handshake_attempts=15-20",
		"random_trailers=true",
		"disable_cookies=true",
		"persistent_keepalive_interval=25-35",
		"jc=6", "s1=43", "s2=143", "s3=21", "s4=12",
		"h1=1", "h2=2", "h3=3", "h4=4",
	}
	for _, w := range want {
		if !strings.Contains(uapi, w) {
			t.Errorf("UAPI does not contain %q; uapi=%s", w, uapi)
		}
	}
	t.Logf("UAPI: %s", uapi)
}

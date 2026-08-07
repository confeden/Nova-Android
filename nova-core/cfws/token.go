// Package cfws считает подпись рукопожатия WebSocket для собственных
// поддоменов nova-app.eu.
//
// Поддомены `kws*.nova-app.eu` — это Worker Cloudflare, проксирующий WSS
// Telegram. Без подписи им может воспользоваться любая программа, узнавшая имя
// узла, а имена лежат в публичном репозитории.
//
// Формат заголовка:
//
//	Sec-WebSocket-Protocol: binary, nova1.<окно>.<подпись>
//
// Окно — номер двухминутного интервала от начала эпохи; воркер принимает ±2
// окна, то есть допускает расхождение часов до четырёх минут. Подпись привязана
// к имени узла: иначе токен, выданный для kws2, можно было бы предъявить для
// kws5.
//
// Расчёт живёт в Go, а не в Kotlin, потому что окно привязано ко времени
// соединения: пул держит WebSocket открытыми и добивает новые по мере расхода,
// поэтому посчитанный при старте приложения токен успел бы протухнуть. Kotlin
// передаёт сюда только сам секрет.
//
// Отдельный пакет, а не файл в engine: engine не собирается вне Android, и
// тесты подписи иначе негде было бы запускать.
package cfws

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

const (
	version     = "nova1"
	windowSecs  = 120
	macHexLen   = 32
	ownedDomain = "nova-app.eu"

	// PlainSubprotocol — заголовок без подписи. Достаётся всем чужим доменам
	// Cloudflare из общего пула: их воркеры про эту схему не знают.
	PlainSubprotocol = "binary"
)

var secretValue atomic.Value

// SetSecret задаёт секрет подписи. Пустая строка выключает подпись: отладочная
// сборка без секрета должна продолжать работать по публичным доменам, а не
// терять их.
func SetSecret(secret string) {
	secretValue.Store(strings.TrimSpace(secret))
}

func currentSecret() string {
	value, _ := secretValue.Load().(string)
	return value
}

// NormalizeHost приводит имя узла к тому виду, в котором его видит воркер:
// `url.hostname` в нижнем регистре, без порта, скобок и завершающей точки.
func NormalizeHost(host string) string {
	normalized := strings.ToLower(strings.TrimSpace(host))
	if idx := strings.LastIndex(normalized, "]"); idx >= 0 {
		normalized = strings.TrimPrefix(normalized[:idx], "[")
	} else if idx := strings.LastIndex(normalized, ":"); idx >= 0 &&
		strings.Count(normalized, ":") == 1 {
		normalized = normalized[:idx]
	}
	return strings.TrimSuffix(normalized, ".")
}

// IsOwnedHost сообщает, наш ли это узел.
func IsOwnedHost(host string) bool {
	normalized := NormalizeHost(host)
	return normalized == ownedDomain || strings.HasSuffix(normalized, "."+ownedDomain)
}

// Build считает сам токен вида `nova1.<окно>.<подпись>`. Пустая строка —
// посчитать не удалось.
func Build(host string, secret string, nowSeconds int64) string {
	normalized := NormalizeHost(host)
	if normalized == "" || strings.TrimSpace(secret) == "" {
		return ""
	}
	window := floorDiv(nowSeconds, windowSecs)
	mac := hmac.New(sha256.New, []byte(secret))
	mac.Write([]byte(strconv.FormatInt(window, 10) + "|" + normalized))
	digest := hex.EncodeToString(mac.Sum(nil))
	if len(digest) > macHexLen {
		digest = digest[:macHexLen]
	}
	return version + "." + strconv.FormatInt(window, 10) + "." + digest
}

// SubprotocolHeader отдаёт значение Sec-WebSocket-Protocol для узла на текущий
// момент времени.
func SubprotocolHeader(host string) string {
	return subprotocolHeaderAt(host, currentSecret(), time.Now().Unix())
}

func subprotocolHeaderAt(host string, secret string, nowSeconds int64) string {
	if strings.TrimSpace(secret) == "" || !IsOwnedHost(host) {
		return PlainSubprotocol
	}
	token := Build(host, secret, nowSeconds)
	if token == "" {
		return PlainSubprotocol
	}
	return PlainSubprotocol + ", " + token
}

func floorDiv(value int64, divisor int64) int64 {
	quotient := value / divisor
	if value%divisor != 0 && (value < 0) != (divisor < 0) {
		quotient--
	}
	return quotient
}

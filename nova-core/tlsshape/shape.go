// Package tlsshape задаёт форму ClientHello для маршрута Telegram и правило,
// по которому она меняется.
//
// Рукопожатие писал `crypto/tls` из Go, а его ClientHello не совпадает ни с
// одним браузером: набор, порядок и содержимое расширений у Go свои, и по ним
// соединение отделяется от браузерного раньше, чем начинает иметь значение имя
// в SNI. Нейтральное имя без правильной формы решает половину задачи: снаружи
// видно обычную зону, к которой ходит клиент, какого в природе нет.
//
// Заголовок апгрейда в engine представляется Chrome на Android. Форма
// рукопожатия обязана называть тот же браузер: персона, противоречащая себе на
// первом пакете, хуже отсутствующей.
//
// Профилей несколько не ради разнообразия, а ради лечения. Различить «имя
// заблокировано» и «отпечаток отвергнут» по одной попытке невозможно — они
// разделяются экспериментом: повторить то же имя другой формой hello. Поэтому
// профиль вращает ровно одна фаза отказа — рукопожатие, оставшееся без ответа.
// До отправки hello формы ещё не существует, после завершённого рукопожатия
// она уже принята: само рукопожатие и есть доказательство, что форма подошла.
//
// Отдельный пакет, а не файл в engine: engine не собирается вне Android, и
// тесты вращения иначе негде было бы запускать. Та же причина, что у cfws.
package tlsshape

import (
	"sync"
	"time"

	utls "github.com/refraction-networking/utls"
)

// Profile — форма hello под своим именем. Имя нужно журналу: без него смена
// формы выглядит как необъяснимая смена поведения.
type Profile struct {
	Label   string
	HelloID utls.ClientHelloID
}

// Hold — сколько форма держится, прежде чем её сменит очередной отказ.
//
// Гонка кандидатов бьёт по нескольким доменам сразу, и без выдержки один
// замолчавший узел прокрутил бы весь список за миллисекунды, не измерив ни
// одной формы. Выдержка даёт каждой форме дожить до собственного вердикта.
const Hold = 20 * time.Second

// FallbackLabel и FallbackHelloID — форма на случай, когда спецификацию
// профиля собрать не удалось.
//
// Выбрана не случайно: у этого пресета ALPN нет вовсе, значит край CF ответит
// HTTP/1.1 без нашего участия — а именно за это отвечает [Spec].
const FallbackLabel = "android-okhttp"

var FallbackHelloID = utls.HelloAndroid_11_OkHttp

// Profiles — порядок перебора форм.
func Profiles() []Profile {
	return []Profile{
		// Первым — тот браузер, которым представляется заголовок апгрейда.
		{Label: "chrome", HelloID: utls.HelloChrome_Auto},
		// Firefox остаётся браузерным профилем там, где Chrome уже отфильтрован.
		{Label: "firefox", HelloID: utls.HelloFirefox_Auto},
		// OkHttp приносит с собой каждое второе Android-приложение. Форма
		// заметно проще (TLS 1.2, ALPN не объявляется вовсе), зато на телефоне
		// она обычна и от браузерных отличается целиком, а не деталью.
		{Label: FallbackLabel, HelloID: FallbackHelloID},
	}
}

// Spec отдаёт спецификацию пресета, приведённую к http/1.1.
//
// Замерено на живой зоне: `utls.Config.NextProtos` пресеты игнорируют — ALPN
// лежит в самой спецификации, и Chrome с Firefox объявляют «h2, http/1.1» что
// бы ни стояло в конфиге. Край CF в ответ выбирает h2, а апгрейд WebSocket в
// engine пишется вручную по HTTP/1.1 и в h2-соединении не работает.
//
// Поэтому меняем содержимое одного расширения, не трогая ни состав, ни порядок
// остальных: в JA3 значения ALPN не входят, форма остаётся браузерной. JA4
// разницу увидит — там ALPN участвует, — но это одно поле против целиком
// небраузерного отпечатка Go, который стоял здесь до сих пор.
func Spec(id utls.ClientHelloID) (utls.ClientHelloSpec, error) {
	spec, err := utls.UTLSIdToSpec(id)
	if err != nil {
		return spec, err
	}
	for i, extension := range spec.Extensions {
		if _, ok := extension.(*utls.ALPNExtension); ok {
			spec.Extensions[i] = &utls.ALPNExtension{AlpnProtocols: []string{"http/1.1"}}
		}
	}
	return spec, nil
}

var state struct {
	mu          sync.Mutex
	index       int
	rotatedAt   time.Time
	everRotated bool
}

// Current — форма, которой идут соединения прямо сейчас.
func Current() Profile {
	profiles := Profiles()
	state.mu.Lock()
	defer state.mu.Unlock()
	return profiles[state.index%len(profiles)]
}

// Rotate переводит стрелку на следующую форму и возвращает её имя. Пустая
// строка — выдержка ещё не вышла, форма прежняя.
func Rotate() string {
	return rotateAt(time.Now())
}

func rotateAt(now time.Time) string {
	profiles := Profiles()
	state.mu.Lock()
	defer state.mu.Unlock()
	if state.everRotated && now.Sub(state.rotatedAt) < Hold {
		return ""
	}
	state.index = (state.index + 1) % len(profiles)
	state.rotatedAt = now
	state.everRotated = true
	return profiles[state.index].Label
}

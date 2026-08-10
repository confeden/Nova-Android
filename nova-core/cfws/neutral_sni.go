package cfws

import (
	"math/rand"
	"sync"
	"time"
)

// Маршрут Telegram уходит из открытого SNI в заголовок Host.
//
// Каждый CF-туннель открывался на имя вида `kws2.nova-app.eu`, и это имя шло в
// SNI — в открытый текст, до всякого шифрования. Одно правило `^kws\d+\.`
// кладёт весь пул доменов разом: имя читается раньше, чем начинает иметь
// значение TLS-отпечаток, и читается одинаково для всех доменов пула, потому
// что префикс общий. Пул из шести доменов при таком префиксе стоит ровно
// столько же, сколько один. Публичный пул устроен так же
// (`kws2.pclead.co.uk` и прочие), то есть переход на запасные домены от этого
// правила не спасает.
//
// Cloudflare маршрутизирует Workers по заголовку `Host`, который лежит внутри
// TLS. Расхождение SNI и Host край CF допускает в пределах одной зоны и
// отвергает за её пределами — это измерено на живом Worker в Nova PC
// (docs/adr/0004-neutral-sni.md): `nova-app.eu`, `www.nova-app.eu` и
// `cdn.nova-app.eu` при `Host: kws2.nova-app.eu` дали тот же ответ, что и
// литеральное имя, а контрольный `example.com` — 403.
//
// Меняется ровно одно значение — `ServerName` в tls.Config. Имя домена
// остаётся в Host, в ключе остывания маршрута, в журнале и в подписи
// рукопожатия: ничего не мигрирует, воркер менять не нужно.

// neutralSNIBadTTL — на сколько зона возвращается к литеральному имени, если
// подстановку не приняли. Подстановка — оптимизация, а не требование, поэтому
// первого признака, что сеть её не любит, достаточно, чтобы перестать за неё
// платить.
const neutralSNIBadTTL = 15 * time.Minute

// Зона у пакета ровно одна — ownedDomain, — поэтому и выбор имени, и срок
// отката хранятся одним значением, а не картой по зонам.
var neutralSNI struct {
	mu           sync.Mutex
	choice       string
	literalUntil time.Time
}

// neutralSNICandidates — имена, которые край CF принимает для наших маршрутов.
//
// Кандидат ровно один, и это результат замера, а не осторожности. Nova PC
// брала apex и `www.`, проверив только `kws2`. На Android с тем же валидным
// токеном:
//
//	kws5-1.nova-app.eu  SNI kws5-1.nova-app.eu   -> 429 (дошло до маршрута)
//	kws5-1.nova-app.eu  SNI nova-app.eu          -> 403  ← три прогона подряд
//	kws5-1.nova-app.eu  SNI www.nova-app.eu      -> 429
//	kws2.nova-app.eu    SNI nova-app.eu          -> 429
//
// То есть apex край принимает не для всякого маршрута: медийный `kws5-1` с ним
// отвергается стабильно. Держать кандидата, который на трети маршрутов даёт
// 403 и уводит зону в пятнадцатиминутный откат, дороже, чем потерять разброс
// имён между пользователями.
//
// Других кандидатов у зоны нет: wildcard на ней отсутствует (случайное имя не
// резолвится), собственные записи есть только у apex, `www.` и `relay.` — а
// `relay.` живёт не на Cloudflare и в SNI маршрута CF не годится.
func neutralSNICandidates() []string {
	return []string{"www." + ownedDomain}
}

// NeutralSNI отдаёт имя для SNI при соединении с host и признак того, что имя
// подставлено.
//
// Чужие домены возвращаются нетронутыми: у собственных `kwsN.web.telegram.org`
// Telegram край действительно маршрутизирует по SNI, а против воркеров общего
// пула такого измерения никто не делал — ломать чужую инфраструктуру по
// догадке хуже, чем оставить читаемое имя.
func NeutralSNI(host string) (string, bool) {
	return neutralSNIAt(host, time.Now())
}

func neutralSNIAt(host string, now time.Time) (string, bool) {
	normalized := NormalizeHost(host)
	if normalized == "" || !IsOwnedHost(normalized) {
		return normalized, false
	}
	neutralSNI.mu.Lock()
	defer neutralSNI.mu.Unlock()
	if now.Before(neutralSNI.literalUntil) {
		return normalized, false
	}
	if neutralSNI.choice == "" {
		// Одно имя на зону до конца процесса, а не новое на каждое соединение:
		// клиент, разговаривающий с одним контент-хостом, выглядит как любой
		// другой, а клиент, перебирающий пять имён в минуту, — это отдельная
		// сигнатура. Случайный выбор здесь дал бы ещё и разброс между
		// пользователями — пока кандидат один, разброса нет, но появится
		// второй проверенный, и менять придётся только список.
		candidates := neutralSNICandidates()
		neutralSNI.choice = candidates[rand.Intn(len(candidates))]
	}
	if neutralSNI.choice == normalized {
		return normalized, false
	}
	return neutralSNI.choice, true
}

// NoteNeutralSNIRejected возвращает зону к литеральному имени на
// neutralSNIBadTTL.
//
// Отвечает true, только когда откат начался именно сейчас: внутри окна отката
// попытки продолжают приходить, и без этого признака журнал повторял бы одну и
// ту же строку.
func NoteNeutralSNIRejected(host string) bool {
	return noteNeutralSNIRejectedAt(host, time.Now())
}

func noteNeutralSNIRejectedAt(host string, now time.Time) bool {
	if !IsOwnedHost(host) {
		return false
	}
	neutralSNI.mu.Lock()
	defer neutralSNI.mu.Unlock()
	started := !now.Before(neutralSNI.literalUntil)
	neutralSNI.literalUntil = now.Add(neutralSNIBadTTL)
	return started
}

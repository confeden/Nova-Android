package nova

import (
	"container/list"
	"fmt"
	"log"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"

	"nova-core/dnsname"
)

// Обход по доменам и зонам: часть первая — политика и выученные адреса.
//
// Идея целиком. Маршруты фиксируются в момент establish(), менять их без
// переподключения нельзя, а список исключений на Android до 13 стоит ~31
// маршрута на каждый адрес IPv4. Поэтому обход зоны сделан не маршрутами, а
// так: ядро и без того перехватывает DNS, из ответа берётся имя и адреса, имя
// проверяется правилами, и совпавшие адреса запоминаются. Дальше пакет к
// такому адресу не уходит в туннель, а перехватывается локальным netstack и
// пересобирается наружу через защищённый сокет (файл domain_bypass_relay.go).
// Ни маршрутов, ни переподключения.
//
// Из этого следует главное ограничение: без включённого перехвата DNS учиться
// не на чем, и обход по зонам просто не работает.

// Потолок выученных адресов. Он жёсткий, и вот почему.
//
// Каждый выученный адрес нужно поднять локальным адресом netstack, а
// ensureLocalAddressLocked в tools/amneziawg-go/tun/netstack/tun.go умеет
// только ДОБАВЛЯТЬ: API удаления там нет вовсе. То есть вытеснение из нашего
// LRU освобождает место в карте, но не освобождает адрес в netstack — стек
// продолжает держать его до конца сессии. Потолок здесь ровно для того, чтобы
// это накопление было ограниченным и предсказуемым, а не росло вместе с
// историей просмотра.
//
// Что потолок НЕ ограничивал раньше: слушателей, их горутины и уже открытые
// перелитые соединения. Они висели на вытесненном адресе до конца сессии, то
// есть росли вместе с ротацией CDN, а не вместе с этим числом. Теперь
// вытеснение зовёт domainBypassReleaseAddrs, и связанное с адресом закрывается
// сразу; неснимаемым остаётся только сам адрес в netstack.
const domainBypassLearnedCap = 256

type domainBypassConfig struct {
	enabled   bool
	rules     dnsname.Rules
	signature string
}

var domainBypassMu sync.RWMutex
var domainBypassState domainBypassConfig
var domainBypassLogBudget atomic.Int64

// Быстрый флаг для горячего пути чтения из TUN: через него проходит каждый
// исходящий пакет, и брать там RWMutex ради одного bool незачем.
var domainBypassEnabledFlag atomic.Bool

var domainBypassLearnedMu sync.RWMutex
var domainBypassLearnedIndex = make(map[netip.Addr]*list.Element)
var domainBypassLearnedOrder = list.New()
var domainBypassLearnedSize atomic.Int64

// Поколение политики. Растёт на каждый вызов SetDomainBypassPolicy.
//
// Нужно из-за гонки, которую иначе не закрыть: ответ DNS разбирается вне
// замка, и правила могут смениться, пока адрес идёт от AnswerAddrs до LRU.
// Без поколения такой адрес ложится в только что очищенный список и продолжает
// уводить трафик мимо туннеля по снятому правилу.
var domainBypassGeneration atomic.Int64

// Состояние релея: 0 — не поднимался, 1 — работает, 2 — сдался.
//
// Третье значение существует ровно затем, чтобы «обход включён, а счётчики
// стоят» не выглядело как «трафика просто не было». Релей может отказать сам —
// см. domainBypassPumpRetries, — и об этом обязан узнать экран (I4).
const (
	domainBypassRelayOff = iota
	domainBypassRelayAlive
	domainBypassRelayGaveUp
)

var domainBypassRelayState atomic.Int32

var domainBypassDropped atomic.Int64
var domainBypassRelayed atomic.Int64
var domainBypassInstalled atomic.Int64
var domainBypassListenerCount atomic.Int64
var domainBypassQuicSkipped atomic.Int64

// SetDomainBypassPolicy задаёт правила обхода по доменам и зонам.
//
// Смена правил очищает выученные адреса. Иначе снятая на экране галочка
// продолжала бы действовать до переподключения: адрес уже выучен, и релей
// уводил бы его наружу, хотя правила его больше не покрывают.
func SetDomainBypassPolicy(enabled bool, zonesCSV string, domainsCSV string, cyrillic bool) {
	rules := dnsname.ParseRules(zonesCSV, domainsCSV, cyrillic)
	signature := rules.Signature()
	active := enabled && !rules.Empty()

	// Всё состояние политики меняется под одним замком. Раздельно нельзя: два
	// одновременных вызова успевали разъехаться — флаг горячего пути от одного,
	// правила от другого, — и релей продолжал перехватывать по правилам,
	// которые служба считала снятыми.
	domainBypassMu.Lock()
	changed := domainBypassState.signature != signature
	domainBypassState = domainBypassConfig{
		enabled:   active,
		rules:     rules,
		signature: signature,
	}
	domainBypassEnabledFlag.Store(active)
	domainBypassLogBudget.Store(16)
	domainBypassGeneration.Add(1)
	if changed || !active {
		domainBypassForgetAll()
	}
	domainBypassMu.Unlock()

	if active {
		zones := make([]string, 0, len(rules.Zones))
		for zone := range rules.Zones {
			zones = append(zones, zone)
		}
		log.Printf(
			"Domain bypass enabled: zones=%d(%s) domains=%d cyrillic=%v",
			len(zones),
			strings.Join(zones, ","),
			len(rules.Domains),
			rules.Cyrillic,
		)
	} else {
		log.Printf("Domain bypass disabled")
	}
}

// GetDomainBypassStats отдаёт счётчики строкой key=value через перевод строки.
func GetDomainBypassStats() string {
	return fmt.Sprintf(
		"learned=%d\nrelayed=%d\ninstalled=%d\nlisteners=%d\ndropped=%d\nquicSkipped=%d\nrelayState=%d",
		domainBypassLearnedSize.Load(),
		domainBypassRelayed.Load(),
		domainBypassInstalled.Load(),
		domainBypassListenerCount.Load(),
		domainBypassDropped.Load(),
		domainBypassQuicSkipped.Load(),
		domainBypassRelayState.Load(),
	)
}

// getDomainBypassConfig отдаёт копию конфигурации.
//
// Rules внутри содержит карту зон, и копия структуры делит её с оригиналом.
// Это безопасно: ParseRules каждый раз строит новую карту и после публикации
// её никто не меняет.
func getDomainBypassConfig() domainBypassConfig {
	domainBypassMu.RLock()
	defer domainBypassMu.RUnlock()
	return domainBypassState
}

// getDomainBypassConfigAt отдаёт конфигурацию вместе с её поколением.
//
// Читается под тем же замком, что и запись, — иначе можно получить правила от
// одного поколения и номер от следующего, и проверка в LRU стала бы ложной.
func getDomainBypassConfigAt() (domainBypassConfig, int64) {
	domainBypassMu.RLock()
	defer domainBypassMu.RUnlock()
	return domainBypassState, domainBypassGeneration.Load()
}

func domainBypassActive() bool {
	return domainBypassEnabledFlag.Load() && domainBypassLearnedSize.Load() > 0
}

// domainBypassLearnFromAnswer вызывается на каждый перехваченный DNS-ответ.
//
// Матчится имя из вопроса, а не владельцы записей: за CNAME может стоять чужая
// CDN-зона, но пользователь просил обход именно того имени, которое набрал.
func domainBypassLearnFromAnswer(payload []byte) {
	if !domainBypassEnabledFlag.Load() {
		return
	}
	cfg, generation := getDomainBypassConfigAt()
	if !cfg.enabled {
		return
	}
	qname, addrs, ok := dnsname.AnswerAddrs(payload)
	if !ok || len(addrs) == 0 {
		return
	}
	if !cfg.rules.Match(qname) {
		return
	}
	added := 0
	for _, addr := range addrs {
		if domainBypassRemember(addr, generation) {
			added++
		}
	}
	if added > 0 && domainBypassLogBudget.Add(-1) >= 0 {
		log.Printf(
			"Domain bypass learned %d address(es) for %s (total=%d dropped=%d)",
			added,
			qname,
			domainBypassLearnedSize.Load(),
			domainBypassDropped.Load(),
		)
	}
}

// domainBypassRoutable отсеивает адреса, которые уводить наружу нельзя.
//
// Через релей адрес выходит защищённым сокетом, то есть по маршрутам самого
// устройства. Для частных, link-local и CGNAT-адресов это означает трафик в
// локальную сеть или к метаданным провайдера в обход туннеля — по одному
// DNS-ответу, который никто не проверял. Публичный смысл у обхода есть, у
// этих — нет.
func domainBypassRoutable(addr netip.Addr) bool {
	if !addr.IsValid() || addr.IsUnspecified() || addr.IsLoopback() || addr.IsMulticast() {
		return false
	}
	if addr.IsPrivate() || addr.IsLinkLocalUnicast() || addr.IsLinkLocalMulticast() ||
		addr.IsInterfaceLocalMulticast() {
		return false
	}
	// 100.64.0.0/10 (RFC 6598). В net/netip своего предиката нет.
	if addr.Is4() {
		octets := addr.As4()
		if octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127 {
			return false
		}
	}
	return true
}

// domainBypassRemember кладёт адрес в LRU и возвращает true, если он новый.
//
// generation — поколение политики, при котором ответ был разобран. Если правила
// с тех пор сменились, адрес отбрасывается: иначе снятое правило воскресало бы
// ответом, который был в разборе в момент снятия.
func domainBypassRemember(addr netip.Addr, generation int64) bool {
	addr = addr.Unmap()
	if !domainBypassRoutable(addr) {
		return false
	}
	var evicted []netip.Addr
	domainBypassLearnedMu.Lock()
	if domainBypassGeneration.Load() != generation {
		domainBypassLearnedMu.Unlock()
		return false
	}
	if element, exists := domainBypassLearnedIndex[addr]; exists {
		domainBypassLearnedOrder.MoveToFront(element)
		domainBypassLearnedMu.Unlock()
		return false
	}
	domainBypassLearnedIndex[addr] = domainBypassLearnedOrder.PushFront(addr)
	for domainBypassLearnedOrder.Len() > domainBypassLearnedCap {
		oldest := domainBypassLearnedOrder.Back()
		if oldest == nil {
			break
		}
		domainBypassLearnedOrder.Remove(oldest)
		if victim, ok := oldest.Value.(netip.Addr); ok {
			delete(domainBypassLearnedIndex, victim)
			evicted = append(evicted, victim)
		}
		domainBypassDropped.Add(1)
	}
	domainBypassLearnedSize.Store(int64(domainBypassLearnedOrder.Len()))
	domainBypassLearnedMu.Unlock()
	// Слушатели закрываются уже без замка LRU: они берут свой (p.mu), и
	// вкладывать один в другой означало бы вводить порядок, которого больше
	// нигде нет.
	domainBypassReleaseAddrs(evicted)
	return true
}

// domainBypassForget убирает один адрес из выученных.
//
// Нужен там, где релей выяснил, что наружу по этому адресу не выйти: пока адрес
// числится выученным, каждый следующий SYN снова перехватывается, локальное
// рукопожатие снова завершается, прямой набор снова падает — и соединение
// никогда не доходит до туннеля. Забыть адрес значит вернуть его туннелю.
func domainBypassForget(addr netip.Addr) bool {
	addr = addr.Unmap()
	domainBypassLearnedMu.Lock()
	element, exists := domainBypassLearnedIndex[addr]
	if exists {
		domainBypassLearnedOrder.Remove(element)
		delete(domainBypassLearnedIndex, addr)
		domainBypassLearnedSize.Store(int64(domainBypassLearnedOrder.Len()))
	}
	domainBypassLearnedMu.Unlock()
	if exists {
		domainBypassReleaseAddrs([]netip.Addr{addr})
	}
	return exists
}

// domainBypassKnows — проверка на горячем пути чтения из TUN.
//
// Только чтение под RLock: свежесть в LRU обновляется на обучении и на приёме
// соединения, а не на каждом пакете — иначе один поток на 443 порту
// монополизировал бы запись в список.
func domainBypassKnows(addr netip.Addr) bool {
	if domainBypassLearnedSize.Load() == 0 {
		return false
	}
	addr = addr.Unmap()
	domainBypassLearnedMu.RLock()
	_, exists := domainBypassLearnedIndex[addr]
	domainBypassLearnedMu.RUnlock()
	return exists
}

// domainBypassTouch поднимает адрес в LRU: по нему только что пошло соединение.
func domainBypassTouch(addr netip.Addr) {
	addr = addr.Unmap()
	domainBypassLearnedMu.Lock()
	if element, exists := domainBypassLearnedIndex[addr]; exists {
		domainBypassLearnedOrder.MoveToFront(element)
	}
	domainBypassLearnedMu.Unlock()
}

// domainBypassForgetAll забывает всё выученное.
//
// Поднятый локальный адрес netstack остаётся: ensureLocalAddressLocked умеет
// только добавлять. А вот слушателя и его горутину закрыть можно и нужно — они
// и есть то, что накапливалось бы всю сессию. Плюс рвутся уже установленные
// релеем соединения: иначе снятое правило продолжало бы действовать часами на
// том, что успело открыться, — ровно то, чего обещание «без переподключения»
// не подразумевает.
func domainBypassForgetAll() {
	domainBypassLearnedMu.Lock()
	domainBypassLearnedIndex = make(map[netip.Addr]*list.Element)
	domainBypassLearnedOrder = list.New()
	// Счётчик обнуляется под тем же замком, что и правки списка: снаружи он
	// мог обнулиться уже после чужой вставки, и обход выключался бы до
	// следующего выученного адреса.
	domainBypassLearnedSize.Store(0)
	domainBypassLearnedMu.Unlock()
	domainBypassReleaseAll()
}

// domainBypassResetSession закрывает счётчики вместе с сеансом.
//
// Счётчики жили от запуска процесса, а не от подключения: после переподключения
// экран показывал «потоков мимо туннеля: 900» от прошлого сеанса, и отличить их
// от нынешних было нельзя. Выученные адреса тем более обязаны уйти — сеть
// сменилась, и вчерашний адрес CDN уводил бы трафик в никуда.
func domainBypassResetSession() {
	domainBypassForgetAll()
	domainBypassRelayed.Store(0)
	domainBypassDropped.Store(0)
	domainBypassQuicSkipped.Store(0)
	domainBypassInstalled.Store(0)
	domainBypassListenerCount.Store(0)
	domainBypassRelayState.Store(domainBypassRelayOff)
}

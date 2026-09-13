package nova

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"

	pionnet "github.com/pion/transport/v4"
	pt "gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/goptlib"
	"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/transports/base"
	sflib "gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2/client/lib"
)

// Snowflake как транспорт реестра `ptTransports`.
//
// ## Почему это адаптер, а не просто ещё одна строка в карте
//
// Остальные три транспорта (obfs4, webtunnel, meek_lite) приходят из lyrebird и
// уже реализуют `base.Transport`. Snowflake живёт в своём проекте и имеет свой
// интерфейс: `NewSnowflakeClient(ClientConfig)` и `Dial()` без адреса. Поэтому
// здесь тонкая обёртка, переводящая одно в другое.
//
// ## Чем snowflake отличается от соседей по реестру, и это не мелочь
//
// У obfs4 и webtunnel есть адрес моста: `ptProxy` открывает к нему TCP нашим
// `protectedDial` — то есть сокетом, помеченным `protect()`, который не уходит в
// собственный туннель. У snowflake адреса нет вовсе. Клиент договаривается через
// брокера, а данные идут по WebRTC, и сокеты под это заводит pion — сам, минуя
// нашу функцию дозвона. Поэтому `address` и `dialFn` здесь **не используются**, и
// притворяться, что используются, нельзя.
//
// Поэтому сокеты помечаются не здесь, а ловушками в самом snowflake — см.
// `installSnowflakeHooks` ниже и `tor_snowflake_net.go`. С 1.32.2 snowflake —
// полноценный способ входа на экране (`kb/dns-tunnel-and-tor.md`).
//
// ## Аргументы моста
//
// Имена те же, что у собственного PT-клиента snowflake (`client/snowflake.go`):
// `url`, `ampcache`, `sqsqueue`, `sqscreds`, `fronts`/`front`, `ice`, `max`,
// `utls-imitate`, `utls-nosni`, `fingerprint`. Разбирать их своими именами было
// бы отдельной несовместимостью: строки мостов приходят из общих источников и
// написаны под эти.

const snowflakeTransportName = "snowflake"

// Потолок на `max=` из строки моста: столько «снежинок» клиент держит разом.
//
// Восемь с большим запасом: Tor Browser и сам snowflake ходят с одной, а число
// из строки едет прямо в ёмкость канала — см. разбор в `ParseArgs`.
const snowflakeMaxPeers = 8

// Потолок на число кэшированных клиентов у одной фабрики.
//
// Настоящих строк мостов snowflake две. Больше берётся только из чужого
// источника, а локальный SOCKS проверки прав не имеет вовсе (см. шапку
// `ptProxy`): приложение на телефоне может в цикле подавать строки с новым
// `url=` и на каждую получать свой `*sflib.Transport`, а с ним ещё и зонд NAT.
// Это рост памяти и усилитель исходящего трафика разом, поэтому карта
// ограничена, а отказ говорит почему.
const snowflakeMaxClients = 16

// Ловушки snowflake ставятся один раз на процесс.
//
// Два места, где snowflake заводит сокеты сам: pion (ICE и DTLS) и дозвон до
// брокера. Оба идут мимо нашей пометки `protect()`, потому что она поимённая —
// живёт в `Control:` у каждого dialer'а, а не перехватывает пакет `net`
// целиком. Непомеченный сокет при поднятом туннеле уходит в этот же туннель,
// то есть snowflake пробивался бы к мосту через то, что сам и поднимает (G154).
//
// `sync.Once`, а не присваивание в `init()`: ловушки — это глобальные
// переменные чужого пакета, и ставить их надо тогда, когда транспорт реально
// понадобился, а не при загрузке библиотеки.
var snowflakeHooksOnce sync.Once

// Обе ловушки читаются обратно только тестом: снаружи пакета их не видно, а
// проверить, что они доехали, надо — иначе защищённая сеть просто никем не
// используется, и об этом никто не узнает.
func snowflakeNetWrapperInstalled() func(pionnet.Net) pionnet.Net { return sflib.NetWrapper }

func snowflakeBrokerDialInstalled() func(context.Context, string, string) (net.Conn, error) {
	return sflib.BrokerDialContext
}

func installSnowflakeHooks() {
	snowflakeHooksOnce.Do(func() {
		sflib.NetWrapper = func(pionnet.Net) pionnet.Net {
			// Базовая сеть snowflake отбрасывается намеренно: наша строится от
			// того же `stdnet`, и брать чужую значило бы зависеть от того, чем
			// именно её там обернули.
			protected, err := newProtectedPionNet()
			if err != nil {
				// Молчать нельзя (I4): без пометки транспорт «работает», но
				// заворачивает себя в собственный туннель, и снаружи это
				// выглядит как «snowflake не соединяется».
				log.Printf("snowflake: защищённую сеть для pion собрать не удалось: %v", err)
				// И `nil` отдавать нельзя тем более: pion на `nil` молча заводит
				// обычную сеть (`pion/ice/agent.go:467`), то есть непомеченные
				// сокеты — ровно ту утечку, ради которой всё это и написано.
				// Отдаём сеть, которая честно не создаёт ничего.
				return &failingPionNet{
					err: fmt.Errorf("snowflake: сеть без пометки protect() не используется: %w", err),
				}
			}
			return protected
		}
		sflib.BrokerDialContext = func(ctx context.Context, network, addr string) (net.Conn, error) {
			return snowflakeDialer().DialContext(ctx, network, addr)
		}
	})
}

type snowflakeTransport struct{}

func (t *snowflakeTransport) Name() string { return snowflakeTransportName }

func (t *snowflakeTransport) ClientFactory(stateDir string) (base.ClientFactory, error) {
	return &snowflakeClientFactory{transport: t}, nil
}

// ServerFactory не поддерживается намеренно: сервером snowflake мы не бываем, а
// заглушка, возвращающая рабочий объект, означала бы «умеем» там, где не умеем.
func (t *snowflakeTransport) ServerFactory(stateDir string, args *pt.Args) (base.ServerFactory, error) {
	return nil, errors.New("snowflake: серверная сторона в ядре не собрана")
}

type snowflakeClientFactory struct {
	transport base.Transport

	// Клиент держит собственный пул соединений с прокси-«снежинками» и стоит
	// дорого в заводке, поэтому он переживает отдельный Dial. Но один на всю
	// фабрику его держать нельзя: фабрика одна на транспорт, а строк мостов
	// snowflake у tor две, и отличаются они как раз отпечатком моста, к
	// которому надо прийти. Единственный кэшированный клиент отдавал бы на
	// вторую строку соединение, настроенное по первой, — то есть приводил бы
	// tor к чужому мосту, и тот честно рвал бы связь на сверке личности.
	//
	// Отсюда карта, ключ которой — сама строка моста (см. [snowflakeArgsKey]).
	mu      sync.Mutex
	clients map[string]*sflib.Transport
}

// snowflakeArgs — разобранная строка моста вместе с ключом её кэша.
type snowflakeArgs struct {
	key    string
	config sflib.ClientConfig
}

// snowflakeArgsKey — ключ кэша клиентов, собранный из **самих аргументов**
// строки моста, а не из полей разобранной конфигурации.
//
// Так надёжнее ровно в одном, но решающем смысле: поля у `sflib.ClientConfig`
// прибавляются с версиями snowflake (в собранной сейчас есть, например,
// `KeepLocalAddresses`, `FrontDomain` и две настройки CovertDTLS), и ключ,
// перечисляющий поля руками, на первом же новом аргументе начнёт молча
// склеивать разные мосты в один клиент — то есть воспроизведёт тот самый
// дефект, ради которого кэш и разделён. Аргументы же покрывают и те поля, о
// которых мы ещё не знаем.
//
// Печать всей структуры через `%#v` не годится по другой причине: в ней есть
// `CommunicationProxy *url.URL`, и печатается он адресом в памяти. Ключ по
// адресу не совпал бы сам с собой, и кэш выродился бы в «новый клиент на каждое
// соединение» — из экономии превратился бы в утечку пула WebRTC.
func snowflakeArgsKey(args *pt.Args) string {
	if args == nil {
		return ""
	}
	names := make([]string, 0, len(*args))
	for name := range *args {
		names = append(names, name)
	}
	sort.Strings(names)
	parts := make([]string, 0, len(names)*2)
	for _, name := range names {
		parts = append(parts, name)
		parts = append(parts, (*args)[name]...)
	}
	return strings.Join(parts, "\x00")
}

func (f *snowflakeClientFactory) Transport() base.Transport { return f.transport }

func (f *snowflakeClientFactory) OnEvent(func(base.TransportEvent)) {}

// ParseArgs переводит аргументы строки моста в конфигурацию клиента.
//
// Отказывает в двух случаях, и оба — до того, как начата попытка соединения.
// Нет `url=` брокера: договариваться не с кем. Есть `sqsqueue=`/`sqscreds=`:
// такая строка увела бы snowflake в `log.Fatalln` и убила бы процесс — разбор
// ниже по тексту.
func (f *snowflakeClientFactory) ParseArgs(args *pt.Args) (interface{}, error) {
	config := sflib.ClientConfig{Max: 1}

	if value, ok := args.Get("url"); ok {
		config.BrokerURL = value
	}
	if config.BrokerURL == "" {
		return nil, errors.New("snowflake: в строке моста нет url= брокера")
	}
	if value, ok := args.Get("ampcache"); ok {
		config.AmpCacheURL = value
	}
	// Рандеву через SQS отклоняется здесь, и это не разборчивость, а защита
	// процесса.
	//
	// `createBrokerTransport` в snowflake выбирает способ встречи так: если
	// `SQSQueueURL` не пуст, а вместе с ним не пуст `BrokerURL` или
	// `AmpCacheURL`, он зовёт `log.Fatalln` — то есть `os.Exit(1)`
	// (`client/lib/rendezvous.go:106-112`). Ещё три такие же ловушки рядом:
	// пустой `sqscreds` и два разбора адреса очереди
	// (`rendezvous_sqs.go:47,57`). А `url=` мы требуем всегда, парой строк
	// выше, значит `BrokerURL` непуст по построению — и **любая** строка моста
	// с `sqsqueue=` гарантированно убивала бы процесс `:vpn` целиком, вместе с
	// живым туннелем.
	//
	// Строки мостов приходят по сети (Moat) и от человека из буфера обмена, то
	// есть это не теоретический случай, а чужой ввод, роняющий процесс. Отказ
	// же обычной ошибкой tor понимает: мост помечается негодным, остальные
	// продолжают работать.
	//
	// Пользоваться SQS мы и не собирались: строка такого моста несёт в себе
	// ключ доступа AWS чужого проекта.
	if value, ok := args.Get("sqsqueue"); ok && value != "" {
		return nil, errors.New("snowflake: рандеву через SQS не поддерживается (строка моста с sqsqueue=)")
	}
	if value, ok := args.Get("sqscreds"); ok && value != "" {
		return nil, errors.New("snowflake: рандеву через SQS не поддерживается (строка моста с sqscreds=)")
	}
	// `fronts` — список через запятую, `front` — одиночное имя, оставшееся от
	// старых строк. Порядок проверки тот же, что у самого snowflake.
	if value, ok := args.Get("fronts"); ok {
		if value != "" {
			config.FrontDomains = strings.Split(strings.TrimSpace(value), ",")
		}
	} else if value, ok := args.Get("front"); ok {
		config.FrontDomains = []string{value}
	}
	if value, ok := args.Get("ice"); ok {
		if value != "" {
			config.ICEAddresses = strings.Split(strings.TrimSpace(value), ",")
		}
	}
	// `max=` обязан иметь потолок, и это снова не аккуратность, а защита процесса.
	//
	// Число уходит в snowflake как есть (`client/lib/snowflake.go`: `max := 1; if
	// config.Max > max { max = config.Max }`) и доезжает до
	// `make(chan *WebRTCPeer, tongue.GetMax())` (`client/lib/peers.go`). Ни одного
	// клампа по дороге нет, а `make` с огромной ёмкостью — это `fatal error:
	// makechan: size out of range` либо попытка занять гигабайты: и то и другое
	// убивает процесс `:vpn` целиком, причём `recover()` первое не ловит вовсе.
	// Строки мостов приходят по сети и из буфера обмена, так что это тот же
	// класс, что и `sqsqueue=` выше.
	//
	// Значение за пределами разумного не отвергается, а заменяется умолчанием:
	// «снежинок» больше горстки не нужно никому, и выбрасывать из-за странного
	// числа мост, который в остальном рабочий, — плата больше пользы. Но молчать
	// об этом нельзя (I4).
	if value, ok := args.Get("max"); ok {
		parsed, err := strconv.Atoi(value)
		switch {
		case err != nil || parsed <= 0:
			log.Printf("snowflake: max=%q неразбираем, оставляем %d", value, config.Max)
		case parsed > snowflakeMaxPeers:
			log.Printf(
				"snowflake: max=%d выходит за потолок %d, оставляем %d",
				parsed, snowflakeMaxPeers, config.Max,
			)
		default:
			config.Max = parsed
		}
	}
	if value, ok := args.Get("utls-imitate"); ok {
		config.UTLSClientID = value
	}
	if value, ok := args.Get("utls-nosni"); ok {
		config.UTLSRemoveSNI = strings.EqualFold(value, "true")
	}
	if value, ok := args.Get("fingerprint"); ok {
		config.BridgeFingerprint = value
	}
	if value, ok := args.Get("proxy"); ok && value != "" {
		if parsed, err := url.Parse(value); err == nil {
			config.CommunicationProxy = parsed
		}
	}
	return snowflakeArgs{key: snowflakeArgsKey(args), config: config}, nil
}

// Dial поднимает клиента (один раз) и берёт у него соединение.
//
// `network`, `address` и `dialFn` не используются: у snowflake нет адреса моста,
// к которому можно было бы подключиться нашим дозвоном — см. шапку файла.
func (f *snowflakeClientFactory) Dial(
	network, address string,
	dialFn base.DialFunc,
	args interface{},
) (net.Conn, error) {
	parsed, ok := args.(snowflakeArgs)
	if !ok {
		return nil, errors.New("snowflake: неразобранные аргументы моста")
	}

	installSnowflakeHooks()

	f.mu.Lock()
	if f.clients == nil {
		f.clients = map[string]*sflib.Transport{}
	}
	client := f.clients[parsed.key]
	if client == nil {
		if len(f.clients) >= snowflakeMaxClients {
			f.mu.Unlock()
			return nil, fmt.Errorf(
				"snowflake: клиентов уже %d, больше не заводим — строк мостов столько не бывает",
				len(f.clients),
			)
		}
		created, err := sflib.NewSnowflakeClient(parsed.config)
		if err != nil {
			f.mu.Unlock()
			return nil, err
		}
		f.clients[parsed.key] = created
		client = created
	}
	f.mu.Unlock()

	return client.Dial()
}

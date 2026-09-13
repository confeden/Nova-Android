package nova

import (
	"errors"
	"net"
	"strings"
	"testing"

	pionnet "github.com/pion/transport/v4"

	pt "gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/goptlib"
	sflib "gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2/client/lib"
)

// Строки мостов здесь — настоящие, из `tools/snowflake/client/torrc` той же
// версии, что собрана в ядро. Выдуманные не годятся: проверяется в том числе
// то, что два встроенных моста различаются, а различаются они ровно отпечатком.
const (
	snowflakeCDN77Fingerprint1 = "2B280B23E1107BB62ABFC40DDCC8824814F80A72"
	snowflakeCDN77Fingerprint2 = "8838024498816A039FCBBAB14E6F40A0843051FA"
	snowflakeBrokerURL         = "https://1098762253.rsc.cdn77.org/"
	snowflakeFronts            = "www.cdn77.com,www.phpmyadmin.net"
)

func snowflakeBridgeArgs(fingerprint string) *pt.Args {
	args := make(pt.Args)
	args.Add("fingerprint", fingerprint)
	args.Add("url", snowflakeBrokerURL)
	args.Add("fronts", snowflakeFronts)
	args.Add("utls-imitate", "hellorandomizedalpn")
	return &args
}

func parseSnowflake(t *testing.T, args *pt.Args) snowflakeArgs {
	t.Helper()
	factory := &snowflakeClientFactory{transport: new(snowflakeTransport)}
	parsed, err := factory.ParseArgs(args)
	if err != nil {
		t.Fatalf("ParseArgs: %v", err)
	}
	typed, ok := parsed.(snowflakeArgs)
	if !ok {
		t.Fatalf("ParseArgs вернул %T, а не snowflakeArgs", parsed)
	}
	return typed
}

// Два встроенных моста snowflake отличаются только отпечатком, и именно он
// решает, к какому релею «снежинка» приведёт трафик. Совпади у них ключ кэша —
// второй мост получил бы клиента, настроенного по первому, tor пришёл бы к
// чужому мосту и забраковал бы его на сверке личности. Снаружи это выглядело бы
// как «второй мост всегда мёртвый», хотя до него ни разу не звонили.
func TestSnowflakeArgsKeySeparatesBridges(t *testing.T) {
	first := parseSnowflake(t, snowflakeBridgeArgs(snowflakeCDN77Fingerprint1))
	second := parseSnowflake(t, snowflakeBridgeArgs(snowflakeCDN77Fingerprint2))

	if first.key == second.key {
		t.Fatalf("мосты с разными отпечатками получили один ключ кэша: %q", first.key)
	}
	if first.config.BridgeFingerprint != snowflakeCDN77Fingerprint1 {
		t.Fatalf("отпечаток не доехал до конфигурации: %q", first.config.BridgeFingerprint)
	}
}

// Обратная сторона того же: одна и та же строка моста обязана давать один и тот
// же ключ. Иначе кэш из экономии превращается в утечку — новый клиент WebRTC на
// каждое соединение tor'а.
func TestSnowflakeArgsKeyIsStable(t *testing.T) {
	first := parseSnowflake(t, snowflakeBridgeArgs(snowflakeCDN77Fingerprint1))
	second := parseSnowflake(t, snowflakeBridgeArgs(snowflakeCDN77Fingerprint1))

	if first.key != second.key {
		t.Fatalf("одна строка моста дала два ключа:\n%q\n%q", first.key, second.key)
	}
}

// Ключ обязан отличать мосты и по месту встречи, а не только по отпечатку:
// запасной набор ходит к тому же мосту через AMP-кэш.
func TestSnowflakeArgsKeySeparatesRendezvous(t *testing.T) {
	plain := parseSnowflake(t, snowflakeBridgeArgs(snowflakeCDN77Fingerprint1))

	amped := snowflakeBridgeArgs(snowflakeCDN77Fingerprint1)
	amped.Add("ampcache", "https://cdn.ampproject.org/")
	viaAmp := parseSnowflake(t, amped)

	if plain.key == viaAmp.key {
		t.Fatalf("прямое рандеву и рандеву через AMP-кэш получили один ключ: %q", plain.key)
	}
	if viaAmp.config.AmpCacheURL == "" {
		t.Fatal("ampcache= не доехал до конфигурации")
	}
}

// Ключ строится из самих аргументов, а не из перечисленных руками полей
// конфигурации. Проверяется аргументом, которого наш разбор не знает вовсе:
// поля у snowflake прибавляются с версиями, и ключ, перечисляющий поля,
// начал бы склеивать разные мосты молча.
func TestSnowflakeArgsKeyCoversUnknownArguments(t *testing.T) {
	plain := parseSnowflake(t, snowflakeBridgeArgs(snowflakeCDN77Fingerprint1))

	exotic := snowflakeBridgeArgs(snowflakeCDN77Fingerprint1)
	exotic.Add("covert-dtls", "randomize")
	withExtra := parseSnowflake(t, exotic)

	if plain.key == withExtra.key {
		t.Fatal("незнакомый аргумент не изменил ключ кэша")
	}
}

// Главный тест этого файла.
//
// Строка моста с `sqsqueue=` уводит snowflake в `log.Fatalln`, то есть в
// `os.Exit(1)`: выбор способа встречи запрещает SQS вместе с адресом брокера
// (`client/lib/rendezvous.go`), а адрес брокера мы требуем всегда. Процесс
// `:vpn` умер бы вместе с живым туннелем, а строки мостов приходят по сети и из
// буфера обмена. Отказ обычной ошибкой tor понимает: мост помечается негодным,
// остальные продолжают работать.
//
// Тест проверяет отказ, а не отсутствие падения: падение убило бы и сам тест,
// и заметить это было бы уже нечем.
func TestSnowflakeRefusesSqsRendezvous(t *testing.T) {
	factory := &snowflakeClientFactory{transport: new(snowflakeTransport)}

	withQueue := snowflakeBridgeArgs(snowflakeCDN77Fingerprint1)
	withQueue.Add("sqsqueue", "https://sqs.us-east-1.amazonaws.com/893902434899/snowflake-broker")
	if _, err := factory.ParseArgs(withQueue); err == nil {
		t.Fatal("строка моста с sqsqueue= принята — она убила бы процесс :vpn")
	} else if !strings.Contains(err.Error(), "SQS") {
		t.Fatalf("отказ не называет причину: %v", err)
	}

	withCreds := snowflakeBridgeArgs(snowflakeCDN77Fingerprint1)
	withCreds.Add("sqscreds", "eyJhd3MtYWNjZXNzLWtleS1pZCI6IngifQ==")
	if _, err := factory.ParseArgs(withCreds); err == nil {
		t.Fatal("строка моста с sqscreds= принята")
	}
}

// Без адреса брокера договариваться не с кем, и сказать об этом надо до
// попытки соединения, а не после её таймаута.
func TestSnowflakeRefusesBridgeWithoutBroker(t *testing.T) {
	factory := &snowflakeClientFactory{transport: new(snowflakeTransport)}

	args := make(pt.Args)
	args.Add("fingerprint", snowflakeCDN77Fingerprint1)
	if _, err := factory.ParseArgs(&args); err == nil {
		t.Fatal("строка моста без url= принята")
	}
}

// Разбор аргументов: список фронтов режется по запятой, `max` читается числом,
// `utls-nosni` — признаком. Имена те же, что у собственного клиента snowflake:
// строки мостов приходят из общих источников и написаны под них.
func TestSnowflakeParsesBridgeArguments(t *testing.T) {
	args := snowflakeBridgeArgs(snowflakeCDN77Fingerprint1)
	args.Add("ice", "stun:stun.antisip.com:3478,stun:stun.epygi.com:3478")
	args.Add("max", "3")
	args.Add("utls-nosni", "true")

	parsed := parseSnowflake(t, args)
	config := parsed.config

	if got := strings.Join(config.FrontDomains, ","); got != snowflakeFronts {
		t.Fatalf("фронты разобраны как %q", got)
	}
	if len(config.ICEAddresses) != 2 {
		t.Fatalf("серверов ICE разобрано %d, а не 2", len(config.ICEAddresses))
	}
	if config.Max != 3 {
		t.Fatalf("max разобран как %d", config.Max)
	}
	if !config.UTLSRemoveSNI {
		t.Fatal("utls-nosni=true не прочитан")
	}
	if config.UTLSClientID != "hellorandomizedalpn" {
		t.Fatalf("utls-imitate разобран как %q", config.UTLSClientID)
	}
}

// Без `max=` в строке остаётся умолчание самого snowflake — один прокси.
// Подставлять своё число нельзя: строка моста общая, и её смысл задаёт не Nova.
func TestSnowflakeDefaultsToOnePeer(t *testing.T) {
	parsed := parseSnowflake(t, snowflakeBridgeArgs(snowflakeCDN77Fingerprint1))
	if parsed.config.Max != 1 {
		t.Fatalf("умолчание max = %d, а не 1", parsed.config.Max)
	}
	var zero sflib.ClientConfig
	if zero.Max != 0 {
		t.Fatal("предположение о нулевом значении Max перестало быть верным")
	}
}

// Отказ собрать защищённую сеть обязан быть громким, а не тихим.
//
// Ловушка `NetWrapper` не может вернуть `nil`: в `pion/ice` стоит
// `if agent.net == nil { agent.net, err = stdnet.NewNet() }` (`agent.go:467`),
// то есть на `nil` pion молча заводит обычную сеть, сокеты ICE и DTLS остаются
// без `protect()` и при поднятом туннеле уходят в него же — snowflake
// пробивается к мосту через туннель, который сам и поднимает (G154). Утечка,
// выглядящая как «не соединяется». Поэтому на отказе отдаётся сеть, которая
// ничего не создаёт.
func TestFailingPionNetRefusesEverySocket(t *testing.T) {
	boom := errors.New("сеть не собралась")
	var network pionnet.Net = &failingPionNet{err: boom}

	if _, err := network.ListenPacket("udp4", "127.0.0.1:0"); !errors.Is(err, boom) {
		t.Fatalf("ListenPacket отдал %v", err)
	}
	if _, err := network.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1)}); !errors.Is(err, boom) {
		t.Fatalf("ListenUDP отдал %v", err)
	}
	if _, err := network.ListenTCP("tcp4", &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1)}); !errors.Is(err, boom) {
		t.Fatalf("ListenTCP отдал %v", err)
	}
	if _, err := network.Dial("tcp4", "127.0.0.1:9"); !errors.Is(err, boom) {
		t.Fatalf("Dial отдал %v", err)
	}
	if _, err := network.DialUDP("udp4", nil, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9}); !errors.Is(err, boom) {
		t.Fatalf("DialUDP отдал %v", err)
	}
	if _, err := network.DialTCP("tcp4", nil, &net.TCPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 9}); !errors.Is(err, boom) {
		t.Fatalf("DialTCP отдал %v", err)
	}
	if _, err := network.CreateDialer(&net.Dialer{}).Dial("tcp4", "127.0.0.1:9"); !errors.Is(err, boom) {
		t.Fatalf("CreateDialer().Dial отдал %v", err)
	}
}

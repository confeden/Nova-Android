package nova

import (
	"context"
	"fmt"
	"net"
	"syscall"

	pionnet "github.com/pion/transport/v4"
	"github.com/pion/transport/v4/stdnet"
)

// Сеть для pion, у которой каждый сокет помечен `protect()`.
//
// ## Зачем
//
// У obfs4 и webtunnel есть адрес моста, и `ptProxy` дозванивается до него нашим
// `protectedDial` — сокетом, который ядро пометило и который поэтому не уходит в
// туннель, поднимаемый прямо сейчас. У snowflake адреса нет: договаривается
// брокер, данные идут по WebRTC, и сокеты под это заводит pion сам.
//
// Защита в ядре **поимённая**, а не глобальная: она живёт в `Control:` у каждого
// `net.Dialer` и `net.ListenConfig` (см. `dialerControlProtectTCP`,
// `smart_bind.go`). Чужая библиотека, создающая сокет своими руками, проходит
// мимо неё. При поднятом туннеле такие сокеты уйдут в него же — то есть
// snowflake будет пробиваться к мосту через туннель, который сам же и
// поднимает. Это G154, только внутри процесса, а не в отдельном PT-бинаре.
//
// Поэтому pion получает не стандартную сеть, а эту: `SettingEngine.SetNet`
// принимает `transport.Net`, и все сокеты ICE и DTLS создаются через неё.
//
// ## Почему встраивание интерфейса, а не реализация двенадцати методов
//
// `transport.Net` — это тринадцать методов, из которых нам интересны шесть
// создающих сокеты. Остальные (`Resolve*`, `Interface*`) — чистая справка, и
// переписывать их значило бы повторять `stdnet` слово в слово, а потом
// расходиться с ним при каждом обновлении pion. Встроенное значение отдаёт их
// как есть; добавится в интерфейс новый метод — он тоже отдастся, и сборка не
// сломается.
type protectedPionNet struct {
	pionnet.Net
}

// newProtectedPionNet возвращает сеть поверх `stdnet` с пометкой сокетов.
//
// Ошибка `stdnet.NewNet()` пробрасывается, а не глотается: без базовой сети
// возвращать нечего, и «работает, но без защиты» здесь худший из исходов.
func newProtectedPionNet() (pionnet.Net, error) {
	base, err := stdnet.NewNet()
	if err != nil {
		return nil, err
	}
	return &protectedPionNet{Net: base}, nil
}

// failingPionNet — сеть, которая ничего не создаёт и говорит почему.
//
// Нужна ровно на один случай: защищённую сеть собрать не удалось. Отдать вместо
// неё `nil` нельзя, и это не предположение — в `pion/ice` стоит
// `if agent.net == nil { agent.net, err = stdnet.NewNet() }` (`agent.go:467`),
// то есть pion молча заводит **обычную** сеть. Сокеты ICE и DTLS тогда остаются
// непомеченными и при поднятом туннеле уходят в него же: snowflake пробивается к
// мосту через туннель, который сам и поднимает (G154). Снаружи это выглядит как
// «snowflake не соединяется», а на деле это утечка — трафик рандеву идёт не
// туда, куда должен.
//
// Поэтому на отказе отдаётся эта сеть: каждый вызов возвращает ошибку,
// snowflake падает громко и сразу, и tor помечает мост негодным вместо того,
// чтобы тихо течь.
//
// **Реализованы все четырнадцать методов, и встроенного `Net` здесь нет
// намеренно.** Первая версия встраивала интерфейс и переопределяла только семь
// создающих сокеты — по образцу `protectedPionNet`, где встраивание правильно.
// Здесь оно было бы дырой: встроенное значение `nil`, а `Interfaces()` pion
// зовёт **раньше** любого сокета (сбор кандидатов ICE начинается с перечисления
// интерфейсов). Вызов метода у `nil`-интерфейса — это паника, то есть смерть
// процесса `:vpn` вместо честного отказа. Защита, убивающая процесс, хуже
// защищаемого.
type failingPionNet struct{ err error }

func (n *failingPionNet) ListenPacket(string, string) (net.PacketConn, error) {
	return nil, n.err
}

func (n *failingPionNet) ListenUDP(string, *net.UDPAddr) (pionnet.UDPConn, error) {
	return nil, n.err
}

func (n *failingPionNet) ListenTCP(string, *net.TCPAddr) (pionnet.TCPListener, error) {
	return nil, n.err
}

func (n *failingPionNet) Dial(string, string) (net.Conn, error) { return nil, n.err }

func (n *failingPionNet) DialUDP(string, *net.UDPAddr, *net.UDPAddr) (pionnet.UDPConn, error) {
	return nil, n.err
}

func (n *failingPionNet) DialTCP(string, *net.TCPAddr, *net.TCPAddr) (pionnet.TCPConn, error) {
	return nil, n.err
}

func (n *failingPionNet) ResolveIPAddr(string, string) (*net.IPAddr, error) { return nil, n.err }

func (n *failingPionNet) ResolveUDPAddr(string, string) (*net.UDPAddr, error) { return nil, n.err }

func (n *failingPionNet) ResolveTCPAddr(string, string) (*net.TCPAddr, error) { return nil, n.err }

func (n *failingPionNet) Interfaces() ([]*pionnet.Interface, error) { return nil, n.err }

func (n *failingPionNet) InterfaceByIndex(int) (*pionnet.Interface, error) { return nil, n.err }

func (n *failingPionNet) InterfaceByName(string) (*pionnet.Interface, error) { return nil, n.err }

func (n *failingPionNet) CreateDialer(*net.Dialer) pionnet.Dialer {
	return &failingPionDialer{err: n.err}
}

func (n *failingPionNet) CreateListenConfig(*net.ListenConfig) pionnet.ListenConfig {
	return &failingPionListenConfig{err: n.err}
}

type failingPionDialer struct{ err error }

func (d *failingPionDialer) Dial(string, string) (net.Conn, error) { return nil, d.err }

type failingPionListenConfig struct{ err error }

func (c *failingPionListenConfig) Listen(context.Context, string, string) (net.Listener, error) {
	return nil, c.err
}

func (c *failingPionListenConfig) ListenPacket(context.Context, string, string) (net.PacketConn, error) {
	return nil, c.err
}

// snowflakeProtectControl помечает файловый дескриптор сокета до привязки и соединения.
//
// Та же функция, что у `dialerControlProtectTCP`, но общая для `net.Dialer` и
// `net.ListenConfig`: UDP-сокеты ICE создаются вторым, а не первым.
func snowflakeProtectControl(_, _ string, c syscall.RawConn) error {
	if GlobalProtector == nil {
		return nil
	}
	var protectErr error
	if err := c.Control(func(fd uintptr) {
		if !GlobalProtector(int(fd)) {
			protectErr = fmt.Errorf("protect(%d) returned false", fd)
		}
	}); err != nil {
		return err
	}
	return protectErr
}

func snowflakeDialer() *net.Dialer {
	return &net.Dialer{Control: snowflakeProtectControl}
}

func snowflakeListenConfig() *net.ListenConfig {
	return &net.ListenConfig{Control: snowflakeProtectControl}
}

func (n *protectedPionNet) ListenPacket(network, address string) (net.PacketConn, error) {
	return snowflakeListenConfig().ListenPacket(context.Background(), network, address)
}

// ListenUDP — главный из шести: именно здесь ICE заводит сокеты под хостовые
// кандидаты, и именно они утекали бы в туннель.
func (n *protectedPionNet) ListenUDP(network string, locAddr *net.UDPAddr) (pionnet.UDPConn, error) {
	address := ""
	if locAddr != nil {
		address = locAddr.String()
	}
	conn, err := snowflakeListenConfig().ListenPacket(context.Background(), network, address)
	if err != nil {
		return nil, err
	}
	udp, ok := conn.(*net.UDPConn)
	if !ok {
		_ = conn.Close()
		return nil, fmt.Errorf("snowflake: ожидали UDP-сокет для %s, получили %T", network, conn)
	}
	return udp, nil
}

func (n *protectedPionNet) ListenTCP(network string, laddr *net.TCPAddr) (pionnet.TCPListener, error) {
	address := ""
	if laddr != nil {
		address = laddr.String()
	}
	listener, err := snowflakeListenConfig().Listen(context.Background(), network, address)
	if err != nil {
		return nil, err
	}
	tcp, ok := listener.(*net.TCPListener)
	if !ok {
		_ = listener.Close()
		return nil, fmt.Errorf("snowflake: ожидали TCP-слушатель для %s, получили %T", network, listener)
	}
	return protectedTCPListener{TCPListener: tcp}, nil
}

// protectedTCPListener приводит `*net.TCPListener` к интерфейсу pion.
//
// Отличие ровно одно: `AcceptTCP` обязан возвращать `pionnet.TCPConn`, а не
// `*net.TCPConn`. Обёртка на восемь строк, а не отказ от метода: snowflake
// слушать TCP не станет, но оставить в сети непомеченную дыру «потому что ею
// сейчас не пользуются» — это ровно тот способ, которым дыры и остаются.
type protectedTCPListener struct {
	*net.TCPListener
}

func (l protectedTCPListener) AcceptTCP() (pionnet.TCPConn, error) {
	conn, err := l.TCPListener.AcceptTCP()
	if err != nil {
		return nil, err
	}
	return conn, nil
}

func (n *protectedPionNet) Dial(network, address string) (net.Conn, error) {
	return snowflakeDialer().Dial(network, address)
}

func (n *protectedPionNet) DialUDP(network string, laddr, raddr *net.UDPAddr) (pionnet.UDPConn, error) {
	dialer := snowflakeDialer()
	if laddr != nil {
		dialer.LocalAddr = laddr
	}
	remote := ""
	if raddr != nil {
		remote = raddr.String()
	}
	conn, err := dialer.Dial(network, remote)
	if err != nil {
		return nil, err
	}
	udp, ok := conn.(*net.UDPConn)
	if !ok {
		_ = conn.Close()
		return nil, fmt.Errorf("snowflake: ожидали UDP-соединение для %s, получили %T", network, conn)
	}
	return udp, nil
}

func (n *protectedPionNet) DialTCP(network string, laddr, raddr *net.TCPAddr) (pionnet.TCPConn, error) {
	dialer := snowflakeDialer()
	if laddr != nil {
		dialer.LocalAddr = laddr
	}
	remote := ""
	if raddr != nil {
		remote = raddr.String()
	}
	conn, err := dialer.Dial(network, remote)
	if err != nil {
		return nil, err
	}
	tcp, ok := conn.(*net.TCPConn)
	if !ok {
		_ = conn.Close()
		return nil, fmt.Errorf("snowflake: ожидали TCP-соединение для %s, получили %T", network, conn)
	}
	return tcp, nil
}

// CreateDialer возвращает дозвон с той же пометкой.
//
// Копия, а не правка переданного: чужой `net.Dialer` может использоваться ещё
// где-то, и дописывать ему `Control` втихую значило бы менять поведение за
// спиной вызывающего.
func (n *protectedPionNet) CreateDialer(dialer *net.Dialer) pionnet.Dialer {
	copied := &net.Dialer{}
	if dialer != nil {
		*copied = *dialer
	}
	copied.Control = snowflakeProtectControl
	return n.Net.CreateDialer(copied)
}

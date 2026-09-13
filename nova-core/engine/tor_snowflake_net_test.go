package nova

import (
	"net"
	"sync/atomic"
	"testing"
)

// Проверяет главное, ради чего вся сеть и заведена: сокеты, которые заведёт
// pion, проходят через `GlobalProtector`.
//
// Тестом, а не наблюдением за телефоном, потому что промах здесь бесшумный.
// Непомеченный сокет прекрасно создаётся и работает — до тех пор, пока не поднят
// собственный туннель; после этого snowflake начинает пробиваться к мосту через
// то, что сам же и поднимает, и выглядит это как «не соединяется», а не как
// «забыли пометить».
func withProtectorCount(t *testing.T, body func()) int32 {
	t.Helper()
	var calls int32
	previous := GlobalProtector
	GlobalProtector = func(fd int) bool {
		atomic.AddInt32(&calls, 1)
		return true
	}
	defer func() { GlobalProtector = previous }()
	body()
	return atomic.LoadInt32(&calls)
}

func TestProtectedPionNetMarksUDPListeners(t *testing.T) {
	network, err := newProtectedPionNet()
	if err != nil {
		t.Fatalf("собрать защищённую сеть не удалось: %v", err)
	}

	calls := withProtectorCount(t, func() {
		conn, err := network.ListenUDP("udp4", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
		if err != nil {
			t.Fatalf("ListenUDP: %v", err)
		}
		defer conn.Close()
	})
	if calls == 0 {
		t.Fatal("ListenUDP завёл сокет мимо protect() — именно он и утекал бы в туннель")
	}
}

func TestProtectedPionNetMarksPacketListeners(t *testing.T) {
	network, err := newProtectedPionNet()
	if err != nil {
		t.Fatalf("собрать защищённую сеть не удалось: %v", err)
	}

	calls := withProtectorCount(t, func() {
		conn, err := network.ListenPacket("udp4", "127.0.0.1:0")
		if err != nil {
			t.Fatalf("ListenPacket: %v", err)
		}
		defer conn.Close()
	})
	if calls == 0 {
		t.Fatal("ListenPacket завёл сокет мимо protect()")
	}
}

func TestProtectedPionNetMarksDial(t *testing.T) {
	listener, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("подсобный слушатель: %v", err)
	}
	defer listener.Close()

	network, err := newProtectedPionNet()
	if err != nil {
		t.Fatalf("собрать защищённую сеть не удалось: %v", err)
	}

	calls := withProtectorCount(t, func() {
		conn, err := network.Dial("udp4", listener.LocalAddr().String())
		if err != nil {
			t.Fatalf("Dial: %v", err)
		}
		defer conn.Close()
	})
	if calls == 0 {
		t.Fatal("Dial завёл сокет мимо protect()")
	}
}

// Без протектора сеть обязана работать как обычная: ядро живёт и вне службы
// (сканер, пробы), и падать там из-за отсутствия пометки нечему.
func TestProtectedPionNetWorksWithoutProtector(t *testing.T) {
	previous := GlobalProtector
	GlobalProtector = nil
	defer func() { GlobalProtector = previous }()

	network, err := newProtectedPionNet()
	if err != nil {
		t.Fatalf("собрать защищённую сеть не удалось: %v", err)
	}
	conn, err := network.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("без протектора сеть перестала работать: %v", err)
	}
	_ = conn.Close()
}

// Ловушки должны реально доехать до snowflake: без них вся эта сеть никем не
// используется, и проверить это снаружи нечем.
func TestSnowflakeHooksAreInstalled(t *testing.T) {
	installSnowflakeHooks()
	if snowflakeNetWrapperInstalled() == nil {
		t.Fatal("NetWrapper у snowflake не выставлен — pion получит обычную сеть")
	}
	if snowflakeBrokerDialInstalled() == nil {
		t.Fatal("BrokerDialContext у snowflake не выставлен — рандеву пойдёт мимо protect()")
	}
}

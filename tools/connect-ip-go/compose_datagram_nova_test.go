package connectip

import (
	"encoding/binary"
	"net"
	"testing"

	"github.com/stretchr/testify/require"
	"golang.org/x/net/ipv4"
)

// ipv4HeaderChecksumValid проверяет заголовок так, как его проверяет получатель:
// сумма всех шестнадцатибитных слов, вместе с полем контрольной суммы, должна дать
// 0xffff.
func ipv4HeaderChecksumValid(header []byte) bool {
	var sum uint32
	for i := 0; i+1 < ipv4.HeaderLen; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(header[i : i+2]))
	}
	for (sum >> 16) > 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return uint16(sum) == 0xffff
}

// TestComposeDatagramPayloadKeepsIPv4ChecksumValid — регрессия на контрольную сумму.
//
// У Nova два транспорта MASQUE, и запасной (HTTP/2 поверх TCP на порту 443) собирал
// датаграмму своей копией этого кода. Копия складывала все двадцать байт заголовка
// вместе со старым полем контрольной суммы, вместо того чтобы его пропустить, — и
// после уменьшения TTL писала заведомо неверную сумму. Туннель при этом поднимался,
// прокси молча отбрасывал каждый пакет, и снаружи это выглядело как «rx=0 при tx>0».
// Копия удалена, обрамление осталось одно; тест держит его честным.
func TestComposeDatagramPayloadKeepsIPv4ChecksumValid(t *testing.T) {
	header, err := (&ipv4.Header{
		Version:  4,
		Len:      ipv4.HeaderLen,
		TotalLen: ipv4.HeaderLen,
		TTL:      64,
		Protocol: 6,
		Src:      net.IPv4(172, 16, 0, 2),
		Dst:      net.IPv4(1, 1, 1, 1),
	}).Marshal()
	require.NoError(t, err)
	binary.BigEndian.PutUint16(header[10:12], calculateIPv4Checksum([ipv4.HeaderLen]byte(header[:ipv4.HeaderLen])))
	require.True(t, ipv4HeaderChecksumValid(header), "исходный заголовок должен быть валиден")

	data, err := ComposeDatagramPayload(header)
	require.NoError(t, err)

	require.Equal(t, byte(0), data[0], "идентификатор контекста должен быть 0")
	packet := data[1:]
	require.Equal(t, byte(63), packet[8], "TTL должен уменьшиться на единицу")
	require.True(t, ipv4HeaderChecksumValid(packet), "после уменьшения TTL сумма должна остаться валидной")
}

// TestComposeDatagramPayloadIPv6 — на IPv6 контрольной суммы в заголовке нет,
// проверяем обрамление и Hop Limit.
func TestComposeDatagramPayloadIPv6(t *testing.T) {
	packet := make([]byte, 40)
	packet[0] = 6 << 4
	packet[7] = 64

	data, err := ComposeDatagramPayload(packet)
	require.NoError(t, err)
	require.Equal(t, byte(0), data[0], "идентификатор контекста должен быть 0")
	require.Equal(t, byte(63), data[1:][7], "Hop Limit должен уменьшиться на единицу")
	require.Len(t, data, 1+40)
}

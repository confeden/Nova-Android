package dnsname

import (
	"encoding/binary"
	"net/netip"
	"strings"
	"testing"
)

func encodeName(name string) []byte {
	out := make([]byte, 0, len(name)+2)
	for _, label := range strings.Split(strings.Trim(name, "."), ".") {
		if label == "" {
			continue
		}
		out = append(out, byte(len(label)))
		out = append(out, []byte(label)...)
	}
	return append(out, 0)
}

type testRR struct {
	name  []byte
	typ   uint16
	class uint16
	rdata []byte
}

func header(qd, an uint16) []byte {
	head := make([]byte, 12)
	binary.BigEndian.PutUint16(head[0:2], 0x1234)
	binary.BigEndian.PutUint16(head[2:4], 0x8180) // QR=1, RD, RA
	binary.BigEndian.PutUint16(head[4:6], qd)
	binary.BigEndian.PutUint16(head[6:8], an)
	return head
}

func appendRR(msg []byte, rr testRR) []byte {
	msg = append(msg, rr.name...)
	fixed := make([]byte, 10)
	binary.BigEndian.PutUint16(fixed[0:2], rr.typ)
	binary.BigEndian.PutUint16(fixed[2:4], rr.class)
	binary.BigEndian.PutUint32(fixed[4:8], 300)
	binary.BigEndian.PutUint16(fixed[8:10], uint16(len(rr.rdata)))
	msg = append(msg, fixed...)
	return append(msg, rr.rdata...)
}

func buildResponse(qname string, records []testRR) []byte {
	msg := header(1, uint16(len(records)))
	msg = append(msg, encodeName(qname)...)
	msg = append(msg, 0, 1, 0, 1) // QTYPE=A QCLASS=IN
	for _, rr := range records {
		msg = appendRR(msg, rr)
	}
	return msg
}

func ipv4RData(t *testing.T, value string) []byte {
	t.Helper()
	addr := netip.MustParseAddr(value).As4()
	return addr[:]
}

func ipv6RData(t *testing.T, value string) []byte {
	t.Helper()
	addr := netip.MustParseAddr(value).As16()
	return addr[:]
}

func addrStrings(addrs []netip.Addr) []string {
	out := make([]string, 0, len(addrs))
	for _, addr := range addrs {
		out = append(out, addr.String())
	}
	return out
}

func TestAnswerAddrsHappyPaths(t *testing.T) {
	compressed := []byte{0xC0, 0x0C} // указатель на имя вопроса в заголовке+12

	cases := []struct {
		name      string
		msg       []byte
		wantName  string
		wantAddrs []string
	}{
		{
			name: "single A record",
			msg: buildResponse("market.ozon.ru", []testRR{
				{name: encodeName("market.ozon.ru"), typ: 1, class: 1, rdata: ipv4RData(t, "5.61.23.10")},
			}),
			wantName:  "market.ozon.ru",
			wantAddrs: []string{"5.61.23.10"},
		},
		{
			name: "compressed answer owner name",
			msg: buildResponse("ozon.ru", []testRR{
				{name: compressed, typ: 1, class: 1, rdata: ipv4RData(t, "1.2.3.4")},
			}),
			wantName:  "ozon.ru",
			wantAddrs: []string{"1.2.3.4"},
		},
		{
			name: "several A records",
			msg: buildResponse("vk.com", []testRR{
				{name: compressed, typ: 1, class: 1, rdata: ipv4RData(t, "87.240.132.72")},
				{name: compressed, typ: 1, class: 1, rdata: ipv4RData(t, "87.240.129.133")},
			}),
			wantName:  "vk.com",
			wantAddrs: []string{"87.240.132.72", "87.240.129.133"},
		},
		{
			name: "AAAA record",
			msg: buildResponse("ya.ru", []testRR{
				{name: compressed, typ: 28, class: 1, rdata: ipv6RData(t, "2a02:6b8::2:242")},
			}),
			wantName:  "ya.ru",
			wantAddrs: []string{"2a02:6b8::2:242"},
		},
		{
			name: "cname chain keeps the question name",
			msg: buildResponse("market.ozon.ru", []testRR{
				{name: compressed, typ: 5, class: 1, rdata: encodeName("ozon.cdn.example")},
				{name: encodeName("ozon.cdn.example"), typ: 1, class: 1, rdata: ipv4RData(t, "9.9.9.9")},
			}),
			wantName:  "market.ozon.ru",
			wantAddrs: []string{"9.9.9.9"},
		},
		{
			name:      "zero answers is not an error",
			msg:       buildResponse("nowhere.ru", nil),
			wantName:  "nowhere.ru",
			wantAddrs: nil,
		},
		{
			name: "non IN class is skipped",
			msg: buildResponse("chaos.ru", []testRR{
				{name: compressed, typ: 1, class: 3, rdata: ipv4RData(t, "1.1.1.1")},
			}),
			wantName:  "chaos.ru",
			wantAddrs: nil,
		},
		{
			name: "unknown record types are skipped",
			msg: buildResponse("mx.ru", []testRR{
				{name: compressed, typ: 15, class: 1, rdata: []byte{0, 10, 0}},
				{name: compressed, typ: 1, class: 1, rdata: ipv4RData(t, "8.8.8.8")},
			}),
			wantName:  "mx.ru",
			wantAddrs: []string{"8.8.8.8"},
		},
		{
			name: "uppercase question name is lowercased",
			msg: buildResponse("Market.OZON.RU", []testRR{
				{name: compressed, typ: 1, class: 1, rdata: ipv4RData(t, "5.5.5.5")},
			}),
			wantName:  "market.ozon.ru",
			wantAddrs: []string{"5.5.5.5"},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			qname, addrs, ok := AnswerAddrs(tc.msg)
			if !ok {
				t.Fatalf("AnswerAddrs reported a malformed message")
			}
			if qname != tc.wantName {
				t.Fatalf("qname = %q, want %q", qname, tc.wantName)
			}
			got := addrStrings(addrs)
			if len(got) != len(tc.wantAddrs) {
				t.Fatalf("addrs = %v, want %v", got, tc.wantAddrs)
			}
			for i := range got {
				if got[i] != tc.wantAddrs[i] {
					t.Fatalf("addrs = %v, want %v", got, tc.wantAddrs)
				}
			}
		})
	}
}

func TestAnswerAddrsCompressedQuestionName(t *testing.T) {
	// Вопрос с указателем — вырожденный случай, но парсер обязан его пережить:
	// пакет приходит из туннеля и собран может быть как угодно.
	msg := header(1, 1)
	prefix := len(msg) + 2 + 4 + 2 + 10 + 4 // вопрос + ответ, до литерального имени
	msg = append(msg, 0xC0, byte(prefix))
	msg = append(msg, 0, 1, 0, 1)
	msg = appendRR(msg, testRR{name: []byte{0xC0, 0x0C}, typ: 1, class: 1, rdata: ipv4RData(t, "77.88.55.88")})
	if len(msg) != prefix {
		t.Fatalf("test message layout drifted: len=%d prefix=%d", len(msg), prefix)
	}
	msg = append(msg, encodeName("market.ozon.ru")...)

	qname, addrs, ok := AnswerAddrs(msg)
	if !ok {
		t.Fatalf("AnswerAddrs rejected a compressed question name")
	}
	if qname != "market.ozon.ru" {
		t.Fatalf("qname = %q", qname)
	}
	if len(addrs) != 1 || addrs[0].String() != "77.88.55.88" {
		t.Fatalf("addrs = %v", addrStrings(addrs))
	}
}

func TestAnswerAddrsRejectsMalformed(t *testing.T) {
	full := buildResponse("market.ozon.ru", []testRR{
		{name: []byte{0xC0, 0x0C}, typ: 1, class: 1, rdata: ipv4RData(t, "5.61.23.10")},
	})

	loop := header(1, 0)
	loop = append(loop, 0xC0, 0x0C) // указатель сам на себя
	loop = append(loop, 0, 1, 0, 1)

	query := buildResponse("market.ozon.ru", nil)
	query[2] &^= 0x80 // QR=0, то есть запрос

	noQuestion := header(0, 0)

	badLength := buildResponse("market.ozon.ru", []testRR{
		{name: []byte{0xC0, 0x0C}, typ: 1, class: 1, rdata: []byte{1, 2, 3, 4, 5}},
	})

	lyingCount := buildResponse("market.ozon.ru", nil)
	binary.BigEndian.PutUint16(lyingCount[6:8], 3) // ANCOUNT врёт, записей нет

	reservedLabel := header(1, 0)
	reservedLabel = append(reservedLabel, 0x40, 'x', 0)
	reservedLabel = append(reservedLabel, 0, 1, 0, 1)

	cases := []struct {
		name string
		msg  []byte
	}{
		{"empty input", nil},
		{"header only", header(1, 1)},
		{"truncated header", full[:8]},
		{"truncated inside rdata", full[:len(full)-2]},
		{"truncated question", header(1, 0)},
		{"pointer loop", loop},
		{"query instead of a response", query},
		{"no question section", noQuestion},
		{"A record with a wrong rdlength", badLength},
		{"answer count larger than the payload", lyingCount},
		{"reserved label type", reservedLabel},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, _, ok := AnswerAddrs(tc.msg); ok {
				t.Fatalf("AnswerAddrs accepted a malformed message")
			}
		})
	}
}

// Ответ не должен учить нас адресам, которые к вопросу не относятся.
//
// Это регрессия на настоящую атаку: злоумышленнику достаточно заставить
// устройство разрешить любое имя из обойдённой зоны и приложить к ответу чужую
// A-запись, чтобы её адрес начал уходить мимо туннеля.
func TestAnswerAddrsRejectsForeignOwner(t *testing.T) {
	msg := buildResponse("m.ozon.ru", []testRR{
		{name: encodeName("m.ozon.ru"), typ: dnsTypeA, class: dnsClassIN, rdata: []byte{1, 2, 3, 4}},
		{name: encodeName("bank.example"), typ: dnsTypeA, class: dnsClassIN, rdata: []byte{5, 6, 7, 8}},
	})
	qname, addrs, ok := AnswerAddrs(msg)
	if !ok {
		t.Fatalf("AnswerAddrs(ok) = false")
	}
	if qname != "m.ozon.ru" {
		t.Fatalf("qname = %q", qname)
	}
	if len(addrs) != 1 || addrs[0].String() != "1.2.3.4" {
		t.Fatalf("addrs = %v, ожидался только 1.2.3.4", addrs)
	}
}

// Адрес за цепочкой CNAME принимается: ответ действительно про наше имя.
func TestAnswerAddrsFollowsCNAMEChain(t *testing.T) {
	msg := buildResponse("shop.ozon.ru", []testRR{
		{name: encodeName("shop.ozon.ru"), typ: dnsTypeCNAME, class: dnsClassIN, rdata: encodeName("edge.cdn.example")},
		{name: encodeName("edge.cdn.example"), typ: dnsTypeA, class: dnsClassIN, rdata: []byte{9, 9, 9, 9}},
		{name: encodeName("evil.example"), typ: dnsTypeA, class: dnsClassIN, rdata: []byte{6, 6, 6, 6}},
	})
	_, addrs, ok := AnswerAddrs(msg)
	if !ok {
		t.Fatalf("AnswerAddrs(ok) = false")
	}
	if len(addrs) != 1 || addrs[0].String() != "9.9.9.9" {
		t.Fatalf("addrs = %v, ожидался только 9.9.9.9", addrs)
	}
}

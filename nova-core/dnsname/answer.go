package dnsname

import (
	"errors"
	"net/netip"
	"strings"
)

const (
	dnsTypeA     = 1
	dnsTypeAAAA  = 28
	dnsTypeCNAME = 5
	dnsClassIN   = 1

	// Потолок длины цепочки CNAME. Настоящие цепочки — два-три звена; всё, что
	// длиннее, это либо ошибка зоны, либо попытка загнать нас в цикл.
	maxCNAMEChain = 16

	// Имя в DNS не длиннее 255 байт вместе с длинами меток — предел из RFC 1035.
	maxNameLen = 255
	// Потолок прыжков по указателям сжатия. Пакет с циклом указателей —
	// классическая попытка загнать парсер в вечный цикл, и такой пакет нам
	// приходит из туннеля, то есть от кого угодно.
	maxNamePointers = 32
)

var errMalformedDNS = errors.New("malformed dns message")

// AnswerAddrs разбирает DNS-ответ и отдаёт имя из вопроса вместе с адресами из
// секции ответов.
//
// Возвращает ok=false на любом мусоре: обрезанном пакете, битой длине записи,
// цикле указателей сжатия. Пакет приходит из туннеля, доверять ему нельзя, и
// упасть в паникой в горутине чтения TUN означало бы уронить процесс `:vpn`.
//
// Адрес берётся, только если его **владелец** стоит в цепочке CNAME, начатой
// QNAME. Это не педантизм, а защита от прямой атаки: в секции ответов может
// лежать что угодно, и парсер, который просто собирал все A-записи подряд,
// позволял чужому имени подсунуть произвольный адрес. Достаточно было заставить
// устройство разрешить любое имя из обойдённой зоны — например `<img
// src="http://px.attacker.ru">` при включённой `.ru` — и приложить к ответу
// `bank.example A 5.6.7.8`, чтобы этот адрес начал уходить мимо туннеля.
//
// Остаточный риск цепочкой не закрывается и закрыт быть не может: хозяин имени
// в обойдённой зоне волен указать его на любой адрес. Это свойство самой затеи
// «обходить зону целиком», и говорить о нём надо на экране, а не в парсере.
//
// Пустой ответ (0 записей) — не ошибка: ok=true, addrs пустой. Отличить «имя
// не резолвится» от «пакет сломан» вызывающему нужно, чтобы не сыпать в лог.
func AnswerAddrs(payload []byte) (qname string, addrs []netip.Addr, ok bool) {
	name, list, err := parseAnswer(payload)
	if err != nil {
		return "", nil, false
	}
	return name, list, true
}

func parseAnswer(msg []byte) (string, []netip.Addr, error) {
	if len(msg) < 12 {
		return "", nil, errMalformedDNS
	}
	// QR=0 — это запрос, а не ответ. Учиться на запросе нечему.
	if msg[2]&0x80 == 0 {
		return "", nil, errMalformedDNS
	}
	qdCount := int(msg[4])<<8 | int(msg[5])
	anCount := int(msg[6])<<8 | int(msg[7])
	if qdCount < 1 {
		return "", nil, errMalformedDNS
	}

	offset := 12
	qname, next, err := readName(msg, offset)
	if err != nil {
		return "", nil, err
	}
	offset = next + 4 // QTYPE + QCLASS
	if offset > len(msg) {
		return "", nil, errMalformedDNS
	}
	// Остальные вопросы (их почти никогда не бывает) просто пропускаем.
	for i := 1; i < qdCount; i++ {
		_, next, err := readName(msg, offset)
		if err != nil {
			return "", nil, err
		}
		offset = next + 4
		if offset > len(msg) {
			return "", nil, errMalformedDNS
		}
	}

	type ownedAddr struct {
		owner string
		addr  netip.Addr
	}
	owned := make([]ownedAddr, 0, anCount)
	cnames := make(map[string]string, anCount)
	// Цикл ограничен счётчиком из заголовка, а не концом буфера: иначе хвост
	// пакета (секции authority/additional) читался бы как ответы.
	for i := 0; i < anCount; i++ {
		owner, next, err := readName(msg, offset)
		if err != nil {
			return "", nil, err
		}
		owner = normalizeName(owner)
		offset = next
		if offset+10 > len(msg) {
			return "", nil, errMalformedDNS
		}
		rrType := int(msg[offset])<<8 | int(msg[offset+1])
		rrClass := int(msg[offset+2])<<8 | int(msg[offset+3])
		rdLength := int(msg[offset+8])<<8 | int(msg[offset+9])
		offset += 10
		if rdLength < 0 || offset+rdLength > len(msg) {
			return "", nil, errMalformedDNS
		}
		rdata := msg[offset : offset+rdLength]
		offset += rdLength
		if rrClass != dnsClassIN {
			continue
		}
		switch rrType {
		case dnsTypeA:
			if rdLength != 4 {
				return "", nil, errMalformedDNS
			}
			owned = append(owned, ownedAddr{
				owner: owner,
				addr:  netip.AddrFrom4([4]byte{rdata[0], rdata[1], rdata[2], rdata[3]}).Unmap(),
			})
		case dnsTypeAAAA:
			if rdLength != 16 {
				return "", nil, errMalformedDNS
			}
			var raw [16]byte
			copy(raw[:], rdata)
			owned = append(owned, ownedAddr{owner: owner, addr: netip.AddrFrom16(raw).Unmap()})
		case dnsTypeCNAME:
			target, _, err := readName(msg, offset-rdLength)
			if err != nil {
				return "", nil, err
			}
			if _, exists := cnames[owner]; !exists {
				cnames[owner] = normalizeName(target)
			}
		}
	}

	chain := resolveChain(normalizeName(qname), cnames)
	addrs := make([]netip.Addr, 0, len(owned))
	for _, rec := range owned {
		if _, inChain := chain[rec.owner]; inChain {
			addrs = append(addrs, rec.addr)
		}
	}
	return qname, addrs, nil
}

// resolveChain собирает множество имён, к которым по-настоящему относится ответ:
// само QNAME и всё, куда ведут CNAME из этого же ответа.
func resolveChain(qname string, cnames map[string]string) map[string]struct{} {
	chain := map[string]struct{}{qname: {}}
	current := qname
	for i := 0; i < maxCNAMEChain; i++ {
		next, ok := cnames[current]
		if !ok || next == "" {
			break
		}
		if _, seen := chain[next]; seen {
			break
		}
		chain[next] = struct{}{}
		current = next
	}
	return chain
}

func normalizeName(name string) string {
	return strings.ToLower(strings.TrimSuffix(strings.TrimSpace(name), "."))
}

// readName читает имя с учётом сжатия и возвращает смещение сразу за именем в
// том месте, где оно встретилось (а не за указателем в цели прыжка).
func readName(msg []byte, offset int) (string, int, error) {
	if offset < 0 || offset >= len(msg) {
		return "", 0, errMalformedDNS
	}
	var builder strings.Builder
	total := 0
	pointers := 0
	next := -1
	for {
		if offset >= len(msg) {
			return "", 0, errMalformedDNS
		}
		length := int(msg[offset])
		switch {
		case length == 0:
			offset++
			if next < 0 {
				next = offset
			}
			return strings.ToLower(builder.String()), next, nil
		case length&0xC0 == 0xC0:
			if offset+1 >= len(msg) {
				return "", 0, errMalformedDNS
			}
			target := (length&0x3F)<<8 | int(msg[offset+1])
			offset += 2
			if next < 0 {
				next = offset
			}
			pointers++
			if pointers > maxNamePointers || target >= len(msg) {
				return "", 0, errMalformedDNS
			}
			offset = target
		case length&0xC0 != 0:
			// 0x40/0x80 — зарезервированные типы меток, в обычном DNS их нет.
			return "", 0, errMalformedDNS
		default:
			offset++
			if offset+length > len(msg) {
				return "", 0, errMalformedDNS
			}
			total += length + 1
			if total > maxNameLen {
				return "", 0, errMalformedDNS
			}
			if builder.Len() > 0 {
				builder.WriteByte('.')
			}
			builder.Write(msg[offset : offset+length])
			offset += length
		}
	}
}

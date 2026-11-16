package nova

import (
	"bytes"
	"encoding/binary"
	"net"
	"sort"
	"strconv"
	"strings"
	"time"
)

// Стратегии обфускации прямого DoH/DoT к DNS-AI (D46). Лабораторный прогон — `cmd/dnsobfslab`,
// таблица замеров — kb/dns-routing.md#d46. Всё делается обычным пользовательским сокетом, без
// root: TCP-разбиение записей, разбиение ClientHello на несколько TLS-записей, регистр SNI,
// форма ключевых долей. Подделка пакетов, TTL и порядок сегментов недоступны.

// obfsOffset — позиция в записи ClientHello: абсолютная или от имени SNI.
type obfsOffset struct {
	kind byte // 'a' абсолютная, 's' начало имени, 'm' середина, 'e' конец
	n    int
}

type obfsStrategy struct {
	name string
	// tcp — смещения (в исходной записи ClientHello), по которым поток режется на TCP-записи.
	tcp []obfsOffset
	// rec — смещения, по которым ClientHello режется на несколько TLS-записей.
	rec []obfsOffset
	// delay — пауза между TCP-записями.
	delay time.Duration
}

func (s *obfsStrategy) isPlain() bool {
	return s == nil || (len(s.tcp) == 0 && len(s.rec) == 0)
}

// obfsSNIRange находит имя SNI в записи ClientHello (с 5-байтным заголовком записи).
func obfsSNIRange(p []byte) (int, int, bool) {
	if len(p) < 48 || p[0] != 0x16 || p[5] != 0x01 {
		return 0, 0, false
	}
	pos := 5 + 4 + 2 + 32
	if pos >= len(p) {
		return 0, 0, false
	}
	pos += 1 + int(p[pos])
	if pos+2 > len(p) {
		return 0, 0, false
	}
	pos += 2 + int(binary.BigEndian.Uint16(p[pos:]))
	if pos >= len(p) {
		return 0, 0, false
	}
	pos += 1 + int(p[pos])
	if pos+2 > len(p) {
		return 0, 0, false
	}
	extEnd := pos + 2 + int(binary.BigEndian.Uint16(p[pos:]))
	pos += 2
	for pos+4 <= extEnd && pos+4 <= len(p) {
		typ := binary.BigEndian.Uint16(p[pos:])
		l := int(binary.BigEndian.Uint16(p[pos+2:]))
		if typ == 0 && pos+9 <= len(p) {
			nameLen := int(binary.BigEndian.Uint16(p[pos+7:]))
			start := pos + 9
			if start+nameLen <= len(p) {
				return start, start + nameLen, true
			}
		}
		pos += 4 + l
	}
	return 0, 0, false
}

func resolveObfsOffsets(offs []obfsOffset, p []byte) []int {
	s, e, ok := obfsSNIRange(p)
	var out []int
	for _, o := range offs {
		v := o.n
		switch o.kind {
		case 's', 'm', 'e':
			if !ok {
				continue
			}
			switch o.kind {
			case 's':
				v = s + o.n
			case 'm':
				v = (s+e)/2 + o.n
			default:
				v = e + o.n
			}
		}
		if v > 0 && v < len(p) {
			out = append(out, v)
		}
	}
	sort.Ints(out)
	uniq := out[:0]
	for i, v := range out {
		if i == 0 || v != out[i-1] {
			uniq = append(uniq, v)
		}
	}
	return uniq
}

// segments превращает запись ClientHello в список TCP-записей по стратегии.
func (s *obfsStrategy) segments(p []byte) [][]byte {
	wire := p
	recSplits := resolveObfsOffsets(s.rec, p)
	if len(recSplits) > 0 && p[0] == 0x16 && len(p) > 5 {
		body := p[5:]
		var out bytes.Buffer
		prev := 0
		cuts := append(append([]int(nil), recSplits...), len(p))
		for _, c := range cuts {
			bo := c - 5
			if bo <= prev {
				continue
			}
			part := body[prev:bo]
			out.Write([]byte{0x16, p[1], p[2], byte(len(part) >> 8), byte(len(part))})
			out.Write(part)
			prev = bo
		}
		wire = out.Bytes()
	}
	var cuts []int
	for _, v := range resolveObfsOffsets(s.tcp, p) {
		shift := 0
		for _, r := range recSplits {
			if r < v {
				shift += 5
			}
		}
		cuts = append(cuts, v+shift)
	}
	var segs [][]byte
	prev := 0
	for _, c := range cuts {
		if c <= prev || c >= len(wire) {
			continue
		}
		segs = append(segs, wire[prev:c])
		prev = c
	}
	return append(segs, wire[prev:])
}

// strategyConn пишет первую запись (ClientHello) по стратегии, остальное пропускает как есть.
type strategyConn struct {
	net.Conn
	st   *obfsStrategy
	done bool
}

func (c *strategyConn) Write(p []byte) (int, error) {
	if c.done || len(p) < 6 || p[0] != 0x16 {
		return c.Conn.Write(p)
	}
	c.done = true
	if tcp, ok := c.Conn.(*net.TCPConn); ok {
		_ = tcp.SetNoDelay(true)
	}
	segs := c.st.segments(p)
	for i, seg := range segs {
		if _, err := c.Conn.Write(seg); err != nil {
			return 0, err
		}
		if i < len(segs)-1 && c.st.delay > 0 {
			time.Sleep(c.st.delay)
		}
	}
	return len(p), nil
}

// parseObfsSpec разбирает строку стратегии: токены через `;` — tcp=, rec=, d=<мс>; смещения через `/`:
// число, `sni` (начало имени), `mid` (середина), `end` (конец) с поправкой (`sni-1`, `end+2`).
func parseObfsSpec(name, spec string) (*obfsStrategy, bool) {
	st := &obfsStrategy{name: name}
	for _, tok := range strings.Split(spec, ";") {
		key, val, _ := strings.Cut(strings.TrimSpace(tok), "=")
		switch key {
		case "tcp", "rec":
			var offs []obfsOffset
			for _, item := range strings.Split(val, "/") {
				item = strings.TrimSpace(item)
				if item == "" {
					continue
				}
				o := obfsOffset{kind: 'a'}
				for _, prefix := range []string{"sni", "mid", "end"} {
					if strings.HasPrefix(item, prefix) {
						o.kind = prefix[0]
						item = strings.TrimPrefix(item, prefix)
						break
					}
				}
				if item != "" {
					v, err := strconv.Atoi(item)
					if err != nil {
						return nil, false
					}
					o.n = v
				}
				offs = append(offs, o)
			}
			if key == "tcp" {
				st.tcp = offs
			} else {
				st.rec = offs
			}
		case "d":
			ms, err := strconv.ParseFloat(val, 64)
			if err != nil {
				return nil, false
			}
			st.delay = time.Duration(ms * float64(time.Millisecond))
		case "", "none":
		default:
			return nil, false
		}
	}
	return st, true
}

// Именованные стратегии. Выбор и таблица замеров — kb/dns-routing.md#d46.
//
//	tcpmid — ClientHello в двух TCP-сегментах ровно посреди имени SNI: ни один сегмент имени целиком не
//	         содержит. Основная: в замере 160/160 на Cloudflare и 100 % на dns., как и без разбиения.
//	tcp1   — один байт отдельным сегментом, остальное следом.
//	recmid — то же плюс две TLS-записи вместо одной (сильнее против DPI, который собирает сегменты, но на
//	         Cloudflare 76/80 против 160/160 у остальных, поэтому третья).
//	plain  — без изменений: на сетях без DPI разбиение только лишняя работа, а часть коробок на
//	         фрагментацию отвечает хуже, чем на целый ClientHello.
//	legacy — прежний план ядра 1/255/256 с фрагментом 512 байт (для токена obf=legacy).
const (
	obfsTCPMid = "tcpmid"
	obfsTCP1   = "tcp1"
	obfsRecMid = "recmid"
	obfsPlain  = "plain"
	obfsLegacy = "legacy"
)

var obfsRegistry = func() map[string]*obfsStrategy {
	out := map[string]*obfsStrategy{}
	for name, spec := range map[string]string{
		obfsTCPMid: "tcp=mid;d=1",
		obfsTCP1:   "tcp=1;d=1",
		obfsRecMid: "rec=mid;tcp=mid;d=1",
		obfsPlain:  "none",
		obfsLegacy: "tcp=1/256/512;d=4",
	} {
		st, ok := parseObfsSpec(name, spec)
		if !ok {
			panic("bad obfs spec " + name)
		}
		out[name] = st
	}
	return out
}()

func obfsStrategyByName(name string) *obfsStrategy {
	return obfsRegistry[strings.ToLower(strings.TrimSpace(name))]
}

// obfsLadder — порядок стратегий для хоста. Замер (2026-11-14, Mi A1 Wi-Fi Ростелеком и Pixel 4a LTE
// МегаФон, DoH/DoT, v4/v6) разницы между стратегиями на `dns.` и на `cf.` не показал, поэтому лестница
// у хостов общая: основная первой, плоская последней, победитель запоминается. Различается только набор
// адресов (obfsExtraAddresses).
func obfsLadder(host string) []*obfsStrategy {
	_ = host
	return []*obfsStrategy{obfsRegistry[obfsTCPMid], obfsRegistry[obfsTCP1], obfsRegistry[obfsRecMid], obfsRegistry[obfsPlain]}
}

// obfsPrefersHTTP1 — хосты, которым прямой DoH шлётся по HTTP/1.1. `cf.dns-ai.ru` стоит за Cloudflare, а
// тот отвечает по HTTP/1.1 так же, как по h2 (в отличие от dns., где сервер на HTTP/1.1 отвечает 505).
// Замер 2026-11-15 (Pixel 4a, МегаФон LTE, kb/dns-routing.md#d47): на адресах Cloudflare, кроме 172.64.80.1,
// рукопожатие TLS и обмен SETTINGS проходят, а запрос HTTP/2 пропадает (0/4 на трёх из трёх адресов);
// тот же запрос по HTTP/1.1 — 4/4 на каждом. На Wi-Fi Ростелекома работают оба.
func obfsPrefersHTTP1(host string) bool {
	return strings.EqualFold(strings.TrimSuffix(host, "."), "cf.dns-ai.ru")
}

// obfsExtraAddresses — адреса, которые пробуются после тех, что дало приложение. У `cf.dns-ai.ru` на
// МегаФон LTE запрос HTTP/2 к адресам Cloudflare, которые отдаёт имя, пропадает, и проходит только
// 172.64.80.1 (30/30): он запасной для h2. По HTTP/1.1 (obfsPrefersHTTP1) проходят и обычные адреса. Остальным хостам лишних адресов не добавляется.
// obfsExtraOff выключает запасной адрес; нужен только живому тесту, чтобы показать, что cf. проходит без него.
var obfsExtraOff bool

func obfsExtraAddresses(host string) []string {
	if obfsExtraOff {
		return nil
	}
	if strings.EqualFold(strings.TrimSuffix(host, "."), "cf.dns-ai.ru") {
		return []string{"172.64.80.1"}
	}
	return nil
}

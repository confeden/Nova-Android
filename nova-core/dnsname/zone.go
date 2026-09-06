// Package dnsname разбирает правила обхода по доменам и зонам и вытаскивает
// адреса из DNS-ответов.
//
// Пакет намеренно чистый: никаких зависимостей от Android, от движка и от
// сетевых сокетов. Только так его можно гонять обычным `go test ./dnsname` на
// Windows-хосте — пакет `engine` на нём не собирается вовсе (syscall.Dup).
//
// Семантика здесь обязана совпадать с котлиновым `DomainBypassRules` до
// последнего края: экран настроек считает правила своим кодом, ядро — этим, и
// расхождение выглядело бы как «в списке домен есть, а обход не работает».
package dnsname

import (
	"sort"
	"strings"

	"golang.org/x/net/idna"
)

// Rules — набор правил обхода: зоны верхнего уровня, точные домены и флаг
// «любая кириллическая зона».
type Rules struct {
	Zones    map[string]struct{}
	Domains  []string
	Cyrillic bool
}

// ParseRules собирает Rules из сырых строк экрана настроек.
//
// Разделители — запятая, пробел, перевод строки и табуляция: пользователь
// вводит список руками, и требовать от него ровно один разделитель незачем.
func ParseRules(zonesCSV string, domainsCSV string, cyrillic bool) Rules {
	return Rules{
		Zones:    parseZones(zonesCSV),
		Domains:  parseDomains(domainsCSV),
		Cyrillic: cyrillic,
	}
}

// Empty сообщает, что правил нет вовсе и проверять хосты бессмысленно.
func (r Rules) Empty() bool {
	return len(r.Zones) == 0 && len(r.Domains) == 0 && !r.Cyrillic
}

// Signature — устойчивый отпечаток набора правил.
//
// Нужен ровно для одного: понять, изменились ли правила. Выученные адреса
// привязаны к правилам, по которым их выучили, и при смене правил их надо
// сбросить, иначе снятая галочка продолжала бы действовать до переподключения.
func (r Rules) Signature() string {
	zones := make([]string, 0, len(r.Zones))
	for zone := range r.Zones {
		zones = append(zones, zone)
	}
	sort.Strings(zones)
	domains := append([]string(nil), r.Domains...)
	sort.Strings(domains)
	cyr := "0"
	if r.Cyrillic {
		cyr = "1"
	}
	return cyr + "|" + strings.Join(zones, ",") + "|" + strings.Join(domains, ",")
}

// Match проверяет, должен ли хост уходить в обход.
//
// Хост нормализуется: обрезаются пробелы, строка переводится в нижний регистр,
// убирается завершающая точка, а punycode-метки `xn--...` декодируются в
// Unicode. Декодирование обязательно: кириллическая зона в DNS всегда приезжает
// в punycode (`xn--p1ai`), и без него флаг «кириллические зоны» не сработал бы
// никогда.
//
// Совпадение по домену — `host == rule || strings.HasSuffix(host, "."+rule)`.
// Якорь по точке отсекает `67ozon.ru` при правиле `ozon.ru`: без него подошло
// бы любое имя, просто оканчивающееся на нужные буквы.
func (r Rules) Match(host string) bool {
	if strings.TrimSpace(host) == "" {
		return false
	}
	if r.Empty() {
		return false
	}

	normalized := Normalize(host)
	if normalized == "" {
		return false
	}

	labels := strings.Split(normalized, ".")
	// Голая зона без метки перед ней («ru») правилом зоны не покрывается: это
	// не имя сайта, а сама зона.
	if len(labels) < 2 {
		return false
	}
	tld := labels[len(labels)-1]

	if _, ok := r.Zones[tld]; ok {
		return true
	}
	if r.Cyrillic && hasCyrillic(tld) {
		return true
	}

	for _, rule := range r.Domains {
		if normalized == rule || strings.HasSuffix(normalized, "."+rule) {
			return true
		}
	}
	return false
}

// Normalize приводит хост к виду, в котором его сравнивают правила.
func Normalize(host string) string {
	trimmed := strings.ToLower(strings.TrimSpace(host))
	trimmed = strings.TrimRight(trimmed, ".")
	if trimmed == "" {
		return ""
	}
	// idna.ToUnicode на профиле Punycode только раскодирует метки и не
	// валидирует имя: как и котлиновый IDN.toUnicode, он не должен отбрасывать
	// хост, который просто выглядит непривычно. При ошибке остаёмся на исходной
	// строке — хуже, чем ничего, она не будет.
	if decoded, err := idna.ToUnicode(trimmed); err == nil && decoded != "" {
		return decoded
	}
	return trimmed
}

// hasCyrillic повторяет котлиновую проверку
// `Character.UnicodeBlock.of(c) == CYRILLIC`, то есть ровно блок U+0400..U+04FF.
//
// Блоки Cyrillic Supplement (U+0500..U+052F) и Extended-* сюда намеренно не
// входят: в котлине их тоже нет, а расхождение экрана и ядра дороже, чем
// теоретическая зона на букве из дополнения.
func hasCyrillic(label string) bool {
	for _, r := range label {
		if r >= 0x0400 && r <= 0x04FF {
			return true
		}
	}
	return false
}

// parseZones повторяет DomainBypassRules.parseZones.
func parseZones(raw string) map[string]struct{} {
	zones := make(map[string]struct{})
	for _, token := range splitTokens(raw) {
		zone := strings.TrimLeft(strings.ToLower(strings.TrimSpace(token)), ".")
		if zone == "" {
			continue
		}
		zones[zone] = struct{}{}
	}
	return zones
}

// parseDomains повторяет DomainBypassRules.parseDomains: снимает схему, путь,
// ведущие точки и префикс `www.`, а токены без точки выбрасывает — одиночная
// метка вроде `localhost` доменным правилом быть не может.
func parseDomains(raw string) []string {
	seen := make(map[string]struct{})
	domains := make([]string, 0)
	for _, token := range splitTokens(raw) {
		s := strings.TrimSpace(token)
		if idx := strings.Index(s, "://"); idx >= 0 {
			s = s[idx+3:]
		}
		if idx := strings.Index(s, "/"); idx >= 0 {
			s = s[:idx]
		}
		s = strings.TrimSpace(strings.ToLower(s))
		s = strings.TrimLeft(s, ".")
		s = strings.TrimPrefix(s, "www.")
		if s == "" || !strings.Contains(s, ".") {
			continue
		}
		if _, exists := seen[s]; exists {
			continue
		}
		seen[s] = struct{}{}
		domains = append(domains, s)
	}
	return domains
}

func splitTokens(raw string) []string {
	return strings.FieldsFunc(raw, func(r rune) bool {
		switch r {
		case ',', ' ', '\n', '\r', '\t':
			return true
		}
		return false
	})
}

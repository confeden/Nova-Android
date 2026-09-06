package dnsname

import "testing"

func TestParseRules(t *testing.T) {
	t.Run("zones strip leading dot and mixed separators", func(t *testing.T) {
		rules := ParseRules(".ru, su\nxn--p1ai", "", false)
		for _, want := range []string{"ru", "su", "xn--p1ai"} {
			if _, ok := rules.Zones[want]; !ok {
				t.Fatalf("zone %q missing from %v", want, rules.Zones)
			}
		}
		if len(rules.Zones) != 3 {
			t.Fatalf("unexpected zones: %v", rules.Zones)
		}
	})

	t.Run("domains strip scheme www and path", func(t *testing.T) {
		rules := ParseRules("", "https://www.ozon.ru/path/to/page", false)
		if len(rules.Domains) != 1 || rules.Domains[0] != "ozon.ru" {
			t.Fatalf("unexpected domains: %v", rules.Domains)
		}
	})

	t.Run("domains without a dot are dropped", func(t *testing.T) {
		rules := ParseRules("", "localhost ozon.ru", false)
		if len(rules.Domains) != 1 || rules.Domains[0] != "ozon.ru" {
			t.Fatalf("unexpected domains: %v", rules.Domains)
		}
	})

	t.Run("duplicates collapse", func(t *testing.T) {
		rules := ParseRules("", "ozon.ru,www.ozon.ru, OZON.RU", false)
		if len(rules.Domains) != 1 {
			t.Fatalf("unexpected domains: %v", rules.Domains)
		}
	})

	t.Run("signature is stable regardless of order", func(t *testing.T) {
		a := ParseRules("ru,su", "ozon.ru,vk.com", true)
		b := ParseRules("su, ru", "vk.com ozon.ru", true)
		if a.Signature() != b.Signature() {
			t.Fatalf("signatures differ: %q vs %q", a.Signature(), b.Signature())
		}
		c := ParseRules("ru", "ozon.ru,vk.com", true)
		if a.Signature() == c.Signature() {
			t.Fatalf("signature did not change after dropping a zone")
		}
	})
}

func TestRulesMatch(t *testing.T) {
	cases := []struct {
		name     string
		zones    string
		domains  string
		cyrillic bool
		host     string
		want     bool
	}{
		{"exact domain", "", "ozon.ru", false, "ozon.ru", true},
		{"www subdomain", "", "ozon.ru", false, "www.ozon.ru", true},
		{"deep subdomain", "", "ozon.ru", false, "market.ozon.ru", true},
		{"suffix not on a label boundary", "", "ozon.ru", false, "67ozon.ru", false},
		{"rule used as a prefix of another name", "", "ozon.ru", false, "ozon.ru.evil.com", false},
		{"unrelated host", "", "ozon.ru", false, "example.com", false},
		{"zone ru matches multi-label host", "ru", "", false, "a.b.ru", true},
		{"zone ru ignores another tld", "ru", "", false, "example.com", false},
		{"zone ru ignores the bare zone", "ru", "", false, "ru", false},
		{"zone su", "ru,su", "", false, "old.site.su", true},
		{"trailing dot is stripped", "ru", "", false, "ozon.ru.", true},
		{"uppercase host", "", "ozon.ru", false, "Market.OZON.RU", true},
		{"host with spaces", "", "ozon.ru", false, "  ozon.ru  ", true},
		{"punycode decodes to a cyrillic tld", "", "", true, "xn--80aswg.xn--p1ai", true},
		{"unicode cyrillic host", "", "", true, "сайт.рф", true},
		{"cyrillic flag off", "", "", false, "сайт.рф", false},
		{"cyrillic flag does not catch latin", "", "", true, "example.com", false},
		{"cyrillic bare zone", "", "", true, "рф", false},
		{"punycode zone rule does not survive decoding", "xn--p1ai", "", false, "xn--80aswg.xn--p1ai", false},
		{"punycode domain rule after decoding", "", "сайт.рф", false, "www.xn--80aswg.xn--p1ai", true},
		{"empty host", "ru", "ozon.ru", true, "", false},
		{"blank host", "ru", "ozon.ru", true, "   ", false},
		{"dot only host", "ru", "ozon.ru", true, ".", false},
		{"empty rules", "", "", false, "ozon.ru", false},
		{"zone and domain together", "su", "ozon.ru", false, "market.ozon.ru", true},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			rules := ParseRules(tc.zones, tc.domains, tc.cyrillic)
			if got := rules.Match(tc.host); got != tc.want {
				t.Fatalf("Match(%q) = %v, want %v (rules=%+v)", tc.host, got, tc.want, rules)
			}
		})
	}
}

package cfws

import (
	"strings"
	"testing"
	"time"
)

func resetNeutralSNI() {
	neutralSNI.mu.Lock()
	defer neutralSNI.mu.Unlock()
	neutralSNI.choice = ""
	neutralSNI.literalUntil = time.Time{}
}

func TestNeutralSNISubstitutesOwnedHost(t *testing.T) {
	resetNeutralSNI()
	sni, substituted := NeutralSNI("kws2.nova-app.eu")
	if !substituted {
		t.Fatalf("для своего домена ожидали подстановку, получили %q", sni)
	}
	if !strings.HasSuffix(sni, "."+ownedDomain) && sni != ownedDomain {
		t.Fatalf("подставлено имя вне зоны: %q", sni)
	}
}

// Замерено на живом краю CF: `kws5-1.nova-app.eu` с apex в SNI отвечает 403 —
// три прогона подряд, при валидном токене и при том, что `www.` в тех же
// условиях даёт 429. Кандидат, уводящий зону в пятнадцатиминутный откат на
// трети маршрутов, в списке не нужен.
func TestNeutralSNIDoesNotOfferTheApex(t *testing.T) {
	for _, candidate := range neutralSNICandidates() {
		if candidate == ownedDomain {
			t.Fatal("apex вернулся в кандидаты: медийный маршрут отвечает на него 403")
		}
		if !strings.HasSuffix(candidate, "."+ownedDomain) {
			t.Fatalf("кандидат вне зоны: %q", candidate)
		}
	}
}

func TestNeutralSNILeavesForeignHostsAlone(t *testing.T) {
	resetNeutralSNI()
	for _, host := range []string{
		"kws2.web.telegram.org",
		"kws2.pclead.co.uk",
		"nova-app.eu.evil.com",
	} {
		sni, substituted := NeutralSNI(host)
		if substituted {
			t.Fatalf("%s: чужой домен подменён на %q", host, sni)
		}
		if sni != host {
			t.Fatalf("%s: имя изменено на %q", host, sni)
		}
	}
}

func TestNeutralSNIKeepsOneNamePerProcess(t *testing.T) {
	resetNeutralSNI()
	first, _ := NeutralSNI("kws2.nova-app.eu")
	for i := 0; i < 20; i++ {
		again, _ := NeutralSNI("kws5.nova-app.eu")
		if again != first {
			t.Fatalf("имя сменилось внутри процесса: %q → %q", first, again)
		}
	}
}

func TestNeutralSNIApexIsNotItsOwnSubstitution(t *testing.T) {
	resetNeutralSNI()
	neutralSNI.choice = ownedDomain
	sni, substituted := NeutralSNI(ownedDomain)
	if substituted {
		t.Fatalf("apex объявлен подставленным: %q", sni)
	}
}

func TestNeutralSNIRollsBackToLiteralName(t *testing.T) {
	resetNeutralSNI()
	base := time.Unix(1_800_000_000, 0)
	if _, substituted := neutralSNIAt("kws2.nova-app.eu", base); !substituted {
		t.Fatal("до отката ожидали подстановку")
	}
	if started := noteNeutralSNIRejectedAt("kws2.nova-app.eu", base); !started {
		t.Fatal("первый отказ должен открывать окно отката")
	}
	if started := noteNeutralSNIRejectedAt("kws2.nova-app.eu", base.Add(time.Minute)); started {
		t.Fatal("повторный отказ внутри окна не должен открывать его заново")
	}
	sni, substituted := neutralSNIAt("kws2.nova-app.eu", base.Add(time.Minute))
	if substituted || sni != "kws2.nova-app.eu" {
		t.Fatalf("внутри окна отката ожидали литеральное имя, получили %q", sni)
	}
	// Повторный отказ окно не открывает заново, но продлевает: сеть, которая
	// отвергает имя прямо сейчас, не станет к нему добрее оттого, что первый
	// отказ случился минутой раньше.
	sni, substituted = neutralSNIAt("kws2.nova-app.eu", base.Add(neutralSNIBadTTL+time.Second))
	if substituted {
		t.Fatalf("окно отката не продлилось повторным отказом: получили %q", sni)
	}
	sni, substituted = neutralSNIAt(
		"kws2.nova-app.eu",
		base.Add(time.Minute).Add(neutralSNIBadTTL).Add(time.Second),
	)
	if !substituted {
		t.Fatalf("после окна отката ожидали подстановку, получили %q", sni)
	}
}

func TestNeutralSNIRejectionIgnoresForeignHosts(t *testing.T) {
	resetNeutralSNI()
	if noteNeutralSNIRejectedAt("kws2.web.telegram.org", time.Unix(1_800_000_000, 0)) {
		t.Fatal("чужой домен не должен откатывать нашу зону")
	}
	if _, substituted := NeutralSNI("kws2.nova-app.eu"); !substituted {
		t.Fatal("после чужого отказа своя зона должна работать как прежде")
	}
}

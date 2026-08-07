package nova

import (
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"hash/fnv"
	"log"
	"net"
	"net/netip"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"golang.org/x/net/ipv4"
)

var reservedBytes []byte = nil
var reservedMode = "all"
var fakePacketsEnabled = true
var fakeStrategyProfile = "aggressive"
var desktopFakeBurstRepeats = 10
var fakeTemplateHintHost = ""

func SetReservedBytes(b []byte) {
	if len(b) >= 3 {
		reservedBytes = make([]byte, 3)
		copy(reservedBytes, b)
		log.Printf("WARP Reserved Bytes configured: %v", reservedBytes)
	}
}

func ResetPacketTweaks() {
	reservedBytes = nil
	reservedMode = "all"
	fakePacketsEnabled = true
	fakeStrategyProfile = "aggressive"
	desktopFakeBurstRepeats = 10
	fakeTemplateHintHost = ""
	ResetAwgCompatConfig()
}

func SetReservedMode(mode string) {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "off":
		reservedMode = "off"
	case "handshake":
		reservedMode = "handshake"
	default:
		reservedMode = "all"
	}
	log.Printf("WARP Reserved Mode configured: %s", reservedMode)
}

func SetFakePacketsEnabled(enabled bool) {
	fakePacketsEnabled = enabled
	log.Printf("WARP Fake Packet Injection: %v", fakePacketsEnabled)
}

func SetFakeTemplateHintHost(host string) {
	fakeTemplateHintHost = normalizeFakeTemplateHost(host)
	if fakeTemplateHintHost == "" {
		log.Printf("WARP Fake Template Host cleared")
		return
	}
	log.Printf(
		"WARP Fake Template Host: %s -> %s",
		fakeTemplateHintHost,
		currentFakeQuicTemplateName(),
	)
}

func SetFakeStrategyProfile(profile string) {
	switch strings.ToLower(strings.TrimSpace(profile)) {
	case "desktop-warp":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 10
	case "desktop-warp-6":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 6
	case "desktop-warp-10":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 10
	case "desktop-warp-12":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 12
	case "desktop-warp-14":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 14
	case "desktop-warp-18":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 18
	case "desktop-warp-8":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 8
	case "desktop-warp-16":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 16
	case "desktop-warp-20":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 20
	case "desktop-warp-24":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 24
	case "desktop-warp-28":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 28
	case "desktop-warp-32":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 32
	case "desktop-warp-40":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 40
	case "desktop-warp-48":
		fakeStrategyProfile = "desktop-warp"
		desktopFakeBurstRepeats = 48
	case "desktop-hybrid":
		fakeStrategyProfile = "desktop-hybrid"
		desktopFakeBurstRepeats = 10
	case "amnezia-quic-light":
		fakeStrategyProfile = "amnezia-quic-light"
		desktopFakeBurstRepeats = 6
	case "amnezia-quic":
		fakeStrategyProfile = "amnezia-quic"
		desktopFakeBurstRepeats = 10
	case "amnezia-quic-max":
		fakeStrategyProfile = "amnezia-quic-max"
		desktopFakeBurstRepeats = 14
	case "amnezia-awg-light":
		fakeStrategyProfile = "amnezia-awg-light"
		desktopFakeBurstRepeats = 4
	case "amnezia-awg-exact":
		fakeStrategyProfile = "amnezia-awg-exact"
		desktopFakeBurstRepeats = 4
	case "amnezia-awg-dnsmix":
		fakeStrategyProfile = "amnezia-awg-dnsmix"
		desktopFakeBurstRepeats = 4
	case "amnezia-awg-quicmix":
		fakeStrategyProfile = "amnezia-awg-quicmix"
		desktopFakeBurstRepeats = 6
	case "amnezia-awg-v2":
		fakeStrategyProfile = "amnezia-awg-v2"
		desktopFakeBurstRepeats = 7
	case "amnezia-awg-v2max":
		fakeStrategyProfile = "amnezia-awg-v2max"
		desktopFakeBurstRepeats = 9
	case "amnezia-awg-chatstealth":
		fakeStrategyProfile = "amnezia-awg-chatstealth"
		desktopFakeBurstRepeats = 11
	case "amnezia-awg-chat":
		fakeStrategyProfile = "amnezia-awg-chat"
		desktopFakeBurstRepeats = 8
	case "amnezia-awg-chatmax":
		fakeStrategyProfile = "amnezia-awg-chatmax"
		desktopFakeBurstRepeats = 10
	case "amnezia-awg":
		fakeStrategyProfile = "amnezia-awg"
		desktopFakeBurstRepeats = 4
	case "amnezia-awg-max":
		fakeStrategyProfile = "amnezia-awg-max"
		desktopFakeBurstRepeats = 6
	case "aggressive-8":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 8
	case "aggressive-6":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 6
	case "aggressive-10":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 10
	case "aggressive-12":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 12
	case "aggressive-14":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 14
	case "aggressive-18":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 18
	case "aggressive-16":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 16
	case "aggressive-20":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 20
	case "aggressive-24":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 24
	case "aggressive-28":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 28
	case "aggressive-32":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 32
	case "aggressive-40":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 40
	case "aggressive-48":
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 48
	case "off":
		fakeStrategyProfile = "off"
		desktopFakeBurstRepeats = 0
	default:
		fakeStrategyProfile = "aggressive"
		desktopFakeBurstRepeats = 10
	}
	log.Printf("WARP Fake Strategy Profile: %s (desktop repeats=%d)", fakeStrategyProfile, desktopFakeBurstRepeats)
}

// The fake QUIC packet payload (Base64)
// Fake QUIC Initial Packet (Seems more effective than Google)
// Header: c3, Version: 1, DCID Len: 8 (at offset 5), DCID: 8 bytes (at offset 6)
const FakeQuicPacketB64 = "wwAAAAEIeOeYRrvzeYQAAESesuIbTYvML8k+74FJlUpbpjZLkRkbfQ7pPzj/kZL9Fu+3ogPlQ8dyMeP89o8Auy/DSwZaokXfGeuOwoSnfRtQe3OzcZK8imo9mFgmDBxr+y10Kbw8odbQHHk3KTKRDD0/8g5SJAkQBIKWNLqrjZ3+1tCq9p6GcDAc3qo+UCwnJkD6S+3zJDKp/T605OBNXZ0CWT78aGf6p7S4qYfZIhlTwaLpC3DRfnoU6fjOealCm82TouI15OZz2SddeAM94DLpdzpR6RsiF50yvp6XhGGrtG4aqVV+SzXrwxUkgyMrL8hjMlEGvRjXS7KHVda1WHEENZabAalpnEbSY4+7q+Tr21kWGdnpaXbSUHc9b+m8+EjZ9uSeFiAaTZuqjCrg8Awq/bM9kmxDHbj0SrXxGSUovKcBmjQg7N8N641Stbd0FYC2MKELnYtR3ZaDLCwz0eZsJKze/qh+ta8RSm0E97GPcKXE17ZdbfVt/eF+77Aq+J85nWxlCheLfrBJqNN2DDbV56uKq/9VIWHR29TXY/vWQ0UHzPn5KpG9VO2M4wjMzZoNfgTY9YcA+ClVuFOM0BHuCg6x2LAtsR8+Ofr6F0hh/BJzTmL1+dWoznqhCeW+PC4x9NQde8tShTbnhFpwuCRL1S0c+Py2rvQJtGzbj9NQCz+jHaSTpZ35hbdQabjb6jWtKB2Ws/tc3WSZgzVVSXLD2dOgwXCwbhp7vbEsQzBPAG8zcg01mp3zV3bA9u0N66Ne0on5Y30UI79cXsBhwuqivmYW2xpHNT+o8LL7ZhmimySIExeyLDWSOSSfPu33tffnIl4Pjl48p0G3BRdr3SnqhmW1DQFKl1XODREQTAwL5dYu00NgZ9gE9NHj6UGv0Wim7q9FVzLTK3C0nUrs2EVPBZhOcKaOgOHz8L4T06vK02haE+J/n1NsKxQffG9eOsZvmY/G8iAPd4rnRQV8w1vEPgcwyIgpAfREMDEjDZo0RLpAdW4HQ0gBmuFjeKSQ3yRQhRawu4mkrZOf6vfZRS6Wt74wx0s4CUSUwVyyU+WgHMPnWTrljlkoz61LHXDtedEZbg/lD1vjA3pt2/rYmYxmz7+P8+0ozYu4WAlRxG+BwMWZP0qvnwNQNtYUEHb9i/ffKVcW/IHO7/nSSb4JII/z5+oH7otIVVHSEqhxdAWZsDzKjCFInpRjxGL3awJBHnq+ULxEB3+nQbL6sQCjexBGocISAJ9oSXItUcPc6tBxeA/L97oMOpOyFMjR/OTYNWRJjKcDEw7UugROORI+ODAsOpgLte4By2IsGyMp7Ehsi45ZNYgT+OleAgSdkK0EIbkWgQE30NAVIERobsj6FGuIKLzlpVEIb296FBO7PbnJX3wtMjn3zF1wTxlKWyJJc/QshKIFdu9iOKzmx09T+RJuNApwts1ITGtRDc/RiHX6Zuihxw2lZHiKUXKyuNz1EzOj8shdnD8CkEawlP+301xKA9Gha+qp7SeDdC+KqFHKDFxkknOqOB3ZDJUijL+9n7RbDu32TGnEaaJCkE+VQEr94woNA8SxigFvW7dKtpEGSqPticyVBQsiI0atDHBW"
const FakeQuicPacketGoogleComB64 = "xgAAAAEInfzNNZlGdPgDhXQsAEIV/T3P3J/BRKDjP0yhSOdR0ylOsEZVOBRpyeLKetzsK8h4EsO966poL3GZ9lD/hLsXkPoQQ9T94EC339tTz8KOmweK8symaEyAym9+g2tkN/MzEQN5sOgrOSD9zEhOgelDXdDr0+kazpkTEjC+CjPa3M35T1naYaiMx6HsCxbLHnpFw0oCtvitf5Lz3AhK/VsxnvZYF68X58nNf/Lnlf16ul+aOfrg2S/rosAS3rmLBBS7MYABSW/VKQFqoqMLSYpGvOS26LzIlpvC89vcHxhLcnS0VTCS7nKQYLm4WgNJZV2WE9C5OdahyKte+By2/oHhuhfV7Dvz14C8CCQuwK6JhHHvQCfhGubArQTrAuQvjp+05+FoRVWTDBtEfYKJgYMReSLekCMpGPBZCXj9FYjJNgvZJweFUxV9JHsZrR5QHUGNhm11N8MbKocFY27sQqGnX2Dhy3eA/SSXbK9uX04vXK09vJWy+TUAlIlKFGFPOGvJ94UCGD+XBVSqvEGoCY23K9GlEtkbALfVkicHNjepoCfZKYXbSvCvijqmQ/z9iCJt4Ap5rMGxKfV9xth3IDkNY5VGwhTGvSEO72AU9IOQbyyQEu1qZluYyrKGl8boKCbp8/La4Cz0V8yu5SDhaIYiizmGs+TMqDHo8xGj28zj1GhvTQic4slrIl5J2YPY44ZpD63v1tcOujDRwOneEJ7FTW1sNGwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
const FakeQuicPacketVkB64 = "wwAAAAEIcKycBfSdK/8DQdJgAEIVeKzitQ6A06PossLi5fUK6/b3Nkyfvta+jBRgZEXbflwPdbgl/8PYcrPEY0IvDGM0tFodEpfuKr2mFQEQhk3kWt5SuKLjOnxNs5lnjMsFAc4UaWrh3kDDUCk9MdsHOXbj6uSTUANY31m24Whn1MOf9nAWi/CrUOQ6oPwIFMB2Iif/k/M0Ui2VYhQtze9yQbVUv+LCejqwZtUW9NMaR1JjGMZE4V2Q6YiZ4lwM6KZ+FN8Ul2nD0Ugz0nol4l/eiv1o9YfMVz6MiOUCeTtQYm9MUmeleGspAxcqDvTuovooKgLj0zhdWYuqnKy5OV1sQ8XMu9zphFo53thHd58AxEz13zTzrSoi5jUEMWt0jquss7A6HMPfnI1qtgoCVbf41DPW0KZxtc8woK8sBKcTjMGyZDguFk67vMKQF2rJ1mcuV8rFXv+p35kaDsG07WORBDL/A7GHw6IiBsakkU4W1Z428BGgjwPzrHuu0GqIT5+j7oSrLQl9SGP4TtyHtiTKmur87JIDOdOt3HtfriHlnMR8WBR7JEMArYV+cbjLl3LE/tinp3V0Tw2ESMcKSR46f6WpjAmXvpMZoySVARyvtML5s63h7xpe+8AN1zdOXqAibWKTSihHxVwNUkM31Ac1V+lrn/F3QU7wOUVQP7fGFJ20w/SkSecDY/4lk2DeDfDRlPQ6RN02SsrbZoMmKSfhs9vLuOimEKsAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
const FakeQuicPacketRutrackerB64 = "wwAAAAEU+qZ38YiU8+PT/9U85gktRz1ab4sUVmFId2sjyNIBrfZyNV9NQFa3B8cAgAAEfHdtzsk/ngMJNp7xLVNB4+KtyLnznCFuQ9M3avB4ySZHp0i4yiKkpXm+rpdGUdQ8iHW02NiEqENuSPuukODF9LcnMPmCj7Na2psbhGBI+UrESKTvcIU+RDdWuYta1TXt+Dj/m4AqIciioDgxqQc9k/tVnCGLa0kk8wPKq6pwJUFREBPhO+Y00WmTjweoljSZwyePWEF+YboUI+vm2Sc8Jfo8uBCvSYlqUIb5AiwuHcTyJcUQYNgu23cCTV/qFtlsMpjCJM3X11ovqS8OLq4QteN1ymZCAROrOvmvWpdFIqBSN7RMTnQw2zrjrv8HtJoumCl/xrSx1R6LT2FyJqQldnjUr+T7SX0gGFep7fgn3nm4CYrsE3lxX4P2WHrEMsS03i4tQmY97EktbEabc78V+1E2xdmzZomoi8tOUtb/K7k82YK3JVSJTbpaseK+gAQ6dY2Tm+wXoIMegeDZz3QzLAM5QxNWlOEIbKLmu5Cg6yyewzwiARJGsS9Q7dkdgJivMqvO15xCA6ZeQQVX3iiQci1JmlH6M+LXfi0jEU7rJZQK9bVaT3duXliFdBnHA3KK9Tio4fTVslLaoWw8RY56k4b70A16umXbNmNr6r1xI22l1m39RK22eZJu/bMjza6j1jTyW4FSRFaCoqTwljkTUkCD4f7aYpnjv4rDf/ch6tGdSIwDHvS8H6cf2MmzAckMAB0NFhTqVmyOc+SIm/8AD+naFCzaQgVqRcp448aRppfWSQai6hWKekU5FqExTxVoT5qhKad8G9tZdFX7b0ycZDW5PDmlKcFEVjaSY6/ipc1TYY1r2XaTsEWzMvaCj8KKE+mL+ZB3cEyCA+sawZXLjrp/6Gwk5NW3F/TetqhtPFL4u6yo1VvxWvES/o9ZJWC3KE5VwS7RuuO7vgtkzkRsYS74eQny02/4z3VJ5M+uzXO8bL2//ytTPSI51HdFozEK1kdwilSFkZdTKyCH2gMNI7rp7LZmWiGpcNmJvDrnBWMUNNhKQMzy2DMduZboPi3Gl2rEgnkAxXci13fw57RtX7glYQa7BmPRF3G1f2KU3n9kdPWkShSDYb5ewiaqSMEKOuTakodaKv3Q4ZiqYYaFytpvPvqy8d5Npz098f9DFV0/CEWXamw6hTFyk81bPvTrtMtNP8LpyV0MBB/TUWeTDC/DP4AnBR7U9AMkIVi9e4gcDB4pLHh/TKzbYhQDyXC0n9ZxkjAhmU0zitfD7OgeClHgoI4IyeIBqKpBrsjwQrVORoZ+eTpc+mprAnRF1ZbJ/T0cDvQv5soqeZofvgCCNbBSxusF1oxjX0pbXUW1OerfFQaPzDFuZP6/WGoAd7ju5f3ZCqkVAOFMTRZYZH8TeGz7hR+wTFyLA3N2McJ21TRzQ4JtYeuTv67trTLwLRjvw0bTFBLst+9WhA6Sq6uo1EWZbcuqbjv9Of37AwZX6RSww8ICxPC0pHZV0rIldIgeLoosKSi3dII9rM3Rf2U7I1gNnD5n39DNq7EMy5/mJmu4gXF0mvXBmfPlwkC/"
const FakeQuicPacketFacebookB64 = "zwAAAAEUOpmh2NMO2j4NtLwP5OiZB67M30wUxwdym4thdo2Zt31Y+r4sF8sDFMAAgAAEfNcodYz9S1XM2OdTW/q/qj1R2lji//1CoVPGa9pOOWWPWQSSd0U+IQ6bACLrMdRe/KhTE0D5abQgUMTsPqMzl0AMtLyIprxayApattJIelJut/XNMsukJfhISt1SAv9CTBPqGVY3AayVrdeZ8xcUnvKk3zubfvamj+3NaMxbeWUhcVQfoI8peUK6SB3SiUz0kxy2MWCCyMODYPIeYomPdIK5BPxZsyqBv5BGHmtDYtC8+kdTWNCrAsa3uLFAytdBhb6JvmeQomkjgCp4BXZVYy/uHOYKBdKIP7ZvYLLa6YN+5H3SGIm7H6VYfivdI0asyqTJa7X6TxavFkJmm6A7jJLofwsnwK0+aE1ys+Rp8lxZnythrjGMroTXDXvs0cMzazAhdf5ty5d+uOLopGv8Imd+RejVkb7d77PTVgdaMXbRe4yQ58Kx3hUx3G9eWIcttI3/Pp3wD95a3PwoYDfwn1fuu4VkQ8FKeJTu/ncHMH85HerodOuuGmUox/9kx+Qx90vpfwBtIRn4m9zpqh3WbQSR22ALdGb4qKJPfkJF8Prree0OIoLSAipF38YpepdAhVueH7Clk0XxOszB5Y4OQj+cK8qNMZx+4bE1fTVXrHsq/HQWbUlpGa+tsx/fxMxPYduBRUvSQrFrj3cS9uYJuGYQHxswvSjVdTmqsBxPe2FtBKMNF59U9P8Tc++GgcjVZBoGRQXJkDO+0/jbiXa8n2sWfHeoedg4R8IBLeHqOfp8sPp2UX0dAigm+BH8hGxZBvaujpQp9LBbIpal3r0iZFn45fEJHODLX54RK2gAzupwBlnsnbJdz+Kt2RcxAtrBGedT88G9hwwFVUlev++Jywy8yqRNL3ekKFwYk/xYuQrVQwknqyba7F3bIpMmHDXVy3f1eEYxARIuokywCBMPo38q0sJfeiLKuG0pQHz0DQq1RJjJnw4dXv9CAJMmrBzJeGp9aNH5uRE556IR2HLULM/oJe9ay4DdI3hupjgjTjBlSRcs0NM823TwREkvaRUcTFJztQisP8OuEMn/GG+RYVMffzsN+D3nq7zmV1gvV5hDo/pyHgrzkExXtbVRvBFmv1ZGfBmDqqJY8e8PM+zEXpNoKzS56zwfLiiC0g0BCu6HLz1+RaE3c35mjwxD0JIpSQTeXaIObSOns4LaQUW9T7/ugYU7WKIKyDXSzIMj+9AeqcufOCDL1u5zeO7jhN1ZcDvT9cCWPWcq/yR3V9nOB3dVAOvH5LXkS7p7Hyum7o5KgkbyTv6uUoja2BhL6q4DJZWPI42YF9DyRCG8YSQMkkthg2rDTBJC/UXlkEvFs/BziOPkaRkEp82CxVJkKFMMVYRfcakrJEAoRQwJdR9IoVu05X8cqQxYB5FU7HtNmQpSlUyNJ3NkU+SKF79iFTbpchzvuqDcKE1cIofG9/KQMud/x2naYMOhKID2YX8M2Neg9OxBsMm9hbclkGr8Lgce4iLdBxh7gsYcj7rO1cAGGloOzuYWKgTfQZUgH5zhpYzeccA6j1Lchjcp+GLm"

type fakeQuicTemplate struct {
	name     string
	b64      string
	keywords []string
}

var fakeQuicTemplates = []fakeQuicTemplate{
	{name: "www-google", b64: FakeQuicPacketB64, keywords: []string{"google", "gstatic", "youtube", "ytimg", "googlevideo", "gmail", "blogspot", "twitch", "ttvnw", "twitchcdn"}},
	{name: "google", b64: FakeQuicPacketGoogleComB64, keywords: []string{"cloudflare", "chatgpt", "openai", "microsoft", "github", "reuters", "bbc", "nytimes"}},
	{name: "vk", b64: FakeQuicPacketVkB64, keywords: []string{"vk", "vkontakte", "mail.ru", "yandex", "rutube", "rambler", "max.ru", "gosuslugi", "2gis", "ozon", "wildberries", "avito", "megamarket", "market"}},
	{name: "chat", b64: FakeQuicPacketFacebookB64, keywords: []string{"telegram", "telegram.org", "t.me", "telegra.ph", "telesco.pe", "whatsapp", "whatsapp.net"}},
	{name: "rutracker", b64: FakeQuicPacketRutrackerB64, keywords: []string{"rutracker", "kinozal", "torrent", "nnmclub", "rutor"}},
	{name: "facebook", b64: FakeQuicPacketFacebookB64, keywords: []string{"facebook", "instagram", "whatsapp", "meta", "threads"}},
}

var safeFallbackFakeQuicTemplates = []fakeQuicTemplate{
	fakeQuicTemplates[0],
	fakeQuicTemplates[1],
	fakeQuicTemplates[2],
}

var desktopWarpPorts = map[int]struct{}{
	443:  {},
	500:  {},
	1701: {},
	4500: {},
}

func normalizeFakeTemplateHost(host string) string {
	host = strings.ToLower(strings.TrimSpace(host))
	host = strings.TrimPrefix(host, "https://")
	host = strings.TrimPrefix(host, "http://")
	host = strings.Trim(host, "[]")
	if idx := strings.IndexAny(host, "/?#"); idx >= 0 {
		host = host[:idx]
	}
	if idx := strings.Index(host, ":"); idx >= 0 {
		host = host[:idx]
	}
	return strings.Trim(host, ".")
}

func currentFakeTemplateHost() string {
	host := normalizeFakeTemplateHost(fakeTemplateHintHost)
	if host != "" {
		return host
	}
	return "www.google.com"
}

func currentFakeHttpPath() string {
	paths := []string{
		"/generate_204",
		"/favicon.ico",
		"/robots.txt",
		"/assets/app.js",
		"/api/v1/sync",
	}
	host := currentFakeTemplateHost()
	hasher := fnv.New32a()
	_, _ = hasher.Write([]byte(host))
	idx := int(hasher.Sum32() % uint32(len(paths)))
	return paths[idx]
}

func pickFakeQuicTemplate(host string) fakeQuicTemplate {
	host = normalizeFakeTemplateHost(host)
	if host == "" {
		return safeFallbackFakeQuicTemplates[0]
	}
	for _, template := range fakeQuicTemplates {
		for _, keyword := range template.keywords {
			if keyword != "" && strings.Contains(host, keyword) {
				return template
			}
		}
	}
	hasher := fnv.New32a()
	_, _ = hasher.Write([]byte(host))
	idx := int(hasher.Sum32() % uint32(len(safeFallbackFakeQuicTemplates)))
	return safeFallbackFakeQuicTemplates[idx]
}

func currentFakeQuicTemplateName() string {
	return pickFakeQuicTemplate(fakeTemplateHintHost).name
}

func currentFakeQuicPacketB64() string {
	return pickFakeQuicTemplate(fakeTemplateHintHost).b64
}

// SmartBind implements conn.Bind but adds packet injection capabilities.
type SmartBind struct {
	conn       *net.UDPConn
	v6conn     *net.UDPConn
	mu         sync.Mutex
	flowMu     sync.Mutex
	flowState  map[string]endpointFlowState
	sendBudget atomic.Int64
	recvBudget atomic.Int64
}

type endpointFlowState struct {
	dataBursts int
	lastBurst  time.Time
}

func NewSmartBind() conn.Bind {
	bind := &SmartBind{
		flowState: make(map[string]endpointFlowState),
	}
	bind.sendBudget.Store(20)
	bind.recvBudget.Store(20)
	return bind
}

// Global protector callback
var GlobalProtector func(fd int) bool

func (b *SmartBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	var err error
	// IPv4 Listen
	addr4 := net.UDPAddr{Port: int(port)}
	b.conn, err = net.ListenUDP("udp4", &addr4)
	if err != nil {
		return nil, 0, err
	}

	// PROTECT SOCKET IF CALLBACK IS SET
	if GlobalProtector != nil {
		// Use SyscallConn to get FD safely without Side Effects (Blocking Mode)
		rc, err := b.conn.SyscallConn()
		if err == nil {
			rc.Control(func(fd uintptr) {
				GlobalProtector(int(fd))
			})
		}
	}

	// IPv6 Listen (Best effort)
	addr6 := net.UDPAddr{Port: int(port), IP: net.IPv6zero}
	b.v6conn, _ = net.ListenUDP("udp6", &addr6)
	if b.v6conn != nil && GlobalProtector != nil {
		rc, err := b.v6conn.SyscallConn()
		if err == nil {
			rc.Control(func(fd uintptr) {
				GlobalProtector(int(fd))
			})
		}
	}

	// Get actual port
	_, portStr, _ := net.SplitHostPort(b.conn.LocalAddr().String())
	parsedPort, _ := net.LookupPort("udp", portStr)

	// Create receive functions
	fns := []conn.ReceiveFunc{b.makeReceiveFunc(b.conn)}
	if b.v6conn != nil {
		fns = append(fns, b.makeReceiveFunc(b.v6conn))
	}

	return fns, uint16(parsedPort), nil
}

func (b *SmartBind) makeReceiveFunc(c *net.UDPConn) conn.ReceiveFunc {
	return func(buffs [][]byte, sizes []int, eps []conn.Endpoint) (int, error) {
		n, addr, err := c.ReadFromUDP(buffs[0])
		if err != nil {
			return 0, err
		}

		if n > 0 {
			explicitAwgCompat := hasExplicitAwgCompatConfig()
			legacyWireGuardHeaderRewriteActive := !explicitAwgCompat &&
				!(reservedMode == "off" && fakeStrategyProfile == "off")
			msgType := buffs[0][0]
			if legacyWireGuardHeaderRewriteActive {
				if msgType == 4 && b.recvBudget.Add(-1) >= 0 {
					log.Printf("WG transport recv: len=%d from=%s", n, addr.String())
				}
				if msgType < 1 || msgType > 4 {
					encoded := base64.StdEncoding.EncodeToString(buffs[0][:min(n, 32)])
					log.Printf("DROPPED_ALIEN_PACKET: Len=%d Type=%02x Prefix=%s\n", n, msgType, encoded)
					return 0, nil
				}

				// ZERO OUT RESERVED BYTES SO STANDARD WIREGUARD PARSES IT.
				// Do not do this for explicit AWG profiles: AmneziaWG may use a
				// custom 4-byte message header (H1-H4), and mutating bytes 1..3
				// here corrupts valid inbound packets before the AWG device sees them.
				if n >= 4 {
					buffs[0][1] = 0
					buffs[0][2] = 0
					buffs[0][3] = 0
				}
			} else if b.recvBudget.Add(-1) >= 0 {
				log.Printf(
					"AWG recv passthrough: len=%d from=%s (awg=%v reserved=%s fake=%s)",
					n,
					addr.String(),
					explicitAwgCompat,
					reservedMode,
					fakeStrategyProfile,
				)
			}
		}

		sizes[0] = n
		eps[0] = &NovaEndpoint{Addr: addr}
		return 1, nil
	}
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func (b *SmartBind) Close() error {
	var err1, err2 error
	if b.conn != nil {
		err1 = b.conn.Close()
	}
	if b.v6conn != nil {
		err2 = b.v6conn.Close()
	}
	if err1 != nil {
		return err1
	}
	return err2
}

func (b *SmartBind) SetMark(mark uint32) error {
	// DISABLE SET MARK
	// Android VpnService.protect() handles this closer to the kernel/routing table.
	// Setting it here (especially to 0) might override the protection and cause a loop.
	return nil
}

func (b *SmartBind) ParseEndpoint(s string) (conn.Endpoint, error) {
	addr, err := net.ResolveUDPAddr("udp", s)
	if err != nil {
		return nil, err
	}
	return &NovaEndpoint{Addr: addr}, nil
}

func (b *SmartBind) Send(buffs [][]byte, endpoint conn.Endpoint) error {
	novaEp, ok := endpoint.(*NovaEndpoint)
	if !ok {
		return fmt.Errorf("invalid endpoint type")
	}

	if fakePacketsEnabled && len(buffs) > 0 && len(buffs[0]) > 0 {
		msgType := buffs[0][0]
		whenHandshake := msgType == 1
		explicitAwgCompat := hasExplicitAwgCompatConfig()
		if explicitAwgCompat {
			if whenHandshake {
				b.resetFlowState(novaEp)
				b.injectExplicitAwgCompatHandshake(novaEp)
			}
		} else if isAmneziaProfile() {
			whenKeepalive := msgType == 4 && len(buffs[0]) <= 64
			whenTransportData := msgType == 4 && len(buffs[0]) > 64
			if whenHandshake {
				b.resetFlowState(novaEp)
				b.injectAmneziaJunkChain(novaEp, true)
			} else if whenKeepalive {
				b.injectAmneziaJunkChain(novaEp, false)
			} else if whenTransportData {
				_, _, shouldInject := b.transportBurstPlan(novaEp)
				if shouldInject {
					b.injectAmneziaJunkChain(novaEp, false)
				}
			}
		} else if fakeStrategyProfile == "desktop-warp" {
			if whenHandshake {
				b.resetFlowState(novaEp)
			}
		} else if fakeStrategyProfile == "desktop-hybrid" {
			whenKeepalive := msgType == 4 && len(buffs[0]) <= 64
			whenTransportData := msgType == 4 && len(buffs[0]) > 64
			if whenHandshake {
				b.resetFlowState(novaEp)
				b.injectFakeQuicBurst(novaEp, 5, 15)
			} else if whenKeepalive {
				b.injectFakeQuicBurst(novaEp, 2, 8)
			} else if whenTransportData {
				ttl3Repeats, ttl9Repeats, shouldInject := b.transportBurstPlan(novaEp)
				if shouldInject {
					b.injectFakeQuicBurst(novaEp, ttl3Repeats, ttl9Repeats)
				}
			}
		} else if fakeStrategyProfile != "off" {
			whenKeepalive := msgType == 4 && len(buffs[0]) <= 64
			whenTransportData := msgType == 4 && len(buffs[0]) > 64
			if whenHandshake {
				b.resetFlowState(novaEp)
				b.injectFakeQuicBurst(novaEp, 5, 15)
			} else if whenKeepalive {
				// Refresh DPI desync on post-handshake empty transport packets.
				b.injectFakeQuicBurst(novaEp, 2, 8)
				b.injectDesktopLikeQuicBurst(novaEp, 4)
			} else if whenTransportData {
				ttl3Repeats, ttl9Repeats, shouldInject := b.transportBurstPlan(novaEp)
				if shouldInject {
					// Refresh the fake QUIC camouflage around the first real data
					// packets, because on this network the handshake succeeds first
					// and the data plane gets filtered immediately afterwards.
					b.injectFakeQuicBurst(novaEp, ttl3Repeats, ttl9Repeats)
					desktopRepeats := max(4, desktopFakeBurstRepeats/2)
					if ttl3Repeats >= 2 {
						desktopRepeats = max(10, desktopFakeBurstRepeats)
					}
					b.injectDesktopLikeQuicBurst(novaEp, desktopRepeats)
				}
			}
		}
	}

	for _, buf := range buffs {
		if fakePacketsEnabled &&
			(fakeStrategyProfile == "desktop-warp" || fakeStrategyProfile == "desktop-hybrid") &&
			isDesktopWarpPort(novaEp) &&
			len(buf) > 0 {
			msgType := buf[0]
			if msgType >= 1 && msgType <= 4 {
				b.injectDesktopLikeQuicBurst(novaEp, max(10, desktopFakeBurstRepeats))
			}
		}

		if len(buf) > 64 && buf[0] == 4 && b.sendBudget.Add(-1) >= 0 {
			log.Printf("WG transport send: len=%d to=%s", len(buf), novaEp.Addr.String())
		}

		applyReserved := false
		if reservedBytes != nil && len(reservedBytes) >= 3 && len(buf) >= 4 {
			msgType := buf[0]
			switch reservedMode {
			case "all":
				applyReserved = msgType >= 1 && msgType <= 4
			case "handshake":
				applyReserved = msgType >= 1 && msgType <= 3
			}
		}

		if applyReserved {
			buf[1] = reservedBytes[0]
			buf[2] = reservedBytes[1]
			buf[3] = reservedBytes[2]
		}

		var err error
		if novaEp.Addr.IP.To4() != nil {
			if b.conn != nil {
				_, err = b.conn.WriteToUDP(buf, novaEp.Addr)
			}
		} else {
			if b.v6conn != nil {
				_, err = b.v6conn.WriteToUDP(buf, novaEp.Addr)
			} else if b.conn != nil {
				_, err = b.conn.WriteToUDP(buf, novaEp.Addr)
			}
		}
		if err != nil {
			return err
		}
	}
	return nil
}

func isAmneziaProfile() bool {
	return strings.HasPrefix(fakeStrategyProfile, "amnezia-quic") || strings.HasPrefix(fakeStrategyProfile, "amnezia-awg")
}

type amneziaProfilePlan struct {
	templateRepeats   int
	junkPackets       int
	junkMin           int
	junkMax           int
	templateSet       string
	shadowQuicRepeats int
	shadowDNSRepeats  int
	ttlSequence       []int
}

func amneziaProfilePlanFor(handshake bool) amneziaProfilePlan {
	switch fakeStrategyProfile {
	case "amnezia-awg-light":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats: 2,
				junkPackets:     4,
				junkMin:         40,
				junkMax:         70,
				templateSet:     "awg",
				ttlSequence:     []int{3},
			}
		}
		return amneziaProfilePlan{
			templateRepeats: 1,
			junkPackets:     2,
			junkMin:         40,
			junkMax:         64,
			templateSet:     "awg",
			ttlSequence:     []int{3},
		}
	case "amnezia-awg-exact":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats: 4,
				junkPackets:     4,
				junkMin:         40,
				junkMax:         70,
				templateSet:     "awg-exact",
				ttlSequence:     []int{1, 2, 3, 4},
			}
		}
		return amneziaProfilePlan{
			templateRepeats: 2,
			junkPackets:     2,
			junkMin:         40,
			junkMax:         70,
			templateSet:     "awg-exact",
			ttlSequence:     []int{1, 2, 3, 4},
		}
	case "amnezia-awg-dnsmix":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats:  4,
				junkPackets:      4,
				junkMin:          40,
				junkMax:          72,
				templateSet:      "awg-dnsmix",
				shadowDNSRepeats: 2,
				ttlSequence:      []int{1, 2, 3, 4},
			}
		}
		return amneziaProfilePlan{
			templateRepeats:  2,
			junkPackets:      2,
			junkMin:          40,
			junkMax:          64,
			templateSet:      "awg-dnsmix",
			shadowDNSRepeats: 1,
			ttlSequence:      []int{1, 2, 3, 4},
		}
	case "amnezia-awg-quicmix":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats:   4,
				junkPackets:       4,
				junkMin:           40,
				junkMax:           90,
				templateSet:       "awg-quicmix",
				shadowQuicRepeats: 2,
				shadowDNSRepeats:  1,
				ttlSequence:       []int{1, 2, 3, 4},
			}
		}
		return amneziaProfilePlan{
			templateRepeats:   2,
			junkPackets:       2,
			junkMin:           40,
			junkMax:           80,
			templateSet:       "awg-quicmix",
			shadowQuicRepeats: 1,
			shadowDNSRepeats:  1,
			ttlSequence:       []int{1, 2, 3, 4},
		}
	case "amnezia-awg-v2":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats:   5,
				junkPackets:       6,
				junkMin:           52,
				junkMax:           110,
				templateSet:       "awg-quicmix",
				shadowQuicRepeats: 2,
				shadowDNSRepeats:  2,
				ttlSequence:       []int{1, 3, 2, 4, 3},
			}
		}
		return amneziaProfilePlan{
			templateRepeats:   3,
			junkPackets:       3,
			junkMin:           44,
			junkMax:           86,
			templateSet:       "awg-quicmix",
			shadowQuicRepeats: 1,
			shadowDNSRepeats:  1,
			ttlSequence:       []int{2, 4, 1, 3},
		}
	case "amnezia-awg-v2max":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats:   7,
				junkPackets:       8,
				junkMin:           60,
				junkMax:           140,
				templateSet:       "awg-quicmix",
				shadowQuicRepeats: 3,
				shadowDNSRepeats:  2,
				ttlSequence:       []int{1, 2, 4, 3, 1, 4},
			}
		}
		return amneziaProfilePlan{
			templateRepeats:   4,
			junkPackets:       4,
			junkMin:           52,
			junkMax:           110,
			templateSet:       "awg-quicmix",
			shadowQuicRepeats: 2,
			shadowDNSRepeats:  1,
			ttlSequence:       []int{2, 4, 1, 3, 2},
		}
	case "amnezia-awg-chat":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats:   6,
				junkPackets:       7,
				junkMin:           48,
				junkMax:           116,
				templateSet:       "awg-chat",
				shadowQuicRepeats: 2,
				shadowDNSRepeats:  3,
				ttlSequence:       []int{1, 2, 4, 3, 2, 1},
			}
		}
		return amneziaProfilePlan{
			templateRepeats:   4,
			junkPackets:       4,
			junkMin:           40,
			junkMax:           96,
			templateSet:       "awg-chat",
			shadowQuicRepeats: 2,
			shadowDNSRepeats:  2,
			ttlSequence:       []int{2, 4, 3, 1, 2},
		}
	case "amnezia-awg-chatstealth":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats:   10,
				junkPackets:       12,
				junkMin:           64,
				junkMax:           164,
				templateSet:       "awg-chatstealth",
				shadowQuicRepeats: 4,
				shadowDNSRepeats:  4,
				ttlSequence:       []int{1, 3, 2, 4, 2, 1, 4, 3},
			}
		}
		return amneziaProfilePlan{
			templateRepeats:   6,
			junkPackets:       7,
			junkMin:           52,
			junkMax:           132,
			templateSet:       "awg-chatstealth",
			shadowQuicRepeats: 3,
			shadowDNSRepeats:  3,
			ttlSequence:       []int{2, 4, 1, 3, 2, 4},
		}
	case "amnezia-awg-chatmax":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats:   9,
				junkPackets:       10,
				junkMin:           56,
				junkMax:           148,
				templateSet:       "awg-chat",
				shadowQuicRepeats: 3,
				shadowDNSRepeats:  4,
				ttlSequence:       []int{1, 3, 2, 4, 2, 1, 4},
			}
		}
		return amneziaProfilePlan{
			templateRepeats:   5,
			junkPackets:       6,
			junkMin:           48,
			junkMax:           116,
			templateSet:       "awg-chat",
			shadowQuicRepeats: 2,
			shadowDNSRepeats:  3,
			ttlSequence:       []int{2, 4, 1, 3, 2},
		}
	case "amnezia-awg":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats: 4,
				junkPackets:     4,
				junkMin:         40,
				junkMax:         70,
				templateSet:     "awg",
				ttlSequence:     []int{3},
			}
		}
		return amneziaProfilePlan{
			templateRepeats: 2,
			junkPackets:     2,
			junkMin:         40,
			junkMax:         70,
			templateSet:     "awg",
			ttlSequence:     []int{3},
		}
	case "amnezia-awg-max":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats: 6,
				junkPackets:     6,
				junkMin:         40,
				junkMax:         90,
				templateSet:     "awg",
				ttlSequence:     []int{3},
			}
		}
		return amneziaProfilePlan{
			templateRepeats: 3,
			junkPackets:     3,
			junkMin:         40,
			junkMax:         80,
			templateSet:     "awg",
			ttlSequence:     []int{3},
		}
	case "amnezia-quic-light":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats: 2,
				junkPackets:     2,
				junkMin:         96,
				junkMax:         220,
				templateSet:     "default",
				ttlSequence:     []int{3},
			}
		}
		return amneziaProfilePlan{
			templateRepeats: 1,
			junkPackets:     1,
			junkMin:         72,
			junkMax:         160,
			templateSet:     "default",
			ttlSequence:     []int{3},
		}
	case "amnezia-quic-max":
		if handshake {
			return amneziaProfilePlan{
				templateRepeats: 4,
				junkPackets:     6,
				junkMin:         160,
				junkMax:         640,
				templateSet:     "default",
				ttlSequence:     []int{3},
			}
		}
		return amneziaProfilePlan{
			templateRepeats: 2,
			junkPackets:     3,
			junkMin:         120,
			junkMax:         320,
			templateSet:     "default",
			ttlSequence:     []int{3},
		}
	default:
		if handshake {
			return amneziaProfilePlan{
				templateRepeats: 3,
				junkPackets:     4,
				junkMin:         128,
				junkMax:         420,
				templateSet:     "default",
				ttlSequence:     []int{3},
			}
		}
		return amneziaProfilePlan{
			templateRepeats: 2,
			junkPackets:     2,
			junkMin:         96,
			junkMax:         220,
			templateSet:     "default",
			ttlSequence:     []int{3},
		}
	}
}

func (b *SmartBind) injectAmneziaJunkChain(novaEp *NovaEndpoint, handshake bool) {
	plan := amneziaProfilePlanFor(handshake)
	if plan.templateRepeats <= 0 && plan.junkPackets <= 0 {
		return
	}

	templates := amneziaTemplatesForProfile(plan.templateSet)
	if len(templates) == 0 {
		return
	}
	sendIndex := 0
	sendProfilePayload := func(payload []byte) {
		if len(payload) == 0 {
			return
		}
		ttl := 0
		if len(plan.ttlSequence) > 0 {
			ttl = plan.ttlSequence[sendIndex%len(plan.ttlSequence)]
		}
		sendIndex++
		b.sendToEndpointWithTTL(payload, novaEp, ttl)
	}

	for i := 0; i < plan.shadowDNSRepeats; i++ {
		sendProfilePayload(buildAmneziaDnsPayload())
	}

	for i := 0; i < plan.shadowQuicRepeats; i++ {
		sendProfilePayload(buildAmneziaQuicPayload())
	}

	for i := 0; i < plan.templateRepeats; i++ {
		payload := templates[i%len(templates)]
		sendProfilePayload(payload)
	}

	for i := 0; i < plan.junkPackets; i++ {
		size := randomIntBetween(plan.junkMin, plan.junkMax)
		sendProfilePayload(buildAmneziaRandomJunk(size))
	}
}

func (b *SmartBind) injectFakeQuicBurst(novaEp *NovaEndpoint, ttl3Repeats int, ttl9Repeats int) {
	templateB64 := currentFakeQuicPacketB64()
	fakeDataRoot, err := base64.StdEncoding.DecodeString(templateB64)
	if err != nil || len(fakeDataRoot) == 0 {
		return
	}

	if len(fakeDataRoot) > 50 {
		fakeDataRoot[len(fakeDataRoot)-1] ^= 0xFF
	}
	if len(fakeDataRoot) > 5 {
		fakeDataRoot[1] = 0x0a
		fakeDataRoot[2] = 0x0a
		fakeDataRoot[3] = 0x0a
		fakeDataRoot[4] = 0x0a
	}

	if b.conn != nil {
		p4 := ipv4.NewConn(b.conn)
		if err := p4.SetTTL(3); err == nil {
			log.Printf("TTL set to 3 for fakes")
		}
	}
	for i := 0; i < ttl3Repeats; i++ {
		decoded, _ := base64.StdEncoding.DecodeString(templateB64)
		payload := make([]byte, len(decoded))
		copy(payload, decoded)
		if len(payload) > 14 {
			rand.Read(payload[6:14])
		}
		b.sendToEndpoint(payload, novaEp)
	}

	if b.conn != nil {
		p4 := ipv4.NewConn(b.conn)
		if err := p4.SetTTL(9); err == nil {
			log.Printf("TTL set to 9 for fakes")
		}
	}
	for i := 0; i < ttl9Repeats; i++ {
		decoded, _ := base64.StdEncoding.DecodeString(templateB64)
		payload := make([]byte, len(decoded))
		copy(payload, decoded)
		if len(payload) > 14 {
			rand.Read(payload[6:14])
		}
		b.sendToEndpoint(payload, novaEp)
	}

	if b.conn != nil {
		p4 := ipv4.NewConn(b.conn)
		_ = p4.SetTTL(64)
	}
}

func (b *SmartBind) injectDesktopLikeQuicBurst(novaEp *NovaEndpoint, repeats int) {
	if repeats <= 0 {
		return
	}

	templateB64 := currentFakeQuicPacketB64()
	for i := 0; i < repeats; i++ {
		decoded, err := base64.StdEncoding.DecodeString(templateB64)
		if err != nil || len(decoded) == 0 {
			return
		}

		payload := make([]byte, len(decoded))
		copy(payload, decoded)
		b.sendToEndpoint(payload, novaEp)
	}
}

func (b *SmartBind) resetFlowState(novaEp *NovaEndpoint) {
	key := novaEp.Addr.String()

	b.flowMu.Lock()
	b.flowState[key] = endpointFlowState{}
	b.flowMu.Unlock()
}

func (b *SmartBind) transportBurstPlan(novaEp *NovaEndpoint) (int, int, bool) {
	key := novaEp.Addr.String()
	now := time.Now()

	b.flowMu.Lock()
	defer b.flowMu.Unlock()

	state := b.flowState[key]
	if state.dataBursts < 6 {
		state.dataBursts++
		state.lastBurst = now
		b.flowState[key] = state
		return 2, 6, true
	}

	if now.Sub(state.lastBurst) >= 4*time.Second {
		state.lastBurst = now
		b.flowState[key] = state
		return 1, 3, true
	}

	b.flowState[key] = state
	return 0, 0, false
}

func isDesktopWarpPort(novaEp *NovaEndpoint) bool {
	if novaEp == nil || novaEp.Addr == nil {
		return false
	}
	_, ok := desktopWarpPorts[novaEp.Addr.Port]
	return ok
}

func (b *SmartBind) BatchSize() int {
	return 1
}

func (b *SmartBind) sendToEndpoint(data []byte, novaEp *NovaEndpoint) {
	if novaEp.Addr.IP.To4() != nil {
		if b.conn != nil {
			b.conn.WriteToUDP(data, novaEp.Addr)
		}
	} else {
		if b.v6conn != nil {
			b.v6conn.WriteToUDP(data, novaEp.Addr)
		} else if b.conn != nil {
			b.conn.WriteToUDP(data, novaEp.Addr)
		}
	}
}

func (b *SmartBind) sendToEndpointWithTTL(data []byte, novaEp *NovaEndpoint, ttl int) {
	if len(data) == 0 || novaEp == nil || novaEp.Addr == nil {
		return
	}

	if ttl > 0 && novaEp.Addr.IP.To4() != nil && b.conn != nil {
		p4 := ipv4.NewConn(b.conn)
		if err := p4.SetTTL(ttl); err == nil {
			b.sendToEndpoint(data, novaEp)
			_ = p4.SetTTL(64)
			return
		}
	}

	b.sendToEndpoint(data, novaEp)
}

func buildAmneziaQuicPayload() []byte {
	decoded, err := base64.StdEncoding.DecodeString(currentFakeQuicPacketB64())
	if err != nil || len(decoded) == 0 {
		return nil
	}
	payload := make([]byte, len(decoded))
	copy(payload, decoded)
	if len(payload) > 14 {
		_, _ = rand.Read(payload[6:14])
	}
	return payload
}

func buildAmneziaDnsPayload() []byte {
	return []byte{
		0x13, 0x37, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00,
		0x00, 0x00, 0x00, 0x00, 0x03, 'w', 'w', 'w',
		0x06, 'g', 'o', 'o', 'g', 'l', 'e',
		0x03, 'c', 'o', 'm', 0x00, 0x00, 0x01, 0x00, 0x01,
	}
}

func buildAmneziaHttpGetPayload() []byte {
	return []byte(
		fmt.Sprintf(
			"GET %s HTTP/1.1\r\nHost: %s\r\nUser-Agent: okhttp/4.12.0\r\nAccept: */*\r\nConnection: keep-alive\r\n\r\n",
			currentFakeHttpPath(),
			currentFakeTemplateHost(),
		),
	)
}

func buildAmneziaHttp2PrefacePayload() []byte {
	return []byte("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n")
}

func buildAmneziaSipInvitePayload() []byte {
	return []byte(
		"INVITE sip:bob@biloxi.com SIP/2.0\r\n" +
			"Via: SIP/2.0/UDP pc33.atlanta.com;branch=z9hG4bK776asdhds\r\n" +
			"From: Alice <sip:alice@atlanta.com>\r\n" +
			"To: Bob <sip:bob@biloxi.com>\r\n\r\n",
	)
}

func buildAmneziaSipReplyPayload() []byte {
	return []byte(
		"SIP/2.0 100 Trying\r\n" +
			"Via: SIP/2.0/UDP pc33.atlanta.com;branch=z9hG4bK776asdhds\r\n" +
			"Content-Length: 0\r\n\r\n",
	)
}

func buildAwgSipInvitePayload() []byte {
	return []byte(
		"INVITE sip:bob@biloxi.com SIP/2.0\r\n" +
			"Via: SIP/2.0/UDP pc33.atlanta.com;branch=z9hG4bK776asdhds\r\n" +
			"Max-Forwards: 70\r\n" +
			"To: Bob <sip:bob@biloxi.com>\r\n" +
			"From: Alice <sip:alice@atlanta.com>;tag=1928301774\r\n" +
			"Call-ID: a84b4c76e66710@pc33.atlanta.com\r\n" +
			"CSeq: 314159 INVITE\r\n" +
			"Contact: <sip:alice@pc33.atlanta.com>\r\n" +
			"Content-Type: application/sdp\r\n" +
			"Content-Length: 0\r\n\r\n",
	)
}

func buildAwgSipReplyPayload() []byte {
	return []byte(
		"SIP/2.0 100 Trying\r\n" +
			"Via: SIP/2.0/UDP pc33.atlanta.com;branch=z9hG4bK776asdhds\r\n" +
			"To: Bob <sip:bob@biloxi.com>\r\n" +
			"From: Alice <sip:alice@atlanta.com>;tag=1928301774\r\n" +
			"Call-ID: a84b4c76e66710@pc33.atlanta.com\r\n" +
			"CSeq: 314159 INVITE\r\n" +
			"Content-Length: 0\r\n\r\n",
	)
}

func amneziaTemplatesForProfile(templateSet string) [][]byte {
	switch templateSet {
	case "awg-exact":
		return compactAmneziaPayloads(
			buildAwgSipInvitePayload(),
			buildAwgSipReplyPayload(),
		)
	case "awg-dnsmix":
		return compactAmneziaPayloads(
			buildAwgSipInvitePayload(),
			buildAmneziaDnsPayload(),
			buildAwgSipReplyPayload(),
			buildAmneziaDnsPayload(),
		)
	case "awg-quicmix":
		return compactAmneziaPayloads(
			buildAwgSipInvitePayload(),
			buildAmneziaQuicPayload(),
			buildAwgSipReplyPayload(),
			buildAmneziaDnsPayload(),
		)
	case "awg-chat":
		return compactAmneziaPayloads(
			buildAwgSipInvitePayload(),
			buildAmneziaHttpGetPayload(),
			buildAmneziaDnsPayload(),
			buildAmneziaQuicPayload(),
			buildAwgSipReplyPayload(),
			buildAmneziaDnsPayload(),
			buildAmneziaHttp2PrefacePayload(),
		)
	case "awg-chatstealth":
		return compactAmneziaPayloads(
			buildAwgSipInvitePayload(),
			buildAmneziaHttpGetPayload(),
			buildAmneziaDnsPayload(),
			buildAmneziaQuicPayload(),
			buildAmneziaHttp2PrefacePayload(),
			buildAwgSipReplyPayload(),
			buildAmneziaDnsPayload(),
			buildAmneziaQuicPayload(),
			buildAmneziaHttpGetPayload(),
			buildAmneziaSipInvitePayload(),
		)
	case "awg":
		return compactAmneziaPayloads(
			buildAwgSipInvitePayload(),
			buildAwgSipReplyPayload(),
		)
	default:
		return compactAmneziaPayloads(
			buildAmneziaQuicPayload(),
			buildAmneziaDnsPayload(),
			buildAmneziaSipInvitePayload(),
			buildAmneziaSipReplyPayload(),
		)
	}
}

func compactAmneziaPayloads(payloads ...[]byte) [][]byte {
	compacted := make([][]byte, 0, len(payloads))
	for _, payload := range payloads {
		if len(payload) == 0 {
			continue
		}
		compacted = append(compacted, payload)
	}
	return compacted
}

func buildAmneziaRandomJunk(size int) []byte {
	if size < 40 {
		size = 40
	}
	payload := make([]byte, size)
	_, _ = rand.Read(payload)
	if len(payload) >= 4 {
		payload[0] = 0xc3
		payload[1] = 0x00
		payload[2] = 0x00
		payload[3] = 0x00
	}
	return payload
}

func randomIntBetween(minValue int, maxValue int) int {
	if maxValue <= minValue {
		return minValue
	}
	var randomByte [1]byte
	if _, err := rand.Read(randomByte[:]); err != nil {
		return minValue
	}
	return minValue + int(randomByte[0])%(maxValue-minValue+1)
}

// --- NovaEndpoint Implementation ---
type NovaEndpoint struct {
	Addr *net.UDPAddr
}

func (e *NovaEndpoint) ClearSrc() {}

func (e *NovaEndpoint) DstIP() netip.Addr {
	addr, _ := netip.AddrFromSlice(e.Addr.IP)
	return addr
}

func (e *NovaEndpoint) SrcIP() netip.Addr {
	return netip.Addr{}
}

func (e *NovaEndpoint) DstToBytes() []byte {
	if ip4 := e.Addr.IP.To4(); ip4 != nil {
		return ip4
	}
	return e.Addr.IP
}

func (e *NovaEndpoint) DstToString() string {
	return e.Addr.String()
}

func (e *NovaEndpoint) SrcToString() string {
	return ""
}

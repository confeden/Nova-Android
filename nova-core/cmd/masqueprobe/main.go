// Command masqueprobe отвечает на один вопрос: обслуживает ли служба MASQUE
// бесплатный анонимный аккаунт Cloudflare.
//
// Регистрирует новое устройство, ставит ему ключ MASQUE и доводит соединение до
// ответа на запрос туннеля. Датаплейн не поднимается, TUN не нужен.
//
// Проба намеренно идёт по коду upstream-клиента usque, а не по нашему: нужен ответ
// про саму службу, без нашей обфускации и наших списков адресов. Запуск с машины
// разработчика:
//
//	cd nova-core && go run ./cmd/masqueprobe
package main

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"flag"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"time"

	"crypto/tls"
	connectip "github.com/Diniboy1123/connect-ip-go"
	usqueapi "github.com/Diniboy1123/usque/api"
	usquemodels "github.com/Diniboy1123/usque/models"
	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"github.com/yosida95/uritemplate/v3"

	novaengine "nova-core/engine"
)

// Три помощника скопированы из usque/internal — межмодульно тот пакет недоступен.
// Значения обязаны совпадать с upstream: проба и нужна, чтобы говорить его голосом.

func generateEcKeyPair() ([]byte, []byte, error) {
	privKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}
	marshalledPrivKey, err := x509.MarshalECPrivateKey(privKey)
	if err != nil {
		return nil, nil, err
	}
	marshalledPubKey, err := x509.MarshalPKIXPublicKey(&privKey.PublicKey)
	if err != nil {
		return nil, nil, err
	}
	return marshalledPrivKey, marshalledPubKey, nil
}

func generateCert(privKey *ecdsa.PrivateKey) ([][]byte, error) {
	cert, err := x509.CreateCertificate(rand.Reader, &x509.Certificate{
		SerialNumber: big.NewInt(0),
		NotBefore:    time.Now(),
		NotAfter:     time.Now().Add(24 * time.Hour),
	}, &x509.Certificate{}, &privKey.PublicKey, privKey)
	if err != nil {
		return nil, err
	}
	return [][]byte{cert}, nil
}

// maxIdleTimeout повторяет параметр приложения, если задан флагом.
//
// Наш движок ставит MaxIdleTimeout=90s, эталон usque — нет. Значение уезжает в
// transport parameters, то есть сервер видит его до всякого HTTP/3: подходящая форма
// различия для «QUIC и SETTINGS в порядке, а CONNECT-IP без ответа».
var maxIdleTimeout time.Duration

func defaultQuicConfig(keepalivePeriod time.Duration, initialPacketSize uint16) *quic.Config {
	cfg := &quic.Config{
		EnableDatagrams:   true,
		InitialPacketSize: initialPacketSize,
		KeepAlivePeriod:   keepalivePeriod,
	}
	if maxIdleTimeout > 0 {
		cfg.MaxIdleTimeout = maxIdleTimeout
	}
	return cfg
}

const (
	connectSNI = "zt-masque.cloudflareclient.com"
	connectURI = "https://cloudflareaccess.com"

	// Порт по умолчанию у upstream usque (cmd/socks.go:239).
	defaultConnectPort = 443
)

func main() {
	endpointFlag := flag.String("endpoint", "", "endpoint override host:port; по умолчанию берётся из ответа Cloudflare")
	sniFlag := flag.String("sni", connectSNI, "SNI для рукопожатия")
	dnsFlag := flag.String("dns", "1.1.1.1:53", "резолвер; в adb shell своего нет")
	outFlag := flag.String("out", "", "сохранить личность в файл и выйти без рукопожатия")
	inFlag := flag.String("in", "", "взять личность из файла и делать только рукопожатие")
	activateFlag := flag.Bool("activate", true, "включить WARP на устройстве после enroll")
	sweepFlag := flag.String("sweep", "", "перебрать порты через запятую и напечатать таблицу")
	sweepTimeoutFlag := flag.Duration("sweep-timeout", 6*time.Second, "срок одной попытки в переборе")
	cidFlag := flag.Int("cid", 0, "длина Connection ID: 0 — как у эталона, 20 — как в приложении")
	wrapFlag := flag.Bool("wrap", false, "обернуть сокет счётчиком, как делает приложение")
	modelFlag := flag.String("model", "PC", "поле model при регистрации; приложение шлёт Build.MODEL")
	localeFlag := flag.String("locale", "en-US", "поле locale при регистрации; приложение шлёт локаль системы")
	ipv4Flag := flag.Bool("ipv4", true, "ходить в API Cloudflare только по IPv4: через туннель IPv6-путь часто молчит")
	ourEnrollFlag := flag.Bool("our-enroll", false, "выпускать ключ нашим EnsureMasqueConfig вместо usque EnrollKey")
	plainFlag := flag.Bool("plain", false, "выпускать ключ обычным HTTPS-запросом, как приложение внутри туннеля, а не обфусцированным транспортом")
	appConfigFlag := flag.String("app-config", "", "взять ключ из выгрузки приложения (masque_config_dump.json) и делать только рукопожатие")
	accountIDFlag := flag.String("account-id", "", "выпускать ключ на готовом устройстве вместо регистрации нового")
	accountTokenFlag := flag.String("account-token", "", "токен к -account-id")
	idleFlag := flag.Duration("idle", 0, "MaxIdleTimeout в quic.Config; 0 — как у эталона, 90s — как в приложении")
	preprobeFlag := flag.Bool("preprobe", false, "слать пакет с неизвестной версией QUIC перед рукопожатием, как приложение")
	flag.Parse()
	preprobeSocket = *preprobeFlag

	if *ipv4Flag {
		base, _ := http.DefaultTransport.(*http.Transport)
		tr := base.Clone()
		tr.DialContext = func(ctx context.Context, _ string, addr string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "tcp4", addr)
		}
		http.DefaultTransport = tr
		http.DefaultClient = &http.Client{Transport: tr}
	}
	connectionIDLength = *cidFlag
	wrapSocket = *wrapFlag
	maxIdleTimeout = *idleFlag
	if wrapSocket && connectionIDLength == 0 {
		// Обёртка живёт только на пути с собственным quic.Transport — как в приложении.
		connectionIDLength = 20
	}

	log.SetFlags(log.Ltime)

	// В adb shell нет /etc/resolv.conf, и чистый Go-резолвер идёт в [::1]:53.
	if strings.TrimSpace(*dnsFlag) != "" {
		net.DefaultResolver = &net.Resolver{
			PreferGo: true,
			Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, network, strings.TrimSpace(*dnsFlag))
			},
		}
	}

	// Ключ, выпущенный приложением, проверяем этим же инструментом.
	//
	// Без этого «ключ приложения не обслуживается» и «набор в приложении сломан» —
	// неразличимые объяснения одного и того же молчания. Формат профиля у нас свой,
	// поэтому переводим его в формат пробы тем же преобразователем, что и при -our-enroll.
	// Выгрузку делает служба: `am start -a DUMP_MASQUE_CONFIG … --ez include_secrets true`.
	if strings.TrimSpace(*appConfigFlag) != "" {
		raw, err := os.ReadFile(strings.TrimSpace(*appConfigFlag))
		if err != nil {
			log.Fatalf("не удалось прочитать выгрузку приложения: %v", err)
		}
		saved, err := identityFromOurConfig(string(raw))
		if err != nil {
			log.Fatalf("не разобрать выгрузку приложения: %v", err)
		}
		if len(saved.PrivKeyDER) == 0 || strings.TrimSpace(saved.PeerPubPEM) == "" {
			log.Fatalf("в выгрузке нет приватного ключа или ключа узла — сделана ли она с include_secrets?")
		}
		log.Printf("ключ приложения прочитан: endpoint_v4=%s ports=%v", saved.EndpointV4, saved.Ports)
		if strings.TrimSpace(*sweepFlag) != "" {
			sweepPorts(saved, *endpointFlag, *sniFlag, *sweepFlag, *sweepTimeoutFlag)
			return
		}
		endpoint, err := endpointFromParts(*endpointFlag, saved.EndpointV4, saved.EndpointV6)
		if err != nil {
			log.Fatalf("не удалось выбрать endpoint: %v", err)
		}
		log.Printf("рукопожатие MASQUE на %s (sni=%s)…", endpoint, *sniFlag)
		status, err := handshake(saved.PrivKeyDER, saved.PeerPubPEM, *sniFlag, endpoint)
		if err != nil {
			log.Printf("    РЕЗУЛЬТАТ: рукопожатие не прошло: %v", err)
			os.Exit(1)
		}
		log.Printf("    РЕЗУЛЬТАТ: запрос туннеля вернул %s", status)
		return
	}

	if strings.TrimSpace(*inFlag) != "" {
		saved, err := loadIdentity(strings.TrimSpace(*inFlag))
		if err != nil {
			log.Fatalf("не удалось прочитать личность: %v", err)
		}
		if strings.TrimSpace(*sweepFlag) != "" {
			sweepPorts(saved, *endpointFlag, *sniFlag, *sweepFlag, *sweepTimeoutFlag)
			return
		}
		endpoint, err := endpointFromParts(*endpointFlag, saved.EndpointV4, saved.EndpointV6)
		if err != nil {
			log.Fatalf("не удалось выбрать endpoint: %v", err)
		}
		log.Printf("рукопожатие MASQUE на %s (sni=%s)…", endpoint, *sniFlag)
		status, err := handshake(saved.PrivKeyDER, saved.PeerPubPEM, *sniFlag, endpoint)
		if err != nil {
			log.Printf("    РЕЗУЛЬТАТ: рукопожатие не прошло: %v", err)
			os.Exit(1)
		}
		log.Printf("    РЕЗУЛЬТАТ: запрос туннеля вернул %s", status)
		if strings.HasPrefix(status, "2") {
			log.Printf("ВЫВОД: бесплатный аккаунт обслуживается — лицензия WARP+ не требуется.")
			os.Exit(0)
		}
		os.Exit(1)
	}

	// Готовое устройство вместо нового.
	//
	// Нужно, чтобы сравнить выпуск приложения и выпуск пробы на ОДНОМ аккаунте: пока
	// аккаунты разные, у различия всегда остаётся запасное объяснение.
	var account usquemodels.AccountData
	if strings.TrimSpace(*accountIDFlag) != "" && strings.TrimSpace(*accountTokenFlag) != "" {
		account = usquemodels.AccountData{
			ID:    strings.TrimSpace(*accountIDFlag),
			Token: strings.TrimSpace(*accountTokenFlag),
		}
		log.Printf("1/3 регистрацию пропускаем, берём готовое устройство id=%s…", truncate(account.ID, 8))
	} else {
		log.Printf("1/3 анонимная регистрация без лицензии (model=%q locale=%q)…", *modelFlag, *localeFlag)
		registered, err := usqueapi.Register(*modelFlag, *localeFlag, "", true)
		if err != nil {
			log.Fatalf("регистрация не прошла: %v", err)
		}
		account = registered
		log.Printf(
			"    id=%s… account_type=%q warp_enabled=%v premium=%d",
			truncate(account.ID, 8), account.Account.AccountType, account.WarpEnabled, account.Account.PremiumData,
		)
		// Полные значения нужны, чтобы подсунуть эту личность приложению и проверить,
		// в регистрации ли дело. Это одноразовый анонимный аккаунт.
		log.Printf("    ACCOUNT device_id=%s", account.ID)
		log.Printf("    ACCOUNT token=%s", account.Token)
	}

	if *ourEnrollFlag {
		// Приложение внутри туннеля выпускает ключ обычным запросом: имя узла провайдеру
		// не видно, и обфускация там считалась ненужной. Флаг позволяет повторить именно
		// этот путь и сравнить выданный ключ с обфусцированным — в остальном всё то же.
		if *plainFlag {
			novaengine.SetPlainCloudflareAPIPreferredInternal(true)
			defer novaengine.SetPlainCloudflareAPIPreferredInternal(false)
			log.Printf("    enroll идёт обычным HTTPS-запросом (как в приложении)")
		}
		log.Printf("2/3 enroll НАШИМ кодом (EnsureMasqueConfig)…")
		cfgJSON, err := novaengine.EnsureMasqueConfig("", account.Token, account.ID, "Nova Android")
		if err != nil {
			log.Fatalf("наш enroll не прошёл: %v", err)
		}
		saved, err := identityFromOurConfig(cfgJSON)
		if err != nil {
			log.Fatalf("не разобрать наш профиль: %v", err)
		}
		log.Printf("    наш профиль получен: endpoint_v4=%s ports=%v", saved.EndpointV4, saved.Ports)
		if strings.TrimSpace(*outFlag) != "" {
			raw, _ := json.Marshal(saved)
			if err := os.WriteFile(strings.TrimSpace(*outFlag), raw, 0o600); err != nil {
				log.Fatalf("не сохранить профиль: %v", err)
			}
			log.Printf("личность сохранена в %s — рукопожатие пропускаем", *outFlag)
			os.Exit(0)
		}
		return
	}

	log.Printf("2/3 enroll ключа MASQUE…")
	privKey, pubKey, err := generateEcKeyPair()
	if err != nil {
		log.Fatalf("не удалось сгенерировать пару ключей: %v", err)
	}
	enrolled, apiErr, err := usqueapi.EnrollKey(account, pubKey, "Nova Android")
	if err != nil {
		if apiErr != nil {
			log.Fatalf("enroll не прошёл: %v (%s)", err, apiErr.ErrorsAsString("; "))
		}
		log.Fatalf("enroll не прошёл: %v", err)
	}
	log.Printf(
		"    tunnel_type=%q key_type=%q warp_enabled=%v waitlist=%v peers=%d",
		enrolled.TunType, enrolled.KeyType, enrolled.WarpEnabled, enrolled.Waitlist, len(enrolled.Config.Peers),
	)
	if len(enrolled.Config.Peers) == 0 {
		log.Fatalf("в ответе enroll нет ни одного peer — подключаться некуда")
	}
	for i, peer := range enrolled.Config.Peers {
		log.Printf("    peer[%d] v4=%s v6=%s", i, peer.Endpoint.V4, peer.Endpoint.V6)
	}

	if *activateFlag && !enrolled.WarpEnabled {
		log.Printf("    warp_enabled=false — включаем WARP на устройстве…")
		activated, err := activateWarp(enrolled)
		if err != nil {
			log.Printf("    включить не удалось: %v", err)
		} else {
			log.Printf("    после включения warp_enabled=%v account_type=%q", activated.WarpEnabled, activated.Account.AccountType)
			if len(activated.Config.Peers) > 0 {
				enrolled = activated
			} else {
				enrolled.WarpEnabled = activated.WarpEnabled
			}
		}
	}

	if strings.TrimSpace(*outFlag) != "" {
		if err := saveIdentity(strings.TrimSpace(*outFlag), privKey, enrolled); err != nil {
			log.Fatalf("не удалось сохранить личность: %v", err)
		}
		log.Printf("личность сохранена в %s — рукопожатие пропускаем", *outFlag)
		os.Exit(0)
	}

	endpoint, err := resolveEndpoint(*endpointFlag, enrolled)
	if err != nil {
		log.Fatalf("не удалось выбрать endpoint: %v", err)
	}
	log.Printf("3/3 рукопожатие MASQUE на %s (sni=%s)…", endpoint, *sniFlag)

	status, err := probeHandshake(privKey, enrolled, *sniFlag, endpoint)
	if err != nil {
		log.Printf("    РЕЗУЛЬТАТ: рукопожатие не прошло: %v", err)
		os.Exit(1)
	}
	log.Printf("    РЕЗУЛЬТАТ: запрос туннеля вернул %s", status)
	if strings.HasPrefix(status, "2") {
		log.Printf("ВЫВОД: бесплатный аккаунт обслуживается — лицензия WARP+ не требуется.")
		os.Exit(0)
	}
	os.Exit(1)
}

func probeHandshake(
	privKeyDER []byte,
	account usquemodels.AccountData,
	sni string,
	endpoint *net.UDPAddr,
) (string, error) {
	return handshake(privKeyDER, account.Config.Peers[0].PublicKey, sni, endpoint)
}

func handshake(
	privKeyDER []byte,
	peerPubPEM string,
	sni string,
	endpoint *net.UDPAddr,
) (string, error) {
	return handshakeWithTimeout(privKeyDER, peerPubPEM, sni, endpoint, 25*time.Second)
}

func handshakeWithTimeout(
	privKeyDER []byte,
	peerPubPEM string,
	sni string,
	endpoint *net.UDPAddr,
	timeout time.Duration,
) (string, error) {
	privKey, err := x509.ParseECPrivateKey(privKeyDER)
	if err != nil {
		return "", fmt.Errorf("разбор приватного ключа: %w", err)
	}
	// Cloudflare отдаёт ключ узла в PEM, а не «голым» base64 — как и читает usque
	// (config/config.go:104).
	block, _ := pem.Decode([]byte(peerPubPEM))
	if block == nil {
		return "", fmt.Errorf("публичный ключ узла не в PEM")
	}
	peerPubAny, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return "", fmt.Errorf("разбор публичного ключа узла: %w", err)
	}
	peerPub, ok := peerPubAny.(*ecdsa.PublicKey)
	if !ok {
		return "", fmt.Errorf("публичный ключ узла не ecdsa")
	}
	cert, err := generateCert(privKey)
	if err != nil {
		return "", fmt.Errorf("сборка сертификата: %w", err)
	}

	tlsConfig, err := usqueapi.PrepareTlsConfig(privKey, peerPub, cert, sni)
	if err != nil {
		return "", fmt.Errorf("подготовка TLS: %w", err)
	}

	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	if connectionIDLength > 0 {
		return handshakeWithCustomCID(tlsConfig, endpoint, ctx)
	}
	udpConn, tr, ipConn, rsp, err := usqueapi.ConnectTunnel(
		ctx,
		tlsConfig,
		defaultQuicConfig(5*time.Second, 1242),
		connectURI,
		endpoint,
	)
	if err != nil {
		return "", err
	}
	defer func() {
		if ipConn != nil {
			ipConn.Close()
		}
		if tr != nil {
			tr.Close()
		}
		if udpConn != nil {
			udpConn.Close()
		}
	}()
	return rsp.Status, nil
}

func resolveEndpoint(override string, account usquemodels.AccountData) (*net.UDPAddr, error) {
	if strings.TrimSpace(override) != "" {
		host, portRaw, err := net.SplitHostPort(strings.TrimSpace(override))
		if err != nil {
			return nil, err
		}
		port, err := strconv.Atoi(portRaw)
		if err != nil {
			return nil, err
		}
		ip := net.ParseIP(strings.Trim(host, "[]"))
		if ip == nil {
			return nil, fmt.Errorf("endpoint должен быть IP-адресом, получено %q", host)
		}
		return &net.UDPAddr{IP: ip, Port: port}, nil
	}
	raw := strings.TrimSpace(account.Config.Peers[0].Endpoint.V4)
	if raw == "" {
		raw = strings.TrimSpace(account.Config.Peers[0].Endpoint.V6)
	}
	if raw == "" {
		return nil, fmt.Errorf("Cloudflare не вернул endpoint")
	}
	// Сервер отдаёт адрес с портом «:0» — порт выбирает клиент. usque по умолчанию
	// берёт 443 (cmd/socks.go:239) и просто отрезает два последних символа
	// (cmd/register.go:97).
	host, _, err := net.SplitHostPort(raw)
	if err != nil {
		return nil, fmt.Errorf("endpoint %q: %w", raw, err)
	}
	ip := net.ParseIP(strings.Trim(host, "[]"))
	if ip == nil {
		return nil, fmt.Errorf("endpoint %q не IP-адрес", raw)
	}
	return &net.UDPAddr{IP: ip, Port: defaultConnectPort}, nil
}

func truncate(value string, limit int) string {
	if len(value) <= limit {
		return value
	}
	return value[:limit] + "…"
}

type savedIdentity struct {
	PrivKeyDER []byte `json:"priv_key_der"`
	PeerPubPEM string `json:"peer_pub_pem"`
	EndpointV4 string `json:"endpoint_v4"`
	EndpointV6 string `json:"endpoint_v6"`
	Ports      []int  `json:"ports,omitempty"`
}

func saveIdentity(path string, privKeyDER []byte, account usquemodels.AccountData) error {
	payload := savedIdentity{
		PrivKeyDER: privKeyDER,
		PeerPubPEM: account.Config.Peers[0].PublicKey,
		EndpointV4: account.Config.Peers[0].Endpoint.V4,
		EndpointV6: account.Config.Peers[0].Endpoint.V6,
	}
	raw, err := json.Marshal(payload)
	if err != nil {
		return err
	}
	return os.WriteFile(path, raw, 0o600)
}

func loadIdentity(path string) (savedIdentity, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return savedIdentity{}, err
	}
	var payload savedIdentity
	if err := json.Unmarshal(raw, &payload); err != nil {
		return savedIdentity{}, err
	}
	return payload, nil
}

func endpointFromParts(override string, v4 string, v6 string) (*net.UDPAddr, error) {
	account := usquemodels.AccountData{}
	account.Config.Peers = []usquemodels.Peer{{}}
	account.Config.Peers[0].Endpoint.V4 = v4
	account.Config.Peers[0].Endpoint.V6 = v6
	return resolveEndpoint(override, account)
}

// activateWarp включает WARP на только что зарегистрированном устройстве.
//
// Свежая регистрация приходит с warp_enabled=false. Имя поля важно: на {"warp": true}
// сервер отвечает 200 и молча игнорирует, работает {"warp_enabled": true} — так же
// сделано в nova-core/engine/masque.go activateWarpOnDevice.
func activateWarp(account usquemodels.AccountData) (usquemodels.AccountData, error) {
	body, err := json.Marshal(map[string]bool{"warp_enabled": true})
	if err != nil {
		return usquemodels.AccountData{}, err
	}
	req, err := http.NewRequest(
		http.MethodPatch,
		"https://api.cloudflareclient.com/v0a4471/reg/"+account.ID,
		bytes.NewReader(body),
	)
	if err != nil {
		return usquemodels.AccountData{}, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "okhttp/3.12.1")
	req.Header.Set("CF-Client-Version", "a-6.30-3596")
	req.Header.Set("Authorization", "Bearer "+account.Token)

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return usquemodels.AccountData{}, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return usquemodels.AccountData{}, err
	}
	if resp.StatusCode != http.StatusOK {
		return usquemodels.AccountData{}, fmt.Errorf("%s: %s", resp.Status, truncate(string(raw), 200))
	}
	var updated usquemodels.AccountData
	if err := json.Unmarshal(raw, &updated); err != nil {
		return usquemodels.AccountData{}, err
	}
	return updated, nil
}

// sweepPorts перебирает порты по одному и печатает таблицу «порт — ответ».
//
// Последовательно, а не параллельно: параллельные QUIC-рукопожатия с одного адреса
// на разные порты сервер может обслуживать иначе, а нужен воспроизводимый ответ про
// каждый порт в отдельности.
func sweepPorts(saved savedIdentity, endpointOverride string, sni string, portList string, timeout time.Duration) {
	host := strings.TrimSpace(endpointOverride)
	if host == "" {
		raw := strings.TrimSpace(saved.EndpointV4)
		if raw == "" {
			raw = strings.TrimSpace(saved.EndpointV6)
		}
		h, _, err := net.SplitHostPort(raw)
		if err != nil {
			log.Fatalf("не разобрать endpoint %q: %v", raw, err)
		}
		host = h
	}
	ip := net.ParseIP(strings.Trim(host, "[]"))
	if ip == nil {
		log.Fatalf("endpoint должен быть IP-адресом, получено %q", host)
	}

	ok := make([]int, 0, 16)
	for _, raw := range strings.Split(portList, ",") {
		raw = strings.TrimSpace(raw)
		if raw == "" {
			continue
		}
		port, err := strconv.Atoi(raw)
		if err != nil || port < 1 || port > 65535 {
			log.Printf("%-6s пропущен: не порт", raw)
			continue
		}
		status, err := handshakeWithTimeout(
			saved.PrivKeyDER, saved.PeerPubPEM, sni,
			&net.UDPAddr{IP: ip, Port: port}, timeout,
		)
		if err != nil {
			log.Printf("%-6d — %s", port, shortError(err))
			continue
		}
		log.Printf("%-6d %s", port, status)
		if strings.HasPrefix(status, "2") {
			ok = append(ok, port)
		}
	}
	log.Printf("ИТОГ %s: рабочие порты %v", ip, ok)
}

func shortError(err error) string {
	msg := err.Error()
	switch {
	case strings.Contains(msg, "access denied"):
		return "tls: access denied"
	case strings.Contains(msg, "no recent network activity"):
		return "молчит (QUIC не отвечает)"
	case strings.Contains(msg, "deadline exceeded"):
		return "молчит (истёк срок)"
	case strings.Contains(msg, "PROTOCOL_VIOLATION"):
		return "PROTOCOL_VIOLATION"
	}
	return truncate(msg, 80)
}

// connectionIDLength повторяет настройку приложения: 0 — как у эталона usque.
var connectionIDLength int

// handshakeWithCustomCID повторяет путь приложения: свой quic.Transport с заданной
// длиной Connection ID вместо quic.Dial с длиной по умолчанию.
func handshakeWithCustomCID(
	tlsConfig *tls.Config,
	endpoint *net.UDPAddr,
	ctx context.Context,
) (string, error) {
	localAddr := &net.UDPAddr{Port: 0}
	if endpoint.IP.To4() == nil {
		localAddr.IP = net.IPv6zero
	} else {
		localAddr.IP = net.IPv4zero
	}
	udpConn, err := net.ListenUDP("udp", localAddr)
	if err != nil {
		return "", err
	}
	defer udpConn.Close()

	if preprobeSocket {
		probeSocketReachability(udpConn, endpoint)
	}
	var packetConn net.PacketConn = udpConn
	if wrapSocket {
		packetConn = &countingPacketConn{PacketConn: udpConn}
	}
	transport := &quic.Transport{Conn: packetConn, ConnectionIDLength: connectionIDLength}
	defer transport.Close()

	conn, err := transport.Dial(ctx, endpoint, tlsConfig, defaultQuicConfig(5*time.Second, 1242))
	if err != nil {
		return "", fmt.Errorf("failed to dial MASQUE QUIC: %w", err)
	}

	tr := &http3.Transport{
		EnableDatagrams:    true,
		AdditionalSettings: map[uint64]uint64{0x276: 1},
		DisableCompression: true,
	}
	defer tr.Close()
	hconn := tr.NewClientConn(conn)
	template := uritemplate.MustNew(connectURI)
	ipConn, rsp, err := connectip.Dial(
		ctx, hconn, template, "cf-connect-ip",
		http.Header{"User-Agent": []string{""}}, true,
	)
	if err != nil {
		return "", fmt.Errorf("failed to dial connect-ip: %w", err)
	}
	defer ipConn.Close()
	return rsp.Status, nil
}

// wrapSocket повторяет обёртку приложения (trackedPacketConn): голый *net.UDPConn
// включает в quic-go оптимизированный путь с recvmmsg и OOB, а любая обёртка над
// net.PacketConn его отключает. Проверяем, не в этом ли различие с эталоном.
var wrapSocket bool

type countingPacketConn struct {
	net.PacketConn
	sent     int64
	received int64
}

func (c *countingPacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	n, err := c.PacketConn.WriteTo(p, addr)
	c.sent++
	return n, err
}

func (c *countingPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, addr, err := c.PacketConn.ReadFrom(p)
	if n > 0 {
		c.received++
	}
	return n, addr, err
}

// identityFromOurConfig переводит профиль нашего ядра в формат пробы.
//
// Ключ у нас лежит base64 от DER, адрес — без порта: сервер порт не назначает.
func identityFromOurConfig(cfgJSON string) (savedIdentity, error) {
	var cfg struct {
		PrivateKey  string `json:"private_key"`
		EndpointV4  string `json:"endpoint_v4"`
		EndpointV6  string `json:"endpoint_v6"`
		EndpointPub string `json:"endpoint_pub_key"`
		Ports       []int  `json:"ports"`
	}
	if err := json.Unmarshal([]byte(cfgJSON), &cfg); err != nil {
		return savedIdentity{}, err
	}
	der, err := base64.StdEncoding.DecodeString(cfg.PrivateKey)
	if err != nil {
		return savedIdentity{}, err
	}
	withPort := func(host string) string {
		host = strings.TrimSpace(host)
		if host == "" {
			return ""
		}
		if strings.Contains(host, ":") && !strings.HasPrefix(host, "[") {
			return "[" + host + "]:0"
		}
		return host + ":0"
	}
	return savedIdentity{
		PrivKeyDER: der,
		PeerPubPEM: cfg.EndpointPub,
		EndpointV4: withPort(cfg.EndpointV4),
		EndpointV6: withPort(cfg.EndpointV6),
		Ports:      cfg.Ports,
	}, nil
}

// preprobeSocket повторяет probeMasqueSocketReachability из нашего ядра: пакет с
// заведомо неизвестной версией QUIC с того же сокета, что и будущее рукопожатие.
var preprobeSocket bool

func probeSocketReachability(udpConn *net.UDPConn, endpoint *net.UDPAddr) {
	pkt := make([]byte, 1200)
	pkt[0] = 0xC0
	pkt[1], pkt[2], pkt[3], pkt[4] = 0x1a, 0x2a, 0x3a, 0x4b
	pkt[5] = 8
	copy(pkt[6:14], []byte{1, 2, 3, 4, 5, 6, 7, 8})
	pkt[14] = 8
	copy(pkt[15:23], []byte{9, 10, 11, 12, 13, 14, 15, 16})

	started := time.Now()
	if _, err := udpConn.WriteToUDP(pkt, endpoint); err != nil {
		log.Printf("       preprobe: отправка не удалась: %v", err)
		return
	}
	_ = udpConn.SetReadDeadline(time.Now().Add(600 * time.Millisecond))
	defer udpConn.SetReadDeadline(time.Time{})
	buf := make([]byte, 1500)
	n, _, err := udpConn.ReadFromUDP(buf)
	if err != nil {
		log.Printf("       preprobe: ответа нет за %s", time.Since(started).Round(time.Millisecond))
		return
	}
	log.Printf("       preprobe: ответ %d байт за %s", n, time.Since(started).Round(time.Millisecond))
}

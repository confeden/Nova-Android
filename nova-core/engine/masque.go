package nova

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"log"
	"math/big"
	"net"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	connectip "github.com/Diniboy1123/connect-ip-go"
	usqueapi "github.com/Diniboy1123/usque/api"
	usquemodels "github.com/Diniboy1123/usque/models"
	wgtun "github.com/amnezia-vpn/amneziawg-go/tun"
	"github.com/quic-go/quic-go"
	"github.com/quic-go/quic-go/http3"
	"github.com/yosida95/uritemplate/v3"
)

const (
	masqueConnectSNI = "zt-masque.cloudflareclient.com"
	masqueConnectURI = "https://cloudflareaccess.com"
	// Бюджет на всё, что идёт после рукопожатия QUIC: SETTINGS и открытие CONNECT-IP.
	//
	// Было 8 секунд, и это ровно вдвое больше того, что отпущено попытке снаружи:
	// watchdog в NovaVpnService бракует MASQUE без трафика примерно за 5.2 секунды от
	// начала попытки. Разница означала не «подождём подольше», а «ядро зависнет молча,
	// а перебор пойдёт дальше по таймауту без единой строки о причине» — именно так
	// выглядел отказ на тестовом устройстве. Уложиться нужно внутрь чужого бюджета, иначе
	// ошибку никто не увидит.
	masqueConnectIPOpenTimeout = 3500 * time.Millisecond
	// Сколько раз просим CONNECT-IP на одном соединении и сколько ждём каждый ответ.
	//
	// Повтор проверен и не помогает: на тестовом устройстве второй запрос не получил ответа ни
	// разу из полутора десятков, зато стоил лишние полторы секунды каждой неудачной
	// попытке и оставлял на соединении ещё один поток с висящим запросом. Молчание
	// сервера здесь означает не потерянный запрос, а отказ обслуживать: удавшийся
	// CONNECT-IP приходил за 29–37 мс.
	//
	// Цикл сохранён — вернуть повтор ничего не стоит, если найдётся сеть, где он нужен.
	// Срок поднят с 1,6 с по замеру 2026-08-12 (cmd/masqueprobe): рабочий порт
	// отвечает «200 OK» быстро, но не мгновенно, и на 1,6 с приложение сдавалось
	// на живом сочетании ключа и порта. Цена ошибки несимметрична: лишняя секунда
	// ожидания против несостоявшегося подключения по выбранному протоколу.
	masqueConnectIPTries        = 1
	masqueConnectIPTryTimeout   = 4500 * time.Millisecond
	masqueKeepAlivePeriod       = 5 * time.Second
	masqueMaxIdleTimeout        = 90 * time.Second
	masqueInitialPacketSize     = 1242
	masqueDeviceNameDefault     = "Nova Android"
	masqueReconnectGraceTimeout = 5 * time.Second
	masqueDesktopFakeRepeats    = 10
)

// Порядок замерен, а не выбран (2026-08-12, тестовый телефон, Россия, Wi-Fi,
// nova-core/cmd/masqueprobe, двенадцать проходов по свежим ключам). Рабочих портов
// много, но в каждый момент отвечают два-четыре из семи, и какие именно — меняется.
// Список отражает частоту ответов: 8095, 8443 и 4443 отвечали почти всегда, 443 —
// один раз за день. Он всё же оставлен: только на 443 ядро включает запасной путь
// поверх TCP.
var defaultMasquePorts = []int{8095, 8443, 4443, 1701, 4500, 500, 443}

type MasqueIdentity struct {
	PrivateKey           string   `json:"private_key"`
	EndpointV4           string   `json:"endpoint_v4,omitempty"`
	EndpointV6           string   `json:"endpoint_v6,omitempty"`
	EndpointV4Candidates []string `json:"endpoint_v4_candidates,omitempty"`
	EndpointV6Candidates []string `json:"endpoint_v6_candidates,omitempty"`
	EndpointPub          string   `json:"endpoint_pub_key"`
	// EndpointHost — авторитетная форма адреса от Cloudflare
	// (`engage.cloudflareclient.com:2408`). Набирать по ней нельзя: MASQUE дозванивается
	// по IP, а имя вне туннеля режется по SNI. Хранится и печатается ради сверки: если
	// выданный нам IP разойдётся с тем, что стоит за этим именем, это будет видно.
	EndpointHost         string   `json:"endpoint_host,omitempty"`
	IPv4                 string   `json:"ipv4"`
	IPv6                 string   `json:"ipv6"`
	Ports                []int    `json:"ports,omitempty"`
	AccessToken          string   `json:"access_token,omitempty"`
	DeviceID             string   `json:"device_id,omitempty"`
	License              string   `json:"license,omitempty"`
	// IssuedAt — момент выпуска ключа, unix-секунды.
	//
	// Замер 2026-08-12 (ROADMAP §3g): ключ перестаёт обслуживаться примерно за двадцать
	// минут, и это единственная переменная, которая объясняла «порты отвечают через раз».
	// Без отметки возраста обработчик «ключ устарел» не может отличить действительно
	// протухший ключ от только что выпущенного и стирает оба — а перевыпуск здесь
	// возможен только через уже поднятый туннель.
	IssuedAt     int64  `json:"issued_at,omitempty"`
	LastEndpoint string `json:"last_endpoint,omitempty"`
	LastPort     int    `json:"last_port,omitempty"`
}

type masqueRuntimeStats struct {
	lastHandshakeTimeSec atomic.Int64
	rxBytes              atomic.Int64
	txBytes              atomic.Int64
}

type masqueTunnel struct {
	cancel context.CancelFunc
	done   chan struct{}

	mu            sync.Mutex
	tunDev        wgtun.Device
	udpConn       *net.UDPConn
	quicTransport *quic.Transport
	tr            *http3.Transport
	ipConn        masquePacketConn
	stats         *masqueRuntimeStats
}

var activeMasque *masqueTunnel
var masqueFakeBurstEnabled atomic.Bool

func init() {
	// Пачка выключена по умолчанию — она измеримо вредна.
	//
	// Замер 2026-08-13, парная проба по пяти портам на одном ключе: с пачкой рукопожатие
	// QUIC не встаёт вовсе («timeout: no recent network activity»), без неё встаёт всегда.
	// Задумывалась она как маскировка под десктопный клиент, но цена — всё соединение.
	// Механизм оставлен за переключателем: если найдётся сеть, где он нужен, включать
	// нечего заново писать.
	masqueFakeBurstEnabled.Store(false)
	masqueAwaitSettingsEnabled.Store(true)
	masqueProtectSocketEnabled.Store(true)
	masqueSocketPreprobeEnabled.Store(false)
}

func SetMasqueFakeBurstEnabled(enabled bool) {
	masqueFakeBurstEnabled.Store(enabled)
}

// masqueAwaitSettingsEnabled — ждать ли SETTINGS отдельным шагом перед CONNECT-IP.
//
// Это последнее структурное различие между нашим набором и эталонной пробой: она шлёт
// запрос сразу, а мы сначала дожидаемся SETTINGS ради различения двух отказов. Ожидание
// пассивное и не должно ни на что влиять — но «не должно» уже трижды оказывалось неверным
// в этой задаче, поэтому шаг сделан выключаемым, чтобы сравнить оба варианта на одном
// ключе в одну минуту.
var masqueAwaitSettingsEnabled atomic.Bool

func SetMasqueAwaitSettingsEnabled(enabled bool) {
	masqueAwaitSettingsEnabled.Store(enabled)
}

// masqueProtectSocketEnabled — помечать ли сокет MASQUE как «мимо своего VPN».
//
// Последнее, чего не повторяет эталонная проба: в shell протектор не устанавливается
// вовсе, а в `:vpn` каждый сокет MASQUE проходит через VpnService.protect(). Выключать
// это в бою нельзя — набор ушёл бы в собственный туннель и получилась бы петля, — но для
// замера нужен именно этот вариант.
var masqueProtectSocketEnabled atomic.Bool

func SetMasqueProtectSocketEnabled(enabled bool) {
	masqueProtectSocketEnabled.Store(enabled)
}

// masqueConnectIPOpenTimeoutOverrideMs — сменный бюджет на SETTINGS плюс CONNECT-IP.
//
// Все отказы приходят ровно на границе штатного бюджета, а исключали его пробой из
// shell — то есть не тем процессом. Меряем изнутри `:vpn`: если с большим сроком ответ
// приходит, дело в бюджете, а не в отказе сервера. 0 — штатное значение.
// masqueSocketPreprobeEnabled — слать ли пакет с неизвестной версией QUIC перед
// рукопожатием. Выключено: см. комментарий у вызова.
var masqueSocketPreprobeEnabled atomic.Bool

func SetMasqueSocketPreprobeEnabled(enabled bool) {
	masqueSocketPreprobeEnabled.Store(enabled)
}

var masqueConnectIPOpenTimeoutOverrideMs atomic.Int64

func SetMasqueConnectIPOpenTimeoutMs(ms int) {
	masqueConnectIPOpenTimeoutOverrideMs.Store(int64(ms))
}

func masqueConnectIPOpenBudget() time.Duration {
	if override := masqueConnectIPOpenTimeoutOverrideMs.Load(); override > 0 {
		return time.Duration(override) * time.Millisecond
	}
	return masqueConnectIPOpenTimeout
}

// Выпуск ключа MASQUE — операция ровно с одним экземпляром на устройство.
//
// Дефект, ради которого это написано (замер 2026-08-13, полный лог обмена enroll):
// приложение выпускало ключ ДВАЖДЫ за один цикл подключения — запрос в 00:07:05
// (ответ через 48 с) и второй, с другим ключом, в 00:08:03. Сервер хранит только
// последний ключ, поэтому конфигурация, сохранённая от первого вызова, мертва в момент
// сохранения. Дальше обработчик «ключ устарел» стирал её, вызывая третий выпуск, — и так
// по кругу. Именно это и выглядело как «свой адрес принимает TLS и молчит на CONNECT-IP».
//
// Источников двойного вызова несколько, и закрывать их поодиночке бесполезно:
// поток перебора и фоновый backfill друг о друге не знают, а таймаут в Kotlin бросает
// ожидание, но не сам вызов — тот доводит PATCH до конца и ротирует ключ уже после того,
// как его результат выброшен. Поэтому защита стоит здесь, в единственной точке выпуска.
//
// Отмена вызова по таймауту рассмотрена и отвергнута: PATCH к этому моменту, как
// правило, уже ушёл, и обрыв ответа даёт ровно то, от чего избавляемся, — ротацию без
// сохранения. Вместо отмены вызов доводится до конца, а его результат кладётся в
// [masqueEnrollMemo], откуда его забирает следующий вызов или Kotlin через
// [LastMasqueEnrollResult].
type masqueEnrollCall struct {
	done chan struct{}
	json string
	err  error
}

type masqueEnrollMemoEntry struct {
	json     string
	issuedAt time.Time
}

const (
	// Сколько ждёт второй вызов, присоединившийся к идущему выпуску. Бюджеты Kotlin —
	// 6–30 с, поэтому ждать дольше бессмысленно: снаружи ожидание уже брошено.
	masqueEnrollJoinTimeout = 45 * time.Second
	// Сколько живёт результат выпуска.
	//
	// Память нужна на масштабе секунд: круг подключения длится около сорока секунд, а
	// брошенный по таймауту выпуск доходил до конца за 48. Четыре минуты — с запасом.
	//
	// Верхняя граница жёсткая и связана с приложением: там ключ старше пяти минут
	// считается устаревшим и стирается. Если память переживёт этот порог, стирание
	// перестанет работать — ближайший вызов получит из памяти тот же ключ с той же
	// отметкой выпуска, приложение снова признает его старым, и получится прежний вечный
	// двигатель, просто на шаг ниже. Значение обязано оставаться меньше
	// MASQUE_KEY_FRESH_WINDOW_MS в NovaVpnService.
	masqueEnrollMemoTTL = 4 * time.Minute
)

var (
	masqueEnrollMu       sync.Mutex
	masqueEnrollInflight = make(map[string]*masqueEnrollCall)
	masqueEnrollMemo     = make(map[string]masqueEnrollMemoEntry)
)

func EnsureMasqueConfig(existingConfigJSON string, accessToken string, deviceID string, deviceName string) (string, error) {
	accessToken = strings.TrimSpace(accessToken)
	deviceID = strings.TrimSpace(deviceID)

	if cfg, ok := parseMasqueIdentity(existingConfigJSON); ok {
		// Сверяем устройство, а не только разбираемость.
		//
		// Готовый конфиг возвращается без запроса к серверу — на этом и держится
		// идемпотентность. Но если он выпущен для ДРУГОГО устройства (так бывает, когда
		// личность перевыпустили, а старый конфиг ещё лежит в настройках), то молча
		// вернуть его значит выдать ключ от чужой записи. Расхождение — повод выпустить
		// ключ заново, и это единственный случай, когда непустой конфиг не переиспользуется.
		configDeviceID := strings.TrimSpace(cfg.DeviceID)
		if configDeviceID == "" || deviceID == "" || configDeviceID == deviceID {
			normalized, err := json.Marshal(cfg)
			if err == nil {
				return string(normalized), nil
			}
		} else {
			log.Printf(
				"MASQUE enroll: сохранённый ключ выпущен для другого устройства (%s вместо %s). Выпускаем заново.",
				truncateForLog(configDeviceID, 12), truncateForLog(deviceID, 12),
			)
		}
	}

	if accessToken == "" || deviceID == "" {
		return "", errors.New("MASQUE requires a saved WARP access token and device id")
	}
	if strings.TrimSpace(deviceName) == "" {
		deviceName = masqueDeviceNameDefault
	}

	return ensureMasqueConfigOnce(accessToken, deviceID, deviceName)
}

// ensureMasqueConfigOnce пропускает к выпуску ключа ровно один вызов на устройство.
func ensureMasqueConfigOnce(accessToken string, deviceID string, deviceName string) (string, error) {
	masqueEnrollMu.Lock()
	if entry, ok := masqueEnrollMemo[deviceID]; ok && entry.json != "" {
		if age := time.Since(entry.issuedAt); age < masqueEnrollMemoTTL {
			masqueEnrollMu.Unlock()
			log.Printf(
				"MASQUE enroll: ключ уже выпущен %.0f с назад — отдаём его. Второй выпуск отобрал бы ключ у первого.",
				age.Seconds(),
			)
			return entry.json, nil
		}
		delete(masqueEnrollMemo, deviceID)
	}
	if call, ok := masqueEnrollInflight[deviceID]; ok {
		masqueEnrollMu.Unlock()
		log.Printf("MASQUE enroll: выпуск уже идёт, ждём его результат вместо второго запроса.")
		select {
		case <-call.done:
			return call.json, call.err
		case <-time.After(masqueEnrollJoinTimeout):
			return "", errors.New(
				"MASQUE enroll уже выполняется; второй выпуск не запускаем, чтобы не отобрать ключ у первого",
			)
		}
	}

	call := &masqueEnrollCall{done: make(chan struct{})}
	masqueEnrollInflight[deviceID] = call
	masqueEnrollMu.Unlock()

	call.json, call.err = performMasqueEnroll(accessToken, deviceID, deviceName)

	masqueEnrollMu.Lock()
	delete(masqueEnrollInflight, deviceID)
	if call.err == nil && call.json != "" {
		masqueEnrollMemo[deviceID] = masqueEnrollMemoEntry{json: call.json, issuedAt: time.Now()}
	}
	masqueEnrollMu.Unlock()
	close(call.done)

	return call.json, call.err
}

// LastMasqueEnrollResult отдаёт результат последнего выпуска, не делая запросов.
//
// Нужен ровно для одного случая: ожидание в Kotlin истекло, вызов Go доведён до конца, и
// его ключ иначе был бы потерян вместе с ротацией на сервере. Пустая строка означает
// «выпуска не было либо он не удался», и это не ошибка.
func LastMasqueEnrollResult(deviceID string) string {
	deviceID = strings.TrimSpace(deviceID)
	if deviceID == "" {
		return ""
	}
	masqueEnrollMu.Lock()
	defer masqueEnrollMu.Unlock()
	entry, ok := masqueEnrollMemo[deviceID]
	if !ok || entry.json == "" || time.Since(entry.issuedAt) >= masqueEnrollMemoTTL {
		return ""
	}
	return entry.json
}

// ForgetMasqueEnrollResult убирает запомненный ключ.
//
// Вызывается там, где приложение сознательно требует именно свежий ключ (обновление
// личности после серии неудачных попыток). Без этого memo молча превратил бы такое
// обновление в возврат того же самого ключа.
func ForgetMasqueEnrollResult(deviceID string) {
	deviceID = strings.TrimSpace(deviceID)
	masqueEnrollMu.Lock()
	defer masqueEnrollMu.Unlock()
	if deviceID == "" {
		masqueEnrollMemo = make(map[string]masqueEnrollMemoEntry)
		return
	}
	delete(masqueEnrollMemo, deviceID)
}

func performMasqueEnroll(accessToken string, deviceID string, deviceName string) (string, error) {
	privKeyBytes, pubKeyBytes, err := generateMasqueKeyPair()
	if err != nil {
		return "", fmt.Errorf("failed to generate MASQUE key pair: %w", err)
	}

	accountData := usquemodels.AccountData{
		Token: accessToken,
		ID:    deviceID,
	}

	updatedAccountData, apiErr, err := enrollMasqueKey(accountData, pubKeyBytes, deviceName)
	if err != nil {
		if apiErr != nil {
			return "", fmt.Errorf("failed to enroll MASQUE key: %w (%s)", err, apiErr.ErrorsAsString("; "))
		}
		return "", fmt.Errorf("failed to enroll MASQUE key: %w", err)
	}

	// Ответ без peers — не повод потерять уже выпущенный ключ.
	//
	// К этому месту PATCH прошёл, и ключ на сервере УЖЕ заменён. Прежний код возвращал
	// здесь ошибку, приложение выбрасывало результат — и приватная половина только что
	// выпущенного ключа исчезала навсегда, а сервер оставался с публичной. Свежая
	// регистрация штатно приходит с `warp_enabled: false` и без peers, так что это был не
	// редкий сбой, а обычный путь первого подключения. Дочитываем запись устройства
	// отдельным запросом вместо того, чтобы сдаваться.
	if len(updatedAccountData.Config.Peers) == 0 {
		log.Printf("MASQUE enroll: ответ пришёл без peers. Ключ уже выпущен, дочитываем запись устройства.")
		if fetched, ferr := fetchMasqueDeviceRecord(accountData); ferr != nil {
			log.Printf("MASQUE enroll: дочитать запись устройства не удалось: %v", ferr)
		} else if len(fetched.Config.Peers) > 0 {
			log.Printf("MASQUE enroll: запись устройства дала peers=%d.", len(fetched.Config.Peers))
			updatedAccountData = fetched
		}
	}

	cfg, err := buildMasqueIdentity(updatedAccountData, privKeyBytes, accessToken)
	if err != nil {
		return "", err
	}

	encoded, err := json.Marshal(cfg)
	if err != nil {
		return "", fmt.Errorf("failed to encode MASQUE config: %w", err)
	}

	return string(encoded), nil
}

// fetchMasqueDeviceRecord читает запись устройства целиком.
//
// Тот же путь, что и enroll, только GET и без тела: правки не вносим, поэтому ключ
// повторно не ротируется.
func fetchMasqueDeviceRecord(accountData usquemodels.AccountData) (usquemodels.AccountData, error) {
	resp, err := doCloudflareAPIRequest(cloudflareAPIRequest{
		label:     "masque-device",
		method:    http.MethodGet,
		path:      warpRegistrationPath + "/" + strings.TrimSpace(accountData.ID),
		authToken: strings.TrimSpace(accountData.Token),
	})
	if err != nil {
		return usquemodels.AccountData{}, err
	}
	if resp.StatusCode != http.StatusOK {
		return usquemodels.AccountData{}, fmt.Errorf(
			"%s: %s", resp.Status, truncateForLog(string(resp.Body), 200),
		)
	}
	var fetched usquemodels.AccountData
	if err := json.Unmarshal(resp.Body, &fetched); err != nil {
		return usquemodels.AccountData{}, err
	}
	return fetched, nil
}

func enrollMasqueKey(accountData usquemodels.AccountData, pubKey []byte, deviceName string) (usquemodels.AccountData, *usquemodels.APIError, error) {
	if strings.TrimSpace(accountData.Token) == "" || strings.TrimSpace(accountData.ID) == "" {
		return usquemodels.AccountData{}, nil, errors.New("MASQUE enrollment requires access token and device id")
	}

	deviceUpdate := usquemodels.DeviceUpdate{
		Key:     base64.StdEncoding.EncodeToString(pubKey),
		KeyType: "secp256r1",
		TunType: "masque",
	}
	if strings.TrimSpace(deviceName) != "" {
		deviceUpdate.Name = strings.TrimSpace(deviceName)
	}

	jsonData, err := json.Marshal(deviceUpdate)
	if err != nil {
		return usquemodels.AccountData{}, nil, fmt.Errorf("failed to marshal MASQUE enroll request: %w", err)
	}
	logMasqueEnrollExchange("запрос", jsonData)

	resp, err := doCloudflareAPIRequest(cloudflareAPIRequest{
		label:     "masque-enroll",
		method:    http.MethodPatch,
		path:      warpRegistrationPath + "/" + strings.TrimSpace(accountData.ID),
		body:      jsonData,
		authToken: strings.TrimSpace(accountData.Token),
	})
	if err != nil {
		return usquemodels.AccountData{}, nil, fmt.Errorf("failed to send request: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		var apiErr usquemodels.APIError
		if err := json.Unmarshal(resp.Body, &apiErr); err != nil {
			return usquemodels.AccountData{}, nil, fmt.Errorf(
				"failed to parse error response: %w (status=%s body=%s)",
				err,
				resp.Status,
				truncateForLog(string(resp.Body), 200),
			)
		}
		return usquemodels.AccountData{}, &apiErr, fmt.Errorf("failed to update: %s", resp.Status)
	}

	logMasqueEnrollExchange("ответ", resp.Body)

	var updatedAccount usquemodels.AccountData
	if err := json.Unmarshal(resp.Body, &updatedAccount); err != nil {
		return usquemodels.AccountData{}, nil, fmt.Errorf("failed to decode MASQUE enroll response: %w", err)
	}

	// Печатаем состояние устройства, а не только «ключ принят».
	//
	// «HTTP 200 OK» на enroll говорит лишь о том, что запись обновлена, — а служба MASQUE
	// потом отвечала `tls: access denied`, и понять по логу было нечего. Эти поля
	// показывают, в каком состоянии Cloudflare держит устройство: включён ли на нём WARP,
	// какой тип аккаунта, не стоит ли оно в листе ожидания.
	log.Printf(
		"MASQUE enroll: устройство type=%q tunnel=%q key_type=%q warp_enabled=%v enabled=%v "+
			"account=%q waitlist=%v peers=%d",
		updatedAccount.Type, updatedAccount.TunType, updatedAccount.KeyType,
		updatedAccount.WarpEnabled, updatedAccount.Enabled, updatedAccount.Account.AccountType,
		updatedAccount.Waitlist, len(updatedAccount.Config.Peers),
	)

	logMasqueDeviceShape("enroll", resp.Body)
	logMasquePolicyHints(resp.Body)

	// Свежая регистрация приходит с выключенным WARP — включаем.
	//
	// Имя поля выяснено замером, а не догадкой: `{"warp": true}` сервер принимает с
	// «HTTP 200 OK» и молча игнорирует — флаг в ответе остаётся false. Работает
	// `{"warp_enabled": true}`. Поэтому смотрим на ответ, а не на код возврата.
	if !updatedAccount.WarpEnabled {
		activated, body, err := activateWarpOnDevice(accountData, "warp_enabled")
		if err != nil {
			log.Printf("MASQUE enroll: включить WARP на устройстве не удалось: %v", err)
		} else {
			logMasqueDeviceShape("activate", body)
			log.Printf(
				"MASQUE enroll: WARP на устройстве включён — warp_enabled=%v enabled=%v",
				activated.WarpEnabled, activated.Enabled,
			)
			if activated.WarpEnabled && len(activated.Config.Peers) > 0 {
				updatedAccount = activated
			}
		}
	}

	return updatedAccount, nil, nil
}

// logMasqueDeviceShape печатает, какие поля Cloudflare вообще прислал.
//
// Нужно, чтобы отличить «сервер сказал false» от «сервера про это поле не сказал вовсе»:
// в модели устройства флаги помечены omitempty, и оба случая читаются как false. Значения
// печатаем только у флагов; всё остальное — только имена полей, без содержимого.
func logMasqueDeviceShape(stage string, body []byte) {
	var raw map[string]json.RawMessage
	if err := json.Unmarshal(body, &raw); err != nil {
		return
	}
	names := make([]string, 0, len(raw))
	for name := range raw {
		names = append(names, name)
	}
	sort.Strings(names)

	flags := make([]string, 0, 4)
	for _, name := range []string{"warp_enabled", "enabled", "waitlist_enabled", "type"} {
		if value, ok := raw[name]; ok {
			flags = append(flags, fmt.Sprintf("%s=%s", name, string(value)))
		}
	}

	log.Printf(
		"MASQUE %s: поля устройства [%s]; %s",
		stage, strings.Join(names, " "), strings.Join(flags, " "),
	)
}

// activateWarpOnDevice включает WARP на устройстве.
//
// Свежая регистрация приходит с `warp_enabled: false`: зарегистрированное устройство ещё
// не обслуживается. Именно так выглядел отказ MASQUE — ключ принят по API, а служба
// закрывает соединение алертом `access denied`.
func activateWarpOnDevice(
	accountData usquemodels.AccountData,
	field string,
) (usquemodels.AccountData, []byte, error) {
	body, err := json.Marshal(map[string]bool{field: true})
	if err != nil {
		return usquemodels.AccountData{}, nil, err
	}

	resp, err := doCloudflareAPIRequest(cloudflareAPIRequest{
		label:     "warp-activate",
		method:    http.MethodPatch,
		path:      warpRegistrationPath + "/" + strings.TrimSpace(accountData.ID),
		body:      body,
		authToken: strings.TrimSpace(accountData.Token),
	})
	if err != nil {
		return usquemodels.AccountData{}, nil, err
	}
	if resp.StatusCode != http.StatusOK {
		return usquemodels.AccountData{}, nil, fmt.Errorf(
			"%s: %s", resp.Status, truncateForLog(string(resp.Body), 200),
		)
	}

	var activated usquemodels.AccountData
	if err := json.Unmarshal(resp.Body, &activated); err != nil {
		return usquemodels.AccountData{}, resp.Body, err
	}
	return activated, resp.Body, nil
}

func StartMasque(fd int, identityJSON string, endpointHost string, endpointPort int, sni string) error {
	identity, ok := parseMasqueIdentity(identityJSON)
	if !ok {
		return errors.New("invalid MASQUE config")
	}

	endpointHost = strings.TrimSpace(strings.Trim(endpointHost, "[]"))
	if endpointHost == "" {
		endpointHost = identity.EndpointV4
		if endpointHost == "" {
			endpointHost = identity.EndpointV6
		}
	}
	if endpointHost == "" {
		return errors.New("MASQUE endpoint host is missing")
	}

	if endpointPort <= 0 || endpointPort > 65535 {
		if len(identity.Ports) > 0 {
			endpointPort = identity.Ports[0]
		} else {
			endpointPort = 443
		}
	}

	tunDevice, err := CreateAndroidTUN(fd)
	if err != nil {
		return fmt.Errorf("failed to create MASQUE TUN: %w", err)
	}
	tunNeedsClose := true
	defer func() {
		if tunNeedsClose {
			_ = tunDevice.Close()
		}
	}()

	privKey, peerPubKey, cert, err := prepareMasqueCrypto(identity)
	if err != nil {
		return err
	}

	connectSNI := strings.TrimSpace(sni)
	if connectSNI == "" {
		connectSNI = masqueConnectSNI
	}

	tlsConfig, err := usqueapi.PrepareTlsConfig(privKey, peerPubKey, cert, connectSNI)
	if err != nil {
		return fmt.Errorf("failed to prepare MASQUE TLS config: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	tunnel := &masqueTunnel{
		cancel: cancel,
		done:   make(chan struct{}),
		stats:  &masqueRuntimeStats{},
		tunDev: tunDevice,
	}
	tunNeedsClose = false

	stateMu.Lock()
	activeMasque = tunnel
	stateMu.Unlock()

	defer func() {
		cancel()
		tunnel.closeResources()
		close(tunnel.done)
		stateMu.Lock()
		if activeMasque == tunnel {
			activeMasque = nil
		}
		stateMu.Unlock()
	}()

	log.Printf("Starting MASQUE on endpoint %s:%d (sni=%s)", endpointHost, endpointPort, connectSNI)

	return runMasqueTunnel(ctx, tunnel, tlsConfig, endpointHost, endpointPort, tunDevice, 1280, identity)
}

func StopMasque() {
	stateMu.Lock()
	tunnel := activeMasque
	activeMasque = nil
	stateMu.Unlock()

	if tunnel == nil {
		return
	}

	tunnel.cancel()
	tunnel.closeResources()

	select {
	case <-tunnel.done:
	case <-time.After(masqueReconnectGraceTimeout):
	}
}

func GetMasqueRuntimeStats() string {
	stateMu.Lock()
	tunnel := activeMasque
	stateMu.Unlock()

	if tunnel == nil || tunnel.stats == nil {
		return ""
	}

	return strings.Join([]string{
		"mode=masque",
		"last_handshake_time_sec=" + strconv.FormatInt(tunnel.stats.lastHandshakeTimeSec.Load(), 10),
		"rx_bytes=" + strconv.FormatInt(tunnel.stats.rxBytes.Load(), 10),
		"tx_bytes=" + strconv.FormatInt(tunnel.stats.txBytes.Load(), 10),
	}, "\n") + "\n"
}

func parseMasqueIdentity(raw string) (MasqueIdentity, bool) {
	if strings.TrimSpace(raw) == "" {
		return MasqueIdentity{}, false
	}

	var cfg MasqueIdentity
	if err := json.Unmarshal([]byte(raw), &cfg); err != nil {
		return MasqueIdentity{}, false
	}

	cfg.EndpointV4 = normalizeEndpointHost(cfg.EndpointV4)
	cfg.EndpointV6 = normalizeEndpointHost(cfg.EndpointV6)
	cfg.EndpointV4Candidates = normalizeMasqueEndpointCandidates(cfg.EndpointV4, cfg.EndpointV4Candidates, true)
	cfg.EndpointV6Candidates = normalizeMasqueEndpointCandidates(cfg.EndpointV6, cfg.EndpointV6Candidates, false)
	if cfg.EndpointV4 == "" && len(cfg.EndpointV4Candidates) > 0 {
		cfg.EndpointV4 = cfg.EndpointV4Candidates[0]
	}
	if cfg.EndpointV6 == "" && len(cfg.EndpointV6Candidates) > 0 {
		cfg.EndpointV6 = cfg.EndpointV6Candidates[0]
	}
	cfg.Ports = normalizeMasquePorts(cfg.Ports)

	if strings.TrimSpace(cfg.PrivateKey) == "" || strings.TrimSpace(cfg.EndpointPub) == "" {
		return MasqueIdentity{}, false
	}
	if cfg.EndpointV4 == "" && cfg.EndpointV6 == "" {
		return MasqueIdentity{}, false
	}
	if strings.TrimSpace(cfg.IPv4) == "" && strings.TrimSpace(cfg.IPv6) == "" {
		return MasqueIdentity{}, false
	}

	return cfg, true
}

func buildMasqueIdentity(account usquemodels.AccountData, privateKey []byte, accessToken string) (MasqueIdentity, error) {
	if len(account.Config.Peers) == 0 {
		return MasqueIdentity{}, errors.New("MASQUE config has no peers")
	}

	peer := account.Config.Peers[0]
	cfg := MasqueIdentity{
		PrivateKey:   base64.StdEncoding.EncodeToString(privateKey),
		EndpointV4:   normalizeEndpointHost(peer.Endpoint.V4),
		EndpointV6:   normalizeEndpointHost(peer.Endpoint.V6),
		EndpointPub:  peer.PublicKey,
		EndpointHost: strings.TrimSpace(peer.Endpoint.Host),
		IPv4:         strings.TrimSpace(account.Config.Interface.Addresses.V4),
		IPv6:         strings.TrimSpace(account.Config.Interface.Addresses.V6),
		Ports:        normalizeMasquePorts(peer.Endpoint.Ports),
		AccessToken:  accessToken,
		DeviceID:     strings.TrimSpace(account.ID),
		License:      strings.TrimSpace(account.Account.License),
		IssuedAt:     time.Now().Unix(),
	}
	cfg.EndpointV4Candidates = normalizeMasqueEndpointCandidates(cfg.EndpointV4, nil, true)
	cfg.EndpointV6Candidates = normalizeMasqueEndpointCandidates(cfg.EndpointV6, nil, false)

	if cfg.EndpointV4 == "" && cfg.EndpointV6 == "" {
		return MasqueIdentity{}, errors.New("MASQUE config has no endpoint addresses")
	}

	return cfg, nil
}

func generateMasqueKeyPair() ([]byte, []byte, error) {
	privKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, err
	}

	privKeyBytes, err := x509.MarshalECPrivateKey(privKey)
	if err != nil {
		return nil, nil, err
	}

	pubKeyBytes, err := x509.MarshalPKIXPublicKey(&privKey.PublicKey)
	if err != nil {
		return nil, nil, err
	}

	return privKeyBytes, pubKeyBytes, nil
}

func prepareMasqueCrypto(identity MasqueIdentity) (*ecdsa.PrivateKey, *ecdsa.PublicKey, [][]byte, error) {
	privKeyRaw, err := base64.StdEncoding.DecodeString(identity.PrivateKey)
	if err != nil {
		return nil, nil, nil, fmt.Errorf("failed to decode MASQUE private key: %w", err)
	}

	privKey, err := x509.ParseECPrivateKey(privKeyRaw)
	if err != nil {
		return nil, nil, nil, fmt.Errorf("failed to parse MASQUE private key: %w", err)
	}

	block, _ := pem.Decode([]byte(identity.EndpointPub))
	if block == nil {
		return nil, nil, nil, errors.New("failed to decode MASQUE endpoint public key")
	}

	pubAny, err := x509.ParsePKIXPublicKey(block.Bytes)
	if err != nil {
		return nil, nil, nil, fmt.Errorf("failed to parse MASQUE endpoint public key: %w", err)
	}

	pubKey, ok := pubAny.(*ecdsa.PublicKey)
	if !ok {
		return nil, nil, nil, errors.New("MASQUE endpoint public key is not ECDSA")
	}

	cert, err := generateMasqueCert(privKey)
	if err != nil {
		return nil, nil, nil, fmt.Errorf("failed to generate MASQUE certificate: %w", err)
	}

	return privKey, pubKey, cert, nil
}

// generateMasqueCert повторяет эталон usque вплоть до полей сертификата.
//
// Сертификат самоподписанный и предъявляется Cloudflare Access как удостоверение
// устройства. Отличались две мелочи: серийный номер (у нас — наносекунды, в эталоне —
// ноль) и NotBefore (у нас на час назад, в эталоне — «сейчас»). На вид это ничего не
// значит, но проверять догадки дешевле, когда клиент совпадает с эталоном до последнего
// поля: расхождения тогда остаются только на стороне сервера.
func generateMasqueCert(privKey *ecdsa.PrivateKey) ([][]byte, error) {
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

func normalizeEndpointHost(raw string) string {
	value := strings.TrimSpace(raw)
	if value == "" {
		return ""
	}

	if strings.HasPrefix(value, "[") && strings.Contains(value, "]") {
		end := strings.Index(value, "]")
		return strings.TrimSpace(value[1:end])
	}

	if host, _, err := net.SplitHostPort(value); err == nil {
		return strings.Trim(host, "[]")
	}

	if ip := net.ParseIP(value); ip != nil {
		return value
	}

	if idx := strings.LastIndex(value, ":"); idx > 0 && strings.Count(value, ":") == 1 {
		if _, err := strconv.Atoi(value[idx+1:]); err == nil {
			return strings.TrimSpace(value[:idx])
		}
	}

	return strings.Trim(value, "[]")
}

func normalizeMasquePorts(ports []int) []int {
	ordered := linkedSetInts()
	for _, port := range ports {
		if port > 0 && port <= 65535 {
			ordered.add(port)
		}
	}
	for _, port := range defaultMasquePorts {
		ordered.add(port)
	}
	return ordered.values()
}

func normalizeMasqueEndpointCandidates(primary string, candidates []string, ipv4 bool) []string {
	ordered := make([]string, 0, len(candidates)+3)
	seen := make(map[string]struct{})

	add := func(value string) {
		normalized := normalizeEndpointHost(value)
		if normalized == "" {
			return
		}
		ip := net.ParseIP(normalized)
		if ip == nil {
			return
		}
		if ipv4 && ip.To4() == nil {
			return
		}
		if !ipv4 && ip.To4() != nil {
			return
		}
		if _, ok := seen[normalized]; ok {
			return
		}
		seen[normalized] = struct{}{}
		ordered = append(ordered, normalized)
	}

	add(primary)
	for _, candidate := range candidates {
		add(candidate)
	}
	for _, sibling := range knownMasqueSiblings(ordered, ipv4) {
		add(sibling)
	}
	return ordered
}

func knownMasqueSiblings(candidates []string, ipv4 bool) []string {
	for _, candidate := range candidates {
		if ipv4 {
			if candidate == "162.159.198.1" || candidate == "162.159.198.2" {
				return []string{"162.159.198.1", "162.159.198.2"}
			}
			continue
		}
		if candidate == "2606:4700:103::1" || candidate == "2606:4700:103::2" {
			return []string{"2606:4700:103::1", "2606:4700:103::2"}
		}
	}
	return nil
}

type intSetOrder struct {
	valuesByOrder []int
	seen          map[int]struct{}
}

func linkedSetInts() *intSetOrder {
	return &intSetOrder{seen: make(map[int]struct{})}
}

func (s *intSetOrder) add(value int) {
	if _, ok := s.seen[value]; ok {
		return
	}
	s.seen[value] = struct{}{}
	s.valuesByOrder = append(s.valuesByOrder, value)
}

func (s *intSetOrder) values() []int {
	return append([]int(nil), s.valuesByOrder...)
}

func runMasqueTunnel(
	ctx context.Context,
	tunnel *masqueTunnel,
	tlsConfig *tls.Config,
	endpointHost string,
	endpointPort int,
	device wgtun.Device,
	mtu int,
	identity MasqueIdentity,
) error {
	tunnelDevice := newMasqueTunAdapter(device)

	endpointIP := net.ParseIP(endpointHost)
	if endpointIP == nil {
		return fmt.Errorf("MASQUE endpoint must be an IP address, got %q", endpointHost)
	}

	endpoint := &net.UDPAddr{
		IP:   endpointIP,
		Port: endpointPort,
	}

	udpConn, quicTransport, tr, ipConn, rsp, err := connectMasqueTunnel(ctx, tlsConfig, endpoint)
	if err != nil {
		return err
	}
	defer func() {
		ipConn.Close()
		if udpConn != nil {
			udpConn.Close()
		}
		if tr != nil {
			tr.Close()
		}
		if quicTransport != nil {
			quicTransport.Close()
		}
	}()

	tunnel.mu.Lock()
	tunnel.udpConn = udpConn
	tunnel.quicTransport = quicTransport
	tunnel.tr = tr
	tunnel.ipConn = ipConn
	tunnel.mu.Unlock()

	if rsp.StatusCode/100 != 2 {
		return fmt.Errorf("MASQUE tunnel request failed: %s", rsp.Status)
	}

	log.Printf("MASQUE connected to %s", endpoint.String())
	logMasqueControlPlane(ipConn)
	tunnel.stats.lastHandshakeTimeSec.Store(time.Now().Unix())

	errCh := make(chan error, 2)
	loggedTunPackets := 0
	droppedForeignSource := 0
	allowedV4 := net.ParseIP(strings.TrimSpace(identity.IPv4)).To4()
	allowedV6 := net.ParseIP(strings.TrimSpace(identity.IPv6)).To16()
	log.Printf("MASQUE tun: разрешённый источник v4=%v v6=%v", allowedV4, allowedV6)

	go func() {
		buf := make([]byte, mtu)
		for {
			select {
			case <-ctx.Done():
				errCh <- nil
				return
			default:
			}

			n, err := tunnelDevice.ReadPacket(buf)
			if err != nil {
				errCh <- fmt.Errorf("failed to read from MASQUE TUN: %w", err)
				return
			}
			if n <= 0 {
				continue
			}
			if shouldDropMasqueTunPacket(buf[:n]) {
				continue
			}

			// Пакет с чужим адресом источника в туннель не уходит.
			//
			// Замер 2026-08-14: половина пакетов приходит на TUN с адресом телефона в
			// локальной сети (192.168.0.69) вместо выданного нам 172.16.0.2 — это сокеты,
			// привязанные к Wi-Fi до подъёма интерфейса. RFC 9484 разрешает прокси
			// отбрасывать пакеты, чей источник не совпадает с назначенным, и Cloudflare
			// поступает жёстче: после такого пакета узел замолкает целиком, вместе с
			// подтверждениями, и туннель остаётся поднятым, но мёртвым.
			if !masqueSourceAllowed(buf[:n], allowedV4, allowedV6) {
				droppedForeignSource++
				if droppedForeignSource == 1 || droppedForeignSource%50 == 0 {
					logMasqueForeignSource(buf[:n], droppedForeignSource)
				}
				continue
			}

			// Первые пакеты печатаем разобранными.
			//
			// Туннель поднимается, мы шлём датаграммы, узел не отвечает ничем — а RFC 9484
			// разрешает прокси молча отбрасывать пакеты, чей адрес источника не совпадает с
			// назначенным. С провода этого не увидеть: внутри QUIC всё зашифровано. Пять
			// строк на сессию, дальше молчим.
			if loggedTunPackets < masqueTunPacketLogLimit {
				loggedTunPackets++
				logMasqueTunPacket(buf[:n], loggedTunPackets)
			}

			icmpReply, err := ipConn.WritePacket(buf[:n])
			if err != nil {
				if ctx.Err() != nil {
					errCh <- nil
					return
				}
				if isMasqueConnectionClosed(err) {
					errCh <- fmt.Errorf("failed to write MASQUE packet: %w", err)
					return
				}
				log.Printf("MASQUE write warning: %v", err)
				continue
			}
			tunnel.stats.txBytes.Add(int64(n))

			if len(icmpReply) > 0 {
				if err := tunnelDevice.WritePacket(icmpReply); err == nil {
					tunnel.stats.rxBytes.Add(int64(len(icmpReply)))
				}
			}
		}
	}()

	go func() {
		buf := make([]byte, mtu)
		for {
			select {
			case <-ctx.Done():
				errCh <- nil
				return
			default:
			}

			n, err := ipConn.ReadPacket(buf, true)
			if err != nil {
				if ctx.Err() != nil {
					errCh <- nil
					return
				}
				if isMasqueConnectionClosed(err) {
					errCh <- fmt.Errorf("MASQUE connection closed: %w", err)
					return
				}
				log.Printf("MASQUE read warning: %v", err)
				continue
			}
			if n <= 0 {
				continue
			}

			if err := tunnelDevice.WritePacket(buf[:n]); err != nil {
				if ctx.Err() != nil {
					errCh <- nil
					return
				}
				errCh <- fmt.Errorf("failed to write MASQUE packet to TUN: %w", err)
				return
			}
			tunnel.stats.rxBytes.Add(int64(n))
		}
	}()

	select {
	case <-ctx.Done():
		return nil
	case err := <-errCh:
		return err
	}
}

func logMasqueControlPlane(ipConn masquePacketConn) {
	if ipConn == nil {
		return
	}
	log.Printf("MASQUE control-plane watchers armed")

	go func() {
		for attempt := 1; attempt <= 3; attempt++ {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			prefixes, err := ipConn.LocalPrefixes(ctx)
			cancel()
			if err != nil {
				log.Printf("MASQUE ADDRESS_ASSIGN attempt=%d pending/failed: %v", attempt, err)
				time.Sleep(750 * time.Millisecond)
				continue
			}
			log.Printf("MASQUE ADDRESS_ASSIGN: %v", prefixes)
			return
		}
	}()

	go func() {
		for attempt := 1; attempt <= 3; attempt++ {
			ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
			routes, err := ipConn.Routes(ctx)
			cancel()
			if err != nil {
				log.Printf("MASQUE ROUTE_ADVERTISEMENT attempt=%d pending/failed: %v", attempt, err)
				time.Sleep(750 * time.Millisecond)
				continue
			}
			log.Printf("MASQUE ROUTE_ADVERTISEMENT: %v", routes)
			return
		}
	}()
}

type masqueTunAdapter struct {
	dev       wgtun.Device
	packetBuf [][]byte
	sizes     []int
}

func newMasqueTunAdapter(dev wgtun.Device) *masqueTunAdapter {
	return &masqueTunAdapter{
		dev:       dev,
		packetBuf: make([][]byte, 1),
		sizes:     make([]int, 1),
	}
}

func (a *masqueTunAdapter) ReadPacket(buf []byte) (int, error) {
	a.packetBuf[0] = buf
	a.sizes[0] = 0

	_, err := a.dev.Read(a.packetBuf, a.sizes, 0)
	if err != nil {
		return 0, err
	}

	return a.sizes[0], nil
}

func (a *masqueTunAdapter) WritePacket(pkt []byte) error {
	_, err := a.dev.Write([][]byte{pkt}, 0)
	return err
}

func shouldDropMasqueTunPacket(pkt []byte) bool {
	if len(pkt) < 1 {
		return false
	}

	switch pkt[0] >> 4 {
	case 4:
		if len(pkt) < 20 {
			return false
		}
		return pkt[8] <= 1
	case 6:
		if len(pkt) < 8 {
			return false
		}
		return pkt[7] <= 1
	default:
		return false
	}
}

func connectMasqueTunnel(
	ctx context.Context,
	tlsConfig *tls.Config,
	endpoint *net.UDPAddr,
) (*net.UDPConn, *quic.Transport, *http3.Transport, masquePacketConn, *http.Response, error) {
	if endpoint != nil && endpoint.Port == 443 {
		if ipConn, rsp, err := connectMasqueTunnelTCP(ctx, tlsConfig, endpoint.IP.String(), endpoint.Port); err == nil {
			log.Printf("MASQUE connected over HTTP/2/TCP to %s", endpoint.String())
			return nil, nil, nil, ipConn, rsp, nil
		} else {
			log.Printf("MASQUE HTTP/2/TCP fallback failed for %s: %v", endpoint.String(), err)
		}
	}

	localAddr := &net.UDPAddr{Port: 0}
	if endpoint.IP.To4() == nil {
		localAddr.IP = net.IPv6zero
	} else {
		localAddr.IP = net.IPv4zero
	}

	udpConn, err := net.ListenUDP("udp", localAddr)
	if err != nil {
		return nil, nil, nil, nil, nil, fmt.Errorf("failed to open MASQUE UDP socket: %w", err)
	}

	if masqueProtectSocketEnabled.Load() {
		if err := protectUDPConn(udpConn); err != nil {
			log.Printf("MASQUE socket protect warning: %v", err)
		}
	} else {
		log.Printf("MASQUE: сокет НЕ защищён от своего VPN — это диагностический режим.")
	}

	// Предварительная проба сокета выключена по умолчанию.
	//
	// На проводе (захват 2026-08-14, Mi A1 с root) она видна как пакет 1200 байт с
	// заведомо неизвестной версией QUIC, на который узел отвечает Version Negotiation.
	// Рукопожатие после неё проходит, но сразу после запроса CONNECT-IP узел замолкает
	// целиком — перестаёт даже подтверждать пакеты, и клиент шлёт повторы в пустоту.
	// У эталонной пробы, которая этого пакета не шлёт, обмен идёт до конца. Диагностика,
	// ради которой она задумывалась, теперь есть в захвате, а цена — всё соединение.
	if masqueSocketPreprobeEnabled.Load() {
		probeMasqueSocketReachability(udpConn, endpoint)
	}

	if masqueFakeBurstEnabled.Load() {
		injectMasqueDesktopLikeQuicBurst(udpConn, endpoint, masqueDesktopFakeRepeats)
	}

	// Сокет отдаём quic-go завёрнутым, и это не только ради счётчиков.
	//
	// Замеры на тестовом устройстве: тот же самый сокет за миг до вызова получает ответ от
	// узла за 30 мс, сервер отвечает Retry на настоящий QUIC v1 Initial любого
	// размера — а quic-go сообщает «timeout: no recent network activity», то есть не
	// увидел ни байта. Голый *net.UDPConn включает в quic-go оптимизированный путь
	// с recvmmsg и OOB (ECN, GSO); обёртка его отключает и оставляет обычные
	// ReadFrom/WriteTo. Счётчики показывают, отправлено ли хоть что-то и получено ли
	// в ответ, — без них «тишина» неотличима от «не отправляли».
	tracked := newTrackedPacketConn(udpConn, endpoint)

	// Длину Connection ID задаём явно — 20 байт.
	//
	// Без неё quic-go берёт свою короткую длину по умолчанию, и бэкенд Cloudflare время
	// от времени отвечает PROTOCOL_VIOLATION, закрывая соединение. Это известная беда:
	// в usque её нашли замерами на сорока соединениях — от 20 до 50 процентов отказов, —
	// и лечится она ровно этой строкой (коммит 6aa03fc, 20 июля 2026). Снаружи выглядело
	// как капризная сеть: тот же адрес с тем же ключом то поднимал туннель, то молчал в
	// ответ на CONNECT-IP.
	//
	// Транспорт теперь наш, а не созданный внутри quic.Dial, поэтому закрывать его тоже
	// нам: он держит цикл чтения сокета.
	quicTransport := &quic.Transport{Conn: tracked, ConnectionIDLength: 20}
	conn, err := quicTransport.Dial(ctx, endpoint, tlsConfig, defaultMasqueQuicConfig())
	if err != nil {
		tracked.logSummary("dial failed")
		quicTransport.Close()
		udpConn.Close()
		return nil, nil, nil, nil, nil, fmt.Errorf("failed to dial MASQUE QUIC: %w", err)
	}
	tracked.logSummary("dial ok")
	logMasqueQuicState(conn, endpoint)

	tr := &http3.Transport{
		EnableDatagrams: true,
		AdditionalSettings: map[uint64]uint64{
			0x276: 1,
		},
		DisableCompression: true,
	}

	hconn := tr.NewClientConn(conn)
	template := uritemplate.MustNew(masqueConnectURI)
	additionalHeaders := http.Header{
		"User-Agent": []string{""},
	}

	// Один бюджет на обе оставшиеся ступени, а не по бюджету на каждую: снаружи попытку
	// всё равно судят по общему времени.
	openCtx, openCancel := context.WithTimeout(ctx, masqueConnectIPOpenBudget())
	defer openCancel()

	// Ждём SETTINGS отдельно от connectip.Dial, хотя он ждёт их и сам.
	//
	// Внутри Dial это ожидание неотличимо от любого другого шага: ошибка приходит одна
	// и та же — «контекст истёк». А ступени ломаются по-разному. Молчание до SETTINGS
	// означает, что сервер принял QUIC-соединение, но HTTP/3-сессию открывать не стал;
	// отказ после них — что не понравился сам запрос CONNECT-IP.
	if masqueAwaitSettingsEnabled.Load() {
		if err := awaitMasqueHTTP3Settings(openCtx, hconn, tracked, endpoint); err != nil {
			tr.Close()
			udpConn.Close()
			return nil, nil, nil, nil, nil, err
		}
	}

	connectIPStarted := time.Now()
	ipConn, rsp, err := dialMasqueConnectIP(openCtx, hconn, conn, template, additionalHeaders)
	if err != nil {
		tracked.logSummary("CONNECT-IP не открылся")
		tr.Close()
		udpConn.Close()
		return nil, nil, nil, nil, nil, fmt.Errorf(
			"failed to open CONNECT-IP after %s: %w",
			time.Since(connectIPStarted).Round(time.Millisecond), err,
		)
	}
	log.Printf(
		"MASQUE CONNECT-IP открыт для %s за %s",
		endpoint, time.Since(connectIPStarted).Round(time.Millisecond),
	)

	return udpConn, quicTransport, tr, ipConn, rsp, nil
}

// dialMasqueConnectIP открывает CONNECT-IP, соблюдая срок из ctx и повторяя запрос.
//
// Повтор здесь не для надёжности «на всякий случай». Замеры на тестовом устройстве: сервер
// принимает QUIC, присылает SETTINGS с extended_connect=true — и на сам запрос
// CONNECT-IP молчит, подтверждая при этом наши пакеты на транспортном уровне. Уходит
// такая попытка целиком, вместе с рукопожатием и установкой туннеля, а стоит она
// несколько секунд. Второй запрос — это новый поток на уже готовом соединении, он стоит
// один круг обмена и проверяет ровно одно: молчит ли сервер вообще или молчал на первый
// запрос.
//
// Срок соблюдать приходится самим. connectip.Dial контекст использует только чтобы
// открыть поток, а ответ читает через ReadResponse() без всякого срока: узел, который
// принял запрос и промолчал, держал бы вызов до idle-таймаута QUIC — полторы минуты.
// Так и было: перебор уходил дальше по своему бюджету, а поток движка оставался висеть,
// и в журнале это выглядело как «движок не завершился после stop, принудительно
// пропускаем зависшую конфигурацию» — без единого слова о причине.
func dialMasqueConnectIP(
	ctx context.Context,
	hconn *http3.ClientConn,
	quicConn *quic.Conn,
	template *uritemplate.Template,
	additionalHeaders http.Header,
) (masquePacketConn, *http.Response, error) {
	var lastErr error
	for try := 1; try <= masqueConnectIPTries; try++ {
		tryCtx, cancel := context.WithTimeout(ctx, masqueConnectIPTryTimeout)
		ipConn, rsp, err := dialMasqueConnectIPOnce(tryCtx, hconn, template, additionalHeaders)
		cancel()
		if err == nil {
			if try > 1 {
				log.Printf("MASQUE: CONNECT-IP открылся со второго запроса (попытка %d)", try)
			}
			return ipConn, rsp, nil
		}
		lastErr = err
		if ctx.Err() != nil {
			break
		}
		if try < masqueConnectIPTries {
			log.Printf("MASQUE: на запрос CONNECT-IP ответа нет (%v). Повторяем на новом потоке.", err)
		}
	}

	// Брошенные вызовы всё ещё висят в ReadResponse на своих потоках. Отпустить их можно
	// только закрыв соединение: другого рычага у нас нет.
	if quicConn != nil {
		_ = quicConn.CloseWithError(0, "connect-ip timeout")
	}
	return nil, nil, lastErr
}

func dialMasqueConnectIPOnce(
	ctx context.Context,
	hconn *http3.ClientConn,
	template *uritemplate.Template,
	additionalHeaders http.Header,
) (masquePacketConn, *http.Response, error) {
	type dialResult struct {
		ipConn *connectip.Conn
		rsp    *http.Response
		err    error
	}

	started := time.Now()
	results := make(chan dialResult, 1)
	go func() {
		ipConn, rsp, err := connectip.Dial(ctx, hconn, template, "cf-connect-ip", additionalHeaders, true)
		results <- dialResult{ipConn: ipConn, rsp: rsp, err: err}
	}()

	select {
	case res := <-results:
		if res.err != nil {
			return nil, res.rsp, res.err
		}
		return res.ipConn, res.rsp, nil
	case <-ctx.Done():
		// Опоздавший ответ всё равно приходит — закрываем его, чтобы не оставить
		// открытый туннель, о котором никто не знает.
		go func() {
			res := <-results
			if res.ipConn != nil {
				_ = res.ipConn.Close()
			}
		}()
		return nil, nil, fmt.Errorf(
			"MASQUE: сервер принял запрос CONNECT-IP, но не ответил за %s",
			time.Since(started).Round(time.Millisecond),
		)
	}
}

// logMasqueQuicState печатает то, что известно о соединении сразу после рукопожатия.
//
// Главное здесь — ALPN. Сервер, принявший соединение без «h3», не станет открывать
// HTTP/3-сессию, и снаружи это выглядит точно так же, как отказ авторизации: молчание.
func logMasqueQuicState(conn *quic.Conn, endpoint *net.UDPAddr) {
	if conn == nil {
		return
	}
	state := conn.ConnectionState()
	log.Printf(
		"MASQUE QUIC установлен с %s: alpn=%q version=%s датаграммы=%v 0-RTT=%v",
		endpoint, state.TLS.NegotiatedProtocol, state.Version, state.SupportsDatagrams, state.Used0RTT,
	)
}

// awaitMasqueHTTP3Settings ждёт SETTINGS сервера — первый признак, что HTTP/3-сессия
// вообще началась.
//
// Сервер обязан прислать их сразу после рукопожатия, отдельным управляющим потоком.
// Если их нет, дело не в CONNECT-IP: до запроса ещё не дошло. Счётчики пакетов в этот
// момент разделяют два разных отказа — «мы не отправили ничего» и «отправили, а ответа
// нет».
func awaitMasqueHTTP3Settings(
	ctx context.Context,
	hconn *http3.ClientConn,
	tracked *trackedPacketConn,
	endpoint *net.UDPAddr,
) error {
	started := time.Now()
	select {
	case <-hconn.ReceivedSettings():
		settings := hconn.Settings()
		extendedConnect := false
		datagrams := false
		if settings != nil {
			extendedConnect = settings.EnableExtendedConnect
			datagrams = settings.EnableDatagrams
		}
		log.Printf(
			"MASQUE HTTP/3 SETTINGS от %s за %s: extended_connect=%v датаграммы=%v",
			endpoint, time.Since(started).Round(time.Millisecond), extendedConnect, datagrams,
		)
		return nil
	case <-hconn.Context().Done():
		if tracked != nil {
			tracked.logSummary("QUIC закрыт до SETTINGS")
		}
		return fmt.Errorf(
			"MASQUE: соединение закрылось до HTTP/3 SETTINGS за %s: %w",
			time.Since(started).Round(time.Millisecond), context.Cause(hconn.Context()),
		)
	case <-ctx.Done():
		if tracked != nil {
			tracked.logSummary("SETTINGS не пришли")
		}
		return fmt.Errorf(
			"MASQUE: сервер принял QUIC, но не открыл HTTP/3-сессию за %s "+
				"(SETTINGS не пришли)",
			time.Since(started).Round(time.Millisecond),
		)
	}
}

func isMasqueConnectionClosed(err error) bool {
	if err == nil {
		return false
	}
	var closeErr *connectip.CloseError
	if errors.As(err, &closeErr) {
		return true
	}
	var masqueErr *masqueCloseError
	if errors.As(err, &masqueErr) {
		return true
	}
	return errors.Is(err, net.ErrClosed) || errors.Is(err, io.EOF)
}

func injectMasqueDesktopLikeQuicBurst(
	udpConn *net.UDPConn,
	endpoint *net.UDPAddr,
	repeats int,
) {
	if udpConn == nil || endpoint == nil || repeats <= 0 {
		return
	}

	// Keep MASQUE on the older baseline fake-QUIC burst template.
	// The newer template-selected burst improved camouflage experiments, but on
	// тестовое устройство it regressed the previously working 4443 verified path compared
	// with the 1.9 build. AWG still uses adaptive templates in smart_bind.go.
	decoded, err := base64.StdEncoding.DecodeString(FakeQuicPacketB64)
	if err != nil || len(decoded) == 0 {
		return
	}

	for i := 0; i < repeats; i++ {
		payload := make([]byte, len(decoded))
		copy(payload, decoded)
		if len(payload) > 14 {
			_, _ = rand.Read(payload[6:14])
		}
		if _, err := udpConn.WriteToUDP(payload, endpoint); err != nil {
			log.Printf("MASQUE fake QUIC burst warning: %v", err)
			return
		}
		time.Sleep(15 * time.Millisecond)
	}

	log.Printf("MASQUE fake QUIC burst sent: repeats=%d endpoint=%s", repeats, endpoint.String())
}

func defaultMasqueQuicConfig() *quic.Config {
	return &quic.Config{
		EnableDatagrams:   true,
		InitialPacketSize: masqueInitialPacketSize,
		MaxIdleTimeout:    masqueMaxIdleTimeout,
		KeepAlivePeriod:   masqueKeepAlivePeriod,
	}
}

// trackedPacketConn считает пакеты рукопожатия MASQUE и запоминает первую ошибку.
//
// Обёртка намеренно скрывает от quic-go тип *net.UDPConn: по нему он включает
// recvmmsg с OOB (ECN, GSO), и если этот путь на устройстве не работает, ответы
// теряются молча — снаружи это выглядит как «сервер не отвечает».
type trackedPacketConn struct {
	net.PacketConn
	endpoint *net.UDPAddr
	sent     atomic.Int64
	received atomic.Int64
	bytesOut atomic.Int64
	bytesIn  atomic.Int64
	firstErr atomic.Value
}

func newTrackedPacketConn(conn net.PacketConn, endpoint *net.UDPAddr) *trackedPacketConn {
	return &trackedPacketConn{PacketConn: conn, endpoint: endpoint}
}

func (c *trackedPacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	n, err := c.PacketConn.WriteTo(p, addr)
	c.sent.Add(1)
	c.bytesOut.Add(int64(n))
	if err != nil {
		c.firstErr.CompareAndSwap(nil, fmt.Sprintf("write: %v", err))
	}
	return n, err
}

func (c *trackedPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	n, addr, err := c.PacketConn.ReadFrom(p)
	if n > 0 {
		c.received.Add(1)
		c.bytesIn.Add(int64(n))
	}
	if err != nil {
		c.firstErr.CompareAndSwap(nil, fmt.Sprintf("read: %v", err))
	}
	return n, addr, err
}

func (c *trackedPacketConn) logSummary(stage string) {
	first, _ := c.firstErr.Load().(string)
	if first == "" {
		first = "нет"
	}
	log.Printf(
		"MASQUE handshake io (%s) %s: отправлено %d пакетов / %d байт, получено %d / %d байт, первая ошибка: %s",
		stage, c.endpoint, c.sent.Load(), c.bytesOut.Load(), c.received.Load(), c.bytesIn.Load(), first,
	)
}

// probeMasqueSocketReachability отвечает на единственный вопрос, который иначе не
// отличить: доходит ли UDP до узла с ЭТОГО сокета.
//
// Симптом, ради которого написано: рукопожатие MASQUE падало с «timeout: no recent
// network activity», то есть без единого байта в ответ, — а точно такой же пакет,
// отправленный из shell того же телефона, получал ответ за 38 мс. Разница могла быть
// либо в сокете (защита от собственного VPN), либо в содержимом рукопожатия. Проба
// идёт с уже защищённого сокета, прямо перед quic.Dial, и разделяет эти два случая.
//
// Пакет — Long Header с заведомо неизвестной версией: по RFC 9000 сервер обязан
// ответить Version Negotiation, не создавая никакого состояния. Ответ приходит на
// наши же Connection ID и с настоящим рукопожатием не смешивается.
func probeMasqueSocketReachability(udpConn *net.UDPConn, endpoint *net.UDPAddr) {
	if udpConn == nil || endpoint == nil {
		return
	}
	pkt := make([]byte, 1200)
	pkt[0] = 0xC0
	pkt[1], pkt[2], pkt[3], pkt[4] = 0x1a, 0x2a, 0x3a, 0x4b // заведомо неизвестная версия
	pkt[5] = 8
	copy(pkt[6:14], []byte{1, 2, 3, 4, 5, 6, 7, 8})
	pkt[14] = 8
	copy(pkt[15:23], []byte{9, 10, 11, 12, 13, 14, 15, 16})

	started := time.Now()
	if _, err := udpConn.WriteToUDP(pkt, endpoint); err != nil {
		log.Printf("MASQUE socket probe: отправка не удалась на %s: %v", endpoint, err)
		return
	}
	// Живой узел отвечает за 30–40 мс, поэтому ждать долго незачем: проба идёт внутри
	// бюджета попытки, и каждая её секунда — секунда, отнятая у рукопожатия.
	_ = udpConn.SetReadDeadline(time.Now().Add(600 * time.Millisecond))
	defer udpConn.SetReadDeadline(time.Time{})
	buf := make([]byte, 1500)
	n, from, err := udpConn.ReadFromUDP(buf)
	if err != nil {
		log.Printf(
			"MASQUE socket probe: ответа от %s нет за %s (%v). UDP с этого сокета не доходит.",
			endpoint, time.Since(started).Round(time.Millisecond), err,
		)
		return
	}
	log.Printf(
		"MASQUE socket probe: ответ %d байт от %s за %s. Сокет и сеть в порядке, дело в рукопожатии.",
		n, from, time.Since(started).Round(time.Millisecond),
	)
}

func protectUDPConn(udpConn *net.UDPConn) error {
	// Свой протектор для MASQUE, общий — только как запасной.
	//
	// Общий помечает сокет через VpnService.protect(), и по замеру именно это убивает
	// ответ на CONNECT-IP (см. MasqueProtector). Свой привязывает сокет к нижележащей
	// сети. Запасной путь оставлен, потому что привязка может не удаться — например,
	// когда службе ещё не известна нижележащая сеть, — а совсем без защиты набор ушёл бы
	// в собственный туннель.
	protector := MasqueProtector
	if protector == nil {
		protector = GlobalProtector
	}
	if udpConn == nil || protector == nil {
		return nil
	}

	var protectErr error
	rawConn, err := udpConn.SyscallConn()
	if err != nil {
		return err
	}
	if err := rawConn.Control(func(fd uintptr) {
		if !protector(int(fd)) {
			protectErr = fmt.Errorf("protect(%d) returned false", fd)
		}
	}); err != nil {
		return err
	}
	return protectErr
}

func (t *masqueTunnel) closeResources() {
	t.mu.Lock()
	defer t.mu.Unlock()

	if t.tunDev != nil {
		_ = t.tunDev.Close()
		t.tunDev = nil
	}
	if t.ipConn != nil {
		t.ipConn.Close()
		t.ipConn = nil
	}
	if t.tr != nil {
		t.tr.Close()
		t.tr = nil
	}
	if t.quicTransport != nil {
		t.quicTransport.Close()
		t.quicTransport = nil
	}
	if t.udpConn != nil {
		t.udpConn.Close()
		t.udpConn = nil
	}
}

// logMasquePolicyHints печатает списки маршрутизации из ответа enroll.
//
// Это НЕ подсказка о качестве узлов, и применять её к выбору адреса нельзя. `policy`
// лежит на уровне устройства, рядом с `config`, тогда как выданные нам адреса — внутри
// `config.peers[].endpoint`. Пара `always_exclude`/`always_include` — словарь
// раздельного туннелирования: что идёт мимо туннеля, что загоняется в него. Проверка на
// собственных данных закрывает вопрос: в одном замере `162.159.197.3` оказался в
// `always_exclude`, а соседний `162.159.197.4` — в `always_include`; как признак
// «плохого» адреса это бессмысленно, а как пара маршрутов — осмысленно полностью. Более
// того, инфраструктуру самого туннеля клиент обязан выводить наружу, иначе получается
// петля, — то есть адрес в `always_exclude` скорее признак живого узла, чем битого.
//
// Поэтому здесь только печать. Если списки когда-нибудь понадобятся, они понадобятся как
// маршруты, и решать это будет отдельный замер, а не догадка на месте.
func logMasquePolicyHints(body []byte) {
	var parsed struct {
		Policy struct {
			AlwaysExclude []json.RawMessage `json:"always_exclude"`
			AlwaysInclude []json.RawMessage `json:"always_include"`
			PostQuantum   string            `json:"post_quantum"`
		} `json:"policy"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil {
		return
	}
	if len(parsed.Policy.AlwaysExclude) == 0 &&
		len(parsed.Policy.AlwaysInclude) == 0 &&
		strings.TrimSpace(parsed.Policy.PostQuantum) == "" {
		return
	}

	// Запись бывает и голой строкой, и объектом с полями address/host — печатаем обе формы.
	describe := func(entries []json.RawMessage) string {
		values := make([]string, 0, len(entries))
		for _, entry := range entries {
			var plain string
			if json.Unmarshal(entry, &plain) == nil {
				values = append(values, plain)
				continue
			}
			// Реальная форма записи — `{"ip":"162.159.197.3"}`; `address`/`host`
			// оставлены на случай другой версии API. Первый вариант парсера знал только
			// про них и печатал «—» при непустых списках: пустая строка вместо данных
			// хуже отсутствия строки, потому что читается как «сервер ничего не прислал».
			var object struct {
				IP      string `json:"ip"`
				Address string `json:"address"`
				Host    string `json:"host"`
			}
			if json.Unmarshal(entry, &object) == nil {
				value := object.IP
				if value == "" {
					value = object.Address
				}
				switch {
				case value != "" && object.Host != "":
					values = append(values, value+" ("+object.Host+")")
				case value != "":
					values = append(values, value)
				case object.Host != "":
					values = append(values, object.Host)
				}
				continue
			}
			values = append(values, truncateForLog(string(entry), 60))
		}
		if len(values) == 0 {
			return "—"
		}
		return strings.Join(values, ", ")
	}

	log.Printf(
		"MASQUE policy (только к сведению, на выбор адреса не влияет): always_exclude=[%s] always_include=[%s] post_quantum=%q",
		describe(parsed.Policy.AlwaysExclude),
		describe(parsed.Policy.AlwaysInclude),
		strings.TrimSpace(parsed.Policy.PostQuantum),
	)
}

// logMasqueEnrollExchange печатает обмен enroll целиком, а не сводкой.
//
// Сводные строки («устройство type=… peers=1») оказались слишком грубыми: у
// приложения и у эталонной пробы они совпадали, а ключ обслуживался только у пробы.
// Различие, если оно есть, сидит в полях, которых в сводке нет.
//
// Секреты вырезаются: token — это доступ к аккаунту, а журнал уходит в logcat.
func logMasqueEnrollExchange(stage string, body []byte) {
	if len(body) == 0 {
		return
	}
	var parsed map[string]json.RawMessage
	if err := json.Unmarshal(body, &parsed); err != nil {
		log.Printf("MASQUE enroll %s (не JSON, %d байт): %s", stage, len(body), truncateForLog(string(body), 400))
		return
	}
	for _, secret := range []string{"token", "license"} {
		if raw, ok := parsed[secret]; ok {
			parsed[secret] = json.RawMessage(fmt.Sprintf("%q", fmt.Sprintf("<%d символов>", len(raw))))
		}
	}
	if accountRaw, ok := parsed["account"]; ok {
		var account map[string]json.RawMessage
		if json.Unmarshal(accountRaw, &account) == nil {
			if raw, ok := account["license"]; ok {
				account["license"] = json.RawMessage(fmt.Sprintf("%q", fmt.Sprintf("<%d символов>", len(raw))))
			}
			if repacked, err := json.Marshal(account); err == nil {
				parsed["account"] = repacked
			}
		}
	}
	repacked, err := json.Marshal(parsed)
	if err != nil {
		return
	}
	// logcat режет длинные строки, поэтому пишем кусками по 900 символов.
	text := string(repacked)
	const chunk = 900
	for i := 0; i < len(text); i += chunk {
		end := i + chunk
		if end > len(text) {
			end = len(text)
		}
		log.Printf("MASQUE enroll %s [%d/%d]: %s", stage, i/chunk+1, (len(text)+chunk-1)/chunk, text[i:end])
	}
}

// masqueTunPacketLogLimit — сколько первых пакетов сессии разобрать в журнал.
const masqueTunPacketLogLimit = 5

// logMasqueTunPacket печатает заголовок пакета, который уходит в туннель.
func logMasqueTunPacket(pkt []byte, index int) {
	if len(pkt) < 20 {
		log.Printf("MASQUE tun[%d]: пакет короче заголовка IP, %d байт", index, len(pkt))
		return
	}
	version := pkt[0] >> 4
	switch version {
	case 4:
		src := net.IP(pkt[12:16])
		dst := net.IP(pkt[16:20])
		log.Printf(
			"MASQUE tun[%d]: IPv4 %s -> %s proto=%d длина=%d",
			index, src, dst, pkt[9], len(pkt),
		)
	case 6:
		if len(pkt) < 40 {
			log.Printf("MASQUE tun[%d]: IPv6 короче заголовка, %d байт", index, len(pkt))
			return
		}
		src := net.IP(pkt[8:24])
		dst := net.IP(pkt[24:40])
		log.Printf(
			"MASQUE tun[%d]: IPv6 %s -> %s next=%d длина=%d",
			index, src, dst, pkt[6], len(pkt),
		)
	default:
		log.Printf("MASQUE tun[%d]: неизвестная версия IP %d, %d байт", index, version, len(pkt))
	}
}

// masqueSourceAllowed решает, можно ли отправлять пакет в туннель.
//
// Разрешён только адрес, выданный нам Cloudflare при регистрации. Если адреса нет
// (например, семейство не настроено), пакеты этого семейства не пропускаем: отправить
// их всё равно означает получить молчание на всю сессию.
func masqueSourceAllowed(pkt []byte, allowedV4 net.IP, allowedV6 net.IP) bool {
	if len(pkt) < 20 {
		return false
	}
	switch pkt[0] >> 4 {
	case 4:
		if allowedV4 == nil {
			return false
		}
		return net.IP(pkt[12:16]).Equal(allowedV4)
	case 6:
		if len(pkt) < 40 || allowedV6 == nil {
			return false
		}
		return net.IP(pkt[8:24]).Equal(allowedV6)
	default:
		return false
	}
}

// logMasqueForeignSource объясняет, почему пакет не ушёл. Печатается первый и далее
// каждый пятидесятый: поток таких пакетов бывает плотным, а смысл у них один.
func logMasqueForeignSource(pkt []byte, count int) {
	if len(pkt) < 20 {
		log.Printf("MASQUE tun: отброшен пакет короче заголовка IP (всего %d)", count)
		return
	}
	switch pkt[0] >> 4 {
	case 4:
		log.Printf(
			"MASQUE tun: отброшен пакет с чужим источником %s -> %s (всего %d)",
			net.IP(pkt[12:16]), net.IP(pkt[16:20]), count,
		)
	case 6:
		if len(pkt) >= 40 {
			log.Printf(
				"MASQUE tun: отброшен пакет IPv6 с чужим источником %s -> %s (всего %d)",
				net.IP(pkt[8:24]), net.IP(pkt[24:40]), count,
			)
		}
	default:
		log.Printf("MASQUE tun: отброшен пакет неизвестной версии IP (всего %d)", count)
	}
}

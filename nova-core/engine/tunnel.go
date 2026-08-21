package nova

import (
	"bufio"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"errors"
	"fmt"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	wgconn "github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"
	"github.com/amnezia-vpn/amneziawg-go/tun"
)

var activeDevice *device.Device
var activeTunDevice tun.Device
var stopChan chan struct{}
var stateMu sync.Mutex

// activePeerKey — публичный ключ пира текущего туннеля, нужный ForceHandshake.
// Device.LookupPeer экспортирован, а сама карта пиров — нет, поэтому ключ
// запоминается при подъёме туннеля. Пир у нас всегда ровно один.
var activePeerKey device.NoisePublicKey
var activePeerKeySet bool
var tunReadLogBudget atomic.Int64
var tunWriteLogBudget atomic.Int64
var dnsInterceptLogBudget atomic.Int64
var dnsInterceptState = dnsInterceptConfig{
	timeout: 2500 * time.Millisecond,
}
var dnsInterceptMu sync.RWMutex

type dnsInterceptConfig struct {
	enabled             bool
	mediaUpstreams      []string
	defaultUpstreams    []string
	mediaDomainSuffixes []string
	timeout             time.Duration
}

func SetDNSInterceptConfig(enabled bool, upstreams []string) {
	SetDNSInterceptPolicy(enabled, upstreams, nil, nil)
}

func SetDNSInterceptPolicy(enabled bool, mediaUpstreams []string, defaultUpstreams []string, mediaDomains []string) {
	normalized := make([]string, 0, len(mediaUpstreams))
	seen := make(map[string]struct{}, len(mediaUpstreams))
	for _, upstream := range mediaUpstreams {
		trimmed := strings.TrimSpace(upstream)
		if trimmed == "" {
			continue
		}
		if _, exists := seen[trimmed]; exists {
			continue
		}
		seen[trimmed] = struct{}{}
		normalized = append(normalized, trimmed)
	}
	normalizedDefaults := make([]string, 0, len(defaultUpstreams))
	seenDefaults := make(map[string]struct{}, len(defaultUpstreams))
	for _, upstream := range defaultUpstreams {
		trimmed := strings.TrimSpace(upstream)
		if trimmed == "" {
			continue
		}
		if _, exists := seenDefaults[trimmed]; exists {
			continue
		}
		seenDefaults[trimmed] = struct{}{}
		normalizedDefaults = append(normalizedDefaults, trimmed)
	}
	normalizedDomains := make([]string, 0, len(mediaDomains))
	seenDomains := make(map[string]struct{}, len(mediaDomains))
	for _, domain := range mediaDomains {
		trimmed := strings.Trim(strings.ToLower(strings.TrimSpace(domain)), ".")
		if trimmed == "" {
			continue
		}
		if _, exists := seenDomains[trimmed]; exists {
			continue
		}
		seenDomains[trimmed] = struct{}{}
		normalizedDomains = append(normalizedDomains, trimmed)
	}

	dnsInterceptMu.Lock()
	dnsInterceptState = dnsInterceptConfig{
		enabled:             enabled && len(normalized) > 0,
		mediaUpstreams:      normalized,
		defaultUpstreams:    normalizedDefaults,
		mediaDomainSuffixes: normalizedDomains,
		timeout:             2500 * time.Millisecond,
	}
	dnsInterceptMu.Unlock()
	dnsInterceptLogBudget.Store(12)

	if enabled && len(normalized) > 0 {
		log.Printf(
			"DNS intercept enabled: media=%s fallback=%s domains=%s",
			strings.Join(normalized, ","),
			strings.Join(normalizedDefaults, ","),
			strings.Join(normalizedDomains, ","),
		)
	} else {
		log.Printf("DNS intercept disabled")
	}
}

func getDNSInterceptConfig() dnsInterceptConfig {
	dnsInterceptMu.RLock()
	defer dnsInterceptMu.RUnlock()
	return dnsInterceptConfig{
		enabled:             dnsInterceptState.enabled,
		mediaUpstreams:      append([]string(nil), dnsInterceptState.mediaUpstreams...),
		defaultUpstreams:    append([]string(nil), dnsInterceptState.defaultUpstreams...),
		mediaDomainSuffixes: append([]string(nil), dnsInterceptState.mediaDomainSuffixes...),
		timeout:             dnsInterceptState.timeout,
	}
}

// StartWireGuard initializes the WireGuard tunnel.
func StartWireGuard(fd int, conf string) error {
	// Panic Recovery
	defer func() {
		if r := recover(); r != nil {
			log.Printf("CRITICAL PANIC in StartWireGuard: %v", r)
		}
	}()

	log.Printf("Starting WireGuard with FD: %d", fd)

	// Use our custom AndroidTUN
	tunDevice, err := CreateAndroidTUN(fd)
	if err != nil {
		log.Printf("Failed to create TUN: %v", err)
		return err
	}

	// Create Logger
	logger := device.NewLogger(device.LogLevelVerbose, "NovaCore")

	// Android-aware bind that protects sockets immediately on Open() and
	// injects fake QUIC bursts around handshake and early data packets.
	netBind := NewSmartBind()

	// Create Device
	dev := device.NewDevice(tunDevice, netBind, logger)

	// Convert INI config to UAPI
	uapiConf, err := configToUAPI(conf)
	if err != nil {
		log.Printf("Failed to convert config: %v", err)
		dev.Close()
		return err
	}
	// log.Printf("UAPI Config: %s", uapiConf) // Commented out to reduce spam

	// Configure Device using UAPI format
	err = dev.IpcSet(uapiConf)
	if err != nil {
		log.Printf("Failed to configure device: %v", err)
		dev.Close()
		return err
	}

	// Bring Interface Up. The warp-plus device applies reserved bytes and
	// handshake tricks internally via UAPI keys such as trick/reserved.
	if err := dev.Up(); err != nil {
		log.Printf("Failed to bring interface up: %v", err)
		dev.Close()
		return err
	}

	peerKey, peerKeyOK := parsePeerPublicKeyFromUAPI(uapiConf)

	stateMu.Lock()
	activeDevice = dev
	activeTunDevice = tunDevice
	activePeerKey = peerKey
	activePeerKeySet = peerKeyOK
	stateMu.Unlock()

	if !peerKeyOK {
		// Молчаливое «выключено» в этом проекте запрещено: без ключа
		// ForceHandshake навсегда останется no-op, и это надо видеть.
		log.Printf("Peer public key not recovered from UAPI: forced handshake disabled for this tunnel")
	}

	log.Println("WireGuard started successfully. Entering LOCK state...")

	// BLOCK until StopWireGuard is called
	stateMu.Lock()
	stopChan = make(chan struct{})
	localStopChan := stopChan
	stateMu.Unlock()
	<-localStopChan

	log.Println("WireGuard stop signal received. Exiting via Return...")
	return nil
}

// StopWireGuard terminates the tunnel.
func StopWireGuard() {
	stateMu.Lock()
	localStopChan := stopChan
	stopChan = nil
	dev := activeDevice
	tunDevice := activeTunDevice
	activeDevice = nil
	activeTunDevice = nil
	activePeerKey = device.NoisePublicKey{}
	activePeerKeySet = false
	stateMu.Unlock()

	// First unblock StartWireGuard so Java-side retries are not stuck behind Close().
	if localStopChan != nil {
		close(localStopChan)
		log.Println("WireGuard stop signal issued")
	}

	if dev != nil {
		dev.Close()
		log.Println("WireGuard stopped")
	}
	if tunDevice != nil {
		_ = tunDevice.Close()
	}
}

// parsePeerPublicKeyFromUAPI достаёт `public_key=<hex>` из готового UAPI-текста.
//
// Берём именно из UAPI, а не из исходного INI: к этому моменту ключ уже прошёл
// разбор и перекодирование, то есть проверен ровно так же, как его увидит ядро.
func parsePeerPublicKeyFromUAPI(uapiConf string) (device.NoisePublicKey, bool) {
	var key device.NoisePublicKey
	for _, line := range strings.Split(uapiConf, "\n") {
		value, ok := strings.CutPrefix(strings.TrimSpace(line), "public_key=")
		if !ok {
			continue
		}
		if err := key.FromHex(strings.TrimSpace(value)); err != nil {
			log.Printf("Failed to parse peer public key from UAPI: %v", err)
			return device.NoisePublicKey{}, false
		}
		return key, true
	}
	return device.NoisePublicKey{}, false
}

// ForceHandshake просит ядро начать рукопожатие немедленно.
//
// Зачем. Собственный таймер WireGuard пересобирает сессию только через
// пятнадцать секунд молчания (KeepaliveTimeout 10 с + RekeyTimeout 5 с), и всё
// это время пакеты уходят в никуда: в замере на Mi A1 провал длился 15,3 с, а
// само восстановление после рукопожатия заняло 34 мс. Наблюдение за потоком
// живёт на стороне Android (TunnelStallDetector), сюда приходит уже готовое
// решение.
//
// Безопасность вызова обеспечивает само ядро: SendHandshakeInitiation выходит
// без действия, если предыдущее рукопожатие было отправлено меньше RekeyTimeout
// назад. То есть худший исход лишнего вызова — no-op, а не шторм рукопожатий.
// isRetry=false намеренно: это новая попытка по наблюдению, и счётчик
// handshakeAttempts надо обнулить, иначе можно упереться в MaxTimerHandshakes и
// уронить пира.
//
// Возвращает true, если запрос дошёл до пира. Это не обещание, что пакет ушёл:
// факт отправки печатает само ядро строкой «Sending handshake initiation».
func ForceHandshake() bool {
	stateMu.Lock()
	dev := activeDevice
	key := activePeerKey
	keyOK := activePeerKeySet
	stateMu.Unlock()

	if dev == nil || !keyOK {
		return false
	}
	peer := dev.LookupPeer(key)
	if peer == nil {
		log.Printf("Forced handshake skipped: peer not found")
		return false
	}
	if err := peer.SendHandshakeInitiation(false); err != nil {
		log.Printf("Forced handshake failed: %v", err)
		return false
	}
	return true
}

// SwitchPeerEndpoint переводит живой туннель на другой адрес того же пира.
//
// Почему это бесшовно. У всех встроенных сидов один и тот же внутренний адрес
// `172.16.0.2`, а внутри одной личности совпадают ещё и приватный ключ с
// IPv6-адресом — различается только `Endpoint`. Значит смена узла внутри
// личности не требует ни нового TUN, ни переустановки VpnService: интерфейс,
// его адреса, маршруты и все открытые сокеты приложений остаются на месте.
// Наружу это ровно то же, что штатный роуминг WireGuard при смене сети.
//
// Маршрут на прежний узел не мешает: исходящий UDP-сокет ядра защищён через
// VpnService.protect(), а не выведен из туннеля отдельным маршрутом, поэтому
// новый адрес не нужно ни исключать, ни добавлять.
//
// Рукопожатие после смены обязательно: у нового узла Cloudflare нашей сессии
// нет, старый keypair там не примут. Пробуем сразу; если ядро откажет по
// частоте (меньше RekeyTimeout с прошлого), его собственный таймер добьёт это
// в течение пяти секунд.
func SwitchPeerEndpoint(endpoint string) bool {
	endpoint = strings.TrimSpace(endpoint)
	if endpoint == "" {
		return false
	}

	stateMu.Lock()
	dev := activeDevice
	key := activePeerKey
	keyOK := activePeerKeySet
	stateMu.Unlock()

	if dev == nil || !keyOK {
		return false
	}

	// update_only обязателен: без него UAPI создаст второго пира с тем же
	// ключом вместо правки существующего.
	uapi := fmt.Sprintf("public_key=%s\nupdate_only=true\nendpoint=%s\n",
		hex.EncodeToString(key[:]), endpoint)
	if err := dev.IpcSet(uapi); err != nil {
		log.Printf("Failed to switch peer endpoint to %s: %v", endpoint, err)
		return false
	}
	log.Printf("Peer endpoint switched to %s", endpoint)

	if peer := dev.LookupPeer(key); peer != nil {
		if err := peer.SendHandshakeInitiation(false); err != nil {
			log.Printf("Handshake after endpoint switch failed: %v", err)
		}
	}
	return true
}

func GetWireGuardRuntimeStats() string {
	stateMu.Lock()
	dev := activeDevice
	stateMu.Unlock()

	if dev == nil {
		return ""
	}

	stats, err := dev.IpcGet()
	if err != nil {
		log.Printf("Failed to fetch WireGuard runtime stats: %v", err)
		return ""
	}

	return stats
}

// configToUAPI converts standard WireGuard INI config (Base64 keys) to UAPI (Hex keys).
func configToUAPI(conf string) (string, error) {
	ResetPacketTweaks()

	var sb strings.Builder
	scanner := bufio.NewScanner(strings.NewReader(conf))
	currentSection := ""
	pendingTrick := ""
	reservedEnabled := true
	fakePacketsEnabled := true
	fakeStrategyProfile := "aggressive"

	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" {
			continue
		}

		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			currentSection = strings.ToLower(strings.TrimSuffix(strings.TrimPrefix(line, "["), "]"))
			continue
		}

		parts := strings.SplitN(line, "=", 2)
		if len(parts) != 2 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(parts[0]))
		val := strings.TrimSpace(parts[1])

		switch currentSection {
		case "interface":
			switch key {
			case "privatekey", "publickey", "presharedkey":
				decoded, err := base64.StdEncoding.DecodeString(val)
				if err != nil {
					return "", fmt.Errorf("invalid base64 key: %v", err)
				}
				hexVal := hex.EncodeToString(decoded)
				uapiKey := "private_key"
				if key == "publickey" {
					uapiKey = "public_key"
				} else if key == "presharedkey" {
					uapiKey = "preshared_key"
				}
				sb.WriteString(fmt.Sprintf("%s=%s\n", uapiKey, hexVal))

			case "listenport":
				sb.WriteString(fmt.Sprintf("listen_port=%s\n", val))

			case "reserved":
				reservedBytes, err := parseReservedBytes(val)
				if err != nil {
					return "", err
				}
				SetReservedBytes(reservedBytes)

			case "novamode":
				pendingTrick, reservedEnabled, fakePacketsEnabled, fakeStrategyProfile = mapNovaModeToWarpPlus(val)
				if !reservedEnabled {
					SetReservedMode("off")
				}

			case "novareservedmode":
				SetReservedMode(val)
				if strings.EqualFold(val, "off") {
					reservedEnabled = false
				}

			case "novafakepackets":
				fakePacketsEnabled = strings.EqualFold(val, "true") || val == "1" || strings.EqualFold(val, "on")

			case "novafakehost":
				SetFakeTemplateHintHost(val)

			case "jc":
				if _, err := strconv.Atoi(val); err != nil {
					return "", fmt.Errorf("failed to parse Jc: %w", err)
				}
				MarkAwgCompatActive()
				sb.WriteString(fmt.Sprintf("jc=%s\n", val))

			case "jmin":
				if _, err := strconv.Atoi(val); err != nil {
					return "", fmt.Errorf("failed to parse Jmin: %w", err)
				}
				MarkAwgCompatActive()
				sb.WriteString(fmt.Sprintf("jmin=%s\n", val))

			case "jmax":
				if _, err := strconv.Atoi(val); err != nil {
					return "", fmt.Errorf("failed to parse Jmax: %w", err)
				}
				MarkAwgCompatActive()
				sb.WriteString(fmt.Sprintf("jmax=%s\n", val))

			case "s1", "s2", "s3", "s4":
				if _, err := strconv.Atoi(val); err != nil {
					return "", fmt.Errorf("failed to parse %s: %w", strings.ToUpper(key), err)
				}
				if err := ValidateAwgCompatPadding(key, val); err != nil {
					return "", err
				}
				MarkAwgCompatActive()
				sb.WriteString(fmt.Sprintf("%s=%s\n", key, val))

			case "h1", "h2", "h3", "h4":
				if err := ValidateAwgCompatHeader(key, val); err != nil {
					return "", err
				}
				MarkAwgCompatActive()
				sb.WriteString(fmt.Sprintf("%s=%s\n", key, val))

			case "i1", "i2", "i3", "i4", "i5":
				index := int(key[1] - '1')
				if err := SetAwgCompatIPacketSpec(index, val); err != nil {
					return "", err
				}
				MarkAwgCompatActive()
				sb.WriteString(fmt.Sprintf("%s=%s\n", key, val))
			}

		case "peer":
			switch key {
			case "publickey", "presharedkey":
				decoded, err := base64.StdEncoding.DecodeString(val)
				if err != nil {
					return "", fmt.Errorf("invalid base64 key: %v", err)
				}
				hexVal := hex.EncodeToString(decoded)
				uapiKey := "public_key"
				if key == "presharedkey" {
					uapiKey = "preshared_key"
				}
				sb.WriteString(fmt.Sprintf("%s=%s\n", uapiKey, hexVal))

				if key == "publickey" {
					if pendingTrick != "" {
						sb.WriteString(fmt.Sprintf("trick=%s\n", pendingTrick))
					}
				}

			case "allowedips":
				ips := strings.Split(val, ",")
				for _, ip := range ips {
					sb.WriteString(fmt.Sprintf("allowed_ip=%s\n", strings.TrimSpace(ip)))
				}

			case "endpoint":
				sb.WriteString(fmt.Sprintf("endpoint=%s\n", val))

			case "persistentkeepalive":
				sb.WriteString(fmt.Sprintf("persistent_keepalive_interval=%s\n", val))
			}
		}
	}
	if err := FinalizeAwgCompatConfig(); err != nil {
		return "", err
	}
	SetFakeStrategyProfile(fakeStrategyProfile)
	SetFakePacketsEnabled(fakePacketsEnabled)
	return sb.String(), scanner.Err()
}

func parseReservedBytes(value string) ([]byte, error) {
	parts := strings.Split(value, ",")
	if len(parts) != 3 {
		return nil, fmt.Errorf("invalid reserved value: %s", value)
	}

	reserved := make([]byte, 3)
	for i, part := range parts {
		parsed, err := strconv.Atoi(strings.TrimSpace(part))
		if err != nil || parsed < 0 || parsed > 255 {
			return nil, fmt.Errorf("invalid reserved value: %s", value)
		}
		reserved[i] = byte(parsed)
	}

	return reserved, nil
}

func mapNovaModeToWarpPlus(mode string) (trick string, reservedEnabled bool, fakePacketsEnabled bool, fakeStrategyProfile string) {
	switch strings.ToLower(strings.TrimSpace(mode)) {
	case "warp-awg-lite":
		return "", true, false, "off"
	case "warp-awg-exact":
		return "", true, false, "off"
	case "warp-awg-v2":
		return "", true, false, "off"
	case "warp-awg":
		return "", true, false, "off"
	case "warp-awg-max":
		return "", true, false, "off"
	case "warp-v1":
		return "", true, false, "off"
	case "warp-v2":
		return "", true, false, "off"
	case "warp-v3":
		return "", true, false, "off"
	case "reserved-only", "handshake-reserved":
		return "", true, false, "off"
	case "plain":
		return "", false, false, "off"
	case "plain-wireguard":
		return "", true, false, "off"
	default:
		return "", true, false, "off"
	}
}

func protectOpenedSockets(bind wgconn.Bind) {
	peek, ok := bind.(wgconn.PeekLookAtSocketFd)
	if !ok || GlobalProtector == nil {
		return
	}

	if fd4, err := peek.PeekLookAtSocketFd4(); err == nil && fd4 >= 0 {
		GlobalProtector(fd4)
	}
	if fd6, err := peek.PeekLookAtSocketFd6(); err == nil && fd6 >= 0 {
		GlobalProtector(fd6)
	}
}

// --- AndroidTUN Implementation ---

type AndroidTUN struct {
	file          *os.File
	events        chan tun.Event
	mtu           int
	mu            sync.Mutex
	telegramProxy *telegramTransparentProxy
}

func CreateAndroidTUN(fd int) (tun.Device, error) {
	dupFD, err := syscall.Dup(fd)
	if err != nil {
		return nil, fmt.Errorf("failed to dup Android TUN fd: %w", err)
	}
	file := os.NewFile(uintptr(dupFD), "tun")
	tunReadLogBudget.Store(12)
	tunWriteLogBudget.Store(12)
	return &AndroidTUN{
		file:   file,
		events: make(chan tun.Event, 10),
		mtu:    1280,
	}, nil
}

func (t *AndroidTUN) Name() (string, error) {
	return "android0", nil
}

func (t *AndroidTUN) File() *os.File {
	return t.file
}

func (t *AndroidTUN) Events() <-chan tun.Event {
	return t.events
}

func (t *AndroidTUN) Read(buffs [][]byte, sizes []int, offset int) (int, error) {
	if len(buffs) == 0 {
		return 0, nil
	}

	for {
		n, err := t.file.Read(buffs[0][offset:])
		if err != nil {
			// Android TUN may transiently return EAGAIN/EWOULDBLOCK between engine restarts.
			// Treat that as "no packet yet" instead of killing the device.
			if errors.Is(err, syscall.EAGAIN) || errors.Is(err, syscall.EWOULDBLOCK) {
				time.Sleep(10 * time.Millisecond)
				continue
			}
			return 0, err
		}
		if n > 0 {
			debugTelegramTransparentCandidate(buffs[0][offset:offset+n], "tun-read")
			interceptedTelegram, telegramErr := t.tryHandleTelegramTransparent(buffs[0][offset : offset+n])
			if telegramErr != nil {
				log.Printf("Telegram transparent relay skipped: %v", telegramErr)
			}
			if interceptedTelegram {
				continue
			}
			intercepted, interceptErr := t.tryHandleDNSIntercept(buffs[0][offset : offset+n])
			if interceptErr != nil && dnsInterceptLogBudget.Add(-1) >= 0 {
				log.Printf("DNS intercept skipped: %v", interceptErr)
			}
			if intercepted {
				continue
			}
		}
		sizes[0] = n
		if n > 0 && tunReadLogBudget.Add(-1) >= 0 {
			ipVersion := 0
			if len(buffs[0]) > offset {
				ipVersion = int(buffs[0][offset] >> 4)
			}
			log.Printf("TUN outbound packet: len=%d ipver=%d", n, ipVersion)
		}
		return 1, nil
	}
}

func (t *AndroidTUN) Write(buffs [][]byte, offset int) (int, error) {
	for _, buf := range buffs {
		if len(buf) > offset && tunWriteLogBudget.Add(-1) >= 0 {
			ipVersion := int(buf[offset] >> 4)
			log.Printf("TUN inbound packet: len=%d ipver=%d", len(buf)-offset, ipVersion)
		}
		_, err := t.file.Write(buf[offset:])
		if err != nil {
			return 0, err
		}
	}
	return len(buffs), nil
}

// Сколько перехваченных DNS-запросов разрешено резолвить одновременно.
//
// Ограничение нужно на случай шторма запросов: без него каждая новая горутина
// висела бы на своём таймауте, а память и сокеты кончались бы молча. Переполнение
// очереди означает «не перехватываем», и пакет уходит в туннель как обычный.
var dnsInterceptInFlight = make(chan struct{}, 32)

// Пишет в TUN ответ, собранный перехватом DNS.
//
// Под тем же мьютексом, что и закрытие устройства: иначе поздний ответ мог бы
// прийти уже после `Close()` и записаться в закрытый дескриптор.
func (t *AndroidTUN) writeInterceptedResponse(packet []byte) {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.file == nil {
		return
	}
	if _, err := t.file.Write(packet); err != nil && dnsInterceptLogBudget.Add(-1) >= 0 {
		log.Printf("DNS intercept response write failed: %v", err)
	}
}

// Резолвит перехваченный запрос и отвечает на него, не задерживая чтение из TUN.
//
// Почему отдельной горутиной. У wireguard-go одна `RoutineReadFromTUN`, и раньше
// резолвинг шёл прямо в ней: каждый запрос ждал ответа апстрима до 2,5 с на адрес.
// Импортированный профиль со своим DNS внутри туннеля (например `10.2.0.1`),
// который не отвечает, останавливал этим весь исходящий поток — рукопожатие есть,
// первый пинг проходит, а дальше тишина. Воспроизведено на устройстве: с прежним
// порядком резолверов тот же профиль давал 100 % потерь и `curl` по IP не работал,
// с асинхронным ответом — 0 % потерь и рабочий HTTPS.
func (t *AndroidTUN) answerInterceptedDNS(
	query []byte,
	upstreams []string,
	timeout time.Duration,
	queryName string,
	buildResponse func(payload []byte) ([]byte, error),
) bool {
	select {
	case dnsInterceptInFlight <- struct{}{}:
	default:
		return false
	}
	go func() {
		defer func() { <-dnsInterceptInFlight }()
		responsePayload, upstream, err := resolveDNSPayload(query, upstreams, timeout)
		if err != nil {
			if dnsInterceptLogBudget.Add(-1) >= 0 {
				log.Printf("DNS intercept failed (name=%s): %v", queryName, err)
			}
			return
		}
		responsePacket, err := buildResponse(responsePayload)
		if err != nil {
			if dnsInterceptLogBudget.Add(-1) >= 0 {
				log.Printf("DNS intercept response build failed (name=%s): %v", queryName, err)
			}
			return
		}
		t.writeInterceptedResponse(responsePacket)
		if dnsInterceptLogBudget.Add(-1) >= 0 {
			log.Printf(
				"DNS intercept answered locally via %s (query=%dB response=%dB, name=%s)",
				upstream,
				len(query),
				len(responsePayload),
				queryName,
			)
		}
	}()
	return true
}

func (t *AndroidTUN) Flush() error {
	return nil
}

func (t *AndroidTUN) MTU() (int, error) {
	return t.mtu, nil
}

func (t *AndroidTUN) BatchSize() int {
	return 1
}

func (t *AndroidTUN) Close() error {
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.telegramProxy != nil {
		t.telegramProxy.close()
		t.telegramProxy = nil
	}
	if t.file == nil {
		return nil
	}
	err := t.file.Close()
	t.file = nil
	return err
}

func (t *AndroidTUN) tryHandleTelegramTransparent(packet []byte) (bool, error) {
	cfg := getTelegramTransparentProxyConfig()
	if !cfg.enabled || cfg.profile == telegramTransparentOff {
		return false, nil
	}
	if len(packet) < 1 {
		return false, nil
	}
	t.mu.Lock()
	defer t.mu.Unlock()
	if t.file == nil {
		return false, os.ErrClosed
	}
	if t.telegramProxy == nil {
		proxy, err := newTelegramTransparentProxy(t.file, t.mtu)
		if err != nil {
			return false, err
		}
		t.telegramProxy = proxy
	}
	return t.telegramProxy.maybeHandle(packet)
}

func (t *AndroidTUN) tryHandleDNSIntercept(packet []byte) (bool, error) {
	cfg := getDNSInterceptConfig()
	if !cfg.enabled || len(cfg.mediaUpstreams) == 0 {
		return false, nil
	}
	switch packet[0] >> 4 {
	case 4:
		return t.tryHandleDNSInterceptIPv4(packet, cfg)
	case 6:
		return t.tryHandleDNSInterceptIPv6(packet, cfg)
	default:
		return false, nil
	}
}

func (t *AndroidTUN) tryHandleDNSInterceptIPv4(packet []byte, cfg dnsInterceptConfig) (bool, error) {
	if len(packet) < 28 {
		return false, nil
	}
	ihl := int(packet[0]&0x0f) * 4
	if ihl < 20 || len(packet) < ihl+8 {
		return false, nil
	}
	if packet[9] != 17 {
		return false, nil
	}

	totalLen := int(binary.BigEndian.Uint16(packet[2:4]))
	if totalLen <= 0 || totalLen > len(packet) {
		totalLen = len(packet)
	}
	fragmentOffset := binary.BigEndian.Uint16(packet[6:8]) & 0x1fff
	if fragmentOffset != 0 {
		return false, nil
	}

	udpStart := ihl
	srcPort := binary.BigEndian.Uint16(packet[udpStart : udpStart+2])
	dstPort := binary.BigEndian.Uint16(packet[udpStart+2 : udpStart+4])
	if dstPort != 53 {
		return false, nil
	}

	udpLen := int(binary.BigEndian.Uint16(packet[udpStart+4 : udpStart+6]))
	if udpLen < 8 {
		return false, nil
	}

	payloadStart := udpStart + 8
	payloadEnd := payloadStart + (udpLen - 8)
	if payloadEnd > totalLen {
		payloadEnd = totalLen
	}
	if payloadStart >= payloadEnd || payloadEnd > len(packet) {
		return false, nil
	}

	srcIP := append(net.IP(nil), packet[12:16]...)
	dstIP := append(net.IP(nil), packet[16:20]...)
	dnsPayload := append([]byte(nil), packet[payloadStart:payloadEnd]...)

	upstreams, queryName, intercept, err := selectDNSInterceptUpstreams(dnsPayload, cfg)
	if err != nil {
		return false, err
	}
	if !intercept {
		return false, nil
	}
	handed := t.answerInterceptedDNS(
		dnsPayload,
		upstreams,
		cfg.timeout,
		queryName,
		func(payload []byte) ([]byte, error) {
			return buildIPv4UDPResponsePacket(dstIP, srcIP, dstPort, srcPort, payload)
		},
	)
	if !handed {
		// Очередь резолвинга переполнена: пакет не перехватываем, пусть идёт в
		// туннель обычным путём — это лучше, чем потерять запрос.
		return false, errors.New("dns intercept queue is full")
	}
	return true, nil
}

func (t *AndroidTUN) tryHandleDNSInterceptIPv6(packet []byte, cfg dnsInterceptConfig) (bool, error) {
	if len(packet) < 48 {
		return false, nil
	}
	if packet[6] != 17 {
		return false, nil
	}

	payloadLen := int(binary.BigEndian.Uint16(packet[4:6]))
	if payloadLen < 8 || len(packet) < 40+payloadLen {
		return false, nil
	}

	udpStart := 40
	srcPort := binary.BigEndian.Uint16(packet[udpStart : udpStart+2])
	dstPort := binary.BigEndian.Uint16(packet[udpStart+2 : udpStart+4])
	if dstPort != 53 {
		return false, nil
	}

	udpLen := int(binary.BigEndian.Uint16(packet[udpStart+4 : udpStart+6]))
	if udpLen < 8 {
		return false, nil
	}
	payloadStart := udpStart + 8
	payloadEnd := payloadStart + (udpLen - 8)
	maxEnd := 40 + payloadLen
	if payloadEnd > maxEnd {
		payloadEnd = maxEnd
	}
	if payloadStart >= payloadEnd || payloadEnd > len(packet) {
		return false, nil
	}

	srcIP := append(net.IP(nil), packet[8:24]...)
	dstIP := append(net.IP(nil), packet[24:40]...)
	dnsPayload := append([]byte(nil), packet[payloadStart:payloadEnd]...)

	upstreams, queryName, intercept, err := selectDNSInterceptUpstreams(dnsPayload, cfg)
	if err != nil {
		return false, err
	}
	if !intercept {
		return false, nil
	}
	handed := t.answerInterceptedDNS(
		dnsPayload,
		upstreams,
		cfg.timeout,
		queryName,
		func(payload []byte) ([]byte, error) {
			return buildIPv6UDPResponsePacket(dstIP, srcIP, dstPort, srcPort, payload)
		},
	)
	if !handed {
		return false, errors.New("dns intercept queue is full")
	}
	return true, nil
}

func selectDNSInterceptUpstreams(query []byte, cfg dnsInterceptConfig) ([]string, string, bool, error) {
	combinedUpstreams := append([]string(nil), cfg.mediaUpstreams...)
	combinedUpstreams = append(combinedUpstreams, cfg.defaultUpstreams...)
	if len(combinedUpstreams) == 0 {
		return nil, "", false, nil
	}
	if len(cfg.mediaDomainSuffixes) == 0 {
		return combinedUpstreams, "", true, nil
	}
	queryName, err := extractDNSQueryName(query)
	if err != nil {
		return nil, "", false, err
	}
	if !matchesDNSDomainSuffix(queryName, cfg.mediaDomainSuffixes) {
		return nil, queryName, false, nil
	}
	return combinedUpstreams, queryName, true, nil
}

func extractDNSQueryName(query []byte) (string, error) {
	if len(query) < 12 {
		return "", errors.New("dns payload too short")
	}
	if binary.BigEndian.Uint16(query[4:6]) == 0 {
		return "", errors.New("dns query has no questions")
	}
	labels := make([]string, 0, 6)
	offset := 12
	for {
		if offset >= len(query) {
			return "", errors.New("dns query name truncated")
		}
		length := int(query[offset])
		offset++
		if length == 0 {
			break
		}
		if length&0xc0 != 0 {
			return "", errors.New("compressed dns query names are not supported")
		}
		if offset+length > len(query) {
			return "", errors.New("dns label exceeds payload size")
		}
		labels = append(labels, strings.ToLower(string(query[offset:offset+length])))
		offset += length
	}
	if len(labels) == 0 {
		return "", errors.New("dns query name is empty")
	}
	return strings.Join(labels, "."), nil
}

func matchesDNSDomainSuffix(queryName string, suffixes []string) bool {
	normalizedQuery := strings.Trim(strings.ToLower(strings.TrimSpace(queryName)), ".")
	if normalizedQuery == "" {
		return false
	}
	for _, suffix := range suffixes {
		normalizedSuffix := strings.Trim(strings.ToLower(strings.TrimSpace(suffix)), ".")
		if normalizedSuffix == "" {
			continue
		}
		if normalizedQuery == normalizedSuffix || strings.HasSuffix(normalizedQuery, "."+normalizedSuffix) {
			return true
		}
	}
	return false
}

func resolveDNSPayload(query []byte, upstreams []string, timeout time.Duration) ([]byte, string, error) {
	if len(query) < 12 {
		return nil, "", errors.New("dns payload too short")
	}
	if timeout <= 0 {
		timeout = 2500 * time.Millisecond
	}

	var lastErr error
	for _, upstream := range upstreams {
		trimmed := strings.TrimSpace(upstream)
		if trimmed == "" {
			continue
		}

		endpoint, err := net.ResolveUDPAddr("udp", net.JoinHostPort(trimmed, "53"))
		if err != nil {
			lastErr = fmt.Errorf("resolve upstream %s: %w", trimmed, err)
			continue
		}

		network := "udp4"
		if endpoint.IP == nil || endpoint.IP.To4() == nil {
			network = "udp6"
		}
		conn, err := net.ListenUDP(network, nil)
		if err != nil {
			lastErr = fmt.Errorf("open dns socket for %s: %w", trimmed, err)
			continue
		}
		if err := protectDNSUDPConn(conn); err != nil {
			_ = conn.Close()
			lastErr = fmt.Errorf("protect dns socket for %s: %w", trimmed, err)
			continue
		}
		_ = conn.SetDeadline(time.Now().Add(timeout))

		if _, err := conn.WriteToUDP(query, endpoint); err != nil {
			_ = conn.Close()
			lastErr = fmt.Errorf("send dns query to %s: %w", trimmed, err)
			continue
		}

		buffer := make([]byte, 4096)
		n, _, err := conn.ReadFromUDP(buffer)
		_ = conn.Close()
		if err != nil {
			lastErr = fmt.Errorf("read dns response from %s: %w", trimmed, err)
			continue
		}
		if n < 12 {
			lastErr = fmt.Errorf("short dns response from %s", trimmed)
			continue
		}
		if buffer[0] != query[0] || buffer[1] != query[1] {
			lastErr = fmt.Errorf("dns transaction id mismatch from %s", trimmed)
			continue
		}
		return append([]byte(nil), buffer[:n]...), trimmed, nil
	}

	if lastErr == nil {
		lastErr = errors.New("no DNS upstreams configured")
	}
	return nil, "", lastErr
}

func protectDNSUDPConn(conn *net.UDPConn) error {
	if conn == nil || GlobalProtector == nil {
		return nil
	}
	rawConn, err := conn.SyscallConn()
	if err != nil {
		return err
	}
	var protectErr error
	controlErr := rawConn.Control(func(fd uintptr) {
		if !GlobalProtector(int(fd)) {
			protectErr = errors.New("protect returned false")
		}
	})
	if controlErr != nil {
		return controlErr
	}
	return protectErr
}

func buildIPv4UDPResponsePacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, payload []byte) ([]byte, error) {
	src4 := srcIP.To4()
	dst4 := dstIP.To4()
	if src4 == nil || dst4 == nil {
		return nil, errors.New("dns intercept only supports IPv4 packets right now")
	}

	totalLen := 20 + 8 + len(payload)
	if totalLen > 0xffff {
		return nil, errors.New("dns response too large")
	}
	packet := make([]byte, totalLen)
	packet[0] = 0x45
	packet[1] = 0x00
	binary.BigEndian.PutUint16(packet[2:4], uint16(totalLen))
	binary.BigEndian.PutUint16(packet[4:6], 0)
	binary.BigEndian.PutUint16(packet[6:8], 0)
	packet[8] = 64
	packet[9] = 17
	copy(packet[12:16], src4)
	copy(packet[16:20], dst4)

	udpStart := 20
	binary.BigEndian.PutUint16(packet[udpStart:udpStart+2], srcPort)
	binary.BigEndian.PutUint16(packet[udpStart+2:udpStart+4], dstPort)
	binary.BigEndian.PutUint16(packet[udpStart+4:udpStart+6], uint16(8+len(payload)))
	copy(packet[udpStart+8:], payload)

	binary.BigEndian.PutUint16(packet[10:12], internetChecksum(packet[:20]))
	udpChecksum := udpChecksumIPv4(src4, dst4, packet[udpStart:])
	if udpChecksum == 0 {
		udpChecksum = 0xffff
	}
	binary.BigEndian.PutUint16(packet[udpStart+6:udpStart+8], udpChecksum)
	return packet, nil
}

func buildIPv6UDPResponsePacket(srcIP, dstIP net.IP, srcPort, dstPort uint16, payload []byte) ([]byte, error) {
	src16 := srcIP.To16()
	dst16 := dstIP.To16()
	if src16 == nil || dst16 == nil {
		return nil, errors.New("dns intercept requires IPv6 addresses")
	}

	payloadLen := 8 + len(payload)
	if payloadLen > 0xffff {
		return nil, errors.New("dns response too large")
	}

	packet := make([]byte, 40+payloadLen)
	packet[0] = 0x60
	packet[1] = 0x00
	packet[2] = 0x00
	packet[3] = 0x00
	binary.BigEndian.PutUint16(packet[4:6], uint16(payloadLen))
	packet[6] = 17
	packet[7] = 64
	copy(packet[8:24], src16)
	copy(packet[24:40], dst16)

	udpStart := 40
	binary.BigEndian.PutUint16(packet[udpStart:udpStart+2], srcPort)
	binary.BigEndian.PutUint16(packet[udpStart+2:udpStart+4], dstPort)
	binary.BigEndian.PutUint16(packet[udpStart+4:udpStart+6], uint16(payloadLen))
	copy(packet[udpStart+8:], payload)

	udpChecksum := udpChecksumIPv6(src16, dst16, packet[udpStart:])
	if udpChecksum == 0 {
		udpChecksum = 0xffff
	}
	binary.BigEndian.PutUint16(packet[udpStart+6:udpStart+8], udpChecksum)
	return packet, nil
}

func udpChecksumIPv4(srcIP, dstIP net.IP, udpPacket []byte) uint16 {
	pseudo := make([]byte, 12+len(udpPacket))
	copy(pseudo[0:4], srcIP.To4())
	copy(pseudo[4:8], dstIP.To4())
	pseudo[8] = 0
	pseudo[9] = 17
	binary.BigEndian.PutUint16(pseudo[10:12], uint16(len(udpPacket)))
	copy(pseudo[12:], udpPacket)
	return internetChecksum(pseudo)
}

func udpChecksumIPv6(srcIP, dstIP net.IP, udpPacket []byte) uint16 {
	pseudo := make([]byte, 40+len(udpPacket))
	copy(pseudo[0:16], srcIP.To16())
	copy(pseudo[16:32], dstIP.To16())
	binary.BigEndian.PutUint32(pseudo[32:36], uint32(len(udpPacket)))
	pseudo[36] = 0
	pseudo[37] = 0
	pseudo[38] = 0
	pseudo[39] = 17
	copy(pseudo[40:], udpPacket)
	return internetChecksum(pseudo)
}

func internetChecksum(data []byte) uint16 {
	var sum uint32
	length := len(data)
	for i := 0; i+1 < length; i += 2 {
		sum += uint32(binary.BigEndian.Uint16(data[i : i+2]))
	}
	if length%2 != 0 {
		sum += uint32(data[length-1]) << 8
	}
	for (sum >> 16) != 0 {
		sum = (sum & 0xffff) + (sum >> 16)
	}
	return ^uint16(sum)
}

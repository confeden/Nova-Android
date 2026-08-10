package com.example.nova

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.DnsResolver
import android.net.VpnService
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.operaproxy.ProxyVpnService as OperaNativeVpnService
import nova.Nova // Import generated Go wrapper
import org.json.JSONObject
import java.math.BigInteger
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class NovaVpnService : OperaNativeVpnService() {

    private var interfaceDescriptor: ParcelFileDescriptor? = null
    private val CHANNEL_ID = "NovaVpnChannel"
    private val NOTIFICATION_ID = 1
    @Volatile
    private var masqueAuthFailureObserved = false
    @Volatile
    private var masqueLastAuthError: String? = null
    private val cleanupInProgress = AtomicBoolean(false)
    private val stoppedStateStaleDetachInProgress = AtomicBoolean(false)
    @Volatile
    private var lastStoppedStateStaleDetachAtMs = 0L
    @Volatile
    private var explicitStopRequested = false
    @Volatile
    private var operaTunThread: Thread? = null
    @Volatile
    private var operaTunLastExitCode: Int? = null
    @Volatile
    private var novaEngineThread: Thread? = null
    @Volatile
    private var suppressSessionRestore = false
    @Volatile
    private var lastTaskRemovedAtMs = 0L
    @Volatile
    private var operaFallbackActive = false

    /** Когда в этом процессе звали `tun2proxy_stop()`. Ноль — ещё ни разу. */
    @Volatile
    private var tun2proxyForceStopAtMs = 0L
    @Volatile
    private var preferGracefulOperaStopOnce = false
    @Volatile
    private var novaCoreTunnelActive = false
    @Volatile
    private var currentAttemptOrdinal = 0
    @Volatile
    private var currentAttemptTotal = 0
    /**
     * Транспорт текущей попытки: `MASQUE`, `WARP` или `OPERA`. Пустая строка —
     * ещё ничего не пробовали. Значение на момент перехода в CONNECTED и есть
     * тот транспорт, который несёт туннель.
     */
    @Volatile
    private var currentTransportLabel = ""
    /**
     * Пояснение для интерфейса, когда работает не выбранный транспорт. Уезжает
     * в файл состояния вместе с состоянием: главный процесс не видит чужие
     * SharedPreferences.
     */
    @Volatile
    private var currentTransportNotice = ""
    @Volatile
    private var manualWarpProfileSwitchTargetKey: String? = null
    @Volatile
    private var manualWarpProfileSwitchOrdinal = 0
    @Volatile
    private var manualWarpProfileSwitchTotal = 0
    @Volatile
    private var reconnectingForNetworkChange = false
    @Volatile
    private var observedUnderlyingNetworkId: String? = null
    @Volatile
    private var observedUnderlyingNetworkSignature: String? = null
    @Volatile
    private var observedUnderlyingUnavailable = false
    @Volatile
    private var lastNetworkReconnectAt = 0L
    /** Идёт ли сейчас перебор профилей VLESS: только он умеет менять узел на ходу. */
    @Volatile
    private var vlessRotationActive = false

    /** Кнопку «следующий профиль» нажали: перебору пора взять следующую запись. */
    @Volatile
    private var pendingVlessProfileSwitch = false

    /**
     * Порт SOCKS-инбаунда работающего ядра Xray, или -1.
     *
     * Через него наблюдается внешний адрес: это единственный путь наружу, который
     * гарантированно идёт через выходной узел, а не мимо него.
     */
    @Volatile
    private var vlessSocksPort = -1
    @Volatile
    private var currentWarpMaskHost: String? = null
    @Volatile
    private var activeWarpQualityTarget: ActiveWarpQualityTarget? = null
    @Volatile
    private var activeWarpQualityTargetDemoted = false
    @Volatile
    private var lastImportedExactTrafficProofAtMs = 0L
    @Volatile
    private var lastImportedExactObservedStats: TunnelStats? = null
    @Volatile
    private var currentWarpTunnelMtu = 1280
    @Volatile
    private var optionalTrafficCamouflageSetterAvailable = true
    @Volatile
    private var optionalMasqueFakeBurstSetterAvailable = true
    @Volatile
    private var optionalTelegramTransparentSetterAvailable = true
    @Volatile
    private var optionalTelegramWsSignatureSetterAvailable = true
    @Volatile
    private var telegramWsSignatureSecretInstalled = false
    private val restrictedMobileCheckLock = Any()
    @Volatile
    private var lastRestrictedMobileCheckNetworkId: String? = null
    @Volatile
    private var lastRestrictedMobileCheckAtMs = 0L
    @Volatile
    private var lastRestrictedMobileDetected = false
    private val connectGeneration = AtomicInteger(0)
    @Volatile
    private var connectedHealthProbeFailures = 0

    /** Учёт перерукопожатий на живой сессии, см. [sampleTunnelRekeyChurn]. */
    private var tunnelRekeyWindowStartedAtMs = 0L
    private var lastSeenHandshakeTimeSec = 0L
    private var tunnelRekeyCount = 0
    private var activeEndpointHost = ""
    private var activeEndpointPort = 0
    private var tunnelRekeyWindowRxBytes = 0L
    private var tunnelRekeyWindowTxBytes = 0L
    private val connectedWarpHealthWindow = ArrayDeque<Boolean>()
    @Volatile
    private var connectedWarpHealthWindowFailures = 0
    @Volatile
    private var connectedWarpHealthConsecutiveFailures = 0
    @Volatile
    private var lastConnectedAtMs = 0L
    @Volatile
    private var lastSuccessfulTunnelProbeAtMs = 0L
    @Volatile
    private var lastSuccessfulTunnelProbeNetworkSignature: String? = null
    @Volatile
    private var lastSuccessfulTunnelProbeNetworkClass: String? = null
    @Volatile
    private var lastBenignHealthSkipLogAtMs = 0L
    @Volatile
    private var lastTransportFailureSignalAtMs = 0L
    @Volatile
    private var lastTransportFailureSignature: String? = null
    @Volatile
    private var operaBadGatewayWindowStartedAtMs = 0L
    @Volatile
    private var operaBadGatewayBurstCount = 0
    @Volatile
    private var lastOperaBadGatewayRecoveryAtMs = 0L
    @Volatile
    private var lastDeviceWakeAtMs = 0L
    @Volatile
    private var requireFreshTunnelProbeUntilMs = 0L

    /** Порядок резолверов, отданный ядру последним. Пусто — перехват выключен. */
    @Volatile
    private var activeDnsInterceptServers: List<String> = emptyList()

    /** DNS из импортированного профиля текущей сессии, для пересмотра порядка на лету. */
    @Volatile
    private var lastImportedProfileDnsServers: List<String> = emptyList()
    @Volatile
    private var ignoreUnderlyingWakeEventsUntilMs = 0L
    @Volatile
    private var lastAcceleratedRecoveryAtMs = 0L
    @Volatile
    private var lastAcceleratedRecoveryReason: String? = null
    @Volatile
    private var recoveryWakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var recoveryWakeLockHeldUntilMs = 0L
    private var connectedScreenOffWakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var establishNullLoopWindowStartedAtMs = 0L
    @Volatile
    private var establishNullLoopCount = 0
    @Volatile
    private var preparedTransportGenerationId = -1
    @Volatile
    private var preparedTransportStateAtMs = 0L
    private var underlyingNetworkCallback: android.net.ConnectivityManager.NetworkCallback? = null
    private val networkRecoveryHandler = Handler(Looper.getMainLooper())
    private var pendingNetworkRecoveryReason: String? = null
    private val networkRecoveryRunnable = Runnable {
        val reason = pendingNetworkRecoveryReason.orEmpty()
        pendingNetworkRecoveryReason = null
        evaluatePendingNetworkRecovery(reason)
    }
    @Volatile
    private var currentCycleReuseLastSuccess = false
    @Volatile
    private var currentCycleStableSuccess: StableSuccessSnapshot? = null
    @Volatile
    private var pendingUnderlayUpgradeWarpHint: StableSuccessSnapshot? = null
    @Volatile
    private var pendingUnderlayUpgradeWarpHintAtMs = 0L
    private val transientDegradedWarpReuseUntilMs = ConcurrentHashMap<String, Long>()
    private val tileRefreshHandler = Handler(Looper.getMainLooper())
    private val stopCleanupHandler = Handler(Looper.getMainLooper())
    private val stopCleanupRunnable = Runnable {
        performDelayedStopCleanup(
            clientData = ClientData(this),
            source = "local-handler",
        )
    }
    private val vpnConsistencyHandler = Handler(Looper.getMainLooper())
    private val vpnConsistencyRunnable = object : Runnable {
        override fun run() {
            try {
                sampleTunnelRekeyChurn()
                reconcileSystemVpnConsistency()
            } finally {
                vpnConsistencyHandler.postDelayed(this, nextVpnConsistencyIntervalMs())
            }
        }
    }
    private val warpConfigDiscoveryStopRequested = AtomicBoolean(false)
    private val warpConfigDiscoveryRunning = AtomicBoolean(false)
    private val operaEndpointPrewarmRunning = AtomicBoolean(false)
    private val exitObservationRefreshInFlight = AtomicBoolean(false)
    @Volatile
    private var lastOperaEndpointPrewarmAtMs = 0L
    @Volatile
    private var operaBootstrapWarpGenerationId = -1

    private fun normalizeReservedValue(value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        if (
            trimmed.equals("null", ignoreCase = true) ||
            trimmed.equals("none", ignoreCase = true) ||
            trimmed.equals("<none>", ignoreCase = true)
        ) {
            return null
        }
        val tokens = trimmed.split(',').map { it.trim() }
        if (tokens.size == 3) {
            val normalizedTokens = tokens.map { token ->
                token.toIntOrNull()?.takeIf { it in 0..255 }?.toString()
            }
            if (normalizedTokens.all { it != null }) {
                return normalizedTokens.joinToString(",") { it!! }
            }
        }
        return trimmed
    }

    private data class TransportMode(
        val name: String,
        val engine: String = "wireguard",
        val useFakePackets: Boolean,
        val reservedMode: String,
        val preferredPorts: List<Int> = emptyList(),
        val restrictToPreferredPorts: Boolean = false,
        val masqueSni: String? = null,
        val novaModeOverride: String? = null,
        val fakePacketsOverride: Boolean? = null,
        val reservedModeOverride: String? = null,
        val preferImportedRawDns: Boolean = false,
        val preferImportedRawMtu: Boolean = false,
        val preferImportedRawIdentity: Boolean = false,
        val omitReservedLineIfMissingInImport: Boolean = false,
    )

    private data class EndpointCandidate(
        val host: String,
        val preferredPort: Int? = null,
        val source: String = "api",
    )

    private data class ConnectionAttempt(
        val endpointHost: String,
        val port: Int,
        val mode: TransportMode,
        val endpointSource: String,
        val importedConfigHost: String? = null,
        val strategyScope: String? = null,
        val preferredSni: String? = null,
    )

    private data class TunnelStats(
        val lastHandshakeTimeSec: Long,
        val rxBytes: Long,
        val txBytes: Long,
    )

    private data class StableSuccessSnapshot(
        val host: String,
        val port: Int,
        val protocol: String,
        val mode: String,
        val networkSignature: String,
    )

    private data class ActiveWarpQualityTarget(
        val engine: String,
        val mode: String,
        val host: String,
        val port: Int,
        val endpointSource: String,
        val rawConfig: String,
        val scope: String,
        val importedConfigHost: String? = null,
        val preferImportedRawIdentity: Boolean = false,
    )

    private data class ConnectedWarpHealthWindowSnapshot(
        val samples: Int,
        val successes: Int,
        val failures: Int,
        val consecutiveFailures: Int,
    )

    private data class WarpQualityDiagnosticEntry(
        val mode: String,
        val host: String,
        val port: Int,
        val endpointSource: String,
        val sni: String,
        val outcome: String,
        val attemptDurationMs: Long,
        val stableDurationMs: Long,
        val probeCount: Int,
        val pingSuccesses: Int,
        val avgPingMs: Double,
    )

    private enum class MessengerAccelerationProfile {
        OFF,
        WIFI,
        MOBILE,
    }

    private data class ImportedInterfaceOverrides(
        val dnsServers: List<String> = emptyList(),
        val mtu: Int? = null,
        val reserved: String? = null,
        val hasReservedLine: Boolean = false,
        val privateKey: String? = null,
        val addresses: List<ImportedInterfaceAddress> = emptyList(),
        val peerPublicKey: String? = null,
        val peerPresharedKey: String? = null,
        val peerPersistentKeepalive: Int? = null,
        val peerAdvancedSecurity: String? = null,
    )

    private data class ImportedInterfaceAddress(
        val address: String,
        val prefixLength: Int,
    ) {
        fun toConfigValue(): String = "$address/$prefixLength"
    }

    private data class TunnelInterfaceIdentity(
        val addresses: List<ImportedInterfaceAddress>,
        val source: String,
    )

    private data class AttemptBudget(
        val noHandshakeTimeoutMs: Long,
        val handshakeTimeoutMs: Long,
        val noInboundAfterHandshakeTimeoutMs: Long,
        val maxConnectivityProbeAttempts: Int,
        val minProbeSpacingMs: Long,
    )

    private data class MasqueIdentity(
        val privateKey: String,
        val endpointV4: String?,
        val endpointV6: String?,
        val endpointV4Candidates: List<String>,
        val endpointV6Candidates: List<String>,
        val endpointPubKey: String,
        val ipv4: String,
        val ipv6: String,
        val ports: List<Int>,
    )

    private data class ExitSnapshot(
        val ipv4: String,
        val ipv6: String,
        val country: String,
        val colo: String,
    )

    private object AttemptOutcome {
        const val SUCCESS = "success"
        const val UNSTABLE = "unstable"
        const val HANDSHAKE = "handshake"
        const val FAILURE = "failure"
        const val DEFERRED = "deferred"
    }

    private enum class OperaFallbackResult {
        FAILED,
        CONNECTED,
        NEED_RECONNECT,
    }
    
    companion object {
        var isRunning = false
        /** Окно замера перерукопожатий и порог, после которого сессия считается нестабильной. */
        private const val TUNNEL_REKEY_WINDOW_MS = 120_000L
        private const val TUNNEL_REKEY_ALERT_COUNT = 2

        /** Ниже этого объёма отправки окно замера не показательно. */
        private const val TUNNEL_REKEY_MIN_TX_KB = 32L

        /**
         * Предел на резолв имени Private DNS мимо туннеля.
         *
         * Полторы секунды — с запасом на обычный ответ и заведомо меньше, чем
         * стоит одна попытка подключения. Всё, что дольше, означает, что резолвер
         * упёрся в недостижимый DoT-сервер, и ждать его бессмысленно.
         */
        private const val PRIVATE_DNS_RESOLVE_TIMEOUT_MS = 1_500L

        const val ACTION_VPN_STATE = "com.example.nova.VPN_STATE"

        /**
         * Процесс `:vpn` обречён: в этой остановке звали `tun2proxy_stop`, и через две
         * секунды библиотека выполнит `exit(-1)`. Основной процесс живёт дальше и обязан
         * повторить запуск сам — ждать перезапуска от Android нельзя, он назначает свою
         * задержку (на устройстве доходило до 51 секунды).
         */
        const val ACTION_VPN_PROCESS_DOOMED = "com.example.nova.VPN_PROCESS_DOOMED"
        const val ACTION_START_OPERA_ONLY = "START_OPERA_ONLY"
        const val ACTION_CONNECT_SMART = "CONNECT_SMART"
        const val ACTION_RESTORE_LAST_SESSION = "RESTORE_LAST_SESSION"
        const val ACTION_REAPPLY_CURRENT_SESSION = "REAPPLY_CURRENT_SESSION"

        /**
         * Переключение профиля VLESS на ходу, без пересборки сессии.
         *
         * Обычный REAPPLY останавливает tun2proxy и поднимает туннель заново, а
         * библиотека при завершении зовёт `exit()` из своего рабочего потока и роняет
         * процесс службы в `__cxa_finalize` — проверено на тестовом устройстве, стоимость
         * падения около минуты без сети. Здесь останавливается только ядро Xray:
         * TUN и tun2proxy остаются на месте, и опасного участка просто нет.
         */
        const val ACTION_SWITCH_VLESS_PROFILE = "SWITCH_VLESS_PROFILE"
        const val ACTION_STOP_FOR_SOFT_RESTART = "STOP_FOR_SOFT_RESTART"
        const val ACTION_START_WARP_BOOTSTRAP = "START_WARP_BOOTSTRAP"
        const val ACTION_RUN_WARP_DIAGNOSTICS = "RUN_WARP_DIAGNOSTICS"
        /** Размер исходящего пакета QUIC у MASQUE, см. masqueInitialPacketSize в ядре. */
        private const val MASQUE_QUIC_PACKET_SIZE = 1242

        /** Запас на случай, если сервер выберет более длинный идентификатор соединения. */
        private const val MASQUE_MTU_SAFETY_MARGIN = 16

        /**
         * Минимальный MTU канала для IPv6 (RFC 8200). Ниже этой границы ядро не включает
         * IPv6 на интерфейсе, а `VpnService.Builder.establish()` сообщает об этом только
         * общим `Cannot set address`.
         */
        private const val IPV6_MIN_LINK_MTU = 1280

        const val ACTION_REFRESH_DNS_POLICY = "REFRESH_DNS_POLICY"
        const val EXTRA_EXIT_COUNTRY = "EXIT_COUNTRY"
        const val ACTION_RESET_WARP_REGISTRATION = "RESET_WARP_REGISTRATION"
        const val ACTION_START_WARP_CONFIG_DISCOVERY = "START_WARP_CONFIG_DISCOVERY"
        const val ACTION_START_WARP_NETWORK_ADAPTATION = "START_WARP_NETWORK_ADAPTATION"
        const val ACTION_START_WARP_QUALITY_DIAGNOSTICS = "START_WARP_QUALITY_DIAGNOSTICS"
        const val ACTION_STOP_WARP_CONFIG_DISCOVERY = "STOP_WARP_CONFIG_DISCOVERY"
        const val ACTION_REFRESH_NOTIFICATION = "REFRESH_FOREGROUND_NOTIFICATION"
        const val ACTION_FORCE_HEALTH_RECHECK = "FORCE_HEALTH_RECHECK"
        const val ACTION_CONFIRM_STOP_CLEANUP = "CONFIRM_STOP_CLEANUP"
        const val ACTION_SYNC_LOCAL_PROXY = "SYNC_LOCAL_PROXY"
        const val ACTION_BACKGROUND_HEARTBEAT = "BACKGROUND_HEARTBEAT"
        const val ACTION_WARP_CONFIG_DISCOVERY = "com.example.nova.WARP_CONFIG_DISCOVERY"
        const val EXTRA_FORCE_HEALTH_RECHECK_REASON = "force_health_recheck_reason"
        const val EXTRA_IGNORE_AUTO_RECONNECT_ON_RESTORE = "ignore_auto_reconnect_on_restore"
        const val EXTRA_FORCE_RESTART_ON_RESTORE = "force_restart_on_restore"
        const val EXTRA_EXIT_REGION = "exit_region"
        const val EXTRA_IMPORTED_CONFIG_SOURCE_ENABLED = "imported_config_source_enabled"
        const val EXTRA_IMPORTED_PROTOCOL_PREFERENCE = "imported_protocol_preference"
        const val EXTRA_REAPPLY_SPLIT_MODE = "reapply_split_mode"
        const val EXTRA_REAPPLY_SPLIT_APPS = "reapply_split_apps"
        const val EXTRA_REAPPLY_TRAFFIC_MASK_ENABLED = "reapply_traffic_mask_enabled"
        const val EXTRA_REAPPLY_TRAFFIC_MASK_MODE = "reapply_traffic_mask_mode"
        const val EXTRA_REAPPLY_TRAFFIC_MASK_HOST = "reapply_traffic_mask_host"
        const val STATE_CONNECTING = "CONNECTING"
        const val STATE_CONNECTED = "CONNECTED"
        const val STATE_STOPPED = "STOPPED"
        const val EXTRA_STATE = "state"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_ATTEMPT_ORDINAL = "attempt_ordinal"
        const val EXTRA_ATTEMPT_TOTAL = "attempt_total"
        const val EXTRA_MANUAL_WARP_PROFILE_MODE = "manual_warp_profile_mode"
        const val EXTRA_MANUAL_WARP_PROFILE_HOST = "manual_warp_profile_host"
        const val EXTRA_MANUAL_WARP_PROFILE_PORT = "manual_warp_profile_port"

        const val EXTRA_TILE_REFRESH_ONLY = "tile_refresh_only"
        const val EXTRA_MASK_HOST = "mask_host"
        const val EXTRA_MASK_POOL = "mask_pool"
        const val EXTRA_DISCOVERY_RUNNING = "discovery_running"
        const val EXTRA_DISCOVERY_FOUND_COUNT = "discovery_found_count"
        const val EXTRA_DISCOVERY_MESSAGE = "discovery_message"
        /**
         * Как tun2proxy обходится с DNS (`Tun2proxyDns` из `tun2proxy.h`).
         *
         * `VIRTUAL` — отвечать на запросы самому, выдавая виртуальный адрес, а наружу
         * отдавать имя: разрешает его сам прокси. `OVER_TCP` — гнать запрос в туннель
         * как TCP на выданный резолвер.
         *
         * Дефект, ради которого это стало важно: стоял `OVER_TCP`, и весь DNS уходил в
         * туннель как TCP на 1.1.1.1:53. Opera такой CONNECT не пропускает — в журнале
         * `#1 TCP …→1.1.1.1:53 error "timed out"`. Снаружи это выглядело хуже всего:
         * пинги в Nova стабильные (они идут по literal-адресам), а в браузере интернета
         * нет, потому что не разрешается ни одно имя. С `VIRTUAL` имя уходит в сам
         * запрос CONNECT и разрешается на стороне выхода — ровно так же, как это
         * работает в версии для ПК, где браузер ходит через HTTP-прокси.
         */
        private const val TUN2PROXY_DNS_VIRTUAL = 0

        /** Причины, за которыми стоит нажатие пользователя: только их отсечки логируем. */
        private val EXPLICIT_CONNECT_REASONS =
            setOf("opera-only", "smart-connect", "warp-connect", "vless-reapply")

        const val BACKEND_WARP = "WARP"
        const val BACKEND_OPERA = "OPERA"
        const val BACKEND_VLESS = "VLESS"

        /**
         * MTU туннеля VLESS. Наружу трафик уходит обычным TCP-соединением ядра Xray,
         * поэтому запас нужен только под заголовки TLS/REALITY — тот же 1420, что и у
         * Opera-прокси, который ходит через тот же tun2proxy.
         */
        private const val VLESS_TUN_MTU = 1420

        /** Адрес интерфейса VLESS. Отличается от Opera (10.1.10.1), чтобы не путать. */
        private const val VLESS_TUN_ADDRESS = "10.1.11.1"

        /** Порт SOCKS5-inbound по умолчанию, если занять свободный не удалось. */
        private const val VLESS_SOCKS_FALLBACK_PORT = 10808

        /**
         * Сколько ждём ответа от одного узла VLESS, прежде чем взять следующий.
         *
         * Замеры на тестовом устройстве: живой узел отвечает за 320–550 мс, мёртвый выбирает
         * бюджет целиком. Три секунды — с запасом на медленную мобильную сеть, и в них
         * укладывается на треть больше кандидатов за то же время.
         */
        private const val VLESS_CANDIDATE_PROBE_BUDGET_MS = 3_000L

        /**
         * Сколько ждём ответа узла в уже поднятой сессии.
         *
         * Здесь спешить некуда, а цена спешки высокая: не уложившаяся проба означает не
         * только «здоровье не подтверждено», но и отсутствие пинга на экране — публиковать
         * нечего. Живой узел из Сингапура даёт около двух секунд, поэтому четыре.
         */
        private const val VLESS_SESSION_PROBE_TIMEOUT_MS = 4_000

        /**
         * Бюджет пробы Opera в цикле удержания.
         *
         * Проба идёт наружу через сам прокси и служит двум целям: подтверждает живость
         * и задаёт потолок замера задержки. Прежние 1200 мс были меньше бюджета цикла
         * подключения (1400 мс), а подключиться Opera иначе как этой пробой не может:
         * CONNECT на 53-й порт прокси не пропускает, DNS через туннель не идёт, и
         * VALIDATED система не ставит. Выход, уложившийся в 1400 мс на подключении,
         * тут же начинал копить отказы. Две секунды снимают разрыв и не ломают
         * обнаружение мёртвого data-plane: худшая итерация 1500+1200+2000 = 4.7 с,
         * порог в восемь отказов даёт около 37 с против прежних 31.
         */
        private const val OPERA_SESSION_PROBE_TIMEOUT_MS = 2_000

        /**
         * Общий бюджет поиска живого узла, пока туннеля ещё нет.
         *
         * Ограничивает только холодный старт и только тогда, когда есть куда уйти:
         * без запасной цепочки сдача по таймеру означает не «попробуем другое», а
         * «выключаемся на середине списка». Поднятый туннель бюджетом не ограничен —
         * обрывать его нечем.
         */
        private const val VLESS_SEARCH_BUDGET_MS = 75_000L

        /**
         * Сколько кругов кандидатов получает MASQUE, выбранный вручную.
         *
         * Один. Круг перебирает все доступные адреса и порты; если ни один не дал связи,
         * повтор того же списка — это не новая попытка, а те же полминуты ожидания с тем
         * же исходом. Пользователю нужен ответ, а не бесконечная прокрутка счётчика:
         * выбранный протокол не подключился, и об этом надо сказать сразу.
         */
        private const val MASQUE_EXPLICIT_CYCLE_LIMIT = 1

        /**
         * Сессия короче этого не засчитывается за успех.
         *
         * Иначе узел, который подключается и сразу рвётся, давал бы перебору новый
         * круг после каждого срыва, и цикл крутился бы вечно на одном и том же месте.
         */
        private const val VLESS_SESSION_CREDIT_MS = 20_000L


        /** Метки транспорта для честного отображения в интерфейсе. */
        const val TRANSPORT_MASQUE = "MASQUE"
        const val TRANSPORT_VLESS = "VLESS"
        const val TRANSPORT_WARP = "WARP"
        const val TRANSPORT_OPERA = "OPERA"
        private const val BACKGROUND_HEARTBEAT_REQUEST_CODE = 4515
        private const val STOP_CLEANUP_CONFIRM_REQUEST_CODE = 4516
        private const val BACKGROUND_HEARTBEAT_INTERVAL_MS = 120_000L
        private const val BACKGROUND_HEARTBEAT_SCREEN_OFF_INTERVAL_MS = 45_000L
        private const val BACKGROUND_HEARTBEAT_CONNECTING_INTERVAL_MS = 30_000L
        private const val STABLE_LAST_SUCCESS_HOLD_MS = 30_000L
        private const val CONNECTED_WARP_HEALTH_WINDOW_SIZE = 8
        private const val CONNECTED_WARP_HEALTH_MIN_SAMPLES = 6
        private const val CONNECTED_WARP_CONSECUTIVE_FAILURE_LIMIT = 4
        private const val CONNECTED_WARP_HEALTH_FAILURE_PERCENT = 50
        private const val CONNECTED_WARP_HEALTH_GRACE_MS = 30_000L
        @Volatile
        private var lastStoppedStateCleanupAtMs = 0L
        @Volatile
        var currentState = STATE_STOPPED
        @Volatile
        var currentBackendLabel = BACKEND_WARP
    }

    @Volatile
    private var isUserStopped = false

    private val resourceConstrainedDevice by lazy(LazyThreadSafetyMode.NONE) {
        val activityManager = getSystemService(ActivityManager::class.java)
        val lowRam = activityManager?.isLowRamDevice ?: false
        val cpuCount = Runtime.getRuntime().availableProcessors()
        lowRam || isLegacy32BitDevice() || cpuCount <= 4
    }
    
    private val stopReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == "com.example.nova.STOP_VPN") {
                cleanupAndStop(manualStopRequested = true)
            }
        }
    }

    /**
     * Точка доступа и USB-модем не появляются в ConnectivityManager, поэтому обычные
     * колбэки о сменах сети про них молчат. Эти два широковещательных сообщения —
     * единственный сигнал, по которому можно узнать, что раздача включилась, и
     * переоткрыть прокси уже на её адресе.
     */
    private val tetherStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val action = intent?.action.orEmpty()
            if (action.isBlank()) return
            syncLocalAppProxy(reason = "tether-change")
        }
    }

    private val deviceWakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    refreshBackgroundHeartbeatForDeviceState()
                    refreshConnectedScreenOffWakeLock()
                }
                Intent.ACTION_SCREEN_ON -> {
                    requestFreshProbeAfterWake("screen-on")
                    refreshBackgroundHeartbeatForDeviceState()
                    refreshConnectedScreenOffWakeLock()
                }
                Intent.ACTION_USER_PRESENT -> {
                    requestFreshProbeAfterWake("user-present")
                    refreshBackgroundHeartbeatForDeviceState()
                    refreshConnectedScreenOffWakeLock()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        LogManager.setAppContext(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val filter = android.content.IntentFilter("com.example.nova.STOP_VPN")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stopReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(stopReceiver, filter)
            }
        }
        val wakeFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(deviceWakeReceiver, wakeFilter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(deviceWakeReceiver, wakeFilter)
        }
        val tetherFilter = android.content.IntentFilter().apply {
            addAction("android.net.conn.TETHER_STATE_CHANGED")
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(tetherStateReceiver, tetherFilter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(tetherStateReceiver, tetherFilter)
            }
        } catch (error: Exception) {
            LogManager.log("Не удалось подписаться на события раздачи: ${error.message}")
        }
        registerUnderlyingNetworkObserver()
        vpnConsistencyHandler.post(vpnConsistencyRunnable)
    }

    private fun startSafeServiceThread(name: String, block: () -> Unit): Thread {
        return Thread({
            try {
                block()
            } catch (t: Throwable) {
                LogManager.log("Поток $name завершился ошибкой: ${t.message}")
            }
        }, name).apply {
            isDaemon = true
            start()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogManager.log("onStartCommand: action=${intent?.action ?: "<null>"} startId=$startId")
        val clientData = ClientData(this)
        if (intent?.action == "STOP_VPN") {
            cleanupAndStop(manualStopRequested = true)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CONFIRM_STOP_CLEANUP) {
            return handleDelayedStopCleanup(clientData, startId)
        }

        if (intent?.action == ACTION_STOP_FOR_SOFT_RESTART) {
            cleanupAndStop(forceServiceTeardown = true)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_FORCE_HEALTH_RECHECK) {
            val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
            val persistedState = clientData.getServiceState()
            val currentVpn = findCurrentVpnNetwork(connectivityManager)
            val liveNovaVpn =
                currentVpn != null &&
                    isLikelyNovaVpnNetwork(connectivityManager, currentVpn)
            if (persistedState == STATE_STOPPED && clientData.getRestartSession() == null) {
                if (liveNovaVpn) {
                    LogManager.log(
                        "Пропускаем FORCE_HEALTH_RECHECK после ручного stop: " +
                            "в системе ещё висит stale VPN Nova, дожимаем cleanup отдельно."
                    )
                    scheduleStopCleanupConfirmation(250L)
                }
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            val hasLiveSession =
                isRunning ||
                    currentState == STATE_CONNECTED ||
                    currentState == STATE_CONNECTING ||
                    liveNovaVpn ||
                    persistedState == STATE_CONNECTED ||
                    persistedState == STATE_CONNECTING
            if (!hasLiveSession) {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            requestFreshProbeAfterWake(
                intent.getStringExtra(EXTRA_FORCE_HEALTH_RECHECK_REASON) ?: "foreground-resume"
            )
            return if (
                isRunning ||
                    currentState == STATE_CONNECTED ||
                    currentState == STATE_CONNECTING ||
                    liveNovaVpn
            ) {
                START_STICKY
            } else {
                START_NOT_STICKY
            }
        }

        cancelStopCleanupConfirmation()

        if (intent?.action == ACTION_RESET_WARP_REGISTRATION) {
            clientData.resetWarpStoredRegistrationIdentity()
            clientData.clearWarpFullCycleFailureState()
            LogManager.log(
                "Служебный reset WARP registration выполнен: cached bootstrap/config очищены, " +
                    "adaptive ranking сохранён."
            )
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP_WARP_CONFIG_DISCOVERY) {
            val existingSnapshot = ClientData(this).getWarpDiscoverySnapshot()
            invalidateConnectGeneration()
            warpConfigDiscoveryStopRequested.set(true)
            warpConfigDiscoveryRunning.set(false)
            val stoppedMessage = buildStoppedWarpDiscoveryMessage(existingSnapshot)
            broadcastWarpConfigDiscovery(
                running = false,
                foundCount = existingSnapshot?.foundCount ?: ClientData(this).let { data ->
                    data.getWarpVerifiedConfigs().count(data::isBundledSeed)
                },
                message = stoppedMessage,
            )
            currentAttemptOrdinal = 0
            currentAttemptTotal = 0
            setCurrentBackend(BACKEND_WARP)
            cleanupAndStop(
                preserveRestartSession = false,
                unexpectedDisconnect = false,
                forceServiceTeardown = true,
                manualStopRequested = true,
            )
            return START_NOT_STICKY
        }

        if (
            intent?.action != ACTION_REAPPLY_CURRENT_SESSION &&
            intent?.action != ACTION_STOP_FOR_SOFT_RESTART
        ) {
            clientData.clearSoftReapplyPending()
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        if (intent?.action == ACTION_REFRESH_NOTIFICATION) {
            return if (isRunning) START_STICKY else START_NOT_STICKY
        }

        if (intent?.action == ACTION_SYNC_LOCAL_PROXY) {
            syncLocalAppProxy(reason = "manual-sync")
            // Служба могла остаться в живых только ради раздачи. Раз её выключили, а
            // туннеля нет — держать foreground больше не за чем.
            if (
                !isRunning &&
                currentState != STATE_CONNECTED &&
                currentState != STATE_CONNECTING &&
                !clientData.isLocalProxyEnabled()
            ) {
                LogManager.log("Раздача выключена, VPN не активен — останавливаем службу.")
                finishForegroundShutdown()
                stopSelf()
                return START_NOT_STICKY
            }
            return if (isRunning || currentState == STATE_CONNECTED) START_STICKY else START_NOT_STICKY
        }

        if (intent?.action == ACTION_BACKGROUND_HEARTBEAT) {
            return handleBackgroundHeartbeat(clientData, startId)
        }

        if (intent?.action == ACTION_SWITCH_VLESS_PROFILE) {
            if (requestVlessProfileSwitchInPlace()) return START_STICKY
            // Перебор ещё не идёт — поднимаем сессию обычным путём, а следующий профиль
            // выбираем сами: экран живёт в другом процессе и порядок списка видит свой.
            clientData.nextVlessProfileLink()?.let(clientData::setVlessConfigLink)
            return if (reapplyCurrentPreferences(intent)) START_STICKY else START_NOT_STICKY
        }

        if (intent?.action == ACTION_REAPPLY_CURRENT_SESSION) {
            return if (reapplyCurrentPreferences(intent)) START_STICKY else START_NOT_STICKY
        }

        if (intent == null || intent.action == ACTION_RESTORE_LAST_SESSION) {
            val restartMode = if (intent?.action == ACTION_RESTORE_LAST_SESSION) {
                START_REDELIVER_INTENT
            } else {
                START_STICKY
            }
            return if (
                restorePersistedSession(
                    ignoreAutoReconnect = intent?.getBooleanExtra(EXTRA_IGNORE_AUTO_RECONNECT_ON_RESTORE, false) == true,
                    forceRestart = intent?.getBooleanExtra(EXTRA_FORCE_RESTART_ON_RESTORE, false) == true,
                )
            ) {
                restartMode
            } else {
                START_NOT_STICKY
            }
        }
        
        val privateKey = intent?.getStringExtra("PRIVATE_KEY")
        val ipv4 = intent?.getStringExtra("IPV4")
        val ipv6 = intent?.getStringExtra("IPV6")
        val peerPub = intent?.getStringExtra("PEER_PUB")
        val peerEndpoint = intent?.getStringExtra("PEER_ENDPOINT")
        val reserved = normalizeReservedValue(intent?.getStringExtra("RESERVED"))
        val savedPort = intent?.getIntExtra("PORT", -1) ?: -1
        val savedProto = intent?.getStringExtra("PROTOCOL") ?: "MASQUE"
        val operaOnlyStart = intent?.action == ACTION_START_OPERA_ONLY
        val warpBootstrapStart = intent?.action == ACTION_START_WARP_BOOTSTRAP
        val smartConnectStart = intent?.action == ACTION_CONNECT_SMART
        val diagnosticsStart = intent?.action == ACTION_RUN_WARP_DIAGNOSTICS
        val warpConfigDiscoveryStart = intent?.action == ACTION_START_WARP_CONFIG_DISCOVERY
        val warpNetworkAdaptationStart = intent?.action == ACTION_START_WARP_NETWORK_ADAPTATION
        val warpQualityDiagnosticsStart = intent?.action == ACTION_START_WARP_QUALITY_DIAGNOSTICS
        val regionPreference = intent?.getStringExtra(EXTRA_EXIT_REGION)

        if (intent != null && intent.action != ACTION_RESTORE_LAST_SESSION) {
            applyExplicitReapplyOverrides(intent, clientData)
        }

        if (warpConfigDiscoveryStart || warpNetworkAdaptationStart || warpQualityDiagnosticsStart) {
            if (warpConfigDiscoveryRunning.get()) {
                broadcastWarpConfigDiscovery(
                    running = true,
                    foundCount = 0,
                    message = when {
                        warpQualityDiagnosticsStart -> "Диагностика уже выполняется"
                        warpNetworkAdaptationStart -> "Адаптация уже выполняется"
                        else -> "Проверка уже выполняется"
                    },
                )
                return START_STICKY
            }
            isUserStopped = false
            explicitStopRequested = false
            warpConfigDiscoveryStopRequested.set(false)
            val workerName = when {
                warpQualityDiagnosticsStart -> "NovaWarpQualityDiagnostics"
                warpNetworkAdaptationStart -> "NovaWarpNetworkAdaptation"
                else -> "NovaWarpConfigDiscovery"
            }
            startSafeServiceThread(workerName) {
                startWarpConfigDiscovery(
                    regionPreference,
                    adaptToNetwork = warpNetworkAdaptationStart,
                    qualityDiagnostics = warpQualityDiagnosticsStart,
                )
            }
            isRunning = true
            return START_REDELIVER_INTENT
        }

        if (smartConnectStart || diagnosticsStart) {
            isUserStopped = false
            explicitStopRequested = false
            suppressSessionRestore = false
            operaFallbackActive = false
            novaCoreTunnelActive = false
            operaTunThread = null
            novaEngineThread = null
            setCurrentBackend(BACKEND_WARP)
            val requestedRegion = normalizeRegionPreference(
                regionPreference ?: ClientData(this).getExitRegionPreference()
            )
            val expectedBackendHint = if (shouldUseWarpTransport(requestedRegion)) {
                BACKEND_WARP
            } else {
                getOperaFallbackSequence(requestedRegion).firstOrNull()?.second?.let { "$BACKEND_OPERA-$it" }
                    ?: BACKEND_OPERA
            }
            if (adoptHealthyExistingVpnIfPresent(expectedBackendHint)) {
                isRunning = true
                return START_STICKY
            }
            val connectGenerationId = beginConnectGeneration(stopExisting = true)
            // Явный пуск отменяет отложенное добивание предыдущего stop: иначе оно
            // снесёт службу под уже начавшимся циклом.
            cancelStopCleanupConfirmation()
            startSafeServiceThread("NovaSmartConnect") {
                // Ждём в потоке, а не в onStartCommand: ожидание блокирующее, а
                // onStartCommand исполняется на main-потоке службы.
                if (!waitForPreviousCleanupIfNeeded(
                        connectGenerationId = connectGenerationId,
                        reason = "smart-connect",
                        maxWaitMs = 2_500L,
                        allowForcedRelease = false,
                    )
                ) {
                    logConnectAbortedBeforeStart("smart-connect", connectGenerationId)
                    return@startSafeServiceThread
                }
                startSmartConnection(
                    regionPreferenceOverride = regionPreference,
                    diagnosticsMode = diagnosticsStart,
                    connectGenerationId = connectGenerationId,
                    aggressiveFastStart = smartConnectStart,
                )
            }
            isRunning = true
            return START_REDELIVER_INTENT
        }

        if (operaOnlyStart) {
            isUserStopped = false
            explicitStopRequested = false
            suppressSessionRestore = false
            operaFallbackActive = false
            novaCoreTunnelActive = false
            operaTunThread = null
            novaEngineThread = null
            val requestedRegion = normalizeRegionPreference(
                regionPreference ?: ClientData(this).getExitRegionPreference()
            )
            val expectedBackendHint = getOperaFallbackSequence(requestedRegion).firstOrNull()?.second
                ?.let { "$BACKEND_OPERA-$it" }
                ?: BACKEND_OPERA
            // Метку ставим до первого broadcast, а не WARP «по умолчанию»: пока цикл
            // назывался WARP, интерфейс подставлял счётчик встроенных WARP-профилей —
            // те самые «1/50» перед настоящим «1/54» плана запуска Opera.
            setCurrentBackend(expectedBackendHint)
            if (adoptHealthyExistingVpnIfPresent(expectedBackendHint)) {
                isRunning = true
                return START_STICKY
            }
            ClientData(this).saveRestartSession(
                RestartSession(
                    kind = "opera",
                    region = regionPreference ?: ClientData(this).getExitRegionPreference(),
                )
            )
            val connectGenerationId = beginConnectGeneration(stopExisting = true)
            cancelStopCleanupConfirmation()
            // Ожидание стоит здесь, а не в теле configureAndStartOperaOnly: у неё
            // девять точек вызова, и среди них recovery-пути, которые намеренно
            // отказываются работать при активном cleanup.
            startSafeServiceThread("NovaOperaOnly") {
                if (!waitForPreviousCleanupIfNeeded(
                        connectGenerationId = connectGenerationId,
                        reason = "opera-only",
                        maxWaitMs = 2_500L,
                        allowForcedRelease = false,
                    )
                ) {
                    logConnectAbortedBeforeStart("opera-only", connectGenerationId)
                    return@startSafeServiceThread
                }
                configureAndStartOperaOnly(regionPreference, connectGenerationId)
            }
            isRunning = true
            return START_REDELIVER_INTENT
        }
             
        if (privateKey != null && ipv4 != null && ipv6 != null && peerPub != null && peerEndpoint != null) {
            isUserStopped = false
            explicitStopRequested = false
            suppressSessionRestore = false
            operaFallbackActive = false
            novaCoreTunnelActive = false
            operaTunThread = null
            novaEngineThread = null
            setCurrentBackend(BACKEND_WARP)
            if (adoptHealthyExistingVpnIfPresent(BACKEND_WARP)) {
                isRunning = true
                return START_STICKY
            }
            ClientData(this).saveRestartSession(
                RestartSession(
                    kind = "warp",
                    region = regionPreference ?: ClientData(this).getExitRegionPreference(),
                    privateKey = privateKey,
                    ipv4 = ipv4,
                    ipv6 = ipv6,
                    peerPublicKey = peerPub,
                    peerEndpoint = peerEndpoint,
                    reserved = reserved,
                    savedPort = savedPort.takeIf { it in 1..65535 },
                    savedProto = savedProto,
                )
            )
            val connectGenerationId = beginConnectGeneration(stopExisting = true)
            // Ожидание cleanup у этого пути уже есть внутри configureAndStartVpn —
            // не хватало только отмены отложенного добивания предыдущего stop.
            cancelStopCleanupConfirmation()
            if (warpBootstrapStart) {
                operaBootstrapWarpGenerationId = connectGenerationId
            }
            startSafeServiceThread("NovaWarpConnect") {
                configureAndStartVpn(
                    privateKey,
                    ipv4,
                    ipv6,
                    peerPub,
                    peerEndpoint,
                    reserved,
                    savedPort,
                    savedProto,
                    regionPreference,
                    allowOperaFallbackOverride = if (warpBootstrapStart) false else null,
                    preferWarpOnlySticky = warpBootstrapStart,
                    diagnosticsMode = false,
                    aggressiveFastStart = warpBootstrapStart,
                    connectGenerationId = connectGenerationId,
                )
            }
            isRunning = true
            return START_REDELIVER_INTENT
        }
        
        stopSelf()
        return START_NOT_STICKY 
    }

    private fun configureAndStartOperaOnly(regionPreferenceOverride: String?, connectGenerationId: Int) {
        try {
            if (!isConnectGenerationCurrent(connectGenerationId)) {
                logConnectAbortedBeforeStart("opera-only", connectGenerationId)
                return
            }
            if (!ensureFreshTransportState(connectGenerationId, "opera-only")) return
            suppressSessionRestore = false
            val clientData = ClientData(this)
            val regionPreference = normalizeRegionPreference(
                regionPreferenceOverride ?: clientData.getExitRegionPreference()
            )
            if (!OperaProxyManager.isSupportedOnDevice(this)) {
                LogManager.log(
                    "Opera-only запуск невозможен: для ABI ${Build.SUPPORTED_ABIS.joinToString()} нет native-библиотек tun2proxy/operaproxy."
                )
                isRunning = false
                setCurrentBackend(BACKEND_WARP)
                broadcastState(STATE_STOPPED)
                stopSelf()
                return
            }
            val operaTargets = getOperaFallbackSequence(regionPreference)
            if (operaTargets.isEmpty()) {
                LogManager.log("Opera-only запуск невозможен: для текущего региона Opera отключена.")
                isRunning = false
                setCurrentBackend(BACKEND_WARP)
                broadcastState(STATE_STOPPED)
                stopSelf()
                return
            }
            val expectedBackendHint = "${BACKEND_OPERA}-${operaTargets.first().second}"
            if (adoptHealthyExistingVpnIfPresent(expectedBackendHint)) {
                return
            }
            // beginConnectGeneration стирает метку транспорта, а первый broadcast идёт
            // уже отсюда: без этой строки интерфейс несколько секунд считал фазу
            // неизвестной и показывал заглушку по списку встроенных WARP-профилей.
            setCurrentBackend(expectedBackendHint)
            broadcastState(STATE_CONNECTING)
            val result = runOperaFallbackUntilStable(clientData, operaTargets, connectGenerationId)
            if (
                result == OperaFallbackResult.FAILED &&
                tryStartOperaBootstrapViaWarp(clientData, regionPreference, connectGenerationId)
            ) {
                return
            }
            if (!isUserStopped && isConnectGenerationCurrent(connectGenerationId)) {
                if (
                    result == OperaFallbackResult.FAILED &&
                    scheduleOperaRetryInsteadOfStopping(
                        clientData = clientData,
                        connectGenerationId = connectGenerationId,
                        regionPreference = regionPreference,
                        reason = "Opera ${regionPreference.uppercase(Locale.US)} пока не подключилась"
                    )
                ) {
                    return
                }
                isRunning = false
                // Остановка выбранного региона — это остановка выбранного региона, а не
                // переход на WARP. Метка WARP в момент STOPPED и читалась как переход.
                setCurrentBackend(expectedBackendHint)
                broadcastState(STATE_STOPPED)
                stopSelf()
            }
        } catch (e: Exception) {
            LogManager.log("Критическая ошибка Opera-only запуска: ${e.message}")
            closeActiveInterface()
            val clientData = ClientData(this)
            val regionPreference = normalizeRegionPreference(
                regionPreferenceOverride ?: clientData.getExitRegionPreference()
            )
            if (
                scheduleOperaRetryInsteadOfStopping(
                    clientData = clientData,
                    connectGenerationId = connectGenerationId,
                    regionPreference = regionPreference,
                    reason = "Критическая ошибка Opera-only запуска: ${e.message}"
                )
            ) {
                return
            }
            isRunning = false
            setCurrentBackend(BACKEND_WARP)
            broadcastState(STATE_STOPPED)
            stopSelf()
        }
    }
    
    private fun broadcastState(state: String) {
        if (explicitStopRequested && state != STATE_STOPPED) {
            return
        }
        val clientData = ClientData(this)
        if (state == STATE_CONNECTED) {
            lastStoppedStateCleanupAtMs = 0L
            lastConnectedAtMs = SystemClock.elapsedRealtime()
            connectedHealthProbeFailures = 0
            clientData.clearSoftReapplyPending()
            clientData.clearTransientConnectingPending()
        } else if (state == STATE_STOPPED) {
            connectedHealthProbeFailures = 0
            clientData.clearTransportLatency()
            lastSuccessfulTunnelProbeAtMs = 0L
            lastSuccessfulTunnelProbeNetworkSignature = null
            lastSuccessfulTunnelProbeNetworkClass = null
            lastTransportFailureSignalAtMs = 0L
            lastTransportFailureSignature = null
            clearActiveWarpQualityTarget()
            currentTransportLabel = ""
            currentTransportNotice = ""
            clientData.setLastTransportNotice("")
        }
        currentState = state
        clientData.saveServiceState(
            state,
            currentBackendLabel,
            currentAttemptOrdinal,
            currentAttemptTotal,
            currentTransportLabel,
            currentTransportNotice,
        )
        if (state == STATE_CONNECTING || state == STATE_CONNECTED) {
            scheduleBackgroundKeepAlive(clientData, state)
        } else if (state == STATE_STOPPED) {
            cancelBackgroundKeepAlive()
        }
        refreshConnectedScreenOffWakeLock()
        if (state == STATE_CONNECTED) {
            refreshConnectedExitObservationAsync()
            scheduleWarpIdentityBackfill()
        }
        syncLocalAppProxy(reason = "state-$state")
        val intent = Intent(ACTION_VPN_STATE).apply {
            putExtra(EXTRA_STATE, state)
            putExtra(EXTRA_BACKEND, currentBackendLabel)
            putExtra(EXTRA_ATTEMPT_ORDINAL, currentAttemptOrdinal)
            putExtra(EXTRA_ATTEMPT_TOTAL, currentAttemptTotal)
            val maskHost = currentWarpMaskHost
                ?.takeIf { it.isNotBlank() }
                ?: clientData.getWarpTrafficMaskActiveHost().takeIf { it.isNotBlank() }
                ?: clientData.getTrafficMaskActiveHost().takeIf { it.isNotBlank() }
                ?: clientData.getTrafficMaskRecentProbeHost().takeIf { it.isNotBlank() }
            val maskPool = when {
                currentWarpMaskHost?.isNotBlank() == true -> ClientData.TRAFFIC_MASK_POOL_RUSSIA
                clientData.getWarpTrafficMaskActiveHost().isNotBlank() -> ClientData.TRAFFIC_MASK_POOL_RUSSIA
                else -> clientData.getTrafficMaskActivePool()
                    .ifBlank { clientData.getTrafficMaskRecentProbePool() }
            }
            putExtra(EXTRA_MASK_HOST, maskHost.orEmpty())
            putExtra(EXTRA_MASK_POOL, maskPool)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        requestTileRefresh(state)
    }

    private fun handleBackgroundHeartbeat(clientData: ClientData, startId: Int): Int {
        val persistedState = clientData.getServiceState()
        val activePersistedState = persistedState == STATE_CONNECTING || persistedState == STATE_CONNECTED
        val hasRestartSession = clientData.getRestartSession() != null
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        if (
            isUserStopped ||
            explicitStopRequested ||
            !activePersistedState ||
            !clientData.getAutoReconnect() ||
            !hasRestartSession
        ) {
            cancelBackgroundKeepAlive()
            if (!isRunning && currentState != STATE_CONNECTED && currentState != STATE_CONNECTING) {
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }

        scheduleBackgroundKeepAlive(clientData, persistedState)
        if (isRunning || currentState == STATE_CONNECTED || currentState == STATE_CONNECTING) {
            val currentVpn = findCurrentVpnNetwork(connectivityManager)
            if (!isDeviceInteractiveNow()) {
                if (currentVpn != null && isLikelyNovaVpnNetwork(connectivityManager, currentVpn)) {
                    connectedHealthProbeFailures = 0
                    return START_STICKY
                }
                LogManager.log("Фоновый heartbeat: системный VPN исчез во сне. Запускаем восстановление сеанса.")
                if (currentState != STATE_CONNECTING) {
                    broadcastState(STATE_CONNECTING)
                }
                val restored = restorePersistedSession(ignoreAutoReconnect = true, forceRestart = true)
                if (!restored) {
                    stopSelfResult(startId)
                }
                return if (restored) START_STICKY else START_NOT_STICKY
            }
            requestBackgroundHeartbeatCheck()
            return START_STICKY
        }

        if (findCurrentVpnNetwork(connectivityManager) != null) {
            setCurrentBackend(clientData.getServiceBackend().ifBlank { BACKEND_WARP })
            broadcastState(STATE_CONNECTED)
            if (isDeviceInteractiveNow()) {
                requestBackgroundHeartbeatCheck()
            }
            return START_STICKY
        }

        LogManager.log("Фоновый heartbeat: активный сеанс потерян, запускаем восстановление VPN.")
        broadcastState(STATE_CONNECTING)
        val restored = restorePersistedSession(ignoreAutoReconnect = true, forceRestart = true)
        if (!restored) {
            stopSelfResult(startId)
        }
        return if (restored) START_STICKY else START_NOT_STICKY
    }

    private fun requestBackgroundHeartbeatCheck() {
        val triggerReason = "background-heartbeat"
        val now = SystemClock.elapsedRealtime()
        if (
            lastAcceleratedRecoveryReason == triggerReason &&
            now - lastAcceleratedRecoveryAtMs < 2_000L
        ) {
            return
        }
        lastAcceleratedRecoveryReason = triggerReason
        lastAcceleratedRecoveryAtMs = now
        acquireRecoveryWakeLock(triggerReason, 6_000L)
        if (!isRunning || isUserStopped || explicitStopRequested || cleanupInProgress.get() || suppressSessionRestore || warpConfigDiscoveryRunning.get()) {
            return
        }
        if (currentState != STATE_CONNECTED && currentState != STATE_CONNECTING) {
            return
        }
        scheduleNetworkRecoveryCheck("background-heartbeat")
    }

    private fun scheduleStopCleanupConfirmation(delayMs: Long = 650L) {
        stopCleanupHandler.removeCallbacks(stopCleanupRunnable)
        stopCleanupHandler.postDelayed(
            stopCleanupRunnable,
            delayMs.coerceIn(150L, 1_200L),
        )
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            STOP_CLEANUP_CONFIRM_REQUEST_CODE,
            Intent(applicationContext, NovaVpnService::class.java).apply {
                action = ACTION_CONFIRM_STOP_CLEANUP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(150L) + 1_200L
        scheduleSafeInexactServiceAlarm(
            alarmManager = alarmManager,
            triggerAt = triggerAt,
            pendingIntent = pendingIntent,
            label = "stop-cleanup-confirmation",
        )
    }

    private fun scheduleSafeInexactServiceAlarm(
        alarmManager: AlarmManager,
        triggerAt: Long,
        pendingIntent: PendingIntent,
        label: String,
    ): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent,
                )
            } else {
                alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
            }
            true
        } catch (e: SecurityException) {
            LogManager.log(
                "Не удалось запланировать $label через AlarmManager: нет права на alarm (${e.message}). " +
                    "Продолжаем без падения сервиса."
            )
            false
        } catch (e: Exception) {
            LogManager.log("Не удалось запланировать $label через AlarmManager: ${e.message}")
            false
        }
    }

    private fun cancelStopCleanupConfirmation() {
        stopCleanupHandler.removeCallbacks(stopCleanupRunnable)
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            STOP_CLEANUP_CONFIRM_REQUEST_CODE,
            Intent(applicationContext, NovaVpnService::class.java).apply {
                action = ACTION_CONFIRM_STOP_CLEANUP
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun handleDelayedStopCleanup(clientData: ClientData, startId: Int): Int {
        performDelayedStopCleanup(clientData, source = "alarm", stopStartId = startId)
        return START_NOT_STICKY
    }

    private fun performDelayedStopCleanup(
        clientData: ClientData,
        source: String,
        stopStartId: Int? = null,
    ) {
        cancelStopCleanupConfirmation()
        // Отложенное добивание планируется при остановке, но срабатывает через сотни
        // миллисекунд — за это время пользователь успевает нажать «Пуск». Голый
        // stopSelf() в этот момент убивал уже начавшийся цикл, и подключение молча не
        // происходило. stopSelfResult гасит службу только если это последний startId.
        if (isRunning || currentState == STATE_CONNECTING || currentState == STATE_CONNECTED) {
            LogManager.log(
                "Отложенное добивание stop ($source) отменено: уже идёт новый цикл подключения."
            )
            stopStartId?.let { stopSelfResult(it) }
            return
        }
        val persistedState = clientData.getServiceState()
        if (persistedState != STATE_STOPPED || clientData.getRestartSession() != null) {
            stopStartId?.let { stopSelfResult(it) }
            return
        }
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val currentVpn = findCurrentVpnNetwork(connectivityManager)
        if (currentVpn != null && isLikelyNovaVpnNetwork(connectivityManager, currentVpn)) {
            LogManager.log(
                "После ручного stop stale VPN Nova всё ещё виден в системе. " +
                    "Выполняем delayed synthetic detach ($source), чтобы убрать ключ и системный VPN."
            )
            forceDetachVpnStack()
            try {
                Thread.sleep(220L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (findCurrentVpnNetwork(connectivityManager) != null) {
                LogManager.log(
                    "После delayed detach ($source) системный VPN Nova ещё виден. " +
                        "Повторяем сброс один раз."
                )
                forceDetachVpnStack()
            }
        }
        stopStartId?.let { stopSelfResult(it) } ?: stopSelf()
    }

    private fun scheduleBackgroundKeepAlive(clientData: ClientData, state: String = currentState) {
        if (state != STATE_CONNECTING && state != STATE_CONNECTED) return
        if (!clientData.getAutoReconnect() || clientData.getRestartSession() == null) return
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            BACKGROUND_HEARTBEAT_REQUEST_CODE,
            Intent(applicationContext, NovaVpnService::class.java).apply {
                action = ACTION_BACKGROUND_HEARTBEAT
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val triggerAt = SystemClock.elapsedRealtime() + currentBackgroundHeartbeatIntervalMs(state)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent,
            )
        } else {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent)
        }
    }

    private fun currentBackgroundHeartbeatIntervalMs(state: String): Long {
        val interactive = isDeviceInteractiveNow()
        return when (state) {
            STATE_CONNECTING -> if (interactive) 45_000L else BACKGROUND_HEARTBEAT_CONNECTING_INTERVAL_MS
            STATE_CONNECTED -> if (interactive) BACKGROUND_HEARTBEAT_INTERVAL_MS else BACKGROUND_HEARTBEAT_SCREEN_OFF_INTERVAL_MS
            else -> BACKGROUND_HEARTBEAT_INTERVAL_MS
        }
    }

    private fun refreshBackgroundHeartbeatForDeviceState() {
        val clientData = ClientData(this)
        val persistedState = clientData.getServiceState()
        val activeState =
            when {
                currentState == STATE_CONNECTED || currentState == STATE_CONNECTING -> currentState
                persistedState == STATE_CONNECTED || persistedState == STATE_CONNECTING -> persistedState
                else -> STATE_STOPPED
            }
        if (activeState == STATE_STOPPED) {
            cancelBackgroundKeepAlive()
            return
        }
        scheduleBackgroundKeepAlive(clientData, activeState)
    }

    private fun cancelBackgroundKeepAlive() {
        val alarmManager = getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            BACKGROUND_HEARTBEAT_REQUEST_CODE,
            Intent(applicationContext, NovaVpnService::class.java).apply {
                action = ACTION_BACKGROUND_HEARTBEAT
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun publishConnectingAttemptProgress(
        clientData: ClientData,
        ordinal: Int,
        total: Int,
        backendLabel: String = currentBackendLabel,
    ) {
        val normalizedTotal = total.coerceIn(1, 240)
        val cachedTotal = clientData.getCachedConnectAttemptTotal(backendLabel, currentTransportLabel)
        currentAttemptTotal = maxOf(normalizedTotal, cachedTotal)
        currentAttemptOrdinal = ordinal.coerceIn(0, currentAttemptTotal)
        clientData.rememberConnectAttemptTotal(currentAttemptTotal, backendLabel, currentTransportLabel)
        setCurrentBackend(backendLabel)
        broadcastState(STATE_CONNECTING)
    }

    private fun requestTileRefresh(state: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val component = android.content.ComponentName(this, NovaTileService::class.java)
        val request = Runnable {
            android.service.quicksettings.TileService.requestListeningState(this, component)
        }
        tileRefreshHandler.removeCallbacksAndMessages(null)
        request.run()
        when (state) {
            STATE_STOPPED -> {
                tileRefreshHandler.postDelayed(request, 450L)
                tileRefreshHandler.postDelayed(request, 1400L)
                tileRefreshHandler.postDelayed(request, 2600L)
            }
            STATE_CONNECTING -> {
                tileRefreshHandler.postDelayed(request, 300L)
                tileRefreshHandler.postDelayed(request, 900L)
                tileRefreshHandler.postDelayed(request, 1800L)
            }
            STATE_CONNECTED -> {
                tileRefreshHandler.postDelayed(request, 250L)
                tileRefreshHandler.postDelayed(request, 750L)
                tileRefreshHandler.postDelayed(request, 1600L)
            }
        }
    }

    private fun syncLocalAppProxy(reason: String) {
        val clientData = ClientData(this)
        val backend = currentBackendLabel.ifBlank { clientData.getServiceBackend() }
        val state = currentState.ifBlank { clientData.getServiceState() }
        LocalAppProxyManager.sync(
            context = this,
            backendLabel = backend,
            serviceState = state,
            logger = { message -> LogManager.log("[$reason] $message") },
        )
    }

    private fun setCurrentBackend(label: String) {
        currentBackendLabel = label
        val normalized = label.trim().uppercase(Locale.ROOT)
        if (!normalized.startsWith(BACKEND_WARP)) {
            clearActiveWarpQualityTarget()
        }
        // Opera не проходит через общий перебор попыток, поэтому её транспорт
        // фиксируем здесь — иначе от предыдущей фазы остался бы MASQUE.
        if (normalized.startsWith(BACKEND_OPERA)) {
            currentTransportLabel = TRANSPORT_OPERA
        }
    }

    /**
     * Сохраняет пояснение о подмене транспорта и сразу переиздаёт состояние,
     * чтобы оно попало в файл: интерфейс живёт в другом процессе и читает
     * именно файл.
     */
    private fun publishTransportNotice(clientData: ClientData, text: String) {
        val normalized = text.trim()
        if (currentTransportNotice == normalized) return
        currentTransportNotice = normalized
        clientData.setLastTransportNotice(normalized)
        if (currentState.isNotBlank()) {
            clientData.saveServiceState(
                currentState,
                currentBackendLabel,
                currentAttemptOrdinal,
                currentAttemptTotal,
                currentTransportLabel,
                normalized,
            )
        }
    }

    /**
     * Транспорт попытки определяется движком её режима — единственный признак,
     * по которому MASQUE отличим от WireGuard/AWG внутри одного бэкенда WARP.
     */
    private fun setCurrentTransportForAttempt(attempt: ConnectionAttempt) {
        val label = if (attempt.mode.engine.equals("masque", ignoreCase = true)) {
            TRANSPORT_MASQUE
        } else {
            TRANSPORT_WARP
        }
        if (currentTransportLabel != label) {
            currentTransportLabel = label
        }
        // Запоминаем узел попытки, чтобы было к чему привязать замер
        // перерукопожатий. Замер идёт только в состоянии CONNECTED, а последняя
        // выставленная попытка к этому моменту и есть удачная.
        activeEndpointHost = attempt.endpointHost
        activeEndpointPort = attempt.port
    }

    /**
     * Отдаёт замер окна в оценку узла.
     *
     * До этого частоту пересборки сессии не видел никто: ранжирование смотрит на
     * пинг и на факт подключения, поэтому узел, который не удерживает сессию,
     * считался хорошим и оставался наверху очереди. Замер 2026-08-10 показал
     * разброс между узлами вдвое-вчетверо при одинаковых параметрах AWG.
     */
    private fun recordTunnelRekeyChurnForEndpoint(rekeys: Int) {
        val host = activeEndpointHost.trim()
        val port = activeEndpointPort
        if (host.isBlank() || port !in 1..65535) return
        runCatching { ClientData(this).recordWarpConfigChurn(host, port, rekeys) }
            .onFailure { LogManager.log("Не удалось записать churn узла $host:$port: ${it.message}") }
    }

    /**
     * Отдаёт замер удержания окна в оценку узла.
     *
     * Дешёвая половина той же работы, что делает [recordTunnelRekeyChurnForEndpoint]:
     * churn меряется двухминутными окнами на живой сессии, а этот замер снимается
     * с двадцатисекундного окна адаптации, которое и так держится на каждом
     * профиле. Ради него ничего не удлиняется — считается тишина между уже
     * идущими пробами связности.
     *
     * Непоказательное окно не записывается, но и не молчит: пустой замер
     * неотличим от «правка не подействовала», и один прогон на этом уже потерян.
     */
    private fun recordWarpHoldWindow(
        clientData: ClientData,
        attempt: ConnectionAttempt,
        window: SessionHoldMetric.Window,
    ) {
        val label = "${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}"
        if (!window.representative) {
            LogManager.log(
                "Удержание $label не замерено: ${window.rejectionReason}. " +
                    "Проб ${window.probeCount}, окно ${window.spanMs} мс."
            )
            return
        }
        val recorded = runCatching {
            clientData.recordWarpConfigHoldWindow(
                host = attempt.endpointHost,
                port = attempt.port,
                window = window,
            )
        }.onFailure {
            LogManager.log("Не удалось записать удержание узла $label: ${it.message}")
        }.getOrDefault(false)
        if (!recorded) {
            LogManager.log(
                "Удержание $label не записано: узла нет в списке проверенных конфигураций."
            )
            return
        }
        LogManager.log(
            "Удержание $label: тишина ${window.worstStallMs} мс за окно ${window.spanMs} мс, " +
                "проб ${window.probeCount}. " +
                when {
                    window.worstStallMs <= SessionHoldMetric.STEADY_MAX_STALL_MS ->
                        "Обратный поток не пропадал."
                    window.worstStallMs <= SessionHoldMetric.SHAKY_MAX_STALL_MS ->
                        "Обратный поток проседал."
                    else ->
                        "Обратный поток пропадал надолго — узел уходит вниз очереди."
                }
        )
    }

    private fun beginConnectGeneration(stopExisting: Boolean = true): Int {
        val generationId = connectGeneration.incrementAndGet()
        lastStoppedStateCleanupAtMs = 0L
        // Новый цикл ничего ещё не пробовал: и транспорт, и пояснение о подмене
        // транспорта от прошлого цикла к нему не относятся.
        currentTransportLabel = ""
        currentTransportNotice = ""
        runCatching { ClientData(this).setLastTransportNotice("") }
        clearPreparedTransportState()
        clearCurrentCycleReconnectHints()
        connectedHealthProbeFailures = 0
        resetConnectedWarpHealthWindow()
        lastTransportFailureSignalAtMs = 0L
        lastTransportFailureSignature = null
        operaBadGatewayWindowStartedAtMs = 0L
        operaBadGatewayBurstCount = 0
        reconnectingForNetworkChange = false
        clearActiveWarpQualityTarget()
        pendingNetworkRecoveryReason = null
        networkRecoveryHandler.removeCallbacks(networkRecoveryRunnable)
        manualWarpProfileSwitchTargetKey = null
        if (stopExisting) {
            stopNovaCoreEngine(joinTimeoutMs = 1200L)
            stopOperaFallback(joinTimeoutMs = 1200L, stopProxyManager = true)
            closeActiveInterface()
            markTransportStatePrepared(generationId)
        }
        return generationId
    }

    private fun rememberActiveWarpQualityTarget(
        attempt: ConnectionAttempt,
        strategyScope: String,
    ) {
        if (!currentBackendLabel.trim().uppercase(Locale.ROOT).startsWith(BACKEND_WARP)) return
        activeWarpQualityTarget = ActiveWarpQualityTarget(
            engine = attempt.mode.engine.ifBlank { "wireguard" },
            mode = attempt.mode.name,
            host = attempt.endpointHost.trim().removePrefix("[").removeSuffix("]"),
            port = attempt.port,
            endpointSource = normalizeVerifiedConfigSource(attempt.endpointSource),
            rawConfig = buildWarpConfigDescription(attempt),
            scope = strategyScope.ifBlank { "default" },
            importedConfigHost = attempt.importedConfigHost,
            preferImportedRawIdentity = attempt.mode.preferImportedRawIdentity,
        )
        activeWarpQualityTargetDemoted = false
        if (attempt.importedConfigHost != null && attempt.mode.preferImportedRawIdentity) {
            lastImportedExactTrafficProofAtMs = 0L
            lastImportedExactObservedStats = null
        }
    }

    private fun clearActiveWarpQualityTarget() {
        activeWarpQualityTarget = null
        activeWarpQualityTargetDemoted = false
        lastImportedExactTrafficProofAtMs = 0L
        lastImportedExactObservedStats = null
    }

    private fun markActiveWarpQualityTargetDegraded(
        clientData: ClientData,
        reason: String,
        probeCount: Int? = null,
        pingSuccesses: Int? = null,
        avgPingMs: Double? = null,
    ) {
        val target = activeWarpQualityTarget ?: return
        if (activeWarpQualityTargetDemoted) return
        activeWarpQualityTargetDemoted = true
        val safeProbeCount = probeCount?.coerceAtLeast(1) ?: 6
        val safePingSuccesses = pingSuccesses?.coerceIn(0, safeProbeCount) ?: 1
        val safeAvgPingMs = avgPingMs?.takeIf { it.isFinite() && it > 0.0 }
            ?: if (resourceConstrainedDevice) 520.0 else 460.0
        if (safePingSuccesses > 0) {
            clientData.recordWarpVerifiedDegradedQualityResult(
                engine = target.engine,
                mode = target.mode,
                host = target.host,
                port = target.port,
                probeCount = safeProbeCount,
                pingSuccesses = safePingSuccesses,
                avgPingMs = safeAvgPingMs,
                endpointSource = target.endpointSource,
                rawConfig = target.rawConfig,
                scope = target.scope,
            )
        } else {
            clientData.recordWarpVerifiedQualityResult(
                engine = target.engine,
                mode = target.mode,
                host = target.host,
                port = target.port,
                success = false,
                probeCount = safeProbeCount,
                pingSuccesses = 0,
                avgPingMs = 0.0,
                endpointSource = target.endpointSource,
                rawConfig = target.rawConfig,
                scope = target.scope,
            )
        }
        val activeSni = normalizeRuntimeTrafficMaskHost(currentWarpMaskHost)
        rememberTransientDegradedWarpProfile(
            engine = target.engine,
            mode = target.mode,
            host = target.host,
            port = target.port,
            cooldownMs = 2L * 60L * 1000L,
        )
        clientData.markWarpAttemptCooldown(
            engine = target.engine,
            mode = target.mode,
            host = target.host,
            port = target.port,
            preferredSni = null,
            cooldownMs = 10L * 60L * 1000L,
        )
        if (activeSni.isNotBlank()) {
            clientData.markWarpAttemptCooldown(
                engine = target.engine,
                mode = target.mode,
                host = target.host,
                port = target.port,
                preferredSni = activeSni,
                cooldownMs = 10L * 60L * 1000L,
            )
        }
        clientData.clearLastSuccessIfMatches(
            mode = target.mode,
            host = target.host,
            port = target.port,
        )
        LogManager.log(
            "Текущий WARP-профиль ${target.mode}@${target.host}:${target.port} " +
                "помечен как деградировавший ($reason), поставлен на cooldown и смещён ниже рабочих конфигураций."
        )
    }

    private fun isDegradedWarpQualitySample(
        probeCount: Int,
        pingSuccesses: Int,
        avgPingMs: Double,
    ): Boolean {
        if (probeCount <= 0) return false
        if (pingSuccesses <= 0) return true
        val coveragePercent = pingSuccesses * 100 / probeCount.coerceAtLeast(1)
        val minCoveragePercent = if (resourceConstrainedDevice) 45 else 60
        val highLatencyThresholdMs = if (resourceConstrainedDevice) 520.0 else 420.0
        val fullCoverageLatencyThresholdMs = if (resourceConstrainedDevice) 1_350.0 else 1_100.0
        if (coveragePercent >= 100) {
            return avgPingMs.isFinite() && avgPingMs > 0.0 && avgPingMs >= fullCoverageLatencyThresholdMs
        }
        return coveragePercent < minCoveragePercent ||
            (avgPingMs.isFinite() && avgPingMs > 0.0 && avgPingMs >= highLatencyThresholdMs)
    }

    private fun shouldForceWarpQualityRecovery(
        probeCount: Int,
        pingSuccesses: Int,
        avgPingMs: Double,
    ): Boolean {
        return false
    }

    private fun resetConnectedWarpHealthWindow() {
        synchronized(connectedWarpHealthWindow) {
            connectedWarpHealthWindow.clear()
            connectedWarpHealthWindowFailures = 0
            connectedWarpHealthConsecutiveFailures = 0
        }
    }

    private fun recordConnectedWarpHealthProbe(success: Boolean): ConnectedWarpHealthWindowSnapshot {
        synchronized(connectedWarpHealthWindow) {
            if (connectedWarpHealthWindow.size >= CONNECTED_WARP_HEALTH_WINDOW_SIZE) {
                val removed = connectedWarpHealthWindow.removeFirst()
                if (!removed) {
                    connectedWarpHealthWindowFailures = (connectedWarpHealthWindowFailures - 1).coerceAtLeast(0)
                }
            }
            connectedWarpHealthWindow.addLast(success)
            if (success) {
                connectedWarpHealthConsecutiveFailures = 0
            } else {
                connectedWarpHealthWindowFailures += 1
                connectedWarpHealthConsecutiveFailures += 1
            }
            val samples = connectedWarpHealthWindow.size
            val failures = connectedWarpHealthWindowFailures.coerceIn(0, samples)
            return ConnectedWarpHealthWindowSnapshot(
                samples = samples,
                successes = samples - failures,
                failures = failures,
                consecutiveFailures = connectedWarpHealthConsecutiveFailures,
            )
        }
    }

    private fun shouldRecoverFromConnectedWarpHealthWindow(
        snapshot: ConnectedWarpHealthWindowSnapshot,
    ): Boolean {
        if (snapshot.samples <= 0) return false
        val connectedAgeMs = if (lastConnectedAtMs > 0L) {
            SystemClock.elapsedRealtime() - lastConnectedAtMs
        } else {
            Long.MAX_VALUE
        }
        if (connectedAgeMs < CONNECTED_WARP_HEALTH_GRACE_MS) return false
        if (snapshot.consecutiveFailures >= CONNECTED_WARP_CONSECUTIVE_FAILURE_LIMIT) return true
        if (snapshot.samples < CONNECTED_WARP_HEALTH_MIN_SAMPLES) return false
        val failurePercent = snapshot.failures * 100 / snapshot.samples.coerceAtLeast(1)
        return failurePercent >= CONNECTED_WARP_HEALTH_FAILURE_PERCENT
    }

    private fun maybeRecoverDegradedConnectedWarpWindow(
        clientData: ClientData,
        snapshot: ConnectedWarpHealthWindowSnapshot,
        reason: String,
    ): Boolean {
        return false
    }

    private fun currentConnectionUsesImportedExactAwg(): Boolean {
        val active = activeWarpQualityTarget ?: return false
        return active.importedConfigHost != null &&
            active.preferImportedRawIdentity &&
            active.engine.equals("wireguard", ignoreCase = true)
    }

    private fun noteImportedExactAwgTrafficProof(stats: TunnelStats? = null) {
        if (!currentConnectionUsesImportedExactAwg()) return
        lastImportedExactTrafficProofAtMs = SystemClock.elapsedRealtime()
        if (stats != null) {
            lastImportedExactObservedStats = stats
        }
    }

    private fun hasRecentImportedExactAwgTrafficProof(
        windowMs: Long = if (isDeviceInteractiveNow()) 60_000L else 90_000L,
    ): Boolean {
        if (!currentConnectionUsesImportedExactAwg()) return false
        val lastProofAt = lastImportedExactTrafficProofAtMs
        return lastProofAt > 0L && SystemClock.elapsedRealtime() - lastProofAt <= windowMs
    }

    private fun observeImportedExactAwgTrafficProof(): Boolean {
        if (!currentConnectionUsesImportedExactAwg()) return false
        val currentStats = readTunnelStats()
        val previousStats = lastImportedExactObservedStats
        lastImportedExactObservedStats = currentStats
        val nowSec = System.currentTimeMillis() / 1000L
        val handshakeFresh = currentStats.lastHandshakeTimeSec > 0L &&
            (nowSec - currentStats.lastHandshakeTimeSec) in 0L..180L
        val trafficAdvanced = previousStats != null &&
            (
                currentStats.rxBytes > previousStats.rxBytes ||
                    currentStats.txBytes > previousStats.txBytes
                ) &&
            currentStats.rxBytes > 0L &&
            currentStats.txBytes > 0L
        val substantialTraffic = currentStats.rxBytes >= 8_192L && currentStats.txBytes >= 4_096L
        if (handshakeFresh && (trafficAdvanced || substantialTraffic)) {
            lastImportedExactTrafficProofAtMs = SystemClock.elapsedRealtime()
            return true
        }
        return handshakeFresh && hasRecentImportedExactAwgTrafficProof()
    }

    private fun shouldAbortConnectWork(connectGenerationId: Int? = null): Boolean {
        if (isUserStopped || explicitStopRequested || cleanupInProgress.get()) {
            return true
        }
        return connectGenerationId?.let { !isConnectGenerationCurrent(it) } ?: false
    }

    private fun markTransportStatePrepared(generationId: Int) {
        preparedTransportGenerationId = generationId
        preparedTransportStateAtMs = SystemClock.elapsedRealtime()
    }

    private fun clearPreparedTransportState() {
        preparedTransportGenerationId = -1
        preparedTransportStateAtMs = 0L
    }

    private fun hasFreshPreparedTransportState(generationId: Int): Boolean {
        if (preparedTransportGenerationId != generationId) return false
        val preparedAt = preparedTransportStateAtMs
        return preparedAt > 0L && SystemClock.elapsedRealtime() - preparedAt <= 20_000L
    }

    private fun ensureFreshTransportState(
        connectGenerationId: Int,
        reason: String,
    ): Boolean {
        if (!isConnectGenerationCurrent(connectGenerationId)) {
            // Пишем только про явные пуски: сюда же приходят ротация профилей, повторы
            // и восстановление после смены сети — их отсечки залили бы журнал.
            if (reason in EXPLICIT_CONNECT_REASONS) {
                logConnectAbortedBeforeStart("$reason/transport", connectGenerationId)
            }
            return false
        }
        if (hasFreshPreparedTransportState(connectGenerationId)) {
            LogManager.log(
                "Транспорт уже очищен для текущего connect-сеанса. " +
                    "Повторную зачистку пропускаем: $reason"
            )
            return true
        }
        LogManager.log("Готовим чистое транспортное состояние перед новым циклом: $reason")
        stopNovaCoreEngine(joinTimeoutMs = 1200L, allowBlockingWait = true)
        stopOperaFallback(
            joinTimeoutMs = 1200L,
            stopProxyManager = true,
            allowBlockingWait = true,
        )
        runCatching { XrayBridge.stop() }
        closeActiveInterface()
        markTransportStatePrepared(connectGenerationId)
        return isConnectGenerationCurrent(connectGenerationId)
    }

    /**
     * @param allowForcedRelease снимать зависший guard силой. Для явного пуска это
     * опасно: guard может держаться и из-за исключения в cleanup, и тогда форс просто
     * запустит цикл поверх недоразобранного стека. Явные пути ждут и честно
     * отказываются, а guard от исключений защищён try/finally в [cleanupAndStop].
     */
    private fun waitForPreviousCleanupIfNeeded(
        connectGenerationId: Int,
        reason: String,
        maxWaitMs: Long = 1_600L,
        allowForcedRelease: Boolean = true,
    ): Boolean {
        if (!cleanupInProgress.get()) {
            return connectGeneration.get() == connectGenerationId && !explicitStopRequested
        }
        LogManager.log(
            "Новый явный запуск пришёл во время cleanup предыдущего stop. " +
                "Ждём завершения cleanup: $reason"
        )
        val deadline = SystemClock.elapsedRealtime() + maxWaitMs.coerceAtLeast(200L)
        while (cleanupInProgress.get() && SystemClock.elapsedRealtime() < deadline) {
            if (connectGeneration.get() != connectGenerationId) return false
            try {
                Thread.sleep(40L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        if (
            allowForcedRelease &&
            cleanupInProgress.get() &&
            !explicitStopRequested &&
            connectGeneration.get() == connectGenerationId
        ) {
            LogManager.log(
                "Cleanup предыдущего stop не отпустил guard вовремя. " +
                    "Снимаем cleanup-guard и продолжаем новый connect-сеанс: $reason"
            )
            cleanupInProgress.set(false)
        }
        return !cleanupInProgress.get() && connectGeneration.get() == connectGenerationId && !explicitStopRequested
    }

    /**
     * Объясняет, почему цикл подключения не начался.
     *
     * Отсечки до старта были молчаливыми: пользователь нажимал «Пуск», в журнале не
     * появлялось ни строки, и понять, что запуск съеден чужим cleanup, было нельзя.
     */
    private fun logConnectAbortedBeforeStart(reason: String, connectGenerationId: Int) {
        LogManager.log(
            "Цикл подключения ($reason) прерван до старта: " +
                "поколение=${connectGeneration.get()} ожидалось=$connectGenerationId " +
                "cleanupInProgress=${cleanupInProgress.get()} " +
                "explicitStopRequested=$explicitStopRequested isUserStopped=$isUserStopped"
        )
    }

    private fun invalidateConnectGeneration() {
        connectGeneration.incrementAndGet()
        clearPreparedTransportState()
        connectedHealthProbeFailures = 0
        resetConnectedWarpHealthWindow()
    }

    /**
     * Просит основной процесс повторить запуск, когда обречённый `:vpn` умрёт.
     *
     * Обещание «Android перезапустит службу» на устройстве не выполняется вовремя:
     * после падения ActivityManager пишет `Scheduling restart of crashed service ... in
     * 51242ms`, и нажатый «Пуск» оживает только через минуту. Явный
     * `startForegroundService` из живого основного процесса эту задержку снимает — в том
     * же журнале процесс поднимался через 30 мс после такого запуска.
     *
     * Отправляем до смерти: широковещание уходит через system_server, и выживание
     * отправителя ему уже не нужно.
     */
    private fun requestRestartFromMainProcess() {
        runCatching {
            sendBroadcast(
                Intent(ACTION_VPN_PROCESS_DOOMED).apply { setPackage(packageName) }
            )
        }.onFailure {
            LogManager.log(
                "Не удалось позвать основной процесс на перезапуск: ${it.message}. " +
                    "Остаётся перезапуск средствами Android с его задержкой."
            )
        }
    }

    private fun isConnectGenerationCurrent(connectGenerationId: Int): Boolean {
        return connectGeneration.get() == connectGenerationId &&
            !cleanupInProgress.get() &&
            !explicitStopRequested
    }

    private fun markSuccessfulTunnelProbe() {
        lastSuccessfulTunnelProbeAtMs = SystemClock.elapsedRealtime()
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val selectedUnderlying = selectUnderlyingNetwork(connectivityManager)
        lastSuccessfulTunnelProbeNetworkSignature = buildUnderlyingNetworkSignature(
            connectivityManager,
            selectedUnderlying,
        )
        lastSuccessfulTunnelProbeNetworkClass = buildUnderlyingNetworkClass(
            connectivityManager,
            selectedUnderlying,
        )
        lastTransportFailureSignalAtMs = 0L
        lastTransportFailureSignature = null
        requireFreshTunnelProbeUntilMs = 0L
        if (currentBackendLabel.trim().uppercase(Locale.US).startsWith(BACKEND_WARP)) {
            ClientData(this).clearWarpFullCycleFailureState()
        }
        releaseRecoveryWakeLock()
    }

    private fun hasRecentConfirmedWarpExitSnapshot(
        clientData: ClientData,
        freshnessMs: Long = 6_000L,
    ): Boolean {
        val snapshot = clientData.getTunnelUiSnapshot() ?: return false
        if (!snapshot.backend.trim().uppercase(Locale.ROOT).startsWith(BACKEND_WARP)) return false
        if (snapshot.ipv4.isBlank() && snapshot.ipv6.isBlank()) return false
        val observedAt = snapshot.observedAt
        if (observedAt <= 0L) return false
        val ageMs = (System.currentTimeMillis() - observedAt).coerceAtLeast(0L)
        return ageMs <= freshnessMs
    }

    private fun acquireRecoveryWakeLock(reason: String, durationMs: Long) {
        val holdMs = durationMs.coerceIn(2_000L, 15_000L)
        try {
            val powerManager = getSystemService(PowerManager::class.java) ?: return
            val wakeLock = recoveryWakeLock ?: powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:recovery")
                .apply {
                    setReferenceCounted(false)
                    recoveryWakeLock = this
                }
            val now = SystemClock.elapsedRealtime()
            val shouldRefresh = !wakeLock.isHeld || recoveryWakeLockHeldUntilMs - now < 1_000L
            if (shouldRefresh) {
                wakeLock.acquire(holdMs)
            }
            recoveryWakeLockHeldUntilMs = maxOf(recoveryWakeLockHeldUntilMs, now + holdMs)
        } catch (_: Throwable) {
        }
    }

    private fun releaseRecoveryWakeLock() {
        recoveryWakeLockHeldUntilMs = 0L
        val wakeLock = recoveryWakeLock ?: return
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
            }
        } catch (_: Throwable) {
        }
    }

    private fun refreshConnectedScreenOffWakeLock() {
        val shouldHold =
            currentState == STATE_CONNECTED &&
                isRunning &&
                !explicitStopRequested &&
                !cleanupInProgress.get() &&
                !isDeviceInteractiveNow()
        if (shouldHold) {
            acquireConnectedScreenOffWakeLock()
        } else {
            releaseConnectedScreenOffWakeLock()
        }
    }

    private fun acquireConnectedScreenOffWakeLock() {
        try {
            val powerManager = getSystemService(PowerManager::class.java) ?: return
            val wakeLock = connectedScreenOffWakeLock ?: powerManager
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:connected-screen-off")
                .apply {
                    setReferenceCounted(false)
                    connectedScreenOffWakeLock = this
                }
            if (!wakeLock.isHeld) {
                wakeLock.acquire()
                LogManager.log("Экран погашен при активном VPN. Удерживаем partial wake lock для стабильности туннеля.")
            }
        } catch (_: Throwable) {
        }
    }

    private fun releaseConnectedScreenOffWakeLock() {
        val wakeLock = connectedScreenOffWakeLock ?: return
        try {
            if (wakeLock.isHeld) {
                wakeLock.release()
                LogManager.log("Снимаем screen-off wake lock для VPN.")
            }
        } catch (_: Throwable) {
        }
    }

    private fun isDeviceInteractiveNow(): Boolean {
        return try {
            getSystemService(PowerManager::class.java)?.isInteractive ?: true
        } catch (_: Exception) {
            true
        }
    }

    private fun currentRecentTunnelProbeWindowMs(): Long {
        val now = SystemClock.elapsedRealtime()
        return when {
            requireFreshTunnelProbeUntilMs > now -> 4_500L
            isDeviceInteractiveNow() -> 12_000L
            else -> 7_500L
        }
    }

    private fun hasRecentSuccessfulTunnelProbe(windowMs: Long = currentRecentTunnelProbeWindowMs()): Boolean {
        if (hasRecentTransportFailureSignal()) return false
        val last = lastSuccessfulTunnelProbeAtMs
        return last > 0L && SystemClock.elapsedRealtime() - last <= windowMs
    }

    private fun hasRecentSuccessfulTunnelProbeForUnderlying(
        connectivityManager: android.net.ConnectivityManager?,
        selectedUnderlying: android.net.Network?,
        windowMs: Long = currentRecentTunnelProbeWindowMs(),
    ): Boolean {
        if (!hasRecentSuccessfulTunnelProbe(windowMs)) return false
        val currentSignature = buildUnderlyingNetworkSignature(connectivityManager, selectedUnderlying).orEmpty()
        val probeSignature = lastSuccessfulTunnelProbeNetworkSignature.orEmpty()
        if (currentSignature.isNotBlank() && probeSignature.isNotBlank()) {
            return currentSignature == probeSignature
        }
        val currentNetworkClass = buildUnderlyingNetworkClass(connectivityManager, selectedUnderlying).orEmpty()
        val probeNetworkClass = lastSuccessfulTunnelProbeNetworkClass.orEmpty()
        return currentNetworkClass.isNotBlank() &&
            probeNetworkClass.isNotBlank() &&
            currentNetworkClass == probeNetworkClass
    }

    private fun hasStableRecentTunnelProof(): Boolean {
        return !requiresFreshTunnelProbeNow() && hasRecentSuccessfulTunnelProbe()
    }

    private fun hasStableRecentTunnelProofForUnderlying(
        connectivityManager: android.net.ConnectivityManager?,
        selectedUnderlying: android.net.Network?,
    ): Boolean {
        return !requiresFreshTunnelProbeNow() &&
            hasRecentSuccessfulTunnelProbeForUnderlying(connectivityManager, selectedUnderlying)
    }

    private fun shouldHonorFreshConnectGraceForUnderlying(
        connectivityManager: android.net.ConnectivityManager?,
        selectedUnderlying: android.net.Network?,
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lastConnectedAtMs <= 0L || now - lastConnectedAtMs >= currentHealthReconnectGraceMs()) {
            return false
        }
        if (!hasRecentSuccessfulTunnelProbe()) {
            return true
        }
        val selectedSignature = buildUnderlyingNetworkSignature(connectivityManager, selectedUnderlying).orEmpty()
        val probeSignature = lastSuccessfulTunnelProbeNetworkSignature.orEmpty()
        if (selectedSignature.isNotBlank() && probeSignature.isNotBlank()) {
            return selectedSignature == probeSignature
        }
        val selectedNetworkClass = buildUnderlyingNetworkClass(connectivityManager, selectedUnderlying).orEmpty()
        val probeNetworkClass = lastSuccessfulTunnelProbeNetworkClass.orEmpty()
        if (selectedNetworkClass.isNotBlank() && probeNetworkClass.isNotBlank()) {
            return selectedNetworkClass == probeNetworkClass
        }
        return true
    }

    private fun shouldPreferImmediateReconnectForUnderlyingUpgrade(
        connectivityManager: android.net.ConnectivityManager?,
        selectedUnderlying: android.net.Network?,
        reason: String,
    ): Boolean {
        if (reason != "underlying-change") return false
        if (!hasRecentSuccessfulTunnelProbe()) return false
        val selectedNetworkClass = buildUnderlyingNetworkClass(connectivityManager, selectedUnderlying)
            ?.lowercase(Locale.US)
            .orEmpty()
        val probeNetworkClass = lastSuccessfulTunnelProbeNetworkClass
            ?.lowercase(Locale.US)
            .orEmpty()
        if (!selectedNetworkClass.contains("wifi")) return false
        if (!probeNetworkClass.contains("cell") || probeNetworkClass.contains("wifi")) return false
        if (hasRecentSuccessfulTunnelProbeForUnderlying(connectivityManager, selectedUnderlying)) return false
        return true
    }

    private fun rememberPendingUnderlayUpgradeWarpHint(
        connectivityManager: android.net.ConnectivityManager?,
        selectedUnderlying: android.net.Network?,
    ) {
        if (!currentBackendLabel.trim().uppercase(Locale.ROOT).startsWith(BACKEND_WARP)) return
        val active = activeWarpQualityTarget
        val stable = currentCycleStableSuccess
        val host = active?.host?.trim().orEmpty().ifBlank { stable?.host.orEmpty() }
        val port = active?.port ?: stable?.port ?: -1
        val mode = active?.mode?.trim().orEmpty().ifBlank { stable?.mode.orEmpty() }
        if (host.isBlank() || port !in 1..65535 || mode.isBlank()) return
        val protocol = active?.engine?.trim().orEmpty().ifBlank { stable?.protocol.orEmpty() }.ifBlank { "wireguard" }
        pendingUnderlayUpgradeWarpHint = StableSuccessSnapshot(
            host = host,
            port = port,
            protocol = protocol,
            mode = mode,
            networkSignature = buildUnderlyingNetworkSignature(connectivityManager, selectedUnderlying).orEmpty(),
        )
        pendingUnderlayUpgradeWarpHintAtMs = SystemClock.elapsedRealtime()
        LogManager.log(
            "Переход на Wi‑Fi: первым exact-кандидатом сохраняем текущий WARP-путь $mode@$host:$port."
        )
    }

    private fun logBenignHealthSkip(message: String, minIntervalMs: Long = 15_000L) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBenignHealthSkipLogAtMs < minIntervalMs) return
        lastBenignHealthSkipLogAtMs = now
        LogManager.log(message)
    }

    private fun hasRecentTransportFailureSignal(windowMs: Long = 18_000L): Boolean {
        val last = lastTransportFailureSignalAtMs
        return last > 0L && SystemClock.elapsedRealtime() - last <= windowMs
    }

    private fun requiresFreshTunnelProbeNow(): Boolean {
        return requireFreshTunnelProbeUntilMs > SystemClock.elapsedRealtime()
    }

    private fun isTransportConnectivityFailureLog(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("network is unreachable") ||
            normalized.contains("no route to host") ||
            normalized.contains("host is unreachable") ||
            normalized.contains("network is down") ||
            normalized.contains("connection refused")
    }

    private fun isOperaUpstreamBadGatewayLog(message: String): Boolean {
        val normalized = message.lowercase()
        return normalized.contains("server replied with 502") ||
            normalized.contains("502 [reason: bad gateway]") ||
            normalized.contains("upstream proxy server: 500 internal server error")
    }

    private fun noteTransportConnectivityLoss(source: String, message: String) {
        val normalizedMessage = message.trim()
        if (normalizedMessage.isBlank()) return
        if (!isRunning || isUserStopped || explicitStopRequested || cleanupInProgress.get() || suppressSessionRestore) {
            return
        }
        if (currentState != STATE_CONNECTED && currentState != STATE_CONNECTING) {
            return
        }
        val signature = "$source|${normalizedMessage.lowercase()}"
        val now = SystemClock.elapsedRealtime()
        if (signature == lastTransportFailureSignature && now - lastTransportFailureSignalAtMs < 1500L) {
            return
        }
        lastTransportFailureSignature = signature
        lastTransportFailureSignalAtMs = now
        lastSuccessfulTunnelProbeAtMs = 0L
        lastSuccessfulTunnelProbeNetworkSignature = null
        lastSuccessfulTunnelProbeNetworkClass = null
        requestAcceleratedVpnCheck(
            triggerReason = "transport-loss",
            recoveryReason = "underlying-loss",
            freshProbeWindowMs = 8_000L,
            dedupeMs = 1200L,
            logMessage =
                "$source сообщает о потере внешней сети (${normalizedMessage.take(120)}). " +
                    "Сбрасываем старый tunnel-probe и ускоряем проверку VPN.",
        )
    }

    private fun currentOperaRecoveryRegionPreference(clientData: ClientData): String {
        val backend = currentBackendLabel.trim().uppercase(Locale.US)
        return when {
            backend.endsWith("-US") -> "us"
            backend.endsWith("-EU") -> "eu"
            else -> normalizeRegionPreference(clientData.getExitRegionPreference()).let {
                if (it == "us" || it == "eu") it else "eu"
            }
        }
    }

    private fun currentOperaPinnedCountryCode(): String? {
        val backend = currentBackendLabel.trim().uppercase(Locale.US)
        return when {
            backend.endsWith("-US") -> "AM"
            backend.endsWith("-EU") -> "EU"
            else -> null
        }
    }

    private fun noteOperaUpstreamBadGateway(message: String) {
        if (!isOperaUpstreamBadGatewayLog(message)) return
        if (!isRunning || isUserStopped || explicitStopRequested || cleanupInProgress.get() || suppressSessionRestore) {
            return
        }
        if (currentState != STATE_CONNECTED || !isOperaBackendLabel(currentBackendLabel)) return

        val now = SystemClock.elapsedRealtime()
        if (now - operaBadGatewayWindowStartedAtMs > 8_000L) {
            operaBadGatewayWindowStartedAtMs = now
            operaBadGatewayBurstCount = 0
        }
        operaBadGatewayBurstCount += 1
        OperaProxyManager.noteRuntimeFailureCode(
            if (message.lowercase().contains("502")) 502 else 500
        )
        if (operaBadGatewayBurstCount < 8) return
        if (now - lastOperaBadGatewayRecoveryAtMs < 45_000L) return

        lastOperaBadGatewayRecoveryAtMs = now
        operaBadGatewayWindowStartedAtMs = now
        operaBadGatewayBurstCount = 0

        val clientData = ClientData(this)
        val regionPreference = currentOperaRecoveryRegionPreference(clientData)
        currentOperaPinnedCountryCode()?.let { country ->
            OperaProxyManager.getCurrentEndpoint()?.let { endpoint ->
                clientData.demoteOperaPinnedEndpoint(country, endpoint)
                clientData.markOperaPinnedEndpointFailure(country, endpoint)
                LogManager.log(
                    "У текущего Opera endpoint начался burst bad-gateway ошибок. " +
                        "Опускаем endpoint $endpoint вниз списка $country и временно пропускаем его перед recovery."
                )
            }
        }

        LogManager.log(
            "Opera backend даёт серию upstream 500/502. " +
                "Пробуем recovery через WARP-bootstrap для региона ${regionPreference.uppercase(Locale.US)}."
        )

        startSafeServiceThread("NovaOperaBadGatewayRecovery") {
            val currentGeneration = connectGeneration.get()
            val startedBootstrap = tryStartOperaBootstrapViaWarp(
                clientData = clientData,
                regionPreference = regionPreference,
                connectGenerationId = currentGeneration,
            )
            if (!startedBootstrap && !isUserStopped) {
                LogManager.log(
                    "WARP-bootstrap для Opera сейчас недоступен. " +
                        "Перезапускаем текущий Opera-регион напрямую как запасной recovery."
                )
                val nextGeneration = beginConnectGeneration(stopExisting = true)
                if (isConnectGenerationCurrent(nextGeneration) && !isUserStopped) {
                    configureAndStartOperaOnly(regionPreference, nextGeneration)
                }
            }
        }
    }

    private fun logOperaProxyManagerMessage(message: String) {
        LogManager.log(message)
        if (isTransportConnectivityFailureLog(message)) {
            noteTransportConnectivityLoss("Opera proxy", message)
        }
        if (isOperaUpstreamBadGatewayLog(message)) {
            noteOperaUpstreamBadGateway(message)
        }
    }

    private fun requestAcceleratedVpnCheck(
        triggerReason: String,
        recoveryReason: String,
        freshProbeWindowMs: Long,
        dedupeMs: Long,
        logMessage: String,
    ) {
        val now = SystemClock.elapsedRealtime()
        if (
            lastAcceleratedRecoveryReason == triggerReason &&
            now - lastAcceleratedRecoveryAtMs < dedupeMs
        ) {
            return
        }
        lastAcceleratedRecoveryReason = triggerReason
        lastAcceleratedRecoveryAtMs = now
        requireFreshTunnelProbeUntilMs = maxOf(requireFreshTunnelProbeUntilMs, now + freshProbeWindowMs)
        acquireRecoveryWakeLock(triggerReason, freshProbeWindowMs + 2_000L)
        if (!isRunning || isUserStopped || explicitStopRequested || cleanupInProgress.get() || suppressSessionRestore || warpConfigDiscoveryRunning.get()) {
            return
        }
        if (currentState != STATE_CONNECTED && currentState != STATE_CONNECTING) {
            return
        }
        if (currentState == STATE_CONNECTING) {
            if (maybeRecoverStaleConnectingLiveVpn(triggerReason)) {
                return
            }
            LogManager.log(
                "Ускоренную проверку VPN во время активного подключения пропускаем: " +
                    "connect-flow ещё не завершён."
            )
            return
        }
        connectedHealthProbeFailures = 0
        LogManager.log(logMessage)
        scheduleNetworkRecoveryCheck(recoveryReason)
        vpnConsistencyHandler.removeCallbacks(vpnConsistencyRunnable)
        vpnConsistencyHandler.post(vpnConsistencyRunnable)
    }

    private fun maybeRecoverStaleConnectingLiveVpn(triggerReason: String): Boolean {
        if (currentState != STATE_CONNECTING) return false
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val currentVpn = findCurrentVpnNetwork(connectivityManager) ?: return false
        if (!isLikelyNovaVpnNetwork(connectivityManager, currentVpn)) return false

        val clientData = ClientData(this)
        val expectedBackendHint = currentBackendLabel.ifBlank { clientData.getServiceBackend() }.takeIf { it.isNotBlank() }
        if (adoptHealthyExistingVpnIfPresent(expectedBackendHint)) {
            LogManager.log(
                "Обнаружен уже поднятый рабочий VPN Nova при состоянии CONNECTING " +
                    "($triggerReason). Синхронизируем сервис с CONNECTED."
            )
            return true
        }

        val updatedAt = clientData.getServiceStateUpdatedAt()
        val connectingAgeMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
        if (connectingAgeMs < 8_000L) {
            return false
        }

        val persistedBackend = clientData.getServiceBackend().ifBlank { currentBackendLabel }
        val probeTimeout = if (isOperaBackendLabel(persistedBackend)) 1400 else 900
        if (hasTunnelConnectivity(currentVpn, probeTimeout, allowHttpDnsFallback = true)) {
            return false
        }

        LogManager.log(
            "CONNECTING завис при живом системном VPN Nova после события $triggerReason: " +
                "туннель не подтверждается уже ${connectingAgeMs} ms. Перезапускаем текущий сеанс."
        )
        triggerReconnectForNetworkChange(clientData)
        return true
    }

    private fun shouldDeferWakeReconnect(
        reason: String,
        connectivityManager: android.net.ConnectivityManager?,
        currentVpn: android.net.Network?,
        health: VpnHealthSnapshot,
    ): Boolean {
        if (
            reason != "device-wake" &&
            reason != "wake-followup" &&
            reason != "underlying-loss" &&
            reason != "underlying-change" &&
            reason != "watchdog" &&
            reason != "health-followup"
        ) {
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (now > ignoreUnderlyingWakeEventsUntilMs) return false
        val transientReason =
            health.reason == "VPN потерял underlying networks" ||
                health.reason == "VPN-интерфейс отсутствует" ||
                health.reason == "подложная сеть отсутствует"
        if (!transientReason) return false
        val usableUnderlying = isUsableUnderlyingNetwork(connectivityManager, health.selectedUnderlying)
        if (!usableUnderlying && currentVpn == null && !hasRecentSuccessfulTunnelProbe(windowMs = 90_000L)) {
            return false
        }
        return true
    }

    private fun requestFreshProbeAfterWake(reason: String) {
        val now = SystemClock.elapsedRealtime()
        val connectedAgeMs = if (lastConnectedAtMs > 0L) now - lastConnectedAtMs else Long.MAX_VALUE
        if (
            currentState == STATE_CONNECTED &&
            (
                (reason == "foreground-resume" && connectedAgeMs in 0..currentHealthReconnectGraceMs()) ||
                    (reason == "pending-proof-enter" && connectedAgeMs in 0..8_000L)
                )
        ) {
            return
        }
        if (
            reason == "foreground-resume" &&
            currentState == STATE_CONNECTED &&
            hasRecentSuccessfulTunnelProbe() &&
            !requiresFreshTunnelProbeNow()
        ) {
            return
        }
        if (
            reason == "connected-proof-timeout" &&
            currentState == STATE_CONNECTED &&
            hasRecentSuccessfulTunnelProbe() &&
            !requiresFreshTunnelProbeNow()
        ) {
            return
        }
        val dedupeMs =
            when (reason) {
                "foreground-resume" -> 5_000L
                "connected-proof-timeout" -> 5_000L
                "pending-proof-enter" -> 2_500L
                else -> 1_500L
            }
        if (now - lastDeviceWakeAtMs < dedupeMs) return
        lastDeviceWakeAtMs = now
        if (reason != "background-heartbeat" && reason != "background-heartbeat-adopt") {
            ignoreUnderlyingWakeEventsUntilMs = maxOf(ignoreUnderlyingWakeEventsUntilMs, now + 3_500L)
        }
        val freshProbeWindowMs =
            when (reason) {
                "connected-proof-timeout" -> 5_000L
                "foreground-resume" -> 8_000L
                else -> 10_000L
            }
        val recoveryReason =
            when (reason) {
                "connected-proof-timeout" -> "connected-proof-timeout"
                "background-heartbeat", "background-heartbeat-adopt" -> "background-heartbeat"
                else -> "device-wake"
            }
        val logMessage =
            when (reason) {
                "connected-proof-timeout" ->
                    "Подключение зависло на проверке туннеля дольше 5 секунд. " +
                        "Требуем свежий tunnel-probe и готовим ранний переход к следующему варианту."
                else ->
                    "Устройство проснулось ($reason). Требуем свежий tunnel-probe и ускоренную проверку VPN."
            }
        requestAcceleratedVpnCheck(
            triggerReason = "device-wake:$reason",
            recoveryReason = recoveryReason,
            freshProbeWindowMs = freshProbeWindowMs,
            dedupeMs = dedupeMs,
            logMessage = logMessage,
        )
    }

    private fun hasVpnPreparationPermission(): Boolean {
        return runCatching { VpnService.prepare(this) == null }.getOrDefault(false)
    }

    private fun resetEstablishNullLoopGuard() {
        establishNullLoopWindowStartedAtMs = 0L
        establishNullLoopCount = 0
    }

    private fun shouldStopAfterRepeatedEstablishNull(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (establishNullLoopWindowStartedAtMs == 0L || now - establishNullLoopWindowStartedAtMs > 20_000L) {
            establishNullLoopWindowStartedAtMs = now
            establishNullLoopCount = 1
            return false
        }
        establishNullLoopCount += 1
        return establishNullLoopCount >= 3
    }

    private fun handleUnrecoverableEstablishFailure(
        clientData: ClientData,
        reason: String,
        clearRestartSession: Boolean = false,
    ) {
        LogManager.log(reason)
        isRunning = false
        currentAttemptOrdinal = 0
        currentAttemptTotal = 0
        clientData.clearTransientConnectingPending()
        clientData.clearSoftReapplyPending()
        if (clearRestartSession) {
            clientData.clearRestartSession()
        }
        resetEstablishNullLoopGuard()
        setCurrentBackend(BACKEND_WARP)
        broadcastState(STATE_STOPPED)
        stopSelf()
    }

    private fun restorePersistedSession(
        ignoreAutoReconnect: Boolean = false,
        forceRestart: Boolean = false,
    ): Boolean {
        val clientData = ClientData(this)
        val persistedState = clientData.getServiceState()
        val unexpectedServiceDeathRecovery =
            persistedState == STATE_CONNECTING || persistedState == STATE_CONNECTED
        if (!ignoreAutoReconnect && !clientData.getAutoReconnect() && !unexpectedServiceDeathRecovery) {
            LogManager.log("restorePersistedSession: авто-реконнект выключен и аварийного восстановления не требуется.")
            return false
        }
        val currentPreference = normalizeRegionPreference(clientData.getExitRegionPreference())
        val persistedSession = clientData.getRestartSession()
        val recoveredSession = when {
            persistedSession != null -> persistedSession
            shouldUseWarpTransport(currentPreference) ->
                buildWarpRestartSessionFromCurrentConfig(clientData, currentPreference)?.also {
                    clientData.saveRestartSession(it)
                    LogManager.log(
                        "restorePersistedSession: restart session отсутствует, " +
                            "пересобрали её из текущей WARP-конфигурации."
                    )
                }
            shouldAllowOperaTransport(currentPreference) ->
                RestartSession(kind = "opera", region = currentPreference).also {
                    clientData.saveRestartSession(it)
                    LogManager.log(
                        "restorePersistedSession: restart session отсутствует, " +
                            "пересобрали её для Opera по текущему региону."
                    )
                }
            else -> null
        }
        if (recoveredSession == null) {
            LogManager.log("restorePersistedSession: restart session отсутствует.")
            if (unexpectedServiceDeathRecovery || forceRestart || persistedState == STATE_CONNECTING) {
                clientData.clearTransientConnectingPending()
                clientData.clearSoftReapplyPending()
                isRunning = false
                currentAttemptOrdinal = 0
                currentAttemptTotal = 0
                setCurrentBackend(BACKEND_WARP)
                broadcastState(STATE_STOPPED)
            }
            return false
        }
        val session = alignRestartSessionWithCurrentPreference(clientData, recoveredSession)
        if (session == null) {
            LogManager.log(
                "restorePersistedSession: сохранённая сессия больше не совпадает с текущим регионом, " +
                    "а рабочего WARP-профиля для восстановления нет."
            )
            clientData.clearRestartSession()
            return false
        }
        if (session != persistedSession) {
            clientData.saveRestartSession(session)
        }
        if (suppressSessionRestore) {
            LogManager.log("restorePersistedSession: восстановление подавлено текущим состоянием сервиса.")
            return false
        }
        if (!hasVpnPreparationPermission()) {
            handleUnrecoverableEstablishFailure(
                clientData = clientData,
                reason = "restorePersistedSession: системное разрешение VPN потеряно. Останавливаем авто-восстановление и ждём явного запуска из UI.",
                clearRestartSession = false,
            )
            return false
        }
        if (unexpectedServiceDeathRecovery) {
            LogManager.log(
                "Восстанавливаем предыдущий VPN-сеанс после перезапуска :vpn процесса " +
                    "(persistedState=$persistedState, forceRestart=$forceRestart)."
            )
        }
        val expectedBackendHint = when (session.kind) {
            "opera" -> getOperaFallbackSequence(session.region).firstOrNull()?.second?.let { "$BACKEND_OPERA-$it" }
                ?: BACKEND_OPERA
            else -> BACKEND_WARP
        }
        if (!forceRestart && adoptHealthyExistingVpnIfPresent(expectedBackendHint)) {
            isRunning = true
            return true
        }
        clientData.markTransientConnectingPending(9000L)
        isUserStopped = false
        explicitStopRequested = false
        suppressSessionRestore = false
        operaFallbackActive = false
        novaCoreTunnelActive = false
        operaTunThread = null
        novaEngineThread = null
        setCurrentBackend(BACKEND_WARP)
        val connectGenerationId = beginConnectGeneration(stopExisting = true)
        startSafeServiceThread("NovaRestoreSession") {
            when (session.kind) {
                "opera" -> configureAndStartOperaOnly(
                    regionPreferenceOverride = session.region,
                    connectGenerationId = connectGenerationId,
                )
                "warp" -> {
                    val reconnectWarpOnly = autoReconnectShouldPreferWarpOnly(clientData, session.region)
                    val operaAllowed = shouldAllowOperaTransport(session.region)
                    configureAndStartVpn(
                        privateKey = session.privateKey.orEmpty(),
                        ipv4 = session.ipv4.orEmpty(),
                        ipv6 = session.ipv6.orEmpty(),
                        peerPub = session.peerPublicKey.orEmpty(),
                        peerEndpoint = session.peerEndpoint.orEmpty(),
                        reserved = session.reserved,
                        savedPort = session.savedPort ?: -1,
                        savedProto = session.savedProto.orEmpty().ifBlank { "MASQUE" },
                        regionPreferenceOverride = session.region,
                        allowOperaFallbackOverride = if (operaAllowed) !reconnectWarpOnly else false,
                        preferWarpOnlySticky = reconnectWarpOnly,
                        diagnosticsMode = false,
                        connectGenerationId = connectGenerationId,
                    )
                }
                else -> return@startSafeServiceThread
            }
        }
        isRunning = true
        return true
    }

    private fun buildWarpRestartSessionFromCurrentConfig(
        clientData: ClientData,
        regionPreference: String,
    ): RestartSession? {
        val config = clientData.getConfig() ?: return null
        return RestartSession(
            kind = "warp",
            region = regionPreference,
            privateKey = config.privateKey,
            ipv4 = config.ipv4,
            ipv6 = config.ipv6,
            peerPublicKey = config.peerPublicKey,
            peerEndpoint = config.peerEndpoint,
            reserved = config.reserved,
            savedPort = clientData.getLastSuccessPort().takeIf { it in 1..65535 },
            savedProto = clientData.getLastSuccessProtocol(),
        )
    }

    private fun buildWarpRestartSessionFromAttemptInputs(
        regionPreference: String,
        privateKey: String,
        ipv4: String,
        ipv6: String,
        peerPub: String,
        peerEndpoint: String,
        reserved: String?,
        savedPort: Int,
        savedProto: String,
    ): RestartSession? {
        if (
            privateKey.isBlank() ||
            ipv4.isBlank() ||
            ipv6.isBlank() ||
            peerPub.isBlank() ||
            peerEndpoint.isBlank()
        ) {
            return null
        }
        return RestartSession(
            kind = "warp",
            region = regionPreference,
            privateKey = privateKey,
            ipv4 = ipv4,
            ipv6 = ipv6,
            peerPublicKey = peerPub,
            peerEndpoint = peerEndpoint,
            reserved = reserved,
            savedPort = savedPort.takeIf { it in 1..65535 },
            savedProto = savedProto.ifBlank { "MASQUE" },
        )
    }

    private fun alignRestartSessionWithCurrentPreference(
        clientData: ClientData,
        session: RestartSession,
    ): RestartSession? {
        if (clientData.isImportedConfigSourceActive()) {
            return when (session.kind) {
                "warp" -> session
                else -> buildWarpRestartSessionFromCurrentConfig(clientData, normalizeRegionPreference(clientData.getExitRegionPreference()))
            }
        }
        val currentPreference = normalizeRegionPreference(clientData.getExitRegionPreference())
        return when {
            shouldUseWarpTransport(currentPreference) && !shouldAllowOperaTransport(currentPreference) -> {
                if (session.kind == "warp" && session.region == currentPreference) {
                    session
                } else {
                    LogManager.log(
                        "Игнорируем stale restart session (${session.kind}:${session.region}) " +
                            "и возвращаемся к WARP, потому что сейчас выбран регион $currentPreference."
                    )
                    buildWarpRestartSessionFromCurrentConfig(clientData, currentPreference)
                }
            }
            !shouldUseWarpTransport(currentPreference) && shouldAllowOperaTransport(currentPreference) -> {
                RestartSession(
                    kind = "opera",
                    region = currentPreference,
                )
            }
            else -> session
        }
    }

    private fun applyExplicitReapplyOverrides(intent: Intent, clientData: ClientData) {
        intent.getStringExtra(EXTRA_EXIT_REGION)?.let {
            clientData.setExitRegionPreference(it)
        }
        if (intent.hasExtra(EXTRA_IMPORTED_CONFIG_SOURCE_ENABLED)) {
            clientData.setImportedWarpOnlyModeEnabled(
                intent.getBooleanExtra(
                    EXTRA_IMPORTED_CONFIG_SOURCE_ENABLED,
                    clientData.isImportedWarpOnlyModeEnabled(),
                )
            )
        }
        intent.getStringExtra(EXTRA_IMPORTED_PROTOCOL_PREFERENCE)?.let {
            clientData.setImportedProtocolPreference(it)
        }
        if (intent.hasExtra(EXTRA_REAPPLY_SPLIT_MODE)) {
            clientData.setSplitMode(intent.getIntExtra(EXTRA_REAPPLY_SPLIT_MODE, clientData.getSplitMode()))
        }
        if (intent.hasExtra(EXTRA_REAPPLY_SPLIT_APPS)) {
            val splitApps = intent.getStringArrayListExtra(EXTRA_REAPPLY_SPLIT_APPS)?.toSet().orEmpty()
            clientData.setSplitApps(splitApps)
        }
        if (intent.hasExtra(EXTRA_REAPPLY_TRAFFIC_MASK_ENABLED)) {
            clientData.setTrafficMaskEnabled(
                intent.getBooleanExtra(EXTRA_REAPPLY_TRAFFIC_MASK_ENABLED, clientData.getTrafficMaskEnabled())
            )
        }
        intent.getStringExtra(EXTRA_REAPPLY_TRAFFIC_MASK_MODE)?.let {
            clientData.setTrafficMaskMode(it)
        }
        if (intent.hasExtra(EXTRA_REAPPLY_TRAFFIC_MASK_HOST)) {
            clientData.setTrafficMaskHost(intent.getStringExtra(EXTRA_REAPPLY_TRAFFIC_MASK_HOST))
        }
    }

    private fun reapplyCurrentPreferences(intent: Intent): Boolean {
        val clientData = ClientData(this)
        LogManager.log(
            "REAPPLY current session: extras region=${intent.getStringExtra(EXTRA_EXIT_REGION)}, " +
                "importedSource=${intent.getBooleanExtra(EXTRA_IMPORTED_CONFIG_SOURCE_ENABLED, clientData.isImportedWarpOnlyModeEnabled())}, " +
                "importedProtocol=${intent.getStringExtra(EXTRA_IMPORTED_PROTOCOL_PREFERENCE)}, " +
                "splitMode=${intent.getIntExtra(EXTRA_REAPPLY_SPLIT_MODE, -1)}, " +
                "maskEnabled=${intent.getBooleanExtra(EXTRA_REAPPLY_TRAFFIC_MASK_ENABLED, clientData.getTrafficMaskEnabled())}, " +
                "maskMode=${intent.getStringExtra(EXTRA_REAPPLY_TRAFFIC_MASK_MODE)}, " +
                "maskHost=${intent.getStringExtra(EXTRA_REAPPLY_TRAFFIC_MASK_HOST)}"
        )
        applyExplicitReapplyOverrides(intent, clientData)
        LogManager.log(
            "REAPPLY обновил настройки :vpn процесса из свежих extras UI: " +
                "region=${clientData.getExitRegionPreference()}, importedSource=${clientData.isImportedWarpOnlyModeEnabled()}, " +
                "importedActive=${clientData.isImportedConfigSourceActive()}, importedProtocol=${clientData.getImportedProtocolPreference()}, " +
                "splitMode=${clientData.getSplitMode()}, maskEnabled=${clientData.getTrafficMaskEnabled()}, " +
                "maskMode=${clientData.getTrafficMaskMode()}, maskHost=${clientData.getTrafficMaskHost()}"
        )
        val regionPreference = normalizeRegionPreference(clientData.getExitRegionPreference())
        LogManager.log("REAPPLY resolved region preference in VPN service: $regionPreference")
        val connectGenerationId = beginConnectGeneration(stopExisting = true)
        isUserStopped = false
        explicitStopRequested = false
        suppressSessionRestore = false
        operaFallbackActive = false
        novaCoreTunnelActive = false
        operaTunThread = null
        novaEngineThread = null
        val requestedAttemptOrdinal = intent.getIntExtra(EXTRA_ATTEMPT_ORDINAL, 0).coerceAtLeast(0)
        val requestedAttemptTotal = intent.getIntExtra(EXTRA_ATTEMPT_TOTAL, 0).coerceAtLeast(0)
        currentAttemptOrdinal = requestedAttemptOrdinal.coerceAtMost(
            requestedAttemptTotal.takeIf { it > 0 } ?: Int.MAX_VALUE
        )
        currentAttemptTotal = requestedAttemptTotal
        val manualWarpProfileMode = intent.getStringExtra(EXTRA_MANUAL_WARP_PROFILE_MODE).orEmpty().trim()
        val manualWarpProfileHost = intent.getStringExtra(EXTRA_MANUAL_WARP_PROFILE_HOST).orEmpty().trim()
        val manualWarpProfilePort = intent.getIntExtra(EXTRA_MANUAL_WARP_PROFILE_PORT, -1)
        manualWarpProfileSwitchTargetKey = buildWarpDiscoveryAttemptKey(
            manualWarpProfileMode,
            manualWarpProfileHost,
            manualWarpProfilePort,
        ).takeIf {
            // Ключ склеивается из трёх полей и пустым не бывает никогда: без профиля
            // получается «||-1». Проверять надо сами поля — иначе любой REAPPLY с
            // порядковым номером (например, переключение профиля VLESS) включал бы
            // ручной режим ординала в переборе WARP.
            requestedAttemptOrdinal > 0 && requestedAttemptTotal > 0 &&
                manualWarpProfileMode.isNotEmpty() && manualWarpProfileHost.isNotEmpty()
        }
        if (manualWarpProfileSwitchTargetKey != null) {
            manualWarpProfileSwitchOrdinal = requestedAttemptOrdinal
            manualWarpProfileSwitchTotal = requestedAttemptTotal
        } else {
            manualWarpProfileSwitchOrdinal = 0
            manualWarpProfileSwitchTotal = 0
        }
        currentWarpMaskHost = null

        // VLESS проверяем раньше WARP: с регионом `vless` общая ветка потребовала бы
        // регистрацию WARP и молча возвращала false там, где её нет, — кнопка
        // «следующий профиль» тогда не делала вообще ничего.
        if (clientData.shouldUseVlessTransport()) {
            setCurrentBackend(BACKEND_VLESS)
            currentTransportLabel = TRANSPORT_VLESS
            broadcastState(STATE_CONNECTING)
            val warpConfig = clientData.getConfig()
            if (warpConfig != null) {
                startSafeServiceThread("NovaReapplyVless") {
                    configureAndStartVpn(
                        privateKey = warpConfig.privateKey,
                        ipv4 = warpConfig.ipv4,
                        ipv6 = warpConfig.ipv6,
                        peerPub = warpConfig.peerPublicKey,
                        peerEndpoint = warpConfig.peerEndpoint,
                        reserved = warpConfig.reserved,
                        savedPort = clientData.getLastSuccessPort(),
                        savedProto = clientData.getLastSuccessProtocol(),
                        regionPreferenceOverride = regionPreference,
                        allowOperaFallbackOverride = null,
                        preferWarpOnlySticky = false,
                        diagnosticsMode = false,
                        connectGenerationId = connectGenerationId,
                    )
                }
            } else {
                startSafeServiceThread("NovaReapplyVlessOnly") {
                    if (!waitForPreviousCleanupIfNeeded(connectGenerationId, "vless-reapply")) {
                        return@startSafeServiceThread
                    }
                    if (!isConnectGenerationCurrent(connectGenerationId)) return@startSafeServiceThread
                    if (!ensureFreshTransportState(connectGenerationId, "vless-reapply")) {
                        return@startSafeServiceThread
                    }
                    installSocketProtector()
                    if (runVlessPhase(clientData, connectGenerationId)) return@startSafeServiceThread
                    if (isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) {
                        return@startSafeServiceThread
                    }
                    LogManager.log("VLESS не поднялся, а WARP не зарегистрирован — продолжать нечем.")
                    broadcastState(STATE_STOPPED)
                }
            }
            isRunning = true
            return true
        }

        setCurrentBackend(BACKEND_WARP)
        broadcastState(STATE_CONNECTING)

        if (shouldUseWarpTransport(regionPreference)) {
            val config = clientData.getConfig() ?: return false
            clientData.saveRestartSession(
                RestartSession(
                    kind = "warp",
                    region = regionPreference,
                    privateKey = config.privateKey,
                    ipv4 = config.ipv4,
                    ipv6 = config.ipv6,
                    peerPublicKey = config.peerPublicKey,
                    peerEndpoint = config.peerEndpoint,
                    reserved = config.reserved,
                    savedPort = clientData.getLastSuccessPort().takeIf { it in 1..65535 },
                    savedProto = clientData.getLastSuccessProtocol(),
                )
            )
            startSafeServiceThread("NovaReapplyWarp") {
                configureAndStartVpn(
                    privateKey = config.privateKey,
                    ipv4 = config.ipv4,
                    ipv6 = config.ipv6,
                    peerPub = config.peerPublicKey,
                    peerEndpoint = config.peerEndpoint,
                    reserved = config.reserved,
                    savedPort = clientData.getLastSuccessPort(),
                    savedProto = clientData.getLastSuccessProtocol(),
                    regionPreferenceOverride = regionPreference,
                    allowOperaFallbackOverride = null,
                    preferWarpOnlySticky = false,
                    diagnosticsMode = false,
                    connectGenerationId = connectGenerationId,
                )
            }
            isRunning = true
            return true
        }

        if (shouldAllowOperaTransport(regionPreference)) {
            clientData.saveRestartSession(
                RestartSession(
                    kind = "opera",
                    region = regionPreference,
                )
            )
            startSafeServiceThread("NovaReapplyOpera") {
                configureAndStartOperaOnly(
                    regionPreferenceOverride = regionPreference,
                    connectGenerationId = connectGenerationId,
                )
            }
            isRunning = true
            return true
        }

        return false
    }

    private fun startWarpConfigDiscovery(
        regionPreferenceOverride: String?,
        adaptToNetwork: Boolean = false,
        qualityDiagnostics: Boolean = false,
    ) {
        if (!warpConfigDiscoveryRunning.compareAndSet(false, true)) return
        val clientData = ClientData(this)
        var foundCount = clientData.getWarpVerifiedConfigs().count(clientData::isBundledSeed)
        val operationName = when {
            qualityDiagnostics -> "диагностика качества WARP"
            adaptToNetwork -> "адаптация WARP к сети"
            else -> "проверка WARP-конфигураций"
        }
        val discoveryConnectGeneration = connectGeneration.get()
        try {
            val initialConnectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
            if (hasActiveForeignVpnNetwork(initialConnectivityManager)) {
                LogManager.log(
                    "$operationName остановлена: сейчас активен VPN другого приложения. " +
                        "Для проверки Nova нужен свободный VpnService."
                )
                broadcastWarpConfigDiscovery(
                    false,
                    foundCount,
                    "Отключите другой VPN и запустите проверку ещё раз",
                )
                return
            }
            if (!hasVpnPreparationPermission()) {
                LogManager.log(
                    "$operationName остановлена: Nova сейчас не является подготовленным VPN-пакетом. " +
                        "Нужно запустить проверку из UI и подтвердить системное разрешение VPN."
                )
                broadcastWarpConfigDiscovery(
                    false,
                    foundCount,
                    "Разрешите Nova использовать VPN и запустите проверку снова",
                )
                return
            }

            val resolvedConfig = clientData.resolveWarpConfigForReuse(repairWithBootstrap = true)
            var config = resolvedConfig?.config
            val hasCachedMasqueIdentityForDiscovery =
                !config?.masqueConfigJson.isNullOrBlank() ||
                    !clientData.getMasqueConfigJson().isNullOrBlank()
            val hasNovaMasqueBootstrapInputs =
                !config?.accessToken.isNullOrBlank() &&
                    !config?.deviceId.isNullOrBlank()
            val masqueProfilesToAdaptCount = clientData.getWarpVerifiedConfigs()
                .count { !it.manual && it.engine.equals("masque", ignoreCase = true) }
            val requiresMasqueIdentity = masqueProfilesToAdaptCount > 0
            val requiresRealIdentityRegistration =
                config != null &&
                    requiresMasqueIdentity &&
                    !hasCachedMasqueIdentityForDiscovery &&
                    !hasNovaMasqueBootstrapInputs
            if (config != null && resolvedConfig?.persisted == false) {
                clientData.saveConfig(config)
                LogManager.log(
                    "WARP discovery: восстановили WARP identity из ${resolvedConfig.source} " +
                        "и сохранили её, чтобы дальнейшие проверки не запускали регистрацию заново."
                )
            }
            if (requiresRealIdentityRegistration) {
                LogManager.log(
                    "WARP discovery: профиль из ${resolvedConfig?.source ?: "saved-config"} " +
                        "не содержит access token/device id и cached MASQUE identity. " +
                        "Сначала регистрируем полноценную WARP identity."
                )
                broadcastWarpConfigDiscovery(
                    true,
                    foundCount,
                    "Получаем полную WARP identity перед проверкой профилей...",
                )
            } else {
                when (resolvedConfig?.source) {
                    "restart-session" -> {
                        LogManager.log(
                            "WARP discovery: сохранённого профиля нет, но есть живая WARP identity в restart session. " +
                                "Используем её без новой регистрации."
                        )
                        broadcastWarpConfigDiscovery(
                            true,
                            foundCount,
                            "Используем уже зарегистрированное устройство WARP...",
                        )
                    }
                    "pending-bootstrap-restart" -> {
                        LogManager.log(
                            "WARP discovery: используем pending bootstrap restart-сеанс и пропускаем повторную регистрацию."
                        )
                        broadcastWarpConfigDiscovery(
                            true,
                            foundCount,
                            "Используем уже зарегистрированное устройство WARP...",
                        )
                    }
                    "bootstrap-seed" -> {
                        LogManager.log(
                            "WARP discovery: сохранённый профиль восстановлен из release seed, повторная регистрация не нужна."
                        )
                        broadcastWarpConfigDiscovery(
                            true,
                            foundCount,
                            "Используем bootstrap WARP-профиль. Подготавливаем конфигурации...",
                        )
                    }
                }
            }
            if (config == null || requiresRealIdentityRegistration) {
                val fallbackConfig = config
                broadcastState(STATE_CONNECTING)
                val registrationMessage = if (fallbackConfig == null) {
                    "Нет WARP-профиля. Регистрируем устройство..."
                } else {
                    "Получаем полную WARP identity..."
                }
                val registrationReason = if (fallbackConfig == null) {
                    "сохранённой конфигурации нет"
                } else {
                    "текущий профиль не содержит access token/device id"
                }
                broadcastWarpConfigDiscovery(true, foundCount, registrationMessage)
                LogManager.log("$operationName: $registrationReason, запускаем регистрацию устройства.")
                val warpClient = WarpClient(
                    applicationContext,
                    LogManager::log,
                    shouldAbort = {
                        warpConfigDiscoveryStopRequested.get() ||
                            isUserStopped ||
                            explicitStopRequested ||
                            cleanupInProgress.get() ||
                            !isConnectGenerationCurrent(discoveryConnectGeneration)
                    }
                )
                val registeredConfig = warpClient.register(
                    onProgress = { progress ->
                        broadcastWarpConfigDiscovery(
                            running = true,
                            foundCount = foundCount,
                            message = if (fallbackConfig == null) {
                                "Регистрируем устройство для WARP: ${progress.coerceIn(0, 100)}%"
                            } else {
                                "Получаем полную WARP identity: ${progress.coerceIn(0, 100)}%"
                            },
                        )
                    }
                )
                if (registeredConfig != null) {
                    config = registeredConfig
                    clientData.saveConfig(registeredConfig)
                    LogManager.log("$operationName: регистрация устройства завершена, продолжаем генерацию стратегий.")
                    broadcastWarpConfigDiscovery(true, foundCount, "Регистрация завершена. Подготавливаем WARP-конфигурации...")
                } else if (fallbackConfig == null && !adaptToNetwork) {
                    broadcastWarpConfigDiscovery(false, foundCount, "Не удалось зарегистрировать устройство для WARP")
                    return
                } else {
                    config = fallbackConfig
                    if (config != null) {
                        LogManager.log(
                            "$operationName: полноценную WARP identity получить не удалось. " +
                                "Продолжаем с текущим профилем без MASQUE bootstrap-данных."
                        )
                        broadcastWarpConfigDiscovery(
                            true,
                            foundCount,
                            "Полную WARP identity получить не удалось. Продолжаем без MASQUE-профилей..."
                        )
                    } else {
                        LogManager.log(
                            "$operationName: регистрацию WARP получить не удалось. " +
                                "Переходим в partial-режим и проверяем только профили, которым не нужна Nova identity."
                        )
                        broadcastWarpConfigDiscovery(
                            true,
                            foundCount,
                            "Регистрация WARP не удалась. Проверяем доступные raw/MASQUE-профили..."
                        )
                    }
                }
            }

            installSocketProtector()
            suppressSessionRestore = true
            if (!ensureFreshTransportState(discoveryConnectGeneration, "warp-config-discovery")) {
                broadcastWarpConfigDiscovery(false, foundCount, "Проверка отменена новым действием")
                return
            }
            setCurrentBackend(BACKEND_WARP)
            currentAttemptOrdinal = 0
            currentAttemptTotal = 0
            broadcastState(STATE_CONNECTING)
            broadcastWarpConfigDiscovery(
                true,
                foundCount,
                when {
                    qualityDiagnostics -> "Готовим диагностику качества WARP..."
                    adaptToNetwork -> "Готовим адаптацию WARP к текущей сети..."
                    else -> "Подготовка WARP-конфигураций..."
                }
            )
            val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
            val restrictedMobileDiscovery =
                clientData.getTrafficMaskMode() == "auto" &&
                    shouldForceImmediateWarpMaskOnRestrictedMobileNetwork(connectivityManager, clientData)
            val discoveryTrafficMaskHosts = resolveWarpTrafficMaskHosts(
                clientData = clientData,
            )
            val useTrafficMaskInDiscovery = when (clientData.getTrafficMaskMode()) {
                "custom" -> discoveryTrafficMaskHosts.isNotEmpty()
                "auto" -> discoveryTrafficMaskHosts.isNotEmpty() &&
                    restrictedMobileDiscovery
                else -> false
            }
            val effectiveDiscoveryTrafficMaskHosts = if (useTrafficMaskInDiscovery) {
                discoveryTrafficMaskHosts
            } else {
                emptyList()
            }
            if (effectiveDiscoveryTrafficMaskHosts.isNotEmpty()) {
                LogManager.log(
                    "WARP discovery на этой сети сразу использует маскировку (${effectiveDiscoveryTrafficMaskHosts.size} доменов)."
                )
                publishWarpTrafficMaskHint(
                    clientData = clientData,
                    trafficMaskHosts = effectiveDiscoveryTrafficMaskHosts,
                    attemptIndex = 0,
                )
            } else {
                currentWarpMaskHost = null
                clientData.setWarpTrafficMaskActiveHost(null)
                clientData.setTrafficMaskActiveHost(null)
            }

            val endpointInfo = config?.let { parseEndpoint(it.peerEndpoint) }
            val endpointCandidates = if (config != null && endpointInfo != null) {
                buildEndpointCandidates(
                    apiEndpoint = endpointInfo.first,
                    apiPort = endpointInfo.second,
                    privateKey = config.privateKey,
                    peerPublicKey = config.peerPublicKey,
                    clientData = clientData,
                    expandForDiscovery = true,
                    includeScannerCandidates = false,
                    includeApiResolution = false,
                )
            } else {
                emptyList()
            }
            LogManager.log("Discovery shortlist prep: endpointCandidates=${endpointCandidates.size}")
            val portCandidates = buildPortCandidates(
                apiPort = endpointInfo?.second ?: -1,
                savedPort = clientData.getLastSuccessPort(),
                savedProto = clientData.getLastSuccessProtocol(),
                clientData = clientData,
            )
            LogManager.log("Discovery shortlist prep: portCandidates=${portCandidates.size}")
            val transportModes = if (config != null) {
                buildTransportModes(
                    reserved = config.reserved,
                    savedProto = clientData.getLastSuccessProtocol(),
                )
            } else {
                emptyList()
            }
            val discoveryTransportModes = if (transportModes.isNotEmpty()) {
                selectPrimaryWarpTransportModes(
                    transportModes = transportModes,
                    clientData = clientData,
                    preferMessengerChatProfiles = clientData.shouldForceMessengerWarpPriority(),
                    fastStartEnabled = true,
                )
            } else {
                emptyList()
            }
            LogManager.log(
                "Discovery shortlist prep: transportModes=${transportModes.size}, " +
                    "discoveryTransportModes=${discoveryTransportModes.size}"
            )
            val allWireGuardAttempts = if (endpointCandidates.isNotEmpty() && transportModes.isNotEmpty()) {
                buildConnectionAttempts(
                    endpointCandidates = endpointCandidates,
                    portCandidates = portCandidates,
                    transportModes = transportModes,
                )
            } else {
                emptyList()
            }
            LogManager.log("Discovery shortlist prep: allWireGuardAttempts=${allWireGuardAttempts.size}")
            val wireGuardAttempts = if (endpointCandidates.isNotEmpty() && discoveryTransportModes.isNotEmpty()) {
                buildConnectionAttempts(
                    endpointCandidates = endpointCandidates,
                    portCandidates = portCandidates,
                    transportModes = discoveryTransportModes,
                )
            } else {
                emptyList()
            }
            LogManager.log("Discovery shortlist prep: wireGuardAttempts=${wireGuardAttempts.size}")
            val savedMasqueProfileCount = clientData.getWarpVerifiedConfigs()
                .count { !it.manual && it.engine.equals("masque", ignoreCase = true) }
            val shouldPrepareMasqueBeforeAdaptation = adaptToNetwork && savedMasqueProfileCount > 0
            if (shouldPrepareMasqueBeforeAdaptation) {
                broadcastWarpConfigDiscovery(
                    true,
                    foundCount,
                    "Готовим MASQUE identity перед адаптацией..."
                )
                LogManager.log(
                    "Адаптация WARP: найдено $savedMasqueProfileCount сохранённых MASQUE-профилей. " +
                        "Получаем MASQUE identity до основного прогона."
                )
            }
            val preparedMasqueIdentityForDiscovery = prepareMasqueIdentity(
                clientData = clientData,
                connectGenerationId = discoveryConnectGeneration,
                trackConnectProgress = shouldPrepareMasqueBeforeAdaptation,
            )
            if (shouldPrepareMasqueBeforeAdaptation && preparedMasqueIdentityForDiscovery == null) {
                LogManager.log(
                    "Адаптация WARP: MASQUE identity до старта получить не удалось. " +
                        "MASQUE-профили будут отложены, остальные профили проверяем дальше."
                )
                broadcastWarpConfigDiscovery(
                    true,
                    foundCount,
                    "MASQUE identity не получен, MASQUE-профили будут отложены"
                )
            }
            if (warpConfigDiscoveryStopRequested.get() || !isConnectGenerationCurrent(discoveryConnectGeneration)) {
                LogManager.log("$operationName остановлена во время подготовки MASQUE identity; VPN-интерфейс не создаём.")
                broadcastWarpConfigDiscovery(
                    false,
                    foundCount,
                    if (adaptToNetwork) "Адаптация остановлена" else "Проверка остановлена",
                )
                return
            }
            val masqueAttempts = preparedMasqueIdentityForDiscovery?.let { identity ->
                buildMasqueConnectionAttempts(
                    identity = identity,
                    clientData = clientData,
                    includeScannerCandidates = false,
                )
            }.orEmpty()
            LogManager.log("Discovery shortlist prep: masqueAttempts=${masqueAttempts.size}")
            val masqueIdentityJsonForDiscovery =
                clientData.getMasqueConfigJson().orEmpty().ifBlank { null }
            val attempts = (masqueAttempts + allWireGuardAttempts + wireGuardAttempts)
                .distinctBy { "${it.mode.engine}:${it.mode.name}:${it.endpointHost}:${it.port}" }
            LogManager.log("Discovery shortlist prep: attempts=${attempts.size}")
            val reducedAttempts = reduceDiscoveryRankingInputs(attempts)
            LogManager.log("Discovery shortlist prep: reducedAttempts=${reducedAttempts.size}")
            val rankedAttempts = prioritizeConnectionAttempts(reducedAttempts, clientData)
            LogManager.log("Discovery shortlist prep: rankedAttempts=${rankedAttempts.size}")
            if (warpConfigDiscoveryStopRequested.get() || !isConnectGenerationCurrent(discoveryConnectGeneration)) {
                LogManager.log("$operationName остановлена во время подготовки shortlist; VPN-интерфейс не создаём.")
                broadcastWarpConfigDiscovery(
                    false,
                    foundCount,
                    if (adaptToNetwork) "Адаптация остановлена" else "Проверка остановлена",
                )
                return
            }
            var selectedAttempts = when {
                qualityDiagnostics -> {
                    val liveConnectRankedAttempts = prioritizeConnectionAttempts(wireGuardAttempts, clientData)
                    val liveConnectShortlist = compactWarpAttemptSet(liveConnectRankedAttempts, clientData)
                    LogManager.log(
                        "WARP quality diagnostics uses live-connect shortlist: " +
                            liveConnectShortlist.take(8).joinToString(",") {
                                "${it.mode.name}@${it.endpointHost}:${it.port}"
                            }
                    )
                    buildWarpQualityDiagnosticsAttemptSet(liveConnectShortlist, clientData)
                }
                adaptToNetwork -> buildWarpNetworkAdaptationAttemptSet(attempts, clientData)
                else -> buildWarpConfigDiscoveryAttemptSet(rankedAttempts, clientData)
            }
            val partialAdaptationWithoutNovaIdentity = adaptToNetwork && config == null
            if (partialAdaptationWithoutNovaIdentity) {
                val masqueIdentityAvailable = preparedMasqueIdentityForDiscovery != null
                val filteredAttempts = selectedAttempts.filter { attempt ->
                    canRunAdaptationAttemptWithoutNovaIdentity(
                        attempt = attempt,
                        clientData = clientData,
                        masqueIdentityAvailable = masqueIdentityAvailable,
                    )
                }
                LogManager.log(
                    "Адаптация WARP без Nova identity: из ${selectedAttempts.size} профилей доступны ${filteredAttempts.size}. " +
                        "MASQUE identity=${if (masqueIdentityAvailable) "yes" else "no"}."
                )
                selectedAttempts = filteredAttempts
                if (selectedAttempts.isNotEmpty()) {
                    broadcastWarpConfigDiscovery(
                        true,
                        foundCount,
                        if (masqueIdentityAvailable) {
                            "Nova identity не получен. Адаптируем raw-профили и доступные MASQUE-профили..."
                        } else {
                            "Nova identity не получен. Адаптируем только raw-профили с собственными ключами..."
                        }
                    )
                }
            }
            LogManager.log("Discovery shortlist prep: selectedAttempts=${selectedAttempts.size}")
            if (warpConfigDiscoveryStopRequested.get() || !isConnectGenerationCurrent(discoveryConnectGeneration)) {
                LogManager.log("$operationName остановлена после подготовки shortlist; VPN-интерфейс не создаём.")
                broadcastWarpConfigDiscovery(
                    false,
                    foundCount,
                    if (adaptToNetwork) "Адаптация остановлена" else "Проверка остановлена",
                )
                return
            }
            if (selectedAttempts.isEmpty()) {
                broadcastWarpConfigDiscovery(
                    false,
                    foundCount,
                    if (adaptToNetwork) {
                        if (config == null) {
                            "Не удалось получить WARP identity, и нет профилей для адаптации без него"
                        } else {
                            "Нет сохранённых WARP-конфигураций для адаптации"
                        }
                    } else {
                        "Нет WARP-конфигураций для перепроверки на этой сети"
                    }
                )
                return
            }

            val adaptationWireGuardPrivateKey = config?.privateKey.orEmpty()
            var adaptationWireGuardIpv4 = config?.ipv4.orEmpty().ifBlank {
                preparedMasqueIdentityForDiscovery?.ipv4.orEmpty()
            }
            var adaptationWireGuardIpv6 = config?.ipv6.orEmpty().ifBlank {
                preparedMasqueIdentityForDiscovery?.ipv6.orEmpty()
            }
            val adaptationWireGuardPeerPub = config?.peerPublicKey.orEmpty()
            val adaptationWireGuardReserved = config?.reserved

            currentAttemptOrdinal = 0
            currentAttemptTotal = selectedAttempts.size
            val qualityDiagnosticHoldMs = if (qualityDiagnostics) 40_000L else 20_000L
            val diagnosticEntries = mutableListOf<WarpQualityDiagnosticEntry>()
            broadcastWarpConfigDiscovery(
                true,
                foundCount,
                when {
                    qualityDiagnostics -> "Диагностика: профиль 1 из ${selectedAttempts.size}, quality window 40 секунд..."
                    adaptToNetwork -> "Адаптация: профиль 1 из ${selectedAttempts.size}, data-plane 20 секунд..."
                    else -> "Проверяем конфигурации 1 из ${selectedAttempts.size}..."
                }
            )

            val knownVerifiedKeys = clientData.getWarpVerifiedConfigs()
                .asSequence()
                .filter { !it.manual }
                .map { buildWarpDiscoveryAttemptKey(it.mode, it.host, it.port) }
                .toMutableSet()
            val failedAdaptationAttempts = mutableListOf<ConnectionAttempt>()
            val deferredMasqueAttempts = mutableListOf<ConnectionAttempt>()
            val successfulAdaptationKeys = linkedSetOf<String>()
            var latestMasqueIdentityJsonForAdaptation = masqueIdentityJsonForDiscovery
            var masqueIdentityAvailableForAdaptation =
                preparedMasqueIdentityForDiscovery != null &&
                    !latestMasqueIdentityJsonForAdaptation.isNullOrBlank()
            runConnectionAttempts(
                descriptor = null,
                descriptorFactory = { attempt ->
                    establishTunnelInterfaceForAttempt(
                        adaptationWireGuardIpv4,
                        adaptationWireGuardIpv6,
                        attempt,
                        clientData,
                    )
                },
                connectionAttempts = selectedAttempts,
                clientData = clientData,
                wireGuardPrivateKey = adaptationWireGuardPrivateKey,
                wireGuardIpv4 = adaptationWireGuardIpv4,
                wireGuardIpv6 = adaptationWireGuardIpv6,
                wireGuardPeerPub = adaptationWireGuardPeerPub,
                wireGuardReserved = adaptationWireGuardReserved,
                masqueIdentityJson = masqueIdentityJsonForDiscovery,
                maxCycles = 1,
                globalAttemptOffset = 0,
                globalAttemptTotal = selectedAttempts.size,
                persistPrimarySuccess = false,
                continueAfterVerifiedSuccess = true,
                verifiedSuccessHoldMs = when {
                    qualityDiagnostics -> qualityDiagnosticHoldMs
                    adaptToNetwork -> 20_000L
                    else -> 3500L
                },
                fastScanMode = !adaptToNetwork && !qualityDiagnostics,
                trafficMaskHosts = effectiveDiscoveryTrafficMaskHosts,
                connectGenerationId = discoveryConnectGeneration,
                externalStopRequested = { warpConfigDiscoveryStopRequested.get() },
                useCachedAttemptTotal = false,
                deferMasqueWithoutIdentity = adaptToNetwork,
                qualitySamplingWindowMs = if (qualityDiagnostics) qualityDiagnosticHoldMs else 20_000L,
                onAttemptStart = { attempt, ordinal, total ->
                    currentAttemptOrdinal = ordinal
                    currentAttemptTotal = total
                    broadcastWarpConfigDiscovery(
                        true,
                        foundCount,
                        if (qualityDiagnostics) {
                            "Диагностика $ordinal из $total: ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}"
                        } else if (adaptToNetwork) {
                            "Адаптация $ordinal из $total: ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}"
                        } else {
                            "Проверяем конфигурацию $ordinal из $total: ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}"
                        }
                    )
                },
                onAttemptResult = { attempt, outcome, attemptDurationMs, stableDurationMs ->
                    if (qualityDiagnostics) {
                        diagnosticEntries += buildWarpQualityDiagnosticEntry(
                            clientData = clientData,
                            attempt = attempt,
                            outcome = outcome,
                            attemptDurationMs = attemptDurationMs,
                            stableDurationMs = stableDurationMs,
                        )
                    }
                    if (outcome == AttemptOutcome.SUCCESS) {
                        successfulAdaptationKeys += attemptExactKey(attempt)
                        val attemptKey = buildWarpDiscoveryAttemptKey(
                            attempt.mode.name,
                            attempt.endpointHost,
                            attempt.port,
                        )
                        val wasKnownVerified = attemptKey in knownVerifiedKeys
                        clientData.upsertWarpVerifiedConfig(
                            engine = attempt.mode.engine,
                            mode = attempt.mode.name,
                            host = attempt.endpointHost,
                            port = attempt.port,
                            endpointSource = normalizeVerifiedConfigSource(attempt.endpointSource),
                            rawConfig = buildWarpConfigDescription(attempt),
                            manual = false,
                            preferredSni = attempt.preferredSni,
                        )
                        if (!wasKnownVerified) {
                            knownVerifiedKeys += attemptKey
                            foundCount += 1
                        }
                        broadcastWarpConfigDiscovery(
                            true,
                            foundCount,
                            if (qualityDiagnostics) {
                                "Диагностика: ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port} удержал quality window"
                            } else if (adaptToNetwork) {
                                "Профиль ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port} прошёл data-plane окно"
                            } else if (wasKnownVerified) {
                                "Подтверждена конфигурация ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}"
                            } else {
                                "Найдена конфигурация ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}"
                            }
                        )
                    } else if (adaptToNetwork && !qualityDiagnostics && outcome == AttemptOutcome.DEFERRED) {
                        if (deferredMasqueAttempts.none { attemptExactKey(it) == attemptExactKey(attempt) }) {
                            deferredMasqueAttempts += attempt
                        }
                        broadcastWarpConfigDiscovery(
                            true,
                            foundCount,
                            "MASQUE отложен до получения identity: ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}"
                        )
                    } else if (adaptToNetwork && !qualityDiagnostics) {
                        if (failedAdaptationAttempts.none { attemptExactKey(it) == attemptExactKey(attempt) }) {
                            failedAdaptationAttempts += attempt
                        }
                        broadcastWarpConfigDiscovery(
                            true,
                            foundCount,
                            "Профиль ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port} не прошёл data-plane"
                        )
                    }
                },
            )
            var sniRetryOffset = selectedAttempts.size
            if (
                adaptToNetwork &&
                !qualityDiagnostics &&
                !warpConfigDiscoveryStopRequested.get() &&
                !isUserStopped &&
                deferredMasqueAttempts.isNotEmpty() &&
                !masqueIdentityAvailableForAdaptation
            ) {
                LogManager.log(
                    "Адаптация WARP: ${deferredMasqueAttempts.size} MASQUE-профилей отложены до получения identity."
                )
                broadcastWarpConfigDiscovery(
                    true,
                    foundCount,
                    "Готовим MASQUE identity для отложенных профилей..."
                )
                val refreshedMasqueIdentity = prepareMasqueIdentity(
                    clientData = clientData,
                    connectGenerationId = discoveryConnectGeneration,
                    trackConnectProgress = true,
                )
                latestMasqueIdentityJsonForAdaptation =
                    clientData.getMasqueConfigJson().orEmpty().ifBlank { null }
                masqueIdentityAvailableForAdaptation =
                    refreshedMasqueIdentity != null &&
                        !latestMasqueIdentityJsonForAdaptation.isNullOrBlank()
                if (masqueIdentityAvailableForAdaptation && refreshedMasqueIdentity != null) {
                    if (adaptationWireGuardIpv4.isBlank()) {
                        adaptationWireGuardIpv4 = refreshedMasqueIdentity.ipv4
                    }
                    if (adaptationWireGuardIpv6.isBlank()) {
                        adaptationWireGuardIpv6 = refreshedMasqueIdentity.ipv6
                    }
                    closeActiveInterface()
                    sniRetryOffset += deferredMasqueAttempts.size
                    LogManager.log(
                        "Адаптация WARP: MASQUE identity получен, проверяем отложенные MASQUE-профили."
                    )
                    runConnectionAttempts(
                        descriptor = null,
                        descriptorFactory = { attempt ->
                            establishTunnelInterfaceForAttempt(refreshedMasqueIdentity.ipv4, refreshedMasqueIdentity.ipv6, attempt, clientData)
                        },
                        connectionAttempts = deferredMasqueAttempts,
                        clientData = clientData,
                        wireGuardPrivateKey = refreshedMasqueIdentity.privateKey,
                        wireGuardIpv4 = refreshedMasqueIdentity.ipv4,
                        wireGuardIpv6 = refreshedMasqueIdentity.ipv6,
                        wireGuardPeerPub = refreshedMasqueIdentity.endpointPubKey,
                        wireGuardReserved = null,
                        masqueIdentityJson = latestMasqueIdentityJsonForAdaptation,
                        maxCycles = 1,
                        globalAttemptOffset = selectedAttempts.size,
                        globalAttemptTotal = selectedAttempts.size + deferredMasqueAttempts.size,
                        persistPrimarySuccess = false,
                        continueAfterVerifiedSuccess = true,
                        verifiedSuccessHoldMs = 20_000L,
                        fastScanMode = false,
                        trafficMaskHosts = effectiveDiscoveryTrafficMaskHosts,
                        connectGenerationId = discoveryConnectGeneration,
                        externalStopRequested = { warpConfigDiscoveryStopRequested.get() },
                        useCachedAttemptTotal = false,
                        deferMasqueWithoutIdentity = true,
                        onAttemptStart = { attempt, ordinal, total ->
                            currentAttemptOrdinal = ordinal
                            currentAttemptTotal = total
                            broadcastWarpConfigDiscovery(
                                true,
                                foundCount,
                                "Проверяем отложенный MASQUE ${ordinal - selectedAttempts.size} из ${deferredMasqueAttempts.size}: " +
                                    "${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}"
                            )
                        },
                        onAttemptResult = { attempt, outcome, _, _ ->
                            when (outcome) {
                                AttemptOutcome.SUCCESS -> {
                                    successfulAdaptationKeys += attemptExactKey(attempt)
                                    val attemptKey = buildWarpDiscoveryAttemptKey(
                                        attempt.mode.name,
                                        attempt.endpointHost,
                                        attempt.port,
                                    )
                                    val wasKnownVerified = attemptKey in knownVerifiedKeys
                                    clientData.upsertWarpVerifiedConfig(
                                        engine = attempt.mode.engine,
                                        mode = attempt.mode.name,
                                        host = attempt.endpointHost,
                                        port = attempt.port,
                                        endpointSource = normalizeVerifiedConfigSource(attempt.endpointSource),
                                        rawConfig = buildWarpConfigDescription(attempt),
                                        manual = false,
                                        preferredSni = attempt.preferredSni,
                                    )
                                    if (!wasKnownVerified) {
                                        knownVerifiedKeys += attemptKey
                                        foundCount += 1
                                    }
                                    broadcastWarpConfigDiscovery(
                                        true,
                                        foundCount,
                                        "Отложенный MASQUE прошёл data-plane: ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}"
                                    )
                                }
                                AttemptOutcome.DEFERRED -> {
                                    LogManager.log(
                                        "Отложенный MASQUE всё ещё без identity: ${attemptLogLabel(attempt)}."
                                    )
                                }
                                else -> {
                                    if (failedAdaptationAttempts.none { attemptExactKey(it) == attemptExactKey(attempt) }) {
                                        failedAdaptationAttempts += attempt
                                    }
                                }
                            }
                        },
                    )
                } else {
                    LogManager.log(
                        "Адаптация WARP: MASQUE identity получить не удалось. " +
                            "Отложенные MASQUE-профили останутся в списке и будут проверены при следующей адаптации."
                    )
                    broadcastWarpConfigDiscovery(
                        true,
                        foundCount,
                        "MASQUE identity не получен, MASQUE-профили отложены до следующей адаптации"
                    )
                }
            }
            var portRetryOffset = sniRetryOffset
            if (
                !adaptToNetwork &&
                !qualityDiagnostics &&
                !warpConfigDiscoveryStopRequested.get() &&
                !isUserStopped &&
                failedAdaptationAttempts.isNotEmpty()
            ) {
                val sniRetryAttempts = buildWarpAdaptationSniRetryAttempts(
                    failedAttempts = failedAdaptationAttempts,
                    clientData = clientData,
                    masqueIdentityAvailable = !latestMasqueIdentityJsonForAdaptation.isNullOrBlank(),
                )
                if (sniRetryAttempts.isNotEmpty()) {
                    closeActiveInterface()
                    currentAttemptOrdinal = sniRetryOffset
                    currentAttemptTotal = sniRetryOffset + sniRetryAttempts.size
                    LogManager.log(
                        "Адаптация WARP: второй проход SNI для ${failedAdaptationAttempts.size} " +
                            "неудачных профилей, попыток=${sniRetryAttempts.size}."
                    )
                    broadcastWarpConfigDiscovery(
                        true,
                        foundCount,
                        "SNI-подбор: ${sniRetryOffset + 1} из ${sniRetryOffset + sniRetryAttempts.size} для неудачных профилей..."
                    )
                    runConnectionAttempts(
                        descriptor = null,
                        descriptorFactory = { attempt ->
                            establishTunnelInterfaceForAttempt(
                                adaptationWireGuardIpv4,
                                adaptationWireGuardIpv6,
                                attempt,
                                clientData,
                            )
                        },
                        connectionAttempts = sniRetryAttempts,
                        clientData = clientData,
                        wireGuardPrivateKey = adaptationWireGuardPrivateKey,
                        wireGuardIpv4 = adaptationWireGuardIpv4,
                        wireGuardIpv6 = adaptationWireGuardIpv6,
                        wireGuardPeerPub = adaptationWireGuardPeerPub,
                        wireGuardReserved = adaptationWireGuardReserved,
                        masqueIdentityJson = latestMasqueIdentityJsonForAdaptation,
                        maxCycles = 1,
                        globalAttemptOffset = sniRetryOffset,
                        globalAttemptTotal = sniRetryOffset + sniRetryAttempts.size,
                        persistPrimarySuccess = false,
                        continueAfterVerifiedSuccess = true,
                        verifiedSuccessHoldMs = 20_000L,
                        fastScanMode = false,
                        trafficMaskHosts = emptyList(),
                        connectGenerationId = discoveryConnectGeneration,
                        externalStopRequested = { warpConfigDiscoveryStopRequested.get() },
                        useCachedAttemptTotal = false,
                        onAttemptStart = { attempt, ordinal, total ->
                            currentAttemptOrdinal = ordinal
                            currentAttemptTotal = total
                            broadcastWarpConfigDiscovery(
                                true,
                                foundCount,
                                "SNI-подбор $ordinal из $total: " +
                                    "${attempt.mode.name}@${attempt.endpointHost}:${attempt.port} через ${attempt.preferredSni}"
                            )
                        },
                        onAttemptResult = { attempt, outcome, _, _ ->
                                if (outcome == AttemptOutcome.SUCCESS) {
                                    successfulAdaptationKeys += attemptExactKey(attempt)
                                    val attemptKey = buildWarpDiscoveryAttemptKey(
                                        attempt.mode.name,
                                        attempt.endpointHost,
                                        attempt.port,
                                    )
                                    val wasKnownVerified = attemptKey in knownVerifiedKeys
                                    clientData.upsertWarpVerifiedConfig(
                                        engine = attempt.mode.engine,
                                        mode = attempt.mode.name,
                                    host = attempt.endpointHost,
                                    port = attempt.port,
                                    endpointSource = normalizeVerifiedConfigSource(attempt.endpointSource),
                                    rawConfig = buildWarpConfigDescription(attempt),
                                        manual = false,
                                        preferredSni = attempt.preferredSni,
                                    )
                                    if (!wasKnownVerified) {
                                        knownVerifiedKeys += attemptKey
                                        foundCount += 1
                                    }
                                    broadcastWarpConfigDiscovery(
                                        true,
                                        foundCount,
                                    "Найден рабочий SNI для ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}: ${attempt.preferredSni}"
                                )
                            }
                        },
                    )
                    portRetryOffset += sniRetryAttempts.size
                } else {
                    LogManager.log("Адаптация WARP: для неудачных профилей нет новых SNI-кандидатов.")
                }
            }
            if (
                !adaptToNetwork &&
                !qualityDiagnostics &&
                !warpConfigDiscoveryStopRequested.get() &&
                !isUserStopped &&
                failedAdaptationAttempts.isNotEmpty()
            ) {
                val unresolvedFailedAttempts = failedAdaptationAttempts
                    .filter { attemptExactKey(it) !in successfulAdaptationKeys }
                val portRetryAttempts = buildWarpAdaptationPortRetryAttempts(
                    failedAttempts = unresolvedFailedAttempts,
                    clientData = clientData,
                    masqueIdentityAvailable = !latestMasqueIdentityJsonForAdaptation.isNullOrBlank(),
                )
                if (portRetryAttempts.isNotEmpty()) {
                    closeActiveInterface()
                    currentAttemptOrdinal = portRetryOffset
                    currentAttemptTotal = portRetryOffset + portRetryAttempts.size
                    LogManager.log(
                        "Адаптация WARP: третий проход портов для ${unresolvedFailedAttempts.size} " +
                            "неудачных профилей, попыток=${portRetryAttempts.size}."
                    )
                    broadcastWarpConfigDiscovery(
                        true,
                        foundCount,
                        "Подбор портов: ${portRetryOffset + 1} из ${portRetryOffset + portRetryAttempts.size} для неудачных профилей..."
                    )
                    runConnectionAttempts(
                        descriptor = null,
                        descriptorFactory = { attempt ->
                            establishTunnelInterfaceForAttempt(
                                adaptationWireGuardIpv4,
                                adaptationWireGuardIpv6,
                                attempt,
                                clientData,
                            )
                        },
                        connectionAttempts = portRetryAttempts,
                        clientData = clientData,
                        wireGuardPrivateKey = adaptationWireGuardPrivateKey,
                        wireGuardIpv4 = adaptationWireGuardIpv4,
                        wireGuardIpv6 = adaptationWireGuardIpv6,
                        wireGuardPeerPub = adaptationWireGuardPeerPub,
                        wireGuardReserved = adaptationWireGuardReserved,
                        masqueIdentityJson = latestMasqueIdentityJsonForAdaptation,
                        maxCycles = 1,
                        globalAttemptOffset = portRetryOffset,
                        globalAttemptTotal = portRetryOffset + portRetryAttempts.size,
                        persistPrimarySuccess = false,
                        continueAfterVerifiedSuccess = true,
                        verifiedSuccessHoldMs = 20_000L,
                        fastScanMode = false,
                        trafficMaskHosts = emptyList(),
                        connectGenerationId = discoveryConnectGeneration,
                        externalStopRequested = { warpConfigDiscoveryStopRequested.get() },
                        useCachedAttemptTotal = false,
                        deferMasqueWithoutIdentity = true,
                        onAttemptStart = { attempt, ordinal, total ->
                            currentAttemptOrdinal = ordinal
                            currentAttemptTotal = total
                            broadcastWarpConfigDiscovery(
                                true,
                                foundCount,
                                "Подбор портов $ordinal из $total: " +
                                    "${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}"
                            )
                        },
                        onAttemptResult = { attempt, outcome, _, _ ->
                            if (outcome == AttemptOutcome.SUCCESS) {
                                successfulAdaptationKeys += attemptExactKey(attempt)
                                val attemptKey = buildWarpDiscoveryAttemptKey(
                                    attempt.mode.name,
                                    attempt.endpointHost,
                                    attempt.port,
                                )
                                val wasKnownVerified = attemptKey in knownVerifiedKeys
                                clientData.upsertWarpVerifiedConfig(
                                    engine = attempt.mode.engine,
                                    mode = attempt.mode.name,
                                    host = attempt.endpointHost,
                                    port = attempt.port,
                                    endpointSource = normalizeVerifiedConfigSource(attempt.endpointSource),
                                    rawConfig = buildWarpConfigDescription(attempt),
                                    manual = false,
                                    preferredSni = attempt.preferredSni,
                                )
                                if (!wasKnownVerified) {
                                    knownVerifiedKeys += attemptKey
                                    foundCount += 1
                                }
                                broadcastWarpConfigDiscovery(
                                    true,
                                    foundCount,
                                    "Найден рабочий порт ${attempt.port} для ${attempt.mode.name}@${attempt.endpointHost}"
                                )
                            }
                        },
                    )
                } else {
                    LogManager.log("Адаптация WARP: для неудачных профилей нет новых порт-кандидатов.")
                }
            }
            closeActiveInterface()
            val finalSnapshot = clientData.getWarpDiscoverySnapshot()
            val diagnosticsSummary = if (qualityDiagnostics && !warpConfigDiscoveryStopRequested.get()) {
                summarizeWarpQualityDiagnostics(diagnosticEntries)
            } else {
                null
            }
            broadcastWarpConfigDiscovery(
                false,
                foundCount,
                if (warpConfigDiscoveryStopRequested.get()) {
                    buildStoppedWarpDiscoveryMessage(finalSnapshot, adaptToNetwork)
                } else {
                    when {
                        qualityDiagnostics -> diagnosticsSummary ?: "Диагностика завершена"
                        adaptToNetwork -> "Адаптация завершена"
                        else -> "Проверка завершена"
                    }
                }
            )
        } catch (e: Exception) {
            val finalSnapshot = clientData.getWarpDiscoverySnapshot()
            broadcastWarpConfigDiscovery(
                false,
                foundCount,
                if (warpConfigDiscoveryStopRequested.get()) {
                    buildStoppedWarpDiscoveryMessage(finalSnapshot, adaptToNetwork)
                } else if (qualityDiagnostics) {
                    "Ошибка диагностики: ${e.message}"
                } else if (adaptToNetwork) {
                    "Ошибка адаптации: ${e.message}"
                } else {
                    "Ошибка проверки: ${e.message}"
                }
            )
        } finally {
            closeActiveInterface()
            currentAttemptOrdinal = 0
            currentAttemptTotal = 0
            warpConfigDiscoveryStopRequested.set(false)
            warpConfigDiscoveryRunning.set(false)
            isRunning = false
            broadcastState(STATE_STOPPED)
            finishForegroundShutdown()
            stopSelf()
        }
    }

    private fun startSmartConnection(
        regionPreferenceOverride: String?,
        diagnosticsMode: Boolean,
        connectGenerationId: Int,
        aggressiveFastStart: Boolean = false,
    ) {
        val clientData = ClientData(this)
        val regionPreference = normalizeRegionPreference(
            regionPreferenceOverride ?: clientData.getExitRegionPreference()
        )
        val warpAllowed = shouldUseWarpTransport(regionPreference)
        val operaAllowed = shouldAllowOperaTransport(regionPreference)
        try {
            if (!ensureFreshTransportState(connectGenerationId, "smart-connect")) return
            if (!warpAllowed) {
                clientData.saveRestartSession(
                    RestartSession(
                        kind = "opera",
                        region = regionPreference,
                    )
                )
                configureAndStartOperaOnly(regionPreference, connectGenerationId)
                return
            }

            val resolvedConfig = clientData.resolveWarpConfigForReuse(repairWithBootstrap = true)
            var config = resolvedConfig?.config
            if (config != null && resolvedConfig?.persisted == false) {
                clientData.saveConfig(config)
                LogManager.log(
                    "Smart start: восстановили WARP identity из ${resolvedConfig.source} " +
                        "и сохранили её, чтобы не регистрироваться повторно."
                )
            }
            if (config == null) {
                broadcastState(STATE_CONNECTING)
                LogManager.log("Smart start: регистрируем/восстанавливаем WARP-конфигурацию без UI.")
                val warpClient = WarpClient(
                    applicationContext,
                    LogManager::log,
                    shouldAbort = {
                        isUserStopped ||
                            explicitStopRequested ||
                            cleanupInProgress.get() ||
                            !isConnectGenerationCurrent(connectGenerationId)
                    }
                )
                config = warpClient.register()
                if (config != null) {
                    clientData.saveConfig(config)
                }
            }

            if (config != null) {
                clientData.saveRestartSession(
                    RestartSession(
                        kind = "warp",
                        region = regionPreference,
                        privateKey = config.privateKey,
                        ipv4 = config.ipv4,
                        ipv6 = config.ipv6,
                        peerPublicKey = config.peerPublicKey,
                        peerEndpoint = config.peerEndpoint,
                        reserved = config.reserved,
                        savedPort = clientData.getLastSuccessPort().takeIf { it in 1..65535 },
                        savedProto = clientData.getLastSuccessProtocol(),
                    )
                )
                configureAndStartVpn(
                    privateKey = config.privateKey,
                    ipv4 = config.ipv4,
                    ipv6 = config.ipv6,
                    peerPub = config.peerPublicKey,
                    peerEndpoint = config.peerEndpoint,
                    reserved = config.reserved,
                    savedPort = clientData.getLastSuccessPort(),
                    savedProto = clientData.getLastSuccessProtocol(),
                    regionPreferenceOverride = regionPreference,
                    allowOperaFallbackOverride = null,
                    preferWarpOnlySticky = false,
                    diagnosticsMode = diagnosticsMode,
                    aggressiveFastStart = aggressiveFastStart,
                    connectGenerationId = connectGenerationId,
                )
                return
            }

            if (operaAllowed && !diagnosticsMode) {
                clientData.saveRestartSession(
                    RestartSession(
                        kind = "opera",
                        region = regionPreference,
                    )
                )
                configureAndStartOperaOnly(regionPreference, connectGenerationId)
                return
            }

            LogManager.log("Smart start не смог получить рабочую конфигурацию.")
            isRunning = false
            setCurrentBackend(BACKEND_WARP)
            broadcastState(STATE_STOPPED)
            stopSelf()
        } catch (e: Exception) {
            LogManager.log("Smart start завершился ошибкой: ${e.message}")
            isRunning = false
            setCurrentBackend(BACKEND_WARP)
            broadcastState(STATE_STOPPED)
            stopSelf()
        }
    }

    private fun isOperaBackendLabel(label: String?): Boolean {
        return label?.trim()?.uppercase()?.startsWith(BACKEND_OPERA) == true
    }

    private fun isVlessBackendLabel(label: String?): Boolean {
        return label?.trim()?.uppercase()?.startsWith(BACKEND_VLESS) == true
    }

    private fun cleanupAndStop(
        preserveRestartSession: Boolean = false,
        unexpectedDisconnect: Boolean = false,
        forceServiceTeardown: Boolean = false,
        manualStopRequested: Boolean = false,
        allowSyntheticDetach: Boolean = true,
        allowAsyncFromMainThread: Boolean = true,
    ) {
        if (allowAsyncFromMainThread && Looper.myLooper() == Looper.getMainLooper()) {
            startSafeServiceThread("NovaCleanupStop") {
                cleanupAndStop(
                    preserveRestartSession = preserveRestartSession,
                    unexpectedDisconnect = unexpectedDisconnect,
                    forceServiceTeardown = forceServiceTeardown,
                    manualStopRequested = manualStopRequested,
                    allowSyntheticDetach = allowSyntheticDetach,
                    allowAsyncFromMainThread = false,
                )
            }
            return
        }
        if (!cleanupInProgress.compareAndSet(false, true)) {
            return
        }
        // Guard снимаем в finally: между взведением и снятием больше сотни строк, и
        // любое исключение внутри оставляло бы cleanupInProgress взведённым навсегда —
        // а с ним isConnectGenerationCurrent гасит все будущие циклы подключения.
        try {
            invalidateConnectGeneration()
            // Снимок поколения: если пока идёт разбор стека придёт явный пуск, он
            // выдаст себе номер больше этого — по нему хвост и узнаёт, что гасить
            // больше нечего.
            val cleanupGeneration = connectGeneration.get()
            val clientData = ClientData(this)
            if (!forceServiceTeardown && !unexpectedDisconnect) {
                clientData.clearSoftReapplyPending()
            }
            val delayServiceTeardownForOpera = !forceServiceTeardown && (
                operaFallbackActive ||
                operaTunThread != null ||
                isOperaBackendLabel(currentBackendLabel)
            )
            explicitStopRequested = manualStopRequested && !unexpectedDisconnect
            suppressSessionRestore = false
            isUserStopped = manualStopRequested && !unexpectedDisconnect
            isRunning = false
            currentState = STATE_STOPPED
            releaseRecoveryWakeLock()
            resetEstablishNullLoopGuard()
            currentAttemptOrdinal = 0
            currentAttemptTotal = 0
            observedUnderlyingNetworkId = null
            observedUnderlyingNetworkSignature = null
            observedUnderlyingUnavailable = false
            pendingNetworkRecoveryReason = null
            networkRecoveryHandler.removeCallbacks(networkRecoveryRunnable)
            setCurrentBackend(BACKEND_WARP)
            if (!preserveRestartSession) {
                clientData.clearRestartSession()
            }
            if (manualStopRequested && !unexpectedDisconnect) {
                cancelStopCleanupConfirmation()
            }
            LogManager.log(
                if (unexpectedDisconnect) {
                    "VPN-сеанс завершился неожиданно. Останавливаем текущий стек."
                } else {
                    "Система остановлена."
                }
            )

            stopNovaCoreEngine(allowBlockingWait = true)
            stopOperaFallback(stopProxyManager = true, allowBlockingWait = true)
            try {
                XrayBridge.stop()
            } catch (t: Throwable) {
                LogManager.log("Не удалось остановить ядро Xray при остановке сервиса: ${t.message}")
            }
            LocalDnsProxyManager.stop(LogManager::log)
            LocalAppProxyManager.stop(this, LogManager::log)
            closeActiveInterface()
            val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
            val aggressiveManualDetach = manualStopRequested && shouldUseAggressiveStopDetach()
            val shouldUseSyntheticDetach = allowSyntheticDetach && aggressiveManualDetach
            val fallbackDetachAllowed =
                allowSyntheticDetach &&
                    manualStopRequested &&
                    !unexpectedDisconnect &&
                    !preserveRestartSession
            if (shouldUseSyntheticDetach) {
                LogManager.log("Выполняем агрессивный detach VPN для явного stop на Android 10/Honor/Huawei.")
                forceDetachVpnStack()
                try {
                    Thread.sleep(180L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            try {
                Thread.sleep(220L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            if (findCurrentVpnNetwork(connectivityManager) != null) {
                if (shouldUseSyntheticDetach) {
                    try {
                        Thread.sleep(180L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    if (findCurrentVpnNetwork(connectivityManager) != null) {
                        LogManager.log("После первого detach VPN всё ещё висит в системе. Повторяем принудительный сброс.")
                        forceDetachVpnStack()
                    }
                } else if (fallbackDetachAllowed) {
                    LogManager.log(
                        "После штатного stop Android всё ещё видит VPN Nova. " +
                            "Выполняем единичный synthetic detach, чтобы снять зависший VPN-стек."
                    )
                    forceDetachVpnStack()
                    try {
                        Thread.sleep(180L)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                    if (findCurrentVpnNetwork(connectivityManager) != null) {
                        LogManager.log(
                            "После fallback detach системный VPN всё ещё не снят. " +
                                "Оставляем delayed confirmation на добивку cleanup."
                        )
                        scheduleStopCleanupConfirmation()
                    } else {
                        LogManager.log("Fallback detach успешно снял зависший VPN-стек.")
                        cancelStopCleanupConfirmation()
                    }
                } else {
                    LogManager.log(
                        "После штатного stop Android всё ещё видит VPN Nova. " +
                            "Synthetic detach пропускаем, чтобы не создавать временный VPN и не моргать Wi‑Fi."
                    )
                    if (manualStopRequested && !unexpectedDisconnect && !preserveRestartSession) {
                        scheduleStopCleanupConfirmation()
                    }
                }
            } else if (aggressiveManualDetach) {
                LogManager.log("После агрессивного detach живой VPN-сети уже не видно.")
                cancelStopCleanupConfirmation()
            } else {
                LogManager.log("VPN-стек уже снят штатно. Принудительный detach не нужен.")
                cancelStopCleanupConfirmation()
            }
            currentAttemptOrdinal = 0
            currentAttemptTotal = 0
            if (connectGeneration.get() != cleanupGeneration) {
                cancelStopCleanupConfirmation()
                if (tun2proxyForceStopAtMs != 0L) {
                    // В этой остановке звали tun2proxy_stop, значит через две секунды
                    // библиотека выполнит exit(-1) и процесс умрёт в любом случае.
                    // Поднимать в нём новую сессию — значит через эти две секунды
                    // уронить уже работающий туннель, что и происходило.
                    LogManager.log(
                        "В этой остановке был force-stop tun2proxy: процесс :vpn обречён " +
                            "и умрёт в ближайшие секунды. Новый цикл в нём не начинаем — " +
                            "просим основной процесс повторить запуск в свежем :vpn."
                    )
                    requestRestartFromMainProcess()
                    invalidateConnectGeneration()
                    return
                }
                // Пока разбирали стек, пользователь запустил подключение заново. Хвост
                // прошлой остановки не должен ни возвращать экран в «выключено», ни
                // снимать foreground, ни звать stopSelf под уже начавшимся циклом.
                LogManager.log(
                    "Хвост cleanup застал новый явный запуск (поколение ${connectGeneration.get()}). " +
                        "STOPPED, снятие foreground и stopSelf пропускаем."
                )
                return
            }
            broadcastState(STATE_STOPPED)
            // Раздача живёт отдельно от туннеля. На клиентах уже прописаны адрес и порт,
            // и терять их вместе с остановкой VPN нельзя: человек увидит не «VPN
            // выключен», а «интернет пропал», и пойдёт перенастраивать телевизор.
            // Служба остаётся именно в foreground — без него Android быстро её убьёт,
            // и шлюз исчезнет тем же способом, только с задержкой.
            if (!forceServiceTeardown && ClientData(this).isLocalProxyEnabled()) {
                cleanupInProgress.set(false)
                runCatching {
                    startForeground(
                        NOTIFICATION_ID,
                        createNotification("VPN выключен, работает только раздача"),
                    )
                }
                syncLocalAppProxy(reason = "gateway-keepalive")
                LogManager.log(
                    "VPN остановлен, но раздача включена: службу оставляем, адрес и порт " +
                        "для клиентов сохраняются."
                )
                return
            }
            finishForegroundShutdown()
            cleanupInProgress.set(false)
            if (delayServiceTeardownForOpera && !explicitStopRequested) {
                LogManager.log("Opera shutdown завершён. Сервис оставляем живым, чтобы избежать native abort tun2proxy после stop.")
                return
            }
            if (forceServiceTeardown) {
                LogManager.log("Выполняем полный stop сервиса для безопасного мягкого рестарта VPN.")
            }
            stopSelf()
        } finally {
            cleanupInProgress.set(false)
        }
    }

    private fun configureAndStartVpn(
        privateKey: String, 
        ipv4: String, 
        ipv6: String, 
        peerPub: String, 
        peerEndpoint: String,
        reserved: String?,
        savedPort: Int,
        savedProto: String,
        regionPreferenceOverride: String?,
        allowOperaFallbackOverride: Boolean? = null,
        preferWarpOnlySticky: Boolean = false,
        diagnosticsMode: Boolean = false,
        aggressiveFastStart: Boolean = false,
        recoveryCycle: Int = 0,
        connectGenerationId: Int,
    ) {
        try {
            if (!waitForPreviousCleanupIfNeeded(connectGenerationId, "warp-connect")) return
            if (!isConnectGenerationCurrent(connectGenerationId)) return
            if (!ensureFreshTransportState(connectGenerationId, "warp-connect")) return
            suppressSessionRestore = false
            val clientData = ClientData(this)
            val normalizedReserved = normalizeReservedValue(reserved)
            val regionPreference = normalizeRegionPreference(
                regionPreferenceOverride ?: clientData.getExitRegionPreference()
            )
            if (!diagnosticsMode && adoptHealthyExistingVpnIfPresent(BACKEND_WARP)) {
                return
            }
            currentAttemptOrdinal = 0
            currentAttemptTotal = 0
            currentWarpMaskHost = null
            setCurrentBackend(BACKEND_WARP)
            broadcastState(STATE_CONNECTING)
            installSocketProtector()
            installTelegramWsSignatureSecret()
            val warpAllowed = shouldUseWarpTransport(regionPreference)
            val importedConfigSourceActive = clientData.isImportedConfigSourceActive()
            val operaAllowed = shouldAllowOperaTransport(regionPreference)
            val preferWarpOnlyThisCycle = preferWarpOnlySticky || importedConfigSourceActive
            val allowOperaFallbackThisCycle = if (importedConfigSourceActive) {
                false
            } else {
                allowOperaFallbackOverride ?: (operaAllowed && !preferWarpOnlyThisCycle)
            }
            val isOperaWarpBootstrapCycle = operaBootstrapWarpGenerationId == connectGenerationId
            val aggressiveTileFastStart = aggressiveFastStart && !diagnosticsMode
            val messengerAccelerationProfile = resolveMessengerAccelerationProfile(clientData)
            val preferMessengerChatProfiles = messengerAccelerationProfile != MessengerAccelerationProfile.OFF
            val operaTargets = getOperaFallbackSequence(regionPreference)
            val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
            val selectedUnderlyingNetwork = selectUnderlyingNetwork(connectivityManager)
            val telegramTransparentProfile = resolveTelegramTransparentProfile(
                clientData = clientData,
                connectivityManager = connectivityManager,
                selectedUnderlyingNetwork = selectedUnderlyingNetwork,
            )
            setTelegramTransparentProxyConfigCompat(
                enabled = telegramTransparentProfile != "off",
                profile = telegramTransparentProfile,
            )
            if (!isUserStopped && clientData.shouldUseVlessTransport()) {
                if (runVlessPhase(clientData, connectGenerationId)) return
                if (isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) return
                closeActiveInterface()
                if (clientData.isVlessOnlyTransportMode()) {
                    // Уходить некуда: обычный пул WARP в этом режиме выключён, и цикл
                    // дошёл бы до «shortlist пуст» и погасил службу — снаружи это
                    // выглядело как «подключение само остановилось на середине».
                    // Останавливаемся честно и вслух, а не через семь секунд впустую.
                    LogManager.log(
                        "Ни один профиль VLESS не ответил, а другие протоколы в этом режиме выключены."
                    )
                    publishTransportNotice(
                        clientData,
                        "Ни один профиль VLESS не ответил. Обновите подписку.",
                    )
                    isRunning = false
                    broadcastState(STATE_STOPPED)
                    stopSelf()
                    return
                }
                LogManager.log("VLESS не дал стабильного трафика. Продолжаем по общей цепочке WARP/AWG.")
            }
            val selectedUnderlyingSignature = buildUnderlyingNetworkSignature(
                connectivityManager,
                selectedUnderlyingNetwork,
            )
            prepareCurrentCycleReconnectHints(clientData, selectedUnderlyingSignature)
            val effectiveSavedPort = if (currentCycleReuseLastSuccess) savedPort else -1
            val effectiveSavedProto = if (currentCycleReuseLastSuccess) savedProto else ""
            val underlyingNetworkMetered = isMeteredUnderlyingNetwork(
                connectivityManager = connectivityManager,
                network = selectedUnderlyingNetwork,
            )
            LogManager.log(
                "Старт connect-flow на подложной сети: " +
                    "${describeNetwork(connectivityManager, selectedUnderlyingNetwork) ?: "none"}, " +
                    "metered=$underlyingNetworkMetered"
            )
            if (telegramTransparentProfile != "off") {
                LogManager.log(
                    "Transparent Telegram relay: профиль=$telegramTransparentProfile, " +
                        "Telegram/Nagram/AyuGram трафик пойдёт через встроенный WS/WSS transport."
                )
            } else {
                // Молчащий «выключен» уже стоил одного прогона: журнал был пуст, и
                // отличить «релей не понадобился» от «правка не подействовала»
                // оказалось нечем. Причина отказа обязана быть в журнале.
                LogManager.log(
                    "Transparent Telegram relay выключен: " +
                        clientData.describeMessengerWarpProfilesRefusal() + "."
                )
            }
            if (preferMessengerChatProfiles) {
                LogManager.log(
                    "Messenger acceleration: профиль=${messengerAccelerationProfile.name.lowercase(Locale.US)}, " +
                        "первая волна ${messengerAccelerationLaneCount(messengerAccelerationProfile)} стратегий."
                )
            }
            if (currentCycleStableSuccess != null) {
                LogManager.log(
                    "Сначала пробуем последнюю стабильную WARP-стратегию: " +
                        "${currentCycleStableSuccess?.mode}@${currentCycleStableSuccess?.host}:${currentCycleStableSuccess?.port}"
                )
            } else if (clientData.hasFreshStableLastSuccess()) {
                LogManager.log(
                    "Последняя стабильная стратегия сейчас недоступна для exact-reuse. " +
                        "стартуем с верхушки verified-конфигураций."
                )
            }
            val trafficMaskMode = clientData.getTrafficMaskMode()
            val trafficMaskConfigured = !diagnosticsMode &&
                !isUserStopped &&
                clientData.getTrafficMaskEnabled() &&
                (trafficMaskMode == "auto" || trafficMaskMode == "custom")
            val restrictedMobileWarpMask =
                trafficMaskMode == "auto" &&
                    shouldForceImmediateWarpMaskOnRestrictedMobileNetwork(connectivityManager, clientData)
            val warpTrafficMaskHosts by lazy(LazyThreadSafetyMode.NONE) {
                resolveWarpTrafficMaskHosts(
                    clientData = clientData,
                )
            }
            val forceImmediateWarpMaskForRestrictedMobileNetwork =
                trafficMaskConfigured && restrictedMobileWarpMask
            val primaryWarpTrafficMaskHosts = when {
                forceImmediateWarpMaskForRestrictedMobileNetwork -> warpTrafficMaskHosts
                trafficMaskConfigured && trafficMaskMode == "custom" && warpTrafficMaskHosts.isNotEmpty() ->
                    warpTrafficMaskHosts.take(1)
                else -> emptyList()
            }
            if (forceImmediateWarpMaskForRestrictedMobileNetwork && primaryWarpTrafficMaskHosts.isNotEmpty()) {
                LogManager.log(
                    "Обнаружен ограниченный доступ на мобильной сети. " +
                        "Сразу включаем AUTO-маскировку для WARP (${primaryWarpTrafficMaskHosts.size} доменов)."
                )
                publishWarpTrafficMaskHint(
                    clientData = clientData,
                    trafficMaskHosts = primaryWarpTrafficMaskHosts,
                    attemptIndex = 0,
                )
            } else if (trafficMaskConfigured && trafficMaskMode == "auto") {
                LogManager.log(
                    "AUTO-маскировку на обычной сети не вплетаем в первичную WARP-волну: " +
                        "сначала идёт чистый core-connect, затем при необходимости masked retry."
                )
            }
            var masqueProgressBudget = 0
            var deferredMasqueIdentity: MasqueIdentity? = null
            var deferredMasqueAttempted = false
            val hasCachedMasqueIdentity = parseMasqueIdentity(clientData.getMasqueConfigJson().orEmpty()) != null
            val hasStrongVerifiedMasquePreferred = sortedVerifiedWarpConfigs(clientData).any {
                it.engine.equals("masque", ignoreCase = true) &&
                    !it.manual &&
                    it.port in setOf(500, 1701, 4500, 443, 4443, 8443, 8095)
            }
            val hasUserImportedWarpPreferred = clientData.getWarpVerifiedConfigs().any {
                it.userImported && !it.manual && !it.engine.equals("masque", ignoreCase = true)
            }
            // Выбор «MASQUE» в списке регионов — это явное указание начать именно с
            // него, а не догадка по накопленной статистике.
            val masqueChosenExplicitly =
                clientData.getExitRegionPreference().trim().lowercase(Locale.US) == "masque"
            val masqueStartDecision = MasqueStartPolicy.decide(
                MasqueStartPolicy.Inputs(
                    masqueChosenExplicitly = masqueChosenExplicitly,
                    hasCachedIdentity = hasCachedMasqueIdentity,
                    hasStrongVerifiedMasque = hasStrongVerifiedMasquePreferred,
                    hasUserImportedWarpProfiles = hasUserImportedWarpPreferred,
                    messengerFastStart = preferMessengerChatProfiles,
                    aggressiveFastStart = aggressiveTileFastStart,
                    underlyingNetworkMetered = underlyingNetworkMetered,
                    restrictedMobileNetwork = forceImmediateWarpMaskForRestrictedMobileNetwork,
                    diagnosticsMode = diagnosticsMode,
                    operaBootstrapCycle = isOperaWarpBootstrapCycle,
                    cooldownActive = clientData.shouldSkipMasqueTransport(),
                    failureStreak = clientData.getMasqueTransportFailureCount(),
                    explicitLockoutFresh = clientData.isMasqueExplicitLockoutFresh(),
                    hasFreshLastSuccess = clientData.hasFreshLastSuccess(),
                )
            )
            val preferMasqueFirstFastPath = masqueStartDecision.masqueFirst
            val deferMasquePreparationForUserImportedFastStart =
                masqueStartDecision.deferForUserImported
            val deferMasquePreparationForMessengerFastStart =
                masqueStartDecision.deferForMessenger
            val deferMasquePreparationForOrdinaryWifiFastStart =
                masqueStartDecision.deferForOrdinaryWifi
            val deferMasquePreparationForFastStart =
                masqueStartDecision.deferIdentityPreparation
            if (masqueChosenExplicitly && !masqueStartDecision.brokenDespiteExplicitChoice) {
                LogManager.log(
                    "MASQUE выбран явно: подготовку identity не откладываем и cooldown не применяем, " +
                        "иначе выбранный протокол не запустится вовсе."
                )
            }
            if (masqueStartDecision.brokenDespiteExplicitChoice) {
                // Выбран конкретный протокол, поэтому подменять его на WARP нельзя, а
                // повторять бесполезные попытки подряд — значит крутить цикл
                // переподключений. Останавливаемся и говорим, когда попробуем снова.
                val streak = clientData.getMasqueTransportFailureCount()
                val retryInMinutes =
                    (clientData.getMasqueExplicitLockoutRemainingMs() + 59_999L) / 60_000L
                LogManager.log(
                    "MASQUE выбран явно, но сорвался $streak раз подряд. " +
                        "Другие протоколы за него не подставляем — останавливаем цикл. " +
                        "Полный цикл MASQUE повторим через ~$retryInMinutes мин."
                )
                publishTransportNotice(
                    clientData,
                    "MASQUE не поднимается ($streak попытки подряд). " +
                        "Повтор через ~$retryInMinutes мин, либо выберите «Авто».",
                )
                isRunning = false
                setCurrentBackend(BACKEND_WARP)
                broadcastState(STATE_STOPPED)
                stopSelf()
                return
            }

            if (!warpAllowed) {
                val operaRoute = operaTargets.joinToString(" > ") { it.second }.ifBlank { "Opera" }
                LogManager.log("Режим региона требует прямое подключение через $operaRoute. WARP-путь пропускаем.")
            } else {
                if (isOperaWarpBootstrapCycle) {
                    LogManager.log(
                        "Временный WARP-bootstrap для Opera работает в WARP-only режиме: " +
                            "MASQUE identity через Opera не готовим, чтобы не уйти в рекурсивный Opera/WARP цикл."
                    )
                }
                if (deferMasquePreparationForOrdinaryWifiFastStart) {
                    LogManager.log(
                        "Обычный Wi‑Fi fast-path без cached MASQUE identity: " +
                            "не блокируем старт bootstrap/Opera и сначала пробуем WARP/AWG."
                    )
                }
                if (deferMasquePreparationForUserImportedFastStart) {
                    LogManager.log(
                        "Найдены пользовательские WARP/AWG профили. " +
                            "Сначала пробуем их, MASQUE оставляем fallback этой же сессии."
                    )
                }
                // В «Авто» MASQUE берём только с лицензией WARP+.
                //
                // Без неё аккаунт бесплатный, а бесплатные к службе MASQUE не допускают:
                // соединение принимается, туннель не открывается. Замер на тестовом устройстве —
                // двадцать секунд на четыре кандидата, и все впустую, после чего перебор
                // только начинает встроенные профили. Снаружи это и выглядело как
                // «счётчик показал 1/4, потом 1/50». Явный выбор MASQUE это правило не
                // трогает: попросили — пробуем, чем бы ни кончилось.
                val masqueLicensedForAuto =
                    masqueChosenExplicitly || clientData.getWarpPlusLicense().isNotBlank()
                if (!masqueLicensedForAuto && !diagnosticsMode) {
                    LogManager.log(
                        "MASQUE в режиме «Авто» пропускаем: лицензия WARP+ не задана, а на " +
                            "бесплатном аккаунте Cloudflare туннель MASQUE не открывает. " +
                            "Сразу идём по профилям WARP/AWG."
                    )
                }
                val masqueIdentity = if (
                    isOperaWarpBootstrapCycle ||
                    deferMasquePreparationForFastStart ||
                    (!masqueLicensedForAuto && !diagnosticsMode)
                ) {
                    null
                } else {
                    prepareMasqueIdentity(
                        clientData,
                        connectGenerationId = connectGenerationId,
                        trackConnectProgress = true,
                        thoroughRegistration = masqueStartDecision.thoroughRegistration,
                        // В «Авто» MASQUE — догадка, а не просьба. Регистрация ради
                        // догадки стоит десятков секунд на Opera-прокси и API Cloudflare,
                        // и всё это время счётчик показывает чужой список: сначала «1/4»
                        // от кандидатов MASQUE, потом «1/50» от встроенных профилей.
                        // Есть готовый ключ — пробуем, нет — сразу идём по WARP/AWG.
                        cachedOnly = !masqueChosenExplicitly,
                    )
                }
                if (!deferMasquePreparationForFastStart && !isConnectGenerationCurrent(connectGenerationId)) {
                    LogManager.log("Во время подготовки MASQUE identity стартовал новый connect-сеанс. Старый цикл больше не продолжаем.")
                    return
                }
                val masqueFailureCount = clientData.getMasqueTransportFailureCount()
                val skipMasqueForCooldown = masqueStartDecision.skipForCooldown
                if (skipMasqueForCooldown && !masqueChosenExplicitly && !diagnosticsMode) {
                    val retryInMinutes =
                        (clientData.getMasqueExplicitLockoutRemainingMs() + 59_999L) / 60_000L
                    LogManager.log(
                        "MASQUE сорвался $masqueFailureCount раз подряд на этой сети — в этом цикле его пропускаем " +
                            "и сразу идём по выбранным профилям WARP/AWG. Повторим примерно через $retryInMinutes мин."
                    )
                }
                if (clientData.shouldSkipMasqueTransport() && !skipMasqueForCooldown && !diagnosticsMode) {
                    LogManager.log(
                        "Недавний MASQUE cooldown найден, но на текущей сети его игнорируем " +
                            "(metered=$underlyingNetworkMetered, restricted=$forceImmediateWarpMaskForRestrictedMobileNetwork, " +
                            "verified-masque=$hasStrongVerifiedMasquePreferred)."
                    )
                }
                val deferMasqueForMessengerTraffic =
                    preferMessengerChatProfiles &&
                        !preferMasqueFirstFastPath &&
                        !diagnosticsMode &&
                        (masqueIdentity != null || deferMasquePreparationForMessengerFastStart)
                if (preferWarpOnlyThisCycle) {
                    LogManager.log("AUTO-реконнект зафиксировал свежий рабочий WARP-путь. В этом цикле не уходим в Opera fallback.")
                }
                if (preferMasqueFirstFastPath && preferMessengerChatProfiles) {
                    LogManager.log(
                        "Есть сильные verified MASQUE-конфиги. Для быстрого старта возвращаем MASQUE-first, " +
                            "а chat-aware WARP/AWG оставляем fallback этой же сессии."
                    )
                }
                if (diagnosticsMode) {
                    LogManager.log("WARP diagnostics: пропускаем MASQUE и собираем shortlist только по WireGuard/AWG режимам.")
                } else if (deferMasqueForMessengerTraffic) {
                    deferredMasqueIdentity = masqueIdentity
                    if (deferMasquePreparationForMessengerFastStart && masqueIdentity == null) {
                        LogManager.log(
                            "Messenger fast-start: подготовку MASQUE identity откладываем до первого реального fallback, " +
                                "чтобы не тормозить стартовую chat-aware волну."
                        )
                    } else if (deferMasquePreparationForOrdinaryWifiFastStart && masqueIdentity == null) {
                        LogManager.log(
                            "Обычный Wi‑Fi fast-start: подготовку MASQUE identity откладываем до реального fallback, " +
                                "чтобы не сгорать на bootstrap до WARP/AWG попыток."
                        )
                    }
                    LogManager.log(
                        "Через VPN идут мессенджеры. Первую волну начинаем с chat-aware WARP/AWG; " +
                            "MASQUE оставляем fallback внутри этой же сессии."
                    )
                } else if (masqueChosenExplicitly && masqueIdentity == null) {
                    // Пользователь выбрал MASQUE явно, но идентификатор получить не
                    // удалось: без регистрации в Cloudflare фаза MASQUE не стартует.
                    // Уходить на WARP нельзя — снаружи это выглядит как успешное
                    // подключение по выбранному протоколу, хотя работает другой.
                    LogManager.log(
                        "Выбран MASQUE, но идентификатор устройства для него не получен: " +
                            "регистрация в Cloudflare не прошла. Другие протоколы за него " +
                            "не подставляем — останавливаем цикл."
                    )
                    // Обещание: при следующем подключении, каким бы протоколом оно ни
                    // шло, ключ добудем фоном через туннель.
                    clientData.setMasqueIdentityWanted(true)
                    publishTransportNotice(
                        clientData,
                        "MASQUE недоступен: ключ устройства не получен. Подключитесь один " +
                            "раз по «Авто» — Nova выпустит ключ через туннель, и MASQUE " +
                            "снова заработает.",
                    )
                    isRunning = false
                    setCurrentBackend(BACKEND_WARP)
                    broadcastState(STATE_STOPPED)
                    stopSelf()
                    return
                } else if (!isUserStopped && masqueIdentity != null && !skipMasqueForCooldown) {
                    // Выбранный вручную MASQUE идёт на второй и третий круг.
                    //
                    // Круг кандидатов конечен, а заканчивать его некуда: на WARP мы
                    // намеренно не переключаемся, и «кандидаты кончились» превращается в
                    // «НЕ ПОДКЛЮЧЕНО». Между тем отказ здесь непостоянный: на тестовом устройстве
                    // один и тот же свой адрес то открывал CONNECT-IP за 29мс, то не
                    // отвечал на запрос вовсе. Круг стоит около сорока секунд, и второй
                    // заход — куда более разумный ответ, чем остановка.
                    var masqueCycle = 0
                    var masqueIdentityRejected = false
                    while (true) {
                        masqueCycle++
                        masqueAuthFailureObserved = false
                        masqueLastAuthError = null
                        publishTransportNotice(clientData, "")
                        masqueProgressBudget = runMasquePhase(
                            identity = masqueIdentity,
                            clientData = clientData,
                            wireGuardPrivateKey = privateKey,
                            wireGuardIpv4 = ipv4,
                            wireGuardIpv6 = ipv6,
                            wireGuardPeerPub = peerPub,
                            wireGuardReserved = reserved,
                            trafficMaskHosts = primaryWarpTrafficMaskHosts,
                            cycleUnderlyingSignature = selectedUnderlyingSignature,
                            connectGenerationId = connectGenerationId,
                            // Быстрый старт — это размен полноты кандидатов на скорость, и
                            // он уместен, только пока MASQUE идёт первым по догадке. Явный
                            // выбор получает полный скан: иначе выбранный протокол пробовал
                            // меньше вариантов, чем «Авто».
                            fastStart = preferMasqueFirstFastPath &&
                                !masqueStartDecision.thoroughCandidateScan,
                            aggressiveFastStart = aggressiveTileFastStart,
                            exhaustiveCandidates = masqueChosenExplicitly,
                        )

                        if (!isUserStopped && masqueAuthFailureObserved) {
                            LogManager.log(
                                "Cloudflare отклонил MASQUE identity (${
                                    masqueLastAuthError ?: "auth failure"
                                }). Сбрасываем и ключ, и саму запасную личность: перевыпускать ключ " +
                                    "поверх устройства, которое сервер уже не признаёт, бессмысленно. " +
                                    "Новую личность заведём фоном при следующем подключении."
                            )
                            clientData.saveMasqueConfigJson(null)
                            clientData.clearReserveWarpIdentity()
                            clientData.setMasqueIdentityWanted(true)
                            clientData.markMasqueTransportFailure()
                            masqueAuthFailureObserved = false
                            masqueLastAuthError = null
                            // Ключ отвергнут — повторять круг нечем, новый выдаёт только
                            // регистрация.
                            masqueIdentityRejected = true
                        }

                        closeActiveInterface()
                        if (isUserStopped) return
                        if (!isConnectGenerationCurrent(connectGenerationId)) return
                        if (shouldPauseConnectForMissingUnderlying(clientData, "перехода из MASQUE в WireGuard fallback")) {
                            return
                        }
                        clientData.markMasqueTransportFailure()
                        if (
                            masqueIdentityRejected ||
                            !masqueChosenExplicitly ||
                            masqueCycle >= MASQUE_EXPLICIT_CYCLE_LIMIT
                        ) {
                            break
                        }
                        LogManager.log(
                            "MASQUE выбран явно, а кандидаты круга $masqueCycle кончились без " +
                                "рабочего трафика. Идём на круг ${masqueCycle + 1} из " +
                                "$MASQUE_EXPLICIT_CYCLE_LIMIT: адреса и порты пересобираются заново."
                        )
                        publishTransportNotice(
                            clientData,
                            "MASQUE: пробуем ещё раз, круг ${masqueCycle + 1} из $MASQUE_EXPLICIT_CYCLE_LIMIT.",
                        )
                    }
                    if (masqueChosenExplicitly) {
                        // Выбран конкретный протокол, а не «Авто». Подменять его на WARP
                        // молча нельзя: снаружи это выглядит как успешное подключение по
                        // MASQUE, хотя работает другое. Перебор чужих транспортов —
                        // поведение режима «Авто».
                        LogManager.log(
                            "MASQUE не дал стабильного трафика, но выбран именно он — " +
                                "на WARP/AWG не переключаемся. Останавливаем цикл."
                        )
                        publishTransportNotice(
                            clientData,
                            if (masqueIdentityRejected) {
                                // Ключ отвергнут — новый выдаёт только регистрация в
                                // Cloudflare, а её API на многих сетях режется по SNI и
                                // проходит лишь через уже поднятый туннель. Совет «выберите
                                // Авто» здесь не про запасные протоколы, а про то, что после
                                // одного подключения по «Авто» MASQUE снова заработает.
                                "Cloudflare отклонил ключ MASQUE. Подключитесь один раз " +
                                    "по «Авто» — Nova выпустит новый ключ через туннель, " +
                                    "и MASQUE снова заработает."
                            } else {
                                "MASQUE не подключился. Выберите «Авто», чтобы Nova пробовала другие протоколы."
                            },
                        )
                        isRunning = false
                        setCurrentBackend(BACKEND_WARP)
                        broadcastState(STATE_STOPPED)
                        stopSelf()
                        return
                    }
                    LogManager.log("MASQUE не дал стабильного трафика. Переходим к WireGuard fallback.")
                } else if (skipMasqueForCooldown && masqueIdentity != null) {
                    LogManager.log(
                        "MASQUE временно пропускаем: на этой сети он недавно дал $masqueFailureCount " +
                            "быстрых срыва data-plane подряд. Сразу пробуем WireGuard/AWG."
                    )
                } else if (effectiveSavedProto.equals("MASQUE", ignoreCase = true) || effectiveSavedProto.equals("warp-plus", ignoreCase = true)) {
                    LogManager.log("MASQUE пропущен: нет сохранённых access token/device id или валидного MASQUE-кэша.")
                }
            }

            if (isUserStopped) return

            if (!warpAllowed) {
                if (operaAllowed) {
                    runOperaFallbackUntilStable(clientData, operaTargets, connectGenerationId)
                }
                if (!isUserStopped && isConnectGenerationCurrent(connectGenerationId)) {
                    isRunning = false
                    setCurrentBackend(BACKEND_WARP)
                    broadcastState(STATE_STOPPED)
                    stopSelf()
                }
                return
            }

            val endpointInfo = parseEndpoint(peerEndpoint)
            val portCandidates = buildPortCandidates(
                apiPort = endpointInfo.second,
                savedPort = effectiveSavedPort,
                savedProto = effectiveSavedProto,
                clientData = clientData
            )
            val preferredProto = normalizePreferredProtocol(effectiveSavedProto, reserved)
            val transportModes = buildTransportModes(
                reserved = reserved,
                savedProto = effectiveSavedProto
            )
            val fastPrimaryStart = !diagnosticsMode && sortedVerifiedWarpConfigs(clientData).isNotEmpty()
            val primaryTransportModes = selectPrimaryWarpTransportModes(
                transportModes = transportModes,
                clientData = clientData,
                preferMessengerChatProfiles = preferMessengerChatProfiles,
                fastStartEnabled = fastPrimaryStart,
            )
            val primaryEndpointCandidates = buildEndpointCandidates(
                apiEndpoint = endpointInfo.first,
                apiPort = endpointInfo.second,
                privateKey = privateKey,
                peerPublicKey = peerPub,
                clientData = clientData,
                includeScannerCandidates = !fastPrimaryStart,
                includeApiResolution = !fastPrimaryStart,
            )
            if (fastPrimaryStart) {
                LogManager.log(
                    "Fast-start WARP: первичную волну строим без ipscanner, " +
                        "только по verified/known-anycast endpoint-ам и укороченному набору режимов."
                )
            }
            val primaryConnectionAttempts = buildConnectionAttempts(
                endpointCandidates = primaryEndpointCandidates,
                portCandidates = portCandidates,
                transportModes = primaryTransportModes
            )
            val importedProtocolModeActive = clientData.isImportedConfigSourceActive()
            val forcedImportedProtocol = clientData.getImportedProtocolPreference()
                .takeIf { importedProtocolModeActive && !it.equals("auto", ignoreCase = true) }
            val primaryRankedAttempts = prioritizeConnectionAttempts(primaryConnectionAttempts, clientData)
            val builtInSeedConfigsForPrimary = if (!importedConfigSourceActive) {
                clientData.getWarpVerifiedConfigs()
                    .filter { clientData.isBundledSeed(it) && !it.engine.equals("masque", ignoreCase = true) }
            } else {
                emptyList()
            }
            val primaryWarpAttempts = if (importedProtocolModeActive) {
                buildUserImportedWarpAttemptSet(primaryRankedAttempts, clientData)
            } else {
                buildBuiltInWarpAttemptSet(
                    primaryRankedAttempts,
                    clientData,
                    builtInSeedConfigsForPrimary,
                    manualWarpProfileSwitchTargetKey,
                )
            }
            val importedFrontloadedInPrimaryWave = primaryWarpAttempts.any { it.importedConfigHost != null }
            val importedPrimaryProgressCount = primaryWarpAttempts
                .mapNotNull { attempt ->
                    attempt.importedConfigHost?.trim()
                        ?.removePrefix("[")
                        ?.removeSuffix("]")
                        ?.lowercase(Locale.US)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { importedHost ->
                            "$importedHost:${attempt.port}:${normalizeImportedRuntimeModeName(attempt.mode.name)}"
                        }
                }
                .distinct()
                .size
            val fullRankedAttempts by lazy(LazyThreadSafetyMode.NONE) {
                val fullEndpointCandidates = buildEndpointCandidates(
                    apiEndpoint = endpointInfo.first,
                    apiPort = endpointInfo.second,
                    privateKey = privateKey,
                    peerPublicKey = peerPub,
                    clientData = clientData,
                    includeScannerCandidates = true,
                )
                val fullConnectionAttempts = buildConnectionAttempts(
                    endpointCandidates = fullEndpointCandidates,
                    portCandidates = portCandidates,
                    transportModes = transportModes
                )
                prioritizeConnectionAttempts(fullConnectionAttempts, clientData)
            }
            val builtInProgressGroupKeys = if (!importedConfigSourceActive) {
                buildList {
                    val seen = linkedSetOf<String>()
                    builtInSeedConfigsForPrimary
                        .asSequence()
                        .sortedBy { it.seedOrder }
                        .map { buildWarpDiscoveryAttemptKey(it.mode, it.host, it.port) }
                        .filter { it.isNotBlank() }
                        .forEach { key ->
                            if (seen.add(key)) add(key)
                        }
                    primaryWarpAttempts
                        .map { buildWarpDiscoveryAttemptKey(it.mode.name, it.endpointHost, it.port) }
                        .filter { it.isNotBlank() }
                        .forEach { key ->
                            if (seen.add(key)) add(key)
                        }
                    if (isEmpty()) {
                        fullRankedAttempts
                            .map { buildWarpDiscoveryAttemptKey(it.mode.name, it.endpointHost, it.port) }
                            .filter { it.isNotBlank() }
                            .forEach { key ->
                                if (seen.add(key)) add(key)
                            }
                    }
                }.takeIf { it.isNotEmpty() }
            } else {
                null
            }
            val builtInProgressGroupKeysByCurrentQueue = if (!importedConfigSourceActive) {
                primaryWarpAttempts
                    .map { buildWarpDiscoveryAttemptKey(it.mode.name, it.endpointHost, it.port) }
                    .filter { it.isNotBlank() }
                    .distinct()
            } else {
                null
            }
            val fullWarpProgressTotalHint = if (importedConfigSourceActive) {
                importedPrimaryProgressCount.coerceAtLeast(1)
            } else {
                builtInProgressGroupKeys?.size?.coerceAtLeast(1) ?: 1
            }
            currentAttemptOrdinal = 0
            currentAttemptTotal = fullWarpProgressTotalHint
            // Метку транспорта возвращаем на WARP до первой публикации прогресса: после
            // сорвавшейся фазы MASQUE она оставалась прежней, и полсотни WARP-профилей
            // уходили в кэш под ключом MASQUE. Оттуда они возвращались в следующий цикл
            // и показывались как перебор MASQUE.
            currentTransportLabel = TRANSPORT_WARP
            clientData.rememberConnectAttemptTotal(fullWarpProgressTotalHint, BACKEND_WARP, TRANSPORT_WARP)
            setCurrentBackend(BACKEND_WARP)
            broadcastState(STATE_CONNECTING)
            LogManager.log(
                "WARP progress total warmed to $fullWarpProgressTotalHint " +
                    "(progress profiles: ${builtInProgressGroupKeys?.size}, queue profiles: ${builtInProgressGroupKeysByCurrentQueue?.size}) " +
                    "before primary attempt cycle."
            )

            if (importedProtocolModeActive && primaryWarpAttempts.isEmpty()) {
                val selectedProtocol = forcedImportedProtocol?.uppercase(Locale.US) ?: "AUTO"
                LogManager.log(
                    "USER WARP: режим импортированных конфигураций ($selectedProtocol), " +
                        "но shortlist пуст. Прерываем цикл без fallback на обычный WARP."
                )
                isRunning = false
                currentAttemptOrdinal = 0
                currentAttemptTotal = 0
                clientData.rememberConnectAttemptTotal(0, BACKEND_WARP)
                broadcastState(STATE_STOPPED)
                stopSelf()
                return
            }

            val localDescriptor = primaryWarpAttempts.firstOrNull()?.let { firstAttempt ->
                establishTunnelInterfaceForAttempt(ipv4, ipv6, firstAttempt, clientData)
            } ?: establishTunnelInterface(ipv4, ipv6, clientData)
            if (localDescriptor == null) {
                val missingVpnPermission = !hasVpnPreparationPermission()
                if (missingVpnPermission) {
                    handleUnrecoverableEstablishFailure(
                        clientData = clientData,
                        reason = "builder.establish() вернул null: у Nova больше нет системного разрешения на VPN. Останавливаем цикл и ждём повторного запуска из UI.",
                    )
                    return
                }
                LogManager.log(
                    "builder.establish() вернул null. VPN-интерфейс не создан. " +
                        "Частая причина: другой VPN всё ещё активен или система не разрешила заменить его."
                )
                if (shouldStopAfterRepeatedEstablishNull()) {
                    handleUnrecoverableEstablishFailure(
                        clientData = clientData,
                        reason = "builder.establish() несколько раз подряд возвращает null. Прекращаем бесконечный recovery-loop и переводим Nova в STOPPED."
                    )
                    return
                }
                if (scheduleWarpRetryInsteadOfStopping(clientData, connectGenerationId, "VPN-интерфейс не создан")) {
                    return
                }
                isRunning = false
                broadcastState(STATE_STOPPED)
                stopSelf()
                return
            }

            interfaceDescriptor = localDescriptor
            resetEstablishNullLoopGuard()
            LogManager.log(
                    "Интерфейс поднят. Endpoints: ${
                        primaryEndpointCandidates.joinToString(",") {
                            val suffix = if (it.preferredPort != null) ":${it.preferredPort}" else ""
                            "${it.host}$suffix(${it.source})"
                        }
                    }, Reserved: ${normalizedReserved ?: "нет"}, порты: ${portCandidates.joinToString(",")}, " +
                    "режимы: ${primaryTransportModes.joinToString(",") { it.name }}, приоритет: $preferredProto, " +
                    "top: ${primaryWarpAttempts.take(8).joinToString(",") { "${it.mode.name}@${it.endpointHost}:${it.port}" }}"
            )

            if (primaryWarpTrafficMaskHosts.isNotEmpty()) {
                publishWarpTrafficMaskHint(
                    clientData = clientData,
                    trafficMaskHosts = primaryWarpTrafficMaskHosts,
                    attemptIndex = 0,
                )
            } else {
                currentWarpMaskHost = null
                clientData.setWarpTrafficMaskActiveHost(null)
                clientData.setTrafficMaskActiveHost(null)
            }
            val primaryFailedExactKeys = linkedSetOf<String>()
            runConnectionAttempts(
                descriptor = localDescriptor,
                descriptorFactory = { attempt ->
                    establishTunnelInterfaceForAttempt(ipv4, ipv6, attempt, clientData)
                },
                connectionAttempts = primaryWarpAttempts,
                clientData = clientData,
                wireGuardPrivateKey = privateKey,
                wireGuardIpv4 = ipv4,
                wireGuardIpv6 = ipv6,
                wireGuardPeerPub = peerPub,
                wireGuardReserved = reserved,
                masqueIdentityJson = clientData.getMasqueConfigJson(),
                maxCycles = 1,
                globalAttemptOffset = 0,
                globalAttemptTotal = fullWarpProgressTotalHint,
                trafficMaskHosts = primaryWarpTrafficMaskHosts,
                cycleUnderlyingSignature = selectedUnderlyingSignature,
                connectGenerationId = connectGenerationId,
                fastConnectMode = aggressiveTileFastStart,
                progressGroupKeys = builtInProgressGroupKeys,
                onAttemptResult = { attempt, outcome, _, _ ->
                    if (outcome != AttemptOutcome.SUCCESS) {
                        primaryFailedExactKeys += attemptExactKey(attempt)
                    }
                },
            )

            if (isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) {
                closeActiveInterface()
                return
            }

            if (
                !diagnosticsMode &&
                !isUserStopped &&
                isConnectGenerationCurrent(connectGenerationId) &&
                !importedConfigSourceActive &&
                !isOperaWarpBootstrapCycle &&
                !deferredMasqueAttempted &&
                !(currentState == STATE_CONNECTED && hasRecentSuccessfulTunnelProbe())
            ) {
                val earlyMasqueIdentity = deferredMasqueIdentity ?: if (deferMasquePreparationForFastStart) {
                    LogManager.log(
                        if (deferMasquePreparationForMessengerFastStart) {
                            "Первые chat-aware WARP/AWG попытки не дали stable data-plane. " +
                                "Готовим MASQUE identity только сейчас, перед ранним fallback."
                        } else if (deferMasquePreparationForUserImportedFastStart) {
                            "Пользовательские WARP/AWG профили не дали stable data-plane. " +
                                "Готовим MASQUE identity только сейчас, перед ранним fallback."
                        } else {
                            "Обычный Wi‑Fi WARP/AWG fast-path не дал stable data-plane. " +
                                "Готовим MASQUE identity только сейчас, перед ранним fallback."
                        }
                    )
                    prepareMasqueIdentity(
                        clientData,
                        connectGenerationId = connectGenerationId,
                        trackConnectProgress = true,
                    )
                } else {
                    null
                }
                if (!isConnectGenerationCurrent(connectGenerationId)) return
                if (earlyMasqueIdentity != null) {
                    deferredMasqueIdentity = earlyMasqueIdentity
                    deferredMasqueAttempted = true
                    closeActiveInterface()
                    masqueAuthFailureObserved = false
                    masqueLastAuthError = null
                    LogManager.log(
                        "Первые chat-aware WARP/AWG попытки не дали стабильного data-plane. " +
                            "Раньше возвращаем MASQUE как fallback этой же сессии."
                    )
                    runMasquePhase(
                        identity = earlyMasqueIdentity,
                        clientData = clientData,
                        wireGuardPrivateKey = privateKey,
                        wireGuardIpv4 = ipv4,
                        wireGuardIpv6 = ipv6,
                        wireGuardPeerPub = peerPub,
                        wireGuardReserved = reserved,
                        trafficMaskHosts = primaryWarpTrafficMaskHosts,
                        connectGenerationId = connectGenerationId,
                        fastStart = true,
                        aggressiveFastStart = aggressiveTileFastStart,
                    )
                    if (!isUserStopped && masqueAuthFailureObserved) {
                        LogManager.log(
                            "Ранний MASQUE fallback отклонён (${
                                masqueLastAuthError ?: "auth failure"
                            }). Возвращаемся к masked WARP retry и дальнейшим fallback-путям."
                        )
                        clientData.saveMasqueConfigJson(null)
                        clientData.markMasqueTransportFailure()
                        masqueAuthFailureObserved = false
                        masqueLastAuthError = null
                    }
                    closeActiveInterface()
                    if (isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) {
                        return
                    }
                    if (currentState == STATE_CONNECTED && hasRecentSuccessfulTunnelProbe()) {
                        return
                    }
                }
            }

            closeActiveInterface()
            if (isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) {
                return
            }
            if (
                !diagnosticsMode &&
                !isUserStopped &&
                isConnectGenerationCurrent(connectGenerationId) &&
                !importedConfigSourceActive &&
                !isOperaWarpBootstrapCycle &&
                !deferredMasqueAttempted &&
                !(currentState == STATE_CONNECTED && hasRecentSuccessfulTunnelProbe())
            ) {
                val lateMasqueIdentity = deferredMasqueIdentity ?: if (deferMasquePreparationForFastStart) {
                    LogManager.log(
                        if (deferMasquePreparationForMessengerFastStart) {
                            "Chat-aware WARP/AWG не дал стабильного data-plane. " +
                                "Готовим MASQUE identity только сейчас, перед поздним fallback."
                        } else if (deferMasquePreparationForUserImportedFastStart) {
                            "Пользовательские WARP/AWG профили не дали стабильного data-plane. " +
                                "Готовим MASQUE identity только сейчас, перед поздним fallback."
                        } else {
                            "Обычный Wi‑Fi WARP/AWG fast-path не дал стабильного data-plane. " +
                                "Готовим MASQUE identity только сейчас, перед поздним fallback."
                        }
                    )
                    prepareMasqueIdentity(
                        clientData,
                        connectGenerationId = connectGenerationId,
                        trackConnectProgress = true,
                    )
                } else {
                    null
                }
                if (!isConnectGenerationCurrent(connectGenerationId)) return
                if (lateMasqueIdentity != null) {
                    deferredMasqueIdentity = lateMasqueIdentity
                    masqueAuthFailureObserved = false
                    masqueLastAuthError = null
                    LogManager.log(
                        "Chat-aware WARP/AWG не дал стабильного data-plane. Возвращаем MASQUE как fallback этой же сессии."
                    )
                    runMasquePhase(
                        identity = lateMasqueIdentity,
                        clientData = clientData,
                        wireGuardPrivateKey = privateKey,
                        wireGuardIpv4 = ipv4,
                        wireGuardIpv6 = ipv6,
                        wireGuardPeerPub = peerPub,
                        wireGuardReserved = reserved,
                        trafficMaskHosts = primaryWarpTrafficMaskHosts,
                        connectGenerationId = connectGenerationId,
                        fastStart = true,
                        aggressiveFastStart = aggressiveTileFastStart,
                    )
                    if (!isUserStopped && masqueAuthFailureObserved) {
                        LogManager.log(
                            "MASQUE fallback отклонён (${
                                masqueLastAuthError ?: "auth failure"
                            }). Оставляем WireGuard/AWG и дальнейшие fallback-пути."
                        )
                        clientData.saveMasqueConfigJson(null)
                        clientData.markMasqueTransportFailure()
                        masqueAuthFailureObserved = false
                        masqueLastAuthError = null
                    }
                    closeActiveInterface()
                    if (isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) {
                        return
                    }
                    if (currentState == STATE_CONNECTED && hasRecentSuccessfulTunnelProbe()) {
                        return
                    }
                }
            }
            if (
                !diagnosticsMode &&
                !isUserStopped &&
                isConnectGenerationCurrent(connectGenerationId) &&
                recoveryCycle == 0 &&
                currentState != STATE_CONNECTED &&
                !hasRecentSuccessfulTunnelProbe()
            ) {
                LogManager.log(
                    "Полный WARP-цикл не дал стабильного data-plane. " +
                        "Сбрасываем stale WARP transport learning, cached MASQUE identity " +
                        "и возвращаем verified-конфиги к bundled seeds."
                )
                val fullFailureCount = clientData.noteWarpFullCycleFailure()
                clientData.resetWarpTransportLearning()
                clientData.saveMasqueConfigJson(null)
                clientData.clearLastSuccessIfProtocol("MASQUE")
                currentWarpMaskHost = null
                val stableLastSuccessAgeMs = (System.currentTimeMillis() - clientData.getStableLastSuccessAt())
                    .coerceAtLeast(0L)
                val shouldColdResetStoredBootstrap =
                    clientData.canRunWarpColdReset() &&
                        (
                            (fullFailureCount >= 1 && stableLastSuccessAgeMs > 6L * 60L * 60L * 1000L) ||
                                (fullFailureCount >= 2 && stableLastSuccessAgeMs > 30L * 60L * 1000L)
                        )
                if (shouldColdResetStoredBootstrap) {
                    LogManager.log(
                        "WARP несколько полных циклов подряд не даёт data-plane. " +
                            "Выполняем self-heal без очистки данных: сбрасываем cached WARP bootstrap, " +
                            "restart-session и сетевое обучение, чтобы следующий запуск заново зарегистрировал конфигурацию."
                    )
                    clientData.resetWarpRuntimeState(clearStoredConfig = true)
                }
            }
            if (shouldPauseConnectForMissingUnderlying(clientData, "перехода к Opera/recovery fallback")) {
                return
            }
            if (!diagnosticsMode && !isUserStopped && allowOperaFallbackThisCycle && isConnectGenerationCurrent(connectGenerationId)) {
                currentAttemptOrdinal = 0
                currentAttemptTotal = 0
                broadcastState(STATE_CONNECTING)
                runOperaFallbackUntilStable(clientData, operaTargets, connectGenerationId)
            }
            if (isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) {
                return
            }
            val shouldRunRecoveryCycle = !diagnosticsMode &&
                !isUserStopped &&
                clientData.getAutoReconnect() &&
                recoveryCycle < 1 &&
                !aggressiveTileFastStart &&
                operaBootstrapWarpGenerationId != connectGenerationId &&
                isConnectGenerationCurrent(connectGenerationId)
            if (shouldRunRecoveryCycle) {
                val nextCycle = recoveryCycle + 1
                LogManager.log(
                    "Полный цикл подключения не дал стабильного data-plane. " +
                        "Запускаем recovery-цикл ${nextCycle + 1}/2 с обновлённым ранжированием."
                )
                currentAttemptOrdinal = 0
                currentAttemptTotal = 0
                currentWarpMaskHost = null
                clientData.setWarpTrafficMaskActiveHost(null)
                clientData.setTrafficMaskActiveHost(null)
                setCurrentBackend(BACKEND_WARP)
                broadcastState(STATE_CONNECTING)
                try {
                    Thread.sleep(1200L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                configureAndStartVpn(
                    privateKey = privateKey,
                    ipv4 = ipv4,
                    ipv6 = ipv6,
                    peerPub = peerPub,
                    peerEndpoint = peerEndpoint,
                    reserved = reserved,
                    savedPort = effectiveSavedPort,
                    savedProto = effectiveSavedProto,
                    regionPreferenceOverride = regionPreference,
                    allowOperaFallbackOverride = allowOperaFallbackOverride,
                    preferWarpOnlySticky = preferWarpOnlySticky,
                    diagnosticsMode = diagnosticsMode,
                    recoveryCycle = nextCycle,
                    connectGenerationId = connectGenerationId,
                )
                return
            }
            if (!isUserStopped && isConnectGenerationCurrent(connectGenerationId)) {
                if (operaBootstrapWarpGenerationId == connectGenerationId) {
                    if (
                        fallbackToDirectOperaAfterWarpBootstrapFailure(
                            clientData = clientData,
                            reason = "Временный WARP-bootstrap для Opera не дал рабочего соединения."
                        )
                    ) {
                        return
                    }
                    stopFailedOperaWarpBootstrap(clientData, "Временный WARP-bootstrap для Opera не дал рабочего соединения.")
                    return
                }
                if (clientData.getAutoReconnect()) {
                    if (clientData.getRestartSession() == null) {
                        val rebuiltSession = buildWarpRestartSessionFromAttemptInputs(
                            regionPreference = regionPreference,
                            privateKey = privateKey,
                            ipv4 = ipv4,
                            ipv6 = ipv6,
                            peerPub = peerPub,
                            peerEndpoint = peerEndpoint,
                            reserved = reserved,
                            savedPort = effectiveSavedPort,
                            savedProto = effectiveSavedProto,
                        ) ?: buildWarpRestartSessionFromCurrentConfig(clientData, regionPreference)
                        if (rebuiltSession != null) {
                            clientData.saveRestartSession(rebuiltSession)
                            LogManager.log(
                                "Автоповтор WARP: пересобрали restart session перед новым циклом."
                            )
                        }
                    }
                    LogManager.log("Все попытки подключений исчерпаны, автопереподключение включено. Повторный запуск цикла...")
                    currentAttemptOrdinal = 0
                    currentAttemptTotal = 0
                    broadcastState(STATE_CONNECTING)
                    try { Thread.sleep(4000L) } catch (_: Exception) {}
                    if (!isUserStopped && isConnectGenerationCurrent(connectGenerationId)) {
                        val intent = android.content.Intent(applicationContext, NovaVpnService::class.java).apply {
                            action = ACTION_RESTORE_LAST_SESSION
                        }
                        androidx.core.content.ContextCompat.startForegroundService(applicationContext, intent)
                    }
                    return
                }

                isRunning = false
                currentAttemptOrdinal = 0
                currentAttemptTotal = 0
                setCurrentBackend(BACKEND_WARP)
                broadcastState(STATE_STOPPED)
                stopSelf()
            }
        } catch (e: Exception) {
            LogManager.log("Критическая ошибка: ${e.message}")
            closeActiveInterface()
            val clientData = ClientData(this)
            // Сорвалась именно фаза MASQUE — засчитываем это ей. Без счётчика
            // явный выбор MASQUE повторял бы неудачную попытку бесконечно:
            // цикл планирует новый заход, а тот снова начинается с MASQUE.
            if (currentTransportLabel == TRANSPORT_MASQUE) {
                clientData.markMasqueTransportFailure()
                LogManager.log(
                    "Ошибка пришлась на фазу MASQUE. Отмечаем срыв, " +
                        "чтобы повторы не превратились в бесконечный цикл."
                )
            }
            if (operaBootstrapWarpGenerationId == connectGenerationId) {
                if (
                    fallbackToDirectOperaAfterWarpBootstrapFailure(
                        clientData = clientData,
                        reason = "Временный WARP-bootstrap для Opera завершился ошибкой."
                    )
                ) {
                    return
                }
                stopFailedOperaWarpBootstrap(clientData, "Временный WARP-bootstrap для Opera завершился ошибкой.")
                return
            }
            if (scheduleWarpRetryInsteadOfStopping(clientData, connectGenerationId, "Критическая ошибка WARP-цикла: ${e.message}")) {
                return
            }
            isRunning = false
            currentAttemptOrdinal = 0
            currentAttemptTotal = 0
            setCurrentBackend(BACKEND_WARP)
            broadcastState(STATE_STOPPED)
            stopSelf()
        }
    }

    private fun scheduleWarpRetryInsteadOfStopping(
        clientData: ClientData,
        connectGenerationId: Int,
        reason: String,
        delayMs: Long = 2200L,
    ): Boolean {
        val session = clientData.getRestartSession()
        if (isUserStopped || explicitStopRequested || cleanupInProgress.get()) return false
        if (!clientData.getAutoReconnect()) return false
        if (session == null || session.kind != "warp") return false
        if (!isConnectGenerationCurrent(connectGenerationId)) return false

        LogManager.log("$reason. Вместо раннего STOPPED планируем новый WARP-цикл.")
        currentAttemptOrdinal = 0
        currentAttemptTotal = 0
        clientData.markTransientConnectingPending((delayMs + 10_000L).coerceAtLeast(10_000L))
        broadcastState(STATE_CONNECTING)
        startSafeServiceThread("NovaScheduleWarpRetry") {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@startSafeServiceThread
            }
            if (isUserStopped || explicitStopRequested || cleanupInProgress.get()) return@startSafeServiceThread
            if (!isConnectGenerationCurrent(connectGenerationId)) return@startSafeServiceThread
            runCatching {
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, NovaVpnService::class.java).apply {
                        action = ACTION_RESTORE_LAST_SESSION
                    }
                )
            }.onFailure { error ->
                LogManager.log("Не удалось запустить WARP recovery после ошибки: ${error.message}")
            }
        }
        return true
    }

    private fun scheduleOperaRetryInsteadOfStopping(
        clientData: ClientData,
        connectGenerationId: Int,
        regionPreference: String,
        reason: String,
        delayMs: Long = 2600L,
    ): Boolean {
        val normalizedRegion = normalizeRegionPreference(regionPreference)
        if (normalizedRegion != "eu" && normalizedRegion != "us") return false
        if (isUserStopped || explicitStopRequested || cleanupInProgress.get()) return false
        if (!clientData.getAutoReconnect()) return false
        if (!isConnectGenerationCurrent(connectGenerationId)) return false

        val backendHint = getOperaFallbackSequence(normalizedRegion)
            .firstOrNull()
            ?.second
            ?.let { "$BACKEND_OPERA-$it" }
            ?: BACKEND_OPERA
        LogManager.log(
            "$reason. Вместо STOPPED оставляем состояние подключения и планируем новый Opera-цикл."
        )
        clientData.saveRestartSession(
            RestartSession(
                kind = "opera",
                region = normalizedRegion,
            )
        )
        currentAttemptOrdinal = 0
        currentAttemptTotal = clientData.getCachedConnectAttemptTotal(backendHint)
        clientData.markTransientConnectingPending((delayMs + 14_000L).coerceAtLeast(12_000L))
        setCurrentBackend(backendHint)
        broadcastState(STATE_CONNECTING)
        startSafeServiceThread("NovaScheduleOperaRetry") {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return@startSafeServiceThread
            }
            if (isUserStopped || explicitStopRequested || cleanupInProgress.get()) return@startSafeServiceThread
            if (!isConnectGenerationCurrent(connectGenerationId)) return@startSafeServiceThread
            runCatching {
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, NovaVpnService::class.java).apply {
                        action = ACTION_START_OPERA_ONLY
                        putExtra(EXTRA_EXIT_REGION, normalizedRegion)
                    }
                )
            }.onFailure { error ->
                LogManager.log("Не удалось запустить Opera recovery после ошибки: ${error.message}")
            }
        }
        return true
    }

    private fun installSocketProtector() {
        Nova.setSocketProtector(object : nova.SocketProtector {
            override fun protect(socketFd: Long): Boolean {
                return this@NovaVpnService.protect(socketFd.toInt())
            }
        })
    }

    override fun onTun2ProxyLog(message: String) {
        if (message.isNotBlank()) {
            LogManager.log("[tun2proxy] $message")
            if (isTransportConnectivityFailureLog(message)) {
                noteTransportConnectivityLoss("tun2proxy", message)
            }
            if (isOperaUpstreamBadGatewayLog(message)) {
                noteOperaUpstreamBadGateway(message)
            }
        }
    }

    private fun resolveDnsServersForBuilder(
        clientData: ClientData,
        backendLabel: String,
        countryHint: String? = null,
    ): Pair<List<String>, String> {
        val dnsServers = clientData.getPreferredVpnDnsServers(backendLabel, countryHint)
        val dnsLabel = clientData.getPreferredVpnDnsLabel(backendLabel, countryHint)
        LocalDnsProxyManager.stop(LogManager::log)
        if (clientData.isLocalDnsProxyPlanned()) {
            LogManager.log(
                "Локальный DNS-proxy пока не активирован: Android блокирует bind на 127.0.0.1:53 без TUN-level перехвата DNS."
            )
        }
        if (dnsLabel == "adguard-noads-media-profile") {
            val adguardReachable = DnsProbe.isReachable(
                context = this,
                servers = dnsServers,
                cacheKeyPrefix = "$backendLabel-media-adguard",
                logger = LogManager::log,
                protector = { socket -> protect(socket) },
            )
            if (!adguardReachable) {
                val fallbackServers = clientData.getFallbackVpnDnsServers(backendLabel, countryHint)
                val fallbackLabel = clientData.getFallbackVpnDnsLabel(backendLabel, countryHint)
                LogManager.log(
                    "AdGuard DNS сейчас недоступен, откатываемся на обычный профиль: $fallbackLabel " +
                        "(${fallbackServers.joinToString(",")})"
                )
                return fallbackServers to fallbackLabel
            }
        }
        return dnsServers to dnsLabel
    }

    private fun applyCoreDnsInterceptConfig(
        clientData: ClientData,
        backendLabel: String,
        countryHint: String?,
        dnsServers: List<String>,
        dnsLabel: String,
    ) {
        val defaultServers = dnsServers
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val enableTargetedMediaDns =
            backendLabel == BACKEND_WARP && clientData.shouldEnableTargetedMediaDns(backendLabel, countryHint)
        val mediaServers = if (enableTargetedMediaDns) {
            clientData.getMediaAdBlockDnsServers()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        } else {
            emptyList()
        }
        val mediaDomains = if (enableTargetedMediaDns) {
            clientData.getMediaAdBlockDnsDomains()
                .map { it.trim().trim('.') }
                .filter { it.isNotBlank() }
                .distinct()
        } else {
            emptyList()
        }
        try {
            if (enableTargetedMediaDns && mediaServers.isNotEmpty() && mediaDomains.isNotEmpty()) {
                val adguardReachable = DnsProbe.isReachable(
                    context = this,
                    servers = mediaServers,
                    cacheKeyPrefix = "$backendLabel-targeted-media-adguard",
                    logger = LogManager::log,
                    protector = { socket: java.net.DatagramSocket -> protect(socket) },
                )
                if (adguardReachable) {
                    Nova.setDnsInterceptPolicy(
                        true,
                        mediaServers.joinToString(","),
                        defaultServers.joinToString(","),
                        mediaDomains.joinToString(","),
                    )
                    LogManager.log(
                        "TUN-level targeted DNS intercept активирован: " +
                            "media=${mediaServers.joinToString(",")} " +
                            "fallback=${defaultServers.joinToString(",")} " +
                            "domains=${mediaDomains.joinToString(",")}"
                    )
                    return
                }
                LogManager.log(
                    "AdGuard DNS для targeted intercept сейчас недоступен, оставляем обычный DNS: " +
                        "$dnsLabel (${defaultServers.joinToString(",")})"
                )
            }
            applyPlainDnsIntercept(defaultServers, reason = dnsLabel)
        } catch (t: Throwable) {
            LogManager.log("Не удалось обновить TUN-level DNS intercept: ${t.message}")
        }
    }

    /**
     * Включает перехват всего DNS внутри туннеля с заданным порядком резолверов.
     *
     * Зачем: список DNS из `VpnService.Builder` меняется только переустановкой
     * туннеля, то есть с обрывом всех соединений. Состояние перехвата в ядре живёт
     * под мьютексом и перечитывается на каждом запросе, поэтому порядок можно
     * поменять на лету — например, когда выяснилась настоящая страна выхода.
     *
     * Пустой список доменов означает «перехватывать всё»: ядро в этом случае шлёт
     * любой запрос в объединённый список апстримов по порядку. Список передаётся
     * первым параметром, потому что включение перехвата в ядре завязано именно на
     * его непустоту.
     */
    private fun applyPlainDnsIntercept(servers: List<String>, reason: String) {
        val normalized = servers.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (normalized.isEmpty()) {
            Nova.setDnsInterceptPolicy(false, "", "", "")
            activeDnsInterceptServers = emptyList()
            return
        }
        Nova.setDnsInterceptPolicy(true, normalized.joinToString(","), "", "")
        activeDnsInterceptServers = normalized
        LogManager.log(
            "TUN-level DNS intercept: порядок резолверов задан без переустановки туннеля " +
                "($reason): ${normalized.joinToString(", ")}"
        )
    }

    /**
     * Пересматривает порядок DNS, когда стала известна настоящая страна выхода.
     *
     * До первого наблюдения импортированный AWG считается российским, поэтому первым
     * идёт Xbox DNS. Если выход оказался зарубежным, порядок должен смениться на
     * «сначала DNS из профиля» — и сделать это надо, не роняя соединения.
     */
    private fun reapplyDnsOrderForObservedCountry(clientData: ClientData, observedCountry: String) {
        val country = observedCountry.trim().uppercase(Locale.US)
        if (country.isBlank()) return
        val current = activeDnsInterceptServers
        if (current.isEmpty()) return
        val importedDns = lastImportedProfileDnsServers
        if (importedDns.isEmpty()) return

        val novaDns = clientData.getPreferredVpnDnsServers(BACKEND_WARP, country)
        val desired = if (country == "RU") {
            (novaDns + importedDns).distinct()
        } else {
            (importedDns + novaDns).distinct()
        }
        if (desired == current) return
        LogManager.log(
            "Страна выхода определена как $country — меняем порядок DNS на лету, без переподключения."
        )
        applyPlainDnsIntercept(desired, reason = "exit-country-$country")
    }

    private fun establishTunnelInterface(
        ipv4: String,
        ipv6: String,
        clientData: ClientData,
        identityOverride: TunnelInterfaceIdentity? = null,
        dnsOverride: List<String> = emptyList(),
        localBypassProtectedAddresses: Set<String> = emptySet(),
        mtuOverride: Int? = null,
        addressPlanOverride: List<ImportedInterfaceAddress>? = null,
    ): ParcelFileDescriptor? {
        val builder = Builder()
        clearPreparedTransportState()
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val underlyingNetwork = selectUnderlyingNetwork(connectivityManager)
        val warpTunnelMtu = resolveWarpTunnelMtu(
            connectivityManager = connectivityManager,
            underlyingNetwork = underlyingNetwork,
            clientData = clientData,
        )
        val effectiveTunnelMtu = mtuOverride ?: warpTunnelMtu
        currentWarpTunnelMtu = effectiveTunnelMtu
        builder.setMtu(effectiveTunnelMtu)
        if (mtuOverride != null) {
            LogManager.log("MTU туннеля задан транспортом: $effectiveTunnelMtu (по умолчанию $warpTunnelMtu).")
        }
        val requestedAddresses = identityOverride?.addresses.orEmpty()
        val addressSource = if (requestedAddresses.isNotEmpty()) {
            identityOverride?.source ?: "imported"
        } else {
            "nova"
        }
        val interfaceAddresses = addressPlanOverride ?: dropIpv6AddressesBelowMinimumMtu(
            addresses = normalizeTunnelInterfaceAddresses(
                requested = requestedAddresses.ifEmpty {
                    buildList {
                        if (ipv4.isNotBlank()) add(ImportedInterfaceAddress(ipv4, 32))
                        if (ipv6.isNotBlank()) add(ImportedInterfaceAddress(ipv6, 128))
                    }
                },
                source = addressSource,
            ),
            mtu = effectiveTunnelMtu,
        )
        if (interfaceAddresses.isEmpty()) {
            LogManager.log(
                "TUN identity: $addressSource — ни одного пригодного адреса " +
                    "(${describeTunnelAddressInput(ipv4)} / ${describeTunnelAddressInput(ipv6)}), интерфейс не поднимаем."
            )
            return null
        }
        interfaceAddresses.forEach { interfaceAddress ->
            builder.addAddress(interfaceAddress.address, interfaceAddress.prefixLength)
        }
        LogManager.log(
            "TUN identity: $addressSource " +
                "(${interfaceAddresses.joinToString(",") { it.toConfigValue() }}) " +
                "исходно v4=${describeTunnelAddressInput(ipv4)} v6=${describeTunnelAddressInput(ipv6)}"
        )
        val importedDnsServers = dnsOverride
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val (dnsServers, dnsLabel) = if (importedDnsServers.isNotEmpty()) {
            importedDnsServers to "imported-awg"
        } else {
            resolveDnsServersForBuilder(
                clientData = clientData,
                backendLabel = BACKEND_WARP,
            )
        }
        dnsServers.forEach(builder::addDnsServer)
        LogManager.log(
            "DNS профиль VPN: $dnsLabel (${dnsServers.joinToString(",")})"
        )
        applyCoreDnsInterceptConfig(
            clientData = clientData,
            backendLabel = BACKEND_WARP,
            countryHint = null,
            dnsServers = dnsServers,
            dnsLabel = dnsLabel,
        )
        builder.setSession("NovaVPN")

        applySplitTunnelPolicy(builder, clientData)
        val underlyingDescription = describeNetwork(connectivityManager, underlyingNetwork)
        observedUnderlyingNetworkId = underlyingNetwork?.toString()
        observedUnderlyingNetworkSignature = buildUnderlyingNetworkSignature(connectivityManager, underlyingNetwork)
        observedUnderlyingUnavailable = underlyingNetwork == null
        LogManager.log(
            if (underlyingDescription != null) {
                "Подложная сеть для VPN: $underlyingDescription"
            } else {
                "Подложная сеть для VPN не найдена, оставляем выбор системе."
            }
        )
        LogManager.log("MTU для WARP/MASQUE: $warpTunnelMtu")
        configureInternetRoutes(
            builder = builder,
            enableIpv6DefaultRoute = interfaceAddresses.any { it.address.contains(':') },
            connectivityManager = connectivityManager,
            underlyingNetwork = underlyingNetwork,
            protectedAddresses = localBypassProtectedAddresses,
        )
        applyUnderlyingNetworkHint(
            builder = builder,
            underlyingNetwork = underlyingNetwork,
            backendLabel = BACKEND_WARP,
        )
        applyPrivateDnsBypass(builder, connectivityManager, underlyingNetwork)

        val descriptor = try {
            builder.establish()
        } catch (e: Exception) {
            LogManager.log(
                "Система отказалась поднимать TUN " +
                    "(${addressSource}: ${interfaceAddresses.joinToString(",") { it.toConfigValue() }}, " +
                    "MTU $effectiveTunnelMtu): ${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
        if (descriptor != null || addressPlanOverride != null) {
            return descriptor
        }
        for (fallbackPlan in tunnelInterfaceAddressFallbackPlans(interfaceAddresses)) {
            LogManager.log(
                "Повторяем поднятие TUN сокращённым набором адресов: " +
                    fallbackPlan.joinToString(",") { it.toConfigValue() }
            )
            val fallbackDescriptor = establishTunnelInterface(
                ipv4 = ipv4,
                ipv6 = ipv6,
                clientData = clientData,
                identityOverride = identityOverride,
                dnsOverride = dnsOverride,
                localBypassProtectedAddresses = localBypassProtectedAddresses,
                mtuOverride = mtuOverride,
                addressPlanOverride = fallbackPlan,
            )
            if (fallbackDescriptor != null) {
                return fallbackDescriptor
            }
        }
        return null
    }

    /**
     * Приводит адреса TUN к тому виду, который принимает `VpnService.Builder`.
     *
     * Источники у адресов разные: WARP-регистрация, импортированный конфиг и MASQUE-профиль
     * Cloudflare. Формат при этом никто не гарантирует — может прийти CIDR (`172.16.0.2/32`),
     * адрес в скобках, со scope-суффиксом или пустая строка. Раньше такие значения уходили в
     * `addAddress` как есть, и весь подъём интерфейса падал исключением.
     */
    private fun normalizeTunnelInterfaceAddresses(
        requested: List<ImportedInterfaceAddress>,
        source: String,
    ): List<ImportedInterfaceAddress> {
        val normalized = linkedSetOf<ImportedInterfaceAddress>()
        requested.forEach { candidate ->
            val parsed = normalizeTunnelInterfaceAddress(candidate.address, candidate.prefixLength)
            if (parsed == null) {
                LogManager.log(
                    "TUN identity: $source — адрес ${describeTunnelAddressInput(candidate.address)} " +
                        "не разобран, пропускаем."
                )
            } else {
                normalized.add(parsed)
            }
        }
        return normalized.toList()
    }

    private fun normalizeTunnelInterfaceAddress(
        rawAddress: String,
        defaultPrefixLength: Int,
    ): ImportedInterfaceAddress? {
        val trimmed = rawAddress.trim()
        if (trimmed.isBlank()) return null
        val host = trimmed.substringBefore('/')
            .trim()
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')
            .trim()
        if (host.isBlank()) return null
        val looksNumeric = if (host.contains(':')) {
            Regex("^[0-9a-fA-F:.]+$").matches(host)
        } else {
            Regex("^[0-9.]+$").matches(host)
        }
        if (!looksNumeric) return null
        val parsed = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return null
        val maxPrefixLength = if (parsed is Inet6Address) 128 else 32
        val prefixLength = trimmed.substringAfter('/', "")
            .trim()
            .toIntOrNull()
            ?.takeIf { it in 0..maxPrefixLength }
            ?: defaultPrefixLength.coerceIn(0, maxPrefixLength)
        val canonical = parsed.hostAddress
            ?.substringBefore('%')
            ?.takeIf { it.isNotBlank() }
            ?: host
        return ImportedInterfaceAddress(canonical, prefixLength)
    }

    /**
     * Убирает IPv6-адреса с интерфейса, чей MTU меньше [IPV6_MIN_LINK_MTU].
     *
     * IPv6 требует канала не меньше 1280 байт (RFC 8200), и ядро отказывается включать
     * его на интерфейсе с меньшим MTU: `establish()` падает с `Cannot set address`, не
     * уточняя причину. У MASQUE MTU равен 1179 — он вычисляется из размера пакета QUIC,
     * см. [resolveMasqueTunnelMtu], и поднять его нельзя: датаграммы QUIC не
     * фрагментируются, поэтому всё, что не поместилось, просто пропадает. Значит,
     * IPv6 внутри MASQUE-туннеля не живёт, и честнее не заявлять его вовсе.
     *
     * IPv4 при этом должен остаться: если адрес только один и он IPv6, убирать нечего —
     * пусть система откажет сама, зато с исходным набором в логе.
     */
    private fun dropIpv6AddressesBelowMinimumMtu(
        addresses: List<ImportedInterfaceAddress>,
        mtu: Int,
    ): List<ImportedInterfaceAddress> {
        if (mtu >= IPV6_MIN_LINK_MTU) return addresses
        val ipv4Only = addresses.filter { !it.address.contains(':') }
        if (ipv4Only.isEmpty() || ipv4Only.size == addresses.size) return addresses
        LogManager.log(
            "MTU туннеля $mtu меньше минимума IPv6 ($IPV6_MIN_LINK_MTU) — " +
                "IPv6 внутри туннеля не поднимаем, остаётся только IPv4."
        )
        return ipv4Only
    }

    /**
     * Запасные наборы адресов на случай, когда система не принимает исходный.
     *
     * Отказ обычно приходит из-за одной из семей: например, IPv6-адрес MASQUE-профиля не
     * ложится на устройство без IPv6. Двухшаговый откат (только IPv4, затем только IPv6)
     * оставляет туннелю шанс подняться вместо срыва всей фазы.
     */
    private fun tunnelInterfaceAddressFallbackPlans(
        addresses: List<ImportedInterfaceAddress>,
    ): List<List<ImportedInterfaceAddress>> {
        val ipv4Only = addresses.filter { !it.address.contains(':') }
        val ipv6Only = addresses.filter { it.address.contains(':') }
        if (ipv4Only.isEmpty() || ipv6Only.isEmpty()) return emptyList()
        return listOf(ipv4Only, ipv6Only)
    }

    private fun describeTunnelAddressInput(rawAddress: String): String {
        if (rawAddress.isEmpty()) return "<пусто>"
        val flags = buildList {
            if (rawAddress != rawAddress.trim()) add("пробелы")
            if (rawAddress.contains('/')) add("cidr")
            if (rawAddress.contains('[') || rawAddress.contains(']')) add("скобки")
            if (rawAddress.contains('%')) add("scope")
        }
        val suffix = if (flags.isEmpty()) "" else " ${flags.joinToString("+")}"
        return "\"$rawAddress\" len=${rawAddress.length}$suffix"
    }

    private fun establishOperaTunnelInterface(clientData: ClientData): ParcelFileDescriptor? {
        val builder = Builder()
        clearPreparedTransportState()
        builder.setSession("NovaOperaVPN")
        builder.setMtu(1420)
        builder.addAddress("10.1.10.1", 24)
        val (resolvedDnsServers, dnsLabel) = resolveDnsServersForBuilder(
            clientData = clientData,
            backendLabel = BACKEND_OPERA,
        )
        val dnsServers = resolvedDnsServers
            .filter { !it.contains(':') }
            .ifEmpty { listOf("1.1.1.1", "1.0.0.1") }
            .take(2)
        dnsServers.forEach(builder::addDnsServer)
        LogManager.log(
            "Opera fallback DNS через tun2proxy: " +
                "$dnsLabel (${dnsServers.joinToString(",")})"
        )
        applyCoreDnsInterceptConfig(
            clientData = clientData,
            backendLabel = BACKEND_OPERA,
            countryHint = null,
            dnsServers = emptyList(),
            dnsLabel = "",
        )
        applyOperaSplitTunnelPolicy(builder, clientData)
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val underlyingNetwork = selectUnderlyingNetwork(connectivityManager)
        observedUnderlyingNetworkId = underlyingNetwork?.toString()
        observedUnderlyingNetworkSignature = buildUnderlyingNetworkSignature(connectivityManager, underlyingNetwork)
        observedUnderlyingUnavailable = underlyingNetwork == null
        configureInternetRoutes(
            builder = builder,
            enableIpv6DefaultRoute = false,
            connectivityManager = connectivityManager,
            underlyingNetwork = underlyingNetwork,
        )
        applyUnderlyingNetworkHint(
            builder = builder,
            underlyingNetwork = underlyingNetwork,
            backendLabel = BACKEND_OPERA,
        )
        applyPrivateDnsBypass(builder, connectivityManager, underlyingNetwork)
        return builder.establish()
    }

    /**
     * Интерфейс TUN для VLESS.
     *
     * Устроен как у Opera-прокси: ядро Xray поднимает SOCKS5 на петле, а пакеты в него
     * заворачивает тот же `tun2proxy`. Поэтому и адрес интерфейса частный, и IPv6 внутри
     * нет — `tun2proxy` ходит наружу только по IPv4-петле.
     */
    private fun establishVlessTunnelInterface(
        clientData: ClientData,
        socksPort: Int,
    ): ParcelFileDescriptor? {
        val builder = Builder()
        clearPreparedTransportState()
        builder.setSession("NovaVlessVPN")
        builder.setMtu(VLESS_TUN_MTU)
        builder.addAddress(VLESS_TUN_ADDRESS, 24)
        val (resolvedDnsServers, dnsLabel) = resolveDnsServersForBuilder(
            clientData = clientData,
            backendLabel = BACKEND_VLESS,
        )
        val dnsServers = resolvedDnsServers
            .filter { !it.contains(':') }
            .ifEmpty { listOf("1.1.1.1", "1.0.0.1") }
            .take(2)
        dnsServers.forEach(builder::addDnsServer)
        LogManager.log("VLESS DNS через tun2proxy: $dnsLabel (${dnsServers.joinToString(",")})")
        applyCoreDnsInterceptConfig(
            clientData = clientData,
            backendLabel = BACKEND_VLESS,
            countryHint = null,
            dnsServers = emptyList(),
            dnsLabel = "",
        )
        applyOperaSplitTunnelPolicy(builder, clientData)
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val underlyingNetwork = selectUnderlyingNetwork(connectivityManager)
        observedUnderlyingNetworkId = underlyingNetwork?.toString()
        observedUnderlyingNetworkSignature = buildUnderlyingNetworkSignature(connectivityManager, underlyingNetwork)
        observedUnderlyingUnavailable = underlyingNetwork == null
        configureInternetRoutes(
            builder = builder,
            enableIpv6DefaultRoute = false,
            connectivityManager = connectivityManager,
            underlyingNetwork = underlyingNetwork,
        )
        applyUnderlyingNetworkHint(
            builder = builder,
            underlyingNetwork = underlyingNetwork,
            backendLabel = BACKEND_VLESS,
        )
        applyPrivateDnsBypass(builder, connectivityManager, underlyingNetwork) { privateDnsHost ->
            // 853/tcp через прокси — обычное TCP-соединение, tun2proxy его прокачает.
            // Проверяем до подъёма интерфейса: ядро уже поднято и проверено, а решение
            // о маршруте надо принять до establish.
            openSocksTunnel(socksPort, privateDnsHost, 853, 4_000)?.use { true } ?: false
        }
        return try {
            builder.establish()
        } catch (e: Exception) {
            LogManager.log("Система отказалась поднимать TUN для VLESS: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun findEphemeralLoopbackPort(): Int? {
        return try {
            java.net.ServerSocket().use { socket ->
                socket.bind(InetSocketAddress("127.0.0.1", 0))
                socket.localPort
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Фаза VLESS: ядро Xray → SOCKS5 на петле → tun2proxy → TUN.
     *
     * Перебор идёт сам. Не подключились к текущему профилю — сразу берём следующий,
     * а текущий уводим вниз списка; ждать, пока пользователь нажмёт «следующий
     * профиль», больше не нужно. Порядок начинается с активной записи: первая строка
     * подписки обычно давно протухла, и стартовать с неё значило бы каждый раз
     * проходить одни и те же мёртвые узлы.
     *
     * Порт SOCKS выбирается один раз на всю фазу, и это принципиально: смена профиля
     * перезапускает только ядро Xray, а TUN и tun2proxy остаются на месте. Поэтому
     * после первого живого узла перебор идёт без разрыва туннеля — «без пауз и
     * остановок» в буквальном смысле, а не «быстро переподключаемся».
     *
     * Счётчик показывает место профиля в списке. Поэтому наказание откладывается до
     * конца прохода: если уводить запись вниз сразу, следующая занимает её место, и
     * текущий профиль всегда остаётся первым — счётчик застревал бы на «1/151».
     * Отвергнутые уезжают вниз одной пачкой в момент успеха или в конце прохода, и
     * тогда рабочий профиль честно становится первым, а следующий за ним — вторым.
     */
    private fun runVlessPhase(clientData: ClientData, connectGenerationId: Int): Boolean {
        var rotation = clientData.getVlessProfileLinks()
            .mapNotNull { link -> VlessConfig.parse(link)?.let { link to it } }
        if (rotation.isEmpty()) {
            LogManager.log("VLESS выбран, но ни один профиль не разбирается.")
            publishTransportNotice(clientData, "Профиль VLESS не разобран.")
            return false
        }
        if (!XrayBridge.ensureLoaded()) {
            LogManager.log("VLESS недоступен: ядро Xray не загрузилось (${XrayBridge.lastLoadError()}).")
            publishTransportNotice(clientData, "VLESS недоступен на этом устройстве.")
            return false
        }
        if (!OperaNativeVpnService.isNativeRuntimeAvailable(this)) {
            LogManager.log(
                "VLESS недоступен: для ABI ${Build.SUPPORTED_ABIS.joinToString()} нет native-библиотеки tun2proxy."
            )
            publishTransportNotice(clientData, "VLESS недоступен на этом устройстве.")
            return false
        }

        currentTransportLabel = TRANSPORT_VLESS
        clientData.rememberConnectAttemptTotal(rotation.size, BACKEND_VLESS, TRANSPORT_VLESS)
        setCurrentBackend(BACKEND_VLESS)

        val socksPort = findEphemeralLoopbackPort() ?: VLESS_SOCKS_FALLBACK_PORT
        XrayBridge.setProtector(this)
        LogManager.log("VLESS: перебор ${rotation.size} профилей, SOCKS5 на 127.0.0.1:$socksPort.")

        pendingVlessProfileSwitch = false
        vlessRotationActive = true
        vlessSocksPort = socksPort
        // Бюджет времени осмыслен только там, где есть куда уйти. В режиме «только
        // VLESS» запасной цепочки нет, и сдача по таймеру означала не «попробуем
        // другое», а «выключаемся на середине списка».
        val fallbackAvailable = !clientData.isVlessOnlyTransportMode()
        val rejected = LinkedHashSet<String>()
        var tunnelUp = false
        var searchDeadline = SystemClock.elapsedRealtime() + VLESS_SEARCH_BUDGET_MS
        // Начинаем с активной записи, но идём по самому списку: номер на экране — это
        // место профиля в списке, а не в перестановке под перебор.
        var cursor = clientData.getVlessRotationStartIndex().coerceIn(0, rotation.lastIndex)
        var tried = 0
        try {
            while (tried < rotation.size) {
                if (isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) return true
                // Бюджет ограничивает только холодный поиск. Когда туннель уже поднят,
                // торопиться некуда: уход в запасную цепочку сети не добавит.
                if (fallbackAvailable && !tunnelUp && SystemClock.elapsedRealtime() > searchDeadline) {
                    LogManager.log(
                        "VLESS: за ${VLESS_SEARCH_BUDGET_MS / 1000} с живого узла не нашлось " +
                            "(пройдено $tried из ${rotation.size}). Уходим в запасную цепочку."
                    )
                    break
                }
                val (link, config) = rotation[cursor]
                // Номер — место профиля в списке, а не счётчик попыток: наказание
                // отложено, порядок за проход не меняется, и числа не скачут.
                currentAttemptOrdinal = cursor + 1
                currentAttemptTotal = rotation.size
                cursor = (cursor + 1) % rotation.size
                tried++
                pendingVlessProfileSwitch = false

                // Активная ссылка двигается вместе с перебором: если службу убьют на
                // середине, следующий запуск продолжит с того же места, а не с начала.
                clientData.setVlessConfigLink(link)
                broadcastState(STATE_CONNECTING)
                LogManager.log(
                    "VLESS профиль $currentAttemptOrdinal/${rotation.size}: ${config.displayName} " +
                        "(${config.host}:${config.port}, ${config.network})."
                )

                val startError = XrayBridge.start(VlessXrayConfig.build(config, socksPort))
                if (startError.isNotBlank()) {
                    XrayBridge.stop()
                    rejected += link
                    reportVlessProfileRejected(clientData, config, "ядро не приняло конфигурацию: $startError")
                    continue
                }
                if (!awaitVlessProxyReady(socksPort, connectGenerationId)) {
                    if (isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) return true
                    XrayBridge.stop()
                    // Пробу оборвал сам пользователь кнопкой — узел ответить не успел,
                    // и наказывать его не за что.
                    if (pendingVlessProfileSwitch) continue
                    rejected += link
                    reportVlessProfileRejected(clientData, config, "узел не ответил")
                    continue
                }

                if (!tunnelUp) {
                    if (!startVlessTunnel(clientData, socksPort)) return false
                    tunnelUp = true
                }
                // Отметку об успешной пробе ставим только с поднятым туннелем: сторожевые
                // таймеры считают по ней, что сеть в порядке, и до establish она соврала бы.
                markSuccessfulTunnelProbe()

                // Узел жив: отвергнутые уезжают вниз, рабочий поднимается наверх, список
                // пересобирается. С этого момента рабочий профиль — первый в списке, а
                // «следующий» — честно второй.
                val demoted = clientData.demoteVlessProfileLinks(rejected.toList())
                val promoted = clientData.promoteVlessProfileLink(link)
                if (demoted || promoted) {
                    rotation = clientData.getVlessProfileLinks()
                        .mapNotNull { stored -> VlessConfig.parse(stored)?.let { stored to it } }
                        .ifEmpty { rotation }
                    clientData.rememberConnectAttemptTotal(rotation.size, BACKEND_VLESS, TRANSPORT_VLESS)
                }
                rejected.clear()
                val activeIndex = rotation.indexOfFirst { it.first == link }.takeIf { it >= 0 } ?: 0
                cursor = (activeIndex + 1) % rotation.size
                val triedBeforeSession = tried
                tried = 0
                currentAttemptOrdinal = activeIndex + 1
                currentAttemptTotal = rotation.size

                LogManager.log(
                    "VLESS активен: ${config.displayName} — профиль $currentAttemptOrdinal/${rotation.size}."
                )
                publishTransportNotice(clientData, "")
                broadcastState(STATE_CONNECTED)

                val session = holdVlessSession(clientData, socksPort, connectGenerationId)
                if (session.outcome == VlessSessionOutcome.RELEASED) return true
                XrayBridge.stop()
                if (session.outcome == VlessSessionOutcome.SWITCH_REQUESTED) {
                    // Профиль сменил пользователь, а не отказ узла: наказывать не за что,
                    // и туннель остаётся поднятым — меняется только ядро на том же порту.
                    // Курсор уже стоит на следующей записи, брать её и надо.
                    searchDeadline = SystemClock.elapsedRealtime() + VLESS_SEARCH_BUDGET_MS
                    continue
                }
                // Новый круг даётся только за сессию, продержавшуюся заметное время.
                // Узел, срывающийся сразу после подключения, иначе обнулял бы счётчик
                // после каждого срыва, и перебор крутился бы на нём вечно.
                if (session.uptimeMs < VLESS_SESSION_CREDIT_MS) tried = triedBeforeSession
                searchDeadline = SystemClock.elapsedRealtime() + VLESS_SEARCH_BUDGET_MS
                rejected += link
                reportVlessProfileRejected(clientData, config, "туннель потерял data-plane")
                if (session.outcome == VlessSessionOutcome.TUNNEL_GONE) {
                    // tun2proxy завершился сам; тот же дескриптор ему больше не отдать,
                    // поэтому следующий живой узел получит свежий TUN.
                    stopVlessTransport()
                    tunnelUp = false
                }
            }
            if (!tunnelUp) {
                LogManager.log("Ни один профиль VLESS не ответил. Продолжаем по запасной цепочке.")
                publishTransportNotice(clientData, "Ни один профиль VLESS не ответил.")
            } else {
                LogManager.log(
                    "VLESS: круг пройден целиком, ни один профиль не удержал трафик. " +
                        "Уходим в запасную цепочку."
                )
            }
            return false
        } finally {
            vlessRotationActive = false
            pendingVlessProfileSwitch = false
            vlessSocksPort = -1
            clientData.clearTransportLatency()
            // Наказание переживает выход из фазы: без этого сорванный проход возвращал
            // бы следующему циклу тот же список мёртвых записей в том же порядке.
            if (clientData.demoteVlessProfileLinks(rejected.toList())) {
                clientData.getVlessProfileLinks().firstOrNull()?.let(clientData::setVlessConfigLink)
            }
            if (!(currentState == STATE_CONNECTED && hasRecentSuccessfulTunnelProbe())) {
                stopVlessTransport()
            }
        }
    }

    /**
     * Просит идущий перебор VLESS взять следующий профиль, не разрывая туннель.
     *
     * Какой профиль следующий, решает сама служба: список она же и переставляет, а
     * экран живёт в другом процессе и видит порядок на момент своего запуска. Пока
     * ссылку выбирал экран, кнопка возвращала перебор к уже отвергнутым узлам.
     *
     * Возвращает false, если перебора нет: тогда вызывающая сторона поднимает сессию
     * обычным путём.
     */
    private fun requestVlessProfileSwitchInPlace(): Boolean {
        if (!vlessRotationActive) return false
        pendingVlessProfileSwitch = true
        LogManager.log("VLESS: берём следующий профиль по кнопке, без разрыва туннеля.")
        return true
    }

    /** Сообщает об отвергнутом профиле. Вниз списка он уедет пачкой, в конце прохода. */
    private fun reportVlessProfileRejected(
        clientData: ClientData,
        config: VlessConfig,
        reason: String,
    ) {
        LogManager.log("VLESS профиль ${config.displayName} отклонён ($reason).")
        publishTransportNotice(
            clientData,
            "Профиль $currentAttemptOrdinal/$currentAttemptTotal не ответил — берём следующий.",
        )
    }

    /**
     * Ждём, пока ядро начнёт пропускать трафик через свой SOCKS5-inbound.
     *
     * Проба идёт до подъёма TUN и наружу через сам узел, поэтому отвечает ровно на
     * тот вопрос, который сейчас нужен, — жив ли профиль. Раньше проверка стояла
     * после подъёма интерфейса, и каждая мёртвая запись стоила полного цикла
     * establish/teardown; теперь мёртвый узел не трогает системный VPN вовсе.
     */
    private fun awaitVlessProxyReady(socksPort: Int, connectGenerationId: Int): Boolean {
        val deadline = SystemClock.elapsedRealtime() + VLESS_CANDIDATE_PROBE_BUDGET_MS
        while (!isUserStopped &&
            isConnectGenerationCurrent(connectGenerationId) &&
            // Нажатие кнопки важнее текущего кандидата: дожидаться его ответа значит
            // держать пользователя ещё несколько секунд на узле, который он уже отверг.
            !pendingVlessProfileSwitch
        ) {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            if (remainingMs <= 0L) return false
            // Ждём весь остаток бюджета, а не фиксированные две секунды: на медленном
            // узле полный оборот занимает под два, и жёсткий потолок отбраковывал бы
            // живые записи ровно на границе. Не поднявшийся инбаунд отказывает мгновенно,
            // так что на повторную попытку время всё равно остаётся.
            if (hasVlessProxyConnectivity(socksPort, remainingMs.toInt())) return true
            // Inbound может ещё не успеть встать на порт: тогда проба возвращается
            // мгновенно, и без этой паузы цикл сжёг бы бюджет вхолостую.
            Thread.sleep(150L)
        }
        return false
    }

    /** Поднимает TUN и tun2proxy поверх уже работающего SOCKS5-инбаунда ядра. */
    private fun startVlessTunnel(clientData: ClientData, socksPort: Int): Boolean {
        val descriptor = establishVlessTunnelInterface(clientData, socksPort)
        if (descriptor == null) {
            LogManager.log("Не удалось поднять VPN-интерфейс для VLESS.")
            return false
        }
        interfaceDescriptor = descriptor
        resetEstablishNullLoopGuard()
        val tunFd = try {
            descriptor.detachFd()
        } catch (e: Exception) {
            LogManager.log("Не удалось detachFd для VLESS: ${e.message}")
            closeActiveInterface()
            return false
        }

        operaTunLastExitCode = null
        val tunThread = Thread {
            try {
                val exitCode = OperaNativeVpnService.runTun2proxy(
                    this,
                    "socks5://127.0.0.1:$socksPort",
                    tunFd,
                    true,
                    VLESS_TUN_MTU.toChar(),
                    TUN2PROXY_DNS_VIRTUAL,
                    3,
                )
                operaTunLastExitCode = exitCode
                LogManager.log("[tun2proxy] VLESS завершён с кодом $exitCode")
            } catch (t: Throwable) {
                operaTunLastExitCode = -1
                LogManager.log("[tun2proxy] Ошибка VLESS: ${t.message}")
            }
        }.apply {
            name = "NovaVlessTunThread"
            start()
        }
        // Учёт нити ведём теми же полями, что и Opera: tun2proxy в процессе один,
        // и общий stopOperaFallback остаётся единственной точкой его остановки.
        operaTunThread = tunThread
        operaFallbackActive = true
        return true
    }

    private enum class VlessSessionOutcome {
        /** Цикл прервали снаружи: остановка пользователем или новое подключение. */
        RELEASED,

        /** Узел перестал пропускать трафик — пора взять следующий профиль. */
        DATA_PLANE_LOST,

        /** tun2proxy завершился сам, туннель придётся поднимать заново. */
        TUNNEL_GONE,

        /** Профиль сменил пользователь кнопкой; узел ни в чём не виноват. */
        SWITCH_REQUESTED,
    }

    private data class VlessSessionResult(
        val outcome: VlessSessionOutcome,
        val uptimeMs: Long,
    )

    /** Держит поднятый туннель VLESS, пока узел отдаёт трафик. */
    private fun holdVlessSession(
        clientData: ClientData,
        socksPort: Int,
        connectGenerationId: Int,
    ): VlessSessionResult {
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val tunThread = operaTunThread
        val startedAtMs = SystemClock.elapsedRealtime()
        var vpnNetwork: android.net.Network? = null
        var failures = 0
        var lastHealthyAtMs = startedAtMs
        fun uptimeMs(): Long = SystemClock.elapsedRealtime() - startedAtMs

        while (!isUserStopped && isConnectGenerationCurrent(connectGenerationId)) {
            if (pendingVlessProfileSwitch) {
                return VlessSessionResult(VlessSessionOutcome.SWITCH_REQUESTED, uptimeMs())
            }
            if (tunThread != null && !tunThread.isAlive) {
                LogManager.log("tun2proxy для VLESS завершился. Поднимаем туннель заново.")
                return VlessSessionResult(VlessSessionOutcome.TUNNEL_GONE, uptimeMs())
            }
            Thread.sleep(1500L)
            vpnNetwork = findCurrentVpnNetwork(connectivityManager) ?: vpnNetwork
            val validated = isValidatedVpnNetwork(connectivityManager, vpnNetwork)
            val tunnelReady = hasTunnelConnectivity(vpnNetwork, 1200, allowHttpDnsFallback = false)
            // Проба живости и есть замер задержки: запрос идёт наружу через сам узел.
            // Экран измерить не может — при раздельном туннелировании он снаружи VPN,
            // а порт SOCKS-инбаунда ему неизвестен, поэтому «Ping: ---» висел всегда.
            val probeStartedAtMs = SystemClock.elapsedRealtime()
            // Бюджет с запасом на медленный узел: замер на живом сингапурском выходе —
            // 1974 мс, и при прежних 1800 мс проба не успевала. Туннель работал, а
            // пинга на экране не было вовсе, потому что публиковать было нечего.
            val proxyReady = hasVlessProxyConnectivity(socksPort, VLESS_SESSION_PROBE_TIMEOUT_MS)
            if (proxyReady) {
                clientData.publishTransportLatency(
                    (SystemClock.elapsedRealtime() - probeStartedAtMs).toInt().coerceAtLeast(0),
                    TRANSPORT_VLESS,
                )
            }
            if (validated || tunnelReady || proxyReady) {
                failures = 0
                lastHealthyAtMs = SystemClock.elapsedRealtime()
                markSuccessfulTunnelProbe()
            } else {
                failures++
                if (failures >= 4 && SystemClock.elapsedRealtime() - lastHealthyAtMs >= 6_000L) {
                    LogManager.log("VLESS потерял рабочий data-plane. Переключаемся на следующий профиль.")
                    return VlessSessionResult(VlessSessionOutcome.DATA_PLANE_LOST, uptimeMs())
                }
            }
        }
        // Цикл прервали снаружи: остановка пользователем или новый цикл подключения.
        // В обоих случаях запасная цепочка не нужна — ей займётся тот, кто прервал.
        return VlessSessionResult(VlessSessionOutcome.RELEASED, uptimeMs())
    }

    /**
     * Прямая проверка SOCKS5-inbound ядра Xray.
     *
     * Проба через сеть VPN тут не работает: при раздельном туннелировании пакет Nova
     * остаётся вне VPN, и своей же сети VPN он не видит — `findCurrentVpnNetwork`
     * возвращает null, а `hasTunnelConnectivity` вместе с ним всегда false. Для Opera
     * ту же дыру закрывает [hasOperaProxyConnectivity]; здесь то же самое, только через
     * SOCKS5, а не HTTP-прокси. Петля наружу не маршрутизируется, поэтому проверка
     * одинаково честна и когда приложение внутри VPN, и когда снаружи.
     */
    private fun hasVlessProxyConnectivity(socksPort: Int, timeoutMs: Int): Boolean {
        val host = "api.ipify.org"
        return try {
            openSocksTunnel(socksPort, host, 80, timeoutMs)?.use { socket ->
                val output = socket.getOutputStream()
                output.write(
                    (
                        "GET http://$host/ HTTP/1.1\r\n" +
                            "Host: $host\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                )
                output.flush()
                socket.getInputStream().bufferedReader().readLine().orEmpty().contains("200")
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Открывает соединение до `host:port` через SOCKS5-inbound ядра.
     *
     * Имя резолвит узел, а не устройство: за тем прокси и нужен. Возвращает уже
     * готовый сокет — вызывающая сторона обязана его закрыть — или null, если узел
     * отказал.
     */
    private fun openSocksTunnel(socksPort: Int, host: String, port: Int, timeoutMs: Int): Socket? {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", socksPort), timeoutMs)
            socket.soTimeout = timeoutMs
            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            // Приветствие: версия 5, один метод, без авторизации.
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = ByteArray(2)
            if (!readFully(input, greeting)) return null.also { socket.close() }
            if (greeting[0].toInt() != 0x05 || greeting[1].toInt() != 0x00) {
                return null.also { socket.close() }
            }

            val hostBytes = host.toByteArray(Charsets.US_ASCII)
            val request = ByteArray(7 + hostBytes.size)
            request[0] = 0x05
            request[1] = 0x01
            request[2] = 0x00
            request[3] = 0x03
            request[4] = hostBytes.size.toByte()
            hostBytes.copyInto(request, 5)
            request[5 + hostBytes.size] = (port ushr 8).toByte()
            request[6 + hostBytes.size] = (port and 0xFF).toByte()
            output.write(request)
            output.flush()

            val head = ByteArray(4)
            if (!readFully(input, head)) return null.also { socket.close() }
            if (head[0].toInt() != 0x05 || head[1].toInt() != 0x00) {
                return null.also { socket.close() }
            }
            val boundLength = when (head[3].toInt() and 0xFF) {
                0x01 -> 4
                0x04 -> 16
                0x03 -> {
                    val length = ByteArray(1)
                    if (!readFully(input, length)) return null.also { socket.close() }
                    length[0].toInt() and 0xFF
                }
                else -> return null.also { socket.close() }
            }
            if (!readFully(input, ByteArray(boundLength + 2))) return null.also { socket.close() }
            socket
        } catch (e: Exception) {
            try {
                socket?.close()
            } catch (_: Exception) {
            }
            null
        }
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }

    private fun stopVlessTransport() {
        stopOperaFallback(joinTimeoutMs = 2500L, stopProxyManager = false)
        try {
            XrayBridge.stop()
        } catch (t: Throwable) {
            LogManager.log("Не удалось остановить ядро Xray: ${t.message}")
        }
    }

    private fun runOperaFallbackUntilStable(
        clientData: ClientData,
        operaTargets: List<Pair<String, String>>,
        connectGenerationId: Int,
    ): OperaFallbackResult {
        if (operaTargets.isEmpty()) return OperaFallbackResult.FAILED

        var cycle = 0
        val maxCycles = if (operaTargets.size == 1) 3 else 3
        while (!isUserStopped && cycle < maxCycles && isConnectGenerationCurrent(connectGenerationId)) {
            var reconnectRequested = false
            for ((country, label) in operaTargets) {
                when (val result = runOperaFallback(clientData, country, label, connectGenerationId)) {
                    OperaFallbackResult.CONNECTED -> return result
                    OperaFallbackResult.NEED_RECONNECT -> {
                        reconnectRequested = true
                        break
                    }
                    OperaFallbackResult.FAILED -> {
                    }
                }
            }
            cycle++
            if ((!reconnectRequested && cycle >= maxCycles) || !clientData.getAutoReconnect() || isUserStopped) {
                return OperaFallbackResult.FAILED
            }
            LogManager.log("Реконнект при обрыве связи: повторяем доступные Opera-регионы (цикл $cycle/$maxCycles)...")
            Thread.sleep(1200L)
        }
        return if (isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) {
            OperaFallbackResult.CONNECTED
        } else {
            OperaFallbackResult.FAILED
        }
    }

    private fun maybePrewarmOperaEndpointsThroughCurrentWarp(clientData: ClientData) {
        if (!OperaProxyManager.isRegistrationSupportedOnDevice(this)) return
        if (!currentBackendLabel.trim().uppercase(Locale.US).startsWith(BACKEND_WARP)) return
        if (currentState == STATE_CONNECTED) {
            clientData.clearTransientConnectingPending()
            clientData.clearSoftReapplyPending()
            clientData.saveServiceState(
                STATE_CONNECTED,
                currentBackendLabel.ifBlank { BACKEND_WARP },
                0,
                0,
            )
        }
        val now = SystemClock.elapsedRealtime()
        val pendingBootstrapRegion = clientData.getPendingOperaBootstrapViaWarpRegion()
        val bootstrapHandoffPending = pendingBootstrapRegion.isNotBlank()
        if (!bootstrapHandoffPending && now - lastOperaEndpointPrewarmAtMs < 30L * 60L * 1000L) return
        if (!operaEndpointPrewarmRunning.compareAndSet(false, true)) return
        lastOperaEndpointPrewarmAtMs = now
        startSafeServiceThread("NovaOperaEndpointPrewarm") {
            try {
                val targets = getOperaFallbackSequence(
                    pendingBootstrapRegion.ifBlank { clientData.getExitRegionPreference() }
                )
                    .ifEmpty { listOf("EU" to "EU", "AM" to "US") }
                    .distinctBy { it.first }
                if (targets.isEmpty()) return@startSafeServiceThread
                LogManager.log(
                    "WARP активен: фоново проверяем Opera endpoints через текущий VPN, " +
                        "чтобы при ошибках Opera 801/500/502 потом стартовать Opera напрямую через cached override."
                )
                var bootstrapDiscovered = false
                for ((country, label) in targets) {
                    if (isUserStopped || currentState != STATE_CONNECTED) break
                    val readyThroughWarp = tryPrepareOperaThroughCurrentWarp(
                        clientData = clientData,
                        country = country,
                        label = label,
                        bootstrapHandoffPending = bootstrapHandoffPending,
                    )
                    when {
                        readyThroughWarp -> {
                            bootstrapDiscovered = true
                        }
                        else -> {
                            val result = OperaProxyManager.discoverPinnedEndpoints(
                                context = applicationContext,
                                logger = ::logOperaProxyManagerMessage,
                                country = country,
                                preferGlobalMaskHosts = true,
                                maxMaskHostAttempts = if (bootstrapHandoffPending) 4 else 2,
                                shouldAbort = {
                                    isUserStopped ||
                                        currentState != STATE_CONNECTED ||
                                        !currentBackendLabel.trim().uppercase(Locale.US).startsWith(BACKEND_WARP)
                                },
                            )
                            when {
                                result.endpoints.isNotEmpty() -> {
                                    bootstrapDiscovered = true
                                    LogManager.log("Opera $label endpoints обновлены через WARP-bootstrap: ${result.endpoints.size}.")
                                }
                                result.isApiBlocked ->
                                    LogManager.log("Opera $label discovery даже через текущий VPN вернул code=${result.apiCode}.")
                                else ->
                                    LogManager.log("Opera $label discovery через текущий VPN не вернул endpoints.")
                            }
                        }
                    }
                }
                if (bootstrapHandoffPending) {
                    if (bootstrapDiscovered) {
                        handoffFromWarpBootstrapToOpera(clientData, pendingBootstrapRegion)
                    } else {
                        if (
                            !fallbackToDirectOperaAfterWarpBootstrapFailure(
                                clientData = clientData,
                                reason = "WARP-bootstrap не смог добыть Opera endpoints."
                            )
                        ) {
                            stopFailedOperaWarpBootstrap(
                                clientData = clientData,
                                reason = "WARP-bootstrap не смог добыть Opera endpoints."
                            )
                        }
                    }
                }
            } finally {
                operaEndpointPrewarmRunning.set(false)
            }
        }
    }

    private fun tryPrepareOperaThroughCurrentWarp(
        clientData: ClientData,
        country: String,
        label: String,
        bootstrapHandoffPending: Boolean,
    ): Boolean {
        val readyState = OperaProxyManager.ensureReady(
            context = applicationContext,
            logger = ::logOperaProxyManagerMessage,
            purposeLabel = if (bootstrapHandoffPending) {
                "регистрации Opera через WARP-bootstrap"
            } else {
                "фоновой проверки Opera через активный WARP"
            },
            country = country,
            preferGlobalMaskHosts = true,
            readyTimeoutMs = if (bootstrapHandoffPending) 11_000L else 9_000L,
            maxMaskHostAttempts = if (bootstrapHandoffPending) 4 else 3,
            shouldAbort = {
                isUserStopped ||
                    currentState != STATE_CONNECTED ||
                    !currentBackendLabel.trim().uppercase(Locale.US).startsWith(BACKEND_WARP)
            },
        )
        return when (readyState) {
            OperaProxyManager.ReadyState.ALREADY_RUNNING,
            OperaProxyManager.ReadyState.STARTED_INTERNAL -> {
                val cachedEndpoints = clientData.getOperaPinnedEndpoints(country)
                LogManager.log(
                    "Opera $label registration/discovery через текущий WARP успешны. " +
                        "Кэш endpoints: ${cachedEndpoints.size}."
                )
                OperaProxyManager.stopManaged(::logOperaProxyManagerMessage)
                clientData.setTrafficMaskActiveHost(null)
                true
            }
            OperaProxyManager.ReadyState.FAILED -> {
                val apiCode = OperaProxyManager.getLastFailureApiCode()
                if (apiCode == 801 || apiCode == 500 || apiCode == 502) {
                    LogManager.log(
                        "Opera $label даже через текущий WARP вернула code=$apiCode. " +
                            "Пытаемся хотя бы добыть pinned endpoints через list-proxies."
                    )
                } else {
                    LogManager.log("Opera $label ensureReady через текущий WARP не дал рабочего proxy.")
                }
                false
            }
        }
    }

    private fun handoffFromWarpBootstrapToOpera(clientData: ClientData, regionPreference: String) {
        val normalizedRegion = normalizeRegionPreference(regionPreference)
        if (normalizedRegion != "eu" && normalizedRegion != "us") return
        LogManager.log("Opera endpoints получены через WARP-bootstrap. Мягко переключаемся на Opera ${normalizedRegion.uppercase(Locale.US)}.")
        clientData.clearOperaBootstrapViaWarpPending()
        operaBootstrapWarpGenerationId = -1
        val nextGeneration = beginConnectGeneration(stopExisting = true)
        if (!isConnectGenerationCurrent(nextGeneration) || isUserStopped) return
        configureAndStartOperaOnly(normalizedRegion, nextGeneration)
    }

    private fun fallbackToDirectOperaAfterWarpBootstrapFailure(
        clientData: ClientData,
        reason: String,
    ): Boolean {
        val normalizedRegion = normalizeRegionPreference(clientData.getPendingOperaBootstrapViaWarpRegion())
        if (normalizedRegion != "eu" && normalizedRegion != "us") {
            return false
        }
        LogManager.log(
            "$reason Возвращаемся к прямому Opera ${normalizedRegion.uppercase(Locale.US)} " +
                "и пробуем следующий endpoint/API без WARP."
        )
        clientData.clearOperaBootstrapViaWarpPending()
        operaBootstrapWarpGenerationId = -1
        val nextGeneration = beginConnectGeneration(stopExisting = true)
        if (!isConnectGenerationCurrent(nextGeneration) || isUserStopped || cleanupInProgress.get()) {
            return true
        }
        isRunning = true
        configureAndStartOperaOnly(normalizedRegion, nextGeneration)
        return true
    }

    private fun stopFailedOperaWarpBootstrap(
        clientData: ClientData,
        reason: String,
    ) {
        LogManager.log("$reason Останавливаем bootstrap без autoreconnect-loop.")
        clientData.clearOperaBootstrapViaWarpPending()
        operaBootstrapWarpGenerationId = -1
        isRunning = false
        currentAttemptOrdinal = 0
        currentAttemptTotal = 0
        setCurrentBackend(BACKEND_WARP)
        broadcastState(STATE_STOPPED)
        stopSelf()
    }

    private fun tryStartOperaBootstrapViaWarp(
        clientData: ClientData,
        regionPreference: String,
        connectGenerationId: Int,
    ): Boolean {
        val normalizedRegion = normalizeRegionPreference(regionPreference)
        if (normalizedRegion != "eu" && normalizedRegion != "us") return false
        if (!isConnectGenerationCurrent(connectGenerationId) || isUserStopped) return false
        // Временный WARP — это другой протокол и другая страна выхода. В «Авто» такой
        // размен делает само приложение и он законен, при выбранном EU/US — нет:
        // ровно так выбранный регион и превращался в WARP через минуту после неудачи.
        val userChoice = clientData.getExitRegionPreference()
        if (!RegionTransportPolicy.allowsTransportSubstitution(userChoice)) {
            LogManager.log(
                "Opera ${normalizedRegion.uppercase(Locale.US)} не поднялась, но регион выбран явно: " +
                    "временный WARP-bootstrap не запускаем. Подмена протокола допустима только в «Авто»."
            )
            // Сюда приходят и с живого туннеля — по серии upstream 500/502. Там регион
            // работает, просто плохо, и «не подключается» было бы неправдой.
            if (currentState != STATE_CONNECTED) {
                publishTransportNotice(
                    clientData,
                    "${normalizedRegion.uppercase(Locale.US)} не подключается: узлы Opera не отвечают. " +
                        "Выбранный регион на WARP не меняем — переключитесь на «Авто», если это допустимо."
                )
            }
            return false
        }
        if (!OperaProxyManager.isLastFailureWorthWarpBootstrap()) return false
        if (!clientData.canStartOperaBootstrapViaWarp()) {
            LogManager.log("WARP-bootstrap для Opera недавно уже запускался. Повтор не делаем, чтобы не уйти в цикл.")
            return false
        }

        clientData.ensureBootstrapConfig()
        val config = clientData.getConfig()
        if (config == null) {
            LogManager.log("WARP-bootstrap для Opera невозможен: нет сохранённой WARP-конфигурации.")
            return false
        }

        LogManager.log(
            "Opera ${normalizedRegion.uppercase(Locale.US)} вернула code=${OperaProxyManager.getLastFailureApiCode()}. " +
                "Коротко поднимаем WARP только для discovery Opera endpoints; после discovery переключимся обратно на Opera напрямую."
        )
        clientData.markOperaBootstrapViaWarpPending(normalizedRegion)
        clientData.clearPendingWarpBootstrapRestart()
        val liveOperaTransport = operaFallbackActive || operaTunThread?.isAlive == true || isOperaBackendLabel(currentBackendLabel)
        if (liveOperaTransport) {
            preferGracefulOperaStopOnce = true
            armPendingWarpBootstrapRestart(
                clientData = clientData,
                config = config,
            )
            cleanupAndStop(
                preserveRestartSession = false,
                forceServiceTeardown = true,
                allowAsyncFromMainThread = false,
            )
            return true
        }
        val bootstrapGeneration = beginConnectGeneration(stopExisting = true)
        operaBootstrapWarpGenerationId = bootstrapGeneration
        startSafeServiceThread("NovaOperaWarpBootstrap") {
            configureAndStartVpn(
                privateKey = config.privateKey,
                ipv4 = config.ipv4,
                ipv6 = config.ipv6,
                peerPub = config.peerPublicKey,
                peerEndpoint = config.peerEndpoint,
                reserved = config.reserved,
                savedPort = clientData.getLastSuccessPort(),
                savedProto = clientData.getLastSuccessProtocol(),
                regionPreferenceOverride = "ru",
                allowOperaFallbackOverride = false,
                preferWarpOnlySticky = true,
                diagnosticsMode = false,
                aggressiveFastStart = true,
                connectGenerationId = bootstrapGeneration,
            )
        }
        isRunning = true
        return true
    }

    private fun armPendingWarpBootstrapRestart(
        clientData: ClientData,
        config: WarpConfig,
    ) {
        clientData.markTransientConnectingPending(16_000L)
        clientData.savePendingWarpBootstrapRestart(
            RestartSession(
                kind = "warp",
                region = "ru",
                privateKey = config.privateKey,
                ipv4 = config.ipv4,
                ipv6 = config.ipv6,
                peerPublicKey = config.peerPublicKey,
                peerEndpoint = config.peerEndpoint,
                reserved = config.reserved,
                savedPort = clientData.getLastSuccessPort().takeIf { it in 1..65535 },
                savedProto = clientData.getLastSuccessProtocol(),
            )
        )
        LogManager.log(
            "Подготовили WARP-bootstrap к запуску после полного teardown текущего Opera-сеанса."
        )
    }

    private fun schedulePendingWarpBootstrapStart(session: RestartSession) {
        if (
            session.kind != "warp" ||
                session.privateKey.isNullOrBlank() ||
                session.ipv4.isNullOrBlank() ||
                session.ipv6.isNullOrBlank() ||
                session.peerPublicKey.isNullOrBlank() ||
                session.peerEndpoint.isNullOrBlank()
        ) {
            return
        }
        val intent = Intent(applicationContext, NovaVpnService::class.java).apply {
            action = ACTION_START_WARP_BOOTSTRAP
            putExtra("PRIVATE_KEY", session.privateKey)
            putExtra("IPV4", session.ipv4)
            putExtra("IPV6", session.ipv6)
            putExtra("PEER_PUB", session.peerPublicKey)
            putExtra("PEER_ENDPOINT", session.peerEndpoint)
            putExtra("RESERVED", session.reserved)
            putExtra("PORT", session.savedPort ?: -1)
            putExtra("PROTOCOL", session.savedProto ?: "MASQUE")
            putExtra(EXTRA_EXIT_REGION, session.region.ifBlank { "ru" })
        }
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            4107,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (alarmManager != null) {
            val scheduled = scheduleSafeInexactServiceAlarm(
                alarmManager = alarmManager,
                triggerAt = SystemClock.elapsedRealtime() + 450L,
                pendingIntent = pendingIntent,
                label = "WARP-bootstrap restart",
            )
            if (scheduled) {
                LogManager.log("После уничтожения сервиса перезапускаем WARP-bootstrap через безопасный AlarmManager.")
            } else {
                ContextCompat.startForegroundService(applicationContext, intent)
                LogManager.log("AlarmManager не принял WARP-bootstrap, запускаем напрямую после teardown.")
            }
        } else {
            ContextCompat.startForegroundService(applicationContext, intent)
            LogManager.log("AlarmManager недоступен, запускаем WARP-bootstrap напрямую после teardown.")
        }
    }

    private fun runOperaFallback(
        clientData: ClientData,
        operaCountry: String,
        operaLabel: String,
        connectGenerationId: Int,
    ): OperaFallbackResult {
        if (!isConnectGenerationCurrent(connectGenerationId)) return OperaFallbackResult.FAILED
        LogManager.log("Пробуем резервное подключение через Opera $operaLabel...")
        setCurrentBackend("$BACKEND_OPERA-$operaLabel")
        currentAttemptOrdinal = 0
        currentAttemptTotal = clientData.getCachedConnectAttemptTotal("$BACKEND_OPERA-$operaLabel")
        broadcastState(STATE_CONNECTING)
        val readyState = OperaProxyManager.ensureReady(
            context = applicationContext,
            logger = ::logOperaProxyManagerMessage,
            purposeLabel = "резервного Opera VPN",
            country = operaCountry,
            preferGlobalMaskHosts = true,
            shouldAbort = { shouldAbortConnectWork(connectGenerationId) },
            onAttemptState = { ordinal, total, _ ->
                publishConnectingAttemptProgress(
                    clientData = clientData,
                    ordinal = ordinal,
                    total = total,
                    backendLabel = "$BACKEND_OPERA-$operaLabel",
                )
            },
        )
        if (readyState == OperaProxyManager.ReadyState.FAILED || isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) {
            OperaProxyManager.getLastFailureApiCode()?.let { apiCode ->
                if (apiCode == 801 || apiCode == 500 || apiCode == 502) {
                    LogManager.log(
                        "Opera $operaLabel вернула API code=$apiCode. " +
                            "Если есть cached endpoints, Nova пробует их через override; " +
                            "если кэша нет, он будет пополняться через WARP-bootstrap после успешного WARP."
                    )
                }
            }
            LogManager.log("Opera fallback недоступен.")
            return OperaFallbackResult.FAILED
        }

        val descriptor = establishOperaTunnelInterface(clientData)
        if (descriptor == null) {
            if (!hasVpnPreparationPermission()) {
                handleUnrecoverableEstablishFailure(
                    clientData = clientData,
                    reason = "Не удалось поднять VPN-интерфейс для Opera fallback: системное разрешение VPN потеряно.",
                )
                return OperaFallbackResult.FAILED
            }
            LogManager.log("Не удалось поднять VPN-интерфейс для Opera fallback.")
            if (readyState == OperaProxyManager.ReadyState.STARTED_INTERNAL) {
                OperaProxyManager.stopManaged(::logOperaProxyManagerMessage)
                clientData.setTrafficMaskActiveHost(null)
            }
            return OperaFallbackResult.FAILED
        }

        interfaceDescriptor = descriptor
        resetEstablishNullLoopGuard()
        val tunFd = try {
            descriptor.detachFd()
        } catch (e: Exception) {
            LogManager.log("Не удалось detachFd для Opera fallback: ${e.message}")
            closeActiveInterface()
            if (readyState == OperaProxyManager.ReadyState.STARTED_INTERNAL) {
                OperaProxyManager.stopManaged(::logOperaProxyManagerMessage)
                clientData.setTrafficMaskActiveHost(null)
            }
            return OperaFallbackResult.FAILED
        }

        currentAttemptOrdinal = 0
        currentAttemptTotal = 0
        setCurrentBackend("$BACKEND_OPERA-$operaLabel")
        broadcastState(STATE_CONNECTING)

        val proxyUrl = OperaProxyManager.getLoopbackProxyUrl(this)
        operaTunLastExitCode = null
        val tunThread = Thread {
            try {
                val exitCode = OperaNativeVpnService.runTun2proxy(
                    this,
                    proxyUrl,
                    tunFd,
                    true,
                    1420.toChar(),
                    TUN2PROXY_DNS_VIRTUAL,
                    3,
                )
                operaTunLastExitCode = exitCode
                LogManager.log("[tun2proxy] Opera fallback завершён с кодом $exitCode")
            } catch (t: Throwable) {
                operaTunLastExitCode = -1
                LogManager.log("[tun2proxy] Ошибка Opera fallback: ${t.message}")
            }
        }.apply {
            name = "NovaOperaTunThread"
            start()
        }
        operaTunThread = tunThread
        operaFallbackActive = true

        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        var vpnNetwork: android.net.Network? = null
        var connected = false
        var failures = 0
        var reconnectRecommended = false
        var lastHealthyAtMs = 0L
        var connectedAtMs = 0L
        val connectDeadline = SystemClock.elapsedRealtime() + when (operaLabel.uppercase()) {
            "US" -> 26_000L
            else -> 22_000L
        }

        // Проба через сам прокси нужна всегда, а не только при раздельном туннелировании.
        //
        // Две другие пробы на Opera структурно бессильны: CONNECT на 53-й порт прокси не
        // пропускает, поэтому DNS через туннель не проходит, а без DNS система не ставит
        // VALIDATED. Оставалась одна рабочая проверка — и она была выключена у всех, кто
        // не настроил split-режим.
        val operaProxyHealthCheckEnabled = true

        while (!isUserStopped && tunThread.isAlive && SystemClock.elapsedRealtime() < connectDeadline && isConnectGenerationCurrent(connectGenerationId)) {
            Thread.sleep(1000L)
            vpnNetwork = findCurrentVpnNetwork(connectivityManager) ?: vpnNetwork
            val validated = isValidatedVpnNetwork(connectivityManager, vpnNetwork)
            val tunnelReady = hasTunnelConnectivity(vpnNetwork, 1400, allowHttpDnsFallback = false)
            // Бюджет здесь не трогаем: у цикла жёсткий дедлайн 22/26 с, и удлинение
            // пробы стоило бы попыток. Замер публикуем, чтобы к моменту STATE_CONNECTED
            // экрану было что показать. Число завышено — в него входит первый подъём
            // upstream-соединения прокси до edge; через полторы секунды цикл удержания
            // перепишет его честным.
            val connectProbeStartedAtMs = SystemClock.elapsedRealtime()
            val proxyReady = operaProxyHealthCheckEnabled && hasOperaProxyConnectivity(1400)
            if (proxyReady) {
                clientData.publishTransportLatency(
                    (SystemClock.elapsedRealtime() - connectProbeStartedAtMs).toInt().coerceAtLeast(0),
                    TRANSPORT_OPERA,
                )
            }
            if (validated || tunnelReady || proxyReady) {
                connected = true
                connectedAtMs = SystemClock.elapsedRealtime()
                lastHealthyAtMs = connectedAtMs
                markSuccessfulTunnelProbe()
                break
            }
        }

        if (!connected) {
            LogManager.log("Opera fallback не дал рабочего tunnel-probe за отведённое время.")
            clientData.recordTrafficMaskAttempt(
                clientData.getTrafficMaskActiveHost(),
                success = false,
                poolHint = clientData.getTrafficMaskActivePool(),
            )
            stopOperaFallback(
                joinTimeoutMs = 2000L,
                stopProxyManager = readyState == OperaProxyManager.ReadyState.STARTED_INTERNAL,
            )
            setCurrentBackend(BACKEND_WARP)
            return OperaFallbackResult.FAILED
        }

        LogManager.log("Opera $operaLabel fallback активен.")
        OperaProxyManager.markCurrentMaskHostSuccessful(applicationContext)
        broadcastState(STATE_CONNECTED)

        // То же правило, что у встроенных профилей: наверх очереди поднимается не то,
        // что подключилось, а то, что продержалось. Подключиться успевает и выход,
        // который через секунду отваливается, — с ним перебор ходил бы по кругу.
        var stablePlanPromoted = false

        while (!isUserStopped && tunThread.isAlive && isConnectGenerationCurrent(connectGenerationId)) {
            Thread.sleep(1500L)
            if (
                !stablePlanPromoted &&
                connectedAtMs > 0L &&
                SystemClock.elapsedRealtime() - connectedAtMs >= STABLE_LAST_SUCCESS_HOLD_MS
            ) {
                stablePlanPromoted = true
                val promoted = OperaProxyManager.promoteCurrentLaunchPlan(applicationContext)
                if (promoted.isNotEmpty()) {
                    LogManager.log(
                        "Opera $operaLabel удержалась 20 секунд. Поднимаем этот способ наверх очереди: $promoted."
                    )
                }
            }
            vpnNetwork = findCurrentVpnNetwork(connectivityManager) ?: vpnNetwork
            val validated = isValidatedVpnNetwork(connectivityManager, vpnNetwork)
            val tunnelReady = hasTunnelConnectivity(vpnNetwork, 1200, allowHttpDnsFallback = false)
            // Проба живости и есть замер задержки: запрос уходит наружу через сам прокси.
            // Экран измерить не может — в режиме Opera пакет Nova всегда вне VPN
            // (applyOperaSplitTunnelPolicy исключает его во всех трёх ветках), сети VPN
            // он не видит, а порт локального прокси служба выбирает на лету в процессе
            // :vpn и в основной процесс он не долетает.
            val probeStartedAtMs = SystemClock.elapsedRealtime()
            val proxyReady = operaProxyHealthCheckEnabled &&
                hasOperaProxyConnectivity(OPERA_SESSION_PROBE_TIMEOUT_MS)
            if (proxyReady) {
                clientData.publishTransportLatency(
                    (SystemClock.elapsedRealtime() - probeStartedAtMs).toInt().coerceAtLeast(0),
                    TRANSPORT_OPERA,
                )
            }
            if (validated || tunnelReady || proxyReady) {
                failures = 0
                lastHealthyAtMs = SystemClock.elapsedRealtime()
                markSuccessfulTunnelProbe()
            } else {
                failures++
                val now = SystemClock.elapsedRealtime()
                val connectedAgeMs = if (connectedAtMs > 0L) now - connectedAtMs else Long.MAX_VALUE
                val minimumUnhealthyWindowMs = if (connectedAgeMs < 30_000L) 15_000L else 10_000L
                val requiredFailures = if (connectedAgeMs < 30_000L) 10 else 8
                if (failures >= requiredFailures && now - lastHealthyAtMs >= minimumUnhealthyWindowMs) {
                    reconnectRecommended = true
                    clientData.recordTrafficMaskAttempt(
                        clientData.getTrafficMaskActiveHost(),
                        success = false,
                        poolHint = clientData.getTrafficMaskActivePool(),
                    )
                    LogManager.log("Opera fallback потерял рабочий data-plane. Останавливаем резервный туннель.")
                    break
                }
            }
        }

        if (cleanupInProgress.get() || explicitStopRequested) {
            // Тот же сброс, что и на обычном выходе: путь через STATE_STOPPED страхует
            // не всегда — broadcastState глушит всё, кроме STOPPED, а cleanup может
            // закончиться и без него.
            clientData.clearTransportLatency()
            operaTunThread = null
            operaFallbackActive = false
            return OperaFallbackResult.CONNECTED
        }

        // Замер принадлежит закончившейся сессии: без сброса он ещё восемь секунд
        // выдавался бы за пинг следующего транспорта.
        clientData.clearTransportLatency()
        stopOperaFallback(
            joinTimeoutMs = 2500L,
            stopProxyManager = readyState == OperaProxyManager.ReadyState.STARTED_INTERNAL,
        )
        if (!isUserStopped) {
            setCurrentBackend(BACKEND_WARP)
            if (reconnectRecommended && clientData.getAutoReconnect()) {
                broadcastState(STATE_CONNECTING)
            }
        }
        return when {
            reconnectRecommended && clientData.getAutoReconnect() && !isUserStopped -> OperaFallbackResult.NEED_RECONNECT
            connected -> OperaFallbackResult.CONNECTED
            else -> OperaFallbackResult.FAILED
        }
    }

    private fun closeActiveInterface() {
        val descriptor = interfaceDescriptor
        try {
            descriptor?.close()
            if (descriptor != null) {
                LogManager.log("Текущий VPN-интерфейс закрыт.")
            }
        } catch (_: Exception) {
        } finally {
            interfaceDescriptor = null
        }
    }

    /**
     * Единственная точка вызова `tun2proxy_stop()` — и точка признания приговора.
     *
     * Дизассемблирование `libtun2proxy.so` показало: после этого вызова библиотека
     * безусловно поднимает отсоединённый поток «поспать 2 секунды → `exit(-1)`», не
     * проверяя между сном и выходом ничего. Отменить фитиль нечем. `exit` запускает
     * `__cxa_finalize`, тот разрушает глобальные мьютексы, и любой поток, висящий в
     * этот момент в нативном чтении, роняет процесс на разрушенном мьютексе.
     *
     * Дефект, ради которого это записано: остановка вызывала force-stop, через 160 мс
     * пользователь запускал подключение заново, оно успевало подняться — и через две
     * секунды догоравший фитиль убивал уже работающий туннель.
     */
    private fun forceStopTun2proxy(reason: String, thread: Thread?, wasActive: Boolean) {
        tun2proxyForceStopAtMs = SystemClock.elapsedRealtime()
        LogManager.log(
            "force-stop tun2proxy ($reason): поток жив=${thread?.isAlive}, wasActive=$wasActive, " +
                "код выхода=$operaTunLastExitCode. После вызова процесс :vpn обречён — " +
                "новую сессию в нём поднимать нельзя."
        )
        try {
            OperaNativeVpnService.haltTun2proxy { LogManager.log("[tun2proxy] $it") }
        } catch (t: Throwable) {
            LogManager.log("[tun2proxy] force-stop не удался: ${t.message}")
        }
    }

    private fun logForceStopSkipped(reason: String, thread: Thread?, wasActive: Boolean) {
        LogManager.log(
            "force-stop tun2proxy пропущен ($reason): поток жив=${thread?.isAlive}, " +
                "wasActive=$wasActive, код выхода=$operaTunLastExitCode."
        )
    }

    private fun stopOperaFallback(
        joinTimeoutMs: Long = 2500L,
        stopProxyManager: Boolean = false,
        allowBlockingWait: Boolean = Looper.myLooper() != Looper.getMainLooper(),
    ) {
        closeActiveInterface()
        val wasActive = operaFallbackActive
        val thread = operaTunThread
        val preferGracefulStop = preferGracefulOperaStopOnce
        preferGracefulOperaStopOnce = false
        val effectiveJoinTimeoutMs = if (preferGracefulStop) maxOf(joinTimeoutMs, 4200L) else joinTimeoutMs
        if (!allowBlockingWait) {
            operaTunThread = null
            operaFallbackActive = false
            startSafeServiceThread("NovaStopOperaFallback") {
                val shouldForceStopTun =
                    !preferGracefulStop && ((thread?.isAlive == true) || (wasActive && operaTunLastExitCode == null))
                if (preferGracefulStop) {
                    LogManager.log(
                        "Переход с Opera на WARP: ждём естественное завершение tun2proxy без force-stop, " +
                            "чтобы избежать native crash."
                    )
                }
                if (shouldForceStopTun) {
                    forceStopTun2proxy("асинхронная остановка", thread, wasActive)
                } else {
                    logForceStopSkipped("асинхронная остановка", thread, wasActive)
                }
                if (thread != null && thread.isAlive && thread !== Thread.currentThread()) {
                    try {
                        thread.join(effectiveJoinTimeoutMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
                if (stopProxyManager) {
                    OperaProxyManager.stopManaged(::logOperaProxyManagerMessage)
                    ClientData(this).setTrafficMaskActiveHost(null)
                }
            }
            return
        }
        if (thread != null && thread.isAlive && thread !== Thread.currentThread()) {
            try {
                thread.join(effectiveJoinTimeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val shouldForceStopTun =
            !preferGracefulStop && ((thread?.isAlive == true) || (wasActive && operaTunLastExitCode == null))
        if (preferGracefulStop) {
            LogManager.log(
                "Переход с Opera на WARP: ждём естественное завершение tun2proxy без force-stop, " +
                    "чтобы избежать native crash."
            )
        }
        if (shouldForceStopTun) {
            forceStopTun2proxy("синхронная остановка", thread, wasActive)
        } else {
            logForceStopSkipped("синхронная остановка", thread, wasActive)
        }
        if (thread != null && thread.isAlive) {
            if (thread !== Thread.currentThread()) {
                try {
                    thread.join(1500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        // Сброс учёта — только если гасили именно тот поток, который держим. Иначе
        // хвост старой остановки обнулял бы состояние уже новой сессии, и следующая
        // остановка решила бы, что убивать нечего, оставив живой tun2proxy работать.
        if (operaTunThread === thread || operaTunThread?.isAlive != true) {
            operaTunThread = null
            operaFallbackActive = false
            operaTunLastExitCode = null
        } else {
            LogManager.log(
                "Хвост stopOperaFallback застал уже новый поток tun2proxy " +
                    "(${operaTunThread?.name}): учёт не трогаем."
            )
        }
        if (stopProxyManager) {
            OperaProxyManager.stopManaged(::logOperaProxyManagerMessage)
            ClientData(this).setTrafficMaskActiveHost(null)
        }
    }

    private fun stopNovaCoreEngine(
        joinTimeoutMs: Long = 4000L,
        allowBlockingWait: Boolean = Looper.myLooper() != Looper.getMainLooper(),
    ) {
        if (!novaCoreTunnelActive && novaEngineThread == null) {
            return
        }
        val thread = novaEngineThread
        if (!allowBlockingWait) {
            novaEngineThread = null
            novaCoreTunnelActive = false
            startSafeServiceThread("NovaStopCoreEngine") {
                try {
                    Nova.setDnsInterceptPolicy(false, "", "", "")
                } catch (_: Exception) {
                }
                try {
                    Nova.stopVPN()
                } catch (_: Exception) {
                }
                if (thread != null && thread.isAlive && thread !== Thread.currentThread()) {
                    try {
                        thread.join(joinTimeoutMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                    }
                }
            }
            return
        }
        try {
            Nova.setDnsInterceptPolicy(false, "", "", "")
        } catch (_: Exception) {
        }
        try {
            Nova.stopVPN()
        } catch (_: Exception) {
        }
        if (thread != null && thread.isAlive && thread !== Thread.currentThread()) {
            try {
                thread.join(joinTimeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        novaEngineThread = null
        novaCoreTunnelActive = false
    }

    private fun finishForegroundShutdown() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }

    private fun forceDetachVpnStack() {
        try {
            val revokeDescriptor = Builder()
                .setSession("NovaVPN-stop")
                .setMtu(1280)
                .addAddress("169.254.254.1", 32)
                .addRoute("169.254.254.1", 32)
                .establish()
            revokeDescriptor?.close()
            LogManager.log("Системный VPN-стек принудительно сброшен.")
        } catch (e: Exception) {
            LogManager.log("Принудительный сброс VPN-стека не удался: ${e.message}")
        }
    }

    private fun shouldUseAggressiveStopDetach(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim().lowercase()
        val brand = Build.BRAND.orEmpty().trim().lowercase()
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q ||
            manufacturer.contains("honor") ||
            manufacturer.contains("huawei") ||
            brand.contains("honor") ||
            brand.contains("huawei")
    }

    /**
     * @param thoroughRegistration регистрацию нельзя ужимать по времени: так бывает,
     * когда MASQUE выбран явно и быстрый бюджет означал бы отказ от него совсем.
     * @param cachedOnly брать только готовый профиль и не регистрироваться. Регистрация
     * идёт через Opera-прокси и API Cloudflare — это десятки секунд и сетевые запросы,
     * которых пользователь не просил. Затевать их стоит только тогда, когда MASQUE выбран
     * в списке протоколов; в режиме «Авто» есть кэш — берём, нет — идём по WARP/AWG.
     */
    private fun prepareMasqueIdentity(
        clientData: ClientData,
        fastRefresh: Boolean = false,
        connectGenerationId: Int? = null,
        trackConnectProgress: Boolean = false,
        thoroughRegistration: Boolean = false,
        cachedOnly: Boolean = false,
    ): MasqueIdentity? {
        if (shouldAbortConnectWork(connectGenerationId)) {
            return null
        }
        val existingJson = clientData.getMasqueConfigJson().orEmpty()
        val accessToken = clientData.getAccessToken().orEmpty()
        val deviceId = clientData.getDeviceId().orEmpty()

        if (existingJson.isBlank() && (accessToken.isBlank() || deviceId.isBlank())) {
            return null
        }
        if (cachedOnly && parseMasqueIdentity(existingJson) == null) {
            return null
        }

        val progressBase = currentAttemptOrdinal.coerceAtLeast(0)
        val progressTotalHint = maxOf(
            currentAttemptTotal,
            clientData.getCachedConnectAttemptTotal(currentBackendLabel, currentTransportLabel),
            progressBase + 1,
        )
        if (trackConnectProgress) {
            publishConnectingAttemptProgress(
                clientData = clientData,
                ordinal = (progressBase + 1).coerceAtMost(progressTotalHint),
                total = progressTotalHint,
            )
        }

        val cachedIdentity = parseMasqueIdentity(existingJson)
        if (cachedIdentity != null) {
            LogManager.log("Используем cached MASQUE identity без refresh для быстрого старта.")
            return cachedIdentity
        }

        val hasCachedIdentity = false
        val proxyReadyState = if (!hasCachedIdentity && accessToken.isNotBlank() && deviceId.isNotBlank()) {
            OperaProxyManager.ensureReady(
                context = applicationContext,
                logger = ::logOperaProxyManagerMessage,
                purposeLabel = if (fastRefresh) "быстрого обновления MASQUE identity" else "регистрации WARP",
                readyTimeoutMs = when {
                    fastRefresh -> 6_000L
                    thoroughRegistration -> 20_000L
                    trackConnectProgress -> 8_000L
                    else -> 12_000L
                },
                maxMaskHostAttempts = when {
                    fastRefresh -> 1
                    thoroughRegistration -> null
                    trackConnectProgress -> 2
                    else -> null
                },
                maxLaunchPlans = if (trackConnectProgress && !thoroughRegistration) 2 else null,
                onAttemptState =
                    if (trackConnectProgress) {
                        { ordinal, total, _ ->
                            if (shouldAbortConnectWork(connectGenerationId)) return@ensureReady
                            val mappedTotal = maxOf(progressTotalHint, progressBase + total)
                            val mappedOrdinal = (progressBase + ordinal).coerceAtMost(mappedTotal)
                            publishConnectingAttemptProgress(
                                clientData = clientData,
                                ordinal = mappedOrdinal,
                                total = mappedTotal,
                            )
                        }
                    } else {
                        null
                    },
                shouldAbort = { shouldAbortConnectWork(connectGenerationId) },
            )
        } else {
            null
        }
        if (shouldAbortConnectWork(connectGenerationId)) {
            return null
        }
        val stopManagedOperaProxy = proxyReadyState == OperaProxyManager.ReadyState.STARTED_INTERNAL
        if ((fastRefresh || trackConnectProgress) && proxyReadyState == OperaProxyManager.ReadyState.FAILED) {
            LogManager.log(
                if (fastRefresh) {
                    "Быстрый refresh MASQUE identity: Opera proxy не поднялся, refresh пропускаем."
                } else if (thoroughRegistration) {
                    "MASQUE identity: Opera-прокси для регистрации поднять не удалось даже с полным бюджетом. " +
                        "Пробуем прямую регистрацию — она может упереться в блокировку SNI api.cloudflareclient.com."
                } else {
                    "MASQUE identity: быстрый Opera bootstrap не поднялся за лимит. " +
                        "Пробуем короткий direct refresh и продолжаем перебор."
                }
            )
            if (fastRefresh) {
                return null
            }
        }

        fun ensureMasqueConfigWithTimeout(timeoutMs: Long): String? {
            val executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "NovaMasqueIdentity").apply { isDaemon = true }
            }
            return try {
                val future = executor.submit<String?> {
                    Nova.ensureMasqueConfig(
                        existingJson,
                        accessToken,
                        deviceId,
                        "Nova Android"
                    ).orEmpty().takeIf { it.isNotBlank() }
                }
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                LogManager.log("MASQUE identity refresh не ответил за ${timeoutMs}мс. Продолжаем перебор без ожидания.")
                null
            } finally {
                executor.shutdownNow()
            }
        }

        return try {
            if (shouldAbortConnectWork(connectGenerationId)) {
                return null
            }
            val preparedJson = ensureMasqueConfigWithTimeout(
                when {
                    fastRefresh -> 6_000L
                    thoroughRegistration -> 30_000L
                    trackConnectProgress -> 8_000L
                    else -> 20_000L
                }
            ).orEmpty()
            if (preparedJson.isBlank()) {
                LogManager.log("MASQUE config не получен от core.")
                null
            } else {
                clientData.saveMasqueConfigJson(preparedJson)
                parseMasqueIdentity(preparedJson)
            }
        } catch (e: Exception) {
            LogManager.log("MASQUE prepare error: ${e.message}")
            null
        } finally {
            if (stopManagedOperaProxy) {
                OperaProxyManager.stopManaged(::logOperaProxyManagerMessage)
                clientData.setTrafficMaskActiveHost(null)
            }
        }
    }

    private fun parseMasqueIdentity(raw: String): MasqueIdentity? {
        return try {
            val json = JSONObject(raw)
            val portsJson = json.optJSONArray("ports")
            val ports = linkedSetOf<Int>()
            if (portsJson != null) {
                for (i in 0 until portsJson.length()) {
                    val port = portsJson.optInt(i, -1)
                    if (port in 1..65535) {
                        ports.add(port)
                    }
                }
            }
            for (fallback in listOf(443, 500, 1701, 4500, 4443, 8443, 8095)) {
                ports.add(fallback)
            }

            MasqueIdentity(
                privateKey = json.optString("private_key"),
                endpointV4 = normalizeEndpointHost(json.optString("endpoint_v4", "")),
                endpointV6 = normalizeEndpointHost(json.optString("endpoint_v6", "")),
                endpointV4Candidates = parseEndpointHostArray(json, "endpoint_v4_candidates"),
                endpointV6Candidates = parseEndpointHostArray(json, "endpoint_v6_candidates"),
                endpointPubKey = json.optString("endpoint_pub_key"),
                ipv4 = json.optString("ipv4"),
                ipv6 = json.optString("ipv6"),
                ports = ports.toList(),
            ).takeIf {
                it.privateKey.isNotBlank() &&
                    it.endpointPubKey.isNotBlank() &&
                    (it.endpointV4?.isNotBlank() == true || it.endpointV6?.isNotBlank() == true) &&
                    (it.ipv4.isNotBlank() || it.ipv6.isNotBlank())
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseEndpointHostArray(json: JSONObject, fieldName: String): List<String> {
        val values = linkedSetOf<String>()
        val array = json.optJSONArray(fieldName) ?: return emptyList()
        for (i in 0 until array.length()) {
            val normalized = normalizeEndpointHost(array.optString(i))
            if (!normalized.isNullOrBlank()) {
                values.add(normalized)
            }
        }
        return values.toList()
    }

    private fun normalizeEndpointHost(value: String?): String? {
        val clean = value?.trim().orEmpty()
        if (clean.isBlank()) return null
        return parseEndpoint(clean).first.removePrefix("[").removeSuffix("]")
    }

    private fun resolveWarpTunnelMtu(
        connectivityManager: android.net.ConnectivityManager?,
        underlyingNetwork: android.net.Network?,
        clientData: ClientData,
    ): Int {
        return 1280
    }

    /**
     * MTU туннеля для MASQUE. Он заметно меньше, чем у WireGuard, и это не запас
     * «на всякий случай», а арифметика.
     *
     * Наружу MASQUE отдаёт пакет QUIC размером [MASQUE_QUIC_PACKET_SIZE] байт.
     * Внутрь помещается то, что осталось после заголовка QUIC с идентификатором
     * соединения и номером пакета, метки AEAD, обрамления датаграммы HTTP/3 и
     * идентификатора контекста connect-ip — около полусотни байт. Датаграммы QUIC
     * не фрагментируются: пакет, не поместившийся целиком, просто отбрасывается.
     *
     * Пока MTU туннеля равнялся 1280, как у WireGuard, всё, что крупнее примерно
     * 1195 байт, исчезало. Мелкие пакеты — подтверждения, запросы к серверу имён —
     * проходили, а полноразмерные пакеты данных пропадали, из-за чего соединение
     * выглядело установленным и одновременно теряло значительную часть трафика.
     */
    private fun resolveMasqueTunnelMtu(): Int {
        val overheadWorstCase = 1 + 20 + 4 + 16 + 5 + 1 // флаги, DCID, номер, AEAD, H3, контекст
        return MASQUE_QUIC_PACKET_SIZE - overheadWorstCase - MASQUE_MTU_SAFETY_MARGIN
    }

    private fun normalizeVerifiedConfigSource(source: String?): String {
        return when (source?.trim()?.lowercase()) {
            "last-success-exact", "last-success" -> "verified-config"
            else -> source?.ifBlank { "verified-config" } ?: "verified-config"
        }
    }

    private fun buildMasqueConnectionAttempts(
        identity: MasqueIdentity,
        clientData: ClientData,
        includeScannerCandidates: Boolean = true,
    ): List<ConnectionAttempt> {
        val lastProtocol = clientData.getLastSuccessProtocol()
        val lastMode = clientData.getLastSuccessMode()
        val forbidStableMismatchReuse =
            clientData.hasFreshStableLastSuccess() && currentCycleStableSuccess == null

        val v4Candidates = identity.endpointV4Candidates.ifEmpty {
            listOfNotNull(identity.endpointV4)
        }
        val v6Candidates = if (hasIpv6Connectivity()) {
            identity.endpointV6Candidates.ifEmpty { listOfNotNull(identity.endpointV6) }
        } else {
            emptyList()
        }
        val orderedPorts = linkedSetOf<Int>()
        val lastPort = clientData.getLastSuccessPort()
        if (!forbidStableMismatchReuse && clientData.hasFreshLastSuccess() && lastProtocol.equals("MASQUE", ignoreCase = true) && lastPort in 1..65535) {
            orderedPorts.add(lastPort)
        }
        for (port in identity.ports) {
            if (port in 1..65535) orderedPorts.add(port)
        }

        val masqueModes = listOf(
            TransportMode(
                name = "MASQUE-ZT",
                engine = "masque",
                useFakePackets = false,
                reservedMode = "off",
                preferredPorts = orderedPorts.toList(),
                masqueSni = "zt-masque.cloudflareclient.com",
            ),
            TransportMode(
                name = "MASQUE-CONSUMER",
                engine = "masque",
                useFakePackets = false,
                reservedMode = "off",
                preferredPorts = orderedPorts.toList(),
                masqueSni = "consumer-masque.cloudflareclient.com",
            ),
        )

        val attempts = mutableListOf<ConnectionAttempt>()
        val topVerifiedMasquePorts = sortedVerifiedWarpConfigs(clientData)
            .filter { it.engine.equals("masque", ignoreCase = true) }
            .take(6)
            .map { it.port }
        for (mode in masqueModes) {
            val endpoints = linkedMapOf<String, EndpointCandidate>()

            fun addEndpoint(host: String?, preferredPort: Int?, source: String) {
                val cleanHost = host?.trim().orEmpty().removePrefix("[").removeSuffix("]")
                if (cleanHost.isBlank()) return
                val key = "$cleanHost:${preferredPort ?: 0}"
                if (!endpoints.containsKey(key)) {
                    endpoints[key] = EndpointCandidate(cleanHost, preferredPort, source)
                }
            }

            if (!forbidStableMismatchReuse && clientData.hasFreshLastSuccess() && lastProtocol.equals("MASQUE", ignoreCase = true)) {
                val lastEndpoint = currentCycleLastSuccessEndpoint(clientData)
                val freshLastPort = currentCycleLastSuccessPort(clientData)?.takeIf { it in 1..65535 }
                val cycleLastMode = currentCycleLastSuccessMode(clientData)
                val canReuseFreshMasqueExact =
                    currentCycleHasReusableLastSuccess(clientData) &&
                        currentCycleLastSuccessProtocol(clientData).equals("MASQUE", ignoreCase = true) &&
                        freshLastPort != null &&
                        (
                            topVerifiedMasquePorts.isEmpty() ||
                                freshLastPort in topVerifiedMasquePorts
                            )
                if (!lastEndpoint.isNullOrBlank() && isNumericEndpointHost(lastEndpoint) && canReuseFreshMasqueExact) {
                    val source = if (mode.name.equals(cycleLastMode, ignoreCase = true)) {
                        "last-success-exact"
                    } else {
                        "last-success"
                    }
                    addEndpoint(lastEndpoint, freshLastPort, source)
                }
            }

            sortedVerifiedWarpConfigs(clientData)
                .filter {
                    it.engine.equals("masque", ignoreCase = true) &&
                        it.mode.equals(mode.name, ignoreCase = true)
                }
                .take(12)
                .forEach { verified ->
                    addEndpoint(verified.host, verified.port, "verified-config")
                }

            if (includeScannerCandidates) {
                for (candidate in readScannedMasqueEndpoints(mode.masqueSni.orEmpty(), orderedPorts.toList())) {
                    addEndpoint(candidate.host, candidate.preferredPort, candidate.source)
                }
            }

            for (host in v4Candidates) {
                host.takeIf { isNumericEndpointHost(it) }?.let {
                    addEndpoint(it, null, "masque-v4")
                }
            }
            for (host in v6Candidates) {
                host.takeIf { isNumericEndpointHost(it) }?.let {
                    addEndpoint(it, null, "masque-v6")
                }
            }

            if (!includeScannerCandidates) {
                val expansionSeeds = linkedSetOf<String>()
                v4Candidates.forEach { expansionSeeds += it }
                sortedVerifiedWarpConfigs(clientData)
                    .filter { it.engine.equals("masque", ignoreCase = true) }
                    .take(6)
                    .forEach { expansionSeeds += it.host }
                expansionSeeds
                    .flatMap { expandWarpNeighborHosts(it, includeWider = false) }
                    .distinct()
                    .take(4)
                    .forEach { neighbor ->
                        addEndpoint(neighbor, null, "known-anycast")
                    }
            }

            attempts += buildConnectionAttempts(
                endpointCandidates = endpoints.values.toList(),
                portCandidates = orderedPorts.toList(),
                transportModes = listOf(mode),
            )
        }

        return attempts
    }

    private fun buildWireGuardConfig(
        transportMode: TransportMode,
        endpointHost: String,
        endpointPort: Int,
        privateKey: String,
        ipv4: String,
        ipv6: String,
        peerPub: String,
        reserved: String?,
        clientData: ClientData,
        awgInterfaceExtras: List<String> = emptyList(),
        attemptContext: ConnectionAttempt? = null,
    ): String {
        val currentEndpoint = formatEndpoint(endpointHost, endpointPort)
        val importedInterfaceOverrides = resolveImportedInterfaceOverridesForAttempt(
            attempt = attemptContext ?: ConnectionAttempt(
                    endpointHost = endpointHost,
                    port = endpointPort,
                    mode = transportMode,
                    endpointSource = "runtime",
                ),
            clientData = clientData,
        )
        val normalizedReserved = normalizeReservedValue(reserved)
        val normalizedImportedReserved = normalizeReservedValue(importedInterfaceOverrides.reserved)
        val dnsServers = if (transportMode.preferImportedRawDns && importedInterfaceOverrides.dnsServers.isNotEmpty()) {
            // DNS импортированного профиля не остаётся в одиночестве: у него нет
            // запасных, и если сервер провайдера недоступен, имена перестают
            // разрешаться совсем.
            //
            // Порядок решает страна выхода. На российском выходе первым идёт Xbox DNS
            // (правило Nova), а DNS из профиля становится одним из запасных. На
            // зарубежном — наоборот: профиль главный, серверы Nova подстраховывают.
            // WireGuard перебирает список по порядку, поэтому важен именно он.
            val importedDns = importedInterfaceOverrides.dnsServers
            // Страна берётся по внешнему IP предыдущего наблюдения. Пока выход ни разу
            // не наблюдался, импортированный AWG считаем российским: подавляющее
            // большинство таких профилей выдают российский адрес, а Xbox DNS в этой
            // стране работает и на зарубежном выходе — цена ошибки в эту сторону
            // меньше, чем в обратную.
            val observedCountry = clientData.getLastExitCountry().trim().uppercase(Locale.US)
            val countryHint = observedCountry.ifBlank { "RU" }
            val (novaDns, _) = resolveDnsServersForBuilder(
                clientData = clientData,
                backendLabel = BACKEND_WARP,
                countryHint = countryHint,
            )
            val russianExit = countryHint == "RU"
            val merged = if (russianExit) novaDns + importedDns else importedDns + novaDns
            val result = merged.distinct()
            // Запоминаем DNS профиля: если позже выяснится, что выход не российский,
            // порядок поменяется на лету, без переустановки туннеля.
            lastImportedProfileDnsServers = importedDns
            LogManager.log(
                "DNS импортированного профиля: страна выхода " +
                    (observedCountry.ifBlank { "неизвестна, считаем RU" }) +
                    ", порядок ${if (russianExit) "Xbox → профиль" else "профиль → запасные Nova"}: " +
                    result.joinToString(", ")
            )
            result
        } else {
            resolveDnsServersForBuilder(
                clientData = clientData,
                backendLabel = BACKEND_WARP,
            ).first
        }
        val effectiveMtu = if (transportMode.preferImportedRawMtu) {
            importedInterfaceOverrides.mtu?.takeIf { it in 1280..1500 } ?: currentWarpTunnelMtu
        } else {
            currentWarpTunnelMtu
        }
        val useImportedIdentity =
            transportMode.preferImportedRawIdentity &&
                importedInterfaceOverrides.privateKey?.isNotBlank() == true &&
                importedInterfaceOverrides.addresses.isNotEmpty() &&
                importedInterfaceOverrides.peerPublicKey?.isNotBlank() == true
        val effectivePrivateKey = if (useImportedIdentity) {
            importedInterfaceOverrides.privateKey.orEmpty()
        } else {
            privateKey
        }
        val effectiveAddressLine = if (useImportedIdentity) {
            importedInterfaceOverrides.addresses.joinToString(", ") { it.toConfigValue() }
        } else {
            "$ipv4/32, $ipv6/128"
        }
        val effectivePeerPub = if (useImportedIdentity) {
            importedInterfaceOverrides.peerPublicKey.orEmpty()
        } else {
            peerPub
        }
        val effectivePresharedKey = importedInterfaceOverrides.peerPresharedKey
            ?.takeIf { useImportedIdentity && it.isNotBlank() }
        val effectivePersistentKeepalive =
            importedInterfaceOverrides.peerPersistentKeepalive ?: 5
        val effectiveAdvancedSecurity = importedInterfaceOverrides.peerAdvancedSecurity
            ?.takeIf { useImportedIdentity && it.isNotBlank() }
        val emittedNovaMode = transportMode.novaModeOverride?.trim().orEmpty().ifBlank { transportMode.name }
        val emittedReservedMode = transportMode.reservedModeOverride?.trim().orEmpty().ifBlank { transportMode.reservedMode }
        val emittedReserved = when {
            transportMode.omitReservedLineIfMissingInImport && !importedInterfaceOverrides.hasReservedLine -> null
            transportMode.omitReservedLineIfMissingInImport && normalizedImportedReserved != null ->
                normalizedImportedReserved
            else -> normalizedReserved ?: normalizedImportedReserved ?: "0,0,0"
        }
        if (
            transportMode.novaModeOverride != null ||
            transportMode.reservedModeOverride != null ||
            transportMode.preferImportedRawDns ||
            transportMode.preferImportedRawMtu ||
            transportMode.preferImportedRawIdentity
        ) {
            LogManager.log(
                "EXACT IMPORT runtime ${endpointHost}:${endpointPort}/${transportMode.name}: " +
                    "novaMode=$emittedNovaMode, reservedMode=$emittedReservedMode, " +
                    "rawIdentity=${if (useImportedIdentity) "true" else "false"}, " +
                    "dns=${dnsServers.joinToString(",")}, mtu=$effectiveMtu, reserved=${emittedReserved ?: "<none>"}"
            )
        }
        return buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $effectivePrivateKey")
            appendLine("Address = $effectiveAddressLine")
            appendLine("DNS = ${dnsServers.joinToString(", ")}")
            appendLine("MTU = $effectiveMtu")
            if (!emittedReserved.isNullOrBlank()) {
                appendLine("Reserved = $emittedReserved")
            }
            appendLine("NovaMode = $emittedNovaMode")
            appendLine("NovaReservedMode = $emittedReservedMode")
            appendLine("NovaCacheDir = ${cacheDir.absolutePath}")
            awgInterfaceExtras.forEach { appendLine(it) }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $effectivePeerPub")
            if (!effectivePresharedKey.isNullOrBlank()) {
                appendLine("PresharedKey = $effectivePresharedKey")
            }
            if (!effectiveAdvancedSecurity.isNullOrBlank()) {
                appendLine("AdvancedSecurity = $effectiveAdvancedSecurity")
            }
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = $currentEndpoint")
            appendLine("PersistentKeepalive = $effectivePersistentKeepalive")
        }.trim()
    }

    private fun findMatchingWarpVerifiedConfigForAttempt(
        attempt: ConnectionAttempt,
        clientData: ClientData,
    ): WarpVerifiedConfig? {
        val normalizedHost = attempt.endpointHost.trim().removePrefix("[").removeSuffix("]")
        val importedConfigHostHint = attempt.importedConfigHost
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.takeIf { it.isNotBlank() }
        val normalizedAttemptMode = normalizeImportedRuntimeModeName(attempt.mode.name)
        fun importedHostMatchesAttempt(configHost: String): Boolean {
            val normalizedConfigHost = configHost.trim().removePrefix("[").removeSuffix("]")
            if (normalizedConfigHost.equals(normalizedHost, ignoreCase = true)) return true
            if (importedConfigHostHint != null && normalizedConfigHost.equals(importedConfigHostHint, ignoreCase = true)) {
                return true
            }
            return false
        }

        val candidates = mergedVerifiedWarpConfigs(clientData)
            .asSequence()
            .filter { it.engine.equals("wireguard", ignoreCase = true) }
            .filter { it.port == attempt.port }
            .toList()

        val exactHostConfigs = candidates
            .filter {
                it.host.equals(normalizedHost, ignoreCase = true) ||
                    (importedConfigHostHint != null && it.host.equals(importedConfigHostHint, ignoreCase = true))
            }

        val configs = (exactHostConfigs.ifEmpty {
            candidates.filter { config ->
                config.userImported &&
                    normalizeImportedRuntimeModeName(config.mode).equals(normalizedAttemptMode, ignoreCase = true) &&
                    importedHostMatchesAttempt(config.host)
            }
        })
            .asSequence()
            .sortedWith(
                compareByDescending<WarpVerifiedConfig> { it.userImported }
                    .thenByDescending { it.successCount }
                    .thenByDescending { it.lastVerifiedAt }
            )
            .toList()

        fun sourcePreferred(config: WarpVerifiedConfig): Boolean {
            return if (clientData.isImportedConfigSourceActive()) {
                config.userImported
            } else {
                clientData.isBundledSeed(config)
            }
        }

        return configs.firstOrNull {
            sourcePreferred(it) && normalizeImportedRuntimeModeName(it.mode).equals(normalizedAttemptMode, ignoreCase = true)
        }
            ?: configs.firstOrNull { sourcePreferred(it) }
            ?: configs.firstOrNull { normalizeImportedRuntimeModeName(it.mode).equals(normalizedAttemptMode, ignoreCase = true) }
            ?: configs.firstOrNull { it.userImported }
            ?: configs.firstOrNull()
    }

    private fun normalizeImportedRuntimeModeName(modeName: String): String {
        return modeName
            .trim()
            .removeSuffix("-mask")
            .removeSuffix("-nova-mask")
    }

    private fun resolveImportedInterfaceOverridesForAttempt(
        attempt: ConnectionAttempt,
        clientData: ClientData,
    ): ImportedInterfaceOverrides {
        val matchingConfig = findMatchingWarpVerifiedConfigForAttempt(attempt, clientData)
            ?.takeIf { it.userImported || (attempt.mode.preferImportedRawIdentity && clientData.isBundledSeed(it)) }
            ?: return ImportedInterfaceOverrides()

        var currentSection = ""
        val dnsServers = mutableListOf<String>()
        val addresses = mutableListOf<ImportedInterfaceAddress>()
        var mtu: Int? = null
        var reserved: String? = null
        var hasReservedLine = false
        var privateKey: String? = null
        var peerPublicKey: String? = null
        var peerPresharedKey: String? = null
        var peerPersistentKeepalive: Int? = null
        var peerAdvancedSecurity: String? = null

        matchingConfig.rawConfig.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) return@forEach
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.removePrefix("[").removeSuffix("]").trim().lowercase(Locale.US)
                return@forEach
            }
            if (!trimmed.contains('=')) return@forEach
            val key = trimmed.substringBefore('=').trim().lowercase(Locale.US)
            val value = trimmed.substringAfter('=').trim()
            when (currentSection) {
                "interface" -> when (key) {
                    "privatekey" -> {
                        privateKey = value.takeIf { it.isNotBlank() }
                    }
                    "address" -> {
                        addresses += parseImportedInterfaceAddresses(value)
                    }
                    "dns" -> {
                        dnsServers += value.split(',').map { it.trim() }.filter { it.isNotBlank() }
                    }
                    "mtu" -> {
                        mtu = value.toIntOrNull()
                    }
                    "reserved" -> {
                        hasReservedLine = true
                        reserved = normalizeReservedValue(value.takeIf { it.isNotBlank() })
                    }
                }
                "peer" -> when (key) {
                    "publickey" -> {
                        peerPublicKey = value.takeIf { it.isNotBlank() }
                    }
                    "presharedkey" -> {
                        peerPresharedKey = value.takeIf { it.isNotBlank() }
                    }
                    "persistentkeepalive" -> {
                        peerPersistentKeepalive = value.toIntOrNull()?.coerceIn(0, 65_535)
                    }
                    "advancedsecurity" -> {
                        peerAdvancedSecurity = value.takeIf { it.isNotBlank() }
                    }
                }
            }
        }

        return ImportedInterfaceOverrides(
            dnsServers = dnsServers.distinct(),
            mtu = mtu,
            reserved = reserved,
            hasReservedLine = hasReservedLine,
            privateKey = privateKey,
            addresses = addresses.distinct(),
            peerPublicKey = peerPublicKey,
            peerPresharedKey = peerPresharedKey,
            peerPersistentKeepalive = peerPersistentKeepalive,
            peerAdvancedSecurity = peerAdvancedSecurity,
        )
    }

    private fun parseImportedInterfaceAddresses(value: String): List<ImportedInterfaceAddress> {
        return value.split(',')
            .mapNotNull { token ->
                val normalized = token.trim()
                if (normalized.isBlank()) return@mapNotNull null
                val address = normalized.substringBefore('/').trim().removePrefix("[").removeSuffix("]")
                if (address.isBlank()) return@mapNotNull null
                val parsedAddress = runCatching { InetAddress.getByName(address) }.getOrNull()
                    ?: return@mapNotNull null
                val inferredPrefix = when (parsedAddress) {
                    is Inet4Address -> 32
                    is Inet6Address -> 128
                    else -> return@mapNotNull null
                }
                val prefix = normalized.substringAfter('/', "")
                    .trim()
                    .toIntOrNull()
                    ?.coerceIn(0, if (parsedAddress is Inet4Address) 32 else 128)
                    ?: inferredPrefix
                ImportedInterfaceAddress(
                    address = parsedAddress.hostAddress?.substringBefore('%').orEmpty().ifBlank { address },
                    prefixLength = prefix,
                )
            }
            .distinct()
    }

    private fun hasImportedWireGuardIdentity(rawConfig: String): Boolean {
        val overrides = parseImportedInterfaceIdentity(rawConfig)
        return overrides.privateKey?.isNotBlank() == true &&
            overrides.addresses.isNotEmpty() &&
            overrides.peerPublicKey?.isNotBlank() == true
    }

    private fun parseImportedInterfaceIdentity(rawConfig: String): ImportedInterfaceOverrides {
        var currentSection = ""
        val addresses = mutableListOf<ImportedInterfaceAddress>()
        var privateKey: String? = null
        var peerPublicKey: String? = null
        rawConfig.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isBlank()) return@forEach
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.removePrefix("[").removeSuffix("]").trim().lowercase(Locale.US)
                return@forEach
            }
            if (!trimmed.contains('=')) return@forEach
            val key = trimmed.substringBefore('=').trim().lowercase(Locale.US)
            val value = trimmed.substringAfter('=').trim()
            when (currentSection) {
                "interface" -> when (key) {
                    "privatekey" -> privateKey = value.takeIf { it.isNotBlank() }
                    "address" -> addresses += parseImportedInterfaceAddresses(value)
                }
                "peer" -> if (key == "publickey") {
                    peerPublicKey = value.takeIf { it.isNotBlank() }
                }
            }
        }
        return ImportedInterfaceOverrides(
            privateKey = privateKey,
            addresses = addresses.distinct(),
            peerPublicKey = peerPublicKey,
        )
    }

    private fun defaultTunnelInterfaceIdentity(ipv4: String, ipv6: String): TunnelInterfaceIdentity {
        val addresses = buildList {
            if (ipv4.isNotBlank()) add(ImportedInterfaceAddress(ipv4, 32))
            if (ipv6.isNotBlank()) add(ImportedInterfaceAddress(ipv6, 128))
        }
        return TunnelInterfaceIdentity(addresses = addresses, source = "nova")
    }

    private fun resolveTunnelInterfaceIdentityForAttempt(
        attempt: ConnectionAttempt,
        clientData: ClientData,
        defaultIpv4: String,
        defaultIpv6: String,
    ): TunnelInterfaceIdentity {
        if (attempt.mode.preferImportedRawIdentity) {
            val imported = resolveImportedInterfaceOverridesForAttempt(attempt, clientData)
            if (
                imported.privateKey?.isNotBlank() == true &&
                imported.peerPublicKey?.isNotBlank() == true &&
                imported.addresses.isNotEmpty()
            ) {
                return TunnelInterfaceIdentity(
                    addresses = imported.addresses,
                    source = "imported-raw",
                )
            }
            LogManager.log(
                "Imported raw identity requested for ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}, " +
                    "но в raw-конфиге не хватает PrivateKey/Address/PublicKey. Используем identity Nova."
            )
        }
        return defaultTunnelInterfaceIdentity(defaultIpv4, defaultIpv6)
    }

    private fun canRunAdaptationAttemptWithoutNovaIdentity(
        attempt: ConnectionAttempt,
        clientData: ClientData,
        masqueIdentityAvailable: Boolean,
    ): Boolean {
        if (attempt.mode.engine == "masque") {
            return masqueIdentityAvailable
        }
        val imported = resolveImportedInterfaceOverridesForAttempt(attempt, clientData)
        return imported.privateKey?.isNotBlank() == true &&
            imported.addresses.isNotEmpty() &&
            imported.peerPublicKey?.isNotBlank() == true
    }

    private fun establishTunnelInterfaceForAttempt(
        defaultIpv4: String,
        defaultIpv6: String,
        attempt: ConnectionAttempt,
        clientData: ClientData,
    ): ParcelFileDescriptor? {
        if (warpConfigDiscoveryStopRequested.get()) {
            LogManager.log(
                "Не создаём TUN для ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port}: " +
                    "остановка WARP-проверки уже запрошена."
            )
            return null
        }
        val identity = resolveTunnelInterfaceIdentityForAttempt(
            attempt = attempt,
            clientData = clientData,
            defaultIpv4 = defaultIpv4,
            defaultIpv6 = defaultIpv6,
        )
        val importedOverrides = resolveImportedInterfaceOverridesForAttempt(attempt, clientData)
        val shouldUseImportedDnsForBuilder =
            identity.source == "imported-raw" ||
                (clientData.isImportedConfigSourceActive() && attempt.importedConfigHost != null)
        val importedDnsOverride = if (shouldUseImportedDnsForBuilder) {
            importedOverrides.dnsServers
                .map { it.trim() }
                .filter { raw ->
                    runCatching { InetAddress.getByName(raw) }.isSuccess
                }
                .distinct()
        } else {
            emptyList()
        }
        return establishTunnelInterface(
            ipv4 = defaultIpv4,
            ipv6 = defaultIpv6,
            clientData = clientData,
            identityOverride = identity.takeIf { it.source == "imported-raw" },
            dnsOverride = importedDnsOverride,
            localBypassProtectedAddresses = importedDnsOverride
                .filter { raw ->
                    runCatching { InetAddress.getByName(raw.trim()) }.getOrNull()?.let { address ->
                        !address.isLoopbackAddress && !address.isAnyLocalAddress
                    } == true
                }
                .toSet(),
        )
    }

    private fun resolveAwgInterfaceExtrasForAttempt(
        attempt: ConnectionAttempt,
        clientData: ClientData,
    ): List<String> {
        val matchingConfig = findMatchingWarpVerifiedConfigForAttempt(attempt, clientData)
            ?: return emptyList()

        val allExtras = extractSupportedAwgInterfaceLines(
            rawConfig = matchingConfig.rawConfig,
            includeHandshakePayloads = true,
        )
        val extras = if (clientData.isAwgJunkDisabled()) {
            // Опыт по проблеме 5: мы шлём по Jc мусорных пакетов перед каждым
            // рукопожатием на живой сервер Cloudflare, а рукопожатий втрое больше
            // нормы. Отключаемый junk даёт вторую точку замера на том же узле —
            // без него сравнивать не с чем, и любая правка алгоритма была бы
            // угадыванием. Заголовки и паддинги (H/S) остаются: их край принимает
            // как обычный WireGuard.
            allExtras.filterNot { line ->
                line.substringBefore('=').trim().uppercase(Locale.US)
                    .matches(Regex("JC|JMIN|JMAX|I[1-5]"))
            }
        } else {
            allExtras
        }
        if (extras.isNotEmpty() || allExtras.isNotEmpty()) {
            LogManager.log(
                "AWG client extras for ${attempt.endpointHost}:${attempt.port}/${attempt.mode.name}: " +
                    extras.joinToString(", ") { it.substringBefore('=').trim() }.ifBlank { "<нет>" } +
                    if (clientData.isAwgJunkDisabled()) " (junk отключён отладочным ключом)" else ""
            )
        }
        return extras
    }

    private fun extractSupportedAwgInterfaceLines(
        rawConfig: String,
        includeHandshakePayloads: Boolean = true,
    ): List<String> {
        val supportedKeys = Regex("(?i)^(Jc|Jmin|Jmax|S[1-4]|H[1-4]|I[1-5])\\s*=")
        val deduped = linkedMapOf<String, String>()
        rawConfig.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trim()
            if (trimmed.isBlank() || !supportedKeys.containsMatchIn(trimmed)) return@forEach
            val key = trimmed.substringBefore('=').trim().uppercase(Locale.US)
            if (!includeHandshakePayloads && key.matches(Regex("I[1-5]"))) return@forEach
            deduped[key] = trimmed
        }
        return deduped.values.toList()
    }

    private fun runMasquePhase(
        identity: MasqueIdentity,
        clientData: ClientData,
        wireGuardPrivateKey: String,
        wireGuardIpv4: String,
        wireGuardIpv6: String,
        wireGuardPeerPub: String,
        wireGuardReserved: String?,
        trafficMaskHosts: List<String> = emptyList(),
        cycleUnderlyingSignature: String? = null,
        connectGenerationId: Int,
        fastStart: Boolean = false,
        aggressiveFastStart: Boolean = false,
        exhaustiveCandidates: Boolean = false,
    ): Int {
        var activeMasqueIdentity = identity
        // Метку ставим до первой попытки: интерфейс туннеля собирается раньше,
        // чем начинается перебор, и сбой на этом шаге тоже должен засчитаться
        // MASQUE, а не предыдущему транспорту.
        currentTransportLabel = TRANSPORT_MASQUE
        // Метку надо не только поставить, но и опубликовать. Пока она лежала до
        // ближайшего broadcast, экран все секунды скана считал фазу неизвестной и
        // показывал заглушку по размеру списка встроенных профилей: сначала «x/50»,
        // потом «x/4» от самого MASQUE. Со стороны это выглядело так, будто перебор
        // перескочил с выбранных профилей на чужие.
        currentAttemptOrdinal = 0
        currentAttemptTotal = 0
        broadcastState(STATE_CONNECTING)
        LogManager.log(
            if (fastStart) {
                "Пробуем MASQUE / HTTP3 (ранний быстрый fallback без скана)."
            } else {
                "Пробуем MASQUE / HTTP3."
            }
        )
        val masqueAttempts = buildMasqueConnectionAttempts(
            identity = identity,
            clientData = clientData,
            includeScannerCandidates = !fastStart,
        )
        val earlyVerifiedMasqueCount = masqueAttempts.count {
            it.endpointSource.equals("verified-config", ignoreCase = true) ||
                it.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                it.endpointSource.equals("last-success", ignoreCase = true)
        }
        val pixelOrdinaryWifiMasqueExtendedFallback =
            android.os.Build.MANUFACTURER.equals("Google", ignoreCase = true) &&
                !isLegacy32BitDevice() &&
                !clientData.shouldForceMessengerWarpPriority() &&
                !lastRestrictedMobileDetected
        val legacy32MasqueExtendedFallback = isLegacy32BitDevice() && !lastRestrictedMobileDetected
        val masqueAttemptLimit = when {
            // Явный выбор MASQUE: перебор должен быть широким, потому что заканчивать его
            // некуда. В режиме «Авто» кончившиеся кандидаты означают переход на WARP/AWG, а
            // здесь — «Останавливаем цикл» и экран «НЕ ПОДКЛЮЧЕНО». На тестовое устройство четырёх
            // кандидатов хватало на 30 секунд, из которых два адреса были одним и тем же
            // host:port с разными SNI, а два порта эта сеть не пропускает вовсе.
            exhaustiveCandidates -> 12
            fastStart && legacy32MasqueExtendedFallback && earlyVerifiedMasqueCount >= 4 -> 6
            fastStart && legacy32MasqueExtendedFallback && earlyVerifiedMasqueCount >= 3 -> 5
            fastStart && pixelOrdinaryWifiMasqueExtendedFallback && earlyVerifiedMasqueCount >= 4 -> 7
            fastStart && pixelOrdinaryWifiMasqueExtendedFallback && earlyVerifiedMasqueCount >= 3 -> 6
            fastStart && earlyVerifiedMasqueCount >= 4 -> 5
            fastStart && earlyVerifiedMasqueCount >= 3 -> 4
            fastStart && aggressiveFastStart -> 3
            fastStart -> 3
            else -> 4
        }
        val rankedMasqueAttempts = ensureMasqueModeDiversity(
            prioritizeConnectionAttempts(masqueAttempts, clientData),
            masqueAttemptLimit,
            clientData,
        )
        val masqueProgressBudget = rankedMasqueAttempts.size
        val masqueVerifiedSourceCount = rankedMasqueAttempts.count {
            it.endpointSource.equals("verified-config", ignoreCase = true) ||
                it.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                it.endpointSource.equals("last-success", ignoreCase = true)
        }
        LogManager.log(
            "MASQUE кандидаты: ${
                rankedMasqueAttempts.joinToString(",") { "${it.endpointHost}:${it.port}(${it.endpointSource})" }
            }"
        )
        val masqueDescriptor = establishTunnelInterface(
            activeMasqueIdentity.ipv4,
            activeMasqueIdentity.ipv6,
            clientData,
            mtuOverride = resolveMasqueTunnelMtu(),
        )
        if (masqueDescriptor == null) {
            LogManager.log("Не удалось поднять единый VPN-интерфейс для MASQUE-фазы.")
            return masqueProgressBudget
        }
        interfaceDescriptor = masqueDescriptor
        try {
            runConnectionAttempts(
                descriptor = masqueDescriptor,
                descriptorFactory = { _ ->
                    establishTunnelInterface(
                        activeMasqueIdentity.ipv4,
                        activeMasqueIdentity.ipv6,
                        clientData,
                        mtuOverride = resolveMasqueTunnelMtu(),
                    )
                },
                connectionAttempts = rankedMasqueAttempts,
                clientData = clientData,
                wireGuardPrivateKey = wireGuardPrivateKey,
                wireGuardIpv4 = wireGuardIpv4,
                wireGuardIpv6 = wireGuardIpv6,
                wireGuardPeerPub = wireGuardPeerPub,
                wireGuardReserved = wireGuardReserved,
                masqueIdentityJson = clientData.getMasqueConfigJson(),
                maxCycles = 1,
                globalAttemptOffset = 0,
                // Счётчик показывает длину перебора MASQUE, а не ожидаемый остаток
                // цикла WARP: у MASQUE три-четыре кандидата, и «1/50» рядом с
                // «ПОДКЛЮЧЕНИЕ... MASQUE» читалось как чужая фаза.
                globalAttemptTotal = masqueProgressBudget,
                // Кэш сглаживает счётчик там, где размер перебора заранее неизвестен —
                // это про WARP с его discovery. У MASQUE список кандидатов известен
                // точно, и подтянутое из кэша большее число только врёт.
                useCachedAttemptTotal = false,
                trafficMaskHosts = trafficMaskHosts,
                cycleUnderlyingSignature = cycleUnderlyingSignature,
                connectGenerationId = connectGenerationId,
                fastConnectMode = aggressiveFastStart || (fastStart && masqueVerifiedSourceCount > 0),
                onMasqueIdentityRefreshed = { refreshedIdentity ->
                    activeMasqueIdentity = refreshedIdentity
                },
            )
        } finally {
            if (!(currentState == STATE_CONNECTED && hasRecentSuccessfulTunnelProbe())) {
                closeActiveInterface()
            }
        }
        return masqueProgressBudget
    }

    private fun closeAttemptInterface(descriptor: ParcelFileDescriptor?) {
        if (descriptor == null) return
        try {
            descriptor.close()
            LogManager.log("Текущий VPN-интерфейс закрыт.")
        } catch (_: Exception) {
        } finally {
            if (interfaceDescriptor === descriptor) {
                interfaceDescriptor = null
            }
        }
    }

    /**
     * Отдаёт движку живой дескриптор tun, не передавая владение.
     *
     * Раньше здесь был `dup().detachFd()`, и это течело: Go в CreateAndroidTUN всё
     * равно делает свой `syscall.Dup`, а отсоединённую копию не закрывает никто —
     * в Java владельца у неё уже нет. Пока хоть один дескриптор tun открыт,
     * интерфейс живёт, поэтому каждый реконнект оставлял в системе лишний tun:
     * после четырёх переключений сети на устройстве висели tun0..tun3 сразу.
     *
     * Дублирование на стороне движка синхронное и происходит до возврата из
     * startVPN, так что отдавать сюда обычный fd безопасно: владение остаётся за
     * ParcelFileDescriptor, который закроет closeAttemptInterface.
     */
    private fun tunnelFdForEngine(descriptor: ParcelFileDescriptor): Long {
        return descriptor.fd.toLong()
    }

    private fun setTrafficCamouflageHostCompat(host: String) {
        if (!optionalTrafficCamouflageSetterAvailable) return
        try {
            Nova.setTrafficCamouflageHost(host)
        } catch (t: Throwable) {
            optionalTrafficCamouflageSetterAvailable = false
            LogManager.log(
                "Optional JNI setTrafficCamouflageHost недоступен. " +
                    "Отключаем host-level camouflage для текущей native-библиотеки: ${t.message}"
            )
        }
    }

    private fun setMasqueFakeBurstEnabledCompat(enabled: Boolean) {
        if (!optionalMasqueFakeBurstSetterAvailable) return
        try {
            Nova.setMasqueFakeBurstEnabled(enabled)
        } catch (t: Throwable) {
            optionalMasqueFakeBurstSetterAvailable = false
            LogManager.log(
                "Optional JNI setMasqueFakeBurstEnabled недоступен. " +
                    "Отключаем fake-burst toggle для текущей native-библиотеки: ${t.message}"
            )
        }
    }

    private fun setTelegramTransparentProxyConfigCompat(enabled: Boolean, profile: String) {
        if (!optionalTelegramTransparentSetterAvailable) return
        try {
            Nova.setTelegramTransparentProxyConfig(enabled, profile)
        } catch (t: Throwable) {
            optionalTelegramTransparentSetterAvailable = false
            LogManager.log(
                "Optional JNI setTelegramTransparentProxyConfig недоступен. " +
                    "Отключаем transparent Telegram relay для текущей native-библиотеки: ${t.message}"
            )
        }
    }

    /**
     * Передаёт движку секрет подписи WSS для собственных поддоменов `nova-app.eu`.
     *
     * Сам токен считает Go: окно подписи двухминутное и привязано к моменту
     * открытия соединения, а пул WebSocket добивает соединения по мере расхода —
     * посчитанный здесь заранее токен успел бы протухнуть.
     */
    private fun installTelegramWsSignatureSecret() {
        if (telegramWsSignatureSecretInstalled || !optionalTelegramWsSignatureSetterAvailable) return
        val secret = BuildConfig.TG_CF_WS_SECRET
        try {
            Nova.setTelegramWsSignatureSecret(secret)
            telegramWsSignatureSecretInstalled = true
            LogManager.log(
                if (secret.isBlank()) {
                    "Подпись WSS выключена: секрет в сборке не задан, идём по публичным доменам Cloudflare."
                } else {
                    "Подпись WSS включена для собственных узлов ${CfWsToken.OWNED_DOMAIN}."
                }
            )
        } catch (t: Throwable) {
            optionalTelegramWsSignatureSetterAvailable = false
            LogManager.log(
                "Optional JNI setTelegramWsSignatureSecret недоступен. " +
                    "Подпись WSS не включаем для текущей native-библиотеки: ${t.message}"
            )
        }
    }

    private fun runConnectionAttempts(
        descriptor: ParcelFileDescriptor?,
        descriptorFactory: ((ConnectionAttempt) -> ParcelFileDescriptor?)?,
        connectionAttempts: List<ConnectionAttempt>,
        clientData: ClientData,
        wireGuardPrivateKey: String,
        wireGuardIpv4: String,
        wireGuardIpv6: String,
        wireGuardPeerPub: String,
        wireGuardReserved: String?,
        masqueIdentityJson: String?,
        maxCycles: Int?,
        globalAttemptOffset: Int = 0,
        globalAttemptTotal: Int = connectionAttempts.size,
        trafficMaskHosts: List<String> = emptyList(),
        cycleUnderlyingSignature: String? = null,
        persistPrimarySuccess: Boolean = true,
        continueAfterVerifiedSuccess: Boolean = false,
        verifiedSuccessHoldMs: Long = 3500L,
        fastScanMode: Boolean = false,
        connectGenerationId: Int,
        externalStopRequested: (() -> Boolean)? = null,
        onAttemptStart: ((ConnectionAttempt, Int, Int) -> Unit)? = null,
        onAttemptResult: ((ConnectionAttempt, String, Long, Long) -> Unit)? = null,
        fastConnectMode: Boolean = false,
        onMasqueIdentityRefreshed: ((MasqueIdentity) -> Unit)? = null,
        useCachedAttemptTotal: Boolean = true,
        deferMasqueWithoutIdentity: Boolean = false,
        qualitySamplingWindowMs: Long = 20_000L,
        progressGroupKeys: List<String>? = null,
    ) {
        if (connectionAttempts.isEmpty()) return

        var attemptIndex = 0
        var attemptsCompleted = 0
        var unstableMasqueStreak = 0
        // Прошло ли на этой сети хоть одно рукопожатие QUIC без маскировки.
        //
        // Ставится из потока движка, читается из потока перебора, поэтому атомарный.
        val masqueQuicPassesClean = AtomicBoolean(false)
        var runtimeMasqueIdentityJson = masqueIdentityJson
        var refreshedMasqueIdentityThisRun = false
        var reusableDescriptor: ParcelFileDescriptor? = descriptor
        var reusableDescriptorIdentity: TunnelInterfaceIdentity? =
            descriptor?.let {
                resolveTunnelInterfaceIdentityForAttempt(
                    attempt = connectionAttempts.first(),
                    clientData = clientData,
                    defaultIpv4 = wireGuardIpv4,
                    defaultIpv6 = wireGuardIpv6,
                )
            }
        val importedSourceProgressActive =
            clientData.isImportedConfigSourceActive() &&
                connectionAttempts.any { it.importedConfigHost != null }
        val importedProgressGroupKeys = if (importedSourceProgressActive) {
            connectionAttempts.map { attempt ->
                val importedHost = attempt.importedConfigHost
                    ?.trim()
                    ?.removePrefix("[")
                    ?.removeSuffix("]")
                    ?.lowercase(Locale.US)
                    .orEmpty()
                    .ifBlank {
                        attempt.endpointHost.trim()
                            .removePrefix("[")
                            .removeSuffix("]")
                            .lowercase(Locale.US)
                    }
                "$importedHost:${attempt.port}:${normalizeImportedRuntimeModeName(attempt.mode.name)}"
            }.distinct()
        } else {
            emptyList()
        }
        currentAttemptTotal = if (importedProgressGroupKeys.isNotEmpty()) {
            importedProgressGroupKeys.size
        } else {
            maxOf(
                connectionAttempts.size,
                globalAttemptTotal,
                if (useCachedAttemptTotal) {
                    clientData.getCachedConnectAttemptTotal(currentBackendLabel, currentTransportLabel)
                } else {
                    0
                },
            )
        }
        clientData.rememberConnectAttemptTotal(currentAttemptTotal, currentBackendLabel, currentTransportLabel)
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val selectedUnderlyingNetwork = selectUnderlyingNetwork(connectivityManager)
        val selectedUnderlyingSignature = cycleUnderlyingSignature
            ?: buildUnderlyingNetworkSignature(
                connectivityManager,
                selectedUnderlyingNetwork,
            )
        val strategyNetworkClass =
            stableSuccessNetworkClassFromSignature(selectedUnderlyingSignature)
                ?: stableSuccessNetworkClassFromSignature(
                    buildUnderlyingNetworkClass(connectivityManager, selectedUnderlyingNetwork)
                )
        val allowMasqueIdentityRefreshInRun =
            lastRestrictedMobileDetected ||
                isMeteredUnderlyingNetwork(connectivityManager, selectedUnderlyingNetwork)
        val preferMessengerChatProfiles = clientData.shouldForceMessengerWarpPriority()
        val trustedExoticMasqueAvailable = connectionAttempts.any { attempt ->
            attempt.mode.engine == "masque" &&
                attempt.port in setOf(443, 4443) &&
                (
                    attempt.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                        attempt.endpointSource.equals("last-success", ignoreCase = true) ||
                        attempt.endpointSource.equals("verified-config", ignoreCase = true)
                    )
        }
        var trustedExoticMasqueTried = false
        val protectedMasqueFastPathKeys =
            connectionAttempts
                .filter { attempt ->
                    attempt.mode.engine == "masque" &&
                        (
                            attempt.endpointSource.equals("verified-config", ignoreCase = true) ||
                                attempt.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                                attempt.endpointSource.equals("last-success", ignoreCase = true) ||
                                attempt.endpointSource.equals("bundled-seed", ignoreCase = true)
                            )
                }
                .take(5)
                .map { attempt ->
                    "${attempt.mode.name.lowercase()}:${attempt.endpointHost.trim().lowercase()}:${attempt.port}"
                }
                .toSet()
        val triedMasqueFastPathKeys = linkedSetOf<String>()

        while (!isUserStopped && isConnectGenerationCurrent(connectGenerationId) && externalStopRequested?.invoke() != true) {
            if (shouldPauseConnectForMissingUnderlying(clientData, "connect-cycle")) {
                break
            }
            val attemptLimit = maxCycles?.let { connectionAttempts.size * it }
            if (attemptLimit != null && attemptsCompleted >= attemptLimit) {
                LogManager.log("Лимит попыток исчерпан для ${connectionAttempts.first().mode.engine}.")
                break
            }

            val currentAttempt = connectionAttempts[attemptIndex]
            setCurrentTransportForAttempt(currentAttempt)
            val currentHost = currentAttempt.endpointHost
            val currentPort = currentAttempt.port
            if (currentAttempt.mode.engine == "masque") {
                triedMasqueFastPathKeys +=
                    "${currentAttempt.mode.name.lowercase()}:${currentHost.trim().lowercase()}:${currentPort}"
            }
            if (
                currentAttempt.mode.engine == "masque" &&
                currentPort in setOf(443, 4443) &&
                (
                    currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                        currentAttempt.endpointSource.equals("last-success", ignoreCase = true) ||
                        currentAttempt.endpointSource.equals("verified-config", ignoreCase = true)
                    )
            ) {
                trustedExoticMasqueTried = true
            }
            val transportMode = currentAttempt.mode
            val explicitAttemptScope = currentAttempt.strategyScope?.trim()?.ifBlank { null }
            val strategyLearningScope = explicitAttemptScope
                ?: if (clientData.shouldForceMessengerWarpPriority()) "messenger" else "default"
            val verifiedConfigScope = explicitAttemptScope
                ?: if (clientData.shouldForceMessengerWarpPriority() && isChatAwareWarpMode(transportMode)) {
                    "messenger"
                } else {
                    "default"
                }
            val currentEndpoint = formatEndpoint(currentHost, currentPort)
            val modeLabel = transportMode.name
            val attemptLabel = attemptLogLabel(currentAttempt)
            val progressGroupKeysToUse = progressGroupKeys ?: if (importedProgressGroupKeys.isNotEmpty()) {
                importedProgressGroupKeys
            } else {
                null
            }

            if (progressGroupKeysToUse != null) {
                val currentKey = if (importedSourceProgressActive && progressGroupKeys == null) {
                    val importedHost = currentAttempt.importedConfigHost
                        ?.trim()
                        ?.removePrefix("[")
                        ?.removeSuffix("]")
                        ?.lowercase(Locale.US)
                        .orEmpty()
                        .ifBlank {
                            currentHost.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.US)
                        }
                    "$importedHost:${currentAttempt.port}:${normalizeImportedRuntimeModeName(currentAttempt.mode.name)}"
                } else {
                    buildWarpDiscoveryAttemptKey(currentAttempt.mode.name, currentHost, currentPort)
                }
                val progressTotal = progressGroupKeysToUse.size.coerceAtLeast(1)
                val keyIndex = progressGroupKeysToUse.indexOf(currentKey)
                currentAttemptTotal = progressTotal
                // During a manual profile switch the UI commands an explicit ordinal
                // (the sequential profile cursor 1..N). Honor it instead of deriving
                // the ordinal from the config's seedOrder position, otherwise the
                // counter jumps to an unrelated value.
                currentAttemptOrdinal = if (manualWarpProfileSwitchTargetKey != null &&
                    manualWarpProfileSwitchOrdinal > 0 &&
                    manualWarpProfileSwitchTotal > 0
                ) {
                    manualWarpProfileSwitchOrdinal.coerceIn(1, manualWarpProfileSwitchTotal)
                } else if (keyIndex >= 0) {
                    keyIndex + 1
                } else {
                    currentAttemptOrdinal
                        .takeIf { it in 1..progressTotal }
                        ?: (globalAttemptOffset + attemptIndex + 1).coerceIn(1, progressTotal)
                }
                LogManager.log(
                    "DIAG attempt-ordinal: manual=${manualWarpProfileSwitchTargetKey != null} " +
                        "cmdOrdinal=$manualWarpProfileSwitchOrdinal cmdTotal=$manualWarpProfileSwitchTotal " +
                        "keyIndex=${if (keyIndex >= 0) keyIndex else "n/a"} progressTotal=$progressTotal " +
                        "-> currentAttemptOrdinal=$currentAttemptOrdinal"
                )
            } else {
                currentAttemptOrdinal = (globalAttemptOffset + attemptIndex + 1).coerceAtLeast(1)
                currentAttemptTotal = maxOf(currentAttemptTotal, currentAttemptOrdinal)
            }
            clientData.rememberConnectAttemptTotal(currentAttemptTotal, currentBackendLabel, currentTransportLabel)
            publishWarpTrafficMaskHint(
                clientData = clientData,
                trafficMaskHosts = trafficMaskHosts,
                attemptIndex = attemptIndex,
                preferredHost = currentAttempt.preferredSni,
            )
            broadcastState(STATE_CONNECTING)
            onAttemptStart?.invoke(currentAttempt, currentAttemptOrdinal, currentAttemptTotal)
            LogManager.log(
                "Пробуем endpoint: $currentEndpoint, источник: ${currentAttempt.endpointSource}, режим: $modeLabel"
            )
            val attemptStartedAt = SystemClock.elapsedRealtime()
            val attemptStartedWallTimeSec = System.currentTimeMillis() / 1000L
            val longBudgetAttempt = shouldUseLongAttemptBudget(currentAttempt)
            if (transportMode.engine == "masque" && runtimeMasqueIdentityJson.isNullOrBlank()) {
                val deferred = deferMasqueWithoutIdentity
                LogManager.log(
                    "MASQUE identity отсутствует для $modeLabel@$currentEndpoint. " +
                        if (deferred) {
                            "Откладываем профиль до получения MASQUE identity без штрафа."
                        } else {
                            "Засчитываем профиль как непрошедший адаптацию без ожидания таймаута."
                        }
                )
                if (!deferred) {
                    clientData.recordStrategyOutcome(
                        engine = transportMode.engine,
                        mode = transportMode.name,
                        host = currentHost,
                        port = currentPort,
                        outcome = AttemptOutcome.FAILURE,
                        connectDurationMs = 0L,
                        stableDurationMs = 0L,
                        strategyScope = strategyLearningScope,
                        networkClass = strategyNetworkClass,
                        failureReason = "masque_identity_missing",
                    )
                    clientData.recordWarpVerifiedRuntimeOutcome(
                        engine = transportMode.engine,
                        mode = transportMode.name,
                        host = currentHost,
                        port = currentPort,
                        success = false,
                        endpointSource = currentAttempt.endpointSource,
                        rawConfig = null,
                        scope = verifiedConfigScope,
                    )
                }
                onAttemptResult?.invoke(
                    currentAttempt,
                    if (deferred) AttemptOutcome.DEFERRED else AttemptOutcome.FAILURE,
                    0L,
                    0L,
                )
                attemptsCompleted++
                attemptIndex = (attemptIndex + 1) % connectionAttempts.size
                Thread.sleep(120L)
                continue
            }
            val currentInterfaceIdentity = resolveTunnelInterfaceIdentityForAttempt(
                attempt = currentAttempt,
                clientData = clientData,
                defaultIpv4 = wireGuardIpv4,
                defaultIpv6 = wireGuardIpv6,
            )
            val descriptorIdentityChanged =
                reusableDescriptor != null &&
                    reusableDescriptorIdentity != null &&
                    reusableDescriptorIdentity != currentInterfaceIdentity

            val requiresFreshDescriptor = descriptorFactory != null &&
                (fastScanMode || reusableDescriptor == null || interfaceDescriptor == null || descriptorIdentityChanged)
            var attemptDescriptor: ParcelFileDescriptor? = reusableDescriptor
            if (requiresFreshDescriptor) {
                if (externalStopRequested?.invoke() == true || !isConnectGenerationCurrent(connectGenerationId)) {
                    LogManager.log("Новая TUN-попытка отменена: остановка адаптации уже запрошена.")
                    break
                }
                closeAttemptInterface(attemptDescriptor)
                if (descriptorIdentityChanged) {
                    LogManager.log(
                        "TUN identity changed for ${currentAttempt.mode.name}@${currentEndpoint}: " +
                            "${reusableDescriptorIdentity?.source} -> ${currentInterfaceIdentity.source}. Пересоздаём интерфейс."
                    )
                }
                val freshDescriptor = descriptorFactory?.invoke(currentAttempt)
                if (freshDescriptor == null) {
                    LogManager.log("Не удалось поднять VPN интерфейс для $currentEndpoint.")
                    attemptsCompleted++
                    attemptIndex = (attemptIndex + 1) % connectionAttempts.size
                    Thread.sleep(600)
                    continue
                }
                interfaceDescriptor = freshDescriptor
                LogManager.log("VPN интерфейс поднят для $currentEndpoint.")
                attemptDescriptor = freshDescriptor
                reusableDescriptor = freshDescriptor
                reusableDescriptorIdentity = currentInterfaceIdentity
            }
            if (attemptDescriptor == null) {
                val recoveredDescriptor = descriptorFactory?.invoke(currentAttempt)
                if (recoveredDescriptor != null) {
                    interfaceDescriptor = recoveredDescriptor
                    LogManager.log("VPN интерфейс восстановлен для $currentEndpoint.")
                    attemptDescriptor = recoveredDescriptor
                    reusableDescriptor = recoveredDescriptor
                    reusableDescriptorIdentity = currentInterfaceIdentity
                } else {
                    LogManager.log("VPN интерфейс отсутствует для попытки $currentEndpoint.")
                    break
                }
            }

            val attemptBaselineStats = readTunnelStats()
            val watchdogActive = AtomicBoolean(true)
            val isConnected = AtomicBoolean(false)
            val everConnected = AtomicBoolean(false)
            val handshakeObserved = AtomicBoolean(false)
            val rotateRequested = AtomicBoolean(false)
            val skipStrategyLearning = AtomicBoolean(false)
            val attemptFailureReason = AtomicReference<String?>(null)
            val firstConnectedAt = java.util.concurrent.atomic.AtomicLong(0L)
            val verifiedAttempt = AtomicBoolean(false)
            val quickRotateHint = AtomicBoolean(false)
            val attemptActive = AtomicBoolean(true)
            val qualitySamplingStarted = AtomicBoolean(false)
            val qualitySamplingFinished = AtomicBoolean(false)
            val qualitySamplingHealthy = AtomicBoolean(false)
            val operaWarmupStarted = AtomicBoolean(false)
            var successPersistedEarly = false
            var stableSuccessPersisted = false
            var stablePromotionWaitLogged = false

            val watchdogThread = Thread {
                try {
                    var failures = 0
                    val cm = getSystemService(android.net.ConnectivityManager::class.java)
                    var firstHandshakeAtMs = 0L
                    var connectivityProbeAttempts = 0
                    var connectivityProbeSucceeded = false
                    var connectivityProbeSuccessCount = 0
                    var connectivityProbeLastAtMs = 0L
                    var earlyMasqueProbeAttempted = false
                    var validationSignalLogged = false
                    var validatedAtMs = 0L
                    var probeOnlySignalLogged = false
                    var inboundAwaitingProbeLogged = false
                    var stableInboundSamples = 0
                    var provisionalValidatedAtMs = 0L
                    var provisionalValidatedLogged = false
                    var legacyValidatedTrafficLogged = false

                    var vpnNetWaitMs = 0
                    var vpnNet: android.net.Network? = null
                    fun resolveCurrentAttemptVpnNetwork(): android.net.Network? {
                        return findLatestLikelyNovaVpnNetwork(cm) ?: findCurrentVpnNetwork(cm)
                    }
                    val vpnNetworkWaitLimitMs = when {
                        fastScanMode -> 2_500
                        fastConnectMode -> 5_000
                        else -> 10_000
                    }
                    while (
                        watchdogActive.get() &&
                            !isUserStopped &&
                            isConnectGenerationCurrent(connectGenerationId) &&
                            vpnNet == null &&
                            vpnNetWaitMs < vpnNetworkWaitLimitMs
                    ) {
                        Thread.sleep(500)
                        vpnNetWaitMs += 500
                        vpnNet = resolveCurrentAttemptVpnNetwork()
                    }

                    if (vpnNet == null) {
                        LogManager.log(
                            "VPN Network не появилась в ConnectivityManager за ${vpnNetworkWaitLimitMs}мс — " +
                                "fallback без привязки"
                        )
                    } else {
                        LogManager.log("VPN Network зарегистрирована (${vpnNetWaitMs}мс), проверяем трафик...")
                    }

                    val attemptBudget = getAttemptBudget(
                        currentAttempt,
                        longBudgetAttempt,
                        fastScanMode,
                        fastConnectMode,
                        preferMessengerChatProfiles,
                    )
                    val noHandshakeTimeoutMs = attemptBudget.noHandshakeTimeoutMs
                    val handshakeTimeoutMs = attemptBudget.handshakeTimeoutMs
                    val noInboundAfterHandshakeTimeoutMs = attemptBudget.noInboundAfterHandshakeTimeoutMs
                    val maxConnectivityProbeAttempts = attemptBudget.maxConnectivityProbeAttempts
                    val minProbeSpacingMs = attemptBudget.minProbeSpacingMs

                    while (watchdogActive.get() && !isUserStopped && isConnectGenerationCurrent(connectGenerationId) && externalStopRequested?.invoke() != true) {
                        val watchdogSleepMs = when {
                            fastScanMode -> 350L
                            fastConnectMode && isChatAwareWarpMode(transportMode) -> 500L
                            fastConnectMode -> 600L
                            isChatAwareWarpMode(transportMode) -> 650L
                            else -> 800L
                        }
                        Thread.sleep(watchdogSleepMs)

                        if (!isConnected.get() && currentState == STATE_CONNECTED && hasRecentSuccessfulTunnelProbe()) {
                            skipStrategyLearning.set(true)
                            LogManager.log(
                                "Другой connect-сеанс уже поднял рабочий VPN. Прерываем текущую попытку $modeLabel@$currentPort."
                            )
                            rotateRequested.set(true)
                            try {
                                Nova.stopVPN()
                            } catch (_: Exception) {
                            }
                            break
                        }

                        val latestVpnNet = resolveCurrentAttemptVpnNetwork()
                        if (latestVpnNet != null && latestVpnNet != vpnNet) {
                            val previousLabel = describeNetwork(cm, vpnNet) ?: vpnNet?.toString() ?: "-"
                            val latestLabel = describeNetwork(cm, latestVpnNet) ?: latestVpnNet.toString()
                            LogManager.log(
                                "Watchdog переключается на более свежую VPN Network: $previousLabel -> $latestLabel"
                            )
                            vpnNet = latestVpnNet
                            validationSignalLogged = false
                            validatedAtMs = 0L
                            connectivityProbeAttempts = 0
                            connectivityProbeSucceeded = false
                            connectivityProbeSuccessCount = 0
                            connectivityProbeLastAtMs = 0L
                            earlyMasqueProbeAttempted = false
                            probeOnlySignalLogged = false
                            inboundAwaitingProbeLogged = false
                            stableInboundSamples = 0
                            provisionalValidatedAtMs = 0L
                            provisionalValidatedLogged = false
                        } else if (vpnNet == null) {
                            vpnNet = latestVpnNet
                        }
                        val currentVpnNet = latestVpnNet ?: vpnNet
                        val vpnValidated = isValidatedVpnNetwork(cm, currentVpnNet)

                    val rawTunnelStats = readTunnelStats()
                    val tunnelStats = relativeTunnelStats(rawTunnelStats, attemptBaselineStats)
                    val handshakeReady = hasFreshHandshake(
                        currentStats = rawTunnelStats,
                        baselineStats = attemptBaselineStats,
                        attemptStartedWallTimeSec = attemptStartedWallTimeSec,
                    ) || hasInboundTraffic(tunnelStats)
                    if (handshakeReady && firstHandshakeAtMs == 0L) {
                        firstHandshakeAtMs = SystemClock.elapsedRealtime()
                    }
                    val attemptElapsedMs = SystemClock.elapsedRealtime() - attemptStartedAt
                    val handshakeAgeMs = if (firstHandshakeAtMs > 0L) {
                        SystemClock.elapsedRealtime() - firstHandshakeAtMs
                    } else {
                        0L
                    }

                    if (
                        currentVpnNet != null &&
                        vpnValidated &&
                        (transportMode.engine != "masque" || handshakeReady || hasInboundTraffic(tunnelStats))
                    ) {
                        if (validatedAtMs == 0L) {
                            validatedAtMs = SystemClock.elapsedRealtime()
                        }
                        if (!validationSignalLogged) {
                            validationSignalLogged = true
                            LogManager.log("VPN Network получила VALIDATED для $modeLabel@$currentPort.")
                        }
                    }

                    if (
                        handshakeReady &&
                        connectivityProbeAttempts < maxConnectivityProbeAttempts &&
                        currentVpnNet != null &&
                        handshakeAgeMs >= 1200L &&
                        (
                            !connectivityProbeSucceeded ||
                                (transportMode.engine != "masque" && connectivityProbeSuccessCount < 2)
                            ) &&
                        (
                            connectivityProbeLastAtMs == 0L ||
                                SystemClock.elapsedRealtime() - connectivityProbeLastAtMs >= minProbeSpacingMs
                            )
                    ) {
                        connectivityProbeAttempts++
                        connectivityProbeLastAtMs = SystemClock.elapsedRealtime()
                        val trustedMasqueProbeBudget =
                            transportMode.engine == "masque" &&
                                isTrustedMasqueFastPort(currentPort) &&
                                (
                                    currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                                        currentAttempt.endpointSource.equals("verified-config", ignoreCase = true) ||
                                        currentAttempt.endpointSource.equals("last-success", ignoreCase = true) ||
                                        currentAttempt.endpointSource.equals("bundled-seed", ignoreCase = true)
                                    )
                        val probeBudgetMs = when {
                            trustedMasqueProbeBudget ->
                                if (fastConnectMode) 1_600 else 1_900
                            transportMode.engine == "masque" -> 1_200
                            isChatAwareWarpMode(transportMode) -> 1_150
                            else -> 700
                        }
                        val allowMasqueHttpProbeFallback =
                            transportMode.engine == "masque" &&
                                vpnValidated &&
                                tunnelStats.rxBytes > 0L
                        if (
                            hasTunnelConnectivity(
                                currentVpnNet,
                                probeBudgetMs,
                                allowHttpDnsFallback = allowMasqueHttpProbeFallback,
                            )
                        ) {
                            markSuccessfulTunnelProbe()
                            connectivityProbeSucceeded = true
                            connectivityProbeSuccessCount += 1
                            if (connectivityProbeSuccessCount >= 2 && transportMode.engine != "masque") {
                                LogManager.log("Повторный tunnel-probe через VPN прошёл для $modeLabel@$currentPort.")
                            } else {
                                LogManager.log("Tunnel-probe через VPN прошёл для $modeLabel@$currentPort.")
                            }
                        } else {
                            LogManager.log("Tunnel-probe через VPN не прошёл для $modeLabel@$currentPort.")
                        }
                    }

                    val inboundReady = tunnelStats.rxBytes > 0L && tunnelStats.txBytes > 0L
                    stableInboundSamples = if (inboundReady) stableInboundSamples + 1 else 0
                    val enoughInboundBytes = if (transportMode.engine == "masque") {
                        tunnelStats.rxBytes >= 256L && tunnelStats.txBytes >= 256L
                    } else {
                        tunnelStats.rxBytes >= 128L && tunnelStats.txBytes >= 128L
                    }
                    val sustainedInbound = stableInboundSamples >= 2 && enoughInboundBytes
                    val strongBidirectionalTrafficReady =
                        transportMode.engine != "masque" &&
                            currentVpnNet != null &&
                            vpnValidated &&
                            handshakeReady &&
                            tunnelStats.rxBytes >= 32_768L &&
                            tunnelStats.txBytes >= 32_768L
                    val validatedReady = transportMode.engine != "masque" &&
                        currentVpnNet != null &&
                        vpnValidated &&
                        (
                            (sustainedInbound && connectivityProbeSucceeded) ||
                                connectivityProbeSuccessCount >= 2
                            )
                    val messengerValidatedReady = isChatAwareWarpMode(transportMode) &&
                        currentVpnNet != null &&
                        vpnValidated &&
                        sustainedInbound &&
                        connectivityProbeSuccessCount >= 1
                    val legacyValidatedTrafficReady =
                        transportMode.engine != "masque" &&
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                            currentVpnNet != null &&
                            vpnValidated &&
                            sustainedInbound &&
                            tunnelStats.rxBytes >= 8_192L &&
                            tunnelStats.txBytes >= 4_096L
                    val importedRawValidatedTrafficReady =
                        transportMode.engine != "masque" &&
                            currentAttempt.importedConfigHost != null &&
                            transportMode.preferImportedRawIdentity &&
                            handshakeReady &&
                            tunnelStats.rxBytes >= 8_192L &&
                            tunnelStats.txBytes >= 8_192L
                    if (legacyValidatedTrafficReady && !legacyValidatedTrafficLogged) {
                        legacyValidatedTrafficLogged = true
                        LogManager.log(
                            "$modeLabel@$currentPort на Android < 10 дал системный VALIDATED " +
                                "и устойчивый двусторонний data-plane " +
                                "(rx=${tunnelStats.rxBytes}, tx=${tunnelStats.txBytes}). " +
                                "Доверяем legacy traffic heuristic."
                        )
                    }
                    if (importedRawValidatedTrafficReady && !legacyValidatedTrafficLogged) {
                        legacyValidatedTrafficLogged = true
                        noteImportedExactAwgTrafficProof(rawTunnelStats)
                        LogManager.log(
                            "$modeLabel@$currentPort дал системный VALIDATED и устойчивый двусторонний " +
                                "traffic на imported raw-profile " +
                                "(rx=${tunnelStats.rxBytes}, tx=${tunnelStats.txBytes}). " +
                                "Для exact imported AWG считаем это достаточным data-plane подтверждением."
                        )
                    }
                    val dataReady = if (transportMode.engine == "masque") {
                        sustainedInbound && connectivityProbeSucceeded
                    } else {
                        strongBidirectionalTrafficReady ||
                            legacyValidatedTrafficReady ||
                            importedRawValidatedTrafficReady ||
                            messengerValidatedReady ||
                            validatedReady ||
                            (sustainedInbound && connectivityProbeSucceeded)
                    }
                    val trustedCoreVerifiedAttempt =
                        (
                            (transportMode.engine == "masque" && isTrustedMasqueFastPort(currentPort)) ||
                                (transportMode.engine != "masque" && isCoreWarpPort(currentPort))
                            ) &&
                            (
                                currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                                    currentAttempt.endpointSource.equals("verified-config", ignoreCase = true) ||
                                    currentAttempt.endpointSource.equals("last-success", ignoreCase = true) ||
                                    currentAttempt.endpointSource.equals("bundled-seed", ignoreCase = true)
                                )
                    val trustedMasqueTrafficReady =
                        transportMode.engine == "masque" &&
                            currentVpnNet != null &&
                            vpnValidated &&
                            trustedCoreVerifiedAttempt &&
                            sustainedInbound &&
                            connectivityProbeSucceeded &&
                            tunnelStats.rxBytes >= 256L &&
                            tunnelStats.txBytes >= 4_096L
                    val messengerPriorityCore =
                        preferMessengerChatProfiles &&
                            transportMode.engine != "masque" &&
                            !isChatAwareWarpMode(transportMode) &&
                            isCoreWarpPort(currentPort)
                    val earlyMasqueProbeDelayMs = when {
                        transportMode.engine != "masque" -> Long.MAX_VALUE
                        trustedCoreVerifiedAttempt &&
                            currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) ->
                            3_000L
                        trustedCoreVerifiedAttempt -> 3_200L
                        else -> 2_000L
                    }

                    if (
                        transportMode.engine == "masque" &&
                        currentVpnNet != null &&
                        !earlyMasqueProbeAttempted &&
                        attemptElapsedMs >= earlyMasqueProbeDelayMs
                    ) {
                        earlyMasqueProbeAttempted = true
                        connectivityProbeAttempts++
                        connectivityProbeLastAtMs = SystemClock.elapsedRealtime()
                        val allowMasqueHttpProbeFallback =
                            transportMode.engine == "masque" &&
                                vpnValidated &&
                                tunnelStats.rxBytes > 0L
                        if (
                            hasTunnelConnectivity(
                                currentVpnNet,
                                1200,
                                allowHttpDnsFallback = allowMasqueHttpProbeFallback,
                            )
                        ) {
                            markSuccessfulTunnelProbe()
                            connectivityProbeSucceeded = true
                            connectivityProbeSuccessCount += 1
                            LogManager.log("Ранний MASQUE tunnel-probe прошёл для $modeLabel@$currentPort.")
                        } else {
                            LogManager.log("Ранний MASQUE tunnel-probe пока не прошёл для $modeLabel@$currentPort.")
                        }
                    }
                    // System VALIDATED alone is not a reliable success signal for MASQUE on
                    // all devices/networks: тестовое устройство can report VALIDATED while rxBytes stay 0
                    // and even direct pings through the tunnel still fail. Keep MASQUE honest
                    // and require real tunnel traffic/probe success instead of accepting
                    // provisional system validation.
                    val effectiveDataReady = dataReady || trustedMasqueTrafficReady
                    val validatedAgeMs = if (validatedAtMs > 0L) {
                        SystemClock.elapsedRealtime() - validatedAtMs
                    } else {
                        0L
                    }

                    val isPixel = android.os.Build.MANUFACTURER.equals("Google", ignoreCase = true)
                    val ordinaryWarpFastFail =
                        transportMode.engine != "masque" &&
                            !lastRestrictedMobileDetected
                    val provisionalValidatedGraceMs = when {
                        currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) &&
                            ordinaryWarpFastFail &&
                            isPixel ->
                            if (fastConnectMode) 3_400L else 4_200L
                        currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) &&
                            ordinaryWarpFastFail ->
                            if (fastConnectMode) 4_200L else 5_200L
                        currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) ->
                            if (fastConnectMode) 6_000L else 8_000L
                        trustedCoreVerifiedAttempt && ordinaryWarpFastFail && isPixel ->
                            if (fastConnectMode) 2_800L else 3_500L
                        trustedCoreVerifiedAttempt && ordinaryWarpFastFail ->
                            if (fastConnectMode) 3_200L else 4_200L
                        trustedCoreVerifiedAttempt ->
                            if (fastConnectMode) 5_000L else 6_500L
                        else ->
                            if (fastConnectMode) 3_500L else 4_500L
                    }
                    val provisionalValidatedWindowActive =
                        provisionalValidatedAtMs > 0L &&
                            currentVpnNet != null &&
                            vpnValidated &&
                            SystemClock.elapsedRealtime() - provisionalValidatedAtMs <= provisionalValidatedGraceMs
                    val provisionalValidatedReady =
                        transportMode.engine != "masque" &&
                            currentVpnNet != null &&
                            vpnValidated &&
                            trustedCoreVerifiedAttempt &&
                            !effectiveDataReady &&
                            tunnelStats.txBytes > 0L &&
                            (
                                provisionalValidatedWindowActive ||
                                    (
                                        provisionalValidatedAtMs == 0L &&
                                        attemptElapsedMs >= 1_600L &&
                                            (currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                                                currentAttempt.endpointSource.equals("verified-config", ignoreCase = true))
                                        )
                                )
                    var validatedWithoutTrafficTimeoutMs = when {
                        transportMode.engine == "masque" &&
                            currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) ->
                            if (fastConnectMode) 5_200L else 6_600L
                        transportMode.engine == "masque" &&
                            trustedCoreVerifiedAttempt ->
                            if (fastConnectMode) 4_800L else 6_100L
                        transportMode.engine == "masque" -> if (fastConnectMode) 2_400L else 3_000L
                        isExoticWarpPort(currentPort) && isChatAwareWarpMode(transportMode) ->
                            if (fastConnectMode) 1_350L else 2_000L
                        isExoticWarpPort(currentPort) ->
                            if (fastConnectMode) 1_500L else 2_200L
                        currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) &&
                            messengerPriorityCore -> if (fastConnectMode) 1_650L else 2_300L
                        (currentAttempt.endpointSource.equals("verified-config", ignoreCase = true) ||
                            currentAttempt.endpointSource.equals("last-success", ignoreCase = true)) &&
                            messengerPriorityCore -> if (fastConnectMode) 1_750L else 2_450L
                        isChatAwareWarpMode(transportMode) -> when {
                            currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) ->
                                if (fastConnectMode) 1_700L else 2_600L
                            currentAttempt.endpointSource.equals("verified-config", ignoreCase = true) ||
                                currentAttempt.endpointSource.equals("last-success", ignoreCase = true) ->
                                if (fastConnectMode) 1_900L else 2_800L
                            else -> if (fastConnectMode) 2_200L else 3_100L
                        }
                        messengerPriorityCore -> if (fastConnectMode) 1_950L else 2_700L
                        currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) &&
                            isCoreWarpPort(currentPort) -> if (fastConnectMode) 4_400L else 5_400L
                        (currentAttempt.endpointSource.equals("verified-config", ignoreCase = true) ||
                            currentAttempt.endpointSource.equals("last-success", ignoreCase = true) ||
                            currentAttempt.endpointSource.equals("bundled-seed", ignoreCase = true)) &&
                            isCoreWarpPort(currentPort) -> if (fastConnectMode) 3_900L else 4_900L
                        else -> if (fastConnectMode) 2_750L else 3_500L
                    }
                    if (transportMode.engine != "masque" && trustedCoreVerifiedAttempt) {
                        validatedWithoutTrafficTimeoutMs = maxOf(
                            validatedWithoutTrafficTimeoutMs,
                            provisionalValidatedGraceMs + if (fastConnectMode) 500L else 800L,
                        )
                    }
                    val validatedWithoutTraffic = currentVpnNet != null &&
                        vpnValidated &&
                        !effectiveDataReady &&
                        !connectivityProbeSucceeded &&
                        tunnelStats.rxBytes <= 0L &&
                        tunnelStats.txBytes in 1L..256L &&
                        attemptElapsedMs >= validatedWithoutTrafficTimeoutMs &&
                        (handshakeReady || tunnelStats.txBytes > 0L)
                    val deadMasqueNoTrafficTimeoutMs = when {
                        transportMode.engine == "masque" && trustedCoreVerifiedAttempt ->
                            if (fastConnectMode) 5_600L else 7_200L
                        transportMode.engine == "masque" ->
                            if (fastConnectMode) 4_000L else 5_200L
                        else ->
                            Long.MAX_VALUE
                    }
                    val deadMasqueNoTraffic = transportMode.engine == "masque" &&
                        !isConnected.get() &&
                        !effectiveDataReady &&
                        tunnelStats.rxBytes <= 0L &&
                        tunnelStats.txBytes <= 0L &&
                        attemptElapsedMs >= deadMasqueNoTrafficTimeoutMs
                    val deadMasqueControlPlaneOnlyTimeoutMs = when {
                        transportMode.engine != "masque" -> Long.MAX_VALUE
                        trustedCoreVerifiedAttempt &&
                            tunnelStats.rxBytes > 0L &&
                            currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) ->
                            if (fastConnectMode) 8_200L else 9_400L
                        trustedCoreVerifiedAttempt &&
                            tunnelStats.rxBytes > 0L ->
                            if (fastConnectMode) 7_200L else 8_400L
                        trustedCoreVerifiedAttempt &&
                            currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) ->
                            if (isPixel) {
                                if (fastConnectMode) 3_800L else 4_600L
                            } else {
                                if (fastConnectMode) 5_400L else 6_400L
                            }
                        trustedCoreVerifiedAttempt ->
                            if (isPixel) {
                                if (fastConnectMode) 3_500L else 4_200L
                            } else {
                                if (fastConnectMode) 5_000L else 6_000L
                            }
                        else -> 
                            if (isPixel) {
                                if (fastConnectMode) 3_100L else 3_900L
                            } else {
                                if (fastConnectMode) 4_400L else 5_600L
                            }
                    }
                    val deadMasqueControlPlaneOnly = transportMode.engine == "masque" &&
                        !isConnected.get() &&
                        !effectiveDataReady &&
                        currentVpnNet != null &&
                        vpnValidated &&
                        handshakeReady &&
                        connectivityProbeSuccessCount == 0 &&
                        tunnelStats.txBytes >= 8_000L &&
                        tunnelStats.rxBytes <= 2_048L &&
                        (
                            validatedAgeMs >= deadMasqueControlPlaneOnlyTimeoutMs ||
                                handshakeAgeMs >= deadMasqueControlPlaneOnlyTimeoutMs
                            )
                    val deadMasqueValidatedNoProbeTimeoutMs = when {
                        transportMode.engine != "masque" -> Long.MAX_VALUE
                        currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) ->
                            if (fastConnectMode) 7_200L else 8_400L
                        trustedCoreVerifiedAttempt ->
                            if (fastConnectMode) 6_600L else 7_800L
                        else ->
                            if (fastConnectMode) 5_800L else 6_800L
                    }
                    val deadMasqueValidatedNoProbe = transportMode.engine == "masque" &&
                        !isConnected.get() &&
                        !effectiveDataReady &&
                        currentVpnNet != null &&
                        vpnValidated &&
                        connectivityProbeSuccessCount == 0 &&
                        (
                            validatedAgeMs >= deadMasqueValidatedNoProbeTimeoutMs ||
                                handshakeAgeMs >= deadMasqueValidatedNoProbeTimeoutMs
                            )
                    val deadMasqueHardCapTimeoutMs = when {
                        transportMode.engine != "masque" -> Long.MAX_VALUE
                        lastRestrictedMobileDetected -> if (fastConnectMode) 13_500L else 15_000L
                        currentAttempt.endpointSource.equals("last-success-exact", ignoreCase = true) ->
                            if (fastConnectMode) 9_000L else 10_500L
                        trustedCoreVerifiedAttempt ->
                            if (fastConnectMode) 9_500L else 11_000L
                        else ->
                            if (fastConnectMode) 10_500L else 12_000L
                    }
                    val deadMasqueHardCap = transportMode.engine == "masque" &&
                        !isConnected.get() &&
                        !effectiveDataReady &&
                        (vpnValidated || handshakeReady) &&
                        attemptElapsedMs >= deadMasqueHardCapTimeoutMs
                    if (connectivityProbeSucceeded && !inboundReady && !probeOnlySignalLogged) {
                        probeOnlySignalLogged = true
                        LogManager.log(
                            "Tunnel-probe для $modeLabel@$currentPort прошёл, " +
                                "но inbound traffic через ядро туннеля не подтверждён; " +
                                "успех пока не засчитываем."
                        )
                    }
                    if (sustainedInbound && !connectivityProbeSucceeded && !effectiveDataReady && !inboundAwaitingProbeLogged) {
                        inboundAwaitingProbeLogged = true
                        LogManager.log(
                            "Для $modeLabel@$currentPort появился inbound traffic, " +
                                "но внешний tunnel-probe ещё не подтвердил полноценный интернет."
                        )
                    }

                    if (handshakeReady && !handshakeObserved.get()) {
                        handshakeObserved.set(true)
                        LogManager.log(
                            "$modeLabel ответил: port=$currentPort, rx=${tunnelStats.rxBytes}, tx=${tunnelStats.txBytes}"
                        )
                    }

                    val provisionalValidatedExpired =
                        provisionalValidatedAtMs > 0L &&
                            !effectiveDataReady &&
                            SystemClock.elapsedRealtime() - provisionalValidatedAtMs >= provisionalValidatedGraceMs

                    if (effectiveDataReady) {
                        markSuccessfulTunnelProbe()
                        provisionalValidatedAtMs = 0L
                        provisionalValidatedLogged = false
                        failures = 0
                        if (!successPersistedEarly) {
                            successPersistedEarly = true
                            if (persistPrimarySuccess) {
                                clientData.saveSuccessParams(
                                    currentPort,
                                    if (transportMode.engine == "masque") "MASQUE" else transportMode.name,
                                    currentHost,
                                    transportMode.name
                                )
                            }
                            clientData.saveWarpLastSuccessParams(
                                port = currentPort,
                                protocol = if (transportMode.engine == "masque") "MASQUE" else transportMode.name,
                                endpointHost = currentHost,
                                modeName = transportMode.name,
                            )
                            val canUpdateVerifiedConfig =
                                currentAttempt.endpointSource.equals("bundled-seed", ignoreCase = true) ||
                                    clientData.hasWarpVerifiedConfig(transportMode.name, currentHost, currentPort)
                            if (canUpdateVerifiedConfig) {
                                clientData.upsertWarpVerifiedConfig(
                                    engine = transportMode.engine,
                                    mode = transportMode.name,
                                    host = currentHost,
                                    port = currentPort,
                                    endpointSource = normalizeVerifiedConfigSource(currentAttempt.endpointSource),
                                    rawConfig = buildWarpConfigDescription(currentAttempt),
                                    manual = false,
                                )
                            }
                        }
                        if (!isConnected.get()) {
                            isConnected.set(true)
                            rememberActiveWarpQualityTarget(
                                attempt = currentAttempt,
                                strategyScope = verifiedConfigScope,
                            )
                            if (!continueAfterVerifiedSuccess) {
                                if (dataReady) {
                                    LogManager.log("Успешное подключение на порту $currentPort в режиме $modeLabel!")
                                } else if (trustedMasqueTrafficReady) {
                                    LogManager.log(
                                        "Успешное подключение на порту $currentPort в режиме $modeLabel " +
                                            "(доверились устойчивому двустороннему трафику для trusted MASQUE fast-path)."
                                    )
                                } else {
                                    LogManager.log(
                                        "Успешное подключение на порту $currentPort в режиме $modeLabel " +
                                            "(доверились системному VALIDATED для trusted MASQUE fast-path)."
                                    )
                                }
                                currentAttemptOrdinal = 0
                                currentAttemptTotal = 0
                                broadcastState(STATE_CONNECTED)
                            } else {
                                LogManager.log(
                                    "Конфигурация $modeLabel@$currentPort прошла первичную проверку, удерживаем туннель для верификации..."
                                )
                            }
                        }
                        if (!everConnected.get()) {
                            everConnected.set(true)
                            firstConnectedAt.compareAndSet(0L, SystemClock.elapsedRealtime())
                            if (qualitySamplingStarted.compareAndSet(false, true)) {
                                startWarpQualitySampling(
                                    clientData = clientData,
                                    attempt = currentAttempt,
                                    connectGenerationId = connectGenerationId,
                                    attemptActive = attemptActive,
                                    strategyScope = verifiedConfigScope,
                                    windowMs = qualitySamplingWindowMs,
                                    qualitySamplingFinished = qualitySamplingFinished,
                                    qualitySamplingHealthy = qualitySamplingHealthy,
                                    onHealthyConfirmed = {
                                        if (
                                            !continueAfterVerifiedSuccess &&
                                            currentBackendLabel.trim().uppercase(Locale.ROOT).startsWith(BACKEND_WARP) &&
                                            currentState == STATE_CONNECTED &&
                                            operaWarmupStarted.compareAndSet(false, true)
                                        ) {
                                            LogManager.log(
                                                "WARP quality подтверждён. Только теперь запускаем фоновую проверку Opera через текущий WARP."
                                            )
                                            maybePrewarmOperaEndpointsThroughCurrentWarp(clientData)
                                        }
                                    },
                                )
                            }
                            if (currentBackendLabel.trim().uppercase().startsWith(BACKEND_WARP)) {
                                val activeMaskHost = currentWarpMaskHost
                                    .orEmpty()
                                    .ifBlank { clientData.getWarpTrafficMaskActiveHost() }
                                    .ifBlank { clientData.getTrafficMaskActiveHost() }
                                val maskPoolHint = resolveWarpTrafficMaskPoolHint(clientData)
                                clientData.setTrafficMaskActiveHost(
                                    activeMaskHost,
                                    maskPoolHint,
                                )
                                clientData.setWarpTrafficMaskActiveHost(activeMaskHost)
                                clientData.recordTrafficMaskAttempt(
                                    activeMaskHost,
                                    success = true,
                                    poolHint = maskPoolHint,
                                )
                                clientData.recordWarpTrafficMaskAttempt(activeMaskHost, success = true)
                                // Имя в профиль больше не записывается. Именно эта строка
                                // и проставляла маску всем пятидесяти профилям за один
                                // прогон адаптации, после чего она подставлялась сама и
                                // переживала выключение маскировки.
                            }
                        }
                        if (
                            !stableSuccessPersisted &&
                            firstConnectedAt.get() > 0L &&
                            SystemClock.elapsedRealtime() - firstConnectedAt.get() >= STABLE_LAST_SUCCESS_HOLD_MS &&
                            qualitySamplingFinished.get() &&
                            qualitySamplingHealthy.get()
                        ) {
                            stableSuccessPersisted = true
                            clientData.saveStableLastSuccessParams(
                                port = currentPort,
                                protocol = if (transportMode.engine == "masque") "MASQUE" else transportMode.name,
                                endpointHost = currentHost,
                                modeName = transportMode.name,
                                underlyingSignature = null,
                                networkClass = null,
                            )
                            findMatchingWarpVerifiedConfigForAttempt(currentAttempt, clientData)?.let { stableConfig ->
                                clientData.promoteWarpVerifiedConfig(stableConfig.id)
                            }
                            LogManager.log(
                                "Конфигурация $modeLabel@$currentPort удержалась 20 секунд. " +
                                    "Поднимаем её как последнюю стабильную."
                            )
                        } else if (
                            !stableSuccessPersisted &&
                            !stablePromotionWaitLogged &&
                            firstConnectedAt.get() > 0L &&
                            SystemClock.elapsedRealtime() - firstConnectedAt.get() >= STABLE_LAST_SUCCESS_HOLD_MS
                        ) {
                            stablePromotionWaitLogged = true
                            val reason = when {
                                !qualitySamplingFinished.get() ->
                                    "quality-check ещё не завершён"
                                !qualitySamplingHealthy.get() ->
                                    "quality-check не подтвердил стабильный data-plane"
                                else ->
                                    "ожидаем подтверждение стабильности"
                            }
                            LogManager.log(
                                "Конфигурация $modeLabel@$currentPort прожила 20 секунд, но пока не поднимаем её как last-stable: $reason."
                            )
                        }
                        if (
                            continueAfterVerifiedSuccess &&
                            dataReady &&
                            !verifiedAttempt.get() &&
                            firstConnectedAt.get() > 0L &&
                            SystemClock.elapsedRealtime() - firstConnectedAt.get() >= verifiedSuccessHoldMs
                        ) {
                            verifiedAttempt.set(true)
                            rotateRequested.set(true)
                            LogManager.log("Конфигурация $modeLabel@$currentPort подтверждена, фиксируем и переходим к следующей.")
                            try {
                                Nova.stopVPN()
                            } catch (_: Exception) {
                            }
                            break
                        }
                    } else if (provisionalValidatedReady) {
                        if (provisionalValidatedAtMs == 0L) {
                            provisionalValidatedAtMs = SystemClock.elapsedRealtime()
                            provisionalValidatedLogged = false
                        }
                        requireFreshTunnelProbeUntilMs = maxOf(
                            requireFreshTunnelProbeUntilMs,
                            SystemClock.elapsedRealtime() + provisionalValidatedGraceMs,
                        )
                        failures = 0
                        if (!provisionalValidatedLogged) {
                            provisionalValidatedLogged = true
                            LogManager.log(
                                "$modeLabel@$currentPort получил системный VALIDATED на доверенном verified-path. " +
                                    "Оставляем попытку жить ещё ${provisionalValidatedGraceMs}мс и ждём подтверждения реального data-plane."
                            )
                        }
                    } else {
                        failures++
                        if (!isConnected.get()) {
                            val waitMessage = if (handshakeReady) {
                                "Handshake есть, но трафик через туннель пока не проходит " +
                                    "(rx=${tunnelStats.rxBytes}, tx=${tunnelStats.txBytes})"
                            } else {
                                "Ожидание туннеля... ($failures, rx=${tunnelStats.rxBytes}, tx=${tunnelStats.txBytes})"
                            }
                            LogManager.log(waitMessage)
                        }
                    }

                    val noInboundAfterHandshake = !isConnected.get() &&
                        handshakeReady &&
                        tunnelStats.txBytes > 0L &&
                        tunnelStats.rxBytes <= 0L &&
                        handshakeAgeMs >= noInboundAfterHandshakeTimeoutMs
                    val shouldRotate = if (deadMasqueNoTraffic) {
                        true
                    } else if (deadMasqueControlPlaneOnly) {
                        true
                    } else if (deadMasqueValidatedNoProbe) {
                        true
                    } else if (deadMasqueHardCap) {
                        true
                    } else if (provisionalValidatedExpired) {
                        true
                    } else if (isConnected.get()) {
                        failures >= 4
                    } else if (validatedWithoutTraffic) {
                        true
                    } else if (noInboundAfterHandshake) {
                        true
                    } else if (handshakeReady) {
                        attemptElapsedMs >= handshakeTimeoutMs
                    } else {
                        attemptElapsedMs >= noHandshakeTimeoutMs
                    }

                    if (shouldRotate) {
                        if (isConnected.get()) {
                            markActiveWarpQualityTargetDegraded(
                                clientData = clientData,
                                reason = attemptFailureReason.get().orEmpty().ifBlank { "runtime-rotate" },
                                probeCount = maxOf(4, failures + 2),
                                pingSuccesses = if (resourceConstrainedDevice) 1 else 2,
                                avgPingMs = if (resourceConstrainedDevice) 560.0 else 470.0,
                            )
                            clearActiveWarpQualityTarget()
                        }
                        rotateRequested.set(true)
                        if (validatedWithoutTraffic || noInboundAfterHandshake) {
                            quickRotateHint.set(true)
                        }
                        attemptFailureReason.compareAndSet(
                            null,
                            when {
                                deadMasqueControlPlaneOnly -> "control_plane_only"
                                deadMasqueValidatedNoProbe || provisionalValidatedExpired || validatedWithoutTraffic ->
                                    "validated_no_traffic"
                                noInboundAfterHandshake -> "no_inbound_after_handshake"
                                deadMasqueNoTraffic || deadMasqueHardCap -> "no_traffic"
                                handshakeReady -> "handshake_timeout"
                                else -> "no_traffic"
                            }
                        )
                        isConnected.set(false)
                        broadcastState(STATE_CONNECTING)
                        val rotateAgeMs = if (handshakeAgeMs > 0L) handshakeAgeMs else attemptElapsedMs
                        val rotateReason = when {
                            deadMasqueNoTraffic ->
                                "$modeLabel@$currentPort не дал ни одного байта трафика за ${attemptElapsedMs}мс. " +
                                    "Считаем ранний MASQUE fast-path мёртвым."
                            deadMasqueControlPlaneOnly ->
                                "$modeLabel@$currentPort застрял в control-plane: VALIDATED уже есть, " +
                                    "но route assignment/data-plane так и не пришли за ${attemptElapsedMs}мс " +
                                    "(rx=${tunnelStats.rxBytes}, tx=${tunnelStats.txBytes})."
                            deadMasqueValidatedNoProbe ->
                                "$modeLabel@$currentPort слишком долго держит системный VALIDATED, " +
                                    "но так и не подтвердил реальный tunnel-probe/data-plane за ${attemptElapsedMs}мс " +
                                    "(rx=${tunnelStats.rxBytes}, tx=${tunnelStats.txBytes})."
                            deadMasqueHardCap ->
                                "$modeLabel@$currentPort превысил жёсткий лимит ожидания ${deadMasqueHardCapTimeoutMs}мс " +
                                    "без реального data-plane/tunnel-probe " +
                                    "(rx=${tunnelStats.rxBytes}, tx=${tunnelStats.txBytes})."
                            provisionalValidatedExpired ->
                                "$modeLabel@$currentPort получил provisional VALIDATED, " +
                                    "но так и не подтвердил реальный data-plane за ${provisionalValidatedGraceMs}мс " +
                                    "(rx=${tunnelStats.rxBytes}, tx=${tunnelStats.txBytes})."
                            validatedWithoutTraffic ->
                                "$modeLabel@$currentPort быстро получил VALIDATED, " +
                                    "но data-plane так и не появился за ${rotateAgeMs}мс " +
                                    "(rx=${tunnelStats.rxBytes}, tx=${tunnelStats.txBytes})."
                            noInboundAfterHandshake ->
                                "Порт $currentPort в режиме $modeLabel даёт handshake, но inbound traffic не появился " +
                                    "за ${rotateAgeMs}мс (rx=${tunnelStats.rxBytes}, tx=${tunnelStats.txBytes})."
                            handshakeReady ->
                                "Порт $currentPort не даёт рабочего трафика в режиме $modeLabel."
                            else ->
                                "Порт $currentPort не отвечает в режиме $modeLabel."
                        }
                        LogManager.log("$rotateReason Переключаемся на следующий режим/порт...")
                        try {
                            Nova.stopVPN()
                        } catch (_: Exception) {
                        }
                        break
                    }
                    }
                } catch (t: Throwable) {
                    LogManager.log("Watchdog поток завершился ошибкой: ${t.message}")
                }
            }.apply {
                name = "NovaWatchdogThread"
                start()
            }

            val engineThread = Thread {
                var cleanMasqueAttempt = false
                try {
                    if (transportMode.engine == "masque") {
                        val identityJson = runtimeMasqueIdentityJson.orEmpty()
                        require(identityJson.isNotBlank()) { "MASQUE identity отсутствует" }
                        val trustedVerifiedMasqueFastPath =
                            !clientData.shouldForceMessengerWarpPriority() &&
                                currentAttempt.endpointSource.equals("verified-config", ignoreCase = true) &&
                                isTrustedMasqueFastPort(currentPort)
                        val currentMasqueMaskHost = currentWarpMaskHost
                            .orEmpty()
                            .ifBlank { clientData.getWarpTrafficMaskActiveHost() }
                            .trim()
                        val trustedVerifiedMasqueMaskedFastPath =
                            trustedVerifiedMasqueFastPath &&
                                lastRestrictedMobileDetected &&
                                currentMasqueMaskHost.isNotBlank()
                        val ordinaryWifiMasqueFastPath =
                            fastConnectMode &&
                                !lastRestrictedMobileDetected &&
                                !clientData.shouldForceMessengerWarpPriority()
                        val ordinaryWifiMasqueMaskedFastPath = false
                        val ordinaryWifiMasqueLegacyBurstFastPath = false
                        // Первая попытка цикла идёт без маскировки.
                        //
                        // Замер на этой сети: голая QUIC-проба к тому же адресу и порту
                        // получает Version Negotiation за 38 мс, а замаскированное
                        // рукопожатие Nova — ноль байт. То есть UDP доходит, а падает
                        // именно то, что мы наворачиваем сверху. Маскировка — это цена,
                        // и платить её вслепую, ни разу не проверив чистый путь, нельзя:
                        // на сети, где MASQUE прошёл бы как есть, она сама всё и ломала.
                        // Не вышло чисто — следующие попытки идут с маскировкой.
                        val firstMasqueAttemptOfCycle = attemptsCompleted == 0
                        // Чистое рукопожатие на этой сети уже проходило — fake-пачку дальше
                        // не шлём.
                        //
                        // Замеры на тестовом устройстве, 162.159.198.2:443: без пачки QUIC поднимался
                        // 4 раза из 4, с пачкой — падал по таймауту, получив ровно 11
                        // маленьких пакетов (десять Version Negotiation на наши десять
                        // подделок плюс ответ пробы) и ни байта на настоящий Initial.
                        // Маскировка нужна там, где режут сам QUIC; там, где не режут, она
                        // сама и есть помеха.
                        val masqueCleanPathProven = masqueQuicPassesClean.get()
                        val shouldKeepMasqueFastPathClean =
                            firstMasqueAttemptOfCycle ||
                                (ordinaryWifiMasqueFastPath && !ordinaryWifiMasqueMaskedFastPath) ||
                                (
                                    !ordinaryWifiMasqueLegacyBurstFastPath &&
                                        trustedVerifiedMasqueFastPath &&
                                        !trustedVerifiedMasqueMaskedFastPath
                                    )
                        val adaptiveMasqueCamouflageHost = when {
                            trustedVerifiedMasqueMaskedFastPath -> currentMasqueMaskHost
                            ordinaryWifiMasqueMaskedFastPath -> currentMasqueMaskHost
                            ordinaryWifiMasqueLegacyBurstFastPath -> ""
                            shouldKeepMasqueFastPathClean -> ""
                            else -> resolveAdaptiveCamouflageHost(
                                clientData = clientData,
                                seed = "masque-${currentHost}:${currentPort}-${transportMode.name}",
                                preferMessengerChatProfiles = clientData.shouldForceMessengerWarpPriority(),
                            )
                        }
                        val masqueFakeBurstEnabled = when {
                            masqueCleanPathProven -> false
                            trustedVerifiedMasqueMaskedFastPath -> true
                            ordinaryWifiMasqueMaskedFastPath -> true
                            ordinaryWifiMasqueLegacyBurstFastPath -> true
                            shouldKeepMasqueFastPathClean -> false
                            else -> adaptiveMasqueCamouflageHost.isNotBlank()
                        }
                        if (trustedVerifiedMasqueMaskedFastPath) {
                            LogManager.log(
                                "MASQUE camouflage host: $adaptiveMasqueCamouflageHost " +
                                    "(ранняя AUTO/custom маскировка для trusted fast path)"
                            )
                            LogManager.log("MASQUE fake QUIC burst: enabled for trusted fast path with traffic mask")
                        } else if (ordinaryWifiMasqueMaskedFastPath) {
                            LogManager.log(
                                "MASQUE fast path: ранняя AUTO-маскировка на обычном Wi‑Fi " +
                                    "($adaptiveMasqueCamouflageHost)"
                            )
                            LogManager.log("MASQUE fake QUIC burst: enabled for ordinary Wi-Fi masked fast path")
                        } else if (ordinaryWifiMasqueLegacyBurstFastPath) {
                            LogManager.log("MASQUE fast path: legacy fake QUIC burst on ordinary Wi-Fi")
                        } else if (firstMasqueAttemptOfCycle) {
                            LogManager.log(
                                "MASQUE: первую попытку цикла делаем чистым рукопожатием, без маскировки " +
                                    "и fake-пачки. Не выйдет — следующие попытки пойдут с маскировкой."
                            )
                        } else if (shouldKeepMasqueFastPathClean) {
                            LogManager.log("MASQUE trusted verified fast path: clean handshake on ordinary network")
                        } else if (trustedVerifiedMasqueFastPath && adaptiveMasqueCamouflageHost.isNotBlank()) {
                            LogManager.log("MASQUE camouflage host: $adaptiveMasqueCamouflageHost (trusted verified fast path)")
                            LogManager.log("MASQUE fake QUIC burst: enabled for trusted verified fast path")
                        } else if (adaptiveMasqueCamouflageHost.isNotBlank()) {
                            LogManager.log("MASQUE camouflage host: $adaptiveMasqueCamouflageHost")
                        } else if (trustedVerifiedMasqueFastPath && masqueFakeBurstEnabled) {
                            LogManager.log("MASQUE camouflage host: legacy-default for trusted verified fast path")
                            LogManager.log("MASQUE fake QUIC burst: enabled for trusted verified fast path")
                        }
                        if (masqueCleanPathProven && !firstMasqueAttemptOfCycle) {
                            LogManager.log(
                                "MASQUE: чистое рукопожатие на этой сети уже проходило — " +
                                    "fake-пачку не шлём, она здесь только мешает."
                            )
                        }
                        setTrafficCamouflageHostCompat(adaptiveMasqueCamouflageHost)
                        setMasqueFakeBurstEnabledCompat(masqueFakeBurstEnabled)
                        cleanMasqueAttempt = !masqueFakeBurstEnabled
                        Nova.startMasqueVPNWithSNI(
                            tunnelFdForEngine(attemptDescriptor),
                            identityJson,
                            currentHost,
                            currentPort.toLong(),
                            transportMode.masqueSni.orEmpty()
                        )
                        // Вернулись без исключения — значит, сессия жила, а рукопожатие
                        // прошло.
                        if (cleanMasqueAttempt) {
                            masqueQuicPassesClean.set(true)
                        }
                    } else {
                        val awgInterfaceExtras = resolveAwgInterfaceExtrasForAttempt(currentAttempt, clientData)
                        val finalConfig = buildWireGuardConfig(
                            transportMode = transportMode,
                            endpointHost = currentHost,
                            endpointPort = currentPort,
                            privateKey = wireGuardPrivateKey,
                            ipv4 = wireGuardIpv4,
                            ipv6 = wireGuardIpv6,
                            peerPub = wireGuardPeerPub,
                            reserved = wireGuardReserved,
                            clientData = clientData,
                            awgInterfaceExtras = awgInterfaceExtras,
                            attemptContext = currentAttempt,
                        )
                        Nova.startVPN(tunnelFdForEngine(attemptDescriptor), finalConfig)
                    }
                } catch (t: Throwable) {
                    attemptFailureReason.compareAndSet(null, "engine_crash")
                    if (transportMode.engine == "masque") {
                        val errorMessage = t.message.orEmpty()
                        // Отказ в доступе считаем приговором identity только со своего
                        // адреса.
                        //
                        // Дефект, ради которого это условие: перебор идёт и по адресам из
                        // скана, а они этому аккаунту не выдавались — «access denied» от
                        // них означает ровно то, что мы пришли не туда. Замер на тестовом устройстве:
                        // свежая identity подняла туннель на своём 162.159.198.2:443
                        // (CONNECT-IP за 29мс, трафик пошёл), а через полминуты сканированный
                        // 162.159.197.1 ответил отказом — и рабочий ключ был стёрт. Дальше
                        // MASQUE не стартовал вовсе: перевыпустить ключ на этой сети можно
                        // только через уже поднятый туннель.
                        if (
                            isMasqueRemoteAuthError(errorMessage) &&
                            isOwnMasqueIdentityEndpoint(currentAttempt.endpointSource)
                        ) {
                            masqueAuthFailureObserved = true
                            masqueLastAuthError = errorMessage
                        }
                        // Сорвалось после рукопожатия — значит, чистый QUIC до узла дошёл, и
                        // маскировать остальные попытки цикла незачем.
                        if (
                            cleanMasqueAttempt &&
                            !errorMessage.contains("failed to dial MASQUE QUIC", ignoreCase = true)
                        ) {
                            masqueQuicPassesClean.set(true)
                        }
                        // Молчание на CONNECT-IP со своего адреса — тот же отказ, только
                        // тихий.
                        //
                        // Громкий отказ (`tls: access denied`) приложение уже понимает и
                        // обновляет ключ. А тихий выглядел как «сеть капризничает»: до
                        // SETTINGS дошли, значит попали в нужную службу, — и она не
                        // ответила на запрос туннеля. Замер на тестовом устройстве: ключ в таком
                        // состоянии не оживает сам, весь круг проходит впустую, а
                        // приложение продолжает держаться за него до следующего громкого
                        // отказа. Свой адрес отличаем от сканированных: чужие узлы этому
                        // аккаунту не выдавались и молчат по другой причине.
                        if (
                            errorMessage.contains("CONNECT-IP", ignoreCase = true) &&
                            errorMessage.contains("не ответил", ignoreCase = true) &&
                            isOwnMasqueIdentityEndpoint(currentAttempt.endpointSource)
                        ) {
                            masqueAuthFailureObserved = true
                            masqueLastAuthError = errorMessage
                        }
                    }
                    LogManager.log("Ошибка движка: ${t.message}")
                }
            }.apply {
                name = if (transportMode.engine == "masque") "NovaMasqueThread" else "NovaEngineThread"
                start()
            }
            novaEngineThread = engineThread
            novaCoreTunnelActive = true

            while (engineThread.isAlive && !isUserStopped && isConnectGenerationCurrent(connectGenerationId) && !rotateRequested.get()) {
                if (!watchdogThread.isAlive) {
                    break
                }
                Thread.sleep(250)
            }

            watchdogActive.set(false)
            if (watchdogThread.isAlive) {
                watchdogThread.join(1500)
            }

            if (engineThread.isAlive && !isUserStopped) {
                val stopJoinTimeoutMs = when {
                    fastScanMode -> 700L
                    rotateRequested.get() -> 2_000L
                    else -> 4_000L
                }
                stopNovaCoreEngine(stopJoinTimeoutMs)
            } else if (novaEngineThread === engineThread) {
                novaEngineThread = null
                novaCoreTunnelActive = false
            }

            val keepDescriptorForVerifiedScan =
                continueAfterVerifiedSuccess &&
                    !fastScanMode &&
                    !descriptorIdentityChanged &&
                    descriptorFactory != null
            if (!keepDescriptorForVerifiedScan && (fastScanMode || (requiresFreshDescriptor && descriptorFactory != null))) {
                closeAttemptInterface(attemptDescriptor)
                if (reusableDescriptor === attemptDescriptor) {
                    reusableDescriptor = null
                }
            }
            attemptActive.set(false)

            val attemptDurationMs = (SystemClock.elapsedRealtime() - attemptStartedAt).coerceAtLeast(0L)
            val connectStartedAt = firstConnectedAt.get()
            val stableDurationMs = if (connectStartedAt > 0L) {
                (SystemClock.elapsedRealtime() - connectStartedAt).coerceAtLeast(0L)
            } else {
                0L
            }
            if ((isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) && !everConnected.get()) {
                skipStrategyLearning.set(true)
            }
            val outcome = when {
                verifiedAttempt.get() ->
                    AttemptOutcome.SUCCESS
                everConnected.get() && (isUserStopped || !rotateRequested.get() || stableDurationMs >= 15_000L) ->
                    AttemptOutcome.SUCCESS
                everConnected.get() ->
                    AttemptOutcome.UNSTABLE
                handshakeObserved.get() ->
                    AttemptOutcome.HANDSHAKE
                else ->
                    AttemptOutcome.FAILURE
            }
            val activeMaskHostForAttempt = currentWarpMaskHost
                .orEmpty()
                .ifBlank { clientData.getWarpTrafficMaskActiveHost() }
            if (
                (trafficMaskHosts.isNotEmpty() || normalizeRuntimeTrafficMaskHost(currentAttempt.preferredSni).isNotBlank()) &&
                transportMode.engine != "masque" &&
                activeMaskHostForAttempt.isNotBlank() &&
                outcome != AttemptOutcome.SUCCESS
            ) {
                clientData.recordTrafficMaskAttempt(
                    activeMaskHostForAttempt,
                    success = false,
                    poolHint = resolveWarpTrafficMaskPoolHint(clientData),
                )
            }
            val normalizedFailureReason = when {
                outcome == AttemptOutcome.SUCCESS -> null
                else -> attemptFailureReason.get()
                    ?.trim()
                    ?.lowercase()
                    ?.ifBlank { null }
                    ?: when (outcome) {
                        AttemptOutcome.UNSTABLE -> "handshake_timeout"
                        AttemptOutcome.HANDSHAKE -> "handshake_timeout"
                        else -> "no_traffic"
                    }
            }
            if (
                normalizedFailureReason in setOf("validated_no_traffic", "no_inbound_after_handshake", "handshake_timeout") &&
                transportMode.engine != "masque"
            ) {
                val failedPreferredSni = normalizeRuntimeTrafficMaskHost(currentAttempt.preferredSni)
                val cooldownMs = if (failedPreferredSni.isNotBlank()) 8L * 60L * 1000L else 3L * 60L * 1000L
                clientData.markWarpAttemptCooldown(
                    engine = transportMode.engine,
                    mode = transportMode.name,
                    host = currentHost,
                    port = currentPort,
                    preferredSni = null,
                    cooldownMs = 3L * 60L * 1000L,
                )
                clientData.markWarpAttemptCooldown(
                    engine = transportMode.engine,
                    mode = transportMode.name,
                    host = currentHost,
                    port = currentPort,
                    preferredSni = failedPreferredSni,
                    cooldownMs = cooldownMs,
                )
                LogManager.log(
                    "Ставим cooldown на $modeLabel@$currentPort${
                        failedPreferredSni.takeIf { it.isNotBlank() }?.let { " через $it" }.orEmpty()
                    } после $normalizedFailureReason."
                )
                if (failedPreferredSni.isNotBlank()) {
                    clientData.clearWarpVerifiedPreferredSni(
                        engine = transportMode.engine,
                        mode = transportMode.name,
                        host = currentHost,
                        port = currentPort,
                        scope = verifiedConfigScope,
                    )
                    LogManager.log(
                        "Сбрасываем сохранённый preferred SNI '$failedPreferredSni' для $modeLabel@$currentPort: он привёл к $normalizedFailureReason."
                    )
                }
            }
            if (skipStrategyLearning.get()) {
                LogManager.log(
                    "Статистику для $modeLabel@$currentPort не обновляем: попытка была прервана внешним событием " +
                        "(user-stop/new-connect-session)."
                )
            } else {
                if (normalizedFailureReason != null) {
                    LogManager.log(
                        "Диагностика WARP попытки: $attemptLabel outcome=$outcome, " +
                            "reason=$normalizedFailureReason, network=${strategyNetworkClass ?: "generic"}."
                    )
                }
                clientData.recordStrategyOutcome(
                    engine = transportMode.engine,
                    mode = transportMode.name,
                    host = currentHost,
                    port = currentPort,
                    outcome = outcome,
                    connectDurationMs = attemptDurationMs,
                    stableDurationMs = stableDurationMs,
                    strategyScope = strategyLearningScope,
                    networkClass = strategyNetworkClass,
                    failureReason = normalizedFailureReason,
                )
                clientData.recordWarpVerifiedRuntimeOutcome(
                    engine = transportMode.engine,
                    mode = transportMode.name,
                    host = currentHost,
                    port = currentPort,
                    success = outcome == AttemptOutcome.SUCCESS,
                    endpointSource = currentAttempt.endpointSource,
                    rawConfig = if (outcome == AttemptOutcome.SUCCESS) {
                        buildWarpConfigDescription(currentAttempt)
                    } else {
                        null
                    },
                    scope = verifiedConfigScope,
                )
            }
            onAttemptResult?.invoke(currentAttempt, outcome, attemptDurationMs, stableDurationMs)

            if (transportMode.engine == "masque") {
                unstableMasqueStreak = when (outcome) {
                    AttemptOutcome.UNSTABLE, AttemptOutcome.HANDSHAKE -> unstableMasqueStreak + 1
                    AttemptOutcome.SUCCESS -> 0
                    else -> unstableMasqueStreak
                }
                val protectedMasqueFastPathExhausted =
                    protectedMasqueFastPathKeys.isEmpty() ||
                        protectedMasqueFastPathKeys.all { it in triedMasqueFastPathKeys }
                val shouldForceLateOrdinaryWifiMasqueRefresh =
                    !refreshedMasqueIdentityThisRun &&
                        unstableMasqueStreak >= 3 &&
                        outcome in setOf(AttemptOutcome.UNSTABLE, AttemptOutcome.HANDSHAKE) &&
                        !allowMasqueIdentityRefreshInRun &&
                        protectedMasqueFastPathExhausted
                if (
                    !refreshedMasqueIdentityThisRun &&
                    (
                        (
                            unstableMasqueStreak >= 2 &&
                                outcome in setOf(AttemptOutcome.UNSTABLE, AttemptOutcome.HANDSHAKE) &&
                                allowMasqueIdentityRefreshInRun
                            ) ||
                            shouldForceLateOrdinaryWifiMasqueRefresh
                        )
                ) {
                    LogManager.log(
                        if (shouldForceLateOrdinaryWifiMasqueRefresh) {
                            "Ранний MASQUE verified-shortlist на обычном Wi‑Fi исчерпан без data-plane. " +
                                "Один раз принудительно обновляем cached MASQUE identity и заново даём шанс быстрому MASQUE."
                        } else {
                            "MASQUE несколько раз подряд дал control-plane без data-plane. " +
                                "Сбрасываем cached MASQUE identity и пробуем refresh."
                        }
                    )
                    // Прежний identity стираем только на время обновления и возвращаем,
                    // если новый не пришёл.
                    //
                    // Дефект, ради которого это сделано: обновление здесь спекулятивное —
                    // мы всего лишь подозреваем, что identity протух. А получить новый
                    // можно не везде: регистрация идёт через api.cloudflareclient.com,
                    // который на этой сети режется по SNI, и через Opera-прокси, который
                    // поднимается не всегда. На тестовое устройство так и вышло: рабочий identity
                    // стёрли, новый не получили, и MASQUE перестал стартовать вовсе —
                    // «идентификатор устройства не получен, останавливаем цикл».
                    val previousMasqueConfigJson = clientData.getMasqueConfigJson().orEmpty()
                    clientData.saveMasqueConfigJson(null)
                    val refreshedIdentity = prepareMasqueIdentity(
                        clientData = clientData,
                        fastRefresh = shouldForceLateOrdinaryWifiMasqueRefresh,
                        connectGenerationId = connectGenerationId,
                        trackConnectProgress = true,
                    )
                    val refreshedJson = clientData.getMasqueConfigJson().orEmpty()
                    if (refreshedIdentity != null && refreshedJson.isNotBlank()) {
                        runtimeMasqueIdentityJson = refreshedJson
                        onMasqueIdentityRefreshed?.invoke(refreshedIdentity)
                        refreshedMasqueIdentityThisRun = true
                        unstableMasqueStreak = 0
                        closeAttemptInterface(reusableDescriptor)
                        reusableDescriptor = null
                        if (shouldForceLateOrdinaryWifiMasqueRefresh) {
                            attemptsCompleted = 0
                            trustedExoticMasqueTried = false
                        }
                        if (shouldForceLateOrdinaryWifiMasqueRefresh) {
                            triedMasqueFastPathKeys.clear()
                            attemptIndex = 0
                            LogManager.log("MASQUE identity обновлена. Перезапускаем ранний MASQUE shortlist уже с новой identity.")
                            Thread.sleep(320)
                            continue
                        } else {
                            LogManager.log("MASQUE identity обновлена. Продолжаем ранний replay уже с новой identity.")
                        }
                    } else {
                        refreshedMasqueIdentityThisRun = true
                        if (previousMasqueConfigJson.isNotBlank()) {
                            clientData.saveMasqueConfigJson(previousMasqueConfigJson)
                            runtimeMasqueIdentityJson = previousMasqueConfigJson
                            LogManager.log(
                                "Обновить MASQUE identity не удалось — возвращаем прежний. " +
                                    "Он хотя бы позволяет пробовать подключение, а без него " +
                                    "MASQUE не стартует вовсе."
                            )
                        } else {
                            LogManager.log("Обновить MASQUE identity не удалось. Продолжаем без refresh.")
                        }
                        if (shouldForceLateOrdinaryWifiMasqueRefresh) {
                            LogManager.log("Fast refresh MASQUE identity не удался. Без задержки переходим к WireGuard/AWG fallback.")
                            break
                        }
                    }
                }
                if (
                    unstableMasqueStreak >= 3 &&
                    (!trustedExoticMasqueAvailable || trustedExoticMasqueTried) &&
                    protectedMasqueFastPathExhausted
                ) {
                    LogManager.log("MASQUE несколько раз подряд даёт handshake без рабочего трафика. Переходим к WireGuard/AWG fallback.")
                    break
                } else if (
                    unstableMasqueStreak >= 3 &&
                    (
                        (trustedExoticMasqueAvailable && !trustedExoticMasqueTried) ||
                            !protectedMasqueFastPathExhausted
                        )
                ) {
                    LogManager.log(
                        "MASQUE уже несколько раз дал control-plane без data-plane, " +
                            "но ранний доверенный verified-shortlist ещё не исчерпан. " +
                            "Даём шанс оставшимся быстрым MASQUE-кандидатам."
                    )
                }
            } else {
                unstableMasqueStreak = 0
            }

            if (engineThread.isAlive) {
                val safeToSkipStuckAttempt =
                    fastScanMode ||
                        continueAfterVerifiedSuccess ||
                        quickRotateHint.get() ||
                        rotateRequested.get() ||
                        currentState != STATE_CONNECTED ||
                        !everConnected.get()
                if (safeToSkipStuckAttempt) {
                    LogManager.log(
                        "Движок не завершился после stop. " +
                            "Принудительно пропускаем зависшую конфигурацию и идём дальше."
                    )
                    stopNovaCoreEngine(joinTimeoutMs = if (fastScanMode) 600L else 1_200L)
                    closeAttemptInterface(attemptDescriptor)
                    if (reusableDescriptor === attemptDescriptor) {
                        reusableDescriptor = null
                    }
                    if (interfaceDescriptor === attemptDescriptor) {
                        interfaceDescriptor = null
                    }
                    if (externalStopRequested?.invoke() == true || !isConnectGenerationCurrent(connectGenerationId)) {
                        LogManager.log("Остановка адаптации подтверждена после остановки движка; новые TUN не создаём.")
                        break
                    }
                    attemptsCompleted++
                    attemptIndex = resolveNextAttemptIndex(
                        connectionAttempts = connectionAttempts,
                        currentIndex = attemptIndex,
                        currentAttempt = currentAttempt,
                        quickRotate = quickRotateHint.get(),
                    )
                    Thread.sleep(400)
                    continue
                } else {
                    LogManager.log("Движок не завершился после stop. Перезапуск режима остановлен.")
                    break
                }
            }

            if (isUserStopped || externalStopRequested?.invoke() == true) break

            attemptsCompleted++
            attemptIndex = resolveNextAttemptIndex(
                connectionAttempts = connectionAttempts,
                currentIndex = attemptIndex,
                currentAttempt = currentAttempt,
                quickRotate = quickRotateHint.get(),
            )
            if (!rotateRequested.get()) {
                LogManager.log("Движок завершился. Переходим к следующему режиму/порту...")
            }
            val nextAttemptPauseMs = when {
                fastScanMode -> 200L
                quickRotateHint.get() && transportMode.engine == "masque" -> 320L
                quickRotateHint.get() -> 220L
                attemptDurationMs <= 2_500L -> 320L
                else -> 520L
            }
            Thread.sleep(nextAttemptPauseMs)
        }
        if (currentState != STATE_CONNECTING || isUserStopped || !isConnectGenerationCurrent(connectGenerationId)) {
            currentAttemptOrdinal = 0
            currentAttemptTotal = 0
        }
    }

    override fun onDestroy() {
        releaseRecoveryWakeLock()
        releaseConnectedScreenOffWakeLock()
        val clientData = ClientData(this)
        val pendingWarpBootstrapRestart = clientData.consumePendingWarpBootstrapRestart()
        val persistedState = clientData.getServiceState()
        val recentTaskRemovalDuringConnect =
            wasRecentTaskRemoval() &&
                (
                    currentState == STATE_CONNECTING ||
                        persistedState == STATE_CONNECTING
                    )
        val shouldRestore = !explicitStopRequested &&
            !suppressSessionRestore &&
            !recentTaskRemovalDuringConnect &&
            clientData.getRestartSession() != null &&
            (
                currentState == STATE_CONNECTED ||
                    currentState == STATE_CONNECTING ||
                    persistedState == STATE_CONNECTED ||
                    persistedState == STATE_CONNECTING
                )
        stopNovaCoreEngine(joinTimeoutMs = 1000L)
        stopOperaFallback(joinTimeoutMs = 1000L, stopProxyManager = true)
        LocalDnsProxyManager.stop(LogManager::log)
        LocalAppProxyManager.stop(this, LogManager::log)
        closeActiveInterface()
        if (explicitStopRequested) {
            LogManager.log("VPN-стек уже сброшен в явной stop-команде, повторный detach пропускаем.")
        }
        finishForegroundShutdown()
        unregisterUnderlyingNetworkObserver()
        vpnConsistencyHandler.removeCallbacks(vpnConsistencyRunnable)
        isRunning = false
        currentState = STATE_STOPPED
        LogManager.log("NovaVpnService уничтожен.")
        try { unregisterReceiver(stopReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(deviceWakeReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(tetherStateReceiver) } catch (_: Exception) {}
        stoppedStateStaleDetachInProgress.set(false)
        cleanupInProgress.set(false)
        explicitStopRequested = false
        suppressSessionRestore = false
        super.onDestroy()
        if (pendingWarpBootstrapRestart != null) {
            schedulePendingWarpBootstrapStart(pendingWarpBootstrapRestart)
            return
        }
        if (recentTaskRemovalDuringConnect) {
            LogManager.log("NovaVpnService уничтожен после закрытия задачи пользователем во время подключения. Автовосстановление подавлено.")
        }
        if (shouldRestore) {
            try {
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, NovaVpnService::class.java).apply {
                        action = ACTION_RESTORE_LAST_SESSION
                        putExtra(EXTRA_IGNORE_AUTO_RECONNECT_ON_RESTORE, true)
                        putExtra(EXTRA_FORCE_RESTART_ON_RESTORE, true)
                    }
                )
            } catch (_: Exception) {
            }
        }
    }

    override fun onRevoke() {
        LogManager.log("Система отозвала VPN-сеанс Nova. Считаем это внешним отключением и полностью останавливаем VPN.")
        cleanupAndStop(
            preserveRestartSession = false,
            unexpectedDisconnect = false,
            manualStopRequested = true,
        )
        super.onRevoke()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val clientData = ClientData(this)
        lastTaskRemovedAtMs = SystemClock.elapsedRealtime()
        val persistedState = clientData.getServiceState()
        val hasRestartSession = clientData.getRestartSession() != null
        val isConnectedState =
            currentState == STATE_CONNECTED || persistedState == STATE_CONNECTED
        val isConnectingState =
            currentState == STATE_CONNECTING || persistedState == STATE_CONNECTING
        when {
            isConnectedState -> {
                LogManager.log("Пользователь закрыл задачу Nova из недавних. Живой VPN оставляем работать без принудительного restore.")
            }
            !explicitStopRequested &&
                hasRestartSession &&
                (isConnectingState || clientData.getAutoReconnect()) -> {
                LogManager.log("Пользователь закрыл задачу Nova во время подключения. Останавливаем фоновые попытки и очищаем restart session.")
                cleanupAndStop(
                    preserveRestartSession = false,
                    unexpectedDisconnect = false,
                    forceServiceTeardown = true,
                    manualStopRequested = true,
                )
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun wasRecentTaskRemoval(windowMs: Long = 8_000L): Boolean {
        val recordedAt = lastTaskRemovedAtMs
        if (recordedAt <= 0L) return false
        return (SystemClock.elapsedRealtime() - recordedAt) in 0..windowMs
    }

    private fun reconcileSystemVpnConsistency() {
        if (cleanupInProgress.get() || warpConfigDiscoveryRunning.get()) return
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val currentVpn = findCurrentVpnNetwork(connectivityManager)
        val selectedUnderlying = selectUnderlyingNetwork(connectivityManager)
        val currentVpnIsNova = isLikelyNovaVpnNetwork(connectivityManager, currentVpn)
        if (currentState == STATE_STOPPED) {
            connectedHealthProbeFailures = 0
            resetConnectedWarpHealthWindow()
            if (!currentVpnIsNova) {
                return
            }
            val clientData = ClientData(this)
            if (clientData.getAutoReconnect() && clientData.getRestartSession() != null && !isUserStopped && !suppressSessionRestore) {
                LogManager.log("Watchdog обнаружил активный системный VPN Nova при состоянии STOPPED. Пытаемся восстановить сеанс.")
                broadcastState(STATE_CONNECTING)
                restorePersistedSession()
            } else {
                val now = SystemClock.elapsedRealtime()
                if (stoppedStateStaleDetachInProgress.get()) {
                    return
                }
                if (now - lastStoppedStateStaleDetachAtMs < 10_000L) {
                    return
                }
                if (now - lastStoppedStateCleanupAtMs < 45_000L) {
                    return
                }
                if (!stoppedStateStaleDetachInProgress.compareAndSet(false, true)) {
                    return
                }
                lastStoppedStateStaleDetachAtMs = now
                lastStoppedStateCleanupAtMs = now
                LogManager.log(
                    "Watchdog обнаружил stale системный VPN Nova при состоянии STOPPED. " +
                        "Запускаем cleanup с delayed detach, чтобы убрать зависший ключ без мгновенного Wi‑Fi flicker."
                )
                cleanupAndStop(
                    forceServiceTeardown = true,
                    allowSyntheticDetach = false,
                    manualStopRequested = true,
                )
            }
            return
        }
        if (currentState != STATE_CONNECTED) {
            connectedHealthProbeFailures = 0
            resetConnectedWarpHealthWindow()
            return
        }
        if (!isRunning || isUserStopped || explicitStopRequested || suppressSessionRestore) {
            return
        }
        val clientData = ClientData(this)
        val importedExactAwgActive = currentConnectionUsesImportedExactAwg()
        val warpBackend =
            currentBackendLabel.trim().uppercase(Locale.ROOT).startsWith(BACKEND_WARP) &&
                !importedExactAwgActive
        val probeTimeout = if (isOperaBackendLabel(currentBackendLabel)) 1400 else 1600
        // См. пояснение в evaluateNetworkRecoveryNeed: пока взведено требование свежей
        // пробы, накопленные доказательства относятся к прежней сети и ничего не говорят
        // о работоспособности туннеля сейчас.
        val importedExactAwgProof =
            if (
                importedExactAwgActive &&
                !requiresFreshTunnelProbeNow() &&
                currentVpnIsNova &&
                currentVpn != null
            ) {
                observeImportedExactAwgTrafficProof()
            } else {
                false
            }
        val probeSucceeded = currentVpnIsNova &&
            currentVpn != null &&
            if (importedExactAwgActive) {
                importedExactAwgProof ||
                    hasTunnelConnectivity(currentVpn, probeTimeout, allowHttpDnsFallback = true)
            } else if (warpBackend) {
                measureWarpQualityLatency(currentVpn, timeoutMs = probeTimeout) >= 0 ||
                    (!requiresFreshTunnelProbeNow() && hasRecentConfirmedWarpExitSnapshot(clientData))
            } else {
                hasTunnelConnectivity(currentVpn, probeTimeout, allowHttpDnsFallback = true)
            }
        if (probeSucceeded) {
            if (importedExactAwgActive) {
                noteImportedExactAwgTrafficProof()
            }
            markSuccessfulTunnelProbe()
            connectedHealthProbeFailures = 0
            if (warpBackend) {
                recordConnectedWarpHealthProbe(success = true)
            } else {
                resetConnectedWarpHealthWindow()
            }
            return
        }
        val warpHealthAfterFailure =
            if (warpBackend &&
                currentVpnIsNova &&
                currentVpn != null
            ) {
                recordConnectedWarpHealthProbe(success = false)
            } else {
                resetConnectedWarpHealthWindow()
                null
            }
        if (
            warpHealthAfterFailure != null &&
            maybeRecoverDegradedConnectedWarpWindow(
                clientData = clientData,
                snapshot = warpHealthAfterFailure,
                reason = "intermittent tunnel-probe loss",
            )
        ) {
            return
        }
        if (
            importedExactAwgActive &&
            !requiresFreshTunnelProbeNow() &&
            hasRecentImportedExactAwgTrafficProof()
        ) {
            connectedHealthProbeFailures = 0
            return
        }
        if (hasStableRecentTunnelProofForUnderlying(connectivityManager, selectedUnderlying)) {
            connectedHealthProbeFailures = 0
            return
        }
        if (shouldHonorFreshConnectGraceForUnderlying(connectivityManager, selectedUnderlying)) {
            logBenignHealthSkip("VPN только что подключился. Даём сети стабилизироваться перед health-reconnect.")
            return
        }
        if (
            !requiresFreshTunnelProbeNow() &&
            hasRecentSuccessfulTunnelProbeForUnderlying(connectivityManager, selectedUnderlying)
        ) {
            connectedHealthProbeFailures = 0
            return
        }
        var health = inspectVpnHealth(connectivityManager, currentVpn)
        if (
            !requiresFreshTunnelProbeNow() &&
            health.reason == "VPN потерял underlying networks" &&
            health.selectedUnderlying != null &&
            hasRecentSuccessfulTunnelProbeForUnderlying(
                connectivityManager,
                health.selectedUnderlying,
            )
        ) {
            LogManager.log(
                "Подложная сеть по-прежнему есть, а недавний tunnel-probe уже подтверждал живой VPN. " +
                    "Игнорируем transient потерю underlying networks."
            )
            connectedHealthProbeFailures = 0
            return
        }
        if (currentVpnIsNova && currentVpn != null) {
            connectedHealthProbeFailures += 1
            val requiredFailures = healthReconnectFailureThreshold(health.reason)
            if (connectedHealthProbeFailures < requiredFailures) {
                scheduleNetworkRecoveryCheck("health-followup")
                LogManager.log(
                    "Периодический tunnel-probe для активного VPN не прошёл. " +
                        "Ждём повторного подтверждения перед реконнектом " +
                        "($connectedHealthProbeFailures/$requiredFailures)."
                )
                return
            }
            health = health.copy(isHealthy = false, reason = "tunnel-probe не проходит")
        } else if (health.isHealthy) {
            connectedHealthProbeFailures = 0
        }
        if (!currentVpnIsNova || !health.isHealthy) {
            if (clientData.getAutoReconnect() && !isUserStopped && !suppressSessionRestore) {
                evaluateNetworkRecoveryNeed("watchdog")
            } else if (currentVpnIsNova) {
                LogManager.log(
                    "Watchdog обнаружил stale системный VPN Nova без рабочей подложной сети. " +
                        "Авто-реконнект выключен, полностью снимаем VPN-стек и разрешаем delayed detach при зависшем ключе."
                )
                cleanupAndStop(
                    allowSyntheticDetach = false,
                    manualStopRequested = true,
                )
            }
        }
    }

    private fun registerUnderlyingNetworkObserver() {
        if (underlyingNetworkCallback != null) return
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val request = android.net.NetworkRequest.Builder()
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        underlyingNetworkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                handleUnderlyingNetworkChange(network)
            }

            override fun onCapabilitiesChanged(
                network: android.net.Network,
                networkCapabilities: android.net.NetworkCapabilities,
            ) {
                handleUnderlyingNetworkChange(network, networkCapabilities)
            }

            override fun onLost(network: android.net.Network) {
                handleUnderlyingNetworkChange(network, lost = true)
            }
        }
        try {
            connectivityManager.registerNetworkCallback(request, underlyingNetworkCallback!!)
        } catch (_: Exception) {
        }
    }

    private fun unregisterUnderlyingNetworkObserver() {
        val callback = underlyingNetworkCallback ?: return
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        underlyingNetworkCallback = null
    }

    private fun handleUnderlyingNetworkChange(
        network: android.net.Network? = null,
        networkCapabilities: android.net.NetworkCapabilities? = null,
        lost: Boolean = false,
    ) {
        if (!isRunning || isUserStopped || explicitStopRequested || cleanupInProgress.get() || suppressSessionRestore || warpConfigDiscoveryRunning.get()) return
        if (currentState != STATE_CONNECTED && currentState != STATE_CONNECTING) return
        val clientData = ClientData(this)
        if (currentState == STATE_CONNECTED && clientData.isLocalProxyEnabled()) {
            syncLocalAppProxy(reason = "underlying-change")
        }
        if (currentState == STATE_CONNECTED && !clientData.getAutoReconnect()) return

        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val triggerCaps = networkCapabilities ?: network?.let { connectivityManager.getNetworkCapabilities(it) }
        if (triggerCaps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true) {
            return
        }
        val selected = selectUnderlyingNetwork(connectivityManager)
        val selectedId = selected?.toString()
        val selectedSignature = buildUnderlyingNetworkSignature(connectivityManager, selected)
        val previousId = observedUnderlyingNetworkId
        val previousSignature = observedUnderlyingNetworkSignature
        val previousNetwork = previousId?.let { findUnderlyingNetworkById(connectivityManager, it) }
        val previousStillUsable = previousNetwork?.let { isUsableUnderlyingNetwork(connectivityManager, it) } == true
        val previousWasWifi =
            previousNetwork?.let {
                connectivityManager.getNetworkCapabilities(it)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            } == true
        val selectedIsWifi =
            selected?.let {
                connectivityManager.getNetworkCapabilities(it)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            } == true
        val selectedIsCellular =
            selected?.let {
                connectivityManager.getNetworkCapabilities(it)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            } == true
        val definiteTransportMigration =
            !previousId.isNullOrBlank() &&
                !selectedId.isNullOrBlank() &&
                selectedId != previousId &&
                (
                    (previousWasWifi && selectedIsCellular && !selectedIsWifi && !previousStillUsable) ||
                        (observedUnderlyingUnavailable && !selectedIsWifi)
                    )
        val now = SystemClock.elapsedRealtime()
        if (
            currentState == STATE_CONNECTED &&
            now <= ignoreUnderlyingWakeEventsUntilMs &&
            !definiteTransportMigration
        ) {
            val triggerId = network?.toString()
            if (!selectedId.isNullOrBlank()) {
                observedUnderlyingNetworkId = selectedId
                observedUnderlyingNetworkSignature = selectedSignature
            }
            LogManager.log(
                "Сразу после пробуждения игнорируем transient событие подложной сети " +
                    "($triggerId -> ${selectedId ?: "<none>"}), пока Wi‑Fi/VPN стабилизируются."
            )
            scheduleNetworkRecoveryCheck("wake-followup")
            return
        }
        if (previousId == null && !selectedId.isNullOrBlank()) {
            observedUnderlyingNetworkId = selectedId
            observedUnderlyingNetworkSignature = selectedSignature
            observedUnderlyingUnavailable = false
            return
        }
        if (previousId == selectedId) {
            if (!selectedId.isNullOrBlank() && observedUnderlyingUnavailable) {
                observedUnderlyingNetworkId = selectedId
                observedUnderlyingNetworkSignature = selectedSignature
                observedUnderlyingUnavailable = false
                if (currentState == STATE_CONNECTING) {
                    LogManager.log(
                        "Подложная сеть вернулась на том же system handle ($selectedId) после полной потери. Возобновляем connect-cycle."
                    )
                    scheduleNetworkRecoveryCheck("underlying-change-connecting")
                } else {
                    LogManager.log(
                        "Подложная сеть вернулась на том же system handle ($selectedId) после полной потери. " +
                            "Сразу запускаем background recovery VPN."
                    )
                    triggerReconnectForNetworkChange(clientData)
                }
            }
            return
        }

        if (
            !previousId.isNullOrBlank() &&
            !selectedId.isNullOrBlank() &&
            selectedId != previousId &&
            previousSignature.isNullOrBlank().not() &&
            previousSignature == selectedSignature
        ) {
            observedUnderlyingNetworkId = selectedId
            observedUnderlyingNetworkSignature = selectedSignature
            observedUnderlyingUnavailable = false
            if (currentState == STATE_CONNECTING && (observedUnderlyingUnavailable || !previousStillUsable)) {
                LogManager.log(
                    "Подложная сеть вернулась на том же интерфейсе/SSID ($previousId -> $selectedId) " +
                        "после полной потери. Возобновляем connect-cycle."
                )
                scheduleNetworkRecoveryCheck("underlying-change-connecting")
                return
            }
            if (currentState == STATE_CONNECTED && (observedUnderlyingUnavailable || !previousStillUsable)) {
                LogManager.log(
                    "Подложная сеть вернулась на том же интерфейсе/SSID ($previousId -> $selectedId) " +
                        "после полной потери. Сразу переносим VPN на вернувшийся underlay."
                )
                triggerReconnectForNetworkChange(clientData)
                return
            }
            LogManager.log(
                "Подложная сеть сменила только системный handle ($previousId -> $selectedId), " +
                    "но это тот же интерфейс/SSID. Игнорируем без реконнекта."
            )
            return
        }

        if (
            currentState == STATE_CONNECTING &&
            !previousId.isNullOrBlank() &&
            previousStillUsable
        ) {
            val triggerId = network?.toString()
            if (selectedId.isNullOrBlank() || selectedId != previousId || (!lost && triggerId != null && triggerId != previousId)) {
                LogManager.log(
                    "Во время подключения появилась альтернативная подложная сеть ($triggerId -> $selectedId), " +
                        "но текущая сеть $previousId ещё доступна. Продолжаем текущий цикл без перезапуска."
                )
                return
            }
        }

        if (
            currentState == STATE_CONNECTING &&
            previousStillUsable &&
            previousWasWifi &&
            selectedIsCellular &&
            !selectedIsWifi
        ) {
            LogManager.log(
                "Во время подключения система увидела transient cellular подложку ($previousId -> $selectedId), " +
                    "но рабочий Wi-Fi ещё доступен. Игнорируем смену и продолжаем текущий цикл."
            )
            return
        }

        if (
            selectedId != null &&
            (
                currentState != STATE_CONNECTING ||
                    previousId.isNullOrBlank() ||
                    !previousStillUsable ||
                    selectedId == previousId
                )
        ) {
            observedUnderlyingNetworkId = selectedId
            observedUnderlyingNetworkSignature = selectedSignature
            observedUnderlyingUnavailable = false
        }
        if (currentState == STATE_CONNECTING) {
            if (selectedId.isNullOrBlank()) {
                observedUnderlyingUnavailable = true
            }
            val description = when {
                previousId.isNullOrBlank() && !selectedId.isNullOrBlank() ->
                    "Во время подключения определилась подложная сеть ($selectedId). Фиксируем её для текущего цикла."
                !previousId.isNullOrBlank() && selectedId.isNullOrBlank() && lost ->
                    "Во время подключения исчезла подложная сеть ($previousId). Ждём восстановления сети."
                !previousId.isNullOrBlank() && selectedId.isNullOrBlank() ->
                    "Во время подключения подложная сеть временно недоступна. Ждём восстановления сети."
                else ->
                    "Во время подключения сменился сетевой интерфейс ($previousId -> $selectedId). Перезапустим попытки с начала на новой сети."
            }
            LogManager.log(description)
            scheduleNetworkRecoveryCheck("underlying-change-connecting")
            return
        }
        if (selectedId.isNullOrBlank()) {
            observedUnderlyingUnavailable = true
        }
        val description = when {
            previousId.isNullOrBlank() && !selectedId.isNullOrBlank() -> "Появилась подложная сеть ($selectedId). Проверяем, пережил ли VPN смену сети."
            !previousId.isNullOrBlank() && selectedId.isNullOrBlank() && lost -> "Подложная сеть исчезла ($previousId). Даём VPN время восстановиться."
            !previousId.isNullOrBlank() && selectedId.isNullOrBlank() -> "Подложная сеть временно недоступна. Даём VPN время восстановиться."
            else -> "Сменился сетевой интерфейс ($previousId -> $selectedId). Проверяем, сохранился ли рабочий VPN."
        }
        LogManager.log(description)
        val recoveryReason = if (selectedId.isNullOrBlank() || lost) "underlying-loss" else "underlying-change"
        requestAcceleratedVpnCheck(
            triggerReason = recoveryReason,
            recoveryReason = recoveryReason,
            freshProbeWindowMs = if (recoveryReason == "underlying-loss") 16_000L else 12_000L,
            dedupeMs = if (recoveryReason == "underlying-loss") 700L else 1000L,
            logMessage = when (recoveryReason) {
                "underlying-loss" -> "Подложная сеть изменилась неблагоприятно. Запускаем ускоренную проверку и ранний реконнект VPN."
                else -> "Подложная сеть сменилась. Запускаем ускоренную проверку рабочего VPN."
            },
        )
    }

    private fun triggerReconnectForNetworkChange(clientData: ClientData) {
        if (reconnectingForNetworkChange) return
        reconnectingForNetworkChange = true
        clientData.markTransientConnectingPending(9000L)
        acquireRecoveryWakeLock("network-reconnect", 12_000L)
        startSafeServiceThread("NovaNetworkReconnect") {
            try {
                if (isUserStopped || explicitStopRequested || cleanupInProgress.get()) return@startSafeServiceThread
                val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
                val selectedUnderlying = selectUnderlyingNetwork(connectivityManager)
                if (!isUsableUnderlyingNetwork(connectivityManager, selectedUnderlying)) {
                    LogManager.log(
                        "Подложная сеть сейчас недоступна. Ложный реконнект не запускаем и не тратим попытки."
                    )
                    return@startSafeServiceThread
                }
                val recoveryBackend = clientData.getServiceBackend()
                    .ifBlank { currentBackendLabel }
                    .ifBlank { BACKEND_WARP }
                currentAttemptOrdinal = 0
                currentAttemptTotal = 0
                setCurrentBackend(recoveryBackend)
                LogManager.log(
                    "Запускаем recovery-реконнект на сети " +
                        "${describeNetwork(connectivityManager, selectedUnderlying) ?: "unknown"}."
                )
                broadcastState(STATE_CONNECTING)
                val connectGenerationId = beginConnectGeneration(stopExisting = true)

                val regionPreference = normalizeRegionPreference(clientData.getExitRegionPreference())
                if (shouldUseWarpTransport(regionPreference)) {
                    val config = clientData.getConfig()
                    if (config != null) {
                        val reconnectWarpOnly = autoReconnectShouldPreferWarpOnly(clientData, regionPreference)
                        val operaAllowed = shouldAllowOperaTransport(regionPreference)
                        configureAndStartVpn(
                            config.privateKey,
                            config.ipv4,
                            config.ipv6,
                            config.peerPublicKey,
                            config.peerEndpoint,
                            config.reserved,
                            clientData.getLastSuccessPort(),
                            clientData.getLastSuccessProtocol(),
                            regionPreference,
                            allowOperaFallbackOverride = if (operaAllowed) {
                                if (reconnectWarpOnly) false else null
                            } else {
                                false
                            },
                            preferWarpOnlySticky = reconnectWarpOnly,
                            diagnosticsMode = false,
                            connectGenerationId = connectGenerationId,
                        )
                    } else if (shouldAllowOperaTransport(regionPreference)) {
                        configureAndStartOperaOnly(regionPreference, connectGenerationId)
                    }
                } else if (shouldAllowOperaTransport(regionPreference)) {
                    configureAndStartOperaOnly(regionPreference, connectGenerationId)
                }
            } finally {
                reconnectingForNetworkChange = false
            }
        }
    }

    private fun scheduleNetworkRecoveryCheck(reason: String) {
        pendingNetworkRecoveryReason = reason
        networkRecoveryHandler.removeCallbacks(networkRecoveryRunnable)
        val delayMs = when (reason) {
            "device-wake" -> 900L
            "wake-followup" -> 1000L
            "background-heartbeat" -> 350L
            "underlying-loss" -> 250L
            "underlying-change-connecting" -> 450L
            "underlying-change" -> 450L
            "health-followup" -> 650L
            "watchdog" -> 800L
            else -> 1800L
        }
        networkRecoveryHandler.postDelayed(networkRecoveryRunnable, delayMs)
    }

    private fun evaluatePendingNetworkRecovery(reason: String) {
        when (currentState) {
            STATE_CONNECTING -> evaluateConnectingRestartNeed(reason)
            STATE_CONNECTED -> evaluateNetworkRecoveryNeed(reason)
        }
    }

    private fun evaluateConnectingRestartNeed(reason: String) {
        if (!isRunning || isUserStopped || explicitStopRequested || cleanupInProgress.get() || suppressSessionRestore || warpConfigDiscoveryRunning.get()) {
            return
        }
        if (currentState != STATE_CONNECTING) return

        val clientData = ClientData(this)
        val session = clientData.getRestartSession()
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val selectedUnderlying = selectUnderlyingNetwork(connectivityManager)
        val selectedId = selectedUnderlying?.toString()
        val selectedSignature = buildUnderlyingNetworkSignature(connectivityManager, selectedUnderlying)
        if (selectedId.isNullOrBlank()) {
            LogManager.log("Во время подключения всё ещё нет доступной подложной сети. Ждём следующего сетевого события.")
            return
        }
        if (session == null) {
            LogManager.log(
                "Подложная сеть уже вернулась, но soft restart session ещё не сохранён. " +
                    "Жёстко перезапускаем connect-flow с начала на новой сети."
            )
            val reconnectNow = SystemClock.elapsedRealtime()
            if (reconnectingForNetworkChange || reconnectNow - lastNetworkReconnectAt < 2500L) {
                return
            }
            lastNetworkReconnectAt = reconnectNow
            observedUnderlyingNetworkId = selectedId
            observedUnderlyingNetworkSignature = selectedSignature
            triggerReconnectForNetworkChange(clientData)
            return
        }

        val previousId = observedUnderlyingNetworkId
        val previousSignature = observedUnderlyingNetworkSignature
        val previousNetwork = previousId?.let { findUnderlyingNetworkById(connectivityManager, it) }
        val previousStillUsable = previousNetwork?.let { isUsableUnderlyingNetwork(connectivityManager, it) } == true
        val previousWasWifi =
            previousNetwork?.let {
                connectivityManager.getNetworkCapabilities(it)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            } == true
        val selectedIsWifi =
            selectedUnderlying?.let {
                connectivityManager.getNetworkCapabilities(it)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            } == true
        val selectedIsCellular =
            selectedUnderlying?.let {
                connectivityManager.getNetworkCapabilities(it)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            } == true
        if (
            !previousId.isNullOrBlank() &&
            selectedId != previousId &&
            previousSignature.isNullOrBlank().not() &&
            previousSignature == selectedSignature
        ) {
            observedUnderlyingNetworkId = selectedId
            observedUnderlyingNetworkSignature = selectedSignature
            LogManager.log(
                "Во время подключения у подложной сети изменился только системный handle ($previousId -> $selectedId), " +
                    "но Wi-Fi/интерфейс прежний. Продолжаем connect-cycle."
            )
            return
        }
        if (
            !previousId.isNullOrBlank() &&
            previousStillUsable &&
            selectedId != previousId
        ) {
            LogManager.log(
                "Во время подключения система увидела альтернативную подложную сеть ($previousId -> $selectedId), " +
                    "но исходная сеть ещё жива. Продолжаем текущий connect-cycle без рестарта."
            )
            return
        }
        if (
            !previousId.isNullOrBlank() &&
            previousStillUsable &&
            previousWasWifi &&
            selectedIsCellular &&
            !selectedIsWifi
        ) {
            LogManager.log(
                "Во время подключения transient cellular-сеть пытается заменить Wi-Fi как подложку ($previousId -> $selectedId), " +
                    "но Wi-Fi остаётся живым. Перезапуск не нужен."
            )
            return
        }

        val reconnectNow = SystemClock.elapsedRealtime()
        if (reconnectingForNetworkChange || reconnectNow - lastNetworkReconnectAt < 2500L) {
            return
        }
        lastNetworkReconnectAt = reconnectNow
        observedUnderlyingNetworkId = selectedId
        observedUnderlyingNetworkSignature = selectedSignature
        LogManager.log(
            if (reason == "underlying-change-connecting") {
                "Подложная сеть сменилась во время подключения ($selectedId). Начинаем перебор заново на новой сети."
            } else {
                "Во время подключения условия сети изменились. Перезапускаем попытки подключения с начала."
            }
        )
        triggerReconnectForNetworkChange(clientData)
    }

    private fun evaluateNetworkRecoveryNeed(reason: String) {
        if (!isRunning || isUserStopped || explicitStopRequested || cleanupInProgress.get() || suppressSessionRestore || warpConfigDiscoveryRunning.get()) {
            return
        }
        if (currentState != STATE_CONNECTED) return
        val clientData = ClientData(this)
        val forceImmediateRecovery = reason == "connected-proof-timeout"

        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val currentVpn = findCurrentVpnNetwork(connectivityManager)
        val selectedUnderlying = selectUnderlyingNetwork(connectivityManager)
        val shouldRecordWarpHealthWindow = reason != "watchdog"
        val importedExactAwgActive = currentConnectionUsesImportedExactAwg()
        val warpBackend =
            currentBackendLabel.trim().uppercase(Locale.ROOT).startsWith(BACKEND_WARP) &&
                !importedExactAwgActive
        if (shouldPreferImmediateReconnectForUnderlyingUpgrade(connectivityManager, selectedUnderlying, reason)) {
            connectedHealthProbeFailures = 0
            rememberPendingUnderlayUpgradeWarpHint(connectivityManager, selectedUnderlying)
            LogManager.log(
                "Появилась более приоритетная подложная сеть Wi‑Fi поверх мобильного WARP. " +
                    "Сразу переносим туннель на Wi‑Fi без ожидания потери старого underlay."
            )
            triggerReconnectForNetworkChange(clientData)
            return
        }
        val probeTimeout = if (isOperaBackendLabel(currentBackendLabel)) 1400 else 1600
        // Пока действует окно требования свежей пробы (его взводит смена подложной
        // сети), накопленные доказательства не считаются. Иначе туннель объявляется
        // живым по данным из прошлой сети: счётчики трафика в
        // observeImportedExactAwgTrafficProof накопительные, а рукопожатие считается
        // свежим целых три минуты, так что оба условия выполняются и после переезда
        // сокета на другой интерфейс, когда через туннель уже не проходит ни байта.
        val importedExactAwgProof =
            if (
                importedExactAwgActive &&
                !requiresFreshTunnelProbeNow() &&
                currentVpn != null &&
                isLikelyNovaVpnNetwork(connectivityManager, currentVpn)
            ) {
                observeImportedExactAwgTrafficProof()
            } else {
                false
            }
        if (
            currentVpn != null &&
            isLikelyNovaVpnNetwork(connectivityManager, currentVpn) &&
            if (importedExactAwgActive) {
                importedExactAwgProof ||
                    hasTunnelConnectivity(currentVpn, probeTimeout, allowHttpDnsFallback = true)
            } else if (warpBackend) {
                measureWarpQualityLatency(currentVpn, timeoutMs = probeTimeout) >= 0 ||
                    (!requiresFreshTunnelProbeNow() && hasRecentConfirmedWarpExitSnapshot(clientData))
            } else {
                hasTunnelConnectivity(currentVpn, probeTimeout, allowHttpDnsFallback = true)
            }
        ) {
            if (importedExactAwgActive) {
                noteImportedExactAwgTrafficProof()
            }
            markSuccessfulTunnelProbe()
            connectedHealthProbeFailures = 0
            if (warpBackend) {
                if (shouldRecordWarpHealthWindow) {
                    recordConnectedWarpHealthProbe(success = true)
                }
            } else {
                resetConnectedWarpHealthWindow()
            }
            if (reason != "watchdog") {
                LogManager.log("После смены сети tunnel-probe через текущий VPN уже проходит. Реконнект не требуется.")
            }
            return
        }
        val warpHealthAfterFailure =
            if (shouldRecordWarpHealthWindow &&
                warpBackend &&
                currentVpn != null &&
                isLikelyNovaVpnNetwork(connectivityManager, currentVpn)
            ) {
                recordConnectedWarpHealthProbe(success = false)
            } else {
                resetConnectedWarpHealthWindow()
                null
            }
        if (
            warpHealthAfterFailure != null &&
            maybeRecoverDegradedConnectedWarpWindow(
                clientData = clientData,
                snapshot = warpHealthAfterFailure,
                reason = "intermittent tunnel-probe loss",
            )
        ) {
            return
        }
        if (
            importedExactAwgActive &&
            !requiresFreshTunnelProbeNow() &&
            hasRecentImportedExactAwgTrafficProof()
        ) {
            connectedHealthProbeFailures = 0
            return
        }
        if (reason == "watchdog" && hasStableRecentTunnelProofForUnderlying(connectivityManager, selectedUnderlying)) {
            connectedHealthProbeFailures = 0
            return
        }
        if (!forceImmediateRecovery && shouldHonorFreshConnectGraceForUnderlying(connectivityManager, selectedUnderlying)) {
            logBenignHealthSkip("Смена сети пришлась на окно стабилизации после connect. Даём VPN время восстановиться без реконнекта.")
            return
        }
        val health = inspectVpnHealth(connectivityManager, currentVpn)
        if (
            !requiresFreshTunnelProbeNow() &&
            health.reason == "VPN потерял underlying networks" &&
            health.selectedUnderlying != null &&
            hasRecentSuccessfulTunnelProbeForUnderlying(
                connectivityManager,
                health.selectedUnderlying,
            )
        ) {
            connectedHealthProbeFailures = 0
            LogManager.log(
                "Подложная сеть всё ещё доступна, а tunnel-probe недавно был успешен. " +
                    "Transient потерю underlying networks игнорируем."
            )
            return
        }
        if (
            reason == "watchdog" &&
            !requiresFreshTunnelProbeNow() &&
            hasRecentSuccessfulTunnelProbeForUnderlying(connectivityManager, selectedUnderlying)
        ) {
            connectedHealthProbeFailures = 0
            return
        }
        if (shouldDeferWakeReconnect(reason, connectivityManager, currentVpn, health)) {
            connectedHealthProbeFailures = 0
            LogManager.log(
                "Сразу после пробуждения системный VPN Nova ещё восстанавливает привязку к подложной сети. " +
                    "Даём туннелю короткий grace без принудительного реконнекта."
            )
            scheduleNetworkRecoveryCheck("wake-followup")
            return
        }
        if (health.isHealthy) {
            connectedHealthProbeFailures = 0
            LogManager.log("После смены сети VPN сохранился рабочим. Реконнект не требуется.")
            return
        }
        if (health.selectedUnderlying == null) {
            connectedHealthProbeFailures = 0
            LogManager.log(
                "Подложная сеть сейчас отсутствует. Реконнект не запускаем и не считаем попытки ложными " +
                    "до возврата Wi-Fi или мобильного сигнала."
            )
            return
        }
        if (currentVpn != null && isLikelyNovaVpnNetwork(connectivityManager, currentVpn)) {
            if (!forceImmediateRecovery) {
                connectedHealthProbeFailures += 1
                val requiredFailures = healthReconnectFailureThreshold(health.reason)
                if (connectedHealthProbeFailures < requiredFailures) {
                    scheduleNetworkRecoveryCheck("health-followup")
                    LogManager.log(
                        "После смены сети tunnel-probe через текущий VPN не прошёл. " +
                            "Ждём повторной проверки перед реконнектом " +
                            "($connectedHealthProbeFailures/$requiredFailures)."
                    )
                    return
                }
            } else {
                connectedHealthProbeFailures = maxOf(
                    connectedHealthProbeFailures,
                    healthReconnectFailureThreshold("tunnel-probe не проходит"),
                )
                LogManager.log(
                    "Туннель не подтвердился за 5 секунд после CONNECTED. " +
                        "Не ждём дополнительную grace-паузу и сразу запускаем recovery."
                )
            }
        }

        if (!clientData.getAutoReconnect()) {
            if (currentVpn != null && isLikelyNovaVpnNetwork(connectivityManager, currentVpn)) {
                LogManager.log(
                    if (health.currentVpn == null) {
                        "Подложная сеть пропала, авто-реконнект выключен. Полностью снимаем VPN-стек с delayed detach."
                    } else {
                        "VPN больше не считается рабочим (${health.reason}), авто-реконнект выключен. Полностью снимаем VPN-стек с delayed detach."
                    }
                )
                cleanupAndStop(
                    allowSyntheticDetach = false,
                    manualStopRequested = true,
                )
            }
            return
        }

        val reconnectNow = SystemClock.elapsedRealtime()
        if (reconnectingForNetworkChange) return
        if (!forceImmediateRecovery && reconnectNow - lastNetworkReconnectAt < 5_000L) return
        lastNetworkReconnectAt = reconnectNow
        val reconnectReason = when {
            health.currentVpn == null -> "VPN-интерфейс пропал"
            health.selectedUnderlying == null -> "подложная сеть недоступна"
            forceImmediateRecovery -> "проверка туннеля не подтвердилась за 5 секунд"
            health.reason.isNotBlank() -> health.reason
            else -> "VPN больше не валидирован ($reason)"
        }
        val reconnectPrefix = when (reason) {
            "background-heartbeat" -> "Фоновый heartbeat не подтвердил рабочий VPN ($reconnectReason)."
            "watchdog" -> "Watchdog не подтвердил рабочий VPN ($reconnectReason)."
            else -> "После смены сети $reconnectReason."
        }
        if (currentBackendLabel.trim().uppercase(Locale.ROOT).startsWith(BACKEND_WARP)) {
            markActiveWarpQualityTargetDegraded(clientData, reconnectReason)
            clearActiveWarpQualityTarget()
        }
        LogManager.log("$reconnectPrefix Запускаем реконнект.")
        triggerReconnectForNetworkChange(clientData)
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Nova VPN", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(subtitle: String = ""): Notification {
        return AppUpdateManager.buildForegroundVpnNotification(this, CHANNEL_ID, subtitle)
    }

    private fun parseEndpoint(endpoint: String): Pair<String, Int?> {
        val value = endpoint.trim()
        if (value.startsWith("[") && value.contains("]")) {
            val end = value.indexOf(']')
            val host = value.substring(1, end)
            val port = value.substring(end + 1).removePrefix(":").toIntOrNull()?.takeIf { it in 1..65535 }
            return host to port
        }

        val colonCount = value.count { it == ':' }
        if (colonCount == 1) {
            val idx = value.lastIndexOf(':')
            val host = value.substring(0, idx)
            val port = value.substring(idx + 1).toIntOrNull()?.takeIf { it in 1..65535 }
            return host to port
        }

        return value to null
    }

    private fun formatEndpoint(host: String, port: Int): String {
        return if (host.contains(':') && !host.startsWith("[")) {
            "[$host]:$port"
        } else {
            "$host:$port"
        }
    }

    private fun isNumericEndpointHost(host: String): Boolean {
        val normalized = host.trim().removePrefix("[").removeSuffix("]")
        if (normalized.isBlank()) return false
        val ipv4 = Regex("^[0-9.]+$")
        val ipv6 = Regex("^[0-9a-fA-F:]+$")
        return ipv4.matches(normalized) || ipv6.matches(normalized)
    }

    private fun buildEndpointCandidates(
        apiEndpoint: String,
        apiPort: Int?,
        privateKey: String,
        peerPublicKey: String,
        clientData: ClientData,
        expandForDiscovery: Boolean = false,
        includeScannerCandidates: Boolean = true,
        includeApiResolution: Boolean = true,
    ): List<EndpointCandidate> {
        val candidates = linkedMapOf<String, EndpointCandidate>()
        val currentCycleLastProtocol = currentCycleLastSuccessProtocol(clientData)
        val forbidStableMismatchReuse =
            clientData.hasFreshStableLastSuccess() && currentCycleStableSuccess == null
        val canReuseFreshLastSuccessForWg = !forbidStableMismatchReuse &&
            currentCycleHasReusableLastSuccess(clientData) &&
            !currentCycleLastProtocol.equals("MASQUE", ignoreCase = true)

        fun addCandidate(host: String?, preferredPort: Int?, source: String) {
            val cleanHost = host?.trim().orEmpty().removePrefix("[").removeSuffix("]")
            if (cleanHost.isBlank()) return
            val key = "$cleanHost:${preferredPort ?: 0}"
            if (!candidates.containsKey(key)) {
                candidates[key] = EndpointCandidate(cleanHost, preferredPort, source)
            }
        }

        fun addCandidateWithResolution(host: String?, preferredPort: Int?, source: String) {
            val cleanHost = host?.trim().orEmpty().removePrefix("[").removeSuffix("]")
            if (cleanHost.isBlank()) return
            if (isNumericEndpointHost(cleanHost)) {
                addCandidate(cleanHost, preferredPort, source)
                return
            }
            val resolvedHosts = resolveWarpEndpointHost(cleanHost)
            if (resolvedHosts.isEmpty()) {
                LogManager.log("Endpoint-host $cleanHost не удалось быстро зарезолвить вне VPN, пропускаем его в WG fallback.")
                return
            }
            resolvedHosts.forEach { resolved ->
                addCandidate(resolved, preferredPort, "$source-resolved")
            }
        }

        if (canReuseFreshLastSuccessForWg) {
            addCandidate(
                currentCycleLastSuccessEndpoint(clientData),
                currentCycleLastSuccessPort(clientData),
                if (currentCycleStableSuccess != null) "last-success-exact" else "last-success",
            )
        }
        sortedVerifiedWarpConfigs(clientData)
            .filter { !it.engine.equals("masque", ignoreCase = true) }
            .take(if (expandForDiscovery) 14 else 8)
            .forEach { verified ->
                addCandidate(verified.host, verified.port, "verified-config")
            }
        if (includeApiResolution) {
            addCandidateWithResolution(apiEndpoint, apiPort, "api")
        } else {
            LogManager.log("Fast-start WARP: пропускаем ранний resolve API endpoint, используем verified/known-anycast кандидаты.")
        }
        addCandidate("162.159.192.1", 988, "awg-fallback")
        val knownAnycast = mutableListOf(
            "162.159.192.1" to 500,
            "162.159.192.9" to 500,
            "162.159.193.3" to 2408,
            "162.159.195.1" to 500,
            "162.159.197.1" to 1701,
            "162.159.197.2" to 500,
            "162.159.198.1" to 1701,
            "162.159.198.2" to 1701,
            "2606:4700:d0::3cd7:73cc:615b:bf06" to 4500,
            "188.114.98.198" to 500,
            "188.114.97.76" to 500,
        )
        if (expandForDiscovery) {
            knownAnycast += listOf(
                "162.159.192.2" to 500,
                "162.159.192.3" to 1701,
                "162.159.193.1" to 2408,
                "162.159.193.2" to 2408,
                "162.159.195.2" to 1701,
                "162.159.197.3" to 443,
                "162.159.198.3" to 500,
                "188.114.96.1" to 500,
                "188.114.99.97" to 500,
            )
        }
        for ((host, port) in knownAnycast) {
            addCandidate(host, port, "known-anycast")
        }

        val scannedEndpoints = if (includeScannerCandidates) {
            readScannedWarpEndpoints(privateKey, peerPublicKey)
        } else {
            emptyList()
        }
        for (candidate in scannedEndpoints) {
            addCandidate(candidate.host, candidate.preferredPort, candidate.source)
        }

        val expansionSeeds = linkedSetOf<String>()
        clientData.getLastSuccessEndpoint()?.let { expansionSeeds += it }
        sortedVerifiedWarpConfigs(clientData)
            .filter { !it.engine.equals("masque", ignoreCase = true) }
            .take(if (expandForDiscovery) 14 else 8)
            .forEach { expansionSeeds += it.host }
        scannedEndpoints.forEach { expansionSeeds += it.host }
        if (expandForDiscovery) {
            knownAnycast.forEach { expansionSeeds += it.first }
        }
        for (seedHost in expansionSeeds) {
            for (neighbor in expandWarpNeighborHosts(seedHost, includeWider = expandForDiscovery)) {
                addCandidate(neighbor, null, "neighbor-anycast")
            }
        }

        if (candidates.isEmpty()) {
            val fallbackHost = if (isPlausibleWarpEndpoint(apiEndpoint)) apiEndpoint else "162.159.192.1"
            addCandidate(fallbackHost, apiPort ?: 500, "fallback")
        }

        return compactWarpEndpointCandidates(
            candidates = candidates.values.toList(),
            maxDistinctHosts = if (expandForDiscovery) 18 else 9,
        )
    }

    private fun selectPrimaryWarpTransportModes(
        transportModes: List<TransportMode>,
        clientData: ClientData,
        preferMessengerChatProfiles: Boolean,
        fastStartEnabled: Boolean,
    ): List<TransportMode> {
        if (!fastStartEnabled || transportModes.size <= 10) return transportModes

        val preferredNames = linkedSetOf<String>()
        val lastMode = clientData.getLastSuccessMode().trim()
        if (lastMode.isNotBlank()) {
            preferredNames += lastMode
        }

        preferredNames += listOf(
            "warp-awg-exact",
            "warp-awg-v2",
            "warp-awg",
            "warp-awg-lite",
            "warp-awg-max",
            "warp-v1",
            "warp-v2",
            "warp-v3",
        )

        val selected = mutableListOf<TransportMode>()
        preferredNames.forEach { name ->
            transportModes.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { mode ->
                if (mode !in selected) {
                    selected += mode
                }
            }
        }

        transportModes.forEach { mode ->
            if (selected.size >= 10) return@forEach
            val family = modeFamily(mode)
            if (family == "awg" && mode !in selected) {
                selected += mode
            }
        }

        return if (selected.isNotEmpty()) selected else transportModes
    }

    private fun endpointSourceRank(source: String): Int {
        return when (source.trim().lowercase()) {
            "verified-config" -> 0
            "last-success-exact" -> 1
            "last-success" -> 2
            "api", "api-resolved" -> 3
            "awg-fallback" -> 4
            "known-anycast" -> 5
            "neighbor-anycast" -> 6
            "scan", "fallback-scan", "masque-scan" -> 7
            "masque-scan-tcp443" -> 8
            "random" -> 8
            "fallback" -> 9
            else -> 10
        }
    }

    /** Первые три октета IPv4-адреса — «сеть» узла в терминах анкаста Cloudflare. */
    private fun ipv4NetworkPrefix(host: String): String? {
        val parts = host.trim().removePrefix("[").removeSuffix("]").split('.')
        if (parts.size != 4) return null
        if (parts.any { it.toIntOrNull() !in 0..255 }) return null
        return parts.take(3).joinToString(".")
    }

    private fun expandWarpNeighborHosts(
        host: String,
        includeWider: Boolean,
    ): List<String> {
        val normalized = host.trim().removePrefix("[").removeSuffix("]")
        val parts = normalized.split('.')
        if (parts.size != 4) return emptyList()
        val octets = parts.map { it.toIntOrNull() ?: return emptyList() }
        val prefix = octets.take(3)
        val supportedPrefixes = setOf(
            listOf(162, 159, 192),
            listOf(162, 159, 193),
            listOf(162, 159, 195),
            listOf(162, 159, 197),
            listOf(162, 159, 198),
            listOf(188, 114, 96),
            listOf(188, 114, 97),
            listOf(188, 114, 98),
            listOf(188, 114, 99),
        )
        if (prefix !in supportedPrefixes) return emptyList()

        val base = octets[3]
        val offsets = if (includeWider) {
            listOf(-3, -2, -1, 1, 2, 3, 4, 5)
        } else {
            listOf(-2, -1, 1, 2)
        }
        return offsets
            .map { base + it }
            .filter { it in 1..254 && it != base }
            .map { "${prefix[0]}.${prefix[1]}.${prefix[2]}.$it" }
            .distinct()
    }

    private fun compactWarpEndpointCandidates(
        candidates: List<EndpointCandidate>,
        maxDistinctHosts: Int = 9,
    ): List<EndpointCandidate> {
        if (candidates.size <= maxDistinctHosts) return candidates

        val selected = mutableListOf<EndpointCandidate>()
        val seenHosts = linkedSetOf<String>()
        val seenHostPorts = linkedSetOf<String>()
        val sorted = candidates.sortedWith(
            compareBy<EndpointCandidate>(
                { endpointSourceRank(it.source) },
                { it.preferredPort == null },
                { warpPortRank(it.preferredPort ?: 500) },
                { it.host.contains(':') },
            )
        )

        val trustedSources = setOf("verified-config", "last-success-exact", "last-success")

        for (candidate in sorted) {
            if (selected.size >= minOf(maxDistinctHosts, 4)) break
            if (candidate.source.lowercase() !in trustedSources) continue
            val hostKey = candidate.host.trim().lowercase()
            val hostPortKey = "$hostKey:${candidate.preferredPort ?: 0}"
            if (hostPortKey in seenHostPorts) continue
            if (selected.count { it.host.equals(candidate.host, ignoreCase = true) } >= 3) continue
            selected += candidate
            seenHosts += hostKey
            seenHostPorts += hostPortKey
        }

        for (candidate in sorted) {
            val hostKey = candidate.host.trim().lowercase()
            val hostPortKey = "$hostKey:${candidate.preferredPort ?: 0}"
            if (hostPortKey in seenHostPorts) continue
            val allowTrustedPortDiversity =
                candidate.source.lowercase() in trustedSources &&
                    candidate.preferredPort != null &&
                    selected.count { it.host.equals(candidate.host, ignoreCase = true) } < 3
            if (!allowTrustedPortDiversity && !seenHosts.add(hostKey)) continue
            selected += candidate
            seenHosts += hostKey
            seenHostPorts += hostPortKey
            if (selected.size >= maxDistinctHosts) break
        }

        if (selected.size < candidates.size) {
            LogManager.log(
                "Сжали набор WARP endpoint-ов: ${candidates.size} -> ${selected.size}. " +
                    "Оставили: ${selected.joinToString(",") { "${it.host}${it.preferredPort?.let { port -> ":$port" } ?: ""}(${it.source})" }}"
            )
        }

        return selected
    }

    private fun resolveWarpEndpointHost(hostname: String): List<String> {
        val normalized = hostname.trim().removePrefix("[").removeSuffix("]").trimEnd('.')
        if (normalized.isBlank()) return emptyList()
        if (isNumericEndpointHost(normalized)) return listOf(normalized)

        val resolved = linkedSetOf<String>()
        runCatching {
            WarpBootstrapDns.resolveHost(normalized)
                .mapNotNull { it.hostAddress }
                .forEach { resolved.add(it) }
        }
        if (resolved.isEmpty()) {
            resolveHostOutsideVpn(normalized)
                .mapNotNull { it.hostAddress }
                .forEach { resolved.add(it) }
        }

        return resolved
            .filter { isPlausibleWarpEndpoint(it) }
            .distinct()
    }

    private fun readScannedWarpEndpoints(privateKey: String, peerPublicKey: String): List<EndpointCandidate> {
        return try {
            val ipv6Available = hasIpv6Connectivity()
            if (!ipv6Available) {
                LogManager.log("IPv6 до WARP с текущей сети недоступен, IPv6 endpoint-ы пропускаем.")
            }

            val raw = Nova.scanWarpEndpoints(privateKey, peerPublicKey, true, ipv6Available, 3500, 3).orEmpty()
            if (raw.isBlank()) {
                return fallbackWarpScannerCandidates()
            }

            val parsed = mutableListOf<EndpointCandidate>()
            raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { line ->
                    val endpointValue = line.substringBefore('|').trim()
                    val endpointInfo = parseEndpoint(endpointValue)
                    val source = if (line.substringAfter('|', "-1").trim().toLongOrNull()?.let { it >= 0L } == true) {
                        "scan"
                    } else {
                        "random"
                    }
                    parsed.add(
                        EndpointCandidate(
                            host = endpointInfo.first,
                            preferredPort = null,
                            source = source
                        )
                    )
                }

            val normalized = parsed
                .distinctBy { "${it.host}:${it.preferredPort ?: 0}:${it.source}" }
                .sortedWith(compareBy<EndpointCandidate>({ it.host.contains(':') }, { it.source != "scan" }))

            if (normalized.isEmpty()) {
                fallbackWarpScannerCandidates()
            } else {
                LogManager.log(
                    "ipscanner кандидаты: ${
                        normalized.joinToString(",") {
                            val suffix = if (it.preferredPort != null) ":${it.preferredPort}" else ""
                            "${it.host}$suffix(${it.source})"
                        }
                    }"
                )
                normalized
            }
        } catch (e: Exception) {
            LogManager.log("ipscanner недоступен: ${e.message}. Используем простой fallback scan.")
            fallbackWarpScannerCandidates()
        }
    }

    private fun readScannedMasqueEndpoints(sni: String, ports: List<Int>): List<EndpointCandidate> {
        return try {
            val ipv6Available = hasIpv6Connectivity()
            val quicRaw = Nova.scanMasqueEndpoints(
                true,
                ipv6Available,
                3200,
                4,
                ports.filter { it in 1..65535 }.distinct().joinToString(","),
                sni
            ).orEmpty()
            val tcpRaw = runCatching {
                Nova.scanMasqueEndpointsWithTransport(
                    true,
                    ipv6Available,
                    2800,
                    2,
                    "443",
                    sni,
                    "tcp-tls"
                ).orEmpty()
            }.getOrElse {
                LogManager.log("MASQUE TCP 443 scan недоступен (${sni.ifBlank { "default" }}): ${it.message}")
                ""
            }

            val parsed = mutableListOf<EndpointCandidate>()
            fun appendResults(raw: String, source: String) {
                if (raw.isBlank()) return
                raw.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .mapNotNull { line ->
                        val endpointValue = line.substringBefore('|').trim()
                        val endpointInfo = parseEndpoint(endpointValue)
                        val cleanHost = endpointInfo.first.trim().removePrefix("[").removeSuffix("]")
                        if (cleanHost.isBlank()) return@mapNotNull null
                        EndpointCandidate(
                            host = cleanHost,
                            preferredPort = endpointInfo.second?.takeIf { it in 1..65535 },
                            source = source
                        )
                    }
                    .forEach(parsed::add)
            }

            appendResults(quicRaw, "masque-scan")
            appendResults(tcpRaw, "masque-scan-tcp443")

            val normalized = parsed
                .distinctBy { "${it.host}:${it.preferredPort ?: 0}:${it.source}" }
                .toList()

            if (normalized.isNotEmpty()) {
                LogManager.log(
                    "MASQUE scan (${sni.ifBlank { "default" }}): ${
                        normalized.joinToString(",") {
                            "${it.host}${it.preferredPort?.let { port -> ":$port" } ?: ""}(${it.source})"
                        }
                    }"
                )
            }
            normalized
        } catch (e: Exception) {
            LogManager.log("MASQUE scan недоступен (${sni.ifBlank { "default" }}): ${e.message}")
            emptyList()
        }
    }

    private fun fallbackWarpScannerCandidates(): List<EndpointCandidate> {
        val scanner = WarpScanner()
        val scanned = mutableListOf<EndpointCandidate>()
        val latch = java.util.concurrent.CountDownLatch(1)
        scanner.scanBestIp { ipPair ->
            val ip = ipPair?.first
            val port = ipPair?.second
            if (!ip.isNullOrBlank() && port != null) {
                scanned.add(EndpointCandidate(ip, port, "fallback-scan"))
            }
            latch.countDown()
        }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        return scanned
    }

    private fun buildPortCandidates(apiPort: Int?, savedPort: Int, savedProto: String, clientData: ClientData): List<Int> {
        val ports = linkedSetOf<Int>()
        val canReuseFreshLastSuccessForWg = currentCycleHasReusableLastSuccess(clientData) &&
            !currentCycleLastSuccessProtocol(clientData).equals("MASQUE", ignoreCase = true)
        val freshLastSuccessPort = currentCycleLastSuccessPort(clientData)?.takeIf {
            it in 1..65535 && canReuseFreshLastSuccessForWg
        }
        if (freshLastSuccessPort != null) ports.add(freshLastSuccessPort)
        sortedVerifiedWarpConfigs(clientData)
            .filter { !it.engine.equals("masque", ignoreCase = true) }
            .map { it.port }
            .filter { it in 1..65535 }
            .forEach { ports.add(it) }
        val learnedPorts = clientData.getLearnedWarpPortOrder(limit = 14, includeMasque = false)
        learnedPorts.forEach { ports.add(it) }
        if (apiPort != null && apiPort in 1..65535) ports.add(apiPort)
        if (savedPort in 1..65535 && !savedProto.equals("MASQUE", ignoreCase = true)) ports.add(savedPort)
        ports.add(988)

        // Обычный цикл держим коротким: полный список портов используется в ручной адаптации.
        for (fallback in warpQuickPortOrder()) {
            ports.add(fallback)
        }
        return ports
            .sortedByDescending { port ->
                var score = 0
                if (port == freshLastSuccessPort) score += 40
                if (port in learnedPorts) score += 16
                if (apiPort != null && port == apiPort) {
                    score += if (isCoreWarpPort(port)) 4 else 1
                }
                if (port == savedPort) score += 1
                score - warpPortRank(port)
            }
    }

    private fun warpQuickPortOrder(): List<Int> =
        listOf(988, 942, 934, 880, 878, 894, 908, 500, 4500, 1701, 2408, 1002)

    private fun warpExperimentalPortOrder(): List<Int> =
        listOf(
            500, 854, 859, 864, 878, 880, 890, 891, 894, 903, 908, 928,
            934, 939, 942, 943, 945, 946, 955, 968, 987, 988, 1002, 1010,
            1014, 1018, 1070, 1074, 1180, 1387, 1701, 1843, 2371, 2408,
            2506, 3138, 3736, 4500, 5000, 5060, 6081, 7153, 7559, 8888,
        )

    private fun masqueExperimentalPortOrder(): List<Int> =
        listOf(443, 500, 1701, 4500, 4443, 8095, 8443)

    private fun warpPortRank(port: Int): Int {
        val quickRank = warpQuickPortOrder().indexOf(port)
        if (quickRank >= 0) return quickRank
        val experimentalRank = warpExperimentalPortOrder().indexOf(port)
        if (experimentalRank >= 0) return 40 + experimentalRank
        val masqueRank = masqueExperimentalPortOrder().indexOf(port)
        if (masqueRank >= 0) return 90 + masqueRank
        return 200 + port
    }

    private fun isLegacy32BitDevice(): Boolean {
        if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) return false
        return Build.SUPPORTED_ABIS.any { abi ->
            val normalized = abi.lowercase()
            normalized.contains("armeabi") || normalized == "arm" || normalized.startsWith("x86")
        }
    }

    private fun buildConnectionAttempts(
        endpointCandidates: List<EndpointCandidate>,
        portCandidates: List<Int>,
        transportModes: List<TransportMode>,
    ): List<ConnectionAttempt> {
        val attempts = mutableListOf<ConnectionAttempt>()
        val orderedPorts = linkedSetOf<Int>()
        val endpointPreferredPorts = endpointCandidates.mapNotNull { it.preferredPort }.filter { it in 1..65535 }

        for (port in endpointPreferredPorts) {
            orderedPorts.add(port)
        }
        for (mode in transportModes) {
            for (port in mode.preferredPorts) {
                if (port in portCandidates) {
                    orderedPorts.add(port)
                }
            }
        }
        for (port in portCandidates) {
            orderedPorts.add(port)
        }

        for (mode in transportModes) {
            for (port in orderedPorts) {
                val preferredEndpoints = endpointCandidates.filter { it.preferredPort == port }
                val fallbackEndpoints = endpointCandidates.filter { it.preferredPort != port }
                for (endpoint in preferredEndpoints + fallbackEndpoints) {
                    val modeAllowsPort = !mode.restrictToPreferredPorts || port in mode.preferredPorts || endpoint.preferredPort == port
                    if (!modeAllowsPort) continue
                    attempts.add(
                        ConnectionAttempt(
                            endpointHost = endpoint.host,
                            port = port,
                            mode = mode,
                            endpointSource = endpoint.source
                        )
                    )
                }
            }
        }
        return attempts.distinctBy { "${it.mode.engine}:${it.mode.name}:${it.endpointHost}:${it.port}" }
    }

    private fun ensureMasqueModeDiversity(
        attempts: List<ConnectionAttempt>,
        limit: Int,
        clientData: ClientData,
    ): List<ConnectionAttempt> {
        if (limit <= 0 || attempts.isEmpty()) return emptyList()
        if (attempts.none { it.mode.engine == "masque" }) return attempts.take(limit)

        val masqueAttempts = attempts.filter { it.mode.engine == "masque" }
        val selected = mutableListOf<ConnectionAttempt>()
        val legacy32 = isLegacy32BitDevice()
        val messengerAccelerationProfile = resolveMessengerAccelerationProfile(clientData)
        val preferMessengerMasqueStealth = messengerAccelerationProfile != MessengerAccelerationProfile.OFF
        val lastSuccessProtocol = clientData.getLastSuccessProtocol()
        val lastSuccessMode = clientData.getLastSuccessMode()
        val masqueStrategyScope = if (preferMessengerMasqueStealth) "messenger" else "default"
        fun masqueAttemptKey(attempt: ConnectionAttempt): String {
            return "${attempt.mode.name.lowercase()}:${attempt.endpointHost.trim().lowercase()}:${attempt.port}"
        }
        val verifiedMasqueConfigs = sortedVerifiedWarpConfigs(clientData)
            .filter { it.engine.equals("masque", ignoreCase = true) && !it.manual }
        val verifiedMasquePriorityByKey = verifiedMasqueConfigs.associate { config ->
            "${config.mode.lowercase()}:${config.host.trim().lowercase()}:${config.port}" to
                clientData.getWarpVerifiedPriorityScore(config)
        }
        // Адреса из сети, выданной этому устройству, идут выше сканированных чужих.
        //
        // Замер на тестовом устройстве: узлы 162.159.198.x — та самая сеть из identity — принимают
        // QUIC и HTTP/3, а 162.159.197.x отвечают алертом `tls: access denied` за 130мс:
        // этому аккаунту они не выдавались, и обслуживать его не станут. Половина круга
        // уходила на них впустую, а при явном выборе MASQUE круг — единственное, что есть.
        val ownMasqueNetworkPrefixes = masqueAttempts
            .filter { isOwnMasqueIdentityEndpoint(it.endpointSource) }
            .mapNotNull { ipv4NetworkPrefix(it.endpointHost) }
            .toSet()
        fun masqueRankScore(attempt: ConnectionAttempt): Double {
            val ownNetworkBoost = if (
                ownMasqueNetworkPrefixes.isNotEmpty() &&
                ipv4NetworkPrefix(attempt.endpointHost) in ownMasqueNetworkPrefixes
            ) {
                6.0
            } else {
                0.0
            }
            val strategyScore = clientData.getStrategyScore(
                engine = attempt.mode.engine,
                mode = attempt.mode.name,
                host = attempt.endpointHost,
                port = attempt.port,
                strategyScope = masqueStrategyScope,
            ) + attemptSourceBias(
                attempt = attempt,
                lastProtocol = lastSuccessProtocol,
                lastMode = lastSuccessMode,
                preferMessengerChatProfiles = preferMessengerMasqueStealth,
                messengerAccelerationProfile = messengerAccelerationProfile,
            )
            val verifiedPriority = verifiedMasquePriorityByKey[masqueAttemptKey(attempt)] ?: 0.0
            val persistedSourceBoost = when (attempt.endpointSource.lowercase()) {
                "last-success-exact" -> 14.0
                "verified-config" -> 8.0
                "last-success" -> 5.0
                else -> 0.0
            }
            return strategyScore + verifiedPriority * 1.35 + persistedSourceBoost + ownNetworkBoost
        }
        val rankedMasqueAttempts = masqueAttempts.sortedByDescending { attempt ->
            masqueRankScore(attempt)
        }
        val rankedMasqueScoreByKey = rankedMasqueAttempts
            .associate { attempt ->
                masqueAttemptKey(attempt) to masqueRankScore(attempt)
            }
        val preferredCoreMasquePorts = if (legacy32) {
            setOf(500, 1701, 4500, 443)
        } else {
            setOf(1701, 500, 4500, 443, 8443, 4443, 8095)
        }
        val lastProtocol = clientData.getLastSuccessProtocol()
        val lastMode = clientData.getLastSuccessMode()
        val lastPort = clientData.getLastSuccessPort().takeIf { it in 1..65535 }
        val hasFreshMasqueSuccess =
            clientData.hasFreshLastSuccess() &&
                lastProtocol.equals("masque", ignoreCase = true) &&
                lastPort != null
        val topVerifiedMasquePorts = verifiedMasqueConfigs.take(6).map { it.port }
        val canTrustFreshMasqueAnchor =
            hasFreshMasqueSuccess &&
                lastPort in preferredCoreMasquePorts &&
                lastPort in topVerifiedMasquePorts
        val preferredVerifiedPort = if (canTrustFreshMasqueAnchor) {
            lastPort
        } else if (preferMessengerMasqueStealth) {
            val messengerMasquePortOrder = listOf(500, 4500, 1701, 443, 8443, 4443, 8095)
            messengerMasquePortOrder.firstOrNull { desiredPort ->
                sortedVerifiedWarpConfigs(clientData).any {
                    it.engine.equals("masque", ignoreCase = true) && it.port == desiredPort
                }
            }
        } else {
            verifiedMasqueConfigs
                .firstOrNull()
                ?.port
                ?.takeIf { it in preferredCoreMasquePorts }
        }
        val preferredVerifiedMode = if (canTrustFreshMasqueAnchor) {
            lastMode.takeIf { it.isNotBlank() && preferredVerifiedPort == lastPort }
        } else if (preferMessengerMasqueStealth) {
            preferredVerifiedPort?.let { preferredPort ->
                rankedMasqueAttempts.firstOrNull { it.port == preferredPort }?.mode?.name
            }
        } else {
            sortedVerifiedWarpConfigs(clientData)
                .firstOrNull {
                    it.engine.equals("masque", ignoreCase = true) &&
                        it.port == preferredVerifiedPort
                }
                ?.mode
        }
        // В 1.10 упрощённый ordinary-wifi shortlist периодически уводил тестовое устройство в
        // нерабочий набор MASQUE попыток. Возвращаем проверенный diversity-алгоритм
        // как основной путь, чтобы сохранить стабильное восстановление через ZT@4443.
        val useSimplifiedOrdinaryWifiMasqueShortlist = false &&
            !legacy32 &&
            !preferMessengerMasqueStealth &&
            !lastRestrictedMobileDetected
        if (useSimplifiedOrdinaryWifiMasqueShortlist) {
            val simpleSelected = mutableListOf<ConnectionAttempt>()

            fun addSimpleFirst(predicate: (ConnectionAttempt) -> Boolean) {
                rankedMasqueAttempts.firstOrNull { predicate(it) && it !in simpleSelected }?.let(simpleSelected::add)
            }

            if (preferredVerifiedPort != null) {
                addSimpleFirst {
                    it.port == preferredVerifiedPort &&
                        it.endpointSource.equals("last-success-exact", ignoreCase = true) &&
                        (preferredVerifiedMode.isNullOrBlank() || it.mode.name.equals(preferredVerifiedMode, ignoreCase = true))
                }
                addSimpleFirst {
                    it.port == preferredVerifiedPort &&
                        it.endpointSource.equals("last-success", ignoreCase = true) &&
                        (preferredVerifiedMode.isNullOrBlank() || it.mode.name.equals(preferredVerifiedMode, ignoreCase = true))
                }
                addSimpleFirst {
                    it.port == preferredVerifiedPort &&
                        it.endpointSource.equals("verified-config", ignoreCase = true) &&
                        (preferredVerifiedMode.isNullOrBlank() || it.mode.name.equals(preferredVerifiedMode, ignoreCase = true))
                }
            }
            addSimpleFirst { it.endpointSource.equals("last-success-exact", ignoreCase = true) }
            addSimpleFirst { it.endpointSource.equals("last-success", ignoreCase = true) }

            val simpleVerifiedPortOrder =
                buildList<Int> {
                    preferredVerifiedPort?.let { add(it) }
                    listOf(1701, 500, 4500).forEach { port ->
                        if (port !in this) add(port)
                    }
                }
            simpleVerifiedPortOrder.forEach { port ->
                addSimpleFirst {
                    it.port == port &&
                        it.endpointSource.equals("verified-config", ignoreCase = true)
                }
            }
            addSimpleFirst { it.port == 443 && it.endpointSource.equals("masque-scan-tcp443", ignoreCase = true) }
            addSimpleFirst { it.port == 443 }

            val simplePreferredModeOrder =
                buildList<String> {
                    preferredVerifiedMode
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::add)
                    listOf("MASQUE-ZT", "MASQUE-CONSUMER").forEach { modeName ->
                        if (none { it.equals(modeName, ignoreCase = true) }) {
                            add(modeName)
                        }
                    }
                }
            simplePreferredModeOrder.forEach { modeName ->
                addSimpleFirst { it.mode.name.equals(modeName, ignoreCase = true) }
            }

            val simplePreferredPortOrder =
                buildList<Int> {
                    preferredVerifiedPort?.let { add(it) }
                    listOf(1701, 500, 443, 4500, 8443, 4443, 8095).forEach { port ->
                        if (port !in this) add(port)
                    }
                }
            simplePreferredPortOrder.forEach { port ->
                addSimpleFirst { it.port == port }
            }

            rankedMasqueAttempts.forEach { attempt ->
                if (attempt !in simpleSelected) {
                    simpleSelected += attempt
                }
            }

            val simpleResult = simpleSelected.take(limit)
            LogManager.log(
                "MASQUE simple ordinary-wifi shortlist: ${
                    simpleResult.joinToString(",") {
                        "${it.mode.name}@${it.endpointHost}:${it.port}(${it.endpointSource})"
                    }
                }"
            )
            return simpleResult
        }
        val effectivePreferredVerifiedPort = preferredVerifiedPort
        val preferConsumerMasqueFamily =
            !preferMessengerMasqueStealth &&
                (
                    preferredVerifiedMode.equals("MASQUE-CONSUMER", ignoreCase = true) ||
                        verifiedMasqueConfigs.firstOrNull()
                            ?.mode
                            .equals("MASQUE-CONSUMER", ignoreCase = true)
                    )

        fun addFirst(
            allowSameEndpointPort: Boolean = false,
            predicate: (ConnectionAttempt) -> Boolean,
        ) {
            rankedMasqueAttempts.firstOrNull {
                predicate(it) &&
                    it !in selected &&
                    (
                        allowSameEndpointPort ||
                            selected.none { existing ->
                                existing.port == it.port &&
                                    existing.endpointHost.equals(it.endpointHost, ignoreCase = true)
                            }
                        )
            }?.let(selected::add)
        }

        fun buildPortOrder(vararg ports: Int?): List<Int> {
            return buildList {
                ports.forEach { port ->
                    if (port != null && port !in this) {
                        add(port)
                    }
                }
            }
        }

        fun buildModeOrder(vararg modes: String?): List<String> {
            return buildList {
                modes.forEach { mode ->
                    val normalized = mode?.trim().orEmpty()
                    if (normalized.isNotBlank() && none { it.equals(normalized, ignoreCase = true) }) {
                        add(normalized)
                    }
                }
            }
        }

        val ordinaryWifiMasquePrimaryCoreOrder = listOf(500, 1701, 4500)
        val preferCoreMasquePortsOnOrdinaryWifi =
            !legacy32 &&
                !preferMessengerMasqueStealth &&
                !lastRestrictedMobileDetected
        val trustedPreferredVerifiedPortAnchor =
            effectivePreferredVerifiedPort?.takeIf { preferredPort ->
                val hasTrustedHistoryForPort = verifiedMasqueConfigs.any { config ->
                    config.port == preferredPort &&
                        (
                            config.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                                config.endpointSource.equals("last-success", ignoreCase = true)
                            )
                }
                if (!hasTrustedHistoryForPort) {
                    false
                } else if (!preferCoreMasquePortsOnOrdinaryWifi) {
                    true
                } else if (preferredPort in ordinaryWifiMasquePrimaryCoreOrder) {
                    true
                } else {
                    verifiedMasqueConfigs.count { it.port in ordinaryWifiMasquePrimaryCoreOrder } < 2
                }
            }
        val hasTrusted4443MasqueConfig = verifiedMasqueConfigs.any { config ->
            config.port == 4443 &&
                (
                    config.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                        config.endpointSource.equals("last-success", ignoreCase = true) ||
                        config.successCount >= 2
                    )
        }
        val allowEarly4443MasquePort =
            hasTrusted4443MasqueConfig ||
                verifiedMasqueConfigs.count { it.port in setOf(500, 1701, 4500) } < 2
        val ordinaryWifiMasquePreferredPortOrder = listOf(500, 1701, 4500, 443, 4443, 8443, 8095)
        val ordinaryWifiMasqueVerifiedPortOrder = buildList {
            trustedPreferredVerifiedPortAnchor?.let { preferredPort ->
                if (preferredPort !in this) add(preferredPort)
            }
            ordinaryWifiMasquePreferredPortOrder.forEach { preferredPort ->
                if (preferredPort == 4443 && !allowEarly4443MasquePort) return@forEach
                val hasTrustedOrVerifiedPort = verifiedMasqueConfigs.any { config ->
                    config.port == preferredPort
                }
                if (hasTrustedOrVerifiedPort && preferredPort !in this) {
                    add(preferredPort)
                }
            }
            verifiedMasqueConfigs
                .map { it.port }
                .filter { port -> port != 4443 || allowEarly4443MasquePort }
                .forEach { verifiedPort ->
                    if (verifiedPort !in this) add(verifiedPort)
                }
            ordinaryWifiMasquePreferredPortOrder.forEach { fallbackPort ->
                if (fallbackPort !in this) add(fallbackPort)
            }
        }
        val frontloadVerifiedPortOrder = if (legacy32) {
            buildPortOrder(effectivePreferredVerifiedPort, 500, 1701, 4500, 443, 8443, 4443, 8095)
        } else if (preferMessengerMasqueStealth) {
            buildPortOrder(effectivePreferredVerifiedPort, 500, 1701, 4500, 443, 8443, 4443, 8095)
        } else {
            ordinaryWifiMasqueVerifiedPortOrder
        }
        val frontloadVerifiedModeOrder = if (preferMessengerMasqueStealth) {
            buildModeOrder(preferredVerifiedMode, "MASQUE-CONSUMER", "MASQUE-ZT")
        } else if (preferConsumerMasqueFamily) {
            buildModeOrder(preferredVerifiedMode, "MASQUE-CONSUMER", "MASQUE-ZT")
        } else {
            buildModeOrder(preferredVerifiedMode, "MASQUE-CONSUMER", "MASQUE-ZT")
        }
        fun frontloadVerifiedModeRank(attempt: ConnectionAttempt): Int {
            val index = frontloadVerifiedModeOrder.indexOfFirst {
                it.equals(attempt.mode.name, ignoreCase = true)
            }
            return if (index >= 0) index else Int.MAX_VALUE
        }
        fun frontloadVerifiedPortRank(attempt: ConnectionAttempt): Int {
            val index = frontloadVerifiedPortOrder.indexOfFirst { it == attempt.port }
            return if (index >= 0) index else Int.MAX_VALUE
        }

        fun materializeVerifiedMasqueAttempt(config: WarpVerifiedConfig): ConnectionAttempt? {
            val preservedSource = config.endpointSource.trim().ifBlank { "verified-config" }
            rankedMasqueAttempts.firstOrNull {
                it.mode.name.equals(config.mode, ignoreCase = true) &&
                    it.endpointHost.equals(config.host, ignoreCase = true) &&
                    it.port == config.port
            }?.let { return it.copy(endpointSource = preservedSource) }

            masqueAttempts.firstOrNull {
                it.mode.name.equals(config.mode, ignoreCase = true) &&
                    it.endpointHost.equals(config.host, ignoreCase = true) &&
                    it.port == config.port
            }?.let { return it.copy(endpointSource = preservedSource) }

            val modeTemplate = masqueAttempts.firstOrNull {
                it.mode.name.equals(config.mode, ignoreCase = true) &&
                    it.mode.engine.equals("masque", ignoreCase = true)
            }?.mode ?: return null

            return ConnectionAttempt(
                endpointHost = config.host,
                port = config.port,
                mode = modeTemplate,
                endpointSource = preservedSource,
            )
        }

        val verifiedCoreMasquePortCount = verifiedMasqueConfigs.count { it.port in setOf(500, 1701, 4500) }
        fun shouldFrontloadVerifiedMasqueConfig(config: WarpVerifiedConfig): Boolean {
            if (legacy32 || preferMessengerMasqueStealth) return true
            val source = config.endpointSource.trim()
            if (config.port in setOf(500, 1701, 4500)) return true
            if (config.port == 4443 &&
                verifiedCoreMasquePortCount >= 2 &&
                !hasTrusted4443MasqueConfig &&
                !source.equals("last-success-exact", ignoreCase = true) &&
                !source.equals("last-success", ignoreCase = true)
            ) {
                return false
            }
            if (source.equals("last-success-exact", ignoreCase = true) || source.equals("last-success", ignoreCase = true)) {
                return true
            }
            if (source.equals("verified-config", ignoreCase = true) &&
                config.successCount < 2 &&
                verifiedCoreMasquePortCount >= 3
            ) {
                return false
            }
            return true
        }

        val verifiedMasqueOrderByKey = verifiedMasqueConfigs
            .withIndex()
            .associate { index ->
                "${index.value.mode.lowercase()}:${index.value.host.trim().lowercase()}:${index.value.port}" to index.index
            }
        fun isTrustedPersistedMasqueAttempt(attempt: ConnectionAttempt): Boolean {
            return attempt.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                attempt.endpointSource.equals("last-success", ignoreCase = true) ||
                attempt.endpointSource.equals("verified-config", ignoreCase = true)
        }
        fun isStrongBundledMasqueAttempt(attempt: ConnectionAttempt): Boolean {
            if (!attempt.endpointSource.equals("bundled-seed", ignoreCase = true)) return false
            if (attempt.port !in setOf(4443, 8443)) return false
            return verifiedMasqueConfigs.firstOrNull {
                it.mode.equals(attempt.mode.name, ignoreCase = true) &&
                    it.host.equals(attempt.endpointHost, ignoreCase = true) &&
                    it.port == attempt.port
            }?.successCount?.let { it >= 2 } == true
        }
        val rankedMasqueOrderByKey = rankedMasqueAttempts
            .withIndex()
            .associate { indexed ->
                masqueAttemptKey(indexed.value) to indexed.index
            }
        val frontloadedVerifiedMasqueAttempts = verifiedMasqueConfigs
            .filter(::shouldFrontloadVerifiedMasqueConfig)
            .mapNotNull(::materializeVerifiedMasqueAttempt)
            .sortedWith(
                compareByDescending<ConnectionAttempt> {
                    when {
                        it.endpointSource.equals("last-success-exact", ignoreCase = true) -> 3
                        it.endpointSource.equals("last-success", ignoreCase = true) -> 2
                        it.endpointSource.equals("verified-config", ignoreCase = true) -> 1
                        else -> 0
                    }
                }.thenBy {
                    if (
                        !legacy32 &&
                        !preferMessengerMasqueStealth &&
                        it.endpointSource.equals("verified-config", ignoreCase = true)
                    ) {
                        verifiedMasqueOrderByKey[masqueAttemptKey(it)] ?: Int.MAX_VALUE
                    } else {
                        Int.MAX_VALUE
                    }
                }.thenBy {
                    frontloadVerifiedPortRank(it)
                }.thenByDescending {
                    rankedMasqueScoreByKey[masqueAttemptKey(it)] ?: Double.NEGATIVE_INFINITY
                }.thenBy {
                    frontloadVerifiedModeRank(it)
                }.thenBy {
                    rankedMasqueOrderByKey[masqueAttemptKey(it)] ?: Int.MAX_VALUE
                }.thenBy {
                    verifiedMasqueOrderByKey[masqueAttemptKey(it)] ?: Int.MAX_VALUE
                }
            )
            .distinctBy {
                "${it.mode.name.lowercase()}:${it.endpointHost.trim().lowercase()}:${it.port}"
            }
        LogManager.log(
            "MASQUE verified order: ${
                verifiedMasqueConfigs.take(6).joinToString(",") {
                    "${it.mode}@${it.host}:${it.port}"
                }
            }"
        )
        LogManager.log(
            "MASQUE frontloaded order: ${
                frontloadedVerifiedMasqueAttempts.take(6).joinToString(",") {
                    "${it.mode.name}@${it.endpointHost}:${it.port}"
                }
            }"
        )

        val uniquePortFrontloadedMasqueAttempts = buildList<ConnectionAttempt> {
            val primaryAttempt = frontloadedVerifiedMasqueAttempts.firstOrNull()
            val primarySiblingAttempt = primaryAttempt?.let { anchor ->
                frontloadedVerifiedMasqueAttempts.firstOrNull { candidate ->
                    candidate.port == anchor.port &&
                        candidate.endpointHost.equals(anchor.endpointHost, ignoreCase = true) &&
                        !candidate.mode.name.equals(anchor.mode.name, ignoreCase = true)
                }
            }

            primaryAttempt?.let(::add)

            val usedPorts = linkedSetOf<Int>()
            primaryAttempt?.let { usedPorts += it.port }
            frontloadedVerifiedMasqueAttempts.forEach { attempt ->
                if (usedPorts.add(attempt.port) && attempt !in this) {
                    add(attempt)
                }
            }
            primarySiblingAttempt?.takeIf { it !in this }?.let(::add)
            frontloadedVerifiedMasqueAttempts.forEach { attempt ->
                if (attempt !in this) {
                    add(attempt)
                }
            }
        }
        LogManager.log(
            "MASQUE unique-port order: ${
                uniquePortFrontloadedMasqueAttempts.take(6).joinToString(",") {
                    "${it.mode.name}@${it.endpointHost}:${it.port}"
                }
            }"
        )

        uniquePortFrontloadedMasqueAttempts.forEach { verifiedAttempt ->
            if (selected.size >= limit) return@forEach
            if (selected.none {
                    it.endpointHost.equals(verifiedAttempt.endpointHost, ignoreCase = true) &&
                        it.port == verifiedAttempt.port &&
                        it.mode.name.equals(verifiedAttempt.mode.name, ignoreCase = true)
                }
            ) {
                selected += verifiedAttempt
            }
        }

        if (!legacy32 && !preferMessengerMasqueStealth && selected.isNotEmpty()) {
            val preferred1701ZtAttempt = uniquePortFrontloadedMasqueAttempts.firstOrNull { candidate ->
                candidate.port == 1701 &&
                    candidate.mode.name.equals("MASQUE-ZT", ignoreCase = true) &&
                    isTrustedPersistedMasqueAttempt(candidate)
            }
            val preferred1701ConsumerAttempt = uniquePortFrontloadedMasqueAttempts.firstOrNull { candidate ->
                candidate.port == 1701 &&
                    candidate.mode.name.equals("MASQUE-CONSUMER", ignoreCase = true) &&
                    isTrustedPersistedMasqueAttempt(candidate)
            }
            val trusted1701Anchor = preferred1701ZtAttempt ?: preferred1701ConsumerAttempt
            if (trusted1701Anchor != null) {
                val selected1701Index = selected.indexOfFirst { it.port == 1701 }
                if (selected1701Index >= 0) {
                    val current1701 = selected[selected1701Index]
                    if (
                        trusted1701Anchor.mode.name.equals("MASQUE-ZT", ignoreCase = true) &&
                        !current1701.mode.name.equals("MASQUE-ZT", ignoreCase = true)
                    ) {
                        selected[selected1701Index] = trusted1701Anchor.copy(
                            endpointSource = current1701.endpointSource
                        )
                    }
                } else if (trusted1701Anchor !in selected) {
                    selected.add(minOf(1, selected.size), trusted1701Anchor)
                }
            }
            val trusted1701Sibling = when {
                trusted1701Anchor?.mode?.name.equals("MASQUE-ZT", ignoreCase = true) ->
                    preferred1701ConsumerAttempt
                else ->
                    preferred1701ZtAttempt
            }
            trusted1701Sibling
                ?.takeIf { it !in selected }
                ?.let { sibling ->
                    selected.add(minOf(2, selected.size), sibling)
                }
            val preferred4500Sibling = rankedMasqueAttempts.firstOrNull { candidate ->
                candidate.port == 4500 &&
                    candidate.mode.name.equals("MASQUE-ZT", ignoreCase = true) &&
                    (
                        candidate.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                            candidate.endpointSource.equals("last-success", ignoreCase = true) ||
                            candidate.endpointSource.equals("verified-config", ignoreCase = true)
                        )
            }
            val selected4500Index = selected.indexOfFirst { it.port == 4500 }
            if (preferred4500Sibling != null && selected4500Index >= 0 &&
                !selected[selected4500Index].mode.name.equals("MASQUE-ZT", ignoreCase = true)
            ) {
                selected[selected4500Index] = preferred4500Sibling.copy(
                    endpointSource = selected[selected4500Index].endpointSource
                )
            }
            val preferredTrusted443Modes = if (!preferMessengerMasqueStealth) {
                listOf("MASQUE-CONSUMER", "MASQUE-ZT")
            } else {
                listOf("MASQUE-ZT", "MASQUE-CONSUMER")
            }
            preferredTrusted443Modes.firstNotNullOfOrNull { preferredMode ->
                uniquePortFrontloadedMasqueAttempts.firstOrNull { candidate ->
                    candidate.port == 443 &&
                        isTrustedPersistedMasqueAttempt(candidate) &&
                        candidate.mode.name.equals(preferredMode, ignoreCase = true) &&
                        candidate !in selected
                }
            } ?: uniquePortFrontloadedMasqueAttempts.firstOrNull { candidate ->
                candidate.port == 443 &&
                    isTrustedPersistedMasqueAttempt(candidate) &&
                    candidate !in selected
            }?.let { trusted443Attempt ->
                val insertAt = minOf(2, selected.size)
                selected.add(insertAt, trusted443Attempt)
            }
            uniquePortFrontloadedMasqueAttempts.firstOrNull { candidate ->
                candidate.port in setOf(4443, 8443, 443) &&
                    (
                        isTrustedPersistedMasqueAttempt(candidate) ||
                            isStrongBundledMasqueAttempt(candidate)
                        ) &&
                    (candidate.port != 4443 || (allowEarly4443MasquePort && verifiedCoreMasquePortCount < 2) ||
                        candidate.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                        candidate.endpointSource.equals("last-success", ignoreCase = true) ||
                        isStrongBundledMasqueAttempt(candidate)) &&
                    candidate !in selected
            }?.let { trustedExoticAttempt ->
                val insertAt = minOf(3, selected.size)
                selected.add(insertAt, trustedExoticAttempt)
            }
            val anchorHost = selected.first().endpointHost.trim().lowercase()
            val earlyHosts = selected.take(3).map { it.endpointHost.trim().lowercase() }.distinct()
            val hasTrusted443Candidate = uniquePortFrontloadedMasqueAttempts.any {
                it.port == 443 &&
                    isTrustedPersistedMasqueAttempt(it)
            }
            if (earlyHosts.size == 1) {
                rankedMasqueAttempts.firstOrNull { candidate ->
                    candidate.endpointHost.trim().lowercase() != anchorHost &&
                        candidate.port in setOf(4443, 8443) &&
                        candidate !in selected
                }?.let { alternateHostExoticAttempt ->
                    val insertAt = minOf(4, selected.size)
                    selected.add(insertAt, alternateHostExoticAttempt)
                }
                if (!hasTrusted443Candidate || selected.any { it.port == 443 }) {
                    rankedMasqueAttempts.firstOrNull { candidate ->
                        candidate.endpointHost.trim().lowercase() != anchorHost &&
                            candidate.port in setOf(500, 1701, 4500, 4443, 8443) &&
                            candidate !in selected
                    }?.let { alternateHostAttempt ->
                        val insertAt = minOf(4, selected.size)
                        selected.add(insertAt, alternateHostAttempt)
                    }
                }
            }
        }

        if (effectivePreferredVerifiedPort != null) {
            addFirst {
                it.port == effectivePreferredVerifiedPort &&
                    it.endpointSource.equals("last-success-exact", ignoreCase = true) &&
                    (preferredVerifiedMode.isNullOrBlank() || it.mode.name.equals(preferredVerifiedMode, ignoreCase = true))
            }
            addFirst {
                it.port == effectivePreferredVerifiedPort &&
                    it.endpointSource.equals("last-success", ignoreCase = true) &&
                    (preferredVerifiedMode.isNullOrBlank() || it.mode.name.equals(preferredVerifiedMode, ignoreCase = true))
            }
            addFirst {
                it.port == effectivePreferredVerifiedPort &&
                    it.endpointSource.equals("verified-config", ignoreCase = true) &&
                    (preferredVerifiedMode.isNullOrBlank() || it.mode.name.equals(preferredVerifiedMode, ignoreCase = true))
            }
        }
        addFirst { it.endpointSource.equals("last-success-exact", ignoreCase = true) }
        addFirst { it.endpointSource.equals("last-success", ignoreCase = true) }

        if (preferMessengerMasqueStealth) {
            sortedVerifiedWarpConfigs(clientData)
                .filter { it.engine.equals("masque", ignoreCase = true) }
                .forEach { config ->
                    addFirst {
                        it.endpointSource.equals("verified-config", ignoreCase = true) &&
                            it.mode.name.equals(config.mode, ignoreCase = true) &&
                            it.endpointHost.equals(config.host, ignoreCase = true) &&
                            it.port == config.port
                    }
                    if (selected.size >= limit) {
                        return@forEach
                    }
                }
        }

        // On older arm32 devices, keep QUIC-first behavior, but let the freshest real
        // winner (for example port 500) override the old static 1701-first bias.
        val verifiedPortOrder = if (legacy32) {
            buildPortOrder(effectivePreferredVerifiedPort, 500, 1701, 4500)
        } else if (preferMessengerMasqueStealth) {
            buildPortOrder(effectivePreferredVerifiedPort, 500, 4500, 1701, 443)
        } else {
            ordinaryWifiMasqueVerifiedPortOrder
        }
        verifiedPortOrder.forEach { port ->
            addFirst {
                it.port == port &&
                    it.endpointSource.equals("verified-config", ignoreCase = true)
            }
        }
        if (!legacy32 && !preferMessengerMasqueStealth) {
            // Keep one explicit TCP 443 candidate only after core verified QUIC attempts
            // had a chance to occupy the front of the shortlist.
            if (selected.count { it.endpointSource.equals("verified-config", ignoreCase = true) } < 2) {
                addFirst { it.port == 443 && it.endpointSource.equals("masque-scan-tcp443", ignoreCase = true) }
            }
            if (selected.size < 3) {
                addFirst { it.port == 443 }
            }
        }
        if (legacy32) {
            addFirst {
                it.port == 443 &&
                    it.endpointSource.equals("verified-config", ignoreCase = true)
            }
            addFirst { it.port == 443 && it.endpointSource.equals("masque-scan-tcp443", ignoreCase = true) }
            addFirst { it.port == 443 }
        }

        val preferredModeOrder = if (preferMessengerMasqueStealth) {
            buildModeOrder(preferredVerifiedMode, "MASQUE-CONSUMER", "MASQUE-ZT")
        } else if (legacy32) {
            buildModeOrder(preferredVerifiedMode, "MASQUE-ZT", "MASQUE-CONSUMER")
        } else {
            emptyList()
        }
        preferredModeOrder.forEach { modeName ->
            addFirst { it.mode.name.equals(modeName, ignoreCase = true) }
        }

        val preferredPortOrder = if (legacy32) {
            buildPortOrder(effectivePreferredVerifiedPort, 500, 1701, 4500, 443, 8443, 4443, 8095)
        } else if (preferMessengerMasqueStealth) {
            buildPortOrder(effectivePreferredVerifiedPort, 500, 4500, 1701, 443, 8443, 4443, 8095)
        } else {
            ordinaryWifiMasqueVerifiedPortOrder
        }
        preferredPortOrder.forEach { port ->
            addFirst { it.port == port }
        }

        rankedMasqueAttempts.forEach { attempt ->
            if (attempt !in selected) {
                selected += attempt
            }
        }

        val dynamicMasquePortPreference = buildList {
            val exactFrontloadedPorts = uniquePortFrontloadedMasqueAttempts
                .filter {
                    it.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                        it.endpointSource.equals("last-success", ignoreCase = true)
                }
                .map { it.port }
                .distinct()
            val orderedFrontloadedPorts = uniquePortFrontloadedMasqueAttempts
                .map { it.port }
                .distinct()
            val coreFallbackPorts = if (preferMessengerMasqueStealth) {
                listOf(500, 4500, 1701)
            } else {
                listOf(500, 1701, 4500)
            }
            val exoticFallbackPorts = if (preferMessengerMasqueStealth) {
                listOf(443, 8443, 4443, 8095)
            } else {
                listOf(443, 4443, 8443, 8095)
            }
            if (preferCoreMasquePortsOnOrdinaryWifi) {
                exactFrontloadedPorts
                    .filter { it in ordinaryWifiMasquePrimaryCoreOrder }
                    .forEach { port ->
                        if (port !in this) add(port)
                    }
                coreFallbackPorts.forEach { port ->
                    if (port !in this) add(port)
                }
                orderedFrontloadedPorts
                    .filter { it in ordinaryWifiMasquePrimaryCoreOrder }
                    .forEach { port ->
                        if (port !in this) add(port)
                    }
                exoticFallbackPorts.forEach { port ->
                    if (port !in this) add(port)
                }
                exactFrontloadedPorts
                    .filter { it !in ordinaryWifiMasquePrimaryCoreOrder }
                    .forEach { port ->
                        if (port !in this) add(port)
                    }
                orderedFrontloadedPorts
                    .filter { it !in ordinaryWifiMasquePrimaryCoreOrder }
                    .forEach { port ->
                        if (port !in this) add(port)
                    }
            } else {
                exactFrontloadedPorts.forEach { port ->
                    if (port !in this) add(port)
                }
                coreFallbackPorts.forEach { port ->
                    if (port !in this) add(port)
                }
                orderedFrontloadedPorts
                    .filter { it in ordinaryWifiMasquePrimaryCoreOrder }
                    .forEach { port ->
                        if (port !in this) add(port)
                    }
                exoticFallbackPorts.forEach { port ->
                    if (port !in this) add(port)
                }
                orderedFrontloadedPorts
                    .filter { it !in ordinaryWifiMasquePrimaryCoreOrder }
                    .forEach { port ->
                        if (port !in this) add(port)
                    }
            }
            val fallbackPorts = if (preferMessengerMasqueStealth) {
                listOf(500, 4500, 1701, 443, 8443, 4443, 8095)
            } else {
                listOf(500, 1701, 4500, 443, 4443, 8443, 8095)
            }
            fallbackPorts.forEach { port ->
                if (port !in this) add(port)
            }
        }

        fun preferredMasqueModesForPort(port: Int): List<String> {
            val exactPortWinner = uniquePortFrontloadedMasqueAttempts
                .firstOrNull {
                    it.port == port &&
                        it.endpointSource.equals("last-success-exact", ignoreCase = true)
                }
            val frontloadedPreferredMode = exactPortWinner
                ?.mode
                ?.name
                .orEmpty()
            if (frontloadedPreferredMode.isNotBlank()) {
                return if (frontloadedPreferredMode.equals("MASQUE-ZT", ignoreCase = true)) {
                    listOf("MASQUE-ZT", "MASQUE-CONSUMER")
                } else {
                    listOf("MASQUE-CONSUMER", "MASQUE-ZT")
                }
            }
            if (legacy32) {
                return when (port) {
                    500 -> listOf("MASQUE-CONSUMER", "MASQUE-ZT")
                    1701 -> listOf("MASQUE-ZT", "MASQUE-CONSUMER")
                    4500 -> listOf("MASQUE-CONSUMER", "MASQUE-ZT")
                    443, 4443, 8443, 8095 -> listOf("MASQUE-ZT", "MASQUE-CONSUMER")
                    else -> listOf("MASQUE-ZT", "MASQUE-CONSUMER")
                }
            }
            if (!preferMessengerMasqueStealth && preferConsumerMasqueFamily) {
                return listOf("MASQUE-CONSUMER", "MASQUE-ZT")
            }
            return when (port) {
                500 -> listOf("MASQUE-CONSUMER", "MASQUE-ZT")
                1701 -> listOf("MASQUE-ZT", "MASQUE-CONSUMER")
                4500 -> listOf("MASQUE-ZT", "MASQUE-CONSUMER")
                // On ordinary Wi-Fi, verified MASQUE-CONSUMER@443 is the most reliable
                // exotic fallback on real devices; do not let diversity heuristics demote it
                // behind ZT unless we have an exact last-success winner for the same port.
                443 -> listOf("MASQUE-CONSUMER", "MASQUE-ZT")
                else -> listOf("MASQUE-ZT", "MASQUE-CONSUMER")
            }
        }

        fun compactMasqueShortlist(items: List<ConnectionAttempt>): List<ConnectionAttempt> {
            val preferredCoreOrder = dynamicMasquePortPreference
            val preferredSources = listOf(
                "last-success-exact",
                "last-success",
                "verified-config",
                "bundled-seed",
                "known-anycast",
                "neighbor-anycast",
                "masque-scan-tcp443",
                "masque-scan",
                "api",
                "api-resolved",
            )
            val pool = (items + rankedMasqueAttempts).distinct()
            val compacted = mutableListOf<ConnectionAttempt>()
            val claimedCorePorts = mutableSetOf<Int>()
            val useLegacyOrdinaryWifiMasqueShortlist =
                !legacy32 &&
                    !preferMessengerMasqueStealth &&
                    !lastRestrictedMobileDetected
            val useTrustedVerifiedReplay =
                !useLegacyOrdinaryWifiMasqueShortlist &&
                    !lastRestrictedMobileDetected &&
                    frontloadedVerifiedMasqueAttempts.isNotEmpty()

            if (useTrustedVerifiedReplay) {
                val replay = mutableListOf<ConnectionAttempt>()
                var collapseReplayToEarlyCrossHostSlice = false
                fun appendReplay(candidate: ConnectionAttempt?) {
                    if (candidate != null && candidate !in replay) {
                        replay += candidate
                    }
                }

                val conservativeReplayPortOrder =
                    if (!legacy32 && !lastRestrictedMobileDetected) {
                        if (preferMessengerMasqueStealth) {
                            listOf(500, 4500, 1701, 443, 8443, 4443, 8095)
                        } else {
                            ordinaryWifiMasqueVerifiedPortOrder
                        }
                    } else {
                        emptyList()
                }
                val exactReplayAttempt = frontloadedVerifiedMasqueAttempts.firstOrNull {
                    it.endpointSource.equals("last-success-exact", ignoreCase = true)
                }
                val hasTrustedCoreReplayCandidate =
                    (frontloadedVerifiedMasqueAttempts + rankedMasqueAttempts).any { candidate ->
                        candidate.port in setOf(500, 1701, 4500) &&
                            (isTrustedPersistedMasqueAttempt(candidate) || isStrongBundledMasqueAttempt(candidate))
                    }
                val shouldPreferStrongExoticReplay =
                    !legacy32 &&
                        !preferMessengerMasqueStealth &&
                        (
                            currentCycleStableSuccess?.port in setOf(4443, 8443, 443) ||
                                exactReplayAttempt?.port in setOf(4443, 8443, 443) ||
                                (!hasTrustedCoreReplayCandidate &&
                                    ordinaryWifiMasqueVerifiedPortOrder.firstOrNull() in setOf(4443, 8443, 443))
                            )
                val ordinaryWifiStrongExoticReplayAttempt =
                    if (shouldPreferStrongExoticReplay) {
                        val strongExoticPool = (frontloadedVerifiedMasqueAttempts + rankedMasqueAttempts)
                            .distinct()
                            .filter { candidate ->
                                candidate.port in setOf(4443, 8443, 443) &&
                                    (isTrustedPersistedMasqueAttempt(candidate) || isStrongBundledMasqueAttempt(candidate))
                            }
                        listOf(4443, 8443, 443).firstNotNullOfOrNull { preferredPort ->
                            strongExoticPool.firstOrNull { candidate ->
                                candidate.port == preferredPort &&
                                    (
                                        preferredPort != 8443 ||
                                            candidate.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                                            candidate.endpointSource.equals("last-success", ignoreCase = true)
                                        )
                            }
                        } ?: listOf(4443, 8443, 443).firstNotNullOfOrNull { preferredPort ->
                            strongExoticPool.firstOrNull { candidate ->
                                candidate.port == preferredPort
                            }
                        } ?: strongExoticPool.firstOrNull()
                    } else {
                        null
                    }
                val primaryReplayAttempt =
                    ordinaryWifiStrongExoticReplayAttempt
                        ?: frontloadedVerifiedMasqueAttempts.firstOrNull()
                        ?: conservativeReplayPortOrder.firstNotNullOfOrNull { preferredPort ->
                            frontloadedVerifiedMasqueAttempts.firstOrNull { candidate ->
                                candidate.port == preferredPort
                            }
                        }
                appendReplay(primaryReplayAttempt)

                val preferredReplayExoticPorts = if (preferMessengerMasqueStealth) {
                    listOf(4443, 443, 8443)
                } else if (legacy32) {
                    emptyList()
                } else {
                    listOf(4443, 443, 8443)
                }
                val preferredPairedExoticPorts =
                    if (!legacy32 && !preferMessengerMasqueStealth) {
                        when (primaryReplayAttempt?.port) {
                            8443 -> listOf(4443, 443)
                            4443 -> listOf(443, 8443)
                            443 -> listOf(4443, 8443)
                            else -> emptyList()
                        }
                    } else {
                        emptyList()
                    }
                val primaryReplayHost = primaryReplayAttempt?.endpointHost?.trim().orEmpty()
                if (primaryReplayHost.isNotBlank()) {
                    val preferredReplayModes =
                        if (primaryReplayAttempt?.mode?.name.equals("MASQUE-ZT", ignoreCase = true)) {
                            listOf("MASQUE-ZT", "MASQUE-CONSUMER")
                        } else {
                            listOf("MASQUE-CONSUMER", "MASQUE-ZT")
                        }
                    val shouldWarmSameHostExoticReplay =
                        primaryReplayAttempt?.port in preferredReplayExoticPorts ||
                            ordinaryWifiMasqueVerifiedPortOrder.firstOrNull() in preferredReplayExoticPorts
                    val sameHostExoticReplayPortOrder =
                        (preferredPairedExoticPorts + preferredReplayExoticPorts).distinct()
                    val sameHostExoticReplayCandidate =
                        if (shouldWarmSameHostExoticReplay) {
                            sameHostExoticReplayPortOrder.firstNotNullOfOrNull { preferredPort ->
                                preferredReplayModes.firstNotNullOfOrNull { preferredMode ->
                                    (frontloadedVerifiedMasqueAttempts + rankedMasqueAttempts).firstOrNull { candidate ->
                                        candidate !in replay &&
                                            candidate.port == preferredPort &&
                                            candidate.endpointHost.trim().equals(primaryReplayHost, ignoreCase = true) &&
                                            candidate.mode.name.equals(preferredMode, ignoreCase = true)
                                    }
                                } ?: (frontloadedVerifiedMasqueAttempts + rankedMasqueAttempts).firstOrNull { candidate ->
                                    candidate !in replay &&
                                        candidate.port == preferredPort &&
                                        candidate.endpointHost.trim().equals(primaryReplayHost, ignoreCase = true)
                                }
                            }
                        } else {
                            null
                        }
                    appendReplay(sameHostExoticReplayCandidate)
                    appendReplay(
                        sameHostExoticReplayCandidate?.let { exotic ->
                            (frontloadedVerifiedMasqueAttempts + rankedMasqueAttempts).firstOrNull { candidate ->
                                candidate !in replay &&
                                    candidate.port == exotic.port &&
                                    candidate.endpointHost.trim().equals(primaryReplayHost, ignoreCase = true) &&
                                    !candidate.mode.name.equals(exotic.mode.name, ignoreCase = true)
                            }
                        }
                    )
                    appendReplay(
                        preferredPairedExoticPorts.firstNotNullOfOrNull { preferredPort ->
                            preferredReplayModes.firstNotNullOfOrNull { preferredMode ->
                                (frontloadedVerifiedMasqueAttempts + rankedMasqueAttempts).firstOrNull { candidate ->
                                    candidate !in replay &&
                                        candidate.port == preferredPort &&
                                        candidate.endpointHost.trim().equals(primaryReplayHost, ignoreCase = true) &&
                                        candidate.mode.name.equals(preferredMode, ignoreCase = true)
                                }
                            } ?: (frontloadedVerifiedMasqueAttempts + rankedMasqueAttempts).firstOrNull { candidate ->
                                candidate !in replay &&
                                    candidate.port == preferredPort &&
                                    candidate.endpointHost.trim().equals(primaryReplayHost, ignoreCase = true)
                            }
                        }
                    )
                }

                val shouldWarmPrimarySibling =
                    primaryReplayAttempt != null &&
                        exactReplayAttempt != null &&
                        primaryReplayAttempt.port in setOf(500, 1701, 4500) &&
                        !primaryReplayAttempt.endpointSource.equals("last-success-exact", ignoreCase = true)

                if (shouldWarmPrimarySibling) {
                    appendReplay(
                        frontloadedVerifiedMasqueAttempts.firstOrNull { candidate ->
                            primaryReplayAttempt != null &&
                                candidate.port == primaryReplayAttempt.port &&
                                candidate.endpointHost.equals(primaryReplayAttempt.endpointHost, ignoreCase = true) &&
                                !candidate.mode.name.equals(primaryReplayAttempt.mode.name, ignoreCase = true)
                        }
                    )
                }

                val replayUsedPorts = linkedSetOf<Int>()
                replay.mapTo(replayUsedPorts) { it.port }
                frontloadedVerifiedMasqueAttempts.forEach { candidate ->
                    if (replay.size >= maxOf(limit, 4)) return@forEach
                    if (replayUsedPorts.add(candidate.port)) {
                        appendReplay(candidate)
                    }
                }

                if (replay.none { it.port in setOf(500, 1701, 4500) }) {
                    appendReplay(
                        (frontloadedVerifiedMasqueAttempts + rankedMasqueAttempts).firstOrNull { candidate ->
                            candidate.port in setOf(500, 1701, 4500) && candidate !in replay
                        }
                    )
                }

                val leadingReplayHost = replay.firstOrNull()?.endpointHost?.trim().orEmpty()
                if (leadingReplayHost.isNotBlank()) {
                    val earlyReplay = replay.take(3)
                    val sameHostCoreEarlyCount = earlyReplay.count {
                        it.endpointHost.trim().equals(leadingReplayHost, ignoreCase = true) &&
                            it.port in setOf(500, 1701, 4500)
                    }
                    val crossHostThreshold = if (preferMessengerMasqueStealth) 2 else 3
                    if (sameHostCoreEarlyCount >= crossHostThreshold) {
                        val preferredCrossHostPorts = if (preferMessengerMasqueStealth) {
                            listOf(500, 4500, 1701, 443)
                        } else if (legacy32) {
                            listOf(500, 1701, 4500, 443, 4443, 8443)
                        } else {
                            listOf(4443, 8443, 500, 1701, 4500, 443)
                        }
                        val crossHostReplayCandidate =
                            preferredSources.firstNotNullOfOrNull { source ->
                                preferredCrossHostPorts.firstNotNullOfOrNull { preferredPort ->
                                    rankedMasqueAttempts.firstOrNull { candidate ->
                                        candidate !in replay &&
                                            candidate.port == preferredPort &&
                                            candidate.endpointSource.equals(source, ignoreCase = true) &&
                                            !candidate.endpointHost.trim().equals(leadingReplayHost, ignoreCase = true)
                                    }
                                }
                            } ?: rankedMasqueAttempts.firstOrNull { candidate ->
                                candidate !in replay &&
                                    !candidate.endpointHost.trim().equals(leadingReplayHost, ignoreCase = true) &&
                                    candidate.endpointSource.lowercase() in preferredSources
                            } ?: rankedMasqueAttempts.firstOrNull { candidate ->
                                candidate !in replay &&
                                    !candidate.endpointHost.trim().equals(leadingReplayHost, ignoreCase = true)
                            }
                        if (crossHostReplayCandidate != null) {
                            replay.removeAll { candidate ->
                                candidate.endpointHost.trim().equals(leadingReplayHost, ignoreCase = true) &&
                                    candidate.port in setOf(443, 8443, 4443, 8095)
                            }
                            val insertionIndex = minOf(if (preferMessengerMasqueStealth) 1 else 1, replay.size)
                            replay.add(insertionIndex, crossHostReplayCandidate)
                            collapseReplayToEarlyCrossHostSlice = true
                        }
                    }
                }

                if (!legacy32 && leadingReplayHost.isNotBlank() && replay.size < maxOf(limit, 5)) {
                    val preferredExoticPorts = if (preferMessengerMasqueStealth) {
                        listOf(4443, 443, 8443)
                    } else {
                        listOf(4443, 8443, 443)
                    }
                    val crossHostExoticCandidate =
                        preferredSources.firstNotNullOfOrNull { source ->
                            preferredExoticPorts.firstNotNullOfOrNull { preferredPort ->
                                rankedMasqueAttempts.firstOrNull { candidate ->
                                    candidate !in replay &&
                                        candidate.port == preferredPort &&
                                        candidate.endpointSource.equals(source, ignoreCase = true) &&
                                        !candidate.endpointHost.trim().equals(leadingReplayHost, ignoreCase = true)
                                    }
                            }
                        } ?: preferredExoticPorts.firstNotNullOfOrNull { preferredPort ->
                            rankedMasqueAttempts.firstOrNull { candidate ->
                                candidate !in replay &&
                                    candidate.port == preferredPort &&
                                    !candidate.endpointHost.trim().equals(leadingReplayHost, ignoreCase = true)
                            }
                        } ?: rankedMasqueAttempts.firstOrNull { candidate ->
                            candidate !in replay &&
                                candidate.port in preferredExoticPorts &&
                                !candidate.endpointHost.trim().equals(leadingReplayHost, ignoreCase = true)
                        }
                    if (crossHostExoticCandidate != null) {
                        val exoticInsertIndex = minOf(if (preferMessengerMasqueStealth) 2 else replay.size, replay.size)
                        replay.add(exoticInsertIndex, crossHostExoticCandidate)
                    }
                }

                if (!legacy32 && replay.none { it.port in setOf(4443, 443, 8443) }) {
                    val preferredExoticPorts = if (preferMessengerMasqueStealth) {
                        listOf(4443, 443, 8443)
                    } else {
                        listOf(4443, 8443, 443)
                    }
                    val earlyExoticCandidate =
                        (frontloadedVerifiedMasqueAttempts + rankedMasqueAttempts)
                            .distinct()
                            .firstOrNull { candidate ->
                                candidate !in replay &&
                                    candidate.port in preferredExoticPorts
                            }
                    if (earlyExoticCandidate != null) {
                        if (replay.isEmpty()) {
                            replay += earlyExoticCandidate
                        } else {
                            val exoticInsertIndex = minOf(2, replay.size)
                            replay.add(exoticInsertIndex, earlyExoticCandidate)
                            val maxReplaySize = maxOf(limit, 5)
                            while (replay.size > maxReplaySize) {
                                val dropIndex =
                                    replay.indexOfLast { candidate ->
                                        candidate.port in setOf(500, 1701, 4500) &&
                                            candidate.endpointHost.trim().equals(leadingReplayHost, ignoreCase = true) &&
                                            candidate != earlyExoticCandidate
                                    }.takeIf { it >= 0 } ?: (replay.size - 1)
                                replay.removeAt(dropIndex)
                            }
                        }
                    }
                }

                val orderedReplay = if (collapseReplayToEarlyCrossHostSlice) {
                    replay.toList()
                } else {
                    (replay + items + rankedMasqueAttempts).distinct()
                }
                val stabilizedReplay =
                    if (legacy32) {
                        val preferredLegacyPorts = listOf(500, 1701, 4500, 443, 4443, 8443, 8095)
                        val legacyPrefix = buildList {
                            preferredLegacyPorts.forEach { port ->
                                val preferredModes = preferredMasqueModesForPort(port)
                                val candidate =
                                    preferredModes.firstNotNullOfOrNull { modeName ->
                                        orderedReplay.firstOrNull { attempt ->
                                            attempt !in this &&
                                                attempt.port == port &&
                                                attempt.mode.name.equals(modeName, ignoreCase = true)
                                        }
                                    } ?: orderedReplay.firstOrNull { attempt ->
                                        attempt !in this && attempt.port == port
                                    }
                                if (candidate != null) {
                                    add(candidate)
                                }
                            }
                        }
                        (legacyPrefix + orderedReplay).distinct()
                    } else {
                        orderedReplay
                    }
                LogManager.log(
                    "MASQUE trusted verified replay: ${
                        stabilizedReplay.take(6).joinToString(",") {
                            "${it.mode.name}@${it.endpointHost}:${it.port}(${it.endpointSource})"
                        }
                    }"
                )
                return stabilizedReplay
            }

            val usePixelMasqueCompatibilityWarmup =
                android.os.Build.MANUFACTURER.equals("Google", ignoreCase = true) &&
                    !legacy32 &&
                    !clientData.shouldForceMessengerWarpPriority() &&
                    !lastRestrictedMobileDetected &&
                    pool.any {
                        it.endpointSource.equals("verified-config", ignoreCase = true) &&
                            it.port == 1701
                    } &&
                    pool.any {
                        it.endpointSource.equals("verified-config", ignoreCase = true) &&
                            it.port == 500
                    }

            if (usePixelMasqueCompatibilityWarmup) {
                val pixelWarmupPool =
                    (uniquePortFrontloadedMasqueAttempts + frontloadedVerifiedMasqueAttempts + rankedMasqueAttempts + pool)
                        .distinct()
                fun pickPixelWarmupAttempt(
                    port: Int,
                    preferredModes: List<String>,
                ): ConnectionAttempt? {
                    return preferredSources.firstNotNullOfOrNull { source ->
                        preferredModes.firstNotNullOfOrNull { preferredMode ->
                            pixelWarmupPool.firstOrNull { attempt ->
                                attempt.port == port &&
                                    attempt.endpointSource.equals(source, ignoreCase = true) &&
                                    attempt.mode.name.equals(preferredMode, ignoreCase = true)
                            }
                        } ?: pixelWarmupPool.firstOrNull { attempt ->
                            attempt.port == port &&
                                attempt.endpointSource.equals(source, ignoreCase = true)
                        }
                    } ?: pixelWarmupPool.firstOrNull { attempt -> attempt.port == port }
                }

                val preferredPixelWarmupPorts = listOf(4443, 8095, 8443, 443, 500, 1701, 4500)
                val exportedPixelWarmupAttempts = clientData.getWarpVerifiedExportSnapshot("default")
                    .filter { config ->
                        !config.manual &&
                            config.engine.equals("masque", ignoreCase = true)
                    }
                    .mapNotNull(::materializeVerifiedMasqueAttempt)
                    .take(8)
                val pixelWarmupCandidatePool = (exportedPixelWarmupAttempts + pixelWarmupPool).distinct()

                fun pixelPreferredModes(port: Int): List<String> {
                    return when (port) {
                        500 -> listOf("MASQUE-ZT", "MASQUE-CONSUMER")
                        1701 -> listOf("MASQUE-ZT", "MASQUE-CONSUMER")
                        4500 -> listOf("MASQUE-CONSUMER", "MASQUE-ZT")
                        443, 4443, 8443, 8095 -> listOf("MASQUE-CONSUMER", "MASQUE-ZT")
                        else -> listOf("MASQUE-CONSUMER", "MASQUE-ZT")
                    }
                }

                val pixelWarmup = buildList {
                    preferredPixelWarmupPorts.forEach { port ->
                        if (size >= 8) return@forEach
                        val preferredCandidate = preferredSources.firstNotNullOfOrNull { source ->
                            pixelPreferredModes(port).firstNotNullOfOrNull { preferredMode ->
                                pixelWarmupCandidatePool.firstOrNull { attempt ->
                                    attempt.port == port &&
                                        attempt.endpointSource.equals(source, ignoreCase = true) &&
                                        attempt.mode.name.equals(preferredMode, ignoreCase = true)
                                }
                            } ?: pixelWarmupCandidatePool.firstOrNull { attempt ->
                                attempt.port == port &&
                                    attempt.endpointSource.equals(source, ignoreCase = true)
                            }
                        } ?: pixelWarmupCandidatePool.firstOrNull { attempt -> attempt.port == port }
                        preferredCandidate
                            ?.takeIf { it !in this }
                            ?.let(::add)
                    }
                    preferredPixelWarmupPorts.forEach { port ->
                        if (size >= 8) return@forEach
                        val sibling = pixelWarmupCandidatePool.firstOrNull { candidate ->
                            candidate.port == port &&
                                candidate !in this &&
                                any { existing ->
                                    existing.port == port &&
                                        existing.endpointHost.equals(candidate.endpointHost, ignoreCase = true) &&
                                        !existing.mode.name.equals(candidate.mode.name, ignoreCase = true)
                                }
                        }
                        sibling?.let(::add)
                    }
                    exportedPixelWarmupAttempts.forEach { attempt ->
                        if (size >= 8) return@forEach
                        if (attempt !in this) {
                            add(attempt)
                        }
                    }
                }

                if (pixelWarmup.isNotEmpty()) {
                    LogManager.log(
                        "MASQUE Pixel warmup: ${
                            pixelWarmup.joinToString(",") {
                                "${it.mode.name}@${it.endpointHost}:${it.port}(${it.endpointSource})"
                            }
                        }"
                    )
                    compacted += pixelWarmup
                    claimedCorePorts += pixelWarmup.map { it.port }.filter { it in preferredCoreOrder }
                }
            }

            val useLegacyMasqueSiblingWarmup =
                !useLegacyOrdinaryWifiMasqueShortlist &&
                    !clientData.shouldForceMessengerWarpPriority() &&
                    !lastRestrictedMobileDetected &&
                    pool.any {
                        it.endpointSource.equals("verified-config", ignoreCase = true) &&
                            it.port == 500
                    } &&
                    pool.any {
                        it.endpointSource.equals("verified-config", ignoreCase = true) &&
                            it.port == 1701
                    } &&
                    pool.none {
                        it.endpointSource.equals("last-success-exact", ignoreCase = true)
                    }

            if (useLegacyMasqueSiblingWarmup) {
                val legacyWarmup = buildList {
                    addAll(
                        listOf("MASQUE-CONSUMER", "MASQUE-ZT").mapNotNull { modeName ->
                            pool.firstOrNull { attempt ->
                                attempt !in compacted &&
                                    attempt.endpointSource.equals("verified-config", ignoreCase = true) &&
                                    attempt.port == 500 &&
                                    attempt.mode.name.equals(modeName, ignoreCase = true)
                            }
                        }
                    )
                    add(
                        preferredMasqueModesForPort(1701).firstNotNullOfOrNull { modeName ->
                            pool.firstOrNull { attempt ->
                                attempt !in compacted &&
                                    attempt.endpointSource.equals("verified-config", ignoreCase = true) &&
                                    attempt.port == 1701 &&
                                    attempt.mode.name.equals(modeName, ignoreCase = true)
                            }
                        } ?: pool.firstOrNull { attempt ->
                            attempt !in compacted &&
                                attempt.endpointSource.equals("verified-config", ignoreCase = true) &&
                                attempt.port == 1701
                        }
                    )
                    val primaryWarmupHost = firstOrNull()?.endpointHost?.trim()
                    add(
                        pool.firstOrNull { attempt ->
                            attempt !in compacted &&
                                attempt.port == 500 &&
                                !attempt.endpointHost.trim().equals(primaryWarmupHost, ignoreCase = true) &&
                                attempt.endpointSource.lowercase() in setOf("known-anycast", "verified-config", "api", "api-resolved")
                        }
                    )
                }.filterNotNull().distinct()

                if (legacyWarmup.isNotEmpty()) {
                    compacted += legacyWarmup
                    claimedCorePorts += legacyWarmup.map { it.port }.filter { it in preferredCoreOrder }
                    LogManager.log(
                        "MASQUE legacy sibling warmup: ${
                            legacyWarmup.joinToString(",") {
                                "${it.mode.name}@${it.endpointHost}:${it.port}(${it.endpointSource})"
                            }
                        }"
                    )
                }
            }

            preferredCoreOrder.forEach { port ->
                val candidate = preferredSources.firstNotNullOfOrNull { source ->
                    preferredMasqueModesForPort(port).firstNotNullOfOrNull { modeName ->
                        pool.firstOrNull { attempt ->
                            attempt !in compacted &&
                                attempt.port == port &&
                                attempt.endpointSource.equals(source, ignoreCase = true) &&
                                attempt.mode.name.equals(modeName, ignoreCase = true)
                        }
                    } ?: pool.firstOrNull { attempt ->
                        attempt !in compacted &&
                            attempt.port == port &&
                            attempt.endpointSource.equals(source, ignoreCase = true)
                    }
                } ?: pool.firstOrNull { attempt ->
                    attempt !in compacted && attempt.port == port
                }
                if (candidate != null) {
                    compacted += candidate
                    claimedCorePorts += port
                }
            }

            val earlyCoreCandidates = compacted.take(preferredCoreOrder.size)
            val primaryEarlyHost = earlyCoreCandidates.firstOrNull()?.endpointHost?.trim()?.lowercase().orEmpty()
            if (primaryEarlyHost.isNotBlank()) {
                val sameHostEarlyCount = earlyCoreCandidates.count {
                    it.endpointHost.trim().equals(primaryEarlyHost, ignoreCase = true)
                }
                if (sameHostEarlyCount >= 3) {
                    val alternateHostCandidate = preferredSources.firstNotNullOfOrNull { source ->
                        preferredCoreOrder.firstNotNullOfOrNull { port ->
                            preferredMasqueModesForPort(port).firstNotNullOfOrNull { modeName ->
                                pool.firstOrNull { attempt ->
                                    attempt !in compacted &&
                                        attempt.port == port &&
                                        attempt.endpointSource.equals(source, ignoreCase = true) &&
                                        attempt.mode.name.equals(modeName, ignoreCase = true) &&
                                        !attempt.endpointHost.trim().equals(primaryEarlyHost, ignoreCase = true)
                                }
                            } ?: pool.firstOrNull { attempt ->
                                attempt !in compacted &&
                                    attempt.port == port &&
                                    attempt.endpointSource.equals(source, ignoreCase = true) &&
                                    !attempt.endpointHost.trim().equals(primaryEarlyHost, ignoreCase = true)
                            }
                        }
                    } ?: pool.firstOrNull { attempt ->
                        attempt !in compacted &&
                            attempt.port in preferredCoreOrder &&
                            !attempt.endpointHost.trim().equals(primaryEarlyHost, ignoreCase = true)
                    }

                    if (alternateHostCandidate != null && alternateHostCandidate !in compacted) {
                        compacted += alternateHostCandidate
                    }
                }
            }

            items.forEach { attempt ->
                if (attempt in compacted) return@forEach
                if (attempt.port in claimedCorePorts && attempt.port in preferredCoreOrder) return@forEach
                compacted += attempt
            }

            pool.forEach { attempt ->
                if (attempt in compacted) return@forEach
                if (attempt.port in claimedCorePorts && attempt.port in preferredCoreOrder) return@forEach
                compacted += attempt
            }

            return compacted
        }

        val result = compactMasqueShortlist(selected).toMutableList()
        fun ensureMasqueFamilyPresent(modeName: String, insertIndex: Int) {
            if (result.any { it.mode.name.equals(modeName, ignoreCase = true) }) return
            val preferredPortOrder = if (modeName.equals("MASQUE-ZT", ignoreCase = true)) {
                if (legacy32) {
                    listOf(1701, 500, 4500, 443, 4443, 8443, 8095)
                } else if (preferCoreMasquePortsOnOrdinaryWifi) {
                    listOf(1701, 4500, 443, 4443, 500, 8443, 8095)
                } else {
                    listOf(4443, 443, 1701, 500, 4500, 8443, 8095)
                }
            } else {
                listOf(500, 1701, 4500, 4443, 443, 8443, 8095)
            }
            fun pickCandidate(source: String? = null): ConnectionAttempt? {
                for (port in preferredPortOrder) {
                    val byPort = rankedMasqueAttempts.firstOrNull { attempt ->
                        attempt.mode.name.equals(modeName, ignoreCase = true) &&
                            attempt.port == port &&
                            (source == null || attempt.endpointSource.equals(source, ignoreCase = true))
                    }
                    if (byPort != null) return byPort
                }
                return rankedMasqueAttempts.firstOrNull { attempt ->
                    attempt.mode.name.equals(modeName, ignoreCase = true) &&
                        (source == null || attempt.endpointSource.equals(source, ignoreCase = true))
                }
            }
            val candidate = pickCandidate("last-success-exact")
                ?: pickCandidate("last-success")
                ?: pickCandidate("verified-config")
                ?: pickCandidate()
            if (candidate == null || candidate in result) return

            if (result.size >= limit) {
                val removableIndex = result.indexOfLast { existing ->
                    !existing.mode.name.equals(modeName, ignoreCase = true) &&
                        !existing.endpointSource.equals("last-success-exact", ignoreCase = true) &&
                        !isOwnMasqueIdentityEndpoint(existing.endpointSource)
                }
                if (removableIndex >= 0) {
                    result.removeAt(removableIndex)
                } else {
                    result.removeAt(result.lastIndex)
                }
            }

            val clampedIndex = insertIndex.coerceIn(0, result.size)
            result.add(clampedIndex, candidate)
        }

        if (rankedMasqueAttempts.any { it.mode.name.equals("MASQUE-ZT", ignoreCase = true) }) {
            ensureMasqueFamilyPresent("MASQUE-ZT", insertIndex = 1)
        }
        if (rankedMasqueAttempts.any { it.mode.name.equals("MASQUE-CONSUMER", ignoreCase = true) }) {
            ensureMasqueFamilyPresent("MASQUE-CONSUMER", insertIndex = 1)
        }

        // Собственный адрес аккаунта обязан быть в переборе и первым.
        //
        // Одного приоритета источника мало: отбор ниже досыпает кандидатов «на
        // разнообразие режимов и портов» и вытесняет ими то, что уже стоит в списке.
        // Именно так родной адрес и пропадал целиком, оставляя перебор из одних
        // сканированных — а те отвечают отказом по сертификату.
        //
        // Среди своих адресов предпочитаем 443: только на этом порту ядро включает
        // запасной путь MASQUE поверх TCP, и только он проходит там, где QUIC режут.
        val ownIdentityAttempts = preferredOwnMasqueIdentityAttempts(rankedMasqueAttempts)
        if (ownIdentityAttempts.isNotEmpty()) {
            result.removeAll(ownIdentityAttempts)
            while (result.isNotEmpty() && result.size + ownIdentityAttempts.size > limit) {
                val removableIndex = result.indexOfLast { existing ->
                    !existing.endpointSource.equals("last-success-exact", ignoreCase = true) &&
                        !isOwnMasqueIdentityEndpoint(existing.endpointSource)
                }.takeIf { it >= 0 } ?: result.lastIndex
                result.removeAt(removableIndex)
            }
            result.addAll(0, ownIdentityAttempts)
        } else {
            LogManager.log(
                "MASQUE: собственного адреса аккаунта нет в кандидатах — в identity пусты " +
                    "endpoint_v4/endpoint_v6. Перебор пойдёт по анкасту и скану."
            )
        }

        // На реальных сетях QUIC может отваливаться, а TCP fallback в core включается только
        // когда пробуем порт 443. Добавляем раннее покрытие ключевых портов без дублей.
        val preferredPortCoverage = if (!legacy32 && !preferMessengerMasqueStealth) {
            listOf(500, 1701, 4500, 443, 4443, 8443)
        } else if (legacy32) {
            listOf(500, 1701, 4500, 443, 4443, 8443)
        } else {
            listOf(500, 4443, 443, 1701, 4500, 8443)
        }
        preferredPortCoverage.forEachIndexed { index, port ->
            if (result.any { it.port == port }) return@forEachIndexed
            val candidate = rankedMasqueAttempts.firstOrNull { attempt ->
                attempt.port == port && attempt !in result
            } ?: return@forEachIndexed

            if (result.size >= limit) {
                val removableDuplicatePortIndex = result.indexOfLast { existing ->
                    result.count { it.port == existing.port } > 1 &&
                        !existing.endpointSource.equals("last-success-exact", ignoreCase = true) &&
                        !isOwnMasqueIdentityEndpoint(existing.endpointSource)
                }
                val removableIndex = if (removableDuplicatePortIndex >= 0) {
                    removableDuplicatePortIndex
                } else {
                    result.indexOfLast { existing ->
                        !existing.endpointSource.equals("last-success-exact", ignoreCase = true) &&
                        !isOwnMasqueIdentityEndpoint(existing.endpointSource) &&
                            existing.port != port
                    }
                }
                if (removableIndex >= 0) {
                    result.removeAt(removableIndex)
                }
            }

            if (result.size < limit) {
                val insertAt = (index + 1).coerceIn(0, result.size)
                result.add(insertAt, candidate)
            }
        }
        val duplicate4443Index = result.indexOfLast { it.port == 4443 }
        val hasAnother4443 = duplicate4443Index >= 0 &&
            result.indexOfFirst { it.port == 4443 } != duplicate4443Index
        if (hasAnother4443) {
            val fallback443 = rankedMasqueAttempts.firstOrNull { attempt ->
                attempt.port == 443 && attempt !in result
            }
            if (fallback443 != null) {
                result.removeAt(duplicate4443Index)
                if (result.size < limit) {
                    result.add(2.coerceIn(0, result.size), fallback443)
                }
            }
        }
        val primaryHost = result.firstOrNull()?.endpointHost?.trim().orEmpty()
        val sameHostOnly = primaryHost.isNotBlank() &&
            result.all { it.endpointHost.trim().equals(primaryHost, ignoreCase = true) }
        if (sameHostOnly) {
            val crossHostCandidate = rankedMasqueAttempts.firstOrNull { attempt ->
                !attempt.endpointHost.trim().equals(primaryHost, ignoreCase = true) &&
                    attempt.port in preferredPortCoverage &&
                    attempt !in result
            } ?: rankedMasqueAttempts.firstOrNull { attempt ->
                !attempt.endpointHost.trim().equals(primaryHost, ignoreCase = true) &&
                    attempt !in result
            }
            if (crossHostCandidate != null) {
                val replaceIndex = result.indexOfLast { existing ->
                    !existing.endpointSource.equals("last-success-exact", ignoreCase = true) &&
                    !isOwnMasqueIdentityEndpoint(existing.endpointSource) &&
                        !existing.port.equals(443)
                }.takeIf { it >= 0 } ?: result.lastIndex
                if (replaceIndex >= 0 && replaceIndex < result.size) {
                    result[replaceIndex] = crossHostCandidate
                }
            }
        }

        val finalResult = result.take(limit)
        LogManager.log(
            "MASQUE shortlist diversity: ${
                finalResult.joinToString(",") {
                    "${it.mode.name}@${it.endpointHost}:${it.port}(${it.endpointSource})"
                }
            }"
        )
        return finalResult
    }

    /**
     * Адрес, выданный Cloudflare именно этому устройству при регистрации MASQUE.
     *
     * Отличается от всех прочих кандидатов принципиально: только на нём сходится
     * сертификат, закреплённый в identity. Остальные — анкаст и результаты скана —
     * догадки об адресах, которые этому аккаунту не выдавались.
     */
    private fun isOwnMasqueIdentityEndpoint(source: String): Boolean {
        val normalized = source.trim().lowercase()
        return normalized == "masque-v4" || normalized == "masque-v6"
    }

    /**
     * Свой адрес аккаунта в начало перебора — обоими режимами и consumer вперёд.
     *
     * Один и тот же адрес обслуживают две разные службы Cloudflare, и выбирает между
     * ними SNI: `zt-masque` — Zero Trust, `consumer-masque` — обычный WARP. Закреплённый
     * в identity сертификат принадлежит узлу, а не службе, поэтому рукопожатие проходит
     * в обоих случаях — и различить их по факту подключения нельзя.
     *
     * Замер на тестовом устройстве, свежая identity, свой адрес 443: с `consumer-masque` сервер не
     * ответил на запрос CONNECT-IP ни разу, с `zt-masque` тот же запрос открылся за 29мс и
     * туннель заработал. Поэтому ZT идёт первым, а consumer следом: гадать о типе аккаунта
     * не нужно, если можно попробовать оба подряд.
     */
    private fun preferredOwnMasqueIdentityAttempts(
        rankedMasqueAttempts: List<ConnectionAttempt>,
    ): List<ConnectionAttempt> {
        val own = rankedMasqueAttempts.filter { isOwnMasqueIdentityEndpoint(it.endpointSource) }
        if (own.isEmpty()) return emptyList()
        // 443 предпочитаем внутри режима, а не поверх него: у режимов свой порядок портов,
        // и отбор «сначала все 443» оставлял в списке один Zero Trust — тот самый, который
        // молчит. Своё лучшее у каждого режима, consumer первым.
        fun pick(modeName: String): ConnectionAttempt? {
            val ofMode = own.filter { it.mode.name.equals(modeName, ignoreCase = true) }
            return ofMode.firstOrNull { it.port == 443 } ?: ofMode.firstOrNull()
        }
        val zeroTrust = pick("MASQUE-ZT")
        val consumer = pick("MASQUE-CONSUMER")
        return listOfNotNull(zeroTrust, consumer).ifEmpty { listOf(own.first()) }
    }

    /**
     * Порядок источников адресов MASQUE.
     *
     * Собственный адрес аккаунта стоит первым, и это не вопрос вкуса. Пока он был
     * пятым — ниже анкаста и api — в перебор попадали только сканированные адреса, а
     * они на чужой сертификат отвечают отказом. По TCP это видно прямо:
     * `x509: remote endpoint has a different public key than what we trust in
     * config.json`, а по UDP выглядит как тишина и таймаут рукопожатия. Проверено на
     * тестовое устройство: за два полных цикла в переборе не было ни одного `masque-v4`.
     */
    private fun masqueSourcePriority(source: String): Int {
        return when (source.trim().lowercase()) {
            "masque-v4", "masque-v6" -> 0
            "last-success-exact" -> 1
            "verified-config" -> 2
            "last-success" -> 3
            "known-anycast", "neighbor-anycast" -> 4
            "api", "api-resolved" -> 5
            "masque-scan" -> 6
            "masque-scan-tcp443" -> 7
            else -> 8
        }
    }

    private fun masquePortPriority(port: Int): Int {
        return when (port) {
            1701 -> 0
            500 -> 1
            4500 -> 2
            443 -> 3
            4443 -> 4
            8443 -> 5
            8095 -> 6
            else -> 7
        }
    }

    private fun prioritizeConnectionAttempts(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
    ): List<ConnectionAttempt> {
        if (attempts.isEmpty()) return attempts

        val importedProtocolModeActive = clientData.isImportedConfigSourceActive()
        val forcedImportedProtocol = clientData.getImportedProtocolPreference()
            .takeIf { importedProtocolModeActive && !it.equals("auto", ignoreCase = true) }
        val userImportedAttempts = buildUserImportedWarpAttemptSet(
            attempts,
            clientData,
            limit = if (importedProtocolModeActive) attempts.size.coerceAtLeast(1) else 8,
        )
        if (importedProtocolModeActive && userImportedAttempts.isEmpty()) {
            val selectedProtocol = forcedImportedProtocol?.uppercase(Locale.US) ?: "AUTO"
            LogManager.log(
                "USER WARP: режим импортированных конфигураций ($selectedProtocol), " +
                    "но подходящих импортированных профилей нет. Обычный WARP-пул не используем."
            )
            return emptyList()
        }
        val rankedPool = if (importedProtocolModeActive && userImportedAttempts.isNotEmpty()) {
            if (forcedImportedProtocol != null) {
                LogManager.log("USER WARP: принудительно используем импортированные профили протокола ${forcedImportedProtocol.uppercase(Locale.US)}.")
            } else {
                LogManager.log("USER WARP: AUTO переключён в режим только импортированных конфигураций.")
            }
            userImportedAttempts.distinctBy { attemptExactKey(it) }
        } else {
            (userImportedAttempts + attempts)
                .distinctBy { attemptExactKey(it) }
        }

        val lastProtocol = currentCycleLastSuccessProtocol(clientData)
        val lastMode = currentCycleLastSuccessMode(clientData)
        val messengerAccelerationProfile = resolveMessengerAccelerationProfile(clientData)
        val preferMessengerChatProfiles = messengerAccelerationProfile != MessengerAccelerationProfile.OFF
        val strategyScope = if (preferMessengerChatProfiles) "messenger" else "default"
        val strategyNetworkClass = currentStrategyNetworkClass()
        val nowMs = System.currentTimeMillis()
        val scored = rankedPool.mapIndexed { index, attempt ->
            val cooldownPenalty = if (isWarpAttemptCoolingDown(clientData, attempt, nowMs)) 260.0 else 0.0
            val score = clientData.getStrategyScore(
                engine = attempt.mode.engine,
                mode = attempt.mode.name,
                host = attempt.endpointHost,
                port = attempt.port,
                strategyScope = strategyScope,
                networkClass = strategyNetworkClass,
            ) + attemptSourceBias(
                attempt = attempt,
                lastProtocol = lastProtocol,
                lastMode = lastMode,
                preferMessengerChatProfiles = preferMessengerChatProfiles,
                messengerAccelerationProfile = messengerAccelerationProfile,
            ) - cooldownPenalty
            Triple(attempt, score, index)
        }

        val scoredSorted = scored
            .sortedWith(
                compareByDescending<Triple<ConnectionAttempt, Double, Int>> { it.second }
                    .thenBy { it.third }
            )
        val coreWarpBurst = seedCoreWarpAttempts(scoredSorted, preferMessengerChatProfiles)
        val deterministicBurst = scoredSorted
            .filter { isDeterministicEndpointSource(it.first.endpointSource) }
            .let { diversifyRankedAttempts(it, coreWarpBurst) }
            .take(maxOf(8, coreWarpBurst.size))
        val ranked = diversifyRankedAttempts(scoredSorted, deterministicBurst)
        val promoted = promoteFreshExactWinner(
            promoteVerifiedAttempts(ranked, clientData),
            clientData,
        )
        val preferredImportedPrefix = selectPreferredUserImportedWarpPrefix(
            attempts = rankedPool,
            importedAttempts = userImportedAttempts,
            clientData = clientData,
            strategyScope = strategyScope,
            strategyNetworkClass = strategyNetworkClass,
            limit = userImportedAttempts.size,
        )
        val finalOrdered = if (preferredImportedPrefix.isEmpty()) {
            promoted
        } else {
            preferredImportedPrefix + promoted.filterNot { promotedAttempt ->
                preferredImportedPrefix.any { importedAttempt ->
                    attemptExactKey(importedAttempt) == attemptExactKey(promotedAttempt)
                }
            }
        }

        val preview = finalOrdered
            .take(10)
            .map { attempt ->
                val score = scoredSorted.firstOrNull { it.first == attempt }?.second ?: 0.0
                Triple(
                    "${attempt.mode.name}${
                        clientData.getStrategyDiagnosticTag(
                            engine = attempt.mode.engine,
                            mode = attempt.mode.name,
                            host = attempt.endpointHost,
                            port = attempt.port,
                            strategyScope = strategyScope,
                            networkClass = strategyNetworkClass,
                        )?.let { "[$it]" }.orEmpty()
                    }",
                    formatEndpoint(attempt.endpointHost, attempt.port),
                    score,
                )
            }
            .take(8)
            .joinToString(",") {
                "${it.first}@${it.second}=${"%.1f".format(it.third)}"
            }
        LogManager.log(
            "Рейтинг стратегий${strategyNetworkClass?.let { " [$it]" }.orEmpty()}: $preview"
        )

        return finalOrdered
    }

    private fun isWarpAttemptCoolingDown(
        clientData: ClientData,
        attempt: ConnectionAttempt,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (attempt.mode.engine == "masque") return false
        return isTransientlyDegradedWarpProfile(
            engine = attempt.mode.engine,
            mode = attempt.mode.name,
            host = attempt.endpointHost,
            port = attempt.port,
            nowMs = nowMs,
        ) || clientData.isWarpAttemptCoolingDown(
            engine = attempt.mode.engine,
            mode = attempt.mode.name,
            host = attempt.endpointHost,
            port = attempt.port,
            preferredSni = attempt.preferredSni,
            nowMs = nowMs,
        ) || clientData.isWarpAttemptCoolingDown(
            engine = attempt.mode.engine,
            mode = attempt.mode.name,
            host = attempt.endpointHost,
            port = attempt.port,
            preferredSni = null,
            nowMs = nowMs,
        )
    }

    private fun buildUserImportedWarpAttemptSet(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
        limit: Int = 8,
    ): List<ConnectionAttempt> {
        if (attempts.isEmpty()) return emptyList()
        val importedProtocolModeActive = clientData.isImportedConfigSourceActive()
        if (!importedProtocolModeActive) {
            return emptyList()
        }
        val forcedImportedProtocol = clientData.getImportedProtocolPreference()
            .takeIf { importedProtocolModeActive && !it.equals("auto", ignoreCase = true) }

        val importedConfigs = mergedVerifiedWarpConfigs(clientData)
            .filter { it.userImported && !it.manual && !it.engine.equals("masque", ignoreCase = true) }
            .filter { config ->
                forcedImportedProtocol == null || clientData.inferImportedProtocolFamily(config) == forcedImportedProtocol
            }
            .sortedWith(
                compareByDescending<WarpVerifiedConfig> { it.promotedAt }
                    .thenByDescending { clientData.getWarpVerifiedQualityTier(it) }
                    .thenByDescending { it.qualityPingSuccesses }
                    .thenBy {
                        if (it.qualityPingSuccesses > 0 && it.qualityAvgPingMs > 0.0) {
                            it.qualityAvgPingMs
                        } else {
                            Double.MAX_VALUE
                        }
                    }
                    .thenBy { it.qualityFailureCount }
                    .thenByDescending { clientData.getWarpVerifiedPriorityScore(it) }
                    .thenByDescending { it.lastVerifiedAt }
            )
            .take(limit)
        LogManager.log(
            "USER WARP imported configs available: ${importedConfigs.size} (limit=$limit, " +
                "protocol=${forcedImportedProtocol ?: "auto"})."
        )
        if (importedConfigs.isEmpty()) return emptyList()

        val exactAttemptMap = linkedMapOf<String, ConnectionAttempt>()
        attempts.forEach { attempt ->
            exactAttemptMap.putIfAbsent(attemptExactKey(attempt), attempt)
        }

        val modeTemplateMap = linkedMapOf<String, TransportMode>()
        attempts.forEach { attempt ->
            val key = "${attempt.mode.engine.lowercase()}|${attempt.mode.name.lowercase()}"
            modeTemplateMap.putIfAbsent(key, attempt.mode)
        }
        fun fallbackModeTemplate(config: WarpVerifiedConfig): TransportMode {
            val normalizedMode = config.mode.lowercase()
            val explicitAwgImport = config.userImported && hasExplicitAwgImport(config.rawConfig)
            val primaryPort = clientDataPrimaryPortForConfig(config, clientData)
            val preferredPorts = buildList {
                add(primaryPort)
                when (normalizedMode) {
                    "warp-awg-lite" -> addAll(listOf(1701, 500, 988, 4500, 2408, 443))
                    "warp-awg-exact",
                    "warp-awg",
                    "warp-awg-max" -> addAll(listOf(988, 500, 1701, 4500, 2408, 443))
                    "warp-awg-v2",
                    "warp-v1",
                    "warp-v2",
                    "warp-v3" -> addAll(listOf(500, 1701, 4500, 2408, 443, 988))
                    else -> addAll(listOf(500, 1701, 4500, 443, 2408, 988))
                }
            }.distinct()

            return TransportMode(
                name = config.mode,
                engine = config.engine,
                useFakePackets = false,
                reservedMode = if (explicitAwgImport) "off" else "handshake",
                preferredPorts = preferredPorts,
                restrictToPreferredPorts = true,
                novaModeOverride = if (explicitAwgImport) "plain-wireguard" else null,
                fakePacketsOverride = false,
                reservedModeOverride = if (explicitAwgImport) "off" else null,
                preferImportedRawDns = explicitAwgImport,
                preferImportedRawMtu = explicitAwgImport,
                omitReservedLineIfMissingInImport = explicitAwgImport,
            )
        }

        val selected = linkedSetOf<ConnectionAttempt>()
        for (config in importedConfigs) {
            val explicitAwgImport = config.userImported && hasExplicitAwgImport(config.rawConfig)
            fun applyImportedModeOverrides(mode: TransportMode): TransportMode {
                if (!explicitAwgImport) return mode
                return mode.copy(
                    reservedMode = "off",
                    novaModeOverride = "plain-wireguard",
                    fakePacketsOverride = false,
                    reservedModeOverride = "off",
                    preferImportedRawDns = true,
                    preferImportedRawMtu = true,
                    omitReservedLineIfMissingInImport = true,
                )
            }
            fun addImportedAttemptWithFallback(attempt: ConnectionAttempt) {
                val baseAttempt = attempt.copy(
                    mode = attempt.mode.copy(
                        preferImportedRawIdentity = false,
                    )
                )
                val hasRawIdentity = explicitAwgImport && hasImportedWireGuardIdentity(config.rawConfig)
                // Импортированный AWG-профиль подключается ровно так, как его выдал
                // провайдер, и никаких подвариантов к нему не добавляется.
                //
                // Раньше строгий путь включался только при отдельно включённом режиме
                // импортированных конфигураций. Без него к попыткам добавлялся вариант
                // с ключами из собственной регистрации Nova и её обфускацией: внешне
                // «профиль подключился», а фактически работал совершенно другой
                // профиль, о чём пользователь не догадывался. Для явного AWG-импорта
                // достаточно самого факта импорта.
                if (explicitAwgImport) {
                    val exactAttempt = baseAttempt.copy(
                        mode = baseAttempt.mode.copy(
                            preferImportedRawIdentity = hasRawIdentity,
                            preferImportedRawDns = true,
                            preferImportedRawMtu = true,
                            fakePacketsOverride = false,
                            novaModeOverride = "plain-wireguard",
                            reservedModeOverride = "off",
                            omitReservedLineIfMissingInImport = true,
                        )
                    )
                    selected += exactAttempt
                    LogManager.log(
                        if (hasRawIdentity) {
                            "USER WARP exact imported AWG ${exactAttempt.endpointHost}:${exactAttempt.port}/" +
                                "${exactAttempt.mode.name}: профиль применяется как есть — ключи, адреса, DNS, MTU и " +
                                "параметры обфускации взяты из импорта, подварианты Nova не добавляются."
                        } else {
                            "USER WARP imported AWG ${exactAttempt.endpointHost}:${exactAttempt.port}/" +
                                "${exactAttempt.mode.name}: в профиле нет PrivateKey, поэтому ключи берутся из " +
                                "регистрации Nova; endpoint, DNS, MTU и обфускация — из импорта."
                        }
                    )
                    return
                }

                selected += baseAttempt
            }

            val modeTemplate = modeTemplateMap["${config.engine.lowercase()}|${config.mode.lowercase()}"]
                ?.let(::applyImportedModeOverrides)
                ?: applyImportedModeOverrides(fallbackModeTemplate(config))
            val resolvedHosts = resolveImportedWarpAttemptHosts(config)
            for (resolvedHost in resolvedHosts) {
                val exactKey = buildWarpDiscoveryAttemptKey(config.mode, resolvedHost, config.port)
                val existingAttempt = exactAttemptMap.values.firstOrNull { attempt ->
                    buildWarpDiscoveryAttemptKey(attempt.mode.name, attempt.endpointHost, attempt.port) == exactKey
                }
                if (existingAttempt != null) {
                    val adjusted = existingAttempt.copy(
                        mode = applyImportedModeOverrides(existingAttempt.mode),
                        importedConfigHost = config.host,
                        preferredSni = config.preferredSni.takeIf { it.isNotBlank() }
                            ?: existingAttempt.preferredSni,
                        strategyScope = config.scope,
                    )
                    LogManager.log(
                            "USER WARP adjusted existing attempt ${adjusted.endpointHost}:${adjusted.port}/${adjusted.mode.name} " +
                                "novaMode=${adjusted.mode.novaModeOverride ?: adjusted.mode.name}, " +
                            "reservedMode=${adjusted.mode.reservedModeOverride ?: adjusted.mode.reservedMode}"
                    )
                    addImportedAttemptWithFallback(adjusted)
                    continue
                }
                val primaryPort = clientDataPrimaryPortForConfig(config, clientData)
                val createdAttempt = ConnectionAttempt(
                    endpointHost = resolvedHost,
                    port = primaryPort,
                    mode = modeTemplate.copy(
                        preferredPorts = listOf(primaryPort) + modeTemplate.preferredPorts.filter { it != primaryPort },
                    ),
                    endpointSource = config.endpointSource.ifBlank { "verified-config" },
                    importedConfigHost = config.host,
                    strategyScope = config.scope,
                    preferredSni = config.preferredSni.takeIf { it.isNotBlank() },
                )
                LogManager.log(
                    "USER WARP created attempt ${createdAttempt.endpointHost}:${createdAttempt.port}/${createdAttempt.mode.name} " +
                        "novaMode=${createdAttempt.mode.novaModeOverride ?: createdAttempt.mode.name}, " +
                        "reservedMode=${createdAttempt.mode.reservedModeOverride ?: createdAttempt.mode.reservedMode}"
                )
                addImportedAttemptWithFallback(createdAttempt)
            }
        }

        if (selected.isNotEmpty()) {
            LogManager.log(
                "USER WARP shortlist: ${selected.size}. " +
                    selected.joinToString(",") { attemptLogLabel(it) }
            )
        }
        return selected.toList()
    }

    private fun buildBuiltInWarpAttemptSet(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
        bundledSeedConfigs: List<WarpVerifiedConfig> = clientData.getWarpVerifiedConfigs()
            .filter { clientData.isBundledSeed(it) && !it.engine.equals("masque", ignoreCase = true) },
        manualFirstAttemptKey: String? = null,
    ): List<ConnectionAttempt> {
        val bundledSeedCount = bundledSeedConfigs.size.coerceAtLeast(1)
        /**
         * Прошивочный порядок — грубыми корзинами, а не поштучно.
         *
         * Пока здесь стоял сам `seedOrder`, очередь встроенных профилей задавалась
         * им целиком: у всех пятидесяти записей он разный, равенства по первому
         * ключу не случается никогда, и все следующие ключи — качество, пинг,
         * удержание, числовая оценка — были недостижимы. Адаптация мерила
         * профили, а очередь после неё не менялась ни на шаг; штраф за churn,
         * заведённый ради того же, тоже никуда не попадал. Корзина оставляет
         * прошивке грубую власть (первая десятка идёт раньше второй) и отдаёт
         * порядок внутри десятки замерам с устройства.
         */
        fun bundledSeedQueueOrder(config: WarpVerifiedConfig): Int {
            return SessionHoldMetric.bundledSeedQueueBucket(config.seedOrder)
        }

        val sortedVerifiedConfigs = bundledSeedConfigs
            .sortedWith(
                compareBy<WarpVerifiedConfig> { bundledSeedQueueOrder(it) }
                    .thenByDescending { clientData.getWarpVerifiedQualityTier(it) }
                    .thenByDescending { clientData.warpConfigHoldGrade(it) }
                    .thenByDescending { it.qualityPingSuccesses }
                    .thenBy {
                        if (it.qualityPingSuccesses > 0 && it.qualityAvgPingMs > 0.0) {
                            it.qualityAvgPingMs
                        } else {
                            Double.MAX_VALUE
                        }
                    }
                    .thenBy { it.qualityFailureCount }
                    .thenByDescending { clientData.getWarpVerifiedPriorityScore(it) }
                    .thenByDescending { it.lastVerifiedAt }
                    // Замеров может не быть вовсе — тогда порядок обязан остаться
                    // прошивочным и одинаковым от запуска к запуску.
                    .thenBy { it.seedOrder }
            )
        val manualKey = manualFirstAttemptKey?.trim()?.takeIf { it.isNotBlank() }
        val verifiedConfigs = if (manualKey != null) {
            sortedVerifiedConfigs.sortedWith(
                compareBy<WarpVerifiedConfig> {
                    if (buildWarpDiscoveryAttemptKey(it.mode, it.host, it.port) == manualKey) 0 else 1
                }
            )
        } else {
            sortedVerifiedConfigs
        }

        if (verifiedConfigs.isEmpty()) return emptyList()

        val exactAttemptMap = linkedMapOf<String, ConnectionAttempt>()
        attempts.forEach { attempt ->
            exactAttemptMap.putIfAbsent(attemptExactKey(attempt), attempt)
        }

        val modeTemplateMap = linkedMapOf<String, TransportMode>()
        attempts.forEach { attempt ->
            val key = "${attempt.mode.engine.lowercase()}|${attempt.mode.name.lowercase()}"
            modeTemplateMap.putIfAbsent(key, attempt.mode)
        }

        fun fallbackModeTemplate(config: WarpVerifiedConfig): TransportMode {
            val strictRawAwgSeed =
                clientData.isBundledSeed(config) &&
                    hasExplicitAwgImport(config.rawConfig) &&
                    hasImportedWireGuardIdentity(config.rawConfig)
            return TransportMode(
                name = config.mode,
                engine = config.engine,
                useFakePackets = false,
                reservedMode = if (strictRawAwgSeed) "off" else "handshake",
                preferredPorts = listOf(config.port),
                restrictToPreferredPorts = true,
                novaModeOverride = if (strictRawAwgSeed) "plain-wireguard" else null,
                fakePacketsOverride = false,
                reservedModeOverride = if (strictRawAwgSeed) "off" else null,
                preferImportedRawDns = strictRawAwgSeed,
                preferImportedRawMtu = strictRawAwgSeed,
                preferImportedRawIdentity = strictRawAwgSeed,
                omitReservedLineIfMissingInImport = strictRawAwgSeed,
            )
        }

        val selected = mutableListOf<ConnectionAttempt>()
        for (config in verifiedConfigs) {
            val strictRawAwgSeed =
                clientData.isBundledSeed(config) &&
                    hasExplicitAwgImport(config.rawConfig) &&
                    hasImportedWireGuardIdentity(config.rawConfig)
            fun strictBuiltInMode(mode: TransportMode): TransportMode {
                if (!strictRawAwgSeed) return mode.copy(
                    preferredPorts = listOf(config.port),
                    restrictToPreferredPorts = true,
                )
                return mode.copy(
                    name = config.mode,
                    engine = config.engine.ifBlank { mode.engine },
                    useFakePackets = false,
                    reservedMode = "off",
                    preferredPorts = listOf(config.port),
                    restrictToPreferredPorts = true,
                    novaModeOverride = "plain-wireguard",
                    fakePacketsOverride = false,
                    reservedModeOverride = "off",
                    preferImportedRawDns = true,
                    preferImportedRawMtu = true,
                    preferImportedRawIdentity = true,
                    omitReservedLineIfMissingInImport = true,
                )
            }
            val exactKey = buildWarpDiscoveryAttemptKey(config.mode, config.host, config.port)
            val existingAttempt = exactAttemptMap[exactKey]
            if (existingAttempt != null) {
                selected += existingAttempt.copy(
                    mode = strictBuiltInMode(existingAttempt.mode),
                    preferredSni = config.preferredSni.takeIf { it.isNotBlank() }
                        ?: existingAttempt.preferredSni,
                    strategyScope = config.scope,
                    importedConfigHost = config.host,
                )
                continue
            }
            val modeKey = "${config.engine.lowercase()}|${config.mode.lowercase()}"
            val modeTemplate = modeTemplateMap[modeKey] ?: fallbackModeTemplate(config)
            selected += ConnectionAttempt(
                endpointHost = config.host.trim().removePrefix("[").removeSuffix("]"),
                port = config.port,
                mode = strictBuiltInMode(modeTemplate),
                endpointSource = config.endpointSource.ifBlank { "verified-config" },
                importedConfigHost = config.host,
                strategyScope = config.scope,
                preferredSni = config.preferredSni.takeIf { it.isNotBlank() },
            )
        }
        return selected
    }

    private fun mergedVerifiedWarpConfigs(clientData: ClientData, scope: String? = null): List<WarpVerifiedConfig> {
        return (clientData.getWarpVerifiedExportSnapshot(scope) + clientData.getWarpVerifiedConfigs(scope))
            .distinctBy { it.id }
    }

    private fun hasExplicitAwgImport(rawConfig: String): Boolean {
        return Regex("(?im)^(Jc|Jmin|Jmax|S[1-4]|H[1-4]|I[1-5])\\s*=").containsMatchIn(rawConfig)
    }

    private fun hasImportedHandshakePayload(rawConfig: String): Boolean {
        return Regex("(?im)^I[1-5]\\s*=").containsMatchIn(rawConfig)
    }

    private fun resolveImportedWarpAttemptHosts(config: WarpVerifiedConfig): List<String> {
        val cleanHost = config.host.trim().removePrefix("[").removeSuffix("]")
        if (cleanHost.isBlank()) return emptyList()
        if (isNumericEndpointHost(cleanHost)) {
            return listOf(cleanHost)
        }
        LogManager.log(
            "USER WARP host $cleanHost: пропускаем блокирующий DNS pre-resolve в горячем пути, " +
                "передаём hostname движку как есть."
        )
        return listOf(cleanHost)
    }

    private fun selectPreferredUserImportedWarpPrefix(
        attempts: List<ConnectionAttempt>,
        importedAttempts: List<ConnectionAttempt>,
        clientData: ClientData,
        strategyScope: String,
        strategyNetworkClass: String?,
        limit: Int,
    ): List<ConnectionAttempt> {
        if (attempts.isEmpty() || importedAttempts.isEmpty() || limit <= 0) return emptyList()
        val importedProtocolModeActive = clientData.isImportedConfigSourceActive()
        if (!importedProtocolModeActive) return emptyList()
        val forcedImportedProtocol = clientData.getImportedProtocolPreference()
            .takeIf { importedProtocolModeActive && !it.equals("auto", ignoreCase = true) }
        if (importedProtocolModeActive) {
            val forced = importedAttempts
                .distinctBy { attemptExactKey(it) }
                .take(limit)
            if (forced.isNotEmpty()) {
                val selectedProtocol = forcedImportedProtocol?.uppercase(Locale.US) ?: "AUTO"
                LogManager.log(
                    "USER WARP: режим импортированных конфигураций ($selectedProtocol), используем " +
                        forced.joinToString(",") { attemptLogLabel(it) }
                )
            }
            return forced
        }

        val messengerAccelerationProfile = resolveMessengerAccelerationProfile(clientData)
        val preferMessengerChatProfiles = messengerAccelerationProfile != MessengerAccelerationProfile.OFF
        val lastProtocol = currentCycleLastSuccessProtocol(clientData)
        val lastMode = currentCycleLastSuccessMode(clientData)
        val importedKeys = importedAttempts.mapTo(linkedSetOf()) { attemptExactKey(it) }
        val stickyExactHost = currentCycleLastSuccessEndpoint(clientData)
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.lowercase()
            .orEmpty()
        val stickyExactPort = currentCycleLastSuccessPort(clientData) ?: -1
        val stickyExactMode = lastMode.trim().lowercase()

        fun isProtectedImportedFrontloadAttempt(attempt: ConnectionAttempt): Boolean {
            if (attempt.endpointSource.equals("last-success-exact", ignoreCase = true)) return true
            val host = attempt.endpointHost.trim().removePrefix("[").removeSuffix("]").lowercase()
            if (host != stickyExactHost || attempt.port != stickyExactPort) return false
            if (stickyExactMode.isBlank()) return true
            return attempt.mode.name.equals(stickyExactMode, ignoreCase = true)
        }

        fun score(attempt: ConnectionAttempt): Double {
            return clientData.getStrategyScore(
                engine = attempt.mode.engine,
                mode = attempt.mode.name,
                host = attempt.endpointHost,
                port = attempt.port,
                strategyScope = strategyScope,
                networkClass = strategyNetworkClass,
            ) + attemptSourceBias(
                attempt = attempt,
                lastProtocol = lastProtocol,
                lastMode = lastMode,
                preferMessengerChatProfiles = preferMessengerChatProfiles,
                messengerAccelerationProfile = messengerAccelerationProfile,
            )
        }

        val bestNonImportedScore = attempts
            .filterNot { attemptExactKey(it) in importedKeys }
            .maxOfOrNull(::score)

        val scoredImported = importedAttempts
            .distinctBy { attemptExactKey(it) }
            .map { attempt ->
                Triple(
                    attempt,
                    score(attempt),
                    clientData.getStrategyDiagnosticTag(
                        engine = attempt.mode.engine,
                        mode = attempt.mode.name,
                        host = attempt.endpointHost,
                        port = attempt.port,
                        strategyScope = strategyScope,
                        networkClass = strategyNetworkClass,
                    ),
                )
            }
            .sortedWith(
                compareBy<Triple<ConnectionAttempt, Double, String?>> {
                    !isProtectedImportedFrontloadAttempt(it.first)
                }
                    .thenBy {
                    importedAttemptVariantRank(it.first)
                }
                    .thenByDescending { it.second }
                    .thenBy { it.first.port }
            )

        val prefix = scoredImported
            .filterNot { (attempt, scoreValue, diagnosticTag) ->
                if (isProtectedImportedFrontloadAttempt(attempt)) {
                    return@filterNot false
                }
                shouldDeferImportedWarpAttempt(
                    diagnosticTag = diagnosticTag,
                    score = scoreValue,
                    bestNonImportedScore = bestNonImportedScore,
                    currentNetworkClass = strategyNetworkClass,
                )
            }
            .take(limit)
            .map { it.first }

        if (prefix.isNotEmpty()) {
            LogManager.log(
                "USER WARP префикс: " +
                    prefix.joinToString(",") { attemptLogLabel(it) }
            )
        }
        return prefix
    }

    private fun shouldDeferImportedWarpAttempt(
        diagnosticTag: String?,
        score: Double,
        bestNonImportedScore: Double?,
        currentNetworkClass: String?,
    ): Boolean {
        if (bestNonImportedScore == null) return false
        val (code, count, networkScoped) = decodeStrategyDiagnosticTag(diagnosticTag)
        val blockingCode = code in setOf("hs", "nt", "vt", "cp", "ni", "ec")
        if (!blockingCode || count <= 0) return false
        if (networkScoped && currentNetworkClass != null && score >= bestNonImportedScore + 2.0) {
            return false
        }
        return score < bestNonImportedScore + 2.0
    }

    private fun decodeStrategyDiagnosticTag(tag: String?): Triple<String?, Int, Boolean> {
        val normalized = tag?.trim().orEmpty()
        if (normalized.isBlank()) return Triple(null, 0, false)
        val networkScoped = normalized.contains(':')
        val coreTag = normalized.substringAfterLast(':').trim()
        val match = Regex("([a-z]+)(\\d+)").matchEntire(coreTag)
        if (match == null) return Triple(coreTag.ifBlank { null }, 0, networkScoped)
        val code = match.groupValues.getOrNull(1).orEmpty().ifBlank { null }
        val count = match.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
        return Triple(code, count, networkScoped)
    }

    private fun promoteFreshExactWinner(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
    ): List<ConnectionAttempt> {
        if (attempts.isEmpty() || !currentCycleHasReusableLastSuccess(clientData)) return attempts

        val lastHost = currentCycleLastSuccessEndpoint(clientData)
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.lowercase()
            .orEmpty()
        val lastPort = currentCycleLastSuccessPort(clientData) ?: -1
        val lastMode = currentCycleLastSuccessMode(clientData).trim()
        val lastProtocol = currentCycleLastSuccessProtocol(clientData).trim()
        if (lastHost.isBlank() || lastPort !in 1..65535) return attempts
        val matchingVerified = clientData.getWarpVerifiedConfigs()
            .firstOrNull { config ->
                config.mode.equals(lastMode, ignoreCase = true) &&
                    config.host.trim().removePrefix("[").removeSuffix("]").equals(lastHost, ignoreCase = true) &&
                    config.port == lastPort
            }
        if (matchingVerified != null && clientData.getWarpVerifiedQualityTier(matchingVerified) < 2) {
            return attempts
        }

        val exactPrefix = attempts.filter { attempt ->
            attempt.endpointHost.trim().removePrefix("[").removeSuffix("]").equals(lastHost, ignoreCase = true) &&
                attempt.port == lastPort &&
                (
                    attempt.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                        attempt.mode.name.equals(lastMode, ignoreCase = true) ||
                        attempt.mode.engine.equals(lastProtocol, ignoreCase = true)
                    )
        }.sortedWith(
            compareBy<ConnectionAttempt>(
                { !it.endpointSource.equals("last-success-exact", ignoreCase = true) },
                { !it.mode.name.equals(lastMode, ignoreCase = true) },
            )
        )

        if (exactPrefix.isEmpty()) return attempts
        return exactPrefix + attempts.filter { it !in exactPrefix }
    }

    private fun sortedVerifiedWarpConfigs(clientData: ClientData): List<WarpVerifiedConfig> {
        val strategyNetworkClass = currentStrategyNetworkClass()
        fun looksLikeWarpLastSuccess(mode: String?, protocol: String?): Boolean {
            val tokens = listOf(mode, protocol)
                .map { it.orEmpty().trim().lowercase(Locale.ROOT) }
                .filter { it.isNotBlank() }
            if (tokens.isEmpty()) return false
            if (tokens.any { it.startsWith("opera") || it.startsWith("quic-") || it.contains("fake") || it.contains("obfs") }) return false
            return tokens.any { it.contains("warp") || it == "masque" }
        }
        val stableFresh = clientData.hasFreshStableLastSuccess()
        val warpFresh = clientData.hasFreshWarpLastSuccess()
        val genericMode = clientData.getLastSuccessMode().trim()
        val genericProtocol = clientData.getLastSuccessProtocol().trim()
        val genericLooksWarp = looksLikeWarpLastSuccess(genericMode, genericProtocol)
        val stableLastMode = clientData.getStableLastSuccessMode()
            ?.takeIf { stableFresh }
            ?.trim()
            .orEmpty()
        val fallbackLastMode = if (warpFresh) {
            clientData.getWarpLastSuccessMode().orEmpty().trim()
        } else if (genericLooksWarp) {
            genericMode
        } else {
            ""
        }
        val lastMode = stableLastMode.ifBlank { fallbackLastMode }
        val lastHost = (clientData.getStableLastSuccessEndpoint()
            ?.takeIf { stableFresh }
            ?: clientData.getWarpLastSuccessEndpoint()?.takeIf { warpFresh }
            ?: clientData.getLastSuccessEndpoint()?.takeIf { genericLooksWarp })
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.lowercase()
            .orEmpty()
        val lastPort = when {
            stableFresh -> clientData.getStableLastSuccessPort()
            warpFresh -> clientData.getWarpLastSuccessPort()
            genericLooksWarp -> clientData.getLastSuccessPort()
            else -> -1
        }
        fun sort(items: List<WarpVerifiedConfig>): List<WarpVerifiedConfig> {
            val runtimeScoreById = items.associate { item ->
                item.id to clientData.getStrategyScore(
                    engine = item.engine,
                    mode = item.mode,
                    host = item.host,
                    port = item.port,
                    strategyScope = item.scope,
                    networkClass = strategyNetworkClass,
                )
            }
            // Корзина, а не сам seedOrder: поштучный прошивочный порядок делал все
            // следующие ключи недостижимыми, см. buildBuiltInWarpAttemptSet.
            fun bundledSeedQueueOrder(item: WarpVerifiedConfig): Int {
                if (!clientData.isBundledSeed(item)) return Int.MAX_VALUE
                return SessionHoldMetric.bundledSeedQueueBucket(item.seedOrder)
            }
            return items
                .filter { !it.manual }
                .sortedWith(
                    compareByDescending<WarpVerifiedConfig> { it.promotedAt }
                        .thenBy {
                            if (clientData.isBundledSeed(it)) bundledSeedQueueOrder(it) else Int.MAX_VALUE
                        }
                        .thenByDescending { clientData.getWarpVerifiedQualityTier(it) }
                        .thenByDescending { clientData.warpConfigHoldGrade(it) }
                        .thenByDescending { clientData.isExactFreshWarpVerifiedLastSuccessMatch(it) }
                        .thenByDescending { it.qualityPingSuccesses }
                        .thenBy {
                            if (it.qualityPingSuccesses > 0 && it.qualityAvgPingMs > 0.0) {
                                it.qualityAvgPingMs
                            } else {
                                Double.MAX_VALUE
                            }
                        }
                        .thenBy { it.qualityFailureCount }
                        .thenByDescending {
                            clientData.getWarpVerifiedPriorityScore(it) +
                                (runtimeScoreById[it.id] ?: 0.0) * 0.55
                        }
                        .thenByDescending { it.lastVerifiedAt }
                        .thenByDescending { it.userImported }
                        // Без замеров порядок обязан остаться прошивочным.
                        .thenBy { it.seedOrder }
                )
        }
        fun exported(scope: String): List<WarpVerifiedConfig> =
            sort(clientData.getWarpVerifiedExportSnapshot(scope))
        fun persisted(scope: String): List<WarpVerifiedConfig> =
            sort(clientData.getWarpVerifiedConfigs(scope))
        fun merged(scope: String): List<WarpVerifiedConfig> =
            sort(
                (clientData.getWarpVerifiedExportSnapshot(scope) + clientData.getWarpVerifiedConfigs(scope))
                    .distinctBy { it.id }
            )

        val messengerConfigs = merged("messenger").takeIf { it.isNotEmpty() }
            ?: exported("messenger").takeIf { it.isNotEmpty() }
            ?: persisted("messenger")
        val defaultFallbackConfigs = (merged("default").takeIf { it.isNotEmpty() }
            ?: exported("default").takeIf { it.isNotEmpty() }
            ?: persisted("default"))
            .filterNot { config -> config.mode.contains("chat", ignoreCase = true) }
        if (!clientData.shouldForceMessengerWarpPriority()) {
            if (messengerConfigs.isEmpty()) {
                return defaultFallbackConfigs
            }
            val hasKnownStableWarp = lastMode.isNotBlank() || lastHost.isNotBlank() || lastPort in 1..65535
            val defaultFrontCount = if (hasKnownStableWarp) 4 else 2
            val messengerFrontCount = if (hasKnownStableWarp) 2 else 4
            val messengerFrontload = messengerConfigs
                .filter { config ->
                    config.mode.contains("chat", ignoreCase = true) ||
                        config.mode.contains("awg", ignoreCase = true)
                }
                .take(messengerFrontCount)
            return (
                defaultFallbackConfigs.take(defaultFrontCount) +
                    messengerFrontload +
                    defaultFallbackConfigs.drop(defaultFrontCount) +
                    messengerConfigs.filterNot { candidate ->
                        messengerFrontload.any { it.id == candidate.id }
                    }
                )
                .distinctBy { it.id }
        }

        return (messengerConfigs + defaultFallbackConfigs).distinctBy { it.id }
    }

    private fun promoteVerifiedAttempts(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
    ): List<ConnectionAttempt> {
        if (attempts.isEmpty()) return attempts
        val prefix = mutableListOf<ConnectionAttempt>()
        for (config in sortedVerifiedWarpConfigs(clientData)) {
            val match = attempts.firstOrNull { attempt ->
                val configModeFamily = if (config.mode.equals("awg", ignoreCase = true)) "warp-awg" else config.mode
                attempt.mode.name.startsWith(configModeFamily, ignoreCase = true) &&
                    attempt.endpointHost.trim().removePrefix("[").removeSuffix("]").equals(config.host, ignoreCase = true) &&
                    attempt.port == config.port
            } ?: continue
            val adjusted = match.copy(
                preferredSni = config.preferredSni.takeIf { it.isNotBlank() } ?: match.preferredSni,
                strategyScope = config.scope,
            )
            if (prefix.none { attemptExactKey(it) == attemptExactKey(adjusted) }) {
                prefix += adjusted
            }
        }
        if (prefix.isEmpty()) return attempts
        val prefixKeys = prefix.mapTo(linkedSetOf()) { attemptExactKey(it) }
        return prefix + attempts.filter { attemptExactKey(it) !in prefixKeys }
    }

    private fun diversifyRankedAttempts(
        scoredSorted: List<Triple<ConnectionAttempt, Double, Int>>,
        seededPrefix: List<ConnectionAttempt> = emptyList(),
    ): List<ConnectionAttempt> {
        if (scoredSorted.isEmpty()) return emptyList()

        val seededSet = seededPrefix.toSet()
        val remaining = scoredSorted.filterNot { it.first in seededSet }.toMutableList()
        val diversified = seededPrefix.toMutableList()
        val recentTargets = ArrayDeque<String>()
        val recentPorts = ArrayDeque<Int>()
        val recentModes = ArrayDeque<String>()
        val recentFamilies = ArrayDeque<String>()

        for (seed in seededPrefix.takeLast(3)) {
            recentTargets.addLast("${seed.endpointHost}:${seed.port}")
            if (recentTargets.size > 3) recentTargets.removeFirst()
            recentPorts.addLast(seed.port)
            if (recentPorts.size > 2) recentPorts.removeFirst()
            recentModes.addLast(seed.mode.name)
            if (recentModes.size > 2) recentModes.removeFirst()
            recentFamilies.addLast(modeFamily(seed.mode))
            if (recentFamilies.size > 3) recentFamilies.removeFirst()
        }

        fun pickIndex(predicate: (Triple<ConnectionAttempt, Double, Int>) -> Boolean): Int {
            for (index in remaining.indices) {
                if (predicate(remaining[index])) return index
            }
            return -1
        }

        while (remaining.isNotEmpty()) {
            val index =
                pickIndex {
                    val attempt = it.first
                    val target = "${attempt.endpointHost}:${attempt.port}"
                    target !in recentTargets &&
                        attempt.port !in recentPorts &&
                        attempt.mode.name !in recentModes &&
                        modeFamily(attempt.mode) !in recentFamilies
                }.takeIf { it >= 0 }
                    ?: pickIndex {
                        val attempt = it.first
                        attempt.mode.name !in recentModes &&
                            modeFamily(attempt.mode) !in recentFamilies &&
                            attempt.port !in recentPorts
                }.takeIf { it >= 0 }
                    ?: pickIndex {
                        val attempt = it.first
                        val target = "${attempt.endpointHost}:${attempt.port}"
                        target !in recentTargets &&
                            attempt.mode.name !in recentModes &&
                            modeFamily(attempt.mode) !in recentFamilies
                    }.takeIf { it >= 0 }
                    ?: pickIndex {
                        val attempt = it.first
                        attempt.mode.name !in recentModes &&
                            modeFamily(attempt.mode) !in recentFamilies
                    }.takeIf { it >= 0 }
                    ?: pickIndex {
                        val attempt = it.first
                        modeFamily(attempt.mode) !in recentFamilies &&
                            attempt.port !in recentPorts
                    }.takeIf { it >= 0 }
                    ?: pickIndex {
                        val attempt = it.first
                        modeFamily(attempt.mode) !in recentFamilies
                    }.takeIf { it >= 0 }
                    ?: pickIndex {
                        val attempt = it.first
                        attempt.port !in recentPorts
                    }.takeIf { it >= 0 }
                    ?: 0

            val selected = remaining.removeAt(index).first
            diversified.add(selected)

            recentTargets.addLast("${selected.endpointHost}:${selected.port}")
            if (recentTargets.size > 3) {
                recentTargets.removeFirst()
            }
            recentPorts.addLast(selected.port)
            if (recentPorts.size > 2) {
                recentPorts.removeFirst()
            }
            recentModes.addLast(selected.mode.name)
            if (recentModes.size > 2) {
                recentModes.removeFirst()
            }
            recentFamilies.addLast(modeFamily(selected.mode))
            if (recentFamilies.size > 3) {
                recentFamilies.removeFirst()
            }
        }

        return diversified
    }

    private fun isDeterministicEndpointSource(source: String): Boolean {
        return when (source.lowercase()) {
            "last-success-exact", "last-success", "verified-config", "awg-fallback", "api", "api-resolved", "scan", "fallback-scan", "masque-scan", "masque-scan-tcp443" -> true
            else -> false
        }
    }

    private fun seedCoreWarpAttempts(
        scoredSorted: List<Triple<ConnectionAttempt, Double, Int>>,
        preferMessengerChatProfiles: Boolean,
    ): List<ConnectionAttempt> {
        if (scoredSorted.isEmpty()) return emptyList()

        val selected = mutableListOf<ConnectionAttempt>()
        val hostUseCount = linkedMapOf<String, Int>()
        val ordinaryWifiLike = !preferMessengerChatProfiles && !lastRestrictedMobileDetected
        val ordinaryPrimaryPortOrder = listOf(500, 1701, 4500)
        val ordinaryDeferredModes = setOf(
            "warp-awg-exact",
            "warp-awg-v2",
            "reserved-only",
        )
        val chatModeOrder = emptyList<String>()
        scoredSorted
            .map { it.first }
            .firstOrNull {
                it.mode.engine != "masque" &&
                    (!preferMessengerChatProfiles || isChatAwareWarpMode(it.mode)) &&
                    (!preferMessengerChatProfiles || it.port in setOf(500, 4500, 1701)) &&
                    it.endpointSource.equals("last-success-exact", ignoreCase = true)
            }
            ?.let { exact ->
                selected += exact
                val hostKey = exact.endpointHost.trim().lowercase()
                hostUseCount[hostKey] = 1
            }
        if (preferMessengerChatProfiles) {
            fun pickMessengerPortCandidate(targetPort: Int): ConnectionAttempt? {
                return scoredSorted
                    .map { it.first }
                    .filter {
                        it.mode.engine != "masque" &&
                            isChatAwareWarpMode(it.mode) &&
                            it.port == targetPort &&
                            isCoreWarpPort(it.port) &&
                            isDeterministicEndpointSource(it.endpointSource)
                    }
                    .minWithOrNull(
                        compareBy<ConnectionAttempt>(
                            { chatModeOrder.indexOfFirst { mode -> mode.equals(it.mode.name, ignoreCase = true) }.let { idx -> if (idx >= 0) idx else Int.MAX_VALUE } },
                            { hostUseCount[it.endpointHost.trim().lowercase()] ?: 0 },
                            { it.endpointHost.trim().lowercase() },
                        )
                    )
            }

            listOf(500, 4500, 1701).forEach { targetPort ->
                pickMessengerPortCandidate(targetPort)?.let { messengerCandidate ->
                    if (messengerCandidate !in selected) {
                        selected += messengerCandidate
                        val hostKey = messengerCandidate.endpointHost.trim().lowercase()
                        hostUseCount[hostKey] = (hostUseCount[hostKey] ?: 0) + 1
                    }
                }
            }

            if (selected.isEmpty()) {
                pickMessengerPortCandidate(988)?.let { messengerCandidate ->
                    if (messengerCandidate !in selected) {
                        selected += messengerCandidate
                        val hostKey = messengerCandidate.endpointHost.trim().lowercase()
                        hostUseCount[hostKey] = (hostUseCount[hostKey] ?: 0) + 1
                    }
                }
            }
        }
        val modePriority = listOf(
            "warp-awg-lite",
            "warp-awg-max",
            "warp-awg-exact",
            "warp-awg-v2",
            "reserved-only",
        )

        fun recordSelection(attempt: ConnectionAttempt) {
            val hostKey = attempt.endpointHost.trim().lowercase()
            hostUseCount[hostKey] = (hostUseCount[hostKey] ?: 0) + 1
        }

        fun pick(pool: List<ConnectionAttempt>): ConnectionAttempt? {
            if (pool.isEmpty()) return null
            return pool.minWithOrNull(
                compareBy<ConnectionAttempt>(
                    { hostUseCount[it.endpointHost.trim().lowercase()] ?: 0 },
                    {
                        (if (ordinaryWifiLike) ordinaryPrimaryPortOrder else it.mode.preferredPorts)
                            .indexOf(it.port)
                            .takeIf { index -> index >= 0 }
                            ?: Int.MAX_VALUE
                    },
                    { warpPortRank(it.port) }
                )
            )
        }

        for (modeName in modePriority) {
            if (ordinaryWifiLike && modeName.lowercase() in ordinaryDeferredModes) continue
            val deterministicPool = scoredSorted
                .map { it.first }
                .filter { attempt ->
                attempt.mode.engine != "masque" &&
                    attempt.mode.name.equals(modeName, ignoreCase = true) &&
                    isCoreWarpPort(attempt.port) &&
                    isDeterministicEndpointSource(attempt.endpointSource)
                }
            val fallbackPool = if (deterministicPool.isNotEmpty()) {
                deterministicPool
            } else {
                scoredSorted
                    .map { it.first }
                    .filter { attempt ->
                attempt.mode.engine != "masque" &&
                    attempt.mode.name.equals(modeName, ignoreCase = true) &&
                    isCoreWarpPort(attempt.port)
                    }
            }
            val selectedAttempt = pick(fallbackPool)
            if (selectedAttempt != null && selectedAttempt !in selected) {
                selected += selectedAttempt
                recordSelection(selectedAttempt)
            }
            if (selected.size >= 10) break
        }

        if (ordinaryWifiLike && selected.size < 10) {
            for (modeName in modePriority) {
                if (modeName.lowercase() !in ordinaryDeferredModes) continue
                val deterministicPool = scoredSorted
                    .map { it.first }
                    .filter { attempt ->
                        attempt.mode.engine != "masque" &&
                            attempt.mode.name.equals(modeName, ignoreCase = true) &&
                            isCoreWarpPort(attempt.port) &&
                            isDeterministicEndpointSource(attempt.endpointSource)
                    }
                val fallbackPool = if (deterministicPool.isNotEmpty()) {
                    deterministicPool
                } else {
                    scoredSorted
                        .map { it.first }
                        .filter { attempt ->
                            attempt.mode.engine != "masque" &&
                                attempt.mode.name.equals(modeName, ignoreCase = true) &&
                                isCoreWarpPort(attempt.port)
                        }
                }
                val selectedAttempt = pick(fallbackPool)
                if (selectedAttempt != null && selectedAttempt !in selected) {
                    selected += selectedAttempt
                    recordSelection(selectedAttempt)
                }
                if (selected.size >= 10) break
            }
        }

        if (selected.size < 10) {
            val extra = scoredSorted
                .map { it.first }
                .filter {
                        it.mode.engine != "masque" &&
                            isCoreWarpPort(it.port) &&
                            modeFamily(it.mode) in setOf("awg", "reserved")
                }
            for (attempt in extra) {
                if (attempt !in selected) {
                    selected += attempt
                    recordSelection(attempt)
                }
                if (selected.size >= 10) break
            }
        }

        return selected
    }

    private fun buildDiagnosticWarpAttemptSet(
        attempts: List<ConnectionAttempt>,
    ): List<ConnectionAttempt> {
        if (attempts.isEmpty()) return attempts
        val selected = mutableListOf<ConnectionAttempt>()
        val seenModes = linkedSetOf<String>()

        attempts.firstOrNull { it.endpointSource.equals("last-success-exact", ignoreCase = true) }?.let {
            selected += it
            seenModes += it.mode.name.lowercase()
        }
        for (attempt in attempts) {
            val modeKey = attempt.mode.name.lowercase()
            if (modeKey in seenModes) continue
            if (attempt.endpointSource.equals("verified-config", ignoreCase = true)) {
                selected += attempt
                seenModes += modeKey
            }
        }

        for (attempt in attempts) {
            val modeKey = attempt.mode.name.lowercase()
            if (modeKey in seenModes) continue
            if (attempt.mode.engine == "masque" || isCoreWarpPort(attempt.port)) {
                selected += attempt
                seenModes += modeKey
            }
        }

        if (selected.size < 18) {
            for (attempt in attempts) {
                if (attempt in selected) continue
                selected += attempt
                if (selected.size >= 18) break
            }
        }

        LogManager.log(
            "WARP diagnostics shortlist: ${selected.size}. " +
                selected.joinToString(",") { "${it.mode.name}@${it.endpointHost}:${it.port}" }
        )
        return selected
    }

    private fun buildWarpQualityDiagnosticsAttemptSet(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
    ): List<ConnectionAttempt> {
        if (attempts.isEmpty()) return attempts
        val rankedBasePool = compactWarpAttemptSet(
            attempts.filter { it.mode.engine != "masque" }.ifEmpty { attempts },
            clientData,
        ).ifEmpty { attempts.filter { it.mode.engine != "masque" }.ifEmpty { attempts } }
        val basePool = rankedBasePool.take(8)
        val selected = linkedSetOf<ConnectionAttempt>()
        val diagnosticLimit = 6

        fun addGroup(candidates: List<ConnectionAttempt>, limit: Int) {
            candidates
                .sortedWith(
                    compareBy<ConnectionAttempt>(
                        { endpointSourceRank(it.endpointSource) },
                        { warpDiscoveryModeRank(it.mode.name) },
                        { warpPortRank(it.port) },
                        { it.endpointHost.contains(':') },
                        { it.endpointHost },
                    )
                )
                .take(limit)
                .forEach(selected::add)
        }

        basePool.firstOrNull()?.let(selected::add)

        val bestPortAnchor = basePool
            .groupBy { "${it.mode.name.lowercase()}|${it.endpointHost.trim().removePrefix("[").removeSuffix("]").lowercase()}" }
            .values
            .filter { group -> group.map { it.port }.distinct().size >= 2 }
            .maxWithOrNull(
                compareBy<List<ConnectionAttempt>>(
                    { it.map { attempt -> attempt.port }.distinct().size },
                    { -endpointSourceRank(it.minByOrNull { attempt -> endpointSourceRank(attempt.endpointSource) }?.endpointSource.orEmpty()) },
                )
            )
        if (bestPortAnchor != null) {
            addGroup(bestPortAnchor, limit = 4)
        }

        val bestModeAnchor = basePool
            .groupBy { "${it.endpointHost.trim().removePrefix("[").removeSuffix("]").lowercase()}|${it.port}" }
            .values
            .filter { group -> group.map { it.mode.name.lowercase() }.distinct().size >= 2 }
            .maxWithOrNull(
                compareBy<List<ConnectionAttempt>>(
                    { it.map { attempt -> attempt.mode.name.lowercase() }.distinct().size },
                    { -warpPortRank(it.firstOrNull()?.port ?: 65535) },
                )
            )
        if (bestModeAnchor != null) {
            addGroup(bestModeAnchor, limit = 4)
        }

        val bestHostAnchor = basePool
            .groupBy { "${it.mode.name.lowercase()}|${it.port}" }
            .values
            .filter { group ->
                group.map {
                    "${it.endpointHost.trim().removePrefix("[").removeSuffix("]").lowercase()}|${normalizeRuntimeTrafficMaskHost(it.preferredSni)}"
                }.distinct().size >= 2
            }
            .maxWithOrNull(
                compareBy<List<ConnectionAttempt>>(
                    {
                        it.map { attempt ->
                            "${attempt.endpointHost.trim().removePrefix("[").removeSuffix("]").lowercase()}|${normalizeRuntimeTrafficMaskHost(attempt.preferredSni)}"
                        }.distinct().size
                    },
                    { warpDiscoveryModeRank(it.firstOrNull()?.mode?.name.orEmpty()) },
                )
            )
        if (bestHostAnchor != null) {
            addGroup(bestHostAnchor, limit = 4)
        }

        if (selected.size < diagnosticLimit) {
            buildDiagnosticWarpAttemptSet(basePool).forEach(selected::add)
        }
        if (selected.size < diagnosticLimit) {
            for (attempt in basePool) {
                selected += attempt
                if (selected.size >= diagnosticLimit) break
            }
        }

        val finalSelected = selected.take(diagnosticLimit)
        LogManager.log(
            "WARP quality diagnostics shortlist: ${finalSelected.size} из top-${basePool.size} ranked attempts. " +
                finalSelected.joinToString(",") { "${it.mode.name}@${it.endpointHost}:${it.port}" }
        )
        return finalSelected
    }

    private fun buildWarpQualityDiagnosticEntry(
        clientData: ClientData,
        attempt: ConnectionAttempt,
        outcome: String,
        attemptDurationMs: Long,
        stableDurationMs: Long,
    ): WarpQualityDiagnosticEntry {
        val matchingConfig = clientData.getWarpVerifiedConfigs()
            .asSequence()
            .filter { config ->
                config.mode.equals(attempt.mode.name, ignoreCase = true) &&
                    config.host.trim().removePrefix("[").removeSuffix("]").equals(
                        attempt.endpointHost.trim().removePrefix("[").removeSuffix("]"),
                        ignoreCase = true,
                    ) &&
                    config.port == attempt.port
            }
            .maxByOrNull { maxOf(it.qualityLastCheckedAt, it.lastVerifiedAt) }
        val sni = currentWarpMaskHost
            .orEmpty()
            .ifBlank { normalizeRuntimeTrafficMaskHost(attempt.preferredSni) }
            .ifBlank { matchingConfig?.preferredSni.orEmpty() }
        return WarpQualityDiagnosticEntry(
            mode = attempt.mode.name,
            host = attempt.endpointHost.trim().removePrefix("[").removeSuffix("]"),
            port = attempt.port,
            endpointSource = normalizeVerifiedConfigSource(attempt.endpointSource),
            sni = sni,
            outcome = outcome,
            attemptDurationMs = attemptDurationMs,
            stableDurationMs = stableDurationMs,
            probeCount = matchingConfig?.qualityProbeCount ?: 0,
            pingSuccesses = matchingConfig?.qualityPingSuccesses ?: 0,
            avgPingMs = matchingConfig?.qualityAvgPingMs ?: 0.0,
        )
    }

    private fun warpQualityDiagnosticScore(entry: WarpQualityDiagnosticEntry): Double {
        val coverage = if (entry.probeCount > 0) {
            entry.pingSuccesses.toDouble() / entry.probeCount.toDouble()
        } else {
            0.0
        }
        val outcomeBonus = when (entry.outcome) {
            AttemptOutcome.SUCCESS -> 30.0
            AttemptOutcome.UNSTABLE -> 12.0
            AttemptOutcome.HANDSHAKE -> 2.0
            else -> -18.0
        }
        val latencyPenalty = when {
            entry.avgPingMs > 0.0 -> (entry.avgPingMs / 18.0).coerceAtMost(80.0)
            else -> 70.0
        }
        return coverage * 100.0 + outcomeBonus - latencyPenalty
    }

    private fun buildWarpQualityFactorLabel(
        label: String,
        entries: List<WarpQualityDiagnosticEntry>,
        factorSelector: (WarpQualityDiagnosticEntry) -> String,
        controlSelector: (WarpQualityDiagnosticEntry) -> String,
    ): Pair<String, Double> {
        val comparableGroups = entries
            .groupBy(controlSelector)
            .values
            .mapNotNull { group ->
                val distinctFactors = group.map(factorSelector).distinct()
                if (distinctFactors.size < 2) {
                    null
                } else {
                    val scores = group.map(::warpQualityDiagnosticScore)
                    (scores.maxOrNull() ?: 0.0) - (scores.minOrNull() ?: 0.0)
                }
            }
        if (comparableGroups.isEmpty()) {
            return "$label: данных недостаточно" to 0.0
        }
        val averageSpread = comparableGroups.average()
        val factorScores = entries
            .groupBy(factorSelector)
            .mapValues { (_, group) -> group.map(::warpQualityDiagnosticScore).average() }
            .toList()
            .sortedBy { it.second }
        val worst = factorScores.firstOrNull()?.let { "${it.first}=${"%.1f".format(it.second)}" }.orEmpty()
        val best = factorScores.lastOrNull()?.let { "${it.first}=${"%.1f".format(it.second)}" }.orEmpty()
        return "$label: spread=${"%.1f".format(averageSpread)}, worst=$worst, best=$best" to averageSpread
    }

    private fun summarizeWarpQualityDiagnostics(entries: List<WarpQualityDiagnosticEntry>): String {
        if (entries.isEmpty()) {
            LogManager.log("WARP quality diagnostics: ни одной завершённой попытки не собрано.")
            return "Диагностика завершена: данных нет"
        }
        val successCount = entries.count { it.outcome == AttemptOutcome.SUCCESS }
        val coverageAvg = entries.mapNotNull { entry ->
            if (entry.probeCount > 0) entry.pingSuccesses.toDouble() / entry.probeCount.toDouble() else null
        }.average().takeIf { !it.isNaN() } ?: 0.0
        val avgPing = entries.mapNotNull { entry ->
            entry.avgPingMs.takeIf { it.isFinite() && it > 0.0 }
        }.average().takeIf { !it.isNaN() } ?: 0.0
        val (portLabel, portSpread) = buildWarpQualityFactorLabel(
            label = "PORT",
            entries = entries,
            factorSelector = { it.port.toString() },
            controlSelector = { "${it.mode.lowercase()}|${it.host.lowercase()}" },
        )
        val (modeLabel, modeSpread) = buildWarpQualityFactorLabel(
            label = "MODE",
            entries = entries,
            factorSelector = { it.mode.lowercase() },
            controlSelector = { "${it.host.lowercase()}|${it.port}" },
        )
        val (endpointLabel, endpointSpread) = buildWarpQualityFactorLabel(
            label = "ENDPOINT/SNI",
            entries = entries,
            factorSelector = {
                val normalizedSni = normalizeRuntimeTrafficMaskHost(it.sni)
                if (normalizedSni.isBlank()) it.host.lowercase() else "${it.host.lowercase()}|sni=$normalizedSni"
            },
            controlSelector = { "${it.mode.lowercase()}|${it.port}" },
        )
        LogManager.log(
            "WARP quality diagnostics totals: attempts=${entries.size}, success=$successCount, " +
                "coverage=${"%.0f".format(coverageAvg * 100.0)}%, avgPing=${avgPing.toInt().takeIf { it > 0 } ?: "-"}ms."
        )
        LogManager.log("WARP quality diagnostics factor: $portLabel")
        LogManager.log("WARP quality diagnostics factor: $modeLabel")
        LogManager.log("WARP quality diagnostics factor: $endpointLabel")
        val factorSpreads = listOf(
            "порт" to portSpread,
            "стратегия" to modeSpread,
            "endpoint/SNI" to endpointSpread,
        ).sortedByDescending { it.second }
        val leader = factorSpreads.first()
        val runnerUp = factorSpreads.getOrNull(1)
        val dominantCause = when {
            leader.second <= 0.0 -> "данных для изоляции причины недостаточно"
            runnerUp == null || leader.second >= runnerUp.second + 8.0 ->
                "сильнее всего качество зависит от фактора: ${leader.first}"
            else -> "влияют сразу несколько факторов, сильнее всего ${leader.first} и ${runnerUp.first}"
        }
        return "Диагностика завершена: $dominantCause"
    }

    private fun buildWarpConfigDiscoveryAttemptSet(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
    ): List<ConnectionAttempt> {
        val reverifyAttempts = buildWarpConfigReverifyAttemptSet(attempts, clientData)
        val discoveryCandidates = attempts
            .filter { attempt ->
                !clientData.hasWarpVerifiedConfig(attempt.mode.name, attempt.endpointHost, attempt.port)
            }
        if (reverifyAttempts.isEmpty() && discoveryCandidates.isEmpty()) {
            LogManager.log("Проверка WARP-конфигураций: ни сохранённых, ни новых кандидатов нет.")
            return emptyList()
        }

        val selected = linkedSetOf<ConnectionAttempt>()
        reverifyAttempts.forEach(selected::add)
        val hostUse = linkedMapOf<String, Int>()
        val modeUse = linkedMapOf<String, Int>()
        val preferredPorts = buildWarpDiscoveryPortPriority(discoveryCandidates, clientData)
        val preferredModes = buildWarpDiscoveryModePriority(discoveryCandidates, clientData)
        val preferredHosts = buildWarpDiscoveryHostPriority(discoveryCandidates, clientData)
        val baseMaxAttempts = if (isLegacy32BitDevice()) 48 else 56
        val maxAttempts = maxOf(baseMaxAttempts, selected.size + 24)

        fun record(attempt: ConnectionAttempt) {
            val hostKey = attempt.endpointHost.trim().removePrefix("[").removeSuffix("]").lowercase()
            val modeKey = attempt.mode.name.lowercase()
            hostUse[hostKey] = (hostUse[hostKey] ?: 0) + 1
            modeUse[modeKey] = (modeUse[modeKey] ?: 0) + 1
        }

        reverifyAttempts.forEach(::record)

        fun addAttempt(attempt: ConnectionAttempt, force: Boolean = false): Boolean {
            if (attempt in selected) return false
            val hostKey = attempt.endpointHost.trim().removePrefix("[").removeSuffix("]").lowercase()
            val modeKey = attempt.mode.name.lowercase()
            if (!force) {
                if ((hostUse[hostKey] ?: 0) >= 4) return false
                if ((modeUse[modeKey] ?: 0) >= 3) return false
            }
            selected += attempt
            record(attempt)
            return true
        }

        val masqueDiscoveryFrontload = buildList {
            add(
                discoveryCandidates.firstOrNull { attempt ->
                    attempt.endpointSource.equals("masque-scan-tcp443", ignoreCase = true) &&
                        modeFamily(attempt.mode) == "masque-consumer"
                }
            )
            add(
                discoveryCandidates.firstOrNull { attempt ->
                    attempt.endpointSource.equals("masque-scan-tcp443", ignoreCase = true) &&
                        modeFamily(attempt.mode) == "masque-zt"
                }
            )
            if (isLegacy32BitDevice()) {
                add(
                    discoveryCandidates.firstOrNull { attempt ->
                        attempt.port == 1701 && modeFamily(attempt.mode) == "masque-consumer"
                    }
                )
                add(
                    discoveryCandidates.firstOrNull { attempt ->
                        attempt.port == 500 && modeFamily(attempt.mode) == "masque-consumer"
                    }
                )
                add(
                    discoveryCandidates.firstOrNull { attempt ->
                        attempt.port == 4500 && modeFamily(attempt.mode) == "masque-zt"
                    }
                )
                add(
                    discoveryCandidates.firstOrNull { attempt ->
                        attempt.port == 8443 && modeFamily(attempt.mode) == "masque-consumer"
                    }
                )
                add(
                    discoveryCandidates.firstOrNull { attempt ->
                        attempt.port == 4443 && modeFamily(attempt.mode) == "masque-consumer"
                    }
                )
            } else {
                add(
                    discoveryCandidates.firstOrNull { attempt ->
                        attempt.port == 4443 && modeFamily(attempt.mode) == "masque-consumer"
                    }
                )
                add(
                    discoveryCandidates.firstOrNull { attempt ->
                        attempt.port == 4500 && modeFamily(attempt.mode) == "masque-zt"
                    }
                )
                add(
                    discoveryCandidates.firstOrNull { attempt ->
                        attempt.port == 8443 && modeFamily(attempt.mode) == "masque-consumer"
                    }
                )
            }
        }.filterNotNull()

        masqueDiscoveryFrontload.forEach { addAttempt(it, force = true) }

        buildDiagnosticWarpAttemptSet(discoveryCandidates).forEach {
            val shouldForce = it.endpointSource.equals("last-success-exact", ignoreCase = true)
            addAttempt(it, force = shouldForce)
        }

        for (modeName in preferredModes.take(18)) {
            for (host in preferredHosts.take(14)) {
                val bestForPair = discoveryCandidates.firstOrNull { attempt ->
                    attempt.endpointHost.equals(host, ignoreCase = true) &&
                        attempt.mode.name.equals(modeName, ignoreCase = true) &&
                        attempt.port in preferredPorts
                } ?: discoveryCandidates.firstOrNull { attempt ->
                    attempt.endpointHost.equals(host, ignoreCase = true) &&
                        attempt.mode.name.equals(modeName, ignoreCase = true)
                }
                if (bestForPair != null) {
                    addAttempt(bestForPair)
                }
                if (selected.size >= maxAttempts) break
            }
            if (selected.size >= maxAttempts) break
        }

        if (selected.size < maxAttempts) {
            for (host in preferredHosts.take(14)) {
                val hostCandidates = discoveryCandidates
                    .filter { it.endpointHost.equals(host, ignoreCase = true) }
                    .sortedWith(
                        compareBy<ConnectionAttempt>(
                            { endpointSourceRank(it.endpointSource) },
                            { warpDiscoveryModeRank(it.mode.name) },
                            { warpPortRank(it.port) },
                        )
                    )
                for (attempt in hostCandidates) {
                    addAttempt(attempt)
                    if (selected.size >= maxAttempts) break
                }
                if (selected.size >= maxAttempts) break
            }
        }

        if (selected.size < maxAttempts) {
            val families = listOf("masque-consumer", "masque-zt", "awg", "reserved")
            for (port in preferredPorts.take(7)) {
                for (family in families) {
                    val familyAttempt = discoveryCandidates.firstOrNull { attempt ->
                        attempt.port == port &&
                            modeFamily(attempt.mode) == family
                    }
                    if (familyAttempt != null) {
                        addAttempt(familyAttempt)
                    }
                    if (selected.size >= maxAttempts) break
                }
                if (selected.size >= maxAttempts) break
            }
        }

        if (selected.size < maxAttempts) {
            val exploratory = discoveryCandidates.sortedWith(
                compareBy<ConnectionAttempt>(
                    { endpointSourceRank(it.endpointSource) },
                    { warpDiscoveryModeRank(it.mode.name) },
                    { warpPortRank(it.port) },
                    { it.endpointHost.contains(':') },
                    { it.endpointHost },
                )
            )
            for (attempt in exploratory) {
                addAttempt(attempt)
                if (selected.size >= maxAttempts) break
            }
        }

        LogManager.log(
            "Проверка WARP-конфигураций: reverify=${reverifyAttempts.size}, total=${selected.size}. " +
                selected.joinToString(",") { "${it.mode.name}@${it.endpointHost}:${it.port}" }
        )
        return selected.toList()
    }

    private fun buildWarpNetworkAdaptationAttemptSet(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
    ): List<ConnectionAttempt> {
        val verifiedConfigs = clientData.getWarpVerifiedConfigs()
            .filter { !it.manual && it.endpointSource.equals("bundled-seed", ignoreCase = true) }
            .sortedWith(
                compareBy<WarpVerifiedConfig> { it.seedOrder }
                    .thenBy { it.host }
                    .thenBy { it.port }
                    .thenBy { it.mode }
            )
        Log.w("NovaAdapt", "buildWarpNetworkAdaptationAttemptSet: bundledSeedCount=${verifiedConfigs.size}, modes=${verifiedConfigs.groupBy { it.mode }.mapValues { it.value.size }}")
        if (verifiedConfigs.isEmpty()) return emptyList()

        val exactAttemptMap = linkedMapOf<String, ConnectionAttempt>()
        attempts.forEach { attempt ->
            exactAttemptMap.putIfAbsent(
                buildWarpDiscoveryAttemptKey(attempt.mode.name, attempt.endpointHost, attempt.port),
                attempt,
            )
        }
        val modeTemplateMap = linkedMapOf<String, TransportMode>()
        attempts.forEach { attempt ->
            val key = "${attempt.mode.engine.lowercase()}|${attempt.mode.name.lowercase()}"
            modeTemplateMap.putIfAbsent(key, attempt.mode)
        }

        val selected = mutableListOf<ConnectionAttempt>()
        val fallbackCount = AtomicInteger(0)
        for (config in verifiedConfigs) {
            val exactKey = buildWarpDiscoveryAttemptKey(config.mode, config.host, config.port)
            val directTemplate = exactAttemptMap[exactKey]?.mode
            val modeKey = "${config.engine.lowercase()}|${config.mode.lowercase()}"
            val baseModeName = config.mode.removeSuffix("-mask")
            val baseModeKey = "${config.engine.lowercase()}|${baseModeName.lowercase()}"
            val template = directTemplate ?: modeTemplateMap[modeKey] ?: modeTemplateMap[baseModeKey]
            val attempt = if (template != null) {
                materializeWarpVerifiedAdaptationAttempt(config, template, clientData)
            } else {
                fallbackCount.incrementAndGet()
                buildFallbackWarpVerifiedAdaptationAttempt(config, clientData)
            }
            selected += attempt
        }

        LogManager.log(
            "Адаптация WARP: подготовлено ${selected.size}/${verifiedConfigs.size} сохранённых профилей, " +
                "fallback=${fallbackCount.get()}. " +
                selected.take(12).joinToString(",") { attemptLogLabel(it) }
        )
        return selected
    }

    private fun materializeWarpVerifiedAdaptationAttempt(
        config: WarpVerifiedConfig,
        template: TransportMode,
        clientData: ClientData,
    ): ConnectionAttempt {
        val normalizedHost = config.host.trim().removePrefix("[").removeSuffix("]")
        val explicitAwgImport = config.userImported && hasExplicitAwgImport(config.rawConfig)
        val hasRawIdentity = config.userImported && hasImportedWireGuardIdentity(config.rawConfig)
        val primaryPort = config.port
        val mode = template.copy(
            name = config.mode,
            engine = config.engine.ifBlank { template.engine },
            preferredPorts = emptyList(),
            restrictToPreferredPorts = false,
            reservedMode = if (explicitAwgImport) "off" else template.reservedMode,
            novaModeOverride = if (explicitAwgImport) "plain-wireguard" else template.novaModeOverride,
            fakePacketsOverride = false,
            reservedModeOverride = if (explicitAwgImport) "off" else template.reservedModeOverride,
            preferImportedRawDns = false,
            preferImportedRawMtu = explicitAwgImport || template.preferImportedRawMtu,
            preferImportedRawIdentity = hasRawIdentity,
            omitReservedLineIfMissingInImport = explicitAwgImport || template.omitReservedLineIfMissingInImport,
        )
        return ConnectionAttempt(
            endpointHost = normalizedHost,
            port = primaryPort,
            mode = mode,
            endpointSource = normalizeVerifiedConfigSource(config.endpointSource),
            importedConfigHost = config.host.takeIf { config.userImported },
            strategyScope = config.scope,
            preferredSni = config.preferredSni.takeIf { it.isNotBlank() },
        )
    }

    private fun clientDataPreferredPortsForConfig(
        config: WarpVerifiedConfig,
        clientData: ClientData? = null,
        limit: Int = 12,
    ): List<Int> {
        val localPorts = config.preferredPorts.map { it.port }
        val aggregatedPorts = clientData?.getPreferredWarpPortsFor(
            engine = config.engine,
            mode = config.mode,
            host = config.host,
            scope = config.scope,
            limit = limit + 1,
        ).orEmpty()
        return (localPorts + aggregatedPorts)
            .asSequence()
            .filter { it in 1..65535 && it != config.port }
            .distinct()
            .take(limit)
            .toList()
    }

    private fun clientDataPrimaryPortForConfig(
        config: WarpVerifiedConfig,
        clientData: ClientData? = null,
    ): Int {
        return clientDataPreferredPortsForConfig(config, clientData, limit = 1).firstOrNull()
            ?: config.port
    }

    private fun buildFallbackWarpVerifiedAdaptationAttempt(
        config: WarpVerifiedConfig,
        clientData: ClientData,
    ): ConnectionAttempt {
        val normalizedHost = config.host.trim().removePrefix("[").removeSuffix("]")
        val normalizedMode = config.mode.trim().ifBlank { "warp-awg" }
        val normalizedModeLower = normalizedMode.lowercase()
        val primaryPort = config.port
        val normalizedEngine = config.engine.trim().ifBlank {
            if (normalizedModeLower.startsWith("masque")) "masque" else "wireguard"
        }
        val masqueMode = normalizedEngine.equals("masque", ignoreCase = true) ||
            normalizedModeLower.startsWith("masque")
        val preferredPorts = emptyList<Int>()
        val mode = if (masqueMode) {
            TransportMode(
                name = normalizedMode,
                engine = "masque",
                useFakePackets = false,
                reservedMode = "off",
                preferredPorts = preferredPorts,
                restrictToPreferredPorts = false,
                masqueSni = if (normalizedModeLower.contains("consumer")) {
                    "consumer-masque.cloudflareclient.com"
                } else {
                    "zt-masque.cloudflareclient.com"
                },
            )
        } else {
            val explicitAwgImport = config.userImported && hasExplicitAwgImport(config.rawConfig)
            val hasRawIdentity = config.userImported && hasImportedWireGuardIdentity(config.rawConfig)
            TransportMode(
                name = normalizedMode,
                engine = "wireguard",
                useFakePackets = false,
                reservedMode = if (explicitAwgImport) "off" else "handshake",
                preferredPorts = preferredPorts,
                restrictToPreferredPorts = false,
                novaModeOverride = if (explicitAwgImport) "plain-wireguard" else null,
                fakePacketsOverride = false,
                reservedModeOverride = if (explicitAwgImport) "off" else null,
                preferImportedRawDns = false,
                preferImportedRawMtu = explicitAwgImport,
                preferImportedRawIdentity = hasRawIdentity,
                omitReservedLineIfMissingInImport = explicitAwgImport,
            )
        }
        return ConnectionAttempt(
            endpointHost = normalizedHost,
            port = primaryPort,
            mode = mode,
            endpointSource = normalizeVerifiedConfigSource(config.endpointSource),
            importedConfigHost = config.host.takeIf { config.userImported },
            strategyScope = config.scope,
            preferredSni = config.preferredSni.takeIf { it.isNotBlank() },
        )
    }

    private fun buildWarpAdaptationSniRetryAttempts(
        failedAttempts: List<ConnectionAttempt>,
        clientData: ClientData,
        masqueIdentityAvailable: Boolean,
        maxSniPerProfile: Int = 10,
    ): List<ConnectionAttempt> {
        if (failedAttempts.isEmpty() || maxSniPerProfile <= 0) return emptyList()
        val globalCandidates = linkedSetOf<String>()

        fun addCandidate(value: String?) {
            val normalized = normalizeRuntimeTrafficMaskHost(value)
            if (normalized.isNotBlank()) {
                globalCandidates += normalized
            }
        }

        addCandidate(clientData.getWarpTrafficMaskLastSuccessfulHost())
        addCandidate(clientData.getTrafficMaskLastSuccessfulHostForPool(ClientData.TRAFFIC_MASK_POOL_RUSSIA))
        clientData.getPreferredTrafficMaskHosts(TrafficMaskCatalog.getWhiteHosts(this), limit = 32)
            .forEach(::addCandidate)
        clientData.getPreferredTrafficMaskHosts(TrafficMaskCatalog.getRussiaHosts(this), limit = 32)
            .forEach(::addCandidate)

        val selected = mutableListOf<ConnectionAttempt>()
        val seenRetryKeys = linkedSetOf<String>()
        for (attempt in failedAttempts) {
            if (attempt.mode.engine == "masque" && !masqueIdentityAvailable) {
                continue
            }
            val attemptedSni = normalizeRuntimeTrafficMaskHost(attempt.preferredSni)
            val perProfileCandidates = globalCandidates
                .asSequence()
                .filter { it != attemptedSni }
                .take(maxSniPerProfile)
                .toList()
            for (sni in perProfileCandidates) {
                val key = "${attemptExactKey(attempt)}|$sni"
                if (!seenRetryKeys.add(key)) continue
                selected += attempt.copy(
                    endpointSource = normalizeVerifiedConfigSource(attempt.endpointSource).let { source ->
                        if (source.contains("sni-retry", ignoreCase = true)) source else "$source-sni-retry"
                    },
                    preferredSni = sni,
                )
            }
        }
        if (selected.isNotEmpty()) {
            LogManager.log(
                "Адаптация WARP SNI-кандидаты: профилей=${failedAttempts.size}, попыток=${selected.size}, " +
                    "домены=" + selected.mapNotNull { it.preferredSni }.distinct().take(12).joinToString(",")
            )
        }
        return selected
    }

    private fun buildWarpAdaptationPortRetryAttempts(
        failedAttempts: List<ConnectionAttempt>,
        clientData: ClientData,
        masqueIdentityAvailable: Boolean,
    ): List<ConnectionAttempt> {
        if (failedAttempts.isEmpty()) return emptyList()
        val selected = mutableListOf<ConnectionAttempt>()
        val seenRetryKeys = linkedSetOf<String>()

        for (attempt in failedAttempts) {
            if (attempt.mode.engine == "masque" && !masqueIdentityAvailable) {
                continue
            }
            val learnedPorts = clientData.getPreferredWarpPortsFor(
                engine = attempt.mode.engine,
                mode = attempt.mode.name,
                host = attempt.endpointHost,
                scope = attempt.strategyScope ?: "default",
                limit = 16,
            )
            val candidatePorts = if (attempt.mode.engine == "masque") {
                (learnedPorts + masqueExperimentalPortOrder()).distinct()
            } else {
                (learnedPorts + warpExperimentalPortOrder()).distinct()
            }
                .filter { it in 1..65535 && it != attempt.port }
                .sortedBy { warpPortRank(it) }

            for (port in candidatePorts) {
                val key = "${attemptExactKey(attempt)}|port=$port|sni=${normalizeRuntimeTrafficMaskHost(attempt.preferredSni)}"
                if (!seenRetryKeys.add(key)) continue
                selected += attempt.copy(
                    port = port,
                    endpointSource = normalizeVerifiedConfigSource(attempt.endpointSource).let { source ->
                        if (source.contains("port-retry", ignoreCase = true)) source else "$source-port-retry"
                    },
                    mode = attempt.mode.copy(
                        preferredPorts = (listOf(port) + attempt.mode.preferredPorts.filter { it != port }).distinct(),
                        restrictToPreferredPorts = true,
                    ),
                )
            }
        }
        if (selected.isNotEmpty()) {
            LogManager.log(
                "Адаптация WARP port-кандидаты: профилей=${failedAttempts.size}, попыток=${selected.size}, " +
                    "порты=" + selected.map { it.port }.distinct().take(24).joinToString(",")
            )
        }
        return selected
    }

    private fun buildWarpConfigReverifyAttemptSet(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
    ): List<ConnectionAttempt> {
        val verifiedConfigs = clientData.getWarpVerifiedConfigs()
            .filter { !it.manual }
            .sortedWith(
                compareByDescending<WarpVerifiedConfig> { clientData.getWarpVerifiedQualityTier(it) }
                    .thenByDescending { it.qualityPingSuccesses }
                    .thenBy {
                        if (it.qualityPingSuccesses > 0 && it.qualityAvgPingMs > 0.0) {
                            it.qualityAvgPingMs
                        } else {
                            Double.MAX_VALUE
                        }
                    }
                    .thenBy { it.qualityFailureCount }
                    .thenByDescending { clientData.getWarpVerifiedPriorityScore(it) }
                    .thenByDescending { it.lastVerifiedAt }
            )
        if (verifiedConfigs.isEmpty()) return emptyList()

        val userImportedCount = verifiedConfigs.count { it.userImported }
        val importedReverifyAttempts = buildUserImportedWarpAttemptSet(
            attempts = attempts,
            clientData = clientData,
            limit = userImportedCount.coerceAtLeast(1),
        )

        val exactAttemptMap = linkedMapOf<String, ConnectionAttempt>()
        attempts.forEach { attempt ->
            exactAttemptMap.putIfAbsent(
                buildWarpDiscoveryAttemptKey(
                    attempt.mode.name,
                    attempt.endpointHost,
                    attempt.port,
                ),
                attempt,
            )
        }

        val modeTemplateMap = linkedMapOf<String, TransportMode>()
        attempts.forEach { attempt ->
            val key = "${attempt.mode.engine.lowercase()}|${attempt.mode.name.lowercase()}"
            modeTemplateMap.putIfAbsent(key, attempt.mode)
        }

        val selected = linkedSetOf<ConnectionAttempt>()
        importedReverifyAttempts.forEach(selected::add)
        val missing = mutableListOf<String>()
        for (config in verifiedConfigs) {
            if (config.userImported) continue
            val exactKey = buildWarpDiscoveryAttemptKey(config.mode, config.host, config.port)
            val existingAttempt = exactAttemptMap[exactKey]
            if (existingAttempt != null) {
                selected += existingAttempt.copy(
                    preferredSni = config.preferredSni.takeIf { it.isNotBlank() }
                        ?: existingAttempt.preferredSni,
                    strategyScope = config.scope,
                )
                continue
            }

            val modeKey = "${config.engine.lowercase()}|${config.mode.lowercase()}"
            val modeTemplate = modeTemplateMap[modeKey]
            if (modeTemplate != null) {
                val primaryPort = clientDataPrimaryPortForConfig(config, clientData)
                selected += ConnectionAttempt(
                    endpointHost = config.host.trim().removePrefix("[").removeSuffix("]"),
                    port = primaryPort,
                    mode = modeTemplate.copy(
                        preferredPorts = listOf(primaryPort) + modeTemplate.preferredPorts.filter { it != primaryPort },
                    ),
                    endpointSource = config.endpointSource.ifBlank { "verified-config" },
                    strategyScope = config.scope,
                    preferredSni = config.preferredSni.takeIf { it.isNotBlank() },
                )
            } else {
                missing += "${config.mode}@${config.host}:${config.port}"
            }
        }

        if (missing.isNotEmpty()) {
            LogManager.log(
                "Не удалось подготовить часть сохранённых WARP-конфигураций к перепроверке: " +
                    missing.joinToString(",")
            )
        }
        LogManager.log(
            "Перепроверка сохранённых WARP-конфигураций: ${selected.size}/${verifiedConfigs.size}, " +
                "user=${importedReverifyAttempts.size}. " +
                selected.joinToString(",") { attemptLogLabel(it) }
        )
        return selected.toList()
    }

    private fun buildWarpDiscoveryAttemptKey(mode: String, host: String, port: Int): String {
        return "${mode.trim().lowercase()}|${host.trim().removePrefix("[").removeSuffix("]").lowercase()}|$port"
    }

    private fun reduceDiscoveryRankingInputs(
        attempts: List<ConnectionAttempt>,
        maxTotal: Int = 720,
    ): List<ConnectionAttempt> {
        if (attempts.size <= maxTotal) return attempts

        val selected = mutableListOf<ConnectionAttempt>()
        val hostCounts = mutableMapOf<String, Int>()
        val modeCounts = mutableMapOf<String, Int>()
        val hostPortCounts = mutableMapOf<String, Int>()

        val ordered = attempts.sortedWith(
            compareBy<ConnectionAttempt>(
                { endpointSourceRank(it.endpointSource) },
                { warpDiscoveryModeRank(it.mode.name) },
                { warpPortRank(it.port) },
                { it.endpointHost.contains(':') },
                { it.endpointHost },
            )
        )

        for (attempt in ordered) {
            val host = attempt.endpointHost.trim().removePrefix("[").removeSuffix("]").lowercase()
            val mode = attempt.mode.name.lowercase()
            val hostPort = "$host:${attempt.port}"
            val force =
                attempt.endpointSource.equals("verified-config", ignoreCase = true) ||
                    attempt.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                    attempt.endpointSource.equals("last-success", ignoreCase = true)
            if (!force) {
                if ((hostCounts[host] ?: 0) >= 16) continue
                if ((modeCounts[mode] ?: 0) >= 10) continue
                if ((hostPortCounts[hostPort] ?: 0) >= 4) continue
            }
            selected += attempt
            hostCounts[host] = (hostCounts[host] ?: 0) + 1
            modeCounts[mode] = (modeCounts[mode] ?: 0) + 1
            hostPortCounts[hostPort] = (hostPortCounts[hostPort] ?: 0) + 1
            if (selected.size >= maxTotal) break
        }

        return if (selected.isNotEmpty()) selected else ordered.take(maxTotal)
    }

    private fun buildWarpDiscoveryHostPriority(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
    ): List<String> {
        val selected = linkedSetOf<String>()

        fun add(host: String?) {
            val normalized = host?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
            if (normalized.isBlank()) return
            val match = attempts.firstOrNull { it.endpointHost.equals(normalized, ignoreCase = true) }?.endpointHost
            selected += match ?: normalized
        }

        add(clientData.getLastSuccessEndpoint())
        sortedVerifiedWarpConfigs(clientData)
            .filter { !it.manual }
            .take(12)
            .forEach { add(it.host) }
        attempts
            .filter {
                it.endpointSource.equals("verified-config", ignoreCase = true) ||
                    it.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                    it.endpointSource.equals("last-success", ignoreCase = true) ||
                    it.endpointSource.equals("masque-scan-tcp443", ignoreCase = true) ||
                    it.endpointSource.equals("masque-scan", ignoreCase = true) ||
                    it.endpointSource.equals("known-anycast", ignoreCase = true) ||
                    it.endpointSource.equals("scan", ignoreCase = true) ||
                    it.endpointSource.equals("fallback-scan", ignoreCase = true)
            }
            .forEach { add(it.endpointHost) }
        attempts.forEach { add(it.endpointHost) }
        return selected.toList()
    }

    private fun buildWarpDiscoveryPortPriority(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
    ): List<Int> {
        val ports = linkedSetOf<Int>()
        clientData.getLastSuccessPort().takeIf { it in 1..65535 }?.let { ports += it }
        sortedVerifiedWarpConfigs(clientData)
            .filter { !it.manual }
            .map { it.port }
            .filter { it in 1..65535 }
            .forEach { ports += it }
        listOf(1701, 500, 443, 8443, 4500, 4443, 8095, 988, 2408).forEach { ports += it }
        attempts.sortedBy { warpPortRank(it.port) }.forEach { ports += it.port }
        return ports.toList()
    }

    private fun buildWarpDiscoveryModePriority(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
    ): List<String> {
        val available = attempts.map { it.mode.name }.distinct()
        val selected = linkedSetOf<String>()

        fun add(modeName: String?) {
            if (modeName.isNullOrBlank()) return
            available.firstOrNull { it.equals(modeName, ignoreCase = true) }?.let { selected += it }
        }

        add(clientData.getLastSuccessMode())
        sortedVerifiedWarpConfigs(clientData)
            .filter { !it.manual }
            .take(12)
            .forEach { add(it.mode) }

        val staticOrder = listOf(
            "MASQUE-CONSUMER",
            "MASQUE-ZT",
            "warp-awg-exact",
            "warp-awg-v2",
            "warp-awg-lite",
            "warp-awg",
            "warp-awg-max",
            "warp-v1",
            "warp-v2",
            "warp-v3",
            "reserved-only",
        )
        staticOrder.forEach(::add)
        attempts.forEach { add(it.mode.name) }
        return selected.toList()
    }

    private fun warpDiscoveryModeRank(modeName: String): Int {
        return when {
            modeName.startsWith("warp-awg", ignoreCase = true) -> 0
            modeName.startsWith("warp-v", ignoreCase = true) -> 1
            modeName.equals("reserved-only", ignoreCase = true) -> 2
            else -> 8
        }
    }

    private fun buildWarpConfigDescription(
        attempt: ConnectionAttempt,
    ): String {
        return buildString {
            appendLine("HOST=${attempt.endpointHost}")
            appendLine("PORT=${attempt.port}")
            appendLine("PROTOCOL=${attempt.mode.engine.uppercase()}")
            appendLine("STRATEGY=${attempt.mode.name}")
            appendLine("SOURCE=${attempt.endpointSource}")
            val preferredSni = normalizeRuntimeTrafficMaskHost(attempt.preferredSni)
            if (preferredSni.isNotBlank()) {
                appendLine("PREFERRED_SNI=$preferredSni")
            }
        }.trim()
    }

    private fun broadcastWarpConfigDiscovery(
        running: Boolean,
        foundCount: Int,
        message: String,
    ) {
        ClientData(this).saveWarpDiscoverySnapshot(
            running = running,
            foundCount = foundCount,
            message = message,
            ordinal = currentAttemptOrdinal,
            total = currentAttemptTotal,
        )
        sendBroadcast(Intent(ACTION_WARP_CONFIG_DISCOVERY).apply {
            putExtra(EXTRA_DISCOVERY_RUNNING, running)
            putExtra(EXTRA_DISCOVERY_FOUND_COUNT, foundCount)
            putExtra(EXTRA_DISCOVERY_MESSAGE, message)
            putExtra(EXTRA_ATTEMPT_ORDINAL, currentAttemptOrdinal)
            putExtra(EXTRA_ATTEMPT_TOTAL, currentAttemptTotal)
            setPackage(packageName)
        })
    }

    private fun freezeWarpDiscoveryProgress(
        existingSnapshot: WarpDiscoverySnapshot?,
    ): Pair<Int, Int> {
        val frozenTotal = maxOf(
            currentAttemptTotal,
            existingSnapshot?.total ?: 0,
        )
        val frozenOrdinal = maxOf(
            currentAttemptOrdinal,
            existingSnapshot?.ordinal ?: 0,
        ).coerceAtMost(frozenTotal.takeIf { it > 0 } ?: Int.MAX_VALUE)
        currentAttemptOrdinal = frozenOrdinal
        currentAttemptTotal = frozenTotal
        return frozenOrdinal to frozenTotal
    }

    private fun buildStoppedWarpDiscoveryMessage(
        existingSnapshot: WarpDiscoverySnapshot?,
        adaptToNetwork: Boolean? = null,
    ): String {
        val (ordinal, total) = freezeWarpDiscoveryProgress(existingSnapshot)
        val baseMessage = existingSnapshot?.message.orEmpty().trim()
        val stopPrefix = when (adaptToNetwork) {
            true -> "Адаптация остановлена"
            false -> "Проверка остановлена"
            null -> if (baseMessage.lowercase(Locale.getDefault()).contains("адаптац")) {
                "Адаптация остановлена"
            } else {
                "Проверка остановлена"
            }
        }
        return when {
            total > 0 && ordinal > 0 -> "$stopPrefix на $ordinal из $total"
            total > 0 -> "$stopPrefix на 0 из $total"
            baseMessage.isNotBlank() -> "$stopPrefix"
            else -> stopPrefix
        }
    }

    private fun compactWarpAttemptSet(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
    ): List<ConnectionAttempt> {
        if (attempts.isEmpty()) return attempts
        val hasReservedWarp = attempts.any { attempt ->
            val name = attempt.mode.name.lowercase()
            name.startsWith("warp-") || name == "reserved-only"
        }
        val verifiedCount = attempts.count { it.endpointSource.equals("verified-config", ignoreCase = true) }
        val attemptLimit = when {
            verifiedCount >= 4 -> 7
            verifiedCount >= 2 -> 8
            verifiedCount >= 1 -> 11
            hasReservedWarp -> 14
            else -> 12
        }
        if (attempts.size <= attemptLimit) return attempts

        val selected = mutableListOf<ConnectionAttempt>()
        val preferMessengerShortlist = clientData.shouldForceMessengerWarpPriority()
        val ordinaryWifiLike = !preferMessengerShortlist && !lastRestrictedMobileDetected
        val ordinaryPrimaryPortOrder = listOf(500, 1701, 4500)
        val ordinaryDeferredModes = setOf(
            "warp-awg-exact",
            "warp-awg-v2",
            "reserved-only",
        )
        val coreFrontload = buildHostDiverseCoreWarpFrontload(
            attempts = attempts,
            limit = 3,
        )

        fun verifiedSourceOrder(attempt: ConnectionAttempt): Int {
            return when (attempt.endpointSource.lowercase()) {
                "last-success-exact" -> 0
                "verified-config" -> 1
                "last-success" -> 2
                "bundled-seed" -> 3
                else -> 4
            }
        }

        fun familyFrontloadOrder(attempt: ConnectionAttempt): Int {
            return when {
                preferMessengerShortlist && isChatAwareWarpMode(attempt.mode) -> 0
                modeFamily(attempt.mode) == "awg" -> 1
                modeFamily(attempt.mode) == "reserved" -> 4
                else -> 5
            }
        }

        coreFrontload.forEach { candidate ->
            if (candidate !in selected) {
                selected += candidate
            }
        }
        if (preferMessengerShortlist) {
            (
                listOf(500, 1701, 4500).firstNotNullOfOrNull { preferredPort ->
                    attempts.firstOrNull {
                        isChatAwareWarpMode(it.mode) &&
                            it.port == preferredPort &&
                            isCoreWarpPort(it.port)
                    }
                } ?: attempts.firstOrNull {
                    isChatAwareWarpMode(it.mode) &&
                        isCoreWarpPort(it.port)
                }
            )?.let { candidate ->
                if (candidate !in selected) selected += candidate
            }
        }
        attempts.firstOrNull {
            it.endpointSource.equals("last-success-exact", ignoreCase = true)
        }?.let {
            if (it !in selected) selected += it
        }

        fun preferredPrimaryPortIndex(attempt: ConnectionAttempt): Int {
            val preferredPorts = if (ordinaryWifiLike) ordinaryPrimaryPortOrder else attempt.mode.preferredPorts
            return preferredPorts.indexOf(attempt.port).takeIf { it >= 0 } ?: Int.MAX_VALUE
        }

        fun endpointSourcePriority(attempt: ConnectionAttempt): Int {
            return when (attempt.endpointSource.lowercase()) {
                "last-success-exact" -> 0
                "verified-config" -> 1
                "last-success" -> 2
                "bundled-seed" -> 3
                "awg-fallback" -> 4
                "api", "api-resolved", "neighbor-anycast" -> 5
                else -> 6
            }
        }

        fun pickModeCandidate(modeName: String): ConnectionAttempt? {
            val corePool = attempts.filter { attempt ->
                attempt !in selected &&
                    attempt.mode.name.equals(modeName, ignoreCase = true) &&
                    isCoreWarpPort(attempt.port)
            }
            val pool = if (corePool.isNotEmpty()) {
                corePool
            } else {
                attempts.filter { attempt ->
                    attempt !in selected &&
                        attempt.mode.name.equals(modeName, ignoreCase = true)
                }
            }
            return pool.minWithOrNull(
                compareBy<ConnectionAttempt>(
                    { preferredPrimaryPortIndex(it) },
                    { endpointSourcePriority(it) },
                    { warpPortRank(it.port) },
                )
            )
        }

        val primaryModePriority = listOf(
            "warp-awg-lite",
            "warp-awg-max",
            "warp-awg-exact",
            "warp-awg-v2",
            "warp-awg",
            "warp-v1",
            "warp-v2",
            "warp-v3",
            "reserved-only",
        )

        val verifiedFamilies = mutableSetOf<String>()
        val verifiedHostPorts = mutableSetOf<String>()
        attempts
            .filter { it.endpointSource.equals("verified-config", ignoreCase = true) }
            .forEach { attempt ->
                if (selected.count { it.endpointSource.equals("verified-config", ignoreCase = true) } >= 3) {
                    return@forEach
                }
                val family = modeFamily(attempt.mode)
                val hostPort = "${attempt.endpointHost.lowercase()}:${attempt.port}"
                val shouldTake = when {
                    hostPort in verifiedHostPorts -> false
                    family !in verifiedFamilies -> true
                    selected.none {
                        it.endpointSource.equals("verified-config", ignoreCase = true) &&
                            it.endpointHost.equals(attempt.endpointHost, ignoreCase = true)
                    } -> true
                    else -> false
                }
                if (shouldTake && attempt !in selected) {
                    selected += attempt
                    verifiedFamilies += family
                    verifiedHostPorts += hostPort
                }
            }

        attempts.firstOrNull { attempt ->
            attempt !in selected &&
                attempt.endpointSource in setOf("api", "api-resolved", "neighbor-anycast", "awg-fallback") &&
                isCoreWarpPort(attempt.port) &&
                modeFamily(attempt.mode) == "awg"
        }?.let { selected += it }

        for (modeName in primaryModePriority) {
            if (ordinaryWifiLike && modeName.lowercase() in ordinaryDeferredModes) continue
            val candidate = pickModeCandidate(modeName)
            if (candidate != null) {
                selected += candidate
            }
            if (selected.size >= attemptLimit) break
        }

        if (ordinaryWifiLike && selected.size < attemptLimit) {
            for (modeName in primaryModePriority) {
                if (modeName.lowercase() !in ordinaryDeferredModes) continue
                val candidate = pickModeCandidate(modeName)
                if (candidate != null) {
                    selected += candidate
                }
                if (selected.size >= attemptLimit) break
            }
        }

        val familyCounts = mutableMapOf<String, Int>()
        val hostCounts = mutableMapOf<String, Int>()
        val portCounts = mutableMapOf<Int, Int>()

        fun familyCap(family: String): Int {
            return when (family) {
                "awg" -> 3
                else -> 1
            }
        }

        fun remember(attempt: ConnectionAttempt) {
            val family = modeFamily(attempt.mode)
            val hostKey = attempt.endpointHost.trim().lowercase()
            familyCounts[family] = (familyCounts[family] ?: 0) + 1
            hostCounts[hostKey] = (hostCounts[hostKey] ?: 0) + 1
            portCounts[attempt.port] = (portCounts[attempt.port] ?: 0) + 1
        }

        selected.forEach { remember(it) }

        for (attempt in attempts) {
            if (attempt in selected) continue
            val family = modeFamily(attempt.mode)
            val hostKey = attempt.endpointHost.trim().lowercase()
            val hasEnoughCoreCoverage = selected.count { isCoreWarpPort(it.port) } >= 3
            if (!hasEnoughCoreCoverage && attempt.port == 988) continue
            if ((familyCounts[family] ?: 0) >= familyCap(family)) continue
            if ((hostCounts[hostKey] ?: 0) >= 2) continue
            if (isCoreWarpPort(attempt.port) && (portCounts[attempt.port] ?: 0) >= 2) continue
            selected += attempt
            remember(attempt)
            if (selected.size >= attemptLimit) break
        }

        if (selected.size < attemptLimit) {
            for (attempt in attempts) {
                if (attempt in selected) continue
                selected += attempt
                if (selected.size >= attemptLimit) break
            }
        }

        val compactedSelected = if (ordinaryWifiLike) {
            val conservativeWave = buildOrdinaryWifiConservativeWarpWave(
                attempts = attempts,
                limit = minOf(attemptLimit, 4),
                clientData = clientData,
            )
            if (conservativeWave.size >= 3) {
                (conservativeWave + selected.filter { it !in conservativeWave }).take(attemptLimit)
            } else {
                selected
            }
        } else {
            selected
        }

        val strategyScope = if (clientData.shouldForceMessengerWarpPriority()) "messenger" else "default"
        val compactImportedAttempts = buildUserImportedWarpAttemptSet(
            attempts,
            clientData,
            limit = if (clientData.isImportedConfigSourceActive()) attempts.size.coerceAtLeast(1) else 8,
        )
        val userImportedPrefix = selectPreferredUserImportedWarpPrefix(
            attempts = attempts,
            importedAttempts = compactImportedAttempts,
            clientData = clientData,
            strategyScope = strategyScope,
            strategyNetworkClass = currentStrategyNetworkClass(),
            limit = minOf(compactImportedAttempts.size, attemptLimit),
        )
        val userImportedKeys = userImportedPrefix.mapTo(linkedSetOf()) { attemptExactKey(it) }
        val finalSelected = if (userImportedPrefix.isEmpty()) {
            compactedSelected
        } else {
            (userImportedPrefix + compactedSelected.filterNot { candidate ->
                attemptExactKey(candidate) in userImportedKeys
            }).take(attemptLimit)
        }

        LogManager.log(
            "Сжали WARP-перебор: ${attempts.size} -> ${finalSelected.size}. " +
                "Shortlist: ${finalSelected.take(10).joinToString(",") { "${it.mode.name}@${it.endpointHost}:${it.port}" }}"
        )

        return finalSelected
    }

    private fun buildPrimaryWarpAttemptSetForMaskedRetry(
        attempts: List<ConnectionAttempt>,
        clientData: ClientData,
        preferMessengerChatProfiles: Boolean = false,
        aggressiveFastStart: Boolean = false,
    ): List<ConnectionAttempt> {
        if (attempts.isEmpty()) return attempts
        val legacy32 = isLegacy32BitDevice()
        val preferChatAwareRetry = attempts.any { isChatAwareWarpMode(it.mode) }
        val filtered = attempts.filter { attempt ->
            val family = modeFamily(attempt.mode)
            family == "awg" || family == "reserved"
        }

        val preferred = if (legacy32) {
            val portOrder = listOf(500, 1701, 4500, 2408)
            filtered
                .sortedWith(
                    compareBy<ConnectionAttempt>(
                        { portOrder.indexOf(it.port).let { idx -> if (idx >= 0) idx else Int.MAX_VALUE } },
                        {
                            when (it.endpointSource.lowercase()) {
                                "verified-config" -> 0
                                "last-success-exact" -> 1
                                "last-success" -> 2
                                else -> 3
                            }
                        },
                        {
                            when (modeFamily(it.mode)) {
                                "awg" -> 0
                                "reserved" -> 1
                                else -> 3
                            }
                        }
                    )
                )
                .distinctBy { "${it.mode.engine}:${it.mode.name}:${it.endpointHost}:${it.port}" }
                .take(4)
        } else if (aggressiveFastStart && preferMessengerChatProfiles && preferChatAwareRetry) {
            val selected = mutableListOf<ConnectionAttempt>()
            val chatPortOrder = listOf(500, 1701, 4500)
            val genericPortOrder = listOf(1701, 500, 4500)
            val preferredSourceOrder = listOf("last-success-exact", "verified-config", "last-success")
            val availableExactKeys = attempts
                .map { "${it.mode.name.lowercase()}@${it.endpointHost.trim().lowercase()}:${it.port}" }
                .toSet()
            val preferredVerifiedChat = sortedVerifiedWarpConfigs(clientData)
                .filter {
                    !it.engine.equals("masque", ignoreCase = true) &&
                        it.scope == "messenger" &&
                        it.port in chatPortOrder &&
                        it.mode.contains("chat", ignoreCase = true) &&
                        availableExactKeys.contains("${it.mode.lowercase()}@${it.host.trim().lowercase()}:${it.port}")
                }
                .sortedWith(
                    compareBy<WarpVerifiedConfig>(
                        { chatPortOrder.indexOf(it.port).let { idx -> if (idx >= 0) idx else Int.MAX_VALUE } },
                        { if (it.mode.contains("chatstealth", ignoreCase = true)) 0 else if (it.mode.contains("chatmax", ignoreCase = true)) 1 else 2 },
                    )
                )
            fun addCandidate(candidate: ConnectionAttempt?) {
                if (candidate != null && candidate !in selected) {
                    selected += candidate
                }
            }
            addCandidate(preferredVerifiedChat.firstNotNullOfOrNull { config ->
                attempts.firstOrNull {
                    isChatAwareWarpMode(it.mode) &&
                        it.mode.name.equals(config.mode, ignoreCase = true) &&
                        it.endpointHost.equals(config.host, ignoreCase = true) &&
                        it.port == config.port
                }
            })
            if (selected.isEmpty()) {
                loop@ for (port in chatPortOrder) {
                    for (source in preferredSourceOrder) {
                        val candidate = attempts.firstOrNull {
                            isChatAwareWarpMode(it.mode) &&
                                it.port == port &&
                                it.endpointSource.equals(source, ignoreCase = true)
                        } ?: continue
                        addCandidate(candidate)
                        break@loop
                    }
                }
            }
            if (selected.isEmpty()) {
                addCandidate(attempts.firstOrNull {
                    isChatAwareWarpMode(it.mode) && isCoreWarpPort(it.port)
                })
            }
            genericPortOrder.forEach { preferredPort ->
                preferredSourceOrder.firstNotNullOfOrNull { source ->
                    attempts.firstOrNull {
                        !isChatAwareWarpMode(it.mode) &&
                            it.port == preferredPort &&
                            isCoreWarpPort(it.port) &&
                            it.endpointSource.equals(source, ignoreCase = true)
                    }
                }?.let { addCandidate(it) }
                if (selected.size >= 2) return@forEach
            }
            if (selected.size < 3) {
                chatPortOrder.forEach { preferredPort ->
                    preferredSourceOrder.firstNotNullOfOrNull { source ->
                        attempts.firstOrNull {
                            isChatAwareWarpMode(it.mode) &&
                                it.port == preferredPort &&
                                isCoreWarpPort(it.port) &&
                                it.endpointSource.equals(source, ignoreCase = true) &&
                                selected.none { picked -> picked.port == it.port && picked.mode.name.equals(it.mode.name, ignoreCase = true) }
                        }
                    }?.let { addCandidate(it) }
                    if (selected.size >= 3) return@forEach
                }
            }
            if (selected.none { it.endpointSource.equals("verified-config", ignoreCase = true) }) {
                addCandidate(attempts.firstOrNull {
                    it.endpointSource.equals("verified-config", ignoreCase = true) &&
                        modeFamily(it.mode) == "awg" &&
                        !isChatAwareWarpMode(it.mode) &&
                        isCoreWarpPort(it.port)
                })
            }
            selected
        } else {
            buildList {
                attempts.firstOrNull {
                    it.endpointSource.equals("last-success-exact", ignoreCase = true)
                }?.let {
                    add(it)
                }
                attempts.firstOrNull {
                    it.endpointSource.equals("verified-config", ignoreCase = true) &&
                        modeFamily(it.mode) == "awg"
                }?.let {
                    if (it !in this) add(it)
                }
            }
        }

        val pool = (preferred + filtered)
            .distinctBy { "${it.mode.engine}:${it.mode.name}:${it.endpointHost}:${it.port}" }

        fun endpointSourceOrder(attempt: ConnectionAttempt): Int {
            return when (attempt.endpointSource.lowercase()) {
                "last-success-exact" -> 0
                "verified-config" -> 1
                "last-success" -> 2
                "bundled-seed" -> 3
                else -> 4
            }
        }

        fun familyOrder(attempt: ConnectionAttempt): Int {
            return when {
                preferMessengerChatProfiles && isChatAwareWarpMode(attempt.mode) -> 0
                modeFamily(attempt.mode) == "awg" -> 1
                modeFamily(attempt.mode) == "reserved" -> 4
                else -> 5
            }
        }

        val verifiedCount = pool.count { it.endpointSource.equals("verified-config", ignoreCase = true) }
        val limit = when {
            aggressiveFastStart && preferMessengerChatProfiles && preferChatAwareRetry -> 2
            preferMessengerChatProfiles && preferChatAwareRetry -> 2
            legacy32 && verifiedCount >= 4 -> 4
            legacy32 && verifiedCount >= 2 -> 4
            legacy32 && verifiedCount >= 1 -> 4
            legacy32 -> 5
            verifiedCount >= 4 -> 5
            verifiedCount >= 2 -> 6
            verifiedCount >= 1 -> 7
            else -> 8
        }

        val hostDiverseCoreFrontload = buildHostDiverseCoreWarpFrontload(
            attempts = pool,
            limit = minOf(limit, 4),
        )

        val interleavedPrefix = buildInterleavedWarpPrefix(
            attempts = pool,
            preferMessengerChatProfiles = preferMessengerChatProfiles && preferChatAwareRetry,
            limit = minOf(limit, if (aggressiveFastStart) 4 else 5),
            corePortsOnly = true,
        )
        val corePreferredPool = pool.filter { isCoreWarpPort(it.port) }
        val ordinaryWifiLike = !legacy32 && !preferMessengerChatProfiles && !lastRestrictedMobileDetected
        val compactedSelected = if (
            preferMessengerChatProfiles &&
            preferChatAwareRetry &&
            corePreferredPool.size >= 3
        ) {
            val coreOnlyPrimary = (interleavedPrefix.filter { isCoreWarpPort(it.port) } + corePreferredPool)
                .distinctBy { "${it.mode.engine}:${it.mode.name}:${it.endpointHost}:${it.port}" }
            val messengerFrontload = buildHostDiverseCoreWarpFrontload(
                attempts = coreOnlyPrimary,
                limit = minOf(limit, 3),
            )
            (messengerFrontload + coreOnlyPrimary)
                .distinctBy { "${it.mode.engine}:${it.mode.name}:${it.endpointHost}:${it.port}" }
                .take(limit)
                .ifEmpty { attempts.take(limit.coerceAtMost(attempts.size)) }
        } else if (ordinaryWifiLike) {
            val ordinaryWifiLimit = maxOf(limit, 6)
            val conservativeWave = buildOrdinaryWifiConservativeWarpWave(
                attempts = pool,
                limit = minOf(ordinaryWifiLimit, 4),
                clientData = clientData,
            )
            val primaryPool =
                conservativeWave +
                    hostDiverseCoreFrontload +
                    interleavedPrefix +
                    corePreferredPool +
                    pool
            primaryPool
                .distinctBy { "${it.mode.engine}:${it.mode.name}:${it.endpointHost}:${it.port}" }
                .take(ordinaryWifiLimit)
                .ifEmpty { attempts.take(ordinaryWifiLimit.coerceAtMost(attempts.size)) }
        } else {
            val primaryPool =
                hostDiverseCoreFrontload +
                    interleavedPrefix +
                    corePreferredPool +
                    pool
            primaryPool
                .distinctBy { "${it.mode.engine}:${it.mode.name}:${it.endpointHost}:${it.port}" }
                .take(limit)
                .ifEmpty { attempts.take(limit.coerceAtMost(attempts.size)) }
        }
        val strategyScope = if (clientData.shouldForceMessengerWarpPriority()) "messenger" else "default"
        val primaryImportedAttempts = buildUserImportedWarpAttemptSet(
            pool,
            clientData,
            limit = if (clientData.isImportedConfigSourceActive()) pool.size.coerceAtLeast(1) else 8,
        )
        val userImportedPrefix = selectPreferredUserImportedWarpPrefix(
            attempts = pool,
            importedAttempts = primaryImportedAttempts,
            clientData = clientData,
            strategyScope = strategyScope,
            strategyNetworkClass = currentStrategyNetworkClass(),
            limit = minOf(primaryImportedAttempts.size, limit),
        )
        val userImportedKeys = userImportedPrefix.mapTo(linkedSetOf()) { attemptExactKey(it) }
        val selected = if (userImportedPrefix.isEmpty()) {
            compactedSelected
        } else {
            (userImportedPrefix + compactedSelected.filterNot { candidate ->
                attemptExactKey(candidate) in userImportedKeys
            }).take(limit)
        }
        val promotedSelected = if (userImportedPrefix.isEmpty()) {
            promoteFreshExactWinner(selected, clientData)
        } else {
            val promotedTail = promoteFreshExactWinner(
                selected.filterNot { candidate -> attemptExactKey(candidate) in userImportedKeys },
                clientData,
            )
            userImportedPrefix + promotedTail.filterNot { candidate ->
                attemptExactKey(candidate) in userImportedKeys
            }
        }
        LogManager.log(
            "Сжали первичный WARP-shortlist перед masked retry: ${attempts.size} -> ${promotedSelected.size}. " +
                "Shortlist: ${promotedSelected.joinToString(",") { "${it.mode.name}@${it.endpointHost}:${it.port}" }}"
        )
        return promotedSelected
    }

    private fun buildOrdinaryWifiConservativeWarpWave(
        attempts: List<ConnectionAttempt>,
        limit: Int,
        clientData: ClientData,
    ): List<ConnectionAttempt> {
        if (attempts.isEmpty() || limit <= 0) return emptyList()

        fun hostKey(attempt: ConnectionAttempt): String {
            return attempt.endpointHost.trim().removePrefix("[").removeSuffix("]").lowercase()
        }

        fun endpointPortKey(attempt: ConnectionAttempt): String {
            return "${hostKey(attempt)}:${attempt.port}"
        }

        val attemptOrder = attempts.withIndex().associate { attemptExactKey(it.value) to it.index }
        val nowMs = System.currentTimeMillis()
        val corePool = attempts.filter { attempt ->
            attempt.mode.engine != "masque" && isCoreWarpPort(attempt.port)
        }
        if (corePool.isEmpty()) return emptyList()

        val (coolingDown, activePool) = corePool.partition { attempt ->
            isWarpAttemptCoolingDown(clientData, attempt, nowMs)
        }

        val selected = mutableListOf<ConnectionAttempt>()
        val usedEndpointPorts = mutableSetOf<String>()
        val usedHosts = mutableSetOf<String>()

        fun addFromPool(pool: List<ConnectionAttempt>) {
            pool.forEach { attempt ->
                if (selected.size >= limit) return
                val endpointPortKey = endpointPortKey(attempt)
                val trustedDeterministic = attempt.endpointSource.equals("last-success-exact", ignoreCase = true) ||
                    attempt.endpointSource.equals("verified-config", ignoreCase = true) ||
                    attempt.endpointSource.equals("bundled-seed", ignoreCase = true)
                if (endpointPortKey in usedEndpointPorts) return@forEach
                if (!trustedDeterministic && hostKey(attempt) in usedHosts) return@forEach
                selected += attempt
                usedEndpointPorts += endpointPortKey
                usedHosts += hostKey(attempt)
            }
        }

        addFromPool(
            activePool.sortedBy { attemptOrder[attemptExactKey(it)] ?: Int.MAX_VALUE }
        )
        if (selected.isEmpty()) {
            addFromPool(
                coolingDown.sortedBy { attemptOrder[attemptExactKey(it)] ?: Int.MAX_VALUE }
            )
        }
        if (selected.isEmpty()) {
            corePool.sortedBy { attemptOrder[attemptExactKey(it)] ?: Int.MAX_VALUE }
                .forEach { attempt ->
                    if (selected.size >= limit) return@forEach
                    if (attempt !in selected) selected += attempt
                }
        }

        if (coolingDown.isNotEmpty()) {
            LogManager.log(
                "Ordinary Wi-Fi WARP warmup: отложили ${coolingDown.size} попыток из-за cooldown " +
                    "(validated-no-traffic/no-inbound раньше на них уже ловили)."
            )
        }
        if (selected.isNotEmpty()) {
            LogManager.log(
                "Ordinary Wi-Fi WARP warmup trio: ${
                    selected.take(limit).joinToString(",") { "${it.mode.name}@${it.endpointHost}:${it.port}" }
                }"
            )
        }

        return selected
    }

    private fun buildHostDiverseCoreWarpFrontload(
        attempts: List<ConnectionAttempt>,
        limit: Int,
    ): List<ConnectionAttempt> {
        if (attempts.isEmpty() || limit <= 0) return emptyList()

        val corePool = attempts.filter { isCoreWarpPort(it.port) }
        if (corePool.isEmpty()) return emptyList()

        val selected = mutableListOf<ConnectionAttempt>()
        val usedHosts = mutableSetOf<String>()
        val usedPorts = mutableSetOf<Int>()
        val stableAwgModes = setOf(
            "warp-awg-max",
            "warp-awg-lite",
            "warp-awg-exact",
        )

        fun hostKey(attempt: ConnectionAttempt): String = attempt.endpointHost.trim().lowercase()
        fun starterPriority(attempt: ConnectionAttempt): Int {
            val name = attempt.mode.name.lowercase()
            return when {
                name in stableAwgModes -> 0
                modeFamily(attempt.mode) == "awg" -> 1
                modeFamily(attempt.mode) == "reserved" -> 2
                else -> 8
            }
        }

        fun remember(attempt: ConnectionAttempt) {
            val host = hostKey(attempt)
            if (host.isNotBlank()) usedHosts += host
            usedPorts += attempt.port
            selected += attempt
        }

        val starter = corePool
            .withIndex()
            .minWithOrNull(
                compareBy<IndexedValue<ConnectionAttempt>>(
                    { starterPriority(it.value) },
                    { it.index },
                )
            )?.value ?: corePool.first()
        remember(starter)

        while (selected.size < limit) {
            val nextCandidate =
                corePool.firstOrNull { candidate ->
                    candidate !in selected &&
                        hostKey(candidate).isNotBlank() &&
                        hostKey(candidate) !in usedHosts &&
                        candidate.port !in usedPorts
                } ?: corePool.firstOrNull { candidate ->
                    candidate !in selected &&
                        hostKey(candidate).isNotBlank() &&
                        hostKey(candidate) !in usedHosts
                } ?: corePool.firstOrNull { candidate ->
                    candidate !in selected && candidate.port !in usedPorts
                } ?: corePool.firstOrNull { candidate ->
                    candidate !in selected
                }

            if (nextCandidate == null) break
            remember(nextCandidate)
        }

        return selected
    }

    private fun warpInterleaveBucket(
        attempt: ConnectionAttempt,
        preferMessengerChatProfiles: Boolean,
    ): String {
        val family = modeFamily(attempt.mode)
        return when {
            preferMessengerChatProfiles && isChatAwareWarpMode(attempt.mode) -> "chat"
            family == "reserved" -> "reserved"
            family == "awg" -> "awg"
            else -> family
        }
    }

    private fun buildInterleavedWarpPrefix(
        attempts: List<ConnectionAttempt>,
        preferMessengerChatProfiles: Boolean,
        limit: Int,
        corePortsOnly: Boolean,
    ): List<ConnectionAttempt> {
        if (attempts.isEmpty() || limit <= 0) return emptyList()

        val earlyPorts = if (preferMessengerChatProfiles) {
            if (limit >= 6) listOf(500, 1701, 4500, 443) else listOf(500, 1701, 4500)
        } else {
            listOf(1701, 500, 4500)
        }
        val bucketOrder = if (preferMessengerChatProfiles) {
            listOf("chat", "awg", "reserved")
        } else {
            listOf("awg", "reserved")
        }
        val filtered = attempts.filter { attempt ->
            !corePortsOnly || isCoreWarpPort(attempt.port)
        }
        val pool = filtered.ifEmpty { attempts }
        val selected = mutableListOf<ConnectionAttempt>()
        val usedHosts = mutableSetOf<String>()
        val usedPorts = mutableSetOf<Int>()
        val usedBuckets = mutableSetOf<String>()

        fun hostKey(attempt: ConnectionAttempt): String {
            return attempt.endpointHost.trim().removePrefix("[").removeSuffix("]").lowercase()
        }

        fun pickCandidate(
            preferredBucket: String? = null,
            preferredPort: Int? = null,
            requireFreshBucket: Boolean = true,
            requireFreshPort: Boolean = true,
            requireFreshHost: Boolean = true,
        ): ConnectionAttempt? {
            val passes = listOf(
                Triple(requireFreshBucket, requireFreshPort, requireFreshHost),
                Triple(requireFreshBucket, requireFreshPort, false),
                Triple(requireFreshBucket, false, requireFreshHost),
                Triple(false, requireFreshPort, requireFreshHost),
                Triple(requireFreshBucket, false, false),
                Triple(false, requireFreshPort, false),
                Triple(false, false, requireFreshHost),
                Triple(false, false, false),
            )
            for ((freshBucket, freshPort, freshHost) in passes) {
                val candidate = pool.firstOrNull { attempt ->
                    if (attempt in selected) return@firstOrNull false
                    val bucket = warpInterleaveBucket(attempt, preferMessengerChatProfiles)
                    val host = hostKey(attempt)
                    (preferredBucket == null || bucket == preferredBucket) &&
                        (preferredPort == null || attempt.port == preferredPort) &&
                        (!freshBucket || bucket !in usedBuckets) &&
                        (!freshPort || attempt.port !in usedPorts) &&
                        (!freshHost || host !in usedHosts)
                }
                if (candidate != null) return candidate
            }
            return null
        }

        fun remember(attempt: ConnectionAttempt) {
            selected += attempt
            usedHosts += hostKey(attempt)
            usedPorts += attempt.port
            usedBuckets += warpInterleaveBucket(attempt, preferMessengerChatProfiles)
        }

        if (preferMessengerChatProfiles) {
            earlyPorts.firstNotNullOfOrNull { port ->
                pickCandidate(
                    preferredBucket = "chat",
                    preferredPort = port,
                    requireFreshBucket = false,
                )
            }?.let { candidate ->
                remember(candidate)
            }
        }

        for (bucket in bucketOrder) {
            val candidate = earlyPorts.firstNotNullOfOrNull { port ->
                pickCandidate(preferredBucket = bucket, preferredPort = port)
            } ?: pickCandidate(preferredBucket = bucket)
            if (candidate != null) {
                remember(candidate)
                if (selected.size >= limit) break
            }
        }

        if (selected.size < limit) {
            for (port in earlyPorts) {
                val candidate = pickCandidate(preferredPort = port)
                if (candidate != null) {
                    remember(candidate)
                    if (selected.size >= limit) break
                }
            }
        }

        if (selected.size < limit) {
            for (attempt in pool) {
                if (attempt !in selected) {
                    remember(attempt)
                    if (selected.size >= limit) break
                }
            }
        }

        return selected
    }

    private fun resolveNextAttemptIndex(
        connectionAttempts: List<ConnectionAttempt>,
        currentIndex: Int,
        currentAttempt: ConnectionAttempt,
        quickRotate: Boolean,
    ): Int {
        if (connectionAttempts.isEmpty()) return 0
        val defaultNext = (currentIndex + 1) % connectionAttempts.size
        if (!quickRotate || currentAttempt.mode.engine == "masque" || connectionAttempts.size <= 1) {
            return defaultNext
        }

        val currentHost = currentAttempt.endpointHost.trim().removePrefix("[").removeSuffix("]").lowercase()
        val currentPort = currentAttempt.port

        fun sameEndpoint(attempt: ConnectionAttempt): Boolean {
            val host = attempt.endpointHost.trim().removePrefix("[").removeSuffix("]").lowercase()
            return host == currentHost && attempt.port == currentPort
        }
        val nextAttempt = connectionAttempts[defaultNext]
        if (!sameEndpoint(nextAttempt)) {
            return defaultNext
        }

        val nextDistinctIndex = (1 until connectionAttempts.size)
            .map { (currentIndex + it) % connectionAttempts.size }
            .firstOrNull { idx ->
                val attempt = connectionAttempts[idx]
                attempt.mode.engine != "masque" && !sameEndpoint(attempt)
            }

        return nextDistinctIndex ?: defaultNext
    }

    /**
     * Подстановка маскировочного имени в WARP выключена — решение владельца
     * (2026-08-10).
     *
     * Что она делала. Каждое удачное подключение записывало активный домен
     * маскировки в `preferredSni` профиля, а прогон адаптации проставлял его
     * **всем пятидесяти** разом. Дальше имя жило в сохранённых конфигурациях и
     * подставлялось само, причём в обход выключателя маскировки: ветка с
     * сохранённым именем в [publishWarpTrafficMaskHint] стояла раньше проверки
     * «маскировка включена». Когда сеть переставала пропускать это имя, перебор
     * шёл по всему списку и сбрасывал его по одному — восемь секунд на профиль,
     * минуты вместо секунд на подключение.
     *
     * Цена подстановки оказалась выше пользы: WARP — это WireGuard поверх UDP,
     * имени в нём нет, маскировка была отдельным ходом ради ограниченных сетей.
     * Список доменов и выключатель остаются в настройках и в каталоге — убрана
     * именно подстановка, чтобы вернуть её можно было одним местом, а не
     * восстанавливая по кускам.
     */
    private fun resolveWarpTrafficMaskHosts(
        @Suppress("UNUSED_PARAMETER") clientData: ClientData,
    ): List<String> {
        return emptyList()
    }

    private fun buildWarpTrafficMaskCatalog(clientData: ClientData): List<String> {
        val whiteHosts = TrafficMaskCatalog.getWhiteHosts(this)
        val russiaHosts = TrafficMaskCatalog.getRussiaHosts(this)
        if (whiteHosts.isEmpty()) return russiaHosts
        val combined = linkedSetOf<String>()
        clientData.getPreferredTrafficMaskHosts(whiteHosts, limit = 8).forEach(combined::add)
        clientData.getPreferredTrafficMaskHosts(russiaHosts, limit = 16).forEach(combined::add)
        return combined.toList()
    }

    private fun resolveWarpTrafficMaskPoolHint(clientData: ClientData): String {
        return if (clientData.getTrafficMaskMode() == "custom") {
            ClientData.TRAFFIC_MASK_POOL_CUSTOM
        } else {
            ClientData.TRAFFIC_MASK_POOL_RUSSIA
        }
    }

    private fun publishWarpTrafficMaskHint(
        clientData: ClientData,
        trafficMaskHosts: List<String>,
        attemptIndex: Int,
        preferredHost: String? = null,
    ) {
        // Сохранённое имя больше не подставляется. Раньше эта ветка стояла ВЫШЕ
        // проверки «маскировка включена», поэтому имя, однажды записанное в
        // профиль, применялось даже при выключенном тумблере — и переживало
        // выключение маскировки, не спрашивая никого. См. [resolveWarpTrafficMaskHosts].
        if (!clientData.getTrafficMaskEnabled() || trafficMaskHosts.isEmpty()) {
            currentWarpMaskHost = null
            clientData.setTrafficMaskActiveHost(null)
            clientData.setWarpTrafficMaskActiveHost(null)
            return
        }
        val host = when (clientData.getTrafficMaskMode()) {
            "custom" -> clientData.getTrafficMaskHost()
            else -> trafficMaskHosts.getOrNull(
                attemptIndex.mod(trafficMaskHosts.size.coerceAtLeast(1))
            ).orEmpty()
        }
        val previousHost = currentWarpMaskHost
        currentWarpMaskHost = host.takeIf { it.isNotBlank() }
        clientData.setTrafficMaskActiveHost(
            host.takeIf { it.isNotBlank() },
            resolveWarpTrafficMaskPoolHint(clientData),
        )
        clientData.setWarpTrafficMaskActiveHost(host.takeIf { it.isNotBlank() })
        if (host.isNotBlank() && !host.equals(previousHost, ignoreCase = true)) {
            LogManager.log("WARP маскировка: текущий fake host = $host")
        }
    }

    private fun normalizeRuntimeTrafficMaskHost(value: String?): String {
        val clean = value?.trim().orEmpty()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
            .trim('.')
            .lowercase(Locale.US)
        if (clean.isBlank()) return ""
        val valid = clean.matches(Regex("^[a-z0-9.-]+$")) &&
            clean.contains('.') &&
            !clean.contains("..") &&
            !clean.startsWith("-") &&
            !clean.endsWith("-")
        return if (valid) clean else ""
    }

    private fun autoReconnectShouldPreferWarpOnly(
        clientData: ClientData,
        regionPreference: String,
    ): Boolean {
        if (clientData.isImportedConfigSourceActive()) return true
        if (normalizeRegionPreference(regionPreference) != "auto") return false
        if (!clientData.hasFreshLastSuccess()) return false
        if (isOperaBackendLabel(clientData.getServiceBackend())) return false
        return true
    }

    private fun attemptSourceBias(
        attempt: ConnectionAttempt,
        lastProtocol: String,
        lastMode: String,
        preferMessengerChatProfiles: Boolean,
        messengerAccelerationProfile: MessengerAccelerationProfile,
    ): Double {
        val family = modeFamily(attempt.mode)
        val source = attempt.endpointSource.lowercase()
        val normalizedMode = attempt.mode.name.lowercase()
        val messengerWifiProfile = messengerAccelerationProfile == MessengerAccelerationProfile.WIFI
        val messengerMobileProfile = messengerAccelerationProfile == MessengerAccelerationProfile.MOBILE
        var bias = 0.0

        bias += when (source) {
            "verified-config" -> 30.0
            "last-success-exact" -> 34.0
            "last-success" -> 10.0
            "awg-fallback" -> when (family) {
                "awg" -> 28.0
                "reserved" -> 4.0
                else -> 0.0
            }
            "api-resolved" -> when (family) {
                "awg" -> 9.0
                "reserved" -> 3.5
                else -> 0.0
            }
            "neighbor-anycast" -> when (family) {
                "awg", "reserved" -> 3.0
                else -> 0.0
            }
            "scan", "fallback-scan" -> 2.0
            "masque-scan" -> when (family) {
                in setOf("masque-zt", "masque-consumer") -> 12.0
                else -> 0.0
            }
            "masque-scan-tcp443" -> when (family) {
                in setOf("masque-zt", "masque-consumer") -> 6.0
                else -> 0.0
            }
            "random" -> when {
                family == "awg" && attempt.port == 988 -> -8.0
                else -> -2.5
            }
            else -> 0.0
        }

        if (attempt.port == 988 && attempt.mode.engine != "masque") {
            bias -= 10.0
        }

        if (attempt.port == 988 && family == "awg") {
            bias -= 3.5
        }

        if (attempt.endpointHost == "162.159.192.1" && attempt.port == 988 && family == "awg") {
            bias -= 6.0
        }

        if (attempt.mode.engine != "masque") {
            bias += when {
                isCoreWarpPort(attempt.port) -> 5.0
                isExoticWarpPort(attempt.port) -> -5.5
                else -> 0.0
            }
            bias += when (family) {
                "awg" -> 5.0
                "reserved" -> 3.5
                else -> 0.0
            }
        }

        if (source == "last-success" && lastProtocol.equals("MASQUE", ignoreCase = true) && attempt.mode.engine != "masque") {
            bias -= 8.0
        }
        if (source == "last-success-exact" && attempt.mode.name.equals(lastMode, ignoreCase = true)) {
            bias += if (preferMessengerChatProfiles && !isChatAwareWarpMode(attempt.mode)) 14.0 else 18.0
            if (preferMessengerChatProfiles && isChatAwareWarpMode(attempt.mode) && attempt.port == 988) {
                bias -= 9.0
            }
        }

        if (preferMessengerChatProfiles && attempt.mode.engine != "masque" && family == "awg") {
            bias -= if (messengerMobileProfile) 1.5 else 1.0
        }
        if (preferMessengerChatProfiles && attempt.mode.engine == "masque") {
            bias += when (attempt.port) {
                500 -> 6.0
                1701 -> 5.0
                4500 -> 4.0
                443 -> 1.0
                else -> 0.0
            }
            bias += when (family) {
                "masque-consumer" -> 2.5
                "masque-zt" -> 1.5
                else -> 0.0
            }
            if (source == "masque-scan-tcp443") {
                bias -= 3.5
            }
        }
        if (!lastRestrictedMobileDetected && attempt.mode.engine != "masque") {
            bias += when {
                normalizedMode.contains("b32") -> -18.0
                normalizedMode.contains("b28") -> -14.0
                else -> 0.0
            }
        }
        if (!preferMessengerChatProfiles && !lastRestrictedMobileDetected && attempt.mode.engine != "masque") {
            bias += when {
                normalizedMode == "warp-awg-max" -> 4.5
                normalizedMode == "warp-awg-lite" -> 3.0
                normalizedMode == "warp-awg-exact" -> 2.0
                normalizedMode == "warp-awg-v2" -> -2.5
                else -> 0.0
            }
        }

        if (isLegacy32BitDevice()) {
            if (attempt.mode.engine == "masque") {
                bias += when (attempt.port) {
                    1701 -> 10.0
                    500 -> 8.0
                    4500 -> 6.0
                    443 -> 4.0
                    8443 -> 2.5
                    4443 -> 2.0
                    8095 -> 1.0
                    else -> 0.0
                }
                if (source == "masque-scan-tcp443") {
                    bias -= 4.0
                }
                bias += when (family) {
                    "masque-consumer" -> 6.0
                    "masque-zt" -> 4.5
                    else -> 2.0
                }
            } else {
                bias += when (attempt.port) {
                    1701 -> 6.0
                    500 -> 4.5
                    4500 -> 3.5
                    2408 -> 5.0
                    988 -> 1.5
                    443 -> 2.0
                    else -> if (isExoticWarpPort(attempt.port)) -6.0 else 0.0
                }
                bias += when (family) {
                    "awg" -> 3.0
                    "reserved" -> 1.5
                    else -> 0.0
                }
            }
        }

        return bias
    }

    private fun resolveAdaptiveCamouflageHost(
        clientData: ClientData,
        seed: String,
        preferMessengerChatProfiles: Boolean,
    ): String {
        return if (preferMessengerChatProfiles) {
            MessengerObfsPolicy.pickCamouflageHost(this, seed)
        } else {
            TrafficCamouflagePolicy.pickCamouflageHost(this, clientData, seed)
        }
    }

    private fun isChatAwareWarpMode(mode: TransportMode): Boolean {
        return false
    }

    private fun modeFamily(mode: TransportMode): String {
        val normalized = mode.name.lowercase()
        if (mode.engine == "masque") {
            return when {
                normalized.contains("consumer") -> "masque-consumer"
                normalized.contains("zt") -> "masque-zt"
                else -> "masque"
            }
        }

        return when {
            normalized.startsWith("warp-awg") || normalized.startsWith("warp-v") -> "awg"
            normalized == "reserved-only" -> "reserved"
            normalized == "plain" -> "plain"
            else -> normalized
        }
    }

    private fun attemptExactKey(attempt: ConnectionAttempt): String {
        return buildString {
            append(attempt.mode.engine.trim().lowercase())
            append(':')
            append(attempt.mode.name.trim().lowercase())
            append(':')
            append(attempt.endpointHost.trim().lowercase())
            append(':')
            append(attempt.port)
            if (attempt.mode.preferImportedRawIdentity) append(":raw-id")
        }
    }

    private fun importedAttemptVariantRank(attempt: ConnectionAttempt): Int {
        return if (attempt.mode.preferImportedRawIdentity) 0 else 1
    }

    private fun attemptLogLabel(attempt: ConnectionAttempt): String {
        val variant = when (importedAttemptVariantRank(attempt)) {
            0 -> "raw-id"
            else -> "nova-id"
        }
        val sni = normalizeRuntimeTrafficMaskHost(attempt.preferredSni)
            .takeIf { it.isNotBlank() }
            ?.let { ",sni=$it" }
            .orEmpty()
        return "${attempt.mode.name}[$variant$sni]@${attempt.endpointHost}:${attempt.port}"
    }

    private fun shouldUseLongAttemptBudget(attempt: ConnectionAttempt): Boolean {
        if (attempt.mode.engine == "masque") return true

        val trustedSource = attempt.endpointSource in setOf(
            "last-success-exact",
            "last-success",
            "verified-config",
            "bundled-seed",
            "awg-fallback",
            "api-resolved",
        )
        val importantPort = attempt.port in setOf(443, 500, 1701, 4500, 988)
        val family = modeFamily(attempt.mode)
        if (family == "awg" && attempt.endpointSource == "awg-fallback") {
            return true
        }

        return trustedSource && importantPort && family == "awg"
    }

    private fun isCoreWarpPort(port: Int): Boolean {
        return port in setOf(500, 1701, 4500, 443, 988, 2408)
    }

    private fun isExoticWarpPort(port: Int): Boolean {
        return port in setOf(854, 880, 1002)
    }

    private fun isTrustedMasqueFastPort(port: Int): Boolean {
        return port in setOf(500, 1701, 4500, 443, 4443, 8443, 8095)
    }

    private fun isClassicWarpMode(mode: TransportMode): Boolean {
        val normalized = mode.name.lowercase()
        return normalized.startsWith("warp-awg") ||
            normalized.startsWith("warp-v") ||
            normalized == "reserved-only"
    }

    private fun getAttemptBudget(
        attempt: ConnectionAttempt,
        longBudgetAttempt: Boolean,
        fastScanMode: Boolean = false,
        fastConnectMode: Boolean = false,
        preferMessengerChatProfiles: Boolean = false,
    ): AttemptBudget {
        if (attempt.mode.engine == "masque") {
            val lastSuccessExact = attempt.endpointSource == "last-success-exact"
            val lastSuccessReuse = attempt.endpointSource == "last-success"
            val verifiedReuse = attempt.endpointSource == "verified-config"
            val trustedCoreVerifiedAttempt =
                isTrustedMasqueFastPort(attempt.port) &&
                    (verifiedReuse || lastSuccessExact || lastSuccessReuse)
            var noHandshakeTimeoutMs = when {
                lastSuccessExact -> 12_000L
                lastSuccessReuse -> 10_000L
                else -> 8_000L
            }
            var handshakeTimeoutMs = when {
                lastSuccessExact -> 15_000L
                lastSuccessReuse -> 12_500L
                else -> 10_500L
            }
            var noInboundAfterHandshakeTimeoutMs = when {
                lastSuccessExact -> 7_000L
                lastSuccessReuse -> 5_500L
                else -> 4_000L
            }
            var maxConnectivityProbeAttempts = if (lastSuccessExact || lastSuccessReuse) 3 else 2
            var minProbeSpacingMs = if (lastSuccessExact) 1_200L else 1_000L

            if (fastConnectMode) {
                noHandshakeTimeoutMs = when {
                    lastSuccessExact -> 4_800L
                    lastSuccessReuse -> 4_300L
                    verifiedReuse -> 3_800L
                    else -> 3_300L
                }
                handshakeTimeoutMs = when {
                    lastSuccessExact -> 5_800L
                    lastSuccessReuse -> 5_200L
                    verifiedReuse -> 4_700L
                    else -> 4_300L
                }
                noInboundAfterHandshakeTimeoutMs = when {
                    lastSuccessExact -> 3_000L
                    lastSuccessReuse -> 2_800L
                    verifiedReuse -> 2_500L
                    else -> 2_200L
                }
                maxConnectivityProbeAttempts = if (lastSuccessExact || lastSuccessReuse) 2 else 1
                minProbeSpacingMs = if (verifiedReuse || lastSuccessExact || lastSuccessReuse) 650L else 750L
            }
            if (fastConnectMode && trustedCoreVerifiedAttempt) {
                noHandshakeTimeoutMs = maxOf(
                    noHandshakeTimeoutMs,
                    when {
                        lastSuccessExact -> 6_800L
                        lastSuccessReuse -> 6_300L
                        else -> 5_800L
                    }
                )
                handshakeTimeoutMs = maxOf(
                    handshakeTimeoutMs,
                    when {
                        lastSuccessExact -> 8_200L
                        lastSuccessReuse -> 7_700L
                        else -> 7_100L
                    }
                )
                noInboundAfterHandshakeTimeoutMs = maxOf(
                    noInboundAfterHandshakeTimeoutMs,
                    when {
                        lastSuccessExact -> 5_200L
                        lastSuccessReuse -> 4_900L
                        else -> 4_600L
                    }
                )
                maxConnectivityProbeAttempts = maxOf(maxConnectivityProbeAttempts, 3)
                minProbeSpacingMs = maxOf(minProbeSpacingMs, 900L)
            }
            if (fastScanMode) {
                noHandshakeTimeoutMs = minOf(noHandshakeTimeoutMs, 2_800L)
                handshakeTimeoutMs = minOf(handshakeTimeoutMs, 5_000L)
                noInboundAfterHandshakeTimeoutMs = minOf(noInboundAfterHandshakeTimeoutMs, 2_400L)
                maxConnectivityProbeAttempts = minOf(maxConnectivityProbeAttempts, 1)
                minProbeSpacingMs = minOf(minProbeSpacingMs, 650L)
            }
            if (resourceConstrainedDevice) {
                maxConnectivityProbeAttempts = when {
                    lastSuccessExact || lastSuccessReuse || verifiedReuse -> minOf(maxConnectivityProbeAttempts, 2)
                    else -> 1
                }
                minProbeSpacingMs = maxOf(minProbeSpacingMs, if (fastConnectMode || fastScanMode) 900L else 1_250L)
            }
            return AttemptBudget(
                noHandshakeTimeoutMs = noHandshakeTimeoutMs,
                handshakeTimeoutMs = handshakeTimeoutMs,
                noInboundAfterHandshakeTimeoutMs = noInboundAfterHandshakeTimeoutMs,
                maxConnectivityProbeAttempts = maxConnectivityProbeAttempts,
                minProbeSpacingMs = minProbeSpacingMs,
            )
        }

        val classic = isClassicWarpMode(attempt.mode)
        val corePort = isCoreWarpPort(attempt.port)
        val exoticPort = isExoticWarpPort(attempt.port)
        val messengerFastCore =
            preferMessengerChatProfiles &&
                attempt.mode.engine != "masque" &&
                !isChatAwareWarpMode(attempt.mode) &&
                corePort

        var noHandshakeTimeoutMs = if (longBudgetAttempt) 6_500L else 4_500L
        var handshakeTimeoutMs = if (longBudgetAttempt) 8_500L else 6_500L
        var noInboundAfterHandshakeTimeoutMs = if (longBudgetAttempt) 5_000L else 3_500L
        var maxConnectivityProbeAttempts = if (longBudgetAttempt) 3 else 2
        var minProbeSpacingMs = 1_000L

        if (classic && corePort) {
            noHandshakeTimeoutMs += 1_500L
            handshakeTimeoutMs += 2_000L
            noInboundAfterHandshakeTimeoutMs += 1_200L
            maxConnectivityProbeAttempts = maxOf(maxConnectivityProbeAttempts, 3)
        }

        if (isLegacy32BitDevice() && classic && corePort) {
            noHandshakeTimeoutMs += 1_000L
            handshakeTimeoutMs += 1_500L
            noInboundAfterHandshakeTimeoutMs += 800L
            maxConnectivityProbeAttempts = maxOf(maxConnectivityProbeAttempts, 4)
            minProbeSpacingMs = maxOf(minProbeSpacingMs, 1_100L)
        }

        if (exoticPort) {
            noHandshakeTimeoutMs = minOf(noHandshakeTimeoutMs, 3_500L)
            handshakeTimeoutMs = minOf(handshakeTimeoutMs, 5_000L)
            noInboundAfterHandshakeTimeoutMs = minOf(noInboundAfterHandshakeTimeoutMs, 2_800L)
            maxConnectivityProbeAttempts = minOf(maxConnectivityProbeAttempts, 2)
            minProbeSpacingMs = 900L
        }

        if (fastConnectMode && exoticPort) {
            noHandshakeTimeoutMs = minOf(noHandshakeTimeoutMs, 1_900L)
            handshakeTimeoutMs = minOf(handshakeTimeoutMs, 2_800L)
            noInboundAfterHandshakeTimeoutMs = minOf(noInboundAfterHandshakeTimeoutMs, 1_500L)
            maxConnectivityProbeAttempts = 1
            minProbeSpacingMs = minOf(minProbeSpacingMs, 600L)
        }

        if (fastConnectMode && messengerFastCore) {
            noHandshakeTimeoutMs = minOf(noHandshakeTimeoutMs, 2_400L)
            handshakeTimeoutMs = minOf(handshakeTimeoutMs, 3_200L)
            noInboundAfterHandshakeTimeoutMs = minOf(noInboundAfterHandshakeTimeoutMs, 1_600L)
            maxConnectivityProbeAttempts = minOf(maxConnectivityProbeAttempts, 2)
            minProbeSpacingMs = minOf(minProbeSpacingMs, 550L)
        }

        if (fastScanMode) {
            noHandshakeTimeoutMs = minOf(noHandshakeTimeoutMs, 2_300L)
            handshakeTimeoutMs = minOf(handshakeTimeoutMs, 3_400L)
            noInboundAfterHandshakeTimeoutMs = minOf(noInboundAfterHandshakeTimeoutMs, 1_800L)
            maxConnectivityProbeAttempts = 1
            minProbeSpacingMs = minOf(minProbeSpacingMs, 700L)
        }

        if (isChatAwareWarpMode(attempt.mode)) {
            noHandshakeTimeoutMs += if (longBudgetAttempt) 900L else 500L
            handshakeTimeoutMs += if (longBudgetAttempt) 1_800L else 1_200L
            noInboundAfterHandshakeTimeoutMs += if (longBudgetAttempt) 1_200L else 700L
            maxConnectivityProbeAttempts = maxOf(maxConnectivityProbeAttempts, if (longBudgetAttempt) 3 else 2)
            minProbeSpacingMs = minOf(minProbeSpacingMs, 650L)
        }
        if (resourceConstrainedDevice) {
            maxConnectivityProbeAttempts = when {
                longBudgetAttempt && classic && corePort -> minOf(maxConnectivityProbeAttempts, 3)
                else -> minOf(maxConnectivityProbeAttempts, 2)
            }
            minProbeSpacingMs = maxOf(minProbeSpacingMs, if (fastConnectMode || fastScanMode) 900L else 1_250L)
        }

        val noHandshakeFloor = when {
            fastScanMode -> 2_000L
            fastConnectMode && messengerFastCore -> 1_600L
            fastConnectMode && exoticPort -> 1_700L
            else -> 3_000L
        }
        val handshakeFloor = when {
            fastScanMode -> 3_000L
            fastConnectMode && messengerFastCore -> 2_300L
            fastConnectMode && exoticPort -> 2_500L
            else -> 4_500L
        }
        val noInboundFloor = when {
            fastScanMode -> 1_600L
            fastConnectMode && messengerFastCore -> 1_300L
            fastConnectMode && exoticPort -> 1_400L
            else -> 2_500L
        }

        return AttemptBudget(
            noHandshakeTimeoutMs = noHandshakeTimeoutMs.coerceAtLeast(noHandshakeFloor),
            handshakeTimeoutMs = handshakeTimeoutMs.coerceAtLeast(handshakeFloor),
            noInboundAfterHandshakeTimeoutMs = noInboundAfterHandshakeTimeoutMs.coerceAtLeast(noInboundFloor),
            maxConnectivityProbeAttempts = maxConnectivityProbeAttempts.coerceAtLeast(1),
            minProbeSpacingMs = minProbeSpacingMs.coerceAtLeast(
                if (fastConnectMode && (exoticPort || messengerFastCore)) 550L else 700L
            ),
        )
    }

    private fun buildTransportModes(reserved: String?, savedProto: String): List<TransportMode> {
        val normalizedReserved = normalizeReservedValue(reserved)
        val reservedMode = if (normalizedReserved.isNullOrBlank()) "off" else "handshake"
        val basePorts = listOf(500, 1701, 4500, 443, 988, 2408)
        val modes = mutableListOf(
            TransportMode(
                "warp-awg-exact",
                useFakePackets = false,
                reservedMode = reservedMode,
                preferredPorts = listOf(988, 500, 1701, 4500, 2408, 443),
                restrictToPreferredPorts = true,
            ),
            TransportMode(
                "warp-awg-v2",
                useFakePackets = false,
                reservedMode = reservedMode,
                preferredPorts = basePorts,
                restrictToPreferredPorts = true,
            ),
            TransportMode(
                "warp-awg",
                useFakePackets = false,
                reservedMode = reservedMode,
                preferredPorts = listOf(988, 500, 1701, 4500, 2408, 443),
                restrictToPreferredPorts = true,
            ),
            TransportMode(
                "warp-awg-lite",
                useFakePackets = false,
                reservedMode = reservedMode,
                preferredPorts = listOf(1701, 500, 988, 4500, 2408, 443),
                restrictToPreferredPorts = true,
            ),
            TransportMode(
                "warp-v1",
                useFakePackets = false,
                reservedMode = reservedMode,
                preferredPorts = basePorts,
                restrictToPreferredPorts = true,
            ),
            TransportMode(
                "warp-v2",
                useFakePackets = false,
                reservedMode = reservedMode,
                preferredPorts = basePorts,
                restrictToPreferredPorts = true,
            ),
            TransportMode(
                "warp-v3",
                useFakePackets = false,
                reservedMode = reservedMode,
                preferredPorts = basePorts,
                restrictToPreferredPorts = true,
            ),
        )
        if (!normalizedReserved.isNullOrBlank()) {
            modes += TransportMode(
                "reserved-only",
                useFakePackets = false,
                reservedMode = "all",
                preferredPorts = basePorts,
                restrictToPreferredPorts = true,
            )
        }

        val preferredName = normalizePreferredProtocol(savedProto, normalizedReserved)
        val preferredIndex = modes.indexOfFirst { it.name.equals(preferredName, ignoreCase = true) }
        if (preferredIndex > 0) {
            val preferred = modes.removeAt(preferredIndex)
            modes.add(0, preferred)
        }
        return modes
    }
    private fun normalizePreferredProtocol(savedProto: String?, reserved: String?): String {
        val normalizedReserved = normalizeReservedValue(reserved)
        val normalized = savedProto?.trim().orEmpty().lowercase()
        if (normalized.isBlank()) {
            return if (normalizedReserved.isNullOrBlank()) "warp-awg" else "reserved-only"
        }

        return when {
            normalized == "masque" || normalized == "warp-plus" -> "warp-awg"
            normalized == "quic-handshake" || normalized.startsWith("quic-") -> "warp-awg"
            normalized.contains("obfs") || normalized.startsWith("warp-r") -> "warp-awg"
            normalized.contains("trick") -> "warp-awg"
            normalized == "plain-wireguard" -> if (normalizedReserved.isNullOrBlank()) "warp-awg-lite" else "reserved-only"
            normalized == "handshake-reserved" -> "reserved-only"
            normalized in setOf(
                "warp-awg-exact",
                "warp-awg-v2",
                "warp-awg",
                "warp-awg-lite",
                "warp-v1",
                "warp-v2",
                "warp-v3",
                "reserved-only",
            ) -> normalized
            else -> if (normalizedReserved.isNullOrBlank()) "warp-awg" else "reserved-only"
        }
    }

    /**
     * Считает, как часто туннель пересобирает рукопожатие на живой сессии.
     *
     * Замер сессии 2026-08-09: 18 рукопожатий за семь минут, каждое проходит с
     * первого раза за 40–60 мс, ошибок приёма нет — и семнадцать раз ядро пишет
     * «stopped hearing back after 15 seconds». Таймер перерукопожатия взводится
     * только отправкой **данных**, keepalive его не трогает (см.
     * `timersDataSent` в amneziawg-go), и снимается любым принятым пакетом.
     * Значит это не тишина простоя: мы отправляли трафик и пятнадцать секунд не
     * получали ничего в ответ. Ровно в эти окна и проваливается пинг.
     *
     * Экран этого не видел: ни один слой Nova не смотрел на частоту рукопожатий
     * во время работы. `last_handshake_time_sec` уже отдаёт ядро — считаем по
     * нему, без правок в самом amneziawg-go.
     */
    private fun sampleTunnelRekeyChurn() {
        if (currentState != STATE_CONNECTED || !novaCoreTunnelActive) {
            resetTunnelRekeyChurn()
            return
        }
        val stats = readTunnelStats()
        val handshakeSec = stats.lastHandshakeTimeSec
        if (handshakeSec <= 0L) return
        val nowMs = SystemClock.elapsedRealtime()
        if (tunnelRekeyWindowStartedAtMs == 0L) {
            tunnelRekeyWindowStartedAtMs = nowMs
            lastSeenHandshakeTimeSec = handshakeSec
            tunnelRekeyWindowRxBytes = stats.rxBytes
            tunnelRekeyWindowTxBytes = stats.txBytes
            return
        }
        if (handshakeSec != lastSeenHandshakeTimeSec) {
            lastSeenHandshakeTimeSec = handshakeSec
            tunnelRekeyCount += 1
        }
        val windowMs = nowMs - tunnelRekeyWindowStartedAtMs
        if (windowMs < TUNNEL_REKEY_WINDOW_MS) return
        val perMinute = tunnelRekeyCount * 60_000.0 / windowMs
        val rxKb = (stats.rxBytes - tunnelRekeyWindowRxBytes).coerceAtLeast(0L) / 1024
        val txKb = (stats.txBytes - tunnelRekeyWindowTxBytes).coerceAtLeast(0L) / 1024
        // Трафик за окно печатаем рядом с числом перерукопожатий, и это не украшение.
        // Таймер перерукопожатия взводит только отправка данных: на молчащем туннеле
        // ноль получается сам собой, без всякого здоровья. Без этой пары чисел
        // сравнение двух прогонов ничего не значит — на этом уже один раз обманулись.
        val traffic = "трафик ${txKb}/${rxKb} КБ tx/rx"
        when {
            txKb < TUNNEL_REKEY_MIN_TX_KB -> LogManager.log(
                "Туннель молчал: $traffic за ${windowMs / 1000} с, перерукопожатий " +
                    "${tunnelRekeyCount}. Окно не показательно — таймер перерукопожатия " +
                    "взводит только отправка данных."
            )
            tunnelRekeyCount >= TUNNEL_REKEY_ALERT_COUNT -> LogManager.log(
                "Туннель нестабилен: ${tunnelRekeyCount} перерукопожатий за " +
                    "${windowMs / 1000} с (${"%.1f".format(perMinute)}/мин), $traffic, " +
                    "backend=$currentBackendLabel. Рукопожатие проходит, обратный поток " +
                    "пропадает — провалы пинга ложатся в эти окна."
            )
            else -> LogManager.log(
                "Туннель стабилен: ${tunnelRekeyCount} перерукопожатий за ${windowMs / 1000} с, " +
                    "$traffic, backend=$currentBackendLabel."
            )
        }
        // Показательное окно уходит в оценку узла. Молчащее — нет: там ноль
        // получается сам собой и завысил бы узел, который ничего не вёз.
        if (txKb >= TUNNEL_REKEY_MIN_TX_KB) {
            recordTunnelRekeyChurnForEndpoint(tunnelRekeyCount)
        }
        tunnelRekeyWindowStartedAtMs = nowMs
        tunnelRekeyCount = 0
        tunnelRekeyWindowRxBytes = stats.rxBytes
        tunnelRekeyWindowTxBytes = stats.txBytes
    }

    private fun resetTunnelRekeyChurn() {
        tunnelRekeyWindowStartedAtMs = 0L
        lastSeenHandshakeTimeSec = 0L
        tunnelRekeyCount = 0
        tunnelRekeyWindowRxBytes = 0L
        tunnelRekeyWindowTxBytes = 0L
    }

    private fun readTunnelStats(): TunnelStats {
        return try {
            val rawStats = Nova.getVPNStats().orEmpty()
            var lastHandshakeTimeSec = 0L
            var rxBytes = 0L
            var txBytes = 0L

            rawStats.lineSequence().forEach { line ->
                when {
                    line.startsWith("last_handshake_time_sec=") -> {
                        lastHandshakeTimeSec = line.substringAfter('=').toLongOrNull() ?: 0L
                    }
                    line.startsWith("rx_bytes=") -> {
                        rxBytes = line.substringAfter('=').toLongOrNull() ?: 0L
                    }
                    line.startsWith("tx_bytes=") -> {
                        txBytes = line.substringAfter('=').toLongOrNull() ?: 0L
                    }
                }
            }

            TunnelStats(
                lastHandshakeTimeSec = lastHandshakeTimeSec,
                rxBytes = rxBytes,
                txBytes = txBytes
            )
        } catch (_: Exception) {
            TunnelStats(
                lastHandshakeTimeSec = 0L,
                rxBytes = 0L,
                txBytes = 0L
            )
        }
    }

    private fun relativeTunnelStats(currentStats: TunnelStats, baselineStats: TunnelStats): TunnelStats {
        return TunnelStats(
            lastHandshakeTimeSec = currentStats.lastHandshakeTimeSec,
            rxBytes = (currentStats.rxBytes - baselineStats.rxBytes).coerceAtLeast(0L),
            txBytes = (currentStats.txBytes - baselineStats.txBytes).coerceAtLeast(0L),
        )
    }

    private fun hasFreshHandshake(
        currentStats: TunnelStats,
        baselineStats: TunnelStats,
        attemptStartedWallTimeSec: Long,
        maxAgeSeconds: Long = 180,
    ): Boolean {
        if (currentStats.lastHandshakeTimeSec <= 0L) return false
        val nowSec = System.currentTimeMillis() / 1000L
        val ageSec = nowSec - currentStats.lastHandshakeTimeSec
        if (ageSec !in 0..maxAgeSeconds) return false
        val newerThanBaseline = currentStats.lastHandshakeTimeSec > baselineStats.lastHandshakeTimeSec
        val belongsToCurrentAttempt = currentStats.lastHandshakeTimeSec >= (attemptStartedWallTimeSec - 1L)
        return newerThanBaseline || belongsToCurrentAttempt
    }

    private fun hasInboundTraffic(stats: TunnelStats): Boolean {
        return stats.rxBytes > 0L && stats.txBytes > 0L
    }

    private fun isMasqueRemoteAuthError(message: String): Boolean {
        val normalized = message.lowercase()
        return "access denied" in normalized ||
            "unauthorized" in normalized ||
            "forbidden" in normalized ||
            "identity rejected" in normalized ||
            "invalid identity" in normalized
    }

    private fun applyUnderlyingNetworkHint(
        builder: Builder,
        underlyingNetwork: android.net.Network?,
        backendLabel: String,
    ) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            LogManager.log("Подсказку подложной сети пропускаем: Android < 10.")
            return
        }
        val isGoogleDevice =
            android.os.Build.MANUFACTURER.equals("Google", ignoreCase = true)
        val forceExplicitWarpHintOnGoogle =
            isGoogleDevice && backendLabel.equals(BACKEND_WARP, ignoreCase = true)
        if (isGoogleDevice && !forceExplicitWarpHintOnGoogle) {
            LogManager.log(
                "Подсказку подложной сети не задаём: совместимость с Pixel/Google, " +
                    "оставляем выбор системе."
            )
            AndroidCompat.setUnderlyingNetworks(builder, null)
            return
        }
        if (underlyingNetwork != null) {
            if (forceExplicitWarpHintOnGoogle) {
                LogManager.log(
                    "Для WARP на Pixel/Google явно привязываем VPN к подложной сети: " +
                        underlyingNetwork.toString()
                )
            }
            AndroidCompat.setUnderlyingNetworks(builder, arrayOf(underlyingNetwork))
        } else {
            AndroidCompat.setUnderlyingNetworks(builder, null)
        }
    }

    private fun selectUnderlyingNetwork(
        connectivityManager: android.net.ConnectivityManager?,
    ): android.net.Network? {
        if (connectivityManager == null) return null

        val active = connectivityManager.activeNetwork
        if (
            active != null &&
            isUsableUnderlyingNetwork(connectivityManager, active) &&
            connectivityManager.getNetworkCapabilities(active)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        ) {
            return active
        }

        fun score(network: android.net.Network): Int {
            if (!isUsableUnderlyingNetwork(connectivityManager, network)) return Int.MIN_VALUE
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return Int.MIN_VALUE
            val isWifi = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            val isCellular = caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            val validated = caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val isActive = network == active
            var score = 0
            if (isWifi) score += 300
            if (validated) score += 140
            if (isActive) score += if (isWifi) 80 else 20
            if (isCellular && !isWifi) score += 30
            return score
        }

        return connectivityManager.allNetworks
            .filter { isUsableUnderlyingNetwork(connectivityManager, it) }
            .maxByOrNull { score(it) }
    }

    private fun isMeteredUnderlyingNetwork(
        connectivityManager: android.net.ConnectivityManager?,
        network: android.net.Network?,
    ): Boolean {
        if (connectivityManager == null) return false
        val selectedCaps = network?.let { connectivityManager.getNetworkCapabilities(it) }
        if (selectedCaps != null) {
            return !selectedCaps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        }
        return connectivityManager.isActiveNetworkMetered
    }

    private fun findUnderlyingNetworkById(
        connectivityManager: android.net.ConnectivityManager?,
        networkId: String,
    ): android.net.Network? {
        if (connectivityManager == null || networkId.isBlank()) return null
        return connectivityManager.allNetworks.firstOrNull { it.toString() == networkId }
    }

    private fun isUsableUnderlyingNetwork(
        connectivityManager: android.net.ConnectivityManager?,
        network: android.net.Network?,
    ): Boolean {
        if (connectivityManager == null || network == null) return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return false
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        return true
    }

    private fun shouldPauseConnectForMissingUnderlying(
        clientData: ClientData,
        reason: String,
    ): Boolean {
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val selectedUnderlying = selectUnderlyingNetwork(connectivityManager)
        if (isUsableUnderlyingNetwork(connectivityManager, selectedUnderlying)) return false
        clientData.markTransientConnectingPending(12_000L)
        currentWarpMaskHost = null
        broadcastState(STATE_CONNECTING)
        scheduleNetworkRecoveryCheck("underlying-loss")
        LogManager.log(
            "Подложная сеть отсутствует во время $reason. " +
                "Приостанавливаем перебор до возврата Wi‑Fi или мобильного сигнала."
        )
        return true
    }

    private fun buildUnderlyingNetworkSignature(
        connectivityManager: android.net.ConnectivityManager?,
        network: android.net.Network?,
    ): String? {
        if (connectivityManager == null || network == null) return null
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return null
        val linkProperties = connectivityManager.getLinkProperties(network)
        val (ssid, bssid) = extractWifiSsidAndBssid(caps)
        return NetworkIdentity.signature(
            transportLabel = buildUnderlyingNetworkClass(caps).orEmpty(),
            interfaceName = linkProperties?.interfaceName.orEmpty(),
            ssid = ssid,
            bssid = bssid,
            defaultGateway = linkProperties?.routes
                ?.firstOrNull { it.isDefaultRoute }
                ?.gateway
                ?.hostAddress
                .orEmpty(),
        )
    }

    private fun buildUnderlyingNetworkClass(
        connectivityManager: android.net.ConnectivityManager?,
        network: android.net.Network?,
    ): String? {
        if (connectivityManager == null || network == null) return null
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return null
        return buildUnderlyingNetworkClass(caps)
    }

    private fun buildUnderlyingNetworkClass(
        caps: android.net.NetworkCapabilities?,
    ): String? {
        if (caps == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return null
        return buildList {
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) add("cell")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) add("eth")
        }.joinToString("+")
            .ifBlank { "other" }
    }

    private fun clearCurrentCycleReconnectHints() {
        currentCycleReuseLastSuccess = false
        currentCycleStableSuccess = null
    }

    private fun degradedWarpReuseKey(
        engine: String,
        mode: String,
        host: String,
        port: Int,
    ): String {
        val normalizedEngine = engine.trim().lowercase(Locale.US)
        val normalizedMode = mode.trim().lowercase(Locale.US)
        val normalizedHost = host.trim().removePrefix("[").removeSuffix("]").lowercase(Locale.US)
        return listOf(
            normalizedEngine,
            normalizedMode,
            normalizedHost,
            port.coerceAtLeast(0).toString(),
        ).joinToString("|")
    }

    private fun rememberTransientDegradedWarpProfile(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        cooldownMs: Long,
    ) {
        if (port !in 1..65535) return
        transientDegradedWarpReuseUntilMs[degradedWarpReuseKey(engine, mode, host, port)] =
            System.currentTimeMillis() + cooldownMs.coerceAtLeast(15_000L)
    }

    private fun isTransientlyDegradedWarpProfile(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        if (port !in 1..65535) return false
        val key = degradedWarpReuseKey(engine, mode, host, port)
        val untilMs = transientDegradedWarpReuseUntilMs[key] ?: return false
        if (untilMs <= nowMs) {
            transientDegradedWarpReuseUntilMs.remove(key, untilMs)
            return false
        }
        return true
    }

    private fun currentStrategyNetworkClass(): String? {
        // WARP ranking is intentionally universal now: Wi-Fi/mobile split made
        // working profiles look "lost" after network changes.
        return null
    }

    private fun resolveMessengerAccelerationProfile(
        clientData: ClientData,
    ): MessengerAccelerationProfile {
        if (!clientData.shouldForceMessengerWarpPriority()) {
            return MessengerAccelerationProfile.OFF
        }
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
        val selectedUnderlying = selectUnderlyingNetwork(connectivityManager)
            ?: connectivityManager?.activeNetwork
        val networkClass = buildUnderlyingNetworkClass(connectivityManager, selectedUnderlying)
            .orEmpty()
            .lowercase(Locale.US)
        return when {
            "cell" in networkClass && "wifi" !in networkClass -> MessengerAccelerationProfile.MOBILE
            else -> MessengerAccelerationProfile.WIFI
        }
    }

    private fun resolveTelegramTransparentProfile(
        clientData: ClientData,
        connectivityManager: android.net.ConnectivityManager?,
        selectedUnderlyingNetwork: android.net.Network?,
    ): String {
        if (!clientData.shouldPreferMessengerWarpProfiles()) {
            return "off"
        }
        val networkClass = buildUnderlyingNetworkClass(connectivityManager, selectedUnderlyingNetwork)
            .orEmpty()
            .lowercase(Locale.US)
        return when {
            "cell" in networkClass && "wifi" !in networkClass -> "mobile"
            else -> "wifi"
        }
    }

    private fun messengerAccelerationLaneCount(
        profile: MessengerAccelerationProfile,
    ): Int {
        return when (profile) {
            MessengerAccelerationProfile.MOBILE -> 8
            MessengerAccelerationProfile.WIFI -> 4
            MessengerAccelerationProfile.OFF -> 0
        }
    }

    private fun stableSuccessNetworkClassFromSignature(signature: String?): String? {
        val transportLabel = signature
            ?.substringBefore('|')
            ?.trim()
            ?.lowercase()
            .orEmpty()
        return when {
            transportLabel.contains("wifi") -> "wifi"
            transportLabel.contains("cell") -> "cell"
            transportLabel.contains("eth") -> "eth"
            transportLabel.isNotBlank() -> "other"
            else -> null
        }
    }

    private fun resolveStableSuccessSnapshot(
        clientData: ClientData,
        @Suppress("UNUSED_PARAMETER") selectedUnderlyingSignature: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): StableSuccessSnapshot? {
        pendingUnderlayUpgradeWarpHint?.let { hint ->
            val ageMs = SystemClock.elapsedRealtime() - pendingUnderlayUpgradeWarpHintAtMs
            pendingUnderlayUpgradeWarpHintAtMs = 0L
            pendingUnderlayUpgradeWarpHint = null
            if (ageMs in 0..15_000L) {
                return hint
            }
        }
        if (!clientData.hasFreshStableLastSuccess(nowMs)) return null
        val host = clientData.getStableLastSuccessEndpoint()
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            .orEmpty()
        val port = clientData.getStableLastSuccessPort()
        if (host.isBlank() || port !in 1..65535) return null
        // WARP ranking/reuse is universal now: a stable profile must survive Wi-Fi/mobile
        // changes instead of being hidden behind per-network buckets.
        val protocol = clientData.getStableLastSuccessProtocol().orEmpty()
            .ifBlank { clientData.getLastSuccessProtocol() }
        val mode = clientData.getStableLastSuccessMode().orEmpty()
            .ifBlank { protocol }
        return StableSuccessSnapshot(
            host = host,
            port = port,
            protocol = protocol,
            mode = mode,
            networkSignature = "",
        )
    }

    private fun prepareCurrentCycleReconnectHints(
        clientData: ClientData,
        selectedUnderlyingSignature: String?,
    ) {
        val stableSnapshot = resolveStableSuccessSnapshot(clientData, selectedUnderlyingSignature)
        currentCycleStableSuccess = stableSnapshot
        currentCycleReuseLastSuccess = when {
            stableSnapshot != null -> true
            clientData.hasFreshStableLastSuccess() -> false
            else -> clientData.hasFreshLastSuccess()
        }
    }

    private fun currentCycleHasReusableLastSuccess(clientData: ClientData): Boolean {
        if (currentCycleStableSuccess == null && clientData.hasFreshStableLastSuccess()) {
            return false
        }
        val reusable = currentCycleStableSuccess != null ||
            (currentCycleReuseLastSuccess && clientData.hasFreshLastSuccess())
        if (!reusable) return false
        val lastHost = currentCycleLastSuccessEndpoint(clientData)
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            .orEmpty()
        val lastMode = currentCycleLastSuccessMode(clientData).trim()
        val lastPort = currentCycleLastSuccessPort(clientData)
        if (lastHost.isBlank() || lastMode.isBlank() || lastPort == null || lastPort !in 1..65535) return true
        val matchingVerified = clientData.getWarpVerifiedConfigs().firstOrNull { config ->
            config.mode.equals(lastMode, ignoreCase = true) &&
                config.host.trim().removePrefix("[").removeSuffix("]").equals(lastHost, ignoreCase = true) &&
                config.port == lastPort
        } ?: return true
        return clientData.getWarpVerifiedQualityTier(matchingVerified) >= 2
    }

    private fun currentCycleLastSuccessEndpoint(clientData: ClientData): String? {
        return currentCycleStableSuccess?.host
            ?: clientData.getLastSuccessEndpoint().takeIf { currentCycleReuseLastSuccess }
    }

    private fun currentCycleLastSuccessPort(clientData: ClientData): Int? {
        val stablePort = currentCycleStableSuccess?.port
        if (stablePort != null && stablePort in 1..65535) return stablePort
        return clientData.getLastSuccessPort().takeIf {
            currentCycleReuseLastSuccess && it in 1..65535
        }
    }

    private fun currentCycleLastSuccessProtocol(clientData: ClientData): String {
        return currentCycleStableSuccess?.protocol
            ?: clientData.getLastSuccessProtocol().takeIf { currentCycleReuseLastSuccess }
            ?: ""
    }

    private fun currentCycleLastSuccessMode(clientData: ClientData): String {
        return currentCycleStableSuccess?.mode
            ?: clientData.getLastSuccessMode().takeIf { currentCycleReuseLastSuccess }
            ?: ""
    }

    /** Имя и MAC точки доступа. Как их использовать, решает [NetworkIdentity]. */
    private fun extractWifiSsidAndBssid(caps: android.net.NetworkCapabilities?): Pair<String, String> {
        if (caps == null || !caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
            return "" to ""
        }
        val transportInfo = NetworkCapabilitiesCompat.getTransportInfo(caps) ?: return "" to ""
        return try {
            val getSsid = transportInfo.javaClass.methods
                .firstOrNull { it.name == "getSSID" && it.parameterCount == 0 }
            val getBssid = transportInfo.javaClass.methods
                .firstOrNull { it.name == "getBSSID" && it.parameterCount == 0 }
            NetworkIdentity.sanitizeWifiValue(getSsid?.invoke(transportInfo) as? String) to
                NetworkIdentity.sanitizeWifiValue(getBssid?.invoke(transportInfo) as? String)
        } catch (_: Throwable) {
            val raw = transportInfo.toString()
            val ssid = Regex("""SSID[:=]\s*"?([^",]+)""").find(raw)?.groupValues?.getOrNull(1)
            val bssid = Regex("""BSSID[:=]\s*([0-9a-fA-F:]{17})""").find(raw)?.groupValues?.getOrNull(1)
            NetworkIdentity.sanitizeWifiValue(ssid) to NetworkIdentity.sanitizeWifiValue(bssid)
        }
    }

    /** Человекочитаемое имя Wi-Fi для логов: там BSSID полезен, в подписи — нет. */
    private fun extractWifiIdentity(caps: android.net.NetworkCapabilities?): String {
        val (ssid, bssid) = extractWifiSsidAndBssid(caps)
        return listOf(ssid, bssid).filter { it.isNotBlank() }.joinToString("@")
    }

    private fun describeNetwork(
        connectivityManager: android.net.ConnectivityManager?,
        network: android.net.Network?,
    ): String? {
        if (connectivityManager == null || network == null) return null
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return network.toString()
        val transports = buildList {
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
        }
        val transportLabel = if (transports.isEmpty()) "unknown" else transports.joinToString("+")
        return "$network [$transportLabel]"
    }

    private fun networkId(network: android.net.Network): Int {
        return network.toString().toIntOrNull() ?: -1
    }

    private fun findLatestLikelyNovaVpnNetwork(
        connectivityManager: android.net.ConnectivityManager?,
    ): android.net.Network? {
        if (connectivityManager == null) return null
        return connectivityManager.allNetworks
            .filter { network ->
                val caps = connectivityManager.getNetworkCapabilities(network) ?: return@filter false
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                    isLikelyNovaVpnNetwork(connectivityManager, network)
            }
            .maxByOrNull { networkId(it) }
    }

    private fun findCurrentVpnNetwork(
        connectivityManager: android.net.ConnectivityManager?,
    ): android.net.Network? {
        if (connectivityManager == null) return null
        val active = connectivityManager.activeNetwork
        if (
            active != null &&
            connectivityManager.getNetworkCapabilities(active)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true &&
            isLikelyNovaVpnNetwork(connectivityManager, active)
        ) {
            return active
        }

        fun score(network: android.net.Network): Int {
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return Int.MIN_VALUE
            if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return Int.MIN_VALUE
            var score = 0
            if (isLikelyNovaVpnNetwork(connectivityManager, network)) score += 1_000
            if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 200
            if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) score += 50
            if (getVpnUnderlyingNetworks(connectivityManager, network).isNotEmpty()) score += 100
            return score
        }

        val bestVpn = connectivityManager.allNetworks
            .filter { network ->
                connectivityManager.getNetworkCapabilities(network)
                    ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
            }
            .maxWithOrNull(
                compareBy<android.net.Network> { score(it) }
                    .thenBy { networkId(it) }
            )
        if (bestVpn != null && isLikelyNovaVpnNetwork(connectivityManager, bestVpn)) {
            return bestVpn
        }
        return if (bestVpn != null && hasStrongLocalNovaSessionEvidence()) bestVpn else null
    }

    private fun isLikelyNovaVpnNetwork(
        connectivityManager: android.net.ConnectivityManager?,
        network: android.net.Network?,
    ): Boolean {
        if (connectivityManager == null || network == null) return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return false
        if (isNovaVpnOwner(caps)) return true
        val transportInfo = extractVpnTransportLabel(caps)
        return transportInfo.contains("NovaVPN", ignoreCase = true) ||
            transportInfo.contains("NovaOperaVPN", ignoreCase = true)
    }

    private fun hasActiveForeignVpnNetwork(
        connectivityManager: android.net.ConnectivityManager?,
    ): Boolean {
        if (connectivityManager == null) return false
        return connectivityManager.allNetworks.any { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@any false
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
                !isLikelyNovaVpnNetwork(connectivityManager, network)
        }
    }

    private fun extractVpnTransportLabel(caps: android.net.NetworkCapabilities?): String {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return ""
        val transportInfo = NetworkCapabilitiesCompat.getTransportInfo(caps) ?: return ""
        return try {
            val sessionId = transportInfo.javaClass.methods
                .firstOrNull { it.name == "getSessionId" && it.parameterCount == 0 }
                ?.invoke(transportInfo) as? String
            sessionId?.takeIf { it.isNotBlank() } ?: transportInfo.toString().orEmpty()
        } catch (_: Throwable) {
            transportInfo.toString().orEmpty()
        }
    }

    private fun hasStrongLocalNovaSessionEvidence(): Boolean {
        val clientData = ClientData(this)
        if (
            currentState == STATE_CONNECTED ||
            currentState == STATE_CONNECTING ||
            clientData.isSoftReapplyPending() ||
            clientData.isTransientConnectingPending()
        ) {
            return true
        }
        if (isRunning) {
            return true
        }
        val persistedState = clientData.getServiceState()
        val updatedAt = clientData.getServiceStateUpdatedAt()
        val ageMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
        return clientData.getRestartSession() != null &&
            persistedState == STATE_CONNECTING &&
            ageMs in 0..12_000L
    }

    private fun isNovaVpnOwner(caps: android.net.NetworkCapabilities?): Boolean {
        return extractVpnOwnerUid(caps) == applicationInfo.uid
    }

    private fun extractVpnOwnerUid(caps: android.net.NetworkCapabilities?): Int? {
        if (caps == null) return null
        val reflectedOwnerUid = try {
            val ownerUid = caps.javaClass.methods
                .firstOrNull { it.name == "getOwnerUid" && it.parameterCount == 0 }
                ?.invoke(caps) as? Int
            ownerUid?.takeIf { it >= 0 }
        } catch (_: Throwable) {
            null
        }
        if (reflectedOwnerUid != null) return reflectedOwnerUid

        return Regex("(?:OwnerUid|EstablishingAppUid):\\s*(\\d+)")
            .find(caps.toString())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
    }

    private fun currentHealthReconnectGraceMs(): Long {
        return when {
            hasRecentTransportFailureSignal() -> 0L
            requiresFreshTunnelProbeNow() && isOperaBackendLabel(currentBackendLabel) -> 8_000L
            requiresFreshTunnelProbeNow() -> 6_000L
            isOperaBackendLabel(currentBackendLabel) -> if (isDeviceInteractiveNow()) 18_000L else 12_000L
            else -> if (isDeviceInteractiveNow()) 12_000L else 9_000L
        }
    }

    private fun nextVpnConsistencyIntervalMs(): Long {
        val interactive = try {
            getSystemService(PowerManager::class.java)?.isInteractive ?: true
        } catch (_: Exception) {
            true
        }
        return when (currentState) {
            STATE_CONNECTED -> if (interactive) 4_500L else 4_000L
            STATE_CONNECTING -> if (interactive) 3_200L else 3_800L
            else -> if (interactive) 9_000L else 12_000L
        }
    }

    private fun healthReconnectFailureThreshold(reason: String): Int {
        val normalizedReason = reason.trim()
        return when {
            !isDeviceInteractiveNow() &&
                !hasRecentSuccessfulTunnelProbe(windowMs = 5_500L) &&
                (
                    hasRecentTransportFailureSignal() ||
                        normalizedReason == "VPN не валидирован" ||
                        normalizedReason == "VPN потерял underlying networks" ||
                        normalizedReason == "tunnel-probe не проходит"
                    ) -> 1
            requiresFreshTunnelProbeNow() &&
                (normalizedReason == "подложная сеть отсутствует" ||
                    normalizedReason == "VPN-интерфейс отсутствует" ||
                    normalizedReason == "VPN потерял underlying networks") -> 1
            requiresFreshTunnelProbeNow() && isOperaBackendLabel(currentBackendLabel) -> 2
            requiresFreshTunnelProbeNow() -> 1
            normalizedReason == "VPN потерял underlying networks" && isOperaBackendLabel(currentBackendLabel) -> 3
            normalizedReason == "VPN потерял underlying networks" -> 2
            else -> 2
        }
    }

    private fun isValidatedVpnNetwork(
        connectivityManager: android.net.ConnectivityManager?,
        network: android.net.Network?,
    ): Boolean {
        if (connectivityManager == null || network == null) return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private data class VpnHealthSnapshot(
        val isHealthy: Boolean,
        val currentVpn: android.net.Network?,
        val selectedUnderlying: android.net.Network?,
        val reason: String,
    )

    private fun inspectVpnHealth(
        connectivityManager: android.net.ConnectivityManager?,
        currentVpn: android.net.Network?,
    ): VpnHealthSnapshot {
        if (connectivityManager == null) {
            return VpnHealthSnapshot(
                isHealthy = false,
                currentVpn = currentVpn,
                selectedUnderlying = null,
                reason = "connectivity manager недоступен"
            )
        }
        if (currentVpn == null) {
            return VpnHealthSnapshot(
                isHealthy = false,
                currentVpn = null,
                selectedUnderlying = null,
                reason = "VPN-интерфейс отсутствует"
            )
        }

        val selectedUnderlying = selectUnderlyingNetwork(connectivityManager)
        if (selectedUnderlying == null) {
            return VpnHealthSnapshot(
                isHealthy = false,
                currentVpn = currentVpn,
                selectedUnderlying = null,
                reason = "подложная сеть отсутствует"
            )
        }

        val vpnUnderlyingNetworks = getVpnUnderlyingNetworks(connectivityManager, currentVpn)
        if (vpnUnderlyingNetworks.isEmpty()) {
            return VpnHealthSnapshot(
                isHealthy = false,
                currentVpn = currentVpn,
                selectedUnderlying = selectedUnderlying,
                reason = "VPN потерял underlying networks"
            )
        }

        val vpnValidated = isValidatedVpnNetwork(connectivityManager, currentVpn)
        if (!vpnValidated) {
            return VpnHealthSnapshot(
                isHealthy = false,
                currentVpn = currentVpn,
                selectedUnderlying = selectedUnderlying,
                reason = "VPN не валидирован"
            )
        }

        return VpnHealthSnapshot(
            isHealthy = true,
            currentVpn = currentVpn,
            selectedUnderlying = selectedUnderlying,
            reason = "OK"
        )
    }

    private fun adoptHealthyExistingVpnIfPresent(expectedBackendHint: String? = null): Boolean {
        val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java) ?: return false
        val currentVpn = findCurrentVpnNetwork(connectivityManager) ?: return false
        if (!isLikelyNovaVpnNetwork(connectivityManager, currentVpn)) return false

        val persistedBackend = ClientData(this).getServiceBackend().ifBlank { currentBackendLabel }
        val expectedBackend = expectedBackendHint?.trim()?.uppercase().orEmpty()
        val actualBackend = persistedBackend.trim().uppercase()
        val backendMatches = when {
            expectedBackend.isBlank() -> true
            expectedBackend.contains('-') -> actualBackend == expectedBackend
            else -> actualBackend.startsWith(expectedBackend)
        }
        if (!backendMatches) return false

        val health = inspectVpnHealth(connectivityManager, currentVpn)
        val probeTimeout = if (isOperaBackendLabel(persistedBackend)) 1400 else 900
        if (!health.isHealthy || !hasTunnelConnectivity(currentVpn, probeTimeout, allowHttpDnsFallback = true)) {
            return false
        }

        markSuccessfulTunnelProbe()
        observedUnderlyingNetworkId = health.selectedUnderlying?.toString()
        observedUnderlyingNetworkSignature = buildUnderlyingNetworkSignature(connectivityManager, health.selectedUnderlying)
        observedUnderlyingUnavailable = health.selectedUnderlying == null
        currentAttemptOrdinal = 0
        currentAttemptTotal = 0
        isRunning = true
        suppressSessionRestore = false
        setCurrentBackend(persistedBackend.ifBlank { BACKEND_WARP })
        LogManager.log("Обнаружен уже активный рабочий VPN Nova. Повторный connect не требуется.")
        broadcastState(STATE_CONNECTED)
        return true
    }

    private fun getVpnUnderlyingNetworks(
        connectivityManager: android.net.ConnectivityManager?,
        network: android.net.Network?,
    ): List<android.net.Network> {
        if (connectivityManager == null || network == null) return emptyList()
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return emptyList()

        fun normalize(result: Any?): List<android.net.Network> {
            return when (result) {
                is Array<*> -> result.filterIsInstance<android.net.Network>()
                is Collection<*> -> result.filterIsInstance<android.net.Network>()
                else -> emptyList()
            }
        }

        return try {
            val method = caps.javaClass.methods.firstOrNull { method ->
                method.name == "getUnderlyingNetworks" && method.parameterCount == 0
            }
            normalize(method?.invoke(caps))
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun applySplitTunnelPolicy(builder: Builder, clientData: ClientData) {
        val splitMode = clientData.getSplitMode()
        val selectedApps = clientData.getSplitApps()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != packageName }
            .distinct()

        when (splitMode) {
            1 -> {
                val allowedPackages = (selectedApps + packageName).distinct()
                var applied = 0
                for (pkg in allowedPackages) {
                    try {
                        builder.addAllowedApplication(pkg)
                        applied++
                    } catch (_: Exception) {
                    }
                }
                LogManager.log(
                    "Split tunneling: только выбранные приложения " +
                        "(${selectedApps.size}), служебный пакет Nova оставлен внутри VPN."
                )
            }

            2 -> {
                val disallowedPackages = selectedApps
                for (pkg in disallowedPackages) {
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (_: Exception) {
                    }
                }
                LogManager.log(
                    "Split tunneling: VPN для всех, кроме выбранных приложений " +
                        "(${selectedApps.size}), служебный пакет Nova оставлен внутри VPN."
                )
            }

            else -> {
                LogManager.log("Split tunneling: все приложения, включая Nova, идут через VPN.")
            }
        }
    }

    private fun applyOperaSplitTunnelPolicy(builder: Builder, clientData: ClientData) {
        val splitMode = clientData.getSplitMode()
        val selectedApps = clientData.getSplitApps()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != packageName }
            .distinct()

        when (splitMode) {
            1 -> {
                var applied = 0
                for (pkg in selectedApps) {
                    try {
                        builder.addAllowedApplication(pkg)
                        applied++
                    } catch (error: Exception) {
                        LogManager.log("Opera split tunneling: не удалось разрешить пакет $pkg: ${error.message}")
                    }
                }
                LogManager.log(
                    "Opera split tunneling: только выбранные приложения " +
                        "(${selectedApps.size}), пакет Nova остаётся вне VPN, так как allow-list не включает его."
                )
                if (applied == 0) {
                    LogManager.log("Opera split tunneling: список allow пуст, трафик Nova и остальные приложения остаются вне VPN.")
                }
            }

            2 -> {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (error: Exception) {
                    LogManager.log("Opera split tunneling: не удалось исключить пакет Nova из VPN: ${error.message}")
                }
                for (pkg in selectedApps) {
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (error: Exception) {
                        LogManager.log("Opera split tunneling: не удалось исключить пакет $pkg из VPN: ${error.message}")
                    }
                }
                LogManager.log(
                    "Opera split tunneling: VPN для всех, кроме выбранных приложений " +
                        "(${selectedApps.size}), пакет Nova исключён из VPN."
                )
            }

            else -> {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (error: Exception) {
                    LogManager.log("Opera split tunneling: не удалось исключить пакет Nova из VPN: ${error.message}")
                }
                LogManager.log("Opera split tunneling: все приложения, кроме Nova, идут через VPN.")
            }
        }
    }

    private fun normalizeRegionPreference(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            "eu" -> "eu"
            "us" -> "us"
            "ru" -> "ru"
            else -> "auto"
        }
    }

    private fun shouldUseWarpTransport(regionPreference: String): Boolean {
        return regionPreference != "eu" && regionPreference != "us"
    }

    private fun shouldAllowOperaTransport(regionPreference: String): Boolean {
        if (regionPreference == "ru") return false
        return OperaProxyManager.isSupportedOnDevice(this)
    }

    private fun getOperaFallbackSequence(regionPreference: String): List<Pair<String, String>> {
        return when (regionPreference) {
            "eu" -> listOf("EU" to "EU")
            "us" -> listOf("AM" to "US")
            "ru" -> emptyList()
            else -> listOf("EU" to "EU", "AM" to "US")
        }
    }

    /**
     * Выводит сервер Private DNS мимо VPN.
     *
     * @param tunnelReachesPrivateDns проверка, доходит ли DoT до сервера через сам
     * туннель. Для прокси-транспортов обход — это дыра: система резолвит имена по
     * DoT, а не через DNS туннеля, и весь список посещённых доменов уходит провайдеру
     * по тому самому каналу, от которого пользователь и прячется. Хуже того, при
     * недоступном сервере DoT имена перестают резолвиться вовсе — снаружи это выглядит
     * как «туннель активен, а интернета в браузере нет». Поэтому если узел пропускает
     * 853/tcp, DNS остаётся внутри туннеля. Если не пропускает — обход возвращается:
     * строгий Private DNS без доступа к серверу убил бы резолвинг совсем.
     */
    private fun applyPrivateDnsBypass(
        builder: Builder,
        connectivityManager: android.net.ConnectivityManager?,
        underlyingNetwork: android.net.Network?,
        tunnelReachesPrivateDns: ((String) -> Boolean)? = null,
    ) {
        if (connectivityManager == null || underlyingNetwork == null) return

        val linkProperties = connectivityManager.getLinkProperties(underlyingNetwork) ?: return
        if (!AndroidCompat.isPrivateDnsActive(linkProperties)) return

        val privateDnsHost = AndroidCompat.getPrivateDnsServerName(linkProperties).trim()
        if (privateDnsHost.isBlank()) return

        if (tunnelReachesPrivateDns != null) {
            if (tunnelReachesPrivateDns(privateDnsHost)) {
                LogManager.log(
                    "Private DNS $privateDnsHost доступен через туннель — оставляем его внутри: " +
                        "имена резолвятся выходным узлом, а не провайдером."
                )
                return
            }
            LogManager.log(
                "Private DNS $privateDnsHost через туннель не отвечает. Выводим его мимо VPN, " +
                    "иначе строгий режим оставил бы систему совсем без резолвинга."
            )
        }

        val resolvedAddresses = resolveHostOutsideVpn(privateDnsHost, underlyingNetwork)
        if (resolvedAddresses.isEmpty()) {
            LogManager.log("Private DNS $privateDnsHost активен, но IP исключения получить не удалось.")
            return
        }

        val excluded = mutableListOf<String>()
        val excludeRouteMethod = findExcludeRouteMethod(builder) ?: return

        for (address in resolvedAddresses) {
            val prefixLength = when (address) {
                is Inet4Address -> 32
                is Inet6Address -> 128
                else -> continue
            }
            try {
                val ipPrefix = AndroidCompat.createIpPrefix(address, prefixLength) ?: continue
                excludeRouteMethod.invoke(builder, ipPrefix)
                excluded.add(address.hostAddress ?: address.toString())
            } catch (_: Exception) {
            }
        }

        if (excluded.isNotEmpty()) {
            LogManager.log("Private DNS $privateDnsHost выводим мимо VPN: ${excluded.joinToString(",")}")
        }
    }

    private fun configureInternetRoutes(
        builder: Builder,
        enableIpv6DefaultRoute: Boolean,
        connectivityManager: android.net.ConnectivityManager?,
        underlyingNetwork: android.net.Network?,
        protectedAddresses: Set<String> = emptySet(),
    ) {
        val localPrefixes = buildLocalNetworkBypassPrefixes(
            connectivityManager = connectivityManager,
            underlyingNetwork = underlyingNetwork,
            protectedAddresses = protectedAddresses,
        )
        val excludeRouteMethod = findExcludeRouteMethod(builder)
        if (excludeRouteMethod != null) {
            if (enableIpv6DefaultRoute) {
                builder.addRoute("::", 0)
            }
            builder.addRoute("0.0.0.0", 0)
            applyLocalNetworkBypass(builder, excludeRouteMethod, localPrefixes)
            return
        }

        val legacyIpv4Routes = buildInternetRouteFallback(localPrefixes, totalBits = 32)
        legacyIpv4Routes.forEach { (address, prefixLength) ->
            builder.addRoute(address, prefixLength)
        }
        if (enableIpv6DefaultRoute) {
            val legacyIpv6Routes = buildInternetRouteFallback(localPrefixes, totalBits = 128)
            legacyIpv6Routes.forEach { (address, prefixLength) ->
                builder.addRoute(address, prefixLength)
            }
        }
        LogManager.log(
            "Локальные подсети оставляем мимо VPN через legacy route fallback: " +
                localPrefixes.joinToString(",") +
                " | ipv4-routes=${legacyIpv4Routes.size}" +
                if (enableIpv6DefaultRoute) " | ipv6-routes=${buildInternetRouteFallback(localPrefixes, 128).size}" else ""
        )
    }

    private fun findExcludeRouteMethod(builder: Builder): java.lang.reflect.Method? {
        val ipPrefixClass = try {
            Class.forName("android.net.IpPrefix")
        } catch (_: Throwable) {
            null
        } ?: return null
        return try {
            builder.javaClass.getMethod("excludeRoute", ipPrefixClass)
        } catch (_: Exception) {
            null
        }
    }

    private fun applyLocalNetworkBypass(
        builder: Builder,
        excludeRouteMethod: java.lang.reflect.Method,
        localPrefixes: List<String>,
    ) {
        val excluded = mutableListOf<String>()
        for (prefix in localPrefixes) {
            val addressString = prefix.substringBefore('/')
            val prefixLength = prefix.substringAfter('/').toIntOrNull() ?: continue
            val address = runCatching { InetAddress.getByName(addressString) }.getOrNull() ?: continue
            try {
                val ipPrefix = AndroidCompat.createIpPrefix(address, prefixLength) ?: continue
                excludeRouteMethod.invoke(builder, ipPrefix)
                excluded.add(prefix)
            } catch (_: Exception) {
            }
        }
        if (excluded.isNotEmpty()) {
            LogManager.log("Локальные подсети выводим мимо VPN: ${excluded.joinToString(",")}")
        }
    }

    private fun buildLocalNetworkBypassPrefixes(
        connectivityManager: android.net.ConnectivityManager?,
        underlyingNetwork: android.net.Network?,
        protectedAddresses: Set<String> = emptySet(),
    ): List<String> {
        val prefixes = linkedSetOf(
            "10.0.0.0/8",
            "100.64.0.0/10",
            "127.0.0.0/8",
            "169.254.0.0/16",
            "172.16.0.0/12",
            "192.168.0.0/16",
            "224.0.0.0/4",
            "240.0.0.0/4",
            "::1/128",
            "fe80::/10",
            "fc00::/7",
            "ff00::/8",
        )
        val linkProperties = underlyingNetwork?.let { network ->
            connectivityManager?.getLinkProperties(network)
        }
        linkProperties
            ?.linkAddresses
            ?.forEach { linkAddress ->
                val address = linkAddress.address ?: return@forEach
                val prefixLength = linkAddress.prefixLength
                if (prefixLength <= 0) return@forEach
                when (address) {
                    is Inet4Address -> {
                        if (
                            address.isAnyLocalAddress ||
                            address.isLoopbackAddress ||
                            address.isMulticastAddress
                        ) {
                            return@forEach
                        }
                        prefixes += "${address.hostAddress}/$prefixLength"
                    }
                    is Inet6Address -> {
                        if (
                            address.isAnyLocalAddress ||
                            address.isLoopbackAddress ||
                            address.isMulticastAddress
                        ) {
                            return@forEach
                        }
                        val normalizedHost = address.hostAddress?.substringBefore('%').orEmpty()
                        if (normalizedHost.isNotBlank()) {
                            prefixes += "$normalizedHost/$prefixLength"
                        }
                    }
                }
            }
        if (protectedAddresses.isEmpty()) {
            return prefixes.toList()
        }

        val protectedRoutePrefixes = protectedAddresses.mapNotNull { raw ->
            val address = runCatching { InetAddress.getByName(raw.trim()) }.getOrNull() ?: return@mapNotNull null
            val host = address.hostAddress?.substringBefore('%').orEmpty()
            if (host.isBlank()) return@mapNotNull null
            val bits = when (address) {
                is Inet4Address -> 32
                is Inet6Address -> 128
                else -> return@mapNotNull null
            }
            parseRoutePrefix("$host/$bits", bits)
        }
        if (protectedRoutePrefixes.isEmpty()) {
            return prefixes.toList()
        }

        val adjusted = mutableListOf<String>()
        for (prefix in prefixes) {
            val route = parseRoutePrefix(prefix, 32) ?: parseRoutePrefix(prefix, 128)
            if (route == null) {
                adjusted += prefix
                continue
            }
            var current = listOf(route)
            protectedRoutePrefixes
                .filter { it.totalBits == route.totalBits }
                .forEach { protected ->
                    current = current.flatMap { candidate -> subtractRoutePrefix(candidate, protected) }
                }
            adjusted += current.map { routePrefixToAddressString(it) + "/" + it.prefixLength }
        }
        return adjusted.distinct()
    }

    private data class RoutePrefix(
        val address: BigInteger,
        val prefixLength: Int,
        val totalBits: Int,
    )

    private fun buildInternetRouteFallback(
        localPrefixes: List<String>,
        totalBits: Int,
    ): List<Pair<String, Int>> {
        val universe = listOf(RoutePrefix(BigInteger.ZERO, 0, totalBits))
        val excluded = localPrefixes.mapNotNull { prefix ->
            parseRoutePrefix(prefix, totalBits)
        }.sortedBy { it.prefixLength }
        val included = excluded.fold(universe) { current, excludedPrefix ->
            current.flatMap { subtractRoutePrefix(it, excludedPrefix) }
        }
        return included
            .sortedWith(compareBy<RoutePrefix>({ it.prefixLength }, { routePrefixToAddressString(it) }))
            .map { routePrefixToAddressString(it) to it.prefixLength }
    }

    private fun parseRoutePrefix(
        prefix: String,
        totalBits: Int,
    ): RoutePrefix? {
        val addressString = prefix.substringBefore('/').trim()
        val prefixLength = prefix.substringAfter('/').toIntOrNull() ?: return null
        val address = runCatching { InetAddress.getByName(addressString) }.getOrNull() ?: return null
        val bits = when (address) {
            is Inet4Address -> 32
            is Inet6Address -> 128
            else -> return null
        }
        if (bits != totalBits || prefixLength !in 0..totalBits) return null
        return RoutePrefix(
            address = maskRoutePrefixAddress(BigInteger(1, address.address), totalBits, prefixLength),
            prefixLength = prefixLength,
            totalBits = totalBits,
        )
    }

    private fun subtractRoutePrefix(
        source: RoutePrefix,
        excluded: RoutePrefix,
    ): List<RoutePrefix> {
        if (source.totalBits != excluded.totalBits) return listOf(source)
        if (!routePrefixesOverlap(source, excluded)) return listOf(source)
        if (source.prefixLength >= excluded.prefixLength) return emptyList()
        val childPrefixLength = source.prefixLength + 1
        val childSize = BigInteger.ONE.shiftLeft(source.totalBits - childPrefixLength)
        val left = RoutePrefix(source.address, childPrefixLength, source.totalBits)
        val right = RoutePrefix(source.address + childSize, childPrefixLength, source.totalBits)
        return subtractRoutePrefix(left, excluded) + subtractRoutePrefix(right, excluded)
    }

    private fun routePrefixesOverlap(
        first: RoutePrefix,
        second: RoutePrefix,
    ): Boolean {
        val firstEnd = routePrefixEnd(first)
        val secondEnd = routePrefixEnd(second)
        return first.address <= secondEnd && second.address <= firstEnd
    }

    private fun routePrefixEnd(prefix: RoutePrefix): BigInteger {
        val hostBits = prefix.totalBits - prefix.prefixLength
        if (hostBits <= 0) return prefix.address
        return prefix.address + BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE)
    }

    private fun maskRoutePrefixAddress(
        address: BigInteger,
        totalBits: Int,
        prefixLength: Int,
    ): BigInteger {
        if (prefixLength <= 0) return BigInteger.ZERO
        if (prefixLength >= totalBits) return address
        val shift = totalBits - prefixLength
        return address.shiftRight(shift).shiftLeft(shift)
    }

    private fun routePrefixToAddressString(prefix: RoutePrefix): String {
        val byteCount = prefix.totalBits / 8
        val raw = prefix.address.toByteArray()
        val normalized = ByteArray(byteCount)
        val copyStart = maxOf(0, raw.size - byteCount)
        val copyLength = minOf(raw.size, byteCount)
        System.arraycopy(raw, copyStart, normalized, byteCount - copyLength, copyLength)
        val address = InetAddress.getByAddress(normalized)
        return if (address is Inet6Address) {
            address.hostAddress?.substringBefore('%').orEmpty()
        } else {
            address.hostAddress.orEmpty()
        }
    }

    /**
     * Резолвит имя мимо туннеля, с жёстким пределом по времени.
     *
     * Предел здесь — не перестраховка. Раньше стоял голый
     * `InetAddress.getAllByName` в потоке подключения, внутри создания
     * TUN-интерфейса. На устройстве со строгим Private DNS
     * (`private_dns_mode=hostname`) системный резолвер обязан сходить к DoT-серверу,
     * а тот в этот момент недостижим — VPN ещё не поднят, а прямой путь режется.
     * Резолв висел около восьмидесяти секунд, попытка не начиналась вовсе, и
     * фоновый heartbeat успевал перезапустить цикл подключения с начала. Снаружи
     * это выглядело как «1/50 и дальше не идёт»: счётчик вечно на первом профиле,
     * ни одной строки в журнале между поднятием TUN и перезапуском.
     *
     * Имя резолвим через подложную сеть, а не через общий резолвер: после того как
     * туннель поднят, общий резолвер уходит в него, и «мимо VPN» перестаёт быть
     * правдой.
     */
    private fun resolveHostOutsideVpn(
        hostname: String,
        underlyingNetwork: android.net.Network? = null,
        timeoutMs: Long = PRIVATE_DNS_RESOLVE_TIMEOUT_MS,
    ): List<InetAddress> {
        val executor = Executors.newSingleThreadExecutor()
        return try {
            val task = executor.submit<List<InetAddress>> {
                val resolved = underlyingNetwork?.getAllByName(hostname)
                    ?: InetAddress.getAllByName(hostname)
                resolved.toList().distinctBy { it.hostAddress ?: it.toString() }
            }
            try {
                task.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                task.cancel(true)
                LogManager.log(
                    "Резолв $hostname мимо VPN не уложился в $timeoutMs мс. Идём дальше без него: " +
                        "молча ждать здесь нельзя, ожидание съедает всю попытку подключения."
                )
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun isPlausibleWarpEndpoint(ip: String): Boolean {
        val normalized = ip.removePrefix("[").removeSuffix("]").lowercase()
        val warpPrefixes = listOf(
            "162.159.192.", "162.159.193.", "162.159.195.",
            "162.159.204.",
            "188.114.96.", "188.114.97.", "188.114.98.", "188.114.99.",
            "2606:4700:d0:", "2606:4700:d1:"
        )
        return warpPrefixes.any { normalized.startsWith(it) }
    }

    private fun shouldForceImmediateWarpMaskOnRestrictedMobileNetwork(
        connectivityManager: android.net.ConnectivityManager?,
        clientData: ClientData,
    ): Boolean {
        fun resetActiveRestrictedMobile(reason: String) {
            val shouldLog = synchronized(restrictedMobileCheckLock) {
                val changed = lastRestrictedMobileDetected
                lastRestrictedMobileCheckNetworkId = null
                lastRestrictedMobileCheckAtMs = 0L
                lastRestrictedMobileDetected = false
                changed
            }
            if (shouldLog) {
                LogManager.log(
                    "Сбрасываем режим restricted-mobile: $reason. " +
                        "Для обычного Wi-Fi возвращаем WARP к немаскированному первичному профилю."
                )
            }
        }

        if (connectivityManager == null) {
            resetActiveRestrictedMobile("нет ConnectivityManager")
            return false
        }
        if (!clientData.getTrafficMaskEnabled()) {
            resetActiveRestrictedMobile("маскировка трафика отключена")
            return false
        }
        if (clientData.getTrafficMaskMode() != "auto") {
            resetActiveRestrictedMobile("режим маскировки не AUTO")
            return false
        }

        val underlyingNetwork = selectUnderlyingNetwork(connectivityManager)
        if (underlyingNetwork == null) {
            resetActiveRestrictedMobile("подложная сеть не определена")
            return false
        }
        val caps = connectivityManager.getNetworkCapabilities(underlyingNetwork)
        if (caps == null) {
            resetActiveRestrictedMobile("capabilities подложной сети недоступны")
            return false
        }
        if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
            resetActiveRestrictedMobile("активная подложная сеть не cellular")
            return false
        }

        val networkId = RestrictedMobileDetector.buildNetworkId(underlyingNetwork)
        if (networkId == null) {
            resetActiveRestrictedMobile("не удалось собрать networkId для mobile сети")
            return false
        }
        val nowMs = SystemClock.elapsedRealtime()
        clientData.getCachedRestrictedMobileStatus(networkId, freshnessMs = 30_000L)?.let { cached ->
            synchronized(restrictedMobileCheckLock) {
                lastRestrictedMobileCheckNetworkId = networkId
                lastRestrictedMobileCheckAtMs = nowMs
                lastRestrictedMobileDetected = cached
            }
            if (cached) {
                LogManager.log(
                    "Для активной мобильной сети уже подтверждён режим белых списков. " +
                        "WARP сразу используем с AUTO-маскировкой только по доменам из white.sni."
                )
            }
            return cached
        }
        synchronized(restrictedMobileCheckLock) {
            if (
                networkId == lastRestrictedMobileCheckNetworkId &&
                nowMs - lastRestrictedMobileCheckAtMs <= 30_000L
            ) {
                return lastRestrictedMobileDetected
            }
        }

        val probeTargets = listOf(
            "1.1.1.1" to 443,
            "1.0.0.1" to 443,
            "8.8.8.8" to 443,
        )
        var reachableTargets = 0
        for ((host, port) in probeTargets) {
            if (tcpProbe(underlyingNetwork, host, port, 350)) {
                reachableTargets += 1
                break
            }
        }
        val restricted = reachableTargets == 0
        synchronized(restrictedMobileCheckLock) {
            lastRestrictedMobileCheckNetworkId = networkId
            lastRestrictedMobileCheckAtMs = nowMs
            lastRestrictedMobileDetected = restricted
        }
        clientData.cacheRestrictedMobileStatus(
            networkId = networkId,
            detected = restricted,
        )

        LogManager.log(
            if (restricted) {
                "Похоже, на мобильной сети действует ограниченный доступ/белые списки: " +
                    "публичные DNS IP (1.1.1.1/8.8.8.8) недоступны. " +
                    "Для WARP сразу включаем AUTO-маскировку и ограничиваем SNI доменами из white.sni."
            } else {
                "Публичные DNS IP доступны на мобильной сети. WARP AUTO-маскировку можно отложить до retry."
            }
        )
        return restricted
    }

    private fun hasTunnelConnectivity(
        vpnNet: android.net.Network?,
        probeBudgetMs: Int = 900,
        allowHttpDnsFallback: Boolean = true,
    ): Boolean {
        if (vpnNet == null) return false
        val budgetDeadlineMs = SystemClock.elapsedRealtime() + probeBudgetMs.coerceAtLeast(250)
        fun remainingBudgetMs(): Int =
            (budgetDeadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L).toInt()

        val tcpTargets = listOf(
            "1.1.1.1" to 443,
            "1.0.0.1" to 443,
            "8.8.8.8" to 443,
            "9.9.9.9" to 443,
        )
        // Место под HTTP-проверку держим всегда: с литеральным адресом она недорогая и
        // решает вопрос вернее, чем перебор ещё двух TCP-адресов.
        val reservedForHttpFallbackMs = 420
        val tcpTargetsToProbe = tcpTargets.take(2)
        val tcpBudgetMs = (probeBudgetMs - reservedForHttpFallbackMs).coerceAtLeast(220)
        val tcpTimeoutMs = (tcpBudgetMs / tcpTargetsToProbe.size).coerceIn(120, 320)
        var tcpReachable = false
        for ((host, port) in tcpTargetsToProbe) {
            val remaining = remainingBudgetMs()
            if (remaining <= reservedForHttpFallbackMs) {
                break
            }
            if (remaining < 140) {
                return false
            }
            if (tcpProbe(vpnNet, host, port, minOf(tcpTimeoutMs, remaining))) {
                tcpReachable = true
                break
            }
        }
        // HTTP-проверка идёт раньше DNS и по литеральному адресу, без имени.
        //
        // Дефект, который это чинит: `connectTimeout` у HttpURLConnection ограничивает
        // соединение, но не разрешение имени. Резолвер здесь пользовательский
        // (xbox-primary-warp-ru), и с чужого выхода он вполне может не отвечать — тогда
        // каждая проверка висела до собственного отказа резолвера. Замер на тестовом устройстве:
        // проба с бюджетом 1200мс занимала 4.8 секунды и съедала попытку целиком — при
        // живом туннеле, где TCP до 1.1.1.1:443 проходил за 36мс.
        //
        // По литеральному адресу разрешать нечего, и проверка укладывается в бюджет. Это
        // ещё и честнее по сути: транспорт отвечает за то, доходят ли пакеты, а не за то,
        // отвечает ли выбранный пользователем резолвер.
        val httpTargets = listOf(
            "http://1.1.1.1/",
            "http://8.8.8.8/",
        )
        for (url in httpTargets) {
            val remaining = remainingBudgetMs()
            if (remaining < 220) {
                break
            }
            if (httpProbe(vpnNet, url, minOf(700, remaining))) return true
        }
        // DNS — последней и только в дополнение к TCP: она подтверждает резолвер, а
        // транспорт мы к этому моменту уже проверили.
        val dnsProbeBudgetMs = minOf(
            if (allowHttpDnsFallback) 750 else 550,
            remainingBudgetMs().coerceAtLeast(0),
        )
        return tcpReachable &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            dnsProbeBudgetMs >= 220 &&
            dnsResolutionProbe(vpnNet, dnsProbeBudgetMs)
    }

    private fun startWarpQualitySampling(
        clientData: ClientData,
        attempt: ConnectionAttempt,
        connectGenerationId: Int,
        attemptActive: AtomicBoolean,
        strategyScope: String,
        windowMs: Long = 20_000L,
        sampleIntervalMs: Long = 1_000L,
        qualitySamplingFinished: AtomicBoolean? = null,
        qualitySamplingHealthy: AtomicBoolean? = null,
        onHealthyConfirmed: (() -> Unit)? = null,
    ) {
        Thread(
            {
                var probeCount = 0
                var pingSuccesses = 0
                var latencyTotalMs = 0L
                val startedAt = SystemClock.elapsedRealtime()
                val effectiveSampleIntervalMs = if (resourceConstrainedDevice) {
                    maxOf(sampleIntervalMs, 1_600L)
                } else {
                    sampleIntervalMs
                }
                val effectiveProbeTimeoutMs = if (resourceConstrainedDevice) 650 else 900
                // Замер удержания закрывается раньше окна качества. Окно удержания
                // попытки и это окно равны и отсчитываются от одной отметки, поэтому
                // последние итерации приходятся на уже разбираемый туннель:
                // `Nova.stopVPN()` вызван, а флаг попытки ещё не снят. Проба по
                // мёртвому туннелю добавляет тишину каждому профилю, включая
                // здоровые, то есть смещение било бы ровно по тем, кто окно выдержал.
                val holdWindowMs = (windowMs - SessionHoldMetric.TAIL_GUARD_MS).coerceAtLeast(0L)
                // Тишина меряется по uptimeMillis, а не по elapsedRealtime, и это не
                // мелочь. elapsedRealtime идёт и во сне устройства: в журнале Pixel 4 XL
                // есть окна вида «4/4, avg=56836ms» — пробы удались, но между ними
                // телефон спал почти минуту. По elapsedRealtime это выглядело бы как
                // минута молчания узла, и здоровый узел получил бы худшую оценку за
                // дозу Android. uptimeMillis во сне стоит, поэтому непронаблюдённое
                // время просто не засчитывается: спавшее окно окажется слишком
                // коротким и будет отброшено как непоказательное.
                val holdStartedAtMs = SystemClock.uptimeMillis()
                val hold = SessionHoldMetric.Accumulator(startedAtMs = holdStartedAtMs)
                var holdFinishedAtMs = holdStartedAtMs
                try {
                    val cm = getSystemService(android.net.ConnectivityManager::class.java)
                    while (
                        attemptActive.get() &&
                            !isUserStopped &&
                            isConnectGenerationCurrent(connectGenerationId) &&
                            SystemClock.elapsedRealtime() - startedAt < windowMs
                    ) {
                        Thread.sleep(effectiveSampleIntervalMs)
                        if (!attemptActive.get() || !isConnectGenerationCurrent(connectGenerationId)) break
                        val vpnNet = findCurrentVpnNetwork(cm)
                        val latency = measureWarpQualityLatency(vpnNet, timeoutMs = effectiveProbeTimeoutMs)
                        probeCount += 1
                        if (latency >= 0) {
                            pingSuccesses += 1
                            latencyTotalMs += latency.toLong()
                        }
                        if (SystemClock.elapsedRealtime() - startedAt <= holdWindowMs) {
                            val holdNowMs = SystemClock.uptimeMillis()
                            // Итерация без VPN-сети наружу ничего не отправила —
                            // такую тишину узлу не приписываем, но и полноценным
                            // окно после неё не считаем.
                            if (vpnNet == null) {
                                hold.noteSkipped(holdNowMs)
                            } else {
                                hold.note(succeeded = latency >= 0, atMs = holdNowMs)
                            }
                            holdFinishedAtMs = holdNowMs
                        }
                    }
                } catch (error: Throwable) {
                    LogManager.log("WARP quality sampling завершился ошибкой: ${error.message}")
                } finally {
                    recordWarpHoldWindow(
                        clientData = clientData,
                        attempt = attempt,
                        window = hold.finish(
                            minOf(
                                holdFinishedAtMs.coerceAtLeast(holdStartedAtMs),
                                holdStartedAtMs + holdWindowMs,
                            )
                        ),
                    )
                    if (probeCount > 0) {
                        val avgPingMs = if (pingSuccesses > 0) {
                            latencyTotalMs.toDouble() / pingSuccesses.toDouble()
                        } else {
                            0.0
                        }
                        val degradedQuality = isDegradedWarpQualitySample(
                            probeCount = probeCount,
                            pingSuccesses = pingSuccesses,
                            avgPingMs = avgPingMs,
                        )
                        if (degradedQuality && pingSuccesses > 0) {
                            clientData.recordWarpVerifiedDegradedQualityResult(
                                engine = attempt.mode.engine,
                                mode = attempt.mode.name,
                                host = attempt.endpointHost,
                                port = attempt.port,
                                probeCount = probeCount,
                                pingSuccesses = pingSuccesses,
                                avgPingMs = avgPingMs,
                                endpointSource = normalizeVerifiedConfigSource(attempt.endpointSource),
                                rawConfig = buildWarpConfigDescription(attempt),
                                scope = strategyScope,
                            )
                        } else {
                            clientData.recordWarpVerifiedQualityResult(
                                engine = attempt.mode.engine,
                                mode = attempt.mode.name,
                                host = attempt.endpointHost,
                                port = attempt.port,
                                success = pingSuccesses > 0,
                                probeCount = probeCount,
                                pingSuccesses = pingSuccesses,
                                avgPingMs = avgPingMs,
                                endpointSource = normalizeVerifiedConfigSource(attempt.endpointSource),
                                rawConfig = buildWarpConfigDescription(attempt),
                                scope = strategyScope,
                            )
                        }
                        Log.w("NovaAdapt", "sampling done: ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port} probeCount=$probeCount pingSuccesses=$pingSuccesses degraded=$degradedQuality")
                        val qualityLabel = if (degradedQuality) "degraded" else "ok"
                        LogManager.log(
                            "WARP quality [$qualityLabel]: ${attempt.mode.name}@${attempt.endpointHost}:${attempt.port} " +
                                "$pingSuccesses/$probeCount, avg=${avgPingMs.toInt().takeIf { it > 0 } ?: "-"}ms."
                        )
                        qualitySamplingHealthy?.set(!degradedQuality && pingSuccesses > 0)
                        qualitySamplingFinished?.set(true)
                        if (!degradedQuality && pingSuccesses > 0) {
                            runCatching { onHealthyConfirmed?.invoke() }
                        }
                        val shouldRecover = degradedQuality &&
                            shouldForceWarpQualityRecovery(
                                probeCount = probeCount,
                                pingSuccesses = pingSuccesses,
                                avgPingMs = avgPingMs,
                            ) &&
                            !(attempt.importedConfigHost != null && attempt.mode.preferImportedRawIdentity) &&
                            !reconnectingForNetworkChange &&
                            SystemClock.elapsedRealtime() - lastNetworkReconnectAt >= 15_000L &&
                            currentState == STATE_CONNECTED &&
                            currentBackendLabel.trim().uppercase(Locale.ROOT).startsWith(BACKEND_WARP) &&
                            attemptActive.get() &&
                            !isUserStopped &&
                            isConnectGenerationCurrent(connectGenerationId)
                        if (shouldRecover) {
                            lastNetworkReconnectAt = SystemClock.elapsedRealtime()
                            activeWarpQualityTargetDemoted = true
                            clearActiveWarpQualityTarget()
                            LogManager.log(
                                "Качество текущего WARP-профиля остаётся слишком слабым. " +
                                    "Запускаем мягкий реконнект к следующему профилю/порту."
                            )
                            triggerReconnectForNetworkChange(clientData)
                        }
                    } else {
                        qualitySamplingHealthy?.set(false)
                        qualitySamplingFinished?.set(true)
                    }
                }
            },
            "NovaWarpQualitySampler"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun measureWarpQualityLatency(
        network: android.net.Network?,
        timeoutMs: Int,
    ): Int {
        if (network == null) return -1
        val startedAt = SystemClock.elapsedRealtime()
        val tcpTargets = listOf(
            "1.1.1.1" to 443,
            "8.8.8.8" to 443,
            "9.9.9.9" to 443,
        )
        val perTargetTimeoutMs = (timeoutMs / tcpTargets.size.coerceAtLeast(1)).coerceIn(180, 420)
        for ((host, port) in tcpTargets) {
            val targetStartedAt = SystemClock.elapsedRealtime()
            if (tcpProbe(network, host, port, perTargetTimeoutMs)) {
                return (SystemClock.elapsedRealtime() - targetStartedAt).toInt().coerceAtLeast(1)
            }
        }
        val remainingMs = (timeoutMs - (SystemClock.elapsedRealtime() - startedAt)).coerceAtLeast(220L).toInt()
        val httpStartedAt = SystemClock.elapsedRealtime()
        return if (httpProbe(network, "https://cp.cloudflare.com/generate_204", remainingMs.coerceAtMost(700))) {
            (SystemClock.elapsedRealtime() - httpStartedAt).toInt().coerceAtLeast(1)
        } else {
            -1
        }
    }

    private fun dnsResolutionProbe(
        network: android.net.Network,
        timeoutMs: Int,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val effectiveTimeoutMs = timeoutMs.coerceAtLeast(220)
        val tick = SystemClock.elapsedRealtime().toString()
        val probeHosts = listOf(
            "nova-$tick-1-1-1-1.sslip.io",
            "nova-$tick.1.1.1.1.nip.io",
        )
        val perHostTimeoutMs = (effectiveTimeoutMs / probeHosts.size).coerceAtLeast(220)
        return probeHosts.any { host ->
            dnsResolutionProbeSingle(network, host, perHostTimeoutMs)
        }
    }

    private fun dnsResolutionProbeSingle(
        network: android.net.Network,
        host: String,
        timeoutMs: Int,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(1)
        val resolved = AtomicBoolean(false)
        val cancellationSignal = CancellationSignal()
        return try {
            DnsResolver.getInstance().query(
                network,
                host,
                DnsResolver.FLAG_EMPTY,
                executor,
                cancellationSignal,
                object : DnsResolver.Callback<List<InetAddress>> {
                    override fun onAnswer(answer: List<InetAddress>, rcode: Int) {
                        resolved.set(answer.isNotEmpty())
                        latch.countDown()
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        latch.countDown()
                    }
                }
            )
            val completed = latch.await(timeoutMs.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!completed) {
                cancellationSignal.cancel()
            }
            completed && resolved.get()
        } catch (_: Throwable) {
            false
        } finally {
            executor.shutdownNow()
        }
    }

    private fun hasIpv6Connectivity(): Boolean {
        return tcpProbeUnbound("2606:4700:4700::1111", 443, 1500)
    }

    private fun tcpProbe(network: android.net.Network, host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.isConnected
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun tcpProbeUnbound(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.isConnected
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun httpProbe(network: android.net.Network, url: String, timeoutMs: Int): Boolean {
        return try {
            val connection = network.openConnection(URL(url)) as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.requestMethod = "GET"
            val code = try {
                connection.inputStream.use { it.read() }
                connection.responseCode
            } finally {
                connection.disconnect()
            }
            code == HttpURLConnection.HTTP_NO_CONTENT ||
                code == HttpURLConnection.HTTP_OK ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 ||
                code == 308
        } catch (_: Exception) {
            false
        }
    }

    private fun hasOperaProxyConnectivity(timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(OperaProxyManager.getLoopbackProxyAddress(this), timeoutMs)
                socket.soTimeout = timeoutMs
                // Поток закрывать нельзя: закрытие потока сокета закрывает и сам сокет.
                //
                // Дефект, который это чинит: `use` закрывал writer сразу после flush, и
                // прокси видел обрыв клиента раньше, чем успевал сходить наружу. В его
                // журнале это «HTTP fetch error: context canceled» через 2мс после
                // запроса, а проба возвращала false всегда. На Opera она единственная,
                // кто может подтвердить туннель: CONNECT на 53-й порт прокси не
                // пропускает, поэтому DNS-проба там не проходит по определению, а
                // системный VALIDATED без DNS не выставляется. Живой туннель, гонявший
                // трафик, из-за этого раз за разом гасился как «не дал tunnel-probe».
                val writer = socket.getOutputStream().bufferedWriter()
                writer.write("GET http://api.ipify.org/ HTTP/1.1\r\n")
                writer.write("Host: api.ipify.org\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.flush()
                val statusLine = socket.getInputStream().bufferedReader().readLine().orEmpty()
                statusLine.contains("200")
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Через Opera туннеля Cloudflare нет — там трафик идёт по HTTP-прокси, и
     * запрос ушёл бы мимо, в заблокированный домен. Поэтому добываем личность
     * только на собственном WARP/MASQUE-туннеле.
     */
    private fun scheduleWarpIdentityBackfill() {
        if (isOperaBackendLabel(currentBackendLabel)) return
        // Ключ MASQUE добываем только тогда, когда MASQUE выбран.
        //
        // Раньше фоновая регистрация запускалась при любом подключении, если готового
        // профиля нет. Это сетевые запросы к api.cloudflareclient.com и подъём
        // Opera-прокси ради протокола, которым пользователь не пользуется. Выбран
        // MASQUE — добываем; выбрано другое — не трогаем.
        val backfillClientData = ClientData(applicationContext)
        val masqueSelected = backfillClientData
            .getExitRegionPreference()
            .trim()
            .lowercase(Locale.US) == "masque"
        // Либо MASQUE выбран сейчас, либо мы уже пообещали пользователю ключ и попросили
        // его подключиться по «Авто» — тогда добываем, хотя выбран другой протокол.
        if (!masqueSelected && !backfillClientData.isMasqueIdentityWanted()) return
        WarpIdentityBackfill.scheduleAfterConnect(
            context = applicationContext,
            logger = LogManager::log,
            tunnelStillUp = {
                currentState == STATE_CONNECTED &&
                    !isUserStopped &&
                    !isOperaBackendLabel(currentBackendLabel)
            },
            // Сюда доходим только с выбранным MASQUE: пользователь сидит без него и
            // ждёт результата, а не фоновой любезности. Паузу после неудачи не выдерживаем.
            ignoreCooldown = true,
        )
    }

    private fun refreshConnectedExitObservationAsync() {
        if (!exitObservationRefreshInFlight.compareAndSet(false, true)) return
        LogManager.log("Запускаем refresh внешнего IP/региона для UI после STATE_CONNECTED.")
        Thread(
            {
                try {
                    val clientData = ClientData(this)
                    val backend = currentBackendLabel.ifBlank { clientData.getServiceBackend() }
                    val connectivityManager = getSystemService(android.net.ConnectivityManager::class.java)
                    repeat(5) { index ->
                        if (currentState != STATE_CONNECTED) return@Thread
                        val snapshot = when {
                            isOperaBackendLabel(backend) -> fetchExitSnapshotViaOperaProxy()
                            // Для VLESS запасного пути «через сеть устройства» нет и
                            // быть не должно: он приносит адрес и страну провайдера,
                            // и бейдж показывал RU при выходе в Сингапуре.
                            isVlessBackendLabel(backend) -> fetchExitSnapshotViaVlessProxy()
                            else -> {
                                val vpnNetwork = findCurrentVpnNetwork(connectivityManager)
                                fetchExitSnapshot(vpnNetwork) ?: fetchExitSnapshot(null)
                            }
                        } ?: fallbackExitSnapshot(clientData, backend)

                        if (snapshot != null && hasMeaningfulExitSnapshot(snapshot)) {
                            val primaryIp = when {
                                snapshot.ipv4.isNotBlank() -> snapshot.ipv4
                                snapshot.ipv6.isNotBlank() -> snapshot.ipv6
                                else -> ""
                            }
                            // Прошлая страна годится только для того же адреса. Пока
                            // она подставлялась к любому свежему IP, смена узла давала
                            // новый адрес со старой страной — выход в Сингапуре, бейдж
                            // «RU». Не знаем страну — честнее показать её пустой.
                            val previousIp = clientData.getLastExitIp().trim()
                            val sameExitAsBefore = primaryIp.isNotBlank() && primaryIp == previousIp
                            val displayCountry = snapshot.country.ifBlank {
                                if (sameExitAsBefore) clientData.getLastExitCountry() else ""
                            }
                            if (primaryIp.isNotBlank()) {
                                clientData.saveLastExitObservation(
                                    ip = primaryIp,
                                    country = displayCountry,
                                    colo = snapshot.colo,
                                )
                                reapplyDnsOrderForObservedCountry(clientData, displayCountry)
                            }
                            clientData.saveTunnelUiSnapshot(
                                ipv4 = snapshot.ipv4,
                                ipv6 = snapshot.ipv6,
                                country = displayCountry,
                                backend = backend,
                            )
                            LogManager.log(
                                "Refresh внешнего IP/региона для UI успешен: ip=${primaryIp.ifBlank { "-" }}, " +
                                    "country=${snapshot.country.ifBlank { "-" }}, backend=$backend"
                            )
                            return@Thread
                        }
                        if (index < 4) {
                            LogManager.log("Refresh внешнего IP/региона для UI: попытка ${index + 1}/5 пока без данных.")
                            Thread.sleep(1_500L)
                        }
                    }
                    LogManager.log("Не удалось обновить внешний IP/регион для UI после успешного подключения.")
                } catch (error: Throwable) {
                    LogManager.log("Не удалось обновить внешний IP/регион для UI: ${error.message}")
                } finally {
                    exitObservationRefreshInFlight.set(false)
                }
            },
            "NovaExitObservationRefresh"
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun fetchExitSnapshot(network: android.net.Network?): ExitSnapshot? {
        val fastTrace = fetchExitTraceViaSocket(
            network,
            listOf("1.1.1.1", "1.0.0.1"),
            timeoutMs = if (network != null) 1200 else 1800,
        )
        if (fastTrace != null) {
            return fastTrace
        }
        if (network != null) {
            return null
        }

        val ipv4Trace = fetchExitTraceFromUrls(
            network,
            listOf(
                "http://1.1.1.1/cdn-cgi/trace",
                "http://1.0.0.1/cdn-cgi/trace",
            )
        )
        val ipv6Trace = fetchExitTraceFromUrls(
            network,
            listOf(
                "http://[2606:4700:4700::1111]/cdn-cgi/trace",
                "http://[2606:4700:4700::1001]/cdn-cgi/trace",
            )
        )
        val genericTrace = fetchExitTraceFromUrls(
            network,
            listOf(
                "https://www.cloudflare.com/cdn-cgi/trace",
                "https://cloudflare.com/cdn-cgi/trace",
            )
        )

        val ipv4 = ipv4Trace?.ipv4.orEmpty().ifBlank {
            genericTrace?.ipv4.orEmpty()
        }.ifBlank {
            fetchExitPlainIpFromUrls(
                network,
                listOf(
                    "https://api4.ipify.org",
                    "https://ipv4.icanhazip.com",
                    "https://v4.ident.me",
                )
            ).orEmpty()
        }
        val ipv6 = ipv6Trace?.ipv6.orEmpty().ifBlank {
            genericTrace?.ipv6.orEmpty()
        }.ifBlank {
            fetchExitPlainIpFromUrls(
                network,
                listOf(
                    "https://api6.ipify.org",
                    "https://ipv6.icanhazip.com",
                    "https://v6.ident.me",
                )
            ).orEmpty()
        }
        val badgeTrace = ipv4Trace ?: ipv6Trace ?: genericTrace
        if (ipv4.isBlank() && ipv6.isBlank() && badgeTrace?.country.orEmpty().isBlank()) return null
        return ExitSnapshot(
            ipv4 = ipv4,
            ipv6 = ipv6,
            country = badgeTrace?.country.orEmpty(),
            colo = badgeTrace?.colo.orEmpty(),
        )
    }

    /**
     * Внешний адрес и страна — через SOCKS-инбаунд ядра Xray.
     *
     * Единственный путь, который гарантированно идёт через выходной узел. Общий путь
     * через сеть VPN для VLESS ненадёжен, а его запасной вариант ходит по сети
     * устройства и приносит адрес и страну провайдера — снаружи это выглядело как
     * «выход в Сингапуре, а на бейдже RU».
     *
     * Берётся HTTPS-трейс: простой HTTP на anycast-адрес резолвера через узел не
     * проходит вовсе (проверено на живом узле — пустой ответ), а по HTTPS Cloudflare
     * честно отдаёт `loc` и `colo` выходного узла.
     */
    private fun fetchExitSnapshotViaVlessProxy(): ExitSnapshot? {
        val socksPort = vlessSocksPort.takeIf { it in 1..65535 } ?: return null
        for (host in listOf("www.cloudflare.com", "cloudflare.com")) {
            val body = readTextThroughSocks(socksPort, host, "/cdn-cgi/trace") ?: continue
            val snapshot = parseExitTraceBody(body)
            if (snapshot != null) return snapshot
        }
        return null
    }

    /** Разбор тела `cdn-cgi/trace`. */
    private fun parseExitTraceBody(body: String): ExitSnapshot? {
        val lines = body.lineSequence().toList()
        val traceIp = lines.firstOrNull { it.startsWith("ip=") }?.substringAfter("=")?.trim().orEmpty()
        val traceCountry = lines.firstOrNull { it.startsWith("loc=") }?.substringAfter("=")?.trim().orEmpty()
        val traceColo = lines.firstOrNull { it.startsWith("colo=") }?.substringAfter("=")?.trim().orEmpty()
        if (traceIp.isBlank() && traceCountry.isBlank()) return null
        return ExitSnapshot(
            ipv4 = traceIp.takeIf { it.contains('.') && !it.contains(':') }.orEmpty(),
            ipv6 = traceIp.takeIf { it.contains(':') }.orEmpty(),
            country = traceCountry,
            colo = traceColo,
        )
    }

    /** GET по HTTPS через SOCKS-инбаунд ядра. Имя резолвит узел. */
    private fun readTextThroughSocks(
        socksPort: Int,
        host: String,
        path: String,
        timeoutMs: Int = 6_000,
    ): String? {
        val tunnel = openSocksTunnel(socksPort, host, 443, timeoutMs) ?: return null
        return try {
            val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            factory.createSocket(tunnel, host, 443, true).use { tls ->
                tls.soTimeout = timeoutMs
                val output = tls.getOutputStream()
                output.write(
                    (
                        "GET $path HTTP/1.1\r\n" +
                            "Host: $host\r\n" +
                            "User-Agent: Nova\r\n" +
                            "Connection: close\r\n\r\n"
                        ).toByteArray(Charsets.US_ASCII)
                )
                output.flush()
                val text = tls.getInputStream().bufferedReader(Charsets.UTF_8).readText()
                text.substringAfter("\r\n\r\n", "").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        } finally {
            try {
                tunnel.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun fetchExitSnapshotViaOperaProxy(): ExitSnapshot? {
        val proxy = Proxy(Proxy.Type.HTTP, OperaProxyManager.getLoopbackProxyAddress(this))
        val genericTrace = fetchExitTraceFromUrlsViaProxy(
            proxy,
            listOf(
                "https://www.cloudflare.com/cdn-cgi/trace",
                "https://cloudflare.com/cdn-cgi/trace",
            )
        )
        val ipv4 = fetchExitPlainIpFromUrlsViaProxy(
            proxy,
            listOf(
                "http://api.ipify.org",
                "http://v4.ident.me",
                "http://ipv4.icanhazip.com",
                "https://api4.ipify.org",
            )
        ).orEmpty()
        val ipv6 = fetchExitPlainIpFromUrlsViaProxy(
            proxy,
            listOf(
                "http://v6.ident.me",
                "http://ipv6.icanhazip.com",
                "https://api6.ipify.org",
            )
        ).orEmpty()
        if (ipv4.isBlank() && ipv6.isBlank() && genericTrace?.country.orEmpty().isBlank()) return null
        return ExitSnapshot(
            ipv4 = ipv4,
            ipv6 = ipv6,
            country = genericTrace?.country.orEmpty(),
            colo = genericTrace?.colo.orEmpty(),
        )
    }

    private fun fetchExitTraceFromUrls(
        network: android.net.Network?,
        urls: List<String>,
    ): ExitSnapshot? {
        for (url in urls) {
            val body = readTextFromUrlForExit(network, url) ?: continue
            val lines = body.lineSequence().toList()
            val traceIp = lines.firstOrNull { it.startsWith("ip=") }?.substringAfter("=")?.trim().orEmpty()
            val traceCountry = lines.firstOrNull { it.startsWith("loc=") }?.substringAfter("=")?.trim().orEmpty()
            val traceColo = lines.firstOrNull { it.startsWith("colo=") }?.substringAfter("=")?.trim().orEmpty()
            if (traceIp.isNotBlank() || traceCountry.isNotBlank()) {
                return ExitSnapshot(
                    ipv4 = traceIp.takeIf { it.contains('.') && !it.contains(':') }.orEmpty(),
                    ipv6 = traceIp.takeIf { it.contains(':') }.orEmpty(),
                    country = traceCountry,
                    colo = traceColo,
                )
            }
        }
        return null
    }

    private fun fetchExitTraceViaSocket(
        network: android.net.Network?,
        hosts: List<String>,
        timeoutMs: Int = 4000,
    ): ExitSnapshot? {
        for (host in hosts) {
            val body = readTraceViaSocket(network, host, timeoutMs) ?: continue
            val lines = body.lineSequence().toList()
            val traceIp = lines.firstOrNull { it.startsWith("ip=") }?.substringAfter("=")?.trim().orEmpty()
            val traceCountry = lines.firstOrNull { it.startsWith("loc=") }?.substringAfter("=")?.trim().orEmpty()
            val traceColo = lines.firstOrNull { it.startsWith("colo=") }?.substringAfter("=")?.trim().orEmpty()
            if (traceIp.isNotBlank() || traceCountry.isNotBlank()) {
                return ExitSnapshot(
                    ipv4 = traceIp.takeIf { it.contains('.') && !it.contains(':') }.orEmpty(),
                    ipv6 = traceIp.takeIf { it.contains(':') }.orEmpty(),
                    country = traceCountry,
                    colo = traceColo,
                )
            }
        }
        return null
    }

    private fun fetchExitTraceFromUrlsViaProxy(
        proxy: Proxy,
        urls: List<String>,
    ): ExitSnapshot? {
        for (url in urls) {
            val body = readTextFromUrlViaProxyForExit(proxy, url) ?: continue
            val lines = body.lineSequence().toList()
            val traceIp = lines.firstOrNull { it.startsWith("ip=") }?.substringAfter("=")?.trim().orEmpty()
            val traceCountry = lines.firstOrNull { it.startsWith("loc=") }?.substringAfter("=")?.trim().orEmpty()
            val traceColo = lines.firstOrNull { it.startsWith("colo=") }?.substringAfter("=")?.trim().orEmpty()
            if (traceIp.isNotBlank() || traceCountry.isNotBlank()) {
                return ExitSnapshot(
                    ipv4 = traceIp.takeIf { it.contains('.') && !it.contains(':') }.orEmpty(),
                    ipv6 = traceIp.takeIf { it.contains(':') }.orEmpty(),
                    country = traceCountry,
                    colo = traceColo,
                )
            }
        }
        return null
    }

    private fun fetchExitPlainIpFromUrls(
        network: android.net.Network?,
        urls: List<String>,
    ): String? {
        for (url in urls) {
            val body = readTextFromUrlForExit(network, url)?.trim().orEmpty()
            if (body.isNotBlank()) {
                return body.lineSequence().firstOrNull()?.trim().orEmpty()
            }
        }
        return null
    }

    private fun fetchExitPlainIpFromUrlsViaProxy(
        proxy: Proxy,
        urls: List<String>,
    ): String? {
        for (url in urls) {
            val body = readTextFromUrlViaProxyForExit(proxy, url)?.trim().orEmpty()
            if (body.isNotBlank()) {
                return body.lineSequence().firstOrNull()?.trim().orEmpty()
            }
        }
        return null
    }

    private fun readTextFromUrlForExit(
        network: android.net.Network?,
        url: String,
        timeoutMs: Int = 4_000,
    ): String? {
        return try {
            val connection = if (network != null) {
                network.openConnection(URL(url)) as HttpURLConnection
            } else {
                URL(url).openConnection() as HttpURLConnection
            }
            connection.instanceFollowRedirects = true
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "NovaAndroid/1.12")
            connection.setRequestProperty("Accept", "text/plain,*/*")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            body
        } catch (_: Exception) {
            null
        }
    }

    private fun readTraceViaSocket(
        network: android.net.Network?,
        host: String,
        timeoutMs: Int = 4000,
    ): String? {
        return try {
            val socket = if (network != null) {
                network.socketFactory.createSocket()
            } else {
                Socket()
            }
            socket.use {
                it.soTimeout = timeoutMs
                it.connect(InetSocketAddress(host, 80), timeoutMs)
                val writer = it.getOutputStream().bufferedWriter()
                writer.write("GET /cdn-cgi/trace HTTP/1.1\r\n")
                writer.write("Host: $host\r\n")
                writer.write("User-Agent: NovaAndroid/1.12\r\n")
                writer.write("Accept: text/plain,*/*\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.flush()
                runCatching { it.shutdownOutput() }
                val raw = it.getInputStream().bufferedReader().use { reader -> reader.readText() }
                raw.substringAfter("\r\n\r\n", "").ifBlank {
                    raw.substringAfter("\n\n", "")
                }.trim().takeIf { body -> body.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readTextFromUrlViaProxyForExit(
        proxy: Proxy,
        url: String,
        timeoutMs: Int = 4_000,
    ): String? {
        return try {
            val connection = URL(url).openConnection(proxy) as HttpURLConnection
            connection.instanceFollowRedirects = true
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "NovaAndroid/1.12")
            connection.setRequestProperty("Accept", "text/plain,*/*")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            connection.disconnect()
            body
        } catch (_: Exception) {
            null
        }
    }

    private fun fallbackExitSnapshot(
        clientData: ClientData,
        backend: String,
    ): ExitSnapshot? {
        val tunnelSnapshot = clientData.getTunnelUiSnapshot()
        if (
            tunnelSnapshot != null &&
            (tunnelSnapshot.ipv4.isNotBlank() || tunnelSnapshot.ipv6.isNotBlank() || tunnelSnapshot.country.isNotBlank()) &&
            tunnelSnapshot.backend.trim().equals(backend.trim(), ignoreCase = true)
        ) {
            return ExitSnapshot(
                ipv4 = tunnelSnapshot.ipv4,
                ipv6 = tunnelSnapshot.ipv6,
                country = tunnelSnapshot.country,
                colo = clientData.getLastExitColo(),
            )
        }

        val lastExitIp = clientData.getLastExitIp().trim()
        val lastExitCountry = clientData.getLastExitCountry().trim()
        if (lastExitIp.isBlank() && lastExitCountry.isBlank()) return null
        return ExitSnapshot(
            ipv4 = lastExitIp.takeIf { it.contains('.') && !it.contains(':') }.orEmpty(),
            ipv6 = lastExitIp.takeIf { it.contains(':') }.orEmpty(),
            country = lastExitCountry,
            colo = clientData.getLastExitColo(),
        )
    }

    private fun hasMeaningfulExitSnapshot(snapshot: ExitSnapshot): Boolean {
        return snapshot.ipv4.isNotBlank() || snapshot.ipv6.isNotBlank() || snapshot.country.isNotBlank()
    }

}

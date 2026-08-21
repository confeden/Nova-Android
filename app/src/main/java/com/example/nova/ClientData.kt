package com.example.nova

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import java.util.TimeZone

data class WarpConfig(
    val privateKey: String,
    val publicKey: String,
    val ipv4: String,
    val ipv6: String,
    val peerPublicKey: String,
    val peerEndpoint: String,
    val reserved: String? = null,
    val accessToken: String? = null,
    val deviceId: String? = null,
    val license: String? = null,
    val masqueConfigJson: String? = null,
)

data class ResolvedWarpConfig(
    val config: WarpConfig,
    val source: String,
    val persisted: Boolean,
)

data class RestartSession(
    val kind: String,
    val region: String,
    val privateKey: String? = null,
    val ipv4: String? = null,
    val ipv6: String? = null,
    val peerPublicKey: String? = null,
    val peerEndpoint: String? = null,
    val reserved: String? = null,
    val savedPort: Int? = null,
    val savedProto: String? = null,
)

data class TunnelUiSnapshot(
    val ipv4: String = "",
    val ipv6: String = "",
    val country: String = "",
    val backend: String = NovaVpnService.BACKEND_WARP,
    val observedAt: Long = 0L,
)

/**
 * Подписка VLESS: адрес, валидаторы условного запроса и снимок состава.
 *
 * @param knownIdentities состав подписки на момент прошлой загрузки. Нужен, чтобы
 * при обновлении отличить «узел убрали из подписки» от «профиль добавили руками»:
 * удалять можно только первое.
 */
data class VlessSubscriptionState(
    val url: String = "",
    val title: String = "",
    val etag: String = "",
    val lastModified: String = "",
    val updateIntervalHours: Int = 0,
    val lastCheckedAt: Long = 0L,
    val lastChangedAt: Long = 0L,
    val lastStatus: String = "",
    val knownIdentities: List<String> = emptyList(),
)

/**
 * Задержка, измеренная самой службой на своём транспорте.
 *
 * Нужна там, где экран измерить не может: при раздельном туннелировании пакет Nova
 * остаётся вне VPN и своей же сети VPN не видит, а порт SOCKS-инбаунда ядра выбирается
 * службой на лету и экрану неизвестен.
 */
data class TransportLatencySample(
    val latencyMs: Int = -1,
    val transport: String = "",
    val observedAt: Long = 0L,
)

data class DirectUiSnapshot(
    val ipv4: String = "",
    val ipv6: String = "",
    val country: String = "",
    val observedAt: Long = 0L,
)

data class WarpDiscoverySnapshot(
    val running: Boolean = false,
    val foundCount: Int = 0,
    val message: String = "",
    val ordinal: Int = 0,
    val total: Int = 0,
    val observedAt: Long = 0L,
)

data class LocalProxyUnauthorizedAttempt(
    val observedAt: Long = 0L,
    val protocol: String = "",
    val packageName: String = "",
    val appLabel: String = "",
    val reason: String = "",
)

data class LocalProxyStatusSnapshot(
    val enabled: Boolean = false,
    val running: Boolean = false,
    val backend: String = "",
    val host: String = "127.0.0.1",
    val port: Int = 1370,
    val observedAt: Long = 0L,
    /** Все адреса, на которых прокси реально слушает: раздача плюс общая сеть. */
    val endpoints: List<GatewayEndpoint> = emptyList(),
)

data class WarpPortStat(
    val port: Int,
    val successCount: Int = 0,
    val failureCount: Int = 0,
    val probeCount: Int = 0,
    val pingSuccesses: Int = 0,
    val avgPingMs: Double = 0.0,
    val lastSuccessAt: Long = 0L,
    val lastCheckedAt: Long = 0L,
)

data class DnsAppOverride(
    val enabled: Boolean = false,
    val packageName: String = "",
    val appLabel: String = "",
    val primaryDns: String = "",
    val secondaryDns: String = "",
    val encryptedFallback: String = "",
    val allowPlainFallback: Boolean = true,
)

data class DnsSettingsConfig(
    val globalEnabled: Boolean = false,
    val globalPrimaryDns: String = "",
    val globalSecondaryDns: String = "",
    val globalEncryptedFallback: String = "",
    val allowPlainFallback: Boolean = true,
    val routeMode: String = "auto",
    val appOverride: DnsAppOverride = DnsAppOverride(),
)

data class DnsAppOverrideStatus(
    val configured: Boolean,
    val active: Boolean,
    val waitingForExclusiveMode: Boolean,
    val packageName: String = "",
    val appLabel: String = "",
)

data class SplitTunnelSnapshot(
    val mode: Int = 0,
    val apps: Set<String> = emptySet(),
    val targetPackage: String = "",
)

data class SettingsMenuSnapshot(
    val selectorMode: String = "region",
    val selectorOptions: List<String> = emptyList(),
    val selectorSummary: String = "",
    val warpConfigsSummary: String = "",
    val localProxySummary: String = "",
    val dnsSummary: String = "",
    val logsSummary: String = "",
    val trafficMaskStatus: String = "",
    val autostartVisible: Boolean = false,
    val vendorRowLabel: String = "",
    val observedAt: Long = 0L,
)

data class WarpConfigCardSnapshot(
    val id: String = "",
    val title: String = "",
    val meta: String = "",
    val body: String = "",
    val current: Boolean = false,
    val userImported: Boolean = false,
)

data class WarpConfigsMenuSnapshot(
    val importedOnly: Boolean = false,
    val importedCount: Int = 0,
    val builtInCount: Int = 0,
    val discoveryRunning: Boolean = false,
    val discoveryMessage: String = "",
    val discoveryFoundCount: Int = 0,
    val discoveryOrdinal: Int = 0,
    val discoveryTotal: Int = 0,
    val createButtonText: String = "Сгенерировать новые",
    val adaptButtonText: String = "Адаптация к условиям сети",
    val importedToggleText: String = "Переключиться на импортированные",
    val sourceToken: String = "",
    val items: List<WarpConfigCardSnapshot> = emptyList(),
    val observedAt: Long = 0L,
)

data class DiagnosticLogSettingsConfig(
    val enabled: Boolean = false,
    val level: String = "error",
)

data class WarpVerifiedConfig(
    val id: String,
    val engine: String,
    val mode: String,
    val host: String,
    val port: Int,
    val endpointSource: String,
    val rawConfig: String,
    val createdAt: Long,
    val lastVerifiedAt: Long,
    val promotedAt: Long = 0L,
    val seedOrder: Int = Int.MAX_VALUE,
    val successCount: Int = 1,
    val scope: String = "default",
    val manual: Boolean = false,
    val userImported: Boolean = false,
    val qualityProbeCount: Int = 0,
    val qualityPingSuccesses: Int = 0,
    val qualityAvgPingMs: Double = 0.0,
    val qualityLastCheckedAt: Long = 0L,
    val qualityFailureCount: Int = 0,
    /**
     * Учёт перерукопожатий на живой сессии этого узла.
     *
     * Замер 2026-08-10: два узла в одной сети и на одной сборке дали 2,0–2,5 и
     * 0,5–1,0 перерукопожатия в минуту, причём спокойный нёс больше трафика.
     * Параметры AWG у всех встроенных профилей одинаковые — значит частота
     * пересборки сессии описывает путь до узла, а пинг и факт подключения её не
     * видят: узел, не удерживающий сессию, до сих пор считался хорошим.
     */
    val churnWindows: Int = 0,
    val churnRekeys: Int = 0,
    /**
     * Удержание сессии, замеренное в коротком окне адаптации.
     *
     * Отдельно от churn намеренно: у churn шкала двухминутного окна под
     * пользовательским трафиком (здоровье — одно перерукопожатие на окно), и
     * записать туда двадцатисекундный замер значило бы выдать катастрофу за
     * норму. Здесь копится тишина обратного потока в миллисекундах и рядом с
     * ней знаменатель — сколько времени замер реально шёл. Подробности и
     * физика — в [SessionHoldMetric].
     */
    val holdWindows: Int = 0,
    val holdStallMs: Long = 0L,
    val holdSpanMs: Long = 0L,
    /**
     * Собственная отметка времени замера удержания.
     *
     * Не `qualityLastCheckedAt`: та управляет свежестью пинговых слагаемых, и
     * подъём её из чужого замера тихо продлевал бы жизнь устаревшему пингу,
     * которого никто не перемерял.
     */
    val holdCheckedAt: Long = 0L,
    val preferredSni: String = "",
    val preferredPorts: List<WarpPortStat> = emptyList(),
)

class ClientData(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = context.getSharedPreferences("nova_warp_config", Context.MODE_PRIVATE)
    private val serviceStateFile = AtomicFile(File(appContext.filesDir, "service_state.json"))
    private val warpDiscoveryStateFile = AtomicFile(File(appContext.filesDir, "warp_discovery_state.json"))
    private val trafficMaskStateFile = AtomicFile(File(appContext.filesDir, "traffic_mask_state.json"))
    private val tunnelUiSnapshotFile = AtomicFile(File(appContext.filesDir, "tunnel_ui_snapshot.json"))
    private val localProxyStatusFile = AtomicFile(File(appContext.filesDir, "local_proxy_status.json"))
    private val gatewayFlagsFile = AtomicFile(File(appContext.filesDir, "gateway_flags.json"))
    private val lastExitObservationFile = AtomicFile(File(appContext.filesDir, "last_exit_observation.json"))
    private val vlessProfilesFile = AtomicFile(File(appContext.filesDir, "vless_profiles.json"))
    private val importedSourceFile = AtomicFile(File(appContext.filesDir, "imported_source.json"))

    /**
     * Ключ MASQUE. В файле, а не в настройках.
     *
     * Замер 2026-08-13: служба выпускала ключ и писала его `commit()`-ом, а следующий же
     * `commit()` из главного процесса возвращал свою кэшированную карту — со старым пустым
     * значением — и ключ исчезал. Снаружи это выглядело как «MASQUE опять потерял ключ»
     * после обычного нажатия кнопки, и сбило не один замер. Тот же капкан, что у
     * `warp_imported_only_mode` и отладочного ключа AWG; лечение то же.
     */
    private val masqueConfigFile = AtomicFile(File(appContext.filesDir, "masque_config.json"))
    /**
     * Отметка версии, с которой в прошлый раз запускалось приложение.
     *
     * Файл, а не настройки: значение читают оба процесса, а `SharedPreferences` в
     * `MODE_PRIVATE` кэшируются каждым процессом отдельно — второй процесс увидел бы
     * старое значение и повторил сброс.
     */
    private val appVersionStateFile = AtomicFile(File(appContext.filesDir, "app_version_state.json"))
    private val transportLatencyFile = AtomicFile(File(appContext.filesDir, "transport_latency.json"))
    private val vlessSubscriptionFile = AtomicFile(File(appContext.filesDir, "vless_subscription.json"))
    private val operaStateFile = AtomicFile(File(appContext.filesDir, "opera_state.json"))
    private val warpVerifiedExportFile = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        WARP_VERIFIED_EXPORT_FILE_NAME,
    )

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

    init {
        if (runtimeSeedInitDone.compareAndSet(false, true)) {
            ensureBundledVerifiedWarpSeeds()
            mergeWarpVerifiedExportSnapshotIntoPrefs()
            purgeStoredWarpPreferredSniOnce()
            pruneGeneratedBuiltInWarpConfigs()
            // compactWarpVerifiedConfigsIfNeeded()
            if (!isVpnRuntimeProcess()) {
                syncWarpVerifiedExport()
            }
        }
    }

    private fun isVpnRuntimeProcess(): Boolean {
        return runCatching {
            AndroidCompat.getProcessName(appContext).endsWith(":vpn")
        }.getOrDefault(false)
    }

    private data class BootstrapSeed(
        val config: WarpConfig,
        val lastSuccessPort: Int? = null,
        val lastSuccessProtocol: String? = null,
        val lastSuccessEndpoint: String? = null,
        val lastSuccessMode: String? = null,
    )

    private data class StrategyStats(
        val attempts: Int = 0,
        val successes: Int = 0,
        val stableSuccesses: Int = 0,
        val handshakes: Int = 0,
        val failures: Int = 0,
        val consecutiveFailures: Int = 0,
        val avgSuccessMs: Double = 0.0,
        val avgStableMs: Double = 0.0,
        val lastSuccessAt: Long = 0L,
        val lastFailureAt: Long = 0L,
        val validatedNoTrafficFailures: Int = 0,
        val controlPlaneOnlyFailures: Int = 0,
        val noInboundAfterHandshakeFailures: Int = 0,
        val noTrafficFailures: Int = 0,
        val engineCrashFailures: Int = 0,
        val underlyingLossFailures: Int = 0,
        val lastFailureReason: String = "",
    )

    private data class RegistrationRouteStats(
        val attempts: Int = 0,
        val successes: Int = 0,
        val failures: Int = 0,
        val consecutiveFailures: Int = 0,
        val avgSuccessMs: Double = 0.0,
        val lastSuccessAt: Long = 0L,
        val lastFailureAt: Long = 0L,
    )

    private data class RegistrationProfileStats(
        val attempts: Int = 0,
        val successes: Int = 0,
        val failures: Int = 0,
        val consecutiveFailures: Int = 0,
        val avgSuccessMs: Double = 0.0,
        val lastSuccessAt: Long = 0L,
        val lastFailureAt: Long = 0L,
    )

    private data class OperaLaunchPlanStats(
        val attempts: Int = 0,
        val successes: Int = 0,
        val failures: Int = 0,
        val consecutiveFailures: Int = 0,
        val avgSuccessMs: Double = 0.0,
        val lastSuccessAt: Long = 0L,
        val lastFailureAt: Long = 0L,
    )

    private data class OperaRegistrationPlanStats(
        val attempts: Int = 0,
        val successes: Int = 0,
        val failures: Int = 0,
        val consecutiveFailures: Int = 0,
        val avgSuccessMs: Double = 0.0,
        val lastSuccessAt: Long = 0L,
        val lastFailureAt: Long = 0L,
    )

    private data class ExitObservation(
        val country: String = "",
        val colo: String = "",
        val ip: String = "",
        val observedAt: Long = 0L,
    )

    fun saveConfig(config: WarpConfig) {
        val normalizedReserved = normalizeReservedValue(config.reserved)
        prefs.edit().apply {
            putString("private_key", config.privateKey)
            putString("public_key", config.publicKey)
            putString("ipv4", config.ipv4)
            putString("ipv6", config.ipv6)
            putString("peer_pub", config.peerPublicKey)
            putString("peer_endpoint", config.peerEndpoint)
            putString("reserved", normalizedReserved)
            putString("access_token", config.accessToken)
            putString("device_id", config.deviceId)
            putString("license", config.license)
            // Ключ MASQUE здесь не пишется вовсе — он живёт в отдельном файле.
            //
            // `WarpClient.parseConfig` всегда возвращает `masqueConfigJson = null`: он
            // разбирает ответ регистрации WireGuard, где ключа MASQUE нет и быть не может.
            // Пока поле писалось сюда, любая регистрация WARP молча стирала только что
            // выпущенный ключ. Хранилище у ключа одно — [saveMasqueConfigJson].
            putBoolean("is_registered", true)
            if (!config.accessToken.isNullOrBlank() && !config.deviceId.isNullOrBlank()) {
                remove("masque_bootstrap_failed_at")
            }
            commit()
        }
    }

    fun ensureBootstrapConfig(): Boolean {
        val existingConfig = loadStoredConfig()
        if (existingConfig != null && !needsBootstrapRepair(existingConfig)) return false

        val seed = loadBundledBootstrapSeed() ?: return false
        saveConfig(seed.config)

        val now = System.currentTimeMillis()
        prefs.edit().apply {
            seed.lastSuccessPort?.takeIf { it in 1..65535 }?.let {
                putInt("last_success_port", it)
            }
            seed.lastSuccessProtocol?.takeIf { it.isNotBlank() }?.let {
                putString("last_success_protocol", it)
            }
            seed.lastSuccessEndpoint?.takeIf { it.isNotBlank() }?.let {
                putString("last_success_endpoint", it)
            }
            seed.lastSuccessMode?.takeIf { it.isNotBlank() }?.let {
                putString("last_success_mode", it)
            }
            if (seed.lastSuccessPort != null && !seed.lastSuccessProtocol.isNullOrBlank()) {
                putLong("last_success_at", now)
            }
            commit()
        }
        return true
    }

    fun getConfig(): WarpConfig? {
        return loadStoredConfig()
    }

    fun resolveWarpConfigForReuse(repairWithBootstrap: Boolean = false): ResolvedWarpConfig? {
        loadStoredConfig()?.let {
            return ResolvedWarpConfig(
                config = it,
                source = "saved-config",
                persisted = true,
            )
        }
        restartSessionToWarpConfig(getRestartSession())?.let {
            return ResolvedWarpConfig(
                config = it,
                source = "restart-session",
                persisted = false,
            )
        }
        restartSessionToWarpConfig(getPendingWarpBootstrapRestart())?.let {
            return ResolvedWarpConfig(
                config = it,
                source = "pending-bootstrap-restart",
                persisted = false,
            )
        }
        if (repairWithBootstrap && ensureBootstrapConfig()) {
            loadStoredConfig()?.let {
                return ResolvedWarpConfig(
                    config = it,
                    source = "bootstrap-seed",
                    persisted = true,
                )
            }
        }
        return null
    }


    fun promoteWarpVerifiedConfig(id: String) {
        synchronized(warpVerifiedConfigsLock) {
            val configs = getWarpVerifiedConfigs().toMutableList()
            val index = configs.indexOfFirst { it.id == id }
            if (index != -1) {
                val item = configs[index]
                configs[index] = item.copy(promotedAt = System.currentTimeMillis())
                saveWarpVerifiedConfigs(configs)
            }
        }
    }

    private fun loadStoredConfig(): WarpConfig? {
        if (!prefs.getBoolean("is_registered", false)) return null

        val config = WarpConfig(
            privateKey = prefs.getString("private_key", "") ?: "",
            publicKey = prefs.getString("public_key", "") ?: "",
            ipv4 = prefs.getString("ipv4", "") ?: "",
            ipv6 = prefs.getString("ipv6", "") ?: "",
            peerPublicKey = prefs.getString("peer_pub", "") ?: "",
            peerEndpoint = prefs.getString("peer_endpoint", "") ?: "",
            reserved = normalizeReservedValue(prefs.getString("reserved", null)),
            accessToken = prefs.getString("access_token", null),
            deviceId = prefs.getString("device_id", null),
            license = prefs.getString("license", null),
            masqueConfigJson = getMasqueConfigJson()
        )
        return config.takeIf {
            it.privateKey.isNotBlank() &&
                it.publicKey.isNotBlank() &&
                it.ipv4.isNotBlank() &&
                it.ipv6.isNotBlank() &&
                it.peerPublicKey.isNotBlank() &&
                it.peerEndpoint.isNotBlank()
        }
    }

    private fun restartSessionToWarpConfig(session: RestartSession?): WarpConfig? {
        val warpSession = session?.takeIf { it.kind == "warp" } ?: return null
        val privateKey = warpSession.privateKey.orEmpty().trim()
        val ipv4 = warpSession.ipv4.orEmpty().trim()
        val ipv6 = warpSession.ipv6.orEmpty().trim()
        val peerPublicKey = warpSession.peerPublicKey.orEmpty().trim()
        val peerEndpoint = warpSession.peerEndpoint.orEmpty().trim()
        if (
            privateKey.isBlank() ||
            ipv4.isBlank() ||
            ipv6.isBlank() ||
            peerPublicKey.isBlank() ||
            peerEndpoint.isBlank()
        ) {
            return null
        }
        return WarpConfig(
            privateKey = privateKey,
            publicKey = prefs.getString("public_key", "").orEmpty(),
            ipv4 = ipv4,
            ipv6 = ipv6,
            peerPublicKey = peerPublicKey,
            peerEndpoint = peerEndpoint,
            reserved = normalizeReservedValue(warpSession.reserved),
            accessToken = prefs.getString("access_token", null),
            deviceId = prefs.getString("device_id", null),
            license = prefs.getString("license", null),
            masqueConfigJson = getMasqueConfigJson(),
        )
    }

    private fun needsBootstrapRepair(config: WarpConfig): Boolean {
        if (config.accessToken.isNullOrBlank() || config.deviceId.isNullOrBlank()) {
            return true
        }
        val endpoint = config.peerEndpoint.trim()
        val endpointPort = endpoint.substringAfterLast(':', "").toIntOrNull() ?: -1
        if (endpointPort !in 1..65535) {
            return true
        }
        val endpointHost = endpoint.substringBeforeLast(':', "").trim().removePrefix("[").removeSuffix("]")
        if (endpointHost in POISONED_ENDPOINT_HOSTS) {
            return true
        }
        return false
    }

    // Auto-Reconnect Persistence
    fun getAutoReconnect(): Boolean = prefs.getBoolean("auto_reconnect", true)
    fun setAutoReconnect(enabled: Boolean) { prefs.edit().putBoolean("auto_reconnect", enabled).commit() }
    fun getAutoAppUpdate(): Boolean = prefs.getBoolean("auto_app_update", true)
    fun setAutoAppUpdate(enabled: Boolean) { prefs.edit().putBoolean("auto_app_update", enabled).commit() }
    fun getMainBackgroundMode(): String =
        MainBackgroundPolicy.normalize(
            prefs.getString("main_background_mode", MainBackgroundPolicy.MODE_ANIMATION)
        )
    fun setMainBackgroundMode(mode: String) {
        prefs.edit()
            .putString("main_background_mode", MainBackgroundPolicy.normalize(mode))
            .commit()
    }
    fun isWarpDebugSkipFastProxyOnceEnabled(): Boolean =
        prefs.getBoolean("warp_debug_skip_fast_proxy_once", false)
    fun setWarpDebugSkipFastProxyOnceEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("warp_debug_skip_fast_proxy_once", enabled).commit()
    }
    fun consumeWarpDebugSkipFastProxyOnce(): Boolean {
        if (!isWarpDebugSkipFastProxyOnceEnabled()) return false
        prefs.edit().putBoolean("warp_debug_skip_fast_proxy_once", false).commit()
        return true
    }
    fun hasPromptedNotificationPermission(): Boolean = prefs.getBoolean("notifications_prompted", false)
    fun setPromptedNotificationPermission(prompted: Boolean) {
        prefs.edit().putBoolean("notifications_prompted", prompted).commit()
    }
    fun getQuickTileAdded(): Boolean = prefs.getBoolean("quick_tile_added", false)
    fun setQuickTileAdded(added: Boolean) { prefs.edit().putBoolean("quick_tile_added", added).commit() }
    fun getIsFirstLaunch(): Boolean = prefs.getBoolean("is_first_app_launch", true)
    fun setIsFirstLaunch(value: Boolean) { prefs.edit().putBoolean("is_first_app_launch", value).commit() }
    /**
     * Выбор источника профилей живёт в файле, а не в `SharedPreferences`.
     *
     * Ровно та же ловушка, что уже описана у профилей VLESS, но обошлась дороже:
     * экран включал «только импортированные» в основном процессе, а служба в `:vpn`
     * держала свою копию prefs и продолжала читать `false`. Хуже того, любой
     * `commit()` из `:vpn` (а `saveServiceState` зовётся на каждой смене состояния)
     * сериализует всю карту процесса целиком и **откатывал** только что включённый
     * режим обратно. Снаружи это выглядело так: пользователь выбрал свои профили,
     * а подключение пошло по встроенным — и после перезапуска приложения выбор
     * оказывался сброшенным.
     *
     * Файл перечитывается каждый раз, поэтому оба процесса всегда видят одно и то
     * же. Старая установка переносится один раз при первом чтении.
     */
    private fun readImportedSource(): JSONObject {
        readAtomicJson(importedSourceFile)?.let { return it }
        // Перенос из prefs делается ровно один раз — когда файла ещё нет.
        //
        // Если файл есть, но не прочитался (запись в этот момент, повреждение),
        // возвращаться к prefs нельзя: там лежит зеркало, которое любой `commit()`
        // из процесса `:vpn` мог откатить, — то есть ровно тот дефект, ради которого
        // настройка и переехала в файл. В таком случае честнее отдать значения по
        // умолчанию и не переписывать файл.
        if (importedSourceFile.baseFile.exists()) {
            return JSONObject().apply {
                put("only_mode", false)
                put("protocol", "auto")
            }
        }
        val migrated = JSONObject().apply {
            put("only_mode", prefs.getBoolean("warp_imported_only_mode", false))
            put(
                "protocol",
                normalizeImportedProtocolPreference(prefs.getString("imported_protocol_preference", "auto")),
            )
        }
        writeAtomicRaw(importedSourceFile, migrated.toString())
        return migrated
    }

    private fun writeImportedSource(vararg entries: Pair<String, Any>) {
        val json = readImportedSource()
        entries.forEach { (key, value) -> json.put(key, value) }
        writeAtomicRaw(importedSourceFile, json.toString())
    }

    fun isImportedWarpOnlyModeEnabled(): Boolean = readImportedSource().optBoolean("only_mode", false)
    fun setImportedWarpOnlyModeEnabled(enabled: Boolean) {
        writeImportedSource("only_mode" to enabled)
        prefs.edit().putBoolean("warp_imported_only_mode", enabled).commit()
    }
    fun isImportedConfigSourceActive(): Boolean =
        isImportedWarpOnlyModeEnabled() && getAvailableImportedProtocolFamilies().isNotEmpty()
    fun getImportedProtocolPreference(): String =
        normalizeImportedProtocolPreference(readImportedSource().optString("protocol", "auto"))
    fun setImportedProtocolPreference(value: String) {
        val normalized = normalizeImportedProtocolPreference(value)
        writeImportedSource("protocol" to normalized)
        prefs.edit().putString("imported_protocol_preference", normalized).commit()
    }

    /**
     * Какой протокол среди импортированных считать выбранным на самом деле.
     *
     * «AUTO» при единственной доступной семье — это не выбор, а отсутствие выбора:
     * пользователь импортировал подписку VLESS, экран честно подписал бэкенд
     * «VLESS», а [shouldUseVlessTransport] требовал ровно значения `vless` и
     * возвращал false. Фаза VLESS не запускалась вовсе, импортированных WARP тоже
     * не было — и служба гасла через полминуты со строкой «shortlist пуст».
     * Схлопывание жило в UI ([MainActivity.resolveImportedUiBackendLabel]), поэтому
     * экран и служба расходились в том, что выбрано.
     *
     * Несуществующая семья («AWG» после того, как все AWG-профили удалены) тоже
     * откатывается сюда: иначе выбор молча отсекает весь список.
     */
    fun resolveEffectiveImportedProtocol(): String = ImportedSourcePolicy.resolveEffectiveProtocol(
        preference = getImportedProtocolPreference(),
        availableFamilies = getAvailableImportedProtocolFamilies(),
    )
    /**
     * Настройки раздачи живут ещё и в файле, а не только в SharedPreferences.
     *
     * Экран раздачи работает в основном процессе, а NovaVpnService — в отдельном
     * `:vpn`. SharedPreferences в MODE_PRIVATE кешируются каждым процессом
     * независимо и не перечитываются, поэтому служба не увидела бы включение
     * раздачи до полного перезапуска приложения. Файл читается заново каждый раз,
     * так что оба процесса всегда видят одно и то же.
     */
    private fun readGatewayFlags(): JSONObject? = readAtomicJson(gatewayFlagsFile)

    private fun writeGatewayFlag(vararg entries: Pair<String, Any>) {
        val json = readGatewayFlags() ?: JSONObject()
        entries.forEach { (key, value) -> json.put(key, value) }
        writeAtomicRaw(gatewayFlagsFile, json.toString())
    }

    fun isLocalProxyEnabled(): Boolean {
        readGatewayFlags()?.let { if (it.has("enabled")) return it.optBoolean("enabled", false) }
        return prefs.getBoolean("local_proxy_enabled", false)
    }
    fun setLocalProxyEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("local_proxy_enabled", enabled).commit()
        writeGatewayFlag("enabled" to enabled)
    }
    fun getLocalProxyPort(): Int = 1370
    fun getLocalProxyUsername(): String {
        readGatewayFlags()?.optString("username")?.takeIf { it.isNotBlank() }?.let { return it }
        return prefs.getString("local_proxy_username", "").orEmpty()
    }
    fun getLocalProxyPassword(): String {
        readGatewayFlags()?.optString("password")?.takeIf { it.isNotBlank() }?.let { return it }
        return prefs.getString("local_proxy_password", "").orEmpty()
    }
    fun ensureLocalProxyCredentials(): Pair<String, String> {
        val username = getLocalProxyUsername()
        val password = getLocalProxyPassword()
        if (username.length == 4 && password.length == 4) {
            return username to password
        }
        return regenerateLocalProxyCredentials()
    }
    fun regenerateLocalProxyCredentials(): Pair<String, String> {
        val username = generateLocalProxyToken()
        val password = generateLocalProxyToken()
        prefs.edit()
            .putString("local_proxy_username", username)
            .putString("local_proxy_password", password)
            .commit()
        writeGatewayFlag("username" to username, "password" to password)
        return username to password
    }
    fun getLocalProxyUnauthorizedCount(): Int = prefs.getInt("local_proxy_unauthorized_count", 0).coerceAtLeast(0)
    fun appendLocalProxyUnauthorizedAttempt(
        protocol: String,
        packageName: String?,
        appLabel: String?,
        reason: String,
        observedAt: Long = System.currentTimeMillis(),
    ) {
        val current = getLocalProxyUnauthorizedAttempts(limit = 24).toMutableList()
        current.add(
            0,
            LocalProxyUnauthorizedAttempt(
                observedAt = observedAt,
                protocol = protocol.trim().uppercase(Locale.US),
                packageName = packageName?.trim().orEmpty(),
                appLabel = appLabel?.trim().orEmpty(),
                reason = reason.trim(),
            )
        )
        val payload = JSONArray().apply {
            current.take(24).forEach { item ->
                put(
                    JSONObject().apply {
                        put("observed_at", item.observedAt)
                        put("protocol", item.protocol)
                        put("package_name", item.packageName)
                        put("app_label", item.appLabel)
                        put("reason", item.reason)
                    }
                )
            }
        }
        prefs.edit()
            .putInt("local_proxy_unauthorized_count", getLocalProxyUnauthorizedCount() + 1)
            .putString("local_proxy_unauthorized_attempts_json", payload.toString())
            .commit()
    }
    fun clearLocalProxyUnauthorizedHistory() {
        prefs.edit()
            .remove("local_proxy_unauthorized_count")
            .remove("local_proxy_unauthorized_attempts_json")
            .commit()
    }
    fun getLocalProxyUnauthorizedAttempts(limit: Int = 24): List<LocalProxyUnauthorizedAttempt> {
        val raw = prefs.getString("local_proxy_unauthorized_attempts_json", null)?.trim().orEmpty()
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    add(
                        LocalProxyUnauthorizedAttempt(
                            observedAt = item.optLong("observed_at", 0L),
                            protocol = item.optString("protocol").trim(),
                            packageName = item.optString("package_name").trim(),
                            appLabel = item.optString("app_label").trim(),
                            reason = item.optString("reason").trim(),
                        )
                    )
                }
            }.take(limit.coerceAtLeast(1))
        } catch (_: Exception) {
            emptyList()
        }
    }
    fun saveLocalProxyStatus(
        running: Boolean,
        backend: String,
        host: String = "127.0.0.1",
        port: Int = getLocalProxyPort(),
        observedAt: Long = System.currentTimeMillis(),
        endpoints: List<GatewayEndpoint> = emptyList(),
    ) {
        val raw = JSONObject().apply {
            put("enabled", isLocalProxyEnabled())
            put("running", running)
            put("backend", backend.trim())
            put("host", host.trim().ifBlank { "127.0.0.1" })
            put("port", port)
            put("observed_at", observedAt)
            put(
                "endpoints",
                JSONArray().apply {
                    endpoints.forEach { endpoint ->
                        put(
                            JSONObject().apply {
                                put("host", endpoint.host)
                                put("iface", endpoint.interfaceName)
                                put("kind", endpoint.kind.name)
                            }
                        )
                    }
                }
            )
        }.toString()
        prefs.edit()
            .putString("local_proxy_status_json", raw)
            .commit()
        writeAtomicRaw(localProxyStatusFile, raw)
    }
    fun getLocalProxyStatusSnapshot(): LocalProxyStatusSnapshot? {
        val raw = readAtomicRaw(localProxyStatusFile).takeIf { it.isNotBlank() }
            ?: prefs.getString("local_proxy_status_json", null)?.trim().orEmpty()
        if (raw.isBlank()) {
            return LocalProxyStatusSnapshot(
                enabled = isLocalProxyEnabled(),
                running = false,
                backend = getServiceBackend(),
                host = "127.0.0.1",
                port = getLocalProxyPort(),
                observedAt = 0L,
            )
        }
        return try {
            val json = JSONObject(raw)
            LocalProxyStatusSnapshot(
                enabled = json.optBoolean("enabled", isLocalProxyEnabled()),
                running = json.optBoolean("running", false),
                backend = json.optString("backend").trim(),
                host = json.optString("host").trim().ifBlank { "127.0.0.1" },
                port = json.optInt("port", getLocalProxyPort()).coerceIn(1, 65535),
                observedAt = json.optLong("observed_at", 0L),
                endpoints = parseGatewayEndpoints(json.optJSONArray("endpoints")),
            )
        } catch (_: Exception) {
            null
        }
    }
    private fun parseGatewayEndpoints(array: JSONArray?): List<GatewayEndpoint> {
        if (array == null) return emptyList()
        val parsed = mutableListOf<GatewayEndpoint>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val host = item.optString("host").trim()
            if (host.isBlank()) continue
            val kind = runCatching { GatewayKind.valueOf(item.optString("kind")) }
                .getOrDefault(GatewayKind.OTHER_TETHER)
            parsed += GatewayEndpoint(
                host = host,
                interfaceName = item.optString("iface").trim(),
                kind = kind,
            )
        }
        return parsed
    }

    /**
     * Пропускать авторизацию для клиентов, пришедших через раздачу самого телефона.
     * Нужно для телевизоров и приставок, где в настройках прокси нет полей логина
     * и пароля. На общую сеть Wi-Fi послабление не распространяется никогда.
     */
    fun isGatewayOpenForTethered(): Boolean {
        readGatewayFlags()?.let { if (it.has("open_for_tethered")) return it.optBoolean("open_for_tethered", false) }
        return prefs.getBoolean("gateway_open_for_tethered", false)
    }
    fun setGatewayOpenForTethered(enabled: Boolean) {
        prefs.edit().putBoolean("gateway_open_for_tethered", enabled).commit()
        writeGatewayFlag("open_for_tethered" to enabled)
    }
    /**
     * Выпускать трафик клиентов напрямую, когда туннель недоступен.
     *
     * По умолчанию выключено, и это осознанно: клиент подключился к раздаче именно
     * ради VPN, и молча выпустить его мимо туннеля — это не «связь продолжила
     * работать», а утечка, которую он не заметит. Пока туннеля нет, шлюз остаётся
     * доступен и честно отвечает ошибкой, а как только туннель поднимется, всё
     * заработает само.
     */
    fun isGatewayAllowDirectWithoutVpn(): Boolean {
        readGatewayFlags()?.let { if (it.has("allow_direct")) return it.optBoolean("allow_direct", false) }
        return prefs.getBoolean("gateway_allow_direct_without_vpn", false)
    }
    fun setGatewayAllowDirectWithoutVpn(enabled: Boolean) {
        prefs.edit().putBoolean("gateway_allow_direct_without_vpn", enabled).commit()
        writeGatewayFlag("allow_direct" to enabled)
    }
    /**
     * Короткое пояснение для интерфейса, когда работает не тот транспорт, который
     * выбрал пользователь. Пустая строка — сказать нечего, всё идёт как выбрано.
     *
     * Пишет сервис из процесса `:vpn`, читает интерфейс из главного процесса,
     * поэтому ведущим хранилищем служит файл состояния: SharedPreferences
     * кэшируются на процесс и чужую запись не показывают.
     */
    /**
     * Заметка службы о том, что работает не выбранный транспорт.
     *
     * Файл состояния главнее prefs, даже когда заметка в нём пустая: пустая строка —
     * это «служба сняла заметку», а не «данных нет». Пока пустое значение уходило в
     * `ifBlank`, снятая заметка подменялась копией из prefs своего процесса, и на
     * подключённом туннеле висело «Профиль 2/151 не ответил — берём следующий».
     */
    fun getLastTransportNotice(): String {
        val serviceState = readServiceStateFile()
        if (serviceState.has("notice")) return serviceState.optString("notice").trim()
        return prefs.getString("last_transport_notice", "").orEmpty().trim()
    }
    fun setLastTransportNotice(value: String) {
        prefs.edit().putString("last_transport_notice", value.trim()).commit()
    }
    fun getGatewayPortalPort(): Int = 1371
    fun saveServiceState(
        state: String,
        /**
         * `null` означает «нет новостей», как и у `transport`: остаётся тот бэкенд,
         * который уже записан.
         *
         * Раньше по умолчанию стоял `BACKEND_WARP`, и любая публикация без бэкенда
         * перекрашивала сессию в WARP — в том числе остановка из главного экрана и
         * из плитки. Проверено на устройстве: после неудачного перебора своих
         * профилей VLESS в `service_state.json` оставалось `backend=WARP`, и экран
         * подписывал происходящее встроенным профилем. Это и читалось как «Nova
         * сама переключается на встроенные».
         */
        backend: String? = null,
        attemptOrdinal: Int = 0,
        attemptTotal: Int = 0,
        transport: String = "",
        notice: String = "",
        /**
         * `null` означает «нет новостей», а не «регистрации нет».
         *
         * `saveServiceState` зовут из десятков мест по любому поводу — от смены
         * счётчика попыток до перечитывания настроек. Если бы отсутствие
         * параметра означало `false`, ступень регистрации гасла бы на первой же
         * посторонней публикации. На поле `transport` этот дефект уже был
         * пойман на устройстве, и повторять его здесь незачем.
         */
        registering: Boolean? = null,
    ) {
        val normalizedState = state.ifBlank { NovaVpnService.STATE_STOPPED }
        val normalizedBackend = backend?.trim()?.takeIf { it.isNotEmpty() }
            ?: readServiceStateFile().optString("backend").trim().takeIf { it.isNotEmpty() }
            ?: NovaVpnService.BACKEND_WARP
        // Пустой транспорт на живом туннеле означает «служба ничего не сообщила»,
        // а не «это WARP», и затирать им уже известный нельзя.
        //
        // Метка живёт в поле службы и обнуляется на старте каждого поколения
        // подключения (`beginConnectGeneration`). Пока туннель поднят, поколения
        // сменяются и без переподключения — от проверок и перечитывания настроек, —
        // и любая такая публикация делала `transport` пустым. Экран тогда падал в
        // общую ветку и писал «WARP: RU» поверх работающего MASQUE: замер на
        // Pixel 4 XL, LTE, туннель через MASQUE-CONSUMER@443 —
        // `backend=WARP, transport=-`.
        val requestedTransport = transport.trim().uppercase(Locale.US)
        val normalizedTransport = if (
            requestedTransport.isBlank() && normalizedState == NovaVpnService.STATE_CONNECTED
        ) {
            readServiceStateFile().optString("transport").trim().uppercase(Locale.US)
        } else {
            requestedTransport
        }
        val normalizedNotice = notice.trim()
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString("service_state", normalizedState)
            .putString("service_backend", normalizedBackend)
            .putString("service_transport", normalizedTransport)
            .putString("last_transport_notice", normalizedNotice)
            .putInt("service_attempt_ordinal", attemptOrdinal.coerceAtLeast(0))
            .putInt("service_attempt_total", attemptTotal.coerceAtLeast(0))
            .putLong("service_state_updated_at", now)
            .apply {
                if (normalizedState == NovaVpnService.STATE_STOPPED) {
                    remove("traffic_mask_recent_probe_host")
                    remove("traffic_mask_recent_probe_pool")
                }
            }
            .commit()
        if (normalizedState == NovaVpnService.STATE_STOPPED) {
            clearTrafficMaskStateFile()
        }
        if (normalizedState == NovaVpnService.STATE_CONNECTED) {
            clearTransientConnectingPending()
        }
        // Остановленная служба никого не регистрирует: здесь флаг снимается сам,
        // иначе зависший `true` пережил бы отключение и запер бы настройки.
        val normalizedRegistering = when {
            normalizedState == NovaVpnService.STATE_STOPPED -> false
            registering != null -> registering
            else -> readServiceStateFile().optBoolean("registering", false)
        }
        writeServiceStateFile(
            normalizedState,
            normalizedBackend,
            attemptOrdinal,
            attemptTotal,
            normalizedTransport,
            normalizedNotice,
            normalizedRegistering,
        )
    }

    /**
     * Идёт ли прямо сейчас регистрация устройства в Cloudflare.
     *
     * Ключ MASQUE выдаётся только изнутри поднятого туннеля, поэтому при первом
     * выборе MASQUE цикл поднимает WARP как ступень регистрации. Снаружи это
     * выглядело как обычное подключение, которое почему-то полминуты стоит с
     * пустым пингом: пользователь успевал решить, что всё сломалось, и нажать
     * отключение или сменить протокол — то есть уронить ровно тот туннель, через
     * который выдаётся ключ.
     *
     * Флаг живёт в файле, а не в `SharedPreferences`: состояние пишет процесс
     * `:vpn`, а читает UI-процесс, и `commit()` любого из них откатывает карту
     * другого (GOTCHA про кросс-процессный откат).
     */
    fun isDeviceRegistrationInProgress(): Boolean =
        readServiceStateFile().optBoolean("registering", false)

    /**
     * Транспорт, который на самом деле несёт туннель прямо сейчас: `MASQUE`,
     * `WARP` или пустая строка, если сервис ещё ничего не сообщил.
     *
     * Отдельно от `backend` потому, что MASQUE работает внутри WARP-бэкенда:
     * по одному лишь бэкенду невозможно отличить выбранный протокол от того,
     * на который приложение ушло по fallback.
     */
    fun getServiceTransport(): String =
        readServiceStateFile().optString("transport").ifBlank {
            prefs.getString("service_transport", "").orEmpty()
        }.trim().uppercase(Locale.US)
    fun getServiceState(): String =
        readServiceStateFile().optString("state").ifBlank {
            prefs.getString("service_state", NovaVpnService.STATE_STOPPED)
                ?: NovaVpnService.STATE_STOPPED
        }
    fun getServiceBackend(): String =
        readServiceStateFile().optString("backend").ifBlank {
            prefs.getString("service_backend", NovaVpnService.BACKEND_WARP)
                ?: NovaVpnService.BACKEND_WARP
        }
    fun getServiceAttemptOrdinal(): Int =
        readServiceStateFile().optInt("attempt_ordinal", -1).takeIf { it >= 0 }
            ?: prefs.getInt("service_attempt_ordinal", 0)
    fun getServiceAttemptTotal(): Int =
        readServiceStateFile().optInt("attempt_total", -1).takeIf { it >= 0 }
            ?: prefs.getInt("service_attempt_total", 0)
    // Кэш числа попыток удалён намеренно, восстанавливать не нужно.
    //
    // Он лежал в SharedPreferences и писался только процессом `:vpn`, а читался в
    // том числе процессом UI — который в MODE_PRIVATE чужие записи не перечитывает
    // никогда. Знаменатель прошлого прогона поэтому замерзал в экране до полного
    // перезапуска приложения. Вдобавок ключ был общим для встроенного и
    // импортированного наборов, а обе стороны брали `maxOf(своё, кэш)` — то есть
    // работал храповик: попавшие в кэш полсотни встроенных профилей уже не
    // опускались до четырёх импортированных. Длину перебора знает только цикл,
    // который его ведёт, и он объявляет её до первой попытки; до этого момента
    // экран показывает «...».
    // Ручной порядок конфигураций: пока пуст, список сортируется по качеству.
    // Первое же перемещение фиксирует текущий порядок целиком, дальше он ведущий,
    // а новые конфигурации дописываются в конец.
    private fun manualOrderKey(importedOnly: Boolean): String =
        if (importedOnly) "warp_manual_order_imported" else "warp_manual_order_builtin"

    fun getWarpManualOrder(importedOnly: Boolean): List<String> {
        val raw = prefs.getString(manualOrderKey(importedOnly), null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun setWarpManualOrder(importedOnly: Boolean, ids: List<String>) {
        prefs.edit()
            .putString(manualOrderKey(importedOnly), ids.filter { it.isNotBlank() }.joinToString("\n"))
            .apply()
    }

    fun clearWarpManualOrder(importedOnly: Boolean) {
        prefs.edit().remove(manualOrderKey(importedOnly)).apply()
    }

    fun getAutostartEnabledHint(): Boolean =
        prefs.getBoolean("autostart_enabled_hint", false)
    fun setAutostartEnabledHint(enabled: Boolean) {
        prefs.edit().putBoolean("autostart_enabled_hint", enabled).apply()
    }
    fun getServiceStateUpdatedAt(): Long =
        readServiceStateFile().optLong("updated_at", -1L).takeIf { it > 0L }
            ?: prefs.getLong("service_state_updated_at", 0L)
    fun markSoftReapplyPending(durationMs: Long = 15000L) {
        val until = System.currentTimeMillis() + durationMs.coerceAtLeast(1000L)
        prefs.edit().putLong("soft_reapply_pending_until", until).commit()
    }
    fun clearSoftReapplyPending() {
        prefs.edit().remove("soft_reapply_pending_until").commit()
    }
    fun isSoftReapplyPending(): Boolean {
        val until = prefs.getLong("soft_reapply_pending_until", 0L)
        if (until <= 0L) return false
        return until > System.currentTimeMillis()
    }
    fun markTransientConnectingPending(durationMs: Long = 8000L) {
        val until = System.currentTimeMillis() + durationMs.coerceAtLeast(1000L)
        prefs.edit().putLong("transient_connecting_pending_until", until).commit()
    }
    fun clearTransientConnectingPending() {
        prefs.edit().remove("transient_connecting_pending_until").commit()
    }
    fun isTransientConnectingPending(): Boolean {
        val until = prefs.getLong("transient_connecting_pending_until", 0L)
        if (until <= 0L) return false
        return until > System.currentTimeMillis()
    }
    fun cacheRestrictedMobileStatus(
        networkId: String,
        detected: Boolean,
        checkedAtMs: Long = System.currentTimeMillis(),
    ) {
        prefs.edit()
            .putString("restricted_mobile_network_id", networkId)
            .putBoolean("restricted_mobile_detected", detected)
            .putLong("restricted_mobile_checked_at", checkedAtMs)
            .commit()
    }
    fun getCachedRestrictedMobileStatus(
        networkId: String,
        freshnessMs: Long = 30_000L,
    ): Boolean? {
        val cachedNetworkId = prefs.getString("restricted_mobile_network_id", null)?.trim().orEmpty()
        if (cachedNetworkId.isBlank() || cachedNetworkId != networkId) return null
        val checkedAt = prefs.getLong("restricted_mobile_checked_at", 0L)
        if (checkedAt <= 0L) return null
        val ageMs = (System.currentTimeMillis() - checkedAt).coerceAtLeast(0L)
        if (ageMs > freshnessMs.coerceAtLeast(1_000L)) return null
        return prefs.getBoolean("restricted_mobile_detected", false)
    }
    fun getLatestRestrictedMobileStatus(freshnessMs: Long = 30_000L): Boolean? {
        val checkedAt = prefs.getLong("restricted_mobile_checked_at", 0L)
        if (checkedAt <= 0L) return null
        val ageMs = (System.currentTimeMillis() - checkedAt).coerceAtLeast(0L)
        if (ageMs > freshnessMs.coerceAtLeast(1_000L)) return null
        return prefs.getBoolean("restricted_mobile_detected", false)
    }
    private fun encodeRestartSession(session: RestartSession): String {
        val normalizedReserved = normalizeReservedValue(session.reserved)
        return JSONObject().apply {
            put("kind", session.kind)
            put("region", normalizeRegionPreference(session.region))
            put("private_key", session.privateKey.orEmpty())
            put("ipv4", session.ipv4.orEmpty())
            put("ipv6", session.ipv6.orEmpty())
            put("peer_public_key", session.peerPublicKey.orEmpty())
            put("peer_endpoint", session.peerEndpoint.orEmpty())
            put("reserved", normalizedReserved ?: JSONObject.NULL)
            put("saved_port", session.savedPort ?: JSONObject.NULL)
            put("saved_proto", session.savedProto ?: JSONObject.NULL)
        }.toString()
    }

    private fun decodeRestartSession(raw: String?): RestartSession? {
        val normalizedRaw = raw?.trim().orEmpty()
        if (normalizedRaw.isBlank()) return null
        return try {
            val json = JSONObject(normalizedRaw)
            val kind = json.optString("kind").trim().lowercase(Locale.US)
            val region = normalizeRegionPreference(json.optString("region"))
            RestartSession(
                kind = kind,
                region = region,
                privateKey = json.optString("private_key").ifBlank { null },
                ipv4 = json.optString("ipv4").ifBlank { null },
                ipv6 = json.optString("ipv6").ifBlank { null },
                peerPublicKey = json.optString("peer_public_key").ifBlank { null },
                peerEndpoint = json.optString("peer_endpoint").ifBlank { null },
                reserved = normalizeReservedValue(json.optString("reserved").ifBlank { null }),
                savedPort = json.optInt("saved_port", -1).takeIf { it in 1..65535 },
                savedProto = json.optString("saved_proto").ifBlank { null },
            ).takeIf { session ->
                when (session.kind) {
                    "opera" -> true
                    "warp" -> !session.privateKey.isNullOrBlank() &&
                        !session.ipv4.isNullOrBlank() &&
                        !session.ipv6.isNullOrBlank() &&
                        !session.peerPublicKey.isNullOrBlank() &&
                        !session.peerEndpoint.isNullOrBlank()
                    else -> false
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveRestartSession(session: RestartSession?) {
        prefs.edit().apply {
            if (session == null) {
                remove("restart_session_json")
            } else {
                putString("restart_session_json", encodeRestartSession(session))
            }
            commit()
        }
    }

    fun getRestartSession(): RestartSession? {
        return decodeRestartSession(prefs.getString("restart_session_json", null))
    }

    fun savePendingWarpBootstrapRestart(
        session: RestartSession?,
        durationMs: Long = 30_000L,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        prefs.edit().apply {
            if (session == null) {
                remove("pending_warp_bootstrap_restart_json")
                remove("pending_warp_bootstrap_restart_until")
            } else {
                putString("pending_warp_bootstrap_restart_json", encodeRestartSession(session))
                putLong(
                    "pending_warp_bootstrap_restart_until",
                    nowMs + durationMs.coerceAtLeast(5_000L),
                )
            }
            commit()
        }
    }

    fun getPendingWarpBootstrapRestart(nowMs: Long = System.currentTimeMillis()): RestartSession? {
        val until = prefs.getLong("pending_warp_bootstrap_restart_until", 0L)
        if (until <= nowMs) {
            clearPendingWarpBootstrapRestart()
            return null
        }
        return decodeRestartSession(prefs.getString("pending_warp_bootstrap_restart_json", null))
            ?.takeIf { it.kind == "warp" }
    }

    fun consumePendingWarpBootstrapRestart(nowMs: Long = System.currentTimeMillis()): RestartSession? {
        val pending = getPendingWarpBootstrapRestart(nowMs)
        clearPendingWarpBootstrapRestart()
        return pending
    }

    fun clearPendingWarpBootstrapRestart() {
        prefs.edit()
            .remove("pending_warp_bootstrap_restart_json")
            .remove("pending_warp_bootstrap_restart_until")
            .commit()
    }
    fun clearRestartSession() {
        saveRestartSession(null)
    }
    fun getExitRegionPreference(): String = prefs.getString("exit_region_preference", "auto") ?: "auto"
    fun setExitRegionPreference(value: String) {
        prefs.edit().putString("exit_region_preference", normalizeRegionPreference(value)).commit()
    }
    fun shouldUseWarpTransport(): Boolean {
        // Источник профилей старше региона. Регион EU/US мог остаться от прошлого
        // выбора — экран настроек в режиме импортированных показывает не регионы, а
        // протоколы, и сменить его пользователю физически нечем. Пока решение
        // принималось по одному региону, включённый режим «только импортированные»
        // отменялся целиком: подключение уходило в Opera EU, и ни одна
        // импортированная попытка не выполнялась.
        if (isImportedConfigSourceActive()) return true
        return when (getExitRegionPreference()) {
            "eu", "us" -> false
            else -> true
        }
    }

    /**
     * Профили VLESS и выбранная ссылка лежат в файле, а не в `SharedPreferences`.
     *
     * Служба живёт в отдельном процессе `:vpn`, а `SharedPreferences` межпроцессными
     * не бывают: каждый процесс держит свою копию в памяти и чужих записей не видит.
     * Пока список лежал в prefs, экран показывал порядок на момент импорта, а перебор
     * в службе — свой, уже переставленный. Кнопка «следующий профиль» из-за этого
     * выбирала запись по устаревшему списку и возвращала перебор к отвергнутым узлам.
     *
     * Формат — тот же JSON, что лежал в prefs, поэтому старая установка переносится
     * один раз при первом чтении и ничего не теряет.
     */
    private fun readVlessStore(): JSONObject {
        readAtomicJson(vlessProfilesFile)?.let { return it }
        val migrated = JSONObject().apply {
            put("links", prefs.getString("vless_profile_links", "").orEmpty())
            put("active", prefs.getString("vless_config_link", "").orEmpty())
        }
        writeAtomicRaw(vlessProfilesFile, migrated.toString())
        return migrated
    }

    private fun writeVlessStore(links: List<String>, activeLink: String) {
        writeAtomicRaw(
            vlessProfilesFile,
            JSONObject().apply {
                put("links", JSONArray(links).toString())
                put("active", activeLink)
            }.toString(),
        )
    }

    /** Ссылка `vless://` выбранного профиля. */
    fun getVlessConfigLink(): String = readVlessStore().optString("active").trim()

    fun setVlessConfigLink(value: String?) {
        val normalized = value?.trim().orEmpty()
        if (normalized == getVlessConfigLink()) return
        writeVlessStore(getVlessProfileLinks(), normalized)
    }

    /**
     * Импортированные профили VLESS — список ссылок в текущем порядке перебора.
     *
     * Порядок ведущий: неудачные записи уезжают вниз, и следующий цикл начинает с тех,
     * кто ещё не подводил. Объём ограничен [MAX_VLESS_PROFILES] — подписки бывают на
     * тысячи записей, а под такой объём нужен Room (см. docs/vless-reality-plan.md).
     */
    fun getVlessProfileLinks(): List<String> {
        val raw = readVlessStore().optString("links")
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    array.optString(i).trim().takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    /** Добавляет ссылки, пропуская дубликаты. Возвращает число реально добавленных. */
    /** @return ссылки, которых в списке ещё не было и которые действительно добавлены. */
    fun addVlessProfileLinks(links: List<String>): List<String> {
        if (links.isEmpty()) return emptyList()
        val existing = getVlessProfileLinks()
        // Ключ — набор параметров профиля, а не сама строка: провайдеры
        // перенумеровывают узлы в имени при каждой публикации, и по строке одна и та
        // же запись выглядела бы новой.
        val seen = existing.mapNotNullTo(HashSet()) { VlessConfig.parse(it)?.identity }
        val merged = existing.toMutableList()
        val added = mutableListOf<String>()
        for (link in links) {
            if (merged.size >= MAX_VLESS_PROFILES) break
            val identity = VlessConfig.parse(link)?.identity ?: continue
            if (!seen.add(identity)) continue
            val normalizedLink = link.trim()
            merged += normalizedLink
            added += normalizedLink
        }
        if (added.isEmpty()) return emptyList()
        val activeLink = getVlessConfigLink().ifBlank { merged.firstOrNull().orEmpty() }
        writeVlessStore(merged, activeLink)
        return added
    }

    /**
     * С какого места списка начинать перебор: с активной записи, иначе с начала.
     *
     * Перебор идёт по самому списку, поэтому номер профиля на экране — это его место
     * в списке, а не в перестановке. Начинать всегда с первой строки нельзя: там
     * обычно давно протухшая запись подписки, и после каждого разрыва перебор снова
     * упирался бы в неё.
     */
    fun getVlessRotationStartIndex(): Int = ProfileRotation.startIndex(
        getVlessProfileLinks(),
        getVlessConfigLink().takeIf { it.isNotBlank() },
    )

    /**
     * Поднимает удачный профиль в начало списка.
     *
     * Ради этого перебор и переставляет список: рабочий узел становится первым,
     * следующий за ним — вторым, и «следующий профиль» уводит вниз от рабочего, а не
     * в непроверенную часть списка. Возвращает true, если порядок изменился.
     */
    fun promoteVlessProfileLink(link: String): Boolean {
        val normalized = link.trim()
        if (normalized.isEmpty()) return false
        val existing = getVlessProfileLinks()
        val promoted = ProfileRotation.promote(existing, normalized)
        if (promoted === existing) return false
        writeVlessStore(promoted, getVlessConfigLink())
        return true
    }

    /**
     * Уводит вниз пачку отвергнутых профилей, сохраняя их относительный порядок.
     *
     * Это и есть наказание за отказ: следующее подключение начнёт с тех, кто ещё не
     * подводил, а мёртвая запись подписки будет пробоваться последней. Возвращает
     * true, если порядок действительно изменился.
     *
     * Пачкой, а не по одному: перебор откладывает наказание до конца прохода, иначе
     * номер текущего профиля в списке всё время оставался бы первым — каждая
     * отвергнутая запись подтягивала бы следующую на её место.
     */
    fun demoteVlessProfileLinks(links: List<String>): Boolean {
        if (links.isEmpty()) return false
        var current = getVlessProfileLinks()
        var moved = false
        for (link in links) {
            val normalized = link.trim()
            if (normalized.isEmpty()) continue
            val demoted = ProfileRotation.demote(current, normalized)
            if (demoted === current) continue
            current = demoted
            moved = true
        }
        if (!moved) return false
        writeVlessStore(current, getVlessConfigLink())
        return true
    }

    fun getVlessSubscription(): VlessSubscriptionState? {
        val json = readAtomicJson(vlessSubscriptionFile) ?: return null
        val url = json.optString("url").trim()
        if (url.isBlank()) return null
        val identities = runCatching {
            val array = JSONArray(json.optString("known_identities"))
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.isNotEmpty() }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
        return VlessSubscriptionState(
            url = url,
            title = json.optString("title"),
            etag = json.optString("etag"),
            lastModified = json.optString("last_modified"),
            updateIntervalHours = json.optInt("update_interval_hours", 0),
            lastCheckedAt = json.optLong("last_checked_at", 0L),
            lastChangedAt = json.optLong("last_changed_at", 0L),
            lastStatus = json.optString("last_status"),
            knownIdentities = identities,
        )
    }

    fun saveVlessSubscription(state: VlessSubscriptionState) {
        writeAtomicRaw(
            vlessSubscriptionFile,
            JSONObject().apply {
                put("url", state.url.trim())
                put("title", state.title.trim())
                put("etag", state.etag)
                put("last_modified", state.lastModified)
                put("update_interval_hours", state.updateIntervalHours.coerceAtLeast(0))
                put("last_checked_at", state.lastCheckedAt)
                put("last_changed_at", state.lastChangedAt)
                put("last_status", state.lastStatus)
                put("known_identities", JSONArray(state.knownIdentities).toString())
            }.toString(),
        )
    }

    fun clearVlessSubscription() {
        writeAtomicRaw(vlessSubscriptionFile, "")
    }

    /** Что изменилось в списке профилей после загрузки подписки. */
    data class VlessSyncResult(val added: Int, val removed: Int, val total: Int)

    /** Приводит список профилей в соответствие со свежей загрузкой подписки. */
    fun syncVlessSubscriptionProfiles(
        freshLinks: List<String>,
        previousIdentities: Collection<String>,
    ): VlessSyncResult {
        val plan = VlessSubscription.planSync(
            existingLinks = getVlessProfileLinks(),
            freshLinks = freshLinks,
            previousIdentities = previousIdentities,
            limit = MAX_VLESS_PROFILES,
        )
        if (plan.changed) {
            // Активная ссылка могла оказаться среди удалённых: тогда перебор начнёт с
            // первой строки, а не с записи, которой в списке больше нет.
            val activeLink = getVlessConfigLink().takeIf { it in plan.links }
                ?: plan.links.firstOrNull().orEmpty()
            writeVlessStore(plan.links, activeLink)
        }
        return VlessSyncResult(added = plan.added, removed = plan.removed, total = plan.links.size)
    }

    /** Следующий профиль VLESS за текущим, с закольцовыванием. */
    fun nextVlessProfileLink(current: String? = getVlessConfigLink()): String? =
        ProfileRotation.next(getVlessProfileLinks(), current?.trim().orEmpty())

    /**
     * Импортированные профили VLESS в виде карточек списка конфигураций.
     *
     * Хранятся они отдельно от WARP/AWG, но показывать их надо в том же списке
     * импортированных — иначе после импорта подписки пользователь видит «Сохранено: 0»
     * и не понимает, куда делся 151 профиль. В перебор WARP эти записи не попадают:
     * список для подключения строится из `getWarpVerifiedConfigs`, а не отсюда.
     */
    fun getVlessProfilesAsConfigs(): List<WarpVerifiedConfig> {
        return getVlessProfileLinks().mapIndexedNotNull { index, link ->
            val config = VlessConfig.parse(link) ?: return@mapIndexedNotNull null
            WarpVerifiedConfig(
                id = "$VLESS_CONFIG_ID_PREFIX${config.identity}",
                engine = "vless",
                // Протокол в названии режима: в списке импортированных рядом стоят
                // профили разных протоколов, и «WS» само по себе не говорит, чей он.
                mode = "VLESS-${config.network.ifBlank { "tcp" }.uppercase(Locale.US)}",
                host = config.host,
                port = config.port,
                endpointSource = config.displayName.ifBlank { "vless" },
                rawConfig = link,
                createdAt = 0L,
                lastVerifiedAt = 0L,
                seedOrder = index,
                successCount = 0,
                // Пометка «ручная» здесь означала бы «выбранный сейчас профиль», а
                // экран прячет ручные записи из обоих списков — и встроенных, и
                // импортированных. Активный профиль из-за этого пропадал совсем:
                // импортируешь одну ссылку в пустой список, она сразу становится
                // активной и никуда не попадает. Снаружи — «импорт не сработал».
                manual = false,
                userImported = true,
            )
        }
    }

    fun clearVlessProfileLinks() {
        writeVlessStore(emptyList(), "")
        prefs.edit()
            .remove("vless_profile_links")
            .remove("vless_config_link")
            .commit()
    }

    /**
     * Удаляет карточку из списка конфигураций — там, где она на самом деле лежит.
     *
     * Дефект, ради которого это появилось: кнопка удаления звала только
     * [removeWarpVerifiedConfig], а профили VLESS в хранилище WARP не лежат — они
     * собираются на лету из `vless_profiles.json` с идентификатором `vless|…`.
     * Фильтр по такому id не находил ничего, удалять было нечего, и после
     * перерисовки профиль возвращался на место. Снаружи это выглядело так, будто
     * удаление не сработало вовсе: счётчик оставался прежним.
     *
     * @return true, если запись действительно исчезла.
     */
    fun removeImportedConfig(id: String): Boolean {
        val normalized = id.trim()
        if (normalized.isEmpty()) return false
        if (normalized.startsWith(VLESS_CONFIG_ID_PREFIX)) {
            return removeVlessProfileByIdentity(normalized.removePrefix(VLESS_CONFIG_ID_PREFIX))
        }
        val before = getWarpVerifiedConfigs().map { it.id }.toSet()
        if (normalized !in before) return false
        removeWarpVerifiedConfig(normalized)
        return true
    }

    /** Убирает профиль VLESS по его identity и, если нужно, переносит активную ссылку. */
    fun removeVlessProfileByIdentity(identity: String): Boolean {
        val target = identity.trim()
        if (target.isEmpty()) return false
        val removal = ProfileRotation.remove(
            items = getVlessProfileLinks(),
            active = getVlessConfigLink().takeIf { it.isNotBlank() },
        ) { link -> VlessConfig.parse(link)?.identity == target } ?: return false
        writeVlessStore(removal.items, removal.active.orEmpty())
        return true
    }

    /** Сколько всего конфигураций пользователь принёс сам: импортированные плюс VLESS. */
    fun countImportedConfigs(): Int =
        getWarpVerifiedConfigs().count { it.userImported } + getVlessProfileLinks().size

    /**
     * Стирает всё, что пользователь импортировал сам: конфигурации WARP/AWG с
     * пометкой `userImported` и все профили VLESS. Встроенные профили не трогает —
     * без них не останется вообще ничего, чем подключаться.
     *
     * @return сколько записей удалено.
     */
    fun clearImportedConfigs(): Int {
        synchronized(warpVerifiedConfigsLock) {
            val configs = getWarpVerifiedConfigs()
            val importedIds = configs.filter { it.userImported }.mapTo(mutableSetOf()) { it.id }
            if (importedIds.isNotEmpty()) {
                saveWarpVerifiedConfigs(configs.filterNot { it.id in importedIds })
            }
            val vlessCount = getVlessProfileLinks().size
            if (vlessCount > 0) {
                clearVlessProfileLinks()
            }
            return importedIds.size + vlessCount
        }
    }

    /** true, если выбран VLESS и для него есть разбираемая ссылка. */
    fun hasVlessTransport(): Boolean =
        VlessConfig.parse(getVlessConfigLink()) != null

    /**
     * VLESS выбирается двумя способами: регионом `vless` и выбором протокола в режиме
     * импортированных профилей. Во втором случае экран показывает не регионы, а
     * протоколы, и «VLESS» там означает перебор только своих профилей — встроенные
     * AWG в него не входят.
     */
    fun shouldUseVlessTransport(): Boolean {
        if (getVlessProfileLinks().isEmpty() && !hasVlessTransport()) return false
        return isVlessExplicitlyChosen()
    }

    /**
     * Назвал ли пользователь VLESS выбранным транспортом.
     *
     * Отдельно от [shouldUseVlessTransport] потому, что тот дополнительно требует
     * разбираемую ссылку: «выбран, но ссылок нет» и «не выбран» — разные состояния,
     * и подставлять встроенные профили нельзя ни в одном из них.
     *
     * В режиме импортированных конфигураций решает только выбор протокола: регион
     * там от прошлого выбора и экраном не управляется, а «сначала регион, потом
     * протокол» приводило к тому, что при выбранном AWG перебирались профили VLESS.
     */
    fun isVlessExplicitlyChosen(): Boolean = ImportedSourcePolicy.isVlessChosen(
        importedSourceActive = isImportedConfigSourceActive(),
        effectiveImportedProtocol = resolveEffectiveImportedProtocol(),
        regionPreference = normalizeRegionPreference(getExitRegionPreference()),
    )

    /**
     * true, если кроме VLESS пробовать нечего.
     *
     * Выбор VLESS отключает обычный пул WARP: цикл подключения честно доходит до
     * «shortlist пуст» и гасит службу. Пока перебор VLESS сдавался по бюджету
     * времени, это выглядело как «на 18-й попытке подключение само остановилось» —
     * уходить было некуда, а перебор всё равно уходил.
     *
     * Условие обязано совпадать с условием входа в фазу ([shouldUseVlessTransport]).
     * Пока оно было уже входного — только режим импортированных, без региона, —
     * провал VLESS уводил перебор на встроенные профили WARP: вход и выход
     * проверяли разные вещи.
     */
    fun isVlessOnlyTransportMode(): Boolean = isVlessExplicitlyChosen()

    fun shouldAllowOperaTransport(): Boolean {
        return when (getExitRegionPreference()) {
            "ru" -> false
            else -> true
        }
    }
    fun getPreferredOperaCountry(): String {
        return getOperaFallbackSequence().firstOrNull()?.first ?: "EU"
    }
    fun getPreferredOperaLabel(): String {
        return getOperaFallbackSequence().firstOrNull()?.second ?: "EU"
    }
    fun getOperaFallbackSequence(): List<Pair<String, String>> {
        return when (normalizeRegionPreference(getExitRegionPreference())) {
            "eu" -> listOf("EU" to "EU")
            "us" -> listOf("AM" to "US")
            "ru" -> emptyList()
            else -> listOf("EU" to "EU", "AM" to "US")
        }
    }

    fun getPreferredRegistrationCountries(): List<String> {
        val nowMs = System.currentTimeMillis()
        val defaults = listOf("EU", "AM")
        return defaults.sortedWith(
            compareByDescending<String> { country ->
                maxOf(
                    scoreRegistrationRoute(country, nowMs),
                    bestOperaBootstrapCountryScore(country, nowMs),
                )
            }.thenBy { defaults.indexOf(it) }
        )
    }

    fun getPreferredRegistrationProfiles(defaultOrder: List<String>): List<String> {
        val normalizedDefaults = defaultOrder
            .map { it.trim().lowercase(Locale.US) }
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedDefaults.isEmpty()) return emptyList()
        val nowMs = System.currentTimeMillis()
        return normalizedDefaults.sortedWith(
            compareByDescending<String> { profileId ->
                scoreRegistrationProfile(profileId, nowMs)
            }.thenBy { normalizedDefaults.indexOf(it) }
        )
    }

    fun getRetryRegistrationCountries(primaryOrder: List<String>): List<String> {
        val normalized = primaryOrder
            .map(::normalizeOperaRegionCode)
            .filter { it == "EU" || it == "AM" }
            .distinct()
        val defaults = listOf("EU", "AM")
        val base = if (normalized.isEmpty()) defaults else normalized
        return if (base.size <= 1) {
            defaults
        } else {
            listOf(base[1], base[0])
        }
    }

    fun recordRegistrationRouteOutcome(
        country: String,
        success: Boolean,
        durationMs: Long = 0L,
    ) {
        val normalizedCountry = normalizeOperaRegionCode(country)
        if (normalizedCountry != "EU" && normalizedCountry != "AM") return
        val key = registrationRouteKey(normalizedCountry)
        val nowMs = System.currentTimeMillis()
        val current = readRegistrationRouteStats(key, legacyRegistrationRouteKey(normalizedCountry))
        val next = if (success) {
            current.copy(
                attempts = current.attempts + 1,
                successes = current.successes + 1,
                consecutiveFailures = 0,
                avgSuccessMs = ewma(current.avgSuccessMs, durationMs),
                lastSuccessAt = nowMs,
            )
        } else {
            current.copy(
                attempts = current.attempts + 1,
                failures = current.failures + 1,
                consecutiveFailures = (current.consecutiveFailures + 1).coerceAtMost(8),
                lastFailureAt = nowMs,
            )
        }
        prefs.edit().putString(key, encodeRegistrationRouteStats(next)).commit()
    }

    fun recordRegistrationProfileOutcome(
        profileId: String,
        success: Boolean,
        durationMs: Long = 0L,
    ) {
        val normalizedProfileId = profileId.trim().lowercase(Locale.US)
        if (normalizedProfileId.isBlank()) return
        val key = registrationProfileKey(normalizedProfileId)
        val nowMs = System.currentTimeMillis()
        val current = readRegistrationProfileStats(key)
        val next = if (success) {
            current.copy(
                attempts = current.attempts + 1,
                successes = current.successes + 1,
                consecutiveFailures = 0,
                avgSuccessMs = ewma(current.avgSuccessMs, durationMs),
                lastSuccessAt = nowMs,
            )
        } else {
            current.copy(
                attempts = current.attempts + 1,
                failures = current.failures + 1,
                consecutiveFailures = (current.consecutiveFailures + 1).coerceAtMost(8),
                lastFailureAt = nowMs,
            )
        }
        prefs.edit().putString(key, encodeRegistrationProfileStats(next)).commit()
    }
    /**
     * Режим экрана «SNI маскировка»: `auto` или `custom`.
     *
     * Это выбор пользователя, поэтому живёт в настройках, а не в файле состояния:
     * пишет его только главный процесс, служба лишь читает — и читает через
     * extras при переустановке туннеля, как остальные настройки.
     */
    fun getSniMaskMode(): String = SniMaskPolicy.normalizeMode(prefs.getString("sni_mask_mode", SniMaskPolicy.MODE_AUTO))

    fun setSniMaskMode(value: String?) {
        prefs.edit().putString("sni_mask_mode", SniMaskPolicy.normalizeMode(value)).commit()
    }

    /** Сырая строка, как её набрал пользователь: показываем её же при возврате на экран. */
    fun getSniCustomListRaw(): String = prefs.getString("sni_mask_custom_list", "").orEmpty()

    fun setSniCustomListRaw(value: String?) {
        prefs.edit().putString("sni_mask_custom_list", value.orEmpty().trim()).commit()
    }

    fun getSniCustomHosts(): List<String> = SniMaskPolicy.parseCustomList(getSniCustomListRaw())

    /**
     * Наборы имён для маскировки: проверенные российские, большой российский список
     * и зарубежный.
     *
     * Списки лежат в ассетах (`white.sni`, `traffic_mask_russia.sni`,
     * `traffic_mask_global.sni`) и кэшируются каталогом, поэтому файл читается один
     * раз за процесс. Российский список большой — 59 тысяч имён, — но нужен нам не
     * целиком: политике хватает начала, а `buildOrder` берёт из него не больше
     * `MAX_ORDER` кандидатов.
     */
    fun getSniMaskPools(): SniMaskPolicy.Pools = SniMaskPolicy.Pools(
        white = TrafficMaskCatalog.getWhiteHosts(appContext),
        russia = TrafficMaskCatalog.getRussiaHosts(appContext).take(SNI_RUSSIA_POOL_LIMIT),
        global = TrafficMaskCatalog.getGlobalHosts(appContext),
    )

    fun getTrafficMaskEnabled(): Boolean = prefs.getBoolean("traffic_mask_enabled", true)
    fun setTrafficMaskEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("traffic_mask_enabled", enabled).commit()
    }
    fun getTrafficMaskMode(): String = normalizeTrafficMaskMode(prefs.getString("traffic_mask_mode", "auto"))
    fun setTrafficMaskMode(value: String?) {
        prefs.edit().putString("traffic_mask_mode", normalizeTrafficMaskMode(value)).commit()
    }
    fun getTrafficMaskHost(): String =
        normalizeTrafficMaskHost(prefs.getString("traffic_mask_host", DEFAULT_TRAFFIC_MASK_HOST))
    fun setTrafficMaskHost(value: String?) {
        prefs.edit().putString("traffic_mask_host", normalizeTrafficMaskHost(value)).commit()
    }
    fun getTrafficMaskActiveHost(): String =
        readTrafficMaskStateFile().optString("active_host").ifBlank {
            prefs.getString("traffic_mask_active_host", "").orEmpty()
        }
    fun getTrafficMaskActivePool(): String = normalizeTrafficMaskPool(
        readTrafficMaskStateFile().optString("active_pool").ifBlank {
            prefs.getString("traffic_mask_active_pool", null).orEmpty()
        }
    )
    fun getTrafficMaskRecentProbeHost(): String =
        readTrafficMaskStateFile().optString("recent_probe_host").ifBlank {
            prefs.getString("traffic_mask_recent_probe_host", "").orEmpty()
        }
    fun getTrafficMaskRecentProbePool(): String =
        normalizeTrafficMaskPool(
            readTrafficMaskStateFile().optString("recent_probe_pool").ifBlank {
                prefs.getString("traffic_mask_recent_probe_pool", null).orEmpty()
            }
        )
    fun setTrafficMaskActiveHost(value: String?, poolHint: String? = null) {
        val normalized = normalizeTrafficMaskHost(value)
        val pool = resolveTrafficMaskPool(normalized, poolHint)
        prefs.edit().apply {
            if (value.isNullOrBlank()) {
                remove("traffic_mask_active_host")
                remove("traffic_mask_active_pool")
            } else {
                putString("traffic_mask_active_host", normalized)
                putString("traffic_mask_recent_probe_host", normalized)
                if (pool.isBlank()) {
                    remove("traffic_mask_active_pool")
                } else {
                    putString("traffic_mask_active_pool", pool)
                    putString("traffic_mask_recent_probe_pool", pool)
                }
            }
            commit()
        }
        updateTrafficMaskStateFile(
            activeHost = normalized.takeIf { it.isNotBlank() },
            activePool = pool.takeIf { it.isNotBlank() },
            recentProbeHost = normalized.takeIf { it.isNotBlank() },
            recentProbePool = pool.takeIf { it.isNotBlank() },
        )
    }
    fun getWarpTrafficMaskActiveHost(): String =
        readTrafficMaskStateFile().optString("warp_active_host").ifBlank {
            prefs.getString("warp_traffic_mask_active_host", "").orEmpty()
        }
    fun setWarpTrafficMaskActiveHost(value: String?) {
        val normalized = normalizeTrafficMaskHost(value)
        prefs.edit().apply {
            if (value.isNullOrBlank()) {
                remove("warp_traffic_mask_active_host")
            } else {
                putString("warp_traffic_mask_active_host", normalized)
                putString("traffic_mask_recent_probe_host", normalized)
                putString("traffic_mask_recent_probe_pool", TRAFFIC_MASK_POOL_RUSSIA)
            }
            commit()
        }
        updateTrafficMaskStateFile(
            warpActiveHost = normalized.takeIf { it.isNotBlank() },
            recentProbeHost = normalized.takeIf { it.isNotBlank() },
            recentProbePool = TRAFFIC_MASK_POOL_RUSSIA,
        )
    }
    fun getTrafficMaskLastSuccessfulHost(): String = prefs.getString("traffic_mask_last_success_host", "").orEmpty()
    fun getTrafficMaskLastSuccessfulHostForPool(pool: String?): String {
        return when (normalizeTrafficMaskPool(pool)) {
            TRAFFIC_MASK_POOL_GLOBAL -> prefs.getString("traffic_mask_last_success_host_global", "").orEmpty()
            TRAFFIC_MASK_POOL_RUSSIA -> prefs.getString("traffic_mask_last_success_host_russia", "").orEmpty()
            else -> getTrafficMaskLastSuccessfulHost()
        }
    }
    fun getWarpTrafficMaskLastSuccessfulHost(): String = prefs.getString("warp_traffic_mask_last_success_host", "").orEmpty()

    fun recordTrafficMaskAttempt(
        host: String?,
        success: Boolean,
        poolHint: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (host.isNullOrBlank()) return
        val normalizedHost = normalizeTrafficMaskHost(host)
        if (normalizedHost.isBlank()) return
        val pool = resolveTrafficMaskPool(normalizedHost, poolHint)
        val current = readTrafficMaskStats(normalizedHost)
        val next = if (success) {
            current.copy(
                successes = current.successes + 1,
                lastSuccessAt = nowMs,
            )
        } else {
            current.copy(
                failures = current.failures + 1,
                lastFailureAt = nowMs,
            )
        }
        prefs.edit().apply {
            putString(trafficMaskStatsKey(normalizedHost), encodeTrafficMaskStats(next))
            if (success) {
                putString("traffic_mask_last_success_host", normalizedHost)
                putLong("traffic_mask_last_success_at", nowMs)
                when (pool) {
                    TRAFFIC_MASK_POOL_GLOBAL -> {
                        putString("traffic_mask_last_success_host_global", normalizedHost)
                        putLong("traffic_mask_last_success_at_global", nowMs)
                    }
                    TRAFFIC_MASK_POOL_RUSSIA -> {
                        putString("traffic_mask_last_success_host_russia", normalizedHost)
                        putLong("traffic_mask_last_success_at_russia", nowMs)
                    }
                }
            }
            commit()
        }
    }

    fun recordWarpTrafficMaskAttempt(host: String?, success: Boolean, nowMs: Long = System.currentTimeMillis()) {
        if (host.isNullOrBlank()) return
        val normalizedHost = normalizeTrafficMaskHost(host)
        if (normalizedHost.isBlank()) return
        prefs.edit().apply {
            if (success) {
                putString("warp_traffic_mask_last_success_host", normalizedHost)
                putLong("warp_traffic_mask_last_success_at", nowMs)
            }
            commit()
        }
    }

    fun getPreferredTrafficMaskHosts(allHosts: List<String>, limit: Int = 16): List<String> {
        val normalizedHosts = allHosts
            .map(::normalizeTrafficMaskHost)
            .filter { it.isNotBlank() }
            .distinct()
        if (normalizedHosts.isEmpty()) {
            return listOf(DEFAULT_TRAFFIC_MASK_HOST)
        }

        val pool = detectTrafficMaskPool(normalizedHosts)
        val knownStats = prefs.all.entries
            .asSequence()
            .filter { it.key.startsWith(TRAFFIC_MASK_STATS_PREFIX) }
            .mapNotNull { entry ->
                val host = entry.key.removePrefix(TRAFFIC_MASK_STATS_PREFIX)
                if (host !in normalizedHosts) return@mapNotNull null
                val stats = readTrafficMaskStats(host)
                if (stats.successes <= 0) return@mapNotNull null
                host to stats
            }
            .sortedWith(
                compareByDescending<Pair<String, TrafficMaskStats>> { it.second.successes }
                    .thenByDescending { it.second.lastSuccessAt }
                    .thenBy { it.second.failures }
            )
            .map { it.first }
            .toList()

        val preferred = linkedSetOf<String>()
        preferred += getTrafficMaskActiveHost().takeIf {
            it in normalizedHosts && (getTrafficMaskActivePool().isBlank() || getTrafficMaskActivePool() == pool)
        }.orEmpty()
        preferred += getTrafficMaskLastSuccessfulHostForPool(pool).takeIf { it in normalizedHosts }.orEmpty()
        preferred += getTrafficMaskLastSuccessfulHost().takeIf { it in normalizedHosts }.orEmpty()
        knownStats.forEach(preferred::add)

        val remaining = normalizedHosts
            .filterNot { it in preferred }
            .shuffled(Random(System.currentTimeMillis()))

        return (preferred.filter { it.isNotBlank() } + remaining).take(limit.coerceAtLeast(1))
    }

    fun getResolvedTrafficMaskLabel(): String {
        return when (getTrafficMaskMode()) {
            "custom" -> getTrafficMaskHost()
            else -> getTrafficMaskActiveHost().ifBlank { getTrafficMaskLastSuccessfulHost() }
        }
    }

    fun getOperaProxyCountry(): String = prefs.getString("opera_proxy_country", "").orEmpty()
    fun setOperaProxyCountry(value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove("opera_proxy_country") else putString("opera_proxy_country", value.trim().uppercase())
            commit()
        }
    }
    fun getOperaInternalProxyPort(): Int =
        prefs.getInt("opera_internal_proxy_port", 0).takeIf { it in 1024..65535 } ?: 0
    fun setOperaInternalProxyPort(value: Int?) {
        prefs.edit().apply {
            if (value == null || value !in 1024..65535) {
                remove("opera_internal_proxy_port")
            } else {
                putInt("opera_internal_proxy_port", value)
            }
            commit()
        }
    }

    /**
     * Состояние перебора Opera живёт в отдельном файле, а не в `SharedPreferences`.
     *
     * Служба работает в процессе `:vpn`, экран — в основном. Настройки в `MODE_PRIVATE`
     * кешируются каждым процессом целиком и не перечитываются, а любой `commit()`
     * сбрасывает на диск всю копию карты. Основной процесс на «Стоп» пишет сразу
     * несколько ключей и вместе с ними возвращает свой старый снимок, откатывая всё,
     * что за сеанс записал `:vpn`.
     *
     * Ровно это и происходило: endpoint, только что удержавший туннель двадцать
     * секунд, исчезал из кэша к следующему циклу — не по остыванию, а потому что файл
     * настроек откатывался к состоянию до подключения. Вместе с ним терялись и
     * поднятый наверх способ запуска, и выбранный профиль API, то есть перебор каждый
     * раз начинался с нуля.
     *
     * Всё читаем-меняем-пишем под общим замком: внутри `:vpn` сюда одновременно пишут
     * поток цикла подключения, колбэк tun2proxy и фоновые потоки recovery и prewarm.
     */
    private fun readOperaStateLocked(): JSONObject {
        val file = operaStateFile.baseFile
        val stamp = if (file.exists()) file.lastModified() to file.length() else 0L to 0L
        cachedOperaState?.let { cached ->
            if (cachedOperaStateStamp == stamp) return cached
        }
        val parsed = readAtomicJson(operaStateFile) ?: JSONObject()
        cachedOperaState = parsed
        cachedOperaStateStamp = stamp
        return parsed
    }

    private fun <T> withOperaState(block: (JSONObject) -> T): T =
        synchronized(operaStateLock) { block(readOperaStateLocked()) }

    private fun editOperaState(block: (JSONObject) -> Unit) {
        synchronized(operaStateLock) {
            val state = readOperaStateLocked()
            block(state)
            writeAtomicRaw(operaStateFile, state.toString())
            cachedOperaState = state
            val file = operaStateFile.baseFile
            cachedOperaStateStamp = if (file.exists()) file.lastModified() to file.length() else 0L to 0L
        }
    }

    private fun operaStateString(key: String): String = withOperaState { it.optString(key) }

    private fun operaStateLong(key: String): Long = withOperaState { it.optLong(key, 0L) }

    fun getOperaPinnedEndpoints(country: String): List<String> {
        val key = operaPinnedEndpointsKey(country)
        // Установка, обновившаяся со старой версии, читает список из настроек один раз:
        // выбрасывать уже проверенные адреса ради переезда хранилища незачем.
        val raw = operaStateString(key).ifBlank { prefs.getString(key, null).orEmpty() }
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val endpoint = normalizeOperaEndpoint(array.optString(index))
                    if (endpoint.isNotBlank() && endpoint !in this) add(endpoint)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveOperaPinnedEndpoints(country: String, endpoints: List<String>) {
        val normalized = endpoints
            .map(::normalizeOperaEndpoint)
            .filter { it.isNotBlank() }
            .distinct()
            .take(16)
        val key = operaPinnedEndpointsKey(country)
        editOperaState { state ->
            if (normalized.isEmpty()) {
                state.remove(key)
            } else {
                state.put(key, JSONArray().apply { normalized.forEach(::put) }.toString())
            }
        }
        // Легаси-копию убираем, чтобы кэш не жил в двух местах и старое значение не
        // всплыло обратно при откате настроек соседним процессом.
        if (prefs.contains(key)) {
            prefs.edit().remove(key).commit()
        }
    }

    /**
     * Складывает свежий ответ discover с уже известным списком, не теряя порядка.
     *
     * Прямая перезапись стирала выстроенный порядок: наверху списка стоит адрес,
     * поднятый туда после двадцати секунд удержания, а фоновая проверка через WARP
     * возвращала свой набор в порядке ответа API и клала его поверх. Проверенный
     * адрес оказывался в середине, и следующий цикл начинал не с него.
     *
     * Адреса, которых в свежем ответе нет, убираем: их больше не выдают, и держать их
     * в очереди — значит платить за них попытками.
     */
    /**
     * @return список, оказавшийся в кэше после слияния. Возвращаем его, чтобы
     * вызывающий мог показать результат в журнале: строка «Есть кэш Opera
     * endpoints» печатает первые два адреса, не попавшие в остывание, и по ней
     * слияние от замены не отличить.
     */
    fun mergeDiscoveredOperaPinnedEndpoints(country: String, discovered: List<String>): List<String> {
        val fresh = discovered
            .map(::normalizeOperaEndpoint)
            .filter { it.isNotBlank() }
            .distinct()
        val known = getOperaPinnedEndpoints(country)
        if (fresh.isEmpty()) return known
        val merged = ProfileRotation.mergeKeepingOrder(known, fresh)
        saveOperaPinnedEndpoints(country = country, endpoints = merged)
        return getOperaPinnedEndpoints(country)
    }

    /**
     * Когда лента опубликованных адресов Opera опрашивалась в последний раз.
     *
     * Две отметки, а не одна: успех держит ленту свежей часами, а попытка не даёт
     * долбить сеть по кругу, когда до GitHub не достучаться. Живут они в
     * `opera_state.json`, потому что читает и пишет их процесс `:vpn`, а
     * `SharedPreferences` откатывает записи соседнего процесса.
     */
    fun getOperaEndpointFeedSyncedAt(): Long = operaStateLong(OPERA_FEED_SYNCED_AT_KEY)

    fun getOperaEndpointFeedAttemptAt(): Long = operaStateLong(OPERA_FEED_ATTEMPT_AT_KEY)

    fun markOperaEndpointFeedAttempt(nowMs: Long = System.currentTimeMillis()) {
        editOperaState { it.put(OPERA_FEED_ATTEMPT_AT_KEY, nowMs) }
    }

    fun markOperaEndpointFeedSynced(nowMs: Long = System.currentTimeMillis()) {
        editOperaState { it.put(OPERA_FEED_SYNCED_AT_KEY, nowMs) }
    }

    /**
     * Прокси для вызовов API SurfEasy, заданный пользователем в настройках.
     *
     * Живёт в `opera_state.json`, а не в настройках: пишет его UI, а читает
     * процесс `:vpn`, и через `SharedPreferences` значение до него не дошло бы —
     * так уже молча пропал отладочный ключ «AWG без junk».
     */
    fun getCustomOperaApiProxy(): String = operaStateString(OPERA_CUSTOM_API_PROXY_KEY)

    fun setCustomOperaApiProxy(value: String) {
        val clean = value.trim().take(200)
        editOperaState { state ->
            if (clean.isEmpty()) {
                state.remove(OPERA_CUSTOM_API_PROXY_KEY)
            } else {
                state.put(OPERA_CUSTOM_API_PROXY_KEY, clean)
            }
        }
    }

    fun promoteOperaPinnedEndpoint(country: String, endpoint: String) {
        val normalized = normalizeOperaEndpoint(endpoint)
        if (normalized.isBlank()) return
        saveOperaPinnedEndpoints(
            country = country,
            endpoints = listOf(normalized) + getOperaPinnedEndpoints(country).filterNot { it == normalized },
        )
    }

    fun demoteOperaPinnedEndpoint(country: String, endpoint: String) {
        val normalized = normalizeOperaEndpoint(endpoint)
        if (normalized.isBlank()) return
        val endpoints = getOperaPinnedEndpoints(country)
        if (normalized !in endpoints) return
        saveOperaPinnedEndpoints(
            country = country,
            endpoints = endpoints.filterNot { it == normalized } + normalized,
        )
    }

    fun markOperaPinnedEndpointFailure(
        country: String,
        endpoint: String,
        cooldownMs: Long = 5L * 60L * 1000L,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val normalizedCountry = normalizeOperaRegionCode(country)
        val normalizedEndpoint = normalizeOperaEndpoint(endpoint)
        if (normalizedCountry.isBlank() || normalizedEndpoint.isBlank()) return
        val key = operaPinnedEndpointFailureKey(normalizedCountry, normalizedEndpoint)
        editOperaState { it.put(key, nowMs + cooldownMs.coerceAtLeast(15_000L)) }
    }

    fun isOperaPinnedEndpointCoolingDown(
        country: String,
        endpoint: String,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val normalizedCountry = normalizeOperaRegionCode(country)
        val normalizedEndpoint = normalizeOperaEndpoint(endpoint)
        if (normalizedCountry.isBlank() || normalizedEndpoint.isBlank()) return false
        val key = operaPinnedEndpointFailureKey(normalizedCountry, normalizedEndpoint)
        val until = operaStateLong(key)
        if (until <= 0L) return false
        if (until <= nowMs) {
            // Протухшие отметки убираем сразу: иначе файл разрастался бы при ротации.
            editOperaState { it.remove(key) }
            return false
        }
        return true
    }

    fun getPreferredOperaApiProfile(country: String): String {
        return operaStateString(operaApiProfileKey(country))
    }

    fun setPreferredOperaApiProfile(country: String, profileId: String?) {
        val normalized = normalizeOperaApiProfileId(profileId)
        val key = operaApiProfileKey(country)
        editOperaState { state ->
            if (normalized.isBlank()) state.remove(key) else state.put(key, normalized)
        }
    }

    fun markOperaApiProfileFailure(
        country: String,
        profileId: String?,
        cooldownMs: Long = 90_000L,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val normalizedCountry = normalizeOperaRegionCode(country)
        val normalizedProfile = normalizeOperaApiProfileId(profileId)
        if (normalizedCountry.isBlank() || normalizedProfile.isBlank()) return
        val failureKey = operaApiProfileFailureKey(normalizedCountry, normalizedProfile)
        val preferredKey = operaApiProfileKey(normalizedCountry)
        editOperaState { state ->
            state.put(failureKey, nowMs + cooldownMs.coerceAtLeast(10_000L))
            if (state.optString(preferredKey) == normalizedProfile) {
                state.remove(preferredKey)
            }
        }
    }

    fun isOperaApiProfileCoolingDown(
        country: String,
        profileId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val normalizedCountry = normalizeOperaRegionCode(country)
        val normalizedProfile = normalizeOperaApiProfileId(profileId)
        if (normalizedCountry.isBlank() || normalizedProfile.isBlank()) return false
        val key = operaApiProfileFailureKey(normalizedCountry, normalizedProfile)
        val until = operaStateLong(key)
        if (until <= 0L) return false
        if (until <= nowMs) {
            editOperaState { it.remove(key) }
            return false
        }
        return true
    }

    fun markWarpAttemptCooldown(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        preferredSni: String?,
        cooldownMs: Long = 8L * 60L * 1000L,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val key = warpAttemptCooldownKey(
            engine = engine,
            mode = mode,
            host = host,
            port = port,
            preferredSni = preferredSni,
        ) ?: return
        prefs.edit()
            .putLong(key, nowMs + cooldownMs.coerceAtLeast(20_000L))
            .commit()
    }

    fun isWarpAttemptCoolingDown(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        preferredSni: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = warpAttemptCooldownKey(
            engine = engine,
            mode = mode,
            host = host,
            port = port,
            preferredSni = preferredSni,
        ) ?: return false
        val until = prefs.getLong(key, 0L)
        if (until <= 0L) return false
        if (until <= nowMs) {
            prefs.edit().remove(key).apply()
            return false
        }
        return true
    }

    private fun warpAttemptCooldownKey(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        preferredSni: String?,
    ): String? {
        if (port !in 1..65535) return null
        val normalizedEngine = normalizeToken(engine)
        val normalizedMode = normalizeToken(mode)
        val normalizedHost = normalizeHost(host)
        val normalizedPreferredSni = normalizeOptionalTrafficMaskHost(preferredSni)
        if (normalizedEngine.isBlank() || normalizedMode.isBlank() || normalizedHost.isBlank()) return null
        return "warp_attempt_cooldown|$normalizedEngine|$normalizedMode|$normalizedHost|$port|$normalizedPreferredSni"
    }

    fun recordOperaLaunchPlanOutcome(
        country: String,
        fakeSni: String?,
        endpoint: String?,
        apiProfileId: String?,
        success: Boolean,
        durationMs: Long = 0L,
    ) {
        val key = operaLaunchPlanKey(country, fakeSni, endpoint, apiProfileId)
        if (key.isBlank()) return
        val nowMs = System.currentTimeMillis()
        val current = readOperaLaunchPlanStats(key)
        val next = if (success) {
            current.copy(
                attempts = current.attempts + 1,
                successes = current.successes + 1,
                consecutiveFailures = 0,
                avgSuccessMs = ewma(current.avgSuccessMs, durationMs),
                lastSuccessAt = nowMs,
            )
        } else {
            current.copy(
                attempts = current.attempts + 1,
                failures = current.failures + 1,
                consecutiveFailures = (current.consecutiveFailures + 1).coerceAtMost(8),
                lastFailureAt = nowMs,
            )
        }
        editOperaState { it.put(key, encodeOperaLaunchPlanStats(next)) }
    }

    /**
     * Помечает способ запуска Opera как удержавшийся.
     *
     * То же правило, что у встроенных профилей ([promoteWarpVerifiedConfig]): наверх
     * очереди поднимается не то, что «когда-то подключилось», а то, что продержалось
     * двадцать секунд. Статистика попыток на это неспособна — она одинаково считает
     * успехом и рабочий узел, и тот, что поднял локальный порт и сразу отвалился.
     */
    fun promoteOperaLaunchPlan(
        country: String,
        fakeSni: String?,
        endpoint: String?,
        apiProfileId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val key = operaLaunchPlanPromotionKey(country, fakeSni, endpoint, apiProfileId)
        if (key.isBlank()) return
        editOperaState { it.put(key, nowMs) }
    }

    /** Когда этот способ запуска в последний раз удержался. Ноль — ни разу. */
    fun getOperaLaunchPlanPromotedAt(
        country: String,
        fakeSni: String?,
        endpoint: String?,
        apiProfileId: String?,
    ): Long {
        val key = operaLaunchPlanPromotionKey(country, fakeSni, endpoint, apiProfileId)
        if (key.isBlank()) return 0L
        return operaStateLong(key)
    }

    private fun operaLaunchPlanPromotionKey(
        country: String,
        fakeSni: String?,
        endpoint: String?,
        apiProfileId: String?,
    ): String {
        val base = operaLaunchPlanKey(country, fakeSni, endpoint, apiProfileId)
        return if (base.isBlank()) "" else "${base}|promoted_at"
    }

    fun getOperaLaunchPlanScore(
        country: String,
        fakeSni: String?,
        endpoint: String?,
        apiProfileId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Double {
        val key = operaLaunchPlanKey(country, fakeSni, endpoint, apiProfileId)
        if (key.isBlank()) return 0.0
        val stats = readOperaLaunchPlanStats(key)
        return scoreOperaLaunchPlanStats(stats, nowMs)
    }

    fun recordOperaRegistrationPlanOutcome(
        country: String,
        fakeSni: String?,
        endpoint: String?,
        apiProfileId: String?,
        success: Boolean,
        durationMs: Long = 0L,
    ) {
        val key = operaRegistrationPlanKey(country, fakeSni, endpoint, apiProfileId)
        if (key.isBlank()) return
        val nowMs = System.currentTimeMillis()
        val current = readOperaRegistrationPlanStats(key)
        val next = if (success) {
            current.copy(
                attempts = current.attempts + 1,
                successes = current.successes + 1,
                consecutiveFailures = 0,
                avgSuccessMs = ewma(current.avgSuccessMs, durationMs),
                lastSuccessAt = nowMs,
            )
        } else {
            current.copy(
                attempts = current.attempts + 1,
                failures = current.failures + 1,
                consecutiveFailures = (current.consecutiveFailures + 1).coerceAtMost(8),
                lastFailureAt = nowMs,
            )
        }
        editOperaState { it.put(key, encodeOperaRegistrationPlanStats(next)) }
    }

    fun getOperaRegistrationPlanScore(
        country: String,
        fakeSni: String?,
        endpoint: String?,
        apiProfileId: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Double {
        val key = operaRegistrationPlanKey(country, fakeSni, endpoint, apiProfileId)
        if (key.isBlank()) return 0.0
        val stats = readOperaRegistrationPlanStats(key)
        return scoreOperaRegistrationPlanStats(stats, nowMs)
    }

    private fun scoreOperaLaunchPlanStats(
        stats: OperaLaunchPlanStats,
        nowMs: Long,
    ): Double {
        if (stats.attempts <= 0) return 0.0
        if (stats.successes <= 0) {
            return -(
                stats.failures.coerceAtMost(6) * 2.5 +
                    stats.consecutiveFailures.coerceAtMost(6) * 4.0
                )
        }
        val attempts = stats.attempts.toDouble()
        val successRate = (stats.successes + 0.75) / (attempts + 1.5)
        val speedScore = when {
            stats.avgSuccessMs <= 0.0 -> 0.35
            else -> 1.0 - (stats.avgSuccessMs.coerceAtMost(20_000.0) / 20_000.0)
        }
        val recencyScore = when {
            stats.lastSuccessAt <= 0L -> 0.0
            else -> {
                val ageMs = (nowMs - stats.lastSuccessAt).coerceAtLeast(0L).coerceAtMost(7L * 24 * 60 * 60 * 1000L)
                1.0 - ageMs.toDouble() / (7.0 * 24 * 60 * 60 * 1000.0)
            }
        }
        return successRate * 24.0 +
            speedScore * 14.0 +
            recencyScore * 6.0 -
            stats.consecutiveFailures.coerceAtMost(6) * 3.5
    }

    private fun scoreOperaRegistrationPlanStats(
        stats: OperaRegistrationPlanStats,
        nowMs: Long,
    ): Double {
        if (stats.attempts <= 0) return 0.0
        if (stats.successes <= 0) {
            return -(
                stats.failures.coerceAtMost(6) * 3.0 +
                    stats.consecutiveFailures.coerceAtMost(6) * 4.5
                )
        }
        val attempts = stats.attempts.toDouble()
        val successRate = (stats.successes + 0.75) / (attempts + 1.5)
        val speedScore = when {
            stats.avgSuccessMs <= 0.0 -> 0.35
            else -> 1.0 - (stats.avgSuccessMs.coerceAtMost(15_000.0) / 15_000.0)
        }
        val recencyScore = when {
            stats.lastSuccessAt <= 0L -> 0.0
            else -> {
                val ageMs = (nowMs - stats.lastSuccessAt).coerceAtLeast(0L).coerceAtMost(7L * 24 * 60 * 60 * 1000L)
                1.0 - ageMs.toDouble() / (7.0 * 24 * 60 * 60 * 1000.0)
            }
        }
        return successRate * 28.0 +
            speedScore * 16.0 +
            recencyScore * 6.0 -
            stats.consecutiveFailures.coerceAtMost(6) * 4.0
    }

    fun canStartOperaBootstrapViaWarp(nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastStartedAt = prefs.getLong("opera_bootstrap_warp_started_at", 0L)
        return lastStartedAt <= 0L || nowMs - lastStartedAt >= 75_000L
    }

    fun markOperaBootstrapViaWarpPending(region: String, nowMs: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putString("opera_bootstrap_warp_pending_region", normalizeRegionPreference(region))
            .putLong("opera_bootstrap_warp_pending_until", nowMs + 2L * 60L * 1000L)
            .putLong("opera_bootstrap_warp_started_at", nowMs)
            .commit()
    }

    fun getPendingOperaBootstrapViaWarpRegion(nowMs: Long = System.currentTimeMillis()): String {
        val until = prefs.getLong("opera_bootstrap_warp_pending_until", 0L)
        if (until <= nowMs) return ""
        return normalizeRegionPreference(prefs.getString("opera_bootstrap_warp_pending_region", ""))
            .takeIf { it == "eu" || it == "us" }
            .orEmpty()
    }

    fun clearOperaBootstrapViaWarpPending() {
        prefs.edit()
            .remove("opera_bootstrap_warp_pending_region")
            .remove("opera_bootstrap_warp_pending_until")
            .commit()
    }

    fun getLastUpdateCheckAt(): Long = prefs.getLong("app_update_last_check_at", 0L)
    fun setLastUpdateCheckAt(value: Long) { prefs.edit().putLong("app_update_last_check_at", value).commit() }
    fun getLastUpdateVersion(): String = prefs.getString("app_update_last_version", "").orEmpty()
    fun setLastUpdateVersion(value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove("app_update_last_version") else putString("app_update_last_version", value.trim())
            commit()
        }
    }
    fun getLastUpdateUrl(): String = prefs.getString("app_update_last_url", "").orEmpty()
    fun setLastUpdateUrl(value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove("app_update_last_url") else putString("app_update_last_url", value.trim())
            commit()
        }
    }
    fun getLastUpdateSha256(): String = prefs.getString("app_update_last_sha256", "").orEmpty()
    fun setLastUpdateSha256(value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove("app_update_last_sha256") else putString("app_update_last_sha256", value.trim())
            commit()
        }
    }
    fun getUpdateDownloadId(): Long = prefs.getLong("app_update_download_id", -1L)
    fun setUpdateDownloadId(value: Long) { prefs.edit().putLong("app_update_download_id", value).commit() }
    fun getDownloadedApkPath(): String = prefs.getString("app_update_downloaded_path", "").orEmpty()
    fun setDownloadedApkPath(value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove("app_update_downloaded_path") else putString("app_update_downloaded_path", value)
            commit()
        }
    }
    fun getDownloadedApkVersion(): String = prefs.getString("app_update_downloaded_version", "").orEmpty()
    fun setDownloadedApkVersion(value: String?) {
        prefs.edit().apply {
            if (value.isNullOrBlank()) remove("app_update_downloaded_version") else putString("app_update_downloaded_version", value)
            commit()
        }
    }
    fun shouldResumeInstallAfterPermissionGrant(): Boolean =
        prefs.getBoolean("app_update_resume_install_after_permission", false)
    fun setResumeInstallAfterPermissionGrant(value: Boolean) {
        prefs.edit().putBoolean("app_update_resume_install_after_permission", value).commit()
    }
    fun consumeResumeInstallAfterPermissionGrant(): Boolean {
        val shouldResume = shouldResumeInstallAfterPermissionGrant()
        if (shouldResume) {
            setResumeInstallAfterPermissionGrant(false)
        }
        return shouldResume
    }
    fun isUpdateRepairInProgress(): Boolean = prefs.getBoolean("app_update_repair_active", false)
    fun getUpdateRepairVersion(): String = prefs.getString("app_update_repair_version", "").orEmpty()
    fun getUpdateRepairDownloadedBytes(): Long = prefs.getLong("app_update_repair_downloaded_bytes", 0L)
    fun getUpdateRepairTotalBytes(): Long = prefs.getLong("app_update_repair_total_bytes", 0L)
    fun getUpdateRepairStatus(): String = prefs.getString("app_update_repair_status", "").orEmpty()
    fun setUpdateRepairState(
        active: Boolean,
        version: String? = null,
        downloadedBytes: Long = 0L,
        totalBytes: Long = 0L,
        status: String? = null,
    ) {
        prefs.edit().apply {
            putBoolean("app_update_repair_active", active)
            if (version.isNullOrBlank()) remove("app_update_repair_version") else putString("app_update_repair_version", version.trim())
            putLong("app_update_repair_downloaded_bytes", downloadedBytes.coerceAtLeast(0L))
            putLong("app_update_repair_total_bytes", totalBytes.coerceAtLeast(0L))
            if (status.isNullOrBlank()) remove("app_update_repair_status") else putString("app_update_repair_status", status.trim())
            commit()
        }
    }
    fun clearUpdateRepairState() {
        prefs.edit().apply {
            remove("app_update_repair_active")
            remove("app_update_repair_version")
            remove("app_update_repair_downloaded_bytes")
            remove("app_update_repair_total_bytes")
            remove("app_update_repair_status")
            commit()
        }
    }
    fun clearDownloadedUpdateState() {
        prefs.edit().apply {
            remove("app_update_download_id")
            remove("app_update_downloaded_path")
            remove("app_update_downloaded_version")
            commit()
        }
    }

    /**
     * Адреса DNS, запросы к которым обязаны идти напрямую к провайдеру, мимо VPN.
     *
     * Требование владельца: Xbox DNS используется во всех режимах, и обращения к нему
     * не должны заворачиваться в туннель. Реализуется исключением этих адресов из
     * маршрутов `VpnService.Builder`: пакет к ним просто не попадает в TUN — ни к
     * перехвату DNS в ядре (WARP/MASQUE), ни в tun2proxy (Opera, VLESS).
     */
    fun getDirectBypassDnsServers(): List<String> = XBOX_DNS_SERVERS

    fun getPreferredVpnDnsServers(
        backendLabel: String = getServiceBackend(),
        countryHint: String? = null,
    ): List<String> = resolvePreferredVpnDnsProfile(backendLabel, countryHint).servers

    fun getPreferredVpnDnsLabel(
        backendLabel: String = getServiceBackend(),
        countryHint: String? = null,
    ): String = resolvePreferredVpnDnsProfile(backendLabel, countryHint).label

    fun getFallbackVpnDnsServers(
        backendLabel: String = getServiceBackend(),
        countryHint: String? = null,
    ): List<String> = resolvePreferredVpnDnsProfile(
        backendLabel = backendLabel,
        countryHint = countryHint,
        allowLocalDnsOverride = false,
    ).servers

    fun getFallbackVpnDnsLabel(
        backendLabel: String = getServiceBackend(),
        countryHint: String? = null,
    ): String = resolvePreferredVpnDnsProfile(
        backendLabel = backendLabel,
        countryHint = countryHint,
        allowLocalDnsOverride = false,
    ).label

    fun getPreferredOperaBootstrapResolvers(): String =
        resolvePreferredVpnDnsProfile(backendLabel = NovaVpnService.BACKEND_OPERA, countryHint = null).operaBootstrapResolvers

    fun getPreferredOperaBootstrapLabel(): String =
        resolvePreferredVpnDnsProfile(backendLabel = NovaVpnService.BACKEND_OPERA, countryHint = null).operaBootstrapLabel

    fun getFallbackOperaBootstrapResolvers(): String =
        resolvePreferredVpnDnsProfile(
            backendLabel = NovaVpnService.BACKEND_OPERA,
            countryHint = null,
            allowLocalDnsOverride = false,
        ).operaBootstrapResolvers

    fun getFallbackOperaBootstrapLabel(): String =
        resolvePreferredVpnDnsProfile(
            backendLabel = NovaVpnService.BACKEND_OPERA,
            countryHint = null,
            allowLocalDnsOverride = false,
        ).operaBootstrapLabel

    fun isLocalDnsProxyPlanned(): Boolean = false

    fun getLocalDnsProxySummary(): String = buildLocalDnsSummary()

    private fun resolvePreferredVpnDnsProfile(
        backendLabel: String,
        countryHint: String?,
        allowLocalDnsOverride: Boolean = true,
    ): VpnDnsProfile {
        val (operaBootstrapResolvers, operaBootstrapLabel) = resolvePreferredOperaBootstrapProfile(allowLocalDnsOverride)
        if (allowLocalDnsOverride && shouldUseGlobalMediaAdBlockDns(backendLabel, countryHint)) {
            return VpnDnsProfile(
                label = "adguard-noads-media-profile",
                servers = ADGUARD_NO_ADS_DNS_SERVERS,
                operaBootstrapResolvers = ADGUARD_NO_ADS_BOOTSTRAP_RESOLVERS,
                operaBootstrapLabel = "adguard-noads-opera-bootstrap",
            )
        }

        // Xbox DNS идёт первым в любом режиме — решение владельца. Раньше он
        // подставлялся только на российском выходе WARP, а встроенный AWG с выходом,
        // скажем, в Финляндии и весь прокси-режим Opera получали Cloudflare. Теперь
        // порядок один для всех: Xbox впереди, остальные резолверы — запасные.
        // Порядок в списке и есть порядок обхода: и `VpnService.Builder`, и перехват
        // DNS в ядре берут следующий адрес, когда предыдущий не ответил.
        val primary = XBOX_DNS_SERVERS
        val secondary = CLOUDFLARE_DNS_SERVERS
        val label = when {
            isOperaBackendLabel(backendLabel) -> "xbox-primary-opera"
            isVlessBackendLabel(backendLabel) -> "xbox-primary-vless"
            shouldPreferXboxDnsForWarp(countryHint) -> "xbox-primary-warp-ru"
            else -> "xbox-primary-warp"
        }
        return VpnDnsProfile(
            label = label,
            servers = (primary + secondary).distinct(),
            operaBootstrapResolvers = operaBootstrapResolvers,
            operaBootstrapLabel = operaBootstrapLabel,
        )
    }

    private fun resolvePreferredOperaBootstrapProfile(
        allowLocalDnsOverride: Boolean,
    ): Pair<String, String> {
        // Xbox DNS стоит первым и здесь: opera-proxy резолвит свой API до того, как
        // поднят туннель, то есть по сети оператора — ровно там, где этот резолвер и
        // должен работать. Дальше идут DNS провайдера и публичные, как раньше.
        if (!allowLocalDnsOverride) {
            return (XBOX_BOOTSTRAP_RESOLVER_LIST + DEFAULT_OPERA_BOOTSTRAP_RESOLVER_LIST)
                .distinct()
                .joinToString(",") to "xbox-public-opera-bootstrap"
        }
        val providerResolvers = resolveProviderOperaBootstrapResolvers()
        if (providerResolvers.isEmpty()) {
            return (XBOX_BOOTSTRAP_RESOLVER_LIST + DEFAULT_OPERA_BOOTSTRAP_RESOLVER_LIST)
                .distinct()
                .joinToString(",") to "xbox-public-opera-bootstrap"
        }
        val combined = (
            XBOX_BOOTSTRAP_RESOLVER_LIST + providerResolvers + DEFAULT_OPERA_BOOTSTRAP_RESOLVER_LIST
            ).distinct()
        return combined.joinToString(",") to "xbox-provider-public-opera-bootstrap"
    }

    private fun resolveProviderOperaBootstrapResolvers(): List<String> {
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val activeNetwork = connectivityManager.activeNetwork ?: return emptyList()
        val activeCaps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return emptyList()
        if (activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return emptyList()
        val linkProperties = connectivityManager.getLinkProperties(activeNetwork) ?: return emptyList()
        return linkProperties.dnsServers
            .mapNotNull { server ->
                normalizeBootstrapDnsHost(
                    server.hostAddress
                        ?.substringBefore('%')
                        ?.trim()
                )
            }
            .filterNot(::isExcludedBootstrapDnsHost)
            .distinct()
            .map(::toPlainBootstrapDnsResolver)
            .filter { it.isNotBlank() }
    }

    private fun normalizeBootstrapDnsHost(value: String?): String {
        return value?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
    }

    private fun isExcludedBootstrapDnsHost(host: String): Boolean {
        if (host.isBlank()) return true
        val normalized = normalizeBootstrapDnsHost(host)
        if (normalized.isBlank()) return true
        if (normalized in XBOX_DNS_SERVERS) return true
        if (normalized in ADGUARD_NO_ADS_DNS_SERVERS) return true
        if (normalized == LocalDnsPolicy.LOCAL_PROXY_IPV4 || normalized == LocalDnsPolicy.LOCAL_PROXY_IPV6) return true
        if (normalized == "127.0.0.1" || normalized == "::1") return true
        return runCatching { InetAddress.getByName(normalized).isLoopbackAddress }.getOrDefault(false)
    }

    private fun toPlainBootstrapDnsResolver(host: String): String {
        val normalized = normalizeBootstrapDnsHost(host)
        return if (normalized.contains(':')) {
            "dns://[$normalized]"
        } else {
            "dns://$normalized"
        }
    }

    private fun shouldUseGlobalMediaAdBlockDns(
        backendLabel: String = getServiceBackend(),
        countryHint: String? = null,
    ): Boolean {
        if (!isOperaBackendLabel(backendLabel)) return false
        if (!isNonRussianRouteForMediaDns(backendLabel, countryHint)) return false
        val selectedApps = getSplitApps()
        return getSplitMode() == 1 &&
            selectedApps.isNotEmpty() &&
            selectedApps.all(LocalDnsPolicy::isMediaAdBlockPackage)
    }

    fun shouldEnableTargetedMediaDns(
        backendLabel: String = getServiceBackend(),
        countryHint: String? = null,
    ): Boolean {
        if (isOperaBackendLabel(backendLabel)) return false
        if (!isNonRussianRouteForMediaDns(backendLabel, countryHint)) return false
        return when (getSplitMode()) {
            0 -> true
            1 -> getSplitApps().any(LocalDnsPolicy::isMediaAdBlockPackage)
            2 -> true
            else -> false
        }
    }

    fun shouldPreferMessengerWarpProfiles(): Boolean {
        val installedMessengerPackages = getInstalledMessengerPackages()
        if (installedMessengerPackages.isEmpty()) return false

        val selectedApps = getSplitApps()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        return when (getSplitMode()) {
            1 -> installedMessengerPackages.any { it in selectedApps } ||
                selectedApps.any(MessengerObfsPolicy::isMessengerPackage)
            2 -> installedMessengerPackages.any { it !in selectedApps }
            else -> true
        }
    }

    /**
     * Называет причину, по которой [shouldPreferMessengerWarpProfiles] сказала «нет».
     *
     * Условие одно, но складывается из двух разных обстоятельств, и лечатся они
     * противоположным образом: мессенджера нет на устройстве или он не попал в
     * раздельное туннелирование. Без разбора обе выглядят как «не работает», и
     * один прогон на это уже потрачен.
     */
    fun describeMessengerWarpProfilesRefusal(): String {
        val installedMessengerPackages = getInstalledMessengerPackages()
        if (installedMessengerPackages.isEmpty()) {
            return "на устройстве нет ни одного известного мессенджера"
        }
        return "мессенджеры (${installedMessengerPackages.size}) не попали в раздельное " +
            "туннелирование (режим ${getSplitMode()})"
    }

    /**
     * Отладочный ключ: убрать junk-пакеты AWG (`Jc`, `Jmin`, `Jmax`, `I1..I5`) из
     * конфигурации туннеля.
     *
     * Нужен, чтобы разделить причины нестабильности WARP (проблема 5 в ROADMAP):
     * на одном и том же узле снять окно замера с junk и без него. Ставится через
     * adb, в интерфейсе не показывается — это инструмент опыта, а не настройка.
     */
    fun isAwgJunkDisabled(): Boolean = operaStateString(AWG_JUNK_DISABLED_KEY) == "1"

    fun setAwgJunkDisabled(disabled: Boolean) {
        // В файл, а не в SharedPreferences: ключ пишет экран, а читает служба из
        // процесса `:vpn`, и там у настроек свой кэш — переключатель бы просто не
        // доехал. На этом уже обжигались, см. ROADMAP.
        editOperaState { state ->
            if (disabled) state.put(AWG_JUNK_DISABLED_KEY, "1") else state.remove(AWG_JUNK_DISABLED_KEY)
        }
    }

    fun shouldForceMessengerWarpPriority(): Boolean {
        // Chat-aware WARP reordering is intentionally disabled for now.
        // It changed the fast-start path, but did not solve Telegram DPI slowdowns.
        // Messenger-aware camouflage and DNS logic remain available independently.
        return false
    }

    fun isMediaAdBlockDnsActive(): Boolean =
        shouldEnableTargetedMediaDns() || shouldUseGlobalMediaAdBlockDns()

    fun getMediaAdBlockDnsServers(): List<String> = ADGUARD_NO_ADS_DNS_SERVERS

    fun getMediaAdBlockDnsDomains(): List<String> = LocalDnsPolicy.getMediaDomainSuffixes()

    fun getMediaAdBlockDnsSummary(): String = buildLocalDnsSummary()

    private fun getInstalledMessengerPackages(): Set<String> {
        return MessengerObfsPolicy.getInstalledMessengerPackages(appContext)
    }

    private fun buildLocalDnsSummary(): String {
        val splitMode = getSplitMode()
        val selectedApps = getSplitApps()
        val matchedSelectedApps = selectedApps
            .filter(LocalDnsPolicy::isMediaAdBlockPackage)
            .map(LocalDnsPolicy::toDisplayName)
            .distinct()
        val routeReady = isNonRussianRouteForMediaDns(getServiceBackend(), null)
        return when (splitMode) {
            1 -> {
                if (selectedApps.isEmpty()) {
                    "Выберите YouTube или Twitch, чтобы Nova могла использовать для их доменов отдельный DNS."
                } else if (matchedSelectedApps.isEmpty()) {
                    "Добавьте YouTube или Twitch в список приложений VPN, чтобы Nova использовала для их доменов DNS AdGuard No Ads."
                } else {
                    val appSummary = when (matchedSelectedApps.size) {
                        1 -> matchedSelectedApps.first()
                        2 -> matchedSelectedApps.joinToString(" и ")
                        else -> "${matchedSelectedApps.size} приложений"
                    }
                    if (routeReady) {
                        "Для доменов $appSummary, которые идут через VPN, Nova использует DNS AdGuard No Ads. " +
                            "Если он недоступен, Nova автоматически вернётся к обычному DNS."
                    } else {
                        "Для доменов $appSummary отдельный DNS включается только при зарубежном IP. " +
                            "Сейчас Nova использует обычный DNS."
                    }
                }
            }
            2 -> {
                if (routeReady) {
                    "Если YouTube или Twitch не исключены из VPN, Nova использует для их доменов DNS AdGuard No Ads. " +
                        "При недоступности AdGuard Nova автоматически вернётся к обычному DNS."
                } else {
                    "Отдельный DNS для YouTube и Twitch включается только при зарубежном IP. " +
                        "Сейчас Nova использует обычный DNS."
                }
            }
            0 -> {
                if (routeReady) {
                    "Для доменов YouTube и Twitch, которые идут через VPN, Nova использует DNS AdGuard No Ads. " +
                        "При недоступности AdGuard Nova автоматически вернётся к обычному DNS."
                } else {
                    "Отдельный DNS для YouTube и Twitch включается только при зарубежном IP. " +
                        "Сейчас Nova использует обычный DNS."
                }
            }
            else -> ""
        }
    }

    private fun isNonRussianRouteForMediaDns(
        backendLabel: String,
        countryHint: String? = null,
    ): Boolean {
        val preference = normalizeRegionPreference(getExitRegionPreference())
        if (preference == "ru") return false
        if (preference == "eu" || preference == "us") return true
        if (isOperaBackendLabel(backendLabel)) return true

        val resolvedCountry = countryHint?.trim()?.uppercase().orEmpty()
            .ifBlank { getLastExitCountry().trim().uppercase() }
        if (resolvedCountry.isNotBlank()) {
            return resolvedCountry != "RU"
        }

        val snapshotBackend = getTunnelUiSnapshot()?.backend?.trim()?.uppercase().orEmpty()
        val snapshotCountry = getTunnelUiSnapshot()?.country?.trim()?.uppercase().orEmpty()
        if (snapshotBackend.startsWith(NovaVpnService.BACKEND_OPERA)) return true
        if (snapshotCountry.isNotBlank()) return snapshotCountry != "RU"

        return false
    }

    // Port Rotation
    private val PORTS = listOf(500, 4500, 1701, 443, 1002)

    fun getLastSuccessPort(): Int = prefs.getInt("last_success_port", -1)

    fun getCurrentPort(): Int {
        val lastPort = getLastSuccessPort()
        if (lastPort != -1 && hasFreshLastSuccess()) return lastPort
        val index = prefs.getInt("port_index", 0)
        return PORTS.getOrElse(index) { 500 }
    }

    fun rotatePort() {
        prefs.edit().remove("last_success_port").apply()
        var index = prefs.getInt("port_index", 0)
        index = (index + 1) % PORTS.size
        prefs.edit().putInt("port_index", index).apply()
    }

    fun saveSuccessParams(
        port: Int,
        protocol: String,
        endpointHost: String? = null,
        modeName: String? = null,
    ) {
        prefs.edit().apply {
            putInt("last_success_port", port)
            putString("last_success_protocol", protocol)
            putLong("last_success_at", System.currentTimeMillis())
            if (!modeName.isNullOrBlank()) {
                putString("last_success_mode", modeName)
            } else {
                remove("last_success_mode")
            }
            if (protocol.equals("MASQUE", ignoreCase = true)) {
                remove("masque_transport_failed_at")
                remove("masque_transport_failed_count")
            }
            if (!endpointHost.isNullOrBlank()) {
                putString("last_success_endpoint", endpointHost)
            }
            apply()
        }
    }

    fun saveWarpLastSuccessParams(
        port: Int,
        protocol: String,
        endpointHost: String? = null,
        modeName: String? = null,
    ) {
        prefs.edit().apply {
            putInt("warp_last_success_port", port)
            putString("warp_last_success_protocol", protocol)
            putLong("warp_last_success_at", System.currentTimeMillis())
            if (!modeName.isNullOrBlank()) {
                putString("warp_last_success_mode", modeName)
            } else {
                remove("warp_last_success_mode")
            }
            if (!endpointHost.isNullOrBlank()) {
                putString("warp_last_success_endpoint", endpointHost)
            } else {
                remove("warp_last_success_endpoint")
            }
            apply()
        }
    }

    fun saveStableLastSuccessParams(
        port: Int,
        protocol: String,
        endpointHost: String? = null,
        modeName: String? = null,
        underlyingSignature: String? = null,
        networkClass: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        prefs.edit().apply {
            putInt("stable_last_success_port", port)
            putString("stable_last_success_protocol", protocol)
            putLong("stable_last_success_at", nowMs)
            if (!modeName.isNullOrBlank()) {
                putString("stable_last_success_mode", modeName)
            } else {
                remove("stable_last_success_mode")
            }
            if (!endpointHost.isNullOrBlank()) {
                putString("stable_last_success_endpoint", endpointHost)
            } else {
                remove("stable_last_success_endpoint")
            }
            val normalizedSignature = underlyingSignature?.trim().orEmpty()
            if (normalizedSignature.isNotBlank()) {
                putString("stable_last_success_network_signature", normalizedSignature)
            } else {
                remove("stable_last_success_network_signature")
            }
            val normalizedNetworkClass = normalizeStableSuccessNetworkClass(networkClass)
            if (normalizedNetworkClass != null) {
                putInt("${normalizedNetworkClass}_stable_last_success_port", port)
                putString("${normalizedNetworkClass}_stable_last_success_protocol", protocol)
                putLong("${normalizedNetworkClass}_stable_last_success_at", nowMs)
                if (!modeName.isNullOrBlank()) {
                    putString("${normalizedNetworkClass}_stable_last_success_mode", modeName)
                } else {
                    remove("${normalizedNetworkClass}_stable_last_success_mode")
                }
                if (!endpointHost.isNullOrBlank()) {
                    putString("${normalizedNetworkClass}_stable_last_success_endpoint", endpointHost)
                } else {
                    remove("${normalizedNetworkClass}_stable_last_success_endpoint")
                }
                if (normalizedSignature.isNotBlank()) {
                    putString("${normalizedNetworkClass}_stable_last_success_network_signature", normalizedSignature)
                } else {
                    remove("${normalizedNetworkClass}_stable_last_success_network_signature")
                }
            }
            apply()
        }
    }

    private fun writeServiceStateFile(
        state: String,
        backend: String,
        attemptOrdinal: Int,
        attemptTotal: Int,
        transport: String,
        notice: String,
        registering: Boolean,
    ) {
        val payload = JSONObject().apply {
            put("state", state)
            put("backend", backend)
            put("transport", transport)
            put("notice", notice)
            put("registering", registering)
            put("attempt_ordinal", attemptOrdinal.coerceAtLeast(0))
            put("attempt_total", attemptTotal.coerceAtLeast(0))
            put("updated_at", System.currentTimeMillis())
        }.toString().toByteArray(Charsets.UTF_8)
        val stream = try {
            serviceStateFile.startWrite()
        } catch (_: Exception) {
            return
        }
        try {
            stream.write(payload)
            stream.flush()
            serviceStateFile.finishWrite(stream)
        } catch (_: Exception) {
            serviceStateFile.failWrite(stream)
        }
    }

    private fun readServiceStateFile(): JSONObject {
        return try {
            val bytes = serviceStateFile.readFully()
            if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun writeAtomicRaw(file: AtomicFile, raw: String) {
        val payload = raw.toByteArray(Charsets.UTF_8)
        val stream = try {
            file.startWrite()
        } catch (_: Exception) {
            return
        }
        try {
            stream.write(payload)
            stream.flush()
            file.finishWrite(stream)
        } catch (_: Exception) {
            file.failWrite(stream)
        }
    }

    private fun readAtomicRaw(file: AtomicFile): String {
        return try {
            val bytes = file.readFully()
            if (bytes.isEmpty()) "" else String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun readAtomicJson(file: AtomicFile): JSONObject? {
        val raw = readAtomicRaw(file)
        if (raw.isBlank()) return null
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeWarpDiscoveryStateFile(raw: String) {
        val payload = raw.toByteArray(Charsets.UTF_8)
        val stream = try {
            warpDiscoveryStateFile.startWrite()
        } catch (_: Exception) {
            return
        }
        try {
            stream.write(payload)
            stream.flush()
            warpDiscoveryStateFile.finishWrite(stream)
        } catch (_: Exception) {
            warpDiscoveryStateFile.failWrite(stream)
        }
    }

    private fun readWarpDiscoveryStateFile(): String {
        return try {
            val bytes = warpDiscoveryStateFile.readFully()
            if (bytes.isEmpty()) "" else String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun updateTrafficMaskStateFile(
        activeHost: String? = UNSET_SENTINEL,
        activePool: String? = UNSET_SENTINEL,
        warpActiveHost: String? = UNSET_SENTINEL,
        recentProbeHost: String? = UNSET_SENTINEL,
        recentProbePool: String? = UNSET_SENTINEL,
    ) {
        val current = readTrafficMaskStateFile()
        fun applyValue(key: String, value: String?, normalizePool: Boolean = false) {
            when {
                value === UNSET_SENTINEL -> Unit
                value.isNullOrBlank() -> current.remove(key)
                normalizePool -> current.put(key, normalizeTrafficMaskPool(value))
                else -> current.put(key, value)
            }
        }
        applyValue("active_host", activeHost)
        applyValue("active_pool", activePool, normalizePool = true)
        applyValue("warp_active_host", warpActiveHost)
        applyValue("recent_probe_host", recentProbeHost)
        applyValue("recent_probe_pool", recentProbePool, normalizePool = true)
        current.put("updated_at", System.currentTimeMillis())
        writeTrafficMaskStateFile(current)
    }

    private fun clearTrafficMaskStateFile() {
        writeTrafficMaskStateFile(JSONObject())
    }

    private fun writeTrafficMaskStateFile(json: JSONObject) {
        val payload = json.toString().toByteArray(Charsets.UTF_8)
        val stream = try {
            trafficMaskStateFile.startWrite()
        } catch (_: Exception) {
            return
        }
        try {
            stream.write(payload)
            stream.flush()
            trafficMaskStateFile.finishWrite(stream)
        } catch (_: Exception) {
            trafficMaskStateFile.failWrite(stream)
        }
    }

    private fun readTrafficMaskStateFile(): JSONObject {
        return try {
            val bytes = trafficMaskStateFile.readFully()
            if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun generateLocalProxyToken(length: Int = 4): String {
        return buildString(length) {
            repeat(length) {
                append(LOCAL_PROXY_TOKEN_ALPHABET[localProxySecureRandom.nextInt(LOCAL_PROXY_TOKEN_ALPHABET.length)])
            }
        }
    }

    private fun normalizeStableSuccessNetworkClass(networkClass: String?): String? {
        return when (networkClass?.trim()?.lowercase(Locale.US)) {
            "wifi" -> "wifi"
            "cell", "cellular", "mobile" -> "cell"
            "eth", "ethernet" -> "eth"
            "other" -> "other"
            else -> null
        }
    }

    fun clearLastSuccess() {
        val scopedPrefixes = listOf(
            "wifi_stable_last_success_",
            "cell_stable_last_success_",
            "eth_stable_last_success_",
            "other_stable_last_success_",
        )
        prefs.edit().apply {
            remove("last_success_port")
            remove("last_success_protocol")
            remove("last_success_at")
            remove("last_success_endpoint")
            remove("last_success_mode")
            remove("warp_last_success_port")
            remove("warp_last_success_protocol")
            remove("warp_last_success_at")
            remove("warp_last_success_endpoint")
            remove("warp_last_success_mode")
            remove("stable_last_success_port")
            remove("stable_last_success_protocol")
            remove("stable_last_success_at")
            remove("stable_last_success_endpoint")
            remove("stable_last_success_mode")
            remove("stable_last_success_network_signature")
            prefs.all.keys
                .filter { key -> scopedPrefixes.any(key::startsWith) }
                .forEach(::remove)
            apply()
        }
    }

    fun clearLastSuccessIfProtocol(protocol: String?) {
        val expected = protocol?.trim().orEmpty()
        if (expected.isBlank()) return
        val current = getLastSuccessProtocol()
        if (current.equals(expected, ignoreCase = true)) {
            clearLastSuccess()
        }
    }

    fun clearLastSuccessIfMatches(
        mode: String,
        host: String,
        port: Int,
        protocol: String? = null,
    ) {
        if (port !in 1..65535) return
        val expectedMode = normalizeToken(mode)
        val expectedHost = normalizeHost(host)
        val expectedProtocol = protocol?.let(::normalizeToken).orEmpty()
        if (expectedMode.isBlank() || expectedHost.isBlank()) return

        fun matches(itemMode: String?, itemHost: String?, itemPort: Int, itemProtocol: String?): Boolean {
            if (itemPort != port) return false
            if (!normalizeHost(itemHost).equals(expectedHost, ignoreCase = true)) return false
            if (!normalizeToken(itemMode).equals(expectedMode, ignoreCase = true)) return false
            if (expectedProtocol.isNotBlank() && !normalizeToken(itemProtocol).equals(expectedProtocol, ignoreCase = true)) {
                return false
            }
            return true
        }

        val scopedClasses = listOf("wifi", "cell", "eth", "other")
        val hasMatch =
            matches(getLastSuccessMode(), getLastSuccessEndpoint(), getLastSuccessPort(), getLastSuccessProtocol()) ||
                matches(getStableLastSuccessMode(), getStableLastSuccessEndpoint(), getStableLastSuccessPort(), getStableLastSuccessProtocol()) ||
                scopedClasses.any { networkClass ->
                    matches(
                        getStableLastSuccessMode(networkClass),
                        getStableLastSuccessEndpoint(networkClass),
                        getStableLastSuccessPort(networkClass),
                        getStableLastSuccessProtocol(networkClass),
                    )
                }
        if (hasMatch) {
            clearLastSuccess()
        }
    }

    fun getLastSuccessProtocol(): String = prefs.getString("last_success_protocol", "MASQUE") ?: "MASQUE"
    fun getLastSuccessEndpoint(): String? = prefs.getString("last_success_endpoint", null)
    fun getLastSuccessMode(): String = prefs.getString("last_success_mode", getLastSuccessProtocol()) ?: getLastSuccessProtocol()
    fun getLastSuccessAt(): Long = prefs.getLong("last_success_at", 0L)
    fun getWarpLastSuccessPort(): Int = prefs.getInt("warp_last_success_port", -1)
    fun getWarpLastSuccessProtocol(): String? = prefs.getString("warp_last_success_protocol", null)
    fun getWarpLastSuccessEndpoint(): String? = prefs.getString("warp_last_success_endpoint", null)
    fun getWarpLastSuccessMode(): String? = prefs.getString("warp_last_success_mode", null)
    fun getWarpLastSuccessAt(): Long = prefs.getLong("warp_last_success_at", 0L)
    fun getStableLastSuccessPort(): Int = prefs.getInt("stable_last_success_port", -1)
    fun getStableLastSuccessProtocol(): String? = prefs.getString("stable_last_success_protocol", null)
    fun getStableLastSuccessEndpoint(): String? = prefs.getString("stable_last_success_endpoint", null)
    fun getStableLastSuccessMode(): String? = prefs.getString("stable_last_success_mode", null)
    fun getStableLastSuccessAt(): Long = prefs.getLong("stable_last_success_at", 0L)
    fun getStableLastSuccessNetworkSignature(): String =
        prefs.getString("stable_last_success_network_signature", "").orEmpty()
    fun getStableLastSuccessPort(networkClass: String): Int {
        val normalized = normalizeStableSuccessNetworkClass(networkClass) ?: return -1
        return prefs.getInt("${normalized}_stable_last_success_port", -1)
    }
    fun getStableLastSuccessProtocol(networkClass: String): String? {
        val normalized = normalizeStableSuccessNetworkClass(networkClass) ?: return null
        return prefs.getString("${normalized}_stable_last_success_protocol", null)
    }
    fun getStableLastSuccessEndpoint(networkClass: String): String? {
        val normalized = normalizeStableSuccessNetworkClass(networkClass) ?: return null
        return prefs.getString("${normalized}_stable_last_success_endpoint", null)
    }
    fun getStableLastSuccessMode(networkClass: String): String? {
        val normalized = normalizeStableSuccessNetworkClass(networkClass) ?: return null
        return prefs.getString("${normalized}_stable_last_success_mode", null)
    }
    fun getStableLastSuccessAt(networkClass: String): Long {
        val normalized = normalizeStableSuccessNetworkClass(networkClass) ?: return 0L
        return prefs.getLong("${normalized}_stable_last_success_at", 0L)
    }
    fun getStableLastSuccessNetworkSignature(networkClass: String): String {
        val normalized = normalizeStableSuccessNetworkClass(networkClass) ?: return ""
        return prefs.getString("${normalized}_stable_last_success_network_signature", "").orEmpty()
    }
    fun getLastExitColo(): String {
        val fileJson = readAtomicJson(lastExitObservationFile)
        return fileJson?.optString("colo")?.takeIf { it.isNotBlank() }
            ?: (prefs.getString("last_exit_colo", "") ?: "")
    }
    fun getLastExitIp(): String {
        val fileJson = readAtomicJson(lastExitObservationFile)
        return fileJson?.optString("ip")?.takeIf { it.isNotBlank() }
            ?: (prefs.getString("last_exit_ip", "") ?: "")
    }
    fun getLastExitCountry(): String {
        val fileJson = readAtomicJson(lastExitObservationFile)
        return fileJson?.optString("country")?.takeIf { it.isNotBlank() }
            ?: (prefs.getString("last_exit_country", "") ?: "")
    }
    fun hasFreshLastSuccess(nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastSuccessAt = getLastSuccessAt()
        if (lastSuccessAt <= 0L) return false
        return nowMs - lastSuccessAt <= LAST_SUCCESS_FRESH_MS
    }
    fun hasFreshStableLastSuccess(nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastSuccessAt = getStableLastSuccessAt()
        if (lastSuccessAt <= 0L) return false
        return nowMs - lastSuccessAt <= LAST_SUCCESS_FRESH_MS
    }
    fun hasFreshWarpLastSuccess(nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastSuccessAt = getWarpLastSuccessAt()
        if (lastSuccessAt <= 0L) return false
        return nowMs - lastSuccessAt <= LAST_SUCCESS_FRESH_MS
    }
    fun hasFreshStableLastSuccess(networkClass: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastSuccessAt = getStableLastSuccessAt(networkClass)
        if (lastSuccessAt <= 0L) return false
        return nowMs - lastSuccessAt <= LAST_SUCCESS_FRESH_MS
    }
    fun getAccessToken(): String? =
        prefs.getString("access_token", null)?.takeIf { it.isNotBlank() }
            ?: getReserveWarpIdentity()?.accessToken?.takeIf { it.isNotBlank() }
    fun getDeviceId(): String? =
        prefs.getString("device_id", null)?.takeIf { it.isNotBlank() }
            ?: getReserveWarpIdentity()?.deviceId?.takeIf { it.isNotBlank() }

    /**
     * Запасная WARP-личность, добытая в фоне через уже поднятый туннель.
     *
     * Лежит отдельно от активной конфигурации намеренно: подменять активную
     * означало бы вмешаться в текущее подключение. Отсюда берутся только
     * access token и device id — их требует регистрация MASQUE, и без них
     * выбранный MASQUE не запускается вовсе.
     */
    fun getReserveWarpIdentity(): WarpConfig? {
        val raw = prefs.getString("reserve_warp_identity_json", null)?.takeIf { it.isNotBlank() }
            ?: return null
        return try {
            val json = JSONObject(raw)
            WarpConfig(
                privateKey = json.optString("private_key"),
                publicKey = json.optString("public_key"),
                ipv4 = json.optString("ipv4"),
                ipv6 = json.optString("ipv6"),
                peerPublicKey = json.optString("peer_pub"),
                peerEndpoint = json.optString("peer_endpoint"),
                reserved = json.optString("reserved").takeIf { it.isNotBlank() },
                accessToken = json.optString("access_token"),
                deviceId = json.optString("device_id"),
                license = json.optString("license").takeIf { it.isNotBlank() },
                masqueConfigJson = null,
            ).takeIf { !it.accessToken.isNullOrBlank() && !it.deviceId.isNullOrBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun saveReserveWarpIdentity(config: WarpConfig, nowMs: Long = System.currentTimeMillis()) {
        if (config.accessToken.isNullOrBlank() || config.deviceId.isNullOrBlank()) return
        val json = JSONObject().apply {
            put("private_key", config.privateKey)
            put("public_key", config.publicKey)
            put("ipv4", config.ipv4)
            put("ipv6", config.ipv6)
            put("peer_pub", config.peerPublicKey)
            put("peer_endpoint", config.peerEndpoint)
            put("reserved", normalizeReservedValue(config.reserved).orEmpty())
            put("access_token", config.accessToken.orEmpty())
            put("device_id", config.deviceId.orEmpty())
            put("license", config.license.orEmpty())
            put("obtained_at", nowMs)
        }
        prefs.edit()
            .putString("reserve_warp_identity_json", json.toString())
            .remove("warp_identity_backfill_failed_at")
            .commit()
    }

    /**
     * Забыть запасную личность, чтобы следующая фоновая регистрация завела новую.
     *
     * Дефект, ради которого это появилось: личность заводилась один раз и жила вечно. При
     * отказе Cloudflare приложение перевыпускало поверх неё только MASQUE-ключ — то есть
     * снова и снова подписывалось устройством, которое сервер уже не признаёт. Замер на
     * тестовое устройство: три перевыпуска ключа подряд, все три отвергнуты `tls: access denied` со
     * своего же адреса. Снаружи это выглядело как «подключился по Авто, а MASQUE всё
     * равно не работает»: совет, который приложение само и даёт, переставал помогать.
     *
     * Заодно снимаем шестичасовую паузу фоновой регистрации: отказ ключа — это новое
     * обстоятельство, а не повтор той же неудачи.
     */
    fun clearReserveWarpIdentity() {
        prefs.edit()
            .remove("reserve_warp_identity_json")
            .remove("warp_identity_backfill_failed_at")
            .commit()
    }

    /**
     * Работа нужна, пока нет готового MASQUE-профиля: без него выбранный
     * MASQUE не стартует, а получить его можно только через живой туннель.
     *
     * Пауза между повторами была шесть часов — под задачу «молчаливое удобство в фоне».
     * Но личность оказалась не удобством, а обязательным условием для протокола, который
     * пользователь выбирает руками: одна неудачная регистрация выключала MASQUE до конца
     * дня, и подсказка «подключитесь по Авто» переставала работать. Замер на тестовом устройстве:
     * регистрация через туннель не прошла с первого раза и назначила себе шесть часов.
     * Сама операция — один HTTPS-запрос по уже поднятому туннелю, столько ждать незачем.
     */
    private val WARP_IDENTITY_BACKFILL_RETRY_MS = 15L * 60L * 1000L

    /** @see WARP_IDENTITY_BACKFILL_RETRY_MS */
    fun shouldAttemptWarpIdentityBackfill(
        nowMs: Long = System.currentTimeMillis(),
        ignoreCooldown: Boolean = false,
    ): Boolean {
        if (!getMasqueConfigJson().isNullOrBlank()) return false
        // Пауза после неудачи защищает от молчаливых повторов в фоне. Но когда MASQUE
        // выбран в списке протоколов, повтор перестаёт быть молчаливым: пользователь сам
        // нажал «подключить» и ждёт результата. Шесть часов ожидания в этом случае
        // означают, что совет «подключитесь по Авто» — а его приложение само и даёт при
        // отказе ключа — не работает, и понять почему нельзя ниоткуда.
        if (ignoreCooldown) return true
        val lastFailedAt = prefs.getLong("warp_identity_backfill_failed_at", 0L)
        if (lastFailedAt <= 0L) return true
        return nowMs - lastFailedAt >= WARP_IDENTITY_BACKFILL_RETRY_MS
    }

    fun markWarpIdentityBackfillFailure(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("warp_identity_backfill_failed_at", nowMs).commit()
    }
    /**
     * Ключ MASQUE читается из файла; из настроек он переносится ровно один раз.
     *
     * Если файл уже есть, но не прочитался (идёт запись, повреждение) — возвращаем
     * пустоту, а не значение из настроек: там лежит зеркало, которое любой `commit()`
     * соседнего процесса мог откатить, то есть ровно тот дефект, ради которого ключ и
     * переехал в файл.
     */
    fun getMasqueConfigJson(): String? {
        val stored = readAtomicRaw(masqueConfigFile).trim()
        if (stored.isNotEmpty()) return stored
        if (masqueConfigFile.baseFile.exists()) return null
        val legacy = prefs.getString("masque_config_json", null)?.trim().orEmpty()
        if (legacy.isEmpty()) return null
        writeAtomicRaw(masqueConfigFile, legacy)
        prefs.edit().remove("masque_config_json").commit()
        return legacy
    }

    /**
     * Лицензия WARP+, введённая пользователем.
     *
     * Хранится отдельно от личности и переживает её сброс: личность приложение
     * перевыпускает само при отказе Cloudflare, а лицензию вводят руками, и терять её при
     * каждом перевыпуске нельзя. При регистрации нового устройства лицензия применяется
     * заново — иначе оно снова окажется бесплатным.
     */
    fun getWarpPlusLicense(): String = prefs.getString("warp_plus_license", "").orEmpty().trim()

    fun setWarpPlusLicense(license: String) {
        val normalized = license.trim()
        prefs.edit().apply {
            if (normalized.isEmpty()) remove("warp_plus_license") else putString("warp_plus_license", normalized)
        }.commit()
    }

    /** Тип аккаунта, каким его вернул сервер при последней привязке лицензии. */
    fun getWarpAccountType(): String = prefs.getString("warp_account_type", "").orEmpty().trim()

    fun setWarpAccountType(accountType: String) {
        prefs.edit().putString("warp_account_type", accountType.trim()).commit()
    }

    fun saveTunnelUiSnapshot(
        ipv4: String?,
        ipv6: String?,
        country: String?,
        backend: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val snapshot = JSONObject().apply {
            put("ipv4", ipv4?.trim().orEmpty())
            put("ipv6", ipv6?.trim().orEmpty())
            put("country", country?.trim()?.uppercase().orEmpty())
            put("backend", backend?.trim().orEmpty().ifBlank { NovaVpnService.BACKEND_WARP })
            put("observed_at", nowMs)
        }
        val raw = snapshot.toString()
        prefs.edit().putString("tunnel_ui_snapshot", raw).commit()
        writeAtomicRaw(tunnelUiSnapshotFile, raw)
    }

    /**
     * Публикует задержку, измеренную службой на своём транспорте.
     *
     * Даром: проба живости узла и так идёт раз в полторы секунды, остаётся засечь её
     * длительность. Экран сам замерить не может — при раздельном туннелировании он
     * снаружи VPN, а порт SOCKS-инбаунда ядра ему неизвестен.
     */
    fun publishTransportLatency(
        latencyMs: Int,
        transport: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        writeAtomicRaw(
            transportLatencyFile,
            JSONObject().apply {
                put("latency_ms", latencyMs)
                put("transport", transport.trim().uppercase(Locale.US))
                put("observed_at", nowMs)
            }.toString(),
        )
    }

    fun clearTransportLatency() {
        writeAtomicRaw(transportLatencyFile, "")
    }

    /** Свежий замер службы или null, если его нет или он протух. */
    fun getTransportLatency(
        freshnessMs: Long = 8_000L,
        nowMs: Long = System.currentTimeMillis(),
    ): TransportLatencySample? {
        val json = readAtomicJson(transportLatencyFile) ?: return null
        val observedAt = json.optLong("observed_at", 0L)
        if (observedAt <= 0L) return null
        if (nowMs - observedAt > freshnessMs.coerceAtLeast(1_000L)) return null
        val latencyMs = json.optInt("latency_ms", -1)
        if (latencyMs < 0) return null
        return TransportLatencySample(
            latencyMs = latencyMs,
            transport = json.optString("transport").orEmpty(),
            observedAt = observedAt,
        )
    }

    fun saveDirectUiSnapshot(
        ipv4: String?,
        ipv6: String?,
        country: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val snapshot = JSONObject().apply {
            put("ipv4", ipv4?.trim().orEmpty())
            put("ipv6", ipv6?.trim().orEmpty())
            put("country", country?.trim()?.uppercase().orEmpty())
            put("observed_at", nowMs)
        }
        prefs.edit().putString("direct_ui_snapshot", snapshot.toString()).apply()
    }

    fun getTunnelUiSnapshot(): TunnelUiSnapshot? {
        val raw = readAtomicRaw(tunnelUiSnapshotFile).takeIf { it.isNotBlank() }
            ?: prefs.getString("tunnel_ui_snapshot", null).orEmpty()
        if (raw.isBlank()) return null
        return try {
            val json = JSONObject(raw)
            TunnelUiSnapshot(
                ipv4 = json.optString("ipv4").orEmpty(),
                ipv6 = json.optString("ipv6").orEmpty(),
                country = json.optString("country").orEmpty(),
                backend = json.optString("backend").ifBlank { NovaVpnService.BACKEND_WARP },
                observedAt = json.optLong("observed_at", 0L),
            ).takeIf {
                it.ipv4.isNotBlank() || it.ipv6.isNotBlank() || it.country.isNotBlank()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getDirectUiSnapshot(): DirectUiSnapshot? {
        val raw = prefs.getString("direct_ui_snapshot", null).orEmpty()
        if (raw.isBlank()) return null
        return try {
            val json = JSONObject(raw)
            DirectUiSnapshot(
                ipv4 = json.optString("ipv4").orEmpty(),
                ipv6 = json.optString("ipv6").orEmpty(),
                country = json.optString("country").orEmpty(),
                observedAt = json.optLong("observed_at", 0L),
            ).takeIf {
                it.ipv4.isNotBlank() || it.ipv6.isNotBlank() || it.country.isNotBlank()
            }
        } catch (_: Exception) {
            null
        }
    }

    fun saveWarpDiscoverySnapshot(
        running: Boolean,
        foundCount: Int,
        message: String,
        ordinal: Int,
        total: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val snapshot = JSONObject().apply {
            put("running", running)
            put("found_count", foundCount.coerceAtLeast(0))
            put("message", message)
            put("ordinal", ordinal.coerceAtLeast(0))
            put("total", total.coerceAtLeast(0))
            put("observed_at", nowMs)
        }
        val raw = snapshot.toString()
        prefs.edit().putString("warp_discovery_snapshot", raw).commit()
        writeWarpDiscoveryStateFile(raw)
    }

    fun getWarpDiscoverySnapshot(): WarpDiscoverySnapshot? {
        val raw = readWarpDiscoveryStateFile().takeIf { it.isNotBlank() }
            ?: prefs.getString("warp_discovery_snapshot", null).orEmpty()
        if (raw.isBlank()) return null
        return try {
            val json = JSONObject(raw)
            WarpDiscoverySnapshot(
                running = json.optBoolean("running", false),
                foundCount = json.optInt("found_count", 0).coerceAtLeast(0),
                message = json.optString("message").orEmpty(),
                ordinal = json.optInt("ordinal", 0).coerceAtLeast(0),
                total = json.optInt("total", 0).coerceAtLeast(0),
                observedAt = json.optLong("observed_at", 0L),
            )
        } catch (_: Exception) {
            null
        }
    }

    fun clearTunnelUiSnapshot() {
        prefs.edit().remove("tunnel_ui_snapshot").commit()
        writeAtomicRaw(tunnelUiSnapshotFile, "")
    }

    fun isExactFreshWarpVerifiedLastSuccessMatch(
        item: WarpVerifiedConfig,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val normalizedEngine = normalizeToken(item.engine)
        val normalizedMode = normalizeToken(item.mode)
        val normalizedHost = normalizeHost(item.host)
        if (normalizedEngine.isBlank() || normalizedMode.isBlank() || normalizedHost.isBlank() || item.port !in 1..65535) {
            return false
        }
        val stableLastSuccessFresh = hasFreshStableLastSuccess(nowMs)
        val freshLastSuccess = if (stableLastSuccessFresh) {
            true
        } else {
            hasFreshLastSuccess(nowMs)
        }
        if (!freshLastSuccess) return false
        val preferredSuccessHost = normalizeHost(
            if (stableLastSuccessFresh) getStableLastSuccessEndpoint() else getLastSuccessEndpoint()
        )
        val preferredSuccessPort = if (stableLastSuccessFresh) {
            getStableLastSuccessPort()
        } else {
            getLastSuccessPort()
        }
        val preferredSuccessProtocol = normalizeToken(
            if (stableLastSuccessFresh) {
                getStableLastSuccessProtocol().orEmpty()
            } else {
                getLastSuccessProtocol()
            }
        )
        val preferredSuccessMode = normalizeToken(
            if (stableLastSuccessFresh) {
                getStableLastSuccessMode().orEmpty().ifBlank {
                    getStableLastSuccessProtocol().orEmpty()
                }
            } else {
                getLastSuccessMode()
            }
        )
        return normalizedHost == preferredSuccessHost &&
            item.port == preferredSuccessPort &&
            (
                normalizedMode == preferredSuccessMode ||
                    normalizedEngine == preferredSuccessProtocol ||
                    normalizedMode == preferredSuccessProtocol
                )
    }

    private fun hasWarpVerifiedConfigProvenHistory(item: WarpVerifiedConfig): Boolean {
        val stats = readWarpVerifiedExactStats(item)
        return stats.stableSuccesses > 0 ||
            stats.successes > 0 ||
            item.successCount >= 2 ||
            item.lastVerifiedAt > 0L
    }

    private fun bestEffectiveWarpPortStat(item: WarpVerifiedConfig): WarpPortStat? {
        val stats = sortWarpPortStats(item.preferredPorts, item.port)
        if (stats.isEmpty()) return null
        return stats.minWithOrNull(
            compareBy<WarpPortStat>(
                { if (it.pingSuccesses > 0) 0 else 1 },
                { it.failureCount },
                {
                    if (it.pingSuccesses > 0 && it.avgPingMs > 0.0) {
                        it.avgPingMs
                    } else {
                        Double.MAX_VALUE
                    }
                },
                { -it.pingSuccesses },
                { -it.successCount },
                { if (it.port == item.port) 0 else 1 },
                { it.port },
            ),
        )
    }

    private fun effectiveWarpQualityPingSuccesses(item: WarpVerifiedConfig): Int {
        if (item.qualityPingSuccesses > 0) return item.qualityPingSuccesses
        return bestEffectiveWarpPortStat(item)?.pingSuccesses ?: 0
    }

    private fun effectiveWarpQualityProbeCount(item: WarpVerifiedConfig): Int {
        if (item.qualityProbeCount > 0) return item.qualityProbeCount
        return bestEffectiveWarpPortStat(item)?.probeCount ?: 0
    }

    private fun effectiveWarpQualityAvgPingMs(item: WarpVerifiedConfig): Double {
        if (item.qualityPingSuccesses > 0 && item.qualityAvgPingMs > 0.0) return item.qualityAvgPingMs
        val bestPort = bestEffectiveWarpPortStat(item)
        return if (bestPort != null && bestPort.pingSuccesses > 0 && bestPort.avgPingMs > 0.0) {
            bestPort.avgPingMs
        } else {
            0.0
        }
    }

    private fun effectiveWarpQualityFailureCount(item: WarpVerifiedConfig): Int {
        if (item.qualityFailureCount > 0) return item.qualityFailureCount
        return bestEffectiveWarpPortStat(item)?.failureCount ?: 0
    }

    /**
     * Среднее число перерукопожатий за окно замера. −1, если данных ещё нет.
     *
     * Здоровая сессия WireGuard меняет ключ раз в две минуты — при двухминутном
     * окне это 1. Всё, что заметно выше, означает потерянный обратный поток.
     */
    fun warpConfigChurnPerWindow(item: WarpVerifiedConfig): Double {
        if (item.churnWindows < WARP_CHURN_MIN_WINDOWS) return -1.0
        return item.churnRekeys.toDouble() / item.churnWindows.toDouble()
    }

    /**
     * Копит замер перерукопожатий для узла.
     *
     * Зовётся только по показательным окнам — тем, где через туннель реально шёл
     * трафик. Молчащее окно даёт ноль само по себе и оценку бы завысило.
     */
    fun recordWarpConfigChurn(
        host: String,
        port: Int,
        rekeys: Int,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(warpVerifiedConfigsLock) {
            val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
            if (normalizedHost.isBlank() || port !in 1..65535 || rekeys < 0) return
            val existing = getWarpVerifiedConfigs().associateBy { it.id }.toMutableMap()
            var touched = false
            existing.entries.forEach { entry ->
                val item = entry.value
                val sameHost = item.host.trim().removePrefix("[").removeSuffix("]")
                    .equals(normalizedHost, ignoreCase = true)
                if (!sameHost || item.port != port) return@forEach
                touched = true
                // Окно старения: держим порядка WARP_CHURN_MAX_WINDOWS последних
                // замеров. Без него узел, испортившийся месяц назад, не отмоется
                // никогда, а сеть у телефона меняется чаще.
                // `qualityLastCheckedAt` здесь намеренно не двигается. Раньше двигался —
                // и тихо продлевал жизнь пинговому замеру, которого никто не перемерял:
                // эта отметка задаёт свежесть сразу трёх качественных слагаемых и через
                // `isWarpVerifiedConfigDegraded` попадает в ранний ключ сортировки.
                // Замер удержания о пинге не знает ничего и подтверждать его не вправе.
                entry.setValue(
                    if (item.churnWindows >= WARP_CHURN_MAX_WINDOWS) {
                        item.copy(
                            churnWindows = item.churnWindows / 2 + 1,
                            churnRekeys = item.churnRekeys / 2 + rekeys,
                        )
                    } else {
                        item.copy(
                            churnWindows = item.churnWindows + 1,
                            churnRekeys = item.churnRekeys + rekeys,
                        )
                    }
                )
            }
            if (!touched) return
            saveWarpVerifiedConfigs(existing.values.toList())
        }
    }

    /**
     * Тишина обратного потока узла, приведённая к общей длительности окна.
     * −1, если замера нет. Подробности — в [SessionHoldMetric].
     */
    fun warpConfigHoldStallMs(item: WarpVerifiedConfig): Double {
        return SessionHoldMetric.normalizedStallMs(
            windows = item.holdWindows,
            stallMs = item.holdStallMs,
            spanMs = item.holdSpanMs,
        )
    }

    /**
     * Грубая оценка удержания узла — то, что участвует в порядке перебора.
     *
     * Именно грубая: одно окно на профиль за прогон адаптации — это одно
     * наблюдение, и точная сортировка по нему выдала бы за свойство узла то, в
     * какую минуту прогона он попал.
     */
    fun warpConfigHoldGrade(
        item: WarpVerifiedConfig,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        return SessionHoldMetric.grade(
            windows = item.holdWindows,
            stallMs = item.holdStallMs,
            spanMs = item.holdSpanMs,
            checkedAtMs = item.holdCheckedAt,
            nowMs = nowMs,
        )
    }

    /**
     * Копит замер удержания для узла.
     *
     * Адресуется парой host+port, без учёта режима, — как и churn: тишина
     * обратного потока описывает путь до узла, а не форму пакетов клиента.
     * Замер 2026-08-10 это и показал: параметры AWG у всех девяноста шести
     * встроенных профилей одинаковые, а поведение узлов разное.
     *
     * Зовётся только по показательным окнам, см. [SessionHoldMetric.Window].
     */
    fun recordWarpConfigHoldWindow(
        host: String,
        port: Int,
        window: SessionHoldMetric.Window,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(warpVerifiedConfigsLock) {
            val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
            if (normalizedHost.isBlank() || port !in 1..65535) return false
            if (!window.representative) return false
            val existing = getWarpVerifiedConfigs().associateBy { it.id }.toMutableMap()
            var touched = false
            existing.entries.forEach { entry ->
                val item = entry.value
                val sameHost = item.host.trim().removePrefix("[").removeSuffix("]")
                    .equals(normalizedHost, ignoreCase = true)
                if (!sameHost || item.port != port) return@forEach
                touched = true
                val accumulated = SessionHoldMetric.accumulate(
                    previousWindows = item.holdWindows,
                    previousStallMs = item.holdStallMs,
                    previousSpanMs = item.holdSpanMs,
                    window = window,
                )
                entry.setValue(
                    item.copy(
                        holdWindows = accumulated.windows,
                        holdStallMs = accumulated.stallMs,
                        holdSpanMs = accumulated.spanMs,
                        holdCheckedAt = maxOf(item.holdCheckedAt, nowMs),
                    )
                )
            }
            if (!touched) return false
            saveWarpVerifiedConfigs(existing.values.toList())
            return true
        }
    }

    private fun effectiveWarpQualityLastCheckedAt(item: WarpVerifiedConfig): Long {
        return maxOf(item.qualityLastCheckedAt, bestEffectiveWarpPortStat(item)?.lastCheckedAt ?: 0L)
    }

    fun isWarpVerifiedConfigDegraded(
        item: WarpVerifiedConfig,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val effectiveLastCheckedAt = effectiveWarpQualityLastCheckedAt(item)
        if (effectiveLastCheckedAt <= 0L) return false
        val qualityAgeMs = (nowMs - effectiveLastCheckedAt).coerceAtLeast(0L)
        if (qualityAgeMs > 6L * 60L * 60L * 1000L) return false
        if (!hasWarpVerifiedConfigProvenHistory(item)) return false
        val effectiveFailures = effectiveWarpQualityFailureCount(item)
        val effectiveProbeCount = effectiveWarpQualityProbeCount(item)
        val effectivePingSuccesses = effectiveWarpQualityPingSuccesses(item)
        val effectiveAvgPingMs = effectiveWarpQualityAvgPingMs(item)
        val recentQualityFailure =
            effectiveFailures > 0 &&
                effectiveLastCheckedAt >= item.lastVerifiedAt
        if (!recentQualityFailure) return false
        val lowPingCoverage =
            effectiveProbeCount >= 4 &&
                effectivePingSuccesses > 0 &&
                effectivePingSuccesses * 100 < effectiveProbeCount * 60
        val tooHighLatency =
            effectivePingSuccesses > 0 &&
                effectiveAvgPingMs > 0.0 &&
                effectiveAvgPingMs >= 420.0
        return !isWarpVerifiedConfigWorking(item, nowMs) || lowPingCoverage || tooHighLatency
    }

    fun getWarpVerifiedQualityTier(
        item: WarpVerifiedConfig,
        nowMs: Long = System.currentTimeMillis(),
    ): Int {
        val degraded = isWarpVerifiedConfigDegraded(item, nowMs)
        return when {
            isWarpVerifiedConfigWorking(item, nowMs) && !degraded -> 2
            degraded -> 1
            else -> 0
        }
    }

    private fun sortWarpVerifiedConfigs(
        items: List<WarpVerifiedConfig>,
        nowMs: Long = System.currentTimeMillis(),
    ): List<WarpVerifiedConfig> {
        return items
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<WarpVerifiedConfig> { !it.manual }
                    .thenByDescending { it.promotedAt }
                    .thenByDescending { getWarpVerifiedQualityTier(it, nowMs) }
                    // Удержание идёт выше пинга, и это не вкусовщина. Пинг у узла,
                    // теряющего обратный поток, нормальный — на нём и держалась
                    // ошибка, ради которой замер заведён. А ниже — числовая оценка,
                    // до которой сравнение почти никогда не доходит: пинг Double и
                    // различается у всех, поэтому слагаемое в оценке само по себе
                    // порядок не меняет.
                    .thenByDescending { warpConfigHoldGrade(it, nowMs) }
                    .thenByDescending { isExactFreshWarpVerifiedLastSuccessMatch(it, nowMs) }
                    .thenByDescending { effectiveWarpQualityPingSuccesses(it) }
                    .thenBy {
                        val effectivePingSuccesses = effectiveWarpQualityPingSuccesses(it)
                        val effectiveAvgPingMs = effectiveWarpQualityAvgPingMs(it)
                        if (effectivePingSuccesses > 0 && effectiveAvgPingMs > 0.0) {
                            effectiveAvgPingMs
                        } else {
                            Double.MAX_VALUE
                        }
                    }
                    .thenBy { effectiveWarpQualityFailureCount(it) }
                    .thenByDescending { getWarpVerifiedPriorityScore(it, nowMs) }
                    .thenByDescending { it.lastVerifiedAt }
                    .thenBy { if (isBundledSeed(it)) it.seedOrder else Int.MAX_VALUE }
                    .thenByDescending { it.userImported }
                    .thenBy { it.mode }
                    .thenBy { it.host }
                    .thenBy { it.port }
                    .thenBy { it.id }
            )
    }

    private fun sortWarpPortStats(
        stats: List<WarpPortStat>,
        primaryPort: Int? = null,
    ): List<WarpPortStat> {
        return stats
            .filter { it.port in 1..65535 }
            .groupBy { it.port }
            .map { (port, entries) ->
                entries.reduce { best, item ->
                    WarpPortStat(
                        port = port,
                        successCount = maxOf(best.successCount, item.successCount),
                        failureCount = minOf(
                            best.failureCount.takeIf { it > 0 } ?: item.failureCount,
                            item.failureCount.takeIf { it > 0 } ?: best.failureCount,
                        ).coerceAtLeast(0),
                        probeCount = maxOf(best.probeCount, item.probeCount),
                        pingSuccesses = maxOf(best.pingSuccesses, item.pingSuccesses),
                        avgPingMs = listOf(best.avgPingMs, item.avgPingMs)
                            .filter { it.isFinite() && it > 0.0 }
                            .minOrNull() ?: 0.0,
                        lastSuccessAt = maxOf(best.lastSuccessAt, item.lastSuccessAt),
                        lastCheckedAt = maxOf(best.lastCheckedAt, item.lastCheckedAt),
                    )
                }
            }
            .sortedWith(
                compareByDescending<WarpPortStat> { it.successCount > 0 || it.pingSuccesses > 0 }
                    .thenByDescending { it.pingSuccesses }
                    .thenBy {
                        if (it.pingSuccesses > 0 && it.avgPingMs > 0.0) it.avgPingMs else Double.MAX_VALUE
                    }
                    .thenByDescending { it.successCount }
                    .thenBy { it.failureCount }
                    .thenByDescending { it.lastSuccessAt }
                    .thenBy { if (primaryPort != null && it.port == primaryPort) 0 else 1 }
                    .thenBy { warpPortPreferenceRank(it.port) }
                    .thenBy { it.port }
            )
    }

    private fun parseWarpPortStats(json: JSONObject, primaryPort: Int): List<WarpPortStat> {
        val array = json.optJSONArray("preferred_ports") ?: return emptyList()
        val parsed = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val port = item.optInt("port", -1)
                if (port !in 1..65535) continue
                add(
                    WarpPortStat(
                        port = port,
                        successCount = item.optInt("success_count", 0).coerceAtLeast(0),
                        failureCount = item.optInt("failure_count", 0).coerceAtLeast(0),
                        probeCount = item.optInt("probe_count", 0).coerceAtLeast(0),
                        pingSuccesses = item.optInt("ping_successes", 0).coerceAtLeast(0),
                        avgPingMs = item.optDouble("avg_ping_ms", 0.0).takeIf { it.isFinite() } ?: 0.0,
                        lastSuccessAt = item.optLong("last_success_at", 0L),
                        lastCheckedAt = item.optLong("last_checked_at", 0L),
                    )
                )
            }
        }
        return sortWarpPortStats(parsed, primaryPort)
    }

    private fun putWarpPortStats(target: JSONObject, stats: List<WarpPortStat>, primaryPort: Int) {
        val sorted = sortWarpPortStats(stats, primaryPort)
        if (sorted.isEmpty()) return
        target.put(
            "preferred_ports",
            JSONArray().apply {
                sorted.forEach { stat ->
                    put(
                        JSONObject().apply {
                            put("port", stat.port)
                            put("success_count", stat.successCount)
                            put("failure_count", stat.failureCount)
                            put("probe_count", stat.probeCount)
                            put("ping_successes", stat.pingSuccesses)
                            put("avg_ping_ms", stat.avgPingMs)
                            put("last_success_at", stat.lastSuccessAt)
                            put("last_checked_at", stat.lastCheckedAt)
                        }
                    )
                }
            },
        )
    }

    private fun updateWarpPortStats(
        existing: List<WarpPortStat>,
        port: Int,
        success: Boolean,
        probeCount: Int = 0,
        pingSuccesses: Int = 0,
        avgPingMs: Double = 0.0,
        nowMs: Long = System.currentTimeMillis(),
    ): List<WarpPortStat> {
        if (port !in 1..65535) return sortWarpPortStats(existing)
        val current = existing.firstOrNull { it.port == port } ?: WarpPortStat(port = port)
        val safeProbeCount = probeCount.coerceAtLeast(0)
        val safePingSuccesses = pingSuccesses.coerceIn(0, safeProbeCount.coerceAtLeast(pingSuccesses))
        val safeAvgPingMs = avgPingMs.takeIf { it.isFinite() && it > 0.0 } ?: current.avgPingMs
        val updated = current.copy(
            successCount = if (success) current.successCount + 1 else current.successCount,
            failureCount = if (success) 0 else current.failureCount + 1,
            probeCount = if (safeProbeCount > 0) safeProbeCount else current.probeCount,
            pingSuccesses = if (safeProbeCount > 0) safePingSuccesses else current.pingSuccesses,
            avgPingMs = if (safePingSuccesses > 0 && safeAvgPingMs > 0.0) safeAvgPingMs else current.avgPingMs,
            lastSuccessAt = if (success) nowMs else current.lastSuccessAt,
            lastCheckedAt = nowMs,
        )
        return sortWarpPortStats(existing.filterNot { it.port == port } + updated, port)
    }

    private fun mergeWarpPortStats(
        previous: WarpVerifiedConfig,
        incoming: WarpVerifiedConfig,
    ): List<WarpPortStat> {
        return sortWarpPortStats(
            previous.preferredPorts + incoming.preferredPorts,
            incoming.port.takeIf { it in 1..65535 } ?: previous.port,
        )
    }

    private fun warpPortStatsForConfig(config: WarpVerifiedConfig): List<WarpPortStat> {
        return sortWarpPortStats(
            listOf(
                WarpPortStat(
                    port = config.port,
                    successCount = config.successCount,
                    failureCount = config.qualityFailureCount,
                    probeCount = config.qualityProbeCount,
                    pingSuccesses = config.qualityPingSuccesses,
                    avgPingMs = config.qualityAvgPingMs,
                    lastSuccessAt = config.lastVerifiedAt,
                    lastCheckedAt = maxOf(config.qualityLastCheckedAt, config.lastVerifiedAt),
                )
            ) + config.preferredPorts,
            config.port,
        )
    }

    private fun warpPortPreferenceRank(port: Int): Int {
        return when (port) {
            500 -> 0
            1701 -> 1
            4500 -> 2
            988 -> 3
            942 -> 4
            934 -> 5
            880 -> 6
            878 -> 7
            894 -> 8
            908 -> 9
            2408 -> 10
            1002 -> 11
            443 -> 12
            8443 -> 13
            4443 -> 14
            8095 -> 15
            else -> 100 + port
        }
    }

    fun getWarpVerifiedMergedConfigs(scope: String? = null): List<WarpVerifiedConfig> {
        val exported = getWarpVerifiedExportSnapshot(scope)
        val persisted = getWarpVerifiedConfigs(scope)
        if (exported.isEmpty()) return persisted
        if (persisted.isEmpty()) {
            val assetBacked = buildBundledVerifiedWarpSeedConfigsFromAsset()
            if (assetBacked.isNotEmpty()) {
                val exportedById = exported.associateBy { it.id }
                val mergedAssetBacked = assetBacked.map { item ->
                    val exportedItem = exportedById[item.id] ?: return@map item
                    mergeWarpVerifiedExportStats(item, exportedItem)
                }
                return sortWarpVerifiedConfigs(mergedAssetBacked)
            }
        }
        val persistedIds = persisted.mapTo(mutableSetOf()) { it.id }
        val safeExported = exported.filter { item ->
            !isBundledSeed(item) || item.id in persistedIds
        }
        val exportedById = safeExported.associateBy { it.id }
        val mergedPersisted = persisted.map { item ->
            val exportedItem = exportedById[item.id] ?: return@map item
            mergeWarpVerifiedExportStats(item, exportedItem)
        }
        val mergedPersistedIds = mergedPersisted.mapTo(mutableSetOf()) { it.id }
        val merged = mergedPersisted + safeExported.filterNot { it.id in mergedPersistedIds }
        return sortWarpVerifiedConfigs(merged)
    }

    private fun buildBundledVerifiedWarpSeedConfigsFromAsset(): List<WarpVerifiedConfig> {
        val rawSeeds = runCatching {
            appContext.assets.open(WARP_VERIFIED_SEEDS_ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull().orEmpty()
        if (rawSeeds.isBlank()) return emptyList()
        return loadBundledVerifiedWarpSeeds(rawSeeds).map { seed ->
            val configId = buildWarpConfigId(seed.mode, seed.host, seed.port, seed.scope)
            WarpVerifiedConfig(
                id = configId,
                engine = seed.engine,
                mode = seed.mode,
                host = seed.host,
                port = seed.port,
                endpointSource = seed.endpointSource,
                rawConfig = seed.rawConfig,
                createdAt = seed.lastVerifiedAt,
                lastVerifiedAt = seed.lastVerifiedAt,
                successCount = seed.successCount.coerceAtLeast(1),
                scope = seed.scope,
                manual = false,
                seedOrder = seed.seedOrder,
                preferredSni = seed.preferredSni,
                preferredPorts = seed.preferredPorts.ifEmpty {
                    updateWarpPortStats(emptyList(), seed.port, success = true, nowMs = seed.lastVerifiedAt)
                },
            )
        }
    }

    private fun mergeWarpVerifiedExportStats(
        persisted: WarpVerifiedConfig,
        exported: WarpVerifiedConfig,
    ): WarpVerifiedConfig {
        return persisted.copy(
            lastVerifiedAt = maxOf(persisted.lastVerifiedAt, exported.lastVerifiedAt),
            promotedAt = maxOf(persisted.promotedAt, exported.promotedAt),
            successCount = maxOf(persisted.successCount, exported.successCount),
            qualityProbeCount = maxOf(persisted.qualityProbeCount, exported.qualityProbeCount),
            qualityPingSuccesses = maxOf(persisted.qualityPingSuccesses, exported.qualityPingSuccesses),
            qualityAvgPingMs = when {
                exported.qualityPingSuccesses > 0 && exported.qualityAvgPingMs > 0.0 &&
                    exported.qualityLastCheckedAt >= persisted.qualityLastCheckedAt -> exported.qualityAvgPingMs
                persisted.qualityPingSuccesses > 0 && persisted.qualityAvgPingMs > 0.0 -> persisted.qualityAvgPingMs
                else -> maxOf(persisted.qualityAvgPingMs, exported.qualityAvgPingMs)
            },
            qualityLastCheckedAt = maxOf(persisted.qualityLastCheckedAt, exported.qualityLastCheckedAt),
            qualityFailureCount = maxOf(persisted.qualityFailureCount, exported.qualityFailureCount),
            preferredSni = persisted.preferredSni.ifBlank { exported.preferredSni },
            preferredPorts = mergeWarpPortStats(persisted, exported),
        ).withMergedHoldAndChurn(persisted, exported)
    }

    /**
     * Сводит накопительные замеры двух копий записи.
     *
     * Отдельной функцией, потому что здесь нельзя брать `maxOf` по каждому полю
     * по отдельности: пара «сколько окон» и «сколько в них насчитано» имеет
     * смысл только целиком. Двенадцать окон одной стороны с сорока событиями
     * другой дают выдуманное частное, которого не наблюдал никто.
     *
     * Сторона выбирается по числу окон — то есть по объёму замера, а не по
     * свежести отметки: свежая, но одноразовая проверка не должна затирать
     * накопленное.
     */
    /**
     * Переносит накопленные замеры со старой записи на пересобранную.
     *
     * Точка ровно одна, и это главное в ней. Пока каждое место пересборки
     * перечисляло счётчики само, добавление нового поля означало найти все такие
     * места — а компилятор молчит, потому что у полей есть значения по умолчанию и
     * пропущенный аргумент остаётся валидным кодом. Так уже терялся churn в двух
     * функциях и удержание — ещё в трёх: замер писался, в журнале было «записали»,
     * а в выгрузке стоял ноль. Новое поле теперь достаточно дописать сюда.
     *
     * Переносятся только накопительные величины — те, что копятся окнами и не
     * относятся к текущей попытке. Качество и пинг сюда не входят: их каждая
     * запись перевычисляет осознанно.
     */
    private fun WarpVerifiedConfig.carryMeasurementsFrom(
        previous: WarpVerifiedConfig?,
    ): WarpVerifiedConfig {
        if (previous == null) return this
        return copy(
            churnWindows = previous.churnWindows,
            churnRekeys = previous.churnRekeys,
            holdWindows = previous.holdWindows,
            holdStallMs = previous.holdStallMs,
            holdSpanMs = previous.holdSpanMs,
            holdCheckedAt = previous.holdCheckedAt,
        )
    }

    private fun WarpVerifiedConfig.withMergedHoldAndChurn(
        persisted: WarpVerifiedConfig,
        exported: WarpVerifiedConfig,
    ): WarpVerifiedConfig {
        val churnSource = if (exported.churnWindows > persisted.churnWindows) exported else persisted
        val holdSource = if (exported.holdWindows > persisted.holdWindows) exported else persisted
        return copy(
            churnWindows = churnSource.churnWindows,
            churnRekeys = churnSource.churnRekeys,
            holdWindows = holdSource.holdWindows,
            holdStallMs = holdSource.holdStallMs,
            holdSpanMs = holdSource.holdSpanMs,
            holdCheckedAt = holdSource.holdCheckedAt,
        )
    }

    fun getWarpVerifiedConfigs(scope: String? = null): List<WarpVerifiedConfig> {
        val raw = prefs.getString("warp_verified_configs", null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONObject("""{"items":$raw}""").optJSONArray("items")
            if (array == null) return emptyList()
            val requestedScope = scope?.let(::normalizeStrategyScope)
            val parsed = buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val engine = json.optString("engine").ifBlank { "wireguard" }
                    val mode = json.optString("mode").orEmpty()
                    val host = json.optString("host").orEmpty()
                    val port = json.optInt("port", -1)
                    val rawConfig = json.optString("raw_config").orEmpty()
                    val manual = json.optBoolean("manual", false)
                    val userImported = json.optBoolean("user_imported", false)
                    val endpointSource = json.optString("endpoint_source").orEmpty()
                    if (mode.isBlank() || host.isBlank() || port !in 1..65535 || rawConfig.isBlank()) continue
                    if (!isAllowedWarpVerifiedMode(mode)) continue
                    if (!isPersistableWarpVerifiedConfig(engine, host, manual, userImported, endpointSource)) continue
                    val normalizedScope = inferWarpVerifiedScope(mode, json.optString("scope"))
                    val id = resolveStoredWarpConfigId(
                        json = json,
                        index = index,
                        mode = mode,
                        host = host,
                        port = port,
                        normalizedScope = normalizedScope,
                        manual = manual,
                        userImported = userImported,
                        rawConfig = rawConfig,
                    )
                    add(
                        WarpVerifiedConfig(
                            id = id,
                            engine = engine,
                            mode = mode,
                            host = host,
                            port = port,
                            endpointSource = endpointSource,
                            rawConfig = rawConfig,
                            createdAt = json.optLong("created_at", 0L),
                            lastVerifiedAt = json.optLong("last_verified_at", 0L),
                            promotedAt = json.optLong("promoted_at", 0L),
                            seedOrder = json.optInt("seed_order", Int.MAX_VALUE),
                            successCount = json.optInt("success_count", 1).coerceAtLeast(1),
                            scope = normalizedScope,
                            manual = manual,
                            userImported = userImported,
                            qualityProbeCount = json.optInt("quality_probe_count", 0).coerceAtLeast(0),
                            qualityPingSuccesses = json.optInt("quality_ping_successes", 0).coerceAtLeast(0),
                            qualityAvgPingMs = json.optDouble("quality_avg_ping_ms", 0.0).takeIf { it.isFinite() } ?: 0.0,
                            qualityLastCheckedAt = json.optLong("quality_last_checked_at", 0L),
                            qualityFailureCount = json.optInt("quality_failure_count", 0).coerceAtLeast(0),
                            churnWindows = json.optInt("churn_windows", 0).coerceAtLeast(0),
                            churnRekeys = json.optInt("churn_rekeys", 0).coerceAtLeast(0),
                            holdWindows = json.optInt("hold_windows", 0).coerceAtLeast(0),
                            holdStallMs = json.optLong("hold_stall_ms", 0L).coerceAtLeast(0L),
                            holdSpanMs = json.optLong("hold_span_ms", 0L).coerceAtLeast(0L),
                            holdCheckedAt = json.optLong("hold_checked_at", 0L).coerceAtLeast(0L),
                            preferredSni = normalizeOptionalTrafficMaskHost(json.optString("preferred_sni")),
                            preferredPorts = parseWarpPortStats(json, port),
                        )
                    )
                }
            }
            val filtered = if (requestedScope == null) {
                parsed
            } else {
                parsed.filter { it.scope == requestedScope }
            }
            sortWarpVerifiedConfigs(filtered)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getWarpVerifiedExportSnapshot(scope: String? = null): List<WarpVerifiedConfig> {
        val raw = runCatching {
            if (!warpVerifiedExportFile.exists()) {
                synchronized(warpVerifiedExportCacheLock) {
                    cachedWarpVerifiedExportPath = warpVerifiedExportFile.absolutePath
                    cachedWarpVerifiedExportModifiedAt = 0L
                    cachedWarpVerifiedExportLength = 0L
                    cachedWarpVerifiedExportItems = emptyList()
                }
                return@runCatching ""
            }
            val path = warpVerifiedExportFile.absolutePath
            val modifiedAt = warpVerifiedExportFile.lastModified()
            val length = warpVerifiedExportFile.length()
            synchronized(warpVerifiedExportCacheLock) {
                if (
                    cachedWarpVerifiedExportPath == path &&
                    cachedWarpVerifiedExportModifiedAt == modifiedAt &&
                    cachedWarpVerifiedExportLength == length
                ) {
                    return@runCatching null
                }
            }
            warpVerifiedExportFile.readText().also {
                synchronized(warpVerifiedExportCacheLock) {
                    cachedWarpVerifiedExportPath = path
                    cachedWarpVerifiedExportModifiedAt = modifiedAt
                    cachedWarpVerifiedExportLength = length
                }
            }
        }.getOrDefault("").orEmpty().trim()
        val cachedItems = synchronized(warpVerifiedExportCacheLock) {
            cachedWarpVerifiedExportItems
        }
        if (raw.isBlank() && cachedItems != null) {
            val requestedScope = scope?.let(::normalizeStrategyScope)
            return if (requestedScope == null) {
                cachedItems
            } else {
                cachedItems.filter { it.scope == requestedScope }
            }
        }
        if (raw.isBlank()) return emptyList()
        return try {
            val requestedScope = scope?.let(::normalizeStrategyScope)
            val array = JSONObject(raw).optJSONArray("items") ?: return emptyList()
            val parsed = buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val engine = json.optString("engine").ifBlank { "wireguard" }
                    val mode = json.optString("mode").orEmpty()
                    val host = json.optString("host").orEmpty()
                    val port = json.optInt("port", -1)
                    val rawConfig = json.optString("raw_config").orEmpty()
                    val manual = json.optBoolean("manual", false)
                    val userImported = json.optBoolean("user_imported", false)
                    val endpointSource = json.optString("endpoint_source").orEmpty()
                    if (mode.isBlank() || host.isBlank() || port !in 1..65535 || rawConfig.isBlank()) continue
                    if (!isAllowedWarpVerifiedMode(mode)) continue
                    if (!isPersistableWarpVerifiedConfig(engine, host, manual, userImported, endpointSource)) continue
                    val normalizedScope = inferWarpVerifiedScope(mode, json.optString("scope"))
                    val id = resolveStoredWarpConfigId(
                        json = json,
                        index = index,
                        mode = mode,
                        host = host,
                        port = port,
                        normalizedScope = normalizedScope,
                        manual = manual,
                        userImported = userImported,
                        rawConfig = rawConfig,
                    )
                    add(
                        WarpVerifiedConfig(
                            id = id,
                            engine = engine,
                            mode = mode,
                            host = host,
                            port = port,
                            endpointSource = endpointSource,
                            rawConfig = rawConfig,
                            createdAt = json.optLong("created_at", 0L),
                            lastVerifiedAt = json.optLong("last_verified_at", 0L),
                            promotedAt = json.optLong("promoted_at", 0L),
                            seedOrder = json.optInt("seed_order", Int.MAX_VALUE),
                            successCount = json.optInt("success_count", 1).coerceAtLeast(1),
                            scope = normalizedScope,
                            manual = manual,
                            userImported = userImported,
                            qualityProbeCount = json.optInt("quality_probe_count", 0).coerceAtLeast(0),
                            qualityPingSuccesses = json.optInt("quality_ping_successes", 0).coerceAtLeast(0),
                            qualityAvgPingMs = json.optDouble("quality_avg_ping_ms", 0.0).takeIf { it.isFinite() } ?: 0.0,
                            qualityLastCheckedAt = json.optLong("quality_last_checked_at", 0L),
                            qualityFailureCount = json.optInt("quality_failure_count", 0).coerceAtLeast(0),
                            churnWindows = json.optInt("churn_windows", 0).coerceAtLeast(0),
                            churnRekeys = json.optInt("churn_rekeys", 0).coerceAtLeast(0),
                            holdWindows = json.optInt("hold_windows", 0).coerceAtLeast(0),
                            holdStallMs = json.optLong("hold_stall_ms", 0L).coerceAtLeast(0L),
                            holdSpanMs = json.optLong("hold_span_ms", 0L).coerceAtLeast(0L),
                            holdCheckedAt = json.optLong("hold_checked_at", 0L).coerceAtLeast(0L),
                            preferredSni = normalizeOptionalTrafficMaskHost(json.optString("preferred_sni")),
                            preferredPorts = parseWarpPortStats(json, port),
                        )
                    )
                }
            }.distinctBy { it.id }
            val sortedParsed = sortWarpVerifiedConfigs(parsed)
            synchronized(warpVerifiedExportCacheLock) {
                cachedWarpVerifiedExportItems = sortedParsed
            }
            if (requestedScope == null) {
                sortedParsed
            } else {
                sortedParsed.filter { it.scope == requestedScope }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun getWarpVerifiedExportFile(): File = warpVerifiedExportFile

    fun getAvailableImportedProtocolFamilies(): List<String> {
        val warpFamilies = (getWarpVerifiedExportSnapshot() + getWarpVerifiedConfigs())
            .distinctBy { it.id }
            .asSequence()
            .filter { it.userImported && !it.manual }
            .map(::inferImportedProtocolFamily)
            .filter { it != "auto" }
            .distinct()
            .toList()
        // Профили VLESS лежат в своём хранилище, но для выбора протокола это такая же
        // импортированная семья, как AWG: без неё выбрать VLESS было бы негде.
        //
        // VLESS идёт первым намеренно. Экран выбора протокола раскладывает список по
        // пяти радиокнопкам, первую из которых занимает «AUTO», — а семей бывает до
        // пяти. Пока VLESS дописывался в конец, при полном наборе импортированных
        // протоколов кнопки ему не доставалось и выбрать его было нечем.
        val vlessFamilies = if (getVlessProfileLinks().isEmpty()) emptyList() else listOf("vless")
        return (vlessFamilies + warpFamilies).distinct()
    }

    fun inferImportedProtocolFamily(config: WarpVerifiedConfig): String {
        val normalizedEngine = normalizeToken(config.engine)
        val normalizedMode = normalizeToken(config.mode)
        val raw = config.rawConfig
        return when {
            normalizedEngine == "vless" -> "vless"
            normalizedEngine == "masque" || normalizedMode.startsWith("masque") -> "masque"
            Regex("(?im)^(Jc|Jmin|Jmax|S[1-4]|H[1-4]|I[1-5])\\s*=").containsMatchIn(raw) ||
                normalizedMode.contains("awg") -> "awg"
            normalizedMode.contains("obfs") ||
                normalizedMode.contains("fake") ||
                normalizedMode.contains("quic") ||
                normalizedMode.contains("trick") ||
                normalizedMode.contains("random") ||
                normalizedMode.contains("reserved") -> "warp"
            else -> "wireguard"
        }
    }

    fun formatImportedProtocolDisplay(value: String?): String {
        return when (normalizeImportedProtocolPreference(value)) {
            "awg" -> "AWG"
            "masque" -> "MASQUE"
            "warp" -> "WARP"
            "wireguard" -> "WIREGUARD"
            "vless" -> "VLESS"
            else -> "AUTO"
        }
    }

    private fun mergeWarpVerifiedExportSnapshotIntoPrefs() {
        synchronized(warpVerifiedConfigsLock) {
            val exported = getWarpVerifiedExportSnapshot()
                .filter { !it.manual && it.rawConfig.isNotBlank() }
            if (exported.isEmpty()) return
            val existing = getWarpVerifiedConfigs().associateBy { it.id }.toMutableMap()
            var changed = false
            for (item in exported) {
                val previous = existing[item.id]
                val merged = when {
                    previous == null -> item
                    item.userImported && !previous.userImported -> item.copy(
                        successCount = maxOf(item.successCount, previous.successCount),
                        lastVerifiedAt = maxOf(item.lastVerifiedAt, previous.lastVerifiedAt),
                        createdAt = minOf(item.createdAt.takeIf { it > 0L } ?: previous.createdAt, previous.createdAt),
                        qualityProbeCount = maxOf(item.qualityProbeCount, previous.qualityProbeCount),
                        qualityPingSuccesses = maxOf(item.qualityPingSuccesses, previous.qualityPingSuccesses),
                        qualityAvgPingMs = mergeWarpQualityAverage(previous, item),
                        qualityLastCheckedAt = maxOf(item.qualityLastCheckedAt, previous.qualityLastCheckedAt),
                        qualityFailureCount = minOf(item.qualityFailureCount, previous.qualityFailureCount),
                        preferredSni = chooseWarpPreferredSni(previous, item),
                        preferredPorts = mergeWarpPortStats(previous, item),
                    ).withMergedHoldAndChurn(previous, item)
                    item.lastVerifiedAt > previous.lastVerifiedAt || item.successCount > previous.successCount ->
                        previous.copy(
                            engine = item.engine.ifBlank { previous.engine },
                            mode = item.mode.ifBlank { previous.mode },
                            host = item.host.ifBlank { previous.host },
                            port = item.port.takeIf { it in 1..65535 } ?: previous.port,
                            endpointSource = item.endpointSource.ifBlank { previous.endpointSource },
                            rawConfig = if (item.userImported || previous.rawConfig.isBlank()) item.rawConfig else previous.rawConfig,
                            lastVerifiedAt = maxOf(item.lastVerifiedAt, previous.lastVerifiedAt),
                            successCount = maxOf(item.successCount, previous.successCount),
                            userImported = item.userImported || previous.userImported,
                            qualityProbeCount = chooseRecentWarpQualityInt(previous, item) { it.qualityProbeCount },
                            qualityPingSuccesses = chooseRecentWarpQualityInt(previous, item) { it.qualityPingSuccesses },
                            qualityAvgPingMs = chooseRecentWarpQualityDouble(previous, item) { it.qualityAvgPingMs },
                            qualityLastCheckedAt = maxOf(item.qualityLastCheckedAt, previous.qualityLastCheckedAt),
                            qualityFailureCount = chooseRecentWarpQualityInt(previous, item) { it.qualityFailureCount },
                            preferredSni = chooseWarpPreferredSni(previous, item),
                            preferredPorts = mergeWarpPortStats(previous, item),
                        ).withMergedHoldAndChurn(previous, item)
                    else -> previous
                }
                if (merged != previous) {
                    existing[item.id] = merged
                    changed = true
                }
            }
            if (changed) {
                saveWarpVerifiedConfigs(existing.values.toList())
            }
        }
    }

    private fun pruneGeneratedBuiltInWarpConfigs() {
        synchronized(warpVerifiedConfigsLock) {
            val rawCount = runCatching {
                val raw = prefs.getString("warp_verified_configs", null).orEmpty()
                if (raw.isBlank()) 0 else JSONObject("""{"items":$raw}""").optJSONArray("items")?.length() ?: 0
            }.getOrDefault(0)
            val current = getWarpVerifiedConfigs()
            val pruned = current.filter {
                it.manual || it.userImported || isBundledSeed(it)
            }
            val removed = current - pruned.toSet()
            if (removed.isNotEmpty()) {
                Log.w("NovaAdapt", "pruneGeneratedBuiltInWarpConfigs: removing ${removed.size} generated configs: " +
                    removed.joinToString("; ") { "${it.mode}@${it.host}:${it.port} src=${it.endpointSource} seedOrder=${it.seedOrder}" })
            }
            if (rawCount != pruned.size || pruned.size != current.size) {
                saveWarpVerifiedConfigs(pruned)
            }
        }
    }

    private fun chooseRecentWarpQualityInt(
        previous: WarpVerifiedConfig,
        incoming: WarpVerifiedConfig,
        selector: (WarpVerifiedConfig) -> Int,
    ): Int {
        return if (incoming.qualityLastCheckedAt >= previous.qualityLastCheckedAt) {
            selector(incoming)
        } else {
            selector(previous)
        }
    }

    private fun chooseRecentWarpQualityDouble(
        previous: WarpVerifiedConfig,
        incoming: WarpVerifiedConfig,
        selector: (WarpVerifiedConfig) -> Double,
    ): Double {
        return if (incoming.qualityLastCheckedAt >= previous.qualityLastCheckedAt) {
            selector(incoming)
        } else {
            selector(previous)
        }
    }

    private fun mergeWarpQualityAverage(
        previous: WarpVerifiedConfig,
        incoming: WarpVerifiedConfig,
    ): Double {
        val best = listOf(previous, incoming)
            .filter { it.qualityPingSuccesses > 0 && it.qualityAvgPingMs > 0.0 }
            .minWithOrNull(
                compareBy<WarpVerifiedConfig> { it.qualityAvgPingMs }
                    .thenByDescending { it.qualityPingSuccesses }
            )
        return best?.qualityAvgPingMs ?: 0.0
    }

    private fun chooseWarpPreferredSni(
        previous: WarpVerifiedConfig,
        incoming: WarpVerifiedConfig,
    ): String {
        val previousSni = normalizeOptionalTrafficMaskHost(previous.preferredSni)
        val incomingSni = normalizeOptionalTrafficMaskHost(incoming.preferredSni)
        if (incomingSni.isBlank()) return previousSni
        if (previousSni.isBlank()) return incomingSni
        val previousFreshness = maxOf(previous.lastVerifiedAt, previous.qualityLastCheckedAt)
        val incomingFreshness = maxOf(incoming.lastVerifiedAt, incoming.qualityLastCheckedAt)
        return if (incomingFreshness >= previousFreshness) incomingSni else previousSni
    }

    fun hasWarpVerifiedConfig(mode: String, host: String, port: Int): Boolean {
        val normalizedMode = mode.trim()
        val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
        return getWarpVerifiedConfigs().any {
            !it.manual &&
                it.mode.equals(normalizedMode, ignoreCase = true) &&
                it.host.equals(normalizedHost, ignoreCase = true) &&
                it.port == port
        }
    }

    fun getPreferredWarpPortsFor(
        engine: String,
        mode: String,
        host: String,
        scope: String = STRATEGY_SCOPE_DEFAULT,
        limit: Int = 16,
    ): List<Int> {
        val normalizedEngine = engine.trim().lowercase()
        val normalizedMode = mode.trim().lowercase()
        val normalizedHost = host.trim().removePrefix("[").removeSuffix("]").lowercase()
        val normalizedScope = inferWarpVerifiedScope(mode, scope)
        if (normalizedMode.isBlank() || normalizedHost.isBlank() || limit <= 0) return emptyList()
        val direct = getWarpVerifiedConfigs()
            .filter {
                it.engine.trim().lowercase().ifBlank { normalizedEngine } == normalizedEngine &&
                    it.mode.trim().lowercase() == normalizedMode &&
                    it.host.trim().removePrefix("[").removeSuffix("]").lowercase() == normalizedHost &&
                    inferWarpVerifiedScope(it.mode, it.scope) == normalizedScope
        }
        val stats = direct.flatMap { config ->
            warpPortStatsForConfig(config)
        }
        return sortWarpPortStats(stats)
            .map { it.port }
            .distinct()
            .take(limit)
    }

    fun getLearnedWarpPortOrder(limit: Int = 18, includeMasque: Boolean = false): List<Int> {
        if (limit <= 0) return emptyList()
        val stats = getWarpVerifiedConfigs()
            .filter { includeMasque || !it.engine.equals("masque", ignoreCase = true) }
            .flatMap { config ->
                warpPortStatsForConfig(config)
            }
        return sortWarpPortStats(stats)
            .map { it.port }
            .distinct()
            .take(limit)
    }

    fun removeWarpVerifiedConfig(id: String) {
        synchronized(warpVerifiedConfigsLock) {
            if (id.isBlank()) return
            val filtered = getWarpVerifiedConfigs().filterNot { it.id == id }
            saveWarpVerifiedConfigs(filtered)
        }
    }

    fun upsertWarpVerifiedConfig(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        endpointSource: String,
        rawConfig: String,
        scope: String = STRATEGY_SCOPE_DEFAULT,
        manual: Boolean = false,
        userImported: Boolean = false,
        preferredSni: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(warpVerifiedConfigsLock) {
            if (port !in 1..65535) return
            val normalizedMode = mode.trim()
            val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
            val normalizedEndpointSource = endpointSource.trim()
            if (normalizedMode.isBlank() || normalizedHost.isBlank() || rawConfig.isBlank()) return
            if (!isAllowedWarpVerifiedMode(normalizedMode)) return
            if (!isPersistableWarpVerifiedConfig(engine, normalizedHost, manual, userImported, normalizedEndpointSource)) return
            val normalizedScope = inferWarpVerifiedScope(normalizedMode, scope)
            val configId = buildWarpConfigId(normalizedMode, normalizedHost, port, normalizedScope)
            val existing = getWarpVerifiedConfigs().associateBy { it.id }.toMutableMap()
            val previous = existing[configId]
            val preserveBundledSeed = previous?.let(::isBundledSeed) == true &&
                !normalizedEndpointSource.equals("bundled-seed", ignoreCase = true)
            val normalizedRawConfig = rawConfig.trim()
            val normalizedPreferredSni = normalizeOptionalTrafficMaskHost(preferredSni)
                .ifBlank { previous?.preferredSni.orEmpty() }
            val rawConfigToStore = when {
                // Конфигурация встроенного профиля неприкосновенна. Она приходит из
                // прошивки вместе с параметрами обфускации AWG (Jc/Jmin/Jmax, S1..S4,
                // H1..H4, I1..I5), и переписывать её во время работы нечем: служба не
                // знает о профиле ничего, чего не знала бы прошивка. Прежняя защита
                // срабатывала, только когда источник записи отличался от bundled-seed,
                // а при обычном подключении к встроенному профилю он ровно такой —
                // поэтому профиль терял параметры при первом же подключении.
                previous != null && isBundledSeed(previous) && previous.rawConfig.isNotBlank() ->
                    previous.rawConfig
                preserveBundledSeed -> previous?.rawConfig.orEmpty()
                // Огрызок не должен вытеснять полную конфигурацию: встроенные профили
                // везут свои AWG-параметры в том же поле rawConfig.
                previous != null &&
                    previous.rawConfig.contains("[Interface]", ignoreCase = true) &&
                    !normalizedRawConfig.contains("[Interface]", ignoreCase = true) ->
                    previous.rawConfig
                else -> normalizedRawConfig
            }
            existing[configId] = WarpVerifiedConfig(
                id = configId,
                engine = engine.trim().ifBlank { "wireguard" },
                mode = normalizedMode,
                host = normalizedHost,
                port = port,
                endpointSource = if (preserveBundledSeed) previous?.endpointSource.orEmpty() else normalizedEndpointSource,
                rawConfig = rawConfigToStore,
                createdAt = previous?.createdAt ?: nowMs,
                seedOrder = previous?.seedOrder ?: Int.MAX_VALUE,
                lastVerifiedAt = nowMs,
                successCount = (previous?.successCount ?: 0) + if (manual) 0 else 1,
                scope = normalizedScope,
                manual = manual,
                userImported = userImported || previous?.userImported == true,
                qualityProbeCount = previous?.qualityProbeCount ?: 0,
                qualityPingSuccesses = previous?.qualityPingSuccesses ?: 0,
                qualityAvgPingMs = previous?.qualityAvgPingMs ?: 0.0,
                qualityLastCheckedAt = previous?.qualityLastCheckedAt ?: 0L,
                qualityFailureCount = previous?.qualityFailureCount ?: 0,
                preferredSni = normalizedPreferredSni,
                preferredPorts = updateWarpPortStats(
                    previous?.preferredPorts.orEmpty(),
                    port = port,
                    success = true,
                    nowMs = nowMs,
                ),
            ).carryMeasurementsFrom(previous)
            saveWarpVerifiedConfigs(existing.values.toList())
        }
    }

    fun addUserImportedWarpConfig(
        rawConfig: String,
        engine: String,
        mode: String,
        host: String,
        port: Int,
        scope: String = STRATEGY_SCOPE_DEFAULT,
        preferredSni: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): WarpVerifiedConfig? {
        synchronized(warpVerifiedConfigsLock) {
            if (port !in 1..65535) return null
            val normalizedMode = mode.trim()
            val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
            val normalizedRaw = rawConfig.trim()
            if (normalizedMode.isBlank() || normalizedHost.isBlank() || normalizedRaw.isBlank()) return null
            if (!isAllowedWarpVerifiedMode(normalizedMode)) return null
            if (!isAllowedVerifiedWarpEndpoint(engine, normalizedHost, manual = false, userImported = true)) return null
            val normalizedScope = inferWarpVerifiedScope(normalizedMode, scope)
            val configId = buildUserImportedWarpConfigId(
                mode = normalizedMode,
                host = normalizedHost,
                port = port,
                scope = normalizedScope,
                rawConfig = normalizedRaw,
            )
            val existing = getWarpVerifiedConfigs().associateBy { it.id }.toMutableMap()
            val previous = existing[configId]
            val normalizedPreferredSni = normalizeOptionalTrafficMaskHost(preferredSni)
                .ifBlank { previous?.preferredSni.orEmpty() }
            val config = WarpVerifiedConfig(
                id = configId,
                engine = engine.trim().ifBlank { "wireguard" },
                mode = normalizedMode,
                host = normalizedHost,
                port = port,
                endpointSource = previous?.endpointSource?.ifBlank { "verified-config" } ?: "verified-config",
                rawConfig = normalizedRaw,
                createdAt = previous?.createdAt ?: nowMs,
                lastVerifiedAt = nowMs,
                successCount = previous?.successCount?.coerceAtLeast(1) ?: 1,
                scope = normalizedScope,
                manual = false,
                userImported = true,
                qualityProbeCount = previous?.qualityProbeCount ?: 0,
                qualityPingSuccesses = previous?.qualityPingSuccesses ?: 0,
                qualityAvgPingMs = previous?.qualityAvgPingMs ?: 0.0,
                qualityLastCheckedAt = previous?.qualityLastCheckedAt ?: 0L,
                qualityFailureCount = previous?.qualityFailureCount ?: 0,
                preferredSni = normalizedPreferredSni,
                preferredPorts = updateWarpPortStats(
                    previous?.preferredPorts.orEmpty(),
                    port = port,
                    success = true,
                    nowMs = nowMs,
                ),
            ).carryMeasurementsFrom(previous)
            existing[configId] = config
            saveWarpVerifiedConfigs(existing.values.toList())
            return config
        }
    }

    fun recordWarpVerifiedPreferredSni(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        preferredSni: String,
        endpointSource: String,
        rawConfig: String? = null,
        scope: String = STRATEGY_SCOPE_DEFAULT,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(warpVerifiedConfigsLock) {
            if (port !in 1..65535) return
            val normalizedPreferredSni = normalizeOptionalTrafficMaskHost(preferredSni)
            if (normalizedPreferredSni.isBlank()) return
            val normalizedMode = mode.trim()
            val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
            if (normalizedMode.isBlank() || normalizedHost.isBlank()) return
            if (!isAllowedWarpVerifiedMode(normalizedMode)) return
            val normalizedEngine = engine.trim().ifBlank { "wireguard" }
            val normalizedScope = inferWarpVerifiedScope(normalizedMode, scope)
            val configId = buildWarpConfigId(normalizedMode, normalizedHost, port, normalizedScope)
            val existing = getWarpVerifiedConfigs().associateBy { it.id }.toMutableMap()
            val previous = existing[configId]
            val source = endpointSource.trim().ifBlank { previous?.endpointSource ?: "runtime-sni" }
            if (!isPersistableWarpVerifiedConfig(
                    normalizedEngine,
                    normalizedHost,
                    previous?.manual ?: false,
                    previous?.userImported ?: false,
                    source,
                )
            ) return

            val candidateRawConfig = rawConfig?.trim().orEmpty()
            val rawConfigToStore = when {
                // Конфигурация встроенного профиля неприкосновенна. Она приходит из
                // прошивки вместе с параметрами обфускации AWG (Jc/Jmin/Jmax, S1..S4,
                // H1..H4, I1..I5), и переписывать её во время работы нечем: служба не
                // знает о профиле ничего, чего не знала бы прошивка. Прежняя защита
                // срабатывала, только когда источник записи отличался от bundled-seed,
                // а при обычном подключении к встроенному профилю он ровно такой —
                // поэтому профиль терял параметры при первом же подключении.
                previous != null && isBundledSeed(previous) && previous.rawConfig.isNotBlank() ->
                    previous.rawConfig
                // Огрызок не должен вытеснять полную конфигурацию: встроенные профили
                // везут свои AWG-параметры в том же поле rawConfig.
                previous != null &&
                    previous.rawConfig.contains("[Interface]", ignoreCase = true) &&
                    !candidateRawConfig.contains("[Interface]", ignoreCase = true) ->
                    previous.rawConfig
                candidateRawConfig.isNotBlank() -> candidateRawConfig
                else -> previous?.rawConfig.orEmpty().ifBlank {
                    buildString {
                        appendLine("HOST=$normalizedHost")
                        appendLine("PORT=$port")
                        appendLine("PROTOCOL=${normalizedEngine.uppercase()}")
                        appendLine("STRATEGY=$normalizedMode")
                        appendLine("SOURCE=$source")
                        appendLine("PREFERRED_SNI=$normalizedPreferredSni")
                    }.trim()
                }
            }
            if (rawConfigToStore.isBlank()) return

            existing[configId] = WarpVerifiedConfig(
                id = configId,
                engine = previous?.engine?.ifBlank { normalizedEngine } ?: normalizedEngine,
                mode = normalizedMode,
                host = normalizedHost,
                port = port,
                endpointSource = source,
                rawConfig = rawConfigToStore,
                createdAt = previous?.createdAt ?: nowMs,
                lastVerifiedAt = maxOf(previous?.lastVerifiedAt ?: 0L, nowMs),
                promotedAt = previous?.promotedAt ?: 0L,
                seedOrder = previous?.seedOrder ?: Int.MAX_VALUE,
                successCount = previous?.successCount ?: 1,
                scope = normalizedScope,
                manual = previous?.manual ?: false,
                userImported = previous?.userImported ?: false,
                qualityProbeCount = previous?.qualityProbeCount ?: 0,
                qualityPingSuccesses = previous?.qualityPingSuccesses ?: 0,
                qualityAvgPingMs = previous?.qualityAvgPingMs ?: 0.0,
                qualityLastCheckedAt = previous?.qualityLastCheckedAt ?: 0L,
                qualityFailureCount = previous?.qualityFailureCount ?: 0,
                preferredSni = normalizedPreferredSni,
                preferredPorts = previous?.preferredPorts.orEmpty(),
            ).carryMeasurementsFrom(previous)
            saveWarpVerifiedConfigs(existing.values.toList())
        }
    }

    /**
     * Разовая чистка сохранённых маскировочных имён.
     *
     * Подстановка выключена (см. `resolveWarpTrafficMaskHosts`), но у устройств,
     * которые уже прошли адаптацию, имя лежит в профилях. Без чистки оно осталось
     * бы там навсегда: писать его больше некому, а стирается оно только по факту
     * отказа рукопожатия — то есть ценой пяти минут перебора на каждом старте.
     *
     * Возвращает, сколько записей вычищено, — чтобы отказ был виден в журнале.
     */
    /**
     * Чистит маскировочные имена, оставшиеся от сборок с подстановкой.
     *
     * Один раз на установку: ключ ставится независимо от того, нашлось ли что
     * чистить, иначе чистка ходила бы по всему списку при каждом запуске службы.
     */
    private fun purgeStoredWarpPreferredSniOnce() {
        if (prefs.getBoolean(WARP_PREFERRED_SNI_PURGED_KEY, false)) return
        val cleared = runCatching { clearAllWarpVerifiedPreferredSni() }.getOrDefault(0)
        prefs.edit().putBoolean(WARP_PREFERRED_SNI_PURGED_KEY, true).commit()
        if (cleared > 0) {
            LogManager.log(
                "Убраны сохранённые маскировочные имена у $cleared профилей WARP: " +
                    "подстановка SNI выключена, а имя без чистки осталось бы жить в профилях."
            )
        }
    }

    fun clearAllWarpVerifiedPreferredSni(): Int {
        synchronized(warpVerifiedConfigsLock) {
            val existing = getWarpVerifiedConfigs()
            val stale = existing.count { it.preferredSni.isNotBlank() }
            if (stale == 0) return 0
            saveWarpVerifiedConfigs(existing.map { it.copy(preferredSni = "") })
            return stale
        }
    }

    fun clearWarpVerifiedPreferredSni(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        scope: String = STRATEGY_SCOPE_DEFAULT,
    ) {
        synchronized(warpVerifiedConfigsLock) {
            if (port !in 1..65535) return
            val normalizedMode = mode.trim()
            val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
            if (normalizedMode.isBlank() || normalizedHost.isBlank()) return
            if (!isAllowedWarpVerifiedMode(normalizedMode)) return
            val normalizedEngine = engine.trim().ifBlank { "wireguard" }
            val normalizedScope = inferWarpVerifiedScope(normalizedMode, scope)
            val configId = buildWarpConfigId(normalizedMode, normalizedHost, port, normalizedScope)
            val existing = getWarpVerifiedConfigs().associateBy { it.id }.toMutableMap()
            val previous = existing[configId] ?: return
            if (!isPersistableWarpVerifiedConfig(normalizedEngine, normalizedHost, previous.manual, previous.userImported, previous.endpointSource)) return
            if (previous.preferredSni.isBlank()) return
            existing[configId] = previous.copy(preferredSni = "")
            saveWarpVerifiedConfigs(existing.values.toList())
        }
    }

    fun recordWarpVerifiedRuntimeOutcome(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        success: Boolean,
        endpointSource: String,
        rawConfig: String? = null,
        scope: String = STRATEGY_SCOPE_DEFAULT,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(warpVerifiedConfigsLock) {
            if (port !in 1..65535) return
            val normalizedMode = mode.trim()
            val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
            if (normalizedMode.isBlank() || normalizedHost.isBlank()) return
            if (!isAllowedWarpVerifiedMode(normalizedMode)) return
            val normalizedEngine = engine.trim().ifBlank { "wireguard" }
            val existing = getWarpVerifiedConfigs().associateBy { it.id }.toMutableMap()
            val normalizedScope = inferWarpVerifiedScope(normalizedMode, scope)
            val configId = buildWarpConfigId(normalizedMode, normalizedHost, port, normalizedScope)
            val previousStored = existing[configId]
            val normalizedSource = endpointSource.trim().ifBlank { previousStored?.endpointSource ?: "runtime-success" }
            Log.w("NovaAdapt", "recordWarpVerifiedRuntimeOutcome: mode=$normalizedMode host=$normalizedHost port=$port src=$normalizedSource success=$success prevExists=${previousStored != null}")
            if (!isPersistableWarpVerifiedConfig(
                    normalizedEngine,
                    normalizedHost,
                    previousStored?.manual ?: false,
                    previousStored?.userImported ?: false,
                    normalizedSource,
                )
            ) return
            // Узел MASQUE заводим при первом же исходе.
            //
            // Список проверенных конфигураций наполняется встроенными профилями, а у
            // MASQUE их нет: адреса приходят из ответа enroll. Запись без заготовки
            // просто выбрасывалась, поэтому `recordWarpConfigHoldWindow` каждый раз
            // отвечал «узла нет в списке» — качество MASQUE не копилось между сессиями
            // и в рейтинге не участвовало.
            val previous = previousStored ?: WarpVerifiedConfig(
                id = configId,
                engine = normalizedEngine,
                mode = normalizedMode,
                host = normalizedHost,
                port = port,
                endpointSource = normalizedSource,
                rawConfig = "",
                createdAt = nowMs,
                lastVerifiedAt = 0L,
                successCount = 0,
                scope = normalizedScope,
            ).takeIf { isOwnIssuedMasqueEndpointSource(normalizedEngine, normalizedSource) }
            if (previous == null) return


            val normalizedResolvedEngine = normalizedEngine.ifBlank { previous.engine }
            val candidateRawConfig = rawConfig?.trim().orEmpty()
            val normalizedRawConfig = when {
                // Конфигурация встроенного профиля неприкосновенна. Она приходит из
                // прошивки вместе с параметрами обфускации AWG (Jc/Jmin/Jmax, S1..S4,
                // H1..H4, I1..I5), и переписывать её во время работы нечем: служба не
                // знает о профиле ничего, чего не знала бы прошивка. Прежняя защита
                // срабатывала, только когда источник записи отличался от bundled-seed,
                // а при обычном подключении к встроенному профилю он ровно такой —
                // поэтому профиль терял параметры при первом же подключении.
                previous != null && isBundledSeed(previous) && previous.rawConfig.isNotBlank() ->
                    previous.rawConfig
                // Полную конфигурацию не заменяем огрызком — независимо от того,
                // импортирована она пользователем или пришла из прошивки. Раньше
                // условие держалось на `userImported`, и встроенные профили теряли
                // свои AWG-параметры (Jc/Jmin/Jmax, S1..S4, H1..H4, I1..I5) при первой
                // же записи результата: на их место ложилось описание из пяти строк
                // HOST/PORT/PROTOCOL/STRATEGY/SOURCE. Дальше профиль поднимался как
                // обычный WireGuard, без обфускации, и рукопожатие уходило в таймаут.
                previous.rawConfig.contains("[Interface]", ignoreCase = true) &&
                    !candidateRawConfig.contains("[Interface]", ignoreCase = true) ->
                    previous.rawConfig
                candidateRawConfig.isNotBlank() -> candidateRawConfig
                else -> previous.rawConfig
            }
            if (success && normalizedRawConfig.isBlank()) return
            val exoticMasquePort = port in setOf(443, 4443, 8443, 8095)
            val shouldDecayPersistedPriority = !success &&
                normalizedSource.lowercase() in setOf("verified-config", "last-success-exact", "last-success")
            val nextSuccessCount = when {
                success -> previous.successCount + 1
                shouldDecayPersistedPriority -> {
                    val currentSuccessCount = previous.successCount
                    when {
                        normalizedResolvedEngine.equals("masque", ignoreCase = true) && exoticMasquePort && currentSuccessCount >= 4 ->
                            currentSuccessCount - 3
                        normalizedResolvedEngine.equals("masque", ignoreCase = true) && exoticMasquePort && currentSuccessCount >= 2 ->
                            currentSuccessCount - 2
                        currentSuccessCount >= 4 -> currentSuccessCount - 2
                        currentSuccessCount >= 2 -> currentSuccessCount - 1
                        else -> 1
                    }
                }
                else -> previous?.successCount ?: 1
            }
            val nextQualityFailureCount = if (success) {
                previous?.qualityFailureCount ?: 0
            } else {
                (previous?.qualityFailureCount ?: 0) + 1
            }

            // Пересобирать запись конструктором здесь нельзя, и это не стилистика.
            // У всех счётчиков есть значения по умолчанию, поэтому пропущенное поле —
            // валидный код, о котором компилятор промолчит. Так и потерялись
            // churnWindows/churnRekeys: замер удержания копился на живой сессии, а
            // первый же исход попытки обнулял его по всему списку. `copy` переносит
            // и то, что появится позже.
            existing[configId] = previous.copy(
                id = configId,
                engine = normalizedResolvedEngine,
                mode = normalizedMode,
                host = normalizedHost,
                port = port,
                endpointSource = normalizedSource,
                rawConfig = normalizedRawConfig.ifBlank {
                    buildString {
                        appendLine("HOST=$normalizedHost")
                        appendLine("PORT=$port")
                        appendLine("PROTOCOL=${normalizedResolvedEngine.uppercase()}")
                        appendLine("STRATEGY=$normalizedMode")
                        appendLine("SOURCE=$normalizedSource")
                    }.trim()
                },
                lastVerifiedAt = if (success) nowMs else previous.lastVerifiedAt,
                // Отказ снимает закрепление наверху списка. Ручное переключение профиля
                // ставит `promotedAt`, и без сброса выбранный профиль оставался первым
                // даже после того, как перестал подключаться: перебор упирался в него
                // каждый цикл, а «плохие вниз» не работало вовсе.
                promotedAt = if (success) previous.promotedAt else 0L,
                successCount = nextSuccessCount,
                scope = normalizedScope,
                qualityProbeCount = if (success) previous.qualityProbeCount else 0,
                qualityPingSuccesses = if (success) previous.qualityPingSuccesses else 0,
                qualityAvgPingMs = if (success) previous.qualityAvgPingMs else 0.0,
                qualityLastCheckedAt = if (success) previous.qualityLastCheckedAt else nowMs,
                qualityFailureCount = nextQualityFailureCount,
                preferredPorts = updateWarpPortStats(
                    previous.preferredPorts,
                    port = port,
                    success = success,
                    nowMs = nowMs,
                ),
            )
            saveWarpVerifiedConfigs(existing.values.toList())
        }
    }

    fun recordWarpVerifiedQualityResult(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        success: Boolean,
        probeCount: Int,
        pingSuccesses: Int,
        avgPingMs: Double,
        endpointSource: String,
        rawConfig: String? = null,
        scope: String = STRATEGY_SCOPE_DEFAULT,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(warpVerifiedConfigsLock) {
            if (port !in 1..65535) return
            val normalizedMode = mode.trim()
            val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
            if (normalizedMode.isBlank() || normalizedHost.isBlank()) return
            if (!isAllowedWarpVerifiedMode(normalizedMode)) return
            val normalizedEngine = engine.trim().ifBlank { "wireguard" }
            val normalizedScope = inferWarpVerifiedScope(normalizedMode, scope)
            val configId = buildWarpConfigId(normalizedMode, normalizedHost, port, normalizedScope)
            val existing = getWarpVerifiedConfigs().associateBy { it.id }.toMutableMap()
            val previous = existing[configId]
            val source = endpointSource.trim().ifBlank { previous?.endpointSource ?: "runtime-quality" }
            if (!isPersistableWarpVerifiedConfig(
                    normalizedEngine,
                    normalizedHost,
                    previous?.manual ?: false,
                    previous?.userImported ?: false,
                    source,
                )
            ) return
            if (previous == null && !success) return

            val safeProbeCount = probeCount.coerceAtLeast(0)
            val safePingSuccesses = pingSuccesses.coerceIn(0, safeProbeCount.coerceAtLeast(pingSuccesses))
            val safeAvgPingMs = avgPingMs.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
            Log.w("NovaAdapt", "recordWarpVerifiedQualityResult: mode=$normalizedMode host=$normalizedHost port=$port probeCount=$safeProbeCount pingSuccesses=$safePingSuccesses avgPing=${safeAvgPingMs}")
            val candidateRawConfig = rawConfig?.trim().orEmpty()
            val normalizedRawConfig = when {
                // Конфигурация встроенного профиля неприкосновенна. Она приходит из
                // прошивки вместе с параметрами обфускации AWG (Jc/Jmin/Jmax, S1..S4,
                // H1..H4, I1..I5), и переписывать её во время работы нечем: служба не
                // знает о профиле ничего, чего не знала бы прошивка. Прежняя защита
                // срабатывала, только когда источник записи отличался от bundled-seed,
                // а при обычном подключении к встроенному профилю он ровно такой —
                // поэтому профиль терял параметры при первом же подключении.
                previous != null && isBundledSeed(previous) && previous.rawConfig.isNotBlank() ->
                    previous.rawConfig
                // Огрызок не должен вытеснять полную конфигурацию: встроенные профили
                // везут свои AWG-параметры в том же поле rawConfig.
                previous != null &&
                    previous.rawConfig.contains("[Interface]", ignoreCase = true) &&
                    !candidateRawConfig.contains("[Interface]", ignoreCase = true) ->
                    previous.rawConfig
                candidateRawConfig.isNotBlank() -> candidateRawConfig
                else -> previous?.rawConfig.orEmpty()
            }
            if (success && normalizedRawConfig.isBlank()) return

            val qualityFailed = !success || safePingSuccesses <= 0
            // Основа — прежняя запись, если она есть. Пересборка конструктором молча
            // теряла всё, чего нет в списке аргументов: churnWindows/churnRekeys
            // обнулялись здесь на каждом окне замера, то есть один прогон адаптации
            // стирал накопленное удержание по всем пятидесяти профилям. Через `copy`
            // новые счётчики переживают публикацию качества сами.
            val base = previous ?: WarpVerifiedConfig(
                id = configId,
                engine = normalizedEngine,
                mode = normalizedMode,
                host = normalizedHost,
                port = port,
                endpointSource = source,
                rawConfig = normalizedRawConfig,
                createdAt = nowMs,
                lastVerifiedAt = nowMs,
                scope = normalizedScope,
            )
            existing[configId] = base.copy(
                id = configId,
                engine = base.engine.ifBlank { normalizedEngine },
                mode = normalizedMode,
                host = normalizedHost,
                port = port,
                endpointSource = source,
                rawConfig = normalizedRawConfig.ifBlank {
                    buildString {
                        appendLine("HOST=$normalizedHost")
                        appendLine("PORT=$port")
                        appendLine("PROTOCOL=${normalizedEngine.uppercase()}")
                        appendLine("STRATEGY=$normalizedMode")
                        appendLine("SOURCE=$source")
                    }.trim()
                },
                lastVerifiedAt = if (!qualityFailed) nowMs else base.lastVerifiedAt,
                promotedAt = if (qualityFailed) 0L else base.promotedAt,
                successCount = if (!qualityFailed) {
                    maxOf(base.successCount, 1) + 1
                } else {
                    base.successCount
                },
                scope = normalizedScope,
                qualityProbeCount = safeProbeCount,
                qualityPingSuccesses = if (qualityFailed) 0 else safePingSuccesses,
                qualityAvgPingMs = if (qualityFailed) 0.0 else safeAvgPingMs,
                qualityLastCheckedAt = nowMs,
                qualityFailureCount = if (qualityFailed) base.qualityFailureCount + 1 else 0,
                preferredPorts = updateWarpPortStats(
                    base.preferredPorts,
                    port = port,
                    success = !qualityFailed,
                    probeCount = safeProbeCount,
                    pingSuccesses = safePingSuccesses,
                    avgPingMs = safeAvgPingMs,
                    nowMs = nowMs,
                ),
            )
            saveWarpVerifiedConfigs(existing.values.toList())
        }
    }

    fun recordWarpVerifiedDegradedQualityResult(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        probeCount: Int,
        pingSuccesses: Int,
        avgPingMs: Double,
        endpointSource: String,
        rawConfig: String? = null,
        scope: String = STRATEGY_SCOPE_DEFAULT,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(warpVerifiedConfigsLock) {
            if (port !in 1..65535) return
            val normalizedMode = mode.trim()
            val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
            if (normalizedMode.isBlank() || normalizedHost.isBlank()) return
            if (!isAllowedWarpVerifiedMode(normalizedMode)) return
            val normalizedEngine = engine.trim().ifBlank { "wireguard" }
            val normalizedScope = inferWarpVerifiedScope(normalizedMode, scope)
            val configId = buildWarpConfigId(normalizedMode, normalizedHost, port, normalizedScope)
            val existing = getWarpVerifiedConfigs().associateBy { it.id }.toMutableMap()
            val previous = existing[configId] ?: return
            val source = endpointSource.trim().ifBlank { previous.endpointSource.ifBlank { "runtime-quality-degraded" } }
            if (!isPersistableWarpVerifiedConfig(normalizedEngine, normalizedHost, previous.manual, previous.userImported, source)) return

            val safeProbeCount = probeCount.coerceAtLeast(previous.qualityProbeCount.coerceAtLeast(1))
            val safePingSuccesses = pingSuccesses
                .coerceIn(0, safeProbeCount.coerceAtLeast(pingSuccesses))
                .coerceAtLeast(1)
            val safeAvgPingMs = avgPingMs.takeIf { it.isFinite() && it > 0.0 }
                ?: previous.qualityAvgPingMs.takeIf { it.isFinite() && it > 0.0 }
                ?: 480.0
            val candidateRawConfig = rawConfig?.trim().orEmpty()
            val normalizedRawConfig = when {
                // Конфигурация встроенного профиля неприкосновенна. Она приходит из
                // прошивки вместе с параметрами обфускации AWG (Jc/Jmin/Jmax, S1..S4,
                // H1..H4, I1..I5), и переписывать её во время работы нечем: служба не
                // знает о профиле ничего, чего не знала бы прошивка. Прежняя защита
                // срабатывала, только когда источник записи отличался от bundled-seed,
                // а при обычном подключении к встроенному профилю он ровно такой —
                // поэтому профиль терял параметры при первом же подключении.
                previous != null && isBundledSeed(previous) && previous.rawConfig.isNotBlank() ->
                    previous.rawConfig
                // Полную конфигурацию не заменяем огрызком — независимо от того,
                // импортирована она пользователем или пришла из прошивки. Раньше
                // условие держалось на `userImported`, и встроенные профили теряли
                // свои AWG-параметры (Jc/Jmin/Jmax, S1..S4, H1..H4, I1..I5) при первой
                // же записи результата: на их место ложилось описание из пяти строк
                // HOST/PORT/PROTOCOL/STRATEGY/SOURCE. Дальше профиль поднимался как
                // обычный WireGuard, без обфускации, и рукопожатие уходило в таймаут.
                previous.rawConfig.contains("[Interface]", ignoreCase = true) &&
                    !candidateRawConfig.contains("[Interface]", ignoreCase = true) ->
                    previous.rawConfig
                candidateRawConfig.isNotBlank() -> candidateRawConfig
                else -> previous.rawConfig
            }
            existing[configId] = previous.copy(
                engine = previous.engine.ifBlank { normalizedEngine },
                endpointSource = source,
                rawConfig = normalizedRawConfig.ifBlank { previous.rawConfig },
                qualityProbeCount = safeProbeCount,
                qualityPingSuccesses = safePingSuccesses,
                qualityAvgPingMs = safeAvgPingMs,
                qualityLastCheckedAt = nowMs,
                qualityFailureCount = previous.qualityFailureCount + 1,
                preferredPorts = updateWarpPortStats(
                    previous.preferredPorts.orEmpty(),
                    port = port,
                    success = false,
                    probeCount = safeProbeCount,
                    pingSuccesses = safePingSuccesses,
                    avgPingMs = safeAvgPingMs,
                    nowMs = nowMs,
                ),
            )
            saveWarpVerifiedConfigs(existing.values.toList())
        }
    }

    fun getWarpVerifiedPriorityScore(
        item: WarpVerifiedConfig,
        nowMs: Long = System.currentTimeMillis(),
    ): Double {
        val normalizedEngine = normalizeToken(item.engine)
        val normalizedMode = normalizeToken(item.mode)
        val normalizedHost = normalizeHost(item.host)
        val normalizedScope = inferWarpVerifiedScope(item.mode, item.scope)
        if (normalizedEngine.isBlank() || normalizedMode.isBlank() || normalizedHost.isBlank() || item.port !in 1..65535) {
            return 0.0
        }
        val exactStats = if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            readStrategyStats(
                strategyExactKey(normalizedEngine, normalizedMode, normalizedHost, item.port),
                legacyStrategyExactKey(normalizedEngine, normalizedMode, normalizedHost, item.port),
            )
        } else {
            readStrategyStats(
                strategyExactKey(normalizedEngine, normalizedMode, normalizedHost, item.port, normalizedScope),
            )
        }
        val arm32Profile = statsProfileKey() == "arm32"
        val freshnessAgeMs = (nowMs - item.lastVerifiedAt).coerceAtLeast(0L)
            .coerceAtMost(30L * 24 * 60 * 60 * 1000L)
        val freshnessScore = 1.0 - freshnessAgeMs.toDouble() / (30.0 * 24 * 60 * 60 * 1000.0)
        val boundedSuccesses = minOf(item.successCount, 4)
        val extraSuccesses = (item.successCount - boundedSuccesses).coerceAtLeast(0)
        val base = boundedSuccesses.toDouble() * 7.0 + extraSuccesses.toDouble() * 1.25
        val unstableExactAttempts = (exactStats.successes - exactStats.stableSuccesses).coerceAtLeast(0)
        val exactRaw = exactStats.stableSuccesses.toDouble() * 10.0 +
            exactStats.stableSuccesses.toDouble() * 6.0 -
            exactStats.failures.toDouble() * 5.0 -
            exactStats.handshakes.toDouble() * 1.5 -
            exactStats.consecutiveFailures.toDouble() * 4.0 -
            unstableExactAttempts.toDouble() * 5.5
        val provenWorkingConfig =
            item.successCount >= 2 ||
                exactStats.stableSuccesses > 0
        val isMasqueConfig = normalizedEngine == "masque" || normalizedMode == "masque"
        val coreMasquePort = item.port in setOf(500, 1701, 4500)
        val exoticMasquePort = item.port in setOf(443, 4443, 8443, 8095)
        val trustedMasquePort = coreMasquePort || exoticMasquePort
        val provenMasqueConfig = isMasqueConfig && trustedMasquePort && item.successCount >= 2
        val exactPenaltyFloor = when {
            !provenWorkingConfig -> Double.NEGATIVE_INFINITY
            provenMasqueConfig && coreMasquePort && item.successCount >= 3 -> -2.0
            provenMasqueConfig && coreMasquePort -> -4.0
            provenMasqueConfig && exoticMasquePort && item.successCount >= 3 -> -6.0
            provenMasqueConfig && exoticMasquePort -> -8.0
            normalizedEngine == "masque" && item.successCount >= 3 -> -10.0
            normalizedEngine == "masque" -> -14.0
            item.successCount >= 3 -> -14.0
            else -> -18.0
        }
        val exact = exactRaw.coerceAtLeast(exactPenaltyFloor)
        val bundledBias = if (item.endpointSource.equals("bundled-seed", ignoreCase = true)) 2.0 else 0.0
        val manualPenalty = if (item.manual) -12.0 else 0.0
        val stableLastSuccessFresh = hasFreshStableLastSuccess(nowMs)
        val exactFreshLastSuccessMatch = isExactFreshWarpVerifiedLastSuccessMatch(item, nowMs)
        val exactLastSuccessBias = when {
            !exactFreshLastSuccessMatch -> 0.0
            stableLastSuccessFresh ->
                if (arm32Profile) 46.0 else 40.0
            item.endpointSource.equals("last-success-exact", ignoreCase = true) ->
                if (arm32Profile) 38.0 else 32.0
            else ->
                if (arm32Profile) 30.0 else 24.0
        }
        val successAgeMs = (nowMs - exactStats.lastSuccessAt).coerceAtLeast(0L)
        val recentSuccessBonus = if (
            exactStats.lastSuccessAt > 0L &&
            exactStats.lastSuccessAt >= exactStats.lastFailureAt &&
            exactStats.stableSuccesses > 0
        ) {
            val freshnessWindowMs = if (arm32Profile) 90L * 60L * 1000L else 45L * 60L * 1000L
            val successFreshness =
                1.0 - (successAgeMs.coerceAtMost(freshnessWindowMs)).toDouble() / freshnessWindowMs.toDouble()
            val baseBonus = if (arm32Profile) 18.0 else 10.0
            val stableBonus = exactStats.stableSuccesses.coerceAtMost(3) * if (arm32Profile) 2.5 else 1.5
            (baseBonus + stableBonus) * successFreshness
        } else {
            0.0
        }
        val failureAgeMs = (nowMs - exactStats.lastFailureAt).coerceAtLeast(0L)
        val recentFailurePenalty = if (
            exactStats.lastFailureAt > exactStats.lastSuccessAt &&
            exactStats.lastFailureAt > 0L
        ) {
            val freshnessWindowMs = 20L * 60L * 1000L
            val failureFreshness = 1.0 - (failureAgeMs.coerceAtMost(freshnessWindowMs)).toDouble() / freshnessWindowMs.toDouble()
            (8.0 + exactStats.consecutiveFailures.coerceAtMost(6) * 2.5) * failureFreshness
        } else {
            0.0
        }
        val profileFailurePenaltyMultiplier = when {
            provenMasqueConfig && coreMasquePort -> if (arm32Profile) 0.85 else 0.9
            provenMasqueConfig && exoticMasquePort -> if (arm32Profile) 1.0 else 1.35
            arm32Profile -> 1.45
            else -> 1.0
        }
        val profileFailurePenalty = recentFailurePenalty * profileFailurePenaltyMultiplier
        val collapsingMasqueWinnerPenalty = if (
            provenMasqueConfig &&
            coreMasquePort &&
            exactStats.lastFailureAt > exactStats.lastSuccessAt &&
            exactStats.consecutiveFailures >= 2
        ) {
            10.0 + exactStats.consecutiveFailures.coerceAtMost(6) * 3.5
        } else {
            0.0
        }
        val exoticMasqueRecentFailurePenalty = if (
            provenMasqueConfig &&
            exoticMasquePort &&
            exactStats.lastFailureAt > exactStats.lastSuccessAt &&
            exactStats.consecutiveFailures >= 1
        ) {
            28.0 + exactStats.consecutiveFailures.coerceAtMost(6) * 8.0
        } else {
            0.0
        }
        val recentValidatedNoTrafficPenalty = if (
            exactStats.validatedNoTrafficFailures > 0 &&
            exactStats.lastFailureAt > exactStats.lastSuccessAt
        ) {
            18.0 + exactStats.validatedNoTrafficFailures.coerceAtMost(6) * 7.0
        } else if (
            exactStats.validatedNoTrafficFailures > 0 &&
            exactStats.stableSuccesses <= 0
        ) {
            exactStats.validatedNoTrafficFailures.coerceAtMost(6) * 3.5
        } else {
            0.0
        }
        val poisonExactSignals = exactStats.failures + exactStats.handshakes + unstableExactAttempts
        val masquePortStabilityBias = when {
            !isMasqueConfig -> 0.0
            coreMasquePort && exactStats.stableSuccesses > 0 -> if (arm32Profile) 8.0 else 6.0
            provenMasqueConfig && coreMasquePort -> if (arm32Profile) 12.0 else 9.0
            provenMasqueConfig && exoticMasquePort -> if (arm32Profile) 7.0 else 5.5
            exoticMasquePort && exactStats.stableSuccesses <= 0 && poisonExactSignals > 0 ->
                -18.0 - poisonExactSignals.coerceAtMost(6) * 2.5
            exoticMasquePort && exactStats.stableSuccesses <= 0 -> -8.0
            else -> 0.0
        }
        val messengerScopeBias = if (normalizedScope == STRATEGY_SCOPE_MESSENGER && normalizedMode.contains("chat")) 3.0 else 0.0
        val messengerPortBias = if (normalizedScope == STRATEGY_SCOPE_MESSENGER && normalizedMode.contains("chat")) {
            when (item.port) {
                500 -> 5.0
                4500 -> 4.0
                1701 -> 3.0
                443 -> 1.0
                2408 -> 0.5
                988 -> -8.0
                else -> 0.0
            }
        } else {
            0.0
        }
        val defaultWarpStabilityBias = when {
            isMasqueConfig -> 0.0
            normalizedMode == "warp-awg-max" -> 6.0
            normalizedMode == "warp-awg-lite" -> 3.5
            normalizedMode == "warp-awg-v2" &&
                exactStats.stableSuccesses <= 0 -> -6.0
            else -> 0.0
        }
        val qualityAgeMs = (nowMs - item.qualityLastCheckedAt).coerceAtLeast(0L)
        val qualityFreshness = if (item.qualityLastCheckedAt > 0L) {
            1.0 - qualityAgeMs.coerceAtMost(6L * 60L * 60L * 1000L).toDouble() /
                (6.0 * 60.0 * 60.0 * 1000.0)
        } else {
            0.0
        }
        val qualitySuccessScore = item.qualityPingSuccesses.coerceAtMost(20).toDouble() * 3.0 * qualityFreshness
        val qualityLatencyScore = if (item.qualityPingSuccesses > 0 && item.qualityAvgPingMs > 0.0) {
            (24.0 - (item.qualityAvgPingMs / 18.0)).coerceIn(0.0, 24.0) * qualityFreshness
        } else {
            0.0
        }
        val qualityFailurePenalty = item.qualityFailureCount.coerceAtMost(8).toDouble() * 7.0 * qualityFreshness
        // Узел, который не удерживает сессию, до сих пор считался хорошим: пинг у
        // него нормальный и подключение проходит. Штрафуем за превышение здоровой
        // единицы на окно, с потолком — чтобы один плохой вечер не хоронил узел.
        val churnPerWindow = warpConfigChurnPerWindow(item)
        val churnPenalty = if (churnPerWindow < 0.0) {
            0.0
        } else {
            ((churnPerWindow - 1.0).coerceAtLeast(0.0) * 12.0).coerceAtMost(36.0)
        }
        // Тот же дефект пути, замеренный дёшево — в коротком окне адаптации, а не
        // двухминутными окнами живой сессии. Потолок ниже churn'а намеренно:
        // одно двадцатисекундное окно слабее двух минут под нагрузкой, и
        // перевешивать более дорогой замер оно не должно.
        val holdPenalty = SessionHoldMetric.penalty(
            windows = item.holdWindows,
            stallMs = item.holdStallMs,
            spanMs = item.holdSpanMs,
            checkedAtMs = item.holdCheckedAt,
            nowMs = nowMs,
        )
        // Потолок общий, а не по штрафу на каждый. Оба замера описывают один и тот
        // же дефект пути — пропадающий обратный поток, — и на обычном подключении
        // снимаются с одних и тех же секунд: сэмплер меряет тишину в первом окне,
        // а тикер живой сессии считает там же перерукопожатия. Два независимых
        // вычета за одно наблюдение перекосили бы оценку, и увидеть это в журнале
        // было бы нечем.
        val pathInstabilityPenalty = (churnPenalty + holdPenalty).coerceAtMost(36.0)
        return base +
            exact +
            freshnessScore * 10.0 +
            bundledBias +
            manualPenalty +
            exactLastSuccessBias +
            recentSuccessBonus +
            masquePortStabilityBias +
            defaultWarpStabilityBias +
            messengerScopeBias +
            messengerPortBias +
            qualitySuccessScore +
            qualityLatencyScore -
            qualityFailurePenalty -
            pathInstabilityPenalty -
            profileFailurePenalty -
            collapsingMasqueWinnerPenalty -
            exoticMasqueRecentFailurePenalty -
            recentValidatedNoTrafficPenalty
    }

    fun isWarpVerifiedConfigWorking(
        item: WarpVerifiedConfig,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val stats = readWarpVerifiedExactStats(item)
        val effectiveLastCheckedAt = effectiveWarpQualityLastCheckedAt(item)
        val effectiveFailures = effectiveWarpQualityFailureCount(item)
        val effectivePingSuccesses = effectiveWarpQualityPingSuccesses(item)
        val effectiveAvgPingMs = effectiveWarpQualityAvgPingMs(item)
        if (
            effectiveLastCheckedAt > 0L &&
            effectiveFailures > 0 &&
            effectivePingSuccesses <= 0 &&
            effectiveLastCheckedAt >= item.lastVerifiedAt
        ) {
            return false
        }
        if (effectivePingSuccesses >= 3 && effectiveAvgPingMs > 0.0) {
            return true
        }
        if (stats.lastFailureAt > stats.lastSuccessAt && stats.consecutiveFailures > 0) {
            return false
        }
        if (stats.stableSuccesses > 0 && stats.lastSuccessAt >= stats.lastFailureAt) {
            return true
        }
        if (stats.successes > 0 && stats.lastSuccessAt >= stats.lastFailureAt) {
            return true
        }
        return item.successCount >= 2 &&
            item.lastVerifiedAt > 0L &&
            nowMs - item.lastVerifiedAt <= 30L * 24L * 60L * 60L * 1000L
    }

    private fun readWarpVerifiedExactStats(item: WarpVerifiedConfig): StrategyStats {
        val normalizedEngine = normalizeToken(item.engine)
        val normalizedMode = normalizeToken(item.mode)
        val normalizedHost = normalizeHost(item.host)
        val normalizedScope = inferWarpVerifiedScope(item.mode, item.scope)
        if (normalizedEngine.isBlank() || normalizedMode.isBlank() || normalizedHost.isBlank() || item.port !in 1..65535) {
            return StrategyStats()
        }
        return if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            readStrategyStats(
                strategyExactKey(normalizedEngine, normalizedMode, normalizedHost, item.port),
                legacyStrategyExactKey(normalizedEngine, normalizedMode, normalizedHost, item.port),
            )
        } else {
            readStrategyStats(
                strategyExactKey(normalizedEngine, normalizedMode, normalizedHost, item.port, normalizedScope),
            )
        }
    }

    fun addManualWarpConfig(rawConfig: String, nowMs: Long = System.currentTimeMillis()): WarpVerifiedConfig? {
        synchronized(warpVerifiedConfigsLock) {
            val cleaned = rawConfig.trim()
            if (cleaned.isBlank()) return null
            val marker = "manual-${nowMs}-${cleaned.hashCode()}"
            val config = WarpVerifiedConfig(
                id = marker,
                engine = "manual",
                mode = "manual",
                host = "manual",
                port = 1,
                endpointSource = "manual",
                rawConfig = cleaned,
                createdAt = nowMs,
                lastVerifiedAt = nowMs,
                successCount = 1,
                scope = STRATEGY_SCOPE_DEFAULT,
                manual = true,
                userImported = false,
            )
            val list = getWarpVerifiedConfigs().toMutableList()
            list.removeAll { it.id == marker }
            list.add(config)
            saveWarpVerifiedConfigs(list)
            return config
        }
    }

    private data class BundledWarpSeed(
        val engine: String,
        val mode: String,
        val host: String,
        val port: Int,
        val endpointSource: String,
        val rawConfig: String,
        val successCount: Int,
        val lastVerifiedAt: Long,
        val scope: String,
        val preferredSni: String = "",
        val preferredPorts: List<WarpPortStat> = emptyList(),
        val seedOrder: Int = Int.MAX_VALUE,
    )

    private data class VpnDnsProfile(
        val label: String,
        val servers: List<String>,
        val operaBootstrapResolvers: String,
        val operaBootstrapLabel: String,
    )

    private fun ensureBundledVerifiedWarpSeeds() {
        synchronized(warpVerifiedConfigsLock) {
            val rawSeeds = runCatching {
                appContext.assets.open(WARP_VERIFIED_SEEDS_ASSET_NAME).bufferedReader().use { it.readText() }
            }.getOrNull().orEmpty()
            if (rawSeeds.isBlank()) return

            val assetVersion = rawSeeds.hashCode().toString()
            val importedVersion = prefs.getString("warp_verified_seeds_version", null).orEmpty()

            val parsedSeeds = loadBundledVerifiedWarpSeeds(rawSeeds)
            if (parsedSeeds.isEmpty()) return
            val currentById = getWarpVerifiedConfigs().associateBy { it.id }
            val exportedStatsById = getWarpVerifiedExportSnapshot().associateBy { it.id }
            val parsedSeedIds = parsedSeeds
                .mapTo(mutableSetOf()) { seed -> buildWarpConfigId(seed.mode, seed.host, seed.port, seed.scope) }
            val currentSeeds = currentById.values.filter(::isBundledSeed)
            val currentSeedIds = currentSeeds
                .mapTo(mutableSetOf()) { it.id }
            val parsedRawConfigById = parsedSeeds.associate { seed ->
                buildWarpConfigId(seed.mode, seed.host, seed.port, seed.scope) to seed.rawConfig.trim()
            }
            val currentSeedsMatchAsset = currentSeedIds == parsedSeedIds &&
                currentSeeds.all { seed -> seed.rawConfig.trim() == parsedRawConfigById[seed.id] }
            val exportStatsAlreadyMerged = parsedSeedIds.all { id ->
                val current = currentById[id]
                val exported = exportedStatsById[id]
                exported == null || (
                    current != null &&
                        current.successCount >= exported.successCount &&
                        current.lastVerifiedAt >= exported.lastVerifiedAt &&
                        current.qualityProbeCount >= exported.qualityProbeCount &&
                        current.qualityPingSuccesses >= exported.qualityPingSuccesses &&
                        current.qualityLastCheckedAt >= exported.qualityLastCheckedAt
                    )
            }
            if (assetVersion == importedVersion && currentSeedsMatchAsset && exportStatsAlreadyMerged) return

            val existing = currentById.toMutableMap()
            existing.values.removeAll { isBundledSeed(it) }

            for (seed in parsedSeeds) {
                val configId = buildWarpConfigId(seed.mode, seed.host, seed.port, seed.scope)
                val baseConfig = WarpVerifiedConfig(
                    id = configId,
                    engine = seed.engine,
                    mode = seed.mode,
                    host = seed.host,
                    port = seed.port,
                    endpointSource = seed.endpointSource,
                    rawConfig = seed.rawConfig,
                    createdAt = seed.lastVerifiedAt,
                    lastVerifiedAt = seed.lastVerifiedAt,
                    successCount = seed.successCount.coerceAtLeast(1),
                    scope = seed.scope,
                    manual = false,
                    seedOrder = seed.seedOrder,
                    preferredSni = seed.preferredSni,
                    preferredPorts = seed.preferredPorts.ifEmpty {
                        updateWarpPortStats(emptyList(), seed.port, success = true, nowMs = seed.lastVerifiedAt)
                    },
                )
                existing[configId] = listOfNotNull(
                    currentById[configId],
                    exportedStatsById[configId],
                ).fold(baseConfig) { merged, statsSource ->
                    mergeWarpVerifiedExportStats(merged, statsSource)
                }
            }

            saveWarpVerifiedConfigs(existing.values.toList())
            prefs.edit().putString("warp_verified_seeds_version", assetVersion).apply()
        }
    }

    private fun loadBundledVerifiedWarpSeeds(raw: String): List<BundledWarpSeed> {
        return try {
            val array = JSONObject("""{"items":$raw}""").optJSONArray("items") ?: return emptyList()
            buildList {
                for (index in 0 until array.length()) {
                    val json = array.optJSONObject(index) ?: continue
                    val engine = json.optString("engine").trim()
                    val mode = json.optString("mode").trim()
                    val host = json.optString("host").trim().removePrefix("[").removeSuffix("]")
                    val endpointSource = json.optString("endpoint_source").trim().ifBlank { "bundled-seed" }
                    if (engine.isBlank() || mode.isBlank() || host.isBlank()) continue
                    if (!isAllowedWarpVerifiedMode(mode)) continue
                    if (!isAllowedVerifiedWarpEndpoint(engine, host, manual = false, endpointSource = endpointSource)) continue
                    val port = json.optInt("port", -1)
                    if (port !in 1..65535) continue
                    val scope = inferWarpVerifiedScope(mode, json.optString("scope"))
                    val lastVerifiedAt = json.optLong("last_verified_at", 0L).takeIf { it > 0L } ?: System.currentTimeMillis()
                    val rawConfig = json.optString("raw_config").trim().ifBlank {
                        buildString {
                            appendLine("HOST=$host")
                            appendLine("PORT=$port")
                            appendLine("PROTOCOL=${engine.uppercase()}")
                            appendLine("STRATEGY=$mode")
                            appendLine("SOURCE=$endpointSource")
                            val preferredSni = normalizeOptionalTrafficMaskHost(json.optString("preferred_sni"))
                            if (preferredSni.isNotBlank()) {
                                appendLine("PREFERRED_SNI=$preferredSni")
                            }
                        }.trim()
                    }
                    add(
                        BundledWarpSeed(
                            engine = engine,
                            mode = mode,
                            host = host,
                            port = port,
                            endpointSource = endpointSource,
                            rawConfig = rawConfig,
                            successCount = json.optInt("success_count", 1).coerceAtLeast(1),
                            lastVerifiedAt = lastVerifiedAt,
                            scope = scope,
                            preferredSni = normalizeOptionalTrafficMaskHost(json.optString("preferred_sni")),
                            preferredPorts = parseWarpPortStats(json, port).ifEmpty {
                                updateWarpPortStats(emptyList(), port, success = true, nowMs = lastVerifiedAt)
                            },
                            seedOrder = index,
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveMasqueConfigJson(json: String?) {
        writeAtomicRaw(masqueConfigFile, json.orEmpty())
        val editor = prefs.edit()
        // Зеркало в настройках больше не ведём — оно и было источником потери. Старое
        // значение убираем, чтобы никто не прочитал его вместо файла.
        editor.remove("masque_config_json")
        // Ключ получен — обещание закрыто.
        if (!json.isNullOrBlank()) editor.remove("masque_identity_wanted")
        // Паузы адресов держатся на прежнем ключе и вместе с ним заканчиваются.
        //
        // Единая точка: новый ключ появляется только здесь, поэтому здесь же и
        // единственное место, где срок жизни пауз можно привязать к поколению ключа, не
        // рассчитывая на дисциплину вызывающих.
        if (!json.isNullOrBlank()) {
            prefs.all.keys
                .filter { it.startsWith("warp_attempt_cooldown|masque|") }
                .forEach { editor.remove(it) }
        }
        editor.commit()
    }

    /**
     * Приложение обещало пользователю добыть ключ MASQUE и ещё не добыло.
     *
     * Нужно, чтобы не сложился тупик. Ключ добывается только через уже поднятый туннель, и
     * добывать его «на всякий случай» при любом подключении не нужно — это запросы к
     * Cloudflare ради протокола, которым не пользуются. Но когда выбран MASQUE и ключа
     * нет, приложение останавливает цикл и просит подключиться по «Авто» — а при «Авто»
     * MASQUE уже не выбран, и добывать было бы некому. Отметка переживает смену региона и
     * снимается сама, как только ключ сохранён.
     */
    fun isMasqueIdentityWanted(): Boolean = prefs.getBoolean("masque_identity_wanted", false)

    fun setMasqueIdentityWanted(wanted: Boolean) {
        prefs.edit().apply {
            if (wanted) putBoolean("masque_identity_wanted", true) else remove("masque_identity_wanted")
        }.commit()
    }

    fun saveLastExitObservation(
        ip: String?,
        country: String?,
        colo: String?,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val normalizedIp = ip?.trim().orEmpty()
        val normalizedCountry = country?.trim()?.uppercase().orEmpty()
        val normalizedColo = colo?.trim()?.uppercase().orEmpty()
        if (normalizedIp.isBlank() && normalizedCountry.isBlank() && normalizedColo.isBlank()) return

        prefs.edit().apply {
            if (normalizedIp.isNotBlank()) putString("last_exit_ip", normalizedIp)
            if (normalizedCountry.isNotBlank()) putString("last_exit_country", normalizedCountry)
            if (normalizedColo.isNotBlank()) putString("last_exit_colo", normalizedColo)
            putLong("last_exit_observed_at", nowMs)

            val exactKey = currentLastSuccessExitKey()
            if (exactKey != null) {
                putString(
                    exactKey,
                    encodeExitObservation(
                        ExitObservation(
                            country = normalizedCountry,
                            colo = normalizedColo,
                            ip = normalizedIp,
                            observedAt = nowMs,
                        )
                    )
                )
            }
            apply()
        }
        writeAtomicRaw(
            lastExitObservationFile,
            JSONObject().apply {
                put("ip", normalizedIp)
                put("country", normalizedCountry)
                put("colo", normalizedColo)
                put("observed_at", nowMs)
            }.toString()
        )
    }

    fun recordStrategyOutcome(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        outcome: String,
        connectDurationMs: Long,
        stableDurationMs: Long,
        strategyScope: String = STRATEGY_SCOPE_DEFAULT,
        networkClass: String? = null,
        failureReason: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (port !in 1..65535) return
        val normalizedHost = normalizeHost(host)
        val normalizedEngine = normalizeToken(engine)
        val normalizedMode = normalizeToken(mode)
        val normalizedScope = normalizeStrategyScope(strategyScope)
        val normalizedNetworkClass = normalizeStrategyNetworkClass(networkClass)
        if (normalizedHost.isBlank() || normalizedEngine.isBlank() || normalizedMode.isBlank()) return

        val editor = prefs.edit()
        val exactKey = strategyExactKey(normalizedEngine, normalizedMode, normalizedHost, port, normalizedScope)
        val modePortKey = strategyModePortKey(normalizedEngine, normalizedMode, port, normalizedScope)
        val modeOnlyKey = strategyModeKey(normalizedEngine, normalizedMode, normalizedScope)
        val legacyExactKey = if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            legacyStrategyExactKey(normalizedEngine, normalizedMode, normalizedHost, port)
        } else {
            null
        }
        val legacyModePortKey = if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            legacyStrategyModePortKey(normalizedEngine, normalizedMode, port)
        } else {
            null
        }
        val legacyModeOnlyKey = if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            legacyStrategyModeKey(normalizedEngine, normalizedMode)
        } else {
            null
        }

        updateStrategyStats(
            editor,
            exactKey,
            legacyExactKey,
            outcome,
            connectDurationMs,
            stableDurationMs,
            failureReason,
            nowMs,
        )
        updateStrategyStats(
            editor,
            modePortKey,
            legacyModePortKey,
            outcome,
            connectDurationMs,
            stableDurationMs,
            failureReason,
            nowMs,
        )
        updateStrategyStats(
            editor,
            modeOnlyKey,
            legacyModeOnlyKey,
            outcome,
            connectDurationMs,
            stableDurationMs,
            failureReason,
            nowMs,
        )
        if (normalizedNetworkClass != null) {
            updateStrategyStats(
                editor,
                strategyNetworkExactKey(
                    normalizedEngine,
                    normalizedMode,
                    normalizedHost,
                    port,
                    normalizedNetworkClass,
                    normalizedScope,
                ),
                null,
                outcome,
                connectDurationMs,
                stableDurationMs,
                failureReason,
                nowMs,
            )
            updateStrategyStats(
                editor,
                strategyNetworkModePortKey(
                    normalizedEngine,
                    normalizedMode,
                    port,
                    normalizedNetworkClass,
                    normalizedScope,
                ),
                null,
                outcome,
                connectDurationMs,
                stableDurationMs,
                failureReason,
                nowMs,
            )
            updateStrategyStats(
                editor,
                strategyNetworkModeKey(
                    normalizedEngine,
                    normalizedMode,
                    normalizedNetworkClass,
                    normalizedScope,
                ),
                null,
                outcome,
                connectDurationMs,
                stableDurationMs,
                failureReason,
                nowMs,
            )
        }
        editor.apply()
    }

    fun getStrategyScore(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        strategyScope: String = STRATEGY_SCOPE_DEFAULT,
        networkClass: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Double {
        if (port !in 1..65535) return 0.0
        val normalizedHost = normalizeHost(host)
        val normalizedEngine = normalizeToken(engine)
        val normalizedMode = normalizeToken(mode)
        val normalizedScope = normalizeStrategyScope(strategyScope)
        val normalizedNetworkClass = normalizeStrategyNetworkClass(networkClass)
        val arm32Profile = statsProfileKey() == "arm32"

        val exactStats = if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            readStrategyStats(
                strategyExactKey(normalizedEngine, normalizedMode, normalizedHost, port),
                legacyStrategyExactKey(normalizedEngine, normalizedMode, normalizedHost, port),
            )
        } else {
            readStrategyStats(
                strategyExactKey(normalizedEngine, normalizedMode, normalizedHost, port, normalizedScope),
            )
        }
        val modePortStats = if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            readStrategyStats(
                strategyModePortKey(normalizedEngine, normalizedMode, port),
                legacyStrategyModePortKey(normalizedEngine, normalizedMode, port),
            )
        } else {
            readStrategyStats(
                strategyModePortKey(normalizedEngine, normalizedMode, port, normalizedScope),
            )
        }
        val modeOnlyStats = if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            readStrategyStats(
                strategyModeKey(normalizedEngine, normalizedMode),
                legacyStrategyModeKey(normalizedEngine, normalizedMode),
            )
        } else {
            readStrategyStats(
                strategyModeKey(normalizedEngine, normalizedMode, normalizedScope),
            )
        }
        val networkExactStats = normalizedNetworkClass?.let { network ->
            readStrategyStats(
                strategyNetworkExactKey(
                    normalizedEngine,
                    normalizedMode,
                    normalizedHost,
                    port,
                    network,
                    normalizedScope,
                ),
            )
        } ?: StrategyStats()
        val networkModePortStats = normalizedNetworkClass?.let { network ->
            readStrategyStats(
                strategyNetworkModePortKey(
                    normalizedEngine,
                    normalizedMode,
                    port,
                    network,
                    normalizedScope,
                ),
            )
        } ?: StrategyStats()
        val networkModeOnlyStats = normalizedNetworkClass?.let { network ->
            readStrategyStats(
                strategyNetworkModeKey(
                    normalizedEngine,
                    normalizedMode,
                    network,
                    normalizedScope,
                ),
            )
        } ?: StrategyStats()

        val exactScore = scoreStrategyStats(
            exactStats,
            nowMs
        )
        val modePortScore = scoreStrategyStats(
            modePortStats,
            nowMs
        )
        val modeOnlyScore = scoreStrategyStats(
            modeOnlyStats,
            nowMs
        )
        val networkExactScore = scoreStrategyStats(networkExactStats, nowMs)
        val networkModePortScore = scoreStrategyStats(networkModePortStats, nowMs)
        val networkModeOnlyScore = scoreStrategyStats(networkModeOnlyStats, nowMs)

        var weighted = 0.0
        var totalWeight = 0.0
        if (normalizedNetworkClass != null) {
            if (networkExactScore != null) {
                weighted += networkExactScore * 0.45
                totalWeight += 0.45
            }
            if (networkModePortScore != null) {
                weighted += networkModePortScore * 0.18
                totalWeight += 0.18
            }
            if (networkModeOnlyScore != null) {
                weighted += networkModeOnlyScore * 0.09
                totalWeight += 0.09
            }
            if (exactScore != null) {
                weighted += exactScore * 0.18
                totalWeight += 0.18
            }
            if (modePortScore != null) {
                weighted += modePortScore * 0.07
                totalWeight += 0.07
            }
            if (modeOnlyScore != null) {
                weighted += modeOnlyScore * 0.03
                totalWeight += 0.03
            }
        } else {
            if (exactScore != null) {
                weighted += exactScore * 0.52
                totalWeight += 0.52
            }
            if (modePortScore != null) {
                weighted += modePortScore * 0.33
                totalWeight += 0.33
            }
            if (modeOnlyScore != null) {
                weighted += modeOnlyScore * 0.15
                totalWeight += 0.15
            }
        }
        if (normalizedScope != STRATEGY_SCOPE_DEFAULT) {
            val genericExactScore = scoreStrategyStats(
                readStrategyStats(
                    strategyExactKey(normalizedEngine, normalizedMode, normalizedHost, port),
                    legacyStrategyExactKey(normalizedEngine, normalizedMode, normalizedHost, port),
                ),
                nowMs,
            )
            val genericModePortScore = scoreStrategyStats(
                readStrategyStats(
                    strategyModePortKey(normalizedEngine, normalizedMode, port),
                    legacyStrategyModePortKey(normalizedEngine, normalizedMode, port),
                ),
                nowMs,
            )
            val genericModeOnlyScore = scoreStrategyStats(
                readStrategyStats(
                    strategyModeKey(normalizedEngine, normalizedMode),
                    legacyStrategyModeKey(normalizedEngine, normalizedMode),
                ),
                nowMs,
            )
            if (genericExactScore != null) {
                weighted += genericExactScore * 0.16
                totalWeight += 0.16
            }
            if (genericModePortScore != null) {
                weighted += genericModePortScore * 0.08
                totalWeight += 0.08
            }
            if (genericModeOnlyScore != null) {
                weighted += genericModeOnlyScore * 0.04
                totalWeight += 0.04
            }
        }

        var score = if (totalWeight > 0.0) weighted / totalWeight else 50.0

        if (exactScore == null) score += 5.0
        if (modePortScore == null) score += 2.5
        if (modeOnlyScore == null) score += 1.0
        if (normalizedNetworkClass != null) {
            if (networkExactScore == null) score += 4.0
            if (networkModePortScore == null) score += 1.8
            if (networkModeOnlyScore == null) score += 0.8
        }
        score += explorationBonus(
            exactAttempts = exactStats.attempts,
            modePortAttempts = modePortStats.attempts,
            modeOnlyAttempts = modeOnlyStats.attempts,
        )
        score += baselinePortBias(normalizedEngine, normalizedMode, port)

        val lastHost = normalizeHost(getLastSuccessEndpoint())
        val lastPort = getLastSuccessPort()
        val lastProtocol = normalizeToken(getLastSuccessProtocol())
        val lastSuccessFresh = hasFreshLastSuccess(nowMs)
        val recentExactFailure = exactStats.lastFailureAt > exactStats.lastSuccessAt && exactStats.failures > 0
        val recentExactSuccess = exactStats.lastSuccessAt > 0L && exactStats.lastSuccessAt >= exactStats.lastFailureAt
        val recentModePortFailure = modePortStats.lastFailureAt > modePortStats.lastSuccessAt && modePortStats.failures > 0
        val recentModePortSuccess = modePortStats.lastSuccessAt > 0L && modePortStats.lastSuccessAt >= modePortStats.lastFailureAt
        val recentNetworkModePortFailure =
            normalizedNetworkClass != null &&
                networkModePortStats.lastFailureAt > networkModePortStats.lastSuccessAt &&
                networkModePortStats.failures > 0
        val lastWasMasque = lastProtocol == "masque"
        val currentIsMasque = normalizedEngine == "masque" || normalizedMode == "masque"
        if (lastSuccessFresh && !recentExactFailure && lastWasMasque == currentIsMasque) {
            if (normalizedHost == lastHost) score += if (arm32Profile) 3.5 else 3.0
            if (port == lastPort) score += if (arm32Profile) 3.0 else 2.5
            if (normalizedMode == lastProtocol || normalizedEngine == lastProtocol) score += if (arm32Profile) 2.4 else 1.8
            if (normalizedHost == lastHost && port == lastPort) score += if (arm32Profile) 15.0 else 11.0
        }
        if (recentExactSuccess) {
            val successAgeMs = (nowMs - exactStats.lastSuccessAt).coerceAtLeast(0L)
            val freshnessWindowMs = if (arm32Profile) 90L * 60L * 1000L else 45L * 60L * 1000L
            val successFreshness =
                1.0 - (successAgeMs.coerceAtMost(freshnessWindowMs)).toDouble() / freshnessWindowMs.toDouble()
            score += (if (arm32Profile) 14.0 else 8.0) * successFreshness
            score += exactStats.stableSuccesses.coerceAtMost(3) * if (arm32Profile) 2.2 else 1.4
        }
        if (recentModePortSuccess) {
            val successAgeMs = (nowMs - modePortStats.lastSuccessAt).coerceAtLeast(0L)
            val freshnessWindowMs = if (arm32Profile) 120L * 60L * 1000L else 60L * 60L * 1000L
            val successFreshness =
                1.0 - (successAgeMs.coerceAtMost(freshnessWindowMs)).toDouble() / freshnessWindowMs.toDouble()
            score += (if (arm32Profile) 7.0 else 4.0) * successFreshness
            score += modePortStats.stableSuccesses.coerceAtMost(4) * if (arm32Profile) 1.8 else 1.1
        }
        if (recentExactFailure) {
            val failurePenaltyBase = 6.0 + exactStats.consecutiveFailures.coerceAtMost(6) * 2.5
            score -= failurePenaltyBase * if (arm32Profile) 1.45 else 1.0
            if (exactStats.successes <= 1 && (exactStats.failures + exactStats.handshakes) >= 3) {
                score -= if (arm32Profile) 15.0 else 12.0
            }
        }
        if (recentModePortFailure) {
            val failurePenaltyBase = 4.0 + modePortStats.consecutiveFailures.coerceAtMost(6) * 1.7
            score -= failurePenaltyBase * if (arm32Profile) 1.35 else 1.0
            if (modePortStats.successes <= 1 && (modePortStats.failures + modePortStats.handshakes) >= 3) {
                score -= if (arm32Profile) 10.0 else 7.0
            }
        }
        if (recentNetworkModePortFailure) {
            score -= if (arm32Profile) 6.5 else 4.5
        }
        val exitPreference = normalizeRegionPreference(getExitRegionPreference())
        if (exitPreference != "auto") {
            val exitObservation = readExitObservation(
                strategyExitKey(normalizedEngine, normalizedMode, normalizedHost, port),
                legacyStrategyExitKey(normalizedEngine, normalizedMode, normalizedHost, port),
            )
            val exitCountry = when {
                exitObservation.country.isNotBlank() -> exitObservation.country
                lastSuccessFresh &&
                    normalizedHost == lastHost &&
                    port == lastPort &&
                    normalizedMode == normalizeToken(getLastSuccessMode()) ->
                    getLastExitCountry().trim().uppercase()
                else -> ""
            }
            if (exitCountry.isNotBlank()) {
                if (regionMatches(exitPreference, exitCountry)) {
                    score += 18.0
                } else {
                    score -= 38.0
                }
            }
        }
        if (currentIsMasque) {
            if (exactStats.handshakes > 0 && exactStats.successes == 0) {
                score -= 8.0 + exactStats.handshakes.coerceAtMost(6) * 2.5
            }
            if (modePortStats.handshakes > 0 && modePortStats.successes == 0) {
                score -= 3.0 + modePortStats.handshakes.coerceAtMost(6) * 0.75
            }
        }
        if (exactStats.failures > 0 && exactStats.successes == 0) {
            score -= 10.0 + exactStats.consecutiveFailures.coerceAtMost(4) * 3.0
        }

        return score
    }

    fun getStrategyDiagnosticTag(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        strategyScope: String = STRATEGY_SCOPE_DEFAULT,
        networkClass: String? = null,
    ): String? {
        if (port !in 1..65535) return null
        val normalizedHost = normalizeHost(host)
        val normalizedEngine = normalizeToken(engine)
        val normalizedMode = normalizeToken(mode)
        val normalizedScope = normalizeStrategyScope(strategyScope)
        val normalizedNetworkClass = normalizeStrategyNetworkClass(networkClass)
        if (normalizedHost.isBlank() || normalizedEngine.isBlank() || normalizedMode.isBlank()) return null

        val genericExactStats = if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            readStrategyStats(
                strategyExactKey(normalizedEngine, normalizedMode, normalizedHost, port),
                legacyStrategyExactKey(normalizedEngine, normalizedMode, normalizedHost, port),
            )
        } else {
            readStrategyStats(
                strategyExactKey(normalizedEngine, normalizedMode, normalizedHost, port, normalizedScope),
            )
        }
        val networkExactStats = normalizedNetworkClass?.let { network ->
            readStrategyStats(
                strategyNetworkExactKey(
                    normalizedEngine,
                    normalizedMode,
                    normalizedHost,
                    port,
                    network,
                    normalizedScope,
                ),
            )
        }
        val networkTag = networkExactStats?.let(::dominantStrategyDiagnosticTag)
        if (networkTag != null) {
            return "${normalizedNetworkClass}:${networkTag}"
        }
        return dominantStrategyDiagnosticTag(genericExactStats)
    }

    fun shouldRetryMasqueBootstrap(nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastFailedAt = prefs.getLong("masque_bootstrap_failed_at", 0L)
        return nowMs - lastFailedAt >= 30 * 1000L
    }

    fun shouldSkipMasqueTransport(nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastFailedAt = prefs.getLong("masque_transport_failed_at", 0L)
        val consecutiveFailures = prefs.getInt("masque_transport_failed_count", 0)
        return lastFailedAt > 0L &&
            consecutiveFailures >= 2 &&
            nowMs - lastFailedAt < MASQUE_TRANSPORT_COOLDOWN_MS
    }

    fun getMasqueTransportFailureCount(): Int {
        return prefs.getInt("masque_transport_failed_count", 0)
    }

    /**
     * Действует ли ещё уступка WARP после серии срывов явно выбранного MASQUE.
     *
     * Счётчик срывов обнуляется только успешным подключением по MASQUE. Без срока
     * действия это ловушка: три срыва — и выбранный протокол больше не пробуется, а
     * успеху взяться неоткуда, потому что попыток нет. Поэтому уступка живёт
     * [MASQUE_EXPLICIT_LOCKOUT_MS] от последнего срыва, а потом выбор пользователя
     * снова получает полный цикл.
     */
    fun isMasqueExplicitLockoutFresh(nowMs: Long = System.currentTimeMillis()): Boolean {
        val lastFailedAt = prefs.getLong("masque_transport_failed_at", 0L)
        if (lastFailedAt <= 0L) return false
        return nowMs - lastFailedAt < MASQUE_EXPLICIT_LOCKOUT_MS
    }

    /** Сколько осталось до следующей полноценной попытки MASQUE, мс. */
    fun getMasqueExplicitLockoutRemainingMs(nowMs: Long = System.currentTimeMillis()): Long {
        val lastFailedAt = prefs.getLong("masque_transport_failed_at", 0L)
        if (lastFailedAt <= 0L) return 0L
        return (lastFailedAt + MASQUE_EXPLICIT_LOCKOUT_MS - nowMs).coerceAtLeast(0L)
    }

    fun markMasqueBootstrapFailure(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong("masque_bootstrap_failed_at", nowMs).apply()
    }

    fun markMasqueTransportFailure(nowMs: Long = System.currentTimeMillis()) {
        val lastFailedAt = prefs.getLong("masque_transport_failed_at", 0L)
        // Серия — это срывы подряд, а не сумма за всё время. Иначе накопленные час
        // назад три срыва возвращали бы уступку сразу после первого нового.
        val previousFailures = if (
            lastFailedAt > 0L && nowMs - lastFailedAt < MASQUE_EXPLICIT_LOCKOUT_MS
        ) {
            prefs.getInt("masque_transport_failed_count", 0)
        } else {
            0
        }
        val nextFailures = (previousFailures + 1).coerceAtMost(8)
        prefs.edit()
            .putLong("masque_transport_failed_at", nowMs)
            .putInt("masque_transport_failed_count", nextFailures)
            .apply()
    }

    fun noteWarpFullCycleFailure(nowMs: Long = System.currentTimeMillis()): Int {
        val nextFailures = (prefs.getInt("warp_full_cycle_failed_count", 0) + 1).coerceAtMost(8)
        prefs.edit()
            .putInt("warp_full_cycle_failed_count", nextFailures)
            .putLong("warp_full_cycle_failed_at", nowMs)
            .apply()
        return nextFailures
    }

    /**
     * Полный цикл WARP/AWG недавно закончился без трафика.
     *
     * Используется как условие для догадки «начать с MASQUE»: пока WARP работает,
     * подменять его нечем. Отметка снимается при первом успешном подключении.
     */
    fun hasFreshWarpFullCycleFailure(
        nowMs: Long = System.currentTimeMillis(),
        freshnessMs: Long = 6L * 60L * 60L * 1000L,
    ): Boolean {
        val failedAt = prefs.getLong("warp_full_cycle_failed_at", 0L)
        if (failedAt <= 0L) return false
        val age = nowMs - failedAt
        return age >= 0L && age <= freshnessMs
    }

    fun clearWarpFullCycleFailureState() {
        prefs.edit()
            .remove("warp_full_cycle_failed_count")
            .remove("warp_full_cycle_failed_at")
            .apply()
    }

    fun getWarpLastColdResetAt(): Long = prefs.getLong("warp_cold_reset_at", 0L)

    fun canRunWarpColdReset(
        nowMs: Long = System.currentTimeMillis(),
        cooldownMs: Long = 10L * 60L * 1000L,
    ): Boolean {
        val lastResetAt = getWarpLastColdResetAt()
        if (lastResetAt <= 0L) return true
        return nowMs - lastResetAt >= cooldownMs
    }

    private fun markWarpColdReset(nowMs: Long) {
        prefs.edit()
            .putLong("warp_cold_reset_at", nowMs)
            .remove("warp_full_cycle_failed_count")
            .remove("warp_full_cycle_failed_at")
            .apply()
    }

    fun resetWarpRuntimeState(
        clearStoredConfig: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        synchronized(warpVerifiedConfigsLock) {
            val manualConfigs = getWarpVerifiedConfigs().filter { it.manual }
            val preservedImportedConfigs = getWarpVerifiedConfigs().filter { it.userImported }
            val strategyPrefixes = listOf(
                "strategy_exact|",
                "strategy_mode_port|",
                "strategy_mode|",
                "strategy_net_exact|",
                "strategy_net_mode_port|",
                "strategy_net_mode|",
                "strategy_exit|",
                "registration_route|",
                TRAFFIC_MASK_STATS_PREFIX,
            )
            val scopedStablePrefixes = listOf(
                "wifi_stable_last_success_",
                "cell_stable_last_success_",
                "eth_stable_last_success_",
                "other_stable_last_success_",
            )
            prefs.edit().apply {
                remove("last_success_port")
                remove("last_success_protocol")
                remove("last_success_at")
                remove("last_success_endpoint")
                remove("last_success_mode")
                remove("stable_last_success_port")
                remove("stable_last_success_protocol")
                remove("stable_last_success_at")
                remove("stable_last_success_endpoint")
                remove("stable_last_success_mode")
                remove("stable_last_success_network_signature")
                remove("masque_transport_failed_at")
                remove("masque_transport_failed_count")
                remove("masque_bootstrap_failed_at")
                remove("warp_verified_seeds_version")
                remove("restart_session_json")
                remove("soft_reapply_pending_until")
                remove("transient_connecting_pending_until")
                remove("restricted_mobile_network_id")
                remove("restricted_mobile_detected")
                remove("restricted_mobile_checked_at")
                prefs.all.keys
                    .filter { key -> strategyPrefixes.any(key::startsWith) || scopedStablePrefixes.any(key::startsWith) }
                    .forEach(::remove)
                if (clearStoredConfig) {
                    remove("private_key")
                    remove("public_key")
                    remove("ipv4")
                    remove("ipv6")
                    remove("peer_pub")
                    remove("peer_endpoint")
                    remove("reserved")
                    remove("access_token")
                    remove("device_id")
                    remove("license")
                    remove("is_registered")
                }
                apply()
            }
            // Ключ MASQUE лежит в файле, а не в настройках, — стираем отдельно.
            saveMasqueConfigJson(null)
            saveWarpVerifiedConfigs((manualConfigs + preservedImportedConfigs).distinctBy { it.id })
            ensureBundledVerifiedWarpSeeds()
            clearTunnelUiSnapshot()
            setTrafficMaskActiveHost(null)
            setWarpTrafficMaskActiveHost(null)
            markWarpColdReset(nowMs)
        }
    }

    /**
     * Стирает выученное прошлой версией, когда приложение обновилось.
     *
     * Зачем. Каталог данных переживает установку обновления, и вместе с ним переезжают
     * замеры, рейтинги профилей, последний удачный режим и ключ MASQUE. Из-за
     * `stable_last_success_mode` новая версия начинала не с начала очереди, а с того
     * транспорта, на котором прошлая остановилась: человек ждал встроенный AWG, а
     * получал MASQUE сразу после нажатия. Единственным лечением было «стереть данные»
     * — теперь то же самое делает само приложение при смене versionCode.
     *
     * Что сохраняется: импортированные профили и подписки (`userImported`), профили,
     * добавленные вручную (`manual`), и все настройки, включая выбранный регион, —
     * явный выбор пользователя не трогаем.
     *
     * Отметка версии пишется до сброса: если в этот момент стартует второй процесс,
     * он увидит уже новую версию и не станет стирать всё повторно.
     */
    fun resetLearnedStateAfterUpdate(): Boolean {
        val currentCode = BuildConfig.VERSION_CODE
        val storedCode = readAtomicJson(appVersionStateFile)
            ?.optInt("version_code", -1)
            ?.takeIf { it > 0 }
        if (storedCode == currentCode) return false
        writeAtomicRaw(
            appVersionStateFile,
            JSONObject().put("version_code", currentCode).toString(),
        )
        if (storedCode == null) {
            LogManager.log("Первый запуск на этой установке: сбрасывать нечего, отмечаем versionCode $currentCode.")
            return false
        }
        LogManager.log(
            "Обновление $storedCode → $currentCode: стираем замеры, рейтинги профилей, " +
                "последний удачный режим и ключ MASQUE, чтобы подбор начался с начала."
        )
        resetWarpRuntimeState(clearStoredConfig = true)
        return true
    }

    fun resetWarpStoredRegistrationIdentity() {
        prefs.edit().apply {
            remove("private_key")
            remove("public_key")
            remove("ipv4")
            remove("ipv6")
            remove("peer_pub")
            remove("peer_endpoint")
            remove("reserved")
            remove("access_token")
            remove("device_id")
            remove("license")
            remove("is_registered")
            remove("restart_session_json")
            remove("pending_warp_bootstrap_restart_json")
            remove("pending_warp_bootstrap_restart_until")
            remove("soft_reapply_pending_until")
            remove("transient_connecting_pending_until")
            remove("masque_bootstrap_failed_at")
            remove("warp_full_cycle_failed_count")
            remove("warp_full_cycle_failed_at")
            // Запасная личность — тоже регистрация, и сброс обязан её убирать.
            //
            // Без этой строки «сбросить регистрацию» не сбрасывало ничего: основную
            // конфигурацию стирали, а MASQUE продолжал работать поверх старого
            // устройства из запасной личности. Проверка исправленной регистрации
            // из-за этого показывала прежний результат.
            remove("reserve_warp_identity_json")
            remove("warp_identity_backfill_failed_at")
            commit()
        }
        // Ключ MASQUE лежит в файле, а не в настройках, — стираем отдельно. Иначе «сбросить
        // регистрацию» оставляло бы ключ от устройства, которого больше нет.
        saveMasqueConfigJson(null)
        clearTunnelUiSnapshot()
        setTrafficMaskActiveHost(null)
        setWarpTrafficMaskActiveHost(null)
    }

    fun resetWarpTransportLearning() {
        synchronized(warpVerifiedConfigsLock) {
            val manualConfigs = getWarpVerifiedConfigs().filter { it.manual }
            val preservedImportedConfigs = getWarpVerifiedConfigs().filter { it.userImported }
            val strategyPrefixes = listOf(
                "strategy_exact|",
                "strategy_mode_port|",
                "strategy_mode|",
                "strategy_net_exact|",
                "strategy_net_mode_port|",
                "strategy_net_mode|",
                "strategy_exit|",
                TRAFFIC_MASK_STATS_PREFIX,
            )
            val scopedStablePrefixes = listOf(
                "wifi_stable_last_success_",
                "cell_stable_last_success_",
                "eth_stable_last_success_",
                "other_stable_last_success_",
            )
            prefs.edit().apply {
                remove("last_success_port")
                remove("last_success_protocol")
                remove("last_success_at")
                remove("last_success_endpoint")
                remove("last_success_mode")
                remove("stable_last_success_port")
                remove("stable_last_success_protocol")
                remove("stable_last_success_at")
                remove("stable_last_success_endpoint")
                remove("stable_last_success_mode")
                remove("stable_last_success_network_signature")
                remove("masque_transport_failed_at")
                remove("masque_transport_failed_count")
                remove("masque_bootstrap_failed_at")
                remove("warp_verified_seeds_version")
                prefs.all.keys
                    .filter { key -> strategyPrefixes.any(key::startsWith) || scopedStablePrefixes.any(key::startsWith) }
                    .forEach(::remove)
                apply()
            }
            saveWarpVerifiedConfigs((manualConfigs + preservedImportedConfigs).distinctBy { it.id })
            ensureBundledVerifiedWarpSeeds()
            setTrafficMaskActiveHost(null)
            setWarpTrafficMaskActiveHost(null)
        }
    }

    // Split Tunneling
    fun getSplitMode(): Int = prefs.getInt("split_mode", 0)
    fun setSplitMode(mode: Int) { prefs.edit().putInt("split_mode", mode).commit() }
    fun getSplitApps(): Set<String> = prefs.getStringSet("split_apps", emptySet()) ?: emptySet()
    fun setSplitApps(apps: Set<String>) { prefs.edit().putStringSet("split_apps", apps).commit() }

    private fun loadBundledBootstrapSeed(): BootstrapSeed? {
        return try {
            val rawJson = appContext.assets.open(BOOTSTRAP_ASSET_NAME).bufferedReader().use { it.readText() }
            val json = JSONObject(rawJson)
            val config = WarpConfig(
                privateKey = json.optString("private_key"),
                publicKey = json.optString("public_key"),
                ipv4 = json.optString("ipv4"),
                ipv6 = json.optString("ipv6"),
                peerPublicKey = json.optString("peer_pub"),
                peerEndpoint = json.optString("peer_endpoint"),
                reserved = normalizeReservedValue(json.optString("reserved").takeIf { it.isNotBlank() }),
                accessToken = json.optString("access_token").takeIf { it.isNotBlank() },
                deviceId = json.optString("device_id").takeIf { it.isNotBlank() },
                license = json.optString("license").takeIf { it.isNotBlank() },
                masqueConfigJson = json.optString("masque_config_json").takeIf { it.isNotBlank() },
            )
            BootstrapSeed(
                config = config,
                lastSuccessPort = json.optInt("last_success_port", -1).takeIf { it in 1..65535 },
                lastSuccessProtocol = json.optString("last_success_protocol").takeIf { it.isNotBlank() },
                lastSuccessEndpoint = json.optString("last_success_endpoint").takeIf { it.isNotBlank() },
                lastSuccessMode = json.optString("last_success_mode").takeIf { it.isNotBlank() },
            ).takeIf {
                config.privateKey.isNotBlank() &&
                    config.publicKey.isNotBlank() &&
                    config.ipv4.isNotBlank() &&
                    config.ipv6.isNotBlank() &&
                    config.peerPublicKey.isNotBlank() &&
                    config.peerEndpoint.isNotBlank()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun updateStrategyStats(
        editor: SharedPreferences.Editor,
        key: String,
        fallbackKey: String?,
        outcome: String,
        connectDurationMs: Long,
        stableDurationMs: Long,
        failureReason: String?,
        nowMs: Long,
    ) {
        val current = readStrategyStats(key, fallbackKey)
        val normalizedFailureReason = normalizeToken(failureReason)
        val next = when (normalizeToken(outcome)) {
            "success" -> current.copy(
                attempts = current.attempts + 1,
                successes = current.successes + 1,
                stableSuccesses = current.stableSuccesses + 1,
                consecutiveFailures = 0,
                avgSuccessMs = ewma(current.avgSuccessMs, connectDurationMs),
                avgStableMs = ewma(current.avgStableMs, stableDurationMs),
                lastSuccessAt = nowMs,
            )
            "unstable" -> current.copy(
                attempts = current.attempts + 1,
                handshakes = current.handshakes + 1,
                failures = current.failures + 1,
                consecutiveFailures = (current.consecutiveFailures + 1).coerceAtMost(8),
                validatedNoTrafficFailures = current.validatedNoTrafficFailures + if (normalizedFailureReason == FAILURE_REASON_VALIDATED_NO_TRAFFIC) 1 else 0,
                controlPlaneOnlyFailures = current.controlPlaneOnlyFailures + if (normalizedFailureReason == FAILURE_REASON_CONTROL_PLANE_ONLY) 1 else 0,
                noInboundAfterHandshakeFailures = current.noInboundAfterHandshakeFailures + if (normalizedFailureReason == FAILURE_REASON_NO_INBOUND_AFTER_HANDSHAKE) 1 else 0,
                noTrafficFailures = current.noTrafficFailures + if (normalizedFailureReason == FAILURE_REASON_NO_TRAFFIC) 1 else 0,
                engineCrashFailures = current.engineCrashFailures + if (normalizedFailureReason == FAILURE_REASON_ENGINE_CRASH) 1 else 0,
                underlyingLossFailures = current.underlyingLossFailures + if (normalizedFailureReason == FAILURE_REASON_UNDERLYING_LOSS) 1 else 0,
                lastFailureReason = normalizedFailureReason,
                lastFailureAt = nowMs,
            )
            "handshake" -> current.copy(
                attempts = current.attempts + 1,
                handshakes = current.handshakes + 1,
                consecutiveFailures = (current.consecutiveFailures + 1).coerceAtMost(8),
                validatedNoTrafficFailures = current.validatedNoTrafficFailures + if (normalizedFailureReason == FAILURE_REASON_VALIDATED_NO_TRAFFIC) 1 else 0,
                controlPlaneOnlyFailures = current.controlPlaneOnlyFailures + if (normalizedFailureReason == FAILURE_REASON_CONTROL_PLANE_ONLY) 1 else 0,
                noInboundAfterHandshakeFailures = current.noInboundAfterHandshakeFailures + if (normalizedFailureReason == FAILURE_REASON_NO_INBOUND_AFTER_HANDSHAKE) 1 else 0,
                noTrafficFailures = current.noTrafficFailures + if (normalizedFailureReason == FAILURE_REASON_NO_TRAFFIC) 1 else 0,
                engineCrashFailures = current.engineCrashFailures + if (normalizedFailureReason == FAILURE_REASON_ENGINE_CRASH) 1 else 0,
                underlyingLossFailures = current.underlyingLossFailures + if (normalizedFailureReason == FAILURE_REASON_UNDERLYING_LOSS) 1 else 0,
                lastFailureReason = normalizedFailureReason,
                lastFailureAt = nowMs,
            )
            else -> current.copy(
                attempts = current.attempts + 1,
                failures = current.failures + 1,
                consecutiveFailures = (current.consecutiveFailures + 1).coerceAtMost(8),
                validatedNoTrafficFailures = current.validatedNoTrafficFailures + if (normalizedFailureReason == FAILURE_REASON_VALIDATED_NO_TRAFFIC) 1 else 0,
                controlPlaneOnlyFailures = current.controlPlaneOnlyFailures + if (normalizedFailureReason == FAILURE_REASON_CONTROL_PLANE_ONLY) 1 else 0,
                noInboundAfterHandshakeFailures = current.noInboundAfterHandshakeFailures + if (normalizedFailureReason == FAILURE_REASON_NO_INBOUND_AFTER_HANDSHAKE) 1 else 0,
                noTrafficFailures = current.noTrafficFailures + if (normalizedFailureReason == FAILURE_REASON_NO_TRAFFIC) 1 else 0,
                engineCrashFailures = current.engineCrashFailures + if (normalizedFailureReason == FAILURE_REASON_ENGINE_CRASH) 1 else 0,
                underlyingLossFailures = current.underlyingLossFailures + if (normalizedFailureReason == FAILURE_REASON_UNDERLYING_LOSS) 1 else 0,
                lastFailureReason = normalizedFailureReason,
                lastFailureAt = nowMs,
            )
        }
        editor.putString(key, encodeStrategyStats(next))
    }

    private fun readStrategyStats(key: String, fallbackKey: String? = null): StrategyStats {
        val raw = prefs.getString(key, null).orEmpty().ifBlank {
            fallbackKey?.let { prefs.getString(it, null).orEmpty() }.orEmpty()
        }
        if (raw.isBlank()) return StrategyStats()
        return try {
            val json = JSONObject(raw)
            StrategyStats(
                attempts = json.optInt("attempts", 0),
                successes = json.optInt("successes", 0),
                stableSuccesses = json.optInt("stable_successes", 0),
                handshakes = json.optInt("handshakes", 0),
                failures = json.optInt("failures", 0),
                consecutiveFailures = json.optInt("consecutive_failures", 0),
                avgSuccessMs = json.optDouble("avg_success_ms", 0.0),
                avgStableMs = json.optDouble("avg_stable_ms", 0.0),
                lastSuccessAt = json.optLong("last_success_at", 0L),
                lastFailureAt = json.optLong("last_failure_at", 0L),
                validatedNoTrafficFailures = json.optInt("validated_no_traffic_failures", 0),
                controlPlaneOnlyFailures = json.optInt("control_plane_only_failures", 0),
                noInboundAfterHandshakeFailures = json.optInt("no_inbound_after_handshake_failures", 0),
                noTrafficFailures = json.optInt("no_traffic_failures", 0),
                engineCrashFailures = json.optInt("engine_crash_failures", 0),
                underlyingLossFailures = json.optInt("underlying_loss_failures", 0),
                lastFailureReason = json.optString("last_failure_reason", ""),
            )
        } catch (_: Exception) {
            StrategyStats()
        }
    }

    private fun encodeStrategyStats(stats: StrategyStats): String {
        return JSONObject().apply {
            put("attempts", stats.attempts)
            put("successes", stats.successes)
            put("stable_successes", stats.stableSuccesses)
            put("handshakes", stats.handshakes)
            put("failures", stats.failures)
            put("consecutive_failures", stats.consecutiveFailures)
            put("avg_success_ms", stats.avgSuccessMs)
            put("avg_stable_ms", stats.avgStableMs)
            put("last_success_at", stats.lastSuccessAt)
            put("last_failure_at", stats.lastFailureAt)
            put("validated_no_traffic_failures", stats.validatedNoTrafficFailures)
            put("control_plane_only_failures", stats.controlPlaneOnlyFailures)
            put("no_inbound_after_handshake_failures", stats.noInboundAfterHandshakeFailures)
            put("no_traffic_failures", stats.noTrafficFailures)
            put("engine_crash_failures", stats.engineCrashFailures)
            put("underlying_loss_failures", stats.underlyingLossFailures)
            put("last_failure_reason", stats.lastFailureReason)
        }.toString()
    }

    private fun readExitObservation(key: String, fallbackKey: String? = null): ExitObservation {
        val raw = prefs.getString(key, null).orEmpty().ifBlank {
            fallbackKey?.let { prefs.getString(it, null).orEmpty() }.orEmpty()
        }
        if (raw.isBlank()) return ExitObservation()
        return try {
            val json = JSONObject(raw)
            ExitObservation(
                country = json.optString("country", ""),
                colo = json.optString("colo", ""),
                ip = json.optString("ip", ""),
                observedAt = json.optLong("observed_at", 0L),
            )
        } catch (_: Exception) {
            ExitObservation()
        }
    }

    private fun encodeExitObservation(observation: ExitObservation): String {
        return JSONObject().apply {
            put("country", observation.country)
            put("colo", observation.colo)
            put("ip", observation.ip)
            put("observed_at", observation.observedAt)
        }.toString()
    }

    private fun scoreStrategyStats(stats: StrategyStats, nowMs: Long): Double? {
        if (stats.attempts <= 0) return null

        val attempts = stats.attempts.toDouble()
        val effectiveSuccesses = stats.stableSuccesses
        val unstableAttempts = (stats.successes - stats.stableSuccesses).coerceAtLeast(0)
        val successRate = (effectiveSuccesses + 0.6) / (attempts + 1.2)
        val stableRate = if (stats.successes > 0) {
            stats.stableSuccesses.toDouble() / stats.successes.toDouble()
        } else {
            0.0
        }
        val handshakeRate = (stats.handshakes + 0.2) / (attempts + 1.0)
        val failureRate = stats.failures.toDouble() / attempts
        val speedScore = when {
            stats.avgSuccessMs <= 0.0 -> 0.45
            else -> 1.0 - (stats.avgSuccessMs.coerceAtMost(30_000.0) / 30_000.0)
        }
        val recencyScore = when {
            stats.lastSuccessAt <= 0L -> 0.0
            else -> {
                val ageMs = (nowMs - stats.lastSuccessAt).coerceAtLeast(0L).coerceAtMost(7L * 24 * 60 * 60 * 1000L)
                1.0 - ageMs.toDouble() / (7.0 * 24 * 60 * 60 * 1000.0)
            }
        }
        val confidenceScore = (attempts / 8.0).coerceAtMost(1.0)
        val handshakeOnlyPenalty = when {
            stats.successes <= 0 && stats.handshakes > 0 ->
                stats.handshakes.coerceAtMost(5) * 3.5
            stats.handshakes > stats.successes ->
                (stats.handshakes - stats.successes).coerceAtMost(4) * 1.8
            else -> 0.0
        }
        val unstablePenalty = when {
            unstableAttempts <= 0 -> 0.0
            effectiveSuccesses <= 0 -> unstableAttempts.coerceAtMost(6) * 5.0
            else -> unstableAttempts.coerceAtMost(6) * 2.6
        }
        val diagnosticPenalty =
            stats.validatedNoTrafficFailures.coerceAtMost(6) * 4.8 +
                stats.controlPlaneOnlyFailures.coerceAtMost(6) * 3.3 +
                stats.noInboundAfterHandshakeFailures.coerceAtMost(6) * 2.6 +
                stats.noTrafficFailures.coerceAtMost(6) * 2.2 +
                stats.engineCrashFailures.coerceAtMost(4) * 4.2 +
                stats.underlyingLossFailures.coerceAtMost(4) * 0.25

        return 40.0 +
            successRate * 28.0 +
            stableRate * 18.0 +
            handshakeRate * 8.0 +
            speedScore * 10.0 +
            recencyScore * 8.0 +
            confidenceScore * 4.0 -
            failureRate * 12.0 -
            stats.consecutiveFailures.coerceAtMost(6) * 5.0 -
            handshakeOnlyPenalty -
            unstablePenalty -
            diagnosticPenalty
    }

    private fun dominantStrategyDiagnosticTag(stats: StrategyStats): String? {
        if (stats.attempts <= 0) return null
        val candidates = linkedMapOf(
            "cp" to stats.controlPlaneOnlyFailures,
            "vt" to stats.validatedNoTrafficFailures,
            "ni" to stats.noInboundAfterHandshakeFailures,
            "ec" to stats.engineCrashFailures,
            "hs" to (stats.handshakes - stats.successes).coerceAtLeast(0),
            "nt" to stats.noTrafficFailures,
            "ul" to stats.underlyingLossFailures,
        ).filterValues { it > 0 }
        if (candidates.isEmpty()) return null

        val preferredCode = when (stats.lastFailureReason) {
            FAILURE_REASON_CONTROL_PLANE_ONLY -> "cp"
            FAILURE_REASON_VALIDATED_NO_TRAFFIC -> "vt"
            FAILURE_REASON_NO_INBOUND_AFTER_HANDSHAKE -> "ni"
            FAILURE_REASON_ENGINE_CRASH -> "ec"
            FAILURE_REASON_NO_TRAFFIC -> "nt"
            FAILURE_REASON_UNDERLYING_LOSS -> "ul"
            FAILURE_REASON_HANDSHAKE_TIMEOUT -> "hs"
            else -> null
        }
        val dominant = candidates
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { if (it.key == preferredCode) 0 else 1 }
                    .thenBy { it.key }
            )
            .firstOrNull()
            ?: return null
        return "${dominant.key}${dominant.value}"
    }

    private fun explorationBonus(
        exactAttempts: Int,
        modePortAttempts: Int,
        modeOnlyAttempts: Int,
    ): Double {
        val exact = (3 - exactAttempts.coerceAtMost(3)) * 1.2
        val modePort = (3 - modePortAttempts.coerceAtMost(3)) * 0.8
        val modeOnly = (4 - modeOnlyAttempts.coerceAtMost(4)) * 0.5
        return exact + modePort + modeOnly
    }

    private fun baselinePortBias(engine: String, mode: String, port: Int): Double {
        val isMasque = engine == "masque" || mode == "masque"
        val profile = statsProfileKey()
        return if (isMasque) {
            when (port) {
                1701 -> if (profile == "arm32") 3.4 else 1.8
                500 -> if (profile == "arm32") 3.0 else 2.8
                4500 -> if (profile == "arm32") 2.4 else 2.1
                443 -> if (profile == "arm32") 1.6 else 3.2
                4443 -> 1.4
                8443 -> 1.0
                8095 -> 0.1
                else -> 0.0
            }
        } else {
            when (port) {
                500 -> 3.0
                1701 -> 2.4
                4500 -> 2.0
                988 -> 1.6
                2408 -> 0.8
                443 -> -1.2
                else -> 0.0
            }
        }
    }

    private fun ewma(previous: Double, sampleMs: Long): Double {
        if (sampleMs <= 0L) return previous
        return if (previous <= 0.0) {
            sampleMs.toDouble()
        } else {
            previous * 0.65 + sampleMs.toDouble() * 0.35
        }
    }

    private fun registrationRouteKey(country: String): String {
        return "registration_route|${statsProfileKey()}|${normalizeOperaRegionCode(country).ifBlank { "EU" }}"
    }

    private fun registrationProfileKey(profileId: String): String {
        return "registration_profile|${statsProfileKey()}|${profileId.trim().lowercase(Locale.US)}"
    }

    private fun legacyRegistrationRouteKey(country: String): String {
        return "registration_route|${normalizeOperaRegionCode(country).ifBlank { "EU" }}"
    }

    private fun readRegistrationRouteStats(key: String, fallbackKey: String? = null): RegistrationRouteStats {
        val raw = prefs.getString(key, null).orEmpty().ifBlank {
            fallbackKey?.let { prefs.getString(it, null).orEmpty() }.orEmpty()
        }
        if (raw.isBlank()) return RegistrationRouteStats()
        return try {
            val json = JSONObject(raw)
            RegistrationRouteStats(
                attempts = json.optInt("attempts", 0),
                successes = json.optInt("successes", 0),
                failures = json.optInt("failures", 0),
                consecutiveFailures = json.optInt("consecutive_failures", 0),
                avgSuccessMs = json.optDouble("avg_success_ms", 0.0),
                lastSuccessAt = json.optLong("last_success_at", 0L),
                lastFailureAt = json.optLong("last_failure_at", 0L),
            )
        } catch (_: Exception) {
            RegistrationRouteStats()
        }
    }

    private fun encodeRegistrationRouteStats(stats: RegistrationRouteStats): String {
        return JSONObject().apply {
            put("attempts", stats.attempts)
            put("successes", stats.successes)
            put("failures", stats.failures)
            put("consecutive_failures", stats.consecutiveFailures)
            put("avg_success_ms", stats.avgSuccessMs)
            put("last_success_at", stats.lastSuccessAt)
            put("last_failure_at", stats.lastFailureAt)
        }.toString()
    }

    private fun readRegistrationProfileStats(key: String): RegistrationProfileStats {
        val raw = prefs.getString(key, null).orEmpty()
        if (raw.isBlank()) return RegistrationProfileStats()
        return try {
            val json = JSONObject(raw)
            RegistrationProfileStats(
                attempts = json.optInt("attempts", 0),
                successes = json.optInt("successes", 0),
                failures = json.optInt("failures", 0),
                consecutiveFailures = json.optInt("consecutive_failures", 0),
                avgSuccessMs = json.optDouble("avg_success_ms", 0.0),
                lastSuccessAt = json.optLong("last_success_at", 0L),
                lastFailureAt = json.optLong("last_failure_at", 0L),
            )
        } catch (_: Exception) {
            RegistrationProfileStats()
        }
    }

    private fun encodeRegistrationProfileStats(stats: RegistrationProfileStats): String {
        return JSONObject().apply {
            put("attempts", stats.attempts)
            put("successes", stats.successes)
            put("failures", stats.failures)
            put("consecutive_failures", stats.consecutiveFailures)
            put("avg_success_ms", stats.avgSuccessMs)
            put("last_success_at", stats.lastSuccessAt)
            put("last_failure_at", stats.lastFailureAt)
        }.toString()
    }

    private fun readOperaLaunchPlanStats(key: String): OperaLaunchPlanStats {
        val raw = operaStateString(key)
        if (raw.isBlank()) return OperaLaunchPlanStats()
        return try {
            val json = JSONObject(raw)
            OperaLaunchPlanStats(
                attempts = json.optInt("attempts", 0),
                successes = json.optInt("successes", 0),
                failures = json.optInt("failures", 0),
                consecutiveFailures = json.optInt("consecutive_failures", 0),
                avgSuccessMs = json.optDouble("avg_success_ms", 0.0),
                lastSuccessAt = json.optLong("last_success_at", 0L),
                lastFailureAt = json.optLong("last_failure_at", 0L),
            )
        } catch (_: Exception) {
            OperaLaunchPlanStats()
        }
    }

    private fun encodeOperaLaunchPlanStats(stats: OperaLaunchPlanStats): String {
        return JSONObject().apply {
            put("attempts", stats.attempts)
            put("successes", stats.successes)
            put("failures", stats.failures)
            put("consecutive_failures", stats.consecutiveFailures)
            put("avg_success_ms", stats.avgSuccessMs)
            put("last_success_at", stats.lastSuccessAt)
            put("last_failure_at", stats.lastFailureAt)
        }.toString()
    }

    private fun readOperaRegistrationPlanStats(key: String): OperaRegistrationPlanStats {
        val raw = operaStateString(key)
        if (raw.isBlank()) return OperaRegistrationPlanStats()
        return try {
            val json = JSONObject(raw)
            OperaRegistrationPlanStats(
                attempts = json.optInt("attempts", 0),
                successes = json.optInt("successes", 0),
                failures = json.optInt("failures", 0),
                consecutiveFailures = json.optInt("consecutive_failures", 0),
                avgSuccessMs = json.optDouble("avg_success_ms", 0.0),
                lastSuccessAt = json.optLong("last_success_at", 0L),
                lastFailureAt = json.optLong("last_failure_at", 0L),
            )
        } catch (_: Exception) {
            OperaRegistrationPlanStats()
        }
    }

    private fun encodeOperaRegistrationPlanStats(stats: OperaRegistrationPlanStats): String {
        return JSONObject().apply {
            put("attempts", stats.attempts)
            put("successes", stats.successes)
            put("failures", stats.failures)
            put("consecutive_failures", stats.consecutiveFailures)
            put("avg_success_ms", stats.avgSuccessMs)
            put("last_success_at", stats.lastSuccessAt)
            put("last_failure_at", stats.lastFailureAt)
        }.toString()
    }

    private fun scoreRegistrationRoute(country: String, nowMs: Long): Double {
        val normalizedCountry = normalizeOperaRegionCode(country).ifBlank { "EU" }
        val stats = readRegistrationRouteStats(
            registrationRouteKey(normalizedCountry),
            legacyRegistrationRouteKey(normalizedCountry),
        )
        if (stats.attempts <= 0) {
            return if (normalizedCountry == "EU") 1.0 else 0.5
        }
        val attempts = stats.attempts.toDouble()
        val successRate = (stats.successes + 0.8) / (attempts + 1.6)
        val speedScore = when {
            stats.avgSuccessMs <= 0.0 -> 0.45
            else -> 1.0 - (stats.avgSuccessMs.coerceAtMost(30_000.0) / 30_000.0)
        }
        val recencyScore = when {
            stats.lastSuccessAt <= 0L -> 0.0
            else -> {
                val ageMs = (nowMs - stats.lastSuccessAt).coerceAtLeast(0L).coerceAtMost(7L * 24 * 60 * 60 * 1000L)
                1.0 - ageMs.toDouble() / (7.0 * 24 * 60 * 60 * 1000.0)
            }
        }
        return successRate * 24.0 +
            speedScore * 10.0 +
            recencyScore * 6.0 -
            stats.consecutiveFailures.coerceAtMost(6) * 4.0
    }

    private fun bestOperaBootstrapCountryScore(country: String, nowMs: Long): Double {
        val normalizedCountry = normalizeOperaRegionCode(country).ifBlank { "EU" }
        val registrationPrefix = "opera_registration_plan_${normalizedCountry}_"
        val launchPrefix = "opera_launch_plan_${normalizedCountry}_"
        val bestRegistration = prefs.all.keys
            .asSequence()
            .filter { it.startsWith(registrationPrefix) }
            .map { key -> scoreOperaRegistrationPlanStats(readOperaRegistrationPlanStats(key), nowMs) }
            .maxOrNull()
            ?: 0.0
        if (bestRegistration > 0.0) return bestRegistration
        return prefs.all.keys
            .asSequence()
            .filter { it.startsWith(launchPrefix) }
            .map { key -> scoreOperaLaunchPlanStats(readOperaLaunchPlanStats(key), nowMs) }
            .maxOrNull()
            ?: 0.0
    }

    private fun scoreRegistrationProfile(profileId: String, nowMs: Long): Double {
        val normalizedProfileId = profileId.trim().lowercase(Locale.US)
        if (normalizedProfileId.isBlank()) return 0.0
        val stats = readRegistrationProfileStats(registrationProfileKey(normalizedProfileId))
        if (stats.attempts <= 0) return 0.0
        if (stats.successes <= 0) {
            return -(
                stats.failures.coerceAtMost(6) * 3.0 +
                    stats.consecutiveFailures.coerceAtMost(6) * 4.5
                )
        }
        val attempts = stats.attempts.toDouble()
        val successRate = (stats.successes + 0.75) / (attempts + 1.5)
        val speedScore = when {
            stats.avgSuccessMs <= 0.0 -> 0.35
            else -> 1.0 - (stats.avgSuccessMs.coerceAtMost(90_000.0) / 90_000.0)
        }
        val recencyScore = when {
            stats.lastSuccessAt <= 0L -> 0.0
            else -> {
                val ageMs = (nowMs - stats.lastSuccessAt).coerceAtLeast(0L).coerceAtMost(7L * 24 * 60 * 60 * 1000L)
                1.0 - ageMs.toDouble() / (7.0 * 24 * 60 * 60 * 1000.0)
            }
        }
        return successRate * 28.0 +
            speedScore * 12.0 +
            recencyScore * 6.0 -
            stats.consecutiveFailures.coerceAtMost(6) * 4.5
    }

    private fun strategyExactKey(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        scope: String = STRATEGY_SCOPE_DEFAULT,
    ): String {
        val normalizedScope = normalizeStrategyScope(scope)
        return if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            "strategy_exact|${statsProfileKey()}|$engine|$mode|$host|$port"
        } else {
            "strategy_exact|${statsProfileKey()}|$normalizedScope|$engine|$mode|$host|$port"
        }
    }

    private fun legacyStrategyExactKey(engine: String, mode: String, host: String, port: Int): String {
        return "strategy_exact|$engine|$mode|$host|$port"
    }

    private fun strategyModePortKey(
        engine: String,
        mode: String,
        port: Int,
        scope: String = STRATEGY_SCOPE_DEFAULT,
    ): String {
        val normalizedScope = normalizeStrategyScope(scope)
        return if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            "strategy_mode_port|${statsProfileKey()}|$engine|$mode|$port"
        } else {
            "strategy_mode_port|${statsProfileKey()}|$normalizedScope|$engine|$mode|$port"
        }
    }

    private fun legacyStrategyModePortKey(engine: String, mode: String, port: Int): String {
        return "strategy_mode_port|$engine|$mode|$port"
    }

    private fun strategyModeKey(
        engine: String,
        mode: String,
        scope: String = STRATEGY_SCOPE_DEFAULT,
    ): String {
        val normalizedScope = normalizeStrategyScope(scope)
        return if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            "strategy_mode|${statsProfileKey()}|$engine|$mode"
        } else {
            "strategy_mode|${statsProfileKey()}|$normalizedScope|$engine|$mode"
        }
    }

    private fun legacyStrategyModeKey(engine: String, mode: String): String {
        return "strategy_mode|$engine|$mode"
    }

    private fun strategyNetworkExactKey(
        engine: String,
        mode: String,
        host: String,
        port: Int,
        networkClass: String,
        scope: String = STRATEGY_SCOPE_DEFAULT,
    ): String {
        val normalizedScope = normalizeStrategyScope(scope)
        return if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            "strategy_net_exact|${statsProfileKey()}|$networkClass|$engine|$mode|$host|$port"
        } else {
            "strategy_net_exact|${statsProfileKey()}|$networkClass|$normalizedScope|$engine|$mode|$host|$port"
        }
    }

    private fun strategyNetworkModePortKey(
        engine: String,
        mode: String,
        port: Int,
        networkClass: String,
        scope: String = STRATEGY_SCOPE_DEFAULT,
    ): String {
        val normalizedScope = normalizeStrategyScope(scope)
        return if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            "strategy_net_mode_port|${statsProfileKey()}|$networkClass|$engine|$mode|$port"
        } else {
            "strategy_net_mode_port|${statsProfileKey()}|$networkClass|$normalizedScope|$engine|$mode|$port"
        }
    }

    private fun strategyNetworkModeKey(
        engine: String,
        mode: String,
        networkClass: String,
        scope: String = STRATEGY_SCOPE_DEFAULT,
    ): String {
        val normalizedScope = normalizeStrategyScope(scope)
        return if (normalizedScope == STRATEGY_SCOPE_DEFAULT) {
            "strategy_net_mode|${statsProfileKey()}|$networkClass|$engine|$mode"
        } else {
            "strategy_net_mode|${statsProfileKey()}|$networkClass|$normalizedScope|$engine|$mode"
        }
    }

    private fun strategyExitKey(engine: String, mode: String, host: String, port: Int): String {
        return "strategy_exit|${statsProfileKey()}|$engine|$mode|$host|$port"
    }

    private fun legacyStrategyExitKey(engine: String, mode: String, host: String, port: Int): String {
        return "strategy_exit|$engine|$mode|$host|$port"
    }

    private fun currentLastSuccessExitKey(): String? {
        val mode = normalizeToken(getLastSuccessMode())
        val host = normalizeHost(getLastSuccessEndpoint())
        val port = getLastSuccessPort()
        if (mode.isBlank() || host.isBlank() || port !in 1..65535) return null
        val protocol = normalizeToken(getLastSuccessProtocol())
        val engine = if (protocol == "masque" || mode.startsWith("masque")) "masque" else "wireguard"
        return strategyExitKey(engine, mode, host, port)
    }

    private fun normalizeHost(host: String?): String {
        return host?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase().orEmpty()
    }

    private fun isPlausibleWarpEndpointHost(host: String): Boolean {
        val normalized = normalizeHost(host)
        if (normalized.isBlank()) return false
        if (normalized == "engage.cloudflareclient.com") return true
        val warpPrefixes = listOf(
            "162.159.192.", "162.159.193.", "162.159.195.",
            "162.159.197.", "162.159.198.",
            "162.159.204.",
            "188.114.96.", "188.114.97.", "188.114.98.", "188.114.99.",
            "2606:4700:d0:", "2606:4700:d1:",
        )
        return warpPrefixes.any { normalized.startsWith(it) }
    }

    private fun isAllowedVerifiedWarpEndpoint(
        engine: String,
        host: String,
        manual: Boolean,
        userImported: Boolean = false,
        endpointSource: String = "",
    ): Boolean {
        val normalizedEngine = normalizeToken(engine).ifBlank { "wireguard" }
        val normalizedHost = normalizeHost(host)
        if (normalizedHost.isBlank()) return false
        if (manual || userImported) return true
        if (endpointSource.equals("bundled-seed", ignoreCase = true)) return true
        return (normalizedEngine == "wireguard" || normalizedEngine == "masque") &&
            isPlausibleWarpEndpointHost(normalizedHost)
    }

    private fun isPersistableWarpVerifiedConfig(
        engine: String,
        host: String,
        manual: Boolean,
        userImported: Boolean,
        endpointSource: String,
    ): Boolean {
        if (!isAllowedVerifiedWarpEndpoint(engine, host, manual, userImported, endpointSource)) return false
        if (manual || userImported) return true
        if (isOwnIssuedMasqueEndpointSource(engine, endpointSource)) return true
        return endpointSource.equals("bundled-seed", ignoreCase = true)
    }

    /**
     * Адрес MASQUE, выданный нам самим enroll-ом.
     *
     * Список проверенных конфигураций задуман под встроенные профили, поэтому в него
     * пускали только `bundled-seed`. У MASQUE встроенных профилей нет, адреса приходят
     * из ответа enroll (`masque-v4`/`masque-v6`) — и из-за этого правила ни один узел
     * MASQUE в список не попадал: замеры удержания отваливались с «узла нет в списке
     * проверенных конфигураций», а качество между сессиями не накапливалось вовсе.
     *
     * Сканированные адреса сюда не относятся намеренно: узел прикреплён по
     * `endpoint_pub_key` из enroll, чужой адрес не пройдёт проверку в принципе.
     */
    private fun isOwnIssuedMasqueEndpointSource(engine: String, endpointSource: String): Boolean {
        if (!normalizeToken(engine).equals("masque", ignoreCase = true)) return false
        return normalizeToken(endpointSource) in setOf("masque-v4", "masque-v6")
    }

    private fun isAllowedWarpVerifiedMode(mode: String?): Boolean {
        val normalized = normalizeToken(mode)
        if (normalized.isBlank()) return false
        if (normalized == "masque-consumer" || normalized == "masque-zt" || normalized == "masque") return true
        if (normalized in setOf(
                "warp-awg-exact",
                "warp-awg-v2",
                "warp-awg",
                "warp-awg-lite",
                "warp-awg-max",
                "warp-v1",
                "warp-v2",
                "warp-v3",
                "reserved-only",
            )
        ) return true
        if (normalized.startsWith("quic-")) return false
        if (normalized.contains("fake") || normalized.contains("obfs")) return false
        if (normalized.contains("random") || normalized.contains("trick")) return false
        if (normalized.contains("chat") || normalized.contains("dnsmix") || normalized.contains("quicmix")) return false
        return false
    }

    fun isBundledSeed(config: WarpVerifiedConfig): Boolean {
        return !config.manual && !config.userImported && config.endpointSource.equals("bundled-seed", ignoreCase = true)
    }

    private fun normalizeToken(value: String?): String {
        return value?.trim()?.lowercase().orEmpty()
    }

    private fun normalizeStrategyScope(scope: String?): String {
        return when (scope?.trim()?.lowercase(Locale.US)) {
            STRATEGY_SCOPE_MESSENGER -> STRATEGY_SCOPE_MESSENGER
            else -> STRATEGY_SCOPE_DEFAULT
        }
    }

    private fun normalizeImportedProtocolPreference(value: String?): String {
        return when (value?.trim()?.lowercase(Locale.US)) {
            "awg" -> "awg"
            "masque" -> "masque"
            "warp" -> "warp"
            "wireguard" -> "wireguard"
            "vless" -> "vless"
            else -> "auto"
        }
    }

    private fun normalizeStrategyNetworkClass(networkClass: String?): String? {
        return normalizeStableSuccessNetworkClass(networkClass)
    }

    private fun inferWarpVerifiedScope(mode: String?, scope: String? = null): String {
        val normalizedScope = normalizeStrategyScope(scope)
        if (normalizedScope != STRATEGY_SCOPE_DEFAULT) return normalizedScope
        val normalizedMode = normalizeToken(mode)
        return if (normalizedMode.contains("chat")) STRATEGY_SCOPE_MESSENGER else STRATEGY_SCOPE_DEFAULT
    }

    private fun statsProfileKey(): String {
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().lowercase(Locale.US)
        return when {
            Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && primaryAbi.contains("arm") -> "arm64"
            Build.SUPPORTED_64_BIT_ABIS.isEmpty() && primaryAbi.contains("arm") -> "arm32"
            primaryAbi.contains("x86_64") -> "x64"
            primaryAbi.contains("x86") -> "x86"
            else -> primaryAbi.ifBlank { "generic" }
        }
    }

    private fun buildWarpConfigId(
        mode: String,
        host: String,
        port: Int,
        scope: String = STRATEGY_SCOPE_DEFAULT,
    ): String {
        val normalizedScope = normalizeStrategyScope(scope)
        val suffix = "${normalizeToken(mode)}|${normalizeHost(host)}|$port"
        return if (normalizedScope == STRATEGY_SCOPE_DEFAULT) suffix else "$normalizedScope|$suffix"
    }

    private fun buildUserImportedWarpConfigId(
        mode: String,
        host: String,
        port: Int,
        scope: String = STRATEGY_SCOPE_DEFAULT,
        rawConfig: String,
    ): String {
        val baseId = buildWarpConfigId(mode, host, port, scope)
        val digest = java.util.UUID.nameUUIDFromBytes(rawConfig.trim().toByteArray(Charsets.UTF_8))
            .toString()
            .substringBefore('-')
        return "user|$baseId|$digest"
    }

    private fun resolveStoredWarpConfigId(
        json: JSONObject,
        index: Int,
        mode: String,
        host: String,
        port: Int,
        normalizedScope: String,
        manual: Boolean,
        userImported: Boolean,
        rawConfig: String,
    ): String {
        val storedId = json.optString("id").orEmpty().trim()
        return when {
            manual -> storedId.ifBlank { "manual-$index" }
            userImported -> {
                if (storedId.startsWith("user|")) {
                    storedId
                } else {
                    buildUserImportedWarpConfigId(mode, host, port, normalizedScope, rawConfig)
                }
            }
            else -> storedId.ifBlank { buildWarpConfigId(mode, host, port, normalizedScope) }
        }
    }

    private fun compactWarpVerifiedPortVariantsInMemory(
        items: List<WarpVerifiedConfig>,
    ): List<WarpVerifiedConfig> {
        if (items.size <= 1) return items
        val passthrough = items.filter { it.manual }
        val grouped = items
            .filterNot { it.manual }
            .groupBy(::warpVerifiedPortVariantKey)
        val compacted = grouped.values.map { group ->
            if (group.size == 1) {
                group.first()
            } else {
                mergeWarpVerifiedPortVariantGroup(group)
            }
        }
        return passthrough + compacted
    }

    private fun compactWarpVerifiedConfigsIfNeeded() {
        synchronized(warpVerifiedConfigsLock) {
            val current = getWarpVerifiedConfigs()
            if (current.isEmpty()) return
            val compacted = sortWarpVerifiedConfigs(compactWarpVerifiedPortVariantsInMemory(current))
            val currentSignature = current.joinToString("\n") {
                "${it.id}|${it.port}|${it.preferredPorts.map(WarpPortStat::port).joinToString(",")}|${it.successCount}|${it.lastVerifiedAt}"
            }
            val compactedSignature = compacted.joinToString("\n") {
                "${it.id}|${it.port}|${it.preferredPorts.map(WarpPortStat::port).joinToString(",")}|${it.successCount}|${it.lastVerifiedAt}"
            }
            if (currentSignature != compactedSignature) {
                saveWarpVerifiedConfigs(compacted)
            }
        }
    }

    private fun mergeWarpVerifiedPortVariantGroup(
        group: List<WarpVerifiedConfig>,
    ): WarpVerifiedConfig {
        val sorted = sortWarpVerifiedConfigs(group)
        val primary = sorted.first()
        val mergedPortStats = sortWarpPortStats(
            sorted.flatMap(::warpPortStatsForConfig),
            primary.port,
        )
        val preferredSni = sorted
            .fold(primary) { best, item ->
                if (chooseWarpPreferredSni(best, item) == item.preferredSni) item else best
            }
            .preferredSni
        return primary.copy(
            id = buildWarpConfigId(primary.mode, primary.host, primary.port, primary.scope),
            endpointSource = primary.endpointSource.ifBlank {
                sorted.firstNotNullOfOrNull { it.endpointSource.takeIf { value -> value.isNotBlank() } }.orEmpty()
            },
            createdAt = sorted
                .map { it.createdAt }
                .filter { it > 0L }
                .minOrNull() ?: primary.createdAt,
            lastVerifiedAt = sorted.maxOf { it.lastVerifiedAt },
            successCount = sorted.sumOf { it.successCount.coerceAtLeast(0) }.coerceAtLeast(primary.successCount),
            qualityFailureCount = sorted.minOf { it.qualityFailureCount.coerceAtLeast(0) },
            preferredSni = preferredSni,
            preferredPorts = mergedPortStats,
        )
    }

    private fun warpVerifiedPortVariantKey(item: WarpVerifiedConfig): String {
        return listOf(
            normalizeToken(item.engine.ifBlank { "wireguard" }),
            normalizeToken(item.mode),
            normalizeHost(item.host),
            normalizeStrategyScope(item.scope),
            item.userImported.toString(),
            rawWarpObfuscationSignature(item.rawConfig).takeIf { item.userImported }.orEmpty(),
        ).joinToString("|")
    }

    private fun rawWarpObfuscationSignature(rawConfig: String): String {
        if (rawConfig.isBlank()) return ""
        val excludedKeys = setOf(
            "address",
            "dns",
            "endpoint",
            "host",
            "id",
            "listenport",
            "manual",
            "mtu",
            "port",
            "preferred_ports",
            "preferred_sni",
            "privatekey",
            "protocol",
            "scope",
            "source",
            "strategy",
        )
        return rawConfig
            .lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotBlank() && !it.startsWith("[") }
            .mapNotNull { line ->
                val key = line.substringBefore('=', "").trim().lowercase(Locale.US)
                if (key.isBlank() || key in excludedKeys) {
                    null
                } else {
                    "$key=${line.substringAfter('=', "").trim()}"
                }
            }
            .sorted()
            .joinToString(";")
    }

    private fun saveWarpVerifiedConfigs(items: List<WarpVerifiedConfig>) {
        val array = org.json.JSONArray()
        val normalizedItems = items
            .filter { isPersistableWarpVerifiedConfig(it.engine, it.host, it.manual, it.userImported, it.endpointSource) }
            .let(::sortWarpVerifiedConfigs)
        normalizedItems
            .forEach { item ->
                array.put(
                    JSONObject().apply {
                        put("id", item.id)
                        put("engine", item.engine)
                        put("mode", item.mode)
                        put("host", item.host)
                        put("port", item.port)
                        put("endpoint_source", item.endpointSource)
                        put("raw_config", item.rawConfig)
                        put("created_at", item.createdAt)
                        put("last_verified_at", item.lastVerifiedAt)
                        put("promoted_at", item.promotedAt)
                        put("seed_order", item.seedOrder)
                        put("success_count", item.successCount)
                        put("scope", item.scope)
                        put("manual", item.manual)
                        put("user_imported", item.userImported)
                        put("quality_probe_count", item.qualityProbeCount)
                        put("quality_ping_successes", item.qualityPingSuccesses)
                        put("quality_avg_ping_ms", item.qualityAvgPingMs)
                        put("quality_last_checked_at", item.qualityLastCheckedAt)
                        put("quality_failure_count", item.qualityFailureCount)
                        put("churn_windows", item.churnWindows)
                        put("churn_rekeys", item.churnRekeys)
                        put("hold_windows", item.holdWindows)
                        put("hold_stall_ms", item.holdStallMs)
                        put("hold_span_ms", item.holdSpanMs)
                        put("hold_checked_at", item.holdCheckedAt)
                        put("preferred_sni", item.preferredSni)
                        putWarpPortStats(this, item.preferredPorts, item.port)
                    }
                )
            }
        prefs.edit().putString("warp_verified_configs", array.toString()).commit()
        syncWarpVerifiedExport(normalizedItems)
    }

    private fun syncWarpVerifiedExport(items: List<WarpVerifiedConfig>? = null) {
        val normalizedItems = sortWarpVerifiedConfigs(
            (items ?: getWarpVerifiedConfigs())
                .filter { isPersistableWarpVerifiedConfig(it.engine, it.host, it.manual, it.userImported, it.endpointSource) }
        )
        val payload = JSONObject().apply {
            put("generated_at", System.currentTimeMillis())
            put("stats_profile", statsProfileKey())
            put("count", normalizedItems.size)
            put("items", org.json.JSONArray().apply {
                normalizedItems.forEachIndexed { index, item ->
                    val priorityScore = getWarpVerifiedPriorityScore(item).takeIf { it.isFinite() } ?: 0.0
                    val effectiveProbeCount = effectiveWarpQualityProbeCount(item)
                    val effectivePingSuccesses = effectiveWarpQualityPingSuccesses(item)
                    val effectiveAvgPingMs = effectiveWarpQualityAvgPingMs(item)
                    val effectiveLastCheckedAt = effectiveWarpQualityLastCheckedAt(item)
                    val effectiveFailureCount = effectiveWarpQualityFailureCount(item)
                    put(
                        JSONObject().apply {
                            put("rank", index + 1)
                            put("id", item.id)
                            put("engine", item.engine)
                            put("mode", item.mode)
                            put("host", item.host)
                            put("port", item.port)
                            put("endpoint_source", item.endpointSource)
                            put("raw_config", item.rawConfig)
                            put("created_at", item.createdAt)
                            put("last_verified_at", item.lastVerifiedAt)
                            put("promoted_at", item.promotedAt)
                            put("seed_order", item.seedOrder)
                            put("success_count", item.successCount)
                            put("scope", item.scope)
                            put("manual", item.manual)
                            put("user_imported", item.userImported)
                            put("priority_score", priorityScore)
                            put("quality_probe_count", effectiveProbeCount)
                            put("quality_ping_successes", effectivePingSuccesses)
                            put("quality_avg_ping_ms", effectiveAvgPingMs)
                            put("quality_last_checked_at", effectiveLastCheckedAt)
                            put("quality_failure_count", effectiveFailureCount)
                            put("churn_windows", item.churnWindows)
                            put("churn_rekeys", item.churnRekeys)
                            put("hold_windows", item.holdWindows)
                            put("hold_stall_ms", item.holdStallMs)
                            put("hold_span_ms", item.holdSpanMs)
                            put("hold_checked_at", item.holdCheckedAt)
                            put("hold_stall_normalized_ms", warpConfigHoldStallMs(item))
                            put("hold_grade", warpConfigHoldGrade(item))
                            put("preferred_sni", item.preferredSni)
                            putWarpPortStats(this, item.preferredPorts, item.port)
                        }
                    )
                }
            })
            put("release_seed_items", org.json.JSONArray().apply {
                normalizedItems
                    .filter { item ->
                        !item.manual &&
                            !item.userImported &&
                            item.endpointSource.equals("bundled-seed", ignoreCase = true)
                    }
                    .forEachIndexed { index, item ->
                        val effectiveProbeCount = effectiveWarpQualityProbeCount(item)
                        val effectivePingSuccesses = effectiveWarpQualityPingSuccesses(item)
                        val effectiveAvgPingMs = effectiveWarpQualityAvgPingMs(item)
                        val effectiveLastCheckedAt = effectiveWarpQualityLastCheckedAt(item)
                        val effectiveFailureCount = effectiveWarpQualityFailureCount(item)
                        put(
                            JSONObject().apply {
                                put("rank", index + 1)
                                put("engine", item.engine)
                                put("mode", item.mode)
                                put("host", item.host)
                                put("port", item.port)
                                put("scope", item.scope)
                                put("endpoint_source", "bundled-seed")
                                put("success_count", item.successCount)
                                put("last_verified_at", item.lastVerifiedAt)
                                put("raw_config", item.rawConfig)
                                put("quality_probe_count", effectiveProbeCount)
                                put("quality_ping_successes", effectivePingSuccesses)
                                put("quality_avg_ping_ms", effectiveAvgPingMs)
                                put("quality_last_checked_at", effectiveLastCheckedAt)
                                put("quality_failure_count", effectiveFailureCount)
                                put("churn_windows", item.churnWindows)
                                put("churn_rekeys", item.churnRekeys)
                                put("hold_windows", item.holdWindows)
                                put("hold_stall_ms", item.holdStallMs)
                                put("hold_span_ms", item.holdSpanMs)
                                put("hold_checked_at", item.holdCheckedAt)
                                put("preferred_sni", item.preferredSni)
                                putWarpPortStats(this, item.preferredPorts, item.port)
                            }
                        )
                    }
            })
        }.toString(2)
        runCatching {
            warpVerifiedExportFile.parentFile?.mkdirs()
            val current = warpVerifiedExportFile.takeIf { it.exists() }?.readText().orEmpty()
            if (current != payload) {
                warpVerifiedExportFile.writeText(payload)
            }
            synchronized(warpVerifiedExportCacheLock) {
                cachedWarpVerifiedExportPath = warpVerifiedExportFile.absolutePath
                cachedWarpVerifiedExportModifiedAt = warpVerifiedExportFile.lastModified()
                cachedWarpVerifiedExportLength = warpVerifiedExportFile.length()
                cachedWarpVerifiedExportItems = normalizedItems
            }
        }
    }

    private fun normalizeRegionPreference(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            "eu" -> "eu"
            "us" -> "us"
            "ru" -> "ru"
            // MASQUE стоит в общей цепочке между WARP и Opera, но его можно выбрать
            // и отдельно — тогда перебор начинается сразу с него.
            "masque" -> "masque"
            // VLESS в общую цепочку не входит: узел задаёт пользователь, и подбирать
            // его перебором не из чего. Выбирается только явно.
            "vless" -> "vless"
            else -> "auto"
        }
    }

    private fun operaPinnedEndpointsKey(country: String): String {
        val normalizedCountry = normalizeOperaRegionCode(country).ifBlank { "EU" }
        return "opera_pinned_endpoints_$normalizedCountry"
    }

    private fun operaApiProfileKey(country: String): String {
        val normalizedCountry = normalizeOperaRegionCode(country).ifBlank { "EU" }
        return "opera_api_profile_$normalizedCountry"
    }

    private fun operaApiProfileFailureKey(country: String, profileId: String): String {
        val normalizedCountry = normalizeOperaRegionCode(country).ifBlank { "EU" }
        val normalizedProfile = normalizeOperaApiProfileId(profileId).ifBlank { "unknown" }
        return "opera_api_profile_failure_${normalizedCountry}_$normalizedProfile"
    }

    private fun operaPinnedEndpointFailureKey(country: String, endpoint: String): String {
        val normalizedCountry = normalizeOperaRegionCode(country).ifBlank { "EU" }
        val normalizedEndpoint = normalizeOperaEndpoint(endpoint)
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "unknown" }
        return "opera_pinned_endpoint_failure_${normalizedCountry}_$normalizedEndpoint"
    }

    private fun operaLaunchPlanKey(
        country: String,
        fakeSni: String?,
        endpoint: String?,
        apiProfileId: String?,
    ): String {
        val normalizedCountry = normalizeOperaRegionCode(country).ifBlank { "EU" }
        val normalizedProfile = normalizeOperaApiProfileId(apiProfileId).ifBlank { "unknown" }
        val normalizedEndpoint = normalizeOperaEndpoint(endpoint)
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "direct" }
        val normalizedHost = normalizeOperaLaunchPlanHost(fakeSni)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "hostless" }
        return "opera_launch_plan_${normalizedCountry}_${normalizedProfile}_${normalizedEndpoint}_${normalizedHost}"
    }

    private fun operaRegistrationPlanKey(
        country: String,
        fakeSni: String?,
        endpoint: String?,
        apiProfileId: String?,
    ): String {
        val normalizedCountry = normalizeOperaRegionCode(country).ifBlank { "EU" }
        val normalizedProfile = normalizeOperaApiProfileId(apiProfileId).ifBlank { "unknown" }
        val normalizedEndpoint = normalizeOperaEndpoint(endpoint)
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "direct" }
        val normalizedHost = normalizeOperaLaunchPlanHost(fakeSni)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "hostless" }
        return "opera_registration_plan_${normalizedCountry}_${normalizedProfile}_${normalizedEndpoint}_${normalizedHost}"
    }

    private fun normalizeOperaRegionCode(value: String?): String {
        return when (value?.trim()?.uppercase(Locale.US)) {
            "EU" -> "EU"
            "AM", "US" -> "AM"
            "AS" -> "AS"
            else -> ""
        }
    }

    private fun normalizeOperaApiProfileId(value: String?): String {
        val clean = value?.trim()?.lowercase(Locale.US).orEmpty()
        return if (clean.matches(Regex("^[a-z0-9_-]{1,32}$"))) clean else ""
    }

    private fun normalizeOperaEndpoint(value: String?): String {
        val clean = value?.trim().orEmpty()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
        if (clean.isBlank()) return ""
        val host = clean.substringBeforeLast(':').trim().trim('[', ']')
        val port = clean.substringAfterLast(':', "").trim().toIntOrNull() ?: return ""
        if (host.isBlank() || port !in 1..65535) return ""
        val validHost = host.matches(Regex("^[a-zA-Z0-9.:-]+$")) && !host.contains("..")
        return if (validHost) "$host:$port" else ""
    }

    private fun normalizeOperaLaunchPlanHost(value: String?): String {
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

    private fun normalizeOptionalTrafficMaskHost(value: String?): String {
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

    private fun normalizeTrafficMaskHost(value: String?): String {
        var host = value?.trim().orEmpty()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .lowercase()

        if (host.isBlank()) return DEFAULT_TRAFFIC_MASK_HOST
        host = host.trim('.')
        if (host.isBlank()) return DEFAULT_TRAFFIC_MASK_HOST
        val isValid = host.matches(Regex("^[a-z0-9.-]+$")) &&
            host.contains('.') &&
            !host.contains("..") &&
            !host.startsWith("-") &&
            !host.endsWith("-")
        return if (isValid) host else DEFAULT_TRAFFIC_MASK_HOST
    }

    private fun normalizeTrafficMaskMode(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            "custom" -> "custom"
            else -> "auto"
        }
    }

    private fun normalizeTrafficMaskPool(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            TRAFFIC_MASK_POOL_GLOBAL -> TRAFFIC_MASK_POOL_GLOBAL
            TRAFFIC_MASK_POOL_RUSSIA -> TRAFFIC_MASK_POOL_RUSSIA
            TRAFFIC_MASK_POOL_CUSTOM -> TRAFFIC_MASK_POOL_CUSTOM
            else -> ""
        }
    }

    private fun detectTrafficMaskPool(hosts: List<String>): String {
        val normalizedHosts = hosts.map(::normalizeTrafficMaskHost).filter { it.isNotBlank() }
        if (normalizedHosts.isEmpty()) return ""
        val globalHosts = TrafficMaskCatalog.getGlobalHosts(appContext).toHashSet()
        val russiaHosts = TrafficMaskCatalog.getRussiaHosts(appContext).toHashSet()
        val globalMatches = normalizedHosts.count { it in globalHosts }
        val russiaMatches = normalizedHosts.count { it in russiaHosts }
        return when {
            globalMatches > russiaMatches -> TRAFFIC_MASK_POOL_GLOBAL
            russiaMatches > globalMatches -> TRAFFIC_MASK_POOL_RUSSIA
            else -> ""
        }
    }

    private fun resolveTrafficMaskPool(host: String?, poolHint: String? = null): String {
        val hinted = normalizeTrafficMaskPool(poolHint)
        if (hinted.isNotBlank()) return hinted
        if (host.isNullOrBlank()) return ""
        val normalizedHost = normalizeTrafficMaskHost(host)
        if (normalizedHost.isBlank()) return ""
        if (getTrafficMaskMode() == "custom" && normalizedHost == getTrafficMaskHost()) {
            return TRAFFIC_MASK_POOL_CUSTOM
        }
        val globalHosts = TrafficMaskCatalog.getGlobalHosts(appContext)
        if (normalizedHost in globalHosts) return TRAFFIC_MASK_POOL_GLOBAL
        val russiaHosts = TrafficMaskCatalog.getRussiaHosts(appContext)
        if (normalizedHost in russiaHosts) return TRAFFIC_MASK_POOL_RUSSIA
        return ""
    }

    private fun readTrafficMaskStats(host: String): TrafficMaskStats {
        val raw = prefs.getString(trafficMaskStatsKey(host), null).orEmpty()
        if (raw.isBlank()) return TrafficMaskStats()
        val parts = raw.split('|')
        return TrafficMaskStats(
            successes = parts.getOrNull(0)?.toIntOrNull() ?: 0,
            failures = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            lastSuccessAt = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
            lastFailureAt = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
        )
    }

    private fun encodeTrafficMaskStats(stats: TrafficMaskStats): String {
        return listOf(
            stats.successes,
            stats.failures,
            stats.lastSuccessAt,
            stats.lastFailureAt,
        ).joinToString("|")
    }

    private fun trafficMaskStatsKey(host: String): String {
        return TRAFFIC_MASK_STATS_PREFIX + host
    }

    private fun shouldPreferXboxDnsForWarp(countryHint: String? = null): Boolean {
        val preference = normalizeRegionPreference(getExitRegionPreference())
        val resolvedCountry = countryHint?.trim()?.uppercase().orEmpty()
            .ifBlank { getLastExitCountry().trim().uppercase() }
        if (preference == "ru") return true
        // Страна, определённая по внешнему IP — та же проверка, что показывает регион
        // на главном экране, — решает окончательно. Раньше здесь стояло только
        // `== "RU" -> true`, и известный зарубежный выход проваливался дальше, к
        // догадкам по локали и часовому поясу: у российского пользователя они всегда
        // дают «Россия», хотя выход в этот момент, скажем, в Германии.
        if (resolvedCountry.isNotEmpty()) return resolvedCountry == "RU"
        if (preference != "auto") return false

        val localeCountry = Locale.getDefault().country?.trim()?.uppercase().orEmpty()
        if (localeCountry == "RU") return true

        val timezoneId = TimeZone.getDefault().id?.trim().orEmpty()
        if (timezoneId in RUSSIAN_TIME_ZONES) return true

        val lastMode = normalizeToken(getLastSuccessMode())
        val lastProtocol = normalizeToken(getLastSuccessProtocol())
        val lastBackendLooksWarp = !lastMode.startsWith("opera") && !lastProtocol.startsWith("opera")
        if (hasFreshLastSuccess() && lastBackendLooksWarp) {
            return true
        }

        return false
    }

    private fun isOperaBackendLabel(label: String?): Boolean {
        return label?.trim()?.uppercase()?.startsWith(NovaVpnService.BACKEND_OPERA) == true
    }

    private fun isVlessBackendLabel(label: String?): Boolean {
        return label?.trim()?.uppercase()?.startsWith(NovaVpnService.BACKEND_VLESS) == true
    }

    private fun regionMatches(preference: String, country: String): Boolean {
        val normalizedCountry = country.trim().uppercase()
        return when (preference) {
            "ru" -> normalizedCountry == "RU"
            "us" -> normalizedCountry == "US"
            "eu" -> normalizedCountry in EUROPEAN_COUNTRIES
            else -> true
        }
    }

    companion object {
        private val runtimeSeedInitDone = AtomicBoolean(false)
        private val warpVerifiedExportCacheLock = Any()

        /**
         * Читаем-меняем-пишем `opera_state.json` из нескольких потоков `:vpn` — только
         * под этим замком. Замок именно в companion, а не в экземпляре: `ClientData`
         * создаётся заново на каждом вызове, и замок экземпляра ничего не сериализовал бы.
         */
        private val operaStateLock = Any()

        /**
         * Список проверенных WARP-конфигураций читается-меняется-пишется целиком,
         * и делают это три потока сразу: поток перебора пишет исход попытки,
         * демон-сэмплер — итог окна качества и удержания, главный поток — замер
         * перерукопожатий с живой сессии. Без замка проигравший гонку писатель
         * откатывал не одно поле, а результаты всех конфигураций, изменённых с
         * момента его чтения, — и выглядело бы это как «замер не записался».
         *
         * Замок в companion, а не в экземпляре: `ClientData` создаётся заново на
         * каждом вызове, и замок экземпляра ничего не сериализовал бы.
         *
         * Под ним не появляется никакой новой работы — только та запись, что и
         * так шла. Расширять его дальше нельзя: общий замок вокруг остановки
         * Opera уже приводил к ANR на главном потоке.
         */
        private val warpVerifiedConfigsLock = Any()

        @Volatile
        private var cachedOperaState: JSONObject? = null

        @Volatile
        private var cachedOperaStateStamp: Pair<Long, Long> = 0L to 0L
        @Volatile private var cachedWarpVerifiedExportPath: String? = null
        @Volatile private var cachedWarpVerifiedExportModifiedAt: Long = -1L
        @Volatile private var cachedWarpVerifiedExportLength: Long = -1L
        @Volatile private var cachedWarpVerifiedExportItems: List<WarpVerifiedConfig>? = null
        private val localProxySecureRandom = SecureRandom()
        private const val BOOTSTRAP_ASSET_NAME = "warp_bootstrap.json"
        private const val WARP_VERIFIED_SEEDS_ASSET_NAME = "warp_verified_seeds.json"
        private const val WARP_VERIFIED_EXPORT_FILE_NAME = "warp_verified_export.json"

        /** Разовая чистка маскировочных имён из профилей, см. purgeStoredWarpPreferredSniOnce. */
        private const val WARP_PREFERRED_SNI_PURGED_KEY = "warp_preferred_sni_purged_v1"

        /** Сколько окон замера нужно, чтобы churn узла считался измеренным. */
        private const val WARP_CHURN_MIN_WINDOWS = 2

        /** Потолок накопления: дальше счётчики делятся пополам, давая старению ход. */
        private const val WARP_CHURN_MAX_WINDOWS = 12

        /** Отладочный ключ «AWG без junk», см. [isAwgJunkDisabled]. */
        private const val AWG_JUNK_DISABLED_KEY = "debug_awg_junk_disabled"
        private const val LAST_SUCCESS_FRESH_MS = 3L * 24 * 60 * 60 * 1000L
        /**
         * Потолок числа импортированных профилей VLESS. Подписки бывают на тысячи
         * записей, а `SharedPreferences` читается на старте целиком.
         */
        const val MAX_VLESS_PROFILES = 400

        private const val MASQUE_TRANSPORT_COOLDOWN_MS = 25L * 1000L

        /**
         * Срок уступки WARP после серии срывов явно выбранного MASQUE.
         *
         * Полный цикл MASQUE занимает около полуминуты, поэтому окно короче минуты
         * означало бы тот самый цикл переподключений, от которого уступка и защищает.
         * Десять минут — компромисс: подряд не крутится, но выбор пользователя
         * восстанавливается сам, без переустановки и сброса настроек.
         */
        private const val MASQUE_EXPLICIT_LOCKOUT_MS = 10L * 60L * 1000L
        private const val LOCAL_PROXY_TOKEN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val DEFAULT_TRAFFIC_MASK_HOST = "ads.max.ru"
        private const val UNSET_SENTINEL = "\u0000"
        private const val TRAFFIC_MASK_STATS_PREFIX = "traffic_mask_stats|"
        private const val STRATEGY_SCOPE_DEFAULT = "default"

        /**
         * Приставка идентификатора карточки профиля VLESS.
         *
         * По ней удаление отличает записи, собранные из `vless_profiles.json`, от
         * настоящих строк хранилища WARP: лежат они в разных местах, и чистить их
         * надо тоже по-разному.
         */
        const val VLESS_CONFIG_ID_PREFIX = "vless|"
        private const val STRATEGY_SCOPE_MESSENGER = "messenger"
        private const val FAILURE_REASON_VALIDATED_NO_TRAFFIC = "validated_no_traffic"
        private const val FAILURE_REASON_CONTROL_PLANE_ONLY = "control_plane_only"
        private const val FAILURE_REASON_NO_INBOUND_AFTER_HANDSHAKE = "no_inbound_after_handshake"
        private const val FAILURE_REASON_NO_TRAFFIC = "no_traffic"
        private const val FAILURE_REASON_ENGINE_CRASH = "engine_crash"
        private const val FAILURE_REASON_UNDERLYING_LOSS = "underlying_loss"
        private const val FAILURE_REASON_HANDSHAKE_TIMEOUT = "handshake_timeout"
        const val TRAFFIC_MASK_POOL_GLOBAL = "global"
        const val TRAFFIC_MASK_POOL_RUSSIA = "russia"
        const val TRAFFIC_MASK_POOL_CUSTOM = "custom"
        private const val OPERA_FEED_SYNCED_AT_KEY = "opera_endpoint_feed_synced_at"
        private const val OPERA_FEED_ATTEMPT_AT_KEY = "opera_endpoint_feed_attempt_at"
        private const val OPERA_CUSTOM_API_PROXY_KEY = "opera_custom_api_proxy"
        var needsRestart = false
        private val POISONED_ENDPOINT_HOSTS = setOf(
            "8.6.112.0",
            "8.47.69.0",
        )
        private val CLOUDFLARE_DNS_SERVERS = listOf(
            "1.1.1.1",
            "1.0.0.1",
            "2606:4700:4700::1111",
            "2606:4700:4700::1001",
        )
        private val GOOGLE_DNS_SERVERS = listOf(
            "8.8.8.8",
            "8.8.4.4",
            "2001:4860:4860::8888",
            "2001:4860:4860::8844",
        )
        private val DEFAULT_ENCRYPTED_DNS_RESOLVER_LIST = listOf(
            "https://1.1.1.1/dns-query",
            "https://dns.google/dns-query",
            "tls://1.1.1.1:853",
            "tls://8.8.8.8:853",
        )
        /**
         * Сколько имён брать из большого российского списка.
         *
         * В `traffic_mask_russia.sni` 59 тысяч строк. Держать их все в памяти ради
         * одного имени на рукопожатие незачем: перебор на узле всё равно не уходит
         * дальше первых десятков, а взятые подряд имена из начала списка одинаковы
         * у всех установок — это не секрет, а маскировка.
         */
        private const val SNI_RUSSIA_POOL_LIMIT = 512

        private val XBOX_DNS_SERVERS = listOf(
            "111.88.96.50",
            "111.88.96.51",
            "2a00:ab00:1233:26::50",
            "2a00:ab00:1233:26::51",
        )

        /**
         * Те же адреса в формате, который понимает opera-proxy.
         *
         * IPv6 в bootstrap не отдаём: у opera-proxy список резолверов разбирается по
         * запятой, и адрес с двоеточиями в нём не отличить от `host:port`.
         */
        private val XBOX_BOOTSTRAP_RESOLVER_LIST = listOf(
            "dns://111.88.96.50",
            "dns://111.88.96.51",
        )
        private val ADGUARD_NO_ADS_DNS_SERVERS = listOf(
            "94.140.14.14",
            "94.140.15.15",
            "2a10:50c0::ad1:ff",
            "2a10:50c0::ad2:ff",
        )
        private val DEFAULT_OPERA_BOOTSTRAP_RESOLVER_LIST = listOf(
            "dns://1.1.1.1",
            "dns://8.8.8.8",
            "https://1.1.1.1/dns-query",
            "https://dns.google/dns-query",
            "tls://1.1.1.1:853",
            "tls://8.8.8.8:853",
        )
        private val DEFAULT_OPERA_BOOTSTRAP_RESOLVERS =
            DEFAULT_OPERA_BOOTSTRAP_RESOLVER_LIST.joinToString(",")
        private const val ADGUARD_NO_ADS_BOOTSTRAP_RESOLVERS =
            "https://dns.adguard-dns.com/dns-query,https://dns.adguard-dns.com/dns-query"
        private val EUROPEAN_COUNTRIES = setOf(
            "AL", "AD", "AM", "AT", "AZ", "BA", "BE", "BG", "BY", "CH", "CY", "CZ",
            "DE", "DK", "EE", "ES", "FI", "FR", "GB", "GE", "GR", "HR", "HU", "IE",
            "IS", "IT", "LI", "LT", "LU", "LV", "MC", "MD", "ME", "MK", "MT", "NL",
            "NO", "PL", "PT", "RO", "RS", "SE", "SI", "SK", "SM", "TR", "UA", "VA"
        )
        private val RUSSIAN_TIME_ZONES = setOf(
            "Europe/Kaliningrad",
            "Europe/Moscow",
            "Europe/Kirov",
            "Europe/Volgograd",
            "Asia/Yekaterinburg",
            "Asia/Omsk",
            "Asia/Novosibirsk",
            "Asia/Barnaul",
            "Asia/Tomsk",
            "Asia/Krasnoyarsk",
            "Asia/Irkutsk",
            "Asia/Yakutsk",
            "Asia/Chita",
            "Asia/Vladivostok",
            "Asia/Ust-Nera",
            "Asia/Magadan",
            "Asia/Sakhalin",
            "Asia/Srednekolymsk",
            "Asia/Kamchatka",
            "Asia/Anadyr",
        )
    }

    private data class TrafficMaskStats(
        val successes: Int = 0,
        val failures: Int = 0,
        val lastSuccessAt: Long = 0L,
        val lastFailureAt: Long = 0L,
    )

    // Public effective quality helpers
    fun getWarpVerifiedEffectiveProbeCount(item: WarpVerifiedConfig): Int = effectiveWarpQualityProbeCount(item)
    fun getWarpVerifiedEffectiveAvgPingMs(item: WarpVerifiedConfig): Double = effectiveWarpQualityAvgPingMs(item)
    fun getWarpVerifiedEffectiveLastCheckedAt(item: WarpVerifiedConfig): Long = effectiveWarpQualityLastCheckedAt(item)
    fun getWarpVerifiedEffectivePingSuccesses(item: WarpVerifiedConfig): Int = effectiveWarpQualityPingSuccesses(item)
    fun getWarpVerifiedEffectiveFailureCount(item: WarpVerifiedConfig): Int = effectiveWarpQualityFailureCount(item)

    fun getWarpVerifiedEffectiveQualityScore(
        item: WarpVerifiedConfig,
        nowMs: Long = System.currentTimeMillis()
    ): Double {
        val probeCount = effectiveWarpQualityProbeCount(item)
        if (probeCount <= 0) return 0.0
        val pingSuccesses = effectiveWarpQualityPingSuccesses(item)
        val avgPingMs = effectiveWarpQualityAvgPingMs(item)
        val successRate = pingSuccesses.toDouble() / probeCount

        val configs = getWarpVerifiedConfigs()
        val bestAvgPing = configs
            .map { effectiveWarpQualityAvgPingMs(it) }
            .filter { it > 0.0 }
            .minOrNull() ?: 100.0

        val pingFactor = if (bestAvgPing > 0.0 && avgPingMs > 0.0) {
            (bestAvgPing / avgPingMs).coerceAtMost(1.0)
        } else {
            0.0
        }
        return successRate * pingFactor * 100.0
    }

    // DNS settings configuration methods
    private fun normalizeDnsHostInput(value: String?): String {
        return value?.trim()?.removePrefix("dns://")?.removePrefix("[")?.removeSuffix("]").orEmpty()
    }

    private fun normalizeDnsPlainCsv(vararg values: String?): String {
        return values.asList()
            .flatMap { value ->
                value.orEmpty()
                    .replace(';', ',')
                    .split(',')
            }
            .map(::normalizeDnsHostInput)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
    }

    private fun mergePlainDnsInputs(primary: String?, secondary: String?): String {
        return normalizeDnsPlainCsv(primary, secondary)
    }

    private fun normalizeDnsResolverList(value: String?): String {
        return value
            .orEmpty()
            .replace(';', ',')
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(",")
    }

    private fun normalizeDnsRouteMode(value: String?): String {
        return when (value?.trim()?.lowercase(Locale.US)) {
            "direct" -> "direct"
            "tunnel" -> "tunnel"
            "fastest", "auto" -> "auto"
            else -> "auto"
        }
    }

    private fun resolveDnsChain(primary: String, secondary: String): List<String> {
        return normalizeDnsPlainCsv(primary, secondary)
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun getDnsSettingsConfig(): DnsSettingsConfig {
        val raw = prefs.getString("dns_settings_json", null).orEmpty().trim()
        if (raw.isBlank()) return DnsSettingsConfig()
        return try {
            val json = JSONObject(raw)
            val appOverrideJson = json.optJSONObject("app_override")
            DnsSettingsConfig(
                globalEnabled = json.optBoolean("global_enabled", false),
                globalPrimaryDns = mergePlainDnsInputs(
                    json.optString("global_primary_dns"),
                    json.optString("global_secondary_dns"),
                ),
                globalSecondaryDns = "",
                globalEncryptedFallback = normalizeDnsResolverList(json.optString("global_encrypted_fallback")),
                allowPlainFallback = json.optBoolean("allow_plain_fallback", true),
                routeMode = normalizeDnsRouteMode(json.optString("route_mode")),
                appOverride = DnsAppOverride(
                    enabled = appOverrideJson?.optBoolean("enabled", false) == true,
                    packageName = appOverrideJson?.optString("package_name").orEmpty().trim(),
                    appLabel = appOverrideJson?.optString("app_label").orEmpty().trim(),
                    primaryDns = mergePlainDnsInputs(
                        appOverrideJson?.optString("primary_dns"),
                        appOverrideJson?.optString("secondary_dns"),
                    ),
                    secondaryDns = "",
                    encryptedFallback = normalizeDnsResolverList(appOverrideJson?.optString("encrypted_fallback")),
                    allowPlainFallback = appOverrideJson?.optBoolean("allow_plain_fallback", true) != false,
                ),
            )
        } catch (_: Exception) {
            DnsSettingsConfig()
        }
    }

    fun saveDnsSettingsConfig(config: DnsSettingsConfig) {
        val normalized = config.copy(
            globalPrimaryDns = normalizeDnsPlainCsv(config.globalPrimaryDns, config.globalSecondaryDns),
            globalSecondaryDns = "",
            globalEncryptedFallback = normalizeDnsResolverList(config.globalEncryptedFallback),
            allowPlainFallback = config.allowPlainFallback,
            routeMode = normalizeDnsRouteMode(config.routeMode),
            appOverride = config.appOverride.copy(
                packageName = config.appOverride.packageName.trim(),
                appLabel = config.appOverride.appLabel.trim(),
                primaryDns = normalizeDnsPlainCsv(config.appOverride.primaryDns, config.appOverride.secondaryDns),
                secondaryDns = "",
                encryptedFallback = normalizeDnsResolverList(config.appOverride.encryptedFallback),
                allowPlainFallback = config.appOverride.allowPlainFallback,
            ),
        )
        val raw = JSONObject().apply {
            put("global_enabled", normalized.globalEnabled)
            put("global_primary_dns", normalized.globalPrimaryDns)
            put("global_secondary_dns", normalized.globalSecondaryDns)
            put("global_encrypted_fallback", normalized.globalEncryptedFallback)
            put("allow_plain_fallback", normalized.allowPlainFallback)
            put("route_mode", normalized.routeMode)
            put(
                "app_override",
                JSONObject().apply {
                    put("enabled", normalized.appOverride.enabled)
                    put("package_name", normalized.appOverride.packageName)
                    put("app_label", normalized.appOverride.appLabel)
                    put("primary_dns", normalized.appOverride.primaryDns)
                    put("secondary_dns", normalized.appOverride.secondaryDns)
                    put("encrypted_fallback", normalized.appOverride.encryptedFallback)
                    put("allow_plain_fallback", normalized.appOverride.allowPlainFallback)
                },
            )
        }.toString()
        prefs.edit().putString("dns_settings_json", raw).apply()
    }

    fun getDnsSettingsSummary(): String {
        val config = getDnsSettingsConfig()
        val globalServers = resolveDnsChain(config.globalPrimaryDns, config.globalSecondaryDns)
        val encryptedResolvers = resolveEncryptedResolverList(config.globalEncryptedFallback)
        val exclusiveAppOverride = getActiveExclusiveAppDnsOverride()
        val mediaAppOverride = getActiveMediaDnsOverride()
        val configuredAppOverride = getConfiguredAppDnsOverride()
        val appOverrideStatus = getDnsAppOverrideStatus()
        val routeLabel = when (config.routeMode) {
            "direct" -> "direct"
            "tunnel" -> "VPN"
            else -> "AUTO"
        }
        val appRuleSummary = config.appOverride.takeIf { it.enabled && it.packageName.isNotBlank() }?.let { override ->
            override.appLabel.ifBlank { override.packageName }
        }.orEmpty()
        return when {
            exclusiveAppOverride != null ->
                "DNS: $routeLabel, app-only ${exclusiveAppOverride.appLabel.ifBlank { exclusiveAppOverride.packageName }}"
            mediaAppOverride != null ->
                "DNS: $routeLabel, app ${mediaAppOverride.appLabel.ifBlank { mediaAppOverride.packageName }}"
            configuredAppOverride != null && config.globalEnabled && encryptedResolvers.isNotEmpty() ->
                "DNS: $routeLabel, шифрованный + fallback • app ${configuredAppOverride.appLabel.ifBlank { configuredAppOverride.packageName }}"
            config.globalEnabled && encryptedResolvers.isNotEmpty() && appRuleSummary.isNotBlank() ->
                "DNS: $routeLabel, шифрованный + fallback • app: $appRuleSummary"
            config.globalEnabled && encryptedResolvers.isNotEmpty() ->
                "DNS: $routeLabel, шифрованный + fallback"
            config.globalEnabled && globalServers.isNotEmpty() && appRuleSummary.isNotBlank() ->
                "DNS: $routeLabel, ${globalServers.joinToString(", ")} • app: $appRuleSummary"
            config.globalEnabled && globalServers.isNotEmpty() ->
                "DNS: $routeLabel, ${globalServers.joinToString(", ")}"
            appOverrideStatus.waitingForExclusiveMode ->
                "DNS app ждёт: Только выбранные -> ${appOverrideStatus.appLabel.ifBlank { appOverrideStatus.packageName }}"
            appRuleSummary.isNotBlank() ->
                "DNS для app: $appRuleSummary (ограниченно)"
            else ->
                "Автовыбор DNS для VPN и приложений"
        }
    }

    private fun resolveEncryptedResolverList(value: String?): List<String> {
        val configured = normalizeDnsResolverList(value)
            .split(',')
            .map { it.trim() }
            .filter { it.startsWith("https://", ignoreCase = true) || it.startsWith("tls://", ignoreCase = true) }
            .distinct()
        return if (configured.isNotEmpty()) configured else DEFAULT_ENCRYPTED_DNS_RESOLVER_LIST
    }

    fun getConfiguredAppDnsOverride(): DnsAppOverride? {
        val override = getDnsSettingsConfig().appOverride
        if (!override.enabled || override.packageName.isBlank()) return null
        if (resolveDnsChain(override.primaryDns, override.secondaryDns).isEmpty()) return null
        return override
    }

    fun getDnsAppOverrideStatus(): DnsAppOverrideStatus {
        val configured = getConfiguredAppDnsOverride()
        if (configured == null) return DnsAppOverrideStatus(configured = false, active = false, waitingForExclusiveMode = false)
        val exclusive = getActiveExclusiveAppDnsOverride()
        return DnsAppOverrideStatus(
            configured = true,
            active = exclusive != null,
            waitingForExclusiveMode = exclusive == null,
            packageName = configured.packageName,
            appLabel = configured.appLabel,
        )
    }

    fun getActiveExclusiveAppDnsOverride(): DnsAppOverride? {
        val override = getActiveVpnRoutedDnsOverride() ?: return null
        if (resolveDnsChain(override.primaryDns, override.secondaryDns).isEmpty()) return null
        val selectedApps = getSplitApps()
        if (getSplitMode() != 1) return null
        if (selectedApps.size != 1) return null
        if (override.packageName !in selectedApps) return null
        return override
    }

    fun getActiveVpnRoutedDnsOverride(): DnsAppOverride? {
        val config = getDnsSettingsConfig()
        val override = config.appOverride
        if (!override.enabled || override.packageName.isBlank()) return null
        val selectedApps = getSplitApps()
        val routedViaVpn = when (getSplitMode()) {
            1 -> override.packageName in selectedApps
            2 -> override.packageName !in selectedApps
            else -> true
        }
        if (!routedViaVpn) return null
        if (resolveDnsChain(override.primaryDns, override.secondaryDns).isEmpty()) return null
        return override
    }

    fun getActiveMediaDnsOverride(): DnsAppOverride? {
        val override = getActiveVpnRoutedDnsOverride() ?: return null
        if (!LocalDnsPolicy.isMediaAdBlockPackage(override.packageName)) return null
        return override
    }

    fun saveDnsExclusiveRestoreSnapshot(mode: Int, apps: Set<String>, targetPackage: String) {
        prefs.edit()
            .putInt("dns_exclusive_restore_mode", mode)
            .putStringSet("dns_exclusive_restore_apps", apps)
            .putString("dns_exclusive_restore_target", targetPackage)
            .commit()
    }

    fun getDnsExclusiveRestoreSnapshot(): SplitTunnelSnapshot? {
        val hasMode = prefs.contains("dns_exclusive_restore_mode")
        val targetPackage = prefs.getString("dns_exclusive_restore_target", "").orEmpty()
        if (!hasMode || targetPackage.isBlank()) return null
        return SplitTunnelSnapshot(
            mode = prefs.getInt("dns_exclusive_restore_mode", 0),
            apps = prefs.getStringSet("dns_exclusive_restore_apps", emptySet()) ?: emptySet(),
            targetPackage = targetPackage,
        )
    }

    fun clearDnsExclusiveRestoreSnapshot() {
        prefs.edit()
            .remove("dns_exclusive_restore_mode")
            .remove("dns_exclusive_restore_apps")
            .remove("dns_exclusive_restore_target")
            .commit()
    }

    // Diagnostic logging settings methods
    private fun normalizeDiagnosticLogLevel(value: String?): String {
        return when (value?.trim()?.lowercase(Locale.US)) {
            "debug" -> "debug"
            "info" -> "info"
            "warn", "warning" -> "warn"
            else -> "error"
        }
    }

    fun getDiagnosticLogSettingsConfig(): DiagnosticLogSettingsConfig {
        val raw = prefs.getString("diagnostic_log_settings_json", null).orEmpty()
        if (raw.isBlank()) return DiagnosticLogSettingsConfig()
        return try {
            val json = JSONObject(raw)
            DiagnosticLogSettingsConfig(
                enabled = json.optBoolean("enabled", false),
                level = normalizeDiagnosticLogLevel(json.optString("level")),
            )
        } catch (_: Exception) {
            DiagnosticLogSettingsConfig()
        }
    }

    fun saveDiagnosticLogSettingsConfig(config: DiagnosticLogSettingsConfig) {
        val normalized = DiagnosticLogSettingsConfig(
            enabled = config.enabled,
            level = normalizeDiagnosticLogLevel(config.level),
        )
        val raw = JSONObject().apply {
            put("enabled", normalized.enabled)
            put("level", normalized.level)
        }.toString()
        prefs.edit().putString("diagnostic_log_settings_json", raw).commit()
    }

    fun getDiagnosticLogSettingsSummary(): String {
        val config = getDiagnosticLogSettingsConfig()
        return if (!config.enabled) {
            "Выключено"
        } else {
            "Включено • ${config.level.uppercase(Locale.US)}"
        }
    }
}

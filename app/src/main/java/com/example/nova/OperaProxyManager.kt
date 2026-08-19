package com.example.nova

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import nova.Nova
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.Locale
import kotlin.concurrent.thread

object OperaProxyManager {

    private val lock = Any()
    private val startLock = Any()
    private val sessionFailedLock = Any()

    /**
     * Способ запуска бинаря. Остался один — прямой.
     *
     * Ветка через `/system/bin/sh -c` убрана: она ничего не давала сверх прямого
     * запуска, а выглядела как выполнение команд оболочки.
     */
    private enum class LaunchMode {
        DIRECT,
    }

    enum class ReadyState {
        ALREADY_RUNNING,
        STARTED_INTERNAL,
        FAILED,
    }

    enum class MaskHostPolicy {
        WHITE_FIRST,
        GLOBAL_FIRST,
        WHITE_ONLY,
        GLOBAL_ONLY,
    }

    data class DiscoveryResult(
        val endpoints: List<String> = emptyList(),
        val apiCode: Int? = null,
    ) {
        val isApiBlocked: Boolean get() = apiCode == 801 || apiCode == 500 || apiCode == 502
    }

    private data class OperaApiProfile(
        val id: String,
        val label: String,
        val apiAddress: String? = null,
        val clientType: String = DEFAULT_API_CLIENT_TYPE,
        val clientVersion: String = DEFAULT_API_CLIENT_VERSION,
        val userAgent: String = DEFAULT_API_USER_AGENT,
    )

    private data class OperaLaunchPlan(
        val fakeSni: String,
        val endpointOverride: String?,
        val apiProfile: OperaApiProfile,
        /**
         * Прокси только для вызовов API SurfEasy (`-api-proxy`). Туннель при этом
         * по-прежнему набирается напрямую с адреса пользователя.
         */
        val apiRelay: String = "",
    )

    private const val BIND_HOST = "127.0.0.1"
    private const val LEGACY_BIND_PORT = 1085
    private const val INTERNAL_PORT_RANGE_START = 20080
    private const val INTERNAL_PORT_RANGE_END = 40999
    private const val DEFAULT_COUNTRY = "EU"

    /**
     * Бюджет запуска на готовом адресе из кэша.
     *
     * Там нет ни discover, ни выбора сервера — только регистрация и открытие порта,
     * а это доли секунды. Версия для ПК отводит такой попытке 2.5 секунды; здесь
     * чуть больше из-за запуска отдельного процесса.
     */
    private const val CACHED_ENDPOINT_READY_TIMEOUT_MS = 4_000L

    /**
     * Потолок продления ожидания для попытки по готовому адресу из кэша.
     *
     * Discover здесь пропущен, а регистрация устройства в SurfEasy — нет: её
     * `-override-proxy-address` не отменяет, и на медленной сети она занимает около
     * семи секунд (см. [isOperaStartupProgressLine]). Поэтому потолок урезан не до
     * символического значения: 4 + 6 = 10 с всё ещё покрывают живую регистрацию, но
     * зависший запуск больше не стоит двенадцати. Оборвать регистрацию на середине
     * дороже, чем подождать: следом идёт cooldown на 90 секунд, и рабочий адрес
     * вылетел бы из кэша из-за одной медленной сети.
     */
    private const val CACHED_ENDPOINT_PROGRESS_MAX_EXTENSION_MS = 6_000L

    /**
     * Таймаут одной HTTP-пробы и общий бюджет пробы для попытки по кэшу.
     *
     * Живой узел отвечает с первого адреса; мёртвый одинаково мёртв и после шести.
     */
    private const val CACHED_ENDPOINT_PROBE_ATTEMPT_TIMEOUT_MS = 2_000
    private const val CACHED_ENDPOINT_PROBE_BUDGET_MS = 4_000L

    /** Насколько свежей должна быть отметка о продвижении, чтобы продлевать ожидание. */
    private const val PROGRESS_GRACE_MS = 4_000L

    /** Потолок продления ожидания запуска: дальше запуск считается зависшим. */
    private const val PROGRESS_MAX_EXTENSION_MS = 8_000L

    private const val DEFAULT_VERBOSITY = "20"
    private const val DEFAULT_SERVER_SELECTION = "fastest"
    private const val AM_SERVER_SELECTION = "first"
    private const val DEFAULT_SERVER_SELECTION_TIMEOUT = "10s"
    private const val DEFAULT_TEST_URL =
        "https://ajax.googleapis.com/ajax/libs/indefinite-observable/2.0.1/indefinite-observable.bundle.js"
    private const val DEFAULT_API_CLIENT_TYPE = "se0316"
    private const val DEFAULT_API_CLIENT_VERSION = "Stable 114.0.5282.21"
    private const val DEFAULT_API_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36 OPR/114.0.0.0"

    /** Адреса собственных релеев. Не секрет — секрет только пароль к ним. */
    private val API_RELAY_ENDPOINTS = listOf(
        "relay.nova-app.eu" to 8443,
        "relay.nova-app.eu" to 2053,
    )

    private const val API_RELAY_USER = "nova"

    /**
     * Релеи для вызовов API SurfEasy, в порядке предпочтения.
     *
     * SurfEasy отдаёт разный набор endpoint'ов в зависимости от того, откуда пришёл
     * discover, и набор для российских клиентов из России недостижим. Поэтому
     * прямой discover — это не «обычный путь, который иногда режут», а путь,
     * который здесь не приводит к рабочему туннелю в принципе: он честно отдаёт
     * адреса, до которых потом не дозвониться. Релей переносит в Швецию только
     * вызовы API — сам туннель набирается напрямую, страна выхода не меняется.
     *
     * Пароль приходит из сборки и в репозиторий не попадает. Без него список пуст:
     * подставлять заглушку значило бы потратить попытку и получить 407, чтобы
     * узнать то же самое.
     */
    private fun apiRelays(): List<String> {
        val password = BuildConfig.OPERA_RELAY_PASSWORD.trim()
        if (password.isEmpty()) return emptyList()
        val user = encodeUserInfo(API_RELAY_USER)
        val secret = encodeUserInfo(password)
        return API_RELAY_ENDPOINTS.map { (host, port) -> "https://$user:$secret@$host:$port" }
    }

    /**
     * Процент-кодирование для логина и пароля в ссылке.
     *
     * `URLEncoder` здесь не годится: он кодирует пробел как «+», а в userinfo это
     * означает именно плюс, и пароль с пробелом молча превратился бы в другой.
     */
    private fun encodeUserInfo(value: String): String {
        val allowed = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
        return buildString {
            for (byte in value.toByteArray(Charsets.UTF_8)) {
                val char = byte.toInt().toChar()
                if (char in allowed) append(char) else append("%%%02X".format(byte.toInt() and 0xFF))
            }
        }
    }

    /**
     * `opera-proxy` понимает голый `host:port` как HTTP-прокси. Мы задаём релеи
     * ссылкой со схемой, а голое значение трактуем как SOCKS5 — так же, как это
     * делает Nova PC, чтобы одна и та же строка настройки работала в обоих
     * клиентах одинаково.
     */
    private fun normalizeApiRelay(raw: String): String? {
        val value = raw.trim().trim('"', '\'')
        if (value.isEmpty() || value.startsWith("#")) return null
        return if (value.contains("://")) value else "socks5://$value"
    }

    /**
     * Журнал показывается пользователю и уходит в отчёты, а в ссылке релея лежит
     * логин с паролем. Оставляем только схему и адрес — по ним отличима попытка
     * через релей от прямой, а больше от строки в журнале ничего и не нужно.
     */
    private fun describeApiRelay(relay: String): String {
        val scheme = relay.substringBefore("://", missingDelimiterValue = "")
        val rest = relay.substringAfter("://", missingDelimiterValue = relay)
        val hostPort = rest.substringAfterLast('@')
        return if (scheme.isEmpty()) hostPort else "$scheme://$hostPort"
    }

    private val operaApiProfiles = listOf(
        OperaApiProfile(
            id = "api2-default",
            label = "api2.sec-tunnel.com",
        ),
        OperaApiProfile(
            id = "api-legacy",
            label = "api.sec-tunnel.com",
        ),
    )

    @Volatile
    private var managedProcess: Process? = null

    @Volatile
    private var logThread: Thread? = null

    @Volatile
    private var managedCountry: String? = null

    @Volatile
    private var managedPort: Int? = null

    @Volatile
    private var lastAppContext: Context? = null

    @Volatile
    private var managedEndpoint: String? = null

    @Volatile
    private var managedFakeSni: String? = null

    @Volatile
    private var managedApiProfileId: String? = null

    @Volatile
    private var lastFailureApiCode = 0

    private val portRandom = SecureRandom()

    private val sessionFailedMaskHosts = linkedMapOf<String, Long>()

    private val apiCodePattern = Regex("""code=(\d+)""")
    private val selectedEndpointPattern = Regex("""Selected endpoint address:\s*([^\s]+)""", RegexOption.IGNORE_CASE)
    private val failFastBootstrapApiCodes = setOf(801, 500, 502)

    @Volatile
    private var localProxyAddressSetterAvailable = true

    /**
     * Сообщает Go-ядру, где слушает локальный Opera-прокси.
     *
     * Ядро ходит в API Cloudflare через этот прокси и для регистрации MASQUE
     * предпочитает именно его. Порт приложение выбирает случайно из диапазона
     * 20080–40999, а в ядре раньше стоял жёсткий `127.0.0.1:1085` — проверка
     * доступности прокси не срабатывала никогда, и регистрация уходила напрямую, в ту
     * самую фильтрацию по имени узла, из-за которой она и не проходит. Раньше расхождение
     * закрывалось переадресацией с 1085 на живой порт.
     */
    private fun publishLocalProxyAddressToCore(port: Int?, logger: (String) -> Unit) {
        if (!localProxyAddressSetterAvailable) return
        val address = port?.takeIf { it in 1..65535 }?.let { "$BIND_HOST:$it" }.orEmpty()
        try {
            Nova.setLocalCloudflareProxyAddress(address)
            if (address.isNotEmpty()) {
                logger("Ядру задан адрес локального прокси для API Cloudflare: $address.")
            }
        } catch (t: Throwable) {
            localProxyAddressSetterAvailable = false
            logger(
                "Optional JNI setLocalCloudflareProxyAddress недоступен для текущей native-библиотеки: " +
                    "${t.message}"
            )
        }
    }




    fun isRegistrationSupportedOnDevice(context: Context): Boolean {
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir.orEmpty())
        if (!nativeLibDir.exists()) return false
        return File(nativeLibDir, "liboperaproxy.so").exists()
    }

    fun isTransportSupportedOnDevice(context: Context): Boolean {
        if (!isRegistrationSupportedOnDevice(context)) return false
        return com.example.operaproxy.ProxyVpnService.isNativeRuntimeAvailable(context)
    }

    fun isSupportedOnDevice(context: Context): Boolean {
        return isTransportSupportedOnDevice(context)
    }

    fun getLastFailureApiCode(): Int? = lastFailureApiCode.takeIf { it > 0 }
    fun getCurrentEndpoint(): String? = managedEndpoint?.takeIf { it.isNotBlank() }
    fun getCurrentFakeSni(): String? = managedFakeSni?.takeIf { it.isNotBlank() }
    fun getCurrentApiProfileId(): String? = managedApiProfileId?.takeIf { it.isNotBlank() }

    fun noteRuntimeFailureCode(apiCode: Int) {
        if (apiCode > 0) {
            lastFailureApiCode = apiCode
        }
    }

    fun isLastFailureWorthWarpBootstrap(): Boolean {
        // ISPs completely block Opera IPs resulting in timeouts (code 0),
        // so we should always allow WARP bootstrap as a fallback if Opera fails.
        return true
    }

    fun getLoopbackProxyPort(context: Context): Int {
        val clientData = ClientData(context.applicationContext)
        return managedPort
            ?: clientData.getOperaInternalProxyPort().takeIf { it in 1024..65535 }
            ?: LEGACY_BIND_PORT
    }

    fun getLoopbackProxyAddress(context: Context): InetSocketAddress {
        return InetSocketAddress(BIND_HOST, getLoopbackProxyPort(context))
    }

    fun getLoopbackProxyUrl(context: Context): String {
        return "http://$BIND_HOST:${getLoopbackProxyPort(context)}"
    }

    fun ensureReady(
        context: Context,
        logger: (String) -> Unit,
        purposeLabel: String = "регистрации WARP",
        country: String = DEFAULT_COUNTRY,
        preferGlobalMaskHosts: Boolean = false,
        maskHostPolicy: MaskHostPolicy? = null,
        readyTimeoutMs: Long = 12_000L,
        maxMaskHostAttempts: Int? = null,
        maxLaunchPlans: Int? = null,
        skipHostlessCandidate: Boolean = false,
        maskHostRotation: Int = 0,
        onAttemptState: ((Int, Int, String) -> Unit)? = null,
        shouldAbort: (() -> Boolean)? = null,
    ): ReadyState {
        return synchronized(startLock) {
            val appContext = context.applicationContext
            lastAppContext = appContext
            val clientData = ClientData(appContext)
            val requestedCountry = normalizeOperaCountry(country)
            lastFailureApiCode = 0
            val detectedApiCode = AtomicInteger(0)
            var bindPort: Int
            var existingProcess: Process?
            var currentCountry: String?
            synchronized(lock) {
                existingProcess = managedProcess
                bindPort = managedPort
                    ?: clientData.getOperaInternalProxyPort().takeIf { it in 1024..65535 }
                    ?: LEGACY_BIND_PORT
                currentCountry = managedCountry
            }
            val launchLogger: (String) -> Unit = { message -> logger(message) }
            fun abortIfRequested(stopManagedProxy: Boolean = true): Boolean {
                if (shouldAbort?.invoke() != true) return false
                if (stopManagedProxy) {
                    stopManaged(launchLogger)
                }
                launchLogger("Запуск встроенного Opera proxy прерван текущей stop/connect-командой.")
                return true
            }
            if (abortIfRequested(stopManagedProxy = false)) {
                return ReadyState.FAILED
            }
            val persistedCountry = clientData.getOperaProxyCountry()
            if (
                existingProcess?.isAlive == true &&
                isLocalProxyOpen(bindPort) &&
                currentCountry.equals(requestedCountry, ignoreCase = true)
            ) {
                return ReadyState.STARTED_INTERNAL
            }
            if (
                existingProcess == null &&
                isLocalProxyOpen(bindPort) &&
                persistedCountry.isNotBlank() &&
                !persistedCountry.equals(requestedCountry, ignoreCase = true)
            ) {
                launchLogger("Локальный Opera proxy ещё занят регионом $persistedCountry, ожидаем освобождения перед стартом $requestedCountry...")
                waitUntilClosed(4200L, shouldAbort)
                if (isLocalProxyOpen(bindPort)) {
                    launchLogger("Локальный Opera proxy не освободился вовремя, регион $requestedCountry откладываем до следующей попытки.")
                    return ReadyState.FAILED
                }
            }
            if (
                existingProcess?.isAlive == true &&
                currentCountry != null &&
                !currentCountry.equals(requestedCountry, ignoreCase = true)
            ) {
                launchLogger("Меняем регион встроенного Opera proxy: $currentCountry -> $requestedCountry")
                stopManaged(launchLogger)
            }
            if (existingProcess?.isAlive == true && !isLocalProxyOpen(bindPort)) {
                stopManaged(launchLogger)
            }
            if (isLocalProxyOpen(bindPort)) {
                synchronized(lock) {
                    managedPort = bindPort
                }
                clientData.setOperaInternalProxyPort(bindPort)
                publishLocalProxyAddressToCore(bindPort, launchLogger)
                if (persistedCountry.isNotBlank()) {
                    launchLogger("Локальный proxy уже доступен на $BIND_HOST:$bindPort (region=$persistedCountry).")
                } else {
                    launchLogger("Локальный proxy уже доступен на $BIND_HOST:$bindPort.")
                }
                clientData.setOperaProxyCountry(if (persistedCountry.isNotBlank()) persistedCountry else requestedCountry)
                return ReadyState.ALREADY_RUNNING
            }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val bundledBinary = File(nativeLibDir, "liboperaproxy.so")
        if (!isRegistrationSupportedOnDevice(context) || !bundledBinary.exists()) {
            launchLogger(
                "Встроенный Opera proxy недоступен для ABI: ${Build.SUPPORTED_ABIS.joinToString()}."
            )
            return ReadyState.FAILED
        }

        return try {
            val binaryPath = bundledBinary
            launchLogger("Используем системно распакованный Opera proxy binary: ${binaryPath.absolutePath}")
            val candidatePool = when {
                !clientData.getTrafficMaskEnabled() -> ""
                clientData.getTrafficMaskMode() == "custom" -> ClientData.TRAFFIC_MASK_POOL_CUSTOM
                maskHostPolicy == MaskHostPolicy.WHITE_ONLY ||
                    maskHostPolicy == MaskHostPolicy.WHITE_FIRST ||
                    (!preferGlobalMaskHosts && maskHostPolicy == null) -> ClientData.TRAFFIC_MASK_POOL_RUSSIA
                else -> ClientData.TRAFFIC_MASK_POOL_GLOBAL
            }
            var bootstrapResolvers = clientData.getPreferredOperaBootstrapResolvers()
            var bootstrapLabel = clientData.getPreferredOperaBootstrapLabel()
            if (bootstrapLabel == "adguard-noads-opera-bootstrap") {
                val adguardReachable = DnsProbe.isReachable(
                    context = context,
                    servers = listOf("94.140.14.14", "94.140.15.15"),
                    cacheKeyPrefix = "opera-bootstrap-adguard",
                    logger = launchLogger,
                    protector = null,
                )
                if (!adguardReachable) {
                    bootstrapResolvers = clientData.getFallbackOperaBootstrapResolvers()
                    bootstrapLabel = clientData.getFallbackOperaBootstrapLabel()
                    launchLogger("AdGuard bootstrap DNS недоступен, используем стандартный bootstrap DNS.")
                }
            }
            val candidateHosts = resolveTrafficMaskCandidates(
                clientData = clientData,
                context = context,
                preferGlobalMaskHosts = preferGlobalMaskHosts,
                maskHostPolicy = maskHostPolicy,
                skipHostlessCandidate = skipHostlessCandidate,
                maskHostRotation = maskHostRotation,
            ).let { hosts ->
                val filtered = filterFailedHostsForSession(
                    hosts = hosts,
                    country = requestedCountry,
                    pool = candidatePool,
                )
                if (filtered.isNotEmpty()) filtered else hosts
            }.let { hosts ->
                val limit = maxMaskHostAttempts?.coerceAtLeast(1)
                if (limit != null && hosts.size > limit) hosts.take(limit) else hosts
            }
            val apiProfiles = orderedOperaApiProfiles(clientData, requestedCountry)
            val pinnedEndpoints = clientData.getOperaPinnedEndpoints(requestedCountry)
            val cachedEndpoints = pinnedEndpoints
                .filterNot { clientData.isOperaPinnedEndpointCoolingDown(requestedCountry, it) }
                .take(2)
            val apiRelays = apiRelays()
            val launchPlans = buildList {
                val seenPlans = linkedSetOf<String>()
                fun appendPlan(
                    host: String,
                    endpoint: String?,
                    apiProfile: OperaApiProfile,
                    apiRelay: String = "",
                ) {
                    val normalizedHost = host.trim()
                    val normalizedEndpoint = endpoint?.trim().orEmpty()
                    val key = "${normalizedEndpoint}|${apiProfile.id}|$normalizedHost|$apiRelay"
                    if (seenPlans.add(key)) {
                        add(OperaLaunchPlan(normalizedHost, endpoint, apiProfile, apiRelay))
                    }
                }
                if (cachedEndpoints.isNotEmpty()) {
                    for (endpoint in cachedEndpoints) {
                        val pinnedHosts = candidateHosts.take(2).ifEmpty { listOf("") }
                        for (host in pinnedHosts) {
                            for (apiProfile in apiProfiles) {
                                if (apiProfile.id == "api-legacy") {
                                    appendPlan("", endpoint, apiProfile)
                                }
                                appendPlan(host, endpoint, apiProfile)
                            }
                        }
                    }
                    launchLogger(
                        "Есть кэш Opera endpoints для $requestedCountry: " +
                            cachedEndpoints.joinToString(",") +
                            ". Сначала пробуем их через override, чтобы обойти discover."
                        )
                }
                if (pinnedEndpoints.isNotEmpty() && cachedEndpoints.isEmpty()) {
                    launchLogger(
                        "Кэшированные Opera endpoints для $requestedCountry сейчас в cooldown после ошибок. " +
                            "Пропускаем override и запускаем discovery/API."
                    )
                }
                // Discover через свой релей идёт раньше прямого: из России прямой
                // discover отдаёт набор endpoint'ов, до которых потом не дозвониться,
                // и все последующие попытки уходят в таймаут по очереди.
                if (apiRelays.isNotEmpty()) {
                    for (relay in apiRelays) {
                        for (apiProfile in apiProfiles) {
                            appendPlan("", null, apiProfile, relay)
                        }
                    }
                    launchLogger(
                        "Вызовы API SurfEasy для $requestedCountry сначала пробуем через свои релеи " +
                            "(${apiRelays.size}): туннель при этом набирается напрямую."
                    )
                } else {
                    launchLogger(
                        "Релеи API SurfEasy не заданы в сборке — discover идёт напрямую. " +
                            "Из России такой набор endpoint'ов обычно недостижим."
                    )
                }
                val directHosts = candidateHosts.ifEmpty { listOf("") }
                for (host in directHosts) {
                    for (apiProfile in apiProfiles) {
                        if (apiProfile.id == "api-legacy") {
                            appendPlan("", null, apiProfile)
                        }
                        appendPlan(host, null, apiProfile)
                    }
                }
            }.let { plans ->
                // Порядок сначала по способу добычи endpoint'а, и только внутри него —
                // по накопленной статистике. Иначе удачливый в прошлом прямой discover
                // обгонял и кэш, и релей: статистика копилась на сети, где он ещё
                // работал, а на текущей он отдаёт недостижимые адреса.
                fun discoverTier(plan: OperaLaunchPlan): Int = when {
                    !plan.endpointOverride.isNullOrBlank() -> 0
                    plan.apiRelay.isNotEmpty() -> 1
                    else -> 2
                }
                val rankedPlans = plans.withIndex()
                    .sortedWith(
                        compareBy<IndexedValue<OperaLaunchPlan>> { discoverTier(it.value) }
                            // Способ, который уже удерживал соединение двадцать секунд,
                            // идёт раньше любой накопленной статистики: она считает
                            // успехом и запуск, отвалившийся через секунду.
                            .thenByDescending { indexedPlan ->
                                clientData.getOperaLaunchPlanPromotedAt(
                                    country = requestedCountry,
                                    fakeSni = indexedPlan.value.fakeSni,
                                    endpoint = indexedPlan.value.endpointOverride,
                                    apiProfileId = indexedPlan.value.apiProfile.id,
                                )
                            }
                            .thenByDescending { indexedPlan ->
                                val launchScore = clientData.getOperaLaunchPlanScore(
                                    country = requestedCountry,
                                    fakeSni = indexedPlan.value.fakeSni,
                                    endpoint = indexedPlan.value.endpointOverride,
                                    apiProfileId = indexedPlan.value.apiProfile.id,
                                )
                                val registrationScore = clientData.getOperaRegistrationPlanScore(
                                    country = requestedCountry,
                                    fakeSni = indexedPlan.value.fakeSni,
                                    endpoint = indexedPlan.value.endpointOverride,
                                    apiProfileId = indexedPlan.value.apiProfile.id,
                                )
                                registrationScore * 1.7 + launchScore
                            }
                            .thenBy { it.index }
                    )
                    .map { it.value }
                if (rankedPlans.isNotEmpty()) {
                    val rankingPreview = rankedPlans.take(5).joinToString(" | ") { plan ->
                        val launchScore = clientData.getOperaLaunchPlanScore(
                            country = requestedCountry,
                            fakeSni = plan.fakeSni,
                            endpoint = plan.endpointOverride,
                            apiProfileId = plan.apiProfile.id,
                        )
                        val registrationScore = clientData.getOperaRegistrationPlanScore(
                            country = requestedCountry,
                            fakeSni = plan.fakeSni,
                            endpoint = plan.endpointOverride,
                            apiProfileId = plan.apiProfile.id,
                        )
                        buildString {
                            append(plan.apiProfile.id)
                            append('@')
                            append(plan.endpointOverride?.ifBlank { "discover" } ?: "discover")
                            append('/')
                            append(plan.fakeSni.ifBlank { "<none>" })
                            append(" reg=")
                            append(String.format(Locale.US, "%.1f", registrationScore))
                            append(" launch=")
                            append(String.format(Locale.US, "%.1f", launchScore))
                        }
                    }
                    launchLogger("Opera launch plan order for $requestedCountry: $rankingPreview")
                }
                val limit = maxLaunchPlans?.coerceAtLeast(1)
                if (limit != null && rankedPlans.size > limit) rankedPlans.take(limit) else rankedPlans
            }
            // Если в остывании оба профиля API, отсеется каждый план, и цикл закончится,
            // не начавшись. В живом логе это выглядело так: план построен, следом за 2мс
            // «Opera fallback недоступен» — и служба уходит в холостой повтор раз в
            // 2.6 секунды, ни разу ничего не попробовав. Остывание — это подсказка, что
            // пробовать первым, а не запрет подключаться вообще.
            val allApiProfilesCoolingDown = apiProfiles.isNotEmpty() &&
                apiProfiles.all { clientData.isOperaApiProfileCoolingDown(requestedCountry, it.id) }
            if (allApiProfilesCoolingDown) {
                launchLogger(
                    "Все профили API SurfEasy сейчас в остывании после отказов. " +
                        "Игнорируем его: иначе попыток не будет вовсе."
                )
            }
            val failedEndpointOverrides = linkedSetOf<String>()
            var attemptedPlans = 0
            for ((attemptIndex, plan) in launchPlans.withIndex()) {
                if (abortIfRequested()) {
                    return ReadyState.FAILED
                }
                val fakeSni = plan.fakeSni
                val endpointOverride = plan.endpointOverride
                val apiProfile = plan.apiProfile
                val normalizedEndpointOverride = endpointOverride?.trim().orEmpty()
                if (normalizedEndpointOverride.isNotBlank() && normalizedEndpointOverride in failedEndpointOverrides) {
                    continue
                }
                if (
                    !allApiProfilesCoolingDown &&
                    clientData.isOperaApiProfileCoolingDown(requestedCountry, apiProfile.id)
                ) {
                    continue
                }
                val planStartedAt = System.currentTimeMillis()
                attemptedPlans += 1
                bindPort = allocateInternalProxyPort(appContext, preferredPort = bindPort)
                onAttemptState?.invoke(attemptIndex + 1, launchPlans.size, fakeSni)
                val args = mutableListOf(
                    binaryPath.absolutePath,
                    "-bind-address",
                    "$BIND_HOST:$bindPort",
                    "-country",
                    requestedCountry,
                    "-verbosity",
                    DEFAULT_VERBOSITY,
                    "-bootstrap-dns",
                    bootstrapResolvers,
                    "-timeout",
                    "8s",
                    "-init-retries",
                    "1",
                    "-init-retry-interval",
                    "700ms",
                    "-server-selection",
                    serverSelectionForCountry(requestedCountry),
                    "-server-selection-test-url",
                    DEFAULT_TEST_URL,
                )
                if (serverSelectionForCountry(requestedCountry) == DEFAULT_SERVER_SELECTION) {
                    args += listOf("-server-selection-timeout", DEFAULT_SERVER_SELECTION_TIMEOUT)
                }
                appendOperaApiProfileArgs(args, apiProfile)
                if (fakeSni.isNotBlank()) {
                    args += listOf("-fake-SNI", fakeSni)
                }
                if (!endpointOverride.isNullOrBlank()) {
                    args += listOf("-override-proxy-address", endpointOverride)
                }
                var bridgedRelay = ""
                if (plan.apiRelay.isNotEmpty()) {
                    // Только вызовы API. Туннель остаётся прямым, иначе выход уехал бы
                    // в страну релея, а просили EU/US.
                    //
                    // Имя релея передаём не бинарнику, а мосту: резолвер Go на Android
                    // без настроек и уходит в [::1]:53, см. [OperaApiRelayBridge].
                    bridgedRelay = OperaApiRelayBridge.start(plan.apiRelay, launchLogger).orEmpty()
                    if (bridgedRelay.isEmpty()) {
                        launchLogger("Релей API недоступен, попытку через него пропускаем.")
                        continue
                    }
                    args += listOf("-api-proxy", bridgedRelay)
                }

                launchLogger("Поднимаем встроенный Opera proxy для $purposeLabel... попытка ${attemptIndex + 1}/${launchPlans.size}")
                launchLogger("Opera bootstrap DNS: $bootstrapLabel")
                launchLogger("Opera API profile: ${apiProfile.label}")
                if (fakeSni.isNotBlank()) {
                    launchLogger("Встроенный Opera proxy: fake SNI = $fakeSni")
                } else {
                    launchLogger("Встроенный Opera proxy: маскировка трафика отключена")
                }
                if (!endpointOverride.isNullOrBlank()) {
                    launchLogger("Встроенный Opera proxy: endpoint override = $endpointOverride")
                }
                if (plan.apiRelay.isNotEmpty()) {
                    launchLogger("Встроенный Opera proxy: API SurfEasy через релей ${describeApiRelay(plan.apiRelay)}")
                }
                if (plan.apiRelay.isEmpty()) {
                    // Прошлая попытка могла оставить мост поднятым, а этой он не нужен:
                    // открытый прокси к своему релею не должен жить дольше, чем нужен.
                    OperaApiRelayBridge.stop(launchLogger)
                }
                // Попытке по кэшу деваться некуда: discover пропущен, остаётся поднять
                // порт на готовом адресе. Пока ей отводился общий бюджет, каждый
                // протухший адрес стоил пятнадцати секунд — в живом логе два таких
                // подряд съели полминуты перед первой попыткой через релей. Продление
                // по признаку продвижения при этом никуда не делось: узел, который
                // действительно регистрируется, своё время получит.
                val planReadyTimeoutMs = if (!endpointOverride.isNullOrBlank()) {
                    minOf(readyTimeoutMs, CACHED_ENDPOINT_READY_TIMEOUT_MS)
                } else {
                    readyTimeoutMs
                }
                // Продление по признаку продвижения кэшированному плану нужно: discover
                // пропущен, но регистрация в SurfEasy идёт и здесь. Урезаем потолок, а не
                // отменяем его. Для AM всё остаётся как было.
                val planProgressExtensionMs = if (
                    !endpointOverride.isNullOrBlank() && requestedCountry != "AM"
                ) {
                    CACHED_ENDPOINT_PROGRESS_MAX_EXTENSION_MS
                } else {
                    PROGRESS_MAX_EXTENSION_MS
                }
                val launchModes = buildLaunchModes()
                var hostSucceeded = false
                // Один отказ — один штраф: ветка провала HTTP-probe уже записала исход,
                // и повторный учёт ниже завысил бы durationMs и перекосил ранжирование.
                var planOutcomeRecorded = false
                var successfulEndpoint = endpointOverride
                val startupTimeoutObserved = java.util.concurrent.atomic.AtomicBoolean(false)
                for ((launchVariantIndex, launchMode) in launchModes.withIndex()) {
                    if (abortIfRequested()) {
                        return ReadyState.FAILED
                    }
                    val selectedEndpoint = AtomicReference<String?>(null)
                    val lastStartupProgressAt = java.util.concurrent.atomic.AtomicLong(0L)
                    val process = try {
                        startOperaProcess(
                            args = args,
                            nativeLibDir = nativeLibDir,
                            launchMode = launchMode,
                        )
                    } catch (e: Exception) {
                        launchLogger(
                            "Не удалось запустить встроенный Opera proxy (${launchMode.name.lowercase()}): " +
                                "${e::class.java.simpleName}: ${e.message}"
                        )
                        null
                    }

                    if (process == null) {
                        continue
                    }

                    launchLogger("Opera proxy process started (${launchMode.name.lowercase()})")
                    synchronized(lock) {
                        managedProcess = process
                        managedCountry = requestedCountry
                        managedPort = bindPort
                        managedFakeSni = fakeSni.takeIf { it.isNotBlank() }
                        managedApiProfileId = apiProfile.id
                    }
                    clientData.setOperaInternalProxyPort(bindPort)
                    publishLocalProxyAddressToCore(bindPort, launchLogger)
                    clientData.setOperaProxyCountry(requestedCountry)
                    clientData.setTrafficMaskActiveHost(
                        fakeSni.takeIf { it.isNotBlank() },
                        candidatePool,
                    )
                    logThread = thread(
                        start = true,
                        isDaemon = true,
                        name = "nova-opera-proxy-log",
                    ) {
                        try {
                            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isNotBlank()) {
                                        launchLogger("[OperaProxy] $line")
                                        extractOperaApiCode(line)?.let { apiCode ->
                                            detectedApiCode.compareAndSet(0, apiCode)
                                            lastFailureApiCode = apiCode
                                        }
                                        if (isOperaStartupTimeoutLine(line)) {
                                            startupTimeoutObserved.set(true)
                                        }
                                        if (isOperaStartupProgressLine(line)) {
                                            lastStartupProgressAt.set(System.currentTimeMillis())
                                        }
                                        extractSelectedEndpoint(line)?.let { selected ->
                                            selectedEndpoint.set(selected)
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }

                    if (waitUntilReady(
                            process = process,
                            bindPort = bindPort,
                            timeoutMs = planReadyTimeoutMs,
                            logger = launchLogger,
                            shouldAbort = shouldAbort,
                            lastProgressAtMs = { lastStartupProgressAt.get() },
                            maxExtensionMs = planProgressExtensionMs,
                        )
                    ) {
                        val selectedOrOverride = endpointOverride ?: selectedEndpoint.get()
                        val requireHttpProbe = requestedCountry == "AM" || !endpointOverride.isNullOrBlank()
                        // AM ходит своей веткой и другого способа проверить выход не имеет:
                        // там проба остаётся во всю длину. Кэшированному адресу столько
                        // не нужно — живой отвечает с первого URL.
                        val probeAttemptTimeoutMs = if (requestedCountry == "AM") {
                            2600
                        } else {
                            CACHED_ENDPOINT_PROBE_ATTEMPT_TIMEOUT_MS
                        }
                        val probeBudgetMs = if (requestedCountry == "AM") {
                            Long.MAX_VALUE
                        } else {
                            CACHED_ENDPOINT_PROBE_BUDGET_MS
                        }
                        if (
                            requireHttpProbe &&
                            !probeLocalProxyHttpConnectivity(bindPort, probeAttemptTimeoutMs, probeBudgetMs)
                        ) {
                            launchLogger(
                                "Opera endpoint ${selectedOrOverride ?: "<discover>"} поднял локальный порт, " +
                                    "но не дал HTTP-probe. Переходим к следующему плану."
                            )
                            // Прерывание — не отказ узла: пока сворачивается стек или
                            // сменилось поколение подключения, репутацию не портим.
                            if (shouldAbort?.invoke() != true) {
                                planOutcomeRecorded = true
                                clientData.recordOperaLaunchPlanOutcome(
                                    country = requestedCountry,
                                    fakeSni = fakeSni,
                                    endpoint = selectedOrOverride,
                                    apiProfileId = apiProfile.id,
                                    success = false,
                                    durationMs = System.currentTimeMillis() - planStartedAt,
                                )
                                if (!selectedOrOverride.isNullOrBlank()) {
                                    clientData.demoteOperaPinnedEndpoint(requestedCountry, selectedOrOverride)
                                    clientData.markOperaPinnedEndpointFailure(
                                        requestedCountry,
                                        selectedOrOverride,
                                        cooldownMs = if (requestedCountry == "AM") 30L * 60L * 1000L else 90_000L,
                                    )
                                }
                            }
                            stopManaged(launchLogger)
                            if (requestedCountry == "AM") {
                                return ReadyState.FAILED
                            }
                            // Именно break, а не continue: порт уже открылся, значит способ
                            // запуска ни при чём, и второй проход по launchModes на arm32
                            // прогнал бы тот же мёртвый адрес ещё раз.
                            break
                        }
                        clearFailedHostForSession(
                            country = requestedCountry,
                            pool = candidatePool,
                            host = fakeSni,
                        )
                        successfulEndpoint = endpointOverride ?: selectedEndpoint.get()
                        synchronized(lock) {
                            managedEndpoint = successfulEndpoint
                        }
                        successfulEndpoint?.let { clientData.promoteOperaPinnedEndpoint(requestedCountry, it) }
                        clientData.setPreferredOperaApiProfile(requestedCountry, apiProfile.id)
                        clientData.recordOperaLaunchPlanOutcome(
                            country = requestedCountry,
                            fakeSni = fakeSni,
                            endpoint = successfulEndpoint ?: endpointOverride,
                            apiProfileId = apiProfile.id,
                            success = true,
                            durationMs = System.currentTimeMillis() - planStartedAt,
                        )
                        launchLogger("Встроенный Opera proxy готов на $BIND_HOST:$bindPort.")
                        hostSucceeded = true
                        break
                    }

                    val exited = !process.isAlive
                    if (exited) {
                        runCatching { logThread?.join(600L) }
                    }
                    if (startupTimeoutObserved.get()) {
                        clientData.markOperaApiProfileFailure(requestedCountry, apiProfile.id)
                        launchLogger(
                            "Opera API ${apiProfile.label} дал timeout на регистрации. " +
                                "Оставшиеся попытки этого API в текущем запуске пропускаем."
                        )
                    }
                    stopManaged(launchLogger)
                    if (abortIfRequested(stopManagedProxy = false)) {
                        return ReadyState.FAILED
                    }
                    val failedApiCode = detectedApiCode.get()
                    if (
                        endpointOverride.isNullOrBlank() &&
                        requestedCountry == "EU" &&
                        failedApiCode in failFastBootstrapApiCodes
                    ) {
                        lastFailureApiCode = failedApiCode
                        clientData.markOperaApiProfileFailure(requestedCountry, apiProfile.id, cooldownMs = 45_000L)
                        launchLogger(
                            "Opera EU discover вернула code=$failedApiCode. " +
                                "Дальнейший перебор fake SNI/launch plans для EU сейчас бесполезен, " +
                                "раньше переходим к следующей стране."
                        )
                        return ReadyState.FAILED
                    }
                    if (
                        endpointOverride.isNullOrBlank() &&
                        requestedCountry == "AM" &&
                        apiProfile.id == "api-legacy" &&
                        fakeSni.isBlank() &&
                        (failedApiCode == 801 || failedApiCode == 500 || failedApiCode == 502)
                    ) {
                        lastFailureApiCode = failedApiCode
                        clientData.markOperaApiProfileFailure(requestedCountry, apiProfile.id, cooldownMs = 45_000L)
                        launchLogger(
                            "Opera AM через ${apiProfile.label} без SNI вернула code=$failedApiCode. " +
                                "Legacy API временно пропускаем и продолжаем прямой перебор API2/SNI без WARP-bootstrap."
                        )
                    }
                    if (exited && launchVariantIndex < launchModes.lastIndex) {
                        launchLogger(
                            "Пробуем альтернативный способ запуска встроенного Opera proxy " +
                                "для того же домена..."
                        )
                        Thread.sleep(200L)
                        continue
                    }

                    launchLogger("Не удалось дождаться запуска встроенного Opera proxy.")
                    break
                }

                if (hostSucceeded) {
                    successfulEndpoint?.let {
                        launchLogger("Opera endpoint сохранён в кэш $requestedCountry: $it")
                    }
                    return ReadyState.STARTED_INTERNAL
                }

                rememberFailedHostForSession(
                    country = requestedCountry,
                    pool = candidatePool,
                    host = fakeSni,
                )
                clientData.recordTrafficMaskAttempt(
                    fakeSni.takeIf { it.isNotBlank() },
                    success = false,
                    poolHint = candidatePool,
                )
                if (!planOutcomeRecorded) {
                    clientData.recordOperaLaunchPlanOutcome(
                        country = requestedCountry,
                        fakeSni = fakeSni,
                        endpoint = endpointOverride,
                        apiProfileId = apiProfile.id,
                        success = false,
                        durationMs = System.currentTimeMillis() - planStartedAt,
                    )
                }
                if (!endpointOverride.isNullOrBlank()) {
                    clientData.demoteOperaPinnedEndpoint(requestedCountry, endpointOverride)
                    clientData.markOperaPinnedEndpointFailure(
                        requestedCountry,
                        endpointOverride,
                        cooldownMs = if (requestedCountry == "AM") 30L * 60L * 1000L else 90_000L,
                    )
                    failedEndpointOverrides += normalizedEndpointOverride
                    launchLogger(
                        "Кэшированный Opera endpoint $normalizedEndpointOverride не поднялся. " +
                            "Ставим его на cooldown и сразу переходим к другим launch plan."
                    )
                }
                if (attemptIndex < launchPlans.lastIndex) {
                    if (abortIfRequested(stopManagedProxy = false)) {
                        return ReadyState.FAILED
                    }
                    Thread.sleep(450L)
                }
            }
            if (attemptedPlans == 0) {
                // Отличать «пробовали и не вышло» от «пробовать было нечего» важно:
                // второе означает, что отсеялись все планы, и повтор цикла ничего не
                // изменит, сколько его ни крути.
                launchLogger(
                    "Ни один план запуска Opera не был выполнен: все ${launchPlans.size} " +
                        "отсеялись до попытки. Повтор цикла здесь не поможет."
                )
            }
            lastFailureApiCode = detectedApiCode.get()
            ReadyState.FAILED
        } catch (e: Exception) {
            launchLogger("Ошибка запуска встроенного Opera proxy: ${e::class.java.simpleName}: ${e.message}")
            stopManaged(launchLogger)
            ReadyState.FAILED
        }
        }
    }

    fun stopManaged(logger: (String) -> Unit) {
        val process: Process?
        val contextToClear: Context?
        synchronized(lock) {
            process = managedProcess
            contextToClear = lastAppContext
            managedProcess = null
            managedCountry = null
            managedPort = null
            managedEndpoint = null
            managedFakeSni = null
            managedApiProfileId = null
        }
        publishLocalProxyAddressToCore(null, logger)
        OperaApiRelayBridge.stop(logger)

        contextToClear?.let { ctx ->
            runCatching { ClientData(ctx).setOperaInternalProxyPort(null) }
        }

        val threadToInterrupt = logThread
        logThread = null
        threadToInterrupt?.interrupt()

        if (process != null) {
            try {
                process.destroy()
                if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    process.waitFor(1500, TimeUnit.MILLISECONDS)
                }
            } catch (_: Exception) {
            } finally {
                logger("Встроенный Opera proxy остановлен.")
            }
        }
    }

    /**
     * Поднимает наверх очереди тот способ запуска, которым сейчас держится туннель.
     *
     * Вызывается не по факту подключения, а когда соединение уже продержалось: до
     * этого момента отличить рабочий выход от поднявшего локальный порт и сразу
     * отвалившегося нельзя. Заодно поднимается сам endpoint и профиль API — в
     * следующий раз перебор начнётся ровно с того, что сработало.
     *
     * @return строка для журнала или пустая, если поднимать нечего.
     */
    fun promoteCurrentLaunchPlan(context: Context): String {
        val country: String?
        val fakeSni: String?
        val endpoint: String?
        val apiProfileId: String?
        synchronized(lock) {
            country = managedCountry
            fakeSni = managedFakeSni
            endpoint = managedEndpoint
            apiProfileId = managedApiProfileId
        }
        val normalizedCountry = country?.trim().orEmpty()
        if (normalizedCountry.isEmpty()) return ""
        val clientData = ClientData(context.applicationContext)
        clientData.promoteOperaLaunchPlan(
            country = normalizedCountry,
            fakeSni = fakeSni,
            endpoint = endpoint,
            apiProfileId = apiProfileId,
        )
        endpoint?.takeIf { it.isNotBlank() }?.let { clientData.promoteOperaPinnedEndpoint(normalizedCountry, it) }
        apiProfileId?.takeIf { it.isNotBlank() }?.let {
            clientData.setPreferredOperaApiProfile(normalizedCountry, it)
        }
        return buildString {
            append("API=")
            append(apiProfileId?.takeIf { it.isNotBlank() } ?: "по умолчанию")
            append(", endpoint=")
            append(endpoint?.takeIf { it.isNotBlank() } ?: "из discover")
            append(", SNI=")
            append(fakeSni?.takeIf { it.isNotBlank() } ?: "без маскировки")
        }
    }

    fun markCurrentMaskHostSuccessful(context: Context) {
        val clientData = ClientData(context)
        val activeHost = clientData.getTrafficMaskActiveHost()
        if (activeHost.isNotBlank()) {
            clientData.recordTrafficMaskAttempt(
                activeHost,
                success = true,
                poolHint = clientData.getTrafficMaskActivePool(),
            )
        }
    }

    fun discoverPinnedEndpoints(
        context: Context,
        logger: (String) -> Unit,
        country: String = DEFAULT_COUNTRY,
        preferGlobalMaskHosts: Boolean = true,
        maskHostPolicy: MaskHostPolicy? = null,
        maxMaskHostAttempts: Int = 6,
        shouldAbort: (() -> Boolean)? = null,
    ): DiscoveryResult {
        val appContext = context.applicationContext
        lastAppContext = appContext
        val clientData = ClientData(appContext)
        val requestedCountry = normalizeOperaCountry(country)
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binaryPath = File(nativeLibDir, "liboperaproxy.so")
        if (!binaryPath.exists()) {
            logger("Opera endpoint discovery невозможен: liboperaproxy.so не найден.")
            return DiscoveryResult()
        }

        var bootstrapResolvers = clientData.getPreferredOperaBootstrapResolvers()
        var bootstrapLabel = clientData.getPreferredOperaBootstrapLabel()
        if (bootstrapLabel == "adguard-noads-opera-bootstrap") {
            val adguardReachable = DnsProbe.isReachable(
                context = context,
                servers = listOf("94.140.14.14", "94.140.15.15"),
                cacheKeyPrefix = "opera-discovery-adguard",
                logger = logger,
                protector = null,
            )
            if (!adguardReachable) {
                bootstrapResolvers = clientData.getFallbackOperaBootstrapResolvers()
                bootstrapLabel = clientData.getFallbackOperaBootstrapLabel()
            }
        }
        val candidatePool = when {
            !clientData.getTrafficMaskEnabled() -> ""
            clientData.getTrafficMaskMode() == "custom" -> ClientData.TRAFFIC_MASK_POOL_CUSTOM
            else -> ClientData.TRAFFIC_MASK_POOL_GLOBAL
        }
        val candidateHosts = resolveTrafficMaskCandidates(
            clientData = clientData,
            context = context,
            preferGlobalMaskHosts = preferGlobalMaskHosts,
            maskHostPolicy = maskHostPolicy,
            skipHostlessCandidate = false,
            maskHostRotation = 0,
        ).take(maxMaskHostAttempts.coerceAtLeast(1))

        val apiProfiles = orderedOperaApiProfiles(clientData, requestedCountry)
        val collected = linkedSetOf<String>()
        var detectedCode = 0
        // Свой релей идёт первым по той же причине, что и при запуске туннеля: список
        // endpoint'ов зависит от того, откуда пришёл запрос, и российскому адресу
        // выдаётся набор, до которого потом всё равно не дозвониться. Пустая строка в
        // конце — прежний прямой путь, он остаётся запасным.
        val discoveryPasses = (apiRelays() + "").flatMap { relay ->
            apiProfiles.map { profile -> relay to profile }
        }
        for ((apiRelay, apiProfile) in discoveryPasses) {
            // Через релей маскировать SNI незачем: соединение с API идёт до релея,
            // а имя api2.sec-tunnel.com в открытый эфир не выходит вовсе.
            val profileHosts = if (apiRelay.isNotEmpty()) {
                listOf("")
            } else {
                maskHostsForApiProfile(apiProfile, candidateHosts)
            }
            for ((index, fakeSni) in profileHosts.withIndex()) {
                if (shouldAbort?.invoke() == true) break
                val args = mutableListOf(
                    binaryPath.absolutePath,
                    "-country",
                    requestedCountry,
                    "-verbosity",
                    DEFAULT_VERBOSITY,
                    "-bootstrap-dns",
                    bootstrapResolvers,
                    "-server-selection",
                    serverSelectionForCountry(requestedCountry),
                    "-server-selection-test-url",
                    DEFAULT_TEST_URL,
                    "-timeout",
                    "8s",
                    "-init-retries",
                    "1",
                    "-init-retry-interval",
                    "700ms",
                    "-list-proxies",
                )
                if (serverSelectionForCountry(requestedCountry) == DEFAULT_SERVER_SELECTION) {
                    args += listOf("-server-selection-timeout", DEFAULT_SERVER_SELECTION_TIMEOUT)
                }
                appendOperaApiProfileArgs(args, apiProfile)
                if (fakeSni.isNotBlank()) {
                    args += listOf("-fake-SNI", fakeSni)
                }
                if (apiRelay.isNotEmpty()) {
                    val bridged = OperaApiRelayBridge.start(apiRelay, logger)
                    if (bridged.isNullOrEmpty()) {
                        logger("Релей API недоступен, discovery через него пропускаем.")
                        continue
                    }
                    args += listOf("-api-proxy", bridged)
                }
                logger(
                    "Opera endpoint discovery через текущую сеть/VPN: $requestedCountry " +
                        "попытка ${index + 1}/${profileHosts.size}, API=${apiProfile.label}, DNS=$bootstrapLabel" +
                        (if (fakeSni.isNotBlank()) ", SNI=$fakeSni" else ", без SNI") +
                        if (apiRelay.isNotEmpty()) ", релей ${describeApiRelay(apiRelay)}" else ""
                )

                for (launchMode in buildLaunchModes()) {
                    if (shouldAbort?.invoke() == true) break
                    val output = StringBuilder()
                    val process = try {
                        startOperaProcess(args, nativeLibDir, launchMode)
                    } catch (e: Exception) {
                        logger("Opera endpoint discovery не стартовал (${launchMode.name.lowercase()}): ${e.message}")
                        null
                    } ?: continue

                    val readerThread = thread(start = true, isDaemon = true, name = "nova-opera-discovery-log") {
                        try {
                            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                                while (true) {
                                    val line = reader.readLine() ?: break
                                    if (line.isBlank()) continue
                                    synchronized(output) {
                                        output.append(line).append('\n')
                                    }
                                    extractOperaApiCode(line)?.let { code ->
                                        detectedCode = code
                                        lastFailureApiCode = code
                                    }
                                    parseProxyListEndpoint(line)?.let(collected::add)
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }

                    val finished = runCatching { process.waitFor(12_000L, TimeUnit.MILLISECONDS) }.getOrDefault(false)
                    if (!finished) {
                        process.destroyForcibly()
                        logger("Opera endpoint discovery превысил таймаут, процесс остановлен.")
                    }
                    runCatching { readerThread.join(800L) }
                    if (collected.isNotEmpty()) {
                        val endpoints = collected.toList()
                        // Складываем, а не заменяем: проверенный адрес должен остаться
                        // наверху очереди, иначе фоновая проверка отменяет подъём,
                        // сделанный после двадцати секунд удержания.
                        val knownBefore = clientData.getOperaPinnedEndpoints(requestedCountry)
                        val mergedCache = clientData.mergeDiscoveredOperaPinnedEndpoints(requestedCountry, endpoints)
                        clientData.setPreferredOperaApiProfile(requestedCountry, apiProfile.id)
                        logger("Opera endpoints сохранены для $requestedCountry через ${apiProfile.label}: ${endpoints.joinToString(",")}")
                        // Печатаем итог слияния целиком: строка «Есть кэш Opera endpoints»
                        // показывает только первые два адреса вне остывания, и по ней
                        // нельзя отличить сложенный список от заменённого.
                        logger(
                            "Кэш Opera endpoints для $requestedCountry после слияния: " +
                                "было ${knownBefore.size}, discover дал ${endpoints.size}, " +
                                "стало ${mergedCache.size}: ${mergedCache.joinToString(",")}"
                        )
                        clientData.recordTrafficMaskAttempt(
                            fakeSni.takeIf { it.isNotBlank() },
                            success = true,
                            poolHint = candidatePool,
                        )
                        OperaApiRelayBridge.stop(logger)
                        return DiscoveryResult(endpoints = endpoints, apiCode = detectedCode.takeIf { it > 0 })
                    }
                }

                clientData.recordTrafficMaskAttempt(
                    fakeSni.takeIf { it.isNotBlank() },
                    success = false,
                    poolHint = candidatePool,
                )
            }
        }
        OperaApiRelayBridge.stop(logger)
        return DiscoveryResult(apiCode = detectedCode.takeIf { it > 0 })
    }

    private fun extractOperaApiCode(line: String): Int? {
        val normalized = line.lowercase()
        if (normalized.contains("server replied with 502")) return 502
        if (normalized.contains("upstream proxy server: 500 internal server error")) return 500
        return apiCodePattern.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun isOperaStartupTimeoutLine(line: String): Boolean {
        val normalized = line.lowercase()
        return normalized.contains("context deadline exceeded") ||
            normalized.contains("i/o timeout") ||
            normalized.contains("client.timeout exceeded")
    }

    /**
     * Строка лога, по которой видно, что запуск прокси движется вперёд.
     *
     * Регистрация в Opera на медленной сети занимает больше, чем отведено на ожидание:
     * в логе видно, как анонимная регистрация проходит за семь секунд, а ожидание
     * обрывается на восьмой. Следующая попытка начинает регистрацию заново, поэтому
     * перебор не сходится — в живом логе так набралось 17 подряд неудачных запусков.
     * Пока прокси отчитывается о продвижении, ожидание продлевается (с жёстким
     * потолком, см. [waitUntilReady]).
     */
    private fun isOperaStartupProgressLine(line: String): Boolean {
        val normalized = line.lowercase()
        if (isOperaStartupTimeoutLine(normalized)) return false
        return normalized.contains("attempting action") ||
            normalized.contains("succeeded on attempt") ||
            normalized.contains("discovered endpoints") ||
            normalized.contains("selected endpoint address") ||
            normalized.contains("starting proxy server") ||
            normalized.contains("init complete")
    }

    private fun extractSelectedEndpoint(line: String): String? {
        return normalizeEndpointAddress(selectedEndpointPattern.find(line)?.groupValues?.getOrNull(1))
    }

    private fun parseProxyListEndpoint(line: String): String? {
        val clean = line.trim()
        if (clean.isBlank() || clean.startsWith("host,", ignoreCase = true)) return null
        val parts = clean.split(',').map { it.trim() }
        if (parts.size < 3) return null
        val host = parts.getOrNull(1).orEmpty().ifBlank { parts.getOrNull(0).orEmpty() }
        val port = parts.getOrNull(2).orEmpty()
        return normalizeEndpointAddress("$host:$port")
    }

    private fun normalizeEndpointAddress(value: String?): String? {
        val clean = value?.trim().orEmpty()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
        if (clean.isBlank() || ':' !in clean) return null
        val host = clean.substringBeforeLast(':').trim().trim('[', ']')
        val port = clean.substringAfterLast(':', "").trim().toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        if (!host.matches(Regex("^[a-zA-Z0-9.:-]+$")) || host.contains("..")) return null
        return "$host:$port"
    }

    /**
     * Проверяет, что локальный порт не просто открыт, а действительно проксирует.
     *
     * Перебор шести адресов подряд стоил шести таймаутов: на протухшем endpoint'е TCP
     * до 127.0.0.1 устанавливается мгновенно, а ответа на CONNECT не приходит, и
     * каждая проба честно ждала свой таймаут — шесть по 2.6 с. Попытка по кэшу из-за
     * этого растягивалась на пятнадцать секунд при бюджете плана в четыре: проба жила
     * снаружи этого бюджета и ничем не ограничивалась. Теперь у неё есть свой: адреса
     * перебираются, пока он не исчерпан, а таймаут каждой попытки не превышает остатка.
     */
    private fun probeLocalProxyHttpConnectivity(
        port: Int,
        timeoutMs: Int,
        budgetMs: Long = Long.MAX_VALUE,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(BIND_HOST, port))
        // Только Cloudflare: раньше сюда были вписаны gstatic, google, ident.me,
        // icanhazip и ipify — пять чужих сервисов ради ответа «канал жив».
        val probeUrls = CloudflareTrace.PROBE_URLS
        for (url in probeUrls) {
            val remainingMs = budgetMs - (System.currentTimeMillis() - startedAt)
            // Меньше полусекунды — это уже не проба, а лишний таймаут.
            if (remainingMs < 500L) break
            val attemptTimeoutMs = minOf(timeoutMs.toLong(), remainingMs).toInt()
            try {
                val connection = URL(url).openConnection(proxy) as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = attemptTimeoutMs
                connection.readTimeout = attemptTimeoutMs
                connection.useCaches = false
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "NovaAndroid/1.21")
                connection.setRequestProperty("Accept", "text/plain,*/*")
                val code = try {
                    connection.responseCode
                } finally {
                    runCatching { connection.inputStream?.close() }
                    runCatching { connection.errorStream?.close() }
                    connection.disconnect()
                }
                if (
                    code == HttpURLConnection.HTTP_NO_CONTENT ||
                    code == HttpURLConnection.HTTP_OK ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 ||
                    code == 308
                ) {
                    return true
                }
            } catch (_: Exception) {
            }
        }
        return false
    }

    /**
     * Ждёт, пока встроенный Opera proxy откроет локальный порт.
     *
     * Базовый срок задаётся вызывающей стороной, но обрывать запуск ровно по нему
     * нельзя: регистрация в Opera на медленной сети идёт дольше, и обрыв на середине
     * означает, что следующая попытка начнёт её заново. Поэтому, пока прокси пишет в
     * лог о продвижении (см. [isOperaStartupProgressLine]), ожидание продлевается — но
     * не более чем на [maxExtensionMs] (по умолчанию [PROGRESS_MAX_EXTENSION_MS], а
     * попытке по готовому адресу из кэша отводится
     * [CACHED_ENDPOINT_PROGRESS_MAX_EXTENSION_MS]), чтобы зависший запуск не держал
     * подключение бесконечно.
     */
    private fun waitUntilReady(
        process: Process,
        bindPort: Int,
        timeoutMs: Long,
        logger: (String) -> Unit,
        shouldAbort: (() -> Boolean)? = null,
        lastProgressAtMs: (() -> Long)? = null,
        maxExtensionMs: Long = PROGRESS_MAX_EXTENSION_MS,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        val baseDeadline = startedAt + timeoutMs
        val hardDeadline = baseDeadline + maxExtensionMs
        var extensionLogged = false
        while (true) {
            val now = System.currentTimeMillis()
            if (now >= hardDeadline) return false
            if (now >= baseDeadline) {
                val progressAt = lastProgressAtMs?.invoke() ?: 0L
                if (progressAt <= 0L || now - progressAt > PROGRESS_GRACE_MS) return false
                if (!extensionLogged) {
                    extensionLogged = true
                    logger(
                        "Встроенный Opera proxy ещё регистрируется — продлеваем ожидание " +
                            "ещё на ${maxExtensionMs / 1000} с вместо перезапуска с нуля."
                    )
                }
            }
            if (shouldAbort?.invoke() == true) {
                logger("Ожидание готовности встроенного Opera proxy прервано.")
                return false
            }
            if (isLocalProxyOpen(bindPort)) {
                return true
            }
            if (!process.isAlive) {
                val exitCode = runCatching { process.exitValue() }.getOrNull()
                logger(
                    "Встроенный Opera proxy завершился до готовности" +
                        (exitCode?.let { " (exit=$it)" } ?: "") +
                        ". Переходим к следующему домену."
                )
                return false
            }
            // Опрос вдвое чаще: на закрытом локальном порту connect падает мгновенно по
            // ECONNREFUSED, поэтому шаг ничего не стоит и снимает до 0.4 с с запуска.
            Thread.sleep(200L)
        }
    }

    private fun waitUntilClosed(timeoutMs: Long, shouldAbort: (() -> Boolean)? = null): Boolean {
        val bindPort = managedPort ?: lastAppContext?.let { ClientData(it).getOperaInternalProxyPort() } ?: LEGACY_BIND_PORT
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (shouldAbort?.invoke() == true) {
                return false
            }
            if (!isLocalProxyOpen(bindPort)) {
                return true
            }
            Thread.sleep(200L)
        }
        return !isLocalProxyOpen(bindPort)
    }

    private fun resolveTrafficMaskCandidates(
        clientData: ClientData,
        context: Context,
        preferGlobalMaskHosts: Boolean,
        maskHostPolicy: MaskHostPolicy?,
        skipHostlessCandidate: Boolean,
        maskHostRotation: Int,
    ): List<String> {
        if (!clientData.getTrafficMaskEnabled()) {
            return listOf("")
        }
        return when (clientData.getTrafficMaskMode()) {
            "custom" -> listOf(clientData.getTrafficMaskHost())
            else -> {
                val effectivePolicy = maskHostPolicy ?: when {
                    preferGlobalMaskHosts -> MaskHostPolicy.GLOBAL_FIRST
                    else -> MaskHostPolicy.WHITE_FIRST
                }
                val whiteHosts = TrafficMaskCatalog.getWhiteHosts(context)
                val globalHosts = TrafficMaskCatalog.getGlobalHosts(context)
                val primaryHosts = when (effectivePolicy) {
                    MaskHostPolicy.WHITE_FIRST,
                    MaskHostPolicy.WHITE_ONLY -> whiteHosts
                    MaskHostPolicy.GLOBAL_FIRST,
                    MaskHostPolicy.GLOBAL_ONLY -> globalHosts
                }
                val secondaryHosts = when (effectivePolicy) {
                    MaskHostPolicy.WHITE_FIRST -> globalHosts
                    MaskHostPolicy.GLOBAL_FIRST -> whiteHosts
                    MaskHostPolicy.WHITE_ONLY,
                    MaskHostPolicy.GLOBAL_ONLY -> emptyList()
                }
                val preferredPool = when (effectivePolicy) {
                    MaskHostPolicy.WHITE_FIRST,
                    MaskHostPolicy.WHITE_ONLY -> ClientData.TRAFFIC_MASK_POOL_RUSSIA
                    MaskHostPolicy.GLOBAL_FIRST,
                    MaskHostPolicy.GLOBAL_ONLY -> ClientData.TRAFFIC_MASK_POOL_GLOBAL
                }
                val combined = linkedSetOf<String>()
                clientData.getPreferredTrafficMaskHosts(primaryHosts, limit = 8).forEach(combined::add)
                clientData.getPreferredTrafficMaskHosts(secondaryHosts, limit = 16).forEach(combined::add)
                if (clientData.shouldPreferMessengerWarpProfiles()) {
                    combined.add(
                        MessengerObfsPolicy.pickCamouflageHost(
                            context = context,
                            seed = "opera-${clientData.getPreferredOperaLabel().lowercase()}-${preferredPool.lowercase()}",
                        )
                    )
                }
                if (!skipHostlessCandidate && clientData.getTrafficMaskLastSuccessfulHostForPool(preferredPool).isBlank()) {
                    combined.add("")
                }
                rotateMaskCandidates(combined.toList(), maskHostRotation)
            }
        }.ifEmpty { listOf("") }
    }

    private fun rotateMaskCandidates(hosts: List<String>, offset: Int): List<String> {
        if (hosts.size <= 1) return hosts
        val normalizedOffset = offset.coerceAtLeast(0)
        if (normalizedOffset == 0) return hosts

        val hostlessFirst = hosts.firstOrNull().orEmpty().isBlank()
        val prefix = if (hostlessFirst) listOf("") else emptyList()
        val maskedHosts = if (hostlessFirst) hosts.drop(1) else hosts
        if (maskedHosts.size <= 1) return hosts

        val effectiveOffset = normalizedOffset % maskedHosts.size
        if (effectiveOffset == 0) return hosts

        return prefix +
            maskedHosts.drop(effectiveOffset) +
            maskedHosts.take(effectiveOffset)
    }

    private fun orderedOperaApiProfiles(
        clientData: ClientData,
        country: String,
    ): List<OperaApiProfile> {
        val preferred = clientData.getPreferredOperaApiProfile(country)
            .takeUnless { clientData.isOperaApiProfileCoolingDown(country, it) }
            .orEmpty()
        val defaultOrder = if (normalizeOperaCountry(country) == "AM") {
            operaApiProfiles.sortedBy { if (it.id == "api-legacy") 0 else 1 }
        } else {
            operaApiProfiles
        }
        fun defaultIndex(profile: OperaApiProfile): Int {
            return defaultOrder.indexOf(profile).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE
        }
        if (preferred.isBlank()) {
            return operaApiProfiles.sortedWith(
                compareBy<OperaApiProfile> { if (clientData.isOperaApiProfileCoolingDown(country, it.id)) 1 else 0 }
                    .thenBy(::defaultIndex)
            )
        }
        return operaApiProfiles.sortedWith(
            compareBy<OperaApiProfile> { if (clientData.isOperaApiProfileCoolingDown(country, it.id)) 1 else 0 }
                .thenBy { if (it.id == preferred) 0 else 1 }
                .thenBy(::defaultIndex)
        )
    }

    private fun appendOperaApiProfileArgs(args: MutableList<String>, profile: OperaApiProfile) {
        args += listOf("-api-client-type", profile.clientType)
        args += listOf("-api-client-version", profile.clientVersion)
        args += listOf("-api-user-agent", profile.userAgent)
        profile.apiAddress?.takeIf { it.isNotBlank() }?.let { apiAddress ->
            args += listOf("-api-address", apiAddress)
        }
    }

    private fun serverSelectionForCountry(country: String): String {
        return if (normalizeOperaCountry(country) == "AM") AM_SERVER_SELECTION else DEFAULT_SERVER_SELECTION
    }

    private fun maskHostsForApiProfile(
        profile: OperaApiProfile,
        hosts: List<String>,
    ): List<String> {
        if (profile.id != "api-legacy") return hosts.ifEmpty { listOf("") }
        return (listOf("") + hosts.filter { it.isNotBlank() }).distinct().ifEmpty { listOf("") }
    }

    private fun normalizeOperaCountry(value: String?): String {
        return when (value?.trim()?.uppercase()) {
            "EU" -> "EU"
            "AM", "US" -> "AM"
            "AS" -> "AS"
            else -> DEFAULT_COUNTRY
        }
    }

    private fun filterFailedHostsForSession(
        hosts: List<String>,
        country: String,
        pool: String,
    ): List<String> {
        synchronized(sessionFailedLock) {
            val now = System.currentTimeMillis()
            val iterator = sessionFailedMaskHosts.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > 30 * 60_000L) {
                    iterator.remove()
                }
            }
            val filtered = hosts.filterNot { host ->
                host.isNotBlank() && sessionFailedMaskHosts.containsKey(sessionFailedHostKey(country, pool, host))
            }
            return if (filtered.isNotEmpty()) filtered else hosts
        }
    }

    private fun rememberFailedHostForSession(country: String, pool: String, host: String) {
        val normalizedHost = host.trim().lowercase()
        if (normalizedHost.isBlank()) return
        synchronized(sessionFailedLock) {
            sessionFailedMaskHosts[sessionFailedHostKey(country, pool, normalizedHost)] = System.currentTimeMillis()
        }
    }

    private fun clearFailedHostForSession(country: String, pool: String, host: String) {
        val normalizedHost = host.trim().lowercase()
        if (normalizedHost.isBlank()) return
        synchronized(sessionFailedLock) {
            sessionFailedMaskHosts.remove(sessionFailedHostKey(country, pool, normalizedHost))
        }
    }

    private fun sessionFailedHostKey(country: String, pool: String, host: String): String {
        return "${country.trim().uppercase()}|${pool.trim().lowercase()}|${host.trim().lowercase()}"
    }

    private fun isLocalProxyOpen(port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(BIND_HOST, port), 300)
                socket.isConnected
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun allocateInternalProxyPort(context: Context, preferredPort: Int): Int {
        if (preferredPort in 1024..65535 && canBindLoopbackPort(preferredPort)) {
            return preferredPort
        }
        repeat(18) {
            val candidate = INTERNAL_PORT_RANGE_START + portRandom.nextInt(INTERNAL_PORT_RANGE_END - INTERNAL_PORT_RANGE_START + 1)
            if (canBindLoopbackPort(candidate)) {
                return candidate
            }
        }
        return findEphemeralLoopbackPort().takeIf { it in 1024..65535 }
            ?: context.let { LEGACY_BIND_PORT }
    }

    private fun canBindLoopbackPort(port: Int): Boolean {
        if (port !in 1024..65535) return false
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = false
                socket.bind(InetSocketAddress(BIND_HOST, port))
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun findEphemeralLoopbackPort(): Int? {
        return try {
            ServerSocket().use { socket ->
                socket.bind(InetSocketAddress(BIND_HOST, 0))
                socket.localPort
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Бинарь запускается напрямую, без оболочки.
     *
     * Раньше на 64-битных устройствах единственным способом был `SHELL`: команда
     * собиралась строкой в рантайме и отдавалась в `/system/bin/sh -c`. Работает это
     * не лучше прямого запуска, а со стороны выглядит как «приложение выполняет
     * команды оболочки» — самостоятельный признак вредоносного поведения для
     * сканеров. Путь `DIRECT` давно написан и используется на arm32; здесь он
     * становится единственным.
     */
    private fun buildLaunchModes(): List<LaunchMode> = listOf(LaunchMode.DIRECT)

    private fun startOperaProcess(
        args: List<String>,
        nativeLibDir: String,
        launchMode: LaunchMode,
    ): Process {
        val processBuilder = when (launchMode) {
            LaunchMode.DIRECT -> ProcessBuilder(args)
        }
        processBuilder.redirectErrorStream(true)
        val environment = processBuilder.environment()
        val existingLdLibraryPath = environment["LD_LIBRARY_PATH"].orEmpty().trim()
        environment["LD_LIBRARY_PATH"] = when {
            existingLdLibraryPath.isBlank() -> nativeLibDir
            existingLdLibraryPath.split(':').any { it == nativeLibDir } -> existingLdLibraryPath
            else -> "$nativeLibDir:$existingLdLibraryPath"
        }
        return processBuilder.start()
    }
}

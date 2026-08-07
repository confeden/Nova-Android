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

    private enum class LaunchMode {
        DIRECT,
        SHELL,
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
    )

    private const val BIND_HOST = "127.0.0.1"
    private const val LEGACY_BIND_PORT = 1085
    private const val INTERNAL_PORT_RANGE_START = 20080
    private const val INTERNAL_PORT_RANGE_END = 40999
    private const val DEFAULT_COUNTRY = "EU"

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

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private fun appendStartupDiagnostic(context: Context, message: String) {
        runCatching {
            val target = File(context.getExternalFilesDir(null) ?: context.filesDir, "operaproxy_diag.txt")
            target.parentFile?.mkdirs()
            target.appendText("${System.currentTimeMillis()} $message\n")
        }
    }

    private fun prepareExecutableBinary(
        context: Context,
        sourceBinary: File,
        logger: (String) -> Unit,
    ): File {
        val abiSuffix = Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "unknown" }
        val binDir = File(context.codeCacheDir ?: context.filesDir, "embedded-bin").apply { mkdirs() }
        val stagedBinary = File(binDir, "operaproxy-$abiSuffix")
        val needsRefresh =
            !stagedBinary.exists() ||
                stagedBinary.length() != sourceBinary.length() ||
                stagedBinary.lastModified() < sourceBinary.lastModified()
        if (needsRefresh) {
            sourceBinary.inputStream().use { input ->
                stagedBinary.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            stagedBinary.setReadable(true, true)
            stagedBinary.setWritable(true, true)
            stagedBinary.setExecutable(true, true)
            stagedBinary.setLastModified(sourceBinary.lastModified())
            logger("Подготовили исполняемый Opera proxy binary: ${stagedBinary.absolutePath}")
        } else if (!stagedBinary.canExecute()) {
            stagedBinary.setExecutable(true, true)
        }
        return stagedBinary
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
            val launchLogger: (String) -> Unit = { message ->
                logger(message)
                appendStartupDiagnostic(appContext, "[$requestedCountry:$bindPort] $message")
            }
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
            val launchPlans = buildList {
                val seenPlans = linkedSetOf<String>()
                fun appendPlan(host: String, endpoint: String?, apiProfile: OperaApiProfile) {
                    val normalizedHost = host.trim()
                    val normalizedEndpoint = endpoint?.trim().orEmpty()
                    val key = "${normalizedEndpoint}|${apiProfile.id}|$normalizedHost"
                    if (seenPlans.add(key)) {
                        add(OperaLaunchPlan(normalizedHost, endpoint, apiProfile))
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
                val rankedPlans = plans.withIndex()
                    .sortedWith(
                        compareByDescending<IndexedValue<OperaLaunchPlan>> { indexedPlan ->
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
                        }.thenBy { it.index }
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
            val failedEndpointOverrides = linkedSetOf<String>()
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
                if (clientData.isOperaApiProfileCoolingDown(requestedCountry, apiProfile.id)) {
                    continue
                }
                val planStartedAt = System.currentTimeMillis()
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
                val launchModes = buildLaunchModes()
                var hostSucceeded = false
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
                            timeoutMs = readyTimeoutMs,
                            logger = launchLogger,
                            shouldAbort = shouldAbort,
                            lastProgressAtMs = { lastStartupProgressAt.get() },
                        )
                    ) {
                        val selectedOrOverride = endpointOverride ?: selectedEndpoint.get()
                        val requireHttpProbe = requestedCountry == "AM" || !endpointOverride.isNullOrBlank()
                        if (requireHttpProbe && !probeLocalProxyHttpConnectivity(bindPort, 2600)) {
                            launchLogger("Кэшированный Opera endpoint $endpointOverride поднял локальный порт, но не дал HTTP-probe. Пробуем следующий endpoint.")
                            clientData.recordOperaLaunchPlanOutcome(
                                country = requestedCountry,
                                fakeSni = fakeSni,
                                endpoint = selectedOrOverride ?: endpointOverride,
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
                            stopManaged(launchLogger)
                            if (requestedCountry == "AM") {
                                return ReadyState.FAILED
                            }
                            continue
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
                clientData.recordOperaLaunchPlanOutcome(
                    country = requestedCountry,
                    fakeSni = fakeSni,
                    endpoint = endpointOverride,
                    apiProfileId = apiProfile.id,
                    success = false,
                    durationMs = System.currentTimeMillis() - planStartedAt,
                )
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
        for (apiProfile in apiProfiles) {
            val profileHosts = maskHostsForApiProfile(apiProfile, candidateHosts)
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
                logger(
                    "Opera endpoint discovery через текущую сеть/VPN: $requestedCountry " +
                        "попытка ${index + 1}/${profileHosts.size}, API=${apiProfile.label}, DNS=$bootstrapLabel" +
                        if (fakeSni.isNotBlank()) ", SNI=$fakeSni" else ", без SNI"
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
                        clientData.saveOperaPinnedEndpoints(requestedCountry, endpoints)
                        clientData.setPreferredOperaApiProfile(requestedCountry, apiProfile.id)
                        logger("Opera endpoints сохранены для $requestedCountry через ${apiProfile.label}: ${endpoints.joinToString(",")}")
                        clientData.recordTrafficMaskAttempt(
                            fakeSni.takeIf { it.isNotBlank() },
                            success = true,
                            poolHint = candidatePool,
                        )
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

    private fun probeLocalProxyHttpConnectivity(port: Int, timeoutMs: Int): Boolean {
        val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress(BIND_HOST, port))
        val probeUrls = listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://www.google.com/generate_204",
            "http://v4.ident.me",
            "http://ipv4.icanhazip.com",
            "http://api.ipify.org",
        )
        for (url in probeUrls) {
            try {
                val connection = URL(url).openConnection(proxy) as HttpURLConnection
                connection.instanceFollowRedirects = false
                connection.connectTimeout = timeoutMs
                connection.readTimeout = timeoutMs
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
     * не более чем на [PROGRESS_MAX_EXTENSION_MS], чтобы зависший запуск не держал
     * подключение бесконечно.
     */
    private fun waitUntilReady(
        process: Process,
        bindPort: Int,
        timeoutMs: Long,
        logger: (String) -> Unit,
        shouldAbort: (() -> Boolean)? = null,
        lastProgressAtMs: (() -> Long)? = null,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        val baseDeadline = startedAt + timeoutMs
        val hardDeadline = baseDeadline + PROGRESS_MAX_EXTENSION_MS
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
                            "до ${PROGRESS_MAX_EXTENSION_MS / 1000} с вместо перезапуска с нуля."
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
            Thread.sleep(400L)
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

    private fun buildLaunchModes(): List<LaunchMode> {
        val isLegacyArm32Only = Build.SUPPORTED_64_BIT_ABIS.isEmpty() &&
            Build.SUPPORTED_ABIS.any { it.contains("armeabi-v7a") || it == "armeabi" }
        return if (isLegacyArm32Only) {
            listOf(LaunchMode.DIRECT, LaunchMode.SHELL)
        } else {
            listOf(LaunchMode.SHELL)
        }
    }

    private fun startOperaProcess(
        args: List<String>,
        nativeLibDir: String,
        launchMode: LaunchMode,
    ): Process {
        val processBuilder = when (launchMode) {
            LaunchMode.DIRECT -> ProcessBuilder(args)
            LaunchMode.SHELL -> {
                val shellCommand = buildString {
                    append("exec ")
                    append(args.joinToString(" ") { shellQuote(it) })
                }
                ProcessBuilder("/system/bin/sh", "-c", shellCommand)
            }
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

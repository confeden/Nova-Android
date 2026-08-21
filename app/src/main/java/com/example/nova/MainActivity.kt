package com.example.nova

import android.app.ActivityManager
import android.app.Activity
import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import nova.Nova
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    companion object {
        /** Сколько ждать прогресс от новой фазы, прежде чем доверять состоянию сервиса. */
        private const val PROGRESS_PHASE_SWITCH_QUIET_MS = 1_500L

        /** Сколько ждать уже работающее обновление IP, прежде чем считать его зависшим. */
        private const val IP_REFRESH_RUNNING_STALE_MS = 8_000L

        /** Сколько ждать обновление IP, ещё не получившее поток из пула. */
        private const val IP_REFRESH_QUEUED_STALE_MS = 12_000L

        private const val STATE_PENDING_STATUS_TEXT = "pending_status_text"
        private const val STATE_START_FLOW_ACTIVE = "start_flow_active"
        private const val START_FLOW_TRANSIENT_PENDING_MS = 60_000L
        private const val ACTION_ADB_RESET_WARP_REGISTRATION = "RESET_WARP_REGISTRATION"
        private const val ACTION_ADB_FORCE_WARP_DIRECT_REGISTRATION_ONCE =
            "FORCE_WARP_DIRECT_REGISTRATION_ONCE"

        /**
         * Задаёт профиль VLESS и переключает выбор региона на него.
         *
         * Экрана подписок ещё нет (см. docs/vless-reality-plan.md), а транспорт уже
         * подключён к сервису — без этой точки входа его нечем проверить на устройстве.
         */
        private const val ACTION_ADB_SET_VLESS_PROFILE = "SET_VLESS_PROFILE"
        private const val EXTRA_ADB_VLESS_LINK = "vless_link"

        /**
         * Отладочный ключ для опыта по нестабильности WARP: убрать junk-пакеты AWG.
         *
         * Своего экрана у него нет намеренно — это инструмент для одного замера,
         * а не настройка, которую стоит показывать.
         */
        private const val ACTION_ADB_SET_AWG_JUNK = "SET_AWG_JUNK"

        /**
         * Выгружает сохранённый профиль MASQUE во внешнюю папку приложения.
         *
         * Отладочное действие: сравнивать наш профиль с эталонным (проба
         * `nova-core/cmd/masqueprobe`) иначе нечем — `run-as` на релизной сборке
         * недоступен, а профиль лежит в `SharedPreferences`. Приватный ключ в выгрузке
         * заменён длиной: для сверки формата этого достаточно, а ключ наружу не уходит.
         */
        private const val ACTION_ADB_DUMP_MASQUE_CONFIG = "DUMP_MASQUE_CONFIG"
        private const val ACTION_ADB_DOUBLE_ENROLL_MASQUE = "DOUBLE_ENROLL_MASQUE"

        /** Просит службу прогнать пробу MASQUE изнутри процесса `:vpn`. */
        private const val ACTION_ADB_PROBE_MASQUE = "PROBE_MASQUE"

        /**
         * Кладёт готовые token/device id в запасную личность.
         *
         * Отладочное действие ради одного опыта: подсунуть приложению устройство,
         * зарегистрированное эталонной пробой, и посмотреть, заработает ли MASQUE.
         * Так вина регистрации отделяется от вины всего остального.
         */
        private const val ACTION_ADB_SET_WARP_IDENTITY = "SET_WARP_IDENTITY"
        private const val EXTRA_ADB_WARP_TOKEN = "warp_token"
        private const val EXTRA_ADB_WARP_DEVICE_ID = "warp_device_id"
        private const val EXTRA_ADB_AWG_JUNK_DISABLED = "junk_disabled"

        /** Как часто проверять, умер ли обречённый `:vpn`. */
        private const val DOOMED_RESTART_POLL_MS = 150L

        /**
         * Сколько всего ждать смерть обречённого `:vpn`.
         *
         * Фитиль библиотеки — две секунды; на устройстве от `tun2proxy_stop` до
         * `Process ... has died` прошло 2.26 с. Запас взят с четырёхкратным
         * перекрытием: лучше подождать лишнее, чем поднять сессию под `exit(-1)`.
         */
        private const val DOOMED_RESTART_WAIT_MS = 8_000L
    }

    private data class TraceInfo(
        val ip: String,
        val country: String,
        val colo: String,
    )

    private data class IpSnapshot(
        val ipv4: String,
        val ipv6: String,
        val country: String,
        val colo: String,
    )

    private enum class BackdropState {
        STOPPED,
        CONNECTING,
        CONNECTED,
    }

    private lateinit var tvIpAddress: TextView
    private lateinit var tvCountryBadge: TextView
    private lateinit var tvAttemptProgress: TextView
    private lateinit var tvTransportNotice: TextView
    private lateinit var restrictedMobileDots: SlidingDotsIndicatorView
    private lateinit var tvStatus: com.example.nova.StrokeTextView
    private lateinit var btnConnect: GlowPillButton
    private lateinit var btnNextProfile: GlowPillButton
    private lateinit var btnInstallUpdate: com.example.nova.UpdateChipView
    private lateinit var tvUpdateCaption: TextView
    private lateinit var latencyGraph: LatencyGraphView
    private lateinit var ivBackgroundArt: BackdropRevealImageView
    private lateinit var networkBackground: NovaNetworkBackgroundView
    private lateinit var tronBackdrop: TronRingsView
    private lateinit var tvVersion: TextView
    
    private lateinit var clientData: ClientData
    private var vpnState = NovaVpnService.STATE_STOPPED
    private var isActivityResumed = false
    private var warpDiscoverySnapshot: WarpDiscoverySnapshot? = null
    private var lastRenderedDiscoveryRunning = false

    private var isIpVisible = false
    private var currentIpv4 = "..."
    private var currentIpv6 = "..."
    private var currentCountry = "--"
    private var currentTunnelBackend = NovaVpnService.BACKEND_WARP
    private var currentAttemptOrdinal = 0
    private var currentAttemptTotal = 0
    private var displayedAttemptOrdinal = 0
    private var displayedAttemptTotal = 0
    private var lastRawAttemptOrdinal = 0
    private var lastRawAttemptTotal = 0
    private var manualProfileSwitchProgressHoldUntilMs = 0L
    private var primaryActionLockedUntilMs = 0L
    private var primaryActionPreviewActive = false
    private var pendingVpnPermissionFlowGeneration: Int? = null
    private var tunnelIpResolved = false
    private var connectedUiAwaitingProof = false
    private var backdropState = BackdropState.STOPPED
    private var missingVpnSinceMs = 0L
    private var manualStopUiSuppressedUntilMs = 0L
    private var lastForegroundHealthRecheckAtMs = 0L

    /**
     * Последний явный запуск, отправленный службе. Нужен, чтобы повторить его в свежем
     * процессе, когда `:vpn` объявил себя обречённым после `tun2proxy_stop`.
     */
    private var lastExplicitStartIntent: Intent? = null
    private var doomedRestartArmed = false
    private var doomedRestartDeadlineMs = 0L

    /**
     * Дошёл ли текущий пуск до службы.
     *
     * Отделяет «цикл готовит конфигурацию» от «служба уже получила intent». До
     * передачи любое STOPPED — хвост предыдущей сессии, и отменять им новый пуск
     * нельзя.
     */
    private var startFlowHandedToService = false
    private var firstLaunchAutoConnectTriggered = false
    private var backgroundRevealAnimator: Animator? = null
    private val lowEndUiAnimationDevice by lazy(LazyThreadSafetyMode.NONE) {
        val am = getSystemService(ActivityManager::class.java)
        val lowRam = am?.isLowRamDevice ?: false
        lowRam || Build.VERSION.SDK_INT <= Build.VERSION_CODES.P || Runtime.getRuntime().availableProcessors() <= 4 || !android.os.Process.is64Bit()
    }
    private val ipResetHandler = Handler(Looper.getMainLooper())
    private val ipResetRunnable = Runnable { 
        isIpVisible = false
        updateIpDisplay()
    }
    private var startFlowExecutor = Executors.newSingleThreadExecutor()
    private val ipExecutor = Executors.newFixedThreadPool(3)
    private val latencyExecutor = Executors.newFixedThreadPool(2)
    private val operaProxyHttpClientBase by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }
    private val ipRefreshInFlight = AtomicBoolean(false)
    private val ipRefreshGeneration = AtomicInteger(0)
    private val latencyRefreshInFlight = AtomicBoolean(false)
    private val latencyRefreshGeneration = AtomicInteger(0)
    @Volatile
    private var ipRefreshStartedAtMs = 0L

    /**
     * Момент постановки обновления IP в очередь.
     *
     * Отдельно от [ipRefreshStartedAtMs], потому что в пуле три потока, а вызов к
     * сервису ожидания IP занимает до шести секунд: задача может ждать своей очереди
     * дольше, чем работать. Пока это не различалось, ожидающая задача считалась
     * зависшей, вместо неё ставилась ещё одна — и очередь росла сама от себя. В логе
     * это выглядело как бесконечное «прерываем зависший IP refresh», а на экране —
     * как застрявшее «ПОДКЛЮЧЕНИЕ...».
     */
    /** Транспорт, под который сейчас показан счётчик попыток. */
    private var lastSeenServiceTransport = ""
    private var lastSeenServiceBackend = ""

    /** Момент смены фазы: пока он свеж, числа прошлой фазы не показываем. */
    private var progressPhaseSwitchAtMs = 0L
    @Volatile
    private var ipRefreshQueuedAtMs = 0L
    @Volatile
    private var latencyRefreshStartedAtMs = 0L
    private var lastLatencyRefreshAtMs = 0L
    private var lastTunnelConnectedAtMs = 0L
    private var lastMeasuredLatencyMs = -1

    private data class ObservedIpCandidate(
        var value: String = "",
        var seenCount: Int = 0,
    )

    private val ipv4Candidate = ObservedIpCandidate()
    private val ipv6Candidate = ObservedIpCandidate()

    private var usePrimaryLatencyServer = true
    private var vpnNetwork: Network? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var isStartFlowActive = false
    @Volatile
    private var pendingStatusText = "ПОДКЛЮЧЕНИЕ..."
    private val startFlowGeneration = AtomicInteger(0)
    private var lastDeadConnectingRecoveryAtMs = 0L

    private val statusHandler = Handler(Looper.getMainLooper())
    private val primaryActionUnlockRunnable = Runnable { applyPrimaryActionInterlock() }
    private val deferredNotificationPermissionRunnable = Runnable {
        if (!isActivityResumed) return@Runnable
        maybeRequestNotificationPermission()
    }
    private val statusRunnable = object : Runnable {
        override fun run() {
            if (isActivityResumed) {
                reconcileSystemVpnStateIfNeeded()
                if (recoverDeadConnectingStateIfNeeded()) {
                    statusHandler.postDelayed(this, 2000)
                    return
                }
                if (shouldDropStaleConnectingState()) {
                    markServiceStoppedLocally()
                    currentTunnelBackend = NovaVpnService.BACKEND_WARP
                    restoreDirectUiSnapshot()
                    updateUiByState(NovaVpnService.STATE_STOPPED)
                }
                syncUiFromPersistedServiceState()
                validateConnectedTunnelState()
                if (vpnState != NovaVpnService.STATE_CONNECTING) {
                    checkCurrentIp()
                    measureLatency()
                }
                val nextDelayMs =
                    if (
                        vpnState == NovaVpnService.STATE_CONNECTING ||
                        clientData.getServiceState() == NovaVpnService.STATE_CONNECTING ||
                        isStartFlowActive
                    ) {
                        1000L
                    } else {
                        2000L
                    }
                statusHandler.postDelayed(this, nextDelayMs)
            }
        }
    }

    private val vpnStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == NovaVpnService.ACTION_VPN_STATE) {
                if (intent.getBooleanExtra(NovaVpnService.EXTRA_TILE_REFRESH_ONLY, false)) {
                    return
                }
                val state = intent.getStringExtra(NovaVpnService.EXTRA_STATE)
                val backend = intent.getStringExtra(NovaVpnService.EXTRA_BACKEND)
                val incomingAttemptOrdinal = intent.getIntExtra(NovaVpnService.EXTRA_ATTEMPT_ORDINAL, 0)
                val incomingAttemptTotal = intent.getIntExtra(NovaVpnService.EXTRA_ATTEMPT_TOTAL, 0)
                if (
                    state == NovaVpnService.STATE_CONNECTING &&
                    isManualProfileSwitchProgressHeld()
                ) {
                    currentAttemptOrdinal = currentAttemptOrdinal.coerceAtMost(currentAttemptTotal)
                } else {
                    currentAttemptOrdinal = incomingAttemptOrdinal
                    currentAttemptTotal = incomingAttemptTotal
                }
                if (!backend.isNullOrBlank()) {
                    currentTunnelBackend = resolveUiBackend(backend)
                }
                if (
                    state != NovaVpnService.STATE_STOPPED &&
                    isManualStopUiSuppressed() &&
                    !clientData.isSoftReapplyPending() &&
                    !clientData.isTransientConnectingPending() &&
                    findCurrentVpnNetwork() == null
                ) {
                    LogManager.log("Игнорируем stale-состояние $state после явного отключения из UI.")
                    return
                }
                val effectiveState = if (state == NovaVpnService.STATE_STOPPED && 
                    (clientData.isSoftReapplyPending() || clientData.isTransientConnectingPending())) {
                    NovaVpnService.STATE_CONNECTING
                } else {
                    state
                }
                updateUiByState(effectiveState)
                updateAttemptProgressDisplay()
            }
        }
    }

    /**
     * Служба сообщает, что процесс `:vpn` обречён: в её остановке звали `tun2proxy_stop`,
     * и библиотека через две секунды выполнит `exit(-1)`. Нажатый «Пуск» в этом процессе
     * уже не поднимут, а перезапуск средствами Android приходит с его собственной
     * задержкой — на устройстве было 51 секунда. Повторяем запуск сами, как только
     * обречённый процесс действительно умрёт.
     */
    private val vpnProcessDoomedReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != NovaVpnService.ACTION_VPN_PROCESS_DOOMED) return
            armDoomedProcessRestart()
        }
    }

    private val doomedRestartRunnable = object : Runnable {
        override fun run() {
            if (!doomedRestartArmed) return
            if (isVpnProcessAlive()) {
                if (SystemClock.elapsedRealtime() >= doomedRestartDeadlineMs) {
                    doomedRestartArmed = false
                    LogManager.log(
                        "Обречённый процесс :vpn не умер за ${DOOMED_RESTART_WAIT_MS} мс. " +
                            "Свой перезапуск отменяем: поднимать сессию поверх горящего " +
                            "фитиля — это ровно тот убитый туннель, от которого уходим."
                    )
                    return
                }
                statusHandler.postDelayed(this, DOOMED_RESTART_POLL_MS)
                return
            }
            doomedRestartArmed = false
            val pending = lastExplicitStartIntent
            if (pending == null) {
                LogManager.log("Процесс :vpn умер, но повторять нечего: явного запуска не сохранено.")
                return
            }
            LogManager.log(
                "Обречённый процесс :vpn умер. Повторяем запуск сами: " +
                    "${pending.action ?: "WARP"}."
            )
            runCatching {
                ContextCompat.startForegroundService(this@MainActivity, Intent(pending))
            }.onFailure {
                LogManager.log(
                    "Повторить запуск после смерти :vpn не удалось: ${it.message}. " +
                        "Остаётся перезапуск средствами Android с его задержкой."
                )
            }
        }
    }

    private val warpDiscoveryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action != NovaVpnService.ACTION_WARP_CONFIG_DISCOVERY) return
            val previousSnapshot = warpDiscoverySnapshot
            warpDiscoverySnapshot = WarpDiscoverySnapshot(
                running = intent.getBooleanExtra(NovaVpnService.EXTRA_DISCOVERY_RUNNING, false),
                foundCount = intent.getIntExtra(
                    NovaVpnService.EXTRA_DISCOVERY_FOUND_COUNT,
                    previousSnapshot?.foundCount ?: 0,
                ),
                message = intent.getStringExtra(NovaVpnService.EXTRA_DISCOVERY_MESSAGE)
                    .orEmpty()
                    .ifBlank { previousSnapshot?.message.orEmpty() },
                ordinal = intent.getIntExtra(
                    NovaVpnService.EXTRA_ATTEMPT_ORDINAL,
                    previousSnapshot?.ordinal ?: clientData.getServiceAttemptOrdinal(),
                ).coerceAtLeast(0),
                total = intent.getIntExtra(
                    NovaVpnService.EXTRA_ATTEMPT_TOTAL,
                    previousSnapshot?.total ?: clientData.getServiceAttemptTotal(),
                ).coerceAtLeast(0),
                observedAt = System.currentTimeMillis(),
            )
            updateUiByState(clientData.getServiceState())
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        pendingVpnPermissionFlowGeneration = null
        if (result.resultCode == Activity.RESULT_OK) {
            LogManager.log("Системное разрешение на VPN подтверждено. Возобновляем подключение Nova.")
            currentTunnelBackend = resolvePendingConnectBackend()
            val flowGeneration = beginStartFlow(buildConnectingStatusText())
            lockPrimaryActionFor(900L)
            registerAndStart(flowGeneration)
        } else {
            LogManager.log("Системное разрешение на VPN не подтверждено. Подключение Nova отменено.")
            cancelStartFlow()
            markServiceStoppedLocally()
            renderVpnPermissionRequiredState()
            Toast.makeText(
                this,
                "Разреши VPN в системном окне и попробуй снова",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        clientData.setPromptedNotificationPermission(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Диагностическая сборка уводит на экран самодиагностики: на устройстве,
        // где главный экран не открывается, дальше идти незачем. В обычных
        // сборках DIAGNOSTICS равен false и ветка вырезается компилятором.
        if (BuildConfig.DIAGNOSTICS) {
            startActivity(android.content.Intent(this, DiagnosticsActivity::class.java))
            finish()
            return
        }
        LogManager.setAppContext(this)
        clientData = ClientData(this)
        // Обновление приложения не должно приносить в новую версию выученное старой:
        // проверка стоит до первого чтения состояния, иначе снимок успел бы взять
        // рейтинги и последний режим прошлой версии.
        clientData.resetLearnedStateAfterUpdate()
        warpDiscoverySnapshot = clientData.getWarpDiscoverySnapshot()
        if (maybeHandleAdbResetIntent(intent)) return
        
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        
        setContentView(R.layout.activity_main)

        ivBackgroundArt = findViewById(R.id.iv_background_art)
        networkBackground = findViewById(R.id.nova_background_animation)
        tronBackdrop = findViewById(R.id.tron_backdrop)
        tronBackdrop.setYogurtIndigoEnabled(mainBackgroundMode() == MainBackgroundPolicy.MODE_ANIMATION)
        tvIpAddress = findViewById(R.id.tv_ip_address)
        tvCountryBadge = findViewById(R.id.tv_country_badge)
        tvAttemptProgress = findViewById(R.id.tvAttemptProgress)
        tvTransportNotice = findViewById(R.id.tv_transport_notice)
        restrictedMobileDots = findViewById(R.id.restricted_mobile_dots)
        tvStatus = findViewById(R.id.tvStatus)
        btnConnect = findViewById(R.id.btnConnect)
        btnNextProfile = findViewById(R.id.btnNextProfile)
        btnNextProfile.visibility = View.GONE
        btnNextProfile.setOnClickListener {
            // Кнопка одна на все протоколы, а списки у них разные. При выбранном VLESS
            // машинерия WARP не подходит вовсе: там профиль опознаётся парой
            // «режим + endpoint», а здесь узел задаётся целиком ссылкой — раньше
            // кнопка уходила в ветку WARP и отвечала «нет следующей WARP-конфигурации».
            if (clientData.shouldUseVlessTransport()) {
                switchToNextVlessProfile()
                return@setOnClickListener
            }
            val importedOnly = clientData.isImportedWarpOnlyModeEnabled()
            // Кнопка ведёт по всей цепочке, а не по одному списку.
            //
            // Раньше следующий индекс брался как `(current + 1) % configs.size`, то
            // есть на пятидесятом встроенном профиле перебор заворачивался на первый
            // и до EU, US и MASQUE не доходил никогда. А когда транспортом был уже
            // MASQUE или Opera, кнопка всё равно уходила в ветку WARP и начинала
            // подбор заново — снаружи «кнопка не переключает, а перезапускает».
            val activeTransport = clientData.getServiceTransport()
            val activeBackend = clientData.getServiceBackend().uppercase(Locale.ROOT)
            val nextChainStep = when {
                activeTransport == NovaVpnService.TRANSPORT_MASQUE ->
                    if (importedOnly) NovaVpnService.MANUAL_STEP_VLESS else NovaVpnService.MANUAL_STEP_OPERA_EU
                activeTransport == NovaVpnService.TRANSPORT_OPERA ->
                    if (activeBackend.endsWith("US")) null else NovaVpnService.MANUAL_STEP_OPERA_US
                else -> null
            }
            if (nextChainStep != null) {
                startManualTransportStep(nextChainStep)
                return@setOnClickListener
            }
            val configs = clientData.getWarpVerifiedMergedConfigs()
                .filter { config ->
                    !config.manual &&
                        if (importedOnly) {
                            config.userImported
                        } else {
                            !config.userImported && clientData.isBundledSeed(config)
                        }
                }
                .let { filteredConfigs ->
                    if (importedOnly) {
                        filteredConfigs
                    } else {
                        filteredConfigs.sortedWith(
                            compareByDescending<WarpVerifiedConfig> { it.promotedAt }
                                .thenBy { config ->
                                    if (config.seedOrder == Int.MAX_VALUE) Int.MAX_VALUE else config.seedOrder
                                }
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
                    }
                }
            if (configs.isNotEmpty()) {
                fun normalizeEndpointHost(value: String?): String =
                    value
                        ?.trim()
                        ?.removePrefix("[")
                        ?.removeSuffix("]")
                        .orEmpty()

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
                val cursorSource = when {
                    stableFresh -> "stable"
                    warpFresh -> "warp"
                    genericLooksWarp -> "generic"
                    else -> "promoted"
                }
                val currentHost = when (cursorSource) {
                    "stable" -> normalizeEndpointHost(clientData.getStableLastSuccessEndpoint())
                    "warp" -> normalizeEndpointHost(clientData.getWarpLastSuccessEndpoint())
                    "generic" -> normalizeEndpointHost(clientData.getLastSuccessEndpoint())
                    else -> ""
                }
                val currentPort = when (cursorSource) {
                    "stable" -> clientData.getStableLastSuccessPort()
                    "warp" -> clientData.getWarpLastSuccessPort()
                    "generic" -> clientData.getLastSuccessPort()
                    else -> -1
                }
                val currentMode = when (cursorSource) {
                    "stable" -> clientData.getStableLastSuccessMode().orEmpty().trim()
                    "warp" -> clientData.getWarpLastSuccessMode().orEmpty().trim()
                    "generic" -> genericMode
                    else -> ""
                }
                val exactCurrentIndex = configs.indexOfFirst { config ->
                    config.host.equals(currentHost, ignoreCase = true) &&
                        (currentPort !in 1..65535 || config.port == currentPort) &&
                        (currentMode.isBlank() || config.mode.equals(currentMode, ignoreCase = true))
                }.takeIf { it >= 0 } ?: -1
                val endpointCurrentIndex = configs.indexOfFirst { config ->
                    currentHost.isNotBlank() &&
                        config.host.equals(currentHost, ignoreCase = true) &&
                        (currentPort !in 1..65535 || config.port == currentPort)
                }.takeIf { it >= 0 } ?: -1
                val promotedCurrentIndex = configs
                    .withIndex()
                    .filter { it.value.promotedAt > 0L }
                    .maxByOrNull { it.value.promotedAt }
                    ?.index
                    ?: -1
                fun visibleOrdinalIndex(ordinal: Int, total: Int): Int =
                    if (ordinal in 1..configs.size && total == configs.size) {
                        ordinal - 1
                    } else {
                        -1
                    }

                val serviceAttemptOrdinal = clientData.getServiceAttemptOrdinal()
                val serviceAttemptTotal = clientData.getServiceAttemptTotal()
                val progressCurrentIndex = listOf(
                    visibleOrdinalIndex(displayedAttemptOrdinal, displayedAttemptTotal),
                    visibleOrdinalIndex(currentAttemptOrdinal, currentAttemptTotal),
                    visibleOrdinalIndex(serviceAttemptOrdinal, serviceAttemptTotal),
                )
                    .firstOrNull { it >= 0 }
                    ?: -1
                // The user-visible ordinal (the "X" in "X/50") is the authoritative
                // cursor for manual next-profile switching. Derive the current list
                // index from it so the counter advances 1 -> 2 -> 3 sequentially
                // instead of jumping (e.g. to 48/50) due to fragile endpoint/host
                // matching or a desynced service attempt ordinal.
                val visibleCursorOrdinal = displayedAttemptOrdinal
                    .coerceAtLeast(1)
                    .coerceAtMost(configs.size)
                // После Opera US цепочка заходит на второй круг, и счётчик на экране
                // считает попытки Opera, а не встроенные профили. Брать его как курсор
                // по списку WARP нельзя — начинаем список с начала.
                val restartListFromStart = activeTransport == NovaVpnService.TRANSPORT_OPERA
                val currentIndex = if (restartListFromStart) -1 else visibleCursorOrdinal - 1
                val currentIndexSource = if (restartListFromStart) "chain-wrap" else "visible"
                val chainStepAfterList = when {
                    currentIndex < configs.size - 1 -> null
                    importedOnly ->
                        NovaVpnService.MANUAL_STEP_VLESS.takeIf {
                            clientData.getVlessProfileLinks().isNotEmpty()
                        }
                    else -> NovaVpnService.MANUAL_STEP_MASQUE
                }
                if (chainStepAfterList != null) {
                    LogManager.log(
                        "UI next-profile: список из ${configs.size} профилей пройден, " +
                            "переходим к следующему транспорту ($chainStepAfterList)."
                    )
                    startManualTransportStep(chainStepAfterList)
                    return@setOnClickListener
                }
                val nextIndex = (currentIndex + 1) % configs.size
                LogManager.log(
                    "DIAG next-profile: displayedAttemptOrdinal=$displayedAttemptOrdinal " +
                        "configs.size=${configs.size} visibleCursor=$visibleCursorOrdinal " +
                        "currentIndex=$currentIndex nextIndex=$nextIndex " +
                        "serviceOrdinal=${clientData.getServiceAttemptOrdinal()}"
                )
                val nextConfig = configs.getOrNull(nextIndex)
                if (nextConfig != null) {
                    val nextOrdinal = nextIndex + 1
                    val nextTotal = configs.size
                    clientData.promoteWarpVerifiedConfig(nextConfig.id)
                    currentAttemptOrdinal = nextOrdinal
                    currentAttemptTotal = nextTotal
                    displayedAttemptOrdinal = nextOrdinal
                    displayedAttemptTotal = nextTotal
                    lastRawAttemptOrdinal = nextOrdinal
                    lastRawAttemptTotal = nextTotal
                    manualProfileSwitchProgressHoldUntilMs = SystemClock.elapsedRealtime() + 25_000L
                    LogManager.log(
                        "UI WARP next-profile: source=$cursorSource/$currentIndexSource last=$currentMode@$currentHost:$currentPort " +
                            "exact=${exactCurrentIndex + 1} hostPort=${endpointCurrentIndex + 1} " +
                            "progress=${progressCurrentIndex + 1} promoted=${promotedCurrentIndex + 1}, " +
                            "current #${currentIndex + 1}/$nextTotal, " +
                            "selected #$nextOrdinal/$nextTotal ${nextConfig.mode}@${nextConfig.host}:${nextConfig.port}"
                    )
                    clientData.saveServiceState(
                        NovaVpnService.STATE_CONNECTING,
                        NovaVpnService.BACKEND_WARP,
                        nextOrdinal,
                        nextTotal,
                    )
                    updateUiByState(NovaVpnService.STATE_CONNECTING)
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, NovaVpnService::class.java).apply {
                            action = NovaVpnService.ACTION_REAPPLY_CURRENT_SESSION
                            putExtra(NovaVpnService.EXTRA_ATTEMPT_ORDINAL, nextOrdinal)
                            putExtra(NovaVpnService.EXTRA_ATTEMPT_TOTAL, nextTotal)
                            putExtra(NovaVpnService.EXTRA_MANUAL_WARP_PROFILE_MODE, nextConfig.mode)
                            putExtra(NovaVpnService.EXTRA_MANUAL_WARP_PROFILE_HOST, nextConfig.host)
                            putExtra(NovaVpnService.EXTRA_MANUAL_WARP_PROFILE_PORT, nextConfig.port)
                        }
                    )
                } else {
                    Toast.makeText(this, "Нет следующей WARP-конфигурации", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(
                    this,
                    if (importedOnly) {
                        "Нет импортированных WARP-конфигураций"
                    } else {
                        "Нет встроенных WARP-конфигураций"
                    },
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
        btnInstallUpdate = findViewById(R.id.btn_install_update)
        tvUpdateCaption = findViewById(R.id.tv_update_caption)
        latencyGraph = findViewById(R.id.graph_latency)
        tvVersion = findViewById(R.id.tv_version)
        tvIpAddress.setSaveEnabled(false)
        tvCountryBadge.setSaveEnabled(false)
        
        currentTunnelBackend = clientData.getServiceBackend()
        if (savedInstanceState != null) {
            pendingStatusText = savedInstanceState.getString(STATE_PENDING_STATUS_TEXT, pendingStatusText)
        }
        if (savedInstanceState?.getBoolean(STATE_START_FLOW_ACTIVE, false) == true) {
            isStartFlowActive = true
        }
        tvVersion.text = "v${getAppVersionName()}"
        // Экран открыт — уведомление «Nova обновлена, открыть» больше не нужно,
        // независимо от того, сам он открылся или его открыл человек.
        AppUpdateManager.cancelUpdatedNotification(this)
        setTaskDescription(
            ActivityManager.TaskDescription(
                getString(R.string.app_name),
                BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher),
                Color.parseColor("#04070D")
            )
        )
        NovaFontHelper.apply(findViewById(android.R.id.content))
        if (mainBackgroundMode() == MainBackgroundPolicy.MODE_IMAGE) {
            loadBackdropArtSafely()
        } else {
            ivBackgroundArt.setImageDrawable(null)
        }
        ivBackgroundArt.visibility = View.GONE
        ivBackgroundArt.alpha = 0f
        ivBackgroundArt.revealProgress = 0f
        networkBackground.visibility = View.GONE
        networkBackground.alpha = 0f
        tronBackdrop.visibility = View.VISIBLE
        tronBackdrop.alpha = 1f
        val isFirstAppLaunch = clientData.getIsFirstLaunch()
        Thread {
            runCatching {
                VendorBackgroundSettingsHelper.primeCache(applicationContext)
            }
            runCatching {
                // Версия ядра Xray заодно показывает, подгрузилась ли нативная
                // библиотека на этой архитектуре — без неё доступен только WARP.
                val version = XrayBridge.version()
                if (version.isNotBlank()) {
                    LogManager.i("Ядро Xray: $version")
                } else {
                    LogManager.i("Ядро Xray недоступно: ${XrayBridge.lastLoadError()}")
                }
            }
        }.start()
        AppCacheManager.prewarmAsync(this)
        AppUpdateManager.syncSchedule(this)
        AppUpdateManager.enqueueImmediateCheck(this, reason = "app-launch")
        // Расписание переживает перезагрузку, но не переустановку и не «очистить
        // данные»: восстанавливаем его на каждом запуске, раз подписка сохранена.
        VlessSubscriptionManager.syncSchedule(this)
        refreshInstallUpdateButton()
        btnConnect.setPillStyle(
            fillColor = Color.argb(78, 220, 208, 255),
            glowColor = Color.argb(188, 220, 208, 255),
            highlightColor = Color.argb(78, 220, 208, 255),
            insetX = 12f,
            insetY = 10f,
            blurRadius = 9f,
        )
        btnConnect.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (SystemClock.elapsedRealtime() >= primaryActionLockedUntilMs) {
                        showPrimaryActionPreview()
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    clearPrimaryActionPreview()
                }
            }
            false
        }

        btnConnect.setOnClickListener { 
            primaryActionPreviewActive = false
            btnConnect.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            if (SystemClock.elapsedRealtime() < primaryActionLockedUntilMs) return@setOnClickListener
            if (shouldTreatPrimaryActionAsStop()) {
                lockPrimaryActionFor(350L)
                btnConnect.text = if (isWarpDiscoveryActive()) "ОСТАНОВКА..." else "ОТКЛЮЧЕНИЕ..."
                applyPrimaryActionInterlock()
                stopVpn()
            } else {
                startConnectFromPrimaryAction()
            }
        }

        btnInstallUpdate.setOnClickListener {
            if (AppUpdateManager.getReadyDownloadedVersion(this).isNotBlank()) {
                AppUpdateManager.installReadyUpdate(this)
                return@setOnClickListener
            }
            // Загрузку начинает это нажатие — и только оно.
            if (AppUpdateManager.startUserRequestedDownload(this)) {
                Toast.makeText(this, "Скачиваем обновление", Toast.LENGTH_SHORT).show()
                refreshInstallUpdateButton()
            } else {
                Toast.makeText(this, "Не удалось начать загрузку обновления", Toast.LENGTH_SHORT).show()
            }
        }
        // Экран рисуется под системными панелями, а плашка прижата к верхнему краю:
        // без отступа она уезжала под часы и заряд и читалась как мусор поверх статус-бара.
        // Высота панели разная на разных устройствах, поэтому берём её из insets, а не
        // из константы.
        val updateChipBaseMarginTop =
            (btnInstallUpdate.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(btnInstallUpdate) { view, insets ->
            val systemBars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            val params = view.layoutParams as ViewGroup.MarginLayoutParams
            params.topMargin = updateChipBaseMarginTop + systemBars.top
            params.rightMargin = params.rightMargin.coerceAtLeast(systemBars.right)
            view.layoutParams = params
            insets
        }

        val btnSettings = findViewById<TextView>(R.id.btn_settings)
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        TvFocusHelper.install(
            this,
            btnConnect,
            btnInstallUpdate,
            btnSettings,
        )

        val initialState = clientData.getServiceState()
        val startupState = resolveStartupState(initialState)
        if (startupState == NovaVpnService.STATE_CONNECTED) {
            restoreCachedTunnelSnapshot()
        } else {
            restoreDirectUiSnapshot()
        }
        updateUiByState(startupState)
        setupIpInteractions()
        maybeHandleAutomationIntent(intent)
        
        if (isFirstAppLaunch) {
            clientData.setIsFirstLaunch(false)
            triggerFirstLaunchAutoConnect()
            scheduleDeferredNotificationPermissionRequest()
        } else {
            maybeRequestNotificationPermission()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_PENDING_STATUS_TEXT, pendingStatusText)
        outState.putBoolean(STATE_START_FLOW_ACTIVE, isStartFlowActive || clientData.isTransientConnectingPending())
        super.onSaveInstanceState(outState)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateUiByState(clientData.getServiceState())
    }

    private fun getAppVersionName(): String {
        return try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.12"
        } catch (_: Exception) {
            "1.12"
        }
    }

    private fun loadBackdropArtSafely() {
        val metrics = resources.displayMetrics
        val targetWidth = metrics.widthPixels.coerceAtLeast(1)
        val targetHeight = metrics.heightPixels.coerceAtLeast(1)
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        BitmapFactory.decodeResource(resources, R.drawable.background, boundsOptions)
        val targetScale = if (lowEndUiAnimationDevice || Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) 1.0 else 1.35
        val sampleSize = computeBackdropSampleSize(
            sourceWidth = boundsOptions.outWidth.coerceAtLeast(1),
            sourceHeight = boundsOptions.outHeight.coerceAtLeast(1),
            targetWidth = (targetWidth * targetScale).toInt().coerceAtLeast(targetWidth),
            targetHeight = (targetHeight * targetScale).toInt().coerceAtLeast(targetHeight),
        )
        val decodeOptions = BitmapFactory.Options().apply {
            inScaled = false
            inSampleSize = sampleSize
            inPreferredConfig = if (lowEndUiAnimationDevice || Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                Bitmap.Config.RGB_565
            } else {
                Bitmap.Config.ARGB_8888
            }
            inDither = inPreferredConfig == Bitmap.Config.RGB_565
        }
        val bitmap = runCatching {
            BitmapFactory.decodeResource(resources, R.drawable.background, decodeOptions)
        }.getOrNull()
        if (bitmap != null) {
            ivBackgroundArt.setImageBitmap(bitmap)
            LogManager.log(
                "Фон загружен безопасно: ${bitmap.width}x${bitmap.height}, sampleSize=$sampleSize, config=${bitmap.config}"
            )
        } else {
            ivBackgroundArt.setImageDrawable(null)
            LogManager.log("Не удалось загрузить фон безопасно. Оставляем однотонный фон.")
        }
    }

    private fun mainBackgroundMode(): String {
        return MainBackgroundPolicy.effectiveMode(this, clientData.getMainBackgroundMode())
    }

    private fun ensureBackdropImageLoaded() {
        if (ivBackgroundArt.drawable == null) {
            loadBackdropArtSafely()
        }
    }

    private fun showNetworkBackground() {
        networkBackground.visibility = View.VISIBLE
        networkBackground.alpha = 1f
        networkBackground.startAnimation()
    }

    private fun hideNetworkBackground() {
        networkBackground.stopAnimation()
        networkBackground.visibility = View.GONE
        networkBackground.alpha = 0f
    }

    private fun computeBackdropSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): Int {
        var sampleSize = 1
        while (
            sourceWidth / sampleSize > targetWidth * 2 ||
                sourceHeight / sampleSize > targetHeight * 2
        ) {
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }

    private fun maybeRequestNotificationPermission() {
        if (!canRequestNotificationPermissionNow()) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun scheduleDeferredNotificationPermissionRequest(delayMs: Long = 1200L) {
        if (!canRequestNotificationPermissionNow()) return
        statusHandler.removeCallbacks(deferredNotificationPermissionRunnable)
        statusHandler.postDelayed(deferredNotificationPermissionRunnable, delayMs)
    }

    private fun canRequestNotificationPermissionNow(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (!clientData.getAutoAppUpdate()) return false
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return false
        if (clientData.hasPromptedNotificationPermission()) return false
        return true
    }

    private fun triggerFirstLaunchAutoConnect() {
        if (firstLaunchAutoConnectTriggered) return
        firstLaunchAutoConnectTriggered = true
        btnConnect.postDelayed({
            if (!isActivityResumed) return@postDelayed
            if (
                isStartFlowActive ||
                isTunnelConnected() ||
                clientData.getServiceState() != NovaVpnService.STATE_STOPPED
            ) {
                return@postDelayed
            }
            startConnectFromPrimaryAction()
        }, 700)
    }

    private fun startConnectFromPrimaryAction() {
        currentTunnelBackend = resolvePendingConnectBackend()
        val flowGeneration = beginStartFlow(buildConnectingStatusText())
        lockPrimaryActionFor(900L)
        prepareVpn(flowGeneration)
    }
    
    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        refreshWarpDiscoverySnapshotFromStorage()
        statusHandler.post(statusRunnable)
        refreshInstallUpdateButton()
        AppUpdateManager.resumePendingInstallIfAllowed(this)
        currentTunnelBackend = resolveUiBackend(clientData.getServiceBackend())
        reconcileSystemVpnStateIfNeeded()
        val resumedState = clientData.getServiceState()
        if (resumedState == NovaVpnService.STATE_CONNECTING && recoverDeadConnectingStateIfNeeded()) {
            currentTunnelBackend = resolveUiBackend(clientData.getServiceBackend())
            updateUiByState(NovaVpnService.STATE_CONNECTING)
            return
        }
        if (resumedState == NovaVpnService.STATE_CONNECTING && shouldDropStaleConnectingState()) {
            markServiceStoppedLocally()
            currentTunnelBackend = NovaVpnService.BACKEND_WARP
            restoreDirectUiSnapshot()
            updateUiByState(NovaVpnService.STATE_STOPPED)
            return
        }
        if (resumedState == NovaVpnService.STATE_CONNECTED) {
            restoreCachedTunnelSnapshot()
        } else if (resumedState == NovaVpnService.STATE_STOPPED) {
            restoreDirectUiSnapshot()
        }
        updateUiByState(resumedState)
        prewarmRestrictedMobileDetectionIfNeeded(resumedState)
        if (resumedState == NovaVpnService.STATE_CONNECTED || isTunnelConnected()) {
            requestImmediateVpnHealthRecheck(
                minIntervalMs = 5_000L,
                reason = "foreground-resume",
            )
            checkCurrentIp()
            measureLatency()
        }
    }

    override fun onPause() {
        super.onPause()
        isActivityResumed = false
        refreshKeepScreenAwake()
        statusHandler.removeCallbacks(statusRunnable)
        statusHandler.removeCallbacks(deferredNotificationPermissionRunnable)
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter(NovaVpnService.ACTION_VPN_STATE)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
             registerReceiver(vpnStateReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
             registerReceiver(vpnStateReceiver, filter)
        }
        val doomedFilter = android.content.IntentFilter(NovaVpnService.ACTION_VPN_PROCESS_DOOMED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
             registerReceiver(vpnProcessDoomedReceiver, doomedFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
             registerReceiver(vpnProcessDoomedReceiver, doomedFilter)
        }
        val discoveryFilter = android.content.IntentFilter(NovaVpnService.ACTION_WARP_CONFIG_DISCOVERY)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
             registerReceiver(warpDiscoveryReceiver, discoveryFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
             registerReceiver(warpDiscoveryReceiver, discoveryFilter)
        }
        val updateFilter = android.content.IntentFilter(AppUpdateManager.ACTION_UPDATE_STATE_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
             registerReceiver(updateStateReceiver, updateFilter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
             registerReceiver(updateStateReceiver, updateFilter)
        }
    }
    
    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(vpnStateReceiver) } catch (e: Exception) {}
        try { unregisterReceiver(vpnProcessDoomedReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(warpDiscoveryReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(updateStateReceiver) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseVpnNetwork()
        cancelDoomedProcessRestart("экран закрыт")
        statusHandler.removeCallbacks(deferredNotificationPermissionRunnable)
        startFlowExecutor.shutdown()
        ipExecutor.shutdown()
        latencyExecutor.shutdown()
    }

    private fun getPersistedServiceState(): String = clientData.getServiceState()

    private fun getPersistedServiceBackend(): String = clientData.getServiceBackend()

    private fun refreshInstallUpdateButton() {
        val readyVersion = AppUpdateManager.getReadyDownloadedVersion(this)
        if (readyVersion.isNotBlank()) {
            // Версию показываем ту, что реально лежит на диске: подпись — это обещание,
            // и оно должно совпадать с тем, что установится по нажатию.
            tvUpdateCaption.text = "Обновить до ${formatVersionLabel(readyVersion)}"
            btnInstallUpdate.visibility = View.VISIBLE
            return
        }
        // Обновление вышло, но ещё не скачано — плашка всё равно нужна.
        //
        // Пока приложение качало APK само, плашка появлялась только на скачанное и
        // этого хватало. Автозагрузку убрали, и без этой ветки единственным сигналом
        // осталось бы уведомление — а оно не показывается вовсе, если человек
        // запретил уведомления. Подпись честно называет, что сделает нажатие.
        val availableVersion = AppUpdateManager.getAvailableUpdateVersion(this)
        if (availableVersion.isNotBlank()) {
            tvUpdateCaption.text = "Скачать ${formatVersionLabel(availableVersion)}"
            btnInstallUpdate.visibility = View.VISIBLE
        } else {
            btnInstallUpdate.visibility = View.GONE
        }
    }

    private fun formatVersionLabel(version: String): String {
        val trimmed = version.trim()
        return if (trimmed.startsWith("v", ignoreCase = true)) trimmed else "v$trimmed"
    }

    private fun requestImmediateVpnHealthRecheck(
        minIntervalMs: Long = 1500L,
        reason: String = "foreground-resume",
    ) {
        if (reason == "pending-proof-enter") {
            return
        }
        if (isManualStopUiSuppressed()) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        val connectedAgeMs = if (lastTunnelConnectedAtMs > 0L) now - lastTunnelConnectedAtMs else Long.MAX_VALUE
        if (
            reason == "foreground-resume" &&
            isTunnelConnected() &&
            connectedAgeMs in 0..10_000L
        ) {
            return
        }
        val liveNovaService = isNovaVpnServiceRunning()
        val liveNovaVpn = findCurrentVpnNetwork() != null
        if (!liveNovaService && !liveNovaVpn) {
            return
        }
        val persistedState = getPersistedServiceState()
        val shouldRecheck =
            persistedState == NovaVpnService.STATE_CONNECTED ||
                isTunnelConnected()
        if (!shouldRecheck) return
        if (now - lastForegroundHealthRecheckAtMs < minIntervalMs) return
        lastForegroundHealthRecheckAtMs = now
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, NovaVpnService::class.java).apply {
                    action = NovaVpnService.ACTION_FORCE_HEALTH_RECHECK
                    putExtra(NovaVpnService.EXTRA_FORCE_HEALTH_RECHECK_REASON, reason)
                }
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Отправляет службе явный запуск и запоминает его.
     *
     * Запоминаем именно здесь: когда процесс `:vpn` объявит себя обречённым, повторить
     * будет нечего — служба умрёт вместе со своим состоянием, а восстанавливать намерение
     * пользователя из файлов дороже и менее точно, чем сохранить сам intent.
     */
    private fun startExplicitVpnService(intent: Intent) {
        lastExplicitStartIntent = Intent(intent)
        ContextCompat.startForegroundService(this, intent)
        startFlowHandedToService = true
    }

    /**
     * Жив ли ещё процесс `:vpn`.
     *
     * С Android 8 список отдаёт только процессы своего приложения — а нужен как раз свой.
     * Когда ответа нет вовсе, отвечаем «жив»: неизвестность здесь стоит дороже ожидания.
     * Запуск поверх живого фитиля — это ровно тот убитый через полторы секунды туннель,
     * от которого уходим, а лишнее ожидание всего лишь возвращает нас к перезапуску
     * средствами Android.
     */
    private fun isVpnProcessAlive(): Boolean {
        val am = getSystemService(ActivityManager::class.java) ?: return true
        val target = "$packageName:vpn"
        return runCatching {
            am.runningAppProcesses?.any { it.processName == target } ?: true
        }.getOrDefault(true)
    }

    private fun armDoomedProcessRestart() {
        if (lastExplicitStartIntent == null) {
            LogManager.log(
                "Служба сообщила об обречённом :vpn, но повторять нечего: " +
                    "явного запуска из этого экрана не было."
            )
            return
        }
        if (isManualStopUiSuppressed()) {
            LogManager.log(
                "Служба сообщила об обречённом :vpn, но пользователь остановил VPN. " +
                    "Перезапуск не планируем."
            )
            return
        }
        LogManager.log(
            "Служба сообщила: процесс :vpn обречён. Ждём его смерти и повторяем запуск " +
                "сами — перезапуск средствами Android приходит со своей задержкой."
        )
        doomedRestartArmed = true
        doomedRestartDeadlineMs = SystemClock.elapsedRealtime() + DOOMED_RESTART_WAIT_MS
        statusHandler.removeCallbacks(doomedRestartRunnable)
        statusHandler.postDelayed(doomedRestartRunnable, DOOMED_RESTART_POLL_MS)
    }

    private fun cancelDoomedProcessRestart(reason: String) {
        if (!doomedRestartArmed) return
        doomedRestartArmed = false
        statusHandler.removeCallbacks(doomedRestartRunnable)
        LogManager.log("Перезапуск после обречённого :vpn отменён: $reason.")
    }

    private fun markServiceStoppedLocally() {
        isStartFlowActive = false
        currentAttemptOrdinal = 0
        currentAttemptTotal = 0
        missingVpnSinceMs = 0L
        clientData.clearSoftReapplyPending()
        clientData.clearTransientConnectingPending()
        // Бэкенд не называем: остановка не меняет того, чем подключались. Пока здесь
        // стоял `BACKEND_WARP`, любая локальная остановка переписывала VLESS-сессию
        // на WARP, и экран после неудачи со своими профилями подписывал её встроенным
        // профилем.
        clientData.saveServiceState(NovaVpnService.STATE_STOPPED)
    }

    private fun markServiceConnectingLocally(backend: String) {
        clientData.clearSoftReapplyPending()
        val backendLabel = backend.ifBlank { NovaVpnService.BACKEND_WARP }
        // Знаменатель экран не называет.
        //
        // Здесь писалась длина встроенного списка, и она уходила в общий файл
        // состояния как будто от службы: при выбранном MASQUE до первой попытки
        // мелькало «1/50», хотя кандидатов три. Очередь знает только служба —
        // до её первого снимка честно показывается «...».
        clientData.saveServiceState(
            NovaVpnService.STATE_CONNECTING,
            backendLabel,
            0,
            0,
        )
    }

    private fun maybeHandleAutomationIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("run_opera_only", false)) {
            val region = intent
                .getStringExtra(NovaVpnService.EXTRA_EXIT_REGION)
                .orEmpty()
                .trim()
                .lowercase()
                .let { if (it == "us") "us" else "eu" }
            val backend = "${NovaVpnService.BACKEND_OPERA}-${region.uppercase()}"
            pendingStatusText = "ПОДКЛЮЧЕНИЕ... ${region.uppercase()}"
            markServiceConnectingLocally(backend)
            updateUiByState(NovaVpnService.STATE_CONNECTING)
            val serviceIntent = Intent(this, NovaVpnService::class.java).apply {
                action = NovaVpnService.ACTION_START_OPERA_ONLY
                applyCurrentPreferenceExtras(this)
                putExtra(NovaVpnService.EXTRA_EXIT_REGION, region)
            }
            startExplicitVpnService(serviceIntent)
            return
        }
        if (intent.getBooleanExtra("run_warp_diagnostics", false)) {
            val region = intent.getStringExtra(NovaVpnService.EXTRA_EXIT_REGION).orEmpty().ifBlank { "ru" }
            LogManager.log("Automation intent: run_warp_diagnostics=true, region=$region")
            val serviceIntent = Intent(this, NovaVpnService::class.java).apply {
                action = NovaVpnService.ACTION_RUN_WARP_DIAGNOSTICS
                applyCurrentPreferenceExtras(this)
                putExtra(NovaVpnService.EXTRA_EXIT_REGION, region)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            return
        }
        if (intent.getBooleanExtra("run_warp_quality_diagnostics", false)) {
            val qualityRegion = intent.getStringExtra(NovaVpnService.EXTRA_EXIT_REGION).orEmpty().ifBlank { "ru" }
            LogManager.log("Automation intent: run_warp_quality_diagnostics=true, region=$qualityRegion")
            val qualityIntent = Intent(this, NovaVpnService::class.java).apply {
                action = NovaVpnService.ACTION_START_WARP_QUALITY_DIAGNOSTICS
                applyCurrentPreferenceExtras(this)
                putExtra(NovaVpnService.EXTRA_EXIT_REGION, qualityRegion)
            }
            ContextCompat.startForegroundService(this, qualityIntent)
        }
    }

    private fun applyCurrentPreferenceExtras(intent: Intent) {
        intent.putExtra(NovaVpnService.EXTRA_EXIT_REGION, clientData.getExitRegionPreference())
        intent.putExtra(
            NovaVpnService.EXTRA_IMPORTED_CONFIG_SOURCE_ENABLED,
            clientData.isImportedWarpOnlyModeEnabled(),
        )
        intent.putExtra(
            NovaVpnService.EXTRA_IMPORTED_PROTOCOL_PREFERENCE,
            clientData.getImportedProtocolPreference(),
        )
        intent.putExtra(NovaVpnService.EXTRA_REAPPLY_SPLIT_MODE, clientData.getSplitMode())
        intent.putStringArrayListExtra(
            NovaVpnService.EXTRA_REAPPLY_SPLIT_APPS,
            ArrayList(clientData.getSplitApps()),
        )
        intent.putExtra(
            NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_ENABLED,
            clientData.getTrafficMaskEnabled(),
        )
        intent.putExtra(
            NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_MODE,
            clientData.getTrafficMaskMode(),
        )
        intent.putExtra(
            NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_HOST,
            clientData.getTrafficMaskHost(),
        )
        intent.putExtra(
            NovaVpnService.EXTRA_REAPPLY_SNI_MASK_MODE,
            clientData.getSniMaskMode(),
        )
        intent.putExtra(
            NovaVpnService.EXTRA_REAPPLY_SNI_MASK_LIST,
            clientData.getSniCustomListRaw(),
        )
    }

    private fun restoreCachedTunnelSnapshot() {
        val snapshot = clientData.getTunnelUiSnapshot() ?: return
        if (snapshot.ipv4.isBlank() && snapshot.ipv6.isBlank() && snapshot.country.isBlank()) return
        currentIpv4 = snapshot.ipv4.ifBlank { currentIpv4 }
        currentIpv6 = snapshot.ipv6.ifBlank { currentIpv6 }
        currentCountry = snapshot.country.ifBlank { currentCountry }
        currentTunnelBackend = resolveUiBackend(snapshot.backend)
        tunnelIpResolved = snapshot.ipv4.isNotBlank() || snapshot.ipv6.isNotBlank()
    }

    private fun restoreDirectUiSnapshot() {
        val snapshot = clientData.getDirectUiSnapshot() ?: return
        if (snapshot.ipv4.isBlank() && snapshot.ipv6.isBlank() && snapshot.country.isBlank()) return
        currentIpv4 = snapshot.ipv4.ifBlank { "..." }
        currentIpv6 = snapshot.ipv6.ifBlank { "..." }
        currentCountry = snapshot.country.ifBlank { "--" }
    }

    private fun resolveUiBackend(snapshotBackend: String? = null): String {
        resolveImportedUiBackendLabel()?.let { importedBackend ->
            return importedBackend
        }
        val snapshot = snapshotBackend?.trim().orEmpty()
        val selectedRegion = clientData.getExitRegionPreference().trim().lowercase()
        val restartSessionBackend = clientData.getRestartSession()?.let { session ->
            when (session.kind.trim().lowercase()) {
                "opera" -> "${NovaVpnService.BACKEND_OPERA}-${session.region.trim().uppercase().ifBlank { clientData.getPreferredOperaLabel() }}"
                "warp" -> NovaVpnService.BACKEND_WARP
                else -> ""
            }
        }.orEmpty()
        val preferredOperaBackend =
            if (!clientData.shouldUseWarpTransport()) {
                "${NovaVpnService.BACKEND_OPERA}-${clientData.getPreferredOperaLabel()}"
            } else {
                ""
            }
        val activeVpn = findCurrentVpnNetwork()
        val activeBackend = inferBackendFromActiveVpn(activeVpn)
        val persisted = clientData.getServiceBackend().trim()
        val persistedState = getPersistedServiceState()
        val shouldPreferPersisted = isTunnelConnected() ||
            persistedState == NovaVpnService.STATE_CONNECTED ||
            persistedState == NovaVpnService.STATE_CONNECTING
        if (activeVpn != null && selectedRegion in setOf("eu", "us")) {
            return listOf(
                restartSessionBackend.takeIf(::isOperaBackend).orEmpty(),
                preferredOperaBackend,
                currentTunnelBackend.takeIf(::isOperaBackend).orEmpty(),
                persisted.takeIf(::isOperaBackend).orEmpty(),
                snapshot.takeIf(::isOperaBackend).orEmpty(),
                "${NovaVpnService.BACKEND_OPERA}-${selectedRegion.uppercase()}",
            ).firstOrNull { it.isNotBlank() } ?: NovaVpnService.BACKEND_OPERA
        }
        if (activeVpn != null && activeBackend.isBlank() && isOperaProxyLoopbackAlive()) {
            return listOf(
                restartSessionBackend.takeIf(::isOperaBackend).orEmpty(),
                currentTunnelBackend.takeIf(::isOperaBackend).orEmpty(),
                persisted.takeIf(::isOperaBackend).orEmpty(),
                snapshot.takeIf(::isOperaBackend).orEmpty(),
                preferredOperaBackend,
                "${NovaVpnService.BACKEND_OPERA}-${clientData.getPreferredOperaLabel()}",
                NovaVpnService.BACKEND_OPERA,
            ).firstOrNull { it.isNotBlank() } ?: NovaVpnService.BACKEND_OPERA
        }
        return when {
            shouldPreferPersisted && activeBackend.isNotBlank() -> activeBackend
            shouldPreferPersisted && restartSessionBackend.isNotBlank() && isOperaBackend(restartSessionBackend) ->
                restartSessionBackend
            shouldPreferPersisted && persisted.isNotBlank() -> persisted
            shouldPreferPersisted && preferredOperaBackend.isNotBlank() -> preferredOperaBackend
            snapshot.isNotBlank() -> snapshot
            activeBackend.isNotBlank() -> activeBackend
            restartSessionBackend.isNotBlank() -> restartSessionBackend
            persisted.isNotBlank() -> persisted
            preferredOperaBackend.isNotBlank() -> preferredOperaBackend
            currentTunnelBackend.isNotBlank() -> currentTunnelBackend
            else -> NovaVpnService.BACKEND_WARP
        }
    }

    private fun reconcileSystemVpnStateIfNeeded() {
        if (isManualStopUiSuppressed() || isRecentLocalStop()) return
        if (isStartFlowActive) return
        if (isNovaVpnServiceRunning()) return
        val persistedState = getPersistedServiceState()
        val activeVpn = findCurrentVpnNetwork() ?: return
        if (!isSystemVpnLikelyNova(activeVpn)) return

        if (persistedState == NovaVpnService.STATE_STOPPED && clientData.getRestartSession() == null) {
            LogManager.log(
                "После ручного stop в системе ещё висит stale VPN Nova. " +
                    "Не синхронизируем UI в CONNECTED и просим сервис дожать cleanup."
            )
            requestStaleStopCleanup()
            return
        }

        if (persistedState == NovaVpnService.STATE_STOPPED) {
            if (clientData.getAutoReconnect() && clientData.getRestartSession() != null) {
                LogManager.log("Обнаружен системный VPN Nova без живого состояния приложения. Пытаемся восстановить сеанс.")
                clientData.saveServiceState(
                    NovaVpnService.STATE_CONNECTING,
                    clientData.getServiceBackend(),
                )
                currentTunnelBackend = clientData.getServiceBackend()
                updateUiByState(NovaVpnService.STATE_CONNECTING)
                try {
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, NovaVpnService::class.java).apply {
                            action = NovaVpnService.ACTION_RESTORE_LAST_SESSION
                        }
                    )
                } catch (e: Exception) {
                    LogManager.log("Не удалось восстановить VPN-сеанс Nova: ${e.message}")
                }
            } else {
                val syncedBackend = resolveConnectedUiBackend(activeVpn)
                    .ifBlank { clientData.getServiceBackend() }
                    .ifBlank { currentTunnelBackend.ifBlank { NovaVpnService.BACKEND_OPERA } }
                val syncedState = if (
                    isStartFlowActive ||
                    clientData.isTransientConnectingPending() ||
                    clientData.isSoftReapplyPending()
                ) {
                    NovaVpnService.STATE_CONNECTING
                } else {
                    NovaVpnService.STATE_CONNECTED
                }
                LogManager.log(
                    "Обнаружен системный VPN Nova без локального состояния. " +
                        "Синхронизируем UI с системным VPN без принудительного stop."
                )
                clientData.saveServiceState(syncedState, syncedBackend)
                currentTunnelBackend = syncedBackend
                updateUiByState(syncedState)
            }
        }
    }

    private fun requestStaleStopCleanup() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, NovaVpnService::class.java).apply {
                    action = NovaVpnService.ACTION_CONFIRM_STOP_CLEANUP
                }
            )
        } catch (_: Exception) {
        }
    }

    private val updateStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            if (intent?.action == AppUpdateManager.ACTION_UPDATE_STATE_CHANGED) {
                refreshInstallUpdateButton()
            }
        }
    }

    private fun isLikelyNovaVpnNetwork(network: Network): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        if (isNovaVpnOwner(caps)) return true
        val transportInfo = extractVpnTransportLabel(caps)
        return transportInfo.contains("NovaVPN", ignoreCase = true) ||
            transportInfo.contains("NovaOperaVPN", ignoreCase = true)
    }

    private fun isSystemVpnLikelyNova(network: Network): Boolean {
        if (isLikelyNovaVpnNetwork(network)) return true
        return hasStrongLocalNovaSessionEvidence()
    }

    private fun inferBackendFromActiveVpn(network: Network?): String {
        if (network != null) {
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(network)
            if (isNovaVpnOwner(caps)) {
                resolveImportedUiBackendLabel()?.let { importedBackend ->
                    return importedBackend
                }
                val persistedBackend = getPersistedServiceBackend().ifBlank { clientData.getServiceBackend() }
                if (isOperaBackend(persistedBackend)) {
                    return persistedBackend
                }
                if (persistedBackend.trim().uppercase().startsWith(NovaVpnService.BACKEND_WARP)) {
                    return NovaVpnService.BACKEND_WARP
                }
                val transportInfo = extractVpnTransportLabel(caps)
                if (transportInfo.contains("NovaOperaVPN", ignoreCase = true) || isOperaProxyLoopbackAlive()) {
                    return "${NovaVpnService.BACKEND_OPERA}-${clientData.getPreferredOperaLabel()}"
                }
                return NovaVpnService.BACKEND_WARP
            }
            val transportInfo = extractVpnTransportLabel(caps)
            if (transportInfo.contains("NovaOperaVPN", ignoreCase = true)) {
                return "${NovaVpnService.BACKEND_OPERA}-${clientData.getPreferredOperaLabel()}"
            }
            if (transportInfo.contains("NovaVPN", ignoreCase = true)) {
                return NovaVpnService.BACKEND_WARP
            }
        }
        return ""
    }

    private fun extractVpnTransportLabel(caps: NetworkCapabilities?): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""
        val transportInfo = NetworkCapabilitiesCompat.getTransportInfo(caps) ?: return ""
        try {
            val sessionId = transportInfo.javaClass.methods
                .firstOrNull { it.name == "getSessionId" && it.parameterCount == 0 }
                ?.invoke(transportInfo) as? String
            if (!sessionId.isNullOrBlank()) {
                return sessionId
            }
        } catch (_: Throwable) {
        }
        return transportInfo.toString().orEmpty()
    }

    private fun isNovaVpnOwner(caps: NetworkCapabilities?): Boolean {
        return extractVpnOwnerUid(caps) == applicationInfo.uid
    }

    private fun extractVpnOwnerUid(caps: NetworkCapabilities?): Int? {
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

    private fun isOperaProxyLoopbackAlive(timeoutMs: Int = 350): Boolean {
        return try {
            java.net.Socket().use { socket ->
                socket.connect(OperaProxyManager.getLoopbackProxyAddress(this), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isOperaBackend(backendLabel: String): Boolean {
        return backendLabel.trim().uppercase().startsWith(NovaVpnService.BACKEND_OPERA)
    }

    private fun isAwgBackend(backendLabel: String): Boolean {
        return backendLabel.trim().uppercase() == "AWG"
    }

    private fun isVlessBackend(backendLabel: String): Boolean {
        return backendLabel.trim().uppercase().startsWith(NovaVpnService.BACKEND_VLESS)
    }

    private fun resolveImportedUiBackendLabel(): String? {
        if (!clientData.isImportedConfigSourceActive()) return null
        // Схлопывание «AUTO» в единственную семью раньше жило только здесь, и экран
        // расходился со службой: подпись обещала «VLESS», а служба фазу VLESS не
        // запускала. Теперь решение одно на обоих — в ClientData.
        return clientData.formatImportedProtocolDisplay(clientData.resolveEffectiveImportedProtocol())
            .takeIf { it.isNotBlank() && !it.equals("AUTO", ignoreCase = true) }
    }

    private fun resolveConnectedUiBackend(tunnelNetwork: Network? = findCurrentVpnNetwork()): String {
        resolveImportedUiBackendLabel()?.let { importedBackend ->
            return importedBackend
        }
        val selectedRegion = clientData.getExitRegionPreference().trim().lowercase()
        val restartSession = clientData.getRestartSession()
        val inferred = inferBackendFromActiveVpn(tunnelNetwork)
        if (inferred.isNotBlank()) return inferred
        if (restartSession?.kind?.trim()?.lowercase() == "opera") {
            val restartRegion = restartSession.region.trim().uppercase().ifBlank { clientData.getPreferredOperaLabel() }
            return "${NovaVpnService.BACKEND_OPERA}-$restartRegion"
        }
        if (selectedRegion in setOf("eu", "us")) {
            return "${NovaVpnService.BACKEND_OPERA}-${selectedRegion.uppercase()}"
        }
        if (tunnelNetwork != null && isOperaProxyLoopbackAlive()) {
            return "${NovaVpnService.BACKEND_OPERA}-${clientData.getPreferredOperaLabel()}"
        }
        return resolveUiBackend()
    }

    private fun checkCurrentIp() {
        val now = SystemClock.elapsedRealtime()
        if (!ipRefreshInFlight.compareAndSet(false, true)) {
            val startedAt = ipRefreshStartedAtMs
            val staleConnectedRefresh = isTunnelConnected() && if (startedAt > 0L) {
                // Задача уже работает: столько ждать её нечего, вызов к сервису
                // ограничен шестью секундами.
                now - startedAt > IP_REFRESH_RUNNING_STALE_MS
            } else {
                // Задача ещё стоит в очереди. Ставить рядом вторую бессмысленно:
                // выполнять их будет тот же занятый пул.
                now - ipRefreshQueuedAtMs > IP_REFRESH_QUEUED_STALE_MS
            }
            if (!staleConnectedRefresh) {
                return
            }
            ipRefreshGeneration.incrementAndGet()
            ipRefreshInFlight.set(false)
            if (!ipRefreshInFlight.compareAndSet(false, true)) {
                return
            }
            LogManager.log("UI checkCurrentIp: прерываем зависший IP refresh и запускаем новый для живого туннеля.")
        }
        ipRefreshStartedAtMs = 0L
        ipRefreshQueuedAtMs = now
        val refreshGeneration = ipRefreshGeneration.incrementAndGet()
        ipExecutor.execute {
            ipRefreshStartedAtMs = SystemClock.elapsedRealtime()
            try {
                val connectUiPending =
                    vpnState == NovaVpnService.STATE_CONNECTING ||
                        isStartFlowActive ||
                        clientData.isTransientConnectingPending() ||
                        clientData.isSoftReapplyPending() ||
                        getPersistedServiceState() == NovaVpnService.STATE_CONNECTING
                val tunnelNetwork = if (isTunnelConnected()) {
                    vpnNetwork ?: findCurrentVpnNetwork()?.also { vpnNetwork = it }
                } else {
                    null
                }
                if (tunnelNetwork == null && connectUiPending) {
                    Handler(Looper.getMainLooper()).post {
                        tunnelIpResolved = false
                        currentIpv4 = "..."
                        currentIpv6 = "..."
                        currentCountry = "--"
                        updateIpDisplay()
                    }
                    return@execute
                }
                val resolvedBackend = if (tunnelNetwork != null) {
                    resolveConnectedUiBackend(tunnelNetwork)
                } else {
                    resolveUiBackend()
                }
                val pendingStrictWarpProof =
                    tunnelNetwork != null &&
                        !isOperaBackend(resolvedBackend) &&
                        (!hasConnectedUiProof() || connectedUiAwaitingProof)
                val hadUiProofBeforeRefresh = hasConnectedUiProof()
                currentTunnelBackend = resolvedBackend
                if (isTunnelConnected() && tunnelNetwork == null) {
                    LogManager.log("UI checkCurrentIp: VPN отмечен как CONNECTED, но текущая VPN Network не найдена.")
                    Handler(Looper.getMainLooper()).post {
                        tunnelIpResolved = false
                        currentIpv4 = "..."
                        currentIpv6 = "..."
                        currentCountry = "--"
                        updateIpDisplay()
                    }
                    return@execute
                }
                var snapshot = if (tunnelNetwork != null) {
                    if (isOperaBackend(resolvedBackend)) {
                        fetchIpSnapshotViaOperaProxy()
                    } else if (isVlessBackend(resolvedBackend)) {
                        // Своими силами экран этот адрес не узнает: при раздельном
                        // туннелировании он снаружи VPN, и запрос «по умолчанию» уходит
                        // мимо узла — возвращался адрес и страна провайдера, отчего при
                        // выходе в Сингапуре бейдж показывал RU. Берём снимок службы:
                        // она наблюдает выход через SOCKS-инбаунд ядра.
                        null
                    } else if (pendingStrictWarpProof) {
                        // Пока нет подтверждённого data-plane, берём IP/trace только через сам VPN Network.
                        // Иначе можно случайно увидеть прямой Wi‑Fi IP и ложно объявить WARP рабочим.
                        fetchIpSnapshot(tunnelNetwork)
                    } else {
                        // For WARP Nova itself always remains inside the VPN, so querying the
                        // exit IP through the app's default route is both sufficient and avoids
                        // problematic VPN-Network-bound fetches on some devices.
                        fetchIpSnapshot(null)
                    }
                } else {
                    fetchIpSnapshot(null)
                }
                if (
                    snapshot == null ||
                    (
                        snapshot.ipv4.isBlank() &&
                            snapshot.ipv6.isBlank() &&
                            snapshot.country.isBlank()
                        )
                ) {
                    if (!pendingStrictWarpProof) {
                        snapshot = fallbackIpSnapshot(tunnelNetwork, resolvedBackend)
                    }
                }
                val effectiveSnapshot = snapshot ?: return@execute

                val primaryIp = when {
                    effectiveSnapshot.ipv4.isNotBlank() -> effectiveSnapshot.ipv4
                    effectiveSnapshot.ipv6.isNotBlank() -> effectiveSnapshot.ipv6
                    else -> ""
                }
                if (primaryIp.isBlank() && effectiveSnapshot.country.isBlank()) {
                    if (tunnelNetwork != null) {
                        LogManager.log("UI checkCurrentIp: живой туннель есть, но snapshot IP/региона пуст.")
                    }
                    return@execute
                }
                if (tunnelNetwork != null) {
                    // Транспорт печатаем рядом с бэкендом: именно он решает, что
                    // окажется на бейдже, и его отсутствие в этой строке однажды уже
                    // спрятало расхождение «в туннеле MASQUE, на экране WARP».
                    LogManager.log(
                        "UI checkCurrentIp: snapshot получен, ip=${primaryIp.ifBlank { "-" }}, " +
                            "country=${effectiveSnapshot.country.ifBlank { "-" }}, backend=$resolvedBackend, " +
                            "transport=${clientData.getServiceTransport().ifBlank { "-" }}"
                    )
                }

                if (
                    tunnelNetwork != null &&
                    primaryIp.isNotBlank() &&
                    // Для VLESS экран лишь пересказывает наблюдение службы. Записывать
                    // его обратно нельзя: любое своё измерение здесь идёт мимо узла и
                    // затирало бы честное наблюдение адресом провайдера.
                    !isVlessBackend(resolvedBackend)
                ) {
                    clientData.saveLastExitObservation(
                        ip = primaryIp,
                        country = effectiveSnapshot.country,
                        colo = effectiveSnapshot.colo,
                    )
                }
                Handler(Looper.getMainLooper()).post {
                    val staleButUsefulConnectedSnapshot =
                        refreshGeneration != ipRefreshGeneration.get() &&
                            tunnelNetwork != null &&
                            isTunnelConnected() &&
                            !tunnelIpResolved &&
                            (
                                effectiveSnapshot.ipv4.isNotBlank() ||
                                    effectiveSnapshot.ipv6.isNotBlank() ||
                                    effectiveSnapshot.country.isNotBlank()
                                )
                    if (refreshGeneration != ipRefreshGeneration.get() && !staleButUsefulConnectedSnapshot) {
                        return@post
                    }
                    if (tunnelNetwork != null && !isTunnelConnected()) {
                        tunnelIpResolved = false
                        currentIpv4 = "..."
                        currentIpv6 = "..."
                        currentCountry = "--"
                        clientData.clearTunnelUiSnapshot()
                        updateIpDisplay()
                        return@post
                    }
                    tunnelIpResolved =
                        tunnelNetwork != null &&
                            (effectiveSnapshot.ipv4.isNotBlank() || effectiveSnapshot.ipv6.isNotBlank())
                    val operaTunnelSnapshot = tunnelNetwork != null && isOperaBackend(resolvedBackend)
                    currentIpv4 = if (operaTunnelSnapshot) {
                        effectiveSnapshot.ipv4.ifBlank { "..." }
                    } else {
                        stabilizeObservedIp(effectiveSnapshot.ipv4, currentIpv4, ipv4Candidate)
                    }
                    currentIpv6 = if (operaTunnelSnapshot) {
                        effectiveSnapshot.ipv6.ifBlank { "..." }
                    } else {
                        stabilizeObservedIp(effectiveSnapshot.ipv6, currentIpv6, ipv6Candidate)
                    }
                    currentCountry = effectiveSnapshot.country.ifBlank {
                        if (tunnelNetwork != null && !hadUiProofBeforeRefresh) {
                            "--"
                        } else {
                            currentCountry.takeIf { it.isNotBlank() && it != "--" } ?: "--"
                        }
                    }
                    if (tunnelIpResolved) {
                        clientData.saveTunnelUiSnapshot(
                            ipv4 = currentIpv4,
                            ipv6 = currentIpv6,
                            country = currentCountry,
                            backend = resolvedBackend,
                        )
                    } else {
                        clientData.saveDirectUiSnapshot(
                            ipv4 = currentIpv4,
                            ipv6 = currentIpv6,
                            country = currentCountry,
                        )
                    }
                    updateIpDisplay()
                    promoteConnectedUiIfVerified()
                }
            } catch (_: Exception) {
            } finally {
                if (refreshGeneration == ipRefreshGeneration.get()) {
                    ipRefreshStartedAtMs = 0L
                    ipRefreshQueuedAtMs = 0L
                    ipRefreshInFlight.set(false)
                }
            }
        }
    }

    private fun fallbackIpSnapshot(
        tunnelNetwork: Network?,
        resolvedBackend: String,
    ): IpSnapshot? {
        if (tunnelNetwork == null) return null
        val tunnelSnapshot = clientData.getTunnelUiSnapshot()
        if (
            tunnelSnapshot != null &&
            (tunnelSnapshot.ipv4.isNotBlank() || tunnelSnapshot.ipv6.isNotBlank() || tunnelSnapshot.country.isNotBlank()) &&
            tunnelSnapshot.backend.trim().equals(resolvedBackend.trim(), ignoreCase = true)
        ) {
            return IpSnapshot(
                ipv4 = tunnelSnapshot.ipv4,
                ipv6 = tunnelSnapshot.ipv6,
                country = tunnelSnapshot.country,
                colo = clientData.getLastExitColo(),
            )
        }

        val lastExitIp = clientData.getLastExitIp().trim()
        val lastExitCountry = clientData.getLastExitCountry().trim()
        if (lastExitIp.isBlank() && lastExitCountry.isBlank()) return null
        return IpSnapshot(
            ipv4 = if (isIpv4Address(lastExitIp)) lastExitIp else "",
            ipv6 = if (lastExitIp.contains(':')) lastExitIp else "",
            country = lastExitCountry,
            colo = clientData.getLastExitColo(),
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            if (maybeHandleAdbResetIntent(intent)) return
            maybeHandleAutomationIntent(intent)
        }
    }

    private fun maybeHandleAdbResetIntent(intent: Intent?): Boolean {
        val action = intent?.action?.trim().orEmpty()
        if (action.isNotEmpty()) {
            LogManager.log("MainActivity launched with action: $action")
        }
        if (!::clientData.isInitialized) {
            clientData = ClientData(this)
        }
        when (action) {
            ACTION_ADB_RESET_WARP_REGISTRATION -> {
                clientData.resetWarpStoredRegistrationIdentity()
                clientData.clearWarpFullCycleFailureState()
                LogManager.log(
                    "ADB reset WARP registration выполнен: cached bootstrap/config очищены, " +
                        "adaptive ranking сохранён."
                )
                finish()
                return true
            }
            ACTION_ADB_FORCE_WARP_DIRECT_REGISTRATION_ONCE -> {
                clientData.setWarpDebugSkipFastProxyOnceEnabled(true)
                LogManager.log(
                    "ADB debug: следующий запуск регистрации WARP пропустит быстрый proxy path " +
                        "и пойдёт в прямой obfuscated этап."
                )
                finish()
                return true
            }
            ACTION_ADB_SET_AWG_JUNK -> {
                val disabled = intent?.getBooleanExtra(EXTRA_ADB_AWG_JUNK_DISABLED, false) ?: false
                clientData.setAwgJunkDisabled(disabled)
                LogManager.log(
                    if (disabled) {
                        "ADB debug: junk-пакеты AWG (Jc/Jmin/Jmax/I1..I5) отключены — " +
                            "следующее подключение уйдёт без них."
                    } else {
                        "ADB debug: junk-пакеты AWG возвращены в конфигурацию."
                    }
                )
                finish()
                return true
            }
            ACTION_ADB_SET_WARP_IDENTITY -> {
                val token = intent?.getStringExtra(EXTRA_ADB_WARP_TOKEN)?.trim().orEmpty()
                val deviceId = intent?.getStringExtra(EXTRA_ADB_WARP_DEVICE_ID)?.trim().orEmpty()
                if (token.isEmpty() || deviceId.isEmpty()) {
                    LogManager.log("ADB debug: не заданы warp_token/warp_device_id — личность не подменена.")
                } else {
                    val base = clientData.getConfig()
                    clientData.saveReserveWarpIdentity(
                        WarpConfig(
                            privateKey = base?.privateKey.orEmpty(),
                            publicKey = base?.publicKey.orEmpty(),
                            ipv4 = base?.ipv4.orEmpty(),
                            ipv6 = base?.ipv6.orEmpty(),
                            peerPublicKey = base?.peerPublicKey.orEmpty(),
                            peerEndpoint = base?.peerEndpoint.orEmpty(),
                            reserved = base?.reserved,
                            accessToken = token,
                            deviceId = deviceId,
                            license = null,
                            masqueConfigJson = null,
                        )
                    )
                    clientData.saveMasqueConfigJson(null)
                    clientData.setMasqueIdentityWanted(true)
                    LogManager.log(
                        "ADB debug: запасная личность подменена на внешнюю (device_id=$deviceId). " +
                            "Ключ MASQUE стёрт — будет выпущен заново на этом устройстве."
                    )
                }
                finish()
                return true
            }
            ACTION_ADB_PROBE_MASQUE -> {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, NovaVpnService::class.java).setAction(
                        NovaVpnService.ACTION_PROBE_MASQUE
                    ),
                )
                LogManager.log("ADB debug: запросили у службы пробу MASQUE.")
                finish()
                return true
            }
            ACTION_ADB_DOUBLE_ENROLL_MASQUE -> {
                // Тоже через службу: ключ и личность живут в её копии SharedPreferences.
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, NovaVpnService::class.java).setAction(
                        NovaVpnService.ACTION_DOUBLE_ENROLL_MASQUE
                    ),
                )
                LogManager.log("ADB debug: запросили у службы двойной выпуск ключа MASQUE.")
                finish()
                return true
            }
            ACTION_ADB_DUMP_MASQUE_CONFIG -> {
                // Пересылаем в службу: профиль пишет процесс `:vpn`, и только он видит
                // его в своей копии SharedPreferences. Из главного процесса выгрузка
                // получалась пустой — та же межпроцессная ловушка, что и с настройками.
                val includeSecrets =
                    intent?.getBooleanExtra(NovaVpnService.EXTRA_DUMP_MASQUE_SECRETS, false) ?: false
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, NovaVpnService::class.java)
                        .setAction(NovaVpnService.ACTION_DUMP_MASQUE_CONFIG)
                        .putExtra(NovaVpnService.EXTRA_DUMP_MASQUE_SECRETS, includeSecrets),
                )
                LogManager.log(
                    if (includeSecrets) {
                        "ADB debug: запросили у службы выгрузку профиля MASQUE без маскировки."
                    } else {
                        "ADB debug: запросили у службы выгрузку профиля MASQUE."
                    }
                )
                finish()
                return true
            }
            ACTION_ADB_SET_VLESS_PROFILE -> {
                val link = intent?.getStringExtra(EXTRA_ADB_VLESS_LINK)?.trim().orEmpty()
                if (link.isEmpty()) {
                    clientData.setVlessConfigLink(null)
                    if (clientData.getExitRegionPreference() == "vless") {
                        clientData.setExitRegionPreference("auto")
                    }
                    LogManager.log("ADB debug: профиль VLESS очищен, регион возвращён на auto.")
                } else {
                    val parsed = VlessConfig.parse(link)
                    if (parsed == null) {
                        LogManager.log("ADB debug: ссылка VLESS не разобрана, профиль не сохранён.")
                    } else {
                        clientData.setVlessConfigLink(link)
                        clientData.setExitRegionPreference("vless")
                        LogManager.log("ADB debug: профиль VLESS сохранён (${parsed.displayName}), регион переключён на VLESS.")
                    }
                }
                finish()
                return true
            }
        }
        return false
    }

    /**
     * Ручное переключение на следующий профиль VLESS.
     *
     * Служба и без кнопки уводит мёртвые узлы вниз списка и берёт следующий сама;
     * кнопка нужна там, где узел жив, но не устраивает — медленный или не тот выход.
     *
     * Какой профиль следующий, экран не решает. Список переставляет служба, она живёт
     * в процессе `:vpn`, и её порядок экран узнаёт только из общего файла. Пока ссылку
     * выбирал экран, кнопка после успешного подключения возвращала перебор к уже
     * отвергнутым узлам и счётчик начинался заново с «1/151».
     */
    /**
     * Просит службу перейти к следующему транспорту цепочки: MASQUE, Opera EU/US или
     * импортированные VLESS.
     *
     * Шаг живёт одно подключение и сохранённый режим не меняет: выбранное
     * пользователем «Авто» после нажатия кнопки остаётся «Авто».
     */
    private fun startManualTransportStep(step: String) {
        val caption = when (step) {
            NovaVpnService.MANUAL_STEP_MASQUE -> "MASQUE"
            NovaVpnService.MANUAL_STEP_OPERA_EU -> "Opera EU"
            NovaVpnService.MANUAL_STEP_OPERA_US -> "Opera US"
            NovaVpnService.MANUAL_STEP_VLESS -> "VLESS"
            else -> step
        }
        LogManager.log("UI next-profile: ручной шаг цепочки — $caption.")
        Toast.makeText(this, "Пробуем $caption", Toast.LENGTH_SHORT).show()
        currentAttemptOrdinal = 0
        currentAttemptTotal = 0
        displayedAttemptOrdinal = 0
        displayedAttemptTotal = 0
        manualProfileSwitchProgressHoldUntilMs = SystemClock.elapsedRealtime() + 25_000L
        updateUiByState(NovaVpnService.STATE_CONNECTING)
        ContextCompat.startForegroundService(
            this,
            Intent(this, NovaVpnService::class.java).apply {
                action = NovaVpnService.ACTION_REAPPLY_CURRENT_SESSION
                applyCurrentPreferenceExtras(this)
                putExtra(NovaVpnService.EXTRA_MANUAL_TRANSPORT_STEP, step)
            }
        )
    }

    private fun switchToNextVlessProfile() {
        val profileCount = clientData.getVlessProfileLinks().size
        if (profileCount == 0) {
            Toast.makeText(this, "Нет профилей VLESS", Toast.LENGTH_SHORT).show()
            return
        }
        if (profileCount == 1) {
            Toast.makeText(this, "Профиль VLESS всего один", Toast.LENGTH_SHORT).show()
            return
        }
        LogManager.log("UI VLESS next-profile: просим службу взять следующий профиль из $profileCount.")
        updateUiByState(NovaVpnService.STATE_CONNECTING)
        ContextCompat.startForegroundService(
            this,
            Intent(this, NovaVpnService::class.java).apply {
                // Не REAPPLY: пересборка сессии останавливает tun2proxy, а он при
                // завершении роняет процесс службы изнутри native-библиотеки. Служба
                // умеет сменить узел на ходу, оставив туннель поднятым.
                action = NovaVpnService.ACTION_SWITCH_VLESS_PROFILE
                // Если перебора VLESS в службе сейчас нет, она уходит в REAPPLY и
                // решает по своим настройкам, какой транспорт поднимать. Без этих
                // полей она решала по устаревшему срезу и пересобирала WARP-сессию
                // вместо смены узла VLESS.
                applyCurrentPreferenceExtras(this)
            }
        )
    }

    private fun stabilizeObservedIp(
        observedValue: String,
        currentValue: String,
        candidate: ObservedIpCandidate,
    ): String {
        val observed = observedValue.trim()
        val current = currentValue.trim()
        if (observed.isBlank()) {
            candidate.value = ""
            candidate.seenCount = 0
            return current.takeIf { it.isNotBlank() && it != "..." } ?: "—"
        }

        if (current.isBlank() || current == "..." || current == "—") {
            candidate.value = ""
            candidate.seenCount = 0
            return observed
        }

        if (observed == current) {
            candidate.value = ""
            candidate.seenCount = 0
            return current
        }

        if (candidate.value == observed) {
            candidate.seenCount += 1
        } else {
            candidate.value = observed
            candidate.seenCount = 1
        }

        val connectedWarmup = isTunnelConnected() &&
            (SystemClock.elapsedRealtime() - lastTunnelConnectedAtMs) < 12_000L
        val requiredMatches = if (connectedWarmup) 3 else 2
        return if (candidate.seenCount >= requiredMatches) {
            candidate.value = ""
            candidate.seenCount = 0
            observed
        } else {
            current
        }
    }

    private fun fetchIpSnapshot(network: Network?): IpSnapshot? {
        val fastTrace = fetchTraceInfoViaSocket(
            network,
            listOf("1.1.1.1", "1.0.0.1"),
            timeoutMs = if (network != null) 1200 else 1800,
        )
        if (fastTrace != null) {
            return IpSnapshot(
                ipv4 = fastTrace.ip.takeIf(::isIpv4Address).orEmpty(),
                ipv6 = fastTrace.ip.takeUnless(::isIpv4Address).orEmpty(),
                country = fastTrace.country,
                colo = fastTrace.colo,
            )
        }
        if (network != null) {
            return null
        }

        val ipv4Trace = fetchTraceInfoFromUrls(network, CloudflareTrace.IPV4_URLS)
        val ipv6Trace = fetchTraceInfoFromUrls(network, CloudflareTrace.IPV6_URLS)
        val genericTrace = fetchTraceInfoFromUrls(network, CloudflareTrace.HOSTNAME_URLS)

        // Только Cloudflare: адрес и страна приходят одним ответом и разойтись не
        // могут, а сторонних определителей адреса здесь больше нет ([CloudflareTrace]).
        val ipv4 = ipv4Trace?.ip.orEmpty().ifBlank {
            genericTrace?.ip.orEmpty().takeIf(::isIpv4Address).orEmpty()
        }
        val ipv6 = ipv6Trace?.ip.orEmpty().ifBlank {
            genericTrace?.ip.orEmpty().takeUnless(::isIpv4Address).orEmpty()
        }
        val badgeTrace = ipv4Trace ?: ipv6Trace ?: genericTrace
        if (ipv4.isBlank() && ipv6.isBlank()) return null
        return IpSnapshot(
            ipv4 = ipv4,
            ipv6 = ipv6,
            country = badgeTrace?.country.orEmpty(),
            colo = badgeTrace?.colo.orEmpty(),
        )
    }

    private fun fetchTraceInfoViaSocket(
        network: Network?,
        hosts: List<String>,
        timeoutMs: Int = 4000,
    ): TraceInfo? {
        for (host in hosts) {
            val body = readTraceViaSocket(network, host, timeoutMs) ?: continue
            val lines = body.lineSequence().toList()
            val traceIp = lines.firstOrNull { it.startsWith("ip=") }?.substringAfter("=")?.trim().orEmpty()
            val traceCountry = lines.firstOrNull { it.startsWith("loc=") }?.substringAfter("=")?.trim().orEmpty()
            val traceColo = lines.firstOrNull { it.startsWith("colo=") }?.substringAfter("=")?.trim().orEmpty()
            if (traceIp.isNotBlank()) {
                return TraceInfo(traceIp, traceCountry, traceColo)
            }
        }
        return null
    }

    private fun readTraceViaSocket(network: Network?, host: String, timeoutMs: Int = 4000): String? {
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

    private fun parseTraceInfo(body: String): TraceInfo? {
        val lines = body.lineSequence().toList()
        val traceIp = lines.firstOrNull { it.startsWith("ip=") }?.substringAfter("=")?.trim().orEmpty()
        val traceCountry = lines.firstOrNull { it.startsWith("loc=") }?.substringAfter("=")?.trim().orEmpty()
        val traceColo = lines.firstOrNull { it.startsWith("colo=") }?.substringAfter("=")?.trim().orEmpty()
        return if (traceIp.isNotBlank()) {
            TraceInfo(traceIp, traceCountry, traceColo)
        } else {
            null
        }
    }

    private fun readTextViaOperaProxySocket(
        host: String,
        path: String = "/",
        timeoutMs: Int = 3000,
    ): String? {
        val normalizedPath = path.takeIf { it.startsWith("/") } ?: "/$path"
        return try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(OperaProxyManager.getLoopbackProxyAddress(this), timeoutMs)
                val writer = socket.getOutputStream().bufferedWriter(Charsets.US_ASCII)
                writer.write("GET http://$host$normalizedPath HTTP/1.1\r\n")
                writer.write("Host: $host\r\n")
                writer.write("User-Agent: NovaAndroid/1.12\r\n")
                writer.write("Accept: text/plain,*/*\r\n")
                writer.write("Connection: close\r\n\r\n")
                writer.flush()
                val raw = socket.getInputStream().bufferedReader(Charsets.US_ASCII).use { reader ->
                    reader.readText()
                }
                val statusLine = raw.lineSequence().firstOrNull().orEmpty()
                if (!statusLine.contains(" 200 ")) return null
                raw.substringAfter("\r\n\r\n", "").ifBlank {
                    raw.substringAfter("\n\n", "")
                }.trim().takeIf { body -> body.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchIpSnapshotViaOperaProxy(): IpSnapshot? {
        // Прокси Opera ходит и по литералам, и по именам, поэтому пробуем оба входа
        // Cloudflare подряд; сторонних определителей адреса здесь нет.
        val fastTrace = CloudflareTrace.PROXY_TRACE_HOSTS
            .firstNotNullOfOrNull { host ->
                readTextViaOperaProxySocket(host, CloudflareTrace.PATH, timeoutMs = 2200)
                    ?.let(::parseTraceInfo)
            }
        val fastIpv4 = fastTrace?.ip?.takeIf(::isIpv4Address).orEmpty()
        val proxy = Proxy(Proxy.Type.HTTP, OperaProxyManager.getLoopbackProxyAddress(this))
        val genericTrace = fastTrace
            ?: fetchTraceInfoFromUrlsViaProxy(proxy, CloudflareTrace.HOSTNAME_URLS)

        val ipv4 = fastIpv4.ifBlank {
            genericTrace?.ip.orEmpty().takeIf(::isIpv4Address).orEmpty()
        }
        val ipv6 = genericTrace?.ip?.takeUnless(::isIpv4Address).orEmpty()
        val badgeTrace = genericTrace
        if (ipv4.isBlank() && ipv6.isBlank()) return null
        return IpSnapshot(
            ipv4 = ipv4,
            ipv6 = ipv6,
            country = badgeTrace?.country.orEmpty(),
            colo = badgeTrace?.colo.orEmpty(),
        )
    }

    private fun fetchTraceInfoFromUrls(network: Network?, urls: List<String>): TraceInfo? {
        for (url in urls) {
            val body = readTextFromUrl(network, url) ?: continue
            val lines = body.lineSequence().toList()
            val traceIp = lines.firstOrNull { it.startsWith("ip=") }?.substringAfter("=")?.trim().orEmpty()
            val traceCountry = lines.firstOrNull { it.startsWith("loc=") }?.substringAfter("=")?.trim().orEmpty()
            val traceColo = lines.firstOrNull { it.startsWith("colo=") }?.substringAfter("=")?.trim().orEmpty()
            if (traceIp.isNotBlank()) {
                return TraceInfo(traceIp, traceCountry, traceColo)
            }
        }
        return null
    }

    private fun fetchTraceInfoFromUrlsViaProxy(proxy: Proxy, urls: List<String>): TraceInfo? {
        for (url in urls) {
            val body = readTextFromUrlViaProxy(proxy, url) ?: continue
            val lines = body.lineSequence().toList()
            val traceIp = lines.firstOrNull { it.startsWith("ip=") }?.substringAfter("=")?.trim().orEmpty()
            val traceCountry = lines.firstOrNull { it.startsWith("loc=") }?.substringAfter("=")?.trim().orEmpty()
            val traceColo = lines.firstOrNull { it.startsWith("colo=") }?.substringAfter("=")?.trim().orEmpty()
            if (traceIp.isNotBlank()) {
                return TraceInfo(traceIp, traceCountry, traceColo)
            }
        }
        return null
    }

    private fun fetchPlainIpFromUrls(network: Network?, urls: List<String>): String? {
        for (url in urls) {
            val body = readTextFromUrl(network, url)?.trim().orEmpty()
            if (body.isNotBlank()) {
                return body.lineSequence().firstOrNull()?.trim().orEmpty()
            }
        }
        return null
    }

    private fun fetchPlainIpFromUrlsViaProxy(proxy: Proxy, urls: List<String>): String? {
        for (url in urls) {
            val body = readTextFromUrlViaProxy(proxy, url)?.trim().orEmpty()
            if (body.isNotBlank()) {
                return body.lineSequence().firstOrNull()?.trim().orEmpty()
            }
        }
        return null
    }

    private fun isIpv4Address(ip: String): Boolean {
        return ip.count { it == '.' } == 3 && !ip.contains(':')
    }

    private fun readTextFromUrl(network: Network?, url: String, timeoutMs: Int = 4000): String? {
        return try {
            val conn = if (network != null) {
                network.openConnection(URL(url)) as HttpURLConnection
            } else {
                URL(url).openConnection() as HttpURLConnection
            }
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", "NovaAndroid/1.12")
            conn.setRequestProperty("Accept", "text/plain,*/*")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            body
        } catch (_: Exception) {
            null
        }
    }

    private fun readTextFromUrlViaProxy(proxy: Proxy, url: String, timeoutMs: Int = 4000): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NovaAndroid/1.12")
                .header("Accept", "text/plain,*/*")
                .build()
            operaProxyHttpClientBase.newBuilder()
                .proxy(proxy)
                .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                .callTimeout((timeoutMs + 1500).toLong(), TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return null
                    response.body?.string()
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun measureLatency(minIntervalMs: Long = 2_000L) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLatencyRefreshAtMs < minIntervalMs) {
            return
        }
        if (!latencyRefreshInFlight.compareAndSet(false, true)) {
            val staleConnectedRefresh = isTunnelConnected() && (now - latencyRefreshStartedAtMs) > 2_500L
            if (!staleConnectedRefresh) {
                return
            }
            latencyRefreshGeneration.incrementAndGet()
            latencyRefreshInFlight.set(false)
            if (!latencyRefreshInFlight.compareAndSet(false, true)) {
                return
            }
            LogManager.log("UI latency refresh: прерываем зависший ping-probe и запускаем новый.")
        }
        lastLatencyRefreshAtMs = now
        latencyRefreshStartedAtMs = now
        val refreshGeneration = latencyRefreshGeneration.incrementAndGet()
        latencyExecutor.execute {
            try {
                val timeout = 3000
                var latency = -1

                val tunnelNetwork = if (isTunnelConnected()) {
                    vpnNetwork ?: findCurrentVpnNetwork()?.also { vpnNetwork = it }
                } else {
                    null
                }
                val resolvedBackend = if (tunnelNetwork != null) {
                    resolveConnectedUiBackend(tunnelNetwork)
                } else {
                    resolveUiBackend()
                }
                currentTunnelBackend = resolvedBackend

                if (isTunnelConnected() && isVlessBackend(resolvedBackend)) {
                    // Замер берём у службы. Сама она проверяет узел раз в полторы
                    // секунды через SOCKS-инбаунд ядра, а экран этот путь повторить не
                    // может: при раздельном туннелировании он снаружи VPN и своей же
                    // сети VPN не видит, а порт инбаунда служба выбирает на лету.
                    // Фильтр по метке обязателен: файл замера пишут два транспорта, а
                    // getTransportLatency отбирает только по свежести.
                    latency = clientData.getTransportLatency()
                        ?.takeIf { it.transport.equals(NovaVpnService.TRANSPORT_VLESS, ignoreCase = true) }
                        ?.latencyMs
                        ?: -1
                } else if (isTunnelConnected() && isOperaBackend(resolvedBackend)) {
                    // Замер берём у службы, как у VLESS. Свой путь экран повторить не
                    // может: в режиме Opera пакет Nova всегда вне VPN, сети VPN он не
                    // видит, а порт локального прокси служба выбирает на лету в :vpn.
                    // Прежнее условие tunnelNetwork != null поэтому не выполнялось
                    // никогда, и «Ping» для EU/US оставался пустым у всех.
                    latency = clientData.getTransportLatency()
                        ?.takeIf { it.transport.equals(NovaVpnService.TRANSPORT_OPERA, ignoreCase = true) }
                        ?.latencyMs
                        ?: -1
                    if (latency < 0) {
                        latency = measureLatencyViaOperaProxy(timeout)
                    }
                    if (latency < 0 && tunnelNetwork != null) {
                        latency = measureLatencyViaTunnelNetwork(tunnelNetwork, timeout)
                    }
                } else {
                    if (isTunnelConnected() && tunnelNetwork == null) {
                        latency = -1
                    } else {
                        latency = measureLatencyViaTunnelNetwork(tunnelNetwork, timeout)
                    }
                }

                Handler(Looper.getMainLooper()).post {
                    if (refreshGeneration != latencyRefreshGeneration.get()) {
                        return@post
                    }
                    if (!isTunnelConnected()) {
                        resetLatencyDisplay()
                        return@post
                    }
                    val labelView = findViewById<TextView>(R.id.tv_internet_label)
                    lastMeasuredLatencyMs = latency
                    if (latency >= 0) {
                         latencyGraph.addLatency(latency)
                         labelView.text = "Ping:\n$latency ms"
                         labelView.setTextColor(LatencyGraphView.colorForLatency(latency))
                    } else {
                         latencyGraph.addLatency(-1)
                         labelView.text = "Ping:\n---"
                         labelView.setTextColor(android.graphics.Color.GRAY)
                    }
                    promoteConnectedUiIfVerified()
                }
            } finally {
                if (refreshGeneration == latencyRefreshGeneration.get()) {
                    latencyRefreshStartedAtMs = 0L
                    latencyRefreshInFlight.set(false)
                }
            }
        }
    }

    private fun setupIpInteractions() {
        tvIpAddress.setOnClickListener {
            isIpVisible = !isIpVisible
            updateIpDisplay()
            ipResetHandler.removeCallbacks(ipResetRunnable)
            if (isIpVisible) ipResetHandler.postDelayed(ipResetRunnable, 5000)
        }
    }

    private fun updateIpDisplay() {
        hydrateConnectedUiFromPersistenceIfNeeded()
        val persistedTunnelSnapshot =
            if (isTunnelConnected()) clientData.getTunnelUiSnapshot() else null
        val hasPersistedTunnelSnapshot =
            persistedTunnelSnapshot != null &&
                (
                    persistedTunnelSnapshot.ipv4.isNotBlank() ||
                        persistedTunnelSnapshot.ipv6.isNotBlank() ||
                        persistedTunnelSnapshot.country.isNotBlank()
                    )
        if (hasPersistedTunnelSnapshot) {
            if (currentIpv4.isBlank() || currentIpv4 == "..." || currentIpv4 == "—") {
                currentIpv4 = persistedTunnelSnapshot!!.ipv4.ifBlank { currentIpv4 }
            }
            if (currentIpv6.isBlank() || currentIpv6 == "..." || currentIpv6 == "—") {
                currentIpv6 = persistedTunnelSnapshot!!.ipv6.ifBlank { currentIpv6 }
            }
            if (currentCountry.isBlank() || currentCountry == "--") {
                currentCountry = persistedTunnelSnapshot!!.country.ifBlank { currentCountry }
            }
            if (persistedTunnelSnapshot.ipv4.isNotBlank() || persistedTunnelSnapshot.ipv6.isNotBlank()) {
                tunnelIpResolved = true
            }
        }
        val displayIpv4Value =
            persistedTunnelSnapshot?.ipv4?.takeIf { it.isNotBlank() }
                ?: currentIpv4
        val displayIpv6Value =
            persistedTunnelSnapshot?.ipv6?.takeIf { it.isNotBlank() }
                ?: currentIpv6
        val displayCountryValue =
            persistedTunnelSnapshot?.country?.takeIf { it.isNotBlank() }
                ?: currentCountry
        if (displayCountryValue.isNotBlank()) {
            currentCountry = displayCountryValue
        }
        val unresolvedTunnel = isTunnelConnected() && !tunnelIpResolved && !hasPersistedTunnelSnapshot
        val visibleV4 = if (unresolvedTunnel) "—" else if (isIpVisible) displayOrDots(displayIpv4Value) else maskIpForDisplay(displayIpv4Value)
        val visibleV6 = if (unresolvedTunnel) "—" else if (isIpVisible) displayOrDots(displayIpv6Value) else maskIpForDisplay(displayIpv6Value)
        tvIpAddress.text = "$visibleV4\n$visibleV6"
        val tunnelConnected = isTunnelConnected()
        tvIpAddress.setTextColor(
            if (tunnelConnected && tunnelIpResolved) {
                android.graphics.Color.parseColor("#50C878")
            } else {
                android.graphics.Color.WHITE
            }
        )
        if (tunnelConnected) {
            tvCountryBadge.text = buildTunnelBadgeText()
            tvCountryBadge.visibility = android.view.View.VISIBLE
        } else {
            tvCountryBadge.visibility = android.view.View.GONE
        }
    }

    private fun buildTunnelBadgeText(): String {
        val backend = if (isTunnelConnected()) {
            resolveConnectedUiBackend()
        } else {
            resolveUiBackend()
        }
        val snapshotCountry = clientData.getTunnelUiSnapshot()?.country?.trim().orEmpty()
        val effectiveCountry = snapshotCountry.ifBlank { currentCountry.trim() }.uppercase().ifBlank { "--" }
        if (isOperaBackend(backend)) {
            val normalized = backend.trim().uppercase()
            val actualRegion = when {
                normalized.startsWith("${NovaVpnService.BACKEND_OPERA}-") -> normalized.substringAfter('-').ifBlank { "EU" }
                normalized.startsWith("${NovaVpnService.BACKEND_OPERA}:") -> normalized.substringAfter(':').trim().ifBlank { "EU" }
                else -> clientData.getPreferredOperaLabel().trim().uppercase().ifBlank { "EU" }
            }
            return "${NovaVpnService.BACKEND_OPERA}: $actualRegion"
        }
        // MASQUE живёт внутри бэкенда WARP, поэтому по бэкенду его не отличить: сервис
        // отдельно сообщает, какой транспорт реально несёт туннель. Проверка идёт до
        // остальных веток — иначе «MASQUE: RU» проигрывал общему «WARP: RU», и по
        // экрану нельзя было понять, работает ли выбранный протокол.
        val transport = clientData.getServiceTransport()
        if (transport == NovaVpnService.TRANSPORT_MASQUE) {
            return "${NovaVpnService.TRANSPORT_MASQUE}: $effectiveCountry"
        }
        // Импортированный профиль AmneziaWG подписывается своим именем: бэкенд у него
        // тот же `WARP`, и раньше бейдж обещал Cloudflare там, где туннель шёл на
        // сервер пользователя.
        if (transport == NovaVpnService.TRANSPORT_AWG) {
            return "${NovaVpnService.TRANSPORT_AWG}: $effectiveCountry"
        }
        if (backend.trim().uppercase().startsWith(NovaVpnService.BACKEND_VLESS)) {
            return "${NovaVpnService.BACKEND_VLESS}: $effectiveCountry"
        }
        if (isAwgBackend(backend)) {
            return "AWG: $effectiveCountry"
        }
        if (isTunnelConnected() && !hasConnectedUiProof()) {
            return "${NovaVpnService.BACKEND_WARP}: --"
        }
        return "${NovaVpnService.BACKEND_WARP}: $effectiveCountry"
    }

    private fun displayOrDots(ip: String): String {
        return ip.trim().ifBlank { "..." }
    }

    private fun maskIpForDisplay(ip: String): String {
        val value = ip.trim()
        if (value.isBlank() || value == "...") return value

        val ipv4 = value.split(".")
        if (ipv4.size == 4) {
            return "***.***.${ipv4[2]}.${ipv4[3]}"
        }

        if (value.contains(":")) {
            val parts = value.split(":").toMutableList()
            val nonEmptyIndexes = parts.indices.filter { parts[it].isNotEmpty() }
            if (nonEmptyIndexes.isNotEmpty()) {
                val hideCount = (nonEmptyIndexes.size / 2).coerceAtLeast(1)
                for (index in nonEmptyIndexes.take(hideCount)) {
                    parts[index] = "***"
                }
                return parts.joinToString(":")
            }
        }

        return value
    }

    private fun beginStartFlow(initialStatus: String): Int {
        manualStopUiSuppressedUntilMs = 0L
        startFlowHandedToService = false
        currentTunnelBackend = resolvePendingConnectBackend()
        pendingStatusText = initialStatus
        isStartFlowActive = true
        vpnState = NovaVpnService.STATE_CONNECTING
        clientData.markTransientConnectingPending(START_FLOW_TRANSIENT_PENDING_MS)
        markServiceConnectingLocally(currentTunnelBackend)
        seedConnectingAttemptProgress(currentTunnelBackend)
        resetAttemptProgressTracking()
        val generation = startFlowGeneration.incrementAndGet()
        renderStartFlowState(restartBackdrop = true)
        return generation
    }

    private fun seedConnectingAttemptProgress(backendLabel: String) {
        // Ни ординала, ни знаменателя: и то и другое приходит от службы. Кэш,
        // который лежал здесь, читался процессом UI из SharedPreferences и после
        // старта службы уже никогда не обновлялся — знаменатель прошлого прогона
        // побеждал честное число текущего.
        currentAttemptOrdinal = 0
        currentAttemptTotal = 0
    }

    private fun resolvePendingConnectBackend(): String {
        resolveImportedUiBackendLabel()?.let { importedBackend ->
            return importedBackend
        }
        val preference = clientData.getExitRegionPreference().trim().lowercase()
        return when {
            !clientData.shouldUseWarpTransport() && clientData.shouldAllowOperaTransport() ->
                "${NovaVpnService.BACKEND_OPERA}-${clientData.getPreferredOperaLabel()}"
            preference == "eu" || preference == "us" ->
                "${NovaVpnService.BACKEND_OPERA}-${preference.uppercase()}"
            preference == "vless" -> NovaVpnService.BACKEND_VLESS
            // Остаточный бэкенд прошлой сессии годится только там, где выбор не назван
            // явно. Пока сюда попадал и VLESS, подключение к нему подписывалось «EU» от
            // недавнего сеанса Opera.
            isOperaBackend(currentTunnelBackend) &&
                preference != "ru" && preference != "auto" && preference != "masque" ->
                currentTunnelBackend
            else -> NovaVpnService.BACKEND_WARP
        }
    }

    private fun hydrateConnectedUiFromPersistenceIfNeeded() {
        if (!isTunnelConnected()) return
        val snapshot = clientData.getTunnelUiSnapshot() ?: return
        if (snapshot.ipv4.isBlank() && snapshot.ipv6.isBlank() && snapshot.country.isBlank()) return
        if (currentIpv4.isBlank() || currentIpv4 == "..." || currentIpv4 == "—") {
            currentIpv4 = snapshot.ipv4.ifBlank { currentIpv4 }
        }
        if (currentIpv6.isBlank() || currentIpv6 == "..." || currentIpv6 == "—") {
            currentIpv6 = snapshot.ipv6.ifBlank { currentIpv6 }
        }
        if (currentCountry.isBlank() || currentCountry == "--") {
            currentCountry = snapshot.country.ifBlank { currentCountry }
        }
        if (!tunnelIpResolved && (snapshot.ipv4.isNotBlank() || snapshot.ipv6.isNotBlank())) {
            tunnelIpResolved = true
        }
        currentTunnelBackend = resolveUiBackend(snapshot.backend)
    }

    private fun isStartFlowCurrent(generation: Int): Boolean {
        return isStartFlowActive && startFlowGeneration.get() == generation
    }

    /**
     * Объясняет, почему цикл подключения оборвался до отправки intent'а службе.
     *
     * Отсечки по [isStartFlowCurrent] были молчаливыми: в журнале обрывались три
     * строки подготовки, и отличить «пользователь передумал» от «поколение сбил
     * кто-то посторонний» было нечем. Служба такой разбор уже умеет
     * (`logConnectAbortedBeforeStart`), экран — нет.
     *
     * Печатаем всё, что входит в решение, а не только вердикт: причина здесь
     * складывается из состояния флага, номера поколения и подавления после
     * ручной остановки, и по одному вердикту виновника не назвать.
     */
    private fun logStartFlowAborted(stage: String, generation: Int) {
        LogManager.log(
            "Цикл подключения оборван на этапе «$stage»: " +
                "поколение=${startFlowGeneration.get()} ожидалось=$generation " +
                "startFlowActive=$isStartFlowActive " +
                "manualStopSuppressed=${isManualStopUiSuppressed()} " +
                "transientPending=${clientData.isTransientConnectingPending()} " +
                "softReapplyPending=${clientData.isSoftReapplyPending()} " +
                "serviceRunning=${isNovaVpnServiceRunning()}"
        )
    }

    private fun cancelStartFlow() {
        if (isStartFlowActive) {
            try {
                Thread({
                    try {
                        Nova.cancelRegisterWarp()
                    } catch (_: Throwable) {
                    }
                }, "NovaCancelRegister").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: Throwable) {
            }
            isStartFlowActive = false
            startFlowGeneration.incrementAndGet()
        }
        startFlowHandedToService = false
        pendingVpnPermissionFlowGeneration = null
        pendingStatusText = "ПОДКЛЮЧЕНИЕ..."
    }

    private fun lockPrimaryActionFor(durationMs: Long) {
        val now = SystemClock.elapsedRealtime()
        primaryActionLockedUntilMs = maxOf(primaryActionLockedUntilMs, now + durationMs)
        applyPrimaryActionInterlock()
        statusHandler.removeCallbacks(primaryActionUnlockRunnable)
        statusHandler.postDelayed(primaryActionUnlockRunnable, durationMs)
    }

    private fun applyPrimaryActionInterlock() {
        if (!::btnConnect.isInitialized) return
        btnConnect.isEnabled = SystemClock.elapsedRealtime() >= primaryActionLockedUntilMs
    }

    private fun shouldTreatPrimaryActionAsStop(): Boolean {
        return isWarpDiscoveryActive() ||
            isStartFlowActive ||
            isTunnelConnected() ||
            clientData.getServiceState() != NovaVpnService.STATE_STOPPED
    }

    private fun showPrimaryActionPreview() {
        if (!::btnConnect.isInitialized) return
        primaryActionPreviewActive = true
        btnConnect.text = if (shouldTreatPrimaryActionAsStop()) {
            if (isWarpDiscoveryActive()) "ОСТАНОВИТЬ" else "ОТКЛЮЧЕНИЕ..."
        } else {
            "ПОДКЛЮЧЕНИЕ..."
        }
    }

    private fun resetLatencyDisplay() {
        if (!::latencyGraph.isInitialized) return
        lastLatencyRefreshAtMs = 0L
        lastMeasuredLatencyMs = -1
        latencyGraph.clearLatencies()
        val labelView = findViewById<TextView>(R.id.tv_internet_label)
        labelView.text = "Ping:\n---"
        labelView.setTextColor(android.graphics.Color.GRAY)
    }

    private fun clearPrimaryActionPreview() {
        if (!primaryActionPreviewActive || !::btnConnect.isInitialized) return
        primaryActionPreviewActive = false
        btnConnect.text = if (
            !isWarpDiscoveryActive() &&
            vpnState == NovaVpnService.STATE_STOPPED &&
            !isStartFlowActive &&
            !clientData.isTransientConnectingPending() &&
            !clientData.isSoftReapplyPending()
        ) {
            "ПОДКЛЮЧИТЬ"
        } else {
            currentPrimaryStopActionLabel()
        }
    }

    private fun isAdaptationMessage(message: String): Boolean {
        val normalized = message.lowercase(Locale.getDefault())
        return normalized.contains("адаптац") || normalized.contains("data-plane")
    }

    private fun refreshWarpDiscoverySnapshotFromStorage() {
        warpDiscoverySnapshot = clientData.getWarpDiscoverySnapshot()?.let { snapshot ->
            if (snapshot.running && !isNovaVpnServiceRunning()) {
                val staleForMs = (System.currentTimeMillis() - snapshot.observedAt).coerceAtLeast(0L)
                if (staleForMs >= 3_500L) {
                    snapshot.copy(running = false)
                } else {
                    snapshot
                }
            } else {
                snapshot
            }
        }
    }

    private fun isWarpDiscoveryActive(): Boolean {
        refreshWarpDiscoverySnapshotFromStorage()
        return warpDiscoverySnapshot?.running == true
    }

    private fun currentPrimaryStopActionLabel(): String {
        return if (isWarpDiscoveryActive()) "ОСТАНОВИТЬ" else "ОТКЛЮЧИТЬ"
    }

    private fun updatePendingStartStatus(text: String, generation: Int) {
        pendingStatusText = text
        clientData.markTransientConnectingPending(START_FLOW_TRANSIENT_PENDING_MS)
        runOnUiThread {
            if (isStartFlowCurrent(generation)) {
                renderStartFlowState()
            }
        }
    }

    private fun ensureStartFlowExecutorReady() {
        if (startFlowExecutor.isShutdown || startFlowExecutor.isTerminated) {
            LogManager.log("Start-flow executor был остановлен. Пересоздаём новый single-thread executor.")
            startFlowExecutor = Executors.newSingleThreadExecutor()
        }
    }

    private fun measureLatencyViaOperaProxy(timeoutMs: Int): Int {
        val startedAt = System.currentTimeMillis()
        // Оба набора — Cloudflare, просто разные входы: чередование нужно, чтобы не
        // долбить один адрес, а не для того, чтобы опрашивать разных поставщиков.
        val probes = if (usePrimaryLatencyServer) {
            listOf(
                CloudflareTrace.IPV4_HOSTS[0] to CloudflareTrace.PATH,
                "www.cloudflare.com" to CloudflareTrace.PATH,
            )
        } else {
            listOf(
                CloudflareTrace.IPV4_HOSTS[1] to CloudflareTrace.PATH,
                "one.one.one.one" to CloudflareTrace.PATH,
            )
        }
        for ((host, path) in probes) {
            val body = readTextViaOperaProxySocket(host, path, timeoutMs = timeoutMs)
            if (!body.isNullOrBlank()) {
                return (System.currentTimeMillis() - startedAt).toInt()
            }
        }
        return try {
            val proxy = Proxy(Proxy.Type.HTTP, OperaProxyManager.getLoopbackProxyAddress(this))
            val fallbackUrls = if (usePrimaryLatencyServer) {
                CloudflareTrace.IPV4_URLS
            } else {
                CloudflareTrace.IPV4_URLS.reversed()
            }
            for (url in fallbackUrls) {
                if (!readTextFromUrlViaProxy(proxy, url, timeoutMs).isNullOrBlank()) {
                    return (System.currentTimeMillis() - startedAt).toInt()
                }
            }
            usePrimaryLatencyServer = !usePrimaryLatencyServer
            -1
        } catch (_: Exception) {
            usePrimaryLatencyServer = !usePrimaryLatencyServer
            -1
        }
    }

    private fun sleepWithCancellation(totalMs: Long, generation: Int): Boolean {
        var remainingMs = totalMs
        while (remainingMs > 0L) {
            if (!isStartFlowCurrent(generation)) return false
            val chunkMs = minOf(remainingMs, 250L)
            try {
                Thread.sleep(chunkMs)
            } catch (_: Exception) {
                return false
            }
            remainingMs -= chunkMs
        }
        return isStartFlowCurrent(generation)
    }

    private fun renderStartFlowState(restartBackdrop: Boolean = false) {
        renderConnectingState(
            statusText = pendingStatusText.ifBlank { buildConnectingStatusText() },
            restartBackdrop = restartBackdrop,
        )
    }

    private fun resetAttemptProgressTracking() {
        displayedAttemptOrdinal = 0
        displayedAttemptTotal = 0
        lastRawAttemptOrdinal = 0
        lastRawAttemptTotal = 0
        manualProfileSwitchProgressHoldUntilMs = 0L
    }

    private fun isManualProfileSwitchProgressHeld(): Boolean {
        return SystemClock.elapsedRealtime() < manualProfileSwitchProgressHoldUntilMs &&
            currentAttemptTotal > 0 &&
            currentAttemptOrdinal > 0
    }

    // Заглушки знаменателя по длине списка профилей удалены намеренно.
    //
    // Они считали не то, что перебирает служба: экран брал все импортированные
    // записи, а очередь строится с фильтром по выбранному протоколу и ограничением
    // размера — «1/20» через долю секунды превращалось в «1/8». Длину перебора
    // объявляет служба до первой попытки; пока она молчит, честный ответ — «...».

    private fun acceptAttemptProgress(
        rawOrdinal: Int,
        rawTotal: Int,
        displayTotalOverride: Int = 0,
    ) {
        if (isManualProfileSwitchProgressHeld()) return
        val normalizedTotal = (displayTotalOverride.takeIf { it > 0 } ?: rawTotal).coerceAtLeast(0)
        val normalizedOrdinal = rawOrdinal.coerceAtLeast(0).coerceAtMost(
            normalizedTotal.takeIf { it > 0 } ?: Int.MAX_VALUE,
        )
        if (normalizedTotal <= 0) {
            return
        }

        if (displayedAttemptTotal <= 0) {
            displayedAttemptTotal = normalizedTotal
            displayedAttemptOrdinal = if (normalizedOrdinal > 0) {
                normalizedOrdinal.coerceIn(1, normalizedTotal)
            } else {
                0
            }
            lastRawAttemptOrdinal = normalizedOrdinal
            lastRawAttemptTotal = normalizedTotal
            return
        }

        if (displayedAttemptTotal != normalizedTotal) {
            displayedAttemptTotal = normalizedTotal
            displayedAttemptOrdinal = when {
                displayedAttemptOrdinal > 0 -> displayedAttemptOrdinal.coerceAtMost(normalizedTotal)
                normalizedOrdinal > 0 -> 1
                else -> 0
            }
            lastRawAttemptOrdinal = normalizedOrdinal
            lastRawAttemptTotal = normalizedTotal
            return
        }

        val freshCycleReset =
            normalizedOrdinal <= 1 &&
                normalizedTotal in 1 until displayedAttemptTotal &&
                (
                    displayedAttemptTotal >= 32 ||
                        normalizedTotal <= displayedAttemptTotal / 2
                    )

        if (freshCycleReset) {
            displayedAttemptTotal = normalizedTotal
            displayedAttemptOrdinal = normalizedOrdinal.coerceIn(1, normalizedTotal)
            lastRawAttemptOrdinal = normalizedOrdinal
            lastRawAttemptTotal = normalizedTotal
            return
        }

        val phaseLocalReset =
            normalizedOrdinal in 1..2 &&
                lastRawAttemptOrdinal > normalizedOrdinal &&
                normalizedTotal in 1 until displayedAttemptTotal

        if (phaseLocalReset) {
            val phaseBase = displayedAttemptOrdinal.coerceAtLeast(0)
            val candidateTotal = phaseBase + normalizedTotal
            if (candidateTotal > displayedAttemptTotal) {
                displayedAttemptTotal = candidateTotal
            }
            val candidateOrdinal = (phaseBase + normalizedOrdinal)
                .coerceAtMost(displayedAttemptTotal)
            if (candidateOrdinal > displayedAttemptOrdinal) {
                displayedAttemptOrdinal = minOf(candidateOrdinal, displayedAttemptOrdinal + 1)
            }
        } else {
            if (normalizedTotal > displayedAttemptTotal) {
                displayedAttemptTotal = normalizedTotal
            }
            if (normalizedOrdinal > displayedAttemptOrdinal) {
                displayedAttemptOrdinal = minOf(
                    normalizedOrdinal.coerceAtMost(displayedAttemptTotal),
                    displayedAttemptOrdinal + 1,
                )
            }
        }

        lastRawAttemptOrdinal = normalizedOrdinal
        lastRawAttemptTotal = normalizedTotal
    }

    private fun buildConnectingStatusText(): String {
        resolveImportedUiBackendLabel()?.let { importedBackend ->
            return "ПОДКЛЮЧЕНИЕ... $importedBackend"
        }
        val preference = clientData.getExitRegionPreference().trim().lowercase()
        // Названный пользователем выход идёт раньше остаточного бэкенда: тот остаётся
        // от прошлой сессии, и подключение к VLESS подписывалось «EU» от недавнего
        // сеанса Opera. Ветка Opera ниже нужна режиму «Авто», где регион не назван и
        // определяется тем, что реально поднимается.
        return when {
            preference == "ru" -> "ПОДКЛЮЧЕНИЕ... WARP"
            preference == "eu" -> "ПОДКЛЮЧЕНИЕ... EU"
            preference == "us" -> "ПОДКЛЮЧЕНИЕ... US"
            preference == "masque" -> "ПОДКЛЮЧЕНИЕ... MASQUE"
            preference == "vless" -> "ПОДКЛЮЧЕНИЕ... VLESS"
            isOperaBackend(currentTunnelBackend) -> {
                val normalized = currentTunnelBackend.trim().uppercase()
                val region = when {
                    normalized.startsWith("${NovaVpnService.BACKEND_OPERA}-") ->
                        normalized.substringAfter('-').ifBlank { "EU" }
                    normalized.startsWith("${NovaVpnService.BACKEND_OPERA}:") ->
                        normalized.substringAfter(':').trim().ifBlank { "EU" }
                    else -> "EU"
                }
                "ПОДКЛЮЧЕНИЕ... $region"
            }
            else -> "ПОДКЛЮЧЕНИЕ... AUTO"
        }
    }

    private fun prepareVpn(existingFlowGeneration: Int? = null) {
        val foreignVpnActive = findCurrentVpnNetwork() != null &&
            !isStartFlowActive &&
            getPersistedServiceState() == NovaVpnService.STATE_STOPPED
        val intent = VpnService.prepare(this)
        if (intent != null) {
            if (foreignVpnActive) {
                LogManager.log("Обнаружен другой активный VPN. Android попросит заменить его на Nova.")
            }
            pendingVpnPermissionFlowGeneration = existingFlowGeneration
            if (!VpnConsent.request(vpnPermissionLauncher, intent)) {
                pendingVpnPermissionFlowGeneration = null
                cancelStartFlow()
                markServiceStoppedLocally()
                renderVpnConsentUnavailableState()
                Toast.makeText(this, VpnConsent.UNAVAILABLE_HINT, Toast.LENGTH_LONG).show()
            }
        } else {
            registerAndStart(existingFlowGeneration)
        }
    }

    private fun registerAndStart(existingFlowGeneration: Int? = null) {
        val flowGeneration = when {
            existingFlowGeneration != null && isStartFlowCurrent(existingFlowGeneration) -> existingFlowGeneration
            isStartFlowActive -> startFlowGeneration.get()
            else -> beginStartFlow(buildConnectingStatusText())
        }
        if (!isStartFlowCurrent(flowGeneration)) return
        clientData.clearRestartSession()
        clientData.setTrafficMaskActiveHost(null)
        clientData.setWarpTrafficMaskActiveHost(null)
        resetAttemptProgressTracking()
        currentTunnelBackend = if (!clientData.shouldUseWarpTransport() && clientData.shouldAllowOperaTransport()) {
            "${NovaVpnService.BACKEND_OPERA}-${clientData.getPreferredOperaLabel()}"
        } else {
            NovaVpnService.BACKEND_WARP
        }
        seedConnectingAttemptProgress(currentTunnelBackend)
        pendingStatusText = buildConnectingStatusText()
        LogManager.log("Подготовка к подключению...")

        val warpAllowed = clientData.shouldUseWarpTransport()
        val operaAllowed = clientData.shouldAllowOperaTransport()
        if (!warpAllowed && operaAllowed) {
            if (!OperaProxyManager.isSupportedOnDevice(this)) {
                markServiceStoppedLocally()
                cancelStartFlow()
                LogManager.log(
                    "Opera-only режим недоступен: для ABI ${Build.SUPPORTED_ABIS.joinToString()} нет native-библиотек Opera/tun2proxy."
                )
                runOnUiThread {
                    Toast.makeText(
                        this@MainActivity,
                        "EU/US недоступны на этом устройстве: встроенный Opera runtime не поддерживается.",
                        Toast.LENGTH_LONG
                    ).show()
                    tvStatus.text = "ОШИБКА РЕГИОНА"
                    btnConnect.isEnabled = true
                    btnConnect.text = "ПОВТОРИТЬ"
                }
                return
            }
            LogManager.log(
                "Выбран Opera-only режим (${clientData.getPreferredOperaLabel()}). " +
                    "Регистрацию и подключение WARP полностью пропускаем."
            )
            runOnUiThread {
                if (!isStartFlowCurrent(flowGeneration)) return@runOnUiThread
                try {
                    val backendLabel = "${NovaVpnService.BACKEND_OPERA}-${clientData.getPreferredOperaLabel()}"
                    currentTunnelBackend = backendLabel
                    markServiceConnectingLocally(backendLabel)
                    updateUiByState(NovaVpnService.STATE_CONNECTING)
                    val intent = Intent(this@MainActivity, NovaVpnService::class.java).apply {
                        action = NovaVpnService.ACTION_START_OPERA_ONLY
                        applyCurrentPreferenceExtras(this)
                    }
                    startExplicitVpnService(intent)
                } catch (e: Exception) {
                    markServiceStoppedLocally()
                    cancelStartFlow()
                    LogManager.log("Ошибка запуска Opera-only сервиса: ${e.message}")
                    tvStatus.text = "ОШИБКА"
                    btnConnect.isEnabled = true
                    btnConnect.text = "ПОВТОРИТЬ"
                }
            }
            return
        }

        ensureStartFlowExecutorReady()
        try {
            startFlowExecutor.execute {
            try {
                val warpClient = WarpClient(
                    applicationContext,
                    { LogManager.log(it) },
                    { !isStartFlowCurrent(flowGeneration) }
                )
                if (!isStartFlowCurrent(flowGeneration)) {
                    logStartFlowAborted("подготовка WARP-клиента", flowGeneration)
                    return@execute
                }

                val resolvedConfig = clientData.resolveWarpConfigForReuse(repairWithBootstrap = true)
                var config = resolvedConfig?.config

                if (config != null) {
                    if (resolvedConfig?.persisted == false) {
                        clientData.saveConfig(config!!)
                        LogManager.log(
                            "Восстановили WARP identity из ${resolvedConfig.source} " +
                                "и сохранили её как основной профиль, чтобы не регистрироваться повторно."
                        )
                    }
                    when (resolvedConfig?.source) {
                        "restart-session" -> LogManager.log(
                            "Сохранённый WARP-профиль отсутствовал. " +
                                "Восстановили WARP identity из restart session и запускаем VPN без новой регистрации."
                        )
                        "pending-bootstrap-restart" -> LogManager.log(
                            "Сохранённый WARP-профиль отсутствовал. " +
                                "Используем pending bootstrap restart-сеанс и запускаем VPN без новой регистрации."
                        )
                        "bootstrap-seed" -> LogManager.log(
                            "Сохранённый WARP-профиль отсутствовал. " +
                                "Восстановили bootstrap-конфигурацию из release seed и запускаем VPN без новой регистрации."
                        )
                        else -> LogManager.log("Используем сохранённую конфигурацию устройства.")
                    }
                    updatePendingStartStatus(buildConnectingStatusText(), flowGeneration)

                    val needsMasqueBootstrapRefresh =
                        config?.accessToken.isNullOrBlank() || config?.deviceId.isNullOrBlank()

                    if (needsMasqueBootstrapRefresh) {
                        val hasCachedMasqueIdentity = !clientData.getMasqueConfigJson().isNullOrBlank()
                        when {
                            hasCachedMasqueIdentity -> {
                                LogManager.log(
                                    "В сохранённой конфигурации нет token/id, но cached MASQUE identity уже есть. " +
                                        "Не блокируем старт повторной регистрацией."
                                )
                            }
                            clientData.shouldRetryMasqueBootstrap() -> {
                                LogManager.log(
                                    "В сохранённой конфигурации нет token/id для MASQUE. " +
                                        "Первое подключение не блокируем повторной регистрацией: " +
                                        "сразу запускаем VPN через доступные WARP fallback-пути."
                                )
                            }
                            else -> {
                                LogManager.log(
                                    "MASQUE bootstrap недавно уже падал. " +
                                        "Повторную регистрацию перед стартом пропускаем, сразу запускаем VPN."
                                )
                            }
                        }
                    }
                } else {
                    // Нет сохранённой конфигурации — нужна самостоятельная регистрация устройства
                    updatePendingStartStatus("РЕГИСТРАЦИЯ...", flowGeneration)
                    LogManager.log("Сохранённого WARP-профиля нет. Регистрируем новое устройство...")
                    config = warpClient.register(
                        onProgress = { progress ->
                            updatePendingStartStatus("РЕГИСТРАЦИЯ... $progress%", flowGeneration)
                        }
                    )
                    var attempt = 1
                    val maxAttempts = 3

                    // Автоматические повторы если регистрация не прошла
                    while (config == null && attempt < maxAttempts && isStartFlowCurrent(flowGeneration)) {
                        attempt++
                        LogManager.log("Повтор регистрации ($attempt/$maxAttempts)...")
                        updatePendingStartStatus("РЕГИСТРАЦИЯ ($attempt/$maxAttempts)...", flowGeneration)
                        if (!sleepWithCancellation(3000, flowGeneration)) {
                            logStartFlowAborted("пауза между попытками регистрации", flowGeneration)
                            return@execute
                        }
                        config = warpClient.register(
                            onProgress = { progress ->
                                updatePendingStartStatus("РЕГИСТРАЦИЯ ($attempt/$maxAttempts)... $progress%", flowGeneration)
                            },
                            attemptVariant = attempt - 1,
                        )
                    }

                    if (config != null && isStartFlowCurrent(flowGeneration)) {
                        clientData.saveConfig(config!!)
                    }
                }

                if (!isStartFlowCurrent(flowGeneration)) {
                    logStartFlowAborted("конфигурация готова, отправка службе", flowGeneration)
                    return@execute
                }
                runOnUiThread {
                    if (!isStartFlowCurrent(flowGeneration)) {
                        logStartFlowAborted("выход на главный поток", flowGeneration)
                        return@runOnUiThread
                    }
                    if (config != null) {
                        val currentPort = clientData.getLastSuccessPort()
                        val currentProtocol = clientData.getLastSuccessProtocol()

                        try {
                            pendingStatusText = buildConnectingStatusText()
                            currentTunnelBackend = NovaVpnService.BACKEND_WARP
                            markServiceConnectingLocally(NovaVpnService.BACKEND_WARP)
                            updateUiByState(NovaVpnService.STATE_CONNECTING)
                            val intent = Intent(this@MainActivity, NovaVpnService::class.java).apply {
                                putExtra("PRIVATE_KEY", config!!.privateKey)
                                putExtra("IPV4", config!!.ipv4)
                                putExtra("IPV6", config!!.ipv6)
                                putExtra("PEER_PUB", config!!.peerPublicKey)
                                putExtra("PEER_ENDPOINT", config!!.peerEndpoint)
                                putExtra("RESERVED", config!!.reserved)
                                putExtra("PORT", currentPort)
                                putExtra("PROTOCOL", currentProtocol)
                                applyCurrentPreferenceExtras(this)
                            }
                            startExplicitVpnService(intent)
                        } catch (e: Exception) {
                            markServiceStoppedLocally()
                            cancelStartFlow()
                            LogManager.log("Ошибка запуска VPN-сервиса: ${e.message}")
                            tvStatus.text = "ОШИБКА"
                            btnConnect.isEnabled = true
                            btnConnect.text = "ПОВТОРИТЬ"
                        }
                    } else {
                        val exitPreference = clientData.getExitRegionPreference().trim().lowercase()
                        if (exitPreference == "auto" && clientData.shouldAllowOperaTransport()) {
                            if (!OperaProxyManager.isSupportedOnDevice(this@MainActivity)) {
                                markServiceStoppedLocally()
                                cancelStartFlow()
                                LogManager.log(
                                    "Регистрация WARP в AUTO не удалась, но Opera fallback недоступен " +
                                        "для ABI ${Build.SUPPORTED_ABIS.joinToString()}."
                                )
                                Toast.makeText(
                                    this@MainActivity,
                                    "На этом устройстве доступен только WARP: встроенный Opera runtime недоступен.",
                                    Toast.LENGTH_LONG
                                ).show()
                                tvStatus.text = "ОШИБКА РЕГИСТРАЦИИ"
                                btnConnect.isEnabled = true
                                btnConnect.text = "ПОВТОРИТЬ"
                                return@runOnUiThread
                            }
                            LogManager.log("Регистрация WARP в AUTO не удалась. Переходим к Opera fallback: EU -> US.")
                            cancelStartFlow()
                            try {
                                val backendLabel = "${NovaVpnService.BACKEND_OPERA}-${clientData.getPreferredOperaLabel()}"
                                currentTunnelBackend = backendLabel
                                markServiceConnectingLocally(backendLabel)
                                pendingStatusText = buildConnectingStatusText()
                                updateUiByState(NovaVpnService.STATE_CONNECTING)
                                val intent = Intent(this@MainActivity, NovaVpnService::class.java).apply {
                                    action = NovaVpnService.ACTION_START_OPERA_ONLY
                                    applyCurrentPreferenceExtras(this)
                                }
                                startExplicitVpnService(intent)
                            } catch (e: Exception) {
                                markServiceStoppedLocally()
                                tvStatus.text = "ОШИБКА"
                                btnConnect.isEnabled = true
                                btnConnect.text = "ПОВТОРИТЬ"
                                LogManager.log("Ошибка запуска Opera fallback после неудачной регистрации: ${e.message}")
                            }
                        } else {
                            cancelStartFlow()
                            tvStatus.text = "ОШИБКА РЕГИСТРАЦИИ"
                            btnConnect.isEnabled = true
                            btnConnect.text = "ПОВТОРИТЬ"
                        }
                    }
                }
            } catch (t: Throwable) {
                val origin = t.stackTrace.firstOrNull()?.let { trace ->
                    " @ ${trace.className}.${trace.methodName}:${trace.lineNumber}"
                }.orEmpty()
                LogManager.log(
                    "Критическая ошибка start-flow: ${t::class.java.simpleName}: ${t.message ?: "без сообщения"}$origin"
                )
                runOnUiThread {
                    markServiceStoppedLocally()
                    cancelStartFlow()
                    tvStatus.text = "ОШИБКА"
                    btnConnect.isEnabled = true
                    btnConnect.text = "ПОВТОРИТЬ"
                }
            }
            }
        } catch (t: Throwable) {
            LogManager.log("Не удалось отправить задачу в start-flow executor: ${t::class.java.simpleName}: ${t.message ?: "без сообщения"}")
            markServiceStoppedLocally()
            cancelStartFlow()
            tvStatus.text = "ОШИБКА"
            btnConnect.isEnabled = true
            btnConnect.text = "ПОВТОРИТЬ"
        }
    }

    private fun stopVpn() {
        if (isWarpDiscoveryActive()) {
            stopWarpDiscoveryFromMain()
            return
        }
        val hadOnlyLocalStartFlow = isStartFlowActive &&
            getPersistedServiceState() == NovaVpnService.STATE_STOPPED
        cancelStartFlow()
        cancelDoomedProcessRestart("пользователь остановил VPN")
        noteManualStopUiSuppression()
        clientData.clearTransientConnectingPending()
        clientData.clearRestartSession()
        LogManager.log("Остановка запрошена пользователем.")
        if (hadOnlyLocalStartFlow) {
            markServiceStoppedLocally()
            updateUiByState(NovaVpnService.STATE_STOPPED)
            return
        }
        if (getPersistedServiceState() != NovaVpnService.STATE_STOPPED) {
            markServiceStoppedLocally()
            currentTunnelBackend = NovaVpnService.BACKEND_WARP
            updateUiByState(NovaVpnService.STATE_STOPPED)
            startService(Intent(this, NovaVpnService::class.java).apply { action = "STOP_VPN" })
        } else {
            markServiceStoppedLocally()
            updateUiByState(NovaVpnService.STATE_STOPPED)
        }
    }

    private fun stopWarpDiscoveryFromMain() {
        cancelStartFlow()
        clientData.clearTransientConnectingPending()
        clientData.clearSoftReapplyPending()
        val snapshot = warpDiscoverySnapshot
        btnConnect.text = "ОСТАНОВКА..."
        applyPrimaryActionInterlock()
        warpDiscoverySnapshot = snapshot?.copy(
            running = true,
            message = if (isAdaptationMessage(snapshot.message)) {
                "Адаптация останавливается..."
            } else {
                "Проверка WARP останавливается..."
            },
            observedAt = System.currentTimeMillis(),
        ) ?: WarpDiscoverySnapshot(
            running = true,
            foundCount = clientData.getWarpVerifiedConfigs().count(clientData::isBundledSeed),
            message = "Остановка проверки...",
            ordinal = 0,
            total = 0,
            observedAt = System.currentTimeMillis(),
        )
        updateUiByState(clientData.getServiceState())
        try {
            startService(Intent(this, NovaVpnService::class.java).apply {
                action = NovaVpnService.ACTION_STOP_WARP_CONFIG_DISCOVERY
            })
        } catch (_: Exception) {
        }
    }

    private fun updateUiByState(state: String?) {
        refreshWarpDiscoverySnapshotFromStorage()
        val rawPersistedState = state ?: getPersistedServiceState()
        val persistedState = resolvePersistedStateAgainstSystemVpn(rawPersistedState)
        val softReapplyPending = persistedState == NovaVpnService.STATE_STOPPED && clientData.isSoftReapplyPending()
        val previousState = vpnState
        val discoverySnapshot = warpDiscoverySnapshot
        val discoveryRunning = discoverySnapshot?.running == true
        val transientReconnectPending =
            persistedState == NovaVpnService.STATE_STOPPED &&
                !softReapplyPending &&
                shouldBridgeStoppedToConnecting(previousState)
        val resolvedState = if (softReapplyPending || transientReconnectPending) {
            NovaVpnService.STATE_CONNECTING
        } else {
            persistedState
        }
        // Пока intent не ушёл службе, никакой STOPPED не может относиться к этому
        // пуску: сообщать о нём попросту нечему. Значит это хвост предыдущей
        // остановки — той самой, поверх которой пользователь и нажал «Пуск».
        // Без этой оговорки хвост звал cancelStartFlow, поколение уезжало, и цикл
        // умирал молча между «Подготовка к подключению...» и отправкой intent'а.
        // У службы такая защита давно есть, у экрана не было.
        val startFlowAwaitingHandoff = isStartFlowActive && !startFlowHandedToService
        val hasPendingLocalStart = isStartFlowActive &&
            (rawPersistedState == NovaVpnService.STATE_STOPPED || startFlowAwaitingHandoff)
        if (hasPendingLocalStart) {
            vpnState = NovaVpnService.STATE_CONNECTING
            renderStartFlowState()
            requestQuickTileRefresh(vpnState)
            return
        }

        vpnState = resolvedState.ifBlank { NovaVpnService.STATE_STOPPED }
        if (vpnState == NovaVpnService.STATE_CONNECTED && !hasLiveNovaVpn()) {
            vpnState = if (
                isNovaVpnServiceRunning() &&
                clientData.getRestartSession() != null &&
                !isRecentLocalStop()
            ) {
                NovaVpnService.STATE_CONNECTING
            } else {
                markServiceStoppedLocally()
                NovaVpnService.STATE_STOPPED
            }
        }
        if (vpnState == NovaVpnService.STATE_CONNECTING) {
            currentTunnelBackend = getPersistedServiceBackend().ifBlank { currentTunnelBackend }
            val persistedAttemptOrdinal = clientData.getServiceAttemptOrdinal()
            val persistedAttemptTotal = clientData.getServiceAttemptTotal()
            val shouldResetConnectingProgress =
                (previousState != NovaVpnService.STATE_CONNECTING && !clientData.isSoftReapplyPending()) ||
                    discoveryRunning != lastRenderedDiscoveryRunning ||
                    (
                        persistedAttemptTotal <= 0 &&
                            displayedAttemptTotal > 0 &&
                            !isManualProfileSwitchProgressHeld()
                        )
            if (shouldResetConnectingProgress) {
                resetAttemptProgressTracking()
            }
            val holdManualProfileSwitchProgress =
                !discoveryRunning &&
                    isManualProfileSwitchProgressHeld()
            currentAttemptOrdinal = if (discoveryRunning) {
                maxOf(discoverySnapshot?.ordinal ?: 0, persistedAttemptOrdinal)
            } else if (holdManualProfileSwitchProgress) {
                currentAttemptOrdinal
            } else {
                persistedAttemptOrdinal
            }
            currentAttemptTotal = if (discoveryRunning) {
                maxOf(discoverySnapshot?.total ?: 0, persistedAttemptTotal)
            } else if (holdManualProfileSwitchProgress) {
                currentAttemptTotal
            } else {
                persistedAttemptTotal
            }
            if (currentAttemptTotal > 0) {
                acceptAttemptProgress(currentAttemptOrdinal, currentAttemptTotal)
            }
            if ((softReapplyPending || transientReconnectPending) && pendingStatusText.isBlank()) {
                pendingStatusText = buildConnectingStatusText()
            }
        }

        val keepLocalStartPendingOnSyntheticConnecting =
            vpnState == NovaVpnService.STATE_CONNECTING &&
                isStartFlowActive &&
                clientData.isTransientConnectingPending() &&
                !isNovaVpnServiceRunning()

        if ((vpnState == NovaVpnService.STATE_CONNECTING || vpnState == NovaVpnService.STATE_CONNECTED) &&
            !keepLocalStartPendingOnSyntheticConnecting
        ) {
            if (vpnState == NovaVpnService.STATE_CONNECTED) {
                clientData.clearSoftReapplyPending()
            }
            cancelStartFlow()
        } else if (vpnState == NovaVpnService.STATE_STOPPED && !hasPendingLocalStart) {
            cancelStartFlow()
        }

        when {
            discoveryRunning -> renderWarpDiscoveryState(discoverySnapshot!!)
            vpnState == NovaVpnService.STATE_CONNECTING -> renderConnectingState(buildConnectingStatusText())
            vpnState == NovaVpnService.STATE_CONNECTED -> {
                currentTunnelBackend = resolveConnectedUiBackend()
                val preserveKnownIps =
                    previousState == NovaVpnService.STATE_CONNECTED &&
                        (currentIpv4 != "..." || currentIpv6 != "..." || currentCountry != "--")
                val shouldAnimateBackdrop =
                    backdropState != BackdropState.CONNECTED ||
                        tronBackdrop.visibility == View.VISIBLE ||
                        ivBackgroundArt.alpha < 0.999f
                renderConnectedState(
                    preserveKnownIps = preserveKnownIps,
                    animateBackdrop = shouldAnimateBackdrop
                )
            }
            vpnState == NovaVpnService.STATE_STOPPED -> {
                currentTunnelBackend = NovaVpnService.BACKEND_WARP
                renderStoppedState()
            }
            else -> {
                currentTunnelBackend = NovaVpnService.BACKEND_WARP
                renderStoppedState()
            }
        }

        lastRenderedDiscoveryRunning = discoveryRunning
        requestQuickTileRefresh(vpnState)
        refreshKeepScreenAwake()

    }

    private fun renderWarpDiscoveryState(snapshot: WarpDiscoverySnapshot) {
        releaseVpnNetwork()
        setBackdropConnecting(forceRestart = backdropState != BackdropState.CONNECTING)
        clientData.clearTunnelUiSnapshot()
        connectedUiAwaitingProof = false
        primaryActionPreviewActive = false
        btnConnect.text = "ОСТАНОВИТЬ"
        tunnelIpResolved = false
        currentIpv4 = "..."
        currentIpv6 = "..."
        currentCountry = "--"
        ipv4Candidate.value = ""
        ipv4Candidate.seenCount = 0
        ipv6Candidate.value = ""
        ipv6Candidate.seenCount = 0
        currentAttemptOrdinal = snapshot.ordinal.coerceAtLeast(0)
        currentAttemptTotal = snapshot.total.coerceAtLeast(0)
        applyStatusStyle(
            text = if (isAdaptationMessage(snapshot.message)) "АДАПТАЦИЯ" else "ПРОВЕРКА WARP",
            textColor = Color.parseColor("#C99514"),
            textGlowColor = Color.parseColor("#F1C64A"),
        )
        updateAttemptProgressDisplay()
        resetLatencyDisplay()
        updateIpDisplay()
        applyPrimaryActionInterlock()
    }

    private fun refreshKeepScreenAwake() {
        val discoveryRunning = warpDiscoverySnapshot?.running == true
        val shouldKeepAwake =
            isActivityResumed &&
                (
                    vpnState == NovaVpnService.STATE_CONNECTING ||
                        clientData.getServiceState() == NovaVpnService.STATE_CONNECTING ||
                        discoveryRunning
                    )
        if (shouldKeepAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun renderConnectingState(
        statusText: String = "ПОДКЛЮЧЕНИЕ...",
        restartBackdrop: Boolean = false,
    ) {
        releaseVpnNetwork()
        setBackdropConnecting(forceRestart = restartBackdrop)
        clientData.clearTunnelUiSnapshot()
        connectedUiAwaitingProof = false
        primaryActionPreviewActive = false
        btnConnect.text = "ОТКЛЮЧИТЬ"
        btnNextProfile.visibility = View.GONE
        tunnelIpResolved = false
        currentIpv4 = "..."
        currentIpv6 = "..."
        currentCountry = "--"
        ipv4Candidate.value = ""
        ipv4Candidate.seenCount = 0
        ipv6Candidate.value = ""
        ipv6Candidate.seenCount = 0
        if (!applyDeviceRegistrationStatusIfActive()) {
            applyStatusStyle(
                text = statusText,
                textColor = Color.parseColor("#C99514"),
                textGlowColor = Color.parseColor("#F1C64A"),
            )
        }
        updateAttemptProgressDisplay()
        resetLatencyDisplay()
        updateIpDisplay()
        applyPrimaryActionInterlock()
    }

    private fun renderConnectedPendingProofState(
        preserveKnownIps: Boolean = false,
    ) {
        manualStopUiSuppressedUntilMs = 0L
        connectedUiAwaitingProof = true
        primaryActionPreviewActive = false
        setBackdropConnecting(forceRestart = backdropState != BackdropState.CONNECTING)
        missingVpnSinceMs = 0L
        btnConnect.text = "ОТКЛЮЧИТЬ"
        btnNextProfile.visibility = View.GONE
        if (!preserveKnownIps) {
            tunnelIpResolved = false
            currentIpv4 = "..."
            currentIpv6 = "..."
            currentCountry = "--"
        }
        if (!applyDeviceRegistrationStatusIfActive()) {
            applyStatusStyle(
                text = "ПРОВЕРКА ТУННЕЛЯ...",
                textColor = Color.parseColor("#C99514"),
                textGlowColor = Color.parseColor("#F1C64A"),
            )
        }
        updateAttemptProgressDisplay()
        requestVpnNetwork()
        updateIpDisplay()
        checkCurrentIp()
        measureLatency()
        applyPrimaryActionInterlock()
    }

    private fun renderConnectedState(
        preserveKnownIps: Boolean = false,
        animateBackdrop: Boolean = true,
    ) {
        manualStopUiSuppressedUntilMs = 0L
        primaryActionPreviewActive = false
        if (!connectedUiAwaitingProof || lastTunnelConnectedAtMs == 0L) {
            lastTunnelConnectedAtMs = SystemClock.elapsedRealtime()
        }
        missingVpnSinceMs = 0L
        ipv4Candidate.value = ""
        ipv4Candidate.seenCount = 0
        ipv6Candidate.value = ""
        ipv6Candidate.seenCount = 0
        if (!preserveKnownIps) {
            currentTunnelBackend = resolveUiBackend()
            currentIpv4 = "..."
            currentIpv6 = "..."
            currentCountry = "--"
            tunnelIpResolved = false
        }
        if (!hasConnectedUiProof()) {
            renderConnectedPendingProofState(
                preserveKnownIps = preserveKnownIps &&
                    (currentIpv4 != "..." || currentIpv6 != "..." || currentCountry != "--"),
            )
            return
        }
        if (animateBackdrop) {
            setBackdropConnectedAnimated()
        } else {
            setBackdropConnectedInstant()
        }
        connectedUiAwaitingProof = false
        if (!applyDeviceRegistrationStatusIfActive()) {
            applyStatusStyle(
                text = "АКТИВНО : РАБОТАЕТ",
                textColor = Color.parseColor("#13A10E"),
                textGlowColor = Color.parseColor("#13A10E"),
            )
        }
        updateAttemptProgressDisplay()
        btnConnect.text = "ОТКЛЮЧИТЬ"
        btnNextProfile.visibility = View.VISIBLE
        requestVpnNetwork()
        updateIpDisplay()
        checkCurrentIp()
        measureLatency()
        applyPrimaryActionInterlock()
    }

    private fun renderStoppedState() {
        releaseVpnNetwork()
        setBackdropStopped()
        ipRefreshGeneration.incrementAndGet()
        ipRefreshInFlight.set(false)
        ipRefreshStartedAtMs = 0L
        ipRefreshQueuedAtMs = 0L
        latencyRefreshGeneration.incrementAndGet()
        latencyRefreshInFlight.set(false)
        latencyRefreshStartedAtMs = 0L
        connectedUiAwaitingProof = false
        primaryActionPreviewActive = false
        btnNextProfile.visibility = View.GONE
        tunnelIpResolved = false
        lastTunnelConnectedAtMs = 0L
        clientData.clearTunnelUiSnapshot()
        val directSnapshot = clientData.getDirectUiSnapshot()
        if (directSnapshot != null &&
            (directSnapshot.ipv4.isNotBlank() || directSnapshot.ipv6.isNotBlank() || directSnapshot.country.isNotBlank())
        ) {
            currentIpv4 = directSnapshot.ipv4.ifBlank { "..." }
            currentIpv6 = directSnapshot.ipv6.ifBlank { "..." }
            currentCountry = directSnapshot.country.ifBlank { "--" }
        } else {
            currentIpv4 = "..."
            currentIpv6 = "..."
            currentCountry = "--"
        }
        ipv4Candidate.value = ""
        ipv4Candidate.seenCount = 0
        ipv6Candidate.value = ""
        ipv6Candidate.seenCount = 0
        if (!isManualProfileSwitchProgressHeld()) {
            // A genuine stop clears the attempt progress. But a manual next-profile
            // switch (REAPPLY) can emit a transient STOPPED while tearing down the old
            // tunnel; keep the UI cursor/hold so the counter stays sequential.
            currentAttemptOrdinal = 0
            currentAttemptTotal = 0
            resetAttemptProgressTracking()
        }
        missingVpnSinceMs = 0L
        applyStatusStyle(
            text = "НЕ ПОДКЛЮЧЕНО",
            textColor = Color.parseColor("#FF4444"),
            textGlowColor = Color.parseColor("#FF4444"),
        )
        updateAttemptProgressDisplay()
        btnConnect.text = "ПОДКЛЮЧИТЬ"
        resetLatencyDisplay()
        updateIpDisplay()
        applyPrimaryActionInterlock()
    }

    private fun renderVpnPermissionRequiredState() {
        renderStoppedState()
        applyStatusStyle(
            text = "РАЗРЕШИ VPN",
            textColor = Color.parseColor("#F6D365"),
            textGlowColor = Color.parseColor("#F6D365"),
        )
        btnConnect.text = "ПОДКЛЮЧИТЬ"
        applyPrimaryActionInterlock()
    }

    /**
     * Состояние «согласие на VPN запросить невозможно»: в прошивке нет окна
     * согласия. Отдельная строка, а не «РАЗРЕШИ VPN», потому что разрешать
     * человеку нечем — совет уходит в подсказку и лог ([VpnConsent]).
     */
    private fun renderVpnConsentUnavailableState() {
        renderStoppedState()
        applyStatusStyle(
            text = "НЕТ ОКНА VPN",
            textColor = Color.parseColor("#FF6B6B"),
            textGlowColor = Color.parseColor("#FF6B6B"),
        )
        btnConnect.text = "ПОДКЛЮЧИТЬ"
        applyPrimaryActionInterlock()
    }

    private fun shouldBridgeStoppedToConnecting(previousState: String): Boolean {
        return clientData.isTransientConnectingPending()
    }

    private fun requestQuickTileRefresh(state: String) {
        runCatching {
            sendBroadcast(
                Intent(NovaVpnService.ACTION_VPN_STATE).apply {
                    putExtra(NovaVpnService.EXTRA_STATE, state)
                    putExtra(NovaVpnService.EXTRA_TILE_REFRESH_ONLY, true)
                }
            )
        }
        runCatching {
            TileService.requestListeningState(
                this,
                ComponentName(this, NovaTileService::class.java)
            )
        }
    }

    private fun prewarmRestrictedMobileDetectionIfNeeded(state: String) {
        if (state != NovaVpnService.STATE_STOPPED) return
        if (findCurrentVpnNetwork() != null) return
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val active = cm.activeNetwork ?: return
        val networkId = RestrictedMobileDetector.buildNetworkId(active) ?: return
        val caps = cm.getNetworkCapabilities(active) ?: return
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        if (clientData.getCachedRestrictedMobileStatus(networkId) != null) return

        ipExecutor.execute {
            val detected = RestrictedMobileDetector.detect(cm, active) ?: return@execute
            clientData.cacheRestrictedMobileStatus(networkId, detected)
            LogManager.log(
                if (detected) {
                    "На активной мобильной сети заранее обнаружен режим белых списков. " +
                        "При следующем WARP-подключении AUTO-маскировка сразу ограничится доменами из white.sni."
                } else {
                    "На активной мобильной сети публичные DNS IP доступны. " +
                    "При следующем WARP-подключении AUTO-маскировку можно не форсировать заранее."
                }
            )
            Handler(Looper.getMainLooper()).post {
                refreshRestrictedMobileIndicator()
            }
        }
    }

    private fun updateAttemptProgressDisplay() {
        // Метка транспорта — единственное, чем фаза называет себя интерфейсу:
        // по ней счётчик и решает, чей перебор он сейчас показывает.
        val serviceTransport = clientData.getServiceTransport()
        // Бэкенд смотрим наравне с транспортом: при переходе Opera → VLESS метка
        // транспорта в файле состояния меняется не мгновенно, и до её обновления
        // экран честно показывал знаменатель прошлой фазы — «x/54» от плана запуска
        // Opera на подключении по VLESS.
        val serviceBackend = clientData.getServiceBackend()
        if (serviceTransport != lastSeenServiceTransport || serviceBackend != lastSeenServiceBackend) {
            // Смена фазы — это новый счётчик. Без сброса знаменатель прошлой фазы
            // выигрывал у нового через maxOf, и выбранный MASQUE так и показывал
            // «1/50» от списка встроенных профилей WARP.
            lastSeenServiceTransport = serviceTransport
            lastSeenServiceBackend = serviceBackend
            resetAttemptProgressTracking()
            currentAttemptOrdinal = 0
            currentAttemptTotal = 0
            progressPhaseSwitchAtMs = SystemClock.elapsedRealtime()
        }
        // Порядковый номер прошлой фазы ещё лежит в состоянии сервиса, а знаменатель
        // уже от новой: сразу после срыва MASQUE на 3/3 мелькало «3/50». Пока новая
        // фаза не сообщила свой прогресс, старые числа не показываем.
        val progressPhaseSwitching =
            SystemClock.elapsedRealtime() - progressPhaseSwitchAtMs < PROGRESS_PHASE_SWITCH_QUIET_MS
        if (warpDiscoverySnapshot?.running == true) {
            if (isManualProfileSwitchProgressHeld()) {
                // During a manual next-profile switch the engine may start a fresh
                // discovery scan (ordinal/total 0) which would otherwise reset the
                // cursor to 1/50. Trust the UI selection instead.
                if (currentAttemptOrdinal > 0 && currentAttemptTotal > 0) {
                    displayedAttemptOrdinal = currentAttemptOrdinal
                    displayedAttemptTotal = currentAttemptTotal
                }
            } else {
                val discoveryOrdinal = warpDiscoverySnapshot?.ordinal ?: currentAttemptOrdinal
                val discoveryTotal = warpDiscoverySnapshot?.total ?: currentAttemptTotal
                if (discoveryTotal <= 0 && discoveryOrdinal <= 0) {
                    resetAttemptProgressTracking()
                } else {
                    acceptAttemptProgress(discoveryOrdinal, discoveryTotal)
                }
            }
            if (displayedAttemptTotal > 0) {
                val ordinal = displayedAttemptOrdinal.coerceIn(1, displayedAttemptTotal)
                tvAttemptProgress.text = "$ordinal/${displayedAttemptTotal}"
                tvAttemptProgress.visibility = View.VISIBLE
            } else {
                tvAttemptProgress.text = "..."
                tvAttemptProgress.visibility = View.VISIBLE
            }
        } else if (serviceTransport == NovaVpnService.TRANSPORT_VLESS &&
            vpnState == NovaVpnService.STATE_CONNECTING
        ) {
            // У VLESS счётчик — не число попыток, а место профиля в списке, и список
            // переставляется на ходу: успешный узел уезжает наверх, отвергнутые вниз.
            // Сглаживание тут врёт — оно умеет только расти и после «7/151» не пустило
            // бы честное «1/151» подключённого профиля и «2/151» следующего за ним.
            val ordinal = clientData.getServiceAttemptOrdinal()
            val total = clientData.getServiceAttemptTotal()
            if (ordinal > 0 && total > 0) {
                displayedAttemptOrdinal = ordinal
                displayedAttemptTotal = total
                currentAttemptOrdinal = ordinal
                currentAttemptTotal = total
                lastRawAttemptOrdinal = ordinal
                lastRawAttemptTotal = total
                tvAttemptProgress.text = "$ordinal/$total"
            } else {
                tvAttemptProgress.text = "..."
            }
            tvAttemptProgress.visibility = View.VISIBLE
        } else if (vpnState == NovaVpnService.STATE_CONNECTING) {
            // Экран — чистая функция последнего снимка службы.
            //
            // Раньше за этот TextView соревновались четыре источника: снимок службы,
            // кэш прошлого прогона в SharedPreferences, заглушка по длине списка
            // профилей и собственный курсор кнопки «следующий профиль». Их мирили
            // сглаживанием (`acceptAttemptProgress`), а оно тут же обходилось прямым
            // присваиванием строкой выше. Отсюда и брались скачки: «1/20» → «1/8»,
            // «23/50» → «4/50», «12/20» → «8/8» по истечении удержания.
            //
            // Теперь шкала одна и её ведёт только тот цикл, который перебирает:
            // ординал растёт на единицу за попытку, знаменатель равен длине очереди
            // и объявляется до первой попытки. Мирить нечего.
            val persistedOrdinal = clientData.getServiceAttemptOrdinal()
            val persistedTotal = clientData.getServiceAttemptTotal()
            // Единственное исключение из «экран рисует снимок»: секунды между
            // нажатием «следующий профиль» и первой публикацией службы. В файле
            // состояния ещё лежит номер до нажатия, и счётчик успевал отскочить
            // назад. Удержание снимается само, как только служба назвала команду —
            // сравнением, а не таймером.
            val manualSwitchPending = isManualProfileSwitchProgressHeld() &&
                persistedOrdinal < currentAttemptOrdinal
            if (!manualSwitchPending) {
                currentAttemptOrdinal = persistedOrdinal
                currentAttemptTotal = persistedTotal
            }
            // Числа прошлой фазы не показываем: её ординал ещё лежит в состоянии
            // службы, а знаменатель уже от новой — после срыва MASQUE на 3/3 мелькало
            // «3/50».
            val snapshotUsable = !manualSwitchPending &&
                persistedOrdinal > 0 &&
                persistedTotal > 0 &&
                !progressPhaseSwitching
            if (snapshotUsable) {
                displayedAttemptOrdinal = persistedOrdinal
                displayedAttemptTotal = persistedTotal
                lastRawAttemptOrdinal = persistedOrdinal
                lastRawAttemptTotal = persistedTotal
            } else if (manualSwitchPending) {
                displayedAttemptOrdinal = currentAttemptOrdinal
                displayedAttemptTotal = currentAttemptTotal
            } else if (persistedTotal <= 0) {
                resetAttemptProgressTracking()
            }
            if (displayedAttemptTotal > 0 && displayedAttemptOrdinal > 0) {
                val ordinal = displayedAttemptOrdinal.coerceIn(1, displayedAttemptTotal)
                tvAttemptProgress.text = "$ordinal/${displayedAttemptTotal}"
            } else {
                tvAttemptProgress.text = "..."
            }
            tvAttemptProgress.visibility = View.VISIBLE
        } else {
            tvAttemptProgress.visibility = View.INVISIBLE
        }
        refreshTransportNotice()
        refreshRestrictedMobileIndicator()
    }

    /**
     * Показывает пояснение сервиса о том, что работает не выбранный транспорт.
     * Без него подмена выглядит как обычное успешное подключение — именно так
     * выбранный MASQUE незаметно превращался в WARP.
     */
    private fun refreshTransportNotice() {
        if (!::tvTransportNotice.isInitialized) return
        val notice = clientData.getLastTransportNotice()
        // Причина остановки читается именно после остановки. Пока STOPPED сюда не
        // пускали, объяснение «среди импортированных нет такого протокола» видел
        // только журнал, а пользователь — «пара секунд ПОДКЛЮЧЕНИЕ… и всё».
        val relevant = notice.isNotBlank() &&
            (
                isTunnelConnected() ||
                    vpnState == NovaVpnService.STATE_CONNECTING ||
                    vpnState == NovaVpnService.STATE_STOPPED
                )
        if (relevant) {
            tvTransportNotice.text = notice
            tvTransportNotice.visibility = View.VISIBLE
        } else {
            tvTransportNotice.visibility = View.GONE
        }
    }

    private fun syncUiFromPersistedServiceState() {
        val persistedState = clientData.getServiceState()
        val persistedBackend = clientData.getServiceBackend()
        if (persistedBackend.isNotBlank()) {
            currentTunnelBackend = persistedBackend
        }
        val persistedOrdinal = clientData.getServiceAttemptOrdinal()
        val persistedTotal = clientData.getServiceAttemptTotal()
        if (
            persistedState == NovaVpnService.STATE_CONNECTING &&
            persistedTotal <= 0 &&
            !isManualProfileSwitchProgressHeld()
        ) {
            currentAttemptOrdinal = 0
            currentAttemptTotal = 0
        } else if (isManualProfileSwitchProgressHeld()) {
            // Keep the UI cursor chosen by the manual next-profile switch; do not let
            // the engine's own ordinal overwrite currentAttemptOrdinal/currentAttemptTotal.
            currentAttemptOrdinal = currentAttemptOrdinal.coerceAtMost(currentAttemptTotal)
        } else {
            // Снимок службы принимается как есть.
            //
            // `maxOf` работал храповиком: уменьшить числа мог только явный сброс по
            // смене метки транспорта, а смена очереди внутри одной метки (встроенные
            // → импортированные, первичный цикл → recovery) её не меняет. Очередь на
            // восемь попыток после полусотни продолжала показываться как «x/50».
            currentAttemptOrdinal = persistedOrdinal
            currentAttemptTotal = persistedTotal
        }
        val progressAdvanced =
            persistedState == NovaVpnService.STATE_CONNECTING &&
                (
                    (displayedAttemptTotal <= 0 && currentAttemptTotal > 0) ||
                        currentAttemptOrdinal > displayedAttemptOrdinal
                    )
        val progressReset =
            persistedState == NovaVpnService.STATE_CONNECTING &&
                persistedTotal <= 0 &&
                displayedAttemptTotal > 0
        if (persistedState != vpnState || progressAdvanced || progressReset) {
            updateUiByState(persistedState)
        } else if (persistedState == NovaVpnService.STATE_CONNECTING) {
            updateAttemptProgressDisplay()
        }
    }

    private fun refreshRestrictedMobileIndicator() {
        if (!::restrictedMobileDots.isInitialized) return
        val shouldShow =
            vpnState == NovaVpnService.STATE_CONNECTING &&
                warpDiscoverySnapshot?.running != true &&
                clientData.getTrafficMaskEnabled() &&
                clientData.getTrafficMaskMode() == "auto" &&
                isRestrictedMobileActiveNow()
        restrictedMobileDots.visibility = if (shouldShow) View.VISIBLE else View.INVISIBLE
        restrictedMobileDots.setAnimating(shouldShow)
    }

    private fun isRestrictedMobileActiveNow(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        val active = cm?.activeNetwork
        val caps = active?.let { cm.getNetworkCapabilities(it) }
        if (
            active != null &&
            caps != null &&
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        ) {
            val networkId = RestrictedMobileDetector.buildNetworkId(active)
            if (networkId != null) {
                clientData.getCachedRestrictedMobileStatus(networkId, freshnessMs = 45_000L)?.let { cached ->
                    return cached
                }
            }
        }
        return clientData.getLatestRestrictedMobileStatus(freshnessMs = 45_000L) == true
    }

    private fun isRecentLocalStop(): Boolean {
        if (clientData.getServiceState() != NovaVpnService.STATE_STOPPED) return false
        val updatedAt = clientData.getServiceStateUpdatedAt()
        if (updatedAt <= 0L) return false
        val ageMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
        return ageMs <= 8_000L
    }

    private fun hasLiveNovaVpn(): Boolean {
        val activeVpn = findCurrentVpnNetwork() ?: return false
        return isSystemVpnLikelyNova(activeVpn)
    }

    private fun resolveStartupState(persistedState: String): String {
        if (persistedState == NovaVpnService.STATE_STOPPED && isRecentLocalStop()) {
            return NovaVpnService.STATE_STOPPED
        }
        val activeVpn = findCurrentVpnNetwork()
        if (
            persistedState == NovaVpnService.STATE_STOPPED &&
            clientData.getRestartSession() == null &&
            !isNovaVpnServiceRunning() &&
            activeVpn != null &&
            isSystemVpnLikelyNova(activeVpn)
        ) {
            requestStaleStopCleanup()
            return NovaVpnService.STATE_STOPPED
        }
        if (activeVpn != null && isSystemVpnLikelyNova(activeVpn)) {
            if (persistedState == NovaVpnService.STATE_CONNECTED) {
                clientData.clearTransientConnectingPending()
                clientData.clearSoftReapplyPending()
                return NovaVpnService.STATE_CONNECTED
            }
            if (shouldPromoteLiveNovaVpnToConnected(persistedState)) {
                return NovaVpnService.STATE_CONNECTED
            }
            return if (
                persistedState == NovaVpnService.STATE_CONNECTING ||
                isStartFlowActive ||
                clientData.isTransientConnectingPending() ||
                clientData.isSoftReapplyPending()
            ) {
                NovaVpnService.STATE_CONNECTING
            } else {
                NovaVpnService.STATE_CONNECTED
            }
        }
        if (
            persistedState == NovaVpnService.STATE_STOPPED &&
            !isManualStopUiSuppressed() &&
            clientData.getAutoReconnect() &&
            clientData.getRestartSession() != null &&
            isNovaVpnServiceRunning()
        ) {
            return NovaVpnService.STATE_CONNECTING
        }
        if (persistedState != NovaVpnService.STATE_CONNECTING) {
            return persistedState
        }
        if (recoverDeadConnectingStateIfNeeded()) {
            return NovaVpnService.STATE_CONNECTING
        }
        return if (shouldDropStaleConnectingState()) {
            markServiceStoppedLocally()
            NovaVpnService.STATE_STOPPED
        } else {
            NovaVpnService.STATE_CONNECTING
        }
    }

    private fun resolvePersistedStateAgainstSystemVpn(persistedState: String): String {
        if (shouldPromoteLiveNovaVpnToConnected(persistedState)) {
            return NovaVpnService.STATE_CONNECTED
        }
        if (persistedState != NovaVpnService.STATE_STOPPED) {
            val activeVpn = findCurrentVpnNetwork()
            if (
                persistedState == NovaVpnService.STATE_CONNECTED &&
                activeVpn != null &&
                isSystemVpnLikelyNova(activeVpn)
            ) {
                clientData.clearTransientConnectingPending()
                clientData.clearSoftReapplyPending()
                return NovaVpnService.STATE_CONNECTED
            }
            return persistedState
        }
        if (isManualStopUiSuppressed() || isRecentLocalStop()) {
            return persistedState
        }
        val activeVpn = findCurrentVpnNetwork() ?: return persistedState
        if (!isSystemVpnLikelyNova(activeVpn)) {
            return persistedState
        }
        if (clientData.getRestartSession() == null && !isNovaVpnServiceRunning()) {
            requestStaleStopCleanup()
            return persistedState
        }
        return if (
            isStartFlowActive ||
            clientData.isTransientConnectingPending() ||
            clientData.isSoftReapplyPending()
        ) {
            NovaVpnService.STATE_CONNECTING
        } else {
            NovaVpnService.STATE_CONNECTED
        }
    }

    private fun shouldPromoteLiveNovaVpnToConnected(persistedState: String): Boolean {
        if (persistedState != NovaVpnService.STATE_CONNECTING) return false
        if (isStartFlowActive || clientData.isTransientConnectingPending() || clientData.isSoftReapplyPending()) {
            return false
        }
        // Пока жив сам foreground-service Nova, CONNECTING может означать,
        // что идёт обычный перебор вариантов и системный VPN уже временно поднят.
        // Не переводим UI в CONNECTED только по факту существования tun, иначе экран
        // зависает на "ПРОВЕРКА ТУННЕЛЯ..." вместо честного "ПОДКЛЮЧЕНИЕ...".
        if (isNovaVpnServiceRunning()) {
            return false
        }
        val activeVpn = findCurrentVpnNetwork() ?: return false
        return isSystemVpnLikelyNova(activeVpn)
    }

    /**
     * Пока идёт регистрация устройства, статус говорит именно о ней.
     *
     * Ключ MASQUE выдаётся только изнутри поднятого туннеля, поэтому первый
     * выбор MASQUE поднимает WARP как ступень регистрации. На экране это почти
     * полминуты выглядело обычным «АКТИВНО : РАБОТАЕТ» с пустым пингом — и
     * пользователь успевал решить, что зависло, и нажать отключение или сменить
     * протокол, уронив ровно тот туннель, через который выдаётся ключ.
     */
    private fun applyDeviceRegistrationStatusIfActive(): Boolean {
        if (!clientData.isDeviceRegistrationInProgress()) return false
        applyStatusStyle(
            text = "РЕГИСТРАЦИЯ... ОЖИДАЙТЕ",
            textColor = Color.parseColor("#C99514"),
            textGlowColor = Color.parseColor("#F1C64A"),
        )
        return true
    }

    private fun applyStatusStyle(
        text: String,
        textColor: Int,
        textGlowColor: Int,
    ) {
        // Облако принимает цвет textGlowColor для 100% совпадения оттенка
        val red = Color.red(textGlowColor)
        val green = Color.green(textGlowColor)
        val blue = Color.blue(textGlowColor)
        val cloudColor = Color.argb(255, red, green, blue) // Яркое базовое свечение
        tvStatus.letterSpacing = 0.096f
        tvStatus.setStroke(7.4f, Color.parseColor("#EEF8A6"))
        
        // Отключаем свечение у самого текста
        tvStatus.setStrokeGlow(0f, Color.TRANSPARENT)
        tvStatus.setGlow(0f, Color.TRANSPARENT)
        tvStatus.setPillStyle(
            fillColor = Color.TRANSPARENT,
            glowColor = cloudColor,
            innerColor = Color.TRANSPARENT,
            insetX = 10f,
            insetY = 8f,
            blurRadius = 72f,
            ovalGlow = true,
        )
        tvStatus.text = text
        tvStatus.setTextColor(textColor)
    }

    private fun setBackdropStopped() {
        when (mainBackgroundMode()) {
            MainBackgroundPolicy.MODE_ANIMATION -> {
                backdropState = BackdropState.STOPPED
                backgroundRevealAnimator?.cancel()
                ivBackgroundArt.animate().cancel()
                tronBackdrop.animate().cancel()
                ivBackgroundArt.visibility = View.GONE
                ivBackgroundArt.alpha = 0f
                ivBackgroundArt.revealProgress = 0f
                tronBackdrop.setMode(TronRingsView.Mode.STOPPED)
                tronBackdrop.visibility = View.GONE
                tronBackdrop.alpha = 0f
                hideNetworkBackground()
                return
            }
            MainBackgroundPolicy.MODE_NONE -> {
                backdropState = BackdropState.STOPPED
                backgroundRevealAnimator?.cancel()
                ivBackgroundArt.animate().cancel()
                tronBackdrop.animate().cancel()
                ivBackgroundArt.visibility = View.GONE
                ivBackgroundArt.alpha = 0f
                ivBackgroundArt.revealProgress = 0f
                tronBackdrop.setMode(TronRingsView.Mode.STOPPED)
                tronBackdrop.visibility = View.VISIBLE
                tronBackdrop.alpha = 0f
                hideNetworkBackground()
                return
            }
        }
        hideNetworkBackground()
        if (backdropState == BackdropState.STOPPED && ivBackgroundArt.alpha == 0f) {
            tronBackdrop.setMode(TronRingsView.Mode.STOPPED)
            tronBackdrop.visibility = View.GONE
            return
        }
        backdropState = BackdropState.STOPPED
        backgroundRevealAnimator?.cancel()
        ivBackgroundArt.animate().cancel()
        tronBackdrop.animate().cancel()
        tronBackdrop.setMode(TronRingsView.Mode.STOPPED)
        tronBackdrop.visibility = View.GONE
        tronBackdrop.alpha = 0f
        val startAlpha = ivBackgroundArt.alpha.coerceAtLeast(0f)
        if (startAlpha <= 0.001f && ivBackgroundArt.revealProgress <= 0.001f) {
            ivBackgroundArt.visibility = View.GONE
            ivBackgroundArt.alpha = 0f
            ivBackgroundArt.revealProgress = 0f
            return
        }
        ivBackgroundArt.visibility = View.VISIBLE
        ivBackgroundArt.alpha = startAlpha
        if (lowEndUiAnimationDevice) {
            backgroundRevealAnimator = null
            ivBackgroundArt.revealProgress = 1f
            ivBackgroundArt.animate()
                .cancel()
            ivBackgroundArt.animate()
                .alpha(0f)
                .withLayer()
                .setDuration(2500L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .withEndAction {
                    if (backdropState == BackdropState.STOPPED) {
                        ivBackgroundArt.visibility = View.GONE
                        ivBackgroundArt.alpha = 0f
                        ivBackgroundArt.revealProgress = 0f
                    }
                }
                .start()
            return
        }
        val startReveal = ivBackgroundArt.revealProgress.coerceIn(0f, 1f).let { progress ->
            if (progress <= 0.001f && startAlpha > 0.02f) 1f else progress
        }
        ivBackgroundArt.revealProgress = startReveal
        val revealAnimator = ObjectAnimator.ofFloat(
            ivBackgroundArt,
            "revealProgress",
            startReveal,
            0.02f,
        ).apply {
            duration = 2500L
            interpolator = AccelerateDecelerateInterpolator()
        }
        val alphaAnimator = ObjectAnimator.ofFloat(ivBackgroundArt, "alpha", startAlpha, 0.12f).apply {
            duration = 2500L
            interpolator = AccelerateDecelerateInterpolator()
        }
        backgroundRevealAnimator = AnimatorSet().apply {
            playTogether(revealAnimator, alphaAnimator)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (backdropState == BackdropState.STOPPED) {
                        ivBackgroundArt.visibility = View.GONE
                        ivBackgroundArt.alpha = 0f
                        ivBackgroundArt.revealProgress = 0f
                    }
                    if (backgroundRevealAnimator === animation) {
                        backgroundRevealAnimator = null
                    }
                }

                override fun onAnimationCancel(animation: Animator) {
                    if (backgroundRevealAnimator === animation) {
                        backgroundRevealAnimator = null
                    }
                }
            })
            start()
        }
    }

    private fun setBackdropConnecting(forceRestart: Boolean = false) {
        when (mainBackgroundMode()) {
            MainBackgroundPolicy.MODE_ANIMATION -> {
                if (!forceRestart &&
                    backdropState == BackdropState.CONNECTING &&
                    tronBackdrop.alpha == 1f &&
                    tronBackdrop.visibility == View.VISIBLE
                ) {
                    tronBackdrop.setMode(TronRingsView.Mode.CONNECTING)
                    return
                }
                backdropState = BackdropState.CONNECTING
                backgroundRevealAnimator?.cancel()
                ivBackgroundArt.animate().cancel()
                tronBackdrop.animate().cancel()
                ivBackgroundArt.visibility = View.GONE
                ivBackgroundArt.alpha = 0f
                ivBackgroundArt.revealProgress = 0f
                tronBackdrop.setYogurtIndigoEnabled(true)
                hideNetworkBackground()
                tronBackdrop.alpha = 1f
                tronBackdrop.visibility = View.VISIBLE
                tronBackdrop.setMode(TronRingsView.Mode.CONNECTING, restart = forceRestart)
                return
            }
            MainBackgroundPolicy.MODE_NONE -> {
                backdropState = BackdropState.CONNECTING
                backgroundRevealAnimator?.cancel()
                ivBackgroundArt.animate().cancel()
                tronBackdrop.animate().cancel()
                ivBackgroundArt.visibility = View.GONE
                ivBackgroundArt.alpha = 0f
                ivBackgroundArt.revealProgress = 0f
                tronBackdrop.setYogurtIndigoEnabled(false)
                hideNetworkBackground()
                tronBackdrop.alpha = 1f
                tronBackdrop.visibility = View.VISIBLE
                tronBackdrop.setMode(TronRingsView.Mode.CONNECTING, restart = forceRestart)
                return
            }
        }
        hideNetworkBackground()
        tronBackdrop.setYogurtIndigoEnabled(false)
        if (!forceRestart &&
            backdropState == BackdropState.CONNECTING &&
            ivBackgroundArt.alpha == 0f &&
            tronBackdrop.alpha == 1f
        ) {
            tronBackdrop.setMode(TronRingsView.Mode.CONNECTING)
            return
        }
        backdropState = BackdropState.CONNECTING
        backgroundRevealAnimator?.cancel()
        ensureBackdropImageLoaded()
        ivBackgroundArt.animate().cancel()
        tronBackdrop.animate().cancel()
        ivBackgroundArt.alpha = 0f
        ivBackgroundArt.revealProgress = 0f
        tronBackdrop.alpha = 1f
        tronBackdrop.visibility = View.VISIBLE
        tronBackdrop.setMode(TronRingsView.Mode.CONNECTING, restart = forceRestart)
    }

    private fun setBackdropConnectedInstant() {
        when (mainBackgroundMode()) {
            MainBackgroundPolicy.MODE_ANIMATION -> {
                backdropState = BackdropState.CONNECTED
                backgroundRevealAnimator?.cancel()
                ivBackgroundArt.animate().cancel()
                tronBackdrop.animate().cancel()
                ivBackgroundArt.visibility = View.GONE
                ivBackgroundArt.alpha = 0f
                ivBackgroundArt.revealProgress = 0f
                tronBackdrop.setYogurtIndigoEnabled(true)
                showNetworkBackground()
                tronBackdrop.visibility = View.GONE
                tronBackdrop.alpha = 1f
                tronBackdrop.setMode(TronRingsView.Mode.STOPPED)
                return
            }
            MainBackgroundPolicy.MODE_NONE -> {
                backdropState = BackdropState.CONNECTED
                backgroundRevealAnimator?.cancel()
                ivBackgroundArt.animate().cancel()
                tronBackdrop.animate().cancel()
                ivBackgroundArt.visibility = View.GONE
                ivBackgroundArt.alpha = 0f
                ivBackgroundArt.revealProgress = 0f
                hideNetworkBackground()
                tronBackdrop.visibility = View.GONE
                tronBackdrop.alpha = 1f
                tronBackdrop.setMode(TronRingsView.Mode.STOPPED)
                return
            }
        }
        backdropState = BackdropState.CONNECTED
        backgroundRevealAnimator?.cancel()
        hideNetworkBackground()
        ensureBackdropImageLoaded()
        ivBackgroundArt.animate().cancel()
        tronBackdrop.animate().cancel()
        ivBackgroundArt.visibility = View.VISIBLE
        ivBackgroundArt.alpha = 1f
        ivBackgroundArt.revealProgress = 1f
        tronBackdrop.visibility = View.GONE
        tronBackdrop.alpha = 1f
        tronBackdrop.setMode(TronRingsView.Mode.STOPPED)
    }

    private fun setBackdropConnectedAnimated() {
        when (mainBackgroundMode()) {
            MainBackgroundPolicy.MODE_ANIMATION -> {
                backdropState = BackdropState.CONNECTED
                backgroundRevealAnimator?.cancel()
                ivBackgroundArt.animate().cancel()
                tronBackdrop.animate().cancel()
                ivBackgroundArt.visibility = View.GONE
                ivBackgroundArt.alpha = 0f
                ivBackgroundArt.revealProgress = 0f
                tronBackdrop.setYogurtIndigoEnabled(true)
                showNetworkBackground()
                tronBackdrop.alpha = 0.86f
                tronBackdrop.visibility = View.VISIBLE
                tronBackdrop.setMode(TronRingsView.Mode.CONNECTING)
                tronBackdrop.animate()
                    .alpha(0f)
                    .withLayer()
                    .setStartDelay(80L)
                    .setDuration(700L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        if (backdropState == BackdropState.CONNECTED) {
                            tronBackdrop.visibility = View.GONE
                            tronBackdrop.alpha = 1f
                            tronBackdrop.setMode(TronRingsView.Mode.STOPPED)
                        }
                    }
                    .start()
                return
            }
            MainBackgroundPolicy.MODE_NONE -> {
                setBackdropConnectedInstant()
                return
            }
        }
        if (backdropState == BackdropState.CONNECTED && ivBackgroundArt.alpha >= 0.999f) {
            return
        }
        backdropState = BackdropState.CONNECTED
        backgroundRevealAnimator?.cancel()
        hideNetworkBackground()
        ensureBackdropImageLoaded()
        ivBackgroundArt.animate().cancel()
        tronBackdrop.animate().cancel()
        ivBackgroundArt.visibility = View.VISIBLE
        if (lowEndUiAnimationDevice) {
            ivBackgroundArt.alpha = 0f
            ivBackgroundArt.revealProgress = 1f
        } else {
            ivBackgroundArt.alpha = 0.12f
            ivBackgroundArt.revealProgress = 0f
        }
        tronBackdrop.alpha = 0.86f
        tronBackdrop.visibility = View.VISIBLE
        tronBackdrop.setMode(TronRingsView.Mode.CONNECTING)
        if (lowEndUiAnimationDevice) {
            backgroundRevealAnimator = null
            ivBackgroundArt.animate()
                .alpha(1f)
                .withLayer()
                .setDuration(2500L)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .start()
        } else {
            val revealAnimator = ObjectAnimator.ofFloat(ivBackgroundArt, "revealProgress", 0.02f, 1f).apply {
                duration = 2500L
                interpolator = AccelerateDecelerateInterpolator()
            }
            val alphaAnimator = ObjectAnimator.ofFloat(ivBackgroundArt, "alpha", 0.12f, 1f).apply {
                duration = 2500L
                interpolator = AccelerateDecelerateInterpolator()
            }
            backgroundRevealAnimator = AnimatorSet().apply {
                playTogether(revealAnimator, alphaAnimator)
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        if (backgroundRevealAnimator === animation) {
                            backgroundRevealAnimator = null
                        }
                    }

                    override fun onAnimationCancel(animation: Animator) {
                        if (backgroundRevealAnimator === animation) {
                            backgroundRevealAnimator = null
                        }
                    }
                })
                start()
            }
        }
        tronBackdrop.animate()
            .alpha(0f)
            .withLayer()
            .setStartDelay(120L)
            .setDuration(2100L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (backdropState == BackdropState.STOPPED) {
                    return@withEndAction
                }
                if (backdropState == BackdropState.CONNECTED) {
                    tronBackdrop.visibility = View.GONE
                    tronBackdrop.alpha = 1f
                    tronBackdrop.setMode(TronRingsView.Mode.STOPPED)
                }
            }
            .start()
    }

    private fun isTunnelConnected(): Boolean {
        return vpnState == NovaVpnService.STATE_CONNECTED
    }

    private fun hasConnectedUiProof(): Boolean {
        if (tunnelIpResolved) return true
        if (lastMeasuredLatencyMs >= 0) return true
        if (
            vpnState == NovaVpnService.STATE_CONNECTED &&
            clientData.getServiceState() == NovaVpnService.STATE_CONNECTED &&
            hasLiveNovaVpn()
        ) {
            // NovaVpnService переводит состояние в CONNECTED только после успешного
            // tunnel-probe/data-plane. IP/регион могут обновиться позже или не успеть
            // обновиться на конкретном устройстве, но UI не должен из-за этого
            // бесконечно висеть на "ПРОВЕРКА ТУННЕЛЯ...".
            return true
        }
        return false
    }

    private fun promoteConnectedUiIfVerified() {
        if (!connectedUiAwaitingProof || !isTunnelConnected() || !hasConnectedUiProof()) return
        val shouldAnimateBackdrop =
            backdropState != BackdropState.CONNECTED ||
                tronBackdrop.visibility == View.VISIBLE ||
                ivBackgroundArt.alpha < 0.999f
        renderConnectedState(
            preserveKnownIps = true,
            animateBackdrop = shouldAnimateBackdrop,
        )
    }

    private fun validateConnectedTunnelState() {
        if (!isTunnelConnected()) {
            missingVpnSinceMs = 0L
            return
        }
        if (connectedUiAwaitingProof && !hasConnectedUiProof()) {
            val pendingAgeMs = SystemClock.elapsedRealtime() - lastTunnelConnectedAtMs
            if (pendingAgeMs >= 5_000L) {
                requestImmediateVpnHealthRecheck(
                    minIntervalMs = 5_000L,
                    reason = "connected-proof-timeout",
                )
            }
        }
        val currentVpn = vpnNetwork ?: findCurrentVpnNetwork()?.also { vpnNetwork = it }
        if (currentVpn != null) {
            missingVpnSinceMs = 0L
            return
        }
        if (missingVpnSinceMs == 0L) {
            missingVpnSinceMs = SystemClock.elapsedRealtime()
            return
        }
        if ((SystemClock.elapsedRealtime() - missingVpnSinceMs) < 3500L) {
            return
        }
        if (clientData.getAutoReconnect() && clientData.getRestartSession() != null) {
            LogManager.log("VPN-интерфейс исчез. Реконнект активен, пытаемся восстановить сеанс автоматически.")
            clientData.saveServiceState(
                NovaVpnService.STATE_CONNECTING,
                clientData.getServiceBackend(),
            )
            currentTunnelBackend = clientData.getServiceBackend()
            missingVpnSinceMs = 0L
            updateUiByState(NovaVpnService.STATE_CONNECTING)
            try {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, NovaVpnService::class.java).apply {
                        action = NovaVpnService.ACTION_RESTORE_LAST_SESSION
                    }
                )
            } catch (e: Exception) {
                LogManager.log("Не удалось инициировать реконнект после потери VPN: ${e.message}")
            }
            return
        }
        LogManager.log("VPN-интерфейс исчез, а реконнект отключён. Сбрасываем stale-state и системный VPN-стек.")
        markServiceStoppedLocally()
        currentTunnelBackend = NovaVpnService.BACKEND_WARP
        missingVpnSinceMs = 0L
        updateUiByState(NovaVpnService.STATE_STOPPED)
        try {
            startService(Intent(this, NovaVpnService::class.java).apply {
                action = "STOP_VPN"
            })
        } catch (e: Exception) {
            LogManager.log("Не удалось очистить системный VPN-стек после потери туннеля: ${e.message}")
        }
    }

    private fun isNovaVpnServiceRunning(): Boolean {
        @Suppress("DEPRECATION")
        return (getSystemService(ActivityManager::class.java)?.getRunningServices(Int.MAX_VALUE) ?: emptyList())
            .any { service -> service.service?.className == NovaVpnService::class.java.name }
    }

    private fun shouldDropStaleConnectingState(): Boolean {
        if (findCurrentVpnNetwork() != null) return false
        val updatedAt = clientData.getServiceStateUpdatedAt()
        val ageMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
        val serviceRunning = isNovaVpnServiceRunning()
        val hasRestartSession = clientData.getRestartSession() != null
        val noLiveNovaRuntime = !serviceRunning
        val persistedPendingOnly =
            !isStartFlowActive &&
                !serviceRunning &&
                (clientData.isTransientConnectingPending() || clientData.isSoftReapplyPending())
        val localStartInFlight =
            isStartFlowActive || clientData.isTransientConnectingPending() || clientData.isSoftReapplyPending()

        if (noLiveNovaRuntime && !isStartFlowActive) {
            return true
        }

        if (persistedPendingOnly) {
            return ageMs >= 1500L
        }

        if (localStartInFlight) {
            return ageMs >= 120_000L
        }
        if (!serviceRunning && !hasRestartSession && !isStartFlowActive) {
            return true
        }
        if (!serviceRunning && ageMs >= 12_000L) {
            return true
        }
        if (!serviceRunning) {
            return ageMs >= 3000L
        }
        return ageMs >= 20000L
    }

    private fun recoverDeadConnectingStateIfNeeded(): Boolean {
        val persistedState = clientData.getServiceState()
        if (persistedState != NovaVpnService.STATE_CONNECTING) {
            return false
        }
        if (!isStartFlowActive) return false
        if (findCurrentVpnNetwork() != null) return false
        if (isNovaVpnServiceRunning()) return false
        if (!clientData.getAutoReconnect()) return false
        if (clientData.getRestartSession() == null) return false
        if (!clientData.isTransientConnectingPending() && !clientData.isSoftReapplyPending()) {
            return false
        }

        val updatedAt = clientData.getServiceStateUpdatedAt()
        val ageMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
        if (ageMs < 4000L || ageMs > 90_000L) return false

        val now = SystemClock.elapsedRealtime()
        if (now - lastDeadConnectingRecoveryAtMs < 15_000L) return false
        lastDeadConnectingRecoveryAtMs = now

        LogManager.log("Подключение зависло без живого :vpn процесса. Перезапускаем restore-сеанс автоматически.")
        markServiceConnectingLocally(clientData.getServiceBackend())
        currentTunnelBackend = clientData.getServiceBackend()
        updateUiByState(NovaVpnService.STATE_CONNECTING)
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, NovaVpnService::class.java).apply {
                    action = NovaVpnService.ACTION_RESTORE_LAST_SESSION
                }
            )
            return true
        } catch (e: Exception) {
            LogManager.log("Не удалось перезапустить зависший connect-сеанс: ${e.message}")
            return false
        }
    }

    private fun measureLatencyViaTunnelNetwork(network: Network?, timeout: Int): Int {
        val effectiveTimeoutMs = timeout.coerceIn(900, 3_500)
        val deadlineAtMs = SystemClock.elapsedRealtime() + effectiveTimeoutMs
        val tcpCandidates = buildTunnelLatencyTcpTargets()
        val tcpPerTargetTimeoutMs = (effectiveTimeoutMs / tcpCandidates.size.coerceAtLeast(1)).coerceIn(180, 650)
        for ((host, port) in tcpCandidates) {
            val remainingMs = (deadlineAtMs - SystemClock.elapsedRealtime()).toInt()
            if (remainingMs <= 0) return -1
            val latency = measureTcpConnectLatency(
                network,
                host,
                port,
                minOf(tcpPerTargetTimeoutMs, remainingMs.coerceAtLeast(180)),
            )
            if (latency >= 0) {
                return latency
            }
        }

        val httpCandidates = listOf(
            "http://1.1.1.1/cdn-cgi/trace",
            "http://1.0.0.1/cdn-cgi/trace",
        )
        val httpPerTargetTimeoutMs = (effectiveTimeoutMs / httpCandidates.size.coerceAtLeast(1)).coerceIn(250, 600)
        for (url in httpCandidates) {
            val remainingMs = (deadlineAtMs - SystemClock.elapsedRealtime()).toInt()
            if (remainingMs <= 0) return -1
            val latency = measureHttpLatency(
                network,
                url,
                minOf(httpPerTargetTimeoutMs, remainingMs.coerceAtLeast(200)),
            )
            if (latency >= 0) {
                return latency
            }
        }

        usePrimaryLatencyServer = !usePrimaryLatencyServer
        return -1
    }

    private fun buildTunnelLatencyTcpTargets(): List<Pair<String, Int>> {
        val ordered = linkedSetOf<Pair<String, Int>>()
        fun add(host: String, port: Int = 443) {
            ordered += host to port
        }
        if (usePrimaryLatencyServer) {
            add("1.1.1.1")
            add("1.0.0.1")
            add("8.8.8.8")
            add("8.8.4.4")
        } else {
            add("8.8.8.8")
            add("8.8.4.4")
            add("1.1.1.1")
            add("1.0.0.1")
        }
        add("9.9.9.9")
        add("208.67.222.222")
        return ordered.toList()
    }

    private fun measureTcpConnectLatency(
        network: Network?,
        host: String,
        port: Int,
        timeoutMs: Int,
    ): Int {
        val endpoints = resolveLatencyProbeAddresses(network, host, port)
        if (endpoints.isEmpty()) return -1
        val perEndpointTimeoutMs = (timeoutMs / endpoints.size.coerceAtLeast(1)).coerceIn(180, timeoutMs.coerceAtLeast(180))
        for (endpoint in endpoints) {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val socket = if (network != null) network.socketFactory.createSocket() else Socket()
                socket.use {
                    it.connect(endpoint, perEndpointTimeoutMs)
                    if (it.isConnected) {
                        return (SystemClock.elapsedRealtime() - startedAt).toInt()
                    }
                }
            } catch (_: Exception) {
            }
        }
        return -1
    }

    private fun resolveLatencyProbeAddresses(
        network: Network?,
        host: String,
        port: Int,
    ): List<InetSocketAddress> {
        return try {
            val resolved = if (network != null) {
                network.getAllByName(host)
            } else {
                InetAddress.getAllByName(host)
            }
            resolved.map { InetSocketAddress(it, port) }
        } catch (_: Exception) {
            listOf(InetSocketAddress(host, port))
        }
    }

    private fun measureHttpLatency(
        network: Network?,
        url: String,
        timeoutMs: Int,
    ): Int {
        val start = SystemClock.elapsedRealtime()
        return try {
            val connection = if (network != null) {
                network.openConnection(URL(url))
            } else {
                URL(url).openConnection()
            } as HttpURLConnection
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
            if (
                code == HttpURLConnection.HTTP_NO_CONTENT ||
                code == HttpURLConnection.HTTP_OK ||
                code == HttpURLConnection.HTTP_MOVED_TEMP ||
                code == HttpURLConnection.HTTP_MOVED_PERM ||
                code == HttpURLConnection.HTTP_SEE_OTHER ||
                code == 307 ||
                code == 308
            ) {
                (SystemClock.elapsedRealtime() - start).toInt()
            } else {
                -1
            }
        } catch (_: Exception) {
            -1
        }
    }

    private fun requestVpnNetwork() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        vpnNetwork = findCurrentVpnNetwork()
        vpnNetwork?.let {
            Log.i("NovaVPN", "VPN Network Reused: $it (${describeNetwork(it)})")
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                vpnNetwork = network
                Log.i("NovaVPN", "VPN Network Available: $network (${describeNetwork(network)})")
            }
            override fun onLost(network: Network) {
                if (vpnNetwork == network) vpnNetwork = null
            }
        }
        cm?.registerNetworkCallback(request, networkCallback!!)
    }

    private fun findCurrentVpnNetwork(): Network? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        fun bestVpnNetwork(): Network? {
            fun networkId(network: Network): Int {
                return network.toString().toIntOrNull() ?: -1
            }

            fun score(network: Network): Int {
                val caps = cm.getNetworkCapabilities(network) ?: return Int.MIN_VALUE
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return Int.MIN_VALUE
                var score = 0
                if (isLikelyNovaVpnNetwork(network)) score += 1_000
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 200
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) score += 50
                return score
            }

            return cm.allNetworks
                .filter { network ->
                    cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                }
                .maxWithOrNull(
                    compareBy<Network> { score(it) }
                        .thenBy { networkId(it) }
                )
        }

        val active = cm.activeNetwork
        if (
            active != null &&
            cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true &&
            isLikelyNovaVpnNetwork(active)
        ) {
            return active
        }

        val bestVpn = bestVpnNetwork()
        if (bestVpn != null && isLikelyNovaVpnNetwork(bestVpn)) {
            return bestVpn
        }

        return if (bestVpn != null && hasStrongLocalNovaSessionEvidence()) bestVpn else null
    }

    private fun hasStrongLocalNovaSessionEvidence(): Boolean {
        if (isStartFlowActive || clientData.isSoftReapplyPending() || clientData.isTransientConnectingPending()) {
            return true
        }
        if (isNovaVpnServiceRunning()) {
            return true
        }
        val persistedState = getPersistedServiceState()
        val updatedAt = clientData.getServiceStateUpdatedAt()
        val ageMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
        return clientData.getRestartSession() != null &&
            persistedState == NovaVpnService.STATE_CONNECTING &&
            ageMs in 0..12_000L
    }

    private fun noteManualStopUiSuppression(durationMs: Long = 8_000L) {
        manualStopUiSuppressedUntilMs = SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(1_000L)
    }

    private fun isManualStopUiSuppressed(): Boolean {
        return SystemClock.elapsedRealtime() < manualStopUiSuppressedUntilMs
    }

    private fun describeNetwork(network: Network): String {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return "unknown"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        val parts = mutableListOf<String>()
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) parts += "VPN"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) parts += "CELLULAR"
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) parts += "WIFI"
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) parts += "VALIDATED"
        return parts.joinToString(separator = ",").ifBlank { "unknown" }
    }

    private fun releaseVpnNetwork() {
        networkCallback?.let {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it)
        }
        networkCallback = null
        vpnNetwork = null
    }
}

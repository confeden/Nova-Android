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
import android.widget.RadioButton
import android.widget.RadioGroup
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
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
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

    /**
     * Последняя записанная в журнал строка о наблюдённом выходе.
     *
     * Нужна ровно для того, чтобы повтор не писался: проверка адреса идёт по
     * кругу, пока экран открыт, и без сравнения журнал заполняется одной и той
     * же строкой (см. `checkCurrentIp`).
     */
    private var lastExitSnapshotLine: String? = null

    /**
     * Последняя причина, по которой экран показал прошлый снимок вместо замера.
     *
     * Опрос IP идёт раз в две секунды, и на Tor трасса не доходит **никогда**:
     * узел выхода до неё не пускает. Одна и та же строка писалась тридцать раз в
     * минуту и вытесняла из журнала всё остальное — включая ровно те строки, ради
     * которых журнал и присылают. Пишем только на смене причины.
     */
    private var lastIpFallbackLine: String? = null

    companion object {
        /** Сколько ждать прогресс от новой фазы, прежде чем доверять состоянию сервиса. */
        private const val PROGRESS_PHASE_SWITCH_QUIET_MS = 1_500L

        /** Как часто опрашивать прогресс обновления, пока он вообще меняется. */
        private const val UPDATE_CHIP_TICK_MS = 1_000L

        /** Действие, пришедшее с виджета рабочего стола. */
        const val EXTRA_WIDGET_ACTION = "extra_widget_action"
        const val WIDGET_ACTION_NEXT_PROFILE = "next_profile"

        /** Плашка в покое: приглушённый зелёный, тот же, что у остальных подсказок. */
        private const val UPDATE_CHIP_IDLE_COLOR = 0xFFA9F2BF.toInt()

        /** Работа идёт: жёлтый. Он же отличает «происходит сейчас» от «можно нажать». */
        private const val UPDATE_CHIP_BUSY_COLOR = 0xFFFFD166.toInt()

        /** Сорвалось: тот же красный, что у неподключённого состояния. */
        private const val UPDATE_CHIP_FAILED_COLOR = 0xFFFF8A8A.toInt()

        /**
         * Сколько ждать уже работающее обновление IP, прежде чем считать его зависшим.
         *
         * Порог обязан быть **больше** худшего срока самого [fetchIpSnapshot], иначе
         * сторож обрывает не зависшую работу, а просто медленную, и заводит вместо неё
         * такую же. Ровно это и было: через туннель Proton снимок стоил ~8,4 с при
         * пороге 8 с, и весь сеанс раз в 8,4 с в лог шло «прерываем зависший IP
         * refresh», а следом — за 20-120 мс — успех той самой «зависшей» задачи.
         * Худший срок снимка ограничен сверху и равен ~7,8 с: 1,8 с литеральный вход
         * по 80 порту плюс [TRACE_STAGE_CAP_MS] на все входы HTTPS.
         */
        private const val IP_REFRESH_RUNNING_STALE_MS = 12_000L

        /** Сколько ждать обновление IP, ещё не получившее поток из пула. */
        private const val IP_REFRESH_QUEUED_STALE_MS = 16_000L

        /**
         * Сколько ещё ждать **основной** источник, когда ответил запасной.
         *
         * Отсчитывается от ответа запасного: до него ждать нечего. Прежние 700 мс
         * были рассчитаны на другую задачу — дозаполнить второе семейство адреса, —
         * и для выбора между источниками они малы: запасной вход анекастовый и с
         * прогретым именем, основной — один далёкий адрес, чьё редкое имя ещё надо
         * разрешить через DoH туннеля, и разрыв больше 700 мс обычен. С коротким
         * сроком экран брал бы запасной ответ при **живом** основном и писал бы в
         * журнал, что основной молчит, — то есть врал бы (I4).
         *
         * Обратный случай ничего не стоит: ответ основного обрывает опрос сразу.
         */
        private const val TRACE_ENTRY_GRACE_MS = 2_500L

        /**
         * Общий предел опроса входов, даже когда не ответил никто.
         *
         * Своих сроков у запросов недостаточно: `HttpURLConnection` отмеряет
         * `connectTimeout` и `readTimeout`, но **не** разрешение имени, а именной вход
         * идёт через DNS туннеля. Замерено на Mi A1: когда у WARP пропадает обратный
         * поток («получено 0 Б при отправленных 1504 Б»), снимок занимал поток пула
         * 12-16 с — дольше, чем [IP_REFRESH_RUNNING_STALE_MS], то есть сторож снова
         * обрывал бы работу, которая просто не может успеть. Лучше вернуть «трассы
         * нет» за шесть секунд: экран покажет прошлое наблюдение и попробует снова
         * через две секунды.
         */
        private const val TRACE_STAGE_CAP_MS = 6_000L

        /**
         * Рядов селектора, под которые считается высота кнопки.
         *
         * Три смысловые полосы плюс строка подрегиона. Владелец попросил, чтобы
         * все четыре аккуратно помещались между кнопкой подключения и
         * «Настройками» — на маленьком экране 36 dp × 4 туда не влезают, и
         * четвёртая полоса уезжала под кнопку.
         */
        private const val SELECTOR_ROWS = 4

        /** Обычная высота кнопки селектора: столько же, сколько у бейджа. */
        private const val MAX_CHIP_HEIGHT_DP = 36f

        /** Ниже этого кнопка перестаёт быть нажимаемой пальцем. */
        private const val MIN_CHIP_HEIGHT_DP = 22f

        /**
         * Запас между последней полосой и «Настройками».
         *
         * Не украшение: `wrap_content` в `ConstraintLayout` границы не соблюдает,
         * и стоит расчёту ошибиться на пару пикселей — строка подрегиона рисуется
         * поверх кнопки. Запас держит эту ошибку в стороне от нуля.
         */
        private const val SELECTOR_BOTTOM_RESERVE_DP = 10f

        /** Отступ между полосами. Совпадает с `layout_marginBottom` в стиле кнопки. */
        private const val CHIP_ROW_GAP_DP = 5f

        /** Меньше этого полосы сливаются в сплошную стену. */
        private const val MIN_CHIP_ROW_GAP_DP = 2f

        /** Какую долю шага полосы занимает отступ, пока места хватает. */
        private const val CHIP_ROW_GAP_SHARE = 0.14f

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

        /**
         * Снять контрольный замер рукопожатия, не гоняя выпуск профилей Proton.
         *
         * Внутри прогона он идёт при поднятом туннеле и потому не может проверить
         * голый путь: сокет «без protect» уходит внутрь туннеля. Отсюда — отдельная
         * точка входа, чтобы снять тот же замер при снятом туннеле.
         */
        private const val ACTION_ADB_PROBE_CONTROL_HANDSHAKE = "PROBE_CONTROL_HANDSHAKE"
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
        /**
         * Снят ли ответ живой трассой сейчас, или подставлен из прошлого наблюдения.
         * Подставленный показать можно, а записывать обратно нельзя: иначе прошлое
         * наблюдение получает свежую отметку времени, становится «измеренным» и
         * дальше подставляется само себе бесконечно.
         */
        val measured: Boolean = true,
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
    private lateinit var tvUpdateProgress: TextView
    private var updateChipState = UpdateDownloadProgress.State.IDLE

    /**
     * Что сейчас написано под статусом. Нужно, чтобы [refreshTransportNotice]
     * можно было звать с тика, не трогая разметку на каждом вызове.
     */
    private var displayedTransportNotice: String? = null
    private val updateChipTicker = Runnable { refreshInstallUpdateButton() }
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
    /** Транспорт, на котором сняты показанные сейчас адреса. См. `checkCurrentIp`. */
    private var lastObservedIpTransport = ""
    /** Последний шаг выпуска профилей Proton — он показывается под статусом. */
    private var protonProgressText = ""
    private val protonProgressListener = ProtonProfileManager.StatusListener { text ->
        runOnUiThread {
            protonProgressText = text
            updateAttemptProgressDisplay()
        }
    }
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
    /**
     * Пул для одновременного опроса входов Cloudflare.
     *
     * Отдельный от [ipExecutor] намеренно. В том три потока, и задача обновления IP
     * работает как раз в нём: разложи она свои ветки по тому же пулу — ожидающие
     * задачи заняли бы все потоки, а ветки, которых они ждут, встали бы в очередь
     * за ними. Демонский cached-пул стоит здесь дёшево: потоки освобождаются сами
     * после минуты простоя, а на снимок их нужно три.
     *
     * Здесь идёт только внешний веер — три входа, и результат нужен от всех трёх.
     * Запасные адреса внутри входа гоняет [ProtonRace] на своём пуле, поэтому
     * ожидающая задача никогда не ждёт задачу этого же пула.
     */
    private val lazyTraceExecutor = lazy {
        Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "nova-trace").apply { isDaemon = true }
        }
    }
    private val traceExecutor: ExecutorService by lazyTraceExecutor

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
                // Выпуск личных профилей идёт в `:vpn` и сообщает о себе файлом:
                // тик экрана — единственное место, где его вообще можно заметить.
                refreshProfileIssueProgress()
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
        NovaRelay.attach(this)
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
        nextProfileCaption = findViewById(R.id.tv_next_profile_caption)
        tvProfileIssueProgress = findViewById(R.id.tv_profile_issue_progress)
        setNextProfileVisible(false)
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
                NovaVpnService.isPublishedTransport(activeTransport, NovaVpnService.TRANSPORT_MASQUE) ->
                    if (importedOnly) NovaVpnService.MANUAL_STEP_VLESS else NovaVpnService.MANUAL_STEP_OPERA_EU
                NovaVpnService.isPublishedTransport(activeTransport, NovaVpnService.TRANSPORT_OPERA) ->
                    if (activeBackend.endsWith("US")) null else NovaVpnService.MANUAL_STEP_OPERA_US
                else -> null
            }
            if (nextChainStep != null) {
                startManualTransportStep(nextChainStep)
                return@setOnClickListener
            }
            // В режиме Proton перебирается список Proton, а не встроенные семена.
            //
            // `getWarpVerifiedMergedConfigs` знает только семена и импорт из настроек:
            // профили Proton лежат в своём файле и подмешиваются к очереди внутри
            // службы. Кнопка поэтому называла узел Cloudflare, служба его не брала —
            // очередь Proton строит другая ветка — и переподнимала тот же профиль.
            // Снаружи это ровно «нажал следующий, а IP в браузере тот же».
            val protonConfigs = clientData.getProtonVerifiedConfigs()
            val configs = if (protonConfigs.isNotEmpty()) {
                protonConfigs
            } else {
                clientData.getWarpVerifiedMergedConfigs()
                    .filter { config ->
                        !config.manual &&
                            if (importedOnly) {
                                config.userImported
                            } else {
                                !config.userImported && clientData.isBundledSeed(config)
                            }
                    }
            }
                .let { filteredConfigs ->
                    // Список Proton пересортировке не подлежит: он уже в том порядке,
                    // в котором его строит служба (`seedOrder` = позиция в файле).
                    // Компаратор встроенных семян дал бы другой порядок, и стороны
                    // считали бы «следующим» разные узлы.
                    if (importedOnly || protonConfigs.isNotEmpty()) {
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
                            // Свежие настройки едут и здесь. Это единственный путь
                            // подключения, который собирал интент руками, — и служба
                            // оставалась со своей кэшированной копией региона. При
                            // выбранном Proton она строила очередь встроенных семян,
                            // а экран продолжал показывать Proton.
                            applyCurrentPreferenceExtras(this)
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
        tvUpdateProgress = findViewById(R.id.tv_update_progress)
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
            // Что сделает нажатие, решает состояние, а не то, что было на экране в
            // момент касания: между отрисовкой и нажатием загрузка могла и
            // закончиться, и оборваться.
            val progress = AppUpdateManager.getDownloadProgress(this)
            when (progress.state) {
                UpdateDownloadProgress.State.DOWNLOADING,
                UpdateDownloadProgress.State.PAUSED -> {
                    if (AppUpdateManager.cancelUserDownload(this)) {
                        Toast.makeText(this, "Загрузка обновления остановлена", Toast.LENGTH_SHORT).show()
                    }
                }
                UpdateDownloadProgress.State.READY -> {
                    // Плашка гаснет сразу же, ещё до первого процента: сессия
                    // установки поднимается в своём потоке, и без этого между
                    // нажатием и первым отчётом оставалось окно, в котором
                    // «Обновить» нажималось второй раз.
                    showUpdateChip(
                        caption = "УСТАНОВКА ОБНОВЛЕНИЯ",
                        captionColor = UPDATE_CHIP_BUSY_COLOR,
                        progressLine = "0%",
                        clickable = false,
                    )
                    AppUpdateManager.installReadyUpdate(this)
                }
                UpdateDownloadProgress.State.INSTALLING,
                UpdateDownloadProgress.State.CHECKING -> Unit
                UpdateDownloadProgress.State.IDLE,
                UpdateDownloadProgress.State.FAILED -> {
                    // Загрузку начинает это нажатие — и только оно.
                    if (AppUpdateManager.startUserRequestedDownload(this)) {
                        Toast.makeText(this, "Скачиваем обновление", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Не удалось начать загрузку обновления", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            refreshInstallUpdateButton()
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
        bindMainRegionSelector()
        TvFocusHelper.install(
            this,
            btnConnect,
            btnInstallUpdate,
            btnSettings,
        )
        // Кнопки селектора тоже обязаны попасть в обход фокуса: на телевизоре без
        // пульта до них иначе не добраться вовсе.
        TvFocusHelper.install(
            this,
            *(mainRegionButtons.toTypedArray()),
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
        maybeHandleWidgetIntent(intent)
        
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
        // Слушатель добавляется здесь, а не в onCreate: добавление сразу отдаёт
        // текущий шаг, и вернувшийся на экран пользователь видит происходящее, а не
        // ждёт следующего.
        ProtonProfileManager.addListener(protonProgressListener)
        resumeProtonPreparationIfPending()
        // Регион мог смениться в настройках, пока экран был свёрнут.
        refreshMainRegionSelector()
        refreshProtonAvailableCountries()
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
        ProtonProfileManager.removeListener(protonProgressListener)
        refreshKeepScreenAwake()
        statusHandler.removeCallbacks(statusRunnable)
        statusHandler.removeCallbacks(deferredNotificationPermissionRunnable)
        statusHandler.removeCallbacks(updateChipTicker)
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
        // Пул ленивый: трогаем его только если он вообще создавался, иначе закрытие
        // экрана само же его и поднимет.
        if (lazyTraceExecutor.isInitialized()) traceExecutor.shutdown()
    }

    private fun getPersistedServiceState(): String = clientData.getServiceState()

    private fun getPersistedServiceBackend(): String = clientData.getServiceBackend()

    /**
     * Плашка обновления: одно состояние — одно действие.
     *
     * Нажать на действие, которое уже идёт, отсюда нельзя по построению: пока
     * обновление качается, единственное, что делает нажатие, — останавливает
     * загрузку; пока оно устанавливается, плашка не нажимается вовсе. Раньше
     * подпись зависела только от «скачано / доступно», и повторные нажатия на
     * «Скачать» уходили в [AppUpdateManager], где их гасил уже он — молча, без
     * следа на экране.
     *
     * Опрос вместо подписки — потому что процент загрузки живёт в
     * `DownloadManager`, а он о своём прогрессе не вещает. Тикер работает только
     * пока состояние деятельное, см. [syncUpdateChipTicker].
     */
    private fun refreshInstallUpdateButton() {
        val progress = AppUpdateManager.getDownloadProgress(this)
        updateChipState = progress.state
        syncUpdateChipTicker()
        when (progress.state) {
            UpdateDownloadProgress.State.DOWNLOADING,
            UpdateDownloadProgress.State.PAUSED -> {
                showUpdateChip(
                    caption = "СКАЧИВАНИЕ ОБНОВЛЕНИЯ",
                    captionColor = UPDATE_CHIP_BUSY_COLOR,
                    // Проценты и единственное доступное действие в одной строке:
                    // человек видит, сколько уже скачано, и чем это прервать.
                    progressLine = if (progress.isIndeterminate) {
                        "ОСТАНОВИТЬ"
                    } else {
                        "${progress.progressPercent}%   ОСТАНОВИТЬ"
                    },
                    clickable = true,
                )
            }
            UpdateDownloadProgress.State.INSTALLING -> {
                showUpdateChip(
                    caption = "УСТАНОВКА ОБНОВЛЕНИЯ",
                    captionColor = UPDATE_CHIP_BUSY_COLOR,
                    progressLine = "${progress.progressPercent}%",
                    // Установку не отменяют: APK уже уходит в системный
                    // установщик, и прерывать его на середине нечем.
                    clickable = false,
                )
            }
            UpdateDownloadProgress.State.CHECKING -> {
                // Проверка не показывается вовсе.
                //
                // Она ничего не обещает и ничем не управляется: плашка стояла
                // ненажимаемой, а проверка на 1.32.0 могла зависнуть — и человек
                // получал кнопку, которая не исчезает и не отвечает на нажатие.
                // Скачивание и установку показывать надо (там есть и прогресс, и
                // «ОСТАНОВИТЬ»), а «мы куда-то сходили и пока не знаем» — нет:
                // как только версия найдётся, плашка появится сама следующим же
                // тиком. Срок самой проверке ставит `AppUpdateManager`.
                btnInstallUpdate.visibility = View.GONE
            }
            UpdateDownloadProgress.State.READY -> {
                // Версию показываем ту, что реально лежит на диске: подпись — это
                // обещание, и оно должно совпадать с тем, что установится по нажатию.
                val readyVersion = progress.version
                    .ifBlank { AppUpdateManager.getReadyDownloadedVersion(this) }
                showUpdateChip(
                    caption = "Обновить до ${formatVersionLabel(readyVersion)}",
                    captionColor = UPDATE_CHIP_IDLE_COLOR,
                    progressLine = "",
                    clickable = true,
                )
            }
            UpdateDownloadProgress.State.FAILED -> {
                showUpdateChip(
                    caption = "ЗАГРУЗКА НЕ УДАЛАСЬ",
                    captionColor = UPDATE_CHIP_FAILED_COLOR,
                    progressLine = "ПОВТОРИТЬ",
                    clickable = true,
                )
            }
            UpdateDownloadProgress.State.IDLE -> {
                // Обновление вышло, но ещё не скачано — плашка всё равно нужна.
                //
                // Пока приложение качало APK само, плашка появлялась только на
                // скачанное и этого хватало. Автозагрузку убрали, и без этой ветки
                // единственным сигналом осталось бы уведомление — а оно не
                // показывается вовсе, если человек запретил уведомления.
                val availableVersion = AppUpdateManager.getAvailableUpdateVersion(this)
                if (availableVersion.isNotBlank()) {
                    showUpdateChip(
                        caption = "Скачать ${formatVersionLabel(availableVersion)}",
                        captionColor = UPDATE_CHIP_IDLE_COLOR,
                        progressLine = "",
                        clickable = true,
                    )
                } else {
                    btnInstallUpdate.visibility = View.GONE
                }
            }
        }
    }

    private fun showUpdateChip(
        caption: String,
        captionColor: Int,
        progressLine: String,
        clickable: Boolean,
    ) {
        tvUpdateCaption.text = caption
        tvUpdateCaption.setTextColor(captionColor)
        if (progressLine.isBlank()) {
            tvUpdateProgress.visibility = View.GONE
        } else {
            tvUpdateProgress.text = progressLine
            tvUpdateProgress.visibility = View.VISIBLE
        }
        // `isClickable` мало: нажатие по неактивной плашке всё равно съедалось бы
        // ею молча. Гасим и её саму — тогда нажатие проходит насквозь, а вид
        // выглядит ровно тем, чем стал: сообщением, а не кнопкой.
        btnInstallUpdate.isEnabled = clickable
        btnInstallUpdate.isClickable = clickable
        btnInstallUpdate.alpha = if (clickable) 1f else 0.85f
        btnInstallUpdate.visibility = View.VISIBLE
    }

    /**
     * Держит опрос прогресса включённым ровно пока есть что опрашивать.
     *
     * Тикер будит главный поток раз в секунду; на экране, который и так рисует
     * фон, кольца и график задержки, оставлять его в покое нельзя.
     */
    private fun syncUpdateChipTicker() {
        val shouldTick = isActivityResumed && when (updateChipState) {
            UpdateDownloadProgress.State.DOWNLOADING,
            UpdateDownloadProgress.State.PAUSED,
            UpdateDownloadProgress.State.CHECKING,
            UpdateDownloadProgress.State.INSTALLING -> true
            else -> false
        }
        statusHandler.removeCallbacks(updateChipTicker)
        if (shouldTick) {
            statusHandler.postDelayed(updateChipTicker, UPDATE_CHIP_TICK_MS)
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


    // -- селектор протокола/региона на главном экране ------------------------

    private var mainRegionScroll: View? = null
    private var mainRegionGroup: RadioGroup? = null
    private var mainRegionButtons: List<RadioButton> = emptyList()
    private var mainRegionNotice: TextView? = null

    // -- четвёртая строка: подрегион выбранного транспорта --------------------

    private var mainSubRegionRow: View? = null
    private var mainSubRegionLabel: TextView? = null
    private var mainSubRegionGroup: FlowRadioGroup? = null

    /**
     * Что сейчас нарисовано в строке подрегионов.
     *
     * Перерисовка селектора случается на каждом кадре состояния, а кнопок в этой
     * строке переменное число, и пересобирать их несколько раз в секунду значило
     * бы каждый раз снимать отметку у пользователя под пальцем. Подпись хранит
     * состав, и группа пересобирается только когда состав правда изменился.
     */
    private var mainSubRegionSignature: String = ""

    /** Тот же заслон, что у основного селектора: программная отметка — не нажатие. */
    private var suppressMainSubRegionCallback = false

    /** Подсказка «Отключите DoT» рядом с выбором входа в Tor. */
    private var mainDotHint: TextView? = null

    /**
     * Страны, которые есть в выпущенных профилях Proton.
     *
     * Кэш, а не чтение по месту: список живёт в `proton_profiles.json`, а
     * перерисовка селектора приходит на каждом кадре состояния — чтение файла
     * оттуда было бы блокирующим вводом-выводом в главном потоке (I13).
     * Обновляется с диска в [refreshProtonAvailableCountries].
     */
    private var protonAvailableCountries: List<String> = emptyList()

    // -- выпуск личных профилей Cloudflare: строка под зелёным статусом --------

    private var tvProfileIssueProgress: TextView? = null

    /** Идёт ли чтение файла состояния — чтобы не заводить второе на каждом тике. */
    private val profileIssueReadInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Сколько показывать итог прогона после его конца.
     *
     * В файле состояние `done`/`failed` остаётся навсегда, и без срока строка
     * «WARP: 50 профилей, лучший 75 мс» висела бы под статусом до следующего
     * выпуска — то есть месяцами.
     */
    private val profileIssueTerminalTtlMs = 30_000L

    /**
     * Строка выпуска личных профилей Cloudflare.
     *
     * Читается **из файла**, а не из синглтона: прогон всегда идёт в процессе
     * `:vpn`, и `WarpProfileGenerator.isRunning()` в интерфейсе всегда false (I2).
     * Чтение уходит с главного потока — файл пишет чужой процесс, и ставить кадры
     * экрана в зависимость от чужой записи нельзя (I13).
     */
    private fun refreshProfileIssueProgress() {
        val view = tvProfileIssueProgress ?: return
        if (!profileIssueReadInFlight.compareAndSet(false, true)) return
        Thread({
            val progress = runCatching { WarpProfileGenerator.readProgress(this) }
                .getOrNull()
            runOnUiThread {
                profileIssueReadInFlight.set(false)
                if (isFinishing || isDestroyed) return@runOnUiThread
                val running = progress?.state == WarpProfileGenerator.STATE_RUNNING
                val terminalFresh = progress != null &&
                    progress.state != WarpProfileGenerator.STATE_IDLE &&
                    progress.atMs > 0L &&
                    System.currentTimeMillis() - progress.atMs < profileIssueTerminalTtlMs
                val message = progress?.message.orEmpty()
                if ((running || terminalFresh) && message.isNotBlank()) {
                    view.visibility = View.VISIBLE
                    view.text = message
                } else {
                    view.visibility = View.GONE
                }
            }
        }, "NovaProfileIssueProgress").apply { isDaemon = true; start() }
    }

    /** Перечитывает страны Proton с диска и перерисовывает четвёртую строку. */
    private fun refreshProtonAvailableCountries() {
        Thread({
            val countries = runCatching { clientData.getProtonAvailableCountries() }.getOrDefault(emptyList())
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (countries == protonAvailableCountries) return@runOnUiThread
                protonAvailableCountries = countries
                // Состав изменился — подпись строки устарела, пересобираем.
                mainSubRegionSignature = ""
                refreshMainRegionSelector()
            }
        }, "NovaProtonCountries").apply { isDaemon = true; start() }
    }

    /**
     * Пока идёт программная простановка отметки, обработчик молчит.
     *
     * `RadioGroup.check` неотличим от нажатия пользователя, а перенастройка
     * случается на каждом кадре состояния — без этого заслона каждая перерисовка
     * запускала бы применение региона.
     */
    private var suppressMainRegionCallback = false

    /** Высота кнопки селектора в пикселях, посчитанная под четыре ряда. */
    private var regionChipHeightPx = 0

    /** Отступ между полосами, посчитанный там же. */
    private var regionChipGapPx = 0

    /**
     * Считает высоту кнопок селектора под четыре ряда.
     *
     * Промежуток между кнопкой подключения и «Настройками» задан не селектором:
     * кнопка подключения центрирована по экрану, «Настройки» привязаны к строке
     * адресов снизу. Значит, высоту кнопок можно подгонять под этот промежуток, не
     * рискуя циклом раскладки — от неё он не зависит.
     *
     * До первой раскладки координаты нулевые: тогда ничего не делаем, а
     * перерисовка селектора приходит несколько раз в секунду и посчитает потом.
     */
    private fun applyRegionChipHeights() {
        if (mainRegionButtons.isEmpty()) return
        val settingsTop = findViewById<View>(R.id.btn_settings)?.top ?: 0
        val connectBottom = if (::btnConnect.isInitialized) btnConnect.bottom else 0
        if (settingsTop <= 0 || connectBottom <= 0 || settingsTop <= connectBottom) return
        val density = resources.displayMetrics.density
        val topMargin = (mainRegionScroll?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
        val usable = settingsTop - connectBottom - topMargin - (SELECTOR_BOTTOM_RESERVE_DP * density).toInt()
        if (usable <= 0) return
        // Сжимается **и** отступ между полосами, а не только сама кнопка.
        //
        // На Mi A1 (1080×1920) промежутка хватает на 4 ряда по 27,7 dp, а нижняя
        // граница высоты кнопки была 28 dp: расчёт упирался в неё, четыре ряда не
        // помещались, и — поскольку `wrap_content` в `ConstraintLayout` границы
        // не соблюдает — строка подрегиона рисовалась поверх «Настроек».
        // Проверено на устройстве.
        val pitch = usable / SELECTOR_ROWS
        val gap = (pitch * CHIP_ROW_GAP_SHARE).toInt().coerceIn(
            (MIN_CHIP_ROW_GAP_DP * density).toInt(),
            (CHIP_ROW_GAP_DP * density).toInt(),
        )
        val height = (pitch - gap).coerceIn(
            (MIN_CHIP_HEIGHT_DP * density).toInt(),
            (MAX_CHIP_HEIGHT_DP * density).toInt(),
        )
        if (height == regionChipHeightPx && gap == regionChipGapPx) return
        regionChipHeightPx = height
        regionChipGapPx = gap
        mainRegionButtons.forEach { button -> applyChipMetrics(button, height, gap) }
        mainSubRegionGroup?.let { group ->
            for (index in 0 until group.childCount) applyChipMetrics(group.getChildAt(index), height, gap)
        }
    }

    private fun applyChipMetrics(view: View?, height: Int, gap: Int) {
        val params = view?.layoutParams ?: return
        val margins = params as? ViewGroup.MarginLayoutParams
        if (params.height == height && (margins == null || margins.bottomMargin == gap)) return
        params.height = height
        margins?.bottomMargin = gap
        view.layoutParams = params
    }

    private fun bindMainRegionSelector() {
        mainRegionScroll = findViewById(R.id.sv_exit_region_main)
        mainRegionGroup = findViewById(R.id.rg_exit_region)
        mainRegionNotice = findViewById(R.id.tv_exit_last)
        mainSubRegionRow = findViewById(R.id.ll_exit_sub_region)
        mainDotHint = findViewById<TextView>(R.id.tv_exit_dot_hint)?.apply {
            setOnClickListener { showPrivateDnsExplanation() }
        }
        mainSubRegionLabel = findViewById(R.id.tv_exit_sub_region_label)
        mainSubRegionGroup = findViewById(R.id.rg_exit_sub_region)
        // Список, порядок и разбиение на строки — из [ConnectionSelectorPolicy],
        // общей с настройками. Пятая копия порядка здесь была бы ровно тем
        // способом, которым случается G49: один список узнаёт о новом значении,
        // остальные молчат.
        (mainRegionGroup as? FlowRadioGroup)?.let { group ->
            group.rowPlan = ConnectionSelectorPolicy.ROWS
            // Владелец попросил левый край и ровные ряды: у центрированных полос
            // 3/2/1 левый край «лесенкой», а при одной ширине кнопок колонки
            // соседних строк встают друг под друга.
            group.alignRowsToStart = true
            group.uniformItemWidth = true
        }
        (mainSubRegionGroup as? FlowRadioGroup)?.let { group ->
            group.alignRowsToStart = true
            group.uniformItemWidth = true
        }
        mainRegionButtons = ConnectionSelectorPolicy.BUTTON_IDS.mapNotNull { findViewById<RadioButton>(it) }
        if (mainRegionButtons.size < ConnectionSelectorPolicy.SIZE) {
            LogManager.log(
                "Главный экран: селектор не собран — найдено ${mainRegionButtons.size} " +
                    "кнопок из ${ConnectionSelectorPolicy.SIZE}."
            )
            mainRegionScroll?.visibility = View.GONE
            return
        }
        // Обработчик подрегионов — тоже один раз и на группу, а не на кнопки:
        // кнопки в ней пересобираются, а обработчик группы это переживает.
        mainSubRegionGroup?.setOnCheckedChangeListener { group, checkedId ->
            if (suppressMainSubRegionCallback) return@setOnCheckedChangeListener
            val value = group.findViewById<RadioButton>(checkedId)?.tag as? String
                ?: return@setOnCheckedChangeListener
            applySubRegionFromMainScreen(value)
        }
        // Обработчик ставится **один раз**, а не на каждой перерисовке.
        //
        // Дефект, который это чинит, был виден на устройстве: `updateUiByState`
        // зовёт перерисовку на каждом кадре состояния, то есть несколько раз в
        // секунду, а перерисовка снимала обработчик, ставила отметку и вешала его
        // обратно. Нажатие, попавшее в это окно, меняло кружок и не делало
        // ничего, а следующая перерисовка возвращала отметку назад — снаружи
        // «кнопка не нажимается». Заслон теперь один: [suppressMainRegionCallback].
        mainRegionGroup?.setOnCheckedChangeListener { _, checkedId ->
            if (suppressMainRegionCallback) return@setOnCheckedChangeListener
            val position = mainRegionButtons.indexOfFirst { it.id == checkedId }
            if (position < 0) return@setOnCheckedChangeListener
            applyChipFromMainScreen(ConnectionSelectorPolicy.valueAt(position))
        }
        refreshMainRegionSelector()
    }

    /**
     * Нажата кнопка селектора. Переводит её значение в то, что ляжет в настройку.
     *
     * OPERA — одна кнопка на два значения службы (`eu`/`us`), и выбирается тот
     * подрегион, который пользователь выбирал в прошлый раз. TOR — не смена
     * региона вовсе: транспорта ещё нет, и записать `tor` значило бы отправить
     * службу перебирать пустоту. Вместо этого запускается сбор мостов, а строка
     * под селектором честно говорит, что именно происходит (I4).
     */
    private fun applyChipFromMainScreen(chipValue: String) {
        if (chipValue == ConnectionSelectorPolicy.CHIP_TOR) {
            LogManager.log("Главный экран: выбран TOR.")
            // Сбор мостов запускается заранее, а не в момент подключения: он идёт
            // по сети десятками секунд, и делать его при уже нажатом «Подключить»
            // означало бы минуту молчания вместо туннеля. Решение и чтение файла —
            // с рабочего потока (I13), надпись — с главного. Текст описывает то,
            // что произошло: сбор мог и не начаться, если список свежий.
            Thread({
                val started = TorBridgeManager.refreshInBackground(
                    this,
                    reason = "выбор TOR на главном экране",
                )
                val known = TorBridgeManager.snapshot(this).bridges.size
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(
                        this,
                        ConnectionSelectorPolicy.torNoticeFor(started, known),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }, "NovaTorChipTap").apply { isDaemon = true; start() }
        }
        applyRegionFromMainScreen(
            ConnectionSelectorPolicy.storedValueForChip(
                chipValue,
                clientData.getOperaSubRegionPreference(),
            )
        )
    }

    /**
     * Нажат подрегион в четвёртой строке.
     *
     * Для Opera это `eu`/`us` — то же самое, что раньше делали две отдельные
     * кнопки, поэтому путь тот же [applyRegionFromMainScreen]: живой сеанс он
     * переподключает безопасным `stop-then-start` (G3).
     * Для Proton это страна выхода. Транспорт она не меняет — только фильтрует
     * очередь профилей, — но живой сеанс всё равно надо переподнять: очередь
     * строится один раз, при подключении.
     *
     * Раньше сессия здесь не перезапускалась вовсе, и это читалось как поломка
     * ровно так, как её и описал владелец: «US не подключается». Предпочтение
     * записывалось (`exit_region.json` честно показывал `US`), кнопка US
     * загоралась, профили US на устройстве были — а бейдж до конца сеанса
     * показывал прежний `AWG PROTON: NL`, и адрес не менялся. Это тот же
     * рассинхрон, ради которого переподключение уже сделано у самой кнопки
     * PROTON и в настройках.
     */
    /**
     * Объясняет, почему при системном строгом DoT через Tor не открывается ничего.
     *
     * Отдельным диалогом, а не длинной строкой на экране: строка занимает место
     * у кнопки подключения, а сказать надо три вещи — что происходит, почему это
     * не поломка Nova и что именно переключить. Кнопка ведёт прямо в тот раздел
     * настроек Android; если производитель его прячет, открывается общий раздел
     * сети — молча не открыть ничего было бы хуже (I4).
     */
    private fun showPrivateDnsExplanation() {
        val host = TorTransport.strictPrivateDnsHost(this)
        val message = "В настройках телефона включён «Частный DNS» в строгом режиме" +
            (if (host.isNotBlank()) " ($host)" else "") + ".\n\n" +
            "В этом режиме Android резолвит имена только через DoT к этому серверу и никогда " +
            "не спрашивает их обычным запросом. Через Tor такой DoT не проходит, поэтому " +
            "туннель поднимется и пинги будут идти, а ни один сайт не откроется — браузер " +
            "покажет ERR_NAME_NOT_RESOLVED.\n\n" +
            "Приложение это исправить не может: настройка системная. Откройте настройки " +
            "телефона и переключите «Частный DNS» на «Автоматически» или «Выключено»."
        // Диалог строится в теме настроек, а не в теме главного экрана: главный
        // экран своих `nova*` не объявляет, и без обёртки диалог приезжает
        // системным серым, не похожим на остальное приложение.
        val themed = android.view.ContextThemeWrapper(
            this,
            NovaTheme.optionFor(NovaTheme.current(this)).styleRes,
        )
        android.app.AlertDialog.Builder(themed)
            .setTitle("Частный DNS мешает Tor")
            .setMessage(message)
            .setPositiveButton("Открыть настройки") { _, _ -> openPrivateDnsSettings() }
            .setNegativeButton("Понятно", null)
            .showNova()
    }

    private fun openPrivateDnsSettings() {
        // `PRIVATE_DNS_SETTINGS` в открытом SDK нет, но строку понимают штатные
        // настройки Android; там, где её нет, остаётся общий раздел сети.
        val candidates = listOf(
            Intent("android.settings.PRIVATE_DNS_SETTINGS"),
            Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS),
        )
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val opened = runCatching { startActivity(intent); true }.getOrDefault(false)
            if (opened) return
        }
        Toast.makeText(
            this,
            "Не удалось открыть настройки. «Частный DNS» — в разделе «Сеть и интернет».",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun applySubRegionFromMainScreen(value: String) {
        val chip = ConnectionSelectorPolicy.valueAt(
            ConnectionSelectorPolicy.selectedIndex(
                storedRegion = clientData.getExitRegionPreference(),
                protonPreparationRequested = clientData.isProtonPreparationRequested(),
            )
        )
        if (chip == "proton") {
            val previous = clientData.getProtonCountryPreference().trim()
            clientData.setProtonCountryPreference(value)
            val label = value.ifBlank { "AUTO" }
            // Переподключаем только на смене страны: повторное нажатие на уже
            // выбранную кнопку не должно ронять живой туннель.
            val changed = !previous.equals(value.trim(), ignoreCase = true)
            val reconnecting = changed &&
                SessionReapply.isSessionLikelyActive(this, clientData) &&
                SessionReapply.applyToLiveSession(this, clientData)
            LogManager.log(
                "Главный экран: страна выхода Proton — $label, смена=$changed, переподключение=$reconnecting."
            )
            Toast.makeText(
                this,
                if (reconnecting) "Страна Proton: $label, переподключаемся" else "Страна Proton: $label",
                Toast.LENGTH_SHORT,
            ).show()
            if (reconnecting) {
                updateUiByState(NovaVpnService.STATE_CONNECTING)
            }
            refreshMainRegionSelector()
            return
        }
        if (chip == ConnectionSelectorPolicy.CHIP_TOR) {
            val previous = TorEntryModeStore.read(this)
            val chosen = ConnectionSelectorPolicy.normalizeTorEntry(value)
            val changed = previous != chosen
            val label = ConnectionSelectorPolicy.TOR_ENTRY_MODES
                .firstOrNull { it.first == chosen }?.second ?: chosen
            // Переподключаем только на смене: повторное нажатие на уже выбранный
            // способ не должно ронять живой туннель.
            val reconnecting = changed && SessionReapply.isSessionLikelyActive(this, clientData)
            // Запись в файл — это диск, а диск на главном потоке запрещён (I13).
            // Поток свой, а не `lifecycleScope`: правка обязана дожить до конца,
            // даже если экран закроют сразу после нажатия (I18).
            //
            // Реаплай уходит **из того же потока и только после записи**: раньше
            // он отправлялся сразу, а файл дописывался параллельно, и служба в
            // `:vpn` успевала прочитать прежний способ входа — то есть человек
            // жал «obfs4», видел «переподключаемся» и получал прежний вход.
            // Контекст берётся приложения: поток переживает экран.
            val appContext = applicationContext
            Thread({
                TorEntryModeStore.write(appContext, chosen)
                if (reconnecting) {
                    SessionReapply.applyToLiveSession(appContext, ClientData(appContext))
                }
            }, "NovaTorEntryWrite").apply { isDaemon = true; start() }
            LogManager.log(
                "Главный экран: вход в Tor — $label, смена=$changed, переподключение=$reconnecting."
            )
            Toast.makeText(
                this,
                if (reconnecting) "Вход в Tor: $label, переподключаемся" else "Вход в Tor: $label",
                Toast.LENGTH_SHORT,
            ).show()
            if (reconnecting) {
                updateUiByState(NovaVpnService.STATE_CONNECTING)
            }
            refreshMainRegionSelector()
            return
        }
        clientData.setOperaSubRegionPreference(value)
        applyRegionFromMainScreen(value)
    }

    /**
     * Перерисовывает селектор по текущему состоянию.
     *
     * Зовётся из каждого пути отрисовки, а не только из одного: регион теперь
     * меняется, не уходя с экрана, и отметка, поставленная один раз при старте,
     * устаревала бы при первом же переключении.
     */
    private fun refreshMainRegionSelector() {
        val group = mainRegionGroup ?: return
        val buttons = mainRegionButtons
        if (buttons.size < ConnectionSelectorPolicy.SIZE) return
        val notice = mainRegionNotice

        // В режиме импортированных профилей выбор делают не регионы, а семейства
        // протоколов, и те же шесть кнопок в настройках перекрашиваются и
        // переименовываются под них. Показать здесь подписи регионов над
        // импортированной семантикой значило бы соврать, поэтому селектор
        // прячется, а вход в «Конфигурации» остаётся — там выбор и живёт.
        if (clientData.isImportedConfigSourceActive()) {
            mainRegionScroll?.visibility = View.GONE
            notice?.visibility = View.VISIBLE
            notice?.text =
                "Активны импортированные конфигурации — протокол выбирается в «Настройках» → «Конфигурации»"
            return
        }
        mainRegionScroll?.visibility = View.VISIBLE

        // Предпочтение читается **один раз** за проход: это чтение `AtomicFile`, а
        // проход приходит несколько раз в секунду, и до этого оно стояло здесь
        // трижды подряд с гарантированно одинаковым ответом (I13).
        val storedRegion = clientData.getExitRegionPreference()
        val availability = ConnectionSelectorPolicy.availability(
            operaSupported = OperaProxyManager.isSupportedOnDevice(this),
            deviceRegistrationInProgress = clientData.isDeviceRegistrationInProgress(),
            storedRegion = storedRegion,
        )
        buttons.forEachIndexed { index, button ->
            // Подпись ставится только когда она правда другая: `TextView` при
            // `wrap_content` уходит в `requestLayout` даже на том же тексте, а
            // это полный обход measure/layout окна на каждом тике.
            val label = ConnectionSelectorPolicy.LABELS.getOrNull(index).orEmpty()
            if (button.text?.toString() != label) button.text = label
            val allowed = availability.enabled.getOrNull(index) ?: true
            if (button.isEnabled != allowed) button.isEnabled = allowed
            val alpha = if (allowed) 1f else ConnectionSelectorPolicy.DISABLED_ALPHA
            if (button.alpha != alpha) button.alpha = alpha
        }
        // Переписывание недостижимого выбора делает экран настроек: оно показывает
        // всплывающее сообщение, и два экрана показали бы его дважды. Здесь только
        // гасим кнопки.

        val index = ConnectionSelectorPolicy.selectedIndex(
            storedRegion = storedRegion,
            protonPreparationRequested = clientData.isProtonPreparationRequested(),
        )
        val wanted = buttons.getOrNull(index)
        if (wanted != null && group.checkedRadioButtonId != wanted.id) {
            // Отметка двигается только когда она и правда не та. Лишний `check`
            // на каждом кадре означал бы лишнее подавленное срабатывание.
            suppressMainRegionCallback = true
            group.check(wanted.id)
            suppressMainRegionCallback = false
        }

        refreshMainSubRegionRow(ConnectionSelectorPolicy.valueAt(index))
        // Высота считается здесь, а не один раз при сборке: до первой раскладки
        // координат ещё нет, а перерисовка приходит несколько раз в секунду.
        // Повторный счёт стоит два сравнения — менять что-либо она будет только
        // когда результат правда изменился.
        applyRegionChipHeights()

        // Запрет держится выключенными кнопками, а не снятым обработчиком:
        // выключенную кнопку нажать нельзя, и снимать обработчик — значит терять
        // нажатия в окне между снятием и возвратом.
        if (availability.lockReason.isNotBlank()) {
            notice?.visibility = View.VISIBLE
            notice?.text = availability.lockReason
            return
        }
        notice?.visibility = View.GONE
    }

    /**
     * Четвёртая строка — подрегион выбранного транспорта.
     *
     * Для OPERA это «Регион: EU US», для PROTON — страны выпущенных профилей в
     * порядке [CountryDisplayOrder]. Для остальных кнопок строки нет: подрегиона
     * у них не существует, а пустая строка отнимала бы место у кнопки подключения.
     *
     * Состав кнопок пересобирается **только когда он изменился**: перерисовка
     * приходит несколько раз в секунду, и сборка на каждом кадре снимала бы
     * отметку прямо под пальцем (тот же класс дефекта, что G95).
     */
    private fun refreshMainSubRegionRow(chipValue: String) {
        val row = mainSubRegionRow ?: return
        val group = mainSubRegionGroup ?: return
        // Подсказка про DoT нужна только там, где она что-то меняет: у Tor и
        // только при включённом строгом «Частном DNS».
        mainDotHint?.visibility = if (
            chipValue == ConnectionSelectorPolicy.CHIP_TOR &&
            TorTransport.strictPrivateDnsHost(this).isNotBlank()
        ) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val entries = ConnectionSelectorPolicy.subRegionsFor(
            chipValue,
            if (chipValue == "proton") protonAvailableCountries else emptyList(),
        )
        if (entries.isEmpty()) {
            row.visibility = View.GONE
            mainSubRegionSignature = ""
            // `removeAllViews` зовёт `requestLayout` даже на пустой группе, а для
            // AUTO/WARP/MASQUE/TOR пустая — это состояние по умолчанию, и сюда
            // заходят несколько раз в секунду.
            if (group.childCount > 0) group.removeAllViews()
            return
        }
        row.visibility = View.VISIBLE
        mainSubRegionLabel?.text = ConnectionSelectorPolicy.subRegionPrefixFor(chipValue).trimEnd()
        // Подрегион — всегда **одна** строка, сколько бы стран ни выпустил Proton.
        // Без плана перенос считается по ширине, а внутри `HorizontalScrollView`
        // ширина приходит спецификацией `UNSPECIFIED`, то есть нулём: строка
        // рассыпалась на несколько. План выключает перенос вовсе — лишнее уезжает
        // вбок и прокручивается пальцем, как и три основные полосы.
        (mainSubRegionGroup as? FlowRadioGroup)?.rowPlan = listOf(entries.size)
        val signature = chipValue + "|" + entries.joinToString(",") { it.first }
        if (signature != mainSubRegionSignature) {
            mainSubRegionSignature = signature
            suppressMainSubRegionCallback = true
            group.removeAllViews()
            val themed = android.view.ContextThemeWrapper(this, R.style.NovaSubRegionChip)
            val density = resources.displayMetrics.density
            entries.forEach { (value, label) ->
                val chip = RadioButton(themed, null, 0)
                chip.id = View.generateViewId()
                chip.tag = value
                chip.text = label
                // Нажимаемость задаётся явно, и это не перестраховка.
                //
                // Третий аргумент конструктора — `defStyleAttr = 0`: он нужен,
                // чтобы наш стиль-тема не был перебит штатным `radioButtonStyle`,
                // но вместе с ним теряется и всё, что этот стиль даёт по части
                // поведения. На устройстве это выглядело так: строка «Регион: EU US»
                // рисуется, отметка стоит, а нажатия не делают ничего —
                // `uiautomator` показывал у обеих кнопок `clickable="false"`.
                chip.isClickable = true
                chip.isFocusable = true
                chip.isSaveEnabled = false
                // Отступы задаются кодом, а не стилем.
                //
                // `ContextThemeWrapper` применяет стиль как **тему**: атрибуты
                // самого вида (фон, поля, размер текста) она разрешает, а
                // `layout_*` читает родитель из `AttributeSet` — здесь он `null`,
                // и `RadioGroup` подставляет свои умолчания с нулевыми полями. То
                // есть объявленные в стиле `layout_marginEnd`/`layout_marginBottom`
                // молча не действовали, и обводки соседних кнопок соприкасались.
                // Высота — тоже здесь, и по той же причине, что и отступы: это
                // `layout_*`, родитель читает её из `AttributeSet`, а он `null`.
                // Владелец попросил одинаковые кнопки, а из стиля-темы высота не
                // применяется вовсе, и подкнопки выходили вдвое ниже основных.
                val params = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.WRAP_CONTENT,
                    regionChipHeightPx.takeIf { it > 0 } ?: (MAX_CHIP_HEIGHT_DP * density).toInt(),
                )
                params.marginEnd = (6 * density).toInt()
                // Отступ тот же, что у основных полос: строка подрегиона — такой
                // же ряд, и в расчёт четырёх рядов она входит наравне.
                params.bottomMargin = regionChipGapPx.takeIf { it > 0 }
                    ?: (CHIP_ROW_GAP_DP * density).toInt()
                group.addView(chip, params)
                NovaFontHelper.apply(chip)
            }
            suppressMainSubRegionCallback = false
        }
        val wantedValue = when (chipValue) {
            // Пустое предпочтение — это кнопка «AUTO», а не первая страна списка.
            "proton" -> clientData.getProtonCountryPreference()
            // Способ входа в Tor лежит в файле, а не в настройках: его читает
            // процесс `:vpn`, а prefs кэшируются попроцессно (I2).
            ConnectionSelectorPolicy.CHIP_TOR -> TorEntryModeStore.read(this)
            else -> ConnectionSelectorPolicy.normalizeOperaSubRegion(clientData.getOperaSubRegionPreference())
        }
        val wanted = (0 until group.childCount)
            .mapNotNull { group.getChildAt(it) as? RadioButton }
            .firstOrNull { it.tag == wantedValue }
        if (wanted != null && group.checkedRadioButtonId != wanted.id) {
            suppressMainSubRegionCallback = true
            group.check(wanted.id)
            suppressMainSubRegionCallback = false
        }
    }

    /**
     * Применяет выбор, сделанный на главном экране.
     *
     * Три случая, и они честно разные.
     *
     * * **PROTON** — это не смена региона, а запуск выпуска профилей: предпочтение
     *   до успеха не записывается, иначе служба пошла бы перебирать пустой список
     *   и погасла бы ровно тогда, когда туннель нужен для самого выпуска.
     * * **Живой сеанс** — переподключается сразу, тем же порядком, что и в
     *   настройках: [SessionReapply] сам выбирает между мягким реаплаем и
     *   безопасным `stop-then-start`, который нужен Opera (G3).
     * * **Выключенный VPN** — предпочтение записывается и применится при
     *   следующем подключении; экран говорит об этом словами (I4).
     */
    private fun applyRegionFromMainScreen(value: String) {
        if (value == "proton") {
            LogManager.log("Главный экран: выбран PROTON — запускаем выпуск профилей.")
            clientData.setProtonPreparationRequested(true)
            ProtonProfileManager.markPreparationRequested()
            // Итог обязан быть обработан, иначе прогон некому закончить.
            //
            // Без этого замыкания признак `proton_preparation.json` не снимался
            // никем: `ProtonProfileManager` его не пишет, а он один и решает,
            // рисовать ли жёлтую «РЕГИСТРАЦИЯ PROTON» поверх экрана. То есть после
            // успешного выпуска надпись висела навсегда, адрес и страна были
            // погашены, а сам регион оставался прежним — кнопка показывала PROTON,
            // подключение шло старым транспортом. Это и есть молчаливая подмена
            // явного выбора (I1); та же обработка стоит в настройках и в
            // продолжении брошенного выпуска.
            val started = ProtonProfileManager.ensureProfiles(this, background = false) { outcome ->
                // Итог применяется, **только если пользователь всё ещё хочет Proton**.
                //
                // Прогон идёт десятки секунд, и за это время можно нажать WARP:
                // тогда ветка ниже уже отменила подготовку и записала свой регион.
                // Безусловная запись «proton» поверх него — это молчаливая подмена
                // явного выбора (I1), да ещё на живой чужой сессии. Тот же заслон
                // стоит в настройках.
                val stillWanted = ProtonProfileManager.isPreparationRequested()
                if (!stillWanted) {
                    LogManager.log(
                        "Главный экран: выпуск Proton закончился, но пользователь уже выбрал другой " +
                            "транспорт — итог не применяем."
                    )
                    return@ensureProfiles
                }
                clientData.setProtonPreparationRequested(false)
                ProtonProfileManager.cancelPreparation()
                var reconnecting = false
                if (outcome.ready) {
                    clientData.setExitRegionPreference("proton")
                    // Записанный регион сам по себе туннель не меняет.
                    //
                    // Без этого шага PROTON был единственной кнопкой селектора,
                    // которая живой сеанс не переподключала: предпочтение стало
                    // `proton`, кнопка горит PROTON, а под зелёным «АКТИВНО» до
                    // конца сеанса идёт прежний транспорт. Ровно тот рассинхрон,
                    // ради которого немедленное переподключение и делалось; в
                    // настройках этот же путь давно идёт через реаплай.
                    reconnecting = SessionReapply.isSessionLikelyActive(this, clientData) &&
                        SessionReapply.applyToLiveSession(this, clientData)
                    LogManager.log(
                        "Главный экран: выпуск Proton закончен, профилей ${outcome.profiles.size} — " +
                            "регион записан, переподключение=$reconnecting."
                    )
                } else {
                    LogManager.log("Главный экран: выпуск Proton не удался — ${outcome.message}")
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
                    refreshProtonAvailableCountries()
                    if (reconnecting) {
                        updateUiByState(NovaVpnService.STATE_CONNECTING)
                    } else {
                        updateUiByState(null)
                    }
                }
            }
            if (!started) {
                // Прогон уже идёт — и признаки принадлежат **ему**, а не этому
                // нажатию. Снимать их здесь нельзя: обработчик итога того прогона
                // читает `isPreparationRequested()` и по снятому признаку решает,
                // что пользователь передумал, — то есть выпуск доходил до конца
                // успешно, а регион не записывался и переподключения не было.
                // Воспроизводилось тремя нажатиями подряд: PROTON → WARP → PROTON.
                //
                // Поэтому признаки **переподвешиваются** на идущий прогон, ровно
                // как в настройках, а отметка остаётся на PROTON. Молчать при этом
                // всё равно нельзя (I4) — говорим словами, что выпуск уже идёт.
                LogManager.log("Главный экран: выпуск Proton уже идёт — подписываемся на его итог.")
                ProtonProfileManager.addListener(object : ProtonProfileManager.StatusListener {
                    override fun onStatus(text: String) {
                        if (ProtonProfileManager.isRunning()) return
                        ProtonProfileManager.removeListener(this)
                        // Свой обработчик у того прогона уже снял бы признаки сам;
                        // снимаем только то, что могло остаться от нашего вызова.
                        if (!ProtonProfileManager.isPreparationRequested()) {
                            clientData.setProtonPreparationRequested(false)
                        }
                    }
                })
                Toast.makeText(this, "Выпуск профилей Proton уже идёт", Toast.LENGTH_SHORT).show()
                refreshMainRegionSelector()
                return
            }
            Toast.makeText(this, "Готовим профили Proton...", Toast.LENGTH_SHORT).show()
            refreshMainRegionSelector()
            return
        }
        val previous = clientData.getExitRegionPreference()
        // Выбор другого транспорта отменяет выпуск Proton — и в синглтоне, и в
        // файле: иначе главный экран продолжал бы рисовать «РЕГИСТРАЦИЯ PROTON»
        // поверх нового выбора, а брошенный выпуск заводился бы заново.
        ProtonProfileManager.cancelPreparation()
        clientData.setProtonPreparationRequested(false)
        clientData.setExitRegionPreference(value)
        LogManager.log("Главный экран: протокол/регион изменён на $value (было $previous).")

        val label = ConnectionSelectorPolicy.LABELS
            .getOrNull(ConnectionSelectorPolicy.indexOf(value))
            .orEmpty()
        if (!SessionReapply.isSessionLikelyActive(this, clientData)) {
            Toast.makeText(this, "Выбрано: $label", Toast.LENGTH_SHORT).show()
            refreshMainRegionSelector()
            return
        }
        // Живой сеанс переподключается **сразу** — тем же порядком, которым это
        // делают настройки. Раньше здесь стояла усечённая копия: живая Opera не
        // переключалась вовсе, а обычный реаплай шёл без записи состояния и без
        // признака мягкого применения, и экран успевал показать «НЕ ПОДКЛЮЧЕНО».
        // Выбор пути (обычный реаплай или безопасный stop-then-start для Opera)
        // принимает [SessionReapply]: он один знает про exit(-1) в tun2proxy (G3).
        val started = SessionReapply.applyToLiveSession(this, clientData)
        if (started) {
            Toast.makeText(this, "Переключаем VPN на $label...", Toast.LENGTH_SHORT).show()
            updateUiByState(NovaVpnService.STATE_CONNECTING)
        } else {
            // Отказ запуска молчать не должен (I4): предпочтение уже записано, и
            // без слов это выглядит как «кнопка не сработала».
            Toast.makeText(
                this,
                "Не удалось переключить на $label — выбор сохранён, примените переподключением.",
                Toast.LENGTH_LONG,
            ).show()
        }
        refreshMainRegionSelector()
    }

    /**
     * Дополнительные поля реаплая — из [SessionReapply], а не своим списком.
     *
     * Список полей был выписан здесь второй копией, и пропущенное в одной из них
     * поле означает молча применённое старое значение. Теперь копия одна, а этот
     * метод только переносит её в чужое намерение.
     */
    private fun applyCurrentPreferenceExtras(intent: Intent) {
        intent.putExtras(SessionReapply.buildIntent(this, clientData))
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
        // Выбранный TOR подписывает бейдж сам и раньше всех догадок ниже: у него
        // нет ни региона Opera, ни конфигурации WARP, по которым эти догадки
        // строятся, и без этой строки экран показывал «WARP» на живом Tor.
        if (clientData.getExitRegionPreference().trim().lowercase() == "tor") {
            return NovaVpnService.BACKEND_TOR
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

    /**
     * Владелец VPN-сети: наш, чужой или **неизвестен**. Третье состояние отдельное,
     * потому что Android 9 вычищает `EstablishingAppUid` из копии
     * `NetworkCapabilities`, которую отдаёт приложению, а метка сессии есть только
     * с Android 10. Там «владелец не прочитался» — это всегда, и считать такую сеть
     * чужой значит показывать бейдж не того транспорта (P16).
     */
    private enum class VpnOwnership { OURS, FOREIGN, UNKNOWN }

    private fun classifyVpnOwnership(caps: NetworkCapabilities?): VpnOwnership {
        if (caps == null) return VpnOwnership.UNKNOWN
        val ownerUid = extractVpnOwnerUid(caps)
        if (ownerUid != null) {
            return if (ownerUid == applicationInfo.uid) VpnOwnership.OURS else VpnOwnership.FOREIGN
        }
        val transportInfo = extractVpnTransportLabel(caps)
        if (transportInfo.isBlank()) return VpnOwnership.UNKNOWN
        return if (
            transportInfo.contains("NovaVPN", ignoreCase = true) ||
            transportInfo.contains("NovaOperaVPN", ignoreCase = true)
        ) {
            VpnOwnership.OURS
        } else {
            VpnOwnership.FOREIGN
        }
    }

    /** «Сеть не чужая»: наша по владельцу либо владельца не прочитать, а сеанс наш. */
    private fun isVpnNetworkNotForeign(network: Network?): Boolean {
        if (network == null) return false
        val cm = getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        return when (classifyVpnOwnership(caps)) {
            VpnOwnership.OURS -> true
            VpnOwnership.FOREIGN -> false
            VpnOwnership.UNKNOWN -> hasStrongLocalNovaSessionEvidence()
        }
    }

    private fun isSystemVpnLikelyNova(network: Network): Boolean {
        return isVpnNetworkNotForeign(network)
    }

    private fun inferBackendFromActiveVpn(network: Network?): String {
        if (network != null) {
            val cm = getSystemService(ConnectivityManager::class.java)
            val caps = cm?.getNetworkCapabilities(network)
            // Не `isNovaVpnOwner`: на Android 9 владелец приложению не виден, и самая
            // достоверная ветка подписи бейджа не выполнялась никогда — метка уезжала
            // в запасные догадки ниже.
            if (isVpnNetworkNotForeign(network)) {
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

    private fun isTorBackend(backendLabel: String): Boolean {
        return backendLabel.trim().uppercase().startsWith(NovaVpnService.BACKEND_TOR)
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
        if (clientData.getExitRegionPreference().trim().lowercase() == "tor") {
            return NovaVpnService.BACKEND_TOR
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
            // С числами, а не «зависший».
            //
            // На Mi A1 эта строка идёт каждые ~8,4 с всю сессию, а следом за ней —
            // «snapshot получен» за 30-200 мс: то есть новая задача успевает, а флаг
            // к следующему тику снова занят, и предыдущая его не отпустила. Без
            // деления «работала N мс» / «стояла в очереди N мс» это одинаково
            // выглядит и как незавершённая задача, и как несовпадение поколений в
            // `finally` — диагнозы разные, лечение разное (G11).
            LogManager.log(
                "UI checkCurrentIp: прерываем зависший IP refresh и запускаем новый для живого туннеля " +
                    if (startedAt > 0L) "(работала ${now - startedAt} мс)."
                    else "(стояла в очереди ${now - ipRefreshQueuedAtMs} мс)."
            )
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
                    } else if (isVlessBackend(resolvedBackend) || isTorBackend(resolvedBackend)) {
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
                    val line = "UI checkCurrentIp: " +
                        (if (effectiveSnapshot.measured) "snapshot получен" else "показано прошлое наблюдение") +
                        ", ip=${primaryIp.ifBlank { "-" }}, " +
                        "country=${effectiveSnapshot.country.ifBlank { "-" }}, backend=$resolvedBackend, " +
                        "transport=${clientData.getServiceTransport().ifBlank { "-" }}"
                    // Проверка идёт по кругу, пока экран открыт, и до этой правки
                    // писала одну и ту же строку каждые несколько секунд — 16 раз за
                    // одно подключение на замере Pixel 4a. Смысл в ней только тогда,
                    // когда что-то изменилось: адрес, страна, бэкенд или транспорт.
                    if (line != lastExitSnapshotLine) {
                        lastExitSnapshotLine = line
                        LogManager.log(line)
                    }
                    // Удачный замер закрывает прошлую причину: если она вернётся,
                    // это уже новое событие, и увидеть его надо.
                    if (effectiveSnapshot.measured) lastIpFallbackLine = null
                }

                if (
                    tunnelNetwork != null &&
                    primaryIp.isNotBlank() &&
                    // Подставленное наблюдение обратно не пишется: запись обновила бы
                    // ему отметку времени, прошлый выход стал бы «свежим» и дальше
                    // подставлялся бы сам себе без конца.
                    effectiveSnapshot.measured &&
                    // Для VLESS экран лишь пересказывает наблюдение службы. Записывать
                    // его обратно нельзя: любое своё измерение здесь идёт мимо узла и
                    // затирало бы честное наблюдение адресом провайдера.
                    !isVlessBackend(resolvedBackend) &&
                    !isTorBackend(resolvedBackend)
                ) {
                    clientData.saveLastExitObservation(
                        ip = primaryIp,
                        country = effectiveSnapshot.country,
                        // Узел экран не измеряет вовсе: [ExitAddress] его не
                        // сообщает, а спрашивать `/cdn-cgi/trace` отдельно — дело
                        // службы, и только при включённом обходе узла. Записать
                        // сюда пустую строку значило бы затирать измеренное
                        // службой значение каждые две секунды: тик экрана идёт
                        // чаще, чем замер выхода, и «Последний выход: RU / ? / …»
                        // стало бы постоянным.
                        colo = effectiveSnapshot.colo.ifBlank { clientData.getLastExitColo() },
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
                    // Показанные адреса привязаны к транспорту, на котором их сняли.
                    //
                    // `stabilizeObservedIp` при пустом наблюдении оставляет прежнее
                    // значение — это защита от мигания внутри сеанса. Через смену
                    // транспорта она превращалась в ложь: у MASQUE MTU 1179 < 1280,
                    // IPv6 внутри туннеля не поднимается вовсе, и экран продолжал
                    // показывать IPv6 предыдущего выхода Opera US как свой текущий.
                    val observedTransport = clientData.getServiceTransport().ifBlank { resolvedBackend }
                    if (observedTransport != lastObservedIpTransport) {
                        lastObservedIpTransport = observedTransport
                        currentIpv4 = "..."
                        currentIpv6 = "..."
                        ipv4Candidate.value = ""
                        ipv4Candidate.seenCount = 0
                        ipv6Candidate.value = ""
                        ipv6Candidate.seenCount = 0
                        // Страну сбрасываем вместе с адресами. Она держится за прежнее
                        // значение по той же причине и врёт так же: на импортированном
                        // AWG с выходом NL бейдж показывал «AWG: RU», потому что
                        // трасса не дошла, а `RU` осталась от прошлого сеанса WARP.
                        // «--» честнее любого прошлого ответа (I10).
                        currentCountry = "--"
                    }
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
                            // Транспорт — то единственное, что отличает сессию Proton от
                            // сессии встроенного WARP: бэкенд у них один и тот же.
                            transport = observedTransport,
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
        // Бэкенд не опознаёт сессию: `WARP` — общая метка для встроенных семян,
        // MASQUE, импортированного AWG и Proton. А Proton перед выпуском профилей сам
        // поднимает обычный WARP с российским выходом — снимок той сессии проходил
        // проверку по бэкенду и показывался поверх живого узла US/NL. Отсюда «страна
        // RU вместо US». Привязываемся к транспорту, как уже сделано для полей
        // экрана (G55).
        val activeTransport = clientData.getServiceTransport().ifBlank { resolvedBackend }
        val tunnelSnapshot = clientData.getTunnelUiSnapshot()
        // Транспорт сверяется с тем, что записан **в самом снимке**, а не с тем, что
        // экран показывал в прошлый раз.
        //
        // Прежнее сравнение отвечало на нужный вопрос лишь косвенно и на смене
        // транспорта запиралось само на себе: снимок отвергался, показывать было
        // нечего, `lastObservedIpTransport` не обновлялся — и следующий круг
        // отвергал снимок ровно по той же причине. На Tor это и наблюдалось:
        // служба измеряла выход через цепочку (FI), а бейдж показывал «TOR: --»
        // бесконечно. Когда транспорт в снимке известен, он и есть ответ; поле
        // экрана остаётся запасным для старых снимков без этой отметки.
        val snapshotTransport = tunnelSnapshot?.transport?.trim().orEmpty()
        val transportMatches = if (snapshotTransport.isNotBlank()) {
            snapshotTransport.equals(activeTransport, ignoreCase = true)
        } else {
            activeTransport == lastObservedIpTransport
        }
        if (!transportMatches) {
            logIpFallbackOnce(
                "UI checkCurrentIp: трасса не дошла, но снимок снят на другом транспорте " +
                    "(${snapshotTransport.ifBlank { lastObservedIpTransport }} вместо $activeTransport) — " +
                    "прошлое наблюдение не подставляем."
            )
            return null
        }
        if (
            tunnelSnapshot != null &&
            (tunnelSnapshot.ipv4.isNotBlank() || tunnelSnapshot.ipv6.isNotBlank() || tunnelSnapshot.country.isNotBlank()) &&
            tunnelSnapshot.backend.trim().equals(resolvedBackend.trim(), ignoreCase = true)
        ) {
            logIpFallbackOnce(
                "UI checkCurrentIp: трасса не дошла, показываем прошлый снимок туннеля " +
                    "(${tunnelSnapshot.country.ifBlank { "--" }}) — не свежее измерение."
            )
            return IpSnapshot(
                ipv4 = tunnelSnapshot.ipv4,
                ipv6 = tunnelSnapshot.ipv6,
                country = tunnelSnapshot.country,
                colo = clientData.getLastExitColo(),
                measured = false,
            )
        }

        // Тройка берётся одним куском: адрес и страна обязаны быть из одного ответа
        // трассы (I10), а раздельное чтение смешивало наблюдения разных сессий.
        val lastExit = clientData.getLastExitObservation()
        val lastExitIp = lastExit.ip.trim()
        val lastExitCountry = lastExit.country.trim()
        if (lastExitIp.isBlank() && lastExitCountry.isBlank()) return null
        logIpFallbackOnce(
            "UI checkCurrentIp: трасса не дошла, показываем прошлое наблюдение " +
                "(${lastExitCountry.ifBlank { "--" }}) — не свежее измерение."
        )
        return IpSnapshot(
            ipv4 = if (isIpv4Address(lastExitIp)) lastExitIp else "",
            ipv6 = if (lastExitIp.contains(':')) lastExitIp else "",
            country = lastExitCountry,
            colo = lastExit.colo,
            measured = false,
        )
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) {
            setIntent(intent)
            if (maybeHandleAdbResetIntent(intent)) return
            maybeHandleAutomationIntent(intent)
            maybeHandleWidgetIntent(intent)
        }
    }

    /**
     * Выполняет действие, пришедшее с виджета рабочего стола.
     *
     * Перебор профилей живёт в обработчике кнопки «&gt;» и знает про VLESS,
     * MASQUE, Opera и Proton по-разному. Виджет поэтому не повторяет эту цепочку,
     * а нажимает ту же кнопку — иначе появилась бы вторая, расходящаяся с первой.
     *
     * Намерение съедается сразу: без этого поворот экрана или возврат из настроек
     * повторяли бы переключение профиля на каждом восстановлении активности.
     */
    private fun maybeHandleWidgetIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.getStringExtra(EXTRA_WIDGET_ACTION)?.trim().orEmpty()
        if (action.isEmpty()) return
        intent.removeExtra(EXTRA_WIDGET_ACTION)
        if (action != WIDGET_ACTION_NEXT_PROFILE) return
        if (!::btnNextProfile.isInitialized) return
        // Кнопка скрыта, когда переключать нечего: туннель не поднят или профиль
        // один. Нажимать её в этом случае незачем — подсказка честнее молчания.
        if (btnNextProfile.visibility != View.VISIBLE) {
            Toast.makeText(this, "Переключать профиль сейчас не на что", Toast.LENGTH_SHORT).show()
            return
        }
        btnNextProfile.performClick()
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
            ACTION_ADB_PROBE_CONTROL_HANDSHAKE -> {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, NovaVpnService::class.java)
                        .setAction(NovaVpnService.ACTION_PROBE_CONTROL_HANDSHAKE),
                )
                LogManager.log("ADB debug: запросили у службы контрольный замер рукопожатия.")
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

    /**
     * Снимок внешнего адреса и страны.
     *
     * Входы Cloudflare независимы и идемпотентны, поэтому опрашиваются **разом**, а
     * не по очереди. Последовательный обход платит полным сроком за каждый
     * неответивший вход (G74): через туннель Proton один снимок стоил ~8,4 с — это
     * больше [IP_REFRESH_RUNNING_STALE_MS], так что сторож в `checkCurrentIp` обрывал
     * каждое обновление за доли секунды до его же успеха и заводил такое же, раз в
     * 8,4 с весь сеанс, с записью «зависший IP refresh» — хотя не зависало ничего.
     *
     * Мёртвый вход не запоминается, а **не дожидается** ([pollTraceEntries]). Памятка
     * «этот вход тут молчал» здесь уже была и оказалась хуже болезни: она сужала опрос
     * до входов, ответивших однажды, то есть уничтожала ровно ту избыточность, ради
     * которой входов несколько. На WARP это выглядело так — один неудачный такт
     * приговаривал три входа из четырёх, оставшийся через несколько секунд икал, и
     * снимок проваливался целиком: «не ответил ни один вход» → сброс → полный опрос →
     * снова сужение, цикл каждые 6-10 с, и каждый его виток стоил экрану свежего
     * адреса. Ограничение ожидания даёт тот же выигрыш во времени, но спрашивает
     * всегда всех.
     */
    private fun fetchIpSnapshot(network: Network?): IpSnapshot? {
        // Спрашиваются оба входа [ExitAddress] разом, а ответ берётся строго по
        // порядку: основной сильнее запасного всегда, даже если запасной успел
        // первым. Разом — по той же причине, по которой раньше разом шли `ipv4.`
        // и `ipv6.`: последовательный обход платит полным сроком за молчащий вход
        // (G74), а молчит здесь именно основной — с выхода Cloudflare до него не
        // устанавливается TCP.
        //
        // Поддоменов `ipv4.`/`ipv6.` тут больше нет: `/txt` у них отдаёт 404, и
        // экран не получал адреса ни на одном транспорте. Семейство приходит
        // полем самого ответа.
        val traces = pollTraceEntries(
            ExitAddress.URLS.map { url -> { fetchExitObservation(network, url) } }
        )
        val index = traces.indexOfFirst { it != null }
        if (index < 0) return null
        val observation = traces[index] ?: return null
        if (index > 0) {
            // Подмена источника молчать не должна (I4): бейдж после неё
            // показывает данные не того места, которое назвал владелец.
            LogManager.log(
                "UI checkCurrentIp: основной источник адреса молчит, ответ взят у " +
                    "запасного (${ExitAddress.URLS[index]})."
            )
        }
        if (observation.ip.isBlank()) return null
        val isV4 = ExitAddress.isIpv4(observation.ip)
        return IpSnapshot(
            // Адрес и страна — из одного ответа. Второе семейство остаётся пустым:
            // соврать про него нечем, а подставить чужой ответ — это ровно то
            // смешение источников, на котором бейдж однажды показал адрес одного
            // пути и страну другого (I10).
            ipv4 = if (isV4) observation.ip else "",
            ipv6 = if (isV4) "" else observation.ip,
            country = observation.country,
            // Узла Cloudflare этот источник не знает и знать не может.
            colo = "",
        )
    }

    /** Один запрос к [ExitAddress] и разбор ответа тем разбором, что положен входу. */
    private fun fetchExitObservation(network: Network?, url: String): TraceInfo? {
        val body = readTextFromUrl(network, url) ?: return null
        val parsed = ExitAddress.observe(url, body) ?: return null
        return TraceInfo(ip = parsed.ip, country = parsed.country, colo = "")
    }

    /**
     * Спрашивает все входы разом и возвращает их ответы в том же порядке.
     *
     * Ждать всех нельзя: цена снимка тогда равна самому медленному входу, а мёртвый
     * вход досиживает свой полный таймаут — через туннель Proton это 4 с на пустом
     * месте при живом ответе за 0,3 с.
     *
     * **Входы упорядочены, и порядок сильнее скорости.** Ответ первого входа
     * заканчивает опрос немедленно — остальные ответы всё равно не понадобятся.
     * Ответ любого другого только начинает отсчёт [TRACE_ENTRY_GRACE_MS], в
     * течение которого первый ещё может успеть; иначе быстрый запасной источник
     * обгонял бы живой основной, и экран показывал бы данные не оттуда, откуда
     * обещано.
     *
     * Пока нет **ни одного** ответа, ждём до [TRACE_STAGE_CAP_MS] — на медленной
     * сети живой вход должен успеть, а зависший не должен держать поток пула
     * дольше, чем сторож считает работу здоровой. Опоздавшие досиживают свой
     * таймаут на демонских потоках пула, никого не держа.
     */
    private fun pollTraceEntries(tasks: List<() -> TraceInfo?>): List<TraceInfo?> {
        val completion = ExecutorCompletionService<Pair<Int, TraceInfo?>>(traceExecutor)
        tasks.forEachIndexed { index, task ->
            completion.submit(Callable { index to runCatching(task).getOrNull() })
        }
        val results = arrayOfNulls<TraceInfo>(tasks.size)
        val capDeadlineMs = SystemClock.elapsedRealtime() + TRACE_STAGE_CAP_MS
        var graceDeadlineMs = 0L
        var pending = tasks.size
        while (pending > 0) {
            val deadlineMs = if (graceDeadlineMs == 0L) capDeadlineMs else minOf(graceDeadlineMs, capDeadlineMs)
            val leftMs = deadlineMs - SystemClock.elapsedRealtime()
            if (leftMs <= 0L) break
            val future = completion.poll(leftMs, TimeUnit.MILLISECONDS) ?: break
            pending--
            val (index, trace) = runCatching { future.get() }.getOrNull() ?: continue
            if (trace == null) continue
            results[index] = trace
            // Ответ первого входа заканчивает опрос сразу: входы упорядочены, и
            // ответы остальных всё равно не будут использованы.
            if (index == 0) break
            if (graceDeadlineMs == 0L) {
                graceDeadlineMs = SystemClock.elapsedRealtime() + TRACE_ENTRY_GRACE_MS
            }
        }
        return results.toList()
    }

    private fun fetchTraceInfoViaSocket(
        network: Network?,
        hosts: List<String>,
        timeoutMs: Int = 4000,
    ): TraceInfo? {
        return firstTrace(hosts) { host ->
            readTraceViaSocket(network, host, timeoutMs)?.let(::parseTraceInfo)
        }
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
        // Через петлевой прокси Opera идёт обычный HTTPS-запрос по имени: у
        // [ExitAddress] нет ни литеральных входов, ни открытого HTTP, поэтому
        // быстрый путь «сырым сокетом на порт 80» здесь не применим вовсе.
        val proxy = Proxy(Proxy.Type.HTTP, OperaProxyManager.getLoopbackProxyAddress(this))
        // Порядок входов тот же, что и на прямом пути: основной, потом запасной.
        // Через прокси они идут по очереди — параллелить нечего, ответ нужен один.
        val trace = ExitAddress.URLS.firstNotNullOfOrNull { url ->
            fetchExitObservationViaProxy(proxy, url)
        } ?: return null
        val isV4 = ExitAddress.isIpv4(trace.ip)
        return IpSnapshot(
            ipv4 = if (isV4) trace.ip else "",
            ipv6 = if (isV4) "" else trace.ip,
            country = trace.country,
            colo = "",
        )
    }

    private fun fetchExitObservationViaProxy(proxy: Proxy, url: String): TraceInfo? {
        val body = readTextFromUrlViaProxy(proxy, url) ?: return null
        val parsed = ExitAddress.observe(url, body) ?: return null
        return TraceInfo(ip = parsed.ip, country = parsed.country, colo = "")
    }

    private fun fetchTraceInfoFromUrls(network: Network?, urls: List<String>): TraceInfo? {
        return firstTrace(urls) { url ->
            readTextFromUrl(network, url)?.let(::parseTraceInfo)
        }
    }

    /**
     * Опрашивает запасные адреса одного входа **одновременно** и возвращает первый
     * пришедший ответ, не дожидаясь остальных.
     *
     * Адреса внутри входа — альтернативы друг другу, а не шаги, поэтому очередь
     * здесь стоит суммы их сроков: через туннель Proton молчали оба литеральных
     * адреса подряд, и только это давало 3,6 с из тех 8,4 (G74).
     *
     * Именно **первый пришедший**, а не первый по списку. Дожидаться всех ради
     * порядка нельзя: тогда живой ответ за 200 мс ждал бы соседа, который на этой
     * сети молчит весь свой таймаут, — то есть возвращалась бы та самая плата за
     * мёртвый адрес, ради которой всё и переписывалось. Разнобоя в показаниях это
     * не даёт: `/cdn-cgi/trace` у любого входа Cloudflare сообщает **наш** адрес и
     * страну, так что все адреса семейства отвечают одним и тем же.
     *
     * Гонку ведёт [ProtonRace.firstSuccess] — примитив тот же, и второй его копии
     * здесь заводить нечего. `cancelAll` пустой: у `HttpURLConnection` и `Socket`
     * ручки для обрыва нет, проигравшие досиживают свой таймаут на демонских
     * потоках. Это стоит потоков, но не времени вызывающего.
     */
    private fun firstTrace(entries: List<String>, read: (String) -> TraceInfo?): TraceInfo? {
        val attempts: List<() -> TraceInfo?> = entries.map { entry -> { read(entry) } }
        return ProtonRace.firstSuccess(attempts, cancelAll = {})
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

                if (isTunnelConnected() && isTorBackend(resolvedBackend)) {
                    // Ровно та же причина, что и у VLESS ниже: цепочку Tor экран не
                    // видит, её проверяет служба и публикует замер под своей меткой.
                    latency = clientData.getTransportLatency()
                        ?.takeIf { it.transport.equals(NovaVpnService.TRANSPORT_TOR, ignoreCase = true) }
                        ?.latencyMs
                        ?: -1
                } else if (isTunnelConnected() && isVlessBackend(resolvedBackend)) {
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
        // Пока выпускаются профили Proton, ни адреса, ни страна показу не подлежат:
        // сеанс, с которого они сняты, — не тот транспорт, который выбрал
        // пользователь. Выходим до восстановления значений из снимка, иначе прочерки
        // тут же затираются последним известным выходом (и бейдж пишет «WARP: RU»
        // ровно тогда, когда идёт регистрация Proton).
        if (clientData.isProtonPreparationRequested()) {
            currentIpv4 = "..."
            currentIpv6 = "..."
            currentCountry = "--"
            tunnelIpResolved = false
            tvIpAddress.text = "—" + System.lineSeparator() + "—"
            tvCountryBadge.visibility = android.view.View.GONE
            return
        }
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
        if (NovaVpnService.isPublishedTransport(transport, NovaVpnService.TRANSPORT_MASQUE)) {
            return "${NovaVpnService.TRANSPORT_MASQUE}: $effectiveCountry"
        }
        // Tor подписывается своим именем по той же причине, что и MASQUE: по
        // бэкенду его не отличить, а без этой ветки бейдж скатывался в общий
        // «WARP: RU» — на живом Tor это была прямая неправда.
        if (NovaVpnService.isPublishedTransport(transport, NovaVpnService.TRANSPORT_TOR)) {
            return "${NovaVpnService.TRANSPORT_TOR}: $effectiveCountry"
        }
        // Импортированный профиль AmneziaWG подписывается своим именем: бэкенд у него
        // тот же `WARP`, и раньше бейдж обещал Cloudflare там, где туннель шёл на
        // сервер пользователя.
        // Сгенерированный Nova профиль Proton подписывается отдельно от чужого
        // импорта: обе метки означают AmneziaWG, но происхождение узла у них разное.
        if (NovaVpnService.isPublishedTransport(transport, NovaVpnService.TRANSPORT_AWG_PROTON)) {
            return "${NovaVpnService.TRANSPORT_AWG_PROTON}: $effectiveCountry"
        }
        if (NovaVpnService.isPublishedTransport(transport, NovaVpnService.TRANSPORT_AWG)) {
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

    /**
     * Пишет строку про подстановку прошлого наблюдения только на смене причины.
     */
    private fun logIpFallbackOnce(message: String) {
        if (message == lastIpFallbackLine) return
        lastIpFallbackLine = message
        LogManager.log(message)
    }

    private fun displayOrDots(ip: String): String {
        return ip.trim().ifBlank { "..." }
    }

    /**
     * Адрес на экране: сеть видна, хвост скрыт.
     *
     * Раньше было наоборот — `***.***.230.61`, — и это ровно та половина, которую
     * скрывать и надо: сеть у всех, кто сидит на одном выходе, общая, а хвост
     * принадлежит одному соединению. Экран попадает в отчёт об отказе не реже
     * журнала: снимок экрана прислать проще всего. Направление маски теперь то же,
     * что и в [DiagnosticLogSanitizer], и «сменился ли выход» по нему по-прежнему
     * видно — вместе с провайдером. Кому нужен адрес целиком, тот жмёт на глаз:
     * `isIpVisible` никуда не делся.
     */
    private fun maskIpForDisplay(ip: String): String {
        val value = ip.trim()
        if (value.isBlank() || value == "...") return value

        val ipv4 = value.split(".")
        if (ipv4.size == 4) {
            return "${ipv4[0]}.${ipv4[1]}.${ipv4[2]}.***"
        }

        if (value.contains(":")) {
            val parts = value.split(":").toMutableList()
            val nonEmptyIndexes = parts.indices.filter { parts[it].isNotEmpty() }
            if (nonEmptyIndexes.isNotEmpty()) {
                // Прячется хвост: интерфейсная часть, по которой устройство узнают
                // между сеансами, а не префикс сети.
                val hideCount = (nonEmptyIndexes.size / 2).coerceAtLeast(1)
                for (index in nonEmptyIndexes.takeLast(hideCount)) {
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

    /**
     * Идёт ли регистрация, ради которой нажимать «подключить» бессмысленно.
     *
     * Два признака, оба в файлах (I2): выпуск профилей Proton и ступень регистрации
     * MASQUE. Оба означают одно — приложение уже занято подготовкой выбранного
     * протокола, и повторное «подключить» посреди неё либо ничего не делает, либо
     * роняет тот самый туннель, через который выдаётся ключ.
     *
     * Фоновой подготовки это не касается: она ни одного из признаков не ставит и
     * пользователю не видна вовсе.
     */
    private fun isRegistrationInProgress(): Boolean =
        clientData.isProtonPreparationRequested() || clientData.isDeviceRegistrationInProgress()

    private fun applyPrimaryActionInterlock() {
        if (!::btnConnect.isInitialized) return
        btnConnect.isEnabled = SystemClock.elapsedRealtime() >= primaryActionLockedUntilMs
        // Пока идёт регистрация, единственное доступное действие — прервать её.
        // Подпись поэтому «ОТКЛЮЧИТЬ» при любом состоянии туннеля: «подключить»
        // нажать нечем, а остановиться — есть чем. Без этого регистрация без
        // поднятого туннеля показывала «ПОДКЛЮЧИТЬ», нажатие заводило второй
        // connect-flow поверх идущей подготовки, а прервать её было нечем совсем.
        if (!primaryActionPreviewActive && isRegistrationInProgress()) {
            btnConnect.text = "ОТКЛЮЧИТЬ"
        }
    }

    private fun shouldTreatPrimaryActionAsStop(): Boolean {
        return isWarpDiscoveryActive() ||
            isStartFlowActive ||
            isRegistrationInProgress() ||
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
        // Отмена касания не должна возвращать «ПОДКЛЮЧИТЬ» посреди регистрации:
        // подпись там задаёт блокировка, а не состояние туннеля.
        applyPrimaryActionInterlock()
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

    /**
     * Замер считается **по удавшейся пробе**, а не от начала перебора.
     *
     * Дефект, который это чинит: отсчёт вёлся от `startedAt` первой пробы, а
     * возвращался после той, которая наконец ответила. Каждая неудачная попытка —
     * это полный её срок (до 3 с), и он целиком приплюсовывался к результату:
     * на живом канале с честными 280 мс экран показывал 800+ мс. Число при этом
     * выглядело правдоподобно, поэтому читалось не как ошибка замера, а как
     * «приложение тормозит канал».
     */
    private fun measureLatencyViaOperaProxy(timeoutMs: Int): Int {
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
            val startedAt = System.currentTimeMillis()
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
                val startedAt = System.currentTimeMillis()
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
            preference == "tor" -> "ПОДКЛЮЧЕНИЕ... TOR"
            preference == "vless" -> "ПОДКЛЮЧЕНИЕ... VLESS"
            preference == "proton" -> "ПОДКЛЮЧЕНИЕ... AWG Proton"
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
        cancelRegistrationOnUserStop()
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

    /**
     * Отключение останавливает и регистрацию — иначе останавливать её нечем.
     *
     * Сам прогон в сети не обрывается: он уже в середине запроса, и рвать его посреди
     * регистрации ключа незачем. Снимается **применение** итога — ровно как при выборе
     * другого транспорта: регион и подключение остаются за последним явным действием
     * пользователя, а этим действием только что было «отключить».
     */
    private fun cancelRegistrationOnUserStop() {
        if (!clientData.isProtonPreparationRequested()) return
        LogManager.log("Proton: пользователь нажал отключение — подготовку прекращаем.")
        ProtonProfileManager.cancelPreparation()
        clientData.setProtonPreparationRequested(false)
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
        // Селектор перерисовывается на каждом кадре состояния, а не один раз при
        // старте: регион теперь меняется, не уходя с экрана, и запрет на время
        // регистрации устройства обязан появляться и сниматься сам.
        refreshMainRegionSelector()
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

        // Слой поверх любого состояния: пока идёт выпуск профилей Proton, ни зелёное
        // «АКТИВНО», ни красное «НЕ ПОДКЛЮЧЕНО» правды не говорят — работа идёт, но
        // не та, что показывает бейдж. Накладывается последним, чтобы не спорить с
        // остальной отрисовкой: кнопки и фон остаются от реального состояния.
        applyProtonPreparationOverlay()

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
        setNextProfileVisible(false)
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
        setNextProfileVisible(false)
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
        // Пока выпускаются профили Proton, зелёного «АКТИВНО» здесь быть не может:
        // сеанс поднят не на том транспорте, который выбрал пользователь. Показываем
        // жёлтую «РЕГИСТРАЦИЯ PROTON», а адреса и страну — прочерками, чтобы бейдж
        // чужого выхода не выдавался за выбранный.
        val protonPreparing = clientData.isProtonPreparationRequested()
        if (!protonPreparing && !applyDeviceRegistrationStatusIfActive()) {
            applyStatusStyle(
                text = "АКТИВНО : РАБОТАЕТ",
                textColor = Color.parseColor("#13A10E"),
                textGlowColor = Color.parseColor("#13A10E"),
            )
        }
        updateAttemptProgressDisplay()
        btnConnect.text = "ОТКЛЮЧИТЬ"
        applyNextProfileButtonVisibility()
        requestVpnNetwork()
        updateIpDisplay()
        if (!protonPreparing) {
            checkCurrentIp()
            measureLatency()
        }
        applyPrimaryActionInterlock()
    }

    /**
     * Регионы, где кнопка «следующий профиль» показу не подлежит.
     *
     * Кнопка ведёт по **цепочке транспортов**, а не по списку узлов выбранного: с
     * активной Opera EU следующий шаг — Opera US, с MASQUE — Opera EU. Когда регион
     * выбран явно, это прямое нарушение I1, и наблюдалось оно ровно так: на выбранном
     * EU нажатие уводило на US, тот не поднялся, экран почти полторы минуты показывал
     * «НЕ ПОДКЛЮЧЕНО», после чего служба сама вернулась на EU — снаружи «нажал
     * переключение, а оно отключило и потом подключилось само».
     *
     * У WARP, Proton и VLESS кнопка перебирает **свой** список узлов и остаётся.
     */
    private val NEXT_PROFILE_HIDDEN_REGIONS = setOf("eu", "us", "masque", "tor")

    /**
     * Подпись «след. профиль» над кнопкой «&gt;».
     *
     * Видимость обязана меняться там же, где у самой кнопки: висящая над пустотой
     * подпись — это ровно та ложь, которую подпись и должна была убрать.
     */
    private var nextProfileCaption: TextView? = null

    private fun setNextProfileVisible(visible: Boolean) {
        if (::btnNextProfile.isInitialized) {
            btnNextProfile.visibility = if (visible) View.VISIBLE else View.GONE
        }
        nextProfileCaption?.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun applyNextProfileButtonVisibility() {
        if (!::btnNextProfile.isInitialized) return
        val region = clientData.getExitRegionPreference().trim().lowercase(Locale.ROOT)
        setNextProfileVisible(region !in NEXT_PROFILE_HIDDEN_REGIONS)
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
        setNextProfileVisible(false)
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
        // Пока идёт выпуск профилей Proton, под статусом показываем его шаги.
        //
        // Раньше это было видно только на экране настроек: пользователь выбирал
        // PROTON, возвращался на главный и видел «...» без единого слова о том, что
        // происходит, — а выпуск занимает до минуты (сессия, список серверов,
        // регистрация ключа, замер 50-60 кандидатов). Место под статусом уже
        // занято счётчиком перебора, и это тот же самый вопрос «что сейчас идёт».
        //
        // Признак подготовки проверяется наравне с самим прогоном: после отмены
        // (отключение или выбор другого транспорта) прогон в сети ещё доигрывает свой
        // шаг, но его итог уже никуда не применяется. Строка «Proton: проверка 45/51»
        // над красным «НЕ ПОДКЛЮЧЕНО» читалась бы как «отключение не сработало».
        if (ProtonProfileManager.isRunning() && clientData.isProtonPreparationRequested()) {
            val step = protonProgressText.ifBlank { ProtonProfileManager.currentStatus() }
            if (step.isNotBlank()) {
                tvAttemptProgress.text = step
                tvAttemptProgress.visibility = View.VISIBLE
                refreshTransportNotice()
                refreshRestrictedMobileIndicator()
                return
            }
        }
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
        val text = if (relevant) notice else ""
        // Идемпотентность здесь не украшение, а условие того, что эту функцию
        // можно звать с тика.
        //
        // `TextView` при `wrap_content` уходит в `requestLayout` даже на том же
        // самом тексте, а тик идёт дважды в секунду поверх фона, колец и графика
        // задержки. Со сравнением повторный вызов не стоит ничего, и подпись
        // можно перечитывать постоянно — без этого она обновлялась только вместе
        // со сменой состояния службы.
        if (text == displayedTransportNotice) return
        displayedTransportNotice = text
        if (text.isNotEmpty()) {
            tvTransportNotice.text = text
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
        // Подпись под статусом меняется и без смены состояния.
        //
        // Выпуск профилей идёт при уже поднятом туннеле и обновляет её трижды за
        // полторы минуты, оставаясь в `CONNECTED`. Условие выше сюда не пускало,
        // и на экране навсегда застывало «Выпуск профилей 1/3», хотя журнал
        // службы честно показывал шаги 2/3 и 3/3. Вызов дешёвый: сама функция
        // сравнивает текст и на совпадении не делает ничего.
        refreshTransportNotice()
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
    /**
     * Пока идут регистрация и выпуск профилей Proton, экран говорит именно это.
     *
     * Без этого пользователь видел зелёное «АКТИВНО : РАБОТАЕТ» и бейдж «WARP: RU»:
     * выпуск шёл поверх обычного сеанса, и снаружи это читалось как «выбрал Proton,
     * а подключился WARP». Состояние промежуточное, значит и цвет промежуточный —
     * тот же жёлтый, что у регистрации устройства.
     */
    /**
     * Доводит прерванный выпуск профилей Proton до конца.
     *
     * Признак лежит в файле, поэтому переживает и уход из приложения, и смерть
     * процесса: вернувшись, человек продолжает с того места, где остановился, а не
     * начинает всё заново. Сам [ProtonProfileManager.ensureProfiles] уже устроен как
     * продолжение — живая личность и сертификат переиспользуются, заново делается
     * только недостающее.
     */
    private fun resumeProtonPreparationIfPending() {
        if (!clientData.isProtonPreparationRequested()) return
        if (ProtonProfileManager.isRunning()) return
        LogManager.log("Proton: выпуск профилей не был закончен — продолжаем.")
        ProtonProfileManager.markPreparationRequested()
        ProtonProfileManager.ensureProfiles(this) { outcome ->
            clientData.setProtonPreparationRequested(false)
            ProtonProfileManager.cancelPreparation()
            if (outcome.ready) {
                clientData.setExitRegionPreference("proton")
                LogManager.log("Proton: выпуск доведён до конца, профилей ${outcome.profiles.size}.")
            } else {
                LogManager.log("Proton: продолжить выпуск не удалось — ${outcome.message}")
            }
            runOnUiThread { updateUiByState(null) }
        }
    }

    /** Шаг бегущего многоточия у «РЕГИСТРАЦИЯ PROTON». */
    private val PROTON_ELLIPSIS_PERIOD_MS = 450L

    private var protonEllipsisStep = 0

    private val protonEllipsisRunnable = object : Runnable {
        override fun run() {
            if (!isActivityResumed || !clientData.isProtonPreparationRequested()) return
            protonEllipsisStep += 1
            applyProtonPreparationOverlay()
            statusHandler.postDelayed(this, PROTON_ELLIPSIS_PERIOD_MS)
        }
    }

    /**
     * Накладывает состояние «идёт регистрация Proton» поверх любого другого.
     *
     * Отдельным слоем, а не веткой в каждом рендере: выпуск идёт и когда туннеля
     * нет вовсе, и поверх живого сеанса. Пока проверка стояла только в
     * `renderConnectedState`, без туннеля экран показывал красное «НЕ ПОДКЛЮЧЕНО» —
     * то есть ровно в тот момент, когда работа и идёт, пользователь видел, что всё
     * стоит. Многоточие бежит, потому что неподвижный текст на минутной операции
     * неотличим от зависшего.
     *
     * @return true, если слой применён
     */
    private fun applyProtonPreparationOverlay(): Boolean {
        if (!clientData.isProtonPreparationRequested()) {
            statusHandler.removeCallbacks(protonEllipsisRunnable)
            return false
        }
        val dots = ".".repeat(1 + (protonEllipsisStep % 3))
        applyStatusStyle(
            text = "РЕГИСТРАЦИЯ PROTON$dots",
            textColor = Color.parseColor("#C99514"),
            textGlowColor = Color.parseColor("#F1C64A"),
        )
        // Бейдж и адреса — прочерками: показывать чужой выход как свой нельзя (I10).
        currentIpv4 = "..."
        currentIpv6 = "..."
        currentCountry = "--"
        updateIpDisplay()
        statusHandler.removeCallbacks(protonEllipsisRunnable)
        statusHandler.postDelayed(protonEllipsisRunnable, PROTON_ELLIPSIS_PERIOD_MS)
        return true
    }

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
                if (isVpnNetworkNotForeign(network)) score += 1_000
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
            isVpnNetworkNotForeign(active)
        ) {
            return active
        }

        // Обе прежние ветки — строгая и «по локальным признакам» — теперь внутри
        // isVpnNetworkNotForeign, а чужой VPN по локальным признакам больше не проходит.
        val bestVpn = bestVpnNetwork()
        return if (bestVpn != null && isVpnNetworkNotForeign(bestVpn)) bestVpn else null
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

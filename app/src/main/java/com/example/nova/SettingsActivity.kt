package com.example.nova



import android.app.ActivityManager

import android.app.StatusBarManager

import android.content.BroadcastReceiver

import android.content.ComponentName

import android.content.Context

import android.content.res.ColorStateList

import android.graphics.Color

import android.content.Intent

import android.content.IntentFilter

import android.graphics.drawable.Icon

import android.net.ConnectivityManager

import android.net.NetworkCapabilities

import android.net.Uri

import android.os.Build

import android.os.Bundle

import android.os.Handler

import android.os.Looper

import android.os.PowerManager

import android.provider.Settings

import android.text.Editable

import android.text.TextWatcher

import android.view.MotionEvent

import android.view.View

import android.widget.*

import androidx.core.content.FileProvider

import java.io.File

import java.text.SimpleDateFormat

import java.util.Date

import java.util.Locale

import java.util.TimeZone

import androidx.appcompat.app.AppCompatActivity

import androidx.appcompat.widget.SwitchCompat

import androidx.core.content.ContextCompat

import androidx.lifecycle.lifecycleScope

import androidx.core.widget.NestedScrollView

import androidx.recyclerview.widget.LinearLayoutManager

import androidx.recyclerview.widget.RecyclerView

import kotlinx.coroutines.CoroutineScope

import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.Job

import kotlinx.coroutines.SupervisorJob

import kotlinx.coroutines.cancel

import kotlinx.coroutines.launch

import kotlinx.coroutines.withContext



class SettingsActivity : AppCompatActivity() {



    private lateinit var clientData: ClientData

    private lateinit var adapter: AppAdapter

    private lateinit var scrollContent: NestedScrollView

    private lateinit var etSearch: EditText

    private lateinit var rvApps: RecyclerView

    private lateinit var rbMaskAuto: RadioButton
    private lateinit var rbMaskCustom: RadioButton
    private lateinit var swTrafficMask: Switch

    private lateinit var rgTrafficMaskMode: RadioGroup

    private lateinit var etTrafficMaskHost: EditText

    private lateinit var tvWarpLicenseNote: TextView

    private lateinit var tvTrafficMaskActive: TextView

    private lateinit var tvLogsNote: TextView

    private lateinit var swLogs: Switch

    private lateinit var layoutLogsActions: LinearLayout

    private lateinit var btnClearLogs: TextView

    private lateinit var btnExportLogs: TextView

    private lateinit var rowManualUpdateCheck: TextView

    private lateinit var pbManualUpdate: ProgressBar

    private lateinit var tvManualUpdateStatus: TextView

    private var allApps: List<AppItem> = emptyList()

    private var appLoadJob: Job? = null

    private var suppressBackgroundSwitchCallback = false

    private var suppressAutostartSwitchCallback = false

    private var suppressQuickTileSwitchCallback = false

    private val uiRefreshHandler = Handler(Looper.getMainLooper())

    private val reapplyHandler = Handler(Looper.getMainLooper())

    private var lastTelegramOpenAtMs: Long = 0L

    private var latestMaskHostFromBroadcast: String = ""

    private var latestMaskPoolFromBroadcast: String = ""

    private var latestMaskStateFromBroadcast: String = NovaVpnService.STATE_STOPPED

    private var latestMaskBroadcastAtMs: Long = 0L

    private val trafficMaskRefreshRunnable = object : Runnable {

        override fun run() {

            if (!isFinishing && !isDestroyed) {

                updateTrafficMaskUi(swTrafficMask, rgTrafficMaskMode, etTrafficMaskHost, tvTrafficMaskActive)

                val shouldRefreshFast =

                    clientData.getServiceState() == NovaVpnService.STATE_CONNECTING ||

                        clientData.isTransientConnectingPending() ||

                        clientData.isSoftReapplyPending() ||

                        clientData.getWarpTrafficMaskActiveHost().isNotBlank()

                val nextDelayMs = if (shouldRefreshFast) 350L else 1200L

                uiRefreshHandler.postDelayed(this, nextDelayMs)

            }

        }

    }

    private val manualUpdateRefreshRunnable = object : Runnable {

        override fun run() {

            if (!isFinishing && !isDestroyed) {

                updateManualUpdateUi()

                uiRefreshHandler.postDelayed(this, 900L)

            }

        }

    }

    /**
     * Опрос хода выпуска своих профилей WARP.
     *
     * Прогон живёт в процессе `:vpn` и сообщает о себе файлом (I2) — слушателя, как
     * у Proton, здесь нет и быть не может, поэтому остаётся опрос. Свой Handler, а
     * не общий `uiRefreshHandler`: этот опрос заводится и гасится по собственному
     * поводу (начался и кончился прогон), и делить с ним очередь чужих обновлений
     * значило бы снимать их вместе со своими.
     */
    private val warpGenerateHandler = Handler(Looper.getMainLooper())

    /** Опрос заведён и не остановлен. Экран между `onResume` и `onPause`. */
    private var warpGeneratePolling = false

    /**
     * Момент нажатия кнопки выпуска.
     *
     * Между `startForegroundService` и первой записью `running` помещается запуск
     * процесса `:vpn`, а на холодном старте это заметно дольше одного шага опроса.
     * Без этой отсрочки первый же опрос читал бы состояние **прошлого** прогона,
     * гасил опрос и отпирал кнопку — нажатие выглядело бы как не сделавшее ничего,
     * а под кнопкой висел бы итог позапрошлого выпуска.
     */
    private var warpGenerateRequestedAtMs = 0L

    /** Начальная расстановка переключателей WARP не должна выглядеть как нажатие. */
    private var suppressWarpGenerateSwitchCallback = false

    private val warpGenerateRefreshRunnable = Runnable {

        if (!isFinishing && !isDestroyed) refreshWarpGenerateState()

    }

    

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)



    // State Triggers

    private var initialSplitMode: Int = 0

    private var initialSplitApps: Set<String> = emptySet()

    private var initialAutoReconnect: Boolean = true

    private var initialAutoAppUpdate: Boolean = true

    private var initialQuickTileAdded: Boolean = false

    private var initialExitRegionPreference: String = "auto"

    /**
     * Слушатель этапов генерации Proton-профилей.
     *
     * Держится полем и снимается в onDestroy: прогон переживает экран, и оставленный
     * слушатель удерживал бы уничтоженный TextView до конца прогона.
     */
    private var protonStatusListener: ProtonProfileManager.StatusListener? = null


    /**
     * Идёт подготовка Proton, начатая с этого экрана.
     *
     * Нужен потому, что предпочтение «proton» записывается **только по успеху**, а
     * до тех пор `configureRegionSelector` перечитывает старое и возвращает
     * переключатель на прежний регион. Служба шлёт состояние подключения сразу же,
     * экран на него перерисовывается — и кнопка отскакивала назад через секунду
     * после нажатия, унося с собой все строки хода выпуска.
     */
    private var protonPreparationActive = false

    /**
     * Сообщение о ходе или неудаче Proton, которое обязано пережить перерисовку.
     *
     * `configureRegionSelector` в конце безусловно перекрашивает строку статуса, и
     * без этого поля любая причина отказа («сначала подключитесь», «туннель не
     * поднялся», текст ошибки прогона) стиралась в той же посылке главного потока —
     * пользователь видел только откатившуюся кнопку и ничего больше (I4).
     */
    private var protonPendingMessage: String? = null

    /** Опрос туннеля идёт на главном лупере, а не на вида: экран может закрыться. */
    private val protonWaitHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private var initialImportedProtocolPreference: String = "auto"

    private var initialTrafficMaskEnabled: Boolean = false

    private var initialTrafficMaskMode: String = "auto"

    private var initialTrafficMaskHost: String = ""

    private var splitReapplyHandledInPlace = false

    private var regionReapplyHandledInPlace = false

    private var protocolReapplyHandledInPlace = false

    private val splitReapplyRunnable = Runnable {

        splitReapplyHandledInPlace = false

        maybeApplySplitChangesImmediately()

    }

    private val packageChangesReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            if (intent?.data?.scheme != "package") return

            handleInstalledAppsChanged()

        }

    }

    private val vpnStateReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            if (intent?.action != NovaVpnService.ACTION_VPN_STATE) return

            if (intent.getBooleanExtra(NovaVpnService.EXTRA_TILE_REFRESH_ONLY, false)) return

            latestMaskStateFromBroadcast =

                intent.getStringExtra(NovaVpnService.EXTRA_STATE).orEmpty()

                    .ifBlank { NovaVpnService.STATE_STOPPED }

            latestMaskHostFromBroadcast =

                intent.getStringExtra(NovaVpnService.EXTRA_MASK_HOST).orEmpty()

            latestMaskPoolFromBroadcast =

                intent.getStringExtra(NovaVpnService.EXTRA_MASK_POOL).orEmpty()

            latestMaskBroadcastAtMs = System.currentTimeMillis()

            updateTrafficMaskUi(swTrafficMask, rgTrafficMaskMode, etTrafficMaskHost, tvTrafficMaskActive)

            // Регистрация устройства запирает выбор протокола, а заканчивается
            // она сама. Без этой строки запрет снимался бы только при уходе с
            // экрана и возврате: пользователь, дождавшийся конца регистрации
            // прямо здесь, видел бы серые кнопки и считал их сломанными.
            refreshConnectionSelector()

        }

    }

    private var vpnStateReceiverRegistered = false



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)

        LogManager.setAppContext(this)
        NovaRelay.attach(this)

        setContentView(R.layout.activity_settings)

        NovaFontHelper.apply(findViewById(android.R.id.content))

        

        clientData = ClientData(this)

        VendorBackgroundSettingsHelper.primeCache(applicationContext)

        ClientData.needsRestart = false // Reset on entry

        

        // Capture Initial State

        initialSplitMode = clientData.getSplitMode()

        initialSplitApps = clientData.getSplitApps()

        initialAutoReconnect = clientData.getAutoReconnect()

        initialAutoAppUpdate = clientData.getAutoAppUpdate()

        initialQuickTileAdded = clientData.getQuickTileAdded()

        initialExitRegionPreference = clientData.getExitRegionPreference()

        initialImportedProtocolPreference = clientData.getImportedProtocolPreference()

        initialTrafficMaskEnabled = clientData.getTrafficMaskEnabled()

        initialTrafficMaskMode = clientData.getTrafficMaskMode()

        initialTrafficMaskHost = clientData.getTrafficMaskHost()

        

        val swBackground = findViewById<Switch>(R.id.sw_background)

        val rowAutostart = findViewById<LinearLayout>(R.id.row_autostart)

        val swAutostart = findViewById<Switch>(R.id.sw_autostart)

        val rowBackgroundVendor = findViewById<TextView>(R.id.row_background_vendor)

        scrollContent = findViewById(R.id.scroll_content)

        val rgMode = findViewById<RadioGroup>(R.id.rg_mode)

        val rowQSTile = findViewById<LinearLayout>(R.id.row_qs_tile)

        val swQSTile = findViewById<Switch>(R.id.sw_qs_tile)

        val tvQSTileNote = findViewById<TextView>(R.id.tv_qs_tile_note)

        val tvConnectionSelectorTitle = findViewById<TextView>(R.id.tv_connection_selector_title)

        val rgExitRegion = findViewById<RadioGroup>(R.id.rg_exit_region)

        val rbExitAuto = findViewById<RadioButton>(R.id.rb_exit_auto)

        val rbExitRu = findViewById<RadioButton>(R.id.rb_exit_ru)

        val rbExitOpera = findViewById<RadioButton>(R.id.rb_exit_opera)

        val rbExitTor = findViewById<RadioButton>(R.id.rb_exit_tor)

        val rbExitMasque = findViewById<RadioButton>(R.id.rb_exit_masque)

        val rbExitProton = findViewById<RadioButton>(R.id.rb_exit_proton)

        // Порядок — из [ConnectionSelectorPolicy]. Здесь он был выписан вручную и
        // не совпадал с порядком в разметке (`masque` третий в коде, пятый в XML),
        // а таких копий было четыре.
        val protocolButtons = ConnectionSelectorPolicy.BUTTON_IDS.mapNotNull { findViewById<RadioButton>(it) }

        val rowShareRelease = findViewById<TextView>(R.id.row_share_release)

        val swAutoUpdate = findViewById<Switch>(R.id.sw_auto_update)

        rowManualUpdateCheck = findViewById(R.id.row_manual_update_check)

        pbManualUpdate = findViewById(R.id.pb_manual_update)

        tvManualUpdateStatus = findViewById(R.id.tv_manual_update_status)

        // В сборке для F-Droid обновления выдаёт каталог, а собственный
        // загрузчик выключен целиком (AppUpdateManager.isUpdaterEnabled).
        // Оставлять при этом переключатель и кнопку проверки нельзя: они
        // выглядели бы рабочими и молча ничего не делали.
        if (!AppUpdateManager.isUpdaterEnabled) {
            findViewById<View>(R.id.row_auto_update)?.visibility = View.GONE
            findViewById<View>(R.id.row_manual_update_block)?.visibility = View.GONE
        }

        rbMaskAuto = RadioButton(this).apply { id = View.generateViewId() }
        rbMaskCustom = RadioButton(this).apply { id = View.generateViewId() }
        swTrafficMask = Switch(this)
        rgTrafficMaskMode = RadioGroup(this)
        etTrafficMaskHost = EditText(this)
        tvTrafficMaskActive = TextView(this)


        val rowWarpConfigs = findViewById<LinearLayout>(R.id.row_warp_configs)

        val tvWarpConfigsNote = findViewById<TextView>(R.id.tv_warp_configs_note)

        val rowMainBackground = findViewById<LinearLayout>(R.id.row_main_background)

        val rowDnsSettings = findViewById<LinearLayout>(R.id.row_dns_settings)

        val rowNotificationSettings = findViewById<LinearLayout>(R.id.row_notification_settings)

        val rowDomainBypass = findViewById<LinearLayout>(R.id.row_domain_bypass)

        val rowDirectFlow = findViewById<LinearLayout>(R.id.row_direct_flow)

        val rowAddWidget = findViewById<LinearLayout>(R.id.row_add_widget)

        val rowSniMask = findViewById<LinearLayout>(R.id.row_sni_mask)

        val rowLocalProxy = findViewById<LinearLayout>(R.id.row_local_proxy)

        val tvLocalProxyNote = findViewById<TextView>(R.id.tv_local_proxy_note)

        tvLogsNote = findViewById(R.id.tv_logs_note)

        swLogs = findViewById(R.id.sw_logs)

        layoutLogsActions = findViewById(R.id.layout_logs_actions)

        btnClearLogs = findViewById(R.id.btn_clear_logs)

        btnExportLogs = findViewById(R.id.btn_export_logs)



        val logConfig = clientData.getDiagnosticLogSettingsConfig()

        swLogs.isChecked = logConfig.enabled

        setupSwitchColor(swLogs, logConfig.enabled)

        layoutLogsActions.visibility = if (logConfig.enabled) View.VISIBLE else View.GONE

        tvLogsNote.text = if (logConfig.enabled) "Включено" else "Выключено"

        etSearch = findViewById(R.id.et_search)

        rvApps = findViewById(R.id.rv_apps)

        val tvSplitSectionTitle = findViewById<TextView>(R.id.tv_split_section_title)

        val tvSplitDnsHint = findViewById<TextView>(R.id.tv_split_dns_hint)

        val tvLastExit = findViewById<TextView>(R.id.tv_exit_last)

        

        // Footer with Telegram link

        val tvFooter = findViewById<TextView>(R.id.tv_footer)

        setupFooterLink(tvFooter)

        rowShareRelease.setOnClickListener {

            openDownloadPage()

        }

        

        // 1. Background Work Permission

        checkBatteryOptimization(swBackground)

        updateAutoStartRow(rowAutostart, swAutostart)

        updateVendorBackgroundRow(rowBackgroundVendor)

        swBackground.setOnCheckedChangeListener { _, isChecked ->

            if (suppressBackgroundSwitchCallback) return@setOnCheckedChangeListener

            if (isChecked) {

                val pm = getSystemService(PowerManager::class.java)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && pm.isIgnoringBatteryOptimizations(packageName)) {

                    // Вендорные экраны есть только на китайских прошивках; на
                    // остальных оптимизация батареи уже отключена — делать нечего.

                    if (VendorBackgroundSettingsHelper.canOpen(this) &&

                        !VendorBackgroundSettingsHelper.open(this)

                    ) {

                        Toast.makeText(

                            this,

                            "Не удалось открыть доп. настройки фона на этом устройстве.",

                            Toast.LENGTH_SHORT

                        ).show()

                    }

                } else {

                    requestBatteryOptimization()

                }

            } else {

                requestDisableBatteryOptimization()

            }

            updateVendorBackgroundRow(rowBackgroundVendor)

        }

        rowAutostart.setOnClickListener {

            if (rowAutostart.visibility == View.VISIBLE) {

                swAutostart.toggle()

            }

        }

        swAutostart.setOnCheckedChangeListener { _, isChecked ->

            if (suppressAutostartSwitchCallback) return@setOnCheckedChangeListener

            if (isChecked && !VendorBackgroundSettingsHelper.openAutoStart(this)) {

                // Экран не открылся — не запоминаем «включено», иначе тумблер
                // навсегда останется зелёным при неработающей настройке.

                Toast.makeText(

                    this,

                    "Не удалось открыть экран автозапуска на этом устройстве.",

                    Toast.LENGTH_SHORT

                ).show()

                suppressAutostartSwitchCallback = true

                swAutostart.isChecked = false

                suppressAutostartSwitchCallback = false

                clientData.setAutostartEnabledHint(false)

                setupSwitchColor(swAutostart, false)

                return@setOnCheckedChangeListener

            }

            clientData.setAutostartEnabledHint(isChecked)

            setupSwitchColor(swAutostart, isChecked)

        }

        

        // 2. Auto Reconnect

        val swAutoReconnect = findViewById<Switch>(R.id.sw_autoreconnect)

        swAutoReconnect.isChecked = initialAutoReconnect

        setupSwitchColor(swAutoReconnect, initialAutoReconnect)

        TvFocusHelper.install(

            this,

            rowShareRelease,

            swBackground,

            rowAutostart,

            swAutostart,

            rowBackgroundVendor,

            swAutoReconnect,

            swAutoUpdate,

            rowManualUpdateCheck,

            rowQSTile,

            swQSTile,

            rbExitAuto,

            rbExitRu,

            rbExitOpera,

            rbExitTor,

            swTrafficMask,

            rbMaskAuto,
            rbMaskCustom,

            rowWarpConfigs,

            swLogs,

            btnClearLogs,

            btnExportLogs,

            rowLocalProxy,

            findViewById(R.id.rb_all),

            findViewById(R.id.rb_allow),

            findViewById(R.id.rb_disallow),

        )

        

        swAutoReconnect.setOnCheckedChangeListener { _, isChecked ->

            clientData.setAutoReconnect(isChecked)

            setupSwitchColor(swAutoReconnect, isChecked)

        }



        setupAwgAdaptationRow()

        swAutoUpdate.isChecked = initialAutoAppUpdate

        setupSwitchColor(swAutoUpdate, initialAutoAppUpdate)

        swAutoUpdate.setOnCheckedChangeListener { _, isChecked ->

            clientData.setAutoAppUpdate(isChecked)

            setupSwitchColor(swAutoUpdate, isChecked)

            AppUpdateManager.syncSchedule(this)

            if (isChecked) {

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {

                    Toast.makeText(this, "Разрешите установку обновлений для Nova", Toast.LENGTH_LONG).show()

                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))

                }

                AppUpdateManager.enqueueImmediateCheck(this, reason = "settings-toggle")

            }

            updateManualUpdateUi()

        }

        rowManualUpdateCheck.setOnClickListener {

            if (AppUpdateManager.hasReadyDownloadedUpdate(this)) {

                AppUpdateManager.installReadyUpdate(this)

            } else {

                runManualUpdateCheck()

            }

        }

        updateManualUpdateUi()



        updateQuickTileUi(swQSTile, tvQSTileNote, initialQuickTileAdded)

        swQSTile.setOnCheckedChangeListener { _, isChecked ->

            if (suppressQuickTileSwitchCallback) return@setOnCheckedChangeListener

            if (isChecked) {

                requestQuickTile(swQSTile, tvQSTileNote)

            } else {

                requestQuickTileRemoval(swQSTile, tvQSTileNote)

            }

        }

        rowQSTile.setOnClickListener {

            swQSTile.toggle()

        }



        configureConnectionSelector(tvConnectionSelectorTitle, rgExitRegion, protocolButtons, tvLastExit)



        swTrafficMask.isChecked = initialTrafficMaskEnabled

        setupSwitchColor(swTrafficMask, initialTrafficMaskEnabled)

        when (initialTrafficMaskMode) {

            "custom" -> rgTrafficMaskMode.check(rbMaskCustom.id)
            else -> rgTrafficMaskMode.check(rbMaskAuto.id)

        }

        updateTrafficMaskUi(swTrafficMask, rgTrafficMaskMode, etTrafficMaskHost, tvTrafficMaskActive)

        updateWarpConfigsSummary(tvWarpConfigsNote)

        updateLocalProxySummary(tvLocalProxyNote)

        setupWarpGenerateCard()

        rowWarpConfigs.setOnClickListener {

            startActivity(Intent(this, WarpConfigsActivity::class.java))

        }

        rowMainBackground.setOnClickListener {

            startActivity(Intent(this, BackgroundSettingsActivity::class.java))

        }

        rowDnsSettings.setOnClickListener {

            startActivity(Intent(this, DnsSettingsActivity::class.java))

        }

        rowNotificationSettings.setOnClickListener {

            startActivity(Intent(this, NotificationSettingsActivity::class.java))

        }

        rowDomainBypass.setOnClickListener {

            startActivity(Intent(this, DomainBypassActivity::class.java))

        }

        rowDirectFlow.setOnClickListener {

            startActivity(Intent(this, DirectFlowActivity::class.java))

        }

        rowAddWidget.setOnClickListener {

            // Часть лаунчеров закрепление по запросу не поддерживает вовсе, и до
            // Android 8 системного запроса нет. Молчать в этом случае нельзя:
            // нажатие без видимого следа читается как поломка.

            if (!NovaWidgetProvider.requestPin(this)) {

                Toast.makeText(

                    this,

                    "Лаунчер не умеет добавлять виджет сам. Долгое нажатие на рабочем столе, раздел «Виджеты», Nova",

                    Toast.LENGTH_LONG,

                ).show()

            }

        }

        rowSniMask.setOnClickListener {

            startActivity(Intent(this, SniMaskSettingsActivity::class.java))

        }

        val rowOperaApiProxy = findViewById<LinearLayout>(R.id.row_opera_api_proxy)

        val tvOperaApiProxyNote = findViewById<TextView>(R.id.tv_opera_api_proxy_note)

        fun renderOperaApiProxyNote() {

            val value = clientData.getCustomOperaApiProxy()

            tvOperaApiProxyNote.text = if (value.isBlank()) {

                "Не задан: вызовы API идут обычным порядком"

            } else {

                // Логин с паролем на экран не выводим: строка видна через плечо и
                // уходит в скриншоты, а сам адрес и так всё объясняет.
                val scheme = value.substringBefore("://", missingDelimiterValue = "")

                val rest = value.substringAfter("://", missingDelimiterValue = value)

                val hostPort = rest.substringAfterLast('@')

                if (scheme.isEmpty()) hostPort else "$scheme://$hostPort"

            }

        }

        renderOperaApiProxyNote()

        rowOperaApiProxy.setOnClickListener {

            val input = EditText(this).apply {

                setText(clientData.getCustomOperaApiProxy())

                hint = "1.2.3.4:1080"

                setSingleLine()

            }

            android.app.AlertDialog.Builder(this)

                .setTitle("Прокси для вызовов API Opera")

                .setMessage(
                    "Через него идут только вызовы API SurfEasy: сам туннель набирается " +
                        "напрямую, страна выхода не меняется.\n\n" +
                        "Форматы: 1.2.3.4:1080 (SOCKS5), socks5://1.2.3.4:1080, " +
                        "http://логин:пароль@1.2.3.4:3128.\n\n" +
                        "Лучше указывать IP-адрес: имя хоста opera-proxy резолвит сам, " +
                        "а на Android его резолвер не работает.\n\n" +
                        "Пустое поле — прежнее поведение."
                )

                .setView(input)

                .setPositiveButton("Сохранить") { _, _ ->

                    clientData.setCustomOperaApiProxy(input.text?.toString().orEmpty())

                    renderOperaApiProxyNote()

                }

                .setNegativeButton("Отмена", null)

                .show()

        }

        val rowWarpLicense = findViewById<LinearLayout>(R.id.row_warp_license)
        tvWarpLicenseNote = findViewById(R.id.tv_warp_license_note)
        updateWarpLicenseNote()
        rowWarpLicense.setOnClickListener { showWarpLicenseDialog() }

        rowLocalProxy.setOnClickListener {

            startActivity(Intent(this, LocalProxyActivity::class.java))

        }

        swLogs.setOnCheckedChangeListener { _, isChecked ->

            val config = clientData.getDiagnosticLogSettingsConfig()

            clientData.saveDiagnosticLogSettingsConfig(

                DiagnosticLogSettingsConfig(

                    enabled = isChecked,

                    level = config.level

                )

            )

            LogManager.reloadSettings()

            setupSwitchColor(swLogs, isChecked)

            layoutLogsActions.visibility = if (isChecked) View.VISIBLE else View.GONE

            tvLogsNote.text = if (isChecked) "Включено" else "Выключено"

        }

        btnClearLogs.setOnClickListener {

            LogManager.clearCapturedLogs()

            Toast.makeText(this, "Логи успешно стёрты", Toast.LENGTH_SHORT).show()

        }

        btnExportLogs.setOnClickListener {

            exportDiagnosticsLog()

        }

        swTrafficMask.setOnCheckedChangeListener { _, isChecked ->

            clientData.setTrafficMaskEnabled(isChecked)

            setupSwitchColor(swTrafficMask, isChecked)

            updateTrafficMaskUi(swTrafficMask, rgTrafficMaskMode, etTrafficMaskHost, tvTrafficMaskActive)

        }

        rgTrafficMaskMode.setOnCheckedChangeListener { _, checkedId ->

            val mode = when (checkedId) {

                rbMaskCustom.id -> "custom"

                else -> "auto"

            }

            clientData.setTrafficMaskMode(mode)

            updateTrafficMaskUi(swTrafficMask, rgTrafficMaskMode, etTrafficMaskHost, tvTrafficMaskActive)

        }

        etTrafficMaskHost.setText(initialTrafficMaskHost)

        etTrafficMaskHost.addTextChangedListener(object : TextWatcher {

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

            override fun afterTextChanged(s: Editable?) {

                clientData.setTrafficMaskHost(s?.toString())

                updateTrafficMaskUi(swTrafficMask, rgTrafficMaskMode, etTrafficMaskHost, tvTrafficMaskActive)

            }

        })



        // 3. Split Mode

        when (initialSplitMode) {

            1 -> rgMode.check(R.id.rb_allow)

            2 -> rgMode.check(R.id.rb_disallow)

            else -> rgMode.check(R.id.rb_all)

        }



        // 3. App List

        adapter = AppAdapter { pkg, isSelected ->

            val currentSet = clientData.getSplitApps().toMutableSet()

            if (isSelected) currentSet.add(pkg) else currentSet.remove(pkg)

            clientData.setSplitApps(currentSet)

            updateSplitDnsHint(tvSplitDnsHint)

            scheduleSplitChangesReapply()

        }

        

        rvApps.layoutManager = LinearLayoutManager(this)

        rvApps.setHasFixedSize(true)

        rvApps.adapter = adapter

        rvApps.isNestedScrollingEnabled = initialSplitMode != 0

        rvApps.overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS

        rvApps.setOnTouchListener { view, event ->

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN,

                MotionEvent.ACTION_MOVE -> setAppListParentInterceptDisabled(true)

                MotionEvent.ACTION_UP,

                MotionEvent.ACTION_CANCEL -> setAppListParentInterceptDisabled(false)

            }

            false

        }

        rvApps.addOnScrollListener(object : RecyclerView.OnScrollListener() {

            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {

                if (newState == RecyclerView.SCROLL_STATE_IDLE) {

                    setAppListParentInterceptDisabled(false)

                } else {

                    setAppListParentInterceptDisabled(true)

                }

            }

        })

        updateAppListHeight(initialSplitMode != 0)

        window.decorView.post {

            if (initialSplitMode != 0) {

                AppCacheManager.prewarmAsync(this)

                loadAppsIfNeeded()

            }

        }

        val swShowSystemApps = findViewById<android.widget.Switch>(R.id.sw_show_system_apps)

        swShowSystemApps.isChecked = clientData.isShowSystemAppsEnabled()

        swShowSystemApps.setOnCheckedChangeListener { _, checked ->

            clientData.setShowSystemAppsEnabled(checked)

            submitFilteredApps()

        }

        if (initialSplitMode != 0) {

            val cachedApps = AppCacheManager.peekInstalledApps(this, initialSplitApps)

            if (cachedApps.isNotEmpty()) {

                // Список рисуется сразу, а отметки «идёт мимо туннеля» доезжают
                // следом: `DirectAppsPolicy` опрашивает `PackageManager` по
                // каждому пакету закрытого списка, и после возврата из «Прямого
                // потока» его кэш сброшен — то есть здесь стоял бы десяток
                // биндер-вызовов до первого кадра (I13).

                allApps = cachedApps

                submitFilteredApps()

                scope.launch {

                    val marked = withContext(Dispatchers.IO) { markDirectApps(cachedApps) }

                    if (isFinishing || isDestroyed) return@launch

                    allApps = marked

                    submitFilteredApps()

                }

            }

        }



        updateAppListState(clientData.getSplitMode(), rvApps, etSearch, tvSplitSectionTitle, tvSplitDnsHint, false)

        

        rgMode.setOnCheckedChangeListener { _, checkedId ->

            val newMode = when (checkedId) {

                R.id.rb_allow -> 1

                R.id.rb_disallow -> 2

                else -> 0

            }

            clientData.setSplitMode(newMode)

            updateAppListState(newMode, rvApps, etSearch, tvSplitSectionTitle, tvSplitDnsHint, true)

            scheduleSplitChangesReapply()

        }

        

        etSearch.addTextChangedListener(object : TextWatcher {

            override fun afterTextChanged(s: Editable?) {}

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {

                submitFilteredApps(s?.toString().orEmpty())

            }

        })

    }



    private fun loadAppsIfNeeded(forceRefresh: Boolean = false) {

        if (appLoadJob?.isActive == true) return

        if (!forceRefresh && allApps.isNotEmpty()) {

            submitFilteredApps()

            AppCacheManager.prewarmAsync(this)

            return

        }



        appLoadJob?.cancel()

        appLoadJob = scope.launch {

            val savedSelection = clientData.getSplitApps()

            val loaded = AppCacheManager.getInstalledApps(this@SettingsActivity, savedSelection)

            // `getInstalledApps` возвращает управление уже на главном потоке, а
            // `markDirectApps` — это опрос `PackageManager` по каждому пакету
            // закрытого списка. Без переключения он шёл бы там же (I13).

            allApps = withContext(Dispatchers.IO) { markDirectApps(loaded) }

            submitFilteredApps()

        }

    }

    

    private fun updateAppListState(

        mode: Int,

        rv: RecyclerView,

        et: EditText,

        sectionTitle: TextView,

        dnsHintView: TextView,

        scrollToSection: Boolean,

    ) {

        val isEnabled = (mode != 0) // 0 = ALL, 1/2 = Custom

        

        if (isEnabled) {

            rv.visibility = android.view.View.VISIBLE

            et.visibility = android.view.View.VISIBLE

            rv.isNestedScrollingEnabled = true

            updateAppListHeight(true)

            loadAppsIfNeeded()

            submitFilteredApps()

            if (scrollToSection) {

                scrollContent.post {

                    scrollContent.smoothScrollTo(0, (sectionTitle.top - (24 * resources.displayMetrics.density).toInt()).coerceAtLeast(0))

                    rv.postDelayed({

                        rv.requestFocus()

                        rv.requestFocusFromTouch()

                    }, 220L)

                }

            }

        } else {

            rv.visibility = android.view.View.GONE

            et.visibility = android.view.View.GONE

            rv.isNestedScrollingEnabled = false

            updateAppListHeight(false)

        }



        et.isEnabled = isEnabled

        dnsHintView.visibility = View.GONE

        dnsHintView.text = ""

    }



    private fun updateSplitDnsHint(hintView: TextView) {

        hintView.visibility = View.GONE

        hintView.text = ""

    }



    private fun setAppListParentInterceptDisabled(disabled: Boolean) {

        scrollContent.requestDisallowInterceptTouchEvent(disabled)

        var parent = rvApps.parent

        while (parent != null) {

            parent.requestDisallowInterceptTouchEvent(disabled)

            parent = parent.parent

        }

    }



    private fun updateAppListHeight(enabled: Boolean) {

        val params = rvApps.layoutParams ?: return

        params.height = if (enabled) {

            val desired = (resources.displayMetrics.heightPixels * 0.76f).toInt()

            desired.coerceAtLeast(dp(620)).coerceAtMost(dp(1080))

        } else {

            dp(360)

        }

        rvApps.layoutParams = params

    }



    private fun dp(value: Int): Int {

        return (value * resources.displayMetrics.density).toInt()

    }



    /**
     * Помечает приложения, которые уходят мимо туннеля независимо от галочки.
     *
     * Набор считается один раз на весь список: [DirectAppsPolicy] опрашивает
     * `PackageManager` про каждое имя, и делать это на каждую строку значило бы
     * сотни обращений при прокрутке.
     */
    private fun markDirectApps(items: List<AppItem>): List<AppItem> {

        val direct = DirectAppsPolicy.resolve(this, clientData)

        if (direct.isEmpty()) return items

        items.forEach { item -> item.isDirect = item.packageName in direct }

        return items

    }

    private fun submitFilteredApps(query: String = etSearch.text?.toString().orEmpty()) {

        if (!::adapter.isInitialized) return

        val lowered = query.lowercase()

        val showSystem = clientData.isShowSystemAppsEnabled()

        val filtered = if (lowered.isBlank()) {

            allApps

        } else {

            allApps.filter { it.label.lowercase().contains(lowered) || it.packageName.lowercase().contains(lowered) }

        }.filter { item ->

            // Уже выбранное приложение из списка не исчезает, даже если оно
            // системное: иначе галочка стояла бы у пункта, которого не видно, и
            // снять её было бы нечем.

            showSystem || !item.isSystem || item.isSelected

        }.sortedWith(compareByDescending<AppItem> { it.isSelected }.thenBy { it.label.lowercase() })

        adapter.setData(filtered)

    }

    

    // Dirty Check Logic

    override fun onPause() {

        super.onPause()

        uiRefreshHandler.removeCallbacks(trafficMaskRefreshRunnable)

        uiRefreshHandler.removeCallbacks(manualUpdateRefreshRunnable)

        stopWarpGeneratePolling()

        reapplyHandler.removeCallbacks(splitReapplyRunnable)

        val currentSplitMode = clientData.getSplitMode()

        val currentSplitApps = clientData.getSplitApps()

        val currentAutoReconnect = clientData.getAutoReconnect()

        val currentAutoAppUpdate = clientData.getAutoAppUpdate()

        val currentExitRegionPreference = clientData.getExitRegionPreference()

        val currentImportedProtocolPreference = clientData.getImportedProtocolPreference()

        val currentTrafficMaskEnabled = clientData.getTrafficMaskEnabled()

        val currentTrafficMaskMode = clientData.getTrafficMaskMode()

        val currentTrafficMaskHost = clientData.getTrafficMaskHost()

        val splitChanged = currentSplitMode != initialSplitMode || currentSplitApps != initialSplitApps

        val effectiveSplitChanged = splitChanged && !splitReapplyHandledInPlace

        val regionChanged = currentExitRegionPreference != initialExitRegionPreference

        val protocolChanged = currentImportedProtocolPreference != initialImportedProtocolPreference

        val trafficMaskChanged =

            currentTrafficMaskEnabled != initialTrafficMaskEnabled ||

                currentTrafficMaskMode != initialTrafficMaskMode ||

                currentTrafficMaskHost != initialTrafficMaskHost

        val effectiveRegionChanged = regionChanged && !regionReapplyHandledInPlace

        val effectiveProtocolChanged = protocolChanged && !protocolReapplyHandledInPlace

        val otherRestartRelevantChanged =

            currentAutoReconnect != initialAutoReconnect ||

                currentAutoAppUpdate != initialAutoAppUpdate ||

                effectiveRegionChanged ||

                effectiveProtocolChanged ||

                trafficMaskChanged

        

        if (effectiveSplitChanged || otherRestartRelevantChanged) {

            ClientData.needsRestart = true

        }



        maybeApplyActiveSessionChanges(

            splitChanged = effectiveSplitChanged,

            regionChanged = effectiveRegionChanged,

            protocolChanged = effectiveProtocolChanged,

            trafficMaskChanged = trafficMaskChanged,

        )

        splitReapplyHandledInPlace = false

        regionReapplyHandledInPlace = false

        protocolReapplyHandledInPlace = false

    }



    override fun onResume() {

        super.onResume()

        // Снимок раздельного туннелирования переснимается при каждом возврате на
        // экран. Он снят в onCreate, а список правит и соседний экран «Прямой
        // поток» — и правит уже применённо, сам отдавая изменение живому сеансу.
        // Со старым снимком onPause сравнивал бы «как было до открытия Настроек»
        // с «как стало после чужой правки» и применял бы то же самое второй раз:
        // на Opera это stop-then-start туннеля за чужую галочку.
        initialSplitMode = clientData.getSplitMode()
        initialSplitApps = clientData.getSplitApps()

        AppUpdateManager.resumePendingInstallIfAllowed(this)

        val swBackground = findViewById<Switch>(R.id.sw_background)

        val rowAutostart = findViewById<LinearLayout>(R.id.row_autostart)

        val swAutostart = findViewById<Switch>(R.id.sw_autostart)

        val rowBackgroundVendor = findViewById<TextView>(R.id.row_background_vendor)

        checkBatteryOptimization(swBackground)

        updateAutoStartRow(rowAutostart, swAutostart)

        updateVendorBackgroundRow(rowBackgroundVendor)

        findViewById<TextView>(R.id.tv_warp_configs_note)?.let(::updateWarpConfigsSummary)

        findViewById<TextView>(R.id.tv_local_proxy_note)?.let(::updateLocalProxySummary)

        configureConnectionSelector(

            findViewById(R.id.tv_connection_selector_title),

            findViewById(R.id.rg_exit_region),

            ConnectionSelectorPolicy.BUTTON_IDS.mapNotNull { findViewById<RadioButton>(it) },

            findViewById(R.id.tv_exit_last),

        )

        updateTrafficMaskUi(swTrafficMask, rgTrafficMaskMode, etTrafficMaskHost, tvTrafficMaskActive)

        if (clientData.getSplitMode() != 0) {

            loadAppsIfNeeded(forceRefresh = true)

        }

        uiRefreshHandler.removeCallbacks(trafficMaskRefreshRunnable)

        uiRefreshHandler.post(trafficMaskRefreshRunnable)

        uiRefreshHandler.removeCallbacks(manualUpdateRefreshRunnable)

        uiRefreshHandler.post(manualUpdateRefreshRunnable)

        // Один шаг опроса заводится всегда: он же и рисует итог прошлого прогона,
        // а продолжится только если прогон действительно идёт.
        startWarpGeneratePolling()

        // Профили могли появиться, пока экрана не было: от этого зависит, что
        // написано на кнопке — «сгенерировать» или «обновить».
        refreshIssuedProfilesState()

        refreshTorBridgeStatus()

    }



    override fun onStart() {

        super.onStart()

        registerPackageChangesReceiver()

        registerVpnStateReceiver()

    }



    override fun onStop() {

        super.onStop()

        unregisterPackageChangesReceiver()

        unregisterVpnStateReceiver()

    }



    override fun onDestroy() {

        super.onDestroy()

        reapplyHandler.removeCallbacks(splitReapplyRunnable)

        stopWarpGeneratePolling()

        unregisterPackageChangesReceiver()

        unregisterVpnStateReceiver()

        appLoadJob?.cancel()

        protonStatusListener?.let(ProtonProfileManager::removeListener)

        protonStatusListener = null

        scope.cancel()

    }

    

    private fun exportDiagnosticsLog() {

        runCatching {

            val moscowTz = TimeZone.getTimeZone("GMT+3")

            val fileFormat = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {

                timeZone = moscowTz

            }

            val headerFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'GMT+3'", Locale.US).apply {

                timeZone = moscowTz

            }

            val date = Date()

            val fileName = "NA_${fileFormat.format(date)}.log"

            val headerTimestamp = headerFormat.format(date)

            

            val packageInfo = packageManager.getPackageInfo(packageName, 0)

            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {

                packageInfo.longVersionCode.toString()

            } else {

                @Suppress("DEPRECATION")

                packageInfo.versionCode.toString()

            }

            val snapshot = clientData.getTunnelUiSnapshot()

            val directSnapshot = clientData.getDirectUiSnapshot()

            

            val logsContent = LogManager.getPersistedLogs()

            val sanitizedLogs = DiagnosticLogSanitizer.sanitize(logsContent)

            

            val report = buildString {

                appendLine("Nova diagnostic log")

                appendLine("generated_at=$headerTimestamp")

                appendLine("app_version=${packageInfo.versionName ?: "unknown"} ($versionCode)")

                appendLine("android=${Build.VERSION.RELEASE ?: "unknown"} sdk=${Build.VERSION.SDK_INT}")

                appendLine("service_state=${clientData.getServiceState().ifBlank { "unknown" }}")

                appendLine("backend=${clientData.getServiceBackend().ifBlank { "unknown" }}")

                appendLine("exit_preference=${clientData.getExitRegionPreference()}")

                appendLine("vpn_snapshot_backend=${snapshot?.backend?.ifBlank { "unknown" } ?: "unknown"}")

                appendLine("vpn_snapshot_country=${snapshot?.country?.ifBlank { "unknown" } ?: "unknown"}")

                appendLine("direct_snapshot_country=${directSnapshot?.country?.ifBlank { "unknown" } ?: "unknown"}")

                appendLine("logging=${clientData.getDiagnosticLogSettingsSummary()}")

                appendLine()

                appendLine("--- logs ---")

                if (sanitizedLogs.isBlank()) {

                    appendLine("Логов пока нет")

                } else {

                    appendLine(sanitizedLogs)

                }

            }



            val logsDir = File(cacheDir, "logs").apply { mkdirs() }

            logsDir.listFiles()?.forEach { it.delete() }

            

            val exportFile = File(logsDir, fileName)

            exportFile.writeText(report, Charsets.UTF_8)

            

            val uri = FileProvider.getUriForFile(this, "com.brent.nova.provider", exportFile)

            

            val shareIntent = Intent(Intent.ACTION_SEND).apply {

                type = "text/plain"

                putExtra(Intent.EXTRA_SUBJECT, "Nova diagnostic log")

                putExtra(Intent.EXTRA_STREAM, uri)

                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

            }

            startActivity(Intent.createChooser(shareIntent, "Отправить лог"))

        }.onFailure {

            Toast.makeText(this, "Ошибка экспорта лога: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()

        }

    }



    override fun finish() {

        super.finish()

        overridePendingTransition(0, 0) // No animation on close

    }



    private fun maybeApplyActiveSessionChanges(

        splitChanged: Boolean,

        regionChanged: Boolean,

        protocolChanged: Boolean,

        trafficMaskChanged: Boolean,

    ) {

        val requiresReapply = splitChanged || regionChanged || protocolChanged || trafficMaskChanged

        if (!requiresReapply) return



        val isVpnActive = isNovaSessionLikelyActive()

        if (!isVpnActive) return



        LogManager.log(

            "Настройки запускают мягкое применение: splitChanged=$splitChanged, " +

                "regionChanged=$regionChanged(${clientData.getExitRegionPreference()}), " +

                "protocolChanged=$protocolChanged(${clientData.getImportedProtocolPreference()}), " +

                "trafficMaskChanged=$trafficMaskChanged(" +

                "${clientData.getTrafficMaskMode()}:${clientData.getTrafficMaskHost()})"

        )

        val toastMessage = when {

            protocolChanged -> "Применяем новый протокол VPN..."

            regionChanged -> "Применяем новый регион VPN..."

            trafficMaskChanged -> "Применяем новые параметры подключения..."

            else -> "Применяем новые правила VPN..."

        }

        if (shouldUseControlledOperaRestartReapply()) {

            launchControlledOperaReapply(toastMessage)

            return

        }

        launchDirectReapply(toastMessage)

    }



    private fun scheduleSplitChangesReapply() {

        val likelyActive = isNovaSessionLikelyActive()

        LogManager.log(

            "Split tunneling изменён в UI. Планируем reapply: " +

                "active=$likelyActive mode=${clientData.getSplitMode()} apps=${clientData.getSplitApps().size}"

        )

        if (!likelyActive) return

        reapplyHandler.removeCallbacks(splitReapplyRunnable)

        reapplyHandler.postDelayed(splitReapplyRunnable, 650L)

    }



    private fun maybeApplySplitChangesImmediately() {

        val currentSplitMode = clientData.getSplitMode()

        val currentSplitApps = clientData.getSplitApps()

        val splitChanged = currentSplitMode != initialSplitMode || currentSplitApps != initialSplitApps

        if (!splitChanged || !isNovaSessionLikelyActive()) return

        runCatching {

            LogManager.log(

                "Split tunneling изменён в Настройках. Сразу применяем активный VPN-сеанс: " +

                    "mode=$currentSplitMode apps=${currentSplitApps.size}"

            )

            maybeApplyActiveSessionChanges(

                splitChanged = true,

                regionChanged = false,

                protocolChanged = false,

                trafficMaskChanged = false,

            )

            initialSplitMode = currentSplitMode

            initialSplitApps = currentSplitApps

            splitReapplyHandledInPlace = true

        }.onFailure { error ->

            splitReapplyHandledInPlace = false

            LogManager.log("Не удалось сразу применить split tunneling: ${error.message}")

        }

    }



    private fun maybeApplyRegionChangeImmediately(newRegion: String) {

        if (!isNovaSessionLikelyActive()) return

        runCatching {

            LogManager.log("Регион VPN изменён в Настройках на $newRegion. Запускаем немедленный мягкий реконнект.")

            val toastMessage = "Переключаем VPN на ${formatRegionDisplayName(newRegion)}..."

            if (shouldUseControlledOperaRestartReapply()) {

                launchControlledOperaReapply(toastMessage)

            } else {

                launchDirectReapply(toastMessage)

            }

            initialExitRegionPreference = newRegion

            regionReapplyHandledInPlace = true

        }.onFailure { error ->

            LogManager.log("Не удалось сразу применить новый регион VPN: ${error.message}")

        }

    }



    private fun maybeApplyProtocolChangeImmediately(newProtocol: String) {

        if (!isNovaSessionLikelyActive()) return

        runCatching {

            LogManager.log("Протокол VPN изменён в Настройках на $newProtocol. Запускаем немедленный мягкий реконнект.")

            val toastMessage = "Переключаем протокол VPN на ${clientData.formatImportedProtocolDisplay(newProtocol)}..."

            if (shouldUseControlledOperaRestartReapply()) {

                launchControlledOperaReapply(toastMessage)

            } else {

                launchDirectReapply(toastMessage)

            }

            initialImportedProtocolPreference = newProtocol

            protocolReapplyHandledInPlace = true

        }.onFailure { error ->

            LogManager.log("Не удалось сразу применить новый протокол VPN: ${error.message}")

        }

    }



    private fun launchDirectReapply(toastMessage: String) {

        // Порядок живёт в [SessionReapply]: главный экран делает то же самое, и

        // вторая копия — это ровно тот способ, которым случается G49.

        if (SessionReapply.launchDirect(this, clientData)) {

            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()

        }

    }



    private fun launchControlledOperaReapply(toastMessage: String) {

        // Ожидание остановки переживает экран: настройки можно закрыть сразу

        // после нажатия, а запустить сеанс заново всё равно надо (I18, G83).

        // Поэтому и часы, и признак «опрос уже идёт» живут в [SessionReapply], а

        // не в этой активности.

        if (SessionReapply.launchControlledOperaRestart(this, clientData)) {

            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()

        }

    }






    private fun shouldUseControlledOperaRestartReapply(): Boolean =

        SessionReapply.needsControlledOperaRestart(this, clientData)



    private fun registerPackageChangesReceiver() {

        val filter = IntentFilter().apply {

            addAction(Intent.ACTION_PACKAGE_ADDED)

            addAction(Intent.ACTION_PACKAGE_REMOVED)

            addAction(Intent.ACTION_PACKAGE_CHANGED)

            addAction(Intent.ACTION_PACKAGE_REPLACED)

            addDataScheme("package")

        }

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                registerReceiver(packageChangesReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

            } else {

                registerReceiver(packageChangesReceiver, filter)

            }

        } catch (_: Exception) {

        }

    }



    private fun unregisterPackageChangesReceiver() {

        try {

            unregisterReceiver(packageChangesReceiver)

        } catch (_: Exception) {

        }

    }



    private fun registerVpnStateReceiver() {

        if (vpnStateReceiverRegistered) return

        val filter = IntentFilter(NovaVpnService.ACTION_VPN_STATE)

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                registerReceiver(vpnStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

            } else {

                registerReceiver(vpnStateReceiver, filter)

            }

            vpnStateReceiverRegistered = true

        } catch (_: Exception) {

        }

    }



    private fun unregisterVpnStateReceiver() {

        if (!vpnStateReceiverRegistered) return

        try {

            unregisterReceiver(vpnStateReceiver)

        } catch (_: Exception) {

        } finally {

            vpnStateReceiverRegistered = false

        }

    }



    private fun handleInstalledAppsChanged() {

        AppCacheManager.clearCache(this)

        if (clientData.getSplitMode() != 0) {

            loadAppsIfNeeded(forceRefresh = true)

        } else {

            AppCacheManager.prewarmAsync(this)

        }

    }






    private fun hasActiveNovaSystemVpn(context: Context = this): Boolean {

        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false

        fun isLikelyNovaVpn(network: android.net.Network): Boolean {

            val caps = cm.getNetworkCapabilities(network) ?: return false

            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false

            if (isNovaVpnOwner(caps)) return true

            val transportInfo = extractVpnTransportLabel(caps)

            return transportInfo.contains("NovaVPN", ignoreCase = true) ||

                transportInfo.contains("NovaOperaVPN", ignoreCase = true)

        }



        fun networkId(network: android.net.Network): Int {

            return network.toString().toIntOrNull() ?: -1

        }



        fun score(network: android.net.Network): Int {

            val caps = cm.getNetworkCapabilities(network) ?: return Int.MIN_VALUE

            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return Int.MIN_VALUE

            var score = 0

            if (isLikelyNovaVpn(network)) score += 1_000

            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 200

            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) score += 50

            return score

        }



        val active = cm.activeNetwork

        if (active != null && isLikelyNovaVpn(active)) return true



        val bestVpn = cm.allNetworks

            .filter { network ->

                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

            }

            .maxWithOrNull(

                compareBy<android.net.Network> { score(it) }

                    .thenBy { networkId(it) }

            )



        if (bestVpn != null && isLikelyNovaVpn(bestVpn)) return true



        val anyVpnPresent = bestVpn != null

        return anyVpnPresent && hasStrongLocalNovaSessionEvidence()

    }



    private fun extractVpnTransportLabel(caps: NetworkCapabilities?): String {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ""

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

        val serviceState = clientData.getServiceState()

        if (

            serviceState == NovaVpnService.STATE_CONNECTED ||

            serviceState == NovaVpnService.STATE_CONNECTING ||

            clientData.isSoftReapplyPending() ||

            clientData.isTransientConnectingPending()

        ) {

            return isNovaVpnServiceRunning()

        }

        val updatedAt = clientData.getServiceStateUpdatedAt()

        val ageMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)

        return clientData.getRestartSession() != null && ageMs in 0..90_000L && isNovaVpnServiceRunning()

    }



    private fun isNovaVpnServiceRunning(): Boolean {

        @Suppress("DEPRECATION")

        return (getSystemService(ActivityManager::class.java)?.getRunningServices(Int.MAX_VALUE) ?: emptyList())

            .any { service -> service.service?.className == NovaVpnService::class.java.name }

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



    private fun isNovaSessionLikelyActive(): Boolean {

        val serviceState = clientData.getServiceState()

        if (serviceState == NovaVpnService.STATE_CONNECTED || serviceState == NovaVpnService.STATE_CONNECTING) {

            return true

        }

        if (clientData.getRestartSession() != null) {

            return true

        }

        return hasActiveNovaSystemVpn()

    }

    

    private fun checkBatteryOptimization(switch: Switch) {

        val green = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#13A10E"))

        val grey = android.content.res.ColorStateList.valueOf(android.graphics.Color.GRAY)



        suppressBackgroundSwitchCallback = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            val pm = getSystemService(PowerManager::class.java)

            val isIgnored = pm.isIgnoringBatteryOptimizations(packageName)

            switch.isChecked = isIgnored

            

            applyLiquidSwitchTint(switch)

            switch.isEnabled = true

        } else {

             switch.isChecked = true

             applyLiquidSwitchTint(switch)

             switch.isEnabled = false

        }

        suppressBackgroundSwitchCallback = false

    }



    private fun updateAutoStartRow(row: LinearLayout, switch: Switch) {

        val isAvailable = VendorBackgroundSettingsHelper.canOpenAutoStart(this)

        row.visibility = if (isAvailable) View.VISIBLE else View.GONE

        suppressAutostartSwitchCallback = true

        switch.isChecked = clientData.getAutostartEnabledHint()

        setupSwitchColor(switch, switch.isChecked)

        switch.isEnabled = isAvailable

        suppressAutostartSwitchCallback = false

    }

    

    private fun requestBatteryOptimization() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            val intent = Intent()

            val pm = getSystemService(PowerManager::class.java)

            if (!pm.isIgnoringBatteryOptimizations(packageName)) {

                intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS

                intent.data = Uri.parse("package:$packageName")

                startActivity(intent)

            }

        }

    }

    

    /**
     * Красит переключатель.
     *
     * Раньше бегунок и дорожка красились одним цветом, и переключатель выглядел
     * сплошной заливкой: положение бегунка не читалось совсем, а состояние
     * приходилось подписывать словом внутри. Теперь цвета приходят списками
     * состояний — белый бегунок на зелёной дорожке во включённом положении и
     * серый на тёмной в выключенном.
     *
     * [isChecked] больше не участвует в раскраске: список состояний берёт
     * состояние у самого переключателя, поэтому цвет не может разъехаться с
     * положением. Параметр оставлен, чтобы не трогать тринадцать мест вызова.
     */
    private fun setupSwitchColor(switch: Switch, isChecked: Boolean) {

        applyLiquidSwitchTint(switch)

    }

    private fun applyLiquidSwitchTint(switch: Switch) {

        switch.thumbTintList = ContextCompat.getColorStateList(this, R.color.switch_thumb_tint_liquid)

        switch.trackTintList = ContextCompat.getColorStateList(this, R.color.switch_track_tint_liquid)

    }

    /**
     * То же самое для `SwitchCompat`.
     *
     * Перегрузка, а не общий тип: `SwitchCompat` наследуется от `CompoundButton`, а
     * не от платформенного `Switch`, и в соседнюю функцию не проходит. Списки
     * состояний те же самые — геометрия и палитра переключателей подобраны вручную
     * после того, как обновление appcompat поменяло умолчания библиотеки, и выводить
     * их заново нельзя.
     */
    private fun applyLiquidSwitchTint(switch: SwitchCompat) {

        switch.thumbTintList = ContextCompat.getColorStateList(this, R.color.switch_thumb_tint_liquid)

        switch.trackTintList = ContextCompat.getColorStateList(this, R.color.switch_track_tint_liquid)

    }

    

    private fun setupFooterLink(textView: TextView) {

        val versionName = try {

            packageManager.getPackageInfo(packageName, 0).versionName ?: "1.12"

        } catch (_: Exception) {

            "1.12"

        }

        val fullText = "Nova v$versionName - создана с ❤️ Telegram чат"

        val linkText = "Telegram чат"

        val spannableString = android.text.SpannableString(fullText)

        

        val clickableSpan = object : android.text.style.ClickableSpan() {

            override fun onClick(widget: android.view.View) {

                openTelegramChat()

            }

            

            override fun updateDrawState(ds: android.text.TextPaint) {

                super.updateDrawState(ds)

                ds.isUnderlineText = true

                ds.color = android.graphics.Color.parseColor("#50C878") // Malachite Green

                ds.clearShadowLayer() // Remove any shadow

            }

        }

        

        val startIndex = fullText.indexOf(linkText)

        if (startIndex >= 0) {

            spannableString.setSpan(

                clickableSpan,

                startIndex,

                startIndex + linkText.length,

                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

            )

        }

        

        textView.text = spannableString

        textView.movementMethod = android.text.method.LinkMovementMethod.getInstance()

        textView.setShadowLayer(0f, 0f, 0f, 0) // Explicitly remove shadow from TextView

    }



    private fun openTelegramChat() {

        val now = android.os.SystemClock.elapsedRealtime()

        if (now - lastTelegramOpenAtMs < 1500L) {

            return

        }

        lastTelegramOpenAtMs = now

        val telegramIntent = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=nova_txt"))

        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/nova_txt"))

        try {

            if (telegramIntent.resolveActivity(packageManager) != null) {

                startActivity(telegramIntent)

            } else {

                startActivity(browserIntent)

            }

        } catch (_: Exception) {

            startActivity(browserIntent)

        }

    }



    private fun updateQuickTileUi(

        switch: Switch,

        noteView: TextView,

        added: Boolean,

    ) {

        suppressQuickTileSwitchCallback = true

        switch.isChecked = added

        setupSwitchColor(switch, added)

        switch.isEnabled = true

        switch.isClickable = true

        noteView.text = ""

        noteView.visibility = android.view.View.GONE

        suppressQuickTileSwitchCallback = false

    }



    private fun requestQuickTile(

        switch: Switch,

        noteView: TextView,

    ) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {

            showQSTileTutorial()

            return

        }



        val statusBarManager = getSystemService(StatusBarManager::class.java)

        if (statusBarManager == null) {

            showQSTileTutorial()

            return

        }



        val component = ComponentName(this, NovaTileService::class.java)

        val icon = Icon.createWithResource(this, R.drawable.ic_qs_nova)

        statusBarManager.requestAddTileService(

            component,

            "Nova",

            icon,

            mainExecutor,

        ) { result ->

            runOnUiThread {

                val added = when (result) {

                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,

                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> true

                    else -> false

                }

                clientData.setQuickTileAdded(added)

                updateQuickTileUi(switch, noteView, added)

                val message = when (result) {

                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Плитка Nova добавлена."

                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Плитка Nova уже добавлена."

                    StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> "Добавление плитки отменено."

                    StatusBarManager.TILE_ADD_REQUEST_ERROR_APP_NOT_IN_FOREGROUND -> "Открой настройки Nova на экране и попробуй ещё раз."

                    StatusBarManager.TILE_ADD_REQUEST_ERROR_REQUEST_IN_PROGRESS -> "Системный запрос уже открыт."

                    else -> "Не удалось добавить плитку автоматически."

                }

                Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

            }

        }

    }



    private fun requestQuickTileRemoval(

        switch: Switch,

        noteView: TextView,

    ) {

        clientData.setQuickTileAdded(false)

        updateQuickTileUi(switch, noteView, false)

        android.app.AlertDialog.Builder(this)

            .setTitle("Удаление плитки Nova")

            .setMessage(

                "Android не позволяет приложению убрать плитку автоматически.\n\n" +

                    "Чтобы удалить её:\n" +

                    "1. Потяни шторку вниз\n" +

                    "2. Нажми ✏️ или \"Изменить\"\n" +

                    "3. Убери плитку Nova из активной области"

            )

            .setPositiveButton("Понятно", null)

            .show()

    }



    private fun requestDisableBatteryOptimization() {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

        Toast.makeText(

            this,

            "Открой системный экран и отключи работу Nova без ограничений.",

            Toast.LENGTH_LONG

        ).show()

        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    }



    /**
     * Переключатель фоновой адаптации и показ того, что она уже подобрала.
     *
     * Список показывается **доменами**, а не значениями `I1`: шестнадцатеричная
     * простыня на полторы тысячи знаков не говорит человеку ничего, а имя домена
     * говорит всё, что ему нужно знать о подмене.
     */
    private fun setupAwgAdaptationRow() {

        val row = findViewById<View>(R.id.row_awg_adaptation) ?: return

        val switch = findViewById<Switch>(R.id.sw_awg_adaptation) ?: return

        val summary = findViewById<TextView>(R.id.tv_awg_adaptation_summary) ?: return

        fun renderSummary() {

            val overrides = clientData.getAwgI1Overrides()

            summary.text = when {

                !clientData.isAwgI1AdaptationEnabled() -> "Выключено"

                overrides.isEmpty() -> "Включено, подбор ещё не начинался"

                else -> "Подобрано профилей: ${overrides.size} — нажмите, чтобы посмотреть"

            }

        }

        switch.setOnCheckedChangeListener(null)

        switch.isChecked = clientData.isAwgI1AdaptationEnabled()

        setupSwitchColor(switch, switch.isChecked)

        renderSummary()

        switch.setOnCheckedChangeListener { _, isChecked ->

            clientData.setAwgI1AdaptationEnabled(isChecked)

            setupSwitchColor(switch, isChecked)

            renderSummary()

            LogManager.log("Фоновая адаптация I1: ${if (isChecked) "включена" else "выключена"}.")

        }

        row.setOnClickListener { showAwgAdaptationDetails(::renderSummary) }

    }

    private fun showAwgAdaptationDetails(onChanged: () -> Unit) {

        val overrides = clientData.getAwgI1Overrides().values.sortedByDescending { it.updatedAt }

        val body = if (overrides.isEmpty()) {

            "Пока ничего не подобрано.\n\nПодбор идёт в фоне: один профиль раз в полчаса, " +
                "не раньше чем через пять минут после того, как погас экран. " +
                "Сеть при этом не используется — подобранное проверяет обычное подключение."

        } else {

            overrides.joinToString("\n") { "${it.sni} — ${it.profileId.substringAfterLast('|')}" }

        }

        android.app.AlertDialog.Builder(this)

            .setTitle("Адаптация к сети")

            .setMessage(body)

            .setPositiveButton("Закрыть", null)

            .apply {

                if (overrides.isNotEmpty()) {

                    setNegativeButton("Сбросить подбор") { _, _ ->

                        clientData.clearAwgI1Overrides()

                        onChanged()

                        Toast.makeText(this@SettingsActivity, "Подбор сброшен", Toast.LENGTH_SHORT).show()

                    }

                }

            }

            .show()

    }

    private fun updateExitSummary(textView: TextView) {

        val preference = formatRegionDisplayName(clientData.getExitRegionPreference())

        val lastCountry = clientData.getLastExitCountry().ifBlank { "?" }

        val lastColo = clientData.getLastExitColo().ifBlank { "?" }

        val lastIp = clientData.getLastExitIp().ifBlank { "неизвестно" }

        textView.text = "Предпочтение: $preference. Последний выход: $lastCountry / $lastColo / $lastIp"

    }



    private fun updateProtocolSummary(textView: TextView) {

        val preference = clientData.formatImportedProtocolDisplay(clientData.getImportedProtocolPreference())

        val lastCountry = clientData.getLastExitCountry().ifBlank { "?" }

        val lastColo = clientData.getLastExitColo().ifBlank { "?" }

        val lastIp = clientData.getLastExitIp().ifBlank { "неизвестно" }

        textView.text = "Протокол: $preference. Последний выход: $lastCountry / $lastColo / $lastIp"

    }



    private fun updateVendorBackgroundRow(view: TextView) {

        val label = VendorBackgroundSettingsHelper.getBackgroundLabel(this)

        if (label.isNullOrBlank()) {

            view.visibility = View.GONE

            view.setOnClickListener(null)

            return

        }

        view.visibility = View.VISIBLE

        view.text = "Открыть: $label"

        view.setOnClickListener {

            if (!VendorBackgroundSettingsHelper.open(this)) {

                Toast.makeText(

                    this,

                    "Не удалось открыть доп. настройки фона на этом устройстве.",

                    Toast.LENGTH_SHORT

                ).show()

            }

        }

    }



    private fun formatRegionDisplayName(region: String?): String {

        return when (region?.trim()?.lowercase()) {

            // Кнопка теперь одна и называется OPERA, а подрегион уточняется рядом:
            // «EU» без слова Opera читалось как отдельный транспорт.
            "eu" -> "OPERA EU"

            "us" -> "OPERA US"

            "ru" -> "WARP"

            "masque" -> "MASQUE"

            "proton" -> "AWG Proton"

            "tor" -> "TOR"

            else -> "AUTO"

        }

    }

    /**
     * Готовит Proton-профили и подключается к самому быстрому.
     *
     * Кнопки «сгенерировать» нет намеренно: пусковым событием служит сам выбор
     * региона. Прогон живёт в [ProtonProfileManager], а не в экране, поэтому уход из
     * настроек и поворот его не прерывают; экран только показывает этапы.
     *
     * Строка статуса — та же, что обычно показывает предпочтение и последний выход.
     * Отдельной строки не заводится: вторая строка под селектором наезжала бы на
     * соседний блок, а сообщения здесь короткие и живут только на время прогона.
     */
    private fun startProtonProfilePreparation(
        summaryView: TextView,
        radioGroup: RadioGroup,
        buttons: List<RadioButton>,
    ) {

        detachProtonStatusListener()

        protonPreparationActive = true

        // Признак дублируется в синглтон: он переживает поворот экрана и служит
        // единственным местом, где выбор Proton можно отменить.
        ProtonProfileManager.markPreparationRequested()

        protonPendingMessage = null

        summaryView.visibility = View.VISIBLE

        val listener = ProtonProfileManager.StatusListener { text ->

            protonPendingMessage = text

            summaryView.post { summaryView.text = text }

        }

        protonStatusListener = listener

        ProtonProfileManager.addListener(listener)

        val previousRegion = clientData.getExitRegionPreference()

        // Выбор записывается сразу, а не по успеху.
        //
        // Пока предпочтение писалось только в ветке успеха, любой сбой выпуска
        // оставлял регион прежним, и человек, выбравший AWG Proton, приезжал на
        // встроенном семени WARP с зелёным «АКТИВНО»: явный выбор молча подменялся
        // (I1), а узнать об этом было неоткуда.
        //
        // Помощный туннель для выпуска больше не поднимается. Он был нужен ради
        // `/vpn/logicals`, но у того давно есть встроенный запас на 50 узлов, а
        // маленькие вызовы и так идут по альтернативному маршруту Proton без всякого
        // туннеля (kb P2). Зато поднятый WARP выглядел как «подключились к WARP,
        // хотя выбран Proton» — ровно то, на что жаловались.
        clientData.setExitRegionPreference("proton")
        clientData.setProtonPreparationRequested(true)
        initialExitRegionPreference = "proton"

        runProtonGeneration(summaryView, radioGroup, buttons, previousRegion)

    }


    /** Снимает слушатель этапов Proton: его строки перестают принадлежать экрану. */
    private fun detachProtonStatusListener() {

        protonStatusListener?.let(ProtonProfileManager::removeListener)

        protonStatusListener = null

    }

    /**
     * Заканчивает подготовку Proton отказом: причина остаётся на экране и в журнале.
     *
     * Одно место на все четыре пути отказа именно потому, что раньше их было четыре
     * и каждый терял сообщение по-своему: текст ставился в строку, которую
     * `configureRegionSelector` следом безусловно прятал и перезаписывал.
     */
    private fun failProtonPreparation(
        summaryView: TextView,
        radioGroup: RadioGroup,
        buttons: List<RadioButton>,
        previousRegion: String,
        message: String,
    ) {

        protonPreparationActive = false

        ProtonProfileManager.cancelPreparation()

        clientData.setProtonPreparationRequested(false)

        detachProtonStatusListener()

        protonPendingMessage = message

        // Выбор пользователя при отказе **не откатывается**. Прежде здесь стоял
        // `restoreRegionSelection`, и неудачный выпуск возвращал регион на прежний —
        // человек, выбравший AWG Proton, оказывался на «Авто» и уезжал на встроенном
        // WARP, не узнав об этом. Регион остаётся `proton`, а причина видна и на
        // экране, и в журнале: повторить можно, ничего не выбирая заново.
        LogManager.log("Proton: $message. Регион остаётся proton, выбор не меняем.")

        if (isFinishing || isDestroyed) return

        summaryView.visibility = View.VISIBLE

        summaryView.text = message

        Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    }

    private fun runProtonGeneration(
        summaryView: TextView,
        radioGroup: RadioGroup,
        buttons: List<RadioButton>,
        previousRegion: String,
    ) {

        val started = ProtonProfileManager.ensureProfiles(this) { outcome ->

            // Итог применяется **вне** вида. Выпуск занимает до минуты, и к его
            // концу пользователь обычно уже ушёл с экрана настроек — а пока запись
            // региона и подключение жили внутри `summaryView.post { }`, уход
            // отменял и то, и другое: профили выпускались, регион оставался
            // прежним, туннель оставался прежним. Снаружи это и есть «выбрал
            // Proton, а он не подключается».
            protonPendingMessage = outcome.message

            // Признак снимается здесь, в рабочем потоке, а не в посылке на вид:
            // экрана к этому моменту может уже не быть, посылка тогда не выполнится
            // вовсе — и следующий заход в настройки показал бы Proton выбранным при
            // невыбранном регионе.
            protonPreparationActive = false

            // Пользователь мог передумать, пока шёл прогон. Итог тогда не применяется:
            // профили остаются выпущенными и пригодятся при следующем выборе Proton, но
            // регион и подключение принадлежат последнему явному выбору (I1). Молчать
            // об этом нельзя — «выпустили и никуда не подключились» без объяснения
            // читается как поломка (I4).
            val stillWanted = ProtonProfileManager.isPreparationRequested()

            if (!stillWanted) {

                // Ни региона, ни переключателя не трогаем: и то и другое уже
                // принадлежит выбору, сделанному после отказа от Proton, и «вернуть
                // как было» здесь означало бы вернуть к состоянию до него.
                LogManager.log(
                    if (outcome.ready) {
                        "Proton: профили выпущены (${outcome.profiles.size} шт.), но за это время " +
                            "выбран другой транспорт — регион не меняем и не переподключаемся."
                    } else {
                        "Proton: выпуск не удался (${outcome.message}), но транспорт за это время " +
                            "выбран другой — экран не трогаем."
                    }
                )

                protonPendingMessage = null

                return@ensureProfiles

            }

            if (outcome.ready) {

                // Предпочтение записывается только теперь: до этого момента
                // «proton» означал бы пустой список.
                clientData.setExitRegionPreference("proton")

                initialExitRegionPreference = "proton"

                ProtonProfileManager.cancelPreparation()

                clientData.setProtonPreparationRequested(false)

                connectToFastestProtonProfile()

                summaryView.post {

                    if (!isFinishing && !isDestroyed) {

                        summaryView.visibility = View.VISIBLE

                        summaryView.text = outcome.message

                    }

                }

            } else {

                LogManager.log("Proton: выпуск профилей не удался — ${outcome.message}")

                // Признак снимается сразу, в рабочем потоке: посылка на вид может не
                // выполниться вовсе, и тогда переключатель остался бы на Proton
                // навсегда.
                ProtonProfileManager.cancelPreparation()

                // И признак в файле — здесь же, по той же причине.
                //
                // Дефект, который это чинит: `setProtonPreparationRequested(false)`
                // стоял только внутри `failProtonPreparation`, а её вызывала посылка
                // на вид. К концу минутного прогона экран настроек обычно уже закрыт,
                // посылка не выполняется — и файл навсегда оставался в состоянии «идёт
                // регистрация». Главный экран рисует по нему жёлтую «РЕГИСТРАЦИЯ
                // PROTON» поверх любого другого состояния, поэтому снаружи это было
                // «приложение висит на регистрации Proton и никуда не двигается»,
                // причём при полностью остановленном VPN.
                clientData.setProtonPreparationRequested(false)

                summaryView.post {

                    failProtonPreparation(
                        summaryView,
                        radioGroup,
                        buttons,
                        previousRegion,
                        outcome.message,
                    )

                }

            }

        }

        if (!started) {

            // Прогон уже идёт — а признаки «идёт регистрация» только что выставил
            // этот вызов, и снять их будет некому: обработчик итога принадлежит
            // тому, первому прогону, и наш `onFinished` не вызовут вовсе.
            //
            // Дефект, который это чинит: жёлтая «РЕГИСТРАЦИЯ PROTON» на главном
            // экране оставалась навсегда, а первичная кнопка была заперта в
            // «ОТКЛЮЧИТЬ» — то есть повторный вход в настройки при живом прогоне
            // ломал экран до перезапуска приложения.
            //
            // Признаки не снимаются, а **переподвешиваются** на идущий прогон:
            // снять их здесь значило бы отменить настоящую подготовку, которая
            // никуда не делась.
            LogManager.log("Proton: прогон уже идёт — подписываемся на его итог, второй не заводим.")

            ProtonProfileManager.addListener(object : ProtonProfileManager.StatusListener {

                override fun onStatus(text: String) {

                    if (ProtonProfileManager.isRunning()) return

                    ProtonProfileManager.removeListener(this)

                    // Идущий прогон закончился, а его собственный обработчик уже
                    // снял бы признаки сам. Снимаем только то, что могло остаться
                    // от нашего вызова, и только когда подготовка больше не идёт.
                    if (!ProtonProfileManager.isPreparationRequested()) {

                        clientData.setProtonPreparationRequested(false)

                    }

                }

            })

        }

    }

    /** Возвращает переключатель на прежний регион, не трогая хранилище. */
    private fun restoreRegionSelection(
        radioGroup: RadioGroup,
        buttons: List<RadioButton>,
        region: String,
    ) {

        // Позиция — из [ConnectionSelectorPolicy]. Здесь лежала четвёртая копия
        // порядка, позиционной картой индексов; «proton» в ней приходилось называть
        // явно, иначе возврат к уже выбранному Proton уводил переключатель в «Авто»,
        // то есть отказ выпуска молча менял транспорт (I1) — тот же случай, что G49.
        val index = ConnectionSelectorPolicy.indexOf(region)

        buttons.getOrNull(index)?.let { button ->

            radioGroup.setOnCheckedChangeListener(null)

            radioGroup.check(button.id)

            refreshConnectionSelector()

        }

    }

    /**
     * Подключение сразу после проверки.
     *
     * Живой сеанс переключается мягко, иначе поднимается новый. Согласие на VPN
     * здесь не запрашивается: диалог принадлежит главному экрану, а из настроек
     * `startActivityForResult` за ним вернулся бы в чужой поток запуска.
     */
    /**
     * Зовётся из рабочего потока по итогу выпуска, поэтому всё, что требует главного
     * потока или живого экрана, идёт через `runOnUiThread`, а служба стартует от
     * контекста приложения: к этому моменту настройки могут быть уже закрыты.
     */
    private fun connectToFastestProtonProfile() {

        val appContext = applicationContext

        if (isNovaSessionLikelyActive()) {

            runOnUiThread { maybeApplyRegionChangeImmediately("proton") }

            return

        }

        if (android.net.VpnService.prepare(appContext) != null) {

            // Согласие на VPN спрашивает главный экран: диалог принадлежит ему.
            LogManager.log("Proton: профили готовы, но согласие на VPN не выдано — ждём кнопку на главном экране.")

            runOnUiThread {

                runCatching {

                    Toast.makeText(this, "Профили готовы. Нажмите подключение на главном экране.", Toast.LENGTH_LONG).show()

                }

            }

            return

        }

        runCatching {

            ContextCompat.startForegroundService(

                appContext,

                Intent(appContext, NovaVpnService::class.java).apply {

                    action = NovaVpnService.ACTION_CONNECT_SMART

                    putExtra(NovaVpnService.EXTRA_EXIT_REGION, "proton")

                }

            )

            // «Самый быстрый» — только когда замер действительно был.
            //
            // При `alive == 0` профили лежат в порядке нагрузки узла, а не задержки
            // (`probeProtonProfilesFromServiceProcess`), и на устройстве это как раз
            // обычный случай: 0 из 50 ответивших. Строка про скорость там описывала
            // намерение, а не то, что произошло, — счётчик без замера лжёт (G11).

            val measured = runCatching {

                (ProtonProfileStore(appContext).readProbeState()?.alive ?: 0) > 0

            }.getOrDefault(false)

            LogManager.log(
                "Proton: профили готовы, запускаем подключение к " +
                    if (measured) "самому быстрому." else "наименее загруженному — замер не прошёл."
            )

            runOnUiThread {

                runCatching {

                    val text = if (measured) {

                        "Подключаемся к самому быстрому профилю Proton..."

                    } else {

                        "Подключаемся к наименее загруженному профилю Proton..."

                    }

                    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

                }

            }

        }.onFailure { error ->

            LogManager.log("Proton: не удалось начать подключение — ${error.message}")

        }

    }



    /** Перенастраивает выбор протокола по текущему состоянию службы. */
    private fun refreshConnectionSelector() {
        val title = findViewById<TextView>(R.id.tv_connection_selector_title) ?: return
        val group = findViewById<RadioGroup>(R.id.rg_exit_region) ?: return
        val summary = findViewById<TextView>(R.id.tv_exit_last) ?: return
        // Список обязан совпадать с `protocolButtons` из onCreate — включая PROTON.
        //
        // Пока здесь было пять кнопок, шестая не доставалась ни одному из двух
        // режимов: в режиме регионов `configureRegionSelector` выходил на
        // `getOrNull(5)` и переставал перенастраивать селектор вовсе, а в режиме
        // протоколов PROTON оставался видимым с чужим оформлением — на устройстве
        // это выглядело как зелёная кнопка «PROTON» в списке протоколов.
        // Порядок и состав — из [ConnectionSelectorPolicy], а не выписаны здесь.
        val buttons = ConnectionSelectorPolicy.BUTTON_IDS.mapNotNull { findViewById<RadioButton>(it) }
        if (buttons.size < ConnectionSelectorPolicy.SIZE) {
            LogManager.log(
                "Селектор протокола/региона не перенастроен: найдено ${buttons.size} " +
                    "кнопок из ${ConnectionSelectorPolicy.SIZE}."
            )
            return
        }
        configureConnectionSelector(title, group, buttons, summary)
    }

    private fun configureConnectionSelector(

        titleView: TextView,

        radioGroup: RadioGroup,

        buttons: List<RadioButton>,

        summaryView: TextView,

    ) {

        if (!clientData.isImportedConfigSourceActive()) {

            configureRegionSelector(titleView, radioGroup, buttons, summaryView)

        } else {

            configureImportedProtocolSelector(titleView, radioGroup, buttons, summaryView)

        }

    }



    /**
     * Ставит отметку на кнопку, которую велит [ConnectionSelectorPolicy].
     *
     * Обработчик снимается перед этим у вызывающего: программная простановка
     * отметки неотличима от нажатия пользователя, и без снятия она запускала бы
     * применение региона.
     */
    private fun checkSelectedRegionButton(radioGroup: RadioGroup, buttons: List<RadioButton>) {
        val index = ConnectionSelectorPolicy.selectedIndex(
            storedRegion = clientData.getExitRegionPreference(),
            // Идущая подготовка Proton сильнее записанного региона: предпочтение
            // до успеха хранит прежний транспорт, и любая перерисовка — по
            // broadcast или начавшаяся регистрация устройства — отбрасывала бы
            // кнопку назад посреди выпуска.
            protonPreparationRequested = ProtonProfileManager.isPreparationRequested(),
        )
        buttons.getOrNull(index)?.let { radioGroup.check(it.id) }
    }

    private fun configureRegionSelector(

        titleView: TextView,

        radioGroup: RadioGroup,

        buttons: List<RadioButton>,

        summaryView: TextView,

    ) {

        val rbExitAuto = buttons.getOrNull(0) ?: return

        val rbExitRu = buttons.getOrNull(1) ?: return

        val rbExitMasque = buttons.getOrNull(2) ?: return

        val rbExitOpera = buttons.getOrNull(3) ?: return

        val rbExitProton = buttons.getOrNull(4) ?: return

        val rbExitTor = buttons.getOrNull(5) ?: return

        // Название общее для обеих половин списка: WARP, MASQUE и AWG Proton — это
        // протоколы, EU и US — регионы, и «Выбор региона» половину из них не
        // описывал.
        titleView.text = "Выбор протокола/региона"

        titleView.setTextColor(Color.WHITE)

        val greenTint = ColorStateList.valueOf(Color.parseColor("#13A10E"))

        buttons.forEach { button ->

            button.visibility = View.VISIBLE

            button.isEnabled = true

            button.alpha = 1f

            button.tag = null

            button.buttonTintList = greenTint

            button.setTextColor(Color.WHITE)

        }

        // Подписи — из [ConnectionSelectorPolicy], в порядке кнопок: этот же
        // список нужен главному экрану, и вторая копия разошлась бы молча.
        buttons.forEachIndexed { index, button ->
            button.text = ConnectionSelectorPolicy.LABELS.getOrNull(index).orEmpty()
        }



        // Доступность кнопок задаётся заново на каждом заходе, а не правится
        // поверх прежней: экран перенастраивается в onResume, и «выключено»,
        // поставленное временным запретом, иначе пережило бы его причину.
        buttons.forEach {
            it.isEnabled = true
            it.alpha = 1f
        }

        // Что можно нажать — решает [ConnectionSelectorPolicy], одинаково для
        // обоих экранов. Здесь остаётся только применить решение к кнопкам.
        val availability = ConnectionSelectorPolicy.availability(
            operaSupported = OperaProxyManager.isSupportedOnDevice(this),
            deviceRegistrationInProgress = clientData.isDeviceRegistrationInProgress(),
            storedRegion = initialExitRegionPreference,
        )
        buttons.forEachIndexed { index, button ->
            val allowed = availability.enabled.getOrNull(index) ?: true
            button.isEnabled = allowed
            button.alpha = if (allowed) 1f else ConnectionSelectorPolicy.DISABLED_ALPHA
        }
        availability.rewriteStoredTo?.let { fallback ->
            clientData.setExitRegionPreference(fallback)
            initialExitRegionPreference = fallback
            Toast.makeText(this, ConnectionSelectorPolicy.OPERA_UNSUPPORTED_TOAST, Toast.LENGTH_LONG).show()
        }



        // Пока идёт регистрация устройства, выбор протокола заперт.
        //
        // Ключ MASQUE выдаётся только изнутри поднятого туннеля, и смена
        // протокола в этот момент роняет ровно тот туннель, через который его
        // выдают: регистрация начинается заново, а снаружи это выглядит как
        // «MASQUE не включается». Запрет временный и снимается сам — флаг живёт
        // в состоянии службы и обнуляется на её остановке.
        if (availability.lockReason.isNotBlank()) {
            // Кнопки уже погашены выше, из того же решения. Здесь остаётся сказать,
            // почему, и снять обработчик — иначе программная простановка отметки
            // ниже сработала бы как выбор пользователя.
            summaryView.text = availability.lockReason
            radioGroup.setOnCheckedChangeListener(null)
            checkSelectedRegionButton(radioGroup, buttons)
            return
        }

        radioGroup.setOnCheckedChangeListener(null)

        // Пока идёт подготовка Proton, кнопка держится нажатой.
        //
        // Предпочтение «proton» записывается только по успеху, а перерисовка
        // случается раньше: служба шлёт состояние подключения через долю секунды
        // после старта, приёмник зовёт `refreshConnectionSelector`, и выбор,
        // прочитанный из ещё старого предпочтения, отбрасывал кнопку назад.
        val protonPreparationVisible = ProtonProfileManager.isPreparationRequested()

        checkSelectedRegionButton(radioGroup, buttons)

        // Обработчик держим в поле: ветка TOR обязана вернуть отметку на прежнюю
        // кнопку, а `check()` неотличим от нажатия и зашёл бы сюда заново.
        regionSelectorListener = RadioGroup.OnCheckedChangeListener { _, checkedId ->

            // Значение берётся по позиции кнопки из [ConnectionSelectorPolicy], а
            // не перечислением идентификаторов: перечисление здесь и было той
            // шестой копией порядка, из-за которой новая кнопка молча уезжала в
            // «Авто» (G49). OPERA — одна кнопка на два значения службы, и какое
            // из них записать, решает запомненный подрегион.
            val chipIndex = buttons.indexOfFirst { it.id == checkedId }

            val chipValue = if (chipIndex >= 0) {
                ConnectionSelectorPolicy.valueAt(chipIndex)
            } else {
                "auto"
            }

            if (chipValue == ConnectionSelectorPolicy.CHIP_TOR) {
                // Транспорта Tor ещё нет. Записать `tor` регионом значило бы
                // отправить службу перебирать пустоту, поэтому кнопка только
                // запускает сбор мостов и говорит об этом словами (I4).
                LogManager.log("Настройки: выбран TOR — транспорта ещё нет, запускаем обновление мостов.")
                // Решение о сборе и счётчик мостов читаются в рабочем потоке: оба
                // трогают `tor_bridges.json`, а это блокирующее чтение (I13). И
                // читаются **вместе**: поле `torBridgeCount` обновляется только на
                // `onResume`, поэтому повторное нажатие после успешного сбора
                // показывало бы «мостов пока нет», хотя они только что собраны.
                lifecycleScope.launch(Dispatchers.IO) {
                    val started = TorBridgeManager.refreshInBackground(
                        this@SettingsActivity,
                        reason = "выбор TOR в настройках",
                    )
                    val known = TorBridgeManager.snapshot(this@SettingsActivity).bridges.size
                    withContext(Dispatchers.Main) {
                        if (isFinishing || isDestroyed) return@withContext
                        torBridgeCount = known
                        summaryView.visibility = View.VISIBLE
                        summaryView.text = ConnectionSelectorPolicy.torNoticeFor(started, known)
                    }
                }
                // Отметку возвращаем на прежнюю кнопку **со снятым обработчиком**.
                //
                // `RadioGroup.check` неотличим от нажатия и заходит в этот же
                // обработчик заново: со снятой отметки, потом с новой. То есть
                // нажатие на TOR второй раз выполняло бы ветку прежнего региона —
                // гасило только что написанную строку, отменяло идущий выпуск
                // Proton и могло запустить незаказанное переподключение.
                radioGroup.setOnCheckedChangeListener(null)
                checkSelectedRegionButton(radioGroup, buttons)
                radioGroup.setOnCheckedChangeListener(regionSelectorListener)
                return@OnCheckedChangeListener
            }

            val value = ConnectionSelectorPolicy.storedValueForChip(
                chipValue,
                clientData.getOperaSubRegionPreference(),
            )

            if (value == "proton") {

                // Кнопки «сгенерировать» нет: сам выбор региона и есть запуск.
                //
                // Предпочтение здесь ещё не записывается: до появления профилей
                // регион «proton» означал бы «перебирать пустой список», а служба
                // в таком режиме честно доходит до «shortlist пуст» и гаснет —
                // ровно в тот момент, когда туннель нужен, чтобы профили выпустить.
                startProtonProfilePreparation(summaryView, radioGroup, buttons)

            } else {

                // Выбран другой транспорт — прежняя причина отказа Proton больше не
                // про то, что на экране, и висеть над чужим выбором ей незачем.
                protonPreparationActive = false

                // Отмена в синглтоне, а не только здесь: опрос мог быть заведён другим
                // экземпляром экрана, а итог прогона применяется в рабочем потоке —
                // ни того, ни другого этот обработчик не достанет.
                ProtonProfileManager.cancelPreparation()

                // И признак в файле: он живёт дольше экрана и процесса. Без этой
                // строки выбор другого транспорта оставлял файл в состоянии «идёт
                // регистрация Proton» — главный экран рисовал по нему жёлтую надпись
                // поверх нового транспорта, а `resumeProtonPreparationIfPending`
                // заводил брошенный выпуск заново на каждом возврате на экран.
                clientData.setProtonPreparationRequested(false)

                // Слушатель снимается вместе с выбором. Прогон продолжается — обрывать
                // его посреди регистрации ключа незачем, — но его строки больше не
                // относятся к тому, что на экране: без этого «Proton: проверка 53/53»
                // писалось поверх строки региона уже выбранного WARP.
                detachProtonStatusListener()

                protonPendingMessage = null

                summaryView.visibility = View.GONE

                protonWaitHandler.removeCallbacksAndMessages(null)

                clientData.setExitRegionPreference(value)

                updateExitSummary(summaryView)

                // Кнопка выпуска перерисовывается **здесь**, а не только на
                // следующем проходе селектора.
                //
                // Её вид зависит от выбранного региона: для Cloudflare-режимов она
                // называется «Сгенерировать/Обновить свои профили Cloudflare», для
                // Proton — своё, для Opera её нет вовсе. Пока перерисовку делал
                // только `configureRegionSelector`, смена региона оставляла кнопку
                // в прежнем виде до ухода с экрана и возврата: выбрал WARP — кнопки
                // нет, хотя она к нему и относится.
                bindProtonRefreshButton(summaryView, visible = true)

                if (value != initialExitRegionPreference) {

                    maybeApplyRegionChangeImmediately(value)

                }

            }

        }

        radioGroup.setOnCheckedChangeListener(regionSelectorListener)

        // Строка статуса показывается только в режиме Proton. В остальных режимах
        // она скрыта в разметке — блок «Выбор региона» рассчитан на две строки, и
        // третья наезжала бы на соседнюю карточку.
        //
        // Отложенное сообщение переживает перерисовку намеренно: причина отказа
        // ставится в эту же строку, а хвост функции её безусловно прятал и
        // перезаписывал в той же посылке главного потока — до кадра дело не
        // доходило, и пользователь видел откатившуюся кнопку без единого слова.
        val pendingProtonMessage = protonPendingMessage

        // Видимость решает сама кнопка: она теперь общая для Proton и личных
        // профилей Cloudflare, и «показывать только на PROTON» прятало бы её там,
        // где она как раз и нужна.
        bindProtonRefreshButton(summaryView, visible = true)

        if (protonPreparationVisible || clientData.getExitRegionPreference() == "proton") {

            summaryView.visibility = View.VISIBLE

            summaryView.text = pendingProtonMessage
                ?: ProtonProfileManager.currentStatus().ifBlank { "Proton: профили не создавались" }

        } else if (pendingProtonMessage != null) {

            summaryView.visibility = View.VISIBLE

            summaryView.text = pendingProtonMessage

        } else {

            summaryView.visibility = View.GONE

            updateExitSummary(summaryView)

        }

    }



    /**
     * Кнопка «Обновить профили Proton»: видимость, состояние и обработчик.
     *
     * Зачем она есть. Пусковое событие у выпуска ровно одно — **смена** региона на
     * PROTON, — а `RadioGroup` о нажатии на уже выбранную кнопку не сообщает. То
     * есть повторить выпуск было буквально нечем: прогон, доехавший до конца на
     * встроенном списке узлов из прошивки (а из России `/vpn/logicals` подвисает,
     * P3), оставался таким до переустановки приложения.
     *
     * Состояние читается из синглтона, а не из поля экрана: прогон переживает и
     * поворот, и уход из настроек, и вернувшийся пользователь обязан увидеть
     * кнопку запертой, если выпуск ещё идёт.
     */
    private fun bindProtonRefreshButton(summaryView: TextView, visible: Boolean) {

        val button = findViewById<TextView>(R.id.btn_proton_refresh) ?: return

        // Вид источника решает [ProfileIssueLabels], а не место вызова: кнопка
        // одна на два источника, и подпись раньше была написана трижды.
        val kind = if (!visible) {
            ProfileIssueLabels.Kind.NONE
        } else {
            ProfileIssueLabels.kindFor(
                storedRegion = clientData.getExitRegionPreference(),
                protonPreparationRequested = ProtonProfileManager.isPreparationRequested(),
            )
        }

        if (kind == ProfileIssueLabels.Kind.NONE) {

            button.visibility = View.GONE

            button.setOnClickListener(null)

            return

        }

        button.visibility = View.VISIBLE

        val busy = when (kind) {
            ProfileIssueLabels.Kind.PROTON -> ProtonProfileManager.isRunning()
            // Личные профили Cloudflare выпускает процесс `:vpn`, и его
            // `isRunning()` из интерфейса не виден вовсе — состояние приходит
            // файлом, который опрашивает [refreshWarpGenerateState].
            else -> warpGenerateBusy
        }

        val exists = when (kind) {
            ProfileIssueLabels.Kind.PROTON -> protonProfilesExist
            else -> generatedCloudflareProfilesExist
        }

        button.isEnabled = !busy

        button.alpha = if (busy) 0.5f else 1f

        button.text = ProfileIssueLabels.label(kind, exists, busy)

        button.setOnClickListener {
            if (kind == ProfileIssueLabels.Kind.PROTON) {
                startProtonProfileRefresh(button, summaryView)
            } else {
                startWarpProfileGeneration(force = false)
            }
        }

    }

    /**
     * Есть ли уже выпущенные профили — ответ с диска, положенный в поле.
     *
     * Читать `warp_generated.json` и `proton_profiles.json` прямо в
     * [bindProtonRefreshButton] нельзя: он зовётся из приёмника состояния службы,
     * то есть на каждом кадре, и это был бы блокирующий ввод-вывод в главном
     * потоке (I13).
     */
    private var generatedCloudflareProfilesExist = false
    private var protonProfilesExist = false

    /** Обработчик группы региона — чтобы его можно было снять и вернуть. */
    private var regionSelectorListener: RadioGroup.OnCheckedChangeListener? = null

    /** Сколько мостов Tor уже собрано — для честной надписи под селектором. */
    private var torBridgeCount = 0

    /** Идёт ли выпуск личных профилей Cloudflare — по файлу состояния из `:vpn`. */
    private var warpGenerateBusy = false

    private fun refreshIssuedProfilesState() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cloudflare = runCatching {
                val snapshot = WarpGeneratedStore(this@SettingsActivity).read()
                snapshot.identity != null && snapshot.profiles.isNotEmpty()
            }.getOrDefault(false)
            val proton = runCatching {
                ProtonProfileStore(this@SettingsActivity).readProfiles().isNotEmpty()
            }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                if (cloudflare == generatedCloudflareProfilesExist && proton == protonProfilesExist) {
                    return@withContext
                }
                generatedCloudflareProfilesExist = cloudflare
                protonProfilesExist = proton
                refreshConnectionSelector()
            }
        }
    }


    /**
     * Повторный выпуск профилей Proton по явной просьбе.
     *
     * Чего он намеренно **не** делает, в отличие от [startProtonProfilePreparation]:
     *
     * - не пишет `proton_preparation.json`. Этот файл — единственный источник жёлтой
     *   «РЕГИСТРАЦИЯ PROTON» на главном экране, и он же гасит там адрес выхода и
     *   бейдж страны. Над живым сеансом это читалось бы как «связь пропала», хотя
     *   обновляется только список;
     * - не трогает регион и не переподключается. `connectToFastestProtonProfile`
     *   на живой сессии не «переставляет» её на лучший узел, а через
     *   `ACTION_REAPPLY_CURRENT_SESSION` роняет туннель и собирает заново — цена,
     *   которую пользователь не заказывал, нажимая «обновить». Новый порядок
     *   применится сам на ближайшем подключении или на «следующем профиле».
     *
     * Признак `preparationRequested` тоже не ставится: он решает, применять ли итог
     * прогона к региону и подключению, а здесь применять нечего.
     */
    private fun startProtonProfileRefresh(button: TextView, summaryView: TextView) {

        if (ProtonProfileManager.isRunning()) {

            LogManager.log("Proton: обновление не начали — выпуск уже идёт.")

            Toast.makeText(this, "Выпуск профилей Proton уже идёт", Toast.LENGTH_SHORT).show()

            return

        }

        detachProtonStatusListener()

        protonPendingMessage = null

        summaryView.visibility = View.VISIBLE

        val listener = ProtonProfileManager.StatusListener { text ->

            protonPendingMessage = text

            summaryView.post { summaryView.text = text }

        }

        protonStatusListener = listener

        ProtonProfileManager.addListener(listener)

        button.isEnabled = false

        button.alpha = 0.5f

        button.text = "Профили Proton выпускаются…"

        LogManager.log(
            "Proton: пользователь попросил обновить профили — выпускаем заново, " +
                "регион и подключение не трогаем."
        )

        val started = ProtonProfileManager.ensureProfiles(this, force = true) { outcome ->

            // Итог пишется в журнал из рабочего потока, а не из посылки на вид: к
            // концу минутного прогона экрана обычно уже нет, и посылка не выполнится
            // вовсе (I18).
            protonPendingMessage = outcome.message

            LogManager.log(
                if (outcome.ready) {
                    "Proton: обновление закончено — ${outcome.message}. " +
                        "Новый порядок применится на ближайшем подключении."
                } else {
                    "Proton: обновление не удалось — ${outcome.message}."
                }
            )

            runOnUiThread {

                if (isFinishing || isDestroyed) return@runOnUiThread

                button.isEnabled = true

                button.alpha = 1f

                button.text = ProfileIssueLabels.label(
                    ProfileIssueLabels.Kind.PROTON,
                    exists = true,
                    busy = false,
                )

                summaryView.text = outcome.message

                Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()

            }

        }

        if (!started) {

            LogManager.log("Proton: обновление не начали — выпуск уже ведёт другой процесс.")

            button.isEnabled = true

            button.alpha = 1f

            button.text = "Обновить профили Proton"

        }

    }


    /**
     * Карточка «Свои профили WARP»: кнопка выпуска и два переключателя.
     *
     * Обе настройки читаются с рабочего потока. Это не осторожность впрок: первое
     * обращение к `SharedPreferences` тянет с диска весь файл настроек, а карточка
     * собирается в `onCreate` — то есть ровно в том кадре, который пользователь
     * ждёт после нажатия «Настройки».
     *
     * Обработчики ставятся сразу, а положение приезжает позже под флагом
     * [suppressWarpGenerateSwitchCallback]: без флага начальная расстановка была бы
     * неотличима от нажатия и писала бы в журнал «пользователь включил» на каждом
     * заходе на экран.
     */
    private fun setupWarpGenerateCard() {

        val swAvoidColo = findViewById<SwitchCompat>(R.id.sw_avoid_moscow_colo) ?: return

        applyLiquidSwitchTint(swAvoidColo)

        // Переключателя «использовать свои профили» здесь больше нет: личные
        // профили используются в первую очередь всегда. Кнопка выпуска переехала
        // в карточку выбора протокола и переименовывается по состоянию
        // ([bindProtonRefreshButton]).

        swAvoidColo.setOnCheckedChangeListener { _, isChecked ->

            if (suppressWarpGenerateSwitchCallback) return@setOnCheckedChangeListener

            lifecycleScope.launch(Dispatchers.IO) {

                clientData.setAvoidedColoSwitchEnabled(isChecked)

                LogManager.log(
                    if (isChecked) {
                        "Узел Cloudflare: обход нежелательных узлов " +
                            "(${ExitColoPolicy.DEFAULT_AVOIDED.joinToString(", ")}) включён."
                    } else {
                        "Узел Cloudflare: обход нежелательных узлов выключен."
                    }
                )

            }

        }

        lifecycleScope.launch(Dispatchers.IO) {

            val avoidColo = clientData.isAvoidedColoSwitchEnabled()

            withContext(Dispatchers.Main) {

                if (isFinishing || isDestroyed) return@withContext

                suppressWarpGenerateSwitchCallback = true

                swAvoidColo.isChecked = avoidColo

                suppressWarpGenerateSwitchCallback = false

            }

        }

        refreshTorBridgeStatus()

    }

    /** Строка о мостах Tor в карточке личных профилей. */
    private fun refreshTorBridgeStatus() {
        val view = findViewById<TextView>(R.id.tv_tor_bridges_status) ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val text = TorBridgeManager.summary(this@SettingsActivity)
            val count = TorBridgeManager.snapshot(this@SettingsActivity).bridges.size
            withContext(Dispatchers.Main) {
                if (isFinishing || isDestroyed) return@withContext
                torBridgeCount = count
                view.visibility = View.VISIBLE
                view.text = text
            }
        }
    }


    /**
     * Просьба выпустить свои профили WARP.
     *
     * Работу делает процесс `:vpn`, и не для удобства: сканер точек входа открывает
     * сотни UDP-сокетов и обязан помечать их `protect()`, а `GlobalProtector` ставит
     * служба. Из процесса интерфейса те же сокеты ушли бы в туннель, который они как
     * раз и проверяют.
     *
     * @param force перерегистрировать личность, даже если она уже выдана. Обычное
     *        нажатие передаёт `false`: личность одна на все профили, и менять её
     *        ради нового списка точек входа незачем.
     */
    private fun startWarpProfileGeneration(force: Boolean) {

        // Кнопка теперь общая с Proton и живёт в карточке выбора протокола.
        val button = findViewById<TextView>(R.id.btn_proton_refresh) ?: return

        val status = findViewById<TextView>(R.id.tv_warp_generate_status)

        LogManager.log(
            "WARP-генератор: пользователь попросил выпустить свои профили " +
                "(перерегистрация личности: ${if (force) "да" else "нет"})."
        )

        val launched = runCatching {

            ContextCompat.startForegroundService(
                this,
                Intent(this, NovaVpnService::class.java).apply {
                    action = NovaVpnService.ACTION_GENERATE_WARP_PROFILES
                    putExtra(NovaVpnService.EXTRA_WARP_GENERATE_FORCE, force)
                }
            )

        }

        // Отказ здесь молчать не должен: с фоновым запуском службы система отказывает
        // по своим правилам, и запертая кнопка без единого слова была бы неотличима
        // от начавшегося прогона (I4).
        launched.onFailure { error ->

            LogManager.log("WARP-генератор: службу не удалось разбудить — ${error.message}")

            Toast.makeText(this, "Не удалось начать выпуск профилей WARP", Toast.LENGTH_LONG).show()

            return

        }

        warpGenerateRequestedAtMs = System.currentTimeMillis()

        warpGenerateBusy = true

        button.isEnabled = false

        button.alpha = 0.5f

        button.text = ProfileIssueLabels.label(
            ProfileIssueLabels.Kind.CLOUDFLARE,
            exists = generatedCloudflareProfilesExist,
            busy = true,
        )

        status?.visibility = View.VISIBLE

        status?.text = "WARP: запускаю выпуск"

        startWarpGeneratePolling()

    }


    private fun startWarpGeneratePolling() {

        warpGeneratePolling = true

        warpGenerateHandler.removeCallbacks(warpGenerateRefreshRunnable)

        warpGenerateHandler.post(warpGenerateRefreshRunnable)

    }


    private fun stopWarpGeneratePolling() {

        warpGeneratePolling = false

        warpGenerateHandler.removeCallbacks(warpGenerateRefreshRunnable)

    }


    /**
     * Один шаг опроса: прочитать состояние прогона и перерисовать карточку.
     *
     * Чтение файла — с рабочего потока: он лежит в `filesDir`, его пишет чужой
     * процесс, и читать его каждые полторы секунды на главном потоке значило бы
     * ставить кадры экрана в зависимость от чужой записи.
     *
     * Опрос гаснет сам, как только состояние перестало быть `running`: `done`,
     * `failed` и `idle` дальше не меняются, и продолжать читать файл ради того же
     * ответа незачем.
     */
    private fun refreshWarpGenerateState() {

        val button = findViewById<TextView>(R.id.btn_proton_refresh) ?: return

        val status = findViewById<TextView>(R.id.tv_warp_generate_status) ?: return

        lifecycleScope.launch(Dispatchers.IO) {

            val progress = WarpProfileGenerator.readProgress(this@SettingsActivity)

            val exists = runCatching {
                val snapshot = WarpGeneratedStore(this@SettingsActivity).read()
                snapshot.identity != null && snapshot.profiles.isNotEmpty()
            }.getOrDefault(generatedCloudflareProfilesExist)

            withContext(Dispatchers.Main) {

                if (isFinishing || isDestroyed) return@withContext

                generatedCloudflareProfilesExist = exists

                val running = progress.state == WarpProfileGenerator.STATE_RUNNING

                if (running) warpGenerateRequestedAtMs = 0L

                val awaitingStart = !running &&
                    warpGenerateRequestedAtMs != 0L &&
                    System.currentTimeMillis() - warpGenerateRequestedAtMs < 20_000L

                if (!running && !awaitingStart) warpGenerateRequestedAtMs = 0L

                val busy = running || awaitingStart

                warpGenerateBusy = busy

                // Кнопка общая с Proton: пока выбран Proton, её текстом
                // распоряжается его ветка, и переписывать здесь значило бы драться
                // за один вид двумя писателями.
                val kind = ProfileIssueLabels.kindFor(
                    storedRegion = clientData.getExitRegionPreference(),
                    protonPreparationRequested = ProtonProfileManager.isPreparationRequested(),
                )

                if (kind == ProfileIssueLabels.Kind.CLOUDFLARE) {

                    button.isEnabled = !busy

                    button.alpha = if (busy) 0.5f else 1f

                    button.text = ProfileIssueLabels.label(kind, exists, busy)

                }

                // Пока прогон о себе не заявил, показываем своё слово, а не итог
                // прошлого: «выпущено 50 профилей» сразу под только что нажатой
                // кнопкой читается как мгновенный успех.
                val message = if (awaitingStart) "WARP: запускаю выпуск" else progress.message

                if (message.isBlank()) {

                    status.visibility = View.GONE

                } else {

                    status.visibility = View.VISIBLE

                    status.text = message

                }

                if (!busy || !warpGeneratePolling) {

                    stopWarpGeneratePolling()

                    return@withContext

                }

                warpGenerateHandler.removeCallbacks(warpGenerateRefreshRunnable)

                warpGenerateHandler.postDelayed(warpGenerateRefreshRunnable, 1500L)

            }

        }

    }


    private fun configureImportedProtocolSelector(

        titleView: TextView,

        radioGroup: RadioGroup,

        buttons: List<RadioButton>,

        summaryView: TextView,

    ) {

        // В режиме импортированных профилей регион не выбирают, и кнопка Proton
        // здесь не к чему относиться: без этого она осталась бы от прошлой отрисовки.
        bindProtonRefreshButton(summaryView, visible = false)

        titleView.text = "Выбор протокола"

        titleView.setTextColor(Color.parseColor("#F3C94A"))

        val options = buildList {

            add("auto")

            addAll(clientData.getAvailableImportedProtocolFamilies())

        }.distinct()

        val currentPreference = clientData.getImportedProtocolPreference()

        // Отсутствующую сейчас семью показываем как «AUTO», но в хранилище не пишем.
        //
        // Список семей собирается из уже загруженных конфигураций, и на первых кадрах
        // после запуска он бывает неполным. Пока экран записывал сюда откат, выбранный
        // пользователем протокол молча превращался в «AUTO» просто от захода в
        // настройки — а «AUTO» при нескольких семьях означает уже другой перебор.
        // Решение, что делать с недоступной семьёй, принимает
        // [ClientData.resolveEffectiveImportedProtocol] в момент подключения.
        val effectivePreference = currentPreference.takeIf { it in options } ?: "auto"

        radioGroup.setOnCheckedChangeListener(null)

        val yellowTint = ColorStateList.valueOf(Color.parseColor("#F3C94A"))

        buttons.forEachIndexed { index, button ->

            val value = options.getOrNull(index)

            if (value == null) {

                button.visibility = View.GONE

                button.isEnabled = false

                button.tag = null

                return@forEachIndexed

            }

            button.visibility = View.VISIBLE

            button.isEnabled = true

            button.alpha = 1f

            button.tag = value

            button.text = clientData.formatImportedProtocolDisplay(value)

            button.buttonTintList = yellowTint

            button.setTextColor(Color.WHITE)

        }

        val selectedButton = buttons.firstOrNull { it.tag == effectivePreference }

            ?: buttons.firstOrNull()

        if (selectedButton != null) {

            radioGroup.check(selectedButton.id)

        } else {

            radioGroup.clearCheck()

        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->

            val selectedValue = buttons.firstOrNull { it.id == checkedId }?.tag as? String ?: "auto"

            clientData.setImportedProtocolPreference(selectedValue)

            updateProtocolSummary(summaryView)

            if (selectedValue != initialImportedProtocolPreference) {

                maybeApplyProtocolChangeImmediately(selectedValue)

            }

        }

    }



    private fun updateWarpConfigsSummary(textView: TextView) {
        textView.text = "Загрузка..."
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                val configs = clientData.getWarpVerifiedConfigs()
                val builtInCount = configs.count(clientData::isBundledSeed)
                val importedCount = configs.count { it.userImported && !it.manual }
                val manualCount = configs.count { it.manual }
                when {
                    builtInCount > 0 || importedCount > 0 || manualCount > 0 -> {
                        buildString {
                            append("Встроенных: ")
                            append(builtInCount)
                            if (importedCount > 0) {
                                append(" • импортировано: ")
                                append(importedCount)
                            }
                            if (manualCount > 0) {
                                append(" • вручную: ")
                                append(manualCount)
                            }
                        }
                    }
                    else -> "Открыть список встроенных, импортированных и ручных конфигураций"
                }
            }
            textView.text = summary
        }
        return

        val count = clientData.getWarpVerifiedConfigs().size

        val importedCount = clientData.getWarpVerifiedConfigs().count { it.userImported && !it.manual }

        textView.text = if (count > 0) {

            if (importedCount > 0) {

                "Сохранено конфигураций: $count • импортировано: $importedCount"

            } else {

                "Сохранено конфигураций: $count"

            }

        } else {

            "Открыть список встроенных, импортированных и ручных конфигураций"

        }

    }



    private fun updateLocalProxySummary(textView: TextView) {

        val snapshot = clientData.getLocalProxyStatusSnapshot()

        val enabled = clientData.isLocalProxyEnabled()

        val backend = snapshot?.backend?.ifBlank { clientData.getServiceBackend() } ?: clientData.getServiceBackend()

        val shared = snapshot?.endpoints.orEmpty().count { it.downstream }

        textView.text = when {

            snapshot?.running == true && enabled && shared > 0 ->

                "Раздача активна, выход в ${formatLocalProxyBackend(backend)}"

            snapshot?.running == true && enabled ->

                "Прокси активен, выход в ${formatLocalProxyBackend(backend)}"

            enabled ->

                "Прокси включён, ждёт живой VPN"

            else ->

                "Пустить в VPN устройства, подключённые к телефону"

        }

    }



    private fun formatLocalProxyBackend(backend: String): String {

        val normalized = backend.trim().uppercase()

        return when {

            normalized.startsWith("${NovaVpnService.BACKEND_OPERA}-") ->

                normalized.substringAfter('-').ifBlank { NovaVpnService.BACKEND_OPERA }

            normalized.startsWith(NovaVpnService.BACKEND_OPERA) ->

                NovaVpnService.BACKEND_OPERA

            else ->

                NovaVpnService.BACKEND_WARP

        }

    }



    /**
     * Открывает страницу загрузки на сайте.
     *
     * Раньше здесь был `ACTION_SEND` — «поделиться ссылкой». Владелец попросил
     * вести прямо на сайт: кнопка называется «Скачать последнюю версию», и
     * выбор мессенджера вместо страницы был бы обещанием не той работы.
     *
     * Отсутствие браузера обрабатывается вслух (I4): молчаливый `return` здесь
     * неотличим от «нажатие не сработало».
     */
    private fun openDownloadPage() {

        val link = "https://nova-app.eu/download/#nova-android"

        try {

            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).apply {

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            })

        } catch (e: Exception) {

            LogManager.log("Настройки: страницу загрузки открыть не удалось — ${e.message}")

            Toast.makeText(this, "Не удалось открыть $link", Toast.LENGTH_LONG).show()

        }

    }



    private fun runManualUpdateCheck() {

        updateManualUpdateUi(forceChecking = true)

        scope.launch {

            val result = withContext(Dispatchers.IO) {

                AppUpdateManager.performManualUpdateCheck(this@SettingsActivity)

            }

            updateManualUpdateUi()

            val message = when (result.kind) {

                ManualUpdateCheckResult.Kind.CHECKING -> "Проверка уже выполняется"

                ManualUpdateCheckResult.Kind.NO_UPDATE -> result.message.ifBlank { "Установлена последняя версия" }

                ManualUpdateCheckResult.Kind.DOWNLOAD_STARTED -> "Начали загрузку ${result.version}"

                ManualUpdateCheckResult.Kind.DOWNLOAD_IN_PROGRESS -> "Загрузка ${result.version} уже идёт"

                ManualUpdateCheckResult.Kind.READY -> "Обновление ${result.version} уже скачано"

                ManualUpdateCheckResult.Kind.FAILED -> result.message.ifBlank { "Не удалось проверить обновление" }

            }

            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_SHORT).show()

        }

    }



    private fun updateManualUpdateUi(forceChecking: Boolean = false) {

        val readyVersion = if (!forceChecking) AppUpdateManager.getReadyDownloadedVersion(this) else ""

        val progress = if (forceChecking) {

            UpdateDownloadProgress(

                state = UpdateDownloadProgress.State.CHECKING,

                version = clientData.getLastUpdateVersion(),

                progressPercent = 0,

                downloadedBytes = 0L,

                totalBytes = 0L,

                statusLabel = "Проверяем наличие новой версии...",

            )

        } else if (readyVersion.isNotBlank()) {

            UpdateDownloadProgress(

                state = UpdateDownloadProgress.State.READY,

                version = readyVersion,

                progressPercent = 100,

                downloadedBytes = 0L,

                totalBytes = 0L,

                statusLabel = "Скачано обновление $readyVersion",

            )

        } else {

            AppUpdateManager.getDownloadProgress(this)

        }



        // Нажимать нечего, пока идёт то, что нажатие и запустило бы: проверка,
        // загрузка или установка. Ровно тем и глушатся повторные нажатия.
        rowManualUpdateCheck.isEnabled = !progress.isBusy

        rowManualUpdateCheck.alpha = if (rowManualUpdateCheck.isEnabled) 1f else 0.8f

        rowManualUpdateCheck.setTextColor(

            when (progress.state) {

                UpdateDownloadProgress.State.READY -> android.graphics.Color.parseColor("#13A10E")

                else -> android.graphics.Color.WHITE

            }

        )

        rowManualUpdateCheck.text = when (progress.state) {

            UpdateDownloadProgress.State.CHECKING -> "Проверяем обновления..."

            UpdateDownloadProgress.State.INSTALLING -> "Устанавливаем обновление..."

            UpdateDownloadProgress.State.READY -> "Обновить приложение"

            UpdateDownloadProgress.State.DOWNLOADING,

            UpdateDownloadProgress.State.PAUSED,

            UpdateDownloadProgress.State.FAILED,

            UpdateDownloadProgress.State.IDLE -> "Проверить обновления"

        }



        when (progress.state) {

            UpdateDownloadProgress.State.DOWNLOADING,

            UpdateDownloadProgress.State.PAUSED,

            UpdateDownloadProgress.State.INSTALLING,

            UpdateDownloadProgress.State.CHECKING -> {

                pbManualUpdate.visibility = android.view.View.VISIBLE

                pbManualUpdate.isIndeterminate = progress.isIndeterminate

                if (!progress.isIndeterminate) {

                    pbManualUpdate.progress = progress.progressPercent.coerceIn(0, 100)

                }

                tvManualUpdateStatus.visibility = android.view.View.VISIBLE

                tvManualUpdateStatus.text = progress.statusLabel

            }

            UpdateDownloadProgress.State.READY -> {

                pbManualUpdate.visibility = android.view.View.GONE

                tvManualUpdateStatus.visibility = android.view.View.VISIBLE

                tvManualUpdateStatus.text = progress.statusLabel

            }

            UpdateDownloadProgress.State.FAILED -> {

                pbManualUpdate.visibility = android.view.View.GONE

                tvManualUpdateStatus.visibility = android.view.View.VISIBLE

                tvManualUpdateStatus.text = progress.statusLabel

            }

            UpdateDownloadProgress.State.IDLE -> {

                pbManualUpdate.visibility = android.view.View.GONE

                tvManualUpdateStatus.visibility = android.view.View.GONE

            }

        }

    }



    private fun updateTrafficMaskUi(

        switch: Switch,

        modeGroup: RadioGroup,

        hostField: EditText,

        statusView: TextView,

    ) {

        val enabled = switch.isChecked

        val mode = clientData.getTrafficMaskMode()

        val isCustom = enabled && mode == "custom"

        modeGroup.isEnabled = enabled

        for (i in 0 until modeGroup.childCount) {

            modeGroup.getChildAt(i).isEnabled = enabled

            modeGroup.getChildAt(i).alpha = if (enabled) 1f else 0.55f

        }

        hostField.isEnabled = isCustom

        hostField.visibility = if (isCustom) android.view.View.VISIBLE else android.view.View.GONE

        hostField.alpha = if (isCustom) 1.0f else 0.55f



        val serviceState = clientData.getServiceState()

        val tunnelSnapshot = clientData.getTunnelUiSnapshot()

        val backend = if (serviceState != NovaVpnService.STATE_STOPPED) {

            (tunnelSnapshot?.backend).orEmpty().ifBlank { clientData.getServiceBackend() }

        } else {

            ""

        }.trim().uppercase()

        val regionPreference = clientData.getExitRegionPreference()

        val isOperaBackend = serviceState != NovaVpnService.STATE_STOPPED && backend.startsWith(NovaVpnService.BACKEND_OPERA)

        val activePool = clientData.getTrafficMaskActivePool()

        val activeGlobalHost = clientData.getTrafficMaskActiveHost()

            .takeIf { activePool == ClientData.TRAFFIC_MASK_POOL_GLOBAL }

            .orEmpty()

        val activeRussiaHost = clientData.getWarpTrafficMaskActiveHost()

            .ifBlank {

                clientData.getTrafficMaskActiveHost()

                    .takeIf { activePool == ClientData.TRAFFIC_MASK_POOL_RUSSIA }

                    .orEmpty()

            }

        val recentProbeHost = clientData.getTrafficMaskRecentProbeHost()

        val recentProbePool = clientData.getTrafficMaskRecentProbePool()

        val broadcastHost = latestMaskHostFromBroadcast

        val broadcastPool = latestMaskPoolFromBroadcast

        val effectivePool = when {

            mode == "custom" -> ClientData.TRAFFIC_MASK_POOL_CUSTOM

            serviceState == NovaVpnService.STATE_CONNECTING && activePool.isNotBlank() -> activePool

            isOperaBackend -> ClientData.TRAFFIC_MASK_POOL_GLOBAL

            regionPreference == "eu" || regionPreference == "us" -> ClientData.TRAFFIC_MASK_POOL_GLOBAL

            else -> ClientData.TRAFFIC_MASK_POOL_RUSSIA

        }

        val currentHost = when (effectivePool) {

            ClientData.TRAFFIC_MASK_POOL_GLOBAL -> activeGlobalHost

            ClientData.TRAFFIC_MASK_POOL_RUSSIA -> activeRussiaHost

            ClientData.TRAFFIC_MASK_POOL_CUSTOM -> clientData.getTrafficMaskHost()

            else -> ""

        }

        val currentProbeHost = when {

            broadcastHost.isNotBlank() &&

                System.currentTimeMillis() - latestMaskBroadcastAtMs <= 90_000L &&

                (

                    broadcastPool.isBlank() ||

                        broadcastPool == effectivePool ||

                        latestMaskStateFromBroadcast == NovaVpnService.STATE_CONNECTING

                    ) -> broadcastHost

            currentHost.isNotBlank() -> currentHost

            recentProbeHost.isNotBlank() &&

                (

                    recentProbePool.isBlank() ||

                        recentProbePool == effectivePool ||

                        serviceState == NovaVpnService.STATE_CONNECTING ||

                        clientData.isTransientConnectingPending() ||

                        clientData.isSoftReapplyPending()

                    ) -> recentProbeHost

            else -> ""

        }

        val fallbackHost = when (effectivePool) {

            ClientData.TRAFFIC_MASK_POOL_GLOBAL ->

                clientData.getTrafficMaskLastSuccessfulHostForPool(ClientData.TRAFFIC_MASK_POOL_GLOBAL)

            ClientData.TRAFFIC_MASK_POOL_RUSSIA ->

                clientData.getWarpTrafficMaskLastSuccessfulHost()

                    .ifBlank {

                        clientData.getTrafficMaskLastSuccessfulHostForPool(ClientData.TRAFFIC_MASK_POOL_RUSSIA)

                    }

            ClientData.TRAFFIC_MASK_POOL_CUSTOM -> clientData.getTrafficMaskHost()

            else -> clientData.getTrafficMaskLastSuccessfulHost()

        }

        val recentStateAgeMs = (System.currentTimeMillis() - clientData.getServiceStateUpdatedAt()).coerceAtLeast(0L)

        val connectingLikeState =

            serviceState == NovaVpnService.STATE_CONNECTING ||

                clientData.isTransientConnectingPending() ||

                clientData.isSoftReapplyPending() ||

                (

                    currentProbeHost.isNotBlank() &&

                        serviceState != NovaVpnService.STATE_CONNECTED &&

                        recentStateAgeMs <= 90_000L

                    )

        if (enabled && mode == "auto" && connectingLikeState && currentProbeHost.isNotBlank()) {

            statusView.text = "Пробуем домен: $currentProbeHost"

            statusView.visibility = android.view.View.VISIBLE

        } else if (enabled && mode == "auto" && connectingLikeState) {

            statusView.text = "Пробуем домен: AUTO"

            statusView.visibility = android.view.View.VISIBLE

        } else if (enabled && mode == "auto") {

            statusView.text = "Удачный домен: ${fallbackHost.ifBlank { "AUTO" }}"

            statusView.visibility = android.view.View.VISIBLE

        } else {

            statusView.visibility = android.view.View.GONE

        }

    }

    

    private fun showQSTileTutorial() {

        android.app.AlertDialog.Builder(this)

            .setTitle("Плитка быстрых настроек")

            .setMessage("Как добавить плитку Nova в шторку:\n\n" +

                    "1. Потяните шторку сверху вниз\n" +

                    "2. Нажмите значок ✏️ (редактировать)\n" +

                    "3. Найдите плитку \"Nova\"\n" +

                    "4. Перетащите её в верхнюю часть")

            .setPositiveButton("Понятно", null)

            .show()

    }


    /**
     * Подпись под строкой лицензии: что сейчас с аккаунтом.
     *
     * Тип аккаунта показываем тот, который вернул сервер при последней привязке, а не наш
     * вывод из наличия ключа: ключ может быть введён и не принят, и по строке «задана» это
     * было бы не отличить.
     */
    private fun updateWarpLicenseNote() {
        val license = clientData.getWarpPlusLicense()
        val accountType = clientData.getWarpAccountType()
        val lastError = clientData.getWarpLicenseLastError()
        tvWarpLicenseNote.text = when {
            license.isBlank() -> "Не задана — аккаунт бесплатный"
            // Отказ показываем на экране, а не только всплывающим сообщением: три
            // секунды жизни и никакого следа — это неотличимо от «ещё не проверен».
            lastError.isNotBlank() -> "Ключ не принят: $lastError"
            accountType.isBlank() -> "Ключ сохранён, аккаунт ещё не проверен"
            accountType.equals("free", ignoreCase = true) ->
                "Ключ сохранён, но аккаунт остался бесплатным"
            else -> "Аккаунт: $accountType"
        }
    }

    /**
     * Ввод лицензии WARP+.
     *
     * Для подключения ключ **не нужен**: бесплатный анонимный аккаунт
     * (`account_type: "free"`) Cloudflare обслуживает, MASQUE на нём поднимается
     * (N9, замер 2026-08-12 в `register.go`). Прежний текст диалога утверждал
     * обратное — «без ключа MASQUE не подключается» — и это давно неправда.
     * Ключ меняет тип аккаунта и добавляет ускоренные маршруты Cloudflare.
     *
     * Хранится отдельно от личности: личность приложение перевыпускает само при
     * отказе Cloudflare, а ключ вводят руками, и терять его при каждом перевыпуске
     * нельзя — `WarpIdentityBackfill` переносит его на каждое новое устройство.
     */
    private fun showWarpLicenseDialog() {
        val input = EditText(this).apply {
            setText(clientData.getWarpPlusLicense())
            hint = "xxxxxxxx-xxxxxxxx-xxxxxxxx"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8899A0"))
            setPadding(48, 32, 48, 32)
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("Лицензия WARP+")
            .setMessage(
                "Ключ из приложения Cloudflare 1.1.1.1 (Настройки → Аккаунт → Ключ).\n\n" +
                    "Для подключения он не нужен — Nova работает и на бесплатном аккаунте. " +
                    "Ключ даёт ускоренные маршруты Cloudflare."
            )
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                applyWarpLicense(input.text?.toString().orEmpty())
            }
            .setNeutralButton("Убрать") { _, _ ->
                clientData.setWarpPlusLicense("")
                clientData.setWarpAccountType("")
                updateWarpLicenseNote()
                Toast.makeText(this, "Лицензия убрана", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Сжимает ответ Cloudflare до того, что помещается в подпись под строкой.
     *
     * Целиком это `400 Bad Request: {"result":null,...,"message":"Too many connected
     * devices."}` — в две строки по 12sp не влезает и читается как мусор. Из тела
     * достаём `message`, потому что именно он объясняет отказ: у ключей из публичных
     * каналов лимит устройств исчерпан, и это ответ про ключ, а не про Nova.
     */
    private fun shortenLicenseError(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Cloudflare отклонил запрос"
        Regex(""""message"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return text.substringBefore('{').trim().ifEmpty { text }.take(80)
    }

    private fun applyWarpLicense(rawLicense: String) {
        val license = rawLicense.trim()
        clientData.setWarpPlusLicense(license)
        clientData.setWarpAccountType("")
        clientData.setWarpLicenseLastError("")
        updateWarpLicenseNote()
        if (license.isEmpty()) return

        // Личность MASQUE — полноценный запасной адресат.
        //
        // Обычной регистрации WARP на устройстве может не быть вовсе: встроенные семена
        // идут со своими ключами, а фоновая регистрация выключена, как только личность
        // MASQUE готова. В такой связке ключу было некуда привязаться никогда, а экран
        // обещал «применится при регистрации» — обещание, которое некому исполнить.
        val plainToken = clientData.getAccessToken().orEmpty()
        val plainDeviceId = clientData.getDeviceId().orEmpty()
        val credentials = if (plainToken.isNotBlank() && plainDeviceId.isNotBlank()) {
            plainToken to plainDeviceId
        } else {
            clientData.getMasqueIdentityCredentials()
        }
        if (credentials == null) {
            // Устройства ещё нет — ключ применится при первой регистрации.
            LogManager.log(
                "Лицензия WARP+: ключ сохранён, но привязать не к чему — нет ни регистрации " +
                    "WARP, ни личности MASQUE. Применится, когда появится первая."
            )
            Toast.makeText(
                this,
                "Ключ сохранён. Он применится, когда Nova зарегистрирует устройство.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val (token, deviceId) = credentials

        // Итог привязки идёт и в журнал, а не только всплывающим сообщением.
        // Всплывающее живёт три секунды и в диагностику не попадает, поэтому
        // «ключ сохранён, аккаунт ещё не проверен» на экране было неотличимо от
        // отказа Cloudflare, отказа сети и неверного ключа (I4).
        LogManager.log("Лицензия WARP+: привязываем ключ к устройству $deviceId.")
        Toast.makeText(this, "Привязываем лицензию…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { nova.Nova.setWarpLicense(token, deviceId, license) }
            runOnUiThread {
                result.onSuccess { accountType ->
                    clientData.setWarpAccountType(accountType)
                    updateWarpLicenseNote()
                    LogManager.log(
                        if (accountType.isBlank()) {
                            "Лицензия WARP+: Cloudflare принял запрос, но тип аккаунта не вернул."
                        } else {
                            "Лицензия WARP+: аккаунт стал «$accountType»."
                        }
                    )
                    Toast.makeText(
                        this,
                        when {
                            accountType.equals("free", ignoreCase = true) ->
                                "Cloudflare оставил аккаунт бесплатным — ключ не принят"
                            // Пустой тип — не успех: «Лицензия принята, аккаунт: »
                            // сообщало бы о победе пустым местом.
                            accountType.isBlank() ->
                                "Cloudflare не назвал тип аккаунта — считаем ключ непринятым"
                            else -> "Лицензия принята, аккаунт: $accountType"
                        },
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure { error ->
                    LogManager.log("Лицензия WARP+: привязать не удалось — ${error.message}")
                    clientData.setWarpLicenseLastError(shortenLicenseError(error.message))
                    updateWarpLicenseNote()
                    Toast.makeText(
                        this,
                        "Привязать лицензию не удалось: ${error.message}",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.apply {
            name = "NovaWarpLicense"
            isDaemon = true
            start()
        }
    }

}


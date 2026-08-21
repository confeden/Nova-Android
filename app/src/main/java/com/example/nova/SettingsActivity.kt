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

import androidx.core.content.ContextCompat

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

    

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)



    // State Triggers

    private var initialSplitMode: Int = 0

    private var initialSplitApps: Set<String> = emptySet()

    private var initialAutoReconnect: Boolean = true

    private var initialAutoAppUpdate: Boolean = true

    private var initialQuickTileAdded: Boolean = false

    private var initialExitRegionPreference: String = "auto"

    private var initialImportedProtocolPreference: String = "auto"

    private var initialTrafficMaskEnabled: Boolean = false

    private var initialTrafficMaskMode: String = "auto"

    private var initialTrafficMaskHost: String = ""

    private var splitReapplyHandledInPlace = false

    private var regionReapplyHandledInPlace = false

    private var protocolReapplyHandledInPlace = false

    @Volatile

    private var controlledReapplyPending = false

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

        val rbExitEu = findViewById<RadioButton>(R.id.rb_exit_eu)

        val rbExitUs = findViewById<RadioButton>(R.id.rb_exit_us)

        val rbExitMasque = findViewById<RadioButton>(R.id.rb_exit_masque)

        val protocolButtons = listOf(rbExitAuto, rbExitRu, rbExitMasque, rbExitEu, rbExitUs)

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

            shareReleaseLink()

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

            rbExitEu,

            rbExitUs,

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

        rowWarpConfigs.setOnClickListener {

            startActivity(Intent(this, WarpConfigsActivity::class.java))

        }

        rowMainBackground.setOnClickListener {

            startActivity(Intent(this, BackgroundSettingsActivity::class.java))

        }

        rowDnsSettings.setOnClickListener {

            startActivity(Intent(this, DnsSettingsActivity::class.java))

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

        if (initialSplitMode != 0) {

            val cachedApps = AppCacheManager.peekInstalledApps(this, initialSplitApps)

            if (cachedApps.isNotEmpty()) {

                allApps = cachedApps

                submitFilteredApps()

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

            allApps = AppCacheManager.getInstalledApps(this@SettingsActivity, savedSelection)

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



    private fun submitFilteredApps(query: String = etSearch.text?.toString().orEmpty()) {

        if (!::adapter.isInitialized) return

        val lowered = query.lowercase()

        val filtered = if (lowered.isBlank()) {

            allApps

        } else {

            allApps.filter { it.label.lowercase().contains(lowered) || it.packageName.lowercase().contains(lowered) }

        }.sortedWith(compareByDescending<AppItem> { it.isSelected }.thenBy { it.label.lowercase() })

        adapter.setData(filtered)

    }

    

    // Dirty Check Logic

    override fun onPause() {

        super.onPause()

        uiRefreshHandler.removeCallbacks(trafficMaskRefreshRunnable)

        uiRefreshHandler.removeCallbacks(manualUpdateRefreshRunnable)

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

            listOf(

                findViewById(R.id.rb_exit_auto),

                findViewById(R.id.rb_exit_ru),

                findViewById(R.id.rb_exit_masque),

                findViewById(R.id.rb_exit_eu),

                findViewById(R.id.rb_exit_us),

            ),

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

        unregisterPackageChangesReceiver()

        unregisterVpnStateReceiver()

        appLoadJob?.cancel()

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

        val desiredBackend = resolveDesiredBackendForCurrentPreferences()

        clientData.saveServiceState(

            NovaVpnService.STATE_CONNECTING,

            desiredBackend,

        )

        clientData.markSoftReapplyPending()

        runCatching {

            ContextCompat.startForegroundService(this, buildReapplyIntent(this))

            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()

        }.onFailure { error ->

            clientData.clearSoftReapplyPending()

            LogManager.log("Не удалось мягко применить изменения VPN: ${error.message}")

        }

    }



    private fun launchControlledOperaReapply(toastMessage: String) {

        if (controlledReapplyPending) {

            LogManager.log("Мягкий Opera restart уже запланирован. Просто обновили настройки и дождёмся нового запуска.")

            return

        }

        controlledReapplyPending = true

        val desiredBackend = resolveDesiredBackendForCurrentPreferences()

        clientData.saveServiceState(

            NovaVpnService.STATE_CONNECTING,

            desiredBackend,

        )

        clientData.markSoftReapplyPending(35000L)

        val appContext = applicationContext

        runCatching {

            LogManager.log("Активный Opera-сеанс меняем через безопасный stop-then-start из основного процесса.")

            ContextCompat.startForegroundService(

                this,

                Intent(this, NovaVpnService::class.java).apply {

                    action = NovaVpnService.ACTION_STOP_FOR_SOFT_RESTART

                }

            )

            Toast.makeText(this, toastMessage, Toast.LENGTH_SHORT).show()

            scheduleControlledOperaRestartPoll(appContext, 0)

        }.onFailure { error ->

            controlledReapplyPending = false

            clientData.clearSoftReapplyPending()

            LogManager.log("Не удалось запустить безопасный Opera restart: ${error.message}")

        }

    }



    private fun scheduleControlledOperaRestartPoll(appContext: Context, attempt: Int) {

        reapplyHandler.postDelayed({

            val serviceStopped = clientData.getServiceState() == NovaVpnService.STATE_STOPPED

            val novaVpnStillVisible = hasActiveNovaSystemVpn(appContext)

            if ((serviceStopped && !novaVpnStillVisible) || attempt >= 28) {

                scheduleControlledOperaRestartLaunch(appContext)

            } else {

                scheduleControlledOperaRestartPoll(appContext, attempt + 1)

            }

        }, if (attempt == 0) 220L else 160L)

    }



    private fun scheduleControlledOperaRestartLaunch(appContext: Context) {

        reapplyHandler.postDelayed({

            controlledReapplyPending = false

            clientData.markSoftReapplyPending(25000L)

            runCatching {

                LogManager.log("Старый Opera VPN полностью остановлен. Запускаем новый connect-сеанс в чистом процессе.")

                ContextCompat.startForegroundService(appContext, buildReapplyIntent(appContext))

            }.onFailure { error ->

                clientData.clearSoftReapplyPending()

                LogManager.log("Не удалось заново запустить VPN после безопасного Opera restart: ${error.message}")

            }

        }, 800L)

    }



    private fun buildReapplyIntent(context: Context): Intent {

        return Intent(context, NovaVpnService::class.java).apply {

            action = NovaVpnService.ACTION_REAPPLY_CURRENT_SESSION

            putExtra(NovaVpnService.EXTRA_EXIT_REGION, clientData.getExitRegionPreference())

            putExtra(

                NovaVpnService.EXTRA_IMPORTED_CONFIG_SOURCE_ENABLED,

                clientData.isImportedWarpOnlyModeEnabled()

            )

            putExtra(

                NovaVpnService.EXTRA_IMPORTED_PROTOCOL_PREFERENCE,

                clientData.getImportedProtocolPreference()

            )

            putExtra(NovaVpnService.EXTRA_REAPPLY_SPLIT_MODE, clientData.getSplitMode())

            putStringArrayListExtra(

                NovaVpnService.EXTRA_REAPPLY_SPLIT_APPS,

                ArrayList(clientData.getSplitApps())

            )

            putExtra(NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_ENABLED, clientData.getTrafficMaskEnabled())

            putExtra(NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_MODE, clientData.getTrafficMaskMode())

            putExtra(NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_HOST, clientData.getTrafficMaskHost())

        }

    }



    private fun shouldUseControlledOperaRestartReapply(): Boolean {

        val persistedBackend = clientData.getServiceBackend().trim().uppercase()

        if (persistedBackend.startsWith(NovaVpnService.BACKEND_OPERA)) return true



        val restartKind = clientData.getRestartSession()

            ?.kind

            ?.trim()

            ?.uppercase()

            .orEmpty()

        if (restartKind == "OPERA") return true



        val cm = getSystemService(ConnectivityManager::class.java)

        val activeOperaVpn = cm?.allNetworks?.any { network ->

            val caps = cm.getNetworkCapabilities(network) ?: return@any false

            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false

            extractVpnTransportLabel(caps)

                .orEmpty()

                .contains("NovaOperaVPN", ignoreCase = true)

        } == true

        if (activeOperaVpn) return true



        return resolveDesiredBackendForCurrentPreferences()

            .trim()

            .uppercase()

            .startsWith(NovaVpnService.BACKEND_OPERA) && hasActiveNovaSystemVpn()

    }



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



    private fun resolveDesiredBackendForCurrentPreferences(): String {

        return if (clientData.shouldUseWarpTransport()) {

            NovaVpnService.BACKEND_WARP

        } else {

            "${NovaVpnService.BACKEND_OPERA}-${clientData.getPreferredOperaLabel()}"

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

            "eu" -> "EU"

            "us" -> "US"

            "ru" -> "WARP"

            else -> "AUTO"

        }

    }



    /** Перенастраивает выбор протокола по текущему состоянию службы. */
    private fun refreshConnectionSelector() {
        val title = findViewById<TextView>(R.id.tv_connection_selector_title) ?: return
        val group = findViewById<RadioGroup>(R.id.rg_exit_region) ?: return
        val summary = findViewById<TextView>(R.id.tv_exit_last) ?: return
        val buttons = listOfNotNull(
            findViewById<RadioButton>(R.id.rb_exit_auto),
            findViewById<RadioButton>(R.id.rb_exit_ru),
            findViewById<RadioButton>(R.id.rb_exit_masque),
            findViewById<RadioButton>(R.id.rb_exit_eu),
            findViewById<RadioButton>(R.id.rb_exit_us),
        )
        if (buttons.size < 5) return
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



    private fun configureRegionSelector(

        titleView: TextView,

        radioGroup: RadioGroup,

        buttons: List<RadioButton>,

        summaryView: TextView,

    ) {

        val rbExitAuto = buttons.getOrNull(0) ?: return

        val rbExitRu = buttons.getOrNull(1) ?: return

        val rbExitMasque = buttons.getOrNull(2) ?: return

        val rbExitEu = buttons.getOrNull(3) ?: return

        val rbExitUs = buttons.getOrNull(4) ?: return

        titleView.text = "Выбор региона"

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

        rbExitAuto.text = "AUTO"

        rbExitRu.text = "WARP"

        rbExitMasque.text = "MASQUE"

        rbExitEu.text = "EU"

        rbExitUs.text = "US"



        // Доступность кнопок задаётся заново на каждом заходе, а не правится
        // поверх прежней: экран перенастраивается в onResume, и «выключено»,
        // поставленное временным запретом, иначе пережило бы его причину.
        buttons.forEach {
            it.isEnabled = true
            it.alpha = 1f
        }

        val operaTransportSupported = OperaProxyManager.isSupportedOnDevice(this)

        if (!operaTransportSupported) {

            rbExitEu.isEnabled = false

            rbExitUs.isEnabled = false

            rbExitEu.alpha = 0.45f

            rbExitUs.alpha = 0.45f

            if (initialExitRegionPreference == "eu" || initialExitRegionPreference == "us") {

                clientData.setExitRegionPreference("auto")

                initialExitRegionPreference = "auto"

                Toast.makeText(

                    this,

                    "EU/US недоступны на этом устройстве: встроенный Opera runtime не поддерживается.",

                    Toast.LENGTH_LONG

                ).show()

            }

        }



        // Пока идёт регистрация устройства, выбор протокола заперт.
        //
        // Ключ MASQUE выдаётся только изнутри поднятого туннеля, и смена
        // протокола в этот момент роняет ровно тот туннель, через который его
        // выдают: регистрация начинается заново, а снаружи это выглядит как
        // «MASQUE не включается». Запрет временный и снимается сам — флаг живёт
        // в состоянии службы и обнуляется на её остановке.
        if (clientData.isDeviceRegistrationInProgress()) {
            buttons.forEach {
                it.isEnabled = false
                it.alpha = 0.45f
            }
            summaryView.text =
                "Идёт регистрация устройства — выбор протокола станет доступен, когда она закончится."
            radioGroup.setOnCheckedChangeListener(null)
            when (clientData.getExitRegionPreference()) {
                "eu" -> radioGroup.check(rbExitEu.id)
                "us" -> radioGroup.check(rbExitUs.id)
                "ru" -> radioGroup.check(rbExitRu.id)
                "masque" -> radioGroup.check(rbExitMasque.id)
                else -> radioGroup.check(rbExitAuto.id)
            }
            return
        }

        radioGroup.setOnCheckedChangeListener(null)

        when (clientData.getExitRegionPreference()) {

            "eu" -> radioGroup.check(rbExitEu.id)

            "us" -> radioGroup.check(rbExitUs.id)

            "ru" -> radioGroup.check(rbExitRu.id)

            "masque" -> radioGroup.check(rbExitMasque.id)

            else -> radioGroup.check(rbExitAuto.id)

        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->

            val value = when (checkedId) {

                rbExitEu.id -> "eu"

                rbExitUs.id -> "us"

                rbExitRu.id -> "ru"

                rbExitMasque.id -> "masque"

                else -> "auto"

            }

            clientData.setExitRegionPreference(value)

            updateExitSummary(summaryView)

            if (value != initialExitRegionPreference) {

                maybeApplyRegionChangeImmediately(value)

            }

        }

        updateExitSummary(summaryView)

    }



    private fun configureImportedProtocolSelector(

        titleView: TextView,

        radioGroup: RadioGroup,

        buttons: List<RadioButton>,

        summaryView: TextView,

    ) {

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



    private fun shareReleaseLink() {

        val link = "https://github.com/confeden/Nova-Android/releases"

        val shareIntent = Intent(Intent.ACTION_SEND).apply {

            type = "text/plain"

            putExtra(Intent.EXTRA_SUBJECT, "Nova Android")

            putExtra(Intent.EXTRA_TEXT, link)

        }

        startActivity(Intent.createChooser(shareIntent, "Поделиться Nova"))

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



        rowManualUpdateCheck.isEnabled = progress.state != UpdateDownloadProgress.State.CHECKING

        rowManualUpdateCheck.alpha = if (rowManualUpdateCheck.isEnabled) 1f else 0.8f

        rowManualUpdateCheck.setTextColor(

            when (progress.state) {

                UpdateDownloadProgress.State.READY -> android.graphics.Color.parseColor("#13A10E")

                else -> android.graphics.Color.WHITE

            }

        )

        rowManualUpdateCheck.text = when (progress.state) {

            UpdateDownloadProgress.State.CHECKING -> "Проверяем обновления..."

            UpdateDownloadProgress.State.READY -> "Обновить приложение"

            UpdateDownloadProgress.State.DOWNLOADING,

            UpdateDownloadProgress.State.PAUSED,

            UpdateDownloadProgress.State.FAILED,

            UpdateDownloadProgress.State.IDLE -> "Проверить обновления"

        }



        when (progress.state) {

            UpdateDownloadProgress.State.DOWNLOADING,

            UpdateDownloadProgress.State.PAUSED,

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
        tvWarpLicenseNote.text = when {
            license.isBlank() -> "Не задана — аккаунт бесплатный"
            accountType.isBlank() -> "Ключ сохранён, аккаунт ещё не проверен"
            accountType.equals("free", ignoreCase = true) ->
                "Ключ сохранён, но аккаунт остался бесплатным"
            else -> "Аккаунт: $accountType"
        }
    }

    /**
     * Ввод лицензии WARP+.
     *
     * Зачем она нужна: бесплатная анонимная регистрация выходит с `account_type: "free"`,
     * и служба MASQUE её не обслуживает — соединение принимается, а туннель не
     * открывается. Лицензия меняет тип аккаунта.
     *
     * Ключ хранится отдельно от личности: личность приложение перевыпускает само при
     * отказе Cloudflare, а ключ вводят руками, и терять его при каждом перевыпуске нельзя.
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
                    "Без него аккаунт бесплатный, и MASQUE не подключается: Cloudflare " +
                    "принимает соединение, но туннель не открывает."
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

    private fun applyWarpLicense(rawLicense: String) {
        val license = rawLicense.trim()
        clientData.setWarpPlusLicense(license)
        clientData.setWarpAccountType("")
        updateWarpLicenseNote()
        if (license.isEmpty()) return

        val token = clientData.getAccessToken().orEmpty()
        val deviceId = clientData.getDeviceId().orEmpty()
        if (token.isBlank() || deviceId.isBlank()) {
            // Устройства ещё нет — ключ применится при первой регистрации.
            Toast.makeText(
                this,
                "Ключ сохранён. Он применится, когда Nova зарегистрирует устройство.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        Toast.makeText(this, "Привязываем лицензию…", Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching { nova.Nova.setWarpLicense(token, deviceId, license) }
            runOnUiThread {
                result.onSuccess { accountType ->
                    clientData.setWarpAccountType(accountType)
                    updateWarpLicenseNote()
                    Toast.makeText(
                        this,
                        if (accountType.equals("free", ignoreCase = true)) {
                            "Cloudflare оставил аккаунт бесплатным — ключ не принят"
                        } else {
                            "Лицензия принята, аккаунт: $accountType"
                        },
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure { error ->
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


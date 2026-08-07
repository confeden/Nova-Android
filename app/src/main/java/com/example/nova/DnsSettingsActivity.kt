package com.example.nova

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsSettingsActivity : AppCompatActivity() {

    private lateinit var clientData: ClientData
    private lateinit var swGlobalDns: Switch
    private lateinit var etGlobalPrimary: EditText
    private lateinit var etGlobalFallback: EditText
    private lateinit var swGlobalPlainFallback: Switch
    private lateinit var rgRouteMode: RadioGroup
    private lateinit var swAppOverride: Switch
    private lateinit var tvSelectedApp: TextView
    private lateinit var btnPickApp: TextView
    private lateinit var etAppSearch: EditText
    private lateinit var rvDnsApps: RecyclerView
    private lateinit var etAppPrimary: EditText
    private lateinit var etAppFallback: EditText
    private lateinit var swAppPlainFallback: Switch
    private lateinit var tvSummary: TextView
    private lateinit var dnsAppPickerAdapter: DnsAppPickerAdapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var appPickerJob: Job? = null
    private var allDnsApps: List<AppItem> = emptyList()
    private var suppressUiCallbacks = false
    private var selectedOverridePackage: String = ""
    private var selectedOverrideLabel: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyZeroTransitionOpen()
        setContentView(R.layout.activity_dns_settings)
        NovaFontHelper.apply(findViewById(android.R.id.content))

        clientData = ClientData(this)
        bindViews()
        bindListeners()
        loadConfig()
    }

    override fun onDestroy() {
        super.onDestroy()
        appPickerJob?.cancel()
        scope.cancel()
    }

    override fun finish() {
        super.finish()
        applyZeroTransitionClose()
    }

    private fun bindViews() {
        swGlobalDns = findViewById(R.id.sw_global_dns)
        etGlobalPrimary = findViewById(R.id.et_global_primary_dns)
        etGlobalFallback = findViewById(R.id.et_global_fallback_dns)
        swGlobalPlainFallback = findViewById(R.id.sw_global_plain_fallback)
        rgRouteMode = findViewById(R.id.rg_dns_route_mode)
        swAppOverride = findViewById(R.id.sw_app_override_dns)
        tvSelectedApp = findViewById(R.id.tv_selected_dns_app)
        btnPickApp = findViewById(R.id.btn_pick_dns_app)
        etAppSearch = findViewById(R.id.et_dns_app_search)
        rvDnsApps = findViewById(R.id.rv_dns_apps)
        etAppPrimary = findViewById(R.id.et_app_primary_dns)
        etAppFallback = findViewById(R.id.et_app_fallback_dns)
        swAppPlainFallback = findViewById(R.id.sw_app_plain_fallback)
        tvSummary = findViewById(R.id.tv_dns_runtime_summary)
        dnsAppPickerAdapter = DnsAppPickerAdapter(selectedOverridePackage) { selected ->
            selectedOverridePackage = selected.packageName
            selectedOverrideLabel = selected.label
            renderSelectedApp()
            hideAppPicker()
            applyExclusiveDnsAppMode()
        }
        rvDnsApps.layoutManager = LinearLayoutManager(this)
        rvDnsApps.adapter = dnsAppPickerAdapter
        rvDnsApps.isNestedScrollingEnabled = true
        rvDnsApps.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
            }
            false
        }
        TvFocusHelper.install(
            this,
            swGlobalDns,
            btnPickApp,
            swAppOverride,
        )
    }

    private fun bindListeners() {
        swGlobalDns.setOnCheckedChangeListener { _, _ ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            renderUiState()
            persistConfig()
        }
        swGlobalPlainFallback.setOnCheckedChangeListener { _, _ ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            persistConfig()
        }
        swAppOverride.setOnCheckedChangeListener { _, _ ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            renderUiState()
            persistConfig()
        }
        swAppPlainFallback.setOnCheckedChangeListener { _, _ ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            persistConfig()
        }
        rgRouteMode.setOnCheckedChangeListener { _, _ ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            persistConfig()
        }
        btnPickApp.setOnClickListener {
            if (selectedOverridePackage.isNotBlank()) {
                cancelAppDnsRule()
            } else if (rvDnsApps.visibility == View.VISIBLE) {
                hideAppPicker()
            } else {
                openAppPicker()
            }
        }
        etAppSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                submitDnsAppList(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        val fields = listOf(
            etGlobalPrimary,
            etGlobalFallback,
            etAppPrimary,
            etAppFallback,
        )
        fields.forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (suppressUiCallbacks) return
                    persistConfig()
                }
            })
        }
    }

    private fun loadConfig() {
        val config = clientData.getDnsSettingsConfig()
        suppressUiCallbacks = true
        swGlobalDns.isChecked = config.globalEnabled
        etGlobalFallback.setText(config.globalEncryptedFallback)
        etGlobalPrimary.setText(config.globalPrimaryDns)
        swGlobalPlainFallback.isChecked = config.allowPlainFallback
        when (config.routeMode) {
            "direct" -> rgRouteMode.check(R.id.rb_dns_route_direct)
            "tunnel" -> rgRouteMode.check(R.id.rb_dns_route_tunnel)
            else -> rgRouteMode.check(R.id.rb_dns_route_fastest)
        }
        swAppOverride.isChecked = config.appOverride.enabled
        selectedOverridePackage = config.appOverride.packageName
        selectedOverrideLabel = config.appOverride.appLabel
        etAppFallback.setText(config.appOverride.encryptedFallback)
        etAppPrimary.setText(config.appOverride.primaryDns)
        swAppPlainFallback.isChecked = config.appOverride.allowPlainFallback
        suppressUiCallbacks = false
        renderSelectedApp()
        renderUiState()
        updateSummary()
    }

    private fun renderUiState() {
        val globalEnabled = swGlobalDns.isChecked
        val appOverrideEnabled = swAppOverride.isChecked
        val globalViews = listOf<View>(
            etGlobalPrimary,
            etGlobalFallback,
            swGlobalPlainFallback,
            findViewById(R.id.rb_dns_route_fastest),
            findViewById(R.id.rb_dns_route_direct),
            findViewById(R.id.rb_dns_route_tunnel),
        )
        globalViews.forEach { view ->
            view.isEnabled = globalEnabled
            view.alpha = if (globalEnabled) 1f else 0.55f
        }
        val appViews = listOf<View>(
            btnPickApp,
            etAppSearch,
            rvDnsApps,
            etAppPrimary,
            etAppFallback,
            swAppPlainFallback,
        )
        appViews.forEach { view ->
            view.isEnabled = appOverrideEnabled
            view.alpha = if (appOverrideEnabled) 1f else 0.55f
        }
        tvSelectedApp.alpha = if (appOverrideEnabled) 1f else 0.55f
        renderExclusiveActionState()
    }

    private fun renderSelectedApp() {
        tvSelectedApp.text = if (selectedOverridePackage.isBlank()) {
            "Выбрано приложение: пока не выбрано"
        } else {
            "Выбрано приложение: ${selectedOverrideLabel.ifBlank { selectedOverridePackage }}"
        }
    }

    private fun persistConfig() {
        val routeMode = when (rgRouteMode.checkedRadioButtonId) {
            R.id.rb_dns_route_direct -> "direct"
            R.id.rb_dns_route_tunnel -> "tunnel"
            else -> "auto"
        }
        clientData.saveDnsSettingsConfig(
            DnsSettingsConfig(
                globalEnabled = swGlobalDns.isChecked,
                globalPrimaryDns = etGlobalPrimary.text?.toString().orEmpty(),
                globalSecondaryDns = "",
                globalEncryptedFallback = etGlobalFallback.text?.toString().orEmpty(),
                allowPlainFallback = swGlobalPlainFallback.isChecked,
                routeMode = routeMode,
                appOverride = DnsAppOverride(
                    enabled = swAppOverride.isChecked,
                    packageName = selectedOverridePackage,
                    appLabel = selectedOverrideLabel,
                    primaryDns = etAppPrimary.text?.toString().orEmpty(),
                    secondaryDns = "",
                    encryptedFallback = etAppFallback.text?.toString().orEmpty(),
                    allowPlainFallback = swAppPlainFallback.isChecked,
                ),
            )
        )
        updateSummary()
    }

    private fun updateSummary() {
        tvSummary.text = clientData.getDnsSettingsSummary()
        renderExclusiveActionState()
    }

    private fun renderExclusiveActionState() {
        val status = clientData.getDnsAppOverrideStatus()
        val appOverrideEnabled = swAppOverride.isChecked
        val hasSelectedApp = selectedOverridePackage.isNotBlank()
        btnPickApp.isEnabled = appOverrideEnabled || hasSelectedApp
        btnPickApp.alpha = if (btnPickApp.isEnabled) 1f else 0.55f
        btnPickApp.text = when {
            hasSelectedApp -> "Отменить правило"
            !appOverrideEnabled -> "Включите DNS для приложения"
            rvDnsApps.visibility == View.VISIBLE -> "Скрыть список"
            status.waitingForExclusiveMode -> "Выбрать приложение"
            else -> "Выбрать приложение"
        }
    }

    private fun applyExclusiveDnsAppMode() {
        if (!swAppOverride.isChecked) {
            Toast.makeText(this, "Сначала включите DNS для приложения.", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedOverridePackage.isBlank()) {
            Toast.makeText(this, "Сначала выберите приложение.", Toast.LENGTH_SHORT).show()
            return
        }
        val currentMode = clientData.getSplitMode()
        val currentApps = clientData.getSplitApps()
        val targetApps = setOf(selectedOverridePackage)
        if (!(currentMode == 1 && currentApps == targetApps)) {
            val existingSnapshot = clientData.getDnsExclusiveRestoreSnapshot()
            if (existingSnapshot == null || existingSnapshot.targetPackage != selectedOverridePackage) {
                clientData.saveDnsExclusiveRestoreSnapshot(
                    mode = currentMode,
                    apps = currentApps,
                    targetPackage = selectedOverridePackage,
                )
            }
        }
        clientData.setSplitMode(1)
        clientData.setSplitApps(targetApps)
        persistConfig()
        updateSummary()
        Toast.makeText(
            this,
            "VPN переключён на режим: только ${selectedOverrideLabel.ifBlank { selectedOverridePackage }}",
            Toast.LENGTH_LONG,
        ).show()
        reapplyActiveSession()
    }

    private fun cancelAppDnsRule() {
        val snapshot = clientData.getDnsExclusiveRestoreSnapshot()
        if (snapshot != null) {
            clientData.setSplitMode(snapshot.mode)
            clientData.setSplitApps(snapshot.apps)
            clientData.clearDnsExclusiveRestoreSnapshot()
        }
        selectedOverridePackage = ""
        selectedOverrideLabel = ""
        hideAppPicker()
        renderSelectedApp()
        persistConfig()
        updateSummary()
        Toast.makeText(this, "Правило DNS для приложения отменено.", Toast.LENGTH_LONG).show()
        reapplyActiveSession()
    }

    private fun reapplyActiveSession() {
        val intent = Intent(this, NovaVpnService::class.java).apply {
            action = NovaVpnService.ACTION_REAPPLY_CURRENT_SESSION
            putExtra(NovaVpnService.EXTRA_EXIT_REGION, clientData.getExitRegionPreference())
            putExtra(
                NovaVpnService.EXTRA_IMPORTED_CONFIG_SOURCE_ENABLED,
                clientData.isImportedConfigSourceActive()
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
            putExtra(
                NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_ENABLED,
                clientData.getTrafficMaskEnabled()
            )
            putExtra(
                NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_MODE,
                clientData.getTrafficMaskMode()
            )
            putExtra(
                NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_HOST,
                clientData.getTrafficMaskHost()
            )
        }
        runCatching {
            ContextCompat.startForegroundService(this, intent)
        }.onFailure {
            Toast.makeText(
                this,
                "Параметры сохранены. Переподключите VPN, если режим не применился сразу.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun openAppPicker() {
        appPickerJob?.cancel()
        btnPickApp.isEnabled = false
        btnPickApp.text = "Загружаем список..."
        etAppSearch.visibility = View.VISIBLE
        rvDnsApps.visibility = View.VISIBLE
        appPickerJob = scope.launch {
            val apps = withContext(Dispatchers.IO) {
                AppCacheManager.getInstalledApps(this@DnsSettingsActivity, emptySet())
                    .sortedBy { it.label.lowercase() }
            }
            allDnsApps = apps
            btnPickApp.isEnabled = true
            renderExclusiveActionState()
            if (apps.isEmpty()) {
                Toast.makeText(
                    this@DnsSettingsActivity,
                    "Список приложений пуст.",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            submitDnsAppList(etAppSearch.text?.toString().orEmpty())
        }
    }

    private fun hideAppPicker() {
        etAppSearch.visibility = View.GONE
        rvDnsApps.visibility = View.GONE
        etAppSearch.setText("")
        renderExclusiveActionState()
    }

    private fun submitDnsAppList(query: String = "") {
        val normalizedQuery = query.trim().lowercase()
        val filtered = if (normalizedQuery.isBlank()) {
            allDnsApps
        } else {
            allDnsApps.filter { app ->
                app.label.lowercase().contains(normalizedQuery) ||
                    app.packageName.lowercase().contains(normalizedQuery)
            }
        }
        dnsAppPickerAdapter.setSelectedPackage(selectedOverridePackage)
        dnsAppPickerAdapter.setData(filtered)
    }

    private fun applyZeroTransitionOpen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun applyZeroTransitionClose() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}

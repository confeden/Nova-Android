package com.example.nova

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.app.Activity
import android.app.ActivityManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.Html
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.Job
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import org.apache.commons.compress.archivers.ArchiveEntry
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.BufferedInputStream
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.io.File
import java.util.Base64
import java.util.zip.ZipInputStream
import java.util.zip.InflaterInputStream
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WarpConfigsActivity : AppCompatActivity() {

    private data class ParsedUserWarpConfig(
        val engine: String,
        val mode: String,
        val host: String,
        val port: Int,
        val rawConfig: String,
        val preferredSni: String = "",
    )

    private data class ImportResult(
        val imported: Int,
        val importedAwg: Int = 0,
        val importedVless: Int = 0,
        val warning: String? = null,
        /** Идентификаторы добавленных записей: по первой из них экран прокручивается. */
        val newIds: List<String> = emptyList(),
    )

    private data class ArchiveTextEntry(
        val name: String,
        val text: String,
    )

    private data class BuiltInAwgClone(
        val rawConfig: String,
        val mode: String,
    )

    private data class RenderedConfigLists(
        val allConfigs: List<WarpVerifiedConfig>,
        val importedCount: Int,
        val builtInItems: List<WarpVerifiedConfig>,
        val importedItems: List<WarpVerifiedConfig>,
        val builtInBestAvgPing: Double?,
        val importedBestAvgPing: Double?,
    )

    private companion object {
        const val DEFAULT_WARP_SUBSCRIPTION_URL =
            "https://raw.githubusercontent.com/confeden/auto-warp-config/refs/heads/main/config.txt"
        const val MAX_ARCHIVE_BYTES = 64L * 1024L * 1024L
        const val MAX_ARCHIVE_ENTRIES = 512
        const val MAX_CONFIG_TEXT_BYTES = 2L * 1024L * 1024L
        @Volatile private var cachedAllConfigs: List<WarpVerifiedConfig> = emptyList()
        @Volatile private var cachedBuiltInItems: List<WarpVerifiedConfig> = emptyList()
        @Volatile private var cachedImportedItems: List<WarpVerifiedConfig> = emptyList()
        @Volatile private var cachedImportedCount: Int = 0
        @Volatile private var cachedBuiltInBestAvgPing: Double? = null
        @Volatile private var cachedImportedBestAvgPing: Double? = null
    }

    private lateinit var clientData: ClientData
    private lateinit var btnCreate: TextView
    private lateinit var btnPaste: TextView
    private lateinit var btnAdapt: TextView
    private lateinit var btnImportedOnly: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvProgress: TextView
    private lateinit var trailView: WarpDiscoveryTrailView
    private lateinit var rvConfigs: RecyclerView
    private lateinit var emptyView: TextView

    private var discoveryRunning = false
    private var renderJob: Job? = null
    private var snapshotJob: Job? = null
    private var lastDiscoveryMessage = "Готово к проверке"
    private var stopRequestedLocally = false
    private var adaptationActive = false
    private var pendingVpnPermissionAction = "discovery"
    private var lastRenderedDiscoverySnapshot: WarpDiscoverySnapshot? = null
    private var displayedDiscoveryOrdinal = 0
    private var displayedDiscoveryTotal = 0
    private var lastLoadedConfigs: List<WarpVerifiedConfig> = emptyList()
    private var configSearchQuery: String = ""

    /** Запись, к которой надо прокрутить список при ближайшей отрисовке. */
    private var pendingRevealConfigId: String? = null
    private val snapshotHandler = Handler(Looper.getMainLooper())
    private val snapshotPoller = object : Runnable {
        override fun run() {
            refreshDiscoveryStateFromStorage()
            snapshotHandler.postDelayed(this, if (discoveryRunning || stopRequestedLocally) 500L else 1200L)
        }
    }

    private val importFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importConfigsFromUri(uri)
        }
    }

    private val importArchiveLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importConfigsFromArchiveUri(uri)
        }
    }

    private fun isVisibleBuiltInConfig(config: WarpVerifiedConfig): Boolean {
        return !config.manual &&
            !config.userImported &&
            config.endpointSource.equals("bundled-seed", ignoreCase = true)
    }

    private fun isVisibleImportedConfig(config: WarpVerifiedConfig): Boolean {
        return !config.manual && config.userImported
    }

    private fun countVisibleConfigs(
        importedOnly: Boolean,
        configs: List<WarpVerifiedConfig> = lastLoadedConfigs.ifEmpty { cachedAllConfigs },
    ): Int {
        return configs.count { config ->
            if (importedOnly) isVisibleImportedConfig(config) else isVisibleBuiltInConfig(config)
        }
    }

    private fun applyManualOrder(
        configs: List<WarpVerifiedConfig>,
        importedOnly: Boolean,
    ): List<WarpVerifiedConfig>? {
        val order = clientData.getWarpManualOrder(importedOnly)
        if (order.isEmpty()) return null
        val rank = order.withIndex().associate { (index, id) -> id to index }
        // Конфигурации, появившиеся после последней ручной сортировки, идут в конец
        // в том порядке, в котором их вернула автоматическая сортировка.
        val known = configs.filter { rank.containsKey(it.id) }.sortedBy { rank.getValue(it.id) }
        val fresh = configs.filterNot { rank.containsKey(it.id) }
        return known + fresh
    }

    fun moveConfig(id: String, move: ConfigsAdapter.Move) {
        val importedOnly = clientData.isImportedWarpOnlyModeEnabled()
        // Первое перемещение фиксирует текущий видимый порядок целиком, чтобы
        // дальнейшие сдвиги были предсказуемы для пользователя.
        val current = sortedVisibleConfigs(
            lastLoadedConfigs.ifEmpty { cachedAllConfigs },
            importedOnly,
        ).map { it.id }.toMutableList()
        val from = current.indexOf(id)
        if (from < 0) return
        val to = when (move) {
            ConfigsAdapter.Move.TOP -> 0
            ConfigsAdapter.Move.UP -> (from - 1).coerceAtLeast(0)
            ConfigsAdapter.Move.DOWN -> (from + 1).coerceAtMost(current.lastIndex)
            ConfigsAdapter.Move.BOTTOM -> current.lastIndex
        }
        if (to == from) return
        current.removeAt(from)
        current.add(to, id)
        clientData.setWarpManualOrder(importedOnly, current)
        renderConfigs()
    }

    private fun sortedVisibleConfigs(
        configs: List<WarpVerifiedConfig>,
        importedOnly: Boolean,
    ): List<WarpVerifiedConfig> {
        val visibleConfigs = if (importedOnly) {
            configs.filter(::isVisibleImportedConfig)
        } else {
            configs.filter(::isVisibleBuiltInConfig)
        }
        val autoSorted = autoSortedConfigs(visibleConfigs, importedOnly)
        return applyManualOrder(autoSorted, importedOnly) ?: autoSorted
    }

    private fun autoSortedConfigs(
        visibleConfigs: List<WarpVerifiedConfig>,
        importedOnly: Boolean,
    ): List<WarpVerifiedConfig> {
        if (!importedOnly) {
            return visibleConfigs.sortedWith(
                compareByDescending<WarpVerifiedConfig> { it.promotedAt }
                    .thenByDescending { clientData.getWarpVerifiedQualityTier(it) }
                    // Порядок карточек обязан совпадать с порядком перебора, иначе
                    // экран показывает одно, а служба подключается по другому.
                    .thenByDescending { clientData.warpConfigHoldGrade(it) }
                    .thenByDescending { clientData.getWarpVerifiedEffectiveQualityScore(it) }
                    .thenByDescending { clientData.getWarpVerifiedPriorityScore(it) }
                    .thenByDescending { clientData.getWarpVerifiedEffectivePingSuccesses(it) }
                    .thenBy { clientData.getWarpVerifiedEffectiveFailureCount(it) }
                    .thenBy {
                        clientData.getWarpVerifiedEffectiveAvgPingMs(it)
                            .takeIf { avgPingMs -> avgPingMs > 0.0 }
                            ?: Double.MAX_VALUE
                    }
                    .thenByDescending { it.lastVerifiedAt }
                    .thenBy { it.seedOrder }
                    .thenBy { it.mode }
                    .thenBy { it.host }
                    .thenBy { it.port }
            )
        }
        return visibleConfigs
            .sortedWith(
                compareByDescending<WarpVerifiedConfig> { it.promotedAt }
                    .thenByDescending { clientData.getWarpVerifiedQualityTier(it) }
                    // Порядок карточек обязан совпадать с порядком перебора, иначе
                    // экран показывает одно, а служба подключается по другому.
                    .thenByDescending { clientData.warpConfigHoldGrade(it) }
                    .thenByDescending { clientData.getWarpVerifiedEffectiveQualityScore(it) }
                    .thenByDescending { clientData.getWarpVerifiedPriorityScore(it) }
                    .thenByDescending { clientData.getWarpVerifiedEffectivePingSuccesses(it) }
                    .thenBy { clientData.getWarpVerifiedEffectiveFailureCount(it) }
                    .thenByDescending { it.lastVerifiedAt }
                    .thenByDescending { it.userImported }
                    .thenByDescending { !it.manual }
            )
    }

    private fun calculateBestAvgPing(items: List<WarpVerifiedConfig>): Double? {
        return items
            .mapNotNull { candidate ->
                clientData.getWarpVerifiedEffectiveAvgPingMs(candidate)
                    .takeIf {
                        clientData.getWarpVerifiedEffectiveLastCheckedAt(candidate) > 0L &&
                            clientData.getWarpVerifiedEffectiveProbeCount(candidate) > 0 &&
                            it > 0.0
                    }
            }
            .minOrNull()
    }

    private fun renderConfigItems(allItems: List<WarpVerifiedConfig>, importedOnly: Boolean, bestAvgPing: Double?) {
        val items = applySearchFilter(allItems)
        emptyView.text = when {
            allItems.isNotEmpty() && items.isEmpty() -> "Ничего не найдено."
            importedOnly -> "Пока нет импортированных конфигураций."
            else -> "Пока нет встроенных конфигураций."
        }
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        val metadata = items.associate { item ->
            item.id to ConfigsAdapter.ConfigMetadata(
                probeCount = clientData.getWarpVerifiedEffectiveProbeCount(item),
                pingSuccesses = clientData.getWarpVerifiedEffectivePingSuccesses(item),
                avgPingMs = clientData.getWarpVerifiedEffectiveAvgPingMs(item),
                lastCheckedAt = clientData.getWarpVerifiedEffectiveLastCheckedAt(item),
            )
        }
        val adapter = rvConfigs.adapter as? ConfigsAdapter
        if (adapter == null) {
            rvConfigs.adapter = ConfigsAdapter(
                items,
                metadata,
                bestAvgPing,
                clientData,
                this@WarpConfigsActivity,
                onDataChanged = { renderConfigs() },
                onMove = { id, move -> moveConfig(id, move) },
            )
        } else {
            adapter.updateItems(items, metadata, bestAvgPing)
        }
        consumePendingReveal(items)
    }

    /**
     * Показывает только что импортированную запись.
     *
     * Дефект, ради которого это появилось: профиль добавлялся в конец списка на две
     * с лишним сотни строк, а поиск мог его и вовсе отфильтровать. Приложение честно
     * писало «импортировано», в списке при этом визуально не менялось ничего, и
     * выглядело это как несработавший импорт. Поэтому поиск сбрасываем, список
     * переключаем на импортированные и прокручиваем к новой записи.
     */
    private fun revealImportedConfigs(result: ImportResult) {
        pendingRevealConfigId = result.newIds.firstOrNull()
        if (pendingRevealConfigId != null && configSearchQuery.isNotBlank()) {
            configSearchQuery = ""
            runCatching { findViewById<android.widget.EditText>(R.id.et_config_search).setText("") }
        }
        clientData.setImportedWarpOnlyModeEnabled(true)
        updateImportedOnlyUi()
        renderConfigs()
    }

    /** Прокручивает список к записи, если она в нём есть. Признак одноразовый. */
    private fun consumePendingReveal(items: List<WarpVerifiedConfig>) {
        val target = pendingRevealConfigId ?: return
        val index = items.indexOfFirst { it.id == target }
        if (index < 0) return
        pendingRevealConfigId = null
        rvConfigs.post { runCatching { rvConfigs.scrollToPosition(index) } }
    }

    private fun applySearchFilter(items: List<WarpVerifiedConfig>): List<WarpVerifiedConfig> {
        val query = configSearchQuery.trim().lowercase(java.util.Locale.US)
        if (query.isBlank()) return items
        return items.filter { item ->
            val mode = normalizeModeForDisplay(item.rawConfig, item.mode)
            "${item.host}:${item.port} $mode ${item.endpointSource}".lowercase(java.util.Locale.US)
                .contains(query)
        }
    }

    private fun renderCachedConfigsIfAvailable() {
        val allConfigs = cachedAllConfigs
        if (allConfigs.isEmpty()) return

        val importedOnly = clientData.isImportedWarpOnlyModeEnabled()
        lastLoadedConfigs = allConfigs
        updateImportedOnlyUi(cachedImportedCount)
        renderConfigItems(
            if (importedOnly) cachedImportedItems else cachedBuiltInItems,
            importedOnly,
            if (importedOnly) cachedImportedBestAvgPing else cachedBuiltInBestAvgPing,
        )
    }

    private fun smoothDiscoveryOrdinal(running: Boolean, ordinal: Int, total: Int): Int {
        val safeTotal = total.coerceAtLeast(0)
        val safeOrdinal = ordinal.coerceIn(0, safeTotal.takeIf { it > 0 } ?: Int.MAX_VALUE)

        // Preserve state if not running or no configs
        if (!running || safeTotal <= 0) {
            displayedDiscoveryOrdinal = safeOrdinal
            displayedDiscoveryTotal = safeTotal
            return safeOrdinal
        }

        // Only reset if total changes significantly, otherwise keep current
        if (displayedDiscoveryTotal != safeTotal) {
            displayedDiscoveryTotal = safeTotal
            displayedDiscoveryOrdinal = safeOrdinal
            return safeOrdinal
        }

        // Simply return safeOrdinal to prevent jumpy increment logic
        displayedDiscoveryOrdinal = safeOrdinal
        return safeOrdinal
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (pendingVpnPermissionAction == "adaptation") {
                startAdaptationService()
            } else {
                startDiscoveryService()
            }
        } else {
            stopRequestedLocally = false
            clientData.saveWarpDiscoverySnapshot(
                running = false,
                foundCount = countVisibleConfigs(importedOnly = false),
                message = "Разрешение VPN для Nova не выдано",
                ordinal = 0,
                total = 0,
            )
            updateDiscoveryUi(
                running = false,
                message = "Разрешение VPN для Nova не выдано",
                foundCount = countVisibleConfigs(importedOnly = false),
                ordinal = 0,
                total = 0,
            )
        }
    }

    private val discoveryReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                NovaVpnService.ACTION_WARP_CONFIG_DISCOVERY -> {
                    discoveryRunning = intent.getBooleanExtra(NovaVpnService.EXTRA_DISCOVERY_RUNNING, false)
                    val foundCount = intent.getIntExtra(NovaVpnService.EXTRA_DISCOVERY_FOUND_COUNT, 0)
                    val message = intent.getStringExtra(NovaVpnService.EXTRA_DISCOVERY_MESSAGE).orEmpty()
                    val ordinal = intent.getIntExtra(NovaVpnService.EXTRA_ATTEMPT_ORDINAL, clientData.getServiceAttemptOrdinal())
                    val total = intent.getIntExtra(NovaVpnService.EXTRA_ATTEMPT_TOTAL, clientData.getServiceAttemptTotal())
                    lastDiscoveryMessage = message.ifBlank { lastDiscoveryMessage }
                    updateDiscoveryUi(
                        running = discoveryRunning,
                        message = lastDiscoveryMessage,
                        foundCount = foundCount,
                        ordinal = ordinal,
                        total = total,
                    )
                    renderConfigs()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_warp_configs)
        NovaFontHelper.apply(findViewById(android.R.id.content))

        clientData = ClientData(this)
        btnCreate = findViewById(R.id.btn_create_new)
        btnPaste = findViewById(R.id.btn_paste_config)
        btnAdapt = findViewById(R.id.btn_adapt_network)
        btnImportedOnly = findViewById(R.id.btn_imported_only)
        tvStatus = findViewById(R.id.tv_discovery_status)
        tvProgress = findViewById(R.id.tv_discovery_progress)
        trailView = findViewById(R.id.view_discovery_trail)
        rvConfigs = findViewById(R.id.rv_configs)
        rvConfigs.layoutManager = LinearLayoutManager(this)
        emptyView = findViewById(R.id.tv_empty)
        val etSearch = findViewById<android.widget.EditText>(R.id.et_config_search)
        etSearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                val next = s?.toString().orEmpty()
                if (next == configSearchQuery) return
                configSearchQuery = next
                renderCachedConfigsIfAvailable()
            }
        })
        TvFocusHelper.install(
            this,
            btnCreate,
            btnPaste,
            btnAdapt,
            btnImportedOnly,
        )

        btnCreate.setOnClickListener {
            // Disabled
        }
        btnPaste.setOnClickListener { showImportExportDialog() }
        btnAdapt.setOnClickListener {
            val isAdaptation = discoveryRunning && (adaptationActive || isAdaptationMessage(lastDiscoveryMessage))
            if (isAdaptation) {
                stopDiscovery()
            } else if (!discoveryRunning) {
                startAdaptation()
            }
        }
        btnImportedOnly.setOnClickListener {
            val nextEnabled = !clientData.isImportedWarpOnlyModeEnabled()
            clientData.setImportedWarpOnlyModeEnabled(nextEnabled)
            updateImportedOnlyUi(cachedImportedCount)
            // Переключение вкладки показывает другой срез тех же данных, а не другие
            // данные. Перечитывать хранилище при каждом нажатии незачем: оба списка уже
            // посчитаны и лежат в кэше, а перезагрузка отдаёт список заново и потому
            // выглядит как задержка на ровном месте. Читаем с диска только если кэша
            // ещё нет — то есть при первом открытии экрана.
            if (cachedAllConfigs.isEmpty()) {
                renderConfigs()
            } else {
                renderCachedConfigsIfAvailable()
            }
        }

        seedCurrentSuccessIfNeeded()
        importPendingLocalBatchIfAny()
        val snapshot = clientData.getWarpDiscoverySnapshot()
        lastRenderedDiscoverySnapshot = snapshot
        renderCachedConfigsIfAvailable()
        updateDiscoveryUi(
            running = snapshot?.running == true,
            message = snapshot?.message?.ifBlank { "Готово к проверке" } ?: "Готово к проверке",
            foundCount = countVisibleConfigs(importedOnly = false),
            ordinal = snapshot?.ordinal ?: 0,
            total = snapshot?.total ?: 0,
        )
        updateImportedOnlyUi()
        refreshDiscoveryStateFromStorage(forceRender = true)
        renderConfigs()
    }

    override fun onStart() {
        super.onStart()
        seedCurrentSuccessIfNeeded()
        val filter = android.content.IntentFilter(NovaVpnService.ACTION_WARP_CONFIG_DISCOVERY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(discoveryReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(discoveryReceiver, filter)
        }
        refreshDiscoveryStateFromStorage(forceRender = true)
        snapshotHandler.removeCallbacks(snapshotPoller)
        snapshotHandler.post(snapshotPoller)
    }

    override fun onStop() {
        super.onStop()
        renderJob?.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        snapshotHandler.removeCallbacks(snapshotPoller)
        try {
            unregisterReceiver(discoveryReceiver)
        } catch (_: Exception) {
        }
    }

    private fun startDiscovery() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingVpnPermissionAction = "discovery"
            val savedCount = countVisibleConfigs(importedOnly = false)
            clientData.saveWarpDiscoverySnapshot(
                running = false,
                foundCount = savedCount,
                message = "Разрешите Nova использовать VPN для проверки WARP",
                ordinal = 0,
                total = 0,
            )
            updateDiscoveryUi(
                running = false,
                message = "Разрешите Nova использовать VPN для проверки WARP",
                foundCount = savedCount,
                ordinal = 0,
                total = 0,
            )
            requestVpnConsentOrExplain(prepareIntent, savedCount)
            return
        }
        startDiscoveryService()
    }

    /**
     * Запрашивает согласие на VPN и, если прошивка не может его показать,
     * пишет причину в ту же строку состояния вместо молчаливого падения.
     */
    private fun requestVpnConsentOrExplain(prepareIntent: Intent, savedCount: Int): Boolean {
        if (VpnConsent.request(vpnPermissionLauncher, prepareIntent)) return true
        clientData.saveWarpDiscoverySnapshot(
            running = false,
            foundCount = savedCount,
            message = VpnConsent.UNAVAILABLE_SHORT,
            ordinal = 0,
            total = 0,
        )
        updateDiscoveryUi(
            running = false,
            message = VpnConsent.UNAVAILABLE_SHORT,
            foundCount = savedCount,
            ordinal = 0,
            total = 0,
        )
        Toast.makeText(this, VpnConsent.UNAVAILABLE_HINT, Toast.LENGTH_LONG).show()
        return false
    }

    private fun startDiscoveryService() {
        stopRequestedLocally = false
        pendingVpnPermissionAction = "discovery"
        val regionPreference = clientData.getExitRegionPreference()
        val effectiveRegion = when (regionPreference) {
            "eu", "us" -> "ru"
            else -> regionPreference
        }
        val intent = Intent(this, NovaVpnService::class.java).apply {
            action = NovaVpnService.ACTION_START_WARP_CONFIG_DISCOVERY
            putExtra(NovaVpnService.EXTRA_EXIT_REGION, effectiveRegion)
        }
        clientData.saveWarpDiscoverySnapshot(
            running = true,
            foundCount = countVisibleConfigs(importedOnly = false),
            message = "Подготовка WARP-конфигураций...",
            ordinal = 0,
            total = 0,
        )
        discoveryRunning = true
        updateDiscoveryUi(
            running = true,
            message = "Проверяем новые WARP-конфигурации...",
            foundCount = countVisibleConfigs(importedOnly = false),
            ordinal = 0,
            total = 0,
        )
        ContextCompat.startForegroundService(this, intent)
    }

    private fun startAdaptation() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            pendingVpnPermissionAction = "adaptation"
            val savedCount = countVisibleConfigs(importedOnly = false)
            clientData.saveWarpDiscoverySnapshot(
                running = false,
                foundCount = savedCount,
                message = "Разрешите Nova использовать VPN для адаптации WARP",
                ordinal = 0,
                total = 0,
            )
            updateDiscoveryUi(
                running = false,
                message = "Разрешите Nova использовать VPN для адаптации WARP",
                foundCount = savedCount,
                ordinal = 0,
                total = 0,
            )
            requestVpnConsentOrExplain(prepareIntent, savedCount)
            return
        }
        startAdaptationService()
    }

    private fun startAdaptationService() {
        stopRequestedLocally = false
        adaptationActive = true
        pendingVpnPermissionAction = "adaptation"
        val regionPreference = clientData.getExitRegionPreference()
        val effectiveRegion = when (regionPreference) {
            "eu", "us" -> "ru"
            else -> regionPreference
        }
        val intent = Intent(this, NovaVpnService::class.java).apply {
            action = NovaVpnService.ACTION_START_WARP_NETWORK_ADAPTATION
            putExtra(NovaVpnService.EXTRA_EXIT_REGION, effectiveRegion)
        }
        val savedCount = countVisibleConfigs(importedOnly = false)
        clientData.saveWarpDiscoverySnapshot(
            running = true,
            foundCount = savedCount,
            message = "Адаптация WARP к текущей сети...",
            ordinal = 0,
            total = 0,
        )
        discoveryRunning = true
        updateDiscoveryUi(
            running = true,
            message = "Адаптация WARP к текущей сети...",
            foundCount = savedCount,
            ordinal = 0,
            total = 0,
        )
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopDiscovery() {
        stopRequestedLocally = true
        btnCreate.isEnabled = false
        btnCreate.text = "Останавливаем..."
        tvStatus.text = "Останавливаем проверку..."
        if (!isNovaVpnServiceRunning()) {
            val foundCount = countVisibleConfigs(importedOnly = false)
            val frozenSnapshot = freezeStoppedDiscoverySnapshot(
                snapshot = clientData.getWarpDiscoverySnapshot(),
                fallbackFoundCount = foundCount,
            )
            clientData.saveWarpDiscoverySnapshot(
                running = false,
                foundCount = frozenSnapshot.foundCount,
                message = frozenSnapshot.message,
                ordinal = frozenSnapshot.ordinal,
                total = frozenSnapshot.total,
            )
            updateDiscoveryUi(
                running = false,
                message = frozenSnapshot.message,
                foundCount = frozenSnapshot.foundCount,
                ordinal = frozenSnapshot.ordinal,
                total = frozenSnapshot.total,
            )
            return
        }
        val intent = Intent(this, NovaVpnService::class.java).apply {
            action = NovaVpnService.ACTION_STOP_WARP_CONFIG_DISCOVERY
        }
        startService(intent)
    }

    private fun updateDiscoveryUi(
        running: Boolean,
        message: String,
        foundCount: Int,
        ordinal: Int,
        total: Int,
    ) {
        discoveryRunning = running
        refreshKeepScreenAwake(running)
        if (!running) {
            stopRequestedLocally = false
            adaptationActive = false
        }
        lastDiscoveryMessage = message.ifBlank { lastDiscoveryMessage }
        val adaptationRunning = running && (adaptationActive || isAdaptationMessage(lastDiscoveryMessage))
        btnCreate.isEnabled = !stopRequestedLocally
        btnCreate.text = when {
            running && adaptationRunning -> "Сгенерировать новые"
            !running -> "Сгенерировать новые"
            stopRequestedLocally -> "Останавливаем..."
            else -> "Остановить"
        }

        btnAdapt.isEnabled = !stopRequestedLocally && (!running || adaptationRunning)
        btnAdapt.text = when {
            adaptationRunning && stopRequestedLocally -> "Останавливаем..."
            adaptationRunning -> "Остановить адаптацию"
            else -> "Адаптация к условиям сети"
        }

        tvStatus.text = if (message.isNotBlank()) message else if (running) "Идёт проверка..." else "Готово к проверке"
        trailView.setRunning(running)
        val importedOnly = clientData.isImportedWarpOnlyModeEnabled()
        val displayCount = countVisibleConfigs(importedOnly)
        val displayOrdinal = smoothDiscoveryOrdinal(running, ordinal, total)
        tvProgress.text = buildString {
            if (adaptationRunning) {
                append("Профили: ")
                append(displayCount)
                if (total > 0) {
                    append("    Попытка: ")
                    append(displayOrdinal.coerceAtLeast(0))
                    append("/")
                    append(total)
                }
            } else {
                append("Сохранено: ")
                append(displayCount)
                if (total > 0) {
                    append("    ")
                    append(displayOrdinal.coerceAtLeast(0))
                    append("/")
                    append(total)
                }
            }
        }
        if (importedOnly) {
            tvProgress.setTextColor(Color.parseColor("#F3C94A"))
        } else {
            tvProgress.setTextColor(Color.parseColor("#50C878"))
        }
    }

    private fun refreshKeepScreenAwake(running: Boolean = discoveryRunning) {
        if (running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun freezeStoppedDiscoverySnapshot(
        snapshot: WarpDiscoverySnapshot?,
        fallbackFoundCount: Int,
        fallbackMessage: String? = null,
    ): WarpDiscoverySnapshot {
        val baseMessage = snapshot?.message.orEmpty()
        val total = snapshot?.total?.coerceAtLeast(0) ?: 0
        val ordinal = (snapshot?.ordinal ?: 0).coerceIn(0, total.takeIf { it > 0 } ?: Int.MAX_VALUE)
        val stopPrefix = if (isAdaptationMessage(baseMessage)) {
            "Адаптация остановлена"
        } else {
            "Проверка остановлена"
        }
        val finalMessage = when {
            fallbackMessage != null -> fallbackMessage
            total > 0 && ordinal > 0 -> "$stopPrefix на $ordinal из $total"
            total > 0 -> "$stopPrefix на 0 из $total"
            else -> stopPrefix
        }
        return WarpDiscoverySnapshot(
            running = false,
            foundCount = maxOf(snapshot?.foundCount ?: 0, fallbackFoundCount),
            message = finalMessage,
            ordinal = ordinal,
            total = total,
            observedAt = System.currentTimeMillis(),
        )
    }

    private fun isAdaptationMessage(message: String): Boolean {
        val normalized = message.lowercase(Locale.getDefault())
        return normalized.contains("адаптац") || normalized.contains("data-plane")
    }

    private fun refreshDiscoveryStateFromStorage(forceRender: Boolean = false) {
        val snapshot = clientData.getWarpDiscoverySnapshot() ?: return
        if (snapshot.running && !isNovaVpnServiceRunning()) {
            val staleForMs = System.currentTimeMillis() - snapshot.observedAt
            if (staleForMs >= 3500L) {
                val foundCount = countVisibleConfigs(importedOnly = false)
                val frozenSnapshot = freezeStoppedDiscoverySnapshot(
                    snapshot = snapshot,
                    fallbackFoundCount = foundCount,
                    fallbackMessage = if (stopRequestedLocally) {
                        null
                    } else {
                        if (isAdaptationMessage(snapshot.message)) "Адаптация завершена" else "Проверка завершена"
                    },
                )
                clientData.saveWarpDiscoverySnapshot(
                    running = false,
                    foundCount = frozenSnapshot.foundCount,
                    message = frozenSnapshot.message,
                    ordinal = frozenSnapshot.ordinal,
                    total = frozenSnapshot.total,
                )
                updateDiscoveryUi(
                    running = false,
                    message = frozenSnapshot.message,
                    foundCount = frozenSnapshot.foundCount,
                    ordinal = frozenSnapshot.ordinal,
                    total = frozenSnapshot.total,
                )
                renderConfigs()
                return
            }
        }
        val serviceOrdinal = clientData.getServiceAttemptOrdinal()
        val serviceTotal = clientData.getServiceAttemptTotal()
        val resolvedOrdinal = snapshot.ordinal
        val resolvedTotal = snapshot.total
        val resolvedMessage = when {
            snapshot.message.startsWith("Проверяем новые WARP-конфигурации") && resolvedTotal > 0 ->
                "Проверяем конфигурацию ${resolvedOrdinal.coerceAtLeast(1)} из $resolvedTotal..."
            else -> snapshot.message
        }
        val normalizedSnapshot = snapshot.copy(
            message = resolvedMessage,
            ordinal = resolvedOrdinal,
            total = resolvedTotal,
        )
        if (!forceRender && normalizedSnapshot == lastRenderedDiscoverySnapshot) return
        lastRenderedDiscoverySnapshot = normalizedSnapshot
        updateDiscoveryUi(
            running = normalizedSnapshot.running,
            message = normalizedSnapshot.message.ifBlank { lastDiscoveryMessage },
            foundCount = maxOf(normalizedSnapshot.foundCount, countVisibleConfigs(importedOnly = false)),
            ordinal = normalizedSnapshot.ordinal,
            total = normalizedSnapshot.total,
        )
        renderConfigs()
    }

    private fun isNovaVpnServiceRunning(): Boolean {
        val activityManager = getSystemService(ActivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return activityManager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == NovaVpnService::class.java.name
        }
    }

    private fun showImportExportDialog() {
        val subscription = clientData.getVlessSubscription()
        val importedCount = clientData.countImportedConfigs()
        // Пункты и действия держим одной парой, а не считаем индексы по месту: список
        // меняется в зависимости от подписки, и арифметика по `lastIndex` ломалась бы
        // при каждом новом пункте.
        val entries = buildList<Pair<String, () -> Unit>> {
            add("Вставить из буфера обмена" to ::showPasteDialog)
            add("Вставить из файла" to {
                importFileLauncher.launch(arrayOf("text/plain", "application/octet-stream", "*/*"))
            })
            add("Вставить из архива (zip/rar/7z)" to {
                importArchiveLauncher.launch(
                    arrayOf(
                        "application/zip",
                        "application/x-7z-compressed",
                        "application/vnd.rar",
                        "application/x-rar-compressed",
                        "application/octet-stream",
                        "*/*",
                    )
                )
            })
            add("Вставить из URL-подписки" to ::showImportUrlDialog)
            if (subscription != null) {
                add("Управление подпиской" to { showSubscriptionDialog(subscription) })
            }
            add("Экспортировать текущую" to ::shareCurrentConfig)
            add("Экспортировать все" to ::shareAllConfigs)
            if (importedCount > 0) {
                add("Удалить все импортированные ($importedCount)" to ::showClearImportedDialog)
            }
        }
        AlertDialog.Builder(this)
            // Именно customTitle, а не setMessage: заданное сообщение занимает место
            // списка, и пункты меню просто не показываются — остаётся одна «Отмена».
            .setCustomTitle(buildImportDialogTitle(subscription))
            .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                entries.getOrNull(which)?.second?.invoke()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /**
     * Стирание всего импортированного.
     *
     * Спрашиваем подтверждение и называем число: подписка на сотни профилей
     * восстанавливается только повторной загрузкой, а импортированные вручную
     * конфигурации — вообще ничем.
     */
    private fun showClearImportedDialog() {
        val total = clientData.countImportedConfigs()
        if (total <= 0) {
            Toast.makeText(this, "Импортированных конфигураций нет", Toast.LENGTH_SHORT).show()
            return
        }
        val subscription = clientData.getVlessSubscription()
        val subscriptionNote = if (subscription != null) {
            "\n\nПодписка останется подключённой: при следующем обновлении её профили " +
                "загрузятся заново. Чтобы этого не было, удалите и саму подписку."
        } else {
            ""
        }
        AlertDialog.Builder(this)
            .setTitle("Удалить все импортированные?")
            .setMessage("Будет удалено записей: $total. Встроенные профили останутся.$subscriptionNote")
            .setPositiveButton("Удалить") { _, _ ->
                val removed = clientData.clearImportedConfigs()
                renderConfigs()
                Toast.makeText(this, "Удалено записей: $removed", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Управление подпиской: состояние, обновление, интервал и удаление. */
    private fun showSubscriptionDialog(subscription: VlessSubscriptionState) {
        val intervalLabel = "раз в ${subscription.updateIntervalHours.takeIf { it > 0 } ?: 12} ч"
        val entries = listOf<Pair<String, () -> Unit>>(
            "Обновить сейчас" to { importConfigsFromUrl(subscription.url) },
            "Интервал обновления: $intervalLabel" to { showSubscriptionIntervalDialog(subscription) },
            "Скопировать адрес" to {
                getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("nova-subscription", subscription.url))
                Toast.makeText(this, "Адрес подписки скопирован", Toast.LENGTH_SHORT).show()
            },
            "Удалить подписку" to { showDeleteSubscriptionDialog(subscription) },
        )
        AlertDialog.Builder(this)
            .setCustomTitle(buildImportDialogTitle(subscription))
            .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                entries.getOrNull(which)?.second?.invoke()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showSubscriptionIntervalDialog(subscription: VlessSubscriptionState) {
        // Пункта «выключить» здесь нет намеренно: подписка без расписания — это
        // удалённая подписка, для этого есть отдельное действие.
        val options = listOf(
            3 to "Раз в 3 ч",
            6 to "Раз в 6 ч",
            12 to "Раз в 12 ч (по умолчанию)",
            24 to "Раз в сутки",
            168 to "Раз в неделю",
        )
        val defaultIndex = options.indexOfFirst { it.first == 12 }
        val current = options
            .indexOfFirst { it.first == subscription.updateIntervalHours }
            .takeIf { it >= 0 } ?: defaultIndex
        AlertDialog.Builder(this)
            .setTitle("Интервал обновления подписки")
            .setSingleChoiceItems(options.map { it.second }.toTypedArray(), current) { dialog, which ->
                val hours = options[which].first
                clientData.saveVlessSubscription(subscription.copy(updateIntervalHours = hours))
                VlessSubscriptionManager.syncSchedule(this)
                dialog.dismiss()
                Toast.makeText(this, "Интервал обновления: ${options[which].second}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDeleteSubscriptionDialog(subscription: VlessSubscriptionState) {
        val profileCount = clientData.getVlessProfileLinks().size
        AlertDialog.Builder(this)
            .setTitle("Удалить подписку?")
            .setMessage(
                "Автообновление прекратится. Загруженные профили ($profileCount) можно " +
                    "оставить в списке — они продолжат работать, пока живы сами узлы."
            )
            .setPositiveButton("Удалить с профилями") { _, _ ->
                clientData.clearVlessSubscription()
                clientData.clearVlessProfileLinks()
                VlessSubscriptionManager.syncSchedule(this)
                renderConfigs()
                Toast.makeText(this, "Подписка и её профили удалены", Toast.LENGTH_LONG).show()
            }
            .setNeutralButton("Оставить профили") { _, _ ->
                clientData.clearVlessSubscription()
                VlessSubscriptionManager.syncSchedule(this)
                renderConfigs()
                Toast.makeText(this, "Подписка удалена, профили остались", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun buildImportDialogTitle(subscription: VlessSubscriptionState?): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 48, 64, 16)
        }
        container.addView(
            TextView(this).apply {
                text = "Импорт конфигураций"
                textSize = 20f
                setTextColor(Color.WHITE)
            }
        )
        if (subscription != null) {
            container.addView(
                TextView(this).apply {
                    text = describeSubscriptionState(subscription)
                    textSize = 13f
                    setPadding(0, 16, 0, 0)
                    setTextColor(Color.parseColor("#B0B0B0"))
                }
            )
        }
        return container
    }

    /** Строка состояния подписки: адрес, состав и когда её в последний раз трогали. */
    private fun describeSubscriptionState(state: VlessSubscriptionState): String {
        val host = runCatching { java.net.URL(state.url).host }.getOrNull().orEmpty()
        val name = state.title.ifBlank { host.ifBlank { state.url } }
        val checked = if (state.lastCheckedAt > 0L) {
            describeAge(System.currentTimeMillis() - state.lastCheckedAt)
        } else {
            "ещё не проверялась"
        }
        val interval = state.updateIntervalHours.takeIf { it > 0 }?.let { ", раз в $it ч" }.orEmpty()
        val status = state.lastStatus.takeIf { it.isNotBlank() }?.let { ", $it" }.orEmpty()
        return "Подписка: $name\nПрофилей: ${clientData.getVlessProfileLinks().size}\n" +
            "Проверена: $checked$status$interval"
    }

    private fun describeAge(ageMs: Long): String {
        val minutes = ageMs / 60_000L
        return when {
            minutes < 1 -> "только что"
            minutes < 60 -> "$minutes мин назад"
            minutes < 60 * 24 -> "${minutes / 60} ч назад"
            else -> "${minutes / (60 * 24)} дн назад"
        }
    }

    private fun showImportUrlDialog() {
        // Ссылку почти всегда копируют перед открытием диалога, поэтому она и
        // подставляется. Адрес по умолчанию остаётся запасным вариантом, когда в
        // буфере ничего подходящего нет.
        val clipboardUrl = readClipboardSubscriptionUrl()
        val input = EditText(this).apply {
            setText(clipboardUrl.ifBlank { DEFAULT_WARP_SUBSCRIPTION_URL })
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            // Текст выделен целиком: вставка своей ссылки заменяет его, а не
            // приписывается к нему. Иначе загружался чужой список, и импорт трёх
            // профилей AWG выглядел как результат вставленной пользователем ссылки.
            setSelection(0, text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("Вставить из URL-подписки")
            .setView(
                ScrollView(this).apply {
                    setPadding(32, 16, 32, 0)
                    addView(input)
                }
            )
            .setPositiveButton("Загрузить") { _, _ ->
                importConfigsFromUrl(input.text?.toString().orEmpty())
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun renderConfigsAsync(importedOnly: Boolean) {
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                // Профили VLESS лежат в своём хранилище, но в списке импортированных
                // должны стоять рядом с AWG — сгруппированные по протоколу.
                val vlessConfigs = clientData.getVlessProfilesAsConfigs()
                val allConfigs = clientData.getWarpVerifiedMergedConfigs() + vlessConfigs
                val importedCount = allConfigs.count(::isVisibleImportedConfig)
                val builtInItems = sortedVisibleConfigs(allConfigs, importedOnly = false)
                val importedItems = groupImportedByProtocol(
                    sortedVisibleConfigs(allConfigs, importedOnly = true)
                )
                RenderedConfigLists(
                    allConfigs = allConfigs,
                    importedCount = importedCount,
                    builtInItems = builtInItems,
                    importedItems = importedItems,
                    builtInBestAvgPing = calculateBestAvgPing(builtInItems),
                    importedBestAvgPing = calculateBestAvgPing(importedItems),
                )
            }
            val items = if (importedOnly) result.importedItems else result.builtInItems
            val bestAvgPing = if (importedOnly) result.importedBestAvgPing else result.builtInBestAvgPing
            
            if (importedOnly != clientData.isImportedWarpOnlyModeEnabled()) {
                renderConfigs()
                return@launch
            }
            cachedAllConfigs = result.allConfigs
            cachedBuiltInItems = result.builtInItems
            cachedImportedItems = result.importedItems
            cachedImportedCount = result.importedCount
            cachedBuiltInBestAvgPing = result.builtInBestAvgPing
            cachedImportedBestAvgPing = result.importedBestAvgPing
            lastLoadedConfigs = result.allConfigs
            updateImportedOnlyUi(result.importedCount)
            renderConfigItems(items, importedOnly, bestAvgPing)

            val snapshot = lastRenderedDiscoverySnapshot ?: withContext(Dispatchers.IO) {
                clientData.getWarpDiscoverySnapshot()
            }
            if (snapshot != null) {
                updateDiscoveryUi(
                    running = snapshot.running,
                    message = snapshot.message.ifBlank { lastDiscoveryMessage },
                    foundCount = snapshot.foundCount,
                    ordinal = snapshot.ordinal,
                    total = snapshot.total,
                )
            } else {
                updateDiscoveryUi(
                    running = false,
                    message = lastDiscoveryMessage,
                    foundCount = 0,
                    ordinal = 0,
                    total = 0,
                )
            }
        }
    }

    private fun renderConfigs() {
        renderCachedConfigsIfAvailable()
        renderConfigsAsync(clientData.isImportedWarpOnlyModeEnabled())
    }

    private fun updateImportedOnlyUi(importedCount: Int = countVisibleConfigs(importedOnly = true)) {
        val importedOnly = clientData.isImportedWarpOnlyModeEnabled()
        val textHtml = if (importedOnly) {
            "Активно: <font color='#F3C94A'>импортированные</font>"
        } else {
            "Активно: <font color='#50C878'>встроенные</font>"
        }
        btnImportedOnly.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(textHtml, Html.FROM_HTML_MODE_LEGACY)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(textHtml)
        }
        btnImportedOnly.alpha = if (importedOnly && importedCount <= 0) 0.78f else 1f
    }

    private fun showPasteDialog() {
        val clipboardText = runCatching {
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
                .orEmpty()
        }.getOrDefault("")
        val input = EditText(this).apply {
            minLines = 8
            maxLines = 14
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(clipboardText)
            if (clipboardText.isNotBlank()) {
                setSelection(clipboardText.length)
            }
        }
        AlertDialog.Builder(this)
            .setTitle("Вставить из буфера обмена")
            .setView(
                ScrollView(this).apply {
                    setPadding(32, 16, 32, 0)
                    addView(input)
                }
            )
            .setPositiveButton("Сохранить") { _, _ ->
                val result = importConfigsFromText(input.text?.toString().orEmpty())
                if (result.imported > 0) {
                    revealImportedConfigs(result)
                    Toast.makeText(
                        this,
                        "Импортировано: ${result.imported}. Показываю в списке импортированных.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else if (!result.warning.isNullOrBlank()) {
                    Toast.makeText(this, result.warning, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun importConfigsFromUri(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        }.onSuccess { text ->
            val result = importConfigsFromText(text)
            if (result.imported > 0) {
                clientData.setImportedWarpOnlyModeEnabled(true)
                updateImportedOnlyUi()
                renderConfigs()
                Toast.makeText(this, "Импортировано: ${result.imported}", Toast.LENGTH_SHORT).show()
            } else if (!result.warning.isNullOrBlank()) {
                Toast.makeText(this, result.warning, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Подходящих конфигураций не найдено", Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            Toast.makeText(this, "Не удалось прочитать файл", Toast.LENGTH_SHORT).show()
        }
    }

    private fun importConfigsFromArchiveUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = runCatching {
                var importedTotal = 0
                var warning: String? = null
                val entries = readArchiveTextEntries(uri)
                for (entry in entries) {
                    val result = importConfigsFromText(entry.text)
                    importedTotal += result.imported
                    if (warning.isNullOrBlank()) {
                        warning = result.warning
                    }
                }
                ImportResult(
                    imported = importedTotal,
                    warning = warning ?: if (entries.isEmpty()) "Подходящих файлов конфигураций в архиве не найдено" else null,
                )
            }.getOrElse { error ->
                ImportResult(
                    imported = 0,
                    warning = "Не удалось прочитать архив: ${
                        error.message.orEmpty().ifBlank { "неподдерживаемый или повреждённый файл" }
                    }",
                )
            }
            withContext(Dispatchers.Main) {
                if (result.imported > 0) {
                    clientData.setImportedWarpOnlyModeEnabled(true)
                    updateImportedOnlyUi()
                    renderConfigs()
                    Toast.makeText(this@WarpConfigsActivity, "Импортировано из архива: ${result.imported}", Toast.LENGTH_SHORT).show()
                } else if (!result.warning.isNullOrBlank()) {
                    Toast.makeText(this@WarpConfigsActivity, result.warning, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@WarpConfigsActivity, "Подходящих конфигураций не найдено", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Загрузка подписки по адресу.
     *
     * Сначала пробуем потоковым загрузчиком как подписку VLESS: он читает тело по
     * строкам, дедуплицирует по 64-битным хэшам и останавливается на лимите. Раньше
     * тело читалось целиком через `readText()`, и живой агрегатор на 94 МБ означал
     * гарантированный OOM (замеры в docs/vless-reality-plan.md).
     *
     * Если ссылок VLESS в теле нет, это подписка AWG/WARP — они весят килобайты, и
     * для них остаётся прежний разбор текста целиком.
     */
    private fun importConfigsFromUrl(url: String) {
        val cleanUrl = normalizeSubscriptionUrl(url)
        if (cleanUrl.isBlank()) return
        val sourceHost = runCatching { java.net.URL(cleanUrl).host }.getOrNull().orEmpty()
        val progressDialog = showSubscriptionProgressDialog(sourceHost)
        Thread {
            val outcome = VlessSubscriptionManager.refresh(
                context = this,
                url = cleanUrl,
                force = true,
            ) { kept, charsRead ->
                runOnUiThread { progressDialog.update(kept, charsRead) }
            }
            if (outcome is VlessSubscriptionManager.Outcome.Failed ||
                outcome is VlessSubscriptionManager.Outcome.NoVlessFound
            ) {
                // Ссылок VLESS не нашлось — тело перечитываем как обычный текст.
                val text = runCatching {
                    java.net.URL(cleanUrl).openStream().bufferedReader().use { it.readText() }
                }.getOrNull().orEmpty()
                val result = importConfigsFromText(text)
                runOnUiThread {
                    progressDialog.dismiss()
                    finishUrlImport(result, sourceHost)
                }
                return@Thread
            }
            runOnUiThread {
                progressDialog.dismiss()
                clientData.setImportedWarpOnlyModeEnabled(true)
                updateImportedOnlyUi()
                renderConfigs()
                Toast.makeText(this, describeSubscriptionOutcome(outcome, sourceHost), Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private fun finishUrlImport(result: ImportResult, sourceHost: String) {
        if (result.imported > 0) {
            revealImportedConfigs(result)
            // Источник в сообщении не для красоты: если в поле остался адрес по
            // умолчанию, только он и объясняет, откуда взялись чужие профили.
            val suffix = if (sourceHost.isNotBlank()) " с $sourceHost" else ""
            Toast.makeText(
                this,
                "Импортировано (${describeImportResult(result)})$suffix",
                Toast.LENGTH_LONG,
            ).show()
        } else if (!result.warning.isNullOrBlank()) {
            Toast.makeText(this, result.warning, Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Подходящих конфигураций не найдено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun describeSubscriptionOutcome(
        outcome: VlessSubscriptionManager.Outcome,
        sourceHost: String,
    ): String {
        val suffix = if (sourceHost.isNotBlank()) " с $sourceHost" else ""
        return when (outcome) {
            is VlessSubscriptionManager.Outcome.Updated -> buildString {
                append("Подписка$suffix: профилей ${outcome.total}")
                if (outcome.added > 0) append(", добавлено ${outcome.added}")
                if (outcome.removed > 0) append(", удалено ${outcome.removed}")
                val skipped = outcome.stats.describeSkippedProtocols()
                if (skipped.isNotBlank()) append(". Пропущено: $skipped")
                if (outcome.stats.truncated) append(". Список обрезан по лимиту")
            }
            is VlessSubscriptionManager.Outcome.Unchanged ->
                "Подписка$suffix не изменилась, профилей ${outcome.total}"
            is VlessSubscriptionManager.Outcome.Failed ->
                "Подписка$suffix не загрузилась: ${outcome.message}"
            VlessSubscriptionManager.Outcome.NoVlessFound ->
                "По адресу$suffix нет профилей VLESS"
        }
    }

    /** Диалог с живым счётчиком: подписка на сотни тысяч строк идёт десятками секунд. */
    private inner class SubscriptionProgressDialog(sourceHost: String) {
        private val text = TextView(this@WarpConfigsActivity).apply {
            setPadding(48, 40, 48, 40)
            text = if (sourceHost.isBlank()) "Загрузка подписки..." else "Загрузка с $sourceHost..."
        }
        private val dialog = AlertDialog.Builder(this@WarpConfigsActivity)
            .setTitle("Импорт подписки")
            .setView(text)
            .setCancelable(false)
            .create()

        init {
            dialog.show()
        }

        fun update(kept: Int, charsRead: Long) {
            if (!dialog.isShowing) return
            val volume = when {
                charsRead >= 1024L * 1024L -> "%.1f МБ".format(charsRead / 1024.0 / 1024.0)
                else -> "${charsRead / 1024} КБ"
            }
            text.text = "Профилей: $kept\nПрочитано: $volume"
        }

        fun dismiss() {
            if (dialog.isShowing && !isFinishing) {
                runCatching { dialog.dismiss() }
            }
        }
    }

    private fun showSubscriptionProgressDialog(sourceHost: String) =
        SubscriptionProgressDialog(sourceHost)

    /**
     * Раскладывает импортированные профили по протоколам, сохраняя порядок внутри
     * группы. Протоколов пока два, AWG и VLESS, но список открытый: незнакомые
     * движки идут после известных, а не теряются.
     */
    private fun groupImportedByProtocol(items: List<WarpVerifiedConfig>): List<WarpVerifiedConfig> {
        if (items.isEmpty()) return items
        val order = listOf("wireguard", "awg", "masque", "vless")
        return items.sortedBy { config ->
            val index = order.indexOf(config.engine.trim().lowercase(Locale.US))
            if (index >= 0) index else order.size
        }
    }

    /** Ссылка на подписку из буфера обмена или пустая строка. */
    private fun readClipboardSubscriptionUrl(): String {
        val raw = runCatching {
            getSystemService(ClipboardManager::class.java)
                ?.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.coerceToText(this)
                ?.toString()
        }.getOrNull().orEmpty().trim()
        if (raw.isBlank() || raw.length > 2048 || raw.any { it == '\n' || it == '\r' }) return ""
        val lower = raw.lowercase(Locale.US)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return ""
        return raw
    }

    private fun normalizeSubscriptionUrl(url: String): String {
        val clean = url.trim()
        if (clean.isBlank()) return ""
        val githubBlob = Regex(
            "^https://github\\.com/([^/]+)/([^/]+)/(?:blob|raw)/([^/]+)/(.+)$",
            RegexOption.IGNORE_CASE,
        ).matchEntire(clean)
        if (githubBlob != null) {
            val (owner, repo, branch, path) = githubBlob.destructured
            return "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        }
        return clean
    }

    private fun importPendingLocalBatchIfAny() {
        val downloadDir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return
        val pendingFiles = downloadDir
            .listFiles { file ->
                file.isFile &&
                    file.name.startsWith("nova_warp_import", ignoreCase = true) &&
                    file.extension.equals("conf", ignoreCase = true)
            }
            ?.sortedBy { it.lastModified() }
            .orEmpty()
        if (pendingFiles.isEmpty()) return

        var importedTotal = 0
        var warning: String? = null
        pendingFiles.forEach { file ->
            val result = runCatching {
                importConfigsFromText(file.readText())
            }.getOrDefault(ImportResult(0))
            importedTotal += result.imported
            if (warning.isNullOrBlank()) {
                warning = result.warning
            }
            val target = File(file.parentFile, "${file.name}.imported.${System.currentTimeMillis()}")
            runCatching { file.renameTo(target) }
        }
        if (importedTotal > 0) {
            clientData.setImportedWarpOnlyModeEnabled(true)
            updateImportedOnlyUi()
            renderConfigs()
            Toast.makeText(this, "Импортировано локально: $importedTotal", Toast.LENGTH_SHORT).show()
        } else if (!warning.isNullOrBlank()) {
            Toast.makeText(this, warning, Toast.LENGTH_LONG).show()
        }
    }

    private fun importConfigsFromText(raw: String): ImportResult {
        val normalized = raw.replace("\r", "")
        if (normalized.isBlank()) return ImportResult(0)
        var imported = 0
        var warning: String? = null
        // Идентификаторы того, что действительно добавилось: по ним экран потом
        // показывает новую запись. Без этого профиль уезжал в конец списка на
        // двести с лишним строк, и «импортировано» выглядело как «ничего не
        // произошло».
        val newIds = mutableListOf<String>()

        normalized.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("vpn://", ignoreCase = true) }
            .forEach { line ->
                val converted = parseAmneziaVpnUriToImportedConfig(line)
                if (converted != null && clientData.addUserImportedWarpConfig(
                        rawConfig = converted.rawConfig,
                        engine = converted.engine,
                        mode = converted.mode,
                        host = converted.host,
                        port = converted.port,
                        preferredSni = converted.preferredSni,
                    ) != null
                ) {
                    imported += 1
                } else if (warning.isNullOrBlank()) {
                    warning = explainAmneziaVpnImportIssue(line)
                }
            }

        normalized.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("warp://") }
            .forEach { line ->
                val converted = convertWarpUriToImportedConfig(line)
                if (converted != null && clientData.addUserImportedWarpConfig(
                        rawConfig = converted.rawConfig,
                        engine = converted.engine,
                        mode = converted.mode,
                        host = converted.host,
                        port = converted.port,
                        preferredSni = converted.preferredSni,
                    ) != null
                ) {
                    imported += 1
                }
            }

        val hostBlocks = normalized
            .split(Regex("\\n\\s*\\n"))
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("HOST=", ignoreCase = true) }

        fun rememberImported(converted: ParsedUserWarpConfig?) {
            val draft = converted ?: return
            clientData.addUserImportedWarpConfig(
                rawConfig = draft.rawConfig,
                engine = draft.engine,
                mode = draft.mode,
                host = draft.host,
                port = draft.port,
                preferredSni = draft.preferredSni,
            )?.let { added ->
                newIds += added.id
                imported += 1
            }
        }

        for (block in hostBlocks) {
            rememberImported(convertHostBlockToImportedConfig(block))
        }

        parseClassicWireGuardBlocks(normalized).forEach { block ->
            rememberImported(convertClassicWireGuardBlockToImportedConfig(block))
        }

        parseClashWarpConfigs(normalized).forEach(::rememberImported)

        val vlessLinks = normalized.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("vless://", ignoreCase = true) }
            .filter { VlessConfig.parse(it) != null }
            .toList()
        val addedVlessLinks = clientData.addVlessProfileLinks(vlessLinks)
        val importedVless = addedVlessLinks.size
        newIds += addedVlessLinks.mapNotNull { link ->
            VlessConfig.parse(link)?.identity?.let { "${ClientData.VLESS_CONFIG_ID_PREFIX}$it" }
        }
        if (vlessLinks.isNotEmpty() && importedVless == 0 && imported == 0 && warning.isNullOrBlank()) {
            warning = if (clientData.getVlessProfileLinks().size >= ClientData.MAX_VLESS_PROFILES) {
                "Достигнут предел ${ClientData.MAX_VLESS_PROFILES} профилей VLESS."
            } else {
                "Все профили VLESS из этой подписки уже импортированы."
            }
        }

        return ImportResult(
            imported = imported + importedVless,
            importedAwg = imported,
            importedVless = importedVless,
            warning = warning?.takeIf { imported + importedVless == 0 },
            newIds = newIds,
        )
    }

    /** Короткая сводка по протоколам для сообщения об импорте. */
    private fun describeImportResult(result: ImportResult): String {
        val parts = buildList {
            if (result.importedAwg > 0) add("AWG: ${result.importedAwg}")
            if (result.importedVless > 0) add("VLESS: ${result.importedVless}")
        }
        return if (parts.isEmpty()) "${result.imported}" else parts.joinToString(", ")
    }

    private fun readArchiveTextEntries(uri: Uri): List<ArchiveTextEntry> {
        val tempFile = File.createTempFile("nova_import_", ".archive", cacheDir)
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read.toLong()
                        if (copied > MAX_ARCHIVE_BYTES) {
                            throw IllegalArgumentException("архив слишком большой")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return emptyList()
            val extension = uri.lastPathSegment
                ?.substringAfterLast('.', "")
                ?.lowercase(Locale.US)
                .orEmpty()
            when (extension) {
                "zip" -> readZipTextEntries(tempFile)
                "7z" -> readSevenZipTextEntries(tempFile)
                "rar" -> readStreamArchiveTextEntries(tempFile)
                else -> tryReadArchiveEntries { readZipTextEntries(tempFile) }
                    .ifEmpty { tryReadArchiveEntries { readSevenZipTextEntries(tempFile) } }
                    .ifEmpty { tryReadArchiveEntries { readStreamArchiveTextEntries(tempFile) } }
            }
        } finally {
            runCatching { tempFile.delete() }
        }
    }

    private fun tryReadArchiveEntries(reader: () -> List<ArchiveTextEntry>): List<ArchiveTextEntry> {
        return runCatching(reader).getOrDefault(emptyList())
    }

    private fun readZipTextEntries(file: File): List<ArchiveTextEntry> {
        return ZipInputStream(BufferedInputStream(file.inputStream())).use { zip ->
            val entries = mutableListOf<ArchiveTextEntry>()
            var entry = zip.nextEntry
            while (entry != null && entries.size < MAX_ARCHIVE_ENTRIES) {
                if (!entry.isDirectory && isConfigArchiveEntryName(entry.name)) {
                    val text = readLimitedText(zip)
                    if (text.isNotBlank()) entries += ArchiveTextEntry(entry.name, text)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
            entries
        }
    }

    private fun readSevenZipTextEntries(file: File): List<ArchiveTextEntry> {
        return SevenZFile(file).use { sevenZ ->
            val entries = mutableListOf<ArchiveTextEntry>()
            var entry = sevenZ.nextEntry
            while (entry != null && entries.size < MAX_ARCHIVE_ENTRIES) {
                if (!entry.isDirectory && isConfigArchiveEntryName(entry.name) && entry.size in 1..MAX_CONFIG_TEXT_BYTES) {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var remaining = entry.size
                    while (remaining > 0L) {
                        val read = sevenZ.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        remaining -= read.toLong()
                    }
                    val text = output.toString(Charsets.UTF_8.name())
                    if (text.isNotBlank()) entries += ArchiveTextEntry(entry.name, text)
                }
                entry = sevenZ.nextEntry
            }
            entries
        }
    }

    private fun readStreamArchiveTextEntries(file: File): List<ArchiveTextEntry> {
        return BufferedInputStream(file.inputStream()).use { input ->
            @Suppress("UNCHECKED_CAST")
            val archive = ArchiveStreamFactory().createArchiveInputStream(input) as ArchiveInputStream<out ArchiveEntry>
            archive.use {
                buildList {
                    var entry = archive.nextEntry
                    while (entry != null && size < MAX_ARCHIVE_ENTRIES) {
                        if (!entry.isDirectory && archive.canReadEntryData(entry) && isConfigArchiveEntryName(entry.name)) {
                            val text = readLimitedText(archive)
                            if (text.isNotBlank()) add(ArchiveTextEntry(entry.name, text))
                        }
                        entry = archive.nextEntry
                    }
                }
            }
        }
    }

    private fun readLimitedText(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read.toLong()
            if (copied > MAX_CONFIG_TEXT_BYTES) {
                throw IllegalArgumentException("файл конфигурации в архиве слишком большой")
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun isConfigArchiveEntryName(name: String?): Boolean {
        val clean = name?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase(Locale.US).orEmpty()
        if (clean.isBlank() || clean.startsWith(".")) return false
        return clean.endsWith(".conf") ||
            clean.endsWith(".txt") ||
            clean.endsWith(".yaml") ||
            clean.endsWith(".yml") ||
            clean.endsWith(".json")
    }

    private fun parseClashWarpConfigs(raw: String): List<ParsedUserWarpConfig> {
        if (!raw.contains("proxies:", ignoreCase = true) || !raw.contains("type:", ignoreCase = true)) return emptyList()
        val anchors = parseClashAnchors(raw)
        return extractClashProxyBlocks(raw)
            .mapNotNull { block ->
                val fields = parseClashYamlFields(block).toMutableMap()
                val mergeAnchor = fields["<<"]?.trim()?.removePrefix("*").orEmpty()
                if (mergeAnchor.isNotBlank()) {
                    anchors[mergeAnchor]?.let { base ->
                        val merged = base.toMutableMap()
                        merged.putAll(fields)
                        fields.clear()
                        fields.putAll(merged)
                    }
                }
                convertClashProxyFields(fields)
            }
            .distinctBy { "${it.host}:${it.port}:${it.rawConfig.hashCode()}" }
    }

    private fun parseClashAnchors(raw: String): Map<String, Map<String, String>> {
        val lines = raw.replace("\r", "").lines()
        val anchors = mutableMapOf<String, Map<String, String>>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val match = Regex("^\\S[^:]*:\\s*&([A-Za-z0-9_-]+)\\s*$").find(line)
            if (match != null) {
                val anchorName = match.groupValues[1]
                val block = StringBuilder()
                index += 1
                while (index < lines.size && (lines[index].isBlank() || lines[index].firstOrNull()?.isWhitespace() == true)) {
                    block.appendLine(lines[index])
                    index += 1
                }
                anchors[anchorName] = parseClashYamlFields(block.toString())
                continue
            }
            index += 1
        }
        return anchors
    }

    private fun extractClashProxyBlocks(raw: String): List<String> {
        val lines = raw.replace("\r", "").lines()
        val start = lines.indexOfFirst { it.trim().equals("proxies:", ignoreCase = true) }
        if (start < 0) return emptyList()
        val blocks = mutableListOf<StringBuilder>()
        for (index in start + 1 until lines.size) {
            val line = lines[index]
            if (line.isNotBlank() && line.firstOrNull()?.isWhitespace() != true && !line.trimStart().startsWith("-")) {
                break
            }
            if (line.trimStart().startsWith("- ")) {
                blocks += StringBuilder()
            }
            if (blocks.isNotEmpty()) {
                blocks.last().appendLine(line)
            }
        }
        return blocks.map { it.toString() }.filter { it.isNotBlank() }
    }

    private fun parseClashYamlFields(block: String): Map<String, String> {
        val fields = linkedMapOf<String, String>()
        var nestedPrefix = ""
        block.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val normalized = line.removePrefix("-").trim()
            if (!normalized.contains(":")) return@forEach
            val key = normalized.substringBefore(':').trim().lowercase(Locale.US)
            val value = normalized.substringAfter(':', "").trim()
            if (value.isBlank()) {
                nestedPrefix = key
                return@forEach
            }
            val cleanValue = cleanClashYamlValue(value)
            fields[key] = cleanValue
            if (nestedPrefix == "amnezia-wg-option") {
                fields["awg.$key"] = cleanValue
            }
        }
        return fields
    }

    private fun cleanClashYamlValue(value: String): String {
        var clean = value.substringBefore(" #").trim()
        if (clean.startsWith("[") && clean.endsWith("]")) {
            clean = clean.removePrefix("[").removeSuffix("]")
                .split(',')
                .joinToString(", ") { it.trim().trim('"', '\'') }
        }
        return clean.trim().trim('"', '\'')
    }

    private fun convertClashProxyFields(fields: Map<String, String>): ParsedUserWarpConfig? {
        val type = fields["type"]?.lowercase(Locale.US).orEmpty()
        val server = fields["server"].orEmpty().removePrefix("[").removeSuffix("]")
        val port = fields["port"]?.toIntOrNull() ?: -1
        if (server.isBlank() || port !in 1..65535) return null
        val preferredSni = normalizePreferredSni(
            fields["sni"]
                ?: fields["servername"]
                ?: fields["server-name"]
                ?: fields["fake-sni"]
        )
        if (type == "masque") {
            val rawConfig = buildString {
                appendLine("HOST=$server")
                appendLine("PORT=$port")
                appendLine("ENGINE=masque")
                appendLine("MODE=${fields["mode"].orEmpty().ifBlank { "MASQUE-ZT" }}")
                appendLine("SOURCE=clash-masque")
                if (preferredSni.isNotBlank()) {
                    appendLine("PREFERRED_SNI=$preferredSni")
                }
            }.trim()
            return ParsedUserWarpConfig(
                engine = "masque",
                mode = normalizeImportedMode(
                    requestedMode = fields["mode"].orEmpty().ifBlank { "masque" },
                    engine = "masque",
                    port = port,
                    rawConfig = rawConfig,
                ),
                host = server,
                port = port,
                rawConfig = rawConfig,
                preferredSni = preferredSni,
            )
        }
        if (type != "wireguard") return null
        val privateKey = fields["private-key"].orEmpty()
        val publicKey = fields["public-key"].orEmpty()
        if (privateKey.isBlank() || publicKey.isBlank() || server.isBlank() || port !in 1..65535) return null
        val address = listOf(fields["ip"].orEmpty(), fields["ipv6"].orEmpty())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { fields["address"].orEmpty() }
        if (address.isBlank()) return null
        val dns = fields["dns"].orEmpty().ifBlank {
            "1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001"
        }
        val allowedIps = fields["allowed-ips"].orEmpty().ifBlank { "0.0.0.0/0, ::/0" }
        val mtu = fields["mtu"]?.toIntOrNull()?.takeIf { it in 576..9000 } ?: 1280
        val rawConfig = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = $address")
            appendLine("DNS = $dns")
            appendLine("MTU = $mtu")
            fields["reserved"]?.takeIf { it.isNotBlank() }?.let { appendLine("Reserved = $it") }
            appendClashAwgOptions(fields)
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $publicKey")
            appendLine("AllowedIPs = $allowedIps")
            appendLine("Endpoint = ${formatEndpoint(server, port)}")
        }.trim()
        return ParsedUserWarpConfig(
            engine = "wireguard",
            mode = normalizeImportedMode(
                    requestedMode = fields["mode"].orEmpty(),
                    engine = "wireguard",
                    port = port,
                    rawConfig = rawConfig,
                ),
            host = server,
            port = port,
            rawConfig = rawConfig,
            preferredSni = preferredSni,
        )
    }

    private fun StringBuilder.appendClashAwgOptions(fields: Map<String, String>) {
        val orderedKeys = listOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")
        orderedKeys.forEach { key ->
            val value = fields["awg.$key"] ?: fields[key]
            if (!value.isNullOrBlank()) {
                appendLine("${formatAwgOptionName(key)} = $value")
            }
        }
    }

    private fun formatAwgOptionName(key: String): String {
        return when (key.lowercase(Locale.US)) {
            "jc" -> "Jc"
            "jmin" -> "Jmin"
            "jmax" -> "Jmax"
            else -> key.uppercase(Locale.US)
        }
    }

    private fun explainAmneziaVpnImportIssue(rawUri: String): String {
        val fallback =
            "Не удалось разобрать ключ Amnezia vpn://. " +
                "Попробуй экспортировать из Amnezia обычный AmneziaWG/WireGuard .conf и импортировать его."
        return runCatching {
            val payload = decodeAmneziaVpnUriPayload(rawUri) ?: return@runCatching fallback
            val host = payload.optString("hostName").trim().ifBlank { "сервер" }
            val container = payload.optString("defaultContainer").trim().ifBlank { "amnezia-awg2" }
            "Не удалось извлечь AWG/WireGuard-конфигурацию из ключа Amnezia vpn:// для $host ($container). " +
                "Попробуй импорт через обычный .conf."
        }.getOrDefault(fallback)
    }

    private fun decodeAmneziaVpnUriPayload(rawUri: String): JSONObject? {
        val encoded = rawUri.trim().removePrefix("vpn://").trim()
        if (encoded.isBlank()) return null
        val normalized = buildString(encoded.length + 4) {
            encoded.forEach { ch ->
                append(
                    when (ch) {
                        '-' -> '+'
                        '_' -> '/'
                        else -> ch
                    }
                )
            }
            while (length % 4 != 0) {
                append('=')
            }
        }
        val packed = Base64.getDecoder().decode(normalized)
        if (packed.size <= 5) return null
        val compressed = packed.copyOfRange(4, packed.size)
        val jsonText = InflaterInputStream(ByteArrayInputStream(compressed)).bufferedReader().use { it.readText() }
        if (jsonText.isBlank()) return null
        return JSONObject(jsonText)
    }

    private fun parseAmneziaVpnUriToImportedConfig(rawUri: String): ParsedUserWarpConfig? {
        return runCatching {
            val payload = decodeAmneziaVpnUriPayload(rawUri) ?: return@runCatching null
            val topLevelDns1 = payload.optString("dns1").trim()
            val topLevelDns2 = payload.optString("dns2").trim()
            val defaultContainer = payload.optString("defaultContainer").trim()
            val containers = payload.optJSONArray("containers")
            if (containers == null || containers.length() == 0) return@runCatching null

            fun normalizedDnsCsv(primary: String, secondary: String): String {
                return listOf(primary.trim(), secondary.trim())
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
            }

            fun finalizeConfig(rawConfig: String): String {
                val dnsCsv = normalizedDnsCsv(topLevelDns1, topLevelDns2)
                return rawConfig
                    .replace("\$PRIMARY_DNS", topLevelDns1)
                    .replace("\$SECONDARY_DNS", topLevelDns2)
                    .replace("DNS = ,", "DNS =")
                    .lineSequence()
                    .map { line ->
                        if (line.trim().startsWith("DNS =", ignoreCase = true) && dnsCsv.isNotBlank()) {
                            "DNS = $dnsCsv"
                        } else {
                            line
                        }
                    }
                    .joinToString("\n")
                    .trim()
            }

            for (index in 0 until containers.length()) {
                val entry = containers.optJSONObject(index) ?: continue
                val containerName = entry.optString("container").trim()
                val awg = entry.optJSONObject("awg") ?: continue
                if (defaultContainer.isNotBlank() && containerName.isNotBlank() && containerName != defaultContainer) {
                    continue
                }

                val lastConfigRaw = awg.optString("last_config").trim()
                val lastConfigJson = runCatching { JSONObject(lastConfigRaw) }.getOrNull()
                val configText = lastConfigJson?.optString("config").orEmpty().trim()
                val normalizedConfig = finalizeConfig(configText)
                if (normalizedConfig.isBlank()) continue

                val converted = convertClassicWireGuardBlockToImportedConfig(normalizedConfig)
                    ?: convertHostBlockToImportedConfig(normalizedConfig)
                if (converted != null) {
                    return@runCatching converted
                }
            }

            null
        }.getOrNull()
    }

    private fun convertWarpUriToImportedConfig(rawUri: String): ParsedUserWarpConfig? {
        return runCatching {
            val uri = Uri.parse(rawUri.trim())
            val host = uri.host?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
            val port = uri.port
            if (host.isBlank() || port !in 1..65535) return@runCatching null
            val ifp = uri.getQueryParameter("ifp").orEmpty()
            val ifps = uri.getQueryParameter("ifps").orEmpty()
            val ifpd = uri.getQueryParameter("ifpd").orEmpty()
            val ifpm = uri.getQueryParameter("ifpm").orEmpty()
            val preferredSni = normalizePreferredSni(
                uri.getQueryParameter("sni")
                    ?: uri.getQueryParameter("fake_host")
                    ?: uri.getQueryParameter("host")
            )
            val strategy = buildString {
                append("warp-uri")
                val details = listOf(
                    ifp.takeIf { it.isNotBlank() }?.let { "ifp=$it" },
                    ifps.takeIf { it.isNotBlank() }?.let { "ifps=$it" },
                    ifpd.takeIf { it.isNotBlank() }?.let { "ifpd=$it" },
                    ifpm.takeIf { it.isNotBlank() }?.let { "ifpm=$it" },
                ).filterNotNull()
                if (details.isNotEmpty()) {
                    append(" (")
                    append(details.joinToString(", "))
                    append(")")
                }
            }
            val rawConfig = buildString {
                appendLine("HOST=$host")
                appendLine("PORT=$port")
                appendLine("PROTOCOL=WIREGUARD")
                appendLine("STRATEGY=$strategy")
                appendLine("SOURCE=imported-uri")
                if (preferredSni.isNotBlank()) {
                    appendLine("PREFERRED_SNI=$preferredSni")
                }
            }.trim()
        ParsedUserWarpConfig(
            engine = "wireguard",
            mode = normalizeImportedMode(
                        requestedMode = strategy,
                        engine = "wireguard",
                        port = port,
                        rawConfig = rawConfig,
                    ),
            host = host,
            port = port,
            rawConfig = rawConfig,
            preferredSni = preferredSni,
        )
        }.getOrNull()
    }

    private fun convertHostBlockToImportedConfig(rawBlock: String): ParsedUserWarpConfig? {
        val fields = rawBlock.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.contains("=") }
            .associate { line ->
                val key = line.substringBefore('=').trim().uppercase(Locale.US)
                val value = line.substringAfter('=', "").trim()
                key to value
            }
        val host = fields["HOST"]?.removePrefix("[")?.removeSuffix("]").orEmpty()
        val port = fields["PORT"]?.toIntOrNull() ?: -1
        if (host.isBlank() || port !in 1..65535) return null
        val engine = when (fields["ENGINE"]?.ifBlank { fields["PROTOCOL"].orEmpty() }?.trim()?.lowercase(Locale.US)) {
            "masque" -> "masque"
            else -> "wireguard"
        }
        val requestedMode = fields["MODE"].orEmpty().ifBlank { fields["STRATEGY"].orEmpty() }
        val preferredSni = normalizePreferredSni(
            fields["PREFERRED_SNI"]
                ?: fields["SNI"]
                ?: fields["FAKE_HOST"]
                ?: fields["NOVAFAKEHOST"]
                ?: fields["NOVA_FAKE_HOST"]
        )
        return ParsedUserWarpConfig(
            engine = engine,
            mode = normalizeImportedMode(
                    requestedMode = requestedMode,
                    engine = engine,
                    port = port,
                    rawConfig = rawBlock.trim(),
                ),
            host = host,
            port = port,
            rawConfig = rawBlock.trim(),
            preferredSni = preferredSni,
        )
    }

    private fun parseClassicWireGuardBlocks(raw: String): List<String> {
        return Regex("(?ms)^\\[Interface\\].*?(?=^\\[Interface\\]|\\z)")
            .findAll(raw)
            .map { it.value.trim() }
            .filter { block ->
                block.contains("[Peer]", ignoreCase = true) &&
                    block.contains("Endpoint", ignoreCase = true) &&
                    !block.contains("HOST=", ignoreCase = true)
            }
            .toList()
    }

    private fun getBestBuiltInWarpConfig(): WarpVerifiedConfig? {
        val builtInConfigs = clientData.getWarpVerifiedMergedConfigs()
            .filter { !it.userImported && !it.manual }
            .filter { isBuiltInAwgConfig(it) }
        if (builtInConfigs.isEmpty()) return null
        return builtInConfigs.sortedWith(
            compareByDescending<WarpVerifiedConfig> { it.qualityPingSuccesses }
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
        ).firstOrNull()
    }

    private fun isBuiltInAwgConfig(config: WarpVerifiedConfig): Boolean {
        if (!config.engine.equals("wireguard", ignoreCase = true)) return false
        return config.mode.contains("awg", ignoreCase = true) || hasAwgMarkers(config.rawConfig)
    }

    private fun hasAwgMarkers(rawConfig: String): Boolean {
        return Regex("(?im)^S[1-4]\\s*=").containsMatchIn(rawConfig) ||
            Regex("(?im)^H[1-4]\\s*=").containsMatchIn(rawConfig) ||
            Regex("(?im)^J(c|min|max)\\s*=").containsMatchIn(rawConfig)
    }

    private fun buildBuiltInAwgCloneForEndpoint(host: String, port: Int): BuiltInAwgClone? {
        if (host.isBlank() || port !in 1..65535) return null
        val bestBuiltIn = getBestBuiltInWarpConfig() ?: return null
        val clonedRaw = replacePeerEndpoint(bestBuiltIn.rawConfig, host, port) ?: return null
        return BuiltInAwgClone(
            rawConfig = clonedRaw,
            mode = bestBuiltIn.mode.ifBlank {
                normalizeImportedMode(
                    requestedMode = "",
                    engine = "wireguard",
                    port = port,
                    rawConfig = clonedRaw,
                )
            },
        )
    }

    private fun replacePeerEndpoint(rawConfig: String, host: String, port: Int): String? {
        val configMap = parseWgConfig(rawConfig)
        val newConfigMap = configMap.mapValues { it.value.toMutableMap() }.toMutableMap()
        val peer = newConfigMap["Peer"] ?: return null
        peer["Endpoint"] = formatEndpoint(host, port)
        return renderWgConfig(newConfigMap)
    }

    private fun extractAwgParameters(rawConfig: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        var inInterface = false
        rawConfig.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val section = trimmed.removePrefix("[").removeSuffix("]").trim().lowercase(Locale.US)
                inInterface = (section == "interface")
            } else if (inInterface && '=' in trimmed) {
                val key = trimmed.substringBefore('=').trim()
                val value = trimmed.substringAfter('=').trim()
                val keyLower = key.lowercase(Locale.US)
                if (keyLower.matches(Regex("^(jc|jmin|jmax|s1|s2|s3|s4|h1|h2|h3|h4)$"))) {
                    params[key] = value
                }
            }
        }
        return params
    }

    private fun injectAwgParameters(rawConfig: String, params: Map<String, String>): String {
        val lines = rawConfig.replace("\r", "").split("\n")
        val output = mutableListOf<String>()
        var interfaceFound = false
        var injected = false

        for (line in lines) {
            output.add(line)
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val section = trimmed.removePrefix("[").removeSuffix("]").trim().lowercase(Locale.US)
                if (section == "interface") {
                    interfaceFound = true
                } else if (interfaceFound && !injected) {
                    val lastLine = output.removeAt(output.size - 1)
                    params.forEach { (k, v) ->
                        output.add("$k = $v")
                    }
                    output.add(lastLine)
                    injected = true
                }
            }
        }
        if (interfaceFound && !injected) {
            params.forEach { (k, v) ->
                output.add("$k = $v")
            }
        }
        return output.joinToString("\n")
    }

    private fun parseWgConfig(config: String): Map<String, Map<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        var currentSection = ""
        config.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.removePrefix("[").removeSuffix("]").trim()
                result.putIfAbsent(currentSection, mutableMapOf())
            } else if ('=' in trimmed && currentSection.isNotEmpty()) {
                val key = trimmed.substringBefore('=').trim()
                val value = trimmed.substringAfter('=').trim()
                result[currentSection]?.put(key, value)
            }
        }
        return result
    }

    private fun renderWgConfig(configMap: Map<String, Map<String, String>>): String {
        val sb = StringBuilder()
        if (configMap.containsKey("Interface")) {
            sb.appendLine("[Interface]")
            configMap["Interface"]!!.forEach { (k, v) -> sb.appendLine("$k = $v") }
        }
        if (configMap.containsKey("Peer")) {
            sb.appendLine()
            sb.appendLine("[Peer]")
            configMap["Peer"]!!.forEach { (k, v) -> sb.appendLine("$k = $v") }
        }
        configMap.filterKeys { it != "Interface" && it != "Peer" }.forEach { (section, values) ->
            sb.appendLine()
            sb.appendLine("[$section]")
            values.forEach { (k, v) -> sb.appendLine("$k = $v") }
        }
        return sb.toString().trim()
    }

    private fun cloneBuiltInAwgWithImportedEndpoint(importedConfig: String, builtInConfig: String): String? {
        val importedMap = parseWgConfig(importedConfig)
        val importedPeer = importedMap["Peer"] ?: return null
        val endpoint = importedPeer["Endpoint"] ?: return null
        val host = parseEndpointHost(endpoint)
        val port = parseEndpointPort(endpoint)
        if (host.isBlank() || port !in 1..65535) return null
        return replacePeerEndpoint(builtInConfig, host, port)
    }

    private fun convertClassicWireGuardBlockToImportedConfig(rawBlock: String): ParsedUserWarpConfig? {
        val finalRaw = rawBlock.trim()

        var currentSection = ""
        val interfaceFields = linkedMapOf<String, String>()
        val peerFields = linkedMapOf<String, String>()
        finalRaw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.isBlank() -> Unit
                trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                    currentSection = trimmed.removePrefix("[").removeSuffix("]").trim().lowercase(Locale.US)
                }
                '=' in trimmed -> {
                    val key = trimmed.substringBefore('=').trim().lowercase(Locale.US)
                    val value = trimmed.substringAfter('=', "").trim()
                    when (currentSection) {
                        "interface" -> interfaceFields[key] = value
                        "peer" -> peerFields[key] = value
                    }
                }
            }
        }
        val endpoint = peerFields["endpoint"].orEmpty().trim()
        val host = parseEndpointHost(endpoint)
        val port = parseEndpointPort(endpoint)
        if (host.isBlank() || port !in 1..65535) return null
        val preferredSni = normalizePreferredSni(
            interfaceFields["novafakehost"]
                ?: interfaceFields["preferred_sni"]
                ?: interfaceFields["sni"]
                ?: peerFields["novafakehost"]
                ?: peerFields["preferred_sni"]
                ?: peerFields["sni"]
        )
        return ParsedUserWarpConfig(
            engine = "wireguard",
            mode = normalizeImportedMode(
                requestedMode = "",
                engine = "wireguard",
                port = port,
                rawConfig = finalRaw,
            ),
            host = host,
            port = port,
            rawConfig = finalRaw,
            preferredSni = preferredSni,
        )
    }

    private fun normalizePreferredSni(value: String?): String {
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

    private fun normalizeImportedMode(
        requestedMode: String,
        engine: String,
        port: Int,
        rawConfig: String,
    ): String {
        val normalizedRequested = requestedMode.trim()
        val requestedToken = normalizedRequested.lowercase(Locale.US)
        val supportedWireGuardModes = setOf(
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
        if (requestedToken in supportedWireGuardModes) {
            return requestedToken
        }
        if (requestedToken == "masque-consumer") return "MASQUE-CONSUMER"
        if (requestedToken == "masque-zt") return "MASQUE-ZT"
        if (requestedToken.contains("exact")) return "warp-awg-exact"
        if (requestedToken.contains("v2")) return "warp-awg-v2"
        if (requestedToken.contains("lite")) return "warp-awg-lite"
        if (
            requestedToken.startsWith("quic-") ||
            requestedToken.contains("fake") ||
            requestedToken.contains("obfs") ||
            requestedToken.contains("random") ||
            requestedToken.contains("trick") ||
            requestedToken.contains("chat") ||
            requestedToken.contains("dnsmix") ||
            requestedToken.contains("quicmix")
        ) return "warp-awg"
        if (requestedToken.contains("masque")) {
            return if (port == 500 || port == 4500) "MASQUE-CONSUMER" else "MASQUE-ZT"
        }
        if (engine.equals("masque", ignoreCase = true)) {
            return if (port == 500 || port == 4500) "MASQUE-CONSUMER" else "MASQUE-ZT"
        }
        val lowered = rawConfig.lowercase(Locale.US)
        val hasCpsLines = Regex("(?im)^I[1-5]\\s*=").containsMatchIn(rawConfig)
        val hasAwg2Markers =
            Regex("(?im)^S[1-4]\\s*=").containsMatchIn(rawConfig) ||
                Regex("(?im)^H[1-4]\\s*=").containsMatchIn(rawConfig) ||
                Regex("(?im)^J(c|min|max)\\s*=").containsMatchIn(rawConfig)
        return when {
            hasCpsLines || hasAwg2Markers -> "warp-awg-exact"
            lowered.contains("engage.cloudflareclient.com") -> "warp-awg-lite"
            port == 500 || port == 1701 || port == 4500 -> "warp-awg-lite"
            else -> "warp-awg"
        }
    }

    private fun shareCurrentConfig() {
        val current = clientData.getWarpVerifiedMergedConfigs().firstOrNull { isCurrentConfigPublic(it) }
        if (current == null) {
            Toast.makeText(this, "Текущая конфигурация не найдена", Toast.LENGTH_SHORT).show()
            return
        }
        shareText(renderConfigForDisplay(current), "Nova WARP config")
    }

    private fun shareAllConfigs() {
        val items = clientData.getWarpVerifiedMergedConfigs()
        if (items.isEmpty()) {
            Toast.makeText(this, "Список конфигураций пуст", Toast.LENGTH_SHORT).show()
            return
        }
        val payload = items.joinToString("\n\n") { renderConfigForDisplay(it) }
        shareText(payload, "Nova WARP configs")
    }

    private fun shareText(text: String, subject: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, subject)
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                "Поделиться"
            )
        )
    }

    private fun seedCurrentSuccessIfNeeded() {
        val storedConfig = clientData.getConfig() ?: return
        val mode = clientData.getLastSuccessMode().trim().ifBlank {
            clientData.getLastSuccessProtocol().trim().ifBlank { "plain" }
        }
        val port = clientData.getLastSuccessPort().takeIf { it in 1..65535 } ?: parseEndpointPort(storedConfig.peerEndpoint)
        if (mode.isBlank() || port !in 1..65535) return

        val endpointHost = clientData.getLastSuccessEndpoint()
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.ifBlank { null }
            ?: parseEndpointHost(storedConfig.peerEndpoint)
        if (endpointHost.isNullOrBlank()) return

        if (clientData.hasWarpVerifiedConfig(mode, endpointHost, port)) return

        val engine = if (
            clientData.getLastSuccessProtocol().equals("MASQUE", ignoreCase = true) ||
            mode.lowercase().startsWith("masque")
        ) {
            "masque"
        } else {
            "wireguard"
        }

        val rawConfig = buildString {
            appendLine("HOST=$endpointHost")
            appendLine("PORT=$port")
            appendLine("ENGINE=$engine")
            appendLine("MODE=$mode")
            appendLine("SOURCE=last-success")
        }.trim()

        clientData.upsertWarpVerifiedConfig(
            engine = engine,
            mode = mode,
            host = endpointHost,
            port = port,
            endpointSource = "last-success",
            rawConfig = rawConfig,
            manual = false,
        )
    }

    private fun parseEndpointHost(endpoint: String): String {
        val value = stripInlineConfigComment(endpoint)
        if (value.startsWith("[") && value.contains("]")) {
            return value.substring(1, value.indexOf(']'))
        }
        return if (value.count { it == ':' } == 1) {
            value.substringBeforeLast(':')
        } else {
            value
        }.trim().removePrefix("[").removeSuffix("]")
    }

    private fun formatEndpoint(host: String, port: Int): String {
        return if (host.contains(':') && !host.startsWith("[")) {
            "[$host]:$port"
        } else {
            "$host:$port"
        }
    }

    private fun parseEndpointPort(endpoint: String): Int {
        val value = stripInlineConfigComment(endpoint)
        return when {
            value.startsWith("[") && value.contains("]:") -> value.substringAfter("]:", "").toIntOrNull() ?: -1
            value.count { it == ':' } == 1 -> value.substringAfterLast(':', "").toIntOrNull() ?: -1
            else -> -1
        }
    }

    private fun stripInlineConfigComment(value: String): String {
        return value.substringBefore('#').trim()
    }

    private fun renderConfigForDisplay(item: WarpVerifiedConfig): String {
        val learnedPorts = item.preferredPorts
            .map { it.port }
            .let { localPorts ->
                localPorts + clientData.getPreferredWarpPortsFor(
                    engine = item.engine,
                    mode = item.mode,
                    host = item.host,
                    scope = item.scope,
                    limit = 13,
                )
            }
            .filter { it in 1..65535 && it != item.port }
            .distinct()
            .take(12)
        if (item.manual) return item.rawConfig
        if (item.userImported) {
            val raw = item.rawConfig.trim()
            val hasPreferredSni = raw.contains("PREFERRED_SNI", ignoreCase = true)
            val hasPreferredPorts = raw.contains("PREFERRED_PORTS", ignoreCase = true)
            if (
                (item.preferredSni.isBlank() || hasPreferredSni) &&
                (learnedPorts.isEmpty() || hasPreferredPorts)
            ) {
                return raw
            }
            return buildString {
                append(raw)
                if (item.preferredSni.isNotBlank() && !hasPreferredSni) {
                    appendLine()
                    append("PREFERRED_SNI=${item.preferredSni}")
                }
                if (learnedPorts.isNotEmpty() && !hasPreferredPorts) {
                    appendLine()
                    append("PREFERRED_PORTS=${learnedPorts.joinToString(",")}")
                }
            }
        }
        return buildString {
            appendLine("HOST=${item.host}")
            appendLine("PORT=${item.port}")
            appendLine("PROTOCOL=${item.engine.uppercase()}")
            appendLine("STRATEGY=${normalizeModeForDisplay(item.rawConfig, item.mode)}")
            if (item.endpointSource.isNotBlank()) {
                appendLine("SOURCE=${item.endpointSource}")
            }
            if (item.preferredSni.isNotBlank()) {
                appendLine("PREFERRED_SNI=${item.preferredSni}")
            }
            if (learnedPorts.isNotEmpty()) {
                appendLine("PREFERRED_PORTS=${learnedPorts.joinToString(",")}")
            }
        }.trim()
    }

    fun renderConfigForDisplayPublic(config: WarpVerifiedConfig): String {
        return config.rawConfig.ifBlank {
            buildString {
                appendLine("[Interface]")
                appendLine("PrivateKey = <hidden>")
                if (config.port > 0) {
                    appendLine("Endpoint = ${config.host}:${config.port}")
                }
                if (config.preferredSni.isNotBlank()) {
                    appendLine("SNI = ${config.preferredSni}")
                }
            }
        }.trim()
    }

    fun isCurrentConfigPublic(item: WarpVerifiedConfig): Boolean {
        if (item.manual) return false
        val currentMode = clientData.getLastSuccessMode().trim()
        val storedConfig = clientData.getConfig()
        val currentPort = clientData.getLastSuccessPort().takeIf { it in 1..65535 }
            ?: storedConfig?.peerEndpoint?.let(::parseEndpointPort)
            ?: -1
        val currentHost = clientData.getLastSuccessEndpoint()
            ?.trim()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.ifBlank { storedConfig?.peerEndpoint?.let(::parseEndpointHost) }
            .orEmpty()
        return currentMode.equals(item.mode, ignoreCase = true) &&
            currentPort == item.port &&
            currentHost.equals(item.host, ignoreCase = true)
    }
}

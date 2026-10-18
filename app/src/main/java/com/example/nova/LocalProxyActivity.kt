package com.example.nova

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalProxyActivity : AppCompatActivity() {

    private lateinit var clientData: ClientData
    private lateinit var btnToggle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvHostPort: TextView
    private lateinit var tvUsername: TextView
    private lateinit var tvPassword: TextView
    private lateinit var btnRegenerate: TextView
    private lateinit var tvUnauthorizedCounter: TextView
    private lateinit var tvUnauthorizedEmpty: TextView
    private lateinit var attemptsContainer: LinearLayout
    private lateinit var endpointsContainer: LinearLayout
    private lateinit var tvGatewayEmpty: TextView
    private lateinit var btnToggleOpenTethered: TextView
    private lateinit var tvOpenTetheredHint: TextView
    private lateinit var btnToggleAllowDirect: TextView
    private lateinit var tvAllowDirectHint: TextView
    private lateinit var tvGatewayLead: TextView
    private lateinit var wgDevicesContainer: LinearLayout
    private lateinit var btnWgAddDevice: TextView
    private var wgDevicesSignature = ""

    private var cachedEndpoints: List<GatewayEndpoint> = emptyList()
    private var cachedEndpointsAt = 0L
    private var lastSyncRequestAt = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private val uiPoller = object : Runnable {
        override fun run() {
            renderState()
            uiHandler.postDelayed(this, 900L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тему ставим до super.onCreate: позже окно уже создано со старым фоном,
        // и выбор доехал бы только до следующего открытия экрана.
        NovaTheme.apply(this)
        super.onCreate(savedInstanceState)
        applyZeroTransitionOpen()
        setContentView(R.layout.activity_local_proxy)
        NovaFontHelper.apply(findViewById(android.R.id.content))

        clientData = ClientData(this)
        btnToggle = findViewById(R.id.btn_toggle_proxy)
        tvStatus = findViewById(R.id.tv_proxy_status)
        tvSummary = findViewById(R.id.tv_proxy_summary)
        tvHostPort = findViewById(R.id.tv_host_port)
        tvUsername = findViewById(R.id.tv_username)
        tvPassword = findViewById(R.id.tv_password)
        btnRegenerate = findViewById(R.id.btn_regenerate_creds)
        tvUnauthorizedCounter = findViewById(R.id.tv_unauthorized_counter)
        tvUnauthorizedEmpty = findViewById(R.id.tv_unauthorized_empty)
        attemptsContainer = findViewById(R.id.container_unauthorized_attempts)
        endpointsContainer = findViewById(R.id.container_gateway_endpoints)
        tvGatewayEmpty = findViewById(R.id.tv_gateway_empty)
        btnToggleOpenTethered = findViewById(R.id.btn_toggle_open_tethered)
        tvOpenTetheredHint = findViewById(R.id.tv_open_tethered_hint)
        btnToggleAllowDirect = findViewById(R.id.btn_toggle_allow_direct)
        tvAllowDirectHint = findViewById(R.id.tv_allow_direct_hint)
        tvGatewayLead = findViewById(R.id.tv_gateway_lead)
        wgDevicesContainer = findViewById(R.id.container_wg_devices)
        btnWgAddDevice = findViewById(R.id.btn_wg_add_device)
        TvFocusHelper.install(
            this,
            btnToggle,
            tvHostPort,
            tvUsername,
            tvPassword,
            btnRegenerate,
            btnToggleOpenTethered,
            btnToggleAllowDirect,
            btnWgAddDevice,
        )
        btnWgAddDevice.setOnClickListener {
            val device = GatewayWireGuard.addDevice(this, "")
            if (device == null) {
                Toast.makeText(this, "Достигнут предел устройств", Toast.LENGTH_SHORT).show()
            } else {
                requestProxySyncIfNeeded()
                renderWgDevices()
                showWgDeviceDialog(device)
            }
        }

        btnToggleAllowDirect.setOnClickListener {
            val enabled = !clientData.isGatewayAllowDirectWithoutVpn()
            clientData.setGatewayAllowDirectWithoutVpn(enabled)
            requestProxySyncIfNeeded()
            renderState()
            Toast.makeText(
                this,
                if (enabled) {
                    "Без VPN трафик клиентов пойдёт открыто"
                } else {
                    "Без VPN трафик клиентов выпускаться не будет"
                },
                Toast.LENGTH_SHORT,
            ).show()
        }

        btnToggleOpenTethered.setOnClickListener {
            val enabled = !clientData.isGatewayOpenForTethered()
            clientData.setGatewayOpenForTethered(enabled)
            requestProxySyncIfNeeded()
            renderState()
            Toast.makeText(
                this,
                if (enabled) {
                    "Клиенты раздачи подключаются без пароля"
                } else {
                    "Логин и пароль снова обязательны для всех"
                },
                Toast.LENGTH_SHORT,
            ).show()
        }

        tvHostPort.setOnClickListener {
            copyToClipboard("Прокси", "${resolveDisplayHost()}:${clientData.getLocalProxyPort()}")
        }
        tvUsername.setOnClickListener {
            val username = clientData.ensureLocalProxyCredentials().first
            copyToClipboard("Логин прокси", username)
        }
        tvPassword.setOnClickListener {
            val password = clientData.ensureLocalProxyCredentials().second
            copyToClipboard("Пароль прокси", password)
        }
        btnToggle.setOnClickListener {
            val enabled = !clientData.isLocalProxyEnabled()
            if (enabled) {
                clientData.ensureLocalProxyCredentials()
            }
            clientData.setLocalProxyEnabled(enabled)
            requestProxySyncIfNeeded()
            renderState()
        }
        btnRegenerate.setOnClickListener {
            val credentials = clientData.regenerateLocalProxyCredentials()
            requestProxySyncIfNeeded()
            renderState()
            Toast.makeText(
                this,
                "Новые данные: ${credentials.first} / ${credentials.second}",
                Toast.LENGTH_SHORT,
            ).show()
        }

        renderState()
    }

    override fun onStart() {
        super.onStart()
        if (clientData.isLocalProxyEnabled()) {
            requestProxySyncIfNeeded()
        }
        uiHandler.removeCallbacks(uiPoller)
        uiHandler.post(uiPoller)
    }

    override fun onStop() {
        super.onStop()
        uiHandler.removeCallbacks(uiPoller)
    }

    override fun finish() {
        super.finish()
        applyZeroTransitionClose()
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

    private fun renderState() {
        val enabled = clientData.isLocalProxyEnabled()
        val status = clientData.getLocalProxyStatusSnapshot()
        val serviceState = clientData.getServiceState()
        val serviceConnected = serviceState == NovaVpnService.STATE_CONNECTED
        val now = System.currentTimeMillis()
        val statusFresh = status != null && (now - status.observedAt) <= 15_000L
        val running = status?.running == true && enabled && statusFresh
        val waitingForProxyStart = enabled && !running
        val allowDirect = clientData.isGatewayAllowDirectWithoutVpn()
        val backend = status?.backend?.ifBlank { clientData.getServiceBackend() } ?: clientData.getServiceBackend()
        val endpoints = resolveEndpoints(status, statusFresh)
        val displayHost = endpoints.firstOrNull { it.downstream }?.host
            ?: endpoints.firstOrNull()?.host
            ?: status?.host?.ifBlank { null }
            ?: "127.0.0.1"
        val username = clientData.getLocalProxyUsername().ifBlank { "----" }
        val password = clientData.getLocalProxyPassword().ifBlank { "----" }
        val unauthorizedCount = clientData.getLocalProxyUnauthorizedCount()
        val attempts = clientData.getLocalProxyUnauthorizedAttempts(limit = 12)

        // Раздачу почти всегда включают, стоя именно на этом экране, поэтому регулярно
        // просим службу пересинхронизироваться: новый адрес подхватится, даже если
        // системное сообщение о смене состояния раздачи до нас не дошло.
        //
        // Троттлинг здесь обязателен. Экран перерисовывается каждые 900 мс, а каждый
        // запрос — это startForegroundService с перечислением всех интерфейсов. Без
        // ограничения экран, открытый при лежащем VPN, давал по запросу на кадр:
        // за несколько минут набежало больше двухсот запусков службы.
        if (enabled && now - lastSyncRequestAt > 3_000L) {
            lastSyncRequestAt = now
            requestProxySyncIfNeeded()
        }

        btnToggle.text = if (enabled) "Выключить" else "Включить"
        tvStatus.text = when {
            running && serviceConnected -> "Раздача работает, выход через ${formatBackend(backend)}"
            running && allowDirect -> "Раздача работает, но VPN не подключён — трафик идёт открыто"
            running -> "Раздача включена, ждёт подключения VPN"
            waitingForProxyStart -> "Раздача запускается"
            else -> "Раздача выключена"
        }
        val shared = endpoints.count { it.downstream }
        tvSummary.text = buildString {
            append("Протоколы: HTTP, HTTPS CONNECT, SOCKS5. Порт ${clientData.getLocalProxyPort()}.")
            append(" Адрес остаётся доступен и без VPN, так что настроить клиента можно заранее.")
            if (shared == 0) {
                append(" Включите точку доступа или USB-модем, чтобы раздавать другим устройствам.")
            }
        }
        val port = clientData.getLocalProxyPort()
        tvGatewayLead.text = when {
            endpoints.isEmpty() ->
                "Подключитесь к этому телефону — по его точке доступа, по USB или через " +
                    "общую сеть Wi-Fi, — и адрес для настройки появится здесь."
            else -> {
                val primary = endpoints.firstOrNull { it.downstream } ?: endpoints.first()
                val opening = if (enabled) "На" else "Нажмите «Включить», а затем на"
                "$opening другом устройстве укажите в настройках прокси адрес " +
                    "${primary.host} и порт $port — и его трафик пойдёт через VPN этого телефона. " +
                    "Логин и пароль покажет и адрес http://${primary.host}:$port/ в браузере."
            }
        }
        renderEndpoints(endpoints)
        tvHostPort.text = "Хост: $displayHost    Порт: ${clientData.getLocalProxyPort()}"
        val openForTethered = clientData.isGatewayOpenForTethered()
        btnToggleOpenTethered.text = if (openForTethered) {
            "Без пароля для клиентов раздачи: вкл"
        } else {
            "Без пароля для клиентов раздачи: выкл"
        }
        tvOpenTetheredHint.setTextColor(
            if (openForTethered) android.graphics.Color.parseColor("#FFB347")
            else android.graphics.Color.parseColor("#AAAAAA")
        )
        btnToggleAllowDirect.text = if (allowDirect) {
            "Без VPN выпускать напрямую: вкл"
        } else {
            "Без VPN выпускать напрямую: выкл"
        }
        tvAllowDirectHint.setTextColor(
            if (allowDirect) android.graphics.Color.parseColor("#FF6B6B")
            else android.graphics.Color.parseColor("#AAAAAA")
        )
        tvUsername.text = "Логин: $username"
        tvPassword.text = "Пароль: $password"
        tvUnauthorizedCounter.text = "Неудачных входов: $unauthorizedCount"
        tvUnauthorizedCounter.setTextColor(
            if (unauthorizedCount > 0) android.graphics.Color.parseColor("#FF6B6B")
            else android.graphics.Color.parseColor("#50C878")
        )
        renderUnauthorizedAttempts(attempts)
        renderWgDevices()
    }

    private fun renderWgDevices() {
        val devices = GatewayWireGuard.devices(this)
        val host = primaryEndpoint()?.host.orEmpty()
        val signature = devices.joinToString("|") { "${it.id}:${it.name}" } + "@" + host
        if (signature == wgDevicesSignature) return
        wgDevicesSignature = signature
        wgDevicesContainer.removeAllViews()
        devices.forEach { device ->
            val row = TextView(this).apply {
                text = "${device.name} · ${device.address4}"
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
                setPadding(0, 6, 0, 14)
                isClickable = true
                isFocusable = true
                setOnClickListener { showWgDeviceDialog(device) }
            }
            wgDevicesContainer.addView(row)
        }
    }

    private fun showWgDeviceDialog(device: GatewayWireGuard.Device) {
        val host = primaryEndpoint()?.host
        if (host == null) {
            Toast.makeText(
                this,
                "Нет адреса раздачи: включите точку доступа или подключитесь к Wi-Fi",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        val config = GatewayWireGuard.clientConfig(GatewayWireGuard.ensure(this), device, host)
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        content.addView(
            ImageView(this).apply {
                setImageBitmap(qrBitmap(config))
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            },
            LinearLayout.LayoutParams((260 * density).toInt(), (260 * density).toInt()).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            },
        )
        content.addView(
            TextView(this).apply {
                text = NovaLanguage.tr(this@LocalProxyActivity, "Адрес сервера $host, UDP ${GatewayWireGuard.PORT}. Если точка доступа сменит адрес, конфиг нужно показать снова.")
                textSize = 12f
                setPadding(0, pad / 2, 0, pad / 2)
            },
        )
        content.addView(
            TextView(this).apply {
                text = config
                textSize = 11f
                typeface = NovaFontHelper.monospace(context)
                tag = NovaFontHelper.KEEP_FACE_TAG
                setTextIsSelectable(true)
            },
        )
        val scroll = android.widget.ScrollView(this).apply { addView(content) }
        android.app.AlertDialog.Builder(this)
            .setTitle(device.name)
            .setView(scroll)
            .setPositiveButton("Копировать") { _, _ -> copyToClipboard("Конфиг WireGuard", config) }
            .setNeutralButton("Поделиться") { _, _ ->
                startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, config)
                        },
                        device.name,
                    ),
                )
            }
            .setNegativeButton("Удалить") { _, _ ->
                GatewayWireGuard.removeDevice(this, device.id)
                requestProxySyncIfNeeded()
                renderWgDevices()
            }
            .showNova()
    }

    private fun qrBitmap(text: String): android.graphics.Bitmap {
        val modules = QrCode.encode(text)
        val quiet = 4
        val scale = 8
        val side = (modules.size + quiet * 2) * scale
        val pixels = IntArray(side * side) { android.graphics.Color.WHITE }
        for (y in modules.indices) for (x in modules.indices) {
            if (!modules[y][x]) continue
            for (dy in 0 until scale) {
                val row = ((y + quiet) * scale + dy) * side
                for (dx in 0 until scale) pixels[row + (x + quiet) * scale + dx] = android.graphics.Color.BLACK
            }
        }
        return android.graphics.Bitmap.createBitmap(pixels, side, side, android.graphics.Bitmap.Config.ARGB_8888)
    }

    /**
     * Пока служба жива, список адресов приходит из её снимка состояния. Когда прокси
     * выключен, снимок не обновляется, поэтому перечисляем интерфейсы сами — но не
     * чаще раза в две с половиной секунды: этот экран перерисовывается каждые 900 мс.
     */
    private fun resolveEndpoints(
        status: LocalProxyStatusSnapshot?,
        statusFresh: Boolean,
    ): List<GatewayEndpoint> {
        if (statusFresh && status != null && status.endpoints.isNotEmpty()) {
            cachedEndpoints = status.endpoints
            cachedEndpointsAt = System.currentTimeMillis()
            return status.endpoints
        }
        val now = System.currentTimeMillis()
        if (now - cachedEndpointsAt > 2_500L) {
            cachedEndpoints = GatewayEndpoints.discover(this)
            cachedEndpointsAt = now
        }
        return cachedEndpoints
    }

    private fun renderEndpoints(endpoints: List<GatewayEndpoint>) {
        endpointsContainer.removeAllViews()
        tvGatewayEmpty.visibility = if (endpoints.isEmpty()) View.VISIBLE else View.GONE
        val port = clientData.getLocalProxyPort()
        endpoints.forEach { endpoint ->
            val row = TextView(this).apply {
                text = "${endpoint.kind.title} · ${endpoint.interfaceName}\n${endpoint.host}:$port"
                textSize = 14f
                setTextColor(
                    if (endpoint.downstream) android.graphics.Color.parseColor("#50C878")
                    else android.graphics.Color.parseColor("#DDDDDD")
                )
                setPadding(0, 0, 0, 14)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    copyToClipboard("Адрес прокси", "${endpoint.host}:$port")
                }
            }
            endpointsContainer.addView(row)
        }
    }

    private fun primaryEndpoint(): GatewayEndpoint? {
        return cachedEndpoints.firstOrNull { it.downstream } ?: cachedEndpoints.firstOrNull()
    }

    private fun renderUnauthorizedAttempts(attempts: List<LocalProxyUnauthorizedAttempt>) {
        attemptsContainer.removeAllViews()
        tvUnauthorizedEmpty.visibility = if (attempts.isEmpty()) View.VISIBLE else View.GONE
        val formatter = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())
        attempts.forEach { attempt ->
            val actor = when {
                attempt.appLabel.isNotBlank() && attempt.packageName.isNotBlank() ->
                    "${attempt.appLabel} (${attempt.packageName})"
                attempt.packageName.isNotBlank() -> attempt.packageName
                else -> "неизвестное приложение"
            }
            val item = TextView(this).apply {
                text = "${formatter.format(Date(attempt.observedAt))}  ${attempt.protocol}\n$actor\n${attempt.reason}"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#DDDDDD"))
                setPadding(0, 0, 0, 18)
            }
            attemptsContainer.addView(item)
        }
    }

    /**
     * Раздача не зависит от состояния VPN, поэтому службу поднимаем и без туннеля:
     * пользователь должен успеть настроить клиента заранее, а при обрыве связи
     * адрес и порт не должны исчезать из-под уже настроенного устройства.
     * onStartCommand вызывает startForeground до разбора действия, так что запуск
     * ради одной синхронизации безопасен.
     */
    private fun requestProxySyncIfNeeded() {
        // Выключение раздачи тоже надо донести до службы — иначе слушающие сокеты
        // останутся открытыми до перезапуска. Поэтому молчим только когда раздача
        // выключена и по последнему снимку уже не работает.
        val running = clientData.getLocalProxyStatusSnapshot()?.running == true
        if (!clientData.isLocalProxyEnabled() && !running) {
            clientData.saveLocalProxyStatus(
                running = false,
                backend = clientData.getServiceBackend(),
                host = resolveDisplayHost(),
                port = clientData.getLocalProxyPort(),
            )
            return
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, NovaVpnService::class.java).apply {
                action = NovaVpnService.ACTION_SYNC_LOCAL_PROXY
            }
        )
    }

    private fun copyToClipboard(label: String, value: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        Toast.makeText(this, NovaLanguage.tr(this, "$label скопирован"), Toast.LENGTH_SHORT).show()
    }

    private fun resolveDisplayHost(): String {
        return primaryEndpoint()?.host ?: "127.0.0.1"
    }

    private fun formatBackend(backend: String): String {
        val normalized = backend.trim().uppercase(Locale.US)
        return when {
            normalized.startsWith("${NovaVpnService.BACKEND_OPERA}-") -> normalized.substringAfter('-')
            normalized.startsWith(NovaVpnService.BACKEND_OPERA) -> NovaVpnService.BACKEND_OPERA
            else -> NovaVpnService.BACKEND_WARP
        }
    }
}

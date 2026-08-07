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
    private lateinit var tvPortalUrl: TextView
    private lateinit var tvPacUrl: TextView
    private lateinit var btnToggleOpenTethered: TextView
    private lateinit var tvOpenTetheredHint: TextView
    private lateinit var btnToggleAllowDirect: TextView
    private lateinit var tvAllowDirectHint: TextView
    private lateinit var tvGatewayLead: TextView

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
        tvPortalUrl = findViewById(R.id.tv_portal_url)
        tvPacUrl = findViewById(R.id.tv_pac_url)
        btnToggleOpenTethered = findViewById(R.id.btn_toggle_open_tethered)
        tvOpenTetheredHint = findViewById(R.id.tv_open_tethered_hint)
        btnToggleAllowDirect = findViewById(R.id.btn_toggle_allow_direct)
        tvAllowDirectHint = findViewById(R.id.tv_allow_direct_hint)
        tvGatewayLead = findViewById(R.id.tv_gateway_lead)
        TvFocusHelper.install(
            this,
            btnToggle,
            tvPortalUrl,
            tvPacUrl,
            tvHostPort,
            tvUsername,
            tvPassword,
            btnRegenerate,
            btnToggleOpenTethered,
            btnToggleAllowDirect,
        )

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

        tvPortalUrl.setOnClickListener {
            val url = portalUrl()
            if (url.isNotBlank()) copyToClipboard("Адрес страницы настройки", url)
        }
        tvPacUrl.setOnClickListener {
            val url = pacUrl()
            if (url.isNotBlank()) copyToClipboard("PAC", url)
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
                    "Подробная инструкция для Windows, macOS, iOS, Android и телевизоров " +
                    "откроется по ссылке ниже."
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
        val portal = portalUrl()
        tvPortalUrl.text = if (portal.isBlank()) "Страница настройки: —" else "Страница настройки: $portal"
        val pac = pacUrl()
        tvPacUrl.text = if (pac.isBlank()) "PAC: —" else "PAC: $pac"
    }

    private fun primaryEndpoint(): GatewayEndpoint? {
        return cachedEndpoints.firstOrNull { it.downstream } ?: cachedEndpoints.firstOrNull()
    }

    private fun portalUrl(): String {
        val endpoint = primaryEndpoint() ?: return ""
        return "http://${endpoint.host}:${clientData.getGatewayPortalPort()}/"
    }

    private fun pacUrl(): String {
        val endpoint = primaryEndpoint() ?: return ""
        return "http://${endpoint.host}:${clientData.getGatewayPortalPort()}/nova.pac"
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
        Toast.makeText(this, "$label скопирован", Toast.LENGTH_SHORT).show()
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

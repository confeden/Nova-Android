package com.example.nova

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class LogsActivity : AppCompatActivity() {

    private lateinit var clientData: ClientData
    private lateinit var swEnabled: Switch
    private lateinit var rgLevel: RadioGroup
    private lateinit var tvSummary: TextView
    private lateinit var tvPreview: TextView
    private lateinit var btnPreview: TextView
    private lateinit var btnCopy: TextView
    private lateinit var btnShare: TextView

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var previewJob: Job? = null
    private var suppressUiCallbacks = false
    private var latestPreview: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyZeroTransitionOpen()
        setContentView(R.layout.activity_logs)
        NovaFontHelper.apply(findViewById(android.R.id.content))
        LogManager.setAppContext(this)

        clientData = ClientData(this)
        bindViews()
        bindListeners()
        loadConfig()
        refreshPreview()
    }

    override fun onDestroy() {
        super.onDestroy()
        previewJob?.cancel()
        scope.cancel()
    }

    override fun finish() {
        super.finish()
        applyZeroTransitionClose()
    }

    private fun bindViews() {
        swEnabled = findViewById(R.id.sw_logs_enabled)
        rgLevel = findViewById(R.id.rg_logs_level)
        tvSummary = findViewById(R.id.tv_logs_summary)
        tvPreview = findViewById(R.id.tv_logs_preview)
        btnPreview = findViewById(R.id.btn_preview_log)
        btnCopy = findViewById(R.id.btn_copy_log)
        btnShare = findViewById(R.id.btn_share_log)
        TvFocusHelper.install(this, swEnabled, btnPreview, btnCopy, btnShare)
    }

    private fun bindListeners() {
        swEnabled.setOnCheckedChangeListener { _, _ ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            persistConfig()
        }
        rgLevel.setOnCheckedChangeListener { _, _ ->
            if (suppressUiCallbacks) return@setOnCheckedChangeListener
            persistConfig()
        }
        btnPreview.setOnClickListener { refreshPreview() }
        btnCopy.setOnClickListener { copyPreview() }
        btnShare.setOnClickListener { sharePreview() }
    }

    private fun loadConfig() {
        val config = clientData.getDiagnosticLogSettingsConfig()
        suppressUiCallbacks = true
        swEnabled.isChecked = config.enabled
        when (config.level) {
            "debug" -> rgLevel.check(R.id.rb_logs_debug)
            "info" -> rgLevel.check(R.id.rb_logs_info)
            "warn" -> rgLevel.check(R.id.rb_logs_warn)
            else -> rgLevel.check(R.id.rb_logs_error)
        }
        suppressUiCallbacks = false
        updateSummary()
    }

    private fun persistConfig() {
        val level = when (rgLevel.checkedRadioButtonId) {
            R.id.rb_logs_debug -> "debug"
            R.id.rb_logs_info -> "info"
            R.id.rb_logs_warn -> "warn"
            else -> "error"
        }
        clientData.saveDiagnosticLogSettingsConfig(
            DiagnosticLogSettingsConfig(
                enabled = swEnabled.isChecked,
                level = level,
            )
        )
        LogManager.reloadSettings()
        updateSummary()
    }

    private fun updateSummary() {
        tvSummary.text = buildString {
            append("Состояние: ")
            append(clientData.getDiagnosticLogSettingsSummary())
            append('\n')
            append("Личные данные в отчёте скрываются автоматически")
        }
    }

    private fun refreshPreview() {
        previewJob?.cancel()
        tvPreview.text = "Готовим предпросмотр..."
        previewJob = scope.launch {
            val report = withContext(Dispatchers.IO) {
                buildPreviewReport()
            }
            latestPreview = report
            tvPreview.text = report
        }
    }

    private fun copyPreview() {
        val payload = latestPreview.ifBlank { buildPreviewReport() }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Nova diagnostics log", payload))
        Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun sharePreview() {
        val payload = latestPreview.ifBlank { buildPreviewReport() }
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Nova diagnostic log")
                    putExtra(Intent.EXTRA_TEXT, payload)
                },
                "Отправить лог",
            )
        )
    }

    private fun buildPreviewReport(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val packageInfo = packageManager.getPackageInfo(packageName, 0)
        val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toString()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toString()
        }
        val snapshot = clientData.getTunnelUiSnapshot()
        val directSnapshot = clientData.getDirectUiSnapshot()
        val lines = buildList {
            add("Nova diagnostic log")
            add("generated_at=$timestamp")
            add("app_version=${packageInfo.versionName ?: "unknown"} ($versionCode)")
            add("android=${Build.VERSION.RELEASE ?: "unknown"} sdk=${Build.VERSION.SDK_INT}")
            add("service_state=${clientData.getServiceState().ifBlank { "unknown" }}")
            add("backend=${clientData.getServiceBackend().ifBlank { "unknown" }}")
            add("exit_preference=${clientData.getExitRegionPreference()}")
            add("vpn_snapshot_backend=${snapshot?.backend?.ifBlank { "unknown" } ?: "unknown"}")
            add("vpn_snapshot_country=${snapshot?.country?.ifBlank { "unknown" } ?: "unknown"}")
            add("direct_snapshot_country=${directSnapshot?.country?.ifBlank { "unknown" } ?: "unknown"}")
            add("logging=${clientData.getDiagnosticLogSettingsSummary()}")
            add("")
            add("--- logs ---")
            val persisted = LogManager.getPersistedLogs()
            if (persisted.isBlank()) {
                add("Логов пока нет")
            } else {
                add(DiagnosticLogSanitizer.sanitize(persisted))
            }
        }
        return lines.joinToString("\n")
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

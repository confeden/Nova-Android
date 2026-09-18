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
        // Тему ставим до super.onCreate: позже окно уже создано со старым фоном,
        // и выбор доехал бы только до следующего открытия экрана.
        NovaTheme.apply(this)
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
        // Журнал показывается как есть: его строки — ключи поиска (I14), а перевод
        // по строкам превратил бы его в смесь языков. Подписи-заглушки переводятся явно.
        NovaLanguage.verbatim(tvPreview)
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
        tvPreview.text = NovaLanguage.tr(this, "Готовим предпросмотр...")
        previewJob = scope.launch {
            val report = withContext(Dispatchers.IO) {
                buildPreviewReport()
            }
            latestPreview = report
            tvPreview.text = report
        }
    }

    private fun copyPreview() {
        // Отчёт зовёт десяток системных служб и читает файл под блокировкой — не на
        // главном потоке. Пока предпросмотра нет, просто собираем его.
        val payload = latestPreview.ifBlank {
            refreshPreview()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Nova diagnostics log", payload))
        Toast.makeText(this, NovaLanguage.tr(this, "Лог скопирован"), Toast.LENGTH_SHORT).show()
    }

    private fun sharePreview() {
        val payload = latestPreview.ifBlank {
            refreshPreview()
            return
        }
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

    private fun buildPreviewReport(): String =
        DiagnosticSnapshot.buildReport(this, maxLogChars = 160_000)

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

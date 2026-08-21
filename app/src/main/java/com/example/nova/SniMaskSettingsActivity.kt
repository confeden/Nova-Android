package com.example.nova

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран «SNI маскировка»: чем подписываться в TLS-рукопожатии.
 *
 * Настройка применяется к следующему подключению — туннель ради смены имени не
 * рвём. Экран говорит об этом прямо: молчаливое «сохранено, но не применилось»
 * читается как неработающая настройка.
 */
class SniMaskSettingsActivity : AppCompatActivity() {

    private lateinit var clientData: ClientData
    private lateinit var rgMode: RadioGroup
    private lateinit var rbAuto: RadioButton
    private lateinit var rbCustom: RadioButton
    private lateinit var etCustomList: EditText
    private lateinit var tvCustomStatus: TextView
    private lateinit var tvState: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sni_mask_settings)
        LogManager.setAppContext(this)
        clientData = ClientData(this)

        rgMode = findViewById(R.id.rg_sni_mode)
        rbAuto = findViewById(R.id.rb_sni_auto)
        rbCustom = findViewById(R.id.rb_sni_custom)
        etCustomList = findViewById(R.id.et_sni_custom_list)
        tvCustomStatus = findViewById(R.id.tv_sni_custom_status)
        tvState = findViewById(R.id.tv_sni_state)

        etCustomList.setText(clientData.getSniCustomListRaw())
        val savedMode = clientData.getSniMaskMode()
        if (savedMode == SniMaskPolicy.MODE_CUSTOM) rbCustom.isChecked = true else rbAuto.isChecked = true
        applyModeVisibility(savedMode)
        refreshState()

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.rb_sni_custom) {
                SniMaskPolicy.MODE_CUSTOM
            } else {
                SniMaskPolicy.MODE_AUTO
            }
            clientData.setSniMaskMode(mode)
            applyModeVisibility(mode)
            refreshState()
            LogManager.log("SNI маскировка: режим $mode.")
        }

        etCustomList.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // Сохраняем сырую строку, а показываем разобранную: пользователь видит,
                // что именно приложение поняло из набранного, ещё до подключения.
                clientData.setSniCustomListRaw(s?.toString().orEmpty())
                refreshState()
            }
        })
    }

    private fun applyModeVisibility(mode: String) {
        val custom = mode == SniMaskPolicy.MODE_CUSTOM
        etCustomList.visibility = if (custom) View.VISIBLE else View.GONE
        tvCustomStatus.visibility = if (custom) View.VISIBLE else View.GONE
    }

    private fun refreshState() {
        val mode = clientData.getSniMaskMode()
        val parsed = clientData.getSniCustomHosts()
        if (mode == SniMaskPolicy.MODE_CUSTOM) {
            tvCustomStatus.text = if (parsed.isEmpty()) {
                "Ни одного имени разобрать не удалось — до подключения будет использован встроенный набор."
            } else {
                "Разобрано имён: ${parsed.size} — ${parsed.take(4).joinToString(", ")}" +
                    if (parsed.size > 4) ", …" else ""
            }
        }

        val pools = clientData.getSniMaskPools()
        val regime = when (clientData.getLatestRestrictedMobileStatus(freshnessMs = 5L * 60L * 1000L)) {
            true -> "белый список — только российские имена"
            false -> "чёрный список — российские и зарубежные по очереди"
            null -> "не определён — российские идут первыми"
        }
        val preview = SniMaskPolicy.pick(
            SniMaskPolicy.Inputs(
                mode = mode,
                regime = SniMaskPolicy.Regime.UNKNOWN,
                customHosts = parsed,
                pools = pools,
                seed = 0,
                attempt = 0,
            )
        )
        tvState.text = buildString {
            append("Режим сети: ")
            append(regime)
            append(".\nВстроенные наборы: ")
            append("${pools.white.size} проверенных, ${pools.russia.size} российских, ${pools.global.size} зарубежных.")
            if (preview != null) {
                append("\nСледующее имя: ${preview.host} (набор ${preview.source}).")
            }
            append("\nНастройка применится к следующему подключению.")
        }
    }
}

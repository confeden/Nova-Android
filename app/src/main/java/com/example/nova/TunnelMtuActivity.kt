package com.example.nova

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран «MTU туннеля».
 *
 * Раньше настройка жила строкой в общих настройках и умела ровно одно — двигать
 * ползунок. Здесь то же значение, но с тремя вещами, которых там не было и без
 * которых настройка бесполезна ровно тем, кому нужна.
 *
 * **Регулировка показывается не всегда.** MTU туннеля задаётся в
 * `VpnService.Builder`, и делает это только путь WARP/AWG
 * (`establishVpnInterface`). У Opera, VLESS и Tor туннель строит `tun2proxy` со
 * своим зашитым 1420 (`establishOperaTunnelInterface`,
 * `establishProxyTunnelInterface`), у MASQUE размер считается из пакета QUIC и
 * приезжает `mtuOverride`. Показывать ползунок при выбранном Opera — значит
 * обещать действие, которого не будет (I4); поэтому при таком транспорте на
 * экране остаётся честное объяснение, а не мёртвая шкала.
 *
 * **Пол шкалы 600, а не 1000.** Ради сетей с дырой MTU — см.
 * [NovaVpnService.TUNNEL_MTU_MIN].
 *
 * **Точное значение и заводское.** 84 положения ползунка пальцем не набираются, а
 * «вернуть как было» на глаз не делается вовсе: середина шкалы — 1020, а не 1280.
 */
class TunnelMtuActivity : AppCompatActivity() {

    private lateinit var clientData: ClientData

    private lateinit var cardControl: View
    private lateinit var seekBar: SeekBar
    private lateinit var tvScope: TextView
    private lateinit var tvValue: TextView
    private lateinit var tvSummary: TextView
    private lateinit var tvUnsupported: TextView
    private lateinit var tvState: TextView
    private lateinit var etManual: EditText

    /** Шагов на шкале: от пола до потолка с шагом [NovaVpnService.TUNNEL_MTU_STEP]. */
    private val steps =
        (NovaVpnService.TUNNEL_MTU_MAX - NovaVpnService.TUNNEL_MTU_MIN) / NovaVpnService.TUNNEL_MTU_STEP

    private companion object {
        /**
         * Транспорты, у которых MTU задаёт эта настройка.
         *
         * Ровно те, что уходят в `establishVpnInterface`: «Авто» и WARP строят
         * туннель WireGuard сами, PROTON — тот же путь с импортированным профилем.
         * Значения чипов — из `ConnectionSelectorPolicy.ORDER`.
         */
        private val SUPPORTED_CHIPS = setOf("auto", "ru", "proton")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тему ставим до super.onCreate: позже окно уже создано со старым фоном.
        NovaTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tunnel_mtu)
        LogManager.setAppContext(this)
        clientData = ClientData(this)

        cardControl = findViewById(R.id.card_mtu_control)
        seekBar = findViewById(R.id.sb_tunnel_mtu)
        tvScope = findViewById(R.id.tv_mtu_scope)
        tvValue = findViewById(R.id.tv_mtu_value)
        tvSummary = findViewById(R.id.tv_mtu_summary)
        tvUnsupported = findViewById(R.id.tv_mtu_unsupported)
        tvState = findViewById(R.id.tv_mtu_state)
        etManual = findViewById(R.id.et_mtu_manual)

        findViewById<TextView>(R.id.tv_mtu_min).text = NovaVpnService.TUNNEL_MTU_MIN.toString()
        findViewById<TextView>(R.id.tv_mtu_max).text = NovaVpnService.TUNNEL_MTU_MAX.toString()

        val defaultButton = findViewById<Button>(R.id.btn_mtu_default)
        defaultButton.text = "По умолчанию (${NovaVpnService.TUNNEL_MTU_DEFAULT})"
        defaultButton.setOnClickListener { commit(NovaVpnService.TUNNEL_MTU_DEFAULT, "кнопкой «По умолчанию»") }

        val applyButton = findViewById<Button>(R.id.btn_mtu_apply)
        applyButton.setOnClickListener { applyManualEntry() }

        // Кнопки красятся кодом: платформенный стиль на Pixel приводит фон
        // динамическим акцентом системы, и обе приезжали сиреневыми поверх темы.
        // Логика одна и живёт в [NovaDialogs] — второй копии тут быть не должно.
        NovaDialogs.styleButton(applyButton, primary = true)
        NovaDialogs.styleButton(defaultButton, primary = false)
        etManual.setOnEditorActionListener { _, _, _ -> applyManualEntry(); true }

        seekBar.max = steps
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                // Пока палец на шкале — только показываем. Запись идёт на отпускании:
                // иначе одно движение писало бы файл несколько десятков раз.
                if (fromUser) render(mtuOf(progress), persisted = false)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            override fun onStopTrackingTouch(bar: SeekBar) = commit(mtuOf(bar.progress), "ползунком")
        })

        applyTransportVisibility()
        render(clientData.getTunnelMtu(), persisted = true)
    }

    override fun onResume() {
        super.onResume()
        // Транспорт мог смениться на предыдущем экране, пока этот лежал в стопе.
        applyTransportVisibility()
        render(clientData.getTunnelMtu(), persisted = true)
    }

    private fun mtuOf(progress: Int): Int =
        NovaVpnService.TUNNEL_MTU_MIN + progress * NovaVpnService.TUNNEL_MTU_STEP

    /**
     * Показывает регулировку только там, где MTU решает эта настройка.
     *
     * Ответ берётся у выбранного транспорта, а не у идущего сеанса: экран
     * настраивает следующее подключение, и человек, выбравший WARP при выключенном
     * VPN, обязан увидеть шкалу.
     */
    private fun applyTransportVisibility() {
        // Порядок и подписи — из [ConnectionSelectorPolicy], а не выписаны здесь:
        // вторая копия списка транспортов — это ровно тот способ, которым случается
        // G49.
        val index = ConnectionSelectorPolicy.indexOf(clientData.getExitRegionPreference())
        val chip = ConnectionSelectorPolicy.valueAt(index)
        val transportName = ConnectionSelectorPolicy.LABELS.getOrNull(index) ?: chip.uppercase()

        // Кто берёт это значение: «Авто» и WARP строят туннель сами
        // (`establishVpnInterface`), AWG Proton — тот же путь с импортированным
        // профилем WireGuard. Остальные три — нет, каждый по своей причине.
        val supported = chip in SUPPORTED_CHIPS

        cardControl.visibility = if (supported) View.VISIBLE else View.GONE
        tvScope.text = "Транспорт: $transportName"

        tvUnsupported.text = buildString {
            append("MASQUE — размер считается из пакета QUIC, настройка на него не влияет.\n")
            append("Opera, VLESS и TOR — туннель держит tun2proxy со своим 1420 Б.")
            if (!supported) {
                append("\n\nСейчас выбран $transportName, поэтому регулировка скрыта: она не изменила бы ничего. ")
                append("Выберите AUTO, WARP или PROTON, чтобы настроить MTU.")
            }
        }
    }

    /** Пишет значение и, если сеанс жив, пересобирает туннель. */
    private fun commit(mtu: Int, how: String) {
        val clamped = mtu.coerceIn(NovaVpnService.TUNNEL_MTU_MIN, NovaVpnService.TUNNEL_MTU_MAX)
        if (clamped == clientData.getTunnelMtu()) {
            render(clamped, persisted = true)
            return
        }
        clientData.setTunnelMtu(clamped)
        LogManager.log(
            "MTU туннеля выбран $how: $clamped " +
                "(на проводе ${clamped + NovaVpnService.WARP_WIRE_OVERHEAD_IPV4} Б)."
        )
        render(clamped, persisted = true)
        requestReapply(clamped)
    }

    /**
     * Разбирает поле точного значения.
     *
     * Чужое число не обрезается молча: обрезание — это тихая подмена явного
     * выбора (I1), и человек, набравший 1500, должен узнать, почему получил не
     * его. Поэтому вне диапазона — отказ с названными границами, а не тихий зажим.
     */
    private fun applyManualEntry() {
        val raw = etManual.text?.toString()?.trim().orEmpty()
        if (raw.isEmpty()) {
            Toast.makeText(this, "Введите число от ${NovaVpnService.TUNNEL_MTU_MIN} до ${NovaVpnService.TUNNEL_MTU_MAX}", Toast.LENGTH_SHORT).show()
            return
        }
        val parsed = raw.toIntOrNull()
        if (parsed == null || parsed < NovaVpnService.TUNNEL_MTU_MIN || parsed > NovaVpnService.TUNNEL_MTU_MAX) {
            Toast.makeText(
                this,
                "MTU должен быть от ${NovaVpnService.TUNNEL_MTU_MIN} до ${NovaVpnService.TUNNEL_MTU_MAX}",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        etManual.setText("")
        etManual.clearFocus()
        commit(parsed, "вручную")
    }

    private fun render(mtu: Int, persisted: Boolean) {
        tvValue.text = mtu.toString()
        seekBar.progress =
            ((mtu - NovaVpnService.TUNNEL_MTU_MIN) / NovaVpnService.TUNNEL_MTU_STEP).coerceIn(0, steps)

        val default = if (mtu == NovaVpnService.TUNNEL_MTU_DEFAULT) {
            "по умолчанию ${NovaVpnService.TUNNEL_MTU_DEFAULT} — оно и стоит"
        } else {
            "по умолчанию ${NovaVpnService.TUNNEL_MTU_DEFAULT}"
        }
        tvSummary.text = "На проводе ${mtu + NovaVpnService.WARP_WIRE_OVERHEAD_IPV4} Б, $default."

        tvState.text = if (!persisted) {
            "Отпустите ползунок, чтобы сохранить."
        } else if (isSessionLikelyActive()) {
            "Значение сохранено. Туннель пересобирается — MTU задаётся при подъёме интерфейса."
        } else {
            "Значение сохранено и применится при следующем подключении."
        }
    }

    private fun isSessionLikelyActive(): Boolean = SessionReapply.isSessionLikelyActive(this, clientData)

    /**
     * Пересобирает живой туннель под новый MTU.
     *
     * MTU задаётся в `VpnService.Builder`, то есть в момент подъёма интерфейса, и
     * живому `tun` его не переставить. Порядок общий ([SessionReapply]), включая
     * осторожный путь для tun2proxy: обычный реаплай поверх живой Opera роняет
     * процесс `:vpn` (G3), а вторая копия порядка — это G49.
     */
    private fun requestReapply(mtu: Int) {
        if (!isSessionLikelyActive()) return
        runCatching {
            LogManager.log("MTU туннеля изменён на $mtu. Запускаем немедленный мягкий реконнект.")
            val started = if (SessionReapply.needsControlledTunRestart(this, clientData)) {
                SessionReapply.launchControlledTunRestart(this, clientData)
            } else {
                SessionReapply.launchDirect(this, clientData)
            }
            if (started) {
                Toast.makeText(this, "Пересобираем туннель с MTU $mtu...", Toast.LENGTH_SHORT).show()
            }
        }.onFailure { error ->
            LogManager.log("Не удалось сразу применить MTU туннеля: ${error.message}")
        }
    }
}

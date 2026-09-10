package com.example.nova

import android.content.Intent
import android.os.Bundle
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Отдельное меню под одну настройку — что показывать в уведомлении службы.
 *
 * Отдельное намеренно: в общем списке настроек этот переключатель встал бы
 * между «маскировкой SNI» и «локальным прокси», то есть между вещами, от
 * которых зависит работа туннеля. Внешний вид шторки к ним не относится.
 */
class NotificationSettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тему ставим до super.onCreate: позже окно уже создано со старым фоном,
        // и выбор доехал бы только до следующего открытия экрана.
        NovaTheme.apply(this)
        super.onCreate(savedInstanceState)
        LogManager.setAppContext(this)
        setContentView(R.layout.activity_notification_settings)
        NovaFontHelper.apply(findViewById(android.R.id.content))

        val clientData = ClientData(this)
        val switch = findViewById<Switch>(R.id.sw_notification_details)
        val preview = findViewById<TextView>(R.id.tv_notification_preview)

        fun renderPreview(enabled: Boolean) {
            preview.text = if (enabled) {
                "В шторке: Nova VPN — AWG PROTON · NL · ↓ 1,4 МБ/с ↑ 0,2 МБ/с"
            } else {
                "В шторке: Nova VPN"
            }
        }

        switch.isChecked = clientData.isNotificationDetailsEnabled()
        renderPreview(switch.isChecked)
        switch.setOnCheckedChangeListener { _, checked ->
            clientData.setNotificationDetailsEnabled(checked)
            renderPreview(checked)
            // Служба перерисовывает уведомление своим тиком, но тик заводится
            // только при смене состояния туннеля. Без этого толчка выключенная
            // строка висела бы до следующего переподключения.
            if (clientData.getServiceState() != NovaVpnService.STATE_STOPPED) {
                runCatching {
                    ContextCompat.startForegroundService(
                        this,
                        Intent(this, NovaVpnService::class.java).apply {
                            action = NovaVpnService.ACTION_REFRESH_NOTIFICATION
                            // Значение обязано ехать полем: настройки кэшируются на
                            // процесс, и `:vpn` читал бы своё прежнее (I19). Без
                            // этого переключатель гас на экране, а в шторке
                            // подробности оставались до переподключения.
                            putExtra(NovaVpnService.EXTRA_NOTIFICATION_DETAILS_ENABLED, checked)
                        },
                    )
                }
            }
        }
    }
}

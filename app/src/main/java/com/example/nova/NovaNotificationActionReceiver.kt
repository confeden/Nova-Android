package com.example.nova

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Кнопки в уведомлении Nova.
 *
 * Действий два — «Отключить» и «Подключить»; в строке уведомления живёт то из
 * них, которое соответствует состоянию туннеля. Отдельным приёмником, а не
 * намерением прямо в службу, по двум причинам, и обе не косметические:
 *
 * 1. Останов снимает три признака через `SharedPreferences`, а те кэшируются
 *    попроцессно (I2). Служба живёт в `:vpn`; отправь мы намерение туда, признаки
 *    снялись бы в чужом кэше, и экран после нажатия показывал бы «подключение»
 *    над остановленным туннелем. Запуск читает оттуда же весь срез настроек,
 *    который обязан уехать вместе с намерением (I19), и в `:vpn` этот срез
 *    устаревший. Приёмник объявлен без `android:process`, то есть работает в
 *    основном процессе — там, где интерфейс эти признаки пишет и читает.
 * 2. Уведомление строит служба, но кому принадлежит `PendingIntent`, решает
 *    манифест, а не тот, кто его создал. Так что приёмник из основного процесса
 *    доступен службе без единой оговорки.
 *
 * Приёмник **не экспортирован**: намерение приходит только от нашего же
 * уведомления, и открывать чужим приложениям возможность гасить или поднимать
 * туннель одной широковещательной посылкой незачем.
 */
class NovaNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        LogManager.setAppContext(context)
        when (intent.action) {
            ACTION_DISCONNECT -> NovaTunnelControl.stop(context, "Уведомление")
            ACTION_CONNECT -> NovaTunnelControl.start(context, "Уведомление")
            // Молчать нельзя даже здесь (I4): нераспознанное действие означает, что
            // кнопку добавили, а обработку — нет, и без строки в журнале это
            // выглядит как «кнопка не нажимается».
            else -> LogManager.log(
                "Уведомление: неизвестное действие ${intent.action.orEmpty().ifBlank { "<пусто>" }}."
            )
        }
    }

    companion object {
        const val ACTION_DISCONNECT = "com.example.nova.notification.DISCONNECT"
        const val ACTION_CONNECT = "com.example.nova.notification.CONNECT"

        /** Свои коды запроса, чтобы намерения не сталкивались с чужими (5004, 5005, 5008, 7101, 7102). */
        const val REQUEST_DISCONNECT = 5006
        const val REQUEST_CONNECT = 5007
    }
}

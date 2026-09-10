package com.example.nova

import android.content.Context
import android.content.Intent

/**
 * Остановка туннеля «снаружи экрана» — из плитки, виджета и уведомления.
 *
 * Зачем отдельным местом. Последовательность останова состоит из пяти шагов, и
 * все пять обязательны: три признака снимаются, состояние записывается, и только
 * потом служба получает намерение. Пропущенный признак не роняет ничего сразу —
 * он оживает позже: `transient_connecting_pending` заставит экран показывать
 * «подключение» над остановленным туннелем, а `restart_session` поднимет туннель
 * обратно через секунду после того, как человек его выключил. Копий этой
 * последовательности было три, слово в слово; четвёртая ради уведомления — это
 * ровно тот способ, которым в этом проекте уже расходились списки (G49).
 *
 * **Почему это не может жить в службе.** Три из пяти шагов пишут в
 * `SharedPreferences`, а они кэшируются попроцессно (I2). Служба живёт в `:vpn`,
 * и снятые ею признаки интерфейс не увидел бы. Поэтому вызывать это обязан
 * компонент **основного** процесса — плитка, приёмник виджета или приёмник
 * действий уведомления, но не сама `NovaVpnService`.
 *
 * Главный экран сюда намеренно не переведён: его остановка — это другая операция,
 * с отменой регистрации и перерисовкой экрана, и общей у них только последняя
 * строка.
 */
object NovaTunnelControl {

    /**
     * @param reason кто именно остановил — попадает в журнал. Без него в логе
     *        три одинаковые строки «остановка туннеля», и понять, нажали плитку,
     *        виджет или уведомление, нельзя.
     */
    fun stop(context: Context, reason: String) {
        val appContext = context.applicationContext
        val clientData = ClientData(appContext)
        clientData.clearTransientConnectingPending()
        clientData.clearSoftReapplyPending()
        clientData.clearRestartSession()
        clientData.saveServiceState(NovaVpnService.STATE_STOPPED)
        // `startService` к уже поднятой foreground-службе разрешён и из фона —
        // именно так это работает у виджета с самого начала.
        appContext.startService(
            Intent(appContext, NovaVpnService::class.java).apply { action = ACTION_STOP_VPN }
        )
        LogManager.log("$reason: остановка туннеля.")
    }

    /** Действие службы. Строка историческая, менять её нельзя. */
    const val ACTION_STOP_VPN = "STOP_VPN"
}

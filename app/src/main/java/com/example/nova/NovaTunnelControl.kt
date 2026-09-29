package com.example.nova

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Включение и выключение туннеля «снаружи экрана» — из плитки, виджета и уведомления.
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
        // Сначала команда службе, потом признаки — и только если команду приняли.
        //
        // Раньше порядок был обратный: признаки снимались, а `startService` шёл
        // следом и без присмотра. Стоит ему не дойти — фоновый запуск отклонён
        // прошивкой, службы уже нет, что угодно, — и получается худшее из
        // возможных расхождений: экран говорит «НЕ ПОДКЛЮЧЕНО», а туннель
        // работает, и ключик в строке состояния висит. Признаки при этом уже
        // сняты, так что даже перезаход на экран правды не покажет.
        //
        // `startService` к уже поднятой foreground-службе разрешён и из фона —
        // именно так это работает у виджета с самого начала, — но «разрешён» и
        // «дошёл» это разные вещи (I4: молчащего возврата в пути подключения не
        // бывает).
        val intent = Intent(appContext, NovaVpnService::class.java).apply { action = ACTION_STOP_VPN }
        val component = try {
            appContext.startService(intent)
        } catch (error: Throwable) {
            // Признаки не трогаем: пусть экран показывает живой туннель, потому
            // что он и правда живой.
            LogManager.log(
                "$reason: команда останова не дошла до службы — " +
                    "${error.javaClass.simpleName}: ${error.message}. " +
                    "Туннель остался поднятым, признаки не снимаем."
            )
            return
        }
        if (component == null) {
            // Службы нет — останавливать нечего, но признаки снять надо: иначе
            // они переживут смерть службы и оживят сеанс.
            LogManager.log("$reason: службы уже нет, снимаем только признаки.")
        }
        val clientData = ClientData(appContext)
        clientData.clearTransientConnectingPending()
        clientData.clearSoftReapplyPending()
        clientData.clearRestartSession()
        clientData.saveServiceState(NovaVpnService.STATE_STOPPED)
        LogManager.log("$reason: остановка туннеля.")
    }

    /**
     * Запуск туннеля «снаружи экрана» — из плитки, виджета и уведомления.
     *
     * Отдельным местом по той же причине, что и [stop], только беда здесь другая.
     * Намерение подключения несёт с собой **весь срез настроек**: служба живёт в
     * `:vpn`, её копия `SharedPreferences` своей жизнью не обновляется (I2), и
     * применяет она пришедшее через `commit()`. Урезанное намерение поэтому не
     * «не доедет» — оно сбросит на диск устаревший срез и сотрёт выбор человека
     * (режим импортированных профилей, маскировка, раздельное туннелирование,
     * обход по доменам). Это I19 дословно.
     *
     * Копий этого списка было две — в плитке и в виджете, слово в слово. Третья
     * ради уведомления — ровно тот способ, которым в этом проекте уже расходились
     * списки (G49), поэтому список один на всех.
     *
     * @param reason кто именно подключил — попадает в журнал.
     */
    fun start(context: Context, reason: String) {
        val appContext = context.applicationContext
        val clientData = ClientData(appContext)
        val intent = Intent(appContext, NovaVpnService::class.java).apply {
            action = NovaVpnService.ACTION_CONNECT_SMART
            putExtra(NovaVpnService.EXTRA_EXIT_REGION, clientData.getExitRegionPreference())
            // Выбор источника профилей едет вместе с регионом: без него служба
            // применяла только регион, а commit() сбрасывал на диск устаревший
            // срез и стирал режим импортированных.
            putExtra(
                NovaVpnService.EXTRA_IMPORTED_CONFIG_SOURCE_ENABLED,
                clientData.isImportedWarpOnlyModeEnabled(),
            )
            putExtra(
                NovaVpnService.EXTRA_IMPORTED_PROTOCOL_PREFERENCE,
                clientData.getImportedProtocolPreference(),
            )
            putExtra(NovaVpnService.EXTRA_REAPPLY_SPLIT_MODE, clientData.getSplitMode())
            putStringArrayListExtra(
                NovaVpnService.EXTRA_REAPPLY_SPLIT_APPS,
                ArrayList(clientData.getSplitApps()),
            )
            // «Прямой поток» едет тем же путём: настройки процесса `:vpn` своей
            // копией не обновляются, и без этих extras выбор человека до службы
            // просто не доезжает.
            putStringArrayListExtra(
                NovaVpnService.EXTRA_REAPPLY_DIRECT_APPS,
                ArrayList(clientData.getDirectApps()),
            )
            putStringArrayListExtra(
                NovaVpnService.EXTRA_REAPPLY_DIRECT_EXCLUDED,
                ArrayList(clientData.getDirectAppsExcluded()),
            )
            putExtra(
                NovaVpnService.EXTRA_REAPPLY_RUSSIAN_DIRECT_ENABLED,
                clientData.isRussianDirectAppsEnabled(),
            )
            putExtra(NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_ENABLED, clientData.getTrafficMaskEnabled())
            putExtra(NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_MODE, clientData.getTrafficMaskMode())
            putExtra(NovaVpnService.EXTRA_REAPPLY_TRAFFIC_MASK_HOST, clientData.getTrafficMaskHost())
            putExtra(NovaVpnService.EXTRA_REAPPLY_SNI_MASK_MODE, clientData.getSniMaskMode())
            putExtra(NovaVpnService.EXTRA_REAPPLY_SNI_MASK_LIST, clientData.getSniCustomListRaw())
            putExtra(NovaVpnService.EXTRA_REAPPLY_TUNNEL_MTU, clientData.getTunnelMtu())
            // «Обход по доменам» — по той же причине: список, записанный экраном, в
            // процессе `:vpn` не виден, и без extras он бы сохранялся и не действовал.
            putExtra(
                NovaVpnService.EXTRA_REAPPLY_DOMAIN_BYPASS_ENABLED,
                clientData.isDomainBypassEnabled(),
            )
            putExtra(
                NovaVpnService.EXTRA_REAPPLY_DOMAIN_BYPASS_ZONES,
                clientData.getDomainBypassZonesRaw(),
            )
            putExtra(
                NovaVpnService.EXTRA_REAPPLY_DOMAIN_BYPASS_CUSTOM,
                clientData.getDomainBypassCustomRaw(),
            )
        }
        // Отказ старта нельзя проглотить (I4): на Android 12+ фоновый запуск
        // foreground-службы прошивка вправе отклонить, и без строки в журнале это
        // выглядит как «кнопка не нажимается».
        runCatching { ContextCompat.startForegroundService(appContext, intent) }
            .onSuccess { LogManager.log("$reason: запуск туннеля.") }
            .onFailure { error ->
                LogManager.log(
                    "$reason: команда запуска не дошла до службы — " +
                        "${error.javaClass.simpleName}: ${error.message}."
                )
            }
    }

    /** Действие службы. Строка историческая, менять её нельзя. */
    const val ACTION_STOP_VPN = "STOP_VPN"
}

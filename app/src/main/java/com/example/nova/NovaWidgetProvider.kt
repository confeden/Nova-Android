package com.example.nova

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

/**
 * Виджет рабочего стола: две круглые кнопки, одно касание на действие.
 *
 * Левая кнопка включает и выключает туннель **не открывая приложение** — тем же
 * набором намерений, что и плитка в шторке ([NovaTileService]). Повторять их
 * пришлось буквально: намерение подключения несёт с собой весь срез настроек,
 * потому что служба применяет его через `commit()`, и урезанное намерение стёрло
 * бы выбор человека (режим импортированных профилей, маскировка, раздельное
 * туннелирование).
 *
 * Правая кнопка открывает экран и нажимает там «следующий профиль». Перебор
 * профилей живёт в [MainActivity] и знает про VLESS, MASQUE, Opera и Proton
 * по-разному; переписывать эту цепочку второй раз в приёмнике значило бы
 * завести вторую, расходящуюся с первой. Экран при этом всё равно нужен: смена
 * профиля переподнимает туннель, и результат человек должен видеть.
 *
 * Состояние кнопки питания приходит широковещанием [NovaVpnService.ACTION_VPN_STATE]
 * — оно уходит с `setPackage`, поэтому доходит и до приёмника из манифеста.
 * Системный `updatePeriodMillis` не используется вовсе: чаще получаса он не
 * бывает и будил бы приложение впустую.
 */
class NovaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { widgetId ->
            appWidgetManager.updateAppWidget(widgetId, buildViews(context))
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_TOGGLE -> {
                toggleTunnel(context)
                // Рисуем сразу, не дожидаясь широковещания службы: между нажатием и
                // первым сообщением о состоянии проходят сотни миллисекунд, и всё
                // это время кнопка показывала бы старое состояние.
                refresh(context)
            }
            ACTION_NEXT_PROFILE -> openNextProfile(context)
            NovaVpnService.ACTION_VPN_STATE -> refresh(context)
        }
    }

    private fun buildViews(context: Context): RemoteViews {
        val connected = isTunnelUp(ClientData(context))
        return RemoteViews(context.packageName, R.layout.widget_nova).apply {
            // Круг живёт в `src`, а не в фоне: фон рисуется по размеру вида и
            // обрезается, когда лаунчер отводит строку ниже содержимого, а
            // рисунок при `fitCenter` уменьшается целиком (см. widget_nova.xml).
            setImageViewResource(
                R.id.widget_btn_power,
                if (connected) R.drawable.widget_btn_power_on else R.drawable.widget_btn_power_off,
            )
            setOnClickPendingIntent(R.id.widget_btn_power, buildSelfPendingIntent(context, ACTION_TOGGLE, 7101))
            setOnClickPendingIntent(
                R.id.widget_btn_next,
                buildSelfPendingIntent(context, ACTION_NEXT_PROFILE, 7102),
            )
        }
    }

    private fun buildSelfPendingIntent(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, NovaWidgetProvider::class.java).apply { this.action = action }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun toggleTunnel(context: Context) {
        val clientData = ClientData(context)
        if (isTunnelUp(clientData)) {
            NovaTunnelControl.stop(context, "Виджет")
            return
        }
        val intent = Intent(context, NovaVpnService::class.java).apply {
            action = NovaVpnService.ACTION_CONNECT_SMART
            putExtra(NovaVpnService.EXTRA_EXIT_REGION, clientData.getExitRegionPreference())
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
        ContextCompat.startForegroundService(context, intent)
        LogManager.log("Виджет: запуск туннеля.")
    }

    private fun openNextProfile(context: Context) {
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            putExtra(MainActivity.EXTRA_WIDGET_ACTION, MainActivity.WIDGET_ACTION_NEXT_PROFILE)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { error -> LogManager.log("Виджет: не удалось открыть экран: ${error.message}") }
    }

    /**
     * Туннель считается поднятым по сохранённому состоянию.
     *
     * Плитка в шторке сверяется ещё и с системными сетями, потому что живёт
     * рядом с ними; виджету этого не нужно — он перерисовывается по тому же
     * широковещанию, которым служба и обновляет это состояние.
     */
    private fun isTunnelUp(clientData: ClientData): Boolean {
        val state = clientData.getServiceState()
        return state == NovaVpnService.STATE_CONNECTED || state == NovaVpnService.STATE_CONNECTING
    }

    private fun refresh(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, NovaWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val views = buildViews(context)
        ids.forEach { widgetId -> manager.updateAppWidget(widgetId, views) }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.nova.widget.TOGGLE"
        const val ACTION_NEXT_PROFILE = "com.example.nova.widget.NEXT_PROFILE"

        /**
         * Просит лаунчер поставить виджет на рабочий стол.
         *
         * Работает с Android 8: до неё системного запроса на закрепление нет
         * вовсе, и единственный путь — длинное нажатие на рабочем столе. Часть
         * лаунчеров отказывает и на новых версиях ([AppWidgetManager.isRequestPinAppWidgetSupported]),
         * поэтому вызов возвращает результат, а не молчит.
         */
        fun requestPin(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
            val manager = AppWidgetManager.getInstance(context) ?: return false
            if (!manager.isRequestPinAppWidgetSupported) return false
            return runCatching {
                manager.requestPinAppWidget(
                    ComponentName(context, NovaWidgetProvider::class.java),
                    null,
                    null,
                )
            }.getOrDefault(false)
        }
    }
}

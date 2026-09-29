package com.example.nova

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.RemoteViews

/**
 * Виджет рабочего стола: две круглые кнопки, одно касание на действие.
 *
 * Левая кнопка включает и выключает туннель **не открывая приложение** — через
 * [NovaTunnelControl], общий с плиткой в шторке ([NovaTileService]) и кнопкой в
 * уведомлении. Там же объяснено, почему намерение подключения несёт с собой весь
 * срез настроек, а останов — пять обязательных шагов.
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
        LogManager.setAppContext(context)
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
            // Описания для TalkBack в макете — русские заглушки: макет раздувает лаунчер,
            // и перевод интерфейса до него не доходит.
            setContentDescription(
                R.id.widget_btn_power,
                NovaLanguage.tr(context, "Подключить или отключить Nova"),
            )
            setContentDescription(R.id.widget_btn_next, NovaLanguage.tr(context, "Следующий профиль"))
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
        NovaTunnelControl.start(context, "Виджет")
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

    private fun refresh(context: Context) = refreshAll(context)

    private fun refreshAllInternal(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        val ids = manager.getAppWidgetIds(ComponentName(context, NovaWidgetProvider::class.java))
        if (ids.isEmpty()) return
        val views = buildViews(context)
        ids.forEach { widgetId -> manager.updateAppWidget(widgetId, views) }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.nova.widget.TOGGLE"

        /** Перерисовать все виджеты: например, после смены языка интерфейса. */
        fun refreshAll(context: Context) = NovaWidgetProvider().refreshAllInternal(context)
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

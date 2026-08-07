package com.example.nova

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Возвращает после перезагрузки телефона и обновления приложения то, что человек
 * включил до неё.
 *
 * И раздача, и VPN выключены по умолчанию — включает их пользователь осознанно.
 * Раз включил, выбор надо уважать: адрес и порт уже прописаны на телевизоре или
 * ноутбуке, а туннель должен подняться сам, без захода в приложение.
 *
 * Признак «VPN был включён» — сохранённое состояние службы. При явной остановке
 * пользователем туда записывается «остановлено», поэтому выключенный вручную VPN
 * после перезагрузки не воскреснет.
 */
class NovaBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        val appContext = context?.applicationContext ?: return
        val action = intent?.action.orEmpty()
        if (action !in HANDLED_ACTIONS) return

        val clientData = ClientData(appContext)
        val persistedState = clientData.getServiceState()
        val vpnWasOn = persistedState == NovaVpnService.STATE_CONNECTED ||
            persistedState == NovaVpnService.STATE_CONNECTING
        val gatewayWasOn = clientData.isLocalProxyEnabled()
        if (!vpnWasOn && !gatewayWasOn) return

        // Туннель поднимаем через восстановление последней сессии — оно вернёт и
        // выбранный профиль, и режим. Если VPN не был включён, ограничиваемся
        // шлюзом: поднимать туннель за пользователя не наше дело.
        val serviceAction = if (vpnWasOn) {
            NovaVpnService.ACTION_RESTORE_LAST_SESSION
        } else {
            NovaVpnService.ACTION_SYNC_LOCAL_PROXY
        }
        runCatching {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, NovaVpnService::class.java).apply {
                    this.action = serviceAction
                }
            )
            LogManager.log(
                "После \"$action\" восстанавливаем: " +
                    (if (vpnWasOn) "VPN" else "только раздачу") + "."
            )
        }.onFailure { error ->
            LogManager.log("Не удалось восстановить состояние после \"$action\": ${error.message}")
        }
    }

    private companion object {
        val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}

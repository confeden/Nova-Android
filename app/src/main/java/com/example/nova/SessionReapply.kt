package com.example.nova

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Одно описание того, как применить смену настроек к живому сеансу.
 *
 * ## Зачем отдельный объект
 *
 * Порядок был выписан дважды и по-разному. В настройках — полный: определить
 * желаемый транспорт, записать `CONNECTING`, поставить признак мягкого реаплая и
 * либо послать `ACTION_REAPPLY_CURRENT_SESSION`, либо — для живой Opera —
 * остановиться и дождаться остановки, прежде чем запускать заново. На главном
 * экране — усечённый: один `ACTION_REAPPLY_CURRENT_SESSION` без признака и без
 * записи состояния, а живая Opera не переключалась вовсе, только запоминалась «до
 * следующего подключения».
 *
 * Владелец попросил, чтобы смена региона или протокола на главном экране
 * переподключала **сразу**, ровно как в настройках. Второй копией порядка это
 * делать нельзя: именно так случается G49 — один путь узнаёт о новом шаге,
 * второй молчит. Поэтому порядок здесь один, а оба экрана его зовут.
 *
 * ## Почему у Opera свой путь
 *
 * `tun2proxy_stop()` планирует `exit(-1)` и убивает процесс `:vpn` (G3). Обычный
 * реаплай поверх живого сеанса Opera — известный способ уронить его целиком.
 * Поэтому для Opera здесь честный `stop-then-start`: остановка, опрос до
 * подтверждённой остановки (состояние службы **и** отсутствие системного VPN),
 * пауза и только потом новый запуск.
 *
 * ## Почему часы свои, а не экранные
 *
 * Опрос переживает экран: пользователь нажимает кнопку и уходит с экрана, а
 * дождаться остановки и запустить заново всё равно надо, иначе VPN останется
 * выключенным (I18, G83). Свой `Handler` на главном потоке живёт, пока жив
 * процесс, и не зависит от того, уцелела ли активность.
 */
object SessionReapply {

    /** Опрос уже идёт: второй `stop-then-start` поверх первого только помешает. */
    @Volatile
    private var controlledRestartPending = false

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Намерение «применить текущие настройки к идущему сеансу».
     *
     * Набор дополнительных полей — это ровно то, что служба перечитывает при
     * реаплае. Держать его в одном месте обязательно: пропущенное поле молча
     * применяет старое значение, и снаружи это выглядит как «настройка не
     * сработала».
     */
    fun buildIntent(context: Context, clientData: ClientData): Intent {
        return Intent(context, NovaVpnService::class.java).apply {
            action = NovaVpnService.ACTION_REAPPLY_CURRENT_SESSION
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
    }

    /**
     * Намерение «применить обход по доменам к идущему сеансу», без переподключения.
     *
     * Отдельное от [buildIntent] и намеренно короткое: службе нужны ровно три
     * значения, а любое лишнее поле здесь означало бы, что правка списка доменов
     * тихо применяет ещё и чужую настройку — регион, маскировку, раздельный
     * туннель. Читает их всё тот же `applyExplicitReapplyOverrides`, поэтому
     * разбор extras остаётся в одном месте.
     */
    fun buildDomainBypassIntent(context: Context, clientData: ClientData): Intent {
        return Intent(context, NovaVpnService::class.java).apply {
            action = NovaVpnService.ACTION_APPLY_DOMAIN_BYPASS
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
    }

    /**
     * Применить правку обхода по доменам к живому сеансу.
     *
     * Туннель не трогается вовсе: ядро перечитывает правила под мьютексом на
     * каждом DNS-ответе. Поэтому здесь нет ни `stop-then-start` для Opera, ни
     * записи `CONNECTING`, ни признака мягкого реаплая — рвать нечего, и врать
     * интерфейсу о переподключении не за чем.
     *
     * @return `false`, если намерение не ушло; вызывающий обязан сказать об этом
     *         пользователю, а не проглотить (I4).
     */
    fun applyDomainBypassToLiveSession(context: Context, clientData: ClientData): Boolean {
        val appContext = context.applicationContext
        return runCatching {
            ContextCompat.startForegroundService(
                appContext,
                buildDomainBypassIntent(appContext, clientData),
            )
        }.onFailure { error ->
            LogManager.log("Обход по доменам не доехал до службы — ${error.message}")
        }.isSuccess
    }

    /** Желаемый транспорт по текущим предпочтениям — он же уходит в состояние службы. */
    fun desiredBackend(clientData: ClientData): String {
        return if (clientData.shouldUseWarpTransport()) {
            NovaVpnService.BACKEND_WARP
        } else {
            "${NovaVpnService.BACKEND_OPERA}-${clientData.getPreferredOperaLabel()}"
        }
    }

    /**
     * Есть ли что переподключать.
     *
     * Три источника, и они дополняют друг друга: записанное состояние службы,
     * запомненный сеанс для перезапуска и, наконец, реально существующий
     * системный VPN. Одного состояния мало — оно пишется файлом и может отставать
     * на доли секунды после старта.
     */
    fun isSessionLikelyActive(context: Context, clientData: ClientData): Boolean {
        val serviceState = clientData.getServiceState()
        if (serviceState == NovaVpnService.STATE_CONNECTED || serviceState == NovaVpnService.STATE_CONNECTING) {
            return true
        }
        if (clientData.getRestartSession() != null) return true
        return hasActiveNovaSystemVpn(context, clientData)
    }

    /**
     * Нужен ли безопасный `stop-then-start` вместо обычного реаплая.
     *
     * Ответ намеренно осторожный: ошибка в сторону «нужен» стоит нескольких
     * лишних секунд, ошибка в другую сторону роняет `:vpn` (G3). Поэтому
     * достаточно любого признака Opera — записанного транспорта, запомненного
     * сеанса, живого системного VPN с её меткой или **желаемого** транспорта при
     * живом сеансе.
     */
    fun needsControlledOperaRestart(context: Context, clientData: ClientData): Boolean {
        val persistedBackend = clientData.getServiceBackend().trim().uppercase()
        if (persistedBackend.startsWith(NovaVpnService.BACKEND_OPERA)) return true

        val restartKind = clientData.getRestartSession()?.kind?.trim()?.uppercase().orEmpty()
        if (restartKind == "OPERA") return true

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val activeOperaVpn = cm?.allNetworks?.any { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@any false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@any false
            extractVpnTransportLabel(caps).contains("NovaOperaVPN", ignoreCase = true)
        } == true
        if (activeOperaVpn) return true

        return desiredBackend(clientData)
            .trim()
            .uppercase()
            .startsWith(NovaVpnService.BACKEND_OPERA) && hasActiveNovaSystemVpn(context, clientData)
    }

    /**
     * Применить изменения прямо к идущему сеансу.
     *
     * Сама выбирает путь: живая Opera — через остановку с ожиданием, всё
     * остальное — мягким реаплаем.
     *
     * @return `false`, если запустить не удалось; вызывающему это надо сказать
     *         пользователю, а не проглотить (I4).
     */
    fun applyToLiveSession(context: Context, clientData: ClientData): Boolean {
        return if (needsControlledOperaRestart(context, clientData)) {
            launchControlledOperaRestart(context, clientData)
        } else {
            launchDirect(context, clientData)
        }
    }

    /** Мягкий реаплай: служба перечитывает предпочтения и пересобирает туннель. */
    fun launchDirect(context: Context, clientData: ClientData): Boolean {
        val appContext = context.applicationContext
        clientData.saveServiceState(NovaVpnService.STATE_CONNECTING, desiredBackend(clientData))
        clientData.markSoftReapplyPending()
        return runCatching {
            ContextCompat.startForegroundService(appContext, buildIntent(appContext, clientData))
        }.onFailure { error ->
            clientData.clearSoftReapplyPending()
            LogManager.log("Мягкое применение настроек VPN не запустилось — ${error.message}")
        }.isSuccess
    }

    /**
     * Остановка и запуск заново — единственный безопасный способ для Opera.
     *
     * Признак `controlledRestartPending` общий на процесс: настройки и главный
     * экран живут в одном, и второй `stop` поверх идущего опроса остановил бы уже
     * новый сеанс.
     */
    fun launchControlledOperaRestart(context: Context, clientData: ClientData): Boolean {
        if (controlledRestartPending) {
            LogManager.log("Безопасный перезапуск Opera уже запланирован — настройки обновлены, ждём его.")
            return true
        }
        val appContext = context.applicationContext
        controlledRestartPending = true
        clientData.saveServiceState(NovaVpnService.STATE_CONNECTING, desiredBackend(clientData))
        clientData.markSoftReapplyPending(35_000L)
        return runCatching {
            LogManager.log("Активный Opera-сеанс меняем через безопасный stop-then-start из основного процесса.")
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, NovaVpnService::class.java).apply {
                    action = NovaVpnService.ACTION_STOP_FOR_SOFT_RESTART
                },
            )
            pollUntilStopped(appContext, clientData, attempt = 0)
        }.onFailure { error ->
            controlledRestartPending = false
            clientData.clearSoftReapplyPending()
            LogManager.log("Не удалось запустить безопасный Opera restart: ${error.message}")
        }.isSuccess
    }

    private fun pollUntilStopped(appContext: Context, clientData: ClientData, attempt: Int) {
        handler.postDelayed({
            val serviceStopped = clientData.getServiceState() == NovaVpnService.STATE_STOPPED
            val novaVpnStillVisible = hasActiveNovaSystemVpn(appContext, clientData)
            if ((serviceStopped && !novaVpnStillVisible) || attempt >= MAX_STOP_POLLS) {
                launchAfterStop(appContext, clientData)
            } else {
                pollUntilStopped(appContext, clientData, attempt + 1)
            }
        }, if (attempt == 0) 220L else 160L)
    }

    private fun launchAfterStop(appContext: Context, clientData: ClientData) {
        handler.postDelayed({
            controlledRestartPending = false
            clientData.markSoftReapplyPending(25_000L)
            runCatching {
                LogManager.log("Старый Opera VPN полностью остановлен. Запускаем новый connect-сеанс в чистом процессе.")
                ContextCompat.startForegroundService(appContext, buildIntent(appContext, clientData))
            }.onFailure { error ->
                clientData.clearSoftReapplyPending()
                LogManager.log("Не удалось заново запустить VPN после безопасного Opera restart: ${error.message}")
            }
        }, 800L)
    }

    /**
     * Виден ли системе живой VPN, который с высокой вероятностью наш.
     *
     * «Не можем определить» — не то же самое, что «не наш» (I16): Android 9
     * прячет владельца VPN, и ответ «нет» рвал бы здоровый туннель. Поэтому
     * рядом с owner-uid стоят метка сеанса и, последним, локальные признаки —
     * состояние службы, признак мягкого реаплая, недавний сеанс на перезапуск.
     */
    fun hasActiveNovaSystemVpn(context: Context, clientData: ClientData): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false

        fun isLikelyNovaVpn(network: Network): Boolean {
            val caps = cm.getNetworkCapabilities(network) ?: return false
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
            if (isNovaVpnOwner(context, caps)) return true
            val transportInfo = extractVpnTransportLabel(caps)
            return transportInfo.contains("NovaVPN", ignoreCase = true) ||
                transportInfo.contains("NovaOperaVPN", ignoreCase = true)
        }

        fun networkId(network: Network): Int = network.toString().toIntOrNull() ?: -1

        fun score(network: Network): Int {
            val caps = cm.getNetworkCapabilities(network) ?: return Int.MIN_VALUE
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return Int.MIN_VALUE
            var score = 0
            if (isLikelyNovaVpn(network)) score += 1_000
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 200
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) score += 50
            return score
        }

        val active = cm.activeNetwork
        if (active != null && isLikelyNovaVpn(active)) return true

        val bestVpn = cm.allNetworks
            .filter { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }
            .maxWithOrNull(compareBy<Network> { score(it) }.thenBy { networkId(it) })

        if (bestVpn != null && isLikelyNovaVpn(bestVpn)) return true

        return bestVpn != null && hasStrongLocalEvidence(context, clientData)
    }

    private fun hasStrongLocalEvidence(context: Context, clientData: ClientData): Boolean {
        val serviceState = clientData.getServiceState()
        if (
            serviceState == NovaVpnService.STATE_CONNECTED ||
            serviceState == NovaVpnService.STATE_CONNECTING ||
            clientData.isSoftReapplyPending() ||
            clientData.isTransientConnectingPending()
        ) {
            return isNovaVpnServiceRunning(context)
        }
        val updatedAt = clientData.getServiceStateUpdatedAt()
        val ageMs = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L)
        return clientData.getRestartSession() != null && ageMs in 0..90_000L && isNovaVpnServiceRunning(context)
    }

    private fun isNovaVpnServiceRunning(context: Context): Boolean {
        @Suppress("DEPRECATION")
        return (context.getSystemService(ActivityManager::class.java)?.getRunningServices(Int.MAX_VALUE) ?: emptyList())
            .any { service -> service.service?.className == NovaVpnService::class.java.name }
    }

    private fun isNovaVpnOwner(context: Context, caps: NetworkCapabilities?): Boolean {
        return extractVpnOwnerUid(caps) == context.applicationInfo.uid
    }

    private fun extractVpnOwnerUid(caps: NetworkCapabilities?): Int? {
        if (caps == null) return null
        val reflectedOwnerUid = try {
            val ownerUid = caps.javaClass.methods
                .firstOrNull { it.name == "getOwnerUid" && it.parameterCount == 0 }
                ?.invoke(caps) as? Int
            ownerUid?.takeIf { it >= 0 }
        } catch (_: Throwable) {
            null
        }
        if (reflectedOwnerUid != null) return reflectedOwnerUid

        return Regex("(?:OwnerUid|EstablishingAppUid):\\s*(\\d+)")
            .find(caps.toString())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it >= 0 }
    }

    private fun extractVpnTransportLabel(caps: NetworkCapabilities?): String {
        val transportInfo = NetworkCapabilitiesCompat.getTransportInfo(caps) ?: return ""
        return try {
            val sessionId = transportInfo.javaClass.methods
                .firstOrNull { it.name == "getSessionId" && it.parameterCount == 0 }
                ?.invoke(transportInfo) as? String
            sessionId?.takeIf { it.isNotBlank() } ?: transportInfo.toString()
        } catch (_: Throwable) {
            transportInfo.toString()
        }
    }

    /** Сколько раз опрашиваем остановку, прежде чем запускать заново всё равно. */
    private const val MAX_STOP_POLLS = 28
}

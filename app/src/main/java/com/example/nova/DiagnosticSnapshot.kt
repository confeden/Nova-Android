package com.example.nova

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationManagerCompat
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.net.Inet4Address
import java.net.Inet6Address
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Снимок того, в каких условиях шёл сеанс: настройки, сеть, ограничения системы и
 * почему прошлые процессы умирали.
 *
 * Разбор почти любого отказа начинается с одних и тех же вопросов — какой регион
 * выбран, Wi-Fi или мобильная сеть и какой оператор, включён ли частный DNS, не
 * держит ли система приложение в экономии заряда. Журнал отвечал на них по кускам
 * или не отвечал вовсе. Теперь ответ стоит в начале каждого отрезка записи, в
 * начале каждого сеанса и в шапке отправляемого файла.
 *
 * Настройки печатаются **глазами того процесса, который пишет строку**. Это
 * намеренно: `:vpn` кэширует `SharedPreferences` (I2), и расхождение двух снимков
 * в одном журнале — прямое доказательство дефекта вида G122.
 *
 * Личного здесь нет: имён приложений и адресов не печатаем — только число
 * приложений и код оператора (MCC/MNC). От имени частного DNS и своих резолверов
 * остаются два последних уровня: у NextDNS и AdGuard DNS в начале имени стоит
 * номер аккаунта.
 */
object DiagnosticSnapshot {
    private const val EXIT_STAMP_FILE = "diagnostic_exit_stamp.json"
    private const val EXIT_LOOKBACK_MS = 48 * 60 * 60 * 1000L
    private const val EXIT_MAX_ENTRIES = 10
    private const val SNAPSHOT_OVERLAP_MS = 30_000L

    @Volatile
    private var runningVpnService: WeakReference<VpnService>? = null

    // Новый `:vpn` пишет снимок дважды подряд: как начало отрезка журнала (первая
    // строка процесса) и как старт сеанса (переход в CONNECTING) — с разницей в
    // сотню миллисекунд. Второй из них пропускается. Одинаковые подряд не
    // сравниваются: включение и тут же очистка журнала должны оставить снимок.
    @Volatile
    private var lastCaptureSnapshotAtMs = 0L

    @Volatile
    private var lastSessionSnapshotAtMs = 0L

    /** Служба VPN этого процесса: только у неё можно спросить про постоянный VPN. */
    fun attachVpnService(service: VpnService?) {
        runningVpnService = service?.let { WeakReference(it) }
    }

    /** Начало отрезка журнала в процессе. Зовётся из потока записи журнала. */
    fun logCaptureContext(context: Context) {
        // Строки снимка — INFO. На уровне WARN/ERROR они бы отбросились, а отметки о
        // смертях процессов уже были бы израсходованы, и причины не попали бы никуда.
        if (!LogManager.isCapturing(DiagnosticLogLevel.INFO)) return
        val now = SystemClock.elapsedRealtime()
        if (!isRecent(lastSessionSnapshotAtMs, now)) {
            lastCaptureSnapshotAtMs = now
            LogManager.i(describeSettings(context))
            LogManager.i(describeSystem(context, vpnService = null))
        }
        describeProcessExits(context, onlyUnreported = true).forEach { LogManager.i(it) }
    }

    /** Новый сеанс VPN — из `:vpn`, фоновым потоком: здесь десяток вызовов в систему. */
    fun logSessionStart(service: VpnService) {
        val now = SystemClock.elapsedRealtime()
        if (isRecent(lastCaptureSnapshotAtMs, now)) return
        lastSessionSnapshotAtMs = now
        LogManager.i("Старт сеанса. " + describeSettings(service))
        LogManager.i(describeSystem(service, vpnService = service))
    }

    private fun isRecent(stampMs: Long, nowMs: Long): Boolean =
        stampMs != 0L && nowMs - stampMs < SNAPSHOT_OVERLAP_MS

    /** Отчёт для отправки: шапка, снимок, затем весь журнал. */
    fun buildReport(context: Context, maxLogChars: Int = Int.MAX_VALUE): String {
        val clientData = ClientData(context)
        val now = Date()
        val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(now)
        val snapshot = runCatching { clientData.getTunnelUiSnapshot() }.getOrNull()
        val directSnapshot = runCatching { clientData.getDirectUiSnapshot() }.getOrNull()
        val lines = buildList {
            add("Nova diagnostic log")
            add("generated_at=$utc, device_utc_offset=${utcOffset()}")
            add("app_version=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.FLAVOR})")
            add("android=${Build.VERSION.RELEASE ?: "unknown"} sdk=${Build.VERSION.SDK_INT}, device=${Build.MANUFACTURER} ${Build.MODEL}, abi=${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}")
            add("service_state=${safe { clientData.getServiceState().ifBlank { "unknown" } }}")
            add("backend=${safe { clientData.getServiceBackend().ifBlank { "unknown" } }}")
            add("exit_preference=${safe { clientData.getExitRegionPreference() }}")
            add("vpn_snapshot_backend=${snapshot?.backend?.ifBlank { "unknown" } ?: "unknown"}")
            add("vpn_snapshot_country=${snapshot?.country?.ifBlank { "unknown" } ?: "unknown"}")
            add("direct_snapshot_country=${directSnapshot?.country?.ifBlank { "unknown" } ?: "unknown"}")
            add("last_transport_notice=${safe { clientData.getLastTransportNotice().ifBlank { "-" } }}")
            add("logging=${safe { clientData.getDiagnosticLogSettingsSummary() }}")
            add("")
            add(describeSettings(context))
            add(describeSystem(context, vpnService = null))
            val exits = describeProcessExits(context, onlyUnreported = false)
            if (exits.isNotEmpty()) {
                add("")
                addAll(exits)
            }
            add("")
            add("--- logs ---")
            val persisted = LogManager.getPersistedLogs(maxLogChars)
            if (persisted.isBlank()) {
                add("Логов пока нет")
            } else {
                add(persisted)
            }
        }
        return DiagnosticLogSanitizer.sanitize(lines.joinToString("\n"))
    }

    // ---- настройки ----

    fun describeSettings(context: Context): String {
        val clientData = ClientData(context)
        val fields = buildList {
            add("регион=" + safe { clientData.getExitRegionPreference() })
            add("Opera=" + safe { clientData.getOperaSubRegionPreference() })
            add("страна Proton=" + safe { clientData.getProtonCountryPreference().ifBlank { "авто" } })
            add("вход Tor=" + safe { TorEntryModeStore.read(context) })
            add("импорт: источник активен=" + safe { yesNo(clientData.isImportedConfigSourceActive()) } +
                ", протокол=" + safe { clientData.getImportedProtocolPreference() } +
                ", только импорт=" + safe { yesNo(clientData.isImportedWarpOnlyModeEnabled()) } +
                ", профилей VLESS=" + safe { clientData.getVlessProfileLinks().size.toString() })
            add("свои профили WARP=" + safe { yesNo(clientData.isGeneratedWarpEnabled()) })
            add("SNI=" + safe { clientData.getSniMaskMode() })
            add("маска трафика=" + safe {
                if (clientData.getTrafficMaskEnabled()) clientData.getTrafficMaskMode() else "выкл"
            })
            add("адаптация I1=" + safe { yesNo(clientData.isAwgI1AdaptationEnabled()) })
            add("MTU=" + safe { clientData.getTunnelMtu().toString() })
            add("раздельное туннелирование=" + safe {
                val apps = clientData.getSplitApps().size
                when (clientData.getSplitMode()) {
                    0 -> "выкл"
                    1 -> "только выбранные через VPN ($apps прил.)"
                    2 -> "выбранные мимо VPN ($apps прил.)"
                    else -> "режим ${clientData.getSplitMode()} ($apps прил.)"
                }
            })
            add("прямой поток=" + safe {
                if (clientData.isRussianDirectAppsEnabled()) {
                    "вкл (своих +${clientData.getDirectApps().size}, убрано ${clientData.getDirectAppsExcluded().size})"
                } else {
                    "выкл"
                }
            })
            add("обход по доменам=" + safe { yesNo(clientData.isDomainBypassEnabled()) })
            add("раздача=" + safe { yesNo(clientData.isLocalProxyEnabled()) })
            add("ключ WARP+=" + safe { yesNo(clientData.getWarpPlusLicense().isNotBlank()) })
            add("язык=" + safe { NovaLanguage.current(context).code })
            add("DNS=" + safe { describeDns(context, clientData) })
        }
        return "Настройки (процесс ${LogManager.processLabel}): " + fields.joinToString("; ")
    }

    /**
     * DNS без личного: вид правил и их число, путь, от первого резолвера — два
     * последних уровня имени, от приложений — только то, что своё правило задано.
     *
     * Экранная сводка (`ClientData.getDnsSettingsSummary`) сюда не годится: она
     * печатает имя резолвера целиком — у NextDNS и AdGuard DNS в нём номер аккаунта,
     * у DoH он бывает и в пути — и названия выбранных приложений. Санитайзер журнала
     * голое имя хоста не трогает, так что ушло бы как есть.
     */
    private fun describeDns(context: Context, clientData: ClientData): String {
        val appRule = if (clientData.getConfiguredAppDnsOverride() != null) ", своё для приложения" else ""
        val ruleSet = DnsRulesStore.load(context)
        val active = ruleSet.rules.filter { it.enabled }
        if (ruleSet.enabled && active.isNotEmpty()) {
            val kinds = active.groupingBy { it.kind.name.lowercase(Locale.ROOT) }.eachCount()
                .entries.joinToString(" ") { (kind, count) -> if (count > 1) "$kind×$count" else kind }
            val first = resolverTail(active.first())?.let { ", первый $it" }.orEmpty()
            return "правила ($kinds)$first, путь ${ruleSet.routeMode.storageValue()}$appRule"
        }
        val config = clientData.getDnsSettingsConfig()
        if (!config.globalEnabled) return "встроенная цепочка$appRule"
        val servers = listOf(config.globalPrimaryDns, config.globalSecondaryDns).count { it.isNotBlank() }
        return "свои серверы ($servers), путь ${config.routeMode}$appRule"
    }

    /** Хвост имени резолвера; для адреса-литерала — ничего: сам адрес не нужен, вид уже назван. */
    private fun resolverTail(rule: DnsRule): String? {
        val value = rule.value.trim()
        val host = when (rule.kind) {
            DnsRule.Kind.PROVIDER -> return "провайдер"
            DnsRule.Kind.PLAIN -> return null
            else -> if ("://" in value) {
                runCatching { java.net.URI(value).host }.getOrNull()
            } else {
                value.substringBefore('/').substringBefore(':')
            }
        }
        if (host.isNullOrBlank() || ':' in host || host.all { it.isDigit() || it == '.' }) return null
        return domainTail(host)
    }

    // ---- система и сеть ----

    fun describeSystem(context: Context, vpnService: VpnService?): String {
        val service = vpnService ?: runningVpnService?.get()
        val fields = buildList {
            add("сети: " + safe { describeNetworks(context) })
            safe { describeOperator(context) }.takeIf { it.isNotBlank() }?.let { add(it) }
            add("согласие VPN=" + safe { yesNo(VpnService.prepare(context) == null) })
            if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add("постоянный VPN=" + safe { yesNo(service.isAlwaysOn) } +
                    ", блокировка без VPN=" + safe { yesNo(service.isLockdownEnabled) })
            }
            add(safe { describePower(context) })
            add("уведомления=" + safe { yesNo(NotificationManagerCompat.from(context).areNotificationsEnabled()) })
            add(safe { describeMemory(context) })
        }
        return "Система: " + fields.joinToString("; ")
    }

    private fun describeNetworks(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "нет ConnectivityManager"
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        if (networks.isEmpty()) return "нет ни одной"
        val parts = networks.mapNotNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            val transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ETHERNET"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "BLUETOOTH"
                else -> "OTHER"
            }
            val flags = mutableListOf<String>()
            flags += if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "проверена" else "НЕ проверена"
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) flags += "портал входа"
            flags += if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "безлимитная" else "лимитная"
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                transport == "CELLULAR" &&
                !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
            ) {
                flags += "роуминг"
            }
            cm.getLinkProperties(network)?.let { link ->
                val hasV4 = link.linkAddresses.any { it.address is Inet4Address }
                val hasV6 = link.linkAddresses.any { address ->
                    val ip = address.address
                    ip is Inet6Address && !ip.isLinkLocalAddress && !ip.isSiteLocalAddress
                }
                flags += "IPv4 ${yesNo(hasV4)}"
                flags += "IPv6 ${yesNo(hasV6)}"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    flags += if (link.isPrivateDnsActive) {
                        "частный DNS ${link.privateDnsServerName?.let(::domainTail) ?: "автоматический"}"
                    } else {
                        "частный DNS выкл"
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && link.mtu > 0) {
                    flags += "MTU ${link.mtu}"
                }
            }
            "$transport(${flags.joinToString(", ")})"
        }
        val dataSaver = when (cm.restrictBackgroundStatus) {
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED -> "вкл"
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_WHITELISTED -> "вкл, Nova в исключениях"
            else -> "выкл"
        }
        return parts.joinToString(" + ") + "; экономия трафика=$dataSaver"
    }

    /**
     * Оператор — кодом MCC/MNC: по нему видно страну и сеть, а от этого зависит, режет
     * ли мобильная сеть протоколы по белым спискам. Человека он не выдаёт.
     */
    private fun describeOperator(context: Context): String {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return ""
        val telephony = context.getSystemService(TelephonyManager::class.java) ?: return ""
        val network = telephony.networkOperator.orEmpty()
        val sim = telephony.simOperator.orEmpty()
        if (network.isBlank() && sim.isBlank()) return "оператор: нет SIM или сети"
        val name = telephony.networkOperatorName.orEmpty().ifBlank { "?" }
        val country = telephony.networkCountryIso.orEmpty().ifBlank { "?" }
        val roaming = if (telephony.isNetworkRoaming) ", роуминг" else ""
        val simPart = if (sim.isNotBlank() && sim != network) ", SIM $sim" else ""
        return "оператор: сеть ${network.ifBlank { "?" }} «$name» ($country)$simPart$roaming"
    }

    private fun describePower(context: Context): String {
        val fields = mutableListOf<String>()
        context.getSystemService(PowerManager::class.java)?.let { power ->
            fields += "экран ${if (power.isInteractive) "вкл" else "выкл"}"
            fields += "экономия заряда ${yesNo(power.isPowerSaveMode)}"
            fields += "doze ${yesNo(power.isDeviceIdleMode)}"
            fields += "без оптимизации батареи ${yesNo(power.isIgnoringBatteryOptimizations(context.packageName))}"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            context.getSystemService(ActivityManager::class.java)?.let { activity ->
                fields += "фон ограничен ${yesNo(activity.isBackgroundRestricted)}"
            }
            context.getSystemService(UsageStatsManager::class.java)?.let { usage ->
                fields += "корзина ожидания ${standbyBucketName(usage.appStandbyBucket)}"
            }
        }
        return "питание: " + fields.joinToString(", ")
    }

    private fun describeMemory(context: Context): String {
        val activity = context.getSystemService(ActivityManager::class.java) ?: return "память: ?"
        val info = ActivityManager.MemoryInfo()
        activity.getMemoryInfo(info)
        val availMb = info.availMem / (1024 * 1024)
        val totalMb = info.totalMem / (1024 * 1024)
        val low = if (info.lowMemory) ", система считает памяти мало" else ""
        return "память: свободно $availMb из $totalMb МБ$low"
    }

    // ---- гибель прошлых процессов ----

    /**
     * Почему умирали прошлые процессы Nova — по записям самой системы (API 30+).
     *
     * «VPN отключился сам» чаще всего означает, что `:vpn` убили: за нехватку памяти,
     * за фон, при обновлении. Изнутри процесс этого записать не может — он уже мёртв.
     * А система помнит, и это единственный источник, где причина есть.
     *
     * Основной процесс сообщает обо всех процессах, остальные — только о себе:
     * иначе при включении журнала одна и та же смерть попала бы в него дважды.
     */
    fun describeProcessExits(context: Context, onlyUnreported: Boolean): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return runCatching { describeProcessExitsApi30(context, onlyUnreported) }
            .getOrElse { error -> listOf("История завершения процессов не прочиталась: ${error.javaClass.simpleName}: ${error.message}") }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describeProcessExitsApi30(context: Context, onlyUnreported: Boolean): List<String> {
        val activity = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val packageName = context.packageName
        val ownLabel = LogManager.processLabel
        val ownProcessName = if (ownLabel == "main") packageName else "$packageName:$ownLabel"
        val lookbackStart = System.currentTimeMillis() - EXIT_LOOKBACK_MS
        val stampFile = File(context.filesDir, EXIT_STAMP_FILE)
        val stamps = if (onlyUnreported) readStamps(stampFile) else JSONObject()
        val exits = activity.getHistoricalProcessExitReasons(packageName, 0, 32)
            .filter { exit ->
                val relevantProcess = ownLabel == "main" || exit.processName == ownProcessName
                relevantProcess &&
                    exit.timestamp >= lookbackStart &&
                    exit.timestamp > stamps.optLong(exit.processName, 0L)
            }
            .sortedBy { it.timestamp }
            .takeLast(EXIT_MAX_ENTRIES)
        if (exits.isEmpty()) return emptyList()
        if (onlyUnreported) {
            exits.forEach { exit ->
                if (exit.timestamp > stamps.optLong(exit.processName, 0L)) {
                    stamps.put(exit.processName, exit.timestamp)
                }
            }
            runCatching { stampFile.writeText(stamps.toString(), Charsets.UTF_8) }
        }
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return exits.map { exit ->
            val process = exit.processName.substringAfter(':', "main")
            val description = exit.description?.takeIf { it.isNotBlank() }?.let { ", описание: $it" }.orEmpty()
            "Прошлый процесс $process (pid ${exit.pid}) завершился ${format.format(Date(exit.timestamp))}: " +
                "${exitReasonName(exit.reason)}, статус ${exit.status}, " +
                "важность ${exit.importance} ${importanceName(exit.importance)}, " +
                "PSS ${exit.pss / 1024} МБ, RSS ${exit.rss / 1024} МБ$description"
        }
    }

    private fun readStamps(file: File): JSONObject =
        runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrDefault(JSONObject())

    private fun exitReasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF (завершился сам)"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (убит сигналом)"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY (убит за нехватку памяти)"
        ApplicationExitInfo.REASON_CRASH -> "CRASH (падение Java/Kotlin)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE (падение нативного кода)"
        ApplicationExitInfo.REASON_ANR -> "ANR (не отвечал)"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE (сменились разрешения)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE (убит за расход ресурсов)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED (остановлен пользователем)"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER (решение системы)"
        14 -> "FREEZER (заморожен системой)"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED (обновление)"
        else -> "UNKNOWN ($reason)"
    }

    private fun importanceName(importance: Int): String = when (importance) {
        100 -> "FOREGROUND"
        125 -> "FOREGROUND_SERVICE"
        200 -> "VISIBLE"
        230 -> "PERCEPTIBLE"
        300 -> "SERVICE"
        325 -> "TOP_SLEEPING"
        350 -> "CANT_SAVE_STATE"
        400 -> "CACHED"
        1000 -> "GONE"
        else -> ""
    }

    private fun standbyBucketName(bucket: Int): String = when (bucket) {
        5 -> "EXEMPTED"
        10 -> "ACTIVE"
        20 -> "WORKING_SET"
        30 -> "FREQUENT"
        40 -> "RARE"
        45 -> "RESTRICTED"
        50 -> "NEVER"
        else -> bucket.toString()
    }

    // ---- мелочи ----

    private fun utcOffset(): String {
        val minutes = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (minutes < 0) "-" else "+"
        return String.format(Locale.US, "UTC%s%02d:%02d", sign, Math.abs(minutes) / 60, Math.abs(minutes) % 60)
    }

    private fun yesNo(value: Boolean): String = if (value) "да" else "нет"

    /** `abc123.dns.nextdns.io` → `…nextdns.io`: провайдер виден, аккаунт нет. */
    private fun domainTail(host: String): String {
        val labels = host.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }
        return if (labels.size <= 2) labels.joinToString(".") else "…" + labels.takeLast(2).joinToString(".")
    }

    private inline fun safe(block: () -> String): String =
        try {
            block()
        } catch (error: Throwable) {
            "?(${error.javaClass.simpleName})"
        }
}

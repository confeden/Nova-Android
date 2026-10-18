package com.example.nova

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Кэш и замер задержки импортированных профилей.
 *
 * Кэш живёт в SharedPreferences: его читает и пишет только процесс интерфейса.
 * Отключённые профили — в файле (см. ClientData), потому что их читает `:vpn`.
 *
 * Замер по протоколу:
 * - VLESS (TCP): время установления TCP-соединения с host:port мимо туннеля
 *   ([ProtonLatency.probeTcpRttMs] с привязкой к сети без VPN). Это тот же замер,
 *   которым ранжируется список Proton: он не требует поднятого VPN и не трогает
 *   его сессию.
 * - AWG/WireGuard (UDP): рукопожатием мерить нельзя — оно отбирает ключ у живого
 *   туннеля (I27). Остаётся время DNS-разрешения имени. Для IP-адреса замера нет,
 *   и профиль получает [ImportedProfileRules.NOT_MEASURABLE] («—»).
 */
object ImportedProfilePingStore {

    private const val PREFS = "imported_profile_pings"
    private const val KEY = "pings"

    /** Одна запись кэша: результат и момент, когда он получен (мс от epoch). */
    data class Entry(val pingMs: Int, val measuredAt: Long)

    /** Цель замера: что и как мерить. */
    data class Target(val id: String, val host: String, val port: Int, val tcp: Boolean)

    /**
     * Все записи пишутся одним потоком по очереди: порядок записей совпадает с
     * порядком действий пользователя (отключил, включил), и SharedPreferences не
     * получает гонки за один файл. Поток демон — не держит процесс.
     */
    private val io = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "imported-profile-io").apply { isDaemon = true }
    }

    /** Замеры живут дольше экрана в своём scope; отмена при закрытии — в вызывающем коде. */
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun readAll(context: Context): Map<String, Entry> {
        val raw = prefs(context).getString(KEY, null) ?: return emptyMap()
        return try {
            val root = JSONObject(raw)
            val result = HashMap<String, Entry>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = root.optJSONObject(key) ?: continue
                result[key] = Entry(item.optInt("ms", ImportedProfileRules.NOT_MEASURABLE), item.optLong("at", 0L))
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** Записывает результат в фоне; вызывать можно с главного потока. */
    fun writeAsync(context: Context, id: String, pingMs: Int, measuredAt: Long) {
        val appContext = context.applicationContext
        io.execute {
            try {
                val current = readAll(appContext).toMutableMap()
                current[id] = Entry(pingMs, measuredAt)
                val root = JSONObject()
                for ((key, entry) in current) {
                    root.put(key, JSONObject().put("ms", entry.pingMs).put("at", entry.measuredAt))
                }
                prefs(appContext).edit().putString(KEY, root.toString()).apply()
            } catch (_: Exception) {
                // Кэш — удобство: без записи список просто замерит профиль снова.
            }
        }
    }

    /** Отключение или включение профиля — в том же порядке, что и запись кэша. */
    fun persistDisabledAsync(clientData: ClientData, id: String, disabled: Boolean) {
        io.execute { clientData.setImportedProfileDisabled(id, disabled) }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Сеть без VPN: замер идёт мимо туннеля. `null`, если такой сети нет, — тогда
     * замер идёт по маршруту по умолчанию, и это видно только по его результату.
     */
    fun underlyingNetwork(context: Context): Network? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        fun direct(network: Network): Boolean {
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        cm.activeNetwork?.takeIf(::direct)?.let { return it }
        return cm.allNetworks.firstOrNull(::direct)
    }

    /**
     * Замеряет все цели параллельно, не больше [ImportedProfileRules.MAX_PARALLEL_PROBES]
     * сразу, каждую с таймаутом [ImportedProfileRules.PROBE_TIMEOUT_MS].
     * [onResult] вызывается в контексте вызывающего scope (главный поток для экрана).
     */
    suspend fun measureAll(
        network: Network?,
        targets: List<Target>,
        onResult: (String, Int) -> Unit,
    ) {
        if (targets.isEmpty()) return
        val gate = Semaphore(ImportedProfileRules.MAX_PARALLEL_PROBES)
        coroutineScope {
            for (target in targets) {
                launch {
                    gate.withPermit {
                        onResult(target.id, measureBounded(network, target))
                    }
                }
            }
        }
    }

    private suspend fun measureBounded(network: Network?, target: Target): Int {
        // Замер в отдельном scope: блокирующий вызов не умеет отменяться, и ждать его
        // до конца значило бы держать слот параллельности дольше таймаута.
        val probe = probeScope.async { measureOne(network, target) }
        return withTimeoutOrNull(ImportedProfileRules.PROBE_TIMEOUT_MS) { probe.await() }
            ?: run {
                probe.cancel()
                ImportedProfileRules.TIMED_OUT
            }
    }

    private fun measureOne(network: Network?, target: Target): Int {
        if (target.tcp) {
            val rtt = ProtonLatency.probeTcpRttMs(
                host = target.host,
                port = target.port,
                timeoutMs = ImportedProfileRules.PROBE_TIMEOUT_MS.toInt(),
                protect = { socket -> network?.bindSocket(socket) },
            )
            return if (rtt > 0) rtt else ImportedProfileRules.TIMED_OUT
        }
        if (isIpLiteral(target.host)) return ImportedProfileRules.NOT_MEASURABLE
        val started = System.nanoTime()
        return try {
            if (network != null) network.getAllByName(target.host) else InetAddress.getAllByName(target.host)
            ((System.nanoTime() - started) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            ImportedProfileRules.TIMED_OUT
        }
    }

    private fun isIpLiteral(host: String): Boolean {
        val trimmed = host.trim().trim('[', ']')
        return trimmed.contains(':') || Regex("""\d{1,3}(\.\d{1,3}){3}""").matches(trimmed)
    }
}

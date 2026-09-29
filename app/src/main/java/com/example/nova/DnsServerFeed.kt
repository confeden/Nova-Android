package com.example.nova

import android.content.Context
import android.util.AtomicFile
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Адрес собственного сервера DNS-туннеля Nova — встроенного профиля.
 *
 * ## Почему лента, а не константа
 *
 * Сервер — одна машина, и её адрес может смениться (переезд, блокировка). Если
 * он зашит только в сборку, смена адреса означает новую версию и недели, пока
 * она до всех доедет. Поэтому адрес публикуется в `nova_updates` рядом с
 * лентой Opera, и приложение сверяется с ним раз в 8 часов в фоне и при каждом
 * подключении в режиме DNS. Зашитый в сборку [SEED] — только первый запуск и
 * запасной вариант, пока лента недоступна.
 *
 * **IP в ленте нет, и это намеренно.** Он и так публичен — делегирование зоны
 * требует записи A у NS-сервера, её отдаёт любой резолвер. А раз так, переезд
 * на новый IP — это одна запись A у `tns.nova-app.eu`, и приложению о нём знать
 * незачем: несущие резолверы идут к серверу по NS-записи зоны, а прямую несущую
 * (запросы прямо на сервер, на обычной сети быстрее любого резолвера) Nova
 * узнаёт сама, разрешая [Server.nsHost] при подключении ([directCarriers]).
 * Лента нужна только на редкий случай: сменить ключ или сам домен.
 *
 * ## Ключ «nova1:…»
 *
 * Префикс включает диалект вывода ключа в нашем `libstormdns.so`
 * (`tools/stormdns/nova.patch`): сервер понимает только его, а сторонние клиенты
 * StormDNS/MasterDNS с тем же ключом молча не получают ответа. Секретом от того,
 * кто читает исходники, это не является — см. `kb/dns-tunnel-server.md`.
 *
 * Хранится файлом, а не в prefs: пишет процесс экрана или WorkManager, читает
 * `:vpn`, а prefs кэшируются попроцессно (I2).
 */
object DnsServerFeed {

    data class Server(
        val domain: String,
        /** Имя авторитативного сервера зоны; его A-запись и есть адрес машины. */
        val nsHost: String,
        val method: Int,
        val key: String,
        val record: String,
        /**
         * Порты прямой несущей, по порядку.
         *
         * Не только 53. Операторы заворачивают UDP/53 к публичным резолверам на
         * свои (МегаФон, 2026-09-29), а где заворот шире — он накроет и прямой
         * путь к нам. Порт мимо 53 проходит там, где 53 перехвачен: на том же
         * МегаФоне `5353` работал, а `443` и `8053` оператор не пропустил вовсе.
         * Список в ленте, потому что какой порт жив — свойство сети, а не сборки.
         */
        val ports: List<Int> = DEFAULT_PORTS,
    )

    /** Порты прямой несущей по умолчанию: 53 быстрее, 5353 — обход заворота. */
    val DEFAULT_PORTS: List<Int> = listOf(53, 5353)

    /** Сервер на момент сборки. Совпадает с лентой, пока адрес не менялся. */
    val SEED = Server(
        domain = "t.nova-app.eu",
        nsHost = "tns.nova-app.eu",
        method = 5,
        key = "nova1:9e9ad8a52bf1ec284c1b2aa5994a9ab2",
        record = "TXT",
    )

    private val FEED_URLS = listOf(
        "https://raw.githubusercontent.com/confeden/nova_updates/main/nova_android/dns_tunnel.json",
        "https://confeden.github.io/nova_updates/nova_android/dns_tunnel.json",
    )

    private const val FILE_NAME = "dns_server_feed.json"
    private const val WORK_NAME = "nova-dns-server-feed"

    /** Лента моложе этого — ходить за ней незачем. */
    private const val FRESH_WINDOW_MS = 8L * 60L * 60L * 1000L

    /** После неудачи не долбим недоступный хост каждым подключением. */
    private const val RETRY_AFTER_FAILURE_MS = 15L * 60L * 1000L

    /** Потолок ленты: она весит сотни байт, 64 КБ — запас с избытком. */
    private const val FEED_LIMIT_CHARS = 64 * 1024

    private const val CONNECT_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 3000
    private const val TOTAL_BUDGET_MS = 8000L
    private const val RESOLVE_BUDGET_MS = 3000L

    private val DOMAIN_RE = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")
    private val RECORDS = setOf("TXT", "NS", "CNAME", "SRV", "ROTATE")

    private val refreshInFlight = AtomicBoolean(false)

    /**
     * Первый пригодный сервер из файла ленты, либо null.
     *
     * Формат: `{"version":1,"servers":[{"domain","ns","method","key","record","ports"}]}`.
     * Чистая функция — лента внешние данные, и мусор отбраковывается до записи.
     */
    fun parse(body: String): Server? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val servers = root.optJSONArray("servers") ?: return null
        for (i in 0 until servers.length()) {
            val o = servers.optJSONObject(i) ?: continue
            val domain = o.optString("domain").trim().lowercase()
            val ns = o.optString("ns").trim().lowercase()
            val method = o.optInt("method", -1)
            val key = o.optString("key").trim()
            val record = o.optString("record").trim().uppercase().ifEmpty { "TXT" }
            if (domain.length > 100 || !DOMAIN_RE.matches(domain)) continue
            if (ns.length > 100 || !DOMAIN_RE.matches(ns)) continue
            if (!StormDnsConfig.supportsMethod(method)) continue
            if (key.isEmpty() || key.length > 128 || key.any { it.code < 0x21 || it.code == 0x7F }) continue
            if (record !in RECORDS) continue
            val ports = o.optJSONArray("ports")?.let { array ->
                (0 until array.length())
                    .map { array.optInt(it, 0) }
                    .filter { it in 1..65535 }
                    .distinct()
                    .take(4)
            }?.takeIf { it.isNotEmpty() } ?: DEFAULT_PORTS
            return Server(domain, ns, method, key, record, ports)
        }
        return null
    }

    /** Сервер, которым подключаться: последний из ленты, иначе [SEED]. */
    fun current(context: Context?): Server = readState(context)?.server ?: SEED

    /** Профиль «Профиль Nova» для этого сервера. */
    fun builtInProfile(server: Server): DnsProfile = DnsProfile(
        id = DnsProfileList.BUILT_IN_PROFILE_ID,
        name = "Профиль Nova",
        engine = DnsProfileImport.Engine.COTTEN,
        domain = server.domain,
        key = server.key,
        encryptionMethod = server.method,
        recordType = server.record,
        builtIn = true,
        configured = true,
    )

    /**
     * Прямая несущая для встроенного профиля: адреса [Server.nsHost] с `:53`.
     *
     * Разрешается системным резолвером в момент подключения — процесс `:vpn`
     * исключён из туннеля, так что спрашивает он сеть, а не себя. Бюджет
     * [RESOLVE_BUDGET_MS]: `InetAddress` своего тайм-аута не имеет, а подключение
     * ждать не должно. Не разрешилось — пусто, и туннель идёт через резолверы
     * (под белым списком прямая несущая всё равно отсеялась бы перебором MTU).
     */
    fun directCarriers(context: Context?, logger: (String) -> Unit): List<String> {
        val server = current(context)
        val host = server.nsHost
        val ports = server.ports.ifEmpty { DEFAULT_PORTS }
        val result = java.util.concurrent.atomic.AtomicReference<List<String>>(emptyList())
        val worker = thread(start = true, isDaemon = true, name = "nova-dns-ns-resolve") {
            val addrs = runCatching { java.net.InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
            result.set(
                addrs.filterIsInstance<java.net.Inet4Address>()
                    .mapNotNull { it.hostAddress }
                    .distinct()
                    .flatMap { addr -> ports.map { port -> "$addr:$port" } }
            )
        }
        worker.join(RESOLVE_BUDGET_MS)
        val carriers = result.get()
        logger(
            if (carriers.isEmpty()) {
                "DNS: адрес $host не разрешился за $RESOLVE_BUDGET_MS мс — идём только через резолверы."
            } else {
                "DNS: прямая несущая ${carriers.joinToString(",")} (из $host)."
            }
        )
        return carriers
    }

    /** Периодическая сверка раз в 8 часов (плюс случайные минуты, как у обновлений). */
    fun syncSchedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<DnsServerFeedWorker>(8, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(Random.nextLong(5L, 60L), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /** Сверка в отдельном потоке, если лента устарела. Ничего не ждёт. */
    fun refreshInBackground(context: Context, logger: (String) -> Unit) {
        val appContext = context.applicationContext
        if (!shouldRefresh(appContext)) return
        if (!refreshInFlight.compareAndSet(false, true)) return
        thread(start = true, isDaemon = true, name = "nova-dns-feed") {
            try {
                refresh(appContext, logger)
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    /**
     * @return true, если лента ответила пригодным сервером.
     *
     * Смена адреса пишется в журнал отдельной строкой: «сервер переехал» должно
     * быть видно, а не угадываться по тому, что подключение вдруг заработало.
     */
    fun refresh(context: Context, logger: (String) -> Unit): Boolean {
        val startedAt = System.currentTimeMillis()
        val previous = readState(context)
        writeState(context, previous?.copy(attemptAt = startedAt) ?: State(null, 0L, startedAt))
        var lastError = "нет ответа"
        for (url in FEED_URLS) {
            if (System.currentTimeMillis() - startedAt > TOTAL_BUDGET_MS) break
            val body = try {
                fetch(url)
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            val server = parse(body)
            if (server == null) {
                lastError = "файл получен, пригодного сервера в нём нет"
                continue
            }
            val now = System.currentTimeMillis()
            writeState(context, State(server, now, startedAt))
            val before = previous?.server ?: SEED
            if (before != server) {
                logger("Сервер DNS-туннеля Nova сменился: ${before.domain} → ${server.domain} (ключ или NS обновлены).")
            } else {
                logger("Сервер DNS-туннеля Nova сверен с лентой: ${server.domain}, без изменений.")
            }
            return true
        }
        logger("Лента сервера DNS-туннеля недоступна ($lastError). Остаётся ${current(context).domain}.")
        return false
    }

    private fun shouldRefresh(context: Context): Boolean {
        val state = readState(context) ?: return true
        val now = System.currentTimeMillis()
        // Отметка из будущего (часы уехали назад) — «неизвестно когда», не «только что».
        if (state.attemptAt in (now - RETRY_AFTER_FAILURE_MS)..now) return false
        return state.syncedAt !in (now - FRESH_WINDOW_MS)..now
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "NovaAndroid/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP $code")
            // Читается ровно потолок, а не «всё, потом обрежем»: `readText()`
            // сначала собрал бы в память столько, сколько отдаст дальний конец,
            // и только потом узнал бы, что нам нужны первые 64 КБ.
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(FEED_LIMIT_CHARS)
                var read = 0
                while (read < buffer.size) {
                    val n = reader.read(buffer, read, buffer.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buffer, 0, read)
            }
        } finally {
            connection.disconnect()
        }
    }

    private data class State(val server: Server?, val syncedAt: Long, val attemptAt: Long)

    private fun readState(context: Context?): State? {
        val file = fileFor(context) ?: return null
        val raw = runCatching { file.baseFile.takeIf { it.exists() }?.readText(Charsets.UTF_8) }
            .getOrNull().orEmpty()
        if (raw.isBlank()) return null
        return runCatching {
            val o = JSONObject(raw)
            val server = o.optJSONObject("server")?.let { s ->
                parse(JSONObject().put("servers", org.json.JSONArray().put(s)).toString())
            }
            State(server, o.optLong("syncedAt", 0L), o.optLong("attemptAt", 0L))
        }.getOrNull()
    }

    private fun writeState(context: Context?, state: State) {
        val file = fileFor(context) ?: return
        val json = JSONObject()
            .put("syncedAt", state.syncedAt)
            .put("attemptAt", state.attemptAt)
        state.server?.let { s ->
            json.put(
                "server",
                JSONObject().put("domain", s.domain).put("ns", s.nsHost).put("method", s.method)
                    .put("key", s.key).put("record", s.record)
                    .put("ports", org.json.JSONArray(s.ports)),
            )
        }
        runCatching {
            val stream = file.startWrite()
            try {
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }
    }

    private fun fileFor(context: Context?): AtomicFile? {
        val dir = context?.applicationContext?.filesDir ?: return null
        return AtomicFile(File(dir, FILE_NAME))
    }
}

/** Фоновая сверка адреса сервера DNS-туннеля, раз в 8 часов. */
class DnsServerFeedWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        LogManager.setAppContext(applicationContext)
        // Неудача — не retry: следующая попытка и так через 8 часов, а при
        // подключении в режиме DNS лента сверяется ещё раз.
        DnsServerFeed.refresh(applicationContext, LogManager::log)
        return Result.success()
    }
}

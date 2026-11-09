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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * Встроенная подписка «Nova» — тот же список, что у Nova PC (`nova-list.json` в
 * `confeden/nova_updates`, раздел «Github list»).
 *
 * Список собирает сервер дважды в сутки и кладёт туда только узлы, прошедшие
 * проверку настоящим HTTPS 204 через ядро Mihomo. Поэтому и здесь каждый узел
 * поднимает тот же помощник `nova-proxy` на том же Mihomo ([NovaProxyProcess]):
 * узел проверен ровно этим ядром, и любое другое отвечало бы уже за себя.
 *
 * ## Формат
 * `{"nova_list":{"version":1,"complete":true,"count":N},"proxies":[…]}` — карты
 * прокси в родном виде Mihomo. Снимок принимается только целиком: версия 1,
 * `complete`, и `count` равен числу записей. Иначе остаётся прежний список (как у
 * ПК: обрывок файла не должен стирать рабочие узлы).
 *
 * ## Хранение
 * Файлы, а не prefs: пишет экран или WorkManager, читает `:vpn` (I2).
 * `nova_list.json` — снимок и метки сверки; `nova_list_ping.json` — замеры;
 * `nova_list_choice.json` — выбор человека («» = авто, лучший по замеру).
 */
object NovaList {

    /** Зеркала в порядке ПК: githack, raw, jsDelivr. Пробел в пути закодирован один раз. */
    val FEED_URLS: List<String> = listOf(
        "https://raw.githack.com/confeden/nova_updates/main/Github%20list/nova-list.json",
        "https://raw.githubusercontent.com/confeden/nova_updates/main/Github%20list/nova-list.json",
        "https://cdn.jsdelivr.net/gh/confeden/nova_updates@main/Github%20list/nova-list.json",
    )

    const val REFRESH_HOURS = 12L
    private const val FRESH_WINDOW_MS = REFRESH_HOURS * 60 * 60 * 1000
    private const val RETRY_AFTER_FAILURE_MS = 15 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val TOTAL_BUDGET_MS = 120_000L
    private const val BODY_LIMIT_BYTES = 32 * 1024 * 1024
    private const val MAX_NODES = 20_000
    private const val WORK_NAME = "nova-list-refresh"

    private const val FILE_LIST = "nova_list.json"
    private const val FILE_PING = "nova_list_ping.json"
    private const val FILE_CHOICE = "nova_list_choice.json"
    private const val FILE_ACTIVE = "nova_list_active.json"

    /** Замер старше этого считается устаревшим, и экран меряет заново. */
    const val PING_STALE_MS = 2 * 60 * 60 * 1000L

    private val refreshInFlight = AtomicBoolean(false)
    private val fileLock = Any()

    data class Node(
        /** SHA-256 карты без имени — как у ПК; переживает перенумерацию списка. */
        val id: String,
        /** Номер из имени (`VLESS_#12_…` → 12), иначе место в списке. */
        val number: Int,
        val name: String,
        val type: String,
        /** Короткая метка протокола для строки: VLESS·R, VMESS, SS, WG … */
        val protocol: String,
        val server: String,
        val port: Int,
        /** Карта Mihomo как есть — её и получает движок. */
        val json: JSONObject,
        /**
         * Время HTTPS 204 через узел, замеренное сборщиком списка (Debian, домашняя линия
         * в РФ); 0 — неизвестно. Им упорядочен свежий список, пока своих замеров нет:
         * мерить тысячи узлов с телефона — лишний трафик и минуты работы.
         */
        val serverMs: Int = 0,
    ) {
        /** UDP-протоколы: TCP-преполёт к ним бессмыслен, и движок его пропускает. */
        val isUdp: Boolean get() = type in UDP_TYPES
    }

    data class Ping(val ms: Int, val ok: Boolean, val at: Long)

    private val UDP_TYPES = setOf("hysteria", "hysteria2", "tuic", "wireguard")

    // ---- разбор ------------------------------------------------------------------

    /** Узлы снимка или null, если снимок неполный или не той версии. */
    fun parse(body: String): List<Node>? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val meta = root.optJSONObject("nova_list") ?: return null
        if (meta.optInt("version", 0) != 1) return null
        if (!meta.optBoolean("complete", false)) return null
        val proxies = root.optJSONArray("proxies") ?: return null
        if (meta.optInt("count", -1) != proxies.length()) return null
        if (proxies.length() > MAX_NODES) return null
        val out = ArrayList<Node>(proxies.length())
        val seen = HashSet<String>()
        for (i in 0 until proxies.length()) {
            val map = proxies.optJSONObject(i) ?: continue
            val node = nodeOf(map, i, root.optJSONArray("ms")) ?: continue
            if (seen.add(node.id)) out += node
        }
        return out
    }

    private fun nodeOf(map: JSONObject, index: Int, ms: JSONArray? = null): Node? {
        val type = map.optString("type").trim().lowercase(Locale.ROOT)
        val server = map.optString("server").trim()
        val port = map.opt("port")?.toString()?.trim()?.toIntOrNull() ?: return null
        if (type.isEmpty() || server.isEmpty() || port !in 1..65535) return null
        when (type) {
            "direct", "reject", "dns", "block", "compatible",
            "tailscale", "zerotier", "easytier", "openvpn" -> return null
        }
        // Цепочки через другой узел списка здесь не поднимаются: движок получает
        // ровно один узел, и зависимость осталась бы без родителя.
        val parent = map.optString("dialer-proxy").trim()
        if (parent.isNotEmpty() && !parent.equals("DIRECT", ignoreCase = true)) return null
        val name = map.optString("name").trim().ifEmpty { "${type.uppercase(Locale.ROOT)}_#${index + 1}" }
        val number = Regex("#(\\d+)").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
        val identity = JSONObject(map.toString()).apply { remove("name") }
        return Node(
            id = sha256(canonical(identity)).take(24),
            number = number,
            name = name,
            type = type,
            protocol = protocolLabel(type, map),
            server = server,
            port = port,
            json = map,
            serverMs = ms?.optInt(index, 0)?.coerceAtLeast(0) ?: 0,
        )
    }

    private fun protocolLabel(type: String, map: JSONObject): String = when (type) {
        "vless" -> {
            val reality = map.optJSONObject("reality-opts") != null
            val network = map.optString("network").trim().lowercase(Locale.ROOT)
            val transport = when (network) {
                "", "tcp", "raw" -> ""
                "xhttp" -> "XH"
                "grpc" -> "GRPC"
                "httpupgrade" -> "HU"
                else -> network.uppercase(Locale.ROOT)
            }
            buildString {
                append("VLESS")
                if (reality) append("·R")
                if (transport.isNotEmpty()) append("·").append(transport)
            }
        }
        "vmess" -> "VMESS"
        "trojan" -> "TROJAN"
        "ss" -> "SS"
        "ssr" -> "SSR"
        "http" -> "HTTP"
        "socks5" -> "SOCKS"
        "wireguard" -> "WG"
        "hysteria" -> "HY"
        "hysteria2" -> "HY2"
        "tuic" -> "TUIC"
        "anytls" -> "ANYTLS"
        else -> type.uppercase(Locale.ROOT).take(8)
    }

    /** JSON с ключами по алфавиту на каждом уровне: одинаковая карта — одинаковый id. */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // ---- чтение для экрана и службы -------------------------------------------

    fun nodes(context: Context?): List<Node> {
        val o = readJson(context, FILE_LIST) ?: return emptyList()
        val proxies = o.optJSONArray("proxies") ?: return emptyList()
        val out = ArrayList<Node>(proxies.length())
        for (i in 0 until proxies.length()) {
            proxies.optJSONObject(i)?.let { nodeOf(it, i, o.optJSONArray("ms")) }?.let(out::add)
        }
        return out
    }

    /** Когда снимок последний раз сверен с лентой (0 — никогда). */
    fun syncedAt(context: Context?): Long = readJson(context, FILE_LIST)?.optLong("syncedAt", 0L) ?: 0L

    fun pings(context: Context?): Map<String, Ping> {
        val o = readJson(context, FILE_PING) ?: return emptyMap()
        val out = HashMap<String, Ping>()
        for (key in o.keys()) {
            val p = o.optJSONObject(key) ?: continue
            out[key] = Ping(p.optInt("ms", -1), p.optBoolean("ok", false), p.optLong("at", 0L))
        }
        return out
    }

    /** Пишет пачку замеров поверх прежних; узлы, ушедшие из списка, вычищаются. */
    fun recordPings(context: Context?, results: Map<String, Ping>) {
        if (results.isEmpty()) return
        synchronized(fileLock) {
            val live = nodes(context).mapTo(HashSet()) { it.id }
            val merged = HashMap(pings(context))
            merged.putAll(results)
            val o = JSONObject()
            for ((id, p) in merged) {
                if (live.isNotEmpty() && id !in live) continue
                o.put(id, JSONObject().put("ms", p.ms).put("ok", p.ok).put("at", p.at))
            }
            writeJson(context, FILE_PING, o)
        }
    }

    /**
     * Узлы по замеру: живые по возрастанию задержки, затем не мерянные (в порядке
     * списка), затем не ответившие. Это и порядок экрана, и порядок «Авто».
     */
    fun ordered(context: Context?): List<Node> = order(nodes(context), pings(context))

    fun order(nodes: List<Node>, pings: Map<String, Ping>): List<Node> =
        nodes.withIndex().sortedWith(
            compareBy<IndexedValue<Node>>(
                { v -> pings[v.value.id].let { p -> if (p == null) 1 else if (p.ok) 0 else 2 } },
                { v -> pings[v.value.id]?.takeIf { it.ok }?.ms ?: Int.MAX_VALUE },
                // Не мерянные своим телефоном — по замеру сборщика (0 = неизвестно, в конец).
                { v -> v.value.serverMs.takeIf { it > 0 } ?: Int.MAX_VALUE },
                { v -> v.index },
            )
        ).map { it.value }

    /** Выбранный узел: id или «» — авто. */
    fun choice(context: Context?): String = readJson(context, FILE_CHOICE)?.optString("id").orEmpty()

    fun setChoice(context: Context?, id: String) {
        synchronized(fileLock) { writeJson(context, FILE_CHOICE, JSONObject().put("id", id.trim())) }
    }

    /** Узел, на котором сейчас стоит туннель (пишет `:vpn`, читает экран); «» — нет. */
    fun active(context: Context?): String = readJson(context, FILE_ACTIVE)?.optString("id").orEmpty()

    fun setActive(context: Context?, id: String) {
        synchronized(fileLock) { writeJson(context, FILE_ACTIVE, JSONObject().put("id", id.trim())) }
    }

    // ---- сверка с лентой ---------------------------------------------------------

    fun syncSchedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<NovaListWorker>(REFRESH_HOURS, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(Random.nextLong(10L, 60L), TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun shouldRefresh(context: Context?): Boolean {
        val o = readJson(context, FILE_LIST) ?: return true
        val now = System.currentTimeMillis()
        val attemptAt = o.optLong("attemptAt", 0L)
        val syncedAt = o.optLong("syncedAt", 0L)
        if (attemptAt in (now - RETRY_AFTER_FAILURE_MS)..now && syncedAt != attemptAt) return false
        return syncedAt !in (now - FRESH_WINDOW_MS)..now
    }

    /** Сверка в отдельном потоке, если снимок устарел (или [force]). Ничего не ждёт. */
    fun refreshInBackground(
        context: Context,
        logger: (String) -> Unit,
        force: Boolean = false,
        onDone: ((Boolean) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        if (!force && !shouldRefresh(appContext)) return
        if (!refreshInFlight.compareAndSet(false, true)) return
        thread(start = true, isDaemon = true, name = "nova-list-feed") {
            var ok = false
            try {
                ok = refresh(appContext, logger)
            } finally {
                refreshInFlight.set(false)
                onDone?.invoke(ok)
            }
        }
    }

    fun isRefreshing(): Boolean = refreshInFlight.get()

    /**
     * Скачивает снимок по лестнице зеркал.
     *
     * Условный GET (ETag / Last-Modified): ответ 304 значит «не изменился», и это
     * такая же удачная сверка. HEAD не используется — githack отвечает на него 403.
     */
    fun refresh(context: Context, logger: (String) -> Unit): Boolean {
        val startedAt = System.currentTimeMillis()
        val previous = readJson(context, FILE_LIST)
        synchronized(fileLock) {
            writeJson(context, FILE_LIST, (previous ?: JSONObject()).put("attemptAt", startedAt))
        }
        val etag = previous?.optString("etag").orEmpty()
        val lastModified = previous?.optString("lastModified").orEmpty()
        val havePrevious = previous?.optJSONArray("proxies") != null
        var lastError = "нет ответа"
        for (url in FEED_URLS) {
            if (System.currentTimeMillis() - startedAt > TOTAL_BUDGET_MS) break
            val answer = try {
                fetch(url, if (havePrevious) etag else "", if (havePrevious) lastModified else "")
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            val now = System.currentTimeMillis()
            if (answer.notModified) {
                synchronized(fileLock) {
                    val cur = readJson(context, FILE_LIST) ?: JSONObject()
                    writeJson(context, FILE_LIST, cur.put("syncedAt", now).put("attemptAt", now))
                }
                LogManager.d("Список Nova сверен: не изменился (304).")
                return true
            }
            val nodes = parse(answer.body)
            if (nodes == null) {
                // Битый или неполный снимок не стирает рабочий список — и его ETag не
                // кешируется, иначе следующая сверка получила бы 304 на мусор.
                lastError = "снимок неполный или не той версии"
                continue
            }
            val root = JSONObject(answer.body)
            val state = JSONObject()
                .put("proxies", root.getJSONArray("proxies"))
                .put("ms", root.optJSONArray("ms") ?: JSONArray())
                .put("checkedAt", root.optJSONObject("nova_list")?.optString("checked_at").orEmpty())
                .put("syncedAt", now)
                .put("attemptAt", now)
                .put("etag", answer.etag)
                .put("lastModified", answer.lastModified)
                .put("source", url)
            synchronized(fileLock) { writeJson(context, FILE_LIST, state) }
            val before = previous?.optJSONArray("proxies")?.length() ?: 0
            logger("Список Nova обновлён: ${nodes.size} профилей (было $before).")
            return true
        }
        logger("Список Nova не обновился ($lastError). Остаются прежние профили.")
        return false
    }

    private class Answer(val body: String, val notModified: Boolean, val etag: String, val lastModified: String)

    private fun fetch(url: String, etag: String, lastModified: String): Answer {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "NovaAndroid/${BuildConfig.VERSION_NAME}")
            if (etag.isNotEmpty()) connection.setRequestProperty("If-None-Match", etag)
            if (lastModified.isNotEmpty()) connection.setRequestProperty("If-Modified-Since", lastModified)
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return Answer("", true, etag, lastModified)
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP $code")
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > BODY_LIMIT_BYTES) throw IllegalStateException("файл больше 32 МБ")
                }
                out.toByteArray()
            }
            Answer(
                String(bytes, Charsets.UTF_8),
                false,
                connection.getHeaderField("ETag").orEmpty(),
                connection.getHeaderField("Last-Modified").orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

    // ---- файлы ----------------------------------------------------------------------

    private fun readJson(context: Context?, name: String): JSONObject? {
        val file = fileFor(context, name) ?: return null
        val raw = runCatching { file.readFully().toString(Charsets.UTF_8) }.getOrNull().orEmpty()
        if (raw.isBlank()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun writeJson(context: Context?, name: String, json: JSONObject) {
        val file = fileFor(context, name) ?: return
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

    private fun fileFor(context: Context?, name: String): AtomicFile? {
        val dir = context?.applicationContext?.filesDir ?: return null
        return AtomicFile(File(dir, name))
    }
}

/** Фоновая сверка встроенного списка Nova, раз в 12 часов. */
class NovaListWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {

    override fun doWork(): Result {
        LogManager.setAppContext(applicationContext)
        // В режиме DNS канал не тратится на себя (D35): 100 КБ списка подождут.
        val chip = ClientData(applicationContext).getExitRegionPreference().trim().lowercase(Locale.ROOT)
        if (chip == ConnectionSelectorPolicy.CHIP_DNS) return Result.success()
        NovaList.refresh(applicationContext, LogManager::log)
        return Result.success()
    }
}

package com.example.nova

import android.content.Context
import android.util.AtomicFile
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Один мост Tor.
 *
 * @param transport `obfs4`, `webtunnel`, `snowflake` или `vanilla`.
 * @param line строка в том виде, в каком её понимает сам tor. Она и есть
 *        единица обмена: разбирать её на поля целиком незачем, а собирать
 *        обратно — верный способ потерять параметр, о котором мы не знали.
 * @param endpoint `адрес:порт` из начала строки. Для `webtunnel` и `snowflake`
 *        это **заглушка** (`2001:db8::/32` и `192.0.2.0/24` соответственно), и
 *        соединяться по ней нельзя — см. [dialTarget].
 * @param fingerprint отпечаток моста; используется как ключ уникальности.
 * @param url `url=` из строки: для `webtunnel` именно он и есть настоящий адрес.
 */
data class TorBridge(
    val transport: String,
    val line: String,
    val endpoint: String,
    val fingerprint: String,
    val url: String,
) {
    /** Уникальный ключ. Отпечаток надёжнее адреса: один хост держит несколько мостов. */
    val id: String get() = if (fingerprint.isNotEmpty()) "$transport|$fingerprint" else "$transport|$endpoint"

    /**
     * Куда на самом деле звонить, чтобы проверить мост.
     *
     * `webtunnel` всегда объявляет адрес из документационного префикса RFC 3849
     * `2001:db8::/32` — измерено на всех 332 строках обоих публичных списков и на
     * выдаче Moat. Это идентификатор для tor, а не адрес: соединение идёт по
     * `url=`. Попытка достучаться до `2001:db8:…` не провалится с ошибкой — она
     * молча провисит весь таймаут, и мост будет объявлен мёртвым.
     */
    fun dialTarget(): Pair<String, Int>? {
        if (transport == "webtunnel") return null
        val host = endpoint.substringBeforeLast(':').trim('[', ']')
        val port = endpoint.substringAfterLast(':').toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null
        if (isPlaceholderHost(host)) return null
        return host to port
    }

    fun toJson(): JSONObject = JSONObject()
        .put("transport", transport)
        .put("line", line)
        .put("endpoint", endpoint)
        .put("fingerprint", fingerprint)
        .put("url", url)

    companion object {
        /**
         * Адреса-заглушки, по которым соединения не бывает.
         *
         * Фронтовые транспорты пишут в строку документационный адрес: RFC 5737
         * для IPv4 (`192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`) и
         * RFC 3849 для IPv6 (`2001:db8::/32`). Настоящая точка входа у них —
         * `url=` или брокер. Попытка достучаться до такого адреса не падает с
         * ошибкой: она молча висит весь таймаут, и живой мост объявляется
         * мёртвым. Раньше здесь стоял один префикс `192.0.2.` — остальные
         * четыре набора проходили насквозь.
         */
        private val PLACEHOLDER_PREFIXES = listOf(
            "192.0.2.",
            "198.51.100.",
            "203.0.113.",
            "0.0.0.0",
            "2001:db8:",
            "2001:0db8:",
        )

        fun isPlaceholderHost(host: String): Boolean {
            val clean = host.trim()
            if (clean.isEmpty()) return false
            if (clean == "::" || clean == "::1") return true
            return PLACEHOLDER_PREFIXES.any { clean.startsWith(it, ignoreCase = true) }
        }

        /**
         * Известные имена транспортов.
         *
         * Признак «первое слово — это транспорт» обязан быть перечислением, а не
         * догадкой по виду строки: «ванильный» мост начинается прямо с
         * `[2001:...]:443`, и любая эвристика по скобке или двоеточию рано или
         * поздно съедает адрес вместо имени.
         */
        private val KNOWN_TRANSPORTS = setOf(
            "obfs4", "obfs3", "obfs2", "webtunnel", "snowflake",
            "meek", "meek_lite", "meek-azure", "conjure", "scramblesuit",
            "dnstt", "vanilla",
        )

        /**
         * Транспорты, которыми приложение действительно умеет подключаться.
         *
         * Ровно те три, что отдаёт `ConnectionSelectorPolicy.torBridgeTransportFor`.
         * Всё остальное — мост, который нельзя выбрать: `snowflake` в
         * `libgojni.so` не собран (pion/webrtc и `anet` ломают компоновку, G176),
         * у `meek_lite` и `conjure` нет способа входа, `obfs3`/`obfs2` lyrebird не
         * даёт вовсе. Сборщик их исправно приносил, хранил и считал: на экране
         * стояло «живых мостов 37 (obfs4 15, webtunnel 5, snowflake 2, vanilla 15)»
         * — тридцать семь, из которых подключиться могли тридцать пять. Счёт,
         * которым нельзя воспользоваться, — неверный счёт, поэтому лишнее
         * отбрасывается в разборе, а не прячется в отрисовке.
         */
        private val SUPPORTED_TRANSPORTS = setOf("obfs4", "webtunnel", "vanilla")

        /**
         * Разбирает строку моста.
         *
         * Форматы: `<transport> <addr:port> <FPR> <key=value>...` и, для
         * «ванильного» моста, `<addr:port> <FPR>` без имени транспорта.
         */
        fun parse(raw: String?): TorBridge? {
            val line = raw?.trim().orEmpty()
            if (line.isEmpty() || line.startsWith("#")) return null
            // Управляющий символ внутри строки моста — это дописанная директива
            // torrc, а не мост.
            //
            // Строки приходят с чужих сборщиков по сети, а `torrc` разбирается
            // построчно: перевод строки в середине превратил бы одну запись в
            // «мост плюс что угодно ещё» — от `SocksPort 0.0.0.0:9050`, то есть
            // открытого наружу прокси, до подмены `ClientTransportPlugin`.
            // Отсекаем в самом разборе, чтобы такая запись не доехала ни до
            // файла мостов, ни до torrc.
            if (line.any { it.isISOControl() }) return null
            // Приставку `Bridge ` несут строки, скопированные прямо из torrc.
            val body = if (line.length > 7 && line.regionMatches(0, "Bridge ", 0, 7, ignoreCase = true)) {
                line.substring(7).trim()
            } else {
                line
            }
            if (body.isEmpty()) return null
            val parts = body.split(Regex("\\s+"))
            if (parts.isEmpty()) return null
            val hasTransport = parts[0].lowercase() in KNOWN_TRANSPORTS
            val transport = if (hasTransport) parts[0].lowercase() else "vanilla"
            // Подключиться этим мостом нельзя — значит и моста нет. Отказ здесь,
            // в разборе: иначе строка доедет и до файла, и до счётчика, и до
            // сводки на экране, и везде будет считаться годной.
            if (transport !in SUPPORTED_TRANSPORTS) return null
            val rest = if (hasTransport) parts.drop(1) else parts
            val endpoint = rest.getOrNull(0)?.takeIf { it.contains(':') } ?: return null
            val fingerprint = rest.getOrNull(1)
                ?.takeIf { it.length == 40 && it.all { c -> c.isDigit() || c in 'A'..'F' || c in 'a'..'f' } }
                ?.uppercase()
                .orEmpty()
            val url = rest.firstOrNull { it.startsWith("url=") }?.removePrefix("url=").orEmpty()
            // Хранится строка без приставки: torrc собирается как `Bridge <line>`,
            // и «Bridge Bridge obfs4 …» tor не разберёт.
            return TorBridge(transport, body, endpoint, fingerprint, url)
        }

        fun fromJson(json: JSONObject): TorBridge? {
            val line = json.optString("line").takeIf { it.isNotBlank() } ?: return null
            val transport = json.optString("transport").ifBlank { "vanilla" }
            // Тот же отбор, что и в [parse], и по той же причине.
            //
            // Чтение из файла разбор не повторяет, поэтому без этой строки мосты,
            // записанные прошлой версией, переживали бы правило: на экране стояло
            // «живых мостов 37 (obfs4 15, webtunnel 5, vanilla 15)» — тридцать семь
            // в сумме и тридцать пять в перечислении, то есть два невидимых
            // подключиться не могли, а в счёт входили.
            if (transport !in SUPPORTED_TRANSPORTS) return null
            return TorBridge(
                transport = transport,
                line = line,
                endpoint = json.optString("endpoint"),
                fingerprint = json.optString("fingerprint"),
                url = json.optString("url"),
            )
        }
    }
}

/**
 * Мосты Tor на диске.
 *
 * Файл, а не `SharedPreferences`: список собирает интерфейс, а пользоваться им
 * будет процесс `:vpn`, и кэш настроек на процесс развёл бы их по разным
 * значениям (I2, G2/G16/G70).
 */
object TorBridgeStore {

    private const val FILE_NAME = "tor_bridges.json"
    private val writeLock = Any()

    data class Snapshot(
        val bridges: List<TorBridge>,
        val updatedAtMs: Long,
        val source: String,
        val lastError: String,
    ) {
        fun countOf(transport: String): Int = bridges.count { it.transport == transport }
        val isEmpty: Boolean get() = bridges.isEmpty()
    }

    fun read(context: Context?): Snapshot {
        val file = fileFor(context) ?: return Snapshot(emptyList(), 0L, "", "")
        val raw = readRaw(file) ?: return Snapshot(emptyList(), 0L, "", "")
        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: return Snapshot(emptyList(), 0L, "", "")
        val array = json.optJSONArray("bridges")
        val bridges = buildList {
            for (i in 0 until (array?.length() ?: 0)) {
                array!!.optJSONObject(i)?.let { TorBridge.fromJson(it) }?.let(::add)
            }
        }
        return Snapshot(
            bridges = bridges,
            updatedAtMs = json.optLong("updated_at", 0L),
            source = json.optString("source"),
            lastError = json.optString("last_error"),
        )
    }

    fun write(context: Context?, snapshot: Snapshot): Boolean {
        val file = fileFor(context) ?: return false
        val payload = JSONObject()
            .put("version", 1)
            .put("updated_at", snapshot.updatedAtMs)
            .put("source", snapshot.source)
            .put("last_error", snapshot.lastError)
            .put("bridges", JSONArray().also { array -> snapshot.bridges.forEach { array.put(it.toJson()) } })
        synchronized(writeLock) {
            var stream: java.io.FileOutputStream? = null
            return try {
                stream = file.startWrite()
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
                true
            } catch (e: Exception) {
                if (stream != null) runCatching { file.failWrite(stream) }
                LogManager.log("Tor: список мостов не записался — ${e.message}")
                false
            }
        }
    }

    private fun fileFor(context: Context?): AtomicFile? {
        val dir = context?.applicationContext?.filesDir ?: return null
        return AtomicFile(File(dir, FILE_NAME))
    }

    /** Чтение мимо `readFully()` — до Android 11 `openRead()` рушит чужую запись (G66). */
    private fun readRaw(file: AtomicFile): String? {
        val base = runCatching { file.baseFile.readText(Charsets.UTF_8) }.getOrNull()
        if (!base.isNullOrBlank()) return base
        val backup = File(file.baseFile.path + ".bak")
        return runCatching { backup.takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull()
    }
}

/**
 * Сбор рабочих мостов Tor.
 *
 * ## Почему источники именно эти и именно в этом порядке
 *
 * Замер с российской сети (Ростелеком, AS12389, 2026-09-05):
 *
 * * `bridges.torproject.org` заблокирован **по SNI и только по IPv4**: к тому же
 *   адресу с чужим SNI ответ приходит. По IPv6 Moat отвечает без помех.
 * * Список `OnionHop` (`obfs4_tested.txt`) дал **100 % живых** из 250 проверенных
 *   адресов, обновляется раз в час.
 * * Список `Delta-Kronecker`, у которого те же файлы и та же лицензия, дал 38-44 %:
 *   его признак «протестировано» протух. Он здесь только запасным.
 * * Сам Moat отдаёт по два моста на IP-адрес и не меняет выдачу при повторных
 *   запросах — это «первый контакт», а не источник массы.
 *
 * Отсюда порядок: публичные проверенные списки, потом Moat напрямую, потом Moat
 * через шведский релей (`bridges.torproject.org` уже есть в его списке
 * разрешённых имён — это единственный путь на сети, где нет IPv6).
 *
 * ## Чего здесь ещё нет
 *
 * Самого транспорта. Поднять tor — это ~46 МБ AAR (`tor-android` + `IPtProxy`),
 * причём `IPtProxy` собран через gomobile и столкнётся с `nova-core` теми же
 * `libgojni.so` и классами пакета `go`, из-за которых Xray пришлось собирать
 * отдельной c-shared библиотекой. Пока этого нет, кнопка TOR не меняет регион, а
 * запускает сбор мостов и говорит об этом словами (I4).
 */
/**
 * Выбранный способ входа в сеть Tor — файлом, а не в `SharedPreferences`.
 *
 * Значение читает процесс `:vpn`, а пишет экран. Настройки кэшируются
 * попроцессно (I2), поэтому prefs здесь означали бы «выбрал, а служба не
 * увидела» — ровно тот дефект, ради которого написано правило. Файл лежит рядом
 * с мостами и читается тем же способом.
 */
object TorEntryModeStore {

    private const val FILE_NAME = "tor_entry_mode.txt"

    fun read(context: Context?): String {
        val file = fileFor(context) ?: return ConnectionSelectorPolicy.DEFAULT_TOR_ENTRY
        val raw = runCatching { file.baseFile.takeIf { it.exists() }?.readText(Charsets.UTF_8) }
            .getOrNull()
            .orEmpty()
        return ConnectionSelectorPolicy.normalizeTorEntry(raw)
    }

    fun write(context: Context?, mode: String): Boolean {
        val file = fileFor(context) ?: return false
        val normalized = ConnectionSelectorPolicy.normalizeTorEntry(mode)
        return runCatching {
            val stream = file.startWrite()
            try {
                stream.write(normalized.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
                true
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }.getOrDefault(false)
    }

    private fun fileFor(context: Context?): AtomicFile? {
        val dir = context?.applicationContext?.filesDir ?: return null
        return AtomicFile(File(dir, FILE_NAME))
    }
}

object TorBridgeManager {

    /**
     * Зеркала измеренного лидера — сборщика `OnionHop`.
     *
     * Четыре адреса на один и тот же файл, и запрашиваются они **наперегонки**:
     * побеждает первый, кто отдал непустой список. Последовательный перебор
     * здесь не работает — на сети, где `raw.githubusercontent.com` придушен, он
     * ждал бы весь таймаут прежде, чем попробовать живое зеркало. А одного
     * адреса мало тем более: у прошлой версии все четыре ссылки вели на
     * `raw.githubusercontent.com`, то есть «запасной источник» падал вместе с
     * основным. Приём взят из BridgeHop (`sources/mod.rs`, GPL-3.0-or-later).
     *
     * GitHub Pages, jsDelivr и Statically раздают те же файлы через другую
     * инфраструктуру, поэтому хотя бы один обычно доступен.
     */
    private val MIRROR_BASES = listOf(
        "https://raw.githubusercontent.com/center2055/OnionHop-Bridges-Collector/main/bridge/",
        "https://center2055.github.io/OnionHop-Bridges-Collector/bridge/",
        "https://cdn.jsdelivr.net/gh/center2055/OnionHop-Bridges-Collector@main/bridge/",
        "https://cdn.statically.io/gh/center2055/OnionHop-Bridges-Collector@main/bridge/",
    )

    /**
     * Запасной сборщик.
     *
     * Те же имена файлов, но у него признак «протестировано» протух: измерено
     * 38-44 % живых против 100 % у `OnionHop`. Поэтому он спрашивается только
     * после основного и только для `obfs4`.
     */
    private val FALLBACK_BASES = listOf(
        "https://raw.githubusercontent.com/Delta-Kronecker/Tor-Bridges-Collector/main/bridge/",
        "https://cdn.jsdelivr.net/gh/Delta-Kronecker/Tor-Bridges-Collector@main/bridge/",
    )

    /** Файлы у обоих сборщиков называются одинаково. */
    private val LIST_FILES = listOf("obfs4_tested.txt", "webtunnel_tested.txt", "vanilla_tested.txt")

    private const val FALLBACK_FILE = "obfs4_tested.txt"

    private const val MOAT_SETTINGS_URL = "https://bridges.torproject.org/moat/circumvention/settings"
    private const val MOAT_BUILTIN_URL = "https://bridges.torproject.org/moat/circumvention/builtin"

    /**
     * Сколько мостов **каждого вида** проверять живостью.
     *
     * Бюджет обязан быть свой у каждого транспорта. Общий счётчик на 24 попытки
     * шёл по списку в порядке сбора, а первым собирается `obfs4` — сотни строк.
     * До `vanilla` очередь не доходила никогда: его скачивали, разбирали и
     * выбрасывали целиком, потому что непроверенный мост в файл не попадает.
     */
    private const val PROBE_LIMIT_PER_TRANSPORT = 16
    private const val PROBE_LIMIT_WEBTUNNEL = 8
    private const val PROBE_TIMEOUT_MS = 6_000

    /**
     * Проверки идут параллельно.
     *
     * Последовательно 24 TCP-проверки по 6 с и 8 апгрейдов по 12 с давали до
     * четырёх минут на нажатие кнопки, и всё это время замок сбора удерживался.
     * Восемь рабочих сводят худший случай к десяткам секунд.
     */
    private const val PROBE_WORKERS = 8

    /** Общий потолок на всю проверку: ни один сетевой отказ не должен её подвесить. */
    private const val PROBE_BUDGET_MS = 60_000L

    /** Гонка зеркал: ждём победителя не дольше, чем один честный запрос. */
    private const val MIRROR_RACE_TIMEOUT_MS = 25_000L

    /** Сколько мостов оставлять в файле. Tor всё равно не держит больше 30 PT-сессий. */
    private const val KEEP_LIMIT = 40

    /** Свежести списка хватает на сутки: источники обновляются раз в час, мы — нет. */
    private const val FRESH_FOR_MS = 24L * 60L * 60L * 1000L

    private val running = AtomicBoolean(false)

    /** Идёт ли сбор прямо сейчас — для надписи на экране. */
    fun isRunning(): Boolean = running.get()

    fun snapshot(context: Context?): TorBridgeStore.Snapshot = TorBridgeStore.read(context)

    /** Строка о состоянии мостов для экрана настроек. */
    fun summary(context: Context?): String {
        if (running.get()) return "TOR: обновляем список мостов…"
        val snapshot = TorBridgeStore.read(context)
        if (snapshot.isEmpty) {
            return if (snapshot.lastError.isNotBlank()) {
                "TOR: мостов пока нет — ${snapshot.lastError}"
            } else {
                "TOR: мосты ещё не загружались"
            }
        }
        val parts = listOf("obfs4", "webtunnel", "vanilla")
            .mapNotNull { kind -> snapshot.countOf(kind).takeIf { it > 0 }?.let { "$kind $it" } }
        return "TOR: живых мостов ${snapshot.bridges.size} (${parts.joinToString(", ")})"
    }

    /**
     * Обновляет список в отдельном потоке.
     *
     * Однопоточный заслон — свой, а не «нет ли уже данных»: пользователь может
     * жать кнопку сколько угодно, а прогон занимает десятки секунд.
     *
     * @return начался ли сбор именно сейчас. Возвращается, а не подразумевается:
     *         экран пишет об этом пользователю, и «загружаем мосты» на отказе
     *         из-за свежего списка было бы обещанием работы, которой нет.
     */
    fun refreshInBackground(context: Context, reason: String, force: Boolean = false): Boolean {
        val app = context.applicationContext
        if (!force) {
            val known = TorBridgeStore.read(app)
            val age = System.currentTimeMillis() - known.updatedAtMs
            if (!known.isEmpty && age in 0 until FRESH_FOR_MS) {
                LogManager.log(
                    "Tor: список мостов свежий (${known.bridges.size} шт., ${age / 60000} мин) — " +
                        "повторный сбор не нужен ($reason)."
                )
                return false
            }
        }
        if (!running.compareAndSet(false, true)) {
            LogManager.log("Tor: сбор мостов уже идёт — второй не заводим ($reason).")
            return false
        }
        Thread({
            try {
                runRefresh(app, reason)
            } catch (e: Throwable) {
                LogManager.log("Tor: сбор мостов оборвался — ${e.message}")
            } finally {
                running.set(false)
            }
        }, "NovaTorBridges").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
        return true
    }

    private fun runRefresh(context: Context, reason: String) {
        LogManager.log("Tor: собираем мосты ($reason).")
        val client = OkHttpClient.Builder()
            .connectTimeout(10L, TimeUnit.SECONDS)
            .readTimeout(20L, TimeUnit.SECONDS)
            .callTimeout(40L, TimeUnit.SECONDS)
            .build()

        val collected = linkedMapOf<String, TorBridge>()
        val sources = mutableListOf<String>()
        var lastError = ""

        val pool = java.util.concurrent.Executors.newFixedThreadPool(PROBE_WORKERS) { runnable ->
            Thread(runnable, "NovaTorProbe").apply { isDaemon = true }
        }
        try {
            (LIST_FILES.map { file -> MIRROR_BASES to file } + listOf(FALLBACK_BASES to FALLBACK_FILE))
                .forEach { (bases, file) ->
                    val won = fetchRaced(client, pool, bases, file)
                    if (won == null) {
                        lastError = "список $file недоступен ни с одного зеркала"
                        LogManager.log("Tor: $lastError (${bases.size} шт.).")
                        return@forEach
                    }
                    val (url, body) = won
                    var added = 0
                    var parsed = 0
                    body.lineSequence().forEach { raw ->
                        val bridge = TorBridge.parse(raw) ?: return@forEach
                        parsed++
                        if (collected.put(bridge.id, bridge) == null) added++
                    }
                    when {
                        added > 0 -> {
                            sources.add(shortHost(url))
                            LogManager.log("Tor: из списка $file (${shortHost(url)}) взято $added мостов.")
                        }
                        // Молчать здесь нельзя: «скачалось, но ни одной строки не
                        // разобралось» — это смена формата у источника, и внешне
                        // она неотличима от «источник не ответил» (I4).
                        parsed == 0 -> LogManager.log(
                            "Tor: список $file (${shortHost(url)}) скачался, но ни одной строки моста в нём не разобрано."
                        )
                        else -> LogManager.log(
                            "Tor: список $file (${shortHost(url)}) не добавил новых мостов — все $parsed уже были."
                        )
                    }
                }

            // Moat — «первый контакт»: он отдаёт по два моста на адрес и не ротирует,
            // зато его snowflake-строка несёт актуальные фронты именно для этой страны.
            val moat = fetchMoat(client, context)
            moat.forEach { bridge -> collected.putIfAbsent(bridge.id, bridge) }
            if (moat.isNotEmpty()) sources.add("moat")

            if (collected.isEmpty()) {
                rememberFailure(context, lastError.ifBlank { "ни один источник не ответил" })
                return
            }

            val alive = probeAlive(collected.values.toList(), pool)
            if (alive.isEmpty()) {
                rememberFailure(context, "ни один мост не ответил на проверку")
                return
            }

            val stored = TorBridgeStore.write(
                context,
                TorBridgeStore.Snapshot(
                    bridges = alive.take(KEEP_LIMIT),
                    updatedAtMs = System.currentTimeMillis(),
                    source = sources.distinct().joinToString(", "),
                    lastError = "",
                ),
            )
            // Об отказе записи говорим вслух: без этого журнал сообщал бы об
            // успешном сборе, которого на диске нет, и следующий запуск считал бы
            // список просто устаревшим (I4).
            LogManager.log(
                if (stored) {
                    "Tor: из ${collected.size} собранных мостов живыми оказались ${alive.size}, " +
                        "сохранили ${minOf(alive.size, KEEP_LIMIT)} " +
                        "(источники: ${sources.distinct().joinToString(", ")})."
                } else {
                    "Tor: живых мостов ${alive.size}, но записать список не удалось — " +
                        "останется прежний."
                }
            )
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * Неудачный сбор не стирает прошлый список.
     *
     * Раньше сюда писался пустой снимок, и это было утверждение «мостов нет», а
     * не «в этот раз не узнали» (I3). `AtomicFile.finishWrite` удаляет и
     * резервную копию, так что сорок проверенных мостов пропадали безвозвратно —
     * достаточно было нажать кнопку в поезде без связи. Теперь прежние мосты
     * остаются на месте, меняется только строка ошибки; время обновления не
     * трогаем, чтобы следующий заход не считал список свежим.
     */
    private fun rememberFailure(context: Context, error: String) {
        val known = TorBridgeStore.read(context)
        TorBridgeStore.write(
            context,
            known.copy(lastError = error),
        )
        LogManager.log(
            if (known.isEmpty) {
                "Tor: $error. Сохранённых мостов нет."
            } else {
                "Tor: $error. Оставляем прошлый список — ${known.bridges.size} шт."
            }
        )
    }

    /**
     * Живость.
     *
     * `obfs4` и «ванильные» — обычный TCP-connect. `webtunnel` — только апгрейд до
     * WebSocket: обычный `GET` отдаёт 502 и на живом мосту, так что «проверка
     * загрузкой страницы» объявила бы мёртвыми больше половины рабочих.
     * `snowflake` не проверяется вовсе: у него нет своего адреса, он живёт через
     * брокера, и «мост» здесь — это набор фронтов.
     */
    private fun probeAlive(
        bridges: List<TorBridge>,
        pool: java.util.concurrent.ExecutorService,
    ): List<TorBridge> {
        // Только HTTP/1.1: см. [webtunnelUpgrades]. По HTTP/2 апгрейда не бывает,
        // и проверка объявляла мёртвыми все мосты подряд.
        val client = OkHttpClient.Builder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectTimeout(PROBE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout((PROBE_TIMEOUT_MS * 2).toLong(), TimeUnit.MILLISECONDS)
            .build()

        // Бюджет свой у каждого транспорта: см. PROBE_LIMIT_PER_TRANSPORT.
        val tcpCandidates = bridges
            .filter { it.transport != "webtunnel" }
            .groupBy { it.transport }
            .flatMap { (_, list) -> list.take(PROBE_LIMIT_PER_TRANSPORT) }
        val webtunnelCandidates = bridges
            .filter { it.transport == "webtunnel" && it.url.isNotBlank() }
            .take(PROBE_LIMIT_WEBTUNNEL)

        val tasks = buildList<java.util.concurrent.Callable<TorBridge?>> {
            tcpCandidates.forEach { bridge ->
                val target = bridge.dialTarget() ?: return@forEach
                add(java.util.concurrent.Callable { bridge.takeIf { tcpConnects(target.first, target.second) } })
            }
            webtunnelCandidates.forEach { bridge ->
                add(java.util.concurrent.Callable { bridge.takeIf { webtunnelUpgrades(client, bridge.url) } })
            }
        }

        // Общий потолок обязателен: без него один зависший сокет держал бы весь
        // сбор, а вместе с ним и заслон `running`.
        val probed = runCatching {
            pool.invokeAll(tasks, PROBE_BUDGET_MS, TimeUnit.MILLISECONDS)
                .mapNotNull { future -> runCatching { future.get() }.getOrNull() }
        }.getOrElse { error ->
            LogManager.log("Tor: проверка живости прервана — ${error.message}")
            emptyList()
        }

        return probed
    }

    /**
     * Гонка зеркал: побеждает первое, отдавшее непустой ответ.
     *
     * `invokeAny` возвращает результат первой задачи, завершившейся **без
     * исключения**, и снимает остальные. Поэтому пустой ответ здесь обязан
     * бросать: иначе зеркало, отдающее 200 и пустое тело, выиграло бы гонку у
     * живого.
     */
    private fun fetchRaced(
        client: OkHttpClient,
        pool: java.util.concurrent.ExecutorService,
        bases: List<String>,
        file: String,
    ): Pair<String, String>? {
        val tasks = bases.map { base ->
            java.util.concurrent.Callable {
                val url = base + file
                val body = fetchText(client, url)
                if (body.isBlank()) error("пустой ответ")
                url to body
            }
        }
        return runCatching {
            pool.invokeAny(tasks, MIRROR_RACE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    private fun tcpConnects(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
            socket.isConnected
        }
    }.getOrDefault(false)

    /**
     * Живой webtunnel отвечает `101 Switching Protocols`, а не `200`.
     *
     * Клиент обязан быть **отдельным и строго HTTP/1.1**. Общий клиент
     * договаривается по ALPN на HTTP/2, а в HTTP/2 заголовки соединения —
     * `Connection` и `Upgrade` — запрещены: апгрейда не происходит, мост отвечает
     * обычным ответом, и проверка объявляет мёртвыми **все** мосты подряд.
     * Измерено на Mi A1 2026-09-06: 0 из 8 живых при том, что те же адреса с
     * машины по HTTP/1.1 отдавали `101`.
     */
    private fun webtunnelUpgrades(client: OkHttpClient, url: String): Boolean = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Connection", "Upgrade")
            .header("Upgrade", "websocket")
            .header("Sec-WebSocket-Version", "13")
            .header("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
            .build()
        client.newCall(request).execute().use { it.code == 101 }
    }.getOrDefault(false)

    /**
     * Moat: сначала напрямую, затем через шведский релей.
     *
     * Прямой путь работает на сетях с IPv6 — блокировка в РФ измерена как чисто
     * SNI-фильтрация по IPv4. Релей нужен именно там, где IPv6 нет: имя
     * `bridges.torproject.org` уже стоит в его списке разрешённых.
     */
    private fun fetchMoat(client: OkHttpClient, context: Context): List<TorBridge> {
        val payload = JSONObject()
            .put("country", "ru")
            // snowflake не просим: транспорта в ядре нет, а место в ответе он занимает.
            .put("transports", JSONArray(listOf("webtunnel", "obfs4")))
            .toString()

        runCatching { parseMoat(postJson(client, MOAT_SETTINGS_URL, payload)) }
            .onSuccess { if (it.isNotEmpty()) { LogManager.log("Tor: Moat ответил напрямую, мостов ${it.size}."); return it } }
            .onFailure { LogManager.log("Tor: Moat напрямую не ответил — ${it.message}") }

        val relayed = fetchMoatViaRelay(payload) ?: return emptyList()
        LogManager.log("Tor: Moat ответил через релей, мостов ${relayed.size}.")
        return relayed
    }

    private fun fetchMoatViaRelay(payload: String): List<TorBridge>? {
        if (!NovaRelay.isConfigured()) {
            LogManager.log("Tor: релей не настроен — путь к Moat через него пропущен.")
            return null
        }
        if (NovaRelay.isOutdated()) {
            LogManager.log("Tor: ${NovaRelay.OUTDATED_MESSAGE}.")
            return null
        }
        val bridge = TlsRelayBridge("moat-relay")
        val credential = okhttp3.Credentials.basic(NovaRelay.keyId(), NovaRelay.password())
        try {
            NovaRelay.ENDPOINTS.forEachIndexed { index, (host, port) ->
                val endpoint = bridge.start("https://$host:$port", LogManager::log) ?: run {
                    LogManager.log("Tor: релей ${NovaRelay.describe(index)} не поднялся, пробуем следующий.")
                    return@forEachIndexed
                }
                val client = OkHttpClient.Builder()
                    .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress(endpoint.localHost, endpoint.localPort)))
                    .proxyAuthenticator { _, response ->
                        if (NovaRelay.noteFromResponse(response, "Moat")) null
                        else if (response.request.header("Proxy-Authorization") != null) null
                        else response.request.newBuilder()
                            .header("Proxy-Authorization", credential)
                            .build()
                    }
                    .connectTimeout(8L, TimeUnit.SECONDS)
                    .readTimeout(20L, TimeUnit.SECONDS)
                    .callTimeout(30L, TimeUnit.SECONDS)
                    .build()
                val parsed = runCatching { parseMoat(postJson(client, MOAT_SETTINGS_URL, payload)) }
                    .getOrElse { error ->
                        LogManager.log("Tor: Moat через ${NovaRelay.describe(index)} — ${error.message}")
                        null
                    }
                if (parsed != null && parsed.isNotEmpty()) {
                    NovaRelay.clearOutdated()
                    return parsed
                }
            }
        } finally {
            bridge.stop(LogManager::log)
        }
        return null
    }

    /**
     * Разбор ответа Moat.
     *
     * Порядок в `settings` задаёт сервер, и он же задаёт приоритет — от лучшего
     * транспорта для этой страны к худшему. Переупорядочивать его нельзя: у
     * сервера есть данные о стране, которых у клиента нет.
     */
    private fun parseMoat(body: String): List<TorBridge> {
        val json = JSONObject(body)
        val settings = json.optJSONArray("settings") ?: return emptyList()
        val result = mutableListOf<TorBridge>()
        for (i in 0 until settings.length()) {
            val bridges = settings.optJSONObject(i)?.optJSONObject("bridges") ?: continue
            val strings = bridges.optJSONArray("bridge_strings") ?: continue
            for (j in 0 until strings.length()) {
                TorBridge.parse(strings.optString(j))?.let(result::add)
            }
        }
        return result
    }

    private fun fetchText(client: OkHttpClient, url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", "Nova").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun postJson(client: OkHttpClient, url: String, payload: String): String {
        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody("application/vnd.api+json".toMediaTypeOrNull()))
            .header("User-Agent", "Nova")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    private fun shortHost(url: String): String =
        runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").ifBlank { url }

    /** Адрес встроенных мостов Moat — нужен, только когда список совсем пуст. */
    fun builtinUrl(): String = MOAT_BUILTIN_URL
}

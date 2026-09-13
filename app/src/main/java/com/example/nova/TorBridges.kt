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
        if (addressIsDecoration(transport)) return null
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

    /**
     * Значение аргумента `имя=` из строки моста.
     *
     * Читается из [line], а не хранится полем: полей у строки моста два десятка
     * (`fronts`, `ice`, `ampcache`, `sqsqueue`, `utls-imitate`…), они разные у
     * разных транспортов и меняются с версиями snowflake. Хранить их значило бы
     * повышать версию файла на каждый новый аргумент и терять те, о которых мы
     * ещё не знаем; строка же хранится целиком именно затем, чтобы её не
     * приходилось разбирать на поля.
     *
     * @return значение без имени, либо пустая строка.
     */
    fun arg(name: String): String {
        val prefix = "$name="
        return line.split(Regex("\\s+"))
            .firstOrNull { it.startsWith(prefix, ignoreCase = true) }
            ?.substring(prefix.length)
            .orEmpty()
    }

    companion object {

        /**
         * Транспорты, у которых адрес в начале строки — украшение, а не адрес.
         *
         * `webtunnel` пишет туда документационный `2001:db8::/32`, `snowflake` —
         * `192.0.2.0/24`: и тот и другой это идентификатор моста для tor, а
         * настоящая точка входа лежит в аргументах (`url=` у первого, брокер и
         * фронты у второго). Предикат общий на все четыре места, где он нужен
         * (разбор, проверка живости, отбор перед torrc, ожидание мостов в
         * службе): расходящиеся копии одного признака в этом проекте уже стоили
         * дефекта (G49), а четвёртая копия — ещё одного (I23).
         */
        fun addressIsDecoration(transport: String): Boolean =
            transport == "webtunnel" || transport == "snowflake"

        /**
         * Годится ли мост к употреблению этим транспортом.
         *
         * «Годится» значит «есть куда звонить»: либо разобранный адрес, либо
         * транспорт, у которого адреса и не должно быть.
         */
        fun isUsable(bridge: TorBridge): Boolean =
            addressIsDecoration(bridge.transport) || bridge.dialTarget() != null

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
         * Ровно те четыре, что отдаёт `ConnectionSelectorPolicy.torBridgeTransportFor`.
         * Всё остальное — мост, который нельзя выбрать: у `meek_lite` и `conjure`
         * нет способа входа, `obfs3`/`obfs2` lyrebird не даёт вовсе. Сборщик их
         * исправно приносил, хранил и считал: на экране стояло «живых мостов 37
         * (obfs4 15, webtunnel 5, snowflake 2, vanilla 15)» — тридцать семь, из
         * которых подключиться могли тридцать пять. Счёт, которым нельзя
         * воспользоваться, — неверный счёт, поэтому лишнее отбрасывается в
         * разборе, а не прячется в отрисовке.
         *
         * `snowflake` стоит здесь с 1.32.2: транспорт собран в ядро (G176
         * закрыт), сокеты pion и рандеву с брокером помечены `protect()`, а
         * строки мостов у него встроенные — см. [TorBuiltinBridges].
         */
        private val SUPPORTED_TRANSPORTS = setOf("obfs4", "webtunnel", "snowflake", "vanilla")

        /**
         * Те из них, что ходят через наш SOCKS с транспортом в ядре.
         *
         * У `vanilla` транспорта нет вовсе: tor соединяется своим сокетом, и
         * аргументов никому не передаёт — предел на них к нему не относится.
         */
        private val PLUGGABLE_TRANSPORTS = setOf("obfs4", "webtunnel", "snowflake")

        /**
         * Предел на аргументы строки моста: логин и пароль SOCKS5 по 255 байт.
         *
         * Это не наше ограничение и не запас «на всякий случай», а формат:
         * поле логина и поле пароля в RFC 1929 однобайтовой длины каждое, и tor
         * раскладывает аргументы по обоим. Наша сторона их обратно склеивает
         * (`socks5ReadUserPassword` в `engine/tor_obfs4.go`), но того, что в них
         * не поместилось, склеивать неоткуда.
         */
        private const val MAX_SOCKS5_ARG_BYTES = 510

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
            // Строка snowflake с рандеву через SQS не хранится и не считается.
            //
            // Внутри snowflake выбор способа встречи с брокером кончается
            // `log.Fatalln`, если рядом с `sqsqueue=` оказался ещё и адрес
            // брокера (`client/lib/rendezvous.go`), — а это `os.Exit(1)` в
            // процессе `:vpn`, то есть смерть живого туннеля от строки,
            // пришедшей по сети или из буфера обмена. Ядро такую строку тоже
            // отвергает (`engine/tor_snowflake.go`), и это главная защита; здесь
            // же она отсекается раньше, чтобы не занимать место в списке и не
            // попадать в счёт мостов, которыми нельзя подключиться.
            if (parts.any { it.startsWith("sqsqueue=", true) || it.startsWith("sqscreds=", true) }) {
                return null
            }
            val rest = if (hasTransport) parts.drop(1) else parts
            val endpoint = rest.getOrNull(0)?.takeIf { it.contains(':') } ?: return null
            val fingerprint = rest.getOrNull(1)
                ?.takeIf { it.length == 40 && it.all { c -> c.isDigit() || c in 'A'..'F' || c in 'a'..'f' } }
                ?.uppercase()
                .orEmpty()
            val url = rest.firstOrNull { it.startsWith("url=") }?.removePrefix("url=").orEmpty()
            // Аргументы, которые не доедут до транспорта, — это мост, который не
            // подключится молча.
            //
            // Внешнему pluggable transport tor передаёт аргументы строки моста
            // полями логина и пароля SOCKS5, по 255 байт в каждом, то есть 510
            // всего. Канонические встроенные строки snowflake занимают 384 —
            // запас есть, но не бесконечный, а строки приходят и от Moat, и из
            // буфера обмена, и их длину мы не выбираем. Обрезанные аргументы
            // выглядят снаружи как «мост не отвечает», и найти причину в этом
            // виде невозможно.
            //
            // Проверка стоит здесь, а не перед записью torrc, по той же причине,
            // что и остальные: иначе такой мост попал бы и в файл, и в счёт на
            // экране, и считался бы годным.
            if (transport in PLUGGABLE_TRANSPORTS) {
                val args = rest.drop(1).filter { it.contains('=') }.joinToString(";")
                if (args.toByteArray(Charsets.UTF_8).size > MAX_SOCKS5_ARG_BYTES) {
                    LogManager.log(
                        "Tor: строка моста $transport отброшена — аргументов " +
                            "${args.toByteArray(Charsets.UTF_8).size} Б при пределе $MAX_SOCKS5_ARG_BYTES: " +
                            "tor не смог бы передать их транспорту целиком."
                    )
                    return null
                }
            }
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
 * Встроенные строки мостов — те, которых сборщик не приносит.
 *
 * ## Почему у snowflake строки вообще встроенные
 *
 * У obfs4 и webtunnel мост — это хост, их тысячи, и смысл сборщика в том, чтобы
 * найти живой. У snowflake мостов **два на всю сеть**, и «мост» здесь не адрес,
 * а способ договориться: клиент идёт к брокеру, брокер сводит его со случайной
 * «снежинкой» — чужим браузером с расширением, — и данные идут по WebRTC. Менять
 * в такой строке нечего, поэтому Tor Browser, Orbot и сам snowflake возят её с
 * собой. Публичные сборщики мостов её не отдают по той же причине: собирать
 * нечего.
 *
 * ## Откуда взяты эти строки
 *
 * Из `tools/snowflake/client/torrc` и `client/README.md` — то есть из исходников
 * ровно той версии snowflake, что собрана в ядро (`v2.14.1`, разложена из кэша
 * модулей и сверена по `go.sum`, см. `tools/deps/fetch_go_deps.sh`). Это лучший
 * доступный источник: `gitlab.torproject.org` из России не открывается вовсе, а
 * произвольное зеркало проверить нечем.
 *
 * ## Два набора, а не один
 *
 * Отличаются они местом встречи с брокером, и это единственное, что у snowflake
 * можно заблокировать:
 *
 *  * [SNOWFLAKE_CDN77] — рандеву фронтингом через CDN77. Основной путь, им же
 *    ходит Tor Browser.
 *  * [SNOWFLAKE_AMP] — рандеву через AMP-кэш Google с фронтом `www.google.com`.
 *    У upstream он закомментирован как запасной; нам он нужен именно как
 *    запасной, потому что блокировать `www.google.com` дороже, чем CDN77.
 *
 * **Одновременно в torrc едет только один набор.** Отпечатки у наборов одни и
 * те же (мостов-то два), различаются только адреса-украшения `192.0.2.3/.4`
 * против `.5/.6`: отдав tor'у оба, мы отдали бы ему четыре моста с двумя
 * личностями. Какой набор брать, решает проверка живости в [TorBridgeManager] —
 * замером, а не предположением.
 *
 * ## Чего здесь намеренно нет
 *
 * Набора с рандеву через SQS: он носит в строке ключ доступа AWS чужого
 * проекта, и живучесть у него не наша.
 *
 * Своих STUN-серверов в `ice=`: список менять нельзя без счёта байтов. Tor
 * передаёт аргументы моста внешнему транспорту в полях логина и пароля SOCKS5,
 * по 255 байт каждое, то есть 510 всего; у строки ниже аргументы занимают 384
 * байта — запас 126. Два лишних сервера этот запас съедят, и мост перестанет
 * подключаться молча.
 */
object TorBuiltinBridges {

    /**
     * Рандеву через CDN77. Основной путь.
     *
     * Замер с российской сети (Ростелеком, 2026-09-12): `1098762253.rsc.cdn77.org`,
     * `www.cdn77.com` и `www.phpmyadmin.net` отвечают по HTTPS, из восьми
     * серверов `ice=` на запрос STUN отвечают шесть (`voipgate` и `mixvoip`
     * молчат). Строка при этом оставлена канонической: лишний сервер дороже
     * двух молчащих — см. счёт байтов в шапке.
     */
    val SNOWFLAKE_CDN77: List<String> = listOf(
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://1098762253.rsc.cdn77.org/ " +
            "fronts=www.cdn77.com,www.phpmyadmin.net " +
            "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478," +
            "stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
            "stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 " +
            "utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA " +
            "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA " +
            "url=https://1098762253.rsc.cdn77.org/ " +
            "fronts=www.cdn77.com,www.phpmyadmin.net " +
            "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478," +
            "stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
            "stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 " +
            "utls-imitate=hellorandomizedalpn",
    )

    /**
     * Рандеву через AMP-кэш. Запасной путь на случай, когда CDN77 закрыли.
     *
     * Замер оттуда же, 2026-09-12: `cdn.ampproject.org` отвечает (404 на корень —
     * это ответ, а не молчание), `www.google.com` — 200, сам
     * `snowflake-broker.torproject.net` не заблокирован ни по имени, ни по адресу.
     */
    val SNOWFLAKE_AMP: List<String> = listOf(
        "snowflake 192.0.2.5:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
            "url=https://snowflake-broker.torproject.net/ " +
            "ampcache=https://cdn.ampproject.org/ front=www.google.com " +
            "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478," +
            "stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
            "stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 " +
            "utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.6:80 8838024498816A039FCBBAB14E6F40A0843051FA " +
            "fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA " +
            "url=https://snowflake-broker.torproject.net/ " +
            "ampcache=https://cdn.ampproject.org/ front=www.google.com " +
            "ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478," +
            "stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478," +
            "stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 " +
            "utls-imitate=hellorandomizedalpn",
    )
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

/**
 * Докуда дошёл перебор входов в режиме «Авто».
 *
 * ## Зачем это нужно вообще
 *
 * Перебор написан как цикл внутри фазы Tor, и на бумаге он такой и есть. На деле
 * до второго способа он не доходил никогда, и вот почему. Библиотеку tor нельзя
 * запускать в процессе дважды: её API на это не рассчитан, второй
 * `tor_run_main` кончается `SIGABRT` (G185). Поэтому `TorTransport.start`
 * возвращает `NEEDS_FRESH_PROCESS`, и процесс `:vpn` переподнимается. А свежий
 * процесс читает `tor_entry_mode.txt`, видит там «Авто» и начинает перебор
 * **с начала** — с того же способа, который только что не подошёл. Карусель.
 *
 * Пока способы отваливались до запуска tor (мостов такого вида нет — это дёшево
 * и в том же процессе), дефект был не виден: цикл честно доходил до конца.
 * Стоило первому способу дойти до загрузки и на ней не построить цепочку — и
 * перебор превращался в бесконечное повторение первого варианта.
 *
 * ## Как решено
 *
 * Файлом, по тем же причинам, что и сам способ входа: пишет его процесс `:vpn`,
 * а пережить он должен смерть этого процесса (`SharedPreferences` кэшируются
 * попроцессно, I2, и до диска доехать не успели бы).
 *
 * Помеченные способы выбывают из перебора, и свежий процесс продолжает с того
 * места, где предыдущий остановился. Когда выбыли все — перебор объявляется
 * исчерпанным вслух, а память очищается, чтобы следующая попытка начиналась
 * заново.
 *
 * ## Почему запись протухает
 *
 * Забытая отметка хуже отсутствующей: она вычёркивает рабочий способ из
 * перебора навсегда. Полный перебор из четырёх способов стоит в худшем случае
 * четырёх загрузок по 150 с плюс перезапуски — [FRESH_FOR_MS] взят с запасом
 * над этим и при этом достаточно мал, чтобы попытка через полчаса начиналась с
 * чистого листа.
 */
object TorAutoEntryProgress {

    private const val FILE_NAME = "tor_auto_entry.txt"

    /** Полчаса: с запасом над худшим полным перебором и без памяти на следующий раз. */
    private const val FRESH_FOR_MS = 30L * 60L * 1000L

    /**
     * Способы, которые в идущем сейчас переборе уже не сработали.
     *
     * Протухшая запись читается как пустая — и тут же удаляется, чтобы не
     * разбирать её снова на каждом подключении.
     */
    fun read(context: Context?): Set<String> {
        val file = fileFor(context) ?: return emptySet()
        val raw = runCatching { file.baseFile.takeIf { it.exists() }?.readText(Charsets.UTF_8) }
            .getOrNull()
            .orEmpty()
        if (raw.isBlank()) return emptySet()
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val stamp = lines.firstOrNull()?.toLongOrNull() ?: return emptySet()
        val age = System.currentTimeMillis() - stamp
        if (age !in 0 until FRESH_FOR_MS) {
            clear(context)
            return emptySet()
        }
        return lines.drop(1).toSet()
    }

    /** Помечает способ как не сработавший. Отметка времени обновляется на каждой записи. */
    fun note(context: Context?, mode: String): Boolean {
        val file = fileFor(context) ?: return false
        val updated = read(context) + mode
        val payload = (listOf(System.currentTimeMillis().toString()) + updated).joinToString("\n")
        return runCatching {
            val stream = file.startWrite()
            try {
                stream.write(payload.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
                true
            } catch (error: Throwable) {
                file.failWrite(stream)
                throw error
            }
        }.getOrDefault(false)
    }

    /** Забывает перебор: после успеха, после смены способа входа руками и на исчерпании. */
    fun clear(context: Context?) {
        val file = fileFor(context) ?: return
        runCatching { file.delete() }
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

    /**
     * Проверка рандеву snowflake: срок короткий и число попыток ограничено.
     *
     * Худший случай — сеть, где закрыто всё: два набора по [RENDEZVOUS_PROBE_LIMIT]
     * проверок, то есть шесть запросов. При восьми секундах на запрос это меньше
     * минуты, а не минуты; медленнее было бы платить за ответ, которым всё равно
     * нельзя воспользоваться.
     */
    private const val RENDEZVOUS_PROBE_TIMEOUT_MS = 4_000L
    private const val RENDEZVOUS_PROBE_LIMIT = 3

    /** Сколько мостов оставлять в файле. Tor всё равно не держит больше 30 PT-сессий. */
    private const val KEEP_LIMIT = 40

    /**
     * Все виды мостов, которые приложение умеет поднимать.
     *
     * Один список на счётчик, на сводку и на дележ [KEEP_LIMIT]: три
     * перечисления одного и того же расходятся ровно тогда, когда появляется
     * четвёртый транспорт, и расхождение выглядит как «мост есть, но его не
     * видно».
     */
    private val KNOWN_KINDS = listOf("obfs4", "webtunnel", "snowflake", "vanilla")

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
        val parts = KNOWN_KINDS
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

        // Вердикты о рандеву — на весь прогон, а не на вызов.
        //
        // Место встречи проверяется дважды: при выборе встроенного набора и
        // потом в `probeAlive`. Без общей памяти второй проход не только платит
        // за те же запросы повторно, но и **отменяет решение первого**: ветка
        // «ни один набор не ответил, оставляем CDN77» писала мосты в список, а
        // `probeAlive` тут же выбрасывал их по той же проверке — то есть
        // запасной путь, ради которого ветка и написана, не существовал.
        // Теперь вердикт один на прогон, и «оставляем» действительно оставляет.
        val rendezvousVerdicts = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

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
            //
            // Он идёт **раньше** встроенных строк намеренно: у обеих записей
            // snowflake один и тот же отпечаток, то есть один и тот же [TorBridge.id],
            // а `putIfAbsent` оставляет первого. Ответ Moat для страны свежее
            // нашей константы и должен побеждать.
            val moat = fetchMoat(client, context)
            moat.forEach { bridge -> collected.putIfAbsent(bridge.id, bridge) }
            if (moat.isNotEmpty()) sources.add("moat")

            // Встроенные строки snowflake — на случай, когда Moat не ответил.
            //
            // Сборщики их не отдают: у snowflake мостов два на всю сеть, и
            // собирать нечего (см. [TorBuiltinBridges]). Без этой строки способ
            // входа snowflake был бы кнопкой, которая молча не подключается.
            val builtin = builtinSnowflake(rendezvousVerdicts)
            var builtinAdded = 0
            builtin.forEach { bridge -> if (collected.putIfAbsent(bridge.id, bridge) == null) builtinAdded++ }
            if (builtinAdded > 0) sources.add("встроенные")

            if (collected.isEmpty()) {
                rememberFailure(context, lastError.ifBlank { "ни один источник не ответил" })
                return
            }

            val alive = probeAlive(collected.values.toList(), pool, rendezvousVerdicts)
            if (alive.isEmpty()) {
                rememberFailure(context, "ни один мост не ответил на проверку")
                return
            }

            val stored = TorBridgeStore.write(
                context,
                TorBridgeStore.Snapshot(
                    bridges = trimKeepingEveryKind(alive),
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
                        "сохранили ${trimKeepingEveryKind(alive).size} " +
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
     * Какой из двух встроенных наборов snowflake брать — решает замер, а не вера.
     *
     * Наборы отличаются только местом встречи с брокером, и именно оно —
     * единственное, что у snowflake можно закрыть. Поэтому сначала проверяется
     * основной путь (CDN77), и лишь если он молчит — запасной (AMP-кэш). Оба
     * сразу отдавать нельзя: отпечатки у них одни и те же, и tor получил бы
     * четыре моста с двумя личностями (см. [TorBuiltinBridges]).
     *
     * Если молчат оба — возвращается всё равно основной набор, но вслух (I4).
     * «Не дозвонились до брокера с этой сети» и «snowflake не работает» — разные
     * утверждения: рандеву идёт с uTLS и из ядра, а не этим клиентом, и оно
     * вполне может пройти там, где не прошла обычная проверка.
     */
    private fun builtinSnowflake(verdicts: MutableMap<String, Boolean>): List<TorBridge> {
        val cdn77 = TorBuiltinBridges.SNOWFLAKE_CDN77.mapNotNull { TorBridge.parse(it) }
        val amp = TorBuiltinBridges.SNOWFLAKE_AMP.mapNotNull { TorBridge.parse(it) }

        if (cdn77.any { snowflakeRendezvousReachable(it, verdicts) }) {
            LogManager.log("Tor: встроенный snowflake — рандеву через CDN77 отвечает, берём его (${cdn77.size} моста).")
            return cdn77
        }
        LogManager.log("Tor: встроенный snowflake — CDN77 молчит, пробуем запасной путь через AMP-кэш.")
        if (amp.any { snowflakeRendezvousReachable(it, verdicts) }) {
            LogManager.log("Tor: встроенный snowflake — рандеву через AMP-кэш отвечает, берём его (${amp.size} моста).")
            return amp
        }
        // Оба молчат — и это ровно тот случай, ради которого встроенный набор и
        // нужен. Вердикт переписывается на «годен» **явно**: обычный запрос идёт
        // без uTLS и без фронтинга, а рандеву в ядре — с ними, и вполне проходит
        // там, где не прошла проверка. Оставить мосты в списке и тут же
        // выбросить их проверкой было бы решением, которое само себя отменяет.
        cdn77.forEach { verdicts[rendezvousKey(it)] = true }
        LogManager.log(
            "Tor: встроенный snowflake — ни CDN77, ни AMP-кэш не ответили обычным запросом. " +
                "Оставляем CDN77 непроверенным: рандеву в ядре идёт с uTLS и может пройти там, где не прошла проверка."
        )
        return cdn77
    }

    /**
     * Отвечает ли место встречи с брокером snowflake.
     *
     * Проверять сам мост бессмысленно: у snowflake мост — это не хост, а
     * договорённость. Ходит клиент к брокеру (`url=`, либо `ampcache=` фронтом),
     * а дальше по WebRTC к случайной «снежинке», которой на момент проверки ещё
     * не существует. Единственное, что можно измерить заранее, — доходит ли
     * запрос до места встречи, и ровно это здесь и меряется.
     *
     * Ответом считается **любой** HTTP-код: корень брокера отдаёт то 404, то
     * 502, то заглушку CDN, и требовать `200` значило бы объявить живой путь
     * мёртвым. Важно, что ответ пришёл, а не какой он.
     */
    private fun snowflakeRendezvousReachable(
        bridge: TorBridge,
        verdicts: MutableMap<String, Boolean>,
    ): Boolean {
        val targets = rendezvousTargets(bridge)
        if (targets.isEmpty()) return false
        val key = targets.joinToString("|")
        verdicts[key]?.let { return it }

        val reachable = targets.take(RENDEZVOUS_PROBE_LIMIT).any { target ->
            runCatching {
                val request = Request.Builder().url(target).header("User-Agent", "Nova").build()
                rendezvousProbeClient.newCall(request).execute().use { true }
            }.getOrDefault(false)
        }
        verdicts[key] = reachable
        return reachable
    }

    /** Куда именно ходят, чтобы встретиться с брокером: AMP-кэш, брокер, фронты. */
    private fun rendezvousTargets(bridge: TorBridge): List<String> {
        val targets = linkedSetOf<String>()
        bridge.arg("ampcache").takeIf { it.isNotBlank() }?.let { targets += it }
        bridge.url.takeIf { it.isNotBlank() }?.let { targets += it }
        (bridge.arg("fronts").split(',') + bridge.arg("front"))
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { targets += "https://$it/" }
        return targets.toList()
    }

    /** Ключ вердикта: у двух мостов одного набора место встречи одно и то же. */
    private fun rendezvousKey(bridge: TorBridge): String = rendezvousTargets(bridge).joinToString("|")

    /**
     * Свой клиент для проверки рандеву, с коротким сроком.
     *
     * Не общий с [runRefresh]: у того `callTimeout` 40 с, а проверок рандеву до
     * [RENDEZVOUS_PROBE_LIMIT] на набор и наборов два — на сети, где закрыто всё,
     * это минуты молчания посреди сбора мостов, и всё это время человек смотрит
     * на «собираем мосты». Здесь же важно не «дождаться ответа во что бы то ни
     * стало», а «ответил ли он быстро»: рандеву, до которого нельзя достучаться
     * за несколько секунд, транспорту всё равно не годится.
     *
     * `by lazy` — клиент общий на объект: у OkHttp за каждым свой пул соединений
     * и свой executor, и заводить их на каждую проверку значит платить больше,
     * чем стоит сама проверка.
     */
    private val rendezvousProbeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(RENDEZVOUS_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(RENDEZVOUS_PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(RENDEZVOUS_PROBE_TIMEOUT_MS * 2, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Обрезает список до [KEEP_LIMIT], но не вырезает при этом целый вид моста.
     *
     * Простое `take(KEEP_LIMIT)` шло по порядку проверки, а порядок этот —
     * obfs4, vanilla, webtunnel, snowflake. Бюджеты проверок дают до 16+16+8+2
     * живых, то есть до сорока двух: последние два, а это ровно оба моста
     * snowflake, в файл не попадали бы никогда. Способ входа при этом был бы на
     * экране — то есть кнопка, которая молча не подключается, ради которой всё
     * и делалось.
     *
     * Поэтому раздача по кругу: сначала по одному мосту каждого вида, потом по
     * второму и так далее. Редкий вид переживает обрезку по построению, а
     * порядок внутри вида сохраняется — у `startLocked` он всё равно свой
     * фильтр по транспорту.
     */
    private fun trimKeepingEveryKind(alive: List<TorBridge>): List<TorBridge> {
        if (alive.size <= KEEP_LIMIT) return alive
        val queues = alive.groupBy { it.transport }.values.map { it.toMutableList() }
        val result = mutableListOf<TorBridge>()
        while (result.size < KEEP_LIMIT && queues.any { it.isNotEmpty() }) {
            for (queue in queues) {
                if (result.size >= KEEP_LIMIT) break
                if (queue.isNotEmpty()) result.add(queue.removeAt(0))
            }
        }
        return result
    }

    /**
     * Живость.
     *
     * `obfs4` и «ванильные» — обычный TCP-connect. `webtunnel` — только апгрейд до
     * WebSocket: обычный `GET` отдаёт 502 и на живом мосту, так что «проверка
     * загрузкой страницы» объявила бы мёртвыми больше половины рабочих.
     * `snowflake` — доступность места встречи с брокером
     * ([snowflakeRendezvousReachable]): своего адреса у него нет.
     *
     * Пропускать snowflake мимо проверки нельзя было бы даже при желании:
     * функция возвращает **только** то, что сама проверила, а `dialTarget()` у
     * snowflake пуст. Без своей ветки все его мосты молча исчезали бы здесь,
     * между сбором и файлом.
     */
    private fun probeAlive(
        bridges: List<TorBridge>,
        pool: java.util.concurrent.ExecutorService,
        rendezvousVerdicts: MutableMap<String, Boolean>,
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
            .filter { !TorBridge.addressIsDecoration(it.transport) }
            .groupBy { it.transport }
            .flatMap { (_, list) -> list.take(PROBE_LIMIT_PER_TRANSPORT) }
        val webtunnelCandidates = bridges
            .filter { it.transport == "webtunnel" && it.url.isNotBlank() }
            .take(PROBE_LIMIT_WEBTUNNEL)
        // Потолка у snowflake нет: его мостов на всю сеть два.
        val snowflakeCandidates = bridges.filter { it.transport == "snowflake" }

        val tasks = buildList<java.util.concurrent.Callable<TorBridge?>> {
            tcpCandidates.forEach { bridge ->
                val target = bridge.dialTarget() ?: return@forEach
                add(java.util.concurrent.Callable { bridge.takeIf { tcpConnects(target.first, target.second) } })
            }
            webtunnelCandidates.forEach { bridge ->
                add(java.util.concurrent.Callable { bridge.takeIf { webtunnelUpgrades(client, bridge.url) } })
            }
            snowflakeCandidates.forEach { bridge ->
                add(java.util.concurrent.Callable { bridge.takeIf { snowflakeRendezvousReachable(bridge, rendezvousVerdicts) } })
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
            // snowflake просим снова: транспорт в ядре с 1.32.2, а его строка от
            // Moat несёт фронты, подобранные под страну, — на замере из РФ это
            // были `cdn.zk.mk,img.icons8.com,cdn.kde.org` вместо канонических
            // `www.cdn77.com,www.phpmyadmin.net`. Встроенная строка остаётся
            // запасной на случай, когда Moat не отвечает вовсе.
            .put("transports", JSONArray(listOf("webtunnel", "obfs4", "snowflake")))
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

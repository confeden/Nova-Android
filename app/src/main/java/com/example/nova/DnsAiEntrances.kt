package com.example.nova

import android.content.Context
import android.util.AtomicFile
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Входы в DNS-AI: `dns.dns-ai.ru`, `cf.dns-ai.ru` и личные ссылки `<имя>.dns-ai.ru/<токен>`.
 *
 * То же, что в Nova PC (`resources/nova_dns_ai.py`, D61-D67), без winws: роль его стратегий
 * здесь у `via=obfs` ядра (D39) — ClientHello режется на куски.
 *
 * * `dns.dns-ai.ru` — наш прямой адрес, DoH и DoT, GET и POST;
 * * `cf.dns-ai.ru` — за Cloudflare, только DoH (GET и POST). Адреса CDN плавают, поэтому
 *   зашить их нельзя: служба разворачивает имя мимо VPN при подключении, последний ответивший
 *   набор лежит в файле на случай, когда имя не развернулось;
 * * личная ссылка (`ru.dns-ai.ru/<токен>`) — путь и есть токен устройства, CDN отвечает на GET
 *   и режет POST кодом 413, поэтому ядро ходит к ней только по GET (токен `m=get` в апстриме).
 *   Токен — секрет: в журнал и на экран он целиком не попадает.
 *
 * Порядок как на ПК: сначала все входы напрямую (с обфускацией), и только если не ответил ни
 * один — те же входы через туннель ([routeChain]).
 */
object DnsAiEntrances {

    const val DOMAIN = "dns-ai.ru"
    const val HOST_CF = "cf.dns-ai.ru"
    const val HOST_RU = "ru.dns-ai.ru"

    /** Имя края Яндекс-CDN за `ru.`: запасной вход для разрешения, когда само имя не развернулось. */
    const val RU_EDGE_NAME = "b3280326fa528d82.topology.gslb.yccdn.ru"

    const val CF_URL = "https://cf.dns-ai.ru/dns-query"

    /** Сообщение группы, где Витрина выдаёт личную ссылку: `https://t.me/nova_txt/<тема>/<сообщение>`. */
    const val VITRINA_WEB_LINK = "https://t.me/nova_txt/322474/375424"
    const val VITRINA_APP_LINK = "tg://resolve?domain=nova_txt&post=375424&thread=322474"

    /** Токен в ссылке: на сервере 8-32 буквы и цифры; Витрина выдаёт 13 символов `a-z2-7`. */
    private val TOKEN_RE = Regex("^[A-Za-z0-9]{8,32}$")
    private val BARE_TOKEN_RE = Regex("^[a-z2-7]{13}$")

    private const val ROUTE_TOKEN_PREFIX = "via="
    const val ROUTE_OBFS_ONLY = "via=obfsonly"
    const val ROUTE_TUNNEL = "via=tunnel"
    const val METHOD_GET = "m=get"

    fun isDnsAiHost(host: String?): Boolean {
        val name = host?.trim()?.trimEnd('.')?.lowercase().orEmpty()
        return name == DOMAIN || name.endsWith(".$DOMAIN")
    }

    /** Разобранная личная ссылка: имя входа и токен. */
    data class PersonalLink(val host: String, val token: String) {
        /** Полная форма, как её хранит правило и как ждёт ядро: `https://хост/токен`. */
        val url: String get() = "https://$host/$token"
    }

    /**
     * Личная ссылка из того, что вставил человек, либо `null`.
     *
     * Принимаются голый токен (он относится к `ru.`), `хост/токен`, `https://хост/токен`,
     * шаблон браузера `https://хост/токен{?dns}` и ссылка с `?dns=…`. Хост — любой поддомен
     * `dns-ai.ru`: «например ru.dns-ai.ru/…» — это пример, а не единственный вход.
     */
    fun parsePersonalLink(raw: String?): PersonalLink? {
        var text = raw?.trim().orEmpty()
        if (text.isEmpty() || text.any { it.isWhitespace() } || text.contains(',') || text.contains('|')) return null
        if (BARE_TOKEN_RE.matches(text)) return PersonalLink(HOST_RU, text)
        if (text.startsWith("https://", ignoreCase = true)) {
            text = text.substring(8)
        } else if (text.contains("://")) {
            return null
        }
        val cut = text.indexOfFirst { it == '{' || it == '?' || it == '#' }
        if (cut >= 0) text = text.substring(0, cut)
        val parts = text.trim('/').split('/')
        if (parts.size != 2) return null
        val host = parts[0].trim().trimEnd('.').lowercase()
        val token = parts[1].trim()
        if (host == DOMAIN || !host.endsWith(".$DOMAIN")) return null
        if (!host.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return null
        if (!TOKEN_RE.matches(token)) return null
        return PersonalLink(host, token)
    }

    /** Личная ссылка в правиле DoH (`https://хост/токен`), либо `null` для обычного адреса. */
    fun personalLinkOf(value: String?): PersonalLink? {
        val text = value?.trim().orEmpty()
        if (!text.startsWith("https://", ignoreCase = true)) return null
        return parsePersonalLink(text)
    }

    /**
     * Почему текст не принят как личная ссылка, по-русски и **без** цитаты: токен — секрет.
     * `null` — текст не похож на ссылку DNS-AI вовсе, и решает обычный разбор правила.
     */
    fun linkError(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || parsePersonalLink(text) != null) return null
        val body = text.substringAfter("://")
        val host = body.substringBefore('/').substringBefore('?').substringBefore('{').lowercase()
        if (!isDnsAiHost(host)) return null
        val path = body.substringAfter('/', "").substringBefore('?').substringBefore('{').trim('/')
        // Обычный DoH-адрес сервера: его разбирает обычный путь.
        if (path == "dns-query") return null
        return "Неверная форма личной ссылки. Ожидается https://ru.dns-ai.ru/<токен> " +
            "(можно с {?dns} на конце) или сам токен."
    }

    /** Значение правила, как его надо показывать: токен спрятан. */
    fun displayValue(value: String): String {
        val link = personalLinkOf(value) ?: return value
        return "https://${link.host}/${link.token.take(2)}…"
    }

    /** Для журнала: токена нет вовсе. */
    fun logValue(value: String): String {
        val link = personalLinkOf(value) ?: return value
        return "https://${link.host}/…"
    }

    /** Апстрим для журнала: личная ссылка без токена, остальное как есть. */
    fun logUpstream(entry: String): String {
        val target = entry.substringBefore('|')
        val link = personalLinkOf(target) ?: return entry
        return "https://${link.host}/…" + entry.substring(target.length)
    }

    /** Вход только по DoH: у CDN-хостов DoT нет, а второй целью «любого» транспорта стал бы пустой отказ. */
    fun isDohOnly(value: String?): Boolean {
        val text = value?.trim().orEmpty()
        if (personalLinkOf(text) != null) return true
        val host = text.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return host == HOST_CF
    }

    /**
     * Дописывает токены апстрима, которые зависят только от значения: личная ссылка ходит по GET.
     * Остальные апстримы возвращаются как есть.
     */
    fun methodToken(target: String): String? =
        if (personalLinkOf(target) != null) METHOD_GET else null

    // -- маршрут -----------------------------------------------------------------------

    private fun targetOf(entry: String): String = entry.substringBefore('|').trim()

    private fun hostOfTarget(entry: String): String {
        val target = targetOf(entry)
        val body = target.substringAfter("://", target)
        return body.substringBefore('/').substringBefore(':').trim('[', ']')
    }

    /** Апстрим семейства DNS-AI (любой вход, DoH или DoT). */
    fun isFamilyUpstream(entry: String): Boolean {
        val target = targetOf(entry)
        if (!target.startsWith("https://", ignoreCase = true) && !target.startsWith("tls://", ignoreCase = true)) {
            return false
        }
        return isDnsAiHost(hostOfTarget(entry))
    }

    private fun withRoute(entry: String, route: String): String =
        entry.split('|').filter { !it.trim().lowercase().startsWith(ROUTE_TOKEN_PREFIX) }
            .joinToString("|") + "|" + route

    /**
     * Цепочка апстримов для перехвата DNS: входы DNS-AI прямым путём с обфускацией, а те же
     * входы через туннель — запасом, сразу за последним из них.
     *
     * Раньше у каждого входа было `via=obfs` — прямой путь и туннель внутри одной строки; с
     * несколькими входами это значило бы «dns прямой, dns туннелем, cf прямой…», то есть VPN
     * раньше, чем второй прямой вход. Здесь порядок как на ПК (D61): пока отвечает хоть один
     * прямой вход, туннель не нужен. `via=obfsonly` отказывает сразу, пока прямой путь снят
     * после неудачи, поэтому три мёртвых входа стоят одной пробы, а не трёх таймаутов.
     *
     * Чужие апстримы остаются на своих местах. Выбор человека «через VPN» для входа
     * сохраняется: такой вход прямым не пробуется (I1).
     */
    fun routeChain(upstreams: List<String>): List<String> {
        val lastFamily = upstreams.indexOfLast(::isFamilyUpstream)
        if (lastFamily < 0) return upstreams
        val out = ArrayList<String>(upstreams.size + 4)
        val tunnelCopies = LinkedHashSet<String>()
        for ((index, entry) in upstreams.withIndex()) {
            if (!isFamilyUpstream(entry)) {
                out += entry
                continue
            }
            val userTunnel = entry.split('|').any { it.trim().equals(ROUTE_TUNNEL, ignoreCase = true) }
            if (userTunnel) {
                out += entry
            } else {
                out += withRoute(entry, ROUTE_OBFS_ONLY)
                tunnelCopies += withRoute(entry, ROUTE_TUNNEL)
            }
            if (index == lastFamily) out.addAll(tunnelCopies)
        }
        return out.distinct()
    }

    // -- адреса CDN --------------------------------------------------------------------

    private const val CACHE_FILE = "dns_ai_entrances.json"
    private val cacheLock = Any()

    /** Последний набор адресов, который отвечал, на случай когда имя не развернулось. */
    fun cachedAddresses(context: Context?, host: String): List<String> {
        val file = cacheFile(context) ?: return emptyList()
        synchronized(cacheLock) {
            val json = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
            val array = json.optJSONArray(host.lowercase()) ?: return emptyList()
            return buildList {
                for (i in 0 until array.length()) {
                    DnsRule.normalizeAddress(array.optString(i))?.let(::add)
                }
            }
        }
    }

    fun rememberAddresses(context: Context?, host: String, addresses: List<String>) {
        val file = cacheFile(context) ?: return
        val clean = addresses.mapNotNull(DnsRule::normalizeAddress).distinct().take(8)
        if (clean.isEmpty()) return
        synchronized(cacheLock) {
            val json = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: JSONObject()
            val key = host.lowercase()
            val old = json.optJSONArray(key)
            val same = old != null && old.length() == clean.size &&
                clean.indices.all { old.optString(it) == clean[it] }
            if (same) return
            json.put(key, JSONArray(clean))
            val atomic = AtomicFile(file)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = atomic.startWrite()
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (e: Exception) {
                if (stream != null) runCatching { atomic.failWrite(stream) }
                LogManager.log("DNS-AI: адреса входа не записались — ${e.message}")
            }
        }
    }

    private fun cacheFile(context: Context?): File? =
        context?.applicationContext?.filesDir?.let { File(it, CACHE_FILE) }

    // -- проверка личной ссылки --------------------------------------------------------

    enum class LinkState { ACCEPTED, REJECTED, UNVERIFIED }

    /** DNS-запрос `example.com A` с идентификатором 0x4e56, как в Nova PC. */
    private val PROBE_QUERY = byteArrayOf(
        0x4e, 0x56, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x07, 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
        'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
        0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0x00, 0x00, 0x01, 0x00, 0x01,
    )

    /**
     * Живая проверка ссылки: один настоящий DoH-запрос по GET.
     *
     * 200 с корректным ответом — принята; 404 — токен неверный или отозван; всё остальное
     * (сеть, TLS, 5xx) — не проверена: это не приговор ссылке. Блокирующий вызов, из главного
     * потока его звать нельзя (I13). Адрес в журнал не пишется.
     */
    fun checkLink(link: PersonalLink, timeoutSeconds: Long = 8): LinkState =
        checkLinkDetailed(link, timeoutSeconds).state

    /** Итог проверки: состояние и причина по-русски. Причина **без** токена: её можно показывать и писать в журнал. */
    data class LinkCheck(val state: LinkState, val detail: String, val tech: String = "") {
        /** Для экрана и журнала: причина и, если есть, техническая метка в скобках. */
        fun describe(translate: (String) -> String = { it }): String =
            translate(detail) + if (tech.isNotEmpty()) " ($tech)" else ""
    }

    /**
     * То же, что [checkLink], но с причиной отказа: экран «Персональный DNS-AI» показывает её
     * человеку, а не просто «не вышло». Токен в причину не попадает.
     */
    fun checkLinkDetailed(link: PersonalLink, timeoutSeconds: Long = 8): LinkCheck {
        val encoded = android.util.Base64.encodeToString(
            PROBE_QUERY,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        )
        return try {
            val client = OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .build()
            val request = Request.Builder()
                .url("https://${link.host}/${link.token}?dns=$encoded")
                .header("Accept", "application/dns-message")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = if (response.isSuccessful) response.body?.bytes() ?: ByteArray(0) else ByteArray(0)
                classifyResponse(response.code, body)
            }
        } catch (e: Exception) {
            describeFailure(e, link)
        }
    }

    /** Разбор ответа на пробный запрос: HTTP-код и тело. Отдельно от сети — чтобы проверять без неё. */
    fun classifyResponse(code: Int, body: ByteArray): LinkCheck = when {
        code == 404 ->
            LinkCheck(LinkState.REJECTED, "сервер не знает эту ссылку: она неверна или отозвана", "HTTP 404")
        code == 401 || code == 403 ->
            LinkCheck(LinkState.REJECTED, "сервер отказал в доступе по этой ссылке", "HTTP $code")
        code == 413 ->
            LinkCheck(LinkState.UNVERIFIED, "сервер не принимает POST, нужен GET", "HTTP 413")
        code !in 200..299 ->
            LinkCheck(LinkState.UNVERIFIED, "сервер ответил ошибкой", "HTTP $code")
        body.size < 12 ->
            LinkCheck(LinkState.UNVERIFIED, "ответ не похож на DNS-ответ", "${body.size} байт")
        // Идентификатор, бит ответа и RCODE=0: HTTP 200 сам по себе не доказательство.
        body[0] != PROBE_QUERY[0] || body[1] != PROBE_QUERY[1] || (body[2].toInt() and 0x80) == 0 ->
            LinkCheck(LinkState.UNVERIFIED, "ответ не похож на DNS-ответ: другой идентификатор или это не ответ")
        (body[3].toInt() and 0x0f) != 0 ->
            LinkCheck(LinkState.UNVERIFIED, "DNS ответил кодом ошибки", "RCODE ${body[3].toInt() and 0x0f}")
        else -> LinkCheck(LinkState.ACCEPTED, "сервер ответил")
    }

    private fun describeFailure(e: Exception, link: PersonalLink): LinkCheck {
        val name = e.javaClass.simpleName
        val detail = when (e) {
            is java.net.UnknownHostException -> "имя сервера не нашлось: нет сети или DNS не отвечает"
            is java.net.SocketTimeoutException, is java.io.InterruptedIOException ->
                "нет ответа: сервер или сеть не отвечают"
            is javax.net.ssl.SSLPeerUnverifiedException -> "сертификат сервера не прошёл проверку"
            is javax.net.ssl.SSLException -> "не удалось установить защищённое соединение (TLS)"
            is java.net.ConnectException -> "соединение не установилось: порт закрыт или сеть режет доступ"
            else -> "сеть не дала выполнить запрос"
        }
        // Токен в текст ошибки библиотеки мог попасть вместе с адресом: вырезаем.
        val note = e.message.orEmpty().replace(link.token, "…").take(60)
        val tech = if (e is javax.net.ssl.SSLException && note.isNotEmpty()) "$name: $note" else name
        return LinkCheck(LinkState.UNVERIFIED, detail, tech)
    }

    // -- подпись в списке --------------------------------------------------------------

    /**
     * Короткая подпись встроенного входа: `https://cf.dns-ai.ru/dns-query` → `cf.dns-ai.ru`.
     * Для остальных значений — `null`, и решает обычная подпись.
     */
    fun shortLabel(value: String?): String? {
        val text = value?.trim().orEmpty()
        return if (text.equals(CF_URL, ignoreCase = true)) HOST_CF else null
    }
}

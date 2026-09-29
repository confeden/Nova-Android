package com.example.nova

import org.json.JSONObject

/**
 * Импорт точки входа DNS по ссылке — с распознаванием формата по содержимому.
 *
 * ## Зачем распознавать, а не спрашивать
 *
 * Человек получает ссылку в чате и вставляет её. Спрашивать у него «а это
 * stormdns или slipnet?» значит требовать знания, которого у него нет и не
 * должно быть: формат написан в самой ссылке. Поэтому здесь один вход —
 * [parse] — и он сам решает, что ему дали.
 *
 * ## Три семейства, и они правда разные
 *
 * | Ссылка | Что внутри | Чем это поднимать |
 * |---|---|---|
 * | `slipnet://` | обычный **dnstt**: зона, 32-байтный ключ Noise, список резолверов | клиент vaydns, он уже в ядре |
 * | `stormdns://`, `cottendns://` | схема `whitedns.profile`: домен и **общий симметричный ключ** | движок семейства MasterDNS/StormDNS/CottenDNS — отдельный протокол |
 *
 * Разница не косметическая. У dnstt ключ **публичный** и рукопожатие Noise
 * server-authenticated; у семейства CottenDNS ключ **общий секрет**, а
 * `encryption_method` выбирает шифр (1 — XOR, 2 — ChaCha20, 3-5 — AES-GCM).
 * Одним клиентом это не поднять, поэтому в APK лежат оба: dnstt живёт в ядре,
 * семейство CottenDNS поднимает `libstormdns.so` отдельным процессом
 * ([DnsTunnelProcess]). Разбор говорит, какой из них нужен, а не делает вид,
 * что всё одно и то же.
 *
 * ## Почему разбор чистый
 *
 * Ни `Context`, ни ввода-вывода: ссылка на входе, разобранное на выходе. Формат
 * приходит из чужих рук, и проверять его надо тестом на десятке образцов, а не
 * вставкой в поле на устройстве.
 */
object DnsProfileImport {

    /** Каким движком поднимается точка входа. */
    enum class Engine {
        /** dnstt: публичный ключ сервера, рукопожатие Noise. Умеем. */
        DNSTT,

        /** MasterDNS / StormDNS / CottenDNS: общий секрет и выбранный шифр. */
        COTTEN,
    }

    /** SSH за туннелем: у `dnstt_ssh` дальний конец — не SOCKS, а SSH-сервер. */
    data class InnerSsh(val user: String, val port: Int, val authMethod: String)

    data class Profile(
        val engine: Engine,
        /** Имя из ссылки — его показывают человеку, а не адрес. */
        val name: String,
        /** dnstt: зона туннеля. CottenDNS: домен сервера. */
        val domain: String,
        /** dnstt: публичный ключ (64 hex). CottenDNS: общий секрет. */
        val key: String,
        /** Только CottenDNS: 1 — XOR, 2 — ChaCha20, 3-5 — AES-GCM. */
        val encryptionMethod: Int = 0,
        /** Как назвался формат: `slipnet`, `stormdns`, `cottendns`. */
        val flavour: String = "",
        val resolvers: List<String> = emptyList(),
        val recordType: String = "",
        val maxQnameLen: Int = 0,
        val innerSsh: InnerSsh? = null,
    ) {
        /** Умеет ли приложение поднять это прямо сейчас. */
        val supported: Boolean get() = innerSsh == null && when (engine) {
            Engine.DNSTT -> true
            Engine.COTTEN -> StormDnsConfig.supportsMethod(encryptionMethod)
        }
    }

    sealed class Result {
        /** Разобранная точка входа. */
        data class Imported(val profile: Profile) : Result()

        /** В ссылке не профиль, а список резолверов. */
        data class Resolvers(val addresses: List<String>) : Result()

        /**
         * Не разобрали. Причина обязана быть человеческой: «не удалось
         * импортировать» без объяснения — это тупик, из которого человеку некуда
         * идти (I4).
         */
        data class Failure(val reason: String) : Result()
    }

    private val SCHEMES = listOf("slipnet", "stormdns", "cottendns", "masterdns", "whitedns")

    /** Потолок разбираемого текста: 512 тысяч знаков — это десятки тысяч адресов. */
    const val MAX_INPUT_CHARS = 512 * 1024

    /** Разбирает всё, что человек мог вставить: ссылку, голый base64 или список адресов. */
    fun parse(raw: String?): Result {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Result.Failure("Пустая строка — вставлять нечего.")
        // Ссылка профиля — это сотни знаков, подборка адресов — тысячи. Всё, что
        // больше, разбирать незачем, а вот развернуть его из base64 в память
        // втрое — очень даже есть чем поплатиться.
        if (text.length > MAX_INPUT_CHARS) {
            return Result.Failure("Слишком длинный текст: ${text.length} знаков, а разбирается не больше $MAX_INPUT_CHARS.")
        }

        val scheme = text.substringBefore("://", "").lowercase().takeIf { it.isNotEmpty() && text.contains("://") }
        val body = if (scheme != null) text.substringAfter("://") else text

        if (scheme == null || scheme in SCHEMES) {
            // Тело пробуется и как есть, и без одинокой косой черты на конце.
            // Косую дописывают мессенджеры, оформляя ссылку, — но `/` это
            // законный знак base64 (значение 63), и снимать его всегда значило
            // бы портить последний байт каждой шестьдесят четвёртой ссылки.
            // Порядок здесь и есть правило: целое тело важнее подрезанного.
            val candidates = if (body.endsWith("/")) listOf(body, body.dropLast(1)) else listOf(body)
            for (candidate in candidates) {
                val decoded = decodeBase64(candidate) ?: continue
                val json = tryJsonProfile(decoded, scheme)
                if (json != null) return json
                val pipe = trySlipnet(decoded, scheme)
                if (pipe != null) return pipe
            }
        }

        // Список пробуется **раньше** отказа по схеме: подборка резолверов DoH
        // начинается с `https://`, и отказ по имени схемы отказывал бы ей целиком
        // — при том что сам адрес `https://…` здесь законный ([looksLikeResolver],
        // [DnsProfileList.toEndpoint] делает из него резолвер DoH).
        val resolvers = tryResolverList(text)
        if (resolvers != null) return Result.Resolvers(resolvers)

        if (scheme != null && scheme !in SCHEMES) {
            // Незнакомая схема — это не «попробуем угадать»: угаданный чужой
            // формат тихо создаст нерабочую точку входа.
            return Result.Failure("Неизвестный вид ссылки «$scheme://». Поддерживаются slipnet, stormdns, cottendns.")
        }

        return Result.Failure(
            if (scheme != null) {
                "Ссылка «$scheme://» не разобралась: содержимое не похоже ни на профиль, ни на список адресов."
            } else {
                "Не похоже ни на ссылку профиля, ни на список адресов DNS."
            }
        )
    }

    /**
     * Base64 в обоих алфавитах и без добивки.
     *
     * Разбор свой, а не `android.util.Base64` и не `java.util.Base64`: первый не
     * существует в юнит-тестах, второй появился только на API 26, а minSdk здесь
     * 24. Чужие ссылки приходят и в обычном алфавите, и в URL-safe, и без `=` на
     * конце — в присланных образцах встретились все три случая.
     */
    fun decodeBase64(value: String): String? {
        val cleaned = buildString {
            for (ch in value.trim()) {
                val skip = ch.code == 10 || ch.code == 13 || ch.code == 32 ||
                    ch.code == 9 || ch.code == 61
                if (!skip) append(ch)
            }
        }
        if (cleaned.isEmpty()) return null

        val out = java.io.ByteArrayOutputStream(cleaned.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (ch in cleaned) {
            val v = base64Value(ch)
            if (v < 0) return null
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        val bytes = out.toByteArray()
        if (bytes.isEmpty()) return null
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull() ?: return null
        // Двоичный мусор отсекается сразу: оба наших формата текстовые, и
        // «разобралось во что-то» без этой проверки означало бы, что любой
        // случайный base64 становится профилем.
        if (text.any { it.code in 0..8 || it.code == 11 || it.code in 14..31 }) return null
        return text
    }

    private fun base64Value(ch: Char): Int = when (ch) {
        in 'A'..'Z' -> ch - 'A'
        in 'a'..'z' -> ch - 'a' + 26
        in '0'..'9' -> ch - '0' + 52
        '+', '-' -> 62
        '/', '_' -> 63
        else -> -1
    }

    /** Схема `whitedns.profile` — её отдают stormdns и cottendns. */
    private fun tryJsonProfile(decoded: String, scheme: String?): Result? {
        if (!decoded.trimStart().startsWith("{")) return null
        val json = runCatching { JSONObject(decoded) }.getOrNull() ?: return null
        val profile = json.optJSONObject("profile") ?: return null
        val server = profile.optJSONObject("server") ?: return Result.Failure(
            "В профиле нет раздела server — без адреса сервера подключаться некуда."
        )
        val domain = server.optString("domain").trim()
        if (domain.isEmpty()) return Result.Failure("В профиле не указан домен сервера.")
        val key = server.optString("encryption_key").trim()
        if (key.isEmpty()) return Result.Failure("В профиле нет ключа шифрования.")
        val flavour = json.optString("import_type").trim().lowercase().ifEmpty { scheme.orEmpty() }

        val domains = ArrayList<String>()
        server.optJSONArray("domains")?.let { array ->
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotEmpty() && value != domain) domains.add(value)
            }
        }

        return Result.Imported(
            Profile(
                engine = Engine.COTTEN,
                name = profile.optString("name").trim().ifEmpty { domain },
                domain = domain,
                key = key,
                encryptionMethod = server.optInt("encryption_method", 0),
                flavour = flavour,
                // Запасные домены того же сервера — не резолверы, но и терять их
                // нельзя: по ним движок ходит, когда основной не отвечает.
                resolvers = domains,
            )
        )
    }

    /**
     * Труба SlipNet: поля через `|`, значение каждого — по месту.
     *
     * Читаются только те места, которые подтверждены разбором настоящего
     * профиля; остальные семьдесят игнорируются намеренно. Угадывать смысл поля
     * по его виду — верный способ однажды принять чужой номер порта за длину
     * QNAME.
     */
    private fun trySlipnet(decoded: String, scheme: String?): Result? {
        if (!decoded.contains('|')) return null
        val f = decoded.split('|')
        if (f.size < SLIP_MIN_FIELDS) return null

        val mode = f.getOrNull(SLIP_MODE)?.trim().orEmpty().lowercase()
        val zone = f.getOrNull(SLIP_ZONE)?.trim().orEmpty()
        val key = f.getOrNull(SLIP_PUBKEY)?.trim().orEmpty().lowercase()

        if (!mode.startsWith("dnstt")) {
            return Result.Failure("Профиль SlipNet в режиме «$mode» — приложение умеет только dnstt.")
        }
        if (zone.isEmpty()) return Result.Failure("В профиле SlipNet нет зоны туннеля.")
        if (!isNoiseKey(key)) {
            return Result.Failure("Ключ сервера в профиле SlipNet не похож на ключ dnstt (нужны 64 шестнадцатеричных знака).")
        }

        val resolvers = f.getOrNull(SLIP_RESOLVERS).orEmpty()
            .split(',')
            .mapNotNull { entry ->
                // Запись вида `адрес:порт:флаг`; флаг нам не нужен, а порт нужен.
                val bits = entry.trim().split(':')
                when {
                    bits.size >= 2 && bits[0].isNotBlank() -> "${bits[0].trim()}:${bits[1].trim()}"
                    bits.size == 1 && bits[0].isNotBlank() -> bits[0].trim()
                    else -> null
                }
            }

        val sshUser = f.getOrNull(SLIP_SSH_USER)?.trim().orEmpty()
        val sshPort = f.getOrNull(SLIP_SSH_PORT)?.trim()?.toIntOrNull() ?: 0
        val innerSsh = if (mode == "dnstt_ssh" && sshPort in 1..65535) {
            InnerSsh(sshUser, sshPort, f.getOrNull(SLIP_SSH_AUTH)?.trim().orEmpty())
        } else {
            null
        }

        return Result.Imported(
            Profile(
                engine = Engine.DNSTT,
                name = f.getOrNull(SLIP_NAME)?.trim().orEmpty().ifEmpty { zone },
                domain = zone,
                key = key,
                flavour = scheme ?: "slipnet",
                resolvers = resolvers,
                recordType = f.getOrNull(SLIP_RECORD_TYPE)?.trim().orEmpty().lowercase(),
                maxQnameLen = f.getOrNull(SLIP_MAX_QNAME)?.trim()?.toIntOrNull() ?: 0,
                innerSsh = innerSsh,
            )
        )
    }

    /**
     * Список адресов резолверов: по одному в строке либо через запятую.
     *
     * Признаётся списком, только если **каждая** непустая строка — адрес. Иначе
     * текст, где адрес встретился случайно, молча стал бы списком из одного.
     */
    fun tryResolverList(text: String): List<String>? {
        val items = text.lineSequence()
            .map { stripComment(it) }
            .flatMap { it.split(',', ';', ' ', '\t').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (items.isEmpty()) return null
        if (items.any { !looksLikeResolver(it) }) return null
        return items.distinct()
    }

    /**
     * Снимает комментарий со строки списка.
     *
     * Подборки ходят файлами, и в файле рядом с адресом пишут, чей он. Без этого
     * одна строка `# МегаФон` целиком обесценивала бы файл: список признаётся
     * списком, только если адресом является **каждая** запись.
     *
     * `#` снимается где угодно — в адресе он не встречается. `//` снимается
     * только в начале строки: внутри живёт `https://` резолвера DoH.
     */
    private fun stripComment(line: String): String =
        if (line.trimStart().startsWith("//")) "" else line.substringBefore('#')

    /** Адрес резолвера: IPv4, IPv6 или имя, с портом или без. */
    fun looksLikeResolver(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.startsWith("https://", ignoreCase = true)) return true
        val host = when {
            trimmed.startsWith("[") -> trimmed.substringAfter('[').substringBefore(']')
            trimmed.count { it == ':' } == 1 -> trimmed.substringBefore(':')
            else -> trimmed
        }
        if (host.isEmpty()) return false
        val port = when {
            trimmed.startsWith("[") -> trimmed.substringAfter("]:", "")
            trimmed.count { it == ':' } == 1 -> trimmed.substringAfter(':')
            else -> ""
        }
        if (port.isNotEmpty() && (port.toIntOrNull() ?: 0) !in 1..65535) return false
        if (host.count { it == ':' } >= 2) return host.all { it.isDigit() || it in "abcdefABCDEF:" }
        val octets = host.split('.')
        if (octets.size == 4 && octets.all { it.toIntOrNull() in 0..255 }) return true
        return false
    }

    /** Ключ dnstt — ровно 32 байта в шестнадцатеричном виде. */
    fun isNoiseKey(value: String): Boolean =
        value.length == 64 && value.all { it in "0123456789abcdef" }

    /**
     * Почему этот профиль ещё не поднимется, если не поднимется.
     *
     * Отдельная функция, потому что причин две и они разные: чужой движок — это
     * «нужен другой протокол», а `dnstt_ssh` — «протокол тот, но за туннелем
     * SSH». Сказать «не поддерживается» на оба значит не сказать ничего.
     */
    fun unsupportedReason(profile: Profile): String? = when {
        profile.engine == Engine.COTTEN && !StormDnsConfig.supportsMethod(profile.encryptionMethod) ->
            "Профиль «${profile.flavour}» подписан шифром ${profile.encryptionMethod}, " +
                "а движок знает только 0-5 (0 — без шифрования, 1 — XOR, 2 — ChaCha20, 3-5 — AES-GCM)."
        profile.innerSsh != null ->
            "За туннелем этого профиля стоит SSH на порту ${profile.innerSsh.port}, " +
                "а не SOCKS-сервер. Клиента SSH в приложении пока нет."
        else -> null
    }

    // Места полей в трубе SlipNet — подтверждены разбором настоящего профиля.
    private const val SLIP_MODE = 1
    private const val SLIP_NAME = 2
    private const val SLIP_ZONE = 3
    private const val SLIP_RESOLVERS = 4
    private const val SLIP_PUBKEY = 11
    private const val SLIP_SSH_USER = 16
    private const val SLIP_SSH_PORT = 17
    private const val SLIP_SSH_AUTH = 23
    private const val SLIP_RECORD_TYPE = 42
    private const val SLIP_MAX_QNAME = 43
    private const val SLIP_MIN_FIELDS = 44
}

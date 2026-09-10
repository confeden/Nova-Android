package com.example.nova

import java.util.Locale

/**
 * Очистка журнала перед тем, как он куда-либо уедет.
 *
 * Журнал существует ради одного: человек присылает его владельцу, и по нему
 * разбирают отказ. Значит, у него два требования разом — в нём не должно быть
 * ничего, что указывает на самого человека, и в нём должно остаться всё, по чему
 * отличают один отказ от другого. Обе половины здесь одинаково важны: журнал,
 * вычищенный до `<hidden>`, бесполезен ровно так же, как и неотправленный.
 *
 * Через `sanitize` проходит **каждая** запись: `LogManager.record` зовёт её до
 * буфера, до файла и до системного журнала, так что необезличенной копии не
 * остаётся нигде.
 *
 * ## Адреса: скрывается хвост, а не начало
 *
 * До 1.32.2 маска стояла наоборот — `***.***.97.3`, — то есть в журнале
 * сохранялся именно тот конец адреса, который и делает его уникальным, а
 * пропадала сеть, по которой узнают провайдера и узел. Теперь наоборот: сеть
 * остаётся, хвост уходит. Для IPv4 это `188.114.97.***` (сеть /24: провайдер и
 * маршрут видны, конкретный хост — нет), для IPv6 — три группы и `::***`
 * (префикс сети виден, интерфейсная часть, которая и есть отпечаток устройства,
 * не пишется).
 *
 * ## Что намеренно **не** скрывается
 *
 * - **Служебные и локальные адреса** (`127.0.0.1`, `10/8`, `172.16/12`,
 *   `192.168/16`, `169.254/16`, `::1`, `fc00::/7`). Они ничего не говорят о
 *   человеке и одинаковы у всех, зато по ним разбирают раздачу, локальный прокси
 *   и внутренний адрес туннеля — у всех пятидесяти семян он один и тот же
 *   `172.16.0.2` (I7), и замаскированный он превратился бы в загадку.
 * - **Адрес, оканчивающийся на `.0`**, и всё, что похоже на маршрут: `0.0.0.0/0`,
 *   `128.0.0.0/1`, `255.255.255.0`. Это структура таблицы маршрутизации, а не
 *   чей-то адрес.
 * - **Известные публичные резолверы** — `1.1.1.1`, `8.8.8.8` и соседи. Спор о
 *   том, дошёл ли DNS, разбирают по имени резолвера; `8.8.8.***` в этом месте
 *   скрывает не человека, а предмет разговора.
 */
object DiagnosticLogSanitizer {

    private val macRegex =
        Regex("""(?i)\b[0-9a-f]{2}(?:[:-][0-9a-f]{2}){5}\b""")

    private val ipv4Regex =
        Regex("""\b(?:\d{1,3}\.){3}\d{1,3}\b""")

    /**
     * Кандидат в адреса IPv6: просто связка из шестнадцатеричных групп и
     * двоеточий. Решение принимает [looksLikeIpv6], а не выражение.
     *
     * Разбирать адрес выражением тут нельзя, и прежнее это показало: оно
     * обрывалось на `::` и оставляло хвост `a29f:c001` — ровно ту часть, ради
     * которой маска и нужна, — а «10:07:41» в тексте, наоборот, съедало целиком.
     */
    private val ipv6Regex =
        Regex("""(?i)(?<![0-9a-z:.])[0-9a-f]{0,4}(?::[0-9a-f]{0,4}){2,7}(?![0-9a-z:.])""")

    private val emailRegex =
        Regex("""(?i)\b[a-z0-9._%+\-]+@[a-z0-9.\-]+\.[a-z]{2,}\b""")

    /**
     * Кандидат в телефонный номер.
     *
     * Точка теперь входит в оба ограничителя: без этого длинное число перед
     * адресом склеивалось с его первым октетом — «handshake 1757520123
     * 188.114.97.3» превращалось в «handshake <phone>.114.97.3». Решение о
     * замене принимает [looksLikePhone], а не само выражение.
     */
    private val phoneRegex =
        Regex("""(?<![\w.])\+?\d[\d\-\s()]{7,}\d(?![\w.])""")

    private val keyValueRegex =
        Regex(
            """(?i)\b(token|access[_-]?token|refresh[_-]?token|license|privatekey|publickey|presharedkey|deviceid|password|passwd|secret|auth|authorization|bearer|ssid|bssid|mac|peerendpoint|endpoint|reserved|cookie|email|phone|package|pkg|username|user|user[_-]?name|account)\b(\s*[=:]\s*)([^,\n\r; ]+)"""
        )

    private val keyLineRegex =
        Regex(
            """(?im)^(\s*(?:PrivateKey|PublicKey|PresharedKey|Address|DNS|Endpoint|Reserved|License|DeviceId)\s*=\s*)(.+)$"""
        )

    private val bearerRegex =
        Regex("""(?i)\bBearer\s+[A-Za-z0-9._\-+/=]+\b""")

    // Логин с паролем в ссылке: так задаётся релей API SurfEasy. Без этого правила
    // почтовое правило ниже съедало бы только часть строки, оставляя логин на виду.
    private val urlCredentialsRegex =
        Regex("""(?i)\b([a-z][a-z0-9+.\-]*://)[^/\s@]+@""")

    private val ssidQuotedRegex =
        Regex("""(?i)\b(SSID|BSSID)\b(\s*[:=]\s*)(\"[^\"]*\"|'[^']*'|[^,\n\r]+)""")

    /**
     * Резолверы, которые остаются в журнале целиком.
     *
     * Список закрытый и короткий намеренно: это не «доверенные адреса», а ровно
     * те, что человек и так видит в настройках DNS. Свой резолвер пользователя
     * сюда не попадает и маскируется, как любой другой внешний адрес.
     */
    private val publicResolvers = setOf(
        "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9", "149.112.112.112",
        "77.88.8.8", "77.88.8.1", "94.140.14.14", "94.140.15.15",
        "208.67.222.222", "208.67.220.220", "4.2.2.1", "74.82.42.42",
    )

    private val publicResolversV6 = setOf(
        "2606:4700:4700::1111", "2606:4700:4700::1001",
        "2001:4860:4860::8888", "2001:4860:4860::8844",
        "2620:fe::fe", "2a02:6b8::feed:0ff",
    )

    fun sanitize(raw: String?): String {
        if (raw.isNullOrBlank()) return raw.orEmpty()

        val withoutUrlCredentials = urlCredentialsRegex.replace(raw) { match ->
            "${match.groupValues[1]}<hidden>@"
        }

        var value = withoutUrlCredentials

        value = keyLineRegex.replace(value) { match ->
            "${match.groupValues[1]}<hidden>"
        }

        value = keyValueRegex.replace(value) { match ->
            val key = match.groupValues[1].lowercase(Locale.US)
            val separator = match.groupValues[2]
            "${match.groupValues[1]}$separator${replacementForKey(key)}"
        }

        value = ssidQuotedRegex.replace(value) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}<hidden>"
        }

        value = bearerRegex.replace(value, "Bearer <hidden>")
        value = emailRegex.replace(value, "<email>")

        // Адреса маскируются **до** телефонов, и порядок тут не вкусовой.
        //
        // В правиле телефона класс символов включает пробел, поэтому длинное
        // число перед адресом съедало и первый октет: строка
        // «handshake 1757520123 188.114.97.3:2408» превращалась в
        // «handshake <phone>.114.97.3:2408», а остаток `.114.97.3` под правило
        // адреса уже не подходил и уезжал в журнал как есть. Метки времени рядом
        // с адресом — самая частая пара в этом файле, так что случай не редкий.
        value = ipv4Regex.replace(value) { match ->
            maskIpv4(match.value)
        }

        value = ipv6Regex.replace(value) { match ->
            maskIpv6(match.value)
        }

        value = phoneRegex.replace(value) { match ->
            if (looksLikePhone(match.value)) "<phone>" else match.value
        }
        value = macRegex.replace(value, "<mac>")

        return value
    }

    /**
     * IPv4: сеть остаётся, хост уходит.
     *
     * Порт при этом не трогается и остаётся за пределами совпадения — по паре
     * «сеть плюс порт» встроенные профили по-прежнему отличимы друг от друга, а
     * это половина разбора отказов WARP.
     */
    private fun maskIpv4(ip: String): String {
        val parts = ip.split(".")
        if (parts.size != 4) return "***.***.***.***"

        val octets = parts.map { it.toIntOrNull() ?: -1 }
        if (octets.any { it !in 0..255 }) return ip

        if (ip in publicResolvers) return ip
        // Адрес сети или маршрут — маскировать нечего.
        if (octets[3] == 0) return ip
        if (isLocalIpv4(octets)) return ip

        return "${parts[0]}.${parts[1]}.${parts[2]}.***"
    }

    /**
     * Отличает номер от голого числа.
     *
     * Метка времени и дата — это те же десять-тринадцать цифр с разделителями, и
     * слепое правило превращало их в `<phone>`, отнимая у журнала как раз то, по
     * чему события сопоставляют: строка tun2proxy приезжала как
     * `[<phone>:52:37 INFO tun2proxy]`. Номером считается либо запись с «+» на
     * одиннадцать-пятнадцать цифр, либо одиннадцать цифр, начинающихся с 7 или 8.
     * Само приложение таких чисел не пишет, а пользовательский номер выглядит
     * ровно так.
     */
    private fun looksLikePhone(value: String): Boolean {
        val digits = value.count { it.isDigit() }
        if (value.startsWith("+")) return digits in 11..15
        // Без «плюса» номером считается только российская запись: одиннадцать цифр,
        // начинающихся с 7 или 8. Отметка времени в секундах — десять цифр, в
        // миллисекундах — тринадцать, дата «2026-09-10 23» — те же десять; ни одна
        // под это не подходит, и журнал их сохраняет.
        val bare = value.filter { it.isDigit() }
        return bare.length == 11 && (bare[0] == '7' || bare[0] == '8')
    }

    private fun isLocalIpv4(octets: List<Int>): Boolean = when {
        octets[0] == 127 -> true
        octets[0] == 10 -> true
        octets[0] == 172 && octets[1] in 16..31 -> true
        octets[0] == 192 && octets[1] == 168 -> true
        octets[0] == 169 && octets[1] == 254 -> true
        else -> false
    }

    /**
     * IPv6: три группы префикса и `::***`.
     *
     * Три группы — это /48: провайдер и его блок видны, а интерфейсная часть,
     * по которой устройство узнают между сессиями, не пишется. Локальные адреса
     * (`::1`, `fc00::/7`) остаются целиком, а вот `fe80::` — нет: у него в
     * хвосте бывает идентификатор, выведенный из MAC.
     */
    private fun maskIpv6(ip: String): String {
        val lower = ip.lowercase(Locale.US)
        if (!looksLikeIpv6(lower)) return ip
        if (lower in publicResolversV6) return ip
        if (lower == "::1" || lower == "::") return ip
        // ULA (`fc00::/7`) — такой же локальный адрес, как 10/8 у IPv4.
        if (lower.startsWith("fc") || lower.startsWith("fd")) return ip

        val head = lower.substringBefore("::").split(":").filter { it.isNotEmpty() }
        if (head.isEmpty()) return "***"

        return head.take(3).joinToString(":") + "::***"
    }

    /**
     * Отличает адрес от времени и от чего угодно ещё с двоеточиями.
     *
     * Признак ровно один и он формальный: либо в строке есть `::`, либо групп
     * ровно восемь. «10:07:41» не проходит ни по одному, а `fe80::1` и полная
     * запись — проходят.
     */
    private fun looksLikeIpv6(value: String): Boolean {
        if (value.count { it == ':' } < 2) return false
        if (value.contains(":::")) return false

        val groups = value.split(":")
        if (groups.any { it.length > 4 }) return false
        if (groups.any { group -> group.any { it !in "0123456789abcdef" } }) return false

        return value.contains("::") || groups.size == 8
    }

    private fun replacementForKey(key: String): String {
        return when (key) {
            "ssid" -> "<ssid>"
            "bssid" -> "<bssid>"
            "mac" -> "<mac>"
            "package", "pkg" -> "<package>"
            "peerendpoint", "endpoint" -> "<endpoint>"
            "email" -> "<email>"
            "phone" -> "<phone>"
            else -> "<hidden>"
        }
    }
}

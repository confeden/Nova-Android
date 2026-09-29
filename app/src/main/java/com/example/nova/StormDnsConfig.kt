package com.example.nova

/**
 * Конфигурация встроенного клиента DNS-туннеля семейства MasterDNS/StormDNS/CottenDNS.
 *
 * ## Почему один клиент на три формата
 *
 * Замер 2026-09-20 на живых точках выхода: клиент **StormDNS** поднимает и
 * сервер StormDNS (ссылка `stormdns://`), и сервер CottenDNS (ссылка
 * `cottendns://`) — одинаково, 6 несущих из 6 и тот же MTU, что у родного
 * клиента CottenDNS. Обратное неверно: клиент CottenDNS против сервера
 * StormDNS отверг все несущие тринадцать заходов подряд. Поэтому в APK лежит
 * один клиент, и это StormDNS: он покрывает оба вида ссылок и вдвое меньше.
 *
 * ## Почему конфигурация — текст, а не флаги
 *
 * У клиента есть флаги на каждую настройку, но их сотни, и список зависит от
 * версии. Файл `client_config.toml` — его собственный формат, который он и
 * обещает читать; всё, чего мы не написали, остаётся его умолчанием. Так
 * обновление движка не превращает пропавший флаг в молчаливый отказ старта.
 *
 * Объект чистый: ни `Context`, ни диска — текст на входе, текст на выходе.
 * Проверяется тестом, а не подключением на устройстве.
 */
object StormDnsConfig {

    /** Типы записей, которые умеет клиент. Остальное — молча его умолчание. */
    private val RECORD_TYPES = setOf("TXT", "NS", "CNAME", "SRV", "ROTATE")

    /**
     * Шифры, которые понимает клиент: 0 — без шифрования, 1 — XOR, 2 — ChaCha20,
     * 3-5 — AES-GCM на 128/192/256.
     */
    const val METHOD_MIN = 0
    const val METHOD_MAX = 5

    /** Нижние пределы MTU, см. [clientConfig]. */
    const val MIN_UPLOAD_MTU = 60
    const val MIN_DOWNLOAD_MTU = 200

    /** Поддерживаем ли шифр, которым подписан профиль. */
    fun supportsMethod(method: Int): Boolean = method in METHOD_MIN..METHOD_MAX

    /**
     * Текст `client_config.toml` для одного профиля.
     *
     * Пишется только то, что действительно наше решение:
     *
     * - `STARTUP_MODE = "resolvers"` — перебор начинается со списка, который мы
     *   дали. Режим «из логов» быстрее, но он читает прошлые сессии, а у нас
     *   список несущих меняется с сетью: на Wi-Fi одни, на сотовой другие.
     * - `LOG_TO_FILE = false` — иначе каждая сессия пишет свой файл в наш
     *   каталог, и чистить их некому.
     * - `STATS_REPORT_INTERVAL_SECONDS = 0` — строка про скорость раз в десять
     *   секунд не несёт события и вытеснила бы события из журнала (I30).
     */
    fun clientConfig(
        profile: DnsProfile,
        listenPort: Int,
    ): String {
        val record = profile.recordType.trim().uppercase().takeIf { it in RECORD_TYPES } ?: "TXT"
        val method = if (supportsMethod(profile.encryptionMethod)) profile.encryptionMethod else 1
        return buildString {
            appendLine("# Собрано Nova, правки перезапишутся при следующем подключении.")
            appendLine("DOMAINS = [${quote(profile.domain.trim())}]")
            appendLine("DATA_ENCRYPTION_METHOD = $method")
            appendLine("ENCRYPTION_KEY = ${quote(profile.key.trim())}")
            appendLine("DNS_QUERY_TYPE = ${quote(record)}")
            appendLine("PROTOCOL_TYPE = \"SOCKS5\"")
            appendLine("LISTEN_IP = \"127.0.0.1\"")
            appendLine("LISTEN_PORT = $listenPort")
            appendLine("SOCKS5_AUTH = false")
            appendLine("STARTUP_MODE = \"resolvers\"")
            appendLine("LOG_TO_FILE = false")
            appendLine("STATS_REPORT_INTERVAL_SECONDS = 0")
            // Нижние пределы размера — наши, а не движка (100 вверх, 1000 вниз):
            // с ними отбраковывался каждый российский резолвер. Замер на МегаФоне
            // 2026-09-29: операторские, НСДИ и Cloudflare отдают 650-936 Б вниз
            // и 84-111 Б вверх — меньше умолчаний, а под белым списком других
            // несущих нет. Верх не трогаем: его находит сам перебор MTU.
            appendLine("MIN_UPLOAD_MTU = $MIN_UPLOAD_MTU")
            appendLine("MIN_DOWNLOAD_MTU = $MIN_DOWNLOAD_MTU")
        }
    }

    /**
     * Текст `client_resolvers.txt` — по одной несущей на строку.
     *
     * DoH сюда не попадает: у этого клиента несущая только UDP, и строка
     * `https://…` стала бы именем хоста, который он попробует резолвить. Пусть
     * лучше её не будет, чем она будет молча неправильной.
     */
    fun resolverFile(resolvers: List<DnsTunnelResolver>): String {
        val seen = LinkedHashSet<String>()
        resolvers.forEach { resolver ->
            if (resolver.type != DnsTunnelResolver.TYPE_UDP) return@forEach
            val addr = resolver.addr.trim()
            if (addr.isNotEmpty()) seen.add(DnsTunnelConfig.withDefaultPort(addr))
        }
        return seen.joinToString("\n", postfix = if (seen.isEmpty()) "" else "\n")
    }

    /**
     * Порт из строки готовности клиента.
     *
     * Клиент печатает её, когда локальный SOCKS5 уже принимает соединения, —
     * это единственный честный признак «туннель поднялся». Ждать вместо неё
     * таймаут значило бы отдать `tun2proxy` порт, которого ещё нет.
     *
     * Строка выглядит так:
     * `🚀 SOCKS5 Proxy server is listening on 127.0.0.1:18000`, и у неё есть
     * близнец для `[::1]` — его мы пропускаем, потому что слушателя мы просили
     * на IPv4 и дальше ходим именно туда.
     *
     * @return порт либо 0, если строка не про это.
     */
    fun listenerPort(line: String): Int {
        if (!line.contains("Proxy server is listening on")) return 0
        val addr = line.substringAfterLast("listening on").trim()
        if (!addr.startsWith("127.0.0.1:")) return 0
        return addr.substringAfterLast(':').trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 0
    }

    /**
     * Провал, названный словами, — или пусто, если строка не про провал.
     *
     * Причина обязана дойти до человека: «не удалось подключиться» без неё
     * одинаково выглядит и при мёртвой точке выхода, и при неверном ключе, и
     * при операторе, который режет UDP/53 (I4).
     */
    fun failureReason(line: String): String = when {
        line.contains("No valid connections found") ->
            "ни одна несущая не дошла до точки выхода"
        line.contains("invalid encryption key") || line.contains("encryption key length") ->
            "ключ профиля не подошёл движку"
        line.contains("no resolvers") ->
            "список несущих пуст"
        else -> ""
    }

    /**
     * Значение в кавычках — с вырезанными управляющими символами.
     *
     * Экранировать кавычку и обратную косую мало. Домен и общий секрет приходят
     * из **чужой ссылки**, которую человек взял в канале, а перевод строки
     * внутри них закрывает строку TOML и всё, что идёт после, становится новыми
     * настройками движка: чужой `LISTEN_IP`, чужой `LOG_DIR`. Поэтому всё ниже
     * пробела выбрасывается: в имени домена и в ключе таких символов не бывает,
     * а спорить с чужим форматом о том, какие из них «безопасны», — проигрышная
     * игра.
     */
    private fun quote(value: String): String {
        val sanitized = value.filter { it.code >= 0x20 && it.code != 0x7F }
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        return "\"" + sanitized + "\""
    }
}

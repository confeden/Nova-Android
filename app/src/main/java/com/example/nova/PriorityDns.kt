package com.example.nova

/**
 * Приоритетный резолвер владельца: `dns.dns-ai.ru` по DNS-over-HTTPS.
 *
 * Стоит перед Xbox DNS и, как и он, ходит **мимо туннеля**. Разница в том, что у
 * Xbox нет шифрования: перехват DNS в ядре резолвит имена наружу по защищённому
 * сокету, то есть провайдер видит весь список посещённых доменов даже при включённом
 * Proton или WARP. DoH оставляет тот же маршрут, но наружу видно только TLS-сессию с
 * самим резолвером, а не имена. Открытые резолверы остаются запасными.
 *
 * Незашифрованной версии у этого сервера нет, поэтому в `VpnService.Builder`
 * (`addDnsServer` принимает только IP) он не попадает — только в перехват ядра и в
 * bootstrap opera-proxy, где формат URL поддерживается.
 *
 * Имя надо во что-то развернуть до подъёма туннеля: системный резолвер внутри VPN
 * ведёт обратно в этот же перехват. Адреса резолвятся по нижележащей сети и кладутся
 * в апстрим строкой `url|ip1,ip2` — ядро разбирает её само.
 */
object PriorityDns {

    const val HOST = "dns.dns-ai.ru"
    const val DOH_URL = "https://dns.dns-ai.ru/dns-query"

    /**
     * Тот же сервер по DoT.
     *
     * Второй транспорт нужен потому, что 443 и 853 блокируют **порознь**: замер на
     * МегаФон LTE 2026-09-05 показал живой 853 при недоступном 443, и наоборот на
     * другой сети. Какой из них быстрее сегодня — решает [preferredTransport].
     */
    const val DOT_URL = "tls://dns.dns-ai.ru"

    /**
     * Узлы, за которыми стоит имя: msk3 и spb1.
     *
     * Это и bootstrap для DoH/DoT, и последний рубеж, когда имя не разворачивается.
     * Держать список одним значением обязательно: раньше здесь стоял `217.60.10.20`
     * (msk2), который выведен из ротации и не отвечает, — то есть половина «последнего
     * рубежа» гарантированно тратила таймаут. Порядок соответствует A-записи
     * `dns.dns-ai.ru`, проверенной 2026-09-05.
     */
    val KNOWN_ADDRESSES = listOf(
        "192.144.59.14",
        "186.246.49.127",
    )

    /**
     * Адреса на случай, когда имя не разворачивается: DNS провайдера может его
     * не знать или подменять.
     *
     * Это именно последний рубеж, а не значение по умолчанию: сначала всегда идёт
     * свежий резолв, и в журнал пишется, каким из двух путей адреса получены. Список
     * захардкожен и потому стареет молча — при смене адресов сервера рабочим остаётся
     * только свежий резолв (снято 2026-08-30).
     */
    private val LAST_RESORT_ADDRESSES = KNOWN_ADDRESSES

    private const val SUCCESS_TTL_MS = 10 * 60 * 1000L

    /**
     * Неудачу помним недолго и отдельно от удачи.
     *
     * Один общий TTL здесь не годится: `establishTunnelInterface` вызывается на
     * каждую попытку подключения, а их в обходе бывает полсотни. Без кэша неудачи
     * каждая попытка платила бы полный таймаут резолва — это ровно тот класс потерь,
     * из-за которого обход по заблокированным хостам занимал минуты.
     */
    private const val FAILURE_TTL_MS = 60 * 1000L

    private val lock = Any()
    private var cachedAddresses: List<String> = emptyList()
    private var cachedNetworkKey: String = ""
    private var cachedAtMs: Long = 0L
    private var cachedFromLastResort: Boolean = false

    /**
     * Разворачивает имя резолвера, если кэш устарел, и возвращает адреса.
     *
     * @param networkKey подпись нижележащей сети: смена сети обнуляет кэш, потому что
     * у нового провайдера тот же вопрос может иметь другой ответ.
     * @param resolver резолв **мимо VPN** — обязателен, системный резолвер внутри
     * туннеля вернул бы нас в этот же перехват.
     */
    fun addresses(
        networkKey: String,
        resolver: (String) -> List<String>,
        logger: (String) -> Unit,
    ): List<String> {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val ttl = if (cachedAddresses.isEmpty()) FAILURE_TTL_MS else SUCCESS_TTL_MS
            if (cachedNetworkKey == networkKey && cachedAtMs != 0L && now - cachedAtMs < ttl) {
                return cachedAddresses
            }
        }

        val resolved = runCatching { resolver(HOST) }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val fromLastResort = resolved.isEmpty()
        val effective = if (fromLastResort) LAST_RESORT_ADDRESSES else resolved

        val shouldLog = synchronized(lock) {
            val changed = cachedAddresses != effective ||
                cachedFromLastResort != fromLastResort ||
                cachedNetworkKey != networkKey
            cachedAddresses = effective
            cachedNetworkKey = networkKey
            cachedAtMs = System.currentTimeMillis()
            cachedFromLastResort = fromLastResort
            changed
        }
        if (shouldLog) {
            logger(
                if (fromLastResort) {
                    "Приоритетный DNS $HOST не развернулся мимо VPN — берём запасные адреса " +
                        "(${effective.joinToString(",")}). Если сервер сменил адрес, DoH не поднимется " +
                        "и резолвинг уйдёт на открытый запасной."
                } else {
                    "Приоритетный DNS $HOST развёрнут мимо VPN: ${effective.joinToString(",")}"
                }
            )
        }
        return effective
    }

    /**
     * Строка апстрима для перехвата DNS в ядре: `https://.../dns-query|ip1|ip2`.
     *
     * Разделитель `|`, а не запятая: весь список апстримов уходит в ядро одной
     * строкой через запятую, и адрес с запятой внутри развалил бы её надвое.
     *
     * Без адресов возвращает пустую строку: DoH без bootstrap ядро развернуть не
     * сможет, а тихо подставленный URL стоил бы таймаута на каждом запросе.
     */
    fun interceptUpstream(addresses: List<String>): String {
        val bootstrap = addresses.filter { it.isNotBlank() && !it.contains('|') && !it.contains(',') }
        if (bootstrap.isEmpty()) return ""
        return "$DOH_URL|${bootstrap.joinToString("|")}"
    }

    /** Сбрасывает кэш: сеанс закончился, следующий начнётся со свежего резолва. */
    fun reset() {
        synchronized(lock) {
            cachedAddresses = emptyList()
            cachedNetworkKey = ""
            cachedAtMs = 0L
            cachedFromLastResort = false
        }
        synchronized(hostLock) { hostCache.clear() }
        synchronized(transportLock) {
            cachedTransportDoh = true
            cachedTransportNetworkKey = ""
            cachedTransportAtMs = 0L
        }
    }

    // -- какой транспорт до нашего резолвера быстрее -------------------------

    private val transportLock = Any()
    private var cachedTransportDoh: Boolean = true
    private var cachedTransportNetworkKey: String = ""
    private var cachedTransportAtMs: Long = 0L

    /**
     * DoH или DoT — что быстрее отвечает на этой сети.
     *
     * Меряется **время установления TCP-соединения** к 443 и 853, а не полный
     * запрос: полный обмен стоит дороже, а вопрос стоит ровно один — «какой порт
     * здесь вообще жив и отвечает быстрее». Порт, к которому соединение не
     * встаёт, проигрывает по определению.
     *
     * Результат живёт с тем же сроком, что и адреса: `establishTunnelInterface`
     * вызывается на каждую попытку подключения, а их в обходе бывает полсотни, и
     * незакэшированная гонка добавляла бы свою цену пятьдесят раз подряд.
     *
     * @param probe замер задержки TCP в миллисекундах; `-1` — не отвечает.
     * @return true — впереди DoH, false — DoT.
     */
    fun preferredTransportIsDoh(
        networkKey: String,
        addresses: List<String>,
        probe: (String, Int) -> Int,
        logger: (String) -> Unit,
    ): Boolean {
        val now = System.currentTimeMillis()
        synchronized(transportLock) {
            if (cachedTransportNetworkKey == networkKey &&
                cachedTransportAtMs != 0L &&
                now - cachedTransportAtMs < SUCCESS_TTL_MS
            ) {
                return cachedTransportDoh
            }
        }
        val target = addresses.firstOrNull { it.isNotBlank() } ?: HOST
        val dohMs = runCatching { probe(target, 443) }.getOrDefault(-1)
        val dotMs = runCatching { probe(target, 853) }.getOrDefault(-1)
        // Ничей отказ не считается победой другого: если не отвечают оба, остаётся
        // умолчание DoH, потому что 443 сливается с обычным вебом и режется точечно,
        // а 853 однозначно называет протокол и режется целиком.
        val doh = when {
            dohMs >= 0 && dotMs < 0 -> true
            dotMs >= 0 && dohMs < 0 -> false
            dohMs >= 0 && dotMs >= 0 -> dohMs <= dotMs
            else -> true
        }
        val changed = synchronized(transportLock) {
            val previous = cachedTransportDoh
            val previousKey = cachedTransportNetworkKey
            cachedTransportDoh = doh
            cachedTransportNetworkKey = networkKey
            cachedTransportAtMs = System.currentTimeMillis()
            previous != doh || previousKey != networkKey
        }
        if (changed) {
            logger(
                "Наш DNS: быстрее ${if (doh) "DoH" else "DoT"} " +
                    "(443 → ${if (dohMs >= 0) "$dohMs мс" else "нет ответа"}, " +
                    "853 → ${if (dotMs >= 0) "$dotMs мс" else "нет ответа"})."
            )
        }
        return doh
    }

    // -- произвольные хосты пользовательских правил --------------------------

    private class HostEntry(val addresses: List<String>, val networkKey: String, val atMs: Long)

    private val hostLock = Any()
    private val hostCache = HashMap<String, HostEntry>()

    /**
     * То же самое для любого имени из пользовательских правил DNS.
     *
     * Кэш отдельный и по имени: правил может быть несколько, а
     * `establishTunnelInterface` вызывается на каждую попытку подключения — их в
     * обходе бывает полсотни. Без кэша каждая попытка платила бы полный таймаут
     * резолва на каждое правило.
     *
     * Тот же раздельный TTL, что и у [addresses], и по той же причине: неудача
     * должна протухать быстро, удача — держаться.
     */
    fun addressesForHost(
        host: String,
        networkKey: String,
        resolver: (String) -> List<String>,
        logger: (String) -> Unit,
    ): List<String> {
        val name = host.trim()
        if (name.isEmpty()) return emptyList()
        if (name.equals(HOST, ignoreCase = true)) {
            return addresses(networkKey, resolver, logger)
        }
        val now = System.currentTimeMillis()
        synchronized(hostLock) {
            val entry = hostCache[name]
            if (entry != null && entry.networkKey == networkKey) {
                val ttl = if (entry.addresses.isEmpty()) FAILURE_TTL_MS else SUCCESS_TTL_MS
                if (now - entry.atMs < ttl) return entry.addresses
            }
        }
        val resolved = runCatching { resolver(name) }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val shouldLog = synchronized(hostLock) {
            val previous = hostCache[name]
            hostCache[name] = HostEntry(resolved, networkKey, System.currentTimeMillis())
            previous == null || previous.addresses != resolved || previous.networkKey != networkKey
        }
        if (shouldLog && resolved.isEmpty()) {
            // Молчать нельзя (I4): без адресов ядро не дозвонится до этого
            // резолвера, и цепочка молча уедет на следующее правило.
            logger("DNS-правило $name не развернулось мимо VPN — идём к следующему в списке.")
        }
        return resolved
    }
}

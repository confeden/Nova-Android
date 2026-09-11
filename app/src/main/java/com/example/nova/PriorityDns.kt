package com.example.nova

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
        synchronized(transportLock) { transportCache.clear() }
    }

    // -- какой транспорт до резолвера быстрее --------------------------------

    /** Имя правила и адреса, по которым до него дозваниваться на замере. */
    class TransportProbeTarget(val host: String, val addresses: List<String>)

    /**
     * Предел на **всю пачку** замеров, а не на каждый хост.
     *
     * Считать по одному нельзя: `userDnsPlan` зовётся из
     * `establishTunnelInterface`, то есть на каждую попытку подключения, а их в
     * обходе бывает полсотни. Шесть правил «любой» по два соединения с пределом
     * полторы секунды — это до восемнадцати секунд на попытку, и заплачены они
     * были бы ровно за порядок строк в цепочке.
     *
     * Поэтому замеры идут разом, а не подряд, и через этот срок обрываются. Кто
     * не успел — идёт в цепочку в порядке «сначала DoH»: обе цели в ней всё
     * равно есть, и ответит та, что жива. Пропущенный замер стоит порядка, но
     * не правильности.
     */
    const val TRANSPORT_PROBE_BUDGET_MS = 1_200L

    /**
     * Потолок потоков на пачку.
     *
     * Соединения ставятся параллельно и по портам тоже: последовательные 443 и
     * 853 у одного хоста съели бы весь бюджет вдвоём, если первый порт мёртв, —
     * и живой второй остался бы неизмеренным.
     */
    private const val TRANSPORT_PROBE_THREADS = 12

    private class TransportEntry(
        val doh: Boolean,
        /** Ответил ли хоть один порт. Неответ помним недолго — как и неудачу резолва. */
        val decided: Boolean,
        val networkKey: String,
        val atMs: Long,
    )

    private class TransportMeasurement(val target: TransportProbeTarget) {
        val doh = AtomicInteger(-1)
        val dot = AtomicInteger(-1)
    }

    private val transportLock = Any()
    private val transportCache = HashMap<String, TransportEntry>()

    private fun transportKey(host: String): String = host.trim().lowercase()

    private fun transportIsFresh(entry: TransportEntry?, networkKey: String, now: Long): Boolean {
        if (entry == null || entry.networkKey != networkKey) return false
        val ttl = if (entry.decided) SUCCESS_TTL_MS else FAILURE_TTL_MS
        return now - entry.atMs < ttl
    }

    /**
     * DoH или DoT — что быстрее отвечает на этой сети. **Замера не делает.**
     *
     * Мерит [warmTransportPreferences] — разом и с общим пределом; здесь только
     * читается то, что она успела намерить. Ничего не намерено — впереди идёт
     * DoH: 443 сливается с обычным вебом и режется точечно, а 853 однозначно
     * называет протокол и режется целиком.
     *
     * @return true — впереди DoH, false — DoT.
     */
    fun preferredTransportIsDoh(host: String, networkKey: String): Boolean {
        val key = transportKey(host)
        if (key.isEmpty()) return true
        val now = System.currentTimeMillis()
        val entry = synchronized(transportLock) { transportCache[key] } ?: return true
        return if (transportIsFresh(entry, networkKey, now)) entry.doh else true
    }

    /**
     * Меряет транспорт сразу у всех правил «любой» — одной пачкой.
     *
     * Меряется **время установления TCP-соединения** к 443 и 853, а не полный
     * запрос: полный обмен стоит дороже, а вопрос стоит ровно один — «какой порт
     * здесь вообще жив и отвечает быстрее». Порт, к которому соединение не
     * встаёт, проигрывает по определению.
     *
     * Результат живёт с тем же сроком, что и адреса, и по той же причине: без
     * кэша каждая из полусотни попыток подключения платила бы за пачку заново.
     *
     * @param probe замер задержки TCP в миллисекундах; `-1` — не отвечает.
     * @param budgetMs предел на всю пачку; см. [TRANSPORT_PROBE_BUDGET_MS].
     */
    fun warmTransportPreferences(
        targets: List<TransportProbeTarget>,
        networkKey: String,
        probe: (String, Int) -> Int,
        logger: (String) -> Unit,
        budgetMs: Long = TRANSPORT_PROBE_BUDGET_MS,
    ) {
        val now = System.currentTimeMillis()
        // Тёплая сеть не платит ничего: то, что уже намерено и не протухло, в
        // пачку не попадает, и без единого холодного хоста мы вообще не заходим
        // ни в потоки, ни в журнал.
        val cold = LinkedHashMap<String, TransportProbeTarget>()
        for (target in targets) {
            val key = transportKey(target.host)
            if (key.isEmpty() || cold.containsKey(key)) continue
            val fresh = synchronized(transportLock) {
                transportIsFresh(transportCache[key], networkKey, now)
            }
            if (!fresh) cold[key] = target
        }
        if (cold.isEmpty()) return

        val pending = cold.values.map { TransportMeasurement(it) }
        val pool = runCatching {
            Executors.newFixedThreadPool(
                (pending.size * 2).coerceAtMost(TRANSPORT_PROBE_THREADS)
            ) { runnable -> Thread(runnable, "nova-dns-transport").apply { isDaemon = true } }
        }.getOrNull()
        if (pool == null) {
            // Молчать нельзя (I4): порядок в цепочке останется неизмеренным, и
            // это надо видеть в журнале, а не выводить из времени подключения.
            logger("DNS-правила: замер транспорта не запустился — цепочка идёт в порядке «сначала DoH».")
            return
        }
        val startedAt = System.currentTimeMillis()
        runCatching {
            pending.forEach { measurement ->
                val address = measurement.target.addresses.firstOrNull { it.isNotBlank() }
                    ?: measurement.target.host
                pool.execute {
                    measurement.doh.set(runCatching { probe(address, 443) }.getOrDefault(-1))
                }
                pool.execute {
                    measurement.dot.set(runCatching { probe(address, 853) }.getOrDefault(-1))
                }
            }
            pool.shutdown()
            pool.awaitTermination(budgetMs, TimeUnit.MILLISECONDS)
        }
        // Опоздавших не ждём: соединение, не вставшее за общий срок, для порядка
        // в цепочке уже проиграло, а держать за него весь подъём туннеля — та
        // самая цена, ради которой пачка и заведена.
        runCatching { pool.shutdownNow() }

        val settledAt = System.currentTimeMillis()
        var timedOut = 0
        for (measurement in pending) {
            val dohMs = measurement.doh.get()
            val dotMs = measurement.dot.get()
            val decided = dohMs >= 0 || dotMs >= 0
            // Ничей отказ не считается победой другого: если не отвечают оба, остаётся
            // умолчание DoH, потому что 443 сливается с обычным вебом и режется точечно,
            // а 853 однозначно называет протокол и режется целиком.
            val doh = when {
                dohMs >= 0 && dotMs < 0 -> true
                dotMs >= 0 && dohMs < 0 -> false
                dohMs >= 0 && dotMs >= 0 -> dohMs <= dotMs
                else -> true
            }
            if (!decided) timedOut += 1
            val host = measurement.target.host
            val changed = synchronized(transportLock) {
                val key = transportKey(host)
                val previous = transportCache[key]
                transportCache[key] = TransportEntry(doh, decided, networkKey, settledAt)
                previous == null || previous.doh != doh || previous.networkKey != networkKey
            }
            if (!changed) continue
            val tail = "(443 → ${if (dohMs >= 0) "$dohMs мс" else "нет ответа"}, " +
                "853 → ${if (dotMs >= 0) "$dotMs мс" else "нет ответа"})."
            logger(
                if (host.equals(HOST, ignoreCase = true)) {
                    "Наш DNS: быстрее ${if (doh) "DoH" else "DoT"} $tail"
                } else {
                    "DNS-правило $host: быстрее ${if (doh) "DoH" else "DoT"} $tail"
                }
            )
        }
        // Цена пачки не должна быть невидимой: она платится на подъёме туннеля.
        logger(
            "DNS-правила: транспорт замерен у ${pending.size} хостов за " +
                "${settledAt - startedAt} мс, без ответа в срок — $timedOut " +
                "(бюджет $budgetMs мс)."
        )
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

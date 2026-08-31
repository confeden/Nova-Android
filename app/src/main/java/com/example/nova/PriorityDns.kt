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
     * Адреса на случай, когда имя не разворачивается: DNS провайдера может его
     * не знать или подменять.
     *
     * Это именно последний рубеж, а не значение по умолчанию: сначала всегда идёт
     * свежий резолв, и в журнал пишется, каким из двух путей адреса получены. Список
     * захардкожен и потому стареет молча — при смене адресов сервера рабочим остаётся
     * только свежий резолв (снято 2026-08-30).
     */
    private val LAST_RESORT_ADDRESSES = listOf(
        "186.246.49.127",
        "217.60.10.20",
    )

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
    }
}

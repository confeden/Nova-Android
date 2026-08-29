package com.example.nova

import java.util.Locale

/**
 * Какое имя подставлять в TLS-рукопожатие вместо настоящего.
 *
 * Маскировка нужна там, где рукопожатие видно провайдеру и блокировка идёт по
 * имени: MASQUE (QUIC несёт ClientHello открытым текстом — см. 3g в ROADMAP),
 * `-fake-SNI` у opera-proxy и обращения к API Cloudflare при регистрации WARP.
 * У WireGuard/AmneziaWG никакого TLS нет, и подставлять там нечего — это ровно
 * тот случай, из-за которого прошлая «маскировка» ломала туннели: имя писалось в
 * профиль и до датаплейна не доходило вовсе.
 *
 * Правила ротации задал владелец:
 *   * российские имена идут первыми, зарубежные — следом;
 *   * «белый список» (провайдер пускает только к разрешённым адресам) — только
 *     проверенные имена из `white.sni`: там, где закрыт даже 1.1.1.1, большой
 *     российский список тоже в основном недоступен, и каждое имя из него — это
 *     потраченная впустую попытка рукопожатия;
 *   * «чёрный список» (зарубежное доступно) — российские и зарубежные
 *     чередуются;
 *   * свой список пользователя, если он его задал, отменяет оба набора.
 *
 * Объект чистый: ни `Context`, ни ввода-вывода. Списки приходят снаружи, решение
 * проверяется юнит-тестами.
 */
object SniMaskPolicy {

    const val MODE_AUTO = "auto"
    const val MODE_CUSTOM = "custom"

    /** Сколько кандидатов имеет смысл держать в очереди одной попытки. */
    private const val MAX_ORDER = 64

    /**
     * Что провайдер делает с доступом.
     *
     * [UNKNOWN] — не «что-то среднее», а «мы не знаем»: решение принимается по
     * тому же правилу, что и для чёрного списка, но российские имена всё равно
     * идут первыми, поэтому ошибка в эту сторону ничего не ломает.
     */
    enum class Regime { WHITELIST, BLACKLIST, UNKNOWN }

    /**
     * @param white проверенные российские имена (`white.sni`) — их и берём первыми
     * @param russia большой российский список
     * @param global зарубежный список
     */
    data class Pools(
        val white: List<String> = emptyList(),
        val russia: List<String> = emptyList(),
        val global: List<String> = emptyList(),
    )

    /**
     * @param seed привязка к узлу: одно и то же соединение получает одно и то же имя
     * @param attempt сколько имён уже не сработало на этом узле
     * @param blockedHosts имена, которые здесь уже подводили
     */
    data class Inputs(
        val mode: String,
        val regime: Regime,
        val customHosts: List<String> = emptyList(),
        val pools: Pools = Pools(),
        val seed: Int = 0,
        val attempt: Int = 0,
        val blockedHosts: Set<String> = emptySet(),
        /**
         * Имена, которые уже поднимали сессию **на этом устройстве**, в порядке
         * убывания заслуг. Идут впереди любого набора: общий список составлен без
         * знания конкретной сети, а это знание — единственное, что про неё есть.
         *
         * Список свой у каждого транспорта: имя, проходящее в MASQUE, не обязано
         * проходить в Opera — рукопожатия у них разные и смотрит на них DPI
         * по-разному.
         */
        val preferredHosts: List<String> = emptyList(),
    )

    /** @param source откуда имя: `custom`, `white`, `russia` или `global` */
    data class Choice(val host: String, val source: String)

    /**
     * Разбирает список, введённый пользователем.
     *
     * Разделителем считаются запятая, пробел и перевод строки: человек набирает
     * «через запятую», но копирует часто из списка в столбик.
     */
    fun parseCustomList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(',', ';', '\n', '\r', ' ', '\t')
            .map(::normalizeHost)
            .filter { it.isNotBlank() }
            .distinct()
    }

    /**
     * Приводит имя к виду, годному для SNI: без схемы, пути, порта и регистра.
     *
     * Возвращает пустую строку для всего, что именем хоста не является — так
     * опечатка в своём списке не превращается в неотличимую от «имя не выбрано»
     * ошибку на рукопожатии.
     */
    fun normalizeHost(raw: String?): String {
        val host = raw.orEmpty()
            .trim()
            .lowercase(Locale.US)
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .substringBefore(':')
            .trim('.')
        if (host.isEmpty() || host.length > 253) return ""
        if (!host.contains('.')) return ""
        if (host.contains("..") || host.startsWith("-") || host.endsWith("-")) return ""
        if (!host.matches(Regex("^[a-z0-9.-]+$"))) return ""
        // Голый адрес именем не является: в SNI он бессмыслен и выдаёт подмену.
        if (host.matches(Regex("^[0-9.]+$"))) return ""
        return host
    }

    /**
     * Порядок, в котором стоит пробовать имена на этом узле.
     *
     * Список конечен и детерминирован: одно и то же [Inputs.seed] даёт один и тот
     * же порядок, поэтому повтор подключения не начинает перебор заново.
     */
    fun buildOrder(inputs: Inputs): List<String> {
        val learned = inputs.preferredHosts.map(::normalizeHost).filter { it.isNotBlank() }.distinct()
        if (inputs.mode == MODE_CUSTOM) {
            val custom = rotate(
                inputs.customHosts.map(::normalizeHost).filter { it.isNotBlank() },
                inputs.seed,
            )
            // Выученное поднимается только внутри списка пользователя: подставить
            // сюда чужое имя значило бы отменить его выбор (I1).
            return (promoteFrom(custom, learned) + custom).distinct().take(MAX_ORDER)
        }
        val white = inputs.pools.white.map(::normalizeHost).filter { it.isNotBlank() }.distinct()
        val russia = inputs.pools.russia.map(::normalizeHost).filter { it.isNotBlank() }.distinct()
        val foreign = rotate(
            inputs.pools.global.map(::normalizeHost).filter { it.isNotBlank() }.distinct(),
            inputs.seed,
        )
        if (inputs.regime == Regime.WHITELIST) {
            // Только `white`. Откат на большой российский список — лишь когда
            // проверенных имён нет вовсе: пустая очередь хуже неточной.
            val pool = rotate(white.ifEmpty { russia }, inputs.seed)
            return (promoteFrom(pool, learned) + pool).distinct().take(MAX_ORDER)
        }
        val domestic = rotate((white + russia).distinct(), inputs.seed)
        if (foreign.isEmpty()) {
            return (promoteFrom(domestic, learned) + domestic).distinct().take(MAX_ORDER)
        }
        if (domestic.isEmpty()) return (promoteFrom(foreign, learned) + foreign).distinct().take(MAX_ORDER)
        // Выученные имена ищутся во **всём** наборе, а не в уже обрезанной очереди.
        // Пока подъём делался после чередования, имя искалось среди 64 кандидатов,
        // а взято оно из пятисот с лишним — и на устройстве не поднималось ни разу,
        // хотя записывалось исправно.
        val front = promoteFrom(domestic + foreign, learned)
        // Чередование начинается с российского имени: «сначала российские, потом
        // зарубежные» — правило владельца, и на сети с белым списком ошибиться в
        // эту сторону дешевле.
        val interleaved = ArrayList<String>(MAX_ORDER)
        var i = 0
        while (interleaved.size < MAX_ORDER && (i < domestic.size || i < foreign.size)) {
            domestic.getOrNull(i)?.let(interleaved::add)
            if (interleaved.size >= MAX_ORDER) break
            foreign.getOrNull(i)?.let(interleaved::add)
            i++
        }
        return (front + interleaved).distinct().take(MAX_ORDER)
    }

    /** Выученные имена, встречающиеся в наборе, в порядке их заслуг. */
    private fun promoteFrom(pool: List<String>, learned: List<String>): List<String> {
        if (learned.isEmpty() || pool.isEmpty()) return emptyList()
        val allowed = pool.toHashSet()
        return learned.filter { it in allowed }
    }


    /**
     * Имя для текущей попытки или `null`, если подставлять нечего.
     *
     * `null` — честный ответ «маскировать нечем»: вызывающая сторона оставит
     * настоящее имя, а не подставит пустую строку, на которой рукопожатие
     * выглядит подозрительнее любого имени.
     */
    fun pick(inputs: Inputs): Choice? {
        val order = buildOrder(inputs).filterNot { it in inputs.blockedHosts }
        if (order.isEmpty()) return null
        val index = ((inputs.attempt % order.size) + order.size) % order.size
        val host = order[index]
        return Choice(host, sourceOf(host, inputs))
    }

    private fun sourceOf(host: String, inputs: Inputs): String = when {
        inputs.mode == MODE_CUSTOM -> "custom"
        inputs.pools.white.any { normalizeHost(it) == host } -> "white"
        inputs.pools.global.any { normalizeHost(it) == host } -> "global"
        else -> "russia"
    }

    private fun rotate(hosts: List<String>, seed: Int): List<String> {
        if (hosts.size <= 1) return hosts
        val offset = ((seed % hosts.size) + hosts.size) % hosts.size
        if (offset == 0) return hosts
        return hosts.subList(offset, hosts.size) + hosts.subList(0, offset)
    }

    fun normalizeMode(value: String?): String =
        if (value?.trim()?.lowercase(Locale.US) == MODE_CUSTOM) MODE_CUSTOM else MODE_AUTO
}

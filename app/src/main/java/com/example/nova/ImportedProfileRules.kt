package com.example.nova

/**
 * Правила списка импортированных профилей — чистая логика без Android.
 *
 * Здесь три решения, которые должны быть одинаковы везде: когда замер устарел,
 * в каком порядке показывать профили по задержке и какие профили исключены из
 * выбора. Всё остальное (кэш, сеть, экран) живёт рядом и опирается на эти функции.
 */
object ImportedProfileRules {

    /** Кэш замера старше этого возраста перемеряется при открытии списка. */
    const val STALE_AFTER_MS = 60L * 60L * 1000L

    /** Срок одного замера; по истечении профиль помечается как не ответивший. */
    const val PROBE_TIMEOUT_MS = 3000L

    /** Сколько замеров идёт одновременно. */
    const val MAX_PARALLEL_PROBES = 8

    /** Минимальный интервал между пересортировками списка во время замеров. */
    const val SORT_INTERVAL_MS = 1000L

    /** Замер сделан, узел не ответил вовсе. */
    const val TIMED_OUT = -1

    /**
     * Замер для этого профиля не делается: UDP-узел по IP-адресу, DNS-время здесь не
     * означает задержку до узла. Показывается как «—», как и отсутствие замера.
     */
    const val NOT_MEASURABLE = -2

    /**
     * Нужен ли новый замер. Нет записи, нет времени или время в будущем (часы
     * перевели) — замер нужен. Ровно час — ещё свежо: «старше часа» значит строго больше.
     */
    fun isStale(measuredAt: Long?, now: Long): Boolean {
        if (measuredAt == null || measuredAt <= 0L) return true
        val age = now - measuredAt
        return age < 0L || age > STALE_AFTER_MS
    }

    /**
     * Порядок по задержке: сначала известные от меньшей к большей, затем не ответившие
     * по таймауту, затем без замера. При равной задержке порядок исходного списка
     * сохраняется (sortedBy устойчив), поэтому строки не прыгают без причины.
     */
    fun <T> sortByLatency(items: List<T>, pingOf: (T) -> Int?): List<T> =
        items.sortedBy { item -> latencyKey(pingOf(item)) }

    internal fun latencyKey(ping: Int?): Long = when {
        ping == null || ping == NOT_MEASURABLE -> Long.MAX_VALUE
        ping == TIMED_OUT -> Long.MAX_VALUE - 1
        ping < 0 -> Long.MAX_VALUE
        else -> ping.toLong()
    }

    /**
     * Исключает отключённые профили из любой выборки для подключения.
     *
     * Единственная точка, где отключение применяется: списки, которые читают
     * подключение, перебор и адаптация, пропускают результат через неё. Список для
     * показа (где отключённые видны серыми) её не использует.
     */
    fun <T> withoutDisabled(items: List<T>, idOf: (T) -> String, disabledIds: Set<String>): List<T> =
        if (disabledIds.isEmpty()) items else items.filterNot { idOf(it).trim() in disabledIds }

    /**
     * Число для заголовка списка: до тысячи — как есть, дальше — «1.2к» (одна цифра
     * после точки, не больше 9.9к), от 10к — целые тысячи «12к». [thousandSuffix] —
     * подпись тысяч из каталога: «к» по-русски, «k» по-английски.
     */
    fun formatCount(count: Int, thousandSuffix: String): String {
        if (count < 1000) return count.toString()
        if (count < 10_000) {
            val tenths = count / 100
            val whole = tenths / 10
            val fraction = tenths % 10
            return if (fraction == 0) "$whole$thousandSuffix" else "$whole.$fraction$thousandSuffix"
        }
        return "${(count + 500) / 1000}$thousandSuffix"
    }
}

/**
 * Троттлинг по времени: [delayUntilAllowed] говорит, сколько ждать до следующего
 * запуска, [markRan] фиксирует запуск. Время передаётся снаружи, поэтому тестируется.
 */
class ThrottleGate(private val intervalMs: Long) {
    private var lastRunAt: Long? = null

    /** 0 — можно запускать сейчас; иначе миллисекунды до разрешённого момента. */
    fun delayUntilAllowed(now: Long): Long {
        val last = lastRunAt ?: return 0L
        // Часы могли уйти назад: тогда ждём не больше интервала, а не вечность.
        return (intervalMs - (now - last)).coerceIn(0L, intervalMs)
    }

    fun markRan(now: Long) {
        lastRunAt = now
    }
}

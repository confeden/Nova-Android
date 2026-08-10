package com.example.nova

/**
 * Удержание сессии — сколько времени узел молчит в ответ, пока мы шлём данные.
 *
 * Зачем понадобилось. Ручная «Адаптация к условиям сети» меряла пинг и факт
 * подключения. Обе величины у плохого узла нормальные: рукопожатие проходит за
 * 40–60 мс, страницы открываются. Отличает узлы другое — обратный транспортный
 * поток пропадает, и WireGuard через 15 секунд молчания пересобирает сессию.
 * Замер 2026-08-10: два узла в одной сети и на одной сборке дали 2,0–2,5 и
 * 0,5–1,0 перерукопожатия в минуту, причём спокойный вёз **больше** трафика.
 *
 * Почему не считаем перерукопожатия прямо в окне адаптации. Таймер взводится
 * отправкой данных и срабатывает через 15 секунд молчания, а окно адаптации —
 * 20 секунд и открывается не с рукопожатия, а после подтверждения data-plane.
 * По журналу 2026-08-09 обратный поток жил после рукопожатия 1, 11, 12, 12, 15,
 * 16, 16 секунд — в двадцатисекундное окно перерукопожатие попадает примерно
 * один раз из семи. То есть у плохого узла там чаще всего честный ноль, ровно
 * такой же, как у хорошего: один бит, да ещё и почти всегда нулевой.
 *
 * Что считаем вместо этого. Пробы связности идут раз в секунду и сами по себе
 * являются отправкой данных, а успешная проба — принятым пакетом. Значит
 * **самая длинная тишина между удачными пробами** и есть тот же физический
 * признак, только измеренный двадцатью отсчётами вместо одного бита, и
 * наступающий раньше: пятисекундный провал перерукопожатия не вызовет, а
 * тишину покажет. Это добавка именно к покрытию: двенадцать неудач вразброс и
 * двенадцать подряд дают одинаковые «12/20», но описывают разные узлы.
 *
 * Тишина хранится **в миллисекундах, а не в числе неудачных проб**. Неудачная
 * проба стоит около 1,1 с сверх сна (три TCP-цели плюс HTTP), удачная —
 * десятки миллисекунд, а на слабом устройстве шаг цикла ещё и полуторный: одно
 * и то же «5 подряд» означало бы у разных узлов разное время. Рядом всегда
 * лежит знаменатель — сколько проб сделано и сколько времени окно реально
 * длилось; правило «счётчик без знаменателя врёт» в этом проекте уже стоило
 * одного ложного вывода.
 *
 * Режимы не различаются намеренно. Признак построен на пробах, а не на
 * `last_handshake_time_sec`, поэтому одинаково работает и для WireGuard, и для
 * MASQUE, у которого метка рукопожатия ставится один раз за туннель и
 * «нулевой churn» означал бы не здоровье, а отсутствие замера.
 */
object SessionHoldMetric {

    /** Окно короче этого не описывает узел: замер отбрасывается. */
    const val MIN_SPAN_MS = 12_000L

    /** Меньше этого числа проб — не выборка, а случайность. */
    const val MIN_PROBES = 5

    /**
     * Хвост окна, который в замер не берём.
     *
     * Окно удержания попытки и окно замера в адаптации равны (оба 20 с) и
     * отсчитываются от одной отметки, поэтому последние итерации сэмплера
     * приходятся на уже разбираемый туннель: `Nova.stopVPN()` уже вызван, а
     * флаг попытки ещё не снят. Проба по мёртвому туннелю падает и добавляет
     * тишину **каждому** профилю, включая здоровые, — то есть смещение бьёт
     * ровно по тем, кто окно выдержал.
     */
    const val TAIL_GUARD_MS = 2_500L

    /**
     * К этой длительности приводится тишина, чтобы окна разной длины можно было
     * складывать. Значение — типичное окно адаптации за вычетом хвоста.
     */
    const val REFERENCE_SPAN_MS = 17_500L

    /**
     * Одна одиночная неудачная проба — это около 2,1 с тишины (1,1 с сама проба
     * плюс секунда сна). Такое бывает и на здоровом узле, поэтому граница
     * спокойствия проходит выше неё.
     *
     * Ниже границы должна оставаться и разрешающая способность замера: цикл
     * спит перед пробой, поэтому даже у безупречного узла тишина равна шагу
     * опроса — секунде, а на слабом устройстве 1,6 с.
     */
    const val STEADY_MAX_STALL_MS = 3_000L

    /** Две-три неудачи подряд. Ещё не провал обратного потока, но уже не норма. */
    const val SHAKY_MAX_STALL_MS = 7_000L

    /** Столько окон храним, дальше счётчики делятся пополам — как у churn. */
    const val MAX_WINDOWS = 12

    /**
     * После этого возраста замер перестаёт участвовать: сеть у телефона
     * меняется чаще, чем накапливаются окна, и старение только по числу окон
     * оставляло бы узел заклеймённым навсегда.
     */
    const val STALE_AFTER_MS = 6L * 60L * 60L * 1000L

    /** Узел теряет обратный поток. */
    const val GRADE_LOSING = 0

    /** Замера нет или он протух. Не поощряем и не штрафуем. */
    const val GRADE_UNKNOWN = 1

    /** Тишина заметная, но короткая. */
    const val GRADE_SHAKY = 2

    /** Обратный поток не пропадает. */
    const val GRADE_STEADY = 3

    /** Потолок штрафа в числовой оценке. Ниже потолка churn (36) намеренно. */
    const val MAX_PENALTY = 24.0

    /**
     * Размер корзины, в которую сгребается порядок встроенных профилей.
     *
     * Порядок `seedOrder` приходит из прошивки и различается у всех пятидесяти
     * записей. Пока он стоял первым ключом сортировки очереди, все остальные
     * ключи — качество, пинг, удержание, числовая оценка — были недостижимы:
     * равенства по первому ключу не случалось никогда. Адаптация мерила, а
     * очередь не менялась. Корзина оставляет прошивочному порядку грубую
     * власть (первая десятка идёт раньше второй), а внутри десятки решают
     * замеры с устройства.
     */
    const val SEED_QUEUE_BUCKET = 10

    /**
     * Итог одного окна замера.
     *
     * @param probeCount сколько проб успели сделать
     * @param spanMs сколько миллисекунд окно реально длилось
     * @param worstStallMs самая длинная тишина между удачными пробами
     * @param skippedProbes итерации, где VPN-сеть не нашлась и наружу не ушло
     * ничего: тишина в них не про узел
     */
    data class Window(
        val probeCount: Int,
        val spanMs: Long,
        val worstStallMs: Long,
        val skippedProbes: Int,
    ) {
        /**
         * Показательно ли окно.
         *
         * Порог трафика в килобайтах, которым отсеиваются двухминутные окна
         * churn, здесь неприменим: пробы дают единицы килобайт против
         * тридцати двух, и любое окно адаптации было бы отброшено. Знаменатель
         * тут другой и известен по построению — сколько проб отправлено и
         * сколько времени это заняло.
         */
        val representative: Boolean
            get() = spanMs >= MIN_SPAN_MS &&
                probeCount >= MIN_PROBES &&
                skippedProbes * 2 <= probeCount

        /** Почему окно не пошло в зачёт. Пустая строка — пошло. */
        val rejectionReason: String
            get() = when {
                spanMs < MIN_SPAN_MS -> "окно ${spanMs} мс короче ${MIN_SPAN_MS} мс"
                probeCount < MIN_PROBES -> "проб ${probeCount} меньше ${MIN_PROBES}"
                skippedProbes * 2 > probeCount ->
                    "VPN-сеть не нашлась в ${skippedProbes} итерациях из ${probeCount}"
                else -> ""
            }
    }

    /**
     * Копит тишину внутри одного окна.
     *
     * Тишина считается от последней **удачной** пробы: успешная проба — это
     * принятый из туннеля пакет, то есть обратный поток жив. Отсчёт начинается
     * от открытия окна, а не от первой пробы, иначе узел, не ответивший ни
     * разу, показал бы нулевую тишину.
     */
    class Accumulator(private val startedAtMs: Long) {
        private var lastGoodAtMs = startedAtMs
        private var worstStallMs = 0L
        private var probeCount = 0
        private var skippedProbes = 0

        /** Проба завершилась. [atMs] — момент после её возврата. */
        fun note(succeeded: Boolean, atMs: Long) {
            probeCount += 1
            if (!succeeded) return
            val stallMs = (atMs - lastGoodAtMs).coerceAtLeast(0L)
            if (stallMs > worstStallMs) worstStallMs = stallMs
            lastGoodAtMs = atMs
        }

        /**
         * Итерация прошла без отправки: VPN-сеть не нашлась.
         *
         * Такую тишину узлу не приписываем — наружу не ушло ни байта, и молчал
         * не он. Но и делать вид, что окно полноценное, нельзя: счётчик
         * пропусков решает, показательно ли окно целиком.
         */
        fun noteSkipped(atMs: Long) {
            probeCount += 1
            skippedProbes += 1
            // Тишину, накопленную ДО пропуска, сначала засчитываем. Она измерена
            // настоящими неудачными пробами через живой туннель, то есть она как
            // раз про узел; забыть надо только промежуток, начинающийся с пропуска.
            // Без этой строки узел, замолчавший за десять секунд до того, как
            // пропала VPN-сеть, выглядел бы безупречным.
            val stallMs = (atMs - lastGoodAtMs).coerceAtLeast(0L)
            if (stallMs > worstStallMs) worstStallMs = stallMs
            lastGoodAtMs = atMs
        }

        /**
         * Закрывает окно. Хвостовая тишина засчитывается: узел, замолчавший на
         * пятнадцатой секунде и не ответивший до конца окна, — худший случай,
         * а не отсутствие данных.
         */
        fun finish(atMs: Long): Window {
            val trailingStallMs = (atMs - lastGoodAtMs).coerceAtLeast(0L)
            return Window(
                probeCount = probeCount,
                spanMs = (atMs - startedAtMs).coerceAtLeast(0L),
                worstStallMs = maxOf(worstStallMs, trailingStallMs),
                skippedProbes = skippedProbes,
            )
        }
    }

    /**
     * Накопленный замер узла, приведённый к [REFERENCE_SPAN_MS].
     *
     * Возвращает −1, если данных нет: ноль здесь неотличим от идеала, а
     * неизмеренный узел не должен выглядеть безупречным.
     */
    fun normalizedStallMs(windows: Int, stallMs: Long, spanMs: Long): Double {
        if (windows <= 0 || spanMs < MIN_SPAN_MS || stallMs < 0L) return -1.0
        return stallMs.toDouble() / spanMs.toDouble() * REFERENCE_SPAN_MS
    }

    /**
     * Грубая оценка удержания — то, что реально решает порядок.
     *
     * Именно грубая, а не непрерывная шкала: одно окно на профиль за прогон —
     * это одно наблюдение, и точная сортировка по нему выдала бы за сигнал то,
     * в какую минуту прогона профиль попал.
     */
    fun grade(
        windows: Int,
        stallMs: Long,
        spanMs: Long,
        checkedAtMs: Long,
        nowMs: Long,
    ): Int {
        if (checkedAtMs > 0L && nowMs - checkedAtMs > STALE_AFTER_MS) return GRADE_UNKNOWN
        val normalized = normalizedStallMs(windows, stallMs, spanMs)
        if (normalized < 0.0) return GRADE_UNKNOWN
        return when {
            normalized <= STEADY_MAX_STALL_MS -> GRADE_STEADY
            normalized <= SHAKY_MAX_STALL_MS -> GRADE_SHAKY
            else -> GRADE_LOSING
        }
    }

    /**
     * Штраф в числовой оценке узла.
     *
     * Затухает по возрасту замера теми же шестью часами, что и качество: у
     * churn такого затухания нет, и замер месячной давности штрафует там как
     * вчерашний — повторять эту ошибку в новом признаке незачем.
     */
    fun penalty(
        windows: Int,
        stallMs: Long,
        spanMs: Long,
        checkedAtMs: Long,
        nowMs: Long,
    ): Double {
        val normalized = normalizedStallMs(windows, stallMs, spanMs)
        if (normalized <= STEADY_MAX_STALL_MS) return 0.0
        val freshness = if (checkedAtMs <= 0L) {
            1.0
        } else {
            val ageMs = (nowMs - checkedAtMs).coerceAtLeast(0L)
            1.0 - ageMs.coerceAtMost(STALE_AFTER_MS).toDouble() / STALE_AFTER_MS.toDouble()
        }
        if (freshness <= 0.0) return 0.0
        val excessSeconds = (normalized - STEADY_MAX_STALL_MS) / 1000.0
        return (excessSeconds * 4.0).coerceAtMost(MAX_PENALTY) * freshness
    }

    /** Накопление окна поверх сохранённого замера, со старением пополам. */
    data class Accumulated(val windows: Int, val stallMs: Long, val spanMs: Long)

    fun accumulate(
        previousWindows: Int,
        previousStallMs: Long,
        previousSpanMs: Long,
        window: Window,
    ): Accumulated {
        val safeWindows = previousWindows.coerceAtLeast(0)
        val safeStallMs = previousStallMs.coerceAtLeast(0L)
        val safeSpanMs = previousSpanMs.coerceAtLeast(0L)
        // Старение: держим порядка MAX_WINDOWS последних замеров. Без него узел,
        // испортившийся месяц назад, не отмоется никогда, а сеть у телефона
        // меняется чаще.
        return if (safeWindows >= MAX_WINDOWS) {
            Accumulated(
                windows = safeWindows / 2 + 1,
                stallMs = safeStallMs / 2 + window.worstStallMs,
                spanMs = safeSpanMs / 2 + window.spanMs,
            )
        } else {
            Accumulated(
                windows = safeWindows + 1,
                stallMs = safeStallMs + window.worstStallMs,
                spanMs = safeSpanMs + window.spanMs,
            )
        }
    }

    /**
     * Корзина прошивочного порядка встроенного профиля.
     *
     * `Int.MAX_VALUE` означает «профиль не из прошивки» и остаётся собой:
     * такие идут после встроенных, как и раньше.
     */
    fun bundledSeedQueueBucket(seedOrder: Int, bucketSize: Int = SEED_QUEUE_BUCKET): Int {
        if (seedOrder == Int.MAX_VALUE) return Int.MAX_VALUE
        if (seedOrder < 0) return 0
        val safeBucketSize = bucketSize.coerceAtLeast(1)
        return seedOrder / safeBucketSize
    }
}

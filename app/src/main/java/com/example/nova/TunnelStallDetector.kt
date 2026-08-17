package com.example.nova

/**
 * Детектор замершего data-plane: туннель поднят, мы шлём, обратно не идёт ничего.
 *
 * Зачем понадобился. Замер на Mi A1 (Ростелеком, проводная сеть, встроенный сид
 * `8.47.69.6:945`, 20 минут): десять минут пинга дали 600/600 без единой потери,
 * перерукопожатия шли ровно раз в 120 с — протокольный минимум WireGuard. Но в
 * отдельном прогоне 5% потерь оказались **не рассеянными, а одним сплошным
 * провалом 15,3 с** (пакеты 188–202), закрытым строкой ядра `Retrying handshake
 * because we stopped hearing back after 15 seconds`, ответ на которое пришёл за
 * **34 мс**. То есть восстановление стоит 34 мс, а обнаружение — 15 000 мс, и
 * весь видимый пользователю обрыв — это цена ожидания, а не цена починки.
 *
 * Пятнадцать секунд заложены в сам протокол: `KeepaliveTimeout` (10 с) плюс
 * `RekeyTimeout` (5 с), и таймер взводится **отправкой данных**, а не keepalive.
 * Трогать константы форка нельзя — они общие для всех туннелей и завязаны на
 * `MaxTimerHandshakes`. Поэтому смотрим за потоком снаружи и просим ядро начать
 * рукопожатие раньше, чем это сделает его собственный таймер.
 *
 * Почему это дёшево. Цикл `vpnConsistencyRunnable` уже тикает каждые 4,5 с при
 * подключении и уже читает `rx_bytes`/`tx_bytes`/`last_handshake_time_sec` через
 * `readTunnelStats`. Новых источников данных не нужно — нужен вывод из тех, что
 * уже собираются и до сих пор только логировались.
 *
 * ГЛАВНАЯ ЛОВУШКА, из-за которой наивная проверка «rx вырос» не работает:
 * amneziawg-go засчитывает байты **рукопожатия** в тот же `rx_bytes`
 * (`device/receive.go:392` для initiation и `:423` для response). Ответ на
 * рукопожатие — 92 байта, keepalive — 32. То есть мёртвый data-plane, который
 * каждые 15 с пересобирает сессию, исправно двигает `rx_bytes` и выглядит живым.
 * Поэтому решает не факт роста, а **порог в байтах** выше протокольного шума.
 *
 * Вторая ловушка: молчащий туннель честно не получает ничего, и это здоровье, а
 * не отказ. Поэтому окно засчитывается только когда мы сами реально отправляли
 * ([MIN_TX_BYTES]); окна простоя не копятся, а сбрасывают накопитель.
 *
 * Третья: `elapsedRealtime` идёт во сне, и проспанный промежуток выглядел бы как
 * длинная тишина узла. Время берётся из `uptimeMillis`, а слишком большой разрыв
 * между тиками ([MAX_GAP_MS]) означает «за нами не наблюдали» и сбрасывает
 * накопитель, а не обвиняет узел.
 */
class TunnelStallDetector(
    private val minTxBytes: Long = MIN_TX_BYTES,
    private val minRxBytes: Long = MIN_RX_BYTES,
    private val triggerMs: Long = TRIGGER_MS,
    private val rearmMs: Long = REARM_MS,
    private val maxUnhelpfulForces: Int = MAX_UNHELPFUL_FORCES,
) {

    /**
     * Снимок счётчиков туннеля. Все три величины уже отдаёт `readTunnelStats`.
     *
     * @param uptimeMs `SystemClock.uptimeMillis()` — время без учёта сна
     * @param rxBytes накопительный `rx_bytes` пира
     * @param txBytes накопительный `tx_bytes` пира
     */
    data class Sample(
        val uptimeMs: Long,
        val rxBytes: Long,
        val txBytes: Long,
    )

    enum class State {
        /** Окно ничего не говорит об узле: первый замер, разрыв наблюдения, перезапуск счётчиков. */
        NOT_INDICATIVE,

        /** Обратный поток есть. */
        FLOWING,

        /** Мы шлём, обратно тихо, но порога срабатывания тишина ещё не набрала. */
        SUSPECTED,

        /** Тишина набрала порог — пора просить рукопожатие. */
        STALLED,
    }

    data class Outcome(
        val state: State,
        /** Сколько миллисекунд подряд длится тишина под нашей отправкой. */
        val stalledForMs: Long,
        val deltaTxBytes: Long,
        val deltaRxBytes: Long,
        val windowMs: Long,
        /** Готовая для журнала причина — почему окно решено именно так. */
        val reason: String,
        /**
         * Просить ли рукопожатие. Отличается от самого срабатывания: когда
         * бюджет исчерпан, провал по-прежнему фиксируется и попадает в журнал,
         * но ядро больше не дёргаем.
         */
        val shouldForceHandshake: Boolean = false,
        /** Первое срабатывание после исчерпания бюджета — повод сказать это вслух один раз. */
        val forceBudgetJustExhausted: Boolean = false,
    )

    private var previous: Sample? = null
    private var stalledMs = 0L
    private var mutedUntilUptimeMs = 0L

    /**
     * Сколько форсированных рукопожатий подряд не вернули поток.
     *
     * Замер на Mi A1 (Ростелеком, узел `8.47.69.6:945`): рукопожатие проходило
     * за 37 мс **каждый** раз, и ни одно из двадцати пяти не вернуло обратный
     * поток — 83,5% потерь за десять минут. Значит бывает поломка, которой
     * пересборка сессии не лечит, и долбить её бесконечно бессмысленно: путь
     * менять надо, а не ключи. Сбрасывается, как только поток вернулся.
     */
    private var unhelpfulForces = 0
    private var budgetExhaustionAnnounced = false

    /** Сколько раз детектор срабатывал за жизнь текущего туннеля. */
    var triggerCount = 0
        private set

    /** Правда, когда форсирование признано бесполезным для текущего туннеля. */
    val forceBudgetExhausted: Boolean get() = unhelpfulForces >= maxUnhelpfulForces

    /**
     * Сбрасывает наблюдение. Обязателен при каждой смене туннеля: счётчики
     * нового устройства начинаются с нуля, и разница со старыми была бы
     * отрицательной или бессмысленно большой.
     */
    fun reset() {
        previous = null
        stalledMs = 0L
        mutedUntilUptimeMs = 0L
        triggerCount = 0
        unhelpfulForces = 0
        budgetExhaustionAnnounced = false
    }

    fun observe(sample: Sample): Outcome {
        val prev = previous
        previous = sample

        if (prev == null) {
            return notIndicative(0L, "первый замер")
        }

        val windowMs = sample.uptimeMs - prev.uptimeMs
        if (windowMs <= 0L) {
            stalledMs = 0L
            return notIndicative(windowMs, "часы не двигались")
        }
        if (windowMs > MAX_GAP_MS) {
            stalledMs = 0L
            return notIndicative(windowMs, "перерыв в наблюдении ${windowMs / 1000} с")
        }

        val deltaTx = sample.txBytes - prev.txBytes
        val deltaRx = sample.rxBytes - prev.rxBytes
        if (deltaTx < 0L || deltaRx < 0L) {
            // Счётчики пира начались заново — это новый туннель, а не отказ старого.
            stalledMs = 0L
            return notIndicative(windowMs, "счётчики туннеля перезапущены")
        }

        if (deltaTx < minTxBytes) {
            // Мы почти ничего не отправляли: тишина в ответ ничего не доказывает.
            // Копить её нельзя — иначе спящий телефон обвинит здоровый узел.
            stalledMs = 0L
            return Outcome(
                state = State.NOT_INDICATIVE,
                stalledForMs = 0L,
                deltaTxBytes = deltaTx,
                deltaRxBytes = deltaRx,
                windowMs = windowMs,
                reason = "отправлено $deltaTx Б за окно — меньше порога $minTxBytes Б",
            )
        }

        if (deltaRx >= minRxBytes) {
            stalledMs = 0L
            // Поток вернулся — предыдущие форсирования засчитываем как
            // сработавшие, бюджет открывается заново.
            unhelpfulForces = 0
            return Outcome(
                state = State.FLOWING,
                stalledForMs = 0L,
                deltaTxBytes = deltaTx,
                deltaRxBytes = deltaRx,
                windowMs = windowMs,
                reason = "обратный поток идёт",
            )
        }

        stalledMs += windowMs

        // Порог не набран — только наблюдаем. Вызывающая сторона по этому
        // состоянию учащает опрос, чтобы поймать момент раньше своего же шага.
        if (stalledMs < triggerMs) {
            return Outcome(
                state = State.SUSPECTED,
                stalledForMs = stalledMs,
                deltaTxBytes = deltaTx,
                deltaRxBytes = deltaRx,
                windowMs = windowMs,
                reason = "тишина ${stalledMs} мс при отправке $deltaTx Б",
            )
        }

        // Заглушка после срабатывания: ядро само ограничивает рукопожатия
        // интервалом RekeyTimeout, но долбить его каждый тик всё равно незачем —
        // и в журнале это выглядело бы как отказ, а не как одно событие.
        if (sample.uptimeMs < mutedUntilUptimeMs) {
            return Outcome(
                state = State.SUSPECTED,
                stalledForMs = stalledMs,
                deltaTxBytes = deltaTx,
                deltaRxBytes = deltaRx,
                windowMs = windowMs,
                reason = "тишина ${stalledMs} мс, но рукопожатие уже запрошено",
            )
        }

        mutedUntilUptimeMs = sample.uptimeMs + rearmMs
        triggerCount += 1
        val fired = stalledMs
        stalledMs = 0L
        val budgetLeft = unhelpfulForces < maxUnhelpfulForces
        // Об исчерпании бюджета говорим ровно один раз за туннель: повтор в
        // журнале читался бы как новый отказ, а это одно и то же состояние.
        val justExhausted = !budgetLeft && !budgetExhaustionAnnounced
        if (justExhausted) {
            budgetExhaustionAnnounced = true
        }
        if (budgetLeft) {
            // Считаем форсирование бесполезным заранее. Если поток вернётся,
            // ближайшее живое окно обнулит счётчик — то есть польза
            // подтверждается фактом, а не намерением.
            unhelpfulForces += 1
        }
        return Outcome(
            state = State.STALLED,
            stalledForMs = fired,
            deltaTxBytes = deltaTx,
            deltaRxBytes = deltaRx,
            windowMs = windowMs,
            reason = "получено $deltaRx Б при отправленных $deltaTx Б за $fired мс",
            shouldForceHandshake = budgetLeft,
            forceBudgetJustExhausted = justExhausted,
        )
    }

    private fun notIndicative(windowMs: Long, reason: String) = Outcome(
        state = State.NOT_INDICATIVE,
        stalledForMs = 0L,
        deltaTxBytes = 0L,
        deltaRxBytes = 0L,
        windowMs = windowMs,
        reason = reason,
    )

    companion object {
        /**
         * Ниже этого за окно считаем, что мы не отправляли, и окно не судит узел.
         *
         * Верхнюю границу задаёт измеренный случай: во время провала работал
         * только пинг раз в секунду, около 84 Б на пакет, то есть примерно 378 Б
         * за окно 4,5 с. Порог должен быть заметно ниже, иначе ровно тот отказ,
         * ради которого всё сделано, не будет замечен. Нижнюю границу задаёт
         * простой: `PersistentKeepalive = 5` даёт около 32 Б за окно, и такие
         * окна судить нельзя. 256 Б лежит между ними с запасом в обе стороны.
         */
        const val MIN_TX_BYTES = 256L

        /**
         * Столько байт за окно означает, что обратный поток жив.
         *
         * Обязан быть выше протокольного шума: ответ на рукопожатие — 92 Б,
         * keepalive — 32 Б, и оба идут даже по мёртвому data-plane. Порог ниже
         * сотни означал бы «пересобираем сессию каждые 15 с, значит здоровы».
         */
        const val MIN_RX_BYTES = 256L

        /**
         * Столько накопленной тишины — и просим рукопожатие.
         *
         * Смысл имеет только значение заметно меньше пятнадцати секунд ядра,
         * иначе детектор опоздает к собственному таймеру WireGuard. Восемь
         * секунд при шаге наблюдения 1,5 с в подозрении дают срабатывание
         * примерно на восьмой–девятой секунде провала вместо пятнадцатой.
         */
        const val TRIGGER_MS = 8_000L

        /**
         * Пауза между срабатываниями. Меньше `RekeyTimeout` ядра (5 с) смысла не
         * имеет: там запрос всё равно превратится в no-op.
         *
         * Восемь секунд, а не пятнадцать, потому что за первым срабатыванием
         * теперь стоит не только рукопожатие, но и уход с узла (3i). При паузе в
         * пятнадцать секунд решение о переходе принималось бы почти через
         * полминуты после начала провала — половина этого времени была бы
         * ожиданием ради ожидания.
         */
        const val REARM_MS = 8_000L

        /**
         * Разрыв между тиками больше этого означает, что цикл не работал —
         * телефон спал или процесс был занят. Это не тишина узла.
         */
        const val MAX_GAP_MS = 30_000L

        /**
         * Столько бесполезных форсирований подряд — и перестаём просить.
         *
         * Замер, из-за которого бюджет появился: узел `8.47.69.6:945` дал 83,5%
         * потерь за десять минут, при этом **каждое** из двадцати пяти
         * рукопожатий проходило за 37 мс и ни одно не вернуло поток. Такую
         * поломку пересборка сессии не лечит — лечит смена узла.
         *
         * Ровно одно: рукопожатие стоит десятки миллисекунд и в другом замере
         * вылечило восемь провалов из восьми, поэтому попробовать его стоит
         * всегда. Но если оно не помогло, второе и третье не помогут тем более —
         * а каждая лишняя попытка это ещё восемь секунд, которые пользователь
         * сидит без связи вместо перехода на живой узел.
         */
        const val MAX_UNHELPFUL_FORCES = 1
    }
}

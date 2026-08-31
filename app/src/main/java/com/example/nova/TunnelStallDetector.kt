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
 *
 * Четвёртая, из-за которой детектор до этого почти не срабатывал на живом телефоне:
 * **пороги в байтах нельзя мерить окном переменной длины**. Оба они выведены для
 * шага 4,5 с (378 Б отправки за окно в измеренном провале), а при подозрении шаг
 * учащается до 1,5 с — те же 84 Б/с дают тогда 126 Б за окно, то есть ниже порога
 * [MIN_TX_BYTES]. Окно объявлялось несудящим, накопленная тишина обнулялась,
 * подозрение снималось, шаг возвращался к 4,5 с — и так по кругу: **порог
 * срабатывания не набирался никогда**, провал доживал до пятнадцатисекундного
 * таймера ядра. Ровно на это и жаловались «пропадают пинги».
 *
 * Поэтому судится не соседняя пара замеров, а **скользящее окно не короче
 * [MIN_WINDOW_MS]**: калибровка порогов остаётся той же, что и была, а учащённый
 * шаг даёт не другие пороги, а более частую проверку того же окна.
 */
class TunnelStallDetector(
    private val minTxBytes: Long = MIN_TX_BYTES,
    private val minRxBytes: Long = MIN_RX_BYTES,
    private val triggerMs: Long = TRIGGER_MS,
    private val rearmMs: Long = REARM_MS,
    private val maxUnhelpfulForces: Int = MAX_UNHELPFUL_FORCES,
    private val minWindowMs: Long = MIN_WINDOW_MS,
    private val healthyFlowMs: Long = HEALTHY_FLOW_MS,
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

    /**
     * Замеры за последнее окно наблюдения, от старого к новому.
     *
     * Хранится ровно столько, чтобы самый старый давал окно не короче
     * [minWindowMs]: на шаге 4,5 с это две записи, на учащённом 1,5 с — четыре.
     */
    private val history = ArrayDeque<Sample>()

    /**
     * Начало текущей непрерывной тишины по `uptimeMillis`, 0 — тишины нет.
     *
     * Раньше здесь копилась сумма длин окон. С перекрывающимися окнами так делать
     * нельзя — одно и то же время засчиталось бы столько раз, сколько окон его
     * накрыло. Отметка начала даёт ту же величину без двойного счёта.
     */
    private var silenceStartedAtMs = 0L
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

    /**
     * С какого момента обратный поток идёт непрерывно, 0 — не идёт.
     *
     * Нужно, чтобы бюджет форсирований открывало **устойчивое** возвращение потока, а
     * не мелькание. Замер на Pixel 4a (2026-08-30, узел `8.34.146.3:903`): туннель
     * чередовал ~9 с тишины и ~5 с трафика, и одно живое окно между провалами
     * обнуляло счётчик каждый раз. Из-за этого `maxUnhelpfulForces = 1` не работал
     * вовсе: рукопожатие просили десять раз подряд, а до смены узла дело дошло только
     * через две минуты — всё это время пользователь видел пропадающие пинги.
     */
    private var flowingSinceMs = 0L

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
        history.clear()
        silenceStartedAtMs = 0L
        flowingSinceMs = 0L
        mutedUntilUptimeMs = 0L
        triggerCount = 0
        unhelpfulForces = 0
        budgetExhaustionAnnounced = false
    }

    /**
     * Наблюдение начинается заново: разрыв в тиках, перезапуск счётчиков, новый
     * туннель. Отсчёт непрерывного потока тоже обнуляется — до разрыва он относился
     * к другой картине, и засчитывать его как «течёт уже полминуты» нельзя.
     */
    private fun restartFrom(sample: Sample) {
        history.clear()
        history.addLast(sample)
        silenceStartedAtMs = 0L
        flowingSinceMs = 0L
    }

    fun observe(sample: Sample): Outcome {
        val prev = history.lastOrNull()

        if (prev == null) {
            restartFrom(sample)
            return notIndicative(0L, "первый замер")
        }

        val tickMs = sample.uptimeMs - prev.uptimeMs
        if (tickMs <= 0L) {
            restartFrom(sample)
            return notIndicative(tickMs, "часы не двигались")
        }
        if (tickMs > MAX_GAP_MS) {
            restartFrom(sample)
            return notIndicative(tickMs, "перерыв в наблюдении ${tickMs / 1000} с")
        }
        if (sample.txBytes < prev.txBytes || sample.rxBytes < prev.rxBytes) {
            // Счётчики пира начались заново — это новый туннель, а не отказ старого.
            restartFrom(sample)
            return notIndicative(tickMs, "счётчики туннеля перезапущены")
        }

        history.addLast(sample)
        // Оставляем ровно одну запись за границей окна: тогда `history.first()`
        // даёт самое короткое окно из тех, что не короче порога.
        while (history.size > 2 && sample.uptimeMs - history.elementAt(1).uptimeMs >= minWindowMs) {
            history.removeFirst()
        }
        while (history.size > MAX_HISTORY_SAMPLES) {
            history.removeFirst()
        }

        val reference = history.first()
        val windowMs = sample.uptimeMs - reference.uptimeMs
        if (windowMs < minWindowMs) {
            // Окно ещё не набралось. Тишину при этом не теряем и подозрение не
            // снимаем: иначе учащённый шаг сам себя обнулял бы, а именно из-за
            // этого детектор и не добирался до порога срабатывания.
            return if (silenceStartedAtMs != 0L) {
                Outcome(
                    state = State.SUSPECTED,
                    stalledForMs = sample.uptimeMs - silenceStartedAtMs,
                    deltaTxBytes = sample.txBytes - reference.txBytes,
                    deltaRxBytes = sample.rxBytes - reference.rxBytes,
                    windowMs = windowMs,
                    reason = "тишина ${sample.uptimeMs - silenceStartedAtMs} мс, окно ещё не набралось",
                )
            } else {
                notIndicative(windowMs, "окно наблюдения ещё не набралось")
            }
        }

        val deltaTx = sample.txBytes - reference.txBytes
        val deltaRx = sample.rxBytes - reference.rxBytes

        if (deltaTx < minTxBytes) {
            // Мы почти ничего не отправляли: тишина в ответ ничего не доказывает.
            // Копить её нельзя — иначе спящий телефон обвинит здоровый узел.
            silenceStartedAtMs = 0L
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
            silenceStartedAtMs = 0L
            if (flowingSinceMs == 0L) {
                flowingSinceMs = reference.uptimeMs
            }
            // Бюджет форсирований открывает только **устойчивый** поток. Одно живое
            // окно между двумя провалами — это не «рукопожатие помогло», а обычная
            // картина мерцающего узла, и раньше именно она не давала детектору
            // дойти до смены узла.
            if (sample.uptimeMs - flowingSinceMs >= healthyFlowMs) {
                unhelpfulForces = 0
            }
            return Outcome(
                state = State.FLOWING,
                stalledForMs = 0L,
                deltaTxBytes = deltaTx,
                deltaRxBytes = deltaRx,
                windowMs = windowMs,
                reason = "обратный поток идёт",
            )
        }

        flowingSinceMs = 0L
        if (silenceStartedAtMs == 0L) {
            silenceStartedAtMs = reference.uptimeMs
        }
        val stalledMs = sample.uptimeMs - silenceStartedAtMs

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
        silenceStartedAtMs = 0L
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
         * Короче этого окно ничего не судит: пороги в байтах выведены для шага
         * наблюдения 4,5 с, и на более коротком окне тот же трафик не набирает их
         * просто из-за длины.
         *
         * Четыре секунды, а не четыре с половиной: неинтерактивный шаг равен ровно
         * 4 000 мс, и при пороге 4 500 каждый второй замер спящего телефона
         * оказывался бы «окно ещё не набралось».
         */
        const val MIN_WINDOW_MS = 4_000L

        /**
         * Потолок истории замеров. При шаге 1,5 с и окне 4 с нужно четыре записи;
         * запас взят на случай ещё более частого опроса.
         */
        const val MAX_HISTORY_SAMPLES = 16

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

        /**
         * Столько подряд идущего обратного потока считается выздоровлением.
         *
         * Здоровое удержание в замерах — 1,05–1,4 с наихудшей тишины за двадцать
         * секунд, то есть выздоровевший туннель течёт минутами. Мерцающий узел из
         * замера 2026-08-30 давал ровно 5 с потока между девятисекундными провалами,
         * и именно эти пять секунд обнуляли бюджет. Тридцать секунд лежат между этими
         * двумя картинами с запасом в обе стороны.
         */
        const val HEALTHY_FLOW_MS = 30_000L
    }
}

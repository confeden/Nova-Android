package com.example.nova

/**
 * Тихий подбор `I1` для встроенных AWG-профилей.
 *
 * Зачем. `I1` — первый пакет потока: поддельный QUIC Initial с чужим именем в
 * SNI. Во встроенных профилях он один и тот же у всех и зашит в прошивку, а сети
 * у людей разные — где-то этот конкретный домен уже примелькался DPI, где-то не
 * проходит вовсе. Подобрать имя под сеть можно только на самой сети.
 *
 * Правила владельца: работать в фоне, незаметно, после пяти минут бездействия и
 * не нагружая батарею; имена брать из списка SNI, начиная с верхних; показывать
 * пользователю, какому профилю какой домен достался — доменом, а не шестнадцатеричной
 * простынёй.
 *
 * Почему это дёшево. Один шаг — это построить пакет (чистая арифметика, доли
 * миллисекунды) и записать строчку в файл. **Сети шаг не касается вовсе**: проверит
 * подобранное имя обычное подключение, когда оно случится. Поэтому «не нагружая
 * батарею» здесь не обещание, а свойство: тратить нечего.
 *
 * Объект чистый: ни `Context`, ни ввода-вывода, ни времени — всё приходит снаружи.
 */
object AwgI1Adaptation {

    /** Столько экран должен быть погашен, прежде чем шаг вообще рассматривается. */
    const val IDLE_BEFORE_STEP_MS = 5L * 60L * 1000L

    /** Пауза между шагами. Один профиль за полчаса — незаметно и достаточно. */
    const val STEP_COOLDOWN_MS = 30L * 60L * 1000L

    /**
     * Профиль глазами подбора.
     *
     * @param score чем меньше, тем хуже профиль держится: сюда кладётся качество,
     *        посчитанное снаружи по замерам
     * @param adaptedSni уже подобранное имя, пустая строка — подбора не было
     * @param attempts сколько имён этому профилю уже пробовали
     */
    data class Profile(
        val id: String,
        val score: Int,
        val adaptedSni: String = "",
        val attempts: Int = 0,
        /**
         * `I1` из прошивки — QUIC Initial. Без значения по умолчанию намеренно:
         * забытый параметр должен ломать сборку, а не тихо возвращать подбор на
         * семена с другим прикрытием (см. [isQuicInitial]).
         */
        val quicI1: Boolean,
    )

    data class Inputs(
        val enabled: Boolean,
        val connected: Boolean,
        val screenOffMs: Long,
        val sinceLastStepMs: Long,
        val profiles: List<Profile>,
        /** Имена в порядке предпочтения: сверху `white.sni`, дальше российский список. */
        val sniPool: List<String>,
    )

    /** Что сделать на этом шаге. */
    data class Step(val profileId: String, val sni: String)

    /**
     * Подставляет подобранное `I1` в готовый набор строк интерфейса AWG.
     *
     * Вынесено сюда из службы ради проверяемости: подстановка — это то место, где
     * подбор либо доезжает до рукопожатия, либо тихо теряется, и «наверное
     * работает» тут не годится. Функция чистая, поэтому её стерегут тесты, а не
     * наблюдение за телефоном.
     *
     * @param extras строки вида `Jc = 4`, `I1 = <b 0x…>` из текста профиля
     * @param adaptedI1 подобранное значение; пустое — вернуть набор как есть
     */
    /**
     * Похоже ли значение на QUIC Initial версии 1 — длинный заголовок и версия
     * `00000001`.
     *
     * Нужно потому, что прикрытий во встроенных семенах **два**, а не одно. Из
     * пятидесяти 33 несут в `I1` настоящий QUIC Initial (1250 Б, первый байт `0xce`
     * или `0xc7`), а 17 — SIP-звонок: `I1` это `INVITE …` на 348 Б, и к нему идёт
     * `I2` с ответом `SIP/2.0 …` на 245 Б. Подбор умеет строить только QUIC
     * ([ProtonQuicInitial.buildI1]), и подстановка его SIP-семени оставляла профиль
     * с чужим инициалом впереди и ответом SIP следом — прикрытие переставало быть
     * связным. Поэтому подбор трогает только QUIC-семена.
     */
    fun isQuicInitial(value: String): Boolean {
        val hex = value.substringAfter("0x", "").substringBefore('>').trim()
        if (hex.length < 10) return false
        val firstByte = hex.substring(0, 2).toIntOrNull(16) ?: return false
        // Длинный заголовок (0x80) плюс фиксированный бит (0x40).
        if (firstByte and 0xC0 != 0xC0) return false
        return hex.substring(2, 10).equals("00000001", ignoreCase = true)
    }

    fun applyI1(extras: List<String>, adaptedI1: String): List<String> {
        val value = adaptedI1.trim()
        if (value.isEmpty()) return extras
        var replaced = false
        val out = extras.map { line ->
            if (line.substringBefore('=').trim().equals("I1", ignoreCase = true)) {
                replaced = true
                "I1 = $value"
            } else {
                line
            }
        }
        // Профиль без своего `I1` тоже получает подобранный: иначе подбор работал бы
        // только там, где заготовка уже была, и на голых профилях молча ничего не
        // делал бы.
        return if (replaced) out else out + "I1 = $value"
    }

    /** Качество, при котором профиль считается рабочим (`getWarpVerifiedQualityTier`). */
    const val WORKING_QUALITY_TIER = 2

    /**
     * Стоит ли пробовать этому профилю другой `I1` в ручной адаптации.
     *
     * Рабочий профиль не трогаем вовсе: у него `I1` уже подходит этой сети, и замена
     * может только испортить. Пробовать имеет смысл там, где терять нечего — профиль
     * деградировал или не дал data-plane совсем. Это и есть «менять заголовок там,
     * где это поможет»: угадать заранее нельзя, но можно не рисковать рабочим.
     */
    fun deservesManualI1Candidate(qualityTier: Int, quicI1: Boolean): Boolean =
        quicI1 && qualityTier < WORKING_QUALITY_TIER

    /**
     * Следующее имя для профиля: сверху списка, но не то, что ему уже выдано.
     *
     * Общая для фонового шага и ручной адаптации, чтобы правило выбора имени было
     * одно. Повторно назначить то же имя — потраченный впустую заход.
     *
     * Совпадение с уже выданным именем сдвигает выбор **на одно вперёд**, а не
     * возвращает к началу списка: возврат к началу предлагал бы имя, которое этому
     * профилю пробовали самым первым, то есть заход всё равно уходил бы впустую.
     * Отрицательный счётчик тоже нормализуется — остаток от деления в Kotlin
     * сохраняет знак, и без этого выбор ушёл бы за границы списка.
     */
    fun nextSniForProfile(pool: List<String>, adaptedSni: String, attempts: Int): String? {
        if (pool.isEmpty()) return null
        val start = ((attempts % pool.size) + pool.size) % pool.size
        for (offset in pool.indices) {
            val candidate = pool[(start + offset) % pool.size]
            if (candidate != adaptedSni) return candidate
        }
        // Весь список состоит из уже выданного имени — предлагать нечего.
        return null
    }

    /**
     * @return шаг или null, если сейчас не время. Null — обычное состояние, а не
     *         отказ: подбор на то и фоновый, что почти всегда молчит.
     */
    /**
     * Пора ли вообще рассматривать шаг — только сроки, без списка профилей.
     *
     * Отдельно от [decide] потому, что шаг едет на общем сердцебиении и приходит сюда
     * раз в минуту, а список профилей стоит разбора пятидесяти конфигураций. В
     * двадцати девяти случаях из тридцати ответ здесь «нет», и собирать список ради
     * этого незачем. Пороги остаются в одном месте: [decide] спрашивает эту же
     * функцию.
     */
    fun isStepDue(
        enabled: Boolean,
        connected: Boolean,
        screenOffMs: Long,
        sinceLastStepMs: Long,
    ): Boolean =
        enabled &&
            connected &&
            screenOffMs >= IDLE_BEFORE_STEP_MS &&
            sinceLastStepMs >= STEP_COOLDOWN_MS

    fun decide(inputs: Inputs): Step? {
        if (
            !isStepDue(
                enabled = inputs.enabled,
                connected = inputs.connected,
                screenOffMs = inputs.screenOffMs,
                sinceLastStepMs = inputs.sinceLastStepMs,
            )
        ) {
            return null
        }

        val pool = inputs.sniPool.map(SniMaskPolicy::normalizeHost).filter { it.isNotBlank() }.distinct()
        if (pool.isEmpty()) return null

        // Берётся самый слабый профиль, а среди равных — тот, кому пробовали меньше
        // имён. Иначе один неудачник забирал бы себе все шаги подряд, а остальные
        // не получили бы ни одного.
        val target = inputs.profiles
            .filter { it.id.isNotBlank() && it.quicI1 }
            .minWithOrNull(compareBy<Profile> { it.score }.thenBy { it.attempts }.thenBy { it.id })
            ?: return null

        // Имена идут сверху списка, но уже выданное этому профилю пропускается:
        // повторно подставить то же самое значит потратить шаг впустую.
        val chosen = nextSniForProfile(pool, target.adaptedSni, target.attempts) ?: return null
        return Step(profileId = target.id, sni = chosen)
    }
}

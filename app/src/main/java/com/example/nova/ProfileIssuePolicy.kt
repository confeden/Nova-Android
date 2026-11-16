package com.example.nova

/**
 * Когда личные профили (WARP, Proton, MASQUE) выпускаются сами, а когда — нет.
 *
 * Чистая логика без Android: решает по числам, поэтому проверяется юнит-тестами
 * (`ProfileIssuePolicyTest`). Всё, что читает систему, живёт в [ProfileIssueGate] и
 * [ProfileIssueLedger].
 *
 * ## Правило версии (D45, уточняет D40)
 *
 * Личный набор выпускается автоматически **один раз на версию приложения**:
 *  - набора нет — выпускаем;
 *  - набор есть, и выпущен этой или более новой версией — **не трогаем**:
 *    ни при подключении, ни при переподключении, ни при смене сети. Только кнопкой
 *    в настройках (`force`);
 *  - набор есть, но выпущен более старой версией (или метки нет вовсе — набор из
 *    сборки до D45) — после обновления выпускаем заново ровно один раз. Старый набор
 *    лежит на месте, пока новый не готов целиком; встроенные семена работают весь
 *    остаток времени (I6).
 *
 * Неудача не повод для частых повторов: каждая попытка — это регистрация в
 * Cloudflare/Proton и сканирование. Пауза растёт ([BACKOFF_MS]), а у обновления
 * набора, который уже есть и работает, число автоматических попыток на версию
 * ограничено ([MAX_UPGRADE_ATTEMPTS]): дальше — только кнопка.
 */
object ProfileIssuePolicy {

    enum class Kind(val key: String) {
        WARP("warp"),
        PROTON("proton"),
        MASQUE("masque"),
    }

    /**
     * Запись книги выпусков для одного вида профилей.
     *
     * @param issuedVersion `versionCode`, под которым набор выпущен успешно; 0 — метки нет.
     * @param attemptVersion `versionCode`, под которым считаются [attempts].
     * @param attempts неудачные заходы на выпуск этой версии (успех обнуляет).
     */
    data class Entry(
        val issuedVersion: Int = 0,
        val attemptVersion: Int = 0,
        val attempts: Int = 0,
        val lastAttemptAt: Long = 0L,
    )

    enum class Verdict { NOT_NEEDED, GO, COOLDOWN, EXHAUSTED }

    data class Decision(
        val verdict: Verdict,
        val reason: String,
        val retryInMs: Long = 0L,
        /** Набор уже есть и просто старый: это обновление, а не первый выпуск. */
        val upgrade: Boolean = false,
    ) {
        val go: Boolean get() = verdict == Verdict.GO
    }

    /** Паузы после неудачной попытки: 1 ч, 3 ч, 12 ч, затем сутки. */
    val BACKOFF_MS: LongArray = longArrayOf(
        60L * 60_000L,
        3L * 60L * 60_000L,
        12L * 60L * 60_000L,
        24L * 60L * 60_000L,
    )

    /** Автоматических попыток обновить **уже работающий** набор за версию. */
    const val MAX_UPGRADE_ATTEMPTS = 4

    fun backoffMs(attempts: Int): Long =
        if (attempts <= 0) 0L else BACKOFF_MS[minOf(attempts - 1, BACKOFF_MS.lastIndex)]

    fun decide(
        hasSet: Boolean,
        entry: Entry?,
        currentVersion: Int,
        nowMs: Long,
    ): Decision {
        val issued = entry?.issuedVersion ?: 0
        if (hasSet && issued >= currentVersion) {
            return Decision(Verdict.NOT_NEEDED, "набор выпущен версией $issued, текущая $currentVersion")
        }
        val upgrade = hasSet
        val attempts = if (entry != null && entry.attemptVersion == currentVersion) entry.attempts else 0
        if (attempts <= 0) {
            return Decision(
                Verdict.GO,
                if (upgrade) "набор выпущен версией $issued, текущая $currentVersion" else "набора нет",
                upgrade = upgrade,
            )
        }
        if (upgrade && attempts >= MAX_UPGRADE_ATTEMPTS) {
            return Decision(
                Verdict.EXHAUSTED,
                "обновить набор до версии $currentVersion не вышло $attempts раза — дальше только кнопкой",
                upgrade = true,
            )
        }
        val left = (entry?.lastAttemptAt ?: 0L) + backoffMs(attempts) - nowMs
        return if (left > 0L) {
            Decision(Verdict.COOLDOWN, "пауза после $attempts неудач", retryInMs = left, upgrade = upgrade)
        } else {
            Decision(Verdict.GO, "пауза после $attempts неудач прошла", upgrade = upgrade)
        }
    }

    // -- ворота: можно ли сейчас тратить канал на фоновый выпуск -------------------

    /** Что сейчас делает телефон. Числа, а не `Context`, чтобы правило проверялось тестом. */
    data class Environment(
        val hasNetwork: Boolean = true,
        val powerSave: Boolean = false,
        /** -1 — не знаем. */
        val batteryPercent: Int = -1,
        val charging: Boolean = false,
        val dataSaver: Boolean = false,
        val roaming: Boolean = false,
        val cellular: Boolean = false,
        val metered: Boolean = false,
        /** Оценка системы, кбит/с; 0 — не знаем. */
        val downKbps: Int = 0,
        val upKbps: Int = 0,
        /** Байт/с по всему устройству за окно измерения; -1 — измерить не удалось. */
        val bytesPerSecond: Long = -1L,
    )

    data class GateVerdict(val allowed: Boolean, val reason: String)

    /** Выше этого устройство считается занятым: человек грузит страницу или файл. */
    const val BUSY_BYTES_PER_SECOND = 40_000L
    const val LOW_BATTERY_PERCENT = 20
    const val WEAK_DOWN_KBPS = 1_500
    const val WEAK_UP_KBPS = 300

    /**
     * @param firstSet у пользователя личного набора ещё нет вовсе. Тогда выпуск можно и на
     *        сотовой сети; обновление уже работающего набора ждёт безлимитного канала.
     */
    fun evaluateGate(env: Environment, firstSet: Boolean): GateVerdict {
        fun no(reason: String) = GateVerdict(false, reason)
        return when {
            !env.hasNetwork -> no("нет сети")
            env.powerSave -> no("экономия заряда")
            env.batteryPercent in 0 until LOW_BATTERY_PERCENT && !env.charging -> no("низкий заряд")
            env.dataSaver -> no("экономия трафика")
            env.roaming -> no("роуминг")
            (env.metered || env.cellular) && !firstSet -> no("платный канал: обновление ждёт безлимитную сеть")
            env.downKbps in 1 until WEAK_DOWN_KBPS || env.upKbps in 1 until WEAK_UP_KBPS ->
                no("слабый канал")
            env.bytesPerSecond >= BUSY_BYTES_PER_SECOND -> no("идёт трафик")
            else -> GateVerdict(true, "")
        }
    }
}

package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дефект, ради которого написаны эти проверки: ручная «Адаптация к условиям
 * сети» меряла пинг и факт подключения, а узлы различает не это. Узел с
 * нормальным пингом терял обратный поток и пересобирал сессию втрое чаще нормы
 * — и по замерам адаптации выглядел здоровым.
 *
 * Отдельно проверяется то, на чём уже обжигались: счётчик без знаменателя.
 * «Пять неудачных проб подряд» у разных узлов означает разное время, поэтому
 * тишина меряется в миллисекундах, а окна разной длины приводятся к общей.
 */
class SessionHoldMetricTest {

    /**
     * Здоровый узел: пробы раз в секунду, все удачные.
     *
     * Тишина у него не нулевая, а равна шагу цикла: цикл спит секунду и только
     * потом спрашивает узел, поэтому меньше одного шага мы измерить не можем.
     * Это разрешающая способность замера, и она обязана оставаться заметно ниже
     * границы спокойствия — иначе здоровый узел штрафовался бы за собственный
     * шаг опроса.
     */
    @Test
    fun `здоровое окно даёт тишину не больше шага опроса`() {
        val accumulator = SessionHoldMetric.Accumulator(startedAtMs = 0L)
        var atMs = 0L
        repeat(17) {
            atMs += 1_000L
            accumulator.note(succeeded = true, atMs = atMs)
        }
        val window = accumulator.finish(atMs)

        assertEquals(17, window.probeCount)
        assertEquals(1_000L, window.worstStallMs)
        assertTrue(window.representative)
        assertEquals("", window.rejectionReason)
        assertEquals(SessionHoldMetric.GRADE_STEADY, gradeOf(window))
    }

    /**
     * То же на слабом устройстве, где шаг цикла полуторный: разрешающая
     * способность падает, но здоровый узел всё равно должен остаться спокойным.
     */
    @Test
    fun `полуторный шаг опроса не делает здоровый узел шатким`() {
        val accumulator = SessionHoldMetric.Accumulator(startedAtMs = 0L)
        var atMs = 0L
        repeat(11) {
            atMs += 1_600L
            accumulator.note(succeeded = true, atMs = atMs)
        }
        val window = accumulator.finish(atMs)

        assertEquals(1_600L, window.worstStallMs)
        assertTrue(window.representative)
        assertEquals(SessionHoldMetric.GRADE_STEADY, gradeOf(window))
    }

    /**
     * Главное отличие от покрытия: столько же неудач, но подряд.
     *
     * Двенадцать неудач вразброс и двенадцать подряд дают одинаковые «N из M»,
     * но описывают разные узлы. Пинговое покрытие их не различает, тишина —
     * различает.
     */
    @Test
    fun `неудачи подряд дают большую тишину, чем те же неудачи вразброс`() {
        val scattered = SessionHoldMetric.Accumulator(startedAtMs = 0L)
        var scatteredAtMs = 0L
        repeat(8) { index ->
            scatteredAtMs += 1_000L
            scattered.note(succeeded = index % 2 == 0, atMs = scatteredAtMs)
        }
        val scatteredWindow = scattered.finish(scatteredAtMs)

        val burst = SessionHoldMetric.Accumulator(startedAtMs = 0L)
        var burstAtMs = 0L
        repeat(8) { index ->
            burstAtMs += 1_000L
            burst.note(succeeded = index >= 4, atMs = burstAtMs)
        }
        val burstWindow = burst.finish(burstAtMs)

        assertEquals(scatteredWindow.probeCount, burstWindow.probeCount)
        assertTrue(
            "тишина при неудачах подряд должна быть больше: " +
                "${burstWindow.worstStallMs} против ${scatteredWindow.worstStallMs}",
            burstWindow.worstStallMs > scatteredWindow.worstStallMs
        )
    }

    /** Узел, замолчавший к концу окна, — худший случай, а не отсутствие данных. */
    @Test
    fun `хвостовая тишина засчитывается`() {
        val accumulator = SessionHoldMetric.Accumulator(startedAtMs = 0L)
        accumulator.note(succeeded = true, atMs = 2_000L)
        accumulator.note(succeeded = false, atMs = 5_000L)
        accumulator.note(succeeded = false, atMs = 8_000L)
        val window = accumulator.finish(17_500L)

        assertEquals(15_500L, window.worstStallMs)
    }

    /** Узел, не ответивший ни разу, не должен показать нулевую тишину. */
    @Test
    fun `окно без единой удачной пробы считает тишину от открытия окна`() {
        val accumulator = SessionHoldMetric.Accumulator(startedAtMs = 0L)
        repeat(8) { index ->
            accumulator.note(succeeded = false, atMs = (index + 1) * 2_100L)
        }
        val window = accumulator.finish(17_500L)

        assertEquals(17_500L, window.worstStallMs)
        assertEquals(SessionHoldMetric.GRADE_LOSING, gradeOf(window))
    }

    /**
     * Итерация без VPN-сети наружу ничего не отправила: молчал не узел.
     * Но и полноценным такое окно считать нельзя.
     */
    @Test
    fun `пропущенные итерации не приписываются узлу и обесценивают окно`() {
        val accumulator = SessionHoldMetric.Accumulator(startedAtMs = 0L)
        accumulator.note(succeeded = true, atMs = 1_000L)
        repeat(10) { index ->
            accumulator.noteSkipped(atMs = 2_000L + index * 1_500L)
        }
        accumulator.note(succeeded = true, atMs = 17_000L)
        val window = accumulator.finish(17_500L)

        assertTrue("тишина не должна приписываться узлу", window.worstStallMs < 2_000L)
        assertFalse(window.representative)
        assertTrue(window.rejectionReason.contains("VPN-сеть"))
    }

    /**
     * Узел замолчал, и лишь потом пропала VPN-сеть. Тишина до пропуска измерена
     * настоящими пробами через живой туннель — забывать её нельзя, иначе узел,
     * молчавший десять секунд, выглядел бы безупречным.
     */
    @Test
    fun `тишина до пропуска засчитывается узлу`() {
        val accumulator = SessionHoldMetric.Accumulator(startedAtMs = 0L)
        accumulator.note(succeeded = true, atMs = 1_000L)
        accumulator.note(succeeded = false, atMs = 3_100L)
        accumulator.note(succeeded = false, atMs = 5_200L)
        accumulator.note(succeeded = false, atMs = 7_300L)
        accumulator.note(succeeded = false, atMs = 9_400L)
        accumulator.noteSkipped(atMs = 11_500L)
        accumulator.note(succeeded = true, atMs = 13_000L)
        accumulator.note(succeeded = true, atMs = 14_000L)
        val window = accumulator.finish(15_000L)

        assertEquals(10_500L, window.worstStallMs)
        assertEquals(SessionHoldMetric.GRADE_LOSING, gradeOf(window))
    }

    /** Короткое окно ничего не описывает: профиль ротировали раньше срока. */
    @Test
    fun `слишком короткое окно не показательно`() {
        val accumulator = SessionHoldMetric.Accumulator(startedAtMs = 0L)
        repeat(6) { index ->
            accumulator.note(succeeded = true, atMs = (index + 1) * 1_000L)
        }
        val window = accumulator.finish(6_000L)

        assertFalse(window.representative)
        assertTrue(window.rejectionReason.contains("короче"))
    }

    /** Проб мало — это не выборка, даже если по времени окно длинное. */
    @Test
    fun `окно с горсткой проб не показательно`() {
        val accumulator = SessionHoldMetric.Accumulator(startedAtMs = 0L)
        repeat(3) { index ->
            accumulator.note(succeeded = true, atMs = (index + 1) * 5_000L)
        }
        val window = accumulator.finish(16_000L)

        assertFalse(window.representative)
        assertTrue(window.rejectionReason.contains("проб"))
    }

    /**
     * Знаменатель. Окна разной длины должны сравниваться, а не складываться
     * как есть: иначе длинное окно с той же долей тишины выглядит хуже.
     */
    @Test
    fun `тишина приводится к общей длительности окна`() {
        val short = SessionHoldMetric.normalizedStallMs(
            windows = 1,
            stallMs = 4_000L,
            spanMs = 17_500L,
        )
        val long = SessionHoldMetric.normalizedStallMs(
            windows = 2,
            stallMs = 8_000L,
            spanMs = 35_000L,
        )

        assertEquals(short, long, 0.001)
        assertEquals(4_000.0, short, 0.001)
    }

    /** Ноль неотличим от идеала — «данных нет» должно быть отдельным значением. */
    @Test
    fun `без замера отдаётся минус один, а не ноль`() {
        assertEquals(
            -1.0,
            SessionHoldMetric.normalizedStallMs(windows = 0, stallMs = 0L, spanMs = 0L),
            0.001
        )
        assertEquals(
            SessionHoldMetric.GRADE_UNKNOWN,
            SessionHoldMetric.grade(
                windows = 0,
                stallMs = 0L,
                spanMs = 0L,
                checkedAtMs = 0L,
                nowMs = 1_000L,
            )
        )
        assertEquals(
            0.0,
            SessionHoldMetric.penalty(
                windows = 0,
                stallMs = 0L,
                spanMs = 0L,
                checkedAtMs = 0L,
                nowMs = 1_000L,
            ),
            0.001
        )
    }

    /** Неизмеренный узел не должен оказаться выше того, про кого известно, что он держит. */
    @Test
    fun `порядок оценок удержания`() {
        assertTrue(SessionHoldMetric.GRADE_STEADY > SessionHoldMetric.GRADE_SHAKY)
        assertTrue(SessionHoldMetric.GRADE_SHAKY > SessionHoldMetric.GRADE_UNKNOWN)
        assertTrue(SessionHoldMetric.GRADE_UNKNOWN > SessionHoldMetric.GRADE_LOSING)
    }

    /** Одиночная неудачная проба — это ещё не потеря обратного потока. */
    @Test
    fun `одиночная неудача оставляет узел спокойным`() {
        val grade = SessionHoldMetric.grade(
            windows = 1,
            stallMs = 2_100L,
            spanMs = 17_500L,
            checkedAtMs = 1_000L,
            nowMs = 2_000L,
        )
        assertEquals(SessionHoldMetric.GRADE_STEADY, grade)
        assertEquals(
            0.0,
            SessionHoldMetric.penalty(
                windows = 1,
                stallMs = 2_100L,
                spanMs = 17_500L,
                checkedAtMs = 1_000L,
                nowMs = 2_000L,
            ),
            0.001
        )
    }

    /** Два узла из замера 2026-08-10: спокойный и теряющий поток. */
    @Test
    fun `узел с длинными провалами получает худшую оценку и штраф`() {
        val steady = SessionHoldMetric.grade(
            windows = 2,
            stallMs = 2_000L,
            spanMs = 35_000L,
            checkedAtMs = 1_000L,
            nowMs = 2_000L,
        )
        val losing = SessionHoldMetric.grade(
            windows = 2,
            stallMs = 24_000L,
            spanMs = 35_000L,
            checkedAtMs = 1_000L,
            nowMs = 2_000L,
        )

        assertEquals(SessionHoldMetric.GRADE_STEADY, steady)
        assertEquals(SessionHoldMetric.GRADE_LOSING, losing)
        assertNotEquals(steady, losing)

        val penalty = SessionHoldMetric.penalty(
            windows = 2,
            stallMs = 24_000L,
            spanMs = 35_000L,
            checkedAtMs = 1_000L,
            nowMs = 2_000L,
        )
        assertTrue("штраф должен быть заметным, получено $penalty", penalty > 10.0)
        assertTrue(
            "штраф не должен превышать потолок, получено $penalty",
            penalty <= SessionHoldMetric.MAX_PENALTY
        )
    }

    /** Промежуточная зона: тишина есть, но короткая. */
    @Test
    fun `две-три неудачи подряд дают среднюю оценку`() {
        val grade = SessionHoldMetric.grade(
            windows = 1,
            stallMs = 5_000L,
            spanMs = 17_500L,
            checkedAtMs = 1_000L,
            nowMs = 2_000L,
        )
        assertEquals(SessionHoldMetric.GRADE_SHAKY, grade)
    }

    /** Замер месячной давности не должен клеймить узел навсегда. */
    @Test
    fun `протухший замер перестаёт штрафовать и возвращается в неизвестность`() {
        val staleAtMs = 1_000L
        val nowMs = staleAtMs + SessionHoldMetric.STALE_AFTER_MS + 1L

        assertEquals(
            SessionHoldMetric.GRADE_UNKNOWN,
            SessionHoldMetric.grade(
                windows = 2,
                stallMs = 24_000L,
                spanMs = 35_000L,
                checkedAtMs = staleAtMs,
                nowMs = nowMs,
            )
        )
        assertEquals(
            0.0,
            SessionHoldMetric.penalty(
                windows = 2,
                stallMs = 24_000L,
                spanMs = 35_000L,
                checkedAtMs = staleAtMs,
                nowMs = nowMs,
            ),
            0.001
        )
    }

    /** Свежий замер штрафует сильнее наполовину протухшего. */
    @Test
    fun `штраф затухает с возрастом замера`() {
        val fresh = SessionHoldMetric.penalty(
            windows = 1,
            stallMs = 10_000L,
            spanMs = 17_500L,
            checkedAtMs = 1_000L,
            nowMs = 2_000L,
        )
        val halfStale = SessionHoldMetric.penalty(
            windows = 1,
            stallMs = 10_000L,
            spanMs = 17_500L,
            checkedAtMs = 1_000L,
            nowMs = 1_000L + SessionHoldMetric.STALE_AFTER_MS / 2,
        )

        assertTrue(fresh > halfStale)
        assertTrue(halfStale > 0.0)
    }

    /** Накопление между прогонами: окна складываются вместе со знаменателем. */
    @Test
    fun `накопление складывает и тишину, и длительность`() {
        val window = SessionHoldMetric.Window(
            probeCount = 17,
            spanMs = 17_500L,
            worstStallMs = 4_000L,
            skippedProbes = 0,
        )
        val accumulated = SessionHoldMetric.accumulate(
            previousWindows = 1,
            previousStallMs = 2_000L,
            previousSpanMs = 17_500L,
            window = window,
        )

        assertEquals(2, accumulated.windows)
        assertEquals(6_000L, accumulated.stallMs)
        assertEquals(35_000L, accumulated.spanMs)
    }

    /** Старение: испортившийся месяц назад узел должен уметь отмыться. */
    @Test
    fun `на потолке окон счётчики делятся пополам`() {
        val window = SessionHoldMetric.Window(
            probeCount = 17,
            spanMs = 17_500L,
            worstStallMs = 0L,
            skippedProbes = 0,
        )
        val accumulated = SessionHoldMetric.accumulate(
            previousWindows = SessionHoldMetric.MAX_WINDOWS,
            previousStallMs = 120_000L,
            previousSpanMs = 210_000L,
            window = window,
        )

        assertEquals(SessionHoldMetric.MAX_WINDOWS / 2 + 1, accumulated.windows)
        assertEquals(60_000L, accumulated.stallMs)
        assertEquals(105_000L + 17_500L, accumulated.spanMs)

        val before = SessionHoldMetric.normalizedStallMs(
            windows = SessionHoldMetric.MAX_WINDOWS,
            stallMs = 120_000L,
            spanMs = 210_000L,
        )
        val after = SessionHoldMetric.normalizedStallMs(
            windows = accumulated.windows,
            stallMs = accumulated.stallMs,
            spanMs = accumulated.spanMs,
        )
        assertTrue("спокойное окно должно улучшать замер: $before -> $after", after < before)
    }

    /**
     * Ради чего всё затевалось: пока прошивочный порядок был первым ключом и
     * различался у всех пятидесяти профилей, ни один замер не мог сдвинуть
     * очередь. Корзина оставляет прошивке грубую власть и отдаёт замерам
     * порядок внутри десятки.
     */
    @Test
    fun `корзина прошивочного порядка допускает равенство внутри десятки`() {
        assertEquals(
            SessionHoldMetric.bundledSeedQueueBucket(0),
            SessionHoldMetric.bundledSeedQueueBucket(9)
        )
        assertNotEquals(
            SessionHoldMetric.bundledSeedQueueBucket(9),
            SessionHoldMetric.bundledSeedQueueBucket(10)
        )
        assertTrue(
            SessionHoldMetric.bundledSeedQueueBucket(0) <
                SessionHoldMetric.bundledSeedQueueBucket(49)
        )
    }

    /** Профили не из прошивки идут после встроенных, как и раньше. */
    @Test
    fun `невстроенный профиль остаётся в конце`() {
        assertEquals(
            Int.MAX_VALUE,
            SessionHoldMetric.bundledSeedQueueBucket(Int.MAX_VALUE)
        )
    }

    /** Пятьдесят прошивочных профилей должны лечь в пять корзин, а не в пятьдесят. */
    @Test
    fun `пятьдесят встроенных профилей дают пять корзин`() {
        val buckets = (0 until 50).map { SessionHoldMetric.bundledSeedQueueBucket(it) }.toSet()
        assertEquals(5, buckets.size)
    }

    private fun gradeOf(window: SessionHoldMetric.Window): Int {
        return SessionHoldMetric.grade(
            windows = 1,
            stallMs = window.worstStallMs,
            spanMs = window.spanMs,
            checkedAtMs = 1_000L,
            nowMs = 2_000L,
        )
    }
}

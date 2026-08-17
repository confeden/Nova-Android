package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дефект, ради которого написан детектор: туннель остаётся «Подключено», мы
 * продолжаем отправлять, а обратно пятнадцать секунд не приходит ничего. Ровно
 * столько ждёт собственный таймер ядра, притом что само восстановление в замере
 * заняло 34 мс.
 *
 * Отдельно закреплены три ловушки, на которых наивная реализация ломается:
 * рукопожатие двигает `rx_bytes` и маскирует мёртвый поток; молчащий туннель
 * честно не получает ничего и не должен считаться отказом; проспанный промежуток
 * — не тишина узла.
 */
class TunnelStallDetectorTest {

    private fun detector() = TunnelStallDetector()

    /** Окно 4,5 с с заданными дельтами, начиная с t = 10 000. */
    private fun walk(
        detector: TunnelStallDetector,
        windows: List<Pair<Long, Long>>,
        stepMs: Long = 4_500L,
        startMs: Long = 10_000L,
    ): List<TunnelStallDetector.Outcome> {
        var t = startMs
        var rx = 1_000_000L
        var tx = 1_000_000L
        val out = mutableListOf<TunnelStallDetector.Outcome>()
        out += detector.observe(TunnelStallDetector.Sample(t, rx, tx))
        for ((dTx, dRx) in windows) {
            t += stepMs
            tx += dTx
            rx += dRx
            out += detector.observe(TunnelStallDetector.Sample(t, rx, tx))
        }
        return out
    }

    @Test
    fun `первый замер ничего не судит`() {
        val d = detector()
        val first = d.observe(TunnelStallDetector.Sample(10_000L, 500L, 500L))
        assertEquals(TunnelStallDetector.State.NOT_INDICATIVE, first.state)
        assertFalse(first.shouldForceHandshake)
    }

    @Test
    fun `живой обратный поток не тревожит`() {
        val d = detector()
        val outcomes = walk(d, List(10) { 40_000L to 40_000L })
        assertTrue(outcomes.drop(1).all { it.state == TunnelStallDetector.State.FLOWING })
        assertEquals(0, d.triggerCount)
    }

    @Test
    fun `тишина под нашей отправкой срабатывает раньше пятнадцати секунд ядра`() {
        val d = detector()
        // Отправляем как в измеренном случае — только пинг, около 378 Б за окно.
        val outcomes = walk(d, List(4) { 378L to 0L })
        val fired = outcomes.indexOfFirst { it.shouldForceHandshake }
        assertTrue("детектор обязан сработать", fired > 0)
        // Окна по 4,5 с: срабатывание на втором окне тишины = 9 с < 15 с ядра.
        assertEquals(9_000L, outcomes[fired].stalledForMs)
        assertTrue(outcomes[fired].stalledForMs < 15_000L)
    }

    @Test
    fun `учащённый опрос ловит провал на восьмой секунде`() {
        val d = detector()
        // Первое окно обычное (4,5 с), дальше подозрение и шаг 1,5 с.
        var t = 10_000L
        var rx = 0L
        var tx = 0L
        d.observe(TunnelStallDetector.Sample(t, rx, tx))
        val steps = listOf(4_500L, 1_500L, 1_500L, 1_500L)
        var last: TunnelStallDetector.Outcome? = null
        for (step in steps) {
            t += step
            tx += 300L
            last = d.observe(TunnelStallDetector.Sample(t, rx, tx))
            if (last.shouldForceHandshake) break
        }
        assertTrue(last!!.shouldForceHandshake)
        assertEquals(9_000L, last.stalledForMs)
    }

    @Test
    fun `байты рукопожатия не выдаются за живой поток`() {
        val d = detector()
        // Ровно та ловушка: мёртвый data-plane, но ответ на рукопожатие 92 Б
        // и keepalive 32 Б исправно двигают rx_bytes.
        val outcomes = walk(d, List(4) { 4_000L to 92L })
        assertTrue("92 Б рукопожатия не поток", outcomes.any { it.shouldForceHandshake })
    }

    @Test
    fun `молчащий туннель не обвиняется`() {
        val d = detector()
        // PersistentKeepalive = 5 даёт около 32 Б за окно и ничего в ответ.
        val outcomes = walk(d, List(20) { 32L to 0L })
        assertTrue(outcomes.drop(1).all { it.state == TunnelStallDetector.State.NOT_INDICATIVE })
        assertEquals(0, d.triggerCount)
    }

    @Test
    fun `проспанный промежуток не считается тишиной узла`() {
        val d = detector()
        var t = 10_000L
        d.observe(TunnelStallDetector.Sample(t, 0L, 0L))
        t += TunnelStallDetector.MAX_GAP_MS + 1
        val outcome = d.observe(TunnelStallDetector.Sample(t, 0L, 100_000L))
        assertEquals(TunnelStallDetector.State.NOT_INDICATIVE, outcome.state)
        assertFalse(outcome.shouldForceHandshake)
    }

    @Test
    fun `перезапуск счётчиков нового туннеля не считается отказом`() {
        val d = detector()
        d.observe(TunnelStallDetector.Sample(10_000L, 5_000_000L, 5_000_000L))
        val outcome = d.observe(TunnelStallDetector.Sample(14_500L, 0L, 0L))
        assertEquals(TunnelStallDetector.State.NOT_INDICATIVE, outcome.state)
    }

    @Test
    fun `подтверждённый поток сбрасывает накопленную тишину`() {
        val d = detector()
        val outcomes = walk(
            d,
            listOf(
                4_000L to 0L,      // тишина 4,5 с
                4_000L to 40_000L, // поток вернулся
                4_000L to 0L,      // снова тишина, но накопитель обнулён
            ),
        )
        assertTrue(outcomes.none { it.shouldForceHandshake })
        assertEquals(4_500L, outcomes.last().stalledForMs)
    }

    @Test
    fun `повторные срабатывания разнесены паузой перезарядки`() {
        val d = detector()
        // Проверяем само свойство, а не число: константы паузы и порога
        // меняются вместе с политикой, и тест не должен требовать их правки.
        val step = 4_500L
        var t = 10_000L
        var tx = 0L
        val firedAt = mutableListOf<Long>()
        d.observe(TunnelStallDetector.Sample(t, 0L, tx))
        repeat(20) {
            t += step
            tx += 4_000L
            if (d.observe(TunnelStallDetector.Sample(t, 0L, tx)).state == TunnelStallDetector.State.STALLED) {
                firedAt += t
            }
        }
        assertTrue("детектор обязан срабатывать на мёртвом потоке", firedAt.size >= 2)
        assertTrue("без паузы срабатывало бы каждое окно", firedAt.size < 20)
        firedAt.zipWithNext { a, b ->
            assertTrue(
                "между срабатываниями должно пройти не меньше паузы: ${b - a} мс",
                b - a >= TunnelStallDetector.REARM_MS,
            )
        }
        assertEquals(firedAt.size, d.triggerCount)
    }

    @Test
    fun `бесполезные форсирования прекращаются, но провал фиксируется дальше`() {
        val d = TunnelStallDetector(maxUnhelpfulForces = 2)
        // Мёртвый путь: рукопожатие проходит, поток не возвращается никогда.
        val outcomes = walk(d, List(40) { 4_000L to 0L })
        val stalled = outcomes.filter { it.state == TunnelStallDetector.State.STALLED }
        val forced = outcomes.count { it.shouldForceHandshake }
        assertEquals("форсирований ровно по бюджету", 2, forced)
        assertTrue("провалы продолжают фиксироваться", stalled.size > forced)
        assertTrue(d.forceBudgetExhausted)
        assertEquals("об исчерпании говорим один раз", 1, outcomes.count { it.forceBudgetJustExhausted })
    }

    @Test
    fun `вернувшийся поток открывает бюджет форсирований заново`() {
        val d = TunnelStallDetector(maxUnhelpfulForces = 1)
        // Провал, форсирование помогло, поток пошёл — и снова провал. Окон
        // после возврата потока намеренно больше: между срабатываниями стоит
        // пятнадцатисекундная пауза перезарядки, и без неё сценарий проверял бы
        // не бюджет, а паузу.
        val outcomes = walk(
            d,
            listOf(
                4_000L to 0L,
                4_000L to 0L,      // срабатывание, бюджет израсходован
                4_000L to 40_000L, // поток вернулся — форсирование засчитано полезным
                4_000L to 0L,
                4_000L to 0L,
                4_000L to 0L,      // снова срабатывание: бюджет открыт заново
            ),
        )
        assertEquals("второе форсирование доказывает сброс бюджета", 2, outcomes.count { it.shouldForceHandshake })
        assertEquals(2, d.triggerCount)
    }

    @Test
    fun `reset снимает всё состояние`() {
        val d = detector()
        walk(d, List(4) { 4_000L to 0L })
        assertTrue(d.triggerCount > 0)
        d.reset()
        assertEquals(0, d.triggerCount)
        val first = d.observe(TunnelStallDetector.Sample(99_000L, 0L, 0L))
        assertEquals(TunnelStallDetector.State.NOT_INDICATIVE, first.state)
    }
}

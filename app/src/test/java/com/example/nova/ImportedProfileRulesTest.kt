package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистая логика списка импортированных профилей и ProfilePing-замеров:
 * устаревание кэша, порядок по задержке, троттлинг пересортировки, исключение
 * отключённых профилей.
 */
class ImportedProfileRulesTest {

    private val hour = ImportedProfileRules.STALE_AFTER_MS

    @Test
    fun `нет записи или времени - замер нужен`() {
        assertTrue(ImportedProfileRules.isStale(null, now = 1_000_000L))
        assertTrue(ImportedProfileRules.isStale(0L, now = 1_000_000L))
    }

    @Test
    fun `свежий кэш не перемеряется, старше часа - перемеряется`() {
        val now = 10 * hour
        assertFalse(ImportedProfileRules.isStale(now - 5 * 60_000L, now))
        assertFalse("ровно час ещё свежо", ImportedProfileRules.isStale(now - hour, now))
        assertTrue(ImportedProfileRules.isStale(now - hour - 1, now))
    }

    @Test
    fun `время из будущего - перемеряется`() {
        assertTrue(ImportedProfileRules.isStale(now = 1_000L, measuredAt = 5_000L))
    }

    @Test
    fun `сортировка - известные по возрастанию, таймауты, затем без замера`() {
        data class Item(val name: String, val ping: Int?)
        val items = listOf(
            Item("unmeasured", null),
            Item("slow", 250),
            Item("timeout", ImportedProfileRules.TIMED_OUT),
            Item("fast", 40),
            Item("not-measurable", ImportedProfileRules.NOT_MEASURABLE),
            Item("mid", 120),
        )
        val sorted = ImportedProfileRules.sortByLatency(items) { it.ping }.map { it.name }
        assertEquals(listOf("fast", "mid", "slow", "timeout", "unmeasured", "not-measurable"), sorted)
    }

    @Test
    fun `сортировка равных задержек сохраняет исходный порядок`() {
        val sorted = ImportedProfileRules.sortByLatency(listOf("a" to 50, "b" to 50, "c" to 10)) { it.second }
        assertEquals(listOf("c", "a", "b"), sorted.map { it.first })
    }

    @Test
    fun `троттлинг - первый запуск свободен, следующий ждёт интервал`() {
        val gate = ThrottleGate(ImportedProfileRules.SORT_INTERVAL_MS)
        assertEquals(0L, gate.delayUntilAllowed(now = 5_000L))
        gate.markRan(now = 5_000L)
        assertEquals(1_000L, gate.delayUntilAllowed(now = 5_000L))
        assertEquals(400L, gate.delayUntilAllowed(now = 5_600L))
        assertEquals(0L, gate.delayUntilAllowed(now = 6_000L))
    }

    @Test
    fun `троттлинг - часы назад не дают ждать дольше интервала`() {
        val gate = ThrottleGate(1_000L)
        gate.markRan(now = 10_000L)
        assertEquals(1_000L, gate.delayUntilAllowed(now = 2_000L))
    }

    @Test
    fun `отключённые профили исключаются из выборки, пустой набор ничего не меняет`() {
        val items = listOf("vless|a", "wg-b", "wg-c")
        assertEquals(listOf("vless|a", "wg-c"), ImportedProfileRules.withoutDisabled(items, { it }, setOf("wg-b")))
        assertEquals(items, ImportedProfileRules.withoutDisabled(items, { it }, emptySet()))
    }

    @Test
    fun `число в заголовке - до тысячи как есть, дальше с суффиксом`() {
        assertEquals("0", ImportedProfileRules.formatCount(0, "k"))
        assertEquals("999", ImportedProfileRules.formatCount(999, "k"))
        assertEquals("1k", ImportedProfileRules.formatCount(1000, "k"))
        assertEquals("1.2k", ImportedProfileRules.formatCount(1234, "k"))
        assertEquals("9.9k", ImportedProfileRules.formatCount(9999, "k"))
        assertEquals("10k", ImportedProfileRules.formatCount(10_000, "k"))
        assertEquals("12k", ImportedProfileRules.formatCount(12_499, "k"))
        assertEquals("13k", ImportedProfileRules.formatCount(12_500, "k"))
        assertEquals("1.2к", ImportedProfileRules.formatCount(1234, "к"))
    }

    @Test
    fun `исключение сравнивает идентификаторы без пробелов по краям`() {
        val items = listOf(" wg-b ", "wg-c")
        assertEquals(listOf("wg-c"), ImportedProfileRules.withoutDisabled(items, { it }, setOf("wg-b")))
    }
}

package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Требование владельца: счётчик показывает текущую позицию из общего числа и не
 * перескакивает по числам. Проверки написаны на конкретные наблюдавшиеся скачки.
 */
class AttemptProgressScaleTest {

    @Test
    fun `номер идёт по очереди, а не по прошивочному порядку`() {
        // Симптом был «23/50 → 4/50 → 37/50»: очередь сортировалась по качеству, а
        // нумерация бралась из списка, отсортированного по seed-порядку.
        val ordinals = (0 until 5).map { AttemptProgressScale.ordinal(total = 50, queueIndex = it) }
        assertEquals(listOf(1, 2, 3, 4, 5), ordinals)
    }

    @Test
    fun `номер по очереди монотонен на всём круге`() {
        val total = 50
        val ordinals = (0 until total).map { AttemptProgressScale.ordinal(total, queueIndex = it) }
        assertEquals(1, ordinals.first())
        assertEquals(total, ordinals.last())
        assertTrue(ordinals.zipWithNext().all { (a, b) -> b == a + 1 })
    }

    @Test
    fun `кнопка сдвигает шкалу, а не подменяет одну попытку`() {
        // Нажали «следующий профиль» на восьмом. Выбранный профиль встаёт первым в
        // очередь, поэтому его queueIndex = 0, но на экране должно остаться 8, а
        // дальше — 9 и 10. Раньше здесь было «8 → 2 → 3» (номер гасился после первой
        // попытки) или «8 → 8 → 8» (номер применялся ко всей очереди).
        val ordinals = (0 until 3).map {
            AttemptProgressScale.ordinal(total = 50, queueIndex = it, manualBaseOrdinal = 8)
        }
        assertEquals(listOf(8, 9, 10), ordinals)
    }

    @Test
    fun `сдвинутая шкала заворачивается на круге, а не вылезает за знаменатель`() {
        val total = 10
        val ordinals = (0 until total).map {
            AttemptProgressScale.ordinal(total, queueIndex = it, manualBaseOrdinal = 9)
        }
        assertEquals(listOf(9, 10, 1, 2, 3, 4, 5, 6, 7, 8), ordinals)
        assertTrue(ordinals.all { it in 1..total })
    }

    @Test
    fun `неизвестная позиция берёт запасной номер и не вылезает за знаменатель`() {
        assertEquals(4, AttemptProgressScale.ordinal(total = 50, queueIndex = -1, fallbackOrdinal = 4))
        assertEquals(50, AttemptProgressScale.ordinal(total = 50, queueIndex = -1, fallbackOrdinal = 900))
        assertEquals(1, AttemptProgressScale.ordinal(total = 50, queueIndex = -1, fallbackOrdinal = 0))
    }

    @Test
    fun `знаменатель равен длине очереди и не тянет масштаб прошлой фазы`() {
        // Симптом: после фазы на 50 профилей очередь из восьми показывалась как «3/50».
        assertEquals(8, AttemptProgressScale.total(queueSize = 8, declaredTotal = 0))
        assertEquals(50, AttemptProgressScale.total(queueSize = 50, declaredTotal = 0))
    }

    @Test
    fun `объявленный знаменатель фазы больше размера текущей волны`() {
        // Волна перебирает 9 кандидатов, но фаза объявила 50 профилей — показываем 50.
        assertEquals(50, AttemptProgressScale.total(queueSize = 9, declaredTotal = 50))
    }

    @Test
    fun `знаменатель не бывает меньше достигнутого номера`() {
        assertEquals(12, AttemptProgressScale.total(queueSize = 8, declaredTotal = 0, reachedOrdinal = 12))
        assertEquals(1, AttemptProgressScale.total(queueSize = 0, declaredTotal = 0))
    }

    @Test
    fun `пара номер и знаменатель всегда согласована`() {
        // Ordinal > total на экране читается как ошибка. Проверяем весь разумный диапазон.
        for (total in 1..12) {
            for (index in -1 until total) {
                for (manual in 0..total) {
                    val ordinal = AttemptProgressScale.ordinal(total, index, manual, fallbackOrdinal = total + 5)
                    assertTrue("total=$total index=$index manual=$manual -> $ordinal", ordinal in 1..total)
                }
            }
        }
    }
}

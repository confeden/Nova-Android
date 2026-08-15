package com.example.nova

/**
 * Шкала счётчика попыток: «номер из общего числа».
 *
 * Дефекты, ради которых это появилось:
 *
 * - Номер брался как позиция профиля в списке, отсортированном по прошивочному
 *   порядку, а перебор шёл по очереди, отсортированной по качеству. Числа шли
 *   вразнобой: 23/50 → 4/50 → 37/50.
 * - Знаменатель считался как `maxOf(прошлое значение, …)` и работал храповиком:
 *   очередь из восьми попыток показывалась в масштабе прошлой фазы, «3/50».
 * - Номер, названный кнопкой «следующий профиль», применялся ко всей очереди
 *   (счётчик замирал) или гасился после первой попытки (счётчик прыгал назад).
 *
 * Правило: номер — это позиция текущей попытки **в той очереди, которую сейчас
 * перебирают**, а знаменатель — длина этой очереди. Кнопка не подменяет номер, а
 * сдвигает начало отсчёта: попросили девятый — дальше идут десятый, одиннадцатый.
 */
object AttemptProgressScale {

    /**
     * @param total длина очереди этого цикла; меньше единицы не бывает.
     * @param queueIndex позиция текущей попытки в очереди, 0-based; −1, если попытки
     *   в шкале нет (её ключ не нашёлся).
     * @param manualBaseOrdinal номер, названный кнопкой «следующий профиль», 1-based;
     *   0 — кнопку не нажимали.
     * @param fallbackOrdinal чем нумеровать, когда позиция неизвестна, 1-based.
     */
    fun ordinal(
        total: Int,
        queueIndex: Int,
        manualBaseOrdinal: Int = 0,
        fallbackOrdinal: Int = 1,
    ): Int {
        val normalizedTotal = total.coerceAtLeast(1)
        if (manualBaseOrdinal > 0) {
            // Выбранный кнопкой профиль встаёт в очередь первым, поэтому его позиция —
            // ноль, а на экране должен остаться названный номер. Дальше номер растёт
            // вместе с очередью и заворачивается на круге.
            val base = manualBaseOrdinal.coerceIn(1, normalizedTotal) - 1
            val offset = queueIndex.coerceAtLeast(0)
            return ((base + offset) % normalizedTotal) + 1
        }
        if (queueIndex >= 0) return (queueIndex + 1).coerceAtMost(normalizedTotal)
        return fallbackOrdinal.coerceIn(1, normalizedTotal)
    }

    /**
     * Знаменатель — длина очереди этого цикла и ничего кроме.
     *
     * Прошлое значение общего поля сюда не входит намеренно: оно принадлежит другой
     * фазе, а уменьшиться `maxOf` не мог в принципе.
     */
    fun total(queueSize: Int, declaredTotal: Int, reachedOrdinal: Int = 0): Int =
        maxOf(queueSize, declaredTotal).coerceAtLeast(reachedOrdinal).coerceAtLeast(1)
}

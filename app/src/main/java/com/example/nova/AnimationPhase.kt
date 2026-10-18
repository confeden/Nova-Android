package com.example.nova

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Чистая математика фаз фоновых анимаций. Всё берётся от времени общих часов
 * ([NovaAnimationClock]), поэтому состояние не хранится в видах: пересоздание вида
 * или возврат из паузы попадают в ту же точку цикла.
 */

/** Фаза цикла в долях [0, 1) по прошедшему времени. Отрицательное время тоже даёт [0, 1). */
internal object SweepPhase {
    fun fraction(elapsedSeconds: Double, cycleSeconds: Double): Float {
        if (cycleSeconds <= 0.0) return 0f
        val x = elapsedSeconds / cycleSeconds
        return (x - floor(x)).toFloat()
    }
}

/**
 * Погружение фона. Один цикл — приближение вдвое за [CYCLE_SECONDS]; второй слой
 * сдвинут на полцикла. Вес второго слоя — sin²(π·фаза), он ноль ровно тогда, когда
 * слой вернулся к 1×, и смена узора в этот момент не видна.
 */
internal object DivePhase {
    const val CYCLE_SECONDS = 60.0

    /** Время погружения в циклах по времени общих часов. */
    fun time(clockSeconds: Double): Double = clockSeconds / CYCLE_SECONDS

    fun cycleOf(t: Double): Int = floor(t).toInt()

    /** Фаза слоя, сдвинутого на [offset] циклов, в долях [0, 1). */
    fun phaseOf(t: Double, offset: Double): Float {
        val x = t + offset
        return (x - floor(x)).toFloat()
    }

    fun weightB(phaseB: Float): Float {
        val s = sin(PI.toFloat() * phaseB)
        return s * s
    }
}

/**
 * Точка, которая идёт с постоянной скоростью и упруго отражается от стенок
 * `[0, length]`. Положение считается по пройденному пути, поэтому оно зависит
 * только от времени, а не от числа кадров, и совпадает с прежним пошаговым отскоком.
 */
internal object WallBounce {
    /**
     * @param start начальная координата
     * @param travel полный путь со знаком (скорость × время)
     * @param length длина отрезка, по которому отскакиваем
     */
    fun position(start: Float, travel: Float, length: Float): Float {
        if (length <= 0f) return 0f
        val period = 2.0 * length
        val unfolded = (start + travel).toDouble()
        val m = ((unfolded % period) + period) % period
        val folded = if (m <= length) m else period - m
        return folded.toFloat()
    }
}

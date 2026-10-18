package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationPhaseTest {

    private val eps = 1e-4f

    @Test
    fun sweepPhaseWrapsIntoUnitInterval() {
        assertEquals(0f, SweepPhase.fraction(0.0, 8.2), eps)
        assertEquals(0.5f, SweepPhase.fraction(4.1, 8.2), eps)
        assertEquals(0.25f, SweepPhase.fraction(8.2 * 2 + 2.05, 8.2), eps)
        // Отрицательное время тоже даёт фазу в [0, 1).
        assertEquals(0.75f, SweepPhase.fraction(-2.05, 8.2), eps)
        assertEquals(0f, SweepPhase.fraction(5.0, 0.0), eps)
    }

    @Test
    fun diveCycleIsOneMinuteOfClockTime() {
        assertEquals(0.0, DivePhase.time(0.0), 1e-9)
        assertEquals(1.0, DivePhase.time(60.0), 1e-9)
        assertEquals(2, DivePhase.cycleOf(DivePhase.time(130.0)))
    }

    @Test
    fun secondLayerIsHalfCycleBehindAndWeightsSumToOne() {
        for (step in 0 until 200) {
            val t = step * 0.37
            val phaseA = DivePhase.phaseOf(t, 0.0)
            val phaseB = DivePhase.phaseOf(t, 0.5)
            assertTrue(phaseA in 0f..1f)
            assertTrue(phaseB in 0f..1f)
            // Вес A = 1 − вес B = sin²(π·фаза A): при сдвиге на полцикла sin² и cos² дают единицу.
            val wA = 1f - DivePhase.weightB(phaseB)
            val s = Math.sin(Math.PI * phaseA.toDouble()).toFloat()
            assertEquals(s * s, wA, 1e-3f)
        }
    }

    @Test
    fun secondLayerIsInvisibleWhenItStartsItsCycle() {
        // Фаза B в нуле — новый узор ставится невидимо (вес нуль).
        assertEquals(0f, DivePhase.weightB(0f), eps)
    }

    @Test
    fun cycleIndexOfSecondLayerIsOffsetByHalf() {
        val t = 3.25
        assertEquals(3, DivePhase.cycleOf(t))
        assertEquals(3, DivePhase.cycleOf(t + 0.5))
        assertEquals(3, DivePhase.cycleOf(3.49))
        assertEquals(3, DivePhase.cycleOf(3.5))
        assertEquals(4, DivePhase.cycleOf(4.0))
    }

    @Test
    fun wallBounceMatchesElasticReflection() {
        // Из 10 вправо до стенки 100 (90), назад на 5 — итого 95.
        assertEquals(95f, WallBounce.position(10f, 95f, 100f), eps)
        assertEquals(100f, WallBounce.position(10f, 90f, 100f), eps)
        // 190 пути: 90 до стенки, 100 назад до нуля — точно в нуле.
        assertEquals(0f, WallBounce.position(10f, 190f, 100f), eps)
        // Влево: из 10 к нулю (10), затем обратно на 5.
        assertEquals(5f, WallBounce.position(10f, -15f, 100f), eps)
        assertEquals(10f, WallBounce.position(10f, 0f, 100f), eps)
        assertEquals(0f, WallBounce.position(10f, 0f, 0f), eps)
    }

    @Test
    fun wallBounceStaysInsideBounds() {
        for (k in -500..500) {
            val p = WallBounce.position(37f, k * 3.7f, 120f)
            assertTrue("p=$p", p >= -eps && p <= 120f + eps)
        }
    }

    @Test
    fun keyedSeedsReuseOnlyWhenAsked() {
        val seeds = KeyedSeeds()
        var made = 0
        val make = { made++; made.toLong() * 1000L }
        val first = seeds.seed("main-dns", reuse = false, make = make)
        // Повторное появление без нажатия — то же зерно, новое не создаётся.
        val reused = seeds.seed("main-dns", reuse = true, make = make)
        assertEquals(first, reused)
        assertEquals(1, made)
        // Нажатие — новое зерно, и оно становится сохранённым.
        val fresh = seeds.seed("main-dns", reuse = false, make = make)
        assertEquals(2, made)
        assertEquals(fresh, seeds.seed("main-dns", reuse = true) { 0L })
        // Чужой ключ не делит зерно.
        assertEquals(99L, seeds.seed("settings-dns", reuse = true) { 99L })
    }
}

package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimationClockTest {

    private var nanos = 0L
    private val clock = AnimationClock { nanos }

    private fun at(seconds: Double) {
        nanos = Math.round(seconds * 1e9)
    }

    /** Кадры с шагом 0.1 с — короче порога паузы, как настоящие кадры. */
    private fun frames(from: Double, to: Double) {
        var t = from
        while (t < to - 1e-9) {
            t += 0.1
            at(t)
            clock.now()
        }
    }

    @Test
    fun stoppedClockDoesNotAdvance() {
        at(0.0)
        clock.now()
        at(5.0)
        assertEquals(0.0, clock.now(), 1e-9)
        assertFalse(clock.isRunning())
    }

    @Test
    fun runningClockAdvancesWithFrames() {
        at(0.0)
        clock.resume()
        at(0.016)
        clock.now()
        at(0.032)
        assertEquals(0.032, clock.now(), 1e-6)
        assertTrue(clock.isRunning())
    }

    @Test
    fun peekNeverAdvances() {
        at(0.0)
        clock.resume()
        frames(0.0, 1.0)
        assertEquals(1.0, clock.peek(), 1e-6)
        assertEquals(1.0, clock.now(), 1e-6)
        at(1.05)
        assertEquals(1.0, clock.peek(), 1e-6)
    }

    @Test
    fun pausedIntervalIsNotCountedAfterResume() {
        // Главный экран идёт 1 с, уходит на настройки на 10 с, возвращается.
        at(0.0)
        clock.resume()
        frames(0.0, 1.0)
        clock.suspend()
        at(11.0)
        clock.resume()
        at(11.1)
        assertEquals(1.1, clock.now(), 1e-6)
    }

    @Test
    fun shortAbsenceWithoutSuspendIsNotCounted() {
        // Вид спрятан без явной остановки: разрыв больше порога не считается.
        at(0.0)
        clock.resume()
        frames(0.0, 0.1)
        at(3.0)
        assertEquals(0.1, clock.now(), 1e-6)
        at(3.05)
        assertEquals(0.15, clock.now(), 1e-6)
    }

    @Test
    fun suspendKeepsElapsedTimeUpToThePause() {
        at(0.0)
        clock.resume()
        frames(0.0, 2.0)
        clock.suspend()
        assertEquals(2.0, clock.peek(), 1e-6)
        assertFalse(clock.isRunning())
    }
}

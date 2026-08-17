package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дефект, ради которого написана ротация: узел, чей обратный поток не
 * возвращается, удерживался бесконечно. Замер — 83,5% потерь за десять минут
 * при том, что все двадцать пять рукопожатий проходили за 37 мс.
 *
 * Отдельно закреплено то, что легко потерять: брошенный узел не должен
 * возвращаться в перебор, спешка не должна сжигать всю личность за секунды, и
 * у бесшовности обязан быть конец — иначе общий отказ сети выглядел бы как
 * бесконечная смена узлов.
 */
class LiveNodeRotationTest {

    private fun candidates(vararg spec: Pair<String, Int>): List<LiveNodeRotation.Candidate> =
        spec.mapIndexed { index, (host, port) ->
            LiveNodeRotation.Candidate(host, port, rank = index)
        }

    private val pool = candidates(
        "8.47.69.6" to 945,
        "8.34.70.4" to 864,
        "8.39.146.3" to 903,
        "8.6.112.6" to 987,
        "8.39.204.9" to 7103,
        "8.47.211.1" to 1701,
    )

    @Test
    fun `уходит на лучшего кандидата и не возвращается на брошенный`() {
        val r = LiveNodeRotation()
        val first = r.decide(100_000L, "8.47.69.6", 945, pool)
        assertTrue(first is LiveNodeRotation.Decision.Switch)
        first as LiveNodeRotation.Decision.Switch
        assertEquals("8.34.70.4:864", first.endpoint)
        assertEquals(1, first.ordinal)
        assertTrue(r.isBurned("8.47.69.6", 945))

        val second = r.decide(200_000L, "8.34.70.4", 864, pool)
        second as LiveNodeRotation.Decision.Switch
        assertEquals("8.39.146.3:903", second.endpoint)
        assertFalse("брошенный узел не должен предлагаться снова", second.endpoint == "8.47.69.6:945")
    }

    @Test
    fun `свежепереключённому узлу даётся время проявить себя`() {
        val r = LiveNodeRotation()
        r.decide(100_000L, "8.47.69.6", 945, pool)
        val tooSoon = r.decide(100_000L + LiveNodeRotation.SETTLE_MS - 1, "8.34.70.4", 864, pool)
        assertTrue(tooSoon is LiveNodeRotation.Decision.Settling)
        assertEquals(1, r.rotationCount)

        val allowed = r.decide(100_000L + LiveNodeRotation.SETTLE_MS, "8.34.70.4", 864, pool)
        assertTrue(allowed is LiveNodeRotation.Decision.Switch)
    }

    @Test
    fun `у бесшовности есть конец`() {
        val r = LiveNodeRotation(maxRotationsPerSession = 2)
        var t = 100_000L
        var host = "8.47.69.6"
        var port = 945
        repeat(2) {
            val d = r.decide(t, host, port, pool)
            d as LiveNodeRotation.Decision.Switch
            host = d.host
            port = d.port
            t += LiveNodeRotation.SETTLE_MS
        }
        val last = r.decide(t, host, port, pool)
        assertTrue(last is LiveNodeRotation.Decision.Exhausted)
        assertTrue((last as LiveNodeRotation.Decision.Exhausted).reason.contains("лимит"))
    }

    @Test
    fun `пустая личность честно сообщает, что идти некуда`() {
        val r = LiveNodeRotation()
        val only = candidates("8.47.69.6" to 945)
        val d = r.decide(100_000L, "8.47.69.6", 945, only)
        assertTrue(d is LiveNodeRotation.Decision.Exhausted)
        assertTrue((d as LiveNodeRotation.Decision.Exhausted).reason.contains("непробованных"))
    }

    @Test
    fun `порядок задаётся рангом, а не позицией в списке`() {
        val r = LiveNodeRotation()
        val ranked = listOf(
            LiveNodeRotation.Candidate("8.34.70.4", 864, rank = 9),
            LiveNodeRotation.Candidate("8.39.146.3", 903, rank = 1),
            LiveNodeRotation.Candidate("8.6.112.6", 987, rank = 5),
        )
        val d = r.decide(100_000L, "8.47.69.6", 945, ranked)
        d as LiveNodeRotation.Decision.Switch
        assertEquals("8.39.146.3:903", d.endpoint)
    }

    @Test
    fun `reset снимает сессионное состояние`() {
        val r = LiveNodeRotation()
        r.decide(100_000L, "8.47.69.6", 945, pool)
        assertEquals(1, r.rotationCount)
        r.reset()
        assertEquals(0, r.rotationCount)
        assertFalse(r.isBurned("8.47.69.6", 945))
        val d = r.decide(100_000L, "8.47.69.6", 945, pool)
        assertTrue("после reset пауза не должна держать", d is LiveNodeRotation.Decision.Switch)
    }

    @Test
    fun `некорректные кандидаты отбрасываются`() {
        val r = LiveNodeRotation()
        val dirty = listOf(
            LiveNodeRotation.Candidate("", 945, rank = 0),
            LiveNodeRotation.Candidate("8.34.70.4", 0, rank = 1),
            LiveNodeRotation.Candidate("8.39.146.3", 903, rank = 2),
        )
        val d = r.decide(100_000L, "8.47.69.6", 945, dirty)
        d as LiveNodeRotation.Decision.Switch
        assertEquals("8.39.146.3:903", d.endpoint)
    }
}

package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Страница ленты (`pages/page-NNN.json`) читается тем же разбором, что и целый список, а порядок
 * экрана и «Авто» — «живые по пингу, не мерянные, мёртвые». Формат задаёт сборщик
 * (`nova_updates/Github list/collector.py`, `pages_of`), а не эта реализация (I28).
 */
class NovaListPagesTest {

    private fun page(vararg servers: String, count: Int = servers.size, complete: Boolean = true): String {
        val proxies = servers.joinToString(",") {
            """{"name":"VLESS_#1_2026nov12","type":"vless","server":"$it","port":443,"uuid":"u-$it"}"""
        }
        val ms = servers.indices.joinToString(",") { (100 + it).toString() }
        return """{"nova_list":{"version":1,"checked_at":"2026-11-12T00:00:00+03:00","count":$count,
            "complete":$complete,"page":1,"pages":3},"proxies":[$proxies],"ms":[$ms]}"""
    }

    @Test
    fun `a page parses like a whole list and keeps checker latency in order`() {
        val nodes = NovaList.parse(page("a.example.com", "b.example.com"))
        assertNotNull(nodes)
        assertEquals(listOf("a.example.com", "b.example.com"), nodes!!.map { it.server })
        assertEquals(listOf(100, 101), nodes.map { it.serverMs })
    }

    @Test
    fun `a page whose count does not match is refused`() {
        assertNull(NovaList.parse(page("a.example.com", count = 2)))
        assertNull(NovaList.parse(page("a.example.com", complete = false)))
    }

    @Test
    fun `order puts live by ping first, then unmeasured in list order, dead last`() {
        val nodes = NovaList.parse(page("a", "b", "c", "d"))!!
        val now = System.currentTimeMillis()
        val pings = mapOf(
            nodes[0].id to NovaList.Ping(-1, false, now),
            nodes[2].id to NovaList.Ping(300, true, now),
            nodes[3].id to NovaList.Ping(120, true, now),
        )
        val order = NovaList.order(nodes, pings).map { it.server }
        assertEquals(listOf("d", "c", "b", "a"), order)
    }

    @Test
    fun `owner constants - 12 hours, batches of 50, at least 25 alive`() {
        assertEquals(12L, NovaList.REFRESH_HOURS)
        assertEquals(50, NovaList.PAGE_SIZE)
        assertEquals(25, NovaList.MIN_ALIVE)
        assertEquals(2, NovaList.CONNECT_FAIL_STRIKES)
    }

    private val now = 1_000_000_000L

    @Test
    fun `next profile is the next lowest ping after the current one, wrapping around`() {
        val nodes = NovaList.parse(page("a", "b", "c", "d"))!!
        val pings = mapOf(
            nodes[0].id to NovaList.Ping(300, true, now),
            nodes[1].id to NovaList.Ping(50, true, now),
            nodes[2].id to NovaList.Ping(120, true, now),
            nodes[3].id to NovaList.Ping(-1, false, now),
        )
        val ordered = NovaList.order(nodes, pings) // b 50, c 120, a 300, d dead
        assertEquals(listOf("c", "a", "d", "b"), NovaList.rotationAfter(ordered, nodes[1].id).map { it.server })
        assertEquals(listOf("d", "b", "c", "a"), NovaList.rotationAfter(ordered, nodes[0].id).map { it.server })
    }

    @Test
    fun `strikes count only failed connections and a success clears them`() {
        val one = NovaList.strikeAfter(null, connected = false, now = now)!!
        assertEquals(1, one.count)
        assertEquals(2, NovaList.strikeAfter(one, connected = false, now = now + 1)!!.count)
        assertNull(NovaList.strikeAfter(one, connected = true, now = now + 1))
        // A strike older than the retention window starts over.
        val stale = NovaList.strikeAfter(one, connected = false, now = now + NovaList.BAD_TTL_HOURS * 3_600_000L + 1)!!
        assertEquals(1, stale.count)
    }

    @Test
    fun `no ping alone never makes a profile unfit`() {
        val nodes = NovaList.parse(page("a", "b", "c"))!!
        val pings = mapOf(
            nodes[0].id to NovaList.Ping(-1, false, now),
            nodes[1].id to NovaList.Ping(-1, false, now),
            nodes[2].id to NovaList.Ping(80, true, now),
        )
        assertTrue(NovaList.unfit(nodes, pings, emptyMap(), now).isEmpty())
    }

    @Test
    fun `unfit needs two real connection failures, no answer, and others answering`() {
        val nodes = NovaList.parse(page("a", "b", "c", "d"))!!
        val twice = NovaList.Strike(NovaList.CONNECT_FAIL_STRIKES, now)
        val once = NovaList.Strike(1, now)
        val pings = mapOf(
            nodes[0].id to NovaList.Ping(-1, false, now),
            nodes[1].id to NovaList.Ping(-1, false, now),
            nodes[2].id to NovaList.Ping(90, true, now),
            // d answers the ping although two connections failed: it stays
            nodes[3].id to NovaList.Ping(70, true, now),
        )
        val strikes = mapOf(nodes[0].id to twice, nodes[1].id to once, nodes[3].id to twice)
        assertEquals(setOf(nodes[0].id), NovaList.unfit(nodes, pings, strikes, now))
    }

    @Test
    fun `nothing is removed while no profile answers at all`() {
        val nodes = NovaList.parse(page("a", "b"))!!
        val pings = nodes.associate { it.id to NovaList.Ping(-1, false, now) }
        val strikes = nodes.associate { it.id to NovaList.Strike(5, now) }
        assertTrue(NovaList.unfit(nodes, pings, strikes, now).isEmpty())
    }

    @Test
    fun `an answer older than the freshness window does not count as alive`() {
        val nodes = NovaList.parse(page("a", "b"))!!
        val old = now - NovaList.PING_STALE_MS - 1
        val pings = mapOf(nodes[0].id to NovaList.Ping(-1, false, now), nodes[1].id to NovaList.Ping(60, true, old))
        val strikes = mapOf(nodes[0].id to NovaList.Strike(2, now))
        assertTrue(NovaList.unfit(nodes, pings, strikes, now).isEmpty())
    }
}

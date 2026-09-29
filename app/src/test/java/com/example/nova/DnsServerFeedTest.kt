package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsServerFeedTest {

    private fun feed(vararg servers: String) = """{"version":1,"servers":[${servers.joinToString(",")}]}"""

    private val good = """{"id":"nova-dns-1","domain":"t.nova-app.eu","ns":"tns.nova-app.eu","method":5,"key":"nova1:0123456789abcdef0123456789abcdef","record":"TXT"}"""

    @Test
    fun `the published feed parses into the server`() {
        val server = DnsServerFeed.parse(feed(good))!!
        assertEquals("t.nova-app.eu", server.domain)
        assertEquals("tns.nova-app.eu", server.nsHost)
        assertEquals(5, server.method)
        assertEquals("TXT", server.record)
    }

    /** Лента — внешние данные: мусор отбраковывается, берётся первый годный. */
    @Test
    fun `broken entries are skipped and the first valid one wins`() {
        val badDomain = good.replace("t.nova-app.eu", "t.nova-app.eu\\nLISTEN_IP")
        val badMethod = good.replace("\"method\":5", "\"method\":9")
        val badNs = good.replace("tns.nova-app.eu", "tns..bad")
        val badKey = good.replace("nova1:0123456789abcdef0123456789abcdef", "a b")
        val second = good.replace("tns.nova-app.eu", "ns2.example.org")
        assertEquals(
            "ns2.example.org",
            DnsServerFeed.parse(feed(badDomain, badMethod, badNs, badKey, second))!!.nsHost,
        )
        assertNull(DnsServerFeed.parse(feed(badDomain)))
        assertNull(DnsServerFeed.parse("not json"))
        assertNull(DnsServerFeed.parse("{}"))
    }

    @Test
    fun `the seed matches its own parser`() {
        val seed = DnsServerFeed.SEED
        val json = """{"domain":"${seed.domain}","ns":"${seed.nsHost}","method":${seed.method},"key":"${seed.key}","record":"${seed.record}"}"""
        assertEquals(seed, DnsServerFeed.parse(feed(json)))
    }

    /**
     * Порты прямой несущей приходят из ленты: какой порт жив — свойство сети, а
     * не сборки. На МегаФоне 2026-09-29 `5353` прошёл, а `443` и `8053` оператор
     * не пропустил вовсе, и набор придётся менять без новой версии.
     */
    @Test
    fun `ports come from the feed and fall back to the default pair`() {
        assertEquals(DnsServerFeed.DEFAULT_PORTS, DnsServerFeed.parse(feed(good))!!.ports)

        val listed = good.replace(""""record":"TXT"""", """"record":"TXT","ports":[53,5353,8053]""")
        assertEquals(listOf(53, 5353, 8053), DnsServerFeed.parse(feed(listed))!!.ports)

        // Негодный порт выпадает поодиночке, а пустой остаток — это умолчание.
        val junk = good.replace(""""record":"TXT"""", """"record":"TXT","ports":[0,70000,5353]""")
        assertEquals(listOf(5353), DnsServerFeed.parse(feed(junk))!!.ports)

        val empty = good.replace(""""record":"TXT"""", """"record":"TXT","ports":[]""")
        assertEquals(DnsServerFeed.DEFAULT_PORTS, DnsServerFeed.parse(feed(empty))!!.ports)
    }

    /**
     * Встроенный профиль рабочий: без своих профилей подключаемся им. IP в нём
     * не зашит — прямую несущую служба узнаёт при подключении по имени NS.
     */
    @Test
    fun `the built-in profile is usable and carries no hard-coded address`() {
        val list = DnsProfileStore.withBuiltIn(DnsProfileList())
        val active = list.active()!!
        assertTrue(active.builtIn)
        assertEquals(DnsProfileImport.Engine.COTTEN, active.engine)
        assertEquals("", active.blockedReason)
        assertTrue(active.key.startsWith("nova1:"))
        assertTrue(active.resolvers.isEmpty())
    }
}

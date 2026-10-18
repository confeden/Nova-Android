package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Подсказки по портам, молчащим узлам, свежесть ленты и варианты обфускации повторов. */
class ProtonRetryHintsTest {

    private val now = 1_000_000_000L
    private val hour = 3_600_000L

    private fun outcomes(
        failed: Map<String, Long> = emptyMap(),
        succeeded: Map<String, Long> = emptyMap(),
        counts: Map<String, Int> = emptyMap(),
    ) = ProtonProfileStore.AttemptOutcomes(failed, succeeded, counts)

    private fun profile(ip: String = "1.1.1.1", port: Int = 443, sni: String = "a.example") = ProtonProfile(
        serverName = "n", country = "NL", city = "c", entryIp = ip, port = port, peerPublicKey = "k=",
        pingMs = -1, pingSource = "", load = 1, sni = sni,
        junkCount = 3, junkMin = 1, junkMax = 3, i1 = "STORED", createdAt = 0L,
    )

    @Test
    fun workedPortWinsOverHash() {
        val hints = ProtonProfileStore.portHints(
            outcomes(succeeded = mapOf("1.1.1.1:1224" to now - hour)),
            now,
        )
        assertEquals(1224, ProtonProfileStore.choosePort("1.1.1.1", hints))
    }

    @Test
    fun workedPortIsDroppedWhenItFailedLater() {
        val hints = ProtonProfileStore.portHints(
            outcomes(
                succeeded = mapOf("1.1.1.1:1224" to now - 2 * hour),
                failed = mapOf("1.1.1.1:1224" to now - hour),
            ),
            now,
        )
        assertNull(hints["1.1.1.1"]?.workedPort)
        assertTrue(1224 in hints.getValue("1.1.1.1").failedPorts)
        assertNotEquals(1224, ProtonProfileStore.choosePort("1.1.1.1", hints))
    }

    @Test
    fun portIsStableForTheSameHostAndSkipsFailedOnes() {
        val first = ProtonProfileStore.choosePort("9.9.9.9", emptyMap())
        assertEquals(first, ProtonProfileStore.choosePort("9.9.9.9", emptyMap()))
        val hints = mapOf("9.9.9.9" to ProtonProfileStore.Companion.PortHint(null, setOf(first)))
        assertNotEquals(first, ProtonProfileStore.choosePort("9.9.9.9", hints))
        assertTrue(ProtonProfileStore.choosePort("9.9.9.9", hints) in ProtonProfileStore.PORTS)
    }

    @Test
    fun failedPortOlderThanMemoryIsForgotten() {
        val hints = ProtonProfileStore.portHints(
            outcomes(failed = mapOf("2.2.2.2:443" to now - ProtonProfileStore.FAILURE_MEMORY_MS - 1)),
            now,
        )
        assertTrue(hints.isEmpty())
    }

    @Test
    fun allPortsFailedStillReturnsAPort() {
        val all = ProtonProfileStore.PORTS.toSet()
        val hints = mapOf("3.3.3.3" to ProtonProfileStore.Companion.PortHint(null, all))
        assertTrue(ProtonProfileStore.choosePort("3.3.3.3", hints) in all)
    }

    @Test
    fun fallbackPortsPutFailedOnesLast() {
        val ordered = listOf(profile(ip = "4.4.4.4", port = 51820))
        val plain = ProtonProfileStore.expandPortFallbacks(ordered, 10)
        assertEquals(listOf(51820, 443, 80), plain.map { it.port })
        val hints = mapOf("4.4.4.4" to ProtonProfileStore.Companion.PortHint(null, setOf(443)))
        val hinted = ProtonProfileStore.expandPortFallbacks(ordered, 10, hints)
        assertEquals(listOf(51820, 80, 88), hinted.map { it.port })
    }

    private fun server(ip: String, lastSeen: Long = 0L, load: Int = 50) =
        ProtonApi.Server("n-$ip", "NL", "c", ip, "k=", load, 0.0, lastSeen)

    @Test
    fun silentHostsGoToTheTailOfThePool() {
        val pool = ProtonProfileStore.mergeNodePools(
            proven = emptyList(),
            primary = listOf(server("1.1.1.1"), server("2.2.2.2"), server("3.3.3.3")),
            extra = emptyList(),
            silentHosts = setOf("1.1.1.1"),
        )
        assertEquals(listOf("2.2.2.2", "3.3.3.3", "1.1.1.1"), pool.map { it.entryIp })
    }

    @Test
    fun silentHostsNeedAFreshFailureWithoutLaterSuccess() {
        val silent = ProtonProfileStore.silentHosts(
            outcomes(
                failed = mapOf(
                    "1.1.1.1:443" to now - hour,
                    "2.2.2.2:443" to now - hour,
                    "3.3.3.3:443" to now - ProtonProfileStore.FAILURE_MEMORY_MS - 1,
                ),
                succeeded = mapOf("2.2.2.2:88" to now - 10),
            ),
            now,
        )
        assertEquals(setOf("1.1.1.1"), silent)
    }

    @Test
    fun fresherFeedNodesComeFirstWithinEqualLoad() {
        val pool = ProtonProfileStore.mergeNodePools(
            proven = emptyList(),
            primary = listOf(server("1.1.1.1", lastSeen = 10), server("2.2.2.2", lastSeen = 30), server("3.3.3.3", lastSeen = 20)),
            extra = emptyList(),
        )
        assertEquals(listOf("2.2.2.2", "3.3.3.3", "1.1.1.1"), pool.map { it.entryIp })
    }

    @Test
    fun feedMergeSortsByLastSeenKeepingGeneratorOrderOnTies() {
        fun node(ip: String, seen: Long) =
            """{"server_name":"n","country":"NL","city":"c","entry_ip":"$ip","peer_public_key":"k$ip","last_seen":$seen}"""
        val feed = ProtonNodeCatalog.parseNodes(
            """{"nodes":[${listOf(node("1.1.1.1", 5), node("2.2.2.2", 9), node("3.3.3.3", 9)).joinToString(",")}]}"""
        )
        assertEquals(9L, feed[1].lastSeen)
        val merged = ProtonNodeCatalog.merge(feed, listOf(server("4.4.4.4")))
        assertEquals(listOf("2.2.2.2", "3.3.3.3", "1.1.1.1", "4.4.4.4"), merged.map { it.entryIp })
    }

    @Test
    fun failCountsAccumulateAndResetOnSuccess() {
        var raw: String? = null
        repeat(3) { raw = ProtonProfileStore.encodeAttemptOutcome(raw, "wifi", "1.1.1.1:443", false, now + it) }
        var one = ProtonProfileStore.decodeAttemptOutcomes(raw, "wifi")
        assertEquals(3, one.failCountByEndpoint["1.1.1.1:443"])
        assertEquals(mapOf("1.1.1.1:443" to 3), ProtonProfileStore.failureCounts(one, now + 10))
        raw = ProtonProfileStore.encodeAttemptOutcome(raw, "wifi", "1.1.1.1:443", true, now + 20)
        one = ProtonProfileStore.decodeAttemptOutcomes(raw, "wifi")
        assertTrue(one.failCountByEndpoint.isEmpty())
        assertTrue(ProtonProfileStore.failureCounts(one, now + 30).isEmpty())
    }

    @Test
    fun oldFileWithoutCountsStillCountsOneFailure() {
        val raw = """{"wifi":{"failed_at":{"1.1.1.1:443":$now},"succeeded_at":{}}}"""
        val one = ProtonProfileStore.decodeAttemptOutcomes(raw, "wifi")
        assertEquals(mapOf("1.1.1.1:443" to 1), ProtonProfileStore.failureCounts(one, now + 1))
    }

    @Test
    fun allClassOutcomesTakeNewestAndLargest() {
        var raw: String? = null
        raw = ProtonProfileStore.encodeAttemptOutcome(raw, "wifi", "1.1.1.1:443", false, 100)
        raw = ProtonProfileStore.encodeAttemptOutcome(raw, "cell", "1.1.1.1:443", false, 200)
        raw = ProtonProfileStore.encodeAttemptOutcome(raw, "cell", "1.1.1.1:443", false, 300)
        val all = ProtonProfileStore.decodeAllOutcomes(raw)
        assertEquals(300L, all.failedAtByEndpoint["1.1.1.1:443"])
        assertEquals(2, all.failCountByEndpoint["1.1.1.1:443"])
    }

    @Test
    fun attemptShapeVariesOnlyJunkAndI1() {
        val p = profile()
        val pool = listOf("b.example", "c.example", "d.example")
        val build: (String) -> String = { "I1:$it" }
        val v0 = ProtonProfileStore.attemptShape(p, 0, pool, build)
        assertEquals(ProtonProfileStore.Companion.AttemptShape(3, 1, 3, "STORED"), v0)
        val v1 = ProtonProfileStore.attemptShape(p, 1, pool, build)
        assertTrue(v1.i1.startsWith("I1:") && v1.i1 != "I1:a.example")
        assertEquals(3, v1.junkCount)
        val v2 = ProtonProfileStore.attemptShape(p, 2, pool, build)
        assertEquals("", v2.i1)
        assertTrue(v2.junkMin <= v2.junkMax)
        val v3 = ProtonProfileStore.attemptShape(p, 3, pool, build)
        assertTrue(v3.i1.startsWith("I1:"))
        assertNotEquals(v1.i1, v3.i1)
        assertEquals(v0, ProtonProfileStore.attemptShape(p, 4, pool, build))
        assertEquals(v1, ProtonProfileStore.attemptShape(p, 5, pool, build))
    }

    @Test
    fun attemptShapeWithoutPoolRebuildsForOwnDomain() {
        val p = profile(sni = "own.example")
        val v1 = ProtonProfileStore.attemptShape(p, 1, emptyList()) { "I1:$it" }
        assertEquals("I1:own.example", v1.i1)
        val blank = ProtonProfileStore.attemptShape(profile(sni = ""), 1, emptyList()) { "I1:$it" }
        assertEquals("", blank.i1)
    }

    @Test
    fun configTextKeepsFixedAwgFieldsOnRetry() {
        val configs = ProtonProfileStore.toVerifiedConfigs(
            listOf(profile()),
            "priv=",
            failureCounts = mapOf("1.1.1.1:443" to 2),
        )
        val raw = configs.single().rawConfig
        assertTrue(raw.contains("S1 = 0") && raw.contains("S2 = 0") && raw.contains("H1 = 1") && raw.contains("H4 = 4"))
        assertTrue(raw.contains("Jc = 5"))
        assertFalse(raw.contains("I1 ="))
        assertEquals("proton|1.1.1.1|443", configs.single().id)
    }
}

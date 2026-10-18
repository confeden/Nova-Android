package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtonNodeFeedTest {

    private fun json(vararg nodes: String) = """{"generated_at":1,"nodes":[${nodes.joinToString(",")}]}"""

    private fun node(ip: String, key: String = "k$ip") =
        """{"server_name":"NL-FREE#1","country":"NL","city":"Amsterdam","entry_ip":"$ip","peer_public_key":"$key","last_seen":5}"""

    @Test
    fun parseSkipsNodesWithoutAddressOrKey() {
        val parsed = ProtonNodeCatalog.parseNodes(json(node("1.1.1.1"), node("", "k"), node("2.2.2.2", "")))
        assertEquals(listOf("1.1.1.1"), parsed.map { it.entryIp })
        assertEquals("NL", parsed[0].country)
    }

    @Test
    fun mergePutsFeedFirstAndDropsBundledDuplicates() {
        val feed = ProtonNodeCatalog.parseNodes(json(node("3.3.3.3"), node("1.1.1.1")))
        val bundled = ProtonNodeCatalog.parseNodes(json(node("1.1.1.1"), node("4.4.4.4")))
        val merged = ProtonNodeCatalog.merge(feed, bundled)
        assertEquals(listOf("3.3.3.3", "1.1.1.1", "4.4.4.4"), merged.map { it.entryIp })
    }

    @Test
    fun mergeWithEmptyFeedIsBundled() {
        val bundled = ProtonNodeCatalog.parseNodes(json(node("1.1.1.1")))
        assertTrue(ProtonNodeCatalog.merge(emptyList(), bundled) === bundled)
    }
}

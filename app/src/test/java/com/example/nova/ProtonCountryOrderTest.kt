package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты переупорядочивания профилей Proton по выбранной стране [ProtonProfileStore.orderByCountry].
 */
class ProtonCountryOrderTest {

    private fun profile(country: String, serverName: String = "node-$country"): ProtonProfile =
        ProtonProfile(
            serverName = serverName,
            country = country,
            city = "City",
            entryIp = "10.2.0.1",
            port = 51820,
            peerPublicKey = "key==",
            pingMs = 50,
            pingSource = "",
            load = 10,
            sni = "",
            junkCount = 0,
            junkMin = 0,
            junkMax = 0,
            i1 = "",
            createdAt = 1000L,
        )

    @Test
    fun `profiles of requested country move to front preserving relative order in both halves`() {
        val nl1 = profile("NL", "nl-1")
        val us1 = profile("US", "us-1")
        val nl2 = profile("NL", "nl-2")
        val pl1 = profile("PL", "pl-1")
        val input = listOf(nl1, us1, nl2, pl1)

        val ordered = ProtonProfileStore.orderByCountry(input, "NL")
        assertEquals(listOf(nl1, nl2, us1, pl1), ordered)
    }

    @Test
    fun `empty country string returns list unchanged`() {
        val input = listOf(profile("NL"), profile("US"))
        val result = ProtonProfileStore.orderByCountry(input, "")
        assertSame(input, result)
        assertEquals(input, result)
    }

    @Test
    fun `country with no matching profile returns list unchanged`() {
        val input = listOf(profile("NL"), profile("US"), profile("PL"))
        val result = ProtonProfileStore.orderByCountry(input, "DE")
        assertSame(input, result)
        assertEquals(input, result)
    }

    @Test
    fun `country match is case insensitive and tolerates whitespace`() {
        val nl1 = profile("NL", "nl-1")
        val us1 = profile("US", "us-1")
        val input = listOf(us1, nl1)

        val ordered = ProtonProfileStore.orderByCountry(input, " nl ")
        assertEquals(listOf(nl1, us1), ordered)
    }

    @Test
    fun `invalid country returns list unchanged`() {
        val input = listOf(profile("NL"), profile("US"))
        for (invalid in listOf("NLD", "n", "", "   ", "1", "123")) {
            val result = ProtonProfileStore.orderByCountry(input, invalid)
            assertSame("failed for $invalid", input, result)
            assertEquals("failed for $invalid", input, result)
        }
    }

    @Test
    fun `empty profile list returns empty list`() {
        val empty = emptyList<ProtonProfile>()
        val result = ProtonProfileStore.orderByCountry(empty, "NL")
        assertTrue(result.isEmpty())
        assertSame(empty, result)
    }
}

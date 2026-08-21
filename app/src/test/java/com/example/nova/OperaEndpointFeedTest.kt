package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OperaEndpointFeedTest {

    @Test
    fun `разбирает регионы и приводит US к AM`() {
        val parsed = OperaEndpointFeed.parseFeed(
            """
            {
              "updated_at": "2026-08-21T09:00:00Z",
              "regions": {
                "EU": ["77.111.101.1:443", "77.111.101.2:443"],
                "US": ["185.93.1.1:443"]
              }
            }
            """.trimIndent()
        )

        assertEquals(setOf("EU", "AM"), parsed.keys)
        assertEquals(listOf("77.111.101.1:443", "77.111.101.2:443"), parsed["EU"])
        assertEquals(listOf("185.93.1.1:443"), parsed["AM"])
    }

    @Test
    fun `чистит схему и путь, отбрасывает мусор и повторы`() {
        val parsed = OperaEndpointFeed.parseFeed(
            """
            {"regions": {"EU": [
              "https://77.111.101.1:443/proxy",
              "77.111.101.1:443",
              "77.111.101.3",
              "77.111.101.4:70000",
              "плохой хост:443",
              ""
            ]}}
            """.trimIndent()
        )

        assertEquals(listOf("77.111.101.1:443"), parsed["EU"])
    }

    @Test
    fun `незнакомый регион не попадает в кэш`() {
        // Важно именно отбросить: ключ кэша для неизвестного региона сваливается
        // в EU, и чужие адреса легли бы в европейский список.
        val parsed = OperaEndpointFeed.parseFeed(
            """{"regions": {"XX": ["1.2.3.4:443"], "AS": ["5.6.7.8:443"]}}"""
        )

        assertEquals(setOf("AS"), parsed.keys)
    }

    @Test
    fun `битый или пустой файл не роняет разбор`() {
        assertTrue(OperaEndpointFeed.parseFeed("не json").isEmpty())
        assertTrue(OperaEndpointFeed.parseFeed("{}").isEmpty())
        assertTrue(OperaEndpointFeed.parseFeed("""{"regions": {}}""").isEmpty())
        assertTrue(OperaEndpointFeed.parseFeed("""{"regions": {"EU": []}}""").isEmpty())
        assertTrue(OperaEndpointFeed.parseFeed("""{"regions": {"EU": "1.2.3.4:443"}}""").isEmpty())
    }
}

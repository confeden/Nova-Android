package com.example.nova

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порядок несущих и форма конфигурации решают, поднимется ли DNS-туннель вообще,
 * и проверяются они здесь, а не наблюдением на устройстве: на живой сети виден
 * только итог «подключилось / не подключилось», а порядок — нет.
 */
class DnsTunnelEndpointTest {

    private fun endpoint(
        zone: String = "t.example.com",
        pubKey: String = "aabb",
        extras: List<DnsTunnelResolver> = emptyList(),
    ) = DnsTunnelEndpoint(zone = zone, pubKey = pubKey, extraResolvers = extras)

    @Test
    fun `endpoint without zone or key is not usable`() {
        assertFalse(DnsTunnelEndpoint().isUsable)
        assertFalse(endpoint(zone = "").isUsable)
        assertFalse(endpoint(pubKey = "  ").isUsable)
        assertTrue(endpoint().isUsable)
    }

    /**
     * Резолверы сети идут первыми, и это не вкус. Под белым списком они
     * whitelisted по определению, а на обычной сети просто ближе: замер на
     * МегаФоне дал 21-28 мс против 58-74 мс до 8.8.8.8.
     */
    @Test
    fun `network resolvers lead, then the national one, then the user's`() {
        val order = DnsTunnelConfig.resolverOrder(
            systemResolvers = listOf("10.161.158.250", "10.93.233.196"),
            endpoint = endpoint(extras = listOf(DnsTunnelResolver("udp", "77.88.8.8"))),
        )
        assertEquals(
            listOf(
                "10.161.158.250:53",
                "10.93.233.196:53",
                DnsTunnelConfig.NATIONAL_RESOLVER + ":53",
                DnsTunnelConfig.NATIONAL_RESOLVER_SECOND + ":53",
                "77.88.8.8:53",
            ) + DnsTunnelConfig.PUBLIC_FALLBACK_RESOLVERS
                // Уже стоит выше, как адрес пользователя: повтор выпадает.
                .filterNot { it == "77.88.8.8" }
                .map { "$it:53" },
            order.map { it.addr },
        )
    }

    /**
     * Национальный резолвер стоит в переборе всегда: под белым списком он
     * доступен по построению, и замером подтверждено, что он сам ходит на
     * произвольный зарубежный авторитативный сервер.
     */
    @Test
    fun `the national resolver is always in the walk`() {
        val order = DnsTunnelConfig.resolverOrder(emptyList(), endpoint())
        assertEquals(DnsTunnelConfig.NATIONAL_RESOLVER + ":53", order.first().addr)
        assertEquals(DnsTunnelConfig.NATIONAL_RESOLVER_SECOND + ":53", order[1].addr)
    }

    /**
     * Публичные резолверы идут последними: под белым списком они бесполезны, а
     * на обычной сети спасают перебор — но только после операторских.
     */
    @Test
    fun `public resolvers close the walk, never open it`() {
        val order = DnsTunnelConfig.resolverOrder(listOf("10.0.0.1"), endpoint())
        assertEquals("10.0.0.1:53", order.first().addr)
        assertEquals(
            DnsTunnelConfig.PUBLIC_FALLBACK_RESOLVERS.map { "$it:53" },
            order.takeLast(DnsTunnelConfig.PUBLIC_FALLBACK_RESOLVERS.size).map { it.addr },
        )
    }

    /** Одна и та же несущая дважды — это вдвое больше перебора и ни одного нового исхода. */
    @Test
    fun `duplicates are dropped`() {
        val order = DnsTunnelConfig.resolverOrder(
            systemResolvers = listOf("1.1.1.1", "1.1.1.1", DnsTunnelConfig.NATIONAL_RESOLVER),
            endpoint = endpoint(extras = listOf(DnsTunnelResolver("udp", "1.1.1.1:53"))),
        )
        assertEquals(
            listOf(
                "1.1.1.1:53",
                DnsTunnelConfig.NATIONAL_RESOLVER + ":53",
                DnsTunnelConfig.NATIONAL_RESOLVER_SECOND + ":53",
            ) + DnsTunnelConfig.PUBLIC_FALLBACK_RESOLVERS
                .filterNot { it == "1.1.1.1" }
                .map { "$it:53" },
            order.map { it.addr },
        )
    }

    /**
     * DoT отбрасывается молча только потому, что ядро всё равно откажет вслух:
     * его сокет библиотека открывает сама, пометить его нечем, и внутри
     * VpnService он ушёл бы в поднимаемый туннель (G154, G200).
     */
    @Test
    fun `DoT never reaches the core`() {
        val order = DnsTunnelConfig.resolverOrder(
            systemResolvers = emptyList(),
            endpoint = endpoint(extras = listOf(DnsTunnelResolver("dot", "1.1.1.1:853"))),
        )
        assertTrue(order.none { it.type == "dot" })
    }

    @Test
    fun `DoH survives with its URL untouched`() {
        val order = DnsTunnelConfig.resolverOrder(
            systemResolvers = emptyList(),
            endpoint = endpoint(extras = listOf(DnsTunnelResolver("doh", "https://dns.google/dns-query"))),
        )
        assertTrue(order.any { it.type == "doh" && it.addr == "https://dns.google/dns-query" })
    }

    /** Адреса приходят из LinkProperties голыми, а ядро ждёт host:port. */
    @Test
    fun `default port is added, and only where it is missing`() {
        assertEquals("8.8.8.8:53", DnsTunnelConfig.withDefaultPort("8.8.8.8"))
        assertEquals("8.8.8.8:5353", DnsTunnelConfig.withDefaultPort("8.8.8.8:5353"))
        assertEquals("[2001:4860:4860::8888]:53", DnsTunnelConfig.withDefaultPort("2001:4860:4860::8888"))
        assertEquals("[2001:4860:4860::8888]:53", DnsTunnelConfig.withDefaultPort("[2001:4860:4860::8888]"))
        assertEquals("[2001:4860:4860::8888]:853", DnsTunnelConfig.withDefaultPort("[2001:4860:4860::8888]:853"))
        assertEquals("", DnsTunnelConfig.withDefaultPort("   "))
    }

    @Test
    fun `core config carries every field the engine reads`() {
        val ep = DnsTunnelEndpoint(
            zone = "t.example.com",
            pubKey = "aabbcc",
            dnsttCompat = false,
            maxQnameLen = 140,
            rps = 12.5,
            recordType = "cname",
        )
        val json = JSONObject(
            DnsTunnelConfig.toCoreJson(ep, listOf(DnsTunnelResolver("udp", "1.1.1.1:53")), 9_000)
        )
        assertEquals("t.example.com", json.getString("zone"))
        assertEquals("aabbcc", json.getString("pubkey"))
        assertFalse(json.getBoolean("dnsttCompat"))
        assertEquals(140, json.getInt("maxQnameLen"))
        assertEquals(12.5, json.getDouble("rps"), 0.0001)
        assertEquals("cname", json.getString("recordType"))
        assertEquals(9_000, json.getInt("probeTimeoutMs"))
        val resolvers = json.getJSONArray("resolvers")
        assertEquals(1, resolvers.length())
        assertEquals("udp", resolvers.getJSONObject(0).getString("type"))
        assertEquals("1.1.1.1:53", resolvers.getJSONObject(0).getString("addr"))
    }
}

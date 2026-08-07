package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Классификация интерфейсов — самое хрупкое место раздачи: имена задаёт вендор,
 * и ошибка здесь означает либо что прокси не слушает там, где нужно, либо что он
 * слушает на модемном интерфейсе.
 */
class GatewayEndpointsTest {

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address

    @Test
    fun `модемные и служебные интерфейсы не могут быть шлюзом`() {
        listOf(
            "lo",
            "rmnet_data0",
            "rmnet0",
            "ccmni0",
            "wwan0",
            "tun0",
            "tap0",
            "dummy0",
            "v4-rmnet_data0",
            "clat4",
            "ppp0",
            "sit0",
            "ip6tnl0",
        ).forEach { name ->
            assertTrue("$name не должен считаться шлюзом", GatewayEndpoints.isNeverGateway(name))
        }
    }

    @Test
    fun `интерфейсы раздачи проходят фильтр`() {
        listOf("ap0", "wlan1", "swlan0", "rndis0", "bt-pan", "p2p-wlan0-0", "usb0").forEach { name ->
            assertFalse("$name должен быть допустим", GatewayEndpoints.isNeverGateway(name))
        }
    }

    @Test
    fun `тип раздачи определяется по имени интерфейса`() {
        assertEquals(GatewayKind.WIFI_AP, GatewayEndpoints.classifyDownstream("ap0"))
        assertEquals(GatewayKind.WIFI_AP, GatewayEndpoints.classifyDownstream("wlan1"))
        assertEquals(GatewayKind.WIFI_AP, GatewayEndpoints.classifyDownstream("swlan0"))
        assertEquals(GatewayKind.USB, GatewayEndpoints.classifyDownstream("rndis0"))
        assertEquals(GatewayKind.USB, GatewayEndpoints.classifyDownstream("usb0"))
        assertEquals(GatewayKind.USB, GatewayEndpoints.classifyDownstream("ncm0"))
        assertEquals(GatewayKind.BLUETOOTH, GatewayEndpoints.classifyDownstream("bt-pan"))
        assertEquals(GatewayKind.WIFI_DIRECT, GatewayEndpoints.classifyDownstream("p2p-wlan0-0"))
        assertEquals(GatewayKind.ETHERNET_TETHER, GatewayEndpoints.classifyDownstream("eth1"))
    }

    @Test
    fun `незнакомое имя всё равно годится для раздачи`() {
        // Вендор волен назвать интерфейс как угодно. Лучше показать его как «Раздача»,
        // чем молча не слушать на нём.
        assertEquals(GatewayKind.OTHER_TETHER, GatewayEndpoints.classifyDownstream("vendor0"))
        assertTrue(GatewayEndpoints.classifyDownstream("novanet0").downstream)
        // А вот softap0 узнаётся именно как точка доступа, а не как «прочее».
        assertEquals(GatewayKind.WIFI_AP, GatewayEndpoints.classifyDownstream("softap0"))
    }

    @Test
    fun `регистр имени не влияет на разбор`() {
        assertTrue(GatewayEndpoints.isNeverGateway("RMNET_DATA0"))
        assertEquals(GatewayKind.USB, GatewayEndpoints.classifyDownstream("RNDIS0"))
    }

    @Test
    fun `служебный диапазон 464XLAT не считается адресом раздачи`() {
        assertTrue(GatewayEndpoints.isClatAddress(ipv4("192.0.0.4")))
        assertTrue(GatewayEndpoints.isClatAddress(ipv4("192.0.0.7")))
        assertFalse(GatewayEndpoints.isClatAddress(ipv4("192.0.0.8")))
        assertFalse(GatewayEndpoints.isClatAddress(ipv4("192.168.43.1")))
        assertFalse(GatewayEndpoints.isClatAddress(ipv4("192.168.42.129")))
    }

    @Test
    fun `типичные адреса раздачи Android распознаются как downstream`() {
        // 192.168.43.1 — точка доступа, 192.168.42.129 — USB, 192.168.44.1 — Bluetooth,
        // 192.168.49.1 — Wi-Fi Direct. Ни один из них не должен отсеяться.
        listOf("192.168.43.1", "192.168.42.129", "192.168.44.1", "192.168.49.1").forEach { host ->
            val address = ipv4(host)
            assertFalse(host, GatewayEndpoints.isClatAddress(address))
            assertFalse(host, address.isLinkLocalAddress)
            assertFalse(host, address.isLoopbackAddress)
        }
    }
}

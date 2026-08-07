package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Дефект, ради которого написаны эти проверки: туннель рвался на ровном месте.
 * В признак сети входил BSSID — MAC конкретной точки доступа, — и любой роуминг
 * внутри одной Wi-Fi сети выглядел как смена сети со всеми последствиями:
 * переподключением и обрывом трафика всех протоколов.
 */
class NetworkIdentityTest {

    private fun wifi(ssid: String, bssid: String, gateway: String = "192.168.1.1") =
        NetworkIdentity.signature("wifi", "wlan0", ssid, bssid, gateway)

    @Test
    fun `роуминг между точками одной сети не считается сменой сети`() {
        val nearAccessPoint = wifi("HomeNet", "aa:bb:cc:dd:ee:01")
        val farAccessPoint = wifi("HomeNet", "aa:bb:cc:dd:ee:02")
        assertEquals(nearAccessPoint, farAccessPoint)
    }

    @Test
    fun `переход между диапазонами одной точки не считается сменой сети`() {
        // 2.4 и 5 ГГц одной точки — это разные BSSID при одном SSID.
        assertEquals(wifi("HomeNet", "aa:bb:cc:dd:ee:01"), wifi("HomeNet", "aa:bb:cc:dd:ee:11"))
    }

    @Test
    fun `другая сеть Wi-Fi остаётся сменой сети`() {
        assertNotEquals(wifi("HomeNet", "aa:bb:cc:dd:ee:01"), wifi("CafeNet", "aa:bb:cc:dd:ee:01"))
    }

    @Test
    fun `смена транспорта остаётся сменой сети`() {
        val onWifi = wifi("HomeNet", "aa:bb:cc:dd:ee:01")
        val onCellular = NetworkIdentity.signature("cell", "rmnet0", "", "", "10.0.0.1")
        assertNotEquals(onWifi, onCellular)
    }

    @Test
    fun `без имени сети опорой становится шлюз, а не точка доступа`() {
        // Без разрешений Android скрывает SSID, и BSSID остаётся единственным
        // «различием» — но он-то как раз и меняется при роуминге. Шлюз одинаков для
        // всех точек одной сети, поэтому опираемся на него.
        val first = NetworkIdentity.signature("wifi", "wlan0", "", "aa:bb:cc:dd:ee:01", "192.168.1.1")
        val second = NetworkIdentity.signature("wifi", "wlan0", "", "aa:bb:cc:dd:ee:02", "192.168.1.1")
        assertEquals(first, second)
        assertNotEquals(
            first,
            NetworkIdentity.signature("wifi", "wlan0", "", "aa:bb:cc:dd:ee:01", "10.0.0.1"),
        )
    }

    @Test
    fun `без имени и шлюза опорой остаётся точка доступа`() {
        // Опознавать хоть как-то лучше, чем не опознавать вовсе.
        val first = NetworkIdentity.signature("wifi", "wlan0", "", "aa:bb:cc:dd:ee:01", "")
        val second = NetworkIdentity.signature("wifi", "wlan0", "", "aa:bb:cc:dd:ee:02", "")
        assertNotEquals(first, second)
    }

    @Test
    fun `заглушки Android вместо данных Wi-Fi не попадают в подпись`() {
        assertEquals("", NetworkIdentity.sanitizeWifiValue("<unknown ssid>"))
        assertEquals("", NetworkIdentity.sanitizeWifiValue("02:00:00:00:00:00"))
        assertEquals("", NetworkIdentity.sanitizeWifiValue(null))
        assertEquals("HomeNet", NetworkIdentity.sanitizeWifiValue("\"HomeNet\""))
    }

    @Test
    fun `заглушка вместо имени сети не мешает опознать её по шлюзу`() {
        // Ровно то, что происходит без разрешения на местоположение: имя и MAC
        // скрыты, и подпись обязана опереться на шлюз.
        val ssid = NetworkIdentity.sanitizeWifiValue("<unknown ssid>")
        val bssid = NetworkIdentity.sanitizeWifiValue("02:00:00:00:00:00")
        assertEquals(
            "wifi|wlan0|192.168.1.1",
            NetworkIdentity.signature("wifi", "wlan0", ssid, bssid, "192.168.1.1"),
        )
    }
}

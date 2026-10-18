package com.example.nova

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Память подбора по сетям: ключ не должен выдавать имя точки доступа, а решение «этот
 * домен здесь работает / здесь подвёл» — переживать запись в файл и обратно.
 */
class NetworkI1MemoryTest {

    private val net = "wifi:ba7816bf"

    @Test
    fun wifiKeyHashesTheNameAndNeverContainsIt() {
        // SHA-256("abc") начинается с ba7816bf — вектор из FIPS 180-2, не из нашего кода.
        assertEquals("wifi:ba7816bf", NetworkI1Memory.networkKey("wifi", "abc", "192.168.0.1", ""))
        assertFalse(NetworkI1Memory.networkKey("wifi", "HomeNet", "", "")!!.contains("HomeNet"))
    }

    @Test
    fun wifiFallsBackToGatewayWhenNameIsHidden() {
        val byGateway = NetworkI1Memory.networkKey("wifi", "", "abc", "")
        assertEquals("wifi:ba7816bf", byGateway)
        assertNull(NetworkI1Memory.networkKey("wifi", " ", "", ""))
    }

    @Test
    fun cellKeyIsOperatorCodeAndRejectsGarbage() {
        assertEquals("cell:25002", NetworkI1Memory.networkKey("cell", "", "", "25002"))
        assertEquals("cell:310260", NetworkI1Memory.networkKey("cell", "", "", " 310260 "))
        assertNull(NetworkI1Memory.networkKey("cell", "", "", ""))
        assertNull(NetworkI1Memory.networkKey("cell", "", "", "abc12"))
    }

    @Test
    fun ethernetHasKeyOtherAndUnknownDoNot() {
        assertEquals("eth", NetworkI1Memory.networkKey("eth", "", "", ""))
        assertNull(NetworkI1Memory.networkKey("other", "x", "y", "25002"))
        assertNull(NetworkI1Memory.networkKey(null, "x", "y", "25002"))
    }

    @Test
    fun differentNetworksGetDifferentKeys() {
        assertNotEquals(
            NetworkI1Memory.networkKey("wifi", "a", "", ""),
            NetworkI1Memory.networkKey("wifi", "b", "", ""),
        )
    }

    @Test
    fun bestIsRememberedPerNetwork() {
        var m = NetworkI1Memory.Memory()
        m = NetworkI1Memory.record(m, net, "vk.com", true, 10)
        assertEquals("vk.com", NetworkI1Memory.best(m, net))
        assertNull(NetworkI1Memory.best(m, "cell:25002"))
        assertNull(NetworkI1Memory.best(m, null))
    }

    @Test
    fun moreSuccessesBeatNewerSingleSuccess() {
        var m = NetworkI1Memory.Memory()
        m = NetworkI1Memory.record(m, net, "vk.com", true, 10)
        m = NetworkI1Memory.record(m, net, "vk.com", true, 11)
        m = NetworkI1Memory.record(m, net, "ya.ru", true, 12)
        assertEquals("vk.com", NetworkI1Memory.best(m, net))
    }

    @Test
    fun twoFailuresWithoutSuccessDemote() {
        var m = NetworkI1Memory.Memory()
        m = NetworkI1Memory.record(m, net, "vk.com", false, 1)
        assertTrue(NetworkI1Memory.demoted(m, net).isEmpty())
        m = NetworkI1Memory.record(m, net, "vk.com", false, 2)
        assertEquals(setOf("vk.com"), NetworkI1Memory.demoted(m, net))
        assertNull(NetworkI1Memory.best(m, net))
    }

    @Test
    fun failuresAreScopedToTheNetwork() {
        var m = NetworkI1Memory.Memory()
        m = NetworkI1Memory.record(m, net, "vk.com", false, 1)
        m = NetworkI1Memory.record(m, net, "vk.com", false, 2)
        m = NetworkI1Memory.record(m, "cell:25002", "vk.com", true, 3)
        assertEquals("vk.com", NetworkI1Memory.best(m, "cell:25002"))
        assertTrue(NetworkI1Memory.demoted(m, "cell:25002").isEmpty())
    }

    @Test
    fun successResetsFailureStreakAndLongStreakAfterSuccessDemotes() {
        var m = NetworkI1Memory.Memory()
        m = NetworkI1Memory.record(m, net, "vk.com", true, 1)
        m = NetworkI1Memory.record(m, net, "vk.com", false, 2)
        assertEquals("vk.com", NetworkI1Memory.best(m, net))
        m = NetworkI1Memory.record(m, net, "vk.com", false, 3)
        assertNull(NetworkI1Memory.best(m, net))
        m = NetworkI1Memory.record(m, net, "vk.com", true, 4)
        assertEquals("vk.com", NetworkI1Memory.best(m, net))
    }

    @Test
    fun blankKeyOrNameChangesNothing() {
        val m = NetworkI1Memory.Memory()
        assertEquals(m, NetworkI1Memory.record(m, null, "vk.com", true, 1))
        assertEquals(m, NetworkI1Memory.record(m, " ", "vk.com", true, 1))
        assertEquals(m, NetworkI1Memory.record(m, net, " ", true, 1))
    }

    @Test
    fun networksAreBoundedAndOldestIsEvicted() {
        var m = NetworkI1Memory.Memory()
        for (i in 0 until NetworkI1Memory.MAX_NETWORKS + 5) {
            m = NetworkI1Memory.record(m, "wifi:%08x".format(i), "vk.com", true, i.toLong())
        }
        assertEquals(NetworkI1Memory.MAX_NETWORKS, m.networks.size)
        assertNull(NetworkI1Memory.best(m, "wifi:%08x".format(0)))
        assertEquals("vk.com", NetworkI1Memory.best(m, "wifi:%08x".format(NetworkI1Memory.MAX_NETWORKS + 4)))
    }

    @Test
    fun touchedNetworkSurvivesEviction() {
        var m = NetworkI1Memory.Memory()
        for (i in 0 until NetworkI1Memory.MAX_NETWORKS) {
            m = NetworkI1Memory.record(m, "wifi:%08x".format(i), "vk.com", true, i.toLong())
        }
        // Самая давняя сеть снова в деле — вытеснена должна быть следующая за ней.
        m = NetworkI1Memory.record(m, "wifi:%08x".format(0), "vk.com", true, 100)
        m = NetworkI1Memory.record(m, "wifi:ffffffff", "vk.com", true, 101)
        assertEquals("vk.com", NetworkI1Memory.best(m, "wifi:%08x".format(0)))
        assertNull(NetworkI1Memory.best(m, "wifi:%08x".format(1)))
    }

    @Test
    fun domainsPerNetworkAreBounded() {
        var m = NetworkI1Memory.Memory()
        for (i in 0 until NetworkI1Memory.MAX_SNI_PER_NETWORK + 4) {
            m = NetworkI1Memory.record(m, net, "d$i.ru", true, i.toLong())
        }
        assertEquals(NetworkI1Memory.MAX_SNI_PER_NETWORK, m.networks.single().stats.size)
    }

    @Test
    fun jsonRoundTripKeepsEverythingAndSurvivesGarbage() {
        var m = NetworkI1Memory.Memory()
        m = NetworkI1Memory.record(m, net, "vk.com", true, 5)
        m = NetworkI1Memory.record(m, net, "ya.ru", false, 6)
        val restored = NetworkI1Memory.fromJson(JSONObject(NetworkI1Memory.toJson(m).toString()))
        assertEquals(m, restored)
        assertEquals(NetworkI1Memory.Memory(), NetworkI1Memory.fromJson(null))
        assertEquals(NetworkI1Memory.Memory(), NetworkI1Memory.fromJson(JSONObject("""{"n":[1,{"k":""},{"s":[]}]}""")))
    }
}

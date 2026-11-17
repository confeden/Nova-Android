package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NovaVpnSessionLabelsTest {
    @Test
    fun recognizesEveryServiceSessionWithoutOwnerUid() {
        mapOf(
            "NovaVPN" to "WARP",
            "NovaVPN-stop" to "WARP",
            "NovaOperaVPN" to "OPERA",
            "NovaVlessVPN" to "VLESS",
            "NovaListVPN" to "VLESS-NOVA",
            "NovaDnsVPN" to "DNS",
            "NovaTorVPN" to "TOR",
        ).forEach { (label, backend) ->
            assertEquals(label, backend, NovaVpnSessionLabels.backendFor(label))
            assertEquals(label, backend, NovaVpnSessionLabels.backendFor("  ${label.lowercase()}  "))
            assertEquals(label, backend, NovaVpnSessionLabels.backendFor("VpnTransportInfo{sessionId=$label, type=1}"))
        }
    }

    @Test
    fun rejectsForeignAndPartialSessionNames() {
        listOf("", "WARP", "OtherVPN", "FakeNovaVPN", "NovaVPNExtra", "NovaListVPN-other").forEach {
            assertFalse(it, NovaVpnSessionLabels.isNova(it))
        }
    }
}

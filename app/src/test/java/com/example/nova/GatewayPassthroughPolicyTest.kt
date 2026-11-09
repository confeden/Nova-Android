package com.example.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPassthroughPolicyTest {

    @Test
    fun passthroughAlwaysAllowsEgress() {
        assertTrue(GatewayPassthroughPolicy.egressAllowed(connected = false, allowDirect = false, passthrough = true))
        assertFalse(GatewayPassthroughPolicy.tunnelBlocksTraffic(connected = false, allowDirect = false, passthrough = true))
    }

    @Test
    fun withoutPassthroughTheOldGateIsKept() {
        assertTrue(GatewayPassthroughPolicy.egressAllowed(connected = true, allowDirect = false, passthrough = false))
        assertTrue(GatewayPassthroughPolicy.egressAllowed(connected = false, allowDirect = true, passthrough = false))
        assertFalse(GatewayPassthroughPolicy.egressAllowed(connected = false, allowDirect = false, passthrough = false))
        assertTrue(GatewayPassthroughPolicy.tunnelBlocksTraffic(connected = false, allowDirect = false, passthrough = false))
    }

    @Test
    fun passthroughSkipsOperaUpstream() {
        assertTrue(GatewayPassthroughPolicy.useOperaUpstream("OPERA-EU", passthrough = false))
        assertTrue(GatewayPassthroughPolicy.useOperaUpstream(" opera ", passthrough = false))
        assertFalse(GatewayPassthroughPolicy.useOperaUpstream("OPERA-EU", passthrough = true))
        assertFalse(GatewayPassthroughPolicy.useOperaUpstream("WARP", passthrough = false))
    }
}

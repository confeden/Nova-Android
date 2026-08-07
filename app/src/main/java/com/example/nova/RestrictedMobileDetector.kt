package com.example.nova

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.InetSocketAddress

object RestrictedMobileDetector {
    private val probeTargets = listOf(
        "1.1.1.1" to 443,
        "1.0.0.1" to 443,
        "8.8.8.8" to 443,
    )

    fun detect(connectivityManager: ConnectivityManager, network: Network?): Boolean? {
        if (network == null) return null
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val reachable = probeTargets.any { (host, port) ->
            tcpProbe(network, host, port, 350)
        }
        return !reachable
    }

    fun buildNetworkId(network: Network?): String? {
        return network?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun tcpProbe(
        network: Network,
        host: String,
        port: Int,
        timeoutMs: Int,
    ): Boolean {
        return try {
            network.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                socket.isConnected
            }
        } catch (_: Throwable) {
            false
        }
    }
}

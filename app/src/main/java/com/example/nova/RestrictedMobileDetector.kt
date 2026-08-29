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

    fun detect(connectivityManager: ConnectivityManager, network: Network?): Boolean? =
        detect(connectivityManager, network, requireCellular = true)

    /**
     * @param requireCellular исходное поведение: отвечать только про сотовую сеть.
     *        Режим «белого списка» бывает и на Wi-Fi — в гостевых, корпоративных и
     *        гостиничных сетях, — и для выбора SNI это ровно тот же случай, поэтому
     *        оттуда вызывается с `false`. Прочие потребители ответа (маскировка
     *        WARP, политика MASQUE) настроены на сотовую сеть, и расширять их
     *        поведение заодно нельзя.
     */
    fun detect(
        connectivityManager: ConnectivityManager,
        network: Network?,
        requireCellular: Boolean,
    ): Boolean? {
        if (network == null) return null
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (requireCellular && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return null
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        // 700 мс, а не 350: сотовое радио после простоя просыпается 100–500 мс (RRC),
        // и на этом времени первая проба не успевала соединиться на совершенно
        // обычной сети. Ответ «ограничена» из-за такого промаха дороже лишней
        // секунды ожидания: он переводит маскировку в режим белого списка и убирает
        // зарубежные имена из ротации.
        val reachable = probeTargets.any { (host, port) ->
            tcpProbe(network, host, port, 700)
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

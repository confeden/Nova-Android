package com.example.nova

import android.content.Context
import android.net.ConnectivityManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

object DnsProbe {

    private const val CACHE_TTL_MS = 3 * 60 * 1000L
    private const val DNS_PORT = 53
    private const val DNS_TIMEOUT_MS = 5_000
    private const val TEST_DOMAIN = "www.google.com"

    @Volatile
    private var lastProbeKey: String = ""

    @Volatile
    private var lastProbeAtMs: Long = 0L

    @Volatile
    private var lastProbeResult: Boolean = false

    fun isReachable(
        context: Context,
        servers: List<String>,
        cacheKeyPrefix: String,
        logger: ((String) -> Unit)? = null,
        protector: ((DatagramSocket) -> Boolean)? = null,
    ): Boolean {
        val ipv4Servers = servers
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { raw ->
                runCatching { InetAddress.getByName(raw) }.getOrNull()
                    ?.takeIf { it is Inet4Address }
                    ?.hostAddress
            }
            .distinct()
        if (ipv4Servers.isEmpty()) return false

        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
        val networkKey = connectivityManager?.activeNetwork?.toString().orEmpty()
        val probeKey = "$cacheKeyPrefix|$networkKey|${ipv4Servers.joinToString(",")}"
        val now = System.currentTimeMillis()
        if (lastProbeKey == probeKey && now - lastProbeAtMs <= CACHE_TTL_MS) {
            return lastProbeResult
        }

        val reachable = ipv4Servers.any { server ->
            probeUdpDns(
                server = server,
                protector = protector,
            )
        }
        lastProbeKey = probeKey
        lastProbeAtMs = now
        lastProbeResult = reachable
        logger?.invoke(
            if (reachable) {
                "DNS probe успешен для $cacheKeyPrefix (${ipv4Servers.joinToString(",")})"
            } else {
                "DNS probe не прошёл для $cacheKeyPrefix (${ipv4Servers.joinToString(",")})"
            }
        )
        return reachable
    }

    private fun probeUdpDns(
        server: String,
        protector: ((DatagramSocket) -> Boolean)?,
    ): Boolean {
        val socket = try {
            DatagramSocket().apply {
                soTimeout = DNS_TIMEOUT_MS
            }
        } catch (_: Throwable) {
            return false
        }
        return try {
            if (protector != null && !protector(socket)) {
                false
            } else {
                val query = buildDnsQuery(TEST_DOMAIN)
                val destination = InetSocketAddress(server, DNS_PORT)
                socket.send(DatagramPacket(query, query.size, destination))
                val responseBuffer = ByteArray(512)
                val response = DatagramPacket(responseBuffer, responseBuffer.size)
                socket.receive(response)
                response.length > 0
            }
        } catch (_: SocketTimeoutException) {
            false
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun buildDnsQuery(host: String): ByteArray {
        val parts = host.split('.').filter { it.isNotBlank() }
        val questionSize = parts.sumOf { 1 + it.length } + 1 + 4
        val result = ByteArray(12 + questionSize)
        val transactionId = Random.nextInt(0, 0xFFFF)
        result[0] = ((transactionId shr 8) and 0xFF).toByte()
        result[1] = (transactionId and 0xFF).toByte()
        result[2] = 0x01
        result[3] = 0x00
        result[4] = 0x00
        result[5] = 0x01
        var offset = 12
        for (part in parts) {
            result[offset++] = part.length.toByte()
            part.toByteArray(Charsets.US_ASCII).copyInto(result, offset)
            offset += part.length
        }
        result[offset++] = 0x00
        result[offset++] = 0x00
        result[offset++] = 0x01
        result[offset++] = 0x00
        result[offset] = 0x01
        return result
    }
}

package com.example.nova

import android.util.Log
import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ThreadLocalRandom

object WarpBootstrapDns : Dns {
    private val bootstrapServers = listOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1")
    private val hardcodedWarpApi = listOf(
        InetAddress.getByName("104.16.24.84"),
        InetAddress.getByName("104.16.192.82"),
    )

    override fun lookup(hostname: String): List<InetAddress> {
        return resolveHost(hostname)
    }

    fun resolveHost(hostname: String): List<InetAddress> {
        val normalized = hostname.trim().trimEnd('.')
        if (normalized.endsWith("cloudflareclient.com", ignoreCase = true)) {
            val resolved = resolveViaBootstrap(normalized)
            if (resolved.isNotEmpty()) {
                Log.i("WarpBootstrapDns", "Resolved $normalized via bootstrap DNS: ${resolved.joinToString { it.hostAddress ?: it.toString() }}")
                return resolved
            }
            if (normalized.equals("api.cloudflareclient.com", ignoreCase = true)) {
                Log.w("WarpBootstrapDns", "Bootstrap DNS failed for $normalized, using hardcoded IPs")
                LogManager.w("Bootstrap DNS failed for $normalized, using hardcoded IPs", tag = "WarpBootstrapDns")
                return hardcodedWarpApi
            }
        }
        return Dns.SYSTEM.lookup(hostname)
    }

    private fun resolveViaBootstrap(hostname: String): List<InetAddress> {
        for (server in bootstrapServers) {
            val resolved = resolveA(server, hostname)
            if (resolved.isNotEmpty()) {
                return resolved
            }
        }
        return emptyList()
    }

    private fun resolveA(server: String, hostname: String): List<InetAddress> {
        val query = buildQuery(hostname)
        val socket = DatagramSocket()
        socket.soTimeout = 2500
        return try {
            socket.send(
                DatagramPacket(
                    query,
                    query.size,
                    InetSocketAddress(server, 53)
                )
            )
            val responseBuffer = ByteArray(1500)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)
            parseARecords(hostname, responseBuffer, responsePacket.length)
        } catch (_: Exception) {
            emptyList()
        } finally {
            socket.close()
        }
    }

    private fun buildQuery(hostname: String): ByteArray {
        val out = ByteArrayOutputStream()
        val transactionId = ThreadLocalRandom.current().nextInt(0, 0x10000)

        out.write((transactionId shr 8) and 0xFF)
        out.write(transactionId and 0xFF)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)
        out.write(0x00)

        hostname.split('.')
            .filter { it.isNotBlank() }
            .forEach { label ->
                val bytes = label.toByteArray(Charsets.US_ASCII)
                out.write(bytes.size)
                out.write(bytes)
            }
        out.write(0x00)

        out.write(0x00)
        out.write(0x01)
        out.write(0x00)
        out.write(0x01)
        return out.toByteArray()
    }

    private fun parseARecords(hostname: String, response: ByteArray, length: Int): List<InetAddress> {
        if (length < 12) return emptyList()

        val qdCount = readU16(response, 4)
        val anCount = readU16(response, 6)
        var offset = 12

        repeat(qdCount) {
            offset = skipName(response, offset, length)
            if (offset + 4 > length) return emptyList()
            offset += 4
        }

        val results = mutableListOf<InetAddress>()
        repeat(anCount) {
            offset = skipName(response, offset, length)
            if (offset + 10 > length) return results

            val type = readU16(response, offset)
            offset += 2
            val clazz = readU16(response, offset)
            offset += 2
            offset += 4 // ttl
            val rdLength = readU16(response, offset)
            offset += 2

            if (offset + rdLength > length) return results
            if (type == 1 && clazz == 1 && rdLength == 4) {
                val addr = response.copyOfRange(offset, offset + rdLength)
                results.add(InetAddress.getByAddress(hostname, addr))
            }
            offset += rdLength
        }

        return results
    }

    private fun skipName(response: ByteArray, start: Int, length: Int): Int {
        var offset = start
        while (offset < length) {
            val value = response[offset].toInt() and 0xFF
            if (value == 0) {
                return offset + 1
            }
            if ((value and 0xC0) == 0xC0) {
                return offset + 2
            }
            offset += value + 1
        }
        return length
    }

    private fun readU16(buffer: ByteArray, offset: Int): Int {
        return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
    }
}

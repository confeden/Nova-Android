package com.example.nova

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

object LocalDnsProxyManager {

    const val LOOPBACK_DNS_SERVER = "127.0.0.1"

    private sealed class DnsUpstream(val raw: String) {
        class Plain(raw: String, val host: String) : DnsUpstream(raw)
        class Doh(raw: String, val url: String) : DnsUpstream(raw)
        class Dot(raw: String, val host: String, val port: Int) : DnsUpstream(raw)
    }

    @Volatile
    private var serverSocket: DatagramSocket? = null

    @Volatile
    private var workerThread: Thread? = null

    @Volatile
    private var currentUpstreams: List<String> = emptyList()

    @Volatile
    private var currentLabel: String = ""

    private val running = AtomicBoolean(false)
    private val failedUntilByUpstream = ConcurrentHashMap<String, Long>()

    private const val UPSTREAM_FAILURE_COOLDOWN_MS = 20_000L
    private const val PLAIN_DNS_TIMEOUT_MS = 5_000
    private const val ENCRYPTED_CONNECT_TIMEOUT_MS = 1_100
    private const val ENCRYPTED_READ_TIMEOUT_MS = 1_350

    @Synchronized
    fun ensureRunning(
        upstreamServers: List<String>,
        label: String,
        logger: (String) -> Unit,
        datagramProtector: (DatagramSocket) -> Boolean,
        streamProtector: (Socket) -> Boolean,
    ): Boolean {
        val filteredUpstreams = upstreamServers
            .map { parseUpstream(it) }
            .filterNotNull()
            .distinctBy { it.raw }
        if (filteredUpstreams.isEmpty()) {
            logger("Локальный DNS-proxy не запущен: нет подходящих upstream DNS.")
            stop(logger)
            return false
        }

        val upstreamKeys = filteredUpstreams.map { it.raw }
        failedUntilByUpstream.keys.retainAll(upstreamKeys.toSet())
        if (
            running.get() &&
            serverSocket?.isClosed == false &&
            currentUpstreams == upstreamKeys &&
            currentLabel == label
        ) {
            return true
        }

        stop(logger)
        running.set(true)

        val socket = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                soTimeout = 1200
                bind(InetSocketAddress(LOOPBACK_DNS_SERVER, 53))
            }
        } catch (t: Throwable) {
            running.set(false)
            logger("Локальный DNS-proxy не смог занять $LOOPBACK_DNS_SERVER:53: ${t.message}")
            return false
        }

        val worker = Thread({
            val receiveBuffer = ByteArray(4096)
            while (running.get() && !socket.isClosed) {
                try {
                    val request = DatagramPacket(receiveBuffer, receiveBuffer.size)
                    socket.receive(request)
                    val responseBytes = forwardQuery(
                        payload = request.data.copyOf(request.length),
                        upstreams = filteredUpstreams,
                        datagramProtector = datagramProtector,
                        streamProtector = streamProtector,
                    ) ?: continue
                    val response = DatagramPacket(
                        responseBytes,
                        responseBytes.size,
                        request.address,
                        request.port,
                    )
                    socket.send(response)
                } catch (_: SocketTimeoutException) {
                } catch (_: Throwable) {
                }
            }
        }, "NovaLocalDnsProxy").apply {
            isDaemon = true
            start()
        }

        serverSocket = socket
        workerThread = worker
        currentUpstreams = upstreamKeys
        currentLabel = label
        logger("Локальный DNS-proxy запущен на $LOOPBACK_DNS_SERVER:53 ($label -> ${upstreamKeys.joinToString(",")})")
        return true
    }

    @Synchronized
    fun stop(logger: (String) -> Unit) {
        running.set(false)
        val socket = serverSocket
        serverSocket = null
        if (socket != null) {
            runCatching { socket.close() }
        }
        val thread = workerThread
        workerThread = null
        if (thread != null && thread.isAlive && thread !== Thread.currentThread()) {
            runCatching { thread.join(500L) }
        }
        if (currentUpstreams.isNotEmpty() || currentLabel.isNotBlank()) {
            logger("Локальный DNS-proxy остановлен.")
        }
        currentUpstreams = emptyList()
        currentLabel = ""
    }

    fun isRunning(): Boolean = running.get() && serverSocket?.isClosed == false

    private fun parseUpstream(rawValue: String): DnsUpstream? {
        val raw = rawValue.trim()
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("https://", ignoreCase = true) -> DnsUpstream.Doh(raw, raw)
            raw.startsWith("tls://", ignoreCase = true) -> {
                val target = raw.removePrefix("tls://").trim()
                val host = target.substringBefore(':').trim()
                val port = target.substringAfter(':', "853").trim().toIntOrNull() ?: 853
                if (host.isBlank()) null else DnsUpstream.Dot(raw, host, port)
            }
            raw.startsWith("dns://", ignoreCase = true) -> {
                val host = raw.removePrefix("dns://").trim().removePrefix("[").removeSuffix("]")
                parsePlainHost(raw, host)
            }
            else -> parsePlainHost(raw, raw)
        }
    }

    private fun parsePlainHost(raw: String, hostValue: String): DnsUpstream? {
        val normalizedHost = hostValue.substringBefore('%').trim().removePrefix("[").removeSuffix("]")
        val host = runCatching { InetAddress.getByName(normalizedHost) }.getOrNull()
            ?.takeIf { it is Inet4Address }
            ?.hostAddress
            .orEmpty()
        return if (host.isBlank()) null else DnsUpstream.Plain(raw, host)
    }

    private fun forwardQuery(
        payload: ByteArray,
        upstreams: List<DnsUpstream>,
        datagramProtector: (DatagramSocket) -> Boolean,
        streamProtector: (Socket) -> Boolean,
    ): ByteArray? {
        val now = System.currentTimeMillis()
        val orderedUpstreams = upstreams
            .filterNot { (failedUntilByUpstream[it.raw] ?: 0L) > now } +
            upstreams.filter { (failedUntilByUpstream[it.raw] ?: 0L) > now }
        for (upstream in orderedUpstreams) {
            val response = when (upstream) {
                is DnsUpstream.Plain -> forwardPlainDns(payload, upstream.host, datagramProtector)
                is DnsUpstream.Doh -> forwardDoh(payload, upstream.url)
                is DnsUpstream.Dot -> forwardDot(payload, upstream.host, upstream.port, streamProtector)
            }
            if (response != null) {
                failedUntilByUpstream.remove(upstream.raw)
                return response
            }
            failedUntilByUpstream[upstream.raw] = System.currentTimeMillis() + UPSTREAM_FAILURE_COOLDOWN_MS
        }
        return null
    }

    private fun forwardPlainDns(
        payload: ByteArray,
        host: String,
        datagramProtector: (DatagramSocket) -> Boolean,
    ): ByteArray? {
        val upstreamSocket = try {
            DatagramSocket().apply {
                soTimeout = PLAIN_DNS_TIMEOUT_MS
            }
        } catch (_: Throwable) {
            return null
        }
        return try {
            if (!datagramProtector(upstreamSocket)) {
                null
            } else {
                val destination = InetSocketAddress(host, 53)
                val outbound = DatagramPacket(payload, payload.size, destination)
                upstreamSocket.send(outbound)
                val replyBuffer = ByteArray(4096)
                val inbound = DatagramPacket(replyBuffer, replyBuffer.size)
                upstreamSocket.receive(inbound)
                inbound.data.copyOf(inbound.length)
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { upstreamSocket.close() }
        }
    }

    private fun forwardDoh(payload: ByteArray, resolverUrl: String): ByteArray? {
        return runCatching {
            val connection = (URL(resolverUrl).openConnection() as HttpsURLConnection).apply {
                connectTimeout = ENCRYPTED_CONNECT_TIMEOUT_MS
                readTimeout = ENCRYPTED_READ_TIMEOUT_MS
                requestMethod = "POST"
                doOutput = true
                useCaches = false
                setRequestProperty("Content-Type", "application/dns-message")
                setRequestProperty("Accept", "application/dns-message")
            }
            try {
                connection.outputStream.use { output ->
                    output.write(payload)
                    output.flush()
                }
                if (connection.responseCode !in 200..299) {
                    null
                } else {
                    connection.inputStream.use { input -> input.readBytes() }
                }
            } finally {
                runCatching { connection.errorStream?.close() }
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun forwardDot(
        payload: ByteArray,
        host: String,
        port: Int,
        streamProtector: (Socket) -> Boolean,
    ): ByteArray? {
        val rawSocket = Socket()
        return try {
            if (!streamProtector(rawSocket)) {
                null
            } else {
                rawSocket.connect(InetSocketAddress(host, port), ENCRYPTED_CONNECT_TIMEOUT_MS)
                rawSocket.soTimeout = ENCRYPTED_READ_TIMEOUT_MS
                val sslSocket = ((SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(rawSocket, host, port, true) as SSLSocket)
                sslSocket.use { socket ->
                    socket.soTimeout = ENCRYPTED_READ_TIMEOUT_MS
                    socket.startHandshake()
                    val output = DataOutputStream(socket.outputStream)
                    output.writeShort(payload.size)
                    output.write(payload)
                    output.flush()

                    val input = DataInputStream(socket.inputStream)
                    val responseSize = input.readUnsignedShort()
                    if (responseSize <= 0 || responseSize > 65535) {
                        null
                    } else {
                        val response = ByteArray(responseSize)
                        input.readFully(response)
                        response
                    }
                }
            }
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { rawSocket.close() }
        }
    }
}

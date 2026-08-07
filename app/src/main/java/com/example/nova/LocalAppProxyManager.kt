package com.example.nova

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

object LocalAppProxyManager {

    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val MAX_FAILED_AUTH_PER_MINUTE = 5
    private const val AUTH_WINDOW_MS = 60_000L
    private data class BoundListener(
        val host: String,
        val socket: ServerSocket,
        val worker: Thread,
    )

    @Volatile
    private var listeners: List<BoundListener> = emptyList()

    @Volatile
    private var advertisedHost: String = LOOPBACK_HOST

    @Volatile
    private var currentEndpoints: List<GatewayEndpoint> = emptyList()

    @Volatile
    private var downstreamHosts: Set<String> = emptySet()

    private val running = AtomicBoolean(false)
    private val failedAuthByClient = ConcurrentHashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun sync(
        context: Context,
        backendLabel: String,
        serviceState: String,
        logger: (String) -> Unit,
    ) {
        val appContext = context.applicationContext
        val clientData = ClientData(appContext)
        // Слушаем всегда, пока раздача включена. Раньше прокси поднимался только при
        // живом туннеле и гас вместе с ним — клиент терял соединение с самим шлюзом и
        // не понимал, что произошло. Теперь адрес и порт остаются на месте, а решение
        // «пускать или нет» принимается по месту, на каждом подключении.
        val shouldRun = clientData.isLocalProxyEnabled()
        val endpoints = GatewayEndpoints.discover(appContext)
        val desiredHosts = resolveListenHosts(endpoints)
        val primaryHost = resolveAdvertisedHost(endpoints)
        currentEndpoints = endpoints
        downstreamHosts = endpoints.filter { it.downstream }.map { it.host }.toSet()
        if (!shouldRun) {
            stop(appContext, logger)
            clientData.saveLocalProxyStatus(
                running = false,
                backend = backendLabel,
                host = primaryHost,
                port = clientData.getLocalProxyPort(),
                endpoints = endpoints,
            )
            return
        }

        clientData.ensureLocalProxyCredentials()
        val currentListeners = listeners
        val currentHosts = currentListeners.map { it.host }.sorted()
        // Портал проверяем наравне с прокси. Раньше условие смотрело только на сокеты
        // прокси, и если портал не поднялся — например, в момент включения список
        // интерфейсов оказался пуст, — то дальше каждый sync видел «всё уже работает»
        // и молча уходил. Портал не поднимался уже никогда.
        val portalHealthy = GatewayPortal.isRunning() || endpoints.isEmpty()
        if (
            running.get() &&
            currentListeners.isNotEmpty() &&
            currentListeners.all { !it.socket.isClosed } &&
            currentHosts == desiredHosts.sorted() &&
            portalHealthy
        ) {
            advertisedHost = primaryHost
            GatewayPortal.sync(appContext, endpoints, logger)
            clientData.saveLocalProxyStatus(
                running = true,
                backend = backendLabel,
                host = primaryHost,
                port = clientData.getLocalProxyPort(),
                endpoints = endpoints,
            )
            return
        }

        stop(appContext, logger)
        running.set(true)
        val boundListeners = mutableListOf<BoundListener>()
        try {
            desiredHosts.forEach { host ->
                val socket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(host, clientData.getLocalProxyPort()))
                }
                val worker = thread(
                    start = true,
                    isDaemon = true,
                    name = "NovaLocalAppProxyAccept-$host",
                ) {
                    while (running.get() && !socket.isClosed) {
                        try {
                            val client = socket.accept()
                            thread(
                                start = true,
                                isDaemon = true,
                                name = "NovaLocalAppProxyClient",
                            ) {
                                handleClient(appContext, client, logger)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                }
                boundListeners += BoundListener(host = host, socket = socket, worker = worker)
            }
        } catch (t: Throwable) {
            running.set(false)
            boundListeners.forEach { listener ->
                runCatching { listener.socket.close() }
                if (listener.worker.isAlive && listener.worker !== Thread.currentThread()) {
                    runCatching { listener.worker.join(400L) }
                }
            }
            listeners = emptyList()
            advertisedHost = primaryHost
            GatewayPortal.stop(logger)
            clientData.saveLocalProxyStatus(
                running = false,
                backend = backendLabel,
                host = primaryHost,
                port = clientData.getLocalProxyPort(),
                endpoints = endpoints,
            )
            logger("Локальный прокси не смог занять ${desiredHosts.joinToString(", ")}:${clientData.getLocalProxyPort()}: ${t.message}")
            return
        }

        listeners = boundListeners
        advertisedHost = primaryHost
        GatewayPortal.sync(appContext, endpoints, logger)
        clientData.saveLocalProxyStatus(
            running = true,
            backend = backendLabel,
            host = primaryHost,
            port = clientData.getLocalProxyPort(),
            endpoints = endpoints,
        )
        val tunnelNote = when {
            serviceState == NovaVpnService.STATE_CONNECTED -> "трафик идёт в VPN"
            clientData.isGatewayAllowDirectWithoutVpn() -> "VPN не подключён, трафик пойдёт напрямую"
            else -> "VPN не подключён, трафик пока не выпускается"
        }
        logger(
            "Локальный прокси запущен на ${
                boundListeners.joinToString(", ") { "${it.host}:${clientData.getLocalProxyPort()}" }
            } (HTTP/HTTPS/SOCKS5): $tunnelNote."
        )
        val shared = endpoints.filter { it.downstream }
        if (shared.isNotEmpty()) {
            logger(
                "Раздача через Nova доступна на ${
                    shared.joinToString(", ") { "${it.kind.title} ${it.host} (${it.interfaceName})" }
                }."
            )
        } else {
            logger("Интерфейсов раздачи не найдено. Что видно: ${GatewayEndpoints.diagnose(appContext)}")
        }
    }

    @Synchronized
    fun stop(context: Context, logger: (String) -> Unit) {
        running.set(false)
        val activeListeners = listeners
        listeners = emptyList()
        activeListeners.forEach { listener ->
            runCatching { listener.socket.close() }
        }
        activeListeners.forEach { listener ->
            if (listener.worker.isAlive && listener.worker !== Thread.currentThread()) {
                runCatching { listener.worker.join(500L) }
            }
        }
        if (failedAuthByClient.isNotEmpty()) {
            failedAuthByClient.clear()
        }
        if (activeListeners.isNotEmpty()) {
            logger("Локальный прокси остановлен.")
        }
        GatewayPortal.stop(logger)
        val clientData = ClientData(context.applicationContext)
        val endpoints = currentEndpoints
        advertisedHost = resolveAdvertisedHost(endpoints)
        clientData.saveLocalProxyStatus(
            running = false,
            backend = clientData.getServiceBackend(),
            host = advertisedHost,
            port = clientData.getLocalProxyPort(),
            endpoints = endpoints,
        )
    }

    fun isRunning(): Boolean = running.get() && listeners.any { !it.socket.isClosed }

    fun getAdvertisedHost(context: Context): String {
        return resolveAdvertisedHost(GatewayEndpoints.discover(context.applicationContext))
    }

    /** Адреса, на которых прокси слушал в последний раз. Для экрана раздачи. */
    fun getEndpoints(): List<GatewayEndpoint> = currentEndpoints

    private fun resolveListenHosts(endpoints: List<GatewayEndpoint>): List<String> {
        return buildList {
            add(LOOPBACK_HOST)
            endpoints.forEach { endpoint ->
                if (!endpoint.host.equals(LOOPBACK_HOST, ignoreCase = true)) add(endpoint.host)
            }
        }.distinct()
    }

    /**
     * Что показать пользователю как главный адрес. Раздача важнее общей сети: если
     * телефон раздаёт интернет, клиент почти наверняка подключён именно к нему.
     */
    private fun resolveAdvertisedHost(endpoints: List<GatewayEndpoint>): String {
        return endpoints.firstOrNull { it.downstream }?.host
            ?: endpoints.firstOrNull()?.host
            ?: LOOPBACK_HOST
    }

    private fun handleClient(
        context: Context,
        clientSocket: Socket,
        logger: (String) -> Unit,
    ) {
        try {
            clientSocket.use { socket ->
                socket.soTimeout = 20_000
                socket.keepAlive = false
                socket.tcpNoDelay = true

                val ownerInfo = LocalProxyOwnerResolver.resolveTcpOwner(
                    context = context,
                    clientAddress = InetSocketAddress(socket.inetAddress, socket.port),
                    serverAddress = InetSocketAddress(socket.localAddress, socket.localPort),
                )
                val ownerKey = ownerInfo?.packageName?.ifBlank { null }
                    ?: ownerInfo?.uid?.let { "uid:$it" }
                    ?: buildUnknownOwnerKey(socket)

                val input = PushbackInputStream(BufferedInputStream(socket.getInputStream()), 1)
                val output = BufferedOutputStream(socket.getOutputStream())
                val firstByte = input.read()
                if (firstByte < 0) return
                input.unread(firstByte)

                val authExempt = isAuthExempt(context, socket)
                if (firstByte == 0x05) {
                    handleSocks5Client(context, socket, input, output, ownerInfo, ownerKey, authExempt, logger)
                } else {
                    handleHttpClient(context, socket, input, output, ownerInfo, ownerKey, authExempt, logger)
                }
            }
        } catch (t: Throwable) {
            if (!isBenignClientDisconnect(t)) {
                logger("Локальный прокси: ошибка клиентского соединения: ${t.message ?: t::class.java.simpleName}")
            }
        }
    }

    private fun isBenignClientDisconnect(t: Throwable): Boolean {
        if (t is EOFException) return true
        if (t is SocketException) {
            val message = t.message.orEmpty().lowercase(Locale.US)
            if (
                "broken pipe" in message ||
                "connection reset" in message ||
                "socket closed" in message ||
                "software caused connection abort" in message ||
                "connection aborted" in message
            ) {
                return true
            }
        }
        val cause = t.cause
        return cause != null && cause !== t && isBenignClientDisconnect(cause)
    }

    private fun handleHttpClient(
        context: Context,
        clientSocket: Socket,
        input: PushbackInputStream,
        output: BufferedOutputStream,
        ownerInfo: LocalProxyOwnerInfo?,
        ownerKey: String,
        authExempt: Boolean,
        logger: (String) -> Unit,
    ) {
        val requestLine = readAsciiLine(input)?.trim().orEmpty()
        if (requestLine.isBlank()) return
        val requestParts = requestLine.split(' ', limit = 3)
        if (requestParts.size < 3) {
            writeHttpError(output, 400, "Bad Request")
            return
        }
        val method = requestParts[0].trim().uppercase(Locale.US)
        val requestTarget = requestParts[1].trim()
        val httpVersion = requestParts[2].trim().ifBlank { "HTTP/1.1" }
        val headers = readHttpHeaders(input)
        val protocolLabel = if (method == "CONNECT") "HTTPS-CONNECT" else "HTTP"

        if (!authExempt && !isHttpAuthorized(context, headers)) {
            if (isRateLimited(ownerKey)) {
                logUnauthorizedAttempt(
                    context,
                    ownerInfo,
                    protocolLabel,
                    "Превышен лимит 5 неудачных попыток за минуту",
                    logger,
                )
                writeHttpError(output, 429, "Too Many Requests")
                return
            }
            recordFailedAuth(ownerKey)
            logUnauthorizedAttempt(context, ownerInfo, protocolLabel, "Неверный логин или пароль", logger)
            writeHttpAuthRequired(output)
            return
        }
        clearFailedAuth(ownerKey)

        if (tunnelBlocksTraffic(context)) {
            logger("Локальный прокси: запрос $protocolLabel отклонён, VPN не подключён.")
            writeTunnelUnavailable(output)
            return
        }

        if (method == "CONNECT") {
            val target = parseConnectTarget(requestTarget) ?: run {
                writeHttpError(output, 400, "Bad CONNECT Target")
                return
            }
            logger("Локальный прокси HTTP CONNECT: открываем туннель до ${target.host}:${target.port}.")
            val upstream = openTargetSocket(context, target.host, target.port) ?: run {
                logger("Локальный прокси HTTP CONNECT: не удалось открыть туннель до ${target.host}:${target.port}.")
                writeHttpError(output, 502, "Bad Gateway")
                return
            }
            upstream.use {
                prepareTunnelSocket(clientSocket)
                prepareTunnelSocket(it)
                logger("Локальный прокси HTTP CONNECT: туннель до ${target.host}:${target.port} открыт через ${it.remoteSocketAddress}.")
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                output.flush()
                bridge(
                    clientSocket = clientSocket,
                    clientInput = input,
                    clientOutput = output,
                    upstreamSocket = it,
                    logger = logger,
                    bridgeLabel = "HTTP CONNECT ${target.host}:${target.port}",
                )
            }
            return
        }

        val target = parseHttpTarget(requestTarget, headers) ?: run {
            writeHttpError(output, 400, "Bad Target")
            return
        }
        val usingOperaBackend = isOperaBackend(ClientData(context).getServiceBackend())
        val upstream = if (usingOperaBackend) {
            openOperaProxySocket(context)
        } else {
            openDirectSocket(context, target.host, target.port)
        } ?: run {
            writeHttpError(output, 502, "Bad Gateway")
            return
        }
        upstream.use { remote ->
            val remoteOutput = BufferedOutputStream(remote.getOutputStream())
            val sanitizedHeaders = sanitizeOutgoingHeaders(headers, ensureHost = target.hostHeader)
            val outboundTarget = if (usingOperaBackend) target.absoluteUrl else target.originForm
            remoteOutput.write("$method $outboundTarget $httpVersion\r\n".toByteArray(StandardCharsets.US_ASCII))
            sanitizedHeaders.forEach { (name, value) ->
                remoteOutput.write("$name: $value\r\n".toByteArray(StandardCharsets.US_ASCII))
            }
            remoteOutput.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            remoteOutput.flush()
            bridge(clientSocket, input, output, remote, remoteOutputPrepared = true)
        }
    }

    private fun handleSocks5Client(
        context: Context,
        clientSocket: Socket,
        input: PushbackInputStream,
        output: BufferedOutputStream,
        ownerInfo: LocalProxyOwnerInfo?,
        ownerKey: String,
        authExempt: Boolean,
        logger: (String) -> Unit,
    ) {
        val version = input.read()
        if (version != 0x05) return
        val methodCount = input.read()
        if (methodCount <= 0) return
        val methods = ByteArray(methodCount)
        readFully(input, methods)

        // Клиент раздачи с отключённой авторизацией: если он умеет «без пароля» —
        // так и договариваемся. Если умеет только username/password, обмен всё равно
        // проводим, но содержимое не проверяем: проверять нечего, авторизация выключена.
        val skipCredentialCheck = authExempt
        if (authExempt && methods.contains(0x00)) {
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()
        } else {
            if (!methods.contains(0x02)) {
                if (isRateLimited(ownerKey)) {
                    logUnauthorizedAttempt(
                        context,
                        ownerInfo,
                        "SOCKS5",
                        "Превышен лимит 5 неудачных попыток за минуту",
                        logger,
                    )
                    output.write(byteArrayOf(0x05, 0xFF.toByte()))
                    output.flush()
                    return
                }
                recordFailedAuth(ownerKey)
                logUnauthorizedAttempt(context, ownerInfo, "SOCKS5", "Клиент не предложил username/password auth", logger)
                output.write(byteArrayOf(0x05, 0xFF.toByte()))
                output.flush()
                return
            }
            output.write(byteArrayOf(0x05, 0x02))
            output.flush()

            val authVersion = input.read()
            val userLength = input.read()
            if (authVersion != 0x01 || userLength < 0) {
                recordFailedAuth(ownerKey)
                logUnauthorizedAttempt(context, ownerInfo, "SOCKS5", "Некорректный auth-пакет", logger)
                output.write(byteArrayOf(0x01, 0x01))
                output.flush()
                return
            }
            val usernameBytes = ByteArray(userLength)
            readFully(input, usernameBytes)
            val passwordLength = input.read()
            if (passwordLength < 0) return
            val passwordBytes = ByteArray(passwordLength)
            readFully(input, passwordBytes)
            val username = String(usernameBytes, StandardCharsets.US_ASCII)
            val password = String(passwordBytes, StandardCharsets.US_ASCII)
            val credentials = ClientData(context).ensureLocalProxyCredentials()
            if (!skipCredentialCheck && (username != credentials.first || password != credentials.second)) {
                if (isRateLimited(ownerKey)) {
                    logUnauthorizedAttempt(
                        context,
                        ownerInfo,
                        "SOCKS5",
                        "Превышен лимит 5 неудачных попыток за минуту",
                        logger,
                    )
                    output.write(byteArrayOf(0x01, 0x01))
                    output.flush()
                    return
                }
                recordFailedAuth(ownerKey)
                logUnauthorizedAttempt(context, ownerInfo, "SOCKS5", "Неверный логин или пароль", logger)
                output.write(byteArrayOf(0x01, 0x01))
                output.flush()
                return
            }
            clearFailedAuth(ownerKey)
            output.write(byteArrayOf(0x01, 0x00))
            output.flush()
        }

        val requestVersion = input.read()
        val command = input.read()
        input.read() // reserved
        val atyp = input.read()
        if (requestVersion != 0x05 || command != 0x01) {
            writeSocks5Reply(output, 0x07)
            return
        }
        val host = when (atyp) {
            0x01 -> {
                val raw = ByteArray(4)
                readFully(input, raw)
                raw.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> {
                val size = input.read()
                if (size <= 0) return
                val raw = ByteArray(size)
                readFully(input, raw)
                String(raw, StandardCharsets.US_ASCII)
            }
            0x04 -> {
                val raw = ByteArray(16)
                readFully(input, raw)
                java.net.InetAddress.getByAddress(raw).hostAddress
            }
            else -> {
                writeSocks5Reply(output, 0x08)
                return
            }
        }
        val portBytes = ByteArray(2)
        readFully(input, portBytes)
        val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)
        if (tunnelBlocksTraffic(context)) {
            logger("Локальный прокси SOCKS5: запрос до $host:$port отклонён, VPN не подключён.")
            // 0x02 — connection not allowed by ruleset: ближайший по смыслу код,
            // клиенты показывают его как отказ политики, а не как сбой сети.
            writeSocks5Reply(output, 0x02)
            return
        }
        logger("Локальный прокси SOCKS5 CONNECT: открываем туннель до $host:$port.")
        val upstream = openTargetSocket(context, host, port) ?: run {
            logger("Локальный прокси SOCKS5 CONNECT: не удалось открыть туннель до $host:$port.")
            writeSocks5Reply(output, 0x05)
            return
        }
        upstream.use {
            prepareTunnelSocket(clientSocket)
            prepareTunnelSocket(it)
            logger("Локальный прокси SOCKS5 CONNECT: туннель до $host:$port открыт через ${it.remoteSocketAddress}.")
            writeSocks5Reply(output, 0x00)
            bridge(
                clientSocket = clientSocket,
                clientInput = input,
                clientOutput = output,
                upstreamSocket = it,
                logger = logger,
                bridgeLabel = "SOCKS5 CONNECT $host:$port",
            )
        }
    }

    /**
     * Можно ли сейчас выпускать трафик клиента.
     *
     * Если туннеля нет, исходящий сокет прокси ушёл бы напрямую — клиент получил бы
     * рабочий интернет мимо VPN и ничего бы об этом не узнал. Поэтому по умолчанию
     * отвечаем понятной ошибкой и ждём, пока туннель поднимется.
     */
    private fun tunnelBlocksTraffic(context: Context): Boolean {
        val clientData = ClientData(context)
        if (clientData.getServiceState() == NovaVpnService.STATE_CONNECTED) return false
        return !clientData.isGatewayAllowDirectWithoutVpn()
    }

    private fun writeTunnelUnavailable(output: OutputStream) {
        val body = """
            <!DOCTYPE html><html lang="ru"><head><meta charset="utf-8">
            <title>VPN не подключён</title></head>
            <body style="font-family:sans-serif;padding:24px;max-width:520px">
            <h2 style="color:#c0392b">Nova сейчас не подключена к VPN</h2>
            <p>Раздача включена и ждёт вас на этом же адресе, но выпускать трафик
            мимо туннеля она не станет — иначе вы вышли бы в интернет со своим
            обычным адресом и не заметили этого.</p>
            <p>Подключите VPN в приложении Nova — страницы начнут открываться сами,
            перенастраивать ничего не нужно.</p>
            </body></html>
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        output.write(
            (
                "HTTP/1.1 503 Service Unavailable\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
                ).toByteArray(StandardCharsets.US_ASCII)
        )
        output.write(body)
        output.flush()
    }

    private fun openTargetSocket(context: Context, host: String, port: Int): Socket? {
        return if (isOperaBackend(ClientData(context).getServiceBackend())) {
            openHttpConnectTunnelViaOpera(context, host, port)
        } else {
            openDirectSocket(context, host, port)
        }
    }

    private fun openDirectSocket(context: Context, host: String, port: Int): Socket? {
        return runCatching {
            Socket().apply {
                soTimeout = 20_000
                keepAlive = true
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), 8_000)
            }
        }.getOrNull() ?: openVpnBoundSocket(context, host, port)
    }

    private fun openVpnBoundSocket(context: Context, host: String, port: Int): Socket? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val vpnNetwork = connectivityManager.allNetworks.firstOrNull { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } ?: return null
        return runCatching {
            vpnNetwork.socketFactory.createSocket().apply {
                soTimeout = 20_000
                keepAlive = true
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), 8_000)
            }
        }.getOrNull()
    }

    private fun openOperaProxySocket(context: Context): Socket? {
        val proxyAddress = OperaProxyManager.getLoopbackProxyAddress(context)
        return runCatching {
            Socket().apply {
                soTimeout = 20_000
                keepAlive = true
                tcpNoDelay = true
                connect(proxyAddress, 5_000)
            }
        }.getOrNull()
    }

    private fun openHttpConnectTunnelViaOpera(context: Context, host: String, port: Int): Socket? {
        val proxyAddress = OperaProxyManager.getLoopbackProxyAddress(context)
        return runCatching {
            Socket().apply {
                soTimeout = 20_000
                connect(proxyAddress, 5_000)
                val writer = BufferedOutputStream(getOutputStream())
                writer.write(
                    buildString {
                        append("CONNECT ")
                        append(host)
                        append(":")
                        append(port)
                        append(" HTTP/1.1\r\n")
                        append("Host: ")
                        append(host)
                        append(":")
                        append(port)
                        append("\r\nProxy-Connection: Keep-Alive\r\n\r\n")
                    }.toByteArray(StandardCharsets.US_ASCII)
                )
                writer.flush()
                val reader = BufferedInputStream(getInputStream())
                val statusLine = readAsciiLine(reader).orEmpty()
                while (true) {
                    val line = readAsciiLine(reader) ?: break
                    if (line.isBlank()) break
                }
                if (!statusLine.contains("200")) {
                    close()
                    throw IllegalStateException("Opera CONNECT failed: $statusLine")
                }
                prepareTunnelSocket(this)
            }
        }.getOrNull()
    }

    private fun prepareTunnelSocket(socket: Socket) {
        runCatching {
            socket.soTimeout = 0
            socket.keepAlive = true
            socket.tcpNoDelay = true
        }
    }

    private fun isOperaBackend(backendLabel: String): Boolean {
        return backendLabel.trim().uppercase(Locale.US).startsWith(NovaVpnService.BACKEND_OPERA)
    }

    private fun sanitizeOutgoingHeaders(
        headers: List<Pair<String, String>>,
        ensureHost: String,
    ): List<Pair<String, String>> {
        val sanitized = headers
            .filterNot { (name, _) ->
                name.equals("Proxy-Authorization", ignoreCase = true) ||
                    name.equals("Proxy-Connection", ignoreCase = true) ||
                    name.equals("Connection", ignoreCase = true)
            }
            .toMutableList()
        if (sanitized.none { it.first.equals("Host", ignoreCase = true) }) {
            sanitized.add("Host" to ensureHost)
        }
        sanitized.add("Connection" to "close")
        return sanitized
    }

    private fun readHttpHeaders(input: InputStream): List<Pair<String, String>> {
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val line = readAsciiLine(input) ?: break
            if (line.isBlank()) break
            val separator = line.indexOf(':')
            if (separator <= 0) continue
            headers += line.substring(0, separator).trim() to line.substring(separator + 1).trim()
        }
        return headers
    }

    private fun isHttpAuthorized(context: Context, headers: List<Pair<String, String>>): Boolean {
        val authHeader = headers.firstOrNull { it.first.equals("Proxy-Authorization", ignoreCase = true) }?.second.orEmpty()
        if (!authHeader.startsWith("Basic ", ignoreCase = true)) return false
        val encoded = authHeader.substringAfter(' ').trim()
        val decoded = runCatching {
            String(Base64.decode(encoded, Base64.DEFAULT), StandardCharsets.US_ASCII)
        }.getOrDefault("")
        val separator = decoded.indexOf(':')
        if (separator <= 0) return false
        val providedUser = decoded.substring(0, separator)
        val providedPassword = decoded.substring(separator + 1)
        val credentials = ClientData(context).ensureLocalProxyCredentials()
        return providedUser == credentials.first && providedPassword == credentials.second
    }

    private data class ParsedHttpTarget(
        val host: String,
        val port: Int,
        val hostHeader: String,
        val absoluteUrl: String,
        val originForm: String,
    )

    private fun parseConnectTarget(raw: String): ParsedHttpTarget? {
        val parsed = parseHostAndPort(raw, 443) ?: return null
        val hostHeader = if (parsed.second == 443) parsed.first else "${parsed.first}:${parsed.second}"
        return ParsedHttpTarget(
            host = parsed.first,
            port = parsed.second,
            hostHeader = hostHeader,
            absoluteUrl = "",
            originForm = "",
        )
    }

    private fun parseHttpTarget(
        rawTarget: String,
        headers: List<Pair<String, String>>,
    ): ParsedHttpTarget? {
        return if (rawTarget.startsWith("http://", ignoreCase = true) || rawTarget.startsWith("https://", ignoreCase = true)) {
            val uri = runCatching { URI(rawTarget) }.getOrNull() ?: return null
            val host = uri.host?.trim().orEmpty().removePrefix("[").removeSuffix("]")
            if (host.isBlank()) return null
            val port = when {
                uri.port in 1..65535 -> uri.port
                uri.scheme.equals("https", ignoreCase = true) -> 443
                else -> 80
            }
            val path = buildString {
                append(uri.rawPath?.takeIf { it.isNotBlank() } ?: "/")
                uri.rawQuery?.takeIf { it.isNotBlank() }?.let {
                    append("?")
                    append(it)
                }
            }
            ParsedHttpTarget(
                host = host,
                port = port,
                hostHeader = if (port == 80) host else "$host:$port",
                absoluteUrl = rawTarget,
                originForm = path,
            )
        } else {
            val hostHeader = headers.firstOrNull { it.first.equals("Host", ignoreCase = true) }?.second.orEmpty()
            val parsed = parseHostAndPort(hostHeader, 80) ?: return null
            ParsedHttpTarget(
                host = parsed.first,
                port = parsed.second,
                hostHeader = if (parsed.second == 80) parsed.first else "${parsed.first}:${parsed.second}",
                absoluteUrl = "http://${parsed.first}:${parsed.second}$rawTarget",
                originForm = rawTarget.ifBlank { "/" },
            )
        }
    }

    private fun parseHostAndPort(raw: String, defaultPort: Int): Pair<String, Int>? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return when {
            value.startsWith("[") -> {
                val host = value.substringAfter('[').substringBefore(']').trim()
                val port = value.substringAfter("]:", "").toIntOrNull() ?: defaultPort
                host.takeIf { it.isNotBlank() }?.let { it to port }
            }
            value.count { it == ':' } == 1 -> {
                val host = value.substringBefore(':').trim()
                val port = value.substringAfter(':', "").toIntOrNull() ?: defaultPort
                host.takeIf { it.isNotBlank() }?.let { it to port }
            }
            else -> value.removePrefix("[").removeSuffix("]").takeIf { it.isNotBlank() }?.let { it to defaultPort }
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val value = input.read()
            if (value < 0) {
                return if (buffer.isEmpty()) null else buffer.toString()
            }
            if (value == '\n'.code) {
                return buffer.toString().trimEnd('\r')
            }
            buffer.append(value.toChar())
            if (buffer.length > 16_384) {
                throw EOFException("line too long")
            }
        }
    }

    private fun readFully(input: InputStream, target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read <= 0) throw EOFException("unexpected EOF")
            offset += read
        }
    }

    private fun bridge(
        clientSocket: Socket,
        clientInput: InputStream,
        clientOutput: OutputStream,
        upstreamSocket: Socket,
        logger: ((String) -> Unit)? = null,
        bridgeLabel: String = "proxy-bridge",
        remoteOutputPrepared: Boolean = false,
    ) {
        val upstreamInput = BufferedInputStream(upstreamSocket.getInputStream())
        val upstreamOutput = BufferedOutputStream(upstreamSocket.getOutputStream())
        val clientToUpstream = java.util.concurrent.atomic.AtomicLong(0L)
        val upstreamToClient = java.util.concurrent.atomic.AtomicLong(0L)
        val upstreamThreadError = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val clientFirstChunkLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        val upstreamFirstChunkLogged = java.util.concurrent.atomic.AtomicBoolean(false)
        val upstreamThread = thread(start = true, isDaemon = true, name = "NovaLocalProxyUpstream") {
            try {
                copyStream(
                    input = clientInput,
                    output = upstreamOutput,
                    counter = clientToUpstream,
                    onChunk = { bytes ->
                        if (clientFirstChunkLogged.compareAndSet(false, true)) {
                            logger?.invoke("$bridgeLabel: первые client->upstream ${bytes} B отправлены.")
                        }
                    },
                )
            } catch (t: Throwable) {
                upstreamThreadError.set(t.javaClass.simpleName + ": " + (t.message ?: ""))
            } finally {
                runCatching { upstreamOutput.flush() }
                runCatching { upstreamSocket.shutdownOutput() }
            }
        }
        var downstreamError: String? = null
        try {
            copyStream(
                input = upstreamInput,
                output = clientOutput,
                counter = upstreamToClient,
                onChunk = { bytes ->
                    if (upstreamFirstChunkLogged.compareAndSet(false, true)) {
                        logger?.invoke("$bridgeLabel: первые upstream->client ${bytes} B получены.")
                    }
                },
            )
        } catch (t: Throwable) {
            downstreamError = t.javaClass.simpleName + ": " + (t.message ?: "")
        } finally {
            runCatching { clientOutput.flush() }
            runCatching { clientSocket.shutdownOutput() }
            if (!remoteOutputPrepared) {
                runCatching { upstreamOutput.flush() }
            }
            runCatching { upstreamThread.join(1200L) }
            logger?.invoke(
                "$bridgeLabel завершён: client->upstream=${clientToUpstream.get()} B, upstream->client=${upstreamToClient.get()} B" +
                    buildString {
                        upstreamThreadError.get()?.takeIf { it.isNotBlank() }?.let { append(", upstreamErr=$it") }
                        downstreamError?.takeIf { it.isNotBlank() }?.let { append(", downstreamErr=$it") }
                    }
            )
        }
    }

    private fun copyStream(
        input: InputStream,
        output: OutputStream,
        counter: java.util.concurrent.atomic.AtomicLong? = null,
        onChunk: ((Int) -> Unit)? = null,
    ) {
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            onChunk?.invoke(read)
            output.write(buffer, 0, read)
            output.flush()
            counter?.addAndGet(read.toLong())
        }
    }

    private fun writeHttpAuthRequired(output: OutputStream) {
        output.write(
            "HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"Nova Local Proxy\"\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII)
        )
        output.flush()
    }

    private fun writeHttpError(output: OutputStream, code: Int, text: String) {
        output.write(
            "HTTP/1.1 $code $text\r\nConnection: close\r\nContent-Length: 0\r\n\r\n"
                .toByteArray(StandardCharsets.US_ASCII)
        )
        output.flush()
    }

    private fun writeSocks5Reply(output: OutputStream, replyCode: Int) {
        output.write(
            byteArrayOf(
                0x05,
                replyCode.toByte(),
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
            )
        )
        output.flush()
    }

    private fun recordFailedAuth(ownerKey: String) {
        val now = System.currentTimeMillis()
        val deque = failedAuthByClient.getOrPut(ownerKey) { ArrayDeque() }
        synchronized(deque) {
            pruneAuthWindow(deque, now)
            deque.addLast(now)
        }
    }

    private fun clearFailedAuth(ownerKey: String) {
        failedAuthByClient.remove(ownerKey)
    }

    private fun isRateLimited(ownerKey: String): Boolean {
        val now = System.currentTimeMillis()
        val deque = failedAuthByClient.getOrPut(ownerKey) { ArrayDeque() }
        synchronized(deque) {
            pruneAuthWindow(deque, now)
            return deque.size >= MAX_FAILED_AUTH_PER_MINUTE
        }
    }

    private fun pruneAuthWindow(deque: ArrayDeque<Long>, now: Long) {
        while (deque.isNotEmpty()) {
            val first = deque.peekFirst() ?: break
            if (now - first <= AUTH_WINDOW_MS) break
            deque.removeFirst()
        }
    }

    /**
     * Можно ли пустить этого клиента без логина и пароля.
     *
     * Решает не адрес клиента, а адрес, на который он пришёл: подключение, принятое
     * на интерфейсе раздачи, физически не может прийти из чужой сети. Проверка по
     * адресу отправителя была бы слабее — его подделывают.
     */
    private fun isAuthExempt(context: Context, socket: Socket): Boolean {
        if (!ClientData(context).isGatewayOpenForTethered()) return false
        val localHost = socket.localAddress?.hostAddress?.trim().orEmpty()
        return localHost.isNotEmpty() && localHost in downstreamHosts
    }

    private fun buildUnknownOwnerKey(socket: Socket): String {
        val host = socket.inetAddress?.hostAddress?.ifBlank { "unknown-host" } ?: "unknown-host"
        return if (socket.inetAddress?.isLoopbackAddress == true) {
            "unknown-loopback:${socket.port}"
        } else {
            "unknown-host:$host"
        }
    }

    private fun logUnauthorizedAttempt(
        context: Context,
        ownerInfo: LocalProxyOwnerInfo?,
        protocol: String,
        reason: String,
        logger: (String) -> Unit,
    ) {
        val packageName = ownerInfo?.packageName.orEmpty()
        val appLabel = ownerInfo?.appLabel.orEmpty()
        ClientData(context).appendLocalProxyUnauthorizedAttempt(
            protocol = protocol,
            packageName = packageName,
            appLabel = appLabel,
            reason = reason,
        )
        val actor = when {
            appLabel.isNotBlank() && packageName.isNotBlank() -> "$appLabel ($packageName)"
            packageName.isNotBlank() -> packageName
            else -> "неизвестное приложение"
        }
        logger("Локальный прокси: неавторизованный доступ через $protocol от $actor: $reason")
    }
}

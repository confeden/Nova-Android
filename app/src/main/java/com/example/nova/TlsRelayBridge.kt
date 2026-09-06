package com.example.nova

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import javax.net.SocketFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Локальный мост до релея, который слушает **TLS**.
 *
 * Зачем он вообще нужен. Наши релеи (`relay.nova-app.eu:8443` и `:2053`) — это
 * обычные HTTP-прокси с `CONNECT` и Basic-авторизацией, но завёрнутые в TLS: до
 * прокси идёт TLS, и только внутри него — строка `CONNECT`. Ни `opera-proxy`
 * (резолвер Go на Android остаётся без настроек и уходит в `[::1]:53`), ни OkHttp
 * (`Proxy.Type.HTTP` разговаривает с прокси **открытым текстом** и завернуть эту
 * беседу в TLS не умеет) в такой прокси ходить не могут.
 *
 * Мост снимает ровно это несоответствие: слушает открытый порт на петле, а наружу
 * держит TLS-соединение с релеем и перекладывает байты. Разбирать HTTP ему не
 * нужно — `CONNECT`, `Proxy-Authorization` и всё остальное проходят насквозь.
 * Имя резолвит и сертификат проверяет Android, у которого и резолвер настроен, и
 * хранилище корней на месте.
 *
 * Учётных данных мост **не добавляет**: без них релей отвечает `407`, поэтому
 * открытый на петле порт не превращается в открытый прокси для соседних
 * приложений. Живёт он не дольше той работы, ради которой поднят.
 *
 * Экземпляр — один мост. Экземпляров может быть несколько: Opera и Proton ходят
 * через одни и те же релеи, но живут по разным расписаниям, и общий синглтон
 * означал бы, что старт одного гасит мост другого посреди запроса.
 *
 * @param threadName попадает в имена потоков — иначе в трассировке нельзя понять,
 *        чей мост держит соединение.
 */
class TlsRelayBridge(private val threadName: String) {

    private val lock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    /** Релей, под который поднят текущий мост. Пусто — мост не работает. */
    @Volatile
    private var activeRelay: String = ""

    @Volatile
    private var activeEndpoint: Endpoint? = null

    /** Адрес моста на петле и учётные данные релея, к которому он ведёт. */
    data class Endpoint(
        val localHost: String,
        val localPort: Int,
        val user: String,
        val password: String,
    ) {
        /** Тот же адрес ссылкой — в таком виде его ждёт `opera-proxy`. */
        val proxyUrl: String
            get() = buildString {
                append("http://")
                if (user.isNotEmpty()) {
                    append(user)
                    if (password.isNotEmpty()) {
                        append(':')
                        append(password)
                    }
                    append('@')
                }
                append(localHost)
                append(':')
                append(localPort)
            }
    }

    /**
     * Поднимает мост под конкретный релей.
     *
     * Повторный вызов с тем же адресом переиспользует уже открытый порт: каждый
     * шаг прогона Proton делает свой запрос, и поднимать мост заново на каждом
     * значило бы четыре лишних TLS-рукопожатия с релеем.
     *
     * @return null, если ссылка не разобралась или порт не открылся. Тогда попытку
     *         через этот релей нужно пропустить, а не идти в неё вслепую.
     */
    fun start(relayUrl: String, logger: (String) -> Unit): Endpoint? {
        val target = parseRelay(relayUrl) ?: run {
            logger("Мост $threadName: релей не разобрался как ссылка — ${describe(relayUrl)}")
            return null
        }
        synchronized(lock) {
            val current = activeEndpoint
            if (activeRelay == relayUrl && current != null && serverSocket?.isClosed == false) {
                return current
            }
            stopLocked(logger)
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 16)
                }
            } catch (e: Exception) {
                logger("Мост $threadName: локальный порт не открылся — ${e::class.java.simpleName}: ${e.message}")
                return null
            }
            serverSocket = server
            activeRelay = relayUrl
            val endpoint = Endpoint(
                localHost = LOOPBACK,
                localPort = server.localPort,
                user = decodeUserInfoPart(target.userInfo.substringBefore(':', target.userInfo)),
                password = decodeUserInfoPart(target.userInfo.substringAfter(':', "")),
            )
            activeEndpoint = endpoint
            acceptThread = thread(start = true, isDaemon = true, name = "nova-$threadName-bridge") {
                acceptLoop(server, target, logger)
            }
            logger(
                "Мост $threadName поднят на $LOOPBACK:${server.localPort} → " +
                    "${target.host}:${target.port} (${if (target.useTls) "TLS" else "без TLS"})."
            )
            return endpoint
        }
    }

    fun stop(logger: (String) -> Unit) {
        synchronized(lock) { stopLocked(logger) }
    }

    private fun stopLocked(logger: (String) -> Unit) {
        val server = serverSocket
        val thread = acceptThread
        serverSocket = null
        acceptThread = null
        activeRelay = ""
        activeEndpoint = null
        if (server == null) return
        runCatching { server.close() }
        thread?.interrupt()
        logger("Мост $threadName остановлен.")
    }

    private fun acceptLoop(server: ServerSocket, target: RelayTarget, logger: (String) -> Unit) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                // Закрытие сокета — штатное завершение, а не ошибка.
                return
            }
            thread(start = true, isDaemon = true, name = "nova-$threadName-conn") {
                serveConnection(client, target, logger)
            }
        }
    }

    private fun serveConnection(client: Socket, target: RelayTarget, logger: (String) -> Unit) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = READ_TIMEOUT_MS
            upstream = openUpstream(target)
            val remote = upstream
            pipe(client.getInputStream(), remote.getOutputStream(), client, remote)
            pipe(remote.getInputStream(), client.getOutputStream(), remote, client)
        } catch (e: Exception) {
            logger("Мост $threadName: соединение не установлено (${e::class.java.simpleName}: ${e.message}).")
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun openUpstream(target: RelayTarget): Socket {
        // Сокет создаётся отдельной строкой, а не внутри `apply`, потому что
        // `connect` бросает чаще всего остального: `UnknownHostException` при
        // сломанном DNS, таймаут при фильтрации. Из `apply` исключение уходит
        // мимо присваивания, и созданный дескриптор не закрывает уже никто.
        // Промах DNS при этом повторяется подряд десятками — измерено на
        // Pixel 4a 2026-09-06, где `relay.nova-app.eu` не резолвился в цикле.
        val plain = SocketFactory.getDefault().createSocket()
        try {
            plain.tcpNoDelay = true
            if (!target.useTls) plain.soTimeout = READ_TIMEOUT_MS
            plain.connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
        } catch (e: Throwable) {
            runCatching { plain.close() }
            throw e
        }
        if (!target.useTls) return plain
        // Всё, что после успешного connect, обязано закрывать `plain` при отказе.
        // Вызывающий этого не сделает: он закрывает `upstream`, а тот ещё null —
        // присваивание не состоялось. На сети, где оператор рвёт TLS к релею,
        // каждая попытка Proton теряла дескриптор, и за один прогон выдачи
        // профилей их набиралось столько, что процессу переставало хватать.
        var socket: SSLSocket? = null
        try {
            socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plain, target.host, target.port, true) as SSLSocket
            socket.soTimeout = READ_TIMEOUT_MS
            runCatching {
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            }
            socket.startHandshake()
            // Явная проверка имени на случай, если `endpointIdentificationAlgorithm` не
            // применился: молча принять чужой сертификат здесь — отдать релей вместе с
            // логином и паролем тому, кто перехватил соединение.
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(target.host, socket.session)) {
                throw java.io.IOException("сертификат релея не соответствует имени ${target.host}")
            }
            return socket
        } catch (e: Throwable) {
            // `createSocket(..., autoClose = true)` закрывает `plain` вместе с
            // собой, но только если сам успел получиться.
            runCatching { socket?.close() }
            runCatching { plain.close() }
            throw e
        }
    }

    private fun pipe(input: InputStream, output: OutputStream, source: Socket, sink: Socket) {
        thread(start = true, isDaemon = true, name = "nova-$threadName-pipe") {
            val buffer = ByteArray(16 * 1024)
            try {
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { source.close() }
                runCatching { sink.close() }
            }
        }
    }

    private data class RelayTarget(
        val host: String,
        val port: Int,
        val userInfo: String,
        val useTls: Boolean,
    )

    private fun parseRelay(relayUrl: String): RelayTarget? {
        val uri = runCatching { URI(relayUrl.trim()) }.getOrNull() ?: return null
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty()) return null
        val scheme = uri.scheme?.trim()?.lowercase().orEmpty()
        val useTls = scheme == "https"
        val port = uri.port.takeIf { it in 1..65535 } ?: if (useTls) 443 else 80
        return RelayTarget(
            host = host,
            port = port,
            userInfo = uri.rawUserInfo?.trim().orEmpty(),
            useTls = useTls,
        )
    }

    /**
     * Обратное процент-кодирование для логина и пароля.
     *
     * Ссылка релея собирается с процент-кодированием (пароль может содержать что
     * угодно), а Basic-авторизации нужны исходные байты. `URLDecoder` здесь не
     * годится ровно по той же причине, по какой не годится `URLEncoder` на другом
     * конце: он превращает «+» в пробел, а в userinfo плюс означает плюс.
     */
    private fun decodeUserInfoPart(value: String): String {
        if (!value.contains('%')) return value
        val out = java.io.ByteArrayOutputStream(value.length)
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            val hex = if (ch == '%' && index + 2 < value.length) {
                value.substring(index + 1, index + 3).toIntOrNull(16)
            } else {
                null
            }
            if (hex != null) {
                out.write(hex)
                index += 3
            } else {
                out.write(ch.code)
                index += 1
            }
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Для журнала: без логина и пароля. */
    private fun describe(relayUrl: String): String {
        val scheme = relayUrl.substringBefore("://", missingDelimiterValue = "")
        val rest = relayUrl.substringAfter("://", missingDelimiterValue = relayUrl)
        val hostPort = rest.substringAfterLast('@')
        return if (scheme.isEmpty()) hostPort else "$scheme://$hostPort"
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 45_000
    }
}

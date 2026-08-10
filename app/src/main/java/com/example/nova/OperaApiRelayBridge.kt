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
 * Локальный мост до релея API SurfEasy.
 *
 * Дефект, ради которого он появился: `opera-proxy` резолвит имя хоста из
 * `-api-proxy` штатным резолвером Go, а тот на Android остаётся без настроек —
 * файла `/etc/resolv.conf` в системе нет, и запрос уходит в `[::1]:53`, откуда
 * приходит `connection refused`. В журнале это выглядело так:
 *
 * ```
 * failed to dial api2.sec-tunnel.com:443: dial tcp:
 *   lookup relay.nova-app.eu on [::1]:53: read: connection refused
 * ```
 *
 * Флаг `-bootstrap-dns` тут не помогает: он документирован самим бинарником как
 * «resolvers for initial discovery of SurfEasy API address» и на адрес прокси не
 * распространяется. Передать вместо имени готовый IP тоже нельзя: сертификат
 * релея выписан Let's Encrypt строго на `relay.nova-app.eu` и адреса в SAN не
 * содержит, так что проверка имени провалится.
 *
 * Поэтому имя резолвит и TLS устанавливает сама Android — у неё и резолвер
 * настроен, и хранилище корневых сертификатов на месте, — а `opera-proxy`
 * получает адрес вида `http://логин:пароль@127.0.0.1:порт`, где имени резолвить
 * уже нечего. Мост только перекладывает байты: разбирать HTTP ему не нужно,
 * `CONNECT` и учётные данные проходят насквозь.
 *
 * Слушаем строго на петле и держим мост не дольше самого процесса `opera-proxy`
 * (останавливается в [OperaProxyManager.stopManaged]): открытый прокси к своему
 * релею не должен переживать попытку подключения.
 */
object OperaApiRelayBridge {

    private val lock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    /** Релей, под который поднят текущий мост. Пусто — мост не работает. */
    @Volatile
    private var activeRelay: String = ""

    @Volatile
    private var activeLocalUrl: String = ""

    private const val LOOPBACK = "127.0.0.1"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 45_000

    /**
     * Поднимает мост под конкретный релей и возвращает адрес для `-api-proxy`.
     *
     * Возвращает `null`, если релей не разобрался или порт не открылся: тогда
     * попытку через этот релей нужно пропустить, а не идти в неё вслепую.
     */
    fun start(relayUrl: String, logger: (String) -> Unit): String? {
        val target = parseRelay(relayUrl) ?: run {
            logger("Релей API не разобрался как ссылка, пропускаем: ${describe(relayUrl)}")
            return null
        }
        synchronized(lock) {
            if (activeRelay == relayUrl && serverSocket?.isClosed == false) {
                return activeLocalUrl
            }
            stopLocked(logger)
            val server = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 16)
                }
            } catch (e: Exception) {
                logger("Мост до релея API не открыл локальный порт: ${e::class.java.simpleName}: ${e.message}")
                return null
            }
            serverSocket = server
            activeRelay = relayUrl
            activeLocalUrl = buildString {
                append("http://")
                if (target.userInfo.isNotEmpty()) {
                    append(target.userInfo)
                    append('@')
                }
                append(LOOPBACK)
                append(':')
                append(server.localPort)
            }
            acceptThread = thread(start = true, isDaemon = true, name = "nova-opera-relay-bridge") {
                acceptLoop(server, target, logger)
            }
            logger(
                "Мост до релея API поднят на $LOOPBACK:${server.localPort} → " +
                    "${target.host}:${target.port} (${if (target.useTls) "TLS" else "без TLS"})."
            )
            return activeLocalUrl
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
        activeLocalUrl = ""
        if (server == null) return
        runCatching { server.close() }
        thread?.interrupt()
        logger("Мост до релея API остановлен.")
    }

    private fun acceptLoop(server: ServerSocket, target: RelayTarget, logger: (String) -> Unit) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: Exception) {
                // Закрытие сокета — штатное завершение, а не ошибка.
                return
            }
            thread(start = true, isDaemon = true, name = "nova-opera-relay-conn") {
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
            logger("Мост до релея API: соединение не установлено (${e::class.java.simpleName}: ${e.message}).")
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    private fun openUpstream(target: RelayTarget): Socket {
        if (!target.useTls) {
            return SocketFactory.getDefault().createSocket().apply {
                tcpNoDelay = true
                soTimeout = READ_TIMEOUT_MS
                connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
            }
        }
        // Имя резолвит Android, поэтому SNI и проверка сертификата берут настоящий
        // хост релея — то, чего не может сделать резолвер Go внутри opera-proxy.
        val plain = SocketFactory.getDefault().createSocket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS)
        }
        val socket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
            .createSocket(plain, target.host, target.port, true) as SSLSocket
        socket.soTimeout = READ_TIMEOUT_MS
        runCatching {
            socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        }
        socket.startHandshake()
        // Явная проверка имени на случай, если endpointIdentificationAlgorithm не
        // применился: молча принять чужой сертификат здесь — отдать релей вместе с
        // логином и паролем тому, кто перехватил соединение.
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(target.host, socket.session)) {
            runCatching { socket.close() }
            throw java.io.IOException("сертификат релея не соответствует имени ${target.host}")
        }
        return socket
    }

    private fun pipe(input: InputStream, output: OutputStream, source: Socket, sink: Socket) {
        thread(start = true, isDaemon = true, name = "nova-opera-relay-pipe") {
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

    /** Для журнала: без логина и пароля. */
    private fun describe(relayUrl: String): String {
        val scheme = relayUrl.substringBefore("://", missingDelimiterValue = "")
        val rest = relayUrl.substringAfter("://", missingDelimiterValue = relayUrl)
        val hostPort = rest.substringAfterLast('@')
        return if (scheme.isEmpty()) hostPort else "$scheme://$hostPort"
    }
}

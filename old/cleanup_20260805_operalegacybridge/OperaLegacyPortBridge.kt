package com.example.nova

import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Переадресация с `127.0.0.1:1085` на действующий порт Opera-прокси.
 *
 * Зачем это нужно. Go-ядро умеет отправлять запросы к API Cloudflare через
 * локальный прокси и для MASQUE предпочитает именно этот путь — регистрация
 * `masque-enroll` помечена так, что прокси пробуется первым. Но адрес прокси в
 * ядре записан жёстко: `http://127.0.0.1:1085`. Приложение же поднимает Opera на
 * случайном порту из диапазона 20080–40999, а 1085 остался лишь историческим
 * значением по умолчанию.
 *
 * Из-за этого проверка доступности прокси в ядре не срабатывала никогда, и
 * регистрация MASQUE уходила напрямую — в ту самую фильтрацию по имени узла,
 * из-за которой она и не проходит.
 *
 * Мост согласует две половины приложения, не требуя пересборки нативной части.
 * Правильное решение на будущее — сделать адрес прокси в ядре настраиваемым;
 * тогда этот файл можно удалить.
 */
object OperaLegacyPortBridge {

    private const val LEGACY_PORT = 1085
    private const val LOOPBACK = "127.0.0.1"

    @Volatile
    private var listener: ServerSocket? = null

    @Volatile
    private var targetPort: Int = 0

    private val running = AtomicBoolean(false)

    @Synchronized
    fun sync(actualPort: Int, logger: (String) -> Unit) {
        if (actualPort !in 1..65535 || actualPort == LEGACY_PORT) {
            // Прокси уже слушает тот адрес, который ждёт ядро.
            stop(logger)
            return
        }
        if (running.get() && targetPort == actualPort && listener?.isClosed == false) return

        stop(logger)
        val socket = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(LOOPBACK, LEGACY_PORT))
            }
        }.getOrElse { error ->
            logger("Мост Opera на $LEGACY_PORT не поднялся: ${error.message}")
            return
        }

        targetPort = actualPort
        running.set(true)
        listener = socket
        thread(start = true, isDaemon = true, name = "NovaOperaLegacyBridge") {
            while (running.get() && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: continue
                thread(start = true, isDaemon = true, name = "NovaOperaLegacyBridgeConn") {
                    relay(client, targetPort)
                }
            }
        }
        logger("Мост Opera: $LOOPBACK:$LEGACY_PORT → $LOOPBACK:$actualPort (для регистрации MASQUE в ядре).")
    }

    @Synchronized
    fun stop(logger: (String) -> Unit) {
        running.set(false)
        val active = listener
        listener = null
        if (active != null) {
            runCatching { active.close() }
            logger("Мост Opera остановлен.")
        }
    }

    private fun relay(client: Socket, port: Int) {
        client.use { downstream ->
            val upstream = runCatching {
                Socket().apply {
                    tcpNoDelay = true
                    connect(InetSocketAddress(LOOPBACK, port), 4_000)
                }
            }.getOrNull() ?: return
            upstream.use { remote ->
                downstream.tcpNoDelay = true
                val pump = thread(start = true, isDaemon = true) {
                    runCatching { downstream.getInputStream().copyTo(remote.getOutputStream()) }
                    runCatching { remote.shutdownOutput() }
                }
                runCatching { remote.getInputStream().copyTo(downstream.getOutputStream()) }
                runCatching { downstream.shutdownOutput() }
                runCatching { pump.join(1_000L) }
            }
        }
    }
}

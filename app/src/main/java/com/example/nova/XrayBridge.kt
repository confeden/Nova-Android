package com.example.nova

import android.net.VpnService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Мост к libnovaxray.so — ядру Xray, собранному отдельной c-shared библиотекой.
 *
 * Ядро живёт в собственном модуле, а не внутри nova-core: gomobile кладёт в
 * каждый .aar свой libgojni.so и классы пакета `go`, поэтому две
 * gomobile-библиотеки в одном APK конфликтуют, а слить Xray в nova-core мешает
 * несовместимость quic-go.
 */
object XrayBridge {

    @Volatile
    private var libraryLoaded = false

    @Volatile
    private var loadError: String = ""

    /**
     * Все вызовы в ядро идут с одного выделенного потока.
     *
     * В процессе живут два независимых рантайма Go: `libgojni.so` от nova-core и
     * `libnovaxray.so`. Каждый при входе через cgo помечает поток своей структурой в
     * TLS, и слот у них общий. Поток, уже побывавший в nova-core (а поток подключения
     * успевает вызвать хотя бы `setSocketProtector`), уносил ядро Xray в
     * `fatal error: unknown caller pc` при первом же росте стека — на разборе
     * конфигурации.
     *
     * Поток создаётся один раз и в nova-core не заходит никогда, поэтому рантаймы не
     * пересекаются.
     */
    private val executor: ExecutorService by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "NovaXrayCore").apply { isDaemon = true }
        }
    }

    private fun <T> onCoreThread(timeoutSeconds: Long, fallback: T, block: () -> T): T {
        return try {
            executor.submit(block).get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            fallback
        }
    }

    /**
     * Загружает библиотеку. Возвращает false, если сборка под эту архитектуру
     * отсутствует, — тогда VLESS просто недоступен, а WARP продолжает работать.
     */
    @Synchronized
    fun ensureLoaded(): Boolean {
        if (libraryLoaded) return true
        return try {
            // libnovaxrayjni.so зависит от libnovaxray.so, поэтому порядок важен.
            System.loadLibrary("novaxray")
            System.loadLibrary("novaxrayjni")
            libraryLoaded = true
            true
        } catch (e: Throwable) {
            loadError = e.message.orEmpty()
            false
        }
    }

    fun lastLoadError(): String = loadError

    /**
     * Передаёт ядру сервис, через который защищаются исходящие сокеты.
     * Без этого трафик Xray завернулся бы обратно в собственный туннель.
     */
    fun setProtector(service: VpnService?) {
        if (!ensureLoaded()) return
        onCoreThread(5L, Unit) { nativeSetProtector(service) }
    }

    /**
     * Возвращает пустую строку при успехе или текст ошибки.
     *
     * Предыдущий экземпляр останавливается сам: ядро отвечает на повторный запуск
     * ошибкой `xray already running`, и при реконнекте после смены сети фаза VLESS
     * из-за этого срывалась, хотя узел был жив.
     */
    fun start(configJson: String): String {
        if (!ensureLoaded()) return "библиотека Xray недоступна: ${lastLoadError()}"
        return onCoreThread(25L, "ядро Xray не ответило на запуск") {
            if (nativeIsRunning()) {
                nativeStop()
            }
            nativeStart(configJson)
        }
    }

    fun stop() {
        if (!libraryLoaded) return
        onCoreThread(10L, Unit) { nativeStop() }
    }

    fun isRunning(): Boolean = libraryLoaded && onCoreThread(5L, false) { nativeIsRunning() }

    fun version(): String =
        if (ensureLoaded()) onCoreThread(5L, "") { nativeVersion() } else ""

    @JvmStatic
    private external fun nativeSetProtector(service: VpnService?)

    @JvmStatic
    private external fun nativeStart(configJson: String): String

    @JvmStatic
    private external fun nativeStop()

    @JvmStatic
    private external fun nativeIsRunning(): Boolean

    @JvmStatic
    private external fun nativeVersion(): String
}

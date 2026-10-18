package com.example.nova

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Лента узлов Proton, обновляемая вне релизов.
 *
 * Встроенный `proton_nodes.json` стареет между релизами: из России живы около трёх
 * узлов из двенадцати, и мёртвые мёртвы на уровне узла, а не порта. Ленту раз в
 * несколько часов пересобирает GitHub Actions (`tools/proton_feed/generate_feed.py`, копия в `feeds/nova_updates_pack/`)
 * и кладёт в репозиторий обновлений (`confeden/nova_updates`, каталог `nova_android/`); приложение забирает её обычным GET с raw.githubusercontent
 * либо с зеркала — `/vpn/logicals` для этого не нужен.
 *
 * Как и у прошивочного списка, в ленте только публичные факты об узле (адрес входа,
 * публичный ключ пира, страна, город, имя). Ключей и аккаунта в ней нет.
 *
 * Хранится в `filesDir` через [AtomicFile], не в SharedPreferences (I2): файл читают
 * оба процесса, `:vpn` и основной, и обрыв записи не должен оставить половину.
 */
object ProtonNodeFeed {

    private val FEED_URLS = listOf(
        "https://raw.githubusercontent.com/confeden/nova_updates/main/nova_android/proton_nodes.json",
        "https://nova-app.eu/feeds/proton_nodes.json",
    )

    private const val FILE_NAME = "proton_nodes_feed.json"
    private const val MIN_NODES = 10
    private const val REFRESH_MS = 6L * 3_600_000L
    private const val TIMEOUT_MS = 6000
    private const val MAX_BYTES = 512 * 1024

    private val running = AtomicBoolean(false)

    @Volatile
    private var lastAttemptAt = 0L

    private fun file(context: Context) = AtomicFile(File(context.applicationContext.filesDir, FILE_NAME))

    /** Узлы последней принятой ленты; пустой список, если её нет или она не разобралась. */
    fun loadCached(context: Context): List<ProtonApi.Server> {
        return runCatching {
            val atomic = file(context)
            if (!atomic.baseFile.exists()) return emptyList()
            ProtonNodeCatalog.parseNodes(String(atomic.readFully(), Charsets.UTF_8))
        }.getOrElse { error ->
            LogManager.log("Proton: лента узлов не прочиталась — ${error.message}")
            emptyList()
        }
    }

    /**
     * Обновляет ленту, если с прошлой попытки прошло больше шести часов. Возвращается
     * сразу: работа идёт на отдельном потоке, ошибки только в журнал.
     */
    fun refreshIfStale(context: Context) {
        val appContext = context.applicationContext
        val now = System.currentTimeMillis()
        val atomic = file(appContext)
        val fileAt = atomic.baseFile.takeIf { it.exists() }?.lastModified() ?: 0L
        if (now - maxOf(fileAt, lastAttemptAt) < REFRESH_MS) return
        if (!running.compareAndSet(false, true)) return
        lastAttemptAt = now
        Thread({
            try {
                refresh(atomic)
            } catch (error: Throwable) {
                LogManager.log("Proton: лента узлов — сбой обновления: ${error.message}")
            } finally {
                running.set(false)
            }
        }, "proton-node-feed").start()
    }

    private fun refresh(atomic: AtomicFile) {
        for (url in FEED_URLS) {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "NovaAndroidFeed/1.0")
                val bytes = try {
                    if (connection.responseCode != 200) throw IllegalStateException("HTTP ${connection.responseCode}")
                    connection.inputStream.use { it.readBytes() }
                } finally {
                    connection.disconnect()
                }
                if (bytes.size > MAX_BYTES) throw IllegalStateException("слишком большой ответ")
                val nodes = ProtonNodeCatalog.parseNodes(String(bytes, Charsets.UTF_8))
                if (nodes.size < MIN_NODES) {
                    throw IllegalStateException("в ленте ${nodes.size} узлов, нужно не меньше $MIN_NODES")
                }
                val stream = atomic.startWrite()
                try {
                    stream.write(bytes)
                    atomic.finishWrite(stream)
                } catch (error: Throwable) {
                    atomic.failWrite(stream)
                    throw error
                }
                LogManager.log("Proton: лента узлов обновлена — ${nodes.size} шт. ($url)")
                return
            } catch (error: Exception) {
                LogManager.log("Proton: лента узлов с $url не получена — ${error.message}")
            }
        }
    }
}

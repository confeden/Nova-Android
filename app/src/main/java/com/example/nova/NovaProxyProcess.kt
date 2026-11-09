package com.example.nova

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Помощник протоколов `nova-proxy` (Mihomo 1.19.33) — отдельным процессом.
 *
 * Тот же исходник, что у Nova PC (`nova-proxy/`, сборка `tools/build_nova_proxy.sh`):
 * VLESS (Reality, XHTTP, gRPC, WS), VMess, Trojan, Shadowsocks/2022, Hysteria 1/2,
 * TUIC, AnyTLS, WireGuard, HTTP и SOCKS. Узлы списка Nova сервер проверяет этим
 * же ядром, поэтому и поднимает их здесь оно, а не Xray.
 *
 * Процесс, а не библиотека, по тем же причинам, что у [DnsTunnelProcess]: второй
 * Go-рантайм в `:vpn` не нужен, падение движка не уносит службу, а сокеты его идут
 * мимо туннеля, потому что Nova всегда исключает свой пакет из VPN (G154).
 *
 * Помощник сам выходит, когда умирает родитель (`--parent-pid`): осиротевший
 * движок не держит порт после убитого `:vpn`.
 */
object NovaProxyProcess {

    const val BINARY = "libnovaproxy.so"
    private const val READY_TIMEOUT_MS = 15_000L

    private val process = AtomicReference<Process?>(null)

    fun isAvailable(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir.orEmpty(), BINARY).exists()

    fun isRunning(): Boolean = process.get()?.isAlive ?: false

    /**
     * Поднимает один узел и возвращает порт локального SOCKS5; 0 — не поднялся.
     * Готовность — файл-свидетель, который помощник пишет только после того, как
     * сам дозвонился до своего слушателя.
     */
    fun start(context: Context, node: NovaList.Node, logger: (String) -> Unit, preferredPort: Int = 0): Int {
        stop(logger)
        killStale(logger)
        val binary = binary(context) ?: run {
            logger("Nova: движок $BINARY не найден для ABI ${android.os.Build.SUPPORTED_ABIS.joinToString()}.")
            return 0
        }
        // Смена узла под живым туннелем: tun2proxy уже смотрит на этот порт, и новый
        // движок встаёт на него же — пересборка туннеля (и остановка tun2proxy) не нужна.
        val port = if (preferredPort in 1..65535 && awaitPortFree(preferredPort)) preferredPort else freePort()
        if (port == 0) {
            logger("Nova: не нашлось свободного локального порта под SOCKS5.")
            return 0
        }
        val workDir = File(context.filesDir, "novaproxy").apply { mkdirs() }
        val request = File(workDir, "runtime.json")
        val ready = File(workDir, "ready.json")
        val map = JSONObject(node.json.toString())
        val name = map.optString("name").ifEmpty { node.name }
        map.put("name", name)
        try {
            ready.delete()
            request.writeText(
                JSONObject()
                    .put("proxies", JSONArray().put(map))
                    .put("selected", name)
                    .put("socks_port", port)
                    .put("http_port", 0)
                    .toString()
            )
            // Учётные данные узла — только в приватном каталоге приложения.
            request.setReadable(false, false)
            request.setReadable(true, true)
        } catch (error: Exception) {
            logger("Nova: не удалось записать запрос движку: ${error.message}")
            return 0
        }
        val started = try {
            builder(context, binary, listOf(
                "run",
                "--config", request.absolutePath,
                "--ready-file", ready.absolutePath,
                "--parent-pid", android.os.Process.myPid().toString(),
            ), workDir).start()
        } catch (error: Exception) {
            logger("Nova: движок не запустился: ${error.message}")
            return 0
        }
        process.set(started)
        val output = StringBuilder()
        Thread({
            try {
                BufferedReader(InputStreamReader(started.inputStream)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        synchronized(output) { if (output.length < 2000) output.append(line).append('\n') }
                        LogManager.d("Nova-движок: $line")
                    }
                }
            } catch (_: Exception) {
            } finally {
                if (process.get() === started) {
                    val code = runCatching { started.exitValue() }.getOrNull()
                    logger("Nova: движок завершился (код $code).")
                    process.compareAndSet(started, null)
                }
            }
        }, "NovaProxyLog").apply { isDaemon = true; start() }

        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ready.exists() && ready.length() > 0) {
                runCatching { request.delete() }
                return port
            }
            if (!started.isAlive) break
            Thread.sleep(50)
        }
        val reason = synchronized(output) { output.toString().trim() }.ifEmpty {
            if (started.isAlive) "нет готовности за ${READY_TIMEOUT_MS / 1000} с" else "движок завершился"
        }
        logger("Nova: движок не принял узел ${node.name} — $reason.")
        runCatching { request.delete() }
        stop(logger)
        return 0
    }

    fun stop(logger: (String) -> Unit) {
        val running = process.getAndSet(null) ?: return
        if (!running.isAlive) return
        try {
            running.destroy()
            if (!running.waitFor(1500, TimeUnit.MILLISECONDS)) {
                running.destroyForcibly()
                running.waitFor(1500, TimeUnit.MILLISECONDS)
            }
        } catch (error: Exception) {
            logger("Nova: остановка движка не удалась: ${error.message}")
        }
    }

    /**
     * Проверка узлов настоящим HTTPS 204 через сам узел — как у сборщика списка и
     * у кнопки «Проверить» на ПК. Результаты приходят по одному по мере готовности;
     * задержка — время ответа 204 через узел, то есть то, что человек и почувствует.
     *
     * Вызывать из фонового потока: блокирует до конца проверки или [shouldAbort].
     */
    fun probe(
        context: Context,
        nodes: List<NovaList.Node>,
        workers: Int,
        timeoutSeconds: Int,
        shouldAbort: () -> Boolean,
        onResult: (NovaList.Node, NovaList.Ping) -> Unit,
    ): Boolean {
        if (nodes.isEmpty()) return true
        val binary = binary(context) ?: return false
        val workDir = File(context.cacheDir, "novaproxy-probe").apply { mkdirs() }
        val started = try {
            builder(context, binary, listOf(
                "probe-list",
                "-workers", workers.coerceIn(1, 64).toString(),
                "-timeout", timeoutSeconds.coerceIn(1, 60).toString(),
            ), workDir).redirectErrorStream(false).start()
        } catch (error: Exception) {
            LogManager.log("Nova: проверка профилей не запустилась: ${error.message}")
            return false
        }
        try {
            val body = JSONObject().put("proxies", JSONArray().apply { nodes.forEach { put(it.json) } }).toString()
            started.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            BufferedReader(InputStreamReader(started.inputStream)).use { reader ->
                while (true) {
                    if (shouldAbort()) {
                        started.destroyForcibly()
                        return false
                    }
                    val line = reader.readLine() ?: break
                    val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                    val node = nodes.getOrNull(o.optInt("index", -1)) ?: continue
                    val ok = o.optBoolean("ok", false)
                    onResult(node, NovaList.Ping(if (ok) o.optInt("ms", -1) else -1, ok, System.currentTimeMillis()))
                }
            }
            started.waitFor(2, TimeUnit.SECONDS)
            return true
        } catch (error: Exception) {
            LogManager.d("Nova: проверка профилей прервана: ${error.message}")
            return false
        } finally {
            if (started.isAlive) started.destroyForcibly()
        }
    }

    private fun binary(context: Context): File? =
        File(context.applicationInfo.nativeLibraryDir.orEmpty(), BINARY).takeIf { it.exists() }

    private fun builder(context: Context, binary: File, args: List<String>, dir: File): ProcessBuilder {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        return ProcessBuilder(listOf(binary.absolutePath) + args).apply {
            directory(dir)
            redirectErrorStream(true)
            val env = environment()
            val existing = env["LD_LIBRARY_PATH"].orEmpty().trim()
            env["LD_LIBRARY_PATH"] = if (existing.isBlank()) nativeLibDir else "$nativeLibDir:$existing"
        }
    }

    /** Снимает движок «run», переживший прошлый `:vpn` (проверки экрана не трогает). */
    private fun killStale(logger: (String) -> Unit) {
        val self = android.os.Process.myPid()
        for (entry in File("/proc").list().orEmpty()) {
            val pid = entry.toIntOrNull() ?: continue
            if (pid == self) continue
            val cmdline = runCatching { File("/proc/$entry/cmdline").readText() }.getOrNull() ?: continue
            if (!cmdline.contains(BINARY) || !cmdline.contains("\u0000run\u0000")) continue
            runCatching { android.os.Process.killProcess(pid) }
            logger("Nova: снят осиротевший движок (pid $pid).")
        }
    }

    private fun awaitPortFree(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + 2_000L
        while (true) {
            val free = try {
                ServerSocket().use {
                    it.reuseAddress = true
                    it.bind(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1)
                }
                true
            } catch (_: Exception) {
                false
            }
            if (free) return true
            if (System.currentTimeMillis() >= deadline) return false
            Thread.sleep(100)
        }
    }

    private fun freePort(): Int = try {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    } catch (_: Exception) {
        0
    }
}

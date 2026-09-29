package com.example.nova

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Движок DNS-туннеля семейства StormDNS/CottenDNS — отдельным процессом.
 *
 * ## Почему процесс, а не библиотека в ядре
 *
 * Полезное у этого клиента лежит в `internal/`, а такие пакеты Go запрещает
 * импортировать из чужого модуля — «просто добавить зависимость» в nova-core
 * невозможно без форка. Форк же пришлось бы тянуть и патчить при каждом
 * обновлении, а заодно объяснять F-Droid, откуда взялся изменённый исходник.
 * Процесс снимает и то, и другое: собирается ровно апстрим по закреплённому
 * коммиту (`tools/build_stormdns_client.sh`), а падение движка не уносит с
 * собой `:vpn`.
 *
 * Ровно так же в Nova живёт `liboperaproxy.so`, и правила те же: имя `lib*.so`
 * нужно, чтобы Android распаковал файл из APK исполняемым, а `LD_LIBRARY_PATH`
 * указывает на каталог родных библиотек.
 *
 * ## Почему его трафик не заворачивается в наш же туннель
 *
 * Дочерний процесс наследует наш UID, а Nova **всегда** исключает собственный
 * пакет из своего VPN (`applyDefaultSplitTunnelExclusions`). Поэтому запросы
 * движка идут мимо tun, и `protect()` каждому сокету не нужен — это тот же
 * приём, которым живут Opera и tor, и та же причина, по которой он обязателен
 * (G154).
 */
object DnsTunnelProcess {

    /** Имя исполняемого файла в каталоге родных библиотек. */
    const val BINARY = "libstormdns.so"

    /**
     * Сколько ждём готовности.
     *
     * Клиент сначала меряет MTU по каждой несущей и только потом открывает
     * локальный порт. На живых точках выхода перебор занимал от 4 до 40 секунд:
     * первый заход по несущей часто пустой, и удача приходит со второго круга.
     * Минута с запасом — это «долго, но честно», а не «зависли».
     */
    private const val READY_TIMEOUT_MS = 90_000L

    /** Строки, которыми движок сам сообщает о судьбе сессии. */
    private const val SESSION_UP_MARK = "Session Initialized Successfully"
    private const val SESSION_DOWN_MARK = "Session initialization failed"

    private val process = AtomicReference<Process?>(null)

    /**
     * Держит ли движок сессию с дальним концом — по его собственным словам.
     *
     * Живого процесса для ответа «туннель работает» мало: потеряв точку выхода,
     * клиент не умирает, а бесконечно пересобирает сессию («Session
     * initialization failed», отход, снова). Поэтому признак берётся из его
     * вывода, где он сам говорит, что сессия собрана или развалилась.
     */
    @Volatile
    private var sessionUp = false

    /** Идёт ли сейчас наш движок. */
    fun isRunning(): Boolean = process.get()?.let { it.isAlive } ?: false

    /**
     * Работает ли туннель прямо сейчас.
     *
     * Это **не** проба: сквозь DNS-туннель обычная проба Nova не проходит по
     * построению — ей отпущено 420 мс на цель, а здесь одно соединение идёт
     * секунды. Поэтому живость берётся у самого движка, который держит сессию и
     * знает о ней больше, чем внешний наблюдатель. Слабее сквозной проверки и
     * честно слабее: сессия может стоять, пока дальний конец уже не отвечает.
     */
    fun isTunnelUp(): Boolean = isRunning() && sessionUp

    /** Есть ли движок в этой сборке — на чужой ABI его может не быть. */
    fun isAvailable(context: Context): Boolean =
        File(context.applicationInfo.nativeLibraryDir.orEmpty(), BINARY).exists()

    /**
     * Поднимает туннель и возвращает порт локального SOCKS5; 0 — не поднялся.
     *
     * @param shouldAbort спрашивается, пока идёт перебор: человек мог нажать
     *        «отключить», и тогда ждать минуту готовности не для кого.
     */
    fun start(
        context: Context,
        profile: DnsProfile,
        resolvers: List<DnsTunnelResolver>,
        logger: (String) -> Unit,
        shouldAbort: () -> Boolean = { false },
    ): Int {
        stop(logger)
        killStale(logger)
        sessionUp = false

        val nativeLibDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        val binary = File(nativeLibDir, BINARY)
        if (!binary.exists()) {
            logger("DNS: движок $BINARY не найден для ABI ${android.os.Build.SUPPORTED_ABIS.joinToString()}.")
            return 0
        }

        val resolverText = StormDnsConfig.resolverFile(resolvers)
        if (resolverText.isBlank()) {
            logger("DNS: ни одной несущей по UDP — движку не с чем работать.")
            return 0
        }

        val port = freePort()
        if (port == 0) {
            logger("DNS: не нашлось свободного локального порта под SOCKS5 движка.")
            return 0
        }

        val workDir = File(context.filesDir, "dnstunnel")
        val configFile = File(workDir, "client_config.toml")
        val resolverFile = File(workDir, "client_resolvers.txt")
        try {
            workDir.mkdirs()
            configFile.writeText(StormDnsConfig.clientConfig(profile, port))
            resolverFile.writeText(resolverText)
        } catch (error: Exception) {
            logger("DNS: не удалось записать конфигурацию движка: ${error.message}")
            return 0
        }

        val started = try {
            ProcessBuilder(
                listOf(
                    binary.absolutePath,
                    "-config", configFile.absolutePath,
                    "-resolvers", resolverFile.absolutePath,
                )
            ).apply {
                directory(workDir)
                redirectErrorStream(true)
                val env = environment()
                val existing = env["LD_LIBRARY_PATH"].orEmpty().trim()
                env["LD_LIBRARY_PATH"] = when {
                    existing.isBlank() -> nativeLibDir
                    existing.split(':').any { it == nativeLibDir } -> existing
                    else -> "$nativeLibDir:$existing"
                }
            }.start()
        } catch (error: Exception) {
            logger("DNS: движок не запустился: ${error.message}")
            return 0
        }
        process.set(started)

        // Поток чтения живёт дольше ожидания намеренно: если перестать читать
        // вывод, труба переполнится и дочерний процесс встанет на записи в неё
        // ровно тогда, когда туннель уже работает и ничего не подозревает.
        val ready = CountDownLatch(1)
        val listenPort = java.util.concurrent.atomic.AtomicInteger(0)
        val failure = AtomicReference("")
        Thread({ pump(started, ready, listenPort, failure, logger) }, "NovaDnsEngineLog").apply {
            isDaemon = true
            start()
        }

        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (ready.await(500, TimeUnit.MILLISECONDS)) break
            if (shouldAbort()) {
                logger("DNS: подъём движка прерван — отключение по ходу перебора несущих.")
                stop(logger)
                return 0
            }
            if (!started.isAlive) break
        }

        val resolved = listenPort.get()
        if (resolved != 0) return resolved

        val reason = failure.get().ifEmpty {
            if (started.isAlive) "движок не дошёл до готовности за ${READY_TIMEOUT_MS / 1000} с"
            else "движок завершился, не открыв порт"
        }
        logger("DNS: туннель не поднялся — $reason.")
        stop(logger)
        return 0
    }

    /** Снимает движок; вызывать можно всегда, даже если он и не запускался. */
    fun stop(logger: (String) -> Unit) {
        sessionUp = false
        val running = process.getAndSet(null) ?: return
        if (!running.isAlive) return
        try {
            running.destroy()
            if (!running.waitFor(1500, TimeUnit.MILLISECONDS)) {
                running.destroyForcibly()
                running.waitFor(1500, TimeUnit.MILLISECONDS)
            }
        } catch (error: Exception) {
            logger("DNS: остановка движка не удалась: ${error.message}")
        }
    }

    /**
     * Снимает движок, переживший прошлый запуск.
     *
     * `am force-stop` его уносит — это проверено на Pixel 4a, движка после него
     * в `ps` нет: система бьёт по всему UID. А вот когда система убивает **один**
     * процесс `:vpn` под нехваткой памяти, дочерний остаётся: его переусыновляет
     * `init`, и он продолжает опрашивать резолверы, хотя его локальный SOCKS5
     * уже никому не нужен. Этот случай выведен из устройства Android, а не
     * наблюдён, и стоит он один обход `/proc` на подключение.
     *
     * Найти движок можно только своим же UID: `/proc` показывает приложению
     * лишь его собственные процессы — то самое ограничение, которое здесь
     * работает на нас.
     */
    private fun killStale(logger: (String) -> Unit) {
        val self = android.os.Process.myPid()
        val pids = File("/proc").list().orEmpty()
        for (entry in pids) {
            val pid = entry.toIntOrNull() ?: continue
            if (pid == self) continue
            val cmdline = runCatching { File("/proc/$entry/cmdline").readText() }.getOrNull() ?: continue
            if (!cmdline.contains(BINARY)) continue
            runCatching { android.os.Process.killProcess(pid) }
            logger("DNS: снят осиротевший движок туннеля (pid $pid).")
        }
    }

    /**
     * Читает вывод движка до конца его жизни.
     *
     * Событием считается только готовность и провал: клиент печатает строку на
     * каждую проверенную несущую, и в журнал событий такое не кладут (I30).
     */
    private fun pump(
        running: Process,
        ready: CountDownLatch,
        listenPort: java.util.concurrent.atomic.AtomicInteger,
        failure: AtomicReference<String>,
        logger: (String) -> Unit,
    ) {
        try {
            BufferedReader(InputStreamReader(running.inputStream)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    LogManager.d("DNS-движок: $line")
                    when {
                        line.contains(SESSION_UP_MARK) -> sessionUp = true
                        line.contains(SESSION_DOWN_MARK) -> sessionUp = false
                    }
                    if (listenPort.get() == 0) {
                        val port = StormDnsConfig.listenerPort(line)
                        if (port != 0) {
                            listenPort.set(port)
                            ready.countDown()
                            continue
                        }
                        val reason = StormDnsConfig.failureReason(line)
                        if (reason.isNotEmpty()) failure.set(reason)
                    }
                }
            }
        } catch (_: Exception) {
            // Труба закрылась вместе с процессом — это и есть штатный конец.
        } finally {
            ready.countDown()
            sessionUp = false
            // Движок, умерший под работающим туннелем, обязан сказать об этом:
            // молча он оставил бы VPN стоять поверх порта, который больше никто
            // не слушает (I4). Остановленный нами — не событие, его нет в карте.
            if (process.get() === running) {
                val code = runCatching { running.exitValue() }.getOrNull()
                logger("DNS: движок туннеля завершился (код $code).")
                process.compareAndSet(running, null)
            }
        }
    }

    /**
     * Свободный порт на петле.
     *
     * Движок требует порт в конфигурации заранее — «ноль, а ты скажи какой» он
     * не умеет. Поэтому порт занимает и тут же отпускает система: между
     * закрытием и запуском есть щель, но она в сотни раз уже, чем шанс угадать
     * занятый порт из фиксированного числа.
     */
    private fun freePort(): Int = try {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    } catch (_: Exception) {
        0
    }
}

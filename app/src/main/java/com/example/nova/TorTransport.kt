package com.example.nova

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import org.torproject.jni.TorService
import java.io.File

/**
 * Транспорт Tor: сам tor в нашем процессе, obfs4 — в ядре, мосты — свои.
 *
 * ## Как это собрано
 *
 * ```
 * TUN → tun2proxy → 127.0.0.1:SocksPort (tor)
 *                     └─ ClientTransportPlugin obfs4 socks5 127.0.0.1:N (ядро Go)
 *                          └─ protect()'нутый TCP → мост
 * ```
 *
 * Ключевое здесь — **где открываются настоящие сокеты**. Они открываются в
 * ядре Go, то есть у нас, и потому помечаются `VpnService.protect()`. Готовый
 * бинарь-транспорт (lyrebird) так пометить нельзя: его сокеты принадлежат
 * чужому процессу, и мост завернулся бы в наш же туннель (G154). По этой же
 * причине **берутся только мосты obfs4**: у vanilla-моста tor соединяется сам,
 * своим сокетом, и такое соединение уходит в TUN — то есть в tun2proxy, то есть
 * обратно в tor. Петля.
 *
 * ## Почему tor, а не «свой Tor на Go»
 *
 * SOCKS у obfs4 ведёт на ORPort моста, и говорить с ним умеет только сам tor.
 * `info.guardianproject:tor-android` — это настоящий tor (BSD-3, minSdk 24),
 * запускаемый через JNI в нашем процессе, а не отдельным исполняемым файлом.
 *
 * ## Что здесь не делается
 *
 * Не трогается сеть до того, как мосты прочитаны и ядро подняло obfs4: пустой
 * список мостов — это отказ с внятной причиной, а не «tor запущен и молчит».
 */
object TorTransport {

    /** Сколько ждать полной загрузки tor. Через мост это десятки секунд, не единицы. */
    const val BOOTSTRAP_TIMEOUT_MS = 150_000L

    /** Как часто спрашивать tor о ходе загрузки. */
    private const val POLL_INTERVAL_MS = 1_000L

    private val lock = Any()

    @Volatile
    private var boundService: TorService? = null

    @Volatile
    private var connection: ServiceConnection? = null

    @Volatile
    private var obfs4Address: String = ""

    @Volatile
    private var lastBootstrapLine: String = ""

    /** Последний доложенный ответ про частный DNS: чтобы не писать его на каждом кадре. */
    @Volatile
    private var lastPrivateDnsSnapshot: String = ""

    /**
     * Просьба остановиться, выставленная **вне** общего монитора.
     *
     * `start` держит `lock` до полутора минут ожидания загрузки, а `stop` берёт
     * тот же монитор — значит остановка во время подъёма ждала бы конца подъёма.
     * Флаг читают все ожидания внутри `start`, поэтому остановка доходит сразу.
     */
    @Volatile
    private var stopRequested: Boolean = false

    /** Порт SOCKS уже загруженного tor, или 0. */
    @Volatile
    var socksPort: Int = 0
        private set

    fun isRunning(): Boolean = boundService != null

    /**
     * Имя сервера строгого «Частного DNS», если он включён в системе, иначе пусто.
     *
     * Зачем это транспорту Tor. Строгий режим означает, что netd резолвит имена
     * **только** по DoT к этому серверу и никогда не спрашивает открытым UDP. У
     * Tor в SOCKS нет UDP вовсе, поэтому обычный путь (виртуальный DNS
     * tun2proxy, который сам отвечает на запрос и отдаёт имя наружу) до netd не
     * доходит: тот в эту дверь не стучится. Остаётся сам DoT, а он через Tor не
     * идёт — измерено на Pixel 4a 2026-09-10: три попытки TCP/853 к
     * `dns.dns-ai.ru` через цепочку, все три по тридцать секунд в тайм-аут.
     *
     * Вывести сервер DoT мимо туннеля тоже нельзя: вырез маршрута делает его
     * «недостижимым» в `LinkProperties`, netd получает пустой список DoT-серверов
     * и в строгом режиме отказывает **на каждом имени** (G147). Ровно это и
     * наблюдалось: `PrivateDnsBroken` у сети VPN и `ERR_NAME_NOT_RESOLVED` в
     * браузере при живом туннеле и работающих пробах по literal-адресам.
     *
     * Сделать с этим приложение ничего не может — настройка системная. Значит
     * единственный честный ответ: сказать словами до подключения (I4).
     */
    fun strictPrivateDnsHost(context: Context): String {
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
            ?: return ""

        // Смотрим все сети, а не только активную.
        //
        // Активной бывает и сам VPN (для приложений внутри туннеля), и Wi-Fi (для
        // Nova, которая из своего же туннеля исключена), а признак строгого
        // режима система выставляет на каждой сети отдельно. Проверка одной
        // «активной» поэтому давала разный ответ в двух процессах одного и того
        // же приложения — служба предупреждение печатала, а экран подсказку не
        // показывал.
        val networks = buildList {
            manager.activeNetwork?.let(::add)
            @Suppress("DEPRECATION")
            runCatching { manager.allNetworks.toList() }.getOrDefault(emptyList()).forEach(::add)
        }
        for (network in networks.distinct()) {
            val properties = runCatching { manager.getLinkProperties(network) }.getOrNull() ?: continue
            val active = AndroidCompat.isPrivateDnsActive(properties)
            val name = AndroidCompat.getPrivateDnsServerName(properties).trim()
            // Одна строка на изменение ответа: без неё «подсказки нет» и «строгого
            // режима нет» неразличимы, а это два разных состояния (I4).
            val snapshot = "$active|$name"
            if (snapshot != lastPrivateDnsSnapshot) {
                lastPrivateDnsSnapshot = snapshot
                LogManager.log("TOR: частный DNS сети — активен=$active, имя=${name.ifBlank { "-" }}.")
            }
            if (!active) continue
            val host = name
            // Имя сервера пустое — это режим «автоматически»: там система сама
            // откатывается на обычный DNS, если DoT не отвечает, и Tor он не
            // мешает. Строгим считается только режим с именем.
            if (host.isNotBlank()) return host
        }
        return ""
    }

    /** Одна строка предупреждения для экрана; пусто, если предупреждать не о чем. */
    fun privateDnsWarning(context: Context): String {
        val host = strictPrivateDnsHost(context)
        if (host.isBlank()) return ""
        return "TOR: включён системный «Частный DNS» ($host) — через Tor имена не откроются. " +
            "Нажмите «Отключите DoT» рядом с выбором входа."
    }

    /**
     * То же для журнала, но **без имени сервера**.
     *
     * Журнал уходит в отчёты об отказах, а выбранный резолвер — это признак
     * человека не хуже адреса: на экране он нужен, в присланном файле — нет.
     */
    fun privateDnsWarningForLog(context: Context): String {
        if (strictPrivateDnsHost(context).isBlank()) return ""
        return "TOR: в системе включён строгий «Частный DNS» — через Tor имена резолвиться не будут."
    }

    /** Последняя строка о ходе загрузки — для экрана и журнала. */
    fun bootstrapSummary(): String = lastBootstrapLine

    /**
     * Поднимает связку obfs4 + tor и ждёт готовности.
     *
     * @return порт SOCKS5 tor'а или 0, если поднять не удалось.
     * @param isCancelled зовётся между шагами; `true` прекращает ожидание. Без
     *   него остановка пользователем ждала бы полного тайм-аута загрузки.
     */
    fun start(
        context: Context,
        bridges: List<TorBridge>,
        entryMode: String,
        isCancelled: () -> Boolean,
    ): Int {
        // Прошлый сеанс разбирается **до** взятия монитора и безусловно.
        //
        // Иначе смена способа входа на живом туннеле давала худший из возможных
        // исходов: `startTorPtProxy` при живом слушателе возвращал прежний
        // адрес, новый torrc никто не перечитывал, а `awaitBootstrap` получал от
        // **старого** tor'а сразу «100 %» — в журнале «вход webtunnel», а трафик
        // продолжал идти через прежние мосты.
        if (boundService != null || socksPort != 0) {
            LogManager.log("TOR: разбираем прежний сеанс перед новым.")
            stop(context)
        }
        stopRequested = false
        return startLocked(context, bridges, entryMode, isCancelled)
    }

    private fun startLocked(
        context: Context,
        bridges: List<TorBridge>,
        entryMode: String,
        isCancelled: () -> Boolean,
    ): Int = synchronized(lock) {

        val mode = ConnectionSelectorPolicy.normalizeTorEntry(entryMode)
        val wantedTransport = ConnectionSelectorPolicy.torBridgeTransportFor(mode)

        val usable = if (wantedTransport.isEmpty()) {
            emptyList()
        } else {
            bridges.filter { bridge ->
                bridge.transport == wantedTransport &&
                    bridge.line.none { c -> c.isISOControl() } &&
                    // У webtunnel адрес в строке — заглушка из RFC 3849, ходить
                    // надо по `url=`, и tor это делает сам. Требовать разбираемый
                    // адрес здесь значило бы выбросить все такие мосты.
                    (wantedTransport == "webtunnel" || bridge.dialTarget() != null)
            }
        }
        if (wantedTransport.isNotEmpty() &&
            usable.size != bridges.count { it.transport == wantedTransport }
        ) {
            // Молча выбрасывать записи нельзя (I4): счёт мостов на экране и в
            // журнале должен сходиться с тем, что реально уехало в torrc.
            LogManager.log(
                "TOR: часть мостов $wantedTransport отброшена — адрес не разобран или в строке управляющие символы."
            )
        }
        if (wantedTransport.isNotEmpty() && usable.isEmpty()) {
            LogManager.log("TOR: мостов $wantedTransport нет, подниматься не с чем.")
            return 0
        }

        // Прокси в ядре нужен только транспортным режимам. У vanilla-моста и
        // прямого входа tor соединяется сам: его сокет и так вне нашего VPN,
        // потому что пакет Nova исключён из собственного туннеля во всех режимах
        // раздельного туннелирования.
        val ptAddress = if (ConnectionSelectorPolicy.torEntryUsesPluggableTransport(mode)) {
            try {
                // Порт выбирает ядро: занятый фиксированный порт — это отказ там,
                // где отказывать не за что.
                nova.Nova.startTorPtProxy(
                    mode,
                    "127.0.0.1:0",
                    context.applicationContext.filesDir.absolutePath,
                    allowedTargetsFor(usable),
                )
            } catch (error: Throwable) {
                LogManager.log("TOR: транспорт $mode в ядре не поднялся: ${error.message}")
                return 0
            }
        } else {
            ""
        }
        obfs4Address = ptAddress
        LogManager.log(
            when {
                ConnectionSelectorPolicy.torEntryUsesPluggableTransport(mode) ->
                    "TOR: вход $mode, прокси ядра слушает $ptAddress, мостов ${usable.size}."
                mode == ConnectionSelectorPolicy.TOR_ENTRY_VANILLA ->
                    "TOR: вход обычными мостами (vanilla), их ${usable.size}."
                else ->
                    "TOR: вход прямой, без мостов."
            }
        )

        val torrc = try {
            writeTorrc(context, ptAddress, usable, mode)
        } catch (error: Throwable) {
            LogManager.log("TOR: не записан torrc: ${error.message}")
            stop(context)
            return 0
        }
        LogManager.log("TOR: torrc записан (${torrc.length()} Б), запускаем tor.")

        val cancelled = { stopRequested || isCancelled() }

        val service = bindTorService(context, cancelled)
        if (service == null) {
            LogManager.log("TOR: служба tor не поднялась.")
            stop(context)
            return 0
        }

        val port = awaitBootstrap(service, cancelled)
        if (port <= 0) {
            LogManager.log("TOR: загрузка не завершилась. ${lastBootstrapLine.ifBlank { "без сообщений" }}")
            stop(context)
            return 0
        }

        socksPort = port
        LogManager.log("TOR: загрузка завершена, SOCKS5 на 127.0.0.1:$port. ${nova.Nova.torPtProxyStats()}")
        return port
    }

    /**
     * Останавливает tor и obfs4.
     *
     * Порядок обратный запуску и он важен: пока tor жив, он держит соединения
     * через obfs4, и закрытие слушателя первым оставило бы их висеть.
     */
    fun stop(context: Context) {
        // Флаг ставится **до** монитора: подъём может держать его минутами, а
        // остановка обязана доходить сразу.
        stopRequested = true
        stopLocked(context)
    }

    private fun stopLocked(context: Context) = synchronized(lock) {
        val appContext = context.applicationContext

        connection?.let { active ->
            runCatching { appContext.unbindService(active) }
                .onFailure { LogManager.log("TOR: отвязка службы: ${it.message}") }
        }
        connection = null
        boundService = null

        runCatching { appContext.stopService(Intent(appContext, TorService::class.java)) }
            .onFailure { LogManager.log("TOR: остановка службы: ${it.message}") }

        runCatching { nova.Nova.stopTorPtProxy() }
            .onFailure { LogManager.log("TOR: остановка транспорта: ${it.message}") }

        socksPort = 0
        obfs4Address = ""
    }

    /**
     * Куда прокси этой сессии разрешено звонить: адреса мостов и хосты из `url=`.
     *
     * Список нужен потому, что у локального SOCKS нет проверки прав вовсе: без
     * него любое приложение на телефоне получило бы через него дозвон наружу
     * мимо VPN — сокет-то помечается `protect()`.
     */
    private fun allowedTargetsFor(bridges: List<TorBridge>): String {
        val targets = linkedSetOf<String>()
        bridges.forEach { bridge ->
            bridge.dialTarget()?.let { (host, port) -> targets += "$host:$port" }
            targets += bridge.endpoint.trim().lowercase()
            // У webtunnel настоящая цель — хост из `url=`, а в CONNECT приезжает
            // заглушка; tor подставит туда именно его.
            bridge.url.takeIf { it.isNotBlank() }?.let { url ->
                runCatching { java.net.URI(url).host }.getOrNull()?.let { host ->
                    targets += host.lowercase()
                }
            }
        }
        return targets.filter { it.isNotBlank() }.joinToString(",")
    }

    /**
     * Пишет torrc.
     *
     * Порт SOCKS здесь намеренно **не** задаётся: `TorService` сам кладёт в
     * `torrc-defaults` либо 9050, либо `auto`, если 9050 занят, а фиксированный
     * порт в нашем файле сделал бы занятость порта отказом всего транспорта.
     * Настоящий порт спрашивается у tor после загрузки.
     */
    private fun writeTorrc(
        context: Context,
        ptAddress: String,
        bridges: List<TorBridge>,
        entryMode: String,
    ): File {
        val file = TorService.getTorrc(context.applicationContext)
        val text = buildString {
            appendLine("# Записано Nova. Правки переживут ровно до следующего подключения.")
            appendLine("ClientOnly 1")
            // Прокси слушает петлю, но запрет лишним не бывает: он остаётся в
            // силе, даже если порт однажды окажется на другом интерфейсе.
            appendLine("SocksPolicy accept 127.0.0.0/8")
            appendLine("SocksPolicy reject *")
            // Диск на телефоне медленный, а состояние tor нам между сеансами не
            // нужно: мосты приходят из своего хранилища.
            appendLine("AvoidDiskWrites 1")
            appendLine("DormantCanceledByStartup 1")
            if (entryMode == ConnectionSelectorPolicy.TOR_ENTRY_DIRECT) {
                // Прямой вход: ни мостов, ни транспорта. Строка UseBridges нужна
                // явная — файл переписывается на каждое подключение, но tor читает
                // ещё и torrc-defaults, и умолчание лучше назвать вслух.
                appendLine("UseBridges 0")
                return@buildString
            }
            if (ConnectionSelectorPolicy.torEntryUsesPluggableTransport(entryMode)) {
                // Внешний pluggable transport: tor ходит к мостам через наш SOCKS5,
                // а наружу из него — уже сокетами ядра, помеченными protect().
                appendLine("ClientTransportPlugin $entryMode socks5 $ptAddress")
            }
            appendLine("UseBridges 1")
            // Вторая проверка на управляющие символы, хотя первая стоит в
            // `TorBridge.parse`. Она здесь не «на всякий случай»: файл мостов
            // лежит на диске и переживает обновление приложения, то есть запись
            // могла попасть туда через прежнюю версию разбора. Строка с
            // переводом внутри — это дописанная директива torrc, и цена ошибки
            // тут выше цены одного `filter`.
            bridges
                .filter { bridge -> bridge.line.none { it.isISOControl() } }
                .forEach { bridge -> appendLine("Bridge ${bridge.line}") }
        }
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun bindTorService(context: Context, isCancelled: () -> Boolean): TorService? {
        val appContext = context.applicationContext
        val intent = Intent(appContext, TorService::class.java)

        // Прежняя привязка снимается: затирание поля оставляло её висеть, и
        // остановить такой tor было уже нечем — `unbindService` снимает только
        // последнюю.
        connection?.let { previous ->
            runCatching { appContext.unbindService(previous) }
            connection = null
        }

        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as? TorService.LocalBinder)?.service
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                boundService = null
            }
        }
        connection = serviceConnection

        val bound = runCatching {
            appContext.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }.getOrElse { error ->
            LogManager.log("TOR: bindService не удался: ${error.message}")
            false
        }
        if (!bound) {
            // Отвязывать надо даже после `false`: система всё равно записала
            // заявку, и без этого она протекает.
            runCatching { appContext.unbindService(serviceConnection) }
            connection = null
            return null
        }

        // Привязка асинхронна, а дальше нам нужен сам объект службы: у него
        // спрашивают ход загрузки. Ждём недолго — служба поднимается в этом же
        // процессе.
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            boundService?.let { return it }
            if (isCancelled()) return null
            Thread.sleep(200L)
        }
        return null
    }

    /**
     * Ждёт, пока tor доложит `status/bootstrap-phase` со стопроцентной готовностью.
     *
     * Спрашиваем именно tor, а не полагаемся на широковещание службы: `STATUS_ON`
     * она шлёт по первому построенному каналу, а нам нужен ещё и порт SOCKS,
     * который к тому моменту может быть не прочитан. Заодно ход загрузки попадает
     * в журнал — по нему видно, где именно всё встало: на рукопожатии с мостом,
     * на консенсусе или на построении цепочки.
     */
    private fun awaitBootstrap(service: TorService, isCancelled: () -> Boolean): Int {
        val deadline = SystemClock.elapsedRealtime() + BOOTSTRAP_TIMEOUT_MS
        var lastLogged = ""
        var silentPolls = 0

        while (SystemClock.elapsedRealtime() < deadline) {
            if (isCancelled()) return 0
            if (boundService == null) {
                // Служба отвалилась — ждать полтора тайм-аута незачем, и молчать
                // об этом тоже нельзя (I4).
                LogManager.log("TOR: служба tor отвалилась во время загрузки.")
                return 0
            }

            val phase = runCatching { service.getInfo("status/bootstrap-phase") }.getOrNull().orEmpty()
            if (phase.isBlank()) {
                silentPolls++
                if (silentPolls >= 10) {
                    LogManager.log("TOR: управляющее соединение молчит десять опросов подряд — сдаёмся.")
                    return 0
                }
            } else {
                silentPolls = 0
            }
            if (phase.isNotBlank() && phase != lastLogged) {
                lastLogged = phase
                lastBootstrapLine = phase.trim()
                LogManager.log("TOR: ${summaryOf(phase)}")
            }

            if (phase.contains("PROGRESS=100") || phase.contains("TAG=done")) {
                // Порт спрашивается у самого tor, а поле службы — только запасной
                // путь: оно статическое и переживает прошлый сеанс, то есть на
                // втором подключении могло бы вернуть порт уже мёртвого tor'а.
                val reported = runCatching { service.getInfo("net/listeners/socks") }.getOrNull().orEmpty()
                val port = Regex(":(\\d+)").findAll(reported).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
                    ?: service.socksPort
                if (port > 0) return port
            }

            Thread.sleep(POLL_INTERVAL_MS)
        }
        return 0
    }

    /**
     * Достаёт из строки состояния человеческую часть.
     *
     * Строка tor выглядит как `NOTICE BOOTSTRAP PROGRESS=25 TAG=enough_dirinfo
     * SUMMARY="Loading networkstatus consensus"`. В журнале нужен процент и
     * сводка, остальное — шум, которого и так хватает.
     */
    private fun summaryOf(phase: String): String {
        val progress = Regex("PROGRESS=(\\d+)").find(phase)?.groupValues?.get(1).orEmpty()
        val summary = Regex("SUMMARY=\"([^\"]*)\"").find(phase)?.groupValues?.get(1).orEmpty()
        return when {
            progress.isNotEmpty() && summary.isNotEmpty() -> "загрузка $progress% — $summary"
            summary.isNotEmpty() -> summary
            else -> phase.trim()
        }
    }
}

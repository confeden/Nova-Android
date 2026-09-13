package com.example.nova

import android.content.Context
import android.util.AtomicFile
import nova.Nova
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Выпуск собственных профилей WARP: своя личность плюс найденные сканером точки входа.
 *
 * Зачем, если в прошивке уже лежит пятьдесят семян. У тех семян **чужие приватные
 * ключи, одинаковые на всех установках**: пятнадцать личностей на пятьдесят
 * профилей, все открытым текстом в APK. Это открытый P6. Здесь личность
 * регистрирует само устройство, а точки входа ищет сканер — на той сети, где
 * человек сейчас находится, а не на той, где собирали прошивку.
 *
 * Встроенные семена при этом остаются (I6): они запасной путь на случай, когда
 * регистрация не проходит вовсе.
 *
 * ## Почему это живёт в процессе `:vpn`
 *
 * Сканер открывает сотни UDP-сокетов и обязан помечать их `protect()`, иначе они
 * уйдут в туннель, который в этот момент как раз и проверяется. `GlobalProtector`
 * ставит служба, и в процессе интерфейса ядро — другой экземпляр без протектора.
 * Ровно та же причина, по которой в `:vpn` живёт замер Proton.
 *
 * ## Что меняется от профиля к профилю
 *
 * Только джанк — `Jc`/`Jmin`/`Jmax` (см. [WarpGeneratedStore.randomJunk]). `S1`/`S2`
 * и `H1`-`H4` менять нельзя: узел WARP говорит обычным WireGuard и не разберёт ни
 * мусор внутри рукопожатия, ни переименованные типы пакетов.
 */
object WarpProfileGenerator {

    /** Сколько профилей выпускаем. Столько же, сколько встроенных семян. */
    const val TARGET_COUNT = 50

    /** Потолок одного прохода сканера: дольше ждать бессмысленно, точки входа anycast'овые. */
    private const val SCAN_TIMEOUT_MS = 45_000

    /**
     * Сколько **проверенных** точек считаем достаточным, чтобы не идти вторым проходом.
     *
     * Проверенная — та, до которой сканер довёл настоящее рукопожатие и намерил
     * время ответа. Остальные к сети не прикасались вовсе (см. [scanEndpoints]).
     */
    private const val ENOUGH_VERIFIED = 24

    /**
     * Не больше стольких точек из одной подсети в голове очереди.
     *
     * Блокируют не адрес, а диапазон. Пятьдесят точек из одной /24 — это один
     * рубильник на весь набор, и выключается он для всех сразу.
     */
    private const val MAX_PER_SUBNET = 3

    const val STATE_IDLE = "idle"
    const val STATE_RUNNING = "running"
    const val STATE_DONE = "done"
    const val STATE_FAILED = "failed"

    private val running = AtomicBoolean(false)

    /**
     * @param atMs когда состояние записали. Поле писалось с самого начала, но в
     *        [Progress] не попадало — а без него `done`/`failed` не отличить от
     *        сегодняшнего: состояние в файле терминальное и не меняется никогда,
     *        и экран показывал бы «выпущено 50 профилей» от прогона трёхнедельной
     *        давности как свежую новость.
     */
    data class Progress(
        val state: String,
        val message: String,
        val done: Int,
        val total: Int,
        val atMs: Long = 0L,
    )

    fun isRunning(): Boolean = running.get()

    /**
     * Полный прогон. Зовётся из процесса `:vpn`.
     *
     * @param force заново регистрировать личность, даже если она уже есть.
     * @return true, если прогон начался. false — он уже идёт, и второй заводить
     *         нельзя: два сканера писали бы один файл наперегонки.
     */
    fun run(
        context: Context,
        force: Boolean,
        register: (onProgress: (Int) -> Unit) -> WarpConfig?,
    ): Boolean {
        if (!running.compareAndSet(false, true)) {
            LogManager.log("WARP-генератор: прогон уже идёт — второй не заводим.")
            return false
        }
        val appContext = context.applicationContext
        val store = WarpGeneratedStore(appContext)
        try {
            publish(appContext, STATE_RUNNING, "WARP: готовлю личность", 0, TARGET_COUNT)

            val existing = store.read()
            val identity = if (!force && existing.identity != null) {
                LogManager.log("WARP-генератор: личность уже есть, регистрацию пропускаем.")
                existing.identity
            } else {
                publish(appContext, STATE_RUNNING, "WARP: регистрирую устройство", 0, TARGET_COUNT)
                val config = register { }
                if (config == null) {
                    // Молчаливый отказ здесь неотличим от «сеть медленная» (I4), а
                    // лечение разное: одно — подождать, другое — включить релей.
                    LogManager.log(
                        "WARP-генератор: регистрация не прошла — своих профилей не будет, " +
                            "остаются встроенные семена."
                    )
                    publish(appContext, STATE_FAILED, "WARP: регистрация не прошла", 0, TARGET_COUNT)
                    return true
                }
                WarpGeneratedStore.Identity(
                    privateKey = config.privateKey,
                    publicKey = config.publicKey,
                    ipv4 = config.ipv4,
                    ipv6 = config.ipv6,
                    peerPublicKey = config.peerPublicKey,
                    reserved = config.reserved.orEmpty(),
                    createdAt = System.currentTimeMillis(),
                ).also {
                    store.writeIdentity(it)
                    LogManager.log("WARP-генератор: личность зарегистрирована, адрес ${it.ipv4}.")
                }
            }

            publish(appContext, STATE_RUNNING, "WARP: ищу точки входа", 0, TARGET_COUNT)
            val scanned = diversify(collectVerified(identity).sortedBy { it.rttMs })
            if (scanned.isEmpty()) {
                LogManager.log(
                    "WARP-генератор: сканер не нашёл ни одной точки входа за ${SCAN_TIMEOUT_MS / 1000} с. " +
                        "Личность сохранена — следующий прогон начнёт со сканирования."
                )
                publish(appContext, STATE_FAILED, "WARP: точки входа не найдены", 0, TARGET_COUNT)
                return true
            }

            val random = Random(System.nanoTime())
            val now = System.currentTimeMillis()
            // Прикрытие своё у каждого профиля, а не одно на весь набор.
            //
            // Раньше `I1` брался у первого попавшегося встроенного семени и
            // подставлялся во все профили разом: один DCID, один ClientHello, одно
            // имя в SNI на пятьдесят записей. Это ровно та же беда, что у
            // одинакового джанка (N5) — «сменить профиль, чтобы сменить форму» не
            // меняло первый пакет потока.
            //
            // Строитель у нас свой ([ProtonQuicInitial], RFC 9001) и уже возит
            // профили Proton, поэтому новой поверхности отказа здесь не появляется.
            // Порядок имён тот же, что у маскировки SNI: сверху проверенные, дальше
            // большой российский список.
            val sniPool = runCatching {
                ClientData(appContext).getSniMaskPools().let { it.white + it.russia }
            }.getOrDefault(emptyList())
                .filter { it.isNotBlank() }
                .distinct()
                .shuffled(random)
            val profiles = scanned.take(TARGET_COUNT).mapIndexed { index, endpoint ->
                val (count, min, max) = WarpGeneratedStore.randomJunk(random)
                val sni = if (sniPool.isEmpty()) "" else sniPool[index % sniPool.size]
                WarpGeneratedProfile(
                    host = endpoint.host,
                    port = endpoint.port,
                    rttMs = endpoint.rttMs,
                    junkCount = count,
                    junkMin = min,
                    junkMax = max,
                    createdAt = now,
                    maskPacket = if (sni.isBlank()) "" else ProtonQuicInitial.buildI1(sni),
                )
            }
            // Молчать нельзя (I4): пустое прикрытие у всех означает, что списки SNI
            // не прочитались, и профили уедут с общим пакетом из семени — снаружи
            // это неотличимо от «всё хорошо».
            val withOwnI1 = profiles.count { it.maskPacket.isNotBlank() }
            LogManager.log(
                "WARP-генератор: своё прикрытие I1 собрано для $withOwnI1 из ${profiles.size} профилей " +
                    "(имён в пуле ${sniPool.size})."
            )
            store.writeProfiles(profiles)
            val best = profiles.minByOrNull { if (it.rttMs > 0) it.rttMs else Int.MAX_VALUE }
            LogManager.log(
                "WARP-генератор: выпущено ${profiles.size} профилей, лучший " +
                    "${best?.rttMs ?: 0} мс (${best?.host}:${best?.port})."
            )
            publish(
                appContext,
                STATE_DONE,
                "WARP: ${profiles.size} профилей, лучший ${best?.rttMs ?: 0} мс",
                profiles.size,
                TARGET_COUNT,
            )
            return true
        } catch (e: Exception) {
            LogManager.log("WARP-генератор: прогон оборвался — ${e.message}")
            publish(appContext, STATE_FAILED, "WARP: прогон оборвался", 0, TARGET_COUNT)
            return true
        } finally {
            running.set(false)
        }
    }

    /**
     * Сканирование точек входа ядром.
     *
     * Ядро уже умеет это лучше любого перебора TCP-соединений: `ScanWarpEndpoints`
     * делает **настоящее рукопожатие WireGuard** нашим ключом и меряет ответ, то
     * есть отвечает на вопрос «этот адрес поднимет туннель», а не «на этом адресе
     * что-то слушает». Порт и адрес приходят строкой `addr:port|rtt_ms`.
     *
     * @return пары ((хост, порт), задержка) в порядке, который вернуло ядро.
     */
    /** Точка входа: адрес, порт и намеренное время ответа (0 — не мерили). */
    private data class Endpoint(val host: String, val port: Int, val rttMs: Int)

    /**
     * Подсеть, по которой считается разнообразие.
     *
     * Для IPv4 — /24, для IPv6 — первые три группы: блокируют диапазонами, а не
     * адресами, и две точки из одной /24 переживают блокировку вместе.
     */
    private fun Endpoint.subnet(): String =
        if (host.contains(':')) {
            host.split(':').take(3).joinToString(":")
        } else {
            host.split('.').take(3).joinToString(".")
        }

    /**
     * Переставляет так, чтобы в голове очереди подсети не повторялись.
     *
     * Именно переставляет, а не выбрасывает: очередь пробует по порядку, и всё,
     * что не попало в голову, остаётся в хвосте и своей очереди дождётся.
     * Выбрасывать было бы хуже — набор и так невелик.
     */
    private fun diversify(sorted: List<Endpoint>): List<Endpoint> {
        val perSubnet = HashMap<String, Int>()
        val head = ArrayList<Endpoint>(sorted.size)
        val tail = ArrayList<Endpoint>()
        for (endpoint in sorted) {
            val key = endpoint.subnet()
            val seen = perSubnet.getOrDefault(key, 0)
            if (seen < MAX_PER_SUBNET) {
                perSubnet[key] = seen + 1
                head += endpoint
            } else {
                tail += endpoint
            }
        }
        return head + tail
    }

    private fun scanEndpoints(identity: WarpGeneratedStore.Identity): List<Endpoint> {
        val raw = runCatching {
            Nova.scanWarpEndpoints(
                identity.privateKey,
                identity.peerPublicKey,
                true,
                true,
                SCAN_TIMEOUT_MS.toLong(),
                TARGET_COUNT.toLong(),
            )
        }.getOrElse { error ->
            LogManager.log("WARP-генератор: сканер не запустился — ${error.message}")
            ""
        }
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val addressPart = trimmed.substringBefore('|').trim()
            val rtt = trimmed.substringAfter('|', "").trim().toIntOrNull() ?: 0
            // IPv6 приходит в скобках: `[2606:4700:d0::1]:2408`. Резать по
            // последнему двоеточию — единственный разбор, который не ломается ни
            // на одной из двух форм.
            val host = addressPart.substringBeforeLast(':').trim().trim('[', ']')
            val port = addressPart.substringAfterLast(':').trim().toIntOrNull() ?: return@mapNotNull null
            if (host.isEmpty() || port !in 1..65535) return@mapNotNull null
            Endpoint(host, port, rtt)
        }.toList()
    }

    /**
     * Проверенные точки входа: сканер довёл до них рукопожатие и намерил время.
     *
     * Отделять обязательно. `ScanWarpEndpoints` в ядре **добивает** ответ до
     * запрошенного числа случайными адресами из тех же префиксов, помечая их
     * временем `-1`, — то есть до этих адресов никто не дотрагивался. Раньше они
     * шли в профили наравне с найденными, а личные профили стоят в очереди
     * **впереди** пятидесяти встроенных семян (D22). На сети, где сканер нашёл
     * шесть точек, это значило сорок четыре случайных адреса перед первым живым
     * семенем — на каждом подключении.
     *
     * Второй проход — только если проверенных мало: он стоит ещё [SCAN_TIMEOUT_MS],
     * и платить эту цену там, где и одного прохода хватило, незачем.
     */
    private fun collectVerified(identity: WarpGeneratedStore.Identity): List<Endpoint> {
        val first = scanEndpoints(identity)
        val verified = first.filter { it.rttMs > 0 }
        val padded = first.size - verified.size
        if (padded > 0) {
            LogManager.log(
                "WARP-генератор: сканер нашёл ${verified.size} точек и добил ответ $padded " +
                    "случайными — эти в профили не идут."
            )
        }
        if (verified.size >= ENOUGH_VERIFIED) return verified
        LogManager.log(
            "WARP-генератор: проверенных точек ${verified.size}, это меньше $ENOUGH_VERIFIED — " +
                "идём вторым проходом."
        )
        val second = scanEndpoints(identity).filter { it.rttMs > 0 }
        // Объединение, а не пересечение: сканер обходит префиксы случайно, и два
        // прохода находят во многом разные адреса — пересечение было бы почти
        // пустым. У повторившихся берём лучшее время.
        return (verified + second)
            .groupBy { it.host to it.port }
            .map { (_, same) -> same.minByOrNull { it.rttMs } ?: same.first() }
    }

    // -- состояние для экрана -------------------------------------------------

    private const val STATE_FILE = "warp_generated_state.json"
    private val writeLock = Any()

    /**
     * Состояние прогона — файлом.
     *
     * Прогон идёт в `:vpn`, а показывает его экран, и `SharedPreferences` между
     * процессами не работают (I2).
     */
    fun readProgress(context: Context?): Progress {
        val file = stateFile(context) ?: return Progress(STATE_IDLE, "", 0, 0)
        val raw = runCatching { file.baseFile.readText(Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching {
                File(file.baseFile.path + ".bak").takeIf { it.exists() }?.readText(Charsets.UTF_8)
            }.getOrNull()
            ?: return Progress(STATE_IDLE, "", 0, 0)
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return Progress(STATE_IDLE, "", 0, 0)
        return Progress(
            state = json.optString("state", STATE_IDLE),
            message = json.optString("message"),
            done = json.optInt("done"),
            total = json.optInt("total"),
            atMs = json.optLong("at", 0L),
        )
    }

    private fun publish(context: Context, state: String, message: String, done: Int, total: Int) {
        val file = stateFile(context) ?: return
        val payload = JSONObject()
            .put("state", state)
            .put("message", message)
            .put("done", done)
            .put("total", total)
            .put("at", System.currentTimeMillis())
        synchronized(writeLock) {
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: Exception) {
                if (stream != null) runCatching { file.failWrite(stream) }
            }
        }
    }

    private fun stateFile(context: Context?): AtomicFile? {
        val dir = context?.applicationContext?.filesDir ?: return null
        return AtomicFile(File(dir, STATE_FILE))
    }
}

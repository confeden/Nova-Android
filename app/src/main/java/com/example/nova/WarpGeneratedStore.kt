package com.example.nova

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.random.Random

/**
 * Один сгенерированный профиль WARP: наша личность на чужой точке входа.
 *
 * Личность здесь не хранится — она одна на все профили и лежит в [WarpGeneratedStore.Identity].
 * Дублировать приватный ключ пятьдесят раз значило бы пятьдесят раз потерять его
 * при частичной записи, ровно как у Proton.
 */
data class WarpGeneratedProfile(
    val host: String,
    val port: Int,
    /** Задержка рукопожатия в миллисекундах; 0 — не измерено. */
    val rttMs: Int,
    val junkCount: Int,
    val junkMin: Int,
    val junkMax: Int,
    val createdAt: Long,
    /**
     * Сколько раз подряд эта точка входа подвела с момента последнего успеха.
     *
     * Отметка живёт **здесь**, а не в общем списке проверенных конфигураций.
     * Там идентификатор записи — `mode|host|port`, а личные профили несут тот же
     * режим `warp-awg-exact`, что и все пятьдесят прошивочных семян: общий адрес
     * означал бы общую запись, притом что ключи у них разные и удача одного
     * ничего не говорит об удаче другого.
     *
     * Нужна потому, что приоритет без понижения необратим: набор, выпущенный
     * дома и отфильтрованный на мобильной сети, иначе оставался бы во главе
     * очереди вечно — полсотни мёртвых адресов перед первым рабочим семенем, на
     * каждом подключении.
     */
    val failures: Int = 0,
) {
    val id: String get() = "warpgen|$host|$port"
}

/**
 * Хранилище собственных WARP-профилей.
 *
 * Зачем оно вообще. Пятьдесят встроенных семян несут **чужие приватные ключи**,
 * одинаковые у всех установок: пятнадцать личностей на пятьдесят профилей, и все
 * они лежат открытым текстом в APK. Это и есть открытый P6. Сгенерированный набор
 * снимает обе беды сразу — личность своя, зарегистрированная этим устройством, а
 * точки входа найдены сканером на этой самой сети.
 *
 * Файл, а не `SharedPreferences` (I2): пишет процесс интерфейса, читает `:vpn`.
 */
class WarpGeneratedStore(context: Context) {

    private val appContext = context.applicationContext
    private val file = AtomicFile(File(appContext.filesDir, "warp_generated.json"))

    /**
     * Личность, выданная Cloudflare этому устройству.
     *
     * @param reserved три байта из `client_id`, которые WARP ждёт в заголовке.
     *        Хранится как есть; пусто — сервер их не выдал.
     */
    data class Identity(
        val privateKey: String,
        val publicKey: String,
        val ipv4: String,
        val ipv6: String,
        val peerPublicKey: String,
        val reserved: String,
        val createdAt: Long,
    )

    /** Где вышел трафик, когда это последний раз проверяли. */
    data class Exit(val country: String, val colo: String, val checkedAt: Long)

    /**
     * Два переключателя — **файлом, а не `SharedPreferences`** (I2).
     *
     * Оба ставит экран, а читает процесс `:vpn`, и у каждого процесса свой кэш
     * настроек: значение, записанное интерфейсом, живущая служба не увидит до
     * своего перезапуска. На этом в проекте спотыкались трижды (G2, G16, G70), и
     * здесь споткнулись снова: «обход московского узла» включался, экран честно
     * писал об этом в журнал, а служба продолжала считать настройку выключенной.
     */
    data class Options(val useGenerated: Boolean, val avoidColo: Boolean)

    data class Snapshot(
        val identity: Identity?,
        val profiles: List<WarpGeneratedProfile>,
        val exit: Exit?,
        val options: Options = Options(useGenerated = false, avoidColo = false),
    )

    fun read(): Snapshot {
        val raw = readRaw() ?: return Snapshot(null, emptyList(), null)
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return Snapshot(null, emptyList(), null)
        val identityJson = json.optJSONObject("identity")
        val identity = identityJson?.let {
            val privateKey = it.optString("private_key")
            val peer = it.optString("peer_public_key")
            if (privateKey.isBlank() || peer.isBlank()) return@let null
            Identity(
                privateKey = privateKey,
                publicKey = it.optString("public_key"),
                ipv4 = it.optString("ipv4"),
                ipv6 = it.optString("ipv6"),
                peerPublicKey = peer,
                reserved = it.optString("reserved"),
                createdAt = it.optLong("created_at", 0L),
            )
        }
        val profiles = parseProfiles(json.optJSONArray("profiles"))
        val exitJson = json.optJSONObject("exit")
        val exit = exitJson?.let {
            Exit(it.optString("country"), it.optString("colo"), it.optLong("checked_at", 0L))
        }
        val optionsJson = json.optJSONObject("options")
        val options = Options(
            useGenerated = optionsJson?.optBoolean("use_generated", false) ?: false,
            avoidColo = optionsJson?.optBoolean("avoid_colo", false) ?: false,
        )
        return Snapshot(identity, profiles, exit, options)
    }

    fun writeIdentity(identity: Identity) = mutate { json ->
        json.put(
            "identity",
            JSONObject()
                .put("private_key", identity.privateKey)
                .put("public_key", identity.publicKey)
                .put("ipv4", identity.ipv4)
                .put("ipv6", identity.ipv6)
                .put("peer_public_key", identity.peerPublicKey)
                .put("reserved", identity.reserved)
                .put("created_at", identity.createdAt)
        )
    }

    fun writeProfiles(profiles: List<WarpGeneratedProfile>) = mutate { json ->
        json.put("profiles", encodeProfiles(profiles))
    }

    /**
     * Отмечает исход попытки на точке входа личного профиля.
     *
     * Успех обнуляет счётчик, отказ его повышает. Читает-меняет-пишет под тем же
     * замком, что и остальные записи файла: отметку ставит процесс `:vpn`, а
     * читает и он же, и экран настроек (I2).
     *
     * Незнакомый адрес молча игнорируется — это отметка о **личном** профиле, а
     * тот же адрес мог прийти из прошивочного семени.
     */
    fun noteAttemptOutcome(host: String, port: Int, success: Boolean) {
        val normalizedHost = host.trim().removePrefix("[").removeSuffix("]")
        if (normalizedHost.isBlank() || port !in 1..65535) return
        mutate { json ->
            val current = parseProfiles(json.optJSONArray("profiles"))
            var touched = false
            val updated = current.map { profile ->
                val same = profile.port == port &&
                    profile.host.trim().removePrefix("[").removeSuffix("]")
                        .equals(normalizedHost, ignoreCase = true)
                if (!same) {
                    profile
                } else {
                    touched = true
                    profile.copy(failures = if (success) 0 else profile.failures + 1)
                }
            }
            if (touched) json.put("profiles", encodeProfiles(updated))
        }
    }

    private fun parseProfiles(array: JSONArray?): List<WarpGeneratedProfile> = buildList {
        for (i in 0 until (array?.length() ?: 0)) {
            val item = array!!.optJSONObject(i) ?: continue
            val host = item.optString("host")
            val port = item.optInt("port", -1)
            if (host.isBlank() || port !in 1..65535) continue
            add(
                WarpGeneratedProfile(
                    host = host,
                    port = port,
                    rttMs = item.optInt("rtt_ms", 0),
                    junkCount = item.optInt("jc", 4),
                    junkMin = item.optInt("jmin", 40),
                    junkMax = item.optInt("jmax", 70),
                    createdAt = item.optLong("created_at", 0L),
                    failures = item.optInt("failures", 0),
                )
            )
        }
    }

    private fun encodeProfiles(profiles: List<WarpGeneratedProfile>): JSONArray =
        JSONArray().also { array ->
            profiles.forEach { profile ->
                array.put(
                    JSONObject()
                        .put("host", profile.host)
                        .put("port", profile.port)
                        .put("rtt_ms", profile.rttMs)
                        .put("jc", profile.junkCount)
                        .put("jmin", profile.junkMin)
                        .put("jmax", profile.junkMax)
                        .put("created_at", profile.createdAt)
                        .put("failures", profile.failures)
                )
            }
        }

    fun writeExit(exit: Exit) = mutate { json ->
        json.put(
            "exit",
            JSONObject()
                .put("country", exit.country)
                .put("colo", exit.colo)
                .put("checked_at", exit.checkedAt)
        )
    }

    fun writeOptions(options: Options) = mutate { json ->
        json.put(
            "options",
            JSONObject()
                .put("use_generated", options.useGenerated)
                .put("avoid_colo", options.avoidColo)
        )
    }

    fun readOptions(): Options = read().options

    /**
     * Когда автоматический выпуск последний раз не дал профилей.
     *
     * Отдельное поле верхнего уровня, а не в `options`: это не настройка
     * пользователя, а память о неудаче, и она обязана пережить любую запись
     * настроек. Ноль — неудач не было.
     */
    fun readBackfillFailureAt(): Long =
        runCatching { JSONObject(readRaw().orEmpty()).optLong("backfill_failed_at", 0L) }.getOrDefault(0L)

    fun writeBackfillFailureAt(atMs: Long) = mutate { json ->
        json.put("backfill_failed_at", atMs)
    }

    /** Забывает всё: следующий прогон начнётся с новой регистрации. */
    fun clear() {
        synchronized(writeLock) { runCatching { file.delete() } }
    }

    // -- файл ---------------------------------------------------------------

    private fun mutate(block: (JSONObject) -> Unit) {
        // Чтение и запись под одним замком: между ними помещается чужая запись, и
        // тогда одно из двух изменений пропало бы молча.
        synchronized(writeLock) {
            val json = readRaw()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            json.put("version", VERSION)
            block(json)
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(json.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: Exception) {
                if (stream != null) runCatching { file.failWrite(stream) }
                LogManager.log("WARP-генератор: состояние не записалось — ${e.message}")
            }
        }
    }

    /**
     * Чтение мимо `readFully()` и с проверкой целостности.
     *
     * До Android 11 `openRead()` разрушителен для чужой незавершённой записи (G66),
     * а «непустой» не значит «целый»: `startWrite()` обрезает основной файл, и
     * читатель в этом окне получает обрывок. Обрывок отвергается, и берётся `.bak`
     * — в этот момент он и есть последняя целая копия.
     */
    private fun readRaw(): String? {
        val base = runCatching { file.baseFile.readText(Charsets.UTF_8) }.getOrNull()
        if (!base.isNullOrBlank() && runCatching { JSONObject(base) }.isSuccess) return base
        val backup = File(file.baseFile.path + ".bak")
        val fromBackup = runCatching {
            backup.takeIf { it.exists() }?.readText(Charsets.UTF_8)
        }.getOrNull()
        return fromBackup?.takeIf { runCatching { JSONObject(it) }.isSuccess }
    }

    companion object {

        private const val VERSION = 1

        private val writeLock = Any()

        const val ENDPOINT_SOURCE = "warp-generated"

        /**
         * Диапазоны джанка, из которых генерируются профили.
         *
         * Меняются **только** `Jc`/`Jmin`/`Jmax`, и это не осторожность, а
         * требование протокола. Узел WARP говорит обычным WireGuard: `S1`/`S2`
         * дописывают мусор в само рукопожатие, а `H1`-`H4` переименовывают типы
         * пакетов — и то и другое такой узел просто не разберёт. Джанк же — это
         * отдельные пакеты **перед** рукопожатием, которые сервер и так
         * отбрасывает, поэтому варьировать можно только их.
         *
         * Границы взяты снизу от встроенных семян (4/40/70, работают на всех
         * пятидесяти) и сверху от N25: тяжёлый джанк (`Jc` 110-125, `Jmax` ~1000)
         * прироста не дал, а ~60 КБ на каждое рукопожатие стоил. Ноль джанка тоже
         * исключён: без него сессия не встаёт вовсе (N2).
         */
        const val JUNK_COUNT_MIN = 3
        const val JUNK_COUNT_MAX = 8
        const val JUNK_SIZE_FLOOR = 30
        const val JUNK_SIZE_CEILING = 150

        /**
         * Джанк для одного профиля.
         *
         * Разный у разных профилей намеренно: одинаковая форма у всех пятидесяти —
         * это одна подпись на весь набор, и «сменить профиль, чтобы изменить форму»
         * тогда ничего не меняет (N5).
         */
        fun randomJunk(random: Random): Triple<Int, Int, Int> {
            val count = random.nextInt(JUNK_COUNT_MIN, JUNK_COUNT_MAX + 1)
            val min = random.nextInt(JUNK_SIZE_FLOOR, JUNK_SIZE_FLOOR + 41)
            val max = random.nextInt(min + 20, (min + 81).coerceAtMost(JUNK_SIZE_CEILING + 1))
            return Triple(count, min, max)
        }

        /**
         * Собирает конфигурацию AWG из личности, точки входа и джанка.
         *
         * `S1`-`S4` нули и `H1`-`H4` = 1,2,3,4 стоят literal-ом, а не параметрами:
         * это значения обычного WireGuard, и узел WARP других не понимает.
         * Параметризовать их значило бы предложить сломать туннель.
         *
         * @param maskPacket содержимое `I1` — поддельный первый пакет. Пусто —
         *        строка не добавляется вовсе: `uapi.go` убивает туннель на любом
         *        неизвестном ключе, и пустое значение считается неизвестным (N6).
         */
        fun buildRawConfig(
            identity: Identity,
            profile: WarpGeneratedProfile,
            maskPacket: String,
        ): String = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = ${identity.privateKey}")
            val addresses = listOf(identity.ipv4, identity.ipv6).filter { it.isNotBlank() }
            appendLine("Address = ${addresses.joinToString(", ")}")
            appendLine("DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001")
            appendLine("MTU = 1280")
            appendLine("S1 = 0")
            appendLine("S2 = 0")
            appendLine("S3 = 0")
            appendLine("S4 = 0")
            appendLine("Jc = ${profile.junkCount}")
            appendLine("Jmin = ${profile.junkMin}")
            appendLine("Jmax = ${profile.junkMax}")
            appendLine("H1 = 1")
            appendLine("H2 = 2")
            appendLine("H3 = 3")
            appendLine("H4 = 4")
            if (maskPacket.isNotBlank()) appendLine("I1 = $maskPacket")
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = ${identity.peerPublicKey}")
            appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
            appendLine("Endpoint = ${profile.host}:${profile.port}")
        }

        /**
         * Переводит сгенерированный набор в общий вид очереди подключения.
         *
         * Как у Proton: перевод на чтении, а не запись в `warp_verified_configs`.
         * Смешивать их в одном хранилище значило бы, что удаление сгенерированного
         * набора задевает встроенные семена, а те неизменяемы (I6).
         */
        fun toVerifiedConfigs(
            snapshot: Snapshot,
            maskPacket: String,
        ): List<WarpVerifiedConfig> {
            val identity = snapshot.identity ?: return emptyList()
            if (identity.privateKey.isBlank()) return emptyList()
            return snapshot.profiles.mapIndexed { index, profile ->
                WarpVerifiedConfig(
                    id = profile.id,
                    engine = "wireguard",
                    mode = "warp-awg-exact",
                    host = profile.host,
                    port = profile.port,
                    endpointSource = ENDPOINT_SOURCE,
                    rawConfig = buildRawConfig(identity, profile, maskPacket),
                    createdAt = profile.createdAt,
                    lastVerifiedAt = profile.createdAt,
                    seedOrder = index,
                    successCount = 1,
                    manual = false,
                    userImported = true,
                    qualityProbeCount = if (profile.rttMs > 0) 1 else 0,
                    qualityPingSuccesses = if (profile.rttMs > 0) 1 else 0,
                    qualityAvgPingMs = profile.rttMs.takeIf { it > 0 }?.toDouble() ?: 0.0,
                    qualityLastCheckedAt = profile.createdAt,
                    // Накопленные отказы едут вместе с профилем: по ним очередь и
                    // понижает точку входа, переставшую отвечать на этой сети.
                    qualityFailureCount = profile.failures,
                )
            }
        }
    }
}

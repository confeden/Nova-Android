package com.example.nova

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.random.Random

/**
 * Выдуманный профиль устройства для анти-абузного кадра Proton.
 *
 * Постоянен на установку и хранится рядом с личностью: меняющийся набор выглядел бы
 * для Proton как поток разных устройств с одного адреса, а настоящие `Build`-значения
 * были бы отпечатком, отданным наружу без нужды.
 */
data class ProtonDeviceProfile(
    val model: String,
    val androidVersion: String,
    val language: String,
    val regionCode: String,
    val timezone: String,
    val timezoneOffset: Int,
    val storageBytes: Double,
    val deviceNameHash: Long,
    val keyboards: List<String>,
)

/**
 * Один сгенерированный профиль. Приватного ключа здесь нет намеренно: он один на все
 * профили и лежит в личности, а дублировать его пятьдесят раз значило бы пятьдесят
 * раз его же и потерять при частичной записи.
 */
data class ProtonProfile(
    val serverName: String,
    val country: String,
    val city: String,
    val entryIp: String,
    val port: Int,
    val peerPublicKey: String,
    val pingMs: Int,
    val load: Int,
    val sni: String,
    val junkCount: Int,
    val junkMin: Int,
    val junkMax: Int,
    val i1: String,
    val createdAt: Long,
)

/**
 * Хранилище Proton-профилей.
 *
 * Файлы, а не `SharedPreferences` (I2): список читает процесс `:vpn`, а пишет
 * процесс UI, и кэш настроек в каждом процессе свой — запись одной стороны
 * откатывала бы состояние другой.
 */
class ProtonProfileStore(context: Context) {

    private val appContext = context.applicationContext
    private val accountFile = AtomicFile(File(appContext.filesDir, "proton_account.json"))
    private val profilesFile = AtomicFile(File(appContext.filesDir, "proton_profiles.json"))
    private val probeFile = AtomicFile(File(appContext.filesDir, "proton_probe.json"))
    private val runLeaseFile = AtomicFile(File(appContext.filesDir, "proton_run.json"))

    /**
     * Состояние замера. Живёт в файле, потому что замер идёт в процессе `:vpn`
     * (только у службы есть `protect()`), а показывает его процесс интерфейса.
     */
    data class ProbeState(val state: String, val total: Int, val done: Int, val alive: Int) {
        val finished: Boolean get() = state == STATE_DONE || state == STATE_FAILED
    }

    data class Account(
        val seed: ByteArray,
        val uid: String,
        val accessToken: String,
        val refreshToken: String,
        val certExpiresAt: Long,
        val device: ProtonDeviceProfile,
    ) {
        val wireGuardPrivateKey: String get() = ProtonCrypto.wireGuardPrivateKeyBase64(seed)
    }

    // --- личность -----------------------------------------------------------

    fun readAccount(): Account? {
        val raw = readAtomically(accountFile)
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            val seed = Base64.decode(json.optString("seed"), Base64.DEFAULT)
            if (seed.size != 32) return@runCatching null
            val device = json.optJSONObject("device") ?: return@runCatching null
            Account(
                seed = seed,
                uid = json.optString("uid"),
                accessToken = json.optString("access_token"),
                refreshToken = json.optString("refresh_token"),
                certExpiresAt = json.optLong("cert_expires_at", 0L),
                device = ProtonDeviceProfile(
                    model = device.optString("model"),
                    androidVersion = device.optString("android_version"),
                    language = device.optString("language"),
                    regionCode = device.optString("region_code"),
                    timezone = device.optString("timezone"),
                    timezoneOffset = device.optInt("timezone_offset"),
                    storageBytes = device.optDouble("storage_bytes", 6.4e10),
                    deviceNameHash = device.optLong("device_name_hash"),
                    keyboards = device.optJSONArray("keyboards")?.let { array ->
                        (0 until array.length()).map { array.optString(it) }
                    }.orEmpty(),
                ),
            )
        }.getOrNull()
    }

    /**
     * Правит `proton_account.json`, **сохраняя** поля, которых не касается.
     *
     * Файл делят между собой личность и запасной узел, а пишут их разные шаги
     * прогона. Пересобрать его с нуля значит стереть чужое поле молча: так
     * [writeAccount] и стирал запомненный `alt_host` при каждом обновлении токенов,
     * и запоминание узла переставало работать, ничем этого не показывая (I4).
     *
     * Чтение и запись идут под тем же замком, что и сама запись: между ними
     * помещается чужой `writeAccount`, и тогда одно из двух изменений пропало бы.
     */
    private fun mutateAccountJson(block: (JSONObject) -> Boolean) {
        synchronized(writeLock) {
            val json = readAtomically(accountFile)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: JSONObject()
            if (!block(json)) return
            writeAtomically(accountFile, json.toString())
        }
    }

    fun writeAccount(account: Account) = mutateAccountJson { json ->
        json.apply {
            put("seed", Base64.encodeToString(account.seed, Base64.NO_WRAP))
            put("uid", account.uid)
            put("access_token", account.accessToken)
            put("refresh_token", account.refreshToken)
            put("cert_expires_at", account.certExpiresAt)
            put("device", JSONObject().apply {
                put("model", account.device.model)
                put("android_version", account.device.androidVersion)
                put("language", account.device.language)
                put("region_code", account.device.regionCode)
                put("timezone", account.device.timezone)
                put("timezone_offset", account.device.timezoneOffset)
                put("storage_bytes", account.device.storageBytes)
                put("device_name_hash", account.device.deviceNameHash)
                put("keyboards", JSONArray(account.device.keyboards))
            })
        }
        true
    }

    /**
     * Запасной узел Proton, через который прошлый прогон реально достучался до API.
     *
     * Хранится рядом с личностью, потому что ищется он через DoH, а DoH — самое
     * хрупкое звено на чистой установке: один раз не ответил, и выпуск профилей
     * упал целиком. Сохранённый узел позволяет следующему заходу пропустить поиск.
     */
    fun readAlternativeHost(): String =
        readAtomically(accountFile)
            ?.let { runCatching { JSONObject(it).optString("alt_host") }.getOrDefault("") }
            .orEmpty()
            .trim()

    fun writeAlternativeHost(host: String) {
        val normalized = host.trim()
        if (normalized.isEmpty()) return
        mutateAccountJson { json ->
            if (json.optString("alt_host") == normalized) {
                false
            } else {
                json.put("alt_host", normalized)
                true
            }
        }
    }

    fun clearAccount() {
        runCatching { accountFile.delete() }
    }

    /**
     * Когда фоновая подготовка последний раз не удалась.
     *
     * В том же файле, а не в настройках: пишет его служба, а читать может и экран, а
     * `SharedPreferences` между процессами не работают — `commit()` одной стороны
     * откатывает кэш другой (I2, и уже четырежды пойманный G70).
     *
     * @return 0, если провалов не было.
     */
    fun readBackgroundFailureAt(): Long =
        readAtomically(accountFile)
            ?.let { runCatching { JSONObject(it).optLong("bg_failed_at", 0L) }.getOrDefault(0L) }
            ?: 0L

    fun writeBackgroundFailureAt(atMs: Long) = mutateAccountJson { json ->
        if (json.optLong("bg_failed_at", 0L) == atMs) {
            false
        } else {
            json.put("bg_failed_at", atMs)
            true
        }
    }

    // --- профили ------------------------------------------------------------

    fun readProfiles(): List<ProtonProfile> {
        val raw = readAtomically(profilesFile)
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONObject(raw).optJSONArray("items") ?: return emptyList()
            buildList {
                for (i in 0 until array.length()) {
                    val json = array.optJSONObject(i) ?: continue
                    val entryIp = json.optString("entry_ip")
                    val peer = json.optString("peer_public_key")
                    val port = json.optInt("port", -1)
                    if (entryIp.isBlank() || peer.isBlank() || port !in 1..65535) continue
                    add(
                        ProtonProfile(
                            serverName = json.optString("server_name"),
                            country = json.optString("country"),
                            city = json.optString("city"),
                            entryIp = entryIp,
                            port = port,
                            peerPublicKey = peer,
                            pingMs = json.optInt("ping_ms", -1),
                            load = json.optInt("load", 100),
                            sni = json.optString("sni"),
                            junkCount = json.optInt("jc", 4),
                            junkMin = json.optInt("jmin", 40),
                            junkMax = json.optInt("jmax", 70),
                            i1 = json.optString("i1"),
                            createdAt = json.optLong("created_at", 0L),
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun writeProfiles(profiles: List<ProtonProfile>) {
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject().apply {
                    put("server_name", profile.serverName)
                    put("country", profile.country)
                    put("city", profile.city)
                    put("entry_ip", profile.entryIp)
                    put("port", profile.port)
                    put("peer_public_key", profile.peerPublicKey)
                    put("ping_ms", profile.pingMs)
                    put("load", profile.load)
                    put("sni", profile.sni)
                    put("jc", profile.junkCount)
                    put("jmin", profile.junkMin)
                    put("jmax", profile.junkMax)
                    put("i1", profile.i1)
                    put("created_at", profile.createdAt)
                }
            )
        }
        writeAtomically(profilesFile, JSONObject().put("items", array).toString())
    }

    fun clearProfiles() {
        runCatching { profilesFile.delete() }
    }

    // --- состояние замера ---------------------------------------------------

    fun readProbeState(): ProbeState? {
        val raw = readAtomically(probeFile)
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            ProbeState(
                state = json.optString("state"),
                total = json.optInt("total", 0),
                done = json.optInt("done", 0),
                alive = json.optInt("alive", 0),
            )
        }.getOrNull()
    }

    fun writeProbeState(state: ProbeState) {
        writeAtomically(
            probeFile,
            JSONObject().apply {
                put("state", state.state)
                put("total", state.total)
                put("done", state.done)
                put("alive", state.alive)
            }.toString(),
        )
    }

    // --- аренда прогона -----------------------------------------------------

    /**
     * Кто сейчас выпускает профили: владелец, его старшинство и последний признак жизни.
     */
    data class RunLease(val owner: String, val priority: Int, val heartbeatAt: Long)

    fun readRunLease(): RunLease? {
        val raw = readAtomically(runLeaseFile)
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            val owner = json.optString("owner")
            if (owner.isBlank()) return@runCatching null
            RunLease(
                owner = owner,
                priority = json.optInt("priority", RUN_PRIORITY_BACKGROUND),
                heartbeatAt = json.optLong("heartbeat_at", 0L),
            )
        }.getOrNull()
    }

    /**
     * Занимает прогон за [owner], если он свободен, протух или принадлежит младшему.
     *
     * Аренда лежит в файле, потому что выпускать профили умеют оба процесса: службу
     * просит фоновая подготовка, экран — явный выбор Proton. Признака в памяти хватало
     * бы ровно на один процесс, а два одновременных прогона регистрируют ключ дважды,
     * и сервер помнит только последний — то есть первый прогон дорисовал бы профили
     * под ключ, который уже никому не подходит.
     *
     * Старшинство одностороннее: явный выбор пользователя вытесняет фоновую
     * подготовку, обратное запрещено. Проигравшая сторона узнаёт об этом по
     * [ownsRunLease] на ближайшем шаге и заканчивает, ничего не записав.
     */
    fun tryAcquireRunLease(
        owner: String,
        priority: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        synchronized(writeLock) {
            val current = readRunLease()
            val alive = current != null && nowMs - current.heartbeatAt < RUN_LEASE_STALE_MS
            if (alive && current!!.owner != owner && current.priority >= priority) return false
            writeAtomically(
                runLeaseFile,
                JSONObject()
                    .put("owner", owner)
                    .put("priority", priority)
                    .put("heartbeat_at", nowMs)
                    .toString(),
            )
            return true
        }
    }

    /**
     * Признак жизни: без него аренда протухает и её заберёт следующий желающий.
     *
     * Зовётся часто — из каждого шага, из тика длинного шага раз в две секунды и из
     * опроса замера раз в 700 мс, — поэтому запись прореживается: окно протухания
     * измеряется десятками секунд, и писать файл чаще раза в десять секунд незачем.
     */
    fun heartbeatRunLease(owner: String, priority: Int, nowMs: Long = System.currentTimeMillis()) {
        synchronized(writeLock) {
            val current = readRunLease()
            if (current?.owner != owner) return
            if (nowMs - current.heartbeatAt < RUN_LEASE_HEARTBEAT_MIN_MS) return
            writeAtomically(
                runLeaseFile,
                JSONObject()
                    .put("owner", owner)
                    .put("priority", priority)
                    .put("heartbeat_at", nowMs)
                    .toString(),
            )
        }
    }

    fun ownsRunLease(owner: String): Boolean = readRunLease()?.owner == owner

    /** Освобождает аренду, если она ещё наша: чужую снимать нельзя. */
    fun releaseRunLease(owner: String) {
        synchronized(writeLock) {
            if (readRunLease()?.owner != owner) return
            runCatching { runLeaseFile.delete() }
        }
    }

    /**
     * Читает файл, **не трогая его**.
     *
     * `AtomicFile.readFully()` до Android 11 разрушителен: `openRead()` при живом
     * `.bak` удаляет основной файл и переименовывает резервный на его место. Замер
     * пишет состояние из двенадцати потоков `:vpn`, а интерфейс опрашивает его
     * каждые 700 мс — и такое чтение, попав между `startWrite()` и `finishWrite()`,
     * отменяло чужую запись. Если это была финальная `done`, экран досиживал все
     * 120 с и говорил «проверка не завершилась» при успешно домеренных профилях.
     *
     * Поэтому читаем основной файл напрямую, а на резервный переходим только когда
     * основной не разобрался: недописанный файл даёт `null` и пропущенный тик
     * опроса — это самовосстанавливается, потеря записи — нет.
     */
    private fun readAtomically(file: AtomicFile): String? {
        val base = runCatching { file.baseFile.readText(Charsets.UTF_8) }.getOrNull()
        if (!base.isNullOrBlank()) return base
        val backup = File(file.baseFile.path + ".bak")
        return runCatching { backup.takeIf { it.exists() }?.readText(Charsets.UTF_8) }.getOrNull()
    }

    private fun writeAtomically(file: AtomicFile, payload: String) {
        // Один замок на процесс: двенадцать потоков пробы пишут `proton_probe.json`
        // наперегонки, а `startWrite()` второго удаляет основной файл первого.
        synchronized(writeLock) {
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(payload.toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: Exception) {
                if (stream != null) runCatching { file.failWrite(stream) }
                LogManager.log("Proton store: не записали ${file.baseFile.name} — ${e.message}")
            }
        }
    }

    companion object {
        /**
         * Общий замок записи на процесс. Экземпляр хранилища создаётся на каждом
         * вызывающем, поэтому замок обязан быть здесь, а не в объекте.
         */
        private val writeLock = Any()

        /** Внутренние адреса Proton одинаковы у всех клиентов — это не наш выбор. */
        const val INTERFACE_ADDRESS = "10.2.0.2/32, 2a07:b944::2:2/128"
        const val INTERFACE_DNS = "10.2.0.1, 2a07:b944::2:1"
        const val INTERFACE_MTU = 1420

        /**
         * Порты, на которых узлы Proton принимают WireGuard. Раскладываются по
         * профилям по кругу: если оператор глушит один порт, кандидаты на нём просто
         * не отвечают на рукопожатие и отсеиваются замером, а список не остаётся
         * пустым.
         */
        val PORTS = listOf(51820, 443, 80, 88, 4500, 1194, 5060, 1224, 4569)

        fun buildDeviceProfile(random: Random = Random.Default): ProtonDeviceProfile {
            val models = listOf(
                "Pixel 7" to "13",
                "SM-A536B" to "13",
                "SM-S911B" to "14",
                "Redmi Note 12" to "13",
                "moto g84 5G" to "14",
            )
            val locales = listOf(
                Triple("fr", "FR", "Europe/Paris") to -60,
                Triple("de", "DE", "Europe/Berlin") to -60,
                Triple("en", "GB", "Europe/London") to 0,
                Triple("nl", "NL", "Europe/Amsterdam") to -60,
                Triple("es", "ES", "Europe/Madrid") to -60,
            )
            val (model, androidVersion) = models[random.nextInt(models.size)]
            val (locale, offset) = locales[random.nextInt(locales.size)]
            return ProtonDeviceProfile(
                model = model,
                androidVersion = androidVersion,
                language = locale.first,
                regionCode = locale.second,
                timezone = locale.third,
                timezoneOffset = offset,
                storageBytes = listOf(6.4e10, 1.28e11, 2.56e11)[random.nextInt(3)],
                deviceNameHash = random.nextLong(1_000_000_000_000L, 9_000_000_000_000_000L),
                keyboards = listOf("com.google.android.inputmethod.latin"),
            )
        }

        /** Текст `.conf` профиля. Собирается на лету — ключ живёт в одном месте. */
        fun buildRawConfig(profile: ProtonProfile, privateKeyBase64: String): String {
            val junk = buildString {
                append("\nS1 = 0\nS2 = 0\nS3 = 0\nS4 = 0")
                append("\nJc = ${profile.junkCount}\nJmin = ${profile.junkMin}\nJmax = ${profile.junkMax}")
                append("\nH1 = 1\nH2 = 2\nH3 = 3\nH4 = 4")
                if (profile.i1.isNotBlank()) append("\nI1 = ${profile.i1}")
            }
            return buildString {
                append("[Interface]\n")
                append("PrivateKey = $privateKeyBase64\n")
                append("Address = $INTERFACE_ADDRESS\n")
                append("DNS = $INTERFACE_DNS\n")
                append("MTU = $INTERFACE_MTU")
                append(junk)
                append("\n\n[Peer]\n")
                append("# ${profile.serverName} (${profile.city})\n")
                append("PublicKey = ${profile.peerPublicKey}\n")
                append("Endpoint = ${profile.entryIp}:${profile.port}\n")
                append("AllowedIPs = 0.0.0.0/0, ::/0\n")
            }
        }

        fun displayCountry(profile: ProtonProfile): String =
            profile.country.uppercase(Locale.US).takeIf { it.length == 2 } ?: "??"

        /**
         * Переводит профили в записи, которые понимает перебор службы.
         *
         * `userImported = true` здесь не косметика: именно по нему цикл подключения
         * включает строгий путь «применить профиль как есть» — ключи, адреса, DNS,
         * MTU и параметры AWG берутся из текста, а подварианты Nova не добавляются.
         * Без него к Proton-узлу поехали бы ключи собственной регистрации Nova, и
         * узел молча не ответил бы.
         *
         * Ранг задаётся замеренной задержкой: `qualityAvgPingMs` — то поле, по
         * которому сортирует `buildUserImportedWarpAttemptSet`, поэтому порядок
         * очереди совпадает с порядком списка и первым пробуется самый быстрый.
         */
        fun toVerifiedConfigs(
            profiles: List<ProtonProfile>,
            privateKeyBase64: String,
        ): List<WarpVerifiedConfig> {
            if (privateKeyBase64.isBlank()) return emptyList()
            return profiles.mapIndexed { index, profile ->
                WarpVerifiedConfig(
                    id = "proton|${profile.entryIp}|${profile.port}",
                    engine = "wireguard",
                    mode = "warp-awg-exact",
                    host = profile.entryIp,
                    port = profile.port,
                    endpointSource = ENDPOINT_SOURCE,
                    rawConfig = buildRawConfig(profile, privateKeyBase64),
                    createdAt = profile.createdAt,
                    lastVerifiedAt = profile.createdAt,
                    seedOrder = index,
                    successCount = 1,
                    manual = false,
                    userImported = true,
                    qualityProbeCount = 1,
                    qualityPingSuccesses = if (profile.pingMs > 0) 1 else 0,
                    qualityAvgPingMs = profile.pingMs.takeIf { it > 0 }?.toDouble() ?: 0.0,
                    qualityLastCheckedAt = profile.createdAt,
                    preferredSni = profile.sni,
                )
            }
        }

        const val ENDPOINT_SOURCE = "proton"

        const val STATE_REQUESTED = "requested"
        const val STATE_RUNNING = "running"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"

        /** Фоновая подготовка уступает всем. */
        const val RUN_PRIORITY_BACKGROUND = 0

        /** Явный выбор Proton пользователем вытесняет фоновую подготовку. */
        const val RUN_PRIORITY_USER = 1

        /**
         * Через столько молчания аренда считается брошенной.
         *
         * Признак жизни ставится на каждом шаге прогона и раз в две секунды внутри
         * длинных сетевых шагов, так что живой прогон обновляет его многократно за
         * это окно. Порог с большим запасом: убитый процесс не освобождает аренду
         * сам, и слишком короткое окно значило бы два прогона наперегонки.
         */
        const val RUN_LEASE_STALE_MS = 90_000L

        /** Реже этого признак жизни не пишется — см. [heartbeatRunLease]. */
        private const val RUN_LEASE_HEARTBEAT_MIN_MS = 10_000L
    }
}

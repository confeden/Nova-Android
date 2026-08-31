package com.example.nova

import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Клиент API Proton VPN в объёме, которого хватает для выпуска собственных
 * AWG-профилей: сессия без учётных данных, список бесплатных узлов, регистрация
 * ключа.
 *
 * Учётной записи не заводится и пароля не спрашивается: `auth/v4/credentialless`
 * — это штатный путь официального приложения Proton («подключиться без аккаунта»).
 * Он выдаёт сессию со scope `vpn`, чего достаточно и для `/vpn/logicals`, и для
 * `/vpn/v1/certificate`. Неаутентифицированная сессия (`auth/v4/sessions`) сама по
 * себе не годится — оба вызова отвечают на неё `9106 MissingScopes: [user, vpn]`,
 * поэтому шаг `credentialless` пропустить нельзя.
 */
object ProtonApi {

    /** Оба хоста рабочие; второй остаётся на случай, если первый недоступен из сети. */
    private val HOSTS = listOf("https://vpn-api.proton.me", "https://api.protonvpn.ch")

    private const val APP_VERSION = "android-vpn@5.4.44.0"
    private const val CHALLENGE_FRAME_KEY = "vpn-android-v4-challenge-0"
    private const val CHALLENGE_VERSION = "2.0.7"

    private val JSON = "application/json".toMediaType()

    /**
     * Сроки для **прямых** хостов Proton — короткие намеренно.
     *
     * В России до них TCP 443 не открывается вовсе (P1): имя резолвится, ICMP
     * отвечает, соединение не устанавливается никогда. На прежних 12/20/30 с каждый
     * вызов честно ждал по тридцать секунд на каждом из двух хостов — минута
     * впустую перед тем, как включится обход, и это была самая заметная часть
     * ожидания на чистой установке. Хост, не открывший соединение за шесть секунд,
     * не откроет его и за тридцать.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    class ProtonApiException(message: String) : Exception(message)

    /** Адрес, по которому можно попробовать запрос, вместе с клиентом для него. */
    private class Target(val client: OkHttpClient, val base: String)

    /** Адрес, который ответил, и его ответ: [call] обязан знать, кто выиграл. */
    private class Winner(val base: String, val json: JSONObject)

    data class Session(val uid: String, val accessToken: String, val refreshToken: String)

    data class Server(
        val name: String,
        val country: String,
        val city: String,
        val entryIp: String,
        val peerPublicKey: String,
        val load: Int,
        val score: Double,
    )

    private fun userAgent(profile: ProtonDeviceProfile): String =
        "ProtonVPN/5.4.44.0 (Android ${profile.androidVersion}; ${profile.model})"

    /**
     * Найденный запасной узел живёт до конца процесса.
     *
     * Искать его заново на каждый запрос значило бы четыре DoH-запроса на каждый
     * шаг генерации; а сбрасывать его нечем и незачем — если он перестанет
     * отвечать, прогон всё равно начнётся с прямых хостов.
     */
    @Volatile
    private var alternativeHost: String? = null

    /**
     * Подсказать найденный ранее запасной узел и забрать текущий.
     *
     * Нужно, чтобы прогон **продолжался**, а не начинался с нуля: узел ищется через
     * DoH, а именно DoH на чистой установке однажды и не ответил — сохранённый узел
     * позволяет следующему заходу пропустить этот шаг целиком.
     */
    fun seedAlternativeHost(host: String?) {
        val normalized = host?.trim().orEmpty()
        if (normalized.isNotEmpty() && alternativeHost.isNullOrBlank()) {
            alternativeHost = normalized
            LogManager.log("Proton: помним запасной узел с прошлого раза — $normalized.")
        }
    }

    fun currentAlternativeHost(): String = alternativeHost?.trim().orEmpty()

    /**
     * Прямой хост, который в этом процессе уже отвечал, — первым в следующий раз.
     *
     * Гонка спасает только идемпотентные вызовы; POST-ы ([HOSTS] по очереди) на
     * российской сети платили по 12 с за `vpn-api.proton.me`, который на ней не
     * открывает TCP 443 вовсе, — и так на каждом из трёх вызовов прогона. Какой
     * именно хост закрыт, зависит от сети, поэтому порядок не прибит в [HOSTS], а
     * выясняется первым же удавшимся вызовом.
     *
     * Живёт до конца процесса и не сохраняется на диск намеренно: при живом
     * сертификате следующий прогон обходится вообще без POST-ов, а сеть к тому
     * времени может смениться — запомненный на диске хост тогда стоил бы того же
     * таймаута, только с уверенным видом.
     */
    @Volatile
    private var preferredDirectHost: String? = null

    private fun buildRequest(
        base: String,
        path: String,
        body: JSONObject?,
        profile: ProtonDeviceProfile,
        session: Session?,
    ): Request {
        val builder = Request.Builder()
            .url(base + path)
            .header("x-pm-appversion", APP_VERSION)
            .header("x-pm-apiversion", "3")
            .header("Accept", "application/vnd.protonmail.v1+json")
            .header("User-Agent", userAgent(profile))
        if (session != null) {
            builder.header("x-pm-uid", session.uid)
            builder.header("Authorization", "Bearer ${session.accessToken}")
        }
        if (body != null) {
            builder.post(body.toString().toRequestBody(JSON))
        } else {
            builder.get()
        }
        return builder.build()
    }

    /**
     * @param abandoned выставляется **нами**, когда гонку уже выиграл сосед и
     *        остальные вызовы обрываются. Только по этому признаку и можно молчать
     *        об ошибке.
     * @return разобранный ответ, либо null с записанной причиной. Отличать
     *         «не достучались» от «ответили ошибкой» обязательно: на запасной
     *         маршрут имеет смысл уходить только в первом случае, а ошибку API
     *         он повторит слово в слово.
     */
    private fun execute(
        call: Call,
        base: String,
        abandoned: AtomicBoolean?,
        onError: (String, Boolean) -> Unit,
    ): JSONObject? {
        return try {
            call.execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) {
                    onError("HTTP ${response.code}, пустой ответ", false)
                    return@use null
                }
                val json = JSONObject(text)
                if (!response.isSuccessful) {
                    onError("HTTP ${response.code}: ${json.optString("Error").ifBlank { text.take(200) }}", false)
                    return@use null
                }
                json
            }
        } catch (e: Exception) {
            // Признак «оборвали мы сами» — наш собственный флаг, а **не**
            // `call.isCanceled()`.
            //
            // Дефект, который это чинит: по истечении `callTimeout` OkHttp отменяет
            // вызов сам, и `isCanceled()` после этого возвращает true. То есть на
            // российской сети, где прямые хосты Proton не открывают TCP 443 вовсе,
            // причина не записывалась **ни разу**: `lastError` оставался значением по
            // умолчанию «нет ответа», `transportFailure` — false, и [call] бросал
            // исключение, ни разу не сходив на запасные узлы. Снаружи это ровно
            // «регистрация Proton висит и никуда не двигается»: весь прогон падал
            // за 33 с, а штатный обход блокировки не включался никогда.
            if (abandoned?.get() != true) onError("${base.substringAfter("://")}: ${e.message}", true)
            null
        }
    }

    /**
     * Один заход по группе адресов.
     *
     * Идемпотентный запрос уходит на все адреса разом ([ProtonRace]) — на
     * российской сети шесть запасных узлов по очереди стоили две с половиной
     * минуты. Запрос **с телом** идёт строго по очереди: POST у Proton не
     * идемпотентен, и `body != null` — единственный признак, который не забудут
     * обновить, добавляя новый вызов.
     */
    private fun walk(
        targets: List<Target>,
        path: String,
        body: JSONObject?,
        profile: ProtonDeviceProfile,
        session: Session?,
        record: (String, Boolean) -> Unit,
    ): Winner? {
        if (targets.isEmpty()) return null
        if (body != null) {
            for (target in targets) {
                val request = buildRequest(target.base, path, body, profile, session)
                execute(target.client.newCall(request), target.base, null, record)
                    ?.let { return Winner(target.base, it) }
            }
            return null
        }
        val calls = targets.map { it.client.newCall(buildRequest(it.base, path, null, profile, session)) }
        val abandoned = AtomicBoolean(false)
        return ProtonRace.firstSuccess(
            attempts = targets.indices.map { index ->
                {
                    execute(calls[index], targets[index].base, abandoned, record)
                        ?.let { Winner(targets[index].base, it) }
                }
            },
            // Флаг поднимается **до** отмены: проигравший обязан увидеть его уже
            // выставленным, иначе запишет свой обрыв как настоящую причину.
            cancelAll = {
                abandoned.set(true)
                calls.forEach { runCatching { it.cancel() } }
            },
        )
    }

    private fun call(
        path: String,
        body: JSONObject?,
        profile: ProtonDeviceProfile,
        session: Session?,
    ): JSONObject {
        // Гонка пишет причину из нескольких потоков сразу, поэтому под замком.
        val lock = Any()
        var lastError = "нет ответа"
        var transportFailure = false
        val record: (String, Boolean) -> Unit = { message, isTransport ->
            synchronized(lock) {
                lastError = message
                if (isTransport) transportFailure = true
            }
        }
        val label = path.substringBefore("?")
        val startedAt = System.nanoTime()
        val elapsedMs = { (System.nanoTime() - startedAt) / 1_000_000 }

        val direct = HOSTS.sortedByDescending { it == preferredDirectHost }
        val primary = ArrayList<Target>(direct.size + 1)
        alternativeHost?.let { primary += Target(ProtonDoh.pinnedClient, "https://$it") }
        direct.forEach { primary += Target(client, it) }
        walk(primary, path, body, profile, session, record)?.let { winner ->
            if (winner.base in HOSTS && preferredDirectHost != winner.base) {
                preferredDirectHost = winner.base
                LogManager.log("Proton: прямой хост ${winner.base.substringAfter("://")} отвечает — с него и начинаем дальше.")
            }
            return winner.json
        }

        // Прерывание — не отказ сети, и молчать о нём нельзя: с ним запасные узлы
        // тоже не ответят, а причина «нет ответа» отправила бы искать поломку в
        // сети вместо того, кто оборвал шаг.
        if (Thread.currentThread().isInterrupted) {
            LogManager.log("Proton $label: шаг прерван снаружи за ${elapsedMs()} мс, запасные узлы не опрашиваем.")
            throw ProtonApiException("шаг прерван")
        }

        // Прямые хосты Proton в России закрыты на транспортном уровне: имя
        // резолвится и отвечает на ICMP, но TCP 443 не открывается. Штатный обход
        // самого Proton — запасные узлы из TXT-записи, см. [ProtonDoh].
        if (!transportFailure) {
            LogManager.log(
                "Proton $label: прямые хосты отказали за ${elapsedMs()} мс без транспортной ошибки " +
                    "($lastError) — это ответ сервера, обход его повторит слово в слово."
            )
            throw ProtonApiException(lastError)
        }

        // Без этой строки шаг молчал всё время обхода: на устройстве это две
        // минуты без единой записи, неотличимые от зависания (I4).
        LogManager.log(
            "Proton $label: прямые хосты не ответили за ${elapsedMs()} мс ($lastError), " +
                "идём через запасные узлы."
        )
        val candidates = ProtonDoh.resolveAlternativeHosts(HOSTS.first().substringAfter("://"))
            .map { Target(ProtonDoh.pinnedClient, "https://$it") }
        walk(candidates, path, body, profile, session, record)?.let { winner ->
            val host = winner.base.substringAfter("://")
            if (alternativeHost != host) {
                LogManager.log("Proton: работаем через запасной узел $host.")
                alternativeHost = host
            }
            LogManager.log("Proton $label: ответ через запасной узел за ${elapsedMs()} мс.")
            return winner.json
        }
        LogManager.log(
            "Proton $label: не ответил ни один из ${candidates.size} запасных узлов, " +
                "весь шаг занял ${elapsedMs()} мс."
        )
        throw ProtonApiException(lastError)
    }

    /** Шаг 1: сессия без аутентификации — нужна только как носитель для шага 2. */
    private fun requestUnauthenticatedSession(profile: ProtonDeviceProfile): Session {
        val json = call("/auth/v4/sessions", JSONObject(), profile, null)
        return Session(
            uid = json.optString("UID"),
            accessToken = json.optString("AccessToken"),
            refreshToken = json.optString("RefreshToken"),
        ).also {
            if (it.uid.isBlank() || it.accessToken.isBlank()) {
                throw ProtonApiException("сессия без UID/токена")
            }
        }
    }

    /**
     * Шаг 2: сессия без учётных данных.
     *
     * `Payload` — анти-абузный кадр Proton. Его поля описывают устройство, и
     * отправляется **выдуманный** набор, постоянный для установки: настоящие
     * Build-значения были бы отпечатком устройства, отданным третьей стороне без
     * всякой нужды, а меняющийся от вызова к вызову набор выглядит для их
     * анти-абуза хуже, чем один и тот же.
     */
    /**
     * Создаёт сессию без учётной записи, переживая потерянный ответ.
     *
     * `/auth/v4/credentialless` **не идемпотентен**: он привязывает сессию к
     * пользователю. А [call] перебирает хосты — сначала запасной узел, потом
     * основные, потом все выданные DoH. Если запрос дошёл, а ответ потерялся на
     * российском транзите (он стабильно обрывает ответы, P3), следующий хост
     * получает `400 Session already tied to a user`, и весь прогон падал с этой
     * строкой на устройстве, где Proton уже поднимали.
     *
     * Прежняя сессия к этому моменту сожжена, поэтому лечение — взять новую и
     * повторить ровно один раз. Повторять бесконечно нельзя: если привязка ломается
     * не из-за потери ответа, цикл ничего не исправит и только скроет причину.
     */
    fun createCredentiallessSession(profile: ProtonDeviceProfile): Session {
        return try {
            createCredentiallessSessionOnce(profile)
        } catch (error: ProtonApiException) {
            if (error.message?.contains("already tied", ignoreCase = true) != true) throw error
            LogManager.log(
                "Proton: сессия уже привязана — значит ответ на предыдущую попытку потерялся, " +
                    "а запрос дошёл. Берём новую сессию и повторяем один раз."
            )
            createCredentiallessSessionOnce(profile)
        }
    }

    private fun createCredentiallessSessionOnce(profile: ProtonDeviceProfile): Session {
        val carrier = requestUnauthenticatedSession(profile)
        val frame = JSONObject().apply {
            put("v", CHALLENGE_VERSION)
            put("appLang", profile.language)
            put("timezone", profile.timezone)
            put("deviceName", profile.deviceNameHash)
            put("regionCode", profile.regionCode)
            put("timezoneOffset", profile.timezoneOffset)
            put("isJailbreak", false)
            put("preferredContentSize", "1.0")
            put("storageCapacity", profile.storageBytes)
            put("isDarkmodeOn", true)
            put("keyboards", JSONArray(profile.keyboards))
        }
        val body = JSONObject().put("Payload", JSONObject().put(CHALLENGE_FRAME_KEY, frame))
        val json = call("/auth/v4/credentialless", body, profile, carrier)
        val scopes = json.optJSONArray("Scopes")?.let { array ->
            (0 until array.length()).map { array.optString(it) }
        }.orEmpty()
        if (!scopes.contains("vpn")) {
            throw ProtonApiException("сессия без scope vpn (получено: ${scopes.joinToString(",")})")
        }
        return Session(
            uid = json.optString("UID"),
            accessToken = json.optString("AccessToken"),
            refreshToken = json.optString("RefreshToken"),
        )
    }

    /**
     * Шаг 3: бесплатные узлы. `Tier=0` — это и есть бесплатный уровень; платные в
     * ответ на такую сессию всё равно не приходят, но фильтр оставлен явным, чтобы
     * список не разрастался при смене плана.
     */
    fun fetchFreeServers(profile: ProtonDeviceProfile, session: Session): List<Server> {
        val json = call("/vpn/logicals?Tier=0", null, profile, session)
        val logicals = json.optJSONArray("LogicalServers") ?: return emptyList()
        val out = ArrayList<Server>(logicals.length())
        for (i in 0 until logicals.length()) {
            val logical = logicals.optJSONObject(i) ?: continue
            if (logical.optInt("Tier", -1) != 0) continue
            if (logical.optInt("Status", 0) != 1) continue
            val physicals = logical.optJSONArray("Servers") ?: continue
            for (j in 0 until physicals.length()) {
                val physical = physicals.optJSONObject(j) ?: continue
                if (physical.optInt("Status", 0) != 1) continue
                val entryIp = physical.optString("EntryIP")
                val peerKey = physical.optString("X25519PublicKey")
                if (entryIp.isBlank() || peerKey.isBlank()) continue
                out += Server(
                    name = logical.optString("Name").ifBlank { "PROTON" },
                    country = logical.optString("ExitCountry").ifBlank { "??" },
                    city = logical.optString("City").orEmpty(),
                    entryIp = entryIp,
                    peerPublicKey = peerKey,
                    load = logical.optInt("Load", 100),
                    score = logical.optDouble("Score", Double.MAX_VALUE),
                )
                // Физические узлы одного логического делят и адрес, и ключ —
                // берём один, иначе список раздувается копиями.
                break
            }
        }
        return out
    }

    /**
     * Шаг 4: регистрация ключа.
     *
     * `Mode: persistent` даёт год жизни; выданный сертификат туннелю не нужен —
     * важен сам факт, что Proton теперь знает наш публичный ключ. Возвращается
     * момент истечения, чтобы профили можно было перевыпустить до того, как они
     * молча перестанут подниматься.
     */
    fun registerClientKey(
        profile: ProtonDeviceProfile,
        session: Session,
        publicKeyPem: String,
    ): Long {
        val body = JSONObject()
            .put("ClientPublicKey", publicKeyPem)
            .put("Mode", "persistent")
            .put("DeviceName", "Nova")
        val json = call("/vpn/v1/certificate", body, profile, session)
        val expiresAt = json.optLong("ExpirationTime", 0L)
        if (expiresAt <= 0L) throw ProtonApiException("сертификат без ExpirationTime")
        return expiresAt * 1000L
    }
}

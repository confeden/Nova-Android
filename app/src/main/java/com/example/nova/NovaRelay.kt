package com.example.nova

import android.content.Context
import android.util.AtomicFile
import okhttp3.Response
import org.json.JSONObject
import java.io.File

/**
 * Общее для всех потребителей описание релеев в Швеции.
 *
 * До этого объекта список адресов лежал в двух файлах сразу — в
 * [OperaProxyManager] и в [ProtonRelay] — с одинаковыми значениями и
 * одинаковым логином. Третий потребитель (регистрация Cloudflare) сделал бы
 * третью копию, а копия расходится молча: перенос релея на другой порт
 * чинился бы в двух местах из трёх.
 *
 * **Через релей идёт только регистрация.** Список разрешённых имён на самом
 * сервере — короткий и состоит из API: SurfEasy, Proton, Cloudflare и Moat
 * Tor. Ни один туннель через него не проходит и пройти не может: точки входа
 * туннелей в список не внесены, а порт разрешён единственный — 443. Поэтому
 * страна выхода у пользователя не меняется, а сервер не превращается в
 * VPN-перевозчика.
 *
 * ## Ключ на выпуск
 *
 * У каждого выпуска Nova — свой ключ релея, и сервер принимает **три** ключа на
 * платформу: текущего выпуска и двух предыдущих. Логин имеет вид
 * `nova-<платформа>-<версия>`, версия Android — это `versionCode`.
 *
 * Больше одного, потому что выпуск — не мгновение: APK доезжает до людей
 * днями (F-Droid пересобирает и публикует по своему расписанию), и тот, кто ещё
 * не обновился, должен уметь зарегистрироваться, а не получать «обновитесь» до
 * того, как обновление вышло. Не больше трёх, потому что смысл правила —
 * чтобы утёкший ключ протухал через известное число выпусков.
 *
 * Ключи Android и ПК живут в разных пространствах имён: выпуск Android не
 * должен отключать установленные копии для ПК, они обновляются в свой день.
 *
 * Ключ не секрет и лежит в исходниках (`BuildConfig`), чтобы сборка F-Droid
 * была полноценной: её собирают из открытого дерева и побайтово сверяют с
 * нашим APK, поэтому значение, которого нет в исходниках, туда не попадёт.
 * Защиту даёт не тайна ключа, а список разрешённых имён на сервере и смена
 * ключа на каждом выпуске.
 *
 * Если сервер ответил `407` с заголовком `X-Nova-Relay-Reason: outdated-client`,
 * ключ этой копии уже погашен — это ровно «приложение устарело», и говорить об
 * этом надо словами, а не сетевой ошибкой (I4). После такого ответа релей молчит
 * паузу ([OutdatedVerdict]): повторять запрос раньше незачем.
 */
object NovaRelay {

    /**
     * Адреса релеев. Не секрет: секретен только ключ, и тот — до следующего
     * выпуска.
     *
     * Два порта — это два независимых входа на одну машину. 8443 бывает закрыт
     * там, где 2053 проходит, и наоборот.
     */
    val ENDPOINTS: List<Pair<String, Int>> = listOf(
        "relay.nova-app.eu" to 8443,
        "relay.nova-app.eu" to 2053,
    )

    /** Текст для пользователя, когда сервер погасил ключ этой сборки. */
    const val OUTDATED_MESSAGE: String =
        "Регистрация через наш relay доступна только для последней версии приложения, " +
            "обновите его и повторите попытку"

    /** Заголовок, которым сервер отличает «ключ погашен» от «пароль неверен». */
    internal const val REASON_HEADER = "X-Nova-Relay-Reason"
    private const val REASON_OUTDATED = "outdated-client"
    internal const val CURRENT_HEADER = "X-Nova-Relay-Current"

    private const val STATE_FILE = "relay_state.json"

    private val writeLock = Any()

    @Volatile
    private var appContext: Context? = null

    /**
     * Контекст для файла состояния.
     *
     * Зовётся рядом с `LogManager.setAppContext`. Признак должен пережить экран
     * (I18) и дойти до другого процесса (I2), а объекту-синглтону контекст взять
     * больше неоткуда: `ProtonApi` и `ProtonRelay` — объекты без него.
     */
    fun attach(context: Context?) {
        appContext = context?.applicationContext
    }

    /** Логин этой сборки: `nova-android-<versionCode>`. */
    fun keyId(): String = BuildConfig.RELAY_KEY_ID.trim()

    /** Ключ этой сборки. Пусто — релеи не используются вовсе. */
    fun password(): String = BuildConfig.OPERA_RELAY_PASSWORD.trim()

    /**
     * Настроен ли релей.
     *
     * Пустой ключ — это не ошибка, а сборка без него; подставлять заглушку
     * значило бы потратить попытку и получить `407`, чтобы узнать то же самое.
     */
    fun isConfigured(): Boolean = password().isNotEmpty() && keyId().isNotEmpty()

    /** Подпись релея для журнала — без ключа. */
    fun describe(index: Int): String =
        ENDPOINTS.getOrNull(index)?.let { "${it.first}:${it.second}" } ?: "?"

    // -- «ключ погашен» ----------------------------------------------------

    /**
     * Ответ ли это «ваша версия устарела».
     *
     * Проверяем именно заголовок, а не голый `407`: неверный пароль и погашенный
     * ключ дают один и тот же код, а лечение у них разное — одно чинится
     * обновлением, другое пересборкой.
     */
    fun isOutdatedResponse(response: Response): Boolean =
        response.code == 407 &&
            response.header(REASON_HEADER)?.trim()?.equals(REASON_OUTDATED, ignoreCase = true) == true

    /**
     * Запоминает и объявляет, что ключ этой сборки погашен, и назначает паузу.
     *
     * Пишется файлом, а не в `SharedPreferences`: признак рождается в `:vpn`, а
     * показывает его экран, и кэш настроек на процесс разводит их по разным
     * значениям (I2).
     */
    fun noteOutdated(source: String, currentVersion: String = "") {
        val now = System.currentTimeMillis()
        val outcome = synchronized(writeLock) {
            val known = readState()
            val previous = known?.let(::verdictOf)
            val already = previous?.keyId == keyId()
            val verdict = OutdatedVerdict.next(previous, keyId(), now)
            if (verdict != previous) {
                writeState(
                    JSONObject()
                        .put("outdated", true)
                        .put("key_id", verdict.keyId)
                        .put("server_current", currentVersion.ifEmpty { known?.optString("server_current").orEmpty() })
                        .put("source", source)
                        .put("at", now)
                        .put("strikes", verdict.strikes)
                        .put("paused_until", verdict.pausedUntil)
                )
            }
            Triple(already, verdict, verdict != previous)
        }
        val (already, verdict, escalated) = outcome
        if (!already) {
            LogManager.log(
                "Релей: ключ этой сборки погашен сервером ($source, ключ ${keyId()}" +
                    (if (currentVersion.isNotEmpty()) ", актуальная версия $currentVersion" else "") +
                    "). $OUTDATED_MESSAGE."
            )
        }
        if (escalated) {
            LogManager.log(
                "Релей: отказ №${verdict.strikes} по ключу ${verdict.keyId} ($source) — " +
                    "к релею не обращаемся ${describeDuration(verdict.pausedUntil - now)}."
            )
        }
    }

    /** Снимает признак: релей ответил, значит ключ снова принят. */
    fun clearOutdated() {
        if (readState()?.optBoolean("outdated") != true) return
        writeState(JSONObject().put("outdated", false).put("at", System.currentTimeMillis()))
        LogManager.log("Релей: ключ снова принят, сообщение об устаревшей версии снято.")
    }

    /**
     * Показывать ли пользователю, что версия устарела.
     *
     * Только для ключа этой сборки: вердикт, записанный прошлой версией, после
     * обновления ничего не значит — у новой версии свой ключ. Раньше признак не
     * был привязан к ключу и переживал обновление, а снимался лишь удачным
     * обращением к релею, которое сам же и запрещал.
     */
    fun isOutdated(): Boolean = readState()?.let(::verdictOf)?.keyId == keyId()

    /**
     * До какого момента к релею не обращаться; 0 — можно сейчас.
     *
     * Потребители спрашивают это перед каждым заходом. Когда пауза истекла,
     * проходит одна проверка: ключ однажды уже возвращали на сервер (156), и
     * приложение должно это заметить без обновления.
     */
    fun pausedUntil(): Long =
        readState()?.let(::verdictOf)?.pauseEndFor(keyId(), System.currentTimeMillis()) ?: 0L

    /** Релей на паузе после «ключ погашен». */
    fun isPaused(): Boolean = pausedUntil() > 0L

    /** Сколько ещё длится пауза — для журнала: «25 мин», «2 ч 5 мин». */
    fun describePause(): String =
        describeDuration((pausedUntil() - System.currentTimeMillis()).coerceAtLeast(0L))

    /**
     * Вердикт по заголовку ответа на `CONNECT`, как его видит мост Opera.
     *
     * Opera ходит в релей не через OkHttp, а процессом `opera-proxy`, и
     * аутентификатора, который прочёл бы заголовок, у неё нет. Зато TLS до релея
     * разворачивает наш мост, и ответ на `CONNECT` проходит через него открытым
     * текстом ещё до шифрованного потока к API.
     */
    fun noteFromConnectHead(head: ByteArray, source: String) {
        val parsed = RelayConnectHead.parse(head) ?: return
        when {
            parsed.code == 407 && parsed.reason.equals(REASON_OUTDATED, ignoreCase = true) ->
                noteOutdated(source, parsed.current)
            parsed.code in 200..299 -> clearOutdated()
        }
    }

    private fun verdictOf(state: JSONObject): OutdatedVerdict? {
        if (!state.optBoolean("outdated")) return null
        return OutdatedVerdict(
            keyId = state.optString("key_id"),
            strikes = state.optInt("strikes", 0),
            pausedUntil = state.optLong("paused_until", 0L),
        )
    }

    private fun describeDuration(ms: Long): String {
        val minutes = (ms + 59_999L) / 60_000L
        return when {
            minutes < 60 -> "$minutes мин"
            minutes % 60 == 0L -> "${minutes / 60} ч"
            else -> "${minutes / 60} ч ${minutes % 60} мин"
        }
    }

    /** Версия, которую сервер называет актуальной. Пусто — он её не назвал. */
    fun serverCurrentVersion(): String = readState()?.optString("server_current").orEmpty()

    /**
     * Разбирает ответ и, если ключ погашен, запоминает это.
     *
     * @return true, если дальше пробовать релей бессмысленно.
     */
    fun noteFromResponse(response: Response, source: String): Boolean {
        if (!isOutdatedResponse(response)) return false
        noteOutdated(source, response.header(CURRENT_HEADER)?.trim().orEmpty())
        return true
    }

    // -- файл состояния ----------------------------------------------------

    private fun stateFile(): AtomicFile? {
        val dir = appContext?.filesDir ?: return null
        return AtomicFile(File(dir, STATE_FILE))
    }

    /**
     * Чтение в обход `readFully()`.
     *
     * До Android 11 `AtomicFile.openRead()` разрушителен для чужой незавершённой
     * записи (G66): читаем основной файл напрямую, а к резервному переходим,
     * только если основной пуст.
     */
    private fun readState(): JSONObject? {
        val file = stateFile() ?: return null
        val raw = runCatching { file.baseFile.readText(Charsets.UTF_8) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching {
                File(file.baseFile.path + ".bak").takeIf { it.exists() }?.readText(Charsets.UTF_8)
            }.getOrNull()
            ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    private fun writeState(payload: JSONObject) {
        val file = stateFile() ?: return
        synchronized(writeLock) {
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(payload.toString().toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
            } catch (e: Exception) {
                if (stream != null) runCatching { file.failWrite(stream) }
                LogManager.log("Релей: состояние не записалось — ${e.message}")
            }
        }
    }
}

/**
 * Вердикт «ключ погашен» и пауза после него — без файлов и часов, ради тестов.
 *
 * Вердикт окончательный до обновления приложения, и повторять запрос раньше
 * незачем. По погашенному ключу 155 журнал релея насчитал около 90 тысяч попыток с
 * ~440 адресов за пять часов — примерно 40 в час с одного адреса (P53). Пауза
 * растёт с каждым отказом, пришедшим после её конца, до потолка в 12 часов — две
 * проверки в сутки.
 *
 * Отказы одного залпа (Opera перебирает планы подряд, Proton идёт в оба порта)
 * приходят, когда пауза уже идёт, и не удлиняют её: иначе одна неудачная попытка
 * подключения сразу загоняла бы паузу под потолок.
 */
internal data class OutdatedVerdict(
    val keyId: String,
    val strikes: Int,
    val pausedUntil: Long,
) {
    /** До какого момента не обращаться к релею с ключом [currentKeyId]; 0 — можно сейчас. */
    fun pauseEndFor(currentKeyId: String, now: Long): Long = when {
        keyId != currentKeyId -> 0L
        now >= pausedUntil -> 0L
        // Часы перевели назад: такая пауза тянулась бы дольше любой назначаемой.
        pausedUntil - now > PAUSES_MS.last() -> 0L
        else -> pausedUntil
    }

    companion object {
        val PAUSES_MS: LongArray = longArrayOf(
            30L * 60_000,
            60L * 60_000,
            2L * 3_600_000,
            4L * 3_600_000,
            8L * 3_600_000,
            12L * 3_600_000,
        )

        /** Вердикт после очередного отказа релея. */
        fun next(previous: OutdatedVerdict?, keyId: String, now: Long): OutdatedVerdict {
            val same = previous?.takeIf { it.keyId == keyId }
            if (same != null && same.pauseEndFor(keyId, now) > 0L) return same
            val strikes = (same?.strikes ?: 0) + 1
            return OutdatedVerdict(keyId, strikes, now + PAUSES_MS[minOf(strikes, PAUSES_MS.size) - 1])
        }
    }
}

/** Заголовок ответа релея на `CONNECT`: код и оба заголовка вердикта. */
internal data class RelayConnectHead(val code: Int, val reason: String, val current: String) {
    companion object {
        /** Сколько байт ответа копить: заголовок релея — сотня байт, дальше идёт TLS. */
        const val LIMIT_BYTES = 8 * 1024

        /** Разобранный заголовок; null — ответ не HTTP или ещё не дочитан до пустой строки. */
        fun parse(bytes: ByteArray): RelayConnectHead? {
            val text = String(bytes, Charsets.ISO_8859_1)
            val end = text.indexOf("\r\n\r\n")
            if (end < 0) return null
            val lines = text.substring(0, end).split("\r\n")
            val status = lines.first().split(' ')
            if (status.size < 2 || !status[0].startsWith("HTTP/")) return null
            val code = status[1].toIntOrNull() ?: return null
            fun header(name: String): String = lines.drop(1)
                .firstOrNull { it.substringBefore(':').trim().equals(name, ignoreCase = true) }
                ?.substringAfter(':')
                ?.trim()
                .orEmpty()
            return RelayConnectHead(code, header(NovaRelay.REASON_HEADER), header(NovaRelay.CURRENT_HEADER))
        }
    }
}

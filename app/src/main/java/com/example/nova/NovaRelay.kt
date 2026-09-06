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
 * У каждого выпуска Nova — свой ключ релея, и сервер принимает **два** ключа на
 * платформу: текущего выпуска и предыдущего. Логин имеет вид
 * `nova-<платформа>-<версия>`, версия Android — это `versionCode`.
 *
 * Два, а не один, потому что выпуск — не мгновение: APK доезжает до людей
 * днями (F-Droid пересобирает и публикует по своему расписанию), и тот, кто ещё
 * не обновился, должен уметь зарегистрироваться, а не получать «обновитесь» до
 * того, как обновление вышло. Два, а не больше, потому что смысл правила —
 * чтобы утёкший ключ протухал вместе со своим выпуском.
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
 * этом надо словами, а не сетевой ошибкой (I4).
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
    private const val REASON_HEADER = "X-Nova-Relay-Reason"
    private const val REASON_OUTDATED = "outdated-client"
    private const val CURRENT_HEADER = "X-Nova-Relay-Current"

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
     * Запоминает и объявляет, что ключ этой сборки погашен.
     *
     * Пишется файлом, а не в `SharedPreferences`: признак рождается в `:vpn`, а
     * показывает его экран, и кэш настроек на процесс разводит их по разным
     * значениям (I2).
     */
    fun noteOutdated(source: String, currentVersion: String = "") {
        val known = readState()
        val already = known?.optBoolean("outdated") == true
        writeState(
            JSONObject()
                .put("outdated", true)
                .put("key_id", keyId())
                .put("server_current", currentVersion)
                .put("source", source)
                .put("at", System.currentTimeMillis())
        )
        if (!already) {
            LogManager.log(
                "Релей: ключ этой сборки погашен сервером ($source, ключ ${keyId()}" +
                    (if (currentVersion.isNotEmpty()) ", актуальная версия $currentVersion" else "") +
                    "). $OUTDATED_MESSAGE."
            )
        }
    }

    /** Снимает признак: релей ответил, значит ключ снова принят. */
    fun clearOutdated() {
        if (readState()?.optBoolean("outdated") != true) return
        writeState(JSONObject().put("outdated", false).put("at", System.currentTimeMillis()))
        LogManager.log("Релей: ключ снова принят, сообщение об устаревшей версии снято.")
    }

    /** Показывать ли пользователю, что версия устарела. */
    fun isOutdated(): Boolean = readState()?.optBoolean("outdated") == true

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

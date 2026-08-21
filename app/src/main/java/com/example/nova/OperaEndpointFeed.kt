package com.example.nova

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Опубликованный нами список рабочих адресов Opera.
 *
 * Зачем он есть. `discover` из России отдаёт набор endpoint'ов, до которых потом
 * не дозвониться; пригодный набор получается только с адреса вне России. Сборка
 * с релеем узнаёт его через релей, а сборка F-Droid не может: пароль к релею в
 * открытых исходниках перестал бы быть паролем в тот же день. Поэтому набор
 * публикуется отдельно — задача в GitHub Actions спрашивает discover со своего
 * раннера и кладёт `opera_endpoints.json` в тот же репозиторий, откуда
 * приложение уже берёт данные об обновлениях.
 *
 * Лента одинакова для обеих сборок: адреса секретом не являются, а сборке
 * `github` она экономит проход через релей. Приземляется всё в тот же кэш, что и
 * собственный discover (`mergeDiscoveredOperaPinnedEndpoints`), поэтому дальше
 * работает уже проверенный путь через `-override-proxy-address` — нового в
 * датапате нет ничего.
 */
object OperaEndpointFeed {

    /**
     * Два адреса той же ленты, что у обновлений: raw и GitHub Pages. Один и тот
     * же файл в одном репозитории — если один хост недоступен, второй обычно жив.
     */
    private val FEED_URLS = listOf(
        "https://raw.githubusercontent.com/confeden/nova_updates/main/opera_endpoints.json",
        "https://confeden.github.io/nova_updates/opera_endpoints.json",
    )

    /** Пока лента моложе этого возраста, ходить за ней незачем. */
    private const val FRESH_WINDOW_MS = 3L * 60L * 60L * 1000L

    /**
     * Пауза между попытками, когда лента не отвечает. Без неё каждый неудачный
     * запуск Opera платил бы полным таймаутом за заведомо недоступный хост.
     */
    private const val RETRY_AFTER_FAILURE_MS = 15L * 60L * 1000L

    private const val CONNECT_TIMEOUT_MS = 2500
    private const val READ_TIMEOUT_MS = 2500

    /** Общий предел на весь опрос: два адреса подряд не должны стоить больше. */
    private const val TOTAL_BUDGET_MS = 6000L

    /** Регионы, которые понимает `opera-proxy`. Всё прочее из ленты игнорируем. */
    private val KNOWN_REGIONS = setOf("EU", "AM", "AS")

    private val refreshInFlight = AtomicBoolean(false)

    /**
     * Разбирает файл ленты.
     *
     * Формат:
     * ```
     * {"updated_at": "...", "regions": {"EU": ["1.2.3.4:443"], "AM": [...]}}
     * ```
     * Чистая функция без сети и хранилища — на ней держатся тесты.
     */
    fun parseFeed(body: String): Map<String, List<String>> {
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            return emptyMap()
        }
        val regions = root.optJSONObject("regions") ?: return emptyMap()
        val parsed = linkedMapOf<String, List<String>>()
        for (key in regions.keys()) {
            val region = normalizeRegion(key)
            if (region !in KNOWN_REGIONS) continue
            val array = regions.optJSONArray(key) ?: continue
            val endpoints = buildList {
                for (index in 0 until array.length()) {
                    val endpoint = normalizeEndpoint(array.optString(index))
                    if (endpoint.isNotBlank() && endpoint !in this) add(endpoint)
                }
            }
            if (endpoints.isNotEmpty()) parsed[region] = endpoints
        }
        return parsed
    }

    /**
     * Опрос перед запуском Opera.
     *
     * Ждём ленту только когда своего кэша нет: альтернатива в этом случае —
     * discover на десятки секунд, и шесть секунд ленты дешевле. Когда кэш есть,
     * обновляемся фоном, чтобы следующая попытка получила свежий список, а эта
     * не задержалась ни на что.
     */
    fun refreshBeforeLaunch(
        clientData: ClientData,
        hasUsableCache: Boolean,
        logger: (String) -> Unit,
    ) {
        if (!shouldRefresh(clientData, hasUsableCache)) return
        if (hasUsableCache) {
            refreshInBackground(clientData, logger)
        } else {
            refresh(clientData, logger)
        }
    }

    /**
     * Опрос с уже поднятого туннеля — самый надёжный путь: через туннель лента
     * доступна и там, где до GitHub напрямую не достучаться. Вызывается из
     * фонового прогрева, поэтому ждать здесь можно.
     *
     * @return true, если лента ответила и что-то легло в кэш.
     */
    fun refreshIfStale(clientData: ClientData, logger: (String) -> Unit): Boolean {
        if (!shouldRefresh(clientData, hasUsableCache = true)) return false
        return refresh(clientData, logger)
    }

    /**
     * Опрос в отдельном потоке. Вызывается там, где ждать нельзя: перед запуском
     * Opera, когда свой кэш ещё пригоден и задерживать подъём нечем.
     */
    fun refreshInBackground(clientData: ClientData, logger: (String) -> Unit) {
        if (!refreshInFlight.compareAndSet(false, true)) return
        thread(start = true, isDaemon = true, name = "nova-opera-feed") {
            try {
                refresh(clientData, logger)
            } finally {
                refreshInFlight.set(false)
            }
        }
    }

    /**
     * @return true, если лента ответила и хотя бы один регион лёг в кэш.
     *
     * Молчаливого выхода здесь нет намеренно: отключённая или недоступная лента
     * должна быть видна в журнале, иначе «выключено» не отличить от «не
     * сработало» (правило проекта).
     */
    fun refresh(clientData: ClientData, logger: (String) -> Unit): Boolean {
        val startedAt = System.currentTimeMillis()
        clientData.markOperaEndpointFeedAttempt(startedAt)
        var lastError = "нет ответа"
        for (url in FEED_URLS) {
            if (System.currentTimeMillis() - startedAt > TOTAL_BUDGET_MS) {
                logger("Лента адресов Opera: бюджет опроса исчерпан, оставшиеся адреса не пробуем.")
                break
            }
            val body = try {
                fetch(url)
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                continue
            }
            val parsed = parseFeed(body)
            if (parsed.isEmpty()) {
                lastError = "файл получен, но списка адресов в нём нет"
                continue
            }
            val summary = parsed.entries.joinToString("; ") { (region, endpoints) ->
                val merged = clientData.mergeDiscoveredOperaPinnedEndpoints(region, endpoints)
                "$region: лента дала ${endpoints.size}, в кэше стало ${merged.size}"
            }
            clientData.markOperaEndpointFeedSynced(System.currentTimeMillis())
            logger("Лента адресов Opera получена из $url. $summary")
            return true
        }
        logger("Лента адресов Opera недоступна ($lastError). Работаем на своём кэше и discover.")
        return false
    }

    private fun shouldRefresh(clientData: ClientData, hasUsableCache: Boolean): Boolean {
        val nowMs = System.currentTimeMillis()
        val lastAttempt = clientData.getOperaEndpointFeedAttemptAt()
        // Часы устройства могут уехать назад: отметка из будущего означает
        // «неизвестно когда», а не «только что».
        if (lastAttempt in (nowMs - RETRY_AFTER_FAILURE_MS)..nowMs) return false
        if (!hasUsableCache) return true
        val lastSync = clientData.getOperaEndpointFeedSyncedAt()
        return lastSync !in (nowMs - FRESH_WINDOW_MS)..nowMs
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", "NovaAndroid/${BuildConfig.VERSION_NAME}")
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IllegalStateException("HTTP $code")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeRegion(value: String?): String {
        return when (value?.trim()?.uppercase(Locale.US)) {
            "EU" -> "EU"
            "AM", "US" -> "AM"
            "AS" -> "AS"
            else -> ""
        }
    }

    /**
     * Тот же разбор `host:port`, что и в кэше. Повторён здесь намеренно: лента —
     * внешние данные, и отбраковывать мусор нужно до записи в хранилище.
     */
    private fun normalizeEndpoint(value: String?): String {
        val clean = value?.trim().orEmpty()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
            .trim()
        if (clean.isBlank()) return ""
        val host = clean.substringBeforeLast(':').trim().trim('[', ']')
        val port = clean.substringAfterLast(':', "").trim().toIntOrNull() ?: return ""
        if (host.isBlank() || port !in 1..65535) return ""
        val validHost = host.matches(Regex("^[a-zA-Z0-9.:-]+$")) && !host.contains("..")
        return if (validHost) "$host:$port" else ""
    }
}

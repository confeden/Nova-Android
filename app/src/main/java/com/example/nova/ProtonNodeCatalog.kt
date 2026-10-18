package com.example.nova

import android.content.Context
import org.json.JSONObject

/**
 * Встроенный список бесплатных узлов Proton (`proton_nodes.json`).
 *
 * Зачем он есть. Единственный шаг генератора, который из России не проходит, — это
 * `/vpn/logicals`: маленькие вызовы (`/auth/v4/sessions`, `/auth/v4/credentialless`,
 * `/vpn/v1/certificate`) по альтернативному маршруту Proton работают, а список
 * серверов отдаёт 8–21 КБ и подвисает до таймаута на всех пяти узлах (P3). Список,
 * лежащий в прошивке, убирает ровно этот шаг: Proton поднимается сразу после
 * установки, ещё до того как появится хоть какой-то туннель, через который можно
 * сходить за списком.
 *
 * **Ключей и аккаунта здесь нет и быть не должно.** В активе только публичные факты
 * об узле: адрес входа, публичный ключ пира, страна, город, имя. Приватный ключ
 * каждое устройство выводит из своего seed и регистрирует само. Иначе один аккаунт
 * уехал бы в APK всем пользователям сразу, а у бесплатного тарифа `MaxConnect: 2` —
 * подключились бы двое, остальные получили бы отказ. Это же правило, что и P6 для
 * встроенных семян WARP, только здесь оно соблюдено с самого начала.
 *
 * Список стареет между релизами, поэтому он **запасной, а не главный**: живой ответ
 * API всегда в приоритете, а встроенный идёт в дело, когда список не выдали.
 * Собирается `tools/generate_proton_nodes_from_device.py`.
 */
object ProtonNodeCatalog {

    const val ASSET_NAME = "proton_nodes.json"

    @Volatile
    private var cachedBundled: List<ProtonApi.Server>? = null

    /**
     * @return узлы для пула: сначала свежая лента ([ProtonNodeFeed]), затем узлы из
     * прошивки, чьих адресов в ленте нет. Пустой список — это не «узлов нет», а
     * «ни ленты, ни встроенного списка», и звать эту функцию имеет смысл только там,
     * где живой список уже не получен. Ленту читаем при каждом вызове (файл мал), а
     * прошивочный список кэшируем: лента может обновиться посреди сеанса.
     */
    fun load(context: Context): List<ProtonApi.Server> {
        val bundled = loadBundled(context)
        val feed = ProtonNodeFeed.loadCached(context)
        return merge(feed, bundled)
    }

    private fun loadBundled(context: Context): List<ProtonApi.Server> {
        cachedBundled?.let { return it }
        val parsed = runCatching {
            parseNodes(context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() })
        }.getOrElse { error ->
            LogManager.log("Proton: встроенный список узлов не прочитался — ${error.message}")
            emptyList()
        }
        cachedBundled = parsed
        return parsed
    }

    /**
     * Лента первой, затем встроенные узлы, чей адрес в ленте не встречается.
     *
     * Внутри ленты свежее выше: узел, виденный генератором в последнем прогоне, стоит
     * перед тем, что держится из прежних лент до двух недель. Сортировка устойчивая,
     * поэтому раскладка по кругу стран, заданная генератором, у равных сохраняется.
     */
    internal fun merge(
        feed: List<ProtonApi.Server>,
        bundled: List<ProtonApi.Server>,
    ): List<ProtonApi.Server> {
        if (feed.isEmpty()) return bundled
        val feedHosts = feed.map { it.entryIp.trim().lowercase() }.toSet()
        return feed.sortedByDescending { it.lastSeen } + bundled.filter { it.entryIp.trim().lowercase() !in feedHosts }
    }

    /** Разбор того же JSON, что у актива и у ленты; чистая функция, без Context. */
    internal fun parseNodes(raw: String): List<ProtonApi.Server> {
        val nodes = JSONObject(raw).optJSONArray("nodes") ?: return emptyList()
        val out = ArrayList<ProtonApi.Server>(nodes.length())
        for (index in 0 until nodes.length()) {
            val node = nodes.optJSONObject(index) ?: continue
            val entryIp = node.optString("entry_ip").trim()
            val peerKey = node.optString("peer_public_key").trim()
            // Узел без адреса или без ключа пира бесполезен: рукопожатие собрать не из
            // чего. Пропускаем молча только его, а не весь список.
            if (entryIp.isEmpty() || peerKey.isEmpty()) continue
            out.add(
                ProtonApi.Server(
                    name = node.optString("server_name").trim(),
                    country = node.optString("country").trim(),
                    city = node.optString("city").trim(),
                    entryIp = entryIp,
                    peerPublicKey = peerKey,
                    // Нагрузки у встроенного списка нет и быть не может: она живая.
                    // Ставим середину, чтобы порядок задавал замер, а не выдумка.
                    load = 50,
                    score = 0.0,
                    lastSeen = node.optLong("last_seen", 0L).coerceAtLeast(0L),
                )
            )
        }
        return out
    }
}

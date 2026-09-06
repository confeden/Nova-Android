package com.example.nova

/**
 * Единственный источник внешнего адреса и страны выхода.
 *
 * ## Почему не Cloudflare
 *
 * До этого адрес и страну давал `/cdn-cgi/trace` (инвариант I10). Владелец
 * попросил перейти на `whatismyip.help`, и у этого есть измеренная причина:
 * входы Cloudflare опрашивались по **литеральным адресам** `1.1.1.1` / `1.0.0.1`,
 * а эти же адреса стояли открытыми правилами в умолчаниях DNS и получали вырез
 * маршрута (`excludeRoute`). Вырез сильнее любой пометки сокета, поэтому замер
 * уходил мимо туннеля и честно сообщал адрес провайдера — на живой сессии Proton
 * бейдж показывал российский адрес и «RU», хотя узел был польский. Утечка
 * закрыта отдельно (открытых правил в умолчаниях больше нет), но и источник
 * теперь один и по имени, а не по литералу.
 *
 * ## Формат ответа
 *
 * `GET https://whatismyip.help/txt` отдаёт **одну строку**, семь полей через `|`,
 * без завершающего перевода строки. Проверено 2026-09-05:
 *
 * ```
 * 85.174.181.85|ipv4|12389|RU|HTTP/1.1|curl/8.19.0|
 * ```
 *
 * 1. адрес, 2. `ipv4`/`ipv6`, 3. номер AS, 4. **код страны**, 5. версия HTTP,
 * 6. эхо `User-Agent`, 7. эхо `Accept-Language`.
 *
 * **Индексы считаются только от начала.** Последние два поля — неэкранированное
 * эхо клиентских заголовков: длина ответа зависит от того, что мы сами послали, и
 * «последнее поле» смысла не имеет.
 *
 * ## Поддомены `ipv4.` и `ipv6.` не работают — измерено 2026-09-06
 *
 * Оба имени **резолвятся** (`ipv4.whatismyip.help` и сам домен дают один и тот же
 * `5.253.19.245`, у `ipv6.` есть AAAA), и отсюда взялось предположение, что они
 * отдают то же самое для своего семейства. Они отдают **404**:
 *
 * ```
 * $ curl -o /dev/null -w '%{http_code}' https://ipv4.whatismyip.help/txt
 * 404
 * {"name":"Not Found","message":"Page not found.","code":0,"status":404}
 * ```
 *
 * Путь `/txt` есть только у самого домена. Экран спрашивал ровно эти два
 * поддомена и никогда — сам домен, поэтому `parse` отвергал страницу ошибки, а в
 * журнале стояло «трасса не дошла» на **любом** транспорте. Семейство теперь
 * берётся из поля 2 ответа, а адрес приходит один — тот, которым стек и вышел.
 *
 * ## Запасной источник и почему он не нарушает I10
 *
 * `whatismyip.help` бывает недостижим **с самого выхода**: через живой туннель
 * MASQUE (выход Cloudflare, `warp=on`, `loc=RU`) `ping` до него доходит за 92 мс,
 * а TCP на 443 и на 80 не устанавливается вовсе — измерено 2026-09-06. Тогда
 * экран остаётся без адреса и без страны совсем, и это то, на что пожаловался
 * владелец.
 *
 * Поэтому источников два, и порядок между ними жёсткий: сначала выбор владельца,
 * и только если он промолчал — [URL_FALLBACK]. Инвариант I10 не про число
 * источников, а про то, что адрес и страна обязаны приходить **одним** ответом;
 * оба источника это условие выполняют, смешивать их поля нельзя и код этого не
 * делает. Какой источник ответил, пишется в журнал — молча подменять источник
 * нельзя (I4).
 *
 * ## Что эти источники **не** дают
 *
 * Узла Cloudflare (`colo`). Его продолжает давать `/cdn-cgi/trace`, и он нужен
 * ровно одной настройке — обходу нежелательного узла. Спрашивается он только
 * когда эта настройка включена, отдельным запросом и только за `colo=`: адрес и
 * страна по-прежнему приходят **одним** ответом из одного места, что и было
 * настоящим содержанием I10.
 */
object ExitAddress {

    /** Выбор владельца (D20). Семейство выберет стек, поле 2 о нём и сообщит. */
    const val URL_ANY = "https://whatismyip.help/txt"

    /**
     * Запасной вход — только когда основной молчит.
     *
     * По имени, а не по литеральному адресу: именно литералы `1.1.1.1`/`1.0.0.1`
     * когда-то получали вырез маршрута из умолчаний DNS и уводили замер мимо
     * туннеля. У имени `www.cloudflare.com` этой истории нет.
     */
    const val URL_FALLBACK = "https://www.cloudflare.com/cdn-cgi/trace"

    /** Порядок опроса: основной первым, ответ основного всегда сильнее. */
    val URLS: List<String> = listOf(URL_ANY, URL_FALLBACK)

    /** Разобранный ответ. */
    data class Observation(val ip: String, val ipVersion: String, val country: String)

    /**
     * Разбирает тело ответа тем разбором, который этому входу и положен.
     *
     * Разбор выбирается по адресу входа, а не угадывается по форме тела: у двух
     * источников форматы разные, и «попробовать оба разбора» означало бы принять
     * страницу ошибки одного за ответ другого.
     */
    fun observe(url: String, body: String?): Observation? =
        if (url == URL_FALLBACK) parseTrace(body) else parse(body)

    /**
     * Разбирает ответ `/cdn-cgi/trace`: строки вида `ключ=значение`.
     *
     * Берутся ровно два поля одного ответа — `ip=` и `loc=`. `colo=` здесь есть,
     * но он остаётся отдельным вопросом отдельной настройки (D20).
     */
    fun parseTrace(body: String?): Observation? {
        if (body.isNullOrBlank()) return null
        var ip = ""
        var country = ""
        body.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("ip=") -> ip = line.removePrefix("ip=").trim()
                line.startsWith("loc=") -> country = line.removePrefix("loc=").trim().uppercase()
            }
        }
        if (ip.isEmpty() || !looksLikeAddress(ip)) return null
        val validCountry = country.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }.orEmpty()
        return Observation(
            ip = ip,
            ipVersion = if (isIpv4(ip)) "ipv4" else "ipv6",
            country = validCountry,
        )
    }

    /**
     * Разбирает тело ответа.
     *
     * @return null, если адреса в ответе нет — тогда это не ответ, а страница
     *         ошибки или заглушка антибота.
     */
    fun parse(body: String?): Observation? {
        val line = body?.trim()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (line.isEmpty()) return null
        val parts = line.split('|')
        val ip = parts.getOrNull(0)?.trim().orEmpty()
        if (ip.isEmpty() || !looksLikeAddress(ip)) return null
        val version = parts.getOrNull(1)?.trim()?.lowercase().orEmpty()
        val country = parts.getOrNull(3)?.trim()?.uppercase().orEmpty()
            .takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } }
            .orEmpty()
        return Observation(ip = ip, ipVersion = version, country = country)
    }

    /** Похоже ли это на адрес вообще. Заглушки антибота — это HTML, а не адрес. */
    private fun looksLikeAddress(value: String): Boolean {
        if (value.any { it.isWhitespace() || it == '<' || it == '>' }) return false
        val ipv4 = value.count { it == '.' } == 3 &&
            value.split('.').all { part -> part.isNotEmpty() && part.all(Char::isDigit) }
        val ipv6 = value.contains(':') && value.all { it.isDigit() || it in "abcdefABCDEF:." }
        return ipv4 || ipv6
    }

    /** IPv4 ли это. Тот же вопрос задают оба экрана, и ответ обязан быть один. */
    fun isIpv4(value: String): Boolean =
        value.count { it == '.' } == 3 && !value.contains(':')
}

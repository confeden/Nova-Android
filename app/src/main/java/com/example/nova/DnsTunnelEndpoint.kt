package com.example.nova

import org.json.JSONArray
import org.json.JSONObject

/**
 * Точка входа DNS-туннеля — зона, ключ и ручки прикрытия.
 *
 * Сервера у нас под этот транспорт нет и не будет (D34): зону и открытый ключ
 * приносит пользователь, ровно как в импорте AWG/VLESS. Причина механическая, а
 * не принципиальная: ни один DNS-туннель не работает без авторитативного NS,
 * через который идёт **весь** его трафик, поэтому релей такой зоной быть не
 * может, оставаясь control plane.
 *
 * @param zone делегированная зона туннеля, например `t.example.com`.
 * @param pubKey открытый ключ сервера, шестнадцатеричной строкой.
 * @param dnsttCompat формат исходного dnstt. Включён по умолчанию: чужие точки
 *        выхода почти все именно dnstt, а не vaydns.
 * @param maxQnameLen предел длины QNAME. Ноль — как у библиотеки.
 * @param rps предел частоты запросов; ноль — без предела.
 * @param recordType тип записи для обратного потока (`txt`, `cname`, `a`, …).
 * @param extraResolvers несущие, дописанные пользователем, в его порядке.
 */
data class DnsTunnelEndpoint(
    val zone: String = "",
    val pubKey: String = "",
    val dnsttCompat: Boolean = true,
    val maxQnameLen: Int = 0,
    val rps: Double = 0.0,
    val recordType: String = "",
    val extraResolvers: List<DnsTunnelResolver> = emptyList(),
) {
    /** Заполнена ли точка входа настолько, чтобы пробовать подключаться. */
    val isUsable: Boolean get() = zone.isNotBlank() && pubKey.isNotBlank()
}

/**
 * Одна несущая: чем спрашиваем и кого.
 *
 * `dot` сюда не попадает намеренно — ядро его отвергает вслух. Библиотека
 * пропускает наш `DialerControl` только в ветку UDP, а сокет DoT открывает сама,
 * и внутри VpnService он ушёл бы в тот самый туннель, который мы поднимаем.
 * Это G154 и G200, за которые здесь уже дважды заплачено.
 */
data class DnsTunnelResolver(val type: String, val addr: String) {
    val isUsable: Boolean
        get() = addr.isNotBlank() && (type == TYPE_UDP || type == TYPE_DOH)

    companion object {
        const val TYPE_UDP = "udp"
        const val TYPE_DOH = "doh"
    }
}

/**
 * Порядок несущих и сборка конфигурации для ядра.
 *
 * Объект чистый: ни `Context`, ни ввода-вывода — порядок решает тест, а не
 * наблюдение на устройстве.
 */
object DnsTunnelConfig {

    /**
     * Национальный резолвер. Ставится в перебор **всегда**, и не из вежливости.
     *
     * Под «белыми списками» он доступен по построению — без него не работает
     * ничего, — и замером с российской SIM 2026-09-20 подтверждено, что он сам
     * ходит на произвольный зарубежный авторитативный сервер и объявляет
     * EDNS0 1232 (`kb/dns-tunnel-and-tor.md`). То есть это единственная несущая,
     * про которую заранее известно, что оператор её не отрежет.
     */
    const val NATIONAL_RESOLVER: String = "195.208.5.1"

    /**
     * Второй адрес НСДИ. Принесён владельцем вместе с профилем `stormdns://`
     * 2026-09-20; **нашим замером не подтверждён** — в S86 проверялся только
     * первый. Ставится рядом с ним потому, что цена лишней несущей — секунды
     * перебора, а цена недостающей — не подключились вовсе.
     */
    const val NATIONAL_RESOLVER_SECOND: String = "195.208.4.1"

    /**
     * Публичные резолверы — **хвостом**, последними в переборе.
     *
     * Под белым списком они бесполезны по построению: оператор режет их
     * первыми, и потому они не могут стоять впереди операторских. Но белый
     * список — не единственная сеть, где человек включит этот режим, а на
     * обычной сети выбор несущей решает всё: замер 2026-09-20 по чужой точке
     * выхода `de1.iran.qzz.io` дал ровно одну рабочую несущую из семи, и это
     * был `8.8.8.8`, тогда как четыре российских рекурсора и домашний роутер не
     * дали ни байта. Хвост ничего не стоит там, где не нужен, и решает там, где
     * нужен.
     *
     * Состав расширен замером на МегаФоне LTE 2026-09-29 (наш сервер): почти
     * каждый путь там режется оператором до ~6,4 КБ/с, а Quad9 и `77.88.8.88`
     * под это не попали — 50 и 37 КБ/с. Пути складываются (все вместе 51 КБ/с),
     * так что лишний адрес — ещё одна полоса; слабые по MTU движок отсеет сам
     * (`tools/stormdns/nova.patch`, отбор после перебора MTU).
     */
    val PUBLIC_FALLBACK_RESOLVERS: List<String> = listOf(
        "9.9.9.9", "149.112.112.112",
        "77.88.8.88", "77.88.8.2", "77.88.8.8", "77.88.8.1",
        "8.8.8.8", "8.8.4.4",
        "1.1.1.1", "1.0.0.1",
    )

    /**
     * Собирает порядок несущих: сначала резолверы самой сети, потом НСДИ, потом
     * дописанные пользователем.
     *
     * Резолверы сети идут первыми по двум причинам сразу. Под белым списком они
     * whitelisted по определению — это и есть весь смысл затеи. А на обычной
     * сети они просто ближе: замер на МегаФоне дал 21-28 мс против 58-74 мс до
     * `8.8.8.8`, а частота запросов — то, во что упирается пропускная
     * способность канала.
     *
     * Повторы убираются по паре «тип + адрес»: одна и та же несущая, пройденная
     * дважды, — это вдвое больше времени на перебор и ни одного нового исхода.
     */
    fun resolverOrder(
        systemResolvers: List<String>,
        endpoint: DnsTunnelEndpoint,
    ): List<DnsTunnelResolver> {
        val ordered = ArrayList<DnsTunnelResolver>()
        systemResolvers.forEach { addr ->
            val trimmed = addr.trim()
            if (trimmed.isNotEmpty()) {
                ordered.add(DnsTunnelResolver(DnsTunnelResolver.TYPE_UDP, withDefaultPort(trimmed)))
            }
        }
        ordered.add(DnsTunnelResolver(DnsTunnelResolver.TYPE_UDP, withDefaultPort(NATIONAL_RESOLVER)))
        ordered.add(DnsTunnelResolver(DnsTunnelResolver.TYPE_UDP, withDefaultPort(NATIONAL_RESOLVER_SECOND)))
        endpoint.extraResolvers.forEach { resolver ->
            val trimmed = resolver.addr.trim()
            if (trimmed.isNotEmpty()) {
                val addr = if (resolver.type == DnsTunnelResolver.TYPE_UDP) withDefaultPort(trimmed) else trimmed
                ordered.add(DnsTunnelResolver(resolver.type, addr))
            }
        }
        PUBLIC_FALLBACK_RESOLVERS.forEach { addr ->
            ordered.add(DnsTunnelResolver(DnsTunnelResolver.TYPE_UDP, withDefaultPort(addr)))
        }
        val seen = HashSet<String>()
        return ordered.filter { it.isUsable && seen.add(it.type + "|" + it.addr) }
    }

    /**
     * Дописывает `:53` адресу без порта.
     *
     * Резолверы приходят из `LinkProperties` голыми адресами, а ядро ждёт
     * `host:port`. У IPv6 двоеточий много, поэтому порт распознаётся только в
     * скобочной записи `[::1]:53`.
     */
    fun withDefaultPort(addr: String): String {
        val trimmed = addr.trim()
        if (trimmed.isEmpty()) return trimmed
        if (trimmed.startsWith("[")) {
            return if (trimmed.contains("]:")) trimmed else "$trimmed:53"
        }
        if (trimmed.count { it == ':' } > 1) return "[$trimmed]:53"
        return if (trimmed.contains(':')) trimmed else "$trimmed:53"
    }

    /** Конфигурация в том виде, в каком её разбирает ядро. */
    fun toCoreJson(
        endpoint: DnsTunnelEndpoint,
        resolvers: List<DnsTunnelResolver>,
        probeTimeoutMs: Int,
    ): String {
        val array = JSONArray()
        resolvers.forEach { resolver ->
            array.put(JSONObject().put("type", resolver.type).put("addr", resolver.addr))
        }
        return JSONObject()
            .put("zone", endpoint.zone)
            .put("pubkey", endpoint.pubKey)
            .put("dnsttCompat", endpoint.dnsttCompat)
            .put("maxQnameLen", endpoint.maxQnameLen)
            .put("rps", endpoint.rps)
            .put("recordType", endpoint.recordType)
            .put("probeTimeoutMs", probeTimeoutMs)
            .put("resolvers", array)
            .toString()
    }
}

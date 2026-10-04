package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты раздачи запасных портов [ProtonProfileStore.expandPortFallbacks].
 *
 * Что здесь закрепляется и почему. Порт назначался узлу один и навсегда, и узел с
 * заглушённым у оператора портом выбывал целиком, хотя живёт не хуже соседнего.
 * Замер этого не видит: он идёт по TCP на 443 и о судьбе UDP-порта не говорит
 * ничего. Отсюда свойства, каждое из которых легко потерять правкой: запас
 * достаётся голове **каждой страны** (а не первым узлам списка — они все NL),
 * слоты под него резервируются до раздачи одиночных, хвост остаётся широким, а
 * варианты одного адреса стоят подряд — очередь подключения сортирует по общей у
 * них задержке и рассчитывает на это.
 */
class ProtonPortFallbackTest {

    private fun profile(
        host: String,
        port: Int,
        country: String = "NL",
        ping: Int = 50,
    ): ProtonProfile = ProtonProfile(
        serverName = "node-$host",
        country = country,
        city = "City",
        entryIp = host,
        port = port,
        peerPublicKey = "key==",
        pingMs = ping,
        pingSource = ProtonLatency.SOURCE_TCP,
        load = 10,
        sni = "",
        junkCount = 3,
        junkMin = 1,
        junkMax = 3,
        i1 = "",
        createdAt = 1000L,
    )

    /** Узлы одной страны, по одному на адрес, порты по кругу — как их раздаёт генератор. */
    private fun servers(count: Int, country: String = "NL", from: Int = 0): List<ProtonProfile> =
        (from until from + count).map { index ->
            profile("10.0.0.$index", ProtonProfileStore.PORTS[index % ProtonProfileStore.PORTS.size], country)
        }

    private fun portsByHost(list: List<ProtonProfile>): Map<String, List<Int>> =
        list.groupBy { it.entryIp }.mapValues { (_, v) -> v.map { it.port } }

    @Test
    fun `two nearest servers of every country get spare ports`() {
        // Перекос как на живом замере: NL забивает всю голову списка.
        val input = servers(30, "NL") + servers(10, "US", from = 30) + servers(8, "CA", from = 40)

        val expanded = ProtonProfileStore.expandPortFallbacks(input, limit = 50)
        val ports = portsByHost(expanded)

        listOf("10.0.0.0", "10.0.0.1", "10.0.0.30", "10.0.0.31", "10.0.0.40", "10.0.0.41")
            .forEach { host ->
                assertEquals(
                    "у головы своей страны ($host) должно быть " +
                        "${ProtonProfileStore.PORTS_PER_FALLBACK_SERVER} порта, а не ${ports[host]}",
                    ProtonProfileStore.PORTS_PER_FALLBACK_SERVER,
                    ports[host]?.size,
                )
            }
        assertEquals("третий узел страны остаётся с одним портом", 1, ports["10.0.0.2"]?.size)
    }

    @Test
    fun `a far country still gets its spare ports when the near one could eat the budget`() {
        // Ровно тот случай, ради которого слоты резервируются заранее: NL стоит
        // первым и в один проход выбрал бы весь предел до того, как дойдёт до US.
        val input = servers(48, "NL") + servers(4, "US", from = 48)

        val expanded = ProtonProfileStore.expandPortFallbacks(input, limit = 50)
        val ports = portsByHost(expanded)

        assertEquals(3, ports["10.0.0.48"]?.size)
        assertEquals(3, ports["10.0.0.49"]?.size)
        assertTrue("предел соблюдён", expanded.size <= 50)
    }

    @Test
    fun `variants of one server stay adjacent and the order of servers is kept`() {
        val expanded = ProtonProfileStore.expandPortFallbacks(servers(30), limit = 50)

        val runs = expanded.map { it.entryIp }.fold(mutableListOf<String>()) { acc, host ->
            if (acc.lastOrNull() != host) acc += host
            acc
        }
        assertEquals("варианты одного адреса обязаны идти подряд", runs.distinct(), runs)
        assertEquals("10.0.0.0", runs.first())
        assertEquals("10.0.0.1", runs[1])
    }

    @Test
    fun `each server gets distinct ports from the list Proton actually serves`() {
        val expanded = ProtonProfileStore.expandPortFallbacks(servers(12), limit = 50)

        portsByHost(expanded).forEach { (host, ports) ->
            assertEquals("порты узла $host обязаны быть разными", ports.size, ports.toSet().size)
            assertTrue(
                "порт узла $host обязан быть из списка Proton: $ports",
                ports.all { it in ProtonProfileStore.PORTS },
            )
        }
    }

    @Test
    fun `the assigned port stays the first attempt for its server`() {
        val expanded = ProtonProfileStore.expandPortFallbacks(servers(3), limit = 50)

        assertEquals(ProtonProfileStore.PORTS[0], expanded.first { it.entryIp == "10.0.0.0" }.port)
        assertEquals(ProtonProfileStore.PORTS[1], expanded.first { it.entryIp == "10.0.0.1" }.port)
    }

    @Test
    fun `the limit is never exceeded and port sets are never cut in half`() {
        val expanded = ProtonProfileStore.expandPortFallbacks(servers(30), limit = 32)

        assertTrue("предел соблюдён: ${expanded.size}", expanded.size <= 32)
        val counts = portsByHost(expanded).mapValues { it.value.size }
        assertTrue(
            "обрезанных наборов портов быть не должно: $counts",
            counts.values.all { it == 1 || it == ProtonProfileStore.PORTS_PER_FALLBACK_SERVER },
        )
    }

    @Test
    fun `a limit too small for a spare set still yields single port servers`() {
        // Запас не влезает — но пустой список был бы «Proton не готов» там, где
        // готовы два рабочих узла.
        val expanded = ProtonProfileStore.expandPortFallbacks(servers(5), limit = 2)

        assertEquals(2, expanded.size)
        assertTrue(expanded.all { portsByHost(expanded).getValue(it.entryIp).size == 1 })
    }

    @Test
    fun `duplicate host and port pairs are not emitted twice`() {
        val input = listOf(
            profile("10.0.0.1", ProtonProfileStore.PORTS[0]),
            profile("10.0.0.1", ProtonProfileStore.PORTS[1]),
        )

        val expanded = ProtonProfileStore.expandPortFallbacks(input, limit = 50)

        val keys = expanded.map { "${it.entryIp}:${it.port}" }
        assertEquals("повторов host:port быть не должно", keys.size, keys.toSet().size)
    }

    @Test
    fun `empty input and non positive limit are handled`() {
        assertTrue(ProtonProfileStore.expandPortFallbacks(emptyList(), limit = 50).isEmpty())
        assertTrue(ProtonProfileStore.expandPortFallbacks(servers(3), limit = 0).isEmpty())
    }
    @Test
    fun `single slots go round robin across countries`() {
        // NL заполняет голову по задержке; NO из четырёх узлов стоит в хвосте.
        // Раньше остаток бюджета уходил NL подряд, и третий-четвёртый узел NO
        // в список не попадали (Pixel 4a, 2026-10-04).
        val input = servers(40, "NL") + servers(4, "NO", from = 40)

        val expanded = ProtonProfileStore.expandPortFallbacks(input, limit = 20)

        val noHosts = expanded.filter { it.country == "NO" }.map { it.entryIp }.toSet()
        assertEquals("все четыре узла NO в списке", 4, noHosts.size)
        assertEquals(20, expanded.size)
    }

    @Test
    fun `candidates are picked round robin by country`() {
        fun server(ip: String, country: String, load: Int) =
            ProtonApi.Server("n-$ip", country, "c", ip, "k=", load, 0.0)
        val ordered = (0 until 40).map { server("10.0.0.$it", "NL", it) } +
            (0 until 3).map { server("10.1.0.$it", "NO", 90 + it) }

        val picked = ProtonProfileStore.pickCandidatesByCountry(ordered, limit = 10)

        assertEquals(10, picked.size)
        assertEquals(3, picked.count { it.country == "NO" })
        // Порядок внутри страны сохраняется.
        assertEquals(listOf("10.1.0.0", "10.1.0.1", "10.1.0.2"), picked.filter { it.country == "NO" }.map { it.entryIp })
    }

    @Test
    fun `node pools merge proven first without duplicate hosts`() {
        fun server(ip: String, load: Int, key: String = "k=") =
            ProtonApi.Server("n-$ip", "NO", "c", ip, key, load, 0.0)
        val proven = listOf(server("95.173.205.163", 40, key = "old="), server("1.1.1.1", 10))
        val live = listOf(server("1.1.1.1", 70, key = "fresh="), server("95.173.205.165", 20))
        val bundled = listOf(server("95.173.205.161", 50), server("95.173.205.165", 50))

        val pool = ProtonProfileStore.mergeNodePools(proven, live, bundled)

        assertEquals(
            listOf("95.173.205.163", "1.1.1.1", "95.173.205.165", "95.173.205.161"),
            pool.map { it.entryIp },
        )
        // Проверенный узел из живого списка берётся в живом виде.
        assertEquals("fresh=", pool[1].peerPublicKey)
    }

    @Test
    fun `succeeded hosts are read from every network class`() {
        val raw = """{"wifi":{"failed_at":{"9.9.9.9:443":1},"succeeded_at":{"95.173.205.163:4569":2}},""" +
            """"cell":{"succeeded_at":{"[2a07::1]:443":3}}}"""

        assertEquals(setOf("95.173.205.163", "2a07::1"), ProtonProfileStore.decodeSucceededHosts(raw))
        assertTrue(ProtonProfileStore.decodeSucceededHosts("{broken").isEmpty())
    }
}

package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Тесты порядка очереди Proton [ProtonProfileStore.connectOrder] и устаревания списка
 * узлов [ProtonProfileStore.nodesListStale].
 *
 * Что закрепляется. Очередь бралась одним порядком списка, и каждая волна подключения
 * пробовала те же восемь верхних профилей — мёртвая голова списка не давала дойти до
 * живых узлов никогда (Pixel 4a и Mi A1, 2026-09-13). Отсюда свойства: молчавшая пара
 * уходит в хвост, волна берёт следующие адреса, подключавшаяся пара идёт первой, адреса
 * перебираются вширь, а отказ со временем забывается.
 */
class ProtonConnectOrderTest {

    private val now = 10_000_000_000L

    private fun profile(host: String, port: Int): ProtonProfile = ProtonProfile(
        serverName = "node-$host",
        country = "NL",
        city = "Amsterdam",
        entryIp = host,
        port = port,
        peerPublicKey = "key==",
        pingMs = 60,
        pingSource = ProtonLatency.SOURCE_TCP,
        load = 10,
        sni = "",
        junkCount = 3,
        junkMin = 1,
        junkMax = 3,
        i1 = "",
        createdAt = 1000L,
    )

    private fun key(host: String, port: Int) = ProtonProfileStore.endpointKey(host, port)

    /** Пары в порядке рангов — так их возьмёт очередь. */
    private fun order(
        profiles: List<ProtonProfile>,
        failed: Map<String, Long> = emptyMap(),
        succeeded: Map<String, Long> = emptyMap(),
    ): List<String> {
        val ranks = ProtonProfileStore.connectOrder(
            profiles,
            ProtonProfileStore.AttemptOutcomes(failed, succeeded),
            now,
        ).ranks
        return ranks.entries.sortedBy { it.value }.map { it.key }
    }

    // Голова списка — один узел на трёх портах, как её раскладывает expandPortFallbacks.
    private val list = listOf(
        profile("10.0.0.1", 4500),
        profile("10.0.0.1", 1194),
        profile("10.0.0.1", 5060),
        profile("10.0.0.2", 51820),
        profile("10.0.0.3", 443),
        profile("10.0.0.3", 88),
    )

    @Test
    fun `without history every address gets its first port before any spare port`() {
        assertEquals(
            listOf(
                key("10.0.0.1", 4500),
                key("10.0.0.2", 51820),
                key("10.0.0.3", 443),
                key("10.0.0.1", 1194),
                key("10.0.0.3", 88),
                key("10.0.0.1", 5060),
            ),
            order(list),
        )
    }

    @Test
    fun `silent pair goes behind untried addresses and its spare ports behind them too`() {
        val result = order(list, failed = mapOf(key("10.0.0.1", 4500) to now - 60_000L))

        // Сначала адреса без единого отказа — вширь.
        assertEquals(
            listOf(key("10.0.0.2", 51820), key("10.0.0.3", 443), key("10.0.0.3", 88)),
            result.take(3),
        )
        // Потом непробованные порты молчавшего адреса: запасной порт не пропадает.
        assertEquals(listOf(key("10.0.0.1", 1194), key("10.0.0.1", 5060)), result.subList(3, 5))
        // И сама молчавшая пара — последней.
        assertEquals(key("10.0.0.1", 4500), result.last())
    }

    @Test
    fun `waves walk the whole list instead of returning to the same head`() {
        // Шесть адресов по одному порту, волна — по два.
        val flat = (1..6).map { profile("10.0.1.$it", 443) }
        val failed = HashMap<String, Long>()
        val tried = ArrayList<String>()
        var clock = now - 3_600_000L
        repeat(3) {
            val wave = order(flat, failed = failed).take(2)
            wave.forEach { pair ->
                tried += pair
                clock += 6_000L
                failed[pair] = clock
            }
        }
        assertEquals(flat.map { key(it.entryIp, it.port) }.toSet(), tried.toSet())
        assertEquals(6, tried.size)

        // Когда молчали все, первым снова идёт отказавший раньше всех.
        assertEquals(key("10.0.1.1", 443), order(flat, failed = failed).first())
    }

    @Test
    fun `pair that connected goes first even from the tail of the list`() {
        val result = order(list, succeeded = mapOf(key("10.0.0.3", 88) to now - 5_000L))
        assertEquals(key("10.0.0.3", 88), result.first())
    }

    @Test
    fun `most recent success wins among several`() {
        val result = order(
            list,
            succeeded = mapOf(
                key("10.0.0.2", 51820) to now - 90_000L,
                key("10.0.0.3", 443) to now - 10_000L,
            ),
        )
        assertEquals(listOf(key("10.0.0.3", 443), key("10.0.0.2", 51820)), result.take(2))
    }

    @Test
    fun `success older than the last failure of the same pair does not promote it`() {
        val pair = key("10.0.0.2", 51820)
        val result = order(
            list,
            failed = mapOf(pair to now - 10_000L),
            succeeded = mapOf(pair to now - 50_000L),
        )
        assertEquals(pair, result.last())
    }

    @Test
    fun `outcome survives a list refresh that moved the node to another port`() {
        // Прошлый список: 10.0.0.3 подключался на 1224, 10.0.0.1 молчал на 9999. В новом
        // списке у обоих другие порты — порт раздаётся по месту в списке нагрузки.
        val result = order(
            list,
            failed = mapOf(key("10.0.0.1", 9999) to now - 30_000L),
            succeeded = mapOf(key("10.0.0.3", 1224) to now - 60_000L),
        )
        assertEquals(
            listOf(
                // Рабочий адрес — первым, одной парой: первый порт без своего отказа.
                key("10.0.0.3", 443),
                // Непроверенный адрес и второй порт рабочего — вширь.
                key("10.0.0.2", 51820),
                key("10.0.0.3", 88),
                // Молчавший адрес — после всех, хоть его нынешние порты и не пробовали.
                key("10.0.0.1", 4500),
                key("10.0.0.1", 1194),
                key("10.0.0.1", 5060),
            ),
            result,
        )
    }

    @Test
    fun `a failure newer than the success takes the address out of the first tier`() {
        val result = order(
            list,
            failed = mapOf(key("10.0.0.3", 88) to now - 5_000L),
            succeeded = mapOf(key("10.0.0.3", 443) to now - 60_000L),
        )
        assertEquals(key("10.0.0.1", 4500), result.first())
        assertEquals(key("10.0.0.3", 88), result.last())
    }

    @Test
    fun `network classes keep separate outcomes`() {
        val wifiKey = key("10.0.0.1", 4500)
        var raw: String? = null
        raw = ProtonProfileStore.encodeAttemptOutcome(raw, "cell", wifiKey, success = false, atMs = now)
        raw = ProtonProfileStore.encodeAttemptOutcome(raw, "wifi", wifiKey, success = true, atMs = now + 1)

        val cell = ProtonProfileStore.decodeAttemptOutcomes(raw, "cell")
        val wifi = ProtonProfileStore.decodeAttemptOutcomes(raw, "wifi")
        assertEquals(mapOf(wifiKey to now), cell.failedAtByEndpoint)
        assertTrue(cell.succeededAtByEndpoint.isEmpty())
        assertTrue(wifi.failedAtByEndpoint.isEmpty())
        assertEquals(mapOf(wifiKey to now + 1), wifi.succeededAtByEndpoint)
    }

    @Test
    fun `success clears the failure of the same pair only`() {
        val a = key("10.0.0.1", 4500)
        val b = key("10.0.0.2", 51820)
        var raw: String? = null
        raw = ProtonProfileStore.encodeAttemptOutcome(raw, "wifi", a, success = false, atMs = now)
        raw = ProtonProfileStore.encodeAttemptOutcome(raw, "wifi", b, success = false, atMs = now + 1)
        raw = ProtonProfileStore.encodeAttemptOutcome(raw, "wifi", a, success = true, atMs = now + 2)

        val outcomes = ProtonProfileStore.decodeAttemptOutcomes(raw, "wifi")
        assertEquals(mapOf(b to now + 1), outcomes.failedAtByEndpoint)
        assertEquals(mapOf(a to now + 2), outcomes.succeededAtByEndpoint)
    }

    @Test
    fun `memory keeps only the newest marks and survives a torn file`() {
        var raw: String? = "{\"wifi\": {\"failed_at\": {\"10.0.0.1:4500\""
        repeat(300) { i ->
            raw = ProtonProfileStore.encodeAttemptOutcome(raw, "wifi", key("10.0.$i.1", 443), false, now + i)
        }
        val failed = ProtonProfileStore.decodeAttemptOutcomes(raw, "wifi").failedAtByEndpoint
        assertTrue(failed.size <= 256)
        assertTrue(key("10.0.299.1", 443) in failed)
        assertFalse(key("10.0.0.1", 443) in failed)
        assertEquals(ProtonProfileStore.AttemptOutcomes.EMPTY, ProtonProfileStore.decodeAttemptOutcomes("{oops", "wifi"))
    }

    @Test
    fun `unknown network class shares one bucket`() {
        assertEquals("any", ProtonProfileStore.outcomeNetworkClass(null))
        assertEquals("any", ProtonProfileStore.outcomeNetworkClass("  "))
        assertEquals("wifi", ProtonProfileStore.outcomeNetworkClass(" WiFi "))
    }

    @Test
    fun `counts follow the same tiers as the ranks`() {
        val order = ProtonProfileStore.connectOrder(
            list,
            ProtonProfileStore.AttemptOutcomes(
                failedAtByEndpoint = mapOf(
                    key("10.0.0.1", 4500) to now - 1_000L,
                    // Отказ за окном памяти не считается.
                    key("10.0.0.1", 1194) to now - ProtonProfileStore.FAILURE_MEMORY_MS - 1L,
                    // Пары нет в списке — не считается тоже.
                    key("10.9.9.9", 443) to now - 1_000L,
                ),
                succeededAtByEndpoint = mapOf(key("10.0.0.3", 443) to now - 2_000L),
            ),
            now,
        )
        assertEquals(1, order.provenCount)
        assertEquals(1, order.silentCount)
    }

    @Test
    fun `failure is forgotten after the memory window`() {
        val stale = now - ProtonProfileStore.FAILURE_MEMORY_MS - 1L
        assertEquals(order(list), order(list, failed = mapOf(key("10.0.0.1", 4500) to stale)))
    }

    @Test
    fun `duplicate pairs and blank hosts do not break ranks`() {
        val ranks = ProtonProfileStore.connectOrder(
            list + profile("10.0.0.2", 51820) + profile("  ", 443),
            ProtonProfileStore.AttemptOutcomes.EMPTY,
            now,
        ).ranks
        assertEquals(6, ranks.size)
        assertEquals((0 until 6).toSet(), ranks.values.toSet())
    }

    @Test
    fun `endpoint key ignores brackets case and whitespace`() {
        assertEquals(key("2a07:b944::2", 51820), ProtonProfileStore.endpointKey(" [2A07:B944::2] ", 51820))
    }

    @Test
    fun `live list goes stale after a day and bundled one after six hours`() {
        val live = ProtonProfileStore.NODES_LIVE
        val bundled = ProtonProfileStore.NODES_BUNDLED
        val hour = 3_600_000L

        assertFalse(ProtonProfileStore.nodesListStale(live, now - 23 * hour, now))
        assertTrue(ProtonProfileStore.nodesListStale(live, now - 25 * hour, now))
        assertFalse(ProtonProfileStore.nodesListStale(bundled, now - 5 * hour, now))
        assertTrue(ProtonProfileStore.nodesListStale(bundled, now - 7 * hour, now))
        // Попыток ещё не было — идти за списком пора.
        assertTrue(ProtonProfileStore.nodesListStale("", 0L, now))
    }
}

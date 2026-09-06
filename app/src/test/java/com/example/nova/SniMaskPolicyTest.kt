package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SniMaskPolicyTest {

    private val pools = SniMaskPolicy.Pools(
        white = listOf("www.gosuslugi.ru", "zakupki.gov.ru"),
        russia = listOf("vk.com", "ya.ru", "avito.ru"),
        global = listOf("www.google.com", "www.microsoft.com"),
    )

    private fun inputs(
        mode: String = SniMaskPolicy.MODE_AUTO,
        regime: SniMaskPolicy.Regime = SniMaskPolicy.Regime.UNKNOWN,
        attempt: Int = 0,
        blocked: Set<String> = emptySet(),
        custom: List<String> = emptyList(),
        seed: Int = 0,
        preferred: List<String> = emptyList(),
    ) = SniMaskPolicy.Inputs(
        mode = mode,
        regime = regime,
        customHosts = custom,
        pools = pools,
        seed = seed,
        attempt = attempt,
        blockedHosts = blocked,
        preferredHosts = preferred,
    )

    /**
     * Отобранный набор стоит впереди поворота.
     *
     * Это и есть требование «чтобы все начинали с лучших имён». Поднять их в
     * начало файла было мало: `rotate` смещает список по узлу, и имя из головы
     * попадает в окно очереди примерно раз из девяти.
     */
    @Test
    fun `отобранные имена идут первыми при любом узле`() {
        val provenPools = pools.copy(
            provenRussia = listOf("yastatic.net", "st.okcdn.ru"),
            provenGlobal = listOf("gstatic.com"),
        )
        for (seed in listOf(0, 1, 7, 12345, -99)) {
            val order = SniMaskPolicy.buildOrder(
                SniMaskPolicy.Inputs(
                    mode = SniMaskPolicy.MODE_AUTO,
                    regime = SniMaskPolicy.Regime.BLACKLIST,
                    customHosts = emptyList(),
                    pools = provenPools,
                    seed = seed,
                    attempt = 0,
                    blockedHosts = emptySet(),
                    preferredHosts = emptyList(),
                )
            )
            assertEquals("seed=$seed", listOf("yastatic.net", "st.okcdn.ru", "gstatic.com"), order.take(3))
        }
    }

    /** Измеренное на устройстве старше отобранного при сборке. */
    @Test
    fun `выученное имя обгоняет отобранное`() {
        val provenPools = pools.copy(provenRussia = listOf("yastatic.net"))
        val order = SniMaskPolicy.buildOrder(
            SniMaskPolicy.Inputs(
                mode = SniMaskPolicy.MODE_AUTO,
                regime = SniMaskPolicy.Regime.BLACKLIST,
                customHosts = emptyList(),
                pools = provenPools,
                seed = 3,
                attempt = 0,
                blockedHosts = emptySet(),
                preferredHosts = listOf("avito.ru"),
            )
        )
        assertEquals("avito.ru", order.first())
        assertEquals("yastatic.net", order[1])
    }

    /** В режиме белого списка зарубежные отобранные имена не появляются. */
    @Test
    fun `на белом списке отобранные зарубежные имена не берутся`() {
        val provenPools = pools.copy(
            provenRussia = listOf("yastatic.net"),
            provenGlobal = listOf("gstatic.com"),
        )
        val order = SniMaskPolicy.buildOrder(
            SniMaskPolicy.Inputs(
                mode = SniMaskPolicy.MODE_AUTO,
                regime = SniMaskPolicy.Regime.WHITELIST,
                customHosts = emptyList(),
                pools = provenPools,
                seed = 5,
                attempt = 0,
                blockedHosts = emptySet(),
                preferredHosts = emptyList(),
            )
        )
        assertEquals("yastatic.net", order.first())
        assertTrue(order.none { it == "gstatic.com" })
    }

    @Test
    fun `на белом списке берутся только проверенные имена`() {
        val order = SniMaskPolicy.buildOrder(inputs(regime = SniMaskPolicy.Regime.WHITELIST))
        // Большой российский список там тоже недоступен: каждое имя из него —
        // потраченная впустую попытка рукопожатия.
        assertTrue(order.none { it in pools.russia })
        assertTrue(order.all { it in pools.white })
    }

    @Test
    fun `выученное имя поднимается наверх своего набора`() {
        val order = SniMaskPolicy.buildOrder(
            inputs(regime = SniMaskPolicy.Regime.WHITELIST, preferred = listOf("zakupki.gov.ru")),
        )
        assertEquals("zakupki.gov.ru", order.first())
        assertTrue(order.contains("www.gosuslugi.ru"))
    }

    @Test
    fun `выученное чужое имя не возвращает зарубежные в режим белого списка`() {
        val order = SniMaskPolicy.buildOrder(
            inputs(regime = SniMaskPolicy.Regime.WHITELIST, preferred = listOf("www.google.com")),
        )
        assertTrue(order.none { it in pools.global })
    }

    @Test
    fun `на белом списке зарубежных имён не появляется`() {
        val order = SniMaskPolicy.buildOrder(inputs(regime = SniMaskPolicy.Regime.WHITELIST))
        assertTrue(order.isNotEmpty())
        assertTrue(order.none { it in pools.global })
        // Проверенные вручную имена идут раньше большого списка.
        assertEquals("www.gosuslugi.ru", order.first())
    }

    @Test
    fun `на чёрном списке российские и зарубежные чередуются, начиная с российского`() {
        val order = SniMaskPolicy.buildOrder(inputs(regime = SniMaskPolicy.Regime.BLACKLIST))
        assertEquals("www.gosuslugi.ru", order[0])
        assertTrue(order[1] in pools.global)
        assertTrue(order[2] in pools.white + pools.russia)
        assertTrue(order[3] in pools.global)
    }

    @Test
    fun `неизвестный режим ведёт себя как чёрный список, но начинает с российского`() {
        val unknown = SniMaskPolicy.buildOrder(inputs(regime = SniMaskPolicy.Regime.UNKNOWN))
        val blacklist = SniMaskPolicy.buildOrder(inputs(regime = SniMaskPolicy.Regime.BLACKLIST))
        assertEquals(blacklist, unknown)
        assertTrue(unknown.first() in pools.white + pools.russia)
    }

    @Test
    fun `неудача сдвигает выбор на следующее имя`() {
        val first = SniMaskPolicy.pick(inputs(attempt = 0))!!.host
        val second = SniMaskPolicy.pick(inputs(attempt = 1))!!.host
        assertNotEquals(first, second)
    }

    @Test
    fun `подводившее имя больше не предлагается`() {
        val first = SniMaskPolicy.pick(inputs())!!.host
        val next = SniMaskPolicy.pick(inputs(blocked = setOf(first)))!!.host
        assertNotEquals(first, next)
    }

    @Test
    fun `свой список отменяет встроенные наборы`() {
        val choice = SniMaskPolicy.pick(
            inputs(mode = SniMaskPolicy.MODE_CUSTOM, custom = listOf("example.org", "mail.ru")),
        )!!
        assertEquals("custom", choice.source)
        assertTrue(choice.host in listOf("example.org", "mail.ru"))
    }

    @Test
    fun `пустой свой список не подставляет ничего`() {
        assertNull(SniMaskPolicy.pick(inputs(mode = SniMaskPolicy.MODE_CUSTOM, custom = emptyList())))
        // И пустые наборы в авто дают тот же честный ответ.
        assertNull(
            SniMaskPolicy.pick(
                SniMaskPolicy.Inputs(
                    mode = SniMaskPolicy.MODE_AUTO,
                    regime = SniMaskPolicy.Regime.UNKNOWN,
                ),
            ),
        )
    }

    @Test
    fun `список пользователя разбирается по запятым, пробелам и переводам строк`() {
        val parsed = SniMaskPolicy.parseCustomList(" https://VK.com/feed , ya.ru\nmail.ru;  ya.ru ")
        assertEquals(listOf("vk.com", "ya.ru", "mail.ru"), parsed)
    }

    @Test
    fun `мусор в своём списке отбрасывается, а не превращается в имя`() {
        val parsed = SniMaskPolicy.parseCustomList("не-имя, 10.0.0.1, -bad.ru, ok.ru, ..ru")
        assertEquals(listOf("ok.ru"), parsed)
    }

    @Test
    fun `один и тот же узел получает одно и то же имя`() {
        val a = SniMaskPolicy.pick(inputs(seed = 12345))!!.host
        val b = SniMaskPolicy.pick(inputs(seed = 12345))!!.host
        assertEquals(a, b)
        val other = SniMaskPolicy.pick(inputs(seed = 999))!!.host
        // Разным узлам — разные имена, иначе подмена видна как один и тот же хост.
        assertNotEquals(a, other)
    }
}

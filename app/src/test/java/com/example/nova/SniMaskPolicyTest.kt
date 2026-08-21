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
    ) = SniMaskPolicy.Inputs(
        mode = mode,
        regime = regime,
        customHosts = custom,
        pools = pools,
        seed = seed,
        attempt = attempt,
        blockedHosts = blocked,
    )

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

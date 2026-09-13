package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Прикрытие `I1` у сгенерированных профилей.
 *
 * Раньше `I1` был один на весь набор: `bundledMaskPacket` брал его у первого
 * попавшегося встроенного семени и подставлял всем профилям разом — один DCID,
 * один ClientHello, одно имя в SNI на пятьдесят записей. Теперь каждый профиль
 * несёт свой пакет, а общий остаётся запасным для снимков, снятых до этой
 * правки.
 *
 * Проверяется тестом, а не наблюдением за телефоном, потому что подстановка —
 * ровно то место, где прикрытие либо доезжает до конфигурации, либо тихо
 * теряется, и «наверное работает» тут не годится.
 */
class WarpGeneratedI1Test {

    private val identity = WarpGeneratedStore.Identity(
        privateKey = "cHJpdmF0ZQ==",
        publicKey = "cHVibGlj",
        ipv4 = "172.16.0.2/32",
        ipv6 = "2606:4700:110::2/128",
        peerPublicKey = "cGVlcg==",
        reserved = "",
        createdAt = 0L,
    )

    private fun profile(mask: String) = WarpGeneratedProfile(
        host = "8.8.8.8",
        port = 2408,
        rttMs = 40,
        junkCount = 5,
        junkMin = 44,
        junkMax = 90,
        createdAt = 0L,
        maskPacket = mask,
    )

    private fun lineOf(config: String, key: String): String? =
        config.lineSequence().firstOrNull { it.startsWith("$key = ") }?.substringAfter(" = ")

    @Test
    fun `own mask packet wins over the bundled one`() {
        val config = WarpGeneratedStore.buildRawConfig(
            identity,
            profile("<b 0xdeadbeef>"),
            "<b 0xfeedface>",
        )
        assertEquals("<b 0xdeadbeef>", lineOf(config, "I1"))
    }

    @Test
    fun `bundled packet is the fallback for snapshots without one`() {
        val config = WarpGeneratedStore.buildRawConfig(identity, profile(""), "<b 0xfeedface>")
        assertEquals("<b 0xfeedface>", lineOf(config, "I1"))
    }

    /**
     * Пустой `I1` не превращается в строку `I1 = `: `uapi.go` убивает туннель на
     * любом неизвестном ключе, и пустое значение считается неизвестным (N6).
     */
    @Test
    fun `no I1 line at all when neither is available`() {
        val config = WarpGeneratedStore.buildRawConfig(identity, profile(""), "")
        assertTrue(config.lineSequence().none { it.startsWith("I1") })
    }

    /**
     * `S1`-`S4` и `H1`-`H4` — не параметры, а значения обычного WireGuard: узел
     * WARP других не понимает. Тест стоит здесь, чтобы правка «а давайте
     * поварьируем и их» падала сборкой, а не туннелем на телефоне.
     */
    @Test
    fun `shape fields stay at stock WireGuard values`() {
        val config = WarpGeneratedStore.buildRawConfig(identity, profile(""), "")
        listOf("S1", "S2", "S3", "S4").forEach { assertEquals("0", lineOf(config, it)) }
        listOf("H1" to "1", "H2" to "2", "H3" to "3", "H4" to "4").forEach { (key, value) ->
            assertEquals(value, lineOf(config, key))
        }
    }

    /**
     * Джанк доезжает из профиля, а не подставляется константой: именно он
     * отличает профили друг от друга (N5).
     */
    @Test
    fun `junk comes from the profile`() {
        val config = WarpGeneratedStore.buildRawConfig(identity, profile(""), "")
        assertEquals("5", lineOf(config, "Jc"))
        assertEquals("44", lineOf(config, "Jmin"))
        assertEquals("90", lineOf(config, "Jmax"))
    }

    /**
     * Два вызова строителя дают два **разных** пакета.
     *
     * Это и есть весь выигрыш правки. Прежний `bundledMaskPacket` отдавал всем
     * профилям байт в байт один блоб: один DCID, один ClientHello, одно имя. Если
     * бы строитель оказался детерминированным, замена ничего бы не изменила — а
     * заметить это на телефоне нельзя, `I1` виден только в трафике.
     */
    @Test
    fun `each built packet differs from the previous one`() {
        val packets = (1..8).map { ProtonQuicInitial.buildI1("example-$it.com") }
        assertTrue("строитель вернул пустое", packets.none { it.isBlank() })
        assertEquals("пакеты повторяются", packets.size, packets.toSet().size)
    }

    /**
     * Пакет остаётся разбираемым QUIC Initial версии 1.
     *
     * Поле версии (байты 1..4) защитой заголовка **не** накрыто, поэтому его
     * видно и снаружи — ровно так же, как его видит DPI. Длина обязана быть 1250
     * байт: RFC 9000 §14.1 требует от дейтаграммы с клиентским Initial не меньше
     * 1200, и короткий пакет любой разборщик выбрасывает, то есть прикрытие
     * перестаёт быть прикрытием.
     */
    @Test
    fun `built packet is still a version 1 QUIC Initial`() {
        val hex = ProtonQuicInitial.buildI1("example.com").substringAfter("0x").substringBefore('>')
        assertEquals(1250 * 2, hex.length)
        assertEquals("00000001", hex.substring(2, 10))
        // Длинный заголовок, тип Initial: старшие четыре бита первого байта 1100,
        // младшие зашумлены защитой заголовка и потому не проверяются.
        val first = hex.substring(0, 2).toInt(16)
        assertEquals(0xC0, first and 0xF0)
    }

    /** Слишком длинное имя — не пакет с мусором, а пустая строка (N6). */
    @Test
    fun `absurd sni yields no packet rather than a broken one`() {
        assertEquals("", ProtonQuicInitial.buildI1("a".repeat(300)))
        assertEquals("", ProtonQuicInitial.buildI1("   "))
    }
}

package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Строка моста едет в `torrc` как есть, а `torrc` разбирается построчно.
 *
 * Значит перевод строки внутри записи — это не мост, а дописанная директива
 * tor: от `SocksPort 0.0.0.0:9050` (открытый наружу прокси) до подмены
 * `ClientTransportPlugin`. Строки приходят с чужих сборщиков по сети, поэтому
 * проверка стоит в самом разборе, а не только перед записью файла.
 */
class TorBridgeParseTest {

    @Test
    fun `обычная строка obfs4 разбирается`() {
        val bridge = TorBridge.parse(
            "obfs4 45.66.35.35:443 ABCDEF0123456789ABCDEF0123456789ABCDEF01 cert=xyz+/=;iat-mode=0"
        )
        assertTrue(bridge != null)
        assertEquals("obfs4", bridge!!.transport)
        assertEquals("45.66.35.35:443", bridge.endpoint)
        assertEquals(Pair("45.66.35.35", 443), bridge.dialTarget())
    }

    @Test
    fun `перевод строки в записи моста отвергается`() {
        val injected = "obfs4 45.66.35.35:443 ABCDEF0123456789ABCDEF0123456789ABCDEF01 cert=x;iat-mode=0\n" +
            "SocksPort 0.0.0.0:9050"
        assertNull(TorBridge.parse(injected))
    }

    @Test
    fun `возврат каретки тоже отвергается`() {
        assertNull(
            TorBridge.parse("obfs4 45.66.35.35:443 ABCDEF0123456789ABCDEF0123456789ABCDEF01 cert=x;iat-mode=0\rLog notice file /sdcard/tor.log")
        )
    }

    @Test
    fun `приставка Bridge снимается, чтобы не вышло Bridge Bridge`() {
        val bridge = TorBridge.parse("Bridge obfs4 45.66.35.35:443 ABCDEF0123456789ABCDEF0123456789ABCDEF01 cert=x;iat-mode=0")
        assertTrue(bridge != null)
        assertTrue(!bridge!!.line.startsWith("Bridge", ignoreCase = true))
    }

    @Test
    fun `адрес-заглушка не считается целью для набора`() {
        // Строки webtunnel несут документационный префикс RFC 3849: соединяться
        // надо по `url=`, а не по этому адресу.
        val bridge = TorBridge.parse(
            "webtunnel [2001:db8:1234::1]:443 ABCDEF0123456789ABCDEF0123456789ABCDEF01 url=https://example.com/path"
        )
        assertTrue(bridge != null)
        assertNull(bridge!!.dialTarget())
    }

    @Test
    fun `snowflake разбирается, но целью для набора не становится`() {
        val bridge = TorBridge.parse(TorBuiltinBridges.SNOWFLAKE_CDN77.first())
        assertTrue(bridge != null)
        assertEquals("snowflake", bridge!!.transport)
        assertEquals("2B280B23E1107BB62ABFC40DDCC8824814F80A72", bridge.fingerprint)
        // Адрес в строке — документационный RFC 5737: дозваниваться по нему
        // нельзя, соединение идёт через брокера.
        assertNull(bridge.dialTarget())
        // Но мост при этом годный: у него и не должно быть адреса.
        assertTrue(TorBridge.isUsable(bridge))
    }

    /**
     * `meek_lite` в ядро собран, а способа входа у него нет: источника живых
     * строк моста не нашлось. Мост, которым нельзя подключиться, не должен
     * попадать ни в файл, ни в счёт на экране — иначе «живых мостов 37» значит
     * тридцать пять.
     */
    @Test
    fun `неподдерживаемый транспорт отбрасывается в разборе`() {
        assertNull(
            TorBridge.parse(
                "meek_lite 192.0.2.2:2 97700DFE9F483596DDA6264C4D7DF7641E1E39CE " +
                    "url=https://meek.azureedge.net/ front=ajax.aspnetcdn.com"
            )
        )
        assertNull(TorBridge.parse("conjure 192.0.2.3:80 0000000000000000000000000000000000000000 url=https://example.invalid/"))
    }

    /**
     * Аргументы читаются из строки, а не из полей: полей у snowflake два
     * десятка, и хранить их значило бы терять те, о которых мы ещё не знаем.
     */
    @Test
    fun `аргументы строки моста читаются по имени`() {
        val bridge = TorBridge.parse(TorBuiltinBridges.SNOWFLAKE_CDN77.first())!!
        assertEquals("https://1098762253.rsc.cdn77.org/", bridge.url)
        assertEquals("www.cdn77.com,www.phpmyadmin.net", bridge.arg("fronts"))
        assertEquals("hellorandomizedalpn", bridge.arg("utls-imitate"))
        // Запрошенного аргумента может не быть — это пустая строка, а не падение.
        assertEquals("", bridge.arg("ampcache"))
        // Имя ищется целиком: `front` не должен находиться внутри `fronts`.
        assertEquals("", bridge.arg("front"))

        val amp = TorBridge.parse(TorBuiltinBridges.SNOWFLAKE_AMP.first())!!
        assertEquals("https://cdn.ampproject.org/", amp.arg("ampcache"))
        assertEquals("www.google.com", amp.arg("front"))
    }

    /**
     * Tor передаёт аргументы моста внешнему транспорту полями логина и пароля
     * SOCKS5 — по 255 байт каждое, 510 всего. Строка, которая в них не влезает,
     * не подключается **молча**: tor просто не отдаст часть аргументов.
     *
     * Замер на канонической строке: 384 байта. Тест сторожит запас, чтобы
     * «добавим ещё пару STUN-серверов» не прошло незамеченным.
     */
    @Test
    fun `аргументы встроенных мостов влезают в поля SOCKS5`() {
        (TorBuiltinBridges.SNOWFLAKE_CDN77 + TorBuiltinBridges.SNOWFLAKE_AMP).forEach { raw ->
            val bridge = TorBridge.parse(raw)
            assertTrue(raw, bridge != null)
            val args = bridge!!.line
                .split(Regex("\\s+"))
                .drop(3)
                .joinToString(";")
            assertTrue("$args (${args.length} Б)", args.length <= 510)
        }
    }

    /** Обе встроенные строки разбираются и несут разные отпечатки. */
    @Test
    fun `встроенные наборы snowflake разбираются целиком`() {
        listOf(TorBuiltinBridges.SNOWFLAKE_CDN77, TorBuiltinBridges.SNOWFLAKE_AMP).forEach { set ->
            val parsed = set.mapNotNull { TorBridge.parse(it) }
            assertEquals(set.size, parsed.size)
            assertEquals(set.size, parsed.map { it.fingerprint }.distinct().size)
            parsed.forEach { assertEquals("snowflake", it.transport) }
        }
        // Наборы различаются местом встречи, а не мостами: отпечатки у них одни
        // и те же, и именно поэтому одновременно в torrc едет только один.
        assertEquals(
            TorBuiltinBridges.SNOWFLAKE_CDN77.mapNotNull { TorBridge.parse(it)?.fingerprint }.toSet(),
            TorBuiltinBridges.SNOWFLAKE_AMP.mapNotNull { TorBridge.parse(it)?.fingerprint }.toSet(),
        )
    }
}

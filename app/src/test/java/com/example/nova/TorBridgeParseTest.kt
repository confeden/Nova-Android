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
}

package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ошибка в подписи стоит дорого: воркер ответит 404, и домен потеряется для всех
 * пользователей сборки. Поэтому проверяются именно те свойства, на которые
 * опирается серверная сторона.
 */
class CfWsTokenTest {

    private val secret = "0123456789abcdef".repeat(4) // 64 символа, как настоящий

    @Test
    fun `подписываются только собственные домены`() {
        assertTrue(CfWsToken.isOwnedHost("nova-app.eu"))
        assertTrue(CfWsToken.isOwnedHost("kws5.nova-app.eu"))
        assertTrue(CfWsToken.isOwnedHost("kws5-1.nova-app.eu"))
        assertTrue(CfWsToken.isOwnedHost("KWS5.Nova-App.EU."))

        assertFalse(CfWsToken.isOwnedHost("pclead.co.uk"))
        assertFalse(CfWsToken.isOwnedHost("kws5.web.telegram.org"))
        // Подстрока в чужом домене не должна считаться своей.
        assertFalse(CfWsToken.isOwnedHost("nova-app.eu.evil.com"))
        assertFalse(CfWsToken.isOwnedHost("xnova-app.eu"))
    }

    @Test
    fun `чужим доменам достаётся чистый binary`() {
        assertEquals("binary", CfWsToken.subprotocolHeader("pclead.co.uk", secret, 1_800_000_000L))
        assertEquals("binary", CfWsToken.subprotocolHeader("kws5.web.telegram.org", secret, 1_800_000_000L))
    }

    @Test
    fun `без секрета подпись не добавляется`() {
        assertEquals("binary", CfWsToken.subprotocolHeader("kws5.nova-app.eu", "", 1_800_000_000L))
    }

    /**
     * Тот же вектор закреплён в `nova-core/cfws/token_test.go`. Подпись в
     * рукопожатие подставляет Go, поэтому расхождение реализаций иначе всплыло
     * бы только ответом воркера 404 на живых устройствах.
     */
    @Test
    fun `эталонный вектор совпадает с реализацией в nova-core`() {
        val now = 1_800_000_000L
        assertEquals(
            "nova1.15000000.76385ab1fbc4aacbc00bdaf0f13a52ec",
            CfWsToken.build("kws5.nova-app.eu", secret, now),
        )
        assertEquals(
            "nova1.15000000.f6b498ebcca66e422d2d145870ff8973",
            CfWsToken.build("kws5-1.nova-app.eu", secret, now),
        )
        assertEquals(
            "nova1.15000000.b845bde9cdc531d958661185d335ae3d",
            CfWsToken.build("kws2.nova-app.eu", secret, now),
        )
    }

    @Test
    fun `формат заголовка соответствует спецификации`() {
        val header = CfWsToken.subprotocolHeader("kws5.nova-app.eu", secret, 1_800_000_000L)
        assertTrue(header.startsWith("binary, nova1."))
        val token = header.removePrefix("binary, ")
        val parts = token.split(".")
        assertEquals(3, parts.size)
        assertEquals("nova1", parts[0])
        // Окно — целое десятичное без ведущих нулей.
        assertEquals((1_800_000_000L / 120L).toString(), parts[1])
        // Подпись — 32 знака в нижнем регистре.
        assertEquals(32, parts[2].length)
        assertTrue(parts[2].all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `окно меняется каждые две минуты`() {
        val base = 1_800_000_000L
        val a = CfWsToken.build("kws5.nova-app.eu", secret, base)
        val sameWindow = CfWsToken.build("kws5.nova-app.eu", secret, base + 119)
        val nextWindow = CfWsToken.build("kws5.nova-app.eu", secret, base + 120)
        assertEquals(a, sameWindow)
        assertNotEquals(a, nextWindow)
    }

    @Test
    fun `подпись привязана к имени узла`() {
        val now = 1_800_000_000L
        val kws2 = CfWsToken.build("kws2.nova-app.eu", secret, now)
        val kws5 = CfWsToken.build("kws5.nova-app.eu", secret, now)
        val media = CfWsToken.build("kws5-1.nova-app.eu", secret, now)
        assertNotEquals(kws2, kws5)
        // Медийный узел — отдельное имя, значит и подпись отдельная.
        assertNotEquals(kws5, media)
    }

    @Test
    fun `регистр и завершающая точка не влияют на подпись`() {
        val now = 1_800_000_000L
        val plain = CfWsToken.build("kws5.nova-app.eu", secret, now)
        assertEquals(plain, CfWsToken.build("KWS5.NOVA-APP.EU", secret, now))
        assertEquals(plain, CfWsToken.build("kws5.nova-app.eu.", secret, now))
        assertEquals(plain, CfWsToken.build("  kws5.nova-app.eu  ", secret, now))
    }

    @Test
    fun `другой секрет даёт другую подпись`() {
        val now = 1_800_000_000L
        val a = CfWsToken.build("kws5.nova-app.eu", secret, now)
        val b = CfWsToken.build("kws5.nova-app.eu", secret.replaceRange(0, 1, "f"), now)
        assertNotEquals(a, b)
    }
}

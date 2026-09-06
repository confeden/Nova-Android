package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Источник адреса и страны выхода.
 *
 * Тест существует из-за конкретного дефекта: экран спрашивал поддомены
 * `ipv4.`/`ipv6.`, а `/txt` у них отдаёт страницу 404. Разбор честно возвращал
 * null, в журнал шло «трасса не дошла», и бейдж на **любом** транспорте оставался
 * «MASQUE: --». Поэтому здесь проверяется не только счастливый разбор, но и то,
 * что именно эту страницу ошибки за ответ принять нельзя.
 */
class ExitAddressTest {

    @Test
    fun `разбирает ответ основного источника`() {
        val observation = ExitAddress.parse("85.174.181.85|ipv4|12389|RU|HTTP/1.1|curl/8.19.0|")
        requireNotNull(observation)
        assertEquals("85.174.181.85", observation.ip)
        assertEquals("ipv4", observation.ipVersion)
        assertEquals("RU", observation.country)
    }

    @Test
    fun `страница 404 поддомена ответом не считается`() {
        val body = """{"name":"Not Found","message":"Page not found.","code":0,"status":404}"""
        assertNull(ExitAddress.parse(body))
        assertNull(ExitAddress.observe(ExitAddress.URL_ANY, body))
    }

    @Test
    fun `разбирает trace запасного источника`() {
        val body = """
            fl=123abc
            h=www.cloudflare.com
            ip=104.28.232.200
            ts=1757130000.123
            visit_scheme=https
            colo=HEL
            loc=RU
            warp=on
        """.trimIndent()
        val observation = ExitAddress.parseTrace(body)
        requireNotNull(observation)
        assertEquals("104.28.232.200", observation.ip)
        assertEquals("RU", observation.country)
        assertEquals("ipv4", observation.ipVersion)
    }

    @Test
    fun `trace без адреса ответом не считается`() {
        assertNull(ExitAddress.parseTrace("colo=HEL\nloc=RU\nwarp=off"))
    }

    @Test
    fun `trace с неполной страной отдаёт адрес и пустую страну`() {
        val observation = ExitAddress.parseTrace("ip=2606:4700:110::1\nloc=XX1")
        requireNotNull(observation)
        assertEquals("2606:4700:110::1", observation.ip)
        assertEquals("ipv6", observation.ipVersion)
        assertEquals("", observation.country)
    }

    @Test
    fun `разбор выбирается по входу, а не по форме тела`() {
        val traceBody = "ip=104.28.232.200\nloc=RU"
        // Тело trace основным разбором не читается — и не должно: принять его
        // там значило бы взять поле «страна» из чужого формата.
        assertNull(ExitAddress.observe(ExitAddress.URL_ANY, traceBody))
        assertEquals(
            "104.28.232.200",
            ExitAddress.observe(ExitAddress.URL_FALLBACK, traceBody)?.ip,
        )
    }

    @Test
    fun `порядок входов - основной первым`() {
        assertEquals(ExitAddress.URL_ANY, ExitAddress.URLS.first())
        assertEquals(2, ExitAddress.URLS.size)
        assertTrue(ExitAddress.URLS.contains(ExitAddress.URL_FALLBACK))
        // Поддоменов `ipv4.`/`ipv6.` в опросе быть не должно: `/txt` у них 404.
        assertTrue(ExitAddress.URLS.none { it.contains("ipv4.") || it.contains("ipv6.") })
    }
}

package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Журнал уходит в отчёты об ошибках, а с релеями API SurfEasy в нём появились
 * ссылки с логином и паролем. Проверки здесь про то, что учётные данные из такой
 * ссылки не переживают очистку.
 */
class DiagnosticLogSanitizerTest {

    @Test
    fun `логин и пароль в ссылке не попадают в очищенный журнал`() {
        val sanitized = DiagnosticLogSanitizer.sanitize(
            "Opera API через https://nova:tsp-SECRETVALUE@relay.example.eu:8443"
        )
        assertFalse(sanitized.contains("tsp-SECRETVALUE"))
        assertFalse(sanitized.contains("nova:"))
        assertTrue(sanitized.contains("<hidden>@"))
        // Схема и порт остаются: по ним и отличают попытку через релей от прямой.
        assertTrue(sanitized.contains("https://"))
        assertTrue(sanitized.contains("8443"))
    }

    @Test
    fun `socks5 с учётными данными очищается так же`() {
        val sanitized = DiagnosticLogSanitizer.sanitize("proxy=socks5://user:p%40ss@127.0.0.1:1080")
        assertFalse(sanitized.contains("p%40ss"))
        assertTrue(sanitized.contains("socks5://<hidden>@"))
    }

    @Test
    fun `ссылка без учётных данных не меняется`() {
        // Правило не должно трогать обычные адреса: по ним разбирают отказы.
        val sanitized = DiagnosticLogSanitizer.sanitize("GET https://api2.sec-tunnel.com/v4/discover")
        assertEquals("GET https://api2.sec-tunnel.com/v4/discover", sanitized)
    }

    @Test
    fun `почта по-прежнему скрывается`() {
        // Правило для ссылок стоит перед почтовым и не должно его отменять.
        val sanitized = DiagnosticLogSanitizer.sanitize("контакт: someone@example.com")
        assertFalse(sanitized.contains("someone@example.com"))
        assertTrue(sanitized.contains("<email>"))
    }

    @Test
    fun `у адреса IPv4 скрывается хвост, а сеть и порт остаются`() {
        val sanitized = DiagnosticLogSanitizer.sanitize("endpoint 188.114.97.3:939 отвечает")
        assertTrue(sanitized.contains("188.114.97.***:939"))
        assertFalse(sanitized.contains("97.3:"))
    }

    @Test
    fun `локальные и служебные адреса не трогаются`() {
        // 172.16.0.2 — внутренний адрес туннеля, общий у всех семян (I7).
        val sanitized = DiagnosticLogSanitizer.sanitize(
            "TUN 172.16.0.2, прокси 127.0.0.1:1080, маршрут 0.0.0.0/0, LAN 192.168.1.5"
        )
        assertTrue(sanitized.contains("172.16.0.2"))
        assertTrue(sanitized.contains("127.0.0.1:1080"))
        assertTrue(sanitized.contains("0.0.0.0/0"))
        assertTrue(sanitized.contains("192.168.1.5"))
    }

    @Test
    fun `известный публичный резолвер остаётся целиком`() {
        val sanitized = DiagnosticLogSanitizer.sanitize("DNS 8.8.8.8 ответил за 40 мс")
        assertTrue(sanitized.contains("8.8.8.8"))
    }

    @Test
    fun `у адреса IPv6 не остаётся интерфейсной части`() {
        val sanitized = DiagnosticLogSanitizer.sanitize("выход [2a02:6ea0:c024::17]:2408")
        assertFalse(sanitized.contains("::17"))
        assertTrue(sanitized.contains("2a02:6ea0:c024::***"))
        assertTrue(sanitized.contains("2408"))
    }

    @Test
    fun `полная запись IPv6 тоже теряет хвост`() {
        val sanitized = DiagnosticLogSanitizer.sanitize("адрес 2a02:6ea0:c024:1:f06:b6b8:44c2:6e9e")
        assertFalse(sanitized.contains("6e9e"))
        assertTrue(sanitized.contains("2a02:6ea0:c024::***"))
    }

    @Test
    fun `время в тексте адресом не считается`() {
        val sanitized = DiagnosticLogSanitizer.sanitize("сессия шла 10:07:41 и оборвалась")
        assertEquals("сессия шла 10:07:41 и оборвалась", sanitized)
    }

    @Test
    fun `метка времени рядом с адресом не срывает маску`() {
        // Правило телефона допускает пробелы внутри числа и раньше съедало
        // первый октет вместе с меткой времени, оставляя хвост открытым.
        val sanitized = DiagnosticLogSanitizer.sanitize("handshake 1757520123 188.114.97.3:2408")
        assertTrue(sanitized, sanitized.contains("188.114.97.***:2408"))
        assertFalse(sanitized.contains("97.3:"))
    }

    @Test
    fun `список портов перед адресом тоже не мешает`() {
        val sanitized = DiagnosticLogSanitizer.sanitize("Ports 1080 1081 1082 188.114.97.3")
        assertTrue(sanitized, sanitized.contains("188.114.97.***"))
    }

    @Test
    fun `настоящий номер по-прежнему скрывается`() {
        val sanitized = DiagnosticLogSanitizer.sanitize("контакт +7 999 123-45-67 и всё")
        assertFalse(sanitized.contains("999"))
        assertTrue(sanitized.contains("<phone>"))
    }

    @Test
    fun `метка времени номером не считается`() {
        val sanitized = DiagnosticLogSanitizer.sanitize("cycle 1757520123 done")
        assertEquals("cycle 1757520123 done", sanitized)
    }

    @Test
    fun `дата рядом со временем остаётся в журнале`() {
        val line = "[2026-09-10 23:52:37 INFO  tun2proxy] - Beginning #26"
        assertEquals(line, DiagnosticLogSanitizer.sanitize(line))
    }

    @Test
    fun `номер без плюса всё ещё скрывается`() {
        val sanitized = DiagnosticLogSanitizer.sanitize("звонил 8 999 123 45 67 вчера")
        assertTrue(sanitized.contains("<phone>"))
        assertFalse(sanitized.contains("999"))
    }
}

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
}

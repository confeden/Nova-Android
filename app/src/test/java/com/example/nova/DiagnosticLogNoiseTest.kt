package com.example.nova

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Строки взяты из logcat Mi A1 и Pixel 4a 2026-09-16 как есть, а не собраны по коду.
class DiagnosticLogNoiseTest {

    @Test
    fun `строка opera-proxy на каждый запрос уходит в болтовню`() {
        assertTrue(
            DiagnosticLogNoise.isPerConnectionChatter(
                "[OperaProxy] PROXY   : 2026/09/16 10:38:40 handler.go:102: INFO     Request: 127.0.0.1:48422 HTTP/1.1 CONNECT //cp.cloudflare.com:443"
            )
        )
        assertTrue(
            DiagnosticLogNoise.isPerConnectionChatter(
                "[OperaProxy] PROXY   : 2026/09/16 11:30:49 handler.go:93: INFO     127.0.0.1:38771 GET http://1.1.1.1/cdn-cgi/trace 301 Moved Permanently"
            )
        )
    }

    @Test
    fun `ошибка opera-proxy остаётся в журнале`() {
        assertFalse(
            DiagnosticLogNoise.isPerConnectionChatter(
                "[OperaProxy] PROXY   : 2026/09/16 10:38:40 handler.go:49: ERROR    Can't satisfy CONNECT request: bad response from upstream proxy server: 502 Bad Gateway"
            )
        )
    }

    @Test
    fun `запуск и регистрация opera-proxy остаются в журнале`() {
        assertFalse(
            DiagnosticLogNoise.isPerConnectionChatter(
                "[OperaProxy] MAIN    : 2026/09/16 10:38:39 main.go:231: INFO     Attempting action \"anonymous registration\", attempt #1..."
            )
        )
    }

    @Test
    fun `открытие и закрытие соединения tun2proxy уходят в болтовню`() {
        assertTrue(
            DiagnosticLogNoise.isPerConnectionChatter(
                "[2026-09-16 13:38:53 INFO  tun2proxy] - Beginning #1 TCP 10.1.10.1:41489 -> 198.18.0.0:5228"
            )
        )
        assertTrue(
            DiagnosticLogNoise.isPerConnectionChatter(
                "[2026-09-16 13:39:40 INFO  tun2proxy] - Ending #1 TCP 10.1.10.1:41489 -> 198.18.0.0:5228 with (Ok(2252), Ok(1950))"
            )
        )
    }

    @Test
    fun `соединение tun2proxy с ошибкой остаётся в журнале`() {
        assertFalse(
            DiagnosticLogNoise.isPerConnectionChatter(
                // Форма `Err(...)` — `Debug` пары `io::Result` из `log::info!("Ending {} with {:?}")` tun2proxy.
                "[2026-09-16 13:39:40 INFO  tun2proxy] - Ending #7 TCP 10.1.10.1:41489 -> 198.18.0.0:443 with " +
                    "(Err(Os { code: 104, kind: ConnectionReset, message: \"Connection reset by peer\" }), Ok(0))"
            )
        )
        assertFalse(
            DiagnosticLogNoise.isPerConnectionChatter(
                "[2026-09-16 13:39:41 ERROR tun2proxy] - #9 TCP 10.1.10.1:41490 -> 198.18.0.1:443 error \"Expected success status code. Server replied with 502 [Reason: Bad Gateway].\""
            )
        )
    }

    @Test
    fun `закрытие UDP с текстом ошибки остаётся в журнале`() {
        // Форма из tun2proxy `src/lib.rs`: `log::info!("Ending {info} with \"{e}\"")`.
        assertFalse(
            DiagnosticLogNoise.isPerConnectionChatter(
                "[2026-09-16 13:40:02 INFO  tun2proxy] - Ending #12 UDP 10.1.10.1:5353 -> 198.18.0.3:443 with \"connection refused\""
            )
        )
    }

    @Test
    fun `запуск tun2proxy и свои строки не трогаются`() {
        assertFalse(
            DiagnosticLogNoise.isPerConnectionChatter(
                "[2026-09-16 13:38:50 INFO  tun2proxy] - tun2proxy 0.7.4 (4d3b 2026-09-01) starting..."
            )
        )
        assertFalse(DiagnosticLogNoise.isPerConnectionChatter("Туннель стабилен: 3 перерукопожатий за 120 с"))
    }
}

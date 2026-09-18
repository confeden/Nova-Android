package com.example.nova

/**
 * Строки чужих движков, которые печатаются на **каждый** запрос или соединение.
 *
 * Они уходят в журнал уровнем DEBUG — то есть только в logcat. Измерено на Mi A1
 * (сеанс Opera, 53 минуты): 3806 строк из 4199 — это пара строк opera-proxy на
 * каждую пробу `GET http://1.1.1.1/cdn-cgi/trace`, ещё 113 — открытие и закрытие
 * соединений tun2proxy. Файл журнала за час заполнялся ими целиком, а подключение,
 * смена узла и причина отказа вытеснялись из него первыми.
 *
 * Ошибки остаются: у opera-proxy режется только `INFO` из `handler.go`, у tun2proxy —
 * только `Beginning` и удачный `Ending`. Строки `main.go` (регистрация, запуск) и
 * `ERROR ... #N TCP ... error "..."` идут в журнал как раньше.
 */
object DiagnosticLogNoise {
    private val operaRequestInfo = Regex("""\bhandler\.go:\d+:\s+INFO\s""")
    private val tun2proxyBeginning = Regex("""\bINFO\s+tun2proxy]\s+-\s+Beginning #\d+ (TCP|UDP)\b""")

    // Только удачное закрытие: `with (Ok(n), Ok(n))`. Закрытие UDP с ошибкой печатается
    // иначе — `with "текст ошибки"` (tun2proxy `src/lib.rs`), и оно тоже остаётся.
    private val tun2proxyCleanEnding =
        Regex("""\bINFO\s+tun2proxy]\s+-\s+Ending #\d+ (TCP|UDP)\b.* with \(Ok\(\d+\), Ok\(\d+\)\)\s*$""")

    fun isPerConnectionChatter(message: String): Boolean =
        operaRequestInfo.containsMatchIn(message) ||
            tun2proxyBeginning.containsMatchIn(message) ||
            tun2proxyCleanEnding.containsMatchIn(message)
}

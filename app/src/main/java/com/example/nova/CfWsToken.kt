package com.example.nova

import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Подпись рукопожатия WebSocket для собственных поддоменов `nova-app.eu`.
 *
 * Поддомены `kws*.nova-app.eu` — это Worker Cloudflare, проксирующий WSS Telegram.
 * До сих пор он был открытым: воспользоваться им могла любая программа, узнавшая
 * имя узла, а имена лежат в публичном репозитории. Подпись закрывает эту
 * возможность.
 *
 * Токен передаётся вторым подпротоколом рядом с `binary`:
 *
 * ```
 * Sec-WebSocket-Protocol: binary, nova1.<окно>.<подпись>
 * ```
 *
 * Окно — номер двухминутного интервала от начала эпохи. Воркер принимает ±2 окна,
 * то есть допускает расхождение часов до четырёх минут.
 *
 * Подпись привязана к имени узла: иначе токен, выданный для `kws2`, можно было бы
 * предъявить для `kws5`.
 *
 * Подписываются **только собственные узлы**. Для остальных доменов Cloudflare из
 * общего пула заголовок остаётся ровно `binary`: их воркеры про эту схему не
 * знают, и лишний подпротокол может нарушить их работу.
 *
 * В рукопожатие токен подставляет не этот объект, а `nova-core/cfws`: окно
 * подписи привязано к моменту открытия соединения, а пул WebSocket добивает
 * соединения по мере расхода, поэтому посчитанный при старте приложения токен
 * успел бы протухнуть. Приложение передаёт в движок только секрет. Здесь схема
 * остаётся эталоном: `CfWsTokenTest` и `nova-core/cfws/token_test.go` закреплены
 * одним и тем же вектором, и расхождение реализаций ловится тестами, а не
 * ответом воркера 404.
 */
object CfWsToken {

    private const val VERSION = "nova1"
    private const val WINDOW_SECONDS = 120L
    private const val MAC_HEX_LENGTH = 32

    const val OWNED_DOMAIN = "nova-app.eu"

    /** Заголовок без подписи — значение по умолчанию для всех чужих доменов. */
    const val PLAIN_SUBPROTOCOL = "binary"

    fun isOwnedHost(host: String): Boolean {
        val normalized = normalizeHost(host)
        return normalized == OWNED_DOMAIN || normalized.endsWith(".$OWNED_DOMAIN")
    }

    /**
     * Значение заголовка `Sec-WebSocket-Protocol` для указанного узла.
     *
     * Возвращает `binary` без изменений, если узел чужой или секрет не задан:
     * отладочная сборка без секрета должна продолжать работать по публичным
     * доменам, а не ломаться.
     */
    fun subprotocolHeader(
        host: String,
        secret: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000L,
    ): String {
        if (secret.isBlank() || !isOwnedHost(host)) return PLAIN_SUBPROTOCOL
        val token = build(host, secret, nowSeconds) ?: return PLAIN_SUBPROTOCOL
        return "$PLAIN_SUBPROTOCOL, $token"
    }

    /** Сам токен вида `nova1.<окно>.<подпись>`; null, если посчитать не удалось. */
    fun build(host: String, secret: String, nowSeconds: Long): String? {
        val normalized = normalizeHost(host)
        if (normalized.isEmpty() || secret.isBlank()) return null
        val window = Math.floorDiv(nowSeconds, WINDOW_SECONDS)
        val message = "$window|$normalized"
        val mac = runCatching {
            Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            }.doFinal(message.toByteArray(Charsets.UTF_8))
        }.getOrNull() ?: return null
        return "$VERSION.$window.${toHex(mac).take(MAC_HEX_LENGTH)}"
    }

    private fun normalizeHost(host: String): String =
        host.trim().lowercase(Locale.US).trimEnd('.')

    private fun toHex(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4])
            out.append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

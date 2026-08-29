package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Сверяет наше рукопожатие WireGuard с эталоном.
 *
 * Вектор снят с `wireguard-go` (`device/noise-protocol.go` + `device/cookie.go`)
 * на фиксированных ключах, эфемерном ключе, номере отправителя и метке времени, и
 * проверен независимой реализацией на Python — обе дали ровно эти байты.
 *
 * Тест нужен потому, что WireGuard на неверный пакет **молчит**, ровно как на
 * недоступный узел. Без эталона «ответили 0 из 63» одинаково хорошо объяснялось и
 * блокировкой сети, и ошибкой в собственной криптографии, и на разделение этих
 * версий ушёл целый заход по устройству.
 */
class ProtonHandshakeVectorTest {

    private fun hex(value: String) = ByteArray(value.length / 2) {
        value.substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }

    @Test
    fun `initiation matches the wireguard-go vector`() {
        val staticPriv = hex("a01010101010101010101010101010101010101010101010101010101010101f")
        // Публичный ключ Cloudflare WARP — просто стабильное 32-байтное значение.
        val peerPub = hex("6e65ce0be17517110c17d77288ad87e7fd5252dcc7d09b95a39d61db03df832a")
        val ephPriv = hex("5011010101010101010101010101010101010101010101010101010101010177")
        val sender = hex("deadbeef")
        val timestamp = hex("400000000068aabb00000001")

        val expected =
            "01000000deadbeeffbf34a420f8196539fac3050351a0edd1db01863a2cf37f8c3a7cb583f32cd3f" +
                "b519a958870ca682ae3a896f3048649976246d7f46b656b0a3aefd1d3822e0e6f49737a329f16522" +
                "49abe7d51853da3d55221c9b8c64bb4586625a1146cd951721489c43a6a96171b95a285301d6525a" +
                "9f5c7f44cc942b34e06b636400000000000000000000000000000000"

        val actual = ProtonCrypto.buildInitiation(
            staticPriv = staticPriv,
            peerPub = peerPub,
            ephPriv = ephPriv,
            senderIndex = sender,
            timestamp = timestamp,
        )

        assertEquals(148, actual.size)
        assertEquals(expected, actual.joinToString("") { "%02x".format(it) })
    }

    /**
     * `I1` обязан быть полноразмерным.
     *
     * RFC 9000 §14.1: дейтаграмма с клиентским Initial не короче 1200 байт. Первая
     * версия отдавала ~150 байт, то есть невалидный QUIC — и это единственное, чем
     * наш профиль отличался от конфига сайта, который у владельца работает.
     */
    @Test
    fun `i1 is a full-size quic initial`() {
        val i1 = ProtonQuicInitial.buildI1("www.gosuslugi.ru")
        assert(i1.startsWith("<b 0x") && i1.endsWith(">")) { "неожиданный формат: ${i1.take(24)}" }
        val hex = i1.removePrefix("<b 0x").removeSuffix(">")
        assertEquals(1250, hex.length / 2)
        // Длинный заголовок QUIC v1: старший бит формы, версия 00000001, DCID 8 байт.
        // Защита заголовка правит только младшую тетраду (`mask[0] and 0x0F`), так
        // что старшая обязана быть ровно `0xC0`: форма 1, фиксированный бит 1, тип
        // Initial 00. Писать здесь `or 0xC0` нельзя — инфиксные вызовы в Kotlin
        // левоассоциативны и одного приоритета, выражение свернулось бы в
        // `(x or 0xC0) and 0xF0`, то есть в тождество, и проверка не падала бы даже
        // на коротком заголовке.
        assertEquals(0xC0, hex.substring(0, 2).toInt(16) and 0xF0)
        assertEquals("00000001", hex.substring(2, 10))
        assertEquals("08", hex.substring(10, 12))
    }
}

package com.example.nova

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сверяет наше рукопожатие WireGuard с эталоном.
 *
 * Векторы сняты с реализации на Python (`Nova PC resources/nova_wg_probe.py`,
 * пинится своими тестами) и сходятся с `wireguard-go`
 * (`tools/amneziawg-go/device/noise-protocol.go`) на фиксированных ключах,
 * эфемерном ключе, номере отправителя и метке времени.
 *
 * Тест нужен потому, что WireGuard на неверный пакет **молчит**, ровно как на
 * недоступный узел. И ровно этим тестом ошибку и закрепили: до 2026-09-16 он пинил
 * байты, снятые с нашей же опечатки в имени протокола
 * (`ChaCha20Poly1305` вместо `ChaChaPoly`), то есть подтверждал сам себя. Шестнадцать
 * заходов расследования объясняли «ответили 0 из 50» сетью, блокировкой и занятой
 * личностью, пока то же рукопожатие на ПК не ответило с правильным именем (G218).
 *
 * Отсюда правило: эталон обязан приходить **извне** нашей реализации.
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
                "1394507878a58581eac3f7621de3fb41689c90b8856f4072d2f4c7d87e76ff916d8677e418275b11" +
                "daa4140ead7871d7e4b1759bffa5a492e5eb9199eaa0c61c6d693bb65cee229e7389e03c34fbead9" +
                "fc62ce5c1a1acb56b9f469b500000000000000000000000000000000"

        val actual = ProtonCrypto.buildInitiation(
            staticPriv = staticPriv,
            peerPub = peerPub,
            ephPriv = ephPriv,
            senderIndex = sender,
            timestamp = timestamp,
        ).packet

        assertEquals(148, actual.size)
        assertEquals(expected, actual.joinToString("") { "%02x".format(it) })
    }

    /**
     * Ответ засчитывается, только если на нём сходится пустой AEAD.
     *
     * Вектор снят с той же реализации на Python: её `respond()` — это
     * `ConsumeMessageInitiation` + `CreateMessageResponse` из `wireguard-go`, с
     * фиксированным приватным ключом сервера, его эфемерным ключом и номером
     * отправителя, так что ответ воспроизводим до байта.
     *
     * Проверять обязательно: до этого проба засчитывала любой пакет с двойкой в
     * первом байте. Такой счётчик не отличает «сервер поднял бы сессию» от «что-то
     * прилетело» — а весь смысл рукопожатия именно в первом.
     */
    @Test
    fun `only an authenticated response counts`() {
        val staticPriv = hex("a01010101010101010101010101010101010101010101010101010101010101f")
        // Публичный ключ тестового сервера: его приватный — b0202020…2f.
        val peerPub = hex("1ec093b3c47bc90a4c15bf492f1ad64bca9b974742c9d962cbcb7ce1a09b7a0b")
        val ephPriv = hex("5011010101010101010101010101010101010101010101010101010101010177")
        val sender = hex("deadbeef")
        val timestamp = hex("400000000068aabb00000001")

        val initiation = ProtonCrypto.buildInitiation(
            staticPriv = staticPriv,
            peerPub = peerPub,
            ephPriv = ephPriv,
            senderIndex = sender,
            timestamp = timestamp,
        )
        assertEquals(
            "01000000deadbeeffbf34a420f8196539fac3050351a0edd1db01863a2cf37f8c3a7cb583f32cd3f" +
                "c964acb7a8e239b0dba1ae57a92c3a9004a0b85865b25e28d1ebc90fc3cefabff005bffbb0db1f0d" +
                "804912f9eb18c0cb9c40d5feb33c52f9f6549d72ef161c13c8d499cd96f42e9e5cc626a2b196654f" +
                "2a3b11415040ddd14ac3031400000000000000000000000000000000",
            initiation.packet.joinToString("") { "%02x".format(it) },
        )

        val response = hex(
            "0200000004030201deadbeef4c7a6ebbfa0750c9a21818f80c2a4da863b18c26b9020ed19b511b84" +
                "4475c90b415845e775eafd523c7e59c1f92ac64dca6b4816383355efab8b0fccf47e7a1c00000000" +
                "000000000000000000000000"
        )
        assertEquals(92, response.size)
        assertTrue(ProtonCrypto.verifyResponse(initiation, response, response.size))

        // Подделанная метка AEAD — тот же пакет, тот же тип, тот же индекс.
        val tampered = response.copyOf().also { it[44] = (it[44].toInt() xor 1).toByte() }
        assertFalse(ProtonCrypto.verifyResponse(initiation, tampered, tampered.size))

        // Ответ на чужую инициацию: индекс получателя не наш.
        val foreign = response.copyOf().also { it[8] = (it[8].toInt() xor 1).toByte() }
        assertFalse(ProtonCrypto.verifyResponse(initiation, foreign, foreign.size))

        // Обрезанный пакет: длина берётся из датаграммы, а не из размера буфера —
        // буфер у пробы всегда больше пришедшего.
        assertFalse(ProtonCrypto.verifyResponse(initiation, response, response.size - 1))
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

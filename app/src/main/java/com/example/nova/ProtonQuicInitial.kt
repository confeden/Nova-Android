package com.example.nova

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.SecureRandom

/**
 * Подделка QUIC Initial для параметра `I1` AmneziaWG.
 *
 * `I1` — это произвольный UDP-пакет, который клиент отправляет перед рукопожатием.
 * Стоковый WireGuard на той стороне его просто отбрасывает, поэтому приём годится и
 * для узлов Proton, которые про AmneziaWG ничего не знают. Смысл в первом пакете
 * потока: DPI видит не WireGuard, а корректный QUIC Initial с обычным SNI.
 *
 * Пакет собирается настоящий, по RFC 9001: соль версии 1, метки `client in` /
 * `quic key` / `quic iv` / `quic hp`, AES-GCM поверх CRYPTO-фрейма и защита
 * заголовка. Внутри — минимальный ClientHello, у которого единственное расширение
 * это `server_name`. Проверка «на глаз» ловится сразу, поэтому подделывать частично
 * бессмысленно: либо пакет разбирается как QUIC, либо он выглядит мусором.
 */
object ProtonQuicInitial {

    /** Соль QUIC v1 из RFC 9001 §5.2. */
    private val INITIAL_SALT = byteArrayOf(
        0x38, 0x76, 0x2c, 0xf7.toByte(), 0xf5.toByte(), 0x59, 0x34, 0xb3.toByte(), 0x4d, 0x17,
        0x9a.toByte(), 0xe6.toByte(), 0xa4.toByte(), 0xc8.toByte(), 0x0c, 0xad.toByte(),
        0xcc.toByte(), 0xbb.toByte(), 0x7f, 0x0a,
    )

    private val secureRandom = SecureRandom()

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    /** HKDF-Expand-Label, укороченный до одной итерации: всё нужное ≤ 32 байт. */
    private fun expandLabel(secret: ByteArray, length: Int, label: String): ByteArray {
        val full = "tls13 $label".toByteArray()
        val info = ByteArray(2 + 1 + full.size + 1 + 1)
        info[0] = ((length shr 8) and 0xFF).toByte()
        info[1] = (length and 0xFF).toByte()
        info[2] = full.size.toByte()
        System.arraycopy(full, 0, info, 3, full.size)
        info[3 + full.size] = 0
        info[4 + full.size] = 1
        return hmacSha256(secret, info).copyOf(length)
    }

    private fun varInt(value: Int): ByteArray = when {
        value < 0x40 -> byteArrayOf(value.toByte())
        value < 0x4000 -> byteArrayOf((((value shr 8) and 0xFF) or 0x40).toByte(), (value and 0xFF).toByte())
        else -> byteArrayOf(
            (((value shr 24) and 0xFF) or 0x80).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }

    private fun varIntLength(value: Int): Int = when {
        value < 0x40 -> 1
        value < 0x4000 -> 2
        else -> 4
    }

    private fun u16(value: Int) = byteArrayOf(((value shr 8) and 0xFF).toByte(), (value and 0xFF).toByte())

    private fun clientHello(sni: String): ByteArray {
        val name = sni.toByteArray()
        // server_name: list_len, name_type=0, host_len, host
        val serverNameList = u16(name.size + 3) + byteArrayOf(0) + u16(name.size) + name
        val sniExtension = u16(0) + u16(serverNameList.size) + serverNameList
        val extensions = u16(sniExtension.size) + sniExtension

        val random = ByteArray(32).also { secureRandom.nextBytes(it) }
        // legacy_version, random, session_id_len=0, cipher_suites_len=0, compression_len=0
        val body = byteArrayOf(0x03, 0x03) + random + byteArrayOf(0, 0, 0, 0) + extensions
        return byteArrayOf(
            0x01,
            ((body.size shr 16) and 0xFF).toByte(),
            ((body.size shr 8) and 0xFF).toByte(),
            (body.size and 0xFF).toByte(),
        ) + body
    }

    /**
     * Размер готового пакета.
     *
     * RFC 9000 §14.1 требует, чтобы дейтаграмма с клиентским Initial была **не
     * короче 1200 байт**, и настоящие клиенты набивают её примерно до 1250. Первая
     * версия отдавала ~150 байт без набивки — то есть заведомо невалидный QUIC,
     * который любой разборщик отбрасывает, а DPI видит как мусор. Проверенный
     * владельцем конфиг сайта несёт именно полноразмерный пакет.
     */
    private const val PAD_TO = 1250

    /** Реальные клиенты используют 8-байтный DCID; однобайтный сам по себе примета. */
    private const val DCID_SIZE = 8

    /**
     * @return значение для строки `I1` в формате AmneziaWG: `<b 0x…>`, либо пустая
     *         строка, если собрать пакет не удалось. Пустую строку вызывающий обязан
     *         трактовать как «обфускации нет», а не подставлять заглушку: `I1` с
     *         мусором внутри хуже, чем его отсутствие.
     */
    fun buildI1(sni: String): String {
        val host = sni.trim().trimEnd('.')
        if (host.isBlank() || host.length > 250) return ""
        return try {
            val dcid = ByteArray(DCID_SIZE).also { secureRandom.nextBytes(it) }
            val pkn = byteArrayOf(0)
            val hello = clientHello(host)
            // CRYPTO-фрейм: тип 0x06, смещение 0, длина, данные
            val payload = byteArrayOf(0x06) + varInt(0) + varInt(hello.size) + hello

            val tag = 16
            // Набивка считается так же, как в quicMeasureLengths: длина поля Length
            // сама зависит от набивки, поэтому подгоняется итеративно.
            val baseHeader = 8 + dcid.size + 0 + 0 + pkn.size
            fun overall(padding: Int): Int =
                baseHeader + varIntLength(pkn.size + payload.size + padding + tag) +
                    payload.size + padding + tag
            var padding = 0
            if (overall(0) < PAD_TO) {
                padding = PAD_TO - overall(0)
                while (padding > 0 && overall(padding) > PAD_TO) padding--
                if (overall(padding) < PAD_TO) padding++
            }
            // Хвост от номера пакета должен быть не меньше 20 байт — из него берётся
            // образец для защиты заголовка.
            if (pkn.size + payload.size + padding + tag < 20) {
                padding = 20 - pkn.size - payload.size - tag
            }
            val remainder = pkn.size + payload.size + padding + tag

            val header = byteArrayOf((0xC0 or (pkn.size - 1)).toByte(), 0, 0, 0, 1) +
                byteArrayOf(dcid.size.toByte()) + dcid +
                byteArrayOf(0) +   // scid: пустой
                byteArrayOf(0) +   // token: пустой
                varInt(remainder) + pkn

            val initialSecret = hmacSha256(INITIAL_SALT, dcid)
            val clientSecret = expandLabel(initialSecret, 32, "client in")
            val key = expandLabel(clientSecret, 16, "quic key")
            val iv = expandLabel(clientSecret, 12, "quic iv")
            val hp = expandLabel(clientSecret, 16, "quic hp")
            for (i in pkn.indices) {
                val at = iv.size - pkn.size + i
                iv[at] = (iv[at].toInt() xor pkn[i].toInt()).toByte()
            }

            val gcm = Cipher.getInstance("AES/GCM/NoPadding")
            gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            gcm.updateAAD(header)
            val encrypted = gcm.doFinal(payload + ByteArray(padding))

            val sampleOffset = 4 - pkn.size
            val sample = encrypted.copyOfRange(sampleOffset, sampleOffset + 16)
            val ecb = Cipher.getInstance("AES/ECB/NoPadding")
            ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(hp, "AES"))
            val mask = ecb.doFinal(sample)

            val protectedHeader = header.copyOf()
            protectedHeader[0] = (protectedHeader[0].toInt() xor (mask[0].toInt() and 0x0F)).toByte()
            for (i in pkn.indices) {
                val at = protectedHeader.size - pkn.size + i
                protectedHeader[at] = (protectedHeader[at].toInt() xor mask[1 + i].toInt()).toByte()
            }

            val packet = protectedHeader + encrypted
            val hex = StringBuilder(packet.size * 2)
            packet.forEach { hex.append(String.format("%02x", it)) }
            "<b 0x$hex>"
        } catch (e: Exception) {
            LogManager.log("Proton I1: не собрали QUIC Initial для $host — ${e.message}")
            ""
        }
    }
}

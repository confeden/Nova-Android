package com.example.nova

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Кодировщик проверяется независимым декодером: при заданном NOVA_QR_OUT (свойство или
 * переменная окружения) тест кладёт PNG каждого образца, а внешний скрипт их декодирует.
 */
class QrCodeTest {
    private val wgConfig = """
        [Interface]
        PrivateKey = yAnz5TF+lXXJte14tji3zlMNq+hd2rYUIgJBgB3fBmk=
        Address = 10.0.0.2/32
        DNS = 1.1.1.1
        MTU = 1280
        Jc = 4
        Jmin = 40
        Jmax = 70
        I1 = <b 0xc200000001><r 16><t>

        [Peer]
        PublicKey = xTIBA5rboUvnH4htodjb6e697QjLERt1NAB4mZqp8Dg=
        Endpoint = 162.159.192.1:2408
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """.trimIndent()

    private val samples = linkedMapOf(
        "ascii" to "hello",
        "wg" to wgConfig,
        "cyr" to "Привет, мир! Нова — VPN",
        "long" to "x".repeat(1000),
    )

    private fun outDir(): File? {
        val p = System.getProperty("NOVA_QR_OUT") ?: System.getenv("NOVA_QR_OUT")
        return p?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
    }

    private fun render(m: Array<BooleanArray>, file: File) {
        val quiet = 4
        val scale = 8
        val px = (m.size + quiet * 2) * scale
        // PNG пишем вручную: java.awt в unit-тестах Android недоступен.
        val raw = ByteArrayOutputStream()
        for (y in 0 until px) {
            raw.write(0) // фильтр строки: none
            val my = y / scale - quiet
            for (x in 0 until px) {
                val mx = x / scale - quiet
                val dark = mx in m.indices && my in m.indices && m[my][mx]
                raw.write(if (dark) 0 else 255)
            }
        }
        val z = ByteArrayOutputStream()
        DeflaterOutputStream(z).use { it.write(raw.toByteArray()) }
        val out = DataOutputStream(file.outputStream())
        fun chunk(type: String, body: ByteArray) {
            out.writeInt(body.size)
            val t = type.toByteArray(Charsets.US_ASCII)
            out.write(t)
            out.write(body)
            val crc = CRC32().apply { update(t); update(body) }
            out.writeInt(crc.value.toInt())
        }
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArrayOutputStream()
        DataOutputStream(ihdr).apply { writeInt(px); writeInt(px); write(8); write(0); write(0); write(0); write(0) }
        chunk("IHDR", ihdr.toByteArray())
        chunk("IDAT", z.toByteArray())
        chunk("IEND", ByteArray(0))
        out.close()
    }

    @Test
    fun `образцы кодируются и пишутся в PNG`() {
        val dir = outDir()
        val versions = StringBuilder()
        for ((name, text) in samples) {
            val m = QrCode.encode(text)
            val version = (m.size - 17) / 4
            assertEquals(0, (m.size - 17) % 4)
            assertTrue(version in 1..40)
            assertTrue(m.all { it.size == m.size })
            // Левый верхний искатель: рамка 7x7 тёмная, кольцо внутри светлое, центр тёмный
            assertTrue(m[0][0] && m[0][6] && m[6][0] && m[6][6] && !m[1][1] && m[3][3])
            versions.append("$name=v$version ")
            if (dir != null) {
                render(m, File(dir, "$name.png"))
                File(dir, "$name.txt").writeText(text, Charsets.UTF_8)
            }
        }
        println("QR versions: $versions")
        assertEquals(1, (QrCode.encode("hello").size - 17) / 4)
    }

    @Test
    fun `слишком длинный текст отвергается`() {
        try {
            QrCode.encode("x".repeat(3000))
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
    }
}

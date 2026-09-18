package com.example.nova

import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Цвета уведомления по акценту темы (`NovaNotificationPalette.derive`).
 *
 * Акценты берутся из самих ресурсов тем, а порог и формула контраста — из WCAG 2.x,
 * посчитанной здесь заново, а не функцией из кода (I28): тест должен поймать и
 * ошибку в самой формуле.
 */
class NovaNotificationPaletteTest {

    private fun themeAccents(): Map<String, Int> {
        val candidates = listOf(
            File("src/main/res/values/colors_nova_themes.xml"),
            File("app/src/main/res/values/colors_nova_themes.xml"),
        )
        val file = candidates.firstOrNull { it.isFile } ?: error("не найден colors_nova_themes.xml; искали: $candidates")
        val pattern = Regex("""<color name="nova_([a-z0-9]+)_accent">#([0-9A-Fa-f]{6})</color>""")
        return pattern.findAll(file.readText(Charsets.UTF_8))
            .associate { it.groupValues[1] to (0xFF000000.toInt() or it.groupValues[2].toInt(16)) }
    }

    private fun luminance(color: Int): Double {
        fun linear(value: Int): Double {
            val c = value / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * linear((color shr 16) and 0xFF) +
            0.7152 * linear((color shr 8) and 0xFF) +
            0.0722 * linear(color and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    private fun hue(color: Int): Double {
        val r = ((color shr 16) and 0xFF) / 255.0
        val g = ((color shr 8) and 0xFF) / 255.0
        val b = (color and 0xFF) / 255.0
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        if (delta == 0.0) return 0.0
        val h = when (max) {
            r -> ((g - b) / delta).mod(6.0)
            g -> (b - r) / delta + 2
            else -> (r - g) / delta + 4
        }
        return h * 60
    }

    private fun hueDistance(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }

    private fun hex(color: Int) = "#%08X".format(color)

    @Test
    fun everyThemeIsRead() {
        // Двенадцать тем — столько в `NovaTheme.ORDER`; меньше значит, что шаблон
        // имени цвета разошёлся с генератором тем и тест проверяет не всё.
        assertEquals(12, themeAccents().size)
    }

    @Test
    fun textIsReadableOnEveryTheme() {
        for ((theme, accent) in themeAccents()) {
            val p = NovaNotificationPalette.derive(accent)
            // WCAG AA для обычного текста — 4,5.
            assertTrue(
                "$theme: подпись кнопки ${hex(p.buttonText)} на ${hex(p.buttonFill)} = ${contrast(p.buttonText, p.buttonFill)}",
                contrast(p.buttonText, p.buttonFill) >= 4.5,
            )
            for (background in listOf(p.cardBase, p.cardGlow)) {
                assertTrue(
                    "$theme: заголовок карточки на ${hex(background)}",
                    contrast(p.cardTitle, background) >= 4.5,
                )
                assertTrue(
                    "$theme: подпись карточки ${hex(p.cardSubtitle)} на ${hex(background)}",
                    contrast(p.cardSubtitle, background) >= 4.5,
                )
                // Шеврон — значок, не текст: WCAG 1.4.11 требует 3.
                assertTrue(
                    "$theme: шеврон ${hex(p.cardChevron)} на ${hex(background)}",
                    contrast(p.cardChevron, background) >= 3.0,
                )
            }
        }
    }

    @Test
    fun fillStaysInTheAccentHue() {
        for ((theme, accent) in themeAccents()) {
            val p = NovaNotificationPalette.derive(accent)
            for ((name, color) in listOf("заливка" to p.buttonFill, "полоса" to p.cardGlow, "подпись" to p.buttonText)) {
                assertTrue(
                    "$theme: $name ${hex(color)} ушла от тона акцента ${hex(accent)}",
                    hueDistance(hue(color), hue(accent)) <= 10.0,
                )
            }
            assertEquals("$theme: обводка — чистый акцент", accent, p.buttonStroke)
        }
    }

    @Test
    fun colorsAreOpaqueEvenForTranslucentAccent() {
        val p = NovaNotificationPalette.derive(0x8050C878.toInt())
        for (color in listOf(p.accent, p.buttonFill, p.buttonText, p.cardBase, p.cardGlow, p.cardSubtitle, p.cardChevron)) {
            assertEquals("альфа ${hex(color)}", 0xFF, (color ushr 24) and 0xFF)
        }
    }

    @Test
    fun whiteAndBlackAccentsStillGiveReadableText() {
        for (accent in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFFFFFF00.toInt())) {
            val p = NovaNotificationPalette.derive(accent)
            assertTrue("${hex(accent)}: ${contrast(p.buttonText, p.buttonFill)}", contrast(p.buttonText, p.buttonFill) >= 4.5)
        }
    }
}

package com.example.nova

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dense digital rain with independent streams, speeds and per-cell glyph changes.
 * Short gaps depend on the tail length, so taller screens do not become emptier.
 * Glyphs share an alpha atlas; drawing creates no per-frame objects.
 */
internal class NovaMatrixRain private constructor(
    private val width: Int,
    private val height: Int,
    variant: Int,
) {

    private val seed = 0x4E4F5641 + variant * 6700417
    private val span = min(width, height).toFloat()
    private val cols: Int = max(1, ((width / span) / CELL).toInt())
    private val cellW: Float = width.toFloat() / cols
    private val cellH: Float = cellW * 1.2f
    private val rows: Int = (height / cellH).toInt() + 1

    /** Наибольший шлейф в рядах; он же задаёт период цикла колонки. */
    private val tailMax: Int = max(8, min(36, (rows * 0.6f).toInt()))

    private val atlas: Array<Bitmap>

    init {
        val glyphs = matrixGlyphs()
        val bw = max(1, kotlin.math.ceil(cellW.toDouble()).toInt())
        val bh = max(1, kotlin.math.ceil(cellH.toDouble()).toInt())
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.MONOSPACE
            textAlign = Paint.Align.CENTER
            textSize = cellH * 0.9f
        }
        atlas = Array(glyphs.size) { i ->
            val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ALPHA_8)
            Canvas(bitmap).drawText(glyphs[i], bw / 2f, cellH * 0.82f, textPaint)
            bitmap
        }
    }

    /**
     * Рисует кадр в координатах холста: колонки в `0..width`, ряды в `0..height`.
     *
     * [paint] задаёт цвет (через кисть, а не через фильтр), [alphaScale] — общую
     * прозрачность фактуры в долях единицы.
     */
    fun draw(canvas: Canvas, paint: Paint, time: Double, alphaScale: Float) {
        val t = time.toFloat()
        val glyphCount = atlas.size
        val ink = paint.color
        val headInk = Color.rgb(
            (Color.red(ink) + 255) / 2,
            (Color.green(ink) + 255) / 2,
            (Color.blue(ink) + 255) / 2,
        )
        for (c in 0 until cols) {
            val columnSeed = mix(seed + c * 73856093)
            val speed = 1.2f + 6.8f * unit(columnSeed, 1)
            val period = tailMax + 6f + 22f * unit(columnSeed, 4)
            val s = unit(columnSeed, 2) * period + speed * t
            val cycle = floor(s / period).toInt()
            val pos = s - cycle * period
            var stream = -1
            while (pos + stream * period < rows + tailMax) {
                val head = pos + stream * period
                val cycleSeed = mix(columnSeed + (cycle - stream) * 0x27D4EB2D)
                stream++
                if (unit(cycleSeed, 0) < EMPTY_CHANCE) continue
                val kind = unit(cycleSeed, 1)
                val power = when {
                    kind < 0.18f -> 0.45f + 0.2f * unit(cycleSeed, 3)
                    kind < 0.78f -> 0.75f + 0.2f * unit(cycleSeed, 3)
                    else -> 1f
                }
                val tail = 4 + (unit(cycleSeed, 2) * (tailMax - 4)).toInt()

                val headRow = floor(head).toInt()
                val x = c * cellW
                for (n in 0..tail) {
                    val row = headRow - n
                    if (row < 0) break
                    if (row >= rows) continue
                    val cellSeed = mix(columnSeed xor (row * 19349663))
                    // Символ меняется не разом: у каждой ячейки свой момент.
                    val rate = if (n == 0) 12f else 0.12f + 4.5f * unit(cellSeed, 7) * unit(cellSeed, 8)
                    val tick = floor(t * rate + unit(cellSeed, 9) * 7f).toInt()
                    val glyph = Math.floorMod(mix(cellSeed + tick * 83492791), glyphCount)
                    val alpha = if (n == 0) {
                        255f * power
                    } else {
                        val k = 1f - n.toFloat() / tail
                        255f * power * sqrt(k) * (0.65f + 0.35f * unit(cellSeed, 5))
                    }
                    if (n > 0 && alpha < 6f) continue
                    paint.color = if (n == 0) headInk else ink
                    paint.alpha = min(255f, alpha * alphaScale * 1.35f).toInt()
                    canvas.drawBitmap(atlas[glyph], x, row * cellH, paint)
                }
            }
        }
        paint.color = ink
        paint.alpha = 255
    }

    companion object {

        private const val CELL = 0.0185f
        private const val EMPTY_CHANCE = 0.04f

        /**
         * Несколько последних экземпляров: два вида разного размера во время перехода
         * не должны пересобирать атлас на каждом кадре.
         */
        private const val SHARED_LIMIT = 3
        private val shared = ArrayList<NovaMatrixRain>()

        /**
         * Общий экземпляр для размера и варианта: пересоздание вида берёт тот же атлас.
         * Строится на главном потоке, но дёшево (несколько десятков мелких глифов).
         */
        @Synchronized
        fun shared(width: Int, height: Int, variant: Int): NovaMatrixRain {
            val seed = 0x4E4F5641 + variant * 6700417
            val found = shared.firstOrNull { it.width == width && it.height == height && it.seed == seed }
            if (found != null) {
                shared.remove(found)
                shared.add(found)
                return found
            }
            val created = NovaMatrixRain(width, height, variant)
            shared.add(created)
            if (shared.size > SHARED_LIMIT) shared.removeAt(0)
            return created
        }

        /** Хеш целого числа в 32 бита; без аллокаций. */
        private fun mix(value: Int): Int {
            var h = value
            h = h xor (h ushr 16)
            h *= 0x7FEB352D
            h = h xor (h ushr 15)
            h *= -0x7B935975
            h = h xor (h ushr 16)
            return h
        }

        /** Число в [0, 1) от хеша и номера поля. */
        private fun unit(seed: Int, field: Int): Float =
            (mix(seed + field * 0x632BE5AB).ushr(8)) / 16777216f
    }
}

/**
 * Набор глифов дождя: полуширинная катакана (если шрифт её знает), цифры и знаки.
 *
 * Катакана проверяется через [Paint.hasGlyph]: без неё шрифт рисовал бы «тофу».
 */
internal fun matrixGlyphs(): List<String> {
    val probe = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    val glyphs = ArrayList<String>()
    val kana = (0xFF66..0xFF9D).map { it.toChar().toString() }.filter {
        try {
            probe.hasGlyph(it)
        } catch (_: Throwable) {
            false
        }
    }
    if (kana.size >= 24) glyphs.addAll(kana)
    for (ch in "0123456789") glyphs.add(ch.toString())
    for (ch in "ZTSXEH:.=*+<>|") glyphs.add(ch.toString())
    return glyphs
}

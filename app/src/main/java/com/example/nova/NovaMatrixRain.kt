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
 * Живой «цифровой дождь» для фактуры «Matrix»: колонки, по которым медленно ползёт
 * голова, а шлейф гаснет кверху.
 *
 * ## Без состояния
 *
 * Положение каждой колонки, её длина, яркость и символы выводятся из общего времени
 * ([NovaAnimationClock]) и хеш-функций от номера колонки и цикла. Поэтому дождь
 * не нужно хранить и переносить: пауза и возврат продолжают его с того же места,
 * а пересоздание вида ничего не теряет.
 *
 * ## Как ползёт колонка
 *
 * Фаза колонки растёт линейно, скорость своя у каждой колонки (медленно: полтора-два
 * ряда в секунду). Голова идёт сверху вниз, за нижним краем уходит шлейф, затем
 * колонка пустеет и цикл начинается заново с новой длиной и яркостью. Период
 * выбран так, что голова никогда не исчезает разом: сначала гаснет хвост, потом
 * колонка на время пустеет. Новые символы появляются у головы, старые гаснут сверху.
 *
 * ## Стоимость
 *
 * Глифы один раз рисуются в атлас (`ALPHA_8`, цвет даёт кисть), в кадр уходит
 * по одному `drawBitmap` на видимую ячейку: около тысячи вызовов при ~15 кадрах в
 * секунду. Никаких аллокаций в кадре нет.
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
    private val tailMax: Int = max(4, (rows * 0.6f).toInt())
    private val period: Float = (rows + 2 * tailMax + 2).toFloat()

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
        for (c in 0 until cols) {
            val columnSeed = mix(seed + c * 73856093)
            val speed = 0.7f + 1.5f * unit(columnSeed, 1)
            val s = unit(columnSeed, 2) * period + speed * t
            val cycle = floor(s / period).toInt()
            val pos = s - cycle * period
            val head = pos - (tailMax + 1)

            val cycleSeed = mix(columnSeed + cycle * 0x27D4EB2D)
            if (unit(cycleSeed, 0) < EMPTY_CHANCE) continue
            val kind = unit(cycleSeed, 1)
            val power = when {
                kind < 0.18f -> 0.22f + 0.18f * unit(cycleSeed, 3)
                kind < 0.78f -> 0.6f + 0.25f * unit(cycleSeed, 3)
                else -> 0.9f + 0.1f * unit(cycleSeed, 3)
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
                val tick = floor(t * GLYPH_CHANGE_RATE + unit(cellSeed, 9) * 7f).toInt()
                val glyph = Math.floorMod(mix(cellSeed + tick * 83492791), glyphCount)
                val alpha = if (n == 0) {
                    255f * power
                } else {
                    val k = 1f - n.toFloat() / tail
                    255f * power * k * sqrt(k) * (0.55f + 0.45f * unit(cellSeed, 5))
                }
                if (n > 0 && alpha < 6f) continue
                paint.alpha = min(255f, alpha * alphaScale).toInt()
                canvas.drawBitmap(atlas[glyph], x, row * cellH, paint)
            }
        }
        paint.alpha = 255
    }

    companion object {

        private const val CELL = 0.0185f
        private const val EMPTY_CHANCE = 0.12f
        private const val GLYPH_CHANGE_RATE = 0.6f

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

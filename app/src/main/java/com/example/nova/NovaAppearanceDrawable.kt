package com.example.nova

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.abs

/**
 * Фактура поверхности и температура оттенка — слой поверх фона темы.
 *
 * ## Один рисунок во весь экран, а не плитка
 *
 * Раньше здесь строилась плитка 256×256 и размножалась `BitmapShader`'ом. На
 * экране шириной 1080 точек она укладывается четырежды, и глаз читает не
 * поверхность, а мозаику из одинаковых квадратов; владелец так и сообщил —
 * «просто повторение заполнения, а квадраты маленькие».
 *
 * Теперь [NovaTexturePatterns] строит **сплошной** узор размером с экран. Это
 * снимает с узоров требование бесшовности, а вместе с ним и три ограничения,
 * которые и делали рисунок мозаичным: целые частоты, отрисовку примитивов по
 * девять раз и расстояния по тору. Подробности — в шапке [NovaTexturePatterns].
 *
 * ## Чем это платится и почему почти ничем
 *
 * Построением, и только им: миллион точек с восемью октавами — это доли секунды
 * на всех ядрах, и ровно один раз. Дальше рисунок живёт в кэше, общем на
 * процесс, и стоит одну заливку растром в кадр.
 *
 * Строится он **не в полном разрешении экрана**, а со стороной не длиннее той,
 * что задаёт [NovaTextureCache], и растягивается билинейно
 * (`FILTER_BITMAP_FLAG`). Причин две.
 * Во-первых, цена: полное разрешение — это вчетверо больше работы и вчетверо
 * больше памяти под кэш. Во-вторых, видеть разницу нечем: фактура ложится под
 * альфой 56/255, и мягкость растяжения в полтора раза под такой вуалью не
 * читается, тогда как повторяющийся квадрат читался сразу.
 *
 * ## Где живут готовые рисунки
 *
 * В [NovaTextureCache], а не здесь: тот же узор под тем же ключом берёт фон
 * главного экрана ([NovaMainBackdrop]), который слоя не создаёт вовсе.
 *
 * ## Цвет
 *
 * Растр хранит только прозрачность: рисунок белый, а цвет ему даёт акцент темы
 * через `PorterDuff.SRC_IN`. Поэтому один и тот же рисунок годится всем
 * двенадцати темам, и смена темы кэш не сбрасывает.
 *
 * ## Температура
 *
 * Отдельный слой поверх фактуры: тёплый край — янтарь, холодный — лёд, ноль —
 * ничего. Прозрачность растёт линейно до 14 % на краю шкалы: это оттенок, под
 * которым читается текст, а не светофильтр.
 */
class NovaAppearanceDrawable(
    private val texture: NovaAppearance.Texture,
    private val temperature: Int,
    private val accent: Int,
    private val variant: Int,
) : Drawable() {

    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var washShader: Shader? = null

    private var pattern: Bitmap? = null

    /** Размер, который уже заказан. Повторный заказ того же — это заказ на каждый кадр. */
    private var requested: NovaTextureCache.Key? = null

    init {
        // Цвет задаётся и краске, и фильтру. Фильтр красит растр, у которого
        // есть цветовые каналы; цвет краски нужен на случай, если растр окажется
        // маской без цвета. Один из двух путей всегда лишний, но какой именно —
        // зависит от того, чем растр обернулся, и проверять это в каждом кадре
        // дороже, чем задать оба.
        texturePaint.color = accent
        texturePaint.colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
        texturePaint.alpha = TEXTURE_ALPHA
    }

    /**
     * Размер известен только здесь.
     *
     * Прежняя версия заказывала плитку в конструкторе: плитке размер экрана был
     * не нужен. Сплошному рисунку нужен, а в конструкторе границы ещё пусты —
     * поэтому заказ ушёл сюда.
     */
    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        washShader = buildWash(bounds)
        requestPattern(bounds)
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        // Границы могли приехать до первой отрисовки, а могли и нет: у окна
        // фон выставляют раньше, чем измеряют. Заказ здесь стоит одно сравнение
        // и снимает зависимость от порядка.
        requestPattern(bounds)
        pattern?.let { bitmap ->
            if (!bitmap.isRecycled) canvas.drawBitmap(bitmap, null, bounds, texturePaint)
        }
        washShader?.let { shader ->
            washPaint.shader = shader
            canvas.drawRect(bounds, washPaint)
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    /**
     * Заказывает рисунок под текущий размер.
     *
     * Готовый приходит прямо здесь (он в кэше), иначе — посылкой на главный
     * поток, когда фоновый поток его достроит. Первый кадр без узора никто не
     * замечает, а рывок на главном потоке заметили бы все: самый подробный узор
     * — это доли секунды, то есть десятки пропущенных кадров ровно в тот момент,
     * когда человек нажал на фактуру.
     */
    private fun requestPattern(bounds: Rect) {
        if (texture == NovaAppearance.Texture.NONE) return
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return
        val key = NovaTextureCache.keyFor(texture, width, height, variant)
        if (key == requested) return
        requested = key
        val ready = NovaTextureCache.request(key) { built ->
            // Приходит из фонового потока: виды живут на главном.
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (requested == key) {
                    pattern = built
                    invalidateSelf()
                }
            }
        }
        if (ready != null) {
            pattern = ready
            invalidateSelf()
        }
    }

    /**
     * Температурная заливка: сверху насыщеннее, книзу сходит на нет.
     *
     * Ровная заливка во весь экран читается как плёнка поверх приложения;
     * градиент — как освещение, а именно им температура и является.
     */
    private fun buildWash(bounds: Rect): Shader? {
        if (temperature == NovaAppearance.TEMPERATURE_NEUTRAL || bounds.isEmpty) return null
        val strength = abs(temperature).coerceAtMost(100) / 100f
        val tint = if (temperature < 0) WARM_TINT else COLD_TINT
        val top = Color.argb(
            (strength * WASH_MAX_ALPHA).toInt().coerceIn(0, 255),
            Color.red(tint),
            Color.green(tint),
            Color.blue(tint),
        )
        val bottom = Color.argb(
            (strength * WASH_MAX_ALPHA * 0.35f).toInt().coerceIn(0, 255),
            Color.red(tint),
            Color.green(tint),
            Color.blue(tint),
        )
        return LinearGradient(
            0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
            top, bottom,
            Shader.TileMode.CLAMP,
        )
    }

    companion object {

        private const val TEXTURE_ALPHA = 56
        private const val WASH_MAX_ALPHA = 36f

        private val WARM_TINT = Color.rgb(255, 168, 74)
        private val COLD_TINT = Color.rgb(96, 170, 255)
    }
}

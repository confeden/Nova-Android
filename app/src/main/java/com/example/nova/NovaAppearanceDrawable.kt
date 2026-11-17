package com.example.nova

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock

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
 * всем оттенкам, и смена оттенка кэш не сбрасывает.
 *
 * ## Яркость и насыщенность
 *
 * Яркость — прозрачность узора ([textureAlpha]), насыщенность — цвет, которым он
 * окрашен ([accent]). Оба приходят снаружи готовыми числами: сам слой ничего не
 * читает из настроек и поэтому не знает, откуда взялся его вид.
 */
class NovaAppearanceDrawable(
    private val texture: NovaAppearance.Texture,
    private val accent: Int,
    private val variant: Int,
    /** Прозрачность растрового узора 0..255 (ползунок яркости). */
    private val textureAlpha: Int = TEXTURE_ALPHA,
    /** Залить чёрным под дождём: главный экран так и делал растром. */
    private val blackBase: Boolean = false,
    /** Прозрачность дождя; по умолчанию как у вуали на экранах настроек. */
    private val rainAlpha: Int = TEXTURE_ALPHA,
    /**
     * Живой дождь: кадры заказывает сам drawable, время идёт по часам. Экраны
     * настроек оставляют `false` и рисуют один кадр в зафиксированном времени: без
     * заказов кадров и без CPU, пока экран открыт.
     */
    val animated: Boolean = false,
) : Drawable() {

    private val blackPaint = Paint().apply { color = Color.BLACK }

    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frozenRainPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN)
        alpha = rainAlpha
    }
    private val frozenRainTimeMillis = (NovaAnimationClock.peek() * 1000.0).toLong()

    /** Следующий кадр живого дождя. */
    private val frameTick = Runnable { invalidateSelf() }

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
        texturePaint.alpha = textureAlpha
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
        requestPattern(bounds)
    }

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty) return
        if (texture == NovaAppearance.Texture.MATRIX) {
            drawMatrixLayer(canvas, 1f, 0f, 0f, 255, frameTime())
            requestNextRainFrame()
            return
        }
        // Границы могли приехать до первой отрисовки, а могли и нет: у окна
        // фон выставляют раньше, чем измеряют. Заказ здесь стоит одно сравнение
        // и снимает зависимость от порядка.
        requestPattern(bounds)
        pattern?.let { bitmap ->
            if (!bitmap.isRecycled) canvas.drawBitmap(bitmap, null, bounds, texturePaint)
        }
    }

    /**
     * Останавливает заказ следующего кадра. Зовёт вид, когда его отсоединяют или
     * прячут: без этого дождь продолжал бы заказывать кадры в пустоту. Возврат
     * подхватывает то же место — время живёт в [NovaAnimationClock].
     */
    fun stopAnimation() {
        unscheduleSelf(frameTick)
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
    /**
     * Время кадра: живой дождь берёт его из часов, статичный — фиксированное.
     * Статичный кадр часов не двигает: открыть настройки не сдвигает дождь на главном.
     */
    fun frameTime(): Double = if (animated) NovaAnimationClock.now() else NovaAnimationClock.peek()

    /**
     * Рисует весь слой «Matrix» (чёрная основа, дождь, температурная заливка) с
     * масштабом и прозрачностью — тем же преобразованием, что погружение применяет
     * к растровым слоям ([BackdropRevealImageView]). Кадры не заказывает: это делает
     * [requestNextRainFrame] по желанию вызывающего.
     *
     * [pivotX]/[pivotY] — центр масштаба; [alpha] 0..255 — слой целиком, как
     * `Paint.alpha` у растра, через offscreen-слой, а не по отдельным элементам.
     */
    fun drawMatrixLayer(
        canvas: Canvas,
        scale: Float,
        pivotX: Float,
        pivotY: Float,
        alpha: Int,
        time: Double,
    ) {
        val bounds = bounds
        if (bounds.isEmpty || alpha <= 0 || texture != NovaAppearance.Texture.MATRIX) return
        val checkpoint = canvas.save()
        if (scale != 1f) canvas.scale(scale, scale, pivotX, pivotY)
        // Слой без границ: масштабированное содержимое выходит за прямоугольник вида.
        if (alpha < 255) canvas.saveLayerAlpha(null, alpha)
        if (blackBase) canvas.drawRect(bounds, blackPaint)
        if (!animated || !NovaAnimationClock.isRunning()) {
            val frameMillis = if (animated) (time * 1000.0).toLong() else frozenRainTimeMillis
            requestPattern(bounds, frameMillis)
            pattern?.let { bitmap ->
                if (!bitmap.isRecycled) canvas.drawBitmap(bitmap, null, bounds, frozenRainPaint)
            }
        } else {
            drawRain(canvas, bounds, time)
        }
        canvas.restoreToCount(checkpoint)
    }

    /**
     * Заказывает следующий кадр живого дождя. Без `animated` и на остановленных
     * часах ничего не заказывает: пауза и статичные экраны не тратят CPU.
     */
    fun requestNextRainFrame() {
        if (!animated || !NovaAnimationClock.isRunning()) return
        unscheduleSelf(frameTick)
        scheduleSelf(frameTick, SystemClock.uptimeMillis() + RAIN_FRAME_MS)
    }

    /**
     * Живой дождь «Matrix»: кадр рисуется по времени, которое дал вызывающий. Сам
     * заказ следующего кадра — в [requestNextRainFrame].
     */
    private fun drawRain(canvas: Canvas, bounds: Rect, time: Double) {
        val rain = NovaMatrixRain.shared(bounds.width(), bounds.height(), variant)
        rainPaint.color = accent
        canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top.toFloat())
        rain.draw(canvas, rainPaint, time, rainAlpha / 255f)
        canvas.restore()
    }

    private fun requestPattern(bounds: Rect, frameTimeMillis: Long? = null) {
        if (!texture.hasPattern) return
        if (texture == NovaAppearance.Texture.MATRIX && frameTimeMillis == null) return
        val width = bounds.width()
        val height = bounds.height()
        if (width <= 0 || height <= 0) return
        val key = NovaTextureCache.keyFor(texture, width, height, variant, frameTimeMillis)
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

    companion object {

        /** Прозрачность узора на экранах настроек при яркости по умолчанию. */
        const val TEXTURE_ALPHA = 56

        /** Около пятнадцати кадров в секунду: дождю не нужна плавность выше, а CPU нужен экономный. */
        private const val RAIN_FRAME_MS = 66L
    }
}

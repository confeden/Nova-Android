package com.example.nova

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.ColorFilter
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.hypot

/** Мелочи экрана «Оформление»: имена оттенков, плитки фактур, радужная дорожка. */
internal object NovaAppearanceUi {

    /** Название оттенка для подписи; границы — по цветовому кругу в градусах. */
    fun hueName(hue: Int): String {
        val degrees = hue * 360f / NovaAppearance.HUE_COUNT
        return when {
            degrees < 15f || degrees >= 345f -> "Красный"
            degrees < 45f -> "Оранжевый"
            degrees < 70f -> "Жёлтый"
            degrees < 95f -> "Лаймовый"
            degrees < 150f -> "Зелёный"
            degrees < 165f -> "Изумрудный"
            degrees < 185f -> "Бирюзовый"
            degrees < 205f -> "Голубой"
            degrees < 245f -> "Синий"
            degrees < 270f -> "Индиго"
            degrees < 295f -> "Фиолетовый"
            degrees < 325f -> "Пурпурный"
            else -> "Розовый"
        }
    }

    fun dp(context: Context, value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)
}

/**
 * Миниатюры фактур для плиток.
 *
 * Строятся не в полном размере экрана, а в маленьком растре, в фоне и по одному:
 * шестнадцать плиток не должны занять главный поток ни на кадр. Как и большие узоры,
 * растр хранит только прозрачность, а цвет даёт плитка — поэтому смена оттенка или
 * насыщенности миниатюры не перестраивает.
 */
internal object NovaTextureThumbs {

    private const val WIDTH = 220
    private const val HEIGHT = 240
    private const val LIMIT = 48

    private val cache = object : LinkedHashMap<Pair<NovaAppearance.Texture, Int>, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<Pair<NovaAppearance.Texture, Int>, Bitmap>?,
        ): Boolean = size > LIMIT
    }
    private val pending = HashSet<Pair<NovaAppearance.Texture, Int>>()
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "NovaTextureThumbs").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }
    private val main = Handler(Looper.getMainLooper())

    fun cached(texture: NovaAppearance.Texture, variant: Int): Bitmap? =
        synchronized(cache) { cache[texture to variant] }

    /** Готовое — сразу, иначе строит в фоне и зовёт [onReady] на главном потоке. */
    fun request(texture: NovaAppearance.Texture, variant: Int, onReady: (Bitmap) -> Unit) {
        val key = texture to variant
        cached(texture, variant)?.let { onReady(it); return }
        synchronized(cache) { if (!pending.add(key)) return }
        worker.execute {
            val built = runCatching { NovaTexturePatterns.render(texture, WIDTH, HEIGHT, variant) }.getOrNull()
            synchronized(cache) {
                pending.remove(key)
                if (built != null) cache[key] = built
            }
            if (built != null) main.post { onReady(built) }
        }
    }
}

/**
 * Плитка фактуры: миниатюра, которую можно нажать, чтобы применить стиль сразу.
 *
 * Рисует сама, без `ImageView` и без фоновых ресурсов: узор — это маска
 * ([NovaTextureThumbs]), цвет ему даёт оттенок с насыщенностью, прозрачность —
 * яркость. «Созвездия» и «Без фона» растра не имеют и рисуются тут же: точки с
 * линиями и перечёркнутый круг.
 */
internal class TextureTileView(context: Context, val texture: NovaAppearance.Texture) : View(context) {

    var tint: Int = Color.WHITE
    var alphaFactor: Float = 1f
    var ground: Int = Color.BLACK
    var selectionColor: Int = Color.WHITE
    var mask: Bitmap? = null
    var chosen: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val density = resources.displayMetrics.density
    private val radius = 12f * density
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val clip = Path()
    private val rect = RectF()
    private var constellation: List<NovaConstellation.Point>? = null

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * 1.1f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val inset = 1.5f * density
        rect.set(inset, inset, w - inset, h - inset)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val checkpoint = canvas.save()
        canvas.clipPath(clip)
        fillPaint.color = ground
        canvas.drawRect(rect, fillPaint)
        when (texture) {
            NovaAppearance.Texture.CONSTELLATION -> drawConstellation(canvas, w, h)
            NovaAppearance.Texture.NONE -> drawNone(canvas, w, h)
            else -> mask?.takeIf { !it.isRecycled }?.let { bitmap ->
                maskPaint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.SRC_IN)
                // Миниатюра светлее фона настроек: плитка мала, и вуаль в 22 % на ней не читается.
                maskPaint.alpha = (PREVIEW_ALPHA * alphaFactor).toInt().coerceIn(0, 255)
                canvas.drawBitmap(bitmap, null, rect, maskPaint)
            }
        }
        canvas.restoreToCount(checkpoint)
        if (chosen) {
            strokePaint.color = selectionColor
            strokePaint.strokeWidth = 2.5f * density
        } else {
            strokePaint.color = if (isFocused) selectionColor else 0x24FFFFFF
            strokePaint.strokeWidth = if (isFocused) 2f * density else 1f * density
        }
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    private fun drawConstellation(canvas: Canvas, w: Float, h: Float) {
        val points = constellation
            ?: NovaConstellation.build(w.toInt().coerceAtLeast(8), h.toInt().coerceAtLeast(8), 1f)
                .take(13).also { constellation = it }
        val reach = w * 0.52f
        linePaint.strokeWidth = 1f * density
        for (i in points.indices) for (j in i + 1 until points.size) {
            val a = points[i]
            val b = points[j]
            val distance = hypot(a.x - b.x, a.y - b.y)
            if (distance < reach) {
                val strength = 1f - distance / reach
                linePaint.color = withAlpha(tint, (150 * strength * alphaFactor).toInt())
                canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
            }
        }
        fillPaint.color = withAlpha(tint, (235 * alphaFactor.coerceAtMost(1.2f)).toInt())
        for (p in points) canvas.drawCircle(p.x, p.y, (1.6f + p.radius) * density * 0.8f, fillPaint)
    }

    private fun drawNone(canvas: Canvas, w: Float, h: Float) {
        linePaint.strokeWidth = 2f * density
        linePaint.color = 0x59FFFFFF
        val cx = w / 2f
        val cy = h / 2f
        val r = minOf(w, h) * 0.2f
        canvas.drawCircle(cx, cy, r, linePaint)
        canvas.drawLine(cx - r * 0.7f, cy + r * 0.7f, cx + r * 0.7f, cy - r * 0.7f, linePaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    private companion object {
        const val PREVIEW_ALPHA = 150
    }
}

/**
 * Радужная дорожка ползунка оттенка: те же цвета, что у акцента (`S 0.78, L 0.54`), по
 * всему кругу. Рисуется шейдером, а не растром.
 */
internal class RainbowTrackDrawable(private val heightPx: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onBoundsChange(bounds: android.graphics.Rect) {
        super.onBoundsChange(bounds)
        val steps = 13
        val colors = IntArray(steps) { i ->
            val hue = NovaAppearance.HUE_COUNT * i / (steps - 1)
            NovaAppearance.surfaceColor(hue.coerceAtMost(NovaAppearance.HUE_COUNT), NovaAppearance.SATURATION_DEFAULT)
        }
        paint.shader = LinearGradient(
            bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f,
            colors, null, Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val cy = (b.top + b.bottom) / 2f
        rect.set(b.left.toFloat(), cy - heightPx / 2f, b.right.toFloat(), cy + heightPx / 2f)
        canvas.drawRoundRect(rect, heightPx / 2f, heightPx / 2f, paint)
    }

    override fun getIntrinsicHeight(): Int = heightPx

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

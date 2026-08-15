package com.example.nova

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

class StrokeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var strokeColor: Int = Color.TRANSPARENT
    private var strokeWidthPx: Float = 0f
    private var strokeEnabled: Boolean = false
    private var fillGlowRadiusPx: Float = 0f
    private var fillGlowColor: Int = Color.TRANSPARENT
    private var fillGlowDx: Float = 0f
    private var fillGlowDy: Float = 0f
    private var strokeGlowRadiusPx: Float = 0f
    private var strokeGlowColor: Int = Color.TRANSPARENT

    private val pillBlurPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pillRect = RectF()
    private var pillFillColor: Int = Color.TRANSPARENT
    private var pillGlowColor: Int = Color.TRANSPARENT
    private var pillEnabled: Boolean = false
    private var pillInsetX: Float = dp(4f)
    private var pillInsetY: Float = dp(6f)
    private var pillBlurRadius: Float = dp(12f)
    private var pillOvalGlow: Boolean = false

    init {
        paint.isAntiAlias = true
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeMiter = 10f
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setStroke(width: Float, color: Int) {
        strokeWidthPx = width * resources.displayMetrics.density
        strokeColor = color
        strokeEnabled = true
        invalidate()
    }

    fun setGlow(radius: Float, color: Int, dx: Float = 0f, dy: Float = 0f) {
        fillGlowRadiusPx = dp(radius)
        fillGlowColor = color
        fillGlowDx = dp(dx)
        fillGlowDy = dp(dy)
        invalidate()
    }

    fun setStrokeGlow(radius: Float, color: Int) {
        strokeGlowRadiusPx = dp(radius)
        strokeGlowColor = color
        invalidate()
    }

    fun setPillStyle(
        fillColor: Int,
        glowColor: Int,
        innerColor: Int = fillColor,
        insetX: Float = 4f,
        insetY: Float = 6f,
        blurRadius: Float = 12f,
        ovalGlow: Boolean = false,
    ) {
        pillFillColor = fillColor
        pillGlowColor = glowColor
        pillInsetX = dp(insetX)
        pillInsetY = dp(insetY)
        pillBlurRadius = dp(blurRadius)
        pillOvalGlow = ovalGlow
        pillEnabled = Color.alpha(fillColor) > 0 || Color.alpha(glowColor) > 0
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (pillEnabled) {
            if (pillOvalGlow) {
                drawLineHalo(canvas)
            } else {
                drawPill(canvas)
            }
        }

        val originalColor = currentTextColor
        val textPaint = paint

        if (strokeEnabled && strokeWidthPx > 0f) {
            val oldStyle = textPaint.style
            val oldWidth = textPaint.strokeWidth

            textPaint.style = Paint.Style.STROKE
            textPaint.strokeWidth = strokeWidthPx
            textPaint.strokeJoin = Paint.Join.ROUND
            textPaint.strokeMiter = 10f
            textPaint.setShadowLayer(strokeGlowRadiusPx, 0f, 0f, strokeGlowColor)
            super.setTextColor(strokeColor)
            super.onDraw(canvas)

            textPaint.style = oldStyle
            textPaint.strokeWidth = oldWidth
        }

        textPaint.setShadowLayer(fillGlowRadiusPx, fillGlowDx, fillGlowDy, fillGlowColor)
        super.setTextColor(originalColor)
        super.onDraw(canvas)
        textPaint.clearShadowLayer()
    }

    /**
     * Мягкое облако света с ядром вдоль строки — как у неоновой вывески.
     *
     * Три подхода до этого были неверны каждый по-своему, и все три различимы на
     * тёмном фоне: стопка скруглённых прямоугольников давала ступеньки на стыках
     * слоёв (видны как полосы); радиальный градиент — яркую точку в геометрическом
     * центре; размытие самих букв — ореол по форме глифов, то есть подсветку букв, а
     * не фона под ними.
     *
     * Вывеска светит трубкой: источник вытянут вдоль строки, а не собран в точку.
     * Поэтому ядро здесь — узкая горизонтальная полоса по средней линии текста, и
     * она размывается всё шире с падающей непрозрачностью. Свет ложится на фон
     * из-под букв, оставляя сам текст нетронутым.
     */
    private fun drawLineHalo(canvas: Canvas) {
        if (Color.alpha(pillGlowColor) <= 0) return
        val red = Color.red(pillGlowColor)
        val green = Color.green(pillGlowColor)
        val blue = Color.blue(pillGlowColor)
        val baseAlpha = Color.alpha(pillGlowColor)

        val rawText = text?.toString().orEmpty().ifBlank { " " }
        val textWidth = paint.measureText(rawText)
        val fontMetrics = paint.fontMetrics
        val textHeight = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(dp(18f))
        val cx = width / 2f
        val cy = height / 2f

        // Ядро — капсула по строке, тех же пропорций, что были в 1.26: свет идёт
        // линией из-под букв, а не точкой из середины.
        val coreHalfW = textWidth / 2f + dp(12f)
        val coreHalfH = textHeight / 2f + dp(6f)
        val core = RectF(cx - coreHalfW, cy - coreHalfH, cx + coreHalfW, cy + coreHalfH)
        val coreRadius = core.height() / 2f

        pillBlurPaint.reset()
        pillBlurPaint.isAntiAlias = true
        pillBlurPaint.isDither = true
        pillBlurPaint.style = Paint.Style.FILL

        // Сила и охват — как в 1.26: у ядра почти полная непрозрачность, у самого
        // дальнего слоя размытие во всю заданную величину. Отличие только в числе
        // ступеней: там их было четыре, и на стыках яркость менялась скачком —
        // отсюда полосы. Здесь двенадцать, с плавно спадающей кривой, поэтому
        // переход к краю сплошной.
        val layers = arrayOf(
            floatArrayOf(1.00f, 0.14f),
            floatArrayOf(0.84f, 0.18f),
            floatArrayOf(0.70f, 0.23f),
            floatArrayOf(0.58f, 0.29f),
            floatArrayOf(0.47f, 0.36f),
            floatArrayOf(0.37f, 0.44f),
            floatArrayOf(0.29f, 0.53f),
            floatArrayOf(0.22f, 0.63f),
            floatArrayOf(0.16f, 0.73f),
            floatArrayOf(0.11f, 0.83f),
            floatArrayOf(0.07f, 0.92f),
            floatArrayOf(0.04f, 1.00f),
        )
        val widest = pillBlurRadius
        for (layer in layers) {
            val blur = (widest * layer[0]).coerceAtLeast(1f)
            val alpha = (baseAlpha * layer[1]).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue
            // Форма у всех слоёв одна, меняется только размытие.
            //
            // Когда полоса расширялась вместе с радиусом, у каждого слоя был свой
            // силуэт, и на дальнем крае облака проступали кольца — тем заметнее, чем
            // ярче свечение. При общей форме размытия разного радиуса накладываются
            // друг на друга без границ, а объём даёт сам радиус.
            pillBlurPaint.color = Color.argb(alpha, red, green, blue)
            pillBlurPaint.maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            canvas.drawRoundRect(core, coreRadius, coreRadius, pillBlurPaint)
        }
        pillBlurPaint.maskFilter = null
    }

    private fun drawPill(canvas: Canvas) {
        val rawText = text?.toString().orEmpty().ifBlank { " " }
        val textWidth = paint.measureText(rawText)
        val fontMetrics = paint.fontMetrics
        val textHeight = (fontMetrics.descent - fontMetrics.ascent).coerceAtLeast(dp(18f))

        val cx = width / 2f
        val cy = height / 2f

        // Базовый прямоугольник, слегка больше текста
        val halfW = textWidth / 2f + dp(12f)
        val halfH = textHeight / 2f + dp(6f)

        val rect = RectF(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
        val cornerRadius = rect.height() / 2f

        pillBlurPaint.reset()
        pillBlurPaint.isAntiAlias = true
        pillBlurPaint.style = Paint.Style.FILL

        if (Color.alpha(pillGlowColor) > 0) {
            run {
                pillBlurPaint.color = pillGlowColor
                pillBlurPaint.maskFilter = BlurMaskFilter(pillBlurRadius, BlurMaskFilter.Blur.NORMAL)
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, pillBlurPaint)
                pillBlurPaint.maskFilter = null
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

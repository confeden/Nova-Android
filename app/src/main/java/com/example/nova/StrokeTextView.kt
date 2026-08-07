package com.example.nova

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
            drawPill(canvas)
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
            val red = Color.red(pillGlowColor)
            val green = Color.green(pillGlowColor)
            val blue = Color.blue(pillGlowColor)
            val baseAlpha = Color.alpha(pillGlowColor)

            if (pillOvalGlow) {
                // Равномерное свечение по форме текста (Stadium / Capsule)
                // 4 слоя от огромного/прозрачного до маленького/яркого
                val layers = arrayOf(
                    floatArrayOf(dp(16f), dp(56f), 0.25f), // Outer soft glow
                    floatArrayOf(dp(8f),  dp(28f), 0.50f), // Mid soft glow
                    floatArrayOf(dp(0f),  dp(12f), 0.90f), // Inner bright glow
                    floatArrayOf(dp(-4f), dp(4f),  1.00f)  // Core brightest
                )

                for (layer in layers) {
                    val expand = layer[0]
                    val blur = layer[1]
                    val alphaScale = layer[2]

                    val layerRect = RectF(
                        rect.left - expand,
                        rect.top - expand,
                        rect.right + expand,
                        rect.bottom + expand
                    )
                    val layerRadius = layerRect.height() / 2f
                    
                    pillBlurPaint.color = Color.argb((baseAlpha * alphaScale).toInt().coerceIn(0, 255), red, green, blue)
                    pillBlurPaint.maskFilter = BlurMaskFilter(blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
                    canvas.drawRoundRect(layerRect, layerRadius, layerRadius, pillBlurPaint)
                }
                pillBlurPaint.maskFilter = null
            } else {
                pillBlurPaint.color = pillGlowColor
                pillBlurPaint.maskFilter = BlurMaskFilter(pillBlurRadius, BlurMaskFilter.Blur.NORMAL)
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, pillBlurPaint)
                pillBlurPaint.maskFilter = null
            }
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

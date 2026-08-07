package com.example.nova

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatButton

class GlowPillButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle
) : AppCompatButton(context, attrs, defStyleAttr) {

    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var fillColor: Int = Color.argb(54, 220, 208, 255)
    private var glowColor: Int = Color.argb(172, 220, 208, 255)
    private var insetX: Float = dp(10f)
    private var insetY: Float = dp(8f)
    private var blurRadius: Float = dp(11f)

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        background = null
        stateListAnimator = null
        isAllCaps = true
    }

    fun setPillStyle(
        fillColor: Int,
        glowColor: Int,
        highlightColor: Int = fillColor,
        insetX: Float = 10f,
        insetY: Float = 8f,
        blurRadius: Float = 11f,
    ) {
        this.fillColor = withAlpha(fillColor, 58)
        this.glowColor = glowColor
        this.insetX = dp(insetX)
        this.insetY = dp(insetY)
        this.blurRadius = dp(blurRadius)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        drawPill(canvas)
        super.onDraw(canvas)
    }

    private fun drawPill(canvas: Canvas) {
        rect.set(insetX, insetY, width - insetX, height - insetY)
        if (rect.width() <= 0f || rect.height() <= 0f) return

        val radius = rect.height() / 2f

        blurPaint.reset()
        blurPaint.isAntiAlias = true
        blurPaint.style = Paint.Style.FILL
        blurPaint.color = glowColor
        blurPaint.maskFilter = BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(rect, radius, radius, blurPaint)

        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = fillColor
        canvas.drawRoundRect(rect, radius, radius, fillPaint)
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color),
        )
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}

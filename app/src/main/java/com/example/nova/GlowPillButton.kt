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
) : AppCompatButton(context, attrs, defStyleAttr), NovaLanguage.LocalizedText {

    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val focusRect = RectF()
    private val focusGlowFilter by lazy { BlurMaskFilter(dp(6f), BlurMaskFilter.Blur.NORMAL) }

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
        if (isFocused) drawFocusRing(canvas)
        super.onDraw(canvas)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        // Фокус рисуется в onDraw, а не подложкой: подложки у кнопки нет вовсе.
        invalidate()
    }

    /**
     * Рамка фокуса для пульта (приставки и телевизоры).
     *
     * Указателя на телевизоре нет, и мягкий ореол кнопки не отличается от
     * ореола в покое — пользователи приставок не видели, что выбрана главная
     * кнопка. Поэтому рамка двойная и контрастная на любой теме: белая линия
     * поверх зелёного свечения, снаружи от тела кнопки — цвет фона под ней
     * не важен.
     */
    private fun drawFocusRing(canvas: Canvas) {
        val gap = dp(3f)
        focusRect.set(rect.left - gap, rect.top - gap, rect.right + gap, rect.bottom + gap)
        if (focusRect.width() <= 0f || focusRect.height() <= 0f) return
        val radius = focusRect.height() / 2f

        focusPaint.reset()
        focusPaint.isAntiAlias = true
        focusPaint.style = Paint.Style.STROKE
        focusPaint.strokeWidth = dp(6f)
        focusPaint.color = FOCUS_GLOW
        focusPaint.maskFilter = focusGlowFilter
        canvas.drawRoundRect(focusRect, radius, radius, focusPaint)

        focusPaint.reset()
        focusPaint.isAntiAlias = true
        focusPaint.style = Paint.Style.STROKE
        focusPaint.strokeWidth = dp(3f)
        focusPaint.color = Color.WHITE
        canvas.drawRoundRect(focusRect, radius, radius, focusPaint)
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

    private companion object {
        /** Тот же зелёный, что у `tv_focus_stroke`: фокус пульта одного цвета во всём приложении. */
        val FOCUS_GLOW = Color.rgb(0x32, 0xD7, 0x4B)
    }

    // Текст переводится в момент показа: код ставит русский исходник (см. NovaLanguage).
    override fun setText(text: CharSequence?, type: BufferType?) = NovaLanguage.onSetText(this, text, type)

    override fun setTextDirect(text: CharSequence?, type: BufferType?) = super.setText(text, type)
}

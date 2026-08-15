package com.example.nova

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.LinearLayout

/**
 * Плашка «Обновить до vX.XX» в правом верхнем углу главного экрана.
 *
 * Подсветка мерцает медленно и неглубоко: это подсказка, а не тревога. Диапазон
 * яркости намеренно узкий — на тёмном фоне даже слабое мерцание заметно боковым
 * зрением, а сильное выглядит как ошибка.
 *
 * Анимация живёт только пока плашка на экране и видима. Невидимый ValueAnimator
 * продолжал бы будить главный поток каждый кадр — на экране, который и так рисует
 * фон, график задержки и кольца, это не бесплатно.
 */
class UpdateChipView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private val inset = dp(7f)
    private var pulse = 0f
    private var pulseAnimator: ValueAnimator? = null

    init {
        // Свечение рисуется BlurMaskFilter — он не работает на аппаратном слое.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setWillNotDraw(false)
        orientation = HORIZONTAL
        isClickable = true
        isFocusable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncPulseAnimator()
    }

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncPulseAnimator()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncPulseAnimator()
    }

    override fun onDraw(canvas: Canvas) {
        rect.set(inset, inset, width - inset, height - inset)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val radius = rect.height() / 2f

        // Три слоя от широкого и прозрачного к узкому и яркому: один размытый
        // прямоугольник даёт плоское пятно, а не свечение.
        val breath = 0.55f + 0.45f * pulse
        val layers = arrayOf(
            floatArrayOf(dp(5f), dp(18f), 0.22f),
            floatArrayOf(dp(1f), dp(9f), 0.40f),
            floatArrayOf(dp(-2f), dp(4f), 0.62f),
        )
        for (layer in layers) {
            val expand = layer[0]
            val blur = layer[1]
            val alphaScale = layer[2] * breath
            glowPaint.reset()
            glowPaint.isAntiAlias = true
            glowPaint.style = Paint.Style.FILL
            glowPaint.color = Color.argb(
                (255 * alphaScale).toInt().coerceIn(0, 255),
                GLOW_R,
                GLOW_G,
                GLOW_B,
            )
            glowPaint.maskFilter = BlurMaskFilter(blur.coerceAtLeast(1f), BlurMaskFilter.Blur.NORMAL)
            val layerRect = RectF(
                rect.left - expand,
                rect.top - expand,
                rect.right + expand,
                rect.bottom + expand,
            )
            canvas.drawRoundRect(layerRect, layerRect.height() / 2f, layerRect.height() / 2f, glowPaint)
        }
        glowPaint.maskFilter = null

        fillPaint.reset()
        fillPaint.isAntiAlias = true
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = Color.argb(226, 8, 20, 13)
        canvas.drawRoundRect(rect, radius, radius, fillPaint)

        strokePaint.reset()
        strokePaint.isAntiAlias = true
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeWidth = dp(1.3f)
        strokePaint.color = Color.argb(
            (130 + 90 * pulse).toInt().coerceIn(0, 255),
            GLOW_R,
            GLOW_G,
            GLOW_B,
        )
        canvas.drawRoundRect(rect, radius, radius, strokePaint)
    }

    private fun syncPulseAnimator() {
        val shouldRun = isAttachedToWindow &&
            visibility == VISIBLE &&
            windowVisibility == VISIBLE
        if (shouldRun) startPulse() else stopPulse()
    }

    private fun startPulse() {
        if (pulseAnimator != null) return
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = PULSE_PERIOD_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { animation ->
                pulse = animation.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        pulse = 0f
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val PULSE_PERIOD_MS = 1500L
        const val GLOW_R = 90
        const val GLOW_G = 226
        const val GLOW_B = 130
    }
}

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

    // Свечению нужно место внутри самого вида.
    //
    // Холст вида обрезан его границами, поэтому размытие, выходящее за них, срезалось
    // ровным прямоугольником — вокруг плашки был виден светлый короб с углами. Отступ
    // должен покрывать самый широкий слой целиком: расширение плюс радиус размытия.
    // Внутренние поля в разметке увеличены на столько же, чтобы сама плашка осталась
    // прежнего размера.
    private val inset = dp(16f)
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

        // Ореол — одна размытая капсула, повторяющая форму плашки.
        //
        // Слоёв было три, и на стыках яркость менялась ступенькой; вместе с обрезкой
        // по границам вида это и читалось как светлый короб. Один слой с широким
        // размытием даёт ровное рассеивание, а форма остаётся капсулой — углов у неё
        // нет по определению.
        val breath = 0.55f + 0.45f * pulse
        glowPaint.reset()
        glowPaint.isAntiAlias = true
        glowPaint.isDither = true
        glowPaint.style = Paint.Style.FILL
        glowPaint.color = Color.argb(
            (150 * breath).toInt().coerceIn(0, 255),
            GLOW_R,
            GLOW_G,
            GLOW_B,
        )
        glowPaint.maskFilter = BlurMaskFilter(dp(11f), BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(rect, radius, radius, glowPaint)
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

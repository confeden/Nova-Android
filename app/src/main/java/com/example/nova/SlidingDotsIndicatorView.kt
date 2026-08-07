package com.example.nova

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class SlidingDotsIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density
    private val dotRadius = max(2.25f * density, 2f)
    private val dotCount = 3
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(148, 170, 196, 230)
    }
    private var animating = false
    private var phase = 0f
    private val animator: ValueAnimator by lazy(LazyThreadSafetyMode.NONE) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1450L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                postInvalidateOnAnimation()
            }
        }
    }

    fun setAnimating(enabled: Boolean) {
        if (animating == enabled) return
        animating = enabled
        if (enabled) {
            if (isAttachedToWindow && !animator.isStarted) {
                animator.start()
            }
        } else {
            if (animator.isStarted) {
                animator.cancel()
            }
            phase = 0f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animating && !animator.isStarted) {
            animator.start()
        }
    }

    override fun onDetachedFromWindow() {
        if (animator.isStarted) {
            animator.cancel()
        }
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (74f * density).toInt()
        val desiredHeight = (11f * density).toInt()
        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!animating && phase <= 0f) return
        val availableWidth = (width - paddingLeft - paddingRight).toFloat().coerceAtLeast(1f)
        val availableHeight = (height - paddingTop - paddingBottom).toFloat().coerceAtLeast(1f)
        val centerY = paddingTop + availableHeight / 2f
        val travel = (availableWidth - dotRadius * 2f).coerceAtLeast(0f)
        val cycleSpan = 0.5f
        val dotDelay = 0.2f
        for (index in 0 until dotCount) {
            var local = phase - index * dotDelay
            if (local < 0f) {
                local += 1f
            }
            if (local <= 0f || local >= cycleSpan) continue
            local /= cycleSpan
            val eased = easeOutQuart(local)
            val fade =
                when {
                    local < 0.16f -> local / 0.16f
                    local > 0.68f -> (1f - local) / 0.32f
                    else -> 1f
                }.coerceIn(0f, 1f)
            val x = paddingLeft + dotRadius + eased * travel
            val baseAlpha = 156 - index * 18
            paint.alpha = (baseAlpha * fade).toInt().coerceIn(0, 156)
            canvas.drawCircle(
                x,
                centerY,
                dotRadius * (0.9f + 0.12f * fade),
                paint,
            )
        }
    }

    private fun easeOutQuart(value: Float): Float {
        val inverted = 1f - value.coerceIn(0f, 1f)
        return 1f - inverted * inverted * inverted * inverted
    }
}

package com.example.nova

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.max

class WarpDiscoveryTrailView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), Choreographer.FrameCallback {

    private val interpolator = DecelerateInterpolator(1.45f)
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#50C878")
        style = Paint.Style.FILL
    }
    private var running = false
    private var startNs = 0L

    fun setRunning(value: Boolean) {
        if (running == value) return
        running = value
        if (value) {
            startNs = 0L
            Choreographer.getInstance().postFrameCallback(this)
        } else {
            Choreographer.getInstance().removeFrameCallback(this)
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (startNs == 0L) startNs = frameTimeNanos
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!running || width <= 0 || height <= 0 || startNs == 0L) return

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000f
        val centerY = height / 2f
        val travelStart = 8f
        val travelWidth = max(1f, width - 16f)
        val dotRadius = height.coerceAtMost(14) / 2.6f
        val cycleMs = 1650f
        val dotOffsets = floatArrayOf(0f, 180f, 360f, 540f)

        for (offset in dotOffsets) {
            val localMs = (elapsedMs - offset)
            if (localMs < 0f) continue
            val phase = (localMs % cycleMs) / cycleMs
            val eased = interpolator.getInterpolation(phase.coerceIn(0f, 1f))
            val x = travelStart + travelWidth * eased
            val alpha = ((1f - phase) * 185f).toInt().coerceIn(0, 185)
            dotPaint.alpha = alpha
            canvas.drawCircle(x, centerY, dotRadius, dotPaint)
        }
    }
}

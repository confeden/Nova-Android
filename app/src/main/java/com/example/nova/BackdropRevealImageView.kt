package com.example.nova

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.hypot
import kotlin.math.max

class BackdropRevealImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val preferClipRevealFallback = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
    private var revealProgressInternal = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var maxRadius = 0f
    private val density = context.resources.displayMetrics.density
    private val featherRadius = max(88f * density, 72f)
    private val revealPath = Path()
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        @Suppress("DEPRECATION")
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }

    var revealProgress: Float
        get() = revealProgressInternal
        set(value) {
            val normalized = value.coerceIn(0f, 1f)
            if (revealProgressInternal == normalized) return
            revealProgressInternal = normalized
            if (preferClipRevealFallback && layerType != LAYER_TYPE_NONE) {
                setLayerType(LAYER_TYPE_NONE, null)
            }
            postInvalidateOnAnimation()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h * 0.43f
        maxRadius = hypot(w.toDouble(), h.toDouble()).toFloat() * 1.08f
    }

    override fun onDraw(canvas: Canvas) {
        if (revealProgressInternal <= 0f) return
        if (revealProgressInternal >= 0.999f || width <= 0 || height <= 0 || maxRadius <= 0f) {
            super.onDraw(canvas)
            return
        }

        val revealRadius = maxRadius * revealProgressInternal.coerceAtLeast(0.02f)
        if (preferClipRevealFallback) {
            val checkpoint = canvas.save()
            revealPath.reset()
            revealPath.addCircle(centerX, centerY, revealRadius, Path.Direction.CW)
            canvas.clipPath(revealPath)
            super.onDraw(canvas)
            canvas.restoreToCount(checkpoint)
            return
        }

        val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.onDraw(canvas)
        val outerRadius = revealRadius + featherRadius
        val solidStop = (revealRadius / outerRadius).coerceIn(0.06f, 0.96f)
        maskPaint.shader = RadialGradient(
            centerX,
            centerY,
            outerRadius,
            intArrayOf(Color.WHITE, Color.WHITE, Color.TRANSPARENT),
            floatArrayOf(0f, solidStop, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), maskPaint)
        maskPaint.shader = null
        canvas.restoreToCount(checkpoint)
    }
}

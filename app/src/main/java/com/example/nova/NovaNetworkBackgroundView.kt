package com.example.nova

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class NovaNetworkBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class Node(
        var x: Float,
        var y: Float,
        var vx: Float,
        var vy: Float,
        val radius: Float,
        val phase: Float,
    )

    private val supported = MainBackgroundPolicy.isAnimationSupported(context)
    private val random = Random(0x4E4F5641)
    private val nodes = ArrayList<Node>(36)
    private val nodePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(230, 237, 243)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * resources.displayMetrics.density
    }
    private val trianglePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val trianglePath = Path()
    private var frameScheduled = false
    private var lastFrameMs = 0L
    private var phaseSeconds = 0f
    private val targetFrameDelayMs = 42L

    private val frameRunnable = object : Runnable {
        override fun run() {
            frameScheduled = false
            if (!supported || visibility != VISIBLE || !isAttachedToWindow) return
            val now = android.os.SystemClock.uptimeMillis()
            val deltaMs = if (lastFrameMs > 0L) (now - lastFrameMs).coerceIn(16L, 80L) else targetFrameDelayMs
            lastFrameMs = now
            updateNodes(deltaMs / 16.666f)
            phaseSeconds = (phaseSeconds + deltaMs / 1000f) % 4096f
            invalidate()
            scheduleFrame()
        }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        if (supported) {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
    }

    fun startAnimation() {
        if (!supported) return
        lastFrameMs = 0L
        scheduleFrame()
    }

    fun stopAnimation() {
        frameScheduled = false
        removeCallbacks(frameRunnable)
        lastFrameMs = 0L
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) scheduleFrame()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) {
            startAnimation()
        } else {
            stopAnimation()
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildNodes(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (!supported || width <= 0 || height <= 0 || nodes.isEmpty()) return
        drawTriangles(canvas)
        drawLinks(canvas)
        for (node in nodes) {
            val pulse = (sin(phaseSeconds * 1.4f + node.phase) + 1f) * 0.5f
            nodePaint.alpha = (145 + pulse * 85f).toInt().coerceIn(0, 255)
            canvas.drawCircle(node.x, node.y, node.radius * (1f + pulse * 0.28f), nodePaint)
        }
    }

    private fun rebuildNodes(w: Int, h: Int) {
        nodes.clear()
        if (!supported || w <= 0 || h <= 0) return
        val density = resources.displayMetrics.density
        val count = if (w < h) 30 else 38
        repeat(count) {
            val speed = (0.09f + random.nextFloat() * 0.18f) * density
            val angle = random.nextFloat() * (Math.PI.toFloat() * 2f)
            nodes += Node(
                x = random.nextFloat() * w,
                y = random.nextFloat() * h,
                vx = kotlin.math.cos(angle) * speed,
                vy = kotlin.math.sin(angle) * speed,
                radius = (0.85f + random.nextFloat() * 1.55f) * density,
                phase = random.nextFloat() * Math.PI.toFloat() * 2f,
            )
        }
    }

    private fun updateNodes(step: Float) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        for (node in nodes) {
            node.x += node.vx * step
            node.y += node.vy * step
            if (node.x < 0f) {
                node.x = 0f
                node.vx = kotlin.math.abs(node.vx)
            } else if (node.x > w) {
                node.x = w
                node.vx = -kotlin.math.abs(node.vx)
            }
            if (node.y < 0f) {
                node.y = 0f
                node.vy = kotlin.math.abs(node.vy)
            } else if (node.y > h) {
                node.y = h
                node.vy = -kotlin.math.abs(node.vy)
            }
        }
    }

    private fun drawLinks(canvas: Canvas) {
        val maxDist = 150f * resources.displayMetrics.density
        val maxDistSq = maxDist * maxDist
        for (i in 0 until nodes.size) {
            val a = nodes[i]
            for (j in i + 1 until nodes.size) {
                val b = nodes[j]
                val dx = a.x - b.x
                val dy = a.y - b.y
                val distSq = dx * dx + dy * dy
                if (distSq > maxDistSq) continue
                val dist = sqrt(distSq)
                val pulse = (sin(phaseSeconds * 1.25f + a.phase + b.phase) + 1f) * 0.5f
                val alpha = ((1f - dist / maxDist) * (48f + pulse * 90f)).toInt().coerceIn(0, 138)
                linePaint.color = Color.argb(alpha, 163, 113, 247)
                canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
            }
        }
    }

    private fun drawTriangles(canvas: Canvas) {
        val maxDist = 128f * resources.displayMetrics.density
        val maxDistSq = maxDist * maxDist
        val minArea = 320f * resources.displayMetrics.density * resources.displayMetrics.density
        for (i in 0 until nodes.size) {
            val a = nodes[i]
            var firstIndex = -1
            var firstDist = Float.MAX_VALUE
            var secondIndex = -1
            var secondDist = Float.MAX_VALUE
            for (j in 0 until nodes.size) {
                if (i == j) continue
                val b = nodes[j]
                val dx = a.x - b.x
                val dy = a.y - b.y
                val distSq = dx * dx + dy * dy
                if (distSq > maxDistSq) continue
                if (distSq < firstDist) {
                    secondDist = firstDist
                    secondIndex = firstIndex
                    firstDist = distSq
                    firstIndex = j
                } else if (distSq < secondDist) {
                    secondDist = distSq
                    secondIndex = j
                }
            }
            if (firstIndex < 0 || secondIndex < 0 || firstIndex == secondIndex) continue
            val b = nodes[firstIndex]
            val c = nodes[secondIndex]
            val area = kotlin.math.abs((b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y)) * 0.5f
            if (area < minArea) continue
            val proximity = (1f - kotlin.math.sqrt(maxOf(firstDist, secondDist)) / maxDist).coerceIn(0f, 1f)
            val pulse = (sin(phaseSeconds * 0.72f + a.phase * 0.9f) + 1f) * 0.5f
            val alpha = (proximity * (10f + pulse * 34f)).toInt().coerceIn(0, 44)
            if (alpha <= 2) continue
            trianglePaint.color = Color.argb(alpha, 110, 72, 196)
            trianglePath.reset()
            trianglePath.moveTo(a.x, a.y)
            trianglePath.lineTo(b.x, b.y)
            trianglePath.lineTo(c.x, c.y)
            trianglePath.close()
            canvas.drawPath(trianglePath, trianglePaint)
        }
    }

    private fun scheduleFrame() {
        if (frameScheduled || !supported || visibility != VISIBLE || !isAttachedToWindow) return
        frameScheduled = true
        postOnAnimationDelayed(frameRunnable, targetFrameDelayMs)
    }
}

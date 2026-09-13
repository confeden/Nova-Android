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
        // Цвет обязан стоять уже здесь: в отрисовке меняется только альфа, а
        // `applyAccent` может не прийти вовсе (тема без словаря). Без этой строки
        // треугольники рисовались бы чёрным по умолчанию `Paint`.
        color = Color.rgb(110, 72, 196)
    }
    private val trianglePath = Path()
    private var frameScheduled = false
    private var lastFrameMs = 0L
    private var phaseSeconds = 0f
    private val targetFrameDelayMs = 42L

    /** Заливка градиентом поверх фона. Шейдер пересобирается на размер и на смену темы. */
    private val washPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Акцент темы. Пока не задан — прежний сиреневый, чтобы вид работал и без темы. */
    private var accentR = 163
    private var accentG = 113
    private var accentB = 247

    /**
     * Красит анимацию и её градиент акцентом темы.
     *
     * Оттенок принадлежит **этому** виду, а не экрану, и в этом весь смысл: вид
     * показан ровно тогда, когда фоновая анимация включена, поэтому градиент
     * появляется и исчезает вместе с ней и не требует отдельного признака.
     *
     * Красным/жёлтым здесь ничего не значится, поэтому из темы берётся один цвет:
     * линии идут самим акцентом, треугольники — им же, притушенным до двух третей
     * (иначе заливка спорит с линиями), узлы — почти белые с его примесью.
     */
    fun applyAccent(accent: Int) {
        val r = Color.red(accent)
        val g = Color.green(accent)
        val b = Color.blue(accent)
        if (r == accentR && g == accentG && b == accentB) return
        accentR = r
        accentG = g
        accentB = b
        trianglePaint.color = Color.rgb((r * 0.66f).toInt(), (g * 0.66f).toInt(), (b * 0.66f).toInt())
        nodePaint.color = Color.rgb(
            (230 * 0.78f + r * 0.22f).toInt(),
            (237 * 0.78f + g * 0.22f).toInt(),
            (243 * 0.78f + b * 0.22f).toInt(),
        )
        rebuildWash(width, height)
        invalidate()
    }

    /**
     * Вертикальный градиент акцента: заметный вверху, пустой к середине, намёк внизу.
     *
     * Прозрачности выбраны так, чтобы под ним читался белый текст: 0x30 — это 19 %,
     * на тёмной подложке экрана это оттенок, а не пелена.
     */
    private fun rebuildWash(w: Int, h: Int) {
        if (w <= 0 || h <= 0) {
            washPaint.shader = null
            return
        }
        val strong = Color.argb(0x30, accentR, accentG, accentB)
        val faint = Color.argb(0x12, accentR, accentG, accentB)
        val clear = Color.argb(0x00, accentR, accentG, accentB)
        washPaint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(strong, clear, faint),
            floatArrayOf(0f, 0.52f, 1f),
            android.graphics.Shader.TileMode.CLAMP,
        )
    }

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
        // Аппаратного слоя здесь нет намеренно. Слой окупается на виде, который
        // перерисовывают редко, а двигают часто; этот — наоборот, он меняется
        // каждый кадр, и слой пришлось бы собирать заново во весь экран на каждом.
        setLayerType(LAYER_TYPE_NONE, null)
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
        rebuildWash(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (!supported || width <= 0 || height <= 0) return
        // Градиент рисуется первым и не зависит от узлов: он обязан быть на экране
        // и в тот кадр, когда узлы ещё не построены.
        if (washPaint.shader != null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), washPaint)
        }
        if (nodes.isEmpty()) return
        drawTriangles(canvas)
        drawLinks(canvas)
        for (node in nodes) {
            val pulse = (sin(phaseSeconds * 1.4f + node.phase) + 1f) * 0.5f
            nodePaint.alpha = (145 + pulse * 85f).toInt().coerceIn(0, 255)
            canvas.drawCircle(node.x, node.y, node.radius * (1f + pulse * 0.28f), nodePaint)
        }
    }

    /**
     * Раскладка берётся из [NovaConstellation] — общая с волнами подключения.
     *
     * До этого у каждого вида было своё зерно и своё число точек, и человек видел
     * это прямо: волны зажигали одно созвездие, а подключившись, он оказывался
     * перед другим. Теперь оба вида показывают одни и те же точки.
     */
    private fun rebuildNodes(w: Int, h: Int) {
        nodes.clear()
        if (!supported || w <= 0 || h <= 0) return
        NovaConstellation.build(w, h, resources.displayMetrics.density).forEach { point ->
            nodes += Node(
                x = point.x,
                y = point.y,
                vx = point.vx,
                vy = point.vy,
                radius = point.radius,
                phase = point.phase,
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
                linePaint.color = Color.argb(alpha, accentR, accentG, accentB)
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
            trianglePaint.alpha = alpha
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

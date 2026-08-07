package com.example.nova

import android.app.ActivityManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.random.Random

class TronRingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        STOPPED,
        CONNECTING,
    }

    private data class Star(
        val x: Float,
        val y: Float,
        val radius: Float,
    )

    private data class Edge(
        val a: Int,
        val b: Int,
    )

    private var mode: Mode = Mode.STOPPED
    private var animationStartNanos = 0L
    private var animatedPhase = 0f
    private var canvasWidth = 0f
    private var canvasHeight = 0f
    private var centerX = 0f
    private var centerY = 0f
    private var maxRadius = 0f
    private var frameCallbackArmed = false
    private var useYogurtIndigo = false

    private val lowEndDevice = run {
        val am = context.getSystemService(ActivityManager::class.java)
        val lowRam = am?.isLowRamDevice ?: false
        lowRam || Build.VERSION.SDK_INT <= Build.VERSION_CODES.P || Runtime.getRuntime().availableProcessors() <= 4
    }
    private val density = context.resources.displayMetrics.density

    private val ringRed = intArrayOf(112, 198, 112)
    private val ringGreen = intArrayOf(228, 176, 228)
    private val ringBlue = intArrayOf(255, 255, 255)
    private val ringOffsets = if (lowEndDevice) {
        floatArrayOf(0f, 0.50f)
    } else {
        floatArrayOf(0f, 0.25f, 0.50f, 0.75f)
    }
    private val cycleDurationMs = if (lowEndDevice) 8600f else 7200f
    private val lowEndFrameDelayMs = 32L
    private val baseWidthMax = 2.15f * density
    private val baseWidthMin = 1.3f * density
    private val glowWidthScale = 2.4f

    private val yogurtColors = intArrayOf(
        Color.rgb(191, 177, 216),
    )
    private val yogurtRingOffsets = if (lowEndDevice) {
        floatArrayOf(0f, 0.50f)
    } else {
        floatArrayOf(0f, 0.25f, 0.50f, 0.75f)
    }
    private val yogurtCycleDurationMs = if (lowEndDevice) 12740f else 11180f
    private val yogurtEdgePower = 2.65f
    private val yogurtRingAlpha = if (lowEndDevice) 0.15f else 0.19f
    private val yogurtRingWidth = 5.5f * density
    private val yogurtConstellationAlpha = if (lowEndDevice) 0.50f else 0.72f

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val yogurtRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val constellationLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val constellationStarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val stars = ArrayList<Star>(64)
    private val edges = ArrayList<Edge>(128)

    private val frameRunnable = object : Runnable {
        override fun run() {
            frameCallbackArmed = false
            if (mode != Mode.CONNECTING || !isAttachedToWindow) return
            val now = System.nanoTime()
            if (animationStartNanos == 0L) {
                animationStartNanos = now
            }
            val elapsedMs = (now - animationStartNanos) / 1_000_000f
            val duration = if (useYogurtIndigo) yogurtCycleDurationMs else cycleDurationMs
            animatedPhase = (elapsedMs / duration) % 1f
            invalidate()
            scheduleFrame()
        }
    }

    fun setYogurtIndigoEnabled(enabled: Boolean) {
        if (useYogurtIndigo == enabled) return
        useYogurtIndigo = enabled
        updateGeometry(width, height)
        if (mode == Mode.CONNECTING) {
            animationStartNanos = 0L
            animatedPhase = 0f
            invalidate()
        }
    }

    fun setMode(value: Mode, restart: Boolean = false) {
        if (mode == value && !restart) return
        mode = value
        when (value) {
            Mode.CONNECTING -> {
                setLayerType(if (lowEndDevice) LAYER_TYPE_NONE else LAYER_TYPE_HARDWARE, null)
                animationStartNanos = 0L
                animatedPhase = 0f
                scheduleFrame()
            }
            Mode.STOPPED -> {
                setLayerType(LAYER_TYPE_NONE, null)
                animationStartNanos = 0L
                animatedPhase = 0f
                unscheduleFrame()
                invalidate()
            }
        }
    }

    fun restartAnimation() {
        if (mode != Mode.CONNECTING) return
        animationStartNanos = 0L
        animatedPhase = 0f
        unscheduleFrame()
        scheduleFrame()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (mode == Mode.CONNECTING) {
            scheduleFrame()
        }
    }

    override fun onDetachedFromWindow() {
        unscheduleFrame()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateGeometry(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (mode != Mode.CONNECTING) return
        if (canvasWidth <= 0f || canvasHeight <= 0f || maxRadius <= 0f) return

        if (useYogurtIndigo) {
            drawYogurtIndigo(canvas)
            return
        }

        for (index in ringOffsets.indices) {
            val offset = ringOffsets[index]
            val progress = ((animatedPhase - offset) % 1f + 1f) % 1f
            drawLegacyRing(canvas, progress, index)
        }
    }

    private fun updateGeometry(w: Int, h: Int) {
        canvasWidth = w.toFloat()
        canvasHeight = h.toFloat()
        centerX = canvasWidth / 2f
        centerY = if (useYogurtIndigo) canvasHeight * 0.52f else canvasHeight * 0.43f
        maxRadius = hypot(canvasWidth.toDouble(), canvasHeight.toDouble()).toFloat() * 1.1f
        rebuildConstellation(w, h)
    }

    private fun drawLegacyRing(
        canvas: Canvas,
        progress: Float,
        index: Int,
    ) {
        val radius = maxRadius * progress
        val eased = 1f - progress
        val alphaBase = ((eased * eased) * 128f + 16f).toInt().coerceIn(0, 255)
        val red = ringRed[index % ringRed.size]
        val green = ringGreen[index % ringGreen.size]
        val blue = ringBlue[index % ringBlue.size]
        val strokeFraction = progress * 0.2f
        val baseWidth = (baseWidthMax - baseWidthMax * strokeFraction).coerceAtLeast(baseWidthMin)

        if (!lowEndDevice) {
            ringPaint.color = Color.argb((alphaBase * 0.28f).toInt().coerceIn(0, 255), red, green, blue)
            ringPaint.strokeWidth = baseWidth * glowWidthScale
            canvas.drawCircle(centerX, centerY, radius, ringPaint)
        }

        ringPaint.color = Color.argb(alphaBase, red, green, blue)
        ringPaint.strokeWidth = baseWidth
        canvas.drawCircle(centerX, centerY, radius, ringPaint)
    }

    private fun drawYogurtIndigo(canvas: Canvas) {
        drawYogurtRings(canvas)
        drawConstellation(canvas)
    }

    private fun drawYogurtRings(canvas: Canvas) {
        val ringMaxRadius = yogurtMaxRadius()
        for (index in yogurtRingOffsets.indices) {
            val progress = ((animatedPhase - yogurtRingOffsets[index]) % 1f + 1f) % 1f
            val radius = ringMaxRadius * progress
            val fade = yogurtEdgeFade(radius, ringMaxRadius)
            if (fade <= 0.001f) continue
            val color = yogurtColors[index % yogurtColors.size]
            val alpha = (255f * yogurtRingAlpha * fade).toInt().coerceIn(0, 255)
            val outerRadius = (radius + yogurtRingWidth).coerceAtLeast(1f)
            val gradient = RadialGradient(
                centerX,
                centerY,
                outerRadius,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb((alpha * 0.14f).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)),
                    Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)),
                    Color.argb((alpha * 0.10f).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color)),
                    Color.TRANSPARENT,
                ),
                floatArrayOf(
                    ((radius - yogurtRingWidth) / outerRadius).coerceIn(0f, 1f),
                    ((radius - yogurtRingWidth * 0.26f) / outerRadius).coerceIn(0f, 1f),
                    (radius / outerRadius).coerceIn(0f, 1f),
                    ((radius + yogurtRingWidth * 0.26f) / outerRadius).coerceIn(0f, 1f),
                    1f,
                ),
                Shader.TileMode.CLAMP,
            )
            yogurtRingPaint.shader = gradient
            yogurtRingPaint.strokeWidth = yogurtRingWidth
            canvas.drawCircle(centerX, centerY, radius, yogurtRingPaint)
            yogurtRingPaint.shader = null
        }
    }

    private fun drawConstellation(canvas: Canvas) {
        for (edge in edges) {
            val a = stars.getOrNull(edge.a) ?: continue
            val b = stars.getOrNull(edge.b) ?: continue
            val energy = yogurtWaveEnergy((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)
            val idleAlpha = 0f
            val alpha = ((idleAlpha + energy * yogurtConstellationAlpha * 0.30f) * 255f)
                .toInt()
                .coerceIn(0, 92)
            if (alpha <= 3) continue
            constellationLinePaint.color = Color.argb(alpha, 191, 177, 216)
            constellationLinePaint.strokeWidth = 0.75f * density + energy * 0.45f * density
            canvas.drawLine(a.x, a.y, b.x, b.y, constellationLinePaint)
        }

        for (star in stars) {
            val energy = yogurtWaveEnergy(star.x, star.y)
            val idleAlpha = 0f
            val alpha = ((idleAlpha + energy * yogurtConstellationAlpha * 0.50f) * 255f)
                .toInt()
                .coerceIn(0, 184)
            constellationStarPaint.color = Color.argb(alpha, 191, 177, 216)
            canvas.drawCircle(star.x, star.y, star.radius * (1f + energy * 1.5f), constellationStarPaint)
        }
    }

    private fun yogurtWaveEnergy(x: Float, y: Float): Float {
        val ringMaxRadius = yogurtMaxRadius()
        var energy = 0f
        val dy = (y - centerY) * 1.025f
        val distance = hypot((x - centerX).toDouble(), dy.toDouble()).toFloat()
        val waveWidth = yogurtRingWidth * 3.2f
        for (offset in yogurtRingOffsets) {
            val progress = ((animatedPhase - offset) % 1f + 1f) % 1f
            val radius = ringMaxRadius * progress
            val delta = kotlin.math.abs(distance - radius)
            if (delta > waveWidth) continue
            val local = (1f - delta / waveWidth).coerceIn(0f, 1f)
            energy = maxOf(energy, local * local * (3f - 2f * local) * yogurtEdgeFade(radius, ringMaxRadius))
        }
        return energy
    }

    private fun yogurtEdgeFade(radius: Float, ringMaxRadius: Float): Float {
        if (ringMaxRadius <= 0f) return 0f
        val normalized = (radius / ringMaxRadius).coerceIn(0f, 1f)
        val edgeStart = 0.44f
        val edge = ((normalized - edgeStart) / (1f - edgeStart)).coerceIn(0f, 1f)
        return (1f - edge).pow(yogurtEdgePower)
    }

    private fun yogurtMaxRadius(): Float {
        return hypot(
            maxOf(centerX, canvasWidth - centerX).toDouble(),
            maxOf(centerY, canvasHeight - centerY).toDouble(),
        ).toFloat() + 72f * density
    }

    private fun rebuildConstellation(w: Int, h: Int) {
        stars.clear()
        edges.clear()
        if (w <= 0 || h <= 0) return
        val random = Random(0x594F4755)
        val count = if (lowEndDevice) 36 else if (w < h) 44 else 62
        repeat(count) {
            stars += Star(
                x = random.nextFloat() * w,
                y = random.nextFloat() * h,
                radius = (0.65f + random.nextFloat() * 1.35f) * density,
            )
        }
        val maxDistance = minOf(170f * density, maxOf(118f * density, minOf(w, h) * 0.17f))
        for (i in stars.indices) {
            val nearest = ArrayList<Pair<Int, Float>>(4)
            val a = stars[i]
            for (j in i + 1 until stars.size) {
                val b = stars[j]
                val distance = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
                if (distance <= maxDistance) {
                    nearest += j to distance
                }
            }
            nearest
                .sortedBy { it.second }
                .take(2)
                .forEach { (target, _) -> edges += Edge(i, target) }
        }
    }

    private fun scheduleFrame() {
        if (frameCallbackArmed || mode != Mode.CONNECTING || !isAttachedToWindow) return
        frameCallbackArmed = true
        if (lowEndDevice) {
            postOnAnimationDelayed(frameRunnable, lowEndFrameDelayMs)
        } else {
            postOnAnimation(frameRunnable)
        }
    }

    private fun unscheduleFrame() {
        if (!frameCallbackArmed) return
        frameCallbackArmed = false
        removeCallbacks(frameRunnable)
    }
}

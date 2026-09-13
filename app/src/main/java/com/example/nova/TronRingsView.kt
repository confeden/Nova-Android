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
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Волны подключения: круги, расходящиеся из кнопки, и созвездие, которое они зажигают.
 *
 * ## Три решения владельца, и что каждое значит в коде
 *
 * **Круги, но из центра кнопки.** Форму владелец оставил круглой; что изменилось —
 * это точка отсчёта: она приходит снаружи ([setPulseOrigin]) от самой кнопки
 * «ПОДКЛЮЧИТЬ», а не считается долей высоты экрана. Прежние `высота * 0.43`
 * совпадали с кнопкой ровно на одном размере экрана.
 *
 * **Свечение — след, а не повторённый контур.** Волна рисуется **одним** штрихом,
 * поперёк которого лежит радиальный градиент: снаружи обрыв в прозрачность, на
 * фронте максимум, внутрь уходит длинный затухающий хвост. Первая версия
 * изображала след тремя контурами подряд — и читалась ровно так, как была сделана:
 * три отдельные линии. Градиент по краске не стоит ни размытия, ни слоя.
 *
 * **Созвездие — то же самое, что останется после подключения.** Точки берутся из
 * [NovaConstellation], откуда их берёт и фоновая анимация: раньше у каждого вида
 * была своя раскладка, и созвездие, зажжённое волнами, не совпадало с тем, которое
 * человек видел, подключившись.
 *
 * ## Чем это платится
 *
 * Кадр — это три `drawCircle` и обход созвездия (30-38 точек, до 76 связей). Ни
 * одного размытия, ни одного программного слоя; выделяется только шейдер волны.
 * Кадры идут только в [Mode.CONNECTING] и только пока вид прикреплён.
 */
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
    private var frameCallbackArmed = false

    /** Показывать ли созвездие. Оно принадлежит режиму «анимация» у фона экрана. */
    private var constellationEnabled = false

    private val lowEndDevice = run {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val lowRam = activityManager?.isLowRamDevice == true
        lowRam || Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
    }

    private val density = context.resources.displayMetrics.density

    /**
     * Центр кнопки, из которой расходятся волны, и её половинные размеры.
     *
     * Размеры волна больше не повторяет — она круглая, — но признак «кнопку уже
     * разложили» нужен по-прежнему, и хранится он здесь же. Пока центр не задали,
     * берётся середина экрана: вид обязан что-то рисовать и до раскладки.
     */
    private var originX = 0f
    private var originY = 0f
    private var baseHalfW = 96f * density
    private var baseHalfH = 26f * density

    private var accentR = 112
    private var accentG = 228
    private var accentB = 255

    /** Сколько волн в кадре и как они разнесены по фазе. */
    private val ringOffsets = if (lowEndDevice) {
        floatArrayOf(0f, 0.5f)
    } else {
        floatArrayOf(0f, 0.34f, 0.67f)
    }

    /** Длина следа за фронтом волны. Он рисуется градиентом, а не повтором контура. */
    private val trailWidth = (if (lowEndDevice) 34f else 48f) * density

    private val cycleDurationMs = if (lowEndDevice) 9800f else 8200f
    private val lowEndFrameDelayMs = 32L

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
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
            animatedPhase = (elapsedMs / cycleDurationMs) % 1f
            invalidate()
            scheduleFrame()
        }
    }

    /**
     * Откуда расходятся волны.
     *
     * Зовётся главным экраном по фактическим границам кнопки: считать их здесь
     * значило бы завести вторую копию её размеров и разметки.
     */
    fun setPulseOrigin(centerX: Float, centerY: Float, halfWidth: Float, halfHeight: Float) {
        if (halfWidth <= 0f || halfHeight <= 0f) return
        if (originX == centerX && originY == centerY &&
            baseHalfW == halfWidth && baseHalfH == halfHeight
        ) {
            return
        }
        originX = centerX
        originY = centerY
        baseHalfW = halfWidth
        baseHalfH = halfHeight
        if (mode == Mode.CONNECTING) invalidate()
    }

    /** Цвет волн и созвездия — акцент текущей темы. */
    fun applyAccent(color: Int) {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        if (r == accentR && g == accentG && b == accentB) return
        accentR = r
        accentG = g
        accentB = b
        if (mode == Mode.CONNECTING) invalidate()
    }

    fun setYogurtIndigoEnabled(enabled: Boolean) {
        if (constellationEnabled == enabled) return
        constellationEnabled = enabled
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
                // Аппаратный слой здесь вреден: вид перерисовывается каждый кадр,
                // и слой пришлось бы каждый раз собирать заново во весь экран.
                setLayerType(LAYER_TYPE_NONE, null)
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
        canvasWidth = w.toFloat()
        canvasHeight = h.toFloat()
        if (originX == 0f && originY == 0f) {
            originX = canvasWidth / 2f
            originY = canvasHeight * 0.5f
        }
        rebuildConstellation(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        if (mode != Mode.CONNECTING || canvasWidth <= 0f || canvasHeight <= 0f) return
        if (constellationEnabled) drawConstellation(canvas)
        drawWaves(canvas)
    }

    /**
     * Насколько далеко волне идти, чтобы уйти за любой угол экрана.
     *
     * Считается от контура кнопки, а не от точки: волна — это отодвинутый контур,
     * и до угла ей остаётся меньше на половину кнопки.
     */
    private fun maxReach(): Float {
        val dx = max(originX, canvasWidth - originX)
        val dy = max(originY, canvasHeight - originY)
        return hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private fun drawWaves(canvas: Canvas) {
        val reach = maxReach()
        for (index in ringOffsets.indices) {
            val progress = ((animatedPhase - ringOffsets[index]) % 1f + 1f) % 1f
            if (progress <= 0.001f || progress >= 1f) continue
            val radius = reach * progress
            if (radius <= 1f) continue

            // Яркость гаснет к краю экрана квадратично: у самой кнопки волна
            // плотная, к углам от неё остаётся намёк.
            val fade = (1f - progress) * (1f - progress)
            val peak = (fade * 190f).toInt().coerceIn(0, 255)
            if (peak <= 2) continue

            // Свечение — один штрих с радиальным градиентом поперёк, а не
            // несколько контуров подряд. Повторённые контуры и читались как
            // повторённые контуры: три отдельные линии, а не след. Здесь фронт
            // яркий, снаружи обрыв в прозрачность, а внутрь уходит длинный
            // хвост — это и есть след от круга.
            val tail = trailWidth
            val ahead = tail * 0.22f
            val outer = radius + ahead
            val inner = (radius - tail).coerceAtLeast(0f)
            val width = outer - inner
            if (width <= 1f) continue

            ringPaint.shader = RadialGradient(
                originX,
                originY,
                outer,
                intArrayOf(
                    Color.argb(0, accentR, accentG, accentB),
                    Color.argb((peak * 0.18f).toInt().coerceIn(0, 255), accentR, accentG, accentB),
                    Color.argb(peak, accentR, accentG, accentB),
                    Color.argb(0, accentR, accentG, accentB),
                ),
                floatArrayOf(
                    (inner / outer).coerceIn(0f, 1f),
                    ((radius - tail * 0.45f) / outer).coerceIn(0f, 1f),
                    (radius / outer).coerceIn(0f, 1f),
                    1f,
                ),
                Shader.TileMode.CLAMP,
            )
            // Штрих шириной во весь след: сама краска уже несёт и фронт, и хвост.
            ringPaint.strokeWidth = width
            canvas.drawCircle(originX, originY, (inner + outer) * 0.5f, ringPaint)
            ringPaint.shader = null
        }
    }

    private fun rebuildConstellation(w: Int, h: Int) {
        stars.clear()
        edges.clear()
        if (w <= 0 || h <= 0) return
        // Раскладка общая с фоновой анимацией — см. [NovaConstellation].
        NovaConstellation.build(w, h, density).forEach { point ->
            stars += Star(point.x, point.y, point.radius)
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

    private fun drawConstellation(canvas: Canvas) {
        for (edge in edges) {
            val a = stars.getOrNull(edge.a) ?: continue
            val b = stars.getOrNull(edge.b) ?: continue
            val energy = waveEnergy((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f)
            val alpha = (energy * 92f).toInt().coerceIn(0, 92)
            if (alpha <= 3) continue
            constellationLinePaint.color = Color.argb(alpha, accentR, accentG, accentB)
            constellationLinePaint.strokeWidth = 0.75f * density + energy * 0.45f * density
            canvas.drawLine(a.x, a.y, b.x, b.y, constellationLinePaint)
        }

        for (star in stars) {
            val energy = waveEnergy(star.x, star.y)
            val alpha = (energy * 184f).toInt().coerceIn(0, 184)
            if (alpha <= 3) continue
            constellationStarPaint.color = Color.argb(alpha, accentR, accentG, accentB)
            canvas.drawCircle(star.x, star.y, star.radius * (1f + energy * 1.5f), constellationStarPaint)
        }
    }

    /**
     * Расстояние от точки до центра волн.
     *
     * Волны снова круговые — так попросил владелец, — поэтому и подсветка
     * созвездия считается по кругу: разойдись они формой, вспышки шли бы не по
     * фронту, а рядом с ним.
     */
    private fun contourDistance(x: Float, y: Float): Float =
        hypot((x - originX).toDouble(), (y - originY).toDouble()).toFloat()

    private fun waveEnergy(x: Float, y: Float): Float {
        val reach = maxReach()
        val distance = contourDistance(x, y)
        val waveWidth = 26f * density
        var energy = 0f
        for (offset in ringOffsets) {
            val progress = ((animatedPhase - offset) % 1f + 1f) % 1f
            if (progress <= 0f) continue
            val delta = abs(distance - reach * progress)
            if (delta > waveWidth) continue
            val near = 1f - delta / waveWidth
            energy = max(energy, near * near * (1f - progress))
        }
        return energy.coerceIn(0f, 1f)
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
        frameCallbackArmed = false
        removeCallbacks(frameRunnable)
    }
}

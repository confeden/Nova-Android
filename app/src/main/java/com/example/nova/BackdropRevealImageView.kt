package com.example.nova

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Фон главного экрана: раскрывается кругом от главной кнопки и, пока виден,
 * медленно «погружается» в узор.
 *
 * ## Погружение
 *
 * Владелец просил, чтобы фрактал не стоял картинкой, а плыл навстречу — как
 * погружение с центром там, откуда идут кольца подключения. Сделано бесконечным
 * приближением из двух слоёв: каждый растёт от 1× до 2× за [DIVE_CYCLE_MS]
 * (экспонентой, чтобы скорость полёта казалась ровной), второй сдвинут на
 * полцикла. Веса слоёв — sin² и cos² фазы, в сумме всегда единица, поэтому
 * яркость не мигает. Слой, дошедший до 2×, в этот момент невидим (его вес ноль) —
 * тогда он возвращается к 1× и получает **новый** узор: так в глубине
 * появляются новые детали, а скачка не видно.
 *
 * ## Почему это не нагружает процессор
 *
 * Кадр — два `drawBitmap` с матрицей, без выделений памяти; частота ~30 кадров,
 * для такого медленного движения больше не нужно. Новые узоры строит
 * [diveBitmapSupplier] в фоне, в половину размера экрана и из нескольких
 * вариантов по кругу: после первого круга они берутся из памяти и не строятся
 * больше вовсе. Узор для следующего цикла просится заранее, за целый цикл.
 * Вне экрана, при выключенных системных анимациях и при выключенном погружении
 * кадры не заказываются.
 */
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

    private var diveEnabled = false
    private var diveOriginX = Float.NaN
    private var diveOriginY = Float.NaN
    private var diveElapsedMs = 0L
    private var diveLastFrameMs = 0L
    private var diveCycleA = Int.MIN_VALUE
    private var diveCycleB = Int.MIN_VALUE
    private var diveLayerA: Bitmap? = null
    private var diveLayerB: Bitmap? = null
    private var divePendingA: Bitmap? = null
    private var divePendingB: Bitmap? = null
    private val divePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val diveDst = RectF()
    private val diveFrame = Runnable { invalidate() }

    /**
     * Источник новых узоров: `(номер цикла, готово) -> Unit`. Ответ — на главном
     * потоке. Нет источника — слои повторяют текущую картинку, погружение всё
     * равно идёт.
     */
    var diveBitmapSupplier: ((cycle: Int, onReady: (Bitmap) -> Unit) -> Unit)? = null

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

    /** Включает и выключает погружение; выключенное оставляет картинку как была. */
    fun setDiveEnabled(enabled: Boolean) {
        if (diveEnabled == enabled) return
        diveEnabled = enabled
        // Пауза продолжает с той же фазы: отсчёт времени копится только в кадрах.
        diveLastFrameMs = 0L
        if (!enabled) {
            removeCallbacks(diveFrame)
            divePendingA = null
            divePendingB = null
            diveLayerA = null
            diveLayerB = null
            diveCycleA = Int.MIN_VALUE
            diveCycleB = Int.MIN_VALUE
        }
        invalidate()
    }

    /** Центр погружения в координатах этого вида — центр главной кнопки. */
    fun setDiveOrigin(x: Float, y: Float) {
        diveOriginX = x
        diveOriginY = y
        invalidate()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        // Сменилась тема или фактура: старые слои с чужим узором выбрасываются,
        // новые придут для своей картинки.
        diveLayerA = null
        diveLayerB = null
        divePendingA = null
        divePendingB = null
        diveCycleA = Int.MIN_VALUE
        diveCycleB = Int.MIN_VALUE
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h * 0.43f
        maxRadius = hypot(w.toDouble(), h.toDouble()).toFloat() * 1.08f
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(diveFrame)
        diveLastFrameMs = 0L
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        diveLastFrameMs = 0L
        if (visibility == VISIBLE) invalidate() else removeCallbacks(diveFrame)
    }

    override fun onDraw(canvas: Canvas) {
        if (revealProgressInternal <= 0f) return
        if (revealProgressInternal >= 0.999f || width <= 0 || height <= 0 || maxRadius <= 0f) {
            drawContent(canvas)
            return
        }

        val revealRadius = maxRadius * revealProgressInternal.coerceAtLeast(0.02f)
        if (preferClipRevealFallback) {
            val checkpoint = canvas.save()
            revealPath.reset()
            revealPath.addCircle(centerX, centerY, revealRadius, Path.Direction.CW)
            canvas.clipPath(revealPath)
            drawContent(canvas)
            canvas.restoreToCount(checkpoint)
            return
        }

        val checkpoint = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        drawContent(canvas)
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

    private fun drawContent(canvas: Canvas) {
        val base = (drawable as? BitmapDrawable)?.bitmap
        // Картинка-заглушка 1×1 (чёрный фон без фактуры) погружаться не может.
        if (!diveEnabled || base == null || base.isRecycled || base.width < 8 || !animatorsEnabled()) {
            super.onDraw(canvas)
            return
        }

        val now = SystemClock.uptimeMillis()
        if (diveLastFrameMs != 0L) diveElapsedMs += (now - diveLastFrameMs).coerceIn(0L, MAX_FRAME_STEP_MS)
        diveLastFrameMs = now

        val t = diveElapsedMs.toDouble() / DIVE_CYCLE_MS
        val cycleA = floor(t).toInt()
        val cycleB = floor(t + 0.5).toInt()
        if (cycleA != diveCycleA) {
            // Слой только что невидим (вес ноль): ставим узор, заказанный заранее,
            // и заказываем следующий.
            divePendingA?.let { diveLayerA = it }
            divePendingA = null
            diveCycleA = cycleA
            requestDiveLayer(cycleA + 1, isLayerA = true)
        }
        if (cycleB != diveCycleB) {
            divePendingB?.let { diveLayerB = it }
            divePendingB = null
            diveCycleB = cycleB
            requestDiveLayer(cycleB + 1, isLayerA = false)
        }
        val phaseA = (t - floor(t)).toFloat()
        val phaseB = (t + 0.5 - floor(t + 0.5)).toFloat()
        val weightB = sin(PI.toFloat() * phaseB).let { it * it }

        val originX = if (diveOriginX.isNaN()) width / 2f else diveOriginX
        val originY = if (diveOriginY.isNaN()) height * 0.43f else diveOriginY
        // Слой A — непрозрачная основа, слой B ложится поверх с весом wB:
        // A·(1 − wB) + B·wB, а 1 − wB и есть вес A (sin² + cos² = 1).
        drawDiveLayer(canvas, diveLayerA ?: base, phaseA, originX, originY, 255)
        drawDiveLayer(canvas, diveLayerB ?: base, phaseB, originX, originY, (weightB * 255f).toInt())

        if (isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE) {
            removeCallbacks(diveFrame)
            postDelayed(diveFrame, FRAME_MS)
        }
    }

    private fun drawDiveLayer(
        canvas: Canvas,
        bitmap: Bitmap,
        phase: Float,
        originX: Float,
        originY: Float,
        alpha: Int,
    ) {
        if (alpha <= 0 || bitmap.isRecycled) return
        // centerCrop, как у самой картинки: узор покрывает экран целиком при 1×,
        // а растяжение относительно точки внутри экрана покрытия не теряет.
        val fit = max(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val drawnW = bitmap.width * fit
        val drawnH = bitmap.height * fit
        diveDst.set(
            (width - drawnW) / 2f,
            (height - drawnH) / 2f,
            (width + drawnW) / 2f,
            (height + drawnH) / 2f,
        )
        val scale = 2f.pow(phase)
        divePaint.alpha = alpha
        val checkpoint = canvas.save()
        canvas.scale(scale, scale, originX, originY)
        canvas.drawBitmap(bitmap, null, diveDst, divePaint)
        canvas.restoreToCount(checkpoint)
    }

    private fun requestDiveLayer(cycle: Int, isLayerA: Boolean) {
        val supplier = diveBitmapSupplier ?: return
        supplier(cycle) { bitmap ->
            if (!diveEnabled) return@supplier
            if (isLayerA && diveCycleA + 1 == cycle) divePendingA = bitmap
            if (!isLayerA && diveCycleB + 1 == cycle) divePendingB = bitmap
        }
    }

    private fun animatorsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private companion object {
        /** Один цикл слоя — приближение вдвое. Медленно: просили спокойное движение. */
        const val DIVE_CYCLE_MS = 60_000.0

        /** ~30 кадров в секунду: глазу этого хватает, батарее — меньше работы. */
        const val FRAME_MS = 33L

        /** Пропуск кадров (пауза, тяжёлый главный поток) не превращается в рывок. */
        const val MAX_FRAME_STEP_MS = 100L
    }
}

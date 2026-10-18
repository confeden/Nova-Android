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
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow

/**
 * Фон главного экрана: раскрывается кругом от главной кнопки и, пока виден,
 * медленно «погружается» в узор.
 *
 * ## Погружение
 *
 * Владелец просил, чтобы фрактал не стоял картинкой, а плыл навстречу — как
 * погружение с центром там, откуда идут кольца подключения. Сделано бесконечным
 * приближением из двух слоёв: каждый растёт от 1× до 2× за [DivePhase.CYCLE_SECONDS] (минуту)
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

    /*
     * Фаза погружения — это время общих часов ([NovaAnimationClock]), а не счётчик
     * вида: тогда пауза, пересоздание вида и возврат попадают в ту же точку цикла.
     * Слои хранятся с номером цикла, которому отвечают; устаревший слой остаётся
     * запасным, пока не придёт новый. Его смена происходит, когда слой невидим
     * (вес нуль), поэтому запасной слой на глаз не виден.
     */
    private var diveCycleA = Int.MIN_VALUE
    private var diveCycleB = Int.MIN_VALUE
    private var diveLayerA: Bitmap? = null
    private var diveLayerB: Bitmap? = null
    private var diveAskedA = Int.MIN_VALUE
    private var diveAskedB = Int.MIN_VALUE

    /** Меняется при смене картинки: ответы на старые заказы тогда отбрасываются. */
    private var diveGeneration = 0
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
        // Слои не сбрасываются: при возврате погружение продолжается с того же узора,
        // а не падает на исходную картинку до следующего цикла.
        if (!enabled) removeCallbacks(diveFrame)
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
        diveCycleA = Int.MIN_VALUE
        diveCycleB = Int.MIN_VALUE
        diveAskedA = Int.MIN_VALUE
        diveAskedB = Int.MIN_VALUE
        diveGeneration++
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h * 0.43f
        maxRadius = hypot(w.toDouble(), h.toDouble()).toFloat() * 1.08f
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(diveFrame)
        (drawable as? NovaAppearanceDrawable)?.stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) {
            invalidate()
        } else {
            removeCallbacks(diveFrame)
            (drawable as? NovaAppearanceDrawable)?.stopAnimation()
        }
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

    /**
     * Останавливает живую анимацию, когда главный экран уходит с переднего плана:
     * погружение и кадры дождя перестают заказываться.
     */
    fun pauseLiveAnimation() {
        removeCallbacks(diveFrame)
        (drawable as? NovaAppearanceDrawable)?.stopAnimation()
    }

    /** Возобновляет живую анимацию с того же места ([NovaAnimationClock]). */
    fun resumeLiveAnimation() {
        invalidate()
    }

    /**
     * Погружение живого дождя: тот же приём, что у растровых слоёв, — два слоя
     * одного и того же дождя, каждый растёт от 1× до 2× и смешивается по тем же
     * весам. Слой на пике невидим, поэтому скачка при сбросе масштаба нет.
     * Узоры не меняются: дождь по часам и так каждый раз новый.
     */
    private fun drawLiveDive(canvas: Canvas, live: NovaAppearanceDrawable) {
        // На остановленных часах фаза стоит: кадр, нарисованный в паузе, её не двигает.
        val t = DivePhase.time(NovaAnimationClock.now())
        val phaseA = DivePhase.phaseOf(t, 0.0)
        val phaseB = DivePhase.phaseOf(t, 0.5)
        val weightB = DivePhase.weightB(phaseB)
        val originX = if (diveOriginX.isNaN()) width / 2f else diveOriginX
        val originY = if (diveOriginY.isNaN()) height * 0.43f else diveOriginY
        val time = live.frameTime()
        live.drawMatrixLayer(canvas, 2f.pow(phaseA), originX, originY, 255, time)
        live.drawMatrixLayer(canvas, 2f.pow(phaseB), originX, originY, (weightB * 255f).toInt(), time)
        live.requestNextRainFrame()
    }

    private fun drawContent(canvas: Canvas) {
        val live = drawable as? NovaAppearanceDrawable
        if (diveEnabled && live != null && live.animated && animatorsEnabled()) {
            drawLiveDive(canvas, live)
            return
        }
        val base = (drawable as? BitmapDrawable)?.bitmap
        // Картинка-заглушка 1×1 (чёрный фон без фактуры) погружаться не может.
        if (!diveEnabled || base == null || base.isRecycled || base.width < 8 || !animatorsEnabled()) {
            super.onDraw(canvas)
            return
        }

        val t = DivePhase.time(NovaAnimationClock.now())
        val cycleA = DivePhase.cycleOf(t)
        val cycleB = DivePhase.cycleOf(t + 0.5)
        val phaseA = DivePhase.phaseOf(t, 0.0)
        val phaseB = DivePhase.phaseOf(t, 0.5)
        val weightB = DivePhase.weightB(phaseB)

        val originX = if (diveOriginX.isNaN()) width / 2f else diveOriginX
        val originY = if (diveOriginY.isNaN()) height * 0.43f else diveOriginY
        // Слой A — непрозрачная основа, слой B ложится поверх с весом wB:
        // A·(1 − wB) + B·wB, а 1 − wB и есть вес A (sin² + cos² = 1).
        drawDiveLayer(canvas, diveLayerFor(cycleA, isLayerB = false) ?: base, phaseA, originX, originY, 255)
        drawDiveLayer(
            canvas,
            diveLayerFor(cycleB, isLayerB = true) ?: base,
            phaseB,
            originX,
            originY,
            (weightB * 255f).toInt(),
        )

        // Кадры идут только на часах в ходу: на паузе фаза стоит и новый кадр не нужен.
        if (isAttachedToWindow && windowVisibility == VISIBLE && visibility == VISIBLE &&
            NovaAnimationClock.isRunning()
        ) {
            removeCallbacks(diveFrame)
            postDelayed(diveFrame, FRAME_MS)
        }
    }

    /**
     * Слой для номера цикла. Если его ещё нет, заказывает и отдаёт запасной —
     * предыдущий слой (или `null`, тогда рисуется исходная картинка).
     */
    private fun diveLayerFor(cycle: Int, isLayerB: Boolean): Bitmap? {
        val have = if (isLayerB) diveCycleB else diveCycleA
        if (have != cycle) requestDiveLayer(cycle, isLayerB)
        return if (isLayerB) diveLayerB else diveLayerA
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

    private fun requestDiveLayer(cycle: Int, isLayerB: Boolean) {
        val asked = if (isLayerB) diveAskedB else diveAskedA
        if (asked == cycle) return
        if (isLayerB) diveAskedB = cycle else diveAskedA = cycle
        val supplier = diveBitmapSupplier ?: return
        val generation = diveGeneration
        supplier(cycle) { bitmap ->
            // Ответ на заказ прежней картинки в этот вид уже не относится.
            if (generation != diveGeneration) return@supplier
            if (isLayerB) {
                diveLayerB = bitmap
                diveCycleB = cycle
            } else {
                diveLayerA = bitmap
                diveCycleA = cycle
            }
            invalidate()
        }
    }

    private fun animatorsEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ValueAnimator.areAnimatorsEnabled()

    private companion object {
        /** ~30 кадров в секунду: глазу этого хватает, батарее — меньше работы. */
        const val FRAME_MS = 33L
    }
}

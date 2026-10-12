package com.example.nova

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.os.Build
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Иней, расползающийся по экрану от нажатой кнопки DNS.
 *
 * ## Почему это отдельный слой поверх всего
 *
 * Лёд обязан лежать **над** содержимым: он и есть то, что «намерзает» на экран.
 * Положить его под панели значило бы нарисовать узор, который ничего не
 * покрывает, — а вся затея как раз про то, что интерфейс промерзает. Поэтому
 * представление добавляется последним ребёнком корня и не участвует в разметке.
 *
 * Касания через него проходят насквозь: `isClickable = false` мало, потому что
 * `View` по умолчанию не перехватывает их только пока не нажимаем на него сам,
 * — здесь ещё и [onTouchEvent] отвечает `false` всегда.
 *
 * ## Почему толщина неровная
 *
 * Ровная заливка читается как затемнение, а не как лёд. Толщину изображают три
 * вещи сразу: осколки разной прозрачности (плита толще — светлее), светлая
 * кромка по краю каждого осколка (на изломе лёд всегда ярче) и налегающие друг
 * на друга слои. Ни один из трёх сам по себе не даёт нужного, и именно поэтому
 * их три.
 *
 * ## Почему всё считается заранее
 *
 * В [onDraw] нет ни одного выделения памяти: фигуры строятся один раз на размер
 * и зерно, а анимация двигает только число. Иначе кадр на слабом телефоне
 * собирал бы мусор ровно тогда, когда его видно (I13 — ничего тяжёлого на
 * главном потоке).
 */
class FrostOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private class IcePlate(val path: Path, val distance: Float, val alpha: Int)

    /**
     * Снежинка — готовый спрайт ([sprite], маска ALPHA_8) плюс движение.
     *
     * Спрайт рисуется один раз при постройке: фрактальная фигура — это сотни
     * отрезков, и гонять их через Path каждый кадр значило бы жечь процессор
     * ради неподвижного рисунка. Кадр двигает только матрицу.
     */
    private class Snowflake(
        val sprite: Bitmap,
        val half: Float,
        var currentX: Float,
        var currentY: Float,
        var currentRotation: Float,
        var distance: Float,
        val alpha: Int,
        val size: Float,
        val fallSpeedPxPerMs: Float,
        val swayAmplitudePx: Float,
        val swayOmega: Float,
        /** Фаза копится по дельтам кадров, а не считается от uptime: `Float` от
         *  миллисекунд с загрузки теряет точность через несколько часов, и
         *  покачивание шло ступеньками. */
        var swayPhase: Float,
        val rotationSpeedDegPerMs: Float,
    )

    private class Tendril(val path: Path, val length: Float, val distance: Float)

    /**
     * Место, которое лёд обходит, — произвольной формы.
     *
     * Форма, а не прямоугольник, потому что у выреза их две разные. Под кнопкой
     * DNS это её собственная пилюля. Под кнопкой подключения — **контуры самих
     * букв**: прямоугольная дырка в ледяном поле читалась как квадрат посреди
     * круглой кнопки, а по буквам лёд обходит надпись и оставляет кнопку
     * замёрзшей, что и просили.
     */
    class FrostZone(val path: Path)

    private val plateFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val veilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val plateEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.9f
    }
    private val flakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val spritePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val spriteMatrix = Matrix()
    private val cachePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val eraserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }

    /**
     * Промёрзший слой целиком (вуаль, осколки, усы) — когда намерзание кончилось.
     *
     * Дальше он не меняется, а снег падает непрерывно: без кэша каждый кадр
     * заново растеризовал сотни осколков и резал усы через PathMeasure.
     */
    private var staticCache: Bitmap? = null
    private var staticCacheFailed = false
    private val tendrilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val plates = ArrayList<IcePlate>()
    private val flakes = ArrayList<Snowflake>()
    private val tendrils = ArrayList<Tendril>()
    private val zones = ArrayList<FrostZone>()
    private val clearPath = Path()

    private val measure = PathMeasure()
    private val segment = Path()

    private var originX = 0f
    private var originY = 0f
    private var maxDistance = 1f
    private var seed = 1L
    private var built = false

    /** 0 — экрана не касался иней, 1 — промёрзло всё. */
    private var progress = 0f

    /** Общая прозрачность слоя: ею слой убирается, не пересобирая фигуры. */
    private var layerAlpha = 1f

    /**
     * Густота снежинок и общая плотность слоя — см. [setIntensity].
     *
     * Держатся отдельно от [layerAlpha]: та принадлежит анимации таяния, а эти
     * два — самому месту, куда лёг лёд, и переживают и заморозку, и оттаивание.
     */
    private var flakeDensity = 1f
    private var opacityScale = 1f

    private var animator: ValueAnimator? = null
    private var lastFrameTimeMs = 0L
    private var flakeRandom = Random(seed)

    init {
        setWillNotDraw(false)
        isClickable = false
        isFocusable = false
        alpha = 0f
    }

    /** Проверяет, включены ли аниматоры в системе (developer options / battery saver). */
    private fun areAnimatorsGloballyEnabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ValueAnimator.areAnimatorsEnabled()
        } else {
            true
        }
    }

    /** Нужен ли непрерывный цикл отрисовки падающего снега прямо сейчас. */
    private fun isFallingAnimationActive(): Boolean {
        return progress > 0f &&
            layerAlpha > 0f &&
            isAttachedToWindow &&
            visibility == VISIBLE &&
            windowVisibility == VISIBLE &&
            flakes.isNotEmpty() &&
            areAnimatorsGloballyEnabled()
    }

    /** Касания проходят насквозь: это украшение, а не элемент управления. */
    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false

    /**
     * Запускает похолодание из точки [x], [y] в координатах этого представления.
     *
     * @param durationMs сколько идёт намерзание. Постепенность здесь — смысл, а
     *        не украшение: мгновенная заливка читается как сбой отрисовки.
     */
    fun freezeFrom(x: Float, y: Float, durationMs: Long = DEFAULT_FREEZE_MS) {
        originX = x
        originY = y
        // Зерно новое на каждую заморозку: просили, чтобы узор каждый раз был
        // свой. Привязка к точке нажатия давала ровно обратное — с одной и той
        // же кнопки рисовался один и тот же лёд.
        seed = System.nanoTime() xor (x.toLong() * 73856093L) xor (y.toLong() * 19349663L)
        built = false
        rebuildIfNeeded()
        animator?.cancel()
        layerAlpha = 1f
        alpha = opacityScale
        // Отсчёт начинается с нуля, а не с текущего значения. После оттаивания
        // гаснет только общая прозрачность, а `progress` остаётся единицей, и
        // анимация «из 1 в 1» давала мгновенный лёд вместо расползания — на
        // устройстве это выглядело так, будто анимации нет вовсе.
        progress = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * Насколько плотно ложится лёд именно здесь.
     *
     * Появилось потому, что один и тот же слой лежит и на весь экран, и на одну
     * строку настроек с подписью в 12sp. На экране плотность — это и есть
     * эффект; над мелким текстом ровно та же плотность делает текст нечитаемым,
     * а ради читаемости убирать лёд целиком не нужно — достаточно реже сыпать
     * снежинки и сделать всю плиту прозрачнее.
     *
     * @param flakeDensity множитель числа снежинок: 1 — как на главном экране.
     * @param opacity множитель прозрачности всего слоя: 0.7 — «на 30 % прозрачнее».
     *
     * Звать **до** [freezeFrom]: снежинки строятся один раз на заморозку.
     */
    fun setIntensity(flakeDensity: Float, opacity: Float) {
        this.flakeDensity = flakeDensity.coerceIn(0f, 4f)
        this.opacityScale = opacity.coerceIn(0f, 1f)
        built = false
        // Лёд мог уже лежать: тогда новая плотность должна быть видна сразу, а
        // не со следующей заморозки.
        if (progress > 0f) alpha = layerAlpha * opacityScale
        invalidate()
    }

    /**
     * Места, которые лёд обходит, — в координатах этого слоя.
     *
     * Вызывать **до** [freezeFrom]: кольца инея по краям вырезов строятся вместе
     * с остальными фигурами, один раз на заморозку.
     */
    fun setClearZones(newZones: List<FrostZone>) {
        zones.clear()
        zones.addAll(newZones)
        clearPath.reset()
        for (zone in zones) {
            clearPath.addPath(zone.path)
        }
        // Вырезы — только клип при отрисовке (и дыры в кэше); пересобирать фигуры
        // незачем. Раньше здесь стоял `built = false`, и при каждой смене подписи
        // кнопки снег прыгал обратно на исходные места.
        releaseStaticCache()
        invalidate()
    }

    /** Оттаивание: лёд не исчезает мгновенно, он тает. */
    fun thaw(durationMs: Long = DEFAULT_THAW_MS) {
        if (progress <= 0f && alpha == 0f) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(layerAlpha, 0f).apply {
            duration = durationMs
            addUpdateListener {
                layerAlpha = it.animatedValue as Float
                alpha = layerAlpha * opacityScale
                if (layerAlpha <= 0f) releaseBitmaps()
                invalidate()
            }
            start()
        }
    }

    /** Мгновенно снимает лёд — для случая «экран пересоздали, анимировать нечего». */
    fun clearFrost() {
        animator?.cancel()
        animator = null
        progress = 0f
        layerAlpha = 0f
        alpha = 0f
        lastFrameTimeMs = 0L
        releaseBitmaps()
        invalidate()
    }

    private fun releaseStaticCache() {
        staticCache?.recycle()
        staticCache = null
        staticCacheFailed = false
    }

    /** Отдаёт память спрайтов и кэша; следующая отрисовка соберёт всё заново. */
    private fun releaseBitmaps() {
        releaseStaticCache()
        for (flake in flakes) flake.sprite.recycle()
        flakes.clear()
        built = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        built = false
        if (progress > 0f) rebuildIfNeeded()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isFallingAnimationActive()) {
            lastFrameTimeMs = 0L
            postInvalidateOnAnimation()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        lastFrameTimeMs = 0L
        Choreographer.getInstance().removeFrameCallback(snowFrameCallback)
        snowFramePending = false
        releaseBitmaps()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && isFallingAnimationActive()) {
            lastFrameTimeMs = 0L
            postInvalidateOnAnimation()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && isFallingAnimationActive()) {
            lastFrameTimeMs = 0L
            postInvalidateOnAnimation()
        }
    }

    private fun rebuildIfNeeded() {
        if (built || width <= 0 || height <= 0) return
        built = true
        releaseStaticCache()
        plates.clear()
        for (flake in flakes) flake.sprite.recycle()
        flakes.clear()
        tendrils.clear()

        val w = width.toFloat()
        val h = height.toFloat()
        maxDistance = max(
            hypot(originX, originY),
            max(
                hypot(w - originX, originY),
                max(hypot(originX, h - originY), hypot(w - originX, h - originY)),
            ),
        ).coerceAtLeast(1f)

        val random = Random(seed)
        buildPlates(w, h, random)
        buildFlakes(w, h, random)
        buildTendrils(random)
    }

    /**
     * Осколки льда — дрожащая сетка, разбитая на треугольники.
     *
     * Сетка, а не разбросанные многоугольники: у настоящего льда осколки
     * смыкаются, и зазоры между ними сразу выдают рисунок. Дрожание узлов даёт
     * неровность, а разная прозрачность — разную толщину.
     */
    private fun buildPlates(w: Float, h: Float, random: Random) {
        val cell = max(w, h) / PLATE_GRID
        val cols = (w / cell).toInt() + 2
        val rows = (h / cell).toInt() + 2
        val jitter = cell * 0.42f
        val nodes = Array(rows + 1) { row ->
            Array(cols + 1) { col ->
                val jx = (random.nextFloat() - 0.5f) * 2f * jitter
                val jy = (random.nextFloat() - 0.5f) * 2f * jitter
                floatArrayOf(col * cell + jx, row * cell + jy)
            }
        }
        for (row in 0 until rows) {
            for (col in 0 until cols) {
                val a = nodes[row][col]
                val b = nodes[row][col + 1]
                val c = nodes[row + 1][col + 1]
                val d = nodes[row + 1][col]
                addPlate(a, b, c, random)
                addPlate(a, c, d, random)
            }
        }
        plates.sortBy { it.distance }
    }

    private fun addPlate(a: FloatArray, b: FloatArray, c: FloatArray, random: Random) {
        val path = Path().apply {
            moveTo(a[0], a[1])
            lineTo(b[0], b[1])
            lineTo(c[0], c[1])
            close()
        }
        val cx = (a[0] + b[0] + c[0]) / 3f
        val cy = (a[1] + b[1] + c[1]) / 3f
        // Разброс прозрачности и есть «неравномерная толщина». Нижняя граница не
        // ноль: полностью прозрачный осколок оставил бы дыру в ледяном поле.
        val alpha = PLATE_ALPHA_MIN + random.nextInt(PLATE_ALPHA_SPREAD)
        plates.add(IcePlate(path, hypot(cx - originX, cy - originY), alpha))
    }

    /**
     * Снежинки: шесть одинаковых фрактальных лучей, и у каждой снежинки свой луч.
     *
     * Настоящая шестилучевая симметрия, а не звёздочка: именно повтор одного
     * луча шесть раз читается как снежинка, даже когда фигура мелкая. Сам луч
     * растёт рекурсивно — на ветке сидят ветки поменьше, на них ещё меньше, — и
     * число, места, длины и углы веток выпадают заново на каждую снежинку,
     * поэтому двух одинаковых нет.
     */
    private fun buildFlakes(w: Float, h: Float, random: Random) {
        val density = resources.displayMetrics.density
        val minSpeed = FLAKE_SPEED_MIN_DP_S * density / 1000f
        val maxSpeed = FLAKE_SPEED_MAX_DP_S * density / 1000f
        val minSwayAmp = SWAY_AMP_MIN_DP * density
        val maxSwayAmp = SWAY_AMP_MAX_DP * density

        val count = (FLAKE_COUNT * flakeDensity).roundToInt().coerceAtLeast(0)
        repeat(count) {
            val cx = random.nextFloat() * w
            val cy = random.nextFloat() * h
            val size = FLAKE_MIN_PX + random.nextFloat() * FLAKE_SPREAD_PX
            val sizeFraction = ((size - FLAKE_MIN_PX) / FLAKE_SPREAD_PX).coerceIn(0f, 1f)
            val sprite = renderFlakeSprite(size, sizeFraction, random) ?: return@repeat

            // Скорость падения зависит от размера снежинки: крупные падают быстрее (параллакс).
            val fallSpeed = minSpeed + sizeFraction * (maxSpeed - minSpeed)

            // Покачивание по синусоиде: амплитуда 6..18 dp, период 3..7 с.
            val swayAmp = minSwayAmp + random.nextFloat() * (maxSwayAmp - minSwayAmp)
            val periodMs = (SWAY_PERIOD_MIN_S + random.nextFloat() * (SWAY_PERIOD_MAX_S - SWAY_PERIOD_MIN_S)) * 1000f
            val swayOmega = (2f * PI.toFloat()) / periodMs
            val swayPhase = random.nextFloat() * 2f * PI.toFloat()

            // Медленное вращение: несколько градусов в секунду с индивидуальным направлением знака.
            val rotSign = if (random.nextBoolean()) 1f else -1f
            val rotSpeedDegS = ROTATION_SPEED_MIN_DEG_S + random.nextFloat() * (ROTATION_SPEED_MAX_DEG_S - ROTATION_SPEED_MIN_DEG_S)
            val rotSpeedDegPerMs = rotSign * (rotSpeedDegS / 1000f)

            flakes.add(
                Snowflake(
                    sprite = sprite,
                    half = sprite.width / 2f,
                    currentX = cx,
                    currentY = cy,
                    currentRotation = random.nextFloat() * 60f,
                    distance = hypot(cx - originX, cy - originY),
                    alpha = FLAKE_ALPHA_MIN + random.nextInt(FLAKE_ALPHA_SPREAD),
                    size = size,
                    fallSpeedPxPerMs = fallSpeed,
                    swayAmplitudePx = swayAmp,
                    swayOmega = swayOmega,
                    swayPhase = swayPhase,
                    rotationSpeedDegPerMs = rotSpeedDegPerMs,
                )
            )
        }
        flakeRandom = Random(seed xor 0x5DEECE66DL)
        lastFrameTimeMs = 0L
    }

    /**
     * Правила роста одного луча — выпадают на снежинку целиком.
     *
     * [count], [at], [ratio] и [angle] — по уровню вложенности; веточки
     * зеркальны относительно своей ветки, иначе симметрия снежинки теряется.
     */
    private class ArmGenome(
        val depth: Int,
        val count: IntArray,
        val at: Array<FloatArray>,
        val ratio: Array<FloatArray>,
        val angle: FloatArray,
    )

    private fun randomGenome(sizeFraction: Float, random: Random): ArmGenome {
        // Глубина от размера: у мелкой снежинки третий уровень сливается в пятно.
        val depth = when {
            sizeFraction > 0.55f -> 3
            sizeFraction > 0.15f -> 2
            else -> 1 + random.nextInt(2)
        }
        val count = IntArray(depth) { level -> if (level == 0) 1 + random.nextInt(3) else 1 + random.nextInt(2) }
        val at = Array(depth) { level ->
            FloatArray(count[level]) { 0.22f + random.nextFloat() * 0.62f }.also { it.sort() }
        }
        val ratio = Array(depth) { level ->
            FloatArray(count[level]) { i ->
                // Ближе к основанию ветки длиннее — так растёт настоящий дендрит.
                val base = 0.26f + random.nextFloat() * 0.26f
                base * (1f - 0.45f * at[level][i])
            }
        }
        val angle = FloatArray(depth) { (PI.toFloat() / 3f) + (random.nextFloat() - 0.5f) * 0.55f }
        return ArmGenome(depth, count, at, ratio, angle)
    }

    private fun renderFlakeSprite(size: Float, sizeFraction: Float, random: Random): Bitmap? {
        val genome = randomGenome(sizeFraction, random)
        val path = Path()
        val pad = FLAKE_WIDTH * 2f
        val half = size + pad
        for (arm in 0 until 6) {
            val angle = arm * (PI.toFloat() / 3f)
            growArm(path, half, half, angle, size, 0, genome)
        }
        // Изредка — шестиугольная пластинка в центре: у живых снежинок она бывает.
        if (random.nextFloat() < 0.35f) {
            val r = size * (0.12f + random.nextFloat() * 0.12f)
            for (k in 0..6) {
                val a = k * (PI.toFloat() / 3f)
                val x = half + cos(a) * r
                val y = half + sin(a) * r
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
        }
        val side = (half * 2f).toInt().coerceAtLeast(2)
        val bitmap = try {
            Bitmap.createBitmap(side, side, Bitmap.Config.ALPHA_8)
        } catch (_: Throwable) {
            return null
        }
        flakePaint.color = ICE_HIGHLIGHT
        flakePaint.alpha = 255
        flakePaint.strokeWidth = FLAKE_WIDTH
        Canvas(bitmap).drawPath(path, flakePaint)
        return bitmap
    }

    private fun growArm(
        path: Path,
        x: Float,
        y: Float,
        angle: Float,
        length: Float,
        level: Int,
        genome: ArmGenome,
    ) {
        val tipX = x + cos(angle) * length
        val tipY = y + sin(angle) * length
        path.moveTo(x, y)
        path.lineTo(tipX, tipY)
        if (level >= genome.depth) return
        for (i in 0 until genome.count[level]) {
            val child = length * genome.ratio[level][i]
            if (child < MIN_BRANCH_PX) continue
            val bx = x + cos(angle) * length * genome.at[level][i]
            val by = y + sin(angle) * length * genome.at[level][i]
            growArm(path, bx, by, angle + genome.angle[level], child, level + 1, genome)
            growArm(path, bx, by, angle - genome.angle[level], child, level + 1, genome)
        }
    }

    /**
     * Ветвящиеся усы инея от точки нажатия.
     *
     * Они и создают ощущение, что холод именно **ползёт**: осколки появляются
     * по фронту, а усы тянутся впереди него, как на настоящем стекле.
     */
    private fun buildTendrils(random: Random) {
        repeat(TENDRIL_COUNT) { index ->
            val angle = (index.toFloat() / TENDRIL_COUNT) * 2f * Math.PI.toFloat() +
                (random.nextFloat() - 0.5f) * 0.5f
            val path = Path()
            path.moveTo(originX, originY)
            growTendril(path, originX, originY, angle, maxDistance * 0.52f, 0, random)
            measure.setPath(path, false)
            var total = 0f
            do {
                total += measure.length
            } while (measure.nextContour())
            tendrils.add(Tendril(path, max(total, 1f), 0f))
        }
    }

    private fun growTendril(
        path: Path,
        x: Float,
        y: Float,
        angle: Float,
        length: Float,
        depth: Int,
        random: Random,
    ) {
        if (depth > TENDRIL_DEPTH || length < 8f) return
        val wobble = (random.nextFloat() - 0.5f) * 0.55f
        val nx = x + cos(angle + wobble) * length
        val ny = y + sin(angle + wobble) * length
        path.moveTo(x, y)
        path.lineTo(nx, ny)
        val childLength = length * 0.58f
        growTendril(path, nx, ny, angle + BRANCH_ANGLE * 0.8f, childLength, depth + 1, random)
        growTendril(path, nx, ny, angle - BRANCH_ANGLE * 0.8f, childLength, depth + 1, random)
        if (random.nextFloat() < 0.45f) {
            growTendril(path, nx, ny, angle + wobble * 0.3f, childLength * 0.8f, depth + 1, random)
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (progress <= 0f || layerAlpha <= 0f) return
        rebuildIfNeeded()
        if (plates.isEmpty()) return

        updateFlakes()

        val front = progress * maxDistance
        // Фронт «размазан», а не обрезан по окружности: резкая граница выглядит
        // как круг поверх экрана, а не как наступающий холод.
        val feather = maxDistance * FRONT_FEATHER

        // Вырезы делаются клипом, а не проверкой каждой фигуры: осколков
        // несколько сотен, и «не рисовать те, что пересекают кнопку» оставило бы
        // рваную дыру вместо ровного края.
        //
        // Когда лёд лёг, вырезы уже пробиты в кэше, и клипа нет вовсе: контур
        // букв — сложный путь, и клип по нему каждый кадр стоил на Redmi Note 9S
        // около четверти ядра RenderThread. Снежинки не клипуются никогда —
        // это падающий снег, ему можно пролететь и над надписью.
        val cache = if (progress >= 1f) staticCacheOrNull() else null
        if (cache != null) {
            cachePaint.alpha = (255 * layerAlpha).toInt().coerceIn(0, 255)
            canvas.drawBitmap(cache, 0f, 0f, cachePaint)
        } else {
            val clipped = clearPath.isEmpty.not()
            if (clipped) {
                canvas.save()
                canvas.clipOutPath(clearPath)
            }
            drawStatic(canvas, front, feather)
            if (clipped) canvas.restore()
        }
        drawFlakes(canvas, front, feather)

        if (isFallingAnimationActive()) {
            scheduleSnowFrame()
        }
    }

    private var snowFramePending = false
    private var vsyncsToSkip = 0
    private val snowFrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (vsyncsToSkip > 0) {
                vsyncsToSkip--
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                snowFramePending = false
                invalidate()
            }
        }
    }

    /**
     * Следующий кадр снега — не на каждый vsync, а примерно 30 раз в секунду.
     *
     * Каждый кадр снега заставляет окно перекомпоновать весь экран под слоем;
     * на 60 Гц это стоило RenderThread'у почти ядро. Снег медленный (13..42
     * dp/с, меньше полутора dp за кадр), и ровные 30 к/с — каждый второй vsync
     * на 60 Гц, каждый четвёртый на 120 Гц — глазу неотличимы от 60, а
     * перерисовок вдвое-вчетверо меньше. Пока лёд намерзает, кадры идут
     * своим чередом от аниматора.
     */
    private fun scheduleSnowFrame() {
        if (progress < 1f) {
            postInvalidateOnAnimation()
            return
        }
        if (snowFramePending) return
        snowFramePending = true
        val refresh = display?.refreshRate ?: 60f
        vsyncsToSkip = ((refresh / SNOW_FPS).roundToInt() - 1).coerceAtLeast(0)
        Choreographer.getInstance().postFrameCallback(snowFrameCallback)
    }

    private fun drawStatic(canvas: Canvas, front: Float, feather: Float) {
        drawVeil(canvas, front)
        drawPlates(canvas, front, feather)
        drawTendrils(canvas)
    }

    /**
     * Неподвижный слой, отрисованный один раз в битмап, — или `null`, если
     * памяти не нашлось (тогда рисуем по-старому, каждый кадр).
     */
    private fun staticCacheOrNull(): Bitmap? {
        staticCache?.let { return it }
        if (staticCacheFailed || width <= 0 || height <= 0) return null
        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            staticCacheFailed = true
            return null
        }
        // Прозрачность таяния накладывается при выводе битмапа, поэтому в кэш
        // слой ложится непрозрачным.
        val savedAlpha = layerAlpha
        layerAlpha = 1f
        val cacheCanvas = Canvas(bitmap)
        drawStatic(cacheCanvas, maxDistance, maxDistance * FRONT_FEATHER)
        if (!clearPath.isEmpty) cacheCanvas.drawPath(clearPath, eraserPaint)
        layerAlpha = savedAlpha
        staticCache = bitmap
        return bitmap
    }

    /**
     * Непрерывный пересчёт координат снежинок (падение, покачивание, вращение).
     *
     * Время — кадровое (vsync), дельта зажимается до 50 мс
     * после паузы/сворачивания, чтобы снежинки не телепортировались сквозь экран.
     */
    private fun updateFlakes() {
        // Время кадра (vsync), а не «сейчас»: иначе шаг движения дрожит вместе
        // с тем, в какой момент кадра нас позвали, и снег идёт рывками.
        val now = AnimationUtils.currentAnimationTimeMillis()
        if (lastFrameTimeMs == 0L) {
            lastFrameTimeMs = now
            return
        }
        val deltaMs = (now - lastFrameTimeMs).coerceIn(0L, MAX_FRAME_DELTA_MS)
        lastFrameTimeMs = now
        if (deltaMs <= 0L || !areAnimatorsGloballyEnabled()) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        for (flake in flakes) {
            flake.currentY += flake.fallSpeedPxPerMs * deltaMs
            flake.currentRotation += flake.rotationSpeedDegPerMs * deltaMs

            // Респаун сверху при выходе за нижнюю границу (с запасом на размер снежинки).
            val margin = flake.size * 1.5f
            if (flake.currentY > h + margin) {
                flake.currentY = -margin - flakeRandom.nextFloat() * (h * 0.15f)
                flake.currentX = flakeRandom.nextFloat() * w
            }

            flake.swayPhase += flake.swayOmega * deltaMs
            if (flake.swayPhase > TWO_PI) flake.swayPhase -= TWO_PI

            // Расстояние до центра намерзания считается от текущей позиции снежинки,
            // чтобы она органично проявлялась при приближении фронта холода.
            val swayX = flake.swayAmplitudePx * sin(flake.swayPhase)
            val renderX = flake.currentX + swayX
            flake.distance = hypot(renderX - originX, flake.currentY - originY)
        }
    }

    /**
     * Общая морозная вуаль под осколками.
     *
     * Без неё экран читается как треснувшее стекло, а не как промёрзший: одни
     * кромки дают рисунок линий, а ощущение «всё замерзает» даёт именно ровная
     * молочная плёнка, на которую сверху ложатся осколки разной толщины.
     */
    private fun drawVeil(canvas: Canvas, front: Float) {
        val reach = (front / maxDistance).coerceIn(0f, 1f)
        val alpha = (VEIL_ALPHA * reach * layerAlpha).toInt().coerceIn(0, 255)
        if (alpha <= 0) return
        veilPaint.color = ICE_FILL
        veilPaint.alpha = alpha
        canvas.drawCircle(originX, originY, front, veilPaint)
    }

    private fun drawPlates(canvas: Canvas, front: Float, feather: Float) {
        for (plate in plates) {
            if (plate.distance > front) break
            val local = localProgress(plate.distance, front, feather)
            if (local <= 0f) continue
            val alpha = (plate.alpha * local * layerAlpha).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue
            plateFill.color = ICE_FILL
            plateFill.alpha = alpha
            canvas.drawPath(plate.path, plateFill)
            plateEdge.color = ICE_EDGE
            plateEdge.alpha = (alpha * 0.42f).toInt().coerceIn(0, 255)
            canvas.drawPath(plate.path, plateEdge)
        }
    }

    private fun drawTendrils(canvas: Canvas) {
        tendrilPaint.color = ICE_EDGE
        tendrilPaint.alpha = (TENDRIL_ALPHA * layerAlpha).toInt().coerceIn(0, 255)
        // Усы идут впереди фронта — потому холод и «ползёт», а не «заливается».
        val reach = min(1f, progress * TENDRIL_LEAD)
        for (tendril in tendrils) {
            measure.setPath(tendril.path, false)
            var drawn = 0f
            do {
                val want = (tendril.length * reach) - drawn
                if (want <= 0f) break
                segment.reset()
                val take = min(measure.length, want)
                if (take > 0f && measure.getSegment(0f, take, segment, true)) {
                    tendrilPaint.strokeWidth = TENDRIL_WIDTH
                    canvas.drawPath(segment, tendrilPaint)
                }
                drawn += measure.length
            } while (measure.nextContour())
        }
    }

    private fun drawFlakes(canvas: Canvas, front: Float, feather: Float) {
        spritePaint.color = ICE_HIGHLIGHT
        for (flake in flakes) {
            val local = localProgress(flake.distance, front, feather)
            if (local <= 0f) continue
            val alpha = (flake.alpha * local * layerAlpha).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue
            spritePaint.alpha = alpha

            val renderX = flake.currentX + flake.swayAmplitudePx * sin(flake.swayPhase)
            // Снежинка не появляется целиком, а вырастает: масштаб от точки её
            // центра, иначе фигуры «мигают» на месте.
            val scale = 0.35f + 0.65f * local
            spriteMatrix.setTranslate(-flake.half, -flake.half)
            spriteMatrix.postRotate(flake.currentRotation)
            if (scale != 1f) spriteMatrix.postScale(scale, scale)
            spriteMatrix.postTranslate(renderX, flake.currentY)
            canvas.drawBitmap(flake.sprite, spriteMatrix, spritePaint)
        }
    }

    /** Насколько «дозрела» фигура на расстоянии [distance] при фронте [front]. */
    private fun localProgress(distance: Float, front: Float, feather: Float): Float {
        if (feather <= 0f) return 1f
        return ((front - distance) / feather).coerceIn(0f, 1f)
    }

    companion object {
        /** Светлые, но не яркие тона: лёд читается прозрачным, а не белой заливкой. */
        private const val ICE_FILL = 0xFFE8F2FB.toInt()
        private const val ICE_EDGE = 0xFFFFFFFF.toInt()
        private const val ICE_HIGHLIGHT = 0xFFF2F8FF.toInt()

        /** Прозрачность осколка: нижняя граница и разброс. Разброс и есть толщина. */
        private const val PLATE_ALPHA_MIN = 26
        private const val PLATE_ALPHA_SPREAD = 74

        private const val FLAKE_ALPHA_MIN = 70
        private const val FLAKE_ALPHA_SPREAD = 90
        private const val FLAKE_COUNT = 26

        /** На 20 % мельче прежних 16..60 px (просил владелец). */
        private const val FLAKE_MIN_PX = 12.8f
        private const val FLAKE_SPREAD_PX = 35.2f
        private const val FLAKE_WIDTH = 1.3f

        /** Ветка короче — уже не рисуется: на экране она сливается в точку. */
        private const val MIN_BRANCH_PX = 1.6f
        private const val TWO_PI = (2.0 * PI).toFloat()

        /** Параметры непрерывного падения снежинок: на 30 % медленнее прежних 18..60 dp/с. */
        private const val FLAKE_SPEED_MIN_DP_S = 12.6f
        private const val FLAKE_SPEED_MAX_DP_S = 42f
        private const val SWAY_AMP_MIN_DP = 6f
        private const val SWAY_AMP_MAX_DP = 18f
        private const val SWAY_PERIOD_MIN_S = 3f
        private const val SWAY_PERIOD_MAX_S = 7f
        private const val ROTATION_SPEED_MIN_DEG_S = 4f
        private const val ROTATION_SPEED_MAX_DEG_S = 12f
        private const val MAX_FRAME_DELTA_MS = 50L
        private const val SNOW_FPS = 30f

        private const val TENDRIL_COUNT = 9
        private const val TENDRIL_DEPTH = 4
        private const val TENDRIL_ALPHA = 86f
        private const val TENDRIL_WIDTH = 1.6f

        /** Усы обгоняют фронт: без этого холод «заливается», а не ползёт. */
        private const val TENDRIL_LEAD = 1.22f

        private const val VEIL_ALPHA = 30f
        private const val PLATE_GRID = 13f
        private const val FRONT_FEATHER = 0.22f
        private const val BRANCH_ANGLE = 0.9f

        /**
         * Намерзание идёт медленно намеренно: просили, чтобы холод именно
         * расползался. Две секунды читались как заливка, а не как движение.
         */
        const val DEFAULT_FREEZE_MS = 5200L
        const val DEFAULT_THAW_MS = 620L

        /**
         * Лёд поверх мелкого текста — строка настроек и диалог профилей.
         *
         * Полная плотность съедала подпись в 12sp («Профилей 1, ни один пока не
         * подключается»): снежинки ложились прямо на буквы, а плита добавляла
         * сверху молочную плёнку. Здесь снежинок втрое меньше, и весь слой на
         * 30 % прозрачнее — лёд остаётся, текст остаётся читаемым.
         */
        const val READABLE_FLAKE_DENSITY = 0.34f
        const val READABLE_OPACITY = 0.7f

        /**
         * Намерзание в диалоге: короче экранного.
         *
         * Диалог открывают, чтобы сразу вставить ссылку, и пять секунд
         * расползания читались бы как «интерфейс ещё грузится».
         */
        const val DIALOG_FREEZE_MS = 2000L
    }
}

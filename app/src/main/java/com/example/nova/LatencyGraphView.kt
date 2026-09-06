package com.example.nova

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Shader
import android.os.Parcelable
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View

/**
 * Шкала задержки: непрерывный поток вместо столбиков.
 *
 * ## Три требования владельца и как каждое выполнено
 *
 * ### 1. Поток, а не рывок раз в пару секунд
 *
 * Первая версия считала фазу сдвига от момента последнего замера:
 * `phase = (now - lastSample) / ожидаемый интервал`, обрезанная сверху единицей.
 * Пока замеры шли ровно по расписанию, это работало. Но они не идут: тик экрана
 * — две секунды, поверх него `measureLatency()` зовут ещё и `onResume`, и
 * `renderConnectedState`, а сам замер занимает от десятков миллисекунд до трёх
 * секунд. Ранний замер обрывал фазу на середине — линия прыгала влево на остаток
 * шага; поздний упирался в единицу — линия замирала, потом дёргалась дальше.
 * Хуже всего это читалось слева, где к рывку добавлялся вертикальный край
 * заливки.
 *
 * Здесь сдвиг — **собственная величина** [shift], измеряемая в шагах, и она
 * непрерывна по построению:
 *
 * * приход замера двигает всё содержимое пути ровно на шаг влево, поэтому
 *   `shift` уменьшается на единицу — в этот кадр на экране не двигается **ничего**;
 * * между замерами `shift` растёт с частотой «шаг за интервал», и скорость
 *   корректируется мягко: `1 + [SHIFT_GAIN] * ([SHIFT_TARGET] - shift)`. Это
 *   регулятор **скорости**, а не положения: ошибка гасится за несколько кадров
 *   плавным ускорением, а не скачком;
 * * у верхней границы ([SHIFT_MAX]) скорость гасится до нуля непрерывно, так что
 *   пропавший замер линию не обрывает — она просто останавливается.
 *
 * Ожидаемый интервал — скользящее среднее по факту, а не константа.
 *
 * ### 2. Цвет принадлежит замеру, а не всей шкале
 *
 * Раньше цвет был один на весь вид и плавно ехал к цвету последнего замера: жёлтый
 * участок истории зеленел вместе с остальными, стоило пингу выправиться. Теперь
 * цвет хранится **на замер** ([colors]) и рисуется горизонтальным градиентом:
 * каждая точка держит свой цвет там, где она стоит, и уезжает вместе с ним.
 * Переход между соседями сглажен биномиальным ядром на пять отсчётов
 * ([smoothedColorAt]) — внутри однородного участка цвет остаётся точным, а на
 * границе размывается на пару замеров вместо резкой ступени.
 *
 * ### 3. Цветная тень под линией
 *
 * Прежняя заливка брала вертикальный градиент от **верха вида**: при хорошем
 * пинге линия стоит у самого низа, и на неё приходился уже прозрачный хвост —
 * тени не было видно вовсе. Теперь прозрачность привязана к самой линии:
 * вертикальная рампа ([fadeRamp]) начинается на медианной высоте кривой и гаснет
 * к низу вида. Выше начала рампа зажата ([Shader.TileMode.CLAMP]), поэтому под
 * всплеском заливка насыщена целиком, а не выцветает.
 *
 * Цвет и прозрачность соединяются [ComposeShader] в режиме
 * [PorterDuff.Mode.DST_IN]: цвет берётся из горизонтального градиента, альфа — из
 * рампы. Рампа сделана растровой намеренно: до API 28 аппаратный конвейер не
 * умеет складывать два шейдера **одного** типа, а градиент с растром — умеет.
 *
 * ## Чем это платится
 *
 * Тем же, чем и раньше. Путь и шейдеры собираются **на замер**, а не на кадр: в
 * кадре остаются сдвиг холста и четыре отрисовки готовых `Path`, без единого
 * выделения памяти. Кадры ограничены [FRAME_INTERVAL_MS] и идут, только пока вид
 * прикреплён, показан и данные есть.
 */
class LatencyGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Сколько точек держим. Больше — гуще линия, дороже путь. */
    private val capacity = 48

    /** Значения в порядке прихода; последнее — самое свежее. */
    private val values = IntArray(capacity)

    /** Цвет каждого замера, тот же порядок. Считается один раз, на приход. */
    private val colors = IntArray(capacity)
    private var count = 0

    /** Верх шкалы в миллисекундах. Всё, что выше, прижимается к потолку. */
    private val maxLatency = 500f

    /**
     * Ожидаемый шаг между замерами, скользящее среднее по факту.
     *
     * Замеры приходят примерно раз в две секунды, и именно на этот срок
     * растягивается сдвиг линии влево. Среднее, а не последний интервал: один
     * внеочередной замер (их шлют `onResume` и отрисовка подключённого состояния)
     * не должен разгонять поток вдвое.
     */
    private var sampleIntervalMs = 2_000f
    private var lastSampleAtMs = 0L

    /**
     * Сдвиг содержимого влево, **в шагах**. Единственное, что меняется в кадре.
     *
     * Ноль — новейшая точка стоит ровно у правого края. Установившееся значение
     * около [SHIFT_TARGET]: небольшой запас нужен, чтобы дрожание интервала
     * съедалось им, а не остановкой линии.
     */
    private var shift = 0f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val linePath = Path()
    private val fillPath = Path()

    /** Высота, с которой начинается затухание заливки. Медиана кривой. */
    private var fadeAnchorY = 0f

    /** Горизонтальный градиент «цвет на замер». Общий для линии, сияния и заливки. */
    private var colorShader: Shader? = null

    /** Вертикальная рампа прозрачности. Один столбец пикселей, растягивается матрицей. */
    private var fadeRamp: Bitmap? = null
    private val fadeMatrix = Matrix()

    private val sortedYBuffer = FloatArray(capacity)

    private var animating = false
    private var lastFrameAtMs = 0L

    private val frame = object : Runnable {
        override fun run() {
            if (!animating) return
            val now = SystemClock.uptimeMillis()
            if (now - lastFrameAtMs >= FRAME_INTERVAL_MS) {
                stepFlow(now)
                invalidate()
            }
            postOnAnimation(this)
        }
    }

    fun addLatency(ms: Int) {
        val now = SystemClock.uptimeMillis()
        if (lastSampleAtMs != 0L) {
            val observed = (now - lastSampleAtMs).toFloat()
                .coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS)
            sampleIntervalMs += (observed - sampleIntervalMs) * INTERVAL_EMA
        }
        lastSampleAtMs = now
        val color = colorForLatency(ms)
        val hadContent = count > 0
        if (count < capacity) {
            values[count] = ms
            colors[count] = color
            count++
        } else {
            System.arraycopy(values, 1, values, 0, capacity - 1)
            System.arraycopy(colors, 1, colors, 0, capacity - 1)
            values[capacity - 1] = ms
            colors[capacity - 1] = color
        }
        // Замер сдвинул всё содержимое пути на шаг влево — компенсируем сдвигом
        // ровно на тот же шаг. В этот кадр на экране не двигается ничего: именно
        // отсюда берётся непрерывность потока.
        //
        // Первому замеру компенсировать нечего: на экране ещё пусто. Без этой
        // оговорки он уезжал на шаг **за** правый край и не рисовался вовсе.
        if (hadContent) shift = (shift - 1f).coerceAtLeast(SHIFT_MIN)
        rebuild()
        startAnimation()
        invalidate()
    }

    fun clearLatencies() {
        count = 0
        lastSampleAtMs = 0L
        shift = 0f
        sampleIntervalMs = 2_000f
        linePath.reset()
        fillPath.reset()
        colorShader = null
        linePaint.shader = null
        glowPaint.shader = null
        fillPaint.shader = null
        stopAnimation()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Рампа зависит только от высоты в своих собственных координатах —
        // растягивает её матрица, — поэтому пересоздавать её на каждый размер не
        // надо. А вот путь и градиенты живут в пикселях вида.
        rebuild()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (count > 0) startAnimation()
    }

    override fun onDetachedFromWindow() {
        stopAnimation()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE && count > 0) startAnimation() else stopAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && count > 0) startAnimation() else stopAnimation()
    }

    /**
     * Заводит цикл кадров, если вид **правда** на экране.
     *
     * Условие смотрит на окно, а не только на свой флаг видимости, и это не
     * перестраховка. Свой `visibility` у остановленной активности остаётся
     * `VISIBLE` — меняется видимость окна. А замер приходит и после ухода с
     * экрана: `onPause` снимает тик, но уже запущенная проба досиживает свои три
     * секунды и зовёт [addLatency] из своего потока. Гейт по одному лишь
     * `visibility` она проходила, `animating` возвращался в `true`, и цикл
     * `postOnAnimation` продолжал будить Choreographer на каждом кадре, пока
     * активность жива. Отдельно тот же случай на восстановлении: `View`
     * рассылает `onWindowVisibilityChanged` только когда окно не `GONE`, так что
     * прикрепление в фоне остановить цикл было некому.
     */
    private fun startAnimation() {
        if (animating || !isAttachedToWindow) return
        if (!isShown || windowVisibility != VISIBLE) return
        animating = true
        // Ноль — признак «первый кадр после паузы»: он только заводит часы. Иначе
        // после погашенного экрана линия прыгнула бы на весь простой сразу.
        lastFrameAtMs = 0L
        postOnAnimation(frame)
    }

    private fun stopAnimation() {
        animating = false
        removeCallbacks(frame)
    }

    /**
     * Один шаг потока.
     *
     * Скорость номинально «шаг за интервал» и правится пропорционально
     * расхождению с [SHIFT_TARGET]. Регулятор именно скоростной: положение он
     * никогда не переставляет, поэтому и рывка не бывает — только чуть более
     * быстрое или чуть более медленное течение.
     */
    private fun stepFlow(now: Long) {
        if (lastFrameAtMs == 0L) {
            lastFrameAtMs = now
            return
        }
        val dt = (now - lastFrameAtMs).coerceIn(0L, MAX_FRAME_STEP_MS).toFloat()
        lastFrameAtMs = now
        if (dt <= 0f || count == 0) return
        val nominal = 1f / sampleIntervalMs
        val correction = (1f + SHIFT_GAIN * (SHIFT_TARGET - shift)).coerceIn(0.25f, 3f)
        // Тормоз у верхней границы: без него линия упиралась бы в неё на полном
        // ходу. Пропавший замер должен останавливать поток плавно.
        val headroom = ((SHIFT_MAX - shift) / SHIFT_EASE).coerceIn(0f, 1f)
        shift = (shift + nominal * correction * headroom * dt).coerceAtMost(SHIFT_MAX)
    }

    private fun rebuild() {
        rebuildPaths()
        rebuildShaders()
    }

    /**
     * Пересобирает линию и заливку.
     *
     * Зовётся на приход замера и на смену размера — то есть считанные разы в
     * минуту, а не шестьдесят раз в секунду.
     */
    private fun rebuildPaths() {
        linePath.reset()
        fillPath.reset()
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || count == 0) return

        val inset = strokeWidthPx() * 0.5f
        val usable = (h - inset * 2f).coerceAtLeast(1f)

        fun yAt(index: Int): Float {
            val ms = values[index]
            // Отсутствующий замер (-1) рисуем у самого низа, а не в нуле: линия
            // не должна разрываться, а провал и так виден по цвету.
            val clamped = if (ms < 0) 0 else ms.coerceAtMost(maxLatency.toInt())
            return h - inset - (clamped / maxLatency) * usable
        }

        var prevX = xAt(0)
        var prevY = yAt(0)
        sortedYBuffer[0] = prevY
        // Вход из-за левой границы. Даёт сразу три вещи:
        //
        // * линия не кончается внутри вида, а уходит за край — обрыва, который
        //   читается как срез, нет ни при одном значении сдвига;
        // * у заливки нет вертикальной стенки, пока буфер не полон;
        // * один замер рисуется отрезком, а не точкой: `moveTo` без единого
        //   сегмента Skia не рисует вовсе, и шкала оставалась пустой целый
        //   интервал после каждого переподключения, хотя «Ping: N мс» рядом уже
        //   горел.
        val lead = leadInX()
        linePath.moveTo(lead, prevY)
        linePath.lineTo(prevX, prevY)
        fillPath.moveTo(lead, h)
        fillPath.lineTo(lead, prevY)
        fillPath.lineTo(prevX, prevY)
        for (i in 1 until count) {
            val x = xAt(i)
            val y = yAt(i)
            sortedYBuffer[i] = y
            // Кубика достаточно: контрольные точки на середине шага дают гладкую
            // кривую без отдельного сглаживателя и без лишних выделений.
            val midX = (prevX + x) * 0.5f
            linePath.cubicTo(midX, prevY, midX, y, x, y)
            fillPath.cubicTo(midX, prevY, midX, y, x, y)
            prevX = x
            prevY = y
        }
        fillPath.lineTo(prevX, h)
        fillPath.close()

        // Сдвиг считается в шагах, а не в пикселях, поэтому смена ширины его не
        // трогает: шаг пересчитается сам в [stepPx].
        fadeAnchorY = medianY(count).coerceIn(0f, h)
    }

    /**
     * Медиана высот кривой — начало затухания заливки.
     *
     * Не минимум: один всплеск до 400 мс поднял бы начало рампы над всей
     * остальной линией, и тень под ней снова стала бы невидимой — ровно то, на
     * что жаловался владелец. У медианы это свойство есть: она стоит на типичном
     * уровне линии, а всё, что выше, попадает в зажатую часть рампы и заливается
     * цветом целиком.
     */
    private fun medianY(size: Int): Float {
        if (size <= 0) return height.toFloat()
        // Сортировка идёт по копии: `sortedYBuffer` заполняется заново на каждой
        // пересборке пути, порядок точек в нём после этого не нужен никому.
        java.util.Arrays.sort(sortedYBuffer, 0, size)
        return sortedYBuffer[size / 2]
    }

    /**
     * Первая точка новейшего замера стоит на правом краю; старые уходят влево.
     *
     * Шаг считается не по всей ёмкости, а по [VISIBLE_SPAN]: два самых старых
     * замера намеренно оказываются за левым краем, чтобы граница вида резала
     * кривую по живым данным, а не по её концу.
     */
    private fun xAt(index: Int): Float = width.toFloat() - (count - 1 - index) * stepPx()

    /**
     * Где начинается путь — заведомо за левой границей вида.
     *
     * Шкала не должна обрываться внутри вида: обрыв читается как срез, и заметен
     * он ровно в момент прихода замера, когда пересобирается путь. Поэтому старт
     * всегда левее нуля, с запасом на сдвиг холста в **любую** сторону: пачка
     * внеочередных замеров уводит [shift] до [SHIFT_MIN], то есть рисует
     * содержимое правее на полтора шага, — отсюда и [LEAD_IN_STEPS].
     *
     * До старейшего замера путь идёт горизонталью. На полном буфере она целиком
     * за краем; пока буфер набирается — показывает ровно то, что есть: истории
     * левее нет, но линия при этом входит в вид, а не начинается стенкой.
     */
    private fun leadInX(): Float = minOf(xAt(0), -LEAD_IN_STEPS * stepPx())

    private fun stepPx(): Float = width.toFloat() / VISIBLE_SPAN

    private fun strokeWidthPx(): Float = resources.displayMetrics.density * 2f

    private fun rebuildShaders() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || count == 0) {
            colorShader = null
            linePaint.shader = null
            glowPaint.shader = null
            fillPaint.shader = null
            linePaint.color = COLOR_IDLE
            glowPaint.color = COLOR_IDLE
            // Заливке прозрачность задаётся цветом: шейдера у неё сейчас нет, а
            // альфу кисти перед отрисовкой заливки никто не ставит — сплошной
            // цвет закрасил бы весь прямоугольник под линией.
            fillPaint.color = (COLOR_IDLE and 0x00FFFFFF) or (FADE_PEAK_ALPHA shl 24)
            return
        }

        // Единственный замер — тот же градиент из одного цвета, а не отдельная
        // ветка со сплошной кистью: иначе его заливка легла бы под линию ровным
        // непрозрачным прямоугольником, без вертикального затухания.
        val gradient = if (count == 1) {
            LinearGradient(
                leadInX(), 0f, xAt(0), 0f,
                colors[0], colors[0],
                Shader.TileMode.CLAMP,
            )
        } else {
            LinearGradient(
                xAt(0), 0f, xAt(count - 1), 0f,
                IntArray(count) { smoothedColorAt(it) },
                FloatArray(count) { it / (count - 1).toFloat() },
                Shader.TileMode.CLAMP,
            )
        }
        colorShader = gradient
        linePaint.shader = gradient
        glowPaint.shader = gradient

        val ramp = fadeRamp ?: buildFadeRamp().also { fadeRamp = it }
        val span = (h - fadeAnchorY).coerceAtLeast(1f)
        fadeMatrix.setScale(1f, span / ramp.height.toFloat())
        fadeMatrix.postTranslate(0f, fadeAnchorY)
        val fade = BitmapShader(ramp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        fade.setLocalMatrix(fadeMatrix)
        // DST_IN: цвет берётся из градиента, прозрачность — из рампы.
        fillPaint.shader = ComposeShader(gradient, fade, PorterDuff.Mode.DST_IN)
    }

    /**
     * Цвет замера, размытый по соседям биномиальным ядром `1 4 6 4 1`.
     *
     * Внутри однородного участка сумма весов даёт исходный цвет без искажения —
     * жёлтый отрезок остаётся жёлтым. Размывается только граница, и ровно на
     * столько, чтобы переход читался плавным, а не ступенькой в один замер.
     */
    private fun smoothedColorAt(index: Int): Int {
        var a = 0f
        var r = 0f
        var g = 0f
        var b = 0f
        var weightSum = 0f
        for (offset in -2..2) {
            val i = (index + offset).coerceIn(0, count - 1)
            val weight = KERNEL[offset + 2]
            val color = colors[i]
            a += ((color ushr 24) and 0xFF) * weight
            r += ((color ushr 16) and 0xFF) * weight
            g += ((color ushr 8) and 0xFF) * weight
            b += (color and 0xFF) * weight
            weightSum += weight
        }
        if (weightSum <= 0f) return colors[index]
        return Color.argb(
            (a / weightSum).toInt().coerceIn(0, 255),
            (r / weightSum).toInt().coerceIn(0, 255),
            (g / weightSum).toInt().coerceIn(0, 255),
            (b / weightSum).toInt().coerceIn(0, 255),
        )
    }

    /**
     * Столбец прозрачности: непрозрачно у линии, ничего у низа.
     *
     * Растр, а не второй `LinearGradient`, — потому что до API 28 аппаратный
     * конвейер отказывается складывать два шейдера одного типа, а градиент с
     * растром складывает.
     */
    private fun buildFadeRamp(): Bitmap {
        val pixels = IntArray(FADE_RAMP_HEIGHT)
        for (i in pixels.indices) {
            val t = i / (FADE_RAMP_HEIGHT - 1).toFloat()
            val alpha = (FADE_PEAK_ALPHA * Math.pow((1f - t).toDouble(), FADE_EXPONENT)).toInt()
            pixels[i] = (alpha.coerceIn(0, 255) shl 24) or 0x00FFFFFF
        }
        val bitmap = Bitmap.createBitmap(1, FADE_RAMP_HEIGHT, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, 1, 0, 0, 1, FADE_RAMP_HEIGHT)
        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (count == 0) return
        val w = width.toFloat()
        if (w <= 0f) return

        val stroke = strokeWidthPx()
        canvas.save()
        // Обрезка по своим границам — не перестраховка.
        //
        // Корневая разметка главного экрана стоит с `clipChildren="false"` (это
        // нужно свечению кнопок), поэтому холст вида по умолчанию **не** обрезан
        // ничем. Путь нарочно начинается за левым краем, чтобы линия входила в
        // вид, а не начиналась в нём, — и без этой строки вход рисовался левее
        // тёмной подложки, прямо по фону экрана.
        canvas.clipRect(0f, 0f, w, height.toFloat())
        canvas.translate(-shift * stepPx(), 0f)

        canvas.drawPath(fillPath, fillPaint)

        // Сияние — две широкие полупрозрачные обводки под основной линией. Тот же
        // градиент, что и у линии: сияние обязано быть цвета своего участка.
        glowPaint.strokeWidth = stroke * 5f
        glowPaint.alpha = 34
        canvas.drawPath(linePath, glowPaint)
        glowPaint.strokeWidth = stroke * 2.4f
        glowPaint.alpha = 72
        canvas.drawPath(linePath, glowPaint)

        linePaint.strokeWidth = stroke
        linePaint.alpha = 235
        canvas.drawPath(linePath, linePaint)

        canvas.restore()
    }

    override fun onSaveInstanceState(): Parcelable {
        val superState = super.onSaveInstanceState()
        val savedState = SavedState(superState)
        savedState.latencyValues = values.copyOf(count)
        return savedState
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is SavedState) {
            super.onRestoreInstanceState(state.superState)
            count = 0
            state.latencyValues.forEach { value ->
                if (count < capacity) {
                    values[count] = value
                    colors[count] = colorForLatency(value)
                    count++
                }
            }
            shift = 0f
            rebuild()
            invalidate()
        } else {
            super.onRestoreInstanceState(state)
        }
    }

    private class SavedState : BaseSavedState {
        var latencyValues: IntArray = intArrayOf()

        constructor(superState: Parcelable?) : super(superState)

        constructor(source: android.os.Parcel) : super(source) {
            latencyValues = source.createIntArray() ?: intArrayOf()
        }

        override fun writeToParcel(out: android.os.Parcel, flags: Int) {
            super.writeToParcel(out, flags)
            out.writeIntArray(latencyValues)
        }

        companion object {
            @JvmField
            val CREATOR = object : Parcelable.Creator<SavedState> {
                override fun createFromParcel(source: android.os.Parcel) = SavedState(source)
                override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
            }
        }
    }

    companion object {

        /**
         * Кадр не чаще, чем раз в 33 мс.
         *
         * Тридцать кадров в секунду для линии, ползущей на один шаг за две
         * секунды, визуально неотличимы от шестидесяти, а стоят вдвое дешевле.
         */
        private const val FRAME_INTERVAL_MS = 33L

        /**
         * Больше этого срока один кадр не двигает поток.
         *
         * Экран мог быть погашен, вид — скрыт вкладкой. Без ограничения первый же
         * кадр после паузы перемотал бы линию на весь простой разом.
         */
        private const val MAX_FRAME_STEP_MS = 100L

        /** Сколько шагов помещается по ширине. Ёмкость больше — хвост уходит за левый край. */
        private const val VISIBLE_SPAN = 45f

        /** Границы, в которых доверяем измеренному интервалу между замерами. */
        private const val MIN_INTERVAL_MS = 700f
        private const val MAX_INTERVAL_MS = 6_000f

        /** Доля нового замера в скользящем среднем интервала. */
        private const val INTERVAL_EMA = 0.3f

        /** Куда регулятор тянет сдвиг: полшага запаса на дрожание интервала. */
        private const val SHIFT_TARGET = 0.5f

        /** Сила поправки скорости. Больше — быстрее сходится и заметнее «дышит». */
        private const val SHIFT_GAIN = 0.9f

        /** Дальше этого не уезжаем: справа появилась бы растущая пустота. */
        private const val SHIFT_MAX = 1.25f

        /** Ближе этого к границе поток тормозит, а не упирается. */
        private const val SHIFT_EASE = 0.35f

        /**
         * Предел отставания после пачки внеочередных замеров.
         *
         * `onResume` и отрисовка подключённого состояния зовут замер вне тика, и
         * два замера подряд могут прийти за доли секунды. Регулятор нагонит это
         * сам, а граница держит новейшую точку в разумной близости к краю.
         */
        private const val SHIFT_MIN = -1.5f

        /** Высота столбца прозрачности в пикселях его собственных координат. */
        private const val FADE_RAMP_HEIGHT = 64

        /** Насколько густа заливка у самой линии. */
        private const val FADE_PEAK_ALPHA = 166

        /** Показатель затухания. Больше единицы — гаснет быстрее у линии, мягче внизу. */
        private const val FADE_EXPONENT = 1.7

        /**
         * На сколько шагов путь начинается левее границы вида.
         *
         * Больше, чем модуль [SHIFT_MIN]: даже когда сдвиг уводит содержимое
         * вправо на полтора шага, начало пути остаётся за краем, и линия входит
         * в вид, а не начинается в нём.
         */
        private const val LEAD_IN_STEPS = 2.5f

        /** Биномиальное ядро сглаживания цвета. */
        private val KERNEL = floatArrayOf(1f, 4f, 6f, 4f, 1f)

        /**
         * Приглушённая палитра с неоновым оттенком.
         *
         * Прежние цвета (`#50C878`, `#FFB347`, `#FF6B6B`) на чёрном фоне
         * выжигали глаз; здесь та же семантика, но светимость даёт сияние под
         * линией, а не сам цвет.
         */
        private val COLOR_GOOD = Color.parseColor("#3FD9A0")
        private val COLOR_FAIR = Color.parseColor("#D9A55C")
        private val COLOR_POOR = Color.parseColor("#D96A78")
        private val COLOR_IDLE = Color.parseColor("#5A6B7A")

        fun colorForLatency(ms: Int): Int {
            return when {
                ms < 0 -> COLOR_IDLE
                ms < 100 -> COLOR_GOOD
                ms < 400 -> COLOR_FAIR
                else -> COLOR_POOR
            }
        }
    }
}

package com.example.nova

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.RadioGroup

/**
 * `RadioGroup`, который переносит не поместившиеся кнопки на следующую строку.
 *
 * Понадобился, когда в «Выборе протокола/региона» стало шесть вариантов: обычный
 * `RadioGroup` — это `LinearLayout`, переносить он не умеет, и последняя кнопка
 * обрезалась. Прокрутка вместо переноса тоже не годится — скрытая за краем кнопка
 * читается как отсутствующая.
 *
 * Наследование именно от `RadioGroup`, а не сборка ряда на [FlowLayout], выбрано
 * ради вызывающего кода: экран пользуется `check()` и `setOnCheckedChangeListener`,
 * и логика единственного выбора остаётся его, а не наша.
 */
class FlowRadioGroup @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : RadioGroup(context, attrs) {

    /**
     * Явный план строк: сколько **видимых** кнопок класть в каждую.
     *
     * Пусто — перенос как раньше, только по ширине. Непустой план нужен там, где
     * строки несут смысл: на главном экране это «всё через Cloudflare» /
     * «зарубежные VPN» / «Tor», и разбиение по ширине рассыпало бы группы на
     * узком экране.
     *
     * При заданном плане перенос по ширине **выключен полностью**: строк ровно
     * столько, сколько назвал план, а не поместившееся уезжает вбок — обе группы
     * лежат в `HorizontalScrollView` именно поэтому. Запасного переноса по ширине
     * тут нет, и это намеренно: он вернул бы четвёртую полосу на узком экране.
     */
    var rowPlan: List<Int> = emptyList()
        set(value) {
            // Сравнение обязательно: план переставляется из перерисовки селектора,
            // а она приходит несколько раз в секунду. Безусловный `requestLayout`
            // отсюда гонял бы полный обход measure/layout всего окна на каждом
            // тике при неизменившемся селекторе.
            if (field == value) return
            field = value
            requestLayout()
        }

    /**
     * Прижимать строки к левому краю вместо центрирования.
     *
     * Так попросил владелец: у центрированных полос 3/2/1 левый край
     * «лесенкой», и сетки в них не читается. При выравнивании по левому краю все
     * строки начинаются на одной вертикали.
     *
     * Умолчание оставлено прежним (по центру) намеренно: тот же вид собирает
     * селектор в настройках, где строки набираются по ширине, а не по плану, и
     * менять его вид владелец не просил.
     */
    var alignRowsToStart: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /**
     * Одна ширина на все кнопки — ширина самой широкой из них.
     *
     * Без этого колонки соседних строк не стоят друг под другом: «AUTO» узкая,
     * «MASQUE» широкая, и три полосы выглядят рваными. С одной шириной 3/2/1
     * читаются сеткой.
     */
    var uniformItemWidth: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /**
     * Цвет неоновой подсветки выбранной кнопки. 0 — подсветки нет.
     *
     * Умолчание «нет» намеренно: тот же вид собирает селектор в настройках
     * (`activity_settings.xml`), где кнопки — обычные радиокнопки с кружком, а не
     * овальные бейджи, и ореол вокруг них смысла не имеет.
     */
    var selectionGlowColor: Int = 0
        set(value) {
            if (field == value) return
            field = value
            haloBitmap = null
            invalidate()
        }

    private var haloBitmap: Bitmap? = null
    private var haloW = 0
    private var haloH = 0
    private var haloColor = 0
    private val haloPad = 6f * resources.displayMetrics.density
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    /** Своя кисть для растра: через неё ореол гаснет вместе с кнопкой. */
    private val haloPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /**
     * Отметка двигается — надо перерисовать **группу**, а не кнопку.
     *
     * Ореол рисует группа, и в аппаратном конвейере список отрисовки родителя не
     * пересобирается только оттого, что ребёнок себя пометил грязным: сияние
     * осталось бы под прежней кнопкой. `RadioGroup` держит свой контроль
     * исключительности на скрытом слоте `setOnCheckedChangeWidgetListener`,
     * поэтому публичный слушатель кнопки свободен и взаимное исключение не
     * ломает. `check()` перекрыт заодно — программная отметка идёт мимо касания.
     */
    override fun onViewAdded(child: View) {
        super.onViewAdded(child)
        (child as? CompoundButton)?.setOnCheckedChangeListener { _, _ ->
            if (selectionGlowColor != 0) invalidate()
        }
    }

    override fun check(id: Int) {
        super.check(id)
        if (selectionGlowColor != 0) invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        val checked = if (selectionGlowColor != 0) {
            findViewById<View>(checkedRadioButtonId)?.takeIf { it.width > 0 && it.height > 0 }
        } else null

        // Кнопка гаснет до 0.45 (`ConnectionSelectorPolicy.DISABLED_ALPHA`), когда
        // выбор заперт — идёт регистрация устройства или Opera не поддержана.
        // `View.alpha` действует только на саму кнопку, а свет и обводку рисует
        // группа: без этого множителя погашенный чип светился бы в полную силу и
        // читался как доступный.
        val fade = checked?.alpha ?: 1f

        // Ореол — под кнопками: подложка самой кнопки полупрозрачная, и свет
        // должен лежать на фоне, а не поверх подписи.
        if (checked != null) {
            haloFor(checked.width, checked.height)?.let {
                haloPaint.alpha = (255 * fade).toInt().coerceIn(0, 255)
                canvas.drawBitmap(it, checked.left - haloPad, checked.top - haloPad, haloPaint)
            }
        }
        super.dispatchDraw(canvas)
        // Ядро — поверх: 1dp обводка тем же цветом. Радиус берётся от **высоты
        // кнопки**, а не константой 18dp: на тесном экране кнопка сжимается до
        // 22dp (`applyRegionChipHeights`), и фиксированный радиус перестал бы
        // совпадать с формой подложки.
        if (checked != null) {
            val w = 1f * resources.displayMetrics.density
            // Порядок обязателен: `color` перезаписывает и альфа-канал, поэтому
            // альфа ставится после цвета. Восстанавливать её не надо — цвет
            // присваивается заново на каждом кадре.
            ringPaint.color = selectionGlowColor
            ringPaint.alpha = (Color.alpha(selectionGlowColor) * fade).toInt().coerceIn(0, 255)
            ringPaint.strokeWidth = w
            val r = checked.height / 2f
            canvas.drawRoundRect(
                checked.left + w / 2f, checked.top + w / 2f,
                checked.right - w / 2f, checked.bottom - w / 2f,
                r, r, ringPaint,
            )
        }
    }

    /**
     * Ореол считается один раз на размер и цвет, а не на кадр.
     *
     * `BlurMaskFilter` не работает на аппаратном холсте, но ему и не нужен живой:
     * растр рисуется программным `Canvas`, а на экран уходит обычным
     * `drawBitmap`. Так эффект есть, а весь вид в программный слой не падает
     * (`LatencyGraphView` отказался от размытия именно из-за этого).
     * Все кнопки группы одной ширины (`uniformItemWidth`), поэтому растр один.
     */
    private fun haloFor(w: Int, h: Int): Bitmap? {
        haloBitmap?.let { if (w == haloW && h == haloH && selectionGlowColor == haloColor) return it }
        if (w <= 0 || h <= 0) return null
        val bmp = Bitmap.createBitmap(
            w + (2 * haloPad).toInt(), h + (2 * haloPad).toInt(), Bitmap.Config.ARGB_8888,
        )
        val c = Canvas(bmp)
        val core = RectF(haloPad, haloPad, haloPad + w, haloPad + h)
        val radius = h / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            isDither = true
            style = Paint.Style.FILL
        }
        val red = Color.red(selectionGlowColor)
        val green = Color.green(selectionGlowColor)
        val blue = Color.blue(selectionGlowColor)
        // Лестница как в `StrokeTextView.drawLineHalo`: форма у всех слоёв одна,
        // меняется только радиус размытия. Расширять вместе с радиусом нельзя —
        // у каждого слоя свой силуэт, и на дальнем крае проступают кольца.
        //
        // Размытие именно `OUTER`, а не `NORMAL`. `NORMAL` красит и внутренность
        // силуэта, а слоёв двенадцать, и их прозрачности складываются: кнопка
        // заливалась цветом акцента целиком и читалась как нажатая, а не как
        // подсвеченная. `OUTER` рисует **только снаружи** контура — внутри
        // остаётся подложка кнопки, снаружи свет. Это и есть неоновая вывеска:
        // яркая линия и мягкое зарево вокруг неё.
        for (layer in HALO_LAYERS) {
            val alpha = (HALO_BASE_ALPHA * layer[1]).toInt().coerceIn(0, 255)
            if (alpha <= 0) continue
            paint.color = Color.argb(alpha, red, green, blue)
            paint.maskFilter = BlurMaskFilter((haloPad * layer[0]).coerceAtLeast(1f), BlurMaskFilter.Blur.OUTER)
            c.drawRoundRect(core, radius, radius, paint)
        }
        haloBitmap = bmp
        haloW = w
        haloH = h
        haloColor = selectionGlowColor
        return bmp
    }

    /** Индекс, после которого план требует перенос. Пусто — плана нет. */
    private fun planBreaks(): Set<Int> {
        if (rowPlan.isEmpty()) return emptySet()
        val breaks = mutableSetOf<Int>()
        var taken = 0
        for (count in rowPlan) {
            taken += count
            breaks.add(taken)
        }
        return breaks
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val breaks = planBreaks()
        // Общая ширина считается **до** набора строк: узнать её иначе нельзя, а
        // от неё зависит, где кончается каждая строка. Ноль — режим выключен, и
        // тогда всё идёт ровно как раньше, вплоть до третьего аргумента
        // `measureChildWithMargins`.
        val uniformWidth = if (uniformItemWidth) widestChildWidth(widthMeasureSpec, heightMeasureSpec, available) else 0
        var rowWidth = 0
        var rowHeight = 0
        var totalHeight = 0
        var maxRowWidth = 0
        var visibleIndex = 0

        forEachVisibleChild { child, params ->
            if (uniformWidth > 0) {
                child.measure(
                    MeasureSpec.makeMeasureSpec(uniformWidth, MeasureSpec.EXACTLY),
                    getChildMeasureSpec(
                        heightMeasureSpec,
                        paddingTop + paddingBottom + params.topMargin + params.bottomMargin + totalHeight,
                        params.height,
                    ),
                )
            } else {
                measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, totalHeight)
            }
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            val childHeight = child.measuredHeight + params.topMargin + params.bottomMargin
            val planned = visibleIndex in breaks
            visibleIndex++
            // При заданном плане перенос по ширине **выключен**: строк ровно
            // столько, сколько назвал план, а не поместившееся уезжает вбок и
            // прокручивается пальцем. Иначе узкий экран сам добавлял бы четвёртую
            // строку, и «ровно три полосы» переставало быть правдой.
            if (rowWidth > 0 && (planned || (breaks.isEmpty() && rowWidth + childWidth > available))) {
                maxRowWidth = maxOf(maxRowWidth, rowWidth)
                totalHeight += rowHeight
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += childWidth
            rowHeight = maxOf(rowHeight, childHeight)
        }
        maxRowWidth = maxOf(maxRowWidth, rowWidth)
        totalHeight += rowHeight

        setMeasuredDimension(
            resolveSize(maxRowWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(totalHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    /**
     * Ширина самой широкой кнопки при её собственных размерах.
     *
     * Ограничение по доступной ширине держится только когда она вообще известна:
     * `HorizontalScrollView` меряет содержимое спецификацией `UNSPECIFIED`,
     * то есть нулевым размером, и принимать этот ноль за реальную ширину значило
     * бы схлопнуть все кнопки.
     */
    private fun widestChildWidth(widthMeasureSpec: Int, heightMeasureSpec: Int, available: Int): Int {
        var widest = 0
        forEachVisibleChild { child, _ ->
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0)
            widest = maxOf(widest, child.measuredWidth)
        }
        return if (available > 0) widest.coerceAtMost(available) else widest
    }

    /**
     * Раскладка идёт **по строкам целиком**, а не по одной кнопке.
     *
     * Иначе строку нельзя отцентрировать: чтобы узнать её отступ слева, надо
     * знать её полную ширину, а она известна только когда строка набрана.
     * Выравнивание по левому краю ([alignRowsToStart]) полной ширины строки не
     * требует, но собирать строку всё равно надо — обе раскладки живут в одном
     * проходе.
     */
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = r - l - paddingLeft - paddingRight
        val breaks = planBreaks()
        val row = mutableListOf<View>()
        var rowWidth = 0
        var rowHeight = 0
        var y = paddingTop
        var visibleIndex = 0

        fun flushRow() {
            if (row.isEmpty()) return
            var x = if (alignRowsToStart) paddingLeft else paddingLeft + maxOf(0, (available - rowWidth) / 2)
            for (child in row) {
                val params = child.layoutParams as ViewGroup.MarginLayoutParams
                child.layout(
                    x + params.leftMargin,
                    y + params.topMargin,
                    x + params.leftMargin + child.measuredWidth,
                    y + params.topMargin + child.measuredHeight,
                )
                x += child.measuredWidth + params.leftMargin + params.rightMargin
            }
            y += rowHeight
            row.clear()
            rowWidth = 0
            rowHeight = 0
        }

        forEachVisibleChild { child, params ->
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            val childHeight = child.measuredHeight + params.topMargin + params.bottomMargin
            val planned = visibleIndex in breaks
            visibleIndex++
            if (row.isNotEmpty() &&
                (planned || (breaks.isEmpty() && rowWidth + childWidth > available))
            ) {
                flushRow()
            }
            row.add(child)
            rowWidth += childWidth
            rowHeight = maxOf(rowHeight, childHeight)
        }
        flushRow()
    }

    private inline fun forEachVisibleChild(action: (View, ViewGroup.MarginLayoutParams) -> Unit) {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            action(child, child.layoutParams as ViewGroup.MarginLayoutParams)
        }
    }

    private companion object {
        /** Пары «доля радиуса — доля непрозрачности». Взяты у `StrokeTextView`. */
        private val HALO_LAYERS = arrayOf(
            floatArrayOf(1.00f, 0.14f), floatArrayOf(0.84f, 0.18f),
            floatArrayOf(0.70f, 0.23f), floatArrayOf(0.58f, 0.29f),
            floatArrayOf(0.47f, 0.36f), floatArrayOf(0.37f, 0.44f),
            floatArrayOf(0.29f, 0.53f), floatArrayOf(0.22f, 0.63f),
            floatArrayOf(0.16f, 0.73f), floatArrayOf(0.11f, 0.83f),
            floatArrayOf(0.07f, 0.92f), floatArrayOf(0.04f, 1.00f),
        )

        /** «Мягко, а не резко»: у кнопки 36dp сильнее выглядит тревогой. */
        private const val HALO_BASE_ALPHA = 96
    }
}

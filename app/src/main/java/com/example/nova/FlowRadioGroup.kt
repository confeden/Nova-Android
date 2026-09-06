package com.example.nova

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
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
}

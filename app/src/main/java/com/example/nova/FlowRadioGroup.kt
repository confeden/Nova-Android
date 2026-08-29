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

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var rowWidth = 0
        var rowHeight = 0
        var totalHeight = 0
        var maxRowWidth = 0

        forEachVisibleChild { child, params ->
            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, totalHeight)
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            val childHeight = child.measuredHeight + params.topMargin + params.bottomMargin
            if (rowWidth > 0 && rowWidth + childWidth > available) {
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

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = r - l - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0

        forEachVisibleChild { child, params ->
            val childWidth = child.measuredWidth + params.leftMargin + params.rightMargin
            val childHeight = child.measuredHeight + params.topMargin + params.bottomMargin
            if (x > paddingLeft && x + childWidth > paddingLeft + available) {
                x = paddingLeft
                y += rowHeight
                rowHeight = 0
            }
            child.layout(
                x + params.leftMargin,
                y + params.topMargin,
                x + params.leftMargin + child.measuredWidth,
                y + params.topMargin + child.measuredHeight,
            )
            x += childWidth
            rowHeight = maxOf(rowHeight, childHeight)
        }
    }

    private inline fun forEachVisibleChild(action: (View, ViewGroup.MarginLayoutParams) -> Unit) {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            action(child, child.layoutParams as ViewGroup.MarginLayoutParams)
        }
    }
}

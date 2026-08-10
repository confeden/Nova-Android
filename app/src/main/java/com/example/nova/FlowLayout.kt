package com.example.nova

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup

/**
 * Ряд, который переносит не поместившиеся элементы на следующую строку.
 *
 * Понадобился для кнопок карточки конфигурации: четыре стрелки, «Удалить» и
 * «Копировать» в сумме требуют около 412dp, а ширина экрана — 411dp, и последняя
 * кнопка обрезалась. Обычный горизонтальный `LinearLayout` переносить не умеет,
 * а раскладывать в две строки всегда — терять место там, где всё помещается.
 *
 * Учитываются отступы `layout_margin*`: без них кнопки слипались бы, у них весь
 * зазор задан именно маргинами.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : ViewGroup(context, attrs, defStyleAttr) {

    override fun generateDefaultLayoutParams(): LayoutParams =
        MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)

    override fun generateLayoutParams(attrs: AttributeSet?): LayoutParams =
        MarginLayoutParams(context, attrs)

    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)

    override fun checkLayoutParams(p: LayoutParams?): Boolean = p is MarginLayoutParams

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

    private inline fun forEachVisibleChild(action: (View, MarginLayoutParams) -> Unit) {
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == View.GONE) continue
            action(child, child.layoutParams as MarginLayoutParams)
        }
    }
}

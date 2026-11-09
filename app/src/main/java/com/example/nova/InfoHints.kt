package com.example.nova

import android.app.AlertDialog
import android.content.res.ColorStateList
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.core.view.ViewCompat

/**
 * Длинные пояснения под опциями убираются за маленькую иконку «i» рядом с подписью:
 * нажатие открывает пояснение в диалоге. Сам вид с текстом остаётся в дереве (GONE) —
 * его русский исходник нужен для перевода, а id — коду, который текст меняет.
 */
private const val ICON_DP = 20
private const val ICON_PAD_DP = 8

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

private fun View.iconTint(): ColorStateList? {
    val tv = TypedValue()
    return if (context.theme.resolveAttribute(R.attr.novaIconTint, tv, true) && tv.type in
        TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT
    ) ColorStateList.valueOf(tv.data) else null
}

private fun showHintDialog(hint: TextView, label: TextView) {
    val ctx = label.context
    val title = NovaLanguage.tr(ctx, (NovaLanguage.sourceText(label) ?: label.text).toString())
    val message = NovaLanguage.tr(ctx, (NovaLanguage.sourceText(hint) ?: hint.text).toString())
    AlertDialog.Builder(ctx)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(NovaLanguage.tr(ctx, "Понятно"), null)
        .showNova()
}

/**
 * Прячет это пояснение и ставит иконку «i» рядом с подписью [label] (по умолчанию —
 * предыдущий вид в том же контейнере). Если подпись не найдена или раскладка
 * незнакомая, пояснение остаётся на месте.
 */
fun TextView.collapseToInfoIcon(label: TextView? = null) {
    val hint = this
    val parent = hint.parent as? LinearLayout ?: return
    val anchor = label ?: parent.getChildAt(parent.indexOfChild(hint) - 1) as? TextView ?: return
    val desc = NovaLanguage.tr(hint.context, "Подсказка")
    val size = hint.dp(ICON_DP)
    val tint = hint.iconTint()
    val bottom = (hint.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0

    if (parent is RadioGroup) {
        // Радиокнопку нельзя заворачивать в контейнер — группа перестанет её видеть;
        // иконка рисуется концевым значком самой кнопки, нажатие ловится по зоне.
        val d = hint.context.getDrawable(R.drawable.ic_nova_info)?.mutate() ?: return
        d.setBounds(0, 0, size, size)
        if (tint != null) d.setTintList(tint)
        anchor.compoundDrawablePadding = hint.dp(ICON_PAD_DP)
        val c = anchor.compoundDrawablesRelative
        anchor.setCompoundDrawablesRelative(c[0], c[1], d, c[3])
        (anchor.layoutParams as? ViewGroup.MarginLayoutParams)?.let { it.bottomMargin += bottom }
        val zone = size + hint.dp(ICON_PAD_DP * 2)
        @Suppress("ClickableViewAccessibility")
        anchor.setOnTouchListener { v, e ->
            val rtl = v.layoutDirection == View.LAYOUT_DIRECTION_RTL
            val inZone = if (rtl) e.x <= v.paddingLeft + zone else e.x >= v.width - v.paddingRight - zone
            if (!inZone) return@setOnTouchListener false
            if (e.action == MotionEvent.ACTION_UP) showHintDialog(hint, anchor)
            true
        }
        ViewCompat.addAccessibilityAction(anchor, desc) { _, _ -> showHintDialog(hint, anchor); true }
        hint.visibility = View.GONE
        return
    }

    if (parent.orientation != LinearLayout.VERTICAL || anchor.parent !== parent) return
    val index = parent.indexOfChild(anchor)
    val lp = anchor.layoutParams as? LinearLayout.LayoutParams ?: return
    val fill = lp.width == ViewGroup.LayoutParams.MATCH_PARENT

    val icon = ImageView(hint.context).apply {
        setImageResource(R.drawable.ic_nova_info)
        if (tint != null) imageTintList = tint
        val pad = hint.dp(ICON_PAD_DP)
        setPadding(pad, pad, pad, pad)
        contentDescription = desc
        scaleType = ImageView.ScaleType.FIT_CENTER
        setOnClickListener { showHintDialog(hint, anchor) }
        val outValue = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)) {
            setBackgroundResource(outValue.resourceId)
        }
    }
    val row = LinearLayout(hint.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    parent.removeViewAt(index)
    // Поля и вес подписи переходят к строке, внутри подпись только занимает место.
    val rowParams = LinearLayout.LayoutParams(lp).also { it.bottomMargin += bottom }
    parent.addView(row, index, rowParams)
    row.addView(
        anchor,
        if (fill) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    val box = size + hint.dp(ICON_PAD_DP * 2)
    row.addView(icon, LinearLayout.LayoutParams(box, box).also { it.marginStart = hint.dp(2) })
    // Иконка 36 dp не должна раздувать компактные строки: полям -8 dp сверху и снизу.
    (icon.layoutParams as LinearLayout.LayoutParams).apply { topMargin = -hint.dp(8); bottomMargin = -hint.dp(8) }
    hint.visibility = View.GONE
}

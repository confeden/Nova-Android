package com.example.nova

import android.app.Dialog
import android.view.ViewGroup
import android.widget.Button

/**
 * Кнопки диалогов — по теме приложения.
 *
 * Зачем это код, а не стиль. Тема диалога у нас есть (`Theme.Nova.Dialog.*`,
 * G159): фон, заголовок и текст она красит. Кнопки — нет. На Pixel с Android 14
 * платформенный стиль кнопки диалога приходит из системного оверлея и красит фон
 * динамическим акцентом — цветом обоев; на мятной теме кнопка «Закрыть»
 * получалась сиреневой. Перебить это темой не вышло: `android:background` и
 * `android:backgroundTint` в стиле кнопки оверлей игнорирует, а `@null` в
 * атрибуте оттенка не снимает унаследованный вовсе — `View` читает его через
 * `hasValue`, для которого `@null` значит «не задано». Размеры и цвет текста
 * при этом доезжают, то есть стиль применяется частично, и полагаться на него
 * нельзя.
 *
 * Поэтому кнопки красятся после показа: на этом шаге вид уже создан, и
 * `setBackgroundResource` с `backgroundTintList = null` — последнее слово.
 *
 * Ресурсы берутся из темы **диалога**, а не экрана: у окна диалога свой
 * `ContextThemeWrapper`, и `novaAccent` экрана там не виден.
 *
 * Кнопка была почти не видна не только на Pixel: безрамочная кнопка на тёмном
 * фоне читается как строка текста. Отсюда подложка с обводкой у всех тем, а не
 * только там, где мешает оверлей.
 */
object NovaDialogs {

    /**
     * Красит кнопки уже показанного диалога.
     *
     * Годится и платформенному `android.app.AlertDialog`, и AppCompat: у обоих
     * кнопки лежат под теми же идентификаторами фреймворка.
     */
    fun style(dialog: Dialog) {
        apply(dialog.findViewById(android.R.id.button1), primary = true)
        apply(dialog.findViewById(android.R.id.button2), primary = false)
        apply(dialog.findViewById(android.R.id.button3), primary = false)
    }

    private fun apply(button: Button?, primary: Boolean) {

        val view = button ?: return

        // Кнопки, которой не задали текст, в разметке нет только формально: вид
        // существует и скрыт. Красить его незачем, а вот считать его отсутствие
        // ошибкой — тем более.
        if (view.visibility != android.view.View.VISIBLE) return

        val context = view.context

        val background = if (primary) R.attr.novaDialogButtonPrimary else R.attr.novaDialogButton

        val resource = resourceOf(context, background)

        // Тема без словаря — это главный экран: он не переведён на темы
        // оформления и своих `nova*` не объявляет. Красить по несуществующему
        // атрибуту нельзя: `resolveAttribute` вернёт ноль, а ноль как цвет — это
        // прозрачный текст, то есть кнопка исчезнет совсем.
        if (resource == 0) return

        view.setBackgroundResource(resource)

        // Оттенок снимается здесь, а не в теме: именно он и приносил чужой цвет.
        view.backgroundTintList = null

        view.setTextColor(
            NovaTheme.color(context, if (primary) R.attr.novaAccentOn else R.attr.novaAccent)
        )

        // `setBackgroundResource` сбрасывает отступы на те, что у нового фона, а
        // у фигуры их нет вовсе — без этой строки текст упирается в обводку.
        val padH = dp(context, 18f)
        val padV = dp(context, 10f)
        view.setPadding(padH, padV, padH, padV)

        view.minWidth = dp(context, 96f)
        view.minHeight = dp(context, 44f)
        view.isAllCaps = false
        view.setTypeface(view.typeface, android.graphics.Typeface.BOLD)

        // Между кнопками нужен зазор: с подложкой они иначе слипаются в одну
        // полосу, и «Отмена» читается как часть «Сохранить». Зазор даёт только
        // `marginStart`.
        //
        // Вертикального отступа здесь быть не может. `ButtonBarLayout` — это
        // `LinearLayout` с `android:gravity="bottom"`, а он вычитает
        // вертикальные поля дважды: один раз в `layoutHorizontal`
        // (`childTop = childBottom - childHeight - lp.bottomMargin`), второй —
        // в поправке на базовую линию (`maxDescent[INDEX_BOTTOM]` считался с
        // полями, `descent` — без). В высоту полосы поле входит один раз,
        // поэтому кнопка встаёт ровно на `paddingTop - bottomMargin` и при
        // `bottomMargin = paddingTop = 4dp` — на нулевую отметку, выше
        // padding-бокса. `clipToPadding` там по умолчанию включён, и верхние
        // 4dp подложки — обводка и верх скруглений — просто не рисуются.
        // Отбивку снизу даёт `paddingBottom="4dp"` самой полосы.
        (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
            params.marginStart = dp(context, 8f)
            params.bottomMargin = 0
            view.layoutParams = params
        }
    }

    private fun resourceOf(context: android.content.Context, attr: Int): Int {
        val value = android.util.TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) value.resourceId else 0
    }

    private fun dp(context: android.content.Context, value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}

/**
 * Показывает платформенный диалог и красит его кнопки.
 *
 * Замена `.show()` в конце цепочки: обработчик показа ставится нам, а не
 * вызывающему, поэтому свой `setOnShowListener` цепочка потерять не может —
 * тем местам, где он уже есть, эта функция не нужна, они зовут `NovaDialogs.style`
 * сами.
 */
fun android.app.AlertDialog.Builder.showNova(): android.app.AlertDialog {
    val dialog = create()
    dialog.setOnShowListener { NovaDialogs.style(dialog) }
    dialog.show()
    return dialog
}

/** То же для диалогов AppCompat: у них те же идентификаторы кнопок. */
fun androidx.appcompat.app.AlertDialog.Builder.showNova(): androidx.appcompat.app.AlertDialog {
    val dialog = create()
    dialog.setOnShowListener { NovaDialogs.style(dialog) }
    dialog.show()
    return dialog
}

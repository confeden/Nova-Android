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
        growToFitRow(dialog)
    }

    /**
     * Та же покраска для обычной кнопки в разметке экрана.
     *
     * Капкан у них общий с диалогами: на Pixel платформенный стиль кнопки красит
     * фон динамическим акцентом системы — цветом обоев, — и на экране «MTU
     * туннеля» кнопки приезжали сиреневыми поверх синей темы. Перебить это темой
     * нельзя (см. шапку), поэтому цвет ставится кодом, и код обязан быть один.
     *
     * Поля layout здесь не трогаются: отбивка `marginStart` нужна только внутри
     * `ButtonBarLayout`, а у кнопки на экране своя разметка.
     */
    fun styleButton(button: Button?, primary: Boolean) {
        val view = button ?: return
        val context = view.context
        val accent = NovaTheme.color(context, R.attr.novaAccent)
        val accentOn = NovaTheme.color(context, R.attr.novaAccentOn)
        if (accent == 0) return
        val density = context.resources.displayMetrics.density
        val shape = android.graphics.drawable.GradientDrawable().apply {
            this.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = 10f * density
            if (primary) {
                setColor(accent)
            } else {
                setColor(
                    android.graphics.Color.argb(
                        0x2E,
                        android.graphics.Color.red(accent),
                        android.graphics.Color.green(accent),
                        android.graphics.Color.blue(accent),
                    )
                )
                setStroke((1f * density + 0.5f).toInt(), accent)
            }
        }
        view.background = shape
        // Оттенок снимается после подложки: платформенный стиль кнопки на Pixel
        // красит фон динамическим акцентом системы, и без этой строки он ляжет
        // поверх нашей фигуры (та же история, что в шапке файла).
        view.backgroundTintList = null
        view.setTextColor(if (primary) accentOn else accent)
        val padH = (18f * density + 0.5f).toInt()
        val padV = (10f * density + 0.5f).toInt()
        view.setPadding(padH, padV, padH, padV)
        view.minHeight = (44f * density + 0.5f).toInt()
        view.isAllCaps = false
        view.setTypeface(view.typeface, android.graphics.Typeface.BOLD)
    }

    /** Поле по горизонтали, с которого кнопка стартует: заведомо узко, чтобы полоса не сложилась. */
    private const val PAD_MIN_DP = 10f

    /** Поле, до которого кнопку расширяют, если на полосе есть место. */
    private const val PAD_MAX_DP = 18f

    /** Пол ширины: меньше пальца кнопка быть не должна, даже если надпись — «ОК». */
    private const val MIN_WIDTH_DP = 48f

    /**
     * Раздаёт кнопкам свободное место полосы — после того, как её ширина стала известна.
     *
     * Зачем вообще два шага. Ширина кнопки обязана считаться по её надписи: при
     * фиксированных 96dp три кнопки просили 312dp, столько полосе на телефоне 360dp
     * не достаётся, и `ButtonBarLayout` складывал их в столбик — «Сохранить / Отмена /
     * Убрать» у лицензии WARP+ ехали лесенкой (замерено на Mi A1 и Pixel 4a).
     *
     * Почему нельзя просто распрямить сложившуюся полосу. `ButtonBarLayout`
     * возвращается в строку **только при увеличении ширины**: в `onMeasure` условие
     * распрямления — `widthSize > mLastWidthSize`, а ширина диалога не меняется. Что
     * сложилось один раз, то сложилось навсегда, и сузить кнопки задним числом уже
     * бесполезно. Поэтому первый замер обязан пройти по узкому варианту, и только
     * потом место раздаётся обратно.
     *
     * Раздача идёт в `onPreDraw`, то есть после раскладки, но **до** первой отрисовки:
     * возврат `false` отменяет этот кадр, и человек узкого варианта не видит.
     */
    private fun growToFitRow(dialog: Dialog) {

        val buttons = listOfNotNull(
            dialog.findViewById<Button>(android.R.id.button1),
            dialog.findViewById<Button>(android.R.id.button2),
            dialog.findViewById<Button>(android.R.id.button3),
        ).filter { it.visibility == android.view.View.VISIBLE }

        if (buttons.isEmpty()) return

        val bar = buttons.first().parent as? ViewGroup ?: return

        bar.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {

            override fun onPreDraw(): Boolean {

                val available = bar.width - bar.paddingStart - bar.paddingEnd

                // Полосу ещё не измерили: уйти сейчас — значит не раздать место вовсе.
                if (available <= 0) return true

                bar.viewTreeObserver.removeOnPreDrawListener(this)

                // Уже столбик — расширять нечего, шире полоса не станет (см. выше).
                if ((bar as? android.widget.LinearLayout)?.orientation == android.widget.LinearLayout.VERTICAL) {
                    return true
                }

                val context = bar.context

                val padMin = dp(context, PAD_MIN_DP)

                val padMax = dp(context, PAD_MAX_DP)

                // Считается по **измеренной** ширине, а не по тексту: так в счёт входят
                // и начертание, и уже приложенные поля, и пол `minWidth`.
                val used = buttons.sumOf { button ->
                    val params = button.layoutParams as? ViewGroup.MarginLayoutParams
                    button.measuredWidth + (params?.marginStart ?: 0) + (params?.marginEnd ?: 0)
                }

                val slack = available - used

                if (slack <= 0) return true

                // Место делится поровну на все поля: у каждой кнопки их два.
                val pad = (padMin + slack / (2 * buttons.size)).coerceAtMost(padMax)

                if (pad <= padMin) return true

                buttons.forEach { button ->
                    button.setPadding(pad, button.paddingTop, pad, button.paddingBottom)
                }

                // Кадр пропускаем: ширины только что изменились, и рисовать надо уже новые.
                return false

            }

        })

    }

    private fun apply(button: Button?, primary: Boolean, adjustMargins: Boolean = true) {

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
        //
        // Поле по горизонтали здесь **минимальное**, а не окончательное: полоса не
        // должна сложиться в столбик на первом же замере, потому что обратно она уже
        // не распрямится. Настоящее поле раздаёт [growToFitRow], когда ширина полосы
        // известна.
        val padH = dp(context, PAD_MIN_DP)
        val padV = dp(context, 10f)
        view.setPadding(padH, padV, padH, padV)

        // Ширина — по надписи. Фиксированные 96dp на три кнопки не помещались в полосу
        // ни на одном телефоне ýже планшета; остаётся только пол в размер пальца.
        view.minWidth = dp(context, MIN_WIDTH_DP)
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
        if (adjustMargins) {
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.marginStart = dp(context, 8f)
                params.bottomMargin = 0
                view.layoutParams = params
            }
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

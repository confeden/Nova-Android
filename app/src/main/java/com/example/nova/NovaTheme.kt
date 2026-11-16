package com.example.nova

import android.app.Activity
import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.TypedValue
import android.widget.TextView

/**
 * Палитра экранов: 64 темы-оттенка ([NovaAppearance.hue]).
 *
 * Зачем. Палитра была прибита литералами в разметке: четырнадцать цветов текста на
 * десять экранов, четыре серых на одну роль, четыре зелёных на один акцент.
 * «Единая палитра» в таком виде держится только на внимании, а внимание кончается
 * на пятом экране. Теперь разметка не знает ни одного цвета — она знает четыре
 * роли ([R.attr.novaTextTitle] и соседи), а значения им выдаёт тема.
 *
 * Почему тем несколько и почему это дёшево. Тема — это набор цветов плюс три
 * векторных `<shape>` (фон окна, подложка строки, поле ввода). Растра нет ни
 * байта: семь тем целиком весят десятки килобайт исходного XML и куда меньше
 * после сборки. Ресурсы генерируются `tools/gen_nova_themes.py`, чтобы состояния
 * фокуса пульта (`tv_focus_*`) не разъехались между семью копиями — они молча
 * ломаются и обнаруживаются только на телевизоре.
 *
 * Где хранится выбор. В `SharedPreferences` этого процесса: экраны читают тему
 * только здесь. Службе `:vpn` из всей темы нужен один акцент — им красится
 * заливка уведомления, — и его она берёт из файла-зеркала, который ведёт
 * [NovaNotificationPalette] (I2).
 */
object NovaTheme {

    /** Ключ по умолчанию — бирюзовый оттенок ([NovaAppearance.DEFAULT_HUE]). */
    const val DEFAULT_KEY = "hue30"

    /**
     * Начертание всего интерфейса.
     *
     * Шрифты самих игр сюда не везут, и это решение, а не лень: Exocet (Diablo II)
     * и Fontin (Path of Exile) — чужие лицензии, каждый файл это сотни килобайт на
     * APK, а F-Droid пересобирает дерево побайтово (I15). Берутся ближайшие
     * встроенные семейства Android, они есть на любом устройстве и не весят ничего.
     *
     * `DEFAULT` — штатный Roboto. `MONOSPACE` — терминал (PT Mono). `SERIF_SC` —
     * антиквенные капители, похожие на Fontin (Path of Exile 1). `ROMAN` —
     * надписная антиква Forum (Path of Exile 2). `OLD_STYLE` — старая книжная
     * антиква Old Standard TT (Diablo II). `TECHNO` — гротеск Play (Cyber neon).
     * `HEAVY` — самое жирное в проекте: Roboto Black и Rubik Mono One под
     * «надутые» надписи GTA.
     *
     * Все файлы лежат в `res/font` под лицензией SIL OFL 1.1; перечень и
     * источники — в `THIRD-PARTY.md`. У каждого семейства кириллица обязательна:
     * интерфейс русскоязычный.
     */
    enum class Face { DEFAULT, MONOSPACE, SERIF_SC, ROMAN, OLD_STYLE, TECHNO, HEAVY }

    /**
     * @param face начертание всего интерфейса. Это не цвет и поэтому не живёт в
     *        XML-теме: шрифт ставит [NovaFontHelper], обходя дерево видов, а тема
     *        умеет только `textAppearance` у отдельных ролей. Нужно двум темам,
     *        которые изображают не палитру, а вещь: терминалу Matrix и меню
     *        Diablo II. Перечисление, а не пара флагов: «и моноширинный, и с
     *        засечками» — состояние, которого не бывает.
     */
    data class Option(
        val key: String,
        val title: String,
        val styleRes: Int,
        val note: String,
        val face: Face = Face.DEFAULT,
    )

    /**
     * 64 оттеночные темы: ключ `hue<N>`. Готовых тем больше нет — палитру целиком
     * выводит оттенок (`tools/gen_nova_themes.py`). Начертание у всех штатное.
     */
    val ORDER: List<Option> = List(NovaAppearance.HUE_COUNT) { index ->
        Option("hue$index", "hue$index", NovaHueThemes.SETTINGS[index], "")
    }

    fun optionFor(key: String): Option =
        ORDER.firstOrNull { it.key == key } ?: ORDER[NovaAppearance.DEFAULT_HUE]

    fun current(context: Context): String = NovaAppearance.storedThemeKey(context)

    /**
     * Ставит тему экрану. Зовётся **до** `super.onCreate` и `setContentView`:
     * позже окно уже создано со старым фоном, и смена темы доедет только до
     * следующего запуска активности.
     */
    fun apply(activity: Activity) {
        // Язык подключается здесь же: это первая строка каждого экрана, до
        // `super.onCreate`, и тема и язык вместе решают, как экран выглядит.
        NovaLanguage.install(activity)
        NovaNotificationPalette.syncMirrorOnce(activity)
        activity.setTheme(optionFor(current(activity)).styleRes)
        applySurfaceOverlay(activity)
        applyEdgeToEdge(activity)
    }

    /**
     * Кладёт фактуру поверх фона окна.
     *
     * Фон темы остаётся нижним слоем и берётся у самой темы
     * (`android:windowBackground`), а не выписывается здесь. Слоя нет вовсе, пока
     * нечего рисовать: «Без фона» и «Созвездия» стоят экрану настроек ровно столько,
     * сколько он стоил без этого слоя.
     */
    private fun applySurfaceOverlay(activity: Activity) {
        val window = activity.window ?: return
        val texture = NovaAppearance.texture(activity)
        if (!texture.hasPattern) return
        val base = runCatching {
            val value = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.windowBackground, value, true)
            if (value.resourceId != 0) {
                androidx.core.content.ContextCompat.getDrawable(activity, value.resourceId)
            } else {
                android.graphics.drawable.ColorDrawable(value.data)
            }
        }.getOrNull()
        val overlay = surfaceOverlay(activity)
        val layers = if (base != null) {
            android.graphics.drawable.LayerDrawable(arrayOf(base, overlay))
        } else {
            overlay
        }
        runCatching { window.setBackgroundDrawable(layers) }
    }

    /** Слой фактуры под оформление [state]: цвет — оттенок и насыщенность, прозрачность — яркость. */
    fun surfaceOverlay(
        context: Context,
        state: NovaAppearance.State = NovaAppearance.state(context),
    ): NovaAppearanceDrawable {
        val alpha = (NovaAppearanceDrawable.TEXTURE_ALPHA * state.brightness /
            NovaAppearance.BRIGHTNESS_DEFAULT.toFloat()).toInt().coerceIn(0, 255)
        return NovaAppearanceDrawable(
            texture = state.texture,
            accent = NovaAppearance.surfaceColor(state.hue, state.saturation),
            variant = state.variant,
            textureAlpha = alpha,
            rainAlpha = alpha,
        )
    }

    /**
     * Меняет фон **уже созданного** окна на месте: без пересоздания экрана и без
     * прыжка прокрутки. Так применяются фактура, яркость и насыщенность.
     */
    fun refreshSurface(
        activity: Activity,
        state: NovaAppearance.State = NovaAppearance.state(activity),
    ) {
        val window = activity.window ?: return
        val base = runCatching {
            val value = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.windowBackground, value, true)
            androidx.core.content.ContextCompat.getDrawable(activity, value.resourceId)
        }.getOrNull() ?: return
        val drawable: android.graphics.drawable.Drawable = if (state.texture.hasPattern) {
            android.graphics.drawable.LayerDrawable(arrayOf(base, surfaceOverlay(activity, state)))
        } else {
            base
        }
        runCatching { window.setBackgroundDrawable(drawable) }
    }

    /**
     * Окно во весь экран, а содержимое — в безопасных границах.
     *
     * Что было. Тема объявляла прозрачную строку состояния и
     * `windowTranslucentStatus`, но ничего не говорила про полосу навигации.
     * Получалось худшее из двух: сверху окно заходило под строку состояния и
     * срезало заголовок «Настройки» пополам, а снизу до полосы навигации не
     * доходило вовсе — и под подвалом оставалась чёрная полоса, не принадлежащая
     * приложению. Замечено на Pixel 4a.
     *
     * Что стало. Окно рисует себя целиком, включая обе полосы, поэтому чёрному
     * взяться неоткуда: там лежит фон темы. Отступы системных полос выдаются
     * содержимому как padding, поэтому заголовок не срезается, а подвал не
     * попадает под белую черту жеста.
     *
     * Отступы прибавляются к собственным, а не заменяют их: у экранов свои поля,
     * и заменить их значило бы прижать текст к краю там, где полосы нет.
     *
     * Зовётся из [apply], то есть до `setContentView`; сама раскладка ставится
     * посылкой, потому что до `setContentView` содержимого ещё нет.
     */
    private fun applyEdgeToEdge(activity: Activity) {
        val window = activity.window ?: return
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        // Слушатель ставится **сразу**, а не посылкой на следующий кадр.
        //
        // Посылка стоила заметного глазу рывка: первый кадр экран рисовался во всю
        // высоту окна, а на втором получал отступы системных полос и ужимался.
        // Владелец сообщил это как «на миг нормального размера, потом схлопывается»
        // — и видно это было на каждом пересоздании, то есть на каждом выборе темы.
        //
        // Ждать нечего: `android.R.id.content` существует с момента создания окна,
        // то есть ещё до `setContentView`, и отступы у него на этот момент нулевые —
        // ровно те, которые и надо запомнить как собственные.
        val content = activity.findViewById<android.view.View>(android.R.id.content)
        if (content == null) {
            window.decorView.post { applyEdgeToEdge(activity) }
            return
        }
        val basePaddingTop = content.paddingTop
        val basePaddingBottom = content.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                view.paddingLeft,
                basePaddingTop + bars.top,
                view.paddingRight,
                basePaddingBottom + bars.bottom,
            )
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(content)
    }

    /** Цвет из атрибута текущей темы. */
    fun color(context: Context, attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            androidx.core.content.ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    /**
     * Заливает заголовок группы градиентом роли A.
     *
     * Шейдером по `TextPaint`, а не картинкой: градиент по тексту в Android — это
     * `LinearGradient` на краске, и он ничего не весит. Ширина берётся у самого
     * вида, поэтому вызывать нужно после разметки — иначе она нулевая и текст
     * выходит одноцветным (шейдер нулевой ширины красит первым стопом).
     */
    fun paintHeading(view: TextView) {
        view.post {
            val width = view.width.toFloat()
            if (width <= 0f) return@post
            val from = color(view.context, R.attr.novaTextGroupFrom)
            val to = color(view.context, R.attr.novaTextGroupTo)
            view.paint.shader = LinearGradient(
                0f, 0f, width, 0f,
                from, to,
                Shader.TileMode.CLAMP,
            )
            view.invalidate()
        }
    }
}

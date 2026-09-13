package com.example.nova

import android.app.Activity
import android.content.Context
import android.graphics.LinearGradient
import android.graphics.Shader
import android.util.TypedValue
import android.widget.TextView

/**
 * Тема оформления экранов настроек.
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
 * Где хранится выбор. В `SharedPreferences` этого процесса, а не в файле:
 * тему читает только интерфейс, служба `:vpn` о ней не знает и знать не должна,
 * поэтому правило I2 (состояние UI↔`:vpn` живёт в файлах) сюда не относится.
 */
object NovaTheme {

    private const val PREFS = "nova_appearance"
    private const val KEY = "settings_theme"

    /** Ключ по умолчанию — ближайшая к прежнему виду приложения. */
    const val DEFAULT_KEY = "aurora"

    /**
     * Начертание всего интерфейса.
     *
     * Шрифты самих игр сюда не везут, и это решение, а не лень: Exocet (Diablo II)
     * и Fontin (Path of Exile) — чужие лицензии, каждый файл это сотни килобайт на
     * APK, а F-Droid пересобирает дерево побайтово (I15). Берутся ближайшие
     * встроенные семейства Android, они есть на любом устройстве и не весят ничего.
     *
     * `DEFAULT` — штатный Roboto. `MONOSPACE` — терминал. `SERIF` — с засечками,
     * ближайшее к Exocet и Fontin. `CONDENSED` — узкий гротеск, ближайшее к
     * интерфейсу Path of Exile 2. `HEAVY` — самое жирное, что есть в проекте
     * (Roboto Black), под «надутые» надписи GTA.
     */
    enum class Face { DEFAULT, MONOSPACE, SERIF, CONDENSED, HEAVY }

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
     * Порядок здесь — порядок в списке выбора и в `tools/gen_nova_themes.py`.
     * Расхождение ловит `NovaThemeTest`, а не глаз.
     */
    val ORDER: List<Option> = listOf(
        Option(
            "aurora", "Aurora mint", R.style.Theme_Nova_Settings_Auroramint,
            "Тёмный индиго и холодная мята — развитие прежнего вида",
        ),
        Option(
            "dnsai", "DNS-AI", R.style.Theme_Nova_Settings_DNSAI,
            "Графит и синий с мятой, как на dns-ai.ru",
        ),
        Option(
            "proton", "Proton", R.style.Theme_Nova_Settings_Proton,
            "Почти чёрный с фиолетовым, по мотивам Proton VPN",
        ),
        Option(
            "charcoal", "Charcoal birch", R.style.Theme_Nova_Settings_Charcoalbirch,
            "Уголь и бирюза, ничего лишнего",
        ),
        Option(
            "graphene", "Graphene golden", R.style.Theme_Nova_Settings_Graphenegolden,
            "Графит и тёплое золото",
        ),
        Option(
            "neon", "Cyber neon", R.style.Theme_Nova_Settings_Cyberneon,
            "Матовая ночь, бирюза и маджента — свечение только у заголовков",
        ),
        Option(
            "poe1", "Path of Exile 1", R.style.Theme_Nova_Settings_PathofExile1,
            "Тёплый чёрный, золотая обводка, прямые углы",
            face = Face.SERIF,
        ),
        Option(
            "poe2", "Path of Exile 2", R.style.Theme_Nova_Settings_PathofExile2,
            "Оружейная сталь и расплавленная медь, панель светлее сверху",
            face = Face.CONDENSED,
        ),
        Option(
            "gta6sunset", "GTA VI Vice Sunset", R.style.Theme_Nova_Settings_GTAVIViceSunset,
            "Индиго и лаванда, заголовки фирменным персик→розовый",
            face = Face.HEAVY,
        ),
        Option(
            "gta6night", "GTA VI Vice Night", R.style.Theme_Nova_Settings_GTAVIViceNight,
            "Та же марка после заката: розовый на состояниях, ледяной на данных",
            face = Face.HEAVY,
        ),
        Option(
            "matrix", "Matrix", R.style.Theme_Nova_Settings_Matrix,
            "Терминал на ЭЛТ: люминофорный зелёный по чёрному, моноширинный шрифт, прямые углы",
            face = Face.MONOSPACE,
        ),
        Option(
            "diablo2", "Diablo II", R.style.Theme_Nova_Settings_DiabloII,
            "Камень и золотая филигрань: кнопка — плита с самоцветами, шрифт с засечками",
            face = Face.SERIF,
        ),
    )

    fun optionFor(key: String): Option =
        ORDER.firstOrNull { it.key == key } ?: ORDER.first()

    fun current(context: Context): String {
        // Предпросмотр старше сохранённого: экран оформления показывает выбор до
        // того, как его подтвердили (см. [NovaAppearance]).
        NovaAppearance.previewThemeKey()?.let { preview ->
            if (ORDER.any { it.key == preview }) return preview
        }
        val stored = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, DEFAULT_KEY)
            .orEmpty()
        // Неизвестный ключ — это откат на прошлую версию или правка prefs руками.
        // Молча брать первый попавшийся нельзя было бы, если бы от темы что-то
        // зависело; здесь зависит только внешний вид, и падать из-за него глупо.
        return if (ORDER.any { it.key == stored }) stored else DEFAULT_KEY
    }

    fun store(context: Context, key: String) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, optionFor(key).key)
            .apply()
    }

    /**
     * Ставит тему экрану. Зовётся **до** `super.onCreate` и `setContentView`:
     * позже окно уже создано со старым фоном, и смена темы доедет только до
     * следующего запуска активности.
     */
    fun apply(activity: Activity) {
        activity.setTheme(optionFor(current(activity)).styleRes)
        applySurfaceOverlay(activity)
        applyEdgeToEdge(activity)
    }

    /**
     * Кладёт фактуру и температуру поверх фона окна.
     *
     * Фон темы остаётся нижним слоем и берётся у самой темы
     * (`android:windowBackground`), а не выписывается здесь: у двенадцати тем он
     * разный, и вторая копия этого соответствия — это ровно G49.
     *
     * Слоя нет вовсе, пока нечего рисовать: «без фактуры» и нулевая температура
     * обязаны стоить приложению столько же, сколько стоили до появления этого
     * списка.
     */
    private fun applySurfaceOverlay(activity: Activity) {
        val window = activity.window ?: return
        if (!NovaAppearance.hasOverlay(activity)) return
        val base = runCatching {
            val value = TypedValue()
            activity.theme.resolveAttribute(android.R.attr.windowBackground, value, true)
            if (value.resourceId != 0) {
                androidx.core.content.ContextCompat.getDrawable(activity, value.resourceId)
            } else {
                android.graphics.drawable.ColorDrawable(value.data)
            }
        }.getOrNull()
        val overlay = NovaAppearanceDrawable(
            texture = NovaAppearance.texture(activity),
            temperature = NovaAppearance.temperature(activity),
            accent = color(activity, R.attr.novaAccent),
            variant = NovaAppearance.variant(activity),
        )
        val layers = if (base != null) {
            android.graphics.drawable.LayerDrawable(arrayOf(base, overlay))
        } else {
            overlay
        }
        runCatching { window.setBackgroundDrawable(layers) }
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

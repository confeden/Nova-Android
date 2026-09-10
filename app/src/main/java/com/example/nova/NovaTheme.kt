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
     * Порядок здесь — порядок в списке выбора и в `tools/gen_nova_themes.py`.
     * Расхождение ловит `NovaThemeTest`, а не глаз.
     */
    data class Option(val key: String, val title: String, val styleRes: Int, val note: String)

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
        ),
        Option(
            "poe2", "Path of Exile 2", R.style.Theme_Nova_Settings_PathofExile2,
            "Оружейная сталь и расплавленная медь, панель светлее сверху",
        ),
        Option(
            "gta6sunset", "GTA VI Vice Sunset", R.style.Theme_Nova_Settings_GTAVIViceSunset,
            "Индиго и лаванда, заголовки фирменным персик→розовый",
        ),
        Option(
            "gta6night", "GTA VI Vice Night", R.style.Theme_Nova_Settings_GTAVIViceNight,
            "Та же марка после заката: розовый на состояниях, ледяной на данных",
        ),
    )

    fun optionFor(key: String): Option =
        ORDER.firstOrNull { it.key == key } ?: ORDER.first()

    fun current(context: Context): String {
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

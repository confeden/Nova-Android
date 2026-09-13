package com.example.nova

import android.content.Context

/**
 * Оформление сверх темы: фактура поверхности и температура оттенка.
 *
 * ## Почему это отдельно от [NovaTheme]
 *
 * Тема — это палитра и фигуры, то есть ресурсы: их выдаёт XML, и меняются они
 * только пересозданием экрана. Фактура и температура — слой поверх, он рисуется
 * кодом ([NovaAppearanceDrawable]) и к ресурсам отношения не имеет. Смешать их
 * значило бы либо плодить XML-темы по числу сочетаний (12 тем × 15 фактур × 41
 * положение ползунка), либо держать в теме то, чего она не умеет.
 *
 * ## Предпросмотр
 *
 * Владелец попросил, чтобы любое изменение оформления было видно **сразу**, ещё
 * до «Применить». Поэтому у каждого значения два слоя: сохранённый и
 * предпросмотр. Предпросмотр живёт в памяти процесса интерфейса и старше
 * сохранённого; «Применить» переписывает сохранённый и снимает предпросмотр,
 * уход с экрана — просто снимает. Ни один экран об этом не знает: все они
 * спрашивают [texture] и [temperature], а те сами решают, что отдать.
 *
 * В файл предпросмотр не пишется намеренно: он не должен пережить ни закрытие
 * экрана, ни тем более смерть процесса — иначе «посмотрел и передумал»
 * превращается в «поменял навсегда».
 */
object NovaAppearance {

    private const val PREFS = "nova_appearance"
    private const val KEY_TEXTURE = "surface_texture"
    private const val KEY_TEMPERATURE = "surface_temperature"
    private const val KEY_VARIANT = "surface_variant"

    /**
     * Фактура поверхности.
     *
     * Названия английские — так попросил владелец. `NONE` — «без фактуры», и это
     * умолчание: приложение обязано выглядеть так же, как до появления этого
     * списка, пока человек не выбрал иное.
     */
    enum class Texture(val key: String, val title: String) {
        NONE("none", "None"),
        BASALT("basalt", "Basalt"),
        MARBLE("marble", "Marble"),
        MICA("mica", "Mica"),
        WOOD("wood", "Wood"),
        SAND("sand", "Sand"),
        GRASS("grass", "Grass"),
        CRACKS("cracks", "Cracks"),
        WATER("water", "Water"),
        SMOKE("smoke", "Smoke"),
        STARFIELD("starfield", "Starfield"),
        SLIME("slime", "Slime"),
        SHARDS("shards", "Shards"),
        SPLATTER("splatter", "Splatter"),
        GLOW("glow", "Glow"),
        LIGHTNING("lightning", "Lightning"),
        SNOW("snow", "Snow"),
        ;

        companion object {
            fun of(key: String?): Texture =
                entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: NONE
        }
    }

    /** Пределы ползунка температуры: минус — тёплый, плюс — холодный, ноль — как есть. */
    const val TEMPERATURE_MIN = -100
    const val TEMPERATURE_MAX = 100
    const val TEMPERATURE_NEUTRAL = 0

    @Volatile
    private var previewTexture: Texture? = null

    @Volatile
    private var previewTemperature: Int? = null

    @Volatile
    private var previewTheme: String? = null

    @Volatile
    private var previewVariant: Int? = null

    /** Фактура, которую надо рисовать прямо сейчас. */
    fun texture(context: Context): Texture =
        previewTexture ?: Texture.of(prefs(context).getString(KEY_TEXTURE, Texture.NONE.key))

    /** Температура оттенка, которую надо применять прямо сейчас. */
    fun temperature(context: Context): Int =
        previewTemperature ?: prefs(context)
            .getInt(KEY_TEMPERATURE, TEMPERATURE_NEUTRAL)
            .coerceIn(TEMPERATURE_MIN, TEMPERATURE_MAX)

    /**
     * Номер пересева узора.
     *
     * Фактура — это правило построения, а не одна картинка: у «Молнии» разряды
     * могут пройти иначе, у «Снега» вырасти другие кристаллы. Владелец попросил,
     * чтобы повторное касание уже выбранной фактуры строило узор заново, — этот
     * счётчик и есть то, чем «заново» отличается от «то же самое». Он входит в
     * зерно случайности ([NovaTexturePatterns.render]) и в ключ кэша
     * ([NovaAppearanceDrawable]), поэтому пересев не берётся из кэша, а прежний
     * вариант из него не пропадает.
     */
    fun variant(context: Context): Int =
        previewVariant ?: prefs(context).getInt(KEY_VARIANT, 0)

    fun storedVariant(context: Context): Int = prefs(context).getInt(KEY_VARIANT, 0)

    /** Ключ темы для предпросмотра, если он задан. Читается из [NovaTheme.current]. */
    fun previewThemeKey(): String? = previewTheme

    /** Сохранённая тема — мимо предпросмотра. */
    fun storedThemeKey(context: Context): String =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("settings_theme", NovaTheme.DEFAULT_KEY)
            .orEmpty()
            .ifBlank { NovaTheme.DEFAULT_KEY }

    /** Сохранённые значения — нужны экрану оформления, чтобы знать, что отменять. */
    fun storedTexture(context: Context): Texture =
        Texture.of(prefs(context).getString(KEY_TEXTURE, Texture.NONE.key))

    fun storedTemperature(context: Context): Int =
        prefs(context).getInt(KEY_TEMPERATURE, TEMPERATURE_NEUTRAL)
            .coerceIn(TEMPERATURE_MIN, TEMPERATURE_MAX)

    /** Показать, не сохраняя. */
    fun preview(theme: String?, texture: Texture?, temperature: Int?, variant: Int?) {
        previewTheme = theme
        previewTexture = texture
        previewTemperature = temperature?.coerceIn(TEMPERATURE_MIN, TEMPERATURE_MAX)
        previewVariant = variant
    }

    /** Снять предпросмотр: дальше действуют сохранённые значения. */
    fun clearPreview() {
        previewTheme = null
        previewTexture = null
        previewTemperature = null
        previewVariant = null
    }

    /** Записать выбор насовсем и снять предпросмотр. */
    fun store(
        context: Context,
        theme: String,
        texture: Texture,
        temperature: Int,
        variant: Int,
    ) {
        NovaTheme.store(context, theme)
        prefs(context).edit()
            .putString(KEY_TEXTURE, texture.key)
            .putInt(KEY_TEMPERATURE, temperature.coerceIn(TEMPERATURE_MIN, TEMPERATURE_MAX))
            .putInt(KEY_VARIANT, variant)
            .apply()
        clearPreview()
    }

    /** Есть ли что рисовать поверх темы. Пустой слой не стоит и одного вида. */
    fun hasOverlay(context: Context): Boolean =
        texture(context) != Texture.NONE || temperature(context) != TEMPERATURE_NEUTRAL

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

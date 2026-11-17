package com.example.nova

import android.content.Context
import android.graphics.Color

/**
 * Оформление: оттенок, фон и три ползунка — одним местом.
 *
 * ## Что убрано и почему
 *
 * Раньше были двенадцать готовых тем, отдельная «температура» и отдельный экран
 * «Фон главного экрана» с тремя режимами. Владелец свёл всё в один экран по образцу
 * ПК-версии: **оттенок** (ползунок на 64 шага), **яркость**, **насыщенность**,
 * **скорость погружения**, плитки фактур и переключатель «случайная фактура при
 * запуске». Тем больше нет: палитру целиком выводит оттенок (`tools/gen_nova_themes.py`).
 *
 * ## Что чем управляет
 *
 * - **Оттенок** — единственное, что меняет ресурсы (цвета интерфейса), поэтому
 *   экран пересоздаётся только на нём. Остальное — это слой фона, он рисуется
 *   кодом и применяется на месте, без пересоздания и без прыжка прокрутки.
 * - **Яркость** — прозрачность фактуры; 60 — вид, который был до появления ползунка.
 * - **Насыщенность** — насыщенность цвета самой фактуры; интерфейс остаётся в
 *   палитре оттенка.
 * - **Скорость погружения** — скорость медленного «полёта» фона на главном экране.
 *
 * ## Фон главного экрана — тоже фактура
 *
 * «Созвездия» (прежняя анимация) и «Без фона» — плитки того же списка. Режим
 * главного экрана выводится из выбранной фактуры ([MainBackgroundPolicy.modeFor]), а не
 * хранится второй раз: два источника правды на одно решение расходятся.
 *
 * ## Случайная фактура при подключении
 *
 * Включена по умолчанию. Выбор делает [pickAtConnection] — один раз на новое подключение — и записывает его как обычный выбор, поэтому дальше экраны ничем не
 * отличают «выбрано случайно» от «выбрано пальцем».
 */
object NovaAppearance {

    private const val PREFS = "nova_appearance"
    private const val KEY_TEXTURE = "surface_texture"
    private const val KEY_VARIANT = "surface_variant"
    private const val KEY_HUE = "accent_hue"
    private const val KEY_BRIGHTNESS = "brightness"
    private const val KEY_SATURATION = "saturation"
    private const val KEY_SPEED = "dive_speed"
    // Keep the stored toggle so an explicit opt-out survives the event change.
    private const val KEY_RANDOM = "random_texture_on_start"
    private const val KEY_LAST_CONNECTION = "texture_connection_at"

    /**
     * Фактура поверхности.
     *
     * `NONE` — «Без фона»: на главном экране это пустое поле, на экранах настроек —
     * просто цвет оттенка. `CONSTELLATION` — прежняя анимация главного экрана; на
     * экранах настроек у неё нет узора, там тоже один цвет.
     */
    enum class Texture(val key: String, val title: String) {
        CONSTELLATION("constellation", "Созвездия"),
        NONE("none", "Без фона"),
        BASALT("basalt", "Basalt"),
        MARBLE("marble", "Marble"),
        WOOD("wood", "Wood"),
        SAND("sand", "Sand"),
        GRASS("grass", "Луг"),
        CRACKS("cracks", "Cracks"),
        WATER("water", "Water"),
        SMOKE("smoke", "Smoke"),
        STARFIELD("starfield", "Starfield"),
        SLIME("slime", "Slime"),
        SHARDS("shards", "Shards"),
        SPLATTER("splatter", "Splatter"),
        LIGHTNING("lightning", "Lightning"),
        SNOW("snow", "Snow"),
        FRACTAL("fractal", "Fractal"),
        MATRIX("matrix", "Matrix"),
        ;

        /** Есть ли у фактуры растровый узор, который строит [NovaTexturePatterns]. */
        val hasPattern: Boolean get() = this != NONE && this != CONSTELLATION

        companion object {
            fun of(key: String?): Texture? =
                entries.firstOrNull { it.key == key?.trim()?.lowercase() }
        }
    }

    const val HUE_COUNT = 64

    /** 168,75° — бирюза между голубым и зелёным. Совпадает с `DEFAULT_HUE` генератора. */
    const val DEFAULT_HUE = 30

    const val BRIGHTNESS_MAX = 100
    const val BRIGHTNESS_DEFAULT = 60

    const val SATURATION_MAX = 200
    const val SATURATION_DEFAULT = 100

    const val SPEED_MAX = 200
    const val SPEED_DEFAULT = 100

    /** Состояние оформления целиком — то, что экран оформления читает и пишет. */
    data class State(
        val hue: Int,
        val texture: Texture,
        val variant: Int,
        val brightness: Int,
        val saturation: Int,
        val speed: Int,
        val randomOnConnect: Boolean,
    )

    fun state(context: Context): State = State(
        hue = hue(context),
        texture = texture(context),
        variant = variant(context),
        brightness = brightness(context),
        saturation = saturation(context),
        speed = speed(context),
        randomOnConnect = randomOnConnect(context),
    )

    fun hue(context: Context): Int =
        prefs(context).getInt(KEY_HUE, DEFAULT_HUE).coerceIn(0, HUE_COUNT - 1)

    /**
     * Выбранная фактура. По умолчанию — анимация «Созвездия», а при включённом
     * «случайно при подключении» её тут же заменяет [pickAtConnection]. На устройстве, не
     * тянущем анимацию, умолчание — пустой фон: полноэкранный растр там дороже пустоты.
     */
    fun texture(context: Context): Texture =
        Texture.of(prefs(context).getString(KEY_TEXTURE, null))
            ?: if (MainBackgroundPolicy.isAnimationSupported(context)) Texture.CONSTELLATION else Texture.NONE

    fun variant(context: Context): Int = prefs(context).getInt(KEY_VARIANT, 0)

    fun brightness(context: Context): Int =
        prefs(context).getInt(KEY_BRIGHTNESS, BRIGHTNESS_DEFAULT).coerceIn(0, BRIGHTNESS_MAX)

    fun saturation(context: Context): Int =
        prefs(context).getInt(KEY_SATURATION, SATURATION_DEFAULT).coerceIn(0, SATURATION_MAX)

    fun speed(context: Context): Int =
        prefs(context).getInt(KEY_SPEED, SPEED_DEFAULT).coerceIn(0, SPEED_MAX)

    fun randomOnConnect(context: Context): Boolean = prefs(context).getBoolean(KEY_RANDOM, true)

    /** Ключ темы оттенка (`hue30`) — им пользуются зеркало уведомления и виджет. */
    fun storedThemeKey(context: Context): String = "hue${hue(context)}"

    /**
     * Цвет, которым красится фактура: оттенок плюс насыщенность. Яркость сюда не
     * входит — она задаёт прозрачность узора, а не светлоту его цвета.
     */
    fun surfaceColor(context: Context): Int = surfaceColor(hue(context), saturation(context))

    fun surfaceColor(hue: Int, saturation: Int): Int {
        val s = (BASE_SATURATION * saturation / SATURATION_DEFAULT.toFloat()).coerceIn(0f, 1f)
        return hsl(hue * 360f / HUE_COUNT, s, BASE_LIGHTNESS)
    }

    /** Множитель прозрачности узора по ползунку яркости. 60 даёт ровно прежний вид. */
    fun alphaFactor(context: Context): Float = brightness(context) / BRIGHTNESS_DEFAULT.toFloat()

    // ------------------------------------------------------------------ запись

    fun setHue(context: Context, hue: Int) {
        val value = hue.coerceIn(0, HUE_COUNT - 1)
        val previous = hue(context)
        prefs(context).edit().putInt(KEY_HUE, value).apply()
        if (previous != value) {
            // Службе `:vpn` из всей темы нужен один акцент (заливка уведомления), и
            // виджет на столе красится тем же цветом (I2).
            NovaNotificationPalette.onThemeStored(context, "hue$value")
            runCatching { NovaWidgetProvider.refreshAll(context.applicationContext) }
        }
    }

    fun setTexture(context: Context, texture: Texture, variant: Int = variant(context)) {
        prefs(context).edit()
            .putString(KEY_TEXTURE, texture.key)
            .putInt(KEY_VARIANT, variant)
            .apply()
    }

    fun setBrightness(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_BRIGHTNESS, value.coerceIn(0, BRIGHTNESS_MAX)).apply()
    }

    fun setSaturation(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_SATURATION, value.coerceIn(0, SATURATION_MAX)).apply()
    }

    fun setSpeed(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_SPEED, value.coerceIn(0, SPEED_MAX)).apply()
    }

    fun setRandomOnConnect(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_RANDOM, enabled).apply()
    }

    // ------------------------------------------------------------------ случайный выбор

    /**
     * Select once per connection on a worker thread. The persisted connection id
     * prevents a second selection after activity recreation.
     *
     * Выбор записывается как обычный — так экраны настроек и главный экран видят
     * одно и то же без отдельного «временного» слоя.
     */
    @Synchronized
    fun pickAtConnection(context: Context, connectedAt: Long): Boolean {
        if (connectedAt <= 0L || prefs(context).getLong(KEY_LAST_CONNECTION, 0L) == connectedAt) return false
        prefs(context).edit().putLong(KEY_LAST_CONNECTION, connectedAt).apply()
        if (!randomOnConnect(context)) return false
        val pool = randomPool(context)
        if (pool.isEmpty()) return false
        val current = texture(context)
        // Тот же вид подряд выглядел бы как «не сработало».
        val candidates = if (pool.size > 1) pool.filter { it != current } else pool
        val pick = candidates.random()
        setTexture(context, pick, variant = (0..9999).random())
        return true
    }

    /** Из чего выбирает «случайно»: любая фактура, кроме «Без фона»; анимация — если тянет. */
    fun randomPool(context: Context): List<Texture> {
        val animation = MainBackgroundPolicy.isAnimationSupported(context)
        return Texture.entries.filter {
            it != Texture.NONE && (it != Texture.CONSTELLATION || animation)
        }
    }

    // ------------------------------------------------------------------ цвет

    /** Акцент по умолчанию ярче чуть насыщеннее ПК-версии — «немного неоновый». */
    private const val BASE_SATURATION = 0.78f
    private const val BASE_LIGHTNESS = 0.54f

    private fun hsl(hueDegrees: Float, s: Float, l: Float): Int {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val h = ((hueDegrees % 360f) + 360f) % 360f / 60f
        val x = c * (1f - kotlin.math.abs(h % 2f - 1f))
        val (r1, g1, b1) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return Color.rgb(
            ((r1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255),
            ((g1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255),
            ((b1 + m) * 255f + 0.5f).toInt().coerceIn(0, 255),
        )
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

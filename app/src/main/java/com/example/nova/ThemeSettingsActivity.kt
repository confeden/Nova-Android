package com.example.nova

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Единый экран «Оформление».
 *
 * Раньше их было два: «Оформление» (двенадцать тем, фактуры чипами, температура) и
 * «Фон главного экрана» (три режима). Теперь всё в одном месте, по образцу окна
 * ПК-версии: плитки фактур, ползунки оттенка, яркости, насыщенности и скорости
 * погружения, переключатель случайной фактуры при подключении.
 *
 * ## Как это применяется
 *
 * Любое изменение действует **сразу**, без «Применить», и ни одно не поднимает
 * страницу наверх:
 *
 * - плитки, яркость, насыщенность, скорость и переключатель меняют сохранённое и
 *   фон окна **на месте** ([NovaTheme.refreshSurface]) — экран не пересоздаётся,
 *   поэтому прокрутка не двигается вовсе;
 * - оттенок — единственное, что меняет ресурсы (цвета интерфейса), а они выдаются
 *   окну при создании. Поэтому во время движения пальца перекрашиваются фон и плитки,
 *   а на отпускании экран пересоздаётся с сохранением позиции прокрутки.
 *
 * Состояние живёт в [NovaAppearance], а не в экране, — пересозданный экран просто
 * читает его заново.
 */
class ThemeSettingsActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val tiles = LinkedHashMap<NovaAppearance.Texture, TextureTileView>()
    private lateinit var scroll: ScrollView
    private lateinit var randomSwitch: Switch

    private lateinit var hueBar: SeekBar
    private lateinit var brightnessBar: SeekBar
    private lateinit var saturationBar: SeekBar
    private lateinit var speedBar: SeekBar
    private lateinit var hueValue: TextView
    private lateinit var brightnessValue: TextView
    private lateinit var saturationValue: TextView
    private lateinit var speedValue: TextView

    /** Оттенок, который показывает ползунок, пока он ещё не записан. */
    private var liveHue = NovaAppearance.DEFAULT_HUE

    /** Рисовать ли плитки и фон по движущемуся ползунку оттенка. */
    private var draggingHue = false

    private val commitHue = Runnable { commitHueAndRecreate() }
    private val refreshLook = Runnable { refreshLookNow() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тему ставим до super.onCreate: позже окно уже создано со старым фоном.
        NovaTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_settings)
        LogManager.setAppContext(this)
        findViewById<TextView>(R.id.tv_texture_hint).collapseToInfoIcon()

        scroll = findViewById(R.id.sv_theme_scroll)
        liveHue = NovaAppearance.hue(this)

        buildTiles()
        buildSliders()

        randomSwitch = findViewById(R.id.sw_random_texture)
        randomSwitch.isChecked = NovaAppearance.randomOnConnect(this)
        randomSwitch.setOnCheckedChangeListener { _, checked ->
            NovaAppearance.setRandomOnConnect(this, checked)
            LogManager.log("Оформление: случайная фактура при подключении ${if (checked) "включена" else "выключена"}.")
        }
        findViewById<View>(R.id.row_random_texture).setOnClickListener { randomSwitch.toggle() }

        findViewById<Button>(R.id.btn_appearance_default).let { button ->
            NovaDialogs.styleButton(button, primary = false)
            button.setOnClickListener { resetToDefaults() }
        }

        NovaFontHelper.apply(findViewById(android.R.id.content))
        restoreScroll()
    }

    override fun onPause() {
        // Оттенок, который ещё ждал отпускания, не теряется при уходе назад (I20).
        if (draggingHue || hasHueChange()) {
            main.removeCallbacks(commitHue)
            NovaAppearance.setHue(this, liveHue)
        }
        super.onPause()
    }

    override fun onDestroy() {
        main.removeCallbacks(commitHue)
        main.removeCallbacks(refreshLook)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ плитки

    private fun buildTiles() {
        val grid = findViewById<GridLayout>(R.id.gl_textures)
        grid.removeAllViews()
        tiles.clear()
        val state = NovaAppearance.state(this)
        val gap = (4 * resources.displayMetrics.density).toInt()
        val unsupported = !MainBackgroundPolicy.isAnimationSupported(this)
        val textures = NovaAppearance.Texture.entries.filter {
            // Анимация на слабом устройстве не показывается вовсе: выбрать её нельзя.
            !(it == NovaAppearance.Texture.CONSTELLATION && unsupported)
        }
        textures.forEachIndexed { index, texture ->
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = GridLayout.LayoutParams(
                    GridLayout.spec(index / COLUMNS),
                    GridLayout.spec(index % COLUMNS, 1f),
                ).apply {
                    width = 0
                    setMargins(gap, gap, gap, gap)
                }
            }
            val tile = TextureTileView(this, texture).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                contentDescription = texture.title
                setOnClickListener { onTileClicked(texture) }
            }
            val caption = TextView(this).apply {
                text = texture.title
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                setPadding(0, gap, 0, 0)
                setTextColor(NovaTheme.color(this@ThemeSettingsActivity, R.attr.novaTextValue))
            }
            cell.addView(tile)
            cell.addView(caption)
            grid.addView(cell)
            tiles[texture] = tile
        }
        paintTiles(state)
    }

    /** Цвета и выбор плиток из [state]; миниатюры достраиваются в фоне. */
    private fun paintTiles(state: NovaAppearance.State) {
        val tint = NovaAppearance.surfaceColor(liveHue, state.saturation)
        val factor = state.brightness / NovaAppearance.BRIGHTNESS_DEFAULT.toFloat()
        val ground = NovaTheme.color(this, R.attr.novaScreenMid)
        val accent = NovaTheme.color(this, R.attr.novaAccent)
        for ((texture, tile) in tiles) {
            tile.tint = tint
            tile.alphaFactor = factor
            tile.ground = ground
            tile.selectionColor = accent
            tile.chosen = texture == state.texture
            if (texture.hasPattern) {
                // Выбранная плитка показывает свой пересев, остальные — первый.
                val variant = if (texture == state.texture) state.variant else 0
                NovaTextureThumbs.request(texture, variant) { bitmap ->
                    if (!isDestroyed && tiles[texture] === tile &&
                        (if (texture == NovaAppearance.texture(this)) NovaAppearance.variant(this) else 0) == variant
                    ) {
                        tile.mask = bitmap
                        tile.invalidate()
                    }
                }
            }
            tile.invalidate()
        }
    }

    private fun onTileClicked(texture: NovaAppearance.Texture) {
        val state = NovaAppearance.state(this)
        // Касание уже выбранной фактуры — просьба построить узор заново: фактура
        // задаёт правило, а не одну картинку. Другая плитка просто переключает,
        // иначе выбор каждый раз выдавал бы ещё и случайный пересев и вернуться к
        // увиденному было бы нельзя.
        val variant = if (texture == state.texture && texture.hasPattern) state.variant + 1 else state.variant
        NovaAppearance.setTexture(this, texture, variant)
        LogManager.log("Оформление: фактура ${texture.title}.")
        refreshLookNow()
    }

    // ------------------------------------------------------------------ ползунки

    private fun buildSliders() {
        val container = findViewById<LinearLayout>(R.id.ll_sliders)
        container.removeAllViews()
        val state = NovaAppearance.state(this)

        val hueRow = addSliderRow(container, "Оттенок")
        hueBar = hueRow.bar
        hueValue = hueRow.value
        hueBar.max = NovaAppearance.HUE_COUNT - 1
        hueBar.progress = state.hue
        hueBar.progressDrawable = RainbowTrackDrawable((8 * resources.displayMetrics.density).toInt())
        hueBar.splitTrack = false
        renderHue(state.hue)
        hueBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                liveHue = progress
                renderHue(progress)
                main.removeCallbacks(refreshLook)
                main.postDelayed(refreshLook, LOOK_DEBOUNCE_MS)
                // Кнопками пульта ползунок не «отпускают» — ждём паузу и применяем.
                if (!draggingHue) {
                    main.removeCallbacks(commitHue)
                    main.postDelayed(commitHue, KEY_COMMIT_MS)
                }
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                draggingHue = true
                main.removeCallbacks(commitHue)
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                draggingHue = false
                main.removeCallbacks(commitHue)
                commitHueAndRecreate()
            }
        })

        val brightnessRow = addSliderRow(container, "Яркость")
        brightnessBar = brightnessRow.bar
        brightnessValue = brightnessRow.value
        bindPlainSlider(brightnessBar, brightnessValue, NovaAppearance.BRIGHTNESS_MAX, state.brightness,
            { "$it %" }) { NovaAppearance.setBrightness(this, it) }

        val saturationRow = addSliderRow(container, "Насыщенность цвета")
        saturationBar = saturationRow.bar
        saturationValue = saturationRow.value
        bindPlainSlider(saturationBar, saturationValue, NovaAppearance.SATURATION_MAX, state.saturation,
            { "$it %" }) { NovaAppearance.setSaturation(this, it) }

        val speedRow = addSliderRow(container, "Скорость погружения")
        speedBar = speedRow.bar
        speedValue = speedRow.value
        // Скорость не перерисовывает ничего на этом экране: её читает главный.
        bindPlainSlider(speedBar, speedValue, NovaAppearance.SPEED_MAX, state.speed,
            { if (it == 0) "стоит" else "$it %" }, redraw = false) { NovaAppearance.setSpeed(this, it) }
    }

    private class SliderRow(val bar: SeekBar, val value: TextView)

    private fun addSliderRow(parent: LinearLayout, caption: String): SliderRow {
        val row = layoutInflater.inflate(R.layout.item_appearance_slider, parent, false)
        row.findViewById<TextView>(R.id.tv_slider_caption).text = caption
        parent.addView(row)
        val bar = row.findViewById<SeekBar>(R.id.sb_slider)
        // У четырёх ползунков один и тот же id: платформа сохраняла бы их состояние по
        // id и после пересоздания записала бы значение последнего во все четыре.
        // Правда о значениях живёт в [NovaAppearance], а не в виде.
        bar.isSaveEnabled = false
        return SliderRow(bar, row.findViewById(R.id.tv_slider_value))
    }

    private fun bindPlainSlider(
        bar: SeekBar,
        value: TextView,
        max: Int,
        initial: Int,
        format: (Int) -> String,
        redraw: Boolean = true,
        store: (Int) -> Unit,
    ) {
        bar.max = max
        bar.progress = initial
        value.text = format(initial)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seek: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = format(progress)
                if (!fromUser) return
                store(progress)
                if (redraw) {
                    main.removeCallbacks(refreshLook)
                    main.postDelayed(refreshLook, LOOK_DEBOUNCE_MS)
                }
            }

            override fun onStartTrackingTouch(seek: SeekBar) = Unit
            override fun onStopTrackingTouch(seek: SeekBar) = Unit
        })
    }

    private fun renderHue(hue: Int) {
        hueValue.text = NovaAppearanceUi.hueName(hue)
    }

    // ------------------------------------------------------------------ применение

    /** Применяет оформление к окну и плиткам на месте — без пересоздания и без прокрутки. */
    private fun refreshLookNow() {
        main.removeCallbacks(refreshLook)
        val stored = NovaAppearance.state(this)
        val shown = stored.copy(hue = liveHue)
        NovaTheme.refreshSurface(this, shown)
        paintTiles(shown)
    }

    private fun hasHueChange(): Boolean = liveHue != NovaAppearance.hue(this)

    /** Записывает оттенок и пересоздаёт экран, сохранив позицию прокрутки. */
    private fun commitHueAndRecreate() {
        if (!hasHueChange()) return
        NovaAppearance.setHue(this, liveHue)
        LogManager.log("Оформление: оттенок $liveHue (${NovaAppearanceUi.hueName(liveHue)}).")
        savedScrollY = scroll.scrollY
        recreate()
    }

    private fun resetToDefaults() {
        NovaAppearance.setBrightness(this, NovaAppearance.BRIGHTNESS_DEFAULT)
        NovaAppearance.setSaturation(this, NovaAppearance.SATURATION_DEFAULT)
        NovaAppearance.setSpeed(this, NovaAppearance.SPEED_DEFAULT)
        NovaAppearance.setRandomOnConnect(this, true)
        LogManager.log("Оформление: ползунки возвращены к умолчаниям.")
        val hueChanges = NovaAppearance.hue(this) != NovaAppearance.DEFAULT_HUE
        liveHue = NovaAppearance.DEFAULT_HUE
        if (hueChanges) {
            NovaAppearance.setHue(this, NovaAppearance.DEFAULT_HUE)
            savedScrollY = scroll.scrollY
            recreate()
            return
        }
        brightnessBar.progress = NovaAppearance.BRIGHTNESS_DEFAULT
        saturationBar.progress = NovaAppearance.SATURATION_DEFAULT
        speedBar.progress = NovaAppearance.SPEED_DEFAULT
        randomSwitch.isChecked = true
        refreshLookNow()
    }

    /** Возвращает прокрутку после пересоздания: оттенок не должен поднимать страницу наверх. */
    private fun restoreScroll() {
        val target = savedScrollY
        if (target <= 0) return
        savedScrollY = 0
        scroll.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                scroll.viewTreeObserver.removeOnPreDrawListener(this)
                scroll.scrollTo(0, target)
                return true
            }
        })
    }

    private companion object {
        const val COLUMNS = 3
        const val LOOK_DEBOUNCE_MS = 30L
        const val KEY_COMMIT_MS = 600L

        /** Позиция прокрутки на время пересоздания экрана. */
        var savedScrollY = 0
    }
}

package com.example.nova

import android.os.Bundle
import android.widget.Button
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран «Оформление»: тема, фактура и температура — с живым предпросмотром.
 *
 * ## Почему экран, а не диалог
 *
 * Тема выбиралась диалогом со списком в столбик. Владелец попросил меню, как у
 * выбора региона на главном экране; заодно в диалог не поместились бы ни
 * пятнадцать фактур, ни ползунок.
 *
 * ## Как устроен предпросмотр
 *
 * Выбор кладётся в [NovaAppearance.preview] — это память процесса, не файл, — и
 * экран пересоздаёт себя. `NovaTheme.current` предпочитает предпросмотр
 * сохранённому, поэтому пересозданный экран рисуется уже по-новому, а ничего
 * записано ещё не было. «Применить» переписывает сохранённое и снимает
 * предпросмотр; уход с экрана снимает его молча.
 *
 * `recreate()`, а не точечная перекраска: тема — это ресурсы, они выдаются окну
 * при создании, и половину из них живому экрану не переставить вовсе. Пересоздание
 * стоит один кадр и даёт настоящий вид, а не его приближение.
 *
 * ## Почему выбор не теряется при пересоздании
 *
 * Он и не хранится в экране: единственный его носитель — предпросмотр в
 * [NovaAppearance]. Пересозданный экран читает оттуда же, откуда читает и тема.
 */
class ThemeSettingsActivity : AppCompatActivity() {

    private lateinit var themeGroup: FlowRadioGroup
    private lateinit var textureGroup: FlowRadioGroup
    private lateinit var temperatureBar: SeekBar
    private lateinit var temperatureValue: TextView
    private lateinit var themeNote: TextView

    /** Что стояло на входе. Нужно «Отмене» и уходу назад. */
    private var initialTheme: String = NovaTheme.DEFAULT_KEY
    private var initialTexture: NovaAppearance.Texture = NovaAppearance.Texture.NONE
    private var initialTemperature: Int = NovaAppearance.TEMPERATURE_NEUTRAL

    /** Применили ли выбор. Снимать предпросмотр в `onDestroy` тогда не надо. */
    private var applied = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Тему ставим до super.onCreate: позже окно уже создано со старым фоном.
        NovaTheme.apply(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_theme_settings)
        LogManager.setAppContext(this)

        // Сохранённые значения, а не текущие: «Сбросить» возвращает к тому, что
        // записано, и переживает любое число пересозданий экрана.
        initialTheme = NovaAppearance.storedThemeKey(this)
        initialTexture = NovaAppearance.storedTexture(this)
        initialTemperature = NovaAppearance.storedTemperature(this)

        themeGroup = findViewById(R.id.rg_theme)
        textureGroup = findViewById(R.id.rg_texture)
        temperatureBar = findViewById(R.id.sb_temperature)
        temperatureValue = findViewById(R.id.tv_temperature_value)
        themeNote = findViewById(R.id.tv_theme_note)

        val accent = NovaTheme.color(this, R.attr.novaAccent)
        themeGroup.selectionGlowColor = accent
        textureGroup.selectionGlowColor = accent

        buildThemeChips()
        buildTextureChips()
        setupTemperature()

        findViewById<Button>(R.id.btn_appearance_apply).let { button ->
            NovaDialogs.styleButton(button, primary = true)
            button.setOnClickListener { applySelection() }
        }
        findViewById<Button>(R.id.btn_temperature_default).let { button ->
            NovaDialogs.styleButton(button, primary = false)
            button.setOnClickListener {
                if (NovaAppearance.temperature(this) == NovaAppearance.TEMPERATURE_NEUTRAL) return@setOnClickListener
                previewWith(temperature = NovaAppearance.TEMPERATURE_NEUTRAL)
            }
        }

        NovaFontHelper.apply(findViewById(android.R.id.content))
    }

    override fun onDestroy() {
        // Снимать предпросмотр можно только когда экран уходит **насовсем**.
        //
        // Дефект, который это чинит: `recreate()` — это уничтожение и создание
        // заново, причём именно в таком порядке (`performDestroyActivity`, затем
        // `handleLaunchActivity`). Безусловный сброс в `onDestroy` стирал
        // предпросмотр за мгновение до того, как новый экран собирался его
        // прочитать, — и тот поднимался в прежней теме. Снаружи это выглядело как
        // «нажимаю на тему, ничего не происходит», а «Применить» следом сохраняла
        // прежний выбор, потому что читала то же пустое место.
        //
        // `isFinishing` отличает одно от другого: при уходе назад он `true`, при
        // пересоздании — `false`.
        if (!applied && isFinishing) NovaAppearance.clearPreview()
        super.onDestroy()
    }

    private fun currentThemeKey(): String = NovaAppearance.previewThemeKey() ?: NovaTheme.current(this)

    private fun buildThemeChips() {
        themeGroup.removeAllViews()
        val current = currentThemeKey()
        NovaTheme.ORDER.forEachIndexed { index, option ->
            val chip = RadioButton(this, null, 0, R.style.NovaAppearanceChip).apply {
                id = 0x7100 + index
                text = option.title
                isChecked = option.key == current
            }
            themeGroup.addView(chip)
            chip.setOnClickListener { previewWith(theme = option.key) }
        }
        themeNote.text = NovaTheme.optionFor(current).note
    }

    private fun buildTextureChips() {
        textureGroup.removeAllViews()
        val current = NovaAppearance.texture(this)
        NovaAppearance.Texture.entries.forEachIndexed { index, texture ->
            val chip = RadioButton(this, null, 0, R.style.NovaAppearanceChip).apply {
                id = 0x7200 + index
                text = texture.title
                isChecked = texture == current
            }
            textureGroup.addView(chip)
            chip.setOnClickListener {
                // Касание уже выбранной фактуры — это просьба построить узор
                // заново: фактура задаёт правило, а не одну картинку. Нажатие на
                // другую просто переключает, иначе выбор каждый раз выдавал бы
                // ещё и случайный пересев, и вернуться к увиденному было бы
                // нельзя.
                if (texture == current && texture != NovaAppearance.Texture.NONE) {
                    previewWith(texture = texture, variant = NovaAppearance.variant(this) + 1)
                } else {
                    previewWith(texture = texture)
                }
            }
        }
    }

    private fun setupTemperature() {
        val span = NovaAppearance.TEMPERATURE_MAX - NovaAppearance.TEMPERATURE_MIN
        temperatureBar.max = span
        val current = NovaAppearance.temperature(this)
        temperatureBar.progress = current - NovaAppearance.TEMPERATURE_MIN
        renderTemperature(current)
        temperatureBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) renderTemperature(progress + NovaAppearance.TEMPERATURE_MIN)
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit

            // Предпросмотр — на отпускании: пересоздавать экран на каждый шаг
            // ползунка значило бы сорок пересозданий на одно движение пальца.
            override fun onStopTrackingTouch(bar: SeekBar) {
                previewWith(temperature = bar.progress + NovaAppearance.TEMPERATURE_MIN)
            }
        })
    }

    private fun renderTemperature(value: Int) {
        temperatureValue.text = when {
            value == NovaAppearance.TEMPERATURE_NEUTRAL -> "нейтрально"
            value < 0 -> "теплее ${-value} %"
            else -> "холоднее $value %"
        }
    }

    /**
     * Показывает выбор, ничего не сохраняя.
     *
     * Незаданные здесь значения берутся из текущего предпросмотра, а не из
     * сохранённого: иначе выбор темы сбрасывал бы уже выбранную фактуру.
     */
    private fun previewWith(
        theme: String = currentThemeKey(),
        texture: NovaAppearance.Texture = NovaAppearance.texture(this),
        temperature: Int = NovaAppearance.temperature(this),
        variant: Int = NovaAppearance.variant(this),
    ) {
        NovaAppearance.preview(theme, texture, temperature, variant)
        recreate()
    }

    private fun applySelection() {
        val theme = currentThemeKey()
        val texture = NovaAppearance.texture(this)
        val temperature = NovaAppearance.temperature(this)
        val variant = NovaAppearance.variant(this)
        // `store` снимает предпросмотр сам: дальше действуют сохранённые значения,
        // и `onDestroy` снимать уже нечего.
        NovaAppearance.store(this, theme, texture, temperature, variant)
        applied = true
        LogManager.log(
            "Оформление: тема ${NovaTheme.optionFor(theme).title}, фактура ${texture.title}, " +
                "температура $temperature."
        )
        finish()
    }

}

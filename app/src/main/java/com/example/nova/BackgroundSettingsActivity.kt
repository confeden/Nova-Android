package com.example.nova

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class BackgroundSettingsActivity : AppCompatActivity() {
    private lateinit var clientData: ClientData

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LogManager.setAppContext(this)
        setContentView(R.layout.activity_background_settings)
        NovaFontHelper.apply(findViewById(android.R.id.content))

        clientData = ClientData(this)
        val rgMode = findViewById<RadioGroup>(R.id.rg_main_background_mode)
        val rbImage = findViewById<RadioButton>(R.id.rb_background_image)
        val rbAnimation = findViewById<RadioButton>(R.id.rb_background_animation)
        val rbNone = findViewById<RadioButton>(R.id.rb_background_none)
        val tvHint = findViewById<TextView>(R.id.tv_background_hint)
        val animationSupported = MainBackgroundPolicy.isAnimationSupported(this)

        if (!animationSupported) {
            rbAnimation.isEnabled = false
            rbAnimation.alpha = 0.45f
            tvHint.text = "Анимация недоступна на этом устройстве. Используется изображение."
            if (clientData.getMainBackgroundMode() == MainBackgroundPolicy.MODE_ANIMATION) {
                clientData.setMainBackgroundMode(MainBackgroundPolicy.MODE_IMAGE)
            }
        }

        when (clientData.getMainBackgroundMode()) {
            MainBackgroundPolicy.MODE_IMAGE -> rgMode.check(R.id.rb_background_image)
            MainBackgroundPolicy.MODE_NONE -> rgMode.check(R.id.rb_background_none)
            else -> rgMode.check(if (animationSupported) R.id.rb_background_animation else R.id.rb_background_image)
        }

        rgMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_background_image -> MainBackgroundPolicy.MODE_IMAGE
                R.id.rb_background_none -> MainBackgroundPolicy.MODE_NONE
                else -> MainBackgroundPolicy.MODE_ANIMATION
            }
            if (mode == MainBackgroundPolicy.MODE_ANIMATION && !animationSupported) {
                rgMode.check(R.id.rb_background_image)
                return@setOnCheckedChangeListener
            }
            clientData.setMainBackgroundMode(mode)
        }
    }
}

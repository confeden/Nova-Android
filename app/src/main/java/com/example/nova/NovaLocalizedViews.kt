package com.example.nova

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Switch
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.AppCompatRadioButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.radiobutton.MaterialRadioButton
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.theme.MaterialComponentsViewInflater

/**
 * Раздувает разметку нашими видами вместо штатных, чтобы любой `setText` проходил
 * через перевод ([NovaLanguage.onSetText]).
 *
 * Подключён темой (`viewInflaterClass` в `Theme.Nova` и `Theme.Nova.Settings`), а
 * создаётся AppCompat отражением — отсюда правило `-keep` в `proguard-rules.pro`.
 * Наследуется от инфлейтера Material, а не AppCompat: тема приложения — Material
 * Components, и виды обязаны остаться теми же `MaterialButton`/`MaterialTextView`,
 * иначе поменялся бы их вид.
 *
 * `EditText` не подменяется: введённый текст не переводится никогда, а подсказку
 * переводит обход дерева.
 */
class NovaViewInflater : MaterialComponentsViewInflater() {

    override fun createTextView(context: Context, attrs: AttributeSet?): AppCompatTextView =
        NovaTextView(context, attrs)

    override fun createButton(context: Context, attrs: AttributeSet): AppCompatButton =
        NovaButton(context, attrs)

    override fun createRadioButton(context: Context, attrs: AttributeSet?): AppCompatRadioButton =
        NovaRadioButton(context, attrs)

    override fun createCheckBox(context: Context, attrs: AttributeSet?): AppCompatCheckBox =
        NovaCheckBox(context, attrs)

    override fun createView(context: Context?, name: String?, attrs: AttributeSet?): View? = when (name) {
        null -> null
        "Switch" -> context?.let { NovaSwitch(it, attrs) }
        "androidx.appcompat.widget.SwitchCompat" -> context?.let { NovaSwitchCompat(it, attrs) }
        "androidx.appcompat.widget.AppCompatTextView" -> context?.let { NovaAppCompatTextView(it, attrs) }
        else -> null
    }
}

class NovaTextView(context: Context, attrs: AttributeSet?) :
    MaterialTextView(context, attrs), NovaLanguage.LocalizedText {
    override fun setText(text: CharSequence?, type: BufferType?) = NovaLanguage.onSetText(this, text, type)
    override fun setTextDirect(text: CharSequence?, type: BufferType?) = super.setText(text, type)
}

class NovaAppCompatTextView(context: Context, attrs: AttributeSet?) :
    AppCompatTextView(context, attrs), NovaLanguage.LocalizedText {
    override fun setText(text: CharSequence?, type: BufferType?) = NovaLanguage.onSetText(this, text, type)
    override fun setTextDirect(text: CharSequence?, type: BufferType?) = super.setText(text, type)
}

class NovaButton(context: Context, attrs: AttributeSet?) :
    MaterialButton(context, attrs), NovaLanguage.LocalizedText {
    override fun setText(text: CharSequence?, type: BufferType?) = NovaLanguage.onSetText(this, text, type)
    override fun setTextDirect(text: CharSequence?, type: BufferType?) = super.setText(text, type)
}

class NovaRadioButton(context: Context, attrs: AttributeSet?) :
    MaterialRadioButton(context, attrs), NovaLanguage.LocalizedText {
    override fun setText(text: CharSequence?, type: BufferType?) = NovaLanguage.onSetText(this, text, type)
    override fun setTextDirect(text: CharSequence?, type: BufferType?) = super.setText(text, type)
}

class NovaCheckBox(context: Context, attrs: AttributeSet?) :
    MaterialCheckBox(context, attrs), NovaLanguage.LocalizedText {
    override fun setText(text: CharSequence?, type: BufferType?) = NovaLanguage.onSetText(this, text, type)
    override fun setTextDirect(text: CharSequence?, type: BufferType?) = super.setText(text, type)
}

@Suppress("DEPRECATION")
class NovaSwitch(context: Context, attrs: AttributeSet?) :
    Switch(context, attrs), NovaLanguage.LocalizedText {
    override fun setText(text: CharSequence?, type: BufferType?) = NovaLanguage.onSetText(this, text, type)
    override fun setTextDirect(text: CharSequence?, type: BufferType?) = super.setText(text, type)
}

class NovaSwitchCompat(context: Context, attrs: AttributeSet?) :
    SwitchCompat(context, attrs), NovaLanguage.LocalizedText {
    override fun setText(text: CharSequence?, type: BufferType?) = NovaLanguage.onSetText(this, text, type)
    override fun setTextDirect(text: CharSequence?, type: BufferType?) = super.setText(text, type)
}

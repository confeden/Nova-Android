# -*- coding: utf-8 -*-
"""Генератор ресурсов оформления Nova: 64 оттеночные темы.

Что здесь было. Двенадцать готовых тем (Aurora, Proton, Matrix, Diablo II…), у каждой
своя палитра и свои шесть векторных фигур. Владелец убрал их все: оформление —
это один ползунок оттенка (как в ПК-версии), а яркость, насыщенность и скорость
погружения — отдельные ползунки, которые тему не меняют.

Что стало. Тема — это только **цвета**. Фигуры (фон окна, подложка строки, поле,
диалог, кнопки диалога) одни на всех и ссылаются на словарь темы через `?attr/…`,
поэтому шести файлов хватает на любое число оттенков. Оттенков 64, как на ПК:
шаг 5,6° глаз не различает, а ресурсов это стоит ~70 КБ XML.

Формулы палитры — ПК-версии (`nova_appearance._hue_theme`), с одной поправкой:
акцент у Android чуть насыщеннее (0.78 вместо 0.68) — владелец просил «немного
неоновый» бирюзовый по умолчанию.

Запуск (из корня репозитория):

    python tools/gen_nova_themes.py

Перезаписывает `app/src/main/res/drawable/bg_nova_*.xml`,
`values/colors_nova_themes.xml`, `values/themes_nova.xml` и
`app/src/main/java/com/example/nova/NovaHueThemes.kt`. Руками написанные
`values/attrs_nova_theme.xml` и `values/themes.xml` не трогает.
"""
import colorsys
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
KOTLIN = os.path.join(ROOT, "app", "src", "main", "java", "com", "example", "nova")

HUE_COUNT = 64
# 168.75° — бирюза между голубым и зелёным. Должен совпадать с NovaAppearance.DEFAULT_HUE.
DEFAULT_HUE = 30

HEAD = '<?xml version="1.0" encoding="utf-8"?>\n'
WARN = ("<!-- Сгенерировано tools/gen_nova_themes.py. Руками не править:\n"
        "     правка переживёт ровно до следующего запуска генератора. -->\n")


def hsl(hue, saturation, lightness, alpha=None):
    r, g, b = colorsys.hls_to_rgb(hue % 1.0, lightness, saturation)
    rgb = "%02X%02X%02X" % (round(r * 255), round(g * 255), round(b * 255))
    return "#" + (("%02X" % alpha) if alpha is not None else "") + rgb


def palette(index):
    hue = index / HUE_COUNT
    accent = hsl(hue, .78, .54)
    ground = (hsl(hue, .34, .045), hsl(hue, .34, .068), hsl(hue, .34, .098))
    return {
        "key": "hue%d" % index,
        "bg_top": ground[0], "bg_mid": ground[1], "bg_bot": ground[2],
        "accent": accent,
        "accent_dim": hsl(hue, .78, .54, 0x2E),
        "accent_on": hsl(hue, .40, .04),
        "card_fill": "#0BFFFFFF",
        "card_stroke": hsl(hue, .70, .70, 0x26),
        "separator": "#12FFFFFF",
        "group_from": accent,
        "group_to": hsl(hue - .07, .55, .78),
        "text_title": hsl(hue, .30, .95),
        "text_value": hsl(hue, .20, .74),
        "text_hint": hsl(hue, .14, .56),
        "switch_on": accent,
        "switch_off": "#2EFFFFFF",
        "thumb_on": "#FFFFFF",
        "thumb_off": hsl(hue, .16, .78),
        "icon_tint": hsl(hue, .78, .54, 0xB3),
        "field_fill": "#0BFFFFFF",
        "field_stroke": hsl(hue, .70, .70, 0x1F),
        "focus_fill": hsl(hue, .78, .54, 0x33),
        "focus_stroke": accent,
        "dialog_fill": ground[1],
    }


PALETTES = [palette(i) for i in range(HUE_COUNT)]

# (имя атрибута темы, ключ палитры). Фигуры читают значения только отсюда.
ATTR_MAP = [
    ("novaFocusFill", "focus_fill"), ("novaFocusStroke", "focus_stroke"),
    ("novaAccent", "accent"), ("novaAccentDim", "accent_dim"), ("novaAccentOn", "accent_on"),
    ("novaSeparator", "separator"),
    ("novaTextGroupFrom", "group_from"), ("novaTextGroupTo", "group_to"),
    ("novaTextTitle", "text_title"), ("novaTextValue", "text_value"),
    ("novaTextHint", "text_hint"),
    ("novaSwitchOn", "switch_on"), ("novaSwitchOff", "switch_off"),
    ("novaSwitchThumbOn", "thumb_on"), ("novaSwitchThumbOff", "thumb_off"),
    ("novaIconTint", "icon_tint"),
    ("novaCardFill", "card_fill"), ("novaCardStroke", "card_stroke"),
    ("novaFieldFill", "field_fill"), ("novaFieldStroke", "field_stroke"),
    ("novaScreenTop", "bg_top"), ("novaScreenMid", "bg_mid"), ("novaScreenBottom", "bg_bot"),
    ("novaDialogFill", "dialog_fill"),
]
COLOR_KEYS = [name for _, name in ATTR_MAP]


def write(path, text, base=RES):
    full = os.path.join(base, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with io.open(full, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


FOCUS = (
    '            <item android:state_%s="true">\n'
    '                <shape android:shape="rectangle">\n'
    '                    <solid android:color="?attr/novaFocusFill" />\n'
    '                    <corners android:radius="%s" />\n'
    '                    <stroke android:width="3dp" android:color="?attr/novaFocusStroke" />\n'
    '                </shape>\n'
    '            </item>\n'
)


def screen():
    return HEAD + WARN + (
        '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:shape="rectangle">\n'
        '    <gradient\n'
        '        android:angle="270"\n'
        '        android:startColor="?attr/novaScreenTop"\n'
        '        android:centerColor="?attr/novaScreenMid"\n'
        '        android:endColor="?attr/novaScreenBottom"\n'
        '        android:type="linear" />\n'
        '</shape>\n'
    )


def card():
    return HEAD + WARN + (
        '<ripple xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:color="?attr/novaFocusFill">\n'
        '    <item>\n'
        '        <selector>\n'
        + FOCUS % ("focused", "14dp")
        + FOCUS % ("pressed", "14dp") +
        '            <item>\n'
        '                <shape android:shape="rectangle">\n'
        '                    <solid android:color="?attr/novaCardFill" />\n'
        '                    <corners android:radius="14dp" />\n'
        '                    <stroke android:width="1dp" android:color="?attr/novaCardStroke" />\n'
        '                </shape>\n'
        '            </item>\n'
        '        </selector>\n'
        '    </item>\n'
        '</ripple>\n'
    )


def field():
    return HEAD + WARN + (
        '<selector xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <item android:state_focused="true">\n'
        '        <shape android:shape="rectangle">\n'
        '            <solid android:color="?attr/novaFocusFill" />\n'
        '            <corners android:radius="10dp" />\n'
        '            <stroke android:width="3dp" android:color="?attr/novaFocusStroke" />\n'
        '        </shape>\n'
        '    </item>\n'
        '    <item>\n'
        '        <shape android:shape="rectangle">\n'
        '            <solid android:color="?attr/novaFieldFill" />\n'
        '            <corners android:radius="10dp" />\n'
        '            <stroke android:width="1dp" android:color="?attr/novaFieldStroke" />\n'
        '        </shape>\n'
        '    </item>\n'
        '</selector>\n'
    )


def dialog():
    # Плотный цвет, а не вуаль карточки: диалог лежит в своём окне, и прозрачность
    # показала бы не фактуру, а список под диалогом.
    return HEAD + WARN + (
        '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:shape="rectangle">\n'
        '    <solid android:color="?attr/novaDialogFill" />\n'
        '    <corners android:radius="16dp" />\n'
        '    <stroke android:width="1dp" android:color="?attr/novaCardStroke" />\n'
        '</shape>\n'
    )


def dialog_button(primary):
    # У платформенного диалога кнопки безрамочные и читаются как часть текста;
    # рамка и заливка возвращают им форму. Фокус пульта явный: без рамки на
    # телевизоре непонятно, на чём стоишь.
    fill = "novaAccent" if primary else "novaAccentDim"
    return HEAD + WARN + (
        '<ripple xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:color="?attr/novaFocusFill">\n'
        '    <item>\n'
        '        <selector>\n'
        + FOCUS % ("focused", "10dp") +
        '            <item>\n'
        '                <shape android:shape="rectangle">\n'
        '                    <solid android:color="?attr/%s" />\n'
        '                    <corners android:radius="10dp" />\n'
        '                    <stroke android:width="1dp" android:color="?attr/novaAccent" />\n'
        '                </shape>\n'
        '            </item>\n'
        '        </selector>\n'
        '    </item>\n'
        '</ripple>\n' % fill
    )


def colors():
    out = [HEAD, WARN, "<resources>\n"]
    for i, p in enumerate(PALETTES):
        out.append("\n    <!-- Оттенок %d из %d -->\n" % (i, HUE_COUNT))
        for name in COLOR_KEYS:
            out.append('    <color name="nova_%s_%s">%s</color>\n' % (p["key"], name, p[name]))
    out.append("</resources>\n")
    return "".join(out)


def themes():
    out = [HEAD, WARN, '<resources xmlns:android="http://schemas.android.com/apk/res/android">\n']
    for i, p in enumerate(PALETTES):
        key = p["key"]
        style = "Hue%d" % i
        out.append('\n    <style name="Theme.Nova.Settings.%s" parent="Theme.Nova.Settings">\n' % style)
        # Диалоги в коде платформенные (`android.app.AlertDialog`): имя пишется
        # дважды, без префикса его читает AppCompat, с `android:` — фреймворк.
        out.append('        <item name="android:alertDialogTheme">@style/Theme.Nova.Dialog.%s</item>\n' % style)
        out.append('        <item name="alertDialogTheme">@style/Theme.Nova.Dialog.%s</item>\n' % style)
        for name in ("colorAccent", "android:colorAccent",
                     "colorControlActivated", "android:colorControlActivated"):
            out.append('        <item name="%s">@color/nova_%s_accent</item>\n' % (name, key))
        for name in ("colorControlNormal", "android:colorControlNormal"):
            out.append('        <item name="%s">@color/nova_%s_text_value</item>\n' % (name, key))
        out.append('        <item name="android:textColorHint">@color/nova_%s_text_hint</item>\n' % key)
        for attr, name in ATTR_MAP:
            out.append('        <item name="%s">@color/nova_%s_%s</item>\n' % (attr, key, name))
        out.append("    </style>\n")
    for i, p in enumerate(PALETTES):
        key = p["key"]
        style = "Hue%d" % i
        out.append('\n    <style name="Theme.Nova.Dialog.%s" parent="android:Theme.Material.Dialog.Alert">\n' % style)
        out.append('        <item name="android:windowBackground">@drawable/bg_nova_dialog</item>\n')
        out.append('        <item name="android:textColorPrimary">@color/nova_%s_text_title</item>\n' % key)
        out.append('        <item name="android:textColorSecondary">@color/nova_%s_text_value</item>\n' % key)
        out.append('        <item name="android:textColorAlertDialogListItem">@color/nova_%s_text_title</item>\n' % key)
        out.append('        <item name="android:colorAccent">@color/nova_%s_accent</item>\n' % key)
        out.append('        <item name="android:colorControlActivated">@color/nova_%s_accent</item>\n' % key)
        out.append('        <item name="android:colorControlNormal">@color/nova_%s_text_value</item>\n' % key)
        out.append('        <item name="android:windowMinWidthMajor">85%</item>\n')
        out.append('        <item name="android:windowMinWidthMinor">92%</item>\n')
        # Словарь темы повторяется у диалога целиком: его кнопки красятся кодом
        # (`NovaDialogs`), а код читает атрибуты у контекста диалога, не экрана.
        out.append('        <item name="novaDialogButton">@drawable/bg_nova_dialog_button</item>\n')
        out.append('        <item name="novaDialogButtonPrimary">@drawable/bg_nova_dialog_button_primary</item>\n')
        for attr, name in ATTR_MAP:
            out.append('        <item name="%s">@color/nova_%s_%s</item>\n' % (attr, key, name))
        out.append('        <item name="android:buttonBarPositiveButtonStyle">'
                   '@style/Widget.Nova.DialogButtonPrimary.%s</item>\n' % style)
        out.append('        <item name="android:buttonBarNegativeButtonStyle">'
                   '@style/Widget.Nova.DialogButton.%s</item>\n' % style)
        out.append('        <item name="android:buttonBarNeutralButtonStyle">'
                   '@style/Widget.Nova.DialogButton.%s</item>\n' % style)
        out.append("    </style>\n")
    for i, p in enumerate(PALETTES):
        key = p["key"]
        style = "Hue%d" % i
        out.append('\n    <style name="Widget.Nova.DialogButton.%s" '
                   'parent="android:Widget.Material.Button.Borderless">\n' % style)
        out.append('        <item name="android:background">@drawable/bg_nova_dialog_button</item>\n')
        # Оттенок задаётся явно: на Pixel платформенный стиль красит фон кнопки
        # динамическим акцентом системы, и без явного `src_in` подложка приезжает
        # цветом обоев. Проверено на Pixel 4a, Android 14.
        out.append('        <item name="android:backgroundTint">@color/nova_%s_accent</item>\n' % key)
        out.append('        <item name="android:backgroundTintMode">src_in</item>\n')
        out.append('        <item name="android:textColor">@color/nova_%s_accent</item>\n' % key)
        out.append('        <item name="android:textAllCaps">false</item>\n')
        out.append('        <item name="android:textSize">15sp</item>\n')
        out.append('        <item name="android:textStyle">bold</item>\n')
        # Ширина кнопки — по надписи, иначе `ButtonBarLayout` складывает три
        # кнопки в столбик и обратно уже не раскладывает (см. NovaDialogs.growToFitRow).
        out.append('        <item name="android:minWidth">48dp</item>\n')
        out.append('        <item name="android:minHeight">44dp</item>\n')
        out.append('        <item name="android:paddingLeft">10dp</item>\n')
        out.append('        <item name="android:paddingRight">10dp</item>\n')
        out.append('        <item name="android:layout_marginStart">8dp</item>\n')
        out.append("    </style>\n")
        out.append('\n    <style name="Widget.Nova.DialogButtonPrimary.%s" '
                   'parent="@style/Widget.Nova.DialogButton.%s">\n' % (style, style))
        out.append('        <item name="android:background">@drawable/bg_nova_dialog_button_primary</item>\n')
        out.append('        <item name="android:textColor">@color/nova_%s_accent_on</item>\n' % key)
        out.append("    </style>\n")
    out.append("</resources>\n")
    return "".join(out)


def kotlin():
    lines = [
        "package com.example.nova",
        "",
        "/**",
        " * Сгенерировано tools/gen_nova_themes.py. Руками не править.",
        " *",
        " * Темы оттенков по номеру. Таблица, а не `getIdentifier`: ссылки из кода видны",
        " * сжатию ресурсов, а имена по строке — нет, и R8 вырезал бы «неиспользуемые» темы.",
        " */",
        "internal object NovaHueThemes {",
        "    const val COUNT = %d" % HUE_COUNT,
        "",
        "    val SETTINGS: IntArray = intArrayOf(",
    ]
    lines += ["        R.style.Theme_Nova_Settings_Hue%d," % i for i in range(HUE_COUNT)]
    lines += ["    )", "", "    /** Акцент темы по номеру — тот же, что в `values/colors_nova_themes.xml`. */",
              "    val ACCENTS: IntArray = intArrayOf("]
    for p in PALETTES:
        lines.append("        0xFF%s.toInt()," % p["accent"].lstrip("#"))
    lines += ["    )", "}", ""]
    return "\n".join(lines)


def main():
    write("drawable/bg_nova_screen.xml", screen())
    write("drawable/bg_nova_card.xml", card())
    write("drawable/bg_nova_field.xml", field())
    write("drawable/bg_nova_dialog.xml", dialog())
    write("drawable/bg_nova_dialog_button.xml", dialog_button(False))
    write("drawable/bg_nova_dialog_button_primary.xml", dialog_button(True))
    write("values/colors_nova_themes.xml", colors())
    write("values/themes_nova.xml", themes())
    write("NovaHueThemes.kt", kotlin(), base=KOTLIN)
    print("hues: %d" % HUE_COUNT)


if __name__ == "__main__":
    main()

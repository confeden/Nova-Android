# -*- coding: utf-8 -*-
"""Генератор ресурсов тем оформления Nova.

Зачем генератор, а не семь файлов руками. Каждая тема — это один и тот же набор
XML-ресурсов, отличающийся только цветами: фон окна, подложка строки, поле ввода,
дорожка и бегунок переключателя. Переписывать их по семь раз означает семь шансов
разъехаться в состояниях фокуса пульта (`tv_focus_*`), а именно они молча ломаются
и обнаруживаются только на телевизоре.

Всё, что генератор выдаёт, — векторные `<shape>`/`<selector>`/`<layer-list>`.
Растра нет намеренно: тема обязана стоить приложению килобайты, а не мегабайты.

Запуск (из корня репозитория):

    python tools/gen_nova_themes.py

Перезаписывает `app/src/main/res/drawable/bg_nova_{screen,card,field}_*.xml`,
`app/src/main/res/values/colors_nova_themes.xml` и `values/themes_nova.xml`.
Файлы, написанные руками (`attrs_nova_theme.xml`, `themes.xml`), не трогает.
"""
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# Порядок здесь — порядок в списке выбора на экране. Он же порядок в
# NovaTheme.ORDER: два списка сверяются тестом, а не глазами.
THEMES = [
    dict(
        key="aurora", title="Aurora mint",
        bg=("#06051A", "#0E0B2E", "#160F3F"),
        accent="#2FD98E", accent_dim="#2E2FD98E", accent_on="#06051A",
        card_fill="#0BFFFFFF", card_stroke="#17FFFFFF", card_radius="14dp", card_stroke_w="1dp",
        sep="#12FFFFFF",
        group_from="#2FD98E", group_to="#B8F0D8",
        t_title="#EEF0F8", t_value="#A8ADD4", t_hint="#7A7FA8",
        switch_on="#2FD98E", switch_off="#2EFFFFFF", thumb_on="#FFFFFF", thumb_off="#B8BED0",
        icon="#B32FD98E",
        field_fill="#0BFFFFFF", field_stroke="#14FFFFFF",
    ),
    dict(
        key="dnsai", title="DNS-AI",
        bg=("#0D1117", "#0F141C", "#111823"),
        accent="#5B8CFF", accent_dim="#2E5B8CFF", accent_on="#0D1117",
        card_fill="#161B22", card_stroke="#12FFFFFF", card_radius="14dp", card_stroke_w="1dp",
        sep="#12FFFFFF",
        group_from="#EEF4FF", group_to="#7FE0BD",
        t_title="#E8ECF3", t_value="#A8B0BF", t_hint="#7B8494",
        switch_on="#5B8CFF", switch_off="#1C232D", thumb_on="#FFFFFF", thumb_off="#7B8494",
        icon="#B35B8CFF",
        field_fill="#0BFFFFFF", field_stroke="#21FFFFFF",
    ),
    dict(
        key="proton", title="Proton",
        bg=("#16141C", "#1A1824", "#1C1B24"),
        accent="#6D4AFF", accent_dim="#386D4AFF", accent_on="#FFFFFF",
        card_fill="#242230", card_stroke="#2E2B3C", card_radius="14dp", card_stroke_w="1dp",
        sep="#12FFFFFF",
        group_from="#8A6EFF", group_to="#EDEAF8",
        t_title="#EDEAF8", t_value="#B0ABCC", t_hint="#706B8A",
        switch_on="#6D4AFF", switch_off="#3A3844", thumb_on="#FFFFFF", thumb_off="#B0ABCC",
        icon="#8A6EFF",
        field_fill="#1C1A26", field_stroke="#2E2B3C",
    ),
    dict(
        key="charcoal", title="Charcoal birch",
        bg=("#08090C", "#0B0C10", "#0E1014"),
        accent="#4FD8C4", accent_dim="#294FD8C4", accent_on="#08090C",
        card_fill="#0BFFFFFF", card_stroke="#13FFFFFF", card_radius="16dp", card_stroke_w="1dp",
        sep="#12FFFFFF",
        group_from="#4FD8C4", group_to="#C9D2D8",
        t_title="#F2F4F7", t_value="#9AA3AE", t_hint="#7A8290",
        switch_on="#4FD8C4", switch_off="#26FFFFFF", thumb_on="#FFFFFF", thumb_off="#9AA3AE",
        icon="#8CFFFFFF",
        field_fill="#0BFFFFFF", field_stroke="#13FFFFFF",
    ),
    dict(
        key="graphene", title="Graphene golden",
        bg=("#101215", "#13161A", "#16191E"),
        accent="#F0B429", accent_dim="#26F0B429", accent_on="#101215",
        card_fill="#1B1F25", card_stroke="#12FFFFFF", card_radius="12dp", card_stroke_w="1dp",
        sep="#12FFFFFF",
        group_from="#F0B429", group_to="#F0E6D0",
        t_title="#E8EAF0", t_value="#9098A8", t_hint="#636B78",
        switch_on="#F0B429", switch_off="#353A44", thumb_on="#FFFFFF", thumb_off="#9098A8",
        icon="#9098A8",
        field_fill="#22262D", field_stroke="#14FFFFFF",
    ),
    dict(
        key="neon", title="Cyber neon",
        bg=("#0A0A12", "#0C0A16", "#0E0B1A"),
        accent="#39E6D6", accent_dim="#2E39E6D6", accent_on="#0A0A12",
        card_fill="#08FFFFFF", card_stroke="#3839E6D6", card_radius="12dp", card_stroke_w="1dp",
        sep="#1A39E6D6",
        group_from="#39E6D6", group_to="#FF5FBF",
        t_title="#D6D9E6", t_value="#8C93A8", t_hint="#5F6678",
        switch_on="#39E6D6", switch_off="#2A2A3A", thumb_on="#0A0A12", thumb_off="#8C93A8",
        icon="#39E6D6",
        field_fill="#0DFFFFFF", field_stroke="#2839E6D6",
    ),
    dict(
        key="poe1", title="Path of Exile 1",
        bg=("#0A0705", "#14100B", "#1A150E"),
        accent="#C8AA6E", accent_dim="#2EC8AA6E", accent_on="#0A0705",
        # Панели PoE — прямоугольные. Радиус 4dp здесь не «почти как у всех»,
        # а именно то, по чему тема узнаётся; скругление в 14dp её убивает.
        card_fill="#1E1810", card_stroke="#8B7343", card_radius="4dp", card_stroke_w="1dp",
        sep="#2EC8AA6E",
        group_from="#E8DCC0", group_to="#C8AA6E",
        t_title="#C9BFA8", t_value="#8A8073", t_hint="#6B6254",
        switch_on="#9B2C2C", switch_off="#2A2018", thumb_on="#E8DCC0", thumb_off="#8A8073",
        icon="#BFC8AA6E",
        field_fill="#17110A", field_stroke="#6E5A33",
    ),
    dict(
        key="poe2", title="Path of Exile 2",
        bg=("#0E1012", "#121517", "#16191C"),
        accent="#C86A32", accent_dim="#2EC86A32", accent_on="#FFF3EA",
        # Кованая плита: панель светлее сверху. Это весь приём темы целиком —
        # сплошная заливка убирает её узнаваемость, поэтому здесь кортеж.
        card_fill=("#0EFFFFFF", "#05FFFFFF"),
        card_stroke="#21BEC8D0", card_radius="2dp", card_stroke_w="1dp",
        sep="#80000000",
        group_from="#E68A4E", group_to="#AEB8C0",
        t_title="#CBD2D8", t_value="#8E979F", t_hint="#666E75",
        switch_on="#C86A32", switch_off="#2A2E32", thumb_on="#FFFFFF", thumb_off="#8E979F",
        icon="#8E979F",
        field_fill="#0DFFFFFF", field_stroke="#21BEC8D0",
    ),
    dict(
        key="gta6sunset", title="GTA VI Vice Sunset",
        # Значения сняты с официального сайта Rockstar: фон — их же панельный
        # градиент 189°, карточка — «приподнятый» набор, который там лежит выше.
        bg=("#1F1F38", "#18182D", "#0C0D1B"),
        accent="#E9639B", accent_dim="#2EE9639B", accent_on="#2A0F1C",
        card_fill=("#38365D", "#2A2947"),
        card_stroke="#1FCCC2F5", card_radius="12dp", card_stroke_w="1dp",
        sep="#14CCC2F5",
        # Фирменный градиент персик→розовый. Он и есть марка, поэтому живёт только
        # в заголовках и во включённом переключателе — обоями его делать нельзя.
        group_from="#FEC497", group_to="#E9639B",
        t_title="#E5DDFF", t_value="#B0A6DE", t_hint="#8B84B8",
        switch_on="#E9639B", switch_off="#47435F", thumb_on="#FFFFFF", thumb_off="#CCC2F5",
        icon="#CCC2F5",
        field_fill="#14CCC2F5", field_stroke="#1FCCC2F5",
    ),
    dict(
        key="gta6night", title="GTA VI Vice Night",
        bg=("#07080F", "#0A0B15", "#0C0D1B"),
        accent="#E9639B", accent_dim="#2EE9639B", accent_on="#2A0F1C",
        card_fill="#0AD9F3FE",
        card_stroke="#29E9639B", card_radius="10dp", card_stroke_w="1dp",
        sep="#17E9639B",
        group_from="#E9639B", group_to="#D9F3FE",
        t_title="#E8EAF4", t_value="#9FB4C4", t_hint="#5F6A80",
        switch_on="#E9639B", switch_off="#3A4152", thumb_on="#FFFFFF", thumb_off="#9FB4C4",
        icon="#9FB4C4",
        field_fill="#0FD9F3FE", field_stroke="#1FD9F3FE",
    ),
]

HEAD = '<?xml version="1.0" encoding="utf-8"?>\n'
WARN = ("<!-- Сгенерировано tools/gen_nova_themes.py. Руками не править:\n"
        "     правка переживёт ровно до следующего запуска генератора. -->\n")


def write(path, text):
    full = os.path.join(RES, path)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with io.open(full, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(text)


def screen(t):
    """Фон окна: три стопа по вертикали — ровно то, что умеет <gradient>."""
    return HEAD + WARN + (
        '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:shape="rectangle">\n'
        '    <gradient\n'
        '        android:angle="270"\n'
        '        android:startColor="%s"\n'
        '        android:centerColor="%s"\n'
        '        android:endColor="%s"\n'
        '        android:type="linear" />\n'
        '</shape>\n' % t["bg"]
    )


def card_fill_stops(t):
    """Цвета заливки карточки: один — сплошная, два-три — градиент.

    Кортеж вместо строки нужен двум темам. «Руда» держится на том, что панель
    светлее сверху, — это её вид кованой плиты, и сплошная заливка убирает его
    целиком. У GTA VI карточка приподнята над фоном тем же приёмом. Больше трёх
    стопов `<gradient>` не умеет, поэтому больше и не разрешаем.
    """
    fill = t["card_fill"]
    stops = (fill,) if isinstance(fill, str) else tuple(fill)
    assert 1 <= len(stops) <= 3, "%s: у заливки %d стопов" % (t["key"], len(stops))
    return stops


def fill(t):
    """XML заливки: `<solid>` или `<gradient>` сверху вниз."""
    stops = card_fill_stops(t)
    if len(stops) == 1:
        return '                    <solid android:color="%s" />\n' % stops[0]
    center = ('\n                        android:centerColor="%s"' % stops[1]) if len(stops) == 3 else ""
    return (
        '                    <gradient\n'
        '                        android:angle="270"\n'
        '                        android:startColor="%s"%s\n'
        '                        android:endColor="%s"\n'
        '                        android:type="linear" />\n' % (stops[0], center, stops[-1])
    )


def card(t):
    """Подложка строки настроек.

    Состояния фокуса пульта повторяются в каждой теме дословно и намеренно: без
    явной рамки на телевизоре непонятно, на чём стоишь, а различать только цветом
    нельзя (дальтонизм, плохая матрица). Поэтому рамка вдвое толще обычной и
    подложка светлеет — как в исходном bg_settings_item.
    """
    focus = (
        '            <item android:state_%s="true">\n'
        '                <shape android:shape="rectangle">\n'
        '                    <solid android:color="@color/tv_focus_fill" />\n'
        '                    <corners android:radius="%s" />\n'
        '                    <stroke android:width="3dp" android:color="@color/tv_focus_stroke" />\n'
        '                </shape>\n'
        '            </item>\n'
    )
    return HEAD + WARN + (
        '<ripple xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:color="#20FFFFFF">\n'
        '    <item>\n'
        '        <selector>\n'
        + focus % ("focused", t["card_radius"])
        + focus % ("pressed", t["card_radius"]) +
        '            <item>\n'
        '                <shape android:shape="rectangle">\n'
        + fill(t) +
        '                    <corners android:radius="%s" />\n'
        '                    <stroke android:width="%s" android:color="%s" />\n'
        '                </shape>\n'
        '            </item>\n'
        '        </selector>\n'
        '    </item>\n'
        '</ripple>\n' % (t["card_radius"], t["card_stroke_w"], t["card_stroke"])
    )


def field(t):
    return HEAD + WARN + (
        '<selector xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <item android:state_focused="true">\n'
        '        <shape android:shape="rectangle">\n'
        '            <solid android:color="@color/tv_focus_fill" />\n'
        '            <corners android:radius="10dp" />\n'
        '            <stroke android:width="3dp" android:color="@color/tv_focus_stroke" />\n'
        '        </shape>\n'
        '    </item>\n'
        '    <item>\n'
        '        <shape android:shape="rectangle">\n'
        '            <solid android:color="%s" />\n'
        '            <corners android:radius="10dp" />\n'
        '            <stroke android:width="1dp" android:color="%s" />\n'
        '        </shape>\n'
        '    </item>\n'
        '</selector>\n' % (t["field_fill"], t["field_stroke"])
    )


def dialog(t):
    """Фон диалога.

    Отдельным ресурсом, а не подложкой строки: у части тем `card_fill`
    полупрозрачный (`#0BFFFFFF` и подобные), и диалог на таком фоне читался бы
    насквозь. Здесь нужен плотный цвет — берём средний стоп фона экрана.
    """
    return HEAD + WARN + (
        '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:shape="rectangle">\n'
        '    <solid android:color="%s" />\n'
        '    <corners android:radius="16dp" />\n'
        '    <stroke android:width="1dp" android:color="%s" />\n'
        '</shape>\n' % (t["bg"][1], t["card_stroke"])
    )


def dialog_button(t, primary):
    """Подложка кнопки диалога.

    Зачем она вообще. У платформенного `Theme.Material.Dialog.Alert` кнопки
    безрамочные, и весь их вид — это надпись цветом `colorAccent`. На тёмной
    подложке диалога такая надпись читается как часть текста, а не как кнопка:
    владелец сообщил, что «Закрыть» почти не видно. Рамка и заливка возвращают
    кнопке форму, и это не зависит от того, насколько ярок акцент темы.

    Состояние фокуса пульта повторяется здесь по той же причине, что и у
    подложки строки: без явной рамки на телевизоре непонятно, на чём стоишь.
    """
    fill = t["accent"] if primary else t["accent_dim"]
    return HEAD + WARN + (
        '<ripple xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:color="#33FFFFFF">\n'
        '    <item>\n'
        '        <selector>\n'
        '            <item android:state_focused="true">\n'
        '                <shape android:shape="rectangle">\n'
        '                    <solid android:color="@color/tv_focus_fill" />\n'
        '                    <corners android:radius="10dp" />\n'
        '                    <stroke android:width="3dp" android:color="@color/tv_focus_stroke" />\n'
        '                </shape>\n'
        '            </item>\n'
        '            <item>\n'
        '                <shape android:shape="rectangle">\n'
        '                    <solid android:color="%s" />\n'
        '                    <corners android:radius="10dp" />\n'
        '                    <stroke android:width="1dp" android:color="%s" />\n'
        '                </shape>\n'
        '            </item>\n'
        '        </selector>\n'
        '    </item>\n'
        '</ripple>\n' % (fill, t["accent"])
    )


COLOR_KEYS = [
    ("accent", "accent"), ("accent_dim", "accent_dim"), ("accent_on", "accent_on"),
    ("card_fill", "card_fill"), ("card_stroke", "card_stroke"), ("sep", "separator"),
    ("group_from", "group_from"), ("group_to", "group_to"),
    ("t_title", "text_title"), ("t_value", "text_value"), ("t_hint", "text_hint"),
    ("switch_on", "switch_on"), ("switch_off", "switch_off"),
    ("thumb_on", "thumb_on"), ("thumb_off", "thumb_off"),
    ("icon", "icon_tint"), ("field_fill", "field_fill"), ("field_stroke", "field_stroke"),
]


def colors():
    out = [HEAD, WARN, "<resources>\n"]
    for t in THEMES:
        out.append("\n    <!-- %s -->\n" % t["title"])
        for i, c in enumerate(("bg_top", "bg_mid", "bg_bot")):
            out.append('    <color name="nova_%s_%s">%s</color>\n' % (t["key"], c, t["bg"][i]))
        for src, name in COLOR_KEYS:
            # `novaCardFill` — один цвет, а заливка карточки бывает градиентом.
            # Берём верхний стоп: атрибут нужен там, где рисуется плоская подложка
            # (шапка, поле), и градиент туда не поместится.
            value = card_fill_stops(t)[0] if src == "card_fill" else t[src]
            out.append('    <color name="nova_%s_%s">%s</color>\n' % (t["key"], name, value))
    out.append("</resources>\n")
    return "".join(out)


ATTR_MAP = [
    ("novaAccent", "accent"), ("novaAccentDim", "accent_dim"), ("novaAccentOn", "accent_on"),
    ("novaSeparator", "separator"),
    ("novaTextGroupFrom", "group_from"), ("novaTextGroupTo", "group_to"),
    ("novaTextTitle", "text_title"), ("novaTextValue", "text_value"),
    ("novaTextHint", "text_hint"),
    ("novaSwitchOn", "switch_on"), ("novaSwitchOff", "switch_off"),
    ("novaSwitchThumbOn", "thumb_on"), ("novaSwitchThumbOff", "thumb_off"),
    ("novaIconTint", "icon_tint"),
    ("novaCardFill", "card_fill"), ("novaCardStroke", "card_stroke"),
]


def themes():
    out = [HEAD, WARN, '<resources xmlns:android="http://schemas.android.com/apk/res/android">\n']
    for t in THEMES:
        style = "".join(t["title"].replace("-", " ").split())
        out.append('\n    <style name="Theme.Nova.Settings.%s" parent="Theme.Nova.Settings">\n' % style)
        out.append('        <item name="android:windowBackground">@drawable/bg_nova_screen_%s</item>\n' % t["key"])
        out.append('        <item name="novaCardBackground">@drawable/bg_nova_card_%s</item>\n' % t["key"])
        out.append('        <item name="novaFieldBackground">@drawable/bg_nova_field_%s</item>\n' % t["key"])
        # Диалоги в коде — платформенные (`android.app.AlertDialog`), значит и тема
        # им нужна платформенная. Имя пишется дважды: без префикса его читает
        # AppCompat, с `android:` — фреймворк, и один без другого оставляет часть
        # диалогов белыми на чёрном экране.
        out.append('        <item name="android:alertDialogTheme">@style/Theme.Nova.Dialog.%s</item>\n' % style)
        out.append('        <item name="alertDialogTheme">@style/Theme.Nova.Dialog.%s</item>\n' % style)
        # Кружок радиокнопки и галочка флажка красятся не своим атрибутом, а общим
        # `colorAccent` темы. Пока он не задан, они берут умолчание библиотеки —
        # ярко-зелёное, — и на золотой теме это единственное пятно чужого цвета на
        # экране. Оба пространства имён нужны: платформенный `RadioButton` читает
        # `android:*`, AppCompat — без префикса.
        for name in ("colorAccent", "android:colorAccent",
                     "colorControlActivated", "android:colorControlActivated"):
            out.append('        <item name="%s">@color/nova_%s_accent</item>\n' % (name, t["key"]))
        for name in ("colorControlNormal", "android:colorControlNormal"):
            out.append('        <item name="%s">@color/nova_%s_text_value</item>\n' % (name, t["key"]))
        out.append('        <item name="android:textColorHint">@color/nova_%s_text_hint</item>\n' % t["key"])
        for attr, name in ATTR_MAP:
            out.append('        <item name="%s">@color/nova_%s_%s</item>\n' % (attr, t["key"], name))
        out.append("    </style>\n")
    for t in THEMES:
        style = "".join(t["title"].replace("-", " ").split())
        key = t["key"]
        out.append('\n    <style name="Theme.Nova.Dialog.%s" parent="android:Theme.Material.Dialog.Alert">\n' % style)
        out.append('        <item name="android:windowBackground">@drawable/bg_nova_dialog_%s</item>\n' % key)
        out.append('        <item name="android:textColorPrimary">@color/nova_%s_text_title</item>\n' % key)
        out.append('        <item name="android:textColorSecondary">@color/nova_%s_text_value</item>\n' % key)
        out.append('        <item name="android:textColorAlertDialogListItem">@color/nova_%s_text_title</item>\n' % key)
        out.append('        <item name="android:colorAccent">@color/nova_%s_accent</item>\n' % key)
        out.append('        <item name="android:colorControlActivated">@color/nova_%s_accent</item>\n' % key)
        out.append('        <item name="android:colorControlNormal">@color/nova_%s_text_value</item>\n' % key)
        out.append('        <item name="android:windowMinWidthMajor">85%</item>\n')
        out.append('        <item name="android:windowMinWidthMinor">92%</item>\n')
        # Словарь темы повторяется у диалога целиком: его кнопки красятся кодом
        # (`NovaDialogs`), а код читает атрибуты у контекста **диалога**, не
        # экрана. Без этих строк там оказались бы нули.
        out.append('        <item name="novaDialogButton">@drawable/bg_nova_dialog_button_%s</item>\n' % key)
        out.append('        <item name="novaDialogButtonPrimary">'
                   '@drawable/bg_nova_dialog_button_primary_%s</item>\n' % key)
        for attr, name in ATTR_MAP:
            out.append('        <item name="%s">@color/nova_%s_%s</item>\n' % (attr, key, name))
        # Кнопки диалога — со своей подложкой: у платформенных они безрамочные и
        # выглядят строкой текста (см. dialog_button). Имена читает разметка
        # фреймворка `alert_dialog_material.xml`, поэтому пространство `android:`.
        out.append('        <item name="android:buttonBarPositiveButtonStyle">'
                   '@style/Widget.Nova.DialogButtonPrimary.%s</item>\n' % style)
        out.append('        <item name="android:buttonBarNegativeButtonStyle">'
                   '@style/Widget.Nova.DialogButton.%s</item>\n' % style)
        out.append('        <item name="android:buttonBarNeutralButtonStyle">'
                   '@style/Widget.Nova.DialogButton.%s</item>\n' % style)
        out.append("    </style>\n")
    for t in THEMES:
        style = "".join(t["title"].replace("-", " ").split())
        key = t["key"]
        # Родитель задан явно, поэтому неявный родитель по точкам в имени
        # (`Widget.Nova.DialogButton`, которого нет) не ищется — так же устроены
        # темы диалогов выше.
        out.append('\n    <style name="Widget.Nova.DialogButton.%s" '
                   'parent="android:Widget.Material.Button.Borderless">\n' % style)
        out.append('        <item name="android:background">@drawable/bg_nova_dialog_button_%s</item>\n' % key)
        # Оттенок задаётся явно, и это не украшение. На Pixel платформенный
        # стиль кнопки диалога красит её фон динамическим акцентом системы —
        # цветом обоев, — и наша подложка приезжала сиреневой поверх мятной
        # темы. Снять оттенок через `@null` нельзя: `View` читает атрибут через
        # `hasValue`, а `@null` для него — «не задано», то есть остаётся
        # унаследованный. Поэтому оттенок перебивается своим цветом; `src_in`
        # сохраняет прозрачность фигуры, так что обводка и полупрозрачная
        # заливка остаются на месте. Проверено на Pixel 4a, Android 14.
        out.append('        <item name="android:backgroundTint">@color/nova_%s_accent</item>\n' % key)
        out.append('        <item name="android:backgroundTintMode">src_in</item>\n')
        out.append('        <item name="android:textColor">@color/nova_%s_accent</item>\n' % key)
        out.append('        <item name="android:textAllCaps">false</item>\n')
        out.append('        <item name="android:textSize">15sp</item>\n')
        out.append('        <item name="android:textStyle">bold</item>\n')
        out.append('        <item name="android:minWidth">96dp</item>\n')
        out.append('        <item name="android:minHeight">44dp</item>\n')
        out.append('        <item name="android:paddingLeft">18dp</item>\n')
        out.append('        <item name="android:paddingRight">18dp</item>\n')
        out.append('        <item name="android:layout_marginStart">8dp</item>\n')
        out.append('        <item name="android:layout_marginBottom">4dp</item>\n')
        out.append("    </style>\n")
        out.append('\n    <style name="Widget.Nova.DialogButtonPrimary.%s" '
                   'parent="@style/Widget.Nova.DialogButton.%s">\n' % (style, style))
        out.append('        <item name="android:background">'
                   '@drawable/bg_nova_dialog_button_primary_%s</item>\n' % key)
        out.append('        <item name="android:textColor">@color/nova_%s_accent_on</item>\n' % key)
        out.append("    </style>\n")
    out.append("</resources>\n")
    return "".join(out)


def main():
    for t in THEMES:
        write("drawable/bg_nova_screen_%s.xml" % t["key"], screen(t))
        write("drawable/bg_nova_card_%s.xml" % t["key"], card(t))
        write("drawable/bg_nova_field_%s.xml" % t["key"], field(t))
        write("drawable/bg_nova_dialog_%s.xml" % t["key"], dialog(t))
        write("drawable/bg_nova_dialog_button_%s.xml" % t["key"], dialog_button(t, False))
        write("drawable/bg_nova_dialog_button_primary_%s.xml" % t["key"], dialog_button(t, True))
    write("values/colors_nova_themes.xml", colors())
    write("values/themes_nova.xml", themes())
    print("themes: %d, files: %d" % (len(THEMES), len(THEMES) * 6 + 2))


if __name__ == "__main__":
    main()

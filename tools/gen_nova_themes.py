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
import math
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")

# Порядок здесь — порядок в списке выбора на экране. Он же порядок в
# NovaTheme.ORDER: два списка сверяются тестом, а не глазами.
THEMES = [
    # Aurora mint — тема по умолчанию, и палитра у неё взята с дотемного экрана
    # 1.32.0, а не придумана заново.
    #
    # Первая её версия светила мятой `#2FD98E` поверх фиолетово-чёрного фона и
    # уходила в почти белый `#B8F0D8` в заголовках групп: на глаз это неон, а не
    # то спокойное оформление, которым приложение выглядело до тем. Прежние
    # значения никуда не делись — они лежат в `values/colors.xml` как «liquid»
    # палитра и до сих пор стоят литералами на главном экране:
    # фон `#090817/#120D27/#1B1238`, изумруд `#50C878` у IP и версии,
    # сиреневая обводка карточки `#40A88DFF`. Сюда возвращены именно они.
    #
    # Изумруд и мята — это насыщенность, а не оттенок: `#2FD98E` — S 78 %,
    # `#50C878` — S 60 % при той же светлоте. Отсюда и «менее неоново».
    dict(
        key="aurora", title="Aurora mint",
        bg=("#090817", "#120D27", "#1B1238"),
        accent="#50C878", accent_dim="#2E50C878", accent_on="#090817",
        card_fill="#0BFFFFFF", card_stroke="#1FA88DFF", card_radius="14dp", card_stroke_w="1dp",
        sep="#12FFFFFF",
        group_from="#50C878", group_to="#9FD9BC",
        t_title="#EEF0F8", t_value="#A8ADD4", t_hint="#7A7FA8",
        switch_on="#50C878", switch_off="#2EFFFFFF", thumb_on="#FFFFFF", thumb_off="#B8BED0",
        icon="#B350C878",
        field_fill="#0BFFFFFF", field_stroke="#18A88DFF",
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
    # Matrix — единственная тема, которая изображает не палитру, а устройство:
    # люминофорный терминал.
    #
    # Отсюда три отличия от всех остальных. Фон — настоящий чёрный с зеленоватым
    # подсветом снизу, а не тёмно-серый: у ЭЛТ невыведенный пиксель не светится
    # вовсе. Углы прямые (`card_radius` 2dp): скруглений у текстового терминала
    # нет. Акцент — `#00FF41`, тот самый люминофор P1; он ярче, чем допустимо в
    # любой другой теме, и здесь это цель, а не недосмотр.
    #
    # Шрифт у этой темы моноширинный, но задаётся он не здесь: тема — это цвета и
    # фигуры, а начертание ставит `NovaFontHelper` по признаку `monospace` в
    # `NovaTheme.ORDER`.
    dict(
        key="matrix", title="Matrix",
        bg=("#000000", "#010703", "#031008"),
        accent="#00FF41", accent_dim="#2E00FF41", accent_on="#001204",
        card_fill="#0A1A0E",
        card_stroke="#2600FF41", card_radius="0dp", card_stroke_w="1dp",
        field_radius="0dp", btn_radius="0dp", dialog_radius="0dp",
        sep="#1A00FF41",
        group_from="#00FF41", group_to="#9DFFB8",
        t_title="#8FFFA8", t_value="#41C267", t_hint="#2E8A49",
        switch_on="#00FF41", switch_off="#16301E", thumb_on="#001204", thumb_off="#4E7A5B",
        icon="#B300FF41",
        field_fill="#07120A", field_stroke="#2200FF41",
    ),
    # Diablo II — единственная тема, у которой кнопка не «прямоугольник с
    # обводкой», а нарисованная вещь: каменная плита в золотой филиграни с
    # самоцветом на каждом торце. Поэтому у неё стоит `button_style`, и
    # `dialog_button` для неё идёт другой веткой.
    #
    # Растра по-прежнему ноль: плита — это `layer-list` из четырёх слоёв
    # (тёмная рамка, золотая филигрань, градиент камня, два самоцвета-овала),
    # то есть полторы сотни байт XML вместо девяти PNG на плотность.
    dict(
        key="diablo2", title="Diablo II",
        bg=("#0B0907", "#100D0A", "#15110C"),
        accent="#B79A5B", accent_dim="#2EB79A5B", accent_on="#EDE4CC",
        card_fill=("#302C26", "#1E1B17"),
        card_stroke="#8B7343", card_radius="2dp", card_stroke_w="1dp",
        sep="#2E8B7343",
        group_from="#E4D3A0", group_to="#8B7343",
        t_title="#D8D2C0", t_value="#9A9384", t_hint="#6E6A5E",
        # Красный на включённом переключателе — тот же приём, что у Path of Exile 1:
        # в обеих играх «активно» показывают кровью, а не золотом.
        switch_on="#8E1B1B", switch_off="#2A2620", thumb_on="#E8DCC0", thumb_off="#9A9384",
        icon="#BFB79A5B",
        field_fill="#1A1712", field_stroke="#6E5A33",
        button_style="diablo2", card_style="diablo2",
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


def diablo_card(t):
    """Подложка строки Diablo II: панель игрового окна, а не прямоугольник.

    В самой игре любая панель — это тёмное поле в кованой раме: снаружи чёрный
    кант, за ним золотая полоса с бликом сверху и тенью снизу, за ней тонкая
    тёмная линия, и только потом поле. Углы отмечены гвоздями-накладками.

    Почему рама собрана из колец и полос, а не из вложенных прямоугольников.
    Раньше каждый слой заливался целиком, и верхний просто закрывал нижний. Для
    глаза разницы нет — видна только рамка, — но каждый такой слой непрозрачен по
    всей площади карточки, и фактура поверхности (она лежит в фоне окна) не
    доходила до поля вообще. Теперь кант и внутренняя линия — это `<stroke>` без
    заливки, а филигрань — четыре полосы по краям: середину карточки не трогает
    никто, и сквозь полупрозрачное поле видно фактуру.

    Полосы обходятся `android:gravity` у `<item>`: у `<shape>` нет собственного
    размера, поэтому LayerDrawable сам дотягивает полосу по второй оси
    (`resolveGravity` дописывает `FILL_*`, когда размер не задан). Светлая полоса
    сверху и тёмная снизу — это и есть кованый скос; боковые несут переход между
    ними. Порядок важен: боковые идут первыми, верхняя и нижняя кладутся поверх,
    поэтому углы достаются им.

    Всё это по-прежнему без растра: девять `<shape>` на строку.

    Состояния фокуса и нажатия идут первыми в `<selector>`, как у обычной
    подложки: без явной рамки на телевизоре непонятно, на чём стоишь.
    """
    stops = card_fill_stops(t)
    field_top = stops[0]
    field_bottom = stops[-1]

    stud = """                    <item android:width="7dp" android:height="7dp"
                        android:gravity="%s" android:left="2dp" android:top="2dp"
                        android:right="2dp" android:bottom="2dp">
                        <shape android:shape="rectangle">
                            <solid android:color="#D8BC72" />
                            <stroke android:width="1dp" android:color="#4A3C1E" />
                        </shape>
                    </item>
"""
    studs = "".join(
        stud % g
        for g in ("top|left", "top|right", "bottom|left", "bottom|right")
    )

    # Боковая полоса филиграни: по ней идёт весь переход от блика к тени.
    side = """                    <item android:width="3dp" android:gravity="%s"
                        android:left="1dp" android:top="1dp"
                        android:right="1dp" android:bottom="1dp">
                        <shape android:shape="rectangle">
                            <gradient android:angle="270"
                                android:startColor="#D3B570" android:centerColor="#8B7343"
                                android:endColor="#53421F" />
                        </shape>
                    </item>
"""
    # Верхняя и нижняя — сплошные: на трёх точках высоты градиент не читается,
    # а блик сверху и тень снизу читаются сразу.
    cap = """                    <item android:height="3dp" android:gravity="%s"
                        android:left="1dp" android:top="1dp"
                        android:right="1dp" android:bottom="1dp">
                        <shape android:shape="rectangle">
                            <solid android:color="%s" />
                        </shape>
                    </item>
"""

    return HEAD + WARN + ("""<ripple xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="?attr/novaFocusFill">
    <item>
        <selector>
            <item android:state_focused="true">
                <shape android:shape="rectangle">
                    <solid android:color="?attr/novaFocusFill" />
                    <stroke android:width="3dp" android:color="?attr/novaFocusStroke" />
                </shape>
            </item>
            <item android:state_pressed="true">
                <shape android:shape="rectangle">
                    <solid android:color="?attr/novaFocusFill" />
                    <stroke android:width="3dp" android:color="?attr/novaFocusStroke" />
                </shape>
            </item>
            <item>
                <layer-list>
                    <item android:left="5dp" android:top="5dp"
                        android:right="5dp" android:bottom="5dp">
                        <shape android:shape="rectangle">
                            <gradient android:angle="270"
                                android:startColor="%s" android:endColor="%s" />
                        </shape>
                    </item>
                    <item>
                        <shape android:shape="rectangle">
                            <solid android:color="@android:color/transparent" />
                            <stroke android:width="1dp" android:color="#0A0806" />
                        </shape>
                    </item>
%s%s%s%s                    <item android:left="4dp" android:top="4dp"
                        android:right="4dp" android:bottom="4dp">
                        <shape android:shape="rectangle">
                            <solid android:color="@android:color/transparent" />
                            <stroke android:width="1dp" android:color="#241D12" />
                        </shape>
                    </item>
%s                </layer-list>
            </item>
        </selector>
    </item>
</ripple>
""" % (
        field_top, field_bottom,
        side % "left", side % "right",
        cap % ("top", "#D3B570"), cap % ("bottom", "#53421F"),
        studs,
    ))


def card(t):
    """Подложка строки настроек.

    Состояния фокуса пульта повторяются в каждой теме дословно и намеренно: без
    явной рамки на телевизоре непонятно, на чём стоишь, а различать только цветом
    нельзя (дальтонизм, плохая матрица). Поэтому рамка вдвое толще обычной и
    подложка светлеет — как в исходном bg_settings_item.
    """
    if t.get("card_style") == "diablo2":
        return diablo_card(t)
    focus = (
        '            <item android:state_%s="true">\n'
        '                <shape android:shape="rectangle">\n'
        '                    <solid android:color="?attr/novaFocusFill" />\n'
        '                    <corners android:radius="%s" />\n'
        '                    <stroke android:width="3dp" android:color="?attr/novaFocusStroke" />\n'
        '                </shape>\n'
        '            </item>\n'
    )
    return HEAD + WARN + (
        '<ripple xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:color="?attr/novaFocusFill">\n'
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
    # Радиус поля, кнопки и диалога темы задают редко — 10dp и 16dp подходят
    # девяти темам из двенадцати. Значение по умолчанию здесь, чтобы тема
    # объявляла только то, чем отличается: Matrix — нулём, у терминала
    # скруглений нет вовсе.
    r = t.get("field_radius", "10dp")
    # Радиус поля, кнопки и диалога темы задают редко: 10dp и 16dp подходят
    # одиннадцати темам из двенадцати. Значение по умолчанию здесь, чтобы тема
    # объявляла только то, чем отличается, — Matrix объявляет ноль: у терминала
    # скруглений нет вовсе.
    r = t.get("field_radius", "10dp")
    return HEAD + WARN + (
        '<selector xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <item android:state_focused="true">\n'
        '        <shape android:shape="rectangle">\n'
        '            <solid android:color="?attr/novaFocusFill" />\n'
        '            <corners android:radius="%s" />\n'
        '            <stroke android:width="3dp" android:color="?attr/novaFocusStroke" />\n'
        '        </shape>\n'
        '    </item>\n'
        '    <item>\n'
        '        <shape android:shape="rectangle">\n'
        '            <solid android:color="%s" />\n'
        '            <corners android:radius="%s" />\n'
        '            <stroke android:width="1dp" android:color="%s" />\n'
        '        </shape>\n'
        '    </item>\n'
        '</selector>\n' % (r, t["field_fill"], r, t["field_stroke"])
    )


def dialog(t):
    """Фон диалога.

    Отдельным ресурсом, а не подложкой строки: у части тем `card_fill`
    полупрозрачный (`#0BFFFFFF` и подобные), и диалог на таком фоне читался бы
    насквозь. Здесь нужен плотный цвет — берём средний стоп фона экрана.
    """
    r = t.get("dialog_radius", "16dp")
    return HEAD + WARN + (
        '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:shape="rectangle">\n'
        '    <solid android:color="%s" />\n'
        '    <corners android:radius="%s" />\n'
        '    <stroke android:width="1dp" android:color="%s" />\n'
        '</shape>\n' % (t["bg"][1], r, t["card_stroke"])
    )


def diablo_button(t, primary):
    """Кнопка Diablo II: каменная плита в золотой филиграни с самоцветами.

    Слои снизу вверх: тёмный кант, золотая филигрань, сама плита градиентом
    (сверху светлее — так падает свет в оригинальном меню) и два самоцвета по
    торцам. `android:width`/`android:height`/`android:gravity` у `<item>` есть с
    API 23, у нас minSdk 24.

    Основная кнопка отличается не цветом надписи, а камнем: он теплее и светлее,
    как подсвеченный пункт меню.

    Шаблон здесь тройными кавычками, а не склейкой строк с `\\n`, как у соседей:
    слоёв девять, и в склейке из них не видно ни формы, ни вложенности.
    """
    if primary:
        top, mid, bot = "#7A7365", "#565046", "#3B372F"
    else:
        top, mid, bot = "#6E695E", "#4A463F", "#33302A"

    gem = """            <item android:width="12dp" android:height="12dp"
                android:gravity="center_vertical|%s" android:%s="3dp">
                <shape android:shape="oval">
                    <gradient android:type="radial" android:gradientRadius="7dp"
                        android:startColor="#6C8CE8" android:endColor="#15237A" />
                    <stroke android:width="1dp" android:color="#8B7343" />
                </shape>
            </item>
"""

    body = """<ripple xmlns:android="http://schemas.android.com/apk/res/android"
    android:color="?attr/novaFocusFill">
    <item>
        <selector>
            <item android:state_focused="true">
                <shape android:shape="rectangle">
                    <solid android:color="?attr/novaFocusFill" />
                    <corners android:radius="2dp" />
                    <stroke android:width="3dp" android:color="?attr/novaFocusStroke" />
                </shape>
            </item>
            <item>
                <layer-list>
                    <item>
                        <shape android:shape="rectangle">
                            <solid android:color="#15110C" />
                            <corners android:radius="2dp" />
                        </shape>
                    </item>
                    <item android:left="1dp" android:top="1dp"
                        android:right="1dp" android:bottom="1dp">
                        <shape android:shape="rectangle">
                            <gradient android:angle="270"
                                android:startColor="#C2A461" android:endColor="#6B5528" />
                            <corners android:radius="2dp" />
                        </shape>
                    </item>
                    <item android:left="4dp" android:top="4dp"
                        android:right="4dp" android:bottom="4dp">
                        <shape android:shape="rectangle">
                            <gradient android:angle="270"
                                android:startColor="%s" android:centerColor="%s"
                                android:endColor="%s" />
                            <stroke android:width="1dp" android:color="#2A251C" />
                        </shape>
                    </item>
%s%s                </layer-list>
            </item>
        </selector>
    </item>
</ripple>
""" % (top, mid, bot, gem % ("left", "left"), gem % ("right", "right"))

    return HEAD + WARN + body


def dialog_button(t, primary):
    """Подложка кнопки диалога.

    Тема может заменить её целиком: `button_style="diablo2"` отдаёт каменную
    плиту из [diablo_button]. Ветка одна и здесь, чтобы места вызова
    (`bg_nova_dialog_button_*`) не знали про исключения.

    Зачем она вообще. У платформенного `Theme.Material.Dialog.Alert` кнопки
    безрамочные, и весь их вид — это надпись цветом `colorAccent`. На тёмной
    подложке диалога такая надпись читается как часть текста, а не как кнопка:
    владелец сообщил, что «Закрыть» почти не видно. Рамка и заливка возвращают
    кнопке форму, и это не зависит от того, насколько ярок акцент темы.

    Состояние фокуса пульта повторяется здесь по той же причине, что и у
    подложки строки: без явной рамки на телевизоре непонятно, на чём стоишь.
    """
    if t.get("button_style") == "diablo2":
        return diablo_button(t, primary)
    fill = t["accent"] if primary else t["accent_dim"]
    r = t.get("btn_radius", "10dp")
    r = t.get("btn_radius", "10dp")
    return HEAD + WARN + (
        '<ripple xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:color="?attr/novaFocusFill">\n'
        '    <item>\n'
        '        <selector>\n'
        '            <item android:state_focused="true">\n'
        '                <shape android:shape="rectangle">\n'
        '                    <solid android:color="?attr/novaFocusFill" />\n'
        '                    <corners android:radius="%s" />\n'
        '                    <stroke android:width="3dp" android:color="?attr/novaFocusStroke" />\n'
        '                </shape>\n'
        '            </item>\n'
        '            <item>\n'
        '                <shape android:shape="rectangle">\n'
        '                    <solid android:color="%s" />\n'
        '                    <corners android:radius="%s" />\n'
        '                    <stroke android:width="1dp" android:color="%s" />\n'
        '                </shape>\n'
        '            </item>\n'
        '        </selector>\n'
        '    </item>\n'
        '</ripple>\n' % (r, fill, r, t["accent"])
    )


COLOR_KEYS = [
    ("accent", "accent"), ("accent_dim", "accent_dim"), ("accent_on", "accent_on"),
    ("card_fill", "card_fill"), ("card_stroke", "card_stroke"), ("sep", "separator"),
    ("group_from", "group_from"), ("group_to", "group_to"),
    ("t_title", "text_title"), ("t_value", "text_value"), ("t_hint", "text_hint"),
    ("switch_on", "switch_on"), ("switch_off", "switch_off"),
    ("thumb_on", "thumb_on"), ("thumb_off", "thumb_off"),
    ("icon", "icon_tint"), ("field_fill", "field_fill"), ("field_stroke", "field_stroke"),
    # Выделение — фокус пульта, нажатие и свечение под пальцем. Раньше это был
    # один общий `@color/tv_focus_*` на все темы, то есть зелёный `#32D74B`
    # независимо от выбранного оформления: на золотой теме длинное нажатие
    # подсвечивало строку зелёным. Значения считаются из акцента — см.
    # `derive_focus`.
    ("focus_fill", "focus_fill"), ("focus_stroke", "focus_stroke"),
]


def veil(target, base, headroom=1.3, floor=0x0B):
    """Переводит непрозрачную заливку панели в полупрозрачную вуаль того же вида.

    Зачем. Фактуру поверхности (`NovaAppearanceDrawable`) кладёт на себя **фон
    окна** — и только он. Всё, что нарисовано поверх непрозрачным, её закрывает.
    Поэтому у «Charcoal birch» фактура видна везде (её панель — вуаль `#0BFFFFFF`),
    а у тем со сплошной заливкой панели она пропадала ровно там, куда смотрит
    человек: на карточках, полях и подвале.

    Что делает. Ищет цвет `O` и альфу `a`, при которых `a*O + (1-a)*base` даёт
    ровно прежний непрозрачный цвет. Тогда тема не меняется ни на один пиксель
    там, где фактура выключена, и проступает сквозь панель там, где включена.
    Опора `base` — средний стоп фона экрана: панели лежат в середине экрана.

    Почему альфа минимальная. Чем она меньше, тем больше фактуры доходит до глаза
    (её вклад умножается на `1-a`). Минимум задан гаммой: при слишком малой альфе
    `O` вылезает за 255. `headroom` отодвигает от этой стенки, `floor` не даёт
    опуститься ниже вуали Charcoal — ниже уже не панель, а её отсутствие.

    Тема, которая объявила заливку полупрозрачной сама, сюда не попадает: её
    значение — решение автора темы, а не недосмотр.
    """
    if len(target.lstrip("#")) != 6:
        return target
    tc = [int(target.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4)]
    bc = [int(base.lstrip("#")[i:i + 2], 16) for i in (0, 2, 4)]
    need = 0.0
    for t, b in zip(tc, bc):
        if t > b:
            need = max(need, (t - b) / float(255 - b) if b < 255 else 1.0)
        elif t < b:
            need = max(need, (b - t) / float(b) if b > 0 else 1.0)
    alpha = max(floor, int(math.ceil(min(1.0, need * headroom) * 255)))
    a = alpha / 255.0
    out = []
    for t, b in zip(tc, bc):
        out.append(int(round(min(255.0, max(0.0, (t - (1 - a) * b) / a)))))
    return "#%02X%02X%02X%02X" % (alpha, out[0], out[1], out[2])


def derive_veils(t):
    """Делает полупрозрачными все заливки панелей темы.

    Обводки, разделители и переключатели не трогаются: это не панели, а линии и
    органы управления, и сквозь них смотреть нечего. Фон диалога — тоже: он лежит
    в своём окне, и прозрачность показала бы не фактуру, а список под диалогом
    (см. [dialog]).
    """
    base = t["bg"][1]
    fill = t["card_fill"]
    if isinstance(fill, str):
        t["card_fill"] = veil(fill, base)
    else:
        t["card_fill"] = tuple(veil(stop, base) for stop in fill)
    t["field_fill"] = veil(t["field_fill"], base)
    return t


def derive_focus(t):
    """Дописывает теме цвета выделения, если она их не задала явно.

    Заливка — акцент на 20 %, обводка — сам акцент. Отдельными ключами, а не
    формулой в шаблоне: тема, которой этот расчёт не идёт, сможет задать своё.
    """
    t.setdefault("focus_fill", "#33" + t["accent"].lstrip("#"))
    t.setdefault("focus_stroke", t["accent"])
    return t


THEMES = [derive_veils(derive_focus(t)) for t in THEMES]


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
        # Тема с собственной кнопкой (Diablo II) — исключение: `src_in` заливает
        # весь `layer-list` одним цветом, и от каменной плиты с самоцветами
        # остаётся золотой прямоугольник. Снять оттенок нельзя по той же причине,
        # что и задать его через `@null`, — вернётся системный. Поэтому оттенок
        # остаётся, но нейтральный: белый в режиме `multiply` — это тождество
        # (белый × цвет = цвет), а атрибут задан, и системный оверлей перебит.
        if t.get("button_style"):
            out.append('        <item name="android:backgroundTint">@android:color/white</item>\n')
            out.append('        <item name="android:backgroundTintMode">multiply</item>\n')
        else:
            out.append('        <item name="android:backgroundTint">@color/nova_%s_accent</item>\n' % key)
            out.append('        <item name="android:backgroundTintMode">src_in</item>\n')
        out.append('        <item name="android:textColor">@color/nova_%s_accent</item>\n' % key)
        out.append('        <item name="android:textAllCaps">false</item>\n')
        out.append('        <item name="android:textSize">15sp</item>\n')
        out.append('        <item name="android:textStyle">bold</item>\n')
        # Ширина кнопки — по её надписи, и начинается она с минимума.
        #
        # Здесь стояло `minWidth` 96dp и поля по 18dp, то есть три кнопки просили
        # 3*96 + 2*8 = 312dp независимо от текста. На телефоне шириной 360dp полосе
        # столько не достаётся, и `ButtonBarLayout` складывал кнопки в столбик:
        # «Сохранить/Отмена/Убрать» у лицензии WARP+ ехали лесенкой.
        #
        # Разложить их обратно после первой раскладки нельзя: `ButtonBarLayout`
        # распрямляется только тогда, когда ему **увеличили** ширину
        # (`widthSize > mLastWidthSize`), а она не меняется. Значит, не сложиться
        # надо с первого замера — отсюда узкий старт: поля по минимуму, а `minWidth`
        # ровно в размер пальца (48dp), не больше. Свободное место возвращается уже
        # в `NovaDialogs.growToFitRow`, которое знает настоящую ширину полосы.
        out.append('        <item name="android:minWidth">48dp</item>\n')
        out.append('        <item name="android:minHeight">44dp</item>\n')
        out.append('        <item name="android:paddingLeft">10dp</item>\n')
        out.append('        <item name="android:paddingRight">10dp</item>\n')
        # Вертикального поля у кнопки диалога быть не должно: `ButtonBarLayout`
        # с `gravity="bottom"` вычитает его дважды и срезает верх подложки на
        # столько же (см. NovaDialogs.apply). Отбивку снизу даёт padding полосы.
        out.append('        <item name="android:layout_marginStart">8dp</item>\n')
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

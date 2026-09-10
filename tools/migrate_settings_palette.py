# -*- coding: utf-8 -*-
"""Переводит разметку настроек с литеральных цветов на словарь темы.

Что делает и почему именно так.

Цвет, прибитый в разметке, — это цвет, который нельзя поменять темой. До этой
правки в десяти файлах настроек лежало 14 разных цветов текста: четыре серых на
одну роль подписи, четыре зелёных на один акцент, семь кеглей на четыре смысла.
Поэтому замена не «покрасивее», а механическая: каждый литерал уезжает в ту роль,
которой он по факту был.

    #FFFFFF                          -> ?attr/novaTextTitle   (роль B, название)
    #AAAAAA #8A8A8A #777777 #D9D9D9  -> ?attr/novaTextHint    (роль D, подпись)
    #7FA9C4                          -> ?attr/novaTextValue   (роль C, значение)
    #50C878 #7EEA87 #13A10E          -> ?attr/novaAccent      (акцент)

Отдельно — подписи на акцентных кнопках: белым по акценту читается не на всякой
теме (золото, мята), поэтому они уходят в `?attr/novaAccentOn`, и делается это
ДО общей замены `#FFFFFF`, иначе они станут ролью B вместе со всеми.

`android:backgroundTint="#20FFFFFF"` удаляется целиком: подложку строки теперь
рисует тема (`?attr/novaCardBackground`), а тинт поверх неё перекрашивал бы её
обратно в белый полупрозрачный.

Скрипт печатает, что именно поменял, и его вывод — то, что надо прочитать перед
сборкой. Запуск из корня репозитория:

    python tools/migrate_settings_palette.py
"""
import io
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LAYOUT = os.path.join(ROOT, "app", "src", "main", "res", "layout")
DRAWABLE = os.path.join(ROOT, "app", "src", "main", "res", "drawable")

LAYOUTS = [
    "activity_settings.xml",
    "activity_dns_settings.xml",
    "activity_domain_bypass.xml",
    "activity_direct_flow.xml",
    "activity_local_proxy.xml",
    "activity_notification_settings.xml",
    "activity_sni_mask_settings.xml",
    "activity_warp_configs.xml",
    "activity_background_settings.xml",
    "activity_logs.xml",
    "item_warp_config.xml",
    "item_dns_rule.xml",
    "item_app_selection.xml",
    "dialog_dns_rule_edit.xml",
]

TEXT_ROLES = [
    (("#FFFFFF", "#ffffff"), "?attr/novaTextTitle"),
    (("#AAAAAA", "#8A8A8A", "#777777", "#D9D9D9"), "?attr/novaTextHint"),
    (("#7FA9C4",), "?attr/novaTextValue"),
    (("#50C878", "#7EEA87", "#13A10E"), "?attr/novaAccent"),
]

SENTINEL = "@@ACCENT_ON@@"

# `indent` сохраняется, чтобы возвращённый терминатор не прилип к левому краю;
# `term` — тот самый `/>` или `>`, который на снятой строке уезжал вместе с ней.
TINT = re.compile(
    r'(?P<indent>[ \t]*)android:backgroundTint="#20FFFFFF"[ \t]*(?P<term>/?>)?[ \t]*\n?'
)


def read(path):
    return io.open(path, encoding="utf-8").read()


def write(path, text):
    io.open(path, "w", encoding="utf-8", newline="\n").write(text)


def mark_accent_button_labels(text):
    """Подпись на акцентной кнопке — своя роль, и пометить её надо до общей замены.

    Ищем `android:background="@drawable/bg_settings_action_green"` и ближайший
    `android:textColor` в пределах того же тега. Ограничение «того же тега» тут не
    формальность: без него разметка с двумя кнопками подряд красит подпись второй
    по первой.
    """
    out, count = [], 0
    for chunk in re.split(r"(?=<)", text):
        if "bg_settings_action_green" in chunk and 'android:textColor="#FFFFFF"' in chunk:
            chunk = chunk.replace('android:textColor="#FFFFFF"',
                                  'android:textColor="%s"' % SENTINEL)
            count += 1
        out.append(chunk)
    return "".join(out), count


def migrate_layout(name):
    path = os.path.join(LAYOUT, name)
    if not os.path.exists(path):
        return None
    text = read(path)
    before = text
    stats = {}

    text, n = mark_accent_button_labels(text)
    if n:
        stats["accent-button label"] = n

    n = text.count('android:background="@drawable/bg_settings_item"')
    if n:
        text = text.replace('android:background="@drawable/bg_settings_item"',
                            'android:background="?attr/novaCardBackground"')
        stats["card background"] = n

    # Тинт снимаем самим атрибутом, а не строкой целиком.
    #
    # Первая версия резала строку — и вместе с ней закрывающий `/>`, когда тинт
    # стоял последним атрибутом тега. Три тега в activity_logs.xml остались без
    # терминатора, и aapt2 упал на «TextView must be followed by ">" or "/>"».
    # Строчная правка XML обязана уважать, что строка — не единица разметки:
    # терминатор возвращается на место, если он был на снятой строке.
    def _drop_tint(match):
        term = match.group("term")
        return (match.group("indent") + term + "\n") if term else ""

    text, dropped = TINT.subn(_drop_tint, text)
    if dropped:
        stats["backgroundTint dropped"] = dropped

    for literals, attr in TEXT_ROLES:
        total = 0
        for literal in literals:
            needle = 'android:textColor="%s"' % literal
            total += text.count(needle)
            text = text.replace(needle, 'android:textColor="%s"' % attr)
        if total:
            stats[attr] = total

    # `buttonTint` у флажков и радиокнопок задан литералом, и `colorAccent` темы
    # его не перебивает — атрибут вида всегда сильнее темы. Поэтому зелёные
    # галочки переживали смену темы и оставались единственным чужим цветом на
    # золотом экране. Амбровый `#F3C94A` здесь НЕ трогается: это смысловой цвет
    # семейств импортированных протоколов (`kb/ui.md`), а не «жёлтый вместо
    # зелёного».
    for literal, attr in (("#13A10E", "?attr/novaAccent"),):
        for key in ("android:buttonTint", "android:progressTint", "android:indeterminateTint",
                    "android:progressBackgroundTint"):
            needle = '%s="%s"' % (key, literal)
            n = text.count(needle)
            if n:
                text = text.replace(needle, '%s="%s"' % (key, attr))
                stats[key] = stats.get(key, 0) + n

    text = text.replace('android:textColor="%s"' % SENTINEL,
                        'android:textColor="?attr/novaAccentOn"')

    if text != before:
        write(path, text)
    return stats


DRAWABLE_PATCHES = {
    "bg_settings_action_green.xml": [('<solid android:color="#13A10E" />',
                                      '<solid android:color="?attr/novaAccent" />')],
    "bg_settings_action_dark.xml": [('android:color="#13A10E"',
                                     'android:color="?attr/novaAccent"')],
    "bg_liquid_glass_card.xml": [
        ('android:color="@color/liquid_glass_card_bg"', 'android:color="?attr/novaCardFill"'),
        ('android:color="@color/liquid_glass_card_stroke"', 'android:color="?attr/novaCardStroke"'),
    ],
    "bg_liquid_glass_header.xml": [
        ('android:color="#B8191234"', 'android:color="?attr/novaCardFill"'),
        ('android:color="@color/liquid_glass_card_stroke"', 'android:color="?attr/novaCardStroke"'),
    ],
    "bg_liquid_glass_field.xml": [
        ('android:color="@color/liquid_glass_field_bg"', 'android:color="?attr/novaCardFill"'),
        ('android:color="@color/liquid_glass_field_stroke"', 'android:color="?attr/novaCardStroke"'),
    ],
}


def main():
    for name in LAYOUTS:
        stats = migrate_layout(name)
        if stats is None:
            print("%-38s MISSING" % name)
        elif stats:
            print("%-38s %s" % (name, ", ".join("%s x%d" % (k, v) for k, v in stats.items())))
        else:
            print("%-38s -" % name)
    print()
    for name, patches in DRAWABLE_PATCHES.items():
        path = os.path.join(DRAWABLE, name)
        text = read(path)
        hits = 0
        for old, new in patches:
            hits += text.count(old)
            text = text.replace(old, new)
        write(path, text)
        print("%-38s %d replacement(s)" % (name, hits))


if __name__ == "__main__":
    main()

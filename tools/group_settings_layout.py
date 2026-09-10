# -*- coding: utf-8 -*-
"""Разбивает плоский список настроек на смысловые группы.

Что было. Двадцать пять строк подряд, каждая — отдельная карточка минимум 64dp с
отбивкой 16dp снизу: 80dp на пункт и ни одного признака того, что «Настройка DNS»
и «Обход по доменам» про одно, а «Автообновление» — про другое. Найти пункт можно
было только прокруткой сверху донизу.

Что делает скрипт. Переставляет верхнеуровневые блоки в шесть групп, ставит перед
каждой заголовок, поджимает отбивку внутри группы с 16dp до 6dp и вешает на
название каждого пункта значок через `drawableStart`.

**Почему `drawableStart`, а не `ImageView` слева.** Строки устроены по-разному:
часть — горизонтальный `LinearLayout` с вложенным столбиком текста, часть — просто
`TextView` со стрелкой в `drawableEnd`. Вставка вида требует знать структуру каждой;
атрибут на названии не требует ничего и даёт одинаковый отступ во всех. Тинт идёт
темой (`?attr/novaIconTint`), так что значки перекрашиваются вместе с остальным.

**Хвост со списком приложений остаётся последним.** В нём `RecyclerView` на 520dp;
любая группа после него оказалась бы за экраном.

Скрипт идемпотентен: повторный запуск видит уже расставленные заголовки и выходит.
Запуск из корня репозитория:

    python tools/group_settings_layout.py
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LAYOUT = os.path.join(ROOT, "app", "src", "main", "res", "layout", "activity_settings.xml")

# Группы: (id заголовка, подпись, [опорные id блоков по порядку]).
# Опора — это `android:id` первого элемента блока, каким его видит `split_top_level`.
GROUPS = [
    ("hdr_group_connection", "ПОДКЛЮЧЕНИЕ", [
        "tv_connection_selector_title",
        "row_warp_generate_block",
        "row_warp_configs",
        "row_sni_mask",
        "row_awg_adaptation",
        "row_opera_api_proxy",
        "row_warp_license",
    ]),
    ("hdr_group_routing", "МАРШРУТИЗАЦИЯ", [
        "row_dns_settings",
        "row_domain_bypass",
        "row_direct_flow",
        "row_local_proxy",
    ]),
    ("hdr_group_app", "ПРИЛОЖЕНИЕ", [
        "sw_background",
        "row_autostart",
        "row_background_vendor",
        "sw_autoreconnect",
        "row_qs_tile",
        "row_add_widget",
        "row_main_background",
        "row_app_theme",
        "row_notification_settings",
    ]),
    ("hdr_group_updates", "ОБНОВЛЕНИЯ", [
        "row_auto_update",
        "row_manual_update_block",
        "row_share_release",
    ]),
    ("hdr_group_diagnostics", "ДИАГНОСТИКА", [
        "row_logs_container",
    ]),
    ("hdr_group_apps_vpn", "ПРИЛОЖЕНИЯ В ТУННЕЛЕ", [
        "tv_split_section_title",
        "tv_split_dns_hint",
        "rg_mode",
        "et_search",
        "sw_show_system_apps",
        "rv_apps",
    ]),
]

# Значок для названия пункта. Ключ — опорный id блока.
ICONS = {
    "row_warp_configs": "ic_nova_list",
    "row_sni_mask": "ic_nova_mask",
    "row_awg_adaptation": "ic_nova_tune",
    "row_opera_api_proxy": "ic_nova_proxy",
    "row_warp_license": "ic_nova_key",
    "row_dns_settings": "ic_nova_dns",
    "row_domain_bypass": "ic_nova_split",
    "row_direct_flow": "ic_nova_route",
    "row_local_proxy": "ic_nova_share",
    "sw_background": "ic_nova_power",
    "row_autostart": "ic_nova_star",
    # `row_background_vendor` значка не получает намеренно: это не пункт списка, а
    # условная подсказка 13sp со `visibility=gone`, которая всплывает только на
    # телефонах с фирменным экраном батареи.
    "sw_autoreconnect": "ic_nova_reconnect",
    "row_qs_tile": "ic_nova_tile",
    "row_add_widget": "ic_nova_widget",
    "row_main_background": "ic_nova_palette",
    "row_app_theme": "ic_nova_theme",
    "row_notification_settings": "ic_nova_bell",
    "row_auto_update": "ic_nova_update",
    "row_share_release": "ic_nova_download",
    "row_logs_container": "ic_nova_log",
}

HEADER = '''            <TextView
                android:id="@+id/%s"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="%s"
                android:layout_marginBottom="8dp"
                android:paddingStart="6dp"
                android:paddingEnd="6dp"
                android:fontFamily="sans-serif-medium"
                android:letterSpacing="0.12"
                android:text="%s"
                android:textColor="?attr/novaTextGroupFrom"
                android:textSize="11sp"
                android:textStyle="bold" />

'''


def split_top_level(text):
    """Режет содержимое прокручиваемого столбца на верхнеуровневые блоки.

    Считаем теги, а не разбираем XML: разбор потерял бы комментарии, а они здесь
    несут причины решений и стоят дороже удобства.
    """
    lines = text.split("\n")
    start = next(i for i, ln in enumerate(lines) if "NestedScrollView" in ln)
    depth, inside, blocks, cur = 0, False, [], None
    pending_comment = []
    i = start - 1
    while True:
        i += 1
        if i >= len(lines):
            raise AssertionError("не нашли закрытие NestedScrollView")
        ln = lines[i]
        st = ln.strip()
        if st.startswith("</androidx.core.widget.NestedScrollView"):
            # Хвост начинается с `</LinearLayout>`, который закрывает сам столбец,
            # а не со строки прокрутки: этот закрывающий тег стоит внутри тела и
            # без него всё, что мы пересобрали, остаётся незакрытым.
            j = i - 1
            while lines[j].strip() == "":
                j -= 1
            assert lines[j].strip() == "</LinearLayout>", (
                "ожидали закрытие столбца, нашли %r" % lines[j].strip()
            )
            tail_from = j
            break
        if not inside:
            if st.startswith("<LinearLayout"):
                # Открывающий тег столбца занимает несколько строк — его атрибуты
                # идут ниже. Тело начинается после строки, которая тег закрывает;
                # первая версия резала сразу за `<LinearLayout` и уносила все его
                # атрибуты вместе с `>`, отчего aapt2 падал на «must be followed
                # by attribute specifications».
                j = i
                while not lines[j].rstrip().endswith(">"):
                    j += 1
                inside = True
                depth = 0
                body_from = j + 1
                i = j
            continue
        # Комментарий на верхнем уровне разбирается ДО подсчёта тегов и целиком
        # пропускается. Иначе его первая строка выглядит как открытие блока — а
        # закрытия у неё нет, и разбор ломается на трёх комментариях подряд.
        if depth == 0 and cur is None and st.startswith("<!--"):
            j = i
            while "-->" not in lines[j]:
                j += 1
            pending_comment = lines[i:j + 1]
            i = j
            continue
        opens = len(re.findall(r"<[A-Za-z]", ln))
        sc = len(re.findall(r"/>", ln))
        cl = len(re.findall(r"</[A-Za-z]", ln))
        before = depth
        depth += opens - sc - cl
        if before == 0 and st.startswith("<") and not st.startswith("</"):
            cur = dict(first=i, id=None, comment=pending_comment)
            pending_comment = []
            blocks.append(cur)
        if cur is not None and cur["id"] is None:
            m = re.search(r'android:id="@\+id/([A-Za-z_0-9]+)"', ln)
            if m:
                cur["id"] = m.group(1)
        if depth == 0 and cur is not None and (cl or sc):
            cur["last"] = i
            cur = None
    for b in blocks:
        b["text"] = "\n".join(lines[b["first"]:b["last"] + 1])
        b["comment_text"] = "\n".join(b["comment"]) + "\n" if b["comment"] else ""
    return lines, body_from, tail_from, blocks


NEG_MARGIN_RE = re.compile(r'^[ \t]*android:layout_marginTop="-8dp"[ \t]*\n', re.M)

# Тинты поверх подложки темы перекрашивают её обратно в свой цвет, и тема до
# строки не достаёт. `#20FFFFFF` снял ещё migrate_settings_palette.py; эти два
# остались частными случаями и мешают ровно так же.
STRAY_TINT_RE = re.compile(r'^[ \t]*android:backgroundTint="#(?:163A4030|80FFFFFF)"[ \t]*\n', re.M)


def tighten(text):
    """Отбивка внутри группы: 16dp -> 6dp. Заголовок группы отделяет их сам."""
    text = text.replace('android:layout_marginBottom="16dp"', 'android:layout_marginBottom="6dp"')
    # Строку с отрицательным отступом снимаем целиком, вместе с её отступом:
    # вырезать только атрибут значило бы приклеить оставшиеся пробелы к
    # следующей строке — что первая версия скрипта и сделала.
    text = NEG_MARGIN_RE.sub("", text)
    text = STRAY_TINT_RE.sub("", text)
    text = text.replace('android:textColorHint="#80FFFFFF"',
                        'android:textColorHint="?attr/novaTextHint"')
    return text


TITLE_RE = re.compile(
    r'(<TextView\b(?:(?!</?TextView|/>).)*?android:textSize="16sp"(?:(?!</?TextView|/>).)*?/>)',
    re.S,
)


def add_icon(text, icon):
    """Вешает значок на первое название пункта (16sp) внутри блока."""
    m = TITLE_RE.search(text)
    if not m:
        return text, False
    tag = m.group(1)
    if "drawableStart" in tag:
        return text, False
    indent = re.match(r"\s*", tag.splitlines()[-1]).group(0)
    # Дописываем только то, чего в теге ещё нет. У строк со стрелкой в
    # `drawableEnd` (например «Скачать последнюю версию») `drawablePadding` уже
    # стоит, и второй такой же атрибут — не «перезапись», а ошибка разбора:
    # aapt2 падает на AttributeNSNotUnique.
    extra = ['android:drawableStart="@drawable/%s"' % icon]
    if "android:drawablePadding" not in tag:
        extra.append('android:drawablePadding="14dp"')
    if "android:drawableTint" not in tag:
        extra.append('android:drawableTint="?attr/novaIconTint"')
    injected = tag[:-2].rstrip() + "\n" + \
        "".join("%s%s\n" % (indent, a) for a in extra[:-1]) + \
        "%s%s />" % (indent, extra[-1])
    return text[:m.start(1)] + injected + text[m.end(1):], True


def main():
    text = io.open(LAYOUT, encoding="utf-8").read()
    if "hdr_group_connection" in text:
        print("already grouped, nothing to do")
        return 0

    lines, body_from, tail_from, blocks = split_top_level(text)
    by_id = {b["id"]: b for b in blocks if b["id"]}

    known = {i for g in GROUPS for i in g[2]}
    missing = [i for i in known if i not in by_id]
    if missing:
        print("MISSING anchor ids: %s" % ", ".join(missing))
        return 1
    # Заголовок экрана («Настройки») и всё, что не попало в группы, остаётся сверху
    # в исходном порядке — молча терять блок нельзя.
    leading = [b for b in blocks if b["id"] not in known]

    out = []
    for b in leading:
        out.append(b["comment_text"] + tighten(b["text"]) + "\n")

    iconed = 0
    for n, (hid, label, ids) in enumerate(GROUPS):
        out.append(HEADER % (hid, "6dp" if n == 0 else "18dp", label))
        for bid in ids:
            b = by_id[bid]
            body = tighten(b["text"])
            icon = ICONS.get(bid)
            if icon:
                body, ok = add_icon(body, icon)
                iconed += 1 if ok else 0
                if not ok:
                    print("  icon NOT attached: %s" % bid)
            out.append(b["comment_text"] + body + "\n")

    head = "\n".join(lines[:body_from])
    tail = "\n".join(lines[tail_from:])
    io.open(LAYOUT, "w", encoding="utf-8", newline="\n").write(
        head + "\n" + "\n".join(out) + "\n" + tail
    )
    print("groups: %d, blocks moved: %d, icons: %d/%d"
          % (len(GROUPS), sum(len(g[2]) for g in GROUPS), iconed, len(ICONS)))
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""
Merges new translations into a catalog, checks every entry and rewrites the catalog in source order.

  python tools/i18n/merge_catalog.py app/src/main/assets/i18n/en.json [new.json ...] [--keep-unused]

`new.json` is a JSON object {"<Russian source>": "<translation>"}. Without new files the catalog is
only re-checked, re-ordered and pruned. The order follows `extract_ru_strings.py` (layouts first, then
Kotlin files alphabetically), so strings of one screen stay together for a translator. Keys that no
code produces any more are dropped unless `--keep-unused` is given.

Checks (a failing entry is reported and kept out of the catalog — the screen then shows Russian,
which is better than a translation that lost a number or a node name):
  * the same placeholders {0}, {1}, ... as the source;
  * no Cyrillic (Russian domain names like `.рф` excepted);
  * the same leading/trailing whitespace and the same number of line breaks;
  * CAPS stay CAPS; emoji are kept.

Workflow for new UI strings:
  1. python tools/i18n/extract_ru_strings.py --catalog app/src/main/assets/i18n/en.json --missing > missing.json
  2. translate the "text" fields into {"<source>": "<translation>"} (new.json)
  3. python tools/i18n/merge_catalog.py app/src/main/assets/i18n/en.json new.json
"""
import json
import os
import re
import sys
import unicodedata

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import extract_ru_strings  # noqa: E402

CYRILLIC = re.compile(r"[\u0400-\u04FF]")
PLACEHOLDER = re.compile(r"\{\d+\}")
LETTER = re.compile(r"[^\W\d_]", re.U)
RU_DOMAIN = re.compile(r"\.?\b(рф|РФ|москва|рус|дети|онлайн|сайт|орг|ком)\b")


def emoji(text):
    return sorted(ch for ch in text if unicodedata.category(ch) == "So")


def problems(source, translation):
    found = []
    if not isinstance(translation, str) or not translation.strip():
        return ["empty translation"]
    if sorted(PLACEHOLDER.findall(source)) != sorted(PLACEHOLDER.findall(translation)):
        found.append("placeholders differ")
    if CYRILLIC.search(RU_DOMAIN.sub("", translation)):
        found.append("Cyrillic in translation")
    if source[: len(source) - len(source.lstrip())] != translation[: len(translation) - len(translation.lstrip())]:
        found.append("leading whitespace differs")
    if source[len(source.rstrip()):] != translation[len(translation.rstrip()):]:
        found.append("trailing whitespace differs")
    if source.count("\n") != translation.count("\n"):
        found.append("line breaks differ")
    letters_ru = "".join(LETTER.findall(PLACEHOLDER.sub("", source)))
    letters_en = "".join(LETTER.findall(PLACEHOLDER.sub("", translation)))
    if len(letters_ru) >= 3 and letters_ru.isupper() and not letters_en.isupper():
        found.append("CAPS lost")
    if emoji(source) != emoji(translation):
        found.append("emoji differ")
    return found


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    keep_unused = "--keep-unused" in sys.argv
    if not args:
        print(__doc__)
        sys.exit(2)
    catalog_path, new_paths = args[0], args[1:]
    catalog = {}
    if os.path.exists(catalog_path):
        with open(catalog_path, encoding="utf-8") as f:
            catalog = json.load(f)
    for path in new_paths:
        with open(path, encoding="utf-8") as f:
            catalog.update(json.load(f))

    report = extract_ru_strings.build_report()
    order = [e["text"] for e in report]
    known = set(order)

    bad = []
    merged = {}
    for source in order:
        if source not in catalog:
            continue
        issues = problems(source, catalog[source])
        if issues:
            bad.append((source, catalog[source], issues))
            continue
        merged[source] = catalog[source]
    unused = [k for k in catalog if k not in known]
    if keep_unused:
        for key in unused:
            merged[key] = catalog[key]

    missing = [e["text"] for e in report if e["ui"] and e["text"] not in merged]
    text = json.dumps(merged, ensure_ascii=False, indent=1)
    os.makedirs(os.path.dirname(os.path.abspath(catalog_path)), exist_ok=True)
    with open(catalog_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text + "\n")

    for source, translation, issues in bad:
        print("REJECTED %s: %r -> %r" % (", ".join(issues), source[:80], translation[:80]))
    print("entries: %d, rejected: %d, unused %s: %d, UI sources without translation: %d" % (
        len(merged), len(bad), "kept" if keep_unused else "dropped", len(unused), len(missing)))
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()

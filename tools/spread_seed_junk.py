# -*- coding: utf-8 -*-
"""Разводит джанк AmneziaWG по встроенным семенам WARP.

## Зачем

Все пятьдесят встроенных семян несут один и тот же джанк: `Jc = 4`, `Jmin = 40`,
`Jmax = 70`. Это одна форма на весь набор — то самое, от чего предостерегает N5:
«сменить профиль, чтобы сменить форму» не меняет ничего, потому что форма у всех
одна. Разброс есть только у сгенерированных профилей
(`WarpGeneratedStore.randomJunk`), а встроенные семена — это путь по умолчанию, по
которому и идёт большинство подключений.

## Что можно трогать, а что нет

Только `Jc` / `Jmin` / `Jmax`. Узел WARP говорит обычным WireGuard: `S1`/`S2`
дописывают мусор внутрь рукопожатия, `H1`-`H4` переименовывают типы пакетов — ни
того ни другого он не разберёт. Джанк же уходит **отдельными пакетами перед**
рукопожатием, и сервер их и так отбрасывает. `I1` здесь не трогается вовсе: у
семян их три разных, и подбором имени занимается `AwgI1Adaptation` на живой сети.

## Границы, и почему они уже, чем у генератора

`WarpGeneratedStore.randomJunk` берёт `Jc` от 3. Здесь пол — **4**, потому что
именно на четвёрке проверены все пятьдесят семян, а семена это путь по умолчанию:
ошибиться в них дороже, чем в дополнительном наборе. Сверху потолок из N25:
тяжёлый джанк (`Jc` 110-125, `Jmax` ~1000) прироста не дал, а ~60 КБ на каждое
рукопожатие стоил. Ноль джанка исключён: без него сессия не встаёт вовсе (N2).

## Почему детерминированно

Зерно берётся из `host|port` самого семени, а не из времени. Поэтому повторный
запуск даёт тот же файл: правку можно перечитать в обзоре, а не сверять на глаз, и
пересборка не начинает отличаться от предыдущей.

Запуск из корня репозитория:

    python tools/spread_seed_junk.py [--dry-run]
"""
import hashlib
import io
import json
import os
import random
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "warp_verified_seeds.json")

JUNK_COUNT_MIN = 4
JUNK_COUNT_MAX = 8
JUNK_MIN_FLOOR = 34
JUNK_MIN_CEILING = 70
JUNK_SIZE_CEILING = 150


def draw(host, port):
    """Джанк одного семени. Зерно — из его адреса, поэтому запуск повторяем."""
    digest = hashlib.sha256(("%s|%s" % (host, port)).encode("utf-8")).digest()
    rng = random.Random(int.from_bytes(digest[:8], "big"))
    count = rng.randint(JUNK_COUNT_MIN, JUNK_COUNT_MAX)
    low = rng.randint(JUNK_MIN_FLOOR, JUNK_MIN_CEILING)
    high = rng.randint(low + 20, min(low + 80, JUNK_SIZE_CEILING))
    return count, low, high


def replace_line(raw, key, value):
    pattern = re.compile(r"^%s\s*=\s*\d+$" % key, re.M)
    if not pattern.search(raw):
        raise SystemExit("в семени нет строки %s — трогать такой конфиг нельзя" % key)
    return pattern.sub("%s = %d" % (key, value), raw, count=1)


def main():
    dry = "--dry-run" in sys.argv
    seeds = json.load(io.open(ASSET, encoding="utf-8"))
    shapes = set()
    for seed in seeds:
        raw = seed.get("raw_config", "")
        count, low, high = draw(seed.get("host", ""), seed.get("port", 0))
        raw = replace_line(raw, "Jc", count)
        raw = replace_line(raw, "Jmin", low)
        raw = replace_line(raw, "Jmax", high)
        seed["raw_config"] = raw
        shapes.add((count, low, high))
    print("seeds: %d, distinct junk shapes: %d" % (len(seeds), len(shapes)))
    if dry:
        return
    with io.open(ASSET, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(seeds, fh, ensure_ascii=False, indent=2)
        fh.write("\n")
    print("written:", os.path.relpath(ASSET, ROOT))


if __name__ == "__main__":
    main()

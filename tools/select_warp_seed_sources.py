#!/usr/bin/env python3
"""Отбирает 50 профилей AmneziaWG из большего набора .conf для встроенного ассета.

Зачем отдельный шаг. `generate_warp_verified_seeds.py` намеренно требует ровно 50
файлов и не выбирает ничего сам: его дело — проверить AWG-параметры и разложить
готовый набор. Когда исходных профилей больше пятидесяти, кто-то должен решить,
какие пятьдесят поедут в релиз, и это решение стоит держать отдельно и на виду.

Чем руководствуется отбор.

1. **Разнообразие личностей — главный ключ.** Личность здесь это приватный ключ:
   один ключ = один зарегистрированный аккаунт WARP, общий для всех, кто поставил
   приложение. В релизе 1.29 пятьдесят профилей несли всего шесть личностей с
   перекосом 21/17/5/3/3/1. Замер на Mi A1 показал, почему это плохо: восемь
   переходов между узлами **одной** личности не вернули трафик ни разу, то есть
   отсечка происходит по личности, а не по узлу. Поэтому берём круговым обходом —
   по одному узлу с каждой личности, пока не наберётся пятьдесят.

2. **Внутри личности сначала проверенные узлы.** Те, что уже стоят в текущем
   ассете, прошли прогон адаптации; их относительный порядок — единственный
   имеющийся сигнал качества, и он сохраняется. Новые узлы идут следом.

3. **Никаких двух профилей с одинаковой парой (ключ, хост).** Такие отличаются
   только портом и занимают слот перебора, ничего не добавляя: в наборе 1.29
   таких пар было четыре.

4. **Никаких повторов endpoint.**

Порядок выдачи — чередование личностей, а не группировка. Очередь подключения
режется на корзины по десять (`SessionHoldMetric.bundledSeedQueueBucket`), и при
чередовании первая же корзина охватывает почти все личности: если одну отсекли,
остальные в той же корзине продолжают работать.

Запуск:
    python tools/select_warp_seed_sources.py --source E:/Downloads/awg --staging build/seed_sources
    python tools/generate_warp_verified_seeds.py --source build/seed_sources
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from collections import defaultdict
from pathlib import Path

# Консоль Windows по умолчанию cp1252 и падает на кириллице в выводе. Отчёт о
# том, что именно отобрано, важнее умолчаний терминала.
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

WANTED = 50


def natural_key(name: str) -> list[object]:
    return [int(p) if p.isdigit() else p.lower() for p in re.split(r"(\d+)", name)]


def read_conf(path: Path) -> dict[str, object] | None:
    text = path.read_text(encoding="utf-8-sig", errors="strict").replace("\r\n", "\n")
    if "[Interface]" not in text:
        return None
    key = re.search(r"^\s*PrivateKey\s*=\s*(\S+)\s*$", text, re.M | re.I)
    endpoint = re.search(r"^\s*Endpoint\s*=\s*(\S+)\s*$", text, re.M | re.I)
    if not key or not endpoint:
        return None
    host, sep, port = endpoint.group(1).rpartition(":")
    if not sep or not host or not port.isdigit():
        return None
    return {
        "path": path,
        "name": path.name,
        "key": key.group(1),
        "host": host,
        "port": int(port),
        "endpoint": endpoint.group(1),
    }


def load_current_rank(asset: Path) -> dict[str, int]:
    """Порядок узлов в текущем ассете — сигнал качества от прошлого прогона."""
    if not asset.exists():
        return {}
    seeds = json.loads(asset.read_text(encoding="utf-8"))
    return {f"{s['host']}:{s['port']}": int(s.get("seed_order", 1 << 30)) for s in seeds}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True, help="каталог с .conf (может быть больше 50)")
    parser.add_argument("--staging", required=True, help="куда положить отобранные 50")
    parser.add_argument("--asset", default="app/src/main/assets/warp_verified_seeds.json")
    args = parser.parse_args()

    source = Path(args.source)
    staging = Path(args.staging)
    rank = load_current_rank(Path(args.asset))

    records = []
    skipped_malformed = []
    for path in sorted(source.glob("*.conf"), key=lambda p: natural_key(p.name)):
        record = read_conf(path)
        if record is None:
            skipped_malformed.append(path.name)
        else:
            records.append(record)

    # Отсев дублей: сначала точные повторы endpoint, затем пары (ключ, хост).
    by_endpoint: dict[str, dict] = {}
    for record in records:
        by_endpoint.setdefault(record["endpoint"], record)
    dropped_endpoint = len(records) - len(by_endpoint)

    by_key_host: dict[tuple[str, str], dict] = {}
    for record in by_endpoint.values():
        pair = (record["key"], record["host"])
        current = by_key_host.get(pair)
        if current is None:
            by_key_host[pair] = record
            continue
        # Из пары оставляем тот узел, который уже проверен в текущем ассете.
        if rank.get(record["endpoint"], 1 << 30) < rank.get(current["endpoint"], 1 << 30):
            by_key_host[pair] = record
    dropped_key_host = len(by_endpoint) - len(by_key_host)

    pools: dict[str, list[dict]] = defaultdict(list)
    for record in by_key_host.values():
        pools[record["key"]].append(record)
    for pool in pools.values():
        # Проверенные вперёд, в порядке прошлого ранга; новые — по имени файла.
        pool.sort(key=lambda r: (rank.get(r["endpoint"], 1 << 30), natural_key(r["name"])))

    # Круговой обход: личности с большим запасом идут первыми, чтобы при нехватке
    # у мелких набор всё равно дошёл до пятидесяти.
    order = sorted(pools, key=lambda k: (-len(pools[k]), k))
    chosen: list[dict] = []
    cursor = 0
    while len(chosen) < WANTED:
        added = False
        for key in order:
            if cursor < len(pools[key]):
                chosen.append(pools[key][cursor])
                added = True
                if len(chosen) == WANTED:
                    break
        if not added:
            break
        cursor += 1

    if len(chosen) != WANTED:
        raise SystemExit(
            f"нужно {WANTED} профилей, а из {source} набралось только {len(chosen)}"
        )

    if staging.exists():
        shutil.rmtree(staging)
    staging.mkdir(parents=True)
    # Числовой префикс задаёт seed_order: генератор берёт файлы в порядке имён.
    for index, record in enumerate(chosen):
        shutil.copyfile(record["path"], staging / f"{index:03d}_{record['name']}")

    per_identity = defaultdict(int)
    for record in chosen:
        per_identity[record["key"]] += 1
    print(f"исходных .conf: {len(records)} (пропущено битых: {len(skipped_malformed)})")
    print(f"снято дублей endpoint: {dropped_endpoint}, пар (ключ, хост): {dropped_key_host}")
    print(f"личностей в наборе: {len(per_identity)} из {len(pools)} доступных")
    print("узлов на личность: " + ", ".join(str(n) for n in sorted(per_identity.values(), reverse=True)))
    first_bucket = {r["key"] for r in chosen[:10]}
    print(f"личностей в первой корзине из десяти: {len(first_bucket)}")
    print(f"отобрано {len(chosen)} файлов в {staging}")


if __name__ == "__main__":
    main()

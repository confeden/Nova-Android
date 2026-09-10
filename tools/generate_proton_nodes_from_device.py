#!/usr/bin/env python3
"""Собирает app/src/main/assets/proton_nodes.json из живого ответа Proton.

Зачем через устройство, а не с машины разработчика. Прямой `vpn-api.proton.me`
из России не открывается по TCP 443 вовсе, а альтернативный маршрут Proton
подвисает именно на `/vpn/logicals`: он отдаёт 8-21 КБ и обрывается по таймауту
на всех пяти узлах (kb/proton-generator.md, P1 и P3). Внутри поднятого туннеля
запрос проходит, поэтому список берётся у телефона, который его уже получил.

Порядок работы:

1. На телефоне с root выбрать регион PROTON и дождаться, пока в журнале появится
   «Proton: получено N бесплатных узлов» — приложение положит их в
   `/data/data/com.brent.nova/files/proton_profiles.json`.
2. Запустить этот скрипт. Он вытащит файл, оставит только публичные факты об
   узле и запишет актив.

    python tools/generate_proton_nodes_from_device.py --serial 8e3b97ab0804

Что попадает в актив: имя сервера, страна, город, адрес входа, публичный ключ
пира. Что **не** попадает и попадать не должно: seed, приватный ключ, токены
сессии, `I1`. Аккаунт у каждого устройства свой — иначе один `MaxConnect: 2`
делился бы на всех пользователей сразу.

Узлы раскладываются по кругу между странами: иначе первые кандидаты очереди
оказываются все из одной страны, и при её недоступности перебор буксует.
"""

from __future__ import annotations

import argparse
import collections
import json
import subprocess
import sys
from pathlib import Path

# Кандидаты, а не рабочий список.
#
# В рабочем списке узел встречается несколько раз — по разу на запасной порт
# (`ProtonProfileStore.expandPortFallbacks`), — поэтому пятьдесят записей это
# около тридцати адресов. Кандидаты же лежат по одному на адрес, и их восемьдесят:
# для актива, где порт не хранится вовсе, это строго более широкий источник.
DEVICE_FILE = "/data/data/com.brent.nova/files/proton_candidates.json"
DEVICE_FILE_FALLBACK = "/data/data/com.brent.nova/files/proton_profiles.json"
ASSET = Path("app/src/main/assets/proton_nodes.json")
SOURCE = "vpn-api.proton.me /vpn/logicals?Tier=0"


def pull(serial: str) -> dict:
    for path in (DEVICE_FILE, DEVICE_FILE_FALLBACK):
        cmd = ["adb"]
        if serial:
            cmd += ["-s", serial]
        cmd += ["shell", f'su -c "cat {path}"']
        raw = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", check=True).stdout
        if raw.strip():
            print(f"источник: {path}", file=sys.stderr)
            return json.loads(raw)
    sys.exit("устройство вернуло пустые файлы: выбран ли регион PROTON и прошла ли генерация?")


def build_nodes(payload: dict) -> list[dict]:
    seen: set[tuple[str, str]] = set()
    by_country: dict[str, list[dict]] = collections.OrderedDict()
    for item in payload.get("items", []):
        entry_ip = (item.get("entry_ip") or "").strip()
        peer_key = (item.get("peer_public_key") or "").strip()
        if not entry_ip or not peer_key:
            continue
        key = (entry_ip, peer_key)
        if key in seen:
            continue
        seen.add(key)
        country = (item.get("country") or "").strip()
        by_country.setdefault(country, []).append(
            {
                "server_name": (item.get("server_name") or "").strip(),
                "country": country,
                "city": (item.get("city") or "").strip(),
                "entry_ip": entry_ip,
                "peer_public_key": peer_key,
            }
        )

    ordered: list[dict] = []
    while any(by_country.values()):
        for country in list(by_country):
            if by_country[country]:
                ordered.append(by_country[country].pop(0))
    return ordered


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="", help="серийный номер устройства для adb -s")
    parser.add_argument("--out", default=str(ASSET), help="куда писать актив")
    args = parser.parse_args()

    payload = pull(args.serial)
    nodes = build_nodes(payload)
    if not nodes:
        sys.exit("в файле устройства не нашлось ни одного пригодного узла")

    asset = {
        "generated_at": max((item.get("created_at") or 0) for item in payload.get("items", [])),
        "source": SOURCE,
        "note": "Only public server facts. No keys, no account: each device registers its own.",
        "nodes": nodes,
    }
    out = Path(args.out)
    out.write_text(json.dumps(asset, ensure_ascii=False, indent=1), encoding="utf-8", newline="")

    counts = collections.Counter(node["country"] for node in nodes)
    print(f"записано {len(nodes)} узлов в {out}")
    print("по странам:", dict(counts))


if __name__ == "__main__":
    main()

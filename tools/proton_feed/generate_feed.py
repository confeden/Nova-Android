#!/usr/bin/env python3
"""Собирает feeds/proton_nodes.json из живого /vpn/logicals?Tier=0 (только stdlib).

Повторяет ровно тот путь, что у приложения (ProtonApi.kt): сессия без аутентификации
-> сессия без учётных данных -> список серверов. Для самого списка нужен только токен
сессии; аккаунта и ключей здесь нет. В ленту попадают одни публичные факты об узле.

Выбор узлов как в tools/generate_proton_nodes_from_device.py: раскладка по кругу между
странами, не больше LIMIT штук. Прежняя лента подмешивается: узел, виденный за
последние 14 дней, остаётся (с прежним last_seen), старше — выбрасывается.

Если API отдал меньше MIN_NODES узлов, скрипт завершается с ненулевым кодом и файл не
трогает. Если состав узлов не изменился, а файл моложе суток, файл тоже не переписывается:
иначе обновлённые generated_at/last_seen давали бы коммит каждые шесть часов.

    python tools/proton_feed/generate_feed.py [--out feeds/proton_nodes.json]
"""

from __future__ import annotations

import argparse
import collections
import json
import sys
import time
import urllib.request
from pathlib import Path

HOSTS = ["https://vpn-api.proton.me", "https://api.protonvpn.ch"]
APP_VERSION = "android-vpn@5.4.44.0"
CHALLENGE_FRAME_KEY = "vpn-android-v4-challenge-0"
CHALLENGE_VERSION = "2.0.7"
USER_AGENT = "ProtonVPN/5.4.44.0 (Android 14; Pixel 7)"
SOURCE = "vpn-api.proton.me /vpn/logicals?Tier=0"
NOTE = "Only public server facts. No keys, no account: each device registers its own."
MIN_NODES = 10
LIMIT = 50
MAX_TOTAL = 100
KEEP_MS = 14 * 24 * 3600 * 1000
REWRITE_MS = 24 * 3600 * 1000
TIMEOUT = 15
DEFAULT_OUT = Path(__file__).resolve().parents[2] / "feeds" / "proton_nodes.json"


def call(path: str, body: dict | None, session: dict | None) -> dict:
    last = "нет хостов"
    for host in HOSTS:
        headers = {
            "x-pm-appversion": APP_VERSION,
            "x-pm-apiversion": "3",
            "Accept": "application/vnd.protonmail.v1+json",
            "User-Agent": USER_AGENT,
        }
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
        if session:
            headers["x-pm-uid"] = session["UID"]
            headers["Authorization"] = "Bearer " + session["AccessToken"]
        request = urllib.request.Request(host + path, data=data, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT) as response:
                return json.loads(response.read().decode("utf-8"))
        except Exception as error:  # noqa: BLE001 - пробуем следующий хост
            last = f"{host}: {error}"
            print(f"{path}: {last}", file=sys.stderr)
    raise RuntimeError(last)


def credentialless_session() -> dict:
    carrier = call("/auth/v4/sessions", {}, None)
    if not carrier.get("UID") or not carrier.get("AccessToken"):
        raise RuntimeError("сессия без UID/токена")
    frame = {
        "v": CHALLENGE_VERSION,
        "appLang": "en",
        "timezone": "Europe/Berlin",
        "deviceName": 1234567890,
        "regionCode": "DE",
        "timezoneOffset": -60,
        "isJailbreak": False,
        "preferredContentSize": "1.0",
        "storageCapacity": 128000000000.0,
        "isDarkmodeOn": True,
        "keyboards": ["en_US"],
    }
    session = call("/auth/v4/credentialless", {"Payload": {CHALLENGE_FRAME_KEY: frame}}, carrier)
    if "vpn" not in (session.get("Scopes") or []):
        raise RuntimeError("сессия без scope vpn")
    return session


def fetch_servers(session: dict) -> list[dict]:
    """Как ProtonApi.fetchFreeServers: Tier 0, Status 1, один физический узел на логический."""
    out: list[dict] = []
    for logical in call("/vpn/logicals?Tier=0", None, session).get("LogicalServers") or []:
        if logical.get("Tier", -1) != 0 or logical.get("Status", 0) != 1:
            continue
        for physical in logical.get("Servers") or []:
            if physical.get("Status", 0) != 1:
                continue
            entry_ip = (physical.get("EntryIP") or "").strip()
            peer_key = (physical.get("X25519PublicKey") or "").strip()
            if not entry_ip or not peer_key:
                continue
            out.append(
                {
                    "server_name": (logical.get("Name") or "PROTON").strip(),
                    "country": (logical.get("ExitCountry") or "??").strip(),
                    "city": (logical.get("City") or "").strip(),
                    "entry_ip": entry_ip,
                    "peer_public_key": peer_key,
                }
            )
            break
    return out


def round_robin(nodes: list[dict]) -> list[dict]:
    """Раскладка по кругу между странами (та же, что у generate_proton_nodes_from_device.py)."""
    seen: set[tuple[str, str]] = set()
    by_country: dict[str, list[dict]] = collections.OrderedDict()
    for node in nodes:
        key = (node["entry_ip"], node["peer_public_key"])
        if key in seen:
            continue
        seen.add(key)
        by_country.setdefault(node["country"], []).append(node)
    ordered: list[dict] = []
    while any(by_country.values()):
        for country in list(by_country):
            if by_country[country]:
                ordered.append(by_country[country].pop(0))
    return ordered


def merge(fresh: list[dict], previous: list[dict], now_ms: int) -> list[dict]:
    # Сортировка, а не порядок ответа API: он плавает от вызова к вызову и давал бы
    # другой набор (и коммит) на каждом прогоне.
    chosen = round_robin(sorted(fresh, key=lambda n: (n["country"], n["server_name"])))[:LIMIT]
    # Необязательная подсказка "ports" (её могут дописать вручную или другой сборщик)
    # переживает пересборку: генератор её не вычисляет и не трогает.
    ports_hint = {n.get("entry_ip"): n["ports"] for n in previous if n.get("ports")}
    for node in chosen:
        node["last_seen"] = now_ms
        if node["entry_ip"] in ports_hint:
            node["ports"] = ports_hint[node["entry_ip"]]
    chosen_ips = {node["entry_ip"] for node in chosen}
    kept = [
        dict(node)
        for node in previous
        if node.get("entry_ip") not in chosen_ips
        and node.get("peer_public_key")
        and now_ms - int(node.get("last_seen") or 0) <= KEEP_MS
    ]
    return (chosen + round_robin(kept))[:MAX_TOTAL]


def load_previous(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def main() -> int:
    for stream in (sys.stdout, sys.stderr):
        stream.reconfigure(encoding="utf-8", errors="replace")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    args = parser.parse_args()
    out = Path(args.out)

    try:
        fresh = fetch_servers(credentialless_session())
    except Exception as error:  # noqa: BLE001
        print(f"ошибка: {error}", file=sys.stderr)
        return 1
    if len(fresh) < MIN_NODES:
        print(f"ошибка: API отдал {len(fresh)} узлов (<{MIN_NODES}), файл не тронут", file=sys.stderr)
        return 2

    now_ms = int(time.time() * 1000)
    previous = load_previous(out)
    nodes = merge(fresh, list(previous.get("nodes") or []), now_ms)

    def identity(items: list[dict]) -> list[tuple[str, str]]:
        return [(n.get("entry_ip", ""), n.get("peer_public_key", "")) for n in items]

    unchanged = identity(nodes) == identity(list(previous.get("nodes") or []))
    if unchanged and now_ms - int(previous.get("generated_at") or 0) < REWRITE_MS:
        print(f"состав не изменился ({len(nodes)} узлов), файл моложе суток — не переписываю")
        return 0

    feed = {"generated_at": now_ms, "source": SOURCE, "note": NOTE, "nodes": nodes}
    out.parent.mkdir(parents=True, exist_ok=True)
    tmp = out.with_suffix(".tmp")
    tmp.write_text(json.dumps(feed, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")
    tmp.replace(out)
    print(f"записано {len(nodes)} узлов (свежих {len(fresh)}) в {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

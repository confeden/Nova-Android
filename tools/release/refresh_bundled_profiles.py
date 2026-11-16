#!/usr/bin/env python3
"""Освежает встроенные профили перед выпуском: точки входа WARP и узлы Proton.

Каждый релиз должен везти самые свежие встроенные профили, а пользователь потом может
заменить их своими личными (D45). Источник по порядку:

1. **Телефон по adb** (нужен root: `/data/data/com.brent.nova/files` закрыт для
   `run-as` у релизной сборки). Берётся файл самого свежего устройства:
   `warp_generated.json` (точки входа WARP), `proton_candidates.json` /
   `proton_profiles.json` (узлы Proton).
2. **Одобренный набор в репозитории** — `tools/release/approved/`, то, что в прошлый раз
   вытащили с телефона и закоммитили. Если телефона нет, активы приводятся к нему.

Что можно класть в репозиторий и в APK (I5, I6, I7, I15, kb/warp-generator.md,
kb/proton-generator.md):

* **WARP — только точки входа** (адрес, порт, задержка). Личность устройства
  (`identity`: приватный ключ, токен) в репозиторий не попадает никогда. Встроенные семена
  несут свои пятнадцать общих ключей и остаются неизменными: по I7 у них общий внутренний
  адрес `172.16.0.2` и свой IPv6 на личность, что и позволяет вращать **только** `Endpoint`.
  `S1`-`S4` и `H1`-`H4` у семени стоковые (узел WARP их не разберёт) и скриптом не
  трогаются; ни один новый `PrivateKey` в семена не вносится — это проверяется.
* **Proton — только публичные факты об узле** (имя, страна, город, адрес входа, публичный
  ключ пира). Seed, токены, сертификат и `I1` — нет.
* **MASQUE — ничего.** Профиль MASQUE — это `private_key` и `access_token` устройства
  Cloudflare, публичных частей, которые стоило бы встраивать, у него нет: адреса берёт
  сканер на месте. Скрипт только проверяет, что MASQUE-секретов нет в активах.

Сборка остаётся воспроизводимой (I15, G138): скрипт **не** часть Gradle и не запускается
эталоном F-Droid. Он меняет файлы в рабочей копии, владелец коммитит их до того, как
составлен публичный коммит выпуска; `build_release.sh` потом только проверяет формат и
возраст (`--check`).

    python tools/release/refresh_bundled_profiles.py             # телефон -> одобренный набор
    python tools/release/refresh_bundled_profiles.py --offline   # без adb: из одобренного
    python tools/release/refresh_bundled_profiles.py --dry-run   # то же, но ничего не записывать
    python tools/release/refresh_bundled_profiles.py --check     # только проверка (для build_release.sh)
    python tools/release/refresh_bundled_profiles.py --serial 8e3b97ab0804

Код возврата: 0 — готово; 1 — нарушен формат или найден секрет; 2 — нечего освежать
(нет ни телефона, ни одобренного набора). Устаревший набор — предупреждение, не отказ.
"""

from __future__ import annotations

import argparse
import base64
import collections
import ipaddress
import json
import re
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app" / "src" / "main" / "assets"
SEEDS_ASSET = ASSETS / "warp_verified_seeds.json"
PROTON_ASSET = ASSETS / "proton_nodes.json"
APPROVED = ROOT / "tools" / "release" / "approved"
APPROVED_WARP = APPROVED / "warp_endpoints.json"
APPROVED_PROTON = APPROVED / "proton_nodes.json"

PACKAGE_DIR = "/data/data/com.brent.nova/files"
WARP_FILE = f"{PACKAGE_DIR}/warp_generated.json"
PROTON_FILES = (f"{PACKAGE_DIR}/proton_candidates.json", f"{PACKAGE_DIR}/proton_profiles.json")

MIN_WARP_ENDPOINTS = 24      # меньше — сканер на той сети почти ничего не нашёл
MIN_WARP_SUBNETS = 4         # блокируют диапазонами: одна /24 — один рубильник
MIN_PROTON_NODES = 30
MIN_PROTON_COUNTRIES = 3
MAX_PROTON_NODES = 150
MAX_AGE_DAYS = 45

# Поля, которых не должно быть ни в одобренном наборе, ни в Proton-активе.
SECRET_KEYS = {
    "private_key", "privatekey", "access_token", "accesstoken", "refresh_token", "token",
    "seed", "identity", "i1", "certificate", "password", "client_id", "device_id",
}
PROTON_NODE_FIELDS = {"server_name", "country", "city", "entry_ip", "peer_public_key"}
B64_KEY = re.compile(r"^[A-Za-z0-9+/]{43}=$")


def say(text: str) -> None:
    print(f"[profiles] {text}", file=sys.stderr)


# --------------------------------------------------------------------------- устройство

def adb(serial: str, *args: str, timeout: int = 20) -> str:
    cmd = ["adb"] + (["-s", serial] if serial else []) + list(args)
    done = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8",
                          errors="replace", timeout=timeout)
    if done.returncode != 0:
        raise RuntimeError(done.stderr.strip() or f"adb завершился с кодом {done.returncode}")
    return done.stdout


def list_devices() -> list[str]:
    try:
        out = adb("", "devices")
    except Exception as error:  # noqa: BLE001 — adb может вообще не стоять
        say(f"adb недоступен ({error}) — идём к одобренному набору")
        return []
    return [line.split()[0] for line in out.splitlines()[1:] if line.strip().endswith("device")]


def pull_json(serial: str, path: str) -> dict | None:
    try:
        raw = adb(serial, "shell", f'su -c "cat {path}"')
    except Exception:  # noqa: BLE001
        return None
    if not raw.strip():
        return None
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        return None


def pull_from_devices(serials: list[str]) -> dict:
    """Самый свежий WARP- и Proton-снимок среди устройств с root."""
    best: dict = {"warp": None, "proton": None}
    for serial in serials:
        try:
            if "uid=0" not in adb(serial, "shell", 'su -c "id"'):
                raise RuntimeError("нет root")
        except Exception:  # noqa: BLE001
            say(f"{serial}: нет root — файлы приложения недоступны, пропускаем")
            continue
        warp = pull_json(serial, WARP_FILE)
        if warp and warp.get("profiles"):
            stamp = max((p.get("created_at") or 0) for p in warp["profiles"])
            if best["warp"] is None or stamp > best["warp"][0]:
                best["warp"] = (stamp, serial, warp)
        else:
            say(f"{serial}: warp_generated.json пуст или отсутствует (личные профили ещё не выпускались)")
        for path in PROTON_FILES:
            payload = pull_json(serial, path)
            if payload and payload.get("items"):
                stamp = max((i.get("created_at") or 0) for i in payload["items"])
                if best["proton"] is None or stamp > best["proton"][0]:
                    best["proton"] = (stamp, serial, payload)
                break
    return best


# --------------------------------------------------------------------------- WARP

def subnet(host: str) -> str:
    ip = ipaddress.ip_address(host)
    if ip.version == 4:
        return ".".join(host.split(".")[:3])
    return ":".join(ip.exploded.split(":")[:3])


def endpoints_from_device(warp: dict) -> list[dict]:
    """Проверенные точки входа: только с замеренной задержкой, без повторов."""
    seen: set[tuple[str, int]] = set()
    out: list[dict] = []
    for p in warp.get("profiles", []):
        host = str(p.get("host", "")).strip()
        port = int(p.get("port", 0) or 0)
        rtt = int(p.get("rtt_ms", 0) or 0)
        if rtt <= 0 or not 1 <= port <= 65535:
            continue  # rtt<=0 — адрес, до которого сканер не дотрагивался (добит случайным)
        try:
            ipaddress.ip_address(host)
        except ValueError:
            continue
        if (host, port) in seen:
            continue
        seen.add((host, port))
        out.append({"host": host, "port": port, "rtt_ms": rtt})
    out.sort(key=lambda e: e["rtt_ms"])
    return out


def validate_endpoints(endpoints: list[dict]) -> list[str]:
    problems: list[str] = []
    if len(endpoints) < MIN_WARP_ENDPOINTS:
        problems.append(f"WARP: точек {len(endpoints)}, нужно не меньше {MIN_WARP_ENDPOINTS}")
    subnets = {subnet(e["host"]) for e in endpoints}
    if len(subnets) < MIN_WARP_SUBNETS:
        problems.append(f"WARP: подсетей {len(subnets)}, нужно не меньше {MIN_WARP_SUBNETS}")
    return problems


def rotate_seeds(seeds: list[dict], endpoints: list[dict], stamp_ms: int) -> list[dict]:
    """Меняет у семян только адрес и порт. Всё остальное, включая ключи и мусор, как было."""
    # Подсети раскладываем по кругу: пятьдесят точек из одной /24 — один рубильник на всех.
    by_subnet: dict[str, list[dict]] = collections.OrderedDict()
    for e in endpoints:
        by_subnet.setdefault(subnet(e["host"]), []).append(e)
    ordered: list[dict] = []
    while any(by_subnet.values()):
        for key in list(by_subnet):
            if by_subnet[key]:
                ordered.append(by_subnet[key].pop(0))
    rotated = []
    for index, seed in enumerate(seeds):
        seed = dict(seed)
        if index < len(ordered):
            e = ordered[index]
            old = f"Endpoint = {seed['host']}:{seed['port']}"
            new = f"Endpoint = {e['host']}:{e['port']}"
            if old not in seed["raw_config"]:
                raise ValueError(f"seed {index}: в raw_config нет строки «{old}»")
            seed["raw_config"] = seed["raw_config"].replace(old, new, 1)
            seed["host"], seed["port"] = e["host"], e["port"]
            seed["last_verified_at"] = stamp_ms
        rotated.append(seed)
    return rotated


def private_keys(seeds: list[dict]) -> set[str]:
    return {m.group(1) for s in seeds for m in re.finditer(r"PrivateKey = (\S+)", s["raw_config"])}


def validate_seeds(seeds: list[dict]) -> list[str]:
    problems: list[str] = []
    if len(seeds) != 50:
        problems.append(f"семян {len(seeds)}, а должно быть 50")
    seen: set[tuple[str, int]] = set()
    for i, s in enumerate(seeds):
        cfg = s.get("raw_config", "")
        pair = (s.get("host"), s.get("port"))
        if pair in seen:
            problems.append(f"seed {i}: повтор {pair[0]}:{pair[1]}")
        seen.add(pair)
        if f"Endpoint = {s['host']}:{s['port']}" not in cfg:
            problems.append(f"seed {i}: Endpoint в raw_config не совпадает с host:port")
        if "Address = 172.16.0.2," not in cfg:                       # I7
            problems.append(f"seed {i}: внутренний адрес не 172.16.0.2 (I7)")
        for k in ("S1", "S2", "S3", "S4"):
            if not re.search(rf"^{k} = 0$", cfg, re.M):
                problems.append(f"seed {i}: {k} не 0 — узел WARP не разберёт")
        for n, k in enumerate(("H1", "H2", "H3", "H4"), start=1):
            if not re.search(rf"^{k} = {n}$", cfg, re.M):
                problems.append(f"seed {i}: {k} не {n} — узел WARP не разберёт")
        if s.get("preferred_sni"):
            problems.append(f"seed {i}: preferred_sni должен быть пуст")
    return problems


# --------------------------------------------------------------------------- Proton

def proton_nodes_from_device(payload: dict) -> list[dict]:
    seen: set[tuple[str, str]] = set()
    by_country: dict[str, list[dict]] = collections.OrderedDict()
    for item in payload.get("items", []):
        ip = (item.get("entry_ip") or "").strip()
        key = (item.get("peer_public_key") or "").strip()
        if not ip or not key or (ip, key) in seen:
            continue
        seen.add((ip, key))
        country = (item.get("country") or "").strip()
        by_country.setdefault(country, []).append({
            "server_name": (item.get("server_name") or "").strip(),
            "country": country,
            "city": (item.get("city") or "").strip(),
            "entry_ip": ip,
            "peer_public_key": key,
        })
    return round_robin(by_country)


def round_robin(by_country: dict[str, list[dict]]) -> list[dict]:
    ordered: list[dict] = []
    while any(by_country.values()):
        for country in list(by_country):
            if by_country[country]:
                ordered.append(by_country[country].pop(0))
    return ordered


def merge_proton(fresh: list[dict], old: list[dict]) -> list[dict]:
    """Свежие узлы впереди, прежние — следом (узел, молчавший на одной сети, жив на другой)."""
    seen = {(n["entry_ip"], n["peer_public_key"]) for n in fresh}
    tail = [n for n in old if (n["entry_ip"], n["peer_public_key"]) not in seen]
    by_country: dict[str, list[dict]] = collections.OrderedDict()
    for n in fresh:
        by_country.setdefault(n["country"], []).append(dict(n))
    head = round_robin(by_country)
    by_country = collections.OrderedDict()
    for n in tail:
        by_country.setdefault(n["country"], []).append(dict(n))
    return (head + round_robin(by_country))[:MAX_PROTON_NODES]


def validate_proton(nodes: list[dict]) -> list[str]:
    problems: list[str] = []
    if len(nodes) < MIN_PROTON_NODES:
        problems.append(f"Proton: узлов {len(nodes)}, нужно не меньше {MIN_PROTON_NODES}")
    if len({n.get("country") for n in nodes}) < MIN_PROTON_COUNTRIES:
        problems.append(f"Proton: стран меньше {MIN_PROTON_COUNTRIES}")
    for i, n in enumerate(nodes):
        extra = set(n) - PROTON_NODE_FIELDS
        if extra:
            problems.append(f"Proton node {i}: лишние поля {sorted(extra)} — только публичные факты")
        try:
            ipaddress.ip_address(str(n.get("entry_ip", "")).strip("[]"))
        except ValueError:
            problems.append(f"Proton node {i}: entry_ip не адрес")
        key = str(n.get("peer_public_key", ""))
        if not B64_KEY.match(key) or len(base64.b64decode(key)) != 32:
            problems.append(f"Proton node {i}: peer_public_key не 32 байта base64")
    return problems


# --------------------------------------------------------------------------- секреты

def scan_secrets(value, where: str) -> list[str]:
    """Ищет в JSON имена полей из SECRET_KEYS."""
    found: list[str] = []
    if isinstance(value, dict):
        for k, v in value.items():
            if str(k).lower() in SECRET_KEYS:
                found.append(f"{where}: поле «{k}» — секрет, в репозиторий не кладётся")
            found += scan_secrets(v, f"{where}.{k}")
    elif isinstance(value, list):
        for i, v in enumerate(value[:5000]):
            found += scan_secrets(v, f"{where}[{i}]")
    return found


def load(path: Path):
    return json.loads(path.read_text(encoding="utf-8-sig")) if path.exists() else None


DRY_RUN = False


def write_json(path: Path, data, indent=1) -> None:
    if DRY_RUN:
        say(f"(пробный прогон) записал бы {path.relative_to(ROOT)}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=indent) + "\n", encoding="utf-8", newline="\n")


# --------------------------------------------------------------------------- режимы

def check() -> int:
    problems: list[str] = []
    seeds = load(SEEDS_ASSET)
    nodes_asset = load(PROTON_ASSET)
    if seeds is None or nodes_asset is None:
        problems.append("нет warp_verified_seeds.json или proton_nodes.json в assets")
    else:
        problems += validate_seeds(seeds)
        problems += validate_proton(nodes_asset.get("nodes", []))
        problems += scan_secrets(nodes_asset, "proton_nodes.json")
    for path in (APPROVED_WARP, APPROVED_PROTON):
        data = load(path)
        if data is not None:
            problems += scan_secrets(data, path.name)
    warp = load(APPROVED_WARP)
    if warp is not None:
        problems += validate_endpoints(warp.get("endpoints", []))
    # MASQUE: ни ключа, ни токена в активах быть не должно.
    for asset in ASSETS.glob("*.json"):
        text = asset.read_text(encoding="utf-8", errors="replace")
        if asset.name != "warp_verified_seeds.json" and asset.name != "warp_bootstrap.json" \
                and ('"access_token"' in text or '"private_key"' in text):
            problems.append(f"{asset.name}: ключ или токен в активах (MASQUE/личность)")
    if problems:
        for p in problems:
            say("ОШИБКА: " + p)
        return 1
    ages = []
    if nodes_asset and nodes_asset.get("generated_at"):
        ages.append(("proton_nodes.json", nodes_asset["generated_at"]))
    if warp and warp.get("generated_at"):
        ages.append(("warp_endpoints.json", warp["generated_at"]))
    for name, ms in ages:
        days = (time.time() * 1000 - ms) / 86_400_000
        flag = "  <- СТАРЕЕТ: освежить перед выпуском" if days > MAX_AGE_DAYS else ""
        say(f"{name}: набору {days:.0f} дн.{flag}")
    say("формат встроенных профилей в порядке, секретов нет")
    return 0


def refresh(serials: list[str], offline: bool) -> int:
    seeds = load(SEEDS_ASSET)
    nodes_asset = load(PROTON_ASSET) or {"nodes": []}
    if seeds is None:
        say("ОШИБКА: нет warp_verified_seeds.json")
        return 1
    keys_before = private_keys(seeds)

    pulled = {"warp": None, "proton": None}
    if not offline:
        devices = serials or list_devices()
        say(f"устройства: {', '.join(devices) or 'нет'}")
        pulled = pull_from_devices(devices)

    # ---- WARP
    warp_source = "approved"
    endpoints: list[dict] = []
    stamp_ms = int(time.time() * 1000)
    if pulled["warp"]:
        stamp, serial, payload = pulled["warp"]
        endpoints = endpoints_from_device(payload)
        problems = validate_endpoints(endpoints)
        if problems:
            say(f"телефон {serial}: набор не годится ({'; '.join(problems)}) — берём одобренный")
            endpoints = []
        else:
            warp_source = f"device {serial}"
            stamp_ms = stamp or stamp_ms
            write_json(APPROVED_WARP, {
                "generated_at": stamp_ms,
                "source": f"warp_generated.json с устройства; только точки входа, без личности",
                "endpoints": endpoints,
            })
    if not endpoints:
        approved = load(APPROVED_WARP)
        if approved:
            endpoints = approved["endpoints"]
            stamp_ms = approved.get("generated_at", stamp_ms)
    if endpoints:
        new_seeds = rotate_seeds(seeds, endpoints, stamp_ms)
        if private_keys(new_seeds) != keys_before:
            say("ОШИБКА: набор приватных ключей семян изменился — этот скрипт ключей не трогает")
            return 1
        problems = validate_seeds(new_seeds)
        if problems:
            say("ОШИБКА: " + "; ".join(problems[:5]))
            return 1
        if new_seeds != seeds:
            write_json(SEEDS_ASSET, new_seeds, indent=2)
            say(f"WARP: {min(len(endpoints), len(seeds))} семян получили свежие точки входа ({warp_source})")
        else:
            say(f"WARP: семена уже совпадают с набором ({warp_source})")
    else:
        say("WARP: ни телефона, ни одобренного набора — семена остаются как есть")

    # ---- Proton
    proton_source = "approved"
    nodes: list[dict] = []
    if pulled["proton"]:
        stamp, serial, payload = pulled["proton"]
        fresh = proton_nodes_from_device(payload)
        nodes = merge_proton(fresh, nodes_asset.get("nodes", []))
        problems = validate_proton(nodes)
        if problems:
            say(f"телефон {serial}: Proton-набор не годится ({'; '.join(problems)}) — берём одобренный")
            nodes = []
        else:
            proton_source = f"device {serial}"
            record = {
                "generated_at": stamp or int(time.time() * 1000),
                "source": "vpn-api.proton.me /vpn/logicals?Tier=0",
                "note": "Only public server facts. No keys, no account: each device registers its own.",
                "nodes": nodes,
            }
            write_json(APPROVED_PROTON, record)
    if not nodes:
        approved = load(APPROVED_PROTON)
        if approved:
            nodes = approved["nodes"]
            record = approved
    if nodes:
        problems = validate_proton(nodes) + scan_secrets(record, "proton_nodes")
        if problems:
            say("ОШИБКА: " + "; ".join(problems[:5]))
            return 1
        if load(PROTON_ASSET) != record:
            write_json(PROTON_ASSET, record)
            say(f"Proton: {len(nodes)} узлов в активе ({proton_source})")
        else:
            say(f"Proton: актив уже совпадает с набором ({proton_source})")
    else:
        say("Proton: ни телефона, ни одобренного набора — актив остаётся как есть")

    say("MASQUE: встраивать нечего (ключ и токен — секреты устройства, адреса ищет сканер)")
    if not endpoints and not nodes:
        return 2
    code = check()
    if code == 0:
        say("готово. Изменённые файлы — в git status; закоммитьте их ДО составления публичного коммита выпуска")
    return code


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--serial", action="append", default=[], help="только эти устройства (можно несколько раз)")
    parser.add_argument("--offline", action="store_true", help="adb не трогать: только одобренный набор")
    parser.add_argument("--check", action="store_true", help="только проверка формата, секретов и возраста")
    parser.add_argument("--dry-run", action="store_true", help="показать, что изменится, и ничего не записывать")
    args = parser.parse_args()
    global DRY_RUN
    DRY_RUN = args.dry_run
    if hasattr(sys.stderr, "reconfigure"):
        sys.stderr.reconfigure(encoding="utf-8")
    sys.exit(check() if args.check else refresh(args.serial, args.offline))


if __name__ == "__main__":
    main()

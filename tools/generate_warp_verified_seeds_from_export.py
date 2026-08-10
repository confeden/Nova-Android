#!/usr/bin/env python3
import argparse
import json
import subprocess
from pathlib import Path


DEFAULT_DEVICE_EXPORT = "/sdcard/Android/data/com.brent.nova/files/warp_verified_export.json"
DEFAULT_OUTPUT = "app/src/main/assets/warp_verified_seeds.json"


def read_export(args: argparse.Namespace) -> dict:
    if args.input:
        return json.loads(Path(args.input).read_text(encoding="utf-8-sig"))

    device_path = args.device_path or DEFAULT_DEVICE_EXPORT
    completed = subprocess.run(
        ["adb", "shell", "cat", device_path],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    return json.loads(completed.stdout)


def normalize_seed(item: dict, index: int) -> dict:
    required = ("engine", "mode", "host", "port", "raw_config")
    missing = [name for name in required if not item.get(name)]
    if missing:
        raise ValueError(f"release_seed_items[{index}] missing: {', '.join(missing)}")

    seed = {
        "engine": item["engine"],
        "mode": item["mode"],
        "endpoint_source": "bundled-seed",
        "success_count": max(int(item.get("success_count", 1)), 1),
        "scope": item.get("scope") or "default",
        "seed_order": index,
        # Маскировочное имя в сид не попадает никогда. Раньше попадало: релиз
        # 1.26 вёз ads.max.ru у 45 профилей из 50, то есть каждая свежая
        # установка стартовала с подстановкой, которую никто не включал.
        "preferred_sni": "",
        "source_file": f"pixel4a_export_rank_{index + 1:03d}",
        "host": item["host"],
        "port": int(item["port"]),
        "raw_config": item["raw_config"],
        "last_verified_at": int(item.get("last_verified_at") or 1700000000000),
    }

    for key in (
        "quality_probe_count",
        "quality_ping_successes",
        "quality_avg_ping_ms",
        "quality_last_checked_at",
        "quality_failure_count",
        "preferred_ports",
    ):
        if key in item:
            seed[key] = item[key]

    return seed


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build bundled WARP seed asset from Nova's device export release_seed_items order."
    )
    parser.add_argument("--input", help="Local warp_verified_export.json. If omitted, adb is used.")
    parser.add_argument("--device-path", default=DEFAULT_DEVICE_EXPORT)
    parser.add_argument("--output", default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    export = read_export(args)
    release_items = export.get("release_seed_items") or []
    if not isinstance(release_items, list) or not release_items:
        raise SystemExit("export does not contain release_seed_items")

    seeds = [normalize_seed(item, index) for index, item in enumerate(release_items)]
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(seeds, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(seeds)} seeds to {output}")


if __name__ == "__main__":
    main()

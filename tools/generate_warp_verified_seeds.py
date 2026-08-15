#!/usr/bin/env python3
import argparse
import json
import re
from pathlib import Path


AWG_REQUIRED_KEYS = (
    "S1",
    "S2",
    "S3",
    "S4",
    "Jc",
    "Jmin",
    "Jmax",
    "H1",
    "H2",
    "H3",
    "H4",
    "I1",
)


def natural_key(path: Path) -> list[object]:
    return [
        int(part) if part.isdigit() else part.lower()
        for part in re.split(r"(\d+)", path.name)
    ]


def parse_endpoint(raw_config: str) -> tuple[str, int]:
    match = re.search(r"^\s*Endpoint\s*=\s*(\S+)\s*$", raw_config, re.MULTILINE | re.IGNORECASE)
    if not match:
        raise ValueError("missing Endpoint")

    endpoint = match.group(1).strip()
    if endpoint.startswith("["):
        host, _, rest = endpoint[1:].partition("]")
        if not rest.startswith(":"):
            raise ValueError(f"invalid bracketed endpoint: {endpoint}")
        port_text = rest[1:]
    else:
        host, sep, port_text = endpoint.rpartition(":")
        if not sep:
            raise ValueError(f"missing endpoint port: {endpoint}")

    port = int(port_text)
    if not host or port not in range(1, 65536):
        raise ValueError(f"invalid endpoint: {endpoint}")
    return host, port


def validate_awg(raw_config: str) -> None:
    missing = [
        key
        for key in AWG_REQUIRED_KEYS
        if not re.search(rf"^\s*{re.escape(key)}\s*=", raw_config, re.MULTILINE)
    ]
    if missing:
        raise ValueError(f"missing AWG keys: {', '.join(missing)}")


def normalize_config(path: Path) -> str:
    raw = path.read_text(encoding="utf-8-sig").replace("\r\n", "\n").replace("\r", "\n").strip()
    return raw + "\n"


def build_seed(path: Path, seed_order: int) -> dict[str, object]:
    raw_config = normalize_config(path)
    validate_awg(raw_config)
    host, port = parse_endpoint(raw_config)
    return {
        "engine": "wireguard",
        "mode": "warp-awg-exact",
        "endpoint_source": "bundled-seed",
        "success_count": 1,
        "scope": "default",
        "seed_order": seed_order,
        "preferred_sni": "",
        "source_file": path.name,
        "host": host,
        "port": port,
        "raw_config": raw_config,
        "last_verified_at": 1700000000000,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    # Каталог с исходными .conf задаётся при запуске: путь зависит от машины, а
    # значение по умолчанию из чужой файловой системы только сбивает с толку.
    parser.add_argument(
        "--source",
        required=True,
        help="каталог с 50 файлами AmneziaWG .conf",
    )
    parser.add_argument("--output", default=r"app/src/main/assets/warp_verified_seeds.json")
    args = parser.parse_args()

    source = Path(args.source)
    output = Path(args.output)
    files = sorted(source.glob("*.conf"), key=natural_key)
    if len(files) != 50:
        raise SystemExit(f"expected 50 .conf files in {source}, found {len(files)}")

    seeds = [build_seed(path, index) for index, path in enumerate(files)]
    output.write_text(json.dumps(seeds, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()

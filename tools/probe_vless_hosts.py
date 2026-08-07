"""Быстрый отбор живых узлов из подписки VLESS.

Проверяется только достижимость `host:port` по TCP. Это не подтверждает, что узел
примет рукопожатие REALITY — ключи в публичных списках ротируются, и протухшая
запись отвечает на TCP ровно так же, как рабочая. Смысл проверки в другом: отсеять
заведомо мёртвые адреса, чтобы живую пробу в приложении не тратить на них.

Использование:
    python tools/probe_vless_hosts.py <файл-подписки> [--limit N] [--timeout SEC]
"""

import argparse
import base64
import concurrent.futures
import socket
import sys
import urllib.parse


def decode_body(raw: str) -> str:
    """Подписка приходит либо построчно, либо одним base64."""
    if "vless://" in raw:
        return raw
    for pad in ("", "=", "==", "==="):
        try:
            return base64.b64decode(raw.strip() + pad).decode("utf-8", "replace")
        except Exception:
            continue
    return raw


def parse_links(body: str):
    seen = set()
    for line in body.splitlines():
        line = line.strip()
        if not line.startswith("vless://"):
            continue
        rest = line[len("vless://"):]
        authority = rest.split("?", 1)[0].split("#", 1)[0]
        if "@" not in authority:
            continue
        hostport = authority.rsplit("@", 1)[1]
        if hostport.startswith("["):
            host, _, port = hostport.partition("]:")
            host = host.lstrip("[")
        else:
            host, _, port = hostport.rpartition(":")
        if not host or not port.isdigit():
            continue
        remark = ""
        if "#" in line:
            remark = urllib.parse.unquote(line.split("#", 1)[1])
        key = (host, int(port))
        if key in seen:
            continue
        seen.add(key)
        yield host, int(port), remark, line


def probe(entry, timeout):
    host, port, remark, link = entry
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True, host, port, remark, link
    except Exception:
        return False, host, port, remark, link


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("subscription")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--timeout", type=float, default=4.0)
    args = parser.parse_args()

    with open(args.subscription, encoding="utf-8", errors="replace") as handle:
        body = decode_body(handle.read())

    entries = list(parse_links(body))
    if args.limit:
        entries = entries[: args.limit]
    print(f"узлов в подписке: {len(entries)}")

    alive = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as pool:
        futures = [pool.submit(probe, entry, args.timeout) for entry in entries]
        for future in concurrent.futures.as_completed(futures):
            ok, host, port, remark, link = future.result()
            if ok:
                alive.append((host, port, remark, link))

    print(f"отвечают по TCP: {len(alive)}")
    for host, port, remark, link in alive:
        print(f"{host}:{port}\t{remark}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
"""Проверяет, доходит ли DNS через SOCKS5-инбаунд ядра VLESS.

tun2proxy для VLESS работает в режиме `OverTcp`: UDP-запрос на порт 53 он
переписывает в TCP и отправляет в прокси. Значит, весь DNS туннеля упирается в один
вопрос — принимает ли узел CONNECT на 53/tcp и отвечает ли резолвер. Если нет,
страницы не откроются при полностью живом туннеле, и выглядит это как «нет
интернета в браузере».

Порт SOCKS берётся из /proc/net/tcp устройства и пробрасывается через adb forward:

    adb forward tcp:<порт> tcp:<порт>
    python tools/probe_vless_dns.py <порт> [домен ...]
"""
import socket
import struct
import sys
import time


def socks_connect(sock: socket.socket, host: str, port: int) -> str:
    """SOCKS5 CONNECT по имени. Возвращает "" при успехе или текст ошибки."""
    sock.sendall(b"\x05\x01\x00")
    greeting = sock.recv(2)
    if greeting != b"\x05\x00":
        return f"рукопожатие SOCKS отвергнуто: {greeting!r}"

    host_bytes = host.encode("idna" if not _is_ip(host) else "ascii")
    if _is_ip(host):
        request = b"\x05\x01\x00\x01" + socket.inet_aton(host) + struct.pack("!H", port)
    else:
        request = (
            b"\x05\x01\x00\x03"
            + bytes([len(host_bytes)])
            + host_bytes
            + struct.pack("!H", port)
        )
    sock.sendall(request)

    head = sock.recv(4)
    if len(head) < 4:
        return "узел закрыл соединение до ответа"
    if head[1] != 0x00:
        return f"узел отказал, код {head[1]}"
    bound = {0x01: 4, 0x04: 16}.get(head[3])
    if bound is None:
        bound = sock.recv(1)[0]
    sock.recv(bound + 2)
    return ""


def _is_ip(value: str) -> bool:
    try:
        socket.inet_aton(value)
        return True
    except OSError:
        return False


def build_query(name: str) -> bytes:
    parts = b"".join(bytes([len(p)]) + p.encode() for p in name.split(".")) + b"\x00"
    header = struct.pack("!HHHHHH", 0x1234, 0x0100, 1, 0, 0, 0)
    return header + parts + struct.pack("!HH", 1, 1)  # A, IN


def parse_first_a(payload: bytes) -> str:
    answers = struct.unpack("!H", payload[6:8])[0]
    if answers == 0:
        return ""
    offset = 12
    while payload[offset] != 0:
        offset += payload[offset] + 1
    offset += 5  # нулевой байт + QTYPE + QCLASS
    for _ in range(answers):
        offset += 2  # указатель на имя
        rtype, _rclass, _ttl, rdlen = struct.unpack("!HHIH", payload[offset:offset + 10])
        offset += 10
        if rtype == 1 and rdlen == 4:
            return socket.inet_ntoa(payload[offset:offset + 4])
        offset += rdlen
    return ""


def probe(socks_port: int, resolver: str, name: str, timeout: float = 6.0) -> None:
    started = time.monotonic()
    try:
        with socket.create_connection(("127.0.0.1", socks_port), timeout) as sock:
            sock.settimeout(timeout)
            error = socks_connect(sock, resolver, 53)
            if error:
                print(f"  {resolver:<9} {name:<16} CONNECT не прошёл: {error}")
                return
            query = build_query(name)
            sock.sendall(struct.pack("!H", len(query)) + query)
            length_prefix = sock.recv(2)
            if len(length_prefix) < 2:
                print(f"  {resolver:<9} {name:<16} резолвер не ответил (соединение закрыто)")
                return
            expected = struct.unpack("!H", length_prefix)[0]
            payload = b""
            while len(payload) < expected:
                chunk = sock.recv(expected - len(payload))
                if not chunk:
                    break
                payload += chunk
            elapsed = (time.monotonic() - started) * 1000
            address = parse_first_a(payload) if len(payload) >= expected else ""
            verdict = address or "ответ без A-записи"
            print(f"  {resolver:<9} {name:<16} {verdict:<20} {elapsed:6.0f} мс")
    except Exception as error:  # noqa: BLE001 — диагностический скрипт
        print(f"  {resolver:<9} {name:<16} ошибка: {type(error).__name__}: {error}")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    socks_port = int(sys.argv[1])
    names = sys.argv[2:] or ["example.com", "google.com", "ya.ru"]
    print(f"DNS через SOCKS5 127.0.0.1:{socks_port}, режим OverTcp:")
    for resolver in ("1.1.1.1", "1.0.0.1", "8.8.8.8"):
        for name in names:
            probe(socks_port, resolver, name)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

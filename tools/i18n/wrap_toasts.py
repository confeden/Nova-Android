#!/usr/bin/env python3
"""
Wraps the text argument of every `Toast.makeText(context, text, duration)` in `NovaLanguage.tr`.

A toast is drawn by the system (by SystemUI since Android 11), so its text never passes through the
app's views and has to be translated before `makeText`. The script is idempotent: an argument that
already starts with `NovaLanguage.tr(` is left alone, and so is a resource id (`R.string.x`).

  python tools/i18n/wrap_toasts.py            # rewrite in place, print a summary
  python tools/i18n/wrap_toasts.py --check    # exit 1 if some toast is not wrapped
"""
import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
JAVA = os.path.join(ROOT, "app", "src", "main", "java")
CALL = "Toast.makeText("


def split_args(src, start):
    """src[start] is just after '('. Returns (list of (arg_start, arg_end)), index after ')'."""
    depth = 0
    args = []
    arg_start = start
    k = start
    n = len(src)
    while k < n:
        c = src[k]
        if src.startswith('"""', k):
            end = src.find('"""', k + 3)
            k = end + 3
            continue
        if c == '"':
            k += 1
            while k < n and src[k] != '"':
                if src[k] == "\\":
                    k += 2
                    continue
                if src[k] == "$" and k + 1 < n and src[k + 1] == "{":
                    # template expression: skip balanced braces
                    d = 1
                    k += 2
                    while k < n and d:
                        if src[k] == "{":
                            d += 1
                        elif src[k] == "}":
                            d -= 1
                        elif src[k] == '"':
                            k += 1
                            while k < n and src[k] != '"':
                                k += 2 if src[k] == "\\" else 1
                        k += 1
                    continue
                k += 1
            k += 1
            continue
        if c == "'":
            k += 1
            if src[k] == "\\":
                k += 2
            while src[k] != "'":
                k += 1
            k += 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            if depth == 0:
                args.append((arg_start, k))
                return args, k + 1
            depth -= 1
        elif c == "," and depth == 0:
            args.append((arg_start, k))
            arg_start = k + 1
        k += 1
    raise ValueError("unbalanced call at %d" % start)


def process(path, check):
    with open(path, encoding="utf-8") as f:
        src = f.read()
    out = []
    pos = 0
    wrapped = 0
    unwrapped = []
    while True:
        idx = src.find(CALL, pos)
        if idx < 0:
            break
        # skip occurrences inside comments on the same line
        line_start = src.rfind("\n", 0, idx) + 1
        if src[line_start:idx].lstrip().startswith(("//", "*")):
            out.append(src[pos:idx + len(CALL)])
            pos = idx + len(CALL)
            continue
        args, end = split_args(src, idx + len(CALL))
        if len(args) != 3:
            out.append(src[pos:end])
            pos = end
            continue
        ctx = src[args[0][0]:args[0][1]].strip()
        text_start, text_end = args[1]
        text = src[text_start:text_end]
        stripped = text.strip()
        if stripped.startswith("NovaLanguage.tr(") or stripped.startswith("R.string."):
            out.append(src[pos:end])
            pos = end
            continue
        unwrapped.append(src.count("\n", 0, idx) + 1)
        lead = text[: len(text) - len(text.lstrip())]
        trail = text[len(text.rstrip()):]
        out.append(src[pos:text_start])
        out.append("%sNovaLanguage.tr(%s, %s)%s" % (lead, ctx, stripped, trail))
        out.append(src[text_end:end])
        pos = end
        wrapped += 1
    out.append(src[pos:])
    if check:
        return unwrapped
    if wrapped:
        with open(path, "w", encoding="utf-8") as f:
            f.write("".join(out))
    return wrapped


def main():
    check = "--check" in sys.argv
    total = 0
    missing = []
    for base, _, files in os.walk(JAVA):
        for name in sorted(files):
            if not name.endswith(".kt"):
                continue
            path = os.path.join(base, name)
            result = process(path, check)
            if check:
                missing += ["%s:%d" % (os.path.relpath(path, ROOT), line) for line in result]
            elif result:
                print("%3d  %s" % (result, os.path.relpath(path, ROOT)))
                total += result
    if check:
        for m in missing:
            print("not wrapped:", m)
        sys.exit(1 if missing else 0)
    print("wrapped", total)


if __name__ == "__main__":
    main()

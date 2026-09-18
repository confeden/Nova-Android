#!/usr/bin/env python3
"""
Collects every Russian UI string of the app into one list — the source of the translation catalogs.

The app is written in Russian and the Russian literals stay in the code: they are exact search keys
for the logs and the knowledge base (I14). A translation catalog therefore maps a Russian source
text to its translation (`app/src/main/assets/i18n/<lang>.json`), and `NovaLanguage` looks the text
up at the moment it reaches the screen.

What counts as a UI string:
  * a Kotlin string literal containing Cyrillic that is **not** an argument of a logging call;
  * `android:text`, `android:hint`, `android:contentDescription` in layouts;
  * `android:label` of activities in the manifest (screen titles).

Kotlin templates become numbered placeholders: "Попытка $n из ${total}" -> "Попытка {0} из {1}".
Format strings get the same treatment: "%.1f МБ/с" -> "{0} МБ/с". Raw strings honour
`.trimIndent()` / `.trimMargin()` when the call follows the literal directly. A chain
`"a " + x + "b"` is folded into one source "a {0}b" — that is the text the screen receives.

Usage:
  python tools/i18n/extract_ru_strings.py --stats > strings.json
  python tools/i18n/extract_ru_strings.py --catalog app/src/main/assets/i18n/en.json --missing
  python tools/i18n/extract_ru_strings.py --catalog app/src/main/assets/i18n/en.json --unused
"""

import argparse
import html
import json
import os
import re
import sys
from collections import OrderedDict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MAIN = os.path.join(ROOT, "app", "src", "main")

CYRILLIC = re.compile(r"[\u0400-\u04FF]")
FORMAT_SPEC = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXeEgGc]")
LOG_HELPER = re.compile(r"(^|\.)log[A-Z]\w*$")
PLACEHOLDER = re.compile(r"\{(\d+)\}")


def is_log_call(name):
    if not name:
        return False
    if name in ("LogManager.log", "log", "println", "print"):
        return True
    if re.search(r"(^|\.)Log\.(d|i|w|e|v|wtf)$", name):
        return True
    # callbacks that end up in LogManager.log: `logger(...)`, `launchLogger(...)`, `onTun2ProxyLog(...)`
    if re.search(r"(^|\.)(\w*[lL]ogger|on\w*Log)$", name):
        return True
    return bool(LOG_HELPER.search(name))


class Literal:
    __slots__ = ("parts", "line", "calls", "raw", "trim", "start", "end")

    def __init__(self, parts, line, calls, raw, start, end):
        # parts: list of str (text) and None (placeholder)
        self.parts = parts
        self.line = line
        self.calls = calls
        self.raw = raw
        self.trim = None
        self.start = start
        self.end = end


ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "b": "\b", '"': '"', "'": "'", "\\": "\\", "$": "$"}


def lex_kotlin(src, base_line=1, base_calls=()):
    """Returns literals found in `src`, nested template literals included."""
    i = 0
    n = len(src)
    line = base_line
    call_stack = list(base_calls)
    literals = []
    last_ident = ""

    def skip_template_expr(j):
        """src[j] is just after '${'. Returns index just past the matching '}'."""
        depth = 1
        k = j
        while k < n:
            c = src[k]
            if c == '"':
                if src.startswith('"""', k):
                    end = src.find('"""', k + 3)
                    k = n if end < 0 else end + 3
                    continue
                k += 1
                while k < n and src[k] != '"':
                    if src[k] == "\\":
                        k += 2
                        continue
                    if src[k] == "$" and k + 1 < n and src[k + 1] == "{":
                        k = skip_template_expr(k + 2)
                        continue
                    k += 1
                k += 1
                continue
            if c == "'":
                k += 1
                if k < n and src[k] == "\\":
                    k += 2
                while k < n and src[k] != "'":
                    k += 1
                k += 1
                continue
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    return k + 1
            k += 1
        return n

    while i < n:
        c = src[i]
        if c == "\n":
            line += 1
            i += 1
            continue
        if src.startswith("//", i):
            end = src.find("\n", i)
            i = n if end < 0 else end
            continue
        if src.startswith("/*", i):
            depth = 1
            k = i + 2
            while k < n and depth > 0:
                if src.startswith("/*", k):
                    depth += 1
                    k += 2
                elif src.startswith("*/", k):
                    depth -= 1
                    k += 2
                else:
                    if src[k] == "\n":
                        line += 1
                    k += 1
            i = k
            continue
        if c == "'":
            k = i + 1
            if k < n and src[k] == "\\":
                k += 2
            while k < n and src[k] != "'":
                k += 1
            i = k + 1
            continue
        if c == "`":
            end = src.find("`", i + 1)
            i = n if end < 0 else end + 1
            continue
        if c == '"':
            start = i
            start_line = line
            raw = src.startswith('"""', i)
            parts = []
            nested = []
            calls_here = [x for x in call_stack if x]
            k = i + (3 if raw else 1)
            while k < n:
                ch = src[k]
                if raw and src.startswith('"""', k):
                    run = k
                    while run < n and src[run] == '"':
                        run += 1
                    if run - k > 3:
                        parts.append('"' * (run - k - 3))
                    k = run
                    break
                if not raw and ch == '"':
                    k += 1
                    break
                if not raw and ch == "\\" and k + 1 < n:
                    esc = src[k + 1]
                    if esc == "u":
                        parts.append(chr(int(src[k + 2:k + 6], 16)))
                        k += 6
                    else:
                        parts.append(ESCAPES.get(esc, esc))
                        k += 2
                    continue
                if ch == "$" and k + 1 < n and src[k + 1] == "{":
                    end = skip_template_expr(k + 2)
                    expr = src[k + 2:end - 1]
                    if expr.strip() == "'$'":
                        parts.append("$")
                    else:
                        parts.append(None)
                        inner = lex_kotlin(expr, line, calls_here)
                        for lit_inner in inner:
                            # offsets are relative to `expr`: keep them out of chain folding
                            lit_inner.start = -1
                            lit_inner.end = -1
                        nested.extend(inner)
                    line += src.count("\n", k, end)
                    k = end
                    continue
                if ch == "$" and k + 1 < n and (src[k + 1].isalpha() or src[k + 1] == "_"):
                    k += 1
                    while k < n and (src[k].isalnum() or src[k] == "_"):
                        k += 1
                    parts.append(None)
                    continue
                if ch == "\n":
                    line += 1
                parts.append(ch)
                k += 1
            lit = Literal(parts, start_line, calls_here, raw, start, k)
            if raw:
                m = re.match(r"\s*\.\s*(trimIndent|trimMargin)\s*\(\s*\)", src[k:k + 40])
                if m:
                    lit.trim = m.group(1)
                    lit.end = k + m.end()
            literals.append(lit)
            literals.extend(nested)
            i = k
            last_ident = ""
            continue
        if c.isalpha() or c == "_":
            k = i
            while k < n and (src[k].isalnum() or src[k] == "_"):
                k += 1
            ident = src[i:k]
            j = i - 1
            while j >= 0 and src[j] in " \t":
                j -= 1
            if last_ident and j >= 0 and src[j] == ".":
                last_ident = last_ident + "." + ident
            else:
                last_ident = ident
            i = k
            continue
        if c == "(":
            call_stack.append(last_ident)
            last_ident = ""
            i += 1
            continue
        if c == ")":
            if len(call_stack) > len(base_calls):
                call_stack.pop()
            last_ident = ""
            i += 1
            continue
        if c in ".?!" and last_ident:
            i += 1
            continue
        if c in " \t\r":
            i += 1
            continue
        last_ident = ""
        i += 1
    return literals


def render(parts):
    out = []
    idx = 0
    for p in parts:
        if p is None:
            out.append("{%d}" % idx)
            idx += 1
        else:
            out.append(p)
    return "".join(out)


def kotlin_trim_indent(text):
    lines = text.split("\n")
    if lines and not lines[0].strip():
        lines = lines[1:]
    if lines and not lines[-1].strip():
        lines = lines[:-1]
    indents = [len(l) - len(l.lstrip()) for l in lines if l.strip()]
    cut = min(indents) if indents else 0
    return "\n".join(l[cut:] if l.strip() else "" for l in lines)


def kotlin_trim_margin(text, marker="|"):
    lines = text.split("\n")
    if lines and not lines[0].strip():
        lines = lines[1:]
    if lines and not lines[-1].strip():
        lines = lines[:-1]
    out = []
    for l in lines:
        s = l.lstrip()
        out.append(s[len(marker):] if s.startswith(marker) else l)
    return "\n".join(out)


def normalize_format(text):
    """"%.1f МБ/с" -> "{0} МБ/с". Skipped when template placeholders are already there."""
    if "%" not in text or PLACEHOLDER.search(text):
        return text
    if not FORMAT_SPEC.search(text):
        return text
    counter = [0]

    def repl(m):
        if m.group(1):
            idx = int(m.group(1)[:-1]) - 1
        else:
            idx = counter[0]
            counter[0] += 1
        return "{%d}" % idx

    return FORMAT_SPEC.sub(repl, text).replace("%%", "%")


PLUS = re.compile(r"\s*\+\s*")
CHAIN_END = re.compile(r"\s*(?:[,)}\];]|\n|$|else\b|->|\?:)")


def skip_balanced(src, i):
    """src[i] is an opening bracket; returns the index just past its partner, strings respected."""
    pairs = {"(": ")", "[": "]", "{": "}"}
    stack = [pairs[src[i]]]
    k = i + 1
    n = len(src)
    while k < n and stack:
        c = src[k]
        if src.startswith('"""', k):
            end = src.find('"""', k + 3)
            k = n if end < 0 else end + 3
            continue
        if c == '"':
            k += 1
            while k < n and src[k] != '"':
                if src[k] == "\\":
                    k += 2
                    continue
                if src[k] == "$" and k + 1 < n and src[k + 1] == "{":
                    k = skip_balanced(src, k + 1)
                    continue
                k += 1
            k += 1
            continue
        if c == "'":
            k += 1
            if k < n and src[k] == "\\":
                k += 2
            while k < n and src[k] != "'":
                k += 1
            k += 1
            continue
        if c in pairs:
            stack.append(pairs[c])
        elif c == stack[-1]:
            stack.pop()
        k += 1
    return k


def scan_operand(src, i):
    """
    A postfix expression that is not a string literal: `a.b?.c()`, `foo(x, "y")`,
    `(if (x) " ($h)" else "")`, `list.joinToString(", ") { it }`. Returns the end index or -1.
    """
    n = len(src)
    k = i
    if k < n and src[k] == "(":
        k = skip_balanced(src, k)
    elif k < n and (src[k].isalpha() or src[k] == "_"):
        while k < n and (src[k].isalnum() or src[k] == "_"):
            k += 1
    else:
        return -1
    while k < n:
        m = re.match(r"\s*(\?\.|\.|!!)", src[k:k + 8])
        if src.startswith("(", k) or src.startswith("[", k):
            k = skip_balanced(src, k)
            continue
        if re.match(r"[ \t]*\{", src[k:k + 8]):
            # trailing lambda: `joinToString { it }`, `ifBlank { "—" }`
            brace = src.index("{", k)
            k = skip_balanced(src, brace)
            continue
        if m and (m.group(1) == "!!" or re.match(r"\s*(\?\.|\.)\s*[A-Za-z_]", src[k:k + 16])):
            k += m.end()
            if m.group(1) != "!!":
                while k < n and src[k] in " \t\n":
                    k += 1
                while k < n and (src[k].isalnum() or src[k] == "_"):
                    k += 1
            continue
        break
    return k


def fold_chains(src, literals):
    """Folds `"a " + x + "b"` into one literal with a placeholder for x."""
    by_start = {lit.start: lit for lit in literals}
    consumed = set()
    folded = []
    for lit in literals:
        if id(lit) in consumed:
            continue
        if lit.start < 0 or (lit.raw and lit.trim):
            folded.append(lit)
            continue
        parts = list(lit.parts)
        pos = lit.end
        members = [lit]
        while True:
            m = PLUS.match(src, pos)
            if not m or src.startswith("+=", src.index("+", pos)):
                break
            nxt = m.end()
            if nxt < len(src) and src[nxt] == '"' and nxt in by_start and not by_start[nxt].raw:
                other = by_start[nxt]
                parts.extend(other.parts)
                members.append(other)
                pos = other.end
                continue
            after = scan_operand(src, nxt)
            if after > nxt:
                if PLUS.match(src, after):
                    parts.append(None)
                    pos = after
                    continue
                if CHAIN_END.match(src, after):
                    parts.append(None)
                    pos = after
                break
            break
        if len(members) > 1 or len(parts) != len(lit.parts):
            for mbr in members[1:]:
                consumed.add(id(mbr))
            merged = Literal(parts, lit.line, lit.calls, False, lit.start, pos)
            folded.append(merged)
        else:
            folded.append(lit)
    return folded


# Files whose Cyrillic literals must never enter a catalog. NovaLanguage.kt holds the native names of
# the languages ("Русский"): the picker shows them untranslated on purpose.
EXCLUDED_FILES = {"NovaLanguage.kt", "NovaTranslationCatalog.kt"}


def collect_kotlin():
    found = []
    for base, _, files in os.walk(os.path.join(MAIN, "java")):
        for name in sorted(files):
            if not name.endswith(".kt") or name in EXCLUDED_FILES:
                continue
            path = os.path.join(base, name)
            with open(path, encoding="utf-8") as f:
                src = f.read()
            rel = os.path.relpath(path, ROOT).replace("\\", "/")
            for lit in fold_chains(src, lex_kotlin(src)):
                text = render(lit.parts)
                if not CYRILLIC.search(text):
                    continue
                if lit.trim == "trimIndent":
                    text = kotlin_trim_indent(text)
                elif lit.trim == "trimMargin":
                    text = kotlin_trim_margin(text)
                text = normalize_format(text)
                log = any(is_log_call(c) for c in lit.calls)
                found.append({"text": text, "ref": "%s:%d" % (rel, lit.line), "log": log})
    return found


XML_ATTR = re.compile(r'\b(android:text|android:hint|android:contentDescription)="([^"]*)"')


def decode_android_xml_value(value):
    value = html.unescape(value)
    out = []
    i = 0
    while i < len(value):
        ch = value[i]
        if ch == "\\" and i + 1 < len(value):
            nxt = value[i + 1]
            out.append({"n": "\n", "t": "\t"}.get(nxt, nxt))
            i += 2
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def strip_xml_comments(src):
    return re.sub(r"<!--.*?-->", lambda m: "\n" * m.group(0).count("\n"), src, flags=re.S)


def collect_xml():
    found = []
    base = os.path.join(MAIN, "res", "layout")
    for name in sorted(os.listdir(base)):
        if not name.endswith(".xml"):
            continue
        path = os.path.join(base, name)
        with open(path, encoding="utf-8") as f:
            src = strip_xml_comments(f.read())
        rel = os.path.relpath(path, ROOT).replace("\\", "/")
        for m in XML_ATTR.finditer(src):
            text = decode_android_xml_value(m.group(2))
            if not CYRILLIC.search(text):
                continue
            line = src.count("\n", 0, m.start()) + 1
            found.append({"text": text, "ref": "%s:%d" % (rel, line), "log": False})
    return found


MANIFEST_LABEL = re.compile(r'\bandroid:label="([^"@]*)"')


def collect_manifest():
    """Activity labels: the screen title TalkBack announces and NovaLanguage re-titles."""
    path = os.path.join(MAIN, "AndroidManifest.xml")
    with open(path, encoding="utf-8") as f:
        src = strip_xml_comments(f.read())
    rel = os.path.relpath(path, ROOT).replace("\\", "/")
    found = []
    for m in MANIFEST_LABEL.finditer(src):
        text = decode_android_xml_value(m.group(1))
        if CYRILLIC.search(text):
            line = src.count("\n", 0, m.start()) + 1
            found.append({"text": text, "ref": "%s:%d" % (rel, line), "log": False})
    return found


def build_report():
    entries = OrderedDict()
    for item in collect_xml() + collect_manifest() + collect_kotlin():
        e = entries.setdefault(item["text"], {"text": item["text"], "refs": [], "ui": False})
        e["refs"].append(item["ref"])
        if not item["log"]:
            e["ui"] = True
    return list(entries.values())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--catalog", help="translation catalog JSON (source -> translation)")
    ap.add_argument("--missing", action="store_true", help="UI sources the catalog lacks")
    ap.add_argument("--unused", action="store_true", help="catalog keys no code produces")
    ap.add_argument("--all", action="store_true", help="include log-only literals")
    ap.add_argument("--stats", action="store_true")
    args = ap.parse_args()

    report = build_report()
    ui = [e for e in report if e["ui"] or args.all]
    if args.stats:
        print("unique UI sources:", len(ui), file=sys.stderr)
        print("with placeholders:", len([e for e in ui if PLACEHOLDER.search(e["text"])]), file=sys.stderr)
        print("unique log-only sources:", len([e for e in report if not e["ui"]]), file=sys.stderr)
        print("chars:", sum(len(e["text"]) for e in ui), file=sys.stderr)
    if args.catalog:
        with open(args.catalog, encoding="utf-8") as f:
            catalog = json.load(f)
        if args.missing:
            json.dump([e for e in ui if e["text"] not in catalog], sys.stdout, ensure_ascii=False, indent=1)
            return
        if args.unused:
            known = {e["text"] for e in report}
            json.dump([k for k in catalog if k not in known], sys.stdout, ensure_ascii=False, indent=1)
            return
    json.dump(ui, sys.stdout, ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()

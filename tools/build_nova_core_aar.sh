#!/usr/bin/env bash
#
# Сборка ядра nova-core в .aar и раскладка нативных библиотек.
#
# Собирает `gomobile bind`, снимает символы с `libgojni.so` и кладёт результат в
# два места сразу:
#
#   * app/libs/nova-core-api24-stripped.aar — сюда смотрит app/build.gradle.kts;
#   * app/src/main/jniLibs/<abi>/libgojni.so — эти файлы имеют приоритет над
#     `jni/` внутри .aar.
#
# Обновлять надо оба места. Если положить только .aar, на реальных устройствах
# продолжит грузиться старая библиотека из jniLibs, и новый экспортированный
# метод даст UnsatisfiedLinkError — снаружи это выглядит как «правка не
# подействовала».
#
# Нестрипнутая сборка сохраняется рядом как nova-core-api24.aar: с символами
# читаются нативные стеки из отчётов об аварийном завершении.
#
# Использование:
#   tools/build_nova_core_aar.sh [--ndk <версия>]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK_VERSION="27.2.12479018"

while [ $# -gt 0 ]; do
    case "$1" in
        --ndk)
            NDK_VERSION="$2"
            shift 2
            ;;
        *)
            echo "Неизвестный аргумент: $1" >&2
            exit 2
            ;;
    esac
done

if [ -z "${ANDROID_HOME:-}" ]; then
    sdk_dir="$(sed -n 's/^sdk\.dir=//p' "$REPO_ROOT/local.properties" | tr -d '\r' | sed 's/\\\\/\//g; s/\\//g')"
    if [ -z "$sdk_dir" ]; then
        echo "Не найден sdk.dir в local.properties и не задан ANDROID_HOME." >&2
        exit 1
    fi
    ANDROID_HOME="$sdk_dir"
fi
export ANDROID_HOME
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$ANDROID_HOME/ndk/$NDK_VERSION}"

if [ ! -d "$ANDROID_NDK_HOME" ]; then
    echo "NDK не найден: $ANDROID_NDK_HOME" >&2
    exit 1
fi

host_tag="windows-x86_64"
case "$(uname -s)" in
    Linux*) host_tag="linux-x86_64" ;;
    Darwin*) host_tag="darwin-x86_64" ;;
esac
LLVM_STRIP="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin/llvm-strip"
[ -x "$LLVM_STRIP" ] || LLVM_STRIP="$LLVM_STRIP.exe"
if [ ! -x "$LLVM_STRIP" ]; then
    echo "llvm-strip не найден: $LLVM_STRIP" >&2
    exit 1
fi

PYTHON="${PYTHON:-}"
if [ -z "$PYTHON" ]; then
    for candidate in python3 python py; do
        if command -v "$candidate" >/dev/null 2>&1; then
            PYTHON="$candidate"
            break
        fi
    done
fi
if [ -z "$PYTHON" ]; then
    echo "Не найден python: он нужен для перепаковки .aar (zip есть не на всех машинах)." >&2
    exit 1
fi

FULL_AAR="$REPO_ROOT/app/libs/nova-core-api24.aar"
STRIPPED_AAR="$REPO_ROOT/app/libs/nova-core-api24-stripped.aar"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "==> gomobile bind (androidapi 24)"
(cd "$REPO_ROOT/nova-core" && gomobile bind -androidapi 24 -target=android -o "$WORK_DIR/nova-core.aar" .)

echo "==> распаковка"
unzip -q "$WORK_DIR/nova-core.aar" -d "$WORK_DIR/aar"

echo "==> снятие символов"
for so in "$WORK_DIR"/aar/jni/*/libgojni.so; do
    before="$(wc -c <"$so")"
    "$LLVM_STRIP" --strip-all "$so"
    after="$(wc -c <"$so")"
    echo "    $(basename "$(dirname "$so")"): $before -> $after"
done

echo "==> упаковка"
cp "$WORK_DIR/nova-core.aar" "$FULL_AAR"
cp "$WORK_DIR/nova-core-sources.jar" "$REPO_ROOT/app/libs/nova-core-api24-sources.jar" 2>/dev/null || true
rm -f "$STRIPPED_AAR"
"$PYTHON" - "$WORK_DIR/nova-core.aar" "$WORK_DIR/aar" "$STRIPPED_AAR" <<'PY'
import os
import sys
import zipfile

source_aar, tree, target = sys.argv[1:4]

# Порядок и способ сжатия записей берём из исходного .aar: так стрипнутая
# сборка отличается от полной только содержимым .so.
with zipfile.ZipFile(source_aar) as source, zipfile.ZipFile(
    target, "w", zipfile.ZIP_DEFLATED
) as out:
    for item in source.infolist():
        path = os.path.join(tree, item.filename)
        if item.is_dir():
            out.writestr(item, b"")
            continue
        with open(path, "rb") as handle:
            payload = handle.read()
        entry = zipfile.ZipInfo(item.filename, date_time=item.date_time)
        entry.compress_type = item.compress_type
        entry.external_attr = item.external_attr
        out.writestr(entry, payload)
PY
cp "$REPO_ROOT/app/libs/nova-core-api24-sources.jar" \
    "$REPO_ROOT/app/libs/nova-core-api24-stripped-sources.jar" 2>/dev/null || true

echo "==> раскладка jniLibs"
for abi in arm64-v8a armeabi-v7a; do
    target_dir="$REPO_ROOT/app/src/main/jniLibs/$abi"
    if [ -d "$target_dir" ]; then
        cp "$WORK_DIR/aar/jni/$abi/libgojni.so" "$target_dir/libgojni.so"
        echo "    $abi <- $(wc -c <"$target_dir/libgojni.so") байт"
    fi
done

echo "Готово."

#!/usr/bin/env bash
#
# Сборка libtun2proxy.so из исходников.
#
# Почему тег закреплён именно на v0.7.21. Nova зовёт C-функцию
# `tun2proxy_with_fd_run` (объявлена в `app/src/main/cpp/include/tun2proxy.h`),
# а upstream убрал эти обёртки коммитом «remove legacy C export wrappers»:
# начиная с v0.8.0 такого символа нет вовсе, и JNI-слой перестал бы линковаться.
# v0.7.21 — последняя версия, где сигнатура совпадает с нашим заголовком:
# proxy_url, tun_fd, close_fd_on_drop, packet_information, tun_mtu,
# dns_strategy, verbosity. Подниматься выше без правки заголовка и native-lib.cpp
# нельзя.
#
# Требуется: rustup с целями aarch64-linux-android и armv7-linux-androideabi,
# cargo-ndk и Android NDK.
#
#   rustup target add aarch64-linux-android armv7-linux-androideabi
#   cargo install cargo-ndk --locked
#
# Запуск из корня проекта:
#   ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.2.12479018 tools/build_tun2proxy.sh
set -euo pipefail

REPO="${TUN2PROXY_REPO:-https://github.com/tun2proxy/tun2proxy}"
TAG="${TUN2PROXY_TAG:-v0.7.21}"
API="${TUN2PROXY_API:-24}"

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_dir="${TUN2PROXY_SRC:-$root_dir/build/deps/tun2proxy}"
out_dir="$root_dir/app/src/main/jniLibs"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ANDROID_NDK_HOME не задан" >&2
    exit 1
fi

if [ ! -d "$work_dir/.git" ]; then
    echo "==> клонируем $REPO@$TAG"
    mkdir -p "$(dirname "$work_dir")"
    git init -q "$work_dir"
    git -C "$work_dir" remote add origin "$REPO"
    git -C "$work_dir" fetch -q --depth 1 origin "refs/tags/$TAG:refs/tags/$TAG"
fi
# `-f`: дерево должно быть ровно тем, что в теге, иначе патч ниже ляжет второй раз.
git -C "$work_dir" checkout -q -f "$TAG"

# Патч воспроизводимости. `build.rs` зашивал в бинарник `chrono::Utc::now()`, и
# две сборки одного тега давали разные байты — найдено diffoscope: расходилась
# строка версии в `.rodata`, а следом и суффиксы `.llvm.<n>` у локальных
# символов того же модуля. После патча время берётся из окружения.
git -C "$work_dir" apply "$root_dir/tools/deps/patches/tun2proxy.patch"

# Свой Cargo.lock. Upstream держит его в .gitignore, то есть версии зависимостей
# разрешаются заново на каждой сборке: два клона одного тега, сделанные в разные
# дни, дают разный libtun2proxy.so (поймано — расходились и .text, и .rodata,
# пока не сравнили сами Cargo.lock: 0.7.39 против 0.7.40 у пары транзитивных
# крейтов). Файл рядом фиксирует весь граф; `--locked` ниже требует, чтобы cargo
# именно его и использовал, а не молча обновил.
cp "$root_dir/tools/deps/tun2proxy-Cargo.lock" "$work_dir/Cargo.lock"

# `build.rs` подставляет GIT_HASH только если рядом есть полная история git, а
# у shallow-клона по тегу её нет — сборка падала бы на `env!("GIT_HASH")`.
# BUILD_TIME берём из даты коммита самого тега (UTC): значение осмысленное и
# одинаковое на любой машине в любой день.
export GIT_HASH="${TAG}"
BUILD_TIME="$(TZ=UTC git -C "$work_dir" log -1 --format=%cd --date=format-local:'%Y-%m-%d %H:%M:%S')"
export BUILD_TIME

# Пути в артефакт не зашиваем. Rust иначе кладёт в libtun2proxy.so абсолютные
# пути каталога сборки и реестра crates, из-за чего два прогона дают разные
# байты, — поймано на сравнении воспроизводимой сборки F-Droid. Каталог реестра
# берём у самого cargo: он зависит от CARGO_HOME, а тот на разных машинах разный.
#
# Флаги идут через CARGO_ENCODED_RUSTFLAGS, а не RUSTFLAGS: RUSTFLAGS cargo
# делит по пробелам, и на пути с пробелом («Nova Android») сборка падала с
# «--remap-path-prefix must contain '='». Разделитель здесь — 0x1f, пробелы
# внутри значения безопасны.
cargo_home="${CARGO_HOME:-$HOME/.cargo}"
rust_flags=()
if [ -n "${RUSTFLAGS:-}" ]; then
    read -r -a rust_flags <<<"$RUSTFLAGS"
    unset RUSTFLAGS
fi
rust_flags+=("--remap-path-prefix=$work_dir=/build" "--remap-path-prefix=$cargo_home=/cargo")
unit_sep=$'\x1f'
encoded=""
for flag in "${rust_flags[@]}"; do
    encoded="${encoded:+$encoded$unit_sep}$flag"
done
export CARGO_ENCODED_RUSTFLAGS="$encoded"

echo "==> cargo ndk (arm64-v8a, armeabi-v7a)"
(
    cd "$work_dir"
    cargo ndk -t arm64-v8a -t armeabi-v7a --platform "$API" build --release --locked
)

cp "$work_dir/target/aarch64-linux-android/release/libtun2proxy.so" "$out_dir/arm64-v8a/libtun2proxy.so"
cp "$work_dir/target/armv7-linux-androideabi/release/libtun2proxy.so" "$out_dir/armeabi-v7a/libtun2proxy.so"
ls -l "$out_dir/arm64-v8a/libtun2proxy.so" "$out_dir/armeabi-v7a/libtun2proxy.so"

echo "Готово."

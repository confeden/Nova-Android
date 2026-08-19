#!/usr/bin/env bash
#
# Сборка liboperaproxy.so из исходников.
#
# Откуда исходники. В APK лежал бинарник, у которого `go version -m` показывает
# `github.com/Snawoot/opera-proxy v1.15.0-fork+dirty` с вендоренным
# `pkg/go-http-digest-auth-client`. Сам `github.com/Snawoot/opera-proxy` с
# GitHub исчез, и это делало сборку невоспроизводимой: пересобрать бинарник не
# мог никто, включая нас. Живая копия под тем же module path сохранилась в
# `snawoot-proxies-forks/opera-proxy`, и на теге `v1.15.0-fork` совпадает всё:
# module path, версия, вендоренный пакет и весь список зависимостей.
#
# Почему это важно не только для F-Droid: без исходника любая правка в
# opera-proxy была невозможна в принципе.
#
# Внимание: файл называется `lib*.so`, но это **исполняемый файл**, а не
# библиотека — Nova запускает его как процесс. Имя нужно, чтобы Android
# распаковал его из APK и оставил исполняемым.
#
# Требуется: Go 1.24+ и Android NDK (для armeabi-v7a обязателен cgo).
#
# Запуск из корня проекта:
#   ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.2.12479018 tools/build_opera_proxy.sh
set -euo pipefail

REPO="${OPERA_PROXY_REPO:-https://github.com/snawoot-proxies-forks/opera-proxy}"
# Тег закреплён: ровно та версия, что стояла в релизах Nova до перехода на
# сборку из исходников.
TAG="${OPERA_PROXY_TAG:-v1.15.0-fork}"

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_dir="${OPERA_PROXY_SRC:-$root_dir/build/deps/opera-proxy}"
out_dir="$root_dir/app/src/main/jniLibs"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "ANDROID_NDK_HOME не задан" >&2
    exit 1
fi

case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) host_tag="windows-x86_64"; clang_ext=".cmd" ;;
    Darwin)               host_tag="darwin-x86_64"; clang_ext="" ;;
    *)                    host_tag="linux-x86_64";  clang_ext="" ;;
esac
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin"

if [ ! -d "$work_dir/.git" ]; then
    echo "==> клонируем $REPO@$TAG"
    mkdir -p "$(dirname "$work_dir")"
    git init -q "$work_dir"
    git -C "$work_dir" remote add origin "$REPO"
    git -C "$work_dir" fetch -q --depth 1 origin "refs/tags/$TAG:refs/tags/$TAG"
fi
git -C "$work_dir" checkout -q "$TAG"

# `-buildvcs=false` по той же причине, что и в nova-xray/build.sh: Go иначе
# штампует в библиотеку ревизию и время коммита того репозитория, в котором идёт
# сборка, и артефакт перестаёт зависеть только от исходного кода.
build_one() {
    local abi="$1" goarch="$2" cc="$3"
    echo "==> $abi"
    (
        cd "$work_dir"
        if [ -n "$cc" ]; then
            CGO_ENABLED=1 GOOS=android GOARCH="$goarch" GOARM=7 CC="$cc" \
                go build -trimpath -buildvcs=false -ldflags="-s -w" -o "$out_dir/$abi/liboperaproxy.so" .
        else
            CGO_ENABLED=0 GOOS=android GOARCH="$goarch" \
                go build -trimpath -buildvcs=false -ldflags="-s -w" -o "$out_dir/$abi/liboperaproxy.so" .
        fi
    )
    ls -l "$out_dir/$abi/liboperaproxy.so"
}

# arm64 собирается чистым Go. Для 32-битного ARM Go требует внешний
# компоновщик, поэтому там обязателен clang из NDK.
build_one arm64-v8a arm64 ""
build_one armeabi-v7a arm "$toolchain/armv7a-linux-androideabi24-clang$clang_ext"

echo "Готово."

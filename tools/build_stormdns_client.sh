#!/usr/bin/env bash
#
# Сборка libstormdns.so — клиента DNS-туннеля семейства MasterDNS/StormDNS/CottenDNS.
#
# Почему именно StormDNS, а не CottenDNS. Замер 2026-09-20 на живых точках
# выхода: клиент StormDNS поднимает **и** сервер StormDNS (`de1.iran.qzz.io`,
# ссылка `stormdns://`), **и** сервер CottenDNS (`vpn.de.prtw.ru`, ссылка
# `cottendns://`) — 6 резолверов из 6, MTU 138/3386, то есть ровно то же, что
# даёт родной клиент CottenDNS. Обратно не работает: клиент CottenDNS против
# сервера StormDNS отвергает все несущие (13 попыток подряд, `UPLOAD_MTU=0`).
# Один клиент вместо двух — и он же вдвое меньше: 4,8 + 4,6 МБ против 8,5 + 8,3.
#
# Внимание: файл называется `lib*.so`, но это **исполняемый файл**, а не
# библиотека — Nova запускает его как процесс, ровно как liboperaproxy.so. Имя
# нужно, чтобы Android распаковал его из APK и оставил исполняемым.
#
# Лицензия StormDNS — MIT (цепочка атрибуции MasterDnsVPN -> StormDNS), с
# GPL-3.0-only совместима.
#
# Требуется: Go 1.25+ и Android NDK (cgo обязателен для обеих ABI: внешний
# компоновщик нужен ради выравнивания на 16 КБ страницы).
#
# Запуск из корня проекта:
#   ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.2.12479018 tools/build_stormdns_client.sh
set -euo pipefail

REPO="${STORMDNS_REPO:-https://github.com/nullroute1970/StormDNS}"
# Коммит закреплён, а не ветка: у проекта нет тегов, и «последний master» через
# месяц дал бы другой бинарник — воспроизводимость сломалась бы молча (I15).
COMMIT="${STORMDNS_COMMIT:-ca2eb481fddd4a80d26b3a1a7c714b5aecacecfc}"

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
work_dir="${STORMDNS_SRC:-$root_dir/build/deps/stormdns}"
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
    echo "==> клонируем $REPO@$COMMIT"
    mkdir -p "$(dirname "$work_dir")"
    git init -q "$work_dir"
    git -C "$work_dir" remote add origin "$REPO"
fi
git -C "$work_dir" fetch -q --depth 1 origin "$COMMIT"
# Патч Nova (tools/stormdns/nova.patch) пишется в LF, и дерево обязано быть в LF,
# иначе на Windows с core.autocrlf=true `git apply` отвергнет каждую строку.
# Дерево сбрасывается целиком: патч накладывается всегда на чистый коммит.
git -C "$work_dir" config core.autocrlf false
git -C "$work_dir" checkout -q -f "$COMMIT"
git -C "$work_dir" rm -q -r --cached .
git -C "$work_dir" reset -q --hard "$COMMIT"
git -C "$work_dir" clean -q -f -d
# Диалект ключа Nova («nova1:…», см. internal/security/nova_dialect.go) — только
# с ним клиент говорит с нашим сервером; ключи без префикса выводятся как у
# апстрима, так что чужие ссылки stormdns:// и cottendns:// работают как раньше.
# Серверная половина того же патча (фильтр исходящих) клиенту безвредна.
git -C "$work_dir" apply "$root_dir/tools/stormdns/nova.patch"

# API 24 — наш minSdk. Выравнивание на 16 КБ обязательно: устройства с такими
# страницами не запустят бинарник, собранный под 4 КБ, а понять это по
# сообщению загрузчика почти невозможно.
api="${ANDROID_API:-24}"
ldflags='-s -w -buildid= -linkmode external -extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"'

# `-buildvcs=false` по той же причине, что в build_opera_proxy.sh: иначе Go
# штампует в бинарник ревизию репозитория, в котором идёт сборка, и артефакт
# перестаёт зависеть только от исходного кода.
build_one() {
    local abi="$1" goarch="$2" cc="$3" goarm="${4:-}"
    echo "==> $abi"
    mkdir -p "$out_dir/$abi"
    (
        cd "$work_dir"
        # `env`: слово после раскрытия `${goarm:+…}` bash уже не считает
        # присваиванием, и без `env` пустой goarm превращал `CC=…` в команду.
        env CGO_ENABLED=1 GOOS=android GOARCH="$goarch" ${goarm:+GOARM=$goarm} CC="$cc" \
            go build -trimpath -buildvcs=false -ldflags="$ldflags" \
            -o "$out_dir/$abi/libstormdns.so" ./cmd/client
    )
    ls -l "$out_dir/$abi/libstormdns.so"
}

build_one arm64-v8a arm64 "$toolchain/aarch64-linux-android$api-clang$clang_ext"
build_one armeabi-v7a arm "$toolchain/armv7a-linux-androideabi$api-clang$clang_ext" 7

echo "Готово."

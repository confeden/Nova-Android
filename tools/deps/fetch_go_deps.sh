#!/usr/bin/env bash
#
# Раскладывает Go-зависимости nova-core ровно в том виде, в каком они собраны у нас.
#
# `nova-core/go.mod` подключает пять проектов через `replace`, то есть по путям на
# диске, а не по версиям из прокси. Три из них у нас пропатчены, и патчи меняют
# поведение: без них ядро соберётся, но будет другим. Клонировать «просто последнюю
# версию upstream», как раньше советовала инструкция, значит получить не ту сборку —
# поэтому здесь и коммит зафиксирован, и патч лежит рядом.
#
# Сами каталоги в репозиторий не кладутся: один gvisor весит больше всего остального
# вместе взятого. В репозитории — пины и патчи, каталоги делает этот скрипт.
#
# Запуск из корня проекта:
#   tools/deps/fetch_go_deps.sh
#
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
patches_dir="$root_dir/tools/deps/patches"

# путь|репозиторий|коммит|патч (патч необязателен)
deps=(
    "tools/amneziawg-go|https://github.com/amnezia-vpn/amneziawg-go|b5928efb6ca19f0153958460c3d141f04abc5c2e|amneziawg-go.patch"
    "tools/warp-plus|https://github.com/bepass-org/warp-plus|f70ea7e4f193717c73f9a4357cbc98d6944b36bb|warp-plus.patch"
    "build/deps/usque|https://github.com/Diniboy1123/usque|d0eb96e7e5c56cce6cf34a7f8d75abbedba58fef|"
    "build/deps/gvisor|https://github.com/google/gvisor|af7a19336e551af6f2fa050e1749bc5d2f1eeea5|gvisor.patch"
    # anet патчится ради компоновки, а не поведения: он писал в приватный кэш зон
    # IPv6 стандартной библиотеки через `go:linkname`, Go 1.23 такие ссылки
    # запретил, и `gomobile bind` падал на `invalid reference to net.zoneCache`.
    # Без этого патча snowflake в ядро не собирается вовсе (G176).
    "tools/anet|https://github.com/wlynxg/anet|839bc3a920f1b87dd3ce1386e425aa5ef2e69d24|anet.patch"
)

for entry in "${deps[@]}"; do
    IFS='|' read -r rel repo commit patch <<<"$entry"
    target="$root_dir/$rel"

    if [ -d "$target/.git" ]; then
        # Сверяем ревизию, а не только наличие каталога.
        #
        # Прежний страж пропускал любой уже склонированный каталог, поэтому
        # смена пина в этом файле ничего не меняла на машине, где скрипт хоть раз
        # отработал: сборка молча продолжала идти на старой версии, а патч не
        # переналагался. Ровно это и случилось бы при переходе amneziawg-go на 3.x.
        have="$(git -C "$target" rev-parse HEAD 2>/dev/null || true)"
        if [ "$have" = "$commit" ]; then
            echo "== $rel: уже на пине $commit, пропускаем"
            continue
        fi
        echo "== $rel: на месте $have, а нужен $commit — пересоздаём"
        rm -rf "$target"
    fi

    echo "== $rel: клонируем $repo"
    mkdir -p "$(dirname "$target")"
    git init -q "$target"
    git -C "$target" remote add origin "$repo"
    # Тянем один коммит вместо всей истории: gvisor целиком — это гигабайты.
    git -C "$target" fetch -q --depth 1 origin "$commit"
    git -C "$target" checkout -q FETCH_HEAD

    if [ -n "$patch" ]; then
        echo "== $rel: накладываем $patch"
        git -C "$target" apply "$patches_dir/$patch"
    fi
done

# Готовые бинарники из anet убираются, и не для порядка.
#
# В репозитории anet лежит демо-пример gomobile — `mobile/libs/mobile.aar` и
# `mobile-sources.jar`. Сборке они не нужны: пакет `anet/mobile` не импортирует
# никто, ядро берёт только корневой `anet`. Но сканер F-Droid отказывает во всей
# сборке, увидев в дереве готовый AAR или JAR, — ровно так и упал эталонный
# прогон 1.32.2: «Found Android AAR library at tools/anet/mobile/libs/mobile.aar
# … Can't build due to 2 errors while scanning». Сканер идёт после `prebuild`, где
# и работает этот скрипт, поэтому удалить их здесь достаточно.
#
# Здесь, а не `scandelete` в рецепте: так дерево чистое у любого, кто собирает
# из исходников, а рецепт F-Droid не расходится с ними на каждом выпуске.
# Вне цикла намеренно — ветка «уже на пине, пропускаем» выходит из итерации до
# накладки патча, а убрать бинарники надо и в уже разложенном дереве.
rm -rf "$root_dir/tools/anet/mobile/libs"

# snowflake раскладывается не клоном, а из кэша модулей.
#
# Его канонический репозиторий — gitlab.torproject.org, и он недоступен с части
# сетей (соединение рвётся), а живого зеркала с тегами v2.x нет: keroserene/snowflake
# на GitHub остановился на webext-тегах. Кэш модулей при этом сверен по контрольной
# сумме из go.sum, то есть источник тут строже, чем произвольное зеркало.
snowflake_version="v2.14.1"
snowflake_module="gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2"
snowflake_target="$root_dir/tools/snowflake"
snowflake_have=""
[ -f "$snowflake_target/.version" ] && snowflake_have="$(cat "$snowflake_target/.version")"
if [ "$snowflake_have" = "$snowflake_version" ]; then
    echo "== tools/snowflake: уже на $snowflake_version, пропускаем"
else
    echo "== tools/snowflake: берём $snowflake_version из кэша модулей"
    rm -rf "$snowflake_target"
    (cd "$root_dir/nova-core" && GOFLAGS=-mod=mod go mod download "$snowflake_module@$snowflake_version")
    snowflake_src="$(cd "$root_dir/nova-core" && go env GOMODCACHE)/$snowflake_module@$snowflake_version"
    cp -r "$snowflake_src" "$snowflake_target"
    chmod -R u+w "$snowflake_target"
    echo "== tools/snowflake: накладываем snowflake.patch"
    (cd "$snowflake_target" && git apply "$patches_dir/snowflake.patch")
    printf '%s' "$snowflake_version" > "$snowflake_target/.version"
fi

echo
echo "Готово. Проверить сборку ядра: (cd nova-core && GOOS=linux GOARCH=arm64 go build ./engine)"

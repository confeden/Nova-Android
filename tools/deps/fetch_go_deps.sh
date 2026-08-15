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
    "tools/amneziawg-go|https://github.com/amnezia-vpn/amneziawg-go|12a012205e3c444be02aba91a840455f74c127e1|amneziawg-go.patch"
    "tools/warp-plus|https://github.com/bepass-org/warp-plus|f70ea7e4f193717c73f9a4357cbc98d6944b36bb|warp-plus.patch"
    "build/deps/usque|https://github.com/Diniboy1123/usque|d0eb96e7e5c56cce6cf34a7f8d75abbedba58fef|"
    "build/deps/gvisor|https://github.com/google/gvisor|af7a19336e551af6f2fa050e1749bc5d2f1eeea5|gvisor.patch"
)

for entry in "${deps[@]}"; do
    IFS='|' read -r rel repo commit patch <<<"$entry"
    target="$root_dir/$rel"

    if [ -d "$target/.git" ]; then
        echo "== $rel: уже на месте, пропускаем"
        continue
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

echo
echo "Готово. Проверить сборку ядра: (cd nova-core && GOOS=linux GOARCH=arm64 go build ./engine)"

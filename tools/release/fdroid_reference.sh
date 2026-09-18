#!/usr/bin/env bash
# Эталонный APK F-Droid для выпуска — половина `build_release.sh`, которая идёт в WSL.
#
#   fdroid_reference.sh build  <ver> <code> <sha> <runs> <out-dir> [<throttle-flag>]
#   fdroid_reference.sh verify <signed-apk> <unsigned-apk>
#
# build:  блок Builds для <code> в рецепте рабочего каталога fdroidserver (добавляет копией
#         последнего блока или перенаводит существующий), коммит <sha> в предклон прямо из
#         рабочего репозитория (`refs/nova-release/<ver>`, до всякого пуша), затем <runs>
#         прогонов `fdroid build` и побайтовое сравнение их APK. Неподписанный эталон
#         кладётся в <out-dir> как `Nova_<ver>-fdroid-unsigned.apk`.
# verify: тот же `common.verify_apks`, которым F-Droid сверяет свою сборку с нашим файлом:
#         подпись подписанного эталона должна переноситься на неподписанный APK прогона.
#
# Приоритет. Пока файла <throttle-flag> нет — идёт сборка github-флейвора на Windows —
# прогоны сидят на двух виртуальных ядрах (`taskset`), с `nice 19` и `ionice idle`. Класс
# приоритета процесса VM на стороне Windows тут бесполезен: замер — vmmemWSL в Idle и в
# Normal отнимали у Windows одинаково (11,4 с против 11,3 с на задаче в 4,1 с), а два
# ядра из восьми — 5,0 с. Появился флаг — всем процессам сборки открываются все ядра.
#
# Окружение сборки то же, что у ручных прогонов 1.32.x: JDK 21 (G44), Go из пакета Debian
# (G45). Каталог fdroidserver — $NOVA_FDROID_WORK, по умолчанию /home/vagrant;
# $NOVA_FDROID_BIN подменяет сам `fdroid` (заглушкой — чтобы проверить скрипт без сборки).
set -euo pipefail

WORK="${NOVA_FDROID_WORK:-/home/vagrant}"
FDROID="${NOVA_FDROID_BIN:-fdroid}"
APP=com.brent.nova

export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export GOROOT=/usr/lib/go-1.26
export PATH="$JAVA_HOME/bin:$GOROOT/bin:$HOME/.cargo/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

say() { echo "[fdroid $(date +%H:%M:%S)] $*"; }
die() { say "ОШИБКА: $*"; exit 1; }

cmd_verify() {
    local signed="$1" unsigned="$2"
    [ -f "$signed" ] || die "нет подписанного эталона: $signed"
    [ -f "$unsigned" ] || die "нет неподписанного APK прогона: $unsigned"
    cd "$WORK"
    python3 - "$signed" "$unsigned" <<'PY'
import sys, tempfile
from fdroidserver import common
common.config = None
common.read_config()
signed, unsigned = sys.argv[1], sys.argv[2]
with tempfile.TemporaryDirectory() as tmp:
    err = common.verify_apks(signed, unsigned, tmp)
if err is None:
    print("verify_apks: OK — подпись эталона переносится на сборку F-Droid")
    sys.exit(0)
print("verify_apks: ОШИБКА — %s" % err)
sys.exit(1)
PY
}

cmd_build() {
    local ver="$1" code="$2" sha="$3" runs="$4" out="$5" flag="${6:-}"
    local repo meta clone
    repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
    meta="$WORK/metadata/$APP.yml"
    clone="$WORK/build/$APP"
    [[ "$code" =~ ^[0-9]+$ ]] || die "код версии не число: $code"
    [[ "$sha" =~ ^[0-9a-f]{40}$ ]] || die "нужен полный хэш коммита: $sha"
    [[ "$runs" =~ ^[1-9]$ ]] || die "число прогонов 1..9: $runs"
    [ -f "$meta" ] || die "нет рецепта $meta"
    [ -d "$clone/.git" ] || die "нет предклона $clone"
    mkdir -p "$out"

    # 1. Коммит в предклон из рабочего репозитория — раньше правки рецепта, чтобы неверный
    #    хэш не оставил рецепт перенаведённым на него. `+` обязателен: перенаведение после
    #    правки — не fast-forward, и без него ветка осталась бы на старом коммите.
    git -C "$clone" fetch --quiet "$repo" "+refs/nova-release/$ver:refs/heads/nova-release-$code"
    [ "$(git -C "$clone" rev-parse "refs/heads/nova-release-$code")" = "$sha" ] \
        || die "refs/nova-release/$ver в рабочем репозитории указывает не на $sha"
    say "коммит $sha виден в предклоне"

    # 2. Блок Builds. Копия последнего блока, а не шаблон в скрипте: sudo/prebuild/build
    #    меняются от выпуска к выпуску, и новый блок должен наследовать их, как делали
    #    add_meta_15x.sh вручную.
    [ -f "$meta.bak-$code" ] || cp "$meta" "$meta.bak-$code"
    python3 - "$meta" "$ver" "$code" "$sha" <<'PY'
import io, re, sys
path, ver, code, sha = sys.argv[1:5]
text = io.open(path, encoding="utf-8").read()
tail = text.index("\nAllowedAPKSigningKeys:")
starts = [m.start() for m in re.finditer(r"\n  - versionName: ", text[:tail])]
if not starts:
    sys.exit("в рецепте нет ни одного блока Builds")
blocks = list(zip(starts, starts[1:] + [tail]))
mine = [(s, e) for s, e in blocks if re.search(r"\n    versionCode: %s\n" % code, text[s:e])]
if mine:
    s, e = mine[0]
    block = re.sub(r"\n    commit: [0-9a-f]{40}\n", "\n    commit: %s\n" % sha, text[s:e], count=1)
    text = text[:s] + block + text[e:]
    print("блок %s уже был — перенаведён на %s" % (code, sha))
else:
    s, e = blocks[-1]
    block = text[s:e]
    block = re.sub(r"\n  - versionName: [^\n]*\n", "\n  - versionName: '%s'\n" % ver, block, count=1)
    block = re.sub(r"\n    versionCode: \d+\n", "\n    versionCode: %s\n" % code, block, count=1)
    block = re.sub(r"\n    commit: [0-9a-f]{40}\n", "\n    commit: %s\n" % sha, block, count=1)
    body = block.rstrip("\n")
    text = text[:tail].rstrip("\n") + "\n" + body + "\n" + text[tail:]
    print("блок %s добавлен копией предыдущего" % code)
text = re.sub(r"CurrentVersion: [^\n]*", "CurrentVersion: '%s'" % ver, text)
text = re.sub(r"CurrentVersionCode: \d+", "CurrentVersionCode: %s" % code, text)
io.open(path, "w", encoding="utf-8", newline="\n").write(text)
PY
    say "рецепт: $(grep -c "versionCode: $code" "$meta") блок(а) с кодом $code"

    # 3. Приоритет и расширение до всех ядер по флагу.
    local all low
    all="0-$(( $(nproc) - 1 ))"
    low="${NOVA_FDROID_LOW_CPUS:-0-1}"
    if [ -n "$flag" ] && [ ! -e "$flag" ]; then
        (
            # Сам скрипт умер раньше флага — ждать больше некого.
            while [ ! -e "$flag" ] && kill -0 $$ 2>/dev/null; do sleep 3; done
            # По uid, а не по дереву процессов: демон Gradle отцепляется от родителя и в
            # дерево скрипта уже не входит. Трижды с паузой: процесс, порождённый между
            # перечислением и сменой маски родителя, унаследовал бы старую.
            for _ in 1 2 3; do
                for pid in $(pgrep -u "$(id -u)"); do
                    taskset -a -p -c "$all" "$pid" >/dev/null 2>&1 || true
                done
                sleep 2
            done
            say "сборка github закончилась — эталону открыты все $(nproc) ядер"
        ) &
    fi

    # 4. Прогоны.
    local n log apk prefix
    mkdir -p "$WORK/repro$code"
    for n in $(seq 1 "$runs"); do
        rm -f "$WORK/unsigned/${APP}_$code.apk" "$WORK/unsigned/${APP}_${code}_src.tar.gz"
        prefix=(nice -n 19 ionice -c 3 -t)
        if [ -n "$flag" ] && [ ! -e "$flag" ]; then
            prefix+=(taskset -c "$low")
            say "прогон $n/$runs: $low из $all, пока идёт сборка github"
        else
            say "прогон $n/$runs: все ядра"
        fi
        log="$WORK/build_${code}_run$n.log"
        if ! (cd "$WORK" && "${prefix[@]}" "$FDROID" build -l "$APP:$code") >"$log" 2>&1; then
            tail -30 "$log"
            die "прогон $n не собрался — $log"
        fi
        apk="$WORK/unsigned/${APP}_$code.apk"
        [ -f "$apk" ] || die "прогон $n закончился без APK — $log"
        cp "$apk" "$WORK/repro$code/run$n.apk"
        say "прогон $n: $(sha256sum "$WORK/repro$code/run$n.apk" | cut -c1-64)"
    done

    # 5. Воспроизводимость: все прогоны побайтово равны первому.
    for n in $(seq 2 "$runs"); do
        cmp -s "$WORK/repro$code/run1.apk" "$WORK/repro$code/run$n.apk" \
            || die "run1 и run$n расходятся — сборка не воспроизводится, выпускать нельзя"
    done
    if [ "$runs" -gt 1 ]; then
        say "все прогоны ($runs) побайтово идентичны"
    fi

    cp "$WORK/repro$code/run1.apk" "$out/Nova_$ver-fdroid-unsigned.apk"
    say "эталон: $out/Nova_$ver-fdroid-unsigned.apk"
}

case "${1:-}" in
    build) shift; [ $# -ge 5 ] || die "build <ver> <code> <sha> <runs> <out-dir> [<flag>]"; cmd_build "$@" ;;
    verify) shift; [ $# -eq 2 ] || die "verify <signed-apk> <unsigned-apk>"; cmd_verify "$@" ;;
    *) die "режим: build | verify" ;;
esac

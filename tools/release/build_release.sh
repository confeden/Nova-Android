#!/usr/bin/env bash
# Оба APK выпуска разом: github-флейвор на Windows и эталон F-Droid в WSL — параллельно.
#
#   bash tools/release/build_release.sh <ver> <code> [--runs N] [--upload] [--dry-run]
#
# Запуск из Git Bash на Windows. До запуска публичный коммит уже составлен и припаркован:
# `git update-ref refs/nova-release/<ver> <sha>` (kb/build-release.md, «Build the reference
# before pushing»). Эталон строится из этого коммита, github-APK — из рабочей копии, поэтому
# скрипт сверяет, что они совпадают во всём, кроме `.github/`.
#
# Раньше шаги шли друг за другом: сборка github, затем два прогона `fdroid build` в WSL
# (~11 мин каждый). Теперь прогоны стартуют сразу. Пока идёт сборка github, WSL-сборке
# отдано два виртуальных ядра из всех — так она почти не мешает; как только сборка github
# закончилась, эталону открываются все ядра (`fdroid_reference.sh`, флаг в каталоге выпуска).
#
# Результат — build/release-<code>/: Nova_<ver>.apk, Nova_<ver>-fdroid.apk (эталон, подписан
# ключом выпуска с --alignment-preserved), fdroid.log. `--upload` кладёт оба файла в
# **черновик** v<ver> публичного репозитория (в опубликованный выпуск — отказ).
# Не собирает fdroid-флейвор через Gradle: одноимённый APK в app/build/outputs — ровно
# механизм ловушки G72.
set -euo pipefail
export MSYS_NO_PATHCONV=1

DISTRO="${NOVA_WSL_DISTRO:-Ubuntu-26.04}"
PUBLIC_REPO="${NOVA_PUBLIC_REPO:-confeden/Nova-Android}"
RES_PY="${NOVA_RES_PY:-$HOME/.claude/bin/res.py}"
# Python для Windows не понимает путь Git Bash: `/c/Users/...` он читает как
# `<текущий диск>:\c\Users\...`, а авто-перевод путей выше выключен (MSYS_NO_PATHCONV).
# Смешанная форма `C:/Users/...` понятна и bash, и Python.
if command -v cygpath >/dev/null 2>&1; then RES_PY="$(cygpath -m "$RES_PY")"; fi

say() { echo "[release $(date +%H:%M:%S)] $*"; }
die() { say "ОШИБКА: $*"; exit 1; }

VER="${1:-}"; CODE="${2:-}"
[ -n "$VER" ] && [ -n "$CODE" ] || die "использование: build_release.sh <ver> <code> [--runs N] [--upload] [--dry-run]"
shift 2
RUNS=2; UPLOAD=0; DRY=0
while [ $# -gt 0 ]; do
    case "$1" in
        --runs) RUNS="${2:?число прогонов}"; shift 2 ;;
        --upload) UPLOAD=1; shift ;;
        --dry-run) DRY=1; shift ;;
        *) die "неизвестный ключ: $1" ;;
    esac
done
[[ "$RUNS" =~ ^[1-9]$ ]] || die "--runs 1..9"

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
REPO_WIN="$(pwd -W)"
to_wsl() { wsl.exe -d "$DISTRO" -- wslpath -a "$1" | tr -d '\r'; }

# --- Предусловия. Всё, что можно проверить до двадцати минут сборки, проверяется здесь.
grep -qE "^\s*versionCode = $CODE\s*$" app/build.gradle.kts || die "в app/build.gradle.kts не versionCode = $CODE"
grep -qE "^\s*versionName = \"$VER\"" app/build.gradle.kts || die "в app/build.gradle.kts не versionName = \"$VER\""
SHA="$(git rev-parse --verify --quiet "refs/nova-release/$VER^{commit}")" \
    || die "нет refs/nova-release/$VER — сначала составьте публичный коммит и: git update-ref refs/nova-release/$VER <sha>"
[ -z "$(git status --porcelain --untracked-files=no)" ] \
    || die "в рабочей копии незакоммиченные правки — github-APK собрался бы не из того, что уйдёт в выпуск"
git diff --quiet HEAD "$SHA" -- . ':(exclude).github' \
    || die "исходники $SHA расходятся с HEAD (без .github) — эталон и github-APK собрались бы из разного"
[ -f keystore.properties ] || die "нет keystore.properties — подписать эталон нечем"
# `sdk.dir=C\:\\Users\\...` — экранирование .properties: обратные косые в прямые, `/:` в `:`.
SDK_DIR="$(grep -E '^sdk\.dir=' local.properties | cut -d= -f2- | tr -d '\r' | tr -s '\134' '/' | sed 's#/:#:#')"
BUILD_TOOLS="$(ls -d "$SDK_DIR"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
APKSIGNER="${BUILD_TOOLS%/}/apksigner.bat"
[ -f "$APKSIGNER" ] || die "не найден apksigner в $SDK_DIR/build-tools"
if [ "$UPLOAD" = 1 ]; then
    draft="$(gh release view "v$VER" -R "$PUBLIC_REPO" --json isDraft -q .isDraft 2>/dev/null || true)"
    [ "$draft" = "true" ] || die "v$VER в $PUBLIC_REPO — не черновик (или его нет); --upload кладёт файлы только в черновик"
fi

OUT="build/release-$CODE"
mkdir -p "$OUT"
FLAG="$OUT/windows-build.done"
rm -f "$FLAG"
WSL_REPO="$(to_wsl "$REPO_WIN")"
WSL_OUT="$(to_wsl "$REPO_WIN/$OUT")"

say "выпуск $VER ($CODE), публичный коммит $SHA, прогонов эталона: $RUNS"
if [ "$DRY" = 1 ]; then
    say "проверка без сборки: предусловия выполнены; WSL-репозиторий $WSL_REPO, каталог $WSL_OUT, apksigner $APKSIGNER"
    exit 0
fi

# --- 0. Слот Gradle на общей машине — ДО старта WSL. Очередь res.py пускает Gradle только при
#     запасе памяти, а VM WSL этот запас съедает: получи Gradle слот после старта эталона, он
#     мог бы ждать, пока эталон сидит на двух ядрах, — медленнее, чем по очереди. Резерв
#     привязан к сессии Claude; человеку, запустившему скрипт руками, очередь не нужна.
TICKET=""
if [ -f "$RES_PY" ] && [ -n "${CLAUDE_CODE_SESSION_ID:-}" ]; then
    TICKET="$(python "$RES_PY" reserve --req gradle --req "project-build:Nova Android" --ttl 90m)"
    say "ждём слот Gradle (очередь и память общей машины)"
    python "$RES_PY" wait "$TICKET" >/dev/null || die "слот Gradle не выдан (res.py)"
fi
cleanup() {
    # Что бы ни случилось, эталон не должен остаться на двух ядрах, а слот — занятым.
    touch "$FLAG"
    if [ -n "$TICKET" ]; then python "$RES_PY" release "$TICKET" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

# --- 1. Эталон F-Droid в фоне, с первой секунды.
wsl.exe -d "$DISTRO" -- bash "$WSL_REPO/tools/release/fdroid_reference.sh" \
    build "$VER" "$CODE" "$SHA" "$RUNS" "$WSL_OUT" "$WSL_OUT/windows-build.done" \
    >"$OUT/fdroid.log" 2>&1 &
WSL_JOB=$!
say "эталон F-Droid собирается в WSL (журнал $OUT/fdroid.log)"

# --- 2. github-APK. Под резервом `res.py run` той же сессии проходит без очереди.
say "сборка github-флейвора"
gradle_ok=0
if [ -n "$TICKET" ]; then
    python "$RES_PY" run --req gradle --req "project-build:Nova Android" -- ./gradlew :app:assembleGithubRelease \
        && gradle_ok=1
else
    ./gradlew :app:assembleGithubRelease && gradle_ok=1
fi
touch "$FLAG"
if [ -n "$TICKET" ]; then
    python "$RES_PY" release "$TICKET" >/dev/null 2>&1 || true
    TICKET=""
fi
if [ "$gradle_ok" != 1 ]; then
    die "сборка github не прошла; эталон продолжает собираться в WSL на всех ядрах — $OUT/fdroid.log"
fi
cp -f "app/build/outputs/apk/github/release/Nova_$VER.apk" "$OUT/Nova_$VER.apk"
say "github-APK готов: $OUT/Nova_$VER.apk"

# --- 3. Дождаться эталона.
say "ждём эталон F-Droid"
if ! wait "$WSL_JOB"; then
    tail -20 "$OUT/fdroid.log"
    die "эталон не собрался — $OUT/fdroid.log"
fi
tail -3 "$OUT/fdroid.log"
UNSIGNED="$OUT/Nova_$VER-fdroid-unsigned.apk"
SIGNED="$OUT/Nova_$VER-fdroid.apk"
[ -f "$UNSIGNED" ] || die "WSL не положил $UNSIGNED"

# --- 4. Подпись эталона ключом выпуска. `--alignment-preserved` обязателен: без него
#     apksigner переукладывает записи, и перенесённая F-Droid подпись перестаёт сходиться.
#     Пароли — через окружение, а не в аргументах, чтобы не светились в списке процессов.
prop() { grep -E "^$1=" keystore.properties | cut -d= -f2- | tr -d '\r'; }
export NOVA_KS_PASS NOVA_KEY_PASS
NOVA_KS_PASS="$(prop storePassword)"
NOVA_KEY_PASS="$(prop keyPassword)"
cp -f "$UNSIGNED" "$SIGNED"
"$APKSIGNER" sign --alignment-preserved \
    --ks "$(prop storeFile)" --ks-pass env:NOVA_KS_PASS \
    --ks-key-alias "$(prop keyAlias)" --key-pass env:NOVA_KEY_PASS \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    "$SIGNED"
unset NOVA_KS_PASS NOVA_KEY_PASS

# --- 5. Сверка тем же кодом, которым её сделает F-Droid.
wsl.exe -d "$DISTRO" -- bash "$WSL_REPO/tools/release/fdroid_reference.sh" \
    verify "$WSL_OUT/Nova_$VER-fdroid.apk" "$(to_wsl "$REPO_WIN/$UNSIGNED")" \
    || die "verify_apks не принял подписанный эталон — в выпуск его класть нельзя"

# --- 6. Итог.
for f in "$OUT/Nova_$VER.apk" "$SIGNED"; do
    say "$(sha256sum "$f" | cut -c1-64)  $f"
    "$APKSIGNER" verify --print-certs "$f" | grep -m1 "SHA-256 digest" | sed 's/^/    /'
done

if [ "$UPLOAD" = 1 ]; then
    gh release upload "v$VER" "$OUT/Nova_$VER.apk" "$SIGNED" --clobber -R "$PUBLIC_REPO"
    say "оба файла в черновике v$VER"
else
    say "в черновик: gh release upload v$VER \"$OUT/Nova_$VER.apk\" \"$SIGNED\" --clobber -R $PUBLIC_REPO"
fi

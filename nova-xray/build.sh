#!/usr/bin/env bash
# Сборка libnovaxray.so (ядро Xray) и libnovaxrayjni.so (JNI-мост)
# для ABI, которые поддерживает Nova.
#
# Требуется: Go 1.26+, Android NDK 27+.
# Путь к NDK берётся из ANDROID_NDK_HOME или ANDROID_HOME/ndk/<версия>.
set -euo pipefail

API_LEVEL="${API_LEVEL:-24}"
OUT_DIR="${OUT_DIR:-../app/src/main/jniLibs}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo "ANDROID_NDK_HOME не задан" >&2
  exit 1
fi

case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) HOST_TAG="windows-x86_64"; CLANG_EXT=".cmd" ;;
  Darwin)               HOST_TAG="darwin-x86_64"; CLANG_EXT="" ;;
  *)                    HOST_TAG="linux-x86_64";  CLANG_EXT="" ;;
esac
TOOLCHAIN="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$HOST_TAG/bin"

# -checklinkname=0: wlynxg/anet (транзитивная зависимость Xray) обращается к
# внутренностям пакета net через go:linkname, что Go 1.23+ запрещает по умолчанию.
# max-page-size=16384: требование Android 15+ к выравниванию страниц.
LDFLAGS='-s -w -buildid= -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384'

build_abi() {
  local abi="$1" goarch="$2" triple="$3"
  local staging
  staging="$(mktemp -d)"
  echo "==> $abi"

  CGO_ENABLED=1 GOOS=android GOARCH="$goarch" \
    CC="$TOOLCHAIN/${triple}${API_LEVEL}-clang${CLANG_EXT}" \
    go build -buildmode=c-shared -trimpath -ldflags="$LDFLAGS" \
    -o "$staging/libnovaxray.so" .

  "$TOOLCHAIN/${triple}${API_LEVEL}-clang${CLANG_EXT}" \
    -shared -fPIC -O2 -o "$staging/libnovaxrayjni.so" jni/novaxray_jni.c \
    -L"$staging" -lnovaxray -Wl,-z,max-page-size=16384

  mkdir -p "$OUT_DIR/$abi"
  cp "$staging/libnovaxray.so" "$staging/libnovaxrayjni.so" "$OUT_DIR/$abi/"
  rm -rf "$staging"
  ls -la "$OUT_DIR/$abi/libnovaxray.so" "$OUT_DIR/$abi/libnovaxrayjni.so"
}

build_abi arm64-v8a   arm64 aarch64-linux-android
build_abi armeabi-v7a arm   armv7a-linux-androideabi

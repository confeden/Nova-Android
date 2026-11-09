#!/usr/bin/env bash
# Ð¡Ð±Ð¾Ñ€ÐºÐ° libnovaproxy.so â€” Ð¿Ð¾Ð¼Ð¾Ñ‰Ð½Ð¸ÐºÐ° Ð¿Ñ€Ð¾Ñ‚Ð¾ÐºÐ¾Ð»Ð¾Ð² Ð½Ð° Mihomo (Ñ‚Ð¾Ñ‚ Ð¶Ðµ nova-proxy,
# Ñ‡Ñ‚Ð¾ Ñƒ Nova PC; Ð¸Ñ���Ñ…Ð¾Ð´Ð½Ð¸Ðº Ð² nova-proxy/, Ð²ÐµÑ€Ñ���Ð¸Ñ��� Ñ���Ð´Ñ€Ð° Ð·Ð°ÐºÑ€ÐµÐ¿Ð»ÐµÐ½Ð° Ð² go.mod).
# Ð­Ñ‚Ð¾ Ð¸Ñ���Ð¿Ð¾Ð»Ð½Ñ���ÐµÐ¼Ñ‹Ð¹ Ñ„Ð°Ð¹Ð», Ð° Ð½Ðµ Ð±Ð¸Ð±Ð»Ð¸Ð¾Ñ‚ÐµÐºÐ°: Android Ñ€Ð°Ñ���Ð¿Ð°ÐºÐ¾Ð²Ñ‹Ð²Ð°ÐµÑ‚ Ð¸Ð· APK Ñ‚Ð¾Ð»ÑŒÐºÐ¾
# lib*.so, Ð¿Ð¾Ñ���Ñ‚Ð¾Ð¼Ñƒ Ð¸Ð¼Ñ��� Ñ‚Ð°ÐºÐ¾Ðµ; Ð·Ð°Ð¿ÑƒÑ���ÐºÐ°ÐµÑ‚ ÐµÐ³Ð¾ NovaProxyProcess Ð¾Ñ‚Ð´ÐµÐ»ÑŒÐ½Ñ‹Ð¼ Ð¿Ñ€Ð¾Ñ†ÐµÑ���Ñ���Ð¾Ð¼.
#
#   ANDROID_NDK_HOME=~/AppData/Local/Android/Sdk/ndk/27.2.12479018 tools/build_nova_proxy.sh
#
# CGO Ð½ÑƒÐ¶ÐµÐ½ Ñ€Ð°Ð´Ð¸ Ñ€ÐµÐ·Ð¾Ð»Ð²ÐµÑ€Ð°: Ñƒ Android Ð½ÐµÑ‚ /etc/resolv.conf, Ð¸ Ñ‡Ð¸Ñ���Ñ‚Ñ‹Ð¹ Go-Ñ€ÐµÐ·Ð¾Ð»Ð²ÐµÑ€
# Ñ…Ð¾Ð´Ð¸Ð» Ð±Ñ‹ Ð½Ð° 127.0.0.1:53. Ð¡ cgo Ð¸Ð¼ÐµÐ½Ð° Ñ€Ð°Ð·Ñ€ÐµÑˆÐ°ÐµÑ‚ bionic, Ñ‚Ð¾ ÐµÑ���Ñ‚ÑŒ Ñ���Ð¸Ñ���Ñ‚ÐµÐ¼Ð°.
set -euo pipefail
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
src="$root_dir/nova-proxy"
out_dir="${OUT_DIR:-$root_dir/app/src/main/jniLibs}"
: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME Ð½Ðµ Ð·Ð°Ð´Ð°Ð½}"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) host_tag="windows-x86_64"; clang_ext=".cmd" ;;
  Darwin)               host_tag="darwin-x86_64"; clang_ext="" ;;
  *)                    host_tag="linux-x86_64";  clang_ext="" ;;
esac
toolchain="$ANDROID_NDK_HOME/toolchains/llvm/prebuilt/$host_tag/bin"
api="${ANDROID_API:-24}"
# max-page-size=16384 â€” Android 15+; -buildvcs=false Ð¸ -buildid= â€” Ð²Ð¾Ñ���Ð¿Ñ€Ð¾Ð¸Ð·Ð²Ð¾Ð´Ð¸Ð¼Ð¾Ñ���Ñ‚ÑŒ.
ldflags='-s -w -buildid= -checklinkname=0 -linkmode external -extldflags "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384"'
build_one() {
    local abi="$1" goarch="$2" cc="$3" goarm="${4:-}"
    echo "==> $abi"
    mkdir -p "$out_dir/$abi"
    (
        cd "$src"
        env CGO_ENABLED=1 GOOS=android GOARCH="$goarch" ${goarm:+GOARM=$goarm} CC="$cc" \
            go build -trimpath -buildvcs=false -tags no_zerotier,no_easytier,no_tailscale,with_low_memory -ldflags="$ldflags" \
            -o "$out_dir/$abi/libnovaproxy.so" .
    )
    ls -l "$out_dir/$abi/libnovaproxy.so"
}
build_one arm64-v8a arm64 "$toolchain/aarch64-linux-android$api-clang$clang_ext"
build_one armeabi-v7a arm "$toolchain/armv7a-linux-androideabi$api-clang$clang_ext" 7

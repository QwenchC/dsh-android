#!/usr/bin/env bash
# Build the Android native payloads of @deepseek-ai/node-addon-system.
#
# The Harness needs exactly one native primitive on Android: the asynchronous
# POSIX flock binding that gives a Session directory its cross-process write
# lock. It is a Node-API v8 addon with no libnode dependency, so the NDK
# compiles it straight from source into each platform package's payload.
#
# Usage: bash build-android-native.sh [checkout]
set -euo pipefail

CHECKOUT="${1:-/mnt/e/Lumi/DSH-Android/dsh-harness}"
NDK="${ANDROID_NDK_ROOT:-/mnt/c/Users/LumiTree/AppData/Local/Android/Sdk/ndk/27.2.12479018}"
NODE_VERSION="${NODE_VERSION:-v26.4.0}"
API="${ANDROID_API:-24}"
WORK=/opt/dsh-android-native
TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

[ -d "$TOOLCHAIN" ] || { echo "no NDK toolchain at $TOOLCHAIN" >&2; exit 1; }

# N-API headers are a stable ABI: the official headers of one Node release
# compile addons for every Node >= that release's N-API version.
HEADERS="$WORK/node-headers"
if [ ! -d "$HEADERS/include/node" ]; then
  mkdir -p "$HEADERS"
  echo "downloading Node $NODE_VERSION headers"
  curl -sL "https://nodejs.org/dist/$NODE_VERSION/node-$NODE_VERSION-headers.tar.xz" \
    | tar xJ -C "$HEADERS" --strip-components=1
fi

build_abi() { # platform-package-dir ndk-triple
  local pkg="$1" triple="$2"
  local cc="$TOOLCHAIN/bin/${triple}${API}-clang"
  [ -x "$cc" ] || { echo "no compiler $cc" >&2; exit 1; }
  mkdir -p "$pkg/bin/bionic"
  echo "== $pkg ($triple) =="
  # Shared objects may carry undefined symbols, so the addon is not linked
  # against libnode; the embedding process resolves the N-API entry points.
  "$cc" -shared -fPIC -O2 -Wall \
    -I"$HEADERS/include/node" \
    -o "$pkg/bin/bionic/system.node" \
    "$CHECKOUT/native/system/packages/entry/src/flock.c"
  "$TOOLCHAIN/bin/llvm-strip" --strip-unneeded "$pkg/bin/bionic/system.node" 2>/dev/null || true
  file "$pkg/bin/bionic/system.node"
  ls -l "$pkg/bin/bionic/system.node"
}

build_abi "$CHECKOUT/native/system/packages/android-arm64" "aarch64-linux-android"
build_abi "$CHECKOUT/native/system/packages/android-x64" "x86_64-linux-android"
echo "done"

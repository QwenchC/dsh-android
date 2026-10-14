#!/usr/bin/env bash
# Assemble the Android Node runtime the APK ships.
#
# Android has no Node.js an application can use: official Node publishes no
# Android build, nodejs-mobile stopped at Node 18 while the Harness needs
# >= 24, and an application may not execute a binary from its own writable
# directory. The one maintained Android Node is Termux's, so this script
# extracts it and the libraries it links against, unmodified.
#
# Nothing here is patched. The platform extracts an APK's lib/ entries only
# when they are named lib*.so, and rewriting other names into that form means
# rewriting an ELF dynamic string table, which Android's linker then rejects.
# So only the executable goes into lib/ — as libnode.so — and the libraries
# keep the names their SONAMEs require, extracted beside the Harness tree and
# found through LD_LIBRARY_PATH, which the linker searches before RUNPATH.
#
# Output: <out>/<abi>/bin/node (the executable, exactly as built)
#         <out>/<abi>/lib/*    (its NEEDED closure, original names)
#         <out>/<abi>/include-node
#
# Usage: bash build-node-runtime.sh [outdir] [nodeabi ...]
# Set https_proxy when the build host needs one to reach packages.termux.dev.
set -euo pipefail

OUT="${1:-/mnt/e/Lumi/DSH-Android/runtime/node-android}"
shift || true
ABIS=("$@")
[ ${#ABIS[@]} -gt 0 ] || ABIS=(arm64 x64)

REPO=https://packages.termux.dev/apt/termux-main/pool/main
WORK=/opt/dsh-node-runtime
mkdir -p "$WORK" "$OUT"

# Pinned to the versions verified against the live repository index.
NODE_VERSION=24.18.0-1
LIBS=(
  "libc/libc++/libc++_30"
  "libi/libicu/libicu_78.3"
  "o/openssl/openssl_1:3.6.5"
  "c/c-ares/c-ares_1.34.8"
  "libs/libsqlite/libsqlite_3.54.0"
  "z/zlib/zlib_1.3.2"
)

# SONAMEs the running Android already provides; bundling them would shadow the
# platform's own copies.
SYSTEM_LIBS=" libc.so libm.so libdl.so liblog.so libandroid.so libstdc++.so libunwind.so "

termux_arch() { case $1 in arm64) echo aarch64 ;; x64) echo x86_64 ;; *) echo "unknown abi $1" >&2; exit 1 ;; esac; }

fetch_pool() { # rel-without-arch arch outfile
  local rel=$1 arch=$2 out=$3
  if [ -s "$out" ]; then return 0; fi
  local url="$REPO/${rel}_${arch}.deb"
  if ! curl -sfL --max-time 900 -o "$out" "$url"; then
    local dir=${rel%/*} name=${rel##*/}
    name=${name%%_*}
    local listing
    listing=$(curl -sfL --max-time 120 "$REPO/$dir/" | grep -oE "${name}_[^\"]*_${arch}\\.deb" | sort -V | tail -1)
    [ -n "$listing" ] || { echo "cannot resolve $name for $arch" >&2; return 1; }
    curl -sfL --max-time 900 -o "$out" "$REPO/$dir/$listing"
  fi
}

extract_deb() { # deb dir -> prints the Termux prefix inside the payload
  rm -rf "$2"; mkdir -p "$2"
  ( cd "$2" && ar x "$1" && tar xf data.tar.* )
  find "$2" -maxdepth 7 -type d -path '*/com.termux/files/usr' | head -1
}

for abi in "${ABIS[@]}"; do
  arch=$(termux_arch "$abi")
  echo "===== $abi ($arch) ====="
  target="$OUT/$abi"
  rm -rf "$target"; mkdir -p "$target/bin" "$target/lib"

  echo "  nodejs-lts $NODE_VERSION"
  node_deb="$WORK/nodejs-lts_${NODE_VERSION}_${arch}.deb"
  fetch_pool "n/nodejs-lts/nodejs-lts_${NODE_VERSION}" "$arch" "$node_deb"
  node_usr=$(extract_deb "$node_deb" "$WORK/x-node-$abi")
  cp "$node_usr/bin/node" "$target/bin/node"
  chmod 0755 "$target/bin/node"
  [ -d "$node_usr/include/node" ] && cp -r "$node_usr/include/node" "$target/include-node"

  # Index every library the dependency packages offer by the SONAME the dynamic
  # linker will ask for.
  sources="$WORK/sources-$abi"
  rm -rf "$sources"; mkdir -p "$sources"
  for rel in "${LIBS[@]}"; do
    name=${rel##*/}; name=${name%%_*}
    deb="$WORK/${name}_${arch}.deb"
    fetch_pool "$rel" "$arch" "$deb"
    usr=$(extract_deb "$deb" "$WORK/x-$name-$abi")
    mkdir -p "$sources/$name"
    find "$usr/lib" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' -exec cp -L -t "$sources/$name" {} + 2>/dev/null || true
  done
  declare -A AVAILABLE=()
  while IFS= read -r lib; do
    soname=$(patchelf --print-soname "$lib" 2>/dev/null || true)
    [ -n "$soname" ] || soname=$(basename "$lib")
    if [ -z "${AVAILABLE[$soname]:-}" ]; then AVAILABLE[$soname]=$lib; fi
  done < <(find "$sources" -type f -name '*.so*' | sort)

  # Breadth-first over NEEDED, starting from the executable. Each library keeps
  # the file name its SONAME requires.
  queue=( $(readelf -d "$target/bin/node" | awk '/NEEDED/{gsub(/[][]/,"",$NF); print $NF}') )
  declare -A CLOSURE=()
  i=0
  while [ $i -lt ${#queue[@]} ]; do
    soname=${queue[$i]}; i=$((i+1))
    [ -n "${CLOSURE[$soname]:-}" ] && continue
    case " $SYSTEM_LIBS " in *" $soname "*) CLOSURE[$soname]=SYSTEM; continue ;; esac
    src=${AVAILABLE[$soname]:-}
    if [ -z "$src" ]; then echo "  MISSING $soname" >&2; exit 1; fi
    CLOSURE[$soname]=$src
    while IFS= read -r need; do queue+=("$need"); done < <(readelf -d "$src" | awk '/NEEDED/{gsub(/[][]/,"",$NF); print $NF}')
  done

  for soname in "${!CLOSURE[@]}"; do
    [ "${CLOSURE[$soname]}" = SYSTEM ] && continue
    cp -f "${CLOSURE[$soname]}" "$target/lib/$soname"
    echo "    $soname"
  done

  echo "  executable: $(stat -c%s "$target/bin/node") bytes"
  echo "  libraries:  $(ls "$target/lib" | wc -l) files, $(du -sh "$target/lib" | cut -f1)"
  echo "  RUNPATH (the Termux prefix; LD_LIBRARY_PATH supplies the real one):"
  readelf -d "$target/bin/node" | awk '/RUNPATH|RPATH/{print "    " $NF}'
done
echo done

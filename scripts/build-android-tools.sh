#!/usr/bin/env bash
# Assemble the Android helper executables the Harness tools call.
#
# ripgrep backs the glob and grep tools; bash is the shell the executor prefers
# when the runtime bundle can supply one. Android ships neither, and the tools
# publish no Android build, so both come from Termux packages and keep their
# own SONAMEs, found through the same LD_LIBRARY_PATH the Node runtime uses.
#
# Output: <out>/<abi>/bin/<tool>   (the executable, exactly as built)
#         <out>/<abi>/lib/*        (its NEEDED closure, original names)
#
# Usage: bash build-android-tools.sh [outdir] [nodeabi ...]
set -euo pipefail

OUT="${1:-/mnt/e/Lumi/DSH-Android/runtime/android-tools}"
shift || true
ABIS=("$@")
[ ${#ABIS[@]} -gt 0 ] || ABIS=(arm64 x64)

REPO=https://packages.termux.dev/apt/termux-main/pool/main
WORK=/opt/dsh-android-tools
mkdir -p "$WORK" "$OUT"

# tool -> pool path without the version and ABI suffix
TOOLS=(
  "rg:r/ripgrep/ripgrep"
  "bash:b/bash/bash"
)

# Library packages the tools link against, which no tool package carries.
TOOL_LIBS=(
  "p/pcre2/pcre2"
  "liba/libandroid-support/libandroid-support"
  "r/readline/readline"
  "n/ncurses/ncurses"
  "libi/libiconv/libiconv"
)

SYSTEM_LIBS=" libc.so libm.so libdl.so liblog.so libandroid.so libstdc++.so libunwind.so "

termux_arch() { case $1 in arm64) echo aarch64 ;; x64) echo x86_64 ;; *) echo "unknown abi $1" >&2; exit 1 ;; esac; }

# Resolve the newest published version of a package for an ABI.
latest_url() { # pool-dir package arch
  local dir=$1 name=$2 arch=$3 listing
  listing=$(curl -sfL --max-time 120 "$REPO/$dir/" | grep -oE "${name}_[^\"]*_${arch}\\.deb" | sort -V | tail -1)
  [ -n "$listing" ] || return 1
  echo "$REPO/$dir/$listing"
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

  for entry in "${TOOLS[@]}"; do
    tool=${entry%%:*}
    pool=${entry#*:}
    dir=${pool%/*}; name=${pool##*/}
    url=$(latest_url "$dir" "$name" "$arch") || { echo "  $tool: no package for $arch" >&2; continue; }
    deb="$WORK/${name}_${arch}.deb"
    [ -s "$deb" ] || curl -sfL --max-time 900 -o "$deb" "$url"
    usr=$(extract_deb "$deb" "$WORK/x-$name-$abi")
    src="$usr/bin/$tool"
    [ -f "$src" ] || { echo "  $tool: not in payload" >&2; continue; }
    cp "$src" "$target/bin/$tool"
    chmod 0755 "$target/bin/$tool"

    # The tool's own libraries plus everything the dependency packages offer,
    # indexed by the SONAME the linker asks for.
    sources="$WORK/sources-$name-$abi"
    rm -rf "$sources"; mkdir -p "$sources/$name"
    find "$usr/lib" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' -exec cp -L -t "$sources/$name" {} + 2>/dev/null || true
    if [ -d "$WORK/sources-$abi" ]; then
      cp -rn "$WORK"/sources-$abi/* "$sources/" 2>/dev/null || true
    fi
    # Packages that exist only to provide the libraries a tool links against.
    for libpool in "${TOOL_LIBS[@]}"; do
      libdir=${libpool%/*}; libname=${libpool##*/}
      liburl=$(latest_url "$libdir" "$libname" "$arch") || continue
      libdeb="$WORK/${libname}_${arch}.deb"
      [ -s "$libdeb" ] || curl -sfL --max-time 900 -o "$libdeb" "$liburl"
      libusr=$(extract_deb "$libdeb" "$WORK/x-$libname-$abi")
      mkdir -p "$sources/$libname"
      find "$libusr/lib" -maxdepth 1 \( -type f -o -type l \) -name '*.so*' -exec cp -L -n -t "$sources/$libname" {} + 2>/dev/null || true
    done
    # A package may publish a library under both its real version name and an
    # unversioned link; index the SONAME, the file name, and the name with any
    # version suffix stripped, because a tool can link against any of them.
    declare -A AVAILABLE=()
    register() { if [ -z "${AVAILABLE[$1]:-}" ]; then AVAILABLE[$1]=$2; fi; }
    while IFS= read -r lib; do
      soname=$(patchelf --print-soname "$lib" 2>/dev/null || true)
      base=$(basename "$lib")
      [ -n "$soname" ] && register "$soname" "$lib"
      register "$base" "$lib"
      register "${base%%.so*}.so" "$lib"
    done < <(find "$sources" -type f -name '*.so*' | sort)

    queue=( $(readelf -d "$target/bin/$tool" | awk '/NEEDED/{gsub(/[][]/,"",$NF); print $NF}') )
    declare -A CLOSURE=()
    i=0
    while [ $i -lt ${#queue[@]} ]; do
      soname=${queue[$i]}; i=$((i+1))
      [ -n "${CLOSURE[$soname]:-}" ] && continue
      case " $SYSTEM_LIBS " in *" $soname "*) CLOSURE[$soname]=SYSTEM; continue ;; esac
      src_lib=${AVAILABLE[$soname]:-}
      if [ -z "$src_lib" ]; then echo "  $tool: MISSING $soname" >&2; continue 2; fi
      CLOSURE[$soname]=$src_lib
      while IFS= read -r need; do queue+=("$need"); done < <(readelf -d "$src_lib" | awk '/NEEDED/{gsub(/[][]/,"",$NF); print $NF}')
    done
    # The destination name is the SONAME the tool asks for, whatever the
    # package called the file, so the linker finds exactly what it requests.
    for soname in "${!CLOSURE[@]}"; do
      [ "${CLOSURE[$soname]}" = SYSTEM ] && continue
      [ -f "$target/lib/$soname" ] || cp -f "${CLOSURE[$soname]}" "$target/lib/$soname"
    done
    echo "  $tool: $(stat -c%s "$target/bin/$tool") bytes, $(ls "$target/lib" | wc -l) libraries"
  done
done
echo done

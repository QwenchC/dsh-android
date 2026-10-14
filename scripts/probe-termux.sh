#!/usr/bin/env bash
# Report which Termux package publishes a library the tools link against,
# from the repository index (the pool directory names are not derivable).
set -uo pipefail
export https_proxy=${https_proxy:-http://127.0.0.1:7897}
IDX=/opt/dsh-node-runtime/Packages-aarch64
mkdir -p "$(dirname "$IDX")"
if [ ! -s "$IDX" ]; then
  echo "downloading the index"
  curl -sfL --max-time 900 -o "$IDX" https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages || { echo "download failed"; exit 1; }
fi
echo "index: $(wc -l < "$IDX") lines"
for pattern in "$@"; do
  echo "== $pattern =="
  awk -v p="$pattern" '
    /^Package: /{pkg=substr($0,10)}
    /^Filename: /{if (pkg ~ p) printf "  %s  %s\n", pkg, substr($0,11)}
  ' "$IDX" | sort -u | head -8
done

#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
compiler_dir=$(CDPATH= cd -- "$repo_dir/../compiler" && pwd)
source_file="$repo_dir/kotoba/kekkai/packet_plane.kotoba"
output_dir="$repo_dir/build/packet-plane"

mkdir -p "$output_dir"

"$compiler_dir/bin/kotoba" -M compile "$source_file" --target js \
  --policy "$repo_dir/kotoba/policy.edn" \
  --output "$output_dir/packet-plane.mjs"
"$compiler_dir/bin/kotoba" -M compile "$source_file" --target aarch64-android \
  --policy "$repo_dir/kotoba/policy.edn" \
  --output "$output_dir/packet-plane-android.kexe"

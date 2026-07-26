#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
compiler_dir=$(CDPATH= cd -- "$repo_dir/../compiler" && pwd)
source_file="$repo_dir/kotoba/kekkai/packet_plane.kotoba"
output_dir="$repo_dir/build/packet-plane"

mkdir -p "$output_dir"

"$compiler_dir/bin/kotoba" -M check "$source_file" \
  --policy "$repo_dir/kotoba/policy.edn"
"$compiler_dir/bin/kotoba" -M compile "$source_file" --target wasm32 \
  --policy "$repo_dir/kotoba/policy.edn" \
  --output "$output_dir/packet-plane.wasm"
"$compiler_dir/bin/kotoba" -M compile "$source_file" --target js \
  --policy "$repo_dir/kotoba/policy.edn" \
  --output "$output_dir/packet-plane.mjs"
"$compiler_dir/bin/kotoba" -M compile "$source_file" --target aarch64-ios \
  --policy "$repo_dir/kotoba/policy.edn" \
  --output "$output_dir/packet-plane-ios.kexe"
"$compiler_dir/bin/kotoba" -M package-ios \
  "$output_dir/packet-plane-ios.kexe" --entry decide \
  --output "$output_dir/packet-plane-ios.S" \
  --manifest-output "$output_dir/packet-plane-ios.edn"
"$compiler_dir/bin/kotoba" -M compile "$source_file" --target aarch64-android \
  --policy "$repo_dir/kotoba/policy.edn" \
  --output "$output_dir/packet-plane-android.kexe"
"$compiler_dir/bin/kotoba" -M compile "$source_file" --target x86_64-linux \
  --policy "$repo_dir/kotoba/policy.edn" \
  --output "$output_dir/packet-plane-linux.kexe"
"$compiler_dir/bin/kotoba" -M compile "$source_file" --target x86_64-windows \
  --policy "$repo_dir/kotoba/policy.edn" \
  --output "$output_dir/packet-plane-windows.kexe"
"$compiler_dir/bin/kotoba" -M compile "$source_file" --target aarch64-macos \
  --policy "$repo_dir/kotoba/policy.edn" \
  --output "$output_dir/packet-plane-macos.kexe"

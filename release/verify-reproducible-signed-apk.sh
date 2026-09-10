#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT

release/build-signed-apk.sh
first_apk="$tmp_dir/app-release-first.apk"
cp app/build/outputs/apk/release/app-release.apk "$first_apk"

release/build-signed-apk.sh
second_apk="app/build/outputs/apk/release/app-release.apk"

if ! cmp -s "$first_apk" "$second_apk"; then
  echo "Signed release APK is not byte-for-byte reproducible with the current source, toolchain, and signing key." >&2
  exit 1
fi

echo "Signed release APK is byte-for-byte reproducible across two clean app builds."

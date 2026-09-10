#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$repo_root"

signing_variables=(
  HERMES_BRIDGE_SIGNING_STORE_FILE
  HERMES_BRIDGE_SIGNING_STORE_PASSWORD
  HERMES_BRIDGE_SIGNING_KEY_ALIAS
  HERMES_BRIDGE_SIGNING_KEY_PASSWORD
)
for name in "${signing_variables[@]}"; do
  if [[ -n "${!name:-}" ]]; then
    echo "Unsigned reproducibility check requires release-signing variables to be unset: $name" >&2
    exit 2
  fi
done

if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  echo "Refusing to verify reproducibility from a dirty worktree." >&2
  exit 2
fi

tmp_dir=$(mktemp -d)
trap 'rm -rf "$tmp_dir"' EXIT
apk="app/build/outputs/apk/release/app-release-unsigned.apk"

build_unsigned() {
  ./gradlew --no-daemon :app:clean :app:assembleRelease
  if [[ ! -s "$apk" ]]; then
    echo "Expected unsigned release APK was not produced: $apk" >&2
    exit 1
  fi
}

build_unsigned
cp "$apk" "$tmp_dir/app-release-first-unsigned.apk"
build_unsigned

if ! cmp -s "$tmp_dir/app-release-first-unsigned.apk" "$apk"; then
  echo "Unsigned release APK is not byte-for-byte reproducible with the current source and toolchain." >&2
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$tmp_dir/app-release-first-unsigned.apk" "$apk" >&2
  fi
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$apk" | tee "$apk.sha256"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$apk" | tee "$apk.sha256"
else
  echo "Neither sha256sum nor shasum is available." >&2
  exit 2
fi

echo "Unsigned minified release APK is byte-for-byte reproducible across two clean app builds."

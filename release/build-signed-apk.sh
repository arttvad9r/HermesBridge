#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"

required_variables=(
  HERMES_BRIDGE_SIGNING_STORE_FILE
  HERMES_BRIDGE_SIGNING_STORE_PASSWORD
  HERMES_BRIDGE_SIGNING_KEY_ALIAS
  HERMES_BRIDGE_SIGNING_KEY_PASSWORD
)

missing=()
for name in "${required_variables[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    missing+=("$name")
  fi
done
if (( ${#missing[@]} > 0 )); then
  printf 'Missing release-signing environment variables: %s\n' "${missing[*]}" >&2
  exit 2
fi

if [[ -n "$(git status --porcelain --untracked-files=normal)" ]]; then
  echo "Refusing to build an official signed release from a dirty worktree." >&2
  exit 2
fi

if [[ -n "${HERMES_BRIDGE_APKSIGNER:-}" ]]; then
  apksigner="$HERMES_BRIDGE_APKSIGNER"
elif command -v apksigner >/dev/null 2>&1; then
  apksigner=$(command -v apksigner)
else
  echo "Set HERMES_BRIDGE_APKSIGNER to the exact Android SDK apksigner executable used for the release." >&2
  exit 2
fi
if [[ ! -x "$apksigner" ]]; then
  echo "apksigner is not executable: $apksigner" >&2
  exit 2
fi
if [[ ! -f "$HERMES_BRIDGE_SIGNING_STORE_FILE" ]]; then
  echo "Signing keystore does not exist: $HERMES_BRIDGE_SIGNING_STORE_FILE" >&2
  exit 2
fi

./gradlew --no-daemon :app:clean :app:assembleRelease

apk="app/build/outputs/apk/release/app-release.apk"
if [[ ! -s "$apk" ]]; then
  echo "Expected signed release APK was not produced: $apk" >&2
  exit 1
fi

"$apksigner" verify --verbose --print-certs --Werr "$apk"

checksum_file="$apk.sha256"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "$apk" | tee "$checksum_file"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "$apk" | tee "$checksum_file"
else
  echo "Neither sha256sum nor shasum is available." >&2
  exit 2
fi

printf 'source_commit=%s\n' "$(git rev-parse HEAD)"
printf 'signed_apk=%s\n' "$apk"
printf 'checksum=%s\n' "$checksum_file"

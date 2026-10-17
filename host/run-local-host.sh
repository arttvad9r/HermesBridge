#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
config_home=${XDG_CONFIG_HOME:-"$HOME/.config"}
state_home=${XDG_STATE_HOME:-"$HOME/.local/state"}
data_home=${XDG_DATA_HOME:-"$HOME/.local/share"}

config_dir=${TYPED_DROID_CONFIG_DIR:-"$config_home/typeddroid"}
state_dir=${TYPED_DROID_STATE_DIR:-"$state_home/typeddroid"}
apk_dir=${TYPED_DROID_APK_DIR:-"$data_home/typeddroid/apks"}
transfer_dir=${TYPED_DROID_TRANSFER_DIR:-"$data_home/typeddroid/transfers"}
token_file=${TYPED_DROID_ADMIN_TOKEN_FILE:-"$config_dir/admin-token"}
relay_bin=${TYPED_DROID_RELAY_BIN:-"$repo_root/relay/build/install/relay/bin/relay"}
port=${PORT:-8080}

mkdir -p "$config_dir" "$state_dir" "$apk_dir" "$transfer_dir"
chmod 700 "$config_dir" "$state_dir" "$apk_dir" "$transfer_dir"

if [[ ! -s "$token_file" ]]; then
    umask 077
    python3 - <<'PY' >"$token_file"
import secrets
print(secrets.token_hex(32))
PY
fi
chmod 600 "$token_file"

if [[ ! -x "$relay_bin" ]]; then
    "$repo_root/gradlew" --no-daemon :relay:installDist
fi

export TYPED_DROID_ADMIN_TOKEN="$(tr -d '\r\n' <"$token_file")"
export TYPED_DROID_ADMIN_TOKEN_FILE="$token_file"
export TYPED_DROID_STATE_DIR="$state_dir"
export TYPED_DROID_APK_DIR="$apk_dir"
export TYPED_DROID_TRANSFER_DIR="$transfer_dir"
export PORT="$port"

exec "$relay_bin"

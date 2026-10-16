#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
config_home=${XDG_CONFIG_HOME:-"$HOME/.config"}
state_home=${XDG_STATE_HOME:-"$HOME/.local/state"}
data_home=${XDG_DATA_HOME:-"$HOME/.local/share"}

config_dir=${HERMES_BRIDGE_CONFIG_DIR:-"$config_home/hermesbridge"}
state_dir=${HERMES_BRIDGE_STATE_DIR:-"$state_home/hermesbridge"}
apk_dir=${HERMES_BRIDGE_APK_DIR:-"$data_home/hermesbridge/apks"}
token_file=${HERMES_BRIDGE_ADMIN_TOKEN_FILE:-"$config_dir/admin-token"}
relay_bin=${HERMES_BRIDGE_RELAY_BIN:-"$repo_root/relay/build/install/relay/bin/relay"}
port=${PORT:-8080}

mkdir -p "$config_dir" "$state_dir" "$apk_dir"
chmod 700 "$config_dir" "$state_dir" "$apk_dir"

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

export HERMES_BRIDGE_ADMIN_TOKEN="$(tr -d '\r\n' <"$token_file")"
export HERMES_BRIDGE_ADMIN_TOKEN_FILE="$token_file"
export HERMES_BRIDGE_STATE_DIR="$state_dir"
export HERMES_BRIDGE_APK_DIR="$apk_dir"
export PORT="$port"

exec "$relay_bin"

#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
data_home=${XDG_DATA_HOME:-"$HOME/.local/share"}
bin_dir=${TYPED_DROID_BIN_DIR:-"$HOME/.local/bin"}
install_root=${TYPED_DROID_INSTALL_ROOT:-"$data_home/typeddroid"}
transfer_dir=${TYPED_DROID_TRANSFER_DIR:-"$data_home/typeddroid/transfers"}
runtime_dir="$install_root/relay-runtime"
legacy_runtime_dir="$install_root/host-runtime"
venv_dir="$install_root/mcp-venv"

for command in java python3 cp rm mv mkdir chmod mktemp; do
    command -v "$command" >/dev/null 2>&1 || {
        echo "error: required command is missing: $command" >&2
        exit 1
    }
done

java_major=$(java -version 2>&1 | awk -F'[".]' '/version/ {print $2; exit}')
if [[ -z "$java_major" || "$java_major" -lt 17 ]]; then
    echo "error: Java 17 or newer is required." >&2
    exit 1
fi

python3 - <<'PY'
import sys
if sys.version_info < (3, 10):
    raise SystemExit("error: Python 3.10 or newer is required.")
PY

"$repo_root/gradlew" --no-daemon :relay:installDist

mkdir -p "$install_root" "$bin_dir" "$transfer_dir"
chmod 700 "$transfer_dir"
tmp_dir=$(mktemp -d "$install_root/.install.XXXXXX")
cleanup() { rm -rf -- "$tmp_dir"; }
trap cleanup EXIT

cp -a "$repo_root/relay/build/install/relay" "$tmp_dir/relay-runtime"
rm -rf -- "$runtime_dir.new"
mv "$tmp_dir/relay-runtime" "$runtime_dir.new"
rm -rf -- "$runtime_dir"
mv "$runtime_dir.new" "$runtime_dir"

# Python virtual environments are not safely relocatable because generated entry-point
# scripts contain absolute interpreter paths. Build this one directly at its final path.
rm -rf -- "$venv_dir"
python3 -m venv "$venv_dir"
"$venv_dir/bin/python" -m pip install --quiet "$repo_root/mcp_adapter"

mcp_launcher="$bin_dir/typeddroid-mcp"
cat >"$mcp_launcher" <<EOF_MCP
#!/usr/bin/env bash
set -euo pipefail
export TYPED_DROID_RELAY_BIN="\${TYPED_DROID_RELAY_BIN:-$runtime_dir/bin/relay}"
export TYPED_DROID_INSTALL_ROOT="\${TYPED_DROID_INSTALL_ROOT:-$install_root}"
export TYPED_DROID_TRANSFER_DIR="\${TYPED_DROID_TRANSFER_DIR:-$transfer_dir}"
exec "$venv_dir/bin/python" -m typeddroid_mcp.server "\$@"
EOF_MCP
chmod 0755 "$mcp_launcher"

# Migrate older installs where the Bridge Host was a separate user-facing executable.
rm -f -- "$bin_dir/typeddroid-host"
rm -rf -- "$legacy_runtime_dir" "$install_root/scripts"

cat <<EOF_SUMMARY
Installed TypedDroid agent side:
  MCP: $mcp_launcher
  Transfer directory: $transfer_dir

The local relay is now an internal typeddroid-mcp runtime and starts automatically.
Add $bin_dir to PATH if needed.
No agent configuration was changed automatically.
EOF_SUMMARY

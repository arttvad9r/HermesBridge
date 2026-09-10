#!/usr/bin/env bash
set -euo pipefail

INSTALL_ROOT="/opt/hermes-bridge"
CONFIG_DIR="/etc/hermes-bridge"
STATE_DIR="/var/lib/hermes-bridge"
SERVICE_NAME="hermes-bridge-relay.service"
SERVICE_USER="hermes-bridge"
SERVICE_GROUP="hermes-bridge"
DEFAULT_PORT="8080"

usage() {
    cat <<'EOF'
Usage: sudo bash install-relay.sh PATH_TO_RELAY_TAR

Installs or upgrades the Hermes Bridge relay on a systemd-based Linux VPS.
The script does not configure DNS, TLS, Caddy/Nginx, or firewall rules.
EOF
}

if [[ ${1:-} == "-h" || ${1:-} == "--help" ]]; then
    usage
    exit 0
fi

if [[ $# -ne 1 ]]; then
    usage >&2
    exit 2
fi

if [[ ${EUID:-$(id -u)} -ne 0 ]]; then
    echo "error: run this installer as root (sudo)." >&2
    exit 1
fi

archive=$1
if [[ ! -f "$archive" ]]; then
    echo "error: relay archive not found: $archive" >&2
    exit 1
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
service_source="$script_dir/$SERVICE_NAME"
if [[ ! -f "$service_source" ]]; then
    echo "error: $SERVICE_NAME must be next to install-relay.sh" >&2
    exit 1
fi

for command in tar systemctl groupadd useradd install mktemp grep od tr cp mv rm chmod chown sleep; do
    if ! command -v "$command" >/dev/null 2>&1; then
        echo "error: required command is missing: $command" >&2
        exit 1
    fi
done

# The Gradle application distribution is expected to contain one top-level
# `relay/` directory. Reject absolute paths, parent traversal and unexpected
# archive roots before extraction.
while IFS= read -r entry; do
    [[ -z "$entry" ]] && continue
    if [[ "$entry" == /* || "$entry" == ../* || "$entry" == *"/../"* || "$entry" == *"/.." ]]; then
        echo "error: unsafe path in relay archive: $entry" >&2
        exit 1
    fi
    if [[ "$entry" != relay && "$entry" != relay/* ]]; then
        echo "error: unexpected archive root: $entry" >&2
        exit 1
    fi
done < <(tar -tf "$archive")

if ! tar -tf "$archive" | grep -qx 'relay/bin/relay'; then
    echo "error: archive does not contain relay/bin/relay" >&2
    exit 1
fi

tmp_dir=$(mktemp -d)
cleanup() {
    rm -rf -- "$tmp_dir"
}
trap cleanup EXIT

tar -xf "$archive" -C "$tmp_dir" --no-same-owner --no-same-permissions
if [[ ! -f "$tmp_dir/relay/bin/relay" || -L "$tmp_dir/relay/bin/relay" ]]; then
    echo "error: extracted relay launcher is missing or is not a regular file" >&2
    exit 1
fi
chmod 0755 "$tmp_dir/relay/bin/relay"

# `useradd` defaults differ across distributions. Create the service group
# explicitly, then bind the system account to that exact group.
groupadd --system --force "$SERVICE_GROUP"
if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
    nologin_shell=$(command -v nologin || true)
    [[ -n "$nologin_shell" ]] || nologin_shell=/bin/false
    useradd \
        --system \
        --gid "$SERVICE_GROUP" \
        --home-dir "$STATE_DIR" \
        --shell "$nologin_shell" \
        "$SERVICE_USER"
fi

install -d -o root -g root -m 0755 "$INSTALL_ROOT"
install -d -o root -g "$SERVICE_GROUP" -m 0750 "$CONFIG_DIR"
install -d -o "$SERVICE_USER" -g "$SERVICE_GROUP" -m 0700 "$STATE_DIR"

install -o root -g root -m 0644 \
    "$service_source" "/etc/systemd/system/$SERVICE_NAME"

env_file="$CONFIG_DIR/relay.env"
if [[ ! -f "$env_file" ]]; then
    # 32 random bytes encoded as 64 lowercase hex characters. Keep the token
    # local to this root-owned config; never print it to stdout.
    admin_token=$(od -An -N32 -tx1 /dev/urandom | tr -d ' \n')
    if [[ ${#admin_token} -ne 64 ]]; then
        echo "error: failed to generate relay admin token" >&2
        exit 1
    fi
    umask 0077
    cat >"$env_file" <<EOF
HERMES_BRIDGE_ADMIN_TOKEN=$admin_token
HERMES_BRIDGE_STATE_DIR=$STATE_DIR
PORT=$DEFAULT_PORT
EOF
fi
chown root:"$SERVICE_GROUP" "$env_file"
chmod 0640 "$env_file"

new_dir="$INSTALL_ROOT/relay.new"
old_dir="$INSTALL_ROOT/relay.previous"
rm -rf -- "$new_dir"
cp -a -- "$tmp_dir/relay" "$new_dir"
chown -R root:root "$new_dir"

rm -rf -- "$old_dir"
if [[ -d "$INSTALL_ROOT/relay" ]]; then
    mv -- "$INSTALL_ROOT/relay" "$old_dir"
fi
mv -- "$new_dir" "$INSTALL_ROOT/relay"

rollback_binary() {
    rm -rf -- "$INSTALL_ROOT/relay"
    if [[ -d "$old_dir" ]]; then
        mv -- "$old_dir" "$INSTALL_ROOT/relay"
        systemctl daemon-reload || true
        systemctl restart "$SERVICE_NAME" || true
    fi
}

systemctl daemon-reload
if ! systemctl enable "$SERVICE_NAME"; then
    echo "error: systemd could not enable $SERVICE_NAME; attempting rollback" >&2
    rollback_binary
    exit 1
fi

# `enable --now` does not restart an already-running unit after an upgrade.
# Always restart explicitly so the new distribution is actually executing.
if ! systemctl restart "$SERVICE_NAME"; then
    echo "error: systemd could not start the new relay; attempting rollback" >&2
    rollback_binary
    exit 1
fi
sleep 1
if ! systemctl is-active --quiet "$SERVICE_NAME"; then
    echo "error: new relay did not remain active; attempting rollback" >&2
    rollback_binary
    exit 1
fi

rm -rf -- "$old_dir"

echo "Hermes Bridge relay installed and running."
echo "Next: configure HTTPS/WSS reverse proxying as documented in docs/DEPLOYMENT.md."

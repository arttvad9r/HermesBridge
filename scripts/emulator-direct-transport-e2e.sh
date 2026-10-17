#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$repo_root"

SDK=${ANDROID_HOME:-/home/artt/Android/Sdk}
ADB=${ADB:-"$SDK/platform-tools/adb"}
SERIAL=${1:-emulator-5554}
APP=io.github.arttvad9r.typeddroid
PROBE=io.github.arttvad9r.typeddroid.RelayTransportProbe

die() { echo "error: $*" >&2; exit 1; }
command -v keytool >/dev/null 2>&1 || die "keytool is required"
[[ -x "$ADB" ]] || die "adb not found: $ADB"
"$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 || die "emulator is not online: $SERIAL"

read -r ADMIN_PORT DIRECT_PORT < <(python3 - <<'PY'
import socket
ports=[]
for _ in range(2):
    s=socket.socket(); s.bind(('127.0.0.1', 0)); ports.append(s.getsockname()[1]); s.close()
print(*ports)
PY
)

tmp=$(mktemp -d /tmp/typeddroid-direct-e2e.XXXXXX)
relay_pid=''
cleanup() {
  if [[ -n "$relay_pid" ]]; then
    kill "$relay_pid" >/dev/null 2>&1 || true
    wait "$relay_pid" >/dev/null 2>&1 || true
  fi
  rm -rf -- "$tmp"
}
trap cleanup EXIT

password=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
admin_token=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
keystore="$tmp/direct.p12"
keytool -genkeypair \
  -alias typeddroid-direct \
  -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
  -validity 30 -storetype PKCS12 \
  -keystore "$keystore" -storepass "$password" -keypass "$password" \
  -dname 'CN=typeddroid-direct' -ext 'SAN=dns:typeddroid-direct' -noprompt \
  >/dev/null 2>&1
pin=$(keytool -exportcert -alias typeddroid-direct -keystore "$keystore" -storepass "$password" 2>/dev/null | sha256sum | awk '{print $1}')
if [[ ${pin:0:1} == 0 ]]; then bad_pin="1${pin:1}"; else bad_pin="0${pin:1}"; fi

export ANDROID_HOME="$SDK"
./gradlew --no-daemon :relay:installDist :app:assembleDebug >/dev/null

PORT="$ADMIN_PORT" \
TYPED_DROID_ADMIN_TOKEN="$admin_token" \
TYPED_DROID_STATE_DIR="$tmp/state" \
TYPED_DROID_DIRECT_KEYSTORE="$keystore" \
TYPED_DROID_DIRECT_KEYSTORE_PASSWORD="$password" \
TYPED_DROID_DIRECT_KEY_ALIAS=typeddroid-direct \
TYPED_DROID_DIRECT_PORT="$DIRECT_PORT" \
TYPED_DROID_DIRECT_HOSTS=10.0.2.2 \
./relay/build/install/relay/bin/relay >"$tmp/relay.log" 2>&1 &
relay_pid=$!

for _ in $(seq 1 100); do
  curl -fsS "http://127.0.0.1:$ADMIN_PORT/health" >/dev/null 2>&1 && break
  kill -0 "$relay_pid" 2>/dev/null || { cat "$tmp/relay.log" >&2; die "relay exited before health became ready"; }
  sleep .1
done
curl -fsS "http://127.0.0.1:$ADMIN_PORT/health" | grep -qx ok
curl -kfsS "https://127.0.0.1:$DIRECT_PORT/health" | grep -qx ok

direct_admin_code=$(curl -ksS -o /dev/null -w '%{http_code}' \
  -H "Authorization: Bearer $admin_token" \
  "https://127.0.0.1:$DIRECT_PORT/api/v1/devices")
[[ "$direct_admin_code" == 404 ]]
echo "PASS direct TLS ingress hides admin API"

"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
base_apk=$("$ADB" -s "$SERIAL" shell pm path "$APP" | head -1 | cut -d: -f2 | tr -d '\r')
[[ -n "$base_apk" ]]

probe() {
  timeout 15s "$ADB" -s "$SERIAL" exec-out run-as "$APP" \
    env "CLASSPATH=$base_apk" \
    /system/bin/app_process /system/bin "$PROBE" "$@" 2>&1 | tr -d '\r'
}

good=$(probe "wss://10.0.2.2:$DIRECT_PORT/ws/device" "$pin" || true)
printf '%s\n' "$good" | grep -q '^connected=true$'
echo "PASS Android pinned direct WSS"

bad=$(probe "wss://10.0.2.2:$DIRECT_PORT/ws/device" "$bad_pin" || true)
printf '%s\n' "$bad" | grep -q '^connected=false$'
if printf '%s\n' "$bad" | grep -q '^connected=true$'; then
  die "mismatched TLS pin was accepted"
fi
echo "PASS Android rejects mismatched direct TLS pin"

echo "TYPEDDROID_DIRECT_TRANSPORT_E2E_OK serial=$SERIAL"

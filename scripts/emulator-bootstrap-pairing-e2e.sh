#!/usr/bin/env bash
set -euo pipefail

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)
cd "$repo_root"
SDK=${ANDROID_HOME:-/home/artt/Android/Sdk}
ADB=${ADB:-"$SDK/platform-tools/adb"}
SERIAL=${1:-emulator-5554}
APP=io.github.arttvad9r.typeddroid
SERVICE=io.github.arttvad9r.typeddroid/.BridgeForegroundService
PAIR_ACTION=io.github.arttvad9r.typeddroid.PAIR

die() { echo "error: $*" >&2; exit 1; }
command -v keytool >/dev/null 2>&1 || die "keytool is required"
[[ -x "$ADB" ]] || die "adb not found: $ADB"
"$ADB" -s "$SERIAL" get-state >/dev/null 2>&1 || die "emulator is not online: $SERIAL"
read -r ADMIN_PORT DIRECT_PORT < <(python3 - <<'PY'
import socket
ports=[]
for _ in range(2):
    s=socket.socket(); s.bind(('127.0.0.1',0)); ports.append(s.getsockname()[1]); s.close()
print(*ports)
PY
)
tmp=$(mktemp -d /tmp/typeddroid-bootstrap-e2e.XXXXXX)
relay_pid=''
cleanup() {
  "$ADB" -s "$SERIAL" shell am force-stop "$APP" >/dev/null 2>&1 || true
  if [[ -n "$relay_pid" ]]; then kill "$relay_pid" >/dev/null 2>&1 || true; wait "$relay_pid" >/dev/null 2>&1 || true; fi
  rm -rf -- "$tmp"
}
trap cleanup EXIT
password=$(python3 -c 'import secrets; print(secrets.token_hex(24))')
admin_token=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
keystore="$tmp/direct.p12"
keytool -genkeypair -alias typeddroid-direct -keyalg EC -groupname secp256r1 -sigalg SHA256withECDSA \
  -validity 30 -storetype PKCS12 -keystore "$keystore" -storepass "$password" -keypass "$password" \
  -dname 'CN=typeddroid-direct' -ext 'SAN=dns:typeddroid-direct' -noprompt >/dev/null 2>&1
export ANDROID_HOME="$SDK"
./gradlew --no-daemon :relay:installDist :app:assembleDebug >/dev/null
PORT="$ADMIN_PORT" TYPED_DROID_ADMIN_TOKEN="$admin_token" TYPED_DROID_STATE_DIR="$tmp/state" \
TYPED_DROID_DIRECT_KEYSTORE="$keystore" TYPED_DROID_DIRECT_KEYSTORE_PASSWORD="$password" \
TYPED_DROID_DIRECT_KEY_ALIAS=typeddroid-direct TYPED_DROID_DIRECT_PORT="$DIRECT_PORT" \
TYPED_DROID_DIRECT_HOSTS=10.0.2.2 ./relay/build/install/relay/bin/relay >"$tmp/relay.log" 2>&1 &
relay_pid=$!
for _ in $(seq 1 100); do
  curl -fsS "http://127.0.0.1:$ADMIN_PORT/health" >/dev/null 2>&1 && break
  kill -0 "$relay_pid" 2>/dev/null || { cat "$tmp/relay.log" >&2; die "relay exited"; }
  sleep .1
done
pairing=$(curl -fsS -X POST -H "Authorization: Bearer $admin_token" "http://127.0.0.1:$ADMIN_PORT/api/v1/pairing-codes")
hints=$(curl -fsS -H "Authorization: Bearer $admin_token" "http://127.0.0.1:$ADMIN_PORT/api/v1/transport-hints")
code=$(python3 -c 'import json,sys; print(json.loads(sys.argv[1])["code"])' "$pairing")
[[ -n "$code" ]]
python3 -c 'import json,sys; h=json.loads(sys.argv[1])["hints"]; assert len(h)==1 and h[0]["webSocketUrl"].startswith("wss://10.0.2.2:")' "$hints"

"$ADB" -s "$SERIAL" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null
"$ADB" -s "$SERIAL" shell pm clear "$APP" >/dev/null
# Bring the app to foreground first: Android 12+ forbids arbitrary background FGS starts.
"$ADB" -s "$SERIAL" shell am start -W -n "$APP/.MainActivity" >/dev/null
sleep .3
# Run am as the app UID so the debug app can start its own non-exported service.
cmd="run-as $APP am start-foreground-service --user 0 -n $SERVICE -a $PAIR_ACTION --es pairing_code '$code' --es pairing_hints '$hints'"
timeout 15s "$ADB" -s "$SERIAL" shell "$cmd" >/dev/null

connected=false
for _ in $(seq 1 100); do
  devices=$(curl -fsS -H "Authorization: Bearer $admin_token" "http://127.0.0.1:$ADMIN_PORT/api/v1/devices")
  if python3 -c 'import json,sys; d=json.loads(sys.argv[1]); raise SystemExit(0 if len(d)==1 and d[0]["connected"] else 1)' "$devices"; then
    connected=true
    break
  fi
  sleep .1
done
[[ "$connected" == true ]] || { cat "$tmp/relay.log" >&2; die "device did not authenticate through bootstrap direct endpoint"; }
record=$("$ADB" -s "$SERIAL" shell run-as "$APP" cat shared_prefs/typed_droid_pairing.xml 2>/dev/null | tr -d '\r')
printf '%s\n' "$record" | grep -q 'v2|active|safe|device_'
echo "PASS first pairing authenticates over pinned bootstrap endpoint"
echo "PASS pairing persisted ACTIVE SAFE identity without build-time relay"
echo "TYPEDDROID_BOOTSTRAP_PAIRING_E2E_OK serial=$SERIAL"

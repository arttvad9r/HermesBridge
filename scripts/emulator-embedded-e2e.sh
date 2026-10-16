#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"
ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}
ADB=${ADB:-$ANDROID_SDK_ROOT/platform-tools/adb}
SERIAL=${ANDROID_SERIAL:-emulator-5554}
APP=io.github.arttvad9r.typeddroid
FIXTURE=io.github.arttvad9r.typeddroid.fixture
PROBE=io.github.arttvad9r.typeddroid.EmbeddedPrivilegedProbe
HELPER=io.github.arttvad9r.typeddroid.EmbeddedPrivilegedMain
[[ -x "$ADB" ]] || { echo "adb not found: $ADB" >&2; exit 1; }
"$ADB" -s "$SERIAL" get-state >/dev/null
export ANDROID_HOME="$ANDROID_SDK_ROOT" ANDROID_SDK_ROOT
./gradlew --no-daemon :app:assembleDebug :e2e-fixture:assembleDebug >/dev/null
app_apk=app/build/outputs/apk/debug/app-debug.apk
fixture_apk=e2e-fixture/build/outputs/apk/debug/e2e-fixture-debug.apk
"$ADB" -s "$SERIAL" install -r "$app_apk" >/dev/null
base_apk=$("$ADB" -s "$SERIAL" shell pm path "$APP" | sed -n 's/^package://p' | head -1)
[[ -n "$base_apk" ]]
read -r port token < <(python3 - <<'PY'
import secrets
print(secrets.randbelow(20000) + 35000, secrets.token_hex(32))
PY
)
probe() {
  "$ADB" -s "$SERIAL" exec-out run-as "$APP" env "CLASSPATH=$base_apk" \
    /system/bin/app_process /system/bin "$PROBE" "$port" "$token" "$@"
}
cleanup() {
  probe shutdown >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" uninstall "$FIXTURE" >/dev/null 2>&1 || true
  rm -f /tmp/typeddroid-e2e-large.txt /tmp/typeddroid-e2e-battery.txt
}
trap cleanup EXIT
"$ADB" -s "$SERIAL" shell "pkill -f EmbeddedPrivilegedMain 2>/dev/null || true" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" shell \
  "CLASSPATH='$base_apk' /system/bin/app_process /system/bin $HELPER --port $port --token '$token' </dev/null >/data/local/tmp/typeddroid-helper-e2e.log 2>&1 &"
sleep 1
listener=$("$ADB" -s "$SERIAL" shell ss -lnt 2>/dev/null | grep ":$port " || true)
[[ "$listener" == *"[::1]:$port"* || "$listener" == *"127.0.0.1:$port"* ]]
echo "PASS helper bound to loopback only"
[[ $(probe ping | tr -d '\r') == "ping=true" ]]
echo "PASS authenticated loopback helper"
bad_token=$(printf '0%.0s' {1..64})
bad_ping=$("$ADB" -s "$SERIAL" exec-out run-as "$APP" env "CLASSPATH=$base_apk" \
  /system/bin/app_process /system/bin "$PROBE" "$port" "$bad_token" ping | tr -d '\r')
[[ "$bad_ping" == "ping=false" ]]
echo "PASS invalid helper token denied"
id_out=$(probe exec 5000 id)
printf '%s\n' "$id_out" | grep -q '^exitCode=0$'
id_b64=$(printf '%s\n' "$id_out" | sed -n 's/^stdoutBase64=//p')
python3 - "$id_b64" <<'PY'
import base64, sys
text=base64.b64decode(sys.argv[1]).decode()
assert 'uid=2000(shell)' in text, text
PY
echo "PASS shell UID execution"
probe exec 5000 head -c 500000 /dev/zero > /tmp/typeddroid-e2e-large.txt
python3 - <<'PY'
import base64
v={}
for line in open('/tmp/typeddroid-e2e-large.txt'):
    k, _, x=line.rstrip('\n').partition('='); v[k]=x
assert v['exitCode']=='0' and v['stdoutTruncated']=='true'
assert len(base64.b64decode(v['stdoutBase64'])) == 384 * 1024
PY
echo "PASS bounded shell output"
timeout_out=$(probe exec 100 sleep 1 2>&1 || true)
printf '%s\n' "$timeout_out" | grep -q 'remoteTimedOut=true'
[[ $(probe ping | tr -d '\r') == "ping=true" ]]
echo "PASS command timeout keeps helper alive"
fixture_size=$(stat -c %s "$fixture_apk")
fixture_sha=$(sha256sum "$fixture_apk" | awk '{print $1}')
"$ADB" -s "$SERIAL" uninstall "$FIXTURE" >/dev/null 2>&1 || true
"$ADB" -s "$SERIAL" push "$fixture_apk" /data/local/tmp/typeddroid-e2e-fixture.apk >/dev/null
"$ADB" -s "$SERIAL" shell run-as "$APP" mkdir -p files
"$ADB" -s "$SERIAL" shell run-as "$APP" cp /data/local/tmp/typeddroid-e2e-fixture.apk files/typeddroid-e2e-fixture.apk
wrong_sha=$(printf '0%.0s' {1..64})
bad_install=$(probe install files/typeddroid-e2e-fixture.apk e2e-fixture-debug.apk "$fixture_size" "$wrong_sha" false 2>&1 || true)
printf '%s\n' "$bad_install" | grep -q 'SHA-256 mismatch'
[[ $(probe ping | tr -d '\r') == "ping=true" ]]
[[ -z $("$ADB" -s "$SERIAL" shell 'find /data/local/tmp -maxdepth 1 -name "typed-droid-*.apk" -print' 2>/dev/null) ]]
echo "PASS rejected APK hash cleans staging and keeps helper alive"
probe install files/typeddroid-e2e-fixture.apk e2e-fixture-debug.apk "$fixture_size" "$fixture_sha" false | grep -q '^exitCode=0$'
"$ADB" -s "$SERIAL" shell pm list packages "$FIXTURE" | grep -q "$FIXTURE"
echo "PASS APK streaming install"
"$ADB" -s "$SERIAL" shell monkey -p "$FIXTURE" 1 >/dev/null 2>&1
sleep .3
[[ -n $("$ADB" -s "$SERIAL" shell pidof "$FIXTURE" || true) ]]
probe force-stop "$FIXTURE" | grep -q '^exitCode=0$'
sleep .2
[[ -z $("$ADB" -s "$SERIAL" shell pidof "$FIXTURE" || true) ]]
echo "PASS force-stop"
"$ADB" -s "$SERIAL" shell pm grant "$FIXTURE" android.permission.CAMERA
probe revoke "$FIXTURE" android.permission.CAMERA 0 | grep -q '^exitCode=0$'
"$ADB" -s "$SERIAL" shell dumpsys package "$FIXTURE" | grep 'android.permission.CAMERA' | grep -q 'granted=false'
echo "PASS permission revoke"
probe battery > /tmp/typeddroid-e2e-battery.txt
python3 - <<'PY'
import base64
v={}
for line in open('/tmp/typeddroid-e2e-battery.txt'):
    k, _, x=line.rstrip('\n').partition('='); v[k]=x
assert v['exitCode']=='0' and v['stdoutTruncated']=='false'
raw=base64.b64decode(v['stdoutBase64'])
assert b',i,vers,' in raw and b',l,pws,' in raw
PY
echo "PASS batterystats"
probe uninstall "$FIXTURE" false | grep -q '^exitCode=0$'
! "$ADB" -s "$SERIAL" shell pm list packages "$FIXTURE" | grep -q "$FIXTURE"
echo "PASS uninstall"
"$ADB" -s "$SERIAL" shell am force-stop "$APP"
[[ $(probe ping | tr -d '\r') == "ping=true" ]]
echo "PASS helper survives app force-stop"
echo "TYPEDDROID_EMBEDDED_EMULATOR_E2E_OK serial=$SERIAL"

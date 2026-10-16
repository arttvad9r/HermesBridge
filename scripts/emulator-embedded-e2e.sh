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
echo "STEP build debug APKs"
timeout 120s ./gradlew --no-daemon :app:assembleDebug :e2e-fixture:assembleDebug >/dev/null
app_apk=app/build/outputs/apk/debug/app-debug.apk
fixture_apk=e2e-fixture/build/outputs/apk/debug/e2e-fixture-debug.apk
echo "STEP install TypedDroid"
timeout 20s "$ADB" -s "$SERIAL" install -r "$app_apk" >/dev/null
timeout 10s "$ADB" -s "$SERIAL" shell cmd notification allow_listener "$APP/.TypedDroidNotificationListenerService" >/dev/null
base_apk=$("$ADB" -s "$SERIAL" shell pm path "$APP" | sed -n 's/^package://p' | head -1)
[[ -n "$base_apk" ]]
read -r port token < <(python3 - <<'PY'
import secrets
print(secrets.randbelow(20000) + 35000, secrets.token_hex(32))
PY
)
probe() {
  timeout 15s "$ADB" -s "$SERIAL" exec-out run-as "$APP" env "CLASSPATH=$base_apk" \
    /system/bin/app_process /system/bin "$PROBE" "$port" "$token" "$@"
}
cleanup() {
  probe shutdown >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" shell cmd notification disallow_listener "$APP/.TypedDroidNotificationListenerService" >/dev/null 2>&1 || true
  "$ADB" -s "$SERIAL" uninstall "$FIXTURE" >/dev/null 2>&1 || true
  rm -f /tmp/typeddroid-e2e-large.txt /tmp/typeddroid-e2e-battery.txt /tmp/typeddroid-e2e-ui.xml.b64 /tmp/typeddroid-e2e-ui-after.txt /tmp/typeddroid-e2e-edit-center /tmp/typeddroid-e2e-screen.txt /tmp/typeddroid-e2e-setting.txt /tmp/typeddroid-e2e-appops.txt /tmp/typeddroid-e2e-processes.txt /tmp/typeddroid-e2e-logcat.txt /tmp/typeddroid-e2e-fs-stat.txt /tmp/typeddroid-e2e-fs-list.txt /tmp/typeddroid-e2e-fs-read.txt
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
echo "STEP privileged + UI E2E"
timeout 20s "$ADB" -s "$SERIAL" push "$fixture_apk" /data/local/tmp/typeddroid-e2e-fixture.apk >/dev/null
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
"$ADB" -s "$SERIAL" shell pm grant "$FIXTURE" android.permission.POST_NOTIFICATIONS
"$ADB" -s "$SERIAL" shell run-as "$FIXTURE" rm -f shared_prefs/notification_e2e.xml >/dev/null 2>&1 || true

# Trusted structured system-control commands are built on the same bounded shell helper.
probe exec 5000 settings put global typed_droid_e2e 1 | grep -q '^exitCode=0$'
probe exec 5000 settings get global typed_droid_e2e > /tmp/typeddroid-e2e-setting.txt
python3 - <<'PYSYSTEM'
import base64
v={}
for line in open('/tmp/typeddroid-e2e-setting.txt'):
    k, _, x=line.rstrip('\n').partition('='); v[k]=x
assert v['exitCode']=='0'
assert base64.b64decode(v['stdoutBase64']).decode().strip() == '1'
PYSYSTEM
probe exec 5000 settings delete global typed_droid_e2e >/dev/null
echo "PASS settings get/put"

probe exec 5000 appops set "$FIXTURE" CAMERA ignore | grep -q '^exitCode=0$'
probe exec 5000 appops get "$FIXTURE" CAMERA > /tmp/typeddroid-e2e-appops.txt
python3 - <<'PYSYSTEM'
import base64
v={}
for line in open('/tmp/typeddroid-e2e-appops.txt'):
    k, _, x=line.rstrip('\n').partition('='); v[k]=x
assert v['exitCode']=='0'
assert 'ignore' in base64.b64decode(v['stdoutBase64']).decode().lower()
PYSYSTEM
probe exec 5000 appops set "$FIXTURE" CAMERA default >/dev/null
echo "PASS appops get/set"

probe exec 5000 ps -A -o USER,PID,PPID,NAME > /tmp/typeddroid-e2e-processes.txt
probe exec 5000 logcat -d -t 5 -v threadtime -b main > /tmp/typeddroid-e2e-logcat.txt
python3 - <<'PYSYSTEM'
import base64
def read(path):
    v={}
    for line in open(path):
        k, _, x=line.rstrip('\n').partition('='); v[k]=x
    assert v['exitCode']=='0'
    return base64.b64decode(v['stdoutBase64']).decode(errors='replace')
assert 'init' in read('/tmp/typeddroid-e2e-processes.txt')
read('/tmp/typeddroid-e2e-logcat.txt')
PYSYSTEM
echo "PASS process list + logcat"

probe exec 10000 am start -W -n "$FIXTURE/.MainActivity" | grep -q '^exitCode=0$'
sleep .3
[[ -n $("$ADB" -s "$SERIAL" shell pidof "$FIXTURE" || true) ]]
echo "PASS structured activity start command"

# Real NotificationListenerService / PendingIntent / RemoteInput flow.
notification_probe() {
  timeout 10s "$ADB" -s "$SERIAL" shell am broadcast --receiver-foreground \
    -n "$APP/.NotificationE2eReceiver" "$@" | sed -n 's/.*data="\(.*\)".*/\1/p'
}
notification_find=$(notification_probe --es operation find --es packageName "$FIXTURE")
notification_key=$(printf '%s\n' "$notification_find" | sed -n 's/^key=\([^;]*\);.*/\1/p')
notification_reply_index=$(printf '%s\n' "$notification_find" | sed -n 's/.*;replyIndex=\([-0-9]*\);.*/\1/p')
[[ -n "$notification_key" && "$notification_reply_index" == "1" ]]
[[ $(notification_probe --es operation action --es keyBase64 "$notification_key" --ei actionIndex 0) == "ok" ]]
sleep .2
"$ADB" -s "$SERIAL" shell run-as "$FIXTURE" cat shared_prefs/notification_e2e.xml | grep -q 'name="marked" value="true"'
[[ $(notification_probe --es operation reply --es keyBase64 "$notification_key" --ei actionIndex "$notification_reply_index" --es text TypedDroidReply) == "ok" ]]
sleep .2
"$ADB" -s "$SERIAL" shell run-as "$FIXTURE" cat shared_prefs/notification_e2e.xml | grep -q '<string name="reply">TypedDroidReply</string>'
[[ $(notification_probe --es operation open --es keyBase64 "$notification_key") == "ok" ]]
sleep .2
"$ADB" -s "$SERIAL" shell run-as "$FIXTURE" cat shared_prefs/notification_e2e.xml | grep -q 'name="opened" value="true"'
[[ $(notification_probe --es operation dismiss --es keyBase64 "$notification_key") == "ok" ]]
sleep .2
[[ $(notification_probe --es operation find --es packageName "$FIXTURE") == "not-found" ]]
echo "PASS notification list/action/reply/open/dismiss"

# Toybox primitives used by the TRUSTED typed fs.* layer.
probe exec 5000 mkdir -p /data/local/tmp/typeddroid-fs-e2e/subdir | grep -q '^exitCode=0$'
FS_PAYLOAD=$(printf 'TypedDroid file E2E' | base64 -w0)
probe exec 5000 "printf '%s' '$FS_PAYLOAD' | base64 -d > '/data/local/tmp/typeddroid-fs-e2e/a file.txt'" | grep -q '^exitCode=0$'
probe exec 5000 "stat -c '%F|%s|%Y' '/data/local/tmp/typeddroid-fs-e2e/a file.txt'" > /tmp/typeddroid-e2e-fs-stat.txt
probe exec 5000 "find '/data/local/tmp/typeddroid-fs-e2e' -mindepth 1 -maxdepth 1 -print0" > /tmp/typeddroid-e2e-fs-list.txt
probe exec 5000 "dd if='/data/local/tmp/typeddroid-fs-e2e/a file.txt' bs=1 skip=0 count=128" > /tmp/typeddroid-e2e-fs-read.txt
python3 - <<'PYFS'
import base64
def read(path):
    v={}
    for line in open(path):
        k, _, x=line.rstrip('\n').partition('='); v[k]=x
    assert v['exitCode']=='0'
    return base64.b64decode(v['stdoutBase64'])
stat=read('/tmp/typeddroid-e2e-fs-stat.txt').decode().strip()
assert stat.startswith('regular file|19|'), stat
listing=read('/tmp/typeddroid-e2e-fs-list.txt')
assert b'a file.txt\x00' in listing and b'subdir\x00' in listing
assert read('/tmp/typeddroid-e2e-fs-read.txt') == b'TypedDroid file E2E'
PYFS
probe exec 5000 "cp -f '/data/local/tmp/typeddroid-fs-e2e/a file.txt' '/data/local/tmp/typeddroid-fs-e2e/copy.txt'" | grep -q '^exitCode=0$'
probe exec 5000 "mv -f '/data/local/tmp/typeddroid-fs-e2e/copy.txt' '/data/local/tmp/typeddroid-fs-e2e/moved.txt'" | grep -q '^exitCode=0$'
probe exec 5000 "rm -f '/data/local/tmp/typeddroid-fs-e2e/moved.txt'" | grep -q '^exitCode=0$'
[[ -z $("$ADB" -s "$SERIAL" shell 'test -e /data/local/tmp/typeddroid-fs-e2e/moved.txt && echo exists' || true) ]]
echo "PASS trusted filesystem primitives"

probe open-app "$FIXTURE" | grep -q '^exitCode=0$'
sleep .5
[[ -n $("$ADB" -s "$SERIAL" shell pidof "$FIXTURE" || true) ]]
echo "PASS typed open-app"

probe ui-hierarchy > /tmp/typeddroid-e2e-ui.xml.b64
python3 - <<'PYUI'
import base64, re
values={}
for line in open('/tmp/typeddroid-e2e-ui.xml.b64'):
    k, _, v=line.rstrip('\n').partition('='); values[k]=v
assert values['exitCode']=='0'
assert values['stdoutTruncated']=='false'
xml=base64.b64decode(values['stdoutBase64']).decode()
assert 'TypedDroid E2E Fixture' in xml
m=re.search(r'class="android.widget.EditText"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
assert m, xml[:1000]
x1,y1,x2,y2=map(int,m.groups())
open('/tmp/typeddroid-e2e-edit-center','w').write(f'{(x1+x2)//2} {(y1+y2)//2}\n')
PYUI
read -r edit_x edit_y < /tmp/typeddroid-e2e-edit-center
probe ui-tap "$edit_x" "$edit_y" | grep -q '^exitCode=0$'
probe ui-text TypedDroidTest | grep -q '^exitCode=0$'
sleep .2
probe ui-hierarchy > /tmp/typeddroid-e2e-ui-after.txt
python3 - <<'PYUI'
import base64
v={}
for line in open('/tmp/typeddroid-e2e-ui-after.txt'):
    k, _, x=line.rstrip('\n').partition('='); v[k]=x
xml=base64.b64decode(v['stdoutBase64']).decode()
assert 'TypedDroidTest' in xml, xml[-1500:]
PYUI
echo "PASS hierarchy + tap + text input"

probe ui-swipe 600 1800 600 900 250 | grep -q '^exitCode=0$'
probe ui-key 4 | grep -q '^exitCode=0$'
echo "PASS swipe + key event"

probe open-app "$FIXTURE" >/dev/null
sleep .3
probe ui-screenshot > /tmp/typeddroid-e2e-screen.txt
python3 - <<'PYSHOT'
import base64
v={}
for line in open('/tmp/typeddroid-e2e-screen.txt'):
    k, _, x=line.rstrip('\n').partition('='); v[k]=x
raw=base64.b64decode(v['base64'])
assert v['mimeType']=='image/jpeg'
assert 0 < int(v['bytes']) <= 128 * 1024
assert int(v['width']) > 0 and int(v['height']) > 0
assert raw[:2] == b'\xff\xd8' and raw[-2:] == b'\xff\xd9'
PYSHOT
echo "PASS bounded JPEG screenshot"

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

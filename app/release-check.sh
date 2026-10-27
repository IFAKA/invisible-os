#!/bin/sh
# Build the signed release APK, run it on an own headless emulator, grep logcat for crashes. Prints one PASS/FAIL line.
# Never touches a real phone: only the emulator serial below is used. Log: app/logs/release-check.log
cd "$(dirname "$0")"
mkdir -p logs; LOG=$PWD/logs/release-check.log; : > "$LOG"
SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}; PORT=5590; S=emulator-$PORT
export ANDROID_HOME=$SDK
EMUPID=
fail() { echo "FAIL: $1 (see app/logs/release-check.log)"; [ -n "$EMUPID" ] && kill "$EMUPID" 2>/dev/null; exit 1; }
./release.sh >>"$LOG" 2>&1 || fail "release build"
APK=$(ls -t ../dist/invisible-os-*.apk | head -1)
if ! adb -s $S get-state >/dev/null 2>&1; then
  "$SDK/emulator/emulator" -avd invisible-os-test -port $PORT -no-window -no-snapshot-save >>"$LOG" 2>&1 &
  EMUPID=$!
fi
adb -s $S wait-for-device
i=0; until [ "$(adb -s $S shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
  i=$((i+1)); [ $i -gt 120 ] && fail "emulator boot timeout"; sleep 3; done
[ -n "$EMUPID" ] && sleep 60   # audio service needs a minute after boot or AudioTrack fails
adb -s $S uninstall com.faka.invisible >/dev/null 2>&1
adb -s $S logcat -c
adb -s $S install -r "$APK" >>"$LOG" 2>&1 || fail "install"
adb -s $S shell am start -W -n com.faka.invisible/.MainActivity >>"$LOG" 2>&1
sleep 25
adb -s $S exec-out screencap -p > logs/release-check.png
adb -s $S logcat -d > logs/release-check-logcat.txt
BAD=$(grep -E "AndroidRuntime|FATAL EXCEPTION|ClassNotFoundException|NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|UnsatisfiedLinkError|ANR in com.faka|Process com.faka.invisible .*has died" logs/release-check-logcat.txt | head -10)
echo "$BAD" >>"$LOG"
adb -s $S shell pidof com.faka.invisible >/dev/null || BAD="$BAD process not running"
[ -n "$EMUPID" ] && kill "$EMUPID" 2>/dev/null
[ -z "$BAD" ] && echo "PASS: release APK $APK starts clean" || { echo "FAIL: $(echo "$BAD" | head -1) (see app/logs/release-check.log)"; exit 1; }

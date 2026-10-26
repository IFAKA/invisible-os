#!/bin/sh
# rebuild + install + start
cd "$(dirname "$0")" || exit 1
# Shared phone: another session may be testing on it, or this tree may lack code that is already on master.
~/.claude/bin/phone check || exit 1
if [ -z "$PHONE_FORCE" ] && git rev-parse -q --verify master >/dev/null && ! git merge-base --is-ancestor master HEAD; then
  echo "BLOCKED: master has commits this tree lacks (e.g. another session's dev hook). Rebase on master, or PHONE_FORCE=1." >&2; exit 1
fi
./gradlew assembleDebug -q && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell pm grant com.faka.invisible android.permission.POST_NOTIFICATIONS; adb shell dumpsys deviceidle whitelist +com.faka.invisible; adb shell am start -n com.faka.invisible/.MainActivity

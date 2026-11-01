#!/bin/sh
# Give the app every permission over adb, so first use needs no Settings taps. Safe to run again.
# Button access (accessibility) is appended to the list, never replacing other apps' services.
P=com.faka.invisible
S=$P/$P.ButtonAccessibilityService
cur=$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')
case "$cur" in
  *"$S"*) ;;
  null|"") adb shell settings put secure enabled_accessibility_services "$S" ;;
  *) adb shell settings put secure enabled_accessibility_services "$cur:$S" ;;
esac
adb shell settings put secure accessibility_enabled 1
adb shell cmd notification allow_listener $P/$P.ControllerNotificationListener
adb shell appops set $P GET_USAGE_STATS allow
for perm in POST_NOTIFICATIONS READ_CALENDAR ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION READ_CONTACTS CALL_PHONE ANSWER_PHONE_CALLS READ_PHONE_STATE; do
  adb shell pm grant $P android.permission.$perm 2>/dev/null
done
adb shell dumpsys deviceidle whitelist +$P >/dev/null
echo "granted: button access, notification access, usage access, runtime permissions"

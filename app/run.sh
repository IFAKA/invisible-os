#!/bin/sh
# rebuild + install + start
cd "$(dirname "$0")" && ./gradlew assembleDebug -q && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell pm grant com.faka.invisible android.permission.POST_NOTIFICATIONS; adb shell dumpsys deviceidle whitelist +com.faka.invisible; adb shell am start -n com.faka.invisible/.MainActivity

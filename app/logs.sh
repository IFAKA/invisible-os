#!/bin/sh
# Save the phone's app log to app/logs/last.log and print the button/Write lines. Usage: app/logs.sh [seconds=0 means dump what is buffered]
cd "$(dirname "$0")"; mkdir -p logs
adb logcat -d -v time -s Invisible:V AndroidRuntime:E > logs/last.log
echo "saved app/logs/last.log ($(wc -l < logs/last.log) lines)"; grep -E "RAW|ASSIST|GESTURE|WRITE|DEDUPE" logs/last.log | tail -${1:-60}

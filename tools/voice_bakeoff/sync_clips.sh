#!/bin/sh
# Push clips that are not on the phone yet. sync_clips.sh [watch]  (watch = repeat every 15 s while make_clips.py runs)
SRC=${CLIPS:-~/code/projects/hardware/.lanes/invisible-os/voice-clips/app/os/src/main/assets/voice}
PKG=com.faka.invisible
once() {
  adb shell "run-as $PKG sh -c 'mkdir -p files/voice; ls files/voice'" 2>/dev/null | tr -d '\r' | sort > /tmp/clips_on_phone.txt
  ls "$SRC" | sort | comm -23 - /tmp/clips_on_phone.txt > /tmp/clips_new.txt
  N=$(wc -l < /tmp/clips_new.txt | tr -d ' ')
  if [ "$N" -gt 0 ]; then
    T=$(mktemp -d); while read f; do cp "$SRC/$f" "$T/"; done < /tmp/clips_new.txt
    adb shell "rm -rf /data/local/tmp/vnew; mkdir -p /data/local/tmp/vnew" && adb push -q "$T/." /data/local/tmp/vnew/ >/dev/null \
      && adb shell "run-as $PKG sh -c 'cp /data/local/tmp/vnew/* files/voice/'" && rm -rf "$T"
  fi
  echo "$(date +%T) pushed $N, phone has $(( $(wc -l < /tmp/clips_on_phone.txt) + N ))"
}
if [ "$1" = watch ]; then while true; do once; sleep 15; done; else once; fi

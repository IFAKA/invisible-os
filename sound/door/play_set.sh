#!/bin/sh
# Compare old and new tones: sound/door/play_set.sh [name ...]  (no name = all). Plays old, pause, new.
cd "$(dirname "$0")"
names="${*:-$(ls set | sed 's/\.wav//')}"
for n in $names; do echo "$n"; [ -f ../wav/$n.wav ] && afplay ../wav/$n.wav && sleep .5; afplay set/$n.wav; sleep 1; done

#!/bin/sh
# Plays each option: enter, short pause, leave. Usage: sound/door/play.sh [N]
cd "$(dirname "$0")"
for n in ${1:-0 1 2 3 4 5 6}; do echo "option $n"; afplay ${n}_*_enter.wav; sleep .8; afplay ${n}_*_leave.wav; sleep 1.5; done

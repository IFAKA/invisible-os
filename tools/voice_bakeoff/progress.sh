#!/bin/sh
# One-line progress of the clip build: progress.sh [watch]
D=${CLIPS:-$(dirname "$0")/../../app/os/src/main/assets/voice}
T=$(python3 -c "import sys;sys.path.insert(0,'$(dirname "$0")');from manifest import all_items;print(len(all_items()))")
show() { N=$(ls $D | wc -l | tr -d ' '); P=$((N*100/T)); F=$((P*30/100)); printf "\r[%-30s] %s/%s clips (%s%%)  " "$(printf '#%.0s' $(seq 1 $F))" $N $T $P; }
if [ "$1" = watch ]; then while true; do show; sleep 5; done; else show; echo; fi

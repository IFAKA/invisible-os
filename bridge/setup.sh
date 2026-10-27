#!/bin/sh
# One command to add THIS Mac user to Invisible OS. Run it as the user to add:   sh bridge/setup.sh   [--name NAME] [--port PORT]
# It picks a name and a free port, adds the Claude Code hooks to ~/.claude/settings.json (a backup is kept),
# starts the bridge at login with launchd, waits until it answers, and prints a QR code and the pair code.
# Safe to run again. Env: INVISIBLE_PORT_FROM (first port to try, default 8765).
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLS="$DIR/setup_tools.py"
NAME=""; PORT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --name) NAME="${2:-}"; shift 2 ;;
    --port) PORT="${2:-}"; shift 2 ;;
    *) echo "Usage: sh setup.sh [--name NAME] [--port PORT]"; exit 2 ;;
  esac
done
say() { printf '%s\n' "$*"; }
command -v python3 >/dev/null 2>&1 || { say "python3 is missing. Install it (xcode-select --install) and run this again."; exit 1; }
PY="$(command -v python3)"

# the bridge files must be readable by this user
if ! "$PY" -c '
import os, sys
d = sys.argv[1]
ok = all(os.access(os.path.join(d, f), os.R_OK) for f in ("server.py", "hook.py", "core.py", "rooms.py"))
p = d
while p != "/":
    ok = ok and os.access(p, os.X_OK)
    p = os.path.dirname(p)
sys.exit(0 if ok else 1)' "$DIR"; then
  say "This user cannot read $DIR. Fix: sudo cp -R \"$DIR\" /Users/Shared/invisible-bridge && sh /Users/Shared/invisible-bridge/setup.sh"
  exit 1
fi

LABEL=com.invisible.bridge
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/invisible-bridge.log"
CFG="$HOME/.config/invisible-bridge"
UIDN="$(id -u)"

# (a) name and port: a second run keeps what the first run chose
[ -n "$PORT" ] || PORT="$("$PY" "$TOOLS" plist-get "$PLIST" --port)"
[ -n "$NAME" ] || NAME="$("$PY" "$TOOLS" plist-get "$PLIST" --name)"
START="${INVISIBLE_PORT_FROM:-8765}"
[ -n "$PORT" ] || PORT="$("$PY" "$TOOLS" freeport "$START")" || exit 1
if [ -z "$NAME" ]; then
  HOSTN="$(hostname -s 2>/dev/null || hostname)"
  if [ "$PORT" = "$START" ]; then NAME="$HOSTN"; else NAME="$HOSTN-$(id -un)"; fi   # the first bridge on this Mac is just the Mac's name
fi

# (b) Claude Code hooks for this user
HOOKS="$("$PY" "$TOOLS" hooks "python3 $DIR/hook.py")" || { say "Could not update ~/.claude/settings.json (left as it was)."; exit 1; }

# (c) launchd agent
mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs"
NEW="$PLIST.new"
"$PY" - "$NEW" "$PY" "$DIR/server.py" "$NAME" "$PORT" "$LOG" <<'PYEOF'
import plistlib, sys
out, py, server, name, port, log = sys.argv[1:7]
plistlib.dump({"Label": "com.invisible.bridge", "ProgramArguments": [py, server, "--name", name, "--port", port],
               "RunAtLoad": True, "KeepAlive": True, "StandardOutPath": log, "StandardErrorPath": log}, open(out, "wb"))
PYEOF
if [ -f "$PLIST" ] && cmp -s "$NEW" "$PLIST" && launchctl print "gui/$UIDN/$LABEL" >/dev/null 2>&1; then
  rm -f "$NEW"; LOADED="already running"
else
  mv "$NEW" "$PLIST"
  launchctl bootout "gui/$UIDN/$LABEL" >/dev/null 2>&1 || launchctl unload "$PLIST" >/dev/null 2>&1
  if launchctl bootstrap "gui/$UIDN" "$PLIST" >/dev/null 2>&1 || launchctl load -w "$PLIST" >/dev/null 2>&1; then LOADED="started"
  else say "launchd could not start the bridge. Try by hand: python3 $DIR/server.py --name $NAME --port $PORT"; exit 1; fi
fi

# (d) wait, then the pair code and the QR
URL="$("$PY" "$TOOLS" wait "$CFG" 20)" || { say "The bridge did not answer in 20 s. See $LOG"; exit 1; }
CODE="$("$PY" "$DIR/server.py" --name "$NAME" --port "$PORT" --show-token | tail -n 1)"
say "Bridge \"$NAME\" is $LOADED at $URL (starts at every login)."
case "$HOOKS" in added*) say "Claude Code hooks added to ~/.claude/settings.json (backup: settings.json.bak)." ;; *) say "Claude Code hooks were already in ~/.claude/settings.json." ;; esac
if "$PY" "$DIR/qr.py" "invisible-pair://${CODE#invisible-pair:}" 2>/dev/null; then QRSAY="QR code above."; else QRSAY="No QR code (text too long)."; fi
say "$CODE"
if command -v pbcopy >/dev/null 2>&1 && printf '%s' "$CODE" | pbcopy 2>/dev/null; then say "$QRSAY The pair code line is also copied to this Mac's clipboard."; else say "$QRSAY"; fi
say "On the phone: scan the QR with the camera (it opens Invisible OS), or Computers, Add a computer, Paste."

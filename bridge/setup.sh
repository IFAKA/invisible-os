#!/bin/sh
# One command to add THIS computer user to Invisible OS. Run it as the user to add:   sh bridge/setup.sh   [--name NAME] [--port PORT]
# It picks a name and a free port, adds the Claude Code hooks to ~/.claude/settings.json (a backup is kept),
# starts the bridge at login (launchd on a Mac, systemd on Linux), waits until it answers, and prints a QR code and the pair code.
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
if [ "$(uname -s)" = Linux ]; then   # Linux: a systemd user service; a Mac: a launchd agent
  PLIST="$HOME/.config/systemd/user/invisible-bridge.service"
  LOG="$HOME/.local/state/invisible-bridge.log"
else
  PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
  LOG="$HOME/Library/Logs/invisible-bridge.log"
fi
CFG="$HOME/.config/invisible-bridge"
UIDN="$(id -u)"

# (a) name and port: a second run keeps what the first run chose
[ -n "$PORT" ] || PORT="$("$PY" "$TOOLS" plist-get "$PLIST" --port)"
[ -n "$NAME" ] || NAME="$("$PY" "$TOOLS" plist-get "$PLIST" --name)"
START="${INVISIBLE_PORT_FROM:-8765}"
[ -n "$PORT" ] || PORT="$("$PY" "$TOOLS" freeport "$START")" || exit 1
if [ -z "$NAME" ]; then
  # the person's first name ("Ana"): short to hear on the earbud, and it tells two users of one Mac apart
  NAME="$(id -F 2>/dev/null | awk '{print $1}')"; [ -n "$NAME" ] || NAME="$(id -un)"
fi

# (b) Claude Code hooks for this user
HOOKS="$("$PY" "$TOOLS" hooks "python3 $DIR/hook.py")" || { say "Could not update ~/.claude/settings.json (left as it was)."; exit 1; }

# (c) the service that starts the bridge at login (systemd on Linux, launchd on a Mac)
if [ "$(uname -s)" = Linux ]; then
  command -v tmux >/dev/null 2>&1 || say "Note: tmux is not installed, so there will be no rooms (install it: sudo apt install tmux)."
  command -v systemctl >/dev/null 2>&1 || { say "systemd is missing. Start the bridge by hand: python3 $DIR/server.py --name $NAME --port $PORT"; exit 1; }
  mkdir -p "$HOME/.config/systemd/user" "$HOME/.local/state"
  NEW="$PLIST.new"
  printf '[Unit]\nDescription=Invisible OS bridge\n\n[Service]\nExecStart=%s %s --name %s --port %s\nRestart=always\nStandardOutput=append:%s\nStandardError=append:%s\n\n[Install]\nWantedBy=default.target\n' "$PY" "$DIR/server.py" "$NAME" "$PORT" "$LOG" "$LOG" > "$NEW"
  if [ -f "$PLIST" ] && cmp -s "$NEW" "$PLIST" && systemctl --user is-active invisible-bridge.service >/dev/null 2>&1; then
    rm -f "$NEW"; LOADED="already running"
  else
    mv "$NEW" "$PLIST"
    systemctl --user daemon-reload
    if systemctl --user enable invisible-bridge.service >/dev/null 2>&1 && systemctl --user restart invisible-bridge.service >/dev/null 2>&1; then LOADED="started"
    else say "systemd could not start the bridge. Try by hand: python3 $DIR/server.py --name $NAME --port $PORT"; exit 1; fi
    say "To keep it running when nobody is logged in: loginctl enable-linger $(id -un)"
  fi
else
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
fi

# (c2) the `invisible` command, and the list of what was added (for `invisible uninstall`)
LINK=""
[ -f "$DIR/../bin/invisible" ] && LINK="$("$PY" "$TOOLS" link "$DIR/../bin/invisible")"
"$PY" "$TOOLS" manifest "$PLIST" "$LOG" "python3 $DIR/hook.py" "$LINK"

# (d) wait, then the pair code and the QR
URL="$("$PY" "$TOOLS" wait "$CFG" 20)" || { say "The bridge did not answer in 20 s. See $LOG"; exit 1; }
CODE="$("$PY" "$DIR/server.py" --name "$NAME" --port "$PORT" --show-token | tail -n 1)"
say "Bridge \"$NAME\" is $LOADED at $URL (starts at every login)."
case "$HOOKS" in added*) say "Claude Code hooks added to ~/.claude/settings.json (backup: settings.json.bak)." ;; *) say "Claude Code hooks were already in ~/.claude/settings.json." ;; esac
[ -n "$LINK" ] && say "Command \`invisible\` ready: invisible status, invisible uninstall."
for CLIP in pbcopy wl-copy "xclip -selection clipboard"; do
  if command -v "${CLIP%% *}" >/dev/null 2>&1 && printf '%s' "$CODE" | $CLIP 2>/dev/null; then say "No camera? The code is copied: paste it in the app, Computers, Add."; break; fi
done
say ""
# the QR is last, so the one thing to do is what the eye lands on. A web link: a phone without the app gets "install, then connect"
"$PY" "$DIR/qr.py" "https://invisible-os.vercel.app/pair#${CODE#invisible-pair:}" 2>/dev/null || say "$CODE"
say "Scan this with your phone camera."

#!/bin/sh
# Prints what to add to Claude Code settings and how to start the bridge. It changes NOTHING on your machine.
DIR="$(cd "$(dirname "$0")" && pwd)"
HOOK="python3 $DIR/hook.py"
cat <<TXT
== 1. Add this to the "hooks" part of ~/.claude/settings.json (merge it with what you have) ==
{
  "hooks": {
    "PermissionRequest": [
      { "hooks": [ { "type": "command", "command": "$HOOK", "timeout": 660 } ] }
    ],
    "PreToolUse": [
      { "matcher": "AskUserQuestion", "hooks": [ { "type": "command", "command": "$HOOK", "timeout": 660 } ] }
    ],
    "Notification": [
      { "matcher": "idle_prompt|elicitation_dialog", "hooks": [ { "type": "command", "command": "$HOOK", "timeout": 10 } ] }
    ],
    "Stop": [
      { "hooks": [ { "type": "command", "command": "$HOOK", "timeout": 10 } ] }
    ]
  }
}
The timeout (660 s) must be longer than INVISIBLE_BRIDGE_TIMEOUT (default 600 s).
If the bridge is not on this laptop's 127.0.0.1:8765, set INVISIBLE_BRIDGE_URL in your shell, for example:
  export INVISIBLE_BRIDGE_URL=http://100.x.y.z:8765

== 2. Start the server (keep it running) ==
  python3 $DIR/server.py            # listens on your Tailscale or Wi-Fi address, port 8765
  python3 $DIR/server.py --show-token   # print the token again
Copy the "invisible-pair:" line it prints and paste it in the phone app: home screen, "Computers", "Add a computer".
More than one computer (or a second user on the same Mac): each one runs its own bridge, and the second needs another port:
  python3 $DIR/server.py --port 8766        # or: INVISIBLE_BRIDGE_PORT=8766 python3 $DIR/server.py
Add each one the same way (start it, copy the pair code line, paste it on the phone). The phone listens to one computer at a time:
switch with "Switch to Work" in the Laptop tool, or by tapping it in the app under "Computers".

== 3. Optional: start it at login with launchd (this only prints the file; save it yourself) ==
Save as ~/Library/LaunchAgents/com.invisible.bridge.plist, then run:
  launchctl load ~/Library/LaunchAgents/com.invisible.bridge.plist
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>com.invisible.bridge</string>
  <key>ProgramArguments</key><array><string>/usr/bin/python3</string><string>$DIR/server.py</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>StandardErrorPath</key><string>/tmp/invisible-bridge.log</string>
</dict></plist>
TXT

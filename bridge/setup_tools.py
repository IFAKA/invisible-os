#!/usr/bin/env python3
"""Small helpers for setup.sh (so the shell script stays short). Python 3 stdlib only.
  freeport START        print the first free TCP port from START (a bind test)
  hooks HOOK_CMD        add the Claude Code hooks to ~/.claude/settings.json (only the missing ones); prints added / already
  plist-get PLIST KEY   print --name or --port from an existing launchd plist (nothing if absent)
  wait CONFIG_DIR SEC   wait until the server in CONFIG_DIR answers /ping; prints its URL (exit 1 on timeout)
"""
import json
import os
import plistlib
import shutil
import socket
import sys
import time
import urllib.request


def free_port(start, tries=200):
    for p in range(start, start + tries):
        s = socket.socket()
        try:
            s.bind(("0.0.0.0", p))
            return p
        except OSError:
            continue
        finally:
            s.close()
    raise SystemExit("no free port from %d" % start)


def wanted_hooks(cmd):
    run = lambda t: {"type": "command", "command": cmd, "timeout": t}
    return {
        "PermissionRequest": {"hooks": [run(660)]},
        "PreToolUse": {"matcher": "AskUserQuestion", "hooks": [run(660)]},
        "Notification": {"matcher": "idle_prompt|elicitation_dialog", "hooks": [run(10)]},
        "Stop": {"hooks": [run(10)]},
    }


def merge_hooks(settings_path, cmd):
    """Add the missing bridge hook entries; keep everything else. Backs up to settings.json.bak first. Returns the events added."""
    data = {}
    existed = os.path.exists(settings_path)
    if existed:
        with open(settings_path) as f:
            text = f.read()
        data = json.loads(text) if text.strip() else {}
        if not isinstance(data, dict):
            raise SystemExit("settings.json is not a JSON object; not touched")
    hooks = data.setdefault("hooks", {})
    if not isinstance(hooks, dict):
        raise SystemExit("settings.json: hooks is not an object; not touched")
    added = []
    for event, entry in wanted_hooks(cmd).items():
        lst = hooks.setdefault(event, [])
        mine = any(h.get("command") == cmd for e in lst if isinstance(e, dict) for h in e.get("hooks", []) if isinstance(h, dict))
        if not mine:
            lst.append(entry)
            added.append(event)
    if added:
        os.makedirs(os.path.dirname(settings_path), exist_ok=True)
        if existed:
            shutil.copyfile(settings_path, settings_path + ".bak")
        tmp = settings_path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")
        json.load(open(tmp))   # valid JSON or we stop before replacing
        os.replace(tmp, settings_path)
        json.load(open(settings_path))
    return added


def plist_get(path, key):
    try:
        args = plistlib.load(open(path, "rb")).get("ProgramArguments", [])
    except (OSError, ValueError):
        return ""
    return args[args.index(key) + 1] if key in args and args.index(key) + 1 < len(args) else ""


def wait_server(config_dir, seconds):
    end = time.time() + seconds
    while time.time() < end:
        try:
            url = open(os.path.join(config_dir, "url")).read().strip()
            tok = open(os.path.join(config_dir, "token")).read().strip()
            req = urllib.request.Request(url + "/ping", headers={"Authorization": "Bearer " + tok})
            urllib.request.urlopen(req, timeout=2).read()
            return url
        except (OSError, ValueError):
            time.sleep(0.3)
    return None


def main(argv):
    cmd = argv[1] if len(argv) > 1 else ""
    if cmd == "freeport":
        print(free_port(int(argv[2])))
    elif cmd == "hooks":
        added = merge_hooks(os.path.expanduser("~/.claude/settings.json"), argv[2])
        print("added " + ",".join(added) if added else "already")
    elif cmd == "plist-get":
        print(plist_get(argv[2], argv[3]))
    elif cmd == "wait":
        url = wait_server(argv[2], float(argv[3]))
        if not url:
            return 1
        print(url)
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

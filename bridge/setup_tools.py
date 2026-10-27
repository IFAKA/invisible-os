#!/usr/bin/env python3
"""Small helpers for setup.sh (so the shell script stays short). Python 3 stdlib only.
  freeport START        print the first free TCP port from START (a bind test)
  hooks HOOK_CMD        add the Claude Code hooks to ~/.claude/settings.json (only the missing ones); prints added / already
  plist-get PLIST KEY   print --name or --port from an existing launchd plist (nothing if absent)
  manifest PLIST LOG HOOK_CMD [LINK]  write ~/.config/invisible-bridge/installed.json (what `invisible uninstall` removes)
  link BIN_SCRIPT       link `invisible` into /opt/homebrew/bin or ~/.local/bin; prints the link path (and a PATH hint on stderr)
  wait CONFIG_DIR SEC   wait until the server in CONFIG_DIR answers /ping; prints its URL (exit 1 on timeout)
"""
import json
import os
import plistlib
import shlex
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


def remove_hooks(settings_path, cmd):
    """Remove only the hook entries whose command is [cmd]. Backs up to settings.json.bak first. Returns the events changed."""
    if not os.path.exists(settings_path):
        return []
    with open(settings_path) as f:
        data = json.loads(f.read() or "{}")
    hooks = data.get("hooks") if isinstance(data, dict) else None
    if not isinstance(hooks, dict):
        return []
    changed = []
    for event in list(hooks):
        lst = hooks[event]
        if not isinstance(lst, list):
            continue
        keep = []
        for e in lst:
            if isinstance(e, dict) and isinstance(e.get("hooks"), list):
                inner = [h for h in e["hooks"] if not (isinstance(h, dict) and h.get("command") == cmd)]
                if len(inner) != len(e["hooks"]):
                    changed.append(event)
                    if not inner:
                        continue
                    e = dict(e, hooks=inner)
            keep.append(e)
        if keep:
            hooks[event] = keep
        elif event in changed:
            del hooks[event]
    if changed:
        if not hooks:
            del data["hooks"]
        shutil.copyfile(settings_path, settings_path + ".bak")
        tmp = settings_path + ".tmp"
        with open(tmp, "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")
        json.load(open(tmp))
        os.replace(tmp, settings_path)
    return changed


def write_manifest(config_dir, plist, log, hook_cmd, link=""):
    m = {"launchd_label": "com.invisible.bridge", "plist": plist, "log": log, "config_dir": config_dir, "link": link,
         "hooks": {"settings": os.path.expanduser("~/.claude/settings.json"), "command": hook_cmd}}
    os.makedirs(config_dir, exist_ok=True)
    with open(os.path.join(config_dir, "installed.json"), "w") as f:
        json.dump(m, f, indent=2)
        f.write("\n")
    return m


def link_cli(script):
    """Put the `invisible` command on PATH. Returns (path, on_path).
    It is a copy of bin/invisible-shim, not a link: /opt/homebrew/bin is shared by every user of this Mac,
    so the command must run each user's own copy (written to ~/.config/invisible-bridge/checkout)."""
    script = os.path.realpath(script)
    shim = os.path.join(os.path.dirname(script), "invisible-shim")
    cfg = os.path.expanduser("~/.config/invisible-bridge")
    os.makedirs(cfg, exist_ok=True)
    with open(os.path.join(cfg, "checkout"), "w") as f:
        f.write(os.path.dirname(os.path.dirname(script)) + "\n")
    forced = os.environ.get("INVISIBLE_BIN_DIR")   # tests and people who want one place
    for d in ([forced] if forced else ["/opt/homebrew/bin", os.path.expanduser("~/.local/bin")]):
        if d.startswith("/opt") and not os.access(d, os.W_OK):
            continue
        try:
            os.makedirs(d, exist_ok=True)
            dest = os.path.join(d, "invisible")
            if os.path.lexists(dest) and not os.path.islink(dest) and not is_shim(dest):
                continue   # a real file that is not ours: leave it
            tmp = dest + ".tmp%d" % os.getpid()
            shutil.copyfile(shim, tmp)
            os.chmod(tmp, 0o755)
            os.replace(tmp, dest)
            return dest, d in os.environ.get("PATH", "").split(os.pathsep)
        except OSError:
            continue
    return "", False


def is_shim(path):
    try:
        return "invisible-shim" in open(path, errors="replace").read(200)
    except OSError:
        return False


def plist_get(path, key):
    if path.endswith(".service"):   # Linux: the systemd user unit; the arguments are on its ExecStart line
        try:
            line = next(l for l in open(path).read().splitlines() if l.startswith("ExecStart="))
        except (OSError, StopIteration):
            return ""
        args = shlex.split(line[len("ExecStart="):])
        return args[args.index(key) + 1] if key in args and args.index(key) + 1 < len(args) else ""
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
    elif cmd == "manifest":
        write_manifest(os.path.expanduser("~/.config/invisible-bridge"), argv[2], argv[3], argv[4], argv[5] if len(argv) > 5 else "")
    elif cmd == "link":
        dest, on_path = link_cli(argv[2])
        if dest and not on_path:
            print("Add this to your shell profile so `invisible` is found:  export PATH=\"%s:$PATH\"" % os.path.dirname(dest), file=sys.stderr)
        print(dest)
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

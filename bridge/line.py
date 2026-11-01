#!/usr/bin/env python3
"""Ask or tell the owner by ear from any laptop script (Invisible OS bridge).
  python3 bridge/line.py "Build failed on main" --who build --option Rerun --option Ignore --wait 60
Prints the chosen option on one line (empty line = no answer in time). No options = a notice. Reads ~/.config/invisible-bridge/{url,token}."""
import argparse
import json
import os
import sys
import urllib.parse
import urllib.request

CONFIG_DIR = os.path.expanduser("~/.config/invisible-bridge")


def _read(name, config_dir):
    with open(os.path.join(config_dir, name)) as f:
        return f.read().strip()


def _call(base, token, path, body=None, timeout=10):
    req = urllib.request.Request(base + path, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json", "X-Invisible-Client": "cli"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read() or b"{}")


def ask(text, who="", options=(), wait=0, config_dir=CONFIG_DIR, url=None, token=None):
    """Post the line; with wait > 0 and options, wait for the answer. Returns the choice or None."""
    base = (url or _read("url", config_dir)).rstrip("/")
    token = token or _read("token", config_dir)
    got = _call(base, token, "/line", {"who": who, "text": text, "options": list(options)[:3]})
    if not options or wait <= 0:
        return None
    return _call(base, token, "/line/answer?id=%s&wait=%s" % (urllib.parse.quote(got["id"]), wait), timeout=wait + 10).get("choice")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("text")
    ap.add_argument("--who", default="")
    ap.add_argument("--option", action="append", default=[], help="an answer the owner can click (max 3)")
    ap.add_argument("--wait", type=float, default=0, help="seconds to wait for the answer")
    a = ap.parse_args(argv)
    try:
        choice = ask(a.text, a.who, a.option, a.wait)
    except (OSError, ValueError) as e:
        print("line.py: %s" % e, file=sys.stderr)
        return 1
    print(choice or "")
    return 0


if __name__ == "__main__":
    sys.exit(main())

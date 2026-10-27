#!/usr/bin/env python3
"""Claude Code hook for the Invisible OS bridge. Reads the hook JSON on stdin, talks to server.py.
Any problem (bridge down, no answer in time) prints nothing and exits 0: Claude Code then asks in the terminal as usual.
Env: INVISIBLE_BRIDGE_URL (default: the address server.py wrote to ~/.config/invisible-bridge/url, else http://127.0.0.1:8765), INVISIBLE_BRIDGE_TIMEOUT seconds (default 600).
"""
import json
import os
import signal
import sys
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import core  # noqa: E402

TOKEN_FILE = os.path.expanduser("~/.config/invisible-bridge/token")
URL_FILE = os.path.expanduser("~/.config/invisible-bridge/url")


def base_url():
    url = os.environ.get("INVISIBLE_BRIDGE_URL")
    if not url:
        try:
            url = open(URL_FILE).read().strip()
        except OSError:
            url = ""
    return (url or "http://127.0.0.1:8765").rstrip("/")


def token():
    try:
        return open(os.environ.get("INVISIBLE_BRIDGE_TOKEN_FILE", TOKEN_FILE)).read().strip()
    except OSError:
        return ""


def timeout_s():
    try:
        return float(os.environ.get("INVISIBLE_BRIDGE_TIMEOUT", "600"))
    except ValueError:
        return 600.0


def post(path, body, timeout):
    req = urllib.request.Request(base_url() + path, data=json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + token(), "Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def ask(kind, project, summary, options):
    """Send to the phone and block. Returns (item id, choice or None)."""
    wait = timeout_s()
    ref = uuid.uuid4().hex
    def bye(*_):   # Claude Code stopped this hook (answered in the terminal): take the item off the phone
        try:
            post("/cancel", {"ref": ref}, 3)
        except (urllib.error.URLError, OSError, ValueError):
            pass
        sys.exit(0)
    signal.signal(signal.SIGTERM, bye)
    try:
        r = post("/ask", {"kind": kind, "project": project, "summary": summary, "options": options, "wait": wait, "ref": ref}, wait + 10)
        return r.get("id"), r.get("answer")
    except (urllib.error.URLError, OSError, ValueError):
        return None, None


def notify(kind, project, summary):
    try:
        post("/notify", {"kind": kind, "project": project, "summary": summary}, 5)
    except (urllib.error.URLError, OSError, ValueError):
        pass


def handle(inp):
    """Returns the dict to print as hook output, or None for no output."""
    event = inp.get("hook_event_name")
    project = core.project_name(inp.get("cwd"))
    tool = inp.get("tool_name", "")
    if event == "PermissionRequest":
        if tool == "AskUserQuestion":
            return None   # the PreToolUse hook answers questions
        _, choice = ask("permission", project, core.summarize(tool, inp.get("tool_input")), core.PERMISSION_OPTIONS)
        return core.permission_output(choice, inp) if choice else None
    if event == "PreToolUse" and tool == "AskUserQuestion":
        tin = inp.get("tool_input") or {}
        answers = {}
        for text, labels in core.question_items(tin):
            if not labels:
                return None
            _, choice = ask("question", project, core.shorten(text, 40), labels + [core.ON_LAPTOP])
            if not choice or choice == core.ON_LAPTOP:
                return None
            answers[text] = choice
        return core.question_output(tin, answers) if answers else None
    if event == "Notification":
        if inp.get("notification_type") != "permission_prompt":   # permission prompts already reach the phone as questions
            notify("needs", project, inp.get("message", "needs you"))
        return None
    if event == "Stop":
        notify("done", project, " ".join(str(inp.get("last_assistant_message", "")).split())[:200] or "finished")
        return None
    return None


def main():
    try:
        inp = json.load(sys.stdin)
    except ValueError:
        return
    out = handle(inp)
    if out:
        print(json.dumps(out))


if __name__ == "__main__":
    main()

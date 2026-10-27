"""Pure parts of the bridge (no network, no files): summaries, hook answers, the item store. Python 3 stdlib only."""
import json
import os
import threading
import time

ALLOW, DENY, ALWAYS = "Allow", "Deny", "Always allow"
PERMISSION_OPTIONS = [ALLOW, DENY, ALWAYS]
ON_LAPTOP = "Ask on the laptop"   # an extra choice for questions: the terminal asks as usual


def shorten(text, words=12):
    """First `words` words of text, white space squashed. Adds '...' when cut."""
    parts = str(text or "").split()
    if len(parts) <= words:
        return " ".join(parts)
    return " ".join(parts[:words]) + "..."


def project_name(cwd):
    return os.path.basename((cwd or "").rstrip("/")) or "Claude"


def summarize(tool, tool_input):
    """A short plain line for a tool call, at most about 12 words."""
    ti = tool_input or {}
    if tool == "Bash":
        if ti.get("description"):
            return shorten(ti["description"], 12)
        return shorten("run " + str(ti.get("command", "")), 12)
    if tool in ("Edit", "MultiEdit", "Write", "NotebookEdit"):
        verb = "write" if tool == "Write" else "edit"
        path = ti.get("file_path") or ti.get("notebook_path") or ""
        return "%s %s" % (verb, os.path.basename(path) or "a file")
    if tool == "Read":
        return "read %s" % (os.path.basename(ti.get("file_path", "")) or "a file")
    if tool in ("WebFetch", "WebSearch"):
        return shorten("%s %s" % ("fetch" if tool == "WebFetch" else "search", ti.get("url") or ti.get("query") or ""), 12)
    if tool.startswith("mcp__"):
        return shorten("use " + tool.split("__")[-1].replace("_", " "), 12)
    return shorten("use " + tool, 12)


def detail(tool, tool_input, limit=200):
    """The exact thing a permission ask is about, as plain text, cut at `limit` characters: the command (Bash), or the tool word and
    the file / address / query. Never reworded."""
    ti = tool_input or {}
    if tool == "Bash":
        text = ti.get("command", "")
    else:
        verb = {"Write": "write", "Read": "read", "WebFetch": "fetch", "WebSearch": "search"}.get(tool, "edit" if tool in ("Edit", "MultiEdit", "NotebookEdit") else tool)
        arg = ti.get("file_path") or ti.get("notebook_path") or ti.get("url") or ti.get("query") or (json.dumps(ti, ensure_ascii=False, sort_keys=True) if ti else "")
        text = "%s %s" % (verb, arg)
    return " ".join(str(text).split())[:limit]


def permission_output(choice, hook_input):
    """Hook stdout for a PermissionRequest answer. None = no decision (the terminal asks as usual)."""
    if choice == ALLOW:
        return {"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": {"behavior": "allow"}}}
    if choice == ALWAYS:
        suggestions = hook_input.get("permission_suggestions")
        if not suggestions:
            tool = hook_input.get("tool_name", "")
            rule = {"toolName": tool}
            cmd = (hook_input.get("tool_input") or {}).get("command")
            if tool == "Bash" and cmd:
                rule["ruleContent"] = cmd
            suggestions = [{"type": "addRules", "rules": [rule], "behavior": "allow", "destination": "localSettings"}]
        return {"hookSpecificOutput": {"hookEventName": "PermissionRequest",
                                       "decision": {"behavior": "allow", "updatedPermissions": suggestions}}}
    if choice == DENY:
        return {"hookSpecificOutput": {"hookEventName": "PermissionRequest",
                                       "decision": {"behavior": "deny", "message": "Denied from the earbud"}}}
    return None


def question_output(tool_input, answers):
    """Hook stdout for AskUserQuestion (PreToolUse): allow, with the answers filled in. answers: {question: label}."""
    updated = dict(tool_input)
    updated["answers"] = dict(answers)
    return {"hookSpecificOutput": {"hookEventName": "PreToolUse", "permissionDecision": "allow", "updatedInput": updated}}


def question_items(tool_input):
    """[(question text, [labels])] from an AskUserQuestion tool_input."""
    out = []
    for q in (tool_input or {}).get("questions", []):
        labels = [o.get("label", "") for o in q.get("options", []) if o.get("label")]
        out.append((q.get("question", ""), labels))
    return out


class Store:
    """Items for the phone. kind: permission | question (need an answer), done | needs (no answer)."""
    KEEP_SECONDS = 600

    def __init__(self, clock=time.time):
        self.clock = clock
        self.cv = threading.Condition()
        self.seq = 0
        self.items = {}   # id -> dict
        self.last_poll = None   # when the phone last asked for events (clock time)

    PHONE_ONLINE_SECONDS = 40   # a phone long-polls every ~25 s; quieter than this = not listening

    def phone_online(self):
        return self.last_poll is not None and self.clock() - self.last_poll <= self.PHONE_ONLINE_SECONDS

    def _add(self, kind, project, summary, options, ref=None, pane=None, room=None, detail=""):
        self.seq += 1
        item = {"id": str(self.seq), "seq": self.seq, "kind": kind, "project": project,
                "summary": summary, "options": list(options), "ref": ref, "pane": pane, "room": room, "detail": detail,
                "answer": None, "cancelled": False, "at": self.clock()}
        self.items[item["id"]] = item
        self._trim()
        self.cv.notify_all()
        return item

    def _trim(self):
        cutoff = self.clock() - self.KEEP_SECONDS
        for k in [k for k, v in self.items.items() if v["at"] < cutoff and (v["answer"] is not None or v["cancelled"] or not v["options"])]:
            del self.items[k]

    def add(self, kind, project, summary, options=(), ref=None, pane=None, detail=""):
        with self.cv:
            return self._add(kind, project, summary, options, ref, pane, detail=detail)

    def add_room_event(self, room):
        """A room turned 'needs': an /events item {"type":"room","id":<pane id>,"name":...,"state":...}."""
        ev = {"type": "room", "kind": "room", "id": room["id"], "name": room["name"], "state": room["state"],
              "project": room["project"], "summary": room.get("need", ""), "options": []}
        with self.cv:
            return self._add("room", room["project"], ev["summary"], (), room=ev)

    def pending_panes(self):
        """{tmux pane id: summary} of open permission/question asks that came from a tmux pane."""
        with self.cv:
            return {v["pane"]: v["summary"] for v in self.items.values()
                    if v["pane"] and v["options"] and v["answer"] is None and not v["cancelled"]}

    def pending_asks(self):
        """{tmux pane id: {"id", "kind": "permission", "what", "options"}} of open permission asks that came from a tmux pane.
        `what` is the exact command or file (the hook's detail), else the short summary."""
        with self.cv:
            out = {}
            for v in sorted(self.items.values(), key=lambda v: v["seq"]):
                if v["pane"] and v["kind"] == "permission" and v["options"] and v["answer"] is None and not v["cancelled"]:
                    out[v["pane"]] = {"id": v["id"], "kind": "permission", "what": (v.get("detail") or v["summary"])[:200],
                                      "options": list(v["options"])}
            return out

    def answer(self, item_id, choice):
        """choice: option text or index. Returns True if accepted."""
        with self.cv:
            item = self.items.get(str(item_id))
            if not item or item["answer"] is not None or item["cancelled"] or not item["options"]:
                return False
            if isinstance(choice, int) and not isinstance(choice, bool) and 0 <= choice < len(item["options"]):
                choice = item["options"][choice]
            if choice not in item["options"]:
                return False
            item["answer"] = choice
            self.cv.notify_all()
            return True

    def cancel(self, item_id):
        with self.cv:
            for item in self.items.values():
                if item["id"] == str(item_id) or (item["ref"] and item["ref"] == item_id):
                    item["cancelled"] = True
            self.cv.notify_all()

    def wait_answer(self, item_id, timeout):
        """Block until answered. Returns the choice, or None on timeout/cancel."""
        end = time.monotonic() + timeout
        with self.cv:
            while True:
                item = self.items.get(str(item_id))
                if item is None or item["cancelled"]:
                    return None
                if item["answer"] is not None:
                    return item["answer"]
                left = end - time.monotonic()
                if left <= 0:
                    item["cancelled"] = True
                    return None
                self.cv.wait(left)

    def _visible(self, since):
        now = self.clock()
        out = []
        for v in sorted(self.items.values(), key=lambda v: v["seq"]):
            if v["seq"] <= since or v["cancelled"]:
                continue
            if v["options"] and v["answer"] is not None:
                continue
            if not v["options"] and now - v["at"] > 300:
                continue
            out.append(v)
        return out

    def events(self, since, wait):
        """Items newer than `since` that still need the phone. Waits up to `wait` seconds when there are none.
        A phone that starts at since=0 also gets the asks that are still open."""
        end = time.monotonic() + wait
        self.last_poll = self.clock()
        with self.cv:
            while True:
                vis = self._visible(since)
                if vis or wait <= 0:
                    return {"seq": max([self.seq] + [v["seq"] for v in vis]) if vis else max(since, 0), "items": [public(v) for v in vis]}
                left = end - time.monotonic()
                if left <= 0:
                    return {"seq": since, "items": []}
                self.cv.wait(left)


def public(v):
    if v.get("room"):
        return dict(v["room"])
    return {k: v[k] for k in ("id", "kind", "project", "summary", "options")}

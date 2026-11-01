"""The push layer (DESIGN-LAYER.md): for every item that needs the owner, one spoken `line` and two answers `a` and `b`.
Pure functions, no network. ANSWER = {"label", "risky", and "act": {"id","action"} (phone POSTs /room/act) or "answer": {"id","choice"} (POSTs /answer)}."""
import re



def shorten(text, words=12):   # same as core.shorten (core imports this module, so no import back)
    parts = str(text or "").split()
    return " ".join(parts) if len(parts) <= words else " ".join(parts[:words]) + "..."

# Asks whose `what` deletes or publishes. One list, here.
RISKY_RE = re.compile(
    r"\brm\s|\brmdir\b|\bgit\s+push\b|\bgit\s+reset\s+--hard\b|\bgit\s+clean\b|\bforce\b|--force|\bdrop\b|\btruncate\b|"
    r"\bdeploy\b|\bpublish\b|\bnpm\s+publish\b|\bkubectl\s+delete\b|\bsudo\b", re.I)
RISKY_ACTIONS = {"push", "clear"}   # room actions that are risky (push publishes, clear forgets the chat)
ALLOW_RE = re.compile(r"^(allow|yes|approve|accept|ok|okay|run)\b", re.I)
DENY_RE = re.compile(r"^(deny|no|reject|block|refuse|cancel)\b", re.I)
LABELS = {"go": "Go", "yes": "Yes", "commit": "Commit", "push": "Push", "compact": "Compact", "explain": "Explain simpler",
          "recommend": "Recommend", "research": "Research first", "progress": "Progress", "hold": "Hold on", "stop": "Stop",
          "clear": "Clear", "again": "Again"}
MAX_GO_WORDS = 5


def is_risky(text):
    return bool(RISKY_RE.search(str(text or "")))


def _act(room_id, action, label, risky):
    return {"label": label, "risky": bool(risky), "act": {"id": room_id, "action": action}}


def _choice(item_id, choice, label=None, risky=False):
    return {"label": shorten(label or choice, 4).rstrip("."), "risky": bool(risky), "answer": {"id": str(item_id), "choice": choice}}


def _typed(action, ACTION_TEXT):
    """The text an action types, lower case (to skip an action that says the same as an earlier one), or None if it is not an answer."""
    if action.startswith("option:"):
        return action[7:].strip().lower()
    if action == "stop":
        return "\x1bstop"
    return ACTION_TEXT.get(action, None)


def room_answers(room, action_text):
    """(a, b) for a room that needs the owner. action_text = rooms.ACTION_TEXT. Either may be None."""
    ask = room.get("ask")
    if ask:
        return ask_answers(ask["id"], ask["options"], ask.get("what", ""))
    rid, go = room["id"], room.get("go") or ""
    go = go if go and len(go.split()) <= MAX_GO_WORDS else ""
    if room.get("kind") == "shell":
        return _act(rid, "again", "Again", False), _act(rid, "stop", "Stop", False)
    out, seen = [], set()
    for action in room.get("actions", []):
        key = _typed(action, action_text)
        if key is None or key.lower() in seen:
            continue
        seen.add(key.lower())
        if action.startswith("option:"):
            label = shorten(action[7:], 4).rstrip(".")
        else:
            label = LABELS.get(action, action.capitalize())
            if action in ("go", "yes") and go:
                label = "%s: %s" % (label, go)
        out.append(_act(rid, action, label, action in RISKY_ACTIONS))
        if len(out) == 2:
            break
    out += [None] * (2 - len(out))
    return out[0], out[1]


def ask_answers(ask_id, options, what):
    """(allow, deny) answers of a permission ask, picked by the meaning of the labels. 'Always allow' is never offered."""
    options = list(options or [])
    allow = next((o for o in options if ALLOW_RE.match(o) and "always" not in o.lower()), None)
    deny = next((o for o in options if DENY_RE.match(o)), None)
    a = _choice(ask_id, allow, risky=is_risky(what)) if allow else None
    b = _choice(ask_id, deny) if deny else None
    return a, b


def option_answers(item_id, options):
    """(a, b) for a generic item: the first two options, in order."""
    opts = [o for o in (options or []) if o][:2]
    out = [_choice(item_id, o) for o in opts] + [None, None]
    return out[0], out[1]


def room_line(room):
    ask = room.get("ask")
    if ask:
        return ask_line(room["project"], ask.get("what", ""))
    text = room.get("story") or room.get("need") or room.get("name") or ""
    return shorten("%s: %s" % (room["project"], text), 12)


def ask_line(project, what):
    what = " ".join(str(what or "").split())
    verb = "wants to " + ("" if re.match(r"^(edit|write|read|fetch|search)\b", what) else "run ")
    return shorten("%s %s%s" % (project, verb, what), 12)

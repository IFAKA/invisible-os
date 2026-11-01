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


NEED_WORDS = 14      # the agent's own need line, a model line or a story up to this long is spoken as it is
FIT_WORDS = 16       # the code fallback keeps the line within this (the project name is not counted)
CMD_WORDS = 6        # an ask command longer than this is spoken as program, first argument, "and N more words"
_MD_RE = re.compile(r"```[^\n]*\n?|[`*]+|^\s*[#>]+\s*|^\s*[-+]\s+|^\s*\d+[.)]\s+", re.M)
_SENT_RE = re.compile(r"(?<=[.!?])\s+|\n+")
_CLAUSE_RE = re.compile(r"(?<=[,;:])\s+|\s+[-\u2013\u2014]\s+")
_QUOTED_RE = re.compile(r"\"[^\"\n]+\"|\u201c[^\u201d\n]+\u201d")
_ASK_VERB_RE = re.compile(r"^(say|run|commit|approve|reply|tell|confirm|push|merge|want me|should i|shall i|do you|can i|let me know|choose|pick|click|answer)\b", re.I)


def _clean(text):
    return " ".join(_MD_RE.sub("", str(text or "")).split())


def _n(text):
    return len(text.split())


def _asks(sentence):
    s = sentence.strip()
    return s.endswith("?") or bool(_QUOTED_RE.search(s)) or bool(_ASK_VERB_RE.match(s))


def _units(sentence, limit):
    """The sentence whole if it fits `limit` words, else its clauses cut at commas, colons, semicolons or dashes."""
    return [sentence] if _n(sentence) <= limit else [c for c in _CLAUSE_RE.split(sentence) if c.strip()]


def fit_line(project, need="", model_line="", story="", text=""):
    """The spoken push line (heard once, no screen): what happened and what is wanted. Source order: (a) the agent's own need line
    (<= NEED_WORDS), (b) the model line, (c) the story (<= NEED_WORDS), (d) code: split into sentences, always keep the one that
    asks, add earlier sentences from the start while <= FIT_WORDS; a long asking sentence keeps its last clause. Never "...".
    The project name is a prefix outside the word budget."""
    def pick(t):
        t = _clean(t)
        return t if t and _n(t) <= NEED_WORDS and not t.endswith("...") else ""
    body = pick(need) or pick(model_line) or pick(story)
    if not body:
        body = _fit_text(_clean(story) or _clean(need) or _clean(text))
    return "%s: %s" % (project, body) if body else str(project)


def fit_text(text):
    """Pure. The code fallback body (see fit_line, step d) of a message: the asking sentence kept, earlier sentences added from the start."""
    return _fit_text(_clean(text))


def _fit_text(t):
    sents = [x.strip() for x in _SENT_RE.split(t) if x.strip()]
    if not sents:
        return ""
    ask_i = next((i for i in range(len(sents) - 1, -1, -1) if _asks(sents[i])), None)
    if ask_i is None:
        head, budget = [], FIT_WORDS
        for sent in sents:
            for u in _units(sent, FIT_WORDS):
                if head and _n(u) > budget:
                    return " ".join(head)
                if not head and _n(u) > FIT_WORDS:   # one long clause with no break: the start, nothing to mark a cut
                    u = " ".join(u.split()[:FIT_WORDS])
                head.append(u)
                budget -= _n(u)
        return " ".join(head)
    ask = sents[ask_i]
    if _n(ask) > FIT_WORDS:
        parts = [c for c in _CLAUSE_RE.split(ask) if c.strip()]
        ask = parts[-1] if parts else ask
        if _n(ask) > FIT_WORDS:
            ask = " ".join(ask.split()[-FIT_WORDS:])
    out, budget = [], FIT_WORDS - _n(ask)
    for sent in sents[:ask_i]:
        if _n(sent) > budget:
            break
        out.append(sent)
        budget -= _n(sent)
    return " ".join(out + [ask])


def room_line(room):
    ask = room.get("ask")
    if ask:
        return ask_line(room["project"], ask.get("what", ""))
    return fit_line(room["project"], room.get("need", ""), room.get("line", ""), room.get("story", ""),
                    room.get("_src") or room.get("name") or "")


def short_command(what):
    """The exact command if short; else the program, its first argument and 'and N more words' (the phone can show the full one)."""
    w = str(what or "").split()
    if len(w) <= CMD_WORDS:
        return " ".join(w)
    return "%s and %d more words" % (" ".join(w[:2]), len(w) - 2)


def ask_line(project, what):
    what = " ".join(str(what or "").split())
    verb = "wants to " + ("" if re.match(r"^(edit|write|read|fetch|search)\b", what) else "run ")
    return "%s %s%s" % (project, verb, short_command(what))

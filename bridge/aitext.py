"""Two small jobs for the local model, for the phone (ROOMS-API.md): /ai/digest (one line over the notifications) and
/ai/short (a short version of a long message). Python 3 stdlib only. Same rule as story.py: the model is a function
`model(prompt) -> str`; every answer passes a guard; on any doubt the answer is "" and the phone reads the original.
"""
import hashlib
import json
import re
import threading
import time

from story import check_story, squash, risky_tokens, _in_source, RETRY_AFTER

# label -> (one, many). The model only picks a label per item; we count, so the numbers always add up.
LABELS = {"chat": ("chat", "chats"), "delivery": ("delivery", "deliveries"), "news": ("news item", "news items"),
          "work": ("work message", "work messages"), "payment": ("payment", "payments"), "reminder": ("reminder", "reminders"),
          "junk": ("junk", "junk"), "other": ("other", "other")}
MAX_ITEMS = 30
MAX_GROUPS = 4
SHORT_WORDS = 45   # the guard limit; the prompt asks for 30
MAX_ENTRIES = 200
CONTEXT_CHARS = 1000   # earlier messages of the same chat, as far as the model sees them
REPLY_WORDS = 12
TASK_WORDS = 9


def build_digest_prompt(items):
    lines = []
    for i, it in enumerate(items, 1):
        lines.append("%d. %s | %s | %s" % (i, squash(it.get("app"))[:30], squash(it.get("title"))[:60], squash(it.get("text"))[:120]))
    return ("Below are %d phone notifications (app | title | text).\n---\n%s\n---\n"
            "Give each one a label, in order. Allowed labels: %s.\n"
            "chat = a person writing to the user; delivery = a parcel, food or ride; junk = ads, promotions, marketing.\n"
            "Output only JSON: {\"labels\": [one label per notification, exactly %d]}."
            % (len(items), "\n".join(lines), ", ".join(LABELS), len(items)))


def parse_labels(text, n):
    """Pure. The list of n labels from the model's reply, or None."""
    m = re.search(r"\{.*\}", str(text or ""), re.S)
    if not m:
        return None
    try:
        o = json.loads(m.group(0))
    except ValueError:
        return None
    labels = o.get("labels") if isinstance(o, dict) else None
    if not isinstance(labels, list) or len(labels) != n:
        return None
    out = []
    for v in labels:
        v = squash(v).lower().rstrip("s") if isinstance(v, str) else ""
        v = {"deliverie": "delivery", "parcel": "delivery", "message": "chat", "ad": "junk", "promotion": "junk", "spam": "junk"}.get(v, v)
        if v not in LABELS:
            return None
        out.append(v)
    return out


def digest_line(labels):
    """Pure. "3 chats, 2 deliveries, 3 junk": groups by count (most first, ties by first seen), at most MAX_GROUPS (the rest
    joins "other"). The numbers always add up to len(labels)."""
    count, order = {}, []
    for l in labels:
        if l not in count:
            order.append(l)
        count[l] = count.get(l, 0) + 1
    ranked = sorted(order, key=lambda l: (-count[l], order.index(l)))
    keep = ranked[:MAX_GROUPS]
    rest = sum(count[l] for l in ranked[MAX_GROUPS:])
    parts = [(count[l], l) for l in keep]
    if rest:
        parts.append((rest, "other"))
    return ", ".join("%d %s" % (c, LABELS[l][0 if c == 1 else 1]) for c, l in parts)


def check_digest(line, n):
    """Pure. True when the numbers in `line` add up to n."""
    nums = [int(x) for x in re.findall(r"\d+", str(line))]
    return bool(nums) and sum(nums) == n and len(str(line).split()) <= 24


def make_digest(items, model):
    """Pure given `model`. The line, or "" (no model, failure, bad answer)."""
    items = [i for i in items if isinstance(i, dict)][:MAX_ITEMS]
    if model is None or not items:
        return ""
    try:
        labels = parse_labels(model(build_digest_prompt(items)), len(items))
        line = digest_line(labels) if labels else ""
    except Exception:
        return ""
    return line if check_digest(line, len(items)) else ""


def earlier_block(context):
    c = squash(context)[:CONTEXT_CHARS]
    return ("Earlier messages in the same chat (oldest first, for background only):\n---\n%s\n---\n" % str(context).strip()[:CONTEXT_CHARS]) if c else ""


TASK_WORDS = 12   # the guard limit for a task line; the prompt asks for 8


def build_task_prompt(text):
    return ("Below is a to-do item.\n---\n%s\n---\n"
            "Write what to do, for a blind listener, as JSON: {\"short\": \"...\"}.\n"
            "short: one short action phrase, at most 8 words, in the same language as the item, plain words, no markdown. Keep the key thing. "
            "Use only names and numbers that are in the item. Never invent.\nOutput only the JSON." % str(text)[:1500])


TASKS_WORDS = 20   # the guard limit for the summary of a whole to-do list; the prompt asks for 12


def build_tasks_prompt(text):
    return ("Below is a person's to-do list, one item per line.\n---\n%s\n---\n"
            "Say what the list is mostly about, for a blind listener, as JSON: {\"short\": \"...\"}.\n"
            "short: the 2 or 3 main themes, at most 12 words, in English, plain words, no markdown, no counts. Example: mostly buying electronics parts, and a few calls. "
            "Use only things that are in the list. Never invent.\nOutput only the JSON." % str(text)[:3000])


def build_short_prompt(text, context="", kind=""):
    if kind == "task":
        return build_task_prompt(text)
    if kind == "tasks":
        return build_tasks_prompt(text)
    return ("%sBelow is a message sent to a person.\n---\n%s\n---\n"
            "Write what it says for a blind listener, as JSON: {\"short\": \"...\"}.\n"
            "short: one or two sentences, at most %d words, plain words, no markdown. Say who wants what, when, and any number or place. "
            "Use only facts, numbers and names that are in the message or the earlier messages. Never invent.\nOutput only the JSON." % (earlier_block(context), str(text)[:3000], 30))


def parse_short(text):
    m = re.search(r"\{.*\}", str(text or ""), re.S)
    if not m:
        return ""
    try:
        o = json.loads(m.group(0))
    except ValueError:
        return ""
    v = o.get("short") if isinstance(o, dict) else ""
    return re.sub(r"(\*\*|__|`|^#+\s*)", "", squash(v if isinstance(v, str) else ""))


def make_short(text, model, context="", kind=""):
    """Pure given `model`. A 1-2 sentence version of `text` that passes the accuracy guard (names and numbers are checked against
    `text` and `context` together), or ""."""
    text = str(text or "")
    if model is None or not squash(text):
        return ""
    try:
        s = parse_short(model(build_short_prompt(text, context, kind)))
    except Exception:
        return ""
    ok, _ = check_story(s, text, squash(context)[:CONTEXT_CHARS], limit={"task": TASK_WORDS, "tasks": TASKS_WORDS}.get(kind, SHORT_WORDS))
    return s if ok else ""


def build_replies_prompt(text, lang, context=""):
    language = "Spanish" if lang == "es" else "English"
    return ("%sSomeone sent you this message:\n---\n%s\n---\n"
            "Write what you could answer, in %s, as JSON: {\"replies\": [...], \"tasks\": [...]}.\n"
            "replies: exactly 3 short sentences you could send, at most %d words each: one agreeing, one declining, one not sure. "
            "tasks: 0 to 3 to-do items for yourself that the message asks of you, each starting with a verb, at most %d words; an empty list if it asks nothing. "
            "Use only facts, numbers and names that are in the message or the earlier messages. Never invent.\nOutput only the JSON."
            % (earlier_block(context), str(text)[:2000], language, REPLY_WORDS, TASK_WORDS))


def parse_replies(text):
    """Pure. (replies, tasks) lists of strings from the model's reply; ([], []) if it is not the JSON asked for."""
    m = re.search(r"\{.*\}", str(text or ""), re.S)
    if not m:
        return [], []
    try:
        o = json.loads(m.group(0))
    except ValueError:
        return [], []
    if not isinstance(o, dict):
        return [], []

    def items(v):
        return [re.sub(r"(\*\*|__|`|^#+\s*)", "", squash(x)) for x in v if isinstance(x, str)] if isinstance(v, list) else []
    return items(o.get("replies")), items(o.get("tasks"))


def check_items(items, source, context, limit, n=3):
    """Pure. The items that are short and invent no number, name, file or quote (checked against source and context together), no repeats."""
    hay = (str(source) + "\n" + str(context)).lower()
    out = []
    for it in items:
        it = squash(it).strip(" \"'")
        if not it or len(it.split()) > limit or it.lower() in [o.lower() for o in out]:
            continue
        if any(not _in_source(t, hay) for t in risky_tokens(it)):
            continue
        out.append(it)
    return out[:n]


def make_replies(text, lang, model, context=""):
    """Pure given `model`. {"replies": [...], "tasks": [...]} (each item guarded), or {} (no model, failure, nothing usable)."""
    text = str(text or "")
    if model is None or not squash(text):
        return {}
    try:
        r, t = parse_replies(model(build_replies_prompt(text, lang, context)))
    except Exception:
        return {}
    ctx = squash(context)[:CONTEXT_CHARS]
    r, t = check_items(r, text, ctx, REPLY_WORDS), check_items(t, text, ctx, TASK_WORDS)
    return {"replies": r, "tasks": t} if r or t else {}


class AiText:
    """Digest and short answers, cached (by hash of the input). Runs the model in the calling thread (the server has one per request)."""

    def __init__(self, model=None, clock=time.monotonic):
        self.model, self.clock = model, clock
        self.lock = threading.Lock()
        self.cache = {}   # key -> (answer, time)

    def _get(self, key, make):
        with self.lock:
            e = self.cache.get(key)
            if e and (e[0] or self.clock() - e[1] < RETRY_AFTER):
                return e[0]
        ans = make()
        with self.lock:
            self.cache[key] = (ans, self.clock())
            while len(self.cache) > MAX_ENTRIES:
                self.cache.pop(next(iter(self.cache)))
        return ans

    @staticmethod
    def _key(kind, payload):
        return kind + hashlib.sha1(json.dumps(payload, sort_keys=True, ensure_ascii=False).encode()).hexdigest()

    def digest(self, items):
        items = [i for i in (items or []) if isinstance(i, dict)][:MAX_ITEMS]
        if not items:
            return ""
        return self._get(self._key("d", items), lambda: make_digest(items, self.model))

    def short(self, text, context="", kind=""):
        text, context = str(text or ""), squash(context)[:CONTEXT_CHARS]
        kind = kind if kind in ("task", "tasks") else ""
        return self._get(self._key("s", [text, context] + ([kind] if kind else [])), lambda: make_short(text, self.model, context, kind))

    def replies(self, text, lang="en", context=""):
        text, context, lang = str(text or ""), squash(context)[:CONTEXT_CHARS], "es" if lang == "es" else "en"
        got = self._get(self._key("r", [text, lang, context]), lambda: make_replies(text, lang, self.model, context))
        return {"replies": list(got.get("replies", [])), "tasks": list(got.get("tasks", []))} if got else {"replies": [], "tasks": []}

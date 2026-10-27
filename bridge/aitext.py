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


def build_short_prompt(text):
    return ("Below is a message sent to a person.\n---\n%s\n---\n"
            "Write what it says for a blind listener, as JSON: {\"short\": \"...\"}.\n"
            "short: one or two sentences, at most %d words, plain words, no markdown. Say who wants what, when, and any number or place. "
            "Use only facts, numbers and names that are in the message. Never invent.\nOutput only the JSON." % (str(text)[:3000], 30))


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


def make_short(text, model):
    """Pure given `model`. A 1-2 sentence version of `text` that passes the accuracy guard, or ""."""
    text = str(text or "")
    if model is None or not squash(text):
        return ""
    try:
        s = parse_short(model(build_short_prompt(text)))
    except Exception:
        return ""
    ok, _ = check_story(s, text, limit=SHORT_WORDS)
    return s if ok else ""


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

    def short(self, text):
        text = str(text or "")
        return self._get(self._key("s", text), lambda: make_short(text, self.model))

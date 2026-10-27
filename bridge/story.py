"""The short story of a window: what it was doing, what happened, what it wants from you. Python 3 stdlib only.

The pure part (prompt, parse, accuracy guard, fallback) is tested without a model. The model (Ollama) is a plain function
`model(prompt) -> str`, so tests pass a fake. Never blocks /rooms: StoryCache.peek() answers from the cache and starts the work
in a background thread; StoryCache.ask() may wait (default 6 s) and then gives the fallback. Contract: ROOMS-API.md.
"""
import collections
import hashlib
import json
import re
import threading
import time
import urllib.request

MAX_WORDS = 25        # asked of the model
LIMIT_WORDS = 30      # the guard accepts a little over
GO_WORDS = 5
TIMEOUT = 6.0
RETRY_AFTER = 60.0    # a failed model run is tried again after this many seconds
MAX_ENTRIES = 300

NUMBER_RE = re.compile(r"\d+(?:[.,:]\d+)*")
FILE_RE = re.compile(r"\b[\w-]+(?:\.[\w-]+)*\.[A-Za-z][A-Za-z0-9]{0,5}\b")
QUOTE_RE = re.compile(r"\"([^\"\n]+)\"|“([^”\n]+)”|`([^`\n]+)`")
# a plain "go" only makes sense when the message asks for a yes or a go
ASKS_RE = re.compile(r"\?|\bgo\b|\byes\b|\bshould i\b|\bshall i\b|\bwant me\b|\bready to\b|\bapprove\b|\bconfirm\b", re.I)
CAP_RE = re.compile(r"\b[A-Z][\w-]*")


def squash(text):
    return " ".join(str(text or "").split())


def _words(text):
    return squash(text).split()


def cut_words(text, n):
    w = _words(text)
    return " ".join(w[:n]) + ("..." if len(w) > n else "")


def first_sentence(text, n=MAX_WORDS):
    t = squash(text)
    m = re.search(r"(?<=[.!?])\s", t)
    return cut_words(t[:m.start()] if m else t, n)


def _sentence_starts(text):
    """Offsets where a sentence begins (the first word of a sentence is capitalised for grammar, not because it is a name)."""
    out = {0}
    for m in re.finditer(r"[.!?:]\s+|\n+", text):
        out.add(m.end())
    return out


def risky_tokens(text):
    """Pure. The tokens of `text` that must come from the source: numbers, file names, quoted strings, capitalised words
    that do not start a sentence."""
    text = str(text or "")
    toks = [m.group(0) for m in NUMBER_RE.finditer(text)]
    toks += [m.group(0) for m in FILE_RE.finditer(text)]
    for m in QUOTE_RE.finditer(text):
        toks.append(next(g for g in m.groups() if g))
    starts = _sentence_starts(text)
    toks += [m.group(0) for m in CAP_RE.finditer(text) if m.start() not in starts]
    return toks


FREE = {"ai", "claude", "ok", "okay"}   # words about the speaker, not facts


def _in_source(token, source_lower):
    t = token.lower().strip(".,:;")
    if not t or t in FREE:
        return True
    if t[0].isdigit():
        return re.search(r"(?<![\d.])" + re.escape(t) + r"(?![\d])", source_lower) is not None
    return t in source_lower


def check_story(story, source, context="", limit=LIMIT_WORDS):
    """Pure. (ok, reason). The story must be short, shorter than the source, and invent nothing: every number, file name, quoted
    string and capitalised word must appear in `source` (or in `context`, the pane title and project)."""
    story = squash(story)
    if not story:
        return False, "empty"
    if len(_words(story)) > limit:
        return False, "too long"
    if len(story) >= len(squash(source)):
        return False, "not shorter than the source"
    hay = (str(source) + "\n" + str(context)).lower()
    for tok in risky_tokens(story):
        if not _in_source(tok, hay):
            return False, "invented: " + tok
    return True, ""


def check_go(go, source, context=""):
    """Pure. The cleaned go text, or "" when it is too long or invents something."""
    go = squash(go).strip(" .\"'")
    if not go or len(_words(go)) > GO_WORDS or not ASKS_RE.search(str(source)):
        return ""
    hay = (str(source) + "\n" + str(context)).lower()
    if any(not _in_source(t, hay) for t in risky_tokens(go)):
        return ""
    return go


def build_prompt(kind, source, context=""):
    if kind == "shell":
        what = "the last lines of a terminal window"
        body = source[-3000:]
    else:
        what = "the last message of an AI coding assistant to its user"
        body = source[-4000:]
    return (
        "Below is %s%s.\n---\n%s\n---\n"
        "Write the story for a blind listener, as JSON: {\"story\": \"...\", \"go\": \"...\"}.\n"
        "story: at most %d words, plain words, no markdown. In up to 3 short parts, only those that exist: what it was doing; "
        "what happened (done, failed, or a question); what it wants from the user (or: nothing needed). Start with the action, like \"Pushed the README.\"; do not write \"the AI\". Use only facts, numbers and names that are in the text. Never invent.\n"
        "go: ONLY if the text asks the user a yes/no question or to say \"go\", at most %d words saying what that \"go\" or \"yes\" approves (example: \"commit the fix\"). "
        "Otherwise an empty string.\n"
        "Output only the JSON." % (what, (" (window: %s)" % context) if context else "", body, MAX_WORDS, GO_WORDS))


def parse_reply(text):
    """Pure. (story, go) strings from the model's reply; ("", "") if it is not the JSON asked for."""
    t = str(text or "").strip()
    m = re.search(r"\{.*\}", t, re.S)
    if not m:
        return "", ""
    try:
        o = json.loads(m.group(0))
    except ValueError:
        return "", ""
    if not isinstance(o, dict):
        return "", ""
    clean = lambda v: re.sub(r"(\*\*|__|`|^#+\s*)", "", squash(v if isinstance(v, str) else ""))
    return clean(o.get("story")), clean(o.get("go"))


def make_story(kind, source, context, fallback, model):
    """Pure given `model`. (story, go, from_model). `fallback` is the story used when the model is off, fails, or breaks the guard
    (the room's need line, else the first sentence of the message)."""
    source = str(source or "")
    fb = squash(fallback) or first_sentence(source)
    if not squash(source) or model is None:
        return fb, "", False
    try:
        story, go = parse_reply(model(build_prompt(kind, source, context)))
    except Exception:   # Ollama down, timeout, bad answer
        return fb, "", False
    ok, _ = check_story(story, source, context)
    if not ok:
        return fb, "", False
    return story, check_go(go, source, context), True


def ollama_model(url="http://127.0.0.1:11434/api/generate", model="gemma3:4b", timeout=TIMEOUT):
    def call(prompt):
        body = json.dumps({"model": model, "prompt": prompt, "stream": False, "format": "json", "keep_alive": "30m",
                           "options": {"temperature": 0, "num_predict": 110}}).encode()
        req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read()).get("response", "")
    return call


class StoryCache:
    """Stories cached by (pane id, hash of the source). The model runs in a background thread."""

    def __init__(self, model=None, clock=time.monotonic):
        self.model = model
        self.clock = clock
        self.lock = threading.Lock()
        self.done = collections.OrderedDict()   # key -> (result dict, time, from_model)
        self.pending = {}                        # key -> threading.Event

    @staticmethod
    def key(pane, source):
        return (pane, hashlib.sha1(str(source).encode("utf-8", "ignore")).hexdigest())

    def _fresh(self, key):
        e = self.done.get(key)
        if e and (e[2] or self.model is None or self.clock() - e[1] < RETRY_AFTER):
            return e[0]
        return None

    def _start(self, key, kind, source, context, fallback):
        """Needs self.lock. Returns the Event of the work, or None when there is no model (the answer is made at once)."""
        if self.model is None:
            story, go, ok = make_story(kind, source, context, fallback, None)
            self._put(key, story, go, ok)
            return None
        ev = self.pending.get(key)
        if ev is None:
            ev = self.pending[key] = threading.Event()
            threading.Thread(target=self._work, args=(key, ev, kind, source, context, fallback), daemon=True).start()
        return ev

    def _put(self, key, story, go, ok):
        self.done[key] = ({"story": story, "go": go}, self.clock(), ok)
        self.done.move_to_end(key)
        for old in [k for k in self.done if k[0] == key[0] and k != key]:
            del self.done[old]   # an older source of the same pane
        while len(self.done) > MAX_ENTRIES:
            self.done.popitem(last=False)

    def _work(self, key, ev, kind, source, context, fallback):
        story, go, ok = make_story(kind, source, context, fallback, self.model)
        with self.lock:
            self._put(key, story, go, ok)
            self.pending.pop(key, None)
        ev.set()

    def peek(self, pane, kind, source, context="", fallback=""):
        """The cached {story, go}, or {"story": "", "go": ""} while the work runs (it is started when new). Never waits."""
        empty = {"story": "", "go": ""}
        if not squash(source):
            return {"story": squash(fallback), "go": ""} if squash(fallback) else empty
        key = self.key(pane, source)
        with self.lock:
            r = self._fresh(key)
            if r is not None:
                return dict(r)
            self._start(key, kind, source, context, fallback)
            r = self._fresh(key)
            return dict(r) if r is not None else empty

    def ask(self, pane, kind, source, context="", fallback="", wait=TIMEOUT):
        """Like peek, but waits up to `wait` seconds for the model; then the fallback (the work goes on and fills the cache)."""
        if not squash(source):
            return {"story": squash(fallback), "go": ""}
        key = self.key(pane, source)
        with self.lock:
            r = self._fresh(key)
            if r is not None:
                return dict(r)
            ev = self._start(key, kind, source, context, fallback)
            r = self._fresh(key)
            if r is not None:
                return dict(r)
        if ev is not None:
            ev.wait(wait)
        with self.lock:
            r = self._fresh(key)
        return dict(r) if r is not None else {"story": squash(fallback) or first_sentence(source), "go": ""}

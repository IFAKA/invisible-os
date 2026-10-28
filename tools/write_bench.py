#!/usr/bin/env python3
"""Offline test bench for the Write keyboard: the cost of typing real texts under old and new designs.

Usage: tools/write_bench.py [--all | --n 50] [--seed 2003] [--item-ms 350] [--reaction-ms 400] [--no-cache] [--verify]
  one summary line per design; details in app/logs/write/bench-*.log; results cached by inputs
  (script, model, texts, step costs) in app/logs/write/bench-cache.json.

Texts: (a) the owner's real texts; (b) the MacKenzie & Soukoreff (2003) phrase set (500 phrases), a fixed-seed sample
  of 50 by default. Source: https://www.yorku.ca/mack/PhraseSets.zip (file phrases2.txt, "Enhanced
  memorability...": R. W. Soukoreff and I. S. MacKenzie, CHI 2003). Licence is not stated, so it is NOT kept in git:
  it is downloaded once to app/logs/write/phrases2.txt (untracked).
Ranker: a pure-python port of the real next-word LSTM (NextWord.kt, int8 "ULM1" lm.bin + vocab.txt). Its output per
  context is cached in app/logs/write/lm-cache-*.pkl. --verify compares with app/programs/write/build/lm-top5.txt, which
  the JVM test NextWordDumpTest writes (cd app && ./gradlew :programs:write:testDebugUnitTest --offline --tests '*NextWordDumpTest*').
Step costs (measured from the owner's write_usage.tsv): next pair 2.9 s, pick 3.1 s, menu trip 9.4 s.
Designs: today (the bug: fixed letter order, Space via menu), fixed (letters by the model's chance), loop (placeholder
  for the Ticker/Nomon-style letter loop; a letter costs its spoken position * item_ms + reaction_ms)."""
import argparse, array, bisect, datetime, hashlib, heapq, io, json, math, multiprocessing as mp
import operator, os, pickle, random, re, struct, sys, time, urllib.request, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, "..")
LM_DIR = os.path.join(ROOT, "app/programs/write/src/main/assets/lm")
LOGDIR = os.path.join(ROOT, "app/logs/write")
PHRASES = os.path.join(LOGDIR, "phrases2.txt")
KOTLIN_DUMP = os.path.join(ROOT, "app/programs/write/build/lm-top5.txt")
PHRASE_URL = "https://www.yorku.ca/mack/PhraseSets.zip"

NEXT, PICK, MENU = 2.9, 3.1, 9.4          # seconds, measured
MENU_ACTIONS = 5                           # 3 clicks to open + about 2 to reach and pick the item (estimate)
BYUSE = "eaosrnidltcumpbgvyqhfzjxkw"       # ALPHABET_BY_USE without n-tilde
OWNER = ["android paranoid", "hailie song", "billie jean"]
WORDS_SHOWN = 8                            # Typing.WORD_PAGES * 2
LETTER_RE = re.compile(r"[a-z']+")

# ---------------------------------------------------------------- the LSTM (port of NextWord.kt)
class LM:
    def __init__(self):
        d = open(os.path.join(LM_DIR, "lm.bin"), "rb").read()
        self.model_hash = hashlib.sha256(d).hexdigest()[:10]
        self.pos = 0
        assert d[:4] == b"ULM1"
        self.pos = 4
        self.V, self.E, self.H = struct.unpack_from("<3i", d, 4); self.pos = 16
        self.d = d
        self.emb = self.q8(); self.wih = self.q8(); self.whh = self.q8()
        n = self.i32(); self.b = self.f32s(n)
        self.proj = self.q8()
        n = self.i32(); self.bias = self.f32s(n)
        text = open(os.path.join(LM_DIR, "vocab.txt")).read()
        self.vocab = text.split("\n")[:self.V]
        self.vhash = hashlib.sha256(text.encode()).hexdigest()[:6]
        self.ids = {}
        for i, w in enumerate(self.vocab): self.ids.setdefault(w, i)
        self.states = {}; self.sorted_ids = None
    def i32(self):
        v = struct.unpack_from("<i", self.d, self.pos)[0]; self.pos += 4; return v
    def f32s(self, n):
        v = struct.unpack_from("<%df" % n, self.d, self.pos); self.pos += 4 * n; return list(v)
    def q8(self):
        rows = self.i32(); cols = self.i32(); s = self.f32s(rows)
        raw = struct.unpack_from("<%db" % (rows * cols), self.d, self.pos); self.pos += rows * cols
        return [tuple(x * s[r] for x in raw[r * cols:(r + 1) * cols]) for r in range(rows)]
    @staticmethod
    def dot(w, x): return [sum(map(operator.mul, r, x)) for r in w]
    def step(self, i, h0, c0):
        H = self.H
        g = self.dot(self.wih, self.emb[i]); r = self.dot(self.whh, h0)
        g = [a + b + c for a, b, c in zip(g, r, self.b)]
        sig = lambda v: 1.0 / (1.0 + math.exp(-v)) if v > -500 else 0.0
        h = [0.0] * H; c = [0.0] * H
        for k in range(H):  # gate order i, f, g, o
            c[k] = sig(g[H + k]) * c0[k] + sig(g[k]) * math.tanh(g[2 * H + k])
            h[k] = sig(g[3 * H + k]) * math.tanh(c[k])
        return h, c
    def state(self, words):
        key = tuple(words)
        if key in self.states: return self.states[key]
        if not words: s = self.step(1, [0.0] * self.H, [0.0] * self.H)
        else:
            p = self.state(words[:-1]); s = self.step(self.ids.get(words[-1], 2), p[0], p[1])
        self.states[key] = s; return s
    def logprobs(self, words):
        """log probability of every id (ids 0-2 are special and never offered)."""
        p = self.dot(self.proj, self.state(words)[0])
        lg = [a + b for a, b in zip(self.dot(self.emb, p), self.bias)]
        mx = max(lg); lse = math.log(sum(math.exp(v - mx) for v in lg)) + mx
        return array.array("f", (v - lse for v in lg))

_LM = None
def _work(words): return words, _LM.logprobs(list(words))

def lm_table(lm, contexts, log):
    """logprob arrays for every needed context (tuple of words), cached on disk, computed in parallel when missing."""
    global _LM
    path = os.path.join(LOGDIR, "lm-cache-%s-%s.pkl" % (lm.model_hash, lm.vhash))
    table = pickle.load(open(path, "rb")) if os.path.exists(path) else {}
    need = sorted(set(contexts) - set(table))
    if need:
        t0 = time.time(); _LM = lm
        with mp.get_context("fork").Pool() as pool:
            for k, v in pool.imap_unordered(_work, need, chunksize=4): table[k] = v
        pickle.dump(table, open(path, "wb"))
        log("lstm: %d new contexts in %.1f s (cache %s, %d total)" % (len(need), time.time() - t0, os.path.basename(path), len(table)))
    return table

def verify(lm, log):
    if not os.path.exists(KOTLIN_DUMP): return "not checked (no Kotlin dump)"
    bad = []
    for line in open(KOTLIN_DUMP):
        ctx, top = line.rstrip("\n").split("\t"); ctx = ctx[4:].split()
        lp = lm.logprobs(ctx)
        mine = sorted(range(3, lm.V), key=lambda i: -lp[i])[:5]
        theirs = [(t.rsplit(":", 1)[0], float(t.rsplit(":", 1)[1])) for t in top.split(" ")]
        ok = [lm.vocab[i] for i in mine] == [w for w, _ in theirs] and all(abs(lp[lm.ids[w]] - v) < 0.02 for w, v in theirs)
        log("verify ctx=%r python=%s kotlin=%s -> %s" % (" ".join(ctx), [(lm.vocab[i], round(lp[i], 3)) for i in mine], theirs, "OK" if ok else "MISMATCH"))
        if not ok: bad.append(ctx)
    return "top-5 matches Kotlin on 3 contexts" if not bad else "MISMATCH on %s" % bad

# ---------------------------------------------------------------- texts
def load_phrases(log):
    if not os.path.exists(PHRASES):
        os.makedirs(LOGDIR, exist_ok=True)
        z = zipfile.ZipFile(io.BytesIO(urllib.request.urlopen(PHRASE_URL, timeout=60).read()))
        open(PHRASES, "wb").write(z.read("phrases2.txt"))   # read one member's bytes only, never extract
        log("downloaded %s" % PHRASE_URL)
    out = [re.sub(r"[^a-z ]", "", l.strip().lower()) for l in open(PHRASES, encoding="latin-1") if l.strip()]
    return out

# ---------------------------------------------------------------- the model of one context
class Ctx:
    def __init__(self, lm, lp, recent):
        self.lm = lm; self.recent = recent
        if lm.sorted_ids is None:
            lm.sorted_ids = sorted((i for i in range(3, lm.V) if LETTER_RE.fullmatch(lm.vocab[i])), key=lambda i: lm.vocab[i])
        self.keys = [lm.vocab[i] for i in lm.sorted_ids]; self.p = [math.exp(lp[i]) for i in lm.sorted_ids]
    def with_prefix(self, prefix):
        lo = bisect.bisect_left(self.keys, prefix); hi = bisect.bisect_left(self.keys, prefix + "￿")
        return [(self.keys[k], self.p[k]) for k in range(lo, hi) if self.keys[k] not in self.recent]
    def mass(self, prefix):
        m = {}
        for w, p in self.with_prefix(prefix):
            if len(w) > len(prefix): m[w[len(prefix)]] = m.get(w[len(prefix)], 0.0) + p
        return m
    def top_words(self, prefix, short_ok):
        ws = [(w, p) for w, p in self.with_prefix(prefix) if prefix or short_ok or (len(w) >= 3 and "'" not in w)]
        return [w for w, _ in heapq.nlargest(WORDS_SHOWN, ws, key=lambda x: x[1])]

def bigrams(lm):
    t = {}
    for i in range(3, lm.V):
        w = lm.vocab[i]
        if not re.fullmatch(r"[a-z]+", w): continue
        wt = 1.0 / (i + 10)
        for a, b in zip("^" + w, w): t.setdefault(a, {}); t[a][b] = t[a].get(b, 0.0) + wt
    return {a: {b: v / sum(d.values()) for b, v in d.items()} for a, d in t.items()}

# ---------------------------------------------------------------- designs: (seconds, actions) to type one word
def pair_cost(rank): return (rank // 2) * NEXT + PICK, (rank // 2) * 2 + 1
def add(a, b): return a[0] + b[0], a[1] + b[1]
MENU_COST = (MENU, MENU_ACTIONS)

def spell(ctx, word, order_of):
    t = (0.0, 0)
    for i, ch in enumerate(word):
        order = order_of(ctx, word[:i])
        t = add(t, pair_cost(order.index(ch)))
    return t

def design_today(ctx, word, bg, prm):
    if word in ctx.top_words("", False): return pair_cost(ctx.top_words("", False).index(word))
    t = add(MENU_COST, spell(ctx, word, lambda c, p: BYUSE))   # menu to Letters, spell, menu Space
    return add(t, MENU_COST)

def letter_order_fixed(ctx, prefix, bg):
    m = ctx.mass(prefix); b = bg.get(prefix[-1] if prefix else "^", {})
    return sorted(BYUSE, key=lambda c: -(m.get(c, 0.0) + 1e-6 * b.get(c, 0.0)))

def design_fixed(ctx, word, bg, prm):
    if word in ctx.top_words("", False): return pair_cost(ctx.top_words("", False).index(word))
    t = add(MENU_COST, spell(ctx, word, lambda c, p: letter_order_fixed(c, p, bg)))
    return add(t, MENU_COST)

def design_loop(ctx, word, bg, prm):
    """The real Write loop (LoopItems.kt + LetterLoop.kt): items in the order the app speaks them, two channels (item k: voice k%2,
    channel B half an item later), the cycle = the longer channel. Order: up to 3 word guesses, then letters by chance (bigrams for an
    unknown prefix), then Space (only with a prefix), Undo, Send last. A pick costs: wait to the item's first onset + reaction, plus
    (clicks - 1) more whole cycles (the click model wants about 2 clicks for a letter among 175 ms neighbours; a word guess with a
    high chance 1), plus the short spoken echo of the prefix. Undo and Send are never needed here (no mistakes, no send)."""
    item, react = prm["item_ms"] / 1000, prm["reaction_ms"] / 1000
    say, clicks = prm["say_ms"] / 1000, prm["loop_clicks"]
    t = (0.0, 0); prefix = ""
    def cost(order, i, sure):
        n = len(order) + 2   # + Undo, Send at the end (Space is already in order)
        period = math.ceil(n / 2) * item
        onset = (i // 2) * item + (i % 2) * item / 2
        return onset + react + ((1 if sure else clicks) - 1) * period + say
    while True:
        words = ctx.top_words(prefix, True)[:3]
        m = ctx.mass(prefix); b = bg.get(prefix[-1] if prefix else "^", {})
        probs = {w: p for w, p in ctx.with_prefix(prefix)}
        letters = sorted(BYUSE, key=lambda c: -(m.get(c, 0.0) + 1e-6 * b.get(c, 0.0)))
        order = [("W", w) for w in words] + [("L", c) for c in letters] + ([("S", " ")] if prefix else [])
        if word in words:
            i = words.index(word)
            return add(t, (cost(order, i, probs.get(word, 0) > 0.5), 1))   # a word pick adds its own space
        want = " " if prefix == word else word[len(prefix)]
        i = next(k for k, (kind, x) in enumerate(order) if x == want and kind != "W")
        t = add(t, (cost(order, i, False), 1))
        if prefix == word: return t
        prefix += want

DESIGNS = {"today": design_today, "fixed": design_fixed, "loop": design_loop}

# ---------------------------------------------------------------- run
def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--all", action="store_true", help="all 500 phrases")
    ap.add_argument("--n", type=int, default=50); ap.add_argument("--seed", type=int, default=2003)
    ap.add_argument("--item-ms", type=int, default=350); ap.add_argument("--reaction-ms", type=int, default=400)
    ap.add_argument("--say-ms", type=int, default=800); ap.add_argument("--loop-clicks", type=int, default=2)
    ap.add_argument("--no-cache", action="store_true"); ap.add_argument("--verify", action="store_true")
    a = ap.parse_args()
    t0 = time.time(); os.makedirs(LOGDIR, exist_ok=True)
    logpath = os.path.join(LOGDIR, "bench-%s.log" % datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
    lf = open(logpath, "w")
    def log(s): lf.write(s + "\n"); lf.flush()
    phrases = load_phrases(log)
    sample = phrases if a.all else random.Random(a.seed).sample(phrases, min(a.n, len(phrases)))
    prm = {"item_ms": a.item_ms, "reaction_ms": a.reaction_ms, "say_ms": a.say_ms, "loop_clicks": a.loop_clicks}
    lm = LM()
    key = hashlib.sha256(json.dumps([open(__file__).read(), lm.model_hash, lm.vhash, OWNER, sample, prm, [NEXT, PICK, MENU]]).encode()).hexdigest()[:16]
    cpath = os.path.join(LOGDIR, "bench-cache.json")
    cache = json.load(open(cpath)) if os.path.exists(cpath) else {}
    port = verify(lm, log) if (a.verify or os.path.exists(KOTLIN_DUMP)) else "not checked"
    if key in cache and not a.no_cache:
        res = cache[key]; log("cached result %s" % key)
    else:
        sets = {"owner": OWNER, "phrases%d" % len(sample): sample}
        contexts = []
        for texts in sets.values():
            for t in texts:
                ws = t.split()
                contexts += [tuple(ws[max(0, i - 8):i]) for i in range(len(ws))]
        table = lm_table(lm, contexts, log); bg = bigrams(lm)
        res = {}
        for sname, texts in sets.items():
            for dname, fn in DESIGNS.items():
                tot = (0.0, 0); chars = 0
                for t in texts:
                    ws = t.split(); written = []
                    for w in ws:
                        c = tuple(written[-8:])
                        if c not in _ctxcache: _ctxcache[c] = Ctx(lm, table[c], set(c[-3:]))
                        ctx = _ctxcache[c]
                        r = fn(ctx, w, bg, prm); tot = add(tot, r)
                        log("%s %s %-12s %-14s %6.1f s %3d actions" % (sname, dname, w, " ".join(written[-2:]), r[0], r[1]))
                        written.append(w)
                    chars += len(t)
                res["%s/%s" % (sname, dname)] = {"chars": chars, "sec": tot[0], "actions": tot[1]}
        cache[key] = res; json.dump(cache, open(cpath, "w"))
    for dname in DESIGNS:
        parts = []
        for sname in sorted({k.split("/")[0] for k in res}):
            r = res["%s/%s" % (sname, dname)]
            parts.append("%s: %.3f chars/s, %.1f s/char, %.2f actions/char" % (sname, r["chars"] / r["sec"], r["sec"] / r["chars"], r["actions"] / r["chars"]))
        print("%-5s %s" % (dname, " | ".join(parts)))
    print("lstm port: %s; run %.1f s; log %s" % (port, time.time() - t0, os.path.relpath(logpath, ROOT)))
    log("done in %.1f s" % (time.time() - t0))

_ctxcache = {}
if __name__ == "__main__": main()

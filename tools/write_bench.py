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
Designs: today (the bug: fixed letter order, Space via menu), fixed (letters by the model's chance), loop (the real letter loop
  simulated: a person clicks on every pass of the item he wants at onset + N(click-mean, click-sd) ms, misses a pass with chance
  --miss, makes a stray click with chance --false per item and pass; the clicks go into a Python port of ClickModel.kt
  (tools/click_model.py); wrong picks are undone through the Undo item; one person per seed keeps learning through all the texts;
  s/char = mean over --seeds seeds with a 95% interval). --sweep prints the table for sd 40/60/100 and threshold ln 99 / ln 19.
  --smooth E mixes letter bigrams into the letter chances (Kotlin LetterRank.SMOOTH); --focus [--focus-ms N --focus-cov C] plays a focus round after a click that does not choose
  (Kotlin LetterLoop/LoopFocus), then back to the full loop after --focus-passes quiet passes; --false P = chance of one stray click per full-loop pass (same per second in a focus round).
  --verify-click compares the Python ClickModel with the Kotlin dump (cd app && ./gradlew :oskit:testDebugUnitTest --offline --tests '*ClickModelDumpTest*')."""
import argparse, array, bisect, datetime, hashlib, heapq, io, json, math, multiprocessing as mp
import operator, os, pickle, statistics, random, re, struct, sys, time, urllib.request, zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import click_model as cm
ROOT = os.path.join(HERE, "..")
LM_DIR = os.path.join(ROOT, "app/programs/write/src/main/assets/lm")
LOGDIR = os.path.join(ROOT, "app/logs/write")
PHRASES = os.path.join(LOGDIR, "phrases2.txt")
KOTLIN_DUMP = os.path.join(ROOT, "app/programs/write/build/lm-top5.txt")
KOTLIN_CLICK = os.path.join(ROOT, "app/oskit/build/click-dump.txt")
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

def verify_click():
    if not os.path.exists(KOTLIN_CLICK): print("no Kotlin dump: run ClickModelDumpTest first (see --help)"); return 2
    keys = list("ABCDE"); noon = dict(zip(keys, [0, 600, 1200, 1800, 2400.])); prior = dict(zip(keys, [.4, .25, .15, .1, .1]))
    rounds = [[1300, 4300], [100], [1750, 2100, 5100], [2650], [1900], [1300], [1450], [200], [1350], [1800, 4800], [1400], [2600], [700, 1250, 1390]]
    mine = []
    for name, pr in (("p99", cm.Params()), ("p19", cm.Params(win_diff=math.log(19)))):
        m = cm.ClickModel(3000.0, pr); k = 0
        for ri, r in enumerate(rounds):
            m.start(prior, noon, 0.0, {"E": m.params.win_diff_high})
            for c in r:
                win = m.click(float(c)); k += 1; g = m.posteriors()
                mine.append((name, k, win, [g[x] for x in keys]))
                if win:
                    m.learn(m.chosen())
                    if ri == len(rounds) - 2 and name == "p99": m.undo()
                    break
    theirs = [l.split() for l in open(KOTLIN_CLICK)]
    if len(theirs) != len(mine): print("MISMATCH: %d clicks vs Kotlin %d" % (len(mine), len(theirs))); return 1
    worst = 0.0
    for (name, k, win, post), t in zip(mine, theirs):
        if t[0] != name or int(t[1]) != k or bool(int(t[2])) != win: print("MISMATCH at %s %d (win flag or order)" % (name, k)); return 1
        worst = max(worst, max(abs(a - float(b)) for a, b in zip(post, t[3:])))
    print("click model: Python matches Kotlin on %d clicks, worst posterior difference %.2e (%s)" % (len(mine), worst, "OK" if worst < 1e-6 else "MISMATCH"))
    return 0 if worst < 1e-6 else 1

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

ALPHABET = "abcdefghijklmnopqrstuvwxyzñ"
TIE = {c: 1e-12 * (27 - (BYUSE.index(c) if c in BYUSE else 27)) for c in ALPHABET}
PHRASE_Q, CONTROL_P = 0.15, 0.02
_items_cache = {}

def loop_items(ctx, ckey, bg, written_n, prefix, can_undo, floor=1e-6, smooth=0.0):
    """The items of one round in spoken order with their chances: port of LoopItems.build + LetterRank.letters (no whole-text phrases).
    Returns [(key, prior, risky)]; keys: ('w', word) ('l', c) 'space' 'undo' 'send'."""
    k = (ckey, prefix, can_undo, written_n > 0, floor, smooth)
    if k in _items_cache: return _items_cache[k]
    fits = ctx.with_prefix(prefix)
    mass = sum(p for _, p in fits)
    guesses = ctx.top_words(prefix, True)[:3]
    pm = dict(fits)
    p_words = [pm[w] / mass if mass > 0 else 0.0 for w in guesses]
    m = ctx.mass(prefix)
    g = bg.get(prefix[-1] if prefix else "^", {}); gt = sum(g.get(c, 0.0) for c in ALPHABET) or 1.0
    bchance = {c: g.get(c, 0.0) / gt for c in ALPHABET}
    if m:
        tot = sum(m.values()); chance = {c: m.get(c, 0.0) / tot for c in ALPHABET}
        if smooth: chance = {c: (1 - smooth) * chance[c] + smooth * bchance[c] for c in ALPHABET}   # letter mass = (1-e) word model + e letter bigram
    else: chance = bchance
    letters = sorted(ALPHABET, key=lambda c: -(chance[c] + TIE[c]))
    left = max(1.0 - sum(p_words), 0.2)
    has_text = written_n > 0 or bool(prefix)
    send_p = 0.005 if not has_text else CONTROL_P
    controls = (CONTROL_P if prefix else 0.0) + (CONTROL_P if can_undo else 0.0) + send_p
    kk = max(1.0 - controls, 0.3)
    out = [(("w", w), p_words[i] * kk, False) for i, w in enumerate(guesses)]
    out += [(("l", c), chance[c] * left * kk + floor, False) for c in letters]
    if prefix: out.append(("space", CONTROL_P, False))
    if can_undo: out.append(("undo", CONTROL_P, True))
    out.append(("send", send_p, True))
    _items_cache[k] = out
    return out

def _common(a, b):
    n = 0
    while n < min(len(a), len(b)) and a[n] == b[n]: n += 1
    return a[:n]

def simulate_session(texts, ctxs, bg, prm, seed):
    """One person typing all [texts] one after the other with the real loop. Returns (seconds, chars, picks, wrong_picks)."""
    rng = random.Random(seed)
    item = prm["item_ms"]; say = prm["say_ms"]; lead = 120.0
    thr = math.log(prm["threshold"]); params = cm.Params(win_diff=thr)
    if prm["profile_seed"]: params.mu0 = prm["click_mean"] / 1000; params.sigma0 = prm["click_sd"] / 1000
    prev = None; seconds = 0.0; chars = 0; picks = 0; wrong = 0; failed = 0
    for t in texts:
        chars += len(t); target = t.split(); written = []; prefix = ""; hist = []; rounds = 0; sec0 = seconds; picks0 = picks; wrong0 = wrong
        while True:
            rounds += 1
            if rounds > 3 * len(t) + 30: failed += 1; seconds = sec0; picks = picks0; wrong = wrong0; chars -= len(t); break   # stuck (the model keeps picking the wrong item): give up this text
            # where is the person: consistent with the target?
            ok = written == target[:len(written)] and (prefix == "" or (len(written) < len(target) and target[len(written)].startswith(prefix)))
            if not ok and not hist: raise RuntimeError("lost %r %r %r" % (t, written, prefix))
            if ok and len(written) == len(target): break
            if ok: ckey = tuple(written[-8:]); ipre = prefix
            else:   # a wrong state: its own contexts are not in the table (a Python LSTM step costs seconds), so the round is built for the right words with the common prefix; only the Undo item matters here
                good = 0
                while good < min(len(written), len(target)) and written[good] == target[good]: good += 1
                ckey = tuple(target[:good][-8:]); ipre = ""
                if good == len(written) and good < len(target): ipre = _common(prefix, target[good])
                if good == len(target): ckey = tuple(target[-8:][:-1]); ipre = ""
            items = loop_items(ctxs[ckey], ckey, bg, len(written), ipre, bool(hist), prm["letter_floor"], prm["smooth"])
            if ok:
                w = target[len(written)]
                words = [it[0] for it in items if it[0][0] == "w"]
                if ("w", w) in [x for x in words]: want = ("w", w)
                elif prefix == w: want = "space"
                else: want = ("l", w[len(prefix)])
            else: want = "undo"
            keys = [it[0] for it in items]; n = len(keys)
            if os.environ.get('BENCH_DEBUG'): print(t, written, repr(prefix), ok, want, len(hist), file=sys.stderr)
            period = math.ceil(n / 2) * item
            def layout(ks, ms): return {k: (i // 2) * ms + (i % 2) * ms / 2 for i, k in enumerate(ks)}, math.ceil(len(ks) / 2) * ms
            onset, _ = layout(keys, item)
            risky = {k for k, _, r in items if r}
            m = cm.ClickModel(period, params)
            if prev is not None: m.inherit(prev)
            elif prm["seed_offsets"]: m.seed(prm["seed_offsets"])
            m.start({k: max(p, 1e-9) for k, p, _ in items}, onset, 0.0, {k: m.params.win_diff_high for k in risky})
            chosen = None; at = 0.0; focused = False; t0 = 0.0; per = period; cur = keys; full_scores = None; guard = 0
            while chosen is None:
                guard += 1
                if guard > 400: chosen = "GIVEUP"; break
                nonfocus_stray = prm["false"] * per / period   # stray clicks per second are the same in a short focus pass
                cyc = 0; moved = False
                while not moved and chosen is None:
                    ev = []
                    for k in cur:
                        base = t0 + onset[k] + cyc * per
                        if k == want and rng.random() >= prm["miss"]: ev.append(max(0.0, base + rng.gauss(prm["click_mean"], prm["click_sd"])))
                        if prm["false_per_item"] and rng.random() < prm["false"]: ev.append(base + rng.random() * item)
                    if not prm["false_per_item"] and rng.random() < nonfocus_stray: ev.append(rng.random() * per + t0 + cyc * per)   # one stray click in this pass
                    ev.sort()
                    for e in (ev[:1] if prm["focus"] else ev):
                        at = e
                        if m.click(e): chosen = m.chosen(); break
                        if prm["focus"]:   # a click that did not choose: the next round plays only what the clicks leave likely
                            post = m.posteriors(); order = sorted(post, key=lambda k: -post[k]); fk = []; cum = 0.0
                            for k in order:
                                if k == "undo": continue
                                if len(fk) >= prm["focus_max"] or (cum >= prm["focus_cov"] and len(fk) >= prm["focus_min"]): break
                                fk.append(k); cum += post[k]
                            if "undo" in post: fk.append("undo")
                            sc = m.scores_map()
                            if not focused: full_scores = dict(sc)
                            else: full_scores.update(sc)
                            fscores = {k: sc[k] for k in fk}
                            fon, fper = layout(fk, prm["focus_ms"]); t0 = e + prm["focus_gap"]
                            m2 = cm.ClickModel(fper, params); m2.inherit(m)
                            m2.restart(fscores, fon, t0, {k: m.params.win_diff_high for k in fk if k in risky})
                            m = m2; onset = fon; per = fper; cur = fk; focused = True; moved = True
                            if prm["focus_trace"] and os.environ.get("BENCH_DEBUG3"): print("focus", want, fk, round(cum, 2), file=sys.stderr)
                            break
                    if chosen is not None or moved: continue
                    cyc += 1
                    if focused and cyc >= prm["focus_passes"]:   # nothing clicked for passes: back to the full loop, the evidence is kept
                        sc = m.scores_map(); merged = dict(full_scores); merged.update(sc)
                        t0 = t0 + cyc * per + prm["focus_gap"]; onset, _ = layout(keys, item)
                        m2 = cm.ClickModel(period, params); m2.inherit(m)
                        m2.restart({k: merged[k] for k in keys}, onset, t0, {k: m.params.win_diff_high for k in risky})
                        m = m2; per = period; cur = keys; focused = False; moved = True; at = t0
                    if t0 + cyc * per > 60 * period: chosen = "GIVEUP"
            if chosen == "GIVEUP": seconds += (lead + 60 * period) / 1000; continue
            seconds += (lead + at + say) / 1000; picks += 1
            if chosen == want: pass
            else: wrong += 1
            if chosen == "undo": m.undo()
            else: m.learn(chosen)
            prev = m
            # the effect of the choice on the text
            if chosen == "undo":
                if hist: written, prefix = hist.pop()
            elif chosen == "send": seconds += 3.0   # read back, one hold to cancel
            elif chosen == "space": hist.append((list(written), prefix)); written = written + [prefix]; prefix = ""
            elif chosen[0] == "w": hist.append((list(written), prefix)); written = written + [chosen[1]]; prefix = ""
            else: hist.append((list(written), prefix)); prefix += chosen[1]
            if os.environ.get('BENCH_DEBUG2') and not chosen == want: print('WRONG', t, written, repr(prefix), 'want', want, 'got', chosen, 'cyc', cyc, 'mu', round(statistics.mean(m.ys[:20]),3) if m.ys else None, 'sd', round(statistics.pstdev(m.ys[:20]),3) if len(m.ys)>2 else None, 'ks', round(m.ksigma,3), file=sys.stderr)
    return seconds, chars, picks, wrong, failed

_G = {}
def _session(args):
    name, seed = args
    return simulate_session(_G["sets"][name], _G["ctxs"], _G["bg"], _G["prm"], seed)

def run_loop(sets, ctxs, bg, prm, seeds, log):
    """mean and 95% interval over seeds of s/char and wrong picks per 100 chars, per text set."""
    _G.update(sets=sets, ctxs=ctxs, bg=bg, prm=prm); res = {}
    tasks = [(n, s) for n in sets for s in range(seeds)]
    with mp.get_context("fork").Pool() as pool: outs = pool.map(_session, tasks, chunksize=1)
    for n in sets:
        allrows = [o for (nn, _), o in zip(tasks, outs) if nn == n]; rows = [r for r in allrows if r[1] > 0]   # a person who finished no text has no s/char
        if not rows: res[n] = {"spc": float("nan"), "spc_ci": 0.0, "wrong": float("nan"), "wrong_ci": 0.0, "picks_per_char": float("nan"), "failed_texts": sum(r[4] for r in allrows), "seeds": seeds}; continue
        spc = [r[0] / r[1] for r in rows]; wr = [100.0 * r[3] / r[1] for r in rows]; pk = [r[2] / r[1] for r in rows]
        ci = lambda v: 1.96 * statistics.stdev(v) / math.sqrt(len(v)) if len(v) > 1 else 0.0
        res[n] = {"spc": statistics.mean(spc), "spc_ci": ci(spc), "wrong": statistics.mean(wr), "wrong_ci": ci(wr), "picks_per_char": statistics.mean(pk), "failed_texts": sum(r[4] for r in allrows), "seeds": seeds}
        log("loop %s %s %s" % (n, json.dumps(prm), json.dumps(res[n])))
    return res

DESIGNS = {"today": design_today, "fixed": design_fixed}

# ---------------------------------------------------------------- run
def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--all", action="store_true", help="all 500 phrases")
    ap.add_argument("--n", type=int, default=50); ap.add_argument("--seed", type=int, default=2003)
    ap.add_argument("--item-ms", type=int, default=350); ap.add_argument("--reaction-ms", type=int, default=400)
    ap.add_argument("--say-ms", type=int, default=800)
    ap.add_argument("--click-mean", type=float, default=180.0, help="ms a click comes after the item's onset")
    ap.add_argument("--click-sd", type=float, default=60.0, help="ms spread of the click")
    ap.add_argument("--miss", type=float, default=0.05, help="chance the person misses a pass of the item he wants")
    ap.add_argument("--false", type=float, default=0.02, help="chance of one stray click per pass of the loop")
    ap.add_argument("--false-per-item", action="store_true", help="--false is the chance per item and pass (about 0.6 stray clicks per loop at 0.02: nothing works); default: per pass of the whole loop")
    ap.add_argument("--letter-floor", type=float, default=1e-6, help="the least chance of a letter in LoopItems (Kotlin: 1e-6)")
    ap.add_argument("--smooth", type=float, default=0.0, help="e: letter mass = (1-e) word model + e letter bigram (0 = off, the old 1e-6 floor)")
    ap.add_argument("--focus", action="store_true", help="after a click that does not choose, play only the likely items (focus round)")
    ap.add_argument("--focus-ms", type=float, default=500.0); ap.add_argument("--focus-cov", type=float, default=0.95)
    ap.add_argument("--focus-max", type=int, default=6); ap.add_argument("--focus-min", type=int, default=2)
    ap.add_argument("--focus-passes", type=int, default=2); ap.add_argument("--focus-gap", type=float, default=400.0, help="ms from the click to the first sound of the focus round")
    ap.add_argument("--threshold", type=float, default=99.0, help="the model picks when its lead is ln THRESHOLD (Kotlin: 99)")
    ap.add_argument("--seeds", type=int, default=10, help="simulated persons (same seeds for every setting)")
    ap.add_argument("--profile-seed", action="store_true", help="the model starts from the person's mean and spread (Click test seed)")
    ap.add_argument("--sweep", action="store_true", help="the loop at sd 40/60/100 x threshold 99/19, on all 500 phrases")
    ap.add_argument("--verify-click", action="store_true", help="compare the Python ClickModel with the Kotlin dump and stop")
    ap.add_argument("--no-cache", action="store_true"); ap.add_argument("--verify", action="store_true")
    a = ap.parse_args()
    if a.verify_click: sys.exit(verify_click())
    if a.sweep: a.all = True
    t0 = time.time(); os.makedirs(LOGDIR, exist_ok=True)
    logpath = os.path.join(LOGDIR, "bench-%s.log" % datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
    lf = open(logpath, "w")
    def log(s): lf.write(s + "\n"); lf.flush()
    phrases = load_phrases(log)
    sample = phrases if a.all else random.Random(a.seed).sample(phrases, min(a.n, len(phrases)))
    prm = {"item_ms": a.item_ms, "reaction_ms": a.reaction_ms, "say_ms": a.say_ms}
    lm = LM()
    srcs = open(__file__).read() + open(os.path.join(HERE, "click_model.py")).read()
    key = hashlib.sha256(json.dumps([srcs, lm.model_hash, lm.vhash, OWNER, sample, prm, [NEXT, PICK, MENU]]).encode()).hexdigest()[:16]
    cpath = os.path.join(LOGDIR, "bench-cache.json")
    cache = json.load(open(cpath)) if os.path.exists(cpath) else {}
    port = verify(lm, log) if (a.verify or os.path.exists(KOTLIN_DUMP)) else "not checked"
    sets = {"owner": OWNER, "phrases%d" % len(sample): sample}
    contexts = []
    for texts in sets.values():
        for t in texts:
            ws = t.split()
            contexts += [tuple(ws[max(0, i - 8):i]) for i in range(len(ws))]
    table = None; bg = None
    def tables():
        nonlocal table, bg
        if table is None: table = lm_table(lm, contexts, log); bg = bigrams(lm)
        return table, bg
    if key in cache and not a.no_cache:
        res = cache[key]; log("cached result %s" % key)
    else:
        table, bg = tables()
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
    # the loop: simulated persons
    configs = [(sd, thr) for thr in (99.0, 19.0) for sd in (40.0, 60.0, 100.0)] if a.sweep else [(a.click_sd, a.threshold)]
    for sd, thr in configs:
        lp = dict(prm, click_mean=a.click_mean, click_sd=sd, miss=a.miss, false=a.false, false_per_item=a.false_per_item, letter_floor=a.letter_floor, threshold=thr, profile_seed=a.profile_seed, seed_offsets=None, smooth=a.smooth, focus=a.focus, focus_ms=a.focus_ms, focus_cov=a.focus_cov, focus_max=a.focus_max, focus_min=a.focus_min, focus_passes=a.focus_passes, focus_gap=a.focus_gap, focus_trace=False)
        lkey = hashlib.sha256(json.dumps([srcs, lm.model_hash, lm.vhash, OWNER, sample, lp, a.seeds, "loop"]).encode()).hexdigest()[:16]
        if lkey in cache and not a.no_cache: lres = cache[lkey]; log("cached loop %s" % lkey)
        else:
            t, b = tables(); lres = run_loop(sets, CtxMap(lm, t), b, lp, a.seeds, log)
            cache[lkey] = lres; json.dump(cache, open(cpath, "w"))
        parts = ["%s: %.2f s/char (+-%.2f), %.1f wrong picks/100 chars, %d texts given up" % (n, r["spc"], r["spc_ci"], r["wrong"], r["failed_texts"]) for n, r in lres.items()]
        print("loop  sd=%g ln%g mean=%g miss=%g false=%g seeds=%d | %s" % (sd, thr, a.click_mean, a.miss, a.false, a.seeds, " | ".join(parts)))
    print("lstm port: %s; run %.1f s; log %s" % (port, time.time() - t0, os.path.relpath(logpath, ROOT)))
    log("done in %.1f s" % (time.time() - t0))

class CtxMap(dict):
    """context tuple -> Ctx, built on first use; a context the table lacks (a wrong word was picked) is computed here."""
    def __init__(self, lm, table): super().__init__(); self.lm = lm; self.table = table
    def __missing__(self, c):
        lp = self.table.get(c)
        if lp is None: lp = self.lm.logprobs(list(c))
        self[c] = Ctx(self.lm, lp, set(c[-3:])); return self[c]

_ctxcache = {}
if __name__ == "__main__": main()

#!/usr/bin/env python3
"""Builds YOUR next-word model from WhatsApp exports: words you write, word pairs and triples, how you start messages, plus a general Spanish
word list so rare words can still be spelled. Runs only on this machine. The output holds your private word pairs: it never goes into git.
Usage: python3 -I tools/build_es_model.py CHAT_DIR_OR_FILES... [--general words_es.txt] [--text NOTES.txt] [--out ~/wa_words/words_es.txt] [--eval]
--eval builds on the first 80% of your messages and tests on the last 20%: how often the next word is in the top 2 / 4 / 6."""
import sys, os, re, math, argparse, collections, importlib.util, glob

here = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("chat_words", os.path.join(here, "chat_words.py"))
cw = importlib.util.module_from_spec(spec); spec.loader.exec_module(cw)

def sentences(msgs, me):
    out = []
    for s, t in msgs:
        if s != me: continue
        for part in re.split(r"[.!?\n]+", t):
            w = [x.lower() for x in cw.WORD.findall(part)]
            if w: out.append(w)
    return out

def counts(sents):
    uni, bi, tri = collections.Counter(), collections.Counter(), collections.Counter()
    for w in sents:
        t = ["<s>"] + w
        uni.update(w)
        bi.update(zip(t, t[1:]))
        tri.update(zip(t, t[1:], t[2:]))
    return uni, bi, tri

def merge(personal, general, w, gmin):
    """Your counts count `w` times each; general counts below `gmin` are dropped so the file stays small."""
    out = collections.Counter({k: c for k, c in general.items() if c >= gmin})
    for k, c in personal.items(): out[k] += c * w
    return out

def corpus_sentences(path):
    out = []
    for line in open(os.path.expanduser(path), encoding="utf-8"):
        for part in re.split(r"[.!?\n]+", line.rstrip("\n").split("\t")[-1]):
            w = [x.lower() for x in cw.WORD.findall(part)]
            if w: out.append(w)
    return out

def general(path, top=30000):
    out = {}
    for line in open(path, encoding="utf-8"):
        w, _, z = line.partition("\t")
        if w and cw.WORD.fullmatch(w) and len(out) < top:
            try: out[w] = 10 ** float(z)
            except ValueError: pass
    return out

VOCAB = 40000

def write(path, uni, bi, tri, gen, mincount):
    total_u = sum(uni.values()); total_g = sum(gen.values())
    scale = 0.25 * total_u / total_g              # the general list carries 25% of the weight, so your own words win
    words = {w: uni.get(w, 0) + gen.get(w, 0) * scale for w in set(uni) | set(gen)}
    keep = dict(sorted(words.items(), key=lambda x: -x[1])[:VOCAB])   # a smaller word list keeps the phone fast
    with open(path, "w", encoding="utf-8") as f:
        for w, c in keep.items(): f.write(f"U\t{w}\t{c:.3f}\n")
        for (a, b), c in bi.items():
            if c >= mincount and b in keep: f.write(f"B\t{a}\t{b}\t{c}\n")
        for (a, b, d), c in tri.items():
            if c >= mincount and d in keep: f.write(f"T\t{a}\t{b}\t{d}\t{c}\n")
    return len(keep)

def evaluate(sents_train, sents_test, gen, gcounts=None, w=3, gmin=3):
    uni, bi, tri = counts(sents_train)
    if gcounts: uni, bi, tri = (merge(a, b, w, gmin if i else 1) for i, (a, b) in enumerate(zip((uni, bi, tri), gcounts)))
    total_g = sum(gen.values()); scale = 0.25 * sum(uni.values()) / total_g
    vocab = sorted(set(uni) | set(gen), key=lambda w: -(uni.get(w, 0) + gen.get(w, 0) * scale))
    uw = {w: uni.get(w, 0) + gen.get(w, 0) * scale for w in vocab}
    bctx, tctx = collections.defaultdict(dict), collections.defaultdict(dict)
    for (a, b), c in bi.items():
        if c >= 2: bctx[a][b] = c
    for (a, b, d), c in tri.items():
        if c >= 2: tctx[(a, b)][d] = c
    top_uni = vocab[:6]
    hit = collections.Counter(); n = 0; hit_u = collections.Counter(); inv = set(vocab)
    for w in sents_test:
        t = ["<s>"] + w
        for i in range(1, len(t)):
            if t[i] not in inv: continue
            n += 1
            sc = collections.defaultdict(float)
            tc = tctx.get((t[i - 2], t[i - 1])) if i >= 2 else tctx.get(("<s>", t[i - 1])) if False else (tctx.get((t[i - 2], t[i - 1])) if i >= 2 else None)
            bc = bctx.get(t[i - 1])
            wt, wb, wu = (0.6 if tc else 0), (0.3 if bc else 0), 0.1
            z = wt + wb + wu
            if tc: s_ = sum(tc.values()); [sc.__setitem__(k, sc[k] + wt / z * v / s_) for k, v in tc.items()]
            if bc: s_ = sum(bc.values()); [sc.__setitem__(k, sc[k] + wb / z * v / s_) for k, v in bc.items()]
            rank = sorted(sc, key=lambda k: -sc[k])[:6]
            rank += [x for x in top_uni if x not in rank]
            for k in (2, 4, 6):
                if t[i] in rank[:k]: hit[k] += 1
                if t[i] in top_uni[:k]: hit_u[k] += 1
    print(f"test words: {n}")
    for k in (2, 4, 6): print(f"  next word in top {k}: your model {100*hit[k]/n:.0f}%   (most common words only: {100*hit_u[k]/n:.0f}%)")

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("paths", nargs="+"); ap.add_argument("--me"); ap.add_argument("--text", action="append", default=[], help="plain text file of your own writing (Keep notes, tasks): one note per line")
    ap.add_argument("--general", default=os.path.expanduser("~/code/projects/hardware/health/utter/app/Sources/TypingCore/Resources/words_es.txt"))
    ap.add_argument("--out", default=os.path.expanduser("~/wa_words/words_es.txt")); ap.add_argument("--eval", action="store_true"); ap.add_argument("--min", type=int, default=2)
    ap.add_argument("--corpus", help="general Spanish sentences (tsv, text in the last column), e.g. Tatoeba; makes the model general-purpose")
    ap.add_argument("--weight", type=int, default=3, help="how many times your own counts count against the corpus")
    ap.add_argument("--gmin", type=int, default=3, help="drop corpus pairs/triples seen fewer times than this")
    a = ap.parse_args()
    msgs = cw.read(a.paths)
    me = a.me or collections.Counter(s for s, _ in msgs).most_common(1)[0][0]
    sents = sentences(msgs, me); gen = general(a.general)
    for tf in a.text:
        sents += sentences([(me, l) for l in open(os.path.expanduser(tf), encoding="utf-8").read().split("\n")], me)
    print(f"{len(sents)} of your sentences, {len(gen)} general words")
    gc = counts(corpus_sentences(a.corpus)) if a.corpus else None
    if gc: print(f"{len(gc[0])} corpus words, {len(gc[1])} pairs, {len(gc[2])} triples")
    if a.eval:
        cut = int(len(sents) * 0.8); evaluate(sents[:cut], sents[cut:], gen, gc, a.weight, a.gmin); return
    os.makedirs(os.path.dirname(a.out), exist_ok=True)
    uni, bi, tri = counts(sents)
    if gc: uni, bi, tri = (merge(x, y, a.weight, a.gmin if i else 1) for i, (x, y) in enumerate(zip((uni, bi, tri), gc)))
    n = write(a.out, uni, bi, tri, gen, a.min)
    print(f"wrote {a.out}: {n} words, {os.path.getsize(a.out)/1e6:.2f} MB")

if __name__ == "__main__":
    main()

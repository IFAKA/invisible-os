#!/usr/bin/env python3
"""Seconds of listening to write one text with one button, for 3 methods (see sim/README in the docstring below).
A: your own past texts only.  B: A + 3 local-LLM sentences (replies only).  C: word by word (next-word guesses), spell on a miss.
Usage: python3 -I sim/keyboard_sim.py [--gen gemma3:1b] [--judge gemma3:4b]   Needs ollama running.
The corpus (sim/corpus.jsonl) is invented: rates are illustrative until it is replaced by the user's real texts."""
import json, re, sys, urllib.request, collections, statistics, argparse

SCAN = 1.3     # seconds to hear one option (same as the app's scan step)
SPELL = 8.0    # assumed seconds to spell one word the model missed
K = 3          # options per layer before moving on
DEC = 1.2      # method D: seconds for one stereo decision (hear two words at once, click = left, hold = right)
SPELL_D = 4.0  # method D: spelling a missed word by halving the alphabet left/right (about 4 decisions)

def llm(model, prompt, n=120):
    req = urllib.request.Request("http://localhost:11434/api/generate", json.dumps(
        {"model": model, "prompt": prompt, "stream": False, "options": {"temperature": 0, "num_predict": n}}).encode())
    return json.load(urllib.request.urlopen(req, timeout=120))["response"].strip()

norm = lambda s: re.sub(r"[^\w ]", "", s.lower()).strip()
words = lambda s: s.split()

def memory(history, ctx, kind, lang):
    c = collections.Counter(h["text"] for h in history if h["kind"] == kind and h["lang"] == lang)
    cw = set(norm(ctx).split())
    score = lambda t: (c[t], len(cw & set(norm(t).split())))
    return sorted(c, key=score, reverse=True)

def accept(judge, target, cand, strict_only):
    if norm(target) == norm(cand): return True
    if strict_only: return False
    a = llm(judge, f'Do these two messages say the same thing, so one can replace the other? Answer only yes or no.\nA: {target}\nB: {cand}', 3)
    return a.lower().startswith("yes")

def gen_sentences(gen, ctx, lang):
    p = f'Message received: "{ctx}"\nWrite 3 short different replies in {"Spanish" if lang=="es" else "English"}, one per line, no numbering, no quotes.'
    return [l.strip("-• \"") for l in llm(gen, p).splitlines() if l.strip()][:3]

def next_words(gen, ctx, prefix, lang):
    """Top 4 next words from the model's token probabilities (raw completion, so it just continues the text)."""
    head = (f'Received: "{ctx}"\nMy short reply:' if ctx else 'My to-do list:\n- Pay bills\n- Call the doctor\n-') + (" " + prefix if prefix else "")
    req = urllib.request.Request("http://localhost:11434/api/generate", json.dumps({"model": gen, "prompt": head, "stream": False, "raw": True,
        "logprobs": True, "top_logprobs": 20, "options": {"num_predict": 1, "temperature": 0}}).encode())
    top = json.load(urllib.request.urlopen(req, timeout=120))["logprobs"][0]["top_logprobs"]
    out = []
    for t in top:
        w = norm(t["token"])
        if t["token"].startswith(" ") and w and w not in out: out.append(w)
    return out[:4]

def method_c(gen, ctx, target, lang):
    t = 0.0; prefix = ""
    for w in words(target):
        guess = next_words(gen, ctx, prefix, lang)
        t += SCAN                                  # "Done" is heard first at every step
        nw = norm(w)
        hit = guess.index(nw) if nw in guess else None
        if hit is not None: t += SCAN * (hit + 1)
        else: t += SCAN * 4 + SPELL
        prefix = (prefix + " " + w).strip()
    return t + SCAN                                # final Done

def method_d(gen, ctx, target, lang, spell):
    """Stereo pair: top 2 words (left/right ear) per decision, 2 clicks = next pair; 2 pairs, then spell."""
    t = 0.0; prefix = ""
    for w in words(target):
        guess = next_words(gen, ctx, prefix, lang)[:4]
        nw = norm(w)
        if nw in guess: t += DEC * (guess.index(nw) // 2 + 1)
        else: t += DEC * 2 + spell
        prefix = (prefix + " " + w).strip()
    return t + DEC                                 # final confirm

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("--gen", default="gemma3:1b"); ap.add_argument("--judge", default="gemma3:4b")
    ap.add_argument("--strict", action="store_true"); a = ap.parse_args()
    corpus = [json.loads(l) for l in open("sim/corpus.jsonl")]
    rows = collections.defaultdict(lambda: collections.defaultdict(list))
    for i, it in enumerate(corpus):
        hist = corpus[:i] + corpus[i + 1:]                       # leave one out: memory = all the other texts
        mem = memory(hist, it["ctx"], it["kind"], it["lang"])[:K]
        k = it["kind"]
        # A: memory top K, else spell the whole thing as words (method C cost) after hearing K options
        hitA = next((j for j, m in enumerate(mem) if norm(m) == norm(it["text"])), None)
        c_cost = method_c(a.gen, it["ctx"], it["text"], it["lang"])
        costA = SCAN * (hitA + 1) if hitA is not None else SCAN * len(mem) + c_cost
        rows[k]["A memory only"].append(costA)
        if k == "reply":
            ai = gen_sentences(a.gen, it["ctx"], it["lang"])
            cands = mem + ai
            hitB = next((j for j, m in enumerate(cands) if accept(a.judge, it["text"], m, a.strict or j < len(mem))), None)
            costB = SCAN * (hitB + 1) if hitB is not None else SCAN * len(cands) + c_cost
            rows[k]["B memory + AI sentences"].append(costB)
            rows[k]["  (B hit rate)"].append(1.0 if hitB is not None else 0.0)
        rows[k]["C word by word only"].append(c_cost)
        rows[k]["D stereo pair (spell 8 s)"].append(method_d(a.gen, it["ctx"], it["text"], it["lang"], SPELL))
        rows[k]["D stereo pair (stereo spell)"].append(method_d(a.gen, it["ctx"], it["text"], it["lang"], SPELL_D))
        print(f"{i+1}/{len(corpus)}", end="\r", file=sys.stderr)
    for k, d in rows.items():
        print(f"\n{k.upper()}  ({sum(1 for c in corpus if c['kind']==k)} texts, mean seconds of listening per text)")
        for name, v in d.items(): print(f"  {name:28s} {statistics.mean(v):6.1f}")

main()

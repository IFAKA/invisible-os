#!/usr/bin/env python3
"""Eval of the Write "complete" AI job with the real local Ollama. One line out:
  model=gemma3:4b prompt=new n=21 hit@1=62% hit@5=81% ms=1900 junk=40%
hit@1/hit@5: an acceptable answer (bridge/tests/complete_cases.tsv) is the first / among the first 5 items that pass the guard and the fit rule.
junk: share of the raw items the filter drops. Answers are cached by prompt hash in app/logs/complete_eval_cache.json (--fresh = ignore).
Usage: tools/complete_eval.py [--model gemma3:4b] [--prompt new|old|both] [--fresh]"""
import argparse, hashlib, json, os, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "bridge"))
import aitext, story  # noqa: E402

CACHE = os.path.join(ROOT, "app", "logs", "complete_eval_cache.json")


def old_prompt(done, letters, lang, context=""):
    """The first prompt (before the done/letters split): it was given the typed text as one string."""
    typed = (done + " " + letters).strip()
    return ("Someone is typing a message on a phone with one button, so every letter is slow. So far they typed: \"%s\"\n"
            "The typed letters are EITHER the start of the word they mean (\"hel\" = \"help\") OR the first letters of the next words (\"hay\" = \"how are you\"). "
            "Write up to 5 whole texts they most likely mean, most likely first, in English, as JSON: {\"completions\": [...]}. "
            "Each text starts with the words already typed, is natural and short, at most 12 words. "
            "Use only facts, numbers and names that are in the earlier messages. Never invent.\nOutput only the JSON." % typed[:200])


def old_fits(text, done, letters, whole_word=False):
    """The first fit rule: the typed fragment may be copied as a word."""
    tw = (done + " " + letters).lower().split(); ow = text.lower().split()
    if not tw or not ow:
        return False
    if len(tw) <= len(ow) and all(a == b for a, b in zip(tw[:-1], ow)) and ow[len(tw) - 1].startswith(tw[-1]):
        return True
    ls = "".join(c for w in tw for c in w if c.isalpha())
    return len(ls) >= 2 and "".join(w[0] for w in ow if w[0].isalpha()).startswith(ls)


def cases():
    out = []
    for line in open(os.path.join(ROOT, "bridge", "tests", "complete_cases.tsv"), encoding="utf-8"):
        if line.startswith("#") or not line.strip("\n"):
            continue
        d, l, a = (line.rstrip("\n").split("\t") + ["", "", ""])[:3]
        out.append((d, l, [x.strip().lower() for x in a.split("|") if x.strip()]))
    return out


def run(model_name, which, fresh):
    cache = {} if fresh or not os.path.exists(CACHE) else json.load(open(CACHE))
    call = story.ollama_model(model=model_name, timeout=30)
    prompt_fn, fits = (old_prompt, old_fits) if which == "old" else (aitext.build_complete_prompt, aitext.completion_fits)
    h1 = h5 = raw_n = kept_n = 0
    ms = []
    cs = cases()
    for d, l, ok in cs:
        p = prompt_fn(d, l, "en", "")
        key = hashlib.sha1((model_name + "\x00" + p).encode()).hexdigest()
        if key not in cache:
            t0 = time.time()
            try:
                r = call(p)
            except Exception as e:
                r = ""
            cache[key] = {"raw": r, "ms": round((time.time() - t0) * 1000)}
            os.makedirs(os.path.dirname(CACHE), exist_ok=True)
            json.dump(cache, open(CACHE, "w"))
        e = cache[key]; ms.append(e["ms"])
        items = aitext.parse_completions(e["raw"])
        kept = [t for t in aitext.check_items(items, d + " " + l, "", aitext.COMPLETE_WORDS, n=5) if fits(t, d, l)]
        raw_n += len(items); kept_n += len(kept)
        low = [t.lower() for t in kept]
        h1 += bool(low[:1] and low[0] in ok); h5 += any(t in ok for t in low[:5])
    n = len(cs)
    return "model=%s prompt=%s n=%d hit@1=%d%% hit@5=%d%% ms=%d junk=%d%%" % (
        model_name, which, n, round(100 * h1 / n), round(100 * h5 / n), sum(ms) / n, round(100 * (raw_n - kept_n) / max(raw_n, 1)))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="gemma3:4b"); ap.add_argument("--prompt", default="new", choices=["new", "old", "both"]); ap.add_argument("--fresh", action="store_true")
    a = ap.parse_args()
    for w in (["old", "new"] if a.prompt == "both" else [a.prompt]):
        print(run(a.model, w, a.fresh), flush=True)

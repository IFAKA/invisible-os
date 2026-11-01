"""Replay of the spoken push line over the owner's real answered messages (DESIGN-LAYER.md C8). Deterministic: code fallback only, no model.
Needs /tmp/wf/pairs.jsonl (mkdir -p /tmp/wf && python3 bridge/research/extract.py).
Usage: python3 bridge/research/line_replay.py [pairs.jsonl]
Samples: Claude messages the owner answered with <= 12 words. The ask of a message = the quoted option the reply used, else the
status block's need line, else the last sentence ending with "?". A line KEEPS the ask when the last 6 words of the ask (the part a cut
loses first) or the whole quoted option are in the line. Methods: old = shorten(room text, 12) as layer.room_line did;
new = layer.fit_line with the same inputs a room has without a model."""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))
import layer  # noqa: E402
from rooms import need_line, last_sentences, strip_markdown, one_line  # noqa: E402
from story import first_sentence  # noqa: E402

norm = lambda t: re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", str(t).lower())).strip()
QUOTE = re.compile(r'["“]([^"”]{1,40})["”]')


def room_inputs(msg):
    """(need, story) as a model-less room has them: need = status block line, else the last two sentences."""
    found, need, _opts = need_line(msg)
    if not found:
        need = last_sentences(msg)
    text = strip_markdown(msg)
    if need and not need.endswith("...") and len(need.split()) <= layer.NEED_WORDS:
        story = need
    elif text.strip():
        story = layer.fit_text(text)
    else:
        story = need or first_sentence(text)
    return need, story


def old_line(project, msg):
    need, _ = room_inputs(msg)
    return layer.shorten("%s: %s" % (project, need or first_sentence(strip_markdown(msg))), 12)


def new_line(project, msg):
    need, story = room_inputs(msg)
    return layer.fit_line(project, need=need, story=story)


def ask_of(msg, reply):
    """The text the line must keep, or None when the message has no ask."""
    found, need, opts = need_line(msg)
    r = norm(reply)
    for o in opts:
        if r and (r == norm(o) or r.startswith(norm(o))):
            return ("option", norm(o))
    if found and need:
        return ("tail", norm(need))
    sents = re.split(r"(?<=[.!?])\s+|\n+", strip_markdown(msg))
    q = [s for s in sents if s.strip().endswith("?")]
    return ("tail", norm(q[-1])) if q else None


def kept(line, ask):
    kind, text = ask
    body = norm(line)
    if kind == "option":
        return text in body
    return " ".join(text.split()[-6:]) in body


def main(path):
    rows = [json.loads(l) for l in open(path)]
    sample = []
    for r in rows:
        if r["prev"].strip() and 1 <= len(r["u"].split()) <= 12 and "[Image" not in r["u"] and "interrupted" not in r["u"]:
            a = ask_of(r["prev"], r["u"])
            if a:
                sample.append((r, a))
    for name, fn in (("old shorten(12)", old_line), ("new fit_line", new_line)):
        lines = [(fn(r["p"], r["prev"]), a) for r, a in sample]
        n = len(lines)
        keep = sum(kept(l, a) for l, a in lines)
        short = sum(len(l.split()) - 1 <= 16 for l, _ in lines)   # the project name is one word outside the budget
        dots = sum("..." in l for l, _ in lines)
        words = sum(len(l.split()) - 1 for l, _ in lines) / n
        print("%-16s n=%d keeps_ask=%.1f%% le16_words=%.1f%% dots=%.1f%% mean_words=%.1f" % (name, n, 100 * keep / n, 100 * short / n, 100 * dots / n, words))


def question_test(path, n=40, seed=7):
    """Optional: does the line carry what the message carries? gemma3:4b answers "what happened, what does it want from the user" from the
    full message and from the line; prints the mean cosine similarity (all-minilm) of the two answers. 40 messages, fixed seed.
    Skips cleanly (one line) when Ollama or the models are not there."""
    import math
    import random
    import urllib.request

    def post(p, body):
        req = urllib.request.Request("http://127.0.0.1:11434" + p, data=json.dumps(body).encode())
        return json.load(urllib.request.urlopen(req, timeout=60))
    try:
        post("/api/embed", {"model": "all-minilm", "input": "x"})
        post("/api/generate", {"model": "gemma3:4b", "prompt": "hi", "stream": False, "options": {"num_predict": 1}})
    except Exception as e:   # model down: stop at once
        print("question test: skipped (%s)" % type(e).__name__)
        return
    rows = [json.loads(l) for l in open(path)]
    rows = [r for r in rows if len(r["prev"].split()) > 30]
    random.seed(seed)
    emb = lambda t: post("/api/embed", {"model": "all-minilm", "input": t})["embeddings"][0]
    cos = lambda a, b: sum(x * y for x, y in zip(a, b)) / math.sqrt(sum(x * x for x in a) * sum(y * y for y in b))
    ask = lambda t: post("/api/generate", {"model": "gemma3:4b", "stream": False, "options": {"temperature": 0, "num_predict": 60},
                                           "prompt": "Message from an AI assistant to its user:\n---\n%s\n---\nIn one sentence: what happened, and what does it want from the user?" % t[-3000:]})["response"]
    for name, fn in (("old shorten(12)", old_line), ("new fit_line", new_line)):
        sims = []
        for r in random.Random(seed).sample(rows, n):
            sims.append(cos(emb(ask(r["prev"])), emb(ask(fn(r["p"], r["prev"])))))
        print("question test %-16s n=%d mean similarity of answers (line vs full message) = %.2f" % (name, n, sum(sims) / n))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 and not sys.argv[1].startswith("-") else "/tmp/wf/pairs.jsonl")
    question_test("/tmp/wf/pairs.jsonl")

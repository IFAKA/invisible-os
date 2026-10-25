#!/usr/bin/env python3
"""Your most common words, from WhatsApp "Export chat" .txt files (without media). Runs only on this machine; nothing is sent anywhere.
Usage: python3 -I tools/chat_words.py FILE_OR_DIR [...] [--me "Your name"] [--top 40] [--out sim/my_words.json]
Only YOUR messages count. --me defaults to the sender who wrote the most. Output: top words, first words of a message, word pairs."""
import re, sys, json, glob, os, argparse, collections

LINE = re.compile(r"^\[?(\d{1,4}[/.\-]\d{1,2}[/.\-]\d{1,4}),? \d{1,2}[:.]\d{2}(?:[:.]\d{2})?(?:\s?[APap]\.?[Mm]\.?)?\]?\s*-?\s*([^:]{1,60}?): (.*)$")
SKIP = ("<media omitted>", "<multimedia omitido>", "this message was deleted", "se eliminó este mensaje", "null", "<this message was edited>")
WORD = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)?", re.U)

def read(paths):
    files = []
    for p in paths: files += sorted(glob.glob(os.path.join(p, "*.txt"))) if os.path.isdir(p) else [p]
    msgs = []   # (sender, text)
    for f in files:
        cur = None
        for line in open(f, encoding="utf-8", errors="ignore"):
            line = line.rstrip("\n").replace("‎", "")
            m = LINE.match(line)
            if m:
                if cur: msgs.append(cur)
                cur = [m.group(2).strip(), m.group(3)]
            elif cur: cur[1] += " " + line
        if cur: msgs.append(cur)
    return [(s, t) for s, t in msgs if t.strip().lower() not in SKIP and not t.startswith("http")]

def main():
    ap = argparse.ArgumentParser(); ap.add_argument("paths", nargs="+"); ap.add_argument("--me"); ap.add_argument("--top", type=int, default=40); ap.add_argument("--out")
    a = ap.parse_args()
    msgs = read(a.paths)
    if not msgs: sys.exit("no messages found: use WhatsApp > chat > more > Export chat > Without media")
    me = a.me or collections.Counter(s for s, _ in msgs).most_common(1)[0][0]
    mine = [t for s, t in msgs if s == me]
    words, first, pairs = collections.Counter(), collections.Counter(), collections.Counter()
    for t in mine:
        w = [x.lower() for x in WORD.findall(t)]
        words.update(w)
        if w: first[w[0]] += 1
        pairs.update(zip(w, w[1:]))
    print(f"{len(msgs)} messages, {len(mine)} are from '{me}', {sum(words.values())} words, {len(words)} different")
    show = lambda c, n=a.top: ", ".join(f"{k if isinstance(k, str) else ' '.join(k)} {v}" for k, v in c.most_common(n))
    print("\nMOST COMMON WORDS:\n" + show(words)); print("\nFIRST WORD OF A MESSAGE:\n" + show(first, 25)); print("\nWORD PAIRS:\n" + show(pairs, 25))
    cover = 0; total = sum(words.values())
    for i, (_, v) in enumerate(words.most_common(), 1):
        cover += v
        if i in (100, 500, 1000, 3000, 10000): print(f"top {i} words cover {100*cover/total:.0f}% of what you write")
    if a.out: json.dump({"me": me, "words": words.most_common(5000), "first": first.most_common(500), "pairs": [[" ".join(k), v] for k, v in pairs.most_common(3000)]}, open(a.out, "w"), ensure_ascii=False)

main()

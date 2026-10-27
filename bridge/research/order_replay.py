"""Replay check for order learning (DESIGN-ROOMS.md step 6). Run: python3 research/order_replay.py [pairs.jsonl] [--learn X] [--half-life D]

Each line of pairs.jsonl is one user reply (fields: p project, t epoch, prev = Claude's message before, u = the reply).
The reply is mapped to an action id with the same intent list as coverage.py. Per project (= room), replies are replayed in time order:
the list is ordered by rooms.learned_order() from earlier replies only (recency weighted), and we check if the real action is in the top 3.
Compared: the fixed order against the learned order. Replies that match a quoted option of the message are skipped (options come first in both).
"""
import collections
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import rooms  # noqa: E402

norm = lambda t: re.sub(r"\s+", " ", re.sub(r"[^a-z0-9 ]", " ", t.lower())).strip()
INTENT = {}
for words, action in (
    (["go", "ok", "do it", "continue", "go ahead", "ok do it", "do all", "all", "both", "do what you recommend", "do what you recomend"], "go"),
    (["yes"], "yes"),
    (["commit", "love it", "love it commit", "commit all"], "commit"),
    (["push", "push changes", "commit and push"], "push"),
    (["compact"], "compact"),
    (["eh", "wdym", "i dont get it"], "explain"),
    (["what do you recommend", "what do yo recomend"], "recommend"),
):
    for w in words:
        INTENT[w] = action
# not an action of the list: done, no, still, so we are done, can i close this chat, we are done  -> skipped


def load(path):
    rows = [json.loads(l) for l in open(path)]
    return sorted(rows, key=lambda r: r["t"])


def replay(rows, learn, half_life, top=3):
    history = collections.defaultdict(list)   # project -> [(t, action)]
    base = rooms.CLAUDE_ACTIONS
    n = fixed_hit = learned_hit = fixed1 = learned1 = 0
    for r in rows:
        u = norm(r["u"])
        action = INTENT.get(u)
        if action is None or not r.get("prev"):
            continue
        m = re.search(r"needs (me|you):?\**\s*(.*)", r["prev"], re.I)
        opts = {norm(x) for x in re.findall(r'["“]([^"”]{1,40})["”]', m.group(2))} if m else set()
        if u in opts:
            continue
        picks = rooms.weighted_picks(history[r["p"]], r["t"], half_life)
        order = rooms.learned_order(base, picks, learn)
        n += 1
        fixed_hit += action in base[:top]
        learned_hit += action in order[:top]
        fixed1 += action == base[0]
        learned1 += action == order[0]
        history[r["p"]].append((r["t"], action))
    return n, fixed_hit, learned_hit, fixed1, learned1


def main():
    args = sys.argv[1:]
    learn, half = rooms.LEARN, rooms.HALF_LIFE_DAYS
    if "--learn" in args:
        i = args.index("--learn"); learn = float(args[i + 1]); del args[i:i + 2]
    if "--half-life" in args:
        i = args.index("--half-life"); half = float(args[i + 1]); del args[i:i + 2]
    path = args[0] if args else "/tmp/wf/pairs.jsonl"
    if not os.path.exists(path):
        print("no data file:", path)
        return 1
    n, fh, lh, f1, l1 = replay(load(path), learn, half)
    print("learn=%g half-life=%gd; replies mapped to a fixed action: %d" % (learn, half, n))
    print("top-3 hit rate  fixed order: %d/%d = %.1f%%   learned order: %d/%d = %.1f%%" % (fh, n, 100 * fh / n, lh, n, 100 * lh / n))
    print("top-1 hit rate  fixed order: %d/%d = %.1f%%   learned order: %d/%d = %.1f%%" % (f1, n, 100 * f1 / n, l1, n, 100 * l1 / n))
    return 0


if __name__ == "__main__":
    sys.exit(main())

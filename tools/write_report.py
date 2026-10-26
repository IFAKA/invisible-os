#!/usr/bin/env python3
"""Report on how Write is used. Pulls files/write_usage.tsv from the phone (or reads a path) and prints the cost per word.
Usage: tools/write_report.py [file.tsv]   (no argument = pull from the phone to app/logs/write_usage.tsv)"""
import subprocess, sys, os, collections
root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
if len(sys.argv) > 1: path = sys.argv[1]
else:
    path = os.path.join(root, "app/logs/write_usage.tsv"); os.makedirs(os.path.dirname(path), exist_ok=True)
    out = subprocess.run(["adb", "exec-out", "run-as", "com.faka.invisible", "cat", "files/write_usage.tsv"], capture_output=True, text=True).stdout
    open(path, "w").write(out)
rows = [l.rstrip("\n").split("\t") for l in open(path) if l.strip()]
if not rows: sys.exit("no data yet: use Write first")
c = collections.Counter(); secs = []; presses = []; pageOf = collections.Counter(); modes = collections.Counter()
lastpair = None; n_since = 0; t_since = None; slow = collections.Counter(); skipped = collections.Counter()
for r in rows:
    t = int(r[0]); e = r[1]; f = r[2:]; c[e] += 1
    if e == "pair":
        lastpair = (t, f)
        if t_since is None: t_since = t
    elif e == "pick":
        side, item, page, mode = f[0], f[1], int(f[2]), f[3]; modes[mode] += 1; c["pick" + side] += 1; pageOf[page] += 1
        n_since += 1
        if lastpair: slow[round((t - lastpair[0]) / 1000)] += 1
        if mode == "words": presses.append(n_since); secs.append((t - t_since) / 1000 if t_since else 0); n_since = 0; t_since = None
    elif e == "next":
        n_since += 1
        if lastpair: skipped[(lastpair[1][0], lastpair[1][1])] += 1
    elif e in ("menu", "menupick"): n_since += 1
def avg(x): return sum(x) / len(x) if x else 0
print(f"events {len(rows)}, sessions {c['end']}, words picked {modes['words']}, letters picked {modes['letters']}, phrases {modes['phrases']}")
print(f"per word: {avg(presses):.2f} actions, {avg(secs):.1f} s   (lower is better)")
tot = c["pickL"] + c["pickR"]
print(f"left {c['pickL']} / right {c['pickR']}  (right share {100*c['pickR']/max(tot,1):.0f}%; if over 50% the pair order is wrong)")
print("picks by page:", dict(sorted(pageOf.items())), " next-pair presses:", c["next"], " menu opens:", c["menu"])
print("seconds from pair to pick:", dict(sorted(slow.items())[:12]))
print("most skipped pairs:", skipped.most_common(8))

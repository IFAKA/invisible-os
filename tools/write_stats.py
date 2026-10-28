#!/usr/bin/env python3
"""Write keyboard cost from files/write_usage.tsv: one line per session, then totals.
Usage: tools/write_stats.py [--pull] [--events]   (copy kept in app/logs/write/write_usage.tsv)"""
import subprocess, sys, os, collections, datetime

HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "..", "app", "logs", "write", "write_usage.tsv")

if "--pull" in sys.argv:
    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    data = subprocess.run(["adb", "exec-out", "run-as", "com.faka.invisible", "cat", "files/write_usage.tsv"], capture_output=True).stdout
    open(LOG, "wb").write(data)

rows = [l.rstrip("\n").split("\t") for l in open(LOG) if l.strip()]
sessions, cur = [], []
for r in rows:
    if cur and int(r[0]) - int(cur[-1][0]) > 5 * 60_000:
        sessions.append(cur); cur = []
    cur.append(r)
    if r[1] == "end":
        sessions.append(cur); cur = []
if cur: sessions.append(cur)

tot = collections.Counter()
print("start            secs  end    chars  s/char  pairs nexts backs menus picks(W/L/P)  menupicks")
for s in sessions:
    t0, t1 = int(s[0][0]), int(s[-1][0])
    c = collections.Counter(r[1] for r in s)
    modes = collections.Counter(r[5] for r in s if r[1] == "pick" and len(r) > 5)
    mp = collections.Counter(r[2] for r in s if r[1] == "menupick" and len(r) > 2)
    end = next((r for r in s if r[1] == "end"), None)
    chars = int(end[4]) if end and len(end) > 4 else 0
    secs = (t1 - t0) / 1000
    print(f"{datetime.datetime.fromtimestamp(t0/1000):%m-%d %H:%M:%S} {secs:6.0f}  {(end[2] if end else 'open'):5} {chars:6} {(secs/chars if chars else 0):6.1f}  {c['pair']:5} {c['next']:5} {c['back']:5} {c['menu']:5} "
          f"{modes['words']}/{modes['letters']}/{modes['phrases']:<10} {dict(mp)}")
    tot.update(c); tot["secs"] += secs; tot["chars"] += chars
    tot.update({"pick_" + k: v for k, v in modes.items()})
print(f"TOTAL {len(sessions)} sessions, {tot['secs']:.0f} s, {tot['chars']} chars sent, {tot['pick']} picks, {tot['next']} nexts, {tot['menu']} menus, {tot['back']} backs; "
      f"nexts per pick {tot['next']/max(tot['pick'],1):.1f}; s per sent char {tot['secs']/max(tot['chars'],1):.1f}")

if "--events" in sys.argv:
    for s in sessions:
        print("----")
        t0 = int(s[0][0])
        for r in s:
            print(f"{(int(r[0])-t0)/1000:7.1f} " + " ".join(r[1:]))

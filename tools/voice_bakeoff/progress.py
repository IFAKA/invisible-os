#!/usr/bin/env python3
"""Which sentences have a clip. progress.py [watch|list]
   ✓✓ = on laptop and on phone, ✓ = on laptop only, · = not made yet. watch = live screen; list = every sentence."""
import os, sys, time, shutil
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from manifest import all_items, key
D = os.environ.get("CLIPS", "~/code/projects/hardware/.lanes/invisible-controller/voice-clips/app/os/src/main/assets/voice")
G, Y, R, B, X = "\033[32m", "\033[33m", "\033[2m", "\033[1m", "\033[0m"
def group(l, t): return "Spanish" if l == "es" else "Battery" if t.startswith("Battery ") else "Timer" if t.startswith("Timer ") and "minutes" in t else "Menu and sentences"
def state():
    mine = {f[:-4] for f in os.listdir(D) if f.endswith(".wav")} if os.path.isdir(D) else set()
    ph = set(open("/tmp/clips_on_phone.txt").read().split()) if os.path.exists("/tmp/clips_on_phone.txt") else set()
    ph = {f[:-4] for f in ph}
    rows = [(l, t, "2" if key(l, t) in ph and key(l, t) in mine else "1" if key(l, t) in mine else "0") for l, t in all_items()]
    return rows, os.path.getmtime
def workers():
    import json, glob
    out = []
    for f in sorted(glob.glob("/tmp/clip_status_*.json")):
        try: w = json.load(open(f))
        except Exception: continue
        age = time.time() - w["t"]; run = time.time() - w["started"]
        if w["state"] == "saved" and age > 90: txt = R + f"worker {w['shard']}: idle/stopped" + X
        elif w["state"] == "saved": txt = f"worker {w['shard']}: {G}saved{X} {w['text'][:34]!r}, heard {w.get('heard','')[:30]!r}"
        else:
            best = "" if w.get("best") is None else f"  best error so far {w['best']*100:.0f}%"
            txt = f"worker {w['shard']}: {Y}{w['state']}{X} {w['text'][:34]!r}  try {w['attempt']}/4  {run:3.0f}s{best}"
        out.append(txt)
    return out
def speed(rows):
    import glob
    now = time.time(); recent = [f for f in glob.glob(D + "/*.wav") if now - os.path.getmtime(f) < 240]
    rate = len(recent) / 4.0   # clips per minute over the last 4 minutes
    miss = sum(r[2] == "0" for r in rows)
    return rate, (f"{miss / rate:.0f} min left" if rate > 0.5 else "estimating")
def render(full):
    rows, _ = state(); n = len(rows); done = sum(r[2] != "0" for r in rows); onph = sum(r[2] == "2" for r in rows)
    out = [f"{B}Voice clips{X}  {done}/{n} made ({done*100//n}%)   on phone {onph}", "[" + "#" * (done * 40 // n) + " " * (40 - done * 40 // n) + "]", ""]
    for g in ("Menu and sentences", "Battery", "Timer", "Spanish"):
        gr = [r for r in rows if group(r[0], r[1]) == g]; d = sum(r[2] != "0" for r in gr)
        out.append(f"  {g:20s} {d:3d}/{len(gr):3d}  " + (G + "complete" + X if d == len(gr) else Y + f"{len(gr)-d} missing" + X))
    rate, eta = speed(rows)
    out += ["", f"{B}Making now{X}   {rate:.1f} clips/min, {eta}"] + ["  " + w for w in workers()]
    mark = {"2": G + "✓✓" + X, "1": Y + "✓ " + X, "0": R + "· " + X}
    if full: out += [""] + [f"{mark[s]} {l} {t}" for l, t, s in rows]
    else:
        h = shutil.get_terminal_size().lines - len(out) - 6
        made = [r for r in rows if r[2] != "0"]
        last = sorted(made, key=lambda r: os.path.getmtime(f"{D}/{key(r[0], r[1])}.wav"))[-6:]
        out += ["", f"{B}Newest{X}"] + [f"{mark[s]} {l} {t[:70]}" for l, t, s in last]
        missing = [r for r in rows if r[2] == "0" and group(r[0], r[1]) in ("Menu and sentences", "Spanish")]
        out += ["", f"{B}Not made yet{X} (menu and sentences; numbers omitted)"] + [f"{mark[s]} {l} {t[:70]}" for l, t, s in missing[:max(h, 3)]]
    return "\n".join(out)
if len(sys.argv) > 1 and sys.argv[1] == "watch":
    while True: print("\033[H\033[J" + render(False), flush=True); time.sleep(3)
else: print(render(len(sys.argv) > 1 and sys.argv[1] == "list"))

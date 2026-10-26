#!/usr/bin/env python3
"""Does the hub's Suggested slot make you faster? Reads choices.log from the phone (adb, debug build) and prints a verdict.
Usage: tools/menu_report.py [choices.log]      (no argument = pull it from the phone)
Log line: time_ms, screen, code, intent, sender, tags, time_to_pick_ms (list picks only). code 0 = wake, -1 = "suggest:hit|miss" (sender = the intent picked from the hub)."""
import statistics, subprocess, sys

MIN_PICKS = 30      # deep picks needed on each side before a verdict
GAIN = 0.15         # suggested must be at least 15% faster (median) to keep

def lines():
    if len(sys.argv) > 1:
        return open(sys.argv[1]).read().splitlines()
    return subprocess.run(["adb", "exec-out", "run-as", "com.faka.invisible", "cat", "files/choices.log"],
                          capture_output=True, text=True, check=True).stdout.splitlines()

def pick_times(rows):
    """Time-to-pick (7th column, ms from the start of the list to the press) per screen: [(screen, seconds)]."""
    out = []
    for r in rows:
        if len(r) >= 7 and r[6].strip().lstrip("-").isdigit() and int(r[6]) >= 0:
            out.append((r[1], int(r[6]) / 1000))
    return out

def p90(xs):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(0.9 * len(xs)))]

def report_pick_times(rows):
    ts = pick_times(rows)
    if not ts:
        print("time-to-pick: no data yet (needs the 7th column)"); return
    by = {}
    for screen, t in ts: by.setdefault(screen, []).append(t)
    print("time-to-pick (list start -> press):")
    for screen, xs in sorted(by.items()) + [("ALL", [t for _, t in ts])]:
        print(f"  {screen:<12} n={len(xs):<4} median {statistics.median(xs):.1f} s   p90 {p90(xs):.1f} s")

def report_find(rows):
    """Find (a hold on home): how often it starts ("find"), how often it ends in a pick ("find:<steps>") and with how many steps."""
    started = sum(r[3] == "find" for r in rows)
    ends = [int(r[3].split(":")[1]) for r in rows if r[3].startswith("find:") and r[3].split(":")[1].isdigit()]
    print(f"Find: started {started}, ended in a pick {len(ends)}" + (f", median steps {statistics.median(ends):.0f}" if ends else ""))

def main():
    rows = [l.split("\t") for l in lines() if l.count("\t") >= 4]
    report_pick_times(rows)
    report_find(rows)
    first_suggest = next((int(r[0]) for r in rows if r[3].startswith("suggest:")), None)
    hits = sum(r[3] == "suggest:hit" for r in rows); miss = sum(r[3] == "suggest:miss" for r in rows)
    wake = None; before, after = [], []
    for r in rows:
        ts, code, intent = int(r[0]), int(r[2]), r[3]
        if code == 0: wake = ts; continue
        if code < 0 or wake is None or intent.endswith(":back") or intent.endswith(":done"): continue
        if intent.split(":")[0] in ("info", "phone", "games"):   # the deep options Suggested can shortcut
            t = (ts - wake) / 1000
            if t > 120: continue                                   # a forgotten session, not a pick
            (after if first_suggest is not None and ts >= first_suggest else before).append(t)
    print(f"hub picks with a suggestion shown: {hits + miss}   hit rate: " + (f"{100 * hits / (hits + miss):.0f}%" if hits + miss else "n/a"))
    for name, xs in (("before Suggested", before), ("with Suggested", after)):
        print(f"wake -> deep pick, {name}: n={len(xs)}" + (f"  median {statistics.median(xs):.1f} s" if xs else ""))
    if len(before) < MIN_PICKS or len(after) < MIN_PICKS:
        print(f"VERDICT: not enough data yet (need {MIN_PICKS} deep picks on each side)"); return
    b, a = statistics.median(before), statistics.median(after)
    gain = (b - a) / b
    print(f"VERDICT: {'KEEP' if gain >= GAIN else 'TURN OFF'}  (median {b:.1f} s -> {a:.1f} s, {100 * gain:.0f}% faster; needs {100 * GAIN:.0f}%)")

main()

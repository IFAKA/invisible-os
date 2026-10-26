#!/usr/bin/env python3
"""Turn `adb shell dumpsys usagestats` into app_usage.tsv (per launcher package: month/week launches and minutes).

Usage:
  adb shell dumpsys usagestats > us.txt
  python3 -I tools/usage_weights.py us.txt app/os/src/test/resources/phone_apps.txt app/os/src/test/resources/app_usage.tsv
Without the last two arguments it only prints the top apps by minutes.
Use the LAUNCH columns for weights: minutes are inflated by foreground services (this app, tailscale, crosspaste, termux).
"""
import re
import sys


def seconds(hms):
    parts = [int(x) for x in hms.split(':')]
    return sum(v * 60 ** i for i, v in enumerate(reversed(parts)))


def section(lines, name, end):
    start = next(i for i, l in enumerate(lines) if name in l)
    stop = next(i for i, l in enumerate(lines) if i > start and end in l)
    return lines[start:stop]


def parse(block):
    """package -> (seconds on screen, launch count)."""
    out = {}
    for l in block:
        m = re.search(r'package=(\S+) .*?totalTimeVisible="([\d:]+)".*?totalTimeFS="([\d:]+)".*?appLaunchCount=(\d+)', l)
        if m:
            out[m[1]] = (max(seconds(m[2]), seconds(m[3])), int(m[4]))
    return out


def main(argv):
    lines = open(argv[1], errors='ignore').read().split('\n')
    week = parse(section(lines, "In-memory weekly stats", "In-memory monthly"))
    month = parse(section(lines, "In-memory monthly stats", "In-memory yearly"))
    for name, d in (("weekly", week), ("monthly", month)):
        print(name, len(d))
        for p, (t, c) in sorted(d.items(), key=lambda x: -x[1][0])[:45]:
            if t > 0:
                print(f"  {t // 60:5d}min {c:4d}x {p}")
    if len(argv) > 3:
        apps = set(open(argv[2]).read().split())
        with open(argv[3], 'w') as f:
            f.write("package\tmonth_launches\tmonth_minutes\tweek_launches\tweek_minutes\n")
            for p in sorted(apps):
                mt, mc = month.get(p, (0, 0))
                wt, wc = week.get(p, (0, 0))
                f.write(f"{p}\t{mc}\t{mt // 60}\t{wc}\t{wt // 60}\n")


if __name__ == '__main__':
    main(sys.argv)

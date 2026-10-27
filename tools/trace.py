#!/usr/bin/env python3
"""Pull the app's trace log from the phone and read it. The app writes files/trace/trace-YYYY-MM-DD.log (debug build only).

  tools/trace.py                     today's log, all lines
  tools/trace.py --since 10m|2h|1d   only lines from the last 10 minutes / 2 hours
  tools/trace.py --grep TEXT         only lines that contain TEXT (case-insensitive)
  tools/trace.py --day 2026-10-27    that day (default: today; with --since the days it reaches)
  tools/trace.py --summary           counts: errors, slow steps, gestures per screen, likely wrong picks, dead-end picks, repeated speech
  tools/trace.py --file PATH         read a saved copy instead of the phone (used by the self-test)

A copy of every pulled day is saved in app/logs/trace/. Line format: HH:mm:ss.SSS kind detail.
Kinds: gesture key screen say pick item notif music media wake exit program error slow."""
import subprocess, sys, os, re, datetime, argparse, collections

PKG = "com.faka.invisible"
HERE = os.path.dirname(os.path.abspath(__file__))
SAVE = os.path.join(HERE, "..", "app", "logs", "trace")

def serial():
    devs = [l.split()[0] for l in subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:] if l.strip().endswith("device")]
    real = [d for d in devs if not d.startswith("emulator-")]
    return os.environ.get("ANDROID_SERIAL") or (real or devs or [""])[0]

def pull(day):
    r = subprocess.run(["adb", "-s", serial(), "exec-out", "run-as", PKG, "cat", f"files/trace/trace-{day}.log"], capture_output=True, text=True, timeout=60)
    return r.stdout if r.returncode == 0 else ""

def parse(day, text):
    """[(datetime, kind, detail, raw)]"""
    out = []
    for raw in text.splitlines():
        m = re.match(r"^(\d\d):(\d\d):(\d\d)\.(\d\d\d) (\S+)\s?(.*)$", raw)
        if not m: continue
        h, mi, s, ms = (int(m.group(i)) for i in range(1, 5))
        t = datetime.datetime.strptime(day, "%Y-%m-%d").replace(hour=h, minute=mi, second=s, microsecond=ms * 1000)
        out.append((t, m.group(5), m.group(6), raw))
    return out

def span(s):
    m = re.fullmatch(r"(\d+)([mhd])", s)
    if not m: sys.exit("--since wants 10m, 2h or 1d")
    return datetime.timedelta(**{{"m": "minutes", "h": "hours", "d": "days"}[m.group(2)]: int(m.group(1))})

def kv(detail, key):
    m = re.search(rf"\b{key}=(.*?)(?= \w+=|$)", detail)
    return m.group(1) if m else ""

def summary(ev):
    lines = []
    errs = [e for e in ev if e[1] == "error"]
    lines.append(f"errors: {len(errs)}")
    for t, _, d, _ in errs[:5]: lines.append(f"  {t:%H:%M:%S} {d[:160]}")
    slow = sorted((e for e in ev if e[1] == "slow"), key=lambda e: -int((re.findall(r"(\d+)ms", e[2]) or ["0"])[-1]))
    lines.append(f"slow steps: {len(slow)}")
    for t, _, d, _ in slow[:5]: lines.append(f"  {t:%H:%M:%S} {d}")
    # gestures per screen: the last "screen" line before each gesture
    screen = "idle"; per = collections.defaultdict(collections.Counter)
    for t, k, d, _ in ev:
        if k == "screen": screen = d.split()[0]
        elif k == "exit": screen = "idle"
        elif k == "wake": screen = "HUB" if "menu on" in d else screen
        elif k == "gesture" and not d.startswith("click_hold_end"):
            per[screen][d.split()[0]] += 1
    lines.append("gestures per screen: " + ("; ".join(f"{s}: " + " ".join(f"{g}x{n}" for g, n in c.most_common()) for s, c in per.items()) or "none"))
    # picks: a back within 5 s of a pick (that is not itself a back) = likely wrong pick; a pick with no screen change in 8 s = led nowhere
    picks = [(i, e) for i, e in enumerate(ev) if e[1] == "pick"]
    isback = lambda d: "back" in kv(d, "intent")
    wrong = []; nowhere = []
    for n, (i, e) in enumerate(picks):
        if isback(e[2]) or kv(e[2], "intent") in ("none", "nothing"): continue
        nxt = picks[n + 1][1] if n + 1 < len(picks) else None
        if nxt and isback(nxt[2]) and (nxt[0] - e[0]).total_seconds() <= 5: wrong.append(e)
        later = [x for x in ev[i + 1:] if (x[0] - e[0]).total_seconds() <= 8]
        if not any(x[1] in ("screen", "program", "exit", "music", "media") or (x[1] == "say" and "first audio" not in x[2]) for x in later): nowhere.append(e)
    lines.append(f"back right after a pick (likely wrong pick): {len(wrong)}")
    for t, _, d, _ in wrong[:5]: lines.append(f"  {t:%H:%M:%S} {d[:120]}")
    lines.append(f"picks that led nowhere: {len(nowhere)}")
    for t, _, d, _ in nowhere[:5]: lines.append(f"  {t:%H:%M:%S} {d[:120]}")
    says = collections.Counter(re.sub(r"^\[\w+\]( \w+)* ", "", d) for _, k, d, _ in ev if k == "say" and "first audio" not in d and not d.startswith("still"))
    rep = [(s, n) for s, n in says.most_common() if n >= 4][:5]
    lines.append("repeated speech (4+ times): " + ("; ".join(f"{n}x '{s[:50]}'" for s, n in rep) or "none"))
    return lines

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--since"); ap.add_argument("--grep"); ap.add_argument("--day"); ap.add_argument("--summary", action="store_true"); ap.add_argument("--file")
    a = ap.parse_args()
    now = datetime.datetime.now()
    since = now - span(a.since) if a.since else None
    if a.file:
        day = a.day or re.search(r"(\d{4}-\d\d-\d\d)", a.file).group(1)
        days = [(day, open(a.file).read())]
    else:
        first = datetime.datetime.strptime(a.day, "%Y-%m-%d").date() if a.day else (since or now).date()
        last = first if a.day and not since else now.date()
        days = []; d = first
        while d <= last:
            ds = d.isoformat(); txt = pull(ds)
            if txt:
                os.makedirs(SAVE, exist_ok=True)
                with open(os.path.join(SAVE, f"trace-{ds}.log"), "w") as f: f.write(txt)
                days.append((ds, txt))
            d += datetime.timedelta(days=1)
    ev = [e for ds, txt in days for e in parse(ds, txt)]
    if since: ev = [e for e in ev if e[0] >= since]
    if a.grep: ev = [e for e in ev if a.grep.lower() in e[3].lower()]
    kinds = collections.Counter(e[1] for e in ev)
    print(f"trace {', '.join(d for d, _ in days) or 'no log found (debug build installed? app run once?)'}: {len(ev)} lines, errors={kinds['error']} slow={kinds['slow']}")
    if a.summary:
        print("\n".join(summary(ev)))
    else:
        for e in ev: print(e[3])

if __name__ == "__main__": main()

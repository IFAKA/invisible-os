#!/usr/bin/env python3
"""End-to-end suite on the real phone, no human. Presses go through the real button path (debug hook `press`);
what the OS says is read from the SAY log (and the SPEAK log for the voice rate). Needs the debug build installed
(app/run.sh) and the phone lease (~/.claude/bin/phone take MIN why).

  tools/e2e.py                run every scenario, one line each, log in app/logs/e2e.log
  tools/e2e.py --only NAME    run scenarios whose name contains NAME (comma-separate for several)
  tools/e2e.py --list         list the scenarios

Every scenario starts with the debug hook `reset` (no program, menu closed, speech stopped, fake notifications gone)
and waits on log events, never on fixed sleeps. Safety: no scenario ever picks "Yes, call now", Answer, Reject or a
real reply; the fake call and chat are notifications posted by the app itself whose buttons point at nobody.
Exit code 1 if any scenario fails."""
import subprocess, sys, time, re, threading, os, datetime

PKG = "com.faka.invisible"
RECV = f"{PKG}/.AiTestReceiver"
HERE = os.path.dirname(os.path.abspath(__file__))
LOG = os.path.join(HERE, "..", "app", "logs", "e2e.log")

def _serial():
    """The real phone: another session may have an emulator attached too."""
    devs = [l.split()[0] for l in subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:] if l.strip().endswith("device")]
    real = [d for d in devs if not d.startswith("emulator-")]
    return (os.environ.get("ANDROID_SERIAL") or (real or devs or [""])[0])
SERIAL = _serial()

def adb(*a, timeout=20):
    return subprocess.run(["adb", "-s", SERIAL, *a], capture_output=True, text=True, timeout=timeout).stdout

def bcast(*extra):
    adb("shell", "am", "broadcast", "-n", RECV, *extra)

# ---- log reader: one logcat process for the whole run; events are (time, kind, text) ----
events = []; LOGCAT = []
lock = threading.Lock()
LINE = re.compile(r"^\s*([\d.]+)\s+\d+\s+\d+\s+D\s+(SAY|E2E|Invisible)\s*:\s*(.*)$")

def reader():
    p = subprocess.Popen(["adb", "-s", SERIAL, "logcat", "-T", "1", "-v", "epoch", "-s", "SAY:D", "E2E:D", "Invisible:D"], stdout=subprocess.PIPE, text=True, bufsize=1)
    LOGCAT.append(p)
    for line in p.stdout:
        m = LINE.match(line.rstrip("\n"))
        if not m: continue
        kind, text = m.group(2), m.group(3)
        if kind == "Invisible" and not text.startswith(("SPEAK[", "MSG ")): continue
        with lock: events.append((time.time(), kind, text))

def mark():
    with lock: return len(events)

def since(i, kind=None):
    with lock: ev = events[i:]
    return [t for _, k, t in ev if kind is None or k == kind]

def wait(pred, i, kind=None, timeout=15.0):
    """Wait until an event after index i of this kind satisfies pred(text); return its text or None."""
    t0 = time.time(); seen = i
    while time.time() - t0 < timeout:
        with lock: ev = events[seen:]; n = len(events)
        for _, k, t in ev:
            if (kind is None or k == kind) and pred(t): return t
        seen = n
        time.sleep(0.03)
    return None

def said(text, i, timeout=15.0):
    """Wait for a spoken line (SAY) that contains `text` (case-insensitive)."""
    return wait(lambda t: text.lower() in t.lower(), i, "SAY", timeout)

class Fail(Exception): pass

# ---- phone helpers ----
def press(ms): bcast("--es", "press", ms)
def reset(practice_done=True):
    i = mark(); bcast("--ez", "reset", "true" if practice_done else "false")
    if not wait(lambda t: t == "reset done", i, "E2E", 20): raise Fail("reset hook did not answer (is the debug build installed?)")
def show(screen):
    i = mark(); bcast("--es", "show", screen)
    if not wait(lambda t: t == "show " + screen, i, "E2E", 20): raise Fail("show hook did not answer")
def state():
    i = mark(); bcast("--ez", "state", "true")
    t = wait(lambda t: t.startswith("state "), i, "E2E", 20)
    if not t: raise Fail("state hook did not answer")
    return dict(kv.split("=", 1) for kv in t.split()[1:])
def wait_screen(screen, timeout=8.0):
    t0 = time.time(); s = {}
    while time.time() - t0 < timeout:
        s = state()
        if s.get("active") == "true" and s.get("screen") == screen: return s
    raise Fail(f"expected screen {screen}, state is {s}")
def wait_closed(timeout=8.0):
    t0 = time.time(); s = {}
    while time.time() - t0 < timeout:
        s = state()
        if s.get("active") == "false": return s
    raise Fail(f"menu still open: {s}")
def need(text, i, timeout=15.0):
    r = said(text, i, timeout)
    if r is None: raise Fail(f"never heard {text!r}; heard: " + " | ".join(since(i, "SAY")[:8]))
    return r

def heard_start(text, i, timeout=15.0):
    """Wait until `text` is spoken AND the voice has started it: a click before that does not pick it."""
    need(text, i, timeout)
    with lock: k = max(n for n, (_, kd, t) in enumerate(events[i:], i) if kd == "SAY" and text.lower() in t.lower())
    if wait(lambda t: t == "speech started", k, "E2E", 6) is None: raise Fail(f"{text!r} was logged but the voice never started it")

# ---- scenarios ----
def wake_reads_home_menu():
    reset(); i = mark(); press("900")
    wait(lambda t: len(set(since(i, "SAY"))) >= 3, i, "SAY", 20)
    h = list(dict.fromkeys(since(i, "SAY")))
    if len(h) < 3: raise Fail("home menu not read; heard: " + " | ".join(h))
    wait_screen("HUB")
    return "heard " + ", ".join(h[:4])

def click_picks():
    reset(); show("PHONE"); i = mark()
    heard_start("Emergency", i)
    press("120")   # a click right after hearing an item picks it
    need("Call the emergency number", i)
    wait_screen("EMERGENCY")
    return "Emergency picked"

def triple_click_goes_back():
    reset(); show("PHONE"); i = mark(); heard_start("Emergency", i); press("120"); wait_screen("EMERGENCY")
    j = mark(); heard_start("No, go back", j)
    press("100,150,100,150,100")
    wait_screen("PHONE")
    return "Emergency -> Phone"

def click_hold_closes():
    reset(); show("PHONE"); i = mark(); heard_start("Emergency", i)
    press("120,150,900")
    wait_closed()
    return "menu closed"

def practice_end_to_end():
    reset(False); i = mark(); press("900")
    need("click once now", i, 20)
    press("120"); need("click twice", i)
    press("120,150,120"); need("click three times", i)
    press("100,150,100,150,100"); need("click, then hold", i)
    j = mark(); press("120,150,900")
    # the practice ends, the OS plays the close sound, then the home menu opens by itself
    t0 = time.time(); s = {}
    while time.time() - t0 < 12:
        s = state()
        if s.get("active") == "true" and s.get("screen") == "HUB" and s.get("running") == "false": break
    else: raise Fail(f"home menu did not open after the practice: {s}")
    wait(lambda t: True, j, "SAY", 10)
    h = list(dict.fromkeys(since(j, "SAY")))
    if not h: raise Fail("home menu opened but nothing was said")
    return "practice done, home says " + h[0]

def phone_list():
    reset(); show("PHONE"); i = mark()
    need("Emergency", i); need("Call a contact", i)
    h = since(i, "SAY")
    if h[0] != "Emergency": raise Fail(f"first item is {h[0]!r}, not Emergency")
    return "Emergency, Call a contact"

def emergency_confirm_goes_back():
    reset(); show("PHONE"); i = mark(); heard_start("Emergency", i); press("120"); wait_screen("EMERGENCY")
    j = mark(); heard_start("No, go back", j)
    if any("yes, call" in t.lower() for t in since(j, "SAY")): raise Fail("Yes was read before our click; refusing to click")   # safety: never risk the call
    press("120")   # the click lands on "No, go back"
    wait_screen("PHONE")
    if any("calling" in t.lower() for t in since(j, "SAY")): raise Fail("a call was started")
    return "No, go back -> Phone"

def fake_incoming_call():
    reset(); i = mark(); bcast("--es", "fake", "call")
    need("Call from E2E Caller", i, 12)
    need("Answer", i); need("Reject", i)
    wait_screen("CALL_IN")
    return "Call from E2E Caller, Answer, Reject"

def fake_chat_message():
    reset(); i = mark(); bcast("--es", "fake", "chat")
    if not wait(lambda t: t.startswith("MSG " + PKG + " from E2E Friend"), i, "Invisible", 15): raise Fail("the listener never took the fake message")
    press("900")
    need("E2E Friend", i, 15); need("dinner", i)
    t0 = time.time()   # the reply options follow the message
    while time.time() - t0 < 15 and len(set(since(i, "SAY"))) < 3: time.sleep(0.2)
    h = list(dict.fromkeys(since(i, "SAY")))
    if len(h) < 3: raise Fail("message read but no options: " + " | ".join(h))
    return " | ".join(h[:5])

def voice_speed_applied():
    old = float(state()["rate"])
    try:
        i = mark(); bcast("--ef", "rate", "1.4"); wait(lambda t: t.startswith("rate set"), i, "E2E", 20)
        reset(); j = mark(); press("900")
        t = wait(lambda t: "rate=" in t, j, "Invisible", 15)
        if not t: raise Fail("no SPEAK line with a rate")
        r = float(re.search(r"rate=([\d.]+)", t).group(1))
        if not 1.4 <= r <= 1.61: raise Fail(f"voice rate {r}, expected 1.4 to 1.6")
        return f"rate {r}"
    finally:
        bcast("--ef", "rate", str(old)); reset()

def rooms_go():
    """Laptop tool against the fake Rooms server: hear the rooms, open the first, hear its need, click Go, the server got it."""
    sys.path.insert(0, os.path.join(HERE, "..", "bridge")); import fake_rooms
    home = os.path.expanduser("~/.config/invisible-bridge/")
    real_url = open(home + "url").read().strip() if os.path.exists(home + "url") else ""
    real_tok = open(home + "token").read().strip() if os.path.exists(home + "token") else ""
    host = re.sub(r"^https?://", "", real_url).split(":")[0].split("/")[0] or "0.0.0.0"
    srv = fake_rooms.start(host, 8799, "e2e-token")
    try:
        bcast("--es", "bridge", f"http://{host}:8799", "--es", "token", "e2e-token")
        reset(); i = mark(); bcast("--es", "start", "claude")
        heard_start("Alpha, needs you", i, 20)
        need("Beta, working", i); need("Gamma, done", i)
        press("120")   # picks Alpha, the item just heard
        j = mark(); need("The design doc is ready", j)
        heard_start("Go", j)
        press("120")
        need("Sent to Alpha", j)
        if ("/room/act", {"id": "%1", "action": "go"}) not in fake_rooms.POSTS: raise Fail(f"fake server got {fake_rooms.POSTS}")
        return "rooms heard, Alpha need heard, Go reached the server"
    finally:
        srv.shutdown()
        bcast("--es", "bridge", real_url, "--es", "token", real_tok)
        try: reset()
        except Fail: pass

SCENARIOS = [wake_reads_home_menu, click_picks, triple_click_goes_back, click_hold_closes, practice_end_to_end,
             phone_list, emergency_confirm_goes_back, fake_incoming_call, fake_chat_message, voice_speed_applied, rooms_go]

def main():
    args = sys.argv[1:]
    if "--list" in args: print("\n".join(f.__name__ for f in SCENARIOS)); return 0
    only = []
    if "--only" in args:
        k = args.index("--only"); only = [x for a in args[k + 1:k + 2] for x in a.split(",")]
    chosen = [f for f in SCENARIOS if not only or any(o in f.__name__ for o in only)]
    if not SERIAL: sys.exit("no phone connected (adb mdns services; adb connect IP:PORT)")
    threading.Thread(target=reader, daemon=True).start()
    import atexit; atexit.register(lambda: LOGCAT and LOGCAT[0].terminate())
    time.sleep(0.5)
    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    lines = []; fails = 0; t_all = time.time()
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(0.8)
    for f in chosen:
        t0 = time.time()
        try: detail = f(); ok = True
        except Fail as e: detail = str(e); ok = False
        except Exception as e: detail = repr(e); ok = False
        fails += not ok
        line = f"{'PASS' if ok else 'FAIL'} {f.__name__:<28} {time.time()-t0:5.1f}s  {detail}"
        print(line, flush=True); lines.append(line)
    try: reset()
    except Fail: pass
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    summary = f"{len(chosen)-fails}/{len(chosen)} passed in {time.time()-t_all:.0f}s"
    print(summary)
    with open(LOG, "a") as fh:
        fh.write(f"# {datetime.datetime.now():%Y-%m-%d %H:%M:%S} {summary}\n" + "\n".join(lines) + "\n")
    return 1 if fails else 0

if __name__ == "__main__":
    sys.exit(main())

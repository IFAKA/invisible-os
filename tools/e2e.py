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
        if kind == "Invisible" and not text.startswith(("SPEAK[", "MSG ", "TRACE media")): continue
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
    heard_start("Emergency", i, 30)
    time.sleep(0.8)   # past the 500 ms grace after the item starts, so the click is for Emergency
    press("120")   # a click right after hearing an item picks it
    need("Call the emergency number", i)
    wait_screen("EMERGENCY")
    return "Emergency picked"

def triple_click_goes_back():
    reset(); show("PHONE"); i = mark(); heard_start("Emergency", i, 30); time.sleep(0.8); press("120"); wait_screen("EMERGENCY")
    j = mark(); heard_start("No, go back", j)
    press("100,150,100,150,100")
    wait_screen("PHONE")
    return "Emergency -> Phone"

def click_hold_closes():
    reset(); show("PHONE"); i = mark(); heard_start("Emergency", i, 30)
    press("120,150,900")
    wait_closed()
    return "menu closed"

def screen_tap_picks():
    reset(); show("GAMES"); i = mark(); heard_start("Duel", i)
    bcast("--es", "tap", "0")   # a tap on the first item of the mirror, as a finger on the app screen would
    t0 = time.time()
    while True:
        m = state().get("mirror", "")
        if m.endswith(":click") and state().get("running") == "true": break   # the Duel started (its own lines now fill the screen), the tap shows as a click
        if time.time() - t0 > 8: raise Fail(f"tap did not pick the item: {state()}")
    press("120,150,900"); wait_closed()
    return "a tap on the app screen picks the item, the same as a click"
def screen_mirrors_menu():
    reset(); show("PHONE"); i = mark(); heard_start("Emergency", i, 30)
    s = state()
    if not s.get("mirror", "").startswith("true:") or s["mirror"].split(":")[1] == "0": raise Fail(f"mirror empty while a list is read: {s}")
    press("120")
    t0 = time.time()
    while state().get("mirror", "").split(":")[-1] != "click":
        if time.time() - t0 > 8: raise Fail(f"mirror did not show the click: {state()}")
    press("120,150,900"); wait_closed()
    if state().get("mirror", "").split(":")[0] != "false": raise Fail(f"mirror still on after close: {state()}")
    return "mirror shows the list and the click, and clears on close"

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
    need("Call a contact", i); need("Emergency", i, 30)
    h = since(i, "SAY")
    if h[0] != "Call a contact" or h[-1] != "Emergency": raise Fail(f"order is {h!r}, want Call a contact first and Emergency last")
    return "Call a contact first, Emergency last"

def emergency_confirm_goes_back():
    reset(); show("PHONE"); i = mark(); heard_start("Emergency", i, 30); time.sleep(0.8); press("120"); wait_screen("EMERGENCY")
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

def chat_arrival_spoken():
    """A chat message arrives with the OS idle: after the tone the phone says who wrote, then the text (twice in a burst = once)."""
    reset(); i = mark(); bcast("--es", "fake", "chat")
    need("E2E Friend", i, 15); need("dinner", i, 15)
    lines = list(dict.fromkeys(since(i, "SAY")))
    first = next((l for l in lines if "E2E Friend" in l), "")
    if "," not in first: raise Fail("the sender line should name the app first: " + first)
    time.sleep(2.5)
    if sum(1 for l in since(i, "SAY") if "dinner" in l) != 1: raise Fail("the text was read more than once: " + " | ".join(since(i, "SAY")))
    return " | ".join(lines[:3])

def live_app_first_on_home():
    """An ongoing notification with a chronometer and a Lap button comes first on home (before the groups); a static one does not; a click opens its actions."""
    reset(); bcast("--es", "fake", "static"); bcast("--es", "fake", "live"); time.sleep(1.5)
    i = mark(); press("900")
    heard_start("12:30 running", i, 20)
    h = list(dict.fromkeys(since(i, "SAY")))
    if "running" not in h[0] and "running" not in (h[1] if len(h) > 1 else ""): raise Fail("the live entry is not first: " + " | ".join(h[:4]))
    if any("Connected" in l for l in h): raise Fail("a static ongoing notification was offered: " + " | ".join(h))
    time.sleep(1.3)   # a click in the first moments of an item is for the one before it (reaction grace)
    press("120")
    j = mark(); need("Lap", j, 15); need("Dismiss", j, 15)
    wait_screen("NOTIF_ACTIONS")
    press("100,150,100,150,100"); wait_screen("HUB")   # triple click = back to home
    reset()
    return " | ".join(h[:3]) + " -> Lap, Dismiss ..."

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

def test_host(real_url):
    """The laptop address the phone can reach for the fake server: the one on the phone's own Wi-Fi network (the saved bridge address may be a Tailscale one the phone is not on now)."""
    out = adb("shell", "ip", "-4", "addr", "show", "wlan0")
    m = re.search(r"inet (\d+\.\d+\.\d+)\.\d+/", out if isinstance(out, str) else getattr(out, "stdout", "") or "")
    if m:
        for ifc in ("en0", "en1"):
            ip = subprocess.run(["ipconfig", "getifaddr", ifc], capture_output=True, text=True).stdout.strip()
            if ip.startswith(m.group(1) + "."): return ip
    return re.sub(r"^https?://", "", real_url).split(":")[0].split("/")[0] or "0.0.0.0"

def rooms_go():
    """Laptop tool against the fake Rooms server: hear the rooms, open the first, hear its need, click Go, the server got it."""
    sys.path.insert(0, os.path.join(HERE, "..", "bridge")); import fake_rooms
    home = os.path.expanduser("~/.config/invisible-bridge/")
    real_url = open(home + "url").read().strip() if os.path.exists(home + "url") else ""
    real_tok = open(home + "token").read().strip() if os.path.exists(home + "token") else ""
    host = test_host(real_url)
    srv = fake_rooms.start(host, 8799, "e2e-token")
    try:
        bcast("--es", "bridge", f"http://{host}:8799", "--es", "token", "e2e-token")
        reset(); i = mark(); bcast("--es", "start", "claude")
        heard_start("Alpha, needs you", i, 20)
        press("120")   # picks Alpha, the item just heard (the list goes on: Beta, Gamma, Projects)
        j = mark(); need("The design doc is ready", j)
        heard_start("Go ahead", j)
        press("120")
        need("Sent to Alpha", j)
        if ("/room/act", {"id": "%1", "action": "go"}) not in fake_rooms.POSTS: raise Fail(f"fake server got {fake_rooms.POSTS}")
        return "Alpha heard and picked, its need heard, Go reached the server"
    finally:
        srv.shutdown()
        bcast("--es", "bridge", real_url, "--es", "token", real_tok)
        try: reset()
        except Fail: pass

def rooms_waiting_on_home():
    """Windows wait on the laptop: home says "<Tool>, N need you" as its first entry (the count is renewed at a wake, so it shows from the second wake)."""
    sys.path.insert(0, os.path.join(HERE, "..", "bridge")); import fake_rooms
    home = os.path.expanduser("~/.config/invisible-bridge/")
    real_url = open(home + "url").read().strip() if os.path.exists(home + "url") else ""
    real_tok = open(home + "token").read().strip() if os.path.exists(home + "token") else ""
    host = test_host(real_url)
    srv = fake_rooms.start(host, 8797, "e2e-token")
    try:
        bcast("--es", "bridge", f"http://{host}:8797", "--es", "token", "e2e-token")
        press("900"); time.sleep(3); press("100,150,900"); time.sleep(2)   # no reset: it would drop the Claude tool from the home list
        i = mark(); press("900")
        heard_start("needs you", i, 20)
        h = list(dict.fromkeys(since(i, "SAY")))
        if not any("needs you" in x for x in h[:5]): raise Fail("the waiting entry is not first: " + " | ".join(h[:4]))
        return " | ".join(h[:2])
    finally:
        srv.shutdown()
        bcast("--es", "bridge", real_url, "--es", "token", real_tok)
        try: reset()
        except Fail: pass

def rooms_done_spoken():
    """A laptop window finishes while the phone is idle: the earbud says "<name>, done"."""
    sys.path.insert(0, os.path.join(HERE, "..", "bridge")); import fake_rooms
    home = os.path.expanduser("~/.config/invisible-bridge/")
    real_url = open(home + "url").read().strip() if os.path.exists(home + "url") else ""
    real_tok = open(home + "token").read().strip() if os.path.exists(home + "token") else ""
    host = test_host(real_url)
    srv = fake_rooms.start(host, 8798, "e2e-token")
    try:
        reset(); i = mark()
        bcast("--es", "bridge", f"http://{host}:8798", "--es", "token", "e2e-token")
        fake_rooms.FIRE_DONE.append("Delta")
        need("Delta, done", i, 20)
        return "idle phone said: Delta, done"
    finally:
        srv.shutdown()
        bcast("--es", "bridge", real_url, "--es", "token", real_tok)
        try: reset()
        except Fail: pass

def rooms_new_project():
    """Laptop tool against the fake server: Projects, New project, an area, then the cue for the name (Write starts)."""
    sys.path.insert(0, os.path.join(HERE, "..", "bridge")); import fake_rooms
    home = os.path.expanduser("~/.config/invisible-bridge/")
    real_url = open(home + "url").read().strip() if os.path.exists(home + "url") else ""
    real_tok = open(home + "token").read().strip() if os.path.exists(home + "token") else ""
    host = test_host(real_url)
    srv = fake_rooms.start(host, 8799, "e2e-token")
    try:
        bcast("--es", "bridge", f"http://{host}:8799", "--es", "token", "e2e-token")
        reset(); i = mark(); bcast("--es", "start", "claude")
        heard_start("Projects", i, 25); press("120")
        j = mark(); heard_start("New project", j); press("120")
        k = mark(); heard_start("hardware", k); press("120")
        need("Write the project name", k)
        return "Projects, New project, hardware, name cue heard"
    finally:
        srv.shutdown()
        bcast("--es", "bridge", real_url, "--es", "token", real_tok)
        try: reset()
        except Fail: pass

def computers_two():
    """Two computers (two fake bridges), Home active: only Home is heard (a Work window finishing is not); the Laptop tool offers "Switch to Work";
    picking it says "Work, listening" and opens Work's rooms; then only Work is heard."""
    sys.path.insert(0, os.path.join(HERE, "..", "bridge")); import fake_rooms
    home = os.path.expanduser("~/.config/invisible-bridge/")
    real_url = open(home + "url").read().strip() if os.path.exists(home + "url") else ""
    real_tok = open(home + "token").read().strip() if os.path.exists(home + "token") else ""
    host = test_host(real_url)
    work_rooms = [{"id": "%5", "name": "Zeta", "project": "zeta-proj", "path": "/tmp/zeta", "kind": "claude", "state": "working",
                   "need": "", "options": [], "actions": ["stop", "readall", "screen", "write"]}]
    home_fire = []; work_fire = []
    a = fake_rooms.start(host, 8796, "tok-home", fire=home_fire)
    b = fake_rooms.start(host, 8797, "tok-work", fire=work_fire, rooms=work_rooms)
    try:
        bcast("--es", "computers", f"'Home|http://{host}:8796|tok-home;Work|http://{host}:8797|tok-work'")   # quoted: the phone shell would cut at ; and |
        bcast("--es", "active", "Home")
        reset(); i = mark()
        work_fire.append("Omega"); home_fire.append("Sigma")
        need("Sigma, done", i, 25)
        time.sleep(5)
        if said("Omega", i, 1): raise Fail("heard Work (Omega) while Home is the active computer")
        j = mark(); bcast("--es", "start", "claude")
        heard_start("Switch to Work", j, 40); press("120")
        need("Work, listening", j, 15)
        k = mark(); need("Zeta, working", j, 20)
        reset(); m = mark()
        work_fire.append("Psi")
        need("Psi, done", m, 25)
        return "only Home was heard; Switch to Work said Work, listening and opened Zeta; then Work's Psi was heard"
    finally:
        a.shutdown(); b.shutdown()
        bcast("--es", "bridge", real_url, "--es", "token", real_tok)
        try: reset()
        except Fail: pass

def idle_clicks_go_to_the_player():
    """OS closed: 1, 2, 3 clicks are replayed to another app's player as play/pause, next, previous (dry: traced, not sent)."""
    reset(); bcast("--ez", "replaydry", "true")
    try:
        got = []
        for n, gap, word in ((1, "", r"(pause|play|play_pause)"), (2, "100,150,100", "next"), (3, "100,150,100,150,100", "previous")):
            i = mark(); press(gap or "120")
            hit = wait(lambda t: re.search(rf"replay {n}\b.*{word}|replay {n}: no other", t) is not None, i, "Invisible", 8)
            if hit is None: raise Fail(f"{n} click(s) while idle were not replayed to a player")
            if "pkg=com.faka.invisible" in hit: raise Fail("replayed to our own session")
            got.append(hit[:48])
        return " | ".join(got)
    finally:
        bcast("--ez", "replaydry", "false")

SCENARIOS = [wake_reads_home_menu, click_picks, triple_click_goes_back, click_hold_closes, screen_mirrors_menu, screen_tap_picks, practice_end_to_end,
             phone_list, emergency_confirm_goes_back, fake_incoming_call, fake_chat_message, chat_arrival_spoken, live_app_first_on_home, voice_speed_applied, rooms_go, rooms_waiting_on_home, rooms_done_spoken, computers_two, idle_clicks_go_to_the_player, rooms_new_project]   # rooms_new_project leaves the Write cue running; keep it last

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

#!/usr/bin/env python3
"""End-to-end check on the real phone, no human: presses go through the real button path (debug hook `press`),
what the OS says is read from the SAY log. Run: tools/e2e.py  (needs the debug build installed and the phone lease).
Each scenario prints PASS/FAIL with what was heard. Exit code 1 if any fails."""
import subprocess, sys, time, re
PKG = "com.faka.invisible"
def adb(*a): return subprocess.run(["adb", *a], capture_output=True, text=True).stdout
def press(ms): adb("shell", "am", "broadcast", "-n", f"{PKG}/.AiTestReceiver", "--es", "press", ms)
def clear(): adb("logcat", "-c")
def said():
    out = adb("logcat", "-d", "-v", "time", "-s", "SAY:D")
    return [l.split("): ",1)[1].strip() for l in out.splitlines() if "D/SAY" in l and "): " in l]
def settle(quiet=4.0, limit=40.0):
    """Wait until nothing new is spoken for `quiet` seconds."""
    t0 = last = time.time(); n = -1
    while time.time() - t0 < limit:
        k = len(said())
        if k != n: n = k; last = time.time()
        if time.time() - last >= quiet: break
        time.sleep(0.5)
    return said()
def stop(): press("3200")   # hold 3 s leaves a program / stops
results = []
def check(name, ok, heard):
    results.append(ok); print(("PASS " if ok else "FAIL ") + name + "\n     heard: " + " | ".join(heard[:12]))

def wake_reads_menu():
    clear(); press("900"); h = settle(quiet=3)
    check("hold wakes the OS and reads the home menu", len(h) >= 3, h)
def click_picks_first_item():
    clear(); press("120"); h = settle(quiet=3)   # a click picks the item being read
    check("one click picks and opens something new", len(h) >= 1, h)
def triple_click_goes_back():
    clear(); press("100,150,100,150,100"); h = settle(quiet=3)
    check("three clicks go back (the OS speaks again) [FINDING: says nothing when already at the top]", len(h) >= 1, h)
def practice_runs():
    clear(); adb("shell", "am", "broadcast", "-n", f"{PKG}/.AiTestReceiver", "--es", "start", "practice"); h = settle(quiet=6)
    check("practice starts and speaks its first prompt", any("practice" in x.lower() or "click" in x.lower() for x in h), h)
    press("120"); h = settle(quiet=6)
    check("practice answers a click with the next step", any("twice" in x.lower() for x in h), h)
    stop(); time.sleep(3)

if __name__ == "__main__":
    if "device" not in adb("devices"): sys.exit("no phone connected")
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity"); time.sleep(1)
    for f in (wake_reads_menu, click_picks_first_item, triple_click_goes_back, practice_runs):
        try: f()
        except Exception as e: check(f.__name__, False, [repr(e)])
    adb("shell", "input", "keyevent", "KEYCODE_HOME")
    print(f"\n{sum(results)}/{len(results)} passed"); sys.exit(0 if all(results) else 1)

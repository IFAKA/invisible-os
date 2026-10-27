"""Tests for setup.sh with a temp HOME and a fake launchctl and pbcopy: the real settings and the real launchd are never touched."""
import json
import os
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import setup_tools  # noqa: E402

FAKE_LAUNCHCTL = r'''#!/usr/bin/env python3
import os, plistlib, signal, subprocess, sys
home = os.environ["HOME"]
log = open(os.path.join(home, "launchctl.log"), "a"); log.write(" ".join(sys.argv[1:]) + "\n"); log.close()
pidf = os.path.join(home, "fake.pid")
a = sys.argv[1:]
def running():
    try:
        os.kill(int(open(pidf).read()), 0); return True
    except (OSError, ValueError):
        return False
if a[0] == "print":
    sys.exit(0 if running() else 1)
if a[0] in ("bootout", "unload"):
    if running(): os.kill(int(open(pidf).read()), signal.SIGTERM)
    sys.exit(0)
if a[0] in ("bootstrap", "load"):
    plist = a[-1]
    args = plistlib.load(open(plist, "rb"))["ProgramArguments"]
    p = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    open(pidf, "w").write(str(p.pid))
    sys.exit(0)
sys.exit(1)
'''
FAKE_PBCOPY = '#!/bin/sh\ncat > "$HOME/clipboard.txt"\n'


def free_port():
    s = socket.socket(); s.bind(("0.0.0.0", 0)); p = s.getsockname()[1]; s.close(); return p


class SetupTest(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp(prefix="setup-test-")
        self.bin = tempfile.mkdtemp(prefix="setup-bin-")
        for name, body in (("launchctl", FAKE_LAUNCHCTL), ("pbcopy", FAKE_PBCOPY)):
            p = os.path.join(self.bin, name)
            open(p, "w").write(body); os.chmod(p, 0o755)
        self.env = dict(os.environ, HOME=self.home, PATH=self.bin + os.pathsep + os.environ["PATH"])
        self.env.pop("INVISIBLE_BRIDGE_URL", None)
        self.env["INVISIBLE_BIN_DIR"] = os.path.join(self.home, "bin")

    def tearDown(self):
        try:
            os.kill(int(open(os.path.join(self.home, "fake.pid")).read()), signal.SIGTERM)
        except (OSError, ValueError):
            pass
        shutil.rmtree(self.home, ignore_errors=True); shutil.rmtree(self.bin, ignore_errors=True)

    def run_setup(self, *args, env=None):
        return subprocess.run(["sh", os.path.join(HERE, "setup.sh"), *args], env=env or self.env, capture_output=True, text=True, timeout=90)

    def settings(self):
        return json.load(open(os.path.join(self.home, ".claude", "settings.json")))

    def test_full_run(self):
        port = free_port()
        os.makedirs(os.path.join(self.home, ".claude"))
        mine = {"model": "x", "hooks": {"Stop": [{"hooks": [{"type": "command", "command": "echo mine"}]}], "PostToolUse": [{"hooks": []}]}}
        json.dump(mine, open(os.path.join(self.home, ".claude", "settings.json"), "w"))
        r = self.run_setup("--name", "Work", "--port", str(port))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        out = r.stdout
        self.assertIn("Work", out)
        self.assertIn("\x1b[30;47m", out)   # the QR (a web link: install if needed, then connect)
        self.assertTrue(out.rstrip().endswith("Scan this with your phone camera."))   # the QR and its one line come last
        self.assertLessEqual(len([l for l in out.splitlines() if "\x1b" not in l]), 8)
        # settings: ours added, theirs kept, backup of the old file
        s = self.settings()
        self.assertEqual(s["model"], "x")
        self.assertIn("PostToolUse", s["hooks"])
        self.assertEqual(s["hooks"]["Stop"][0]["hooks"][0]["command"], "echo mine")
        self.assertEqual(len(s["hooks"]["Stop"]), 2)
        for ev in ("PermissionRequest", "PreToolUse", "Notification", "Stop"):
            self.assertTrue(any("bridge/hook.py" in h["command"] for e in s["hooks"][ev] for h in e["hooks"]), ev)
        self.assertEqual(json.load(open(os.path.join(self.home, ".claude", "settings.json.bak"))), mine)
        # launchd file
        plist = os.path.join(self.home, "Library", "LaunchAgents", "com.invisible.bridge.plist")
        import plistlib
        pl = plistlib.load(open(plist, "rb"))
        self.assertTrue(pl["RunAtLoad"] and pl["KeepAlive"])
        self.assertEqual(pl["ProgramArguments"][-4:], ["--name", "Work", "--port", str(port)])
        # the server answers and the hook would find it (url file in this HOME)
        url = open(os.path.join(self.home, ".config", "invisible-bridge", "url")).read().strip()
        self.assertTrue(url.endswith(":%d" % port))
        self.assertEqual(open(os.path.join(self.home, "clipboard.txt")).read().split()[0].split(":")[0], "invisible-pair")
        # the pair code in the output is the clipboard one and carries the right port and name
        import base64
        code = open(os.path.join(self.home, "clipboard.txt")).read().strip()
        raw = code.split(":", 1)[1]
        info = json.loads(base64.urlsafe_b64decode(raw + "=" * (-len(raw) % 4)))
        self.assertEqual(info["n"], "Work")
        self.assertTrue(info["u"].endswith(":%d" % port))
        # a second run changes nothing: same hooks, no second bootstrap
        before = open(os.path.join(self.home, ".claude", "settings.json")).read()
        r2 = self.run_setup()
        self.assertEqual(r2.returncode, 0, r2.stdout + r2.stderr)
        self.assertEqual(open(os.path.join(self.home, ".claude", "settings.json")).read(), before)
        self.assertIn("already running", r2.stdout)
        self.assertIn("already in", r2.stdout)
        self.assertIn('"Work"', r2.stdout)   # name and port are kept
        self.assertEqual(open(os.path.join(self.home, "launchctl.log")).read().count("bootstrap"), 1)

    def test_no_clipboard_tool_and_no_settings_file(self):
        port = free_port()
        os.remove(os.path.join(self.bin, "pbcopy"))
        env = dict(self.env, PATH=self.bin + os.pathsep + os.pathsep.join(p for p in os.environ["PATH"].split(os.pathsep) if not os.path.exists(os.path.join(p, "pbcopy"))))
        r = self.run_setup("--name", "A", "--port", str(port), env=env)
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertFalse(os.path.exists(os.path.join(self.home, ".claude", "settings.json.bak")))
        self.assertTrue(self.settings()["hooks"]["Stop"])
        self.assertNotIn("clipboard", r.stdout)

    def test_second_bridge_gets_next_port_and_first_name(self):
        s = socket.socket(); s.bind(("0.0.0.0", 0)); first = s.getsockname()[1]
        try:
            r = self.run_setup(env=dict(self.env, INVISIBLE_PORT_FROM=str(first)))
        finally:
            s.close()
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        plist = os.path.join(self.home, "Library", "LaunchAgents", "com.invisible.bridge.plist")
        self.assertNotEqual(setup_tools.plist_get(plist, "--port"), str(first))
        first_name = (subprocess.run(["id", "-F"], capture_output=True, text=True).stdout.split() or [""])[0] or \
            subprocess.run(["id", "-un"], capture_output=True, text=True).stdout.strip()
        self.assertEqual(setup_tools.plist_get(plist, "--name"), first_name)   # the person's first name: short to hear

    def test_unreadable_checkout_says_one_line(self):
        d = tempfile.mkdtemp(); self.addCleanup(shutil.rmtree, d, True)
        copy = os.path.join(d, "bridge"); shutil.copytree(HERE, copy, ignore=shutil.ignore_patterns("__pycache__", "research"))
        os.chmod(os.path.join(copy, "hook.py"), 0)
        r = subprocess.run(["sh", os.path.join(copy, "setup.sh")], env=self.env, capture_output=True, text=True)
        os.chmod(os.path.join(copy, "hook.py"), 0o644)
        if os.geteuid() == 0:
            self.skipTest("root can read everything")
        self.assertNotEqual(r.returncode, 0)
        self.assertEqual(len(r.stdout.strip().splitlines()), 1)
        self.assertIn("/Users/Shared", r.stdout)


class ToolsTest(unittest.TestCase):
    def test_merge_keeps_and_is_idempotent(self):
        d = tempfile.mkdtemp(); self.addCleanup(shutil.rmtree, d, True)
        p = os.path.join(d, "s.json")
        self.assertEqual(len(setup_tools.merge_hooks(p, "python3 /x/hook.py")), 4)
        self.assertEqual(setup_tools.merge_hooks(p, "python3 /x/hook.py"), [])
        self.assertEqual(len(setup_tools.merge_hooks(p, "python3 /y/hook.py")), 4)   # another checkout adds its own entries

    def test_bad_settings_untouched(self):
        d = tempfile.mkdtemp(); self.addCleanup(shutil.rmtree, d, True)
        p = os.path.join(d, "s.json"); open(p, "w").write("{broken")
        with self.assertRaises(ValueError):
            setup_tools.merge_hooks(p, "python3 /x/hook.py")
        self.assertEqual(open(p).read(), "{broken")

    def test_freeport(self):
        s = socket.socket(); s.bind(("0.0.0.0", 0)); p = s.getsockname()[1]
        try:
            self.assertGreater(setup_tools.free_port(p), p)
        finally:
            s.close()

    def test_plist_get_reads_a_systemd_unit(self):
        d = tempfile.mkdtemp()
        unit = os.path.join(d, "invisible-bridge.service")
        open(unit, "w").write("[Service]\nExecStart=/usr/bin/python3 /opt/b/server.py --name Work PC --port 8766\n".replace("Work PC", "'Work PC'"))
        self.assertEqual(setup_tools.plist_get(unit, "--name"), "Work PC")
        self.assertEqual(setup_tools.plist_get(unit, "--port"), "8766")
        self.assertEqual(setup_tools.plist_get(os.path.join(d, "none.service"), "--port"), "")
        shutil.rmtree(d)


if __name__ == "__main__":
    unittest.main()

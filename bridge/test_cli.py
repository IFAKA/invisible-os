"""Tests for bin/invisible. A temp HOME and fake launchctl, pbcopy and tmux on PATH: the real ~/.claude and the real launchd are never touched."""
import importlib.machinery
import importlib.util
import json
import os
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
CLI = os.path.join(os.path.dirname(HERE), "bin", "invisible")
sys.path.insert(0, HERE)
import server  # noqa: E402
import setup_tools  # noqa: E402
from test_setup import FAKE_LAUNCHCTL, FAKE_PBCOPY, free_port  # noqa: E402


class CliBase(unittest.TestCase):
    def setUp(self):
        self.home = tempfile.mkdtemp(prefix="cli-test-")
        self.bin = tempfile.mkdtemp(prefix="cli-bin-")
        for name, body in (("launchctl", FAKE_LAUNCHCTL), ("pbcopy", FAKE_PBCOPY), ("tmux", "#!/bin/sh\nexit 1\n")):
            p = os.path.join(self.bin, name)
            open(p, "w").write(body)
            os.chmod(p, 0o755)
        self.env = dict(os.environ, HOME=self.home, PATH=self.bin + os.pathsep + os.environ["PATH"],
                        INVISIBLE_BIN_DIR=os.path.join(self.home, "bin"))
        self.env.pop("INVISIBLE_BRIDGE_URL", None)

    def tearDown(self):
        try:
            os.kill(int(open(os.path.join(self.home, "fake.pid")).read()), signal.SIGTERM)
        except (OSError, ValueError):
            pass
        shutil.rmtree(self.home, ignore_errors=True)
        shutil.rmtree(self.bin, ignore_errors=True)

    def cli(self, *args, stdin=None):
        return subprocess.run([sys.executable, CLI, *args], env=self.env, capture_output=True, text=True, timeout=120, input=stdin)

    def path(self, *p):
        return os.path.join(self.home, *p)


class HelpAndStatus(CliBase):
    def test_help_lists_the_commands(self):
        r = self.cli()
        self.assertEqual(r.returncode, 0)
        for w in ("pair", "status", "logs", "uninstall", "new NAME", "sim NAME", "install NAME", "Start here:  invisible pair"):
            self.assertIn(w, r.stdout)

    def test_unknown_command(self):
        r = self.cli("bogus")
        self.assertEqual(r.returncode, 2)
        self.assertIn("Unknown command", r.stdout)

    def test_status_before_setup(self):
        r = self.cli("status")
        self.assertEqual(r.returncode, 0)
        self.assertIn("Not set up yet", r.stdout)
        self.assertIn("hooks not installed", r.stdout)

    def test_bad_names(self):
        for cmd in ("new", "sim", "install"):
            self.assertEqual(self.cli(cmd, "Bad1").returncode, 2)

    def test_uninstall_with_nothing(self):
        r = self.cli("uninstall", "--yes")
        self.assertEqual(r.returncode, 0)
        self.assertIn("Nothing to remove", r.stdout)


class PairedMarker(unittest.TestCase):
    def test_phone_calls_write_the_marker_but_the_laptop_does_not(self):
        d = tempfile.mkdtemp()
        f = os.path.join(d, "phone_seen")
        try:
            for path in ("/ping", "/ask", "/notify", "/cancel"):
                server._seen_last[0] = 0
                self.assertFalse(server.mark_seen(path, {}, f), path)
            server._seen_last[0] = 0
            self.assertFalse(server.mark_seen("/rooms", {"X-Invisible-Client": "cli"}, f))
            self.assertFalse(os.path.exists(f))
            server._seen_last[0] = 0
            before = time.time()
            self.assertTrue(server.mark_seen("/events", {"X-Phone": "Pixel"}, f))
            d1 = json.load(open(f))
            self.assertGreaterEqual(d1["t"], before)
            self.assertEqual(d1["name"], "Pixel")
            self.assertFalse(server.mark_seen("/events", {}, f))   # throttled: at most one write per 2 s
        finally:
            shutil.rmtree(d, ignore_errors=True)


class FullRun(CliBase):
    def test_pair_status_phone_uninstall(self):
        port = free_port()
        os.makedirs(self.path(".claude"))
        mine = {"model": "x", "hooks": {"Stop": [{"hooks": [{"type": "command", "command": "echo mine"}]}]}}
        json.dump(mine, open(self.path(".claude", "settings.json"), "w"))
        r = self.cli("pair", "--no-wait", "--name", "Work", "--port", str(port))
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        self.assertIn("python3", r.stdout)
        self.assertIn("invisible-pair:", r.stdout)
        cfg = self.path(".config", "invisible-bridge")
        m = json.load(open(os.path.join(cfg, "installed.json")))
        self.assertEqual(m["launchd_label"], "com.invisible.bridge")
        self.assertTrue(m["hooks"]["command"].endswith("hook.py"))
        link = self.path("bin", "invisible")
        self.assertEqual(m["link"], link)
        self.assertFalse(os.path.islink(link))   # a copy of the shim: shared by all users, runs each user's own copy
        self.assertIn("invisible-shim", open(link).read())
        self.assertEqual(open(os.path.join(cfg, "checkout")).read().strip(), os.path.dirname(os.path.dirname(os.path.realpath(CLI))))

        # status: running, phone not seen, hooks in
        s = self.cli("status").stdout
        self.assertIn("Bridge running", s)
        self.assertIn("Phone not seen yet", s)
        self.assertIn("hooks installed (4 events)", s)
        self.assertFalse(os.path.exists(os.path.join(cfg, "phone_seen")))   # the CLI's own calls are not the phone

        # the link works from another folder
        r = subprocess.run([link], env=self.env, capture_output=True, text=True, cwd="/")
        self.assertIn("Start here", r.stdout)

        # the phone calls: the marker appears and `pair` would say Paired
        url = open(os.path.join(cfg, "url")).read().strip()
        tok = open(os.path.join(cfg, "token")).read().strip()
        req = urllib.request.Request(url + "/events?since=0&wait=0", headers={"Authorization": "Bearer " + tok, "X-Phone": "Pixel 9"})
        urllib.request.urlopen(req, timeout=5).read()
        s = self.cli("status").stdout
        self.assertIn("Phone last seen", s)
        self.assertIn("Pixel 9", s)
        loader = importlib.machinery.SourceFileLoader("invisible_cli", CLI)
        spec = importlib.util.spec_from_loader("invisible_cli", loader)
        mod = importlib.util.module_from_spec(spec)
        old = os.environ.get("HOME")
        os.environ["HOME"] = self.home
        try:
            loader.exec_module(mod)
            self.assertEqual(mod.wait_for_phone(time.time() - 60, 2), 0)
            self.assertEqual(mod.wait_for_phone(time.time() + 60, 0.3), 0)   # nothing newer: the paste hint
        finally:
            os.environ["HOME"] = old

        # uninstall: asks, "n" keeps everything
        r = self.cli("uninstall", stdin="n\n")
        self.assertEqual(r.returncode, 1)
        self.assertTrue(os.path.exists(os.path.join(cfg, "installed.json")))
        r = self.cli("uninstall", "--yes")
        self.assertEqual(r.returncode, 0, r.stdout + r.stderr)
        for w in ("Stopped the bridge", "Removed", "hooks from"):
            self.assertIn(w, r.stdout)
        self.assertFalse(os.path.exists(cfg))
        self.assertFalse(os.path.exists(self.path("Library", "LaunchAgents", "com.invisible.bridge.plist")))
        self.assertFalse(os.path.lexists(link))
        self.assertIn("bootout", open(self.path("launchctl.log")).read())
        # only our hooks are gone; the rest of settings.json is as it was
        self.assertEqual(json.load(open(self.path(".claude", "settings.json"))), mine)
        self.assertTrue(os.path.exists(self.path(".claude", "settings.json.bak")))
        self.assertIn("Nothing to remove", self.cli("uninstall", "--yes").stdout)

    def test_remove_hooks_keeps_foreign_entries(self):
        p = os.path.join(self.home, "s.json")
        cmd = "python3 /x/hook.py"
        data = {"hooks": {"Stop": [{"hooks": [{"command": cmd}, {"command": "echo mine"}]}, {"hooks": [{"command": cmd}]}],
                          "PermissionRequest": [{"hooks": [{"command": cmd}]}]}, "other": 1}
        json.dump(data, open(p, "w"))
        self.assertEqual(sorted(set(setup_tools.remove_hooks(p, cmd))), ["PermissionRequest", "Stop"])
        self.assertEqual(json.load(open(p)), {"hooks": {"Stop": [{"hooks": [{"command": "echo mine"}]}]}, "other": 1})
        self.assertEqual(setup_tools.remove_hooks(p, cmd), [])


if __name__ == "__main__":
    unittest.main()

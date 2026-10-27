"""Tests: python3 -m unittest -v   (from the bridge/ folder). Pure parts, then server + fake phone + hook.py end to end."""
import json
import os
import subprocess
import sys
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import core  # noqa: E402
import fake_phone  # noqa: E402
import server  # noqa: E402


class CoreTest(unittest.TestCase):
    def test_shorten(self):
        self.assertEqual(core.shorten("  a  b\nc ", 5), "a b c")
        self.assertEqual(core.shorten("one two three four", 2), "one two...")

    def test_project_name(self):
        self.assertEqual(core.project_name("/Users/x/code/my-app/"), "my-app")
        self.assertEqual(core.project_name(""), "Claude")

    def test_summarize(self):
        self.assertEqual(core.summarize("Bash", {"command": "rm -rf node_modules"}), "run rm -rf node_modules")
        self.assertEqual(core.summarize("Bash", {"command": "x", "description": "Remove build files"}), "Remove build files")
        self.assertEqual(core.summarize("Edit", {"file_path": "/a/b/Main.kt"}), "edit Main.kt")
        self.assertEqual(core.summarize("Write", {"file_path": "/a/b/new.py"}), "write new.py")
        self.assertLessEqual(len(core.summarize("Bash", {"command": " ".join(["w"] * 40)}).split()), 13)
        self.assertEqual(core.summarize("mcp__github__create_issue", {}), "use create issue")

    def test_permission_output(self):
        inp = {"tool_name": "Bash", "tool_input": {"command": "npm test"}}
        d = core.permission_output("Allow", inp)["hookSpecificOutput"]
        self.assertEqual((d["hookEventName"], d["decision"]), ("PermissionRequest", {"behavior": "allow"}))
        self.assertEqual(core.permission_output("Deny", inp)["hookSpecificOutput"]["decision"]["behavior"], "deny")
        always = core.permission_output("Always allow", inp)["hookSpecificOutput"]["decision"]
        self.assertEqual(always["updatedPermissions"][0]["rules"], [{"toolName": "Bash", "ruleContent": "npm test"}])
        sug = [{"type": "addRules", "rules": [], "behavior": "allow", "destination": "session"}]
        self.assertEqual(core.permission_output("Always allow", dict(inp, permission_suggestions=sug))["hookSpecificOutput"]["decision"]["updatedPermissions"], sug)
        self.assertIsNone(core.permission_output("what", inp))

    def test_question(self):
        tin = {"questions": [{"question": "Which?", "header": "H", "options": [{"label": "A"}, {"label": "B"}], "multiSelect": False}]}
        self.assertEqual(core.question_items(tin), [("Which?", ["A", "B"])])
        out = core.question_output(tin, {"Which?": "B"})["hookSpecificOutput"]
        self.assertEqual(out["permissionDecision"], "allow")
        self.assertEqual(out["updatedInput"]["answers"], {"Which?": "B"})
        self.assertEqual(out["updatedInput"]["questions"], tin["questions"])


class StoreTest(unittest.TestCase):
    def test_answer_and_events(self):
        s = core.Store()
        a = s.add("permission", "p", "run ls", core.PERMISSION_OPTIONS)
        s.add("done", "p", "finished")
        ev = s.events(0, 0)
        self.assertEqual([i["kind"] for i in ev["items"]], ["permission", "done"])
        self.assertFalse(s.answer(a["id"], "Nope"))
        self.assertTrue(s.answer(a["id"], 1))
        self.assertEqual(s.wait_answer(a["id"], 1), "Deny")
        self.assertEqual([i["kind"] for i in s.events(0, 0)["items"]], ["done"])   # answered asks are gone
        self.assertFalse(s.answer(a["id"], "Allow"))   # only once

    def test_timeout_cancels(self):
        s = core.Store()
        a = s.add("permission", "p", "x", core.PERMISSION_OPTIONS)
        self.assertIsNone(s.wait_answer(a["id"], 0.05))
        self.assertEqual(s.events(0, 0)["items"], [])
        self.assertFalse(s.answer(a["id"], "Allow"))

    def test_long_poll_wakes(self):
        s = core.Store()
        threading.Timer(0.1, lambda: s.add("done", "p", "hi")).start()
        t = time.monotonic()
        ev = s.events(0, 5)
        self.assertEqual(len(ev["items"]), 1)
        self.assertLess(time.monotonic() - t, 2)
        self.assertEqual(s.events(ev["seq"], 0)["items"], [])   # not delivered twice


class EndToEnd(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.token_file = os.path.join(cls.tmp, "token")
        cls.token = server.load_token(cls.token_file)
        cls.store = core.Store()
        cls.srv = server.make_server("127.0.0.1", 0, cls.store, cls.token)
        cls.url = "http://127.0.0.1:%d" % cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def hook(self, inp, url=None, timeout="5"):
        env = dict(os.environ, INVISIBLE_BRIDGE_URL=url or self.url, INVISIBLE_BRIDGE_TOKEN_FILE=self.token_file, INVISIBLE_BRIDGE_TIMEOUT=timeout)
        p = subprocess.run([sys.executable, os.path.join(HERE, "hook.py")], input=json.dumps(inp), capture_output=True, text=True, env=env, timeout=30)
        self.assertEqual(p.returncode, 0, p.stderr)
        return json.loads(p.stdout) if p.stdout.strip() else None

    def phone(self, pick):
        stop = {"v": False}
        seen = []
        t = threading.Thread(target=fake_phone.run, args=(self.url, self.token, pick, lambda: stop["v"], seen), daemon=True)
        t.start()
        return stop, seen, t

    PERM = {"hook_event_name": "PermissionRequest", "tool_name": "Bash", "cwd": "/x/demo", "tool_input": {"command": "npm test"}}

    def test_token_file_and_auth(self):
        self.assertEqual(oct(os.stat(self.token_file).st_mode & 0o777), "0o600")
        with self.assertRaises(Exception):
            fake_phone.call(self.url, "wrong", "/ping")
        self.assertTrue(fake_phone.call(self.url, self.token, "/ping")["ok"])

    def test_allow_deny_always(self):
        for want, behavior in (("Allow", "allow"), ("Deny", "deny"), ("Always allow", "allow")):
            stop, seen, t = self.phone(lambda i, w=want: w)
            out = self.hook(self.PERM)
            stop["v"] = True; t.join(5)
            self.assertEqual(out["hookSpecificOutput"]["decision"]["behavior"], behavior)
            self.assertEqual((seen[0]["project"], seen[0]["summary"], seen[0]["options"]), ("demo", "run npm test", core.PERMISSION_OPTIONS))
        self.assertIn("updatedPermissions", out["hookSpecificOutput"]["decision"])

    def test_timeout_falls_back(self):
        stop, seen, t = self.phone(lambda i: None)   # the phone hears it but never answers
        out = self.hook(self.PERM, timeout="1")
        stop["v"] = True; t.join(5)
        self.assertIsNone(out)

    def test_bridge_down_falls_back(self):
        self.assertIsNone(self.hook(self.PERM, url="http://127.0.0.1:1"))

    def test_ask_user_question(self):
        tin = {"questions": [{"question": "Which db?", "header": "DB", "multiSelect": False, "options": [{"label": "SQLite"}, {"label": "Postgres"}]}]}
        inp = {"hook_event_name": "PreToolUse", "tool_name": "AskUserQuestion", "cwd": "/x/demo", "tool_input": tin}
        stop, seen, t = self.phone(lambda i: "Postgres")
        out = self.hook(inp)
        stop["v"] = True; t.join(5)
        self.assertEqual(seen[0]["options"], ["SQLite", "Postgres", core.ON_LAPTOP])
        self.assertEqual(out["hookSpecificOutput"]["updatedInput"]["answers"], {"Which db?": "Postgres"})
        stop, seen, t = self.phone(lambda i: core.ON_LAPTOP)
        self.assertIsNone(self.hook(inp))   # asked on the laptop: no output
        stop["v"] = True; t.join(5)
        self.assertIsNone(self.hook(dict(inp, hook_event_name="PermissionRequest"), url="http://127.0.0.1:1"))

    def test_done_and_needs(self):
        seen = []
        self.hook({"hook_event_name": "Stop", "cwd": "/x/demo", "last_assistant_message": "All  done.\n" + "z" * 300})
        self.hook({"hook_event_name": "Notification", "cwd": "/x/demo", "notification_type": "idle_prompt", "message": "Claude is waiting"})
        self.hook({"hook_event_name": "Notification", "cwd": "/x/demo", "notification_type": "permission_prompt", "message": "dup"})
        items = fake_phone.call(self.url, self.token, "/events?since=0&wait=0")["items"]
        mine = [i for i in items if i["kind"] in ("done", "needs")]
        self.assertEqual([i["kind"] for i in mine][-2:], ["done", "needs"])
        self.assertTrue(mine[-2]["summary"].startswith("All done."))
        self.assertEqual(len(mine[-2]["summary"]), 200)
        self.assertNotIn("dup", [i["summary"] for i in mine])


if __name__ == "__main__":
    unittest.main()

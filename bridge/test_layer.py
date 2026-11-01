"""Tests for the push layer: python3 -m unittest test_layer -v   (from bridge/)."""
import json
import os
import sys
import tempfile
import threading
import time
import unittest
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import core  # noqa: E402
import idle  # noqa: E402
import layer  # noqa: E402
import line  # noqa: E402
import server  # noqa: E402
from rooms import ACTION_TEXT  # noqa: E402


def room(**kw):
    r = dict(id="%3", name="Polish", project="invisible-os", path="/x", kind="claude", state="needs", need="", options=[],
             actions=["go", "yes", "commit", "push", "stop"], story="", go="")
    r.update(kw)
    return r


class AnswersTest(unittest.TestCase):
    def ab(self, r):
        return layer.room_answers(r, ACTION_TEXT)

    def test_quoted_option_first(self):
        r = room(options=["go"], actions=["option:go", "go", "yes", "commit"], go="commit the fix")
        a, b = self.ab(r)
        self.assertEqual(a, {"label": "go", "risky": False, "act": {"id": "%3", "action": "option:go"}})
        self.assertEqual(b["act"]["action"], "yes")   # "go" says the same as option:go, so it is skipped
        self.assertEqual(b["label"], "Yes: commit the fix")

    def test_no_option_uses_learned_order_and_go_text(self):
        a, b = self.ab(room(go="commit the fix"))
        self.assertEqual((a["act"]["action"], a["label"]), ("go", "Go: commit the fix"))
        self.assertEqual(b["act"]["action"], "yes")
        a, _ = self.ab(room(go="one two three four five six"))
        self.assertEqual(a["label"], "Go")   # go text longer than 5 words is not used

    def test_risky_actions(self):
        a, b = self.ab(room(actions=["push", "clear", "go"]))
        self.assertTrue(a["risky"] and b["risky"])
        self.assertFalse(self.ab(room())[0]["risky"])

    def test_non_answers_skipped(self):
        a, b = self.ab(room(actions=["readall", "screen", "go"]))
        self.assertEqual(a["act"]["action"], "go")
        self.assertIsNone(b)

    def test_shell(self):
        a, b = self.ab(room(kind="shell", actions=["again", "make:test", "stop"]))
        self.assertEqual((a["act"]["action"], b["act"]["action"]), ("again", "stop"))

    def test_ask_mapping_and_risky(self):
        opts = ["Always allow", "Deny", "Allow"]
        a, b = layer.ask_answers("7", opts, "rm -rf build")
        self.assertEqual(a, {"label": "Allow", "risky": True, "answer": {"id": "7", "choice": "Allow"}})
        self.assertEqual(b["answer"], {"id": "7", "choice": "Deny"})
        self.assertFalse(b["risky"])
        a, b = layer.ask_answers("8", ["Yes", "No"], "ls")
        self.assertEqual((a["answer"]["choice"], b["answer"]["choice"], a["risky"]), ("Yes", "No", False))

    def test_risky_regex(self):
        for t in ("rm -rf build", "git push origin main", "git reset --hard HEAD~1", "sudo make install", "npm publish", "kubectl delete pod x",
                  "git clean -fd", "git push --force", "deploy prod", "psql -c 'drop table x'", "rmdir old"):
            self.assertTrue(layer.is_risky(t), t)
        for t in ("ls", "npm test", "git status", "read /a/b.py", "edit Main.kt", "cat platform.txt"):
            self.assertFalse(layer.is_risky(t), t)

    def test_lines(self):
        self.assertEqual(layer.room_line(room(story="Tests ran. Commit the fix?")), "invisible-os: Tests ran. Commit the fix?")
        self.assertEqual(layer.room_line(room(need="say go")), "invisible-os: say go")
        self.assertEqual(layer.room_line(room()), "invisible-os: Polish")
        long = layer.room_line(room(story=" ".join(["w"] * 30)))
        self.assertTrue("..." not in long and len(long.split()) == 1 + layer.FIT_WORDS)
        self.assertEqual(layer.ask_line("app", "rm -rf build"), "app wants to run rm -rf build")
        self.assertEqual(layer.ask_line("app", "npm run build --prefix web --silent --if-present all"), "app wants to run npm run and 6 more words")
        self.assertTrue(layer.is_risky("rm -rf build/ node_modules dist out tmp cache logs"))   # risk is judged on the full command
        self.assertEqual(layer.ask_line("app", "edit Main.kt"), "app wants to edit Main.kt")


class IdleTest(unittest.TestCase):
    OUT = '''+-o IOHIDSystem  <class IOHIDSystem>
      | "HIDIdleTime" = 2500000000
      | "HIDIdleTime" = 90000000000
'''

    def test_parse(self):
        self.assertEqual(idle.parse_hid_idle(self.OUT), 2.5)
        self.assertIsNone(idle.parse_hid_idle("nothing here"))
        self.assertIsNone(idle.parse_hid_idle(""))

    def test_active_and_cache(self):
        t, calls, val = [0.0], [0], [30.0]
        def reader():
            calls[0] += 1
            return val[0]
        i = idle.Idle(reader, clock=lambda: t[0], ttl=5)
        self.assertEqual((i.idle_s(), i.active()), (30.0, True))
        val[0] = 500.0
        t[0] = 4.9
        self.assertEqual((i.idle_s(), calls[0]), (30.0, 1))   # cached
        t[0] = 5.0
        self.assertEqual((i.idle_s(), i.active()), (500.0, False))
        self.assertEqual(calls[0], 2)

    def test_unknown(self):
        i = idle.Idle(lambda: None)
        self.assertEqual((i.idle_s(), i.active()), (None, None))
        def boom():
            raise OSError("no ioreg")
        self.assertIsNone(idle.Idle(boom).active())


class FakeRooms:
    def __init__(self):
        self.list = []
    def rooms(self, **kw):
        return [dict(r) for r in self.list]


class StoreEventsTest(unittest.TestCase):
    def test_room_needs_event(self):
        s = core.Store(active=lambda: True)
        s.add_room_event(room(options=["go"], actions=["option:go", "go", "yes"], story="Doc ready."))
        ev = s.events(0, 0)["items"][0]
        self.assertEqual((ev["line"], ev["laptop_active"], ev["state"]), ("invisible-os: Doc ready.", True, "needs"))
        self.assertEqual(ev["a"]["act"], {"id": "%3", "action": "option:go"})
        self.assertEqual(ev["b"]["act"]["action"], "yes")

    def test_room_needs_event_with_ask(self):
        ask = {"id": "7", "kind": "permission", "what": "git push", "plain": "", "options": ["Allow", "Deny", "Always allow"]}
        s = core.Store()
        s.add_room_event(room(ask=ask))
        ev = s.events(0, 0)["items"][0]
        self.assertEqual(ev["a"]["answer"], {"id": "7", "choice": "Allow"})
        self.assertTrue(ev["a"]["risky"])
        self.assertIsNone(ev["laptop_active"])

    def test_permission_item(self):
        s = core.Store(active=lambda: False)
        s.add("permission", "demo", "run ls", core.PERMISSION_OPTIONS, detail="ls")
        ev = s.events(0, 0)["items"][0]
        self.assertEqual(ev["line"], "demo wants to run ls")
        self.assertEqual((ev["a"]["answer"]["choice"], ev["b"]["answer"]["choice"], ev["a"]["risky"], ev["laptop_active"]),
                         ("Allow", "Deny", False, False))

    def test_done_event_has_worked_s_and_no_ab(self):
        s = core.Store()
        s.add_room_event(room(state="done"), worked_s=125.4)
        s.add_room_event(room(state="done", id="%4"))
        a, b = s.events(0, 0)["items"]
        self.assertEqual(a["worked_s"], 125)
        self.assertIsNone(b["worked_s"])
        self.assertNotIn("a", a)

    def test_watch_rooms_worked_s(self):
        fr, s, stop = FakeRooms(), core.Store(), threading.Event()
        fr.list = [room(state="idle")]
        th = threading.Thread(target=server.watch_rooms, args=(fr, s, stop, 0.02), daemon=True)
        th.start()
        time.sleep(0.1)
        fr.list = [room(state="working")]
        time.sleep(0.3)
        fr.list = [room(state="done")]
        time.sleep(0.2)
        stop.set(); th.join(2)
        ev = [i for i in s.events(0, 0)["items"] if i["state"] == "done"][0]
        self.assertTrue(0 <= ev["worked_s"] <= 2, ev)


class HttpTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.token = "tok"
        cls.store = core.Store(active=lambda: True)
        fake = idle.Idle(lambda: 42.0)
        cls.srv = server.make_server("127.0.0.1", 0, cls.store, cls.token, rooms=FakeRoomsFull(), idle=fake)
        cls.url = "http://127.0.0.1:%d" % cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        with open(os.path.join(cls.tmp, "url"), "w") as f:
            f.write(cls.url + "\n")
        with open(os.path.join(cls.tmp, "token"), "w") as f:
            f.write(cls.token + "\n")

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def call(self, path, body=None):
        req = urllib.request.Request(self.url + path, data=None if body is None else json.dumps(body).encode(), headers={"Authorization": "Bearer tok"})
        with urllib.request.urlopen(req, timeout=10) as r:
            return json.loads(r.read())

    def test_info_idle(self):
        got = self.call("/info")
        self.assertEqual((got["idle_s"], got["active"]), (42.0, True))

    def test_line_round_trip(self):
        got = self.call("/line", {"who": "build", "text": "Build failed on main", "options": ["Rerun", "Ignore"]})
        self.assertTrue(got["ok"])
        lid = got["id"]
        self.assertEqual(self.call("/line/answer?id=%s&wait=0.2" % lid), {"choice": None})   # short wait: item stays open
        ev = [i for i in self.call("/events?since=0&wait=0")["items"] if i["id"] == lid][0]
        self.assertEqual((ev["kind"], ev["line"], ev["laptop_active"]), ("line", "Build failed on main", True))
        self.assertEqual(ev["a"], {"label": "Rerun", "risky": False, "answer": {"id": lid, "choice": "Rerun"}})
        self.assertEqual(ev["b"]["answer"]["choice"], "Ignore")
        threading.Timer(0.2, lambda: self.call("/answer", ev["a"]["answer"])).start()
        self.assertEqual(self.call("/line/answer?id=%s&wait=5" % lid), {"choice": "Rerun"})

    def test_line_notice_and_bad(self):
        lid = self.call("/line", {"text": "Done"})["id"]
        ev = [i for i in self.call("/events?since=0&wait=0")["items"] if i["id"] == lid][0]
        self.assertEqual((ev["a"], ev["b"]), (None, None))
        with self.assertRaises(Exception):
            self.call("/line", {"text": " "})

    def test_cli(self):
        out = []
        def run():
            out.append(line.ask("Deploy?", "ci", ["Yes", "No"], 5, config_dir=self.tmp))
        t = threading.Thread(target=run)
        t.start()
        end = time.time() + 5
        item = None
        while time.time() < end and not item:
            item = next((i for i in self.call("/events?since=0&wait=0")["items"] if i["kind"] == "line" and i["line"] == "Deploy?"), None)
            time.sleep(0.05)
        self.call("/answer", item["b"]["answer"])
        t.join(6)
        self.assertEqual(out, ["No"])


class FakeRoomsFull:
    """Just enough Rooms for make_handler."""
    def rooms(self, **kw): return []
    def projects(self): return []
    def room_row(self, pane): return None
    preview = None
    ai = None
    def __getattr__(self, name):
        raise AttributeError(name)


if __name__ == "__main__":
    unittest.main()


class FitLineTest(unittest.TestCase):
    def fit(self, text, **kw):
        return layer.fit_line("p", text=text, **kw)

    def test_sources_in_order(self):
        self.assertEqual(layer.fit_line("p", need="say go", model_line="M", story="S"), "p: say go")
        self.assertEqual(layer.fit_line("p", need=" ".join(["w"] * 15), model_line="Tests failed. Commit?", story="S"), "p: Tests failed. Commit?")
        self.assertEqual(layer.fit_line("p", need="x...", model_line="a " * 15, story="Short story."), "p: Short story.")

    def test_question_at_the_end_is_kept(self):
        t = ("I ran the whole suite twice and looked at every failing case in the output. Tests ran, 3 of 12 failed. "
             "I fixed one of them in parser.py and left the rest for later. Do you want me to commit?")
        out = self.fit(t)
        self.assertTrue(out.endswith("Do you want me to commit?"), out)
        self.assertNotIn("...", out)
        self.assertLessEqual(len(out.split()) - 1, layer.FIT_WORDS)

    def test_long_asking_sentence_keeps_its_last_clause(self):
        t = "After reading all of the files in the source tree and comparing them with the notes from last week, should I commit the fix?"
        self.assertEqual(self.fit(t), "p: should I commit the fix?")

    def test_quoted_option_sentence_is_kept(self):
        out = self.fit("Build passed. Nothing else changed. Reply \"push\" when ready. Thanks.")
        self.assertIn('Reply "push" when ready.', out)
        self.assertNotIn("Thanks", out)

    def test_no_question_starts_from_the_beginning(self):
        out = self.fit("Wrote the design doc. It has 4 sections and 120 lines. " + "More text here. " * 10)
        self.assertTrue(out.startswith("p: Wrote the design doc. It has 4 sections and 120 lines."), out)
        self.assertLessEqual(len(out.split()) - 1, layer.FIT_WORDS)

    def test_markdown_removed_numbers_kept(self):
        out = self.fit("**Done.** Fixed `parser.py`, 3 of 12 tests still fail. Run `make test`?")
        self.assertNotIn("`", out)
        self.assertNotIn("*", out)
        self.assertIn("3 of 12", out)
        self.assertTrue(out.endswith("Run make test?"), out)

    def test_never_cuts_with_dots(self):
        for n in (5, 20, 40, 100):
            self.assertNotIn("...", self.fit(("word " * n).strip() + "."))
        self.assertEqual(layer.fit_line("p"), "p")

"""Tests: python3 -m unittest -v   (from the bridge/ folder). Pure parts, then server + fake phone + hook.py end to end."""
import json
import os
import shutil
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


class PairCodeTest(unittest.TestCase):
    def test_pair_code_round_trip(self):
        import base64
        code = server.pair_code("Home é", "http://100.64.0.5:8765", "tok_en-1")
        self.assertTrue(code.startswith("invisible-pair:"))
        b = code.split(":", 1)[1]
        d = json.loads(base64.urlsafe_b64decode(b + "=" * (-len(b) % 4)).decode())
        self.assertEqual(d, {"n": "Home é", "u": "http://100.64.0.5:8765", "t": "tok_en-1"})
        self.assertNotIn("\n", code)

    def test_default_name_not_empty(self):
        n = server.default_name()
        self.assertTrue(n and "." not in n)

    def test_show_token_prints_code(self):
        r = subprocess.run([sys.executable, os.path.join(HERE, "server.py"), "--show-token", "--host", "1.2.3.4", "--name", "Work", "--port", "9"],
                           capture_output=True, text=True, env=dict(os.environ, HOME=tempfile.mkdtemp()))
        self.assertEqual(r.returncode, 0)
        self.assertEqual(len(r.stdout.strip().splitlines()), 2)
        self.assertTrue(r.stdout.strip().splitlines()[1].startswith("invisible-pair:"))


class PhoneOnlineTest(unittest.TestCase):
    def test_online_only_after_a_recent_poll(self):
        now = [1000.0]
        st = core.Store(clock=lambda: now[0])
        self.assertFalse(st.phone_online())
        st.events(0, 0)
        self.assertTrue(st.phone_online())
        now[0] += core.Store.PHONE_ONLINE_SECONDS + 1
        self.assertFalse(st.phone_online())


# ---------- Rooms ----------
import json as _json  # noqa: E402
import rooms as rooms_mod  # noqa: E402

BLOCK = "Done the design.\n\n**What needs me:** say \"go\" or “keep 15”.\n"


class NeedLineTest(unittest.TestCase):
    def test_quoted_options(self):
        found, need, opts = rooms_mod.need_line('Text.\n**What needs me:** say "go" or "keep 15".')
        self.assertEqual((found, opts), (True, ["go", "keep 15"]))
        self.assertEqual(need, 'say "go" or "keep 15".')
        self.assertEqual(rooms_mod.need_line(BLOCK)[2], ["go", "keep 15"])

    def test_nothing(self):
        for t in ("What needs me: nothing.", "- **What needs me:** None", "What needs me:\nnothing from you"):
            self.assertEqual(rooms_mod.need_line(t), (True, "", []), t)

    def test_no_block_and_limits(self):
        self.assertEqual(rooms_mod.need_line("Just a reply.")[0], False)
        found, need, opts = rooms_mod.need_line('What needs me: ' + " ".join(["w"] * 40) + ' "a" "b" "c" "d"')
        self.assertLessEqual(len(need.split()), 21)
        self.assertEqual(rooms_mod.need_line('needs you: pick "a" "b" "c" "d"')[2], ["a", "b", "c"])

    def test_markdown_and_title(self):
        self.assertEqual(rooms_mod.strip_markdown("## Hi\n- **bold** `code` [l](http://x)"), "Hi\nbold code l")
        self.assertEqual(rooms_mod.clean_title("✳ Fix the thing", "w"), "Fix the thing")
        self.assertEqual(rooms_mod.clean_title("⠋ ", "win"), "win")
        self.assertEqual(rooms_mod.last_sentences("One. Two! Three? Four."), "Three? Four.")


class OrderLearningTest(unittest.TestCase):
    BASE = ["option:ship it"] + rooms_mod.CLAUDE_ACTIONS

    def test_no_picks_keeps_fixed_order(self):
        self.assertEqual(rooms_mod.learned_order(self.BASE, {}), self.BASE)

    def test_picks_move_up_but_options_and_pinned_stay(self):
        out = rooms_mod.learned_order(self.BASE, {"research": 20.0})
        self.assertEqual(out[0], "option:ship it")
        self.assertEqual(out[1], "research")
        for a in ("stop", "readall", "screen", "write"):
            self.assertEqual(out.index(a), self.BASE.index(a))
        self.assertEqual(sorted(out), sorted(self.BASE))

    def test_push_needs_much_more_than_go(self):
        self.assertEqual(rooms_mod.learned_order(self.BASE, {"push": 2.0})[1], "go")
        self.assertEqual(rooms_mod.learned_order(self.BASE, {"go": 1.0, "push": 4.5})[1], "push")
        self.assertLess(rooms_mod.learned_order(self.BASE, {"clear": 5.0}).index("clear"), self.BASE.index("clear"))

    def test_recency_weights(self):
        day = 86400.0
        w = rooms_mod.weighted_picks([(1000 * day, "go"), (1000 * day - 14 * day, "go"), (1000 * day + 5, "yes")], 1000 * day)
        self.assertAlmostEqual(w["go"], 1.5)
        self.assertAlmostEqual(w["yes"], 1.0)

    def test_shell_actions(self):
        base = ["again", "make:test", "make:build", "stop", "screen", "write"]
        self.assertEqual(rooms_mod.learned_order(base, {"make:build": 2.0}), ["again", "make:build", "make:test", "stop", "screen", "write"])


class RoomsTmuxTest(unittest.TestCase):
    """Private tmux server (-L roomstest) with `cat` panes. The owner's tmux is never touched."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.status = os.path.join(cls.tmp, "status")
        cls.panes = os.path.join(cls.tmp, "panes")
        cls.claude = os.path.join(cls.tmp, "claude")
        cls.cfg = os.path.join(cls.tmp, "cfg")
        cls.work = os.path.join(cls.tmp, "work")
        for d in (cls.status, cls.panes, os.path.join(cls.claude, "projects", "p"), cls.work):
            os.makedirs(d)
        cls.tm = ["tmux", "-L", "roomstest"]
        subprocess.run(cls.tm + ["kill-server"], capture_output=True)
        # a fake "claude" program: process name 'claude', prints a title, then echoes input
        fake = os.path.join(cls.tmp, "claude-bin")
        os.makedirs(fake)
        cls.fake_claude = os.path.join(fake, "claude")
        shutil.copy("/bin/cat", cls.fake_claude)   # macOS: a copy of cat, re-signed, so tmux shows the command name "claude"
        subprocess.run(["codesign", "-f", "-s", "-", cls.fake_claude], capture_output=True)
        cls.new_window("cl", cls.fake_claude, cls.work)
        cls.new_window("cat2", cls.fake_claude, cls.work)
        cls.new_window("shell", "bash --norc --noprofile -i", cls.work)
        time.sleep(0.5)

    @classmethod
    def new_window(cls, name, cmd, path):
        args = ["new-window", "-P", "-F", "#{pane_id} #{window_id}", "-n", name, "-c", path, cmd]
        if subprocess.run(cls.tm + ["has-session", "-t", "t"], capture_output=True).returncode != 0:
            args = ["new-session", "-d", "-s", "t", "-x", "150", "-y", "40", "-P", "-F", "#{pane_id} #{window_id}", "-n", name, "-c", path, cmd]
        out = subprocess.run(cls.tm + args, capture_output=True, text=True).stdout.split()
        setattr(cls, name, out)
        return out

    @classmethod
    def tearDownClass(cls):
        subprocess.run(cls.tm + ["kill-server"], capture_output=True)

    def make(self, llm=None, pending=None, **kw):
        return rooms_mod.Rooms(tmux="tmux -L roomstest", status_dir=self.status, panes_dir=self.panes, claude_dir=self.claude,
                               config_dir=self.cfg, history=os.path.join(self.claude, "history.jsonl"),
                               zsh_history=os.path.join(self.tmp, "zsh_history"), llm=llm, claude_cmd=self.fake_claude,
                               pending=pending, **kw)

    def set_status(self, win, line):
        open(os.path.join(self.status, win.lstrip("@")), "w").write(line + "\n")

    def transcript(self, pane, sid, text):
        """Fake the pane->session record and a transcript with one assistant message."""
        out = subprocess.run(self.tm + ["display-message", "-p", "-t", pane, "#{session_name}_#{window_index}-#{pane_index}"],
                             capture_output=True, text=True).stdout.strip()
        _json.dump({"session_id": sid}, open(os.path.join(self.panes, out + ".json"), "w"))
        with open(os.path.join(self.claude, "projects", "p", sid + ".jsonl"), "w") as f:
            f.write(_json.dumps({"type": "assistant", "isSidechain": False, "message": {"role": "assistant", "content": [{"type": "text", "text": text}]}}) + "\n")
            f.write(_json.dumps({"type": "assistant", "isSidechain": True, "message": {"role": "assistant", "content": [{"type": "text", "text": "SIDE"}]}}) + "\n")
            f.write(_json.dumps({"type": "system", "subtype": "turn_duration"}) + "\n")

    def by_id(self, rooms, pane):
        return next(r for r in rooms if r["id"] == pane)

    def capture(self, pane):
        return subprocess.run(self.tm + ["capture-pane", "-p", "-t", pane], capture_output=True, text=True).stdout

    def wait_screen(self, pane, text, secs=3):
        end = time.time() + secs
        while time.time() < end:
            if text in self.capture(pane):
                return True
            time.sleep(0.1)
        return False

    def test_state_need_options_sort(self):
        r = self.make()
        cl, cwin = self.cl
        c2, c2win = self.cat2
        self.transcript(cl, "s1", BLOCK)
        self.transcript(c2, "s2", "I did it. All good.\n**What needs me:** nothing.")
        self.set_status(cwin, "0 0 0 0 0 0 0")
        self.set_status(c2win, "1 0 0 0 0 0 0")
        rooms = r.rooms()
        a, b = self.by_id(rooms, cl), self.by_id(rooms, c2)
        self.assertEqual((a["state"], a["kind"], a["options"]), ("needs", "claude", ["go", "keep 15"]))
        self.assertEqual(a["need"], 'say "go" or “keep 15”.')
        self.assertEqual(a["actions"][:3], ["option:go", "option:keep 15", "go"])
        self.assertEqual((b["state"], b["need"], b["options"]), ("done", "", []))
        self.assertEqual(a["project"], "work")
        self.assertEqual(a["path"], os.path.realpath(self.work))
        self.assertEqual(a["name"], "cl")   # no title set by the fake: window name
        self.assertLess(rooms.index(a), rooms.index(b))   # needs before done
        # busy -> working; action_required -> needs; idle without ask stays idle
        self.set_status(cwin, "0 1 0 0 0 0 0")
        self.assertEqual(self.by_id(r.rooms(), cl)["state"], "working")
        self.set_status(cwin, "0 1 0 0 0 0 1")
        self.assertEqual(self.by_id(r.rooms(), cl)["state"], "needs")
        self.set_status(c2win, "0 0 0 0 0 0 0")
        self.assertEqual(self.by_id(r.rooms(), c2)["state"], "idle")

    def test_pending_ask_makes_needs(self):
        c2, c2win = self.cat2
        self.transcript(c2, "s3", "All done.\n**What needs me:** nothing.")
        self.set_status(c2win, "0 0 0 0 0 0 0")
        r = self.make(pending=lambda: {c2: "run npm test"})
        room = self.by_id(r.rooms(), c2)
        self.assertEqual((room["state"], room["need"]), ("needs", "run npm test"))
        st = core.Store()
        st.add("permission", "p", "run ls", core.PERMISSION_OPTIONS, pane="%99")
        self.assertEqual(st.pending_panes(), {"%99": "run ls"})

    def test_no_status_block_uses_last_sentences(self):
        c2, c2win = self.cat2
        self.transcript(c2, "s4", "First. Second. Third.")
        self.set_status(c2win, "1 0 0 0 0 0 0")
        room = self.by_id(self.make().rooms(), c2)
        self.assertEqual((room["state"], room["need"]), ("done", "Second. Third."))

    def test_llm_summary_is_async_and_falls_back(self):
        c2, c2win = self.cat2
        self.transcript(c2, "s5", "Long text. More. Final point.")
        self.set_status(c2win, "1 0 0 0 0 0 0")
        r = self.make(llm=lambda prompt: "model summary")
        self.assertEqual(self.by_id(r.rooms(), c2)["need"], "More. Final point.")   # at once: rules
        time.sleep(0.3)
        self.assertEqual(self.by_id(r.rooms(), c2)["need"], "model summary")        # next poll: model
        def down(prompt):
            raise OSError("ollama down")
        r2 = self.make(llm=down)
        self.assertEqual(self.by_id(r2.rooms(), c2)["need"], "More. Final point.")
        time.sleep(0.3)
        self.assertEqual(self.by_id(r2.rooms(), c2)["need"], "More. Final point.")

    def test_story_in_rooms_cached_and_asked(self):
        c2, c2win = self.cat2
        self.transcript(c2, "s6", "I fixed 3 bugs in build.py. Should I commit the fix?")
        self.set_status(c2win, "1 0 0 0 0 0 0")
        r = self.make(story_model=lambda p: '{"story": "Fixed 3 bugs. Commit the fix?", "go": "commit the fix"}')
        first = self.by_id(r.rooms(), c2)
        self.assertEqual((first["story"], first["go"]), ("", ""))   # /rooms never waits for the model
        got = r.story_for(c2, wait=3)
        self.assertEqual(got, {"story": "Fixed 3 bugs. Commit the fix?", "go": "commit the fix"})
        again = self.by_id(r.rooms(), c2)
        self.assertEqual((again["story"], again["go"]), ("Fixed 3 bugs. Commit the fix?", "commit the fix"))
        self.assertIsNone(r.story_for("%999999"))
        bad = self.make(story_model=lambda p: '{"story": "Fixed 9 bugs.", "go": ""}')   # invents a number: the need line is used
        self.assertEqual(bad.story_for(c2, wait=3)["story"], self.by_id(bad.rooms(), c2)["need"])

    def test_shell_failed_and_make_targets(self):
        sh, _ = self.shell
        open(os.path.join(self.work, "Makefile"), "w").write(".PHONY: a\nbuild:\n\techo\ntest-all: build\n\techo\nrare:\n\techo\nx := 1\nother:\n")
        open(os.path.join(self.tmp, "zsh_history"), "wb").write(
            b": 1:0;make test-all\n: 2:0;make test-all\nmake build\n: 3:0;make -C x other\n: 4:0;ls\n\xff\xfe bad bytes\n: 5:0;make other\n")
        self.send_raw(sh, "echo 'make: *** [build] Error 2'")
        self.assertTrue(self.wait_screen(sh, "Error 2"))
        r = self.make()
        room = self.by_id(r.rooms(), sh)
        self.assertEqual((room["kind"], room["state"]), ("shell", "failed"))
        self.assertIn("Error 2", room["need"])
        self.assertEqual(room["actions"], ["again", "make:test-all", "make:build", "make:other", "stop", "screen", "write"])
        self.send_raw(sh, "clear")
        end = time.time() + 3
        while time.time() < end and self.by_id(r.rooms(), sh)["state"] != "idle":
            time.sleep(0.1)
        self.assertEqual(self.by_id(r.rooms(), sh)["state"], "idle")

    def send_raw(self, pane, text):
        subprocess.run(self.tm + ["send-keys", "-t", pane, "-l", text])
        subprocess.run(self.tm + ["send-keys", "-t", pane, "Enter"])

    def test_send_act_and_log(self):
        c2, _ = self.cat2
        r = self.make()
        self.assertTrue(r.send(c2, "hello rooms -x"))
        self.assertTrue(self.wait_screen(c2, "hello rooms -x"))
        self.assertEqual(r.act(c2, "go"), "go")
        self.assertEqual(r.act(c2, "option:keep 15"), "keep 15")
        self.assertEqual(r.act(c2, "commit"), "commit")
        self.assertTrue(self.wait_screen(c2, "keep 15"))
        self.assertIn("commit", self.capture(c2))
        self.assertIsNone(r.act(c2, "make:build"))   # shell action in a claude room
        self.assertIsNone(r.act(c2, "rm -rf"))
        self.assertIsNone(r.act("%99999", "go"))
        self.assertIsNone(r.act("$(x)", "go"))
        self.assertFalse(r.send("%99999", "x"))
        self.assertFalse(r.send(c2, "  "))
        log = open(os.path.join(self.cfg, "sends.log")).read()
        self.assertIn("hello rooms -x", log)
        self.assertIn("\toption:keep 15\t", log)
        self.assertIn(c2, log)
        self.assertIn("hello rooms -x", r.screen_text(c2))

    def test_stop_keys(self):
        sh, _ = self.shell
        c2, _ = self.cat2
        r = self.make()
        self.send_raw(sh, "sleep 30")
        time.sleep(0.4)
        self.assertEqual(r.act(sh, "stop"), "C-c")
        self.send_raw(sh, "echo after-ctrl-c")
        self.assertTrue(self.wait_screen(sh, "after-ctrl-c"))
        self.assertNotIn("sleep 30\n\n", self.capture(sh).split("echo after")[0][-5:])   # the prompt came back
        self.assertEqual(r.act(c2, "stop"), "Escape")   # cat just ignores Escape
        # again = Up + Enter reruns the previous command
        self.send_raw(sh, "echo AGAIN$((1+1))")
        self.assertTrue(self.wait_screen(sh, "AGAIN2"))
        r.act(sh, "again")
        end = time.time() + 3
        while time.time() < end and self.capture(sh).count("AGAIN2") < 2:
            time.sleep(0.1)
        self.assertGreaterEqual(self.capture(sh).count("AGAIN2"), 2)

    def test_make_target_send(self):
        sh, _ = self.shell
        open(os.path.join(self.work, "Makefile"), "w").write("hi:\n\t@echo MADE-IT\n")
        r = self.make()
        self.assertEqual(r.act(sh, "make:hi"), "make hi")
        self.assertTrue(self.wait_screen(sh, "MADE-IT"))
        self.assertIsNone(r.act(sh, "make:nope"))
        self.assertIsNone(r.act(sh, "make:hi; rm x"))

    def test_open_project_and_projects(self):
        hist = os.path.join(self.claude, "history.jsonl")
        proj_a, proj_b = os.path.join(self.tmp, "proj-a"), os.path.join(self.tmp, "proj-b")
        os.makedirs(proj_a); os.makedirs(proj_b)
        with open(hist, "w") as f:
            for p, n in ((proj_a, 3), (proj_b, 5), ("/does/not/exist", 9)):
                for _ in range(n):
                    f.write(_json.dumps({"display": "x", "project": p}) + "\n")
            f.write("not json\n")
        r = self.make()
        self.assertEqual([(p["name"], p["uses"]) for p in r.projects()], [("proj-b", 5), ("proj-a", 3)])
        pane = r.open_project(proj_a, "continue")
        self.assertRegex(pane, r"^%\d+$")
        self.assertIsNone(r.open_project("/does/not/exist", "new"))
        self.assertIsNone(r.open_project(proj_a, "bad"))
        time.sleep(0.3)
        self.assertEqual(self.by_id(self.make().rooms(), pane)["project"], "proj-a")

    def test_picks_reorder_actions(self):
        c, win = self.cl
        self.set_status(win, "0 0 0 0 0 0 0")
        r = self.make()
        before = self.by_id(r.rooms(), c)["actions"]
        self.assertEqual(before, rooms_mod.CLAUDE_ACTIONS)
        os.makedirs(self.cfg, exist_ok=True)
        now = time.strftime("%Y-%m-%d %H:%M:%S")
        with open(os.path.join(self.cfg, "sends.log"), "a") as f:
            for _ in range(8):
                f.write("%s\t%%99\tresearch\t\"x\"\t%s\n" % (now, self.work))   # other pane, same project path
        after = self.by_id(self.make().rooms(), c)["actions"]
        self.assertEqual(after[0], "research")
        self.assertEqual(sorted(after), sorted(before))

    def test_areas_and_new_project(self):
        area_a, area_b = os.path.join(self.tmp, "areaA"), os.path.join(self.tmp, "areaB")
        projs = [os.path.join(area_a, "p1"), os.path.join(area_a, "p2"), os.path.join(area_b, "q1")]
        for p in projs:
            os.makedirs(p)
        with open(os.path.join(self.claude, "history.jsonl"), "w") as f:
            for p, n in zip(projs, (2, 3, 4)):
                for _ in range(n):
                    f.write(_json.dumps({"display": "x", "project": p}) + "\n")
        r = self.make()
        self.assertEqual([(a["name"], a["uses"]) for a in r.areas()], [("areaA", 5), ("areaB", 4)])
        self.assertEqual(rooms_mod.Rooms.slug("  My Great Game!! "), "my-great-game")
        self.assertEqual(rooms_mod.Rooms.slug("x" * 60), "x" * 40)
        self.assertEqual(rooms_mod.Rooms.slug("!!!"), "")
        self.assertIsNone(r.new_project("/tmp", "evil"))                       # not an area
        self.assertIsNone(r.new_project(area_a + "/../areaB", "evil"))
        self.assertIsNone(r.new_project(area_a, "!!!"))                        # empty slug
        self.assertIsNone(r.new_project(area_a, "p1"))                         # exists
        r.ask_delay = 0.5
        made = r.new_project(area_a, "My New Game", "hello first")
        self.assertEqual(made["path"], os.path.join(area_a, "my-new-game"))
        self.assertRegex(made["pane"], r"^%\d+$")
        self.assertTrue(os.path.isdir(os.path.join(made["path"], ".git")))
        self.assertTrue(self.wait_screen(made["pane"], "hello first", 5))
        self.assertIn("\tnew\t", open(os.path.join(self.cfg, "sends.log")).read())

    def test_http_endpoints_and_auth(self):
        c2, c2win = self.cat2
        self.transcript(c2, "s6", "Hello **there**.\n**What needs me:** say \"go\".")
        self.set_status(c2win, "0 0 0 0 0 0 0")
        store = core.Store()
        srv = server.make_server("127.0.0.1", 0, store, "tok", self.make(pending=store.pending_panes))
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        url = "http://127.0.0.1:%d" % srv.server_address[1]
        try:
            with self.assertRaises(Exception):
                fake_phone.call(url, "bad", "/rooms")
            with self.assertRaises(Exception):
                fake_phone.call(url, "bad", "/room/act", {"id": c2, "action": "go"})
            data = fake_phone.call(url, "tok", "/rooms")
            self.assertIn("seq", data)
            self.assertEqual(self.by_id(data["rooms"], c2)["options"], ["go"])
            self.assertEqual(fake_phone.call(url, "tok", "/room/last?id=" + c2.replace("%", "%25"))["text"], 'Hello there.\nWhat needs me: say "go".')
            self.assertIn("text", fake_phone.call(url, "tok", "/room/screen?id=" + c2.replace("%", "%25")))
            self.assertEqual(fake_phone.call(url, "tok", "/room/act", {"id": c2, "action": "yes"}), {"ok": True, "said": "yes"})
            self.assertEqual(fake_phone.call(url, "tok", "/room/send", {"id": c2, "text": "via http"}), {"ok": True})
            self.assertTrue(self.wait_screen(c2, "via http"))
            self.assertIsInstance(fake_phone.call(url, "tok", "/projects")["projects"], list)
            self.assertIsInstance(fake_phone.call(url, "tok", "/areas")["areas"], list)
            with self.assertRaises(Exception):
                fake_phone.call(url, "tok", "/project/new", {"area": "/tmp", "name": "x"})
            with self.assertRaises(Exception):
                fake_phone.call(url, "tok", "/room/act", {"id": c2, "action": "bogus"})
            # a room turning 'needs' reaches /events
            self.set_status(c2win, "0 0 0 0 0 0 1")
            stop = threading.Event()
            rooms_obj = self.make()
            self.set_status(c2win, "0 0 0 0 0 0 0")
            self.transcript(c2, "s7", "ok.\n**What needs me:** nothing.")
            t = threading.Thread(target=server.watch_rooms, args=(rooms_obj, store, stop, 0.1), daemon=True)
            t.start()
            time.sleep(0.4)
            self.set_status(c2win, "0 0 0 0 0 0 1")
            ev = fake_phone.call(url, "tok", "/events?since=0&wait=5")
            stop.set()
            mine = [i for i in ev["items"] if i.get("type") == "room" and i["id"] == c2]
            self.assertEqual((mine[0]["state"], mine[0]["name"]), ("needs", "cat2"))
            # a room going from working to done reaches /events too (the phone says "<name>, done")
            self.set_status(c2win, "0 0 0 0 0 0 0")
            time.sleep(0.5)
            stop2 = threading.Event()
            rooms2 = self.make()
            self.set_status(c2win, "0 1 0 0 0 0 0")
            t2 = threading.Thread(target=server.watch_rooms, args=(rooms2, store, stop2, 0.1), daemon=True)
            t2.start()
            time.sleep(0.4)
            self.set_status(c2win, "1 0 0 0 0 0 0")
            time.sleep(0.6)
            stop2.set()
            ev2 = fake_phone.call(url, "tok", "/events?since=0&wait=1")
            self.assertTrue([i for i in ev2["items"] if i.get("type") == "room" and i["id"] == c2 and i["state"] == "done"])
        finally:
            srv.shutdown()

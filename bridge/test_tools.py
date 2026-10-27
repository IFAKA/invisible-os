"""Tests for tools.py: every tool with fake runners (no git, glab, acli, network or model is touched). Run: python3 -m unittest test_tools"""
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import tools  # noqa: E402


class Fake:
    """A fake run(): the first rule whose words are all in the command wins. Records every command."""
    def __init__(self, rules):
        self.rules, self.calls = rules, []

    def __call__(self, cmd, cwd=None, timeout=30):
        self.calls.append(list(cmd))
        line = " ".join(cmd)
        for words, ans in self.rules:
            if all(w in line for w in words.split("|")):
                return ans
        return 1, ""

    def ran(self, word):
        return [c for c in self.calls if word in " ".join(c)]


def mk(rules, **kw):
    f = Fake(rules)
    return tools.Tools(run=f, **kw), f


class ListTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()

    def ids(self, t, profile, path=None):
        return [g["id"] for g in t.tool_list(path or self.d, profile)]

    def test_personal_is_git_only(self):
        t, _ = mk([])
        self.assertEqual(self.ids(t, "personal"), ["git"])
        kids = t.tool_list(self.d, "personal")[0]["children"]
        self.assertEqual([(k["id"], k["confirm"]) for k in kids],
                         [("git.status", False), ("git.pull", False), ("git.switch", False), ("git.commit", True), ("git.push", True)])

    def test_work_packs_and_dw(self):
        t, _ = mk([])
        self.assertEqual(self.ids(t, "work"), ["git", "mr", "jira", "research"])
        with open(os.path.join(self.d, "dw.json"), "w") as f:
            json.dump({"hostname": "dev.example.com", "username": "u", "password": "SECRET"}, f)
        self.assertEqual(self.ids(t, "work"), ["git", "mr", "jira", "research", "cc"])
        cc = t.tool_list(self.d, "work")[-1]["children"]
        self.assertEqual([k["id"] for k in cc], ["cc.logs"])          # one instance: no switch
        self.assertNotIn("cc", self.ids(t, "personal", self.d))      # personal never gets it
        self.assertNotIn("SECRET", json.dumps(t.tool_list(self.d, "work")))

    def test_dw_in_parent_folder(self):
        sub = os.path.join(self.d, "a", "b"); os.makedirs(sub)
        with open(os.path.join(self.d, "dw.json"), "w") as f:
            f.write("{}")
        self.assertEqual(tools.find_dw(sub), os.path.join(self.d, "dw.json"))

    def test_suggest(self):
        t, _ = mk([])
        other = tempfile.mkdtemp()
        self.assertEqual(t.suggest([self.d, other]), "personal")
        with open(os.path.join(other, "dw.json"), "w") as f:
            f.write("{}")
        self.assertEqual(t.suggest([self.d, other]), "work")

    def test_not_offered_not_run(self):
        t, f = mk([])
        r = t.call("%1", self.d, "personal", "mr.make")
        self.assertFalse(r["ok"]); self.assertEqual(f.calls, [])
        self.assertFalse(t.call("%1", self.d, "work", "cc.logs")["ok"])      # no dw.json here
        self.assertFalse(t.call("%1", "/no/such/folder", "work", "git.status")["ok"])


class GitTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()

    def test_status(self):
        t, _ = mk([("status", (0, "## feat/x...origin/feat/x [ahead 2, behind 1]\n M a.py\n?? b.py\n"))])
        self.assertEqual(t.call("%1", self.d, "personal", "git.status")["story"], "Branch feat/x. 2 changed files. Ahead 2. Behind 1.")

    def test_status_clean_and_not_repo(self):
        t, _ = mk([("status", (0, "## main...origin/main\n"))])
        self.assertEqual(t.call("%1", self.d, "personal", "git.status")["story"], "Branch main. No changes.")
        t, _ = mk([("status", (128, "fatal"))])
        self.assertFalse(t.call("%1", self.d, "personal", "git.status")["ok"])

    def test_pull(self):
        t, f = mk([("pull", (0, "Already up to date.\n"))])
        self.assertEqual(t.call("%1", self.d, "personal", "git.pull")["story"], "Already up to date.")
        self.assertIn("--ff-only", f.ran("pull")[0])
        t, _ = mk([("pull", (1, "x\nfatal: Not possible to fast-forward\n"))])
        r = t.call("%1", self.d, "personal", "git.pull")
        self.assertFalse(r["ok"]); self.assertIn("fast-forward", r["story"])

    def test_switch_lists_then_switches(self):
        t, f = mk([("for-each-ref", (0, "main\nfeat/x\nfix-1\n")), ("rev-parse", (0, "main\n")), ("switch", (0, ""))])
        r = t.call("%1", self.d, "personal", "git.switch")
        self.assertEqual([i["label"] for i in r["items"]], ["feat/x", "fix-1"])
        self.assertTrue(all(i["confirm"] for i in r["items"]))
        self.assertEqual(f.ran("switch"), [])
        self.assertEqual(t.call("%1", self.d, "personal", "git.switch", "fix-1")["story"], "Switched to fix-1.")
        self.assertEqual(f.ran("switch")[0][-1], "fix-1")
        self.assertFalse(t.call("%1", self.d, "personal", "git.switch", "--detach")["ok"])   # not a listed branch

    def test_commit_message_model_and_fallback(self):
        rules = [("diff|--stat", (0, " a.py | 2 +-\n 1 file changed")), ("--name-only", (0, "src/a.py\n")),
                 ("diff|--unified", (0, "-old\n+new in a.py\n")), ("commit", (0, ""))]
        t, f = mk(rules, model=lambda p: '{"message": "Fix the a.py loop"}')
        r = t.call("%1", self.d, "personal", "git.commit")
        self.assertEqual(r["story"], "Message: Fix the a.py loop.")
        self.assertEqual([i["label"] for i in r["items"]], ["Create", "Cancel"])
        self.assertTrue(r["items"][0]["confirm"]); self.assertEqual(f.ran("commit"), [])
        # the model invents a name: fallback from the file names
        t, _ = mk(rules, model=lambda p: '{"message": "Fix Kubernetes login"}')
        self.assertEqual(t.call("%1", self.d, "personal", "git.commit")["story"], "Message: Update a.py.")
        # model down
        def boom(p): raise OSError("down")
        t, _ = mk(rules, model=boom)
        self.assertEqual(t.call("%1", self.d, "personal", "git.commit")["story"], "Message: Update a.py.")

    def test_commit_nothing_and_do(self):
        t, _ = mk([("diff", (0, ""))])
        self.assertEqual(t.call("%1", self.d, "personal", "git.commit")["story"], "Nothing to commit.")
        t, f = mk([("commit", (0, ""))])
        self.assertEqual(t.call("%1", self.d, "personal", "git.commit.do", "Fix  it")["story"], "Committed.")
        self.assertEqual(f.ran("commit")[0][-3:], ["commit", "-a", "-m"][:0] + f.ran("commit")[0][-3:])
        self.assertEqual(f.ran("commit")[0][-1], "Fix it")
        self.assertFalse(t.call("%1", self.d, "personal", "git.commit.do", "  ")["ok"])

    def test_push_sets_upstream_when_missing(self):
        t, f = mk([("rev-parse", (128, "")), ("push", (0, ""))])
        self.assertEqual(t.call("%1", self.d, "personal", "git.push")["story"], "Pushed.")
        self.assertEqual(f.ran("push")[0], ["git", "push", "-u", "origin", "HEAD"])
        t, f = mk([("rev-parse", (0, "origin/x")), ("push", (1, "rejected\n"))])
        r = t.call("%1", self.d, "personal", "git.push")
        self.assertFalse(r["ok"]); self.assertEqual(f.ran("push")[0], ["git", "push"])


class MrTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        tpl = os.path.join(self.d, ".gitlab", "merge_request_templates"); os.makedirs(tpl)
        with open(os.path.join(tpl, "Default.md"), "w") as f:
            f.write("## What\n\n## Why\n")
        self.rules = [("symbolic-ref", (0, "origin/develop\n")), ("rev-parse|--show-toplevel", (0, self.d + "\n")),
                      ("rev-parse|--abbrev-ref|HEAD", (0, "feat/x\n")), ("log", (0, "Add retry to fetch\nFix timeout\n")),
                      ("diff|--stat", (0, " fetch.py | 4 ++--\n 1 file changed")), ("glab", (0, "https://gitlab.example/mr/5\n"))]

    def test_make_edit_create(self):
        model = lambda p: json.dumps({"title": "Add retry to fetch", "description": "## What\nRetry in fetch.py\n## Why\nFix timeout"})
        t, f = mk(self.rules, model=model)
        r = t.call("%1", self.d, "work", "mr.make")
        self.assertTrue(r["story"].startswith("Merge request: Add retry to fetch. 2 commits, 1 file."))
        self.assertEqual([i["label"] for i in r["items"]], ["Create", "Edit description", "Cancel"])
        self.assertTrue(r["items"][0]["confirm"] and r["items"][1]["write"])
        self.assertIn("origin/develop..HEAD", " ".join(f.ran("log")[0]))
        self.assertEqual(f.ran("glab"), [])
        r = t.call("%1", self.d, "work", "mr.edit", "Only the retry")
        self.assertIn("Only the retry", r["story"])
        r = t.call("%1", self.d, "work", "mr.create")
        self.assertTrue(r["ok"]); self.assertIn("https://gitlab.example/mr/5", r["story"])
        cmd = f.ran("glab")[0]
        self.assertEqual(cmd[:3], ["glab", "mr", "create"])
        self.assertEqual(cmd[cmd.index("--description") + 1], "Only the retry")
        self.assertEqual((cmd[cmd.index("--target-branch") + 1], cmd[cmd.index("--source-branch") + 1]), ("develop", "feat/x"))
        self.assertFalse(t.call("%1", self.d, "work", "mr.create")["ok"])   # the draft is used once

    def test_fallback_when_model_invents(self):
        model = lambda p: json.dumps({"title": "Migrate Kubernetes to 42 pods", "description": "x"})
        t, _ = mk(self.rules, model=model)
        r = t.call("%1", self.d, "work", "mr.make")
        self.assertIn("Merge request: Add retry to fetch.", r["story"])     # first commit subject
        d = t.state["%1"]["mr"]
        self.assertIn("## What", d["description"]); self.assertIn("- Fix timeout", d["description"])

    def test_no_commits_and_no_draft(self):
        t, _ = mk([("log", (0, ""))])
        self.assertIn("No commits", t.call("%1", self.d, "work", "mr.make")["story"])
        self.assertFalse(t.call("%1", self.d, "work", "mr.create")["ok"])

    def test_glab_failure(self):
        t, f = mk([r for r in self.rules if r[0] != "glab"] + [("glab", (1, "401 Unauthorized\n"))])
        t.call("%1", self.d, "work", "mr.make")
        r = t.call("%1", self.d, "work", "mr.create")
        self.assertFalse(r["ok"])

    def test_personal_cannot_create(self):
        t, f = mk(self.rules)
        self.assertFalse(t.call("%1", self.d, "personal", "mr.create")["ok"]); self.assertEqual(f.ran("glab"), [])


class JiraTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()

    def test_mine(self):
        out = json.dumps([{"key": "ABC-1", "fields": {"summary": "Fix login"}}, {"key": "ABC-22", "fields": {"summary": "Add cart"}}, {"key": "bad", "fields": {}}])
        t, f = mk([("acli", (0, out))])
        r = t.call("%1", self.d, "work", "jira.mine")
        self.assertEqual(r["story"], "2 open tickets.")
        self.assertEqual([(i["label"], i["arg"], i["tool"]) for i in r["items"]], [("ABC-1, Fix login", "ABC-1", "jira.view"), ("ABC-22, Add cart", "ABC-22", "jira.view")])
        self.assertIn("currentUser()", " ".join(f.calls[0]))

    def test_mine_missing_acli_and_empty(self):
        t, _ = mk([("acli", (127, ""))])
        r = t.call("%1", self.d, "work", "jira.mine"); self.assertFalse(r["ok"]); self.assertIn("not set up", r["story"])
        t, _ = mk([("acli", (0, "[]"))])
        self.assertEqual(t.call("%1", self.d, "work", "jira.mine")["story"], "No open tickets for you.")

    def test_view_fallback_and_model(self):
        issue = {"key": "ABC-1", "fields": {"summary": "Fix login", "description": {"type": "doc", "content": [{"type": "paragraph", "content": [{"type": "text", "text": " ".join("word%d" % i for i in range(40))}]}]},
                                           "comment": {"comments": [{"body": "Checked on staging, works."}]}}}
        t, _ = mk([("acli", (0, json.dumps(issue)))])
        r = t.call("%1", self.d, "work", "jira.view", "ABC-1")
        self.assertEqual(len(r["story"].replace("...", "").split()), 1 + 25)       # key + first 25 words
        self.assertEqual(r["items"], [tools.item("Comment", "jira.comment", "ABC-1", write=True)])
        t, _ = mk([("acli", (0, json.dumps(issue)))], model=lambda p: '{"story": "Fix login. Checked on staging, works."}')
        self.assertEqual(t.call("%1", self.d, "work", "jira.view", "ABC-1")["story"], "ABC-1. Fix login. Checked on staging, works.")
        self.assertFalse(t.call("%1", self.d, "work", "jira.view", "x; rm -rf")["ok"])

    def test_comment(self):
        t, f = mk([("acli", (0, ""))])
        self.assertEqual(t.call("%1", self.d, "work", "jira.comment", "ABC-1\nLooks good to me")["story"], "Comment added to ABC-1.")
        c = f.calls[0]
        self.assertEqual((c[c.index("--key") + 1], c[c.index("--body") + 1]), ("ABC-1", "Looks good to me"))
        self.assertFalse(t.call("%1", self.d, "work", "jira.comment", "ABC-1\n  ")["ok"])
        self.assertFalse(t.call("%1", self.d, "personal", "jira.comment", "ABC-1\nhi")["ok"])


LOG = """[2026-10-27 08:00:00.100 GMT] INFO PipelineCallServlet|1|x started
[2026-10-27 08:01:00.000 GMT] ERROR PipelineCallServlet|2|Sites-X-Site|Order-Show|PipelineCall|ab12 Order 1234 failed: timeout
[2026-10-27 09:01:00.000 GMT] ERROR PipelineCallServlet|3|Sites-X-Site|Order-Show|PipelineCall|cd34 Order 5678 failed: timeout
[2026-10-27 09:30:00.000 GMT] ERROR JobThread|4|Sites-X-Site|Pay|J|ef56 Payment declined
  at some.stack.Trace
"""


class CommerceTest(unittest.TestCase):
    def setUp(self):
        self.d = tempfile.mkdtemp()
        self.dw = os.path.join(self.d, "dw.json")

    def write(self, obj):
        with open(self.dw, "w") as f:
            json.dump(obj, f)

    def fake_http(self, calls):
        def http(url, user, pw, timeout=20):
            calls.append((url, user, pw))
            if url.endswith("/Logs/"):
                return 200, '<a href="/on/demandware.servlet/webdav/Sites/Logs/error-blade1-20261026.log">a</a><a href="error-blade1-20261027.log">b</a>'
            return (200, LOG) if url.endswith("20261027.log") else (404, "")
        return http

    def test_parse_errors_groups_digits(self):
        g = tools.parse_errors(LOG)
        self.assertEqual([(x["count"], x["first"][11:16]) for x in g], [(2, "08:01"), (1, "09:30")])
        self.assertEqual([x["count"] for x in tools.new_errors(g)], [1])     # the payment error began within the last hour

    def test_logs_story_and_more(self):
        self.write({"hostname": "dev.example.com", "username": "me", "password": "SECRET-PW"})
        calls = []
        t, _ = mk([], http=self.fake_http(calls))
        r = t.call("%1", self.d, "work", "cc.logs")
        self.assertEqual(r["story"], "2 errors, 1 new: Payment declined.")
        self.assertEqual(calls[0][0], "https://dev.example.com/on/demandware.servlet/webdav/Sites/Logs/")
        self.assertTrue(calls[1][0].endswith("error-blade1-20261027.log"))
        self.assertEqual(calls[1][1:], ("me", "SECRET-PW"))
        self.assertNotIn("SECRET-PW", json.dumps(r))
        self.assertEqual(len(r["items"]), 2)
        more = t.call("%1", self.d, "work", "cc.error", r["items"][0]["arg"])
        self.assertIn("2 times", more["story"]); self.assertNotIn("SECRET-PW", json.dumps(more))

    def test_logs_failures(self):
        self.write({"hostname": "dev.example.com", "username": "me", "password": "p"})
        t, _ = mk([], http=lambda *a, **k: (401, ""))
        r = t.call("%1", self.d, "work", "cc.logs"); self.assertFalse(r["ok"])
        self.write({"hostname": "dev.example.com"})
        t, _ = mk([], http=lambda *a, **k: self.fail("no login: no call"))
        self.assertFalse(t.call("%1", self.d, "work", "cc.logs")["ok"])

    def test_switch_multi(self):
        self.write({"configurations": [{"name": "dev", "hostname": "a", "active": True, "password": "x"}, {"name": "stg", "hostname": "b", "password": "y"}]})
        t, _ = mk([])
        kids = [k["id"] for k in t.tool_list(self.d, "work")[-1]["children"]]
        self.assertEqual(kids, ["cc.switch", "cc.logs"])
        r = t.call("%1", self.d, "work", "cc.switch")
        self.assertEqual([(i["arg"], i["confirm"]) for i in r["items"]], [("stg", True)])     # the active one is not offered
        r = t.call("%1", self.d, "work", "cc.switch", "stg")
        self.assertEqual(r["story"], "Active instance is now stg.")
        cs = json.loads(open(self.dw).read())["configurations"]
        self.assertEqual([(c["name"], c["active"]) for c in cs], [("dev", False), ("stg", True)])
        self.assertEqual(cs[0]["password"], "x")
        self.assertFalse(t.call("%1", self.d, "work", "cc.switch", "nope")["ok"])

    def test_switch_single(self):
        self.write({"hostname": "dev.example.com", "username": "me", "password": "p"})
        t, _ = mk([])
        r = t.call("%1", self.d, "work", "cc.switch")
        self.assertIn("Only one instance", r["story"]); self.assertNotIn("p\"", r["story"])
        self.assertEqual(json.loads(open(self.dw).read())["hostname"], "dev.example.com")


class ResearchTest(unittest.TestCase):
    def test_opens_a_window(self):
        seen = []
        d = tempfile.mkdtemp()
        t, _ = mk([], open_window=lambda path, cmd: seen.append((path, cmd)) or "%9", neutral_dir=os.path.join(d, "r"))
        r = t.call("%1", d, "work", "research.ask", "Which repo owns the cart?")
        self.assertTrue(r["ok"])
        path, cmd = seen[0]
        self.assertEqual(path, os.path.join(d, "r"))
        self.assertTrue(cmd.startswith("claude -p "))
        self.assertIn("Which repo owns the cart?", cmd); self.assertIn("glab", cmd); self.assertIn("read only", cmd)
        self.assertFalse(t.call("%1", d, "work", "research.ask", " ")["ok"])
        self.assertFalse(t.call("%1", d, "personal", "research.ask", "x")["ok"])


class ServerTest(unittest.TestCase):
    def test_endpoints(self):
        import core, server
        d = tempfile.mkdtemp()
        with open(os.path.join(d, "dw.json"), "w") as f:
            f.write("{}")

        class R:
            def room_row(self, p): return {"path": d} if p == "%1" else None
            def projects(self): return [{"path": d}]
        t, f = mk([("status", (0, "## main\n"))])
        srv = server.make_server("127.0.0.1", 0, core.Store(), "tok", R()) if False else None
        httpd = server.ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(core.Store(), "tok", R(), t))
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        base = "http://127.0.0.1:%d" % httpd.server_address[1]

        def call(path, body=None):
            req = urllib.request.Request(base + path, data=json.dumps(body).encode() if body is not None else None, headers={"Authorization": "Bearer tok"})
            try:
                with urllib.request.urlopen(req) as r:
                    return r.status, json.loads(r.read())
            except urllib.error.HTTPError as e:
                return e.code, json.loads(e.read())
        try:
            code, info = call("/info")
            self.assertEqual((code, info["suggest"]), (200, "work")); self.assertTrue(info["host"])
            code, lst = call("/room/tools?id=%251&profile=work")
            self.assertEqual([g["id"] for g in lst], ["git", "mr", "jira", "research", "cc"])
            self.assertEqual([g["id"] for g in call("/room/tools?id=%251")[1]], ["git"])
            self.assertEqual(call("/room/tools?id=%25nope")[0], 404)
            code, r = call("/room/tool", {"id": "%1", "tool": "git.status"})
            self.assertEqual(r["story"], "Branch main. No changes.")
            self.assertEqual(call("/room/tool", {"id": "%9", "tool": "git.status"})[0], 404)
        finally:
            httpd.shutdown()


if __name__ == "__main__":
    unittest.main()

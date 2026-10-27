"""Tests for preview.py (Show on phone): python3 -m unittest test_preview -v. Fake lsof/ps/tmux/git, a local test http server, a fake transcript."""
import base64
import json
import os
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import preview  # noqa: E402
import server  # noqa: E402
import core  # noqa: E402

LSOF = "p100\nn*:5173\np200\nn127.0.0.1:3000\nn[::1]:3000\np300\nn*:9999\np400\nn127.0.0.1:8080->127.0.0.1:5000\n"
PS = "  1 0\n 50 1\n 100 50\n 200 100\n 300 1\n 400 200\n"


class StubRooms:
    def __init__(self, path, claude_dir="/nonexistent", sid=None):
        self.path, self.claude_dir, self.sid = path, claude_dir, sid
        self.tmux_calls, self.logs = [], []

    def room_row(self, pane):
        return dict(id=pane, path=self.path, session="me", windex="1", pindex="0") if pane in ("%1", "%2") else None

    def tmux(self, *a, timeout=5):
        self.tmux_calls.append(a)
        if a[0] == "list-panes":
            return 0, "%1\t50\n%2\t300\n%9\t9000\n"
        if a[0] == "display-message":
            return 0, "me\n"
        if a[0] == "new-window":
            return 0, "%9\n"
        return 1, ""

    def session_id(self, row):
        return self.sid

    def _pick_session(self):
        return "me"

    def _log(self, *a):
        self.logs.append(a)


def fake_run(table):
    def run(cmd, cwd=None, timeout=8):
        for key, val in table.items():
            if key in " ".join(cmd):
                return val if isinstance(val, tuple) else (0, val)
        return 1, ""
    return run


class PureTest(unittest.TestCase):
    def test_lsof_and_tree(self):
        tree = preview.descendants(100, preview.parse_ps(PS))
        self.assertEqual(tree, {100, 200, 400})
        self.assertEqual(preview.parse_lsof(LSOF, tree), [(100, 5173), (200, 3000), (400, 8080)])
        self.assertEqual(preview.parse_lsof(LSOF, {300}), [(300, 9999)])

    def test_dw_json_reads_only_hostname(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(preview.dw_host(d), "")
            with open(os.path.join(d, "dw.json"), "w") as f:
                json.dump({"hostname": "dev01-eu.example.demandware.net", "username": "me", "password": "SECRET"}, f)
            self.assertEqual(preview.dw_host(d), "dev01-eu.example.demandware.net")
            with open(os.path.join(d, "dw.json"), "w") as f:
                json.dump({"hostname": "evil.com/path?x"}, f)
            self.assertEqual(preview.dw_host(d), "")

    def test_dev_script(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(preview.dev_script(d), ("", "npm"))
            with open(os.path.join(d, "package.json"), "w") as f:
                json.dump({"scripts": {"start": "node x", "build": "b"}}, f)
            self.assertEqual(preview.dev_script(d), ("start", "npm"))
            with open(os.path.join(d, "package.json"), "w") as f:
                json.dump({"scripts": {"start": "node x", "dev": "vite"}}, f)
            open(os.path.join(d, "pnpm-lock.yaml"), "w").close()
            self.assertEqual(preview.dev_script(d), ("dev", "pnpm"))

    DIFF = ("diff --git a/a.txt b/a.txt\nindex 1..2 100644\n--- a/a.txt\n+++ b/a.txt\n@@ -1,2 +1,2 @@\n keep\n-old\n+new\n+more\n"
            "diff --git a/img.png b/img.png\nBinary files a/img.png and b/img.png differ\n"
            "diff --git a/n.txt b/n.txt\nnew file mode 100644\nindex 0..3\n--- /dev/null\n+++ b/n.txt\n@@ -0,0 +1 @@\n+hello\n")

    def test_parse_diff(self):
        f = preview.parse_diff(self.DIFF)
        self.assertEqual([(x["path"], x["added"], x["removed"]) for x in f], [("a.txt", 2, 1), ("img.png", 0, 0), ("n.txt", 1, 0)])
        self.assertTrue(f[0]["patch"].startswith("@@ -1,2 +1,2 @@\n keep\n-old\n+new"))
        self.assertNotIn("+++", f[0]["patch"])
        self.assertIn("Binary files", f[1]["patch"])

    def test_parse_diff_cuts(self):
        big = "diff --git a/b b/b\n--- a/b\n+++ b/b\n@@ -0,0 +1,500 @@\n" + "\n".join("+line %d" % i for i in range(500))
        f = preview.parse_diff(big, max_patch=100)
        self.assertEqual(f[0]["added"], 500)
        self.assertLessEqual(len(f[0]["patch"]), 120)
        self.assertTrue(f[0]["patch"].endswith("... cut"))
        many = "".join("diff --git a/f%d b/f%d\n--- a/f%d\n+++ b/f%d\n@@ -1 +1 @@\n-a\n+b\n" % ((i,) * 4) for i in range(10))
        self.assertEqual(len(preview.parse_diff(many, max_files=3)), 3)


class PreviewTest(unittest.TestCase):
    def make(self, path="/tmp", **kw):
        rooms = StubRooms(path, **kw)
        pv = preview.Preview(rooms, run=fake_run({"ps -axo": PS, "lsof": LSOF}), connect=lambda p: True, sleep=lambda s: None)
        return rooms, pv

    def test_port_detection(self):
        rooms, pv = self.make()
        self.assertEqual(sorted(pv.ports_of("%1")), [3000, 5173, 8080])   # pid 400 first
        self.assertEqual(pv.port_for("%1"), 8080)   # newest process first (pid 400)
        self.assertEqual(pv.port_for("%2"), 9999)
        self.assertIsNone(pv.port_for("%9"))

    def test_info_dw_beats_dev(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "dw.json"), "w") as f:
                json.dump({"hostname": "dev.example.net", "password": "x"}, f)
            _, pv = self.make(d)
            i = pv.info("%1")
            self.assertEqual((i["live"], i["host"]), ("dw", "dev.example.net"))
            self.assertNotIn("x", json.dumps(i).replace("example", ""))
            self.assertIsNone(pv.info("%7"))

    def test_info_none_and_start_offer(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "package.json"), "w") as f:
                json.dump({"scripts": {"dev": "vite"}}, f)
            rooms = StubRooms(d)
            pv = preview.Preview(rooms, run=fake_run({"ps -axo": PS, "lsof": "p1\nn*:1\n"}), connect=lambda p: True, sleep=lambda s: None)
            i = pv.info("%1")
            self.assertEqual((i["live"], i["start"]), ("none", "dev"))
            self.assertEqual(pv.action_for(d), "preview")

    def test_action_for_and_order(self):
        with tempfile.TemporaryDirectory() as d:
            os.mkdir(os.path.join(d, ".git"))
            _, pv = self.make(d)
            self.assertEqual(pv.action_for(d), "show")
            room = {"id": "%1", "path": d, "kind": "claude"}
            self.assertEqual(pv.add_action(["go", "stop", "readall", "screen", "write"], room), ["go", "stop", "show", "readall", "screen", "write"])
        with tempfile.TemporaryDirectory() as d:
            _, pv = self.make(d)
            self.assertEqual(pv.action_for(d), "")
            self.assertEqual(pv.add_action(["go", "readall"], {"id": "%1", "path": d, "kind": "claude"}), ["go", "readall"])

    def test_start_runs_new_window_and_waits(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "package.json"), "w") as f:
                json.dump({"scripts": {"dev": "vite"}}, f)
            rooms = StubRooms(d)
            calls = {"n": 0}

            def run(cmd, cwd=None, timeout=8):
                if cmd[0] == "ps":
                    return 0, "1 0\n9000 1\n"
                calls["n"] += 1
                return 0, "p9000\nn*:5173\n" if calls["n"] > 2 else "p1\nn*:1\n"
            t = [0.0]
            pv = preview.Preview(rooms, run=run, connect=lambda p: True, sleep=lambda s: t.__setitem__(0, t[0] + s), clock=lambda: t[0])
            self.assertTrue(pv.start("%1", timeout=20))
            nw = [c for c in rooms.tmux_calls if c[0] == "new-window"][0]
            self.assertIn("npm run dev", nw)
            self.assertIn(d, nw)
            self.assertEqual(pv.port_for("%1"), 5173)

    def test_start_times_out(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, "package.json"), "w") as f:
                json.dump({"scripts": {"dev": "vite"}}, f)
            t = [0.0]
            pv = preview.Preview(StubRooms(d), run=fake_run({"ps -axo": "1 0\n", "lsof": "p1\nn*:1\n"}), sleep=lambda s: t.__setitem__(0, t[0] + s), clock=lambda: t[0])
            self.assertFalse(pv.start("%1", timeout=5))

    def test_diff_with_untracked(self):
        run = fake_run({"diff HEAD": PureTest.DIFF, "ls-files": "new.md\na.txt\n"})
        rooms = StubRooms("/tmp")
        pv = preview.Preview(rooms, run=run)
        got = pv.diff("%1")
        self.assertEqual([f["path"] for f in got["files"]], ["a.txt", "img.png", "n.txt", "new.md"])
        self.assertTrue(got["files"][3]["untracked"])
        self.assertIsNone(pv.diff("%7"))

    def test_diff_real_git(self):
        with tempfile.TemporaryDirectory() as d:
            def git(*a):
                subprocess.run(["git", "-C", d, "-c", "user.email=a@b", "-c", "user.name=n"] + list(a), check=True, capture_output=True)
            git("init", "-q")
            with open(os.path.join(d, "a.txt"), "w") as f:
                f.write("one\ntwo\n")
            git("add", "."); git("commit", "-qm", "x")
            with open(os.path.join(d, "a.txt"), "w") as f:
                f.write("one\nthree\n")
            open(os.path.join(d, "fresh.txt"), "w").close()
            got = preview.Preview(StubRooms(d)).diff("%1")
            f = {x["path"]: x for x in got["files"]}
            self.assertEqual((f["a.txt"]["added"], f["a.txt"]["removed"]), (1, 1))
            self.assertTrue(f["fresh.txt"]["untracked"])


class PicturesTest(unittest.TestCase):
    PNG = base64.b64encode(b"\x89PNG-fake").decode()

    def lines(self, img_path):
        L = [
            {"message": {"content": [{"type": "tool_use", "name": "Read", "input": {"file_path": img_path}}]}},
            {"message": {"content": [{"type": "tool_result", "content": [{"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "AAAA"}}]}]}},
            {"message": {"content": [{"type": "text", "text": "hello"}]}},
            {"message": {"content": [{"type": "tool_use", "name": "Write", "input": {"file_path": "/nope/missing.png"}}]}},
            {"message": {"content": [{"type": "image", "source": {"type": "base64", "media_type": "image/jpeg", "data": self.PNG}}]}},
        ]
        return [json.dumps(x) for x in L]

    def test_extract_and_latest(self):
        items = preview.transcript_images(self.lines("/a/shot.png"))
        self.assertEqual([i["kind"] for i in items], ["read", "seen", "written", "seen"])
        got = preview.latest_pictures(items)
        self.assertEqual([g["kind"] for g in got], ["seen", "written", "read"])   # newest first; the image block after the Read is that file
        many = [{"name": str(i), "kind": "read", "path": "/p/%d.png" % i, "media": "image/png"} for i in range(10)]
        self.assertEqual([g["name"] for g in preview.latest_pictures(many)], ["9", "8", "7", "6", "5", "4"])

    def test_end_to_end_with_fake_transcript(self):
        with tempfile.TemporaryDirectory() as d:
            img = os.path.join(d, "shot.png")
            with open(img, "wb") as f:
                f.write(b"REAL-PNG")
            proj = os.path.join(d, "projects", "x")
            os.makedirs(proj)
            with open(os.path.join(proj, "sess1.jsonl"), "w") as f:
                f.write("\n".join(self.lines(img)) + "\n")
            pv = preview.Preview(StubRooms("/tmp", claude_dir=d, sid="sess1"))
            got = pv.pictures("%1")["pictures"]
            self.assertEqual([(p["n"], p["kind"]) for p in got], [(0, "seen"), (1, "read")])   # the missing written file is dropped
            self.assertEqual(pv.picture("%1", 1), ("image/png", b"REAL-PNG"))
            self.assertEqual(pv.picture("%1", 0)[0], "image/jpeg")
            self.assertEqual(pv.picture("%1", 0)[1], b"\x89PNG-fake")
            self.assertIsNone(pv.picture("%1", 5))
            self.assertIsNone(pv.pictures("%7"))
            self.assertEqual(preview.Preview(StubRooms("/tmp", claude_dir=d, sid="nope")).pictures("%1"), {"pictures": []})


class Site(BaseHTTPRequestHandler):
    seen = []

    def log_message(self, *a):
        pass

    def do_GET(self):
        Site.seen.append((self.path, self.headers.get("Authorization"), self.headers.get("Cookie"), self.headers.get("Host")))
        if self.path.startswith("/redir"):
            self.send_response(302); self.send_header("Location", "/home"); self.send_header("Content-Length", "0"); self.end_headers(); return
        body = b"<html>hi " + self.path.encode() + b"</html>"
        ctype = "text/html" if not self.path.endswith(".js") else "application/javascript"
        self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)


class ProxyTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.site = ThreadingHTTPServer(("127.0.0.1", 0), Site)
        threading.Thread(target=cls.site.serve_forever, daemon=True).start()
        port = cls.site.server_address[1]
        rooms = StubRooms("/tmp")
        rooms.rooms = lambda max_age=0: []
        rooms.preview = preview.Preview(rooms, run=fake_run({"ps -axo": "1 0\n", "lsof": "p1\nn*:1\n"}))
        rooms.preview.port_for = lambda pane: port if pane == "%1" else None
        cls.rooms = rooms
        cls.srv = server.make_server("127.0.0.1", 0, core.Store(), "tok", rooms)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = "http://127.0.0.1:%d" % cls.srv.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown(); cls.site.shutdown()

    def get(self, path, headers=None, follow=True):
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *a, **k):
                return None
        op = urllib.request.build_opener(*([] if follow else [NoRedirect]))
        try:
            r = op.open(urllib.request.Request(self.base + path, headers=headers or {}))
            return r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers

    def test_needs_token(self):
        self.assertEqual(self.get("/preview/%253/")[0], 401)
        self.assertEqual(self.get("/preview/%253/?token=bad")[0], 401)

    def test_first_load_sets_cookies_then_forwards(self):
        code, _, h = self.get("/preview/%251/a/b?x=1&token=tok", follow=False)
        self.assertEqual(code, 302)
        self.assertEqual(h["Location"], "/preview/%251/a/b?x=1")
        cookies = h.get_all("Set-Cookie")
        self.assertTrue(any(c.startswith("inv_tok=tok; Path=/") for c in cookies))
        self.assertTrue(any(c.startswith("inv_pane=1") for c in cookies))
        Site.seen.clear()
        code, body, _ = self.get("/preview/%251/a/b?x=1", {"Cookie": "inv_tok=tok; inv_pane=1", "Authorization": "Bearer should-not-reach-site"})
        self.assertEqual(code, 200)
        self.assertEqual(body, b"<html>hi /a/b?x=1</html>")
        path, auth, cookie, host = Site.seen[-1]
        self.assertIsNone(auth); self.assertIsNone(cookie)   # the token never reaches the dev site
        self.assertTrue(host.startswith("127.0.0.1:"))

    def test_bearer_header_also_works(self):
        self.assertEqual(self.get("/preview/%251/", {"Authorization": "Bearer tok"})[0], 200)

    def test_root_paths_follow_the_cookie(self):
        code, body, _ = self.get("/assets/app.js", {"Cookie": "inv_tok=tok; inv_pane=1"})
        self.assertEqual((code, body), (200, b"<html>hi /assets/app.js</html>"))
        self.assertEqual(self.get("/assets/app.js")[0], 401)   # no cookie: not ours
        self.assertEqual(self.get("/rooms", {"Cookie": "inv_tok=tok; inv_pane=1"})[0], 401)   # the API still wants the header

    def test_redirect_is_kept_under_the_preview_path(self):
        code, _, h = self.get("/preview/%251/redir", {"Cookie": "inv_tok=tok"}, follow=False)
        self.assertEqual((code, h["Location"]), (302, "/preview/%251/home"))

    def test_no_server_is_502(self):
        self.assertEqual(self.get("/preview/%252/", {"Authorization": "Bearer tok"})[0], 502)

    def test_json_routes(self):
        auth = {"Authorization": "Bearer tok"}
        self.rooms.preview.diff = lambda pane: {"files": []} if pane == "%1" else None
        code, body, _ = self.get("/room/diff?id=%251", auth)
        self.assertEqual((code, json.loads(body)), (200, {"files": []}))
        self.assertEqual(self.get("/room/diff?id=%257", auth)[0], 404)
        self.assertEqual(self.get("/room/diff?id=%251")[0], 401)
        self.rooms.preview.picture = lambda pane, n: ("image/png", b"PNG") if n == 0 else None
        code, body, h = self.get("/room/picture?id=%251&n=0", auth)
        self.assertEqual((code, body, h["Content-Type"]), (200, b"PNG", "image/png"))
        self.assertEqual(self.get("/room/picture?id=%251&n=3", auth)[0], 404)


if __name__ == "__main__":
    unittest.main()

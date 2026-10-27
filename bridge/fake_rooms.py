#!/usr/bin/env python3
"""A tiny fake of the Rooms server (bridge/ROOMS-API.md) for tests. Same token header; fixed answers; records every POST.
  python3 bridge/fake_rooms.py [--host H] [--port 8799] [--token T]
tools/e2e.py imports it and starts it in a thread (start(); POSTS is the list of recorded (path, body))."""
import json, sys, threading, time, argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

TOKEN = "fake-token"
FIRE_DONE = []   # append a name: the next /events says that window finished
POSTS = []   # (path, parsed json body)
GETS = []   # path of every GET, in order (a test can wait for the phone to ask)

ACTIONS = ["go", "yes", "commit", "push", "readall", "screen", "write", "stop"]
ROOMS = [
    {"id": "%1", "name": "Alpha", "project": "alpha-proj", "path": "/tmp/alpha", "kind": "claude", "state": "needs",
     "need": "The design doc is ready.", "options": [], "actions": ACTIONS},
    {"id": "%2", "name": "Beta", "project": "beta-proj", "path": "/tmp/beta", "kind": "claude", "state": "working",
     "need": "", "options": [], "actions": ["stop", "readall", "screen", "write"]},
    {"id": "%3", "name": "Gamma", "project": "gamma-proj", "path": "/tmp/gamma", "kind": "shell", "state": "done",
     "need": "build finished", "options": [], "actions": ["again", "make:test", "stop", "screen", "write"]},
]
PROJECTS = [{"name": "alpha-proj", "path": "/tmp/alpha", "uses": 10}, {"name": "beta-proj", "path": "/tmp/beta", "uses": 5}]

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def _ok(self):
        if self.headers.get("Authorization") != "Bearer " + getattr(self.server, "token", TOKEN):
            self.send_response(401); self.end_headers(); return False
        return True
    def _json(self, obj, code=200):
        b = json.dumps(obj).encode()
        self.send_response(code); self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def do_GET(self):
        if not self._ok(): return
        u = urlparse(self.path); q = parse_qs(u.query); GETS.append(u.path)
        if u.path == "/rooms": self._json({"seq": 1, "rooms": getattr(self.server, "rooms", ROOMS)})
        elif u.path == "/room/last": self._json({"text": "Alpha last message. The design doc is ready."})
        elif u.path == "/room/screen": self._json({"text": "$ claude\n> working on the design doc\n  done"})
        elif u.path == "/projects": self._json({"projects": PROJECTS})
        elif u.path == "/areas": self._json({"areas": [{"name": "hardware", "path": "/tmp/fake/hardware", "uses": 9}]})
        elif u.path == "/events":
            fire = getattr(self.server, "fire", FIRE_DONE)
            if fire:
                name = fire.pop(0)
                self._json({"seq": 2, "items": [{"type": "room", "kind": "room", "id": "%7", "name": name, "state": "done",
                                                 "project": "p", "summary": "", "options": [], "seq": 2}]})
            else:
                time.sleep(min(float(q.get("wait", ["0"])[0]), 2.0)); self._json({"seq": 1, "items": []})
        else: self._json({"error": "not found"}, 404)
    def do_POST(self):
        if not self._ok(): return
        n = int(self.headers.get("Content-Length", "0") or 0)
        body = json.loads(self.rfile.read(n) or b"{}")
        POSTS.append((urlparse(self.path).path, body))
        if self.path == "/room/act": self._json({"ok": True, "said": body.get("action", "")})
        elif self.path == "/project/open": self._json({"ok": True, "id": "%9"})
        else: self._json({"ok": True})

def start(host="0.0.0.0", port=8799, token="fake-token", rooms=None, fire=None):
    """Start in a background thread; returns the server (call .shutdown() to stop).
    Each server has its own token, and optionally its own [rooms] and its own [fire] list (names of windows that finished); with several servers in one test give each its own."""
    global TOKEN
    TOKEN = token; POSTS.clear()
    srv = ThreadingHTTPServer((host, port), H); srv.daemon_threads = True
    srv.token = token
    if rooms is not None: srv.rooms = rooms
    if fire is not None: srv.fire = fire
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("--host", default="0.0.0.0"); ap.add_argument("--port", type=int, default=8799); ap.add_argument("--token", default="fake-token")
    a = ap.parse_args(); start(a.host, a.port, a.token); print(f"fake rooms on {a.host}:{a.port}"); 
    try:
        while True: time.sleep(1)
    except KeyboardInterrupt: pass

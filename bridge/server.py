#!/usr/bin/env python3
"""Invisible OS bridge server: the phone and the Claude Code hooks meet here. Python 3 stdlib only.

Phone:  GET /events?since=N&wait=25   (long poll)      POST /answer {"id": "3", "choice": "Allow"}
Rooms:  GET /rooms /room/last /room/screen /projects /areas   POST /room/act /room/send /project/open /project/new   (see ROOMS-API.md)
Hooks:  POST /ask {kind, project, summary, options, wait}  (blocks until answered)      POST /notify {kind, project, summary}
All calls need the header  Authorization: Bearer <token>.
"""
import argparse
import hmac
import json
import os
import secrets
import socket
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from core import Store  # noqa: E402
from rooms import Rooms  # noqa: E402

CONFIG_DIR = os.path.expanduser("~/.config/invisible-bridge")
TOKEN_FILE = os.path.join(CONFIG_DIR, "token")
URL_FILE = os.path.join(CONFIG_DIR, "url")   # read by hook.py, so the hook calls the address the server really listens on


def load_token(path=TOKEN_FILE):
    """Read the token, or make a new random one (file mode 600)."""
    try:
        with open(path) as f:
            t = f.read().strip()
            if t:
                return t
    except FileNotFoundError:
        pass
    os.makedirs(os.path.dirname(path), mode=0o700, exist_ok=True)
    t = secrets.token_urlsafe(24)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(t + "\n")
    return t


def default_host():
    """The Tailscale address if there is one, else the LAN address."""
    try:
        ip = subprocess.run(["tailscale", "ip", "-4"], capture_output=True, text=True, timeout=3).stdout.split()
        if ip:
            return ip[0]
    except (OSError, subprocess.SubprocessError):
        pass
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("10.255.255.255", 1))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


def make_handler(store, token, rooms):
    class H(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        def _send(self, code, obj):
            body = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def _auth(self):
            got = self.headers.get("Authorization", "")
            if hmac.compare_digest(got.encode(), ("Bearer " + token).encode()):
                return True
            self._send(401, {"error": "bad token"})
            return False

        def _body(self):
            n = int(self.headers.get("Content-Length") or 0)
            try:
                return json.loads(self.rfile.read(n) or b"{}")
            except ValueError:
                return {}

        def do_GET(self):
            if not self._auth():
                return
            u = urlparse(self.path)
            q = parse_qs(u.query)
            if u.path == "/events":
                since = int(q.get("since", ["0"])[0])
                wait = min(float(q.get("wait", ["25"])[0]), 55)
                self._send(200, store.events(since, wait))
            elif u.path == "/ping":
                self._send(200, {"ok": True})
            elif u.path == "/rooms":
                self._send(200, {"seq": store.seq, "rooms": rooms.rooms(max_age=1.0)})
            elif u.path in ("/room/last", "/room/screen"):
                pane = q.get("id", [""])[0]
                text = rooms.last_text(pane) if u.path == "/room/last" else rooms.screen_text(pane)
                if text is None:
                    self._send(404, {"error": "no such room"})
                else:
                    self._send(200, {"text": text})
            elif u.path == "/projects":
                self._send(200, {"projects": rooms.projects()})
            elif u.path == "/areas":
                self._send(200, {"areas": rooms.areas()})
            else:
                self._send(404, {"error": "not found"})

        def do_POST(self):
            if not self._auth():
                return
            b = self._body()
            path = urlparse(self.path).path
            if path == "/answer":
                ok = store.answer(b.get("id"), b.get("choice"))
                self._send(200 if ok else 409, {"ok": ok})
            elif path == "/ask":
                if not store.phone_online():   # no phone listening: answer at once so the terminal prompt shows without delay
                    self._send(200, {"id": None, "answer": None, "offline": True}); return
                item = store.add(b.get("kind", "permission"), b.get("project", "Claude"), b.get("summary", ""), b.get("options") or [], b.get("ref"), b.get("pane"))
                choice = store.wait_answer(item["id"], float(b.get("wait", 600)))
                self._send(200, {"id": item["id"], "answer": choice})
            elif path == "/notify":
                item = store.add(b.get("kind", "done"), b.get("project", "Claude"), b.get("summary", ""))
                self._send(200, {"id": item["id"]})
            elif path == "/cancel":
                store.cancel(b.get("id") or b.get("ref"))
                self._send(200, {"ok": True})
            elif path == "/room/act":
                said = rooms.act(b.get("id"), b.get("action"))
                if said is None:
                    self._send(400, {"ok": False, "error": "unknown room or action"})
                else:
                    self._send(200, {"ok": True, "said": said})
            elif path == "/room/send":
                ok = rooms.send(b.get("id"), b.get("text"))
                self._send(200 if ok else 400, {"ok": ok})
            elif path == "/project/open":
                pane = rooms.open_project(b.get("path"), b.get("mode", "continue"))
                self._send(200 if pane else 400, {"ok": bool(pane), "id": pane})
            elif path == "/project/new":
                made = rooms.new_project(b.get("area"), b.get("name"), b.get("ask"))
                self._send(200 if made else 400, dict(made or {}, ok=bool(made)))
            else:
                self._send(404, {"error": "not found"})

    return H


def watch_rooms(rooms, store, stop, every=2.0):
    """Poll the rooms; when one turns 'needs', push a room event to the phone through the store."""
    prev = None
    while not stop.is_set():
        try:
            now = {r["id"]: r for r in rooms.rooms()}
            if prev is not None:
                for pid, r in now.items():
                    if r["state"] == "needs" and (pid not in prev or prev[pid]["state"] != "needs"):
                        store.add_room_event(r)
            prev = now
        except Exception as e:   # never let the watcher die
            print("rooms watcher:", e, file=sys.stderr)
        stop.wait(every)


def make_server(host, port, store, token, rooms=None):
    rooms = rooms or Rooms.from_env(pending=store.pending_panes)
    return ThreadingHTTPServer((host, port), make_handler(store, token, rooms))


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--host", default=None, help="address to listen on (default: Tailscale or LAN address)")
    ap.add_argument("--port", type=int, default=int(os.environ.get("INVISIBLE_BRIDGE_PORT", "8765")))
    ap.add_argument("--show-token", action="store_true", help="print the token and exit")
    a = ap.parse_args()
    token = load_token()
    if a.show_token:
        print(token)
        return
    host = a.host or default_host()
    store = Store()
    rooms = Rooms.from_env(pending=store.pending_panes)
    srv = make_server(host, a.port, store, token, rooms)
    threading.Thread(target=watch_rooms, args=(rooms, store, threading.Event()), daemon=True).start()
    print("Bridge listening on http://%s:%d" % (host, a.port))
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(URL_FILE, "w") as f:
        f.write("http://%s:%d\n" % (host, a.port))
    print("Paste this address and the token into the phone app (home screen, Claude bridge).")
    print("Token (also in %s):\n  %s" % (TOKEN_FILE, token))
    sys.stdout.flush()
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()

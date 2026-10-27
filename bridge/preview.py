"""Show on phone: a window's changes on the phone screen. Python 3 stdlib only. Contract: ROOMS-API.md "Show on phone".

Four things, all for one room (a tmux pane):
  * Live      GET /preview/<pane>/<path>   a proxy to the dev server that runs in that pane (or was started by Start preview)
  * Changes   GET /room/diff?id=           `git diff HEAD` of the pane's folder, cut to a sane size, plus the untracked files
  * Pictures  GET /room/pictures?id=, GET /room/picture?id=&n=   the latest images Claude handled in that window's session
  * Info      GET /room/preview?id=        what Live can open; POST /room/preview/start {"id"} starts the dev server
Everything outside (tmux, ps, lsof, git, sockets, the clock) is injectable, so tests use fakes.
dw.json (Commerce Cloud): only its "hostname" is read. Nothing else in the file is read, kept or sent.
"""
import base64
import hmac
import http.client
import json
import mimetypes
import os
import re
import socket
import subprocess
import threading
import time
from urllib.parse import parse_qs, quote, unquote, urlparse, urlencode

MAX_FILES = 40
MAX_PATCH = 20000          # chars of one file's patch
MAX_TOTAL = 200000         # chars of all patches together
MAX_UNTRACKED = 40
MAX_PICTURES = 6
MAX_PICTURE_BYTES = 12 << 20
MAX_BODY = 25 << 20        # the biggest page or file the proxy forwards
TRANSCRIPT_TAIL = 30 << 20
IMAGE_EXT = (".png", ".jpg", ".jpeg", ".gif", ".webp")
HOST_RE = re.compile(r"^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers", "transfer-encoding", "upgrade",
       "content-length", "content-encoding", "set-cookie", "host"}
# Paths the bridge itself serves: the cookie fallback below never takes them.
API_PREFIXES = ("/events", "/ping", "/rooms", "/room/", "/projects", "/areas", "/answer", "/ask", "/notify", "/cancel", "/project/")


# ---------- pure helpers ----------

def parse_lsof(text, pids):
    """`lsof -nP -iTCP -sTCP:LISTEN -Fpn` output -> [(pid, port)] for the pids given (set of ints), in output order, no repeats."""
    out, pid = [], None
    for line in str(text).splitlines():
        if line.startswith("p") and line[1:].isdigit():
            pid = int(line[1:])
        elif line.startswith("n") and pid in pids:
            m = re.search(r":(\d+)$", line[1:].split("->")[0])
            if m and (pid, int(m.group(1))) not in out:
                out.append((pid, int(m.group(1))))
    return out


def parse_ps(text):
    """`ps -axo pid=,ppid=` -> {pid: ppid}."""
    out = {}
    for line in str(text).splitlines():
        p = line.split()
        if len(p) == 2 and p[0].isdigit() and p[1].isdigit():
            out[int(p[0])] = int(p[1])
    return out


def descendants(root, parents):
    """The pid and all its children, grandchildren... (parents = {pid: ppid})."""
    kids = {}
    for p, pp in parents.items():
        kids.setdefault(pp, []).append(p)
    seen, todo = {root}, [root]
    while todo:
        for k in kids.get(todo.pop(), []):
            if k not in seen:
                seen.add(k)
                todo.append(k)
    return seen


def dw_host(folder):
    """The host name in <folder>/dw.json ("hostname"), or "". Reads nothing else from the file."""
    try:
        with open(os.path.join(folder, "dw.json")) as f:
            h = json.load(f).get("hostname")
    except (OSError, ValueError, AttributeError):
        return ""
    h = str(h or "").strip().lower()
    return h if HOST_RE.match(h) else ""


def dev_script(folder):
    """("dev" | "start" | "", package manager) from <folder>/package.json."""
    try:
        with open(os.path.join(folder, "package.json")) as f:
            scripts = json.load(f).get("scripts") or {}
    except (OSError, ValueError, AttributeError):
        return "", "npm"
    name = next((s for s in ("dev", "start") if isinstance(scripts, dict) and scripts.get(s)), "")
    pm = "pnpm" if os.path.exists(os.path.join(folder, "pnpm-lock.yaml")) else "yarn" if os.path.exists(os.path.join(folder, "yarn.lock")) else "npm"
    return name, pm


def in_git(folder):
    d = os.path.abspath(folder)
    while True:
        if os.path.exists(os.path.join(d, ".git")):
            return True
        up = os.path.dirname(d)
        if up == d:
            return False
        d = up


def parse_diff(text, max_files=MAX_FILES, max_patch=MAX_PATCH, max_total=MAX_TOTAL):
    """`git diff` output -> [{"path","added","removed","patch"}]. Each patch is cut at max_patch chars, all together at max_total."""
    files, cur = [], None
    for line in str(text).split("\n"):
        if line.startswith("diff --git "):
            cur = {"path": "", "added": 0, "removed": 0, "lines": [], "head": line}
            files.append(cur)
            m = re.match(r"diff --git a/(.*) b/(.*)$", line)
            if m:
                cur["path"] = m.group(2)
            continue
        if cur is None:
            continue
        if line.startswith("+++ ") or line.startswith("--- "):
            if not cur.get("hunks"):
                continue   # the two file-name lines before the first hunk; the path is already known
        if line.startswith("@@"):
            cur["hunks"] = True
        if line.startswith("@@") or line[:1] in ("+", "-", " ", "\\") or line.startswith("Binary files"):
            if line.startswith("+"):
                cur["added"] += 1
            elif line.startswith("-"):
                cur["removed"] += 1
            cur["lines"].append(line)
        elif line.startswith(("rename from ", "deleted file", "new file")):
            cur["lines"].append(line)
    out, budget = [], max_total
    for f in files[:max_files]:
        patch = "\n".join(f["lines"])
        cap = max(0, min(max_patch, budget))
        if len(patch) > cap:
            patch = patch[:cap].rstrip() + "\n... cut"
        budget -= len(patch)
        out.append({"path": f["path"], "added": f["added"], "removed": f["removed"], "patch": patch})
    return out


def transcript_images(lines):
    """Image items in the transcript lines (JSON strings), oldest first: {"name","kind","path"?,"media","data"?}.
    Sources: an image block (type "image", base64) in a tool result or a user message; a Read or Write tool call of an image file."""
    out = []
    for raw in lines:
        if '"image"' not in raw and "file_path" not in raw:
            continue
        try:
            msg = json.loads(raw)
        except ValueError:
            continue
        stack = [(msg.get("message") or {}).get("content")]
        while stack:
            c = stack.pop()
            if isinstance(c, list):
                stack.extend(reversed(c))
            elif isinstance(c, dict):
                if c.get("type") == "image" and isinstance(c.get("source"), dict) and c["source"].get("type") == "base64" and c["source"].get("data"):
                    out.append({"name": "image", "kind": "seen", "media": c["source"].get("media_type") or "image/png", "data": c["source"]["data"]})
                elif c.get("type") == "tool_use" and c.get("name") in ("Read", "Write"):
                    p = str((c.get("input") or {}).get("file_path") or "")
                    if p.lower().endswith(IMAGE_EXT):
                        out.append({"name": os.path.basename(p), "kind": "read" if c["name"] == "Read" else "written", "path": p,
                                    "media": mimetypes.guess_type(p)[0] or "image/png"})
                elif isinstance(c.get("content"), (list, dict)):
                    stack.append(c["content"])
    return out


def latest_pictures(items, n=MAX_PICTURES):
    """Newest first, at most n. An image block that follows the Read of a file is that file (dropped); the same file twice counts once."""
    kept, seen = [], set()
    prev = None
    for it in items:
        if it["kind"] == "seen" and prev is not None and prev["kind"] == "read":
            prev = it
            continue   # the image block that is the answer to a Read: the file entry already stands for it
        prev = it
        key = it.get("path") or it["data"][:200]
        kept = [k for k in kept if (k.get("path") or k["data"][:200]) != key]
        kept.append(it)
        seen.add(key)
    return list(reversed(kept))[:n]


# ---------- the object the server uses ----------

class Preview:
    def __init__(self, rooms, run=None, connect=None, sleep=time.sleep, clock=time.time, snap_ttl=3.0, action_ttl=10.0):
        self.rooms = rooms
        self.run = run or self._run
        self.connect = connect or self._connect
        self.sleep, self.clock = sleep, clock
        self.snap_ttl, self.action_ttl = snap_ttl, action_ttl
        self.started = {}          # room pane -> pane id of the window Start preview made
        self.start_lock = threading.Lock()
        self._snap = (-1e9, None)
        self._act = {}             # path -> (time, "show" | "preview" | "")

    # -- outside --
    @staticmethod
    def _run(cmd, cwd=None, timeout=8):
        try:
            r = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, errors="replace", cwd=cwd)
        except (OSError, subprocess.SubprocessError):
            return 1, ""
        return r.returncode, r.stdout

    @staticmethod
    def _connect(port):
        try:
            socket.create_connection(("127.0.0.1", port), timeout=1).close()
            return True
        except OSError:
            return False

    # -- ports --
    def _snapshot(self):
        """(ps parents, lsof text, {pane: pane pid}), cached a few seconds: one lsof for all rooms."""
        if self.clock() - self._snap[0] < self.snap_ttl and self._snap[1]:
            return self._snap[1]
        _, ps = self.run(["ps", "-axo", "pid=,ppid="])
        _, ls = self.run(["lsof", "-nP", "-iTCP", "-sTCP:LISTEN", "-Fpn"], timeout=10)
        code, panes = self.rooms.tmux("list-panes", "-a", "-F", "#{pane_id}\t#{pane_pid}")
        pids = {}
        for line in panes.splitlines() if code == 0 else []:
            a, _, b = line.partition("\t")
            if b.strip().isdigit():
                pids[a] = int(b)
        snap = (parse_ps(ps), ls, pids)
        self._snap = (self.clock(), snap)
        return snap

    def ports_of(self, pane):
        """Listening TCP ports of the processes in that pane's process tree, newest process first."""
        parents, ls, pids = self._snapshot()
        root = pids.get(pane)
        if not root:
            return []
        tree = descendants(root, parents)
        return [p for _, p in sorted(parse_lsof(ls, tree), key=lambda x: -x[0])]

    def port_for(self, pane):
        """The dev server port for a room: its own pane tree first, then the window Start preview made."""
        for p in (pane, self.started.get(pane)):
            ports = self.ports_of(p) if p else []
            if ports:
                return ports[0]
        return None

    # -- what the room offers --
    def info(self, pane):
        """{"live": "dev"|"dw"|"none", "port", "host", "start": "dev"|"start"|"", "git"} or None for an unknown room."""
        row = self.rooms.room_row(pane)
        if not row:
            return None
        folder = row["path"]
        host, port = dw_host(folder), self.port_for(pane)
        script, _ = dev_script(folder)
        live = "dw" if host else "dev" if port else "none"
        return {"live": live, "port": port if live == "dev" else None, "host": host, "start": script if (live == "none" and script) else "", "git": in_git(folder)}

    def action_for(self, path):
        """The action id to add to a room's options: "show" (something to look at), "preview" (a dev script, no server yet), or ""."""
        t = self._act.get(path)
        if t and self.clock() - t[0] < self.action_ttl:
            return t[1]
        script, _ = dev_script(path)
        a = "show" if dw_host(path) or in_git(path) else "preview" if script else ""
        self._act[path] = (self.clock(), a)
        return a

    def add_action(self, base, room):
        """Put "show" or "preview" into an action list, before Read all / Screen / Write (after the answers)."""
        a = self.action_for(room.get("path", ""))
        if not a:
            return base
        # a git folder with a dev script and no server running starts one first
        if a == "show":
            script, _ = dev_script(room["path"])
            if script and not dw_host(room["path"]) and not self.port_for(room["id"]):
                a = "preview"
        for anchor in ("readall", "screen", "write"):
            if anchor in base:
                i = base.index(anchor)
                return base[:i] + [a] + base[i:]
        return base + [a]

    # -- start --
    def start(self, pane, timeout=60.0):
        """Start the dev server in a new tmux window of the room's folder; wait until its port answers. True when it does."""
        row = self.rooms.room_row(pane)
        if not row:
            return False
        with self.start_lock:
            if self.port_for(pane):
                return True
            script, pm = dev_script(row["path"])
            if not script:
                return False
            cmd = "%s run %s" % (pm, script) if pm != "yarn" else "yarn %s" % script
            code, sess = self.rooms.tmux("display-message", "-p", "-t", pane, "#{session_name}")
            session = sess.strip() if code == 0 and sess.strip() else self.rooms._pick_session()
            if not session:
                return False
            code, out = self.rooms.tmux("new-window", "-d", "-P", "-F", "#{pane_id}", "-n", "preview", "-t", session + ":", "-c", row["path"], cmd)
            new = out.strip()
            if code != 0 or not new.startswith("%"):
                return False
            self.started[pane] = new
            self.rooms._log(new, "preview", cmd, row["path"])
        end = self.clock() + timeout
        while self.clock() < end:
            self._snap = (-1e9, None)
            port = self.port_for(pane)
            if port and self.connect(port):
                return True
            self.sleep(1.0)
        return False

    # -- changes --
    def diff(self, pane):
        """{"files": [...]} of `git diff HEAD` plus the untracked files; None for an unknown room."""
        row = self.rooms.room_row(pane)
        if not row:
            return None
        folder = row["path"]
        base = ["git", "-C", folder, "-c", "core.quotepath=false", "--no-pager"]
        code, text = self.run(base + ["diff", "HEAD", "--no-color", "--no-ext-diff"], timeout=15)
        if code != 0:
            code, text = self.run(base + ["diff", "--no-color", "--no-ext-diff"], timeout=15)
        files = parse_diff(text) if code == 0 else []
        code, un = self.run(base + ["ls-files", "--others", "--exclude-standard"], timeout=10)
        names = [n for n in un.splitlines() if n.strip()][:MAX_UNTRACKED] if code == 0 else []
        have = {f["path"] for f in files}
        for n in names:
            if n not in have:
                files.append({"path": n, "added": 0, "removed": 0, "patch": "New file, not in git yet.", "untracked": True})
        return {"files": files}

    # -- pictures --
    def transcript(self, pane):
        row = self.rooms.room_row(pane)
        sid = self.rooms.session_id(row) if row else None
        if not sid or not re.match(r"^[\w.-]+$", sid):
            return None
        base = os.path.join(self.rooms.claude_dir, "projects")
        try:
            for d in os.listdir(base):
                p = os.path.join(base, d, sid + ".jsonl")
                if os.path.exists(p):
                    return p
        except OSError:
            pass
        return None

    def _pictures(self, pane):
        path = self.transcript(pane)
        if not path:
            return None
        try:
            size = os.path.getsize(path)
            with open(path, "rb") as f:
                f.seek(max(0, size - TRANSCRIPT_TAIL))
                lines = f.read().decode("utf-8", "replace").splitlines()[1 if size > TRANSCRIPT_TAIL else 0:]
        except OSError:
            return []
        items = [i for i in transcript_images(lines) if "data" in i or (i.get("path") and os.path.isfile(i["path"]))]
        return latest_pictures(items)

    def pictures(self, pane):
        """{"pictures": [{"n","name","kind"}]} newest first (n = 0 is the newest); None for an unknown room."""
        if not self.rooms.room_row(pane):
            return None
        got = self._pictures(pane) or []
        return {"pictures": [{"n": i, "name": g["name"], "kind": g["kind"]} for i, g in enumerate(got)]}

    def picture(self, pane, n):
        """(content type, bytes) of picture n, or None."""
        if not self.rooms.room_row(pane):
            return None
        got = self._pictures(pane) or []
        if not (0 <= n < len(got)):
            return None
        g = got[n]
        try:
            if "data" in g:
                data = base64.b64decode(g["data"])
            else:
                if os.path.getsize(g["path"]) > MAX_PICTURE_BYTES:
                    return None
                with open(g["path"], "rb") as f:
                    data = f.read()
        except (OSError, ValueError):
            return None
        return (g["media"], data) if len(data) <= MAX_PICTURE_BYTES else None

    # -- the proxy --
    def forward(self, port, method, path, headers, timeout=30):
        """Ask 127.0.0.1:port. Returns (code, [(header, value)], body) or None when nothing answers."""
        h = {k: v for k, v in headers.items() if k.lower() not in HOP | {"authorization", "cookie", "accept-encoding", "referer", "origin"}}
        h["Host"] = "127.0.0.1:%d" % port
        h["Accept-Encoding"] = "identity"
        try:
            c = http.client.HTTPConnection("127.0.0.1", port, timeout=timeout)
            c.request(method, path, headers=h)
            r = c.getresponse()
            body = r.read(MAX_BODY + 1)
            out = [(k, v) for k, v in r.getheaders() if k.lower() not in HOP]
            code = r.status
            c.close()
        except (OSError, http.client.HTTPException):
            return None
        if len(body) > MAX_BODY:
            return 502, [("Content-Type", "text/plain")], b"Too big for the phone."
        return code, out, body


# ---------- HTTP glue (called from server.py) ----------

def _cookies(headers):
    out = {}
    for part in (headers.get("Cookie") or "").split(";"):
        k, _, v = part.strip().partition("=")
        if k:
            out[k] = v
    return out


def is_fallback(path, headers):
    """A page of the dev site asked for /static/app.js (not under /preview/): the cookie says which site it is. Never for the bridge's own API."""
    if headers.get("Authorization") or path.startswith(API_PREFIXES) or path.startswith("/preview/"):
        return False
    c = _cookies(headers)
    return bool(c.get("inv_tok") and c.get("inv_pane"))


def _pane_of(seg):
    seg = unquote(seg)
    return "%" + seg if seg.isdigit() else seg


def serve_preview(h, token, pv):
    """Handle one GET /preview/<pane>/<path> (or a fallback path). `h` is the BaseHTTPRequestHandler."""
    u = urlparse(h.path)
    q = parse_qs(u.query)
    cookies = _cookies(h.headers)
    got = (q.get("token") or [""])[0] or cookies.get("inv_tok", "")
    bearer = h.headers.get("Authorization", "")
    ok = hmac.compare_digest(got.encode(), token.encode()) or hmac.compare_digest(bearer.encode(), ("Bearer " + token).encode())

    def reply(code, body=b"", ctype="text/plain", extra=()):
        h.send_response(code)
        h.send_header("Content-Type", ctype)
        h.send_header("Content-Length", str(len(body)))
        h.send_header("Cache-Control", "no-store")
        for k, v in extra:
            h.send_header(k, v)
        h.end_headers()
        if h.command != "HEAD":
            h.wfile.write(body)

    if not ok:
        return reply(401, b"bad token")
    if u.path.startswith("/preview/"):
        seg, _, rest = u.path[len("/preview/"):].partition("/")
        pane, path = _pane_of(seg), "/" + rest
    else:
        pane, path = _pane_of(cookies.get("inv_pane", "")), u.path
    if not re.match(r"^%\d+$", pane):
        return reply(404, b"no such window")
    cookie_extra = [("Set-Cookie", "inv_tok=%s; Path=/; HttpOnly; SameSite=Lax" % token), ("Set-Cookie", "inv_pane=%s; Path=/; SameSite=Lax" % pane[1:])]
    if q.get("token") and u.path.startswith("/preview/"):   # first load: set the cookies, then go to the same page without the token in the address
        rest_q = urlencode([(k, v) for k, vs in q.items() if k != "token" for v in vs])
        return reply(302, b"", extra=cookie_extra + [("Location", u.path + ("?" + rest_q if rest_q else ""))])
    port = pv.port_for(pane)
    if not port:
        return reply(502, b"No dev server is running in that window.")
    target = path + ("?" + u.query if u.query else "")
    got = pv.forward(port, h.command, target, dict(h.headers.items()))
    if got is None:
        return reply(502, b"The dev server did not answer.")
    code, hdrs, body = got
    extra = []
    ctype = "application/octet-stream"
    for k, v in hdrs:
        if k.lower() == "content-type":
            ctype = v
        elif k.lower() == "location" and v.startswith("/") and not v.startswith("//"):
            extra.append(("Location", "/preview/%s%s" % (quote(pane, safe=""), v)))
        elif k.lower() not in ("cache-control",):
            extra.append((k, v))
    reply(code, body, ctype, extra)


def handle_get(send, rooms, path, q):
    """The JSON/picture routes. `send(code, obj)` sends JSON; returns the (ctype, bytes) of a picture, or True when it answered, or False when the path is not ours."""
    pv = rooms.preview
    pane = q.get("id", [""])[0]
    if path == "/room/diff":
        got = pv.diff(pane)
    elif path == "/room/pictures":
        got = pv.pictures(pane)
    elif path == "/room/preview":
        got = pv.info(pane)
    elif path == "/room/picture":
        try:
            n = int(q.get("n", ["0"])[0])
        except ValueError:
            n = -1
        pic = pv.picture(pane, n)
        if pic is None:
            send(404, {"error": "no such picture"})
            return True
        return pic
    else:
        return False
    if got is None:
        send(404, {"error": "no such room"})
    else:
        send(200, got)
    return True


def handle_post(send, rooms, path, body):
    if path != "/room/preview/start":
        return False
    pane = str(body.get("id") or "")
    if not rooms.room_row(pane):
        send(404, {"ok": False})
    else:
        ok = rooms.preview.start(pane)
        send(200 if ok else 504, {"ok": ok})
    return True

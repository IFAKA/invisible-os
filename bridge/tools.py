"""Work tools inside a window (GET /room/tools, POST /room/tool, GET /info). Python 3 stdlib only.

A window's folder decides what is offered; the phone sends its profile (work | personal) with every call.
  personal: Git.   work: Git, Merge request, Jira, Research, and Commerce Cloud only when the folder has dw.json.
Everything that talks to the outside world goes through three injected functions, so the tests use fakes:
  run(cmd, cwd, timeout) -> (exit code, text)      git, glab, acli
  http(url, user, password, timeout) -> (status, text)   WebDAV logs (the password is never logged or returned)
  model(prompt) -> str                             the local model (story.ollama_model), or None
dw.json holds passwords: this module reads it, never prints it, never puts it in a story or an error text.
See ROOMS-API.md and DESIGN-ROOMS.md.
"""
import base64
import glob
import json
import os
import re
import shlex
import socket
import subprocess
import threading
import urllib.error
import urllib.request

import story as storylib
from rooms import strip_markdown

PROFILES = ("work", "personal")
STORY_WORDS = 25
BRANCH_RE = re.compile(r"^[\w][\w./-]{0,100}$")
KEY_RE = re.compile(r"^[A-Z][A-Z0-9]+-\d+$")
LOG_PATH = "/on/demandware.servlet/webdav/Sites/Logs/"
ERROR_LINE_RE = re.compile(r"^\[(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d(?:\.\d+)?)[^\]]*\]\s+(\w+)\s+(.*)$")


def squash(text):
    return " ".join(str(text or "").split())


def cut(text, n):
    return storylib.cut_words(text, n)


def default_run(cmd, cwd=None, timeout=30):
    """(exit code, stdout + stderr). 127 when the program is not installed."""
    try:
        r = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True, timeout=timeout, errors="replace", stdin=subprocess.DEVNULL)
    except FileNotFoundError:
        return 127, ""
    except (OSError, subprocess.SubprocessError):
        return 1, ""
    return r.returncode, (r.stdout or "") + ("" if r.returncode == 0 else (r.stderr or ""))


def default_http(url, user, password, timeout=20):
    req = urllib.request.Request(url)
    if user:
        req.add_header("Authorization", "Basic " + base64.b64encode(("%s:%s" % (user, password)).encode()).decode())
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, r.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, ""
    except Exception:   # never carry the URL or the headers in the message
        return 0, ""


# ---------- dw.json (Commerce Cloud) ----------

def find_dw(folder, levels=4):
    """The path of dw.json in `folder` or one of its parents (at most `levels` up, never above the home folder), or None."""
    cur = os.path.abspath(str(folder or ""))
    home = os.path.expanduser("~")
    for _ in range(levels + 1):
        p = os.path.join(cur, "dw.json")
        if os.path.isfile(p):
            return p
        parent = os.path.dirname(cur)
        if parent == cur or cur == home:
            break
        cur = parent
    return None


def load_dw(path):
    """[config dict, ...] from dw.json: a single object is one config; {"configurations": [...]} (Prophet Debugger style, one with
    "active": true) is many. [] when unreadable. The dicts hold secrets: callers only read hostname/name/active from them."""
    try:
        with open(path) as f:
            data = json.load(f)
    except (OSError, ValueError):
        return []
    if isinstance(data, dict) and isinstance(data.get("configurations"), list):
        return [c for c in data["configurations"] if isinstance(c, dict)]
    return [data] if isinstance(data, dict) else []


def active_index(configs):
    for i, c in enumerate(configs):
        if c.get("active") is True:
            return i
    return 0


def config_name(c):
    return squash(c.get("name") or c.get("hostname") or "instance")


def dw_names(path):
    """Instance names only (for the phone): [(name, is_active)]."""
    cs = load_dw(path)
    a = active_index(cs)
    return [(config_name(c), i == a) for i, c in enumerate(cs)]


def set_active(path, name):
    """Make the instance `name` the only one with "active": true. True when done. Only for the multi-config form."""
    try:
        with open(path) as f:
            data = json.load(f)
    except (OSError, ValueError):
        return False
    cs = data.get("configurations") if isinstance(data, dict) else None
    if not isinstance(cs, list) or not any(isinstance(c, dict) and config_name(c) == name for c in cs):
        return False
    for c in cs:
        if isinstance(c, dict):
            c["active"] = config_name(c) == name
    tmp = path + ".tmp"
    try:
        mode = os.stat(path).st_mode & 0o777
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
        with os.fdopen(fd, "w") as f:
            json.dump(data, f, indent=2)
            f.write("\n")
        os.replace(tmp, path)
    except OSError:
        return False
    return True


def parse_log_index(html):
    """Error log file names (error-*.log) in a WebDAV directory page, newest last."""
    names = sorted(set(re.findall(r"""href="[^"]*?(error-[\w.-]+\.log)"?""", html)) | set(re.findall(r">(error-[\w.-]+\.log)<", html)),
                   key=lambda n: (re.findall(r"\d{8}", n) or ["0"])[-1] + n)
    return names


def parse_errors(text):
    """Group the ERROR entries of a Commerce Cloud log. -> [{"msg","count","first","last"}] by first appearance;
    the message key has digits and hex ids collapsed, so "order 1234" and "order 5678" are one error."""
    groups, order = {}, []
    for line in str(text or "").splitlines():
        m = ERROR_LINE_RE.match(line)
        if not m:
            continue
        ts, level, msg = m.groups()
        if level.upper() not in ("ERROR", "FATAL"):
            continue
        msg = squash(re.sub(r"^\S*\|\S*\s+", "", msg))   # the leading Class|id|site|pipeline|... token is context, not the message
        key = re.sub(r"\b[0-9a-f]{8,}\b|\d+", "#", msg)[:200]
        g = groups.get(key)
        if g is None:
            g = groups[key] = {"msg": msg, "count": 0, "first": ts, "last": ts}
            order.append(key)
        g["count"] += 1
        g["last"] = ts
    return [groups[k] for k in order]


def new_errors(groups, window_minutes=60):
    """Errors whose first entry is within `window_minutes` of the newest entry of the log."""
    import datetime as dt
    def parse(ts):
        try:
            return dt.datetime.strptime(ts[:19], "%Y-%m-%d %H:%M:%S")
        except ValueError:
            return None
    newest = max((parse(g["last"]) for g in groups if parse(g["last"])), default=None)
    if newest is None:
        return []
    return [g for g in groups if parse(g["first"]) and (newest - parse(g["first"])).total_seconds() <= window_minutes * 60]


# ---------- the tools ----------

def item(label, tool, arg="", confirm=False, write=False):
    d = {"label": label, "tool": tool, "arg": arg}
    if confirm:
        d["confirm"] = True
    if write:
        d["write"] = True
    return d


CANCEL = item("Cancel", "")


def reply(story, items=None, ok=True):
    out = {"ok": ok, "story": squash(story)}
    if items:
        out["items"] = items
    return out


def plain_adf(node):
    """The text of an Atlassian document (or a plain string)."""
    if isinstance(node, str):
        return node
    if isinstance(node, list):
        return " ".join(plain_adf(n) for n in node)
    if isinstance(node, dict):
        if isinstance(node.get("text"), str):
            return node["text"]
        return plain_adf(node.get("content") or node.get("body") or "")
    return ""


class Tools:
    def __init__(self, run=None, http=None, model=None, open_window=None, claude_cmd="claude", neutral_dir=None):
        self.run = run or default_run
        self.http = http or default_http
        self.model = model
        self.open_window = open_window       # (path, command) -> pane id or None
        self.claude_cmd = claude_cmd
        self.neutral_dir = neutral_dir or os.path.join(os.path.expanduser("~"), ".config", "invisible-bridge", "research")
        self.state = {}                      # pane id -> drafts, error lists
        self.lock = threading.Lock()

    @classmethod
    def for_rooms(cls, rooms):
        g = lambda o, n, d=None: getattr(o, n, d)
        return cls(model=g(g(rooms, "stories"), "model"), open_window=g(rooms, "_new_window"), claude_cmd=g(rooms, "claude_cmd", "claude"))

    # -- the list --
    def has_dw(self, path):
        return find_dw(path) is not None

    def suggest(self, project_paths):
        """work when any known project folder has dw.json, else personal."""
        return "work" if any(self.has_dw(p) for p in project_paths) else "personal"

    def tool_list(self, path, profile):
        """[{id,label,confirm,write?,children?}]: the tools of one window (GET /room/tools)."""
        t = lambda i, label, confirm=False, write=False: dict(id=i, label=label, confirm=confirm, **({"write": True} if write else {}))
        groups = [dict(id="git", label="Git", confirm=False, children=[
            t("git.status", "Status"), t("git.pull", "Pull"), t("git.switch", "Switch branch"), t("git.commit", "Commit"), t("git.push", "Push", True)])]
        if profile == "work":
            groups.append(dict(id="mr", label="Merge request", confirm=False, children=[t("mr.make", "Make merge request")]))
            groups.append(dict(id="jira", label="Jira", confirm=False, children=[t("jira.mine", "My tickets")]))
            groups.append(dict(id="research", label="Research", confirm=False, children=[t("research.ask", "Ask a question", write=True)]))
            dw = find_dw(path)
            if dw:
                kids = [t("cc.logs", "Read logs")]
                if len(load_dw(dw)) > 1:
                    kids.insert(0, t("cc.switch", "Switch instance"))
                groups.append(dict(id="cc", label="Commerce Cloud", confirm=False, children=kids))
        return groups

    def allowed(self, path, profile, tool):
        def walk(items):
            for i in items:
                yield i["id"]
                yield from walk(i.get("children", []))
        extra = {"git.commit.do", "mr.create", "mr.edit", "jira.view", "jira.comment", "cc.error", "cc.switch"}
        groups = {i["id"] for i in self.tool_list(path, profile)}
        return tool in set(walk(self.tool_list(path, profile))) or (tool in extra and tool.split(".")[0] in groups)

    # -- one call --
    def call(self, pane, path, profile, tool, arg=""):
        """POST /room/tool -> {ok, story, items?}. Never raises."""
        profile = profile if profile in PROFILES else "personal"
        tool, arg = str(tool or ""), str(arg or "")
        if not os.path.isdir(str(path or "")) or not self.allowed(path, profile, tool):
            return reply("That tool is not available here.", ok=False)
        fn = getattr(self, "t_" + tool.replace(".", "_"), None)
        if fn is None:
            return reply("That tool is not available here.", ok=False)
        try:
            return fn(pane, path, arg)
        except Exception:
            return reply("That did not work.", ok=False)

    def git(self, path, *args, timeout=30):
        return self.run(["git"] + list(args), path, timeout)

    def _branch(self, path):
        code, out = self.git(path, "rev-parse", "--abbrev-ref", "HEAD")
        return squash(out) if code == 0 else ""

    def _ask_model(self, prompt, keys):
        """The JSON object of the model's reply, or {} (no model, down, bad answer)."""
        if self.model is None:
            return {}
        try:
            m = re.search(r"\{.*\}", self.model(prompt) or "", re.S)
            o = json.loads(m.group(0)) if m else {}
        except Exception:
            return {}
        return {k: o[k] for k in keys if isinstance(o.get(k), str)} if isinstance(o, dict) else {}

    def _guard(self, text, source, limit):
        ok, _ = storylib.check_story(text, source, limit=limit)
        return ok

    # -- Git --
    def t_git_status(self, pane, path, arg):
        code, out = self.git(path, "status", "--porcelain=v1", "-b")
        if code != 0:
            return reply("This folder is not a git repository.", ok=False)
        lines = out.splitlines()
        head = lines[0][3:] if lines and lines[0].startswith("## ") else ""
        branch = re.split(r"\.\.\.|\s", head)[0] or "unknown"
        changed = len([l for l in lines[1:] if l.strip()])
        parts = ["Branch %s." % branch, "No changes." if changed == 0 else ("1 changed file." if changed == 1 else "%d changed files." % changed)]
        a = re.search(r"ahead (\d+)", head); b = re.search(r"behind (\d+)", head)
        if a or b:
            parts.append(" ".join(x for x in (("Ahead %s." % a.group(1)) if a else "", ("Behind %s." % b.group(1)) if b else "") if x))
        return reply(" ".join(parts))

    def t_git_pull(self, pane, path, arg):
        code, out = self.git(path, "pull", "--ff-only", timeout=60)
        if code != 0:
            return reply("Pull failed. " + cut(self._last_line(out), 15), ok=False)
        return reply("Already up to date." if "up to date" in out.lower() else "Pulled.")

    @staticmethod
    def _last_line(out):
        ls = [l for l in str(out).splitlines() if l.strip()]
        return squash(ls[-1]) if ls else ""

    def t_git_switch(self, pane, path, arg):
        code, out = self.git(path, "for-each-ref", "--format=%(refname:short)", "refs/heads")
        names = [n.strip() for n in out.splitlines() if BRANCH_RE.match(n.strip())] if code == 0 else []
        if not arg:
            cur = self._branch(path)
            others = [n for n in names if n != cur]
            if not others:
                return reply("No other local branches.")
            return reply("On %s. Pick a branch." % cur if cur else "Pick a branch.", [item(n, "git.switch", n, confirm=True) for n in others[:8]])
        if arg not in names:
            return reply("No such branch.", ok=False)
        code, out = self.git(path, "switch", arg)
        return reply("Switched to %s." % arg) if code == 0 else reply("Switch failed. " + cut(self._last_line(out), 15), ok=False)

    def commit_message(self, path):
        """(message, file list text) from the diff, or ("", "") when nothing changed."""
        code, stat = self.git(path, "diff", "HEAD", "--stat")
        if code != 0 or not stat.strip():
            return "", ""
        _, names = self.git(path, "diff", "HEAD", "--name-only")
        files = [os.path.basename(n) for n in names.splitlines() if n.strip()]
        fallback = "Update " + ", ".join(files[:3]) + (" and more" if len(files) > 3 else "")
        _, diff = self.git(path, "diff", "HEAD", "--unified=1")
        source = stat + "\n" + diff[:3000]
        prompt = ("Write a git commit message for this change. One line, at most 12 words, imperative, plain. "
                  "Use only names that are in the text. JSON: {\"message\": \"...\"}.\n---\n%s\n---" % source)
        msg = squash(self._ask_model(prompt, ["message"]).get("message")).strip(" .\"'")
        if msg and len(msg.split()) <= 14 and self._guard(msg, source + " " + " ".join(files), 14):
            return msg, stat
        return fallback, stat

    def t_git_commit(self, pane, path, arg):
        msg, stat = self.commit_message(path)
        if not msg:
            return reply("Nothing to commit.")
        return reply("Message: %s." % msg.rstrip("."), [CANCEL, item("Create", "git.commit.do", msg)])   # the message you heard is the question: Cancel is read first

    def t_git_commit_do(self, pane, path, arg):
        msg = squash(arg)[:200]
        if not msg:
            return reply("No message.", ok=False)
        code, out = self.git(path, "commit", "-a", "-m", msg)
        return reply("Committed.") if code == 0 else reply("Commit failed. " + cut(self._last_line(out), 15), ok=False)

    def t_git_push(self, pane, path, arg):
        code, _ = self.git(path, "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{u}")
        code, out = self.git(path, "push", timeout=90) if code == 0 else self.git(path, "push", "-u", "origin", "HEAD", timeout=90)
        return reply("Pushed.") if code == 0 else reply("Push failed. " + cut(self._last_line(out), 15), ok=False)

    # -- Merge request (glab) --
    def default_branch(self, path):
        code, out = self.git(path, "symbolic-ref", "--short", "refs/remotes/origin/HEAD")
        name = squash(out).split("/", 1)[-1] if code == 0 and squash(out) else ""
        return name or "main"

    def template(self, path):
        code, top = self.git(path, "rev-parse", "--show-toplevel")
        root = squash(top) if code == 0 and squash(top) else path
        files = sorted(glob.glob(os.path.join(root, ".gitlab", "merge_request_templates", "*.md")))
        pick = next((f for f in files if os.path.basename(f).lower() == "default.md"), files[0] if files else None)
        if not pick:
            return ""
        try:
            with open(pick) as f:
                return f.read()[:4000]
        except OSError:
            return ""

    def mr_draft(self, path):
        """{"branch","target","title","description","commits","files"} or None when the branch has no commits of its own."""
        branch, target = self._branch(path), self.default_branch(path)
        rng = "origin/%s..HEAD" % target
        code, log = self.git(path, "log", "--format=%s", rng)
        subjects = [s for s in (squash(l) for l in log.splitlines()) if s] if code == 0 else []
        if not subjects:
            return None
        _, stat = self.git(path, "diff", "--stat", "origin/%s...HEAD" % target)
        tpl = self.template(path)
        source = "Branch %s\nCommits:\n%s\nChanged:\n%s\nTemplate:\n%s" % (branch, "\n".join("- " + s for s in subjects), stat, tpl)
        prompt = ("Fill in a GitLab merge request. Reply JSON: {\"title\": \"at most 12 words\", \"description\": \"the template below filled in; plain markdown; "
                  "keep its headings; use only facts from the commits and changes; no invented names or numbers\"}.\n---\n%s\n---" % source)
        got = self._ask_model(prompt, ["title", "description"])
        title, desc = squash(got.get("title")), str(got.get("description") or "").strip()
        if not (title and desc and len(title.split()) <= 14 and self._guard(title, source, 14) and self._desc_ok(desc, source)):
            title = subjects[-1] if len(subjects) == 1 else (subjects[0] if len(subjects) < 4 else humanize(branch))
            body = "\n".join("- " + s for s in reversed(subjects))
            desc = (tpl.rstrip() + "\n\n" if tpl else "") + body
        files = [l for l in stat.splitlines() if "|" in l]
        return dict(branch=branch, target=target, title=title, description=desc, commits=len(subjects), files=len(files))

    @staticmethod
    def _desc_ok(desc, source):
        hay = source.lower()
        return all(storylib._in_source(t, hay) for t in storylib.risky_tokens(desc))

    def _mr_story(self, d):
        body = squash(strip_markdown(re.sub(r"<!--.*?-->", "", d["description"], flags=re.S)))
        lead = "Merge request: %s. %d commit%s, %d file%s." % (d["title"].rstrip("."), d["commits"], "" if d["commits"] == 1 else "s", d["files"], "" if d["files"] == 1 else "s")
        return lead + (" " + cut(body, STORY_WORDS) if body else "")

    def _mr_items(self):
        return [CANCEL, item("Create", "mr.create"), item("Edit description", "mr.edit", write=True)]

    def t_mr_make(self, pane, path, arg):
        d = self.mr_draft(path)
        if d is None:
            return reply("No commits to merge. Commit and push first.")
        with self.lock:
            self.state.setdefault(pane, {})["mr"] = d
        return reply(self._mr_story(d), self._mr_items())

    def _draft(self, pane, path):
        with self.lock:
            d = self.state.get(pane, {}).get("mr")
        if d is None or d["branch"] != self._branch(path):
            return None
        return d

    def t_mr_edit(self, pane, path, arg):
        d = self._draft(pane, path)
        if d is None:
            return reply("No draft. Make the merge request again.", ok=False)
        if not squash(arg):
            return reply("Nothing was written.", ok=False)
        d["description"] = str(arg).strip()
        return reply(self._mr_story(d), self._mr_items())

    def t_mr_create(self, pane, path, arg):
        d = self._draft(pane, path)
        if d is None:
            return reply("No draft. Make the merge request again.", ok=False)
        code, out = self.run(["glab", "mr", "create", "--title", d["title"], "--description", d["description"],
                              "--target-branch", d["target"], "--source-branch", d["branch"], "--yes"], path, 60)
        if code == 127:
            return reply("glab is not installed.", ok=False)
        if code != 0:
            return reply("Could not create it. " + cut(self._last_line(out), 15), ok=False)
        with self.lock:
            self.state.get(pane, {}).pop("mr", None)
        url = re.search(r"https?://\S+", out)
        return reply("Merge request created." + (" " + url.group(0) if url else ""))

    # -- Jira (acli) --
    def acli(self, path, *args):
        return self.run(["acli", "jira", "workitem"] + list(args), path, 40)

    @staticmethod
    def _json(out):
        m = re.search(r"[\[{].*[\]}]", out or "", re.S)
        try:
            return json.loads(m.group(0)) if m else None
        except ValueError:
            return None

    def t_jira_mine(self, pane, path, arg):
        code, out = self.acli(path, "search", "--jql", "assignee = currentUser() AND statusCategory != Done ORDER BY updated DESC", "--json", "--limit", "20")
        if code == 127:
            return reply("Jira is not set up on this laptop.", ok=False)
        data = self._json(out) if code == 0 else None
        rows = data.get("issues") or data.get("workItems") or data.get("items") or [] if isinstance(data, dict) else data
        if not isinstance(rows, list):
            return reply("Could not read your tickets.", ok=False)
        items = []
        for r in rows:
            if not isinstance(r, dict):
                continue
            key = str(r.get("key") or "")
            f = r.get("fields") if isinstance(r.get("fields"), dict) else r
            summary = squash(f.get("summary"))
            if KEY_RE.match(key):
                items.append(item("%s, %s" % (key, summary), "jira.view", key))
        if not items:
            return reply("No open tickets for you.")
        return reply("%d open ticket%s." % (len(items), "" if len(items) == 1 else "s"), items[:12])

    def t_jira_view(self, pane, path, arg):
        key = squash(arg)
        if not KEY_RE.match(key):
            return reply("Not a ticket key.", ok=False)
        code, out = self.acli(path, "view", key, "--json")
        data = self._json(out) if code == 0 else None
        if not isinstance(data, dict):
            return reply("Could not read %s." % key, ok=False)
        f = data.get("fields") if isinstance(data.get("fields"), dict) else data
        desc = squash(plain_adf(f.get("description")))
        com = f.get("comment")
        comments = (com.get("comments") if isinstance(com, dict) else com) or []
        last = squash(plain_adf(comments[-1].get("body"))) if comments and isinstance(comments[-1], dict) else ""
        summary = squash(f.get("summary"))
        source = squash("%s. Description: %s Last comment: %s" % (summary, desc, last))
        text = ""
        got = self._ask_model("Summarise this ticket for a listener in at most 25 words: what it asks and the last comment. Use only facts in the text. "
                              "JSON: {\"story\": \"...\"}.\n---\n%s\n---" % source[:3000], ["story"])
        if self._guard(got.get("story", ""), source, 30):
            text = squash(got["story"])
        if not text:
            text = cut(desc or summary, STORY_WORDS)
        return reply("%s. %s" % (key, text), [item("Comment", "jira.comment", key, write=True)])

    def t_jira_comment(self, pane, path, arg):
        key, _, text = str(arg).partition("\n")
        key, text = squash(key), text.strip()
        if not KEY_RE.match(key) or not text:
            return reply("Nothing was written.", ok=False)
        code, out = self.acli(path, "comment", "create", "--key", key, "--body", text)
        return reply("Comment added to %s." % key) if code == 0 else reply("Could not comment. " + cut(self._last_line(out), 15), ok=False)

    # -- Commerce Cloud --
    def t_cc_switch(self, pane, path, arg):
        dw = find_dw(path)
        names = dw_names(dw) if dw else []
        if len(names) < 2:
            only = names[0][0] if names else "none"
            return reply("Only one instance: %s. Use Read logs." % only)
        if not arg:
            return reply("Pick an instance.", [item(n + (", active" if act else ""), "cc.switch", n, confirm=True) for n, act in names if not act])
        if arg not in [n for n, _ in names] or not set_active(dw, arg):
            return reply("Could not switch.", ok=False)
        return reply("Active instance is now %s." % arg)

    def t_cc_logs(self, pane, path, arg):
        dw = find_dw(path)
        cs = load_dw(dw) if dw else []
        if not cs:
            return reply("No dw.json here.", ok=False)
        c = cs[active_index(cs)]
        host, user, pw = str(c.get("hostname") or ""), c.get("username") or "", c.get("password") or ""
        if not host or not user or not pw:
            return reply("dw.json has no host or login.", ok=False)
        base = "https://%s%s" % (host, LOG_PATH)
        code, html = self.http(base, user, pw, 20)
        names = parse_log_index(html) if code == 200 else []
        if not names:
            return reply("Could not read the log folder.", ok=False)
        code, text = self.http(base + names[-1], user, pw, 30)
        if code != 200:
            return reply("Could not read the error log.", ok=False)
        groups = parse_errors(text[-400000:])
        with self.lock:
            self.state.setdefault(pane, {})["errors"] = groups
        if not groups:
            return reply("No errors in %s." % names[-1])
        fresh = new_errors(groups)
        story = "%d error%s" % (len(groups), "" if len(groups) == 1 else "s")
        if fresh:
            story += ", %d new: %s" % (len(fresh), cut(fresh[-1]["msg"], 12))
        items = [item("%d times, %s" % (g["count"], cut(g["msg"], 8)) if g["count"] > 1 else cut(g["msg"], 9), "cc.error", str(i)) for i, g in enumerate(groups[:10])]
        return reply(story + ".", items)

    def t_cc_error(self, pane, path, arg):
        with self.lock:
            groups = self.state.get(pane, {}).get("errors", [])
        if not str(arg).isdigit() or int(arg) >= len(groups):
            return reply("Read the logs again.", ok=False)
        g = groups[int(arg)]
        return reply("%s. %d time%s, last at %s." % (cut(g["msg"], 60).rstrip("."), g["count"], "" if g["count"] == 1 else "s", g["last"][11:16]))

    # -- Research --
    def t_research_ask(self, pane, path, arg):
        question = squash(arg)[:1000]
        if not question:
            return reply("Nothing was written.", ok=False)
        prompt = ("Answer this question for the owner in a few plain sentences. Search the organisation's projects the owner has access to: "
                  "use glab for GitLab and acli for Jira, read only (never create, edit, comment, push or merge). Say which project each fact came from. "
                  "Question: " + question)
        cmd = "%s -p %s --allowedTools 'Bash(glab:*)' 'Bash(acli:*)' 'Read'; exec \"${SHELL:-/bin/zsh}\"" % (self.claude_cmd, shlex.quote(prompt))
        try:
            os.makedirs(self.neutral_dir, exist_ok=True)
        except OSError:
            return reply("Could not start the research.", ok=False)
        pane_id = self.open_window(self.neutral_dir, cmd) if self.open_window else None
        return reply("Research started in a new window. Its story will be the answer.") if pane_id else reply("Could not open a window.", ok=False)


def humanize(branch):
    return squash(re.sub(r"[-_/]+", " ", re.sub(r"^[a-z]+/", "", branch))).capitalize() or "Update"


def host_name():
    return (socket.gethostname().split(".")[0].strip()) or "Computer"

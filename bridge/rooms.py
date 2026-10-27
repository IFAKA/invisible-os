"""Rooms: the laptop's tmux windows as seen from the earbud. Python 3 stdlib only. Contract: ROOMS-API.md, why: DESIGN-ROOMS.md.

A room = the active pane of a tmux window. It only reads tmux, the agent-status files, the pane->session records and the Claude
transcripts, and types with `tmux send-keys`. Everything it touches is configurable (env INVISIBLE_*), so tests use a private tmux.
"""
import collections
import hashlib
import json
import os
import re
import shlex
import shutil
import socket
import subprocess
import threading
import time
import urllib.request

from core import shorten
from story import StoryCache, first_sentence, ollama_model

STATES = ["needs", "failed", "done", "working", "idle"]
SHELLS = {"zsh", "bash", "sh", "fish", "-zsh", "-bash", "-sh", "-fish"}
FAIL_RE = re.compile(r"make: \*\*\*|Error|FAILED|Traceback")
PROMPT_END_RE = re.compile(r"[%$#>❯]\s*$")
ANSI_RE = re.compile(r"\x1b\[[0-?]*[ -/]*[@-~]|\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)|\x1b[()][A-Za-z0-9]")
NEED_RE = re.compile(r"what needs (?:me|you)\s*[:\-–—]\s*|needs (?:me|you)\s*:\s*", re.I)
NOTHING_RE = re.compile(r"^(nothing|none|n/a|no)\b", re.I)
QUOTE_RE = re.compile(r"\"([^\"\n]{1,40})\"|“([^”\n]{1,40})”")
PANE_RE = re.compile(r"^%\d+$")

ACTION_TEXT = {
    "go": "go", "yes": "yes", "commit": "commit", "push": "push", "compact": "/compact", "clear": "/clear",
    "explain": "i dont get it, explain simpler", "recommend": "what do you recommend?",
    "research": "research online first, then recommend", "progress": "where are we? what is done and what is left?",
    "hold": "hold on, dont make changes yet",
}
CLAUDE_ACTIONS = ["go", "yes", "commit", "push", "compact", "explain", "recommend", "research", "progress", "hold", "stop",
                  "clear", "readall", "screen", "write"]

PINNED = {"stop", "readall", "screen", "write"}   # keep their place in the list; learning only reorders the others
HALF_LIFE_DAYS = 14.0
LEARN = 1.0   # one fresh pick is worth one place in the fixed order


def learned_order(base, picks, learn=LEARN):
    """Pure. Reorder the fixed actions in `base` by score = (len - rank) + learn * picks[action].
    Quoted options (option:*) stay first, in order. Pinned actions (stop, readall, screen, write) keep their place; the others
    are sorted into the places the movable ones had. Ties keep the fixed order, so with no picks the list is unchanged.
    picks: {action id: recency-weighted count}."""
    base = list(base)
    movable = [(i, a) for i, a in enumerate(base) if a not in PINNED and not a.startswith("option:")]
    n = len(base)
    ranked = sorted(movable, key=lambda ia: -((n - ia[0]) + learn * picks.get(ia[1], 0.0)))   # sorted() is stable
    out = list(base)
    for (slot, _), (_, a) in zip(movable, ranked):
        out[slot] = a
    return out


def weighted_picks(entries, now, half_life=HALF_LIFE_DAYS):
    """Pure. entries = [(epoch seconds, action id)] -> {action id: sum of 0.5 ** (age_days / half_life)}. Future entries count 1."""
    out = collections.Counter()
    for t, a in entries:
        out[a] += 0.5 ** (max(0.0, now - t) / 86400.0 / half_life)
    return dict(out)


# ---------- pure text helpers ----------

def strip_markdown(text):
    t = str(text or "")
    t = re.sub(r"```[^\n]*\n?", "", t)
    t = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", t)
    t = re.sub(r"^\s{0,3}#{1,6}\s*", "", t, flags=re.M)
    t = re.sub(r"^\s*[-*+]\s+", "", t, flags=re.M)
    t = re.sub(r"^\s*>\s?", "", t, flags=re.M)
    t = re.sub(r"(\*\*|__|\*|`)", "", t)
    t = re.sub(r"^\s*\|?[-:| ]{3,}\|?\s*$", "", t, flags=re.M)
    t = t.replace("|", " ")
    return re.sub(r"[ \t]+", " ", t).strip()


def one_line(text):
    return " ".join(str(text or "").split())


def need_line(message):
    """(found, need text, options) from a Claude message. found=False when there is no status block.
    need is "" when the block says nothing is needed. Options = quoted strings in the line (max 3).
    Uses the LAST 'What needs me:' marker: the text after it on the same line, else the next non-empty line."""
    lines = str(message or "").splitlines()
    for i in range(len(lines) - 1, -1, -1):
        clean = strip_markdown(lines[i])
        m = NEED_RE.search(clean)
        if not m:
            continue
        rest = clean[m.end():].strip()
        if not rest:
            rest = next((strip_markdown(n) for n in lines[i + 1:] if n.strip()), "")
        need, options = _parse_need(rest)
        return True, need, options
    return False, "", []


def _parse_need(rest):
    rest = one_line(rest)
    if not rest or NOTHING_RE.match(rest.lstrip("\"“'")):
        return "", []
    options = []
    for m in QUOTE_RE.finditer(rest):
        q = (m.group(1) or m.group(2)).strip()
        if q and q not in options:
            options.append(q)
    return shorten(rest, 20), options[:3]


def last_sentences(text, n=2, words=20):
    t = one_line(strip_markdown(text))
    parts = [p for p in re.split(r"(?<=[.!?])\s+", t) if p]
    return shorten(" ".join(parts[-n:]), words)


def clean_title(title, fallback):
    t = re.sub(r"^[^\w]+", "", str(title or "")).strip()
    if t.startswith(socket.gethostname()):   # tmux's default title is the host name: no real title yet
        return fallback
    return t or fallback


def read_last_assistant_text(path):
    """Text of the last non-sidechain assistant message with text in a transcript (reads only the tail). None if none."""
    try:
        size = os.path.getsize(path)
        f = open(path, "rb")
    except OSError:
        return None
    with f:
        for chunk in (1 << 20, 8 << 20, size):
            start = max(0, size - chunk)
            f.seek(start)
            data = f.read().decode("utf-8", "ignore")
            lines = data.splitlines()
            if start > 0:
                lines = lines[1:]   # the first line may be cut
            for line in reversed(lines):
                if '"assistant"' not in line:
                    continue
                try:
                    e = json.loads(line)
                except ValueError:
                    continue
                msg = e.get("message") or {}
                if e.get("isSidechain") or not (e.get("type") == "assistant" or msg.get("role") == "assistant"):
                    continue
                content = msg.get("content")
                if isinstance(content, str):
                    texts = [content]
                else:
                    texts = [c.get("text", "") for c in content or [] if isinstance(c, dict) and c.get("type") == "text"]
                text = "\n".join(t for t in texts if t.strip()).strip()
                if text:
                    return text
            if start == 0:
                break
    return None


def makefile_targets(folder):
    for name in ("Makefile", "makefile", "GNUmakefile"):
        try:
            with open(os.path.join(folder, name), errors="ignore") as f:
                text = f.read()
        except OSError:
            continue
        out = []
        for m in re.finditer(r"^([A-Za-z0-9_][A-Za-z0-9_./-]*)\s*:(?!=)", text, re.M):
            if m.group(1) not in out:
                out.append(m.group(1))
        return out
    return []


# ---------- optional local model (never blocks /rooms) ----------

class Summarizer:
    """Rules first. A local model (Ollama) improves the summary in the background; the next poll gets it from the cache."""

    def __init__(self, llm=None):
        self.llm = llm            # function(prompt) -> str, or None = off
        self.cache = {}           # key -> text
        self.pending = set()
        self.down_until = 0.0
        self.lock = threading.Lock()

    def get(self, kind, text, fallback):
        if not self.llm or not text:
            return fallback
        key = hashlib.sha1((kind + "\0" + text).encode()).hexdigest()
        with self.lock:
            if key in self.cache:
                return self.cache[key] or fallback
            if key in self.pending or time.monotonic() < self.down_until:
                return fallback
            self.pending.add(key)
        threading.Thread(target=self._work, args=(key, kind, text), daemon=True).start()
        return fallback

    def _work(self, key, kind, text):
        if kind == "shell":
            prompt = "Summarize this terminal output in at most 7 plain words (what failed). Output only the words.\n\n" + text[-1500:]
            limit = 7
        else:
            prompt = "Shorten this message to its last two key sentences, plain text, at most 25 words. Output only the text.\n\n" + text[-3000:]
            limit = 25
        try:
            out = shorten(one_line(strip_markdown(self.llm(prompt))), limit)
        except Exception:   # Ollama down, timeout, bad answer: rules stay
            out = ""
            with self.lock:
                self.down_until = time.monotonic() + 60
        with self.lock:
            self.pending.discard(key)
            if out:
                self.cache[key] = out


def ollama_llm(url="http://127.0.0.1:11434/api/generate", model="gemma3:4b"):
    def call(prompt):
        body = json.dumps({"model": model, "prompt": prompt, "stream": False, "keep_alive": "30m"}).encode()
        req = urllib.request.Request(url, data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=3) as r:
            return json.loads(r.read()).get("response", "")
    return call


# ---------- the rooms ----------

def _default_tmux():
    return os.environ.get("INVISIBLE_TMUX") or ((shutil.which("tmux") or "/opt/homebrew/bin/tmux"))


class Rooms:
    FORMAT = "#{pane_id}\t#{window_id}\t#{window_active}\t#{pane_active}\t#{pane_title}\t#{pane_current_command}\t" \
             "#{pane_current_path}\t#{window_name}\t#{session_name}\t#{window_index}\t#{pane_index}\t#{window_activity}"

    def __init__(self, tmux=None, status_dir=None, panes_dir=None, claude_dir=None, config_dir=None, history=None,
                 zsh_history=None, llm=None, story_model=None, claude_cmd="claude", pending=None, clock=time.time):
        self.tmux_cmd = shlex.split(tmux or _default_tmux())
        self.status_dir = status_dir or os.path.join(os.environ.get("TMUX_TMPDIR", "/tmp"), "agent-status-%d" % os.getuid())
        self.panes_dir = panes_dir or os.path.expanduser("~/.local/state/claude-panes")
        self.claude_dir = claude_dir or os.path.expanduser("~/.claude")
        self.config_dir = config_dir or os.path.expanduser("~/.config/invisible-bridge")
        self.history = history or os.path.join(self.claude_dir, "history.jsonl")
        self.zsh_history = zsh_history or os.path.expanduser("~/.zsh_history")
        self.claude_cmd = claude_cmd
        self.pending = pending or (lambda: {})   # () -> {pane id: summary} of open bridge asks
        self.clock = clock
        self.ask_delay = 4.0
        self.summarizer = Summarizer(llm)
        self.stories = StoryCache(story_model)
        self.lock = threading.Lock()
        self.snap = (0.0, [])
        self._msg_cache = {}      # transcript path -> (mtime, size, text)
        self._zsh_cache = (0.0, {})
        self._proj_cache = (0.0, [])
        self._picks_cache = (-1.0, [])

    @classmethod
    def from_env(cls, pending=None):
        e = os.environ.get
        off = e("INVISIBLE_OLLAMA", "1") == "0"
        llm = None if off else ollama_llm()
        return cls(tmux=e("INVISIBLE_TMUX"), status_dir=e("INVISIBLE_AGENT_STATUS_DIR"), panes_dir=e("CLAUDE_PANES_DIR"),
                   claude_dir=e("INVISIBLE_CLAUDE_DIR"), config_dir=e("INVISIBLE_CONFIG_DIR"), llm=llm,
                   story_model=None if off else ollama_model(),
                   zsh_history=e("INVISIBLE_ZSH_HISTORY"), claude_cmd=e("INVISIBLE_CLAUDE_CMD", "claude"), pending=pending)

    # -- tmux --
    def tmux(self, *args, timeout=5):
        try:
            r = subprocess.run(self.tmux_cmd + list(args), capture_output=True, text=True, timeout=timeout, errors="replace")
        except (OSError, subprocess.SubprocessError):
            return 1, ""
        return r.returncode, r.stdout

    def _panes(self):
        code, out = self.tmux("list-panes", "-a", "-F", self.FORMAT)
        rows = []
        for line in out.splitlines() if code == 0 else []:
            p = line.split("\t")
            if len(p) < 12 or p[3] != "1":   # only the active pane of each window
                continue
            rows.append(dict(id=p[0], window=p[1], title=p[4], cmd=p[5], path=p[6], wname=p[7], session=p[8],
                             windex=p[9], pindex=p[10], activity=int(p[11] or 0)))
        return rows

    def screen_lines(self, pane, n=60, scrollback=True):
        code, out = self.tmux("capture-pane", "-p", "-t", pane, *(["-S", "-%d" % n] if scrollback else []))
        lines = ANSI_RE.sub("", out).rstrip().splitlines() if code == 0 else []
        return lines[-n:]

    # -- state sources --
    def agent_status(self, window):
        try:
            with open(os.path.join(self.status_dir, window.lstrip("@"))) as f:
                parts = f.read().split()
            nums = [int(x) for x in parts[:7]] + [0] * (7 - len(parts[:7]))
        except (OSError, ValueError):
            return dict(notified=0, busy=0, action=0)
        return dict(notified=nums[0], busy=nums[1], action=nums[6])

    def session_id(self, row):
        slot = os.path.join(self.panes_dir, ("%s:%s.%s" % (row["session"], row["windex"], row["pindex"])).replace(":", "_").replace(".", "-") + ".json")
        try:
            with open(slot) as f:
                return json.load(f).get("session_id") or None
        except (OSError, ValueError):
            return None

    def last_message(self, sid):
        if not sid or not re.match(r"^[\w.-]+$", sid):
            return None
        path = None
        base = os.path.join(self.claude_dir, "projects")
        try:
            for d in os.listdir(base):
                p = os.path.join(base, d, sid + ".jsonl")
                if os.path.exists(p):
                    path = p
                    break
        except OSError:
            return None
        if not path:
            return None
        st = os.stat(path)
        c = self._msg_cache.get(path)
        if c and c[0] == st.st_mtime and c[1] == st.st_size:
            return c[2]
        text = read_last_assistant_text(path)
        self._msg_cache[path] = (st.st_mtime, st.st_size, text)
        return text

    @staticmethod
    def is_claude(cmd):
        return bool(re.match(r"^\d+(\.\d+)+$", cmd)) or cmd.lower() == "claude"

    # -- one room --
    def build_room(self, row, asks):
        claude = self.is_claude(row["cmd"])
        project = os.path.basename(row["path"].rstrip("/")) or row["wname"]
        room = dict(id=row["id"], name=clean_title(row["title"], row["wname"]) if claude else row["wname"], project=project,
                    path=row["path"], kind="claude" if claude else "shell", state="idle", need="", options=[], actions=[],
                    story="", go="")
        room["_activity"] = row["activity"]
        if claude:
            self._claude_state(room, row, asks)
        else:
            self._shell_state(room, row)
        room["actions"] = self._actions(room)
        source = room.get("_src", "")
        if room["state"] != "working":
            room.update(self.stories.peek(row["id"], room["kind"], source, self._story_context(room), self._story_fallback(room, source)))
        return room

    @staticmethod
    def _story_context(room):
        return "%s, %s" % (room["name"], room["project"])

    @staticmethod
    def _story_fallback(room, source):
        return room["need"] or first_sentence(source)

    def _claude_state(self, room, row, asks):
        st = self.agent_status(row["window"])
        msg = self.last_message(self.session_id(row))
        room["_src"] = strip_markdown(msg) if msg else ""
        found, need, options = need_line(msg) if msg else (False, "", [])
        if not found and msg:
            need = self.summarizer.get("claude", msg, last_sentences(msg))
        if row["id"] in asks:
            room["state"], room["need"] = "needs", shorten(asks[row["id"]], 20)
            return
        if st["action"]:
            room["state"], room["need"] = "needs", "waiting for your permission"
            return
        if st["busy"]:
            room["state"] = "working"
            return
        room["state"] = "done" if st["notified"] else "idle"
        room["need"], room["options"] = need, options
        if (found and need) or (not found and need.rstrip().endswith("?")):
            room["state"] = "needs"

    def _shell_state(self, room, row):
        if row["cmd"] not in SHELLS:
            room["state"] = "working"
            return
        screen = self.screen_lines(row["id"], 30, scrollback=False)   # what is on the screen now
        room["_src"] = "\n".join(l.rstrip() for l in screen if l.strip())
        lines = screen[-15:]
        if any(FAIL_RE.search(l) for l in lines):
            room["state"] = "failed"
            real = [l.strip() for l in lines if l.strip() and not PROMPT_END_RE.search(l)]
            last = real[-1] if real else ""
            room["need"] = self.summarizer.get("shell", "\n".join(lines), shorten(one_line(last), 12))

    def _make_targets(self, folder):
        if self.clock() - self._zsh_cache[0] > 30:
            counts = collections.Counter()
            try:
                with open(self.zsh_history, "rb") as f:
                    lines = f.read().decode("utf-8", "ignore").splitlines()
                for line in lines:
                    line = line.split(";", 1)[1] if line.startswith(": ") and ";" in line else line
                    m = re.match(r"\s*make\s+(?:-\S+\s+)*([\w./-]+)", line)
                    if m:
                        counts[m.group(1)] += 1
            except OSError:
                pass
            self._zsh_cache = (self.clock(), counts)
        counts = self._zsh_cache[1]
        targets = makefile_targets(folder)
        used = [t for t in targets if counts.get(t)]
        used.sort(key=lambda t: -counts[t])   # stable: ties keep Makefile order
        return used[:3]

    def _actions(self, room):
        if room["kind"] == "claude":
            base = ["option:" + o for o in room["options"]] + CLAUDE_ACTIONS
        else:
            base = ["again"] + ["make:" + t for t in self._make_targets(room["path"])] + ["stop", "screen", "write"]
        return learned_order(base, self.picks_for(room["id"], room["path"]))

    # -- all rooms --
    def rooms(self, max_age=0.0):
        with self.lock:
            if self.clock() - self.snap[0] < max_age:
                return self.snap[1]
            try:
                asks = self.pending() or {}
            except Exception:
                asks = {}
            rooms = [self.build_room(r, asks) for r in self._panes()]
            rooms.sort(key=lambda r: (STATES.index(r["state"]), -r["_activity"]))
            for r in rooms:
                del r["_activity"]
                r.pop("_src", None)
            self.snap = (self.clock(), rooms)
            return rooms

    def room_row(self, pane):
        if not PANE_RE.match(str(pane or "")):
            return None
        for r in self._panes():
            if r["id"] == pane:
                return r
        return None

    def last_text(self, pane):
        row = self.room_row(pane)
        if not row:
            return None
        msg = self.last_message(self.session_id(row)) if self.is_claude(row["cmd"]) else None
        text = strip_markdown(msg) if msg else ""
        if text:   # start the story now, so it is ready when the phone asks
            room = self.build_room(row, {})
            room.pop("_src", None)
        return text

    def story_for(self, pane, wait=6.0):
        """{story, go} of one room, waiting up to `wait` s for the model (GET /room/story). None when there is no such room."""
        row = self.room_row(pane)
        if not row:
            return None
        room = self.build_room(row, self.pending() or {})
        source = room.pop("_src", "")
        ctx, fb = self._story_context(room), self._story_fallback(room, source)
        if room["state"] == "working" and not source:
            return {"story": fb, "go": ""}
        return self.stories.ask(pane, room["kind"], source, ctx, fb, wait)

    def screen_text(self, pane):
        if not self.room_row(pane):
            return None
        return "\n".join(self.screen_lines(pane, 60))

    # -- typing --
    def _log(self, pane, action, text, path=""):
        try:
            os.makedirs(self.config_dir, mode=0o700, exist_ok=True)
            with open(os.path.join(self.config_dir, "sends.log"), "a") as f:
                f.write("%s\t%s\t%s\t%s\t%s\n" % (time.strftime("%Y-%m-%d %H:%M:%S"), pane, action, json.dumps(text, ensure_ascii=False), path or ""))
            self._picks_cache = (-1.0, [])
        except OSError:
            pass

    def _log_entries(self):
        """[(epoch, pane, action, path)] from sends.log (old lines have no path). Cached by the file's mtime and size."""
        p = os.path.join(self.config_dir, "sends.log")
        try:
            st = os.stat(p)
            if self._picks_cache[0] == (st.st_mtime, st.st_size):
                return self._picks_cache[1]
            with open(p, errors="ignore") as f:
                lines = f.read().splitlines()[-5000:]
        except OSError:
            return []
        rows = []
        for line in lines:
            f = line.split("\t")
            if len(f) < 3:
                continue
            try:
                rows.append((time.mktime(time.strptime(f[0], "%Y-%m-%d %H:%M:%S")), f[1], f[2], f[4] if len(f) > 4 else ""))
            except (ValueError, OverflowError):
                continue
        self._picks_cache = ((st.st_mtime, st.st_size), rows)
        return rows

    def picks_for(self, pane, path):
        """Recency-weighted action picks of one room: log lines with the same project path (or, when the line has no path, the same pane)."""
        here = os.path.realpath(path or "/nonexistent-room")
        entries = [(t, a) for t, p, a, pp in self._log_entries() if (os.path.realpath(pp) == here if pp else p == pane)]
        return weighted_picks(entries, self.clock())

    def _type(self, pane, text):
        self.tmux("send-keys", "-t", pane, "-l", "--", text)
        time.sleep(0.15)   # Claude Code treats text and Enter that arrive together as a paste
        self.tmux("send-keys", "-t", pane, "Enter")

    def send(self, pane, text):
        """Type free text + Enter. Returns False when the room does not exist or the text is empty."""
        text = str(text or "")
        row = self.room_row(pane)
        if not text.strip() or not row:
            return False
        self._log(pane, "send", text, row["path"])
        self._type(pane, text)
        return True

    def act(self, pane, action):
        """Run one action id. Returns the text/keys said, or None when unknown (room or action)."""
        row = self.room_row(pane)
        if not row or not isinstance(action, str):
            return None
        claude = self.is_claude(row["cmd"])
        if action == "stop":
            key = "Escape" if claude else "C-c"
            self._log(pane, action, key, row["path"])
            self.tmux("send-keys", "-t", pane, key)
            return key
        if action == "again" and not claude:
            self._log(pane, action, "Up Enter", row["path"])
            self.tmux("send-keys", "-t", pane, "Up")
            time.sleep(0.15)
            self.tmux("send-keys", "-t", pane, "Enter")
            return "again"
        text = None
        if action.startswith("option:") and claude:
            text = action[len("option:"):]
        elif action in ACTION_TEXT and claude:
            text = ACTION_TEXT[action]
        elif action.startswith("make:") and not claude:
            target = action[len("make:"):]
            if re.match(r"^[\w./-]+$", target) and target in makefile_targets(row["path"]):
                text = "make " + target
        if not text:
            return None
        self._log(pane, action, text, row["path"])
        self._type(pane, text)
        return text

    # -- projects --
    def projects(self):
        if self.clock() - self._proj_cache[0] < 60:
            return self._proj_cache[1]
        counts = collections.Counter()
        try:
            with open(self.history, errors="ignore") as f:
                for line in f:
                    if '"project"' not in line:
                        continue
                    try:
                        p = json.loads(line).get("project")
                    except ValueError:
                        continue
                    if isinstance(p, str) and p:
                        counts[p] += 1
        except OSError:
            pass
        out = [{"name": os.path.basename(p.rstrip("/")) or p, "path": p, "uses": n}
               for p, n in counts.most_common() if os.path.isdir(p)][:20]
        self._proj_cache = (self.clock(), out)
        return out

    def areas(self):
        """Parent folders of the known projects, ranked by summed uses, max 8: where a new project may be made."""
        sums = collections.Counter()
        for p in self.projects():
            parent = os.path.dirname(p["path"].rstrip("/"))
            if parent and parent != "/" and os.path.isdir(parent):
                sums[parent] += p["uses"]
        return [{"name": os.path.basename(a) or a, "path": a, "uses": n} for a, n in sums.most_common(8)]

    def _pick_session(self):
        session = None
        code, out = self.tmux("list-clients", "-F", "#{client_activity}\t#{client_session}")
        best = -1
        for line in out.splitlines() if code == 0 else []:
            a, _, s = line.partition("\t")
            if a.isdigit() and int(a) > best and s:
                best, session = int(a), s
        if not session:
            code, out = self.tmux("list-sessions", "-F", "#{session_name}")
            session = out.splitlines()[0] if code == 0 and out.strip() else None
        return session

    def _new_window(self, path, cmd):
        session = self._pick_session()
        if not session:
            return None
        code, out = self.tmux("new-window", "-P", "-F", "#{pane_id}", "-t", session + ":", "-c", path, cmd)
        pane = out.strip()
        return pane if code == 0 and PANE_RE.match(pane) else None

    def open_project(self, path, mode):
        if not isinstance(path, str) or not os.path.isdir(path) or mode not in ("continue", "new"):
            return None
        cmd = self.claude_cmd + (" --continue || " + self.claude_cmd if mode == "continue" else "")
        pane = self._new_window(path, cmd)
        if pane:
            self._log(pane, "open", "%s %s" % (mode, path))
        return pane

    @staticmethod
    def slug(name):
        s = re.sub(r"[^a-z0-9]+", "-", str(name or "").lower()).strip("-")[:40].strip("-")
        return s

    def new_project(self, area, name, ask=None):
        """Make <area>/<slug(name)>, git init, open claude there, type `ask` once claude is up. {pane, path} or None.
        `area` must be one of areas(): a raw path from the phone is never trusted."""
        if not isinstance(area, str) or area.rstrip("/") not in [a["path"] for a in self.areas()]:
            return None
        slug = self.slug(name)
        path = os.path.join(area.rstrip("/"), slug)
        if not slug or os.path.exists(path):
            return None
        try:
            os.mkdir(path)
        except OSError:
            return None
        try:
            subprocess.run(["git", "init", "-q"], cwd=path, capture_output=True, timeout=10)
        except (OSError, subprocess.SubprocessError):
            pass
        pane = self._new_window(path, self.claude_cmd)
        if not pane:
            return None
        self._log(pane, "new", path)
        text = str(ask or "").strip()
        if text:
            threading.Thread(target=self._ask_later, args=(pane, text), daemon=True).start()
        return {"pane": pane, "path": path}

    def _ask_later(self, pane, text):
        time.sleep(self.ask_delay)   # claude needs a moment to show its prompt
        if self.room_row(pane):
            self._log(pane, "send", text)
            self._type(pane, text)

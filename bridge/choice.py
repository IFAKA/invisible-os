"""Claude Code's multiple-choice menus (AskUserQuestion, plan approval) read from the pane screen. Python 3 stdlib only.

Layout (Claude Code, Ink menu), the parts we rely on:
    ☐ Approach                              header chip / tab bar (ignored)
    Which approach do you want?             the question: nearest text line above the first option
    ❯ 1. Option A                           "❯" = cursor; "N. label"; multi select adds "[ ]" / "[x]"
         What A means                       description lines: indented, not numbered
      2. Option B
      3. Type something.                    the type-your-own entry ("Other")
      4. Chat about this                    dropped (the phone has triple click)
    Enter to select · ↑/↓ to navigate · Esc to cancel
Keys: Up/Down move the cursor, Enter picks (single), Space toggles (multi), then the Submit row. Contract: ROOMS-API.md.
"""
import re

OPT_RE = re.compile(r"^\s*(?P<cur>[❯>›])?\s*(?P<n>\d{1,2})[.)]\s+(?P<box>\[[ xX✔✓]\]\s*|[☐☑☒✔]\s+)?(?P<label>\S.*?)\s*$")
FOOT_RE = re.compile(r"enter to (select|confirm|submit)|to navigate|esc to cancel|space to (select|toggle)", re.I)
RULE_RE = re.compile(r"^[\s─━—\-_═]{6,}$")
CHIP_RE = re.compile(r"^\s*[←→]?\s*[☐☑☒✔✓]\s")
OTHER_RE = re.compile(r"^(type|other|something else|enter your own|write your own|tell claude)", re.I)
DROP_RE = re.compile(r"^chat about this", re.I)
SUBMIT_RE = re.compile(r"^submit\b", re.I)
MAX_OPTIONS = 12


def _clean(line):
    return line.rstrip()


def _is_checked(box):
    return bool(box) and bool(re.search(r"[xX✔✓☑☒]", box))


def parse_screen(lines):
    """Pure. The menu shown on `lines` (the visible screen), or None.
    -> {"question", "multi", "options": [{"n","label","desc"}], "other": n or 0, "submit": n or 0, "cursor": n or 0, "checked": [n]}
    `n` is the number printed on the screen. A menu needs a footer line, a cursor on a numbered row, and rows numbered 1, 2, ..."""
    lines = [_clean(l) for l in lines]
    foot = max((i for i, l in enumerate(lines) if FOOT_RE.search(l)), default=-1)
    if foot < 0:
        return None
    # the block of rows above the footer: walk up to the first option row (n == 1) of the last numbered run
    rows = []
    for i in range(foot - 1, -1, -1):
        m = OPT_RE.match(lines[i])
        if m:
            rows.append((i, m))
            if int(m.group("n")) == 1:
                break
        elif RULE_RE.match(lines[i]) and not rows:
            continue
    rows.reverse()
    if len(rows) < 2 or int(rows[0][1].group("n")) != 1:
        return None
    nums = [int(m.group("n")) for _, m in rows]
    if nums != list(range(1, len(nums) + 1)):
        return None
    if not any(m.group("cur") for _, m in rows):
        return None
    first = rows[0][0]
    question = ""
    for i in range(first - 1, -1, -1):
        t = lines[i].strip()
        if not t or RULE_RE.match(lines[i]) or CHIP_RE.match(lines[i]):
            continue
        question = t
        break
    if not question:
        return None
    multi = any(m.group("box") for _, m in rows)
    options, other, submit, cursor, checked = [], 0, 0, 0, []
    for k, (i, m) in enumerate(rows):
        end = rows[k + 1][0] if k + 1 < len(rows) else foot
        desc = []
        for j in range(i + 1, end):
            t = lines[j].strip()
            if not t or RULE_RE.match(lines[j]):
                continue
            desc.append(t)
        n, label = int(m.group("n")), m.group("label").strip()
        if m.group("cur"):
            cursor = n
        if _is_checked(m.group("box")):
            checked.append(n)
        if OTHER_RE.match(label):
            other = n
        elif DROP_RE.match(label):
            pass
        elif SUBMIT_RE.match(label) and multi:
            submit = n
        else:
            options.append({"n": n, "label": label, "desc": " ".join(desc)})
    if not options or len(options) > MAX_OPTIONS:
        return None
    return {"question": question, "multi": multi, "options": options, "other": other, "submit": submit, "cursor": cursor, "checked": checked}


def public(menu):
    """The `choice` object of /rooms: no cursor, no screen details."""
    return {"question": menu["question"], "multi": menu["multi"], "options": menu["options"], "other": bool(menu["other"])}


def moves(cursor, target):
    """Pure. The key names that move the cursor from row `cursor` to row `target`."""
    d = target - cursor
    return ["Down"] * d if d >= 0 else ["Up"] * (-d)

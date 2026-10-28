#!/usr/bin/env python3
"""tree_test: can a NEW user find an app in the spoken home tree? An AI plays the naive user.

For each target app it walks the tree from home. At each level it gives `claude -p --model haiku` only the task
("You want to open Strava") and the spoken options (one list, in the order they are read) and takes the number it answers.
Two variants run on the SAME apps: "short" (group names only: "Play") and "names" (the names inside: "Play: Strava, Games").
Measured per variant: first pick right (the home entry that holds the app) and full (reached the app with no wrong turn).

Needs app/build/tree_dump.json (written by: cd app && ./gradlew :os:testDebugUnitTest --offline --tests '*AppTreeTest*').
Answers are cached on disk by prompt hash (app/logs/tree_test_cache.json), so a rerun with the same tree costs nothing.
Output: one summary line (also in app/logs/tree_test.log) and, after it, the apps the names variant still fails.
"""
import argparse, hashlib, json, os, random, subprocess, sys, tempfile, time
from concurrent.futures import ThreadPoolExecutor

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
LOGS = os.path.join(ROOT, "app", "logs")
CACHE = os.path.join(LOGS, "tree_test_cache.json")
LOG = os.path.join(LOGS, "tree_test.log")
WORKDIR = tempfile.mkdtemp(prefix="tree_test_cwd_")  # an empty folder: the AI reads no project files


def leaves(node, path=()):
    """Every app below node: (app node, [(list node, index of the entry on the right way)...])."""
    for i, c in enumerate(node["children"]):
        if c["pkg"]:
            yield c, path + ((node, i),)
        else:
            yield from leaves(c, path + ((node, i),))


def prompt(app, opts):
    lines = "\n".join(f"{i + 1}. {o}" for i, o in enumerate(opts))
    return (f"You want to open {app}. You hear these menu options, one at a time:\n{lines}\n"
            f"Reply with only the number of the one you pick.")


class Ask:
    def __init__(self, model, use_cache=True, votes=3):
        self.model, self.use_cache, self.votes = model, use_cache, votes
        self.cache = json.load(open(CACHE)) if use_cache and os.path.exists(CACHE) else {}
        self.calls = 0

    def __call__(self, text):
        """The AI is not steady: ask `votes` times (each answer cached on its own) and take the most common number."""
        got = [self.one(text, i) for i in range(self.votes)]
        return max(sorted(set(got)), key=got.count)

    def one(self, text, i):
        key = hashlib.sha256((self.model + f"\n#{i}\n" + text).encode()).hexdigest()
        if key in self.cache:
            return self.cache[key]
        for _ in range(3):
            r = subprocess.run(["claude", "-p", "--model", self.model, "--system-prompt", "You answer with a single number only.",
                                "--setting-sources", ""], input=text, capture_output=True, text=True, cwd=WORKDIR, timeout=120)
            digits = "".join(ch if ch.isdigit() else " " for ch in r.stdout).split()
            if digits:
                self.calls += 1
                self.cache[key] = int(digits[0])
                return self.cache[key]
        return 0

    def save(self):
        if self.use_cache:
            os.makedirs(LOGS, exist_ok=True)
            json.dump(self.cache, open(CACHE, "w"))


def walk(root, target, path, variant, ask):
    """Play the user. Returns (first pick right, reached with no wrong turn, where it went wrong)."""
    node, first, trail = root, None, []
    right = [p[1] for p in path]
    for depth in range(len(path)):
        opts = [c[variant] for c in node["children"]]
        pick = ask(prompt(target["short"], opts)) - 1
        ok = pick == right[depth]
        if depth == 0:
            first = ok
        if not ok:
            said = opts[pick] if 0 <= pick < len(opts) else "?"
            return first, False, f"{' > '.join(trail) or 'home'}: chose \"{said}\", needed \"{opts[right[depth]]}\""
        trail.append(node["children"][pick]["short"])
        node = node["children"][pick]
    return first, True, ""


def main():
    ap = argparse.ArgumentParser(description="AI plays a naive user walking the spoken app tree; compares short labels with labels that name the apps inside.")
    ap.add_argument("--dump", default=os.path.join(ROOT, "app", "build", "tree_dump.json"), help="the tree as spoken (default app/build/tree_dump.json)")
    ap.add_argument("-n", type=int, default=50, help="how many target apps (default 50: the heaviest half by real usage weight, the rest random)")
    ap.add_argument("--first", type=int, default=0, help="halving: run only the first N targets as a quick check")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--model", default="haiku")
    ap.add_argument("--votes", type=int, default=3, help="answers per question; the most common one counts (default 3: the AI is not steady)")
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--no-cache", action="store_true", help="ask again even when the same prompt was answered before")
    ap.add_argument("--tag", default="", help="a note for the log line (for example before/after)")
    a = ap.parse_args()

    root = json.load(open(a.dump))
    apps = list(leaves(root))
    rnd = random.Random(a.seed)
    heavy = sorted(apps, key=lambda t: (-t[0]["weight"], t[0]["pkg"]))
    pick = heavy[: a.n // 2]
    rest = [t for t in heavy if t not in pick]
    pick += rnd.sample(rest, min(len(rest), a.n - len(pick)))
    if a.first:
        pick = pick[: a.first]
    ask = Ask(a.model, not a.no_cache, a.votes)
    t0 = time.time()

    def run(job):
        (app, path), variant = job
        return job, walk(root, app, path, variant, ask)

    jobs = [(t, v) for t in pick for v in ("short", "names")]
    with ThreadPoolExecutor(a.workers) as ex:
        results = list(ex.map(run, jobs))
    ask.save()
    stat = {v: [0, 0] for v in ("short", "names")}
    fails = []
    for ((app, path), v), (first, full, why) in results:
        stat[v][0] += bool(first); stat[v][1] += bool(full)
        if v == "names" and not full:
            fails.append(f"  {app['short']} ({app['pkg']}): {why}")
    n = len(pick)
    pct = lambda x: round(100 * x / n)
    line = (f"tree_test: short first-pick {pct(stat['short'][0])}% full {pct(stat['short'][1])}% | "
            f"names first-pick {pct(stat['names'][0])}% full {pct(stat['names'][1])}% ({n} apps)")
    os.makedirs(LOGS, exist_ok=True)
    with open(LOG, "a") as f:
        f.write(time.strftime("%F %T ") + (a.tag + " " if a.tag else "") + line + f" [{ask.calls} AI calls, {time.time() - t0:.0f}s]\n")
        for x in fails:
            f.write(x + "\n")
    print(line)
    if fails:
        print("names variant still fails:")
        print("\n".join(fails))


if __name__ == "__main__":
    sys.exit(main())

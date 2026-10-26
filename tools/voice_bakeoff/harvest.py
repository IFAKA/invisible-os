"""List every string literal in the spoken-text files that could be a fixed spoken sentence (no $ templates). Review, then freeze into manifest.txt."""
import re, sys, glob
src = sys.argv[1]
files = ["Voice.kt", "ControllerMode.kt", "MessageLogic.kt", "Typing.kt", "Actions.kt", "Info.kt"]
seen = {}
for f in files:
    for n, line in enumerate(open(f"{src}/{f}"), 1):
        if line.lstrip().startswith(("//", "*", "/*", "import")) or "Log." in line or "Regex(" in line: continue
        for m in re.finditer(r'"((?:[^"\\$]|\\.)*)"', line):
            s = m.group(1)
            if not re.search(r"[A-Za-zÁ-ú]{2}", s) or len(s) > 120 or re.search(r"[_/<>{}=]|\.kt|\\n|^[a-z]+:[a-z]|^[a-z]+$", s) and not re.search(r"\s", s) and s.islower(): continue
            if re.match(r"^[a-z0-9_.:\-]+$", s) or s.startswith(("http", "android", "com.")): continue
            seen.setdefault(s, f"{f}:{n}")
for s, w in seen.items(): print(f"{s}\t{w}")

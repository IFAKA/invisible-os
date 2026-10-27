# Rooms API (laptop bridge/server.py <-> phone "Laptop" tool)

Same server, port, token (header `Authorization: Bearer <token>`) and address file as today. JSON in and out.
Design and evidence: `DESIGN-ROOMS.md`. A room = one tmux pane that is the active pane of its window.

## GET /rooms
{"seq": 12, "rooms": [ROOM, ...]}  sorted: needs, failed, done, working, idle; then most recent activity.
ROOM = {
  "id": "%3",                       tmux pane id
  "name": "Consumer-ready distribution and polish",   Claude pane title without spinner/✳ marks; else the window name
  "project": "invisible-os",        basename of pane_current_path
  "path": "/Users/.../invisible-os",
  "kind": "claude" | "shell",
  "state": "needs" | "failed" | "done" | "working" | "idle",
  "need": "say \"go\" for the design doc.",  max ~20 words, plain text, no markdown; "" if nothing
  "options": ["go"],                quoted replies found in the need line (max 3)
  "actions": ["option:go","go","yes","commit",...]   ordered action ids for this room (see below)
  "story": "Tests ran, 3 of 12 failed. Fixed one. Commit the fix?",  the short story (see /room/story); "" until it is made, always "" for working rooms
  "go": "commit the fix",           at most 5 words: what a plain "go" or "yes" approves; "" if unclear
  "ask": {...}                      ONLY while the hook has an open permission ask for this pane (then state is "needs"), see below
}
ASK = {
  "id": "7",                        the ask id, for POST /answer
  "kind": "permission",
  "what": "rm -rf build/cache",     the exact command (Bash) or "edit|write|read|fetch <file or address>", white space squashed, cut at 200 chars; never rewritten by a model (the hook sends it as `detail` in POST /ask)
  "plain": "Deletes the build cache folder",   one line from the local model (same Ollama call, cache and 6 s limit as stories; max 12 words; it must not reassure (safe, harmless, fine, ok...) nor name anything not in `what`); "" until made and on any failure. /rooms never waits for it.
  "options": ["Allow","Deny","Always allow"]   the hook's option labels, in its order
}
Answer an ask: POST /answer {"id": "<ask.id>", "choice": "<one of ask.options, exactly>"} -> 200 {"ok":true}; 409 {"ok":false} = too late (answered on the laptop, cancelled or timed out).
The hook then allows, denies or allows-and-remembers as before (hooks are not changed). The phone offers No, Yes, Yes and do not ask again (only if offered) by the meaning of the labels, No first.

## GET /room/last?id=%3   -> {"text": "..."}   Claude's whole last message, markdown stripped (for "Read all")
## GET /room/story?id=%3 -> {"story": "...", "go": "..."}   may wait up to 6 s for the local model; then the fallback
  story = the shortest text that keeps accuracy, at most ~25 words, plain words: what it was doing, what happened (done / failed / a question), what it wants from you.
  Made by a local model (Ollama gemma3:4b, temperature 0) from Claude's last message (shell windows: the last 30 screen lines) in a background thread, cached by
  (pane id, hash of the source). /rooms only reads the cache and never waits. `bridge/story.py` guard: every number, file name, quoted string and capitalised word
  of the story must be in the source, and the story must be shorter than the source; else (or on timeout, Ollama down) the story is the room's need line, else the
  first sentence of the message cut to 25 words, and go is "". 404 if there is no such room.
## GET /room/screen?id=%3 -> {"text": "..."}   last 60 lines of the pane, ANSI stripped (for the screen view)
## POST /room/act {"id":"%3","action":"go"}             -> {"ok":true,"said":"go"}   runs one action id
## POST /room/send {"id":"%3","text":"free text"}       -> {"ok":true}            types text + Enter (from Write)
## GET /projects -> {"projects":[{"name":"console-core","path":"...","uses":2355}, ...]}  top 20, folders that exist
## POST /project/open {"path":"...","mode":"continue"|"new"} -> {"ok":true,"id":"%9"}  new tmux window running claude
## GET /areas -> {"areas":[{"name":"hardware","path":"/Users/.../code/projects/hardware","uses":4000}, ...]}  parent folders of the known projects, ranked by summed uses, max 8
## POST /project/new {"area":"<a path from /areas>","name":"My game","ask":"optional first message"} -> {"ok":true,"pane":"%9","path":".../my-game"}
  Makes folder slug(name) (lowercase a-z0-9 and "-", max 40) in the area, `git init`, opens a tmux window running claude, and types `ask` + Enter about 4 s later.
  400 {"ok":false} when the area is not in /areas, the name gives an empty slug, or the folder exists.
## /events (exists) also delivers {"type":"room","id":"%3","name":"...","state":"needs"} when a room turns "needs".

## Action ids
Claude room: option:<text> (send that text), go, yes, commit, push*, compact (/compact), explain ("i dont get it, explain simpler"),
recommend ("what do you recommend?"), research ("research online first, then recommend"), progress ("where are we? what is done and what is left?"),
hold ("hold on, dont make changes yet"), stop (Escape key), clear* (/clear), readall, screen, write.
Shell room: again (Up then Enter), make:<target> (top Makefile targets of that folder, ordered by ~/.zsh_history counts, max 3), stop (Ctrl-C), screen, write.
Order: option:* first; then the fixed actions in the order above, re-sorted per room by your past picks (sends.log, same project path, weight 0.5^(age_days/14), one fresh pick = one place). stop, readall, screen, write keep their place. `sends.log` lines are `time, pane, action, text, path`.
* = the phone asks "No, go back" / "Yes" before sending. readall, screen, write are handled on the phone (no POST /room/act).

## Show on phone (bridge/preview.py)
Action ids `show` ("Show on phone") or `preview` ("Start preview") sit after the answers and before readall/screen/write, for a room whose folder is in a git repo, has dw.json, or has a package.json "dev"/"start" script. `preview` replaces `show` when no dev server listens and the folder has such a script (the phone asks "No, go back" / "Yes" first). Both are handled on the phone (no POST /room/act): it posts a notification "<window>: see changes"; tapping it opens the app screen "Changes" with tabs Live, Changes, Pictures.
## GET /room/preview?id=%3 -> {"live":"dev"|"dw"|"none","port":5173|null,"host":"","start":"dev"|"start"|"","git":true}
  live "dev" = a TCP port listens in the pane's process tree (lsof) or in the window Start preview made; "dw" = the folder has dw.json (only its `hostname` is read; the phone opens https://<hostname>/; nothing else of dw.json is ever read or sent); "none" = hide Live. 404 if no such room.
## POST /room/preview/start {"id":"%3"} -> {"ok":true}   `npm|pnpm|yarn run dev|start` in a new detached tmux window "preview" in the room's folder; waits until the port answers (max 60 s; 504 {"ok":false} after). 200 at once if a server already runs.
## GET /preview/<pane>/<path>?<query>   proxy to 127.0.0.1:<port> (GET/HEAD; the pane id is URL-encoded: /preview/%253/ for %3; a plain number also works)
  Auth: `?token=<token>` on the first load (the answer sets cookies `inv_tok` (HttpOnly) and `inv_pane`, Path=/, and redirects to the same address without the token), or the cookie, or the Bearer header. The token and cookies are never sent on to the dev site. Root paths the site asks for (`/assets/app.js`) follow the `inv_pane` cookie (never the bridge's own API paths, which always need the header). A `Location: /x` redirect is kept under /preview/<pane>. 502 when no server runs. Web sockets (hot reload) are not proxied: pull down to reload.
## GET /room/diff?id=%3 -> {"files":[{"path","added","removed","patch","untracked"?}]}   `git diff HEAD` (else `git diff`) of the folder, then the untracked files (patch "New file, not in git yet.", untracked true). At most 40 files + 40 untracked, a patch cut at 20,000 chars ("... cut"), all patches 200,000 chars. patch = hunks only (@@ lines, " ", "+", "-").
## GET /room/pictures?id=%3 -> {"pictures":[{"n":0,"name":"shot.png","kind":"read"|"written"|"seen"}]}   the latest up to 6 images Claude handled in this window's session (same pane to session map as /room/last): Read/Write of an image file, or an image block in the transcript. n = 0 is the newest.
## GET /room/picture?id=%3&n=0 -> the image bytes (Content-Type from the file or block, max 12 MB); 404 if gone.

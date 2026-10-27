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
}

## GET /room/last?id=%3   -> {"text": "..."}   Claude's whole last message, markdown stripped (for "Read all")
## GET /room/screen?id=%3 -> {"text": "..."}   last 60 lines of the pane, ANSI stripped (for the screen view)
## POST /room/act {"id":"%3","action":"go"}             -> {"ok":true,"said":"go"}   runs one action id
## POST /room/send {"id":"%3","text":"free text"}       -> {"ok":true}            types text + Enter (from Write)
## GET /projects -> {"projects":[{"name":"console-core","path":"...","uses":2355}, ...]}  top 20, folders that exist
## POST /project/open {"path":"...","mode":"continue"|"new"} -> {"ok":true,"id":"%9"}  new tmux window running claude
## /events (exists) also delivers {"type":"room","id":"%3","name":"...","state":"needs"} when a room turns "needs".

## Action ids
Claude room: option:<text> (send that text), go, yes, commit, push*, compact (/compact), explain ("i dont get it, explain simpler"),
recommend ("what do you recommend?"), research ("research online first, then recommend"), progress ("where are we? what is done and what is left?"),
hold ("hold on, dont make changes yet"), stop (Escape key), clear* (/clear), readall, screen, write.
Shell room: again (Up then Enter), make:<target> (top Makefile targets of that folder, ordered by ~/.zsh_history counts, max 3), stop (Ctrl-C), screen, write.
* = the phone asks "No, go back" / "Yes" before sending. readall, screen, write are handled on the phone (no POST /room/act).

# Rooms: your laptop workflow from the earbud

Status: built (2026-10-27): rooms, actions, quoted options, screen view, Projects, New project, order learning. Checked on the real phone (e2e `rooms_go`, `rooms_new_project`; a real send into a scratch tmux window). Version 2, 2026-10-27, rewritten from the owner's full records (version 1 guessed too much).
Scripts that produce every number: `bridge/research/` (rerun: `python3 bridge/research/extract.py`, then `analyze.py`, `coverage.py`, `draft.py gemma3:4b 40 0.5`; they read `~/.claude` and write `/tmp/wf/`).

## The problem
The owner works all day in tmux with many Claude Code agents at once (`~/.claude/me.md`).
Away from the laptop, sessions wait. Goal: from the earbud (no screen, no microphone), know which window needs you, and answer the common cases.

## A picture: an office floor
- The floor is tmux. A room is one tmux window (`me:2 os`). The worker is Claude Code or a shell.
- The phone walks the hallway with you; at each door it says what the room needs.
- The desk clerk is `bridge/server.py`: it looks into rooms and types for you (`tmux send-keys`).

## Evidence (6,328 replies to Claude, 1,099 sessions, 2026-09-25 to 10-27)
| Finding | Number | Design consequence |
|---|---|---|
| Your next prompt goes to another session | 46% of prompts within 30 min | The rooms list is the center. |
| Wait before you reply | median 92 s, 1 in 10 over 8 min | Telling you "os needs you" saves real time. |
| Claude works per prompt | median 26 s, 1 in 10 over 7.6 min | Rooms change fast; the phone must be live, not polled slowly. |
| Prompts in auto mode | 92% | Permission prompts are rare (142 denials in 56,000 tool calls). The current bridge serves the rare case. |
| Claude message has your status block ("What needs me") | 49% since Oct 13 | The summary is already written by Claude. Read it; no model. |
| After a status block, you reply "go" / "yes" | 164 / 62 | "Go" first when the block asks something. |
| The block names the reply itself (`say "go" or "keep 15"`) | 580 of 2,116 blocks | Read those quoted options as the first buttons. |
| Replies a short intent menu covers (12 words or less) | 20%: approve 764, commit/push 237, compact/clear 88, explain simpler 80, progress 26, recommend 26, hold 24, research 17, close 12 | The action list (below), in that order. |
| You interrupt Claude ("Request interrupted") | 5% | A "Stop" action. |
| You send while Claude still works | 23% of replies follow no final text | Sending while busy must work (Claude Code queues typed input). Version 1 "idle only" was wrong. |
| With a screenshot | 16% | Not possible from the earbud. |
| Longer free replies | about 60% | Not predictable. See "Local model". |
| Shell history | make 271, cd 145, claude 135 | Shell rooms need: Run again, Make, Stop. |

## Local model: tested, and where it does NOT help
- Laptop: M4 Pro, 48 GB. `gemma3:4b` 0.5 s warm. `gemma3:1b` gives wrong output.
- Test (`research/draft.py`): 40 random free replies (4 to 40 words). The model saw Claude's last message and wrote 3 likely replies.
  Match with your real reply: **0 of 40** (fixed list: 5%). Mean similarity 0.20.
- Why: your free replies carry new facts only you have ("i hate that they are in the middle so i cant break blocks", "does it spread if it has flowers above?"). No model can guess them. A bigger model does not change this (not tested; the missing part is information, not skill).
- So the model has 2 small jobs only:
  1. When a Claude message has no status block (51%): shorten it to 2 sentences.
  2. Shell rooms: the screen to 7 words ("build failed, 2 tests").
- Free text from the earbud: the Write keyboard, slow. Real long prompts stay at the laptop.

## Reuse what already exists (do not build twice)
- `~/.config/tmux/agent-status.sh` already keeps each window's state in `/tmp/agent-status-$UID/<window id>`: `notified busy … action_required`. Rooms reads it.
- `~/.claude/bin/pane_session.py` already maps each tmux pane to its Claude session (`~/.local/state/claude-panes/`). Rooms uses it to find the transcript and the last message.
- `~/.config/tmux/program-name.sh` already names windows. Room names = window names.
- `bridge/server.py`, hooks, token, Tailscale, the phone tool `hub:claude`: grow them, do not replace them.

## What you hear (a small example)
1. The Claude earcon. Hold, pick "Laptop" (it absorbs today's "Claude" tool).
2. Rooms, urgent first (at most 8, a `ScanList`):
   "os, needs you" / "hardware, failed" / "wereable, done" / "layout, working".
3. Click "os". It reads Claude's "What needs me" line: "say go for the design doc."
4. Actions: "Go" / "Yes" / "Commit" / … (below). Click "Go". The laptop types `go` + Enter. "Sent to os."
5. Triple click: back to rooms.

## Room states (from agent-status files and hooks; no model)
needs you (permission, question, or a status block that asks something) > failed (shell) > done (not visited since) > working > idle.

## Actions in a Claude room (one `ScanList`)
1. The quoted options from the "What needs me" line, if any.
2. Go, Yes, Commit, Push (asks "No, go back" first), Compact.
3. Explain simpler (sends "i dont get it, explain simpler"), What do you recommend, Research online first, Where are we (progress), Hold (sends "hold on, dont make changes yet").
4. Stop (Escape: interrupts Claude). Read all. Write (keyboard). Clear (asks first).
Order then adapts by your picks per room, like `AppWeights`.

## Shell room
The 7-word summary, then Run again, Make, Stop (Ctrl-C), Write.

## Tension to name (from `me.md`, not decided for you)
Your busiest hours are 22:00 to 02:00, and you feel wired after work. An earbud that calls "os needs you" follows you to bed.
Option, off by default: quiet hours where the earcon does not play (rooms still readable when you ask). Your call.

## Security
`send-keys` is a remote keyboard: only over Tailscale with the token; only fixed actions or text you wrote; every send logged in `~/.config/invisible-bridge/sends.log`.

## Build order (each step has a check)
1. Server `/rooms` from agent-status files + `pane_session` + transcripts. Check: unit test with a fake state dir and a private tmux (`tmux -L test`).
2. Phone "Laptop": rooms list and the status line. Check: `tools/e2e.py` scenario against `bridge/fake_phone.py`-style fake server.
3. `/room/ID/send` (text, Enter, Escape, Ctrl-C), queue-safe. Check: a test pane running `cat` shows the text.
4. Quoted-option parsing. Check: replay on the 580 blocks: options found vs your real reply (today 184 match).
5. Summaries for messages without a block, and shell rooms (`gemma3:4b`). Check: on the replay set, the summary keeps the line that your reply answered (scored by `research/draft.py`-style similarity).
6. Order learning per room. Check: replay, top-3 hit rate beats the fixed order. Result (`research/order_replay.py`, 645 replies that map to an action): fixed 75.2%, learned 80.6% (top-1: 32.4% to 37.2%).
7. New project: area (parent folders of your projects), name and first ask with Write; the laptop makes the folder, `git init`, starts Claude, types the ask.

## How we know it works
- Replay: share of your replies sendable by one click. Today's honest number: 14% exact (fixed list + quoted options), 20% with the intent menu, 25% with Stop.
- Real use only (the replay cannot tell): how often you use it away from the laptop, and whether the wait before you reply drops from 92 s.

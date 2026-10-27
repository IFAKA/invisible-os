# Rooms: your laptop workflow from the earbud

Status: design, not built yet. Date: 2026-10-27.

## The problem
The owner works in tmux: 3 to 6 windows, most with Claude Code, some with a shell.
Away from the laptop, work stops: a session waits for a reply (typical wait 85 s, 1 in 10 waits over 12 min).
Goal: walk all windows with the earbud (no screen, no microphone), hear what each needs, and act in it.

## A picture: an office floor
- The floor is tmux on the laptop.
- A room is one tmux window (`me:2 os`).
- The worker in a room is what runs there: Claude Code, a shell, a build.
- The phone is you walking the hallway with eyes closed; at each door it says what is going on inside.
- The desk clerk is `bridge/server.py`: it looks into rooms (`tmux capture-pane`) and types for you (`tmux send-keys`).
- The helper is a local model on the laptop: it turns a long screen into one short line.

## Evidence (owner's records, 2026-09-25 to 2026-10-27, `~/.claude/history.jsonl`)
| Fact | Number |
|---|---|
| Prompts | 6,481 |
| 1 to 3 words | 27%: go 184, yes 182, ok 54, commit 44, do it 40, push 19, continue 7 |
| Session commands | /model 307, /clear 160, /compact 85 |
| 4 to 15 words | 35% (many repeat: "can you", "what do you recommend", "i dont get it") |
| Over 15 words | 38% |
| With a screenshot | 16% (not possible from the earbud) |
| 3+ sessions in a busy half hour | about half |
| Shell commands (`~/.zsh_history`) | make 271, cd 145, claude 135 |

Prior art: Claude remote control, MuxCLI, Really Remote, Claudette Echo (all need a screen or voice);
tmux-buddy, TmuxAI, the "observer" pattern (screen to short status). Nobody does eyes-free, no-mic, one-button.

## What you hear (a small example)
1. Phone: "Claude sound." You hold, pick "Laptop".
2. Rooms, most urgent first (at most 8, a `ScanList`):
   "os, needs you: commit?" / "hardware, build failed" / "wereable, working" / "layout, idle".
3. Click "os". It reads the short summary, then the actions:
   "Commit" / "Go" / "What do you recommend" / "Read all" / "More replies" / "Write".
4. Click "Commit". The laptop types `commit` + Enter in that window. Phone: "Sent to os."
5. Triple click: back to the rooms.

## Rooms list
- One room per tmux window (active pane). Source: `tmux list-panes -a`.
- State, in this order of urgency:
  1. **needs you**: Claude asks a permission or a question (the hooks we have), or Claude stopped and you have not answered.
  2. **failed**: a shell command ended with an error (exit code from a zsh `precmd` line that writes to a file; optional).
  3. **done**: Claude or a command finished since you last visited.
  4. **working**: the screen changed in the last 5 s.
  5. **idle**.
- Claude state needs no model: the hook runs inside the pane, so `$TMUX_PANE` tells which room it is.
- The label is at most 7 words (teaching rule): "name, state: short need".
- When a room turns "needs you", the phone plays the Claude earcon (exists: `ClaudeWatch`).

## Actions in a room (one `ScanList`, order learned from use)
- **Claude asks a permission:** Allow, Deny, Always allow (exists today).
- **Claude waits for you:** the summary first, then:
  - Suggested replies (3, see "Local model").
  - Fixed replies from the evidence: Go, Yes, Commit, Push, Continue, What do you recommend, I don't get it.
  - Read all (the full last answer), Write (the keyboard program, for free text).
  - Session: Clear, Compact, Model.
- **Shell:** the summary, then Run again (last command), Make, Stop (Ctrl-C), Write.
- Order = counts of what you pick in that kind of room, like `AppWeights`; start from the counts above.
- Push and Clear ask "No, go back" / "Yes" first (the emergency confirm pattern), because they cannot be undone.
- Text is sent only when the room is idle; if Claude is working, it waits and says "Queued for os".

## Local model (laptop: M4 Pro, 48 GB; Ollama and MLX installed)
Measured 2026-10-27 on a real pane: `gemma3:4b` 0.5 s warm, 5.8 s cold; `gemma3:1b` 0.4 s but wrong output (unusable).
First lesson: given raw screen text, 4b gives generic lines ("needs command input"). So the model gets structured input, not a screen dump.
Four jobs:
1. **Summary:** Claude's last answer (from the hook's `transcript_path`, not the screen) to 2 sentences; a shell screen to 7 words.
2. **Suggested replies:** candidates come from your own history (the fixed list plus your frequent medium prompts); the model only ranks them for this moment ("want me to commit?" puts Commit first).
3. **Drafted replies:** for a question that needs more than one word, the model writes 3 full replies from the context; you hear them and pick one.
4. **Nothing is sent without your click.** The model never types on its own.
- Keep the model loaded (`keep_alive`) so every call is warm.
- Everything stays on the laptop; screen text never leaves it.
- Model choice (4b vs Qwen 27B in MLX) is decided by measurement (step 4), not by guess.

## Security
- `send-keys` is a remote keyboard. Only over Tailscale, with the token (as today).
- Shell rooms get only the fixed actions or text you wrote; no model-made shell commands.
- Every send is logged on the laptop (`~/.config/invisible-bridge/sends.log`).

## Build order (each step has a check)
1. **Server rooms:** `/rooms` (list + state), `/room/ID/screen`. Test with a private tmux server (`tmux -L test`) and fake panes.
2. **Phone Rooms list:** `hub:laptop` absorbs `hub:claude`; reads rooms and summaries by rules (no model). Check: `tools/e2e.py` scenario with a fake server.
3. **Send fixed replies:** `/room/ID/send`, idle-only, queue. Check: a test pane running `cat` shows the text.
4. **Summary model:** 50 real Claude answers from the transcripts; score 4b vs 27B for "would I know what to reply" and latency; halving, same samples (fast-search rules).
5. **Suggested replies:** replay the 6,481 prompts: how often your real reply is in the top 3. Target: beat the plain count order.
6. **Drafted replies.**
7. **Shell rooms:** Run again, Make, Stop; optional zsh exit-code line.

## How we know it works
- Share of your prompts you could send from the earbud: today 27% by count rules; target 40%+ with suggestions and drafts (measured by replay in step 5).
- Typical time before you answer a waiting session: today 85 s; should drop when the phone tells you.

## Not covered
- Screenshots (16% of prompts) and long free text: still the laptop, or Write.

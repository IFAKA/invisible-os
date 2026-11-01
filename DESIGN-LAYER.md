# The Layer: Invisible OS as the ears of the tools you already use

Status: design, 2026-11-01. Nothing here is built yet. It builds on `bridge/DESIGN-ROOMS.md` (rooms, actions, stories) and changes how you reach them.

## The problem (owner, 2026-11-01)
- The owner used Invisible OS while building it, then went back to the laptop: tmux, agents, browser.
- Reason: "I have to wait for things to happen in order to move. It's not on demand."
- Measured (trace 2026-10-28, 11:27): one "Go" to an agent took **37 s** from wake to "Sent".
  - 7 s to reach "Laptop" on home (3.8 s an item).
  - 5 s for the window's story.
  - 2.2 s for each option until the right one.
- Second point from the owner: don't make the phone or the laptop the product. Make one system that your devices plug into. Each device adds what it's already good at, so no app has to be rebuilt.

## A picture: a good assistant at a desk
- You don't ask the assistant "read me everything". The assistant comes to you only when something needs you, with one sentence and the likely answer: "Ana's tests passed. Commit?" You nod or shake your head.
- The assistant is the bridge plus the phone app.
- The things that need you are agent windows, permission asks, chats and timers.
- The nod is one click. The head shake is two clicks.
- "Tell me more" is a hold, which opens today's full window options (the Rooms walk).
- Silence means "later": the item waits in a queue and nothing else happens.
- When you sit at your desk (the laptop is in use), the assistant says nothing. You can see it yourself.

## Evidence used
| Fact | Source | Consequence |
|---|---|---|
| Scanning menus is slow: 1 to 3 words a minute even for experts | Nomon eval (arXiv 2204.01619), Koester & Simpson | Menus only for rare choices. The common answer needs no menu. |
| Ordering by likelihood cuts switch presses about 40% | Roark 2013/2015 Huffman scanning | The pushed answer is the most likely one. |
| Learned order, measured 2026-11-01 on 671 real replies that map to an action: answer A right 37.6%, A or B 66.5%, top 3 79.9% | `bridge/research/order_replay.py` (`replay(..., top=2)`) | Click or two clicks covers two thirds of short replies. These are about a quarter of all replies (DESIGN-ROOMS.md: 25% with Stop); the rest are free text and stay at the laptop. |
| Your next prompt goes to another session 46% of the time; you wait a median of 92 s before you reply | DESIGN-ROOMS.md | Telling you the moment one needs you saves real time. |
| Alerts should scale: silence, then a sound, then a spoken line, then full detail on request | Nomadic Radio (Sawhney & Schmandt, CHI99, ToCHI00) | Four levels (below). |
| Keyboard and mouse activity alone predicts "do not interrupt" 82% of the time | Fogarty & Hudson, TOCHI 2005 | Laptop in use means silent. |
| Interrupt at task boundaries | Iqbal & Bailey, CHI06 | Speak when an agent stops or asks, never while it works. |
| Claude Code hooks: `Notification` (permission_prompt, idle_prompt, agent_needs_input), `Stop` (last message), `PermissionRequest` (tool, command; the hook can answer allow or deny) | code.claude.com/docs/en/hooks | The bridge already gets these. Answers go back through the same hook. |
| Others push agent events to a phone (tap-to-tmux, claude-ntfy-hook, claude-remote-approver) | GitHub | All of them are visual pushes with tap buttons. None is audio-only with a button answer. That gap is ours. |
| Free replies can't be guessed (0 of 40) | DESIGN-ROOMS.md, `research/draft.py` | Don't try. Free text stays at the laptop, or goes through the Write keyboard. |

## The design

### 1. One queue of needs ("the Line")
- Each source puts **items** in one queue. An item is: who (a room name or a contact), one line (at most ~12 words), answer A, an optional answer B, and how urgent it is.
- Sources:
  - rooms: needs you, done, failed
  - permission asks from the hook
  - chats from the notification listener
  - timers and calendar
  - anything on the laptop, through `POST /line` (a script, CI, a build). This is the "plug in, don't rebuild" door: one HTTP call, and any tool gets ears.
- Answer A comes from what already exists: the quoted option from the "What needs me" line, else the learned top action of that room (`order_replay`), else "Go". Answer B is the learned second one, or "No" for a yes/no ask.
- A newer item about the same room replaces the older one.

### 2. Push: speak only when it needs you
Levels, after Nomadic Radio:
| Level | When | What you hear |
|---|---|---|
| 0 Silent | Laptop in use (input in the last 2 min and screen unlocked), in a call, a program running, quiet hours | Nothing. The item waits in the queue. |
| 1 Sound | A room is done after working over 30 s | The done tone only. |
| 2 Line | A room needs you, a permission ask, a chat from a person | Tone, then "os: tests pass, commit? Click go, two for push." |
| 3 Detail | You ask for it (hold) | Today's window walk: story, all options. |

- Rules: at most one line every 20 s, and one per room per 60 s.
- An item that's already waiting is not spoken again. You get it when you wake.
- If music plays, it's ducked under the line and comes back after the answer window.

### 3. Answer without a menu
For 8 s after a line ends (the answer window), the button belongs to that item:
| Press | Does |
|---|---|
| 1 click | Answer A. Speaks "Sent to os." |
| 2 clicks | Answer B. |
| Hold | Level 3: opens that window's full options, the Rooms walk you have today. |
| 3 clicks | "Later": the item goes back to the queue, and it says nothing. |
| No press | Same as later. After the window, clicks go back to play/pause. |

Safety rules (enforced by a unit test):
- A single click is never an action you can't undo: push, clear, deploy, or a permission for a command that deletes or pushes.
- For those, the line says "Hold to confirm". A is then "No".
- The phone also shows the item, so what you hear is what you see.

### 4. Wake opens the queue, not the menu
- Hold (wake) with items waiting: it reads the most urgent item straight away, with the same answer window. After the answer, the next item. Empty queue: "All clear", then home, the same as today.
- 4 clicks with the menu closed (unused today) replays the last line. This is "what was that?".
- Home, the app tree, Find and the games stay. They become the drawer you open on purpose (3 clicks from the queue), not the door to everything.

### 5. Devices plug in; nothing is rebuilt
- The **laptop** runs the work: tmux, agents, browser, builds. The bridge sees it through hooks, tmux and `POST /line`.
- The **earbud** is ears plus one button: lines and answers.
- The **phone screen** is for what you must see: the item shown, "Show on phone" (live site, diff, pictures), the Write keyboard for real text.
- The **browser** is not rebuilt. A page you need to see goes to "Show on phone". A page event that should reach you (a deploy is done, a long download, a reply on a site) comes in through `POST /line` from a small script or extension. That last part is not built yet, and only if you need it.
- Rule: a new capability means a new source that posts items, or a new answer that a source accepts. It never means a new app copy inside Invisible OS.

## Criteria for "done", and the check for each
| # | Criterion | Check |
|---|---|---|
| C1 | From a pushed "needs you" to the answer sent: one line plus one click; listening time 6 s or less (today 37 s) | Flow test in fake time: an `/events` needs item, then one click; assert the send and the audio ms |
| C2 | Share of your real replies sent by click or two clicks from the push | Replay `order_replay.replay(top=2)`: 66.5% of mapped replies (2026-11-01). Rerun after each change to A/B; must not drop |
| C3 | Silent while the laptop is in use | Unit test of the level rule with fake idle time; the bridge exposes idle (macOS `ioreg HIDIdleTime`) |
| C4 | No undoable-only action on a single click | Unit test over all A answers: risky ones need a hold |
| C5 | Hear equals see: the shown item equals the spoken line | `ParityRecorder` Flow test (KIT.md Cue step 5) |
| C6 | No microphone | Existing rules (`checkVoiceKit`) |
| C7 | You actually keep using it | Only real use can tell: days a week with an answered push, and whether the 92 s wait before a reply drops. Read from `sends.log` and the trace after 2 weeks |
| C8 | The spoken line keeps what is wanted (the question is never cut) | `python3 bridge/research/line_replay.py` over 1753 real answered messages (code fallback only): the line keeps the ask in 83.0% (old `shorten(12)` cut: 50.3%), 100% of lines <= 16 words, "..." in 0.1% (old 58.4%), mean 10.3 words (old 9.8). Unit tests `FitLineTest`. The model question test (gemma3:4b, 40 messages) runs at the end of the same command; skipped when Ollama is down (2026-11-01: skipped) |

## Build order (each step has its check)
1. Done 2026-11-01 (`cd bridge && python3 -m unittest test_layer`). Bridge: the `/line` queue and item format, fed from rooms, hook asks and `POST /line`. A and B come from the quoted options and the learned order. Check: `bridge/test_*.py` with fake rooms.
2. Done 2026-11-01: replay top-2 share is 66.5% (C2). Rerun: `mkdir -p /tmp/wf && python3 bridge/research/extract.py`, then `replay(rows, ..., top=2)`.
3. Done 2026-11-01 (same command, `IdleTest`). Bridge: laptop idle in `/info` and in each event. Check: unit test with a fake `ioreg`.
4. Done 2026-11-01: phone push line and answer window (`PushLine`, `LineRules`, `LineQueue`; `RoomEvents` hands events with a line to it). Flow tests in `FlowLineTest`: `line_one_click_sends_the_action_within_six_seconds` (C1), `two_clicks_send_answer_b`, `risky_single_click_sends_nothing_and_hold_sends_it` (C4), `ask_click_allows_and_two_clicks_deny`, `a_second_line_within_20_seconds_is_queued`, `hold_opens_the_full_walk_at_that_room`, `parity_push_line_and_hint` (C5). Rules in `LineRulesTest` (C3, C4).
5. Done 2026-11-01 (phone run not yet made): wake reads the queue first (asks, rooms, lines, then All clear.), 4 clicks replays. Flow tests `laptop_active_is_queued_silently_and_wake_reads_it`, `wake_with_an_empty_queue_is_the_normal_wake`, `wake_reads_asks_first_and_triple_click_goes_home`, `no_press_keeps_the_item_and_walks_on`, `four_clicks_replay_the_last_line_while_it_is_unanswered`. The e2e scenario `line_push_answer` (real button over Bluetooth) is written; run it with the phone lease.
6. Two weeks of real use, then C7 from the logs. Change A/B, the levels and the rules from that data, not from guesses.

## Not decided by me (yours)
- Quiet hours: off by default, as in DESIGN-ROOMS.md. You work 22:00 to 02:00. A line in bed may keep you wired. Your call.
- Whether chats from people share the queue with agents (my default: yes, ranked lower than an agent that needs you).

# Best UX for FAKA's screen-off controller (design, 2026-10-25)

## Goal
Reply to WhatsApp (mostly Spanish) and do small phone jobs with ONE earbud button, screen off, silent, little battery, with the fewest gestures and the fewest mistakes.

## My usage (measured from the phone's usage stats; yearly-like totals)
Brave+Chrome 17.6k min (screen only, out of scope), WhatsApp 6.6k min and most launches (messaging = core), ChatGPT 4.6k, Teams 1.8k, WeChat 1.5k, Clock 1k, YouTube Music 0.7k. Music keys already native. Messaging is the hardest part.

## Criteria (each has a check)
| # | Criterion | Check |
|---|---|---|
| C1 | Reply to a new message in <= 3 gestures, <= 9 s | cost model below; later a real log of gestures per reply |
| C2 | A wrong send can be undone | unit test: triple in the 3 s window cancels |
| C3 | No need to hear every word (click skips speech) | test: click while speaking stops TTS and moves |
| C4 | Know where you are without words | each layer has its own short tone |
| C5 | Learns: frequent choices come first | log of choices; order test |
| C6 | Battery: nothing runs unless pressed; no network | no polling, no model by default |
| C7 | Spanish read and Spanish reply | detector + TTS locale test |

## Research (prior art)
- Single-switch scanning cost = number of items you pass x wait time. Frequency/prediction order cuts it:
  Broderick & MacKay (https://arxiv.org/pdf/0909.2450), Roark et al. Huffman scanning with language models (cited there),
  ColorCode (https://arxiv.org/pdf/2204.09745). Two independent lines agree: put the likely choice first.
- Message in, likely reply out: CHI 2023 AAC paper (https://dl.acm.org/doi/fullHtml/10.1145/3544548.3581560) and the Microsoft two-step patent
  (https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/11947902): offer a few full replies, user picks one.
- Synthesis (mine): the big cost is not choosing, it is walking menus. So: (1) skip the walk, (2) pre-fill the answer from the
  message itself, (3) protect with undo instead of with extra "Back" steps.

## Local experiment: gesture cost of one reply (script in this session)
Current: 16 gestures, ~27 s. Proposed best case: 2 gestures, ~6 s (incl. 3 s undo). Second choice: 3 gestures, ~8 s. Passes C1 on paper.
(Assumes 0.9 s per gesture + 0.8 s speech. Real numbers need real use.)

## The design (one design)
1. **Context quad.** Arrival of a WhatsApp/Teams/WeChat message plays a short tone (favourites get a different tone; no words, silent to others).
   Quad within 2 min opens STRAIGHT on that message: speaks "Ana. Invitación" (tag). No menu walk. No new message: quad opens the normal menu.
2. **Best guess first.** The cursor starts on a ready full reply, not on Back. Candidates are built from the message:
   invitation -> "Sí, esta noche" (time words in the message like hoy/mañana/esta noche are reused), "No puedo hoy", "Luego te digo", then More, Read, Back.
   Click = next candidate (spoken). Double = send.
3. **Undo instead of Back-first.** After send: a 3 s rising tone. Triple in that time cancels ("Cancelado"). Replaces the fear of a wrong double click.
   Back is still an item (last) and hold still exits.
4. **Skip speech.** A click stops the speech and moves on, so you can click ahead. Items keep fixed places (muscle memory).
5. **Tones per layer** (Music, Messages, Timer, Info) so words are optional.
6. **Learn.** Log each chosen reply locally; candidates reorder by frequency (most used = 1st).
7. **Clock.** Timer layer remembers the last length and offers it first.
8. **Out of scope on purpose:** browsers, ChatGPT (need eyes). Music start (YouTube Music blocks it).
9. **Later (needs the user's yes):** New message through a linked device, see NEW-MESSAGE-IDEA.md.

## Atomic build steps (each with a check)
1. Speech interrupt on click + tone per layer. Check: unit test + hear it.
2. Undo window (3 s, triple cancels) around every send. Check: unit test with fake clock.
3. Context quad: open on newest unread message within 2 min. Check: unit test; real test with WhatsApp Web.
4. Candidate builder (Spanish and English, time words from message) + candidates-first menu. Check: unit tests with example messages.
5. Choice log + frequency reorder. Check: unit test; file on disk.
6. Arrival tones (favourites different). Check: hear it.
7. Timer remembers last length. Check: unit test.

## Gaps only real use can close
- Whether the best guess is right often enough (needs your real messages; the log from step 5 measures it).
- Spanish voice quality; how undo and tone timing feel; HyperOS killing the service with the screen off for hours.

## BLOCKED / limits
- Starting YouTube Music from idle with the screen off: its queue is empty and its media service rejects our app. Evidence in session; user chose to start music by hand.
- Starting a NEW WhatsApp chat: needs the linked-device route (risk) or the screen. Waiting for the user.

## Bias check v2 (from scratch, same day)
Honest: v1 above was NOT bias-free. I kept the old shape (menu tree, click = next, Back item, speech for everything) and only patched it.
Hard limits found in real tests: only click COUNTS (1, 2, 3, 4+) reach us on the lock screen; hold does not. So the alphabet is 4 symbols.
From scratch, three ways to choose with one button:
- Scan (current): 1 gesture per item passed. Cost grows with list length.
- Auto-scan: phone reads options one by one, one click picks the one you hear. 1 gesture, but you wait.
- Direct code: the number of clicks IS the choice (1 click = Yes, 2 = No, 3 = Later, 4 = more). 1 gesture, no wait, but you must remember the codes.
Best for FAKA: direct codes for the few things you do all day (reply), auto-scan for lists that change (who to message), scan only as a fallback.
Revised design: quad = "wake" (tag spoken: 'Ana. Invitación'); then within 4 s ONE code sends: 1 = Yes, 2 = No, 3 = Later, 4 = Read/more. Same codes for invitation, question, request (muscle memory); THANKS/INFO: 1 = Ok. 3 s undo tone (triple cancels). Nothing pressed in 4 s = leave silently.
Cost: reply = 2 gestures every time (not only when a guess is right), about 6 s. Hub with no message: quad, then 1 = Messages list, 2 = Timer, 3 = Info/volume, 4 = Exit.
Unknown until real use: whether 3 replies cover ~90% of your answers (the log will show), and whether you remember the codes.

## v3: the whole launcher from scratch (replaces the menu tree)
Core idea: the phone has two kinds of work. PUSH = things that come to you (messages, timer done). PULL = things you ask for (status, timer, volume). A tree of layers treats both the same. Better: push first, pull second, ONE grammar everywhere.
**Wake:** 4 clicks (works on lock screen). The phone answers by context, no menu walk:
- Something waiting (message, finished timer, alarm) -> it speaks the MOST urgent item with a short tag.
- Nothing waiting -> hub: "Status, Timer, Volume, More".
**One grammar (same meaning in every screen, learn it once):** 1 click = YES / main action. 2 = NO / second action. 3 = NEXT (skip, snooze). 4 = MORE (read it, options). No code within 4 s = leave silently. 4 clicks again = cancel and leave. Any send/start has a 3 s undo tone (3 clicks cancels).
**Screens:**
- Message "Ana. Invitación": 1 Yes-reply, 2 No-reply, 3 next waiting item, 4 read it / more replies (Later, Cant now, Tell me more, Compose).
- Timer finished: 1 stop, 2 +5 min, 3 ignore.
- Hub: 1 Status = ONE sentence (time, battery, unread count, next alarm; no sub menu). 2 Timer: 1 = 5 min, 2 = 10, 3 = 25, 4 = last used. 3 Volume: 1 up, 2 down. 4 More: torch, Do not disturb, exit.
**Expert mode:** speech prompts only after 2 s of silence; a learned user types the codes straight after the wake tone. Verbosity drops as you succeed.
**Learning:** log each wake + code locally; the hub order and the reply set adapt to what you really use (most used = code 1).
**Cost vs now:** reply 2 gestures (was 16). Status 2 (was ~5). Timer 5 min 3 (was ~8). Volume 3 (was ~6). Stop ringing timer 2.
**Dropped on purpose:** layer tree, Back item, Read-everything-first, per-item scanning, Quick picks, Mac model. Browsers and ChatGPT stay screen-only.
**Still assumptions (only real use closes them):** that these 4 hub areas cover your daily screen-off needs (the log shows what you reach for); that 4 codes are easy to remember; click-count detection stays reliable at 3 vs 4 (check with a log of mis-counts).

## v3.1: local AI where it pays (on the phone, lazy, with rule fallback)
Honest: v3 used only rules and templates. AI was dropped, not weighed. Where it can really help (all on the phone, no network):
1. **Understand** the one message you wake on: type, urgency, time words, topic. A small model (Gemma 3 270M, fine-tuned or few-shot; built for classification and entity extraction, 140+ languages, ~200 MB int4) handles Spanish slang better than word rules. Rules stay as fast path and fallback.
2. **Digest** to save ear time: several or long messages become ONE short sentence ("Ana: cena hoy a las 9, pregunta si vas"). Ear time is the real cost here. Probably Gemma 3 1B (584 MB), quality of Spanish to be tested.
3. **Translate** WeChat (1.5k min) on-device with ML Kit Translation so you hear it in Spanish. Check that the Chinese pack works offline.
4. **Your style (later):** few-shot from your own sent replies, only behind code 4 (more).
**Battery rule:** the model runs only when YOU wake on a message, for that one message, never on arrival, never in the background. Google's own number: Gemma 3 270M int4 = 0.75% battery for 25 conversations on a Pixel 9 Pro (Google claim, not our phone). Gate: measure latency and battery on the Redmi (dumpsys batterystats) before keeping it; if > 2 s wait or noticeable drain, stay with rules.
Sources: https://developers.googleblog.com/en/introducing-gemma-3-270m/ , https://venturebeat.com/ai/google-unveils-ultra-small-and-efficient-open-source-ai-model-gemma-3-270m-that-can-run-on-smartphones

## v3 built (2026-10-25)
Done: ControllerMode is now the direct-code launcher (screens HUB, MESSAGE, MORE_MSG, COMPOSE, TIMER_DONE, TIMER_PICK, VOLUME, MORE_HUB, SENT). Old layered menu, Back items and scanning are gone. Keyed injected timers (timeout 4 s, prompt 2 s, undo 3 s). Wake picks the screen by context (ringing timer, message that arrived under 2 min ago, favourites, others, else hub). Quick replies with time-word pre-fill (MessageLogic.timeWord/quickReply). 3 s undo for sends and timer starts (3 clicks cancel; pending send survives leaving the mode). Status sentence, last-used timer in prefs, volume stays on screen. ChoiceLog writes files/choices.log and gives favourites. Listener plays an arrival earcon (favourites differ), no speech. Not built: v3.1 AI, new-message sending. 47 unit tests pass; APK installed.
Design choices: the prompt after 2 s restarts the 4 s timeout (so a silent leave takes about 6 s after a wake); Ask details is sent at once (no Extra step); after a timer start the mode leaves; second quad within 1 s of wake = cancel.
Unverified on the device: the earcons (sound and distinctness), TTS stop on click, whether 4-click wake twice within 1 s is easy, the timer from the screen-off path (own timer), RemoteInput send after the undo window, arrival tone timing, choices.log file content, DND and torch from the new hub.

## v4 auto-scan built (2026-10-25)
What: the v3 click-code grammar is gone. On every screen the phone now speaks the options ONE AT A TIME (short speech, 0.8 s pause, soft tick between items, a different ding at the end) and loops by itself. 1 click = pick the option spoken last (bound to the item whose speech started last before the FIRST press; a press within 500 ms of the next item's start picks the one before). 2 clicks = list again from the first. 3 = back one level (top screen: leave). 4 = leave (LEAVE tone). Two full silent loops = leave (LEAVE tone). Hold still exits.
Why: blind use showed the user could not see or memorise the click codes, heard all options at once, and could not listen again. With auto-scan nothing has to be remembered: wait for the word, click.
Kept: message auto-read, 5-min queue, undo window, hub, compose, timer, volume, choice log. Blind-send protection stays (reply options exist only after the text started and was read or cut by a click). The 4 s / 7 s silence timeouts and the code hints are removed.
Details: a click while the message text is read skips it and starts the options; options start after the text ends. Undo: "Sending. Click to cancel" and ANY click within 3 s (counted from the end of that sentence) cancels (CANCEL tone). Learning mode (first 15 picks): one hint at wake ("Click when you hear what you want. Two clicks, again. Three, back."), then none. Status speaks its sentence and leaves. Volume/Torch/DND keep the screen and scan again after their speech.
Ordering: `OptionRanker` interface (ControllerMode.kt). Default `CountRanker` uses pick counts from ChoiceLog (ties keep the default order). Used for HUB and for MESSAGE options (context = message type; intents like send:yes:INVITE). Hook: a language model can implement `OptionRanker` and be passed in Actions.kt.
Code: ControllerMode is a pure state machine with injected clock/timers and events onGesture, onSpeechStarted, onSpeechDone. Actions.kt forwards TTS onStart, the press length, and has TICK and DING tones. 57 ModeTest tests with a fake clock (+13 gesture tests).
Unverified on the device: how TICK and DING sound, TTS onStart timing (the 500 ms grace is tuned for it), whether 0.8 s pause and ~1.3 s per item feel right, Spanish option wording, the ranker with real counts.

## v4.1 (2026-10-25): shorter paths after blind testing
Problem: with many messages waiting, reaching Volume meant skipping every message.
1. Wake order: wake always opens the HUB list. Items: "Messages, N unread" (only if unread exist), Timer, Volume, Status, More, ranked by OptionRanker (CountRanker). Exception: a ringing timer or a message under 2 minutes old still goes first. "Messages" reads unread newest first; Skip on the last message returns to the hub.
2. Volume: after Louder/Quieter the phone speaks only the new level as a word ("seven", es "siete"; media volume index as is), then keeps scanning Louder, Quieter, Done. `ModeOutput.volumeLevel()` is the injected getter; the platform no longer speaks a percent.
3. Compose is ONE step: Yes, No, Maybe, Ask details (es Sí, No, Quizás, Detalles). When/Extra steps removed; the message's time word (hoy, tonight...) is pre-filled in the sent text.
Tests: 60 ModeTest (73 total). Unverified on the device: how the level word sounds in sequence with the list, real max volume range, Spanish level words (only used when a Spanish message is the current context).

## v4.2: longer gap between scanned items
User test: the pause was too short to think. `ControllerMode.ITEM_GAP_MS = 1600` (one constant) is the silence from the END of an item's speech to the start of the next; the tick plays 200 ms before the next item, inside the gap. Click binding, 500 ms grace and the two-loop leave rule are unchanged (they scale with the gap). One item now takes about 2.1 s, so a 4-item loop is about 8.4 s. Unverified on the device: whether 1.6 s feels right.

# Plan: from launcher to OS (2026-10-25)

Goal: a phone you can fully use with one button and your ears. Keyboard first, because search, names, notes and settings all need text.

## Keyboard: the stereo pair
- Two likely next words play at once: left ear one voice, right ear another. **Click** = left, **short hold (~0.5 s)** = right, **two clicks** = next pair, **three clicks** = spell (alphabet halves, left/right). Hold 3 s still leaves. No timer: the player sets the pace.
- Local AI: the word LSTM (1.4 MB, Utter `ml/data/lm`) ranks words each step; Gemma 4 E2B only builds whole-reply options in the background when a message arrives.
- Sim (`sim/keyboard_sim.py`, method D): -32% seconds per text vs word-by-word with equal spell cost; -54% if stereo spelling costs 4 s. Inputs are weak (34 invented texts, gemma3:1b ranker, 1.2 s per decision guessed). Sources: Ticker arXiv 1804.07777, Roark arXiv 1712.10073, MacKay-Broderick buttondasher2b.

## Steps (each has a check)
1. Ear test: play `sound/stereo-test/pair1..5.wav` on the earbuds (left = Samantha, right = Daniel). Check: you name the right word 9 of 10 times. Only the user can do this.
2. Gesture check: `GestureDetector` tells click, 0.5 s hold and 3 s hold apart. Check: unit test.
3. Port the LSTM to Android, off the main thread. Check: top-4 words for a prefix in under 50 ms on the Redmi.
4. Re-run the sim with the LSTM ranker and the user's real texts. Check: D still beats C by 30%.
5. Build the keyboard as an OS service (not a game): `ModeOutput` method, `Actions` entry, returns text to the caller. Check: unit tests plus a spoken-round-trip on the phone.
6. Use it: New message (free text), Search, Notes, rename.

## The OS list, in order (2026-10-25)
A real OS lets you do any task, not only the ones we built. Each item gets a check before the next starts.

Usable every day:
1. **Keyboard (Write).** Built, installed, not yet tested by ear. Check: the ear test and a real message written.
2. **Bridge to other apps.** The accessibility service reads any app's screen aloud and taps for you. Not built yet. Check: reply in one real app (WhatsApp) with only the button. Risk: badly labelled apps, apps that block it. Test it early.
3. **Calls.** Answer, reject, dial, call back. Not built yet.
4. **Lock and unlock by ear.** Not built yet. Needs the keyboard.

Safe:
5. **Interrupt queue.** One queue for messages, calls, timers, with priorities. Not built yet (messages only today).
6. **Help.** "Where am I" and "What can I do", on one gesture, from any screen. Not built yet.
7. **Recovery.** If the earbud disconnects, speech dies or the app crashes: say so, get back home. Not built yet.
8. **Setup.** Wi-Fi, Bluetooth, permissions without a screen. Not built yet.

Clean:
9. **One program rule.** One contract for every program (raw button, how to leave, close sound, who may speak), not only games. Write is the second program; use it to write the contract.
10. **Language rule.** One rule for English menu and Spanish text. Write has an English model only: get a Spanish one.
11. **Battery warning.** Not built yet.
12. **Helper mode.** A sighted person can fix problems. Not built yet.

Not needed: kernel, drivers, file manager, icon launcher (Android has them).

## Later (only with real users)
Custom Android build (AOSP) that boots into this shell. Linux only after that: the design is ideas, not Android code, so keep OS parts clean.

## The one-button keyboard for every app (design, 2026-10-26)
Goal: one keyboard that works in any app and gets better the more specific the place is. Idea: **the keyboard is a candidate chooser.** The phone offers two things, you click or hold. What changes with the place is only where the candidates come from.

### 1. The door: how it reaches any app
- **Input method service (IME)**: Android lets one app be "the keyboard" for every text field. It is told the app, the kind of field (`EditorInfo`: text, number, email, URL, password, search; the hint; the send/search/go action) and the text already in the field (`InputConnection`). It writes with `commitText` / `deleteSurroundingText`; Done runs the field's action (Send, Search).
- **Accessibility service (already in the app)**: reads the screen around the field and can set text where an IME is not used. **Notification listener (already in the app)**: sees the incoming message.
- Risk to test first: does it work with the screen off, and in apps that block accessibility? Check: type "hola" into a notes app with only the button.

### 2. The context frame (one small record, built each time a field gets focus)
`app`, `field kind`, `field id/hint` (Gmail "To" vs "Subject" vs body), `text before the cursor`, `thread` (who, from the window title), `last messages` on screen or the notification, `time of day`. Everything stays on the phone.

### 3. Candidate sources ("providers"), one interface: `candidates(frame) -> units`
A unit is a letter, a word, a phrase or a whole value. The Write program does not change: it plays pairs of units, takes click/hold, offers next pair and the 3-click menu.
| Field kind | What is offered |
|---|---|
| chat, notes, mail body | words (your model), endings and whole replies (Gemma, sees the thread) |
| number, phone | digits first by recent use, then whole numbers you used here |
| email address | whole addresses from contacts and history, then letters |
| name | contacts, then letters |
| URL, search | recent and frequent for this app, then words |
| password | **never spoken, never logged, never sent to Gemma.** Own design later (pick from a vault). |

### 4. Learning by context, scalable
- Counts are kept in a tree of buckets: `kind > app > app+field > app+thread`. A guess uses the most specific bucket that has data, and backs off up the tree (the same idea as the 2-word backoff, one level higher). New apps start from the field kind and from Gemma reading the screen.
- Every finished text updates its buckets on the phone. Weights between buckets (general, kind, app, thread, you) are tuned from hit rate: which source had the picked word in its top 2.
- Gemma is one more source. It runs in the background with the frame and never makes you wait.

### 5. Steps, each with a check
1. **Units + providers**: split `Ranker` into providers; same behaviour. Check: unit tests unchanged.
2. **IME shell**: commit text, read `EditorInfo`, button routed to Write. Check: write a note in a real app, screen on, then screen off.
3. **Field kinds**: digits, email, name, password rule. Check: one test per kind, password never logged.
4. **Frame from screen and notification**: reply in WhatsApp offers answers to the message. Check: on the phone.
5. **Bucket store + back-off**: Check: replay of logged finishes; hit@2 per bucket beats the global model.
6. **Gemma with the frame**: Check: endings use the message topic; time stays background.
7. **Learned weights**: Check: hit@2 up over a week of use.
Measure with one number: hit@2 per bucket and seconds per text. Not built yet: all of the above (Write today uses only the last 2 words and starts from Games).

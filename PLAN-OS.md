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

## OS parts after the keyboard
| Part | State |
|---|---|
| Input, output, shell, lifecycle | built, partly |
| Text input | step 1-5 above |
| Interrupt queue with priorities (message, timer, call) | not built yet |
| Settings by ear | not built yet |
| Search | not built yet (needs keyboard) |
| Help ("where am I", "what can I do") | not built yet |
| Program contract (one API for every program, not only games) | not built yet |
| Speech arbiter (who may speak now) | not built yet |
| Files and storage | not built yet |

## Later (only with real users)
Custom Android build (AOSP) that boots into this shell. Linux only after that: the design is ideas, not Android code, so keep OS parts clean.

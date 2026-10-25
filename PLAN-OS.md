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

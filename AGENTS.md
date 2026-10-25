# Invisible controller

Android app: control the phone with one earbud button, screen off. The phone speaks the options; one click picks.

## Rules
- **No microphone. Ever.** Do not request RECORD_AUDIO, do not use SpeechRecognizer, do not add voice input or dictation. Input is only the button (click counts, hold) and, for text, the app screen or Share. Reason: the owner does not want the app to listen.
- Menu is English; message text is read in its own language (Spanish default).
- Check: `cd app && ./gradlew testDebugUnitTest --offline -q`. Install on the phone: `app/run.sh`.

## Design kits (enforced by `KitRulesTest`)
- Screens use only the **UI Kit** (`Ui.kt`: `Ui` colors/radii and `Kit` parts) and the **Audio Kit** (`Voice.kt`: every fixed spoken sentence, the button rules). Sounds live in `Earcons.kt`.
- No `Color.*`, raw `TextView`/`Button`/`GradientDrawable`, or `say("...")`/`speak("...")` outside the kits. If a part is missing, add it to the kit first, then use it.

## The OS and its programs (how every new game or tool is built)
The app is a small OS with no screen. Android is the building; this app is a shop inside it that you walk through with your ears.
- **The OS owns:** the one button (clicks and holds), the home menu (`ControllerMode`, `LauncherSettings.HUB_ITEMS`), speech, the screen tones (`Earcons.kt`), the close sound, and the Games section (`Screen.GAMES`).
- **A program** (the Duel in `tell/` is the first) owns only its own rules and sounds. It gets the raw button while it runs. It never plays an OS sound and never touches the menu.
- **To add a game:** one line in the Games list in `ControllerMode`, a `ModeOutput` start method (default no-op), one entry in `Actions` that gives it the raw button, and a callback `onEnded`. When the program ends, `Actions.gameEnded()` plays the OS close sound. Do not copy that into the program.
- **Fast scroll:** click then hold (2nd press held) steps through a list fast, first word + tick; release speaks that item in full. It is only active while a list is read (`ScanList`); everywhere else it acts as a plain hold (exit). Every list must be a `ScanList` (enforced by `KitRulesTest`).
- **Leaving:** hold 3 s always leaves a program. Say how to leave in its first minute.
- **Teaching (research: games for blind players):** teach by short play, not by speech; one mechanic at a time (the Duel has levels 1 to 3, then the full game); confirm every success with a sound; at most 7 words per spoken sentence; pauses follow `TellVoice.pauseAfter` (600 ms + 250 ms per word); keep few sounds at once; the same sound always means the same thing; the player sets the pace; narration can always be interrupted by a press.
- **Sounds are built on a background thread** (`TellSynth.warmAsync`); the phone is about 40 times slower than the laptop for synth work. Never build sounds on the main thread.

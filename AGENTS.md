# Invisible controller

Android app: control the phone with one earbud button, screen off. The phone speaks the options; one click picks.

## Rules
- **No microphone. Ever.** Do not request RECORD_AUDIO, do not use SpeechRecognizer, do not add voice input or dictation. Input is only the button (click counts, hold) and, for text, the app screen or Share. Reason: the owner does not want the app to listen.
- Menu is English; message text is read in its own language (Spanish default).
- Check: `cd app && ./gradlew testDebugUnitTest --offline -q`. Install on the phone: `app/run.sh`.

## Design kits (enforced by `KitRulesTest` and the build task `checkVoiceKit`)
- Screens use only the **UI Kit** (`Ui.kt`: `Ui` colors/radii and `Kit` parts) and the **Voice Kit** (`voicekit/`, see `voicekit/KIT.md`).
- Voice Kit = everything that speaks, plays a sound, reads the button or reads a list. It has atoms, molecules, engines, organisms and one door, `VoiceKit`.
- Every spoken line is a `Phrase`. Fixed sentences live only in `voicekit/atoms/Sentences.kt`. Text from users or the world is `Phrase.content(...)`.
- Outside the kit you must not: use TextToSpeech, AudioTrack, SoundPool, MediaPlayer, ToneGenerator, audio focus, MediaSession; make `Phrase(`/`Phrase.fixed`; pass a string to `say`/`speak`; keep your own list index or loop count; write HOLD_MS/GAP_MS by hand. The build fails with file:line. The rules are in `app/voicekit-rules.tsv`.
- No `Color.*`, raw `TextView`/`Button`/`GradientDrawable` outside `Ui.kt`. If a part is missing, add it to the kit first, then use it.
- Lists: use the kit `Scanner`. Sounds: `Earcons.kt` in the kit. Check the kit with `cd app && ./gradlew checkVoiceKit --offline -q`.

## The OS and its programs (how every new game or tool is built)
The app is a small OS with no screen. Android is the building; this app is a shop inside it that you walk through with your ears.
- **The OS owns:** the one button (clicks and holds), the home menu (`ControllerMode`, `LauncherSettings.HUB_ITEMS`), speech, the screen tones (`voicekit/atoms/Earcons.kt`), the close sound, and the Games section (`Screen.GAMES`).
- **A program** (the Duel in `tell/` is the first) owns only its own rules and sounds. It gets the raw button while it runs. It never plays an OS sound and never touches the menu.
- **To add a game:** one line in the Games list in `ControllerMode`, a `ModeOutput` start method (default no-op), one entry in `Actions` that gives it the raw button, and a callback `onEnded`. When the program ends, `Actions.gameEnded()` plays the OS close sound. Do not copy that into the program.
- **Notifications (the hotel mailbox):** the listener keeps every app's notifications (`NotificationStore`); private ones (secret, locked-private, bank or password apps) are read as "<App>, new notification" only. There is no "Notifications" section: the home list is a tree of groups (`AppTree`, pure; places in the table in `AppCategories`; Messages, Day, Money, Media, Out, Tools; lists of at most 5, home 6; a group with one active entry collapses into it; overflow goes to "More"; used or unread leaves are lifted to home when it has room). Walk home -> group -> app -> its notifications -> one in full with its actions plus Dismiss, Mute this app, Back (triple click goes up). The OS programs (Messages, Today, Tasks, Timer, Music, Phone, Games) are leaves of the same tree. The tree is built when the home list is built and does not change while it is walked. To place a new app, add one line to the table in `AppCategories.kt`. A native module per app is one `NotificationModule` in `NotificationModules.kt` plus one line in the registry in `ControllerNotificationListener`; the chat apps (`ChatAppsModule`) are the first.
- **Fast scroll:** click then hold (2nd press held) steps through a list fast, first word + tick; release speaks that item in full. It is only active while a list is read (`ScanList`); everywhere else it acts as a plain hold (exit). Every list must be a `ScanList` (enforced by `KitRulesTest`).
- **Leaving:** hold 3 s always leaves a program. Say how to leave in its first minute.
- **Teaching (research: games for blind players):** teach by short play, not by speech; one mechanic at a time (the Duel has levels 1 to 3, then the full game); confirm every success with a sound; at most 7 words per spoken sentence; pauses follow `TellVoice.pauseAfter` (600 ms + 250 ms per word); keep few sounds at once; the same sound always means the same thing; the player sets the pace; narration can always be interrupted by a press.
- **Sounds are built on a background thread** (`TellSynth.warmAsync`); the phone is about 40 times slower than the laptop for synth work. Never build sounds on the main thread.

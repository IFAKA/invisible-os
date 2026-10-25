# Invisible controller

Android app: control the phone with one earbud button, screen off. The phone speaks the options; one click picks.

## Rules
- **No microphone. Ever.** Do not request RECORD_AUDIO, do not use SpeechRecognizer, do not add voice input or dictation. Input is only the button (click counts, hold) and, for text, the app screen or Share. Reason: the owner does not want the app to listen.
- Menu is English; message text is read in its own language (Spanish default).
- Check: `cd app && ./gradlew testDebugUnitTest --offline -q`. Install on the phone: `app/run.sh`.

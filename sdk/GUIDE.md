# Make a program for the Invisible OS

## What this is

Invisible is an Android app. You control the phone with **one earbud button**. The screen is off.
The phone speaks the options. One click picks one.

Think of a shop with no windows. The OS is the shop owner. It owns the door, the menu and the close sound.
**A program is a small stall inside the shop.** Counter, a game, a tool. You make one stall. You do not change the shop.

You write a **Program**. The OS starts it and gives it the raw button. Your program speaks, listens to clicks, and says when it is done.

## A tiny example first

This is the whole idea of `starter/` (a program called Counter):

1. The OS starts the program. It says: "Counter. Click to add one." and "Hold three seconds to leave."
2. The player clicks once. It says "1". Clicks again. It says "2".
3. The player clicks two times fast. It says "Zero."
4. The player holds the button for 3 seconds. The program ends. The OS plays the close sound.

Read these four files in `starter/src/main/java/com/faka/invisible/counter/`:

| File | What it does |
|---|---|
| `CounterSentences.kt` | Every fixed sentence the program says. Only place for them. |
| `CounterCore.kt` | The rules. No Android in it, so a plain test can check it. |
| `CounterProgram.kt` | The Program: gets the button, counts clicks, speaks. |
| (same file) `CounterEntries` | The one line the OS needs to list your program. |

## The Program contract

```kotlin
interface Program : RawButton {
    fun start(host: ProgramHost)   // the OS starts you. Keep the host.
    fun stop()                     // the OS stops you now. You must end with host.ended().
    fun speechDone() {}            // the phone finished speaking (optional)
    // from RawButton:
    val active: Boolean            // true while you run
    fun down(time: Long)           // the button went down
    fun up(time: Long)             // the button came up
}
```

The raw button gives you only `down` and `up` with a time. **You count the clicks.**
A press shorter than 3 s is a click. Wait `Pacing.GAP_MS` after the last click to know the click count.
`CounterProgram.kt` shows how.

## What the host gives you (`ProgramHost`)

| Member | Use |
|---|---|
| `speak(phrase)` | Say a Phrase. `slow = true` for a slower voice. |
| `stopSpeech()` | Stop talking now. |
| `later(runnable, ms)` / `cancel(runnable)` | A timer on the main thread. Use these, not your own Handler. They are testable. |
| `prefs` | Saved settings. Use your own key names (for example `counter_best`). |
| `context`, `handler` | Android context, main thread handler. |
| `waiting()` | Plays a soft heartbeat while you build slow things. Call the result when ready. |
| `pauseMusic()` / `resumeMusic()` | Be quiet for the phone's music. |
| `log(line)` | A log line for the developer. |
| `ended()` | You are over. The OS plays its close sound. **Call it once.** |

Your module depends on `:oskit` only (`com.faka.invisible:oskit`). Never on the OS or on another program.

## The rules

These are checked by `checkKit`. If you break one, the build fails with the file and line.

1. **No microphone. Ever.** No `RECORD_AUDIO`, no `SpeechRecognizer`, no voice input. The input is the button only. This is the owner's rule and it has no exception.
2. **Every spoken line is a `Phrase`.**
   - Fixed sentences live in one file, `<Name>Sentences.kt`. Only that file may call `Phrase.fixed(...)`.
   - Text from users or the world is `Phrase.content(text)`. Never put a sentence in `Phrase.content("...")` or `speak("...")`.
3. **No audio API of your own.** No `TextToSpeech`, `AudioTrack`, `SoundPool`, `MediaPlayer`, `ToneGenerator`, no audio focus, no `MediaSession`. Speak with `host.speak`. For a short sound use `Earcons.sequence(...)` and `VoiceKit.narrator.play(pcm)`. For a live stream of sound use the kit's `GameAudio` (see Echo or Compass).
4. **No list of your own.** If you read a list of options, use the kit `Scanner`. No own index or loop counter.
5. **No hand timing.** Use `Pacing.GAP_MS`, `Pacing.HOLD_MS`, `Pacing.LEAVE_MS`. Never type the numbers.
6. **Leaving.** Holding 3 s (`Pacing.LEAVE_MS`) must always leave. Say how to leave in the first minute (the starter does it in its welcome). The OS also closes everything on "click, then hold": do not give that gesture another meaning.
7. **Teaching.**
   - Teach by short play, not by long speech. One new thing at a time.
   - Confirm every success with a sound.
   - At most 7 words in a spoken sentence.
   - The same sound always means the same thing. Few sounds at once.
   - The player sets the pace. A press can always interrupt your speech.
8. **Build sounds on a background thread.** A phone is about 40 times slower than a laptop for sound work. Never build sounds on the main thread. Use `host.waiting()` while you do.
9. **Do not play OS sounds** and do not touch the menu. The OS owns them.
10. Menu text is English. Message text from the world is read in its own language.

## Build, test, check

You need Android Studio's SDK (set `ANDROID_HOME` or put `sdk.dir=...` in `local.properties`) and the folder `sdk-repo` we send you.

```
cd starter
./gradlew test checkKit assembleDebug -PsdkRepo=../sdk-repo
```

- `test` runs your unit tests on your computer.
- `checkKit` runs the same rules the OS build runs. It also runs before every build.
- `assembleDebug` builds the library.

**Test with `FakeProgramHost`** (in the framework, package `com.faka.invisible.oskit.testing`). It is a host with a clock you move by hand:

```kotlin
val host = FakeProgramHost(); val p = CounterProgram()
p.start(host)
host.click(p); host.advance(Pacing.GAP_MS + 10)   // click, wait for the end of the burst
assertEquals(listOf("1"), host.takeSpoken().drop(3))
host.hold(p, Pacing.LEAVE_MS + 100)               // hold 3 s
assertEquals(1, host.endedCount)                  // ended() called once
```

It also needs `testOptions { unitTests.isReturnDefaultValues = true }` in your build file (the starter has it).

Do not change `kit-allow.tsv` except to rename `CounterSentences.kt` to your own sentences file.

## Make your own program from the starter

1. Copy `starter/` to a new folder.
2. Rename the package folder `counter` to your name (lowercase letters, for example `dice`), and rename every `Counter...` file and class to `Dice...`.
   Keep the three pieces: `DiceSentences.kt` with `object DiceVoice`, your Program, and `object DiceEntries { val entry = ProgramEntry(...) }`.
3. Change the rules in `...Core.kt` and the sentences. Keep the leave-by-holding code.
4. Run `./gradlew test checkKit assembleDebug`. Fix until it is green.
5. Edit `kit-allow.tsv`: the file name in it must be your `DiceSentences.kt`.

## Hand it in

Send us the folder (without `build/` and `.gradle/`). We run:

```
sdk/new-program.sh dice --from /path/to/your-folder
```

That creates `app/programs/dice`, adds it to `settings.gradle.kts` and to the OS registry (`Programs.kt`), and it shows in the Games list. Then we build the whole app and listen to it.

Your folder must have: one package folder under `src/main/java/com/faka/invisible/`, a `<Name>Sentences.kt`, and an object `<Name>Entries` with `val entry`.

## For us (owners of the repo)

- Publish the framework: `cd app && ./gradlew :oskit:publishAllPublicationsToSdkRepoRepository :oskit:publishToMavenLocal --offline`.
  It goes to `app/build/sdk-repo` and to `~/.m2`. Version `0.1.0`. Two artifacts: `oskit` (AAR + sources) and `oskit-kit-rules` (a zip with `voicekit-rules.tsv` and the gate script `kit-rules.gradle.kts`).
- Make the zip for a stranger: `sdk/pack.sh` gives `app/build/invisible-sdk-0.1.0.zip` (starter, `sdk-repo`, this guide).
- The sherpa AAR and the commons jars are `compileOnly` in `:oskit`. They are not in the published POM. The OS app packs them at runtime. A program never needs them.
- Remove a program again: `sdk/new-program.sh NAME --remove`.

## Not built yet

- Separate APKs or loading programs while the app runs. Programs are built into our APK.
- Publishing to a public Maven server. The framework is a folder (`sdk-repo`) for now.

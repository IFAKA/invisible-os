# Voice Kit

Everything that speaks, plays a sound, reads the button or reads a list lives here. Screens only compose it.
Built on Atomic Design: small parts (atoms) make groups (molecules), engines talk to Android, organisms are what screens use.

Rules are checked two ways:
- Compile time: `speak`/`say` take a `Phrase`. A fixed sentence can only be made inside this folder (`Phrase.fixed` needs `@KitOnly`). Text from the user or the world is `Phrase.content(text, lang)`.
- Build time: Gradle task `checkVoiceKit` (runs before every build and in `check`) and `KitRulesTest` read `app/voicekit-rules.tsv`. They fail with file:line if code outside this folder uses a speech engine, plays audio, takes the button, makes a fixed Phrase, passes a string to say/speak, keeps its own list index, or hand-rolls HOLD_MS/GAP_MS.
- Allowed outside: AudioManager volume stepping and reading devices (not playing), music control of other apps (pause, next), `Phrase.content` with real data.

| File | Level | What it is |
|---|---|---|
| atoms/Phrase.kt | atom | A spoken text with its language; fixed or content. `KitOnly` guard. |
| atoms/Sentences.kt | atom | Every fixed sentence: `Voice`, `Menu`, `TellVoice`, `TypeVoice`, plus `Spoken` number words. |
| atoms/Pacing.kt | atom | All timing numbers (hold, gap, leave, pauses, speech rates) and pause formulas. |
| atoms/Gesture.kt | atom | `Gesture` and `GestureRules` (click counts, hold, click-then-hold). |
| atoms/Tone.kt | atom | Names of the OS tones (`Tone`) and typing cues (`TypeCue`). |
| atoms/Earcons.kt | atom | The tone sounds as sample data (`Earcons`, `TypeSounds`). |
| atoms/TellMistake.kt | atom | The sentence that names the costliest mistake of a Duel. |
| molecules/ScanList.kt | molecule | Items, current index, wrap and loop count. No timers, no speech. |
| molecules/GestureDetector.kt | molecule | Presses to gestures with injected timers; `Deduper` drops duplicate key paths. |
| molecules/Spearcon.kt | molecule | Sped-up spoken word: file reading and compression. |
| molecules/WordClips.kt | molecule | Spoken words kept as sound; joins two into one stereo clip. |
| molecules/Ports.kt | molecule | `SpeechPort`, `TonePort`, `ButtonPort`, `ButtonTarget`, `RawButton`, `SpeechListener`, `Timers`. |
| engines/TtsSpeech.kt | engine | The only TextToSpeech: voice, rate, spearcon and word files. |
| engines/TonePlayer.kt | engine | The only short-sound player: earcons, cues, stereo pair, find-phone loop. |
| engines/MediaKeyIntake.kt | engine | The only media session: takes the earbud button, wins it back, keeps it alive. |
| engines/TellAudio.kt | engine | The Duel's low-latency stereo stream and latency estimate. |
| organisms/Narrator.kt | organism | Say, beep, cue, stereo word pair. What screens call to be heard. |
| organisms/ButtonInput.kt | organism | The one button: dedupe, raw button for programs, tap tone, gestures. |
| organisms/Scanner.kt | organism | The spoken list: pauses, ticks, loops then leave, fast scroll. Used by the menu and the Write menu. |
| VoiceKit.kt | facade | `init` once from the OS; `narrator`, `buttons`, `mediaKeys()`. |

Add a sentence: put it in `Sentences.kt`. Add a sound: `Earcons.kt` and a `Tone`. Add a list: use `Scanner`. Add a program: it gets `RawButton` and speaks through `Narrator`.

# Sound design (2026-10-25)

## Problem
You cannot look at the phone. Sound is the whole screen. Today every tone is a phone-keypad beep (`ToneGenerator` DTMF, `Actions.kt:141`).
They all have the same voice, so they are hard to tell apart. They sound like dialling a number, and they are harsh in an earbud.

## Picture
The earbud is a **small shop with no signs**. You walk in blind. Sound has to do three jobs:
- the **door bell** says "you are in" (wake),
- the **floor** says "which room" (timbre = place),
- the **arrows** say "good way or back" (pitch up = go, down = back).

## Rules (each one is testable)
1. **Direction = meaning.** Pitch goes UP for yes / go / done. It goes DOWN for back / cancel / leave. Never the reverse.
2. **Voice = place.** Bell = hub and results. Pluck = messages and your clicks. Wood tick = timer and scan. Low thud = error/cancel.
3. **One scale.** All notes are C major pentatonic. Sounds that overlap with speech or each other never clash.
4. **Short.** Feedback <= 250 ms. Screen sounds <= 500 ms. Only SENT (0.9 s) and ALARM are longer.
5. **Quiet next to speech.** Peak about -12 dB under the voice. Range 260-1400 Hz (no sharp highs in an earbud). 4 ms fades (no clicks).
6. **Every press answers at once.** A 20 ms `tap` on each press, before the count is known. Blind, silence feels like "it did not hear me".
7. **Result sound after the count is known:** 1 click = `pick` (rises), 2 = `again` (repeats), 3 = `back` (falls). You learn the clicks by ear.
8. **Arrival is quiet and has no words.** One soft note. Each favourite gets its own 3-note motif (like a ringtone per person, picked from ChoiceLog).
9. **Alarm grows.** Timer done repeats and gets louder every 10 s until you click. Nothing else repeats.
10. **Do not trust the speaker volume.** Play on the accessibility usage (not media), so tones keep the same level to speech even when you change media volume. Duck music while scanning.

## The set (listen: `sound/wav/*.wav`; `sound/wav/_demo.wav` is one whole session with silence where speech goes)
| Name | When | Sound | Replaces |
|---|---|---|---|
| tap | every press | 20 ms dry click | nothing (new) |
| tick | 0.2 s before next scan item | softer, lower click | TICK (DTMF *) |
| loop_end | list ended, starts again | bell E5 -> A4 (falls, gentle) | DING |
| wake_hub | 4 clicks accepted | bell C5 -> G5 (up a fifth) | HUB |
| message | message screen | pluck G5 -> C6 | MESSAGE |
| timer | timer screens | tick-tock + bell A5 | TIMER |
| more | more/compose | one low bell E4 | MORE |
| pick / again / back | 1 / 2 / 3 clicks | rise / repeat / fall (pluck) | (new) |
| undo_open | "Sending, click to cancel" | quiet rising pluck C-E-G | UNDO |
| cancel | undone | bell falls + thud | CANCEL |
| sent | send done | bell C5 G5 C6, rings out | SENT |
| leave | silent timeout / exit | bell G5 E5 C5, slow fall | LEAVE, EXIT |
| error | cannot do it | two low thuds | UNKNOWN |
| arrive / arrive_fav_1..4 | message arrives | 1 soft note / 3-note motif | ARRIVE |
| alarm | timer done | bell A C E C E A, repeats louder | alert tone |
| volume_1..8 | Louder/Quieter | one bell note = level on the scale | spoken number |

## Research (2026-10-25) and what it changed
- **Spearcons beat earcons for menus.** A spearcon is the spoken item, sped up until it is no longer speech. Walker & Nance (ICAD 2006): spearcons and plain speech gave faster, more accurate menu search than auditory icons and earcons. Dingler, Lindsay, Walker (ICAD 2008): spearcons are as learnable as speech; abstract earcons need the most learning. Korean, Mandarin and others: same result. Spearcons are free: our TTS plus time compression, made at run time, no menu tree needed.
  Sources: https://www.icad.org/Proceedings/2006/WalkerNance2006.pdf , https://www.icad.org/Proceedings/2008/DinglerLindsay2008.pdf , https://vtechworks.lib.vt.edu/bitstreams/ac02cf6a-1131-43c6-a7fd-586383cf23ab/download
- **Few, distinct, consistent earcons.** Google's voice design guide: earcons add mental load, are not intuitive, use few, same sound in the same place, and do not use one if you would have to teach it. https://developers.google.com/assistant/conversation-design/earcons
- **Do not tell sounds apart by pitch alone.** Brewster: vary timbre (musical timbres work best), rhythm and register together; pitch range about 150 Hz to 5 kHz. Thesis: https://www.dcs.gla.ac.uk/~stephen/papers/theses/Brewster_thesis_tagged.pdf
- **Scan delay should fit the person.** Koester & Simpson: measure the user's own reaction time and set the delay from it; too short causes error fixing, too long wastes time. https://arxiv.org/pdf/0909.2450 and https://arxiv.org/pdf/1712.10073
- **Blind users listen fast.** About half of 19 blind users set their reader above 1.5x; trained users follow 2.8x (Hochheiser & Lazar 2010; Asakawa). Speech rate should be adjustable and rise with use.
- **Not found online:** loudness targets between earcons and speech, and earbud fatigue limits. Those stay as my values (rule 5) and need your ears.

What I changed in the plan because of it (I guessed: melodic icon per option. Research says: wrong for menus):
1. Keep earcons only for **state and feedback** (tap, pick/again/back, wake, sent, cancel, error, leave, alarm). Few, meanings are universal (up = good, down = back).
2. For **options** (Messages, Timer, Yes, No...) use **spearcons**, not melodies. First-time user hears the full word. After N picks of that item, play only the sped-up word. Scan gets faster with use and nothing new to memorise.
3. Favourite arrival: instead of a 3-note motif nobody can decode, play the **spearcon of the name** ("Ana") after a soft note. Motifs stay as fallback.
4. Scan gap (`ITEM_GAP_MS`, now 1600) is **learned** from the time between item start and your click, per user, within 0.9-2.5 s.
5. Speech rate setting, with a slow rise as the user succeeds (start 1.0x, step 0.1x per 20 clean picks, cap 1.8x).
6. Volume: note for the level stays (a scale is the one place pitch is natural).
7. Quiet mode for night: only tap, pick, sent.

## Build steps (each with a check)
0. Spearcon maker: TTS to a file, time-compress to about 40% length, cache per string. Check: unit test that length ratio is 0.4 +/- 0.05; listen to 5 Spanish items (compression must keep them understood; the 2017 Mandarin paper warns that language matters).
1. Port `render.py` voices to Kotlin `AudioTrack` (pre-render PCM once, keep in memory; ButtonService already uses AudioTrack). Check: unit test that every `Tone` has a buffer, peak < 0.9.
2. Add `tap` on press and `pick/again/back` on resolved count in `Actions.kt`. Check: log shows tap before gesture resolves.
3. Accessibility usage + duck music. Check: change media volume to 0 and 100, tone level stays equal (measure on the Redmi).
4. Per-favourite motif from ChoiceLog. Check: unit test: same sender -> same motif.
5. Alarm escalation. Check: unit test with fake clock.

## Unverified (only your ears can close)
Whether pluck vs bell is different enough in your earbud; if the 20 ms `tap` is useful or annoying; whether 8 volume notes are easy to read; the real loudness balance with the TTS voice.

## Built (2026-10-25)
Done: `Earcons.kt` (all tones as PCM, same voices as `sound/render.py`) and `Spearcon.kt` (WAV reader + WSOLA time compression, pitch kept). `Actions.kt`: tones play through `AudioTrack` on the accessibility usage (DTMF `ToneGenerator` removed); `tap` on every press in the mode; `pick/again/back` after the click count is known (not when the click cancels a send); alarm gets louder every 10 s; speech rate rises 0.1 per 20 picks (0.9 start, cap 1.8). Spearcons: an option picked 10 times (`ControllerMode.SPEARCON_AFTER`) is scanned as its sped-up word. First time it is spoken in full, then the TTS engine writes it to a file and it is compressed to 40% and cached in `cacheDir/spearcons`. 83 unit tests pass (5 new in `SoundTest`). APK installed on the Redmi, service starts, no crash in the log.
Not built yet: learned scan gap, volume level as a note (`Earcons.level` exists, not wired), name spearcon on favourite arrival, quiet mode, music ducking.
Unverified on the device (adb cannot inject key events here, so nothing was heard): how the tones sound, whether the spearcon is understandable in Spanish, TTS file synthesis (`synthesizeToFile`) with the screen off, whether `tap` annoys.

## v2 (2026-10-25): simple but sophisticated (replaces the table and voices above)
Problem: 20 tones in 4 voices (bell, pluck, wood, thud) is busy, not refined. Five screen tones were near copies. Sophistication here = fewer things, all made from the same material, with a little space around them.
- **One voice:** a soft mallet (sine + a quick 2nd and 4th partial, 6 ms attack). Only `tap` and `tick` are dry wood clicks (they are touch feedback, not music).
- **One chord:** C major triad (C5 E5 G5 C6; C4 for "no"). Any two sounds, or a sound over speech, are consonant.
- **One rule:** up = in / yes / done. Down = out / back / no. One note = information. Repeat = again. Flat and low = no.
- **Fewer sounds:** the 4 screen tones (message, timer, more, volume) are now one quiet "page" note; the speech names the place. Voices out: bell, pluck, thud. 12 different sounds remain in use (tap, tick, open, page, pick, again, back, undo, sent, cancel, leave, no, end-of-list, arrive, favourite, alarm).
- **Space:** a small room tail (3 combs + allpass, ~12% wet, 300 ms) on every musical sound; clicks stay dry. This is what removes the "phone beep" feeling.
- **Loudness ladder:** tap -20 dB, tick -15 dB, page/undo/end-of-list -13 dB, results -8 dB, sent/open about -4 dB (peak, relative to full scale). Speech stays the loudest thing.
- **Volume** is 4 notes up the chord (`Earcons.level`), not wired yet.
- One source of truth: `Earcons.kt` makes the shipped sound AND the audition files. `cd app && WAV_DIR=../sound/wav ./gradlew testDebugUnitTest --offline -q` rewrites `sound/wav/*.wav` (`_demo.wav` = a short session). `sound/render.py` is deleted.
Checks: 6 sound tests pass (every tone present, no clipping, <= 1.6 s, tap <= 30 ms, pick/again/back differ). APK with v2 installed. Not heard on the device by me.

## v2.1: the loop is audible (2026-10-25)
End of list = `ding` falls (E5 C5). About 0.45 s before the first option comes back, `restart` RISES (G4 C5 E5, from below). So you hear: fall = "it ended", rise = "starting over". Two clicks use the same rising sound, because two clicks also mean "start over": one meaning, one sound. After two silent loops there is no rise, only the leave sound.
Hint (first 15 picks) is now: "Click when you hear it. Two clicks: start over. Three clicks: go back." (was "Two clicks, again. Three, back.")
Checks: new test `listEndFallsThenRisesBeforeItStartsOver`; all tests pass; APK installed.

## Rebuild (2026-10-26): Apple's rules
Why: the old tones were plain sine notes, as loud as the speech (peaks -1 to -8 dBFS, speech about -2). That sounds like a toy.
Rules used (WWDC17 "Designing Sound", WWDC19 810): sound matches a physical cause and material; short, sharp attack; UI sounds much quieter than results; fade out, never cut; remove what the speaker cannot play; keep the set small and consistent.
- **One material:** a tuned wooden bar struck with a mallet. Partials 1 : 3.99 : 9.8 (the high ones die faster), plus a 3 ms noise knock. Tap and tick are a dry wood block.
- **Sharp attack:** 2 ms (was 6 ms). Note length ends about -26 dB down and then a 25 ms fade: nothing is cut.
- **Filter:** low-pass 4.5 kHz (no 3-6 kHz bite in an earbud), high-pass 200 Hz (a phone speaker cannot play it). Error note moved from C4 to G4 for the same reason.
- **Levels (peak dBFS, speech about -2):** tap -26, tick -24, screen notes -20, pick/back/cancel -17, hub -14, sent -13, alarm -10. Set in `LEVEL` in Earcons.kt. Write-program cues use the same material.
- **Audition:** `sound/tones_ab.wav`: each tone as voice, OLD, voice, NEW. Check on the phone speaker and the earbud, and live with it a week.
- Not done: small random variation of the repeated sounds (tap, tick), and a setting to turn tones off.

## Waiting (2026-10-26): so a slow step never sounds like a crash
Problem: weather, calendar, opening Write, a game building its sounds, music starting cold: seconds of silence, and silence sounds like a crash.
Rules used (Nielsen's response times: under 1 s no sign is needed; screen readers' "busy" sounds; our own rule "a repeat = again"):
- **Nothing for 0.6 s.** Fast work stays quiet.
- **Then a heartbeat:** `wait`, C5 twice (150 ms apart, the second softer), LOW bar, -27 dBFS, every 1.4 s. Same note twice: no rise, no fall, so it means neither yes nor no, only "still going". Skipped while speech plays.
- **At 10 s** the phone says "Still working." once. **At 60 s** the beat stops even if no one stopped it.
- One door: `Narrator.waiting()` returns stop. Several waits at once give one heartbeat. Numbers in `Pacing` (`WAIT_*`).
- Used by: weather, next event, Write (word model load), Echo, Guard, Duel (sound build), music search, music cold start.

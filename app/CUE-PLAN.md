# Cue refactor: voice and screen are one thing

Goal: every thing the phone says or plays is a `Cue` (sound side + picture side). The kit emits it; `Kit` is the only drawer. No screen text written by hand for spoken content.

Done when (each has a check):
1. One `Cue` event type in the Voice Kit; Scanner, `say`, LetterLoop emit it; `ScreenMirror.Snapshot` is derived from Cues. Check: unit tests + `checkVoiceKit`.
2. Cue published at audio onset (speech start callback), not at the call. Check: Flow test.
3. `host.screen(rows)` removed from programs; typed parts only. Check: new rule in `voicekit-rules.tsv` fails the build on `host.screen(listOf(`.
4. Sounds (earcons, tones, game audio) emit a sound Cue; `Kit` draws a pulse. Check: unit test + screenshot.
5. Generic Flow helper: at every audio onset shown == spoken, run on Scanner, say, each program. Check: `./gradlew :os:testDebugUnitTest --tests '*Flow*'`.

Steps run in order 1..5; each ends with the 3 checks from AGENTS.md and an atomic commit.

Status log:
- Step 1 done: Cue + Cues.emit (organisms/Cue.kt); Scanner, LetterLoop and the os line paths emit it; ScreenMirror.reduce folds cues into Snapshot; os no longer calls ScreenMirror.show/said. Publish is still at call time (onsetMs = -1).
- Step 2 done: LIST_ITEM and LINE cues are staged (Cues.stage*) right before the say call; Narrator.say takes the staged cue into a CueGate and emits it at Narrator.speechStarted() with the real onset (engine error/ended without start or 2.5 s timeout = emit anyway; new non-queued line or stopSpeech = drop). Loop cues now carry onsetMs on the audio clock. Test rigs (RoboRig, ModeTest.Rig) emit the staged cue at their fake voice start.
- Step 3 done: ProgramHost.screen removed (added speakFast); programs breathe, counter, habits, claude, now, switches, reader, write use spoken Phrases, Scanner cues or Cues.pairScreen; Actions.mirrorSaid no longer overwrites a staged cue; rule hand-screen in voicekit-rules.tsv + KitRulesTest.
- Step 4 done: SoundCues.played is the emitter; TonePlayer.play (earcons/beep/play/close), FrameMixer.render (all game audio: echo, guard, duel...) and LoopSound.confirm emit SOUND cues at the real onset; Snapshot.sounds (8 marks, repeats within 150 ms merged); Kit.soundStrip draws the beat strip in mirrorScreen and loopScreen. Tests in SoundCueTest.
- Step 5 done: ParityRecorder, FakeVoice, FakeTones, CueClock (oskit/testing/CueFakes.kt) shared by RoboRig and ModeTest.Rig (no hand-made cue emit left); FlowParityTest (menu, sub list, line, helper must-fail tests) and FlowParityRoboTest (echo, guard, practice, home letters, Write loop); Cues.takeStagedFor drops a staged cue that does not fit the spoken line; known at-call exceptions in KIT.md.

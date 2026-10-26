# GUARD: a one-button beat game (audio only)

You are a guard at a gate in the dark. An enemy is announced, takes three steady steps, and you click ON the fourth beat. You never react to a surprise: you only keep a beat you just heard. Menu: Games > Guard. Hold 3 s leaves.

## Play
- Enemy: announce sound (beat 0), 3 steps (beats 1 to 3) on its side (left or right ear), strike on beat 4. Real enemies keep beat 4 silent; practice enemies and the tutorial mark it with a soft "ping".
- Answers (one sound, one meaning): PERFECT (60 ms) = bright high clang + rising sparkle. GOOD = duller clang. EARLY or nothing there = air whoosh, free, and the strike stays open. LATE or no click = low heavy thud, you are hit (lose a heart). The thud always means "hurt".
- Ladder (one new thing at a time, each with one short line and 2 free practice enemies marked by a soft double pip): 1 Thief (low steps) / 2 Knight (metal steps, strikes on 4 AND 5) / 3 Decoy bell (do NOT click; a click costs a heart; waiting gives a calm chime) / 4 Fast Thief (tempo x1.35) / 5 Ogre (slow heavy steps, an off-beat fake-out before the real thud; clicking at the fake-out costs a heart). Then random waves of 5: tempo 80 rising by 4 bpm per wave toward 140; two hearts lost inside one wave drop the tempo 10 bpm.
- 3 hearts. One heart left: quiet low heartbeat under play. 5 perfects in a row heal one heart (rising chime). Score = (3 per perfect, 1 per good, 1 per calm decoy) x stage. Best in prefs `guard_best`.
- Window: 180 ms at the start (always at least 170 ms in the first enemies), shrinking 2 ms per real enemy to 100 ms (never over 0.4 beat).
- Help: after 2 hearts lost in a row the rule "Click on the fourth step." is said once per stage or wave.

## Calibration (Bluetooth delay)
- Tap-along: "Click with the beat." 6 ticks at 100 bpm; the median offset of the clicks (ignore over 400 ms; fewer than 3 clicks keeps 0) is the starting delay.
- Running median of the last 16 click errors keeps correcting it; clamp +-300 ms (`GuardJudge.biasMs`). Saved per sound output (earbuds, wired, speaker) in prefs `guard_bias_<output>` (`guard_bias` holds the last value). An output never calibrated runs the quick tap-along at the next start.

## The first two minutes (`GuardTutorial`, pure; run by `GuardGame`)
Shown while `guard_taught` is unset or `guard_best` is 0; also again after losing all hearts in the first 12 enemies. Teach by doing; a step moves on only when the player SUCCEEDS; nothing is lost (hearts are not used); every line can be skipped by one click (it jumps to the next practice); a demo can be skipped too.
1. "Guard. You are a guard at a gate." / "Listen." / demo thief with ping on beat 4 / "Click on the fourth step." / try (window +-250 ms). Early or late: one hint each, once ("Too early. Wait for the fourth." / "Too late. Click sooner."), then only whoosh or thud. No click: "Click when you hear the ping." and the demo again.
2. "Good. Again." second try. Then "Hold three seconds to leave." once.
3. "Click with the beat." tap-along calibration.
4. "Now without the ping." three hits in a row without the ping, windows 200, 170, 150 ms. Three misses bring the ping back for one try.
5. "Good." "A knight. Two strikes." demo, try until one double hit ("Click on four and five." once).
6. "A bell. Do not click." demo, try: stay silent: "Right. Bell means wait."; click: whoosh, "No. Bell means wait.", again.
7. "Ready. Three lives. Begin." The real game starts at stage 4 (Fast Thief); thief, knight and decoy come back in the random waves.
- Silence: never ends silent. A reminder plus the demo repeats after every empty try; after 3 empty tries in a row "Press your button to play." (then every 3rd). After 20 empty tries it leaves quietly.
- Never stuck: after 8 wrong (but clicked) tries in one step, the step is passed anyway.

## Tested (`GuardModelTest`, `GuardTutorialTest`, simulated players on a virtual clock)
- Model (jitter 90 / 45 / 20 ms): novice dies after about 37 enemies in the random waves, good about 280, expert never. Spammer (clicks every beat) and non-clicker never pass the decoy stage (spammer dies at stage 3, non-clicker at stage 1). A constant 200 ms (or -150 ms) delay is learned to within 40 ms. Tempo drop and heal rules, verdicts, ogre bait, windows.
- Tutorial: perfect player finishes in 70 s of audio time, loses nothing; a 200 ms laggy player is calibrated by the tap-along; a never-clicker is told and not hurt and leaves quietly after 3+ minutes; random clickers all get through; a click during speech skips to the practice.

## NOT verified by a human
Whether the model numbers match real people; the feel of the timing on real earbuds (Bluetooth lag that varies cannot be fixed by an offset); the sounds (clang, whoosh, thud, heartbeat) and whether the three kinds of "bad" are told apart by ear; whether the speech ends on time on the phone (a fallback timer moves on if the engine never reports the end); the per-output key from `AudioManager.getDevices` (the output chosen may differ from the route actually used).

## Code (package `guard`, pure except `GuardAndroid`)
GuardCore (enemy, ladder, judge, calibration, run), GuardTutorial (script), GuardSynth (sounds, reuses `EchoSynth.tone/then`), GuardGame (flow on the audio clock), GuardAndroid (TellAudio engine, reuses `EchoMixer`, timers, prefs). Fixed lines: `GuardVoice` in `voicekit/atoms/Sentences.kt`. Wiring: Games list in `ControllerMode`, `ModeOutput.startGuard`, `Actions.startGuardGame`, raw button in `Actions.raw()`.

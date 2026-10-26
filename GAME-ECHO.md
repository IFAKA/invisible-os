# ECHO: a one-button rhythm-copy game (audio only)

Why: the Duel (TELL) is easy to start but its input (hold length, no reference) feels unfair, and its depth is only guessing. ECHO keeps one button and no screen, but skill is copying a rhythm against a steady beat. Same input for a beginner and an expert: a click.

## Play
- Pulse of low ticks: the robot plays a rhythm (wood block, left ear). Pulse turns high: you click the same rhythm.
- A click near a robot hit: bell = spot on (within 55 ms), soft note = close (110 ms; 170 ms at level 1), low buzz = nothing there.
- Bar cleared (every hit, no extra click): level up. Failed: lose a heart, same level. All-perfect bar: heal a heart. 3 hearts. Hold 3 s leaves.

## Why it is easy to learn and hard to master (tested in `EchoModelTest` with simulated players)
- Level 1: 2 hits on the beat, wide window; auto-calibration (median early/late error of recent clicks, `EchoJudge.biasMs`) absorbs the earbud and Bluetooth delay, so the first minute works for anyone.
- Ladder (`EchoLadder`): more hits, off-beat hits (the "and"), tempo 70 to 115 bpm, 2-bar phrases from level 10 (memory), the guide ticks vanish from level 20 (keep time yourself), window shrinks to 110 ms at level 10. Score grows with level and perfect hits.
- Model results: novice about 7 bars, good 14, expert 22; clicking everything / nothing / only downbeats never passes level 6.
- NOT verified by a human: whether the model's numbers match real people, the feel of the timing on real earbuds, the sounds. Tune `EchoLadder`/`EchoSynth`; keep the tests' bands.

## Code (package `echo`, pure except `EchoAndroid`)
EchoCore (ladder, pattern, judge, run), EchoSynth (sounds), EchoMixer (sample-accurate scheduler), EchoGame (round flow, clicks timed on the audio clock), EchoAndroid (TellAudio engine, timers). Menu: Games > Echo. Best score in prefs `echo_best`.

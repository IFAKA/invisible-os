# TELL: a one-button, audio-only duel (design + build spec)

Status: 2026-10-25. Rules model: `~/code/projects/games/tell-model/yomi.py` (exact solve). Sound is the whole screen.

## Why this game (the owner, in short)
Wants a fast skill duel against a smart opponent, hidden information, efficiency (more with less), counting energy like
elixir, no stats/unlocks/timers/streaks, expert from minute one, fast feedback, one-click replay, every loss traceable to
a choice, a named costliest mistake. Plays any time, stimulating not calm. Likes synth music (Tron Legacy / Daft Punk).
One button + headphones only, screen off. Never uses a microphone (see AGENTS.md).

## The rules (identical to yomi.py `resolve`; the Kotlin code must give the same results)
- Two sides: YOU and the OPPONENT. Life 4 each. Energy 0..5, start 3, +1 at the end of every round (cap 5).
- A round: both commit one move in secret, then both are revealed at once.
  - JAB costs 1. GUARD costs 0. CHARGE costs 3. A move you cannot pay for is not allowed (it becomes GUARD).
  - Same move: JAB/JAB both take 1. CHARGE/CHARGE both take 2. GUARD/GUARD nothing.
  - GUARD beats JAB: the jabber takes 1 (counter), the guarder gains +1 energy.
  - JAB beats CHARGE: the charger takes 1 and the charge fizzles (energy was spent).
  - CHARGE beats GUARD: the guarder takes 2 and gets no refund (the refund is only for a guard that wins).
  - Order inside resolve: pay costs, apply damage, add parry refund, then +1 refill with cap 5.
- Ends when someone has life <= 0 (both <= 0 = draw), or after 20 rounds: more life wins, equal = draw.
- Win = ladder rung +1. Loss or draw = same rung (never easier). Rungs 1..5.

## Input: the hold length picks the move (measured from YOUR press, so headphone delay cannot matter)
- A round is 2.0 s (4 beats at 120 bpm). The choose window is the first 1.5 s; the snare at 1.5 s closes it.
- Release time - press time: under 300 ms = JAB; 300..799 ms = GUARD; 800 ms or more = CHARGE.
- While you hold, a detent click plays at 300 ms and again at 800 ms (so you know the move before you let go).
- The window closes at 1500 ms + `latencyMs` (the output delay of the headphones, from AudioTrack timestamps; 0 if unknown),
  so it closes when the snare is HEARD, not when it is written. Hold lengths do not depend on latency.
- Commit = release. If still holding when the window closes, the level reached at that moment commits.
- No press in the window = GUARD.
- One press can only commit one move per round. A press that starts in the last 150 ms counts for the next round.
- Leave: hold 3.0 s (the forfeit cue plays at 2.5 s, leave at 3.0 s). After a match: tap = play again, hold 0.8 s = leave.

## Opponent ladder (Kotlin port of the DP; `TellSolver`)
Rungs: 1 habit bot (60% of one move, changes favourite each match). 2 habit bot that switches favourite every 6 rounds.
3 Nash mix of the exact solve. 4 Nash + exploit (weight .5): learns the player's move frequencies, conditioned on the
player's last move, decay .8, best-responds with the weight. 5 weight .85, conditioned on the last two moves.
The solve (value iteration, 20 rounds, mix grid 1/20) runs once on a background thread at game start.

## After a match: the costliest mistake (voice, via ModeOutput.speak)
For each round, find the move that would have scored best against the opponent's ACTUAL move (value = damage dealt -
damage taken + 0.5 x (energy change)). The round with the biggest gap is the mistake. Say, at most 2 short sentences:
"Round 7. You charged, they jabbed. Cost you one life." plus one habit fact only if it is strong (one move >= 55%):
"You guarded 62 percent." No praise, no jokes.

## SOUND DESIGN (the interface is the sound; every rule here has a test)
Style: dark synth, Tron-Legacy / Daft Punk feel: saw/square voices through a low-pass filter, sub bass, clean gated
hits, short room tail. All pitched notes are in the C minor pentatonic set (C Eb F G Bb) in any octave, so nothing clashes.

### Space (left/right is the only reliable axis; no front/back, no height)
- YOU = centre (pan 0). OPPONENT = hard left (-0.8). Never swap. All opponent sounds are 6 dB quieter and have a small
  low-pass (cutoff 5 kHz) so they feel across the room.
- Panning is binaural: level difference (equal-power) + inter-aural delay up to 0.6 ms on the far ear + 3 kHz low-pass on
  the far ear scaled by the pan amount. Mono sources only; no reverb on the near side.

### The clock (the bed)
The bed is only a clock. 120 bpm kick (soft, sub 55 Hz, 90 ms) on every beat, always, from the first round to the end: the pulse
tells you where in the round you are. Beat 1 is accented. At 1.5 s a snare/clap = the window closed. Nothing else plays at tension 0.
Layers enter only with danger (peak of the whole bed stays -20 dBFS):
- Tension 1 (anyone has 2 life): adds the bass, root C2 / fifth G2 eighth-note pulse.
- Tension 2 (anyone has 1 life): adds the bass, the off-beat hi-hat (very quiet) and a high 16th ostinato on G4/Bb4 (lower in the mix than the cues).
- A coached match (the narrated first match) uses kick and snare only, never a layer.
Music stops with a held bass note at match end.

### Status (beat 1 of every round, 150 ms, two soft notes, quiet: peak -22 dBFS)
- Energy is a pitch: 0 C3, 1 Eb3, 2 F3, 3 G3, 4 Bb3, 5 C4. Yours in the centre (bell/mallet), theirs on the left (muted pluck).
- Charge-ready (energy >= 3) adds a bright shimmer: a 2x overtone trill, 8 Hz amplitude wobble. So you hear "can charge"
  without counting.
- Life: only when it changes. After the damage sound, N short notes (N = life left), 70 ms apart, descending: yours centre,
  theirs left.

### The three moves (same timbre for both sides; only the space and level differ)
- JAB: a short bright pluck, saw through a fast filter envelope, G4, 110 ms. Centroid ~1.8 kHz.
- GUARD: a round low "shield": two detuned sines at C3+0.3% with a small noise burst, 220 ms, centroid ~400 Hz.
- CHARGE: a riser: saw sweep C3 -> C4 over 500 ms with growing brightness and noise; at the end a sub boom (45 Hz, 250 ms).
  Centroid ~2.5 kHz. At reveal it plays as the 500 ms riser; opponent charge winds in the left ear.
- Centroids of the three moves differ by at least a factor 1.6 (test).

### While you hold (your own feedback, centre, instant)
- Press: a 20 ms tap (wood click) at once (before the move is known): "it heard me".
- 300 ms: detent click + a soft low hum starts (GUARD armed). 800 ms: second detent click, the hum jumps an octave and
  a faint riser starts (CHARGE armed). Release stops the hum and plays a 40 ms "stamp" = committed.

### Reveal and result (starts at 1.5 s, lasts about 0.5 s)
- Both moves play at the same instant (yours centre, theirs left). Then the result sound:
  - Parry (guard beats jab): clang (high bell, 1.2 kHz) on the guarder's side, then a quick up-sweep "coin" = +1 energy.
  - Jab beats charge: the charge riser pitch-falls and breaks into noise (fizzle) on the charger's side; a short hit on the other.
  - Charge beats guard: crack + sub boom on the guarder's side (heavier than any other hit).
  - Trades: a hit on both sides.
  - Nothing happens (guard/guard): a soft dull "tick" in the centre.
- A hit on you: low thud (centre, 90 Hz) + a 120 ms down-slide in the low-pass. A hit on them: the same in the left ear.

### Match start and end (the only speech is at start and end)
- Start: kick roll in, then voice: "Duel. Rung three." then the bed starts on the next downbeat.
- Win: three rising notes (C4 Eb4 G4 -> C5), bell, 600 ms. Loss: three falling notes (G4 Eb4 C4 -> C3), 600 ms, low pass.
  Draw: two flat notes. Then the voice names the mistake (see above), then "Tap to play again. Hold to leave."
- Forfeit/leave: the bed falls in pitch for 400 ms (tape stop), silence.

### Levels and safety
- Loudness ladder (peak dBFS): tap -17, status -22, bed -20, moves -12, results -9, hits -9, win/lose -8. Mix limiter at -1 dBFS.
- No sharp highs: nothing above 8 kHz except the clang (up to 6 kHz) and hi-hat (up to 9 kHz at -30 dBFS).
- 4 ms fades on every sample (no clicks). Max simultaneous voices 24. Latency: low-latency AudioTrack stereo, 44.1 kHz,
  render in 5 ms blocks.
- Play on USAGE_GAME so the media volume works; pause the user's music at start and resume at exit
  (ModeOutput.pauseMusic / resumeMusic).

## Code layout (package com.faka.invisible.tell; no Android types in rules/solver/opponent/audio-synth)
- `TellRules.kt`: Move, State, `resolve()`, `Outcome`, `legal()`, terminal checks. Pure.
- `TellSolver.kt`: DP over rounds, Nash mixes + values. Pure.
- `TellOpponent.kt`: the five rungs behind `interface Opponent { fun choose(state: State, round: Int, rng: Random): Move; fun observe(...) }`.
- `TellMistake.kt`: costliest-mistake text from a match record.
- `TellSynth.kt`: every sound as a stereo FloatArray (samples), pure.
- `TellMixer.kt`: voices, binaural pan, limiter, `render(blockFrames)`. Pure and deterministic, so tests can render WAVs.
- `TellGame.kt`: the clock, press handling, round loop. Takes an injected `Clock`, `Out` (audio events) and `Speech`.
- `TellAndroid.kt`: AudioTrack thread, wiring from `Actions`, menu item "Duel", persistence of the rung (SharedPreferences),
  a per-match log line via `ModeOutput.log`.
- Tests under `app/app/src/test/java/com/faka/invisible/tell/`.

## Checks (all run by `cd app && ./gradlew testDebugUnitTest --offline -q`)
Rules: same results as yomi.py for a fixed table of 30 (state, moves) cases. Solver: start value within 0.05 of 0.5; Nash
mixed (max p < 0.8) in states where both can charge. Ladder: a scripted "habit reader" beats rung 1 >= 75% and rung 2 >= 65%
over 200 matches; no always-X policy beats rung 3 more than 55%; rung 5 beats the habit reader less than rung 1 does.
Sound: peaks, centroids, scale membership, ILD/ITD, fades, loudness ladder, no NaN, voice cap, deterministic render. The
tests also write `sound/tell/*.wav` (each cue, plus `_match.wav` one scripted match) for the owner to listen to.

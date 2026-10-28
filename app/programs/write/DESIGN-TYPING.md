# Typing and picking: one design for the whole OS

Status: design, not built yet (2026-10-28). Data: `tools/write_stats.py`, `tools/write_sim.py`, `app/logs/write/research-*.md`.

## Problem (measured)
- The owner sent 3 texts on 10-28 (all song searches from YT Music "Search a song"): 8, 30 and 57 s per character.
- Next pair 2.9 s, pick 3.1 s, a menu trip 9.4 s; menu trips were about 30% of the time.
- The letter order ignores the word being spelled (bug: a 1e-6 tie-break prior beats the word chances), so letters come in a fixed order; unknown names fall back to A-Z.
- AI is not connected to the keyboard except the phone model's next words; `/ai/replies` never reaches Write.
- Earlier research: free chat replies could not be predicted (0/40), so guesses alone are not enough: a fast exact way to spell is needed too.

## Prior art (app/logs/write/research-prior-art.md)
- Scanning (what Write does now): about 1.7 wpm at best. Today Write is about 0.1-0.5 wpm.
- Morse on one switch (click = dot, hold = dash): 7-12 wpm trained, about 3 presses per letter, 10-20 h to learn.
- SpeakFaster (Nature Comms 2024): the first letters of the words + an LLM gives 57% fewer actions.
- Blind experts listen at about 1.75-2.3x speed; two voices (left and right ear) at 1.75x tested best.
- Ticker (Nel, Kristensson, MacKay, IEEE TPAMI 2019; arXiv 1804.07777): one switch, audio only, for blind users. The alphabet is spoken in a loop, letter groups in fixed voices and ears (left ear "a", right ear "z"), about 2 alphabets in 5 s; the user clicks when the letter is heard; nothing is picked at once, each click adds evidence and a language model picks the word when its chance passes a threshold. Robust to late or wrong clicks. A user with a noisy eyebrow switch reached 1.3 wpm after 4 sessions of 2 h. No codes to learn.
- Mixing sources: Gmail Smart Compose (arXiv 1906.00080) mixes a personal and a global model, P = a*P_personal + (1-a)*P_global; Gboard ranks suggestions with a small model trained from the picks (arXiv 1812.02903, 1902.01046); weights can follow the context online (Interspeech 2018, Raju et al.). `BucketRanker` already does a simple version (trust per layer from hits).
- Nobody has published Ticker or scanning with an LLM. We combine the proven parts.

## The design: one chooser, many places
Picture: a waiter. He guesses your order from where you sit, you say yes or give a hint, and he guesses again.
- **Request** = where you are + what the result is + what a pick does. Examples: reply to Ana's chat (text, send), YT Music search (query, search and play), a number field (digits, fill), home (an action, run it).
- **Sources** give guesses for a request, each a plug-in: the history of this place (`BucketStore`), the AI from the context (chat, screen, time, playing), the app's own suggestions read from the screen, OS catalogs (apps, contacts, songs played, words spelled before), the next-word model.
- **One ranker** mixes the sources; the trust in each source is learned from picks (as `BucketRanker` does now, made general).
- **One input language** everywhere:
  - The best guess is spoken after every pause (other voice). Long hold (0.8 s) = take it. 2 guesses at once in two ears when both are good.
  - Letters Ticker style (no codes to learn): the letters are spoken fast in a loop, most likely first, in fixed voices and ears; click when you hear yours. A click is evidence, not a final pick, so a late click is forgiven. The letters are a hint: the prefix of the word, or the first letters of the words (the AI reads both). After each letter the guesses are made again.
  - Undo, end word and send are items of the same loop, not menu trips. Hold 3 s leaves.
  - Morse (click dot, hold dash) is a later option for speed, only if the owner wants to learn it (owner: does not know Morse, 2026-10-28).
- **Home is a request too**: letters at home give actions ("w" -> WhatsApp, "bi" -> play Billie Jean, "ana" -> reply to Ana). The menu tree stays for browsing.

## Expected speed (to be proven)
- Ticker-style letters: about one click per letter, a letter every 1-2 s. With guesses about 2x more (SpeakFaster). Today: 0.02-0.12 characters/s.
- "Billie jean" in YT Music: "b i" + the app suggests "billie jean" + long hold: about 6 s (today 633 s).

## Plan (each step: what, copied from, check). Measure on real use with `tools/write_stats.py` after each step.
1. Fix the letter order (Typing.kt `candidates()`: chances per prefix, normalised). Check: unit test "andro" offers "i" first; `tools/write_sim.py` about 37% less.
2. Logs: the trace gets the request (path, app, field kind), each AI answer time, and every click time in the letter loop. Check: lines in `tools/trace.py`.
3. Benchmark: `tools/write_sim.py` grows into a replay of real texts (the owner's + the standard MacKenzie-Soukoreff phrase set) with the real AI; it counts clicks and seconds per character for old and new. Check: today's baseline number written here.
   - Baseline (2026-10-28, `tools/write_bench.py`, model; owner's 3 texts / 50 MacKenzie phrases): today 13.1 / 12.0 s/char, fixed 9.6 / 7.2 s/char, loop 2.6 / 1.4 s/char (loop = placeholder, 350 ms per item + 400 ms reaction, no click noise). Actions/char: today 7.5 / 7.0, fixed 5.1 / 3.7, loop 0.87 / 0.50. The real LSTM is ported to Python and checked against Kotlin (`NextWordDumpTest`).
4. Click model, copied from Nomon (GPL v3, github.com/tbroderick/Nomon, nbonaker/Nomon-Simulation): a pure Kotlin port in `logickit/` (click time vs the item's time, a learned spread, a Bayesian update, pick when the chance passes a threshold). Check: same posteriors as Nomon-Simulation on the same recorded clicks and seeds.
5. Letter loop, layout from Ticker: a Voice Kit organism; letters at about 1.75x in 2 voices and 2 ears, most likely first, with Undo, Space and Send as items of the loop. Check: laptop Flow tests; phone e2e for the real sound.
6. Click test, 5 minutes, as a game (a sound on each hit): measures how late and how even the owner's clicks are; the model starts from it and keeps learning in use. Check: spread measured; loop speed set from it.
7. New Write: request + sources + one ranker. Mixing rule from Smart Compose (a share per source), shares learned from picks (Gboard). Guesses are words, whole texts or actions; no menu while typing. Check: Flow tests; benchmark at least 3x better than step 3's baseline.
8. Sources: computer AI (replies; a new "complete" job that reads letters as a prefix or as first letters of words, SpeakFaster), phone model when there is no computer, the app's suggestions on screen, lists (contacts, songs played, apps, words spelled before). Private notifications never reach any AI. Check: AI answer under 1 s in the trace; benchmark better again.
9. Home as a request: letters at home give actions (open an app, play a song, call, reply). The tree stays for browsing. Check: Flow tests; e2e on the phone.
10. One week of real use, then tune the loop speed and threshold on the recorded clicks (fast-search: halving, same recorded data). Check: seconds per character at or under 2.

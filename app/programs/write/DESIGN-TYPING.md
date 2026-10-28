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
   - Real loop (step 7, `tools/write_bench.py`, real item order, 2 channels, 350 ms, Space/Undo/Send last, 800 ms echo per pick): 2 clicks per pick (`--loop-clicks 2`, default) 7.1 / 4.6 s/char (owner / 500 phrases); best case 1 click per pick 2.3 / 1.3 s/char. The truth is in between and only recorded clicks (step 10) decide.
   - Simulated person (2026-10-28, `tools/write_bench.py --sweep --profile-seed`, Python port `tools/click_model.py` checked against Kotlin by `--verify-click`, 6e-12 off; click = onset + N(180, sd) ms, 5% missed passes, 2% chance of one stray click per loop pass, 10 persons, Undo for wrong picks, model starts from the person's mean and spread; texts the loop could not finish in 3 rounds per char + 30 are dropped and counted). s/char (95% interval) owner / 500 phrases, wrong picks per 100 chars, phrase texts given up of 5000: sd 40 ln99 8.0 (1.7) / 5.2 (0.1), 5.4 / 3.4, 317; sd 60 ln99 19.5 (4.6) / 12.9 (0.6), 16 / 7.1, 2142; sd 100 ln99 60 (14) / 57 (2), 26 / 23, 2521; with ln19: sd 40 5.7 / 5.3, 10 / 9.5, 2799; sd 60 15.2 / 11.2, 20 / 13, 4135; sd 100 59 / 57, 56 / 35, 3524. So the 1-2 click guess (2.3-7.1) only holds for a steady clicker (sd 40); ln19 is faster only at sd 40 and makes many more wrong picks. Without the seed from the Click test the default start (mean 50 ms) picks the neighbour of every item (owner 83 s/char, sd 60). Two causes of the failures: a letter no word of the model can follow has chance 1e-6 (an unknown word like "skate" cannot be spelled; a floor of 0.002 gives 12.3 vs 12.9 s/char and fewer given-up texts only a little); a stray click is very costly. Recorded clicks (step 10) still decide.
   - Two fixes from prior art, measured alone and together (2026-10-28, same bench, `--all --profile-seed`, ln99, 10 persons, same seeds; click = onset + N(180, sd) ms, 5% missed, 2% stray per pass). (1) Smoothed letter chances (`LetterRank.SMOOTH`): chance = 0.8 word model + 0.2 letter pairs, no fixed 1e-6 floor (sweep e 0.05 / 0.1 / 0.2 / 0.3 at sd 60: 12.5 / 12.5 / 12.3 / 12.9 s/char on 500 phrases, owner 14.6 to 14.4 for all: flat, 0.2 has the fewest texts given up). (2) Focus round (`LoopFocus`, Huffman scanning with a language model and noisy switches: Roark, Fried-Oken, Gibbons et al., Computer Speech & Language 2013; Scanning methods and language modeling for binary switch typing, NAACL SLPAT 2010): a click that does not choose stops the loop; the next round plays only the most likely items (2 to 6, chances together 0.95) plus Undo, most likely first, one item per 650 ms, two ears; the model keeps its scores (`ClickModel.restart`); 2 quiet passes bring back the whole loop with the evidence kept. Sweep on 4 persons at sd 60: item time 400 / 500 / 650 ms gives 2.36 / 2.12 / 2.04 s/char (500 phrases), wrong picks 3.2 / 1.6 / 1.0 per 100 chars; coverage 0.9 / 0.95 / 0.99 changes nothing but 0.1 s/char (the cap of 6 items decides); sd 40: 400 ms is worse, 500 and 650 equal. Defaults: 650 ms, coverage 0.95, min 2, max 6, 2 passes, 280 ms of quiet after the click (the bench uses 400 ms from click to first sound).
     s/char (owner / 500 phrases), wrong picks per 100 chars (owner / phrases), phrase texts given up of 5000:
     | sd | before | smoothing only | focus only | both |
     |----|--------|----------------|------------|------|
     | 40 | 8.0 / 5.2; 5.4 / 3.4; 317 | 7.6 / 5.0; 4.2 / 3.0; 375 | 3.3 / 1.8; 4.7 / 1.3; 10 | 3.0 / 1.8; 1.6 / 0.9; 0 |
     | 60 | 19.5 / 12.9; 16.3 / 7.1; 2142 | 14.6 / 12.3; 5.5 / 6.3; 1298 | 3.9 / 2.2; 8.5 / 2.0; 32 | 3.6 / 2.1; 3.2 / 1.0; 0 |
     | 100 | 60 / 57; 25.9 / 23.1; 2521 | 44 / 57; 14.2 / 22.7; 2370 | 6.6 / 2.8; 19.6 / 2.4; 63 | 4.3 / 2.5; 2.9 / 1.1; 0 |
     Why: after a click the loop no longer waits a whole 31-item cycle (5.6 s) for the next click; a focus pass is 2 to 4 s. Stray clicks (sd 60, before / both): none 19.3 / 10.6 before and 3.3 / 2.0 both; 2% per pass 19.5 / 12.9 before, 3.6 / 2.1 both; a stray click on every pass (1.0, not a person but a stress test): before nothing finishes (5000 of 5000 texts given up), both 22 / 37 s/char with 43 of 60 phrase texts given up (3 persons, 20 phrases): the focus round helps but a stray click on every pass still breaks it. The bench person clicks whenever the wanted item is heard and has no reaction-time limit in the focus round (a click about every 0.9 s): the real number comes from recorded clicks (step 10). Trace: `write focus n=4 ms=650`, `write focus-end 31`.
   - Baseline (2026-10-28, `tools/write_bench.py`, model; owner's 3 texts / 50 MacKenzie phrases): today 13.1 / 12.0 s/char, fixed 9.6 / 7.2 s/char, loop 2.6 / 1.4 s/char (loop = placeholder, 350 ms per item + 400 ms reaction, no click noise). Actions/char: today 7.5 / 7.0, fixed 5.1 / 3.7, loop 0.87 / 0.50. The real LSTM is ported to Python and checked against Kotlin (`NextWordDumpTest`).
4. Click model, copied from Nomon (GPL v3, github.com/tbroderick/Nomon, nbonaker/Nomon-Simulation): a pure Kotlin port in `logickit/` (click time vs the item's time, a learned spread, a Bayesian update, pick when the chance passes a threshold). Check: same posteriors as Nomon-Simulation on the same recorded clicks and seeds.
5. Letter loop, layout from Ticker: a Voice Kit organism; letters at about 1.75x in 2 voices and 2 ears, most likely first, with Undo, Space and Send as items of the loop. Check: laptop Flow tests; phone e2e for the real sound.
6. Click test, 5 minutes, as a game (a sound on each hit): measures how late and how even the owner's clicks are; the model starts from it and keeps learning in use. Check: spread measured; loop speed set from it.
7. New Write: request + sources + one ranker. Mixing rule from Smart Compose (a share per source), shares learned from picks (Gboard). Guesses are words, whole texts or actions; no menu while typing. Check: Flow tests; benchmark at least 3x better than step 3's baseline.
8. Sources: computer AI (replies; a new "complete" job that reads letters as a prefix or as first letters of words, SpeakFaster), phone model when there is no computer, the app's suggestions on screen, lists (contacts, songs played, apps, words spelled before). Private notifications never reach any AI. Check: AI answer under 1 s in the trace; benchmark better again.
9. Home as a request: letters at home give actions (open an app, play a song, call, reply). The tree stays for browsing. Check: Flow tests; e2e on the phone.
10. One week of real use, then tune the loop speed and threshold on the recorded clicks (fast-search: halving, same recorded data). Check: seconds per character at or under 2.

## Step 8 built (2026-10-28)
- Sources (`Sources.kt`): `GuessSource` = ask(Request) -> guesses, async. History, spelled words, lists (contacts, apps), the app's own screen suggestions (`WriteServices.screenSuggestions`, read by the accessibility service), and `CompleteSource` (AI "complete": the letters are the start of the word or the first letters of the words; 1 s timeout; never for a private place, a password or a field that is not text or search). The word model plus `BucketRanker` is the source "lm" (the layer "base").
- One mixer (`Mixer.kt`): chance = sum of share x chance of each source; shares = `BucketStore.trust` (start equal, learned from picks, a source with the picked item in its top 3 earns trust). New answers wait for the next cycle of the loop (`LetterLoop.msToNextCycle`).
- AI job: `AiHelp.complete` (computer `/ai/complete`, else the phone model, same prompt and guard: `AiGuard.makeCompletions`, bridge `make_complete`).
- Trace: `write ai complete 312ms 3`, `write source pick=complete rank=0`.
- Not proven without the real computer and phone: the real AI answer time (must stay under 1 s), the real screen suggestions of YT Music. Songs played has no store yet (Write's own earlier searches are in the history source).

## Step 8b: complete job and initials (2026-10-28)
- The request carries the finished words and the letters apart (`done`, `letters`; `text` stays for old callers). Fit rule (`Completion.fits`, `completion_fits`): starts with the done words, then the next word starts with the letters and is longer, or the next words' first letters start with the letters (2 or more). The letters copied as a word ("hay how are you") fail.
- Prompt: few-shot with "Words done / Letters" (`AiGuard.completePrompt`, `build_complete_prompt`, same text). Eval: `tools/complete_eval.py --model M --prompt both` over `bridge/tests/complete_cases.tsv` (21 cases, real Ollama, cached by prompt hash). gemma3:4b new prompt: hit@1 57%, hit@5 62%, about 1350 ms; gemma3:1b: 29%, 29%, about 735 ms (mostly junk). Default stays gemma3:4b.
- Source "initials" (`InitialsSource`, `NextWord.initials`): beam search (width 8) with the word model, no AI, 2 letters or more. "hay" gives "how are you" first; 4 letters take about 13 ms on the laptop; capped at 400 ms on the phone. "omw" gives "or maybe we" first ("on my way" is rank 27): short forms like that come from history or the AI.
- AI timeout = time to the next loop cycle plus one cycle, 1 to 3 s (`LoopProgram.aiBudgetMs`).

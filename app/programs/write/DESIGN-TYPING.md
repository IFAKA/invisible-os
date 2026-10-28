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
- Nobody has published one button + audio + AI. We combine the proven parts.

## The design: one chooser, many places
Picture: a waiter. He guesses your order from where you sit, you say yes or give a hint, and he guesses again.
- **Request** = where you are + what the result is + what a pick does. Examples: reply to Ana's chat (text, send), YT Music search (query, search and play), a number field (digits, fill), home (an action, run it).
- **Sources** give guesses for a request, each a plug-in: the history of this place (`BucketStore`), the AI from the context (chat, screen, time, playing), the app's own suggestions read from the screen, OS catalogs (apps, contacts, songs played, words spelled before), the next-word model.
- **One ranker** mixes the sources; the trust in each source is learned from picks (as `BucketRanker` does now, made general).
- **One input language** everywhere:
  - The best guess is spoken after every pause (other voice). Long hold (0.8 s) = take it. 2 guesses at once in two ears when both are good.
  - Letters by Morse: click = dot, hold (under 0.8 s) = dash, a pause ends the letter. The letters are a hint: the prefix of the word, or the first letters of the words (the AI reads both). After each letter the guesses are made again.
  - Fixed codes (standard where one exists): end word `..--`, delete `........`, send `.-.-.`. Hold 3 s leaves.
  - New users: the same chooser with the old pair scan instead of Morse (fixed letter order). Same engine, a different speller.
- **Home is a request too**: Morse letters at home give actions ("w" -> WhatsApp, "bi" -> play Billie Jean, "ana" -> reply to Ana). The menu tree stays for browsing.

## Expected speed (to be proven)
- Morse alone, trained: 0.5-0.8 characters/s. With guesses about 2x more (SpeakFaster). Today: 0.02-0.12.
- "Billie jean" in YT Music: "b i" + the app suggests "billie jean" + long hold: about 6 s (today 633 s).

## Gaps and checks (in order)
1. Letter order bug fix in today's Write. Check: `tools/write_sim.py` about 37% less; unit test that "andro" offers "i" first.
2. Logs: Write writes its request (app, field kind, path) and the AI time for each guess to the trace. Check: lines in `tools/trace.py`.
3. Button: can the earbud give clean dots and dashes? A 5-minute Morse practice (Echo style) on the phone. Check: under 5% wrong presses.
4. AI time: guesses in under 1 s from the computer. Check: the trace times from step 2.
5. Offline proof with the real AI: steps per text for real texts, old vs new. Check: at least 3x fewer seconds.
6. Build the engine (request, sources, ranker, spellers), then Morse, then home as a request. Check each with `write_stats.py` on real use.

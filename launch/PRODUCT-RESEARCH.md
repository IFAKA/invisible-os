# Product research: the best screen-off, one-button, no-microphone phone

Date: 2026-10-27. Method: web search, then search again on each gap. "Unknown" means I looked and found no answer. I did not let the current design shape the answer; the code was checked only for section 4.

## 1) Best first target user

**Adults with low vision or blindness who find touch-screen gestures hard: people who lost sight later in life, and older people.** They need a phone that is simple and safe, not a faster expert tool.

Why this group (evidence):
- Need is large. WHO: at least 2.2 billion people have a vision impairment ([WHO](https://www.who.int/news-room/fact-sheets/detail/blindness-and-visual-impairment)). IAPB 2024 (as quoted by [NaviLens](https://www.navilens.com/en/blog/discapacidad-visual-cifras-globales)): about 43 million blind, 295 million moderate to severe.
- They already pay for simple. BlindShell Classic 2 costs $489 and its reviews praise real buttons and "no swiping" ([AFB](https://afb.org/aw/23/4/17918)). Quit reasons in the research: hard touch screens, long training, and a return to tactile tools ([Nature Sci. Reports 2025](https://www.nature.com/articles/s41598-025-91755-w)).
- Voice is a poor fit for them. Blind users say voice assistants mishear, fail in noise, and are not private in public ([ACM, "Blind Leading the Sighted"](https://dl.acm.org/doi/fullHtml/10.1145/3368426)). A no-microphone design avoids all three.
- Expert blind users do NOT need us: VoiceOver/TalkBack are richer. 93% of screen-reader users with disabilities use one on mobile ([WebAIM #11](https://webaim.org/projects/screenreadersurvey11/)). So do not sell to experts first.

Other groups, ranked (why not first):
2. Hands-busy people (runners, cyclists, workers in gloves): real need, but a headset button already does play/pause/skip. The extra value is small and I found no data on runners or cyclists (unknown).
3. Drivers: 34% read a text and 23% type one while driving ([AAAFTS via NHTSA](https://www.nhtsa.gov/book/countermeasures-that-work/distracted-driving/understanding-problem)). Big risk, but Siri/CarPlay/Assistant already own this, and they use voice.
4. People who want less screen time: a real trend (Light Phone III, Mudita, Punkt) but the market is small, about 2% of US handsets ([summary](https://www.accio.com/business/dumbphone-trend)). Claims of "1.5 h saved" are blog claims, not research.
5. Single-switch users with motor impairment: the only group where one-switch scanning is proven and needed. Very good fit for the method, but they often need an adapted switch, and a hard-to-reach, small group. A good second step: the same design serves them.

Gap left open: no source compares audio-only one-button scanning with VoiceOver for newly blind or older users. This needs a small user test (see section 5).

## 2) Top 10 tasks, ranked

Ranked from usage data: over 90% of blind users use calls, messaging, web, email; about 80% calendar, music, social, GPS; about 70% reminders and OCR ([review of 466-person study](https://arxiv.org/pdf/2505.19325)). Logs of expert TalkBack users: WhatsApp 42 min/day, Phone 42 min/day, YouTube 40 min/day; 2.3 h/day on calls ([Microsoft Research, CHI 2021](https://www.microsoft.com/en-us/research/wp-content/uploads/2021/05/TalkBack_Log_Study_CHI_2021.pdf)). Ranking below is my judgment from this, limited to what one button and no mic can do.

1. Answer, reject, end a call; hear who calls.
2. Call a favourite person (short list).
3. Hear new messages (WhatsApp first), know who sent them.
4. Reply to a message with a ready answer (tap to pick).
5. Time, date, battery, next appointment, weather (the "status" check).
6. Play, pause, skip music, podcasts, audiobooks; volume.
7. Alarm, timer, reminders.
8. Navigation: next turn, where am I (50% of blind users say navigation apps miss needs: [survey](https://www.tandfonline.com/doi/full/10.1080/17483107.2025.2544942)).
9. Emergency: call or message a set contact, fast.
10. Write a short text of your own (hard for one button: single-switch scanning averages 1.72 wpm over 80 cases, [switch review](https://www.researchgate.net/publication/267734026_Method_for_enhancing_text_entry_rate_with_single-switch_scanning)). So reply by choice first, write second.

Not on the list, but known: read text from a camera (OCR), ask a person to see (Be My Eyes, over 1 million blind users: [Be My Eyes](https://www.bemyeyes.com/news/be-my-eyes-reaches-1-million-blind-and-low-vision-users-and-10-million-volunteers/)). Both need the screen/camera, so they are outside "screen off, no mic". Say so honestly.

## 3) Design criteria, with sources

| # | Criterion | Value to use | Source / strength |
|---|---|---|---|
| a | Scan step (time per item) | Start slow, adapt to the user. Rule: step = user's average press time / 0.65. Real range 0.3 to 5 s. Apple Switch Control default 0.5 s (visual), 2 loops. | [Simpson, RESNA 2006](https://www.resna.org/sites/default/files/legacy/conference/proceedings/2006/Research/CAC/Simpson.html); [AbilityNet iOS 18](https://mcmw.abilitynet.org.uk/how-to-use-switches-to-control-your-device-in-ios-18-on-your-iphone-or-ipad). Good for visual scan. **Unknown:** no audio-only default found; audio items need the word length plus gap. |
| b | Too slow costs a lot | 2 s step instead of 1 s halves speed. Auto-tuning delay raised entry up to 321%. | Same RESNA/delay papers. Medium. |
| c | Max list length | 4 to 8 items for unordered lists; more only if ordered by use or meaning. Broad and shallow beats deep. The old "5 or fewer" rule is weakly supported. | [Commarford 2008](https://journals.sagepub.com/doi/10.1518/001872008X250665); [Yalla & Walker](https://sonify.psych.gatech.edu/publications/pdfs/2007-GVU0712-YallaWalker.pdf). Medium. |
| d | Cue type | Speech or spearcons (sped-up speech) beat earcons (abstract tones) for finding items. Spearcon gain grows with longer lists. In a driving test spearcons cut looking but were slower. | [Walker, Nance, Lindsay ICAD 2006](https://www.icad.org/Proceedings/2006/WalkerNance2006.pdf); [Jeon, AutoUI 2009](https://www.auto-ui.org/09/docs/p91-jeon.pdf). Good. |
| e | Confirmation sounds | A short sound for every success. Earcons are good for state ("done", "back"), not for naming items. | Screen-reader practice ([Earcon](https://en.wikipedia.org/wiki/Earcon)). **Unknown:** no study on earcon confirmation or undo for blind users. |
| f | Error recovery | Always one cheap way back; a short cancel window before irreversible acts. Users misjudge audio-only errors (42% detected by blind users). | [arXiv 2410.20564](https://arxiv.org/pdf/2410.20564). Weak; mostly design judgment. |
| g | Onboarding length | Short. Quit reasons are "extensive training and support". Aim: first success inside 1 minute, by doing, not by listening. | [Nature 2025](https://www.nature.com/articles/s41598-025-91755-w). Medium. **Unknown:** no number of minutes found. |
| h | Speech speed | Experts want fast speech; novices want slow. Give a setting; default slow for the target user. | [ACM](https://dl.acm.org/doi/fullHtml/10.1145/3368426). Medium. |
| i | Privacy | Read sender only for private apps; no voice, so nothing to overhear. | Same ACM study (public-use worries). |
| j | Text entry | Expect 1 to 3 wpm by scanning, up to about 5 to 7 with Morse or tuned layouts. Offer choice lists before typing. | [One-key challenge](https://www.researchgate.net/publication/221652150_The_one-key_challenge_Searching_for_a_fast_one-key_text_entry_method); switch review above. Good. |

What made premium ones feel premium (from reviews and forums): real tactile control (BlindShell), tiny talk (Soundscape users liked that it was "not very verbose" and did not "shepherd" them: [AppleVis](https://www.applevis.com/blog/microsoft-discontinue-its-soundscape-app-make-code-available-open-source-software)), and trust that it keeps working (Soundscape was shut down in 2023 and users called it "a devastating blow"). What made users quit: too much training, mishearing voice, speech that is too slow or too chatty for experts, and cost. **Unknown:** AppleVis complaints about Siri Announce Notifications; not found.

## 4) Current app vs the criteria (code checked briefly)

Meets / Partly / Missing.

| Item | Status | What the code shows |
|---|---|---|
| Scan step (a, b) | Partly | Fixed gap 1500 ms (`Pacing.ITEM_GAP_MS`), user can set it in settings. No auto-tuning to the user's press time. |
| List length (c) | Meets | Tree: places hold at most 8 entries, home max 10, ordered by use (AppTree). |
| Cues (d) | Partly | Spearcons exist but are off by default; speech names items. Fine for novices; uncertain for experts. |
| Confirmation sounds (e) | Meets | Per-screen tones, close sound, success sounds (`Earcons.kt`, SOUND-DESIGN.md). |
| Error recovery (f) | Meets | Triple click = back everywhere; undo window `undoMs` (3 s) before send and timer start. |
| Onboarding (g) | Partly | A `practice` program exists, ends into the home menu and speaks slowly. Length not measured; no stranger test result recorded here (see launch/STRANGER-TEST.md). |
| Speech speed (h) | Meets | `speechRate` setting, default 0.9. |
| Privacy (i) | Meets | Private apps read as "<App>, new notification" only; no mic. |
| Text entry (j) | Meets | Write program (letters, phrases, word pick) and reply choices first. Speed not benchmarked. |
| Task 1 calls | Partly | Commit 61eda97: caller announce, answer/reject/end. Needs a real-phone test with a stranger. |
| Task 2 call a contact | Partly | "Call a contact" exists; list size and order by use not checked. |
| Task 3 hear messages | Meets | Notification store, chat apps adapter, sender read. |
| Task 4 reply | Meets | Reply candidates, undo, more chat apps. |
| Task 5 status | Meets | Today and Phone: time, battery, weather, event, next turn. |
| Task 6 music | Meets | Music program, volume, play/pause by click. |
| Task 7 alarm, timer, reminders | Meets | Timer, Set alarm, Tasks. |
| Task 8 navigation | Partly | Only "next turn" read from a running nav app. No "where am I", no start of a route. |
| Task 9 emergency | Partly | Call + SMS to a set contact (commit 61eda97); needs setup, test of speed. |
| Task 10 write | Partly | Works; slow by nature. |
| Fit for target user | Partly | Menu is English-only; the target group may not read English (Spanish default for messages). Setup needs a sighted helper (usage access, notification access via adb or settings). |

## 5) The 5 most important changes, ranked by value

1. **Make first setup possible without eyes and without a helper.** Today a person needs settings screens, special permissions (notification access, usage access) and sometimes adb. The target user cannot do this alone. A spoken, button-only setup guide, or a short pairing step with a family member, decides if anyone can start. This is the biggest gate to every other value.
2. **Prove it with 5 to 8 real low-vision/older users, and measure.** Record time to first success, calls answered, and quit points. This closes the biggest unknown (no study compares audio one-button scanning with other phones for this group). Use the existing STRANGER-TEST.md protocol.
3. **Adapt the scan speed to each person.** Use the 0.65 rule: measure how long a user takes to click after hearing an item, and set the gap from it, slow at first and faster as they learn (the app already has a `ReactionGap` test and `learnOrder`). Wrong speed is the main cost in all scanning papers.
4. **Make calls the best part, then fill the gaps in the top 4 tasks.** Calls and messages are the top tasks everywhere. Check: answer in one click, call the 3 most used people at the top of the list, speak the caller's name first. Then add "where am I" to navigation (task 8).
5. **Language and voice choice for the menu.** Menu is English-only while the user and messages may be Spanish (or other). Let the user choose menu language, and offer spearcons as a switch to try when they become faster. Without this, many people in the target group cannot use the menu at all.

Not recommended now: more games, more programs, wake word or any voice input (against the owner rule, and voice is what users complained about).

## Gaps still open (looked, not found)
- Audio-only scan default step in any standard or study.
- Earcon confirmation and undo studies with blind users.
- Runner and cyclist needs and headset-button usage data.
- Blind-user complaints about Siri Announce Notifications and AirPods controls.
- Number of minutes of onboarding that is acceptable.
- Why Android Switch Access auto-scan defaults were not found (Apple only).

# Premium pass: criteria (2026-10-27)

"Premium, simple, sophisticated" for this app = few things, one system, calm, nothing that looks like debug. Each rule has a source and a check.

## Screen
| # | Criterion | Check | Source |
|---|---|---|---|
| S1 | One system font; scale: large title 34, title 22, head 17 medium, body 17, small 15, caption 13 | `Type` in Ui.kt is the only place sizes exist | Apple HIG typography (Body 17, Large Title 34), via secondary summaries |
| S2 | 8 dp grid: all gaps are 8/16/24/32/48 | `Ui.S1..S6` | common reverse-engineering of Apple UI to 8 pt (convention, not Apple rule) |
| S3 | Touch target >= 44 dp (we use 48) | buttons min 44/48 | Apple HIG, Android a11y |
| S4 | Text contrast >= 4.5:1 body, 3:1 large; checked in light AND dark | colours in `Ui` are Apple system pairs | Apple HIG color/dark mode |
| S5 | Colours follow the system light/dark; semantic names, no raw colour outside Ui | `Ui.sync`; `KitRulesTest` | Apple HIG dark mode |
| S6 | One accent colour; green/red only for state | `Ui.accent` | Apple HIG color |
| S7 | Grouped lists with hairlines, not a card per item | `Kit.group` | iOS Settings pattern |
| S8 | Onboarding: one idea per screen, one main button, quiet skip, ask permission when needed, help reachable later | `Kit.onboarding`; Help page | Apple HIG onboarding (via summary; page text not retrievable) |
| S9 | Motion small (fade + 8 dp), 220 ms, off when the system animation scale is 0 | `Kit.enter` | Apple HIG motion: respect Reduce Motion |
| S10 | No debug text for a normal user: version only as a caption at the bottom | MainActivity | owner request |

## Words
| W1 | <= 7 words per spoken sentence | review of all `*Sentences.kt` | AGENTS.md (teaching research) |
| W2 | One name per thing in speech and on screen (Flashlight, not Torch) | grep | owner request |
| W3 | Plain, warm, no jargon | review | me.md |

## Sound
| A1 | Musical timbre, not pure tones; pitch never the only cue | Earcons.kt (wooden bar, rhythm and register differ) | Brewster et al. 1992-95, summarised in Dingler/Lindsay/Walker ICAD 2008 |
| A2 | Few, consistent earcons; spearcons for options | SOUND-DESIGN.md v2 | Walker & Nance ICAD 2006; Google earcon guide |
| A3 | Short (<= 250 ms feedback, <= 500 ms screen), quiet under speech, never cut (fade) | Earcons.kt LEVEL, fades | SOUND-DESIGN.md rules 4-5 |
| A4 | Do not overuse feedback; must work when muted | tap is the quietest (-26 dB) | Apple HIG haptics/sound summary |

## Gaps found and decisions
- Apple's HIG pages could not be read directly (only summaries). Decision: use the numbers that agree across summaries (17/34 pt, 44 pt, 4.5:1); mark the rest as convention.
- Earcons: the v2 + "Apple's rules" rebuild already meets A1-A4 on paper. Nothing changes without listening on the earbud; I cannot hear. Decision: no sound change (changing blind is worse than keeping).
- Reduce Motion: new gap, built into `Kit.enter`.
- Threw away: heavy white cards with a bold title each, outlined inputs, the banner (big blue block) for setup, pill-shaped buttons, the "Step n of 11: ... - done/skipped/now" text list, "Torch"/"Cannot do it" wording, build stamp on every page.

# Design system

The screen is for a glance, not for reading. You use the phone without looking. So: huge, high contrast, one thing at a time.
Voice, screen and gestures are one design: **every spoken thing has its picture, and every picture control has its voice.**

Everything below lives in two files: `DesignTokens.kt` (the values) and `Ui.kt` (the `Kit` parts built from them).
A screen sets no size, no colour, no font by hand. The build fails if it does (rules `ui-*` in `app/voicekit-rules.tsv`).
To see the whole system on the phone: debug build, `adb shell am broadcast -n com.faka.invisible/.AiTestReceiver --es show GALLERY`.

## Colour roles (dark by default; light keeps the same meanings)
| Role | Meaning |
|---|---|
| `now` cyan | What is being spoken this moment. The only glowing thing. |
| `press` orange | The press the person must make (dots and bars). |
| `done` green | It worked, it was picked. |
| `bad` red | A problem. Nothing else is red. |
| `ink` | Main text. |
| `soft` | Quiet text: hints, captions, lines before and after. |
| `page`, `card`, `field`, `tint` | Surfaces: background, grouped surface, input or inner row, soft button. |
| `line`, `edge` | Hairline; off switch, slider track, chevron. |
| `accentFill` | Filled button background (white text on it). |

Text roles must have 4.5:1 contrast on the page (light and dark); `DesignTokensTest` checks it.

## Type scale (four steps, no others)
| Step | sp | Weight | Job |
|---|---|---|---|
| `DISPLAY` | 40 | medium | The NOW line. |
| `TITLE` | 28 | medium | Screen titles. |
| `BODY` | 18 | regular | Rows, normal text, buttons. |
| `CAPTION` | 14 | regular | Hints, footers, quiet lines. |
| `MONO` | 14 | monospace | Terminal and patch text only. |

The NOW line never shrinks continuously. `Kit.nowLine` picks one step: DISPLAY if the text fits in 4 lines, else TITLE if it fits in 6, else BODY.
Lines before and after the NOW line use only BODY and CAPTION, the same on every screen.

## Space, shape
- Space (dp): S1 8, S2 16, S3 24, S4 32, S6 48.
- Radius: R1 12 (rows, buttons, chips, fields), R2 20 (cards). One stroke width (1 dp). One glow (the NOW line). Touch target at least 48 dp; the filled main button is 56 dp.

## Components (one per job, in `Kit`)
| Job | Part |
|---|---|
| Page title with back link | `header` |
| Spoken line / item now | `nowLine`, `mirrorScreen`, `loopScreen` (the stage) |
| The press it asks for | `pressGlyph` |
| Row that opens a page | `navRow`, `dotRow`; one choice: `choiceRow` |
| Group / card | `group`, `card` |
| Main button (full width, 56 dp, filled) | `primary` |
| Second button (tint) | `btn` |
| Text button | `quiet` |
| Switch row | `toggleRow`, `toggle` |
| Status chip | `statusChip` (a label in a role colour), `chip` (a value button) |
| Footer | `footer` |
| Home status, first-run step | `status`, `onboarding` |
| Terminal text | `mono`, `monoLine` |

## One main thing per screen
- Every screen has ONE main thing and at most 3 controls. The app home has three zones: the state in one huge sentence (with its one fix button if something is missing), one big button ("Open the menu"), and one quiet row "Settings and more" that opens the second screen with every setting and its value.
- The Stage (the spoken screen) has no Back, Close or Settings links while a list is read or a program runs. It shows only the NOW line, the press glyph and the sound strip, plus one small quiet "Leave" button for sighted people (`Kit.mirrorScreen` foot).
- The first minute is the same story in voice and picture: setup, then the menu with its short hint, then after a pick "Done. Hold again any time." (first 3 sessions, also drawn on the Stage); the practice is offered nowhere (Help page only)

## How to add a screen
1. Build it only from `Kit` parts and tokens (`Ui.S1..S6`, `Space.*`, `Ui.<role>`). A missing part is added to `Ui.kt` first, then shown in `Gallery.kt`.
2. If it speaks, the voice kit shows it (the stage); do not write the spoken text by hand. If it has a control you can touch, the same choice must be reachable by the button and have its voice.
3. Run `./gradlew checkVoiceKit` and look at the gallery. A new size or colour is a change to `DesignTokens.kt` plus `DesignTokensTest`, not to a screen.

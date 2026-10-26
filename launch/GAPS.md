# Feature gap audit: what a screen-off earbud phone user expects

Method: read the manifest, `LauncherSettings.HUB_ITEMS`, `Actions.kt`, `Info.kt`, adapters and programs in `app/`. Date: 2026-10-27.
Status: exists / partial / missing. Paths are under `app/`.

| Need | Status | Evidence | v1 |
|---|---|---|---|
| Hear new messages | exists | `ControllerNotificationListener.kt`, `adapters/.../ChatAppsModule.kt`, `TeamsModule.kt`, `oskit/.../logickit/MessageStore.kt`. Chat apps: WhatsApp, Teams, WeChat, Claude only. | must-have |
| Read other apps' notifications (SMS, email, Telegram, Signal) | partial | `NotificationStore.kt` keeps all notifications and the tree reads them with Dismiss / Mute / Open app. No quick reply except the 4 chat apps. | must-have: add Google Messages (SMS), Telegram, Signal, Gmail as reply or read modules |
| Reply to messages | exists | Candidates from `logickit/MessageLogic.kt`, undo window (`LauncherSettings.undoMs`), `sendReply`. Optional AI: `LocalAi.kt`. | must-have |
| Write a new message to a person | missing | `NEW-MESSAGE-IDEA.md` is only an idea. Write (`programs/write`) types into a focused field but there is no "pick contact, send". | later (v1.1) |
| Voice dictation for replies | missing on purpose | Rule: no microphone. | never (product rule) |
| Incoming calls: hear caller, answer, reject | missing | No call code (no `TelecomManager`, `InCallService`, `PHONE_STATE`). Android answers on the earbud button natively, but the app does not announce the caller. | must-have: announce caller name; answer/reject by click |
| Make a call | missing | `Phone` item in the menu is status/volume/torch (`LauncherSettings.PHONE_ITEMS`), not a dialer. | must-have: call from a short list (favourites, recents) |
| Contacts | missing | No `ContactsContract` use. | must-have for calls (favourites only) |
| Music play / pause / next / previous | exists | `Actions.kt` `forwardToMusic`, `playQuickPicks`; keys with `MediaKeyIntake.kt`. | must-have |
| Music: search and play a song | partial | `logickit/MusicSearch.kt`, `Actions.searchAndPlay` (YouTube Music; needs typed text; app comment says some players block start). | later |
| Podcasts / audiobooks (speed, skip 30 s, resume) | missing | `Forward` is only PLAY_PAUSE, NEXT, PREVIOUS (`ControllerMode.kt:17`). Podcast apps appear only as apps in the tree (`AppCategories.kt`). | must-have: skip back/forward 30 s for any player |
| Volume | exists | `Actions.adjustVolume`, Volume dial (`Screen.VOLUME_DIAL`). | must-have |
| Navigation: next turn | partial | `Info.kt` `Navigation` reads the Google Maps notification. Cannot start a trip or search. | v1: keep read; start navigation to Home / Work = later |
| Time | exists | `Actions.menuAction("Time")`. | must-have |
| Battery | exists | `Actions.menuAction("Battery")`, Status in Phone. | must-have |
| Weather | exists | `Info.kt` `PhoneInfo.weather` (open-meteo, coarse location). | later (nice) |
| Timers | exists | `Actions.startTimerNow`, `AlarmClock.ACTION_SET_TIMER`, timers in settings. | must-have |
| Alarms: set, hear next, stop | exists | `Actions.setAlarmNow`, "Next alarm", `AlarmReceiver.kt`, `stopAlarmNow`. | must-have |
| Calendar: next event | partial | `Info.kt` `nextEvent` (next 24 h, one event, no all-day). No list, no reminders announced. | later: list today |
| Tasks / to-do by ear | exists | `TaskStore.kt`, `AddTaskActivity.kt` (Share). | later (nice) |
| Notifications: dismiss, mute app, privacy for private apps | exists | `ControllerNotificationListener.kt` (`dismiss`, `mute`), `NotificationStore.kt`. | must-have |
| Do not disturb, torch, find my phone | exists | `Actions.kt` (DND, `toggleTorch`), `ControllerMode.findPhone`. | later |
| Open any app | exists | `AppTree.kt`, `AppCategories.kt`, `NameFinder.kt` (Find). Launches the app, but inside it the user has no screen. | must-have |
| Emergency: call emergency number, share location, SOS | missing | No grep hits for emergency or SOS. | must-have for safety claim: at least "call emergency number" and "send my location to a contact" |
| Lock screen use | partial | Assistant role (`InvisibleVoiceService.kt`) and `HandsFreeActivity.kt` need a manual "Always" step (README). | must-have: guided setup |
| First-run setup that a stranger can finish | partial | Setup card in `MainActivity.kt`; many manual steps (Button access, Notification access, Assistant, battery, usage access via adb today). Usage access needs `adb` per `AndroidManifest.xml` comment. | must-have: all steps inside the app, no adb |
| Teaching / tutorial by ear | partial | Guard and Duel have tutorials. The OS has no spoken first-minute tour. | must-have: a 1-minute tour |
| Speech language / voice quality | exists | `voicekit/engines/NeuralSpeech.kt` (on-device voice, optional download), system TTS fallback. | must-have |
| Accessibility basics (works with TalkBack) | unknown | Not tested. TalkBack and the app both use the screen/keys; conflicts possible. | must-have: test with TalkBack on |
| Release safety | partial | `AiTestReceiver` is exported (dev hooks); `allowBackup` not set. See `PLAY-STORE.md`. | must-have: fix before release |
| Games | exists | `programs/echo`, `guard`, `duel`, `compass`. | later |
| Calculator, price sum | exists | `logickit/Calculator.kt`, `PriceSum.kt`. | later |

## Top gaps for v1
1. Calls: announce caller, answer/reject, call a favourite (all missing).
2. Emergency action (missing).
3. SMS and more chat apps (Telegram, Signal, Gmail) for read and reply.
4. Setup without adb (usage access, assistant default, restricted settings) and a spoken first-minute tour.
5. Podcast and audiobook control (skip 30 s, speed). Also release fixes: exported dev receiver, backup.

## Product rule that stays
No microphone, so no dictation and no "Hey assistant". Anything that needs speech input must use the button and a list.

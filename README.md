# Invisible Controller: control your Android phone with one earbud button, screen off

**Invisible Controller** is an open-source Android app. It turns the single button on any wired or Bluetooth earbud or headset into a hands-free, eyes-free controller. Reply to WhatsApp messages, set a timer, change volume and hear your status, all with the screen off and the phone in your pocket.

It uses **auto-scan** (also called single-switch scanning, the method used in assistive technology): the phone speaks the options one by one, and you click when you hear the one you want. One button is enough. No microphone, no voice recognition, no cloud.

> Keywords: Android accessibility app, headset button controller, earbud button actions, screen-off phone control, eyes-free phone, hands-free WhatsApp reply, single-switch scanning, auto-scan menu, blind and low-vision friendly, text-to-speech, Kotlin.

## What you can do

| Gesture | Phone idle or music playing | Inside the menu |
|---|---|---|
| 1 click | Play / pause | Pick the option you just heard |
| 2 clicks | Next track | Previous item in a list |
| 3 clicks | Previous track | Go back one level |
| Hold | Wake the menu (works on the lock screen) | Leave / exit |

Menu items: **Messages** (read, reply, or turn a message into a task; Spanish or English), **Tasks** (a to-do list you add to and finish by ear), **Timer**, **Volume**, **Status** (time, battery, unread, next alarm, weather, next event, navigation step), **Games** (Duel, an audio fighting game), **More** (torch, do not disturb, find my phone, exit).

## Features

- **Reply to WhatsApp, Teams and WeChat** notifications by earbud. Reply candidates are built from the message, and the language is detected on the device.
- **Undo window.** After a send you have a few seconds to cancel with any click.
- **Tasks by ear.** "Add task" is always the first option. It offers your past tasks, then starters. Click a task right after you hear it to finish it (3 s to cancel). Share text from Keep or any app to add tasks too.
- **One way to pick a text.** Tasks and replies use the same step: your own past texts first, then new ones.
- **Local AI (optional).** Turn on "AI on the phone" in the app. It downloads Gemma 4 E2B (2.6 GB, Wi-Fi only, resumes) and runs it on the phone. It writes reply sentences and clean to-do items from a message. Needs about 6 GB RAM and 3.3 GB free. Off by default; the APK has no model inside.
- **Learns what you use.** Options you pick often move to the front. Optional sped-up "spearcons" are off by default (switch in the app). Everything stays on the phone.
- **Sound design for no-screen use.** Each screen has its own short tone, so words are optional. See [SOUND-DESIGN.md](SOUND-DESIGN.md).
- **Settings screen.** Check setup in one place, then choose which menu items you hear, their order, timer lengths, pause, voice speed, cancel time and more.
- **Battery friendly.** Nothing polls. It reacts to button presses.
- **Private.** No account, no analytics, no microphone. The internet is used only to download the AI model, when you ask.

## Install

Requirements: Android 10 or newer (API 29+), Android SDK and a phone with USB debugging.

```sh
cd app
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

or run `app/run.sh` (build, install, start). Then open the app and turn on the three items in the setup card:

1. **Button access** (accessibility service) to hear the earbud button.
2. **Notification access** to read and reply to new messages.
3. **Assistant** so a long press works on the lock screen. Then open the "Complete action using" box once (`adb shell am start -a android.speech.action.VOICE_SEARCH_HANDS_FREE`), tap **Invisible**, then **Always**. Without this, Google and Invisible both claim the hold and the lock screen cannot show the chooser.

On some phones (Xiaomi / HyperOS and others) also allow the app to run in the background and turn off battery optimization for it, or the system may stop it when the screen is off.

## Share the APK

`share/invisible.apk` is a debug-signed build with setup steps in `share/SETUP.txt` (the APK is git-ignored; build it yourself). Build it from a clean commit. The new phone needs "install unknown apps", Button access and Notification access turned on by hand.

## Docs

- [UX-DESIGN.md](UX-DESIGN.md): goals, research on single-switch scanning, gesture cost model, design decisions.
- [SOUND-DESIGN.md](SOUND-DESIGN.md): earcon rules and the full sound set (listen in `sound/wav/`).
- [sdk/GUIDE.md](sdk/GUIDE.md): make your own program for the OS (starter project, tests, kit check, how to hand it in).
- [GAME-TELL.md](GAME-TELL.md): the Duel audio game.
- [NEW-MESSAGE-IDEA.md](NEW-MESSAGE-IDEA.md): researched idea for starting a new chat with the screen off (not built).

## Tests

```sh
cd app && ./gradlew testDebugUnitTest
```

The menu is a pure state machine ([`ControllerMode.kt`](app/os/src/main/java/com/faka/invisible/ControllerMode.kt)) with injected time and output, so gestures, scanning, undo and replies are unit tested.

## Who it is for

People who cannot or do not want to look at the phone: blind and low-vision users, people with motor limits, runners, cyclists, cooks, drivers (where legal), or anyone who wants to answer a message without taking the phone out.

## Status

Early, personal project, used daily. Issues and ideas are welcome.

## License

MIT, see [LICENSE](LICENSE).

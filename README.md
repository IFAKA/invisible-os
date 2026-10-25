# Invisible Controller: control your Android phone with one earbud button, screen off

**Invisible Controller** is an open-source Android app. It turns the single button on any wired or Bluetooth earbud or headset into a hands-free, eyes-free controller. Reply to WhatsApp messages, set a timer, change volume and hear your status, all with the screen off and the phone in your pocket.

It uses **auto-scan** (also called single-switch scanning, the method used in assistive technology): the phone speaks the options one by one, and you click when you hear the one you want. One button is enough. No voice recognition, no network, no cloud.

> Keywords: Android accessibility app, headset button controller, earbud button actions, screen-off phone control, eyes-free phone, hands-free WhatsApp reply, single-switch scanning, auto-scan menu, blind and low-vision friendly, text-to-speech, Kotlin.

## What you can do

| Gesture | Phone idle or music playing | Inside the menu |
|---|---|---|
| 1 click | Play / pause | Pick the option you just heard |
| 2 clicks | Next track | Start the list again |
| 3 clicks | Previous track | Go back one level |
| 4 clicks or hold | Wake the menu (works on the lock screen) | Leave / exit |

Menu items: **Messages** (read and quick-reply, in Spanish or English), **Timer**, **Volume**, **Status** (time, battery, unread, next alarm), **More** (torch, do not disturb, exit).

## Features

- **Reply to WhatsApp, Teams and WeChat** notifications by earbud. Reply candidates are built from the message, and the language is detected on the device.
- **Undo window.** After a send you have a few seconds to cancel with any click.
- **Learns what you use.** Options you pick often move to the front and become short sped-up "spearcons". Everything stays on the phone.
- **Sound design for no-screen use.** Each screen has its own short tone, so words are optional. See [SOUND-DESIGN.md](SOUND-DESIGN.md).
- **Settings screen.** Check setup in one place, then choose which menu items you hear, their order, timer lengths, pause, voice speed, cancel time and more.
- **Battery friendly.** Nothing polls. It reacts to button presses.
- **Private.** No account, no analytics, no internet permission.

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
3. **Assistant** so a long press works on the lock screen.

On some phones (Xiaomi / HyperOS and others) also allow the app to run in the background and turn off battery optimization for it, or the system may stop it when the screen is off.

## Docs

- [UX-DESIGN.md](UX-DESIGN.md): goals, research on single-switch scanning, gesture cost model, design decisions.
- [SOUND-DESIGN.md](SOUND-DESIGN.md): earcon rules and the full sound set (listen in `sound/wav/`).
- [NEW-MESSAGE-IDEA.md](NEW-MESSAGE-IDEA.md): researched idea for starting a new chat with the screen off (not built).

## Tests

```sh
cd app && ./gradlew testDebugUnitTest
```

The menu is a pure state machine ([`ControllerMode.kt`](app/app/src/main/java/com/faka/invisible/ControllerMode.kt)) with injected time and output, so gestures, scanning, undo and replies are unit tested.

## Who it is for

People who cannot or do not want to look at the phone: blind and low-vision users, people with motor limits, runners, cyclists, cooks, drivers (where legal), or anyone who wants to answer a message without taking the phone out.

## Status

Early, personal project, used daily. Issues and ideas are welcome.

## License

MIT, see [LICENSE](LICENSE).

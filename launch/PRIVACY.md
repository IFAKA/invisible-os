# Privacy Policy for Invisible OS

Last updated: 2026-10-27
Contact: facundoarenas29@gmail.com
App: Invisible OS (package `com.faka.invisible`)

## In short
- The app never uses the microphone. It does not ask for the permission. It has no voice input.
- No account. No ads. No analytics. No tracking. We have no server.
- Your messages, tasks, and settings stay on your phone.
- The app uses the internet only for a few small jobs. They are listed below.
- The app is open source. You can read the code.

## What the app does
Invisible OS lets you control your phone with one earbud button while the screen is off. The phone speaks the options. You click when you hear the one you want.

## Microphone
The app does not record or listen to sound. It does not use speech recognition. Input is only the earbud button (clicks and holds) and, for text, the app screen or the Share menu.

The app registers as a "digital assistant" only so a long press on the earbud button reaches it on the lock screen. It has an empty recognition stub that never listens. The stub is required by Android to accept the assistant setting.

## Permissions and access, and why
| Permission or access | Why | What the app reads |
|---|---|---|
| Accessibility service ("Button access") | To hear the earbud button when the screen is off. Also, in the Write keyboard, to put your typed text into the text field of another app and to read nearby text on screen as context for word suggestions. | Button presses. Only when you use Write: the text on the screen of the app in front, and the field you type in. Password fields are never read. |
| Notification access | To read new notifications aloud, to reply to chat messages (WhatsApp, Teams, WeChat, Claude), to dismiss and to mute apps. | The notifications of your apps (title, text, sender, actions). Private ones (secret, locked-private, bank and password apps) are read only as "App, new notification". |
| Usage access (`PACKAGE_USAGE_STATS`) | To count how often you open each app in the last 30 days. The most used apps are placed first in the spoken menu. | App launch counts. Not screen time. Not what you do inside apps. |
| Calendar (`READ_CALENDAR`) | To say your next event in the next 24 hours. | Event title and start time. Nothing is changed. |
| Approximate location (`ACCESS_COARSE_LOCATION`) | To say the weather where you are. | The last known approximate location, used once per request. |
| Internet and network state | See "What leaves the phone". | See below. |
| Notifications (`POST_NOTIFICATIONS`) | Android requires it for the always-on service notification. | Nothing. |
| Foreground service (media playback) | To keep reading the earbud button while the screen is off. | Nothing. |
| Do Not Disturb access (optional) | Only if you use the "Do not disturb" menu item. | Nothing is read. |
| Camera flash | To turn the torch on and off. No camera pictures. | Nothing. |
| Digital assistant role (voice interaction service) | So a long press works on the lock screen. | Nothing. |

All of these are optional. You turn them on yourself in Android settings. Without one, only the feature that needs it stops working.

## What is stored on your phone
All of it is in the private storage of the app. Other apps cannot read it.
- Settings (menu order, voice speed, timers).
- Your tasks and the texts you used before (to offer them again).
- Which menu choices and replies you pick, to move frequent ones first.
- A short log of button gestures and events, for fixing bugs. It is not sent anywhere.
- The latest unread chat messages, kept so you can reply. They are kept on the phone.
- In Write: words and the apps where you type them, to suggest words. Password fields are skipped.
- Downloaded voice and AI models (see below).

You can delete all of it at any time: Android Settings > Apps > Invisible OS > Storage > Clear storage. Uninstalling the app also deletes it.

## What leaves the phone
The app has no server of ours. Nothing is sent to the developer. These are the only network calls in the code:

| When | To where | What is sent | What comes back |
|---|---|---|---|
| You ask for the weather | open-meteo.com | Your approximate latitude and longitude (2 decimals) | Weather data |
| You turn on the neural voice (once, Wi-Fi) | github.com (k2-fsa/sherpa-onnx releases) | A normal download request (your IP address is visible to GitHub) | A voice model file |
| You turn on "AI on the phone" (once, Wi-Fi) | huggingface.co | A normal download request | The Gemma model file (2.6 GB). The AI then runs on the phone. Your messages are never sent to it. |
| You use Write with translation, or language detection | Google ML Kit | Only the request for a language model, over Wi-Fi. Translation and language detection run on the phone. The text is not sent. | Language model files |
| You search for a song in the Music item | music.youtube.com | The song name you typed | Search results |

Third-party servers see your IP address like any website. Their privacy policies apply to them. We do not sell or share your data. The app has no ads and no analytics libraries.

When you play music, the app asks the music app already on your phone (for example YouTube Music) to play. That app has its own privacy policy.

## Reading and replying to messages
The app reads notifications only to speak them to you and to send the reply you pick. It sends your reply through the same chat app, using the reply action in the notification. The app does not store a copy outside your phone.

## Children
The app is not made for children under 13 and does not knowingly collect their data.

## Your choices
- Turn off any access in Android settings at any time.
- Clear storage to delete the app data.
- Muted apps are hidden from the app.

## Changes
If this policy changes, the date at the top changes, and the new text is published in the same place.

## Contact
facundoarenas29@gmail.com

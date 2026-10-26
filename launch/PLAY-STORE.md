# Google Play launch notes

Policies change. Re-check each form in Play Console before you submit. This text is ready to paste but is a draft.

## 1. Store listing

**Name (max 30):** Invisible OS: Earbud Control

**Short text (max 80):**
Use your phone with one earbud button and the screen off. Hear it, click it.
(75 characters)

**Category:** Tools. (Alternative: Accessibility is not a Play category. Use Tools.)

**Long text (max 4000):**
Invisible OS lets you use your phone without looking at it.

Put in any earbuds or headset. Turn the screen off. The phone speaks the options one by one. When you hear the one you want, click the earbud button. One button is enough.

This is called auto-scan. It is the same method used in assistive technology for people who can use only one switch. It also helps when your hands are busy or your phone is in your pocket.

What you can do:
- Hear new messages and reply with a ready sentence (WhatsApp, Teams, WeChat). You have a few seconds to cancel any reply.
- Play, pause, and skip music.
- Set timers and alarms.
- Hear the time, battery, weather, next calendar event, and the next navigation turn.
- Keep a to-do list by ear.
- Open any app through a short spoken menu that puts your most used apps first.
- Type with one button using the Write keyboard.
- Find your phone, turn on the torch, change the volume.
- Play audio games made for no screen.

Private by design:
- No microphone. The app never listens.
- No account, no ads, no analytics.
- Your messages and tasks stay on the phone.
- Optional AI and voice models download once and run on the phone.

Setup takes a few minutes. You turn on Button access, Notification access, and the Assistant setting yourself. The app explains why each one is needed.

Works in English. Message text is read in its own language (Spanish, English and more).

Open source.

**Graphics needed:** icon 512x512, feature graphic 1024x500, at least 2 phone screenshots (setup card, settings). Add a short video if you can: a person with the phone in a pocket.
**Contact email:** facundoarenas29@gmail.com
**Privacy policy URL:** host `launch/PRIVACY.md` as a web page (GitHub Pages or a raw GitHub page) and paste the link.

## 2. New personal developer accounts: closed testing

If your developer account is a personal account created after 13 Nov 2023, you must run a closed test before you can apply for production access:
- At least 12 testers opted in.
- For at least 14 days in a row.
- Then apply for production in the Play Console. Google reviews it (about a week).
Plan: recruit 15 to 20 testers (people lose interest). Use the stranger test (STRANGER-TEST.md) people first. Make a Google Group or email list. Ask them to open the app and send feedback. An organization account (with a D-U-N-S number) does not need this rule.
Also: a one-time 25 USD fee, and identity verification (ID, address, phone).

## 3. Policy declarations (ready to paste)

### 3.1 Accessibility API (AccessibilityService)
Play allows the API only for apps that help people with disabilities, or with a clear user-facing reason, and asks for a video and a disclosure. If the app is not an "accessibility tool", you must explain the core use. Be honest.

*Is this app an accessibility tool?* Answer: Yes, for the main use. The app helps people use the phone without looking at it: people who are blind or have low vision, people who cannot use a touch screen, and people whose hands or eyes are busy. It uses single-switch scanning, a known assistive method.

*Why does the app need the Accessibility API?*
"The app turns the one button of a wired or Bluetooth earbud into a controller. Android gives that button to the media app, and the lock screen and some phones block it. The accessibility service key-event filter (`canRequestFilterKeyEvents`) is the only way to receive this button when the screen is off. The user hears the options and clicks to choose. In the optional Write keyboard, the service puts the text the user composed into the focused text field of another app (ACTION_SET_TEXT) and reads nearby visible text as context to suggest words."

*What data does the service read or collect?*
"Button key events. Only when the user opens the Write keyboard: the visible text near the focused field, the app name, and the field type. Password fields are never read. This data stays on the device. It is not sent, shared, or sold. It is not used for ads."

*Does the app take actions for the user?* "Yes, only after the user picks them with the button: it sets text in a field. It does not click, scroll, or gesture on its own."

*Prominent disclosure (shown before the user turns it on, in the app, with Agree and No thanks):*
"Invisible OS uses the Accessibility service so you can control your phone with your earbud button when the screen is off. It receives button presses. If you use the Write keyboard, it also reads the text visible on the screen near the field you type in, and types your text into that field. It does not read password fields. Your data stays on your phone. It is never sold or shared."
TODO in the app: this disclosure must be a screen with an explicit button, before the system settings opens. Check that the setup card shows it.

*Video:* record a 30-60 second video: user turns on the service, the screen is off, the earbud button is used, the Write keyboard fills a field.

*Service description (`a11y_desc` string, shown in Android settings):* "Reads the earbud button (also long press) so Invisible can run actions with the screen off." Add: "Also types text you compose into other apps."

### 3.2 Notification listener
There is no special Play form, but the user data policy applies. Declare in the Data safety form.
Text: "The app reads notifications to speak them to the user, to send replies the user picks through the notification's own reply action, to dismiss a notification, and to mute an app. Private notifications (bank, password, secret, locked-private apps) are spoken only as 'App, new notification'. Notification content stays on the device and is never sent to any server."

### 3.3 PACKAGE_USAGE_STATS
Play restricts this permission to apps whose core purpose needs it. Prepare for a form.
Text: "The app uses usage access to count how many times each app was opened in the last 30 days. It sorts the spoken menu so the apps the user opens most are heard first. In a menu you hear one item at a time, order is the main thing that saves time. The app does not read screen time, content, or anything inside other apps. The counts stay on the device."
Risk: reviewers may say this is not core. Fallback: remove the permission and sort by the user's own picks inside Invisible OS only (the code already weights own picks). See the risks table.

### 3.4 Voice interaction service (BIND_VOICE_INTERACTION)
Text: "The app is an optional digital assistant only so a long press on the earbud button works on the lock screen. When invoked, it shows no UI and does not listen. It has no microphone permission. The recognition service is an empty stub required by Android to accept the assistant setting."
Note: Android says the app must handle the assist gesture. Be ready to explain why a recognition service exists with no function.

### 3.5 Foreground service type: mediaPlayback
Text: "A foreground service of type mediaPlayback keeps the app alive with the screen off so it can read the earbud button and speak the menu through the media session and audio output. It plays spoken menu prompts, tones and optional audio games. It shows a persistent notification while active. The user starts it by turning on Button access."
Video: show the notification and the audio output with the screen off.
Check: Play wants the service to really play media. The app speaks and plays sounds, so it fits. Do not use it just to stay alive.

### 3.6 Other forms
- **Permissions: location.** Coarse location only, foreground, for the weather. Show a request before. Fine.
- **READ_CALENDAR.** For next event. Fine, with a purpose line.
- **Data safety form.** Collected: none sent to us. Select "no data collected" only if you are sure. Third party downloads (GitHub, Hugging Face, Google ML Kit, open-meteo, YouTube Music search) get IP and approximate location (weather). Declare location shared with open-meteo to be safe. Encrypted in transit: yes (https). Delete request: data is on device only.
- **Target audience:** 18 and over (or 13+). Not for children.
- **Content rating:** answer the questionnaire. No violence, no user content shared.
- **Ads:** none. **App access:** reviewers cannot see the screen-off use. Give instructions and a demo video.
- **Financial features, health:** none.

## 4. Rejection risks and how to lower each

| # | Risk | Why | How to lower it |
|---|---|---|---|
| 1 | Accessibility API: "not an accessibility tool" or no prominent disclosure | Play is strict. Many apps were removed. The service also reads screen text in Write. | In-app disclosure screen with Agree before system settings. Video. Narrow the config: remove `typeAllMask` events and read the window only inside Write (no event stream is used: `onAccessibilityEvent` is empty). Keep a one-sentence use: "receive the earbud button". Say clearly it serves blind and low-vision users. Consider removing `canRetrieveWindowContent` if Write can work without it. |
| 2 | Notification listener reads messages from other apps | Sensitive user data. Reviewers may see "reads private messages". | Data safety form is honest. Private apps are read only as a title. Say that nothing leaves the device. Link the privacy policy in the app. |
| 3 | PACKAGE_USAGE_STATS | Needs a declaration; core purpose test. Also it cannot be granted by a normal dialog (user goes to a settings page). | Make it optional. Show the use in the form. If refused, remove it and sort by own picks. |
| 4 | Voice interaction service with an empty recognition service | Looks like a fake assistant. | Explain in text. Keep the stub tiny. Test that the app still works if the user does not set it as assistant. |
| 5 | Dev receiver `AiTestReceiver` is exported (any app can send it commands) | Security review and a real hole. | Remove it from release builds or set `exported="false"` and keep it in the debug manifest only. Do this before upload. |
| 6 | Foreground service misuse | mediaPlayback must play media. | Show real audio. Do not start it when nothing is needed. |
| 7 | Policy "Device and Network Abuse" and "Misleading": the app reads WhatsApp and replies | Not a WhatsApp rule, but WhatsApp may dislike it. Reply uses the public notification reply action, so it is allowed. | Do not say "WhatsApp" in the app name or icon. Mention it only as "chat apps". Add a line that the app is not affiliated. |
| 8 | Large model download (2.6 GB) | Policy on downloaded executable code: the model is data, not code. Native code in the APK is fine. | Say the model is data. Keep it opt-in and Wi-Fi only. |
| 9 | Closed test not completed (12 testers x 14 days) | Blocks production. | Start recruiting now. Use the stranger test group. |
| 10 | Privacy policy missing or not matching the app | Common rejection. | Host PRIVACY.md. Keep it equal to the code. Update when network calls change. |
| 11 | Target API and 64-bit | `targetSdk` is 36 (good). Only arm64 is shipped, which is allowed (64-bit). | Re-check the current target-API rule at upload. Upload an AAB (app bundle), not an APK. The app is large with models; the APK has no model inside. |
| 12 | `allowBackup` is not set (default true) | Private data (tasks, typed words) could go to cloud backup. | Set `allowBackup="false"` or add backup rules. Update the privacy policy to match. |

## 5. Alternatives to Google Play

### 5.1 GitHub Releases APK (easiest, do this first)
- Build a signed release APK (your own key, not the debug key). Upload to GitHub Releases. Put SHA-256 in the notes.
- Users need "install unknown apps". The Android 13+ "restricted settings" rule blocks Accessibility and Notification access for apps installed from outside a store: the user must open App info > menu > Allow restricted settings. Put this in the setup guide (this is a real friction point; test it with strangers).
- No review, no 12 testers, no declarations. Updates: use Obtainium (reads GitHub Releases) so users get updates.

### 5.2 F-Droid
Good fit for an open source, no-tracking app. Possible blockers from the libs in `app/os/build.gradle.kts`:
| Library | Problem for F-Droid |
|---|---|
| `libs/sherpa-onnx-1.12.11.aar` (prebuilt binary in the repo) | F-Droid builds from source. A prebuilt AAR is not allowed. Fix: build sherpa-onnx from source in the F-Droid recipe, or download it by a verified step. Likely a blocker until fixed. |
| `com.google.mlkit:language-id`, `com.google.mlkit:translate` | Google proprietary libraries that need Google Play Services components. F-Droid marks them "NonFreeDep" and normally rejects the app (or allows it only with the anti-feature label). Likely a blocker. Fix: put ML Kit behind a flavor, make an `fdroid` flavor without it (use `MessageLogic.guessLang` which already exists as a fallback, no translation). |
| `com.google.ai.edge.litertlm:litertlm-android` | Apache-2.0, but check that it has no Google Play Services dependency, and that F-Droid can build it from source. Uncertain. Make it a flavor too. |
| commons-compress / io / lang3 jars in `libs/` | Free, but prebuilt jars: better to use Maven coordinates. |
| Downloads from Hugging Face | Model download is user-started. Allowed, but F-Droid may list "non-free network service" if the model is non-free. Gemma has its own terms; list it as an anti-feature or drop it from that flavor. |
Plan: make a product flavor `foss` with no ML Kit, no LiteRT, and sherpa built from source. Then open a request in `fdroiddata`. Takes weeks.

### 5.3 Other
- IzzyOnDroid repo: takes prebuilt APKs from GitHub Releases, lighter rules than F-Droid. Good second step.
- Amazon Appstore and Samsung Galaxy Store: possible. Lower value.
- Direct APK from a web page, same as GitHub Releases.

## 6. Recommended order
1. GitHub Releases + Obtainium now. Run the stranger test.
2. Fix risks 5 and 12, add the disclosure screen.
3. Host the privacy policy. Start the closed test (12+ testers, 14 days) on Play.
4. Apply for production with the declarations above.
5. Later: `foss` flavor for IzzyOnDroid and F-Droid.

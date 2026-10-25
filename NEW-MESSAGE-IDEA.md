# Idea: send a NEW WhatsApp message with the screen off (not built, waiting for user's yes)

Date: 2026-10-25. Status: researched, not built.

## Problem
The controller can only REPLY to a chat that has a notification (WhatsApp gives a reply button only on arrived messages).
Starting a new chat needs WhatsApp open = screen on.

## Options found (research)
1. Accessibility service (Tasker method): opens chat with text filled, taps Send. Needs unlocked phone, wakes screen, breaks on WhatsApp updates. Not screen-off.
2. Google Assistant: unreliable now, often asks to unlock. No.
3. Xiaomi "USB debugging (Security settings)" + Shizuku: more control, but needs Xiaomi account sign-in (SIM need unclear) and heavy setup. Later maybe.
4. Linked device with Baileys in Termux (recommended): phone acts as a second WhatsApp device (like WhatsApp Web). Fully headless.

## Recommended design (option 4)
- Termux runs a small Node.js script with Baileys, linked once with a QR code.
- Connects ONLY when the user sends (a few seconds), then disconnects: almost no battery.
- New menu item in Messages: "New message". Pick a contact by ear from the 5 most recent chats, then compose with the same small questions (new-message phrases: On my way, Where are you, Call me, Running late, Good morning).
- App talks to the script on localhost.

## Risk
Baileys is unofficial; WhatsApp does not allow it. Accounts can be banned, mostly for spam. A few personal messages is low risk, not zero. A ban hits the real number. Mitigation: low volume, only people already chatting, no bulk.

## Sources
- https://www.termuxgenius.com/2026/09/how-to-use-termux-to-automate-whatsapp.html
- https://blueticks.co/blog/schedule-whatsapp-message-android
- https://punprime.com/shizuku-vs-wireless-adb/

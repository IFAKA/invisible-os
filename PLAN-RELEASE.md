# Public release plan

Goal: a stranger with any Android phone (10+) and any earbuds installs Invisible OS, sets it up without a computer,
and does one useful thing by ear in under 2 minutes, without getting stuck. Audit 2026-11-01.

## Criteria (each has a check)
1. Nothing opens or starts without a press. Check: Flow tests `*NoAutoOpen*`, trace has no `wake` without a `gesture`.
2. Setup works with no adb on Android 13+ sideload (restricted settings) and on Xiaomi/Samsung battery killers.
   Check: fresh install on the phone with `pm revoke`/settings cleared, timed from the trace, under 2 min, no stuck step.
3. A stranger's menu shows only things a stranger understands. Check: default HUB has no Claude/Laptop until paired.
4. Release build: signed, versionCode rises on its own, privacy policy URL, honest README. Check: `tools/release.sh` output.
5. Crashes reach the owner when the user agrees: "Send log" action. Check: unit test of the log bundle.
6. CI runs the three checks on every push. Check: workflow file runs green.

## Steps
- [x] Auto-open bugs (practice offer, menu after practice). lane one-design.
- [x] Release basics: hide owner-only items, versionCode, README privacy claim, privacy page, CI, Send log. lane release-basics.
- [x] Leaving: a hold that opened the letters left a press open in the menu, so click then hold reopened them (fixed cd2a947c, checked on the phone).
- [ ] First hint: one rule per moment; a hold during the long hint does nothing today.
- [ ] Strangers still hear "Laptop" in the home tree (not a hub key; find where it comes from and hide it until paired).
- [ ] Stranger setup: restricted-settings step, battery step per brand, "hold does not work" fallback.
- [ ] Channel decision (owner): direct APK first, or Play Store (needs SEND_SMS and READ_CALL_LOG removed, declarations).
- [ ] Fresh-install test on the phone, timed.

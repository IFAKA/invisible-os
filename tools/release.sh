#!/bin/bash
# Build the signed release APK. Does NOT publish. Usage: tools/release.sh VERSION [--draft-notes] [--skip-checks]
# Key: ~/.config/invisible-release/{keystore.jks,keystore.properties} (outside the repo, never commit).
# Output: dist/invisible-os-VERSION.apk + dist/invisible-os.apk. Log: app/logs/release.log
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-}"
[ -z "$VERSION" ] && { echo "usage: tools/release.sh VERSION [--draft-notes] [--skip-checks]"; exit 2; }
shift
NOTES=0; CHECKS=1
for a in "$@"; do case "$a" in --draft-notes) NOTES=1;; --skip-checks) CHECKS=0;; esac; done

notes() { cat <<'TXT'
Invisible OS: control your Android phone with one earbud button, screen off.
The phone speaks the options. One click picks.

What it never does
- It never listens. There is no microphone permission. Input is only the button.
- No account, no sign-up, no ads, no tracking.
- It works offline. The internet is used only if you ask for an optional on-phone voice or AI model download.

Install
1. Scan the QR code in the README with your phone camera, or open the link on your phone, and install.
2. Android warns about installing from outside the Play Store. That is normal for open-source apps.
3. Open Invisible OS, allow Button access, then hold your earbud button.

Use Claude Code in tmux on a Mac? Once per Mac user, in Terminal:
  curl -fsSL https://raw.githubusercontent.com/IFAKA/invisible-os/master/install.sh | sh
Then scan the QR code it shows. Remove it all with: invisible uninstall

Permissions and why
- Button access (accessibility service): to hear the earbud button when the screen is off.
- Notification access: to read and reply to new messages.
- Contacts, Phone, Call log, SMS: to say who calls or writes, and to call or reply when you choose it.
- Calendar: to read your day.
- Location: for the Compass and Where am I.
- Usage access (optional): to put the apps you use most first.
- Show notifications: to keep the service alive and show status.
- Run at boot, foreground service: so the button works after a restart.
Each one is asked only when its feature is set up, and you can say no.
TXT
}

if [ "$NOTES" = 1 ]; then notes; exit 0; fi

mkdir -p "$ROOT/app/logs" "$ROOT/dist"
LOG="$ROOT/app/logs/release.log"
: > "$LOG"
fail() { echo "FAIL: $1 (see $LOG)"; exit 1; }

KDIR="$HOME/.config/invisible-release"
KS="$KDIR/keystore.jks"; KP="$KDIR/keystore.properties"
if [ ! -f "$KS" ] || [ ! -f "$KP" ]; then
  mkdir -p "$KDIR" && chmod 700 "$KDIR"
  PW="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"
  keytool -genkeypair -v -keystore "$KS" -storetype JKS -alias invisible -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "$PW" -keypass "$PW" -dname "CN=Invisible OS, O=IFAKA" >>"$LOG" 2>&1 || fail "keytool"
  printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=invisible\nkeyPassword=%s\n' "$KS" "$PW" "$PW" > "$KP"
  chmod 600 "$KP" "$KS"
  echo "NEW KEY at $KDIR: back it up now (lose it and nobody can update the app)."
fi

cd "$ROOT/app" || exit 1
if [ "$CHECKS" = 1 ]; then
  ./gradlew testDebugUnitTest --offline -q >>"$LOG" 2>&1 || fail "unit tests"
  ./gradlew checkVoiceKit --offline -q >>"$LOG" 2>&1 || fail "checkVoiceKit"
  ./gradlew assembleDebug --offline -q >>"$LOG" 2>&1 || fail "assembleDebug"
fi
./gradlew :os:assembleRelease -PappVersion="$VERSION" --offline -q >>"$LOG" 2>&1 || fail "assembleRelease"
SRC="$ROOT/app/os/build/outputs/apk/release/os-release.apk"
[ -f "$SRC" ] || SRC="$(ls "$ROOT"/app/os/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
[ -f "$SRC" ] || fail "no release apk found"
cp "$SRC" "$ROOT/dist/invisible-os-$VERSION.apk" && cp "$SRC" "$ROOT/dist/invisible-os.apk"
SHA="$(shasum -a 256 "$ROOT/dist/invisible-os.apk" | cut -d' ' -f1)"
notes > "$ROOT/dist/notes-$VERSION.md"
echo "OK $VERSION sha256=$SHA dist/invisible-os-$VERSION.apk (+ invisible-os.apk) log=app/logs/release.log"
echo "Publish (you run it): gh release create v$VERSION dist/invisible-os-$VERSION.apk dist/invisible-os.apk --repo IFAKA/invisible-os --title \"Invisible OS $VERSION\" --notes-file dist/notes-$VERSION.md"

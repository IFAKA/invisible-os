#!/bin/sh
# Build the signed release APK and copy it to dist/invisible-os-<version>.apk
set -e
cd "$(dirname "$0")"
[ -f keystore.properties ] || { echo "app/keystore.properties missing (copy from ~/.android-keys/invisible-os-keystore.properties)"; exit 1; }
./gradlew assembleRelease --offline -q
V=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' os/build.gradle.kts | head -1)
mkdir -p ../dist
OUT=../dist/invisible-os-$V.apk
cp os/build/outputs/apk/release/os-release.apk "$OUT"
echo "$(cd ../dist && pwd)/invisible-os-$V.apk $(du -h "$OUT" | cut -f1)"

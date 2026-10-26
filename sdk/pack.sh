#!/bin/bash
# Make the zip we send to an outside developer: the starter + the published framework (sdk-repo) + the guide.
# Output: app/build/invisible-sdk-0.1.0.zip   Needs the framework published first (see sdk/GUIDE.md, "For us").
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO="$ROOT/app/build/sdk-repo"
[ -d "$REPO/com/faka/invisible/oskit/0.1.0" ] || { echo "publish first: cd app && ./gradlew :oskit:publishAllPublicationsToSdkRepoRepository --offline" >&2; exit 1; }
OUT="$ROOT/app/build/invisible-sdk"; rm -rf "$OUT"; mkdir -p "$OUT/invisible-sdk"
cp -R "$REPO" "$OUT/invisible-sdk/sdk-repo"
mkdir "$OUT/invisible-sdk/starter"
(cd "$ROOT/sdk/starter" && tar --exclude=build --exclude=.gradle --exclude=.kotlin --exclude=local.properties -cf - .) | (cd "$OUT/invisible-sdk/starter" && tar -xf -)
cp "$ROOT/sdk/GUIDE.md" "$OUT/invisible-sdk/"
(cd "$OUT" && rm -f ../invisible-sdk-0.1.0.zip && zip -qr ../invisible-sdk-0.1.0.zip invisible-sdk)
echo "made app/build/invisible-sdk-0.1.0.zip (starter + sdk-repo + GUIDE.md)"

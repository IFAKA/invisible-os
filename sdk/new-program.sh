#!/bin/bash
# Add a program to the OS.
#   sdk/new-program.sh NAME                 new program from the starter (sdk/starter, "Counter")
#   sdk/new-program.sh NAME --from PATH     take a finished starter-based folder (what an outside developer sends us)
#   sdk/new-program.sh NAME --remove        undo it (module, include, registry line, rules line)
# NAME is lowercase letters only (for example "dice"). It becomes module app/programs/NAME, package com.faka.invisible.NAME,
# and a line in the Games list. See sdk/GUIDE.md.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
NAME="${1:-}"; MODE="new"; FROM="$ROOT/sdk/starter"
[[ "$NAME" =~ ^[a-z]{2,20}$ ]] || { echo "usage: sdk/new-program.sh NAME [--from PATH | --remove]   (NAME: 2-20 lowercase letters)" >&2; exit 2; }
shift
case "${1:-}" in
  --from) MODE="import"; FROM="$(cd "${2:?--from needs a folder}" && pwd)";;
  --remove) MODE="remove";;
  "") ;;
  *) echo "unknown option $1" >&2; exit 2;;
esac
CAP="$(echo "${NAME:0:1}" | tr a-z A-Z)${NAME:1}"
DEST="$APP/programs/$NAME"
SETTINGS="$APP/settings.gradle.kts"; OSBUILD="$APP/os/build.gradle.kts"
REG="$APP/os/src/main/java/com/faka/invisible/Programs.kt"; RULES="$APP/voicekit-rules.tsv"

if [ "$MODE" = remove ]; then
  rm -rf "$DEST"
  sed -i '' "s/, \":programs:$NAME\"//" "$SETTINGS"
  sed -i '' "/project(\":programs:$NAME\")/d" "$OSBUILD"
  sed -i '' "/^import com.faka.invisible.$NAME\./d; /${CAP}Entries\.entry,/d" "$REG"
  sed -i '' "/^allow	${CAP}Sentences.kt	/d" "$RULES"
  echo "removed program $NAME"; exit 0
fi

[ -e "$DEST" ] && { echo "app/programs/$NAME exists already" >&2; exit 1; }
[ -d "$FROM/src/main/java/com/faka/invisible" ] || { echo "$FROM is not a starter-based folder (no src/main/java/com/faka/invisible)" >&2; exit 1; }
OLD="$(ls "$FROM/src/main/java/com/faka/invisible" | head -1)"
[ -n "$OLD" ] && [ "$(ls "$FROM/src/main/java/com/faka/invisible" | wc -l)" -eq 1 ] || { echo "expected one package folder under src/main/java/com/faka/invisible" >&2; exit 1; }
OLDCAP="$(echo "${OLD:0:1}" | tr a-z A-Z)${OLD:1}"
[ -f "$FROM/src/main/java/com/faka/invisible/$OLD/${OLDCAP}Sentences.kt" ] || { echo "missing ${OLDCAP}Sentences.kt (fixed sentences live in <Name>Sentences.kt)" >&2; exit 1; }
grep -q "${OLDCAP}Entries" -r "$FROM/src/main" || { echo "missing object ${OLDCAP}Entries with val entry (the OS registry line)" >&2; exit 1; }

# 1. the module: sources renamed, a fresh build file (a program depends on :oskit only)
mkdir -p "$DEST"
for part in main test; do
  [ -d "$FROM/src/$part" ] || continue
  mkdir -p "$DEST/src/$part"
  (cd "$FROM/src/$part" && find . -type f -name '*.kt') | while read -r f; do
    nf="$(echo "$f" | sed "s#/invisible/$OLD/#/invisible/$NAME/#; s#/$OLDCAP\([A-Za-z]*\.kt\)\$#/$CAP\1#")"
    mkdir -p "$DEST/src/$part/$(dirname "$nf")"
    perl -pe "s/com\.faka\.invisible\.$OLD\b/com.faka.invisible.$NAME/g; s/programs\.$OLD\b/programs.$NAME/g; s/games:$OLD\b/games:$NAME/g; s/hub:$OLD\b/hub:$NAME/g; s/$OLDCAP/$CAP/g" "$FROM/src/$part/$f" > "$DEST/src/$part/$nf"
  done
done
cat > "$DEST/build.gradle.kts" <<B
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}
// $CAP, a program. A program depends only on :oskit.
android {
    namespace = "com.faka.invisible.programs.$NAME"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(project(":oskit"))
    testImplementation("junit:junit:4.13.2")
}
B

# 2. the build: include, dependency of the OS, the Sentences file may make fixed Phrases
sed -i '' "s/, \":adapters\")/, \":programs:$NAME\", \":adapters\")/" "$SETTINGS"
sed -i '' "s/^\(    implementation(project(\":programs:write\")).*\)\$/\1\\
    implementation(project(\":programs:$NAME\"))/" "$OSBUILD"
printf 'allow\t%sSentences.kt\tphrase-make\t%s'"'"'s own fixed sentences (program-owned)\n' "$CAP" "$CAP" >> "$RULES"

# 3. the registry: one import, one line in the Games list
sed -i '' "s/^import com.faka.invisible.write.WriteEntries\$/import com.faka.invisible.$NAME.${CAP}Entries\\
import com.faka.invisible.write.WriteEntries/" "$REG"
sed -i '' "s/^\(        WriteEntries.sum,\)\$/\1\\
        ${CAP}Entries.entry,/" "$REG"

grep -q "programs:$NAME" "$SETTINGS" && grep -q "programs:$NAME" "$OSBUILD" && grep -q "${CAP}Entries.entry" "$REG" || { echo "could not edit the registry files; run with --remove and tell us" >&2; exit 1; }
echo "added program $NAME: app/programs/$NAME, Games list (Programs.kt); check: cd app && ./gradlew :programs:$NAME:test checkVoiceKit assembleDebug --offline"

#!/bin/bash
# release_audit.sh: assert the release APK contains NO debug/test classes
# (SolverBot, TestHooks, fakes, journeys) and report its size. Minification
# (R8 full mode) is on for release; this is the belt-and-braces check on top
# of the source-set construction (androidTest/src-debug are never compiled
# into release variants).
# Usage: release_audit.sh [apk_path]
set -euo pipefail
APK=${1:-app/build/outputs/apk/release/app-release.apk}
DEXDUMP=/opt/homebrew/share/android-commandlinetools/build-tools/36.0.0/dexdump

[ -f "$APK" ] || { echo "[release] FAIL: $APK not found"; exit 1; }
SIZE=$(stat -f%z "$APK")
echo "[release] APK size: $SIZE bytes ($((SIZE / 1024)) KiB)"

echo "[release] dumping class list from $(unzip -l "$APK" 'classes*.dex' | grep -c 'classes.*dex') dex files"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -o -q "$APK" 'classes*.dex' -d "$TMP"
find "$TMP" -name 'classes*.dex' -print0 | xargs -0 "$DEXDUMP" 2>/dev/null \
  | grep -oE 'L[^(;]+;' | sort -u > "$TMP/classes.txt"
TOTAL=$(wc -l < "$TMP/classes.txt" | tr -d ' ')
echo "[release] $TOTAL classes in release APK"

FORBIDDEN=(
  "Lcom/linguamod/app/solver/"
  "Lcom/linguamod/app/fakes/"
  "Lcom/linguamod/app/journeys/"
  "Lcom/linguamod/app/debug/TestHooks"
  "SolverBot"
  "FakeTtsGateway"
  "FakeSpeechRecognizerGateway"
  "JourneyTest"
  "TestRunner"
)
FAIL=0
for pat in "${FORBIDDEN[@]}"; do
  if grep -qF -- "$pat" "$TMP/classes.txt"; then
    echo "[release] FAIL: forbidden class pattern present: $pat"
    grep -F -- "$pat" "$TMP/classes.txt" | head -5
    FAIL=1
  fi
done
if [ "$FAIL" != "0" ]; then exit 1; fi
echo "[release] PASS: no debug/test classes in release APK"

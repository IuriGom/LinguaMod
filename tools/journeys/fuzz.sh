#!/bin/bash
# Generate the fuzz corpus and run journey_fuzz_plugins on the device.
# Usage: fuzz.sh [device_serial] [count]
set -euo pipefail
ADB=${ADB:-adb}
PKG=com.linguamod.app
SERIAL=${1:-}
COUNT=${2:-220}
DEV=(${SERIAL:+-s $SERIAL})

cd "$(dirname "$0")/.."
python3 fuzz/generate_fuzz.py fuzz/out "$COUNT"

REMOTE=/sdcard/Android/data/$PKG/files/fuzz-corpus
echo "[fuzz] pushing corpus to device"
$ADB "${DEV[@]}" shell rm -rf "$REMOTE" 2>/dev/null || true
$ADB "${DEV[@]}" shell mkdir -p "$REMOTE"
$ADB "${DEV[@]}" push fuzz/out/. "$REMOTE" > /dev/null

echo "[fuzz] running journey_fuzz_plugins"
$ADB "${DEV[@]}" shell am instrument -w -e class \
  com.linguamod.app.journeys.FuzzPluginsJourneyTest \
  $PKG.test/com.linguamod.app.LinguaModTestRunner

echo "[fuzz] checking logcat for crashes"
if $ADB "${DEV[@]}" logcat -d | grep -E "FATAL EXCEPTION.*$PKG"; then
  echo "[fuzz] FAIL: crash detected"; exit 1
fi
echo "[fuzz] PASS"

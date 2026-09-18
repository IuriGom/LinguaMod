#!/bin/bash
# journey_hostile_display: smallest-screen override (720x1560 @ 280dpi) +
# largest font (1.3) on emulator-5554 — no small AVD exists (avdmanager list:
# only pixel_6-class AVDs), so the display is overridden per the gauntlet
# instructions. Runs a critical-flow instrumented journey (path -> unit 1 ->
# full lesson via Solver Bot) under the hostile config: if any critical
# button were clipped, the semantics asserts/taps would fail. Restores the
# original display afterwards.
# Usage: hostile_display.sh [device_serial]
set -euo pipefail
ADB=${ADB:-adb}
PKG=com.linguamod.app
SERIAL=${1:-}
DEV=(${SERIAL:+-s $SERIAL})

ORIG_SIZE=$($ADB "${DEV[@]}" shell wm size | awk -F': ' '{print $2}' | tr -d '[:space:]')
ORIG_DENSITY=$($ADB "${DEV[@]}" shell wm density | awk -F': ' '{print $2}' | tr -d '[:space:]')
ORIG_FONT=$($ADB "${DEV[@]}" shell settings get system font_scale | tr -d '[:space:]')

cleanup() {
  echo "[hostile] restoring display ($ORIG_SIZE @ ${ORIG_DENSITY}dpi, font $ORIG_FONT)"
  $ADB "${DEV[@]}" shell wm size "$ORIG_SIZE" || true
  $ADB "${DEV[@]}" shell wm density "$ORIG_DENSITY" || true
  $ADB "${DEV[@]}" shell settings put system font_scale "$ORIG_FONT" || true
}
trap cleanup EXIT

echo "[hostile] original display: $ORIG_SIZE @ ${ORIG_DENSITY}dpi font=$ORIG_FONT"
echo "[hostile] applying 720x1560 @ 280dpi + font_scale 1.3"
$ADB "${DEV[@]}" shell wm size 720x1560
$ADB "${DEV[@]}" shell wm density 280
$ADB "${DEV[@]}" shell settings put system font_scale 1.3
$ADB "${DEV[@]}" shell am force-stop $PKG
sleep 2

echo "[hostile] running critical-flow journey under hostile display"
$ADB "${DEV[@]}" shell am instrument -w -e class \
  com.linguamod.app.journeys.HostileDeviceJourneyTest#hostile_small_screen_large_font_flow \
  $PKG.test/com.linguamod.app.LinguaModTestRunner

echo "[hostile] PASS (no clipped critical buttons at 720x1560/280dpi/font1.3)"

#!/bin/bash
# journey_airplane: radios off -> full lesson playable end-to-end.
# Usage: airplane.sh [device_serial]
set -euo pipefail
ADB=${ADB:-adb}
SERIAL=${1:-}
DEV=(${SERIAL:+-s $SERIAL})

echo "[airplane] disabling radios"
$ADB "${DEV[@]}" shell svc wifi disable
$ADB "${DEV[@]}" shell svc data disable
$ADB "${DEV[@]}" shell settings put global airplane_mode_on 1 2>/dev/null || true

cleanup() {
  echo "[airplane] re-enabling radios"
  $ADB "${DEV[@]}" shell svc wifi enable || true
  $ADB "${DEV[@]}" shell svc data enable || true
  $ADB "${DEV[@]}" shell settings put global airplane_mode_on 0 2>/dev/null || true
}
trap cleanup EXIT

echo "[airplane] running journey_airplane instrumented test"
$ADB "${DEV[@]}" shell am instrument -w -e class \
  com.linguamod.app.journeys.RotationAirplaneJourneyTest#journey_airplane \
  com.linguamod.app.test/com.linguamod.app.LinguaModTestRunner

echo "[airplane] checking for crashes"
if $ADB "${DEV[@]}" logcat -d | grep -E "FATAL EXCEPTION.*com.linguamod" ; then
  echo "[airplane] FAIL: crash detected"; exit 1
fi
echo "[airplane] PASS"

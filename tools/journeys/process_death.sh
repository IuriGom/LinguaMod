#!/bin/bash
# journey_process_death: background app mid-lesson, am kill, relaunch -> resume gracefully.
# Usage: process_death.sh [device_serial]
set -euo pipefail
ADB=${ADB:-adb}
PKG=com.linguamod.app
SERIAL=${1:-}
DEV=(${SERIAL:+-s $SERIAL})

$ADB "${DEV[@]}" logcat -c

echo "[procdeath] launching app"
$ADB "${DEV[@]}" shell am start -n $PKG/.MainActivity > /dev/null
sleep 3

echo "[procdeath] navigating to unit 1, lesson 1 (fixed-AVD coordinates)"
# pixel_6-class 1080x2400: unit node 1 then lesson row 1
$ADB "${DEV[@]}" shell input tap 200 700 > /dev/null
sleep 1.5
$ADB "${DEV[@]}" shell input tap 540 500 > /dev/null
sleep 2

echo "[procdeath] backgrounding and killing"
$ADB "${DEV[@]}" shell input keyevent KEYCODE_HOME
sleep 1
$ADB "${DEV[@]}" shell am kill $PKG
sleep 1

echo "[procdeath] relaunching"
$ADB "${DEV[@]}" shell am start -n $PKG/.MainActivity > /dev/null
sleep 4

PID=$($ADB "${DEV[@]}" shell pidof $PKG | tr -d '[:space:]')
if [ -z "$PID" ]; then
  echo "[procdeath] FAIL: app did not relaunch"; exit 1
fi
if $ADB "${DEV[@]}" logcat -d | grep -E "FATAL EXCEPTION.*$PKG"; then
  echo "[procdeath] FAIL: crash detected"; exit 1
fi
echo "[procdeath] PASS (pid $PID)"

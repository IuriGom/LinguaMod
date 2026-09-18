#!/bin/bash
# monkey stress: default 20000 events, zero crashes/ANRs allowed.
# Usage: monkey.sh [events] [device_serial]
set -euo pipefail
ADB=${ADB:-adb}
PKG=com.linguamod.app
EVENTS=${1:-20000}
SERIAL=${2:-}
DEV=(${SERIAL:+-s $SERIAL})

$ADB "${DEV[@]}" logcat -c 2>/dev/null || $ADB "${DEV[@]}" logcat -c -b all 2>/dev/null || true
$ADB "${DEV[@]}" shell monkey -p $PKG --pct-syskeys 0 --ignore-security-exceptions "$EVENTS" > /tmp/monkey_out.txt 2>&1 || true
tail -3 /tmp/monkey_out.txt

CRASHES=$($ADB "${DEV[@]}" logcat -d | grep -cE "FATAL EXCEPTION.*$PKG|ANR in $PKG" || true)
if [ "$CRASHES" != "0" ]; then
  echo "[monkey] FAIL: $CRASHES crash/ANR lines"; exit 1
fi
echo "[monkey] PASS ($EVENTS events, zero crashes/ANRs)"

#!/bin/bash
# run_full_suite.sh: run EVERY journey/test class on a device via am
# instrument (no Gradle lock, so both AVDs can run concurrently), capturing
# per-test started/finished/failed lines to a log for the gauntlet record.
# Usage: run_full_suite.sh <device_serial> <log_file>
set -euo pipefail
ADB=${ADB:-adb}
PKG=com.linguamod.app
SERIAL=$1
LOG=$2

$ADB -s "$SERIAL" logcat -c 2>/dev/null || true
$ADB -s "$SERIAL" shell am force-stop "$PKG.test" || true
$ADB -s "$SERIAL" shell am force-stop $PKG || true

$ADB -s "$SERIAL" shell am instrument -w \
  "$PKG.test/com.linguamod.app.LinguaModTestRunner" > "$LOG" 2>&1 || true

grep -E "run finished" "$LOG" | tail -1
FAILED=$(grep -cE "^.*TestRunner: failed:" "$LOG" || true)
echo "failed lines: $FAILED"
grep -E "TestRunner: failed:" "$LOG" | sed 's/^.*TestRunner: //' || true

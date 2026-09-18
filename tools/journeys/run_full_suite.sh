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

# failure stacks and per-class errors appear on stdout as "Error in <test>:"
ERRORS=$(grep -c "^Error in " "$LOG" || true)
echo "errors: $ERRORS"
grep "^Error in " "$LOG" | sed 's/:.*//' || true
tail -1 "$LOG"

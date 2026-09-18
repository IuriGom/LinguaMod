#!/bin/bash
# cold_start.sh: measure cold start (am start -W TotalTime) N times on a
# device; prints each reading + the median. Budget: < 2500 ms on emulator-5554.
# Usage: cold_start.sh [device_serial] [runs]
set -euo pipefail
ADB=${ADB:-adb}
PKG=com.linguamod.app
SERIAL=${1:-}
RUNS=${2:-5}
DEV=(${SERIAL:+-s $SERIAL})

$ADB "${DEV[@]}" shell am force-stop $PKG
sleep 2
# warm the package once so file cache state is realistic, then force-stop
$ADB "${DEV[@]}" shell am start -W -n $PKG/.MainActivity > /dev/null 2>&1 || true
$ADB "${DEV[@]}" shell am force-stop $PKG
sleep 2

TIMES=()
for i in $(seq 1 "$RUNS"); do
  T=$($ADB "${DEV[@]}" shell am start -W -n $PKG/.MainActivity 2>/dev/null | awk -F': ' '/TotalTime/{print $2}' | tr -d '[:space:]')
  TIMES+=("$T")
  echo "[coldstart] run $i: ${T}ms"
  $ADB "${DEV[@]}" shell am force-stop $PKG
  sleep 2
done
MEDIAN=$(printf '%s\n' "${TIMES[@]}" | sort -n | awk '{a[NR]=$1} END{print a[int((NR+1)/2)]}')
echo "[coldstart] median: ${MEDIAN}ms (budget 2500ms)"

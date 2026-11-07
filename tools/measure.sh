#!/bin/bash
# Same remote presses on the app now on screen, then its memory, CPU and dropped frames.
# Usage: ANDROID_SERIAL=<tv ip>:5555 tools/measure.sh <package>   (local.lampanative or top.rootu.lampa; open it on its
# home screen first)
set -euo pipefail
: "${ANDROID_SERIAL:?ANDROID_SERIAL=<tv ip>:5555}"
export ANDROID_SERIAL
PKG=$1
top=$(mktemp)
key() { adb shell input keyevent "$1"; sleep 0.25; }

# Lampa draws through the WebView renderer, a separate process; count it with the app.
PIDS=$(adb shell pidof "$PKG" | tr -d '\r')
[ "$PKG" = top.rootu.lampa ] && PIDS="$PIDS $(adb shell ps -A -o PID,NAME | awk '/sandboxed_process/ {print $1}' | tr '\n' ' ')"
echo "pids: $PIDS"

adb shell dumpsys gfxinfo "$PKG" reset > /dev/null
adb shell top -b -d 1 -n 20 -p "$(echo "$PIDS" | xargs | tr ' ' ',')" > "$top" &
TOP=$!
for _ in 1 2 3; do
    for _ in $(seq 12); do key KEYCODE_DPAD_RIGHT; done
    key KEYCODE_DPAD_DOWN
done
wait $TOP

echo "--- CPU while scrolling (avg %, 400% = all 4 cores)"
awk -v pids="$PIDS" 'BEGIN { n = split(pids, p, " "); for (i = 1; i <= n; i++) want[p[i]] = 1 }
     $1 in want { sum[$NF] += $9; cnt[$NF]++ }
     END { for (k in sum) printf "  %-60s %5.1f\n", k, sum[k] / 20 }' "$top"
rm -f "$top"
echo "--- frames"
adb shell dumpsys gfxinfo "$PKG" | grep -E 'Total frames|Janky frames|50th|90th|99th' | head -5
echo "--- memory (PSS)"
for p in $PIDS; do adb shell dumpsys meminfo "$p" | grep -E '^\*\* MEMINFO|TOTAL PSS' | tr -s ' ' | paste -sd' ' -; done
adb shell grep MemAvailable /proc/meminfo

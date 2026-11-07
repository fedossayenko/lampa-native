#!/bin/bash
# Installs the build on the TV (ANDROID_SERIAL=<tv ip>:5555) and compiles it ahead of time (design rule 6: start ~40 ms
# sooner). A plain adb install leaves it to the JIT until Android's own compile on an idle night on power, which a stick
# powered by the TV's USB never has. On a rooted Fire TV the compiled code stays in /data/dalvik-cache from every
# install: the older ones are removed (skipped without root).
# Not while something plays: the install stops the app, and DDD hands its position back to it.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SERIAL:?ANDROID_SERIAL=<tv ip>:5555}"
export ANDROID_SERIAL
if adb shell dumpsys media_session | grep -q "state=PlaybackState {state=3"; then
    echo "Something is playing: not now." >&2
    exit 1
fi
adb install -r app/build/lampa-native.apk
adb shell cmd package compile -m speed -f local.lampanative
if ! adb shell su -c true > /dev/null 2>&1; then
    echo "Compiled (no root: older compiled files, if any, stay)"
    exit 0
fi
now=$(adb shell pm path local.lampanative | tr -d '\r' | sed 's|package:/||; s|/base.apk||; s|/|@|g')
for f in $(adb shell su -c 'ls /data/dalvik-cache/arm' | tr -d '\r' | grep 'local.lampanative' | grep -v -F "$now"); do
    adb shell su -c "rm -f /data/dalvik-cache/arm/$f" # -f: the replaced install's own go meanwhile
done
echo "Compiled: $(adb shell su -c 'ls /data/dalvik-cache/arm' | grep -c -F "$now") files of this install kept"

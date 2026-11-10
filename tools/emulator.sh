#!/bin/bash
# Runs the app on the Android TV emulator "firetv", made like the stick: 1920x1080 at density 320, 1792 MB RAM,
# 4 cores, a 192 MB heap (Android 12: no arm64 Android 11 TV image exists, and Fire OS 8.1 is Android 11). For
# looking at screens only; performance is measured on the TV (measure.sh). With STICK=<rooted tv ip>:5555 it is set
# up as that TV's app: its worker, play token and CUB sign-in piped across, never written to disk here. The emulator's
# user build has no root, so the app is built DEBUGGABLE for run-as, then built again as the TV's.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
STICK=${STICK:-}
AVD="$HOME/.android/avd/firetv.avd"

if [ ! -d "$AVD" ]; then
    "$SDK/cmdline-tools/latest/bin/sdkmanager" --install emulator "system-images;android-31;android-tv;arm64-v8a"
    echo no | "$SDK/cmdline-tools/latest/bin/avdmanager" create avd -n firetv -d tv_1080p \
        -k "system-images;android-31;android-tv;arm64-v8a"
    sed -i.bak '/^disk.dataPartition.path=/d' "$AVD/config.ini" # a temp file: the app gone at every start
    rm "$AVD/config.ini.bak"
    cat >> "$AVD/config.ini" <<'EOF'
hw.ramSize=1792
hw.cpu.ncore=4
vm.heapSize=192
hw.lcd.density=320
disk.dataPartition.size=2G
hw.keyboard=yes
EOF
fi

emu() { adb devices | grep -o '^emulator-[0-9]*' | head -1 || true; }
if [ -z "$(emu)" ]; then
    "$SDK/emulator/emulator" -avd firetv -no-snapshot-save -no-boot-anim -no-audio > /dev/null 2>&1 &
fi
until [ -n "$(emu)" ]; do sleep 2; done
E=$(emu)
until [ "$(adb -s "$E" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done

DEBUGGABLE=1 app/build.sh
adb -s "$E" install -r app/build/lampa-native.apk
app/build.sh # the stick's build back
adb -s "$E" shell "run-as local.lampanative sh -c 'mkdir -p files'"
if [ -n "$STICK" ]; then
    for f in server play_token cub; do
        adb -s "$STICK" exec-out su -c "cat /data/data/local.lampanative/files/$f" |
            adb -s "$E" exec-in "run-as local.lampanative sh -c 'cat > files/$f'"
    done
fi
adb -s "$E" shell am start -n local.lampanative/.Main

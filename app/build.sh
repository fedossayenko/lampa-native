#!/bin/bash
# Builds build/lampa-native.apk with plain SDK tools (no Gradle).
# Optional, in the environment or app/local.env (not in git):
#   HOME_SERVER=host:port   a computer at home running worker/home-server/voice-claude.py (Claude for voice search
#                           and Story so far): plain http to that host only. Without it both features are off.
#   KEYSTORE, KS_PASS, KEY_ALIAS   release signing; else the local debug key (made when missing).
# DEBUGGABLE=1: for the emulator (tools/emulator.sh), whose user build has no root: run-as copies the sign-in in.
set -euo pipefail
cd "$(dirname "$0")"
# shellcheck source=/dev/null
[ -f local.env ] && . ./local.env
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/36.1.0"
JAR="$SDK/platforms/android-36/android.jar"
HOME_SERVER=${HOME_SERVER:-}
if [ -n "$HOME_SERVER" ] && [[ ! $HOME_SERVER =~ ^[A-Za-z0-9.-]+:[0-9]{1,5}$ ]]; then
    echo "HOME_SERVER must be host:port, e.g. 192.168.1.20:8790" >&2
    exit 1
fi

rm -rf build && mkdir -p build/classes build/gen
cp -R res build/gen/res
sed "s|android:name=\"home_server\" android:value=\"\"|android:name=\"home_server\" android:value=\"$HOME_SERVER\"|" \
    AndroidManifest.xml > build/gen/AndroidManifest.xml
if [ -n "$HOME_SERVER" ]; then # plain http to the home server only; the rest stays https
    sed -i.orig "s|</network-security-config>|<domain-config cleartextTrafficPermitted=\"true\"><domain includeSubdomains=\"false\">${HOME_SERVER%:*}</domain></domain-config></network-security-config>|" \
        build/gen/res/xml/net.xml
    rm build/gen/res/xml/net.xml.orig
fi
# Version: minutes since 2020 at the commit (its committer time: a commit dated before the last release's would
# not update it), and its tag or hash.
at=$(git log -1 --format=%ct 2>/dev/null || date +%s)
"$BT/aapt2" compile --dir build/gen/res -o build/res.zip
"$BT/aapt2" link -o build/unsigned.apk -I "$JAR" --manifest build/gen/AndroidManifest.xml -R build/res.zip \
    --target-sdk-version 30 --replace-version --version-code $(((at - 1577836800) / 60)) \
    --version-name "$(git describe --tags --always 2>/dev/null || echo dev)" ${DEBUGGABLE:+--debug-mode}
javac -encoding UTF-8 --release 21 -classpath "$JAR" -d build/classes src/local/lampanative/*.java
"$BT/d8" --release --min-api 30 --lib "$JAR" --output build build/classes/local/lampanative/*.class
# The same commit gives the same APK: classes.dex dated at the commit, no extra file attributes, no v1 signature
# (its entries carry the signing time). TZ: zip stores local time.
stamp=$(date -u -r "$at" +%Y%m%d%H%M.%S 2>/dev/null || date -u -d "@$at" +%Y%m%d%H%M.%S)
(cd build && TZ=UTC touch -t "$stamp" classes.dex && TZ=UTC zip -qX unsigned.apk classes.dex)
"$BT/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [ -n "${KEYSTORE:-}" ]; then
    sign=(--ks "$KEYSTORE" --ks-pass env:KS_PASS ${KEY_ALIAS:+--ks-key-alias "$KEY_ALIAS"})
else
    debug="$HOME/.android/debug.keystore"
    if [ ! -f "$debug" ]; then # as Android Studio makes it
        mkdir -p "$(dirname "$debug")"
        keytool -genkeypair -keystore "$debug" -storepass android -keypass android -alias androiddebugkey \
            -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US" > /dev/null
    fi
    sign=(--ks "$debug" --ks-pass pass:android)
fi
"$BT/apksigner" sign "${sign[@]}" --v1-signing-enabled false --v4-signing-enabled false \
    --out build/lampa-native.apk build/aligned.apk
echo "Built build/lampa-native.apk"

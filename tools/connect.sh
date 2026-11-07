#!/bin/bash
# Connects a TV to your worker, no root needed (README, Setup): the play token (one for all your TVs, made the first
# time and kept in ~/.config/lampa-native/play_token) into the worker's secret PLAY_TOKEN, then the worker's address
# and the token into the app through adb, and the app's answer back from its log. The token never goes through argv
# or the screen here. The app takes them only while it has neither: adb shell pm clear local.lampanative to redo it.
#   WORKER=https://<name>.<account>.workers.dev STICK=<tv ip>:5555 tools/connect.sh
set -euo pipefail
cd "$(dirname "$0")/.."
: "${WORKER:?WORKER=https://<name>.<account>.workers.dev}" "${STICK:?STICK=<tv ip>:5555 (adb connect it first)}"
if [[ ! $WORKER =~ ^https://[A-Za-z0-9.-]+(:[0-9]{1,5})?$ ]]; then # as the app's Home.valid
    echo "WORKER must be https://host, no path." >&2
    exit 1
fi
file="${XDG_CONFIG_HOME:-$HOME/.config}/lampa-native/play_token"
mkdir -p "$(dirname "$file")"
if [ ! -f "$file" ]; then
    (umask 077 && openssl rand -hex 32 > "$file.new")
else
    cp "$file" "$file.new"
fi
# every run: a worker set up again (or another one) gets it too; the file is kept only once the worker has it
(cd worker && ${NPX:-npx} wrangler@4 secret put PLAY_TOKEN < "$file.new")
mv "$file.new" "$file"
since=$(adb -s "$STICK" shell "date '+%m-%d %H:%M:%S.000'" | tr -d '\r')
out=$(adb -s "$STICK" shell "read -r t; am start -W -S -n local.lampanative/.Main --es server '$WORKER' --es token \"\$t\"" \
    < "$file")
if grep -q Error <<< "$out"; then
    echo "$out" >&2
    exit 1
fi
line=
for _ in 1 2 3 4 5; do # -W: started; the line is there by now, or within a few seconds on a slow TV
    line=$(adb -s "$STICK" shell "logcat -d -T '$since' -s LampaNative:I" | tr -d '\r' | grep -o 'setup: .*' | tail -1 || true)
    [ -n "$line" ] && break
    sleep 1
done
echo "${line:-No answer from the app: is it installed on $STICK?}"
[[ $line == "setup: connected to $WORKER" || $line == "setup: kept $WORKER, set before"* ]]

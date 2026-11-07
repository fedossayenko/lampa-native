#!/bin/bash
# Every commit (.githooks/pre-commit): shell lint, every javac lint as an error, the host unit tests and the worker's.
# Pushes add the APK build and Android Lint (.githooks/pre-push). Runs in a few seconds.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JAR="$SDK/platforms/android-36/android.jar"
# org.json for the tests on the Mac; on the stick Android has its own. Pinned by checksum. The two differ on bad
# values (Android turns JSON null into the text "null", this one throws), so the worker never sends null.
JSON=tools/lib/json-20260814.jar
JSON_SHA256=7dd2de1533e766e2b6c07d8194da85145ad313a167a4760050f5c9b6839604fe

shellcheck tools/*.sh .githooks/* app/build.sh worker/*.sh

# No `trap … EXIT` cleanup: in macOS's bash 3.2 an unset-variable error then exits with status 0 and the
# hook would pass a broken check. A fixed scratch dir, wiped at the start, needs no trap.
out=build/check
rm -rf "$out"
javac --release 21 -Xlint:all -Werror -encoding UTF-8 -cp "$JAR" -d "$out/app" app/src/local/lampanative/*.java

if [ ! -f "$JSON" ]; then
    mkdir -p tools/lib
    curl -sfL -o "$JSON" "https://repo1.maven.org/maven2/org/json/json/20260814/json-20260814.jar"
fi
echo "$JSON_SHA256  $JSON" | shasum -a 256 -c --quiet

# The classes that import nothing from android.* hold the logic; they and their tests run on the Mac's JVM.
pure=() # macOS's bash 3.2 has no mapfile
while IFS= read -r f; do pure+=("$f"); done < <(grep -L '^import android\.' app/src/local/lampanative/*.java)
javac --release 21 -Xlint:all -Werror -encoding UTF-8 -cp "$JSON" -d "$out/test" \
    ${pure[@]+"${pure[@]}"} app/test/local/lampanative/*.java tools/TestRunner.java
java -ea -cp "$out/test:$JSON" TestRunner "$out/test"

# The worker's routes (Node 22+, no dependencies: fetch is mocked)
node --test --test-reporter=dot worker/native.test.js

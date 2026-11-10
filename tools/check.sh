#!/bin/bash
# Every commit (.githooks/pre-commit): shell lint, Python syntax, every javac lint as an error, the host unit tests and
# the worker's.
# Pushes add the APK build and Android Lint (.githooks/pre-push). Runs in a few seconds.
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
JAR="$SDK/platforms/android-36/android.jar"
# org.json for the tests on the Mac; on the stick Android has its own. Pinned by checksum. The two differ on bad
# values (Android turns JSON null into the text "null", this one throws), so the worker never sends null.
JSON=tools/lib/json-20260814.jar
JSON_SHA256=7dd2de1533e766e2b6c07d8194da85145ad313a167a4760050f5c9b6839604fe

shellcheck tools/*.sh tools/stick/*.sh .githooks/* app/build.sh worker/*.sh
# The home-server scripts and the importer have no tests; at least they parse (-B: no __pycache__ written)
python3 -B -c 'import ast, sys; [ast.parse(open(f, encoding="utf-8").read(), f) for f in sys.argv[1:]]' \
    worker/*.py worker/home-server/*.py

# No `trap … EXIT` cleanup: in macOS's bash 3.2 an unset-variable error then exits with status 0 and the
# hook would pass a broken check. A fixed scratch dir, wiped at the start, needs no trap.
out=build/check
rm -rf "$out"
javac --release 21 -Xlint:all -Werror -encoding UTF-8 -cp "$JAR" -d "$out/app" app/src/local/lampanative/*.java

# One file that every screen has to be read through is how Main grew to 3,700 lines: past 2000 a screen gets its own part
for f in app/src/local/lampanative/*.java app/test/local/lampanative/*.java; do
    if [ "$(wc -l <"$f")" -gt 2000 ]; then
        echo "$f is over 2000 lines: move a screen into its own file (Main.Part)" >&2
        exit 1
    fi
done

if [ ! -f "$JSON" ]; then
    mkdir -p tools/lib
    # to a side file: a cut-off download left at $JSON would fail the checksum on every run, never fetched again
    curl -sfL --retry 3 -o "$JSON.part" "https://repo1.maven.org/maven2/org/json/json/20260814/json-20260814.jar"
    echo "$JSON_SHA256  $JSON.part" | shasum -a 256 -c --quiet
    mv "$JSON.part" "$JSON"
fi
echo "$JSON_SHA256  $JSON" | shasum -a 256 -c --quiet

# The classes that import nothing from android.* hold the logic; they and their tests run on the Mac's JVM.
pure=() # macOS's bash 3.2 has no mapfile
while IFS= read -r f; do pure+=("$f"); done < <(grep -L '^import android\.' app/src/local/lampanative/*.java)
javac --release 21 -Xlint:all -Werror -encoding UTF-8 -cp "$JSON" -d "$out/test" \
    ${pure[@]+"${pure[@]}"} app/test/local/lampanative/*.java tools/TestRunner.java
java -ea -cp "$out/test:$JSON" TestRunner "$out/test"

# The worker's routes (Node 22.8+, no dependencies: fetch is mocked); 95 % of its lines are run, 90 is the floor
node --test --test-reporter=dot --experimental-test-coverage --test-coverage-lines=90 worker/native.test.js

# The home server's request checks (Python 3, standard library): signature, time window, replay, sizes
python3 -I worker/home-server/test_voice_claude.py 2>&1 | tail -3

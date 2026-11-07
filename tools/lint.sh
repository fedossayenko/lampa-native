#!/bin/bash
# Android Lint: API-level checks against the stick's Android 11, TV manifest rules, Android correctness;
# warnings count as errors. Needs the classes from app/build.sh. About 3 s; runs before pushes.
set -euo pipefail
cd "$(dirname "$0")/../app"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
# stderr only carries JVM warnings from lint's bundled IntelliJ core, so it goes to a file
if ! ANDROID_HOME="$SDK" lint --sdk-home "$SDK" --compile-sdk-version 36 --sources src --classpath build/classes --libraries "$SDK/platforms/android-36/android.jar" \
    --config lint.xml -Werror --exitcode --quiet --text stdout . 2> build/lint.log; then
    echo "Android Lint failed (JVM output: app/build/lint.log)"
    exit 1
fi

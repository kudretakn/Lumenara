#!/usr/bin/env bash
# Runs the optimized QA smoke test on a booted emulator and always keeps logcat.
set -u
adb logcat -c || true
./gradlew -Pwp02.testBuildType=qa :app:connectedQaAndroidTest --stacktrace
rc=$?
mkdir -p app/build
adb logcat -d -v threadtime > app/build/qa-logcat.txt || true
exit $rc

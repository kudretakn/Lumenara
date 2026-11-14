#!/usr/bin/env bash
# Runs the instrumented tests on an already booted emulator using APKs built by the CI build job.
# Usage: scripts/ci-device-tests.sh <apk-dir> <out-dir>
set -u
apks="${1:-apks}"
out="${2:-screens}"
runner="dev.streamora.tv.debug.test/androidx.test.runner.AndroidJUnitRunner"
mkdir -p "$out"

adb logcat -c || true
adb install -r -g "$apks/app-debug.apk" || exit 1
adb install -r -g "$apks/app-debug-androidTest.apk" || exit 1

# A hung test must not consume the whole job; the timeout leaves time to collect artifacts.
timeout 2400 adb shell am instrument -w -r "$runner" > "$out/instrument.txt" 2>&1
code=$?

adb logcat -d -v time > "$out/logcat.txt" 2>&1 || true
adb pull /sdcard/Android/data/dev.streamora.tv.debug/files/ "$out/" > /dev/null 2>&1 || true
adb pull /sdcard/Download/streamora-ui "$out/" > /dev/null 2>&1 || true

# Summarise: one line per finished test with its result, then failure stacks.
awk -F= '
  /INSTRUMENTATION_STATUS: class=/ { cls = $2 }
  /INSTRUMENTATION_STATUS: test=/  { name = $2 }
  /INSTRUMENTATION_STATUS_CODE: 0/  { print "PASS  " cls "." name }
  /INSTRUMENTATION_STATUS_CODE: -2/ { print "FAIL  " cls "." name }
  /INSTRUMENTATION_STATUS_CODE: -3/ { print "SKIP  " cls "." name }
' "$out/instrument.txt" | tee "$out/summary.txt"
grep 'StreamoraRetry' "$out/logcat.txt" | sed -n 's/.*RETRY \([^ ]*\).*/RETRIED  \1/p' | sort -u | tee -a "$out/summary.txt"
grep -A4 'INSTRUMENTATION_STATUS: stack=' "$out/instrument.txt" | head -80

if [ "$code" -eq 124 ]; then echo "Instrumentation timed out (last test above was still running)"; exit 1; fi
if [ "$code" -ne 0 ]; then echo "adb instrument exited with $code"; exit 1; fi
grep -q 'FAILURES!!!' "$out/instrument.txt" && exit 1
grep -q '^OK (' "$out/instrument.txt" || { echo "No OK line in instrumentation output"; exit 1; }

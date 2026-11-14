#!/usr/bin/env bash
# Runs the instrumented tests on an already booted emulator using APKs built by the CI build job.
# Usage: scripts/ci-device-tests.sh <apk-dir> <out-dir>
#
# A test that fails in the full run is run once more on its own, in a fresh instrumentation
# process. If it then passes it is reported as RETRIED (passed on retry) so flaky tests stay
# visible; if it fails again the job fails.
set -u
apks="${1:-apks}"
out="${2:-screens}"
runner="dev.lumenara.tv.debug.test/androidx.test.runner.AndroidJUnitRunner"
mkdir -p "$out"

results() {
  awk -F= '
    /INSTRUMENTATION_STATUS: class=/ { cls = $2 }
    /INSTRUMENTATION_STATUS: test=/  { name = $2 }
    /INSTRUMENTATION_STATUS_CODE: 0/  { print "PASS  " cls "." name }
    /INSTRUMENTATION_STATUS_CODE: -2/ { print "FAIL  " cls "." name }
    /INSTRUMENTATION_STATUS_CODE: -3/ { print "SKIP  " cls "." name }
  ' "$1"
}

adb logcat -c || true
adb install -r -g "$apks/app-debug.apk" || exit 1
adb install -r -g "$apks/app-debug-androidTest.apk" || exit 1

# A hung test must not consume the whole job; the timeout leaves time to collect artifacts.
timeout 2400 adb shell am instrument -w -r "$runner" > "$out/instrument.txt" 2>&1
code=$?
results "$out/instrument.txt" > "$out/first-run.txt"

# A process crash ends the run early and the tests after it never start. List every test
# (log-only mode runs nothing) and continue with the ones that have no result yet.
timeout 300 adb shell am instrument -w -r -e log true "$runner" > "$out/test-list.txt" 2>&1
results "$out/test-list.txt" | sed 's/^[A-Z]*  //' | sort -u > "$out/all-tests.txt"
for part in 1 2 3; do
  [ "$code" -eq 124 ] && break
  grep -q -E '^OK \(|FAILURES!!!' "$out/instrument.txt" && break
  missing=$(comm -23 "$out/all-tests.txt" <(sed 's/^[A-Z]*  //' "$out/first-run.txt" | sort -u) \
    | sed 's/\.\([^.]*\)$/#\1/' | paste -sd, -)
  [ -z "$missing" ] && break
  echo "Run ended early; continuing with: $missing"
  log="$out/instrument-continue-$part.txt"
  timeout 2400 adb shell am instrument -w -r -e class "$missing" "$runner" > "$log" 2>&1
  code=$?
  results "$log" >> "$out/first-run.txt"
  grep -q -E '^OK \(|FAILURES!!!' "$log" && { cat "$log" >> "$out/instrument.txt"; break; }
done

final="$out/summary.txt"
grep -v '^FAIL' "$out/first-run.txt" > "$final" || true
retry=0
for test in $(sed -n 's/^FAIL  //p' "$out/first-run.txt"); do
  retry=$((retry + 1))
  log="$out/instrument-retry-$retry.txt"
  timeout 900 adb shell am instrument -w -r -e class "${test%.*}#${test##*.}" "$runner" > "$log" 2>&1
  if results "$log" | grep -q "^PASS  $test\$"; then
    echo "RETRIED  $test" >> "$final"
  else
    echo "FAIL  $test" >> "$final"
  fi
done

adb logcat -d -v time > "$out/logcat.txt" 2>&1 || true
adb pull /sdcard/Android/data/dev.lumenara.tv.debug/files/ "$out/" > /dev/null 2>&1 || true
adb pull /sdcard/Download/lumenara-ui "$out/" > /dev/null 2>&1 || true

cat "$final"
grep -A4 'INSTRUMENTATION_STATUS: stack=' "$out"/instrument*.txt | head -120

if [ "$code" -eq 124 ]; then echo "Instrumentation timed out (last test above was still running)"; exit 1; fi
# Every listed test must have a result; a crash that kept tests from running fails the job.
never=$(comm -23 "$out/all-tests.txt" <(sed 's/^[A-Z]*  //' "$final" | sort -u))
[ -s "$out/all-tests.txt" ] || { echo "Could not list the tests"; exit 1; }
[ -z "$never" ] || { echo "Tests that never ran (crash?):"; echo "$never"; exit 1; }
grep -q '^PASS' "$final" || { echo "No test passed; the instrumentation run did not complete"; exit 1; }
grep -q '^FAIL' "$final" && exit 1
exit 0

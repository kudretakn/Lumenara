#!/usr/bin/env bash
# WP10: run prebuilt optimized QA APKs through WP01's strict inventory/deadline gate.
# Lead builds once with -Pwp02.testBuildType=qa :app:assembleQa :app:assembleQaAndroidTest.
# Like connectedQaAndroidTest, execute the stateful smoke once on a fresh emulator.
# Retrying in the same package chains deferred WorkSpecs and retains dead fixture URLs.
# Usage: scripts/qa-smoke.sh [apk-directory] [output-directory] [WP01 options...]
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
apk_dir="${1:-apks}"
out="${2:-screens/qa}"
if (( $# > 0 )); then shift; fi
if (( $# > 0 )); then shift; fi
mkdir -p "$out"
# Mark entry before validating inputs so a bad APK cannot trigger a boot-only retry.
printf 'INFRA  QA runner starting\n' > "$out/summary.txt"
sha256sum -- "$apk_dir/app-qa.apk" "$apk_dir/app-qa-androidTest.apk" > "$out/apk-pair.sha256"
exec "$script_dir/ci-device-tests.sh" "$apk_dir" "$out" \
  --apk "$apk_dir/app-qa.apk" --test-apk "$apk_dir/app-qa-androidTest.apk" \
  --package dev.lumenara.tv.qa \
  --runner dev.lumenara.tv.qa.test/androidx.test.runner.AndroidJUnitRunner \
  --class dev.lumenara.tv.Wp02OptimizedSmokeTest \
  --total-timeout 900 --artifact-reserve 120 --single-run "$@"

#!/usr/bin/env bash
# WP01: uses the same parser, retry policy and total deadline as PowerShell.
# Existing usage: scripts/ci-device-tests.sh <apk-dir> <out-dir>
# QA: append --apk PATH --test-apk PATH --package ID --runner TEST_ID/RUNNER.
# See scripts/test-results/Wp01README.md for filters, deadlines and allowed skips.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec "${WP01_PYTHON:-python3}" "$script_dir/test-results/Wp01DeviceTests.py" "$@"

# WP01 test and release gates

`ci-device-tests.sh` and `local-device-tests.ps1` invoke **the same Python 3.9+
engine** (`Wp01DeviceTests.py`). No Python packages are required. Both require an
emulator serial (Bash auto-selects only when exactly one emulator is online;
PowerShell defaults to `emulator-5580`). Neither targets a physical TV.

Existing calls still work:

```sh
./scripts/ci-device-tests.sh apks screens
```

```powershell
./scripts/local-device-tests.ps1 -Serial emulator-5580 -SkipBuild
```

For WP10 prebuilt QA runs, pass explicit APKs, target app ID, instrumentation
component and separate output directories for each variant/device:

```sh
./scripts/ci-device-tests.sh . screens/qa28 --serial emulator-5580 \
  --apk /path/to/app-qa.apk --test-apk /path/to/app-qa-androidTest.apk \
  --package dev.lumenara.tv.qa \
  --runner dev.lumenara.tv.qa.test/androidx.test.runner.AndroidJUnitRunner
```

```powershell
./scripts/local-device-tests.ps1 -SkipBuild -Out build/qa28 -Serial emulator-5580 `
  -Apk C:/apks/app-qa.apk -TestApk C:/apks/app-qa-androidTest.apk `
  -Package dev.lumenara.tv.qa `
  -Runner dev.lumenara.tv.qa.test/androidx.test.runner.AndroidJUnitRunner
```

The QA IDs above are examples; WP02/WP10 must supply actual variant IDs.
Bash supports `--class ClassName[#method]`; PowerShell uses `-Class`.
Use `--adb` / `-Adb` and `WP01_PYTHON` / `-Python` to select tools explicitly.
PowerShell's optional debug build precedes the shared device-test deadline.

`scripts/qa-smoke.sh apks screens/qa` supplies the actual optimized QA IDs and
`--single-run`: log-only discovery followed by exactly one execution. It retains
the strict inventory/completion/crash/skip gate, with no continuation or test retry.
This matches the old `connectedQaAndroidTest` smoke lifecycle on a fresh emulator.
Restarting instrumentation retains Room/Vault and the WorkManager database, including
deferred jobs and URLs pointing at the previous test's now-closed fixture server.
There is no reinstall or data clear between discovery and execution, or during the
test's Activity recreation/Vault key reuse checks. The default debug retry policy
is unchanged. The QA budget is 900 seconds including the 120-second artifact reserve.
The workflow checks a SHA-256 manifest for the exact pair built together; the
launcher archives its input APK hashes and the shared runner records command argv.
Test APK installation includes `-t` as well as `-r -g`; API28's storage grant is
retained. API36 keeps the original TV profile/RAM/animation settings, and API28
runs the same smoke on the x86/2GB TV image with no skipped verifications.

## API36 debug shards and test timeouts

Android CI runs three API36 debug jobs, with separate emulators, 1,800-second
budgets (including the 120-second artifact reserve), and artifacts named
`android-tv-debug-api36-s0`, `-s1`, and `-s2`. API28 debug and both QA smoke jobs
keep their existing filters, budgets, and execution policy.

```sh
./scripts/ci-device-tests.sh apks screens --num-shards 3 --shard-index 0 --test-timeout 180
```

Each shard first discovers the entire APK's suite in log-only mode. The
`balanced-classes-v1` algorithm groups tests by class, orders classes by decreasing
test count (class name breaks ties), and assigns each class to the smallest shard
(shard index breaks ties). This keeps classes together, balances test counts, and
automatically includes new tests without a maintained allowlist. The same inventory
always produces the same partition regardless of discovery order.

`--test-timeout` opts into one instrumentation invocation per test. The Python
runner enforces a hard wall-clock limit around the whole invocation, including
startup, setup, native code and teardown. A hung invocation is killed and recorded
as `TIMEOUT`; the app and test packages are force-stopped before the next test runs.
This also covers hangs that a JUnit method timeout cannot interrupt. Nothing clears
app data or reinstalls APKs between tests. Each normal failure or timeout gets at
most one retry after the shard's first pass. Normal failures can become visible
`RETRIED` passes; a timeout stays `TIMEOUT`/red even after a clean retry. Crashes and
protocol errors likewise remain red while later tests continue. Every invocation,
cleanup, retry and artifact command shares the original shard deadline. The option
defaults to disabled; `--single-run` rejects isolation and sharding so QA keeps its
persistent-state lifecycle.

`all-tests.txt` and `full_inventory` in `Wp01-results.json` retain full discovery;
`shard-tests.txt` and `inventory` contain only this shard's expected tests. The JSON
also records shard count/index/algorithm, per-test timeout, every attempt and command.
The required `device` job downloads every API36 shard artifact into separate
directories and invokes `Wp10DeviceGate.py --shard-reports device-shards --num-shards 3`.
It requires all existing build/device dependencies to pass, exactly one successful
report per shard index, identical valid full inventories, and each shard's inventory
and actual final results to equal its recomputed partition. It rejects overlaps and
requires their union to equal the full inventory. Missing artifacts, omitted tests,
inconsistent discovery, failing reports and unreviewed skips fail closed.

To increase parallelism, update `DEBUG_API36_SHARDS` and the API36 matrix rows in
`android.yml` together, with indices `0..N-1` and unique `-sINDEX` artifact suffixes.
The fixture tests check their agreement, and the aggregate gate rejects missing or
duplicate indices at runtime. No changes to test lists or release gates are needed.

## Protocol and policy

Inventory is discovered **before** execution using the same runner/filter in
log-only mode. Discovery and every execution require exit 0, exactly one final
`INSTRUMENTATION_CODE: -1`, a JUnit completion summary, nonzero terminal results,
and a consistent `numtests`. Every requested test must appear exactly once.
Status 0 passes; -1/-2 fail; -3/-4 skip; 1/2 are start/progress. Unknown codes,
runner errors, crashes, missing identities, incomplete inventories and timeouts
fail the gate. Discovery results do not count as execution results.

Missing tests may be continued for diagnostic coverage. An infrastructure error
stays red even if subsequent runs pass. Each failed test gets at most one retry;
a complete, clean retry becomes `RETRIED`, including when every result is
`RETRIED`. A retry crash/timeout stays red. All skipped is red.

`Wp01AllowedSkips.json` defaults to `{}`: every skip is unexpected. To permit a
reviewed platform-specific skip, supply `--allowed-skips path.json` or
`-AllowedSkips path.json` with exact `Class#method` keys and nonempty reasons:

```json
{
  "dev.lumenara.tv.AudioFallbackTest#undecodableAudioSwitchesToVlc": "Only for a reviewed emulator image with an MPEG-L2 decoder; its decoder capability makes this fallback scenario inapplicable."
}
```

Do not allow `BackupTest` isolation assumptions: they indicate the wrong test
library. No production skip is automatically exempted. Allowed skips remain
`SKIP` with their reason in `summary.txt` and `Wp01-results.json`.

The default **total device-run budget is 1,800 seconds**, including setup,
discovery, execution, continuations, retries and collection. The last 120 seconds
are reserved for logcat and two pulls. No command receives a fresh full budget;
artifact commands also share the remaining deadline. Tune
`--total-timeout` / `-TotalTimeoutSeconds` and
`--artifact-reserve` / `-ArtifactReserveSeconds` together. The 30-minute default
leaves headroom in the existing 55-minute CI job for emulator boot (up to 15
minutes), setup and artifact upload. WP10 must budget any matrix/subruns at job
level; separate launcher invocations do not share a deadline. API36 debug gives
each parallel shard this budget; all per-test invocations inside a shard share it.

Outputs include raw discovery/attempt logs, `all-tests.txt`, `summary.txt`, and
version 1 `Wp01-results.json` (inventory, attempts, final statuses, gate errors,
allowed reasons, command exit codes/durations and budget). Old output logs never
enter the verdict. A summary is written at startup, so a failed runner is not
mistaken for an emulator that never started by the existing CI retry condition.
Missing optional pull directories are tolerated and their command exits remain
visible. Disconnections, logcat failures and collection timeouts fail the gate,
alongside setup, protocol and runner errors.

## Validation without Gradle or a device

```sh
python3 -m unittest discover -s scripts/test-results -p 'Wp01*Test.py' -v
WP01_PWSH=/path/to/pwsh python3 -m unittest discover -s scripts/test-results -p 'Wp01*Test.py' -v
```

`Wp01Fixtures.json` contains raw Android protocol fixtures.
`Wp01FakeAdb.py` records argv and replays them without contacting a device.
Tests drive the real Bash and PowerShell launchers, including paths with spaces,
QA parameters, timeout/collection deadlines, and compare statuses/errors.
Without PowerShell those tests visibly skip; Release CI explicitly requires
`pwsh`. The tests never invoke Gradle.

## Release provenance and certificate

Tag releases require a commit reachable from `origin/main`, and the latest
Android CI **main push** run for that exact SHA must be successful. The gate
paginates runs/jobs, requires both `build` and aggregate `device`, rejects every
skipped/failed/pending job, and rejects a CI attempt changed during verification.
A previous green run, a PR merge SHA, another branch or another SHA is
insufficient. Check again immediately before publish. The workflow token needs
`actions: read`; main protection and the aggregate device job remain required.
Manual dispatch retains CI-signed experimental artifacts and never publishes.

`Wp01ReleaseGate.py` holds the original certificate fingerprint, unchanged from
the previous release workflow. CI verifies the APK signature using apksigner and
then requires exactly that signer before hashing/publishing.

PLAN section 5 forbids local release signing. `sign-release.ps1` therefore no
longer creates keys or signs APKs. Its supported mode verifies a downloaded
CI-produced APK with apksigner and the **same certificate pin**:

```powershell
./scripts/sign-release.ps1 -Apk C:/downloads/Lumenara-version.apk
```

The helper never reads or changes existing personal keys, aliases or passwords.
Real GitHub publication, real APK signatures, Windows tool discovery and upgrade
survival still require lead validation; fixtures do not establish device/TV
acceptance or performance.

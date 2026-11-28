# WP20 baseline/startup profile candidates

Automatic generation is disabled. Application profile rules and startup DEX layout
optimization are disabled pending measured improvement on the same optimized
**release** variant. The old handwritten bootstrap rules were not measurement
proof and have been removed. Dependency-provided library profiles remain normal
library inputs. ProfileInstaller remains unchanged.

## Explicit emulator-only generation

Never generate profiles on the owner's TV or any physical device. Ordinary `test`,
`lintDebug`, debug APK/test APK, QA and release assemblies exclude the producer
module entirely and require no device. Device-free dependency bootstrap may include
it with `-Pwp20.bootstrapDependencies=true`; that does not enable connected-device
collection. Settings restrict bootstrap to `wp20ResolveAndLockAll` and
`:baselineprofile:assembleNonMinifiedRelease` / `:baselineprofile:assembleBenchmarkRelease`; any other requested task fails before projects
are configured, so bootstrap cannot expose an ungated connected test task.

Only `-Pwp20.generateProfiles=true` enables collection. Before configuring any
project, settings run `Wp20ProfileGate.py`, which requires all of:

- An explicit `ANDROID_SERIAL` matching an exact entry in `Wp20EmulatorSerials.txt`.
- An `emulator-<digits>` serial, online and boot-complete.
- QEMU, API **36**, x86_64 and Android TV/Leanback features.

The checked-in allowlist contains only `emulator-5580`. Use a fresh disposable
Android TV API36 x86_64 AVD on that serial, or explicitly review/change the allowlist
for another emulator serial. There is no wildcard, IP/USB device, first-connected
device, API fallback or managed-device path. Every adb query specifies `-s`; it
has a 15-second timeout and any missing/wrong value fails. Instrumentation checks
API, ABI, QEMU and the host-provided serial again before touching the app.

After the lead has completed [dependency bootstrap](../gradle/Wp20Build.md):

```sh
ANDROID_SERIAL=emulator-5580 ./gradlew --no-configuration-cache -Pwp20.generateProfiles=true :app:generateReleaseBaselineProfile
```

PowerShell (Python 3 must be available as `python3` on PATH):

```powershell
$env:ANDROID_SERIAL = "emulator-5580"
.\gradlew.bat --no-configuration-cache -Pwp20.generateProfiles=true :app:generateReleaseBaselineProfile
```

The plugin uses its non-debuggable, non-minified release-derived collection build,
which retains emulator native libraries. This is **collection**, not performance
proof for the minified shipping release. Do not run a generic generation command
that also collects QA. Do not assemble/distribute a collection invocation's output.
Direct `generate*BaselineProfile` commands without opt-in fail explicitly.

The single `startupAndBrowse` test requires fresh Setup and seeds 48 local live
channels plus one movie through the real UI. It rejects existing catalogs rather
than skipping fixture preparation. It never clears app data or starts playback.
Use a new disposable AVD for another generation session; no uninstall/data-clear
or production source operation is part of this procedure.

Two collections have distinct prefixes: `Wp20Startup` covers cold startup and Hub
navigation only (`includeInStartupProfile=true`); `Wp20Browse` covers live category,
channel grid and DPAD focus movement, movie category/grid/details, populated search
results and return to Hub (`includeInStartupProfile=false`). Missing setup controls,
fixture/category, grid, poster, details, result or focus movement fails the test;
no journey is logged-and-skipped. Strict profile stability is required. The local
HTTP server stays alive through both collections. Profile collection is opt-in
tooling, outside the ordinary app device-suite budget and CI test inventory.

## Candidate storage and promotion

`automaticGenerationDuringBuild=false` and `saveInSrc=true` are required together
by the pinned AndroidX baseline-profile plugin **1.5.0**. `mergeIntoMain=true` and
`baselineProfileOutputDir="."` keep generated output at
`app/src/main/{baseline-prof,startup-prof}.txt`; the plugin's default output
subdirectory would instead be `generated/baselineProfiles`.

The opt-in invocation redirects every module's build directory under root
`build/wp20-profile-candidates/<module>/` for collection intermediates. This does
**not** isolate the source profile output: successful explicit generation can
replace both app source profiles, and subsequent ordinary builds consume those
files. Review their diff immediately and restore the pre-collection contents
before ordinary builds until same-release evidence supports retaining the rules.
Preserve the producer's raw
`Wp20Startup-*-prof.txt` and `Wp20Browse-*-prof.txt`, task logs, test result and
merged candidate with the collection SHA, catalog fixture and emulator image/API.
Both collections and every journey must pass in the same invocation; partial or
stale files from failed invocations are unusable. Inspect candidates for app rules
and verify the startup candidate excludes browse-only methods.

Before retaining an application profile, the lead must:

1. Build A and B from the same code/dependencies with the same release build type,
   R8/resource rules, ABI selection, signing, fixture and runtime conditions. A has
   no candidate application rules; B has the candidate. Do not compare debug, QA,
   non-minified collection APKs or different ABIs as evidence for shipping release.
2. Run at least five paired process-cold and five warm trials after profile
   installation/ART compilation. Record APK SHA/bytes/ABI/certificate/versionCode,
   profile-install result, full-Hub p50/p95/max, frame times, real CPU15 and PSS.
   Fresh-install first launch is separate: ProfileInstaller cannot precompile an
   APK before installation/compilation. Follow `docs/perf/common.md` and PLAN's
   measurement definitions; do not clear user data or downgrade a TV installation.
3. Require measured target improvement with unrelated p95/CPU/PSS regression no
   greater than 5%; repeat noisy pairs. TV collection/instrumentation is forbidden.
   Any optional owner TV validation uses only CI-signed, increasing-versionCode,
   same-certificate upgrades and the non-destructive measurement protocol.
4. Only after review, retain the accepted baseline and startup rules in the app
   source files in a follow-up change, enable startup layout optimization if its
   same-release evidence supports it, and include the complete measurement report.
   Keep automatic generation disabled. Verify the final APK's packaged profiles
   and strict emulator/upgrade/player checks before release.

No candidate has been generated or measured for WP20; application profiles remain
disabled. The host safety gate has deterministic device-free tests in
`app/src/test/python/Wp20ProfileGateTest.py`. The collection itself needs lead
API36 emulator execution and same-release performance measurement.

References: [AndroidX generation/storage configuration](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles),
[startup versus baseline profiles](https://developer.android.com/topic/performance/baselineprofiles/difference-baseline-startup).

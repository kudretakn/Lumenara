# Baseline profiles

Run explicitly on the local Android TV API 36 emulator (not the Mi Box):

```powershell
$env:ANDROID_SERIAL = "emulator-5580"
.\gradlew.bat :app:generateBaselineProfile
```

On bash: `ANDROID_SERIAL=emulator-5580 ./gradlew :app:generateBaselineProfile`.
The plugin builds a non-minified, non-debuggable app for collection. No managed device
is configured. API 28-32 collection requires root; API 33+ supports non-root collection.
The producer's debug variant is disabled and ordinary `test`, `lintDebug`,
`:app:assembleDebug`, and `:app:assembleDebugAndroidTest` do not run generation.
The existing CI device runner still installs and instruments only the two app debug APKs.

The generator refuses physical devices (`ro.kernel.qemu` must be `1`). **Each test
clears `dev.lumenara.tv` app data on the emulator**, imports a fresh local M3U through
Setup, and requires the Hub before collection. This avoids stale server ports or
an unrelated existing source making a run appear successful. The fixture remains
after the run, but its server stops; do not use it as a persistent playlist.

Navigation mirrors `LibraryJourneyTest`: request focus, verify focus, then send
remote DPAD OK. TV Material Surface activation must not depend on a touchscreen
`UiObject2.click()`. A clean install shows the welcome actions alongside the phone
pairing panel; `setup-add-m3u` ("Liste adresi gir") opens the manual M3U form.
Setup locates the existing `setup-name` / `setup-address` resource-id tags and
targets `ACTION_SET_TEXT` on the tagged node or an editable descendant, matching
`LibraryJourneyTest`'s `hasSetTextAction()` without assuming the field's class and
description are exported on the same accessibility node. It focuses the editable
node and verifies the replacement text, without clicking/OK to open the TV IME or
blindly pressing Back (which could close the form). It reveals lazy controls before
use, expands `Gelişmiş`, verifies `HTTP uyumluluğu · Açık`, and submits
`Bağlantıyı sına ve ekle`.
Every required screen/control has a bounded wait; failures throw with the step,
selector, XML hierarchy and PNG screenshot paths in `baseline-failures/` under the
instrumentation argument `additionalTestOutputDir`. AGP pulls this directory into
`baselineprofile/build/outputs/` before uninstalling the test APK, so diagnostics
survive a failed run. Without the argument, it falls back to the producer's external
files directory. Capture errors are attached to the assertion as suppressed failures.
No missing step is silently skipped.

`MainActivity` already wraps `LibraryScreen` in `testTagsAsResourceId = true`.
Setup (including PairPanel), Hub, category/live/poster grids, movie/series details,
search and settings all descend from this root, including the early details branch.
No screen-level override is needed. The journey does not open separate-window popups.

MockWebServer serves 3 live categories with 96 channels each (24 rows at four
columns), 3 movie categories with 96 movies each, and one M3U series with 2 seasons
of 8 episodes. Every item has a distinct local `tvg-logo` PNG URL, including movies,
so Coil executes real fetch/cache/decode paths without external artwork services.
The server remains alive throughout each test's collection iterations.

Hub only has recent-channel and continue-watching shelves; a clean catalog has
neither. `app/src/nonMinifiedRelease/` therefore contains a collection-only receiver
which seeds 12 recent channels and 12 partially watched movies, like the history
setup in `LibraryJourneyTest`. It accepts only the named loopback fixture source,
has an 8-second timeout and reports success to the generator. It never adds sources
or catalog items, and is absent from normal debug/release APKs. This preparation
runs outside profile collection.

The two tests produce distinct outputs, merged by the Gradle plugin:

- `startup`: cold launch to the populated Hub only, `includeInStartupProfile = true`.
- `browseCatalog`: `includeInStartupProfile = false`; scroll both Hub shelves
  horizontally and move between them, open the named live category and navigate
  down eight rows/right three columns, do the same in a movie category, open/back
  from movie details, open series details and season/episodes, scroll episodes and
  back out, type a query and wait for actual results, then open/close settings.
  Absolute live/movie slots are checked so sending keys without scrolling fails.

No playback is started. Decoder/surface initialization, buffering and engine
fallback make playback less stable and more expensive on the emulator; fixture
stream requests fail the test. LibVLC/Media3 playback coverage is **not expected**.

AGP writes the measured files to
`app/src/main/generated/baselineProfiles/baseline-prof.txt` and
`app/src/main/generated/baselineProfiles/startup-prof.txt` with this configuration.
It does **not** replace `app/src/main/baseline-prof.txt`. Review and commit both
generated files after a successful run. The hand-written `app/src/main/baseline-prof.txt`
is an additive bootstrap input: keep it until a measured run is validated, then
remove it in the same commit as the measured profiles so wildcard rules do not mask
missing journey coverage. There is currently no hand-written `startup-prof.txt`.
Only the `startup` test contributes to the generated startup profile; shared startup
code and `BrowseKt.Home` can legitimately occur in it, but the browsing journey is
not marked as startup. See the [collection API](https://developer.android.com/reference/kotlin/androidx/benchmark/macro/junit4/BaselineProfileRule).

After the next run, require both tests to pass and inspect the **generated** baseline
file (not the additive bootstrap or just its line count) for `BrowseKt`,
`CategoryGridKt`, `LiveScreenKt`, `DetailsKt`, `SeriesDetailsKt`, `SearchScreenKt`,
`SettingsKt`, catalog paging/Room and `coil/` rules. Confirm the startup file is
limited to cold launch/Hub paths. Check the release merged manifest has no
`BaselineProfileFixtureReceiver`, and install/launch a freshly built release before
measuring improvements. Actual emulator generation must be verified on the host;
compilation alone does not validate remote focus or the recorded rules.

Release APKs consume the checked-in profiles without connecting to a device.
`dexLayoutOptimization` is enabled; R8 DEX layout optimization takes effect when release
minification is enabled. This change preserves the existing release shrinker settings.

ProfileInstaller's AndroidX Startup initializer is retained for sideloads and the updater.
It writes the packaged profile after launch; ART applies it during background dexopt.
It cannot accelerate the very first launch after a fresh install before profile installation
and compilation have occurred. To validate on an installed release without waiting for idle:

```powershell
adb -s emulator-5580 shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE -n dev.lumenara.tv/androidx.profileinstaller.ProfileInstallReceiver
adb -s emulator-5580 shell cmd package compile -f -m speed-profile dev.lumenara.tv
adb -s emulator-5580 shell am force-stop dev.lumenara.tv
```

Check the APK contains `assets/dexopt/baseline.prof` and `baseline.profm`, and the
broadcast reports successful installation before measuring a cold launch.
After generation, run the normal checks separately:
`./gradlew test lintDebug :app:assembleDebug :app:assembleDebugAndroidTest :app:assembleRelease`.

References: [generation configuration](https://developer.android.com/topic/performance/baselineprofiles/configure-baselineprofiles),
[profile delivery and DEX layout](https://developer.android.com/topic/performance/baselineprofiles/overview).

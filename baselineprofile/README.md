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

The generator uses existing Compose tags exported as resource IDs and localizes
setup selectors to the app's existing Turkish labels/content descriptions. On an
empty emulator it adds a local HTTP M3U through the same UI as LibraryJourneyTest:
Spor live channels and the Gece Treni movie, expanded to 48 channels for scrolling.
MockWebServer stays alive for the whole collection. No playback is started.
An existing source is retained; missing categories/details are logged and skipped.
The fixture remains in the emulator's app data; regenerate on a clean emulator app
installation if you need a fresh fixture server address in subsequent sessions.

Generation replaces `app/src/main/baseline-prof.txt` and `app/src/main/startup-prof.txt`.
Review and commit both generated files. Current files are hand-written bootstrap rules,
not measured device profiles. The startup file currently includes the whole requested
journey because collection uses `includeInStartupProfile = true`.
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

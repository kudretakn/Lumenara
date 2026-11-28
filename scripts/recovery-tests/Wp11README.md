# WP11 recovery and upgrade fixtures

Emulator only: explicit `emulator-N` serial, `ro.kernel.qemu=1`, x86_64, API28 or API36. Never run interruption, source removal, or fixture seeding on a TV. No build, uninstall, data-clear, downgrade, Keystore deletion, or signing operation is performed.

The normal debug instrumentation suite discovers `MigrationTest`, `UpgradeSurvivalTest`, and `ProcessDeathRecoveryTest`. The last test kills only the non-exported `:wp11_recovery` service process; the runner stays alive and finishes normally under the WP01 strict gate. No skipped tests or expected runner crashes are needed. Release/QA variants without the debug manifest do not expose the probe; run this matrix against debug only.

The service uses the real `Repository`, Room WAL, production `RestoreTransaction`, DataStore and Vault. Fixture files live under `files/wp11-recovery`, with the original database/DataStore leaf names; SharedPreferences use the `wp11_fixture_` namespace. The production Keystore alias is used without deleting it; production wrapped keys and user storage are untouched. Reset removes only that fixture directory and its three fixture SharedPreferences. Each kill is observed through Binder death, followed by startup recovery, exact semantic fixture checks, FK/integrity checks, journal removal, and another process restart. Source UUIDs may change on re-add, so identity checks use the production `contentKey` and exact remapped references.

Boundaries: `before_undo` (before journal save), `after_undo` (DAO save completed, FULL checkpoint not yet executed), `after_checkpoint`, `after_settings` (DataStore edit completed), `before_commit` (journal delete executed inside the still-open Room transaction), `after_commit` (Room transaction returned successfully). Every pre-commit boundary must restore the old state; the post-commit boundary must retain the new state. A deleted source is re-added and checked to prove orphan state is usable. Failure to reach a hook fails the test. Reflection is debug-only and checks the actual current storage calls rather than duplicating the undo algorithm; hook drift must be fixed rather than skipped.

On a prebuilt debug APK and matching instrumentation APK:

```bash
python3 scripts/recovery-tests/Wp11Recovery.py \
  --serial emulator-5580 --apk /path/current-debug.apk \
  --test-apk /path/current-debug-androidTest.apk \
  --aapt "$ANDROID_HOME/build-tools/36.0.0/aapt" \
  --apksigner "$ANDROID_HOME/build-tools/36.0.0/apksigner"
```

For a real package replacement, add `--previous-apk /path/previous-debug.apk` (optionally `--upgrade-only`). The current test APK first seeds the previous app using stable pre-WP11 public data APIs in a standalone instrumentation process; the previous APK does not need the probe. The host keeps a loopback-only synthetic playlist alive via `adb reverse`, performs `install -r` of the current app, and verifies through the current debug probe. All three APKs must have the same verified signer; app IDs must match, and neither install may downgrade an installed/baseline version. Use a separate emulator if a newer app is already installed. The previous app must support the public API/schema-13 fixture used by the seeder (the Wave 1 baseline does). APKs are provided by the lead; this script never invokes Gradle.

The standalone seed phase must not be added to a multi-class instrumentation run: DataStore's process-wide delegate must first be initialized with the isolated fixture context. The ordinary no-argument suite test uses the child process instead.

The host runs the process-death matrix, all migration tests (schemas 1–13), and encrypted disk reopen; `--upgrade-only` limits it to the two install-over phases. It has one 600-second default budget (maximum 900), refuses missing/extra/failed/skipped results and abnormal instrumentation completion using WP01's parser, and writes only APK identities/hashes, test IDs, and safe phase/error codes to `/tmp/wp11-recovery-result.json`. It does not capture logcat or print fixture/secret values. Hooks park for at most 25 seconds and IPC waits are bounded. The full device matrix must be timed by the lead within the suite's 30-minute limit.

Host-only harness checks (no Android/Gradle required):

```bash
python3 -m unittest discover -s scripts/recovery-tests -p 'Wp11*Tests.py' -v
```

Schema fixtures remain the immutable exported JSON assets. `MigrationTest` now covers every version 1–13, checks integrity/FKs, preserves its version-backfill/index assertions, and reopens the migrated disk to verify full mark tuples and physical rendition rows. The small migration fixture proves correctness, not the 39,682-item/API28 migration-open latency cited by D:B10; that latency and signed production install-over data survival still require the lead's non-destructive TV measurement. No interruption injection belongs on the TV.

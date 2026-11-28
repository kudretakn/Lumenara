# WP20 reproducible build evidence

The catalog preserves the existing plugin, library, SDK and NDK versions. No
application version, signing, ABI, optimizer, test scope or Room schema change is
part of this work. Gradle 9.3.1 and its existing distribution checksum are retained.

## Lead bootstrap (required before building/merging)

The WP20 implementation agent must not run Gradle. Consequently there are **no
invented lockfiles or checksum records** in this change. Strict locking and CI's
`Wp20DependencyEvidence.py` intentionally reject missing evidence. The lead must
run the following on the trusted JDK17/SDK36/NDK28.2.13676358 build machine, inspect
the downloaded origins/checksums, and include the real outputs in this package.
No device, adb, private signing key, or application data is needed.

```sh
# Resolve every project graph, including the opt-in producer's compile/tool graph.
./gradlew -Pwp20.bootstrapDependencies=true --no-configuration-cache --write-locks --write-verification-metadata sha256 wp20ResolveAndLockAll
./gradlew -Pwp20.bootstrapDependencies=true -Pwp02.testBuildType=qa --no-configuration-cache --write-locks --write-verification-metadata sha256 wp20ResolveAndLockAll

# Exercise the actual artifact/tool configurations, including lazy AGP/KSP/lint inputs.
./gradlew --write-locks --write-verification-metadata sha256 test lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
./gradlew -Pwp02.testBuildType=qa --write-locks --write-verification-metadata sha256 :app:assembleQa :app:assembleQaAndroidTest :app:assembleRelease
./gradlew -Pwp20.bootstrapDependencies=true --write-locks --write-verification-metadata sha256 :baselineprofile:assembleNonMinifiedRelease :baselineprofile:assembleBenchmarkRelease
python3 -B gradle/Wp20DependencyEvidence.py
```

`wp20ResolveAndLockAll` is the only task deliberately incompatible with
configuration cache: it inspects all resolvable project configuration graphs at
execution time, fails on unresolved components, and does not request unbuilt
project artifacts. It is not a prerequisite of normal build/test tasks. Empty
configurations are recorded by Gradle, not handwritten. State is stored in
`gradle/wp20-locks/<module>.lockfile`; `root.lockfile` can also be generated.

Review and retain all generated lockfiles and `gradle/verification-metadata.xml`.
Verification must include metadata and SHA-256 for every artifact, including
plugins, POMs and Gradle module metadata; do not add trust/ignore patterns to make
a failure pass. Checksums collected from a repository are trust-on-first-use, not
independent proof of provenance. Investigate unexpected inputs before accepting.
Delete `gradle/wp20-locks/Wp20Pending.txt` once the outputs are included.

Then rerun both complete CI task scopes **without** `--write-locks` or
`--write-verification-metadata`, with strict dependency verification (the evidence
runner sets this explicitly). On a second run, the reviewed lockfiles and metadata
must stay byte-identical. Repeat from an empty disposable Gradle user home to prove
that success is independent of the lead's existing cache. Verify that an altered
artifact fails SHA-256 verification and an unrecorded version/configuration fails
strict locking; restore the trusted inputs afterwards. Do not bypass validation
with lenient locking, verification off, ignored artifacts, or test exclusions.

Include a Linux x86_64 CI run in that validation. An ARM host's
`android.aapt2FromMavenOverride` can avoid resolving the Maven AAPT2 executable;
it is a local host accommodation, not a CI setting to copy. Fresh dependency
resolution can also read parent POMs and imported BOM/module metadata absent
from a warm-cache bootstrap, even when the selected dependency versions match.
The initial CI run exposed missing `guava-parent:33.3.1-jre` POM,
`junit-bom:5.11.0-M2` module and `kotlinx-coroutines-bom:1.8.0` POM checksums
on the root plugin classpath. Their SHA-256 entries record direct Maven Central
download origins. The Python evidence check validates the recorded entries'
structure; only strict Gradle resolution proves coverage of a host's inputs.

Plugin versions are pinned in the catalog and plugin artifacts are verified.
Project configurations are dependency-locked; the plugins DSL's separate internal
resolution graph is not represented as a fabricated project lockfile.
Bootstrap accepts only the two task names shown above; connected, installation
and profile-generation requests cannot use that flag to bypass the emulator gate.

## Cache measurements, with unchanged coverage

`org.gradle.caching` and `org.gradle.configuration-cache` are enabled. The existing
`setup-gradle` dependency cache is retained. Cache compatibility must pass the
lead's normal builds; no warning-only compatibility mode is configured.

The default CI invocation times its existing complete task scope once and retains
logs, commands, wall-clock seconds, task states and configuration-cache reuse as
JSON and Markdown artifacts, with SHA-256 of the build inputs, wrapper and dependency
evidence. Existing APK handoff, native/mapping reports, debug
API28 plus all three API36 shards, QA API28/API36 and aggregate `device` are kept.
On failure the complete Gradle output is also printed to the job log so long
stack traces cannot hide the exception and missing-artifact list above them.

For two consecutive uncached and two consecutive cached clean builds, use the
Android CI `workflow_dispatch` input `wp20_cache_evidence=true`, or have the lead run:

```sh
python3 -B gradle/Wp20BuildEvidence.py --scope debug --compare --output /tmp/wp20-debug-evidence
python3 -B gradle/Wp20BuildEvidence.py --scope optimized --compare --output /tmp/wp20-optimized-evidence
```

The scopes are exactly:

- `test lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`
- `-Pwp02.testBuildType=qa :app:assembleQa :app:assembleQaAndroidTest :app:assembleRelease`

Each sample first runs `clean`, outside the reported build duration; its own clean
log/time is also retained. Off runs disable both task/configuration caches. On runs
share a newly created isolated local task cache and project cache directory, so
the first on run populates them and the second can reuse them. `--rerun-tasks` is
not used because it would prevent cache reuse. Gradle's dependency cache, daemon,
OS caches and SDK remain shared; downloads can affect the first sample. Report
all four numbers and task states, not a claimed cold-machine speedup. The second
on run must actually reuse configuration cache. Any build/clean/timeout failure
stops the comparison, preserves its evidence, and fails CI. Task execution has a
one-hour per-invocation limit; dispatch optimized-build gets a 120-minute job
budget. Normal optimized-build keeps its existing 40-minute budget.

These measurements have **not** been run by the WP20 agent. No build speedup,
configuration-cache compatibility, or dependency reproducibility is claimed until
the lead supplies the outputs. Device/TV performance remains separate.

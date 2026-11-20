# Mi Box performance harness

Run from Windows PowerShell 5.1 with Android platform-tools (`adb`) on PATH. The
leader runs this against the real Mi Box; creating the harness does not connect to
the device. No APK install, app-data clear, baseline profile, new dependency or
catalog import is performed. The script force-stops the app, clears device logcat
(including other apps' buffered logs), navigates and can start real streams.

```powershell
adb connect 192.168.1.14:5555
adb -s 192.168.1.14:5555 get-state
.\scripts\perf-harness.ps1 -Out .\build\perf-before
.\scripts\perf-harness.ps1 -Serial 192.168.1.14:5555 -Out .\build\perf-after -Runs 3 -Scenarios cold_start,hub_scroll,grid_all_channels
.\scripts\perf-compare.ps1 -Before .\build\perf-before -After .\build\perf-after |
    Set-Content .\build\perf-diff.md -Encoding UTF8
```

Use a new output directory each time. Existing scenario/run JSON files are never
overwritten. `-Scenarios` is a PowerShell array (comma-separated when invoked inside
PowerShell); defaults to all eight scenarios. A failed ADB command or scenario is
recorded and remaining scenarios continue. Output/filesystem failures are fatal.
PASS/FAIL is in the report, not the process exit code. Preserve scripts alongside
each baseline when editing routes. Summary is regenerated after every run from
the current invocation only; do not mix invocations in the same directory.

## Before measuring

Use the same release APK, device, 39,682-item Xtream catalog, source scope and
network for a comparison. Record APK version, catalog counts, playback engine,
refresh rate, Wi-Fi conditions, route changes and room temperature with results.
Finish initial migrations, refresh/backfill, login and permission prompts first.
Keep the TV awake, screen on and unlocked; disable screen saver and close other
apps. Do not operate the remote during a run. Keep power mode and thermal state
consistent; allow the box to cool before each suite. The harness intentionally
does not change thermal, power, display or system settings. Run order is scenario
then repetition; thermal drift and cache warming can bias later runs.

## UI contract and editable routes

All input steps are at the top of `scripts/perf-harness.ps1` in `$Routes`,
`$Actions`, `$StartupWaitMs` and `$VodWaitMs`. `Key name count waitMs` repeats
one key with a fixed sleep after **each** press; `Text value waitMs` uses
`adb shell input text`. Use simple ASCII search text; shell metacharacters,
spaces, Turkish letters and Unicode require different escaping/IME handling.

Each run force-stops the app, resets gfxinfo and clears logcat, presses HOME,
launches `dev.lumenara.tv/.MainActivity` with `am start -W`, then waits 15 seconds.
It assumes an already configured source and Hub focus on **Canlı TV**. Hub has
two rows of three: **Canlı TV, Filmler, Diziler** above **Listem, Ara, Ayarlar**,
with history shelves below. Android saved
task state, multiple sources, missing history shelves, an IME, update prompts,
provider order and focus restoration can change these paths. Force-stop does
not reset preferences, watched history, disk caches or Room. These are process
cold starts, not first-install/cache-cold benchmarks.

| Scenario | Assumed route and workload |
|---|---|
| `cold_start` | Launch + 15 s idle; capture startup marks and process CPU. |
| `hub_scroll` | DOWN x2 into history; four cycles RIGHT x3, LEFT x3, DOWN, UP; RIGHT x3, LEFT x3 (40 presses), 250 ms sleep. Assumes populated history shelves below navigation. |
| `grid_all_channels` | CENTER opens Canlı TV; CENTER opens first category **Tüm kanallar**; DOWN x60, 150 ms. |
| `movie_grid` | RIGHT to Filmler; CENTER opens categories; CENTER opens first populated movie category; 20 cycles RIGHT x2, DOWN (60 presses), 150 ms. |
| `category_open` | Open live categories; open/BACK five different tiles. Four columns: first four tiles then first tile of second row. Each must yield `category_grid_first_draw`. Favorites/recent can be absent; edit path to five populated categories, avoiding timeline if its UI differs. |
| `search` | DOWN then RIGHT to Ara, CENTER, type `ava`, wait 4 s, type `tar`, wait 5 s, BACK dismisses IME. Assumes text field auto-focus. |
| `live_zap` | CENTER x3 through live categories/grid/channel; wait 8 s; CHANNEL_UP/DOWN/UP/UP/DOWN, 8 s after each. |
| `vod_play` | RIGHT to Filmler, CENTER x2 to grid, CENTER to details, CENTER on Play, wait 20 s, BACK. |

Calibrate one run per scenario with the physical remote available and observe the
whole route. Adjust the tables for your actual layout, especially movie details
Play focus, version/resume dialogs, search IME and provider tiles. Use DPAD_UP/DOWN
instead of CHANNEL_UP/DOWN if required by the player. Never accept a low-jank
result from a route that remained on a menu or empty grid. Mark counts can detect
some navigation failures but cannot prove item identity or scrolling distance.
Search has no results-ready Perf mark; its completion is a fixed wait and requires
visual confirmation. No app code or new Perf marks are added by this harness.

## Output and metrics

Each `<scenario>-<n>.json` holds parsed values, all individual frame durations,
each `LumenaraPerf` `key ms=value` occurrence (duplicates preserved), app PID/end
PID, duration, GC, health, errors and failure reasons. Sidecar `*-gfxinfo.txt`,
`*-meminfo.txt`, `*-processes.txt`, `*-logcat.txt`, `*-stat.txt`, `*-launch.txt`
retain the source dumps. Treat raw logs as sensitive: other app messages, titles
or provider URLs can appear. Review before sharing; logs are not automatically
redacted. A failed launch may have no launch sidecar.

`summary.md` shows the median per scenario of each per-run aggregate, using linear
interpolation for even-sized samples. Thus median p99 is not the pooled p99 and
median max is not the suite maximum. PASS requires **every** run to meet its
applicable targets; one failure is never hidden by medians. `summary.json` is the
machine-readable companion used by `perf-compare.ps1`, which writes a side-by-side
Markdown table to stdout, includes missing scenarios/values as N/A and reports
After minus Before. A negative frame-count/duration delta is not inherently an
improvement: the workload may have changed.

- **Frames:** parse Android 9 CSV by header names `Flags`, `IntendedVsync`,
  `FrameCompleted`, not hard-coded column positions (OEM/API columns differ).
  Keep Flags == 0, positive completed timestamps, exclude invalid/sentinel rows,
  deduplicate repeated timestamp pairs. Duration = (FrameCompleted - IntendedVsync)
  / 1e6 ms. Quantiles p50/p90/p95/p99 use linear interpolation. Report strict
  `>16.7 ms`, `>32 ms`, `>100 ms` and max. No frames means missing, not zero jank.
- **Window:** Compose for TV normally keeps one Activity/window; deduplication
  avoids repeated dumps, but additional windows may mix timelines. For three scroll
  scenarios a second gfxinfo reset occurs after navigation/idle and before input;
  their frame statistics exclude startup and category opening. Other scenarios
  include startup/navigation. `frameWindowSeconds` and `durationSeconds` identify
  the observed wall windows. Android framestats has a bounded history (often 120
  frames/window); statistics describe retained frames, not guaranteed whole-run
  coverage. Fast rendering can evict early outliers; no claim of full capture.
- **Rendering limits:** framestats measures UI/HWUI, not decoded Media3/LibVLC video
  Surface frames. It cannot establish stream smoothness or dropped video frames.
  16.7 ms is a fixed 60 Hz reference; actual 24/50/60 Hz switching changes budgets.
- **Perf:** raw LumenaraPerf entries are filtered to the launched app PID. Existing
  `startup_hub_first_draw` measures full Hub; shell/first-frame/data-ready markers
  are retained separately. `category_grid_first_draw` ends category open events.
  `channel_first_frame_media3` and `channel_first_frame_vlc` end selection-to-first
  frame events, including network/decoder delays. Live zap needs at least six
  entries (initial selection + five zaps); every observed first frame must pass.
  VOD requires a first-frame entry but has no latency threshold here.
- **Memory:** end-of-scenario `TOTAL PSS`, Java Heap, Native Heap, Graphics PSS in
  KiB, preferring App Summary; old Dalvik/Native table rows are fallback. Summary
  converts total to MiB (1024 KiB), interpreting the 150 MB target as 150 MiB.
  These are proportional resident allocations, not Java used/allocated heap or
  peak memory. Missing Graphics stays null, not zero. VOD is sampled after BACK;
  this is not playback peak PSS.
- **CPU:** end-of-scenario `/proc/<pid>/stat` fields 14+15 are cumulative process
  user+system ticks, parsed past the closing comm parenthesis. Raw ticks and host
  scenario duration (launch command through CPU sample, before dumpsys) are saved;
  multicore CPU can exceed wall duration. No arbitrary tick-to-seconds conversion.
  The strict first-15s target uses existing `startup_cpu_first_15s` in milliseconds
  (`Process.getElapsedCpuTime` delta) with `startup_cpu_sample_window` required
  within 15-16 s. Missing/delayed samples fail; cumulative end ticks do not replace
  the 15 s metric. A PID change or disappearance fails the run.
- **GC:** app-PID `GC freed` lines, summing all `paused` segments (us/ms/s), not
  concurrent GC total duration. Zero lines means zero *observed* GC; Android may
  suppress logs or log buffers may overflow. Unparseable pauses yield null.
- **Health:** save activity processes and all logcat buffers, detect app ANR and
  app FATAL EXCEPTION/native fatal signal, plus package-qualified crash records.
  PID disappearance/change also fails. Stale process error state and buffer loss
  can affect detection; inspect source dumps on failure. This is not a tombstone
  analyzer. Raw collection failures fail that run and never become a PASS.

## Targets

All runs: PSS <150 MiB and no ANR/crash/process change or collection/drive error.
`cold_start`: full Hub <1500 ms, first-15s CPU <5000 ms with valid sample window.
`hub_scroll`, `grid_all_channels`, `movie_grid`: <1% frames >32 ms and zero frames
>100 ms, with at least one valid frame. Category scenarios require their completion
marks (five for `category_open`). `live_zap`: each first frame <1500 ms and six or
more first-frame events. Missing required evidence is FAIL, explained beneath the
summary. Search PASS only covers collection, memory and health; manually confirm
results. Fixed sleeps are settling windows, not measured readiness thresholds.

ADB over Wi-Fi adds transport time to each input; sleep spacing is a minimum,
not precisely 150/250 ms. `am start -W` plus the 15 s wait exceeds exactly 15 s of
host elapsed time; the app CPU mark supplies its own 15 s window. Force-stop resets
process state but shared caches, artwork and network/server responses remain warm.
Repeat baselines and compare like-for-like; report the retained frame count and
raw failure evidence with any claimed improvement.

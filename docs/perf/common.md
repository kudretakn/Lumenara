# WP05 — Hub drawing and repeatable release measurement

Target: Mi Box 4, Android 9/API28, 2 GB RAM, 39,682 catalog items. This package changes measurement meaning and artwork correctness; it makes no measured speed claim. The binding protocol is the re-architecture plan, sections 3–5.

Use only CI-signed release APKs, the existing application ID/certificate and increasing versionCode. Install upgrades with `install -r` through the lead's normal release process. Never uninstall, clear data/cache, downgrade, generate profiles, run instrumentation or inject process interruption on the owner's TV. Fixtures and instrumentation run on emulators only. Keep source credentials, URLs and content titles out of capture notes/logs. Do not refresh sources during playback measurements.

## Draw contract

All `startup_hub_*_draw` events carry `process_ms` (process birth to draw), `uptime_ms`, `draw_ns` and `frame_vsync_ns`. `draw_ns` and vsync use the monotonic clock, not host wall time. These events execute after content drawing and are **draw recording**, not compositor presentation timestamps. Correlate the actual-vsync timestamp with external release frame evidence; do not subtract unrelated clocks.

`frame_vsync_ns` comes from the main-thread `Choreographer.FrameCallback.doFrame` timestamp, captured before traversal. The callback runs each vsync while foreground frame observation is active and is removed on stop; include its overhead in comparisons. A draw before the first callback has timestamp zero and leaves frame agreement unmeasured. No hidden Choreographer getter or JankStats dependency is used.

| Event | Meaning |
|---|---|
| `startup_hub_shell_first_draw` | Navigation shell; counts/shelves/images may still load. |
| `startup_hub_first_draw`, `startup_hub_shelves_draw` | Source-scoped recent/resume results, including empty results, composed and drawn. The legacy name releases the existing first-draw gate. |
| `startup_hub_counts_draw` | Source-scoped LIVE/MOVIE/SERIES counts drawn (zero is a result, null is loading). |
| `startup_hub_local_draw` | Shelves + counts drawn, with actual focus inside the Hub. |
| `startup_hub_artwork_draw` | All currently visible image surfaces have drawn success or a real missing/error fallback. Address resolution, loader setup, HTTP/decode and loading text are pending. Offscreen lazy-prefetch tiles do not block. |
| `startup_hub_full_draw` | Both local and artwork conditions in the same draw. This is the full-Hub marker. |

The shell/shelf gate stays independent of counts and artwork so the loader can initialize without a readiness deadlock. Image outcomes reset on request changes. The outer paging slot remains stable; its poster resolver/painter subtree resets for a new item or logo. Hub poster fallback uses the poster role (`w342` and the ordinary poster cache keys); real backdrops retain `w780`/`w1280`. Request permission remains part of both cache keys.

`ui_frames` is enabled in release and includes `FIRST_DRAW_FRAME`. It reports count, mean, maximum, bounded 1 ms upper-bound p50/p95 (10,000 ms overflow bucket), >32/>50 ms counts, first-draw count/maximum, frame budget and dropped callbacks. It emits when the foreground session stops. Dropped callbacks or missing first-frame evidence require external measurement; this summary alone does not prove presentation or startup acceptance. External gfxinfo keeps first-draw-flagged frames as well. The collector samples its bounded ring every 0.5 seconds and deduplicates frame timestamps, so a final dump does not silently discard initial frames. If the first-draw frame is still absent, its status remains unmeasured. Periodic adb/gfxinfo sampling has overhead: use identical capture conditions for comparisons and validate against the external trace.

## Capture one run

Python 3.9+ and adb are sufficient; no third-party Python packages. The tools do not install or alter app data. They verify that the supplied APK SHA-256 equals the installed base APK, capture fixed perf logs without clearing global logcat, collect external gfxinfo and bracket readable process counters using device uptime. Supply the certificate SHA-256 from CI/apksigner. `label` must identify the full source Git SHA and CI run URL; the artifact's own SHA is calculated automatically.

```sh
python3 scripts/perf/wp05_capture.py \
  --serial DEVICE --apk /path/to/ci-release.apk --output /tmp/wp05-B-cold-01 \
  --mode cold --label 'FULL_GIT_SHA CI_RUN_URL' \
  --certificate-sha256 CERT_SHA256 --catalog-items 39682 \
  --conditions 'same source/filter; display 60Hz; LAN; existing warm disk cache; thermal state noted'
```

Cold means force-stop/relaunch with retained data/cache. Warm means an already running process, HOME, then Activity foreground; use `--mode warm`. The capture ends with HOME to flush the release frame summary. An existing warm Home composition can retain one-shot stage markers; use the independent foreground event/presentation evidence for warm latency and do not report process age as warm latency. Warm counters report sample-to-sample CPU with its actual window; they are never startup CPU15.

Capture the old baseline before upgrading, then the new release; never reinstall the old APK over the upgrade. Obtain at least five process-cold and five warm runs per revision, fixed catalog, filters, display/network/cache conditions. Record the first post-upgrade run separately. Record ABI, certificate, versionCode, Android version, thermal state, counts, user-data checks and source Git SHA. Capture output contains APK bytes, decimal MB (10^6), binary MiB (2^20), SHA-256, external frames and actual CPU windows. Missing process-counter permissions remain `unmeasured`. If `getconf CLK_TCK` is absent, pass only a separately verified `--clock-ticks`; never guess it.

## External validation and CPU15

Use an Android9-compatible external sched/atrace capture spanning **before process birth through at least birth+15 s**, plus gfx/view/frame presentation evidence or an independently annotated display recording. Check trace buffer overflow/lost events. Retain the first heavy frame. Identify the first frame where shelves, counts, focus and visible success/error artwork are all presented; loading placeholders do not qualify. An image delayed past 1.5 s is a target failure, not a reason to rename full Hub. The marker must differ from that independently identified frame by at most one refresh interval. A gfxinfo histogram without visual/trace attribution cannot establish this check.

Save independent frame annotation as JSON (timestamps below are illustrative only):

```json
{"full_frame_vsync_ns":9000000000,"frame_interval_ns":16666667,"full_process_ms":1600,"evidence":"release-trace-and-annotated-frame artifact path"}
```

Use the **actual-vsync timestamp of the presented frame** (`FrameMetrics.VSYNC_TIMESTAMP`, or gfxinfo's `Vsync` column), from the same monotonic clock as the app, for marker comparison. This is the timestamp supplied to Choreographer frame callbacks; `IntendedVsync` can differ when Main is delayed. The external duration histogram still uses `IntendedVsync` through `FrameCompleted`, retaining that delay. `full_process_ms` must use presentation time relative to externally identified process birth; do not substitute the draw marker. If video is used, establish and retain its clock/frame alignment evidence.

The application `startup_cpu_diagnostic` begins at entry to Application.onCreate, emits `cpu_ms`, actual `window_ms`, `start_elapsed_ms`, and `origin=application_on_create`. It intentionally has no CPU15 label: super.onCreate is included but pre-Application CPU is absent, and coroutine delay may resume late.

For readable `/proc/PID/stat`, the cold collector uses **cumulative process user+system CPU**, including early startup and all threads, from process birth through its observed endpoint. It records endpoint uptime bounds and tick quantization. That observed window is not silently equated with exact CPU15. A denied or missing counter remains unmeasured. Use scheduler evidence for exact process-birth [0,15 s] acceptance. Export attributed running slices for **all app threads**, including short-lived startup threads, from the external trace:

```json
{"process_birth_ns":1000000000,"capture_start_ns":0,"capture_end_ns":17000000000,"lost_events":0,"all_app_threads":true,"app_tids":[42,43],"running_slices_ns":[[42,1000000000,1100000000],[43,1200000000,1500000000]],"evidence":"external scheduler trace + thread-attribution artifact path"}
```

Each slice is `[tid, start_ns, end_ns]`, and `app_tids` contains the independently attributed process threads. These slices must be complete, nonduplicated running intervals attributed by process/thread lifecycle, not thread-name guesses or wall-time percentages. The analyzer clips each slice to birth+15 s and sums concurrent threads separately. Insufficient coverage, lost events or incomplete thread attribution leaves acceptance unmeasured. Obtaining this trace and verifying attribution is a lead/device measurement step; the tool does not fabricate slices from delayed in-app samples.

```sh
python3 scripts/perf/wp05_report.py /tmp/wp05-B-cold-01 \
  --presentation /tmp/independent-frame.json --cpu-sched /tmp/attributed-sched.json
python3 -m unittest discover -s scripts/perf -p 'Wp05*Test.py' -v
```

Review raw evidence and compare p50/p95/max across paired runs. Targets: full Hub p95 <1,500 ms, CPU15 <5 CPU-seconds, each local screen p95 <1,000 ms. Retain >32/>50 ms frames and the first heavy frame. Report unrelated p95/CPU/PSS regressions >5% and repeat paired measurements when noisy. Run Hub/DPAD return, search result 60 return, player engine transitions/Home/resume, subtitles, 30-minute playback and 50-transition PSS drift through the lead's protocol; this harness's launch capture does not automate or certify those journeys. Verify sources/credentials, resume positions, favorites/Listem and settings before/after upgrade. No TV acceptance is complete until actual evidence is reviewed.

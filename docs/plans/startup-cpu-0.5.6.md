TV measurements (Mi Box 4, Android 9, 39,682 items), normal cold start, version backfill already finished:
- v0.5.4: CPU in the first 12-15 s ≈ 16 s (utime+stime ticks, 100 Hz).
- v0.5.5: CPU in the first 12 s = 2448 and 2257 ticks (≈ 23-24 s) -> regression of ~50 % vs 0.5.4.
- v0.5.5 Perf: hub_recent_query_ready 23-30 ms, hub_recent_ready 276-290 ms, hub_resume_ready 288-312 ms, screen_first_draw 686-693 ms, but hub_shell_first_draw / hub_first_draw 3196-3204 ms, hub_counts_ready 2581-2626 ms (0.5.4: 1.3 s).
0.5.5 added: TMDB alternative-title search (`TmdbMatcher.kt`, `Metadata.kt`), TMDB-id version grouping refresh (`VersionRefresh.kt`, "groups refreshed on IO in bulk"), history carry-over (`HistoryCarryOver.kt`, runs on refresh/source edit), subtitle delay, migration backfill (`VersionBackfill.kt`), Hub speed changes (`hubDetailsReady` right after first populated draw, `backgroundReady` after 20 s).

1. Find what consumes CPU in the first 12 s after a cold start in 0.5.5 (read code paths started at startup or right after first draw: TMDB matching of unmatched items, version refresh scans, carry-over checks, backfill re-checks, counts, artwork prefetch). Nothing may scan or process the whole catalog at startup; TMDB matching must only run for items the user looks at (details/visible rows), rate-limited, never in bulk at startup.
2. The Hub data is ready at ~0.3 s but the Hub is drawn at 3.2 s: find why (waiting for counts? images? `rowsDrawn` gate? skeleton until something else completes?) and make the Hub draw as soon as rows are ready; counts must not block drawing.
3. Counts regressed 1.3 s -> 2.6 s: find why (CPU contention or query change) and fix.
Keep fixed-name Perf marks; add one per heavy job you defer so the next TV run shows them. No schema change. Do NOT run Gradle, do NOT commit/push. JUnit 4 only; no `const val` inside a class.
Report: max 8 lines.

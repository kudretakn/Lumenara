# Plan: kaydırdıkça yükleme (Codex istemi)

Kullanıcı 17 Kasım 2026'da istedi. Aşağıdaki metin Codex'e verilecek istemdir (İngilizce). Worktree: `feat/lazy-grids` (main üzerinden yeniden aç), model `gpt-6.1-sol` high.

Task (user request, verbatim meaning): "When I open Movies a 'Devamını göster' (show more) button appears. Pressing it throws me back to the very top. I don't want such a button. Lazy-load: load the next items as I scroll down. If I scroll far down, the items far above must not keep occupying memory/cache."

Where the button exists (remove ALL of them):
- `app/src/main/java/dev/lumenara/tv/Browse.kt:~574` Movies/Series poster grid: `Button(onClick = { limit += POSTER_PAGE_SIZE }) { Text("Devamını göster") }` (POSTER_GRID_COLUMNS = 8). Main target.
- `SearchScreen.kt:~236` search results (`limit += 80`).
- `LiveScreen.kt:~262` live grid.
- `Playback.kt:~771` player side list (`limit += 80`).

Required behaviour:
1. Windowed paging like the live grid already does (`LivePages.kt`: indexed LIMIT/OFFSET or keyset pages, LIVE_PAGE_SIZE; first page via indexed `LIMIT` query — reuse/generalise it instead of inventing a second mechanism). As the focused/visible row approaches the end of the loaded window (e.g. 2 rows before), load the next page automatically. No button.
2. Keep only a bounded window in memory (e.g. current page ± 2 pages); pages far above/below are dropped and re-queried when the user scrolls back. Use stable placeholders/keys for not-loaded slots so the LazyVerticalGrid keeps total item count, scroll position and focus — focus must NEVER jump to the top when pages load or are evicted.
3. Image memory: ensure Coil requests for evicted/off-screen posters are not retained beyond the existing memory cache policy (posters off-screen are fine to drop from the memory cache; disk cache stays).
4. Total count shown in the header can load lazily after first draw (as in the live grid).
5. Filters/sort (Filtrele) and +18 filter must still work; changing them resets to the top intentionally.
6. Tests: unit tests for the window/eviction logic (JUnit 4); update/add device tests (keep testTags) asserting: no "Devamını göster" node exists; scrolling with DPAD_DOWN past the first page loads more and focus stays on the next row; scrolling far down evicts early pages (window size bounded) and scrolling back up restores them with focus intact.

Rules: Read CLAUDE.md first. Work only in this worktree. Do NOT run Gradle, do NOT commit or push. JUnit 4 `@Test` classes only; no `const val` inside a class; never hard-code dates; no Unicode icon glyphs (✓ ★ ▶ • ← →) in production sources. Target: Mi Box 4 (Android 9, 2 GB), catalog 17,183 movies / 4,716 series / 10,645 channels. Do not change the visual card design (sizes from 0.5.2).
Report: max 12 lines — mechanism, files, window size, tests.


# Source add stalls on "Kaynak işleniyor…" (0.5.7)

User report (20 Nov 2026, Mi Box 4): adding an Xtream source with server address `http://tinyurl.com/vipadel` (short URL), HTTP compatibility on. The button stays on "Kaynak işleniyor…" indefinitely; no error, no list. At the same time the "Telefonla ekle" panel shows "Oturum açılamadı. TV ve telefonu aynı yerel ağa bağlayın." (`PairSession.create` failed: active network is not Wi-Fi/Ethernet, e.g. a VPN, or has no IPv4).

Code facts (read before changing):
- `Setup.kt` -> `LibraryModel.job` -> `Repository.add` -> `lock.withLock { import(...) }` (`core/data/.../Repository.kt`). The UI only shows a busy flag; no stage, no progress, no cancel, no overall timeout.
- `Xtream.prepare` downloads `player_api.php` account + 6 bodies sequentially via `Transport.download`. OkHttp has only connect 10 s / read 25 s timeouts: a slowly trickling server never fails.
- `Xtream.api()` appends `/player_api.php` to the user's base. With a short URL (tinyurl, bit.ly…) the path is appended to the shortener, which does not forward paths; every call also goes through the shortener.
- `Repository.lock` is also held by the version backfill loop, `details`/`subtitleQuery` TMDB writes and `checkUpdate` (network inside the lock). An add can wait behind them with no feedback.

Tasks (model: gpt-6.1-sol high):
1. Root cause first: reproduce the stall paths in unit tests (MockWebServer): shortener base that 301/302s to the real host, slow trickle body, lock held by another job. Report which actually stalls.
2. Short URL: when adding/editing an Xtream source, resolve the base once (follow redirects on the bare base URL, respect `allowHttp`), keep scheme+host+port(+path prefix) of the final URL as the stored base; show the resolved address in the form. If resolution fails, show a clear Turkish error.
3. Progress: expose a stage flow from import (e.g. "Hesap doğrulanıyor", "Canlı kanallar indiriliyor · 12 MB", "Filmler indiriliyor", "Diziler indiriliyor", "İçe aktarılıyor · 15.000 / 39.682") and show it in Setup (and Settings refresh) instead of the static text. Show "Sırada bekleniyor…" while waiting for the repository lock.
4. Cancel button while busy (cancels the job; a cancelled add leaves no partial source). Overall per-request deadline (e.g. OkHttp `callTimeout` sized for big bodies, ~3 min) and a whole-add deadline with a clear message.
5. Lock hygiene: background backfill must yield to a user add (check a "user job waiting" flag between batches); `checkUpdate` must not hold the lock during network I/O if not required.
6. Pairing message: distinguish "VPN active" / "no network" / "no IPv4" in `PairSession.create` errors.

Acceptance: unit tests for 1, 2, 4 (JUnit 4, no fixed dates, no `const val` in classes); on the TV the short URL adds successfully or fails within the deadline with a clear message; progress visible during the 39k import; cancel works. Do NOT run Gradle, do NOT commit/push. Report: max 8 lines.

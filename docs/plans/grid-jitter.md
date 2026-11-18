User report (18 Nov 2026, v0.5.5 on Mi Box 4): "When I go down to the lower rows and move right/left, the screen jitters slightly." Fix in the next session (Codex, gpt-6.1-sol medium).

Scope: all focusable grids, Movies/Series poster grid first (`Browse.kt`, POSTER_GRID_COLUMNS = 8, windowed paging from `CatalogPages.kt`/`PagingWindow.kt`), then Live channel grid and category grids (`LiveScreen.kt`, `CategoryGrid.kt`, 4 columns, 60 dp cards), search results and Hub shelves.

Likely causes to check:
1. Only the focused poster shows its title + year below the card (screenshots: "Alita: Sa... / 2019" under the focused card). That changes the row height when focus moves, and `bringIntoView` then re-scrolls the grid by a few pixels on every horizontal step. Fix: reserve the title area for every card (fixed row height) or draw the label as an overlay that does not affect layout.
2. Focus scale (FocusCard scale/border) combined with the default `BringIntoViewSpec`/pivot scrolling of TV lazy grids: every focus change re-requests scrolling. Fix: a custom `BringIntoViewSpec` (or pivot offsets) that keeps the vertical offset unchanged when focus moves within the same row, and scrolls only when the row changes.
3. Windowed paging: placeholder slots and real cards must have exactly the same size; image load must not change the card size (fixed aspect ratio).
4. Animated content/size (`animateContentSize`, crossfade) inside cards.

Acceptance (verify on the TV with screen recording or repeated screenshots): moving left/right within any row, including the last visible rows and after scrolling far down, does not move the list vertically at all; the list scrolls only when the row changes; focus never jumps. Add a device test that moves DPAD_RIGHT across a lower row and asserts the grid's first visible item index and scroll offset stay unchanged.

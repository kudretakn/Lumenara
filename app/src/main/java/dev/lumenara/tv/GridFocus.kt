package dev.lumenara.tv

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Find the previous row's cell containing the current column, including wide cards.
 * A short row falls back to its last cell.
 */
internal fun previousGridRow(index: Int, column: (Int) -> Int, span: (Int) -> Int): Int? {
    var first = index
    while (first > 0 && column(first - 1) < column(first)) first--
    if (first == 0) return null
    val last = first - 1
    var candidate = last
    while (true) {
        if (column(index) in column(candidate) until column(candidate) + span(candidate)) return candidate
        if (candidate == 0 || column(candidate) == 0) return last
        candidate--
    }
}

/** UP beyond the viewport composes the preceding row backwards, placing temporary
 * cells in lane zero. Reveal the actual destination before requesting its focus.
 * The old card keeps focus while that row is composed; horizontal navigation stays still.
 */
internal class GridFocus(private val grid: LazyGridState, private val scope: CoroutineScope,
    private val column: (Int) -> Int, private val span: (Int) -> Int) {
    private var focusedIndex = -1
    private var moving = false
    private val requesters = mutableStateMapOf<Int, FocusRequester>()

    val modifier: Modifier = Modifier.onPreviewKeyEvent { event ->
        if (event.key != Key.DirectionUp || event.type != KeyEventType.KeyDown || focusedIndex < 0) false
        else if (moving) true
        else previousGridRow(focusedIndex, column, span)?.let { target ->
            moving = true
            scope.launch {
                try {
                    if (grid.layoutInfo.visibleItemsInfo.none { it.index == target }) grid.scrollToItem(target)
                    snapshotFlow { requesters[target] }.filterNotNull().first().requestFocus()
                } finally { moving = false }
            }
            true
        } ?: false // The first row can still navigate to the header.
    }

    @Composable
    fun item(index: Int): Modifier {
        val requester = remember(this, index) { FocusRequester() }
        DisposableEffect(this, index, requester) {
            requesters[index] = requester
            onDispose { requesters.remove(index) }
        }
        return Modifier.focusRequester(requester).onFocusChanged { if (it.hasFocus) focusedIndex = index }
    }
}

@Composable
internal fun rememberGridFocus(grid: LazyGridState, span: (Int) -> Int = { 1 }, column: (Int) -> Int): GridFocus {
    val currentColumn by rememberUpdatedState(column)
    val currentSpan by rememberUpdatedState(span)
    val scope = rememberCoroutineScope()
    return remember(grid, scope) { GridFocus(grid, scope, { currentColumn(it) }, { currentSpan(it) }) }
}

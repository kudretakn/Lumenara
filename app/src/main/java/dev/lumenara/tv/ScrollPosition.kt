package dev.lumenara.tv

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics

// Exact lazy position for DPAD device regressions; pixel bounds include focus drawing.
internal val ScrollPositionKey = SemanticsPropertyKey<String>("ScrollPosition")
internal var SemanticsPropertyReceiver.scrollPosition by ScrollPositionKey

internal fun Modifier.reportScrollPosition(state: LazyGridState) = semantics {
    scrollPosition = "${state.firstVisibleItemIndex}:${state.firstVisibleItemScrollOffset}"
}

internal fun Modifier.reportScrollPosition(state: LazyListState) = semantics {
    scrollPosition = "${state.firstVisibleItemIndex}:${state.firstVisibleItemScrollOffset}"
}

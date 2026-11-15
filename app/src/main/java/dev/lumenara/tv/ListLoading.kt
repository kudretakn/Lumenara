package dev.lumenara.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lumenara.design.LumenColors
import kotlinx.coroutines.flow.Flow

/** A replacement query starts unknown, even when the previous query had results. */
@Composable
internal fun <T> Flow<T>.collectAsLoadingState(): State<T?> = key(this) {
    collectAsStateWithLifecycle(initialValue = null)
}

@Composable
internal fun LoadingCard(modifier: Modifier = Modifier) {
    // Static, non-focusable cards avoid animation work and loading-text flicker.
    Box(modifier.background(LumenColors.divider.copy(alpha = .25f), RoundedCornerShape(12.dp))
        .testTag("list-loading"))
}

internal fun LazyGridScope.loadingCards(height: Dp) {
    items(8, key = { "loading-$it" }) {
        LoadingCard(Modifier.fillMaxWidth().height(height))
    }
}

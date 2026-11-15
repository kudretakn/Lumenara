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
import dev.lumenara.design.Radii
import kotlinx.coroutines.flow.Flow

/** A replacement query starts unknown, even when the previous query had results. */
@Composable
internal fun <T> Flow<T>.collectAsLoadingState(): State<T?> = key(this) {
    collectAsStateWithLifecycle(initialValue = null)
}

@Composable
internal fun LoadingCard(modifier: Modifier = Modifier) {
    // Static, non-focusable cards avoid animation work and loading-text flicker.
    Box(modifier.background(LumenColors.surface2, RoundedCornerShape(Radii.card))
        .testTag("list-loading"))
}

internal fun LazyGridScope.loadingCards(height: Dp, count: Int = 8) {
    items(count, key = { "loading-$it" }) {
        LoadingCard(Modifier.fillMaxWidth().height(height))
    }
}

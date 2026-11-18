package dev.lumenara.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import kotlin.math.abs

/** Keep visible rows/shelves still; only reveal a target when it crosses an edge.
 * Unlike the TV pivot default, this also works for nested horizontal shelves.
 * FocusCard uses canvas scaling so these coordinates never include its animation.
 */
@OptIn(ExperimentalFoundationApi::class)
internal object StableFocusBringIntoViewSpec : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size - containerSize
        return when {
            offset >= 0f && end <= 0f -> 0f
            offset < 0f && end > 0f -> 0f // Oversized target already covers the viewport.
            abs(offset) < abs(end) -> offset
            else -> end
        }
    }
}

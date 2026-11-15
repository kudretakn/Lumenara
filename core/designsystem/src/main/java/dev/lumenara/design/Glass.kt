package dev.lumenara.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape

@Deprecated("Use Panel or an opaque surface1 background")
fun Modifier.glass(shape: Shape = RoundedCornerShape(Radii.panel)): Modifier =
    clip(shape).background(LumenColors.surface1)

/** The legacy backdrop parameter is retained for source compatibility, but is not drawn. */
@Suppress("UNUSED_PARAMETER")
@Deprecated("Use Panel", ReplaceWith("Panel(modifier, shape, content)"))
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radii.panel),
    backdrop: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) = Panel(modifier, shape, content)

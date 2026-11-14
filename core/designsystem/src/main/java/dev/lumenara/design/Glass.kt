package dev.lumenara.design

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** Translucent fill and edge; never blur the panel's text or focus ring. */
fun Modifier.glass(shape: Shape = RoundedCornerShape(16.dp)): Modifier =
    clip(shape).background(Ink.glassStrong).background(Ink.glass).border(1.dp, Ink.stroke, shape)

/** Optional background is a separate layer so only fixed panel artwork is blurred. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    backdrop: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.clip(shape)) {
        if (backdrop != null) {
            val blur = if (Build.VERSION.SDK_INT >= 31) Modifier.blur(20.dp) else Modifier
            Box(Modifier.matchParentSize().then(blur)) { backdrop() }
        }
        Box(Modifier.matchParentSize().glass(shape))
        content()
    }
}

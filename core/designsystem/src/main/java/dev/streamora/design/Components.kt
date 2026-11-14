package dev.streamora.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*

@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(2.dp, Ink.focusRing), shape = shape)
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Ink.focusRing.copy(alpha = .18f), 4.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Ink.surface),
        content = content,
    )
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier.semantics {
            this.selected = selected
            contentDescription = text
        },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Ink.stroke), shape = RoundedCornerShape(50)),
            focusedBorder = Border(BorderStroke(2.dp, Ink.focusRing), shape = RoundedCornerShape(50)),
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Ink.glass else Color.Transparent,
            contentColor = if (selected) Ink.text else Ink.muted,
            focusedContainerColor = Ink.focus,
            focusedContentColor = Ink.background,
        ),
    ) {
        Text((if (selected) "✓ " else "") + text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(3.dp).background(Ink.stroke)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().background(Ink.focusRing))
    }
}

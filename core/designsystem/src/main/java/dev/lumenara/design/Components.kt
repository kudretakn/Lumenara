package dev.lumenara.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*

/** The actual default colours passed to Surface, also read by the contrast test. */
object FocusCardDefaults {
    val containerColor = LumenColors.surface2
    val focusedContainerColor = containerColor
    val contentColor = LumenColors.textPrimary
    val focusedContentColor = contentColor
    val secondaryContentColor = LumenColors.textSecondary
}

/** Scale is a centred drawing transform: focus never changes a grid's layout. */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    focusedScale: Float = Motion.cardFocusScale,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val focus by animateFloatAsState(
        if (focused && enabled) 1f else 0f,
        tween(focusDuration(focused && enabled), easing = Motion.focusEasing),
        label = "cardFocus",
    )
    val shape = RoundedCornerShape(Radii.card)
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = enabled,
        interactionSource = interactions,
        modifier = modifier
            .graphicsLayer {
                scaleX = 1f + (focusedScale - 1f) * focus
                scaleY = scaleX
            }
            .drawWithContent {
                drawContent()
                // Outside the fill, with no gap; drawing does not affect measurement.
                val width = Motion.focusRingWidth.toPx()
                drawRoundRect(
                    color = LumenColors.focus.copy(alpha = focus),
                    topLeft = Offset(-width / 2, -width / 2),
                    size = Size(size.width + width, size.height + width),
                    cornerRadius = CornerRadius(Radii.card.toPx() + width / 2),
                    style = Stroke(width),
                )
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = flatScale(),
        border = noBorder(),
        glow = noGlow(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = FocusCardDefaults.containerColor,
            contentColor = FocusCardDefaults.contentColor,
            // tv-material otherwise substitutes its light default on focus.
            focusedContainerColor = FocusCardDefaults.focusedContainerColor,
            focusedContentColor = FocusCardDefaults.focusedContentColor,
            pressedContainerColor = FocusCardDefaults.containerColor,
            pressedContentColor = FocusCardDefaults.contentColor,
            disabledContainerColor = FocusCardDefaults.containerColor,
            disabledContentColor = LumenColors.textTertiary,
        ),
        content = content,
    )
}

enum class LumenButtonStyle { Primary, Secondary }

/** Primary stays white; secondary turns white on focus. Both have 8 dp corners. */
@Composable
fun LumenButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: LumenButtonStyle = LumenButtonStyle.Primary,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val light = enabled && (focused || style == LumenButtonStyle.Primary)
    val fill by animateColorAsState(
        if (light) LumenColors.focus else LumenColors.surface2,
        tween(focusDuration(focused), easing = Motion.focusEasing), label = "buttonFill",
    )
    val foreground by animateColorAsState(
        when {
            !enabled -> LumenColors.textTertiary
            light -> LumenColors.onFocus
            else -> LumenColors.textPrimary
        },
        tween(focusDuration(focused), easing = Motion.focusEasing), label = "buttonText",
    )
    FlatControl(
        onClick, modifier.heightIn(min = 48.dp).semantics { role = Role.Button },
        enabled, interactions, RoundedCornerShape(Radii.button), fill, foreground,
    ) {
        ProvideTextStyle(LumenType.label) {
            Row(
                Modifier.padding(horizontal = Spacing.cardGap, vertical = Spacing.unit),
                horizontalArrangement = Arrangement.spacedBy(Spacing.iconText, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    LumenButton(
        onClick = onClick,
        modifier = modifier.semantics {
            this.selected = selected
            contentDescription = text
        },
        style = LumenButtonStyle.Secondary,
        enabled = enabled,
    ) {
        if (selected) LumenIcon(LumenIcon.Check, contentDescription = null)
        Text(text, style = LumenType.label)
    }
}

/** Focus uses a white left bar; a selected/playing row keeps a gold indicator. */
@Composable
fun ListRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val fill by animateColorAsState(
        if (focused && enabled) LumenColors.surface3 else LumenColors.surface1,
        tween(focusDuration(focused), easing = Motion.focusEasing), label = "rowFill",
    )
    val bar by animateColorAsState(
        when {
            focused && enabled -> LumenColors.focus
            selected -> LumenColors.accent
            else -> Color.Transparent
        },
        tween(focusDuration(focused), easing = Motion.focusEasing), label = "rowBar",
    )
    FlatControl(
        onClick, modifier.fillMaxWidth().heightIn(min = 64.dp)
            .semantics { this.selected = selected }
            .drawWithContent {
                drawContent()
                drawRect(bar, size = Size(Motion.focusRingWidth.toPx(), size.height))
            },
        enabled, interactions, RoundedCornerShape(Radii.button), fill,
        if (enabled) LumenColors.textPrimary else LumenColors.textTertiary,
    ) {
        ProvideTextStyle(LumenType.body) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.cardGap, vertical = Spacing.unit),
                horizontalArrangement = Arrangement.spacedBy(Spacing.unit),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

/** Opaque by construction: no backdrop layers, elevation, or blur. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radii.panel),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.clip(shape).background(LumenColors.surface1), content = content)
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    val value = if (progress.isNaN()) 0f else progress.coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(Radii.progress))
            .background(LumenColors.divider)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value, 0f..1f) },
    ) {
        Box(Modifier.fillMaxWidth(value).fillMaxHeight().background(LumenColors.accent))
    }
}

@Deprecated("Use Chip", ReplaceWith("Chip(text, selected, onClick, modifier)"))
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Chip(text, selected, onClick, modifier)

private fun focusDuration(focused: Boolean) =
    if (focused) Motion.focusEnterMillis else Motion.focusExitMillis

private fun flatScale() = ClickableSurfaceDefaults.scale(
    scale = 1f, focusedScale = 1f, pressedScale = 1f, disabledScale = 1f, focusedDisabledScale = 1f,
)

@Composable
private fun noBorder() = ClickableSurfaceDefaults.border(
    border = Border.None, focusedBorder = Border.None, pressedBorder = Border.None,
    disabledBorder = Border.None, focusedDisabledBorder = Border.None,
)

private fun noGlow() = ClickableSurfaceDefaults.glow(
    glow = Glow.None, focusedGlow = Glow.None, pressedGlow = Glow.None,
)

/** Paint our timed fill outside Surface; all of its own state fills are transparent. */
@Composable
private fun FlatControl(
    onClick: () -> Unit,
    modifier: Modifier,
    enabled: Boolean,
    interactions: MutableInteractionSource,
    shape: Shape,
    fill: Color,
    foreground: Color,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.background(fill, shape),
        enabled = enabled,
        interactionSource = interactions,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = flatScale(), border = noBorder(), glow = noGlow(),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, focusedContainerColor = Color.Transparent,
            pressedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
            contentColor = foreground, focusedContentColor = foreground,
            pressedContentColor = foreground, disabledContentColor = foreground,
        ),
        content = content,
    )
}

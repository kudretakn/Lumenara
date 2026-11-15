package dev.lumenara.design

import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@Deprecated("Use LumenColors; retained until all screens migrate to Quiet Cinema")
object Ink {
    val background = LumenColors.bg
    val secondary = LumenColors.surface1
    val surface = LumenColors.surface2
    val focus = LumenColors.focus
    val text = LumenColors.textPrimary
    val muted = LumenColors.textSecondary
    val glass = LumenColors.surface2
    val glassStrong = LumenColors.surface1
    val stroke = LumenColors.divider
    val focusRing = LumenColors.focus
    val accent = LumenColors.accent
}

@Composable
fun LumenaraTheme(content: @Composable () -> Unit) =
    MaterialTheme(
        typography = LumenType.typography,
        colorScheme =
            darkColorScheme(
                primary = LumenColors.focus,
                onPrimary = LumenColors.onFocus,
                secondary = LumenColors.accent,
                onSecondary = LumenColors.bg,
                background = LumenColors.bg,
                surface = LumenColors.surface2,
                surfaceVariant = LumenColors.surface3,
                onSurfaceVariant = LumenColors.textPrimary,
                onSurface = LumenColors.textPrimary,
                onBackground = LumenColors.textPrimary,
                error = LumenColors.error,
            ),
        content = {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.tv.material3.LocalContentColor provides LumenColors.textPrimary,
                content = content,
            )
        },
    )

@Deprecated("Use LumenType.typography", ReplaceWith("LumenType.typography"))
val LumenaraTypography = LumenType.typography

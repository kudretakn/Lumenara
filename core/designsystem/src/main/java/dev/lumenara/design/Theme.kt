package dev.lumenara.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

@OptIn(ExperimentalFoundationApi::class)
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
                LocalBringIntoViewSpec provides StableFocusBringIntoViewSpec,
                content = content,
            )
        },
    )

@Deprecated("Use LumenType.typography", ReplaceWith("LumenType.typography"))
val LumenaraTypography = LumenType.typography

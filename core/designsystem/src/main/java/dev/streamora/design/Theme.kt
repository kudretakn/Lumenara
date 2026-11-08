package dev.streamora.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object Ink {
    val background = Color(0xFF080B0F)
    val secondary = Color(0xFF14191F)
    val surface = Color(0xFF242B33)
    val focus = Color(0xFFE6E9EF)
    val text = Color(0xFFF5F6F8)
    val muted = Color(0xFFA7ADB8)
    val accent = Color(0xFFC9D6DD)
}

@Composable
fun StreamoraTheme(content: @Composable () -> Unit) =
    MaterialTheme(
        colorScheme =
            darkColorScheme(
                primary = Ink.accent,
                background = Ink.background,
                surface = Ink.surface,
                surfaceVariant = Ink.surface,
                onSurfaceVariant = Ink.text,
                onSurface = Ink.text,
                onBackground = Ink.text,
            ),
        content = {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.tv.material3.LocalContentColor provides Ink.text,
                content = content,
            )
        },
    )

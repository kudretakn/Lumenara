package dev.lumenara.design

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

object Ink {
    val background = Color(0xFF080B0F)
    val secondary = Color(0xFF14191F)
    val surface = Color(0xFF242B33)
    val focus = Color(0xFFE6E9EF)
    val text = Color(0xFFF5F6F8)
    val muted = Color(0xFFA7ADB8)
    val glass = Color.White.copy(alpha = .10f)
    val glassStrong = Color(0xE0080B0F)
    val stroke = Color.White.copy(alpha = .14f)
    val focusRing = Color.White
    val accent = Color(0xFFC9D6DD)
}

@Composable
fun LumenaraTheme(content: @Composable () -> Unit) =
    MaterialTheme(
        typography = LumenaraTypography,
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

val LumenaraTypography =
    androidx.tv.material3.Typography().let {
        it.copy(
            displayLarge = it.displayLarge.copy(fontSize = 44.sp),
            titleLarge = it.titleLarge.copy(fontSize = 28.sp),
            headlineLarge = it.headlineLarge.copy(fontSize = 20.sp),
            bodyLarge = it.bodyLarge.copy(fontSize = 15.sp),
            labelSmall = it.labelSmall.copy(fontSize = 12.sp),
        )
    }

package dev.lumenara.design

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusCardContrastTest {
    @Test
    fun focusPreservesTheDarkFillAndPrimaryTextColor() {
        assertEquals("Focus must not replace a card's dark fill", LumenColors.surface2, FocusCardDefaults.focusedContainerColor)
        assertEquals("Focus must not change card text colour", LumenColors.textPrimary, FocusCardDefaults.focusedContentColor)
        assertEquals(FocusCardDefaults.containerColor, FocusCardDefaults.focusedContainerColor)
        assertEquals(FocusCardDefaults.contentColor, FocusCardDefaults.focusedContentColor)
        assertEquals(255, alpha(FocusCardDefaults.focusedContainerColor.toArgb()))
    }

    @Test
    fun focusedCardTextMeetsWcagAaContrast() {
        assertReadable("primary", FocusCardDefaults.focusedContentColor.toArgb(), FocusCardDefaults.focusedContainerColor.toArgb())
        // This is the metadata colour that became unreadable on Material's light focus fill.
        assertReadable("secondary", FocusCardDefaults.secondaryContentColor.toArgb(), FocusCardDefaults.focusedContainerColor.toArgb())
    }

    private fun assertReadable(role: String, foreground: Int, background: Int) {
        assertEquals("$role text must be opaque", 255, alpha(foreground))
        val foregroundLuminance = luminance(foreground)
        val backgroundLuminance = luminance(background)
        val ratio = (maxOf(foregroundLuminance, backgroundLuminance) + 0.05) /
            (minOf(foregroundLuminance, backgroundLuminance) + 0.05)
        assertTrue("Focused card $role text contrast $ratio is below 4.5:1", ratio >= 4.5)
    }

    private fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF

    private fun luminance(argb: Int): Double {
        fun linear(channel: Int): Double {
            val srgb = channel / 255.0
            return if (srgb <= 0.04045) srgb / 12.92 else Math.pow((srgb + 0.055) / 1.055, 2.4)
        }

        val red = linear((argb ushr 16) and 0xFF)
        val green = linear((argb ushr 8) and 0xFF)
        val blue = linear(argb and 0xFF)
        return 0.2126 * red + 0.7152 * green + 0.0722 * blue
    }
}

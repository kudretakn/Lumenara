package dev.lumenara.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Quiet Cinema palette. Accent is reserved for small indicators and progress. */
object LumenColors {
    val bg = Color(0xFF0B0B0D)
    val surface1 = Color(0xFF141518)
    val surface2 = Color(0xFF1C1D21)
    val surface3 = Color(0xFF26282D)
    val divider = Color.White.copy(alpha = .08f)
    val textPrimary = Color(0xFFF4F4F5)
    val textSecondary = Color(0xFFA3A5AC)
    val textTertiary = Color(0xFF6C6F76)
    val accent = Color(0xFFE8B65A)
    val focus = Color.White
    val onFocus = bg
    val live = Color(0xFFE5484D)
    val success = Color(0xFF46A758)
    /** Always pair an error with an icon, so colour is not its only signal. */
    val error = live
    val scrim = bg.copy(alpha = .92f)
    val scrimTransparent = bg.copy(alpha = 0f)
    val dim = bg.copy(alpha = .60f)
}

/** Reuse these brushes; panels themselves always use opaque surface1. */
object LumenBrushes {
    val scrim = Brush.verticalGradient(listOf(LumenColors.scrimTransparent, LumenColors.scrim))
    val detailLeft = Brush.horizontalGradient(listOf(LumenColors.bg.copy(alpha = .95f), LumenColors.scrimTransparent))
    val detailBottom = Brush.verticalGradient(listOf(LumenColors.scrimTransparent, LumenColors.bg))
}

object Spacing {
    val iconText = 4.dp
    val unit = 8.dp
    val cardGap = 16.dp
    val titleGap = 24.dp
    val shelfGap = 32.dp
    val screenHorizontal = 48.dp
    val screenVertical = 27.dp
}

object Radii {
    val card = 12.dp
    val button = 8.dp
    val chip = 8.dp
    val panel = 16.dp
    val channelLogo = 8.dp
    val progress = 2.dp
}

object Motion {
    const val focusEnterMillis = 150
    const val focusExitMillis = 100
    val focusEasing = FastOutSlowInEasing
    const val cardFocusScale = 1.06f
    const val hubFocusScale = 1.04f
    val focusRingWidth = 3.dp
    const val screenCrossfadeMillis = 200
    const val panelEnterMillis = 220
    val panelSlide = 24.dp
    const val backdropCrossfadeMillis = 300
    const val backdropFocusDelayMillis = 250
    const val bannerAutoHideMillis = 4_000
    const val bannerFadeMillis = 200
    const val toastDurationMillis = 2_500
}

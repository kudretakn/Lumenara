package dev.lumenara.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

object LumenType {
    // Resource fonts otherwise block the first text measure on Main. Each Inter face
    // is about 600 KB: let Compose load requested weights off Main, using fallback meanwhile.
    val inter = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal, loadingStrategy = FontLoadingStrategy.Async),
        Font(R.font.inter_medium, FontWeight.Medium, loadingStrategy = FontLoadingStrategy.Async),
        Font(R.font.inter_semibold, FontWeight.SemiBold, loadingStrategy = FontLoadingStrategy.Async),
        Font(R.font.inter_bold, FontWeight.Bold, loadingStrategy = FontLoadingStrategy.Async),
    )

    private fun style(size: Int, weight: FontWeight) = TextStyle(
        fontFamily = inter,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = (size * 1.25f).sp,
    )

    val display = style(48, FontWeight.Bold).copy(letterSpacing = (-.5).sp)
    val headline = style(32, FontWeight.SemiBold)
    val title = style(22, FontWeight.SemiBold)
    val cardTitle = style(18, FontWeight.Medium)
    val body = style(18, FontWeight.Normal)
    val label = style(16, FontWeight.SemiBold)
    val meta = style(15, FontWeight.Normal).copy(color = LumenColors.textSecondary)
    /** Use uppercase section text with this style (for example, DEVAM ET). */
    val caption = style(14, FontWeight.Medium).copy(letterSpacing = .8.sp)

    val typography = Typography(
        displayLarge = display,
        displayMedium = display,
        displaySmall = display,
        headlineLarge = headline,
        headlineMedium = headline,
        headlineSmall = headline,
        titleLarge = title,
        titleMedium = cardTitle,
        titleSmall = cardTitle,
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = meta,
        labelLarge = label,
        labelMedium = label,
        labelSmall = caption,
    )
}

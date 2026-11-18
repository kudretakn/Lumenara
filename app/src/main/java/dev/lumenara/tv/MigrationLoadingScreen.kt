package dev.lumenara.tv

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.lumenara.design.LumenColors
import dev.lumenara.design.LumenType
import dev.lumenara.design.Spacing

/** Composes before the repository is ready, including old releases' index migrations. */
@Composable
internal fun MigrationLoadingScreen() {
    val transition = rememberInfiniteTransition(label = "migration-progress")
    val angle by transition.animateFloat(0f, 360f,
        infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "migration-angle")
    Column(Modifier.fillMaxSize().background(LumenColors.bg).testTag("migration-loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Canvas(Modifier.size(32.dp).semantics {
            progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
        }.testTag("migration-progress")) {
            drawArc(LumenColors.accent, angle, 270f, false,
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        Spacer(Modifier.height(Spacing.cardGap))
        Text("Lumenara güncelleniyor\u2026", style = LumenType.label, color = LumenColors.textPrimary)
    }
}

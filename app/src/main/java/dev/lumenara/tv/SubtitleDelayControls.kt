package dev.lumenara.tv

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.tv.material3.Text
import dev.lumenara.data.clampSubtitleDelay
import dev.lumenara.data.formatSubtitleDelay
import dev.lumenara.design.LumenType
import dev.lumenara.design.ListRow
import dev.lumenara.design.Spacing

internal val SubtitleEngineDelayUs = SemanticsPropertyKey<Long>("SubtitleEngineDelayUs")

@Composable
internal fun SubtitleDelayControls(delayMs: Long, enabled: Boolean, change: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
        Text("Altyazı gecikmesi · ${formatSubtitleDelay(delayMs)}", style = LumenType.label,
            modifier = Modifier.testTag("subtitle-delay-value"))
        for (step in listOf(100L, 1000L)) Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
            for (direction in listOf(-1, 1)) {
                val delta = step * direction
                ListRow(
                    onClick = { change(clampSubtitleDelay(delayMs + delta)) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f).testTag("subtitle-delay-${if (direction < 0) "minus" else "plus"}-$step"),
                ) { Text(formatSubtitleDelay(delta)) }
            }
        }
        PlayerOption(onClick = { change(0L) }, enabled = enabled,
            modifier = Modifier.testTag("subtitle-delay-reset")) { Text("Sıfırla") }
    }
}

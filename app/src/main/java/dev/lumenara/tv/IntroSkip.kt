package dev.lumenara.tv

import androidx.compose.runtime.*
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.lumenara.catalog.canSkipIntro
import dev.lumenara.data.Item
import kotlinx.coroutines.flow.collect

@Composable
internal fun IntroSkip(item: Item, position: () -> Long, duration: () -> Long, skip: () -> Unit) {
    val currentPosition by rememberUpdatedState(position)
    val currentDuration by rememberUpdatedState(duration)
    val visible by
        produceState(false, item.id) {
            wallClockTicks(400).collect {
                value =
                    canSkipIntro(
                        item.introStartMs,
                        item.introEndMs,
                        currentPosition(),
                        currentDuration(),
                    )
            }
        }
    if (visible)
        Button(
            onClick = {
                if (
                    canSkipIntro(
                        item.introStartMs,
                        item.introEndMs,
                        currentPosition(),
                        currentDuration(),
                    )
                )
                    skip()
            }
        ) {
            Text("İntroyu atla")
        }
}

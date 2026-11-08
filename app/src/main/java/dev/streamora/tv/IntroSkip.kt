package dev.streamora.tv

import androidx.compose.runtime.*
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.streamora.catalog.canSkipIntro
import dev.streamora.data.Item
import kotlinx.coroutines.delay

@Composable
internal fun IntroSkip(item: Item, position: () -> Long, duration: () -> Long, skip: () -> Unit) {
    val currentPosition by rememberUpdatedState(position)
    val currentDuration by rememberUpdatedState(duration)
    val visible by
        produceState(false, item.id) {
            while (true) {
                value =
                    canSkipIntro(
                        item.introStartMs,
                        item.introEndMs,
                        currentPosition(),
                        currentDuration(),
                    )
                delay(400)
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

package dev.lumenara.tv

import androidx.compose.runtime.*
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.lumenara.catalog.canSkipIntro
import dev.lumenara.data.Item

@Composable
internal fun IntroSkip(item: Item, position: Long, duration: Long, skip: () -> Unit) {
    val visible = canSkipIntro(item.introStartMs, item.introEndMs, position, duration)
    if (visible)
        Button(
            onClick = {
                if (
                    canSkipIntro(
                        item.introStartMs,
                        item.introEndMs,
                        position,
                        duration,
                    )
                )
                    skip()
            }
        ) {
            Text("İntroyu atla")
        }
}

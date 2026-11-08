package dev.streamora.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import dev.streamora.catalog.searchable
import dev.streamora.data.*
import dev.streamora.design.Ink
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun Timeline(
    model: LibraryModel,
    source: String,
    open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit,
    back: () -> Unit,
) {
    var window by remember {
        mutableStateOf(ZonedDateTime.now().withMinute(0).withSecond(0).withNano(0))
    }
    var query by remember { mutableStateOf("") }
    var max by remember { mutableIntStateOf(80) }
    var selection by remember(window, query) { mutableStateOf<Pair<Item, Slot>?>(null) }
    val channels by
        remember(source, max) { model.repo.library.browse(source, "LIVE", "", "", "", max) }
            .collectAsStateWithLifecycle(emptyList())
    val from = window.toInstant().toEpochMilli()
    val to = window.plusHours(2).toInstant().toEpochMilli()
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())
    BackHandler { if (selection == null) back() else selection = null }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Button(onClick = back) { Text("Kanallar") }
            Button(onClick = { window = window.minusDays(1) }) { Text("−1 gün") }
            Button(onClick = { window = window.minusHours(2) }) { Text("‹ 2 sa") }
            Button(
                onClick = { window = ZonedDateTime.now().withMinute(0).withSecond(0).withNano(0) }
            ) {
                Text("Şimdi")
            }
            Button(onClick = { window = window.plusHours(2) }) { Text("2 sa ›") }
            Button(onClick = { window = window.plusDays(1) }) { Text("+1 gün") }
        }
        Field("Program ara", query, { query = it })
        Row {
            Text(
                window.format(DateTimeFormatter.ofPattern("dd.MM")),
                Modifier.width(108.dp),
                color = Ink.accent,
            )
            repeat(4) {
                Text(
                    time.format(window.plusMinutes(30L * it)),
                    Modifier.weight(1f),
                    color = Ink.muted,
                    fontSize = 12.sp,
                )
            }
        }
        selection?.let { (channel, slot) ->
            Text(
                "${channel.title} · ${time.format(Instant.ofEpochMilli(slot.start))}  ${slot.title}"
            )
            Text(slot.description, color = Ink.muted, maxLines = 3)
            if (
                channel.archive.isNotEmpty() &&
                    slot.end <= System.currentTimeMillis() &&
                    slot.start >=
                        System.currentTimeMillis() -
                            channel.archiveDays.coerceIn(0, 365) * 86400000L
            ) {
                Button(
                    onClick = {
                        model.job("Arşiv programı açıldı.") {
                            val replay = model.repo.replay(channel, slot)
                            play(replay, listOf(replay))
                        }
                    }
                ) {
                    Text("Arşivden izle")
                }
            }
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(3.dp),
        ) {
            items(channels, key = { it.id }) { channel ->
                val mapping by
                    remember(channel.id) { model.repo.guide.mapping(channel.id) }
                        .collectAsStateWithLifecycle(null)
                val slots by
                    remember(channel.id, mapping, from, query) {
                            model.repo.guide.guide(
                                channel.source,
                                mapping?.channel ?: channel.tvgId,
                                searchable(channel.title),
                                from,
                                to,
                                searchable(query),
                                mapping?.feed.orEmpty(),
                            )
                        }
                        .collectAsStateWithLifecycle(emptyList())
                if (query.isBlank() || slots.isNotEmpty())
                    Row {
                        Surface(
                            onClick = { open(channel) },
                            modifier = Modifier.width(108.dp).height(35.dp),
                        ) {
                            Text(
                                channel.title,
                                Modifier.padding(5.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        BoxWithConstraints(
                            Modifier.weight(1f).height(35.dp).background(Ink.secondary)
                        ) {
                            for (slot in slots.distinctBy { it.start }) {
                                val left = (maxOf(from, slot.start) - from).toFloat() / (to - from)
                                val width =
                                    (minOf(to, slot.end) - maxOf(from, slot.start)).toFloat() /
                                        (to - from)
                                Surface(
                                    onClick = { selection = channel to slot },
                                    modifier =
                                        Modifier.offset(x = maxWidth * left)
                                            .width(maxWidth * width)
                                            .fillMaxHeight()
                                            .padding(horizontal = 1.dp),
                                ) {
                                    Text(
                                        slot.title,
                                        Modifier.padding(4.dp),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
            }
            if (channels.size >= max)
                item { Button(onClick = { max += 80 }) { Text("Diğer kanallar") } }
        }
    }
}

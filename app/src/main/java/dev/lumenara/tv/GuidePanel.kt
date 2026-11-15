package dev.lumenara.tv

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.*
import dev.lumenara.design.LumenColors
import dev.lumenara.catalog.searchable
import dev.lumenara.data.*
import kotlinx.coroutines.flow.collect
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
internal fun GuidePanel(model: LibraryModel, channel: Item, playReplay: (Item) -> Unit) {
    var day by remember { mutableStateOf(LocalDate.now()) }
    var query by remember { mutableStateOf("") }
    var mappingOpen by remember { mutableStateOf(false) }
    var channelQuery by remember { mutableStateOf("") }
    val mapping by
        remember(channel.id) { model.repo.guide.mapping(channel.id) }
            .collectAsStateWithLifecycle(null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        wallClockTicks(60_000).collect { now = it }
    }
    val slots by
        remember(day, channel.id, query, mapping) {
                val zone = ZoneId.systemDefault()
                model.repo.guide.guide(
                    channel.source,
                    mapping?.channel ?: channel.tvgId,
                    searchable(channel.title),
                    day.atStartOfDay(zone).toInstant().toEpochMilli(),
                    day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
                    searchable(query),
                    mapping?.feed.orEmpty(),
                )
            }
            .collectAsStateWithLifecycle(emptyList())
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Yayın rehberi", fontSize = 18.sp)
        Button(onClick = { mappingOpen = !mappingOpen }) {
            Text("EPG kanalı · " + (mapping?.channel ?: "Otomatik"))
        }
        if (mappingOpen) {
            Field("Rehberde kanal ara", channelQuery, { channelQuery = it })
            val names by
                remember(channelQuery, channel.source) {
                        model.repo.guide.names(channel.source, searchable(channelQuery))
                    }
                    .collectAsStateWithLifecycle(emptyList())
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Button(
                        onClick = {
                            model.job("Otomatik eşleştirme seçildi.") {
                                model.repo.guide.unmap(channel.id)
                                mappingOpen = false
                            }
                        }
                    ) {
                        Text("Otomatik")
                    }
                }
                items(names, key = { "${it.feed}:${it.channel}:${it.name}" }) { name ->
                    Button(
                        onClick = {
                            model.job("Rehber kanalı eşleştirildi.") {
                                model.repo.guide.map(Mapping(channel.id, name.feed, name.channel))
                                mappingOpen = false
                            }
                        }
                    ) {
                        Text("${name.name} · ${name.channel}")
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = { day = day.minusDays(1) }) { Text("Önceki gün") }
            Button(onClick = { day = LocalDate.now() }) {
                Text(day.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
            }
            Button(onClick = { day = day.plusDays(1) }) { Text("Sonraki gün") }
        }
        Field("Program ara", query, { query = it })
        if (slots.isEmpty()) Text("Bu gün için eşleşen program yok.", color = LumenColors.textSecondary)
        else
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(slots, key = { "${it.feed}:${it.channel}:${it.start}" }) { slot ->
                    var expanded by remember { mutableStateOf(false) }
                    Surface(onClick = { expanded = !expanded }) {
                        Column(Modifier.width(200.dp).padding(8.dp)) {
                            val format =
                                DateTimeFormatter.ofPattern("HH:mm")
                                    .withZone(ZoneId.systemDefault())
                            Text(
                                format.format(Instant.ofEpochMilli(slot.start)) +
                                    " – " +
                                    format.format(Instant.ofEpochMilli(slot.end)) +
                                    if (now in slot.start until slot.end) " · Şimdi" else "",
                                color = LumenColors.accent,
                            )
                            Text(slot.title)
                            if (expanded)
                                Text(
                                    slot.description.ifEmpty { "Açıklama bulunmuyor." },
                                    fontSize = 13.sp,
                                )
                            if (
                                expanded &&
                                    channel.archive.isNotEmpty() &&
                                    slot.end <= now &&
                                    slot.start >=
                                        now - channel.archiveDays.coerceIn(0, 365) * 86400000L
                            ) {
                                Button(
                                    onClick = {
                                        model.job("Arşiv programı açıldı.") {
                                            playReplay(model.repo.replay(channel, slot))
                                        }
                                    }
                                ) {
                                    Text("Arşivden izle")
                                }
                            }
                        }
                    }
                }
            }
    }
}

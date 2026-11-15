package dev.lumenara.tv

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.lumenara.catalog.searchable
import dev.lumenara.data.Item
import dev.lumenara.data.ProgramHit
import dev.lumenara.design.Ink
import dev.lumenara.design.FocusCard
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
internal fun SearchScreen(
    model: LibraryModel,
    source: String,
    open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit,
) {
    var lastShelf by rememberSaveable(source) { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(true) }
    val entryFocus = remember { FocusRequester() }
    val resultsState = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (lastShelf == null) {
            withFrameNanos { }
            entryFocus.requestFocus()
            restoring = false
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var limit by rememberSaveable(source, query) { mutableIntStateOf(80) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) {
            it.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.let { spoken -> query = spoken }
        }
    }
    fun listen() {
        runCatching {
            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR"))
        }.onFailure { model.notice.value = "Ses tanıma hizmeti bulunamadı. Metinle arayabilirsiniz." }
    }
    val request by model.remoteSearch.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        if (request > 0) {
            model.remoteSearch.value = 0
            listen()
        }
    }
    val shelves = listOf("Kanallar" to "LIVE", "Filmler" to "MOVIE", "Diziler" to "SERIES").map { (title, kind) ->
        val rows by remember(source, kind, query, limit) {
            model.repo.browse(source, kind, "", searchable(query), "", limit)
        }.collectAsStateWithLifecycle(emptyList())
        title to rows
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { wallClockTicks(30_000).collect { now = it } }
    val programs by remember(source, query) {
        if (query.isBlank()) flowOf(emptyList())
        else model.repo.searchPrograms(source, searchable(query), snapshotFlow { (now - 7 * 86_400_000L) to now })
            .flatMapLatest { hits ->
                val ids = hits.map { it.itemId }.distinct()
                if (ids.isEmpty()) flowOf(emptyList())
                else model.repo.liveByIds(source, ids).map { items ->
                    val byId = items.associateBy { it.id }
                    hits.mapNotNull { hit -> byId[hit.itemId]?.let { hit to it } }
                }
            }
    }.collectAsStateWithLifecycle(emptyList())
    LaunchedEffect(shelves, programs) {
        if (restoring && lastShelf != null) {
            val visible = shelves.filter { it.second.isNotEmpty() }.map { it.first } +
                if (programs.isNotEmpty()) listOf("Programlar") else emptyList()
            val index = visible.indexOf(lastShelf)
            if (index >= 0) {
                resultsState.scrollToItem(index)
                restoring = false
            } else if (visible.isEmpty()) entryFocus.requestFocus()
        }
    }
    val notice by model.notice.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(240.dp).focusRequester(entryFocus).focusGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("Kanal adı, kategori veya yayın kimliği", query, { query = it })
            Button(onClick = { listen() }) { Text("Sesle ara") }
            if (notice.isNotEmpty()) Text(notice, color = Ink.accent)
        }
        LazyColumn(Modifier.weight(1f).testTag("search-results").focusRestorer().focusGroup(), state = resultsState,
            contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (programs.isEmpty() && shelves.all { it.second.isEmpty() }) item { Text("Eşleşen içerik yok.", color = Ink.muted) }
            items(shelves.filter { it.second.isNotEmpty() }, key = { it.first }) { (title, rows) ->
                if (title == "Kanallar") ChannelShelf(model, title, rows, play, { lastShelf = title })
                else Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(title, fontSize = 19.sp)
                    LazyRow(Modifier.testTag("search-shelf-$title").focusRestorer().focusGroup(),
                        contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                        items(rows, key = { it.id }) { MovieCard(model, it, true, open, { lastShelf = title }) }
                    }
                }
            }
            if (programs.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Programlar", fontSize = 19.sp)
                    LazyRow(Modifier.testTag("search-shelf-Programlar").focusRestorer().focusGroup(),
                        contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                        items(programs, key = { (hit, _) -> "${hit.itemId}:${hit.slot.feed}:${hit.slot.channel}:${hit.slot.start}" }) { (hit, channel) ->
                            ProgramCard(hit, channel, now, { lastShelf = "Programlar" }) {
                                val slot = hit.slot
                                val atClick = System.currentTimeMillis()
                                when {
                                    atClick in slot.start until slot.end -> play(channel, programs.map { it.second }.distinctBy { it.id })
                                    channel.archive.isNotEmpty() && slot.end <= atClick &&
                                        slot.start >= atClick - channel.archiveDays.coerceIn(0, 365) * 86_400_000L ->
                                        model.job("Arşiv programı açıldı.") {
                                            val replay = model.repo.replay(channel, slot)
                                            play(replay, listOf(replay))
                                        }
                                    else -> open(channel)
                                }
                            }
                        }
                    }
                }
            }
            if (shelves.any { it.second.size >= limit }) item {
                Button(onClick = { limit += 80 }) { Text("Devamını göster") }
            }
        }
    }
}

@Composable
private fun ProgramCard(hit: ProgramHit, channel: Item, now: Long, focus: () -> Unit, click: () -> Unit) {
    var restoreFocus by rememberSaveable(channel.id, hit.slot.start) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (restoreFocus) { withFrameNanos { }; requester.requestFocus(); restoreFocus = false }
    }
    val slot = hit.slot
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(slot.start).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val label = when (day) {
        today -> "Bugün"
        today.plusDays(1) -> "Yarın"
        today.minusDays(1) -> "Dün"
        else -> day.format(DateTimeFormatter.ofPattern("dd.MM"))
    }
    val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    FocusCard({ restoreFocus = true; click() }, Modifier.width(260.dp).focusRequester(requester)
        .onFocusChanged { if (it.isFocused) focus() }.testTag("search-program-${channel.id}-${slot.start}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(slot.title, fontSize = 18.sp, maxLines = 2)
            Text(channel.title, color = Ink.muted, fontSize = 15.sp)
            Text("$label · ${time.format(Instant.ofEpochMilli(slot.start))}–${time.format(Instant.ofEpochMilli(slot.end))}", fontSize = 13.sp)
            if (now in slot.start until slot.end) Text("Şimdi", color = Ink.accent, fontSize = 13.sp)
        }
    }
}

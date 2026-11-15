package dev.lumenara.tv

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import dev.lumenara.data.debouncedSearchQuery
import dev.lumenara.data.Item
import dev.lumenara.data.ProgramHit
import dev.lumenara.design.Ink
import dev.lumenara.design.FocusCard
import dev.lumenara.design.LumenButton
import dev.lumenara.design.LumenButtonStyle
import dev.lumenara.design.LumenColors
import dev.lumenara.design.LumenIcon
import dev.lumenara.design.LumenType
import dev.lumenara.design.Spacing
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
    var recentSearches by rememberSaveable(source) { mutableStateOf(listOf<String>()) }
    var restoring by remember { mutableStateOf(true) }
    var fieldFocused by remember { mutableStateOf(false) }
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
    val settledQuery by remember(source) {
        snapshotFlow { query }.debouncedSearchQuery()
    }.collectAsStateWithLifecycle("")
    var limit by rememberSaveable(source, settledQuery) { mutableIntStateOf(80) }
    val searchPrefill by model.searchPrefill.collectAsStateWithLifecycle()
    LaunchedEffect(searchPrefill) {
        searchPrefill?.let { query = it.second; model.searchPrefill.value = null }
    }

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
    val shelfResults = listOf("Kanallar" to "LIVE", "Filmler" to "MOVIE", "Diziler" to "SERIES").map { (title, kind) ->
        val rows by remember(source, kind, settledQuery, limit) {
            if (settledQuery.isEmpty()) flowOf(emptyList())
            else model.repo.browse(source, kind, "", settledQuery, "", limit)
        }.collectAsLoadingState()
        title to rows
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { wallClockTicks(30_000).collect { now = it } }
    val programResult by remember(source, settledQuery) {
        if (settledQuery.isEmpty()) flowOf(emptyList())
        else model.repo.searchPrograms(source, settledQuery, snapshotFlow { (now - 7 * 86_400_000L) to now })
            .flatMapLatest { hits ->
                val ids = hits.map { it.itemId }.distinct()
                if (ids.isEmpty()) flowOf(emptyList())
                else model.repo.liveByIds(source, ids).map { items ->
                    val byId = items.associateBy { it.id }
                    hits.mapNotNull { hit -> byId[hit.itemId]?.let { hit to it } }
                }
            }
    }.collectAsLoadingState()
    val loading = shelfResults.any { it.second == null } || programResult == null
    val shelves = shelfResults.map { it.first to it.second.orEmpty() }
    val programs = programResult.orEmpty()
    LaunchedEffect(shelves, programs, loading) {
        if (restoring && lastShelf != null && !loading) {
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
    Column(Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(Spacing.titleGap)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
            SearchField(query, { query = it }, entryFocus, { focused ->
                if (fieldFocused && !focused && query.isNotBlank()) {
                    recentSearches = (listOf(query.trim()) + recentSearches.filterNot { it.equals(query.trim(), true) }).take(6)
                }
                fieldFocused = focused
            }, Modifier.weight(1f))
            LumenButton(onClick = { listen() }, style = LumenButtonStyle.Secondary,
                modifier = Modifier.size(56.dp).testTag("search-voice")) {
                LumenIcon(LumenIcon.Search, contentDescription = "Sesle ara")
            }
        }
        if (notice.isNotEmpty()) Text(notice, color = LumenColors.textSecondary, style = LumenType.meta)
        if (query.isBlank() && !fieldFocused && recentSearches.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                Text("SON ARAMALAR", style = LumenType.caption, color = LumenColors.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                    recentSearches.forEach { term ->
                        LumenButton(onClick = { query = term }, style = LumenButtonStyle.Secondary) {
                            Text(term, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).testTag("search-results").focusRestorer().focusGroup(), state = resultsState,
            contentPadding = PaddingValues(bottom = Spacing.unit), verticalArrangement = Arrangement.spacedBy(Spacing.shelfGap)) {
            if (query.isBlank() && recentSearches.isEmpty()) item {
                Text("Kanal, film veya dizi ara", style = LumenType.meta)
            }
            if (loading) item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(8) { LoadingCard(Modifier.width(110.dp).aspectRatio(2f / 3f)) }
                }
            }
            if (!loading && programs.isEmpty() && shelves.all { it.second.isEmpty() }) item { Text("Eşleşen içerik yok.", color = LumenColors.textSecondary, style = LumenType.body) }
            val populated = shelves.filter { it.second.isNotEmpty() }
            if (fieldFocused && populated.isNotEmpty()) item(key = "single-search-shelf") {
                val combined = populated.flatMap { (title, rows) -> rows.map { title to it } }
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                    Text("SONUÇLAR", style = LumenType.caption, color = LumenColors.textSecondary)
                    LazyRow(Modifier.testTag("search-shelf-${populated.first().first}").focusRestorer().focusGroup(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(combined, key = { (kind, item) -> "$kind:${item.id}" }) { (kind, item) ->
                            if (kind == "Kanallar") {
                                Box(Modifier.testTag("channel-shelf-Kanallar")) {
                                    SearchResultCard(model, item, { lastShelf = kind }) {
                                        play(item, shelves.first { it.first == kind }.second)
                                    }
                                }
                            } else {
                                Box(if (kind == populated.first().first) Modifier else Modifier.testTag("search-shelf-$kind")) {
                                    SearchResultCard(model, item, { lastShelf = kind }) { open(item) }
                                }
                            }
                        }
                    }
                }
            } else items(populated, key = { it.first }) { (title, rows) ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                    Text(title, style = LumenType.cardTitle)
                    LazyRow(Modifier.testTag(if (title == "Kanallar") "channel-shelf-$title" else "search-shelf-$title")
                        .focusRestorer().focusGroup(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(rows, key = { it.id }) { item ->
                            SearchResultCard(model, item, { lastShelf = title }) {
                                if (title == "Kanallar") play(item, rows) else open(item)
                            }
                        }
                    }
                }
            }
            if (programs.isNotEmpty()) item {
                Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text("Programlar", style = LumenType.cardTitle)
                    LazyRow(Modifier.testTag("search-shelf-Programlar").focusRestorer().focusGroup(),
                        contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                LumenButton(onClick = { limit += 80 }, style = LumenButtonStyle.Secondary) { Text("Devamını göster") }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    requester: FocusRequester,
    onFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.height(56.dp).background(LumenColors.surface3, RoundedCornerShape(8.dp))
        .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
        LumenIcon(LumenIcon.Search, contentDescription = null, tint = LumenColors.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f).fillMaxHeight().wrapContentHeight(Alignment.CenterVertically)
                .focusRequester(requester).onFocusChanged {
                    onFocus(it.isFocused)
                }.testTag("search-input"),
            textStyle = TextStyle(color = LumenColors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium),
            cursorBrush = SolidColor(LumenColors.accent),
            singleLine = true,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text("Kanal, film veya dizi ara", style = LumenType.body,
                        color = LumenColors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
    }
}

@Composable
private fun SearchResultCard(model: LibraryModel, item: Item, focus: () -> Unit, click: () -> Unit) {
    var restoreFocus by rememberSaveable(item.id) { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (restoreFocus) { withFrameNanos { }; requester.requestFocus(); restoreFocus = false }
    }
    Column(Modifier.width(110.dp)) {
        // The resting slot is measured separately from the 1.06 focus transform.
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).testTag("search-artwork-${item.id}")) {
            FocusCard({ restoreFocus = true; click() }, Modifier.fillMaxSize().focusRequester(requester)
                .semantics { contentDescription = item.title }
                .onFocusChanged { if (it.isFocused) focus() }) {
                if (item.kind == "LIVE") {
                    val logo by produceState<String?>(null, item.id, item.logo) { value = model.repo.poster(item) }
                    ChannelLogo(model, item, logo, Modifier.fillMaxSize())
                } else Poster(model, item, Modifier.fillMaxSize())
            }
        }
        Text(item.title, Modifier.padding(top = 8.dp), style = LumenType.label,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(item.year.ifBlank { item.category }, style = LumenType.meta,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    FocusCard({ restoreFocus = true; click() }, Modifier.width(110.dp).aspectRatio(2f / 3f).focusRequester(requester)
        .onFocusChanged { if (it.isFocused) focus() }.testTag("search-program-${channel.id}-${slot.start}")) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(slot.title, style = LumenType.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(channel.title, style = LumenType.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$label · ${time.format(Instant.ofEpochMilli(slot.start))}–${time.format(Instant.ofEpochMilli(slot.end))}",
                style = LumenType.meta, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (now in slot.start until slot.end) Text("Şimdi", color = Ink.accent, style = LumenType.caption)
        }
    }
}

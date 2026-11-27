package dev.lumenara.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import dev.lumenara.catalog.searchable
import dev.lumenara.data.LiveCategoryCount
import dev.lumenara.design.*

private const val CATEGORY_COLUMNS = 4

internal data class CategoryEntry(val id: String, val name: String, val count: Int, val countKnown: Boolean = true, val searchKey: String = "")

internal fun providerCategories(groups: List<LiveCategoryCount>): List<CategoryEntry> {
    return groups.filter { it.category.isNotEmpty() }
        .map { CategoryEntry("provider:${it.category}", it.category, it.count, searchKey = it.searchKey) }
}

private data class PreparedCategories(val input: List<CategoryEntry>, val rows: List<CategoryEntry>,
    val names: Map<String, String>)

private fun categoryIcon(id: String): LumenIcon? = when (id) {
    "all" -> LumenIcon.List
    "recent" -> LumenIcon.Clock
    "favorite" -> LumenIcon.Heart
    "list" -> LumenIcon.Bookmark
    "airing" -> LumenIcon.Tv
    "timeline" -> LumenIcon.Timer
    else -> null
}

@Composable
internal fun CategoryGrid(title: String, entries: List<CategoryEntry>?, choose: (CategoryEntry) -> Unit) {
    val last = rememberSaveable(title) { mutableStateOf<String?>(null) }
    var searching by rememberSaveable(title) { mutableStateOf(false) }
    var query by rememberSaveable(title) { mutableStateOf("") }
    val grid = rememberLazyGridState()
    val gridFocus = rememberGridFocus(grid) { it % CATEGORY_COLUMNS }
    val focus = remember { FocusRequester() }
    val searchButton = remember { FocusRequester() }
    val searchField = remember { FocusRequester() }
    var restoreSearch by remember { mutableStateOf(false) }
    var positioned by remember { mutableStateOf(false) }
    var requestPending by remember { mutableStateOf(true) }
    val restoreId = remember { last.value }
    val prepared = remember(entries) {
        entries?.let { input ->
            PreparedCategories(input, input, input.associate { it.id to
                if (it.id.startsWith("provider:")) it.searchKey else searchable(it.name) })
        }
    }
    val ready = entries != null && prepared?.input == entries
    val rows = remember(prepared, ready, query) {
        val page = prepared?.takeIf { ready }
        val needle = searchable(query)
        if (needle.isEmpty()) page?.rows.orEmpty()
        else page?.rows.orEmpty().filter { needle in page?.names?.get(it.id).orEmpty() }
    }
    val target = rows.indexOfFirst { it.id == restoreId }.coerceAtLeast(0)
    BackHandler(searching) { searching = false; query = ""; restoreSearch = true }
    LaunchedEffect(searching) {
        if (searching) searchField.requestFocus()
        else if (restoreSearch) { searchButton.requestFocus(); restoreSearch = false }
    }
    LaunchedEffect(rows) {
        if (!positioned && rows.isNotEmpty()) {
            grid.scrollToItem(target)
            positioned = true
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(Spacing.unit)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            if (searching) {
                BasicTextField(query, { query = it },
                    modifier = Modifier.weight(1f).focusRequester(searchField).testTag("category-search-field")
                        .semantics { contentDescription = "Kategori ara" }
                        .background(LumenColors.surface3, RoundedCornerShape(Radii.button))
                        .padding(Spacing.cardGap),
                    textStyle = LumenType.body.copy(color = LumenColors.textPrimary),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(LumenColors.textPrimary),
                    singleLine = true,
                    decorationBox = { field ->
                        Box {
                            if (query.isEmpty()) Text("Kategori ara", style = LumenType.body, color = LumenColors.textSecondary)
                            field()
                        }
                    })
            } else {
                Text(title, Modifier.weight(1f), style = LumenType.headline, color = LumenColors.textPrimary)
                LumenButton(onClick = { searching = true },
                    modifier = Modifier.focusRequester(searchButton).testTag("category-search")
                        .semantics { contentDescription = "Ara" }, style = LumenButtonStyle.Secondary) {
                    LumenIcon(LumenIcon.Search, null)
                }
            }
        }
        if (entries != null && entries.isEmpty()) {
            val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
            Text("İçerik yok.", style = LumenType.body, color = LumenColors.textSecondary)
            LumenButton(onClick = { back?.onBackPressed() }, modifier = Modifier.focusRequester(focus)) { Text("Geri") }
        }
        LazyVerticalGrid(GridCells.Fixed(CATEGORY_COLUMNS), state = grid,
            modifier = Modifier.fillMaxWidth().weight(1f)
                .testTag("category-grid").reportScrollPosition(grid).then(gridFocus.modifier).focusGroup(),
            contentPadding = PaddingValues(top = Spacing.unit, bottom = Spacing.shelfGap),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!ready) items(CATEGORY_COLUMNS * 6, key = { "loading-$it" }, contentType = { "loading" }) {
                Box(Modifier.fillMaxWidth().height(60.dp)
                    .background(LumenColors.surface2, RoundedCornerShape(Radii.card)).testTag("list-loading"))
            }
            if (ready && entries.orEmpty().isNotEmpty() && rows.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text("Eşleşen kategori yok.", style = LumenType.body, color = LumenColors.textSecondary)
            }
            itemsIndexed(rows, key = { _, it -> it.id }, contentType = { _, _ -> "category" }) { index, entry ->
                if (index == target && positioned && requestPending && !searching) LaunchedEffect(Unit) {
                    withFrameNanos { }
                    focus.requestFocus()
                    requestPending = false
                }
                FocusCard(onClick = {
                    Perf.transitions.categoryStart()
                    searching = false
                    query = ""
                    choose(entry)
                }, modifier = Modifier.fillMaxWidth().height(60.dp).testTag("category-${entry.name}")
                    .then(gridFocus.item(index))
                    .semantics { contentDescription = if (entry.countKnown) "${entry.name} · ${entry.count}" else entry.name }
                    .then(if (index == target) Modifier.focusRequester(focus) else Modifier)
                    .onFocusChanged { if (it.isFocused) last.value = entry.id }) {
                    Row(Modifier.fillMaxSize().padding(horizontal = Spacing.unit, vertical = Spacing.iconText),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.unit)) {
                        categoryIcon(entry.id)?.let { LumenIcon(it, null, size = 20.dp) }
                        Text(entry.name, Modifier.weight(1f), style = LumenType.cardTitle,
                            color = LumenColors.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (entry.countKnown) Text(entry.count.toString(), style = LumenType.meta)
                    }
                }
            }
        }
    }
}

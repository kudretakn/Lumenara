package dev.lumenara.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.tv.material3.*
import dev.lumenara.data.LiveCategoryCount
import dev.lumenara.design.Ink

internal data class CategoryEntry(val id: String, val name: String, val count: Int)

internal fun providerCategories(groups: List<LiveCategoryCount>): List<CategoryEntry> {
    return groups.filter { it.category.isNotEmpty() }
        .sortedWith { a, b -> dev.lumenara.catalog.categoryOrder.compare(a.category, b.category) }
        .map { CategoryEntry("provider:${it.category}", it.category, it.count) }
}

@Composable
internal fun NavigationCard(name: String, caption: String, modifier: Modifier,
                            update: Boolean = false, icon: Int? = null, click: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Surface(onClick = click, modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, Ink.stroke), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, Ink.focusRing), shape = shape)),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Ink.focusRing.copy(alpha = .18f), 4.dp)),
        colors = ClickableSurfaceDefaults.colors(containerColor = Ink.glass)) {
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center) {
            if (icon != null) {
                NavIcon(icon, Modifier.size(30.dp))
                Spacer(Modifier.height(12.dp))
            }
            Text(if (update) "$name  •" else name, fontSize = 20.sp, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (caption.isNotEmpty()) Text(caption, color = Ink.muted, fontSize = 15.sp)
        }
    }
}

@Composable
internal fun CategoryGrid(title: String, entries: List<CategoryEntry>, choose: (CategoryEntry) -> Unit) {
    var last by rememberSaveable(title) { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()
    val focus = remember { FocusRequester() }
    var positioned by remember { mutableStateOf(false) }
    var requestPending by remember { mutableStateOf(true) }
    val restoreId = remember { last }
    val target = entries.indexOfFirst { it.id == restoreId }.coerceAtLeast(0)
    LaunchedEffect(entries) {
        if (!positioned && entries.isNotEmpty()) {
            grid.scrollToItem(target)
            positioned = true
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, fontSize = 28.sp)
        if (entries.isEmpty()) {
            val back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            LaunchedEffect(Unit) { withFrameNanos { }; focus.requestFocus() }
            Text("İçerik yok.", color = Ink.muted)
            Button(onClick = { back?.onBackPressed() }, modifier = Modifier.focusRequester(focus)) { Text("Geri") }
        }
        LazyVerticalGrid(GridCells.Fixed(4), state = grid,
            modifier = Modifier.fillMaxSize().testTag("category-grid").focusGroup(),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(entries, key = { _, it -> it.id }) { index, entry ->
                if (index == target && positioned && requestPending) LaunchedEffect(Unit) {
                    withFrameNanos { }
                    focus.requestFocus()
                    requestPending = false
                }
                NavigationCard(entry.name, entry.count.toString(),
                    Modifier.fillMaxWidth().height(120.dp).testTag("category-${entry.name}")
                        .semantics { contentDescription = "${entry.name} · ${entry.count}" }
                        .then(if (index == target) Modifier.focusRequester(focus) else Modifier)
                        .onFocusChanged { if (it.isFocused) last = entry.id }) { choose(entry) }
            }
        }
    }
}

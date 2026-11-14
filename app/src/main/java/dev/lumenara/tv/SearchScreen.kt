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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import dev.lumenara.catalog.searchable
import dev.lumenara.data.Item
import dev.lumenara.design.Ink

@Composable
internal fun SearchScreen(
    model: LibraryModel,
    source: String,
    open: (Item) -> Unit,
    play: (Item, List<Item>) -> Unit,
) {
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
            model.repo.library.browse(source, kind, "", searchable(query), "", limit)
        }.collectAsStateWithLifecycle(emptyList())
        title to rows
    }
    val notice by model.notice.collectAsStateWithLifecycle()
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.width(240.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Field("Kanal adı, kategori veya yayın kimliği", query, { query = it })
            Button(onClick = { listen() }) { Text("Sesle ara") }
            if (notice.isNotEmpty()) Text(notice, color = Ink.accent)
        }
        LazyColumn(Modifier.weight(1f).testTag("search-results").focusRestorer().focusGroup(),
            contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (shelves.all { it.second.isEmpty() }) item { Text("Eşleşen içerik yok.", color = Ink.muted) }
            items(shelves.filter { it.second.isNotEmpty() }, key = { it.first }) { (title, rows) ->
                if (title == "Kanallar") ChannelShelf(model, title, rows, play, {})
                else Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Text(title, fontSize = 19.sp)
                    LazyRow(Modifier.testTag("search-shelf-$title").focusRestorer().focusGroup(),
                        contentPadding = PaddingValues(8.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                        items(rows, key = { it.id }) { MovieCard(model, it, true, open) }
                    }
                }
            }
            if (shelves.any { it.second.size >= limit }) item {
                Button(onClick = { limit += 80 }) { Text("Devamını göster") }
            }
        }
    }
}

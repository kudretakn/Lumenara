package dev.lumenara.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.focus.*
import androidx.compose.ui.semantics.*
import dev.lumenara.design.Chip
import dev.lumenara.design.LumenColors
import dev.lumenara.design.Spacing
import androidx.compose.ui.unit.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.*
import dev.lumenara.data.*
import dev.lumenara.catalog.exactChannelIndex
import dev.lumenara.catalog.searchable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Composable
internal fun LibraryScreen(model: LibraryModel = viewModel(), onFirstDraw: () -> Unit = {}) {
    val sources by model.sources.collectAsStateWithLifecycle()
    val startInSetup by model.startInSetup.collectAsStateWithLifecycle()
    val launchPending = sources == null || startInSetup == null
    var slowLaunch by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model, launchPending) {
        if (launchPending) {
            kotlinx.coroutines.delay(500)
            slowLaunch = true
        } else slowLaunch = false
    }
    // Submit a cheap skeleton immediately; explain a slow migration without touching
    // the lazy repository or composing the navigation graph before its IO snapshot.
    if (launchPending) {
        if (slowLaunch) MigrationLoadingScreen()
        else Column(Modifier.fillMaxSize().background(LumenColors.bg)
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.screenVertical),
            verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            LumenaraLogo(showName = true)
            LoadingCard(Modifier.fillMaxWidth().height(100.dp))
        }
        return
    }
    val available = checkNotNull(sources)
    val initialSetup = checkNotNull(startInSetup)
    LaunchedEffect(model) {
        model.awaitFirstDraw()
        onFirstDraw()
    }
    var section by rememberSaveable {
        mutableStateOf(if (initialSetup) "IPTV Kaynakları" else "Ana Sayfa")
    }
    var sourceParent by rememberSaveable { mutableStateOf("Ana Sayfa") }
    var scope by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(available) { if (available.none { it.id == scope }) scope = "" }
    var detail by remember { mutableStateOf<Item?>(null) }
    var playing by remember { mutableStateOf<Item?>(null) }
    val showingHub = section == "Ana Sayfa" && detail == null && playing == null
    LaunchedEffect(model, showingHub) {
        // Home releases the gate after its cheap history rows draw. Setup, restored screens
        // and playback/search intents must release it too, even if Home is never visited.
        if (!showingHub) {
            withFrameNanos { }; withFrameNanos { }
            model.onFirstDraw()
        }
    }
    DisposableEffect(playing != null) {
        RefreshGate.playing = playing != null
        onDispose { RefreshGate.playing = false }
    }
    var returnToChannel by remember { mutableStateOf<String?>(null) }
    var siblings by remember { mutableStateOf(emptyList<Item>()) }
    val pages = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val request by model.remoteSearch.collectAsStateWithLifecycle()
    val open: (Item) -> Unit = { detail = it }
    var launchPosition by remember { mutableLongStateOf(-1) }
    val playbackScope = rememberCoroutineScope()
    var playJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val play: (Item, List<Item>) -> Unit = { item, group ->
        Perf.mark("channel")
        playJob?.cancel()
        playJob = playbackScope.launch {
            try {
                val chosen = model.repo.selectedVersion(item)
                siblings = if (chosen.kind == "EPISODE" && chosen.parent != item.parent)
                    model.repo.episodeSnapshot(chosen.parent).seriesOrder()
                    else if (item.kind == "EPISODE") group.seriesOrder() else group
                launchPosition = -1
                playing = chosen
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { model.notice.value = "Seçilen sürüm açılamadı." }
        }
    }
    val searchIntent by model.searchIntent.collectAsStateWithLifecycle()
    LaunchedEffect(searchIntent) {
        val pending = searchIntent ?: return@LaunchedEffect
        val (id, search) = pending
        playJob?.cancel()
        playing = null
        detail = null
        if (search.playFromSearch) {
            try {
                val channel = uniqueSearchChannel(model, search.query)
                if (channel != null) {
                    section = "Canlı TV"
                    play(channel, LiveChannelGroup(listOf(channel), channel.source))
                    model.searchIntent.compareAndSet(pending, null)
                    return@LaunchedEffect
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // Fall back to the search screen if catalog lookup fails.
            }
        }
        section = "Ara"
        model.searchPrefill.value = id to search.query
        model.searchIntent.compareAndSet(pending, null)
    }
    LaunchedEffect(request) {
        if (request > 0) {
            playing = null
            detail = null
            section = "Ara"
        }
    }
    var launchSerial by remember { mutableIntStateOf(0) }
    val watchNextRequest by model.watchNextPlayback.collectAsStateWithLifecycle()
    LaunchedEffect(watchNextRequest) {
        val target = watchNextRequest ?: return@LaunchedEffect
        playJob?.cancel()
        try {
            val row = model.repo.library.item(model.repo.library.redirectedId(target.itemId) ?: target.itemId)
            val hidden = model.repo.hidden.first()
            if (row != null && row.kind in listOf("MOVIE", "EPISODE") && row.category !in hidden) {
                val chosen = model.repo.selectedVersion(row)
                check(chosen.category !in hidden)
                siblings = if (chosen.kind == "EPISODE")
                    model.repo.episodeSnapshot(chosen.parent).seriesOrder() else emptyList()
                detail = null
                launchPosition = target.position
                launchSerial++
                playing = chosen
            } else model.notice.value = "İçerik artık bulunamıyor."
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { model.notice.value = "İçerik oynatılamadı." }
        finally { model.watchNextPlayback.compareAndSet(target, null) }
    }
    val item = playing
    val sleepTimer = remember { dev.lumenara.catalog.SleepTimer() }
    if (item != null) {
        key(if (item.kind == "LIVE") "live-player" else item.id,
            if (item.kind == "LIVE") null else item.encryptedUrl, launchSerial) {
            Playback(model, item, siblings, { Perf.mark("channel"); launchPosition = -1; playing = it }, {
                if (section == "Canlı TV") returnToChannel = item.id
                playing = null
                launchPosition = -1
            }, { siblings = it }, initial = launchPosition, sleepTimer = sleepTimer,
                sleepTimerExpired = {
                    sleepTimer.cancel()
                    detail = null
                    playing = null
                    launchPosition = -1
                    section = "Ana Sayfa"
                })
        }
        return
    }
    val settingsEntry = remember { FocusRequester() }
    BackHandler(detail != null || section != "Ana Sayfa") {
        when {
            detail != null -> detail = null
            section == "IPTV Kaynakları" -> section = sourceParent
            else -> section = "Ana Sayfa"
        }
    }
    detail?.let {
        pages.SaveableStateProvider("details-${it.id}") {
            key(it.id) { Details(model, it, play) { detail = null } }
        }
        return
    }
    var hubCard by rememberSaveable { mutableStateOf("Canlı TV") }
    val entry = remember { FocusRequester() }
    LaunchedEffect(section) {
        if (section == "IPTV Kaynakları") {
            withFrameNanos { }
            entry.requestFocus()
        }
    }
    Box(Modifier.fillMaxSize().background(LumenColors.bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.screenVertical)) {
            pages.SaveableStateProvider(section) {
                Column(Modifier.fillMaxSize().semantics { testTag = "section-$section" }
                    .focusRequester(entry).focusRestorer().focusGroup()) {
                    when (section) {
                        "IPTV Kaynakları" -> {
                            LumenaraLogo(showName = true)
                            Setup(model, available, openRestore = { section = "Yedek geri yükle" }) { section = "Ana Sayfa" }
                        }
                        "Yedek geri yükle" -> BackupScreen(model, restore = true,
                            finishLabel = if (available.isEmpty()) "Kuruluma dön" else "Ana Sayfaya dön") {
                            section = if (available.isEmpty()) "IPTV Kaynakları" else "Ana Sayfa"
                        }
                        "Ayarlar" -> {
                            SettingsScreen(model, available, settingsEntry) {
                                sourceParent = "Ayarlar"
                                section = "IPTV Kaynakları"
                            }
                        }
                        "Ana Sayfa" -> {
                            Home(model, scope, open, play, hubCard, sourceSelector = {
                                if (available.size > 1) LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.unit),
                                ) {
                                    items(listOf("" to "Tümü") + available.map { it.id to it.name },
                                        key = { it.first }) { (id, name) ->
                                        Chip(name, scope == id, onClick = { scope = id })
                                    }
                                }
                            }) {
                                hubCard = it
                                section = it
                                model.notice.value = ""
                            }
                        }
                        else -> Browse(model, section, scope, open, play,
                            returnToChannel, { returnToChannel = null })
                    }
                }
            }
        }
        key(section) {
            DoubleBackExit(section == "Ana Sayfa" ||
                available.isEmpty() && section == "IPTV Kaynakları" && sourceParent == "Ana Sayfa")
        }
    }
}

/** Search in bounded pages so a unique name beyond the first page can still launch directly. */
private suspend fun uniqueSearchChannel(model: LibraryModel, query: String): Item? {
    val normalized = searchable(query)
    if (normalized.isBlank()) return null
    val matches = mutableListOf<Item>()
    var after: LiveCursor? = null
    while (true) {
        val rows = model.repo.livePageAfter("", "", normalized, "", LIVE_PAGE_SIZE, after).first()
        for (row in rows) if (searchable(row.title) == normalized) {
            matches += row
            if (matches.size > 1) return null
        }
        if (rows.size < LIVE_PAGE_SIZE) break
        after = rows.last().let { LiveCursor(it.sortKey, it.id) }
    }
    return exactChannelIndex(query, matches.map { it.title })?.let { matches[it] }
}

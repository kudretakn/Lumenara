package dev.lumenara.tv

import dev.lumenara.data.Item
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Wp17ScreenUiStateTest {
    @Test fun loadingEmptyAndReadyAreDistinctAndEqualEmissionsAreSuppressed() = runBlocking {
        val values = flowOf(emptyList<String>(), emptyList(), listOf("one"), listOf("one"))
            .wp17ScreenStates().toList()
        assertEquals(listOf(ScreenUiState.Loading, ScreenUiState.Ready(emptyList<String>()),
            ScreenUiState.Ready(listOf("one"))), values)
        assertNull(ScreenUiState.Loading.readyOrNull())
        assertEquals(emptyList<String>(), values[1].readyOrNull())
    }

    @Test fun errorRetainsOnlyStageAndClassAndCancellationIsNotAUiError() = runBlocking {
        val failure = IllegalStateException("https://user:password@provider/private")
        val values = flow<List<String>> { throw failure }.wp17ScreenStates("hub_recent").toList()
        assertEquals(listOf(ScreenUiState.Loading, ScreenUiState.Error("wp17_hub_recent_IllegalStateException")), values)
        assertFalse(values.toString().contains("password"))
        val cancel = CancellationException("left screen")
        try {
            flow<String> { throw cancel }.wp17ScreenStates().toList()
            fail("Screen departure must cancel its producer")
        } catch (actual: CancellationException) { assertSame(cancel, actual) }
    }

    @Test fun aFreshCollectionCanRecoverFromAnError() = runBlocking {
        var failed = true
        val query = flow { if (failed) error("offline") else emit(emptyList<String>()) }.wp17ScreenStates()
        assertTrue(query.toList().last() is ScreenUiState.Error)
        failed = false
        assertEquals(ScreenUiState.Ready(emptyList<String>()), query.toList().last())
    }

    @Test fun savedRouteIdsAndOldNamesResolveWithoutDependingOnDisplayResources() {
        assertEquals(LibraryRoute.entries.size, LibraryRoute.entries.map { it.id }.toSet().size)
        for (route in LibraryRoute.entries) {
            assertEquals(route, LibraryRoute.fromSaved(route.id))
            assertEquals(route, LibraryRoute.fromSaved(route.legacyName))
        }
        assertEquals(LibraryRoute.Home, LibraryRoute.fromSaved("removed-route"))
        assertEquals(listOf("LIVE", "MOVIE", "SERIES"), LibraryRoute.hub.take(3).map { it.kind })
    }

    @Test fun gridReturnPreservesOffsetAndUsesDefinedMissingItemFallback() {
        val target = Wp17GridReturn("selected", index = 83, firstVisible = 64, offset = 17)
        assertEquals(64 to 17, target.viewport("selected", 83))
        assertEquals(56 to 17, target.viewport("selected", 75))
        assertEquals(0 to 0, target.viewport("replacement", 0))
    }

    @Test fun posterProjectionIgnoresNonvisualEntityUpdatesButTracksVisibleFields() {
        val item = Item("id", "source", "Title", "category", "MOVIE", "", "secret", "title", 1, "", year = "2025")
        val card = Wp17PosterCard.from(item)
        assertEquals(card, Wp17PosterCard.from(item.copy(generation = 2, encryptedUrl = "new secret", summary = "new metadata")))
        assertNotEquals(card, Wp17PosterCard.from(item.copy(title = "New title", cleanTitle = "New title")))
        assertNotEquals(card, Wp17PosterCard.from(item.copy(year = "2026")))
    }
}

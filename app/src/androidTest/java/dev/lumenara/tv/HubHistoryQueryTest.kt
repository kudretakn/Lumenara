package dev.lumenara.tv

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.catalog.isAdultCategory
import dev.lumenara.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HubHistoryQueryTest {
    private fun item(id: String, kind: String = "MOVIE", category: String = "Film",
                     parent: String = "", source: String = "a") =
        Item(id, source, id, category, kind, "", "", id, 0, "", parent = parent)

    @Test fun historyVisibilityMatchesCatalogVisibilityWithoutReadingUnwatchedCategories() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "A", "", false, 0, 0))
            dao.save(Source("b", "B", "", false, 0, 0))
            dao.save(listOf(
                item("unwatched", category = "Unwatched category"),
                item("movie"), item("adult", category = "XXX"), item("other", source = "b"),
                item("hidden-parent", "SERIES", "For Adults"),
                item("hidden-parent-ep", "EPISODE", parent = "hidden-parent"),
                item("hidden-history", "SERIES"),
                item("hidden-played", "EPISODE", "XXX", "hidden-history"),
                item("safe-next", "EPISODE", parent = "hidden-history"),
                item("hidden-remainder", "SERIES"),
                item("completed", "EPISODE", parent = "hidden-remainder"),
                item("hidden-next", "EPISODE", "XXX", "hidden-remainder"),
                item("episode-category-only", "SERIES"),
                item("unique-adult-ep", "EPISODE", "Episode Adult", "episode-category-only"),
            ))
            val now = System.currentTimeMillis()
            listOf("movie", "adult", "other", "hidden-parent-ep", "hidden-played", "unique-adult-ep").forEach {
                dao.save(Mark(it, position = 10, duration = 100, updated = now))
            }
            dao.save(Mark("completed", position = 100, duration = 100, updated = now))
            val historyNames = dao.continueWatchingCategories().first()
            assertEquals(setOf("Film", "XXX", "For Adults"), historyNames.toSet())
            // Match categoryNames' existing top-level category policy, including odd provider data.
            assertFalse(historyNames.contains("Episode Adult"))
            for (source in listOf("", "a", "b")) for (show in listOf(false, true)) {
                val hidden = if (show) emptyList() else historyNames.filter(::isAdultCategory)
                val catalogHidden = if (show) emptyList() else dao.categoryNames().first().filter(::isAdultCategory)
                assertEquals(dao.continueWatching(source, catalogHidden).first(),
                    dao.continueWatching(source, hidden).first())
            }
            val safe = historyNames.filter(::isAdultCategory)
            assertEquals(setOf("movie", "other", "episode-category-only"),
                dao.continueWatching("", safe).first().map { it.id }.toSet())
            assertTrue(dao.continueWatching("", emptyList()).first().any { it.id == "hidden-remainder" })

            // More history still returns only the existing 24-card shelf, newest first.
            dao.save((0 until 30).map { item("recent-$it") })
            repeat(30) { dao.save(Mark("recent-$it", position = 10, duration = 100, updated = now + it + 1)) }
            val rows = dao.continueWatching("a", safe).first()
            assertEquals(24, rows.size)
            assertEquals("recent-29", rows.first().id)
            assertEquals("recent-6", rows.last().id)
        } finally { db.close() }
    }
}

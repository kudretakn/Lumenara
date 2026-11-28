package dev.lumenara.tv

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

class Wp22IncrementalTest {
    private fun database(queries: MutableList<String> = CopyOnWriteArrayList()) =
        Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LibraryDb::class.java)
            .setQueryCallback({ sql, _ -> queries += sql }, Executor { it.run() }).build()

    @Test fun unchangedVersionAndDigestDoNoCatalogRowWorkEvenAfterAMarkWrite(): Unit = runBlocking {
        val queries = CopyOnWriteArrayList<String>()
        val db = database(queries)
        try {
            val dao = db.library()
            dao.save(Source("s", "Fixture", "opaque", false, 1, 3))
            dao.save(listOf(movie("a", "Alpha Dublaj"), movie("b", "Alpha Altyazılı"), movie("c", "Other")))
            val engine = Wp22Incremental(db)
            assertEquals(3, engine.prepare("s", true).normalized)
            dao.save(Mark("b", favorite = true, position = 10, duration = 100, updated = 9))
            queries.clear()
            assertEquals(Wp22Work(), engine.prepare("s", false))
            assertFalse(queries.any { it.contains("wp22_inputs", true) || it.contains("category_counts", true) ||
                it.contains("UPDATE `items`", true) || it.contains("version_backfill_rows", true) })
            assertEquals(10L, dao.mark("b")?.position)
            // A calculation version change must not reuse incompatible normalized inputs.
            dao.wp22SaveState(Wp22CatalogState("s", 0))
            assertEquals(3, engine.prepare("s", false).normalized)
        } finally { db.close() }
    }

    @Test fun metadataOnlyRecomputesItsBucketAndOldAndNewTmdbGroups(): Unit = runBlocking {
        val db = database()
        try {
            val dao = db.library()
            dao.save(Source("s", "Fixture", "opaque", false, 1, 4))
            dao.save(listOf(movie("a", "Alpha Dublaj"), movie("b", "Alpha Altyazılı"),
                movie("translated", "Translation", "Translated").copy(search = "translation [tmdb:9]"),
                movie("untouched", "Other", "Unrelated")))
            val engine = Wp22Incremental(db)
            engine.prepare("s", true)
            dao.save(Mark("b", favorite = true, position = 40, duration = 100, updated = 20))
            dao.assignTmdb(listOf(TmdbAssignment("a", "alpha [tmdb:9]", "")))
            val work = engine.prepare("s", false)
            assertEquals(1, work.normalized)
            assertEquals(2, work.resolved)
            assertEquals(3, work.applied)
            assertEquals(2, work.categories)
            val key = dao.item("a")!!.versionKey
            assertEquals(key, dao.item("b")!!.versionKey)
            assertEquals(key, dao.item("translated")!!.versionKey)
            assertNotEquals(key, dao.item("untouched")!!.versionKey)
            assertEquals(40L, dao.mark("translated")?.position)
            assertTrue(dao.mark("a")!!.favorite)
            assertEquals(Wp22Work(), engine.prepare("s", false))
            assertMatchesFullBackfill(db)
        } finally { db.close() }
    }

    @Test fun splitTmdbGroupDoesNotDonateDepartingMembersProgressToTheOldGroup(): Unit = runBlocking {
        val db = database()
        try {
            val dao = db.library()
            dao.save(Source("s", "Fixture", "opaque", false, 1, 3))
            dao.save(listOf(movie("a", "Alpha").copy(search = "alpha [tmdb:9]"),
                movie("b", "Translation").copy(search = "translation [tmdb:9]")))
            val engine = Wp22Incremental(db)
            engine.prepare("s", true)
            val departing = Mark("a", favorite = true, position = 80, duration = 100, updated = 20)
            val staying = Mark("b", position = 10, duration = 100, updated = 10)
            dao.save(departing)
            dao.save(staying)
            dao.assignTmdb(listOf(TmdbAssignment("a", "alpha [tmdb:10]", "")))
            engine.prepare("s", false)
            assertEquals(departing, dao.mark("a"))
            assertEquals(staying, dao.mark("b"))
            assertNotEquals(dao.item("a")!!.versionKey, dao.item("b")!!.versionKey)
        } finally { db.close() }
    }

    @Test fun changedBodyRepairsRemovedRepresentativesAndConflictingRemakes(): Unit = runBlocking {
        val db = database()
        try {
            val dao = db.library()
            dao.save(Source("s", "Fixture", "opaque", false, 1, 4))
            dao.save(listOf(movie("a", "Alpha Dublaj").copy(year = "2000"),
                movie("b", "Alpha Altyazılı"), movie("other", "Other", "Unrelated")))
            val engine = Wp22Incremental(db)
            engine.prepare("s", true)
            assertEquals(dao.item("a")!!.versionKey, dao.item("b")!!.versionKey)
            dao.save(listOf(movie("remake", "Alpha").copy(year = "2020")))
            assertEquals(1, engine.prepare("s", true).normalized)
            assertNotEquals(dao.item("a")!!.versionKey, dao.item("b")!!.versionKey)
            assertMatchesFullBackfill(db)
            db.openHelper.writableDatabase.execSQL("DELETE FROM items WHERE id='remake'")
            val removed = engine.prepare("s", true)
            assertEquals(0, removed.normalized)
            assertEquals(2, removed.applied)
            assertEquals(dao.item("a")!!.versionKey, dao.item("b")!!.versionKey)
            assertEquals(1, db.derived().categories("s").first { it.category == "Film" }.count)
            assertMatchesFullBackfill(db)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close() }
    }

    @Test fun incrementalCountsDoNotWriteUnrelatedCategories(): Unit = runBlocking {
        val db = database()
        try {
            val dao = db.library()
            dao.save(Source("s", "Fixture", "opaque", false, 1, 3))
            dao.save(listOf(movie("a", "Alpha"), movie("b", "Beta"), movie("other", "Other", "Unrelated")))
            val engine = Wp22Incremental(db)
            engine.prepare("s", true)
            val sql = db.openHelper.writableDatabase
            sql.execSQL("CREATE TABLE wp22_test_writes(category TEXT NOT NULL)")
            sql.execSQL("CREATE TRIGGER wp22_test_update AFTER UPDATE ON category_counts BEGIN INSERT INTO wp22_test_writes VALUES (NEW.category); END")
            sql.execSQL("CREATE TRIGGER wp22_test_insert AFTER INSERT ON category_counts BEGIN INSERT INTO wp22_test_writes VALUES (NEW.category); END")
            dao.save(listOf(movie("new", "New")))
            engine.prepare("s", true)
            sql.query("SELECT DISTINCT category FROM wp22_test_writes").use {
                assertTrue(it.moveToFirst()); assertEquals("Film", it.getString(0)); assertFalse(it.moveToNext())
            }
            assertEquals(3, db.derived().categories("s").first { it.category == "Film" }.count)
        } finally { db.close() }
    }

    @Test fun interruptedPublicationKeepsDirtyInputForAnAtomicRetry(): Unit = runBlocking {
        val db = database()
        try {
            val dao = db.library()
            dao.save(Source("s", "Fixture", "opaque", false, 1, 2))
            dao.save(listOf(movie("a", "Alpha Dublaj"), movie("b", "Alpha Altyazılı")))
            val engine = Wp22Incremental(db)
            engine.prepare("s", true)
            val before = dao.item("a")!!.versionKey
            dao.assignTmdb(listOf(TmdbAssignment("a", "alpha [tmdb:9]", "")))
            val failure = runCatching {
                db.withTransaction {
                    engine.prepare("s", false)
                    error("interrupted publication")
                }
            }
            assertTrue(failure.isFailure)
            assertEquals(before, dao.item("a")!!.versionKey)
            assertTrue(dao.wp22HasDirty("s"))
            assertEquals(1, engine.prepare("s", false).normalized)
            assertFalse(dao.wp22HasDirty("s"))
            assertEquals("s|MOVIE|tmdb:9", dao.item("b")!!.versionKey)
        } finally { db.close() }
    }

    @Test fun episodeProjectionCursorResumesAndWriterProjectsRowsBehindCursor(): Unit = runBlocking {
        val db = database()
        try {
            val dao = db.library()
            dao.save(Source("s", "Fixture", "opaque", false, 1, 3))
            dao.save(listOf(movie("series", "Show").copy(kind = "SERIES"),
                episode("b", 2), episode("c", 1)))
            // Simulate schema-15 rows; migration itself must not parse a catalog at open.
            db.openHelper.writableDatabase.execSQL("UPDATE items SET episodeVersion=0 WHERE kind='EPISODE'")
            assertTrue(Wp22SeriesBackfill(db).runBatch(1))
            assertEquals("b", dao.wp22ProjectionState()?.cursor)
            dao.save(listOf(episode("a", 3)))
            val restarted = Wp22SeriesBackfill(db)
            while (restarted.runBatch(1)) { }
            val rows = dao.episodes("series").first()
            assertEquals(listOf("c", "b", "a"), rows.map { it.id })
            assertTrue(rows.all { it.episodeVersion == 1 })
            assertEquals(3, wp22SeriesCatalog(rows).items.size)
            assertFalse(restarted.runBatch(1))
        } finally { db.close() }
    }

    private suspend fun assertMatchesFullBackfill(db: LibraryDb) {
        suspend fun outputs() = db.library().browse("s", "MOVIE", "", "", "", 100).first()
            .flatMap { db.library().versionRows(it.versionKey) }
            .associate { it.id to listOf(it.versionKey, it.versionRank.toString(), it.displayTitle, it.displayYear) }
        val incremental = outputs()
        val full = VersionBackfill(db, "s")
        while (full.runBatch(2)) { }
        assertEquals(incremental, outputs())
    }

    private fun movie(id: String, title: String, category: String = "Film") =
        Item(id, "s", title, category, "MOVIE", "", "opaque", title.lowercase(), 1, "")
    private fun episode(id: String, number: Int) = movie(id, "Show S1E$number - Episode")
        .copy(kind = "EPISODE", parent = "series")
}

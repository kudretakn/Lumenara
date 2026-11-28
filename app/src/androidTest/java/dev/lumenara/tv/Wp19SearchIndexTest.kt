package dev.lumenara.tv

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.catalog.searchable
import dev.lumenara.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class Wp19SearchIndexTest {
    private data class Case(val query: String, val source: String = "", val kind: String = "",
        val hidden: List<String> = emptyList(), val shelf: String = "", val year: String = "",
        val rating: Double = 0.0)
    private data class Result(val ids: List<String>, val count: Int, val kinds: List<String>, val index: Int?)

    @Test fun indexedPagesCountsAndFocusEqualInstrAcrossVisibilityAndVersionFixtures() = runBlocking {
        withTimeout(60_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
            try {
                val dao = db.library()
                for (source in listOf("a", "b")) dao.save(Source(source, source, "sealed", false, 0, 0))
                val rows = listOf(
                    item("a:dub", "İstanbul Iğdır Işık Café 2024", category = "Adult")
                        .copy(versionKey = "production", versionRank = 0),
                    item("a:sub", "İstanbul Iğdır Işık Café 2024 Altyazılı")
                        .copy(versionKey = "production", versionRank = 1, isRepresentative = false),
                    item("b:sub", "İstanbul Iğdır Işık Café 2024", source = "b")
                        .copy(versionKey = "other-production"),
                    item("a:legacy", "Déjà vu Amélie").copy(isRepresentative = false),
                    item("a:series", "Café 2025", kind = "SERIES").copy(year = "2025"),
                    item("a:negative", "Café 2024").copy(rating = "-2"),
                    item("a:collision", "ab xx bc yy cd"), item("a:exact", "abcd"),
                    item("a:unicode", "😀 Haber 東京 café"),
                    item("a:episode", "Café 2024", kind = "EPISODE"),
                    item("a:channel", "Café Haber", kind = "LIVE").copy(tvgId = "same"),
                    item("b:channel", "Café Haber", kind = "LIVE", source = "b").copy(tvgId = "same"),
                    item("a:literal", "a_b% c'd 2024"))
                dao.save(rows)
                dao.save(Mark("a:sub", favorite = true, listed = true, position = 10, duration = 100, updated = 1))
                dao.save(Mark("a:channel", favorite = true, updated = 2))
                val cases = listOf("İS", "ıŞ", "café", "déjà", "2024", "ab", "abcd", "olmayan", "東京", "😀", "_b%", "c'd", "i")
                    .map { Case(searchable(it)) } + listOf(
                    Case("cafe", hidden = listOf("Adult")), Case("cafe", source = "a"),
                    Case("cafe", source = "b"), Case("cafe", kind = "MOVIE", hidden = listOf("Adult")),
                    Case("cafe", kind = "SERIES"), Case("cafe", kind = "LIVE"),
                    Case("cafe", shelf = "favorite"), Case("cafe", shelf = "list"),
                    Case("cafe", shelf = "resume"), Case("cafe", shelf = "recent"),
                    Case("cafe", shelf = "popular"), Case("cafe", year = "2025"),
                    Case("cafe", rating = -3.0), Case("cafe", rating = 8.0))
                suspend fun result(case: Case): Result {
                    val all = dao.browsePage(case.source, case.kind, "", case.query, case.shelf, 20, 0,
                        year = case.year, minRating = case.rating, hidden = case.hidden).first()
                    val count = dao.browseTotal(case.source, case.kind, "", case.query, case.shelf,
                        year = case.year, minRating = case.rating, hidden = case.hidden).first()
                    val kinds = dao.browseKinds(case.source, case.kind, "", case.query, case.shelf,
                        year = case.year, minRating = case.rating, hidden = case.hidden).first()
                    val target = all.lastOrNull()?.id ?: "missing"
                    val index = dao.browseIndex(case.source, case.kind, "", case.query, case.shelf, target,
                        year = case.year, minRating = case.rating, hidden = case.hidden)
                    val page = dao.browsePage(case.source, case.kind, "", case.query, case.shelf, 2, 1,
                        year = case.year, minRating = case.rating, hidden = case.hidden).first()
                    assertEquals(all.drop(1).take(2).map { it.id }, page.map { it.id })
                    return Result(all.map { it.id }, count, kinds, index)
                }
                // No readiness row: executes exactly the pre-WP19 instr query.
                assertFalse(dao.wp19SearchReady().first())
                val expected = cases.map { result(it) }
                assertEquals(listOf("a:exact"), expected[cases.indexOf(Case("abcd"))].ids)
                assertTrue(expected[cases.indexOf(Case("olmayan"))].ids.isEmpty())
                assertTrue(result(Case("cafe", kind = "MOVIE", hidden = listOf("Adult"))).ids.contains("a:sub"))
                val backfill = Wp19SearchBackfill(db)
                assertTrue(backfill.runBatch(1))
                assertFalse(dao.wp19SearchReady().first())
                assertEquals(expected.first(), result(cases.first()))
                while (backfill.runBatch(8)) { }
                for ((i, case) in cases.withIndex()) assertEquals(case.toString(), expected[i], result(case))
                // Still equivalent while version assignment is only partly applied.
                db.versionBackfill().save(VersionBackfillState(phase = "apply", cursor = "a:collision"))
                val indexed = cases.map { result(it) }
                db.withTransaction { dao.wp19SaveState(Wp19SearchState(done = false)) }
                for ((i, case) in cases.withIndex()) assertEquals(case.toString(), indexed[i], result(case))
                // Import publication can enable a fully covered index without another cursor walk.
                db.withTransaction { dao.wp19PublishIfCovered() }
                assertTrue(dao.wp19SearchReady().first())
                db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally { db.close() }
        }
    }

    @Test fun backfillResumesOnDiskAndAllWritersRollbackAndCascadeAtomically() = runBlocking {
        withTimeout(60_000) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "wp19-restart-${System.nanoTime()}.db"
            var db = Room.databaseBuilder(context, LibraryDb::class.java, name).build()
            try {
                db.library().save(Source("a", "a", "sealed", false, 0, 0))
                // Raw legacy inserts simulate the empty additive index after 14 -> 15.
                val sql = db.openHelper.writableDatabase
                for (id in listOf("a:b", "a:c", "a:d")) sql.execSQL("""INSERT INTO items
                    (id,source,title,category,kind,tvgId,encryptedUrl,search,generation,logo,parent)
                    VALUES (?,'a','Old','Film','MOVIE','','sealed','old needle',1,'','')""", arrayOf(id))
                db.library().save(Mark("a:b", favorite = true, position = 25, duration = 100))
                assertTrue(Wp19SearchBackfill(db).runBatch(1))
                db.withTransaction { db.library().wp19PublishIfCovered() }
                assertFalse(db.library().wp19SearchReady().first())
                assertEquals(3, db.library().browse("a", "MOVIE", "", "needle", "", 20).first().size)
                val cursor = db.withTransaction { db.library().wp19State()!!.cursor }
                db.close()
                db = Room.databaseBuilder(context, LibraryDb::class.java, name).build()
                assertEquals(cursor, db.withTransaction { db.library().wp19State()!!.cursor })
                assertFalse(db.library().wp19SearchReady().first())
                // A new ID behind the durable cursor and a changed already-indexed row.
                db.library().save(listOf(item("a:a", "New needle"), item("a:b", "Changed needle")))
                while (Wp19SearchBackfill(db).runBatch(1)) { }
                suspend fun ids(query: String) = db.library().browse("a", "MOVIE", "", query, "", 20)
                    .first().map { it.id }.toSet()
                assertEquals(setOf("a:a", "a:b", "a:c", "a:d"), ids("needle"))
                assertEquals(setOf("a:b"), ids("changed"))
                db.library().assignTmdb(listOf(TmdbAssignment("a:b", "metadata 2026", "2026")))
                assertEquals(setOf("a:b"), ids("metadata"))
                assertFalse(ids("needle").contains("a:b"))
                db.withTransaction { db.library().writeBatch(listOf(item("a:c", "Batch renamed"))) }
                assertEquals(setOf("a:c"), ids("batch"))
                val document = db.withTransaction { db.library().wp19Document("a:c")!!.documentId }
                db.withTransaction { db.library().writeBatch(listOf(item("a:c", "Batch renamed"))) }
                assertEquals(document, db.withTransaction { db.library().wp19Document("a:c")!!.documentId })
                try {
                    db.withTransaction {
                        db.library().save(listOf(item("a:c", "Rolled back")))
                        throw Wp19Rollback()
                    }
                } catch (_: Wp19Rollback) { }
                assertEquals(setOf("a:c"), ids("batch"))
                assertTrue(ids("rolled").isEmpty())
                assertEquals(25L, db.library().mark("a:b")!!.position)
                db.library().deleteSource("a")
                for (table in listOf("wp19_search_documents", "wp19_search_grams")) {
                    db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
                        assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                    }
                }
                db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            } finally { db.close(); context.deleteDatabase(name) }
        }
    }

    @Test fun mixedSpanWindowMatchesFullReferenceWithoutReturningPrefixKinds() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        try {
            val dao = db.library()
            dao.save(Source("a", "a", "sealed", false, 0, 0))
            dao.save((0 until 121).map { index -> item("a:$index", "Title ${index.toString().padStart(3, '0')}",
                kind = if (index % 13 == 7 || index % 13 == 10) "LIVE" else "MOVIE") })
            val kinds = dao.browseKinds("a", "", "", "", "").first()
            var column = 0
            val expected = kinds.map {
                val span = if (it == "LIVE") 2 else 1
                if (column + span > 8) column = 0
                column.also { column = (column + span) % 8 }
            }
            for (offset in listOf(0, 7, 19, 60, 119, 130)) {
                val window = dao.wp19SpanWindow("a", "", "", "", "", "", "", 0.0,
                    emptyList(), offset, 20, 8).first()
                assertEquals(offset, window.offset)
                assertEquals(kinds.drop(offset).take(20).map { it == "LIVE" }, window.live)
                assertEquals(expected.drop(offset).take(20), window.columns)
                assertTrue(window.live.size <= 20)
            }
        } finally { db.close() }
    }

    private class Wp19Rollback : RuntimeException()
    private fun item(id: String, title: String, category: String = "Film", kind: String = "MOVIE", source: String = "a") =
        Item(id, source, title, category, kind, "", "sealed", searchable(title), 1, "",
            year = "2024", rating = "7.5")
}

package dev.lumenara.tv

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class VersionBackfillTest {
    @Test fun reopenResumesEveryPhaseAndDistantVersionsMatchImportGrouping() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "version-backfill-resume"
        context.deleteDatabase(name)
        fun open() = Room.databaseBuilder(context, LibraryDb::class.java, name).build()
        var db = open()
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR).toString()
        val rows = listOf(
            row("000-sub", "Kıyı Altyazılı"),
            row("001-translated", "The Coast", tmdb = "42"),
            row("002-conflict", "Başka Film", tmdb = "100"),
            row("003-conflict", "Başka Film", tmdb = "101"),
            row("004-unknown", "Başka Film"),
            row("005-pipe", "Kıyı | Sahil", tmdb = "42"),
        ) + (1..501).map { row("filler-${it.toString().padStart(4, '0')}", "Film $it") } +
            row("zzz-dub", "Kıyı ($year) Dublaj", tmdb = "42")
        try {
            db.library().save(Source("a", "Test", "sealed", false, 0, rows.size))
            // Stale flags/keys must not hide any production before its batch is applied.
            db.library().save(rows.map { it.copy(versionKey = "stale", isRepresentative = false) })
            db.library().save(Mark("000-sub", favorite = true, position = 100, duration = 1000,
                updated = System.currentTimeMillis()))
            db.versionBackfill().save(VersionBackfillState())
            assertEquals(rows.size, db.library().browse("a", "MOVIE", "", "", "", 1000).first().size)
            assertTrue(db.library().browse("a", "MOVIE", "", "", "", 1000).first().all { it.title.isNotEmpty() })
            assertEquals("000-sub", db.library().continueWatching("a").first().single().id)
            assertTrue(VersionBackfill(db).runBatch())
            assertEquals(rows[499].id, db.versionBackfill().state()!!.cursor)

            val reopenedPhases = mutableSetOf<String>()
            while (true) {
                val saved = db.versionBackfill().state()!!
                if (saved.phase == "done") break
                // Close/reopen mid-phase, not just at its boundary, as a killed process would.
                if (saved.cursor.isNotEmpty() && reopenedPhases.add(saved.phase)) {
                    db.close()
                    db = open()
                    assertEquals(saved, db.versionBackfill().state())
                }
                val before = db.versionBackfill().state()!!
                if (before.phase == "apply") {
                    // Simulate a killed/failed transaction: assignments and cursor both roll back.
                    try {
                        db.withTransaction {
                            VersionBackfill(db).runBatch()
                            throw CancellationException("test rollback")
                        }
                        fail("Expected cancellation")
                    } catch (_: CancellationException) { }
                    assertEquals(before, db.versionBackfill().state())
                }
                VersionBackfill(db).runBatch()
                assertTrue(db.library().browse("a", "MOVIE", "", "", "", 1000).first().isNotEmpty())
                assertEquals("000-sub", db.library().continueWatching("a").first().single().id)
            }
            assertEquals(setOf("normalize", "resolve", "apply"), reopenedPhases)
            assertEquals(rows.size.toLong(), db.versionBackfill().state()!!.total)
            assertTrue(db.versionBackfill().rows("", 500).isEmpty())
            for (expected in rows.versionAssignments()) {
                val actual = db.library().item(expected.id)!!
                assertEquals(expected.versionKey, actual.versionKey)
                assertEquals(expected.isRepresentative, actual.isRepresentative)
                assertEquals(expected.versionRank, actual.versionRank)
                assertEquals(expected.displayTitle, actual.displayTitle)
                assertEquals(expected.displayYear, actual.displayYear)
                assertEquals("sealed", actual.encryptedUrl)
            }
            val grouped = db.library().item("zzz-dub")!!
            assertEquals(setOf("000-sub", "001-translated", "005-pipe", "zzz-dub"),
                db.library().versionRows(grouped.versionKey).map { it.id }.toSet())
            assertEquals(100L, db.library().mark("000-sub")!!.position)
            assertFalse(VersionBackfill(db).runBatch())
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun refreshedSourceDiscardsStagedAssignmentsBeforeTheyCanOverwriteTheImport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        try {
            db.library().save(Source("a", "Test", "sealed", false, 0, 1))
            db.library().save(listOf(row("old", "Eski")))
            db.versionBackfill().save(VersionBackfillState())
            VersionBackfill(db).runBatch()
            db.withTransaction {
                db.versionBackfill().discardSource("a")
                val refreshed = row("old", "Yeni Dublaj")
                db.library().save(listOf(refreshed))
                db.library().assignVersions(listOf(refreshed).versionAssignments())
            }
            while (VersionBackfill(db).runBatch()) { }
            assertEquals("Yeni", db.library().item("old")!!.displayTitle)
        } finally { db.close() }
    }

    private fun row(id: String, title: String, tmdb: String = "") = Item(id, "a", title, "Film",
        "MOVIE", "", "sealed", "$title [tmdb:$tmdb]", 1, "")
}

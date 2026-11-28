package dev.lumenara.tv

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class Wp07MaintenanceTest {
    @Test fun timeSlicedUpgradeKeepsCursorAtomicAndMatchesRefreshAssignments() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, LibraryDb::class.java).build()
        val rows = (0 until 16).map { index ->
            Item(index.toString().padStart(3, '0'), "wp07", "Film ${index / 2}", "Film", "MOVIE",
                "", "sealed", "film", 1, "")
        }
        try {
            db.library().save(Source("wp07", "Test", "sealed", false, 0, rows.size))
            db.library().save(rows)
            db.versionBackfill().save(VersionBackfillState())
            val work = VersionBackfill(db)
            assertTrue(work.runBatch(limit = 8, timeBudgetMs = 1))
            val before = db.versionBackfill().state()!!
            assertEquals("007", before.cursor)
            try {
                db.withTransaction {
                    work.runBatch(limit = 8, timeBudgetMs = 1)
                    throw CancellationException("Rollback the batch")
                }
                fail("Expected rollback")
            } catch (_: CancellationException) { }
            assertEquals(before, db.versionBackfill().state())
            var batches = 0
            while (work.runBatch(limit = 8, timeBudgetMs = 1)) {
                assertTrue("Small fixture must terminate", ++batches < 100)
            }
            assertEquals(rows.size.toLong(), db.versionBackfill().state()!!.total)
            for (expected in rows.versionAssignments()) {
                val actual = db.library().item(expected.id)!!
                assertEquals(expected.versionKey, actual.versionKey)
                assertEquals(expected.versionRank, actual.versionRank)
                assertEquals(expected.isRepresentative, actual.isRepresentative)
                assertEquals(expected.displayTitle, actual.displayTitle)
            }
        } finally { db.close() }
    }
}

package dev.lumenara.data

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import org.junit.Assert.*
import org.junit.Test

class CatalogPagingQueryTest {
    @Test fun pendingGridsUseOriginalTitleIndexesAndKeepVersionAndShelfPolicy() {
        for (source in listOf("", "provider")) for (category in listOf("", "Films")) {
            val filter = CatalogFilter(source = source, kind = "MOVIE", category = category,
                hidden = listOf("XXX"), derived = false)
            val page = browseQuery(filter, 80)
            assertFalse(page.sql.contains("sortKey"))
            assertTrue(page.sql.contains("ORDER BY i.title,i.id"))
            assertTrue(page.sql.contains("INDEXED BY index_items_"))
            assertTrue(page.sql.contains("v.versionRank<i.versionRank"))
            assertTrue(browseIndexQuery(filter, "saved").sql.contains("(i.title,i.id)<(t.title,t.id)"))
            assertEquals(arguments(page).dropLast(2), arguments(browseCountQuery(filter)))
        }
        val popular = browseQuery(CatalogFilter(kind = "MOVIE", shelf = "popular", derived = false), 80)
        assertTrue(popular.sql.contains("ORDER BY CAST(i.rating AS REAL) DESC,i.title,i.id"))
    }

    @Test fun pendingLivePagesNeighborsIndexAndCountsUseProviderOrderAndOriginalRows() {
        val filter = CatalogFilter(source = "provider", hidden = listOf("XXX"), derived = false)
        val page = livePageQuery(filter, 28, LiveCursor("partly-filled", "anchor"))
        assertTrue(page.sql.contains("ORDER BY i.rowid ASC"))
        assertTrue(page.sql.contains("SELECT t.rowid"))
        assertFalse(page.sql.contains("sortKey"))
        assertFalse(arguments(page).contains("partly-filled"))
        assertTrue(arguments(page).contains("anchor"))
        assertTrue(livePageQuery(filter, 28, backwards = true, targetId = "anchor").sql.contains("ORDER BY i.rowid DESC"))
        assertTrue(liveIndexQuery(filter, "anchor").sql.contains("i.rowid<t.rowid"))
        assertTrue(liveChannelOrderQuery(filter).sql.contains("ORDER BY i.rowid"))
        val count = liveCountQuery(filter)
        assertTrue(count.sql.contains("FROM items i"))
        assertFalse(count.sql.contains("category_counts"))
    }

    private fun arguments(query: SupportSQLiteQuery): List<Any?> {
        val values = MutableList<Any?>(query.argCount) { null }
        query.bindTo(object : SupportSQLiteProgram {
            override fun bindNull(index: Int) { values[index - 1] = null }
            override fun bindLong(index: Int, value: Long) { values[index - 1] = value }
            override fun bindDouble(index: Int, value: Double) { values[index - 1] = value }
            override fun bindString(index: Int, value: String) { values[index - 1] = value }
            override fun bindBlob(index: Int, value: ByteArray) { values[index - 1] = value }
            override fun clearBindings() = Unit
            override fun close() = Unit
        })
        return values
    }

    @Test fun offsetAndLimitAreBoundAndFirstPageUsesExistingStreamingIndex() {
        val filter = CatalogFilter(source = "provider", kind = "MOVIE", category = "Films")
        val first = browseQuery(filter, 80)
        val distant = browseQuery(filter, 400, 8_000)
        assertTrue(first.sql.contains("INDEXED BY index_items_source_kind_category_sortKey_id"))
        assertTrue(first.sql.endsWith("LIMIT ? OFFSET ?"))
        assertEquals(listOf(80L, 0L), arguments(first).takeLast(2))
        assertEquals(listOf(400L, 8_000L), arguments(distant).takeLast(2))
        assertEquals(first.sql, distant.sql)
    }

    @Test fun pageCountAndKindsShareExactEligibilityAndVersionSelection() {
        val filter = CatalogFilter(source = "' OR 1=1 --", kind = "MOVIE", shelf = "popular",
            year = "year", genre = "genre", language = "tr", minRating = 7.0, hidden = listOf("Adult"))
        val page = browseQuery(filter, 80, 160)
        val count = browseCountQuery(filter)
        val kinds = browseKindsQuery(filter)
        assertEquals(arguments(page).dropLast(2), arguments(count))
        assertEquals(arguments(count), arguments(kinds))
        for (query in listOf(page, count, kinds)) {
            assertFalse(query.sql.contains(filter.source))
            assertTrue(query.sql.contains("v.versionRank<i.versionRank"))
            assertTrue(query.sql.contains("i.category NOT IN (?)"))
        }
        assertTrue(page.sql.contains("ORDER BY CAST(i.rating AS REAL) DESC,i.sortKey,i.id"))
        assertFalse(count.sql.contains("ORDER BY"))
        assertFalse(count.sql.contains("LIMIT"))
        assertTrue(kinds.sql.startsWith("SELECT i.kind"))
    }

    @Test fun mixedSearchCountsAndSpansPreserveLiveDeduplication() {
        val filter = CatalogFilter(query = "needle")
        val page = browseQuery(filter, 80)
        val count = browseCountQuery(filter)
        val kinds = browseKindsQuery(filter)
        for (query in listOf(page, count, kinds)) {
            assertTrue(query.sql.contains("UNION ALL"))
            assertTrue(query.sql.contains("i.kind!='LIVE'"))
            assertTrue(query.sql.contains("HAVING i.id=MIN(i.id)"))
        }
        assertEquals(arguments(page).dropLast(2), arguments(count))
    }

    @Test fun restoredIndexUsesShelfOrderWithStableTitleIdTies() {
        for (shelf in listOf("recent", "popular", "resume")) {
            val query = browseIndexQuery(CatalogFilter(kind = "MOVIE", shelf = shelf), "saved-id")
            assertTrue(query.sql.contains("i.catalogSort>t.catalogSort"))
            assertTrue(query.sql.contains("(i.sortKey,i.id)<(t.sortKey,t.id)"))
            assertEquals("saved-id", arguments(query).last())
        }
        val query = browseIndexQuery(CatalogFilter(kind = "SERIES"), "saved-id")
        assertFalse(query.sql.contains("i.catalogSort>t.catalogSort"))
    }
}

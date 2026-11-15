package dev.lumenara.data

import org.junit.Assert.*
import org.junit.Test

class LiveQueryTest {
    @Test fun optionalFiltersAreOmittedAndValuesAreBound() {
        val plain = livePageQuery(CatalogFilter(), 80)
        assertFalse(plain.sql.contains(" OR "))
        assertFalse(plain.sql.contains("source=?"))
        assertFalse(plain.sql.contains("category=?"))
        assertFalse(plain.sql.contains("instr("))
        assertFalse(plain.sql.contains("marks"))
        assertFalse(plain.sql.contains("OFFSET"))
        assertEquals(2, plain.argCount) // LIVE, limit

        val attack = "' OR 1=1 --"
        val filter = CatalogFilter(source = attack, category = "News", query = "news",
            shelf = "favorite", hidden = listOf("Adults", "18+"))
        for (sql in listOf(livePageQuery(filter, 80), liveCountQuery(filter),
            liveIndexQuery(filter, attack), liveChannelOrderQuery(filter))) {
            assertFalse(sql.sql.contains(attack))
            assertTrue(sql.sql.contains("source=?"))
            assertTrue(sql.sql.contains("category=?"))
            assertTrue(sql.sql.contains("NOT IN (?, ?)"))
            assertTrue(sql.sql.contains("EXISTS(SELECT 1 FROM marks"))
            assertFalse(sql.sql.contains("LEFT JOIN marks"))
        }
    }

    @Test fun adjacentPagesUseTheSameBinaryTitleIdCursorInBothDirections() {
        val filter = CatalogFilter(source = "a", category = "News")
        val cursor = LiveCursor("Same title", "a:80")
        val forward = livePageQuery(filter, 80, cursor)
        val backward = livePageQuery(filter, 80, cursor, backwards = true)
        assertTrue(forward.sql.contains("(i.title,i.id)>(?,?)"))
        assertTrue(backward.sql.contains("(i.title,i.id)<(?,?)"))
        assertTrue(backward.sql.contains("ORDER BY i.title DESC,i.id DESC"))
        assertFalse(forward.sql.contains("OFFSET"))
        assertFalse(backward.sql.contains("OFFSET"))
        assertEquals(6, forward.argCount) // source, LIVE, category, title, id, limit
    }

    @Test fun arbitraryJumpOffsetsOnlyTheLightweightCursor() {
        val sql = livePageQuery(CatalogFilter(source = "a"), 240, offset = 8000).sql
        assertTrue(sql.contains("(i.title,i.id)>(SELECT a.title,a.id"))
        assertTrue(sql.contains("LIMIT 1 OFFSET ?"))
        assertEquals(1, Regex("OFFSET").findAll(sql).count())
        assertTrue(sql.contains("INDEXED BY index_items_kind_title"))
        assertFalse(liveChannelOrderQuery(CatalogFilter(source = "a")).sql.contains("ORDER BY i.source"))
    }

    @Test fun playbackResolvesTheCurrentTargetInsideTheSeekStatement() {
        val sql = livePageQuery(CatalogFilter(source = "a", hidden = listOf("Adults")), 80,
            targetId = "a:80", backwards = true)
        assertTrue(sql.sql.contains("(i.title,i.id)<(SELECT t.title,t.id"))
        assertTrue(sql.sql.contains("WHERE t.id=?"))
        assertTrue(sql.sql.contains("t.category NOT IN (?)"))
        assertFalse(sql.sql.contains("a:80"))
        assertFalse(sql.sql.contains("OFFSET"))
        assertEquals(8, sql.argCount) // source/kind/hidden twice, target id, limit
    }

    @Test fun liveShelvesKeepTheirPreviousFavouriteOnlySemantics() {
        for (shelf in listOf("list", "resume", "recent", "popular")) {
            val sql = livePageQuery(CatalogFilter(shelf = shelf), 80).sql
            assertFalse(sql.contains("marks"))
            assertFalse(sql.contains("rating"))
        }
    }
}

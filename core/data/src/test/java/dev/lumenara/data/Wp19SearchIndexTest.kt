package dev.lumenara.data

import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import dev.lumenara.catalog.searchable
import org.junit.Assert.*
import org.junit.Test

class Wp19SearchIndexTest {
    @Test fun normalizedTurkishAccentsYearsAndEverySubstringHaveAllCandidateGrams() {
        val fixtures = listOf("İSTANBUL IĞDIR ışık", "Café déjà vu 2024", "Amélie [Altyazılı]",
            "a_b% c'd", "東京ニュース", "😀 Haber 🌙", "aaaa ababa", "", "i")
        for (raw in fixtures) {
            val text = searchable(raw)
            val points = text.codePoints().toArray()
            for (start in points.indices) for (length in 1..points.size - start) {
                val query = String(points, start, length)
                assertTrue("$raw / $query", wp19Grams(text).containsAll(wp19ProbeGrams(query)))
            }
        }
        assertEquals(searchable("istanbul igdir isik"), searchable(fixtures[0]))
        assertEquals(searchable("cafe deja vu 2024"), searchable(fixtures[1]))
        assertEquals(1, wp19Grams("😀a").size)
        assertEquals(1, wp19Grams("aaaa").size)
    }

    @Test fun shortQueriesAndArbitraryLongInputsHaveBoundedCandidateBinds() {
        assertTrue(wp19ProbeGrams("").isEmpty())
        assertTrue(wp19ProbeGrams("i").isEmpty())
        assertTrue(wp19ProbeGrams("😀").isEmpty())
        assertEquals(1, wp19ProbeGrams("is").size)
        assertTrue(wp19ProbeGrams("abcdefghijklmnopqrstuvwxyz".repeat(100)).size <= 3)
        val fallback = browseQuery(CatalogFilter(query = "i"), 20, 40)
        assertFalse(fallback.sql.contains("wp19_search_grams"))
        assertTrue(fallback.sql.endsWith("LIMIT ? OFFSET ?"))
        assertEquals(listOf(20L, 40L), arguments(fallback).takeLast(2))
    }

    @Test fun gramFalsePositivesStillRequireTheOriginalSubstringAndSiblingPredicates() {
        // All bigrams occur, but in the wrong order with gaps. The index is not an answer.
        assertTrue(wp19Grams("ab xx bc yy cd").containsAll(wp19ProbeGrams("abcd")))
        assertFalse("ab xx bc yy cd".contains("abcd"))
        val filter = CatalogFilter(source = "' OR 1=1 --", kind = "MOVIE", query = "abcd",
            hidden = listOf("Adult"), year = "2024", searchGram = wp19ProbeGrams("abcd").first())
        for (query in listOf(browseQuery(filter, 20), browseCountQuery(filter),
            browseKindsQuery(filter), browseIndexQuery(filter, "saved"))) {
            assertTrue(query.sql.contains("instr(i.search,?)>0"))
            assertTrue(query.sql.contains("instr(v.search,?)>0"))
            assertTrue(query.sql.contains("i.category NOT IN (?)"))
            assertTrue(query.sql.contains("v.versionRank<i.versionRank"))
            assertTrue(query.sql.contains("INDEXED BY sqlite_autoindex_items_1"))
            assertTrue(query.sql.contains("WHERE gram=?"))
            assertFalse(query.sql.contains(filter.source))
            assertEquals(query.sql.count { it == '?' }, query.argCount)
            assertEquals(query.argCount, arguments(query).size)
        }
        val pending = browseQuery(filter.copy(searchGram = null), 20)
        assertFalse(pending.sql.contains("wp19_search"))
        assertTrue(pending.sql.contains("INDEXED BY index_items_source_kind_sortKey_id"))
    }

    private fun arguments(query: SupportSQLiteQuery): List<Any?> {
        val args = MutableList<Any?>(query.argCount) { null }
        query.bindTo(object : SupportSQLiteProgram {
            override fun bindNull(index: Int) { args[index - 1] = null }
            override fun bindLong(index: Int, value: Long) { args[index - 1] = value }
            override fun bindDouble(index: Int, value: Double) { args[index - 1] = value }
            override fun bindString(index: Int, value: String) { args[index - 1] = value }
            override fun bindBlob(index: Int, value: ByteArray) { args[index - 1] = value }
            override fun clearBindings() = Unit
            override fun close() = Unit
        })
        return args
    }
}

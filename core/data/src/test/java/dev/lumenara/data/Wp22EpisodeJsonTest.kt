package dev.lumenara.data

import java.io.Reader
import org.junit.Assert.*
import org.junit.Test

class Wp22EpisodeJsonTest {
    @Test fun emitsBeforeReadingTheRestOfTheResponse(): Unit {
        var emitted = false
        val prefix = """{"ignored":{"nested":[true,null,{"escaped":"a\\\"b"}]},"episodes":{"1":[{"id":"1"},"""
        val json = prefix + "{\"id\":\"2\"}]}}"
        val input = object : Reader() {
            var offset = 0
            override fun read(buffer: CharArray, start: Int, length: Int): Int {
                if (offset >= prefix.length && !emitted) error("Read the entire episode list before emitting")
                if (offset == json.length) return -1
                buffer[start] = json[offset++]
                return 1
            }
            override fun close() = Unit
        }
        var count = 0
        Wp22EpisodeJson(input).read { season, index, episode ->
            emitted = true
            assertEquals("1", season)
            assertEquals(++count, index + 1)
            assertEquals(count.toString(), episode.getString("id"))
        }
        assertEquals(2, count)
    }

    @Test fun streams39682EpisodesWithOnlyOneRecordMaterialized(): Unit {
        val parts = sequence {
            yield("{\"episodes\":{\"1\":[")
            repeat(39682) { index ->
                if (index > 0) yield(",")
                yield("{\"id\":\"$index\",\"title\":\"Episode $index\",\"info\":{\"duration_secs\":2400}}")
            }
            yield("]},\"ignored\":[null,true,false,1.5e+2]}")
        }.iterator()
        val input = object : Reader() {
            var part = ""
            var offset = 0
            override fun read(buffer: CharArray, start: Int, length: Int): Int {
                if (length == 0) return 0
                while (offset == part.length) {
                    if (!parts.hasNext()) return -1
                    part = parts.next(); offset = 0
                }
                buffer[start] = part[offset++]
                return 1
            }
            override fun close() = Unit
        }
        var count = 0
        Wp22EpisodeJson(input).read { _, index, row ->
            assertEquals(count, index)
            assertEquals(count.toString(), row.getString("id"))
            count++
        }
        assertEquals(39682, count)
    }

    @Test fun missingMalformedOrTruncatedEpisodesCannotBecomeAnEmptyCatalog(): Unit {
        for (json in listOf("{}", "{\"episodes\":null}", "{\"episodes\":[1]}",
            "{\"episodes\":{\"1\":{}}}", "{\"episodes\":{\"1\":[{\"id\":1}]}",
            "{\"episodes\":{},\"oops\":tru}")) {
            assertTrue(json, runCatching { Wp22EpisodeJson(json.reader()).read { _, _, _ -> } }.isFailure)
        }
        for (json in listOf("{\"episodes\":[]}", "{\"episodes\":{}}")) {
            var count = 0
            Wp22EpisodeJson(json.reader()).read { _, _, _ -> count++ }
            assertEquals(0, count)
        }
    }

    @Test fun malformedIndividualEntriesKeepProviderArrayIndicesAndCancellationStopsReading(): Unit {
        val rows = mutableListOf<Int>()
        Wp22EpisodeJson("""{"episodes":{"2":[null,7,{},false,{"id":"ok"}]}}""".reader())
            .read { _, index, _ -> rows += index }
        assertEquals(listOf(2, 4), rows)
        val cancelled = kotlinx.coroutines.CancellationException("fixture")
        val error = runCatching {
            Wp22EpisodeJson("""{"episodes":{"1":[{"id":1},{"id":2}]}}""".reader())
                .read { _, _, _ -> throw cancelled }
        }.exceptionOrNull()
        assertSame(cancelled, error)
    }
}

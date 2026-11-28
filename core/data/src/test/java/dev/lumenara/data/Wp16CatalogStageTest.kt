package dev.lumenara.data

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Wp16CatalogStageTest {
    @get:Rule val cache = TemporaryFolder()

    @Test fun encryptedStagePreservesEveryLegacyFieldAndBatchBoundary() {
        val privateUrl = "https://provider.invalid/user/wp16-stage-secret/item.mp4"
        val expected = (0..256).map { index ->
            Item("id-$index", "source", "Başlık $index", "Grup", if (index == 0) "SERIES" else "EPISODE",
                "tvg-$index", privateUrl, "arama", 71L, privateUrl,
                parent = if (index == 0) "" else "id-0", summary = "Satır\n\"$privateUrl\"", year = "2025",
                genre = "Drama", rating = "8.2", language = "tr", castNames = "Oyuncu", archive = privateUrl,
                archiveDays = 3, archiveZone = "Europe/Istanbul", introStartMs = 100L, introEndMs = 900L,
                backdrop = privateUrl, providerId = "provider-$index", tvgName = "Kanal $index", tvgGroup = "TV")
        }
        CatalogStage(cache.root, "source", 71L, EncryptedSpool.ephemeral()).use { stage ->
            stage.write { emit -> expected.forEach(emit) }
            File(cache.root, "catalog-import").walkTopDown().filter { it.isFile }.forEach { file ->
                val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(bytes.contains(privateUrl))
                assertFalse(bytes.contains("wp16-stage-secret"))
            }
            val actual = mutableListOf<Item>()
            val batches = mutableListOf<Int>()
            stage.read { rows -> batches += rows.size; actual.addAll(rows) }
            assertEquals(listOf(128, 128, 1), batches)
            assertEquals(expected, actual)
        }
        assertTrue(File(cache.root, "catalog-import").listFiles()!!.isEmpty())
    }

    @Test fun emptyAndCancelledStageNeverLeavePlaintext() {
        CatalogStage(cache.root, "source", 1L).use { stage ->
            assertTrue(runCatching { stage.write { } }.exceptionOrNull() is SourceFailure)
        }
        val cancellation = kotlinx.coroutines.CancellationException("secret-provider-url")
        CatalogStage(cache.root, "source", 1L).use { stage ->
            assertSame(cancellation, runCatching { stage.write { throw cancellation } }.exceptionOrNull())
        }
        assertTrue(File(cache.root, "catalog-import").listFiles()!!.isEmpty())
    }
}

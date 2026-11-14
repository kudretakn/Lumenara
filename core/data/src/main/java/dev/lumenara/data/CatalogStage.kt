package dev.lumenara.data

import java.io.Closeable
import java.io.File
import org.json.JSONArray

/**
 * Imports are serialized by Repository. Only sealed locators and public metadata reach this file.
 */
internal class CatalogStage(cache: File, private val source: String, private val generation: Long) :
    Closeable {
    private val dir = File(cache, "catalog-import").apply { mkdirs() }
    private val file = File(dir, "pending.ndjson")

    fun write(produce: ((Item) -> Unit) -> Unit) {
        var count = 0
        file.bufferedWriter().use { writer ->
            produce { item ->
                if (++count > 300000) throw SourceFailure("Kaynak 300.000 içerik sınırını aştı.")
                writer.appendLine(
                    JSONArray(
                            listOf(
                                item.id,
                                item.title,
                                item.category,
                                item.kind,
                                item.tvgId,
                                item.encryptedUrl,
                                item.search,
                                item.logo,
                                item.summary,
                                item.year,
                                item.genre,
                                item.rating,
                                item.language,
                                item.castNames,
                                item.archive,
                                item.archiveDays,
                                item.archiveZone,
                                item.introStartMs,
                                item.introEndMs,
                                item.backdrop,
                                item.parent,
                            )
                        )
                        .toString()
                )
            }
        }
        if (count == 0)
            throw SourceFailure(
                "Listede kullanılabilir yayın yok. HTTP uyumluluğu seçimini kontrol edin."
            )
    }

    fun read(insert: (List<Item>) -> Unit) {
        val batch = ArrayList<Item>(128)
        file.forEachLine { line ->
            val row = JSONArray(line)
            batch +=
                Item(
                    row.getString(0),
                    source,
                    row.getString(1),
                    row.getString(2),
                    row.getString(3),
                    row.getString(4),
                    row.getString(5),
                    row.getString(6),
                    generation,
                    row.getString(7),
                    summary = row.getString(8),
                    year = row.getString(9),
                    genre = row.getString(10),
                    rating = row.getString(11),
                    language = row.getString(12),
                    castNames = row.getString(13),
                    archive = row.getString(14),
                    archiveDays = row.getInt(15),
                    archiveZone = row.getString(16),
                    introStartMs = row.getLong(17),
                    introEndMs = row.getLong(18),
                    backdrop = row.getString(19),
                    parent = row.getString(20),
                )
            if (batch.size == 128) {
                insert(batch)
                batch.clear()
            }
        }
        if (batch.isNotEmpty()) insert(batch)
    }

    override fun close() {
        file.delete()
    }
}

package dev.lumenara.tv

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.LibraryDb
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationTest {
    @Test
    fun oldCatalogSurvivesSchemaUpgrade() = verifyUpgrade(1)

    @Test
    fun versionSevenDefaultsKeepUnrefreshedVersionsAndMarksVisible() = verifyUpgrade(7)

    @Test
    fun versionEightCatalogSurvivesCategoryAndProgrammeIndexes() = verifyUpgrade(8)

    private fun verifyUpgrade(from: Int) {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val name = "lumenara-migration-test-$from"
        context.deleteDatabase(name)
        try {
            val schema =
                test.context.assets
                    .open("dev.lumenara.data.LibraryDb/$from.json")
                    .bufferedReader()
                    .use {
                        JSONObject(it.readText()).getJSONObject("database")
                    }
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { db ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    fun run(sql: String) =
                        db.execSQL(sql.replace("${'$'}{TABLE_NAME}", entity.getString("tableName")))
                    run(entity.getString("createSql"))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) run(
                        indices.getJSONObject(j).getString("createSql")
                    )
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
                db.execSQL("INSERT INTO sources VALUES ('local','Test','opaque',0,0,1)")
                db.execSQL(
                    """INSERT INTO items (id,source,title,category,kind,tvgId,encryptedUrl,search,generation,logo,parent)
                    VALUES ('item','local','Başlık','Spor','LIVE','1','opaque','baslik',1,'','')"""
                )
                for ((id, title) in listOf("dub" to "Film (Dublaj)", "sub" to "Film (Altyazılı)")) {
                    db.execSQL("""INSERT INTO items (id,source,title,category,kind,tvgId,encryptedUrl,search,generation,logo,parent)
                        VALUES (?, 'local', ?, 'Film', 'MOVIE', '', 'opaque', 'film', 1, '', '')""", arrayOf(id, title))
                }
                db.execSQL("INSERT INTO marks VALUES ('item',1,0,250,1000,1)")
                if (from >= 7) {
                    db.execSQL("INSERT INTO feeds VALUES ('guide','local','opaque')")
                    db.execSQL("INSERT INTO programmes VALUES ('guide','1',100,200,'Program','Description','program')")
                }
                db.version = from
            }
            val db = Room.databaseBuilder(context, LibraryDb::class.java, name)
                .addMigrations(LibraryDb.MIGRATION_7_8, LibraryDb.MIGRATION_8_9).build()
            try {
                runBlocking {
                    assertEquals(250L, db.library().mark("item")?.position)
                    val old = db.library().item("dub")!!
                    assertEquals("", old.versionKey)
                    assertEquals("", old.displayTitle)
                    assertEquals("", old.displayYear)
                    assertEquals(0, old.versionRank)
                    assertTrue(old.isRepresentative)
                    assertEquals(2, db.library().browse("local", "MOVIE", "", "", "", 120).first().size)
                    assertEquals(1, db.library().browse("local", "LIVE", "", "", "favorite", 120).first().size)
                    assertEquals(2, db.library().categoryCounts("MOVIE", "local").first().single().count)
                }
                db.openHelper.readableDatabase.query("PRAGMA index_list(items)").use { cursor ->
                    val names = buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) }
                    assertTrue(names.containsAll(listOf("index_items_source_kind_category_title",
                        "index_items_kind_category_title", "index_items_kind_isRepresentative_title",
                        "index_items_parent", "index_items_versionKey")))
                }
                db.openHelper.readableDatabase.query("PRAGMA index_info(index_items_kind_category_title)").use { cursor ->
                    val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(2)) }
                    assertEquals(listOf("kind", "category", "title"), columns)
                }
                db.openHelper.readableDatabase.query("PRAGMA index_info(index_programmes_start)").use { cursor ->
                    val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(2)) }
                    assertEquals(listOf("start"), columns)
                }
                assertEquals(9, db.openHelper.readableDatabase.version)
                if (from >= 7) db.openHelper.readableDatabase.query("SELECT title,start FROM programmes").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Program", cursor.getString(0))
                    assertEquals(100L, cursor.getLong(1))
                }
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}

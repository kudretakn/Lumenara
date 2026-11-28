package dev.lumenara.tv

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.LibraryDb
import dev.lumenara.data.CatalogDerivedBackfill
import dev.lumenara.data.VersionBackfill
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
    fun everyIntermediateAutoMigrationPreservesUserState() {
        for (version in 2..6) verifyUpgrade(version)
    }

    @Test
    fun currentSchemaReopensWithoutChangingUserState() {
        // Keep the recovery harness's existing method inventory while covering both
        // the last published schema's index-only upgrade and the current reopen.
        verifyUpgrade(13)
        verifyUpgrade(14)
    }

    @Test
    fun versionSevenUnrefreshedVersionsAreGroupedWithoutLosingStreamsOrMarks() = verifyUpgrade(7)

    @Test
    fun versionEightCatalogSurvivesCategoryAndProgrammeIndexes() = verifyUpgrade(8)

    @Test
    fun versionNineCatalogSurvivesRestoreJournalMigration() = verifyUpgrade(9)

    @Test
    fun versionTenRecomputesStaleProviderVersionKeys() = verifyUpgrade(10)

    @Test
    fun versionElevenAddsCarryIdentitiesWithoutChangingUserState() = verifyUpgrade(11)

    @Test
    fun versionTwelveOnlyAddsDdlAndKeepsFinishedVersionBackfillFinished() = verifyUpgrade(12)

    private fun verifyUpgrade(from: Int) {
        val year = java.util.Calendar.getInstance().apply { timeInMillis = System.currentTimeMillis() }
            .get(java.util.Calendar.YEAR).toString()
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
                for ((id, title) in listOf("dub" to "TR: Avatar ($year) 4K Dublaj", "sub" to "Avatar [Altyazılı]")) {
                    db.execSQL("""INSERT INTO items (id,source,title,category,kind,tvgId,encryptedUrl,search,generation,logo,parent)
                        VALUES (?, 'local', ?, 'Film', 'MOVIE', '', 'opaque', 'film', 1, '', '')""", arrayOf(id, title))
                }
                db.execSQL("INSERT INTO marks VALUES ('item',1,0,250,1000,1)")
                db.execSQL("INSERT INTO marks VALUES ('sub',1,1,100,1000,1)")
                if (from >= 8) {
                    // Simulate both stale keys and the old migration's empty defaults.
                    db.execSQL("UPDATE items SET versionKey=id,isRepresentative=1,versionRank=0 WHERE id='dub'")
                }
                if (from >= 11) {
                    db.execSQL("""UPDATE items SET versionKey='local|MOVIE|fixture',displayTitle='Avatar',
                        displayYear='$year',isRepresentative=CASE WHEN id='dub' THEN 1 ELSE 0 END,
                        versionRank=CASE WHEN id='dub' THEN 0 ELSE 1 END WHERE kind='MOVIE'""")
                }
                if (from == 12) db.execSQL("INSERT INTO version_backfill_state VALUES (1,'done','',0,0)")
                if (from >= 13) db.execSQL("INSERT INTO version_backfill_state VALUES (1,'normalize','',0,0)")
                if (from >= 7) {
                    db.execSQL("INSERT INTO feeds VALUES ('guide','local','opaque')")
                    db.execSQL("INSERT INTO programmes VALUES ('guide','1',100,200,'Program','Description','program')")
                    db.execSQL("INSERT INTO epgNames VALUES ('guide','1','baslik')")
                    db.execSQL("INSERT INTO mappings VALUES ('item','guide','1')")
                    db.execSQL("INSERT INTO collections VALUES ('item','Fixture')")
                }
                db.version = from
            }
            val db = Room.databaseBuilder(context, LibraryDb::class.java, name)
                .addMigrations(LibraryDb.MIGRATION_7_8, LibraryDb.MIGRATION_8_9, LibraryDb.MIGRATION_9_10,
                    LibraryDb.MIGRATION_10_11, LibraryDb.MIGRATION_11_12, LibraryDb.MIGRATION_12_13,
                    LibraryDb.MIGRATION_13_14).build()
            try {
                runBlocking {
                    assertEquals(250L, db.library().mark("item")?.position)
                    val old = db.library().item("dub")!!
                    assertEquals("", old.providerId)
                    assertEquals("", old.tvgName)
                    assertEquals("", old.tvgGroup)
                    assertTrue(db.library().redirects().isEmpty())
                    // Opening Room only changes schema. Every legacy stream is immediately
                    // visible, even when its stale representative flag previously hid it.
                    assertEquals("", old.cleanTitle)
                    assertEquals("", old.sortKey)
                    assertTrue(db.derived().categories("local").isEmpty())
                    assertTrue(db.derived().stats("local").isEmpty())
                    assertEquals(if (from == 12) 1 else 2, db.library().browse("local", "MOVIE", "", "", "", 120).first().size)
                    assertEquals(if (from == 12) "done" else "normalize", db.versionBackfill().state()?.phase)
                    val backfill = VersionBackfill(db)
                    while (backfill.runBatch(1)) { /* Deliberately split the two versions. */ }
                    val derived = CatalogDerivedBackfill(db)
                    while (derived.runBatch("local", 1)) { }
                    val grouped = db.library().item("dub")!!
                    assertTrue(grouped.versionKey.isNotEmpty())
                    assertEquals("Avatar", grouped.displayTitle)
                    assertEquals(year, grouped.displayYear)
                    assertEquals(0, grouped.versionRank)
                    assertTrue(grouped.isRepresentative)
                    assertEquals(1, db.library().browse("local", "MOVIE", "", "", "", 120).first().size)
                    assertEquals(1, db.library().browse("local", "MOVIE", "Film", "", "", 120).first().size)
                    assertEquals(2, db.library().versionRows(grouped.versionKey).size)
                    assertEquals(100L, db.library().mark("sub")?.position)
                    assertEquals("sub", db.library().browse("local", "MOVIE", "Film", "", "favorite", 120).first().single().id)
                    assertEquals(1, db.library().browse("local", "LIVE", "", "", "favorite", 120).first().size)
                    assertEquals(1, db.library().categoryCounts("MOVIE", "local").first().single().count)
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
                db.openHelper.readableDatabase.query("PRAGMA index_info(index_epgNames_feed_name_channel)").use { cursor ->
                    val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(2)) }
                    assertEquals(listOf("feed", "name", "channel"), columns)
                }
                assertEquals(14, db.openHelper.readableDatabase.version)
                db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                    assertTrue("Migration from $from left a foreign key violation", !cursor.moveToFirst())
                }
                db.openHelper.readableDatabase.query("PRAGMA integrity_check").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ok", cursor.getString(0))
                }
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM restore_pending").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(0, cursor.getInt(0))
                }
                if (from >= 7) db.openHelper.readableDatabase.query("SELECT title,start FROM programmes").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("Program", cursor.getString(0))
                    assertEquals(100L, cursor.getLong(1))
                }
                if (from >= 7) {
                    db.openHelper.readableDatabase.query("SELECT feed,channel,name FROM epgNames").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(listOf("guide", "1", "baslik"), (0..2).map { cursor.getString(it) })
                        assertTrue(!cursor.moveToNext())
                    }
                    runBlocking {
                        assertEquals(dev.lumenara.data.Mapping("item", "guide", "1"), db.guide().mapping("item").first())
                    }
                    db.openHelper.readableDatabase.query("SELECT id,name FROM collections").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("item", cursor.getString(0))
                        assertEquals("Fixture", cursor.getString(1))
                    }
                }
            } finally {
                db.close()
            }
            // Validate disk persistence independently of the Room instance that migrated/backfilled.
            val reopened = Room.databaseBuilder(context, LibraryDb::class.java, name).build()
            try {
                runBlocking {
                    assertEquals(dev.lumenara.data.Mark("item", true, false, 250, 1000, 1), reopened.library().mark("item"))
                    assertEquals(dev.lumenara.data.Mark("sub", true, true, 100, 1000, 1), reopened.library().mark("sub"))
                    assertEquals("Test", reopened.library().source("local")?.name)
                    assertTrue(reopened.library().item("dub") != null)
                    assertTrue(reopened.library().item("sub") != null)
                }
                reopened.openHelper.readableDatabase.query("SELECT COUNT(*) FROM items").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(3, cursor.getInt(0))
                }
                reopened.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { cursor ->
                    assertTrue("Reopened schema from $from left a foreign key violation", !cursor.moveToFirst())
                }
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}

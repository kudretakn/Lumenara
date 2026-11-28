package dev.lumenara.tv

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.LibraryDb
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class Wp13GuideMigrationTest {
    @Test fun largeVersionThirteenFirstOpenMeasuresIndexBuildAndPreservesRows() {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val name = "wp13-large-upgrade-${System.nanoTime()}.db"
        val now = System.currentTimeMillis()
        try {
            val schema = test.context.assets.open("dev.lumenara.data.LibraryDb/13.json").bufferedReader().use {
                JSONObject(it.readText()).getJSONObject("database")
            }
            context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { old ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    fun run(sql: String) = old.execSQL(sql.replace("${'$'}{TABLE_NAME}", entity.getString("tableName")))
                    run(entity.getString("createSql"))
                    val indices = entity.optJSONArray("indices") ?: continue
                    for (j in 0 until indices.length()) run(indices.getJSONObject(j).getString("createSql"))
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                old.beginTransaction()
                try {
                    old.execSQL("INSERT INTO sources VALUES ('s','Fixture','opaque',0,0,39682)")
                    old.execSQL("INSERT INTO feeds VALUES ('f','s','opaque')")
                    old.compileStatement("""INSERT INTO items
                        (id,source,title,category,kind,tvgId,encryptedUrl,search,generation,logo,parent)
                        VALUES (?,'s',?,'News','LIVE',?,'opaque','channel',1,'','')""").use { insert ->
                        repeat(39_682) { index ->
                            insert.bindString(1, "i$index")
                            insert.bindString(2, "Channel $index")
                            insert.bindString(3, "c$index")
                            insert.executeInsert()
                        }
                    }
                    old.compileStatement("INSERT INTO epgNames VALUES ('f',?,?)").use { insert ->
                        repeat(10_645) { channel ->
                            insert.bindString(1, "c$channel")
                            for (prefix in listOf("channel", "alias", "other")) {
                                insert.bindString(2, "$prefix $channel")
                                insert.executeInsert()
                            }
                        }
                    }
                    old.compileStatement("INSERT INTO programmes VALUES ('f',?,?,?,'Programme','','programme')").use { insert ->
                        repeat(10_645) { channel ->
                            insert.bindString(1, "c$channel")
                            repeat(4) { offset ->
                                insert.bindLong(2, now + offset * 60_000)
                                insert.bindLong(3, now + (offset + 1) * 60_000)
                                insert.executeInsert()
                            }
                        }
                    }
                    old.execSQL("INSERT INTO marks VALUES ('i0',1,1,250,1000,1)")
                    old.execSQL("INSERT INTO mappings VALUES ('i0','f','c0')")
                    old.execSQL("INSERT INTO collections VALUES ('i0','Fixture')")
                    old.setTransactionSuccessful()
                } finally { old.endTransaction() }
                old.version = 13
            }
            val db = Room.databaseBuilder(context, LibraryDb::class.java, name)
                .addMigrations(LibraryDb.MIGRATION_13_14, LibraryDb.MIGRATION_14_15, LibraryDb.MIGRATION_15_16).build()
            try {
                val start = SystemClock.elapsedRealtimeNanos()
                val upgraded = db.openHelper.writableDatabase
                val openMs = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                Log.i("LumenaraBenchmark", "wp=13 api=${Build.VERSION.SDK_INT} from=13 to=16 items=39682 " +
                    "names=31935 programmes=42580 firstUpgradeOpenMs=$openMs")
                assertEquals(16, upgraded.version)
                for ((table, count) in listOf("items" to 39_682, "epgNames" to 31_935, "programmes" to 42_580,
                    "sources" to 1, "feeds" to 1, "marks" to 1, "mappings" to 1, "collections" to 1)) {
                    upgraded.query("SELECT COUNT(*) FROM $table").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(table, count, cursor.getInt(0))
                    }
                }
                upgraded.query("SELECT favorite,listed,position,duration FROM marks WHERE id='i0'").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(listOf(1L, 1L, 250L, 1000L), (0..3).map { cursor.getLong(it) })
                }
                upgraded.query("PRAGMA index_info(index_epgNames_feed_name_channel)").use { cursor ->
                    assertEquals(listOf("feed", "name", "channel"), buildList {
                        while (cursor.moveToNext()) add(cursor.getString(2))
                    })
                }
                upgraded.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
                upgraded.query("PRAGMA integrity_check").use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("ok", cursor.getString(0))
                }
            } finally { db.close() }
        } finally { context.deleteDatabase(name) }
    }
}

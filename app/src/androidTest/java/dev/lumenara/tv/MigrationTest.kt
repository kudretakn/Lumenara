package dev.lumenara.tv

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.lumenara.data.LibraryDb
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class MigrationTest {
    @Test
    fun oldCatalogSurvivesSchemaUpgrade() {
        val test = InstrumentationRegistry.getInstrumentation()
        val context = test.targetContext
        val name = "lumenara-migration-test"
        context.deleteDatabase(name)
        try {
            val schema =
                test.context.assets
                    .open("dev.lumenara.data.LibraryDb/1.json")
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
                    "INSERT INTO items VALUES ('item','local','Başlık','Spor','LIVE','1','opaque','baslik',1,'','')"
                )
                db.execSQL("INSERT INTO marks VALUES ('item',1,0,250,1000,1)")
                db.version = 1
            }
            val db = Room.databaseBuilder(context, LibraryDb::class.java, name).build()
            try {
                runBlocking { assertEquals(250L, db.library().mark("item")?.position) }
            } finally {
                db.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}

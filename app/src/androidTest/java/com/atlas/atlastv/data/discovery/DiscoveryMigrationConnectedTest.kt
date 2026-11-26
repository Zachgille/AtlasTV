package com.atlas.atlastv.data.discovery

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.db.AtlasDatabase
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DiscoveryMigrationConnectedTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context=instrumentation.targetContext
    @Test fun v9MigrationKeepsEveryExistingTableAndDefersOptionalMetadataUntilBackgroundFill() {
        val name="discovery-migration-fixture.db"
        context.deleteDatabase(name)
        val schema=JsonParser.parseString(instrumentation.context.assets.open("com.atlas.atlastv.data.db.AtlasDatabase/9.json").bufferedReader().use { it.readText() }).asJsonObject.getAsJsonObject("database")
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use { db ->
            schema.getAsJsonArray("entities").forEach { element ->
                val entity=element.asJsonObject;val table=entity.get("tableName").asString
                db.execSQL(entity.get("createSql").asString.replace("\${TABLE_NAME}",table))
                entity.getAsJsonArray("indices")?.forEach { index ->db.execSQL(index.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}",table)) }
            }
            schema.getAsJsonArray("setupQueries").forEach { db.execSQL(it.asString) }
            db.execSQL("INSERT INTO safe_catalog_items VALUES ('movie',1,'Retained (2026) HEVC','retained 2026 hevc','category','Movies',1,1791124020,'mp4','Missing',NULL,NULL,NULL,100,100)")
            db.execSQL("INSERT INTO safe_catalog_items VALUES ('series',1,'Retained show','retained show','category','Shows',1,1791124020000,NULL,'Missing',NULL,NULL,NULL,100,100)")
            db.execSQL("INSERT INTO movie_watchlist_items VALUES(1,'Retained (2026) HEVC','Movies','mp4',10)")
            db.execSQL("INSERT INTO series_watchlist_items VALUES(1,'Retained show','Shows',20)")
            db.execSQL("INSERT INTO watch_progress_records VALUES('movie',1,NULL,'Retained',NULL,NULL,'Movies','mp4',100000,50000,200,0)")
            db.execSQL("INSERT INTO provider_artwork VALUES('opaque-token','private-image-binding')")
            db.execSQL("INSERT INTO movie_details_cache VALUES('private-key',1,100,100,100,100,2,'{}')")
            db.execSQL("INSERT INTO show_details_cache VALUES('private-key',1,100,100,100,100,2,'{}')")
            db.execSQL("INSERT INTO home_hero_cache VALUES('private-key','movie-1',100,100,100,100,2,'{}')")
            db.version=9
        }
        val before=SQLiteDatabase.openDatabase(context.getDatabasePath(name).path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            schema.getAsJsonArray("entities").associate { e -> val table=e.asJsonObject.get("tableName").asString
                table to db.rawQuery("SELECT * FROM $table",null).use { c -> buildList { while(c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) }) } }
            }
        }
        val database=Room.databaseBuilder(context,AtlasDatabase::class.java,name).addMigrations(AtlasDatabase.Migration9To10, AtlasDatabase.Migration10To11).build()
        try {
            val db=database.openHelper.writableDatabase
            assertEquals(11,db.version)
            before.forEach { (table, rows) -> val after=db.query("SELECT * FROM $table").use { c -> buildList { while(c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) }) } }; assertEquals(table,rows,after) }
            runBlocking {
                assertNull(database.safeCatalogDao().discoveryMetadata("movie",1))
                assertEquals(2,DiscoveryLegacySeeder(database.safeCatalogDao()) {1791150000000L}.run())
                assertEquals(0,DiscoveryLegacySeeder(database.safeCatalogDao()).run())
                val movie=database.safeCatalogDao().discoveryMetadata("movie",1)!!
                val show=database.safeCatalogDao().discoveryMetadata("series",1)!!
                assertEquals(20260101,movie.releaseSort);assertEquals("title-year",movie.releaseSource)
                assertEquals(1791124020000L,movie.addedAtMs);assertNull(movie.ratingMilli)
                assertNull(show.addedAtMs);assertEquals(1791124020000L,show.changedAtMs);assertNull(show.releaseSort)
            }
        } finally { database.close();context.deleteDatabase(name) }
    }
}

package com.atlas.atlastv.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.media.*
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Only in-memory Room or uniquely named cache databases/files; never the personal database. */
class ProviderArtworkConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun migration5To6PreservesCatalogWatchlistsAndProgress() {
        val file = File(context.cacheDir, "artwork-migration-${UUID.randomUUID()}.db")
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val schema = JsonParser.parseString(assets.open("com.atlas.atlastv.data.db.AtlasDatabase/5.json").bufferedReader().use { it.readText() }).asJsonObject["database"].asJsonObject
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            schema["entities"].asJsonArray.forEach { element ->
                val entity = element.asJsonObject
                val name = entity["tableName"].asString
                old.execSQL(entity["createSql"].asString.replace("${'$'}{TABLE_NAME}", name))
                entity["indices"]?.asJsonArray?.forEach { old.execSQL(it.asJsonObject["createSql"].asString.replace("${'$'}{TABLE_NAME}", name)) }
            }
            schema["setupQueries"].asJsonArray.forEach { old.execSQL(it.asString) }
            old.execSQL("INSERT INTO safe_catalog_items (contentType,itemId,title,normalizedTitle,categoryName,providerOrder,posterState,importedAtMs,updatedAtMs) VALUES ('movie',7,'Kept','kept','Movies',1,'Missing',100,101)")
            old.execSQL("INSERT INTO movie_watchlist_items (streamId,title,addedAtMs) VALUES (7,'Kept',55)")
            old.execSQL("INSERT INTO series_watchlist_items (seriesId,title,addedAtMs) VALUES (8,'Kept Show',56)")
            old.execSQL("INSERT INTO watch_progress_records (contentType,streamId,title,positionMs,updatedAtMs,completed) VALUES ('movie',7,'Kept',255000,102,0)")
            old.version = 5
        }
        val db = Room.databaseBuilder(context, AtlasDatabase::class.java, file.absolutePath).addMigrations(AtlasDatabase.Migration5To6, AtlasDatabase.Migration6To7, AtlasDatabase.Migration7To8, AtlasDatabase.Migration8To9, AtlasDatabase.Migration9To10, AtlasDatabase.Migration10To11).build()
        try {
            val migrated = db.openHelper.writableDatabase
            fun scalar(sql: String): Long = migrated.query(sql).use { it.moveToFirst(); it.getLong(0) }
            assertEquals(11L, scalar("PRAGMA user_version"))
            assertEquals(1L, scalar("SELECT COUNT(*) FROM safe_catalog_items WHERE itemId=7 AND title='Kept' AND updatedAtMs=101"))
            assertEquals(1L, scalar("SELECT COUNT(*) FROM movie_watchlist_items WHERE streamId=7"))
            assertEquals(1L, scalar("SELECT COUNT(*) FROM series_watchlist_items WHERE seriesId=8"))
            assertEquals(255000L, scalar("SELECT positionMs FROM watch_progress_records WHERE streamId=7"))
            assertEquals(0L, scalar("SELECT COUNT(*) FROM provider_artwork"))
        } finally { db.close(); file.delete(); File(file.path + "-wal").delete(); File(file.path + "-shm").delete() }
    }

    @Test fun retainsFullCoverageAfterLegacyImportAndNewIndexCreation() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        val file = File(context.cacheDir, "artwork-legacy-${UUID.randomUUID()}.json")
        try {
            val legacy = (1..5_000).map { "https://example.test/posters/$it.jpg" }.map { atlasImageRefFromRaw(it).providerImageToken to it }
            AtlasProviderImageIndex(file).registerAllAsync(legacy)
            AtlasProviderImageRegistry.clearForTests()
            val store = ProviderArtworkRoomStore(db.providerArtworkDao())
            val index = AtlasProviderImageIndex(file, store)
            assertEquals(legacy.first().second, index.resolveAsync(legacy.first().first))
            assertEquals(5_000, db.providerArtworkDao().count())
            // Successful migration is durable; lookup must no longer depend on the compatibility file.
            file.writeText("{invalid")
            val additions = (5_001..20_001).map { "https://example.test/posters/$it.jpg" }.map { atlasImageRefFromRaw(it).providerImageToken to it }
            index.registerAllAsync(additions)
            AtlasProviderImageRegistry.clearForTests()
            val restored = AtlasProviderImageIndex(file, store)
            assertEquals(20_001, db.providerArtworkDao().count())
            listOf(legacy.first(), legacy.last(), additions.first(), additions.last()).forEach { assertEquals(it.second, restored.resolveAsync(it.first)) }
            index.clearAsync()
            assertNull(restored.resolveAsync(legacy.first().first))
        } finally { db.close(); file.delete() }
    }

    @Test fun corruptLegacyCannotPreventFreshCatalogArtworkAndDoesNotEraseExistingRows() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        val file = File(context.cacheDir, "artwork-corrupt-${UUID.randomUUID()}.json")
        try {
            file.writeText("{invalid")
            val address = "https://example.test/new.jpg"
            val token = atlasImageRefFromRaw(address).providerImageToken
            val index = AtlasProviderImageIndex(file, ProviderArtworkRoomStore(db.providerArtworkDao()))
            index.registerAllAsync(listOf(token to address))
            assertEquals(address, index.resolveAsync(token))
            assertTrue(runCatching { index.resolveAsync(atlasImageRefFromRaw("https://example.test/missing.jpg").providerImageToken) }.isFailure)
            assertEquals(1, db.providerArtworkDao().count())
            assertEquals("{invalid", file.readText())
        } finally { db.close(); file.delete() }
    }
}

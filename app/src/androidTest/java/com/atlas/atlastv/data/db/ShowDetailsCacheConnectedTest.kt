package com.atlas.atlastv.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Unique cache databases only. No personal-library seeding, clearing or playback. */
class ShowDetailsCacheConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val credentials = XtreamCredentials("http://cache-check.invalid", "opaque", "not-a-real-account")
    private fun result(count: Int = 1536) = SeriesEpisodeLoadResult.Success((1..count).map {
        SeriesEpisodeItem("Episode $it", (it - 1) / 64 + 1, (it - 1) % 64 + 1, it, "mp4", "", 600_000)
    }, SeriesShowMetadata("Saved synopsis", "Drama", "2020"))
    private fun saved(id: Int = 7, request: Long = 10, count: Int = 1536) =
        SavedShowDetails(providerDetailsKey(credentials), id, request, request, 1, result(count))
    private fun clean(file: File) { for (suffix in listOf("", "-wal", "-shm")) File(file.path + suffix).delete() }
    @Test fun migration6To7ValidatesSchemaAndKeepsEveryExistingTableRow() {
        val file = File(context.cacheDir, "show-cache-migration-${UUID.randomUUID()}.db")
        val schema = JsonParser.parseString(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.atlas.atlastv.data.db.AtlasDatabase/6.json").bufferedReader().use { it.readText() }).asJsonObject["database"].asJsonObject
        val tables = listOf("safe_catalog_items", "movie_watchlist_items", "series_watchlist_items", "watch_progress_records", "provider_artwork", "artwork_migrations")
        val before = mutableMapOf<String, List<List<String?>>>()
        fun rows(cursor: android.database.Cursor) = cursor.use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) } }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            schema["entities"].asJsonArray.forEach { element ->
                val entity = element.asJsonObject; val name = entity["tableName"].asString
                old.execSQL(entity["createSql"].asString.replace("${'$'}{TABLE_NAME}", name))
                entity["indices"]?.asJsonArray?.forEach { old.execSQL(it.asJsonObject["createSql"].asString.replace("${'$'}{TABLE_NAME}", name)) }
            }
            schema["setupQueries"].asJsonArray.forEach { old.execSQL(it.asString) }
            old.execSQL("INSERT INTO safe_catalog_items (contentType,itemId,title,normalizedTitle,categoryName,providerOrder,posterState,importedAtMs,updatedAtMs) VALUES ('series',7,'Kept','kept','Shows',1,'Missing',100,101)")
            old.execSQL("INSERT INTO movie_watchlist_items (streamId,title,addedAtMs) VALUES (7,'Kept Movie',55)")
            old.execSQL("INSERT INTO series_watchlist_items (seriesId,title,addedAtMs) VALUES (7,'Kept Show',56)")
            old.execSQL("INSERT INTO watch_progress_records (contentType,streamId,seriesId,title,positionMs,updatedAtMs,completed) VALUES ('series_episode',7,7,'Kept episode',255000,102,0)")
            old.execSQL("INSERT INTO provider_artwork (token,address) VALUES ('fixture-token','https://art.invalid/image.jpg')")
            old.execSQL("INSERT INTO artwork_migrations (source) VALUES ('kept')")
            tables.forEach { before[it] = rows(old.rawQuery("SELECT * FROM $it", null)) }; old.version = 6
        }
        val db = Room.databaseBuilder(context, AtlasDatabase::class.java, file.absolutePath).addMigrations(AtlasDatabase.Migration6To7, AtlasDatabase.Migration7To8, AtlasDatabase.Migration8To9, AtlasDatabase.Migration9To10, AtlasDatabase.Migration10To11).build()
        try {
            val sql = db.openHelper.writableDatabase
            assertEquals(11, sql.version)
            tables.forEach { assertEquals(it, before[it], rows(sql.query("SELECT * FROM $it"))) }
            assertTrue(rows(sql.query("SELECT * FROM show_details_cache")).isEmpty())
        } finally { db.close(); clean(file) }
    }
    @Test fun completeDiskSnapshotReopensOfflineAndOtherAccountsCannotReadIt() = runBlocking(Dispatchers.IO) {
        val file = File(context.cacheDir, "show-cache-reopen-${UUID.randomUUID()}.db")
        var db = Room.databaseBuilder(context, AtlasDatabase::class.java, file.absolutePath).build()
        try {
            RoomShowDetailsStore(db.showDetailsCacheDao()).save(saved())
            db.close(); db = Room.databaseBuilder(context, AtlasDatabase::class.java, file.absolutePath).build()
            var calls = 0
            val repository = ShowDetailsRepository(RoomShowDetailsStore(db.showDetailsCacheDao()),
                { _, _ -> calls++; SeriesEpisodeLoadResult.Failure("Unavailable") }, { 1 }, { 11 })
            val loaded = repository.forPlayback(credentials, 7) as SeriesEpisodeLoadResult.Success
            assertEquals(1536, loaded.episodes.size); assertEquals(1536, loaded.episodes.last().episodeId)
            assertEquals(24, loaded.episodes.last().seasonNumber); assertTrue(loaded.fromSaved); assertEquals(0, calls)
            assertNull(repository.saved(credentials.copy(password = "different"), 7))
            assertNull(repository.saved(credentials, 8))
            assertEquals(0, db.watchProgressDao().records().size)
        } finally { db.close(); clean(file) }
    }
    @Test fun corruptPayloadIsRemovedOnlyForItsOwnVersionAndOtherShowsRemain() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.showDetailsCacheDao(); val store = RoomShowDetailsStore(dao)
            store.save(saved()); store.save(saved(id = 8))
            val row = dao.entry(providerDetailsKey(credentials), 7)!!
            dao.put(row.copy(payload = "{broken", payloadBytes = 7))
            assertNull(store.read(row.providerKey, 7)); assertNull(dao.entry(row.providerKey, 7))
            assertEquals(1536, store.read(row.providerKey, 8)!!.result.episodes.size)
            store.save(saved(request = 20)); dao.deleteVersion(row.providerKey, 7, 10)
            assertEquals(20L, dao.entry(row.providerKey, 7)!!.requestedAtMs)
            dao.put(dao.entry(row.providerKey, 7)!!.copy(payloadBytes = 1))
            assertNull(store.read(row.providerKey, 7)); assertNotNull(store.read(row.providerKey, 8))
        } finally { db.close() }
    }
    @Test fun productionCountBoundUsesLastAccessAndRejectsOlderRequestWrites() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.showDetailsCacheDao(); var time = 0L; val store = RoomShowDetailsStore(dao) { ++time }
            (1..32).forEach { store.save(saved(id = it, count = 3)) }
            store.read(providerDetailsKey(credentials), 1); store.save(saved(id = 33, count = 3))
            assertEquals(32, dao.sizes().size); assertNull(store.read(providerDetailsKey(credentials), 2))
            assertNotNull(store.read(providerDetailsKey(credentials), 1)); assertNotNull(store.read(providerDetailsKey(credentials), 33))
            store.save(saved(id = 33, request = 20, count = 4)); store.save(saved(id = 33, request = 19, count = 1))
            assertEquals(4, store.read(providerDetailsKey(credentials), 33)!!.result.episodes.size)
        } finally { db.close() }
    }
    @Test fun transactionByteBudgetEvictsCompleteEntriesWithoutTruncating() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.showDetailsCacheDao()
            fun row(id: Int) = ShowDetailsCacheEntity("account", id, id.toLong(), 1, null, id.toLong(), 60, "x".repeat(60))
            dao.saveBounded(row(1), 32, 100); dao.saveBounded(row(2), 32, 100)
            assertNull(dao.entry("account", 1)); assertEquals("x".repeat(60), dao.entry("account", 2)!!.payload)
            assertEquals(60L, dao.sizes().sumOf { it.payloadBytes.toLong() })
        } finally { db.close() }
    }
}

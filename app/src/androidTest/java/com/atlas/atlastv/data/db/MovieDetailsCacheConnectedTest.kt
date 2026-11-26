package com.atlas.atlastv.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Unique cache databases or in-memory Room only; personal state is never seeded/cleared. */
class MovieDetailsCacheConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val account = XtreamCredentials("http://movie-cache.invalid", "opaque", "not-a-real-account")
    private val metadata = MovieMetadata("A saved synopsis.", "Drama", "2020", 148)
    private fun saved(id: Int = 1, request: Long = 10) = SavedMovieDetails(providerMovieDetailsKey(account), id, request, request, 1, metadata)
    private fun clean(file: File) { for (suffix in listOf("", "-wal", "-shm")) File(file.path + suffix).delete() }
    @Test fun migration7To8ValidatesSchemaAndKeepsAllPreviousTablesIncludingShowCache() {
        val file = File(context.cacheDir, "movie-cache-migration-${UUID.randomUUID()}.db")
        val schema = JsonParser.parseString(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.atlas.atlastv.data.db.AtlasDatabase/7.json").bufferedReader().use { it.readText() }).asJsonObject["database"].asJsonObject
        val tables = listOf("safe_catalog_items", "movie_watchlist_items", "series_watchlist_items", "watch_progress_records", "provider_artwork", "artwork_migrations", "show_details_cache")
        val before = mutableMapOf<String, List<List<String?>>>()
        fun rows(cursor: android.database.Cursor) = cursor.use { c -> buildList { while (c.moveToNext()) add((0 until c.columnCount).map { if (c.isNull(it)) null else c.getString(it) }) } }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            schema["entities"].asJsonArray.forEach { element ->
                val entity = element.asJsonObject; val name = entity["tableName"].asString
                old.execSQL(entity["createSql"].asString.replace("${'$'}{TABLE_NAME}", name))
                entity["indices"]?.asJsonArray?.forEach { old.execSQL(it.asJsonObject["createSql"].asString.replace("${'$'}{TABLE_NAME}", name)) }
            }
            schema["setupQueries"].asJsonArray.forEach { old.execSQL(it.asString) }
            old.execSQL("INSERT INTO safe_catalog_items (contentType,itemId,title,normalizedTitle,categoryName,providerOrder,posterState,importedAtMs,updatedAtMs) VALUES ('movie',7,'Kept','kept','Movies',1,'Missing',100,101)")
            old.execSQL("INSERT INTO movie_watchlist_items (streamId,title,addedAtMs) VALUES (7,'Kept Movie',55)")
            old.execSQL("INSERT INTO series_watchlist_items (seriesId,title,addedAtMs) VALUES (7,'Kept Show',56)")
            old.execSQL("INSERT INTO watch_progress_records (contentType,streamId,title,positionMs,updatedAtMs,completed) VALUES ('movie',7,'Kept Movie',255000,102,0)")
            old.execSQL("INSERT INTO provider_artwork (token,address) VALUES ('fixture-token','https://art.invalid/image.jpg')")
            old.execSQL("INSERT INTO artwork_migrations (source) VALUES ('kept')")
            old.execSQL("INSERT INTO show_details_cache (providerKey,seriesId,requestedAtMs,savedAtMs,catalogAtMs,accessedAtMs,payloadBytes,payload) VALUES ('kept',7,100,101,99,102,4,'kept')")
            tables.forEach { before[it] = rows(old.rawQuery("SELECT * FROM $it", null)) }; old.version = 7
        }
        val db = Room.databaseBuilder(context, AtlasDatabase::class.java, file.absolutePath).addMigrations(AtlasDatabase.Migration7To8, AtlasDatabase.Migration8To9, AtlasDatabase.Migration9To10, AtlasDatabase.Migration10To11).build()
        try {
            val sql = db.openHelper.writableDatabase; assertEquals(11, sql.version)
            tables.forEach { assertEquals(it, before[it], rows(sql.query("SELECT * FROM $it"))) }
            assertTrue(rows(sql.query("SELECT * FROM movie_details_cache")).isEmpty())
        } finally { db.close(); clean(file) }
    }
    @Test fun metadataReopensFromDiskWithoutProviderAndMovieShowAndAccountsRemainSeparate() = runBlocking(Dispatchers.IO) {
        val file = File(context.cacheDir, "movie-cache-reopen-${UUID.randomUUID()}.db")
        var db = Room.databaseBuilder(context, AtlasDatabase::class.java, file.absolutePath).build()
        try {
            RoomMovieDetailsStore(db.movieDetailsCacheDao()).save(saved())
            val show = SeriesEpisodeLoadResult.Success(listOf(SeriesEpisodeItem("Episode", 1, 1, 1, "mp4", "")))
            RoomShowDetailsStore(db.showDetailsCacheDao()).save(SavedShowDetails(providerDetailsKey(account), 1, 10, 10, 1, show))
            db.close(); db = Room.databaseBuilder(context, AtlasDatabase::class.java, file.absolutePath).build()
            var calls = 0
            val repo = MovieDetailsRepository(RoomMovieDetailsStore(db.movieDetailsCacheDao()), { _, _ -> calls++; null }, { 1 }, { 11 })
            assertEquals(metadata, repo.saved(account, 1)!!.metadata); assertEquals(0, calls)
            assertFalse(repo.stale(repo.saved(account, 1)!!))
            assertNull(repo.saved(account.copy(password = "another"), 1)); assertNull(repo.saved(account, 2))
            assertEquals(1, RoomShowDetailsStore(db.showDetailsCacheDao()).read(providerDetailsKey(account), 1)!!.result.episodes.single().episodeId)
            assertTrue(db.watchProgressDao().records().isEmpty())
        } finally { db.close(); clean(file) }
    }
    @Test fun corruptRepairIsSelectiveAndCannotDeleteANewerVersion() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.movieDetailsCacheDao(); val store = RoomMovieDetailsStore(dao)
            store.save(saved()); store.save(saved(id = 2))
            val row = dao.entry(providerMovieDetailsKey(account), 1)!!
            dao.put(row.copy(payload = "{bad", payloadBytes = 4))
            assertNull(store.read(row.providerKey, 1)); assertNull(dao.entry(row.providerKey, 1)); assertNotNull(store.read(row.providerKey, 2))
            store.save(saved(request = 20)); dao.deleteVersion(row.providerKey, 1, 10)
            assertEquals(20L, dao.entry(row.providerKey, 1)!!.requestedAtMs)
            dao.put(dao.entry(row.providerKey, 1)!!.copy(payloadBytes = 1))
            assertNull(store.read(row.providerKey, 1)); assertEquals(metadata, store.read(row.providerKey, 2)!!.metadata)
        } finally { db.close() }
    }
    @Test fun production64MovieBoundUsesLastAccessAndRejectsOlderRequestWrites() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.movieDetailsCacheDao(); var time = 0L; val store = RoomMovieDetailsStore(dao) { ++time }
            (1..64).forEach { store.save(saved(id = it)) }
            store.read(providerMovieDetailsKey(account), 1); store.save(saved(id = 65))
            assertEquals(64, dao.sizes().size); assertNull(store.read(providerMovieDetailsKey(account), 2))
            assertNotNull(store.read(providerMovieDetailsKey(account), 1)); assertNotNull(store.read(providerMovieDetailsKey(account), 65))
            store.save(saved(id = 65, request = 20).copy(metadata = metadata.copy(synopsis = "New")))
            store.save(saved(id = 65, request = 19).copy(metadata = metadata.copy(synopsis = "Old")))
            assertEquals("New", store.read(providerMovieDetailsKey(account), 65)!!.metadata.synopsis)
        } finally { db.close() }
    }
    @Test fun byteBudgetEvictsWholeEntriesAndOptionalAccessFailureCannotDiscardGoodMetadata() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.movieDetailsCacheDao()
            fun row(id: Int) = MovieDetailsCacheEntity("account", id, id.toLong(), 1, null, id.toLong(), 60, "x".repeat(60))
            dao.saveBounded(row(1), 64, 100); dao.saveBounded(row(2), 64, 100)
            assertNull(dao.entry("account", 1)); assertEquals("x".repeat(60), dao.entry("account", 2)!!.payload)
            RoomMovieDetailsStore(dao).save(saved())
            val broken = object : MovieDetailsCacheDao by dao { override suspend fun touch(key: String, id: Int, request: Long, time: Long) { error("access bookkeeping") } }
            assertEquals(metadata, RoomMovieDetailsStore(broken).read(providerMovieDetailsKey(account), 1)!!.metadata)
        } finally { db.close() }
    }
}

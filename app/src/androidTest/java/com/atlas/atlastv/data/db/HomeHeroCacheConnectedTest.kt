package com.atlas.atlastv.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.home.*
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

/** Every SQL check uses memory or a unique cache file; personal state is untouched. */
class HomeHeroCacheConnectedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val account = XtreamCredentials("http://home-cache.invalid", "opaque", "not-real")
    private val metadata = HomeHeroMetadata("Saved story.", "Drama", "2020", 148)
    private fun saved(id: String = "movie-1", request: Long = 10) = SavedHomeHero(providerHomeHeroKey(account), id, request, request, 1, metadata)
    private fun clean(file: File) { for (suffix in listOf("", "-wal", "-shm")) File(file.path + suffix).delete() }
    @Test fun migration8To9ValidatesSchemaAndPreservesAllPreviousRowsAndBothDetailCaches() {
        val file = File(context.cacheDir, "home-migration-${UUID.randomUUID()}.db")
        val schema = JsonParser.parseString(InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.atlas.atlastv.data.db.AtlasDatabase/8.json").bufferedReader().use { it.readText() }).asJsonObject["database"].asJsonObject
        val tables = listOf("safe_catalog_items", "movie_watchlist_items", "series_watchlist_items", "watch_progress_records", "provider_artwork", "artwork_migrations", "show_details_cache", "movie_details_cache")
        val before = mutableMapOf<String, List<List<String?>>>()
        fun rows(cursor: android.database.Cursor) = cursor.use { c -> buildList { while(c.moveToNext()) add((0 until c.columnCount).map { if(c.isNull(it)) null else c.getString(it) }) } }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            schema["entities"].asJsonArray.forEach { element ->
                val e = element.asJsonObject; val name = e["tableName"].asString
                old.execSQL(e["createSql"].asString.replace("${'$'}{TABLE_NAME}", name))
                e["indices"]?.asJsonArray?.forEach { old.execSQL(it.asJsonObject["createSql"].asString.replace("${'$'}{TABLE_NAME}", name)) }
            }
            schema["setupQueries"].asJsonArray.forEach { old.execSQL(it.asString) }
            old.execSQL("INSERT INTO safe_catalog_items (contentType,itemId,title,normalizedTitle,categoryName,providerOrder,posterState,importedAtMs,updatedAtMs) VALUES ('movie',7,'Kept','kept','Movies',1,'Missing',100,101)")
            old.execSQL("INSERT INTO movie_watchlist_items (streamId,title,addedAtMs) VALUES (7,'Kept Movie',55)")
            old.execSQL("INSERT INTO series_watchlist_items (seriesId,title,addedAtMs) VALUES (7,'Kept Show',56)")
            old.execSQL("INSERT INTO watch_progress_records (contentType,streamId,title,positionMs,updatedAtMs,completed) VALUES ('movie',7,'Kept',255000,102,0)")
            old.execSQL("INSERT INTO provider_artwork (token,address) VALUES ('fixture-token','https://art.invalid/a.jpg')")
            old.execSQL("INSERT INTO artwork_migrations (source) VALUES ('kept')")
            for ((table, column) in listOf("show_details_cache" to "seriesId", "movie_details_cache" to "streamId"))
                old.execSQL("INSERT INTO $table (providerKey,$column,requestedAtMs,savedAtMs,catalogAtMs,accessedAtMs,payloadBytes,payload) VALUES ('kept',7,100,101,99,102,4,'kept')")
            tables.forEach { before[it] = rows(old.rawQuery("SELECT * FROM $it", null)) }; old.version = 8
        }
        val db = Room.databaseBuilder(context, AtlasDatabase::class.java, file.absolutePath).addMigrations(AtlasDatabase.Migration8To9, AtlasDatabase.Migration9To10, AtlasDatabase.Migration10To11).build()
        try {
            val sql = db.openHelper.writableDatabase; assertEquals(11, sql.version)
            tables.forEach { assertEquals(it, before[it], rows(sql.query("SELECT * FROM $it"))) }
            assertTrue(rows(sql.query("SELECT * FROM home_hero_cache")).isEmpty())
        } finally { db.close(); clean(file) }
    }
    @Test fun diskReopenSeparatesAccountsMoviesShowsAndEpisodeParentIdentity() = runBlocking(Dispatchers.IO) {
        val file = File(context.cacheDir, "home-reopen-${UUID.randomUUID()}.db")
        var db = Room.databaseBuilder(context, AtlasDatabase::class.java, file.absolutePath).build()
        try {
            RoomHomeHeroStore(db.homeHeroCacheDao()).save(saved()); RoomHomeHeroStore(db.homeHeroCacheDao()).save(saved("show-1").copy(metadata = metadata.copy(synopsis = "Show")))
            db.close(); db = Room.databaseBuilder(context, AtlasDatabase::class.java, file.absolutePath).build()
            val repo = HomeHeroRepository(RoomHomeHeroStore(db.homeHeroCacheDao()), { _, _ -> error("network not needed") }, { 1 }, clockMs = { 11 })
            val movie = HomeCard("movie", "Movie", HomeContentType.Movie, "Movies", 1, statusText = "")
            val episode = movie.copy(contentType = HomeContentType.SeriesEpisode, streamId = 201, seriesId = 1)
            assertEquals(metadata, repo.saved(account, movie)!!.metadata)
            assertEquals("Show", repo.saved(account, episode)!!.metadata.synopsis)
            assertNull(repo.saved(account.copy(password = "another"), movie)); assertNull(repo.saved(account, movie.copy(streamId = 2)))
            assertFalse(repo.stale(repo.saved(account, episode)!!, episode)); assertTrue(db.watchProgressDao().records().isEmpty())
        } finally { db.close(); clean(file) }
    }
    @Test fun productionFallbackReusesNewerMovieAndCompleteShowSnapshotsWithoutMaterializingAnotherEpisodeCache() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val movie = HomeCard("movie", "Movie", HomeContentType.Movie, "Movies", 1, statusText = "")
            val show = movie.copy(contentType = HomeContentType.Show, seriesId = 1)
            RoomHomeHeroStore(db.homeHeroCacheDao()).save(saved().copy(metadata = metadata.copy(synopsis = "Old Home")))
            RoomMovieDetailsStore(db.movieDetailsCacheDao()).save(SavedMovieDetails(providerMovieDetailsKey(account), 1, 20, 20, 1, MovieMetadata("Movie detail", "Action", "2020")))
            val episodes = (1..1536).map { SeriesEpisodeItem("Episode $it", 1+(it-1)/64, 1+(it-1)%64, it, "mp4", "") }
            RoomShowDetailsStore(db.showDetailsCacheDao()).save(SavedShowDetails(providerDetailsKey(account), 1, 20, 20, 1,
                SeriesEpisodeLoadResult.Success(episodes, SeriesShowMetadata("Show detail", "Drama", "2015"))))
            val repo = HomeHeroRepository.create(context, HomeHeroMetadataLoader(), db)
            assertEquals("Movie detail", repo.saved(account, movie)!!.metadata.synopsis)
            assertEquals("Show detail", repo.saved(account, show.copy(contentType = HomeContentType.SeriesEpisode, streamId = 999))!!.metadata.synopsis)
            assertEquals(1536, RoomShowDetailsStore(db.showDetailsCacheDao()).read(providerDetailsKey(account), 1)!!.result.episodes.size)
            assertEquals(2, db.homeHeroCacheDao().sizes().size)
            assertFalse(db.homeHeroCacheDao().entry(providerHomeHeroKey(account), "show-1")!!.payload.contains("episodes"))
            assertTrue(db.watchProgressDao().records().isEmpty())
        } finally { db.close() }
    }
    @Test fun selectiveCorruptRepairByteCountsAndOlderVersionDeleteProtectOtherSnapshots() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.homeHeroCacheDao(); val store = RoomHomeHeroStore(dao)
            store.save(saved()); store.save(saved("show-1")); val row = dao.entry(providerHomeHeroKey(account), "movie-1")!!
            dao.put(row.copy(payload = "{bad", payloadBytes = 4)); assertNull(store.read(row.providerKey, row.metadataKey))
            assertNotNull(store.read(row.providerKey, "show-1")); store.save(saved(request = 20)); dao.deleteVersion(row.providerKey, row.metadataKey, 10)
            assertEquals(20L, dao.entry(row.providerKey, row.metadataKey)!!.requestedAtMs)
            dao.put(dao.entry(row.providerKey, row.metadataKey)!!.copy(payloadBytes = 1)); assertNull(store.read(row.providerKey, row.metadataKey))
            assertEquals(metadata, store.read(row.providerKey, "show-1")!!.metadata)
        } finally { db.close() }
    }
    @Test fun production64PreviewLruAndByteBudgetEvictWholeEntriesAndRejectOldWrites() = runBlocking(Dispatchers.IO) {
        val db = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = db.homeHeroCacheDao(); var time = 0L; val store = RoomHomeHeroStore(dao) { ++time }
            (1..64).forEach { store.save(saved("movie-$it")) }; store.read(providerHomeHeroKey(account), "movie-1"); store.save(saved("show-1"))
            assertEquals(64, dao.sizes().size); assertNull(store.read(providerHomeHeroKey(account), "movie-2")); assertNotNull(store.read(providerHomeHeroKey(account), "movie-1"))
            store.save(saved("show-1", 20).copy(metadata = metadata.copy(synopsis = "New"))); store.save(saved("show-1", 19).copy(metadata = metadata.copy(synopsis = "Old")))
            assertEquals("New", store.read(providerHomeHeroKey(account), "show-1")!!.metadata.synopsis)
            val broken = object : HomeHeroCacheDao by dao { override suspend fun touch(key: String, id: String, request: Long, time: Long) { error("optional") } }
            assertEquals(metadata, RoomHomeHeroStore(broken).read(providerHomeHeroKey(account), "movie-1")!!.metadata)
        } finally { db.close() }
        val bytesDb = Room.inMemoryDatabaseBuilder(context, AtlasDatabase::class.java).build()
        try {
            val dao = bytesDb.homeHeroCacheDao()
            fun row(id: String, time: Long) = HomeHeroCacheEntity("account", id, time, 1, null, time, 60, "x".repeat(60))
            dao.saveBounded(row("movie-1", 1), 64, 100); dao.saveBounded(row("show-1", 2), 64, 100)
            assertNull(dao.entry("account", "movie-1")); assertEquals("x".repeat(60), dao.entry("account", "show-1")!!.payload)
        } finally { bytesDb.close() }
    }
}

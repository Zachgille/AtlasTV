package com.atlas.atlastv.data.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/** Named disposable databases only; never mutates the personal library. */
class CatalogLanguageCheckpointConnectedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val name = "language-checkpoint-fixture.db"
    private val pages = AtomicInteger()
    private fun open() = Room.databaseBuilder(context, AtlasDatabase::class.java, name)
        .addMigrations(AtlasDatabase.Migration10To11)
        .addCallback(CatalogLanguageVerification.callback)
        .setQueryCallback({ sql, _ -> if (sql.startsWith("SELECT contentType,itemId,title")) pages.incrementAndGet() }, Executor { it.run() })
        .build()
    private fun movie(id: Int = 1, title: String = "Kept movie") = MovieItem(title,"Movies",id,"mp4","").toSafeCatalogEntity(id,100)
        .copy(title=title, normalizedTitle=title.normalizedCatalogText())
    private fun show() = SeriesItem("Kept show","Shows",1).toSafeCatalogEntity(1,100)
    private suspend fun checkpoint(dao: SafeCatalogDao) {
        dao.applyEnglishCategoryPolicy()
        assertEquals(CatalogLanguageCheckpoint(policyRevision=CatalogLanguageVerification.Revision),dao.languageCheckpoint())
    }
    private fun fixture(block: suspend (AtlasDatabase) -> Unit) = runBlocking(Dispatchers.IO) {
        context.deleteDatabase(name)
        val db=open()
        try { block(db) } finally { db.close();context.deleteDatabase(name) }
    }

    @Test fun newLanguageRevisionRechecksStoredTagsAndKeepsOrdinaryEnglishSearchable() = fixture { db ->
        val dao = db.safeCatalogDao()
        val movies = listOf(movie(1, "Mom"), movie(2, "French Kiss"), movie(3, "NF | [FRENCH] Film"),
            movie(4, "Movie [GER]"), movie(5, "(FR) Film"), movie(6, "French-Canadian Affair"))
        val shows = listOf(show().copy(title = "Mom", normalizedTitle = "mom"),
            show().copy(itemId = 2, title = "GER | Show", normalizedTitle = "ger show"),
            show().copy(itemId = 3, title = "FROM", normalizedTitle = "from"))
        dao.insertAll(movies + shows)
        dao.saveLanguageCheckpoint(CatalogLanguageCheckpoint(policyRevision = 1))
        pages.set(0)
        assertEquals(4, dao.applyEnglishCategoryPolicy()); assertTrue(pages.get() > 0)
        assertEquals(movies.filter { it.itemId in setOf(1, 2, 6) }, dao.itemsForType("movie"))
        assertEquals(shows.filter { it.itemId != 2 }, dao.itemsForType("series"))
        assertEquals("Mom", dao.searchPage("series", "mom", 0, 18).single().title)
        assertEquals("French Kiss", dao.searchPage("movie", "french kiss", 0, 18).single().title)
        assertEquals(CatalogLanguageCheckpoint(policyRevision = CatalogLanguageVerification.Revision), dao.languageCheckpoint())
        pages.set(0); assertEquals(0, dao.applyEnglishCategoryPolicy()); assertEquals(0, pages.get())
    }

    @Test fun unchangedCatalogSkipsTitlePagesAcrossDatabaseReopen() = runBlocking(Dispatchers.IO) {
        context.deleteDatabase(name)
        var db=open()
        try {
            db.safeCatalogDao().insertAll(listOf(movie(),show()))
            checkpoint(db.safeCatalogDao()); assertTrue(pages.get()>0)
            pages.set(0); checkpoint(db.safeCatalogDao()); assertEquals(0,pages.get())
            db.close(); db=open(); checkpoint(db.safeCatalogDao()); assertEquals(0,pages.get())
            assertEquals(1,db.safeCatalogDao().countForType("movie"))
        } finally {db.close();context.deleteDatabase(name)}
    }

    @Test fun everyRawMutationAndSameStampReplacementInvalidate() = fixture { db ->
        val dao=db.safeCatalogDao();dao.insertAll(listOf(movie(),show()));checkpoint(dao)
        val sql=db.openHelper.writableDatabase
        sql.execSQL("UPDATE safe_catalog_items SET title='FR - Foreign' WHERE contentType='movie' AND itemId=1")
        assertNull(dao.languageCheckpoint());assertEquals(1,dao.applyEnglishCategoryPolicy())
        assertEquals(1,dao.countForType("series"));checkpoint(dao)
        dao.insertAll(listOf(movie()));assertNull(dao.languageCheckpoint());checkpoint(dao)
        dao.insertAll(listOf(movie(title="FR - Replacement")));assertNull(dao.languageCheckpoint())
        assertEquals(100L,dao.newestUpdatedAtMs("movie"));assertEquals(1,dao.countForType("movie"))
        assertEquals(1,dao.applyEnglishCategoryPolicy());checkpoint(dao)
        sql.execSQL("DELETE FROM safe_catalog_items WHERE contentType='series'")
        assertNull(dao.languageCheckpoint());checkpoint(dao)
        dao.replaceLibrary(listOf(movie()),listOf(show()));assertNull(dao.languageCheckpoint());checkpoint(dao)
        dao.deleteAll();assertNull(dao.languageCheckpoint());checkpoint(dao)
        dao.seedLegacyCatalogIfEmpty(listOf(movie()));assertNull(dao.languageCheckpoint());checkpoint(dao)
    }

    @Test fun missingOrOlderPolicyRevisionRechecksAndDiscoveryWritesDoNotInvalidate() = fixture { db ->
        val dao=db.safeCatalogDao();dao.insertAll(listOf(movie()));checkpoint(dao)
        dao.saveLanguageCheckpoint(CatalogLanguageCheckpoint(policyRevision=0))
        pages.set(0);checkpoint(dao);assertTrue(pages.get()>0)
        db.openHelper.writableDatabase.execSQL("DELETE FROM catalog_language_checkpoint")
        pages.set(0);checkpoint(dao);assertTrue(pages.get()>0)
        dao.deleteDiscoveryType("movie");pages.set(0);checkpoint(dao);assertEquals(0,pages.get())
    }

    @Test fun missingOrAlteredTriggerFailsClosedAndOpenRepairsIt() = runBlocking(Dispatchers.IO) {
        context.deleteDatabase(name);var db=open()
        try {
            var dao=db.safeCatalogDao();dao.insertAll(listOf(movie()));checkpoint(dao)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER catalog_language_dirty_update")
            db.openHelper.writableDatabase.execSQL("UPDATE safe_catalog_items SET title='FR - Foreign'")
            assertEquals(1,dao.applyEnglishCategoryPolicy());assertEquals(0,dao.countForType("movie"))
            db.close();db=open();dao=db.safeCatalogDao();assertNull(dao.languageCheckpoint())
            dao.insertAll(listOf(movie()));checkpoint(dao)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER catalog_language_dirty_insert")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER catalog_language_dirty_insert AFTER INSERT ON safe_catalog_items BEGIN SELECT 1; END")
            db.close();db=open();dao=db.safeCatalogDao();assertNull(dao.languageCheckpoint())
            dao.insertAll(listOf(movie(2,"FR - Foreign")));assertEquals(1,dao.applyEnglishCategoryPolicy())
            assertEquals(listOf(movie()),dao.itemsForType("movie"))
        } finally {db.close();context.deleteDatabase(name)}
    }

    @Test fun failedCleanupAndCancelledCommitRollBackWithoutBlessingPartialWork() = fixture { db ->
        val dao=db.safeCatalogDao();val rows=listOf(movie(1,"FR - First"),movie(2,"FR - Second"))
        dao.insertAll(rows)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_policy BEFORE DELETE ON safe_catalog_items WHEN OLD.itemId=2 BEGIN SELECT RAISE(ABORT,'fixture failure'); END")
        try {dao.applyEnglishCategoryPolicy();fail("Expected cleanup failure")} catch (_: android.database.sqlite.SQLiteException) {}
        assertNull(dao.languageCheckpoint());assertEquals(rows,dao.itemsForType("movie"))
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_policy")
        try {db.withTransaction {assertEquals(2,dao.applyEnglishCategoryPolicy());throw CancellationException("fixture cancellation")}} catch (_: CancellationException) {}
        assertNull(dao.languageCheckpoint());assertEquals(rows,dao.itemsForType("movie"))
        assertEquals(2,dao.applyEnglishCategoryPolicy());checkpoint(dao)
    }

    @Test fun failedImportRestoresVerifiedLibraryAndSubsequentWriterInvalidates() = fixture { db ->
        val dao=db.safeCatalogDao();dao.replaceLibrary(listOf(movie()),listOf(show()));checkpoint(dao)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_import BEFORE INSERT ON safe_catalog_items WHEN NEW.contentType='series' AND NEW.itemId=2 BEGIN SELECT RAISE(ABORT,'fixture failure'); END")
        try {dao.replaceLibrary(listOf(movie(2)),listOf(show().copy(itemId=2)));fail("Expected import failure")} catch (_: android.database.sqlite.SQLiteException) {}
        assertEquals(listOf(movie()),dao.itemsForType("movie"));assertEquals(listOf(show()),dao.itemsForType("series"))
        pages.set(0);checkpoint(dao);assertEquals(0,pages.get())
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        coroutineScope {
            val verifying=launch {db.withTransaction {checkpoint(dao);entered.complete(Unit);release.await()}}
            entered.await()
            val writer=launch {dao.insertAll(listOf(movie(3,"FR - Later writer")))}
            release.complete(Unit);verifying.join();writer.join()
        }
        assertNull(dao.languageCheckpoint());assertEquals(1,dao.applyEnglishCategoryPolicy())
    }

    @Test fun version10UpgradePreservesEveryOriginalTableAndRequiresFirstVerification() = runBlocking(Dispatchers.IO) {
        context.deleteDatabase(name)
        val schema=JsonParser.parseString(instrumentation.context.assets.open("com.atlas.atlastv.data.db.AtlasDatabase/10.json").bufferedReader().use {it.readText()}).asJsonObject.getAsJsonObject("database")
        val tables=schema.getAsJsonArray("entities").map {it.asJsonObject.get("tableName").asString}
        fun rows(sql: SQLiteDatabase) = tables.associateWith { table -> sql.rawQuery("SELECT * FROM $table",null).use {c ->buildList {while(c.moveToNext()) add((0 until c.columnCount).map {if(c.isNull(it)) null else c.getString(it)})}} }
        val before=SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name),null).use { sql ->
            schema.getAsJsonArray("entities").forEach {e ->val entity=e.asJsonObject;val table=entity.get("tableName").asString
                sql.execSQL(entity.get("createSql").asString.replace("\${TABLE_NAME}",table))
                entity.getAsJsonArray("indices")?.forEach {sql.execSQL(it.asJsonObject.get("createSql").asString.replace("\${TABLE_NAME}",table))}}
            schema.getAsJsonArray("setupQueries").forEach {sql.execSQL(it.asString)}
            sql.execSQL("INSERT INTO safe_catalog_items VALUES('movie',1,'Kept movie','kept movie',NULL,'Movies',1,NULL,'mp4','Missing',NULL,NULL,NULL,100,100)")
            sql.execSQL("INSERT INTO movie_watchlist_items VALUES(1,'Kept movie','Movies','mp4',10)")
            sql.execSQL("INSERT INTO series_watchlist_items VALUES(1,'Kept show','Shows',20)")
            sql.execSQL("INSERT INTO watch_progress_records VALUES('movie',1,NULL,'Kept movie',NULL,NULL,'Movies','mp4',100000,50000,200,0)")
            sql.execSQL("INSERT INTO provider_artwork VALUES('opaque','private-binding')")
            sql.execSQL("INSERT INTO artwork_migrations VALUES('fixture')")
            sql.execSQL("INSERT INTO movie_details_cache VALUES('key',1,100,100,100,100,2,'{}')")
            sql.execSQL("INSERT INTO show_details_cache VALUES('key',1,100,100,100,100,2,'{}')")
            sql.execSQL("INSERT INTO home_hero_cache VALUES('key','movie-1',100,100,100,100,2,'{}')")
            sql.execSQL("INSERT INTO catalog_discovery VALUES('movie',1,100,'movie-1',20260101,'title-year',8000,'imdb',NULL,NULL,'Drama','tt0000001',10000,'fixture')")
            sql.execSQL("INSERT INTO discovery_backfill VALUES('movie','category',100,'key',100,1)")
            sql.version=10;rows(sql)
        }
        val db=open()
        try {
            val sql=db.openHelper.writableDatabase;assertEquals(11,sql.version)
            before.forEach {(table,expected) -> val actual=sql.query("SELECT * FROM $table").use {c ->buildList {while(c.moveToNext()) add((0 until c.columnCount).map {if(c.isNull(it)) null else c.getString(it)})}};assertEquals(table,expected,actual)}
            assertNull(db.safeCatalogDao().languageCheckpoint());checkpoint(db.safeCatalogDao())
        } finally {db.close();context.deleteDatabase(name)}
    }
}

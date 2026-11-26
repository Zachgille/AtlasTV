package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.data.catalog.*
import com.atlas.atlastv.xtream.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** In-memory only. Never opens the user's catalog, account or watch-state stores. */
class CatalogRefreshGuardConnectedTest {
    private suspend fun database(check: suspend (AtlasDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AtlasDatabase::class.java).build()
        try { check(db) } finally { db.close() }
    }
    private fun row(type: String, id: Int) = SafeCatalogItemEntity(type, id, "Saved $type $id", categoryName = "US", providerOrder = id,
        containerExtension = null, posterState = "Missing", posterSafeUri = null, posterProviderToken = null,
        posterRedactedSource = null, importedAtMs = 123, updatedAtMs = 456)
    private suspend fun rows(dao: SafeCatalogDao) = dao.itemsForType("movie") + dao.itemsForType("series")

    @Test fun eitherEmptyTypeRejectsTheWholePairWithoutChangingAnyRow() = runBlocking(Dispatchers.IO) { database { db ->
        val dao = db.safeCatalogDao(); val original = listOf(row("movie", 1), row("series", 2)); dao.insertAll(original)
        for ((movies, shows) in listOf(emptyList<SafeCatalogItemEntity>() to emptyList<SafeCatalogItemEntity>(),
            emptyList<SafeCatalogItemEntity>() to listOf(row("series", 22)), listOf(row("movie", 11)) to emptyList())) {
            assertEquals(CatalogRefreshWriteResult.RefusedEmptyCatalog, dao.replaceLibraryFromRefresh(movies, shows))
            assertEquals(original, rows(dao))
        }
    } }

    @Test fun initiallyEmptyTypesRemainEmptyAndNormalRefreshReplacesBoth() = runBlocking(Dispatchers.IO) { database { db ->
        val dao = db.safeCatalogDao()
        assertEquals(CatalogRefreshWriteResult.Replaced, dao.replaceLibraryFromRefresh(emptyList(), emptyList()))
        assertEquals(CatalogRefreshWriteResult.Replaced, dao.replaceLibraryFromRefresh(listOf(row("movie", 1)), emptyList()))
        assertEquals(CatalogRefreshWriteResult.Replaced, dao.replaceLibraryFromRefresh(listOf(row("movie", 11)), listOf(row("series", 22))))
        assertEquals(listOf(row("movie", 11), row("series", 22)), rows(dao))
    } }

    @Test fun importerRefusesFilteredAndInvalidIdsUsingTheRealRoomGuard() = runBlocking(Dispatchers.IO) { database { db ->
        val dao = db.safeCatalogDao(); val original = listOf(row("movie", 1), row("series", 2)); dao.insertAll(original)
        val importer = XtreamCatalogImporter(SafeCatalogRepository(dao))
        val validShow = XtreamSeries(1,"Show",22,null,null,null,null,null,null,null,null,null,null)
        for (candidate in listOf(XtreamVodStream(1,"Movie","movie",11,null,null,null,null,"foreign","mp4"),
            XtreamVodStream(1,"Movie","movie",0,null,null,null,null,null,"mp4"))) {
            val result = importer.importMoviesAndSeries(listOf(XtreamCategory("foreign","French Movies",null)),listOf(candidate),emptyList(),listOf(validShow))
            assertEquals(CatalogSyncStatus.Failure,result.status)
            assertEquals(CatalogRefreshWriteResult.RefusedEmptyCatalog.name,result.errorCategory)
            assertEquals(original,rows(dao))
        }
    } }

    @Test fun insertFailureRollsBackBothDeletesAndAnyNewRows() = runBlocking(Dispatchers.IO) { database { db ->
        val dao = db.safeCatalogDao(); val original = listOf(row("movie", 1), row("series", 2)); dao.insertAll(original)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_fixture_show BEFORE INSERT ON safe_catalog_items WHEN NEW.contentType = 'series' AND NEW.itemId = 22 BEGIN SELECT RAISE(ABORT, 'isolated insertion failure'); END")
        try { dao.replaceLibraryFromRefresh(listOf(row("movie",11)),listOf(row("series",22))); fail("Expected insertion failure") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(original, rows(dao))
    } }

    @Test fun cancellationOfAnEnclosingTransactionPreservesTheOldPair() = runBlocking(Dispatchers.IO) { database { db ->
        val dao = db.safeCatalogDao(); val original = listOf(row("movie",1),row("series",2)); dao.insertAll(original)
        try {
            db.withTransaction { assertEquals(CatalogRefreshWriteResult.Replaced,dao.replaceLibraryFromRefresh(listOf(row("movie",11)),listOf(row("series",22))))
                throw CancellationException("isolated cancellation") }
        } catch (_: CancellationException) { }
        assertEquals(original,rows(dao))
    } }
}

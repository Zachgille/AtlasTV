package com.atlas.atlastv.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Isolated Room only. Never seeds or clears the personal catalog. */
class LibraryUsabilityRoomConnectedTest {
    @Test fun policyPagesVisitEveryTypedTitleOnceUsingIndexedSeeks() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(),AtlasDatabase::class.java).build()
        try {
            val dao=db.safeCatalogDao()
            val entries=(1..750).map {MovieItem("Movie $it","Movies",it*3,"mp4","").toSafeCatalogEntity(it,1)}+
                (1..650).map {SeriesItem("Show $it","Shows",it*3).toSafeCatalogEntity(it,1)}
            dao.insertAll(entries)
            val visited=mutableListOf<CatalogLanguageItem>();var type="";var id=-1
            while(true) {
                val page=dao.languagePolicyPage(type,id)
                if(page.isEmpty()) break
                assertTrue(page.size<=200);visited+=page;type=page.last().contentType;id=page.last().itemId
            }
            assertEquals(entries.sortedWith(compareBy<SafeCatalogItemEntity> {it.contentType}.thenBy {it.itemId})
                .map {CatalogLanguageItem(it.contentType,it.itemId,it.title)},visited)
            val plans=mutableListOf<String>()
            db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN SELECT contentType,itemId,title FROM safe_catalog_items WHERE contentType='movie' AND itemId>900 UNION ALL SELECT contentType,itemId,title FROM safe_catalog_items WHERE contentType>'movie' ORDER BY contentType,itemId LIMIT 200").use {cursor ->
                while(cursor.moveToNext()) plans+=cursor.getString(3)
            }
            assertEquals(2,plans.count {it.contains("SEARCH safe_catalog_items")})
            assertFalse(plans.any {it.contains("SCAN safe_catalog_items")})
        } finally {db.close()}
    }
    @Test fun titleCleanupCrossesPageAndContentTypeBoundariesWithoutSkippingKeptItems() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlasDatabase::class.java).build()
        try {
            val dao = db.safeCatalogDao()
            val movies = (1..451).map { MovieItem(if (it % 2 == 0) "Film جن $it" else "English movie $it", "NETFLIX MOVIES", it, "mp4", "").toSafeCatalogEntity(it, 1) }
            val shows = (1..451).map { SeriesItem(if (it % 2 == 0) "Show تكي $it" else "English show $it", "NETFLIX SERIES", it).toSafeCatalogEntity(it, 1) }
            dao.replaceLibrary(movies, shows)
            assertEquals(450, dao.applyEnglishCategoryPolicy())
            assertEquals(movies.filter { it.itemId % 2 == 1 }, dao.itemsForType("movie"))
            assertEquals(shows.filter { it.itemId % 2 == 1 }, dao.itemsForType("series"))
            assertEquals(0, dao.applyEnglishCategoryPolicy())
        } finally { db.close() }
    }
    @Test fun foreignCategoryCleanupIsIdempotentAndLegacyFallbackCannotReintroduceIt() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlasDatabase::class.java).build()
        try {
            val dao=db.safeCatalogDao(); val repository=SafeCatalogRepository(dao)
            val kept=SeriesItem("Blindspot", "ENGLISH SERIES", 1).toSafeCatalogEntity(0,1)
            val foreign=SeriesItem("Drakryttarna De Nio Rikena", "VIAPLAY KIDS", 2).toSafeCatalogEntity(1,1)
            dao.insertAll(listOf(kept,foreign))
            assertEquals(1,dao.applyEnglishCategoryPolicy()); assertEquals(0,dao.applyEnglishCategoryPolicy())
            assertEquals(listOf(kept),dao.itemsForType("series"))
            dao.deleteAll()
            repository.seedLegacySeriesIfEmpty(listOf(SeriesItem(foreign.title,foreign.categoryName,2)))
            assertEquals(0,dao.countForType("series"))
        } finally { db.close() }
    }
    @Test fun interruptedSecondTypeImportRollsBackBothTypesToLastGoodLibrary() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AtlasDatabase::class.java).build()
        try {
            val dao=db.safeCatalogDao()
            val movie=MovieItem("Saved movie","Movies",1,"mp4","").toSafeCatalogEntity(0,1)
            val show=SeriesItem("Saved show","Shows",1).toSafeCatalogEntity(0,1)
            dao.replaceLibrary(listOf(movie),listOf(show))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_show BEFORE INSERT ON safe_catalog_items WHEN NEW.contentType='series' AND NEW.itemId=2 BEGIN SELECT RAISE(ABORT,'controlled failure'); END")
            try { dao.replaceLibrary(listOf(movie.copy(itemId=2)),listOf(show.copy(itemId=2))); fail("Expected rollback") } catch (_: android.database.sqlite.SQLiteException) {}
            assertEquals(listOf(movie),dao.itemsForType("movie")); assertEquals(listOf(show),dao.itemsForType("series"))
        } finally { db.close() }
    }
}

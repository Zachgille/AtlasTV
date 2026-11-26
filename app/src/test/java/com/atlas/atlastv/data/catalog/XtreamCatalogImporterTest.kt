package com.atlas.atlastv.data.catalog

import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import com.atlas.atlastv.data.db.SafeCatalogCategorySummary
import com.atlas.atlastv.data.db.SafeCatalogDao
import com.atlas.atlastv.data.db.SafeCatalogItemEntity
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.xtream.model.XtreamCategory
import com.atlas.atlastv.xtream.model.XtreamSeries
import com.atlas.atlastv.xtream.model.XtreamVodStream
import com.atlas.atlastv.ui.media.AtlasImageState
import com.atlas.atlastv.ui.media.AtlasProviderImageIndex
import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class XtreamCatalogImporterTest {
    @Test
    fun largeFixtureImportWritesSafeRowsWithoutDebugJson() = runBlocking {
        val dao = FakeSafeCatalogDao()
        val importer = XtreamCatalogImporter(SafeCatalogRepository(dao) { 1_000L }, clockMs = { 1_000L })

        val result = importer.importMoviesAndSeries(
            vodCategories = listOf(
                XtreamCategory("movies-us", "US Movies", null),
                XtreamCategory("movies-fr", "French Movies", null),
            ),
            vodStreams = (1..620).map { index ->
                XtreamVodStream(
                    num = index,
                    name = if (index == 1) "http://provider.example/movie/fake_user/fake_pass/$index.mp4" else "Imported Movie $index",
                    streamType = "movie",
                    streamId = 10_000 + index,
                    streamIcon = if (index == 2) "http://provider.example/poster/$index.jpg" else null,
                    rating = null,
                    rating5Based = null,
                    added = "170000$index",
                    categoryId = if (index <= 600) "movies-us" else "movies-fr",
                    containerExtension = "mp4",
                )
            },
            seriesCategories = listOf(
                XtreamCategory("series-us", "US Series", null),
                XtreamCategory("series-fr", "French Series", null),
            ),
            series = (1..540).map { index ->
                XtreamSeries(
                    num = index,
                    name = "Imported Series $index",
                    seriesId = 20_000 + index,
                    cover = null,
                    plot = null,
                    cast = null,
                    director = null,
                    genre = null,
                    releaseDate = null,
                    lastModified = "170500$index",
                    rating = null,
                    rating5Based = null,
                    categoryId = if (index <= 520) "series-us" else "series-fr",
                )
            },
        )

        val storedJson = Gson().toJson(dao.rows.values)

        assertEquals(CatalogSyncStatus.Success, result.status)
        assertEquals(600, result.moviesImported)
        assertEquals(520, result.seriesImported)
        assertEquals(2, result.excludedCategories)
        assertEquals(40, result.excludedItems)
        assertTrue("Expected multiple insert batches for large imports.", dao.insertCallSizes.size >= 3)
        assertEquals(600, dao.itemsForType(SafeCatalogContentTypes.Movie).size)
        assertEquals(520, dao.itemsForType(SafeCatalogContentTypes.Series).size)
        assertEquals("Movie 10001", dao.itemsForType(SafeCatalogContentTypes.Movie).first().title)
        assertFalse(storedJson.contains("provider.example"))
        assertFalse(storedJson.contains("fake_user"))
        assertFalse(storedJson.contains("fake_pass"))
        assertFalse(storedJson.contains("/movie/"))
        assertFalse(storedJson.contains("/series/"))
    }

    @Test
    fun repositoryQueriesAreBoundedAndSearchIsBounded() = runBlocking {
        val dao = FakeSafeCatalogDao()
        val repository = SafeCatalogRepository(dao) { 2_000L }
        val importer = XtreamCatalogImporter(repository, clockMs = { 2_000L })
        importer.importMoviesAndSeries(
            vodCategories = listOf(XtreamCategory("movies-us", "US Movies", null)),
            vodStreams = (1..250).map { index ->
                XtreamVodStream(
                    num = index,
                    name = if (index <= 150) "Drama Movie $index" else "Other Movie $index",
                    streamType = "movie",
                    streamId = 30_000 + index,
                    streamIcon = null,
                    rating = null,
                    rating5Based = null,
                    added = null,
                    categoryId = "movies-us",
                    containerExtension = "mp4",
                )
            },
            seriesCategories = emptyList(),
            series = emptyList(),
        )

        assertEquals(120, repository.movies { "redacted" }.size)
        assertEquals(50, repository.movieEntities(limit = 50).size)
        assertEquals(25, repository.searchMovieEntities("drama", limit = 25).size)
        assertEquals(150, repository.countSearchMovies("drama"))
        assertEquals(2, repository.movieCategories(limit = 1).size)
        assertEquals("Other Movie 151", repository.movieEntityAtOffset(150)?.title)
        assertTrue(repository.movieCategorySummaries().any { it.name == "US Movies" && it.itemCount == 250 })
    }

    @Test
    fun importPersistsProviderImageIndexWithoutRawUrlsInRoomRows() = runBlocking {
        val dao = FakeSafeCatalogDao()
        val imageIndex = AtlasProviderImageIndex(File(tempDir(), AtlasProviderImageIndex.FileName))
        val importer = XtreamCatalogImporter(
            repository = SafeCatalogRepository(dao) { 3_000L },
            providerImageIndex = imageIndex,
            clockMs = { 3_000L },
        )
        val rawPoster = "https://example.test/images/movie-one.jpg"

        importer.importMoviesAndSeries(
            vodCategories = listOf(XtreamCategory("movies-us", "US Movies", null)),
            vodStreams = listOf(
                XtreamVodStream(
                    num = 1,
                    name = "Imported Movie",
                    streamType = "movie",
                    streamId = 40_001,
                    streamIcon = rawPoster,
                    rating = null,
                    rating5Based = null,
                    added = null,
                    categoryId = "movies-us",
                    containerExtension = "mp4",
                ),
            ),
            seriesCategories = emptyList(),
            series = emptyList(),
        )

        val row = dao.itemsForType(SafeCatalogContentTypes.Movie).single()
        val storedJson = Gson().toJson(row)

        assertEquals(AtlasImageState.ProviderImage.name, row.posterState)
        assertEquals(rawPoster, imageIndex.resolve(row.posterProviderToken))
        assertFalse(storedJson.contains("example.test"))
        assertFalse(storedJson.contains(rawPoster))
    }

    @Test fun explicitForeignTagsAreFilteredBeforeDisplayCleanupCanRemoveTheEvidence() = runBlocking {
        val dao = FakeSafeCatalogDao()
        val result = XtreamCatalogImporter(SafeCatalogRepository(dao)).importMoviesAndSeries(
            emptyList(), listOf(movie(1, name = "NF | [FR] Film"), movie(2, name = "[FRENCH] Film"), movie(3, name = "French Kiss")),
            emptyList(), listOf(show(1, name = "Mom"), show(2, name = "English: GER | Show"), show(3, name = "FROM")))
        assertEquals(CatalogSyncStatus.Success, result.status)
        assertEquals(3, result.excludedItems)
        assertEquals(setOf("movie" to 3, "series" to 1, "series" to 3), dao.rows.keys)
        assertEquals("Mom", dao.rows["series" to 1]!!.title)
        assertEquals("French Kiss", dao.rows["movie" to 3]!!.title)
    }

    @Test fun emptyPairKeepsEveryLastGoodRow() = runBlocking {
        assertRefused(emptyList(), emptyList())
    }

    @Test fun emptyMoviesRejectTheValidShowReplacementToo() = runBlocking {
        assertRefused(emptyList(), listOf(show(22)))
    }

    @Test fun emptyShowsRejectTheValidMovieReplacementToo() = runBlocking {
        assertRefused(listOf(movie(11)), emptyList())
    }

    @Test fun filteringEverythingOutCannotEraseTheSavedLibrary() = runBlocking {
        assertRefused(listOf(movie(11, category = "foreign")), listOf(show(22)),
            listOf(XtreamCategory("foreign", "French Movies", null)))
        assertRefused(listOf(movie(11, name = "日本の映画")), listOf(show(22)))
        assertRefused(listOf(movie(11)), listOf(show(22, name = "日本の番組")))
    }

    @Test fun invalidIdsCannotBecomeUsableReplacementTitles() = runBlocking {
        assertRefused(listOf(movie(null), movie(0), movie(-2)), listOf(show(22)))
        assertRefused(listOf(movie(11)), listOf(show(null), show(0), show(-2)))
    }

    @Test fun freshEmptyTypesAndPopulatedSingleTypeLibrariesRemainValid() = runBlocking {
        for ((movies, shows) in listOf(emptyList<XtreamVodStream>() to emptyList<XtreamSeries>(),
            listOf(movie(1)) to emptyList(), emptyList<XtreamVodStream>() to listOf(show(2)))) {
            val dao = FakeSafeCatalogDao(); val importer = XtreamCatalogImporter(SafeCatalogRepository(dao))
            assertEquals(CatalogSyncStatus.Success, importer.importMoviesAndSeries(emptyList(), movies, emptyList(), shows).status)
            assertEquals(CatalogSyncStatus.Success, importer.importMoviesAndSeries(emptyList(), movies, emptyList(), shows).status)
            assertEquals(movies.size + shows.size, dao.rows.size)
        }
    }

    @Test fun ordinaryRefreshReplacesBothTypesAndFiltersInvalidIds() = runBlocking {
        val dao = FakeSafeCatalogDao(); val importer = XtreamCatalogImporter(SafeCatalogRepository(dao))
        importer.importMoviesAndSeries(emptyList(), listOf(movie(1)), emptyList(), listOf(show(2)))
        val result = importer.importMoviesAndSeries(emptyList(), listOf(movie(0), movie(11)), emptyList(), listOf(show(-1), show(22)))
        assertEquals(CatalogSyncStatus.Success, result.status)
        assertEquals(setOf("movie" to 11, "series" to 22), dao.rows.keys)
        assertEquals(1, result.moviesImported); assertEquals(1, result.seriesImported)
    }

    @Test fun refusedImportDoesNotRegisterUnusedArtwork() = runBlocking {
        val dao = FakeSafeCatalogDao(); val indexFile = File(tempDir(), "guard-artwork.json")
        val index = AtlasProviderImageIndex(indexFile)
        val importer = XtreamCatalogImporter(SafeCatalogRepository(dao), providerImageIndex = index)
        importer.importMoviesAndSeries(emptyList(), listOf(movie(1)), emptyList(), listOf(show(2)))
        val candidate = show(22).copy(cover = "https://example.test/unused.jpg")
        assertEquals(CatalogSyncStatus.Failure, importer.importMoviesAndSeries(emptyList(), emptyList(), emptyList(), listOf(candidate)).status)
        assertFalse(indexFile.exists())
    }

    @Test fun optionalArtworkStorageFailureDoesNotFailCommittedUsableTitles() = runBlocking {
        val dao = FakeSafeCatalogDao(); val directory = tempDir()
        java.io.File(directory, "keep-directory").writeText("fixture")
        val importer = XtreamCatalogImporter(SafeCatalogRepository(dao), providerImageIndex = AtlasProviderImageIndex(directory))
        val result = importer.importMoviesAndSeries(emptyList(), listOf(movie(11).copy(streamIcon = "https://example.test/poster.jpg")), emptyList(), listOf(show(22)))
        assertEquals(CatalogSyncStatus.Success, result.status)
        assertEquals(setOf("movie" to 11, "series" to 22), dao.rows.keys)
        assertTrue(directory.isDirectory)
    }

    private suspend fun assertRefused(movies: List<XtreamVodStream>, shows: List<XtreamSeries>, categories: List<XtreamCategory> = emptyList()) {
        val dao = FakeSafeCatalogDao(); val importer = XtreamCatalogImporter(SafeCatalogRepository(dao), clockMs = { 9_000L })
        importer.importMoviesAndSeries(emptyList(), listOf(movie(1)), emptyList(), listOf(show(2)))
        val before = dao.rows.toMap(); val inserts = dao.insertCallSizes.toList()
        val result = importer.importMoviesAndSeries(categories, movies, emptyList(), shows)
        assertEquals(CatalogSyncStatus.Failure, result.status)
        assertEquals(com.atlas.atlastv.data.db.CatalogRefreshWriteResult.RefusedEmptyCatalog.name, result.errorCategory)
        assertEquals(before, dao.rows); assertEquals(inserts, dao.insertCallSizes)
        assertEquals(0, result.moviesImported); assertEquals(0, result.seriesImported)
    }

    private fun movie(id: Int?, name: String = "Movie", category: String? = null) =
        XtreamVodStream(1, name, "movie", id, null, null, null, null, category, "mp4")
    private fun show(id: Int?, name: String = "Show") =
        XtreamSeries(1, name, id, null, null, null, null, null, null, null, null, null, null)

    private class FakeSafeCatalogDao : SafeCatalogDao {
        override suspend fun genreCollections(query: androidx.sqlite.db.SupportSQLiteQuery): List<com.atlas.atlastv.data.discovery.DiscoveryGenreItem> = error("Not used by importer")
        override suspend fun discoveryWindowCount(query: androidx.sqlite.db.SupportSQLiteQuery): Int = error("Not used by importer")
        override suspend fun discoveryWindowRank(query: androidx.sqlite.db.SupportSQLiteQuery): com.atlas.atlastv.data.discovery.DiscoveryRank? = error("Not used by importer")
        override suspend fun discoveryWindowItems(query: androidx.sqlite.db.SupportSQLiteQuery): List<SafeCatalogItemEntity> = error("Not used by importer")
        override suspend fun missingDiscoveryPage(type: String, id: Int): List<SafeCatalogItemEntity> = error("Not used by importer")
        override suspend fun imdbMatchPage(dataset: String, type: String, id: Int): List<SafeCatalogItemEntity> = error("Not used by importer")
        override suspend fun discoveryMetadataForIds(type: String, ids: List<Int>) = discovery.values.filter {it.contentType==type && it.itemId in ids}
        val discovery = linkedMapOf<Pair<String, Int>, com.atlas.atlastv.data.db.CatalogDiscoveryEntity>()
        val completions = mutableMapOf<Pair<String,String>, com.atlas.atlastv.data.db.DiscoveryBackfillEntity>()
        override suspend fun insertDiscovery(items: List<com.atlas.atlastv.data.db.CatalogDiscoveryEntity>) { items.forEach { discovery[it.contentType to it.itemId] = it } }
        override suspend fun deleteDiscoveryType(type: String) { discovery.keys.filter { it.first == type }.forEach(discovery::remove) }
        override suspend fun discoveryMetadata(type: String, id: Int) = discovery[type to id]
        override suspend fun discoveryBackfill(type: String, category: String) = completions[type to category]
        override suspend fun saveDiscoveryBackfill(value: com.atlas.atlastv.data.db.DiscoveryBackfillEntity) { completions[value.contentType to value.categoryId] = value }
        override suspend fun discoveryCategories(type: String) = rows.values.filter { it.contentType==type && it.categoryId != null }.groupBy { it.categoryId!! }.map { com.atlas.atlastv.data.db.DiscoveryCategoryStamp(it.key,it.value.maxOf { r -> r.updatedAtMs }) }
        override suspend fun discoveryRetainedIds(type: String, category: String, ids: List<Int>) = rows.values.filter { it.contentType==type && it.categoryId==category && it.itemId in ids }
        override suspend fun newDiscovery(type: String, minimum: Int, today: Int, limit: Int): List<SafeCatalogItemEntity> = error("Not used by importer")
        override suspend fun ratedDiscovery(type: String, limit: Int): List<SafeCatalogItemEntity> = error("Not used by importer")
        override suspend fun addedDiscovery(type: String, now: Long, limit: Int): List<SafeCatalogItemEntity> = error("Not used by importer")
        override suspend fun updatedDiscovery(type: String, now: Long, limit: Int): List<SafeCatalogItemEntity> = error("Not used by importer")
        override suspend fun genreDiscovery(type: String, genre: String, limit: Int): List<SafeCatalogItemEntity> = error("Not used by importer")

        override suspend fun countItemsInCategory(type: String, category: String) = rows.values.count { it.contentType == type && it.categoryName == category }
        override suspend fun categoryPage(type: String, category: String, offset: Int, limit: Int) =
            itemsForType(type).filter { it.categoryName == category }.drop(offset).take(limit)
        override suspend fun categoryOffset(type: String, category: String, id: Int) =
            itemsForType(type).filter { it.categoryName == category }.indexOfFirst { it.itemId == id }.takeIf { it >= 0 }
        override suspend fun allCategories(type: String) = allCategorySummariesForType(type, Int.MAX_VALUE)
        override suspend fun languageCheckpoint(): com.atlas.atlastv.data.db.CatalogLanguageCheckpoint? = null
        override suspend fun saveLanguageCheckpoint(value: com.atlas.atlastv.data.db.CatalogLanguageCheckpoint) {}
        override suspend fun languageTriggers(query: androidx.sqlite.db.SupportSQLiteQuery): List<com.atlas.atlastv.data.db.CatalogLanguageTrigger> = emptyList()
        override suspend fun catalogCategoryNames(): List<String> = emptyList()
        override suspend fun removeCatalogCategories(names: List<String>): Int = 0
        override suspend fun languagePolicyPage(type: String, id: Int): List<com.atlas.atlastv.data.db.CatalogLanguageItem> = emptyList()
        override suspend fun removeCatalogIds(type: String, ids: List<Int>): Int = 0
        override suspend fun searchPage(type: String, query: String, offset: Int, limit: Int, exactQuery: String) =
            searchItemsForType(type, query, rows.size, exactQuery).drop(offset).take(limit)
        override suspend fun searchOffset(type: String, query: String, id: Int, exactQuery: String): Int? =
            searchItemsForType(type, query, rows.size, exactQuery).indexOfFirst { it.itemId == id }.takeIf { it >= 0 }
        val rows = linkedMapOf<Pair<String, Int>, SafeCatalogItemEntity>()
        val insertCallSizes = mutableListOf<Int>()
        override suspend fun pageForType(contentType: String, offset: Int, limit: Int): List<SafeCatalogItemEntity> =
            itemsForType(contentType).drop(offset).take(limit)
        override suspend fun offsetForItem(contentType: String, itemId: Int): Int? =
            itemsForType(contentType).indexOfFirst { it.itemId == itemId }.takeIf { it >= 0 }

        override suspend fun itemsForType(contentType: String): List<SafeCatalogItemEntity> =
            rows.values.filter { it.contentType == contentType }.sorted()

        override suspend fun itemsForType(contentType: String, limit: Int): List<SafeCatalogItemEntity> =
            itemsForType(contentType).take(limit)

        override suspend fun itemForTypeAtOffset(contentType: String, offset: Int): SafeCatalogItemEntity? =
            itemsForType(contentType).drop(offset).firstOrNull()

        override suspend fun itemsForTypeTitle(contentType: String, limit: Int): List<SafeCatalogItemEntity> =
            rows.values
                .filter { it.contentType == contentType }
                .sortedWith(compareBy<SafeCatalogItemEntity> { it.title.lowercase() }.thenBy { it.itemId })
                .take(limit)

        override suspend fun itemsForTypeCategoryThenTitle(contentType: String, limit: Int): List<SafeCatalogItemEntity> =
            rows.values
                .filter { it.contentType == contentType }
                .sortedWith(
                    compareBy<SafeCatalogItemEntity> { it.categoryName.lowercase() }
                        .thenBy { it.title.lowercase() }
                        .thenBy { it.itemId },
                )
                .take(limit)

        override suspend fun recentlyAddedItemsForType(contentType: String, limit: Int): List<SafeCatalogItemEntity> =
            rows.values
                .filter { it.contentType == contentType }
                .sortedWith(
                    compareByDescending<SafeCatalogItemEntity> { it.addedAtMs ?: Long.MIN_VALUE }
                        .thenByDescending { it.providerOrder }
                        .thenByDescending { it.itemId },
                )
                .take(limit)

        override suspend fun itemsForCategory(
            contentType: String,
            categoryName: String,
            limit: Int,
        ): List<SafeCatalogItemEntity> =
            itemsForType(contentType).filter { it.categoryName == categoryName }.take(limit)

        override suspend fun itemsForCategoryTitle(
            contentType: String,
            categoryName: String,
            limit: Int,
        ): List<SafeCatalogItemEntity> =
            itemsForTypeTitle(contentType, rows.size).filter { it.categoryName == categoryName }.take(limit)

        override suspend fun itemsForCategoryThenTitle(
            contentType: String,
            categoryName: String,
            limit: Int,
        ): List<SafeCatalogItemEntity> =
            itemsForTypeCategoryThenTitle(contentType, rows.size).filter { it.categoryName == categoryName }.take(limit)

        override suspend fun itemsByIds(
            contentType: String,
            itemIds: List<Int>,
            limit: Int,
        ): List<SafeCatalogItemEntity> =
            itemsForType(contentType).filter { it.itemId in itemIds }.take(limit)

        override suspend fun searchItemsForType(
            contentType: String,
            normalizedQuery: String,
            limit: Int,
            exactQuery: String,
        ): List<SafeCatalogItemEntity> =
            itemsForType(contentType)
                .filter {
                    normalizedQuery.isBlank() ||
                        it.normalizedTitle.contains(normalizedQuery) ||
                        it.categoryName.lowercase().contains(normalizedQuery)
                }
                .take(limit)

        override suspend fun countSearchItemsForType(contentType: String, normalizedQuery: String): Int =
            itemsForType(contentType)
                .count {
                    normalizedQuery.isBlank() ||
                        it.normalizedTitle.contains(normalizedQuery) ||
                        it.categoryName.lowercase().contains(normalizedQuery)
                }

        override suspend fun categorySummariesForType(
            contentType: String,
            minItems: Int,
            limit: Int,
        ): List<SafeCatalogCategorySummary> =
            rows.values
                .filter { it.contentType == contentType }
                .groupBy { it.categoryName }
                .map { (name, items) -> SafeCatalogCategorySummary(name = name, itemCount = items.size) }
                .filter { it.itemCount >= minItems }
                .sortedBy { it.name.lowercase() }
                .take(limit)

        override suspend fun allCategorySummariesForType(contentType: String, limit: Int): List<SafeCatalogCategorySummary> =
            rows.values
                .filter { it.contentType == contentType }
                .groupBy { it.categoryName }
                .map { (name, items) -> SafeCatalogCategorySummary(name = name, itemCount = items.size) }
                .sortedBy { it.name.lowercase() }
                .take(limit)

        override suspend fun deleteType(contentType: String) {
            rows.keys.filter { it.first == contentType }.forEach(rows::remove)
        }

        override suspend fun insertAll(items: List<SafeCatalogItemEntity>) {
            insertCallSizes += items.size
            items.forEach { rows[it.contentType to it.itemId] = it }
        }

        override suspend fun deleteAll() {
            rows.clear()
        }

        override suspend fun countForType(contentType: String): Int =
            rows.values.count { it.contentType == contentType }

        override suspend fun newestUpdatedAtMs(contentType: String): Long? =
            rows.values.filter { it.contentType == contentType }.maxOfOrNull { it.updatedAtMs }

        private fun List<SafeCatalogItemEntity>.sorted(): List<SafeCatalogItemEntity> =
            sortedWith(compareBy<SafeCatalogItemEntity> { it.providerOrder }.thenBy { it.itemId })
    }

    private fun tempDir(): File =
        Files.createTempDirectory("atlas-importer-image-index-").toFile().also { it.deleteOnExit() }
}

package com.atlas.atlastv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction

@Dao
interface SafeCatalogDao {

    @RawQuery
    suspend fun genreCollections(query: androidx.sqlite.db.SupportSQLiteQuery): List<com.atlas.atlastv.data.discovery.DiscoveryGenreItem>

    @RawQuery suspend fun discoveryWindowCount(query: androidx.sqlite.db.SupportSQLiteQuery): Int
    @RawQuery suspend fun discoveryWindowRank(query: androidx.sqlite.db.SupportSQLiteQuery): com.atlas.atlastv.data.discovery.DiscoveryRank?
    @RawQuery suspend fun discoveryWindowItems(query: androidx.sqlite.db.SupportSQLiteQuery): List<SafeCatalogItemEntity>
    @Transaction
    suspend fun discoveryWindow(spec: com.atlas.atlastv.data.discovery.DiscoveryCollectionSpec, now: Long, index: Int, selectedId: Int?): SafeCatalogWindow {
        val query=com.atlas.atlastv.data.discovery.DiscoveryWindowQuery(spec,now)
        val total=discoveryWindowCount(query.count())
        val anchor=(selectedId?.let {discoveryWindowRank(query.selected(it))}?.let {discoveryWindowCount(query.before(it))} ?: index).coerceIn(0,(total-1).coerceAtLeast(0))
        val offset=maxOf(0,anchor/SafeCatalogWindow.PageSize-2)*SafeCatalogWindow.PageSize
        return SafeCatalogWindow(total,offset,anchor,discoveryWindowItems(query.page(offset)))
    }

    @Query("""SELECT c.* FROM safe_catalog_items c LEFT JOIN catalog_discovery m
        ON c.contentType=m.contentType AND c.itemId=m.itemId
        WHERE (m.itemId IS NULL OR m.catalogAtMs != c.updatedAtMs)
        AND (c.contentType > :type OR (c.contentType=:type AND c.itemId>:id)) ORDER BY c.contentType,c.itemId LIMIT 200""")
    suspend fun missingDiscoveryPage(type: String, id: Int): List<SafeCatalogItemEntity>

    @Transaction
    suspend fun seedDiscovery(items: List<CatalogDiscoveryEntity>): Int {
        val updates=items.groupBy {it.contentType}.flatMap { (type,rows) ->
            val current=discoveryMetadataForIds(type,rows.map {it.itemId}).associateBy {it.itemId}
            val retained=itemsByIds(type,rows.map {it.itemId},200).associateBy {it.itemId}
            rows.filter {retained[it.itemId]?.updatedAtMs==it.catalogAtMs && current[it.itemId]?.catalogAtMs!=it.catalogAtMs}
        }
        insertDiscovery(updates)
        return updates.size
    }

    @Query("""SELECT c.* FROM safe_catalog_items c JOIN catalog_discovery m ON c.contentType=m.contentType AND c.itemId=m.itemId AND c.updatedAtMs=m.catalogAtMs
        WHERE (m.ratingDataset IS NULL OR m.ratingDataset != :dataset) AND (c.contentType > :type OR (c.contentType=:type AND c.itemId>:id)) ORDER BY c.contentType,c.itemId LIMIT 200""")
    suspend fun imdbMatchPage(dataset: String, type: String, id: Int): List<SafeCatalogItemEntity>
    @Query("SELECT * FROM catalog_discovery WHERE contentType=:type AND itemId IN (:ids)")
    suspend fun discoveryMetadataForIds(type: String, ids: List<Int>): List<CatalogDiscoveryEntity>
    @Transaction
    suspend fun commitImdb(items: List<CatalogDiscoveryEntity>): Int {
        val updates=mutableListOf<CatalogDiscoveryEntity>()
        for((type, rows) in items.groupBy {it.contentType}) {
            val current=discoveryMetadataForIds(type,rows.map {it.itemId}).associateBy {it.itemId}
            val retained=itemsByIds(type,rows.map {it.itemId},200).associateBy {it.itemId}
            for(row in rows) current[row.itemId]?.takeIf {it.catalogAtMs==row.catalogAtMs && retained[row.itemId]?.updatedAtMs==row.catalogAtMs && it.releaseSort==row.releaseSort && it.variantKey==row.variantKey}?.let { old ->
                updates+=old.copy(ratingMilli=row.ratingMilli,ratingSource=row.ratingSource,ratingVotes=row.ratingVotes,imdbId=row.imdbId,ratingDataset=row.ratingDataset,genres=row.genres)
            }
        }
        insertDiscovery(updates)
        return updates.size
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDiscovery(items: List<CatalogDiscoveryEntity>)
    @Query("DELETE FROM catalog_discovery WHERE contentType=:type")
    suspend fun deleteDiscoveryType(type: String)
    @Query("SELECT * FROM catalog_discovery WHERE contentType=:type AND itemId=:id")
    suspend fun discoveryMetadata(type: String, id: Int): CatalogDiscoveryEntity?
    @Query("SELECT * FROM discovery_backfill WHERE contentType=:type AND categoryId=:category")
    suspend fun discoveryBackfill(type: String, category: String): DiscoveryBackfillEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDiscoveryBackfill(value: DiscoveryBackfillEntity)
    @Query("SELECT categoryId AS id, MAX(updatedAtMs) AS stamp FROM safe_catalog_items WHERE contentType=:type AND categoryId IS NOT NULL GROUP BY categoryId ORDER BY categoryId")
    suspend fun discoveryCategories(type: String): List<DiscoveryCategoryStamp>
    @Query("SELECT * FROM safe_catalog_items WHERE contentType=:type AND categoryId=:category AND itemId IN (:ids)")
    suspend fun discoveryRetainedIds(type: String, category: String, ids: List<Int>): List<SafeCatalogItemEntity>
    @Transaction
    suspend fun commitDiscovery(type: String, category: String, stamp: Long, items: List<CatalogDiscoveryEntity>, completion: DiscoveryBackfillEntity) {
        if (discoveryCategories(type).firstOrNull { it.id == category }?.stamp != stamp) return
        items.chunked(500).forEach { insertDiscovery(it) }
        saveDiscoveryBackfill(completion)
    }

    @Query("""SELECT c.* FROM catalog_discovery m JOIN safe_catalog_items c
        ON c.contentType=m.contentType AND c.itemId=m.itemId AND c.updatedAtMs=m.catalogAtMs
        WHERE m.contentType=:type AND NOT EXISTS (SELECT 1 FROM catalog_discovery other
            JOIN safe_catalog_items oc ON oc.contentType=other.contentType AND oc.itemId=other.itemId AND oc.updatedAtMs=other.catalogAtMs
            WHERE other.contentType=m.contentType AND other.variantKey=m.variantKey AND other.itemId<m.itemId) AND m.releaseSort BETWEEN :minimum AND :today
        ORDER BY m.releaseSort DESC, CASE WHEN m.ratingSource = 'imdb' AND m.ratingVotes >= 1000 THEN COALESCE(m.ratingMilli,0) ELSE 0 END DESC, c.normalizedTitle ASC, c.itemId ASC LIMIT :limit""")
    suspend fun newDiscovery(type: String, minimum: Int, today: Int, limit: Int): List<SafeCatalogItemEntity>

    @Query("""SELECT c.* FROM catalog_discovery m JOIN safe_catalog_items c
        ON c.contentType=m.contentType AND c.itemId=m.itemId AND c.updatedAtMs=m.catalogAtMs
        WHERE m.contentType=:type AND NOT EXISTS (SELECT 1 FROM catalog_discovery other
            JOIN safe_catalog_items oc ON oc.contentType=other.contentType AND oc.itemId=other.itemId AND oc.updatedAtMs=other.catalogAtMs
            WHERE other.contentType=m.contentType AND other.variantKey=m.variantKey AND other.itemId<m.itemId) AND m.ratingSource = 'imdb' AND m.ratingVotes >= 1000 AND m.ratingMilli BETWEEN 7000 AND 10000
        ORDER BY m.ratingMilli DESC, m.ratingVotes DESC, COALESCE(m.releaseSort,0) DESC, c.normalizedTitle ASC, c.itemId ASC LIMIT :limit""")
    suspend fun ratedDiscovery(type: String, limit: Int): List<SafeCatalogItemEntity>

    @Query("""SELECT c.* FROM catalog_discovery m JOIN safe_catalog_items c
        ON c.contentType=m.contentType AND c.itemId=m.itemId AND c.updatedAtMs=m.catalogAtMs
        WHERE m.contentType=:type AND NOT EXISTS (SELECT 1 FROM catalog_discovery other
            JOIN safe_catalog_items oc ON oc.contentType=other.contentType AND oc.itemId=other.itemId AND oc.updatedAtMs=other.catalogAtMs
            WHERE other.contentType=m.contentType AND other.variantKey=m.variantKey AND other.itemId<m.itemId) AND m.addedAtMs BETWEEN 946684800000 AND :now
        ORDER BY m.addedAtMs DESC, c.normalizedTitle ASC, c.itemId ASC LIMIT :limit""")
    suspend fun addedDiscovery(type: String, now: Long, limit: Int): List<SafeCatalogItemEntity>

    @Query("""SELECT c.* FROM catalog_discovery m JOIN safe_catalog_items c
        ON c.contentType=m.contentType AND c.itemId=m.itemId AND c.updatedAtMs=m.catalogAtMs
        WHERE m.contentType=:type AND NOT EXISTS (SELECT 1 FROM catalog_discovery other
            JOIN safe_catalog_items oc ON oc.contentType=other.contentType AND oc.itemId=other.itemId AND oc.updatedAtMs=other.catalogAtMs
            WHERE other.contentType=m.contentType AND other.variantKey=m.variantKey AND other.itemId<m.itemId) AND m.changedAtMs BETWEEN 946684800000 AND :now
        ORDER BY m.changedAtMs DESC, c.normalizedTitle ASC, c.itemId ASC LIMIT :limit""")
    suspend fun updatedDiscovery(type: String, now: Long, limit: Int): List<SafeCatalogItemEntity>

    @Query("""SELECT c.* FROM catalog_discovery m JOIN safe_catalog_items c
        ON c.contentType=m.contentType AND c.itemId=m.itemId AND c.updatedAtMs=m.catalogAtMs
        WHERE m.contentType=:type AND NOT EXISTS (SELECT 1 FROM catalog_discovery other
            JOIN safe_catalog_items oc ON oc.contentType=other.contentType AND oc.itemId=other.itemId AND oc.updatedAtMs=other.catalogAtMs
            WHERE other.contentType=m.contentType AND other.variantKey=m.variantKey AND other.itemId<m.itemId) AND m.genres LIKE '%|' || :genre || '|%'
        ORDER BY CASE WHEN m.ratingSource = 'imdb' AND m.ratingVotes >= 1000 THEN COALESCE(m.ratingMilli,0) ELSE 0 END DESC, COALESCE(m.releaseSort,0) DESC, c.normalizedTitle ASC, c.itemId ASC LIMIT :limit""")
    suspend fun genreDiscovery(type: String, genre: String, limit: Int): List<SafeCatalogItemEntity>

    /** Normal refresh must not erase either last-good type. Check and write in one transaction. */
    @Transaction
    suspend fun replaceLibraryFromRefresh(
        movies: List<SafeCatalogItemEntity>,
        shows: List<SafeCatalogItemEntity>,
        discovery: List<CatalogDiscoveryEntity> = emptyList(),
    ): CatalogRefreshWriteResult {
        val losesMovies = movies.isEmpty() && countForType(SafeCatalogContentTypes.Movie) > 0
        val losesShows = shows.isEmpty() && countForType(SafeCatalogContentTypes.Series) > 0
        if (losesMovies || losesShows) return CatalogRefreshWriteResult.RefusedEmptyCatalog
        replaceLibrary(movies, shows)
        insertDiscovery(discovery)
        for (type in listOf(SafeCatalogContentTypes.Movie, SafeCatalogContentTypes.Series))
            for (category in discoveryCategories(type)) saveDiscoveryBackfill(DiscoveryBackfillEntity(type, category.id, category.stamp, "catalog-import", System.currentTimeMillis(), true))
        return CatalogRefreshWriteResult.Replaced
    }

    @Transaction
    suspend fun replaceLibrary(movies: List<SafeCatalogItemEntity>, shows: List<SafeCatalogItemEntity>) {
        deleteDiscoveryType(SafeCatalogContentTypes.Movie)
        deleteDiscoveryType(SafeCatalogContentTypes.Series)
        deleteType(SafeCatalogContentTypes.Movie)
        deleteType(SafeCatalogContentTypes.Series)
        (movies.asSequence() + shows.asSequence()).chunked(500).forEach { insertAll(it) }
    }

    @Query("SELECT DISTINCT categoryName FROM safe_catalog_items")
    suspend fun catalogCategoryNames(): List<String>

    @Query("DELETE FROM safe_catalog_items WHERE categoryName IN (:names)")
    suspend fun removeCatalogCategories(names: List<String>): Int

    // Two disjoint ranges let SQLite seek the existing typed primary key on every page.
    // Fetch only the identity/title fields used by policy rather than every artwork/catalog field.
    @Query("""SELECT contentType,itemId,title FROM safe_catalog_items WHERE contentType = :type AND itemId > :id
        UNION ALL SELECT contentType,itemId,title FROM safe_catalog_items WHERE contentType > :type
        ORDER BY contentType,itemId LIMIT 200""")
    suspend fun languagePolicyPage(type: String, id: Int): List<CatalogLanguageItem>

    @Query("DELETE FROM safe_catalog_items WHERE contentType = :type AND itemId IN (:ids)")
    suspend fun removeCatalogIds(type: String, ids: List<Int>): Int

    @Query("SELECT * FROM catalog_language_checkpoint WHERE id = 1")
    suspend fun languageCheckpoint(): CatalogLanguageCheckpoint?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveLanguageCheckpoint(value: CatalogLanguageCheckpoint)

    @RawQuery
    suspend fun languageTriggers(query: androidx.sqlite.db.SupportSQLiteQuery): List<CatalogLanguageTrigger>

    @Transaction
    suspend fun applyEnglishCategoryPolicy(): Int {
        // Validate the invalidation mechanism before trusting even a matching revision.
        val triggersValid = CatalogLanguageVerification.hasValidTriggers(languageTriggers(
            androidx.sqlite.db.SimpleSQLiteQuery("SELECT name,sql FROM sqlite_master WHERE type='trigger' AND name IN ('catalog_language_dirty_insert','catalog_language_dirty_update','catalog_language_dirty_delete')")
        ))
        if (triggersValid && languageCheckpoint() == CatalogLanguageCheckpoint(policyRevision = CatalogLanguageVerification.Revision)) return 0
        val filter = com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter()
        var removed = catalogCategoryNames().filter(filter::excludes).chunked(200).sumOf { removeCatalogCategories(it) }
        var type = ""
        var id = -1
        while (true) {
            val page = languagePolicyPage(type,id)
            if (page.isEmpty()) break
            page.filter { com.atlas.atlastv.playlist.filter.EnglishCatalogPolicy.excludesTitle(it.title) }
                .groupBy { it.contentType }.forEach { (kind, rows) -> removed += removeCatalogIds(kind,rows.map { it.itemId }) }
            type = page.last().contentType
            id = page.last().itemId
        }
        // The scan, deletions and checkpoint commit share one transaction. Failed/cancelled work
        // cannot bless a partially checked library. Other writers invalidate after this commits.
        if (triggersValid) saveLanguageCheckpoint(CatalogLanguageCheckpoint(policyRevision = CatalogLanguageVerification.Revision))
        return removed
    }

    @Query("""SELECT * FROM safe_catalog_items WHERE contentType = :type
        AND (:query = '' OR normalizedTitle LIKE '%' || :query || '%' OR categoryName LIKE '%' || :query || '%')
        ORDER BY CASE WHEN :query = '' OR TRIM(title) = :exactQuery COLLATE NOCASE THEN 0
            WHEN normalizedTitle LIKE :query || '%' THEN 1
            WHEN normalizedTitle LIKE '%' || :query || '%' THEN 2 ELSE 3 END,
            providerOrder ASC, itemId ASC LIMIT :limit OFFSET :offset""")
    suspend fun searchPage(type: String, query: String, offset: Int, limit: Int, exactQuery: String = query): List<SafeCatalogItemEntity>

    @Query("""SELECT (SELECT COUNT(*) FROM safe_catalog_items c WHERE c.contentType = selected.contentType
        AND (:query = '' OR c.normalizedTitle LIKE '%' || :query || '%' OR c.categoryName LIKE '%' || :query || '%')
        AND (CASE WHEN :query = '' OR TRIM(c.title) = :exactQuery COLLATE NOCASE THEN 0
                WHEN c.normalizedTitle LIKE :query || '%' THEN 1
                WHEN c.normalizedTitle LIKE '%' || :query || '%' THEN 2 ELSE 3 END,
             c.providerOrder, c.itemId) <
            (CASE WHEN :query = '' OR TRIM(selected.title) = :exactQuery COLLATE NOCASE THEN 0
                WHEN selected.normalizedTitle LIKE :query || '%' THEN 1
                WHEN selected.normalizedTitle LIKE '%' || :query || '%' THEN 2 ELSE 3 END,
             selected.providerOrder, selected.itemId))
        FROM safe_catalog_items selected WHERE selected.contentType = :type AND selected.itemId = :id
        AND (:query = '' OR selected.normalizedTitle LIKE '%' || :query || '%' OR selected.categoryName LIKE '%' || :query || '%')""")
    suspend fun searchOffset(type: String, query: String, id: Int, exactQuery: String = query): Int?

    @Transaction
    suspend fun searchWindow(type: String, query: String, index: Int, selectedId: Int? = null, exactQuery: String = query): SafeCatalogWindow {
        val total = countSearchItemsForType(type, query)
        val anchor = (selectedId?.let { searchOffset(type, query, it, exactQuery) } ?: index).coerceIn(0, (total - 1).coerceAtLeast(0))
        val offset = maxOf(0, anchor / SafeCatalogWindow.PageSize - 2) * SafeCatalogWindow.PageSize
        return SafeCatalogWindow(total, offset, anchor, searchPage(type, query, offset, SafeCatalogWindow.MaxItems, exactQuery))
    }

    @Query("SELECT * FROM safe_catalog_items WHERE contentType = :contentType ORDER BY providerOrder ASC, itemId ASC LIMIT :limit OFFSET :offset")
    suspend fun pageForType(contentType: String, offset: Int, limit: Int): List<SafeCatalogItemEntity>

    @Query("""SELECT (SELECT COUNT(*) FROM safe_catalog_items c WHERE c.contentType = selected.contentType AND
        (c.providerOrder < selected.providerOrder OR (c.providerOrder = selected.providerOrder AND c.itemId < selected.itemId)))
        FROM safe_catalog_items selected WHERE selected.contentType = :contentType AND selected.itemId = :itemId""")
    suspend fun offsetForItem(contentType: String, itemId: Int): Int?

    @Transaction
    suspend fun catalogWindow(type: String, index: Int, selectedId: Int? = null): SafeCatalogWindow {
        val total = countForType(type)
        val anchor = (selectedId?.let { offsetForItem(type, it) } ?: index).coerceIn(0, (total - 1).coerceAtLeast(0))
        val offset = ((anchor / SafeCatalogWindow.PageSize - 2).coerceAtLeast(0)) * SafeCatalogWindow.PageSize
        return SafeCatalogWindow(total, offset, anchor, pageForType(type, offset, SafeCatalogWindow.MaxItems))
    }

    suspend fun movieWindow(index: Int, selectedId: Int? = null): SafeCatalogWindow =
        catalogWindow(SafeCatalogContentTypes.Movie, index, selectedId)

    @Query("SELECT COUNT(*) FROM safe_catalog_items WHERE contentType = :type AND categoryName = :category")
    suspend fun countItemsInCategory(type: String, category: String): Int

    @Query("""SELECT * FROM safe_catalog_items WHERE contentType = :type AND categoryName = :category
        ORDER BY providerOrder ASC, itemId ASC LIMIT :limit OFFSET :offset""")
    suspend fun categoryPage(type: String, category: String, offset: Int, limit: Int): List<SafeCatalogItemEntity>

    @Query("""SELECT (SELECT COUNT(*) FROM safe_catalog_items c WHERE c.contentType = :type AND c.categoryName = :category
        AND (c.providerOrder < selected.providerOrder OR (c.providerOrder = selected.providerOrder AND c.itemId < selected.itemId)))
        FROM safe_catalog_items selected WHERE selected.contentType = :type AND selected.categoryName = :category AND selected.itemId = :id""")
    suspend fun categoryOffset(type: String, category: String, id: Int): Int?

    @Transaction
    suspend fun categoryWindow(type: String, category: String, index: Int, selectedId: Int? = null): SafeCatalogWindow {
        val total = countItemsInCategory(type, category)
        val anchor = (selectedId?.let { categoryOffset(type, category, it) } ?: index).coerceIn(0, (total - 1).coerceAtLeast(0))
        val offset = maxOf(0, anchor / SafeCatalogWindow.PageSize - 2) * SafeCatalogWindow.PageSize
        return SafeCatalogWindow(total, offset, anchor, categoryPage(type, category, offset, SafeCatalogWindow.MaxItems))
    }

    // Lightweight names/counts only; no category or one-title cap, no catalog item/artwork materialization.
    @Query("""SELECT categoryName AS name, COUNT(*) AS itemCount FROM safe_catalog_items WHERE contentType = :type
        GROUP BY categoryName ORDER BY categoryName COLLATE NOCASE ASC, categoryName ASC""")
    suspend fun allCategories(type: String): List<SafeCatalogCategorySummary>

    suspend fun countMoviesInCategory(category: String) = countItemsInCategory(SafeCatalogContentTypes.Movie, category)
    suspend fun movieCategoryPage(category: String, offset: Int, limit: Int) = categoryPage(SafeCatalogContentTypes.Movie, category, offset, limit)
    suspend fun movieCategoryOffset(category: String, id: Int) = categoryOffset(SafeCatalogContentTypes.Movie, category, id)
    suspend fun movieCategoryWindow(category: String, index: Int, selectedId: Int? = null) = categoryWindow(SafeCatalogContentTypes.Movie, category, index, selectedId)
    suspend fun allMovieCategories() = allCategories(SafeCatalogContentTypes.Movie)
    suspend fun seriesCategoryPage(category: String, offset: Int, limit: Int) = categoryPage(SafeCatalogContentTypes.Series, category, offset, limit)
    suspend fun seriesCategoryWindow(category: String, index: Int, selectedId: Int? = null) = categoryWindow(SafeCatalogContentTypes.Series, category, index, selectedId)
    suspend fun allSeriesCategories() = allCategories(SafeCatalogContentTypes.Series)

    @Transaction
    suspend fun seedLegacySeriesIfEmpty(items: List<SafeCatalogItemEntity>) {
        if (countForType(SafeCatalogContentTypes.Series) == 0) insertAll(items)
    }

    @Transaction
    suspend fun seedLegacyMoviesIfEmpty(items: List<SafeCatalogItemEntity>) {
        if (countForType(SafeCatalogContentTypes.Movie) == 0) insertAll(items)
    }

    @Query(
        """
        SELECT * FROM safe_catalog_items
        WHERE contentType = :contentType
        ORDER BY providerOrder ASC, itemId ASC
        """,
    )
    suspend fun itemsForType(contentType: String): List<SafeCatalogItemEntity>

    @Query(
        """
        SELECT * FROM safe_catalog_items
        WHERE contentType = :contentType
        ORDER BY providerOrder ASC, itemId ASC
        LIMIT :limit
        """,
    )
    suspend fun itemsForType(contentType: String, limit: Int): List<SafeCatalogItemEntity>

    @Query(
        """
        SELECT * FROM safe_catalog_items
        WHERE contentType = :contentType
        ORDER BY providerOrder ASC, itemId ASC
        LIMIT 1 OFFSET :offset
        """,
    )
    suspend fun itemForTypeAtOffset(contentType: String, offset: Int): SafeCatalogItemEntity?

    @Query(
        """
        SELECT * FROM safe_catalog_items
        WHERE contentType = :contentType
        ORDER BY title COLLATE NOCASE ASC, itemId ASC
        LIMIT :limit
        """,
    )
    suspend fun itemsForTypeTitle(contentType: String, limit: Int): List<SafeCatalogItemEntity>

    @Query(
        """
        SELECT * FROM safe_catalog_items
        WHERE contentType = :contentType
        ORDER BY categoryName COLLATE NOCASE ASC, title COLLATE NOCASE ASC, itemId ASC
        LIMIT :limit
        """,
    )
    suspend fun itemsForTypeCategoryThenTitle(contentType: String, limit: Int): List<SafeCatalogItemEntity>

    @Query(
        """
        SELECT * FROM safe_catalog_items
        WHERE contentType = :contentType
        ORDER BY addedAtMs DESC, providerOrder DESC, itemId DESC
        LIMIT :limit
        """,
    )
    suspend fun recentlyAddedItemsForType(contentType: String, limit: Int): List<SafeCatalogItemEntity>

    @Query(
        """
        SELECT * FROM safe_catalog_items
        WHERE contentType = :contentType AND categoryName = :categoryName
        ORDER BY providerOrder ASC, itemId ASC
        LIMIT :limit
        """,
    )
    suspend fun itemsForCategory(contentType: String, categoryName: String, limit: Int): List<SafeCatalogItemEntity>

    @Query(
        """
        SELECT * FROM safe_catalog_items
        WHERE contentType = :contentType AND categoryName = :categoryName
        ORDER BY title COLLATE NOCASE ASC, itemId ASC
        LIMIT :limit
        """,
    )
    suspend fun itemsForCategoryTitle(contentType: String, categoryName: String, limit: Int): List<SafeCatalogItemEntity>

    @Query(
        """
        SELECT * FROM safe_catalog_items
        WHERE contentType = :contentType AND categoryName = :categoryName
        ORDER BY categoryName COLLATE NOCASE ASC, title COLLATE NOCASE ASC, itemId ASC
        LIMIT :limit
        """,
    )
    suspend fun itemsForCategoryThenTitle(contentType: String, categoryName: String, limit: Int): List<SafeCatalogItemEntity>

    @Query(
        """
        SELECT * FROM safe_catalog_items
        WHERE contentType = :contentType AND itemId IN (:itemIds)
        ORDER BY providerOrder ASC, itemId ASC
        LIMIT :limit
        """,
    )
    suspend fun itemsByIds(contentType: String, itemIds: List<Int>, limit: Int): List<SafeCatalogItemEntity>

    @Query(
        """
        SELECT * FROM safe_catalog_items
        WHERE contentType = :contentType
            AND (:normalizedQuery = '' OR normalizedTitle LIKE '%' || :normalizedQuery || '%' OR categoryName LIKE '%' || :normalizedQuery || '%')
        ORDER BY CASE WHEN :normalizedQuery = '' OR TRIM(title) = :exactQuery COLLATE NOCASE THEN 0
            WHEN normalizedTitle LIKE :normalizedQuery || '%' THEN 1
            WHEN normalizedTitle LIKE '%' || :normalizedQuery || '%' THEN 2 ELSE 3 END,
            providerOrder ASC, itemId ASC
        LIMIT :limit
        """,
    )
    suspend fun searchItemsForType(
        contentType: String,
        normalizedQuery: String,
        limit: Int,
        exactQuery: String = normalizedQuery,
    ): List<SafeCatalogItemEntity>

    @Query(
        """
        SELECT COUNT(*) FROM safe_catalog_items
        WHERE contentType = :contentType
            AND (:normalizedQuery = '' OR normalizedTitle LIKE '%' || :normalizedQuery || '%' OR categoryName LIKE '%' || :normalizedQuery || '%')
        """,
    )
    suspend fun countSearchItemsForType(contentType: String, normalizedQuery: String): Int

    @Query(
        """
        SELECT categoryName AS name, COUNT(*) AS itemCount
        FROM safe_catalog_items
        WHERE contentType = :contentType
        GROUP BY categoryName
        HAVING itemCount >= :minItems
        ORDER BY categoryName COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun categorySummariesForType(contentType: String, minItems: Int, limit: Int): List<SafeCatalogCategorySummary>

    @Query(
        """
        SELECT categoryName AS name, COUNT(*) AS itemCount
        FROM safe_catalog_items
        WHERE contentType = :contentType
        GROUP BY categoryName
        ORDER BY categoryName COLLATE NOCASE ASC
        LIMIT :limit
        """,
    )
    suspend fun allCategorySummariesForType(contentType: String, limit: Int): List<SafeCatalogCategorySummary>

    @Query("DELETE FROM safe_catalog_items WHERE contentType = :contentType")
    suspend fun deleteType(contentType: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SafeCatalogItemEntity>)

    @Query("DELETE FROM safe_catalog_items")
    suspend fun deleteAll()

    @Transaction
    suspend fun replaceType(contentType: String, items: List<SafeCatalogItemEntity>) {
        deleteType(contentType)
        insertAll(items.filter { it.contentType == contentType })
    }

    @Transaction
    suspend fun seedLegacyCatalogIfEmpty(items: List<SafeCatalogItemEntity>) {
        if (countForType(SafeCatalogContentTypes.Movie) == 0 && countForType(SafeCatalogContentTypes.Series) == 0) {
            insertAll(items)
        }
    }

    @Query("SELECT COUNT(*) FROM safe_catalog_items WHERE contentType = :contentType")
    suspend fun countForType(contentType: String): Int

    @Query("SELECT MAX(updatedAtMs) FROM safe_catalog_items WHERE contentType = :contentType")
    suspend fun newestUpdatedAtMs(contentType: String): Long?
}

data class SafeCatalogCategorySummary(
    val name: String,
    val itemCount: Int,
)

data class SafeCatalogWindow(val total: Int, val offset: Int, val anchor: Int, val items: List<SafeCatalogItemEntity>) {
    companion object {
        const val PageSize = 40
        const val MaxItems = 200
    }
}

data class DiscoveryCategoryStamp(val id: String, val stamp: Long)

package com.atlas.atlastv.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface CatalogTitleRepairDao {
    @Query("""SELECT * FROM safe_catalog_items WHERE title GLOB '([12][0-9][0-9][0-9])*'
        AND categoryId IS NOT NULL ORDER BY CASE
        WHEN contentType='series' AND itemId IN (SELECT seriesId FROM series_watchlist_items) THEN 0
        WHEN contentType='movie' AND itemId IN (SELECT streamId FROM movie_watchlist_items) THEN 0
        ELSE 1 END, contentType, itemId LIMIT 1024""")
    suspend fun candidates(): List<SafeCatalogItemEntity>

    @Query("""UPDATE safe_catalog_items SET title=:corrected WHERE contentType=:type AND itemId=:id
        AND title=:previous AND normalizedTitle=:aliases AND updatedAtMs=:stamp AND categoryId=:category""")
    suspend fun repairCatalog(type: String, id: Int, previous: String, aliases: String, stamp: Long, category: String, corrected: String): Int

    @Query("UPDATE series_watchlist_items SET title=:corrected WHERE seriesId=:id AND title=:previous")
    suspend fun repairShowLabel(id: Int, previous: String, corrected: String)
    @Query("UPDATE movie_watchlist_items SET title=:corrected WHERE streamId=:id AND title=:previous")
    suspend fun repairMovieLabel(id: Int, previous: String, corrected: String)
    @Query("""UPDATE watch_progress_records SET seriesTitle=:corrected
        WHERE contentType='series_episode' AND seriesId=:id AND seriesTitle=:previous""")
    suspend fun repairEpisodeParentLabel(id: Int, previous: String, corrected: String)
    @Query("""UPDATE watch_progress_records SET title=:corrected
        WHERE contentType='movie' AND streamId=:id AND title=:previous""")
    suspend fun repairMovieProgressLabel(id: Int, previous: String, corrected: String)

    /** Metadata only. A newer import/removal wins; IDs, membership, checkpoints and stamps stay put. */
    @Transaction
    suspend fun repair(row: SafeCatalogItemEntity, corrected: String): Boolean {
        val category = row.categoryId ?: return false
        if (repairCatalog(row.contentType, row.itemId, row.title, row.normalizedTitle, row.updatedAtMs, category, corrected) != 1) return false
        when (row.contentType) {
            SafeCatalogContentTypes.Series -> {
                repairShowLabel(row.itemId, row.title, corrected)
                repairEpisodeParentLabel(row.itemId, row.title, corrected)
            }
            SafeCatalogContentTypes.Movie -> {
                repairMovieLabel(row.itemId, row.title, corrected)
                repairMovieProgressLabel(row.itemId, row.title, corrected)
            }
        }
        return true
    }
}

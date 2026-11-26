package com.atlas.atlastv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface SeriesWatchlistDao {
    @Query("SELECT * FROM series_watchlist_items ORDER BY addedAtMs DESC, seriesId ASC")
    suspend fun items(): List<SeriesWatchlistEntity>

    @Query("DELETE FROM series_watchlist_items")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<SeriesWatchlistEntity>)

    @Query("DELETE FROM series_watchlist_items WHERE seriesId = :id")
    suspend fun remove(id: Int)

    @Query("SELECT * FROM series_watchlist_items WHERE seriesId = :id")
    suspend fun item(id: Int): SeriesWatchlistEntity?

    @Transaction
    suspend fun setSaved(value: SeriesWatchlistEntity, saved: Boolean) {
        if (!saved) remove(value.seriesId)
        else {
            val previous = item(value.seriesId)
            insertAll(listOf(value.copy(addedAtMs = maxOf(value.addedAtMs, (previous?.addedAtMs ?: -1) + 1))))
        }
    }

    @Transaction
    suspend fun replaceAll(items: List<SeriesWatchlistEntity>) {
        clear()
        insertAll(items)
    }

    @Query("""SELECT w.seriesId AS saved_seriesId, w.title AS saved_title, w.categoryName AS saved_categoryName,
        w.addedAtMs AS saved_addedAtMs, c.*
        FROM series_watchlist_items w LEFT JOIN safe_catalog_items c ON c.contentType = 'series' AND c.itemId = w.seriesId
        ORDER BY w.addedAtMs DESC, w.seriesId ASC LIMIT :limit OFFSET :offset""")
    suspend fun page(offset: Int, limit: Int): List<SeriesWatchlistEntry>

    @Query("""SELECT (SELECT COUNT(*) FROM series_watchlist_items w WHERE
        w.addedAtMs > selected.addedAtMs OR (w.addedAtMs = selected.addedAtMs AND w.seriesId < selected.seriesId))
        FROM series_watchlist_items selected WHERE selected.seriesId = :id""")
    suspend fun offset(id: Int): Int?

    @Transaction
    suspend fun window(index: Int, selectedId: Int? = null): SeriesWatchlistWindow {
        val total = count()
        val anchor = (selectedId?.let { offset(it) } ?: index).coerceIn(0, maxOf(0, total - 1))
        val start = maxOf(0, anchor / SafeCatalogWindow.PageSize - 2) * SafeCatalogWindow.PageSize
        return SeriesWatchlistWindow(total, start, anchor, page(start, SafeCatalogWindow.MaxItems))
    }

    @Query("SELECT COUNT(*) FROM series_watchlist_items")
    suspend fun count(): Int
}

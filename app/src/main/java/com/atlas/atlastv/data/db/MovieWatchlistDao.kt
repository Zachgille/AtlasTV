package com.atlas.atlastv.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface MovieWatchlistDao {
    @Query("SELECT * FROM movie_watchlist_items ORDER BY addedAtMs DESC, streamId ASC")
    suspend fun items(): List<MovieWatchlistEntity>

    @Query("DELETE FROM movie_watchlist_items")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<MovieWatchlistEntity>)

    @Query("DELETE FROM movie_watchlist_items WHERE streamId = :id")
    suspend fun remove(id: Int)

    @Query("SELECT * FROM movie_watchlist_items WHERE streamId = :id")
    suspend fun item(id: Int): MovieWatchlistEntity?

    @Transaction
    suspend fun setSaved(value: MovieWatchlistEntity, saved: Boolean) {
        if (!saved) remove(value.streamId)
        else {
            val previous = item(value.streamId)
            insertAll(listOf(value.copy(addedAtMs = maxOf(value.addedAtMs, (previous?.addedAtMs ?: -1) + 1))))
        }
    }

    @Transaction
    suspend fun replaceAll(items: List<MovieWatchlistEntity>) {
        clear()
        insertAll(items)
    }

    @Query("""SELECT w.streamId AS saved_streamId, w.title AS saved_title, w.categoryName AS saved_categoryName,
        w.containerExtension AS saved_containerExtension, w.addedAtMs AS saved_addedAtMs, c.*
        FROM movie_watchlist_items w LEFT JOIN safe_catalog_items c ON c.contentType = 'movie' AND c.itemId = w.streamId
        ORDER BY w.addedAtMs DESC, w.streamId ASC LIMIT :limit OFFSET :offset""")
    suspend fun page(offset: Int, limit: Int): List<MovieWatchlistEntry>

    @Query("""SELECT (SELECT COUNT(*) FROM movie_watchlist_items w WHERE
        w.addedAtMs > selected.addedAtMs OR (w.addedAtMs = selected.addedAtMs AND w.streamId < selected.streamId))
        FROM movie_watchlist_items selected WHERE selected.streamId = :id""")
    suspend fun offset(id: Int): Int?

    @Transaction
    suspend fun window(index: Int, selectedId: Int? = null): MovieWatchlistWindow {
        val total = count()
        val anchor = (selectedId?.let { offset(it) } ?: index).coerceIn(0, maxOf(0, total - 1))
        val start = maxOf(0, anchor / SafeCatalogWindow.PageSize - 2) * SafeCatalogWindow.PageSize
        return MovieWatchlistWindow(total, start, anchor, page(start, SafeCatalogWindow.MaxItems))
    }

    @Query("SELECT COUNT(*) FROM movie_watchlist_items")
    suspend fun count(): Int
}

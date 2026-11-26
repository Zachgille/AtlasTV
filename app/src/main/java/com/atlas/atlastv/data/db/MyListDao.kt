package com.atlas.atlastv.data.db

import androidx.room.*
import com.atlas.atlastv.ui.mylist.*

private const val SavedTitles = """WITH saved AS (
    SELECT 'movie' AS type, streamId AS id, title, categoryName, containerExtension, addedAtMs FROM movie_watchlist_items
    UNION ALL SELECT 'series' AS type, seriesId AS id, title, categoryName, NULL AS containerExtension, addedAtMs FROM series_watchlist_items
) """

data class MyListSavedSnapshot(val type: String, val id: Int, val title: String?, val categoryName: String?, val containerExtension: String?, val addedAtMs: Long)
data class MyListEntry(@Embedded(prefix = "saved_") val saved: MyListSavedSnapshot, @Embedded val catalog: SafeCatalogItemEntity?) {
    fun toItem(): MyListItem = when (saved.type) {
        "movie" -> MyListItem.Movie(MovieWatchlistEntry(MovieWatchlistEntity(saved.id, saved.title, saved.categoryName, saved.containerExtension, saved.addedAtMs), catalog).toMovieItem())
        "series" -> MyListItem.Show(SeriesWatchlistEntry(SeriesWatchlistEntity(saved.id, saved.title, saved.categoryName, saved.addedAtMs), catalog).toSeriesItem())
        else -> error("Unknown saved content type")
    }
}
data class MyListWindow(val total: Int, val offset: Int, val anchor: Int, val items: List<MyListEntry>)

@Dao
interface MyListDao {
    @Query(SavedTitles + "SELECT COUNT(*) FROM saved WHERE :filter = '' OR type = :filter")
    suspend fun count(filter: String = ""): Int

    @Query(SavedTitles + """SELECT s.type AS saved_type, s.id AS saved_id, s.title AS saved_title,
        s.categoryName AS saved_categoryName, s.containerExtension AS saved_containerExtension, s.addedAtMs AS saved_addedAtMs, c.*
        FROM saved s LEFT JOIN safe_catalog_items c ON c.contentType = s.type AND c.itemId = s.id
        WHERE :filter = '' OR s.type = :filter ORDER BY s.addedAtMs DESC, s.type ASC, s.id ASC LIMIT :limit OFFSET :offset""")
    suspend fun page(filter: String, offset: Int, limit: Int): List<MyListEntry>

    @Query(SavedTitles + """SELECT (SELECT COUNT(*) FROM saved s WHERE (:filter = '' OR s.type = :filter) AND
        (s.addedAtMs > selected.addedAtMs OR (s.addedAtMs = selected.addedAtMs AND
        (s.type < selected.type OR (s.type = selected.type AND s.id < selected.id)))))
        FROM saved selected WHERE selected.type = :type AND selected.id = :id AND (:filter = '' OR selected.type = :filter)""")
    suspend fun offset(filter: String, type: String, id: Int): Int?

    @Transaction
    suspend fun window(filter: String, index: Int, selected: MyListKey?): MyListWindow {
        val total = count(filter)
        val anchor = (selected?.let { offset(filter, it.type.storageKey, it.id) } ?: index).coerceIn(0, maxOf(0, total - 1))
        val start = maxOf(0, anchor / SafeCatalogWindow.PageSize - 2) * SafeCatalogWindow.PageSize
        return MyListWindow(total, start, anchor, page(filter, start, SafeCatalogWindow.MaxItems))
    }
}

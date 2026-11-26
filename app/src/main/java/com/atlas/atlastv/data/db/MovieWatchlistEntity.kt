package com.atlas.atlastv.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.atlas.atlastv.ui.movies.MovieItem

@Entity(tableName = "movie_watchlist_items")
data class MovieWatchlistEntity(
    @PrimaryKey val streamId: Int,
    val title: String?,
    val categoryName: String?,
    val containerExtension: String?,
    val addedAtMs: Long,
)

fun MovieItem.toMovieWatchlistEntity(addedAtMs: Long): MovieWatchlistEntity =
    MovieWatchlistEntity(
        streamId = streamId,
        title = title.safeWatchlistText(),
        categoryName = categoryName.safeWatchlistText(),
        containerExtension = containerExtension.trim().trimStart('.').takeIf { it.isNotBlank() },
        addedAtMs = addedAtMs,
    )

fun movieWatchlistEntityFromSnapshot(
    streamId: Int,
    title: String?,
    categoryName: String?,
    containerExtension: String?,
    addedAtMs: Long,
): MovieWatchlistEntity =
    MovieWatchlistEntity(
        streamId = streamId,
        title = title?.safeWatchlistText(),
        categoryName = categoryName?.safeWatchlistText(),
        containerExtension = containerExtension?.trim()?.trimStart('.')?.takeIf { it.isNotBlank() },
        addedAtMs = addedAtMs,
    )

private fun String.safeWatchlistText(): String =
    trim().let { value ->
        when {
            value.isBlank() -> ""
            value.contains("://") -> ""
            value.contains("/live/", ignoreCase = true) -> ""
            value.contains("/movie/", ignoreCase = true) -> ""
            value.contains("/series/", ignoreCase = true) -> ""
            else -> value.take(MaxWatchlistTextLength)
        }
    }

private const val MaxWatchlistTextLength = 240

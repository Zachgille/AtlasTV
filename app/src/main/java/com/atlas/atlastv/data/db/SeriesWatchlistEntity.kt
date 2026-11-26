package com.atlas.atlastv.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.atlas.atlastv.ui.series.SeriesItem

@Entity(tableName = "series_watchlist_items")
data class SeriesWatchlistEntity(
    @PrimaryKey val seriesId: Int,
    val title: String?,
    val categoryName: String?,
    val addedAtMs: Long,
)

fun SeriesItem.toSeriesWatchlistEntity(addedAtMs: Long): SeriesWatchlistEntity =
    SeriesWatchlistEntity(
        seriesId = seriesId,
        title = name.safeSeriesWatchlistText(),
        categoryName = categoryName.safeSeriesWatchlistText(),
        addedAtMs = addedAtMs,
    )

fun seriesWatchlistEntityFromSnapshot(
    seriesId: Int,
    title: String?,
    categoryName: String?,
    addedAtMs: Long,
): SeriesWatchlistEntity =
    SeriesWatchlistEntity(
        seriesId = seriesId,
        title = title?.safeSeriesWatchlistText(),
        categoryName = categoryName?.safeSeriesWatchlistText(),
        addedAtMs = addedAtMs,
    )

private fun String.safeSeriesWatchlistText(): String =
    trim().let { value ->
        when {
            value.isBlank() -> ""
            value.contains("://") -> ""
            value.contains("/live/", ignoreCase = true) -> ""
            value.contains("/movie/", ignoreCase = true) -> ""
            value.contains("/series/", ignoreCase = true) -> ""
            else -> value.take(MaxSeriesWatchlistTextLength)
        }
    }

private const val MaxSeriesWatchlistTextLength = 240

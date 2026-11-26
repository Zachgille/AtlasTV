package com.atlas.atlastv.ui.browse

import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.resumeLabel
import com.atlas.atlastv.playback.WatchProgressPolicy
import com.atlas.atlastv.ui.catalog.CardWatchProgress
import com.atlas.atlastv.ui.catalog.cardWatchProgress
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.movies.searchMovies
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.series.searchSeries
import com.atlas.atlastv.ui.series.SeriesSortMode

data class BrowseRow<T>(
    val id: String,
    val title: String,
    val items: List<T>,
    val categoryKey: String? = null,
)

data class BrowseProgress(
    val label: String?,
    val percent: Int?,
    val cardProgress: CardWatchProgress? = null,
)

data class BrowseSearchResult<T>(
    val query: String,
    val totalCount: Int,
    val rows: List<BrowseRow<T>>,
)

fun movieBrowseRows(
    movies: List<MovieItem>,
    watchlistIds: Set<Int>,
    progressRecords: Map<Int, WatchProgressRecord>,
    maxRows: Int = 12,
    maxItemsPerRow: Int = 18,
): List<BrowseRow<MovieItem>> {
    val rows = mutableListOf<BrowseRow<MovieItem>>()
    movies.filter { it.streamId in watchlistIds }
        .take(maxItemsPerRow)
        .takeIf { it.isNotEmpty() }
        ?.let { rows += BrowseRow("movies_watchlist", "Watchlist", it) }
    movies.filter { progressRecords[it.streamId]?.let(com.atlas.atlastv.playback.WatchProgressPolicy::isResumeEligible) == true }
        .take(maxItemsPerRow)
        .takeIf { it.isNotEmpty() }
        ?.let { rows += BrowseRow("movies_continue", "Continue Watching Movies", it) }
    movies.take(maxItemsPerRow)
        .takeIf { it.isNotEmpty() }
        ?.let { rows += BrowseRow("movies_popular", "Browse Movies", it) }
    movies.drop(if (movies.size > 1) 1 else 0).take(maxItemsPerRow)
        .takeIf { it.isNotEmpty() }
        ?.let { rows += BrowseRow("movies_recent", "More Movies", it) }
    rows += categoryRows(
        items = movies,
        category = { it.categoryName },
        rowIdPrefix = "movies_category",
        maxItemsPerRow = maxItemsPerRow,
    )
    return rows.distinctBy { it.id }.filter { it.items.isNotEmpty() }.take(maxRows)
}

fun movieSearchBrowseRows(
    movies: List<MovieItem>,
    query: String,
    maxRows: Int = 7,
    maxItemsPerRow: Int = 18,
): BrowseSearchResult<MovieItem> {
    val cleanedQuery = query.trim()
    val matches = movies.searchMovies(cleanedQuery)
    return BrowseSearchResult(
        query = cleanedQuery,
        totalCount = matches.size,
        rows = searchRows(
            rowIdPrefix = "movies_search",
            rowTitle = "Search Results",
            items = matches,
            maxRows = maxRows,
            maxItemsPerRow = maxItemsPerRow,
        ),
    )
}

fun seriesBrowseRows(
    series: List<SeriesItem>,
    progressRecords: Map<Int, WatchProgressRecord>,
    watchlistIds: Set<Int> = emptySet(),
    maxRows: Int = 12,
    maxItemsPerRow: Int = 18,
): List<BrowseRow<SeriesItem>> {
    val rows = mutableListOf<BrowseRow<SeriesItem>>()
    series.filter { it.seriesId in watchlistIds }
        .take(maxItemsPerRow)
        .takeIf { it.isNotEmpty() }
        ?.let { rows += BrowseRow("series_watchlist", "Watchlist", it) }
    val continueSeriesIds = progressRecords.values.filter(com.atlas.atlastv.playback.WatchProgressPolicy::isResumeEligible).mapNotNull { it.seriesId }.toSet()
    series.filter { it.seriesId in continueSeriesIds }
        .take(maxItemsPerRow)
        .takeIf { it.isNotEmpty() }
        ?.let { rows += BrowseRow("series_continue", "Continue Watching Shows", it) }
    series.take(maxItemsPerRow)
        .takeIf { it.isNotEmpty() }
        ?.let { rows += BrowseRow("series_popular", "Browse Shows", it) }
    series.drop(if (series.size > 1) 1 else 0).take(maxItemsPerRow)
        .takeIf { it.isNotEmpty() }
        ?.let { rows += BrowseRow("series_recent", "More Shows", it) }
    rows += categoryRows(
        items = series,
        category = { it.categoryName },
        rowIdPrefix = "series_category",
        maxItemsPerRow = maxItemsPerRow,
    )
    return rows.distinctBy { it.id }.filter { it.items.isNotEmpty() }.take(maxRows)
}

fun seriesSearchBrowseRows(
    series: List<SeriesItem>,
    query: String,
    maxRows: Int = 7,
    maxItemsPerRow: Int = 18,
): BrowseSearchResult<SeriesItem> {
    val cleanedQuery = query.trim()
    val matches = series.searchSeries(cleanedQuery, SeriesSortMode.Provider)
    return BrowseSearchResult(
        query = cleanedQuery,
        totalCount = matches.size,
        rows = searchRows(
            rowIdPrefix = "series_search",
            rowTitle = "Search Results",
            items = matches,
            maxRows = maxRows,
            maxItemsPerRow = maxItemsPerRow,
        ),
    )
}

fun progressForMovie(movie: MovieItem, records: Map<Int, WatchProgressRecord>): BrowseProgress {
    val record = records[movie.streamId]?.takeIf { it.contentType == WatchProgressContentType.Movie }
        ?: return BrowseProgress(null, null)
    val duration = record.durationMs
    val percent = if (duration != null && duration > 0L) {
        ((record.positionMs.toDouble() / duration.toDouble()) * 100.0).toInt().coerceIn(0, 100)
    } else {
        null
    }
    return BrowseProgress(record.resumeLabel(), percent, record.cardWatchProgress())
}

fun progressForShow(series: SeriesItem, records: Map<Int, WatchProgressRecord>): CardWatchProgress? =
    records.values.filter { it.contentType == WatchProgressContentType.SeriesEpisode && it.seriesId == series.seriesId && WatchProgressPolicy.isResumeEligible(it) }
        .maxWithOrNull(compareBy<WatchProgressRecord> { it.updatedAtMs }.thenBy { it.streamId })?.cardWatchProgress()

fun cleanBrowseRowTitle(raw: String): String {
    val stripped = raw.trim()
        .replace(Regex("""^[A-Z]{2,4}\s*[-|:]\s*"""), "")
        .replace("_", " ")
        .replace(Regex("""\s+"""), " ")
        .ifBlank { "Uncategorized" }
    val normalized = stripped.lowercase()
    val preferred = when {
        normalized.contains("new release") -> "New Releases"
        normalized == "english series" -> "English Series"
        normalized == "4k series" -> "4K Series"
        normalized == "sci fi" || normalized == "sci-fi" || normalized == "scifi" -> "Sci-Fi"
        else -> stripped.split(" ").joinToString(" ") { word ->
            if (word.length <= 3 && word.all { it.isUpperCase() || it.isDigit() }) word
            else word.lowercase().replaceFirstChar { it.titlecase() }
        }
    }
    return preferred.take(48)
}

private fun <T> categoryRows(
    items: List<T>,
    category: (T) -> String,
    rowIdPrefix: String,
    maxItemsPerRow: Int,
): List<BrowseRow<T>> =
    items.groupBy { cleanBrowseRowTitle(category(it)) }
        .filter { (_, grouped) -> grouped.size >= 2 }
        .toSortedMap(String.CASE_INSENSITIVE_ORDER)
        .map { (title, grouped) ->
            BrowseRow(
                id = "$rowIdPrefix-${title.lowercase().replace(Regex("""[^a-z0-9]+"""), "-").trim('-')}",
                title = title,
                items = grouped.take(maxItemsPerRow),
            )
        }

private fun <T> searchRows(
    rowIdPrefix: String,
    rowTitle: String,
    items: List<T>,
    maxRows: Int,
    maxItemsPerRow: Int,
): List<BrowseRow<T>> =
    items.chunked(maxItemsPerRow)
        .take(maxRows)
        .mapIndexed { index, chunk ->
            BrowseRow(
                id = "$rowIdPrefix-$index",
                title = if (index == 0) rowTitle else "$rowTitle ${index + 1}",
                items = chunk,
            )
        }

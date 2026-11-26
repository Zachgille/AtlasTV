package com.atlas.atlastv.ui.home

import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressSnapshot
import com.atlas.atlastv.playback.resumeLabel
import com.atlas.atlastv.ui.catalog.cardWatchProgress
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.data.db.toMovieItem
import com.atlas.atlastv.data.db.toSeriesItem
import com.atlas.atlastv.ui.series.SeriesItem

class HomeShellMapper {
    fun map(
        source: String,
        cacheAgeMs: Long?,
        movies: List<MovieItem>,
        series: List<SeriesItem>,
        watchlistStreamIds: Set<Int>,
        watchlistSeriesIds: Set<Int> = emptySet(),
        continueWatching: List<WatchProgressRecord>,
        maxRowItems: Int = MaxRowItems,
        discovery: List<com.atlas.atlastv.data.discovery.DiscoveryCollection>? = null,
    ): HomeShellState.Ready {
        val watchlistMovies = movies
            .filter { it.streamId in watchlistStreamIds }
            .take(maxRowItems)
            .map { it.toHomeMovieCard(prefix = HomeRowIds.Watchlist, status = "Watchlisted") }
        val watchlistShows = series
            .filter { it.seriesId in watchlistSeriesIds }
            .take(maxRowItems)
            .map { it.toHomeShowCard(prefix = HomeRowIds.Watchlist, status = "Watchlisted") }
        val watchlist = (watchlistMovies + watchlistShows).take(maxRowItems)
        val continueCards = WatchProgressSnapshot(continueWatching)
            .continueWatching(limit = maxRowItems)
            .filter { it.contentType != WatchProgressContentType.SeriesEpisode || it.seriesId != null }
            .map { record ->
                val card = record.toHomeContinueCard()
                val artwork = if (record.contentType == WatchProgressContentType.Movie) movies.firstOrNull { it.streamId == record.streamId }?.posterImage
                    else series.firstOrNull { it.seriesId == record.seriesId }?.posterImage
                card.copy(posterImage = artwork ?: card.posterImage, backdropImage = artwork ?: card.backdropImage)
            }
        val popular = buildPopularCards(movies, series, maxRowItems)
        val discoveryRows = discovery?.map { collection ->
            HomeContentRow(collection.id, collection.title, collection.items.take(maxRowItems).map { item ->
                if (item.contentType == com.atlas.atlastv.data.db.SafeCatalogContentTypes.Movie)
                    item.toMovieItem("room://safe_catalog/vod/${item.itemId}").toHomeMovieCard(collection.id, "Open details")
                else item.toSeriesItem().toHomeShowCard(collection.id, "Open details")
            }, collection = com.atlas.atlastv.data.discovery.DiscoveryCollectionSpec.fromId(collection.id))
        }.orEmpty()
        val rows = (listOfNotNull(
            HomeContentRow(HomeRowIds.ContinueWatching, "Continue Watching", continueCards).takeIf { continueCards.isNotEmpty() },
            HomeContentRow(HomeRowIds.Watchlist, "Watchlist", watchlist).takeIf { watchlist.isNotEmpty() },
        ) + discoveryRows + listOfNotNull(
            HomeContentRow(HomeRowIds.Popular, "Discover", popular).takeIf { popular.isNotEmpty() || discoveryRows.isEmpty() },
            HomeContentRow(HomeRowIds.RecentlyAddedMovies, "Browse Movies", movies.take(maxRowItems).map { it.toHomeMovieCard(HomeRowIds.RecentlyAddedMovies, "Open details") }).takeIf { discovery == null },
            HomeContentRow(HomeRowIds.RecentlyAddedShows, "Browse Shows", series.take(maxRowItems).map { it.toHomeShowCard(HomeRowIds.RecentlyAddedShows, "Open details") }).takeIf { discovery == null }
        )).filter { it.cards.isNotEmpty() || it.id == HomeRowIds.Popular }

        val initialFocus = continueCards.firstOrNull()?.id
            ?: discoveryRows.firstOrNull()?.cards?.firstOrNull()?.id
            ?: popular.firstOrNull()?.id
            ?: watchlist.firstOrNull()?.id

        return HomeShellState.Ready(
            source = source,
            cacheAgeMs = cacheAgeMs,
            rows = rows,
            initialFocusCardId = initialFocus,
        )
    }

    private fun buildPopularCards(
        movies: List<MovieItem>,
        series: List<SeriesItem>,
        maxRowItems: Int,
    ): List<HomeCard> {
        val preferredMovies = movies.filter { it.categoryName.isPreferredHomeCategory() }
        val preferredSeries = series.filter { it.categoryName.isPreferredHomeCategory() }
        val sourceMovies = preferredMovies.ifEmpty { movies }
        val sourceSeries = preferredSeries.ifEmpty { series }
        val cards = mutableListOf<HomeCard>()
        val limit = maxOf(sourceMovies.size, sourceSeries.size)
        for (index in 0 until limit) {
            sourceMovies.getOrNull(index)?.let {
                cards += it.toHomeMovieCard(prefix = HomeRowIds.Popular, status = "Open details")
            }
            if (cards.size >= maxRowItems) break
            sourceSeries.getOrNull(index)?.let {
                cards += it.toHomeShowCard(prefix = HomeRowIds.Popular, status = "Open Series")
            }
            if (cards.size >= maxRowItems) break
        }
        return cards
    }

    companion object {
        const val MaxRowItems = 12
    }
}

internal fun MovieItem.toHomeMovieCard(prefix: String, status: String): HomeCard =
    HomeCard(
        id = "$prefix-movie-$streamId",
        title = title.safeHomeText("Movie $streamId"),
        contentType = HomeContentType.Movie,
        categoryLabel = categoryName.safeHomeText("Movie"),
        streamId = streamId,
        extension = containerExtension.trim().trimStart('.').take(12).ifBlank { "mp4" },
        year = title.extractYear(),
        statusText = status,
        posterImage = posterImage,
        backdropImage = posterImage,
    )

internal fun SeriesItem.toHomeShowCard(prefix: String, status: String): HomeCard =
    HomeCard(
        id = "$prefix-show-$seriesId",
        title = name.safeHomeText("Series $seriesId"),
        contentType = HomeContentType.Show,
        categoryLabel = categoryName.safeHomeText("Series"),
        streamId = seriesId,
        seriesId = seriesId,
        year = name.extractYear(),
        statusText = status,
        posterImage = posterImage,
        backdropImage = posterImage,
    )

private fun WatchProgressRecord.toHomeContinueCard(): HomeCard =
    HomeCard(
        id = homeContinueCardId(),
        title = (if (contentType == WatchProgressContentType.SeriesEpisode) seriesTitle ?: title else title).safeHomeText("Continue Watching"),
        contentType = when (contentType) {
            WatchProgressContentType.Movie -> HomeContentType.Movie
            WatchProgressContentType.SeriesEpisode -> HomeContentType.SeriesEpisode
        },
        categoryLabel = categoryName?.safeHomeText(contentType.displayName) ?: contentType.displayName,
        streamId = streamId,
        seriesId = seriesId,
        extension = extension?.trim()?.trimStart('.')?.take(12),
        year = title.extractYear(),
        progressLabel = resumeLabel(),
        cardProgress = cardWatchProgress(),
        statusText = "Press Select to resume",
    )

/** A Show's remote-focus identity survives a change to its latest resumable episode. */
internal fun WatchProgressRecord.homeContinueCardId(): String {
    val parent = seriesId?.takeIf { it > 0 }
    return if (contentType == WatchProgressContentType.SeriesEpisode && parent != null)
        "${HomeRowIds.ContinueWatching}-show-$parent"
    else "${HomeRowIds.ContinueWatching}-${contentType.storageKey}-$streamId"
}

private fun String.isPreferredHomeCategory(): Boolean {
    val normalized = lowercase()
    val noisy = listOf("xxx", "adult", "foreign", "arabic", "hindi", "latino", "24/7")
    if (noisy.any { normalized.contains(it) }) return false
    val preferred = listOf("movie", "series", "drama", "action", "comedy", "family", "documentary", "sci")
    return preferred.any { normalized.contains(it) }
}

private fun String.extractYear(): String? =
    com.atlas.atlastv.ui.catalog.releaseYearLabel(this)

private fun String.safeHomeText(defaultValue: String): String =
    trim()
        .take(120)
        .takeIf { text ->
            text.isNotBlank() &&
                !text.contains("://") &&
                !text.contains("/live/", ignoreCase = true) &&
                !text.contains("/movie/", ignoreCase = true) &&
                !text.contains("/series/", ignoreCase = true)
        }
        ?: defaultValue

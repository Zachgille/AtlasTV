package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressPolicy
import com.atlas.atlastv.playback.resumeLabel

data class MovieDetails(
    val title: String,
    val categoryName: String,
    val streamId: Int,
    val containerExtension: String,
    val selectedIndex: Int,
    val visibleCount: Int,
    val cacheSource: String,
    val cacheAgeMs: Long?,
    val sortModeLabel: String,
    val isWatchlisted: Boolean,
    val resumeLabel: String?,
    val progressPercent: Int?,
    val redactedStreamUrl: String,
    val playbackHint: String = "Press Select to play",
)

fun MoviesCatalogState.Ready.selectedMovieDetails(): MovieDetails? {
    val visible = visibleMovies()
    val selectedIndex = visible.indexOfFirst { it.streamId == selectedMovieStreamId }
    val movie = visible.getOrNull(selectedIndex) ?: return null
    return movieDetails(movie = movie, selectedIndex = selectedIndex, visibleCount = visible.size)
}

fun MoviesCatalogState.Ready.selectedNormalBrowseMovieDetails(): MovieDetails? {
    val selectedIndex = movies.indexOfFirst { it.streamId == selectedMovieStreamId }
    val movie = movies.getOrNull(selectedIndex) ?: return null
    return movieDetails(movie = movie, selectedIndex = selectedIndex, visibleCount = movies.size)
}

fun MoviesCatalogState.Ready.normalBrowseMovieDetailsFor(movie: MovieItem): MovieDetails {
    val selectedIndex = movies.indexOfFirst { it.streamId == movie.streamId }.takeIf { it >= 0 } ?: 0
    val visibleCount = movies.size.takeIf { it > 0 } ?: 1
    return movieDetails(movie = movie, selectedIndex = selectedIndex, visibleCount = visibleCount)
}

/** A category collection, not a similarity or popularity recommendation. */
fun movieCategoryCandidates(movie: MovieItem, candidates: List<MovieItem>, maxItems: Int = 12): List<MovieItem> =
    if (!movie.available || movie.categoryName.isBlank() || maxItems <= 0) emptyList()
    else candidates.asSequence().filter { it.available && it.streamId != movie.streamId && it.categoryName == movie.categoryName }
        .distinctBy { it.streamId }.take(maxItems).toList()

fun MoviesCatalogState.Ready.categoryMoviesFor(movie: MovieItem, maxItems: Int = 12): List<MovieItem> =
    movieCategoryCandidates(movie, movies, maxItems)

private fun MoviesCatalogState.Ready.movieDetails(
    movie: MovieItem,
    selectedIndex: Int,
    visibleCount: Int,
): MovieDetails {
    val progress = watchProgress[movie.streamId]
    val resumeLabel = progress?.takeIf { it.contentType == WatchProgressContentType.Movie && WatchProgressPolicy.isResumeEligible(it) }?.resumeLabel()
    return MovieDetails(
        title = movie.title,
        categoryName = movie.categoryName,
        streamId = movie.streamId,
        containerExtension = movie.containerExtension,
        selectedIndex = selectedIndex + 1,
        visibleCount = visibleCount,
        cacheSource = source,
        cacheAgeMs = cacheAgeMs,
        sortModeLabel = sortMode.displayName,
        isWatchlisted = movie.streamId in watchlistStreamIds,
        resumeLabel = resumeLabel,
        progressPercent = progress?.durationMs?.takeIf { it > 0L }?.let { duration ->
            ((progress.positionMs.toDouble() / duration.toDouble()) * 100).toInt().coerceIn(0, 100)
        },
        redactedStreamUrl = movie.redactedStreamUrl,
        playbackHint = if (resumeLabel != null) "Press Select to resume" else "Press Select to play",
    )
}

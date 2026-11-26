package com.atlas.atlastv.ui.home

import com.atlas.atlastv.ui.media.AtlasImageRef
import com.atlas.atlastv.ui.movies.MovieInfoLoader
import com.atlas.atlastv.ui.series.SeriesEpisodeLoader
import com.atlas.atlastv.xtream.model.XtreamCredentials

data class HomeHeroMetadata(
    val synopsis: String? = null,
    val genre: String? = null,
    val releaseYear: String? = null,
    val durationMinutes: Int? = null,
    val backdrop: AtlasImageRef = AtlasImageRef.Missing,
    val poster: AtlasImageRef = AtlasImageRef.Missing,
)

internal fun HomeCard.heroMetadataKey(): String = when (contentType) {
    HomeContentType.Movie -> "movie-$streamId"
    HomeContentType.Show, HomeContentType.SeriesEpisode -> "show-${seriesId ?: streamId}"
}

internal fun HomeCard.heroActionLabel(): String = when {
    progressLabel != null -> "Resume"
    contentType == HomeContentType.Movie -> "Play"
    else -> "Episodes"
}

class HomeHeroMetadataLoader(
    private val movieLoader: MovieInfoLoader = MovieInfoLoader(),
    private val seriesLoader: SeriesEpisodeLoader = SeriesEpisodeLoader(),
) {
    suspend fun load(credentials: XtreamCredentials, card: HomeCard): HomeHeroMetadata? = when (card.contentType) {
        HomeContentType.Movie -> movieLoader.load(credentials, card.streamId)?.let {
            HomeHeroMetadata(it.synopsis, it.genre, it.releaseYear, it.durationMinutes, it.backdrop, it.poster)
        }
        HomeContentType.Show, HomeContentType.SeriesEpisode -> seriesLoader.loadMetadata(credentials, card.seriesId ?: card.streamId)?.let {
            HomeHeroMetadata(it.synopsis, it.genre, it.releaseYear, backdrop = it.backdrop, poster = it.poster)
        }
    }
}

data class HomeHeroState(val key: String, val metadata: HomeHeroMetadata = HomeHeroMetadata(), val fromSaved: Boolean = false)

enum class HomeReturnTarget { Primary, Details, Card, Collection }
data class HomeReturnFocus(val cardId: String, val target: HomeReturnTarget, val rowId: String? = null)

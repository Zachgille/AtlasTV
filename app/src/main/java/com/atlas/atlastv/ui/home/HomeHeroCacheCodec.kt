package com.atlas.atlastv.ui.home

import com.atlas.atlastv.ui.movies.MovieDetailsCacheCodec
import com.atlas.atlastv.ui.movies.MovieMetadata
import com.atlas.atlastv.ui.movies.hasDetails
import com.atlas.atlastv.ui.movies.withLastGood

/** Preview fields share the explicit, bounded safe Movie DTO; no episode lists or addresses. */
object HomeHeroCacheCodec {
    const val MaxEntryBytes = MovieDetailsCacheCodec.MaxEntryBytes
    fun safe(value: HomeHeroMetadata) = MovieDetailsCacheCodec.safe(value.asMovie()).asHero()
    fun encode(value: HomeHeroMetadata): String? = MovieDetailsCacheCodec.encode(value.asMovie())
    fun decode(value: String): HomeHeroMetadata? = MovieDetailsCacheCodec.decode(value)?.asHero()
}
internal fun HomeHeroMetadata.asMovie() = MovieMetadata(synopsis, genre, releaseYear, durationMinutes, backdrop, poster)
internal fun MovieMetadata.asHero() = HomeHeroMetadata(synopsis, genre, releaseYear, durationMinutes, backdrop, poster)
internal fun HomeHeroMetadata.hasDetails() = asMovie().hasDetails()
internal fun HomeHeroMetadata.withLastGood(previous: HomeHeroMetadata?) = asMovie().withLastGood(previous?.asMovie()).asHero()

/** Episode previews require a real parent identity, never their own video ID. */
internal fun HomeCard.heroCacheIdentity(): String? {
    val id = when (contentType) {
        HomeContentType.Movie -> streamId
        HomeContentType.Show -> seriesId ?: streamId
        HomeContentType.SeriesEpisode -> seriesId ?: return null
    }
    return if (id > 0) "${if (contentType == HomeContentType.Movie) "movie" else "show"}-$id" else null
}

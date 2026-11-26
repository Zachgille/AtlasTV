package com.atlas.atlastv.xtream.model

/** Provider metadata, confined to parsing/loading rather than presentation state. */
data class XtreamMovieInfo(
    val plot: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val durationSeconds: Int? = null,
    val cover: String? = null,
    val backdrops: List<String> = emptyList(),
)

package com.atlas.atlastv.xtream.model

data class XtreamSeriesInfo(
    val episodes: List<XtreamSeriesEpisode>,
    val plot: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val cover: String? = null,
    val backdrops: List<String> = emptyList(),
)

data class XtreamSeriesEpisode(
    val id: Int?,
    val episodeNumber: Int?,
    val title: String?,
    val seasonNumber: Int?,
    val containerExtension: String?,
    val durationMs: Long? = null,
    val thumbnail: String? = null,
)

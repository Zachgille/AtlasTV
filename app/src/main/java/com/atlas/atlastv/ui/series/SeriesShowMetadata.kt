package com.atlas.atlastv.ui.series

import com.atlas.atlastv.ui.media.AtlasImageRef
import com.atlas.atlastv.ui.media.isDisplayFallback

/** Presentation-safe metadata. Provider artwork addresses never enter this snapshot. */
data class SeriesShowMetadata(
    val synopsis: String? = null,
    val genre: String? = null,
    val releaseYear: String? = null,
    val backdrop: AtlasImageRef = AtlasImageRef.Missing,
    val poster: AtlasImageRef = AtlasImageRef.Missing,
)

internal fun cleanShowDescription(value: String?, limit: Int = 4_000): String? = value
    .let { com.atlas.atlastv.ui.catalog.cleanDetailDescription(it, limit) }

internal fun showReleaseYear(value: String?): String? =
    com.atlas.atlastv.ui.catalog.detailReleaseYear(value)

internal fun SeriesShowMetadata.withLastGood(previous: SeriesShowMetadata?): SeriesShowMetadata = copy(
    synopsis = synopsis ?: previous?.synopsis, genre = genre ?: previous?.genre, releaseYear = releaseYear ?: previous?.releaseYear,
    backdrop = if (backdrop.isDisplayFallback()) previous?.backdrop ?: backdrop else backdrop,
    poster = if (poster.isDisplayFallback()) previous?.poster ?: poster else poster,
)

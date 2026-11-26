package com.atlas.atlastv.ui.series

fun SeriesCatalogState.Ready.withRememberedSeries(seriesItem: SeriesItem): SeriesCatalogState.Ready {
    val rowIds = (browseRows + searchResult?.rows.orEmpty()).flatMap { it.items }.map { it.seriesId }.toSet()
    val base = if (browseRows.isNotEmpty()) series.filter { it.seriesId in rowIds } else series
    val seriesItems = if (base.any { it.seriesId == seriesItem.seriesId }) {
        base
    } else {
        listOf(seriesItem) + base
    }.distinctBy { it.seriesId }
    return copy(
        series = seriesItems,
        selectedSeriesId = seriesItem.seriesId,
        episodeState = SeriesEpisodeState.Idle,
        selectedSeasonNumber = null,
        selectedEpisodeId = null,
    )
}

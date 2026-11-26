package com.atlas.atlastv.ui.movies

fun MoviesCatalogState.Ready.withRememberedMovie(movie: MovieItem): MoviesCatalogState.Ready {
    // Deep grid/detail visits must not accumulate an extra catalog snapshot for every visited title.
    val rowIds = (browseRows + searchResult?.rows.orEmpty()).flatMap { it.items }.map { it.streamId }.toSet()
    val base = if (browseRows.isNotEmpty()) movies.filter { it.streamId in rowIds } else movies
    val movies = if (base.any { it.streamId == movie.streamId }) {
        base
    } else {
        listOf(movie) + base
    }
    return copy(
        movies = movies.distinctBy { it.streamId },
        selectedMovieStreamId = movie.streamId,
    )
}

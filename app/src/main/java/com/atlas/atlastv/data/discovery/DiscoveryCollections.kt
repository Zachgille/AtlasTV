package com.atlas.atlastv.data.discovery

import com.atlas.atlastv.data.db.*

data class DiscoveryCollection(val id: String, val title: String, val items: List<SafeCatalogItemEntity>)

enum class DiscoveryCollectionPhase { All, Leading, Genres }

suspend fun SafeCatalogRepository.discoveryCollections(type: String, limit: Int, phase: DiscoveryCollectionPhase = DiscoveryCollectionPhase.All): List<DiscoveryCollection> {
    val movies = type == SafeCatalogContentTypes.Movie
    val label = if (movies) "Movies" else "Shows"
    val prefix = if (movies) "movies" else "series"
    val rows = mutableListOf<DiscoveryCollection>()
    suspend fun row(key: String, title: String, shelf: DiscoveryShelf, genre: String? = null) {
        val items = discovery(type, shelf, limit, genre)
        if (items.isNotEmpty()) rows += DiscoveryCollection("${prefix}_$key", title, items)
    }
    if (phase != DiscoveryCollectionPhase.Genres) {
        row("new", "New $label", DiscoveryShelf.New)
        row("rated", "Highly Rated $label", DiscoveryShelf.HighlyRated)
        row("recent", if (movies) "Recently Added Movies" else "Recently Updated Shows", if (movies) DiscoveryShelf.RecentlyAdded else DiscoveryShelf.RecentlyUpdated)
    }
    // These are verified genre fields, never inferred from platform/codec/language categories.
    if (phase != DiscoveryCollectionPhase.Leading) {
        val shelves = genreCollections(type, limit).groupBy { it.shelfGenre }
        for (genre in DiscoveryCollectionSpec.Genres) {
            val items = shelves[genre].orEmpty().map { it.item }
            if (items.isNotEmpty()) rows += DiscoveryCollection("${prefix}_genre-${genre.lowercase(java.util.Locale.ROOT)}", "$genre $label", items)
        }
    }
    return rows
}

/** Interleave Movies/Shows near the top; personal rows are ordered separately by Home. */
fun List<DiscoveryCollection>.forHome(): List<DiscoveryCollection> = sortedWith(
    compareBy<DiscoveryCollection> { row -> when { row.id.endsWith("_new") -> 0; row.id.endsWith("_rated") -> 1; row.id.endsWith("_recent") -> 2; else -> 3 } }.thenBy { it.id })

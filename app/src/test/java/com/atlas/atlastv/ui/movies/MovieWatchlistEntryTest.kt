package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.data.db.*
import org.junit.Assert.*
import org.junit.Test

class MovieWatchlistEntryTest {
    @Test fun currentCatalogMetadataWinsAndRetainsPlayableArtwork() {
        val current = MovieItem("Current English Movie", "Movies", 1, "mkv", "room://safe")
        val item = MovieWatchlistEntry(MovieWatchlistEntity(1, "Old", "Old", "mp4", 5), current.toSafeCatalogEntity(0, 1)).toMovieItem()
        assertEquals(current.title, item.title); assertEquals("mkv", item.containerExtension); assertTrue(item.available)
    }
    @Test fun unknownOrKnownForeignMissingSnapshotUsesASafeRemovableLabel() {
        for (saved in listOf(MovieWatchlistEntity(1, null, null, null, 1), MovieWatchlistEntity(2, "French Title", "FRENCH MOVIES", "mp4", 1))) {
            val item = MovieWatchlistEntry(saved, null).toMovieItem()
            assertEquals("Unavailable movie", item.title); assertFalse(item.available)
            assertEquals("No longer in your library", item.categoryName); assertEquals("room://unavailable", item.redactedStreamUrl)
        }
    }
    @Test fun unavailableSavedTitlesAreNotSuggestedAsRelatedMovies() {
        val playable = MovieItem("Current", "Movies", 1, "mp4", "room://safe")
        val other = playable.copy(title = "Other", streamId = 2)
        val orphan = playable.copy(title = "Missing", streamId = 3, available = false)
        val ready = MoviesCatalogState.Ready("check", 0, listOf(playable, orphan, other), emptyList())
        assertEquals(listOf(other), ready.categoryMoviesFor(playable))
    }
}

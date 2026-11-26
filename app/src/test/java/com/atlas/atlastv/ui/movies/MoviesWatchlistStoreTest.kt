package com.atlas.atlastv.ui.movies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class MoviesWatchlistStoreTest {
    @Test fun savingBoundedRowsPreservesUnseenTitlesAndExplicitRemoval() {
        val store = MoviesWatchlistStore(tempFile())
        val hidden = MovieItem("Hidden movie", "Movies", 1, "mp4", "room://1")
        val visible = MovieItem("Visible movie", "Movies", 2, "mp4", "room://2")
        store.saveWatchlist(listOf(hidden), setOf(1))
        store.saveWatchlist(listOf(visible), setOf(1, 2, 3))
        assertEquals(setOf(1, 2, 3), store.loadWatchlistIds())
        store.saveWatchlist(listOf(visible), setOf(1, 3))
        assertEquals(setOf(1, 3), store.loadWatchlistIds())
    }

    @Test
    fun persistsOnlySafeMovieSnapshots() {
        val file = tempFile()
        val store = MoviesWatchlistStore(file)
        val movies = listOf(
            MovieItem(
                title = "Fixture Movie",
                categoryName = "Fixture Action",
                streamId = 2001,
                containerExtension = "mkv",
                redactedStreamUrl = "http://REDACTED_HOST/movie/REDACTED/REDACTED/2001.mkv",
            ),
        )

        store.saveWatchlist(movies, setOf(2001))

        val json = file.readText(Charsets.UTF_8)
        assertEquals(setOf(2001), store.loadWatchlistIds())
        assertFalse(json.contains("REDACTED_HOST/movie"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("example.test"))
    }

    @Test
    fun clearRemovesStoredWatchlist() {
        val file = tempFile()
        val store = MoviesWatchlistStore(file)

        store.saveWatchlist(
            listOf(MovieItem("Fixture Movie", "Fixture Action", 2001, "mp4", "fixture://debug_catalog/movie/2001.mp4")),
            setOf(2001),
        )
        store.clear()

        assertFalse(file.exists())
        assertEquals(emptySet<Int>(), store.loadWatchlistIds())
    }

    private fun tempFile(): File =
        File.createTempFile("movies-watchlist", ".json").also { it.deleteOnExit() }
}

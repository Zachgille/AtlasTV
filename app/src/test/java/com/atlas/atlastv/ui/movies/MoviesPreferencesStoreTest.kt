package com.atlas.atlastv.ui.movies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class MoviesPreferencesStoreTest {
    @Test
    fun persistsSafeSortAndContext() {
        val file = tempFile()
        val store = MoviesPreferencesStore(file)

        store.save(
            MoviesPreferences(
                selectedCategoryId = "Fixture Action",
                selectedCategoryName = "Fixture Action",
                selectedMovieStreamId = 3003,
                selectedMovieTitle = "Beta Movie",
                selectedMovieCategory = "Fixture Action",
                sortMode = MoviesSortMode.CategoryThenTitle,
                searchQuery = "beta",
            ),
        )

        val restored = store.load()
        assertEquals("Fixture Action", restored.selectedCategoryId)
        assertEquals("Fixture Action", restored.selectedCategoryName)
        assertEquals(3003, restored.selectedMovieStreamId)
        assertEquals("Beta Movie", restored.selectedMovieTitle)
        assertEquals("Fixture Action", restored.selectedMovieCategory)
        assertEquals(MoviesSortMode.CategoryThenTitle, restored.sortMode)
        assertEquals("beta", restored.searchQuery)
    }

    @Test
    fun savedPreferencesDoNotContainRawUrlsOrCredentials() {
        val file = tempFile()
        val store = MoviesPreferencesStore(file)

        store.save(
            MoviesPreferences(
                selectedCategoryId = "http://example.test/movie/user/pass/3001.mp4",
                selectedCategoryName = "username fake_user",
                selectedMovieStreamId = 3001,
                selectedMovieTitle = "password fake_pass",
                selectedMovieCategory = "token abc",
                sortMode = MoviesSortMode.Title,
                searchQuery = "http://example.test/movie/user/pass/3001.mp4",
            ),
        )

        val json = file.readText(Charsets.UTF_8)
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("/movie/"))

        val restored = store.load()
        assertNull(restored.selectedCategoryId)
        assertNull(restored.selectedCategoryName)
        assertNull(restored.selectedMovieTitle)
        assertNull(restored.selectedMovieCategory)
        assertEquals("", restored.searchQuery)
    }

    @Test
    fun clearRemovesStoredPreferences() {
        val file = tempFile()
        val store = MoviesPreferencesStore(file)

        store.save(MoviesPreferences(selectedMovieStreamId = 3001, sortMode = MoviesSortMode.Title))
        store.clear()

        assertFalse(file.exists())
        assertEquals(MoviesSortMode.Provider, store.load().sortMode)
    }

    private fun tempFile(): File =
        File.createTempFile("movies-preferences", ".json").also { it.deleteOnExit() }
}

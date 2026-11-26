package com.atlas.atlastv.ui.series

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SeriesPreferencesStoreTest {
    @Test
    fun persistsSafeSortAndContext() {
        val file = tempFile()
        val store = SeriesPreferencesStore(file)

        store.save(
            SeriesPreferences(
                selectedCategoryId = "Fixture Series",
                selectedCategoryName = "Fixture Series",
                selectedSeriesId = 3003,
                selectedSeriesTitle = "Beta Show",
                selectedSeriesCategory = "Fixture Series",
                sortMode = SeriesSortMode.CategoryThenTitle,
                searchQuery = "beta",
            ),
        )

        val restored = store.load()
        assertEquals("Fixture Series", restored.selectedCategoryId)
        assertEquals("Fixture Series", restored.selectedCategoryName)
        assertEquals(3003, restored.selectedSeriesId)
        assertEquals("Beta Show", restored.selectedSeriesTitle)
        assertEquals("Fixture Series", restored.selectedSeriesCategory)
        assertEquals(SeriesSortMode.CategoryThenTitle, restored.sortMode)
        assertEquals("beta", restored.searchQuery)
    }

    @Test
    fun savedPreferencesDoNotContainRawUrlsOrCredentials() {
        val file = tempFile()
        val store = SeriesPreferencesStore(file)

        store.save(
            SeriesPreferences(
                selectedCategoryId = "http://example.test/series/user/pass/3001.mp4",
                selectedCategoryName = "username fake_user",
                selectedSeriesId = 3001,
                selectedSeriesTitle = "password fake_pass",
                selectedSeriesCategory = "token abc",
                sortMode = SeriesSortMode.Title,
                searchQuery = "http://example.test/series/user/pass/3001.mp4",
            ),
        )

        val json = file.readText(Charsets.UTF_8)
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("/series/"))

        val restored = store.load()
        assertNull(restored.selectedCategoryId)
        assertNull(restored.selectedCategoryName)
        assertNull(restored.selectedSeriesTitle)
        assertNull(restored.selectedSeriesCategory)
        assertEquals("", restored.searchQuery)
    }

    @Test
    fun clearRemovesStoredPreferences() {
        val file = tempFile()
        val store = SeriesPreferencesStore(file)

        store.save(SeriesPreferences(selectedSeriesId = 3001, sortMode = SeriesSortMode.Title))
        store.clear()

        assertFalse(file.exists())
        assertEquals(SeriesSortMode.Provider, store.load().sortMode)
    }

    private fun tempFile(): File =
        File.createTempFile("series-preferences", ".json").also { it.deleteOnExit() }
}

package com.atlas.atlastv.ui.livetv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveTvPreferencesStoreTest {
    @Test
    fun persistsSortAndLastContext() {
        val file = tempPreferencesFile()
        val store = LiveTvPreferencesStore(file)

        store.save(
            LiveTvPreferences(
                selectedCategoryId = "News",
                selectedChannelStreamId = 101,
                sortMode = LiveTvSortMode.CategoryThenChannel,
                searchQuery = "local",
            ),
        )

        val restored = store.load()
        assertEquals("News", restored.selectedCategoryId)
        assertEquals(101, restored.selectedChannelStreamId)
        assertEquals(LiveTvSortMode.CategoryThenChannel, restored.sortMode)
        assertEquals("local", restored.searchQuery)
    }

    @Test
    fun storedPreferencesContainNoCredentialsOrRawUrls() {
        val file = tempPreferencesFile()
        val store = LiveTvPreferencesStore(file)

        store.save(
            LiveTvPreferences(
                selectedCategoryId = "http://example.test/live/fake_user/fake_pass",
                selectedChannelStreamId = 101,
                sortMode = LiveTvSortMode.ChannelName,
            ),
        )

        val storedJson = file.readText(Charsets.UTF_8)
        assertFalse(storedJson.contains("fake_user"))
        assertFalse(storedJson.contains("fake_pass"))
        assertFalse(storedJson.contains("/live/"))
    }

    @Test
    fun clearRemovesPersistedPreferences() {
        val file = tempPreferencesFile()
        val store = LiveTvPreferencesStore(file)

        store.save(LiveTvPreferences(selectedCategoryId = "News", selectedChannelStreamId = 101))
        store.clear()

        assertEquals(LiveTvPreferences(), store.load())
    }

    private fun tempPreferencesFile(): File =
        kotlin.io.path.createTempFile(prefix = "live-tv-preferences", suffix = ".json").toFile().apply {
            deleteOnExit()
        }
}

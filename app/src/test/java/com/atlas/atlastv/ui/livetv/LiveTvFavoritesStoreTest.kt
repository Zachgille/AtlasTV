package com.atlas.atlastv.ui.livetv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveTvFavoritesStoreTest {
    @Test
    fun serializesAndRestoresFavoriteIdsWithSafeSnapshotsOnly() {
        val file = tempFavoritesFile()
        val store = LiveTvFavoritesStore(file)

        store.saveFavorites(channels(), setOf(101, 102))

        assertEquals(setOf(101, 102), store.loadFavoriteIds())
        val storedJson = file.readText(Charsets.UTF_8)
        assertFalse(storedJson.contains("fake_user"))
        assertFalse(storedJson.contains("fake_pass"))
        assertFalse(storedJson.contains("example.test"))
        assertFalse(storedJson.contains("/live/"))
    }

    @Test
    fun unfavoriteUpdatesStoredData() {
        val file = tempFavoritesFile()
        val store = LiveTvFavoritesStore(file)

        store.saveFavorites(channels(), setOf(101, 102))
        store.saveFavorites(channels(), setOf(102))

        assertEquals(setOf(102), store.loadFavoriteIds())
        assertFalse(file.readText(Charsets.UTF_8).contains("101"))
    }

    @Test
    fun clearRemovesStoredFavorites() {
        val file = tempFavoritesFile()
        val store = LiveTvFavoritesStore(file)

        store.saveFavorites(channels(), setOf(101))
        store.clear()

        assertEquals(emptySet<Int>(), store.loadFavoriteIds())
    }

    @Test
    fun restoredFavoriteIdsRebuildFavoritesCategory() {
        val channels = channels()
        val favoriteIds = setOf(102)
        val state = LiveTvCatalogState.Ready(
            source = "cached",
            cacheAgeMs = 0L,
            channels = channels,
            categories = channels.toLiveTvCategories(favoriteIds),
            favoriteStreamIds = favoriteIds,
        )

        assertEquals(1, state.categories.first { it.id == LiveTvCategoryIds.Favorites }.channelCount)
        assertEquals(listOf("Sports Two"), state.channelsForCategory(LiveTvCategoryIds.Favorites).map { it.name })
    }

    private fun tempFavoritesFile(): File =
        kotlin.io.path.createTempFile(prefix = "live-tv-favorites", suffix = ".json").toFile().apply {
            deleteOnExit()
        }

    private fun channels(): List<LiveTvChannel> =
        listOf(
            channel("News One", "News", 101),
            channel("Sports Two", "Sports", 102),
        )

    private fun channel(name: String, category: String, streamId: Int): LiveTvChannel =
        LiveTvChannel(
            name = name,
            categoryName = category,
            streamId = streamId,
            containerExtension = "ts",
            redactedStreamUrl = "http://REDACTED_HOST/live/REDACTED/REDACTED/$streamId.ts",
        )
}

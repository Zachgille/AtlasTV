package com.atlas.atlastv.ui.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTvFavoritesTest {
    @Test
    fun favoriteStateUpdatesCategoryCountAndList() {
        val state = readyState(favorites = setOf(102)).withFavoriteCategories()

        assertEquals(1, state.categories.first { it.id == LiveTvCategoryIds.Favorites }.channelCount)
        assertEquals(listOf("Sports Two"), state.channelsForCategory(LiveTvCategoryIds.Favorites).map { it.name })
    }

    @Test
    fun unfavoriteRemovesItemFromFavoritesCategory() {
        val state = readyState(favorites = setOf(102)).copy(favoriteStreamIds = emptySet()).withFavoriteCategories()

        assertEquals(0, state.categories.first { it.id == LiveTvCategoryIds.Favorites }.channelCount)
        assertEquals(emptyList<LiveTvChannel>(), state.channelsForCategory(LiveTvCategoryIds.Favorites))
    }

    @Test
    fun detailsPanelFavoriteStateUpdates() {
        val unfavorited = readyState(selectedStreamId = 102, favorites = emptySet()).selectedChannelDetails()
        val favorited = readyState(selectedStreamId = 102, favorites = setOf(102)).selectedChannelDetails()

        requireNotNull(unfavorited)
        requireNotNull(favorited)
        assertFalse(unfavorited.isFavorite)
        assertTrue(favorited.isFavorite)
    }

    @Test
    fun searchCanMatchFavoriteChannelsAndClearRestoresCategoryContext() {
        val searched = readyState(
            selectedCategoryId = "News",
            selectedStreamId = 101,
            favorites = setOf(102),
            searchQuery = "favorite",
        )

        assertEquals(listOf("Sports Two"), searched.visibleChannels().map { it.name })

        val cleared = searched.copy(
            searchQuery = "",
            selectedChannelStreamId = searched.channelsForCategory("News").firstOrNull()?.streamId,
        )

        assertEquals("News", cleared.selectedCategoryId)
        assertEquals(listOf("News One"), cleared.visibleChannels().map { it.name })
    }

    private fun readyState(
        selectedCategoryId: String = LiveTvCategoryIds.All,
        selectedStreamId: Int = 101,
        favorites: Set<Int> = emptySet(),
        searchQuery: String = "",
    ): LiveTvCatalogState.Ready {
        val channels = listOf(
            channel("News One", "News", 101),
            channel("Sports Two", "Sports", 102),
            channel("Movies Three", "Movies", 103),
        )
        return LiveTvCatalogState.Ready(
            source = "cached",
            cacheAgeMs = 100L,
            channels = channels,
            categories = channels.toLiveTvCategories(favorites),
            selectedCategoryId = selectedCategoryId,
            selectedChannelStreamId = selectedStreamId,
            favoriteStreamIds = favorites,
            searchQuery = searchQuery,
        )
    }

    private fun channel(name: String, category: String, streamId: Int): LiveTvChannel =
        LiveTvChannel(
            name = name,
            categoryName = category,
            streamId = streamId,
            containerExtension = "ts",
            redactedStreamUrl = "fixture://debug_catalog/live/$streamId.ts",
        )
}

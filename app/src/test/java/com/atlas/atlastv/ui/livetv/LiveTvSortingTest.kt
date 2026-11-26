package com.atlas.atlastv.ui.livetv

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveTvSortingTest {
    @Test
    fun providerSortKeepsCachedOrder() {
        assertEquals(listOf("Zeta", "Alpha", "Beta"), readyState().visibleChannels().map { it.name })
    }

    @Test
    fun channelNameSortUsesAlphabeticalOrder() {
        val state = readyState(sortMode = LiveTvSortMode.ChannelName)

        assertEquals(listOf("Alpha", "Beta", "Zeta"), state.visibleChannels().map { it.name })
    }

    @Test
    fun categoryThenChannelSortGroupsByCategoryThenName() {
        val state = readyState(sortMode = LiveTvSortMode.CategoryThenChannel)

        assertEquals(listOf("Alpha", "Zeta", "Beta"), state.visibleChannels().map { it.name })
    }

    @Test
    fun favoritesFirstSortMovesFavoritesBeforeOtherChannels() {
        val state = readyState(sortMode = LiveTvSortMode.FavoritesFirst, favorites = setOf(1))

        assertEquals(listOf("Alpha", "Beta", "Zeta"), state.visibleChannels().map { it.name })
    }

    @Test
    fun sortAppliesInsideCategory() {
        val state = readyState(sortMode = LiveTvSortMode.ChannelName, selectedCategoryId = "News")

        assertEquals(listOf("Alpha", "Zeta"), state.visibleChannels().map { it.name })
    }

    @Test
    fun sortAppliesToSearchResults() {
        val state = readyState(sortMode = LiveTvSortMode.ChannelName, searchQuery = "a")

        assertEquals(listOf("Alpha", "Beta", "Zeta"), state.visibleChannels().map { it.name })
    }

    @Test
    fun sortAppliesToFavoritesCategory() {
        val state = readyState(
            sortMode = LiveTvSortMode.ChannelName,
            selectedCategoryId = LiveTvCategoryIds.Favorites,
            favorites = setOf(1, 3),
        )

        assertEquals(listOf("Alpha", "Beta"), state.visibleChannels().map { it.name })
    }

    private fun readyState(
        sortMode: LiveTvSortMode = LiveTvSortMode.Provider,
        selectedCategoryId: String = LiveTvCategoryIds.All,
        favorites: Set<Int> = emptySet(),
        searchQuery: String = "",
    ): LiveTvCatalogState.Ready {
        val channels = listOf(
            channel("Zeta", "News", 2),
            channel("Alpha", "News", 1),
            channel("Beta", "Sports", 3),
        )
        return LiveTvCatalogState.Ready(
            source = "cached",
            cacheAgeMs = 0L,
            channels = channels,
            categories = channels.toLiveTvCategories(favorites),
            selectedCategoryId = selectedCategoryId,
            favoriteStreamIds = favorites,
            sortMode = sortMode,
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

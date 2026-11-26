package com.atlas.atlastv.ui.livetv

import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveTvCatalogMapperTest {
    private val mapper = LiveTvCatalogMapper()
    private val credentials = XtreamCredentials(
        baseUrl = "http://example.test",
        username = "fake_user",
        password = "fake_pass",
    )

    @Test
    fun mapsCachedLiveItemsToCappedChannelState() {
        val json = """
            {
              "catalogSource": "real",
              "playableLiveItems": [
                {"name":"News","categoryName":"US News","streamId":101,"containerExtension":"ts","redactedUrl":"http://REDACTED_HOST/live/REDACTED/REDACTED/101.ts"},
                {"name":"Sports","categoryName":"Sports","streamId":102,"containerExtension":".m3u8","redactedUrl":"http://REDACTED_HOST/live/REDACTED/REDACTED/102.m3u8"}
              ]
            }
        """.trimIndent()

        val state = mapper.mapCachedCatalogJson(json, credentials, maxItems = 1)

        assertEquals("real", state.source)
        assertEquals(1, state.channels.size)
        assertEquals("All", state.categories[0].name)
        assertEquals("Favorites", state.categories[1].name)
        assertEquals(1, state.categories.first { it.name == "US News" }.channelCount)
        assertEquals("News", state.channels.first().name)
        assertEquals("US News", state.channels.first().categoryName)
        assertEquals("ts", state.channels.first().containerExtension)
        assertEquals("http://REDACTED_HOST/live/REDACTED/REDACTED/101.ts", state.channels.first().redactedStreamUrl)
        assertFalse(state.channels.first().redactedStreamUrl.contains("fake_user"))
        assertFalse(state.channels.first().redactedStreamUrl.contains("fake_pass"))
    }

    @Test
    fun mappedUiStateContainsOnlySafeDisplayDataAndIds() {
        val json = """
            {
              "catalogSource": "real",
              "playableLiveItems": [
                {"name":"News","categoryName":"US News","streamId":101,"containerExtension":"ts"}
              ]
            }
        """.trimIndent()

        val channel = mapper.mapCachedCatalogJson(json, credentials).channels.single()
        val serialized = Gson().toJson(channel)

        assertFalse(serialized.contains("streamUrl"))
        assertFalse(serialized.contains("example.test"))
        assertFalse(serialized.contains("fake_user"))
        assertFalse(serialized.contains("fake_pass"))
        assertEquals("http://REDACTED_HOST/live/REDACTED/REDACTED/101.ts", channel.redactedStreamUrl)
    }

    @Test
    fun usesUncategorizedForMissingCategory() {
        val json = """
            {
              "catalogSource": "cached",
              "playableLiveItems": [
                {"name":"","streamId":201,"containerExtension":"","redactedUrl":"http://REDACTED_HOST/live/REDACTED/REDACTED/201.ts"}
              ]
            }
        """.trimIndent()

        val channel = mapper.mapCachedCatalogJson(json, credentials).channels.single()

        assertEquals("Live stream 201", channel.name)
        assertEquals("Uncategorized", channel.categoryName)
        assertEquals("ts", channel.containerExtension)
    }

    @Test
    fun groupsChannelsByCategoryWithAllAndFavorites() {
        val json = """
            {
              "catalogSource": "real",
              "playableLiveItems": [
                {"name":"News A","categoryName":"News","streamId":101,"containerExtension":"ts","redactedUrl":"http://REDACTED_HOST/live/REDACTED/REDACTED/101.ts"},
                {"name":"News B","categoryName":"News","streamId":102,"containerExtension":"ts","redactedUrl":"http://REDACTED_HOST/live/REDACTED/REDACTED/102.ts"},
                {"name":"Sports A","categoryName":"Sports","streamId":103,"containerExtension":"ts","redactedUrl":"http://REDACTED_HOST/live/REDACTED/REDACTED/103.ts"}
              ]
            }
        """.trimIndent()

        val state = mapper.mapCachedCatalogJson(json, credentials)

        assertEquals(3, state.categories.first { it.id == LiveTvCategoryIds.All }.channelCount)
        assertEquals(0, state.categories.first { it.id == LiveTvCategoryIds.Favorites }.channelCount)
        assertEquals(2, state.categories.first { it.name == "News" }.channelCount)
        assertEquals(1, state.channelsForCategory("Sports").size)
    }

    @Test
    fun searchNormalizesAndMatchesChannelAndCategoryTokens() {
        val state = mapper.mapCachedCatalogJson(searchFixtureJson, credentials)

        assertEquals(listOf("BBC World News"), state.channels.searchLiveTv("bbc-news").map { it.name })
        assertEquals(listOf("Prime Sports 1"), state.channels.searchLiveTv("sports prime").map { it.name })
        assertEquals(listOf("Kids Zone"), state.channels.searchLiveTv("children").map { it.name })
    }

    @Test
    fun categoryAndSearchInteractionUsesQueryAcrossCappedList() {
        val state = mapper.mapCachedCatalogJson(searchFixtureJson, credentials)
            .copy(selectedCategoryId = "News")

        assertEquals(listOf("BBC World News"), state.channelsForCategory().map { it.name })
        assertEquals(listOf("Prime Sports 1"), state.copy(searchQuery = "sports").visibleChannels().map { it.name })
    }

    @Test
    fun clearSearchRestoresPriorCategoryContext() {
        val searched = mapper.mapCachedCatalogJson(searchFixtureJson, credentials)
            .copy(selectedCategoryId = "News", searchQuery = "sports", selectedChannelStreamId = 2)
        val cleared = searched.copy(
            searchQuery = "",
            selectedChannelStreamId = searched.channelsForCategory().firstOrNull()?.streamId,
        )

        assertEquals("News", cleared.selectedCategoryId)
        assertEquals(listOf("BBC World News"), cleared.visibleChannels().map { it.name })
    }

    @Test
    fun preservesConfiguredCapForCachedLiveItems() {
        val items = (1..130).joinToString(",") { id ->
            """{"name":"Channel $id","categoryName":"Bulk","streamId":$id,"containerExtension":"ts","redactedUrl":"http://REDACTED_HOST/live/REDACTED/REDACTED/$id.ts"}"""
        }
        val json = """{"catalogSource":"real","playableLiveItems":[$items]}"""

        val state = mapper.mapCachedCatalogJson(json, credentials)

        assertEquals(120, state.channels.size)
        assertEquals(120, state.categories.first { it.id == LiveTvCategoryIds.All }.channelCount)
    }

    @Test
    fun rowBrowsingBuildsFavoritesRecentAndCategoryRows() {
        val state = mapper.mapCachedCatalogJson(searchFixtureJson, credentials)
            .copy(
                favoriteStreamIds = setOf(2),
                selectedCategoryId = "News",
                sortMode = LiveTvSortMode.ChannelName,
            )
            .withFavoriteCategories()

        val rows = state.channelRows()

        assertEquals("Favorites", rows[0].title)
        assertEquals(listOf("Prime Sports 1"), rows[0].channels.map { it.name })
        assertEquals("Recent Channels", rows[1].title)
        assertEquals("News", rows[2].title)
        assertEquals(listOf("BBC World News"), rows[2].channels.map { it.name })
        assertFalse(Gson().toJson(rows).contains("example.test"))
        assertFalse(Gson().toJson(rows).contains("fake_user"))
        assertFalse(Gson().toJson(rows).contains("fake_pass"))
    }

    @Test
    fun searchRowsUseCappedSearchResultsOnly() {
        val state = mapper.mapCachedCatalogJson(searchFixtureJson, credentials)
            .copy(searchQuery = "sports", favoriteStreamIds = setOf(2))

        val rows = state.channelRows()

        assertEquals(listOf("Search Results"), rows.map { it.title })
        assertEquals(listOf("Prime Sports 1"), rows.single().channels.map { it.name })
    }

    @Test
    fun liveTvRowTitlesAreCleanedForNormalMode() {
        assertEquals("New Releases", cleanLiveTvRowTitle("EN - NEW RELEASES"))
        assertEquals("US Sports", cleanLiveTvRowTitle("US SPORTS"))
        assertEquals("Channels", cleanLiveTvRowTitle("   "))
    }

    private val searchFixtureJson = """
        {
          "catalogSource": "fixture",
          "playableLiveItems": [
            {"name":"BBC World News","categoryName":"News","streamId":1,"containerExtension":"ts","redactedUrl":"http://REDACTED_HOST/live/REDACTED/REDACTED/1.ts"},
            {"name":"Prime Sports 1","categoryName":"US Sports","streamId":2,"containerExtension":"ts","redactedUrl":"http://REDACTED_HOST/live/REDACTED/REDACTED/2.ts"},
            {"name":"Kids Zone","categoryName":"Children TV","streamId":3,"containerExtension":"ts","redactedUrl":"http://REDACTED_HOST/live/REDACTED/REDACTED/3.ts"}
          ]
        }
    """.trimIndent()
}

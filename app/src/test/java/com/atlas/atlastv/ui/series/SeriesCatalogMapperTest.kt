package com.atlas.atlastv.ui.series

import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.ui.media.AtlasImageState
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeriesCatalogMapperTest {
    private val mapper = SeriesCatalogMapper()

    @Test
    fun mapsCachedSeriesItemsToSafeCappedState() {
        val state = mapper.mapCachedCatalogJson(fixtureJson, maxItems = 2)

        assertEquals("fixture", state.source)
        assertEquals(2, state.series.size)
        assertEquals("All", state.categories.first().name)
        assertEquals("Fixture Series One", state.series.first().name)
        assertEquals(AtlasImageState.SafeImage, state.series.first().posterImage.state)
        assertFalse(Gson().toJson(state).contains("fake_user"))
        assertFalse(Gson().toJson(state).contains("fake_pass"))
        assertFalse(Gson().toJson(state).contains("example.test"))
    }

    @Test
    fun categoryFilteringAndSearchUseCappedSafeList() {
        val state = mapper.mapCachedCatalogJson(fixtureJson)

        assertEquals(listOf("Fixture Series One", "Fixture Series Three"), state.copy(selectedCategoryId = "Fixture Series").seriesForCategory().map { it.name })
        assertEquals(listOf("Fixture Series Two"), state.copy(searchQuery = "drama two").visibleSeries().map { it.name })
    }

    @Test
    fun selectedDetailsUseSafeMetadataOnly() {
        val details = mapper.mapCachedCatalogJson(fixtureJson).copy(selectedSeriesId = 3002).selectedSeriesDetails()!!

        assertEquals("Fixture Series Two", details.name)
        assertEquals("Fixture Drama", details.categoryName)
        assertEquals(3002, details.seriesId)
        assertEquals(2, details.selectedIndex)
    }

    @Test
    fun normalBrowseSeriesDetailsResolveSelectedSeriesOutsideOldVisibleScope() {
        val state = mapper.mapCachedCatalogJson(fixtureJson).copy(
            selectedCategoryId = "Fixture Drama",
            searchQuery = "two",
            selectedSeriesId = 3003,
        )

        val details = state.selectedNormalBrowseSeriesDetails()!!

        assertEquals("Fixture Series Three", details.name)
        assertEquals("Fixture Series", details.categoryName)
        assertEquals(3, details.selectedIndex)
        assertEquals(3, details.visibleCount)
        assertFalse(Gson().toJson(details).contains("example.test"))
        assertFalse(Gson().toJson(details).contains("fake_user"))
        assertFalse(Gson().toJson(details).contains("fake_pass"))
    }

    @Test
    fun rememberedSeriesOutsideCurrentRowsBecomesSelectedDetail() {
        val state = mapper.mapCachedCatalogJson(fixtureJson, maxItems = 1)
        val selected = SeriesItem(
            name = "Exact Search Series",
            categoryName = "Search Results",
            seriesId = 3999,
        )

        val next = state.withRememberedSeries(selected)
        val details = next.selectedNormalBrowseSeriesDetails()!!

        assertEquals(3999, next.selectedSeriesId)
        assertEquals("Exact Search Series", details.name)
        assertEquals(2, next.series.size)
        assertFalse(Gson().toJson(next).contains("example.test"))
        assertFalse(Gson().toJson(next).contains("fake_user"))
        assertFalse(Gson().toJson(next).contains("fake_pass"))
    }

    @Test
    fun episodeDetailsShowProgress() {
        val episode = SeriesEpisodeItem(
            title = "Pilot",
            seasonNumber = 1,
            episodeNumber = 1,
            episodeId = 9001,
            containerExtension = "mp4",
            redactedStreamUrl = "fixture:series_episode:9001",
        )
        val state = mapper.mapCachedCatalogJson(fixtureJson).copy(
            watchProgress = mapOf(
                9001 to WatchProgressRecord(
                    contentType = WatchProgressContentType.SeriesEpisode,
                    streamId = 9001,
                    seriesId = 3001,
                    title = "Fixture Series One - Pilot",
                    seriesTitle = "Fixture Series One",
                    episodeTitle = "Pilot",
                    categoryName = "Fixture Series",
                    extension = "mp4",
                    durationMs = 3_600_000L,
                    positionMs = 600_000L,
                    updatedAtMs = 50L,
                ),
            ),
        )

        val details = state.episodeDetails(episode)

        assertEquals("Resume at 10:00 (16%)", details.resumeLabel)
        assertEquals(16, details.progressPercent)
        assertEquals("Resume at 10:00 (16%)", details.progressLabel)
        assertTrue(details.resumeEligible)
    }

    @Test
    fun seasonGroupingSortsSeasonsAndEpisodes() {
        val ready = SeriesEpisodeState.Ready(
            listOf(
                episode(title = "Episode B", season = 2, number = 2, id = 22),
                episode(title = "Episode A", season = 1, number = 2, id = 12),
                episode(title = "Pilot", season = 1, number = 1, id = 11),
                episode(title = "Unknown", season = null, number = null, id = 99),
            ),
        )

        assertEquals(listOf("Season 1", "Season 2", "Season unknown"), ready.seasons().map { it.label })
        assertEquals(listOf(11, 12), ready.episodesForSeason(1).map { it.episodeId })
        assertEquals(listOf(99), ready.episodesForSeason(null).map { it.episodeId })
    }

    @Test
    fun selectedEpisodeDetailsUseCurrentSeasonContext() {
        val state = mapper.mapCachedCatalogJson(fixtureJson).copy(
            episodeState = SeriesEpisodeState.Ready(
                listOf(
                    episode(title = "S1 Pilot", season = 1, number = 1, id = 11),
                    episode(title = "S2 Pilot", season = 2, number = 1, id = 21),
                    episode(title = "S2 Second", season = 2, number = 2, id = 22),
                ),
            ),
            selectedSeasonNumber = 2,
            selectedEpisodeId = 22,
        )

        val details = state.selectedEpisodeDetails()!!

        assertEquals("S2 Second", details.title)
        assertEquals(2, details.seasonNumber)
        assertEquals(2, details.episodeNumber)
        assertEquals(2, details.selectedIndex)
        assertEquals(2, details.visibleCount)
    }

    @Test
    fun invalidSelectedSeasonAndEpisodeFallBackToFirstAvailableEpisode() {
        val state = mapper.mapCachedCatalogJson(fixtureJson).copy(
            episodeState = SeriesEpisodeState.Ready(listOf(episode(title = "Pilot", season = 1, number = 1, id = 11))),
            selectedSeasonNumber = 9,
            selectedEpisodeId = 999,
        ).withValidEpisodeSelection()

        assertEquals(1, state.selectedSeasonNumber)
        assertEquals(11, state.selectedEpisodeId)
    }

    @Test
    fun completedEpisodeStateSuppressesResumeLabel() {
        val episode = episode(title = "Finale", season = 1, number = 8, id = 9008)
        val state = mapper.mapCachedCatalogJson(fixtureJson).copy(
            watchProgress = mapOf(
                9008 to WatchProgressRecord(
                    contentType = WatchProgressContentType.SeriesEpisode,
                    streamId = 9008,
                    seriesId = 3001,
                    title = "Finale",
                    episodeTitle = "Finale",
                    durationMs = 3_600_000L,
                    positionMs = 3_550_000L,
                    updatedAtMs = 90L,
                    completed = true,
                ),
            ),
        )

        val details = state.episodeDetails(episode)

        assertEquals("Completed", details.progressLabel)
        assertFalse(details.resumeEligible)
    }

    @Test
    fun rawProviderCoverUrlsBecomeOpaqueSeriesImageTokens() {
        val state = mapper.mapCachedCatalogJson(
            """
                {
                  "catalogSource": "fixture",
                  "playableSeriesItems": [
                    {"name":"Unsafe Cover","categoryName":"Fixture","seriesId":42,"cover":"https://example.test/series/fake_user/fake_pass/poster.jpg"}
                  ]
                }
            """.trimIndent(),
        )
        val json = Gson().toJson(state)

        assertEquals(AtlasImageState.ProviderImage, state.series.single().posterImage.state)
        assertFalse(json.contains("example.test"))
        assertFalse(json.contains("fake_user"))
        assertFalse(json.contains("fake_pass"))
        assertFalse(json.contains("/series/fake_user"))
    }

    @Test
    fun rawEpisodePlaybackUrlsAreNotAcceptedAsSeriesPosterImages() {
        val state = mapper.mapCachedCatalogJson(
            """
                {
                  "catalogSource": "fixture",
                  "playableSeriesItems": [
                    {"name":"Unsafe Cover","categoryName":"Fixture","seriesId":42,"cover":"https://example.test/series/fake_user/fake_pass/1234.mp4"}
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(AtlasImageState.ProviderImageHidden, state.series.single().posterImage.state)
        assertFalse(Gson().toJson(state).contains("example.test"))
    }

    @Test
    fun watchlistCategoryCountListAndSelectedDetailsUpdate() {
        val state = mapper.mapCachedCatalogJson(fixtureJson)
            .copy(watchlistSeriesIds = setOf(3003))
            .withWatchlistCategory()

        assertEquals(1, state.categories.first { it.id == SeriesCategoryIds.Watchlist }.itemCount)
        assertEquals(listOf("Fixture Series Three"), state.copy(selectedCategoryId = SeriesCategoryIds.Watchlist).seriesForCategory().map { it.name })
        assertEquals(true, state.copy(selectedSeriesId = 3003).selectedSeriesDetails()!!.isWatchlisted)
        assertFalse(Gson().toJson(state).contains("example.test"))
        assertFalse(Gson().toJson(state).contains("fake_user"))
        assertFalse(Gson().toJson(state).contains("fake_pass"))
    }

    @Test
    fun providerSortKeepsCachedOrder() {
        val state = mapper.mapCachedCatalogJson(sortFixtureJson)

        assertEquals(
            listOf("Zeta Show", "Alpha Show", "Beta Show"),
            state.copy(sortMode = SeriesSortMode.Provider).visibleSeries().map { it.name },
        )
    }

    @Test
    fun titleSortOrdersSeriesByTitle() {
        val state = mapper.mapCachedCatalogJson(sortFixtureJson)

        assertEquals(
            listOf("Alpha Show", "Beta Show", "Zeta Show"),
            state.copy(sortMode = SeriesSortMode.Title).visibleSeries().map { it.name },
        )
    }

    @Test
    fun categoryThenTitleSortOrdersByCategoryThenTitle() {
        val state = mapper.mapCachedCatalogJson(sortFixtureJson)

        assertEquals(
            listOf("Beta Show", "Zeta Show", "Alpha Show"),
            state.copy(sortMode = SeriesSortMode.CategoryThenTitle).visibleSeries().map { it.name },
        )
    }

    @Test
    fun sortAppliesToCategoryAndSearchResults() {
        val state = mapper.mapCachedCatalogJson(sortFixtureJson)

        assertEquals(
            listOf("Beta Show", "Zeta Show"),
            state.copy(selectedCategoryId = "Fixture Action", sortMode = SeriesSortMode.Title)
                .seriesForCategory()
                .map { it.name },
        )
        assertEquals(
            listOf("Beta Show", "Zeta Show"),
            state.copy(searchQuery = "fixture action", sortMode = SeriesSortMode.Title)
                .visibleSeries()
                .map { it.name },
        )
    }

    @Test
    fun restoredContextFallsBackToSafeAvailableSeries() {
        val state = mapper.mapCachedCatalogJson(sortFixtureJson)

        val restored = state.withRestoredContext(
            categoryId = "Fixture Action",
            seriesId = 9999,
            query = "zeta",
            sortMode = SeriesSortMode.Title,
        )

        assertEquals("Fixture Action", restored.selectedCategoryId)
        assertEquals(3001, restored.selectedSeriesId)
        assertEquals("zeta", restored.searchQuery)
        assertEquals(SeriesSortMode.Title, restored.sortMode)
    }

    @Test
    fun invalidRestoredContextFallsBackSafely() {
        val state = mapper.mapCachedCatalogJson(sortFixtureJson)

        val restored = state.withRestoredContext(
            categoryId = "Missing",
            seriesId = 9999,
            query = "",
            sortMode = SeriesSortMode.Provider,
        )

        assertEquals(SeriesCategoryIds.All, restored.selectedCategoryId)
        assertEquals(3001, restored.selectedSeriesId)
    }

    @Test
    fun preservesConfiguredCap() {
        val items = (1..130).joinToString(",") { id ->
            """{"name":"Series $id","categoryName":"Bulk","seriesId":$id}"""
        }

        val state = mapper.mapCachedCatalogJson("""{"catalogSource":"fixture","playableSeriesItems":[$items]}""")

        assertEquals(120, state.series.size)
    }

    private val fixtureJson = """
        {
          "catalogSource": "fixture",
          "playableSeriesItems": [
            {"name":"Fixture Series One","categoryName":"Fixture Series","seriesId":3001,"cover":"fixture://poster/series-one"},
            {"name":"Fixture Series Two","categoryName":"Fixture Drama","seriesId":3002},
            {"name":"Fixture Series Three","categoryName":"Fixture Series","seriesId":3003}
          ]
        }
    """.trimIndent()

    private val sortFixtureJson = """
        {
          "catalogSource": "fixture",
          "playableSeriesItems": [
            {"name":"Zeta Show","categoryName":"Fixture Action","seriesId":3001},
            {"name":"Alpha Show","categoryName":"Fixture Drama","seriesId":3002},
            {"name":"Beta Show","categoryName":"Fixture Action","seriesId":3003}
          ]
        }
    """.trimIndent()

    private fun episode(title: String, season: Int?, number: Int?, id: Int): SeriesEpisodeItem =
        SeriesEpisodeItem(
            title = title,
            seasonNumber = season,
            episodeNumber = number,
            episodeId = id,
            containerExtension = "mp4",
            redactedStreamUrl = "http://REDACTED_HOST/series/REDACTED/REDACTED/$id.mp4",
        )
}

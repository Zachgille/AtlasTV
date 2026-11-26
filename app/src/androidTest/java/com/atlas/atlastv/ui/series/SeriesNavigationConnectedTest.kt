package com.atlas.atlastv.ui.series

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import com.atlas.atlastv.data.db.SeriesWatchlistRoomStore
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SeriesNavigationConnectedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        File(targetContext.filesDir, SeriesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, SeriesWatchlistStore.FileName).delete()
        clearRoomState()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        File(targetContext.filesDir, SeriesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, SeriesWatchlistStore.FileName).delete()
        clearRoomState()
    }

    @Test
    fun openSeriesFromHomeAndRenderCachedSeries() {
        openSeries()

        composeRule.onNodeWithTag("series_row_page").fetchSemanticsNode()
        composeRule.onAllNodesWithTag("horizontal_row").fetchSemanticsNodes()
        composeRule.onAllNodesWithTag("series_row_title").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("More Shows").assertCountEquals(1)
        composeRule.onAllNodesWithText("Recently Added Shows").fetchSemanticsNodes()
        composeRule.onAllNodesWithTag("series_category_list").assertCountEquals(0)
        composeRule.onAllNodesWithText("Source:", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Cache age", substring = true).assertCountEquals(0)
    }

    @Test
    fun dpadSelectOpensSeriesDetail() {
        openSeries()

        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionLeft) }
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("series_detail_page").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_detail_header").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_detail_hero").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_detail_title").assertTextContains("Fixture Series One")
        composeRule.onNodeWithTag("series_detail_category").assertTextContains("Fixture Series")
        composeRule.onNodeWithTag("series_detail_play_episode").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_detail_watchlist").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_detail_watchlist").assertTextContains("+ Watchlist")
        composeRule.onAllNodesWithText("Play Next").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Source:", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Stream ID", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("fixture://", substring = true).assertCountEquals(0)
    }

    @Test
    fun toggleSeriesWatchlistFromDetailPersistsInRoomAfterRelaunch() {
        openSeries()
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("series_detail_page").fetchSemanticsNode()

        composeRule.onNodeWithTag("series_detail_watchlist").performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("In Watchlist").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("series_detail_watchlist_state").assertTextContains("In Watchlist")

        File(targetContext.filesDir, SeriesWatchlistStore.FileName).delete()
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)

        openSeries()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Watchlist").fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("Fixture Series One").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun seriesWatchlistRowAppearsOnlyWhenNonEmpty() {
        openSeries()
        composeRule.onAllNodesWithText("Watchlist").assertCountEquals(0)

        seriesWatchlistStore().saveWatchlist(
            series = listOf(SeriesItem("Fixture Series One", "Fixture Series", 3001)),
            watchlistSeriesIds = setOf(3001),
        )
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)

        openSeries()
        composeRule.onAllNodesWithText("Watchlist").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Fixture Series One").fetchSemanticsNodes()
    }

    @Test
    fun seriesWatchlistRestoresAfterRelaunchFromRoomWithoutJson() {
        seriesWatchlistStore().saveWatchlist(
            series = listOf(SeriesItem("Fixture Series Two", "Fixture Drama", 3002)),
            watchlistSeriesIds = setOf(3002),
        )
        File(targetContext.filesDir, SeriesWatchlistStore.FileName).delete()
        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)

        openSeries()

        composeRule.onAllNodesWithText("Watchlist").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Fixture Series Two").fetchSemanticsNodes()
    }

    @Test
    fun searchOverlayFiltersSeriesRowsAndClearsToNormalBrowse() {
        openSeries()

        composeRule.onNodeWithTag("series_search_open").performClick()
        composeRule.onNodeWithTag("series_search_overlay").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_search_field").performTextInput("drama")
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("series_refreshing").fetchSemanticsNodes().isEmpty() }

        composeRule.onNodeWithTag("series_search_result_count").assertTextContains("1 results")
        composeRule.onAllNodesWithText("Search Results").assertCountEquals(1)
        composeRule.onAllNodesWithText("Fixture Series Two").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Fixture Series One").assertCountEquals(0)
        composeRule.onAllNodesWithTag("series_category_list").assertCountEquals(0)

        composeRule.onNodeWithTag("series_clear_search").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("series_refreshing").fetchSemanticsNodes().isEmpty() }
        composeRule.onAllNodesWithText("More Shows").assertCountEquals(1)
        composeRule.onAllNodesWithText("Search Results").assertCountEquals(0)
    }

    @Test
    fun dpadUpFromFirstSeriesRowReachesSearchAction() {
        openSeries()

        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.onNodeWithTag("series_search_open").assertIsFocused()
        composeRule.onNodeWithTag("series_search_open").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("series_search_overlay").fetchSemanticsNode()
    }

    @Test
    fun searchBackClearsBeforeLeavingSeries() {
        openSeries()

        composeRule.onNodeWithTag("series_search_open").performClick()
        composeRule.onNodeWithTag("series_search_field").performTextInput("missing")
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("series_refreshing").fetchSemanticsNodes().isEmpty() }
        composeRule.onNodeWithTag("series_empty").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_search_field").performKeyInput { pressKey(Key.Back) }
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("series_refreshing").fetchSemanticsNodes().isEmpty() }

        composeRule.onNodeWithTag("series_screen").fetchSemanticsNode()
        composeRule.onAllNodesWithText("More Shows").assertCountEquals(1)
        composeRule.onAllNodesWithText("No shows match your search.").assertCountEquals(0)
    }

    private fun openSeries() {
        composeRule.onNodeWithTag("top_nav_item_shows").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("series_row_page").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("series_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_search_open").performKeyInput { pressKey(Key.DirectionDown) }
    }

    private fun seriesWatchlistStore(): SeriesWatchlistStore =
        SeriesWatchlistStore(
            File(targetContext.filesDir, SeriesWatchlistStore.FileName),
            SeriesWatchlistRoomStore(AtlasDatabaseProvider.database(targetContext).seriesWatchlistDao()),
        )

    private fun clearRoomState() {
        runBlocking {
            val database = AtlasDatabaseProvider.database(targetContext)
            database.safeCatalogDao().deleteType(SafeCatalogContentTypes.Movie)
            database.safeCatalogDao().deleteType(SafeCatalogContentTypes.Series)
            SeriesWatchlistRoomStore(database.seriesWatchlistDao()).clear()
        }
    }

    private companion object {
        private val fixtureCacheJson = """
            {
              "catalogSource": "fixture",
              "generatedAtEpochMs": 1,
              "itemCounts": {
                "seriesCategories": 2,
                "series": 3,
                "keptSeries": 3
              },
              "redactedUrl": "fixture://debug_catalog",
              "redactedHost": "fixture",
              "playableLiveItems": [],
              "playableVodItems": [],
              "playableSeriesItems": [
                {"name":"Fixture Series One","categoryName":"Fixture Series","seriesId":3001},
                {"name":"Fixture Series Two","categoryName":"Fixture Drama","seriesId":3002},
                {"name":"Fixture Series Three","categoryName":"Fixture Series","seriesId":3003}
              ]
            }
        """.trimIndent()
    }
}

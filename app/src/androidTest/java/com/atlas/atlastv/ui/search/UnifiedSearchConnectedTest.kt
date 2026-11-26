package com.atlas.atlastv.ui.search

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.MoviesWatchlistRoomStore
import com.atlas.atlastv.data.db.SafeCatalogContentTypes
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import com.atlas.atlastv.ui.movies.MoviesPreferencesStore
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.series.SeriesPreferencesStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class UnifiedSearchConnectedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule(order = 0)
    val seedRule = object : TestWatcher() {
        override fun starting(description: Description) {
            seedState()
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @After
    fun tearDown() {
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, SeriesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, MoviesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, WatchProgressStore.FileName).delete()
        clearRoomState()
    }

    private fun seedState() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, SeriesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, MoviesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, WatchProgressStore.FileName).delete()
        clearRoomState()
    }

    @Test
    fun topNavSearchQueriesMoviesAndShowsTogether() {
        openSearch()
        composeRule.onNodeWithTag("unified_search_prompt").assertDoesNotExist()

        composeRule.onNodeWithTag("unified_search_field").performTextInput("drama")
        waitForResults("unified_search_result_movie-2002")
        composeRule.onNodeWithTag("unified_search_result_show-3002").fetchSemanticsNode()
        composeRule.onNodeWithTag("unified_search_result_count").assertDoesNotExist()
        composeRule.onNodeWithTag("unified_search_movies").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Fixture Movie Two").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Fixture Movie One").assertCountEquals(0)
        composeRule.onAllNodesWithText("Source:", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("fixture://", substring = true).assertCountEquals(0)
    }

    @Test
    fun selectingMovieResultOpensMovieDetail() {
        openSearch()
        composeRule.onNodeWithTag("unified_search_field").performTextInput("action")
        waitForResults("unified_search_result_movie-2001")
        composeRule.onNodeWithTag("unified_search_result_count").assertDoesNotExist()
        composeRule.onNodeWithTag("unified_search_field").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("focused_unified_search_result_movie-2001").performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.onNodeWithTag("movie_detail_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("movie_detail_title").assertTextContains("Fixture Movie One")
    }

    @Test
    fun selectingShowResultOpensSeriesDetail() {
        openSearch()
        composeRule.onNodeWithTag("unified_search_field").performTextInput("series two")
        waitForResults("unified_search_result_show-3002")
        composeRule.onNodeWithTag("unified_search_result_count").assertDoesNotExist()
        composeRule.onAllNodesWithText("Fixture Series Two").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Fixture Series One").assertCountEquals(0)
        composeRule.onNodeWithTag("unified_search_field").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("focused_unified_search_result_show-3002").performKeyInput { pressKey(Key.DirectionCenter) }

        composeRule.onNodeWithTag("series_detail_page").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_detail_title").assertTextContains("Fixture Series Two")
    }

    @Test
    fun clearAndBackBehaviorStayOnSearchThenReturnHome() {
        openSearch()
        composeRule.onNodeWithTag("unified_search_field").performTextInput("missing")
        waitForResults("unified_search_empty")
        composeRule.onNodeWithTag("unified_search_empty").fetchSemanticsNode()
        composeRule.onNodeWithTag("unified_search_clear").performClick()
        composeRule.onNodeWithTag("unified_search_prompt").assertDoesNotExist()
        composeRule.onNodeWithTag("unified_search_back").performClick()
        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()
    }

    private fun openSearch() {
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        composeRule.waitUntil(10_000) {
            composeRule.onNodeWithTag("hero_area").runCatching { fetchSemanticsNode() }.isSuccess
        }
        composeRule.onNodeWithTag("top_nav_item_search").performClick()
        composeRule.onNodeWithTag("unified_search_screen").fetchSemanticsNode()
    }

    private fun waitForResults(tag: String) {
        composeRule.waitUntil(5_000L) { composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun clearRoomState() {
        runBlocking {
            val database = AtlasDatabaseProvider.database(targetContext)
            database.safeCatalogDao().deleteType(SafeCatalogContentTypes.Movie)
            database.safeCatalogDao().deleteType(SafeCatalogContentTypes.Series)
            MoviesWatchlistRoomStore(database.movieWatchlistDao()).clear()
            WatchProgressRoomStore(database.watchProgressDao()).clear()
        }
    }

    private companion object {
        private val fixtureCacheJson = """
            {
              "catalogSource": "fixture",
              "generatedAtEpochMs": 1,
              "redactedUrl": "fixture://debug_catalog",
              "redactedHost": "fixture",
              "playableLiveItems": [],
              "playableVodItems": [
                {"title":"Fixture Movie One","categoryName":"Fixture Action","streamId":2001,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/2001.mp4"},
                {"title":"Fixture Movie Two","categoryName":"Fixture Drama","streamId":2002,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/2002.mp4"}
              ],
              "playableSeriesItems": [
                {"name":"Fixture Series One","categoryName":"Fixture Series","seriesId":3001},
                {"name":"Fixture Series Two","categoryName":"Fixture Drama","seriesId":3002}
              ]
            }
        """.trimIndent()
    }
}

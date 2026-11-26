package com.atlas.atlastv.ui.navigation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.MoviesWatchlistRoomStore
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import com.atlas.atlastv.ui.movies.MoviesPreferencesStore
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaybackBackBehaviorConnectedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, MoviesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, WatchProgressStore.FileName).delete()
        clearRoomWatchlist()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        scenario = null
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, MoviesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, WatchProgressStore.FileName).delete()
        clearRoomWatchlist()
    }

    @Test
    fun backFromMoviesShowsAndLiveTvReturnsHome() {
        composeRule.onNodeWithTag("top_nav_item_movies").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("movies_row_page").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("movies_screen").fetchSemanticsNode()
        pressBack()
        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()

        composeRule.onNodeWithTag("top_nav_item_shows").performClick()
        composeRule.onNodeWithTag("series_screen").fetchSemanticsNode()
        pressBack()
        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()

        composeRule.onNodeWithTag("top_nav_item_live_tv").performClick()
        composeRule.onNodeWithTag("live_tv_screen").fetchSemanticsNode()
        pressBack()
        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()
    }

    @Test
    fun moviePlaybackBackReturnsToMoviesAndHidesDiagnosticsInNormalMode() {
        composeRule.onNodeWithTag("top_nav_item_movies").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("movies_row_page").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("movies_search_open").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("movie_detail_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("movie_detail_play").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.onNodeWithTag("live_tv_player_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("playback_full_screen_surface").fetchSemanticsNode()
        composeRule.onNodeWithTag("normal_playback_overlay").fetchSemanticsNode()
        composeRule.onNodeWithTag("playback_controls_overlay", useUnmergedTree = true).fetchSemanticsNode()
        composeRule.onNodeWithTag("playback_play_pause_control").fetchSemanticsNode()
        composeRule.onNodeWithTag("playback_rewind_control").fetchSemanticsNode()
        composeRule.onNodeWithTag("playback_fast_forward_control").fetchSemanticsNode()
        composeRule.onNodeWithTag("playback_progress_bar", useUnmergedTree = true).fetchSemanticsNode()
        composeRule.onNodeWithTag("debug_diagnostics_hidden").fetchSemanticsNode()
        composeRule.onAllNodesWithText("HTTP status", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Request:", substring = true).assertCountEquals(0)

        pressBack()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("live_tv_player_screen").fetchSemanticsNode()
        pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("movies_screen").fetchSemanticsNode()
    }

    private fun clearRoomWatchlist() {
        runBlocking {
            val database = AtlasDatabaseProvider.database(targetContext)
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
              "playableLiveItems": [
                {"name":"Fixture Live One","categoryName":"Fixture News","streamId":1001,"containerExtension":"ts","redactedUrl":"fixture://debug_catalog/live/1001.ts"}
              ],
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

package com.atlas.atlastv.ui.home

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.MoviesWatchlistRoomStore
import com.atlas.atlastv.data.db.SeriesWatchlistRoomStore
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.series.SeriesWatchlistStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeRowsConnectedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        File(targetContext.filesDir, MoviesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, SeriesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, WatchProgressStore.FileName).delete()
        clearRoomWatchlist()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("hero_area").fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun tearDown() {
        scenario?.close()
        File(targetContext.filesDir, MoviesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, SeriesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, WatchProgressStore.FileName).delete()
        clearRoomWatchlist()
    }

    @Test
    fun emptyPersonalRowsCollapseAndBrowseRowsRemain() {
        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()
        composeRule.onAllNodesWithTag("watchlist_row").assertCountEquals(0)
        composeRule.onAllNodesWithTag("continue_watching_row").assertCountEquals(0)
        composeRule.onNodeWithTag("popular_row").fetchSemanticsNode()
        composeRule.onNodeWithTag("recently_added_movies_row").fetchSemanticsNode()
        composeRule.onNodeWithTag("recently_added_shows_row").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Fixture Movie One").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Source:", substring = true).assertCountEquals(0)
    }

    private fun clearRoomWatchlist() {
        runBlocking {
            val database = AtlasDatabaseProvider.database(targetContext)
            MoviesWatchlistRoomStore(database.movieWatchlistDao()).clear()
            SeriesWatchlistRoomStore(database.seriesWatchlistDao()).clear()
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
                {"title":"Fixture Movie One","categoryName":"EN - NEW RELEASE","streamId":2001,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/2001.mp4"},
                {"title":"Fixture Movie Two","categoryName":"EN - ACTION","streamId":2002,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/2002.mp4"}
              ],
              "playableSeriesItems": [
                {"name":"Fixture Series One","categoryName":"ENGLISH SERIES","seriesId":3001},
                {"name":"Fixture Series Two","categoryName":"DRAMA","seriesId":3002}
              ]
            }
        """.trimIndent()
    }
}

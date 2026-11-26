package com.atlas.atlastv.ui.movies

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MoviesWatchlistConnectedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
        MoviesWatchlistStore(File(targetContext.filesDir, MoviesWatchlistStore.FileName)).saveWatchlist(
            movies = listOf(MovieItem("Fixture Movie One", "Fixture Action", 2001, "mp4", "fixture://debug_catalog/movie/2001.mp4")),
            watchlistStreamIds = setOf(2001),
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, MoviesWatchlistStore.FileName).delete()
    }

    @Test
    fun watchlistRowRendersWhenPersistedWatchlistExists() {
        composeRule.onNodeWithTag("top_nav_item_movies").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("movies_row_page").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("movies_row_page").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Watchlist").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Fixture Movie One").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Source:", substring = true).assertCountEquals(0)
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
              ]
            }
        """.trimIndent()
    }
}

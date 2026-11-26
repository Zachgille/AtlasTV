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
class MoviesSortRestoreConnectedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario?.close()
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
    }

    @Test
    fun rowBrowseUsesCleanCategoryRowsAfterRelaunch() {
        openMovies()
        composeRule.onNodeWithTag("movies_row_page").fetchSemanticsNode()
        composeRule.onAllNodesWithText("New Releases").fetchSemanticsNodes()
        composeRule.onAllNodesWithText("Action").fetchSemanticsNodes()

        scenario?.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        openMovies()

        composeRule.onNodeWithTag("movies_row_page").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Source:", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Cache age", substring = true).assertCountEquals(0)
    }

    private fun openMovies() {
        composeRule.onNodeWithTag("top_nav_item_movies").performClick()
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("movies_row_page").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("movies_screen").fetchSemanticsNode()
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
                {"title":"Zeta Movie","categoryName":"EN - NEW RELEASE","streamId":3001,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/3001.mp4"},
                {"title":"Alpha Movie","categoryName":"EN - NEW RELEASE","streamId":3002,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/3002.mp4"},
                {"title":"Beta Movie","categoryName":"ACTION","streamId":3003,"containerExtension":"mkv","redactedUrl":"fixture://debug_catalog/movie/3003.mkv"},
                {"title":"Gamma Movie","categoryName":"ACTION","streamId":3004,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/3004.mp4"}
              ]
            }
        """.trimIndent()
    }
}

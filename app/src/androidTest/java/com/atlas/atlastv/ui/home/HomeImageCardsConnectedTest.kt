package com.atlas.atlastv.ui.home

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeImageCardsConnectedTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        watchProgressStore().saveRecords(
            listOf(
                WatchProgressRecord(
                    contentType = WatchProgressContentType.Movie,
                    streamId = 2001,
                    title = "Progress Movie",
                    categoryName = "Fixture Action",
                    extension = "mp4",
                    durationMs = 7_200_000L,
                    positionMs = 600_000L,
                    updatedAtMs = 20L,
                ),
            ),
        )
        scenario = ActivityScenario.launch(MainActivity::class.java)
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("hero_area").fetchSemanticsNodes().isNotEmpty() }
    }

    @After
    fun tearDown() {
        scenario?.close()
        watchProgressStore().clear()
    }

    @Test
    fun homePosterFallbacksAndHeroImageStateRender() {
        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()

        assertTrue(composeRule.onAllNodesWithTag("poster_card", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        val safeImages = composeRule.onAllNodesWithTag("loaded_poster_image_state", useUnmergedTree = true).fetchSemanticsNodes()
        val providerImages = composeRule.onAllNodesWithTag("provider_poster_image_state", useUnmergedTree = true).fetchSemanticsNodes()
        val fallbacks = composeRule.onAllNodesWithTag("fallback_poster_card", useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue(safeImages.isNotEmpty() || providerImages.isNotEmpty() || fallbacks.isNotEmpty())
        assertTrue(providerImages.isNotEmpty())
        assertTrue(
            composeRule.onAllNodesWithTag("hero_backdrop_image_state", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() ||
                composeRule.onAllNodesWithTag("hero_backdrop_fallback_state", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(composeRule.onAllNodesWithTag("card_progress_bar", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())

        composeRule.onNodeWithTag("top_nav_item_search").performKeyInput { pressKey(Key.DirectionDown) }
        if (composeRule.onAllNodesWithTag("catalog_refresh_prompt").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("catalog_refresh_prompt").performKeyInput { pressKey(Key.DirectionDown) }
        }
        composeRule.onNodeWithTag("focused_home_card").fetchSemanticsNode()
    }

    private fun watchProgressStore(): WatchProgressStore =
        WatchProgressStore(
            File(targetContext.filesDir, WatchProgressStore.FileName),
            WatchProgressRoomStore(AtlasDatabaseProvider.database(targetContext).watchProgressDao()),
        )

    private companion object {
        private val fixtureCacheJson = """
            {
              "catalogSource": "fixture",
              "generatedAtEpochMs": 1,
              "redactedUrl": "fixture://debug_catalog",
              "redactedHost": "fixture",
              "playableLiveItems": [],
              "playableVodItems": [
                {"title":"Fixture Movie One","categoryName":"Fixture Action","streamId":2001,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/2001.mp4","posterImage":"fixture://poster/movie-one"},
                {"title":"Fixture Movie Two","categoryName":"Fixture Drama","streamId":2002,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/2002.mp4","posterImage":"https://example.test/images/fixture-movie-two.jpg"}
              ],
              "playableSeriesItems": [
                {"name":"Fixture Series One","categoryName":"Fixture Series","seriesId":3001,"posterImage":"fixture://poster/series-one"}
              ]
            }
        """.trimIndent()
    }
}

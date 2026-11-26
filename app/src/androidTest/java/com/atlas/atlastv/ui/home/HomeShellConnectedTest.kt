package com.atlas.atlastv.ui.home

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.movies.MoviesPreferencesStore
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.series.SeriesPreferencesStore
import com.atlas.atlastv.ui.settings.ProviderSyncStatusSnapshot
import com.atlas.atlastv.ui.settings.ProviderSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeShellConnectedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext

    @get:Rule(order = 0)
    val seedRule = object : TestWatcher() {
        override fun starting(description: Description) {
            seedState()
        }
    }

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private fun seedState() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, SeriesPreferencesStore.FileName).delete()
        saveFreshProviderStatus()
        clearCatalog()
        MoviesWatchlistStore(File(targetContext.filesDir, MoviesWatchlistStore.FileName)).saveWatchlist(
            movies = listOf(
                MovieItem("Fixture Movie One", "Fixture Action", 2001, "mp4", "fixture://debug_catalog/movie/2001.mp4"),
                MovieItem("Fixture Movie Two", "Fixture Drama", 2002, "mp4", "fixture://debug_catalog/movie/2002.mp4"),
            ),
            watchlistStreamIds = setOf(2002),
        )
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
                WatchProgressRecord(
                    contentType = WatchProgressContentType.SeriesEpisode,
                    streamId = 4001,
                    seriesId = 3001,
                    title = "Progress Episode",
                    seriesTitle = "Fixture Series One",
                    episodeTitle = "Episode One",
                    categoryName = "Fixture Series",
                    extension = "mp4",
                    durationMs = 3_600_000L,
                    positionMs = 300_000L,
                    updatedAtMs = 10L,
                ),
            ),
        )
        File(targetContext.filesDir, WatchProgressStore.FileName).delete()
    }

    @After
    fun tearDown() {
        File(targetContext.filesDir, MoviesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, MoviesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, SeriesPreferencesStore.FileName).delete()
        File(targetContext.filesDir, ProviderSettingsStore.FileName).delete()
        clearCatalog()
        watchProgressStore().clear()
    }

    @Test
    fun homeShellRendersRowsAndExplicitContinueWatchingFocus() {
        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("top_nav").fetchSemanticsNode()
        composeRule.onNodeWithTag("top_nav_item_search").fetchSemanticsNode()
        composeRule.onNodeWithTag("top_nav_item_home").fetchSemanticsNode()
        composeRule.onNodeWithTag("top_nav_item_shows").fetchSemanticsNode()
        composeRule.onNodeWithTag("top_nav_item_movies").fetchSemanticsNode()
        composeRule.onNodeWithTag("top_nav_item_live_tv").fetchSemanticsNode()
        composeRule.onNodeWithTag("top_nav_item_settings").fetchSemanticsNode()
        composeRule.onNodeWithTag("hero_area").fetchSemanticsNode()
        composeRule.onNodeWithTag("watchlist_row").fetchSemanticsNode()
        composeRule.onNodeWithTag("continue_watching_row").fetchSemanticsNode()
        composeRule.onNodeWithTag("popular_row").fetchSemanticsNode()
        composeRule.onNodeWithTag("recently_added_movies_row").fetchSemanticsNode()
        composeRule.onNodeWithTag("recently_added_shows_row").fetchSemanticsNode()

        enterContent()
        composeRule.onNodeWithTag("focused_home_card").assertIsFocused()
        composeRule.onAllNodesWithText("Progress Movie").fetchSemanticsNodes()
        composeRule.onNodeWithTag("hero_title").fetchSemanticsNode()
        composeRule.onNodeWithTag("card_progress_resume_label").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Source:", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Cache age", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("debug", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test
    fun dpadFocusUpdatesHeroAndMovesUpToWatchlist() {
        enterContent()
        composeRule.onNodeWithTag("focused_home_card").performKeyInput { pressKey(Key.DirectionRight) }
        composeRule.onAllNodesWithText("Progress Episode").fetchSemanticsNodes()

        composeRule.onNodeWithTag("focused_home_card").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Fixture Movie Two").fetchSemanticsNodes()

        composeRule.onNodeWithTag("focused_home_card").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Progress Movie").fetchSemanticsNodes()
    }

    @Before
    fun awaitHome() {
        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("hero_area").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun enterContent() {
        composeRule.onNodeWithTag("top_nav_item_search").performKeyInput { pressKey(Key.DirectionDown) }
        if (composeRule.onAllNodesWithTag("catalog_refresh_prompt").fetchSemanticsNodes().isNotEmpty()) {
            composeRule.onNodeWithTag("catalog_refresh_prompt").performKeyInput { pressKey(Key.DirectionDown) }
        }
    }

    @Test
    fun topNavItemsOpenExistingSections() {
        composeRule.onNodeWithTag("top_nav_item_movies").performClick()
        composeRule.onNodeWithTag("movies_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("movies_back").performClick()

        composeRule.onNodeWithTag("top_nav_item_shows").performClick()
        composeRule.onNodeWithTag("series_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_back").performClick()

        composeRule.onNodeWithTag("top_nav_item_live_tv").performClick()
        composeRule.onNodeWithTag("live_tv_screen").fetchSemanticsNode()
    }

    @Test
    fun homeBackDoesNotExitOnSinglePress() {
        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()

        pressBack()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()
        composeRule.onAllNodesWithTag("home_back_protection").assertCountEquals(0)
    }

    private fun watchProgressStore(): WatchProgressStore =
        WatchProgressStore(
            File(targetContext.filesDir, WatchProgressStore.FileName),
            WatchProgressRoomStore(AtlasDatabaseProvider.database(targetContext).watchProgressDao()),
        )

    private fun clearCatalog() {
        runBlocking {
            SafeCatalogRepository(AtlasDatabaseProvider.database(targetContext).safeCatalogDao()).clearCatalog()
        }
    }

    private fun saveFreshProviderStatus() {
        val nowMs = System.currentTimeMillis()
        ProviderSettingsStore(File(targetContext.filesDir, ProviderSettingsStore.FileName)).saveSyncStatus(
            ProviderSyncStatusSnapshot(
                status = CatalogSyncStatus.Success,
                lastStartedAtMs = nowMs - 100L,
                lastCompletedAtMs = nowMs,
                lastSuccessfulSyncAtMs = nowMs,
                moviesImported = 2,
                seriesImported = 2,
            ),
        )
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

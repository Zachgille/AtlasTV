package com.atlas.atlastv.ui.navigation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.data.catalog.CatalogSyncStatus
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.MoviesWatchlistRoomStore
import com.atlas.atlastv.data.db.SafeCatalogRepository
import com.atlas.atlastv.data.db.SeriesWatchlistRoomStore
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import com.atlas.atlastv.ui.movies.MoviesWatchlistStore
import com.atlas.atlastv.ui.series.SeriesWatchlistStore
import com.atlas.atlastv.ui.settings.ProviderSyncStatusSnapshot
import com.atlas.atlastv.ui.settings.ProviderSettingsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TvNavigationPolishConnectedTest {
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
        File(targetContext.filesDir, MoviesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, SeriesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, WatchProgressStore.FileName).delete()
        File(targetContext.filesDir, ProviderSettingsStore.FileName).delete()
        clearRoomWatchlist()
        clearCatalog()
    }

    private fun seedState() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        File(targetContext.filesDir, MoviesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, SeriesWatchlistStore.FileName).delete()
        File(targetContext.filesDir, WatchProgressStore.FileName).delete()
        saveFreshProviderStatus()
        clearRoomWatchlist()
        clearCatalog()
    }

    @Test
    fun normalScreensHideDebugSourceAndCacheText() {
        resumeActivity()
        composeRule.onNodeWithTag("home_screen").fetchSemanticsNode()
        assertNormalTextHidden()

        composeRule.onNodeWithTag("top_nav_item_movies").performClick()
        composeRule.onNodeWithTag("movies_screen").fetchSemanticsNode()
        assertNormalTextHidden()
        pressBack()

        composeRule.onNodeWithTag("top_nav_item_shows").performClick()
        composeRule.onNodeWithTag("series_screen").fetchSemanticsNode()
        assertNormalTextHidden()
        pressBack()

        composeRule.onNodeWithTag("top_nav_item_live_tv").performClick()
        composeRule.onNodeWithTag("live_tv_screen").fetchSemanticsNode()
        assertNormalTextHidden()
    }

    @Test
    fun homeDpadMovesOneLogicalStepBetweenNavAndRows() {
        resumeActivity()
        composeRule.onNodeWithTag("focused_home_card").performSemanticsAction(SemanticsActions.RequestFocus)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("focused_home_card").assertIsFocused()

        composeRule.onNodeWithTag("focused_home_card").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("top_nav_item_search").assertIsFocused()

        composeRule.onNodeWithTag("top_nav_item_search").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("focused_home_card").assertIsFocused()
    }

    @Test
    fun settingsGatesAdvancedDiagnostics() {
        resumeActivity()
        composeRule.onNodeWithTag("top_nav_item_settings").performClick()
        composeRule.onNodeWithTag("settings_screen").fetchSemanticsNode()
        composeRule.onNodeWithTag("settings_section_advanced").performClick()
        composeRule.onNodeWithTag("advanced_diagnostics_entry").performClick()
        composeRule.onNodeWithTag("debug_loader_screen").fetchSemanticsNode()
    }

    private fun resumeActivity() {
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
    }

    private fun assertNormalTextHidden() {
        composeRule.onAllNodesWithText("Source:", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Cache age", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Stream ID", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("http://REDACTED_HOST", substring = true).assertCountEquals(0)
    }

    private fun clearRoomWatchlist() {
        runBlocking {
            val database = AtlasDatabaseProvider.database(targetContext)
            MoviesWatchlistRoomStore(database.movieWatchlistDao()).clear()
            SeriesWatchlistRoomStore(database.seriesWatchlistDao()).clear()
            WatchProgressRoomStore(database.watchProgressDao()).clear()
        }
    }

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
                moviesImported = 1,
                seriesImported = 1,
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
                {"title":"Fixture Movie One","categoryName":"Fixture Action","streamId":2001,"containerExtension":"mp4","redactedUrl":"fixture://debug_catalog/movie/2001.mp4"}
              ],
              "playableSeriesItems": [
                {"name":"Fixture Series One","categoryName":"Fixture Series","seriesId":3001}
              ]
            }
        """.trimIndent()
    }
}

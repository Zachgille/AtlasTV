package com.atlas.atlastv.ui.series

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.MainActivity
import com.atlas.atlastv.core.network.TextDownloadResult
import com.atlas.atlastv.core.network.TextDownloader
import com.atlas.atlastv.data.db.AtlasDatabaseProvider
import com.atlas.atlastv.data.db.WatchProgressRoomStore
import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.WatchProgressStore
import com.atlas.atlastv.ui.livetv.LiveTvCatalogStateHolder
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class SeriesEpisodesConnectedTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(Dispatchers.Main.immediate + Job())
    private val progressFile = File(targetContext.filesDir, WatchProgressStore.FileName)

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
        File(targetContext.filesDir, SeriesPreferencesStore.FileName).delete()
        watchProgressStore().clear()
    }

    private fun seedState() {
        File(targetContext.filesDir, LiveTvCatalogStateHolder.CacheFileName).writeText(fixtureCacheJson, Charsets.UTF_8)
        File(targetContext.filesDir, SeriesPreferencesStore.FileName).delete()
        watchProgressStore().clear()
        watchProgressStore().saveRecords(
            listOf(
                WatchProgressRecord(
                    contentType = WatchProgressContentType.SeriesEpisode,
                    streamId = 1001,
                    seriesId = 3001,
                    title = "Fixture Series One - Pilot",
                    seriesTitle = "Fixture Series One",
                    episodeTitle = "Pilot",
                    categoryName = "Fixture Series",
                    extension = "mp4",
                    durationMs = 3_600_000L,
                    positionMs = 600_000L,
                    updatedAtMs = 10L,
                ),
                WatchProgressRecord(
                    contentType = WatchProgressContentType.SeriesEpisode,
                    streamId = 1002,
                    seriesId = 3001,
                    title = "Fixture Series One - Second",
                    seriesTitle = "Fixture Series One",
                    episodeTitle = "Second",
                    categoryName = "Fixture Series",
                    extension = "mp4",
                    durationMs = 3_600_000L,
                    positionMs = 3_550_000L,
                    updatedAtMs = 20L,
                    completed = true,
                ),
            ),
        )
        progressFile.delete()
    }

    @Test
    fun seasonEpisodeBrowserShowsProgressAndSelectsEpisode() {
        val holder = SeriesCatalogStateHolder(
            context = targetContext,
            episodeLoader = SeriesEpisodeLoader(FakeDownloader(seriesInfoJson)),
            credentialsProvider = { XtreamCredentials("http://fixture.test", "user", "pass") },
            scope = scope,
        )
        val openedEpisodeId = mutableStateOf<Int?>(null)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        composeRule.activity.setContent {
            AtlasTvTheme {
                SeriesScreen(
                    stateHolder = holder,
                    onBack = {},
                    onEpisodeSelected = { _, episode -> openedEpisodeId.value = episode.episodeId },
                )
            }
        }

        composeRule.waitUntil(10_000) { composeRule.onAllNodesWithTag("series_row_page").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("series_search_open").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("focused_poster_card").performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag("series_episode_list").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("series_season_list").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_episode_list").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_detail_header").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_detail_hero").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_detail_title").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_selected_episode").fetchSemanticsNode()
        composeRule.onNodeWithTag("series_detail_play_episode").fetchSemanticsNode()
        composeRule.onAllNodesWithText("Resume S1 E1").fetchSemanticsNodes().first()
        composeRule.onNodeWithTag("series_selected_episode").fetchSemanticsNode()
        composeRule.onAllNodesWithTag("series_episode_progress_label", useUnmergedTree = true).fetchSemanticsNodes().first()

        composeRule.onAllNodesWithText("Resume at 10:00 (16%)").fetchSemanticsNodes().first()
        composeRule.onNodeWithTag("series_detail_play_episode").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag("series_selected_episode").assertIsFocused()
        composeRule.onNodeWithTag("series_selected_episode").performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Watched").fetchSemanticsNodes().first()
        composeRule.onNodeWithTag("series_selected_episode").assertIsFocused()
        composeRule.onNodeWithTag("series_selected_episode").performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.waitForIdle()
        composeRule.onAllNodesWithText("Resume at 10:00 (16%)").fetchSemanticsNodes().first()
        composeRule.onNodeWithTag("series_selected_episode").assertIsFocused()
        composeRule.onNodeWithTag("series_selected_episode").performClick()
        composeRule.waitForIdle()
        assertEquals(1001, openedEpisodeId.value)
    }

    private class FakeDownloader(private val text: String) : TextDownloader {
        override suspend fun download(url: String): TextDownloadResult =
            TextDownloadResult.Success(text = text, byteCount = text.length.toLong())
    }

    private fun watchProgressStore(): WatchProgressStore =
        WatchProgressStore(
            progressFile,
            WatchProgressRoomStore(AtlasDatabaseProvider.database(targetContext).watchProgressDao()),
        )

    private companion object {
        private val fixtureCacheJson = """
            {
              "catalogSource": "fixture",
              "generatedAtEpochMs": 1,
              "playableSeriesItems": [
                {"name":"Fixture Series One","categoryName":"Fixture Series","seriesId":3001}
              ]
            }
        """.trimIndent()

        private val seriesInfoJson = """
            {
              "episodes": {
                "1": [
                  { "id": "1001", "episode_num": "1", "title": "Pilot", "container_extension": "mp4" },
                  { "id": "1002", "episode_num": "2", "title": "Second", "container_extension": "mp4" },
                  { "id": "1003", "episode_num": "3", "title": "Third", "container_extension": "mp4" }
                ]
              }
            }
        """.trimIndent()
    }
}

package com.atlas.atlastv.ui.series

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.EpisodeRowsCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EpisodeRowsConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<EpisodeRowsCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(12_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun rowChild(tag: String) = compose.onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag("series_selected_episode")), useUnmergedTree = true)
    private fun selectFirst() {
        waitFor("series_episode_list"); focused("series_detail_play_episode")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode")
        // Opening details honors the latest real fixture checkpoint (episode 13).
        // These row checks deliberately inspect the first episode through normal navigation.
        val remembered = (compose.activity.holder.state as SeriesCatalogState.Ready).selectedEpisodeId
        val index = compose.activity.episodes.indexOfFirst { it.episodeId == remembered }
        assertTrue("Unexpected fixture selection: $remembered", index in 0..2)
        repeat(index) { native(KeyEvent.KEYCODE_DPAD_UP) }
        focused("series_selected_episode")
        assertEquals(11, (compose.activity.holder.state as SeriesCatalogState.Ready).selectedEpisodeId)
    }
    @Test fun compactRuntimeAndRealProgressFitTheSelectedRowWithoutResumeSentence() {
        selectFirst()
        rowChild("series_episode_duration").assertTextEquals("42m").assertIsDisplayed()
        rowChild("series_episode_progress_label").assertTextEquals("32m left").assertIsDisplayed()
        val bar = rowChild("card_progress_bar").fetchSemanticsNode()
        assertEquals(0.25f, bar.config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        val fill = rowChild("card_progress_fill").fetchSemanticsNode()
        assertEquals(0.25f, fill.boundsInRoot.width / bar.boundsInRoot.width, 0.015f)
        val row = compose.onNodeWithTag("series_selected_episode").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithTag("series_episode_12").fetchSemanticsNode().boundsInRoot
        val viewport = compose.onNodeWithTag("series_episode_list").fetchSemanticsNode().boundsInRoot
        assertTrue("Two complete episodes must fit", second.bottom <= viewport.bottom)
        for (tag in listOf("series_episode_thumbnail", "series_episode_name", "series_episode_duration", "series_episode_progress_label")) {
            val bounds = rowChild(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= row.left && bounds.right <= row.right && bounds.top >= row.top && bounds.bottom <= row.bottom)
        }
        compose.onAllNodes(hasText("Resume at", substring = true)).assertCountEquals(0)
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        java.io.File(compose.activity.cacheDir, "episode-rows-controlled.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }
    @Test fun completedUnknownAndUnstartedEpisodesUseHonestCompactStates() {
        selectFirst(); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode")
        rowChild("series_episode_progress_label").assertTextEquals("Watched")
        assertEquals(1f, rowChild("card_progress_bar").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        native(KeyEvent.KEYCODE_DPAD_DOWN); rowChild("series_episode_progress_label").assertTextEquals("At 2:00")
        rowChild("series_episode_duration").assertDoesNotExist(); rowChild("card_progress_bar").assertDoesNotExist()
        native(KeyEvent.KEYCODE_DPAD_DOWN); rowChild("series_episode_progress_label").assertDoesNotExist()
        rowChild("card_progress_bar").assertDoesNotExist(); rowChild("series_episode_duration").assertTextEquals("42m")
    }
    @Test fun checkpointRuntimeWinsAndInvalidFallbackStaysAbsentWithoutMovingFocus() {
        selectFirst()
        compose.runOnIdle { compose.activity.replaceRuntimes(60_000, 2_520_000) }
        compose.waitUntil(8_000) { runCatching { rowChild("series_episode_duration").assertTextEquals("42m"); true }.getOrDefault(false) }
        rowChild("series_episode_progress_label").assertTextEquals("32m left"); focused("series_selected_episode")
        compose.runOnIdle { compose.activity.replaceRuntimes(Long.MAX_VALUE, null) }
        compose.waitUntil(8_000) { runCatching {
            rowChild("series_episode_duration").assertDoesNotExist()
            rowChild("series_episode_progress_label").assertTextEquals("At 10:30"); true
        }.getOrDefault(false) }
        rowChild("card_progress_bar").assertDoesNotExist(); focused("series_selected_episode")
        assertEquals(630_000L, compose.activity.records.first { it.streamId == 11 }.positionMs)
    }
    @Test fun decodedThumbnailArrivalAndCheckpointRefreshCannotStealRemoteFocus() {
        selectFirst(); rowChild("detail_artwork_fallback").assertIsDisplayed()
        compose.runOnIdle { compose.activity.revealThumbnail() }
        waitFor("loaded_detail_backdrop"); rowChild("loaded_detail_backdrop").assertIsDisplayed()
        focused("series_selected_episode")
        compose.runOnIdle { compose.activity.updateProgress() }
        compose.waitUntil(8_000) { runCatching { rowChild("series_episode_progress_label").assertTextEquals("21m left"); true }.getOrDefault(false) }
        focused("series_selected_episode")
        assertEquals(0.5f, rowChild("card_progress_bar").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
    }
    @Test fun everySeasonDeepEpisodeAndReturnStillUseTheExactSelectedEpisode() {
        selectFirst(); native(KeyEvent.KEYCODE_DPAD_LEFT); focused("series_season_1")
        repeat(23) { native(KeyEvent.KEYCODE_DPAD_DOWN) }
        focused("series_season_24"); native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("series_selected_episode")
        repeat(63) { native(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.runOnIdle { assertEquals(24064, (compose.activity.holder.state as SeriesCatalogState.Ready).selectedEpisodeId) }
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("episode_rows_destination")
        compose.runOnIdle { assertEquals(24064, compose.activity.openedEpisodeId) }
        native(KeyEvent.KEYCODE_BACK); waitFor("series_episode_list"); focused("series_detail_play_episode")
        native(KeyEvent.KEYCODE_DPAD_DOWN); focused("series_selected_episode")
        rowChild("series_episode_name").assertTextEquals("Episode 64").assertIsDisplayed()
        native(KeyEvent.KEYCODE_DPAD_LEFT); focused("series_season_24")
    }
}

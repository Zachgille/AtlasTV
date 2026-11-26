package com.atlas.atlastv.ui.home

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.CardProgressCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class CardProgressConnectedTest {
    @Test fun rapidPosterTraversalThenImmediateHeroPlayUsesTheNewSelection() {
        ready()
        native(KeyEvent.KEYCODE_DPAD_DOWN)
        focused("home_hero_primary")
        native(KeyEvent.KEYCODE_DPAD_DOWN)
        focused("focused_home_card")
        val input = InstrumentationRegistry.getInstrumentation()
        // No wait/clock settling between keys: Play must not use the old debounced preview.
        listOf(KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_CENTER).forEach(input::sendKeyDownUpSync)
        waitFor("card_progress_return")
        assertEquals(2, compose.activity.selectedId)
        compose.onNodeWithTag("card_progress_return").performClick()
        ready()
        compose.waitUntil(8_000) {
            compose.onNodeWithTag("hero_title", useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.Text]
                .any { it.text == "Unknown Duration" }
        }
    }
    @get:Rule val compose = createAndroidComposeRule<CardProgressCheckActivity>()
    private fun waitFor(tag: String) { compose.waitUntil(12_000) { compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() } }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun focused(tag: String) { compose.waitUntil(8_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun ready() { waitFor("card_time_label") }
    private fun bars() = compose.onAllNodesWithTag("card_progress_bar", useUnmergedTree = true)
    private fun label(text: String) = compose.onNode(hasTestTag("card_time_label") and hasText(text), useUnmergedTree = true)
    @Test fun realCheckpointBarsAndShortLabelsFitTheCompleteHomeRow() {
        ready(); bars().assertCountEquals(3)
        label("45m left").assertIsDisplayed(); label("30m left").assertIsDisplayed(); label("At 2:00").assertIsDisplayed(); label("27m left").assertIsDisplayed()
        compose.onNodeWithTag("card_episode_label", useUnmergedTree = true).assertTextEquals("S1 E2").assertIsDisplayed()
        assertEquals(listOf(0.25f, 0.25f, 0.1f), bars().fetchSemanticsNodes().map { it.config[SemanticsProperties.ProgressBarRangeInfo].current })
        val fills = compose.onAllNodesWithTag("card_progress_fill", useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, bars().fetchSemanticsNodes().map { it.boundsInRoot.top }.distinct().size)
        for ((index, node) in bars().fetchSemanticsNodes().withIndex()) assertEquals(node.config[SemanticsProperties.ProgressBarRangeInfo].current,
            fills[index].boundsInRoot.width / node.boundsInRoot.width, 0.015f)
        val row = compose.onNodeWithTag("continue_watching_row").fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag("home_screen").fetchSemanticsNode().boundsInRoot
        assertTrue(row.bottom <= screen.bottom)
        for (tag in listOf("card_time_label", "card_episode_label")) {
            val nodes = compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            for (index in nodes.fetchSemanticsNodes().indices) {
                val layouts = mutableListOf<TextLayoutResult>()
                nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue(layouts.isNotEmpty()); assertFalse("$tag must not be truncated", layouts.single().hasVisualOverflow)
            }
        }
        compose.onAllNodes(hasText("Resume at", substring = true)).assertCountEquals(0)
        val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.cacheDir, "card-progress-controlled.png").outputStream().use { capture.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; capture.recycle()
    }
    @Test fun unknownDurationAndUnnumberedEpisodeKeepUsefulTimeWithoutFakeBarOrCode() {
        ready()
        val unknown = hasAnyAncestor(hasTestTag("home_card_continue_watching-movie-2"))
        compose.onAllNodes(hasTestTag("card_progress_bar") and unknown, useUnmergedTree = true).assertCountEquals(0)
        compose.onNode(hasTestTag("card_time_label") and unknown, useUnmergedTree = true).assertTextEquals("At 2:00")
        val episode = hasAnyAncestor(hasTestTag("home_card_continue_watching-show-4"))
        compose.onAllNodes(hasTestTag("card_episode_label") and episode, useUnmergedTree = true).assertCountEquals(0)
        compose.onNode(hasTestTag("card_time_label") and episode, useUnmergedTree = true).assertTextEquals("27m left")
    }
    @Test fun checkpointRefreshUpdatesTheBarWithoutMovingRemoteFocus() {
        ready(); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("home_hero_primary"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_home_card")
        compose.runOnIdle { compose.activity.records = compose.activity.records.map { if (it.streamId == 1) it.copy(positionMs = 1_800_000) else it }; compose.activity.home.refresh() }
        compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag("card_time_label") and hasText("30m left"), useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        focused("focused_home_card")
        val node = compose.onNode(hasTestTag("card_progress_bar") and hasAnyAncestor(hasTestTag("focused_home_card")), useUnmergedTree = true).fetchSemanticsNode()
        assertEquals(0.5f, node.config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
    }
    @Test fun shortEpisodeLabelDoesNotChangeResumeTargetOrExactCardReturn() {
        ready(); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("home_hero_primary"); native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_RIGHT)
        focused("focused_home_card"); compose.onNodeWithTag("hero_title").assertTextEquals("A Show")
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("card_progress_return"); assertEquals(31, compose.activity.selectedId)
        compose.onNodeWithTag("card_progress_return").performClick(); focused("focused_home_card")
        compose.onNodeWithTag("hero_title").assertTextEquals("A Show")
        compose.onNode(hasTestTag("card_episode_label") and hasAnyAncestor(hasTestTag("focused_home_card")), useUnmergedTree = true).assertTextEquals("S1 E2")
    }
    @Test fun newResumeEpisodeKeepsOneShowCardFocusedAndReturnsToThatExactShow() {
        ready(); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("home_hero_primary")
        native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_RIGHT); focused("focused_home_card")
        compose.onNodeWithTag("hero_title").assertTextEquals("A Show")
        val original = compose.activity.records
        val newer = original.single { it.streamId == 31 }.copy(streamId = 32,
            title = "A Show - S01E03", episodeTitle = "A Show - S01E03", updatedAtMs = 8)
        compose.runOnIdle { compose.activity.records = original + newer; compose.activity.home.refresh() }
        compose.waitUntil(8_000) { compose.onAllNodes(hasTestTag("card_episode_label") and hasText("S1 E3"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        focused("focused_home_card")
        compose.onNodeWithTag("hero_title").assertTextEquals("A Show")
        val cards = (compose.activity.home.state as HomeShellState.Ready).rows.first { it.id == HomeRowIds.ContinueWatching }.cards
        assertEquals(1, cards.count { it.seriesId == 3 })
        assertEquals(32, cards.single { it.seriesId == 3 }.streamId)
        assertEquals(original + newer, compose.activity.records)
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("card_progress_return")
        assertEquals(32, compose.activity.selectedId)
        compose.onNodeWithTag("card_progress_return").performClick(); focused("focused_home_card")
        compose.onNodeWithTag("hero_title").assertTextEquals("A Show")
        compose.onNode(hasTestTag("card_episode_label") and hasAnyAncestor(hasTestTag("focused_home_card")), useUnmergedTree = true).assertTextEquals("S1 E3")
    }
    @Test fun movieBrowseUsesTheSameAccurateBarAndShortTime() {
        ready(); compose.onNodeWithTag("top_nav_item_movies").performClick(); waitFor("movies_row_page")
        compose.onNodeWithTag("card_time_label", useUnmergedTree = true).assertTextEquals("45m left")
        assertEquals(0.25f, bars().fetchSemanticsNodes().single().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        focused("movies_search_open"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_poster_card")
        native(KeyEvent.KEYCODE_DPAD_CENTER); waitFor("movie_detail_play")
        compose.onNodeWithTag("movie_detail_resume_label").assertTextContains("Resume at 15:00", substring = true)
        native(KeyEvent.KEYCODE_BACK); focused("focused_poster_card")
    }
    @Test fun showBrowseUsesTheLatestEligibleEpisodeAndDoesNotLoadEpisodesToRenderProgress() {
        ready(); compose.onNodeWithTag("top_nav_item_shows").performClick(); waitFor("series_row_page")
        compose.onNodeWithTag("card_time_label", useUnmergedTree = true).assertTextEquals("30m left")
        compose.onNodeWithTag("card_episode_label", useUnmergedTree = true).assertTextEquals("S1 E2")
        assertEquals(0.25f, bars().fetchSemanticsNodes().single().config[SemanticsProperties.ProgressBarRangeInfo].current, 0f)
        compose.onNodeWithTag("series_episode_list").assertDoesNotExist()
        focused("series_search_open"); native(KeyEvent.KEYCODE_DPAD_DOWN); focused("focused_poster_card")
        native(KeyEvent.KEYCODE_DPAD_UP); focused("series_search_open")
    }
    @Test fun cleanupShowsCompactEpisodeTimeAndLeavesTheOtherCheckpointUntouched() {
        ready(); compose.onNodeWithTag("continue_watching_edit").performClick(); waitFor("continue_watching_editor")
        compose.onNodeWithText("S1 E2 · 30m left").assertIsDisplayed()
        compose.onNodeWithTag("continue_watching_remove_continue_watching-show-3").performClick()
        compose.waitUntil(8_000) { compose.activity.records.size == 3 }
        assertEquals(900_000L, compose.activity.records.first { it.streamId == 1 }.positionMs)
        compose.onNodeWithTag("continue_watching_done").performClick(); ready()
        compose.onAllNodesWithTag("card_episode_label", useUnmergedTree = true).assertCountEquals(0)
        assertNull(compose.activity.selectedId)
    }
}

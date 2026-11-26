package com.atlas.atlastv.ui.navigation

import android.content.Intent
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.NextEpisodeCheckActivity
import com.atlas.atlastv.playback.NextEpisodeState
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.ui.series.SeriesCatalogState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real local-video transitions; all progress is in unique cache files, without provider/personal-data traffic. */
class NextEpisodeConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(initial: Int = 11, stall: Boolean = false, fail: Boolean = false) =
        ActivityScenario.launch<NextEpisodeCheckActivity>(Intent(ApplicationProvider.getApplicationContext(), NextEpisodeCheckActivity::class.java)
            .putExtra("initial_episode", initial).putExtra("stall_lookup", stall).putExtra("fail_lookup", fail))
    private fun waitFor(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun ready() {
        waitFor("playback_progress_label")
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Pause").fetchSemanticsNodes().isNotEmpty() }
        native(KeyEvent.KEYCODE_MEDIA_PAUSE)
        waitFor("normal_playback_overlay")
        compose.onNodeWithTag("playback_play_pause_control").assertIsFocused()
    }
    private fun next() {
        waitFor("playback_next_episode_control")
        native(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithTag("playback_next_episode_control").assertIsFocused()
        native(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun saved(activity: NextEpisodeCheckActivity): List<WatchProgressRecord> = runBlocking {
        activity.writer.awaitSaved(); activity.store.loadAsync().records
    }
    private fun returnToDetails(activity: NextEpisodeCheckActivity, id: Int) {
        native(KeyEvent.KEYCODE_BACK)
        native(KeyEvent.KEYCODE_BACK)
        waitFor("series_episode_list")
        compose.runOnIdle { assertEquals(id, (activity.seriesHolder.state as SeriesCatalogState.Ready).selectedEpisodeId) }
    }

    @Test fun nativeNextCapturesOldCheckpointResumesSavedNextAndReturnsToThatEpisode() {
        launch().use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity
            scenario.onActivity { activity = it }
            ready(); next(); ready()
            compose.runOnIdle { assertEquals(12, activity.currentEpisodeId); assertEquals(1, activity.transitions) }
            val old = saved(activity).first { it.streamId == 11 }
            assertTrue("Current checkpoint ${old.positionMs}", old.positionMs in 255_000..280_000)
            assertFalse(old.completed)
            compose.onNodeWithTag("playback_progress_label").assertTextContains("3:0", substring = true)
            returnToDetails(activity, 12)
            val second = saved(activity).first { it.streamId == 12 }
            assertTrue(second.positionMs in 180_000..200_000)
            assertFalse(second.completed)
            assertTrue(saved(activity).first { it.streamId == 11 }.positionMs >= 255_000)
        }
    }

    @Test fun crossesSeasonStartsNewEpisodeAndOffersNoNextAfterFinalEpisode() {
        launch(initial = 12).use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            ready(); next(); ready()
            compose.runOnIdle { assertEquals(21, activity.currentEpisodeId); assertEquals(NextEpisodeState.End, activity.next.state) }
            compose.onNodeWithTag("playback_next_episode_control").assertDoesNotExist()
            assertFalse(saved(activity).first { it.streamId == 12 }.completed)
            returnToDetails(activity, 21)
        }
    }

    @Test fun finalEpisodeStillHasPauseSeekAndBackWithoutJumpingToFirstEpisode() {
        launch(initial = 21).use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            ready()
            compose.runOnIdle { assertEquals(NextEpisodeState.End, activity.next.state) }
            compose.onNodeWithTag("playback_next_episode_control").assertDoesNotExist()
            native(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithTag("playback_seek_feedback").assertIsDisplayed()
            returnToDetails(activity, 21)
            assertEquals(0, activity.transitions)
        }
    }

    @Test fun optionalLookupDoesNotDelayVideoAndArrivalDoesNotStealFocus() {
        launch(stall = true).use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            ready()
            compose.runOnIdle { assertEquals(NextEpisodeState.Loading, activity.next.state) }
            compose.onNodeWithTag("playback_next_episode_control").assertDoesNotExist()
            compose.runOnIdle { activity.lookupReleased = true }
            waitFor("playback_next_episode_control")
            compose.onNodeWithTag("playback_play_pause_control").assertIsFocused()
            native(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithTag("playback_next_episode_control").assertIsFocused()
            native(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithTag("playback_play_pause_control").assertIsFocused()
            assertEquals(0, activity.transitions)
        }
    }

    @Test fun failedOptionalLookupCanRetryWithoutStoppingOrRestartingCurrentVideo() {
        launch(fail = true).use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            ready(); waitFor("playback_next_episode_control")
            compose.onNodeWithText("Retry Next").assertIsDisplayed()
            compose.runOnIdle { activity.lookupFails = false }
            native(KeyEvent.KEYCODE_DPAD_DOWN); native(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(10_000) { activity.next.state is NextEpisodeState.Available }
            compose.onNodeWithText("Next Episode").assertIsDisplayed()
            compose.onNodeWithTag("playback_next_episode_control").assertIsFocused()
            assertEquals(0, activity.transitions)
            assertEquals(11, activity.currentEpisodeId)
            compose.onNodeWithText("Play").assertExists()
        }
    }

    @Test fun repeatedCenterDuringTransitionCannotSkipAnExtraEpisode() {
        launch().use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            ready(); waitFor("playback_next_episode_control"); native(KeyEvent.KEYCODE_DPAD_DOWN)
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            instrumentation.sendKeySync(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            compose.waitForIdle(); ready()
            assertEquals(1, activity.transitions); assertEquals(12, activity.currentEpisodeId)
        }
    }

    @Test fun nextRetainsAlreadyCompletedProgressRatherThanResettingItOnDisposal() {
        launch(initial = 12).use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            ready()
            repeat(13) { compose.onNodeWithTag("normal_playback_overlay").performKeyInput { pressKey(Key.DirectionRight) } }
            compose.waitUntil(5_000) { saved(activity).first { it.streamId == 12 }.completed }
            next(); ready(); returnToDetails(activity, 21)
            assertTrue(saved(activity).first { it.streamId == 12 }.completed)
        }
    }
}

package com.atlas.atlastv.ui.navigation

import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.NextEpisodeCheckActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Natural end in generated video; cache-only progress. No provider or personal account/catalog writes. */
class EpisodeAutoplayConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch(initial: Int = 11, autoplay: Boolean = true, stalled: Boolean = false) =
        ActivityScenario.launch<NextEpisodeCheckActivity>(Intent(ApplicationProvider.getApplicationContext(), NextEpisodeCheckActivity::class.java)
            .putExtra("initial_episode", initial).putExtra("autoplay", autoplay).putExtra("near_end", true).putExtra("stall_lookup", stalled))
    private fun waitFor(tag: String) = compose.waitUntil(20_000) { runCatching { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
    private fun native(key: Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(key); compose.waitForIdle() }
    private fun saved(activity: NextEpisodeCheckActivity) = runBlocking { activity.writer.awaitSaved(); activity.store.loadAsync().records }

    @Test fun naturalEndCountsDownOnceResumesNextAndPreservesCompletedPreviousEpisode() {
        launch().use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            waitFor("playback_autoplay_prompt")
            compose.onNodeWithTag("playback_autoplay_cancel").assertIsFocused()
            compose.waitUntil(15_000) { activity.transitions == 1 }
            waitFor("playback_progress_label"); native(KeyEvent.KEYCODE_MEDIA_PAUSE)
            assertEquals(12, activity.currentEpisodeId)
            compose.waitUntil(5_000) { activity.next.state is com.atlas.atlastv.playback.NextEpisodeState.Available }
            assertTrue(saved(activity).first { it.streamId == 11 }.completed)
            scenario.moveToState(Lifecycle.State.CREATED)
            val next = saved(activity).first { it.streamId == 12 }
            assertTrue("Next resume ${next.positionMs}", next.positionMs in 180_000..205_000)
            assertFalse(next.completed)
        }
    }

    @Test fun cancelWithNativeCenterStaysAtTheFinishedEpisodeAndRestoresControls() {
        launch().use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            waitFor("playback_autoplay_prompt")
            val prompt = compose.onNodeWithTag("playback_autoplay_prompt").fetchSemanticsNode().boundsInRoot
            val screen = compose.onNodeWithTag("live_tv_player_screen").fetchSemanticsNode().boundsInRoot
            assertTrue(prompt.right <= screen.right && prompt.bottom <= screen.bottom)
            compose.onNodeWithTag("playback_autoplay_cancel").assertIsFocused()
            val capture = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            File(activity.cacheDir, "autoplay-refined.png").outputStream().use { capture.compress(Bitmap.CompressFormat.PNG, 100, it) }; capture.recycle()
            native(KeyEvent.KEYCODE_DPAD_CENTER)
            waitFor("normal_playback_overlay")
            compose.onNodeWithTag("playback_play_pause_control").assertIsFocused()
            Thread.sleep(10_500)
            assertEquals(0, activity.transitions); assertEquals(11, activity.currentEpisodeId)
            compose.onNodeWithTag("playback_autoplay_prompt").assertDoesNotExist()
        }
    }

    @Test fun playNowCrossesSeasonsWithoutWaitingAndRepeatedCenterCannotSkipAgain() {
        launch(initial = 12).use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            waitFor("playback_autoplay_prompt"); native(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("playback_autoplay_play_now").assertIsFocused()
            native(KeyEvent.KEYCODE_DPAD_CENTER); native(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.waitUntil(8_000) { activity.transitions == 1 }; waitFor("playback_progress_label")
            assertEquals(21, activity.currentEpisodeId)
            assertTrue(saved(activity).first { it.streamId == 12 }.completed)
        }
    }

    @Test fun backgroundingCancelsAndReturningDoesNotLaunchAnOverdueEpisode() {
        launch().use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            waitFor("playback_autoplay_prompt"); scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(10_500); assertEquals(0, activity.transitions)
            scenario.moveToState(Lifecycle.State.RESUMED); waitFor("normal_playback_overlay")
            compose.onNodeWithTag("playback_autoplay_prompt").assertDoesNotExist()
            assertEquals(11, activity.currentEpisodeId)
        }
    }

    @Test fun backCancelsBeforeUsualPlayerExitAndMediaPauseAlsoCancels() {
        launch().use {
            waitFor("playback_autoplay_prompt"); native(KeyEvent.KEYCODE_BACK); waitFor("normal_playback_overlay")
            native(KeyEvent.KEYCODE_BACK); native(KeyEvent.KEYCODE_BACK); waitFor("series_episode_list")
        }
        launch().use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            waitFor("playback_autoplay_prompt"); native(KeyEvent.KEYCODE_MEDIA_PAUSE); waitFor("normal_playback_overlay")
            Thread.sleep(10_500); assertEquals(0, activity.transitions)
        }
    }

    @Test fun defaultOffAndFinalEpisodeNeverShowAutoplayPrompt() {
        launch(autoplay = false).use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            waitFor("live_tv_player_screen"); Thread.sleep(4_000)
            compose.onNodeWithTag("playback_autoplay_prompt").assertDoesNotExist()
            compose.onNodeWithTag("playback_next_episode_control").assertExists(); assertEquals(0, activity.transitions)
        }
        launch(initial = 21).use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            waitFor("live_tv_player_screen"); Thread.sleep(4_000)
            compose.onNodeWithTag("playback_autoplay_prompt").assertDoesNotExist()
            compose.onNodeWithTag("playback_next_episode_control").assertDoesNotExist(); assertEquals(0, activity.transitions)
        }
    }

    @Test fun lateLookupAfterEndGetsAFullCountdownRatherThanImmediateTransition() {
        launch(stalled = true).use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            waitFor("live_tv_player_screen"); Thread.sleep(5_000)
            assertEquals(0, activity.transitions); compose.onNodeWithTag("playback_autoplay_prompt").assertDoesNotExist()
            compose.runOnIdle { activity.lookupReleased = true }
            waitFor("playback_autoplay_prompt")
            compose.onNodeWithTag("playback_autoplay_countdown").assertTextContains("10s", substring = true)
            native(KeyEvent.KEYCODE_BACK); assertEquals(0, activity.transitions)
        }
    }

    @Test fun turningPreferenceOffDuringPromptCancelsWithoutRearming() {
        launch().use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            waitFor("playback_autoplay_prompt"); compose.runOnIdle { activity.autoplayEnabled = false }
            waitFor("normal_playback_overlay"); compose.runOnIdle { activity.autoplayEnabled = true }
            Thread.sleep(10_500); assertEquals(0, activity.transitions)
            compose.onNodeWithTag("playback_autoplay_prompt").assertDoesNotExist()
        }
    }
    @Test fun catalogInvalidationDuringCountdownReloadsNextAndOffersAFullNewPrompt() {
        launch().use { scenario ->
            lateinit var activity: NextEpisodeCheckActivity; scenario.onActivity { activity = it }
            waitFor("playback_autoplay_prompt")
            compose.runOnIdle { activity.lookupReleased = false; activity.next.deactivate(clearCache = true) }
            waitFor("normal_playback_overlay")
            assertEquals(0, activity.transitions)
            compose.runOnIdle { activity.lookupReleased = true }
            waitFor("playback_autoplay_prompt")
            compose.onNodeWithTag("playback_autoplay_countdown").assertTextContains("10s", substring = true)
            native(KeyEvent.KEYCODE_BACK); assertEquals(0, activity.transitions)
        }
    }
}

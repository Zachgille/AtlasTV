package com.atlas.atlastv.ui.navigation

import android.content.Intent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.atlas.atlastv.debug.PlaybackProgressCheckActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.io.File

/** Uses a generated local video, named cache files and a separate Room database only. */
class PlaybackProgressConnectedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun launch() = ActivityScenario.launch<PlaybackProgressCheckActivity>(
        Intent(ApplicationProvider.getApplicationContext(), PlaybackProgressCheckActivity::class.java)
            .putExtra("reset", true).putExtra("check_id", UUID.randomUUID().toString()),
    )
    private fun ready() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("playback_progress_label").fetchSemanticsNodes().isNotEmpty() }
        // The timer now shows the saved position immediately, before preparation finishes.
        // Wait for actual playing state before testing pause/seek/storage captures.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Pause", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("playback_progress_label").fetchSemanticsNodes().any { node ->
            node.config[SemanticsProperties.Text].any { it.text.matches(Regex("4:[1-5][0-9] / 10:00")) }
        } }
    }
    private fun saved(activity: PlaybackProgressCheckActivity) = runBlocking { activity.writer.awaitSaved(); activity.store.loadAsync().records.single() }

    @Test fun backgroundPausesAndReturnKeepsResumeThenExitCannotOverwriteWithZero() {
        launch().use { scenario ->
            var activity: PlaybackProgressCheckActivity? = null
            scenario.onActivity { activity = it }
            ready()
            scenario.moveToState(Lifecycle.State.CREATED)
            val first = saved(activity!!)
            assertTrue(first.positionMs in 255_000..275_000)
            Thread.sleep(1_200)
            assertEquals(first.positionMs, saved(activity!!).positionMs)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            compose.waitUntil(8_000) {
                runBlocking { activity!!.store.loadAsync().records.firstOrNull()?.positionMs ?: 0 } > first.positionMs
            }
            scenario.close()
            val after = saved(activity!!)
            assertTrue(after.positionMs >= first.positionMs)
            assertFalse(after.completed)
        }
    }

    @Test fun rewindIsNewerProgressAndSurvivesPlayerDisposal() {
        launch().use { scenario ->
            var activity: PlaybackProgressCheckActivity? = null
            scenario.onActivity { activity = it }
            ready()
            val time = compose.onNodeWithTag("playback_progress_label").fetchSemanticsNode().config[SemanticsProperties.Text].single().text.substringBefore(" / ").split(":")
            val before = (time[0].toLong() * 60 + time[1].toLong()) * 1_000
            compose.onNodeWithTag("normal_playback_overlay").performKeyInput { pressKey(Key.DirectionLeft) }
            val rewound = saved(activity!!)
            assertTrue("Rewind checkpoint ${rewound.positionMs} from $before", rewound.positionMs in (before - 10_000)..(before - 7_000))
            scenario.close()
            val after = saved(activity!!)
            assertTrue(after.positionMs in rewound.positionMs..(rewound.positionMs + 5_000))
            assertFalse(after.completed)
        }
    }

    @Test fun manuallyPausedPlaybackStaysPausedAfterBackgroundAndReturn() {
        launch().use { scenario ->
            var activity: PlaybackProgressCheckActivity? = null
            scenario.onActivity { activity = it }
            ready()
            compose.onNodeWithTag("playback_play_pause_control").performKeyInput { pressKey(Key.Enter) }
            compose.onNodeWithText("Play", substring = false).assertExists()
            scenario.moveToState(Lifecycle.State.CREATED)
            val first = saved(activity!!)
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithText("Play", substring = false).assertExists()
            Thread.sleep(1_200)
            scenario.close()
            assertEquals(first.positionMs, saved(activity!!).positionMs)
        }
    }

    @Test fun completionSurvivesShutdownAndIsRemovedFromContinueWatching() {
        launch().use { scenario ->
            var activity: PlaybackProgressCheckActivity? = null
            scenario.onActivity { activity = it }
            ready()
            repeat(12) { compose.onNodeWithTag("normal_playback_overlay").performKeyInput { pressKey(Key.DirectionRight) } }
            compose.waitUntil(8_000) { saved(activity!!).completed }
            scenario.close()
            assertTrue(saved(activity!!).completed)
            assertTrue(runBlocking { activity!!.store.loadAsync().continueWatching().isEmpty() })
        }
    }

    @Test fun exportFailureShowsSafeNonmodalRetryAndRecoversWithoutLosingProgress() {
        launch().use { scenario ->
            var activity: PlaybackProgressCheckActivity? = null
            scenario.onActivity { activity = it }
            ready()
            saved(activity!!)
            val file = File(activity!!.store.identity)
            assertTrue(file.delete()); assertTrue(file.mkdir())
            val sentinel = File(file, "sentinel").apply { writeText("kept") }
            compose.onNodeWithTag("normal_playback_overlay").performKeyInput { pressKey(Key.DirectionRight) }
            compose.waitUntil(8_000) { activity!!.writer.saveFailed.value }
            compose.onNodeWithTag("progress_save_retrying").assertExists()
            compose.onNodeWithTag("playback_play_pause_control").assertIsFocused()
            val pending = runBlocking { activity!!.writer.loadSnapshot().records.single() }
            assertTrue(pending.positionMs >= 285_000)
            assertTrue(sentinel.delete()); assertTrue(file.delete())
            compose.waitUntil(8_000) { !activity!!.writer.saveFailed.value }
            assertTrue(saved(activity!!).positionMs >= pending.positionMs)
            compose.onNodeWithTag("progress_save_retrying").assertDoesNotExist()
        }
    }
}

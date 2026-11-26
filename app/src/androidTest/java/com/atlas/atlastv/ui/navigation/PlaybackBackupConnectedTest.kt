package com.atlas.atlastv.ui.navigation

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.atlas.atlastv.debug.PlaybackBackupCheckActivity
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.xtream.network.ProviderEndpointRecovery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackBackupConnectedTest {
    @get:Rule val ui = createEmptyComposeRule()
    private fun launch(episode: Boolean = false, unreachable: Boolean = false) = ActivityScenario.launch<PlaybackBackupCheckActivity>(
        Intent(ApplicationProvider.getApplicationContext(), PlaybackBackupCheckActivity::class.java)
            .putExtra("episode", episode).putExtra("unreachable", unreachable))
    private fun retryVisible() { ui.waitUntil(20_000) { runCatching {
        ui.onNodeWithTag("playback_retry_focused").assertIsFocused(); true
    }.getOrDefault(false) } }
    private fun retry() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER); ui.waitForIdle() }
    private fun saved(activity: PlaybackBackupCheckActivity): List<WatchProgressRecord> = runBlocking {
        activity.writer.awaitSaved(); activity.store.loadAsync().records
    }
    private fun successfulRetry(episode: Boolean) {
        launch(episode).use { scenario ->
            lateinit var activity: PlaybackBackupCheckActivity; scenario.onActivity { activity = it }
            retryVisible()
            assertEquals(activity.initial, saved(activity).first { it.key == activity.initial.key })
            retry()
            ui.waitUntil(20_000) { saved(activity).any { it.key == activity.initial.key && it.positionMs > 255_000 } }
            val after = saved(activity)
            assertEquals(2, after.size)
            assertEquals(activity.other, after.first { it.key == activity.other.key })
            val playing = after.first { it.key == activity.initial.key }
            assertTrue(playing.positionMs in 255_001..275_000); assertFalse(playing.completed)
            assertTrue(activity.backupRequests.get() > 0)
            assertEquals(activity.account.alternateBaseUrls[0], ProviderEndpointRecovery.Shared.activeBaseUrl(activity.account))
            // Back through the normal overlay path; final stop/disposal must not write position zero.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitForIdle()
            if (!activity.isFinishing && !activity.isDestroyed) InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            ui.waitUntil(5_000) { activity.isFinishing || activity.isDestroyed }
            val exited = saved(activity)
            assertTrue(exited.first { it.key == activity.initial.key }.positionMs >= playing.positionMs)
            assertEquals(activity.other, exited.first { it.key == activity.other.key })
        }
    }
    @Test fun movieRetryRendersFromBackupAtItsResumePoint() = successfulRetry(false)
    @Test fun episodeRetryRendersFromBackupWithoutTouchingAnOverlappingMovieId() = successfulRetry(true)
    @Test fun failedBackupsKeepTheOriginalCheckpointAndFocusedRetryBack() {
        launch(unreachable = true).use { scenario ->
            lateinit var activity: PlaybackBackupCheckActivity; scenario.onActivity { activity = it }
            retryVisible(); retry(); retryVisible(); retry(); retryVisible()
            assertEquals(activity.initial, saved(activity).first { it.key == activity.initial.key })
            assertEquals(activity.other, saved(activity).first { it.key == activity.other.key })
            assertEquals(activity.account.baseUrl, ProviderEndpointRecovery.Shared.activeBaseUrl(activity.account))
            ui.onNodeWithTag("playback_retry_focused").performKeyInput { keyDown(androidx.compose.ui.input.key.Key.DirectionRight); keyUp(androidx.compose.ui.input.key.Key.DirectionRight) }
            ui.onNodeWithTag("playback_back_focused").assertIsFocused(); retry()
            ui.waitUntil(5_000) { activity.isFinishing || activity.isDestroyed }
            assertEquals(activity.initial, saved(activity).first { it.key == activity.initial.key })
        }
    }
}

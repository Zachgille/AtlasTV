package com.atlas.atlastv.ui.navigation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import com.atlas.atlastv.debug.PlaybackRecoveryCheckActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PlaybackRecoveryConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<PlaybackRecoveryCheckActivity>()

    @Test fun failedPlaybackOffersFocusedRemoteRecoveryAndExit() {
        waitForRetry()
        compose.onNodeWithText("Can't connect right now").assertExists()
        compose.onNodeWithTag("normal_playback_overlay").assertDoesNotExist()
        press("playback_retry_focused", Key.DirectionRight)
        compose.onNodeWithTag("playback_back_focused").assertExists()
        press("playback_back_focused", Key.DirectionLeft)
        press("playback_retry_focused", Key.Enter)
        waitForRetry()
        compose.onNodeWithText("Can't connect right now").assertExists()
        press("playback_retry_focused", Key.DirectionRight)
        val activity = compose.activity
        press("playback_back_focused", Key.Enter)
        compose.waitUntil(5_000L) { activity.isFinishing || activity.isDestroyed }
        assertEquals(255_000L, runBlocking { activity.progressStore.loadAsync().records.single().positionMs })
    }

    private fun waitForRetry() {
        compose.waitUntil(15_000L) {
            compose.onAllNodesWithTag("playback_retry_focused").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun press(tag: String, key: Key) {
        compose.onNodeWithTag(tag).performKeyInput { keyDown(key); keyUp(key) }
    }
}

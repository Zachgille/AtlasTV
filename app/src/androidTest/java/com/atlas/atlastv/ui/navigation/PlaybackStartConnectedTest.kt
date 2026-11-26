package com.atlas.atlastv.ui.navigation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.atlas.atlastv.debug.PlaybackStartCheckActivity
import com.atlas.atlastv.playback.WatchProgressSnapshot
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackStartConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<PlaybackStartCheckActivity>()
    @Test fun launchKeyReleaseCannotCancelTheNewLoadingScreen() {
        compose.waitUntil(5_000) { compose.activity.lookups == 1 }
        compose.onNodeWithTag("resume_lookup_back").assertIsFocused()
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeySync(
            android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_DPAD_CENTER))
        compose.waitForIdle()
        assertFalse(compose.activity.isFinishing)
        compose.activity.responses.trySend(Result.success(WatchProgressSnapshot()))
        compose.waitUntil(5_000) { compose.activity.starts == 1 }
    }
    @Test fun slowResumeLookupLeavesFocusedBackAvailableAndCancelsOnExit() {
        compose.waitUntil(5_000) { compose.activity.lookups == 1 }
        compose.onNodeWithTag("resume_lookup_back").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5_000) { compose.activity.isFinishing }
        compose.activity.responses.trySend(Result.success(WatchProgressSnapshot()))
        assertEquals(0, compose.activity.starts)
    }
    @Test fun failedLookupHasSafeRemoteRetryBeforePlaybackStarts() {
        compose.waitUntil(5_000) { compose.activity.lookups == 1 }
        compose.activity.responses.trySend(Result.failure(IllegalStateException("http://private/password")))
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("resume_lookup_retry").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Couldn't get your video ready").assertExists()
        compose.onNodeWithTag("resume_lookup_retry").assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        compose.waitUntil(5_000) { compose.activity.lookups == 2 }
        assertEquals(0, compose.activity.starts)
        compose.activity.responses.trySend(Result.success(WatchProgressSnapshot()))
        compose.waitUntil(5_000) { compose.activity.starts == 1 }
    }
}

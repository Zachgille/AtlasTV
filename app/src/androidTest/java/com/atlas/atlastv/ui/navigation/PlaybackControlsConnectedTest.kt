package com.atlas.atlastv.ui.navigation

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import com.atlas.atlastv.debug.PlaybackControlsCheckActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackControlsConnectedTest {
    @get:Rule val compose = createAndroidComposeRule<PlaybackControlsCheckActivity>()

    @Test fun everyTrackIsRemoteReachableIncludingEnglishAfterTheOldCapAndFinalSubtitle() {
        menu()
        focused("playback_track_audio_0_10")
        press("playback_track_audio_0_10", Key.DirectionUp)
        focused("playback_track_audio_0_9")
        press("playback_track_audio_0_9", Key.DirectionDown)
        press("playback_track_audio_0_10", Key.DirectionRight)
        focused("playback_track_subtitle_1_9")
        for (index in 9..16) press("playback_track_subtitle_1_$index", Key.DirectionDown)
        focused("playback_track_subtitle_1_17")
        compose.onNodeWithTag("playback_track_subtitle_1_17").assertIsDisplayed()
        press("playback_track_subtitle_1_17", Key.Enter)
        waitFor("playback_controls_destination")
        compose.runOnIdle { assertEquals(17, compose.activity.selected?.trackIndex) }
    }

    @Test fun selectedDeepAudioCanBeChosenWithoutFocusResetOnTrackMetadataChange() {
        menu(); focused("playback_track_audio_0_10")
        compose.runOnIdle { compose.activity.reorder = true }
        focused("playback_track_audio_0_10")
        press("playback_track_audio_0_10", Key.Enter)
        waitFor("playback_controls_destination")
        compose.runOnIdle { assertEquals(10, compose.activity.selected?.trackIndex) }
    }

    @Test fun subtitlesCanBeTurnedOffFromTheCompleteMenu() {
        menu(); focused("playback_track_audio_0_10")
        press("playback_track_audio_0_10", Key.DirectionRight)
        for (index in 9 downTo 0) press("playback_track_subtitle_1_$index", Key.DirectionUp)
        focused("playback_track_subtitle_off")
        press("playback_track_subtitle_off", Key.Enter)
        waitFor("playback_controls_destination")
        compose.runOnIdle { assertTrue(compose.activity.subtitlesOff) }
    }

    @Test fun subtitlesOffAndDoneAreReachableWithoutStartingPlayback() {
        menu(); focused("playback_track_audio_0_10")
        press("playback_track_audio_0_10", Key.DirectionRight)
        for (index in 9 downTo 0) press("playback_track_subtitle_1_$index", Key.DirectionUp)
        focused("playback_track_subtitle_off")
        press("playback_track_subtitle_off", Key.DirectionUp)
        focused("playback_track_done")
        press("playback_track_done", Key.Enter)
        waitFor("playback_controls_destination")
        compose.runOnIdle { assertNull(compose.activity.selected) }
    }

    @Test fun playingControlsAutoHideAndPauseKeepsThemVisibleThenBackExits() {
        waitFor("playback_progress_label")
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("normal_playback_overlay").fetchSemanticsNodes().isEmpty() }
        press("live_tv_player_screen", Key.MediaPause)
        waitFor("playback_play_pause_control")
        compose.onNodeWithText("Play").assertExists()
        compose.onNodeWithTag("playback_play_pause_control").assertIsFocused()
        Thread.sleep(4_500)
        compose.onNodeWithTag("normal_playback_overlay").assertExists()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("normal_playback_overlay").fetchSemanticsNodes().isEmpty() }
        androidx.test.espresso.Espresso.pressBack()
        waitFor("playback_controls_destination")
    }

    @Test fun centerActivatesTheFocusedSeekControlInsteadOfTogglingPlayback() {
        waitFor("playback_progress_label")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Pause").fetchSemanticsNodes().isNotEmpty() }
        press("live_tv_player_screen", Key.MediaPause)
        compose.onNodeWithTag("playback_fast_forward_control").performSemanticsAction(SemanticsActions.RequestFocus)
        fun position(): Long {
            val text = compose.onNodeWithTag("playback_progress_label").fetchSemanticsNode().config[SemanticsProperties.Text].first().text.substringBefore('/').trim()
            val parts = text.split(':')
            return (parts[0].toLong() * 60 + parts[1].toLong()) * 1000
        }
        val before = position()
        press("playback_fast_forward_control", Key.Enter)
        compose.waitUntil(5_000) { position() >= before + 25_000 }
        compose.onNodeWithText("Play").assertExists()
    }

    private fun menu() { compose.runOnIdle { compose.activity.menuFixture = true }; waitFor("playback_track_options_panel") }
    private fun waitFor(tag: String) { compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }
    private fun focused(tag: String) { compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag(tag).assertIsFocused(); true }.getOrDefault(false) } }
    private fun press(tag: String, key: Key) { compose.onNodeWithTag(tag).performKeyInput { pressKey(key) }; compose.waitForIdle() }
}

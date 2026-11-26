package com.atlas.atlastv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControlModelsTest {
    @Test
    fun seekTargetClampsToStartAndDuration() {
        assertEquals(0L, seekTargetMs(currentPositionMs = 5_000L, deltaMs = -10_000L, durationMs = 60_000L))
        assertEquals(60_000L, seekTargetMs(currentPositionMs = 45_000L, deltaMs = 30_000L, durationMs = 60_000L))
        assertEquals(75_000L, seekTargetMs(currentPositionMs = 45_000L, deltaMs = 30_000L, durationMs = null))
    }

    @Test
    fun trackLabelsPreferSafeLanguageAndLabel() {
        assertEquals("English · Director", playbackTrackLabel(language = "en", label = "Director", fallback = "Audio 1"))
        assertEquals("English", playbackTrackLabel(language = "und", label = "English", fallback = "Audio 1"))
        assertEquals("Subtitle 1", playbackTrackLabel(language = null, label = " ", fallback = "Subtitle 1"))
    }

    @Test fun readableTrackLabelsDistinguishAudioAndForcedSubtitles() {
        assertEquals("English · 5.1", playbackTrackLabel("eng", "English", "Audio 1", channelCount = 6))
        assertEquals("English · Stereo", playbackTrackLabel("en-US", null, "Audio 1", channelCount = 2))
        assertEquals("French · Forced", playbackTrackLabel("fr", null, "Subtitle 1", forced = true))
        assertEquals("Audio 1", playbackTrackLabel("und", null, "Audio 1"))
        assertEquals("Audio 1", playbackTrackLabel("fake-language", null, "Audio 1"))
    }

    @Test fun anOpenMenuOwnsUpAndNormalPlaybackCanOpenIt() {
        assertTrue(shouldOpenTrackMenu(false, 2, 0))
        assertFalse(shouldOpenTrackMenu(true, 2, 0))
        assertFalse(shouldOpenTrackMenu(false, 1, 0))
    }

    @Test
    fun trackOptionsAppearOnlyWhenUseful() {
        assertFalse(shouldShowTrackOptions(audioCount = 0, subtitleCount = 0))
        assertFalse(shouldShowTrackOptions(audioCount = 1, subtitleCount = 0))
        assertTrue(shouldShowTrackOptions(audioCount = 2, subtitleCount = 0))
        assertTrue(shouldShowTrackOptions(audioCount = 1, subtitleCount = 1))
    }

    @Test
    fun directionalSeekIsDisabledWhileTrackPanelIsOpen() {
        assertTrue(shouldDirectionalSeek(trackOptionsVisible = false, isSeekable = true))
        assertFalse(shouldDirectionalSeek(trackOptionsVisible = true, isSeekable = true))
        assertFalse(shouldDirectionalSeek(trackOptionsVisible = false, isSeekable = false))
    }

    @Test
    fun noFirstFrameTimeoutRequiresKnownDurationAndNoPriorError() {
        assertTrue(
            shouldShowNoFirstFrameTimeout(
                elapsedMs = DefaultNoFirstFrameTimeoutMs,
                durationKnown = true,
                firstFrameRendered = false,
                playbackErrorPresent = false,
            ),
        )
        assertFalse(
            shouldShowNoFirstFrameTimeout(
                elapsedMs = DefaultNoFirstFrameTimeoutMs - 1,
                durationKnown = true,
                firstFrameRendered = false,
                playbackErrorPresent = false,
            ),
        )
        assertFalse(
            shouldShowNoFirstFrameTimeout(
                elapsedMs = DefaultNoFirstFrameTimeoutMs,
                durationKnown = false,
                firstFrameRendered = false,
                playbackErrorPresent = false,
            ),
        )
        assertFalse(
            shouldShowNoFirstFrameTimeout(
                elapsedMs = DefaultNoFirstFrameTimeoutMs,
                durationKnown = true,
                firstFrameRendered = true,
                playbackErrorPresent = false,
            ),
        )
        assertFalse(
            shouldShowNoFirstFrameTimeout(
                elapsedMs = DefaultNoFirstFrameTimeoutMs,
                durationKnown = true,
                firstFrameRendered = false,
                playbackErrorPresent = true,
            ),
        )
    }
}

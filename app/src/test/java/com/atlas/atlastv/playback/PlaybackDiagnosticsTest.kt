package com.atlas.atlastv.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDiagnosticsTest {
    @Test
    fun diagnosticsUseRedactedUrlOnly() {
        val diagnostics = PlaybackDiagnostics(
            playerState = "Ready",
            httpStatus = 200,
            videoWidth = 1280,
            videoHeight = 720,
            firstFrameRendered = true,
            droppedFrames = 0,
            redactedUrl = "http://REDACTED_HOST/live/REDACTED/REDACTED/1001.ts",
        )

        val text = diagnostics.toString()

        assertTrue(text.contains("REDACTED_HOST"))
        assertFalse(text.contains("fake_user"))
        assertFalse(text.contains("fake_pass"))
    }
}

package com.atlas.atlastv.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaybackRequestConfigTest {
    @Test
    fun usesVlcCompatibleUserAgentAndSafeHeaders() {
        assertEquals("VLC/3.0.20 LibVLC/3.0.20", LivePlaybackRequestConfig.UserAgent)
        assertEquals("*/*", LivePlaybackRequestConfig.RequestHeaders["Accept"])
        assertEquals("1", LivePlaybackRequestConfig.RequestHeaders["Icy-MetaData"])
    }

    @Test
    fun requestShapeSummaryDocumentsConfiguredPlaybackRequest() {
        val summary = LivePlaybackRequestConfig.RequestShapeSummary

        assertTrue(summary.contains("userAgent=VLC-compatible"))
        assertTrue(summary.contains("accept=*/*"))
        assertTrue(summary.contains("icyMetaData=1"))
        assertTrue(summary.contains("redirects=true"))
        assertTrue(summary.contains("codecAdapter=synchronous"))
        assertTrue(summary.contains("decoderFallback=true"))
    }
}

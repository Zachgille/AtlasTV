package com.atlas.atlastv.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test

class PlaybackRecoveryTest {
    @Test fun readyWithoutVideoAndFailedRetryCannotEraseTheResumeCheckpoint() {
        val recovery = PlaybackRecovery(255_000L, seekable = true, requireFirstFrame = true)
        recovery.beginAttempt(); recovery.markReady()
        assertFalse(recovery.canSaveProgress)
        assertEquals(255_000L, recovery.checkpoint(0))
        recovery.markFirstFrame()
        assertTrue(recovery.canSaveProgress)
        assertEquals(320_000L, recovery.checkpoint(320_000))
        assertEquals(320_000L, recovery.beginAttempt())
        recovery.markReady()
        assertFalse(recovery.canSaveProgress)
        assertEquals(320_000L, recovery.checkpoint(0))
    }
    @Test fun httpFailuresHaveActionableSafeCopy() {
        val code = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        for (status in listOf(401, 403)) assertEquals(PlaybackFailure.Account, playbackFailure(code, status))
        for (status in listOf(404, 410)) assertEquals(PlaybackFailure.Unavailable, playbackFailure(code, status))
        for (status in listOf(408, 504)) assertEquals(PlaybackFailure.Timeout, playbackFailure(code, status))
        for (status in listOf(429, 500, 503)) assertEquals(PlaybackFailure.Connection, playbackFailure(code, status))
        assertEquals(PlaybackFailure.Unknown, playbackFailure(code, 418))
    }

    @Test fun mediaFailuresHaveSafeFallbacks() {
        assertEquals(PlaybackFailure.Connection, playbackFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(PlaybackFailure.Timeout, playbackFailure(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals(PlaybackFailure.Format, playbackFailure(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(PlaybackFailure.Format, playbackFailure(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
        assertEquals(PlaybackFailure.Format, playbackFailure(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED))
        assertEquals(PlaybackFailure.Unknown, playbackFailure(Int.MAX_VALUE))
        PlaybackFailure.entries.forEach {
            assertFalse(it.message.contains("http"))
            assertTrue(it.message.isNotBlank())
        }
    }

    @Test fun repeatedFailedPreparationsPreserveExistingResume() {
        val recovery = PlaybackRecovery(255_000L, seekable = true)
        repeat(3) {
            assertEquals(255_000L, recovery.beginAttempt())
            assertFalse(recovery.attemptReady)
            assertEquals(255_000L, recovery.checkpoint(0L))
        }
    }

    @Test fun retryUsesLatestPositionIncludingUserRewind() {
        val recovery = PlaybackRecovery(255_000L, seekable = true)
        recovery.beginAttempt()
        recovery.markReady()
        assertEquals(320_000L, recovery.checkpoint(320_000L))
        assertEquals(5_000L, recovery.checkpoint(5_000L))
        assertEquals(5_000L, recovery.beginAttempt())
        assertEquals(5_000L, recovery.checkpoint(0L))
    }

    @Test fun retryBeforeFirstFrameAndLiveDoNotInventProgress() {
        val vod = PlaybackRecovery(null, seekable = true)
        assertEquals(0L, vod.beginAttempt())
        assertEquals(0L, vod.checkpoint(-1L))
        val live = PlaybackRecovery(255_000L, seekable = false)
        assertNull(live.beginAttempt())
        live.markReady()
        assertEquals(0L, live.checkpoint(12_000L))
        assertNull(live.beginAttempt())
    }
}

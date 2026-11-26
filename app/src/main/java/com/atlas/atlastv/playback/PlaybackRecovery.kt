package com.atlas.atlastv.playback

import androidx.media3.common.PlaybackException

/** Only fixed, safe copy crosses into normal UI; exception messages and URLs never do. */
enum class PlaybackFailure(val title: String, val message: String) {
    Connection("Can't connect right now", "Check your internet connection, then try again."),
    Timeout("Playback is taking too long", "Try again, or go back and choose another title."),
    Unavailable("This title is unavailable", "Try again later, or go back and choose another title."),
    Account("Your provider couldn't play this title", "Try again. If this continues, check your account in Settings."),
    Format("This video couldn't play", "Try again, or go back and choose another title."),
    Unknown("Playback stopped", "Try again, or go back and choose another title."),
}

fun playbackFailure(errorCode: Int, httpStatus: Int? = null): PlaybackFailure = when {
    httpStatus == 401 || httpStatus == 403 -> PlaybackFailure.Account
    httpStatus == 404 || httpStatus == 410 -> PlaybackFailure.Unavailable
    httpStatus == 408 || httpStatus == 504 -> PlaybackFailure.Timeout
    httpStatus == 429 || httpStatus in 500..599 -> PlaybackFailure.Connection
    else -> when (errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> PlaybackFailure.Connection
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT -> PlaybackFailure.Timeout
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackFailure.Unavailable
        PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED,
        PlaybackException.ERROR_CODE_PERMISSION_DENIED -> PlaybackFailure.Account
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackFailure.Format
        else -> PlaybackFailure.Unknown
    }
}

/** Retains the session position even when a failed preparation reports position zero. */
class PlaybackRecovery(initialPositionMs: Long?, private val seekable: Boolean, private val requireFirstFrame: Boolean = false) {
    var positionMs: Long = if (seekable) initialPositionMs?.coerceAtLeast(0L) ?: 0L else 0L
        private set
    var attemptReady: Boolean = false
        private set
    private var renderedFirstFrame = false
    val hasRenderedPlayback: Boolean get() = renderedFirstFrame
    val canSaveProgress: Boolean get() = attemptReady && (!requireFirstFrame || renderedFirstFrame)

    fun beginAttempt(): Long? {
        attemptReady = false
        renderedFirstFrame = false
        return positionMs.takeIf { seekable }
    }

    fun markReady() { attemptReady = true }
    fun markFirstFrame() { renderedFirstFrame = true }

    fun checkpoint(currentPositionMs: Long): Long {
        if (seekable && canSaveProgress) positionMs = currentPositionMs.coerceAtLeast(0L)
        return positionMs
    }
}

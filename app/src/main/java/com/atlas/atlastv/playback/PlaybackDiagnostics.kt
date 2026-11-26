package com.atlas.atlastv.playback

data class PlaybackDiagnostics(
    val playerState: String = "Loading",
    val httpStatus: Int? = null,
    val videoWidth: Int? = null,
    val videoHeight: Int? = null,
    val firstFrameRendered: Boolean = false,
    val droppedFrames: Int = 0,
    val redactedUrl: String,
    val error: String? = null,
)

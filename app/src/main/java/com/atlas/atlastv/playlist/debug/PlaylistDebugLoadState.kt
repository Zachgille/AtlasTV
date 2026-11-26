package com.atlas.atlastv.playlist.debug

sealed interface PlaylistDebugLoadState {
    data object Idle : PlaylistDebugLoadState

    data class Loading(
        val redactedUrl: String,
    ) : PlaylistDebugLoadState

    data class Success(
        val summary: PlaylistDebugSummary,
    ) : PlaylistDebugLoadState

    data class Error(
        val redactedUrl: String,
        val message: String,
    ) : PlaylistDebugLoadState
}

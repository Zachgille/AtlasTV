package com.atlas.atlastv.playlist.debug

sealed interface XtreamDebugLoadState {
    data object Unavailable : XtreamDebugLoadState
    data object Ready : XtreamDebugLoadState
    data class Loading(val stage: String) : XtreamDebugLoadState
    data class Success(
        val summary: XtreamDebugSummary,
        val playableLiveStreams: List<DebugPlayableLiveStream>,
    ) : XtreamDebugLoadState
    data class Error(
        val message: String,
        val redactedUrl: String?,
        val endpointLabel: String?,
        val failureCategory: String,
        val httpStatus: Int?,
        val responseByteCount: Long?,
    ) : XtreamDebugLoadState
}

class DebugPlayableLiveStream(
    val name: String,
    val categoryName: String,
    val streamId: Int,
    val streamUrl: String,
    val redactedStreamUrl: String,
) {
    override fun toString(): String =
        "DebugPlayableLiveStream(name=$name, categoryName=$categoryName, streamId=$streamId, streamUrl=$redactedStreamUrl)"
}

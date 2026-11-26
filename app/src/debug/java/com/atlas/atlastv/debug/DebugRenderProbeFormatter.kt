package com.atlas.atlastv.debug

object DebugRenderProbeFormatter {
    fun format(result: DebugCommandResult): String {
        val probe = result.playbackProbe ?: return "render status=${result.status} state=${result.currentState}"
        return listOf(
            "render status=${result.status}",
            "streamId=${probe.streamId}",
            "playerState=${probe.playerState ?: "unknown"}",
            "firstFrame=${probe.firstFrameRendered ?: false}",
            "video=${probe.videoWidth ?: 0}x${probe.videoHeight ?: 0}",
            "dropped=${probe.droppedFrames ?: 0}",
            "category=${probe.statusCategory}",
            "url=${probe.redactedUrl}",
        ).joinToString(" ")
    }
}

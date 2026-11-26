package com.atlas.atlastv.debug

object DebugPlaybackHealthFormatter {
    fun rows(result: DebugCommandResult): List<String> {
        val sweep = result.playbackHealthSweep ?: return emptyList()
        return sweep.results.map { section ->
            listOf(
                section.section,
                section.streamId?.toString() ?: "none",
                section.containerExtension ?: "none",
                section.playerState ?: "none",
                (section.firstFrameRendered).toString(),
                "${section.videoWidth ?: 0}x${section.videoHeight ?: 0}",
                section.droppedFrames.toString(),
                section.bufferCount.toString(),
                section.playbackAdvanced.toString(),
            ).joinToString(" | ")
        }
    }
}

package com.atlas.atlastv.debug

object DebugPlaybackDeepHealthFormatter {
    fun rows(result: DebugCommandResult): List<String> {
        val deep = result.playbackDeepHealth ?: return emptyList()
        return deep.results.flatMap { section ->
            section.profiles.map { profile ->
                listOf(
                    section.section,
                    profile.profile,
                    "${profile.video?.width ?: 0}x${profile.video?.height ?: 0}",
                    profile.video?.codecs ?: profile.video?.sampleMimeType ?: "unknown",
                    profile.renderedFramesPerSecond?.format1() ?: "unknown",
                    profile.counters.droppedBufferCount.toString(),
                    profile.droppedRenderedRatio?.format3() ?: "unknown",
                    profile.counters.maxConsecutiveDroppedBufferCount.toString(),
                    profile.rebufferCount.toString(),
                    profile.totalRebufferMs.toString(),
                    section.transport?.averageMbps?.format1() ?: "0.0",
                    section.transport?.minWindowMbps?.format1() ?: "0.0",
                    section.transport?.stalledIntervalCount?.toString() ?: "0",
                    profile.decoder.videoDecoderName ?: "unknown",
                    section.bottleneck,
                ).joinToString(" | ")
            }
        }
    }
}

private fun Double.format1(): String = "%.1f".format(this)
private fun Double.format3(): String = "%.3f".format(this)

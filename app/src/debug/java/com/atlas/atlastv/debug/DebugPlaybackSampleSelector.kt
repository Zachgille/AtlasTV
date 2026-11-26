package com.atlas.atlastv.debug

import com.atlas.atlastv.ui.series.SeriesEpisodeItem

object DebugPlaybackSampleSelector {
    private val preferredExtensions = listOf("mp4", "m4v", "m3u8", "ts", "mov")

    fun selectVod(items: List<DebugPlayableVodItem>, requestedStreamId: Int?): DebugPlayableVodItem? =
        requestedStreamId?.let { id -> items.firstOrNull { item -> item.streamId == id } }
            ?: items.minByOrNull { item -> extensionRank(item.containerExtension) }

    fun selectEpisode(episodes: List<SeriesEpisodeItem>): SeriesEpisodeItem? =
        episodes.minByOrNull { episode -> extensionRank(episode.containerExtension) }

    fun extensionRank(extension: String): Int {
        val normalized = extension.trim().trimStart('.').lowercase()
        val preferredIndex = preferredExtensions.indexOf(normalized)
        return if (preferredIndex >= 0) preferredIndex else preferredExtensions.size
    }
}

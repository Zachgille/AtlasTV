package com.atlas.atlastv.debug

import com.atlas.atlastv.ui.series.SeriesEpisodeItem
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugPlaybackSampleSelectorTest {
    @Test
    fun defaultVodSelectionPrefersCommonPlayableContainer() {
        val items = listOf(
            vod(streamId = 1, extension = "mkv"),
            vod(streamId = 2, extension = "avi"),
            vod(streamId = 3, extension = "mp4"),
        )

        val selected = DebugPlaybackSampleSelector.selectVod(items, requestedStreamId = null)

        assertEquals(3, selected?.streamId)
    }

    @Test
    fun requestedVodStreamIdIsRespectedEvenWhenContainerIsNotPreferred() {
        val items = listOf(
            vod(streamId = 1, extension = "mkv"),
            vod(streamId = 2, extension = "mp4"),
        )

        val selected = DebugPlaybackSampleSelector.selectVod(items, requestedStreamId = 1)

        assertEquals(1, selected?.streamId)
    }

    @Test
    fun episodeSelectionPrefersCommonPlayableContainer() {
        val episodes = listOf(
            episode(episodeId = 10, extension = "mkv"),
            episode(episodeId = 11, extension = "mp4"),
        )

        val selected = DebugPlaybackSampleSelector.selectEpisode(episodes)

        assertEquals(11, selected?.episodeId)
    }

    private fun vod(streamId: Int, extension: String): DebugPlayableVodItem =
        DebugPlayableVodItem(
            title = "Movie $streamId",
            streamId = streamId,
            containerExtension = extension,
            redactedUrl = "http://REDACTED_HOST/movie/REDACTED/REDACTED/$streamId.$extension",
        )

    private fun episode(episodeId: Int, extension: String): SeriesEpisodeItem =
        SeriesEpisodeItem(
            episodeId = episodeId,
            title = "Episode $episodeId",
            seasonNumber = 1,
            episodeNumber = episodeId,
            containerExtension = extension,
            redactedStreamUrl = "http://REDACTED_HOST/series/REDACTED/REDACTED/$episodeId.$extension",
        )
}

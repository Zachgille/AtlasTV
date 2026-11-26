package com.atlas.atlastv.ui.series

import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder

class SeriesEpisodeStreamUrlProvider(
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
) {
    fun buildPlaybackUrl(credentials: XtreamCredentials?, episode: SeriesEpisodeItem): String {
        if (credentials == null) return episode.redactedStreamUrl
        return endpointBuilder.buildSeriesEpisodeStream(credentials, episode.episodeId, episode.containerExtension)
    }
}

package com.atlas.atlastv.ui.livetv

import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder

class LiveTvStreamUrlProvider(
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
) {
    fun buildPlaybackUrl(credentials: XtreamCredentials?, channel: LiveTvChannel): String? =
        credentials?.let {
            endpointBuilder.buildLiveStream(
                credentials = it,
                streamId = channel.streamId,
                extension = channel.containerExtension,
            )
        }

    fun buildRedactedUrl(credentials: XtreamCredentials?, channel: LiveTvChannel): String =
        credentials?.let {
            endpointBuilder.buildRedactedLiveStream(
                credentials = it,
                streamId = channel.streamId,
                extension = channel.containerExtension,
            )
        } ?: channel.redactedStreamUrl
}

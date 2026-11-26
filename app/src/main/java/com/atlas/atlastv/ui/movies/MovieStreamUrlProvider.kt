package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder

class MovieStreamUrlProvider(
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
) {
    fun buildPlaybackUrl(credentials: XtreamCredentials?, movie: MovieItem): String? =
        credentials?.let {
            endpointBuilder.buildVodStream(it, movie.streamId, movie.containerExtension)
        }
}

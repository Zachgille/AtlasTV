package com.atlas.atlastv.playback

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory

object LivePlaybackRequestConfig {
    const val UserAgent = "VLC/3.0.20 LibVLC/3.0.20"

    val RequestHeaders: Map<String, String> = mapOf(
        "Accept" to "*/*",
        "Icy-MetaData" to "1",
    )

    const val RequestShapeSummary = "userAgent=VLC-compatible; accept=*/*; icyMetaData=1; redirects=true; codecAdapter=synchronous; decoderFallback=true"

    @OptIn(UnstableApi::class)
    fun httpDataSourceFactory(): DefaultHttpDataSource.Factory =
        DefaultHttpDataSource.Factory()
            .setUserAgent(UserAgent)
            .setDefaultRequestProperties(RequestHeaders)
            .setAllowCrossProtocolRedirects(true)

    @OptIn(UnstableApi::class)
    fun dataSourceFactory(context: android.content.Context): DefaultDataSource.Factory =
        DefaultDataSource.Factory(context, httpDataSourceFactory())

    @OptIn(UnstableApi::class)
    fun renderersFactory(context: android.content.Context): DefaultRenderersFactory =
        DefaultRenderersFactory(context)
            .forceDisableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)
}

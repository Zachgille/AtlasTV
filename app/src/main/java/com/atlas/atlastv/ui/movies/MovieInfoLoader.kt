package com.atlas.atlastv.ui.movies

import com.atlas.atlastv.core.network.*
import com.atlas.atlastv.ui.catalog.cleanDetailDescription
import com.atlas.atlastv.ui.catalog.detailReleaseYear
import com.atlas.atlastv.ui.media.*
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.*
import com.atlas.atlastv.xtream.parser.*
import kotlinx.coroutines.CancellationException

/** Only safe text and opaque image references leave this loader. Failure never blocks Play. */
data class MovieMetadata(
    val synopsis: String? = null,
    val genre: String? = null,
    val releaseYear: String? = null,
    val durationMinutes: Int? = null,
    val backdrop: AtlasImageRef = AtlasImageRef.Missing,
    val poster: AtlasImageRef = AtlasImageRef.Missing,
)

class MovieInfoLoader(
    private val downloader: TextDownloader = OkHttpTextDownloader(),
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
    private val parser: XtreamJsonParser = XtreamJsonParser(),
    private val registerArtwork: suspend (List<Pair<String?, String?>>) -> Unit = {},
    private val recovery: ProviderEndpointRecovery = ProviderEndpointRecovery.Shared,
) {
    suspend fun load(credentials: XtreamCredentials, streamId: Int): MovieMetadata? = recovery.run(credentials,
        succeeded = { it.value != null && it.value != MovieMetadata() },
        retryable = { it.failure?.let(ProviderEndpointRecovery::unavailable) == true }) { resolved ->
        var failure: TextDownloadResult.Failure? = null
        ProviderMetadataAttempt(loadFrom(resolved, streamId) { failure = it }, failure)
    }.value

    private suspend fun loadFrom(credentials: XtreamCredentials, streamId: Int,
        onFailure: (TextDownloadResult.Failure) -> Unit): MovieMetadata? {
        val download = downloader.download(endpointBuilder.build(credentials, XtreamEndpoint.VodInfo, vodId = streamId))
        if (download is TextDownloadResult.Failure) onFailure(download)
        if (download !is TextDownloadResult.Success) return null
        val parsed = parser.parseMovieInfo(download.text)
        if (parsed !is XtreamParseResult.Success) return null
        val info = parsed.value
        val backdrop = info.backdrops.asSequence().map { it to atlasImageRefFromRaw(it) }
            .firstOrNull { it.second.state == AtlasImageState.ProviderImage }
        val poster = atlasImageRefFromRaw(info.cover)
        try {
            registerArtwork(listOfNotNull(backdrop?.let { it.second.providerImageToken to it.first },
                poster.providerImageToken?.let { it to info.cover }))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Optional images must not prevent showing text or starting playback. */ }
        return MovieMetadata(cleanDetailDescription(info.plot), cleanDetailDescription(info.genre, 100),
            detailReleaseYear(info.releaseDate), com.atlas.atlastv.core.media.MediaRuntime.minutes(info.durationSeconds?.toLong()?.times(1000)),
            backdrop?.second ?: AtlasImageRef.Missing, poster)
    }
}

sealed interface MovieInfoState {
    data object Empty : MovieInfoState
    data class Loading(val streamId: Int) : MovieInfoState
    data class Ready(val streamId: Int, val metadata: MovieMetadata, val saved: Boolean = false,
        val refreshing: Boolean = false, val refreshFailed: Boolean = false) : MovieInfoState
    data class Unavailable(val streamId: Int) : MovieInfoState
}

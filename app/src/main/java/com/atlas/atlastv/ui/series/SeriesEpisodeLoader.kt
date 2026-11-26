package com.atlas.atlastv.ui.series

import com.atlas.atlastv.core.network.OkHttpTextDownloader
import com.atlas.atlastv.core.network.TextDownloadResult
import com.atlas.atlastv.core.network.TextDownloader
import com.atlas.atlastv.ui.catalog.cleanProviderTitle
import com.atlas.atlastv.xtream.model.XtreamCredentials
import com.atlas.atlastv.xtream.network.XtreamEndpoint
import com.atlas.atlastv.xtream.network.ProviderEndpointRecovery
import com.atlas.atlastv.xtream.network.ProviderMetadataAttempt
import com.atlas.atlastv.xtream.network.XtreamEndpointBuilder
import com.atlas.atlastv.xtream.parser.XtreamJsonParser
import com.atlas.atlastv.xtream.parser.XtreamParseResult
import com.atlas.atlastv.xtream.model.XtreamSeriesInfo
import com.atlas.atlastv.ui.media.atlasImageRefFromRaw
import com.atlas.atlastv.ui.media.AtlasImageState
import com.atlas.atlastv.ui.media.AtlasImageRef
import kotlinx.coroutines.CancellationException

class SeriesEpisodeLoader(
    private val downloader: TextDownloader = OkHttpTextDownloader(),
    private val endpointBuilder: XtreamEndpointBuilder = XtreamEndpointBuilder(),
    private val parser: XtreamJsonParser = XtreamJsonParser(),
    private val registerArtwork: suspend (List<Pair<String?, String?>>) -> Unit = {},
    private val recovery: ProviderEndpointRecovery = ProviderEndpointRecovery.Shared,
) {
    suspend fun loadMetadata(credentials: XtreamCredentials, seriesId: Int): SeriesShowMetadata? = recovery.run(credentials,
        succeeded = { it.value != null && it.value != SeriesShowMetadata() },
        retryable = { it.failure?.let(ProviderEndpointRecovery::unavailable) == true }) { resolved ->
        var failure: TextDownloadResult.Failure? = null
        ProviderMetadataAttempt(loadMetadataFrom(resolved, seriesId) { failure = it }, failure)
    }.value

    private suspend fun loadMetadataFrom(credentials: XtreamCredentials, seriesId: Int,
        onFailure: (TextDownloadResult.Failure) -> Unit): SeriesShowMetadata? {
        val download = downloader.download(endpointBuilder.build(credentials, XtreamEndpoint.SeriesInfo, seriesId = seriesId))
        if (download is TextDownloadResult.Failure) onFailure(download)
        if (download !is TextDownloadResult.Success) return null
        val parsed = parser.parseSeriesMetadata(download.text)
        return if (parsed is XtreamParseResult.Success) metadata(parsed.value) else null
    }

    private suspend fun metadata(info: XtreamSeriesInfo): SeriesShowMetadata {
        val backdrop = info.backdrops.asSequence().map { it to atlasImageRefFromRaw(it) }
            .firstOrNull { it.second.state == AtlasImageState.ProviderImage }
        val poster = atlasImageRefFromRaw(info.cover)
        try {
            registerArtwork(listOfNotNull(backdrop?.let { it.second.providerImageToken to it.first },
                poster.providerImageToken?.let { it to info.cover }))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Optional artwork storage must not block content. */ }
        return SeriesShowMetadata(cleanShowDescription(info.plot), cleanShowDescription(info.genre, 100),
            showReleaseYear(info.releaseDate), backdrop?.second ?: AtlasImageRef.Missing, poster)
    }

    suspend fun loadEpisodes(credentials: XtreamCredentials, seriesId: Int, maxEpisodes: Int = Int.MAX_VALUE): SeriesEpisodeLoadResult =
        recovery.run(credentials,
            succeeded = { (it.value as? SeriesEpisodeLoadResult.Success)?.episodes?.isNotEmpty() == true },
            retryable = { it.failure?.let(ProviderEndpointRecovery::unavailable) == true }) { resolved ->
            var failure: TextDownloadResult.Failure? = null
            ProviderMetadataAttempt(loadEpisodesFrom(resolved, seriesId, maxEpisodes) { failure = it }, failure)
        }.value ?: SeriesEpisodeLoadResult.Failure("Unavailable")

    private suspend fun loadEpisodesFrom(credentials: XtreamCredentials, seriesId: Int, maxEpisodes: Int,
        onFailure: (TextDownloadResult.Failure) -> Unit): SeriesEpisodeLoadResult {
        val url = endpointBuilder.build(credentials, XtreamEndpoint.SeriesInfo, seriesId = seriesId)
        return when (val download = downloader.download(url)) {
            is TextDownloadResult.Failure -> { onFailure(download); SeriesEpisodeLoadResult.Failure(download.reason.name) }
            is TextDownloadResult.Success -> when (val parsed = parser.parseSeriesInfo(download.text)) {
                is XtreamParseResult.Failure -> SeriesEpisodeLoadResult.Failure("MalformedJson")
                is XtreamParseResult.Success -> {
                    val info = parsed.value
                    val episodeArtwork = info.episodes.asSequence().filter { it.id != null }.take(maxEpisodes)
                        .mapNotNull { episode ->
                            atlasImageRefFromRaw(episode.thumbnail).providerImageToken?.let { it to episode.thumbnail }
                        }.distinctBy { it.first }.toList()
                    try { registerArtwork(episodeArtwork) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Missing optional thumbnails cannot block episode selection. */ }
                    SeriesEpisodeLoadResult.Success(
                        parsed.value.episodes
                            .mapNotNull { episode ->
                                val episodeId = episode.id ?: return@mapNotNull null
                                val extension = episode.containerExtension?.trim()?.trimStart('.')?.takeIf { it.isNotBlank() } ?: DefaultEpisodeExtension
                                val rawTitle = episode.title?.takeIf { it.isNotBlank() }
                                    ?: episode.episodeNumber?.let { "Episode $it" } ?: "Episode"
                                SeriesEpisodeItem(
                                    title = cleanProviderTitle(rawTitle).cleanTitle,
                                    seasonNumber = episode.seasonNumber,
                                    episodeNumber = episode.episodeNumber,
                                    episodeId = episodeId,
                                    containerExtension = extension,
                                    redactedStreamUrl = endpointBuilder.buildRedactedSeriesEpisodeStream(credentials, episodeId, extension),
                                    durationMs = episode.durationMs,
                                    thumbnail = atlasImageRefFromRaw(episode.thumbnail),
                                )
                            }
                            .take(maxEpisodes),
                        metadata = metadata(info),
                    )
                }
            }
        }
    }

    companion object {
        private const val DefaultEpisodeExtension = "mp4"
    }
}

sealed interface SeriesEpisodeLoadResult {
    data class Success(val episodes: List<SeriesEpisodeItem>, val metadata: SeriesShowMetadata = SeriesShowMetadata(), val fromSaved: Boolean = false) : SeriesEpisodeLoadResult
    data class Failure(val category: String) : SeriesEpisodeLoadResult
}

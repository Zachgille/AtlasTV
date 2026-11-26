package com.atlas.atlastv.playlist.debug

import com.atlas.atlastv.core.network.OkHttpTextDownloader
import com.atlas.atlastv.core.network.TextDownloadResult
import com.atlas.atlastv.core.network.TextDownloader
import com.atlas.atlastv.core.security.UrlRedactor
import com.atlas.atlastv.playlist.filter.PlaylistCategoryFilter
import com.atlas.atlastv.playlist.indexer.PlaylistIndexer
import com.atlas.atlastv.playlist.parser.M3uParser

class PlaylistDebugLoader(
    private val downloader: TextDownloader = OkHttpTextDownloader(),
    private val parser: M3uParser = M3uParser(),
    private val filter: PlaylistCategoryFilter = PlaylistCategoryFilter(),
    private val indexer: PlaylistIndexer = PlaylistIndexer(),
    private val summaryBuilder: PlaylistDebugSummaryBuilder = PlaylistDebugSummaryBuilder(),
) {
    suspend fun load(url: String): PlaylistDebugLoadState {
        val redactedUrl = UrlRedactor.redact(url)
        if (url.isBlank()) {
            return PlaylistDebugLoadState.Error(
                redactedUrl = redactedUrl,
                message = "Enter a playlist URL.",
            )
        }

        return when (val downloadResult = downloader.download(url.trim())) {
            is TextDownloadResult.Failure -> PlaylistDebugLoadState.Error(
                redactedUrl = redactedUrl,
                message = downloadResult.message,
            )
            is TextDownloadResult.Success -> buildSuccessState(
                redactedUrl = redactedUrl,
                downloadResult = downloadResult,
            )
        }
    }

    private fun buildSuccessState(
        redactedUrl: String,
        downloadResult: TextDownloadResult.Success,
    ): PlaylistDebugLoadState {
        if (downloadResult.text.isBlank()) {
            return PlaylistDebugLoadState.Error(
                redactedUrl = redactedUrl,
                message = "Playlist response was empty.",
            )
        }

        val parseResult = parser.parse(downloadResult.text)
        val filterResult = filter.filter(parseResult.items)
        val indexResult = indexer.index(filterResult.keptItems)
        val summary = summaryBuilder.build(
            redactedUrl = redactedUrl,
            downloadedText = downloadResult.text,
            downloadedByteCount = downloadResult.byteCount,
            parseResult = parseResult,
            filterResult = filterResult,
            indexResult = indexResult,
        )

        return if (filterResult.keptCount == 0 && parseResult.items.isNotEmpty()) {
            PlaylistDebugLoadState.Error(
                redactedUrl = redactedUrl,
                message = "Playlist parsed, but no items were kept after filtering.",
            )
        } else {
            PlaylistDebugLoadState.Success(summary)
        }
    }
}

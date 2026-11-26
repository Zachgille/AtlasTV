package com.atlas.atlastv.ui.series

import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.ui.catalog.CardWatchProgress
import com.atlas.atlastv.ui.catalog.cardWatchProgress

data class EpisodeRowPresentation(val durationLabel: String?, val progress: CardWatchProgress?, val watched: Boolean = false)

fun SeriesEpisodeItem.matchingProgress(seriesId: Int?, record: WatchProgressRecord?): WatchProgressRecord? =
    record?.takeIf { it.contentType == WatchProgressContentType.SeriesEpisode && it.streamId == episodeId &&
        (it.seriesId == null || it.seriesId == seriesId) }

/** Provider runtime is an optional fallback; displaying progress never changes the saved record. */
fun SeriesEpisodeItem.rowPresentation(seriesId: Int?, record: WatchProgressRecord?): EpisodeRowPresentation {
    val saved = matchingProgress(seriesId, record)
    val runtime = com.atlas.atlastv.core.media.MediaRuntime.validMillis(saved?.durationMs)
        ?: com.atlas.atlastv.core.media.MediaRuntime.validMillis(durationMs)
    val durationLabel = com.atlas.atlastv.core.media.MediaRuntime.labelMillis(runtime)
    val progress = saved?.takeIf { it.completed || it.positionMs > 0 }?.let {
        it.copy(durationMs = runtime).cardWatchProgress().copy(episodeLabel = null)
    }
    return EpisodeRowPresentation(durationLabel, progress, saved?.completed == true)
}

/** Remove provider scaffolding while retaining an actual episode name. */
fun SeriesEpisodeItem.displayEpisodeName(seriesName: String?): String {
    var name = title.trim()
    val showNames = listOfNotNull(seriesName?.trim(), seriesName?.let { com.atlas.atlastv.ui.catalog.displayTitle(it) }).filter { it.isNotEmpty() }.distinct()
    showNames.forEach { show ->
        name = name.replace(Regex("^${Regex.escape(show)}(?:\\s*[-:|]\\s*|\\s+)", RegexOption.IGNORE_CASE), "")
    }
    name = name.replace(episodeCode, " ").trim(' ', '-', ':', '|', '.', '–', '—')
    // Some providers place the code before the repeated show title.
    if (showNames.any { name.equals(it, ignoreCase = true) }) name = ""
    if (name.matches(Regex("Episode\\s*0*${episodeNumber ?: -1}", RegexOption.IGNORE_CASE))) name = ""
    return com.atlas.atlastv.ui.catalog.displayTitle(name).replace(Regex("\\s+"), " ").ifBlank { episodeNumber?.let { "Episode $it" } ?: "Episode" }
}

fun SeriesEpisodeItem.shortEpisodeCode(): String =
    listOfNotNull(seasonNumber?.let { "S$it" }, episodeNumber?.let { "E$it" }).joinToString(" ")

private val episodeCode = Regex("\\bS\\s*0*\\d+\\s*[- .]?\\s*E\\s*0*\\d+\\b", RegexOption.IGNORE_CASE)

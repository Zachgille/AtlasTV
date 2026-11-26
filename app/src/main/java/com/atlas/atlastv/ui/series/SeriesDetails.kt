package com.atlas.atlastv.ui.series

import com.atlas.atlastv.playback.WatchProgressContentType
import com.atlas.atlastv.playback.WatchProgressPolicy
import com.atlas.atlastv.playback.WatchProgressRecord
import com.atlas.atlastv.playback.resumeLabel

data class SeriesDetails(
    val name: String,
    val categoryName: String,
    val seriesId: Int,
    val isWatchlisted: Boolean,
    val selectedIndex: Int,
    val visibleCount: Int,
    val cacheSource: String,
    val cacheAgeMs: Long?,
)

data class SeriesEpisodeDetails(
    val title: String,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val episodeId: Int,
    val containerExtension: String,
    val selectedIndex: Int,
    val visibleCount: Int,
    val resumeLabel: String?,
    val progressPercent: Int?,
    val progressLabel: String,
    val completed: Boolean,
    val resumeEligible: Boolean,
    val redactedStreamUrl: String,
)

data class SeriesSeason(
    val seasonNumber: Int?,
    val label: String,
    val episodeCount: Int,
)

fun SeriesCatalogState.Ready.selectedSeriesDetails(): SeriesDetails? {
    val visible = visibleSeries()
    val selectedIndex = visible.indexOfFirst { it.seriesId == selectedSeriesId }
    val item = visible.getOrNull(selectedIndex) ?: return null
    return seriesDetails(item = item, selectedIndex = selectedIndex, visibleCount = visible.size)
}

fun SeriesCatalogState.Ready.selectedNormalBrowseSeriesDetails(): SeriesDetails? {
    return normalBrowseSeriesDetailsFor(selectedSeriesId)
}

fun SeriesCatalogState.Ready.normalBrowseSeriesDetailsFor(seriesId: Int?): SeriesDetails? {
    val selectedIndex = series.indexOfFirst { it.seriesId == seriesId }
    val item = series.getOrNull(selectedIndex) ?: return null
    return seriesDetails(item = item, selectedIndex = selectedIndex, visibleCount = series.size)
}

private fun SeriesCatalogState.Ready.seriesDetails(
    item: SeriesItem,
    selectedIndex: Int,
    visibleCount: Int,
): SeriesDetails =
    SeriesDetails(
        name = item.name,
        categoryName = item.categoryName,
        seriesId = item.seriesId,
        isWatchlisted = item.seriesId in watchlistSeriesIds,
        selectedIndex = selectedIndex + 1,
        visibleCount = visibleCount,
        cacheSource = source,
        cacheAgeMs = cacheAgeMs,
    )

fun SeriesCatalogState.Ready.selectedEpisode(): SeriesEpisodeItem? =
    (episodeState as? SeriesEpisodeState.Ready)
        ?.episodesForSeason(selectedSeasonNumber)
        ?.firstOrNull { it.episodeId == selectedEpisodeId }
        ?: (episodeState as? SeriesEpisodeState.Ready)
            ?.episodesForSeason(selectedSeasonNumber)
            ?.firstOrNull()

fun SeriesCatalogState.Ready.selectedEpisodeDetails(): SeriesEpisodeDetails? {
    val ready = episodeState as? SeriesEpisodeState.Ready ?: return null
    val episode = selectedEpisode() ?: return null
    val visible = ready.episodesForSeason(selectedSeasonNumber)
    val selectedIndex = visible.indexOfFirst { it.episodeId == episode.episodeId }.coerceAtLeast(0)
    return episodeDetails(episode, selectedIndex + 1, visible.size)
}

fun SeriesCatalogState.Ready.episodeDetails(
    episode: SeriesEpisodeItem,
    selectedIndex: Int = 1,
    visibleCount: Int = 1,
): SeriesEpisodeDetails {
    val progress = episode.matchingProgress(selectedSeriesId, watchProgress[episode.episodeId])
    val resumeEligible = progress?.let { it.contentType == WatchProgressContentType.SeriesEpisode && WatchProgressPolicy.isResumeEligible(it) } == true
    val completed = progress?.completed == true
    val resumeLabel = progress?.takeIf { resumeEligible }?.resumeLabel()
    val progressPercent = progress?.durationMs?.takeIf { it > 0L }?.let { duration ->
        ((progress.positionMs.toDouble() / duration.toDouble()) * 100).toInt().coerceIn(0, 100)
    }
    return SeriesEpisodeDetails(
        title = episode.title,
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        episodeId = episode.episodeId,
        containerExtension = episode.containerExtension,
        selectedIndex = selectedIndex,
        visibleCount = visibleCount,
        resumeLabel = resumeLabel,
        progressPercent = progressPercent,
        progressLabel = when {
            completed -> "Completed"
            resumeLabel != null -> resumeLabel
            progressPercent != null -> "Progress: $progressPercent%"
            else -> "Not started"
        },
        completed = completed,
        resumeEligible = resumeEligible,
        redactedStreamUrl = episode.redactedStreamUrl,
    )
}

fun SeriesEpisodeState.Ready.seasons(): List<SeriesSeason> =
    episodes
        .groupBy { it.seasonNumber }
        .map { (seasonNumber, episodes) ->
            SeriesSeason(
                seasonNumber = seasonNumber,
                label = seasonNumber?.let { "Season $it" } ?: "Season unknown",
                episodeCount = episodes.size,
            )
        }
        .sortedBy { it.seasonNumber ?: Int.MAX_VALUE }

fun SeriesEpisodeState.Ready.episodesForSeason(seasonNumber: Int?): List<SeriesEpisodeItem> =
    episodes
        .filter { it.seasonNumber == seasonNumber }
        .sortedWith(
            compareBy<SeriesEpisodeItem> { it.episodeNumber ?: Int.MAX_VALUE }
                .thenBy { it.title.lowercase() }
                .thenBy { it.episodeId },
        )

fun SeriesCatalogState.Ready.withValidEpisodeSelection(preferWatchProgress: Boolean = false): SeriesCatalogState.Ready {
    // Keep a restored/playback choice while the cached or network episode list is loading.
    val readyEpisodes = episodeState as? SeriesEpisodeState.Ready ?: return this
    val explicitEpisode = readyEpisodes.episodes.firstOrNull { it.episodeId == selectedEpisodeId }
    if (explicitEpisode != null && !preferWatchProgress) return copy(selectedSeasonNumber = explicitEpisode.seasonNumber, selectedEpisodeId = explicitEpisode.episodeId)
    if (preferWatchProgress || (selectedSeasonNumber == null && selectedEpisodeId == null)) {
        val ordered = readyEpisodes.episodes.sortedWith(compareBy<SeriesEpisodeItem> { it.seasonNumber ?: Int.MAX_VALUE }
            .thenBy { it.episodeNumber ?: Int.MAX_VALUE }.thenBy { it.episodeId })
        val validIds = ordered.mapTo(hashSetOf()) { it.episodeId }
        val latest = watchProgress.values.filter { it.contentType == WatchProgressContentType.SeriesEpisode &&
            it.seriesId == selectedSeriesId && it.streamId in validIds &&
            (it.completed || WatchProgressPolicy.isResumeEligible(it) || WatchProgressPolicy.isCompleted(it.positionMs, it.durationMs)) }
            .maxWithOrNull(compareBy<WatchProgressRecord> { it.updatedAtMs }.thenBy { it.streamId })
        if (latest != null) {
            val index = ordered.indexOfFirst { it.episodeId == latest.streamId }
            val completed = latest.completed || WatchProgressPolicy.isCompleted(latest.positionMs, latest.durationMs)
            val target = if (completed) ordered.drop(index + 1).firstOrNull { episode ->
                val progress = watchProgress[episode.episodeId]?.takeIf { it.seriesId == selectedSeriesId }
                progress?.completed != true && (progress == null || !WatchProgressPolicy.isCompleted(progress.positionMs, progress.durationMs))
            } ?: ordered[index] else ordered[index]
            return copy(selectedSeasonNumber = target.seasonNumber, selectedEpisodeId = target.episodeId)
        }
    }
    if (explicitEpisode != null) return copy(selectedSeasonNumber = explicitEpisode.seasonNumber, selectedEpisodeId = explicitEpisode.episodeId)
    val seasons = readyEpisodes.seasons()
    val chosenSeason = seasons.firstOrNull { it.seasonNumber == selectedSeasonNumber && (selectedSeasonNumber != null || selectedEpisodeId != null) }
        ?: seasons.firstOrNull()
    val season = chosenSeason?.seasonNumber
    val episodes = readyEpisodes.episodesForSeason(season)
    val episodeId = episodes.firstOrNull { it.episodeId == selectedEpisodeId }?.episodeId ?: episodes.firstOrNull()?.episodeId
    return copy(selectedSeasonNumber = season, selectedEpisodeId = episodeId)
}

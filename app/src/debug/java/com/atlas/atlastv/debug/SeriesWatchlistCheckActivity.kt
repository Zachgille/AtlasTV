package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** Memory-only Watchlist/catalog/writes, no personal stores or network. */
class SeriesWatchlistCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var ids = (1..513).toSet()
    var gridsReleased = true
    var gridsFail = false
    var writesReleased = true
    var writesFail = false
    var orphanId: Int? = null
    var watchlistAtEnd = false
    var reverseOrder = false
    val requests = AtomicInteger()
    val canceled = AtomicInteger()
    val writes = AtomicInteger()
    val metadata = AtomicInteger()
    private fun movie(id: Int) = SeriesItem("Saved Show $id", "Shows", id, available = id != orphanId)
    val browser = SeriesWatchlistBrowser(SeriesGridStateHolder(SeriesGridSource { index, selectedId ->
        requests.incrementAndGet()
        try {
            while (!gridsReleased) delay(10)
            if (gridsFail) error("private diagnostic")
            val sorted = if (reverseOrder) ids.sortedDescending() else ids.sorted()
            val total = sorted.size
            val anchor = (selectedId?.let { sorted.indexOf(it).takeIf { n -> n >= 0 } } ?: index).coerceIn(0, maxOf(0, total - 1))
            val start = maxOf(0, anchor / 40 - 2) * 40
            SeriesGridWindow(total, start, anchor, sorted.drop(start).take(200).map(::movie))
        } catch (cancelled: CancellationException) { canceled.incrementAndGet(); throw cancelled }
    }, allowEmptyRefresh = true))
    val holder = SeriesCatalogStateHolder(object : SeriesCatalogSource {
        override suspend fun load(previous: SeriesCatalogState.Ready?): SeriesCatalogState {
            val saved = ids.sorted().take(18).map(::movie)
            val watchlist = saved.takeIf { it.isNotEmpty() }?.let { BrowseRow("series_watchlist", "Watchlist", it) }
            val other = (0..7).map { BrowseRow("other-$it", "Shows $it", listOf(movie(514), movie(515))) }
            val rows = if (watchlistAtEnd) other + listOfNotNull(watchlist) else listOfNotNull(watchlist) + other
            val selected = previous?.selectedSeriesId?.takeIf { it != orphanId || it in ids }?.let(::movie)
            return SeriesCatalogState.Ready("check", 0, (rows.flatMap { it.items } + listOfNotNull(selected)).distinctBy { it.seriesId },
                emptyList(), selectedSeriesId = selected?.seriesId ?: rows.firstOrNull()?.items?.firstOrNull()?.seriesId,
                browseRows = rows, watchlistSeriesIds = ids)
        }
        override suspend fun saveWatchlist(series: List<SeriesItem>, ids: Set<Int>) {
            writes.incrementAndGet()
            while (!writesReleased) delay(10)
            if (writesFail) error("private provider diagnostic")
            this@SeriesWatchlistCheckActivity.ids = ids.toSet()
        }
    }, scope, loadEpisodes = { id ->
        metadata.incrementAndGet()
        try {
            while (!episodesReleased) delay(10)
            if (episodesFail) error("private")
            SeriesEpisodeLoadResult.Success((1..3).flatMap { season -> (1..4).map { episode ->
                SeriesEpisodeItem("Show $id - S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}",
                    season, episode, id * 100 + season * 10 + episode, "mp4", "room://episode") } })
        } catch (cancelled: CancellationException) { canceledEpisodes.incrementAndGet(); throw cancelled }
    })
    var episodesReleased = true
    var episodesFail = false
    val canceledEpisodes = AtomicInteger()
    private var detailsRequest by mutableIntStateOf(0)
    private var selectedForDetails by mutableStateOf<SeriesItem?>(null)
    private var selectedEpisode by mutableStateOf<SeriesEpisodeItem?>(null)
    private var destination by mutableStateOf(false)
    private var playback by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AtlasTvTheme {
            when {
                destination -> BrowseSearchButton("Shows", { destination = false }, "watchlist_check_home_back")
                playback -> BrowseSearchButton("Back", { playback = false }, "watchlist_check_playback_back")
                else -> SeriesScreen(holder, onBack = { destination = true }, watchlistBrowser = browser,
                    openSelectedSeriesDetailsRequest = detailsRequest, selectedSeriesForDetails = selectedForDetails,
                    selectedEpisodeForDetails = selectedEpisode, onEpisodeSelected = { series, episode ->
                        selectedForDetails = series; selectedEpisode = episode
                        holder.rememberSeries(series); holder.rememberEpisode(episode)
                        detailsRequest++; playback = true
                    })
            }
        } }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

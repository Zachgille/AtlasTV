package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.browse.BrowseRow
import com.atlas.atlastv.ui.browse.BrowseSearchButton
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.theme.LumaireTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic paging/episode navigation without personal data or provider traffic. */
class SeriesGridCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pages = Channel<Boolean>(Channel.UNLIMITED)
    private val episodes = Channel<Boolean>(Channel.UNLIMITED)
    val cancelledLoads = AtomicInteger()
    val requests = AtomicInteger()
    val episodeRequests = AtomicInteger()
    val cancelledEpisodes = AtomicInteger()
    private fun show(index: Int) = SeriesItem("Show ${index + 1}", "Shows", index + 1)
    val grid = SeriesGridStateHolder(SeriesGridSource { index, _ ->
        requests.incrementAndGet()
        try {
            if (!pages.receive()) error("private diagnostic")
            val anchor = index.coerceIn(0, 512)
            val start = maxOf(0, anchor / 40 - 2) * 40
            SeriesGridWindow(513, start, anchor, (start until minOf(start + 200, 513)).map(::show))
        } catch (cancelled: CancellationException) { cancelledLoads.incrementAndGet(); throw cancelled }
    })
    val holder = SeriesCatalogStateHolder(object : SeriesCatalogSource {
        override suspend fun load(previous: SeriesCatalogState.Ready?): SeriesCatalogState {
            val items = listOf(show(0), show(1))
            return SeriesCatalogState.Ready("check", 0, items, listOf(SeriesCategory("all", "All", 513)),
                browseRows = listOf(BrowseRow("shows", "Shows", items)))
        }
    }, scope, loadEpisodes = {
        episodeRequests.incrementAndGet()
        try {
            if (!episodes.receive()) error("private diagnostic")
            SeriesEpisodeLoadResult.Success(listOf(
                SeriesEpisodeItem("Pilot", 1, 1, 11, "mp4", "room://episode/11"),
                SeriesEpisodeItem("Second episode", 1, 2, 12, "mp4", "room://episode/12"),
                SeriesEpisodeItem("Next season", 2, 1, 21, "mp4", "room://episode/21")))
        } catch (cancelled: CancellationException) { cancelledEpisodes.incrementAndGet(); throw cancelled }
    })
    private var destination by mutableStateOf(false)
    private var playback by mutableStateOf(false)
    private var detailsRequest by mutableIntStateOf(0)
    private var selectedForDetails by mutableStateOf<SeriesItem?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LumaireTheme {
            when {
                destination -> Text("Home", Modifier.testTag("grid_check_destination"))
                playback -> BrowseSearchButton("Back", { playback = false }, "grid_check_playback_back")
                else -> SeriesScreen(holder, onBack = { destination = true }, gridHolder = grid,
                    openSelectedSeriesDetailsRequest = detailsRequest, selectedSeriesForDetails = selectedForDetails,
                    onEpisodeSelected = { series, episode ->
                        selectedForDetails = series; holder.rememberSeries(series); holder.rememberEpisode(episode)
                        detailsRequest++; playback = true
                    })
            }
        } }
    }
    fun complete() { pages.trySend(true) }
    fun fail() { pages.trySend(false) }
    fun completeEpisodes() { episodes.trySend(true) }
    fun failEpisodes() { episodes.trySend(false) }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

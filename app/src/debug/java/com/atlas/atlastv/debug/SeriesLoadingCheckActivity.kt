package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.browse.BrowseRow
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.theme.LumaireTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/** Controlled browse acceptance. Does not access account, catalog, watch state or network. */
class SeriesLoadingCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loads = Channel<Result<SeriesCatalogState>>(Channel.UNLIMITED)
    val cancelledLoads = AtomicInteger()
    private val episodeLoads = Channel<Result<SeriesEpisodeLoadResult>>(Channel.UNLIMITED)
    val episodeRequests = AtomicInteger()
    val cancelledEpisodes = AtomicInteger()
    val holder = SeriesCatalogStateHolder(object : SeriesCatalogSource {
        override suspend fun load(previous: SeriesCatalogState.Ready?): SeriesCatalogState {
            try { return loads.receive().getOrThrow() }
            catch (cancelled: CancellationException) { cancelledLoads.incrementAndGet(); throw cancelled }
        }
    }, scope, loadEpisodes = {
        episodeRequests.incrementAndGet()
        try { episodeLoads.receive().getOrThrow() }
        catch (cancelled: CancellationException) { cancelledEpisodes.incrementAndGet(); throw cancelled }
    })
    private var destination by mutableStateOf(false)
    var openedEpisodeId by mutableStateOf<Int?>(null)
        private set
    private var detailRequest by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LumaireTheme {
                if (destination) Text("Home", Modifier.testTag("series_check_destination"))
                else if (openedEpisodeId != null) {
                    androidx.activity.compose.BackHandler { openedEpisodeId = null }
                    Text("Episode $openedEpisodeId", Modifier.testTag("series_check_episode_destination"))
                } else SeriesScreen(holder, onBack = { destination = true },
                    openSelectedSeriesDetailsRequest = detailRequest,
                    selectedSeriesForDetails = (holder.state as? SeriesCatalogState.Ready)?.series?.firstOrNull { it.seriesId == (holder.state as? SeriesCatalogState.Ready)?.selectedSeriesId },
                    onEpisodeSelected = { _, episode -> detailRequest++; openedEpisodeId = episode.episodeId })
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    fun complete(title: String = "Saved show") {
        val series = listOf(SeriesItem(title, "Shows", 1), SeriesItem("Another show", "Shows", 2))
        loads.trySend(Result.success(SeriesCatalogState.Ready("check", 0, series,
            listOf(SeriesCategory("all", "All", 2)), browseRows = listOf(BrowseRow("series_saved", "Series", series)))))
    }
    fun completeEpisodes(metadata: SeriesShowMetadata = SeriesShowMetadata()) {
        episodeLoads.trySend(Result.success(SeriesEpisodeLoadResult.Success(listOf(
            SeriesEpisodeItem("Pilot", 1, 1, 11, "mp4", "room://episode/11"),
            SeriesEpisodeItem("Second episode", 1, 2, 12, "mp4", "room://episode/12"),
            SeriesEpisodeItem("Next season", 2, 1, 21, "mp4", "room://episode/21"),
        ), metadata)))
    }
    fun failEpisodes() { episodeLoads.trySend(Result.failure(IllegalStateException("private diagnostic"))) }
    fun completeLongEpisodes() {
        episodeLoads.trySend(Result.success(SeriesEpisodeLoadResult.Success((1..24).flatMap { season ->
            (1..64).map { episode -> SeriesEpisodeItem("Saved show - S${season.toString().padStart(2,'0')}E${episode.toString().padStart(2,'0')}", season, episode, season*1000+episode, "mp4", "") }
        })))
    }
    fun fail() { loads.trySend(Result.failure(IllegalStateException("private diagnostic"))) }
}

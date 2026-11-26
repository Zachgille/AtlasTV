package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.debug.DebugLivePlaybackScreen
import com.atlas.atlastv.ui.debug.PreparingPlaybackScreen
import com.atlas.atlastv.ui.livetv.LiveTvChannel
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.theme.LumaireTheme
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

/** Generated local video and unique cache progress only. Does not read/write personal library/account files. */
class NextEpisodeCheckActivity : ComponentActivity() {
    lateinit var store: WatchProgressStore
    lateinit var writer: WatchProgressCoordinator
    lateinit var next: NextEpisodeStateHolder
    lateinit var seriesHolder: SeriesCatalogStateHolder
    var lookupReleased by mutableStateOf(false)
    var lookupFails = false
    var transitions = 0
        private set
    var autoplayEnabled by mutableStateOf(false)
    var currentEpisodeId by mutableStateOf(11)
        private set
    val show = SeriesItem("Local series", "", 1)
    val episodes = listOf(
        SeriesEpisodeItem("Third", 2, 1, 21, "mp4", "local"),
        SeriesEpisodeItem("Second", 1, 2, 12, "mp4", "local"),
        SeriesEpisodeItem("First", 1, 1, 11, "mp4", "local"),
    )
    private fun record(episode: SeriesEpisodeItem) = WatchProgressRecord(WatchProgressContentType.SeriesEpisode,
        episode.episodeId, title = episode.title, seriesId = 1, seriesTitle = show.name,
        episodeTitle = "S${episode.seasonNumber}E${episode.episodeNumber}",
        durationMs = 600_000, positionMs = 0, updatedAtMs = 0, extension = "mp4")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentEpisodeId = intent.getIntExtra("initial_episode", 11)
        lookupReleased = !intent.getBooleanExtra("stall_lookup", false)
        lookupFails = intent.getBooleanExtra("fail_lookup", false)
        autoplayEnabled = intent.getBooleanExtra("autoplay", false)
        store = WatchProgressStore(File(cacheDir, "next-episode-check-${UUID.randomUUID()}.json"))
        writer = WatchProgressCoordinator.forStore(store)
        next = NextEpisodeStateHolder({
            while (!lookupReleased) delay(20)
            if (lookupFails) SeriesEpisodeLoadResult.Failure("Unavailable") else SeriesEpisodeLoadResult.Success(episodes)
        })
        setContent {
            LumaireTheme {
                val scope = rememberCoroutineScope()
                seriesHolder = remember { SeriesCatalogStateHolder(object : SeriesCatalogSource {
                    override suspend fun load(previous: SeriesCatalogState.Ready?) = previous ?: SeriesCatalogState.Ready(
                        "Local", null, listOf(show), emptyList(), selectedSeriesId = 1,
                        episodeState = SeriesEpisodeState.Ready(episodes))
                }, scope) }
                val preparation = remember { PlaybackStartStateHolder(scope, writer::loadSnapshot) }
                val preparing by preparation.state.collectAsState()
                var screen by remember { mutableStateOf("preparing") }
                var active by remember { mutableStateOf<PlaybackStartState.Ready?>(null) }
                fun start(episode: SeriesEpisodeItem) {
                    currentEpisodeId = episode.episodeId
                    seriesHolder.rememberSeries(show)
                    next.loadedFor(1)?.let { seriesHolder.rememberPlaybackEpisodes(1, it) }
                    seriesHolder.rememberEpisode(episode)
                    preparation.start(record(episode)) { "asset:///progress-check.mp4" }
                    screen = "preparing"
                }
                LaunchedEffect(Unit) {
                    seriesHolder.ensureCachedCatalog()
                    store.saveRecordsAsync(listOf(record(episodes.first { it.episodeId == 11 }).copy(positionMs = 255_000),
                        record(episodes.first { it.episodeId == 12 }).copy(positionMs = 180_000)))
                    start(episodes.first { it.episodeId == currentEpisodeId })
                }
                LaunchedEffect(preparing) {
                    (preparing as? PlaybackStartState.Ready)?.let { active = it; preparation.cancel(); screen = "playback" }
                }
                when (screen) {
                    "preparing" -> PreparingPlaybackScreen(false, preparation::retry, { preparation.cancel(); screen = "details" })
                    "details" -> SeriesScreen(seriesHolder, onBack = { finish() }, onEpisodeSelected = { _, e -> start(e) },
                        openSelectedSeriesDetailsRequest = currentEpisodeId, selectedSeriesForDetails = show,
                        selectedEpisodeForDetails = episodes.first { it.episodeId == currentEpisodeId })
                    else -> active?.let { ready -> key(ready.record.streamId) {
                        LaunchedEffect(ready.record.key, next.retryVersion) { next.ensureNext(1, ready.record.streamId) }
                        DisposableEffect(Unit) { onDispose { next.deactivate() } }
                        DebugLivePlaybackScreen(
                            stream = LiveTvChannel("Local series · ${ready.record.episodeTitle}", "", ready.record.streamId, "mp4", "local"),
                            streamUrl = ready.playbackUrl!!, initialPositionMs = if (intent.getBooleanExtra("near_end", false) && transitions == 0) 598_000 else ready.resumeMs,
                            progressRecord = ready.record, progressStore = store,
                            nextEpisodeState = next.state, onRetryNextEpisode = next::retry,
                            autoplayEnabled = autoplayEnabled,
                            onNextEpisode = { transitions++; start(it) }, onBack = { screen = "details" },
                        )
                    } }
                }
            }
        }
    }
}

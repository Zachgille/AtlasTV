package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.room.Room
import androidx.tv.material3.Text
import com.atlas.atlastv.data.db.*
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.debug.*
import com.atlas.atlastv.ui.livetv.LiveTvChannel
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Actual local playback and cache-only Room/watch files; never opens the personal library. */
class StartOverCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var database: AtlasDatabase; private set
    lateinit var store: WatchProgressStore; private set
    lateinit var writer: WatchProgressCoordinator; private set
    lateinit var episodeActions: EpisodeWatchActionsStateHolder; private set
    lateinit var movieActions: MovieWatchActionsStateHolder; private set
    @Volatile var failManualSave = false
    @Volatile var failManualLoad = false
    @Volatile var manualSaveGate: CompletableDeferred<Unit>? = null
    @Volatile var manualWrites = 0; private set
    private var checkEpisodeActions = false
    private var checkMovieActions = false
    lateinit var movieHolder: MoviesCatalogStateHolder; private set
    lateinit var seriesHolder: SeriesCatalogStateHolder; private set
    lateinit var starter: PlaybackStartStateHolder; private set
    var failPrepare = false
    val prepareRelease = CompletableDeferred<Unit>()
    var stage by mutableStateOf("details"); private set
    private var ready by mutableStateOf(false)
    private var detailRequest by mutableStateOf(1)
    var movie = MovieItem("A Clean Movie (2020)", "Movies", 1, "mp4", ""); private set
    var series = SeriesItem("A Clean Show (2020)", "Shows", 7); private set
    var episodes = listOf(SeriesEpisodeItem("Pilot", 1, 1, 101, "mp4", ""),
        SeriesEpisodeItem("Second season", 2, 1, 102, "mp4", ""), SeriesEpisodeItem("A fresh start", 2, 2, 103, "mp4", ""))
        private set
    private var selectedEpisode by mutableStateOf(episodes.last())
    lateinit var baseline: List<WatchProgressRecord>; private set
    @Volatile var startCount = 0; private set
    @Volatile var prepared: PlaybackStartState.Ready? = null; private set
    var isSeries = false; private set
    private var mediaPath = "asset:///progress-check.mp4"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra("check_id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,64}")) } ?: UUID.randomUUID().toString()
        database = Room.databaseBuilder(applicationContext, AtlasDatabase::class.java, File(cacheDir, "start-over-$id.db").absolutePath).build()
        store = WatchProgressStore(File(cacheDir, "start-over-$id.json"), WatchProgressRoomStore(database.watchProgressDao()))
        checkEpisodeActions = intent.getBooleanExtra("episode_actions", false)
        checkMovieActions = intent.getBooleanExtra("movie_actions", false)
        writer = if (checkEpisodeActions || checkMovieActions) WatchProgressCoordinator(object : WatchProgressBackend {
            override suspend fun load(): WatchProgressSnapshot {
                if (failManualLoad) error("private check diagnostic")
                return store.loadAsync()
            }
            override suspend fun save(record: WatchProgressRecord) {
                manualSaveGate?.await()
                if (failManualSave) error("private check diagnostic")
                store.saveRecordAsync(record); manualWrites++
            }
        }, if (checkMovieActions) scope else manualWriterScope, 30_000) else WatchProgressCoordinator.forStore(store)
        episodeActions = EpisodeWatchActionsStateHolder(writer, scope)
        movieActions = MovieWatchActionsStateHolder(writer, scope)
        if (intent.getBooleanExtra("many_episodes", false)) episodes = episodes + (3..24).flatMap { season ->
            (1..4).map { number -> SeriesEpisodeItem("Episode $number", season, number, season * 1000 + number, "mp4", "",
                durationMs = if (season == 24 && number == 4) null else 600_000) }
        }
        isSeries = intent.getBooleanExtra("series", false)
        if (intent.getBooleanExtra("unavailable", false)) { movie = movie.copy(available = false); series = series.copy(available = false) }
        failPrepare = intent.getBooleanExtra("fail_prepare", false)
        if (!intent.getBooleanExtra("stall_prepare", false)) prepareRelease.complete(Unit)
        mediaPath = when {
            intent.getBooleanExtra("broken_media", false) -> "asset:///missing-start-over.mp4"
            intent.getBooleanExtra("audio_only", false) -> "asset:///start-over-audio-only.mka"
            else -> mediaPath
        }
        val position = intent.getLongExtra("position", 45_000)
        val completed = intent.getBooleanExtra("completed", false)
        val now = System.currentTimeMillis() - 60_000
        baseline = listOf(
            WatchProgressRecord(WatchProgressContentType.Movie, 1, title = movie.title, durationMs = 600_000, positionMs = position, updatedAtMs = now, completed = completed),
            WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 103, 7, "A fresh start", seriesTitle = series.name,
                durationMs = 600_000, positionMs = position, updatedAtMs = now, completed = completed),
            WatchProgressRecord(WatchProgressContentType.Movie, 103, title = "Same numeric ID, different Movie", durationMs = 600_000, positionMs = 90_000, updatedAtMs = now),
            WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 101, 7, "Pilot", durationMs = 600_000, positionMs = 30_000, updatedAtMs = now),
            WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 102, 7, "Completed", durationMs = 600_000, positionMs = 600_000, updatedAtMs = now, completed = true))
        movieHolder = MoviesCatalogStateHolder(object : MoviesCatalogSource {
            override suspend fun load(previous: MoviesCatalogState.Ready?) = MoviesCatalogState.Ready("check", 0,
                listOf(movie), emptyList(), selectedMovieStreamId = 1, watchlistStreamIds = setOf(1),
                watchProgress = writer.loadSnapshot().records.filter { it.contentType == WatchProgressContentType.Movie }.associateBy { it.streamId })
        }, scope, loadMovieInfo = { MovieMetadata("A concise synopsis.", "Drama", "2020", 10) })
        seriesHolder = SeriesCatalogStateHolder(object : SeriesCatalogSource {
            override suspend fun load(previous: SeriesCatalogState.Ready?) = SeriesCatalogState.Ready("check", 0,
                listOf(series), emptyList(), selectedSeriesId = 7, watchlistSeriesIds = setOf(7),
                watchProgress = writer.loadSnapshot().records.filter { it.contentType == WatchProgressContentType.SeriesEpisode }.associateBy { it.streamId })
        }, scope, loadEpisodes = { SeriesEpisodeLoadResult.Success(episodes, SeriesShowMetadata("A concise synopsis.", "Drama", "2020")) })
        starter = PlaybackStartStateHolder(scope, writer::loadSnapshot)
        scope.launch {
            val existing = if (checkMovieActions) store.loadAsync().records else emptyList()
            if (existing.isEmpty()) {
                if (checkMovieActions) baseline = baseline + WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 1, 99,
                    "Same numeric ID, different episode", durationMs = 600_000, positionMs = 90_000, updatedAtMs = now)
                store.saveRecordsAsync(baseline)
                withContext(Dispatchers.IO) {
                    database.movieWatchlistDao().insertAll(listOf(MovieWatchlistEntity(1, movie.title, movie.categoryName, "mp4", now)))
                    database.seriesWatchlistDao().insertAll(listOf(SeriesWatchlistEntity(7, series.name, series.categoryName, now)))
                }
            } else baseline = existing
            ready = true
        }
        setContent { AtlasTvTheme {
            val start by starter.state.collectAsState()
            val revision by writer.revision.collectAsState()
            LaunchedEffect(revision) { movieHolder.refreshWatchProgress(); seriesHolder.refreshWatchProgress() }
            LaunchedEffect(start) {
                val value = start as? PlaybackStartState.Ready
                if (stage == "preparing" && value != null) { prepared = value; startCount++; stage = "playback"; starter.cancel() }
            }
            when {
                !ready -> Text("Loading local check", Modifier.testTag("start_over_check_loading"))
                stage == "preparing" -> PreparingPlaybackScreen(start == PlaybackStartState.Failed, starter::retry) { starter.cancel(); returnToDetails() }
                stage == "playback" -> prepared?.let { value -> DebugLivePlaybackScreen(
                    stream = LiveTvChannel(if (isSeries) "Local episode" else "Local Movie", "", 1, "mp4", ""),
                    streamUrl = value.playbackUrl!!, initialPositionMs = value.resumeMs, progressRecord = value.record,
                    progressStore = store, progressCoordinator = writer, startedFromBeginning = value.startedFromBeginning, onBack = ::returnToDetails) }
                isSeries -> SeriesScreen(seriesHolder, onBack = { finish() }, selectedSeriesForDetails = series,
                    selectedEpisodeForDetails = selectedEpisode, openSelectedSeriesDetailsRequest = detailRequest,
                    onEpisodeSelected = { _, episode -> begin(episode, false) }, onStartOver = { _, episode -> begin(episode, true) },
                    episodeWatchActions = episodeActions.takeIf { checkEpisodeActions })
                else -> MoviesScreen(movieHolder, onBack = { finish() }, selectedMovieForDetails = movie,
                    openSelectedMovieDetailsRequest = detailRequest, onMovieSelected = { begin(null, false) }, onStartOver = { begin(null, true) },
                    movieWatchActions = movieActions.takeIf { checkMovieActions })
            }
        } }
    }
    private fun begin(episode: SeriesEpisodeItem?, fromBeginning: Boolean) {
        if (episode != null) selectedEpisode = episode
        val base = baseline.firstOrNull { if (episode == null) it.contentType == WatchProgressContentType.Movie && it.streamId == 1
            else it.contentType == WatchProgressContentType.SeriesEpisode && it.streamId == episode.episodeId }
            ?: episode!!.manualProgressRecord(series)
        val prepare: suspend () -> String? = { prepareRelease.await(); if (failPrepare) error("private diagnostic"); mediaPath }
        stage = "preparing"
        if (fromBeginning) starter.startOver(base, prepare) else starter.start(base, prepare)
    }
    private fun returnToDetails() { stage = "details"; detailRequest++; movieHolder.refreshWatchProgress(); seriesHolder.refreshWatchProgress() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object { private val manualWriterScope = CoroutineScope(SupervisorJob() + Dispatchers.IO) }
}

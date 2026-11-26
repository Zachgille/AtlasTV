package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.atlas.atlastv.ui.browse.BrowseRow
import com.atlas.atlastv.ui.browse.BrowseSearchButton
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Controlled optional metadata/navigation; no personal database, account or video requests. */
class MovieDetailCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val metadata = ConcurrentHashMap<Int, Channel<MovieMetadata?>>()
    val requestedIds = CopyOnWriteArrayList<Int>()
    var openedMovieId: Int? = null
        private set
    var movieTitle by mutableStateOf("A Movie (2010)")
    private val first get() = MovieItem(movieTitle, "Movies", 1, "mp4", "")
    private var destination by mutableStateOf(false)
    private var playback by mutableStateOf(false)
    private var savedWatchlistIds: Set<Int> = emptySet()
    private var runtimeRecord: com.atlas.atlastv.playback.WatchProgressRecord? = null
    fun setRuntimeCheckpoint(duration: Long?) {
        runtimeRecord = com.atlas.atlastv.playback.WatchProgressRecord(com.atlas.atlastv.playback.WatchProgressContentType.Movie,
            1, title = "Movie", durationMs = duration, positionMs = 60_000, updatedAtMs = 1)
        holder.refreshWatchProgress()
    }
    val holder = MoviesCatalogStateHolder(object : MoviesCatalogSource {
        override suspend fun load(previous: MoviesCatalogState.Ready?): MoviesCatalogState {
            val movies = listOf(first, MovieItem("Another Movie (2020)", "Movies", 2, "mp4", ""))
            return MoviesCatalogState.Ready("check", 0, movies, emptyList(), watchlistStreamIds = savedWatchlistIds,
                watchProgress = runtimeRecord?.let { mapOf(1 to it) } ?: emptyMap(),
                browseRows = listOf(BrowseRow("movies", "Movies", movies)))
        }
        override suspend fun saveWatchlist(movies: List<MovieItem>, ids: Set<Int>) { savedWatchlistIds = ids }
    }, scope, loadMovieInfo = { id ->
        requestedIds.add(id)
        metadata.getOrPut(id) { Channel(Channel.UNLIMITED) }.receive()
    })
    fun complete(id: Int = 1, value: MovieMetadata? = MovieMetadata("A dream changes everything.", "Action / Mystery", "2010", 148)) {
        metadata.getOrPut(id) { Channel(Channel.UNLIMITED) }.trySend(value)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AtlasTvTheme {
            when {
                destination -> BrowseSearchButton("Home", {}, "movie_check_destination")
                playback -> BrowseSearchButton("Back", { playback = false }, "movie_check_playback_back")
                else -> MoviesScreen(holder, onBack = { destination = true }, onMovieSelected = { openedMovieId = it.streamId; playback = true },
                    openSelectedMovieDetailsRequest = 1, selectedMovieForDetails = first)
            }
        } }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

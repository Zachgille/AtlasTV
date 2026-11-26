package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** Memory-only Watchlist/catalog/writes, no personal stores or network. */
class MovieWatchlistCheckActivity : ComponentActivity() {
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
    private fun movie(id: Int) = MovieItem("Saved Movie $id", "Movies", id, "mp4", "room://safe", available = id != orphanId)
    val browser = MovieWatchlistBrowser(MovieGridStateHolder(MovieGridSource { index, selectedId ->
        requests.incrementAndGet()
        try {
            while (!gridsReleased) delay(10)
            if (gridsFail) error("private diagnostic")
            val sorted = if (reverseOrder) ids.sortedDescending() else ids.sorted()
            val total = sorted.size
            val anchor = (selectedId?.let { sorted.indexOf(it).takeIf { n -> n >= 0 } } ?: index).coerceIn(0, maxOf(0, total - 1))
            val start = maxOf(0, anchor / 40 - 2) * 40
            MovieGridWindow(total, start, anchor, sorted.drop(start).take(200).map(::movie))
        } catch (cancelled: CancellationException) { canceled.incrementAndGet(); throw cancelled }
    }, allowEmptyRefresh = true))
    val holder = MoviesCatalogStateHolder(object : MoviesCatalogSource {
        override suspend fun load(previous: MoviesCatalogState.Ready?): MoviesCatalogState {
            val saved = ids.sorted().take(18).map(::movie)
            val watchlist = saved.takeIf { it.isNotEmpty() }?.let { BrowseRow("movies_watchlist", "Watchlist", it) }
            val other = (0..7).map { BrowseRow("other-$it", "Movies $it", listOf(movie(514), movie(515))) }
            val rows = if (watchlistAtEnd) other + listOfNotNull(watchlist) else listOfNotNull(watchlist) + other
            val selected = previous?.selectedMovieStreamId?.takeIf { it != orphanId || it in ids }?.let(::movie)
            return MoviesCatalogState.Ready("check", 0, (rows.flatMap { it.items } + listOfNotNull(selected)).distinctBy { it.streamId },
                emptyList(), selectedMovieStreamId = selected?.streamId ?: rows.firstOrNull()?.items?.firstOrNull()?.streamId,
                browseRows = rows, watchlistStreamIds = ids)
        }
        override suspend fun saveWatchlist(movies: List<MovieItem>, ids: Set<Int>) {
            writes.incrementAndGet()
            while (!writesReleased) delay(10)
            if (writesFail) error("private provider diagnostic")
            this@MovieWatchlistCheckActivity.ids = ids.toSet()
        }
    }, scope, loadMovieInfo = { metadata.incrementAndGet(); MovieMetadata() })
    private var destination by mutableStateOf(false)
    private var playback by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AtlasTvTheme {
            when {
                destination -> BrowseSearchButton("Movies", { destination = false }, "watchlist_check_home_back")
                playback -> BrowseSearchButton("Back", { playback = false }, "watchlist_check_playback_back")
                else -> MoviesScreen(holder, onBack = { destination = true }, onMovieSelected = { playback = true }, watchlistBrowser = browser)
            }
        } }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

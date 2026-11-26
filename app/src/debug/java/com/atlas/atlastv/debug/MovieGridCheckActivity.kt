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
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.theme.LumaireTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/** Deterministic navigation/paging UI without personal database, account, progress or network. */
class MovieGridCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pages = Channel<Boolean>(Channel.UNLIMITED)
    val cancelledLoads = AtomicInteger()
    val requests = AtomicInteger()
    private fun movie(index: Int) = MovieItem("Movie ${index + 1}", "Movies", index + 1, "mp4", "room://movie/$index")
    val grid = MovieGridStateHolder(MovieGridSource { index, _ ->
        requests.incrementAndGet()
        try {
            if (!pages.receive()) error("private diagnostic")
            val anchor = index.coerceIn(0, 512)
            val start = maxOf(0, anchor / 40 - 2) * 40
            MovieGridWindow(513, start, anchor, (start until minOf(start + 200, 513)).map(::movie))
        } catch (cancelled: CancellationException) { cancelledLoads.incrementAndGet(); throw cancelled }
    })
    private val movies = MoviesCatalogStateHolder(object : MoviesCatalogSource {
        override suspend fun load(previous: MoviesCatalogState.Ready?): MoviesCatalogState {
            val items = listOf(movie(0), movie(1))
            return MoviesCatalogState.Ready("check", 0, items, listOf(MovieCategory("all", "All", 513)), browseRows = listOf(BrowseRow("movies", "Movies", items)))
        }
    }, scope)
    private var destination by mutableStateOf(false)
    private var playback by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LumaireTheme {
            when {
                destination -> Text("Home", Modifier.testTag("grid_check_destination"))
                playback -> BrowseSearchButton("Back", { playback = false }, "grid_check_playback_back")
                else -> MoviesScreen(movies, onBack = { destination = true }, onMovieSelected = { playback = true }, gridHolder = grid)
            }
        } }
    }
    fun complete() { pages.trySend(true) }
    fun fail() { pages.trySend(false) }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

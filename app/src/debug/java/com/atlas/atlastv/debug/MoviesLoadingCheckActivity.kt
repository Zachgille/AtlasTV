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
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger

/** Controlled browse acceptance. Does not access account, catalog, watch state or network. */
class MoviesLoadingCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loads = Channel<Result<MoviesCatalogState>>(Channel.UNLIMITED)
    val cancelledLoads = AtomicInteger()
    val holder = MoviesCatalogStateHolder(object : MoviesCatalogSource {
        override suspend fun load(previous: MoviesCatalogState.Ready?): MoviesCatalogState {
            try { return loads.receive().getOrThrow() }
            catch (cancelled: CancellationException) { cancelledLoads.incrementAndGet(); throw cancelled }
        }
    }, scope)
    private var destination by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AtlasTvTheme {
                if (destination) Text("Home", Modifier.testTag("movies_check_destination"))
                else MoviesScreen(holder, onBack = { destination = true }, onMovieSelected = {})
            }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    fun complete() {
        val movies = listOf(MovieItem("Saved movie", "Movies", 1, "mp4", "room://1"), MovieItem("Another movie", "Movies", 2, "mp4", "room://2"))
        loads.trySend(Result.success(MoviesCatalogState.Ready("check", 0, movies,
            listOf(MovieCategory("all", "All", 2)), browseRows = listOf(BrowseRow("movies_saved", "Movies", movies)))))
    }
    fun fail() { loads.trySend(Result.failure(IllegalStateException("private diagnostic"))) }
}

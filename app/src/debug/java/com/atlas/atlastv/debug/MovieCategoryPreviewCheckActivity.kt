package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import com.atlas.atlastv.ui.browse.BrowseSearchButton
import androidx.compose.runtime.*
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.theme.LumaireTheme
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

/** Fake local category source, no personal store/account/network/video. */
class MovieCategoryPreviewCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val first = MovieItem("A Movie (2020) HEVC", "Drama", 1, "mp4", "room://safe")
    val library = (1..15).map { first.copy(streamId = it, title = if (it == 1) first.title else "Category Movie $it (2020)") }
    val requests = CopyOnWriteArrayList<Pair<Int, Int>>()
    val threads = CopyOnWriteArrayList<String>()
    val release = CompletableDeferred<Unit>()
    var removeCategory = false
    var removeParent = false
    var failPreview = false
    private var playback by mutableStateOf(false)
    private var selectedDetails by mutableStateOf<MovieItem?>(null)
    private var detailsRequest by mutableStateOf(1)
    var playedId: Int? = null
    lateinit var holder: MoviesCatalogStateHolder
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!intent.getBooleanExtra("blocked", false)) release.complete(Unit)
        failPreview = intent.getBooleanExtra("fail", false)
        holder = MoviesCatalogStateHolder(object : MoviesCatalogSource {
            override suspend fun load(previous: MoviesCatalogState.Ready?): MoviesCatalogState {
                val ready = previous ?: MoviesCatalogState.Ready("check", 0, listOf(first, first.copy(title = "Unrelated", streamId = 100, categoryName = "Comedy")), emptyList())
                return if (removeParent) ready.copy(movies = ready.movies.map { if (it.streamId == 1) it.copy(available = false) else it }) else ready
            }
            override suspend fun categoryPreview(movie: MovieItem, maxItems: Int): List<MovieItem> {
                requests.add(movie.streamId to maxItems); threads.add(Thread.currentThread().name)
                release.await()
                if (failPreview) error("private diagnostic")
                return if (removeCategory || intent.getBooleanExtra("singleton", false)) listOf(movie)
                else library + first.copy(streamId = 100, categoryName = "Comedy")
            }
        }, scope)
        setContent { LumaireTheme {
            if (playback) {
                BackHandler { playback = false }
                BrowseSearchButton("Back", { playback = false }, "category_mock_playback_back")
            } else MoviesScreen(holder, onBack = { finish() }, onMovieSelected = {
                playedId = it.streamId; selectedDetails = it; detailsRequest++; playback = true
            }, openSelectedMovieDetailsRequest = detailsRequest, selectedMovieForDetails = selectedDetails ?: first)
        } }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

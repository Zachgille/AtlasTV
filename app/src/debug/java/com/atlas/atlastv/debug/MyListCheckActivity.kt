package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.home.*
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.mylist.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** Memory-only mixed saved titles and normal detail screens; no personal files/network. */
class MyListCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var movieIds = (1..513).toSet()
    var showIds = (1..513).toSet()
    var orphans = emptySet<MyListKey>()
    var reversed = false
    var gridsReleased = true
    var gridsFail = false
    var writesReleased = true
    var writesFail = false
    var episodesReleased = true
    var episodesFail = false
    val reads = AtomicInteger()
    val canceled = AtomicInteger()
    val writes = AtomicInteger()
    val metadata = AtomicInteger()
    private fun movie(id: Int) = MovieItem("Saved Movie $id", "Movies", id, "mp4", "room://safe", available = MyListKey(MyListType.Movie, id) !in orphans)
    private fun show(id: Int) = SeriesItem("Saved Show $id", "Shows", id, available = MyListKey(MyListType.Show, id) !in orphans)
    val browser = MyListBrowser({ filter -> MyListGridSource { index, selected ->
        reads.incrementAndGet()
        try {
            while (!gridsReleased) delay(10)
            if (gridsFail) error("private fixture diagnostic")
            val items = (movieIds.map { MyListItem.Movie(movie(it)) } + showIds.map { MyListItem.Show(show(it)) })
                .filter { filter == MyListFilter.All || it.key.type.storageKey == filter.storageKey }
                .sortedWith(compareBy<MyListItem> { it.key.id }.thenBy { it.key.type.storageKey })
                .let { if (reversed) it.reversed() else it }
            val anchor = (selected?.let { key -> items.indexOfFirst { it.key == key }.takeIf { it >= 0 } } ?: index).coerceIn(0, maxOf(0, items.size - 1))
            val start = maxOf(0, anchor / 40 - 2) * 40
            CatalogGridWindow(items.size, start, anchor, items.drop(start).take(200))
        } catch (cancelled: CancellationException) { canceled.incrementAndGet(); throw cancelled }
    } })
    val movies = MoviesCatalogStateHolder(object : MoviesCatalogSource {
        override suspend fun load(previous: MoviesCatalogState.Ready?): MoviesCatalogState {
            val selected = previous?.selectedMovieStreamId?.let(::movie)
            val items = (movieIds.sorted().take(18).map(::movie) + listOfNotNull(selected)).distinctBy { it.streamId }
            return MoviesCatalogState.Ready("check", 0, items, emptyList(), selectedMovieStreamId = selected?.streamId ?: items.firstOrNull()?.streamId,
                browseRows = listOf(BrowseRow("saved", "Movies", items)), watchlistStreamIds = movieIds)
        }
        override suspend fun saveWatchlist(movies: List<MovieItem>, ids: Set<Int>) { save(); movieIds = ids.toSet() }
    }, scope, loadMovieInfo = { metadata.incrementAndGet(); MovieMetadata() })
    val shows = SeriesCatalogStateHolder(object : SeriesCatalogSource {
        override suspend fun load(previous: SeriesCatalogState.Ready?): SeriesCatalogState {
            val selected = previous?.selectedSeriesId?.let(::show)
            val items = (showIds.sorted().take(18).map(::show) + listOfNotNull(selected)).distinctBy { it.seriesId }
            return SeriesCatalogState.Ready("check", 0, items, emptyList(), selectedSeriesId = selected?.seriesId ?: items.firstOrNull()?.seriesId,
                browseRows = listOf(BrowseRow("saved", "Shows", items)), watchlistSeriesIds = showIds)
        }
        override suspend fun saveWatchlist(series: List<SeriesItem>, ids: Set<Int>) { save(); showIds = ids.toSet() }
    }, scope, loadEpisodes = { id ->
        metadata.incrementAndGet()
        while (!episodesReleased) delay(10)
        if (episodesFail) error("private fixture diagnostic")
        SeriesEpisodeLoadResult.Success((1..3).flatMap { season -> (1..4).map { episode ->
            SeriesEpisodeItem("Show $id - S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}", season, episode,
                id * 100 + season * 10 + episode, "mp4", "room://episode") } })
    })
    private suspend fun save() { writes.incrementAndGet(); while (!writesReleased) delay(10); if (writesFail) error("private fixture diagnostic") }
    private var destination by mutableStateOf("list")
    private var movieRequest by mutableIntStateOf(0)
    private var showRequest by mutableIntStateOf(0)
    private var selectedMovie by mutableStateOf<MovieItem?>(null)
    private var selectedShow by mutableStateOf<SeriesItem?>(null)
    private var selectedEpisode by mutableStateOf<SeriesEpisodeItem?>(null)
    private var playbackReturn = "movie"
    val home = HomeShellStateHolder({ HomeLoadResult(HomeShellState.Ready("check", 0,
        listOf(HomeContentRow("popular", "Movies", listOf(HomeCard("movie-1", "Saved Movie 1", HomeContentType.Movie, "Movies", 1, statusText = "")))), "movie-1")) })
    fun refresh() { movies.invalidateCachedCatalog(); shows.invalidateCachedCatalog() }
    fun showHome() { destination = "home" }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home.rememberNavDeparture("top_nav_item_my_list")
        setContent { AtlasTvTheme {
            when (destination) {
                "home" -> AtlasHomeScreen(home, onLiveTvSelected = {}, onMoviesSelected = {}, onSeriesSelected = {}, onMyListSelected = {
                    home.rememberNavDeparture("top_nav_item_my_list"); destination = "list"
                })
                "list" -> MyListScreen(browser, movies.catalogVersion + shows.catalogVersion, { destination = "home" }, { item ->
                    when (item) {
                        is MyListItem.Movie -> { selectedMovie = item.item; movies.rememberMovie(item.item); movieRequest++; destination = "movie" }
                        is MyListItem.Show -> { selectedShow = item.item; selectedEpisode = null; shows.rememberSeries(item.item); showRequest++; destination = "show" }
                    }
                }, statusMessage = when {
                    movies.canRetryWatchlistSave || shows.canRetryWatchlistSave -> "Couldn't save your change. Please try again."
                    movies.watchlistSaving || shows.watchlistSaving -> "Saving My List…"
                    else -> null
                }, onRetrySave = if (movies.canRetryWatchlistSave || shows.canRetryWatchlistSave)
                    ({ movies.retryWatchlistSave(); shows.retryWatchlistSave() }) else null)
                "movie" -> MoviesScreen(movies, { destination = "home" }, { playbackReturn = "movie"; movieRequest++; destination = "playback" },
                    openSelectedMovieDetailsRequest = movieRequest, selectedMovieForDetails = selectedMovie, onDetailsClosed = { destination = "list" })
                "show" -> SeriesScreen(shows, { destination = "home" }, { series, episode ->
                    selectedShow = series; selectedEpisode = episode; shows.rememberSeries(series); shows.rememberEpisode(episode)
                    playbackReturn = "show"; showRequest++; destination = "playback"
                }, openSelectedSeriesDetailsRequest = showRequest, selectedSeriesForDetails = selectedShow,
                    selectedEpisodeForDetails = selectedEpisode, onDetailsClosed = { destination = "list" })
                "playback" -> BrowseSearchButton("Back", { destination = playbackReturn }, "my_list_playback_back")
            }
        } }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

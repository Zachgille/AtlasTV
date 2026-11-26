package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import com.atlas.atlastv.playback.*
import com.atlas.atlastv.ui.home.*
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.theme.LumaireTheme
import kotlinx.coroutines.*

/** Pure in-memory presentation, navigation and progress; no account/files/network. */
class CardProgressCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val movie = MovieItem("A Movie With A Long Title (2010)", "Movies", 1, "mp4", "room://safe")
    val show = SeriesItem("A Show (2015)", "Shows", 3)
    var records = listOf(
        WatchProgressRecord(WatchProgressContentType.Movie, 1, title = movie.title, positionMs = 900_000, durationMs = 3_600_000, updatedAtMs = 4),
        WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 31, seriesId = 3, title = "A Show - S01E02", seriesTitle = show.name,
            episodeTitle = "A Show - S01E02", positionMs = 600_000, durationMs = 2_400_000, updatedAtMs = 3),
        WatchProgressRecord(WatchProgressContentType.Movie, 2, title = "Unknown Duration", positionMs = 120_000, updatedAtMs = 2),
        WatchProgressRecord(WatchProgressContentType.SeriesEpisode, 41, seriesId = 4, title = "Unnumbered Show - Pilot", seriesTitle = "Unnumbered Show",
            episodeTitle = "Pilot", positionMs = 180_000, durationMs = 1_800_000, updatedAtMs = 1))
    var destination by mutableStateOf("home")
    var selectedId: Int? = null
    val home = HomeShellStateHolder({ HomeLoadResult(HomeShellMapper().map("check", 0, listOf(movie), listOf(show), emptySet(), continueWatching = records)) })
    val movies = MoviesCatalogStateHolder(object : MoviesCatalogSource {
        override suspend fun load(previous: MoviesCatalogState.Ready?) = MoviesCatalogState.Ready("check", 0, listOf(movie), emptyList(),
            browseRows = listOf(BrowseRow("movies_continue", "Continue Watching", listOf(movie))), watchProgress = records.filter { it.contentType == WatchProgressContentType.Movie }.associateBy { it.streamId })
    }, scope)
    val shows = SeriesCatalogStateHolder(object : SeriesCatalogSource {
        override suspend fun load(previous: SeriesCatalogState.Ready?) = SeriesCatalogState.Ready("check", 0, listOf(show), emptyList(),
            browseRows = listOf(BrowseRow("series_continue", "Continue Watching", listOf(show))), watchProgress = records.filter { it.contentType == WatchProgressContentType.SeriesEpisode }.associateBy { it.streamId })
    }, scope)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { LumaireTheme { when (destination) {
            "home" -> AtlasHomeScreen(home, onLiveTvSelected = {}, onMoviesSelected = { destination = "movies" }, onSeriesSelected = { destination = "shows" },
                onHomeCardSelected = { selectedId = it.streamId; destination = "return" }, onRemoveContinueWatching = { card -> records = records.filterNot { it.streamId == card.streamId && (it.contentType == WatchProgressContentType.Movie) == (card.contentType == HomeContentType.Movie) }; home.refresh() })
            "movies" -> MoviesScreen(movies, { destination = "home" }, { selectedId = it.streamId; destination = "return" })
            "shows" -> SeriesScreen(shows, { destination = "home" })
            "return" -> BrowseSearchButton("Back", { destination = "home" }, "card_progress_return")
        } } }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

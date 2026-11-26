package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.home.*
import com.atlas.atlastv.ui.settings.*
import com.atlas.atlastv.ui.theme.LumaireTheme
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Home presentation/focus without personal storage, account or network access. */
class HomeHeroCheckActivity : ComponentActivity() {
    private val discoveryMode get() = intent?.getBooleanExtra("discovery_check", false) == true
    val primarySelections = CopyOnWriteArrayList<String>()
    val detailSelections = CopyOnWriteArrayList<String>()
    private fun discoveryRows() = listOf(
        HomeContentRow(HomeRowIds.ContinueWatching, "Continue Watching", listOf(movie, episode)),
        HomeContentRow(HomeRowIds.Watchlist, "Watchlist", listOf(movie.copy(id = "saved-movie"), savedShow)),
        HomeContentRow(HomeRowIds.Popular, "Discover", listOf(movie.copy(id = "discover-movie", progressLabel = null), savedShow.copy(id = "discover-show")) +
            (3..12).map { movie.copy(id = "discover-$it", streamId = it, title = if (intent.getBooleanExtra("long_discovery_title", false)) "The Complete Adventures of a Very Long Movie Title $it" else "Discover Movie $it", progressLabel = null) }),
        HomeContentRow(HomeRowIds.RecentlyAddedMovies, "Recently Added Movies", listOf(movie.copy(id = "recent-movie", progressLabel = null))),
        HomeContentRow(HomeRowIds.RecentlyAddedShows, "Recently Added Shows", listOf(savedShow.copy(id = "recent-show")))
    )
    private val metadata = ConcurrentHashMap<String, Channel<HomeHeroMetadata?>>()
    val requests = CopyOnWriteArrayList<String>()
    private val movie get() = HomeCard("continue-movie", movieTitle, HomeContentType.Movie, "Movies", 1,
        year = "2010", progressLabel = "Resume at 4:15", statusText = "")
    private val episode = HomeCard("continue-show", "A Show (2015)", HomeContentType.SeriesEpisode, "Shows", 201,
        seriesId = 2, progressLabel = "Resume at 8:00", statusText = "")
    private val savedShow = HomeCard("watch-show", "A Show (2015)", HomeContentType.Show, "Shows", 2, seriesId = 2, statusText = "")
    var movieTitle = "A Movie (2010)"
    var destination by mutableStateOf<String?>(null)
    var selectedCardId: String? = null
        private set
    var refreshRequests = 0
        private set
    var freshness by mutableStateOf(CatalogFreshnessState.Stale)
    val holder = HomeShellStateHolder({ HomeLoadResult(HomeShellState.Ready("check", 0,
        if (discoveryMode) discoveryRows().let { if (intent.getBooleanExtra("discovery_only", false)) it.drop(2) else it } else listOf(HomeContentRow(HomeRowIds.ContinueWatching, "Continue Watching", listOf(movie, episode) + (10..17).map { id ->
            movie.copy(id = "continue-extra-$id", title = "Extra Movie $id", streamId = id)
        }),
            HomeContentRow(HomeRowIds.Watchlist, "Watchlist", listOf(savedShow))), movie.id)) },
        loadHeroMetadata = { card ->
            val key = card.heroMetadataKey(); requests.add(key)
            metadata.getOrPut(key) { Channel(Channel.UNLIMITED) }.receive()
        })
    fun complete(key: String = "movie-1", value: HomeHeroMetadata? = HomeHeroMetadata("A dream changes everything.", "Action / Mystery", "2010", 148)) {
        metadata.getOrPut(key) { Channel(Channel.UNLIMITED) }.trySend(value)
    }
    fun returnHome() { destination = null }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("movie_title")?.let { movieTitle = it }
        setContent { LumaireTheme {
            if (destination != null) {
                BackHandler { returnHome() }
                Text(destination!!, Modifier.testTag("home_hero_destination"))
            }
            else AtlasHomeScreen(holder, onSearchSelected = { destination = "Search" },
                onLiveTvSelected = {}, onMoviesSelected = {}, onSeriesSelected = {},
                onHomeCardSelected = { primarySelections.add(it.id); selectedCardId = it.id; destination = if (discoveryMode) "Primary ${it.id}" else it.heroActionLabel() },
                onHomeCardDetailsSelected = { detailSelections.add(it.id); selectedCardId = it.id; destination = if (discoveryMode) "Details ${it.id}" else "Details" },
                onRemoveContinueWatching = if (discoveryMode) null else ({}),
                catalogFreshness = CatalogFreshnessUiState(freshness, freshness != CatalogFreshnessState.Fresh,
                    "Library status", "Administrative counts should stay out of Home", "Refresh Movies/Shows", 0, 100, 50, 0),
                onRefreshCatalogSelected = { refreshRequests++; freshness = CatalogFreshnessState.Syncing })
        } }
    }
}

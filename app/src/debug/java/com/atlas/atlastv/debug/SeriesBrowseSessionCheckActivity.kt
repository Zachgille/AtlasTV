package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.atlas.atlastv.ui.browse.BrowseRow
import com.atlas.atlastv.ui.browse.BrowseSearchButton
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real Shows screen, fake bounded catalogs/episodes, cache-only navigation file, no personal data. */
class SeriesBrowseSessionCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var all: SeriesGridStateHolder; private set
    lateinit var categories: SeriesCategoryBrowser; private set
    lateinit var store: FileSeriesBrowseSessionStore; private set
    lateinit var session: SeriesBrowseSessionStateHolder; private set
    lateinit var sessionFile: File; private set
    lateinit var catalog: SeriesCatalogStateHolder; private set
    val episodeRequests = AtomicInteger()
    var playedEpisodeId: Int? = null; private set
    private var playback by mutableStateOf(false)
    private var selected by mutableStateOf<SeriesItem?>(null)
    private var detailsRequest by mutableIntStateOf(0)
    private fun show(index: Int, category: String = "Shows") = SeriesItem("Show ${index + 1}", category,
        index + 1 + if (category == "Shows") 0 else 1000)
    private fun grid(category: String) = SeriesGridStateHolder(SeriesGridSource { index, id ->
        val total = if (intent.getBooleanExtra("empty_category", false) && category != "Shows") 0 else 513
        val original = id?.minus(1 + if (category == "Shows") 0 else 1000)
        val anchor = original?.takeIf { it in 0 until total } ?: index.coerceIn(0, (total - 1).coerceAtLeast(0))
        val offset = maxOf(0, anchor / 40 - 2) * 40
        SeriesGridWindow(total, offset, anchor, (offset until minOf(total, offset + 200)).map { show(it, category) })
    }, allowEmptyRefresh = true)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = requireNotNull(intent.getStringExtra("check_id")); require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        sessionFile = File(cacheDir, "show-browse-$id.json"); store = FileSeriesBrowseSessionStore(sessionFile)
        all = grid("Shows")
        categories = SeriesCategoryBrowser(SeriesCategoriesStateHolder(SeriesCategoriesSource {
            (0..15).map { SeriesCategory("Drama $it", "Drama $it", 513) }
        }), ::grid)
        val watchlist = SeriesWatchlistBrowser(grid("Saved"))
        session = SeriesBrowseSessionStateHolder(store, "fixture-owner", all, categories, watchlist)
        catalog = SeriesCatalogStateHolder(object : SeriesCatalogSource {
            override suspend fun load(previous: SeriesCatalogState.Ready?) = SeriesCatalogState.Ready("fixture", 0,
                (0 until intent.getIntExtra("shelf_count", 2).coerceIn(2, 18)).map { show(it) }, emptyList(), browseRows = listOf(BrowseRow("shows", "Shows", (0 until intent.getIntExtra("shelf_count", 2).coerceIn(2, 18)).map { show(it) })))
        }, scope, loadEpisodes = {
            episodeRequests.incrementAndGet()
            SeriesEpisodeLoadResult.Success(listOf(
                SeriesEpisodeItem("Pilot", 1, 1, 11, "mp4", ""),
                SeriesEpisodeItem("Second episode", 1, 2, 12, "mp4", ""),
                SeriesEpisodeItem("Next season", 2, 1, 21, "mp4", "")))
        })
        setContent { AtlasTvTheme {
            if (playback) {
                BackHandler { playback = false }
                BrowseSearchButton("Back", { playback = false }, "session_playback_back")
            } else SeriesScreen(catalog, onBack = { finish() }, gridHolder = all,
                categoryBrowser = categories, watchlistBrowser = watchlist, browseSession = session,
                openSelectedSeriesDetailsRequest = detailsRequest, selectedSeriesForDetails = selected,
                onEpisodeSelected = { series, episode ->
                    selected = series; playedEpisodeId = episode.episodeId; catalog.rememberEpisode(episode)
                    detailsRequest++; playback = true
                })
        } }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

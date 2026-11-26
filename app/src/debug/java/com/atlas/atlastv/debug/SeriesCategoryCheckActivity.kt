package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** Real Series/category/grid composables with isolated memory sources; no personal writes/network. */
class SeriesCategoryCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var categoriesReleased = true
    var categoriesFail = false
    var gridsReleased = true
    var gridsFail = false
    var categoryEmpty = false
    var removedCategory: String? = null
    val gridRequests = AtomicInteger()
    val cancelledLoads = AtomicInteger()
    lateinit var shows: SeriesCatalogStateHolder
    val browser = SeriesCategoryBrowser(SeriesCategoriesStateHolder(SeriesCategoriesSource {
        while (!categoriesReleased) delay(10)
        if (categoriesFail) error("private provider details")
        (0..99).map { category -> SeriesCategory(name(category), name(category), if (category == 99) 1 else 513) }.filter { it.id != removedCategory }
    })) { category ->
        val number = category.removePrefix("Category ").toInt()
        SeriesGridStateHolder(SeriesGridSource { index, selectedId ->
            gridRequests.incrementAndGet()
            try {
                while (!gridsReleased) delay(10)
                if (gridsFail) error("private provider details")
                val total = if (categoryEmpty) 0 else if (number == 99) 1 else 513
                val anchor = ((selectedId?.minus(number * 1000 + 1)) ?: index).coerceIn(0, maxOf(0, total - 1))
                val start = maxOf(0, anchor / 40 - 2) * 40
                SeriesGridWindow(total, start, anchor, (start until minOf(start + 200, total)).map { show(number, it) })
            } catch (cancelled: CancellationException) { cancelledLoads.incrementAndGet(); throw cancelled }
        }, allowEmptyRefresh = true)
    }
    private fun name(category: Int) = "Category ${category.toString().padStart(3, '0')}"
    private fun show(category: Int, index: Int) = SeriesItem("Show $category ${index + 1}", name(category), category * 1000 + index + 1)
    var episodesReleased = true
    var episodesFail = false
    val episodeRequests = AtomicInteger()
    val cancelledEpisodes = AtomicInteger()
    private var detailsRequest by mutableIntStateOf(0)
    private var selectedForDetails by mutableStateOf<SeriesItem?>(null)
    private var selectedEpisode by mutableStateOf<SeriesEpisodeItem?>(null)
    private var destination by mutableStateOf(false)
    private var playback by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shows = SeriesCatalogStateHolder(object : SeriesCatalogSource {
            override suspend fun load(previous: SeriesCatalogState.Ready?): SeriesCatalogState {
                val rows = (0..8).map { category -> BrowseRow("category-$category", name(category), listOf(show(category, 0), show(category, 1)), categoryKey = name(category)) }
                return SeriesCatalogState.Ready("check", 0, rows.flatMap { it.items }, emptyList(), browseRows = rows)
            }
        }, scope, loadEpisodes = { id ->
            episodeRequests.incrementAndGet()
            try {
                while (!episodesReleased) delay(10)
                if (episodesFail) error("private episode details")
                SeriesEpisodeLoadResult.Success((1..3).flatMap { season -> (1..4).map { episode ->
                    SeriesEpisodeItem("Show $id - S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}",
                        season, episode, id * 100 + season * 10 + episode, "mp4", "room://episode")
                } })
            } catch (cancelled: CancellationException) { cancelledEpisodes.incrementAndGet(); throw cancelled }
        })
        setContent { AtlasTvTheme {
            when {
                destination -> Text("Home", Modifier.testTag("category_check_destination"))
                playback -> BrowseSearchButton("Back", { playback = false }, "category_check_playback_back")
                else -> SeriesScreen(shows, onBack = { destination = true }, categoryBrowser = browser,
                    openSelectedSeriesDetailsRequest = detailsRequest, selectedSeriesForDetails = selectedForDetails,
                    selectedEpisodeForDetails = selectedEpisode,
                    onEpisodeSelected = { series, episode ->
                        selectedForDetails = series; selectedEpisode = episode
                        shows.rememberSeries(series); shows.rememberEpisode(episode)
                        detailsRequest++; playback = true
                    })
            }
        } }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

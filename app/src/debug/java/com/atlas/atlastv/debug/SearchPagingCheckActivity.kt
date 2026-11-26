package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.movies.MovieItem
import com.atlas.atlastv.ui.series.SeriesItem
import com.atlas.atlastv.ui.search.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

/** Search/return acceptance without personal catalog, account, watch state or network. */
class SearchPagingCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pages = Channel<Boolean>(Channel.UNLIMITED)
    private val stalledSearch = Channel<Unit>()
    private val firstFailure = AtomicBoolean(true)
    private val reduced = AtomicBoolean(false)
    val searches = AtomicInteger()
    val requests = AtomicInteger()
    val cancelledPages = AtomicInteger()
    val cancelledSearches = AtomicInteger()
    private fun item(query: String, row: String, i: Int): UnifiedSearchItem = if (row.endsWith("movies"))
        UnifiedSearchItem.Movie(MovieItem("$query Movie ${i+1}","Movies",i+1,"mp4","room://safe"))
        else UnifiedSearchItem.Show(SeriesItem("$query Show ${i+1}","Shows",i+1))
    val holder = UnifiedSearchStateHolder(scope, { query ->
        searches.incrementAndGet()
        if (query == "slow") try { stalledSearch.receive() } catch (cancelled: CancellationException) { cancelledSearches.incrementAndGet(); throw cancelled }
        if (query == "fail" && firstFailure.getAndSet(false)) error("private diagnostic")
        if (query == "missing") UnifiedSearchState.Ready(query, 0, 0, emptyList())
        else if (reduced.get()) UnifiedSearchState.Ready(query,1,0,listOf(
            UnifiedSearchRow("unified_search_movies","Movies",listOf(item(query,"unified_search_movies",0)),1)))
        else UnifiedSearchState.Ready(query,513,201,listOf(
            UnifiedSearchRow("unified_search_movies","Movies",(0..17).map { item(query,"unified_search_movies",it) },513),
            UnifiedSearchRow("unified_search_shows","Shows",(0..17).map { item(query,"unified_search_shows",it) },201)))
    }, gridFactory = { query,row ->
        SearchResultsGrid(query,row,"Results",CatalogGridStateHolder(CatalogGridSource { index,_ ->
            requests.incrementAndGet()
            try {
                if (!pages.receive()) error("private diagnostic")
                val total = if (reduced.get()) 1 else if (row.endsWith("movies")) 513 else 201
                val anchor = index.coerceIn(0,total-1); val offset = maxOf(0,anchor/40-2)*40
                CatalogGridWindow(total,offset,anchor,(offset until minOf(total,offset+200)).map { item(query,row,it) })
            } catch (cancelled: CancellationException) { cancelledPages.incrementAndGet(); throw cancelled }
        },::searchResultId,"Couldn't load these results. Try again."))
    })
    private var destination by mutableStateOf("search")
    private var selected by mutableStateOf<UnifiedSearchItem?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AtlasTvTheme {
            when (destination) {
                "home" -> Text("Home",Modifier.testTag("search_check_home"))
                "playback" -> BrowseSearchButton("Back",{ destination="detail" },"search_check_playback_back")
                "detail" -> Column {
                    Text(selected!!.title,Modifier.testTag("search_check_detail_title"))
                    BrowseSearchButton("Play",{ destination="playback" },"search_check_play")
                    BrowseSearchButton("Back",{ destination="search" },"search_check_detail_back")
                }
                else -> UnifiedSearchScreen(onBack={ destination="home" },searchStateHolder=holder,
                    onMovieSelected={ selected=UnifiedSearchItem.Movie(it); destination="detail" },
                    onSeriesSelected={ selected=UnifiedSearchItem.Show(it); destination="detail" })
            }
        } }
    }
    fun completePage() { pages.trySend(true) }
    fun failPage() { pages.trySend(false) }
    fun shrinkPreview() { reduced.set(true); holder.invalidateCatalog() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

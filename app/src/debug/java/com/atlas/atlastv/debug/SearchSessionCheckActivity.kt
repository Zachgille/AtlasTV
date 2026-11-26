package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import java.io.File

/** Bounded fake catalog and a private cache-only navigation file; no personal data or network. */
class SearchSessionCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var holder: UnifiedSearchStateHolder; private set
    lateinit var store: FileSearchSessionStore; private set
    lateinit var sessionFile: File; private set
    private var selected by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = requireNotNull(intent.getStringExtra("check_id"))
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        sessionFile = File(cacheDir, "search-session-$id.json")
        store = FileSearchSessionStore(sessionFile)
        val empty = intent.getBooleanExtra("empty_catalog", false)
        fun item(query: String, row: String, index: Int): UnifiedSearchItem = if (row.endsWith("movies"))
            UnifiedSearchItem.Movie(MovieItem("$query Movie ${index + 1}", "Movies", index + 1, "mp4", ""))
            else UnifiedSearchItem.Show(SeriesItem("$query Show ${index + 1}", "Shows", index + 1))
        holder = UnifiedSearchStateHolder(scope, { query -> if (empty) UnifiedSearchState.Ready(query, 0, 0, emptyList()) else UnifiedSearchState.Ready(query, 513, 201, listOf(
            UnifiedSearchRow("unified_search_movies", "Movies", (0..17).map { item(query, "unified_search_movies", it) }, 513),
            UnifiedSearchRow("unified_search_shows", "Shows", (0..17).map { item(query, "unified_search_shows", it) }, 201))) },
            gridFactory = { query, row -> SearchResultsGrid(query, row, "Results", CatalogGridStateHolder(CatalogGridSource { index, id ->
                val total = if (empty) 0 else if (row.endsWith("movies")) 513 else 201
                val anchor = id?.minus(1)?.takeIf { it in 0 until total } ?: index.coerceIn(0, (total - 1).coerceAtLeast(0))
                val offset = maxOf(0, anchor / 40 - 2) * 40
                CatalogGridWindow(total, offset, anchor, (offset until minOf(total, offset + 200)).map { item(query, row, it) })
            }, ::searchResultId, "Couldn't load results.")) }, sessionStore = store, sessionOwner = { "fixture-owner" })
        setContent { AtlasTvTheme {
            val title = selected
            if (title != null) {
                BackHandler { selected = null }
                Column {
                    Text(title, Modifier.testTag("search_session_detail"))
                    BrowseSearchButton("Back", { selected = null }, "search_session_detail_back")
                }
            } else UnifiedSearchScreen(onBack = { finish() }, searchStateHolder = holder,
                onMovieSelected = { selected = it.title }, onSeriesSelected = { selected = it.name })
        } }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

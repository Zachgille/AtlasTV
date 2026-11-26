package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.atlas.atlastv.ui.browse.BrowseRow
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.*
import java.io.File

/** Real Movies screen with fake bounded catalogs and a cache-only session file. */
class MovieBrowseSessionCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var all: MovieGridStateHolder; private set
    lateinit var categories: MovieCategoryBrowser; private set
    lateinit var store: FileMovieBrowseSessionStore; private set
    lateinit var session: MovieBrowseSessionStateHolder; private set
    lateinit var sessionFile: File; private set
    private fun movie(index: Int, category: String = "Movies") = MovieItem("Movie ${index + 1}", category,
        index + 1 + if (category == "Movies") 0 else 1000, "mp4", "")
    private fun grid(category: String) = MovieGridStateHolder(MovieGridSource { index, id ->
        val total = if (intent.getBooleanExtra("empty_category", false) && category != "Movies") 0 else 513
        val original = id?.minus(1 + if (category == "Movies") 0 else 1000)
        val anchor = original?.takeIf { it in 0 until total } ?: index.coerceIn(0, (total - 1).coerceAtLeast(0))
        val offset = maxOf(0, anchor / 40 - 2) * 40
        MovieGridWindow(total, offset, anchor, (offset until minOf(total, offset + 200)).map { movie(it, category) })
    }, allowEmptyRefresh = true)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = requireNotNull(intent.getStringExtra("check_id")); require(id.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        sessionFile = File(cacheDir, "movie-browse-$id.json"); store = FileMovieBrowseSessionStore(sessionFile)
        all = grid("Movies")
        categories = MovieCategoryBrowser(MovieCategoriesStateHolder(MovieCategoriesSource {
            (0..15).map { MovieCategory("Drama $it", "Drama $it", 513) }
        }), ::grid)
        val watchlist = MovieWatchlistBrowser(grid("Saved"))
        session = MovieBrowseSessionStateHolder(store, "fixture-owner", all, categories, watchlist)
        val catalog = MoviesCatalogStateHolder(object : MoviesCatalogSource {
            override suspend fun load(previous: MoviesCatalogState.Ready?) = MoviesCatalogState.Ready("fixture", 0,
                (0 until intent.getIntExtra("shelf_count", 2).coerceIn(2, 18)).map { movie(it) }, emptyList(), browseRows = listOf(BrowseRow("movies", "Movies", (0 until intent.getIntExtra("shelf_count", 2).coerceIn(2, 18)).map { movie(it) })))
        }, scope)
        setContent { AtlasTvTheme {
            MoviesScreen(catalog, onBack = { finish() }, onMovieSelected = {}, gridHolder = all,
                categoryBrowser = categories, watchlistBrowser = watchlist, browseSession = session)
        } }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

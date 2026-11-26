package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.tv.material3.Text
import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.theme.LumaireTheme
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger

/** Real Movies/category/grid composables with isolated memory sources; no personal writes/network. */
class MovieCategoryCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    var categoriesReleased = true
    var categoriesFail = false
    var gridsReleased = true
    var gridsFail = false
    var categoryEmpty = false
    var removedCategory: String? = null
    val gridRequests = AtomicInteger()
    val cancelledLoads = AtomicInteger()
    lateinit var movies: MoviesCatalogStateHolder
    val browser = MovieCategoryBrowser(MovieCategoriesStateHolder(MovieCategoriesSource {
        while (!categoriesReleased) delay(10)
        if (categoriesFail) error("private provider details")
        (0..99).map { category -> MovieCategory(name(category), name(category), if (category == 99) 1 else 513) }.filter { it.id != removedCategory }
    })) { category ->
        val number = category.removePrefix("Category ").toInt()
        MovieGridStateHolder(MovieGridSource { index, selectedId ->
            gridRequests.incrementAndGet()
            try {
                while (!gridsReleased) delay(10)
                if (gridsFail) error("private provider details")
                val total = if (categoryEmpty) 0 else if (number == 99) 1 else 513
                val anchor = ((selectedId?.minus(number * 1000 + 1)) ?: index).coerceIn(0, maxOf(0, total - 1))
                val start = maxOf(0, anchor / 40 - 2) * 40
                MovieGridWindow(total, start, anchor, (start until minOf(start + 200, total)).map { movie(number, it) })
            } catch (cancelled: CancellationException) { cancelledLoads.incrementAndGet(); throw cancelled }
        }, allowEmptyRefresh = true)
    }
    private fun name(category: Int) = "Category ${category.toString().padStart(3, '0')}"
    private fun movie(category: Int, index: Int) = MovieItem("Movie $category ${index + 1}", name(category), category * 1000 + index + 1, "mp4", "room://safe")
    private var destination by mutableStateOf(false)
    private var playback by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        movies = MoviesCatalogStateHolder(object : MoviesCatalogSource {
            override suspend fun load(previous: MoviesCatalogState.Ready?): MoviesCatalogState {
                val rows = (0..8).map { category -> BrowseRow("category-$category", name(category), listOf(movie(category, 0), movie(category, 1)), categoryKey = name(category)) }
                return MoviesCatalogState.Ready("check", 0, rows.flatMap { it.items }, emptyList(), browseRows = rows)
            }
        }, scope)
        setContent { LumaireTheme {
            when {
                destination -> Text("Home", Modifier.testTag("category_check_destination"))
                playback -> BrowseSearchButton("Back", { playback = false }, "category_check_playback_back")
                else -> MoviesScreen(movies, onBack = { destination = true }, onMovieSelected = { playback = true }, categoryBrowser = browser)
            }
        } }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

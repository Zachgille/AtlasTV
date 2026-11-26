package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import com.atlas.atlastv.ui.browse.*
import com.atlas.atlastv.ui.media.AtlasImageRef
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

/** Normal shared grid with isolated bounded memory data; no account, Room or video writes. */
class GridHeaderCheckActivity : ComponentActivity() {
    data class Item(val id: Int, val name: String)
    val requests = AtomicInteger()
    @Volatile var total = 513
    @Volatile var failReads = false
    @Volatile var blockReads = false
    val release = CompletableDeferred<Unit>()
    var revision by mutableStateOf(0L)
    var headerTitle by mutableStateOf("All Movies")
    var destination by mutableStateOf<Int?>(null)
    val grid = KeyedCatalogGridStateHolder(KeyedCatalogGridSource<Item, Int> { index, _ ->
        requests.incrementAndGet()
        if (blockReads) release.await()
        if (failReads) error("isolated read failure")
        val anchor = index.coerceIn(0, (total - 1).coerceAtLeast(0))
        val start = maxOf(0, anchor / 40 - 2) * 40
        CatalogGridWindow(total, start, anchor, (start until minOf(start + 200, total)).map { Item(it, "Movie ${it + 1}") })
    }, { it.id }, "Couldn't load movies. Try again.", allowEmptyRefresh = true)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AtlasTvTheme {
            if (destination != null) {
                BackHandler { destination = null }
                BrowseSearchButton("Back", { destination = null }, "grid_header_detail_back")
            } else CatalogGridScreen(grid, revision, headerTitle, "movie", "movies", "grid_header",
                { it.name }, { "Movies" }, { AtlasImageRef.Missing }, { finish() }, { destination = it.id })
        } }
    }
}

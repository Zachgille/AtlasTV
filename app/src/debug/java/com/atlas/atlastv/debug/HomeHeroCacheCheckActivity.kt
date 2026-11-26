package com.atlas.atlastv.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.room.Room
import androidx.tv.material3.Text
import com.atlas.atlastv.data.db.AtlasDatabase
import com.atlas.atlastv.ui.home.*
import com.atlas.atlastv.ui.settings.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Cache-only real Room, fake metadata provider and destinations; no personal library or video. */
class HomeHeroCacheCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var db: AtlasDatabase
    private lateinit var file: File
    lateinit var holder: HomeShellStateHolder; private set
    private var ready by mutableStateOf(false)
    var destination by mutableStateOf<String?>(null); private set
    var activeAccount by mutableStateOf(XtreamCredentials("http://home-cache.invalid", "opaque", "not-real"))
    val requests = CopyOnWriteArrayList<String>()
    val release = CompletableDeferred<Unit>()
    var failRefresh = true
    var emptyRefresh = false
    var movieTitle = "A Movie (2020)"
    private val movie get() = HomeCard("movie", movieTitle, HomeContentType.Movie, "Movies", 1, year = "2020", statusText = "")
    private val show = HomeCard("show", "A Show (2015)", HomeContentType.Show, "Shows", 1, seriesId = 1, statusText = "")
    private val episode = HomeCard("episode", "A Show (2015)", HomeContentType.SeriesEpisode, "Shows", 201, seriesId = 1,
        progressLabel = "Resume", statusText = "")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        file = File(savedInstanceState?.getString("cacheFile") ?: File(cacheDir, "home-hero-${UUID.randomUUID()}.db").path)
        db = Room.databaseBuilder(this, AtlasDatabase::class.java, file.absolutePath).build()
        val store = RoomHomeHeroStore(db.homeHeroCacheDao())
        if (!intent.getBooleanExtra("blocked", false)) release.complete(Unit)
        val repo = HomeHeroRepository(store, { _, card ->
            requests.add(card.heroMetadataKey()); release.await()
            if (failRefresh) null else if (emptyRefresh) HomeHeroMetadata() else HomeHeroMetadata("Updated ${card.heroMetadataKey()}.")
        }, { 1 })
        holder = HomeShellStateHolder({ HomeLoadResult(HomeShellState.Ready("check", 0,
            listOf(HomeContentRow(HomeRowIds.ContinueWatching, "Continue Watching", listOf(movie, show, episode) +
                (10..17).map { movie.copy(id = "extra-$it", title = "Movie $it", streamId = it) })), movie.id)) },
            loadHeroMetadata = { repo.refresh(activeAccount, it) }, loadSavedHeroMetadata = { repo.saved(activeAccount, it) },
            savedHeroNeedsRefresh = repo::stale, heroContext = { providerHomeHeroKey(activeAccount) })
        scope.launch {
            withContext(Dispatchers.IO) {
                if (savedInstanceState == null && !intent.getBooleanExtra("no_cache", false)) {
                    val age = if (intent.getBooleanExtra("stale", false)) HomeHeroRepository.FreshMs + 1 else 0
                    val now = System.currentTimeMillis() - age
                    for (card in listOf(movie, show)) store.save(SavedHomeHero(providerHomeHeroKey(activeAccount), card.heroMetadataKey(),
                        now, now, 1, HomeHeroMetadata("Saved ${card.heroMetadataKey()}.", "Drama", if (card == show) "2015" else "2020", if (card == show) null else 148)))
                }
            }; ready = true
        }
        setContent { AtlasTvTheme {
            if (!ready) Text("Loading local check", Modifier.testTag("home_cache_loading"))
            else if (destination != null) {
                BackHandler { destination = null }
                Text(destination!!, Modifier.testTag("home_cache_destination"))
            } else AtlasHomeScreen(holder, onSearchSelected = { destination = "Search" },
                onLiveTvSelected = {}, onMoviesSelected = {}, onSeriesSelected = {},
                onHomeCardSelected = { destination = it.id }, onHomeCardDetailsSelected = { destination = "Details ${it.id}" },
                catalogFreshness = CatalogFreshnessUiState(CatalogFreshnessState.Fresh, false,
                    "Library status", "", "Refresh Library", 0, 1, 1, 0),
                onRefreshCatalogSelected = { holder.refresh(clearHeroMetadata = true) })
        } }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("cacheFile", file.path); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        scope.cancel(); if (::db.isInitialized) db.close()
        if (isFinishing && ::file.isInitialized) for (suffix in listOf("", "-wal", "-shm")) File(file.path + suffix).delete()
        super.onDestroy()
    }
}

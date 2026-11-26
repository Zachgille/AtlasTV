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
import com.atlas.atlastv.ui.series.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Real Room cache with an isolated database and fake provider. No personal data or video. */
class ShowDetailsCacheCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var database: AtlasDatabase
    private lateinit var file: File
    lateinit var holder: SeriesCatalogStateHolder; private set
    private var ready by mutableStateOf(false)
    private var destination by mutableStateOf<Int?>(null)
    private var detailRequest by mutableStateOf(1)
    var openedEpisodeId: Int? = null; private set
    @Volatile var downloads = 0; private set
    var failRefresh = true
    var emptyRefresh = false
    var removedEpisodeId: Int? = null
    var removedSeason: Int? = null
    val release = CompletableDeferred<Unit>()
    private var showTitle = "A Clean Show (2020)"
    val show get() = SeriesItem(showTitle, "Drama", 7)
    val account = XtreamCredentials("http://cache-check.invalid", "opaque", "not-a-real-account")
    val episodes = (1..24).flatMap { season -> (1..64).map { number ->
        SeriesEpisodeItem("A Clean Show - S${season}E$number", season, number, (season - 1) * 64 + number, "mp4", "", 2_520_000)
    } }
    private val good = SeriesEpisodeLoadResult.Success(episodes, SeriesShowMetadata("A saved synopsis.", "Drama", "2020"))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("show_title")?.let { showTitle = it }
        file = File(savedInstanceState?.getString("cacheFile") ?: File(cacheDir, "show-detail-${UUID.randomUUID()}.db").path)
        database = Room.databaseBuilder(this, AtlasDatabase::class.java, file.absolutePath).build()
        val store = RoomShowDetailsStore(database.showDetailsCacheDao())
        if (!intent.getBooleanExtra("blocked", false)) release.complete(Unit)
        val repository = ShowDetailsRepository(store, { _, _ ->
            downloads++; release.await()
            if (failRefresh) SeriesEpisodeLoadResult.Failure("Unavailable")
            else if (emptyRefresh) SeriesEpisodeLoadResult.Success(emptyList())
            else good.copy(episodes = episodes.filter { it.episodeId != removedEpisodeId && it.seasonNumber != removedSeason } + SeriesEpisodeItem("New episode", 24, 65, 1537, "mp4", "", 2_520_000), metadata = SeriesShowMetadata())
        }, { 1 })
        holder = SeriesCatalogStateHolder(object : SeriesCatalogSource {
            override suspend fun load(previous: SeriesCatalogState.Ready?) = SeriesCatalogState.Ready("check", 0, listOf(show), emptyList())
        }, scope, loadEpisodes = { repository.refresh(account, it) }, loadSavedEpisodes = { repository.saved(account, it) },
            savedNeedsRefresh = repository::stale, episodeContext = { providerDetailsKey(account) })
        scope.launch {
            withContext(Dispatchers.IO) {
                if (savedInstanceState == null && !intent.getBooleanExtra("no_cache", false)) {
                    val age = if (intent.getBooleanExtra("stale", false)) ShowDetailsRepository.FreshMs + 1 else 0
                    val now = System.currentTimeMillis() - age
                    store.save(SavedShowDetails(providerDetailsKey(account), 7, now, now, 1, good))
                }
            }
            ready = true
        }
        setContent { AtlasTvTheme {
            if (!ready) Text("Loading local check", Modifier.testTag("show_cache_check_loading"))
            else if (destination != null) {
                BackHandler { destination = null }
                Text("Selected episode", Modifier.testTag("show_cache_destination"))
            } else SeriesScreen(holder, onBack = { finish() }, selectedSeriesForDetails = show,
                openSelectedSeriesDetailsRequest = detailRequest,
                onEpisodeSelected = { _, episode -> openedEpisodeId = episode.episodeId; destination = episode.episodeId; detailRequest++ })
        } }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("cacheFile", file.path); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        scope.cancel(); if (::database.isInitialized) database.close()
        if (isFinishing && ::file.isInitialized) for (suffix in listOf("", "-wal", "-shm")) File(file.path + suffix).delete()
        super.onDestroy()
    }
}

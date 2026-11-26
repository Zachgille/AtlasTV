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
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

/** Isolated real Room, fake provider and destination; no personal data or provider video. */
class MovieDetailsCacheCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var database: AtlasDatabase
    private lateinit var file: File
    lateinit var holder: MoviesCatalogStateHolder; private set
    private var ready by mutableStateOf(false)
    private var destination by mutableStateOf(false)
    private var detailRequest by mutableStateOf(1)
    var openedMovieId: Int? = null; private set
    @Volatile var downloads = 0; private set
    var failRefresh = true
    var emptyRefresh = false
    val release = CompletableDeferred<Unit>()
    private val account = XtreamCredentials("http://movie-cache.invalid", "opaque", "not-a-real-account")
    var activeAccount by mutableStateOf(account)
    var movieTitle by mutableStateOf("A Movie (2020)")
    var unavailable by mutableStateOf(false)
    private val movie get() = MovieItem(movieTitle, "Drama", 1, "mp4", "", available = !unavailable)
    private val metadata = MovieMetadata("A saved synopsis.", "Drama", "2020", 148)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent.getStringExtra("movie_title")?.let { movieTitle = it }
        file = File(savedInstanceState?.getString("cacheFile") ?: File(cacheDir, "movie-detail-${UUID.randomUUID()}.db").path)
        database = Room.databaseBuilder(this, AtlasDatabase::class.java, file.absolutePath).build()
        unavailable = intent.getBooleanExtra("unavailable", false)
        val store = RoomMovieDetailsStore(database.movieDetailsCacheDao())
        if (!intent.getBooleanExtra("blocked", false)) release.complete(Unit)
        val repo = MovieDetailsRepository(store, { _, _ ->
            downloads++; release.await()
            if (failRefresh) null else if (emptyRefresh) MovieMetadata() else MovieMetadata("A refreshed synopsis.")
        }, { 1 })
        holder = MoviesCatalogStateHolder(object : MoviesCatalogSource {
            override suspend fun load(previous: MoviesCatalogState.Ready?) = MoviesCatalogState.Ready("check", 0,
                listOf(movie), emptyList(), watchlistStreamIds = if (unavailable) setOf(1) else emptySet())
        }, scope, loadMovieInfo = { repo.refresh(activeAccount, it) }, loadSavedMovieInfo = { repo.saved(activeAccount, it) },
            savedNeedsRefresh = repo::stale, movieInfoContext = { providerMovieDetailsKey(activeAccount) })
        scope.launch {
            withContext(Dispatchers.IO) {
                if (savedInstanceState == null && !intent.getBooleanExtra("no_cache", false)) {
                    val age = if (intent.getBooleanExtra("stale", false)) MovieDetailsRepository.FreshMs + 1 else 0
                    val now = System.currentTimeMillis() - age
                    store.save(SavedMovieDetails(providerMovieDetailsKey(account), 1, now, now, 1, metadata))
                }
            }; ready = true
        }
        setContent { AtlasTvTheme {
            if (!ready) Text("Loading local check", Modifier.testTag("movie_cache_loading"))
            else if (destination) {
                BackHandler { destination = false }
                Text("Selected Movie", Modifier.testTag("movie_cache_destination"))
            } else MoviesScreen(holder, onBack = { finish() }, selectedMovieForDetails = movie,
                openSelectedMovieDetailsRequest = detailRequest,
                onMovieSelected = { openedMovieId = it.streamId; destination = true; detailRequest++ })
        } }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("cacheFile", file.path); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        scope.cancel(); if (::database.isInitialized) database.close()
        if (isFinishing && ::file.isInitialized) for (suffix in listOf("", "-wal", "-shm")) File(file.path + suffix).delete()
        super.onDestroy()
    }
}

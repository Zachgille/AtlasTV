package com.atlas.atlastv.debug

import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.room.Room
import androidx.tv.material3.Text
import com.atlas.atlastv.data.db.AtlasDatabase
import com.atlas.atlastv.ui.components.LocalPosterArtworkLoader
import com.atlas.atlastv.ui.home.*
import com.atlas.atlastv.ui.media.*
import com.atlas.atlastv.ui.movies.*
import com.atlas.atlastv.ui.theme.AtlasTvTheme
import com.atlas.atlastv.xtream.model.XtreamCredentials
import kotlinx.coroutines.*
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Actual Home and production saved-detail boundary with isolated Room, PNG bytes and image decoder. */
class HomeCardPostersCheckActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var db: AtlasDatabase
    private lateinit var directory: File
    lateinit var holder: HomeShellStateHolder; private set
    var account by mutableStateOf(XtreamCredentials("http://posters.invalid", "opaque", "not-real"))
    var destination by mutableStateOf<String?>(null); private set
    private var ready by mutableStateOf(false)
    val release = CompletableDeferred<Unit>()
    val calls = CopyOnWriteArrayList<String>()
    val blue = ref(11); val red = ref(12); val green = ref(13); val failed = ref(14); val newestBad = ref(15)
    private fun ref(n: Int) = atlasImageRefFromRaw("provider-image://sha256/" + n.toString(16).padStart(64, '0'))
    private val cards get() = listOf(
        HomeCard("movie-1", "A Movie (2020)", HomeContentType.Movie, "Movies", 1, progressLabel = "Resume", statusText = "", cardProgress = com.atlas.atlastv.ui.catalog.CardWatchProgress(.25f, "45m left")),
        HomeCard("episode-99", "A Show (2015)", HomeContentType.SeriesEpisode, "Shows", 99, 1, progressLabel = "Resume", statusText = "", posterImage = failed, cardProgress = com.atlas.atlastv.ui.catalog.CardWatchProgress(.5f, "22m left", "S1 E2")),
        HomeCard("movie-2", "Working Poster", HomeContentType.Movie, "Movies", 2, progressLabel = "Resume", statusText = "", posterImage = green, cardProgress = com.atlas.atlastv.ui.catalog.CardWatchProgress(.1f, "1h left")),
        HomeCard("movie-3", "No Saved Poster", HomeContentType.Movie, "Movies", 3, statusText = "")
    ) + (10..17).map { HomeCard("movie-$it", "Movie $it", HomeContentType.Movie, "Movies", it, statusText = "") }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        directory = File(cacheDir, "poster-check-${UUID.randomUUID()}").apply { mkdirs() }
        db = Room.inMemoryDatabaseBuilder(this, AtlasDatabase::class.java).build()
        val index = AtlasProviderImageIndex(File(directory, "index.json"))
        val imageLoader: suspend (String, ArtworkSize) -> androidx.compose.ui.graphics.ImageBitmap? = { token, size ->
            calls.add(token)
            AtlasProviderImageLoader.load(token, directory, index, size)
        }
        val repo = HomeHeroRepository.create(this, HomeHeroMetadataLoader(), db)
        if (!intent.getBooleanExtra("blocked", false)) release.complete(Unit)
        holder = HomeShellStateHolder({ HomeLoadResult(HomeShellState.Ready("check", null,
            listOf(HomeContentRow(HomeRowIds.ContinueWatching, "Continue Watching", cards)), cards.first().id)) },
            heroContext = { providerHomeHeroKey(account) }, loadSavedPosters = { card -> release.await(); repo.savedPosters(account, card) })
        scope.launch {
            withContext(Dispatchers.IO) {
                val cache = AtlasProviderImageCache(File(directory, AtlasProviderImageCache.DirectoryName))
                for ((ref, color) in listOf(blue to Color.rgb(35, 95, 185), red to Color.rgb(165, 45, 60), green to Color.rgb(35, 150, 90))) {
                    val bitmap = Bitmap.createBitmap(96, 144, Bitmap.Config.ARGB_8888); bitmap.eraseColor(color)
                    val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray(); bitmap.recycle()
                    check(cache.write(ref.providerImageToken, bytes))
                }
                val now = System.currentTimeMillis()
                val movies = RoomMovieDetailsStore(db.movieDetailsCacheDao())
                for (id in listOf(1, 2)) movies.save(SavedMovieDetails(providerMovieDetailsKey(account), id, now, now, null, MovieMetadata(poster = blue)))
                // A newer saved preview fails, then the valid older parent-Show details must win.
                RoomHomeHeroStore(db.homeHeroCacheDao()).save(SavedHomeHero(providerHomeHeroKey(account), "show-1", now + 1, now + 1, null, HomeHeroMetadata(poster = newestBad)))
                com.atlas.atlastv.ui.series.RoomShowDetailsStore(db.showDetailsCacheDao()).save(
                    com.atlas.atlastv.ui.series.SavedShowDetails(com.atlas.atlastv.ui.series.providerDetailsKey(account), 1, now, now, null,
                        com.atlas.atlastv.ui.series.SeriesEpisodeLoadResult.Success(
                            listOf(com.atlas.atlastv.ui.series.SeriesEpisodeItem("Episode", 1, 2, 99, "mp4", "")),
                            com.atlas.atlastv.ui.series.SeriesShowMetadata(poster = red))))
            }
            ready = true
        }
        setContent { AtlasTvTheme { CompositionLocalProvider(LocalPosterArtworkLoader provides imageLoader) {
            if (!ready) Text("Loading local check", Modifier.testTag("poster_check_loading"))
            else if (destination != null) { BackHandler { destination = null }; Text(destination!!, Modifier.testTag("poster_check_destination")) }
            else AtlasHomeScreen(holder, onSearchSelected = { destination = "Search" }, onLiveTvSelected = {}, onMoviesSelected = {}, onSeriesSelected = {},
                onHomeCardSelected = { destination = it.id }, onHomeCardDetailsSelected = { destination = "Details ${it.id}" })
        } } }
    }
    override fun onDestroy() {
        scope.cancel(); if (::db.isInitialized) db.close()
        if (::directory.isInitialized) directory.deleteRecursively()
        super.onDestroy()
    }
}
